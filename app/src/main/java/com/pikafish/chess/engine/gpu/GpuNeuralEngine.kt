package com.pikafish.chess.engine.gpu

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.pikafish.chess.engine.JniEngine
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

/**
 * ★★★ GPU 神经引擎（对外接口，与 EngineManager 兼容） ★★★
 *
 * 架构：
 *   - 一个专用后台活线程（HandlerThread「gpu-nn」）承载 OpenGL ES 3.1 离屏上下文，
 *     搜索核与 GPU 推理都在这一条线程上串行执行，天然规避 GL 跨线程问题。
 *   - 评估走 GPU 计算着色器批量前向；GPU 初始化失败或设备不支持时自动回退 CPU 参考，
 *     保证引擎任何情况下都能走棋（与 JniEngine 的 1:1 语义兼容）。
 *   - 搜索核见 GpuSearch（alpha-beta + 置换表 + 静态搜索 + 迭代加深）。
 *   - 预想列表（ponder line）在此产生：预测人脑多手 + 各自应着，直接复用
 *     JniEngine.PonderLine / JniEngine.PvLine 数据结构，UI 无需改动。
 */
class GpuNeuralEngine(context: Context) {

    companion object {
        private const val TAG = "GpuNeuralEngine"
        private const val DEFAULT_TIME_MS = 2000L
        private const val PONDER_LINES = 3
        /** 懒人解压包：把 gpu_net.txt（权重文本）放进该外部子目录即被自动加载 */
        private const val EXTERNAL_WEIGHTS_SUBDIR = "engine/gpu_net.txt"
    }

    private val appContext: Context = context.applicationContext

    /** 权重文件路径（外置解压包加载）；为空则用内建默认权重 */
    @Volatile var weightFilePath: String? = null

    @Volatile var onPonderInfo: ((depth: Int, score: Int, pv: String, lines: List<JniEngine.PonderLine>) -> Unit)? = null
    @Volatile var onThinking: ((depth: Int, selDepth: Int, score: Int, nodes: Long, nps: Long, pv: String, pvLines: List<JniEngine.PvLine>) -> Unit)? = null
    @Volatile var onEngineReadyChange: ((Boolean) -> Unit)? = null

    private val glThread: HandlerThread = HandlerThread("gpu-nn")
    private val glHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private val net = GpuChessNet()
    private val evaluator = GpuEvaluator()
    private val search = GpuSearch(net, evaluator)

    @Volatile private var ready = false
    @Volatile private var initError: String? = null
    private val initLatch = CountDownLatch(1)

    @Volatile private var searching = false
    @Volatile private var pondering = false
    @Volatile private var ponderDeepest = 0
    @Volatile private var ponderPv = ""
    @Volatile private var ponderBest = ""
    private val ponderLinesLock = Any()
    private val ponderLines = HashMap<Int, JniEngine.PonderLine>()

    init {
        glThread.start()
        glHandler = Handler(glThread.looper)
        // GL 初始化必须投递到专用线程执行，确保 EGL 上下文创建在 glThread 上
        glHandler.post { prepareOnGlThread() }
    }

    private fun prepareOnGlThread() {
        try {
            // 在 GL 线程初始化评估器（EGL + 着色器）
            evaluator.bindNet(net)
            net.initDefaultWeights()
            // 懒人解压包：优先使用外部目录里用户放入的 GPU 权重
            var usePath = weightFilePath
            if (usePath == null) {
                try {
                    val ext = appContext.getExternalFilesDir(null)?.resolve(EXTERNAL_WEIGHTS_SUBDIR)
                    if (ext != null && ext.exists()) usePath = ext.absolutePath
                } catch (_: Exception) {}
            }
            usePath?.let { path ->
                if (java.io.File(path).exists()) {
                    net.loadFromFile(path)
                }
            }
            val err = evaluator.init()
            if (err != null) {
                initError = err
                Log.w(TAG, "GPU 不可用，将走 CPU 参考评估：$err")
            } else {
                ready = true
                Log.i(TAG, "GPU 神经引擎就绪")
            }
        } catch (e: Exception) {
            initError = "GPU 初始化异常: ${e.message}"
            Log.e(TAG, "GPU 初始化异常", e)
        } finally {
            initLatch.countDown()   // 让等待方（EngineManager.startEngine）解除阻塞
        }
        mainHandler.post { onEngineReadyChange?.invoke(true) }
    }

