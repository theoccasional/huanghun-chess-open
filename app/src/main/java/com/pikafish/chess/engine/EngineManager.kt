package com.pikafish.chess.engine

import android.content.Context
import android.util.Log
import com.pikafish.chess.engine.gpu.GpuNeuralEngine
import com.pikafish.chess.heavy.EngineLink
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor

/**
 * ★★★ 红黑对称双引擎管理器 ★★★
 *
 * 设计目标（对应"红引擎只替红着想、黑引擎只替黑着想"）：
 *  - 红方、黑方各持一个独立 JniEngine 实例，各自独立子进程、独立 UCI 管道、
 *    独立 outputQueue / 搜索锁。一个引擎卡死绝不影响另一个。
 *  - 所有按颜色的调用（engineFor / isEngineReadyFor / restartEngineFor / 搜索）
 *    精确路由到对应颜色的那台；全局控制（stop / newGame / updateSettings /
 *    shutdown）对称广播到两台，保持 CHessGame 调用点语义不变。
 *  - 为减轻镜像资产负担，两台共用同一份 NNUE 权重文件，但进程与管道完全隔离。
 *
 * 对外接口保持与旧版兼容，ChessGame / MainActivity 现有点（engineFor 已存在）
 * 无需大改即可得到真正的红黑隔离。
 */
class EngineManager(private val context: Context) {

    companion object {
        private const val TAG = "EngineManager"

        /** 当前活跃实例：异地模式切换 / 断线回退时，用来停掉或重启本机红黑引擎。 */
        @Volatile
        var active: EngineManager? = null
            private set
    }

    /** 红方引擎 — 只负责红方走棋 */
    private val redEngine: JniEngine = JniEngine(context)
    /** 黑方引擎 — 只负责黑方走棋 */
    private val blackEngine: JniEngine = JniEngine(context)

    /** ★ GPU 神经引擎（唯一一个，评估对红黑对称，由网络特征自动区分双方视角）。
     * 惰性创建：仅在 GPU 模式启用时才会拉起 GL 线程与 EGL 上下文。
     */
    private val gpuEngine by lazy { GpuNeuralEngine(context) }

    /** ★ 引擎运行模式：恒为 false=皮卡鱼(UCI子进程)。纯内置、强棋力、全机型可用 */
    @Volatile var gpuMode: Boolean = false

    private val useGpu: Boolean get() = gpuMode

    // ── 用户可调设置（默认 4 线程 / 256MB，上限 8 线程 / 512MB）──
    @Volatile var desiredHash: Int = JniEngine.DEFAULT_HASH
    @Volatile var desiredThreads: Int = JniEngine.DEFAULT_THREADS

    init { active = this }

    /** 兼容旧属性：读取/设置期望值（带设备内存自适应保护） */
    var hashSize: Int
        get() = desiredHash
        set(v) {
            desiredHash = safeHash(v)
            forEachEngine { it.updateSettings(desiredHash, desiredThreads) }
        }

    var threadCount: Int
        get() = desiredThreads
        set(v) {
            desiredThreads = v.coerceIn(1, JniEngine.MAX_THREADS)
            forEachEngine { it.updateSettings(desiredHash, desiredThreads) }
        }

    /** 引擎重启回调 */
    var onEngineRestarted: (() -> Unit)? = null

    /**
     * 后台多线预想信息回调。
     * ★ 关键修复：必须透传到红黑两台真实引擎的 onPonderInfo，否则上层设置的
     *   回调挂在 Manager 上永远不触发 → 后台思考"层数没上报"。
     */
    var onPonderInfo: ((depth: Int, score: Int, pv: String, lines: List<JniEngine.PonderLine>) -> Unit)? = null
        set(v) {
            field = v
            redEngine.onPonderInfo = v
            blackEngine.onPonderInfo = v
            if (gpuMode) gpuEngine.onPonderInfo = v
        }

    /** 单键面板 / 其它功能用到的单一引擎信息回调（后台预测） */
    var onThinkingCallback: ((depth: Int, selDepth: Int, score: Int, nodes: Long, nps: Long, pv: String) -> Unit)? = null

    /** 遍历两台引擎 */
    private inline fun forEachEngine(block: (JniEngine) -> Unit) {
        block(redEngine)
        block(blackEngine)
    }

