package com.pikafish.chess.engine

import android.content.Context
import android.util.Log
import com.pikafish.chess.heavy.HeavyStore
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.Piece
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position
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
 * ★★★ 雪鱼引擎桥接 XueyuEngine（奕狐象棋内置 Fairy-Stockfish 8.x Xiangqi，UCI 协议）★★★
 *
 * 说明：奕狐内置的 fstockfish 是 Fairy-Stockfish 编译出的四川ARM可执行 ELF（PIE），
 * 其"id name"即 Fairy-Stockfish，走标准 UCI 协议命令，配合 xiangqi.nnue 做 NNUE 评估，
 * 棋力明显强于相眼(Eleeye)/变色龙(Chameleon)等旧引擎，适合顶替已失效的名手引擎档位。
 *
 * 启动方式与 SandboxEngine 一致：把 libfstockfish.so 从 nativeLibraryDir 直接作子进程拉起，
 * 走 stdin/stdout 与其对话。NNUE 权重(fairy/xiangqi.nnue)从 assets 拷贝到 filesDir 供其加载。
 *
 * 提速要点（解决"最高档走得很慢"）：Fairy-Stockfish 默认仅单线程、且若用纯深度制会在复杂
 * 中局卡很久。这里显式开多线程(Threads)、合适哈希(Hash)、确保加载 NNUE，并以
 * "go movetime <档位毫秒>" 硬性限时——既保证单步上限(快)，又把多线程+NNUE的全部算力
 * 集中用在时限内下得更深(强)，棋力与名手同一档毫不逊色。
 *
 * 对外暴露的接口与 SandboxEngine 完全一致：
 * locateEngine / copyCurrentNNue / startEngine / liteInit / isEngineReady /
 * searchBestMove / stopSearch / shutdown / loadErrorText。
 */
class XueyuEngine(private val context: Context) {
    companion object {
        private const val TAG = "XueyuEngine"
        const val KEY = "xueyu"

        /** nativeLibraryDir 里的引擎可执行 ELF 文件名 */
        const val BIN_NAME = "libfstockfish.so"

        /** assets 里的 NNUE 权重路径（相对 assets/） */
        private const val NNUE_ASSET = "fairy/xiangqi.nnue"

        /** filesDir 里的 NNUE 落盘文件名（同 engine cwd / HOME，兼容引擎相对路径解析） */
        private const val NNUE_FILE = "xiangqi_fairy.nnue"

        /** 同时以引擎默认期望的 xiangqi 权重名落盘，双保险：相对路径/绝对路径都能被加载 */
        private const val NNUE_ROOT_FILE = "xiangqi.nnue"

        /** 线程上限：象棋对线程敏感，4 线程内收益最大，避免低端机过热卡顿 */
        private const val THREAD_CAP = 4
    }

    @Volatile var hashSize: Int = 64
    @Volatile var threadCount: Int = 2

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    private val inputQueue = ArrayBlockingQueue<String>(1024)
    private val sendLock = ReentrantLock()

    @Volatile private var isReady = false
    @Volatile private var enginePath: String = ""
    @Volatile private var nnuePath: String = ""
    @Volatile private var loadError: String? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var searchJob: Job? = null
    private var readerThread: Thread? = null

    // ═════════════ 启动 ═════════════

    /** 定位雪鱼可执行 ELF 与 NNUE（都在 arm64 nativeLibraryDir / filesDir，保证可执行可读） */
    fun locateEngine(): String? = try {
        val nd = context.applicationInfo.nativeLibraryDir
        val bin = File(nd, BIN_NAME)
        if (!bin.exists()) {
            "雪鱼引擎缺失：${bin.name} 未打包"
        } else {
            if (!bin.canExecute()) bin.setExecutable(true, false)
            enginePath = bin.absolutePath
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "locateEngine failed", e)
        "雪鱼定位失败: ${e.message}"
    }

