package com.yk.xiangqi.link;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import com.yk.xiangqi.core.Board;
import com.yk.xiangqi.core.Side;

import com.pikafish.chess.heavy.HeavyStore;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * 整盘 SwinV2-tiny 识别模型（layout_model.onnx）的 ONNX Runtime CPU 推理器。
 *
 * <p>它不逐格裁剪，而是把棋盘整体缩到固定画布后一次性给出 9×10=90 个交叉点的
 * 16 类分布。整盘上下文能显著降低“张冠李戴”（棋子类别认错、占位却看着正确）的
 * 概率，因此与既有逐格模型形成互补的叠加识别。</p>
 *
 * <p>输入画布与训练侧保持一致：棋盘活动区（9 列 × 10 行交叉点）在 280×315 画布内
 * 各留约 17.5 像素外边距，等价于参考实现的 450×500 加 50 内边距后 centerCrop 400×450
 * 再缩放 0.7 倍。像素按 ImageNet 均值方差做 RGB 归一化，且不除以 255。</p>
 *
 * <p><b>阴影/渐变兜底：</b>{@link #recognizeEnhanced} 用整盘 z-score 局部对比归一替换
 * 标准 ImageNet 归一：每通道减去全盘均值、除以全盘标准差，把整盘投影/渐变底色抹平，
 * 让笔画成为主信号。与逐格模型的增强路径配对，专门处理带阴影/斜面/渐变底色的棋盘；
 * 仅在标准路径失败时由 {@link PieceRecognizer} 调用，已经能稳定识别的棋盘不受影响。</p>
 */
public final class LayoutRecognizer implements AutoCloseable {
    public static final int WIDTH = 280;
    public static final int HEIGHT = 315;
    /** 0 空点、1 遮挡、2..8 红方帅仕相马车炮兵、9..15 黑方将士象马车炮卒。 */
    public static final int CLASSES = 16;
    private static final int CHANNELS = 3;
    private static final int PIXELS = WIDTH * HEIGHT;
    private static final int INPUT_FLOATS = CHANNELS * PIXELS;
    private static final long[] SHAPE = {1, CHANNELS, HEIGHT, WIDTH};
    private static final float[] MEAN = {123.675f, 116.28f, 103.53f};
    private static final float[] STD = {58.395f, 57.12f, 57.375f};
    /** 活动区外扩量：与参考实现 centerCrop 后一致，横向约半格、纵向约半行。 */
    private static final float MARGIN_X = (50f - 25f) * 0.7f;
    private static final float MARGIN_Y = (50f - 25f) * 0.7f;
    /** 单列/单行间距在画布上的像素数：350×0.7/8 与 400×0.7/9。 */
    private static final float PIXELS_PER_COLUMN = 350f * 0.7f / 8f;
    private static final float PIXELS_PER_ROW = 400f * 0.7f / 9f;
    /** 局部对比归一后的幅值上限，避免离群像素把整盘拉飞。 */
    private static final float ENHANCE_CLAMP = 3f;
    /** 局部标准差下限，避免平坦盘面除以极小值放大噪声。 */
    private static final float ENHANCE_MIN_STD = 10f;

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    /** 远程识别器：异地引擎档位下由电脑代跑整盘模型；未配对时为 null，走本机。 */
    private final RemoteRecognizer remote;
    private final FloatBuffer modelInput = ByteBuffer.allocateDirect(INPUT_FLOATS * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] preparedInput = new float[INPUT_FLOATS];
    /** 局部对比归一后的输入缓冲；标准路径失败时启用，避免覆盖标准输入。 */
    private final float[] enhancedInput = new float[INPUT_FLOATS];
    private final FloatBuffer lastInput = ByteBuffer.allocateDirect(INPUT_FLOATS * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private SamplingPlan samplingPlan;
    private boolean hasLastInput;
    /** 黑方在下时输入画布旋转 180°，使整盘模型恒看到红方在下，与其训练朝向一致。 */
    private boolean rotated;

    public LayoutRecognizer(Context context, int modelThreads) throws Exception {
        this(context, modelThreads, null);
    }

    LayoutRecognizer(Context context, int modelThreads, RemoteRecognizer remote) throws Exception {
        this.remote = remote;
        byte[] model;
        try (InputStream input = HeavyStore.openLocalFirst(context, "models/layout_model.onnx");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; )
                output.write(buffer, 0, read);
            model = output.toByteArray();
        }
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(modelThreads);
        session = environment.createSession(model, options);
    }

    public void reset() {
        hasLastInput = false;
    }

    /** 连线推断出底边方向后调用；黑方在下时旋转输入画布，保证红方恒在下方。 */
    public void setBottomSide(Side bottomSide) {
        boolean next = bottomSide == Side.BLACK;
        if (next != rotated) {
            rotated = next;
            samplingPlan = null;
        }
    }

    /** 返回按行优先排列的 90×16 概率（与 {@link ObservedBoard} 的格序一致）。 */
    public float[] recognize(Frame frame, BoardGeometry geometry) throws Exception {
        FloatBuffer input = prepare(frame, geometry);
        lastInput.clear();
        lastInput.put(input);
        lastInput.flip();
        hasLastInput = true;
        return recognizeInput(input);
    }

    /**
     * 用整盘 z-score 局部对比归一替换标准 ImageNet 归一，跑一次推理。
     * 专门处理带阴影/斜面/渐变底色的棋盘，仅在标准路径失败时由 PieceRecognizer 调用。
     */
    public float[] recognizeEnhanced(Frame frame, BoardGeometry geometry) throws Exception {
        prepare(frame, geometry); // 填好 preparedInput 与 modelInput，下面只取 preparedInput
        System.arraycopy(preparedInput, 0, enhancedInput, 0, INPUT_FLOATS);
        enhancePreparedInput(enhancedInput);
        lastInput.clear();
        lastInput.put(enhancedInput);
        lastInput.flip();
        hasLastInput = true;
        return recognizeInput(lastInput);
    }

    public float[] recognizeLastInput() throws Exception {
        if (!hasLastInput)
            return null;
        return recognizeInput(lastInput);
    }

    /**
     * 对 [3, 315, 280] 的 ImageNet 归一输入逐通道做 z-score 归一：减去全盘均值、除以
     * 全盘标准差，并夹在 [-3, 3] 内。它把整盘投影/渐变底色抹平，让笔画成为主信号，
     * 与逐格模型的 {@code enhancePreparedInput} 配对使用。
     */
    private static void enhancePreparedInput(float[] input) {
        for (int channel = 0; channel < CHANNELS; channel++) {
            int channelBase = channel * PIXELS;
            float sum = 0f;
            for (int i = 0; i < PIXELS; i++)
                sum += input[channelBase + i];
            float mean = sum / PIXELS;
            float sumSq = 0f;
            for (int i = 0; i < PIXELS; i++) {
                float diff = input[channelBase + i] - mean;
                sumSq += diff * diff;
            }
            float std = (float) Math.sqrt(sumSq / PIXELS);
            if (std < ENHANCE_MIN_STD)
                std = ENHANCE_MIN_STD;
            for (int i = 0; i < PIXELS; i++) {
                float z = (input[channelBase + i] - mean) / std;
                if (z > ENHANCE_CLAMP)
                    z = ENHANCE_CLAMP;
                else if (z < -ENHANCE_CLAMP)
                    z = -ENHANCE_CLAMP;
                input[channelBase + i] = z;
            }
        }
    }

    private float[] recognizeInput(FloatBuffer input) throws Exception {
        input.rewind();
        // 优先走远程：电脑端跑同一份整盘模型，手机不再为推理耗电。
        // 远程返回的顺序是模型原始格序，这里照本机同款 {#screenSquare} 映射回屏幕格序，
        // 与下面本机路径的输出完全一致；返回 null 时照旧退回本机推理。
        if (remote != null) {
            float[] raw = remote.run(RemoteRecognizer.KIND_LAYOUT, input, Board.SQUARES * CLASSES);
            if (raw != null) {
                float[] flat = new float[Board.SQUARES * CLASSES];
                for (int square = 0; square < Board.SQUARES; square++)
                    System.arraycopy(raw, square * CLASSES, flat, screenSquare(square) * CLASSES, CLASSES);
                return flat;
            }
        }
        input.rewind();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input, SHAPE);
             OrtSession.Result output = session.run(Collections.singletonMap("input", tensor))) {
            float[][][] value = (float[][][]) output.get("output").get().getValue();
            float[] flat = new float[Board.SQUARES * CLASSES];
            for (int square = 0; square < Board.SQUARES; square++) {
                int screen = screenSquare(square);
                System.arraycopy(value[0][square], 0, flat, screen * CLASSES, CLASSES);
            }
            return flat;
        }
    }

    /** 旋转 180° 时把模型格序还原回屏幕格序，供与逐格模型融合。 */
    private int screenSquare(int square) {
        if (!rotated)
            return square;
        int row = square / Board.FILES;
        int column = square % Board.FILES;
        return (Board.RANKS - 1 - row) * Board.FILES + (Board.FILES - 1 - column);
    }

    private FloatBuffer prepare(Frame frame, BoardGeometry geometry) {
        int width = frame.width();
        int height = frame.height();
        int rowStride = frame.rowStride();
        int pixelStride = frame.pixelStride();
        if (samplingPlan == null || !samplingPlan.matches(geometry, width, height,
                rowStride, pixelStride, rotated))
            samplingPlan = new SamplingPlan(geometry, width, height,
                rowStride, pixelStride, rotated);
        samplingPlan.fill(frame.buffer(), preparedInput);
        modelInput.clear();
        modelInput.put(preparedInput);
        modelInput.rewind();
        return modelInput;
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    /** 一个固定棋盘几何对应的整盘双线性采样表；只在框选、投屏尺寸或朝向变化时重建。 */
    static final class SamplingPlan {
        private final BoardGeometry geometry;
        private final int width, height, rowStride, pixelStride;
        private final boolean rotated;
        private final int[] x0 = new int[WIDTH];
        private final int[] x1 = new int[WIDTH];
        private final float[] dx = new float[WIDTH];
        private final int[] y0 = new int[HEIGHT];
        private final int[] y1 = new int[HEIGHT];
        private final float[] dy = new float[HEIGHT];

        SamplingPlan(BoardGeometry geometry, int width, int height, int rowStride, int pixelStride,
                     boolean rotated) {
            this.geometry = geometry;
            this.width = width;
            this.height = height;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
            this.rotated = rotated;
            for (int x = 0; x < WIDTH; x++) {
                int source = rotated ? WIDTH - 1 - x : x;
                float sourceX = geometry.left()
                    + (source + .5f - MARGIN_X) / PIXELS_PER_COLUMN * geometry.stepX();
                sample(sourceX, width, pixelStride, x0, x1, dx, x);
            }
            for (int y = 0; y < HEIGHT; y++) {
                int source = rotated ? HEIGHT - 1 - y : y;
                float sourceY = geometry.top()
                    + (source + .5f - MARGIN_Y) / PIXELS_PER_ROW * geometry.stepY();
                sample(sourceY, height, rowStride, y0, y1, dy, y);
            }
        }

        boolean matches(BoardGeometry geometry, int width, int height, int rowStride, int pixelStride,
                        boolean rotated) {
            return this.geometry == geometry && this.width == width && this.height == height
                && this.rowStride == rowStride && this.pixelStride == pixelStride
                && this.rotated == rotated;
        }

        void fill(ByteBuffer pixels, float[] output) {
            for (int y = 0; y < HEIGHT; y++) {
                int row0 = y0[y], row1 = y1[y];
                float fy = dy[y];
                int base = y * WIDTH;
                for (int x = 0; x < WIDTH; x++) {
                    int a = row0 + x0[x], b = row0 + x1[x];
                    int c = row1 + x0[x], d = row1 + x1[x];
                    float fx = dx[x];
                    int index = base + x;
                    for (int channel = 0; channel < CHANNELS; channel++) {
                        float va = pixel(pixels, a + channel), vb = pixel(pixels, b + channel);
                        float vc = pixel(pixels, c + channel), vd = pixel(pixels, d + channel);
                        float top = va + (vb - va) * fx;
                        float bottom = vc + (vd - vc) * fx;
                        float value = top + (bottom - top) * fy;
                        output[channel * PIXELS + index] =
                            (value - MEAN[channel]) / STD[channel];
                    }
                }
            }
        }

        private static void sample(float value, int limit, int stride, int[] first, int[] second,
                                   float[] fraction, int index) {
            int lower = clamp((int) Math.floor(value), 0, limit - 1);
            first[index] = lower * stride;
            second[index] = Math.min(lower + 1, limit - 1) * stride;
            fraction[index] = value - lower;
        }

        private static int pixel(ByteBuffer pixels, int offset) {
            return pixels.get(offset) & 255;
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
            // 关闭阶段不应阻止前台服务继续释放其余 Android 资源。
        }
    }
}