    /**
     * ★★★ 「异地引擎」档位是否生效：配对时选了异地，且已记下辎重包端的引擎地址。★★★
     *
     * <p>这是「配对成功但主界面引擎加载失败」的根治点：过去只有 {@code SandboxOracle}
     * 认这个档位，红黑两台本机引擎照旧起本地进程——轻量操作包没有权重，就一直
     * 「引擎异常/重启中」。现在整台 EngineManager 都按这一档位行事。</p>
     */
    private fun remoteMode(): Boolean = EngineLink.remoteUsable(context)

    /** 按颜色取引擎 —— 红方引擎 / 黑方引擎，真正分离 */
    fun engineFor(color: PieceColor): JniEngine = when (color) {
        PieceColor.RED -> redEngine
        PieceColor.BLACK -> blackEngine
    }

    /** 指定方引擎是否就绪（★ 询招架构：全局唯一一台询招引擎，红黑通用） */
    fun isEngineReadyFor(color: PieceColor): Boolean = SandboxOracle.isReady()

    // ── 生命周期 ──

    /**
     * 复制 NNUE 权重。红黑两台引擎共用同一份 NNUE 文件；
     * 但每台 JniEngine 都要单独记录各自的 nnuePath，所以两台都跑一遍
     * （文件已存在时内部直接复用，不重复复制）。
     */
    fun copyCurrentNNue(): String? {
        if (useGpu) return null   // GPU 模式无需复制 NNUE（网络权重内嵌/外置路径）
        if (remoteMode()) return null   // ★ 异地引擎：权重在辎重包端加载，本机一字节都不碰
        val e1 = redEngine.copyCurrentNNue()
        if (e1 != null) return e1
        return blackEngine.copyCurrentNNue()
    }

    /**
     * 定位引擎二进制。红、黑两台引擎都必须记录各自的 enginePath（同一个二进制，
     * 但 path 字段是每台各自私有的）。只定位红引擎会导致黑引擎以空路径启动：
     * 报 "Cannot run program \"\"" 错误。
     */
    fun locateEngine(): String? {
        if (useGpu) return null
        if (remoteMode()) return null   // ★ 异地引擎：引擎二进制在辎重包端，本机无需定位
        val e1 = redEngine.locateEngine()
        if (e1 != null) return e1
        return blackEngine.locateEngine()
    }

    /**
     * 启动引擎子进程（对称启动红、黑两台）。纯内置皮卡鱼，恒定双进程。
     * ★★ 询招沙盒接线：主启动流程里同步把询招引擎（对弈唯一算棋通道）也拉起来，
     *   让 isEngineReady() / askMove() 一进来就绪（askMove 另有懒启动双保险）。
     *   询招引擎启动失败只记日志不报错 —— 上层尚有懒启动与"必走子保险"兜底，
     *   绝不因它失败而把整个 APP 启动流程判死刑。
     */
    fun startEngine(): String? {
        if (useGpu) return null
        // ★★★ 异地引擎档位：本机一律不起红黑进程，只拉「询招沙盒」一路——
        //   它自己会连到辎重包端的引擎端口，由那台电脑替本机算棋。
        //   如此既不会在本机白起进程（轻量操作包没权重必然起不来 → "引擎异常/重启中"），
        //   也避免红黑两台各占辎重包端的同一台引擎、互相堵住。
        if (remoteMode()) {
            Log.i(TAG, "★ 异地引擎档位：跳过本机红黑引擎，仅接通辎重包端代算")
            // ★ 配对切档前可能已按本地档拉起红黑两台：这里先杀掉，避免它们在后台
            //   继续预热 / 多线预想，导致操作包端「明明异地算棋却还在发热」。
            shutdownLocalEnginesForRemote()
            return SandboxOracle.start(context, desiredHash, desiredThreads)
        }
        // ★★★ 并行冷启动：红引擎 / 黑引擎 / 询招沙盒三路同时拉起 ★★★
        //   三台彼此独立（各自子进程、各自 UCI 管道、各自搜索锁），天生可并行——
        //   串行启动纯属等待叠加。三线程同时冷启动后，开机墙钟耗时≈单台引擎的冷启动
        //   时间（原来≈三台之和），且全在后台线程，主线程 UI 一律不卡。
        //   前置条件：权重文件已由 copyCurrentNNue() 备好，三路只读同一份已存在的
        //   NNUE，绝不并发写同一文件。
        val results = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val jobs = listOf<Pair<String, () -> String?>>(
            "红方引擎" to { redEngine.startEngine() },
            "黑方引擎" to { blackEngine.startEngine() },
            "询招引擎" to { SandboxOracle.start(context, desiredHash, desiredThreads) }
        )
        val threads = jobs.map { (name, body) ->
            Thread {
                try {
                    body()?.let { results.add(name to it) }
                } catch (t: Throwable) {
                    results.add(name to (t.message ?: "未知异常"))
                }
            }.apply { isDaemon = true }.also { it.start() }
        }
        for (t in threads) t.join()

        // ★ 询招引擎随主启动失败只记日志不阻断（上层另有询招懒启动 + "必走子保险"兜底）
        results.firstOrNull { it.first == "询招引擎" }?.let {
            Log.w(TAG, "询招沙盒随主启动未成（将由询招懒启动再试）: ${it.second}")
        }
        // ★ 红/黑两台硬失败才阻断 APP 启动（保持原语义）
        return results.firstOrNull { it.first != "询招引擎" }
            ?.let { "${it.first}启动失败: ${it.second}" }
    }