    /** 把 NNUE 权重从 assets 拷到 filesDir（双份），供引擎在启动后按 EvalFile 加载 */
    fun copyCurrentNNue(): String? = try {
        val dir = File(context.filesDir, "fairy").apply { if (!exists()) mkdirs() }
        val bytes = HeavyStore.openLocalFirst(context, NNUE_ASSET).use { it.readBytes() }
        if (bytes.size < 1024 * 1024) {
            "雪鱼 NNUE 权重内容为空"
        } else {
            // ① filesDir/xiangqi.nnue（引擎默认期望名 + cwd/HOME 即 filesDir，兼容相对路径）
            val rootOut = File(context.filesDir, NNUE_ROOT_FILE)
            if (!rootOut.exists() || rootOut.length() == 0L) {
                rootOut.writeBytes(bytes)
                if (rootOut.length() == 0L) throw java.io.IOException("root nnue write empty")
            }
            // ② filesDir/fairy/xiangqi_fairy.nnue（兼容 GNUchess/自定义名）
            val out = File(dir, NNUE_FILE)
            if (!out.exists() || out.length() == 0L) {
                out.writeBytes(bytes)
            }
            if (!rootOut.exists() || rootOut.length() < 1024 * 1024) {
                "雪鱼 NNUE 权重拷贝失败"
            } else {
                nnuePath = rootOut.absolutePath
                null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "copyCurrentNNue failed", e)
        "雪鱼 NNUE 权重缺失: ${e.message}"
    }

    /** 拉起 fstockfish 子进程（UCI），第一个参数即是引擎自身路径 */
    fun startEngine(): String? {
        return try {
            if (loadError != null) return loadError
            val pb = ProcessBuilder(enginePath)
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
                            try { inputQueue.offer(line) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) { /* stream closed */ }
            }.also { it.name = "xueyu-reader"; it.start() }
            null
        } catch (e: Exception) {
            loadError = "雪鱼引擎启动失败：${e.message}"
            Log.e(TAG, "startEngine failed", e)
            loadError
        }
    }

    /** 握手 + 关键提速配置：变体=xiangqi、NNUE、多线程、哈希；就绪后 readyok */
    fun liteInit(): String? {
        return try {
            sendCommand("uci")
            if (waitForLine("uciok", 8000) == null) return "雪鱼引擎握手失败（uciok 超时）"

        // ★ 关键提速：显式配置变体与 NNUE，并开启多线程与合适哈希
        val threads = (threadCount.coerceIn(1, THREAD_CAP))
        val hash = hashSize.coerceIn(32, 128)
        sendCommand("setoption name UCI_Variant value xiangqi")
        sendCommand("setoption name Use NNUE value true")
        // ★ 不主动覆盖 EvalFile——先让引擎用自身的默认/内置网络（与奕狐官方行为一致）。
        //   只有 ensureNnueActive() 实测发现 NNUE 未激活，才用我们拷贝的权重去补。
        sendCommand("setoption name Threads value $threads")
        sendCommand("setoption name Hash value $hash")

        sendCommand("isready")
        if (waitForLine("readyok", 12000) == null) {
            // 个别 build 不发 readyok 但可工作：给一次机会继续
            Log.w(TAG, "readys: readyok 超时，继续尝试")
        }
        // ★ 验证 NNUE 是否真的加载成功（决定引擎棋力/残局质量），未加载则重试多次
        ensureNnueActive()
        isReady = true
        Log.d(TAG, "XueyuEngine ready threads=$threads hash=$hash nnueActived=$nnueActive")
        null
        } catch (e: Exception) {
            Log.e(TAG, "liteInit error", e)
            "雪鱼初始化异常: ${e.message}"
        }
    }

    /** 标准 Xiangqi 初始局面 FEN（Fairy-Stockfish 红方底线 rank=1）：分析/验证 NNUE 用，需先摆好局面 eval 才会打印评估方式 */
    private val START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"

