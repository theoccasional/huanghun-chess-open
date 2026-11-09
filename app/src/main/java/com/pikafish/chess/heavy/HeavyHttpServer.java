package com.pikafish.chess.heavy;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * ★★★ 操作包「自身当服务器」：把本机的辎重包在局域网里共享出去，供其他手机 / 电脑直读。★★★
 *
 * <p>极简的只读 HTTP 服务：任何设备用浏览器或本应用（操作包）填一个地址，就能<b>按需分段</b>读取本机辎重包，
 * 无需安装第三方 App。关键点是完整支持 {@code Range} 分段请求（返回 206 + Content-Range），
 * 这样对端 {@link RemoteHeavy} 才能只拉需要的片段、不整份下载。</p>
 *
 * <p>手机互连：手机 A 打开共享 → 手机 B 的「辎重包」控制台填 A 显示的地址即可直读。
 * 电脑侧则用任意支持 Range 的文件服务（如 {@code caddy file-server}、Node 简单脚本）配合，逻辑一致。</p>
 *
 * <p>服务随进程存活；装了辎重包的那台设备只要 App 在运行，共享就一直在。</p>
 */
public final class HeavyHttpServer {

    /** 默认端口；被占用时自动改用系统分配的空闲端口。 */
    private static final int DEFAULT_PORT = 8765;

    private static HeavyHttpServer shared;

    public static synchronized HeavyHttpServer shared() {
        if (shared == null) {
            shared = new HeavyHttpServer();
        }
        return shared;
    }

    private volatile ServerSocket server;
    private volatile File file;
    private volatile int port;

    /** 开始共享指定辎重包文件，返回可填给对端的地址说明。重复调用会先停旧服务。 */
    public synchronized String start(File heavyFile) throws IOException {
        stop();
        if (heavyFile == null || !heavyFile.isFile() || heavyFile.length() == 0L) {
            throw new IOException("本机还没有可共享的辎重包文件");
        }
        ServerSocket socket = bind();
        server = socket;
        file = heavyFile;
        port = socket.getLocalPort();
        Thread thread = new Thread(this::acceptLoop, "heavy-http");
        thread.setDaemon(true);
        thread.start();
        return describe();
    }

    public synchronized void stop() {
        ServerSocket current = server;
        server = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean isRunning() {
        ServerSocket current = server;
        return current != null && !current.isClosed();
    }

    public File file() {
        return file;
    }

    public int port() {
        return port;
    }

    /** 本机所有可用的局域网直读地址（换行分隔）。 */
    public String describe() {
        List<String> urls = urls();
        if (urls.isEmpty()) {
            return "http://<本机局域网IP>:" + port;
        }
        StringBuilder builder = new StringBuilder();
        for (String url : urls) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(url);
        }
        return builder.toString();
    }

    public List<String> urls() {
        List<String> urls = new ArrayList<>();
        int currentPort = port;
        if (currentPort <= 0) {
            return urls;
        }
        for (String ip : localIPv4()) {
            urls.add("http://" + ip + ":" + currentPort);
        }
        return urls;
    }

    // ═════════════════ 服务端 ═════════════════

    private ServerSocket bind() throws IOException {
        try {
            return new ServerSocket(DEFAULT_PORT);
        } catch (IOException busy) {
            return new ServerSocket(0);
        }
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket current = server;
            if (current == null || current.isClosed()) {
                return;
            }
            Socket socket;
            try {
                socket = current.accept();
            } catch (IOException closed) {
                return;
            }
            Thread worker = new Thread(() -> handle(socket), "heavy-http-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket;
             InputStream in = client.getInputStream();
             OutputStream out = new BufferedOutputStream(client.getOutputStream())) {
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";

            long rangeStart = -1L;
            long rangeEnd = -1L;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (key.equals("range")) {
                    long[] parsed = parseRange(value);
                    if (parsed != null) {
                        rangeStart = parsed[0];
                        rangeEnd = parsed[1];
                    }
                }
            }

            File current = file;
            if (current == null || !current.isFile()) {
                writeHead(out, "503 Service Unavailable", null, 0L, method);
                out.flush();
                return;
            }
            long total = current.length();
            boolean partial = rangeStart >= 0;
            long start = 0L;
            long end = total - 1;
            if (partial) {
                start = rangeStart;
                end = rangeEnd >= 0 ? Math.min(rangeEnd, total - 1) : total - 1;
                if (start >= total || start < 0) {
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\n"
                            + "Content-Range: bytes */" + total + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes("US-ASCII"));
                    out.flush();
                    return;
                }
            }
            long length = end - start + 1;
            String head = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Content-Length: " + length + "\r\n"
                    + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n" : "")
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("US-ASCII"));
            if ("HEAD".equalsIgnoreCase(method)) {
                out.flush();
                return;
            }
            try (InputStream fileIn = new FileInputStream(current)) {
                skipFully(fileIn, start);
                byte[] buffer = new byte[64 * 1024];
                long left = length;
                while (left > 0) {
                    int read = fileIn.read(buffer, 0, (int) Math.min((long) buffer.length, left));
                    if (read < 0) {
                        break;
                    }
                    out.write(buffer, 0, read);
                    left -= read;
                }
            }
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void writeHead(OutputStream out, String status, String extra, long length, String method) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("US-ASCII"));
    }

    /** 解析 {@code bytes=start-end}；缺省端点用 -1 表示。 */
    private static long[] parseRange(String value) {
        if (value == null || !value.startsWith("bytes=")) {
            return null;
        }
        String spec = value.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            long start = dash == 0 ? -1L : Long.parseLong(spec.substring(0, dash).trim());
            long end = dash == spec.length() - 1 ? -1L : Long.parseLong(spec.substring(dash + 1).trim());
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                left--;
            } else {
                left -= skipped;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                builder.append((char) c);
            }
            if (builder.length() > 4096) {
                break;
            }
        }
        if (c < 0 && builder.length() == 0) {
            return null;
        }
        return builder.toString();
    }

    private static List<String> localIPv4() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        String host = address.getHostAddress();
                        if (!result.contains(host)) {
                            result.add(host);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