    /**
     * 完整初始化（UCI 握手 + NNUE + Hash/线程 + 验证搜索），红黑两台并行初始化。
     * 两台各自独立进程/管道/锁，并行握手互不干扰；墙钟耗时由"两台之和"降到"较慢那台"。
     */
    fun fullInit(): String? {
        if (useGpu) return null   // GPU 引擎初始化在自身 GL 线程异步完成
        if (remoteMode()) return null   // ★ 异地引擎：握手在辎重包端完成，本机无需
        redEngine.hashSize = desiredHash
        redEngine.threadCount = desiredThreads
        blackEngine.hashSize = desiredHash
        blackEngine.threadCount = desiredThreads
        val results = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val threads = listOf("红方引擎" to redEngine, "黑方引擎" to blackEngine).map { (name, e) ->
            Thread {
                try {
                    e.fullInit()?.let { results.add(name to it) }
                } catch (t: Throwable) {
                    results.add(name to (t.message ?: "未知异常"))
                }
            }.apply { isDaemon = true }.also { it.start() }
        }
        for (t in threads) t.join()
        val err = results.firstOrNull()?.let { "${it.first}初始化失败: ${it.second}" }
        if (err == null) {
            Log.i(TAG, "红黑双引擎就绪 (hash=$desiredHash, threads=$desiredThreads)")
        }
        return err
    }

    fun updateSettings(hash: Int? = null, threads: Int? = null) {
        if (hash != null) desiredHash = safeHash(hash)
        if (threads != null) desiredThreads = threads.coerceIn(1, JniEngine.MAX_THREADS)
        forEachEngine { it.updateSettings(desiredHash, desiredThreads) }
        // ★ 询招引擎同步对接（运行中即时生效，未启动则记住、启动时套上）
        SandboxOracle.updateSettings(desiredHash, desiredThreads)
    }

    /**
     * ★ 哈希内存自适应保护：把请求的哈希 clamp 到安全范围，
     *   上限 = min(期望值, MAX_HASH, 单引擎可承受内存)。
     *   双引擎各分一份，绝不会因把哈希调很大而把系统内存吃垮、等待回收变成"闷死"。
     */
    private fun safeHash(h: Int): Int {
        val wanted = h.coerceIn(16, JniEngine.MAX_HASH)
        val availMb = try {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val availBytes = if (mi.availMem > 0L) mi.availMem else try { mi.totalMem } catch (_: Exception) { 0L }
            (availBytes / (1024L * 1024L) / 6L) // 可用内存六分之一留给本引擎哈希
        } catch (_: Exception) { 512L }
        return wanted.coerceAtMost(availMb.toInt()).coerceAtLeast(16)
    }

    /** 引擎整体是否就绪（★ 询招架构：就绪 = 询招沙盒引擎就绪） */
    fun isEngineReady(): Boolean = if (useGpu) gpuEngine.isReady() else SandboxOracle.isReady()