    /** 发一次 eval 并检测回包是否命中 "nnue evaluation"（需先摆好局面 eval 才会输出评估方式） */
    private fun verifyNnue(): Boolean {
        try {
            sendPositionStart()
            sendCommand("eval")
            val until = System.currentTimeMillis() + 900
            while (System.currentTimeMillis() < until) {
                val line = inputQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
                if (line.lowercase().contains("nnue evaluation")) return true
            }
        } catch (_: Exception) {}
        return false
    }

    /** 设置指定 EvalFile 并等 isready，随后由调用方 verify 判定 */
    private fun applyEvalFile(ev: String) {
        sendCommand("setoption name Use NNUE value true")
        sendCommand("setoption name EvalFile value $ev")
        sendCommand("isready")
        waitForLine("readyok", 4000)
    }

    /** 实测引擎是否处于 NNUE 评估状态。
     *  策略：① 先探默认（不设 EvalFile）——最大化贴合奕狐内置网络，避免用自拷权重覆盖掉本就更好的内置网络；
     *       ② 默认未激活才依次补 绝对路径 / 相对文件名，直至确认命中 NNUE。
     *  ★关键：若 NNUE 未激活，引擎退回极弱古典评估，就会出现中残局无脑漏招 / 残局空棋磨老将的现象★★ */
    @Volatile private var nnueActive = false
    private fun ensureNnueActive() {
        nnueActive = verifyNnue()
        var tryAbs = false
        var tryRel = false
        if (!nnueActive && nnuePath.isNotBlank()) {
            applyEvalFile(nnuePath)
            nnueActive = verifyNnue()
            tryAbs = true
        }
        if (!nnueActive) {
            applyEvalFile(NNUE_ROOT_FILE)   // 相对名：cwd/HOME(=filesDir) 下已布好 xiangqi.nnue
            nnueActive = verifyNnue()
            tryRel = true
        }
        Log.i(TAG, "XueyuEngine NNUE active=$nnueActive (abs=$tryAbs rel=$tryRel)")
    }

    /** 摆一个标准初始局面（eval 需要 position 才输出评估方式） */
    private fun sendPositionStart() {
        sendCommand("position fen $START_FEN")
    }

    fun isEngineReady(): Boolean = isReady

    // ═════════════ 搜索（时间制限时，保证又快又强）═════════════

    /**
     * 思考最佳应着（异步，结果走 onResult 回调）。
     * 按层数(深度)制：depth>0 时发 "go depth N movetime M" 主控层数（M 仅为超时兜底，防止极端局面卡死）；
     * 无 depth 时才退回纯 "go movetime" 时间制。NNUE+多线程保证在同一层数下算得更深、棋力更足。
     */
    fun searchBestMove(
        board: Board,
        timeMs: Long? = null,
        depth: Int? = null,
        onResult: (Move?) -> Unit
    ) {
        if (!isReady) { onResult(null); return }
        searchJob?.cancel()
        searchJob = scope.launch {
            try {
                val fen = board.toFen()
                sendCommand("position fen $fen")
                if (depth != null && depth > 0) {
                    // ★ 层数制：按 depth 搜索，movetime 仅作安全兜底上限（层数先到即回）
                    val movetime = (timeMs ?: 4000L).coerceIn(4000L, 60000L)
                    sendCommand("go depth $depth movetime $movetime")
                    val best = collectBestmove(maxOf(movetime, 3000L) + 5000)
                    val move = normalizeBestmove(best, board) ?: fallbackMove(board)
                    onResult(move)
                    return@launch
                }
                val mtRaw = timeMs ?: 1500L
                val mt = mtRaw.coerceIn(400L, 8000L)
                sendCommand("go movetime $mt")
                val best = collectBestmove(mt + 2000)
                val move = normalizeBestmove(best, board) ?: fallbackMove(board)
                onResult(move)
            } catch (e: Exception) {
                Log.e(TAG, "search failed", e)
                onResult(null)
            }
        }
    }

    fun stopSearch() {
        try { sendCommand("stop") } catch (_: Exception) {}
        searchJob?.cancel()
    }

