package com.yk.xiangqi.engine;

import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Move;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单一串行 UCI 进程；回调在它的工作线程中发出。
 */
public final class UciEngine implements Closeable {
    private static final long INFO_INTERVAL_MS = 200;
    /**
     * 握手「uci → uciok」的最长等待。进程起来后一般几十毫秒就有回音，这里留足富余。
     */
    private static final long HANDSHAKE_TIMEOUT_MS = 10_000;
    /**
     * 配置「isready → readyok」的最长等待。要容纳手机端加载 NNUE 权重（大网络较慢）的耗时，
     * 因此比握手宽松得多。
     */
    private static final long READY_TIMEOUT_MS = 20_000;
    /**
     * 轮询间隔：一律以 {@link BufferedReader#ready()} 非阻塞探测，绝不把唯一的 io 线程挂死。
     */
    private static final long POLL_INTERVAL_MS = 15;

    public interface Listener {
        /**
         * 已收到 uciok；调用方现在可依据声明的 Option 选择配置。
         */
        void onUciOptions(List<UciOption> options);

        /**
         * 引擎握手时自报的身份：{@code id name} 与 {@code id author}。
         * 名称里通常含版本号与构建日期，用于区分当前加载的是哪一个引擎。
         */
        void onEngineIdentity(String name, String author);

        /**
         * 启动或配置引擎失败。
         */
        void onEngineFailed(String message);

        /**
         * 配置已收到 readyok，可以开始搜索。
         */
        void onReady();

        /**
         * 每个 200ms 读取周期内最多交付一条最新的完整主变。
         */
        void onInfo(long searchId, ThinkingInfo info);

        void onBestMove(long searchId, BestMoveInfo info);

        void onSearchFailed(long searchId, String message);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Listener listener;
    /**
     * 进程与两条管道会被「发起重启的线程」与「io 工作线程」同时读写
     * （重启要在别的线程里立刻毁掉旧进程，好让卡在旧搜索里的 io 线程马上解脱），
     * 因此声明为 volatile，保证跨线程可见性。
     */
    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile BufferedReader stdout;
    private final AtomicLong searchId = new AtomicLong();
    private List<UciOption> declaredOptions = List.of();
    /** 引擎最近一次自己报告的错误，例如「The network file ... was not loaded successfully.」。 */
    private volatile String lastError;
    /** 引擎退出前最近若干行输出；没有明确 ERROR 时也能据此判断死因。 */
    private final java.util.List<String> tailLines = new java.util.ArrayList<>();
    private static final int TAIL_LIMIT = 40;

    public UciEngine(Listener listener) {
        this.listener = listener;
    }

    /**
     * 启动进程并完成 {@code uci → uciok} 握手。
     *
     * <p>旧进程<b>在当前线程立即销毁</b>，而不是排到 io 队列里：io 是单线程，旧的无限搜索
     * 正占着它死等 bestmove；若把「销毁旧进程」也排到同一个队列尾部，就会形成
     * 「搜索等进程死、销毁排在其后永远轮不到」的死锁，重启按钮从此形同虚设。
     * 先在这里把旧进程杀死，卡住的旧搜索立刻发现进程已死而返回，io 线程随即腾出来承接新启动。</p>
     */
    public void start(File executable, File workingDir) {
        stopInternal();
        io.execute(() -> {
            try {
                lastError = null;
                tailLines.clear();
                declaredOptions = List.of();
                process = new ProcessBuilder(executable.getAbsolutePath()).directory(workingDir).redirectErrorStream(true).start();
                stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
                stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
                send("uci");
                Handshake handshake = awaitUciOk();
                if (handshake == null)
                    throw new IOException("UCI handshake timeout");
                this.declaredOptions = handshake.options;
                listener.onEngineIdentity(handshake.name, handshake.author);
                listener.onUciOptions(handshake.options);
            } catch (Exception e) {
                listener.onEngineFailed("引擎启动失败: " + describe(e));
                stopInternal();
            }
        });
    }