    /** 引擎来源提示：异地（电脑端代算）/ 外置（用户装载）/ 内置皮卡鱼 */
    fun externalPackageStatus(): String = when {
        remoteMode() -> "异地引擎（电脑端代算）"
        UserEngineStore.isActive(context) -> "已装载外置引擎"
        else -> "内置皮卡鱼引擎"
    }

    /**
     * ★★ 外置引擎装载 / 卸载之后：重启"询招沙盒"（主界面对弈唯一算棋通道），
     *   让新引擎即时生效。其余沙盒（解惑/复盘/打谱）各自进页时新建引擎，下次自动跟随。
     */
    fun reloadForExternalEngine(onDone: ((Boolean) -> Unit)? = null) {
        if (useGpu) {
            onDone?.invoke(true)
            return
        }
        SandboxOracle.restart(context) { ok -> onDone?.invoke(ok) }
    }

    /**
     * 决定引擎运行模式。★ 纯内置统一走皮卡鱼 UCI 引擎：
     *   全机型可用、无需 GPU/GL、棋力最强、绝不因"设备不支持"而弹窗退出。
     *   GPU 神经引擎与外置包逻辑已整体移除，不再涉及。
     */
    fun resolveEngineMode() {
        gpuMode = false
        Log.i(TAG, "引擎运行模式：皮卡鱼(UCI)（纯内置，恒定）")
    }

    // ── 对局控制（广播到两台 / GPU 模式走 GPU） ──

    fun newGame() {
        if (useGpu) { gpuEngine.newGame(); return }
        // ★ 询招架构：新对局让询招引擎换新（ucinewgame），一次一问通道从头来
        SandboxOracle.newGame()
    }

    fun stopSearch() {
        if (useGpu) { gpuEngine.stopSearch(); return }
        // ★ 询招架构：停 = 给在途问题发 stop（对接完了就消，不留任何驻留状态）
        SandboxOracle.stopAsk()
    }

    fun stopPonder() {
        if (useGpu) { gpuEngine.stopPonder(); return }
        // ★ 预想跑在"人脑一方"的空闲引擎上（go infinite）。停预想必须真停那台——
        //   否则 go infinite 永不收尾、searchLock 一直被占，该引擎从此再也算不了棋
        //   （"现世报"评估静默失效、该出子时卡死）。
        redEngine.stopPonder()
        blackEngine.stopPonder()
    }

    fun isPondering(): Boolean =
        if (useGpu) gpuEngine.isPondering()
        else redEngine.isPondering() || blackEngine.isPondering()

    /**
     * 当前"正在预想 / 刚预想过"的那台空闲引擎（无颜色的旧取值接口用它取结果）。
     * 预想结束后 ponderBestMove/Pv 仍保留到下一轮启动前，故可继续读到上一轮成果。
     */
    private fun ponderResultEngine(): JniEngine? = when {
        redEngine.isPondering() -> redEngine
        blackEngine.isPondering() -> blackEngine
        redEngine.getPonderBestMove().isNotEmpty() -> redEngine
        blackEngine.getPonderBestMove().isNotEmpty() -> blackEngine
        else -> null
    }

    fun getPonderBestMove(): String =
        if (useGpu) gpuEngine.getPonderBestMove()
        else (ponderResultEngine()?.getPonderBestMove() ?: "")

    fun getPonderScore(): Int =
        if (useGpu) gpuEngine.getPonderScore()
        else (ponderResultEngine()?.getPonderScore() ?: 0)

    fun getPonderDepth(): Int =
        if (useGpu) gpuEngine.getPonderDepth()
        else (ponderResultEngine()?.getPonderDepth() ?: 0)

    fun getPonderPv(): String =
        if (useGpu) gpuEngine.getPonderPv()
        else (ponderResultEngine()?.getPonderPv() ?: "")

    /** 指定方引擎的当前后台多线预案（人脑思考空档里，那台空闲引擎推出的 ①②③ 多条预想） */
    fun getPonderLinesFor(color: PieceColor): List<JniEngine.PonderLine> =
        if (useGpu) gpuEngine.getPonderLines()
        else engineFor(color).getPonderLines()

    /** 指定方引擎里，命中了"预测 uci"的那条预案；未命中返回 null */
    fun ponderLineForPredictedMove(color: PieceColor, uci: String): JniEngine.PonderLine? =
        if (useGpu) gpuEngine.ponderLineForPredictedMove(uci)
        else engineFor(color).getPonderLines().firstOrNull { it.predictedMove == uci }

