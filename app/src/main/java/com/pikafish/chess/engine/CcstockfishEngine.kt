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
 * ★★★ 内置 CCS 引擎桥接 CcstockfishEngine（CCStockfish，中国象棋 UCI 引擎）★★★
 *
 * 与雪鱼(XueyuEngine)完全同构：把打包进 assets/ccstockfish/ 里的 CCS 可执行(ELF)
 * 与 entry.nnue 权重拷到 filesDir，再作子进程拉起、走 stdin/stdout 的 UCI 对话。
 * - 支持按层数(深度)搜索：go depth N movetime M（M 仅兜底），越深越强。
 * - 标准 UCI 命令；EvalFile 指向落盘的 entry.nnue 让其加载 NNUE。
 *
 * 对外暴露接口与 XueyuEngine / SandboxEngine 一致：
 * locateEngine / copyCurrentNNue / startEngine / liteInit / isEngineReady /
 * searchBestMove / stopSearch / shutdown / loadErrorText。
 */
class CcstockfishEngine(private val context: Context) {
    companion object {
        private const val TAG = "CcstockfishEngine"
        const val KEY = "ccstockfish"

        /** assets 里的引擎可执行 ELF 路径（相对 assets/） */
        private const val BIN_ASSET = "ccstockfish/CCStockFish-armv8"

        /** assets 里的 NNUE 权重路径（相对 assets/） */
        private const val NNUE_ASSET = "ccstockfish/entry.nnue"

        /** filesDir 落盘文件名 */
        private const val BIN_FILE = "CCStockFish-armv8.ccs"
        private const val NNUE_FILE = "entry.ccs.nnue"

        /** 线程上限 */
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

    /** 定位 CCS 可执行与 NNUE：从 assets 拷到 filesDir/ccss/ 并置可执行 */
    fun locateEngine(): String? {
        return try {
            val dir = File(context.filesDir, "ccss").apply { if (!exists()) mkdirs() }
            val bin = File(dir, BIN_FILE)
            // ★ Android 10+ 禁止从应用私有目录 exec（error=13 根因）：
            //   优先用 nativeLibraryDir 下的 libccsengine.so（该目录允许执行）
            val native = File(context.applicationInfo.nativeLibraryDir, "libccsengine.so")
            if (native.exists() && native.length() > 64 * 1024) {
                enginePath = native.absolutePath
                Log.d(TAG, "engine= nativeLibraryDir ${native.absolutePath}")
                return null
            }
            if (!bin.exists() || bin.length() < 64 * 1024) {
                // 从 assets 解出二进制（PIE 可执行）
                context.assets.open(BIN_ASSET).use { ins ->
                    bin.outputStream().use { out -> ins.copyTo(out) }
                }
            }
            if (!bin.exists() || bin.length() < 64 * 1024) return "CCS 引擎缺失：${bin.name} 解出失败"
            // ★ 修复 error=13 Permission denied：解出的 ELF 必须可执行（含目录本身）
            dir.setExecutable(true, false)
            bin.setReadable(true, false)
            bin.setExecutable(true, false)
            if (!bin.canExecute()) {
                // 已有文件权限没落上：删掉重解一次，再置位
                bin.delete()
                context.assets.open(BIN_ASSET).use { ins ->
                    bin.outputStream().use { out -> ins.copyTo(out) }
                }
                bin.setReadable(true, false)
                bin.setExecutable(true, false)
            }
            if (!bin.canExecute()) return "CCS 引擎不可执行：${bin.absolutePath}（chmod 失败）"
            enginePath = bin.absolutePath
            null
        } catch (e: Exception) {
            Log.e(TAG, "locateEngine failed", e)
            "CCS 定位失败: ${e.message}"
        }
    }

    /** 把 NNUE 权重从 assets 拷到 filesDir（供引擎按 EvalFile 加载） */
    fun copyCurrentNNue(): String? {
        return try {
            val dir = File(context.filesDir, "ccss").apply { if (!exists()) mkdirs() }
            val out = File(dir, NNUE_FILE)
            if (!out.exists() || out.length() < 1024 * 1024) {
                HeavyStore.openLocalFirst(context, NNUE_ASSET).use { ins ->
                    val fos = java.io.FileOutputStream(out)
                    ins.copyTo(fos); fos.close()
                }
            }
            if (!out.exists() || out.length() < 1024 * 1024) {
                "CCS NNUE 权重拷贝失败"
            } else {
                nnuePath = out.absolutePath
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyCurrentNNue failed", e)
            "CCS NNUE 权重缺失: ${e.message}"
        }
    }

    /** 拉起 CCS 子进程（UCI） */
    fun startEngine(): String? {
        return try {
            if (loadError != null) return loadError
            val pb = ProcessBuilder(enginePath)
            pb.directory(File(context.filesDir, "ccss"))
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = File(context.filesDir, "ccss").absolutePath
            process = pb.start()
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
            readerThread = Thread {
                try {
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        if (line.isNotEmpty()) try { inputQueue.offer(line) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }.also { it.name = "ccs-reader"; it.start() }
            null
        } catch (e: Exception) {
            loadError = "CCS 引擎启动失败：${e.message}"
            Log.e(TAG, "startEngine failed", e)
            loadError
        }
    }

    /** 握手 + 关键配置：变体=xiangqi、NNUE、多线程、哈希；就绪后 readyok */
    fun liteInit(): String? {
        return try {
            sendCommand("uci")
            if (waitForLine("uciok", 8000) == null) return "CCS 引擎握手失败（uciok 超时）"
            val threads = threadCount.coerceIn(1, THREAD_CAP)
            val hash = hashSize.coerceIn(32, 128)
            sendCommand("setoption name Use NNUE value true")
            if (nnuePath.isNotBlank()) sendCommand("setoption name EvalFile value $nnuePath")
            sendCommand("setoption name Threads value $threads")
            sendCommand("setoption name Hash value $hash")
            sendCommand("isready")
            if (waitForLine("readyok", 12000) == null) {
                Log.w(TAG, "CCS readyok 超时，继续尝试")
            }
            isReady = true
            Log.d(TAG, "CcstockfishEngine ready threads=$threads hash=$hash")
            null
        } catch (e: Exception) {
            Log.e(TAG, "liteInit error", e)
            "CCS 初始化异常: ${e.message}"
        }
    }

    fun isEngineReady(): Boolean = isReady

    // ═════════════ 搜索 ═════════════

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

    /** 兜底：保证引擎绝不因解析失败而"无棋可走" */
    private fun fallbackMove(board: Board): Move? {
        val legal = try { board.getLegalMoves(board.currentPlayer) } catch (e: Exception) { emptyList() }
        if (legal.isEmpty()) return null
        var pick = legal.first()
        var bestScore = Int.MIN_VALUE
        for (m in legal) {
            var score = 0
            val cap = try { board.getPiece(m.to) } catch (e: Exception) { null }
            if (cap != null && cap.color != board.currentPlayer) score += pieceValue(cap.type) * 100
            val nb = board.clone()
            try { if (nb.makeMove(m) && nb.isInCheck(board.currentPlayer.opponent())) score += 60 } catch (e: Exception) {}
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

    /** 解析 bestmove + 自动方位校正（兼容不同 rank 起算） */
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
        toMove(f1, r1, f2, r2, board)?.let { return it }
        return toMove(f1, r1 + 1, f2, r2 + 1, board)
    }

    private fun toMove(f1: Int, r1: Int, f2: Int, r2: Int, board: Board): Move? {
        if (r1 !in 1..10 || r2 !in 1..10) return null
        val fromRow = 10 - r1
        val toRow = 10 - r2
        if (fromRow !in 0..9 || toRow !in 0..9) return null
        val from = Position(fromRow, f1)
        val to = Position(toRow, f2)
        val piece = board.getPiece(from) ?: return null
        if (piece.color != board.currentPlayer) return null
        val legal = board.getLegalMoves(board.currentPlayer).any { it.from == from && it.to == to }
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
        Log.d(TAG, "CcstockfishEngine closed")
    }

    fun loadErrorText(): String? = loadError

    // ═════════════ 基础 I/O ═════════════

    private fun sendCommand(cmd: String) {
        sendLock.lock()
        try {
            writer?.let { it.write(cmd); it.write("\n"); it.flush() }
        } catch (_: Exception) {}
        finally { sendLock.unlock() }
    }

    private fun waitForLine(kw: String, timeoutMs: Long): String? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val line = inputQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
            if (line.contains(kw)) return line
        }
        return null
    }
}