    /**
     * 在收到 {@link Listener#onUciOptions(List)} 后调用：写入配置，并以 readyok
     * 作为配置生效的屏障。调用方应先停止正在进行的搜索。
     */
    public void configure(Map<String, String> settings) {
        Map<String, String> requested = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        io.execute(() -> {
            try {
                if (stdin == null)
                    throw new IOException("引擎尚未启动");
                for (Map.Entry<String, String> option : requested.entrySet())
                    send(declaredOption(option.getKey()).setOption(option.getValue()));
                send("isready");
                if (!awaitReadyOk())
                    throw new IOException("UCI ready timeout");
                listener.onReady();
            } catch (Exception e) {
                listener.onEngineFailed("引擎配置失败: " + describe(e));
            }
        });
    }

    /**
     * 触发一次 UCI button 选项，不把它当作持久化配置。
     */
    public void pressOption(String name) {
        io.execute(() -> {
            try {
                if (stdin == null)
                    throw new IOException("引擎尚未启动");
                send(declaredOption(name).press());
                send("isready");
                if (!awaitReadyOk())
                    throw new IOException("UCI ready timeout");
                listener.onReady();
            } catch (Exception e) {
                listener.onEngineFailed("引擎选项执行失败: " + describe(e));
            }
        });
    }

    private UciOption declaredOption(String name) {
        for (UciOption option : declaredOptions)
            if (option.name.equalsIgnoreCase(name))
                return option;
        return UciOption.unknown(name);
    }

    /**
     * 等待 readyok，并把它当作配置生效的屏障。
     *
     * <p>读取一律用 {@link BufferedReader#ready()} 非阻塞探测 + 短睡轮询，绝不直接阻塞在
     * {@code readLine()} 上：否则一个迟迟不回话的引擎会把唯一的 io 线程永久挂死，
     * 之后所有「重启 / 切引擎 / 搜索」全部排在它后面动弹不得。</p>
     *
     * <p>同时盯住进程存活：引擎若已退出就立刻返回失败（并把它的遗言留在 {@code lastError}），
     * 好让上层马上走重启，而不是干等到超时。</p>
     */
    private boolean awaitReadyOk() throws IOException {
        long end = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < end) {
            BufferedReader current = stdout;
            if (current == null)
                throw new IOException("引擎管道已关闭");
            while (current.ready()) {
                String line = current.readLine();
                if (line == null)
                    break;
                observe(line);
                if (line.trim().equalsIgnoreCase("readyok"))
                    return true;
            }
            if (process == null || !process.isAlive())
                return false;
            sleepQuietly(POLL_INTERVAL_MS);
        }
        return false;
    }

    /** 短暂休眠，供轮询使用；被打断只还原中断标志，不抛异常。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把引擎自己打印的错误记下来。
     *
     * <p>引擎在权重缺失或架构不匹配时会先说明原因再退出；不记下来的话，用户只能看到
     * 一句含糊的「Stream closed」，无从判断到底哪里不对。</p>
     *
     * <p>同时保留最近若干行普通输出，万一遗言不含 ERROR 字样（例如
     * 「Failed to create search thread」「Failed to allocate ... bytes」），
     * 也能从尾部输出反推死因。</p>
     */
    private void observe(String line) {
        if (line == null)
            return;
        String trimmed = line.trim();
        if (trimmed.isEmpty())
            return;
        tailLines.add(trimmed);
        if (tailLines.size() > TAIL_LIMIT)
            tailLines.remove(0);
        if (trimmed.contains("ERROR") || trimmed.contains("CRITICAL")
                || trimmed.contains("Unsupported") || trimmed.contains("Invalid")
                || trimmed.contains("Failed") || trimmed.contains("failed")) {
            if (trimmed.startsWith("info string "))
                trimmed = trimmed.substring("info string ".length()).trim();
            lastError = trimmed;
        }
    }

    /** 是否是引擎明确的命令级失败（如局面不合法导致 position 命令被拒）。 */
    private static boolean isFatal(String message) {
        if (message == null)
            return false;
        return message.contains("CRITICAL") || message.contains("Unsupported")
                || message.contains("failed") || message.contains("Failed");
    }

