package com.pikafish.chess.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pikafish.chess.heavy.EngineLink
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor

/**
 * ★★★ 询招沙盒（主界面对弈唯一算棋通道）★★★
 *
 * 完全照抄"解惑沙盒"被证实永不卡死的询招纪律：
 *  - 自己持有【全新独立】的 JniEngine 实例（与其它任何引擎互不相干、互不抢锁）
 *  - 懒启动 + 轻量握手（定位 → NNUE → 启动 → UCI 握手），与解惑沙盒同一套路
 *  - ★ 一次一问、随问随走：每个问题都是独立的一次 UCI 事务——
 *      stop → isready(站稳) → position fen → go movetime/depth → 硬死线内收 bestmove → 归零
 *    问完即消，绝不留驻任何后台思考状态（无 ponder、无 go infinite、无驻留协程）
 *  - ★ 单飞串行：红方问完交黑方问（单一问答线程排队），轮流对接同一台引擎，
 *    对接完了就消（发个 stop 即归零），绝无两条搜索同时挤一台引擎
 *  - ★ 每问必答：引擎未就绪 / 锁超时 / 死线到点，也立刻返回（空结果交给上层
 *    "必走子保险"兜底落子），绝不让一步棋悬空、绝不无限等待
 *
 * 设置对接：按秒算的（时间模式）就对接成 go movetime 秒数；按层算的（深度模式）
 * 就对接成 go depth 层数 —— 规矩原样跟过来，用户设什么就问什么。
 */
object SandboxOracle {

    private const val TAG = "SandboxOracle"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null

    /** 全局唯一一台询招引擎（红黑轮流对接的就是它） */
    @Volatile private var oracle: JniEngine? = null

    /** 当前询招是否走异地（辎重包端代算）。 */
    @Volatile private var remoteActive = false
    /** 回退流程防重入。 */
    @Volatile private var fallbackBusy = false
    /** 「异地已接通」提示只弹一次，避免每步都吵。 */
    @Volatile private var remoteNoticeShown = false

    /** 供界面显示：当前算棋方式。 */
    @Volatile var lastEngineMode: String = "本地"
        private set

    // ═══════════ ★ PK 比赛模式：红黑各持一台独立引擎（各自二进制/权重，互不串台） ═══════════
    /** PK 是否开启（开启时 askMove 按走棋方路由到红/黑各自那台） */
    @Volatile var pkMode: Boolean = false
        private set
    @Volatile private var pkRed: JniEngine? = null
    @Volatile private var pkBlack: JniEngine? = null
    @Volatile private var pkRedEntry: UserEngineStore.Entry? = null
    @Volatile private var pkBlackEntry: UserEngineStore.Entry? = null
    /** 双方展示名（界面显示 / 成绩记录用） */
    @Volatile var pkRedLabel: String = ""
        private set
    @Volatile var pkBlackLabel: String = ""
        private set
    /** 本次 PK 用的红/黑引擎清单 id（成绩记录用） */
    @Volatile var pkRedId: String = ""
        private set
    @Volatile var pkBlackId: String = ""
        private set

    /** ★ PK 红/黑两台各自握回来的身份（UCI id name / id author）：
     *  挡屏上的「红方 X VS 黑方 Y」档案据实展示——内置那台也能拿到真实构建日期，绝不串台。 */
    val pkRedSelfIdName: String get() = pkRed?.selfIdName ?: ""
    val pkBlackSelfIdName: String get() = pkBlack?.selfIdName ?: ""
    val pkRedSelfAuthor: String get() = pkRed?.selfAuthor ?: ""
    val pkBlackSelfAuthor: String get() = pkBlack?.selfAuthor ?: ""

    @Volatile private var wantHash = JniEngine.DEFAULT_HASH
    @Volatile private var wantThreads = JniEngine.DEFAULT_THREADS