    /**
     * 等待异步 GPU 初始化完成（避免 startEngine 读取到未初始化就绪的竞态）。
     * 返回初始化错误（可空）；返回 null 表示 GPU 引擎可用。
     */
    fun awaitInit(timeoutMs: Long = 5000): String? {
        try { initLatch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        return initError
    }

    // ── 对外状态 ──

    val isGpuActive: Boolean get() = evaluator.isReady
    fun isReady(): Boolean = !searching
    fun isInitError(): String? = initError
    fun isSearching(): Boolean = searching
    fun isPondering(): Boolean = pondering

    fun getPonderLines(): List<JniEngine.PonderLine> = synchronized(ponderLinesLock) {
        (1..PONDER_LINES).mapNotNull { ponderLines[it] }.filter { it.pv.isNotEmpty() }
    }
    fun getPredictedMoves(): List<String> = getPonderLines().map { it.predictedMove }.filter { it.isNotEmpty() }
    fun getPonderBestMove(): String = ponderBest
    fun getPonderScore(): Int = getPonderLines().firstOrNull()?.score ?: 0
    fun getPonderDepth(): Int = ponderDeepest
    fun getPonderPv(): String = ponderPv
    fun ponderLineForPredictedMove(uci: String): JniEngine.PonderLine? = getPonderLines().firstOrNull { it.predictedMove == uci }
    fun respondToPredictedMove(uci: String): String = ponderLineForPredictedMove(uci)?.responseMove ?: ""

    // ── 生命周期 ──

    fun newGame() { /* GPU 引擎无哈希外置状态，无需 ucinewgame；清空置换表即可 */ search.reset() }
    fun stopSearch() { abortSearch() }
    fun stop() { abortSearch() }
    fun shutdown() {
        abortSearch()
        glHandler.post { evaluator.teardown() }
        glThread.quitSafely()
    }

    /** 如果换了个权重文件，重新加载 */
    fun reloadWeights(path: String?) {
        weightFilePath = path
        glHandler.post {
            if (path != null && java.io.File(path).exists()) {
                val e = net.loadFromFile(path)
                Log.i(TAG, if (e == null) "已从外部路径加载权重: $path" else "外部权重加载失败，回默认: $e")
            }
        }
    }

    // ── 搜索 ──

    private fun abortSearch() {
        glHandler.post { search.abort() }
    }

    /**
     * 搜索最佳走法（与 JniEngine.searchBestMoveExcluding 语义对齐）。
     * 全部在 GL 线程执行，结果/进度回调回主线程。
     */
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
        val useDepth = depth != null && depth > 0
        val effDepth = if (useDepth) depth!!.coerceIn(1, 20) else 6
        val effTime = if (useDepth) (effDepth * 1500L).coerceAtLeast(800) else (timeMs ?: DEFAULT_TIME_MS).coerceAtLeast(120)

        searching = true
        val boardCopy = board.clone()
        glHandler.post {
            try {
                val result = if (useDepth) {
                    search.search(
                        boardCopy, timeMs = effTime, maxDepth = effDepth,
                        excludedUci = excludedUci, restrictToUci = restrictToUci, needRootLines = 0,
                        progress = { d, sc, nodes, nps, pv ->
                            val lines = listOf(JniEngine.PvLine(sc, pv))
                            onThinking?.let { cb -> mainHandler.post { try { cb(d, d, sc, nodes, nps, pv, lines) } catch (_: Exception) {} } }
                        }
                    )
                } else {
                    search.search(
                        boardCopy, timeMs = effTime, maxDepth = effDepth,
                        excludedUci = excludedUci, restrictToUci = restrictToUci, needRootLines = 0,
                        progress = { d, sc, nodes, nps, pv ->
                            val lines = listOf(JniEngine.PvLine(sc, pv))
                            onThinking?.let { cb -> mainHandler.post { try { cb(d, d, sc, nodes, nps, pv, lines) } catch (_: Exception) {} } }
                        }
                    )
                }
                val mv = result.bestMove
                mainHandler.post {
                    searching = false
                    try { onResult(mv) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "GPU 搜索异常", e)
                mainHandler.post {
                    searching = false
                    try { onResult(null) } catch (_: Exception) {}
                }
            }
        }
    }

    /** 简化单搜索入口（供 analyzePosition 等使用） */
    fun searchBestMove(
        board: Board,
        timeMs: Long? = null,
        depth: Int? = null,
        onResult: (Move?) -> Unit
    ) {
        searchBestMoveExcluding(board, emptySet(), timeMs, depth, null, onResult, null, 1)
    }

    // ── 后台预想（Ponder / 预想列表）──

    fun startPonder(board: Board, ponderLinesCount: Int = PONDER_LINES) {
        if (searching) return
        stopPonder()
        pondering = true
        ponderDeepest = 0
        ponderBest = ""
        ponderPv = ""
        synchronized(ponderLinesLock) { ponderLines.clear() }

        val boardCopy = board.clone()
        glHandler.post {
            try {
                val res = search.search(boardCopy, timeMs = 1200, maxDepth = 5, needRootLines = ponderLinesCount)
                ponderPv = res.pv
                ponderBest = res.bestMove?.toUci() ?: ""
                ponderDeepest = res.depth
                synchronized(ponderLinesLock) { ponderLines.clear() }
                res.lines.forEachIndexed { idx, line ->
                    if (idx < ponderLinesCount) {
                        ponderLines[idx + 1] = JniEngine.PonderLine(res.depth, line.score, line.pv)
                    }
                }
                val linesNow = getPonderLines()
                if (linesNow.isNotEmpty()) {
                    val d = res.depth
                    val sc = linesNow[0].score
                    val pv = res.pv
                    mainHandler.post { onPonderInfo?.let { cb -> try { cb(d, sc, pv, linesNow) } catch (_: Exception) {} } }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GPU Ponder 异常", e)
            } finally {
                pondering = false
            }
        }
    }

    fun stopPonder() {
        glHandler.post { search.abort() }
        pondering = false
        synchronized(ponderLinesLock) { ponderLines.clear() }
    }
}