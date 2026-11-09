package com.pikafish.chess.engine

import android.content.Context
import android.util.Log
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * ★★★ 沙盒引擎桥接 SandboxEngine（无需神经网络，通用国际象棋协议 UCI/UCCI）★★★
 *
 * 忠实复刻原「中国象棋」Xamarin 应用对这些非神经网络引擎的托管方式：
 *   1. 32 位 ARM 桥接器(engbridge) 通过 dlopen 加载 libeleeye/libufx/libchameleon/libnewgg；
 *      引擎在进程内的内存管道上运行 engine_main/read_pipe/write_pipe。
 *   2. engbridge 把"进程内内存管道引擎"包装成标准 stdin/stdout 的 UCI/UCCI 子进程。
 *   3. 本类用 ProcessBuilder 拉起 engbridge，走 stdin/stdout 与其 UCI/UCCI 对话，
 *      与主界面驱动皮卡鱼的方式完全相同。
 *
 * 对外只暴露 Challenge 需要的子集：locateEngine / startEngine / liteInit /
 * isEngineReady / searchBestMove / stopSearch / shutdown。
 */
class SandboxEngine(
    private val context: Context,
    private val engineKey: String,      // eleeye / ufx / chameleon / newgg
    private val protocol: Boolean       // true = UCCI(eleeye,ufx)；false = UCI(chameleon,newgg)
) {
    companion object {
        private const val TAG = "SandboxEngine"
        private const val BRIDGE_NAME = "libengbridge_v7.so"   // 32位 ARM 桥接器(ELF executable)
        const val ELEEYE = "eleeye"; const val UFX = "ufx"
        const val CHAMELEON = "chameleon"; const val NEWGG = "newgg"

        fun isUcci(key: String): Boolean = (key == ELEEYE || key == UFX)
        fun soName(key: String): String = "lib$key.so"
    }

    @Volatile var hashSize: Int = 64
    @Volatile var threadCount: Int = 2

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val inputQueue = ArrayBlockingQueue<String>(512)
    private val sendLock = ReentrantLock()

    @Volatile private var isReady = false
    @Volatile private var enginePath: String = ""

    // 用线程而非协程作用域，保证与 Challenge 的掌管方式一致、可随时 interrupt
    private var readerThread: Thread? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var searchJob: Job? = null

    // ═════════════ 启动 ═════════════

    /** 定位 32 位桥接器与目标引擎 .so（都在 nativeLibraryDir，保证可执行/可读） */
    fun locateEngine(): String? = try {
        val nd = context.applicationInfo.nativeLibraryDir
        val bridge = File(nd, BRIDGE_NAME)
        val so = File(nd, soName(engineKey))
        if (!bridge.exists() || !so.exists()) {
            "沙盒文件缺失：bridge=${bridge.exists()} engine=${so.exists()}"
        } else {
            if (!bridge.canExecute()) bridge.setExecutable(true, false)
            enginePath = so.absolutePath
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "locateEngine failed", e)
        "沙盒定位失败: ${e.message}"
    }

    /** NNUE 等神经网络文件对本模式不需要 */
    fun copyCurrentNNue(): String? = null

    /** 启动 engbridge 子进程：第一个参数=引擎 .so 绝对路径 */
    fun startEngine(): String? = try {
        val nd = context.applicationInfo.nativeLibraryDir
        val bridge = File(nd, BRIDGE_NAME).absolutePath
        Log.d(TAG, "start engine=$engineKey bridge=$bridge engine=$enginePath")
        val pb = ProcessBuilder(bridge, enginePath, "CChessEngine")
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        pb.environment()["HOME"] = context.filesDir.absolutePath
        process = pb.start()
        writer = BufferedWriter(OutputStreamWriter(process!!.outputStream, Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
        readerThread = Thread {
            try {
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    if (line.isNotEmpty()) {
                        // ★ 思考日志直排已按用户要求整体移除（询招架构一次一问、随问随走）。
                        try { inputQueue.offer(line) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) { /* stream closed */ }
        }.also { it.name = "sandbox-reader"; it.start() }
        null
    } catch (e: Exception) {
        Log.e(TAG, "startEngine failed", e)
        "沙盒启动失败: ${e.message}"
    }

    /** 握手：UCCI 握手用 "ucci"/"ucciok"，UCI 用 "uci"/"uciok" */
    fun liteInit(): String? {
        return try {
            sendCommand(if (protocol) "ucci" else "uci")
            val kick = waitForLine(if (protocol) "ucciok" else "uciok", 8000)
                ?: return "引擎握手失败（${if (protocol) "ucciok" else "uciok"} 超时）"
            // 让引擎固件就绪
            sendCommand("isready")
            if (waitForLine("readyok", 8000) == null) return "引擎就绪超时(readyok)"
            isReady = true
            Log.d(TAG, "SandboxEngine ready: $engineKey protocol=${if (protocol) "UCCI" else "UCI"}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "liteInit error", e)
            "沙盒初始化异常: ${e.message}"
        }
    }

    fun isEngineReady(): Boolean = isReady

    // ═════════════ 搜索 ═════════════

    /**
     * 从当前棋盘思考最佳应着（异步，结果走 onResult 回调）。
     * 用 board.toFen() 一次给足"当前局面+轮走方"，无需同步走法历史。
     */
    fun searchBestMove(
        board: Board,
        timeMs: Long? = null,
        depth: Int? = null,
        onThinking: ((Int, Int, Int, Long, Long, String) -> Unit)? = null,
        onResult: (Move?) -> Unit,
        multiPv: Int = 1
    ) {
        if (!isReady) { onResult(null); return }
        searchJob?.cancel()          // 作废在途搜索
        searchJob = scope.launch {
            try {
                val fen = board.toFen()
                sendCommand("position fen $fen")
                val useDepth = depth != null && depth > 0
                val mt = (timeMs ?: 150).coerceAtLeast(30)
                val cmd = when {
                    useDepth -> "go depth ${depth!!.coerceIn(1, 99)}"
                    protocol -> "go time $mt movestogo 20"   // UCCI 引擎(相眼/UFX)原生语法
                    else -> "go movetime $mt"                // UCI 引擎(变色龙/神机)原生语法
                }
                sendCommand(cmd)
                // 深度模式下也给硬性时间上限，防止低端机长时间卡算
                val budget = when {
                    timeMs != null && timeMs > 0 -> timeMs
                    useDepth -> depthBudget(depth!!)
                    else -> 3000L
                }
                val best = collectBestmove(budget)
                val move = normalizeBestmove(best, board)
                onResult(move)
            } catch (e: Exception) {
                Log.e(TAG, "search failed", e)
                onResult(null)
            }
        }
    }

    /** 深度 → 估算思考预算（随深度上浮） */
    private fun depthBudget(depth: Int): Long =
        (2200L + (depth - 1) * 420L).coerceIn(2000L, 12000L)

    fun stopSearch() {
        try { sendCommand("stop") } catch (_: Exception) {}
        searchJob?.cancel()
    }

    /** 读取队列直到出现 bestmove；超时则发 stop 再补读一次 */
    private fun collectBestmove(timeoutMs: Long): String? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val line = inputQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
            val t = line.trim()
            if (t.startsWith("bestmove")) return t
        }
        try { sendCommand("stop") } catch (_: Exception) {}
        val stopUntil = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < stopUntil) {
            val line = inputQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
            val t = line.trim()
            if (t.startsWith("bestmove")) return t
        }
        return null
    }

    /** 解析 bestmove + 自动方位校正（应对不同引擎 rank 起算的差异，保证落在合法招上） */
    private fun normalizeBestmove(raw: String?, board: Board): Move? {
        if (raw == null) return null
        // "bestmove <mv> [ponder ...]"
        val parts = raw.split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val tok = parts[1]
        val m = Regex("""([a-iA-I])([0-9])([a-iA-I])([0-9])""").find(tok) ?: return null
        val (_, f1s, r1s, f2s, r2s) = m.groupValues
        val f1 = f1s.lowercase()[0] - 'a'
        val f2 = f2s.lowercase()[0] - 'a'
        val r1 = r1s[0] - '0'
        val r2 = r2s[0] - '0'
        // 原样坐标
        val direct = toMove(f1, r1, f2, r2, board)
        if (direct != null) return direct
        // 反转型（rank 上下颠倒的引擎）
        val rev = toMove(f1, 9 - r1, f2, 9 - r2, board)
        return rev
    }

    private fun toMove(f1: Int, r1: Int, f2: Int, r2: Int, board: Board): Move? {
        if (f1 !in 0..8 || f2 !in 0..8 || r1 !in 0..9 || r2 !in 0..9) return null
        // UCI 坐标 → 我们棋盘坐标（col=a..i，row=9-digit）
        val fromCol = f1
        val toCol = f2
        val fromRow = 9 - r1
        val toRow = 9 - r2
        val piece = board.pieceAt(fromRow, fromCol) ?: return null
        val legal = board.getLegalMoves(board.currentPlayer)
            .any { it.from.row == fromRow && it.from.col == fromCol && it.to.row == toRow && it.to.col == toCol }
        if (!legal) return null
        return Move.fromUci("${('a' + f1)}${r1}${('a' + f2)}${r2}", board)
    }

    // ═════════════ 关闭 ═════════════

    fun shutdown() {
        isReady = false
        try { sendCommand("quit") } catch (_: Exception) {}
        searchJob?.cancel()
        try { readerThread?.interrupt() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { process?.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        try { process?.destroyForcibly() } catch (_: Exception) {}
        process = null; writer = null; readerThread = null
        inputQueue.clear()
        Log.d(TAG, "SandboxEngine closed: $engineKey")
    }

    // ═════════════ 基础 I/O ═════════════

    private fun sendCommand(cmd: String) {
        sendLock.lock()
        try {
            writer?.let { it.write(cmd); it.write("\n"); it.flush() }
        } finally {
            sendLock.unlock()
        }
    }

    private fun waitForLine(keyword: String, timeoutMs: Long): String? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val line = inputQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
            if (line.contains(keyword)) return line
        }
        return null
    }
}