    /** 兜底：争取给出至少一个合法着，保证引擎绝不因解析失败而"无棋可走/崩溃"。
     *  优先吃对方高价值子，其次走能送将的着，否则取第一个安全合法着
     * （getLegalMoves 已保证招法不会自送将）。 */
    private fun fallbackMove(board: Board): Move? {
        val legal = try { board.getLegalMoves(board.currentPlayer) } catch (e: Exception) { emptyList() }
        if (legal.isEmpty()) return null
        var pick: Move = legal.first()
        var bestScore = Int.MIN_VALUE
        for (m in legal) {
            var score = 0
            val cap = try { board.getPiece(m.to) } catch (e: Exception) { null }
            if (cap != null && cap.color != board.currentPlayer) score += pieceValue(cap.type) * 100
            val nb = board.clone()
            try {
                if (nb.makeMove(m) && nb.isInCheck(board.currentPlayer.opponent())) score += 60
            } catch (e: Exception) {}
            if (score > bestScore) { bestScore = score; pick = m }
        }
        return pick
    }

    private fun pieceValue(t: PieceType): Int = when (t) {
        PieceType.KING -> 10000
        PieceType.ROOK -> 900
        PieceType.CANNON -> 500
        PieceType.KNIGHT -> 400
        PieceType.ADVISOR -> 200
        PieceType.ELEPHANT -> 200
        PieceType.PAWN -> 100
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

    /** 解析 bestmove + 自动方位校正（应对不同引擎 rank 起算差异，保证落在合法招上）
     *
     * Fairy-Stockfish 的 xiangqi 走法坐标：红方底线=rank 1、黑方底线=rank 10（文件 a-i），
     * 而 App 内部约定 rank 0=红方底线（Pikafish/UCCI 风格，见 Position.fromUci: row=9-rank）。
     * 因此引擎 rank e → App 行 = 10 - e。rank 会超过一位（如 a1a10），故正则需支持 1~2 位。
     */
    private fun normalizeBestmove(raw: String?, board: Board): Move? {
        if (raw == null) return null
        val parts = raw.split(" ").filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val tok = parts[1]
        val m = Regex("""([a-iA-I])([0-9]{1,2})([a-iA-I])([0-9]{1,2})""").find(tok) ?: return null
        val f1 = m.groupValues[1].lowercase()[0] - 'a'
        val r1 = m.groupValues[2].toInt()
        val f2 = m.groupValues[3].lowercase()[0] - 'a'
        val r2 = m.groupValues[4].toInt()
        if (f1 !in 0..8 || f2 !in 0..8) return null
        // ① 先按 Fairy-Stockfish 约定（红方底线 rank=1）转换
        toMove(f1, r1, f2, r2, board)?.let { return it }
        // ② 兼容个别把红方底线当 rank=0 的引擎：把 rank 整体 +1 后再按同一公式解释
        return toMove(f1, r1 + 1, f2, r2 + 1, board)
    }

    /** 把引擎 (file, rank) 走法按「rank 1=红方底线」换算成 App 行列并核验合法性后构造成 Move */
    private fun toMove(f1: Int, r1: Int, f2: Int, r2: Int, board: Board): Move? {
        if (r1 !in 1..10 || r2 !in 1..10) return null
        val fromRow = 10 - r1
        val toRow = 10 - r2
        if (fromRow !in 0..9 || toRow !in 0..9) return null
        val from = Position(fromRow, f1)
        val to = Position(toRow, f2)
        val piece = board.getPiece(from) ?: return null
        if (piece.color != board.currentPlayer) return null
        val legal = board.getLegalMoves(board.currentPlayer)
            .any { it.from == from && it.to == to }
        if (!legal) return null
        val captured = board.getPiece(to)
        return Move(from, to, piece, captured)
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
        Log.d(TAG, "XueyuEngine closed")
    }

    fun loadErrorText(): String? = loadError

    // ═════════════ 基础 I/O：与 SandboxEngine 完全一致 ═════════════

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