    /**
     * 失败时优先给出引擎的原话；引擎已退出时补一句它走了，其次才退回底层异常文本。
     *
     * <p>即使没有明确的 ERROR 行，也把引擎退出前最后若干行输出带上，
     * 免得只剩一句「退出码 1」让人无从下手。</p>
     */
    private String describe(Exception error) {
        String reported = lastError != null ? lastError : drainError();
        if (reported != null && !reported.isEmpty())
            return reported;
        if (process != null && !process.isAlive()) {
            if (!tailLines.isEmpty())
                return "引擎已退出（退出码 " + exitCode() + "），最后输出："
                        + System.lineSeparator() + String.join(System.lineSeparator(), tailLines);
            return "引擎已退出（退出码 " + exitCode() + "）";
        }
        return error.getMessage();
    }

    /**
     * 进程已死时把管道里剩余的输出全部读完。引擎的遗言（无论是否含 ERROR）
     * 都会被 {@link #observe(String)} 捕获；这里不设超时，读到流结束为止，
     * 避免因 200ms 不够而漏掉真正的死因。
     */
    private String drainError() {
        if (stdout == null)
            return lastError;
        try {
            String line;
            while ((line = stdout.readLine()) != null)
                observe(line);
        } catch (Exception ignored) {
            // 管道已关闭，说明确实没留下可读的错误。
        }
        return lastError;
    }

