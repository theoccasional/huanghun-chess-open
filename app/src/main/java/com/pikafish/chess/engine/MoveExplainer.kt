package com.pikafish.chess.engine

import android.util.Log
import com.pikafish.chess.model.*
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType

/**
 * 人话解释引擎 — 完全离线，无需 API Key，无需联网
 *
 * 核心原理：
 *  1. 解析皮卡鱼引擎输出的原始数据（评分、深度、PV路线、候选走法）
 *  2. 将 UCI 走法转换为中文棋谱记号（如"炮二平五"）
 *  3. 基于分差阈值对走棋质量进行分级（妙手/好棋/正着/疑问着/失误/败着）
 *  4. 使用规则模板 + 象棋术语词典生成自然语言解释
 *
 * 功能：
 *  - AI 走棋解释：解释 AI 为什么走这步，目的是什么
 *  - 人脑走棋评价：评价人脑走的棋好坏，给出建议
 *  - 局面评估：用大白话描述当前局面优劣
 *  - 假设分析：如果走某一步会怎样
 *  - 战术检测：重炮、闷宫、挂角、闪击、捉双、铁门栓等
 *  - 后果预测：走完后可能导致的后果
 *  - 心理点评：对走棋心理进行分析
 *  - 劣势追溯：锁定失子失势的原因
 */
object MoveExplainer {

    // ── 走棋质量分级（基于 centipawn loss）──

    enum class MoveQuality(val label: String, val emoji: String) {
        BRILLIANT("妙手", "✨"),
        GOOD("好棋", "👍"),
        STANDARD("正着", "✅"),
        PLAYABLE("可走", "🤔"),
        QUESTIONABLE("疑问着", "⚠️"),
        MISTAKE("失误", "❌"),
        BLUNDER("败着", "💀")
    }

    /**
     * 根据分差（centipawn loss）判断走棋质量
     */
    fun classifyQuality(cpLoss: Int): MoveQuality {
        return when {
            cpLoss <= 0 -> MoveQuality.BRILLIANT
            cpLoss <= 20 -> MoveQuality.GOOD
            cpLoss <= 50 -> MoveQuality.STANDARD
            cpLoss <= 100 -> MoveQuality.PLAYABLE
            cpLoss <= 200 -> MoveQuality.QUESTIONABLE
            cpLoss <= 400 -> MoveQuality.MISTAKE
            else -> MoveQuality.BLUNDER
        }
    }

    // ── UCI 走法转中文棋谱 ──

    private val redNumbers = arrayOf("九", "八", "七", "六", "五", "四", "三", "二", "一")
    private val blackNumbers = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
    private val stepNumbers = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")

    fun uciToChineseNotation(uciMove: String, board: Board): String {
        if (uciMove.length < 4) return ""
        val from = Position.fromUci(uciMove.substring(0, 2))
        val to = Position.fromUci(uciMove.substring(2, 4))
        val piece = board.getPiece(from) ?: return ""   // ★ 悬空/非法UCI一律不泄入棋谱（杜绝"h2e2"烂尾）
        return moveToChineseNotation(Move(from, to, piece, board.getPiece(to)), board)
    }

    fun moveToChineseNotation(move: Move, board: Board): String {
        val piece = move.piece
        val color = piece.color
        val pieceName = piece.displayName()
        val fromCol = move.from.col
        val toCol = move.to.col
        val fromRow = move.from.row
        val toRow = move.to.row
        val isRed = color == PieceColor.RED
        val colLabels = if (isRed) redNumbers else blackNumbers
        val stepLabels = if (isRed) stepNumbers else (1..9).map { it.toString() }.toTypedArray()
        val fromColLabel = colLabels[fromCol]
        // ★★ 同列同型≥2子：标准记谱用"前(近敌阵)/中/后(近己方底线)"定位，放子名之前
        val frontLabel = sameFileLabel(board, piece.type, color, fromCol, fromRow)

        if (fromCol == toCol) {
            val steps = kotlin.math.abs(fromRow - toRow)
            val isForward = if (isRed) toRow < fromRow else toRow > fromRow
            val action = if (isForward) "进" else "退"
            val stepLabel = if (steps <= 9) stepLabels[steps - 1] else steps.toString()
            return if (frontLabel != null) "$frontLabel$pieceName$action$stepLabel" else "$pieceName$fromColLabel$action$stepLabel"
        } else if (fromRow == toRow) {
            val toColLabel = colLabels[toCol]
            return if (frontLabel != null) "$frontLabel${pieceName}平${toColLabel}" else "${pieceName}${fromColLabel}平${toColLabel}"
        } else {
            val isForward = if (isRed) toRow < fromRow else toRow > fromRow
            val action = if (isForward) "进" else "退"
            val toColLabel = colLabels[toCol]
            return if (frontLabel != null) "$frontLabel$pieceName$action${toColLabel}" else "$pieceName$fromColLabel$action$toColLabel"
        }
    }

    /** 同列本方同型≥2子时返回本子的位置标签(近敌阵为前)；仅1子返回null。兵卒可有前/中/后，其余只分前/后。 */
    private fun sameFileLabel(board: Board, type: PieceType, color: PieceColor, fromCol: Int, fromRow: Int): String? {
        val rows = mutableListOf<Int>()
        for (r in 0..9) {
            val p = board.getPiece(Position(r, fromCol))
            if (p != null && p.color == color && p.type == type) rows.add(r)
        }
        if (rows.size < 2) return null
        val ordered = if (color == PieceColor.RED) rows.sorted() else rows.sortedDescending()
        val idx = ordered.indexOf(fromRow)
        if (idx < 0) return null
        return sameFileSiblingLabels(type, rows.size).getOrNull(idx)
    }

    /**
     * ★ 同列叠子称呼：
     *   只有兵/卒(每方5颗)才可能有"前/中/后"；其余兵种(车马炮仕相)每方仅2颗，
     *   只分"前/后"，永远没有"中"。数量按同时落位的"本方同型子"计，不掺对方子。
     */
    private fun sameFileSiblingLabels(type: PieceType, size: Int): List<String> {
        if (type != PieceType.PAWN) {
            // 非兵卒类：同列本型子至多2颗 → 只有前/后
            return when (size) {
                1 -> emptyList()              // 单子，退回落点列号记谱
                else -> listOf("前", "后")     // ≥2 也只按前/后（理论上只会是2）
            }
        }
        // 兵/卒：可同列叠3颗及以上，才有"中"
        return when (size) {
            2 -> listOf("前", "后")
            3 -> listOf("前", "中", "后")
            else -> {
                val num = listOf("", "二", "三", "四", "五", "六", "七", "八", "九")
                listOf("前") + (1 until size).map { num.getOrElse(it) { "九" } }
            }
        }
    }

    // ── 评分转文字 ──

    fun scoreToText(score: Int, isRedToMove: Boolean): String {
        // 杀棋评分：新编码分(29999封顶)，用 matePliesOf 反推成杀步数
        if (JniEngine.isMateScore(score)) {
            val plies = JniEngine.matePliesOf(score) ?: 1
            val side = if ((score > 0) == isRedToMove) "红方" else "黑方"
            return if (plies <= 3) "${side}即将杀棋，${plies}步内绝杀！" else "${side}胜势已定，${plies}步内杀棋"
        }

        val absScore = kotlin.math.abs(score)
        val advantageSide = if ((score > 0) == isRedToMove) "红方" else "黑方"

        return when {
            absScore <= 20 -> "双方势均力敌，局面均势"
            absScore <= 60 -> "${advantageSide}稍占优势"
            absScore <= 120 -> "${advantageSide}略占上风"
            absScore <= 250 -> "${advantageSide}明显占优"
            absScore <= 500 -> "${advantageSide}大占优势"
            absScore <= 900 -> "${advantageSide}胜势已现"
            else -> "${advantageSide}局面碾压，胜局已定"
        }
    }