    /** 指定方引擎：若预测命中 uci，返回预演应着；未命中返回空串 */
    fun respondToPredictedMove(color: PieceColor, uci: String): String =
        if (useGpu) gpuEngine.respondToPredictedMove(uci)
        else engineFor(color).respondToPredictedMove(uci)

    fun stop() {
        if (useGpu) { gpuEngine.stop(); return }
        redEngine.stop()
        blackEngine.stop()
    }

    fun shutdown() {
        if (useGpu) { gpuEngine.shutdown(); onEngineRestarted = null; return }
        redEngine.shutdown()
        blackEngine.shutdown()
        onEngineRestarted = null
        if (active === this) active = null
        Log.i(TAG, "红黑双引擎已关闭")
    }

    /**
     * ★ 异地模式：停掉本机红/黑两台本地引擎。
     * 目的不是省内存，而是**不让 B 端偷偷发热**——这两台一旦在本地跑预热或多线预想，
     * 用户就会看到「明明是异地算棋，操作端还是烫」。询招沙盒不在此列（它自己走远程）。
     */
    fun shutdownLocalEnginesForRemote() {
        if (useGpu) {
            try { gpuEngine.shutdown() } catch (_: Exception) {}
            return
        }
        try { redEngine.shutdown() } catch (_: Exception) {}
        try { blackEngine.shutdown() } catch (_: Exception) {}
        Log.i(TAG, "异地模式：本机红黑引擎已关闭（算棋交给辎重包端）")
    }

    /**
     * ★ 异地断线回退：重新拉起本机红/黑引擎（询招沙盒由 SandboxOracle 自己拉起）。
     * 只在档位已切回本地时执行，避免又把异地模式顶掉。
     */
    fun restartLocalEngines() {
        if (useGpu) return
        if (remoteMode()) return
        Thread {
            try {
                val err = startEngine()
                if (err == null) fullInit()
                Log.i(TAG, "回退本地：红黑引擎重启 " + (err ?: "OK"))
            } catch (t: Throwable) {
                Log.e(TAG, "回退本地：红黑引擎重启异常", t)
            }
        }.start()
    }

    fun isSearching(): Boolean = if (useGpu) gpuEngine.isSearching() else SandboxOracle.isSearching()

    // ── 搜索：一次一问，随问随走（询招沙盒唯一通道） ──

    /**
     * 当前走棋方搜索（走棋用）。
     * ★★★ 询招架构：红方问完交黑方问，全走同一台询招引擎 —— 一次一问、问完即消，
     *   绝无两条搜索同时挤一台引擎、绝无后台驻留状态。color 仅作记录（引擎全局唯一）。
     *   时间模式对接成 go movetime（按秒），深度模式对接成 go depth（按层），规矩跟用户设置走。
     */
    fun searchBestMoveExcluding(
        color: PieceColor,
        board: Board,
        excludedUci: Set<String>,
        timeMs: Long? = null,
        depth: Int? = null,
        onThinking: ((depth: Int, selDepth: Int, score: Int, nodes: Long, nps: Long, pv: String, pvLines: List<JniEngine.PvLine>) -> Unit)? = null,
        onResult: (Move?) -> Unit,
        restrictToUci: Set<String>? = null,
        multiPv: Int = 1
    ) {
        if (useGpu) {
            gpuEngine.searchBestMoveExcluding(board, excludedUci, timeMs, depth, onThinking, onResult, restrictToUci, multiPv)
            return
        }
        SandboxOracle.askMove(
            board = board,
            timeMs = timeMs,
            depth = depth,
            restrictToUci = restrictToUci,
            excludedUci = excludedUci,
            onThinking = onThinking?.let { outer ->
                { d, sd, sc, nodes, nps, pv -> outer(d, sd, sc, nodes, nps, pv, emptyList()) }
            },
            onResult = onResult
        )
    }