    /** 单飞问答线程：所有询招在此排队串行执行（红问完→黑问，问完即归零） */
    private val askExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "sandbox_oracle_ask").apply { isDaemon = true }
    }

    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    fun isReady(): Boolean =
        if (pkMode) pkRed?.isEngineReady() == true && pkBlack?.isEngineReady() == true
        else oracle?.isEngineReady() == true

    fun isSearching(): Boolean =
        if (pkMode) pkRed?.isSearching() == true || pkBlack?.isSearching() == true
        else oracle?.isSearching() == true

    /**
     * 同步冷启动（照抄解惑沙盒的懒启动握手：定位 → NNUE → 启动 → 轻量握手）。
     * 返回 null = 成绪就绪；否则返回错误描述。
     */
    @Synchronized
    fun start(context: Context, hashMb: Int = wantHash, threads: Int = wantThreads): String? {
        if (isReady()) return null
        val ctx = context.applicationContext
        appContext = ctx
        wantHash = hashMb.coerceIn(16, JniEngine.MAX_HASH)
        wantThreads = threads.coerceIn(1, JniEngine.MAX_THREADS)
        try {
            val e = JniEngine(ctx)
            e.hashSize = wantHash
            e.threadCount = wantThreads
            // ★ 异地引擎：档位选了「异地」且已配到辎重包端引擎地址 → 本机不加载引擎、
            //   不加载权重、不跑搜索，只把 UCI 命令透传到辎重包端那台，由它替本机算棋。
            //   （本机因此不发热；档位选「本地」或未配对时，remoteEndpoint 保持 null，行为不变。）
            val remote = EngineLink.remoteUsable(ctx)
            remoteActive = remote
            lastEngineMode = if (remote) "异地（辎重包端）" else "本地"
            if (remote) {
                e.remoteEndpoint = EngineLink.endpoint(ctx)
                Log.i(TAG, "★ 异地引擎档位：由辎重包端代算 ${e.remoteEndpoint}")
                // ★ 本机红黑若已在本地档下拉起：先杀，别让它们在后台预热/预想发热
                EngineManager.active?.shutdownLocalEnginesForRemote()
            }
            val err = e.locateEngine() ?: e.copyCurrentNNue() ?: e.startEngine() ?: e.liteInit()
            if (err != null) {
                oracle = null
                if (remote) triggerLocalFallback("异地引擎启动失败：$err")
                return err
            }
            oracle = e
            if (remote && !remoteNoticeShown) {
                remoteNoticeShown = true
                main.post {
                    Toast.makeText(ctx, "异地引擎已接通：本机只显示，算棋由辎重包端完成",
                        Toast.LENGTH_SHORT).show()
                }
            }
            Log.i(TAG, "★ 询招沙盒引擎就绪 (hash=$wantHash, threads=$wantThreads, ${lastEngineMode})")
            return null
        } catch (t: Throwable) {
            Log.e(TAG, "询招沙盒引擎启动异常", t)
            oracle = null
            return "询招沙盒启动异常: ${t.message}"
        }
    }

    /** 哈希/线程设置对接：运行中即时生效，未启动则先记住、启动时套上 */
    fun updateSettings(hash: Int?, threads: Int?) {
        if (hash != null) wantHash = hash.coerceIn(16, JniEngine.MAX_HASH)
        if (threads != null) wantThreads = threads.coerceIn(1, JniEngine.MAX_THREADS)
        oracle?.let { e ->
            try { e.updateSettings(wantHash, wantThreads) } catch (_: Exception) {}
        }
        pkRed?.let { e -> try { e.updateSettings(wantHash, wantThreads) } catch (_: Exception) {} }
        pkBlack?.let { e -> try { e.updateSettings(wantHash, wantThreads) } catch (_: Exception) {} }
    }

    // ══════════════ ★ PK 比赛：进入 / 退出 ══════════════

    /**
     * 进入 PK 比赛模式：红、黑各拉起一台【隔离】引擎（各自二进制＋权重，互不借用「采用」那台）。
     * 返回 null = 双引擎就绪；否则返回错误描述（并自动回滚、退出 PK）。
     */
    @Synchronized
    fun enterPk(
        context: Context,
        redEntry: UserEngineStore.Entry,
        blackEntry: UserEngineStore.Entry,
        hashMb: Int = wantHash,
        threads: Int = wantThreads
    ): String? {
        val ctx = context.applicationContext
        appContext = ctx
        wantHash = hashMb.coerceIn(16, JniEngine.MAX_HASH)
        wantThreads = threads.coerceIn(1, JniEngine.MAX_THREADS)
        shutdownPk()
        try {
            val r = buildIsolatedEngine(ctx, redEntry) ?: run {
                shutdownPk(); return "红方引擎（${redEntry.displayName}）启动失败"
            }
            val b = buildIsolatedEngine(ctx, blackEntry) ?: run {
                try { r.shutdown() } catch (_: Exception) {}
                shutdownPk(); return "黑方引擎（${blackEntry.displayName}）启动失败"
            }
            pkRed = r; pkBlack = b
            pkRedEntry = redEntry; pkBlackEntry = blackEntry
            pkRedId = redEntry.id; pkBlackId = blackEntry.id
            pkRedLabel = redEntry.displayName; pkBlackLabel = blackEntry.displayName
            pkMode = true
            Log.i(TAG, "★ PK 双引擎就绪：红=${redEntry.displayName} / 黑=${blackEntry.displayName}")
            return null
        } catch (t: Throwable) {
            Log.e(TAG, "PK 双引擎启动异常", t)
            shutdownPk()
            return "PK 双引擎启动异常: ${t.message}"
        }
    }

    /** 退出 PK 模式：关掉红黑两台 PK 引擎，恢复单引擎询招。 */
    @Synchronized
    fun exitPk() {
        shutdownPk()
        Log.i(TAG, "已退出 PK 比赛模式")
    }

    /**
     * ★ PK 下一局：按新的红黑归属重排。若两台还是同一对引擎（只是换边/换先后手），
     *   直接复用现有进程实例（只换"谁执红谁执黑"），省掉重新起进程的开销。
     * @return true=已就地换边；false=引擎不是同一对，需要重新 enterPk(...)。
     */
    @Synchronized
    fun respinPk(redEntry: UserEngineStore.Entry, blackEntry: UserEngineStore.Entry): Boolean {
        if (!pkMode) return false
        val r = pkRed ?: return false
        val b = pkBlack ?: return false
        if (setOf(redEntry.id, blackEntry.id) != setOf(pkRedId, pkBlackId)) return false
        pkRed = if (redEntry.id == pkRedId) r else b
        pkBlack = if (blackEntry.id == pkBlackId) b else r
        pkRedEntry = redEntry; pkBlackEntry = blackEntry
        pkRedId = redEntry.id; pkBlackId = blackEntry.id
        pkRedLabel = redEntry.displayName; pkBlackLabel = blackEntry.displayName
        Log.i(TAG, "★ PK 换边续弈：红=${redEntry.displayName} / 黑=${blackEntry.displayName}")
        return true
    }

    private fun shutdownPk() {
        try { pkRed?.shutdown() } catch (_: Exception) {}
        try { pkBlack?.shutdown() } catch (_: Exception) {}
        pkRed = null; pkBlack = null
        pkRedEntry = null; pkBlackEntry = null
        pkRedId = ""; pkBlackId = ""
        pkRedLabel = ""; pkBlackLabel = ""
        pkMode = false
    }

    /** 按一台引擎清单条目，拉起一台隔离引擎（内置→用 App 内置二进制/权重）。 */
    private fun buildIsolatedEngine(ctx: Context, e: UserEngineStore.Entry): JniEngine? {
        return try {
            val eng = JniEngine(ctx)
            eng.isolated = true
            eng.forcedEngineFile = UserEngineStore.binaryFor(ctx, e)   // 内置→null→回退 app 内置
            eng.forcedNnueFile = UserEngineStore.weightFor(ctx, e)
            eng.hashSize = wantHash
            eng.threadCount = wantThreads
            eng.locateEngine()?.let { Log.e(TAG, "PK 引擎定位失败(${e.displayName}): $it"); return null }
            eng.copyCurrentNNue()?.let { Log.e(TAG, "PK 权重失败(${e.displayName}): $it"); return null }
            eng.startEngine()?.let { Log.e(TAG, "PK 引擎启动失败(${e.displayName}): $it"); return null }
            eng.liteInit()?.let { Log.e(TAG, "PK 引擎握手失败(${e.displayName}): $it"); return null }
            eng
        } catch (t: Throwable) {
            Log.e(TAG, "PK 引擎构建异常(${e.displayName})", t)
            null
        }
    }

    /** 异步重启（进程异常 / 巡检抢救时）：杀旧起新，完了回主线程 */
    fun restart(context: Context? = null, onDone: ((Boolean) -> Unit)? = null) {
        val ctx = (context ?: appContext)?.applicationContext
        // ★ PK 进行中：重启的是红黑两台（用原清单条目原样重建），而不是单引擎询招
        if (pkMode) {
            val r = pkRedEntry; val b = pkBlackEntry
            shutdownPk()
            if (ctx == null || r == null || b == null) {
                onDone?.let { main.post { it(false) } }
                return
            }
            Thread {
                val err = enterPk(ctx, r, b)
                if (err != null) Log.e(TAG, "PK 双引擎重启失败: $err")
                onDone?.let { main.post { it(err == null) } }
            }.start()
            return
        }
        try { oracle?.shutdown() } catch (_: Exception) {}
        oracle = null
        if (ctx == null) {
            onDone?.let { main.post { it(false) } }
            return
        }
        Thread {
            val err = start(ctx)
            val ok = err == null
            if (!ok) Log.e(TAG, "询招沙盒重启失败: $err")
            onDone?.let { main.post { it(ok) } }
        }.start()
    }

    /** 停掉在途问题：发 stop，让引擎立刻吐出 bestmove 收尾（对接完了就消） */
    fun stopAsk() {
        try { oracle?.stop() } catch (_: Exception) {}
        try { pkRed?.stop() } catch (_: Exception) {}
        try { pkBlack?.stop() } catch (_: Exception) {}
    }

    /** 新对局：让询招引擎清空换局表、从头来（ucinewgame） */
    fun newGame() {
        try { oracle?.newGame() } catch (_: Exception) {}
        try { pkRed?.newGame() } catch (_: Exception) {}
        try { pkBlack?.newGame() } catch (_: Exception) {}
    }

    fun shutdown() {
        try { oracle?.shutdown() } catch (_: Exception) {}
        oracle = null
        remoteActive = false
        shutdownPk()
    }

    /**
     * ★★★ 异地失效 → 回退本机（用户选定策略：异地优先 + 可回退）。★★★
     *
     * <p>触发条件：异地引擎启动失败 / 询招空手（多半是辎重包端息屏、被杀、Wi-Fi 断了）。
     * 行为：弹一条明确提示 → 清掉异地档位与地址 → 重启本机红黑 + 本机询招引擎。
     * 之后要再走异地，重新配对一次即可（不会来回抖动）。</p>
     */
    private fun triggerLocalFallback(reason: String) {
        if (fallbackBusy || !remoteActive) return
        val ctx = appContext ?: return
        fallbackBusy = true
        remoteActive = false
        lastEngineMode = "本地（异地回退）"
        Log.w(TAG, "★ 异地引擎失效，回退本机：$reason")
        main.post {
            try {
                Toast.makeText(ctx,
                    "异地引擎断开（$reason）\n已切回本机计算：本机会发热",
                    Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
        Thread {
            try {
                try { oracle?.shutdown() } catch (_: Exception) {}
                oracle = null
                // 清异地档位与地址：之后一律本地，直到用户重新配对
                EngineLink.forceLocal(ctx)
                // 重新拉起本机红黑（本地询招由下面的 start 负责）
                EngineManager.active?.restartLocalEngines()
                val err = start(ctx)
                Log.i(TAG, "回退本地询招引擎: " + (err ?: "OK"))
            } catch (t: Throwable) {
                Log.e(TAG, "回退本地失败", t)
            } finally {
                fallbackBusy = false
            }
        }.start()
    }

    /**
     * ★★★ 一次询招：问完即消。红方问完交黑方问，问完归零，绝不留驻。
     * onThinking / onResult 一律回主线程（绝不碰引擎内部线程）。
     * 结果为空（未就绪/超时/异常）时交付 null，由上层"必走子保险"兜底落子。
     */
    fun askMove(
        board: Board,
        timeMs: Long?,
        depth: Int?,
        restrictToUci: Set<String>? = null,
        excludedUci: Set<String> = emptySet(),
        onThinking: ((Int, Int, Int, Long, Long, String) -> Unit)? = null,
        onResult: (Move?) -> Unit
    ) {
        // ★ 快照隔离（"凭空多子"根治之二）：在【调用线程（主线程）】先把活棋盘克隆成
        //   一份冻结快照，后台问答线程只碰这份快照——绝不读、绝不写主线程正在使用的
        //   活棋盘。配合 getLegalMoves 的"副本模拟"，双保险杜绝并发交错产生多子。
        val snapshot = try { board.clone() } catch (t: Throwable) { board }
        askExecutor.execute {
            // ★ PK 模式：按快照的走棋方路由到红/黑各自那台独立引擎；不碰单引擎询招。
            val pkEngine = if (pkMode) {
                if (snapshot.currentPlayer == PieceColor.RED) pkRed else pkBlack
            } else null
            // ★ 懒启动双保险：万一主启动没拉起询招引擎（或它曾倒下），就在问答线程里
            //   就地冷启动一次（单飞串行，不会与任何在途问题挤在一起）。
            //   PK 模式下不冷启动单引擎询招（红黑两台由 enterPk 负责拉起）。
            if (!pkMode && (oracle == null || !oracle!!.isEngineReady())) {
                val ctx = appContext
                if (ctx != null) {
                    try {
                        Log.w(TAG, "询招：引擎未就绪，就地冷启动（仅一次开销）…")
                        start(ctx)
                    } catch (t: Throwable) {
                        Log.e(TAG, "询招懒启动异常", t)
                    }
                }
            }
            val reply = try {
                val e = pkEngine ?: oracle
                if (e == null || !e.isEngineReady()) {
                    Log.w(TAG, "询招：引擎仍未就绪，快速空手而归（上层必走子保险兜底）")
                    JniEngine.OracleReply()
                } else {
                    e.askBestMove(snapshot, timeMs, depth, restrictToUci, excludedUci) { d, sd, sc, nodes, nps, pv ->
                        onThinking?.let { cb -> main.post { cb(d, sd, sc, nodes, nps, pv) } }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "询招异常（空手而归，上层兜底）", t)
                JniEngine.OracleReply()
            }
            // 询招结果 → 合法着法校验（必须是当前方的合法着法）→ 主线程交付
            if (reply.bestMoveUci.isEmpty() && remoteActive) {
                // ★ 异地引擎空手：多半是对端息屏/被杀/Wi-Fi 断。按「异地优先+可回退」切回本机。
                triggerLocalFallback("异地引擎未响应")
            }
            var move: Move? = null
            if (reply.bestMoveUci.isNotEmpty()) {
                try {
                    val cand = Move.fromUci(reply.bestMoveUci, snapshot)
                    if (cand != null) {
                        val legal = snapshot.getLegalMoves(snapshot.currentPlayer)
                        if (legal.any { it.from == cand.from && it.to == cand.to }) move = cand
                        else Log.w(TAG, "询招结果非当前方合法着法: ${reply.bestMoveUci}（交上层兜底）")
                    }
                } catch (_: Exception) {}
            }
            main.post { onResult(move) }
        }
    }
}