    fun scoreToBrief(score: Int): String {
        val mt = JniEngine.mateText(score)
        return mt ?: (if (score >= 0) "+$score" else "$score")
    }

    // ── 劣势追踪数据 ──

    /**
     * 劣势追踪信息：记录首次失子失势的时刻
     */
    data class DisadvantageInfo(
        val stepNumber: Int,           // 发生在第几步
        val side: String,              // 哪一方落后（"红方"/"黑方"）
        val reason: String,            // 原因描述
        val materialDiff: Int,         // 子力差距（厘兵）
        val scoreAtTime: Int,          // 当时评分
        val isPersistent: Boolean      // 是否持续至今
    )

    /**
     * 检测当前局面是否存在劣势，返回劣势信息
     */
    fun detectDisadvantage(score: Int, isRedToMove: Boolean, board: Board): DisadvantageInfo? {
        val absScore = kotlin.math.abs(score)
        if (absScore < 100) return null // 差距小于100分不视为劣势

        val disadvantagedSide = if ((score > 0) == isRedToMove) "黑方" else "红方"
        val materialDiff = analyzeMaterialDiff(board)
        val diffSide = if (materialDiff > 0) "红方" else if (materialDiff < 0) "黑方" else null

        val diffAbs = kotlin.math.abs(materialDiff)
        val reason = when {
            absScore > 500 && diffAbs >= 500 -> "${disadvantagedSide}失子，子力落后${diffAbs / 100}兵以上"
            absScore > 500 && diffAbs < 500 -> "${disadvantagedSide}失势，局面被压制严重"
            absScore > 200 -> "${disadvantagedSide}局面被动，多处受制"
            else -> "${disadvantagedSide}略处下风"
        }

        return DisadvantageInfo(
            stepNumber = 0,
            side = disadvantagedSide,
            reason = reason,
            materialDiff = materialDiff,
            scoreAtTime = score,
            isPersistent = true
        )
    }

    // ── AI 走棋解释 ──

    fun explainAiMove(
        uciMove: String,
        score: Int,
        depth: Int,
        pv: String,
        board: Board,
        disadvantageInfo: DisadvantageInfo? = null
    ): String {
        val notation = uciToChineseNotation(uciMove, board)
        val move = Move.fromUci(uciMove, board) ?: return "AI 走了 $uciMove"
        // ★ 安全校验：用棋盘上的实际棋子，不依赖 move.piece（可能因异步回调过期）
        val boardPiece = board.getPiece(move.from)
        val piece = boardPiece ?: move.piece
        val color = piece.color
        val colorName = if (color == PieceColor.RED) "红方" else "黑方"
        val opponentName = if (color == PieceColor.RED) "黑方" else "红方"
        // ★ 安全校验：用棋盘快照确认是否吃子，防止"自己吃自己"
        val captured = getSafeCaptured(move, board)
        val isRedToMove = color == PieceColor.RED
        Log.d("MoveExplainer", "explainAiMove: uci=$uciMove, pieceType=${piece.type}, captured=$captured")

        val sb = StringBuilder()
        sb.append("${colorName}${notation}")

        // 吃子描述
        if (captured != null) {
            sb.append("，吃掉${opponentName}的${captured.displayName()}")
            val materialGain = pieceValue(captured.type)
            if (materialGain >= 500 && score > 0) {
                sb.append("！得子优势")
            } else if (materialGain >= 500 && score <= 0) {
                sb.append("，虽然得子，但局面仍有隐忧")
            }
        }

        // 将军检测
        val tempBoard = Board()
        tempBoard.loadFromFen(board.toFen())
        tempBoard.makeMove(move)
        val opponentColor = color.opponent()
        if (tempBoard.isInCheck(opponentColor)) {
            if (tempBoard.isCheckmate(opponentColor)) {
                sb.append("，绝杀！棋局结束，${colorName}获胜。")
                return sb.append("（搜索深度${depth}层）").toString()
            }
            sb.append("，将军！")
        }

        // 战术检测 + 走棋目的分析（不再互斥，都显示）
        val tactics = detectTactics(move, board, tempBoard)
        if (tactics.isNotEmpty()) {
            sb.append("。").append(tactics)
        }
        val purpose = analyzeMovePurpose(move, board, tempBoard)
        if (purpose.isNotEmpty()) {
            sb.append("。$purpose")
        }

        // 局面评估
        val positionAssessment = scoreToText(score, isRedToMove)
        sb.append("。当前局面：$positionAssessment")

        // 劣势追溯
        if (disadvantageInfo != null) {
            val disadvantagedSide = if ((score > 0) == isRedToMove) "黑方" else "红方"
            if (disadvantagedSide == disadvantageInfo.side) {
                sb.append("。${disadvantageInfo.side}仍处劣势，${disadvantageInfo.reason}")
            }
        }

        sb.append("（搜索深度${depth}层）")

        return sb.toString()
    }

    // ── 人脑走棋评价（增强版）──

