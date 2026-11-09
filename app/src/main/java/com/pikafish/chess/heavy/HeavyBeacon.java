package com.pikafish.chess.heavy;

import android.os.Handler;
import android.os.Looper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ★★★ 配对码信标：同网络下靠「广播 + 应答」把两台设备对上，不涉及任何地址输入。★★★
 *
 * <p>协议极简（纯 UDP 文本，不依赖 mDNS、不依赖任何第三方库）：</p>
 * <ul>
 *   <li><b>辎重包端</b>（{@link #startResponder}）：监听 {@link #PORT}，收到任意探测报文，
 *       就把自己的「配对码 + HTTP 端口」原路回给对方。</li>
 *   <li><b>操作包端</b>（{@link #discover}）：向本网段广播 {@code HXQ?<配对码>}，
 *       收到应答后比对配对码；一致即为找到了目标辎重包主机，拿到它的 {@code IP:端口}。</li>
 * </ul>
 *
 * <p>用户侧只看到一个 6 位数字：辎重包端显示它，操作包端填它 —— 对上即成。</p>
 */
public final class HeavyBeacon {

    /** 发现用的 UDP 端口（收发共用）。 */
    public static final int PORT = 45321;

    private static final String ASK = "HXQ?";
    private static final String HELLO = "HXQ!";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 单次 discovery 的总时长。 */
    private static final long DEFAULT_TIMEOUT_MS = 5000L;
    /** 收包超时后重发探测的间隔。 */
    private static final int RESEND_INTERVAL_MS = 900;

    private static volatile DatagramSocket responder;
    private static volatile Thread responderThread;

    private HeavyBeacon() {}

    // ═════════════════ 辎重包端：应答 ═════════════════

    /**
     * 开始应答探测（辎重包端调用）。把本机的配对码与共享端口回给每一个探测方。
     *
     * @param code       本机当前显示的配对码
     * @param httpPort   本机共享辎重包的 HTTP 端口
     * @param enginePort 本机对外借出引擎的端口（0 表示本机不提供异地引擎）
     */
    public static synchronized void startResponder(final String code, final int httpPort, final int enginePort) {
        stopResponder();
        if (code == null || httpPort <= 0) {
            return;
        }
        final DatagramSocket socket;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(PORT));
        } catch (Exception e) {
            return;
        }
        responder = socket;
        Thread thread = new Thread(() -> respondLoop(socket, code, httpPort, enginePort), "heavy-beacon");
        thread.setDaemon(true);
        responderThread = thread;
        thread.start();
    }

    /** 兼容旧调用：不提供异地引擎（引擎端口按 0 上报）。 */
    public static synchronized void startResponder(final String code, final int httpPort) {
        startResponder(code, httpPort, 0);
    }

    /** 停止应答（辎重包端退出时调用）。 */
    public static synchronized void stopResponder() {
        DatagramSocket current = responder;
        responder = null;
        responderThread = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void respondLoop(DatagramSocket socket, String code, int httpPort, int enginePort) {
        byte[] buffer = new byte[64];
        while (true) {
            if (responder != socket || socket.isClosed()) {
                return;
            }
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), UTF8).trim();
                if (!message.startsWith(ASK)) {
                    continue;
                }
                // 应答：HXQ!<码> <HTTP端口> <引擎端口>（引擎端口为 0 时省略，老操作包也能认）
                String body = enginePort > 0
                        ? (HELLO + code + " " + httpPort + " " + enginePort)
                        : (HELLO + code + " " + httpPort);
                byte[] reply = body.getBytes(UTF8);
                socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
            } catch (Exception e) {
                if (responder != socket || socket.isClosed()) {
                    return;
                }
            }
        }
    }

    // ═════════════════ 操作包端：发现 ═════════════════

    /** 找到的辎重包主机：{@code host} 为对方 IP，{@code port} 为对方 HTTP 端口，
     *  {@code enginePort} 为对方对外借出引擎的端口（0 表示对方不提供异地引擎）。 */
    public static final class Peer {
        public final String host;
        public final int port;
        public final int enginePort;

        Peer(String host, int port, int enginePort) {
            this.host = host;
            this.port = port;
            this.enginePort = enginePort;
        }

        /** 对方辎重包的下载地址（同一份文件的统一路径名）。 */
        public String packUrl() {
            return "http://" + host + ":" + port + HeavyStore.REMOTE_PACK_PATH;
        }

        /** 对方异地引擎地址（{@code host:enginePort}）；对方不提供时返回 {@code null}。 */
        public String engineEndpoint() {
            return enginePort > 0 ? host + ":" + enginePort : null;
        }
    }

    /** 发现结果回调（在主线程回调）。{@code peer} 为 {@code null} 表示没找到。 */
    public interface Listener {
        void onResult(Peer peer);

        void onError(String message);

        /**
         * 听到了一台辎重包，但它出示的配对码与我们要找的不一样。
         *
         * <p>最常见的来源：同一个网络里开着不止一个辎重包（另一个窗口 / 另一台设备 /
         * 旧版没关干净）。这时手机听到的是那一台的码，用户手里抄的却是另一台的码，
         * 怎么对都对不上——把对方真实的码回显出来，一眼就能看出来是拿错了码。</p>
         */
        void onMismatch(String heardCode, String host);
    }

    /** 广播探测指定配对码的辎重包主机；找到（或超时）后回调。 */
    public static void discover(final String wantedCode, final Listener listener) {
        discover(wantedCode, DEFAULT_TIMEOUT_MS, listener);
    }

    /**
     * 广播探测指定配对码的辎重包主机。
     *
     * <p>不断向「全网广播地址 + 各网卡广播地址」发送 {@code HXQ?<码>}，
     * 直到收到出示同一配对码的应答，或到达 {@code timeoutMs} 为止。</p>
     */
    public static void discover(final String wantedCode, final long timeoutMs, final Listener listener) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> {
            Peer found = null;
            String error = null;
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(RESEND_INTERVAL_MS);
                byte[] probe = (ASK + wantedCode).getBytes(UTF8);
                List<InetAddress> targets = broadcastTargets();
                final Set<String> seenMismatch = new HashSet<>();
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (found == null && System.currentTimeMillis() < deadline) {
                    for (InetAddress target : targets) {
                        try {
                            socket.send(new DatagramPacket(probe, probe.length, target, PORT));
                        } catch (Exception ignored) {
                        }
                    }
                    long receiveUntil = Math.min(deadline, System.currentTimeMillis() + RESEND_INTERVAL_MS);
                    while (found == null && System.currentTimeMillis() < receiveUntil) {
                        byte[] buffer = new byte[128];
                        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                        try {
                            socket.receive(reply);
                        } catch (SocketTimeoutException timeout) {
                            break;
                        } catch (Exception e) {
                            break;
                        }
                        String shown = advertisedCode(reply);
                        if (shown == null) {
                            continue;
                        }
                        if (!shown.equals(wantedCode)) {
                            // 听到了别的辎重包：把它真实的码回显出去，别让用户对着错码干瞪眼。
                            final String heard = shown;
                            final String host = reply.getAddress().getHostAddress();
                            if (seenMismatch.add(heard)) {
                                handler.post(() -> listener.onMismatch(heard, host));
                            }
                            continue;
                        }
                        Peer candidate = parseReply(reply, wantedCode);
                        if (candidate != null) {
                            found = candidate;
                        }
                    }
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
            final Peer result = found;
            final String failure = error;
            handler.post(() -> {
                if (result != null) {
                    listener.onResult(result);
                } else if (failure != null) {
                    listener.onError(failure);
                } else {
                    listener.onResult(null);
                }
            });
        }, "heavy-discover");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 只把应答里对方出示的配对码抽出来（{@code HXQ!<码> ...}）；格式不对返回 {@code null}。
     * 与 {@link #parseReply} 分开，是为了在码不一致时也能把「听到的真码」回显给用户。
     */
    private static String advertisedCode(DatagramPacket reply) {
        String message = new String(reply.getData(), 0, reply.getLength(), UTF8).trim();
        if (!message.startsWith(HELLO)) {
            return null;
        }
        String[] parts = message.substring(HELLO.length()).trim().split("\\s+");
        if (parts.length < 2 || parts[0].isEmpty()) {
            return null;
        }
        return parts[0];
    }

    /**
     * 解析应答：{@code HXQ!<码> <HTTP端口> [<引擎端口>]}；配对码不一致或格式不对返回 null。
     * 老辎重包只给两项（无引擎端口），此时 {@code enginePort == 0}。
     */
    private static Peer parseReply(DatagramPacket reply, String wantedCode) {
        String message = new String(reply.getData(), 0, reply.getLength(), UTF8).trim();
        if (!message.startsWith(HELLO)) {
            return null;
        }
        String[] parts = message.substring(HELLO.length()).trim().split("\\s+");
        if (parts.length < 2 || !parts[0].equals(wantedCode)) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0) {
            return null;
        }
        int enginePort = 0;
        if (parts.length >= 3) {
            try {
                enginePort = Integer.parseInt(parts[2].trim());
                if (enginePort <= 0) {
                    enginePort = 0;
                }
            } catch (NumberFormatException ignored) {
                enginePort = 0;
            }
        }
        return new Peer(reply.getAddress().getHostAddress(), port, enginePort);
    }

    /** 探测目标：全网广播地址 + 各在用网卡的子网广播地址。 */
    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : iface.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast != null && !targets.contains(broadcast)) {
                        targets.add(broadcast);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return targets;
    }
}
