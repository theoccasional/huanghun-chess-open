package com.yk.xiangqi.link;

import android.content.Context;
import android.util.Log;

import com.pikafish.chess.heavy.EngineLink;
import com.pikafish.chess.heavy.HeavyStore;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★★★ 远程识别：把这台手机的 ONNX 前向整个交给配对上的辎重包端（电脑）去跑。★★★
 *
 * <p>连线时手机仍然负责取帧、采样、融合与裁决（这些逻辑一行不改），但最耗电的那一步
 * —— 逐格模型与整盘模型的推理 —— 被搬到电脑上：手机只把已经预处理好的张量发过去，
 * 电脑跑同一套模型、把 logits 发回来。模型、输入、输出都与本机推理同一份，因此识别结果
 * 完全等价，而手机的 CPU 与电池得以休息。</p>
 *
 * <p>线协议与辎重包端 {@code recognize.go} 的 {@code /recognize} 严格一致（小端）：</p>
 * <pre>
 *   请求：magic(4)="HXR1" + kind(1，0=逐格 1=整盘) + 保留(4) + count(4) + count 个 float32
 *   应答：magic(4)="HXR1" + status(1，0=成功 1=失败) + 保留(3) + count(4) + count 个 float32 / 错误文本
 * </pre>
 *
 * <p><b>兜底：</b>任何一步出问题（没配对、对方服务不在、超时、格式不符），{@link #run}
 * 一律返回 {@code null}，调用方随即退回本机 ONNX 推理 —— 识别照常，绝不因远程而中断。
 * 失败后还会进入一小段「冷却」，避免每帧都去做注定失败的请求白白耗电。</p>
 */
public final class RemoteRecognizer {

    private static final String TAG = "XiangqiLink";

    /** 请求/应答的魔数，与电脑端 recognize.go 的 recogMagic 一致。 */
    private static final byte[] MAGIC = "HXR1".getBytes(StandardCharsets.US_ASCII);
    /** 逐格模型（piece_classifier.onnx）推理。 */
    public static final int KIND_PIECE = 0;
    /** 整盘模型（layout_model.onnx）推理。 */
    public static final int KIND_LAYOUT = 1;

    private static final int CONNECT_TIMEOUT_MS = 1500;
    /** 电脑端整盘推理约几百毫秒，读超时给足余量，避免偶发卡顿被误判成不可用。 */
    private static final int READ_TIMEOUT_MS = 8000;
    /** 一次失败后的冷却时长：这段时间内直接走本机推理，不反复发起注定失败的请求。 */
    private static final long COOLDOWN_MS = 15000L;
    /** 连续失败打日志的上限，避免刷屏。 */
    private static final int MAX_LOGGED_FAILURES = 3;

    private final String endpoint;
    private final AtomicInteger failures = new AtomicInteger();
    /** 冷却截止时刻（System.nanoTime）；0 表示当前可用。 */
    private volatile long cooldownUntilNanos;

    private RemoteRecognizer(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * 若当前处于「异地引擎」档位且辎重包是远程直读，返回一个可用的远程识别器；
     * 否则返回 {@code null}（调用方照常走本机推理）。绝不抛异常。
     */
    public static RemoteRecognizer create(Context context) {
        try {
            if (!EngineLink.remoteUsable(context))
                return null;
            String url = HeavyStore.remoteUrl(context);
            String base = baseOf(url);
            if (base == null)
                return null;
            return new RemoteRecognizer(base + "/recognize");
        } catch (Exception error) {
            Log.w(TAG, "远程识别不可用，改用本机推理", error);
            return null;
        }
    }

    /** 人类可读的识别归属描述，供连线信息页展示。 */
    public static String describe(Context context) {
        RemoteRecognizer remote = create(context);
        if (remote == null)
            return "本机（手机自己跑识别模型）";
        return remote.usable()
                ? "远程（辎重包端代跑识别模型，手机不发烫）"
                : "远程（暂不可用，正回退本机识别）";
    }

    /** 从辎重包地址里取出 {@code scheme://host:port} 基址；取不到返回 null。 */
    private static String baseOf(String url) {
        if (url == null)
            return null;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String authority = uri.getAuthority();
            if (scheme == null || authority == null)
                return null;
            return scheme + "://" + authority;
        } catch (Exception error) {
            return null;
        }
    }

    /** 当前是否值得发起远程请求（未处于冷却中）。 */
    public boolean usable() {
        long until = cooldownUntilNanos;
        return until == 0L || System.nanoTime() >= until;
    }

    /**
     * 把一份已预处理好的张量发到电脑端跑模型，返回展平的 float32 输出。
     *
     * @param kind        模型种类（{@link #KIND_PIECE} / {@link #KIND_LAYOUT}）
     * @param input       与电脑端同一份输入张量；方法内部会 rewind，调用后可安全复用
     * @param expectedOut 期望的输出元素个数；不匹配即视为失败（防版本错位）并退回本机
     * @return 展平输出；任何失败返回 {@code null}
     */
    public float[] run(int kind, FloatBuffer input, int expectedOut) {
        if (!usable())
            return null;
        input.rewind();
        int count = input.remaining();
        if (count <= 0)
            return null;
        byte[] body = encode(kind, input, count);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.getOutputStream().write(body);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                penalize("HTTP " + code);
                return null;
            }
            byte[] reply = readAll(connection.getInputStream());
            float[] result = parseReply(reply, expectedOut);
            if (result == null) {
                penalize("应答格式不符");
                return null;
            }
            failures.set(0);
            return result;
        } catch (Exception error) {
            penalize(error.toString());
            return null;
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    /** 组装请求字节：13 字节头 + 小端 float32 张量。 */
    private static byte[] encode(int kind, FloatBuffer input, int count) {
        ByteBuffer buffer = ByteBuffer.allocate(13 + count * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(MAGIC);
        buffer.put((byte) kind);
        buffer.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        buffer.putInt(count);
        // slice 出的视图与主缓冲共享底层数组，写入即落在 body 的张量区。
        buffer.position(13);
        FloatBuffer floats = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        floats.put(input);
        return buffer.array();
    }

    /** 解析应答；成功返回展平输出，失败（status≠0 / 长度不符 / 期望不符）返回 null。 */
    private static float[] parseReply(byte[] reply, int expectedOut) {
        if (reply == null || reply.length < 13)
            return null;
        for (int i = 0; i < 4; i++)
            if (reply[i] != MAGIC[i])
                return null;
        int status = reply[4] & 0xff;
        int count = ByteBuffer.wrap(reply, 9, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (status != 0) {
            String message = count > 0 && count <= reply.length - 13
                    ? new String(reply, 13, count, StandardCharsets.UTF_8) : "未知错误";
            throw new RemoteFailure(message);
        }
        if (count != expectedOut || reply.length < 13 + count * 4)
            return null;
        float[] out = new float[count];
        ByteBuffer floats = ByteBuffer.wrap(reply, 13, count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++)
            out[i] = floats.getFloat();
        return out;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        if (input == null)
            return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            for (int read; (read = in.read(chunk)) >= 0; )
                out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    /** 记一次失败：进入冷却，并限次打日志。 */
    private void penalize(String reason) {
        cooldownUntilNanos = System.nanoTime() + COOLDOWN_MS * 1_000_000L;
        if (failures.incrementAndGet() <= MAX_LOGGED_FAILURES)
            Log.w(TAG, "远程识别失败（" + reason + "），暂回本机推理");
    }

    /** 电脑端回传的业务错误（模型加载失败等）；被 run 内部捕获，不外泄。 */
    private static final class RemoteFailure extends RuntimeException {
        RemoteFailure(String message) {
            super(message);
        }
    }
}