    fun evaluateHumanMove(
        humanMoveUci: String,
        bestMoveUci: String,
        humanScore: Int,
        bestScore: Int,
        depth: Int,
        pv: String,
        board: Board,
        disadvantageInfo: DisadvantageInfo? = null,
        gamePhase: Int = 0  // 0=开局, 1=中局, 2=残局
    ): String {
        val humanNotation = uciToChineseNotation(humanMoveUci, board)
        val bestNotation = uciToChineseNotation(bestMoveUci, board)
        val move = Move.fromUci(humanMoveUci, board) ?: return "你走了 $humanMoveUci"
        // ★ 安全校验：用棋盘上的实际棋子，不依赖 move.piece（可能因异步回调过期）
        val boardPiece = board.getPiece(move.from)
        val piece = boardPiece ?: move.piece
        val color = piece.color
        val colorName = if (color == PieceColor.RED) "红方" else "黑方"
        val opponentName = if (color == PieceColor.RED) "黑方" else "红方"
        Log.d("MoveExplainer", "evaluateHumanMove: uci=$humanMoveUci, pieceType=${piece.type}, boardPiece=$boardPiece")

        val isRedToMove = color == PieceColor.RED
        val cpLoss = bestScore - humanScore
        val quality = classifyQuality(cpLoss)
        // ★ 安全吃子检测：从棋盘读取并验证颜色，防止"自己吃自己"
        val safeCaptured = getSafeCaptured(move, board)

        val sb = StringBuilder()
        sb.append("你走了${humanNotation}")

        // 吃子描述
        if (safeCaptured != null) {
            sb.append("，吃了${opponentName}的${safeCaptured.displayName()}")
        }

        sb.append("，${quality.emoji}${quality.label}")

        // ── 1. 走棋质量评价 ──
        when (quality) {
            MoveQuality.BRILLIANT -> {
                sb.append("！这步棋和引擎推荐完全一致，棋感极佳，对局面的理解非常精准。")
                addPsychologyNote(sb, "这步棋显示出深厚的棋力功底，思路清晰、判断准确。")
            }
            MoveQuality.GOOD -> {
                sb.append("。走得不错，和最佳走法很接近。")
                if (humanMoveUci != bestMoveUci) {
                    sb.append("不过引擎更推荐${bestNotation}。")
                }
            }
            MoveQuality.STANDARD -> {
                sb.append("。这步可以接受，但不是最优选择。")
                sb.append("引擎推荐${bestNotation}。")
            }
            MoveQuality.PLAYABLE -> {
                sb.append("。这步虽然能走，但略有损失。")
                sb.append("更好的选择是${bestNotation}。")
                addPsychologyNote(sb, "这步棋略显急躁，可能没有仔细推敲对方可能的应着。")
            }
            MoveQuality.QUESTIONABLE -> {
                sb.append("。这步有些疑问，损失了${cpLoss}分的优势。")
                sb.append("建议走${bestNotation}。")
                addPsychologyNote(sb, "这步棋考虑不够周全，可能忽略了对方的某些反击手段。")
            }
            MoveQuality.MISTAKE -> {
                sb.append("！这步失误了，白白损失了${cpLoss}分。")
                sb.append("应该走${bestNotation}才对。")
                if (safeCaptured != null) {
                    sb.append("虽然吃了子，但可能得不偿失。")
                }
                addPsychologyNote(sb, "这步棋显得比较浮躁，急于求成反而容易给对手可乘之机。")
            }
            MoveQuality.BLUNDER -> {
                sb.append("！！这步是败着，损失了${cpLoss}分，可能直接导致败局！")
                sb.append("强烈建议走${bestNotation}。")
                addPsychologyNote(sb, "这步棋过于肤浅，没有看清局面，可能是心态急躁所致。")
            }
        }

        // ── 2. 战术分析 ──
        val tempBoard = Board()
        tempBoard.loadFromFen(board.toFen())
        tempBoard.makeMove(move)

        val tactics = detectTactics(move, board, tempBoard)
        if (tactics.isNotEmpty()) {
            sb.append(tactics)
        }

        // 将军检测
        if (tempBoard.isInCheck(color.opponent())) {
            sb.append("这步将军了${opponentName}。")
            if (tempBoard.isCheckmate(color.opponent())) {
                sb.append("而且是绝杀！恭喜！")
            }
        }

        // ── 3. 后果预测 ──
        val consequence = predictConsequence(move, board, tempBoard, cpLoss, bestMoveUci, pv)
        if (consequence.isNotEmpty()) {
            sb.append(consequence)
        }

        // ── 4. 局面评估 ──
        val positionAssessment = scoreToText(humanScore, isRedToMove)
        sb.append("当前局面：$positionAssessment。")

        // ── 5. 劣势追溯 ──
        if (disadvantageInfo != null) {
            val disadvantagedSide = if ((humanScore > 0) == isRedToMove) "黑方" else "红方"
            if (disadvantagedSide == disadvantageInfo.side) {
                // 用形容词描述损失程度
                val lossDesc = when {
                    kotlin.math.abs(disadvantageInfo.materialDiff) >= 1000 -> "不可挽回的损失"
                    kotlin.math.abs(disadvantageInfo.materialDiff) >= 500 -> "较大损失"
                    kotlin.math.abs(disadvantageInfo.materialDiff) >= 200 -> "中等损失"
                    else -> "较小的损失"
                }
                // 逻辑通顺：先说这步走对了，再说但情况仍不乐观
                if (cpLoss <= 50) {
                    sb.append("这步棋走的是对的，但情况仍然不是很乐观。")
                    sb.append("毕竟前期已经造成了${lossDesc}。")
                } else {
                    sb.append("不过，${disadvantageInfo.side}此前${disadvantageInfo.reason}，")
                    sb.append("这个差距目前还没有抹平。")
                }
                val materialDiff = kotlin.math.abs(disadvantageInfo.materialDiff)
                if (materialDiff >= 500) {
                    sb.append("尽管这步在所有能走的棋中算是最顽强的，但仍然处于下风。")
                    sb.append("${opponentName}优势显著，${disadvantageInfo.side}只有在对方出错的情况下才能赶回平局状态。")
                } else if (materialDiff >= 200) {
                    sb.append("${opponentName}优势明显，${disadvantageInfo.side}需要抓住机会才有翻盘希望。")
                } else {
                    sb.append("${disadvantageInfo.side}需要抓住机会，${opponentName}稍有松懈就可能扳回局面。")
                }
            } else {
                // 劣势方不是当前走棋方，说明是对方落后
                if (cpLoss <= 50) {
                    sb.append("这步棋走得好，保持住了优势。")
                }
            }
        }

        // ── 6. 差距说明 ──
        if (disadvantageInfo == null && cpLoss > 50) {
            addGapExplanation(sb, humanScore, bestScore, isRedToMove, colorName, opponentName)
        }

        return sb.toString()
    }

    // ── 局面分析 ──

    fun explainPosition(
        score: Int,
        depth: Int,
        pv: String,
        board: Board
    ): String {
        val isRedToMove = board.currentPlayer == PieceColor.RED
        val sideName = if (isRedToMove) "红方" else "黑方"

        val sb = StringBuilder()
        sb.append("【局面分析】\n")
        sb.append("当前轮到${sideName}走棋。")
        sb.append(scoreToText(score, isRedToMove))
        sb.append("。\n")

        val materialAnalysis = analyzeMaterial(board)
        if (materialAnalysis.isNotEmpty()) {
            sb.append("子力对比：$materialAnalysis。\n")
        }

        if (pv.isNotEmpty()) {
            val firstMove = pv.split(" ").firstOrNull()
            if (firstMove != null && firstMove.length >= 4) {
                val bestNotation = uciToChineseNotation(firstMove, board)
                sb.append("引擎推荐：${bestNotation}。")
                val move = Move.fromUci(firstMove, board)
                if (move != null) {
                    val purpose = analyzeMovePurpose(move, board, null)
                    if (purpose.isNotEmpty()) {
                        sb.append(purpose)
                    }
                }
                sb.append("。\n")
            }
        }

        if (pv.isNotEmpty()) {
            val moves = pv.split(" ").take(5)
            val variations = StringBuilder()
            val tempBoard = Board()
            tempBoard.loadFromFen(board.toFen())
            for (uci in moves) {
                if (uci.length < 4) continue
                val notation = uciToChineseNotation(uci, tempBoard)
                if (variations.isNotEmpty()) variations.append(" → ")
                variations.append(notation)
                val m = Move.fromUci(uci, tempBoard)
                if (m != null) tempBoard.makeMoveUnchecked(m)
            }
            if (variations.isNotEmpty()) {
                sb.append("预计变化：$variations。")
            }
        }

        sb.append("（深度${depth}层）")
        return sb.toString()
    }

    // ── 假设分析 ──

