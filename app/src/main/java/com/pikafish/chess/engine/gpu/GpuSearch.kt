package com.pikafish.chess.engine.gpu

import android.util.Log
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceType

/**
 * ★★★ GPU 神经引擎 —— 搜索核 ★★★
 *
 * 粒子深度优先 alpha-beta 搜索（negamax），叠加：
 *   - 置换表（zobrist 哈希，避免重复搜索）
 *   - 静态搜索(quiescence)：只吃子，缓解水平效应
 *   - 走法排序：吃子(MVV-LVA)优先
 *   - 迭代加深 + 时间控制（movetime / 深度上限）
 *   - 根路线生成（Multi-PV）：供"预想列表"预测人脑多手并各配应着
 *
 * 评估全部走 GpuEvaluator（GPU 批/单推理，GPU 不可用时自动回 CPU 参考），
 * 这样搜索的叶子评估被搬到 GPU 计算着色器上执行。
 */
class GpuSearch(
    private val net: GpuChessNet,
    private val evaluator: GpuEvaluator
) {

    companion object {
        private const val TAG = "GpuSearch"
        private const val MATE = 1000000
        private const val MAX_PLY = 64
    }

    // 置换表
    private class TTEntry(val key: Long, val moveUci: String, val score: Int, val depth: Int, val flag: Int)
    private val tt = HashMap<Long, TTEntry>(1.shl(16))
    private var ttGen = 0

    // 计时
    private var startNanos = 0L
    private var hardDeadlineNanos = 0L
    private var maxDepthLimit = 0
    private var abortFlag = false

    /** 请求尽快中止当前搜索（线程安全地置标，由搜索循环在下一个节点响应） */
    fun abort() { abortFlag = true }

    @Volatile var nodesSearched = 0L
    private val killers = Array(MAX_PLY) { arrayOfNulls<String>(2) }

    // 是否走棋方已无合法走法的判定最简化为：无合法走法即回退（打将必应也涵盖）

    fun reset() { ttGen++; nodesSearched = 0; for (k in 0 until MAX_PLY) { killers[k][0] = null; killers[k][1] = null } }

    /** 评估一个局面（GPU 首，CPU 兜底），返回从走棋方视角的厘分 */
    private fun evalBoard(board: Board): Int {
        val x = net.encodeFeatures(board, board.currentPlayer == com.pikafish.chess.model.PieceColor.RED)
        return (evaluator.evaluateSingle(x) * 2).toInt().coerceIn(-MATE * 4, MATE * 4)
    }

    private fun boardKey(board: Board): Long {
        var h = 1469598103934665603L
        for (r in 0..9) for (c in 0..8) {
            val p = board.pieceAt(r, c) ?: continue
            h = h xor (p.type.ordinal * 31L + (if (p.color == com.pikafish.chess.model.PieceColor.RED) 7 else 13)) * (r * 9L + c + 17)
            h *= 1099511628211L
        }
        h *= 1099511628211L
        h = h xor (if (board.currentPlayer == com.pikafish.chess.model.PieceColor.RED) 881L else 977L)
        return h
    }

    fun isBoardTerminal(board: Board): Int? {
        val legal = board.getLegalMoves(board.currentPlayer)
        if (legal.isEmpty()) {
            // 无子可走：被绝杀（本方输）——若发生在己方行棋即为输
            return -MATE
        }
        return null
    }

    /** 迭代加深搜索入口。moveGen 限制/排除由 board 上层预先传入 excluded/restrict。 */
    fun search(
        board: Board,
        timeMs: Long = 2500,
        maxDepth: Int = 8,
        excludedUci: Set<String> = emptySet(),
        restrictToUci: Set<String>? = null,
        needRootLines: Int = 0,
        progress: ((depth: Int, score: Int, nodes: Long, nps: Long, pv: String) -> Unit)? = null
    ): SearchResult {
        reset()
        startNanos = System.nanoTime()
        val timeBudget = timeMs.coerceAtLeast(50)
        hardDeadlineNanos = startNanos + (timeBudget + 400) * 1_000_000L
        maxDepthLimit = maxDepth.coerceIn(1, 24)
        abortFlag = false
        nodesSearched = 0

        val rootLegal = legalMovesFull(board, excludedUci, restrictToUci)
        if (rootLegal.isEmpty()) {
            return SearchResult(null, -MATE, 0, 0, 0, "", emptyList())
        }

        var bestMove: Move? = rootLegal[0]
        var bestScore = -MATE * 2
        var achievedDepth = 0
        var bestPv = ""

        // 静态子力快分（用于限定合法但不必要的首子）
        try {
            bestScore = evalBoard(board)
        } catch (_: Exception) {}

        for (d in 1..maxDepthLimit) {
            if (abortFlag || System.nanoTime() > hardDeadlineNanos) break
            var iterBest: Move? = null
            var iterBestScore = -MATE * 2
            var alpha = -MATE * 2
            val beta = MATE * 2

            // 排序根走法：上次 best 优先，其次吃子
            val ordered = orderRootMoves(rootLegal, board, bestMove)

            for (mv in ordered) {
                if (abortFlag || System.nanoTime() > hardDeadlineNanos) { abortFlag = true; break }
                board.makeMoveUnchecked(mv)
                var sc: Int
                val captured = mv.captured
                if (captured != null && captured.type == PieceType.KING) {
                    sc = MATE - (d * 2 + 1)
                } else {
                    sc = -negamax(board, d - 1, -beta, -alpha, 1)
                }
                board.undoMoveUnchecked()
                if (abortFlag) { iterBest = null; break }
                if (sc > iterBestScore) {
                    iterBestScore = sc
                    iterBest = mv
                }
                if (sc > alpha) alpha = sc
            }

            if (iterBest != null) {
                bestMove = iterBest
                bestScore = iterBestScore
                achievedDepth = d
                // 重建 PV（自 root 起的一条主线）
                bestPv = extractPv(board, iterBest, d)
                val nodes = nodesSearched
                val nps = nodes * 1_000_000L / ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1)
                progress?.invoke(d, iterBestScore, nodes, nps, bestPv)
            } else if (abortFlag) {
                break
            }
        }

        // 根路线（预想/多线）：对前 needRootLines 个根走法各做一次浅层补搜，得出评分与应着
        val lines = if (needRootLines > 0) buildRootLines(board, rootLegal, (needRootLines).coerceAtMost(5)) else emptyList()

        val nodes = nodesSearched
        val elapsedMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1)
        val nps = nodes * 1000 / elapsedMs
        Log.d(TAG, "GpuSearch done: depth=$achievedDepth score=$bestScore nodes=$nodes best=$bestMove")
        return SearchResult(bestMove, bestScore, achievedDepth, nodes, nps, bestPv, lines)
    }

    /** 根走法排序：上次 best 优先；已排除集由 legalMovesFull 处理 */
    private fun orderRootMoves(moves: List<Move>, board: Board, prevBest: Move?): List<Move> {
        val prev = prevBest?.let { it.from.row * 9 + it.from.col to it.to.row * 9 + it.to.col }
        return moves.sortedByDescending { mv ->
            var k = 0
            if (prev != null && (mv.from.row * 9 + mv.from.col) == prev.first && (mv.to.row * 9 + mv.to.col) == prev.second) k += 100_000
            if (mv.captured != null) k += 10_000 + (7 - mv.captured.type.ordinal) * 100
            k
        }
    }

    /** 完整合法走法（应用 exclude / restrict），并缓存（一次性） */
    private fun legalMovesFull(board: Board, excludedUci: Set<String>, restrictToUci: Set<String>?): List<Move> {
        val all = board.getLegalMoves(board.currentPlayer)
        return if (restrictToUci != null) {
            all.filter { it.toUci() in restrictToUci }
        } else if (excludedUci.isNotEmpty()) {
            all.filter { it.toUci() !in excludedUci }
        } else all
    }

    /** negamax + alpha-beta + quiescence + TT */
    private fun negamax(board: Board, depth: Int, alpha0: Int, beta0: Int, ply: Int): Int {
        if (abortFlag || System.nanoTime() > hardDeadlineNanos) { abortFlag = true; return 0 }
        nodesSearched++
        var alpha = alpha0
        val beta = beta0
        val st = board.currentPlayer

        val legal = board.getLegalMoves(st)
        if (legal.isEmpty()) return -MATE + ply   // 无子可走即败

        // 绝杀检测（下一步能吃帅即大胜提前返回）
        for (mv in legal) {
            val cap = board.pieceAt(mv.to.row, mv.to.col)
            if (cap != null && cap.type == PieceType.KING) {
                return MATE - ply
            }
        }

        val key = boardKey(board)
        val ttE = tt[key]
        if (ttE != null && ttE.depth >= depth) {
            if (ttE.flag == 0) return ttE.score                       // exact
            if (ttE.flag == 1 && ttE.score <= alpha) return ttE.score // upper/lower
            if (ttE.flag == 2 && ttE.score >= beta) return ttE.score
        }

        if (depth <= 0) {
            return quiescence(board, alpha, beta, ply)
        }

        // 走法排序：吃子优先 + 杀手走法
        val ttFirst = ttE?.moveUci
        val killersPly = killers[ply.coerceAtMost(MAX_PLY - 1)]
        val ordered = legal.sortedByDescending { mv ->
            val u = mv.toUci()
            var k = 0
            if (ttFirst == u) k += 500_000
            if (mv.captured != null) k += 10_000 + (7 - mv.captured.type.ordinal) * 200
            if (u == killersPly[0] || u == killersPly[1]) k += 1_000
            k
        }

        var bestScore = -MATE * 2
        var bestMoveUci: String? = null
        for (mv in ordered) {
            board.makeMoveUnchecked(mv)
            val cap = mv.captured
            val sc: Int = if (cap != null && cap.type == PieceType.KING) {
                MATE - (ply + 1)
            } else {
                val r = -negamax(board, depth - 1, -beta, -alpha, ply + 1)
                r
            }
            board.undoMoveUnchecked()
            if (abortFlag) return 0
            if (sc > bestScore) {
                bestScore = sc
                bestMoveUci = mv.toUci()
            }
            if (bestScore > alpha) alpha = bestScore
            if (alpha >= beta) {
                // beta cutoff：更新杀手
                if (bestMoveUci != null && !ordered.any { mv2 -> mv2.captured != null }) {
                    killers[ply.coerceAtMost(MAX_PLY - 1)][1] = killers[ply.coerceAtMost(MAX_PLY - 1)][0]
                    killers[ply.coerceAtMost(MAX_PLY - 1)][0] = bestMoveUci
                }
                break
            }
        }

        // 写回置换表
        val flag = when {
            bestScore <= alpha0 -> 1 // upper bound (fail-low)
            bestScore >= beta0 -> 2 // lower bound (fail-high)
            else -> 0
        }
        tt[key] = TTEntry(key, bestMoveUci ?: "", bestScore, depth, flag)
        return bestScore
    }

    /** 静态搜索：只考虑吃子，防止水平效应 */
    private fun quiescence(board: Board, alpha0: Int, beta: Int, ply: Int): Int {
        if (abortFlag || System.nanoTime() > hardDeadlineNanos) { abortFlag = true; return 0 }
        nodesSearched++
        var alpha = alpha0

        // 不吃子的"静态"分数作为底线
        val standing = evalBoard(board)
        if (standing >= beta) return standing

        val legal = board.getLegalMoves(board.currentPlayer)
        if (legal.isEmpty()) return -MATE + ply

        // 只看吃子（含吃帅）
        val captures = legal.filter { mv ->
            val cap = board.pieceAt(mv.to.row, mv.to.col)
            cap != null
        }
        if (standing > alpha) alpha = standing
        val ordered = captures.sortedByDescending { mv ->
            val cap = board.pieceAt(mv.to.row, mv.to.col) ?: return@sortedByDescending 0
            10_000 + (7 - cap.type.ordinal) * 200 - mv.piece.type.ordinal
        }

        for (mv in ordered) {
            val cap = board.pieceAt(mv.to.row, mv.to.col)
            board.makeMoveUnchecked(mv)
            val sc: Int
            if (cap != null && cap.type == PieceType.KING) {
                sc = MATE - (ply + 1)
            } else {
                sc = -quiescence(board, -beta, -alpha, ply + 1)
            }
            board.undoMoveUnchecked()
            if (abortFlag) return 0
            if (sc > alpha) alpha = sc
            if (alpha >= beta) break
        }
        return alpha
    }

    /** 从迭代加深取得的 bestPv 补全为 v表示的主线（简单用 root best + 第 2 层走法展开） */
    private fun extractPv(board: Board, bestMove: Move, depth: Int): String {
        val sb = StringBuilder()
        val b = board.clone()
        b.makeMoveUnchecked(bestMove)
        sb.append(bestMove.toUci())
        sb.append(' ')
        var ply = 0
        var cur = b
        while (ply < depth - 1 && ply < 8) {
            val moves = cur.getLegalMoves(cur.currentPlayer)
            if (moves.isEmpty()) break
            // 取静态分最高的走法作为主变延续
            var chosen: Move? = null
            var bestV = Long.MIN_VALUE
            for (mv in moves) {
                val v = mv.captured?.type?.ordinal?.toLong() ?: 0L
                if (v > bestV) { bestV = v; chosen = mv }
            }
            if (chosen == null) chosen = moves[0]
            sb.append(chosen.toUci()).append(' ')
            cur.makeMoveUnchecked(chosen)
            ply++
        }
        return sb.toString().trim()
    }

    /** 生成前 topN 条根路线（每条：预测人脑走法 + 引擎应着 + 评分），供预想列表 */
    private fun buildRootLines(board: Board, rootMoves: List<Move>, topN: Int): List<SearchLine> {
        val lines = ArrayList<SearchLine>()
        val sampled = rootMoves.take(40)
        for (mv in sampled) {
            if (lines.size >= topN) break
            val cap = board.pieceAt(mv.to.row, mv.to.col)
            board.makeMoveUnchecked(mv)
            // 应着：浅层搜索
            var reply: Move? = null
            var mvScore: Int
            if (cap != null && cap.type == PieceType.KING) {
                mvScore = MATE
            } else {
                val sub = search(board, timeMs = 350, maxDepth = 3)
                mvScore = sub.score
                reply = sub.bestMove
            }
            board.undoMoveUnchecked()
            val objInter = lines
            objInter.add(SearchLine(mv, reply, mvScore, mv.toUci() + (reply?.let { " " + it.toUci() } ?: "")))
        }
        return lines.sortedByDescending { it.score }
    }

    data class SearchLine(val move: Move, val reply: Move?, val score: Int, val pv: String)
    data class SearchResult(
        val bestMove: Move?,
        val score: Int,
        val depth: Int,
        val nodes: Long,
        val nps: Long,
        val pv: String,
        val lines: List<SearchLine>
    )
}