    /** 兼容旧签名：无颜色 → 需要调用方自行用 engineFor 穿透（保留以防外部仍引用） */
    fun searchBestMoveExcluding(
        board: Board,
        excludedUci: Set<String>,
        timeMs: Long? = null,
        depth: Int? = null,
        onThinking: ((depth: Int, selDepth: Int, score: Int, nodes: Long, nps: Long, pv: String, pvLines: List<JniEngine.PvLine>) -> Unit)? = null,
        onResult: (Move?) -> Unit,
        restrictToUci: Set<String>? = null,
        multiPv: Int = 1
    ) {
        searchBestMoveExcluding(PieceColor.RED, board, excludedUci, timeMs, depth, onThinking, onResult, restrictToUci, multiPv)
    }

    /** 指定颜色单引擎搜索 */
    fun searchBestMove(
        color: PieceColor,
        board: Board,
        timeMs: Long? = null,
        depth: Int? = null,
        onThinking: ((depth: Int, selDepth: Int, score: Int, nodes: Long, nps: Long, pv: String, pvLines: List<JniEngine.PvLine>) -> Unit)? = null,
        onResult: (Move?) -> Unit
    ) {
        searchBestMoveExcluding(color, board, emptySet(), timeMs, depth, onThinking, onResult, null, 1)
    }

    /** 兼容旧签名 */
    fun searchBestMove(
        board: Board,
        timeMs: Long? = null,
        depth: Int? = null,
        onThinking: ((depth: Int, selDepth: Int, score: Int, nodes: Long, nps: Long, pv: String, pvLines: List<JniEngine.PvLine>) -> Unit)? = null,
        onResult: (Move?) -> Unit
    ) {
        searchBestMoveExcluding(PieceColor.RED, board, emptySet(), timeMs, depth, onThinking, onResult, null, 1)
    }

    // ── 评价类（人话解说）：用红引擎即可，与对局搜索完全分离 ──

    fun analyzePosition(
        board: Board,
        depth: Int = 12,
        onResult: (bestMoveUci: String, score: Int, searchDepth: Int, pv: String) -> Unit
    ) {
        if (useGpu) {
            gpuEngine.searchBestMove(board, timeMs = 1500L, depth = depth.coerceAtMost(10), onResult = { mv ->
                val u = mv?.toUci() ?: ""
                onResult(u, 0, 0, u)
            })
            return
        }
        redEngine.analyzePosition(board, depth, onResult)
    }

    fun evaluateMove(
        board: Board,
        humanMoveUci: String,
        depth: Int = 12,
        onResult: (bestMoveUci: String, bestScore: Int, humanScore: Int, humanPv: String, searchDepth: Int) -> Unit
    ) {
        if (useGpu) {
            onResult("", 0, 0, "", 0)
            return
        }
        redEngine.evaluateMove(board, humanMoveUci, depth, onResult)
    }

    /**
     * ★ AI解说专用：在"指定颜色"的引擎上评估一步（人话/现世报）。
     *  与 evaluateMove 的区别：可指定用红方还是黑方引擎，避免抢占正在走棋的 AI 引擎。
     *  调用方务必保证该颜色引擎当前空闲（人脑那一侧，AI 走棋永远用不到它）。
     *  纯只读评估，不改任何棋局状态、不触发走子。
     */
    fun evaluateMoveFor(
        color: PieceColor,
        board: Board,
        uci: String,
        depth: Int = 12,
        onResult: (bestMoveUci: String, bestScore: Int, humanScore: Int, humanPv: String, searchDepth: Int) -> Unit
    ) {
        if (useGpu) {
            onResult("", 0, 0, "", 0)
            return
        }
        engineFor(color).evaluateMove(board, uci, depth, onResult)
    }

    // ── 自愈：只重启指定方引擎 ──

