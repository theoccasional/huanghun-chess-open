package com.pikafish.chess.heavy;

import android.content.Context;
import android.util.Log;

import com.pikafish.chess.engine.EngineIdentity;
import com.pikafish.chess.engine.UserEngineStore;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ★★★ 辎重包端「异地引擎」服务：把本机这台引擎借给别的设备用（替它算棋）。★★★
 *
 * <p>就是把本机引擎进程的 stdin/stdout 原样接到一个 TCP 连接上，说人话即：</p>
 * <ul>
 *   <li>操作包端（别的手机）连上本机 {@link #DEFAULT_PORT} 端口；</li>
 *   <li>本机就地为这条连接对接本机引擎，把两端管道对接；</li>
 *   <li>操作包端发来的 UCI 命令（uci / position / go …）直接喂给引擎，
 *       引擎的回应（info / bestmove）原样回传给操作包端。</li>
 * </ul>
 *
 * <p>因此引擎与权重都在本机（辎重包端）加载与运算，操作包端只是「遥控」——不加载权重、
 * 不跑引擎，因而不发热。协议就是标准 UCI，与本地子进程方案完全一致，上层零改动。</p>
 *
 * <p>本机 NNUE 权重路径由本服务在收到 {@code uci} 后自动注入
 * （{@code setoption name EvalFile value …}），操作包端无需知道、也无权知道本机路径。</p>
 *
 * <p>同时只服务一条连接（引擎吃满 CPU/内存，串行才是稳妥的用法）；连接断开即收工，
 * 下一台设备再来照样能连。</p>
 *
 * <p>★★★ 本版关键修复：引擎为【常驻热进程】。★★★ 旧版每条连接都新起一台引擎、断开即销毁，
 * 于是手机对手机时——每走一步/每次重连都要重新加载一遍 50MB 的 NNUE 权重（十几秒），
 * 而操作包端的 readyok 只等 10 秒，必然超时 → 询招引擎被判"未就绪" → 空手而归 →
 * 上层"必走子保险"挑第一个合法着法（用户看到的「炮八进七 / 炮打马」），并长期卡在
 * "引擎启动中/思考中"。现改为：引擎进程与权重只加载一次，常驻复用；重连不再重载，
 * 换连接也一触即发。与桌面 EXE 那套「常驻热引擎 + 预热」保持同一思路。</p>
 */
public final class EngineShareServer {

    private static final String TAG = "EngineShareServer";

    /** 异地引擎默认端口；被占用时自动改用系统分配的空闲端口。 */
    public static final int DEFAULT_PORT = 45322;

    private static final String BINARY_NAME = "libpikafish_bin.so";
    private static final String NNUE_LOCAL_NAME = "pikafish.nnue";

    private static EngineShareServer shared;

    public static synchronized EngineShareServer shared() {
        if (shared == null) {
            shared = new EngineShareServer();
        }
        return shared;
    }

    private volatile ServerSocket server;
    private volatile int port;
    private volatile Context appContext;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 引擎串行锁：同一时刻只服务一条连接。 */
    private final Object engineLock = new Object();
    /** 在连的对端 socket（换引擎时全部断开，迫使它们重连）。 */
    private final java.util.Set<Socket> activeSockets =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // ═════════════════ 常驻热引擎 ═════════════════

    /** 常驻热引擎的锁：保护 warmProcess / warmIn / clientOut 的一致性访问。 */
    private final Object warmLock = new Object();
    /** 常驻引擎进程（只拉起一次，跨连接复用）。 */
    private Process warmProcess;
    /** 常驻引擎的 stdin 写入端。 */
    private BufferedWriter warmIn;
    /** 当前挂着的对端输出流；无连接时为 null（此时引擎输出被丢弃）。 */
    private volatile OutputStream clientOut;

    // ═════════════════ 会话监视（供主机界面「明察秋毫」）═════════════════
    // 把借出会话里流动的 UCI 关键信息留痕：对端是谁、发来的局面是什么、引擎在想什么。

    private volatile String peerAddr = "";
    private volatile boolean sessionActive = false;
    private volatile String lastFen = "";
    private volatile String lastInfo = "";
    private volatile String lastBest = "";
    private volatile long lastSeenAt = 0L;
    /** 当前出借的引擎名（＝本机「采用」的那台；内置则标内置信）。 */
    private volatile String lendName = "皮卡鱼（内置）";
    /** ★ 引擎是否真的握手成功（uciok + readyok）。端口开着 ≠ 引擎活着。 */
    private volatile boolean engineReady = false;
    /** 给发送端界面看的引擎状态：预热中 / 就绪：xxx / 启动失败：xxx。 */
    private volatile String engineStatus = "未预热";

    /** 主机界面用来绘制「会话监视」的一张只读快照。 */
    public static final class Monitor {
        public final boolean active;
        public final String peer;
        public final String fen;
        public final String info;
        public final String best;
        public final long seenAt;
        /** 当前出借的引擎名。 */
        public final String lend;
        /** ★ 引擎是否真的就绪（uciok + readyok）。 */
        public final boolean engineReady;
        /** ★ 引擎状态：预热中 / 就绪：xxx / 启动失败：xxx。 */
        public final String engineStatus;

        Monitor(boolean active, String peer, String fen, String info, String best, long seenAt, String lend,
                boolean engineReady, String engineStatus) {
            this.active = active;
            this.peer = peer;
            this.fen = fen;
            this.info = info;
            this.best = best;
            this.seenAt = seenAt;
            this.lend = lend;
            this.engineReady = engineReady;
            this.engineStatus = engineStatus;
        }
    }

    /** 取当前会话快照（主机界面每 ~0.5s 拉一次即可）。 */
    public Monitor monitor() {
        return new Monitor(sessionActive, peerAddr, lastFen, lastInfo, lastBest, lastSeenAt, lendName,
                engineReady, engineStatus);
    }

    /**
     * ★★★ 作废常驻热引擎，下次接入按"最新的采用引擎"重新拉起。★★★
     * <p>本机「采用」换了一台引擎时调用。★ 关键：必须把<b>在连的对端连接一并断开</b>——
     * 否则引擎进程换了，对端那条 socket 还挂着，它的搜索命令发进一台已死的引擎里，
     * 界面就永远卡在"引擎恢复中/思考中"（用户说的"换引擎排异性、卡死"）。断开后对端会
     * 自动重连，重新握手拿到新引擎，整个切换就丝滑了。</p>
     */
    public void resetEngine() {
        closeActiveSockets();
        synchronized (warmLock) {
            killWarmLocked();
        }
        // ★ 立刻按新引擎重新预热；否则要等到下一次对端接入才加载，发送端会长时间显示
        //   「预热中」，对端也容易在握手窗口里超时。
        final Context ctx = appContext;
        if (ctx != null) {
            Thread warm = new Thread(() -> {
                try {
                    boolean ok = ensureWarm(ctx);
                    Log.i(TAG, "换引擎后预热：" + (ok ? "就绪" : "失败"));
                } catch (Exception e) {
                    Log.w(TAG, "换引擎后预热失败：" + e.getMessage());
                }
            }, "engine-share-prewarm");
            warm.setDaemon(true);
            warm.start();
        }
        Log.i(TAG, "出借引擎已作废并断开在连对端，下次接入按最新采用的引擎重新拉起");
    }

    /** 断开所有在连的对端 socket（迫使它们重连、重新握手）。 */
    private void closeActiveSockets() {
        java.util.List<Socket> snapshot;
        synchronized (activeSockets) {
            snapshot = new java.util.ArrayList<>(activeSockets);
            activeSockets.clear();
        }
        for (Socket s : snapshot) {
            closeQuietly(s);
        }
    }

    /**
     * 从借出会话的一条 UCI 行里留痕：对端发来的 {@code position fen …} 记下局面，
     * 引擎回的 {@code info …} 记下思考（深度/评分/主变），{@code bestmove …} 记下着法。
     */
    private void tapLine(String line) {
        lastSeenAt = System.currentTimeMillis();
        String t = line == null ? "" : line.trim();
        if (t.startsWith("position")) {
            int i = t.indexOf("fen ");
            if (i > 0) lastFen = t.substring(i + 4).trim();
        } else if (t.startsWith("info") && t.contains(" pv ")) {
            lastInfo = compactInfo(t);
        } else if (t.startsWith("bestmove")) {
            lastBest = t;
        }
    }

    /** 把一条 info 行压成「深度/评分/主变」摘要。 */
    private static String compactInfo(String t) {
        StringBuilder sb = new StringBuilder();
        int d = t.indexOf(" depth ");
        if (d >= 0) {
            int end = t.indexOf(' ', d + 7);
            sb.append("深度 ").append(t, d + 7, end < 0 ? t.length() : end);
        }
        int sc = t.indexOf("score ");
        if (sc >= 0) {
            int end = t.indexOf(' ', sc + 6);
            int end2 = end < 0 ? t.length() : end;
            String type = t.substring(sc + 6, end2);
            int valEnd = t.indexOf(' ', end2 + 1);
            String val = t.substring(end2 + 1, valEnd < 0 ? t.length() : valEnd);
            sb.append(" 评分 ").append("cp".equals(type) ? val : type + " " + val);
        }
        int pv = t.indexOf(" pv ");
        if (pv >= 0) {
            String tail = t.substring(pv + 4).trim();
            String[] parts = tail.split(" ");
            int n = Math.min(parts.length, 12);
            StringBuilder pvs = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) pvs.append(' ');
                pvs.append(parts[i]);
            }
            sb.append("  主变 ").append(pvs);
        }
        return sb.toString();
    }

    private EngineShareServer() {}

    /** 开始对外借出引擎，返回实际监听端口。重复调用会先停旧服务。 */
    public synchronized int start(Context context) throws IOException {
        Context ctx = context.getApplicationContext();
        appContext = ctx;
        stop();
        ServerSocket socket;
        try {
            socket = new ServerSocket(DEFAULT_PORT);
        } catch (IOException busy) {
            socket = new ServerSocket(0);
        }
        server = socket;
        port = socket.getLocalPort();
        running.set(true);
        Thread thread = new Thread(this::acceptLoop, "engine-share-accept");
        thread.setDaemon(true);
        thread.start();
        // ★ 预热：后台先把常驻引擎与权重加载好——手机第一次接入即可秒算，不必现等权重加载。
        Thread warm = new Thread(() -> {
            try {
                if (ensureWarm(appContext)) {
                    Log.i(TAG, "异地引擎已预热就绪（权重完成加载，重连不再等待）");
                }
            } catch (Exception e) {
                Log.w(TAG, "异地引擎预热失败：" + e.getMessage());
            }
        }, "engine-share-prewarm");
        warm.setDaemon(true);
        warm.start();
        Log.i(TAG, "异地引擎服务已就绪，端口 " + port);
        return port;
    }

    public synchronized void stop() {
        running.set(false);
        ServerSocket current = server;
        server = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        synchronized (warmLock) {
            killWarmLocked();
        }
        engineReady = false;
        engineStatus = "未开启";
    }

    public int port() {
        return port;
    }

    public boolean isRunning() {
        ServerSocket current = server;
        return current != null && !current.isClosed();
    }

    // ═════════════════ 服务端 ═════════════════

    private void acceptLoop() {
        while (running.get()) {
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
            Thread worker = new Thread(() -> serve(socket), "engine-share-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** 一条连接 = 一段引擎会话，串行执行。 */
    private void serve(Socket socket) {
        synchronized (engineLock) {
            if (!running.get()) {
                closeQuietly(socket);
                return;
            }
            session(socket);
        }
    }

    /**
     * 一段异地引擎会话：直接把这条 TCP 连接接到【常驻热引擎】上。
     *
     * <p>引擎与权重只在首次加载一次；此后每条连接都复用这台热引擎——重连不再重载权重。
     * 会话开始时先 stop + isready 让引擎回到干净待命态；会话结束时只 stop，绝不杀进程，
     * 留给下一次连接秒接。</p>
     */
    private void session(Socket socket) {
        Context ctx = appContext;
        if (ctx == null) {
            closeQuietly(socket);
            return;
        }
        try {
            socket.setTcpNoDelay(true);
            if (!ensureWarm(ctx)) {
                closeQuietly(socket);
                return;
            }
            activeSockets.add(socket);
            synchronized (warmLock) {
                if (warmIn == null) {
                    closeQuietly(socket);
                    return;
                }
                // 上一段可能残留搜索：stop + isready 让引擎回到干净待命态。
                sendLocked("stop");
                sendLocked("isready");
                clientOut = socket.getOutputStream();
                // ★ 会话监视：记下对端是谁、会话已激活（主机界面据此显示连接状态）。
                peerAddr = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();
                sessionActive = true;
                lastFen = "";
                lastInfo = "";
                lastBest = "";
                lastSeenAt = System.currentTimeMillis();
            }
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new BufferedInputStream(socket.getInputStream()), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    tapLine(line);   // ★ 留痕：对端局面 / 引擎思考 / 着法 都记下来
                    synchronized (warmLock) {
                        sendLocked(line);
                    }
                }
            } finally {
                synchronized (warmLock) {
                    clientOut = null;
                    sessionActive = false;
                    sendLocked("stop"); // 断开后让引擎停住，留给下一次连接秒接（不杀进程）
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "异地引擎会话结束: " + e.getMessage());
        } finally {
            activeSockets.remove(socket);
            closeQuietly(socket);
            Log.i(TAG, "一条异地引擎连接已释放（热引擎保留）");
        }
    }

    /**
     * ★★★ 确保常驻热引擎已拉起、完成 uciok + readyok 握手、并注入权重。★★★
     *
     * <p>关键修复：旧版只要进程 start 成功就返回 true，完全不管引擎到底会不会说 UCI——
     * 于是借出 C4 这类「权重加密 / 需要设备校验 / 根本不是 UCI」的外置引擎时，端口是开的，
     * 但对端发 uci 永远收不到 uciok，表现就是「配对成功，但引擎连不上」，而发送端还显示
     * 「未连接」。现在必须真正等到 readyok；外置引擎握手失败就自动回退内置皮卡鱼。</p>
     */
    private boolean ensureWarm(Context ctx) throws IOException {
        synchronized (warmLock) {
            if (warmProcess != null && warmProcess.isAlive() && warmIn != null && engineReady) {
                return true;
            }
        }
        // ① 先按本机「采用」的引擎预热（外置 / 内置）
        if (warmUp(ctx, false)) {
            return true;
        }
        // ② 外置引擎握手失败 → 自动回退内置皮卡鱼
        Log.w(TAG, "外置引擎未能完成 UCI 握手，回退内置皮卡鱼");
        if (warmUp(ctx, true)) {
            return true;
        }
        engineStatus = "启动失败：内置皮卡鱼也无法握手";
        Log.e(TAG, engineStatus);
        return false;
    }

    /**
     * 拉起一台引擎并完成 UCI 握手。
     *
     * @param forceInternal true = 强制内置皮卡鱼（外置引擎握手失败时回退）
     */
    private boolean warmUp(Context ctx, boolean forceInternal) throws IOException {
        synchronized (warmLock) {
            killWarmLocked();
            File binary = forceInternal ? internalBinary(ctx) : resolveEngineBinary(ctx);
            if (binary == null || !binary.isFile()) {
                engineStatus = "引擎二进制缺失";
                Log.e(TAG, engineStatus);
                return false;
            }
            String nnue;
            if (forceInternal) {
                nnue = ensureNnue(ctx);
                lendName = "皮卡鱼（内置·外置回退）";
            } else {
                nnue = resolveNnue(ctx, binary);
            }
            if (nnue == null) {
                engineStatus = "取不到引擎权重";
                Log.e(TAG, engineStatus);
                return false;
            }

            engineStatus = "预热中：" + lendName;
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
            pb.directory(ctx.getFilesDir());
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", ctx.getFilesDir().getAbsolutePath());
            Process p = pb.start();

            warmProcess = p;
            warmIn = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));

            final InputStream engineOut = new BufferedInputStream(p.getInputStream());
            final CountDownLatch uciokLatch = new CountDownLatch(1);
            final CountDownLatch readyLatch = new CountDownLatch(1);
            final String[] idName = {""};

            Thread pump = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(engineOut, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String t = line.trim();
                        if (t.startsWith("id name ")) {
                            idName[0] = t.substring(8).trim();
                        }
                        if (t.equals("uciok")) {
                            uciokLatch.countDown();
                        }
                        if (t.equals("readyok")) {
                            readyLatch.countDown();
                        }
                        OutputStream o = clientOut;
                        if (o != null) {
                            try {
                                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                                o.flush();
                            } catch (IOException e) {
                                clientOut = null; // 对端已断：静默丢弃，不打断引擎
                            }
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    uciokLatch.countDown();
                    readyLatch.countDown(); // 进程退出：唤醒等待者
                }
            }, "engine-share-pump");
            pump.setDaemon(true);
            pump.start();

            // 权重只在这里注入一次；此后热引擎一直带着它。
            sendLocked("uci");

            // ① 先等 uciok：4 秒内不会说 UCI 的引擎（C4 这类私有/校验引擎）立即判定不可借。
            boolean uciOk;
            try {
                uciOk = uciokLatch.await(4, TimeUnit.SECONDS) && p.isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                uciOk = false;
            }
            if (!uciOk) {
                killWarmLocked();
                engineStatus = "启动失败：不响应 uci";
                Log.w(TAG, engineStatus + "（" + binary.getName() + "）");
                return false;
            }
            // uciok 已到：把「出借名」细化成：皮卡鱼 2026-09-23（外置）
            lendName = lendLabel(ctx, binary, idName[0], forceInternal);

            // ② 再注入权重、等 readyok（大权重加载可能要十几秒）。
            sendLocked("setoption name EvalFile value " + nnue);
            sendLocked("isready");
            boolean ok;
            try {
                ok = readyLatch.await(forceInternal ? 20 : 20, TimeUnit.SECONDS) && p.isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ok = false;
            }
            if (!ok) {
                killWarmLocked();
                engineStatus = "启动失败：readyok 超时";
                Log.w(TAG, engineStatus + "（" + binary.getName() + "）");
                return false;
            }

            engineReady = true;
            engineStatus = "就绪";
            Log.i(TAG, "异地引擎热进程已就绪：" + lendName + "，权重 " + nnue);
            return true;
        }
    }

    /**
     * 出借引擎的展示名：皮卡鱼 2026-09-23（外置） / C4（外置） / 皮卡鱼（内置）。
     * 日期优先取引擎自报的 id name，其次取外置包名（Pikafish260925.zip），再其次取文件名。
     * 取不到日期就只显示名字，绝不硬编一个日期。
     */
    private static String lendLabel(Context ctx, File binary, String idName, boolean internal) {
        String raw = (idName == null || idName.isEmpty()) ? binary.getName() : idName;
        String disp = EngineIdentity.INSTANCE.chineseNameFor(raw);
        String date = EngineIdentity.INSTANCE.findDate(raw);
        if (date == null) {
            try {
                date = EngineIdentity.INSTANCE.findDate(UserEngineStore.INSTANCE.packageName(ctx));
            } catch (Exception ignored) {
            }
        }
        if (date == null) {
            date = EngineIdentity.INSTANCE.findDate(binary.getName());
        }
        String tag = internal ? "（内置）" : "（外置）";
        return disp + (date == null ? "" : " " + date) + tag;
    }

    /** 内置皮卡鱼二进制（nativeLibraryDir 里的 libpikafish_bin.so）。 */
    private File internalBinary(Context ctx) {
        File b = new File(ctx.getApplicationInfo().nativeLibraryDir, BINARY_NAME);
        if (b.isFile() && !b.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            b.setExecutable(true, false);
        }
        return b;
    }

    /** 向热引擎写一行（调用方须持有 warmLock）。 */
    private void sendLocked(String cmd) {
        BufferedWriter in = warmIn;
        if (in == null) {
            return;
        }
        try {
            in.write(cmd);
            in.newLine();
            in.flush();
        } catch (Exception ignored) {
        }
    }

    /** 结束常驻热引擎（调用方须持有 warmLock）。 */
    private void killWarmLocked() {
        engineReady = false;
        clientOut = null;
        BufferedWriter in = warmIn;
        warmIn = null;
        Process p = warmProcess;
        warmProcess = null;
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * ★★★ 出借哪台引擎：以本机「采用」的那台为准。★★★
     *
     * <p>旧版写死借出内置皮卡鱼（{@code libpikafish_bin.so}），完全不理会用户在设置里
     * 「采用」了哪台外置引擎（八卦 / UFX / C4 / 旋风…），于是接收端永远只认得皮卡鱼。
     * 现在：本机采用了外置引擎就借那台（连同它自带的权重）；没有外置（内置档）才回退内置皮卡鱼。
     * 并把出借的引擎名记进 {@link #lendName} 供界面与对端如实显示。</p>
     */
    private File resolveEngineBinary(Context ctx) {
        try {
            File ext = UserEngineStore.INSTANCE.engineFile(ctx);
            if (ext != null && ext.isFile()) {
                if (!ext.canExecute()) {
                    //noinspection ResultOfMethodCallIgnored
                    ext.setExecutable(true, false);
                }
                if (ext.canExecute()) {
                    lendName = lendLabel(ctx, ext, "", false);
                    Log.i(TAG, "异地引擎：借出外置引擎 " + ext.getAbsolutePath());
                    return ext;
                }
                Log.w(TAG, "外置引擎不可执行（W^X），回退内置：" + ext.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "读取外置引擎失败，回退内置：" + e.getMessage());
        }
        File b = new File(ctx.getApplicationInfo().nativeLibraryDir, BINARY_NAME);
        if (b.isFile() && !b.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            b.setExecutable(true, false);
        }
        lendName = lendLabel(ctx, b, "", true);
        return b;
    }

    /** 权重：外置引擎自带就用它自带的；否则用辎重包里那份 pikafish.nnue。 */
    private String resolveNnue(Context ctx, File binary) {
        try {
            File ext = UserEngineStore.INSTANCE.nnueFile(ctx);
            if (ext != null && ext.isFile() && ext.length() > 1_000_000L) {
                return ext.getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        try {
            return ensureNnue(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    /** 确保本机有一份 NNUE 权重文件（供借出的引擎加载），返回其绝对路径。 */
    private String ensureNnue(Context ctx) throws IOException {
        File nnue = new File(ctx.getFilesDir(), NNUE_LOCAL_NAME);
        if (nnue.isFile() && nnue.length() >= 10_000_000L) {
            return nnue.getAbsolutePath();
        }
        Log.i(TAG, "异地引擎：准备本机 NNUE 权重…");
        InputStream input = null;
        try {
            input = HeavyStore.openLocalFirst(ctx, "engine/" + NNUE_LOCAL_NAME);
        } catch (IOException first) {
            try {
                input = HeavyStore.openLocalFirst(ctx, NNUE_LOCAL_NAME);
            } catch (IOException second) {
                throw new IOException("本机缺少 NNUE 权重：" + second.getMessage());
            }
        }
        try (InputStream in = input; OutputStream out = new FileOutputStream(nnue)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            out.flush();
        }
        return nnue.getAbsolutePath();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