    fun explainWhatIf(
        uciMove: String,
        score: Int,
        depth: Int,
        pv: String,
        board: Board
    ): String {
        val notation = uciToChineseNotation(uciMove, board)
        val move = Move.fromUci(uciMove, board) ?: return "走法无效"
        val isRedToMove = move.piece.color == PieceColor.RED

        val sb = StringBuilder()
        sb.append("【假设分析】如果走${notation}：\n")

        // ★ 安全吃子检测
        val safeCaptured = getSafeCaptured(move, board)
        if (safeCaptured != null) {
            sb.append("这步会吃掉对方的${safeCaptured.displayName()}。")
        }

        val tempBoard = Board()
        tempBoard.loadFromFen(board.toFen())
        tempBoard.makeMove(move)
        val purpose = analyzeMovePurpose(move, board, tempBoard)
        if (purpose.isNotEmpty()) {
            sb.append(purpose)
            sb.append("。")
        }

        if (tempBoard.isInCheck(move.piece.color.opponent())) {
            sb.append("走完这步会将军对方。")
            if (tempBoard.isCheckmate(move.piece.color.opponent())) {
                sb.append("而且是绝杀！")
            }
        }

        sb.append("\n走完后：")
        sb.append(scoreToText(score, !isRedToMove))
        sb.append("（评分${scoreToBrief(score)}）")

        if (pv.isNotEmpty()) {
            val moves = pv.split(" ").take(3)
            val variations = StringBuilder()
            for (uci in moves) {
                if (uci.length < 4) continue
                val notation2 = uciToChineseNotation(uci, tempBoard)
                if (variations.isNotEmpty()) variations.append(" → ")
                variations.append(notation2)
                val m = Move.fromUci(uci, tempBoard)
                if (m != null) tempBoard.makeMoveUnchecked(m)
            }
            if (variations.isNotEmpty()) {
                sb.append("\n后续可能：$variations")
            }
        }

        sb.append("（深度${depth}层）")
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════
    // 战术检测系统
    // ═══════════════════════════════════════════════════════════

    /**
     * 检测走棋涉及的战术手段
     * @return 战术描述，无战术返回空字符串
     */
    private fun detectTactics(move: Move, beforeBoard: Board, afterBoard: Board): String {
        // ★ 安全校验：用棋盘上的实际棋子类型
        val boardPiece = beforeBoard.getPiece(move.from)
        val pieceType = boardPiece?.type ?: move.piece.type
        val color = (boardPiece ?: move.piece).color
        val opponent = color.opponent()
        val tactics = mutableListOf<String>()

        // 1. 重炮/叠炮 — 双炮在同一直线上
        if (pieceType == PieceType.CANNON) {
            val cannonsOnSameFile = countCannonsOnFile(afterBoard, color, move.to.col)
            if (cannonsOnSameFile >= 2) {
                tactics.add("形成重炮之势，双炮叠杀威力巨大")
            }
        }

        // 2. 闷宫 — 将/帅被炮在底线将军且无法移动
        if (pieceType == PieceType.CANNON && afterBoard.isInCheck(opponent)) {
            val opponentKing = findKing(afterBoard, opponent)
            if (opponentKing != null) {
                val kingRow = opponentKing.row
                if ((color == PieceColor.RED && kingRow == 9) || (color == PieceColor.BLACK && kingRow == 0)) {
                    // 底线将军
                    if (isTrappedOnBackRank(afterBoard, opponent)) {
                        tactics.add("这是闷宫杀！炮在底线将军，将/帅无路可逃")
                    }
                }
            }
        }

        // 3. 挂角马 — 马在对方九宫角上将军
        if (pieceType == PieceType.KNIGHT && afterBoard.isInCheck(opponent)) {
            val opponentKing = findKing(afterBoard, opponent)
            if (opponentKing != null) {
                val kingCol = opponentKing.col
                val kingRow = opponentKing.row
                val toCol = move.to.col
                val toRow = move.to.row
                // 挂角：马在九宫四角之一将军
                if ((kotlin.math.abs(toCol - kingCol) == 1 && kotlin.math.abs(toRow - kingRow) == 2) ||
                    (kotlin.math.abs(toCol - kingCol) == 2 && kotlin.math.abs(toRow - kingRow) == 1)) {
                    if (isInPalaceCorner(move.to, opponent)) {
                        tactics.add("挂角马！马入九宫要地将军，威胁极大")
                    }
                }
            }
        }

        // 4. 闪击 — 移动一个子，露出后面的子进行攻击
        val discoveredAttacks = findDiscoveredAttacks(move, beforeBoard, afterBoard)
        if (discoveredAttacks.isNotEmpty()) {
            val targets = discoveredAttacks.joinToString("、") { it.second.displayName() }
            tactics.add("闪击！移动${(boardPiece ?: move.piece).displayName()}后露出后方火力，攻击对方${targets}")
        }

        // 5. 捉双 — 一步棋后，该子同时威胁对方两个无根大子（车马炮）
        //    捉双要求：两个目标都是无根的（没有己方子保护），且都是大子（车马炮）
        val doubleAttacks = findDoubleAttacksOnUndefendedMajors(move, afterBoard, opponent)
        if (doubleAttacks.size >= 2) {
            val targets = doubleAttacks.joinToString("和") { it.displayName() }
            tactics.add("捉双！同时威胁对方无根的${targets}，对方难以两全")
        }

        // 5b. 串捉 — 车/炮在同一直线上威胁排成直线的两个子（前面的子被捉，后面的子也被威胁）
        //     串打 — 炮通过炮架同时威胁一条线上的两个子
        val skewerAttack = findSkewerAttack(move, beforeBoard, afterBoard, opponent)
        if (skewerAttack != null) {
            if (pieceType == PieceType.CANNON) {
                tactics.add("串打！炮通过炮架一线穿二，同时威胁对方的${skewerAttack}")
            } else if (pieceType == PieceType.ROOK) {
                tactics.add("串捉！车在一条直线上同时威胁对方前后两个子：${skewerAttack}")
            }
        }

        // 6. 铁门栓 — 车在对方底线控制
        if (pieceType == PieceType.ROOK && afterBoard.isInCheck(opponent)) {
            val opponentKing = findKing(afterBoard, opponent)
            if (opponentKing != null) {
                val kingRow = opponentKing.row
                if ((color == PieceColor.RED && kingRow == 9) || (color == PieceColor.BLACK && kingRow == 0)) {
                    if (move.to.row == kingRow) {
                        tactics.add("铁门栓！车在底线将军，如铁门关锁，对方插翅难飞")
                    }
                }
            }
        }

        // 7. 双车错 — 双车配合
        val rooksOnBoard = countPiecesOfType(afterBoard, color, PieceType.ROOK)
        if (pieceType == PieceType.ROOK && rooksOnBoard >= 2) {
            val otherRookPos = findOtherRook(afterBoard, color, move.to)
            if (otherRookPos != null) {
                if (afterBoard.isInCheck(opponent)) {
                    tactics.add("双车错！双车配合进攻，威力倍增")
                }
            }
        }

        // 8. 马后炮 — 马将军后炮在后
        if (afterBoard.isInCheck(opponent)) {
            if (pieceType == PieceType.KNIGHT) {
                // 检查是否有己方炮在将军线上
                if (hasCannonBehindKnight(afterBoard, move.to, color, opponent)) {
                    tactics.add("马后炮！马将炮随，连环杀招")
                }
            }
        }

        // 9. 天地炮 — 一炮在中路，一炮在底线
        if (pieceType == PieceType.CANNON) {
            if (hasHeavenEarthCannons(afterBoard, color)) {
                tactics.add("天地炮！一炮镇中路，一炮压底线，上下夹击")
            }
        }

        // 10. 声东击西 — 严格条件，不可滥用：
        //   a) 闪击型：走子后，移动的子本身攻击一个目标，同时因移开露出后方另一子攻击另一个目标
        //      （即两个不同的己方子分别威胁对方两个不同的子，类似抽将的原理但非将军）
        //   b) 弃子型：走子献/弃一个小子（士相兵），但走子后己方另一大子（车马炮）已瞄准对方无法躲避的大子
        //   注意：一子同时威胁两个子是"捉双"不是"声东击西"，不在此检测
        val safeCapturedForTactics = getSafeCaptured(move, beforeBoard)
        if (safeCapturedForTactics == null && !afterBoard.isInCheck(opponent)) {
            // 条件a：闪击型声东击西 — 移动的子攻击一个目标，后方露出的子攻击另一个目标
            val discoveredAttacks = findDiscoveredAttacks(move, beforeBoard, afterBoard)
            if (discoveredAttacks.isNotEmpty()) {
                // 移动的子本身也攻击一个无根大子（不是将军）
                val movePieceAttacks = findAttackedUndefendedMajorsByPiece(afterBoard, move.to, color, opponent)
                if (movePieceAttacks.isNotEmpty() && discoveredAttacks.any { 
                    afterBoard.getPiece(it.first)?.let { p -> 
                        p.type == PieceType.ROOK || p.type == PieceType.KNIGHT || p.type == PieceType.CANNON 
                    } == true 
                }) {
                    val target1 = movePieceAttacks.mapNotNull { afterBoard.getPiece(it)?.displayName() }
                    val target2 = discoveredAttacks.map { it.second.displayName() }
                    if (target1.isNotEmpty() && target2.isNotEmpty()) {
                        tactics.add("声东击西！${(boardPiece ?: move.piece).displayName()}攻${target1.joinToString("")}，同时露出后方火力威胁${target2.joinToString("、")}，令对方顾此失彼")
                    }
                }
            }

            // 条件b：弃子型声东击西 — 走的是小子（士相兵），但己方大子已瞄准对方大子
            if (tactics.isEmpty() && (
                    pieceType == PieceType.ADVISOR || 
                    pieceType == PieceType.ELEPHANT || 
                    pieceType == PieceType.PAWN)) {
                val majorAttacks = findAttackedUndefendedMajors(afterBoard, color, opponent)
                    .filter { it != move.to }  // 排除走子位置本身
                // 检查走子前不威胁这些子（是新出现的威胁）
                val attackedBefore = findAttackedUndefendedMajors(beforeBoard, color, opponent)
                val newTargets = majorAttacks.filter { pos -> attackedBefore.none { it == pos } }
                if (newTargets.size >= 1) {
                    val targetNames = newTargets.mapNotNull { afterBoard.getPiece(it)?.displayName() }
                    if (targetNames.isNotEmpty()) {
                        tactics.add("声东击西！以${(boardPiece ?: move.piece).displayName()}做诱饵，暗中已瞄准对方${targetNames.joinToString("和")}，对方防不胜防")
                    }
                }
            }
        }

        return if (tactics.isNotEmpty()) tactics.joinToString("。") + "。" else ""
    }

    // ── 后果预测 ──

    /**
     * 预测走完这步棋后可能导致什么后果
     *
     * 原则：谁走的糟糕就说谁，建议谁。聚焦当前走棋方的视角，
     * 不展开描述对手的具体应着（避免焦点错位）。
     *
     * 注意：如果游戏已结束（绝杀/困毙），不再预测后续
     */
    private fun predictConsequence(
        move: Move, beforeBoard: Board, afterBoard: Board,
        cpLoss: Int, bestMoveUci: String, pv: String
    ): String {
        // ★ 安全校验：用棋盘上的实际棋子
        val boardPiece = beforeBoard.getPiece(move.from) ?: move.piece
        val color = boardPiece.color
        val opponent = color.opponent()
        val colorName = if (color == PieceColor.RED) "红方" else "黑方"
        val opponentName = if (color == PieceColor.RED) "黑方" else "红方"
        // ★ 安全吃子检测
        val safeCaptured = getSafeCaptured(move, beforeBoard)
        val sb = StringBuilder()

        // 如果已经绝杀或困毙，不再预测后续
        if (afterBoard.isCheckmate(opponent)) {
            return ""
        }
        if (afterBoard.isStalemate(opponent)) {
            return ""
        }

        // 如果损失较大，简要说明后果（聚焦当前走棋方，不展开对手着法）
        if (cpLoss > 200) {
            sb.append("这步走完后，${colorName}的局面已经明显被动，后续需要谨慎防守。")
        } else if (cpLoss > 100) {
            sb.append("这步走完后，${colorName}略微陷入下风，需要注意对方的反击。")
        }

        // 如果是好棋，也说好果子
        if (cpLoss <= 20) {
            if (afterBoard.isInCheck(opponent)) {
                sb.append("这一步将军让对方不得不应将，${colorName}掌握了主动权。")
            } else if (safeCaptured != null) {
                sb.append("白吃一子，子力优势进一步扩大，${opponentName}压力很大。")
            }
        }

        return sb.toString()
    }

    // ── 心理点评 ──

    private fun addPsychologyNote(sb: StringBuilder, note: String) {
        if (note.isNotEmpty()) {
            sb.append(note)
        }
    }

    // ── 差距说明 ──

    private fun addGapExplanation(
        sb: StringBuilder, humanScore: Int, bestScore: Int,
        isRedToMove: Boolean, colorName: String, opponentName: String
    ) {
        val currentAdvantage = if ((humanScore > 0) == isRedToMove) "红方" else "黑方"
        val bestAdvantage = if ((bestScore > 0) == isRedToMove) "红方" else "黑方"

        if (currentAdvantage != bestAdvantage) {
            sb.append("这步棋让${colorName}从优势变为劣势，局面发生了逆转！")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 走棋目的分析
    // ═══════════════════════════════════════════════════════════

    private fun analyzeMovePurpose(move: Move, beforeBoard: Board, afterBoard: Board?): String {
        val after = afterBoard ?: Board().also {
            it.loadFromFen(beforeBoard.toFen())
            it.makeMove(move)
        }
        // ★ 安全校验：用棋盘上的实际棋子，不依赖 move.piece（可能因异步回调过期）
        val boardPiece = beforeBoard.getPiece(move.from)
        val piece = boardPiece ?: move.piece
        val color = piece.color
        val opponent = color.opponent()
        val sb = StringBuilder()
        Log.d("MoveExplainer", "analyzeMovePurpose: move=${move.toUci()}, pieceType=${piece.type}, boardPiece=$boardPiece, move.piece=${move.piece}")

        // ★ 安全吃子检测：从棋盘读取并验证颜色
        val safeCaptured = getSafeCaptured(move, beforeBoard)
        if (safeCaptured != null) {
            val capturedValue = pieceValue(safeCaptured.type)
            val pieceValue2 = pieceValue(piece.type)
            if (capturedValue > pieceValue2) {
                sb.append("用${piece.displayName()}换对方的${safeCaptured.displayName()}，赚了子力")
            } else if (capturedValue == pieceValue2) {
                sb.append("兑掉对方的${safeCaptured.displayName()}")
            } else {
                sb.append("吃掉对方的${safeCaptured.displayName()}")
            }
        }

        if (after.isInCheck(opponent)) {
            if (sb.isNotEmpty()) sb.append("，")
            sb.append("将军迫使对方应将")
        }

        val totalPieces = countTotalPieces(beforeBoard)
        if (totalPieces > 24 && safeCaptured == null) {
            if (piece.type == PieceType.KNIGHT) {
                if (sb.isNotEmpty()) sb.append("，")
                sb.append("跳出马，增强子力机动性")
            } else if (piece.type == PieceType.CANNON) {
                if (sb.isNotEmpty()) sb.append("，")
                if (move.to.col == 4) sb.append("炮镇中路，控制中央要道")
                else sb.append("调整炮位，寻找进攻机会")
            } else if (piece.type == PieceType.ROOK) {
                if (sb.isNotEmpty()) sb.append("，")
                sb.append("出动车，增强攻击力")
            } else if (piece.type == PieceType.PAWN) {
                if (sb.isNotEmpty()) sb.append("，")
                val isRed = color == PieceColor.RED
                val crossedRiver = if (isRed) move.to.row <= 4 else move.to.row >= 5
                if (crossedRiver) sb.append("兵过河，威胁对方阵地")
                else sb.append("挺兵，为后续进攻做准备")
            }
        }

        if (move.to.col == 4 && safeCaptured == null && piece.type != PieceType.KING) {
            if (sb.isEmpty()) sb.append("抢占中路要道，控制棋盘中央")
        }

        if (safeCaptured == null && isDefensiveMove(move, beforeBoard, after)) {
            if (sb.isNotEmpty()) sb.append("，")
            sb.append("加强防守，保护关键位置")
        }

        val attackedPiece = findAttackedPiece(move, after, opponent)
        if (attackedPiece != null && safeCaptured == null) {
            if (sb.isNotEmpty()) sb.append("，")
            sb.append("威胁对方的${attackedPiece.displayName()}")
        }

        if (safeCaptured == null && isExchangeOffer(move, after, opponent)) {
            if (sb.isNotEmpty()) sb.append("，")
            sb.append("准备兑子简化局面")
        }

        return sb.toString()
    }

    /**
     * 走棋目的简短版（用于战术说明）
     */
    private fun analyzeMovePurposeShort(move: Move, beforeBoard: Board, afterBoard: Board): String {
        // ★ 安全吃子检测
        if (getSafeCaptured(move, beforeBoard) != null) return "吃子"
        // ★ 安全校验：用棋盘上的实际棋子
        val boardPiece = beforeBoard.getPiece(move.from)
        val piece = boardPiece ?: move.piece
        return when (piece.type) {
            PieceType.KNIGHT -> "运马"
            PieceType.CANNON -> "调炮"
            PieceType.ROOK -> "出车"
            PieceType.PAWN -> "挺兵"
            PieceType.ADVISOR -> "撑士"
            PieceType.ELEPHANT -> "飞象"
            else -> "运子"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 安全吃子检测（防止"自己吃自己"的荒谬解说）
    // ═══════════════════════════════════════════════════════════

    /**
     * 安全获取被吃棋子：从走棋前的棋盘上读取目标位置的棋子，
     * 并验证其颜色与走棋方不同。如果颜色相同（不可能吃自己的子）则返回null。
     * 这解决了异步回调导致 move.captured 过期或棋盘状态不一致的问题。
     */
    private fun getSafeCaptured(move: Move, beforeBoard: Board): Piece? {
        // 优先从棋盘读取
        val boardPiece = beforeBoard.getPiece(move.to)
        if (boardPiece != null) {
            // ★ 关键校验：被吃的子必须是对方的子，绝不能是自己的子
            val moverPiece = beforeBoard.getPiece(move.from) ?: move.piece
            if (boardPiece.color != moverPiece.color) {
                return boardPiece
            }
            // 颜色相同 = 不可能吃自己的子，返回null
            return null
        }
        // 棋盘上没有子，也没有被吃
        return null
    }

    // ═══════════════════════════════════════════════════════════
    // 战术检测辅助方法
    // ═══════════════════════════════════════════════════════════

    private fun countCannonsOnFile(board: Board, color: PieceColor, col: Int): Int {
        var count = 0
        for (r in 0..9) {
            val piece = board.getPiece(Position(r, col))
            if (piece != null && piece.color == color && piece.type == PieceType.CANNON) count++
        }
        return count
    }

    private fun findKing(board: Board, color: PieceColor): Position? {
        for (r in 0..9) for (c in 0..8) {
            val piece = board.getPiece(Position(r, c))
            if (piece != null && piece.color == color && piece.type == PieceType.KING)
                return Position(r, c)
        }
        return null
    }

    private fun isTrappedOnBackRank(board: Board, color: PieceColor): Boolean {
        val king = findKing(board, color) ?: return false
        val palaceRows = if (color == PieceColor.RED) listOf(7, 8, 9) else listOf(0, 1, 2)
        val palaceCols = listOf(3, 4, 5)

        // 检查将/帅所有可走位置是否都被攻击
        val directions = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        for ((dr, dc) in directions) {
            val nr = king.row + dr
            val nc = king.col + dc
            if (nr in palaceRows && nc in palaceCols) {
                val target = board.getPiece(Position(nr, nc))
                if (target == null || target.color != color) {
                    // 检查走到这里是否安全
                    val testBoard = Board()
                    testBoard.loadFromFen(board.toFen())
                    testBoard.setPiece(king, null)
                    testBoard.setPiece(Position(nr, nc), Piece(PieceType.KING, color))
                    if (!testBoard.isInCheck(color)) return false
                }
            }
        }
        return true
    }

    private fun isInPalaceCorner(pos: Position, color: PieceColor): Boolean {
        val palaceRows = if (color == PieceColor.RED) listOf(7, 8, 9) else listOf(0, 1, 2)
        val palaceCols = listOf(3, 4, 5)
        val corners = listOf(
            Position(palaceRows[0], palaceCols[0]),
            Position(palaceRows[0], palaceCols[2]),
            Position(palaceRows[2], palaceCols[0]),
            Position(palaceRows[2], palaceCols[2])
        )
        return corners.any { it.row == pos.row && it.col == pos.col }
    }

    private fun findDiscoveredAttacks(move: Move, before: Board, after: Board): List<Pair<Position, Piece>> {
        val color = move.piece.color
        val opponent = color.opponent()
        val results = mutableListOf<Pair<Position, Piece>>()

        // 检查是否因为移动了某个棋子，暴露了后面的攻击
        val fromLine = mutableListOf<Position>()
        // 检查与原位置同列
        for (r in 0..9) {
            if (r != move.from.row) fromLine.add(Position(r, move.from.col))
        }
        // 检查与原位置同行
        for (c in 0..8) {
            if (c != move.from.col) fromLine.add(Position(move.from.row, c))
        }

        for (pos in fromLine) {
            val piece = before.getPiece(pos)
            if (piece != null && piece.color == color) {
                // 这个子现在可能攻击到对方棋子
                val attackedAfter = findAttackedByPiece(after, pos, piece, opponent)
                if (attackedAfter != null && attackedAfter.color == opponent) {
                    results.add(Pair(pos, attackedAfter))
                }
            }
        }
        return results
    }

    private fun findAttackedByPiece(board: Board, from: Position, piece: Piece, targetColor: PieceColor): Piece? {
        val legalMoves = board.getLegalMoves(piece.color)
        for (m in legalMoves) {
            if (m.from == from && m.captured != null && m.captured.color == targetColor) {
                return m.captured
            }
        }
        return null
    }

    private fun findDoubleAttacks(move: Move, after: Board, opponent: PieceColor): List<Piece> {
        val color = move.piece.color
        val legalMoves = after.getLegalMoves(color)
        val attacked = mutableSetOf<Piece>()
        for (m in legalMoves) {
            if (m.from == move.to && m.captured != null && m.captured.color == opponent) {
                attacked.add(m.captured)
            }
        }
        return attacked.toList()
    }

    /**
     * 查找走子后该子同时威胁的无根大子（车马炮）
     * 捉双要求：目标必须是无根的（没有己方子保护），且是大子（车马炮）
     */
    private fun findDoubleAttacksOnUndefendedMajors(move: Move, after: Board, opponent: PieceColor): List<Piece> {
        val color = move.piece.color
        val legalMoves = after.getLegalMoves(color)
        val attacked = mutableSetOf<Piece>()
        for (m in legalMoves) {
            if (m.from == move.to && m.captured != null && m.captured.color == opponent) {
                val capturedPiece = m.captured
                // 只算大子（车马炮）
                if (capturedPiece.type == PieceType.ROOK ||
                    capturedPiece.type == PieceType.KNIGHT ||
                    capturedPiece.type == PieceType.CANNON) {
                    // 检查是否无根（对方没有子能保护/反吃）
                    if (isUndefended(after, m.to, opponent)) {
                        attacked.add(capturedPiece)
                    }
                }
            }
        }
        return attacked.toList()
    }

    /**
     * 检查某位置上的对方子是否无根（没有己方子保护）
     * 即：如果该子被吃，对方没有任何子可以反吃吃子的那个子
     */
    private fun isUndefended(board: Board, pos: Position, defenderColor: PieceColor): Boolean {
        // 模拟：如果对方子被吃，防守方能否反吃？
        val piece = board.getPiece(pos) ?: return true
        val attacker = board.getLegalMoves(piece.color.opponent())
        // 找到能吃这个子的攻击方走法
        val attackerMove = attacker.find { it.to == pos } ?: return true
        // 模拟吃子后，防守方能否反吃
        val testBoard = Board()
        testBoard.loadFromFen(board.toFen())
        val attackerPiece = testBoard.getPiece(attackerMove.from) ?: return true
        testBoard.setPiece(pos, attackerPiece)
        testBoard.setPiece(attackerMove.from, null)
        testBoard.currentPlayer = defenderColor
        val defenderMoves = testBoard.getLegalMoves(defenderColor)
        // 防守方能否吃到刚吃子的那个攻击子？
        return defenderMoves.none { it.to == pos }
    }

    /**
     * 串捉/串打检测：车或炮在一条直线上同时威胁两个对方子
     * 串捉：车在直线上，前面一个子被直接攻击，后面的子也被威胁（前面的子移开后）
     * 串打：炮通过炮架同时威胁一条线上的两个子
     * @return 两个被威胁子的名称，如"车和马"，null表示无串捉/串打
     */
    private fun findSkewerAttack(move: Move, before: Board, after: Board, opponent: PieceColor): String? {
        val color = move.piece.color
        val piece = move.piece

        if (piece.type != PieceType.ROOK && piece.type != PieceType.CANNON) return null

        val from = move.to  // 走子后的位置

        // 检查四个方向（上下左右）
        val dirs = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        for ((dr, dc) in dirs) {
            val targets = mutableListOf<Piece>()
            if (piece.type == PieceType.ROOK) {
                // 车：沿直线找，遇到第一个对方子记录，继续找第二个
                var r = from.row + dr
                var c = from.col + dc
                var foundFirst = false
                while (r in 0..9 && c in 0..8) {
                    val p = after.getPiece(Position(r, c))
                    if (p != null) {
                        if (p.color == opponent) {
                            targets.add(p)
                            if (targets.size >= 2) break
                            foundFirst = true
                        } else {
                            break  // 己方子挡住
                        }
                    }
                    r += dr
                    c += dc
                }
            } else {
                // 炮：找炮架，然后找炮架后面的对方子
                var r = from.row + dr
                var c = from.col + dc
                var screenPos: Position? = null
                // 找炮架
                while (r in 0..9 && c in 0..8) {
                    val p = after.getPiece(Position(r, c))
                    if (p != null) {
                        screenPos = Position(r, c)
                        break
                    }
                    r += dr
                    c += dc
                }
                if (screenPos != null) {
                    // 找炮架后面的对方子
                    r = screenPos.row + dr
                    c = screenPos.col + dc
                    while (r in 0..9 && c in 0..8) {
                        val p = after.getPiece(Position(r, c))
                        if (p != null) {
                            if (p.color == opponent) {
                                targets.add(p)
                                if (targets.size >= 2) break
                            } else {
                                break
                            }
                        }
                        r += dr
                        c += dc
                    }
                }
            }

            if (targets.size >= 2) {
                return targets.take(2).joinToString("和") { it.displayName() }
            }
        }
        return null
    }

    /**
     * 查找某方能攻击的对方无根大子位置（车马炮，且无根）
     */
    private fun findAttackedUndefendedMajors(board: Board, attackerColor: PieceColor, defenderColor: PieceColor): List<Position> {
        val legalMoves = board.getLegalMoves(attackerColor)
        val attacked = mutableSetOf<Position>()
        for (m in legalMoves) {
            if (m.captured != null && m.captured.color == defenderColor) {
                val capturedPiece = m.captured
                if (capturedPiece.type == PieceType.ROOK ||
                    capturedPiece.type == PieceType.KNIGHT ||
                    capturedPiece.type == PieceType.CANNON) {
                    if (isUndefended(board, m.to, defenderColor)) {
                        attacked.add(m.to)
                    }
                }
            }
        }
        return attacked.toList()
    }

    /**
     * 查找某个特定位置上的子能攻击的对方无根大子位置
     * 用于声东击西检测：判断走子本身是否也在攻击
     */
    private fun findAttackedUndefendedMajorsByPiece(board: Board, fromPos: Position, attackerColor: PieceColor, defenderColor: PieceColor): List<Position> {
        val legalMoves = board.getLegalMoves(attackerColor)
        val attacked = mutableSetOf<Position>()
        for (m in legalMoves) {
            if (m.from == fromPos && m.captured != null && m.captured.color == defenderColor) {
                val capturedPiece = m.captured
                if (capturedPiece.type == PieceType.ROOK ||
                    capturedPiece.type == PieceType.KNIGHT ||
                    capturedPiece.type == PieceType.CANNON) {
                    if (isUndefended(board, m.to, defenderColor)) {
                        attacked.add(m.to)
                    }
                }
            }
        }
        return attacked.toList()
    }

    private fun countPiecesOfType(board: Board, color: PieceColor, type: PieceType): Int {
        var count = 0
        for (r in 0..9) for (c in 0..8) {
            val piece = board.getPiece(Position(r, c))
            if (piece != null && piece.color == color && piece.type == type) count++
        }
        return count
    }

    private fun findOtherRook(board: Board, color: PieceColor, excludePos: Position): Position? {
        for (r in 0..9) for (c in 0..8) {
            if (r == excludePos.row && c == excludePos.col) continue
            val piece = board.getPiece(Position(r, c))
            if (piece != null && piece.color == color && piece.type == PieceType.ROOK)
                return Position(r, c)
        }
        return null
    }

    private fun hasCannonBehindKnight(board: Board, knightPos: Position, color: PieceColor, opponent: PieceColor): Boolean {
        val opponentKing = findKing(board, opponent) ?: return false
        // 马后炮：马将军时，己方炮在马之后，且炮→马→将帅在同一直线上
        // 即炮和马在同一直线（行或列），且马和将也在同一直线
        for (r in 0..9) for (c in 0..8) {
            val piece = board.getPiece(Position(r, c))
            if (piece == null || piece.color != color || piece.type != PieceType.CANNON) continue
            val cannonPos = Position(r, c)
            // 检查炮-马-将是否共线
            val sameRow = cannonPos.row == knightPos.row && knightPos.row == opponentKing.row
            val sameCol = cannonPos.col == knightPos.col && knightPos.col == opponentKing.col
            if (!sameRow && !sameCol) continue
            // 检查炮在马后面（马在炮和将之间）
            if (sameRow) {
                val minC = minOf(cannonPos.col, opponentKing.col)
                val maxC = maxOf(cannonPos.col, opponentKing.col)
                if (knightPos.col > minC && knightPos.col < maxC) return true
            } else {
                val minR = minOf(cannonPos.row, opponentKing.row)
                val maxR = maxOf(cannonPos.row, opponentKing.row)
                if (knightPos.row > minR && knightPos.row < maxR) return true
            }
        }
        return false
    }

    private fun hasHeavenEarthCannons(board: Board, color: PieceColor): Boolean {
        var hasMidCannon = false
        var hasBottomCannon = false
        val bottomRows = if (color == PieceColor.RED) listOf(0, 1, 2) else listOf(7, 8, 9)

        for (r in 0..9) for (c in 0..8) {
            val piece = board.getPiece(Position(r, c))
            if (piece != null && piece.color == color && piece.type == PieceType.CANNON) {
                if (c == 4) hasMidCannon = true
                if (r in bottomRows) hasBottomCannon = true
            }
        }
        return hasMidCannon && hasBottomCannon
    }

    private fun findAttackedPieces(board: Board, color: PieceColor, opponent: PieceColor): List<Position> {
        val legalMoves = board.getLegalMoves(color)
        val attacked = mutableListOf<Position>()
        for (m in legalMoves) {
            if (m.captured != null && m.captured.color == opponent) {
                attacked.add(m.to)
            }
        }
        return attacked
    }

    // ═══════════════════════════════════════════════════════════
    // 子力分析
    // ═══════════════════════════════════════════════════════════

    private fun analyzeMaterial(board: Board): String {
        var redValue = 0
        var blackValue = 0
        for (r in 0..9) for (c in 0..8) {
            val piece = board.getPiece(Position(r, c)) ?: continue
            val value = pieceValue(piece.type)
            if (piece.color == PieceColor.RED) redValue += value
            else blackValue += value
        }
        if (redValue == blackValue) return "双方子力均等"
        val diff = kotlin.math.abs(redValue - blackValue)
        val diffStr = when {
            diff >= 1000 -> "约${diff / 1000}车"
            diff >= 500 -> "约${diff / 500}马炮"
            else -> "${diff / 100}兵"
        }
        return if (redValue > blackValue) "红方多${diffStr}子力"
        else "黑方多${diffStr}子力"
    }

    /**
     * 返回子力差值（正数=红方多，负数=黑方多）
     */
    fun analyzeMaterialDiff(board: Board): Int {
        var diff = 0
        for (r in 0..9) for (c in 0..8) {
            val piece = board.getPiece(Position(r, c)) ?: continue
            val value = pieceValue(piece.type)
            if (piece.color == PieceColor.RED) diff += value
            else diff -= value
        }
        return diff
    }

    private fun pieceValue(type: PieceType): Int {
        return when (type) {
            PieceType.ROOK -> 1000
            PieceType.KNIGHT -> 500
            PieceType.CANNON -> 500
            PieceType.ADVISOR -> 200
            PieceType.ELEPHANT -> 200
            PieceType.PAWN -> 100
            PieceType.KING -> 0
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助判断
    // ═══════════════════════════════════════════════════════════

    private fun countTotalPieces(board: Board): Int {
        var count = 0
        for (r in 0..9) for (c in 0..8) {
            if (board.getPiece(Position(r, c)) != null) count++
        }
        return count
    }

    private fun isDefensiveMove(move: Move, before: Board, after: Board): Boolean {
        val color = move.piece.color
        val beforeThreats = countThreatenedPieces(before, color)
        val afterThreats = countThreatenedPieces(after, color)
        return afterThreats < beforeThreats
    }

    private fun countThreatenedPieces(board: Board, color: PieceColor): Int {
        var count = 0
        val opponent = color.opponent()
        val opponentMoves = board.getLegalMoves(opponent)
        val attackedPositions = mutableSetOf<Position>()
        for (m in opponentMoves) {
            if (m.captured != null) attackedPositions.add(m.to)
        }
        for (pos in attackedPositions) {
            val piece = board.getPiece(pos)
            if (piece != null && piece.color == color) count++
        }
        return count
    }

    private fun findAttackedPiece(move: Move, after: Board, opponent: PieceColor): Piece? {
        val legalMoves = after.getLegalMoves(move.piece.color)
        for (m in legalMoves) {
            if (m.from == move.to && m.captured != null) {
                return m.captured
            }
        }
        return null
    }

    private fun isExchangeOffer(move: Move, after: Board, opponent: PieceColor): Boolean {
        val opponentMoves = after.getLegalMoves(opponent)
        for (m in opponentMoves) {
            if (m.to == move.to) return true
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════
    // 开局识别
    // ═══════════════════════════════════════════════════════════

    fun identifyOpening(moveHistory: List<String>): String {
        if (moveHistory.isEmpty()) return ""
        val first = moveHistory.firstOrNull() ?: return ""
        return when (first) {
            "b2e2" -> "中炮局"
            "b2b5", "b2a5" -> "过宫炮局"
            "h2e2" -> "仕角炮局"
            "b7e7", "h7e7" -> "卒底炮"
            "i0h2", "a0b2" -> "飞相局"
            "b0c2", "b0a2" -> "起马局"
            "i1i0", "a1a0" -> "出车局"
            else -> ""
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 终局总结
    // ═══════════════════════════════════════════════════════════

    /**
     * 生成终局总结
     * @param winnerName 胜方名称（"红方"/"黑方"）
     * @param loserName 负方名称
     * @param totalSteps 总步数
     * @param loserSteps 负方走的步数
     * @param isCheckmate 是否被将死
     * @param mistakeSteps 失误步列表（步号, 走法记号, 质量等级, 损失分值）
     * @return 终局总结文字
     */
    fun generateGameOverSummary(
        winnerName: String,
        loserName: String,
        totalSteps: Int,
        loserSteps: Int,
        isCheckmate: Boolean,
        mistakeSteps: List<Triple<Int, String, Pair<MoveQuality, Int>>>
    ): String {
        val sb = StringBuilder()

        // 1. 棋局结束声明
        if (isCheckmate) {
            sb.append("${winnerName}已将死${loserName}，棋局结束。")
        } else {
            sb.append("棋局结束，${winnerName}获胜。")
        }

        // 2. 对负方的评价
        sb.append("${loserName}总共支撑了${totalSteps}步。")

        // 3. 根据步数评价
        when {
            totalSteps <= 15 -> sb.append("对局时间很短，败局来得很迅速。")
            totalSteps <= 30 -> sb.append("对局时间适中，双方有过一番较量。")
            totalSteps <= 50 -> sb.append("对局持续时间较长，${loserName}进行了较顽强的抵抗。")
            else -> sb.append("这是一场持久战，${loserName}苦撑了很长时间。")
        }

        // 4. 最大错误分析
        val bigMistakes = mistakeSteps.filter { 
            it.third.first == MoveQuality.BLUNDER || it.third.first == MoveQuality.MISTAKE 
        }.sortedByDescending { it.third.second }

        if (bigMistakes.isNotEmpty()) {
            sb.append("${loserName}的最大失误出现在：")
            val topMistakes = bigMistakes.take(3)
            for ((step, notation, quality_loss) in topMistakes) {
                sb.append("第${step}步${notation}（${quality_loss.first.label}，损失${quality_loss.second}分）")
                if (topMistakes.indexOf(Triple(step, notation, quality_loss)) < topMistakes.size - 1) {
                    sb.append("、")
                }
            }
            sb.append("。")

            // 5. 总结败因
            if (bigMistakes.size >= 3) {
                sb.append("整体来看，${loserName}失误较多，败因主要在于判断不够准确。")
            } else if (bigMistakes.size == 1 && bigMistakes[0].third.second > 300) {
                sb.append("这盘棋的关键转折就在这一步，一着不慎满盘皆输。")
            } else {
                sb.append("整体表现尚可，但关键时刻的失误决定了胜负。")
            }
        } else {
            // 没有明显失误
            val questionableSteps = mistakeSteps.filter { it.third.first == MoveQuality.QUESTIONABLE }
            if (questionableSteps.isNotEmpty()) {
                sb.append("${loserName}虽无大失误，但几步小疑问累积起来，也逐渐落入下风。")
            } else {
                sb.append("${loserName}整体走得不错，但对手更强一筹。")
            }
        }

        return sb.toString()
    }
}