    /** 重启指定方引擎（★ 询招架构：重启的是全局唯一一台询招引擎；同步等它起稳） */
    fun restartEngineFor(color: PieceColor): String? {
        if (useGpu) {
            gpuEngine.newGame()
            try { onEngineRestarted?.invoke() } catch (_: Exception) {}
            return null
        }
        // 询招沙盒：杀旧起新（内部照抄解惑沙盒握手），完了回调（回调在其重启线程上）
        var err: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        SandboxOracle.restart(context) { ok ->
            if (!ok) err = "询招引擎重启失败"
            latch.countDown()
        }
        try { latch.await(25, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (err == null) {
            try { onEngineRestarted?.invoke() } catch (_: Exception) {}
        }
        return err
    }

    /** 兼容旧调用：复位并重启当前走棋方引擎 */
    fun restartEngine(): String? {
        if (useGpu) {
            gpuEngine.newGame()
            try { onEngineRestarted?.invoke() } catch (_: Exception) {}
            return null
        }
        val anyBusy = isSearching()
        forEachEngine { it.stopSearch() }
        var err = redEngine.restartEngine()
        if (err != null) return err
        err = blackEngine.restartEngine()
        if (err != null) return err
        if (!anyBusy) {
            try { onEngineRestarted?.invoke() } catch (_: Exception) {}
        }
        return null
    }

    // ── 后台思考（ponder）──

    /** 后台思考：对指定方引擎发起（对方走完预测步后，由该方引擎继续想） */
    fun startPonderFor(color: PieceColor, board: Board) {
        if (useGpu) { gpuEngine.startPonder(board); return }
        // ★ 异地模式：本机不预想（否则红黑本地引擎会在后台吃 CPU → 操作端发热）
        if (remoteMode()) return
        engineFor(color).startPonder(board)
    }

    fun startPonder(board: Board) {
        if (useGpu) { gpuEngine.startPonder(board); return }
        if (remoteMode()) return
        redEngine.startPonder(board)
    }

    fun startPonder(board: Board, ponderMoveUci: String?) {
        if (useGpu) { gpuEngine.startPonder(board); return }
        if (remoteMode()) return
        redEngine.startPonder(board)
    }

    fun ponderHitFor(
        color: PieceColor,
        timeMs: Long? = null,
        depth: Int? = null,
        onThinking: ((Int, Int, Int, Long, Long, String) -> Unit)? = null,
        onResult: (Move?) -> Unit
    ) {
        // 命中的话，直接取该方引擎 ponder 结果
        val bestUci = engineFor(color).getPonderBestMove()
        onResult(if (bestUci.isNotEmpty()) null else null)  // 保持一致语义，上层用 searchBestMove 再验证
    }

    fun stopBackgroundPonder() {
        if (useGpu) { gpuEngine.stopPonder(); return }
        redEngine.stopPonder()
        blackEngine.stopPonder()
    }

    // ── ★★★ 走棋前引擎同步（参考 P象棋 1.5.2 _stopEngineBeforeBoardMutation）──

    /**
     * ★★★ 在棋盘变化 / 新 AI 走棋前，强制停止指定方引擎并等待确认空闲。
     *
     * 对应参考 APK 的 _stopEngineBeforeBoardMutation：
     *   走棋前必须先让引擎完全停稳（stop → isready → readyok），
     *   而不是 fire-and-forget 发个 stop 就继续。
     *
     * 双 AI 对弈时尤其关键：红方走完 → 黑方引擎必须先确认空闲 → 再启动黑方搜索。
     * 否则黑方引擎可能还残留上一轮的搜索状态，收到新 position+go 后命令交错 → 卡死。
     *
     * @param color 要停止的引擎颜色（通常是"即将走棋"的那一方，确保它空闲）
     * @param timeoutMs 等待 readyok 的超时
     * @return true=引擎已确认空闲，false=超时
     */
    fun stopEngineBeforeMutation(color: PieceColor, timeoutMs: Long = 1500): Boolean {
        if (useGpu) {
            gpuEngine.stopPonder()
            return true
        }
        // 停止该方引擎的 ponder（如果有）
        engineFor(color).stopPonder()
        // 强制停止并等待确认空闲
        return engineFor(color).stopAndWaitForIdle(timeoutMs)
    }

    /**
     * ★★★ 双 AI 交接专用：同时停止红黑两台引擎并等待确认。
     *
     * 对应参考 APK 的 stopThinkingAndClearModes：
     *   一台引擎走完后、下一台引擎启动前，把两台都停稳，
     *   杜绝"上一台的尾巴干扰下一台的启动"。
     *
     * @param timeoutMs 每台引擎的等待超时
     */
    fun stopAllEnginesBeforeMutation(timeoutMs: Long = 1500) {
        if (useGpu) {
            gpuEngine.stopPonder()
            return
        }
        // 两台引擎并行停止（各自独立进程，互不影响）
        redEngine.stopPonder()
        blackEngine.stopPonder()
        redEngine.stopAndWaitForIdle(timeoutMs)
        blackEngine.stopAndWaitForIdle(timeoutMs)
    }
}