    private int exitCode() {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            return -1;
        }
    }

    /** 一次 uci → uciok 握手的产物：声明的选项与引擎自报的身份。 */
    private static final class Handshake {
        final List<UciOption> options;
        final String name;
        final String author;

        Handshake(List<UciOption> options, String name, String author) {
            this.options = options;
            this.name = name;
            this.author = author;
        }
    }

    /**
     * 等待 uciok，并收集引擎实际声明的选项与 {@code id name}/{@code id author}。
     *
     * <p>同 {@link #awaitReadyOk()}：以 {@code ready()} 非阻塞轮询 + 超时收尾，绝不直接阻塞
     * 在 {@code readLine()} 上；进程已死则立刻返回，交由上层报错并重启。</p>
     */
    private Handshake awaitUciOk() throws IOException {
        long end = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
        List<UciOption> options = new ArrayList<>();
        String name = null;
        String author = null;
        while (System.currentTimeMillis() < end) {
            BufferedReader current = stdout;
            if (current == null)
                throw new IOException("引擎管道已关闭");
            while (current.ready()) {
                String line = current.readLine();
                if (line == null)
                    break;
                observe(line);
                String trimmed = line.trim();
                UciOption option = UciOption.parse(line);
                if (option != null)
                    options.add(option);
                else if (trimmed.startsWith("id name "))
                    name = trimmed.substring("id name ".length()).trim();
                else if (trimmed.startsWith("id author "))
                    author = trimmed.substring("id author ".length()).trim();
                if (trimmed.equalsIgnoreCase("uciok"))
                    return new Handshake(List.copyOf(options), name, author);
            }
            if (process == null || !process.isAlive())
                return null;
            sleepQuietly(POLL_INTERVAL_MS);
        }
        return null;
    }

    static String setOption(String name, String value) {
        if (name == null || name.isBlank() || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0)
            throw new IllegalArgumentException("无效的 UCI 选项名");
        if (value == null)
            return "setoption name " + name;
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("无效的 UCI 选项值");
        return "setoption name " + name + " value " + value;
    }

    /**
     * 终止旧搜索后开始新搜索；旧搜索仍会被读取到 bestmove，避免该行泄漏给新搜索。
     */
    public synchronized long go(GameState game, GoParams params) {
        long request = searchId.incrementAndGet();
        stopSearch();
        io.execute(() -> search(request, game, params));
        return request;
    }

    private void search(long request, GameState game, GoParams params) {
        // 引擎尚未启动、正在重启或已被关闭时，stdin 为 null。
        // 此时绝对不能静默返回——上层拿着 request 这个 searchId 等回调，
        // 静默返回会让界面永远停在「思考中」，AI 模式下也永远不走子。
        if (stdin == null) {
            if (request == searchId.get())
                listener.onSearchFailed(request, "引擎尚未就绪");
            return;
        }
        try {
            // 每次搜索前清掉上一轮遗留的错误，免得旧错误被当成本次搜索的失败原因。
            lastError = null;
            send(position(game));
            send(params.uci());
            readSearch(request);
        } catch (Exception e) {
            if (request == searchId.get())
                listener.onSearchFailed(request, describe(e));
        }
    }

    /** 引擎进程是否仍在运行；引擎自己退出后，上层需要重新拉起它。 */
    public boolean isAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /**
     * 按 UCI 语义结束当前搜索，保留这轮随后返回的 bestmove。
     */
    public synchronized void stop() {
        stopSearch();
    }

    /**
     * 取消当前搜索，使其后续 bestmove 失效。
     */
    public synchronized void cancelSearch() {
        searchId.incrementAndGet();
        stopSearch();
    }

    private synchronized void stopSearch() {
        try {
            if (stdin != null)
                send("stop");
        } catch (Exception ignored) {
        }
    }

    private void readSearch(long expected) throws IOException {
        // 先把本轮要读的管道与进程抓成局部变量。重启时会在别的线程把字段清空，
        // 局部抓取保证这里读到的要么是完整的旧管道、要么立刻发现进程已死，
        // 绝不会因为字段被清空而空指针，也不会死等一个再也不会来的 bestmove。
        BufferedReader reader = stdout;
        Process watched = process;
        if (reader == null)
            return;
        while (true) {
            ThinkingInfo info = null;
            BestMoveInfo bestMove = null;
            while (reader.ready()) {
                String line = reader.readLine();
                if (line == null)
                    break;
                observe(line);
                if (line.startsWith("info")) {
                    ThinkingInfo parsed = ThinkingInfo.parse(line);
                    if (parsed != null && !parsed.pv.isEmpty())
                        info = parsed;
                } else if (line.startsWith("bestmove")) {
                    bestMove = BestMoveInfo.parse(line);
                    break;
                }
            }

            if (info == null && bestMove == null) {
                // 引擎自己报错（例如局面不合法）后通常直接退出。若不在这里把错误抛出去，
                // 就会永远等一个不会到来的 bestmove，把唯一的 io 线程彻底堵死。
                // 只认命令级失败，避免某些引擎的普通错误日志被误判成搜索失败。
                if (isFatal(lastError))
                    throw new IOException(lastError);
                if (process == null || watched == null || !watched.isAlive()) {
                    // 进程已死，遗言可能还在管道里；先把剩余输出读完再抛，
                    // 否则 describe() 只能拿到一句「退出码 1」，看不出真正原因。
                    drainError();
                    throw new EOFException("引擎已退出");
                }
                pauseSearchRead();
                continue;
            }

            boolean current = expected == searchId.get();
            if (current) {
                if (info != null)
                    listener.onInfo(expected, info);
                if (bestMove != null)
                    listener.onBestMove(expected, bestMove);
            }
            if (bestMove != null)
                return;
            pauseSearchRead();
        }
    }

    private static void pauseSearchRead() throws IOException {
        try {
            Thread.sleep(INFO_INTERVAL_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("引擎读取被中断", error);
        }
    }

    /**
     * UCI 是文本协议；只有此进程边界才把根位棋盘局面及当前游标之前的着法序列化。
     */
    static String position(GameState game) {
        StringBuilder command = new StringBuilder("position fen ").append(game.initialPosition().toFen());
        List<Move> moves = game.moves();
        if (!moves.isEmpty()) {
            command.append(" moves");
            for (Move move : moves)
                command.append(' ').append(move.uci());
        }
        return command.toString();
    }

    private synchronized void send(String s) throws IOException {
        // 重启会在别的线程把 stdin 置空；这里先抓成局部变量再判空，
        // 避免「检测到非空、写入时已成空」的空指针。
        BufferedWriter writer = stdin;
        if (writer == null)
            throw new IOException("引擎尚未启动");
        writer.write(s);
        writer.newLine();
        writer.flush();
    }

    private synchronized void stopInternal() {
        Process dying = process;
        process = null;
        stdin = null;
        stdout = null;
        try {
            if (dying != null)
                dying.destroy();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        // 先在当前线程把进程杀掉并清空管道，确保立即释放；再中断 io 队列。
        stopInternal();
        io.shutdownNow();
    }
}
