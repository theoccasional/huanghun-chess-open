package com.pikafish.chess.engine

import android.util.Log
import com.pikafish.chess.model.*
import kotlin.math.abs

/**
 * ★ 全新解说引擎 — 推倒重来，简洁、正确、逻辑通顺
 *
 * 核心设计原则：
 *  1. 所有身份数据（颜色、棋谱、步数）由调用方在走棋时捕获并传入，
 *     绝不从棋盘状态异步反推 —— 根除"说错走棋方"的根源。
 *  2. 一回合说一件事：谁走了什么棋，走得好不好，当前局面如何。
 *  3. 聚焦当前走棋方：谁走的糟糕就建议谁，不张冠李戴。
 *  4. 逻辑通顺：先因后果，不自相矛盾。
 *  5. 不搞复杂战术检测（串捉、声东击西等）—— 这些容易误报，
 *     专注于走棋质量和局面评估，做到准确无误。
 */
object CommentaryEngine {

    private const val TAG = "CommentaryEngine"

    // ══════════════════════════════════════════════
    //  走棋质量分级
    // ══════════════════════════════════════════════

    enum class Quality(val label: String, val emoji: String) {
        BRILLIANT("妙手", "✨"),
        GOOD("好棋", "👍"),
        STANDARD("正着", "✅"),
        PLAYABLE("可走", "🤔"),
        QUESTIONABLE("疑问着", "⚠️"),
        MISTAKE("失误", "❌"),
        BLUNDER("败着", "💀")
    }

    fun classifyQuality(cpLoss: Int): Quality = when {
        cpLoss <= 0 -> Quality.BRILLIANT
        cpLoss <= 20 -> Quality.GOOD
        cpLoss <= 50 -> Quality.STANDARD
        cpLoss <= 100 -> Quality.PLAYABLE
        cpLoss <= 200 -> Quality.QUESTIONABLE
        cpLoss <= 400 -> Quality.MISTAKE
        else -> Quality.BLUNDER
    }

    // ══════════════════════════════════════════════
    //  ★★ 一致性铁律：绝不"推荐同一手、又骂同一手"
    // ══════════════════════════════════════════════

    /**
     * 这一手是否就是引擎推荐的那一手（UCI 归一化后比较）。
     *
     * 为什么必须要有这道闸：引擎两次搜（搜本局面得"推荐手"、搜走完局面得"本手分"）
     * 在连杀/边界局面上极易给出互相打架的分 —— 典型如人脑走出了绝杀，
     * 反倒被告知"这步是败着、损失了 59998 分"，紧接着又"强烈建议走"这一手。
     * 逻辑上，既然推荐的就是你走的这一步，就绝不存在"亏"这回事，中间那些
     * 自相矛盾的话术必须整段剔除。
     */
    fun sameAsRecommended(moveUci: String, bestMoveUci: String): Boolean =
        moveUci.isNotBlank() && bestMoveUci.isNotBlank() &&
            moveUci.trim().equals(bestMoveUci.trim(), ignoreCase = true)

    /**
     * 亏分的人话：连杀/必胜区间不报裸数字。
     * （29999 封顶的连杀分一旦相减就是"损失了59998分"这种又吓人又没意义的数，
     *   这里直接改说"形势从【杀N步】直落【被杀N步】"，说人话。）
     */
    fun lossPhrase(bestScore: Int, humanScore: Int): String {
        val b = JniEngine.mateText(bestScore)
        val h = JniEngine.mateText(humanScore)
        if (b != null || h != null) {
            return when {
                b != null && h != null -> "形势从【$b】直落【$h】"
                b != null -> "形势本该【$b】，却被走成了被动挨打"
                else -> "形势急转直下，眼看要【$h】"
            }
        }
        val loss = bestScore - humanScore
        // ★ 兜底铁律：任何情况下都不把"几万分"这种荒唐裸分写进人话（两搜打架的残余），
        //   超过连杀门槛就直接说"急转直下"，与【杀N步】体系自洽。
        return when {
            loss <= 0 -> "分差略有进出"
            loss >= JniEngine.MATE_THRESHOLD -> "形势急转直下，胜负已无悬念"
            else -> "损失了${loss}分"
        }
    }

    // ══════════════════════════════════════════════
    //  UCI 走法转中文棋谱
    // ══════════════════════════════════════════════

    private val redNumbers = arrayOf("九", "八", "七", "六", "五", "四", "三", "二", "一")
    private val blackNumbers = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
    private val redStepNumbers = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")

    fun uciToChineseNotation(uciMove: String, board: Board): String {
        if (uciMove.length < 4) {
            Log.w(TAG, "★ uciToChineseNotation: UCI走法过短 '$uciMove' (len=${uciMove.length})")
            return ""   // ★ 绝不把裸 UCI / 坐标混入中文棋谱（杜绝 h2e2 烂尾）
        }
        val from = Position.fromUci(uciMove.substring(0, 2))
        val to = Position.fromUci(uciMove.substring(2, 4))
        // ★ 边界检查：防止越界导致异常
        if (!from.isValid() || !to.isValid()) {
            Log.w(TAG, "★ uciToChineseNotation: UCI位置越界 uci='$uciMove' from=$from to=$to")
            return ""
        }
        val piece = board.getPiece(from)
        if (piece == null) {
            Log.w(TAG, "★ uciToChineseNotation: 棋盘上找不到棋子 uci='$uciMove' from=$from fen='${board.toFen()}'")
            return ""   // ★ 悬空/非法UCI一律不泄入棋谱
        }
        val captured = board.getPiece(to)
        val notation = moveToChineseNotation(Move(from, to, piece, captured), board)
        // ★ 结果验证：棋谱至少4个字符（棋子名+列号+动作+目标）
        if (notation.length < 3) {
            Log.e(TAG, "★★★ uciToChineseNotation: 生成的棋谱异常短! notation='$notation' uci='$uciMove' piece=${piece.displayName()} from=$from to=$to fen='${board.toFen()}'")
            return ""   // ★ 宁可空缺也绝不让残缺/裸串进棋谱
        }
        return notation
    }

    fun moveToChineseNotation(move: Move, board: Board): String {
        try {
            val piece = move.piece
            val isRed = piece.color == PieceColor.RED
            val pieceName = piece.displayName()
            val colLabels = if (isRed) redNumbers else blackNumbers
            val stepLabels = if (isRed) redStepNumbers else (1..9).map { it.toString() }.toTypedArray()

            // ★ 边界检查：列号必须在 0..8 范围内
            if (move.from.col !in 0..8 || move.to.col !in 0..8) {
                Log.e(TAG, "★★★ moveToChineseNotation: 列号越界 from.col=${move.from.col} to.col=${move.to.col} piece=$pieceName")
                return move.toUci()
            }

            val fromColLabel = colLabels[move.from.col]
            // ★★ 前/后 区分：同列 ≥2 个同兵种同色本方棋子时，用"前/后"定位并放到子名之前
            //  （标准棋谱：靠近敌阵者为"前"，靠近己方底线者为"后"；
            //    只有兵/卒(每方5颗)才可能用到"中"，其余任何兵种都只分前/后，永远没有"中"）
            val frontLabel = sameFileFrontLabel(board, piece, move.from.col, move.from.row)

            if (move.from.col == move.to.col) {
                    // 同列走法：进/退
                    val steps = abs(move.from.row - move.to.row)
                    val isForward = if (isRed) move.to.row < move.from.row else move.to.row > move.from.row
                    val action = if (isForward) "进" else "退"
                    val stepLabel = if (steps in 1..9) stepLabels[steps - 1] else steps.toString()
                    return if (frontLabel != null) "$frontLabel$pieceName$action$stepLabel" else "$pieceName$fromColLabel$action$stepLabel"
                } else if (move.from.row == move.to.row) {
                    // 同行走法：平
                    return if (frontLabel != null) "$frontLabel${pieceName}平${colLabels[move.to.col]}" else "${pieceName}${fromColLabel}平${colLabels[move.to.col]}"
                } else {
                    // 斜向走法（马、相、士）：进/退+目标列
                    val isForward = if (isRed) move.to.row < move.from.row else move.to.row > move.from.row
                    val action = if (isForward) "进" else "退"
                    return if (frontLabel != null) "$frontLabel$pieceName$action${colLabels[move.to.col]}" else "$pieceName$fromColLabel$action${colLabels[move.to.col]}"
                }
        } catch (e: Exception) {
            Log.e(TAG, "★★★ moveToChineseNotation: 异常 ${e.message} move=${move.toUci()}", e)
            return move.toUci()
        }
    }

    /**
     * ★★ 同列叠子前/中/后区分（标准记谱）：
     *   当 fromCol 这条纵线上存在 ≥2 个同兵种同色的棋子时，
     *   按"愈近敌阵愈前"排序，返回本子应带的标签（前/中/后…）；仅一个则返回 null。
     */
    private fun sameFileFrontLabel(board: Board, piece: Piece, fromCol: Int, fromRow: Int): String? {
        val rows = mutableListOf<Int>()
        for (r in 0..9) {
            val p = board.getPiece(Position(r, fromCol))
            if (p != null && p.color == piece.color && p.type == piece.type) rows.add(r)
        }
        if (rows.size < 2) return null
        // 越接近敌阵越靠前：红方敌阵在上(行小)在前；黑方敌阵在下(行大)在前
        // ★ 只数本走棋方(自己颜色)同列同型子，对方棋子坐标系/记谱法不同，一律不掺入。
        val ordered = if (piece.color == PieceColor.RED) rows.sorted() else rows.sortedDescending()
        val idx = ordered.indexOf(fromRow)
        if (idx < 0) return null
        return sameFileSiblingLabels(piece.type, rows.size).getOrNull(idx)
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

    // ══════════════════════════════════════════════
    //  评分转文字
    // ══════════════════════════════════════════════

    /**
     * ★ 思考细节主变（PV）UCI走法串 → 中文棋谱串
     * 例："e2e4 e7e5" → "炮二平五 马8进7"
     * @param pvUci 以空格分隔的 UCI 走法串
     * @param startFen 该搜索的起始局面
     */
    fun pvToChinese(pvUci: String, startFen: String): String {
        if (pvUci.isBlank()) return ""
        return try {
            val b = Board().apply { loadFromFen(startFen) }
            val tokens = pvUci.trim().split(Regex("\\s+"))
            if (tokens.isEmpty()) return ""
            val sb = mutableListOf<String>()
            for (m in tokens) {
                // ★ 只接受规范 4 字符 UCI 走法；跳过 "(none)"、空串、多余字段，
                //   绝不让坐标/垃圾 token 混入中文棋谱（用户截图"坐标中文混杂"根因之一）。
                if (m.length != 4) continue
                val nm = Move.fromUci(m, b) ?: continue
                val notation = moveToChineseNotation(nm, b)
                // ★ 最终防线：转换仍失败就跳过该步，绝不以裸坐标形式泄漏到界面
                if (notation.isEmpty() || notation == m) continue
                sb.add(notation)
                b.makeMoveUnchecked(nm)
            }
            if (sb.isEmpty()) "" else sb.joinToString(" ")
        } catch (e: Exception) {
            Log.e(TAG, "pvToChinese error: ${e.message}")
            ""
        }
    }

    /**
     * 将引擎评分转为自然语言描述
     * @param score 引擎评分（从当前走棋方视角，正数=当前方优势）
     * @param isRedToMove 当前是否红方走棋
     */
    fun scoreToText(score: Int, isRedToMove: Boolean): String {
        // 杀棋评分：新编码分(29999封顶)，用 matePliesOf 反推成杀步数
        if (JniEngine.isMateScore(score)) {
            val plies = JniEngine.matePliesOf(score) ?: 1
            val side = if ((score > 0) == isRedToMove) "红方" else "黑方"
            return if (plies <= 3) "${side}即将杀棋，${plies}步内绝杀！"
                   else "${side}胜势已定，${plies}步内杀棋"
        }

        val absScore = abs(score)
        // 评分方向：score > 0 且当前红方走 → 红方优势
        //           score > 0 且当前黑方走 → 黑方优势
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

    // ══════════════════════════════════════════════
    //  AI 走棋解说
    // ══════════════════════════════════════════════

    /**
     * 生成AI走棋解说
     *
     * ★ 所本身份数据由调用方传入，不从棋盘异步反推
     *
     * @param moveUci AI走法UCI
     * @param colorName 走棋方名称（"红方"/"黑方"）— 调用方在走棋时确定
     * @param notation 中文棋谱（如"炮二平五"）— 调用方在走棋时确定
     * @param score 引擎评分
     * @param depth 搜索深度
     * @param pv 主要变化
     * @param boardBefore 走棋前棋盘快照
     * @return 解说文本
     */
    fun explainAiMove(
        moveUci: String,
        colorName: String,
        notation: String,
        score: Int,
        depth: Int,
        pv: String,
        boardBefore: Board
    ): String {
        val opponentName = if (colorName == "红方") "黑方" else "红方"
        val move = Move.fromUci(moveUci, boardBefore)
        if (move == null) {
            Log.w(TAG, "explainAiMove: 无法解析走法 $moveUci")
            return "${colorName}走了${notation}（深度${depth}层）"
        }

        // ★ 从棋盘快照安全读取棋子
        val piece = boardBefore.getPiece(move.from) ?: move.piece
        val isRedToMove = colorName == "红方"
        val captured = getSafeCaptured(move, boardBefore)

        val sb = StringBuilder()
        sb.append("${colorName}${notation}")

        // 1. 吃子描述
        if (captured != null) {
            sb.append("，吃掉${opponentName}的${captured.displayName()}")
            val capturedVal = pieceValue(captured.type)
            val pieceVal = pieceValue(piece.type)
            if (capturedVal > pieceVal && score > 0) {
                sb.append("，得子占优")
            }
        }

        // 2. 将军/绝杀检测
        val afterBoard = Board().apply {
            loadFromFen(boardBefore.toFen())
            makeMove(move)
        }
        val opponentColor = piece.color.opponent()
        val isCheck = afterBoard.isInCheck(opponentColor)
        if (isCheck) {
            if (afterBoard.isCheckmate(opponentColor)) {
                sb.append("，绝杀！棋局结束，${colorName}获胜。")
                return sb.toString()
            }
            sb.append("，将军！")
        }

        // 3. 走棋目的（简化版，不做复杂战术检测）
        val purpose = analyzePurpose(move, piece, boardBefore, afterBoard, captured)
        if (purpose.isNotEmpty()) {
            sb.append("。$purpose")
        }

        // 4. 局面评估
        val assessment = scoreToText(score, isRedToMove)
        sb.append("。当前局面：$assessment")

        sb.append("（深度${depth}层）")
        return sb.toString()
    }

    // ══════════════════════════════════════════════
    //  人脑走棋解说
    // ══════════════════════════════════════════════

    /**
     * 生成人脑走棋解说
     *
     * ★ 所本身份数据由调用方传入，不从棋盘异步反推
     * ★ 聚焦当前走棋方：谁走的糟糕就建议谁
     *
     * @param moveUci 人脑走法UCI
     * @param colorName 走棋方名称（"红方"/"黑方"）— 调用方在走棋时确定
     * @param notation 中文棋谱 — 调用方在走棋时确定
     * @param bestMoveUci 引擎推荐最佳走法UCI
     * @param humanScore 人脑走法评分
     * @param bestScore 最佳走法评分
     * @param depth 搜索深度
     * @param pv 主要变化
     * @param boardBefore 走棋前棋盘快照
     * @return 解说文本
     */
    /**
     * ★ 皮卡鱼嘴替：把引擎的"闷葫芦"吐出来的人话。
     *  引擎只会给一堆冰冷数字（推荐走法/评分/主变），这里把它翻译成一句人话，
     *  告诉玩家"皮卡鱼原来是这么想的"。绝不改引擎，只解读它的思考产物。
     * @param bestMoveUci 引擎推荐走法
     * @param playedMoveUci 人脑实际走的这一手（用来对账：推荐的与走的是同一手，就不能再骂它）
     * @param pv 引擎在当前局面算出的主变（UCI 走法串，从 boardBefore 这个局面开始）
     */
    fun pikafishThought(
        colorName: String,
        bestMoveUci: String,
        playedMoveUci: String,
        bestScore: Int,
        humanScore: Int,
        depth: Int,
        pv: String,
        boardBefore: Board,
        // ★ 当前引擎的称呼：内置=皮卡鱼；换了外置引擎就跟着换（认识的说中文，不认识的说英文原名）
        engineName: String = EngineIdentity.shortName
    ): String {
        val isRedToMove = colorName == "红方"
        val bestNotation = try {
            uciToChineseNotation(bestMoveUci, boardBefore)
        } catch (e: Exception) { "" }
        val playedNotation = try {
            uciToChineseNotation(playedMoveUci, boardBefore)
        } catch (e: Exception) { "" }
        val cpLoss = bestScore - humanScore
        val scorePhrase = scoreToText(bestScore, isRedToMove)
        // 主变翻译成人话（只取前几步，不可太长）
        val pvZh = pvToChinese(pv, boardBefore.toFen())
        val pvPart = pvZh.split(" ").filter { it.isNotBlank() }.take(4).joinToString(" ")
        val linePart = if (pvPart.isNotEmpty())
            "它盘算的接下去的走法是【$pvPart】。" else ""

        // ★★ 一致性铁律：它推荐的正是你走的这一手时，绝不摇头说"大漏勺"——
        //   否则就成"推荐兵六平五，又骂兵六平五"，前后打架（UCI 或中文棋谱任一相同即算同一手）。
        val sameMove = sameAsRecommended(playedMoveUci, bestMoveUci) ||
            (bestNotation.isNotBlank() && playedNotation.isNotBlank() && bestNotation == playedNotation)
        val verdict = if (sameMove) {
            "这正是它自己挑中的那一步，它没意见。"
        } else when {
            cpLoss <= 20 -> "这步正中它的下怀，挑不出大毛病。"
            cpLoss <= 100 -> "它觉得这步有点折子货，回头多半要还。"
            cpLoss <= 400 -> "它反复看了看，认准这是步问题着，白丢了些便宜。"
            else -> "它连连摇头，说这是步大漏勺，损得不轻。"
        }
        val recPart = if (bestNotation.isNotBlank()) "它推荐$bestNotation，" else ""
        val speaker = engineName.ifBlank { "皮卡鱼" }
        return "${speaker}心里原来是这么想的：${recPart}可成$scorePhrase（深度${depth}层）。$linePart$verdict"
    }

    fun explainHumanMove(
        moveUci: String,
        colorName: String,
        notation: String,
        bestMoveUci: String,
        humanScore: Int,
        bestScore: Int,
        depth: Int,
        pv: String,
        boardBefore: Board
    ): String {
        val opponentName = if (colorName == "红方") "黑方" else "红方"
        val move = Move.fromUci(moveUci, boardBefore)
        if (move != null) {
            Log.d(TAG, "explainHumanMove: uci=$moveUci, colorName=$colorName, notation=$notation")
        } else {
            Log.w(TAG, "explainHumanMove: 无法解析走法 $moveUci")
            return "${colorName}走了${notation}，${Quality.STANDARD.emoji}${Quality.STANDARD.label}。当前局面：${scoreToText(humanScore, colorName == "红方")}（深度${depth}层）"
        }

        // ★ 从棋盘快照安全读取棋子
        val piece = boardBefore.getPiece(move.from) ?: move.piece
        val isRedToMove = colorName == "红方"
        val cpLoss = bestScore - humanScore
        val captured = getSafeCaptured(move, boardBefore)
        val bestNotation = uciToChineseNotation(bestMoveUci, boardBefore)
        // ★★ 一致性闸门（双保险）：UCI 逐字相同，或"引擎推荐手的中文棋谱"与"实走棋谱"完全一致，
        //   都判定为"推荐的正是你走的这一手" → 一律判妙手，绝不出现"推荐同一手、又骂同一手亏了多少分"。
        val sameAsBest = sameAsRecommended(moveUci, bestMoveUci) ||
            (bestNotation.isNotBlank() && notation.isNotBlank() && bestNotation == notation)
        val quality = if (sameAsBest) Quality.BRILLIANT else classifyQuality(cpLoss)

        // ★★ 先算"将军/绝杀"这一客观事实，再谈质量评价 —— 顺序反过来才能根治"前言不搭后语"。
        //    绝杀时棋局已结束，只保留"吃子 → 将军 → 绝杀"这条事实链，中间一切评语整段剔除。
        val afterBoard = Board().apply {
            loadFromFen(boardBefore.toFen())
            makeMove(move)
        }
        val opponentColor = piece.color.opponent()
        val isCheck = afterBoard.isInCheck(opponentColor)
        val isMate = isCheck && afterBoard.isCheckmate(opponentColor)

        val sb = StringBuilder()
        sb.append("${colorName}走了${notation}")

        // 1. 吃子描述（客观动作，永远正确）
        if (captured != null) {
            sb.append("，吃了${opponentName}的${captured.displayName()}")
        }

        // ★★ 绝杀：这步已成杀 → 只报事实链，绝不掺"亏多少分/败着/建议改走"等自相矛盾的中间话术。
        if (isMate) {
            sb.append("，这步将军了${opponentName}。而且是绝杀！")
            return sb.toString()
        }

        sb.append("，${quality.emoji}${quality.label}")

        // 2. 走棋质量评价 — 聚焦当前走棋方，建议走棋方应该怎么走
        when (quality) {
            Quality.BRILLIANT -> {
                if (sameAsBest) {
                    sb.append("！这步正合引擎本意，走的就是它推荐的那一手。")
                } else {
                    sb.append("！这步棋和引擎推荐完全一致，棋感极佳，对局面的理解非常精准。")
                }
            }
            Quality.GOOD -> {
                sb.append("。走得不错，和最佳走法很接近。")
                if (moveUci != bestMoveUci) {
                    sb.append("引擎更推荐${bestNotation}。")
                }
            }
            Quality.STANDARD -> {
                sb.append("。这步可以接受，但不是最优选择。")
                sb.append("引擎推荐${bestNotation}。")
            }
            Quality.PLAYABLE -> {
                sb.append("。这步虽然能走，但略有损失。")
                sb.append("更好的选择是${bestNotation}。")
            }
            Quality.QUESTIONABLE -> {
                sb.append("。这步有些疑问，${lossPhrase(bestScore, humanScore)}。")
                sb.append("${colorName}应该走${bestNotation}。")
            }
            Quality.MISTAKE -> {
                sb.append("！这步失误了，白白${lossPhrase(bestScore, humanScore)}。")
                sb.append("${colorName}应该走${bestNotation}才对。")
                if (captured != null) {
                    sb.append("虽然吃了子，但可能得不偿失。")
                }
            }
            Quality.BLUNDER -> {
                sb.append("！！这步是败着，${lossPhrase(bestScore, humanScore)}，可能直接导致败局！")
                sb.append("${colorName}强烈建议走${bestNotation}。")
            }
        }

        // 3. 将军（绝杀已在上方提前返回，此处只可能是普通将军）
        if (isCheck) {
            sb.append("这步将军了${opponentName}。")
        }

        // 4. 局面评估
        val assessment = scoreToText(humanScore, isRedToMove)
        sb.append("当前局面：$assessment。")

        // 5. 后果说明（聚焦当前走棋方）
        //   ★ sameAsBest 时这手本是引擎首选，绝不再说"局面被动/需要防守"这种自打嘴巴的话。
        if (!sameAsBest) {
            if (cpLoss > 200) {
                sb.append("${colorName}这步走完后局面已经明显被动，后续需要谨慎防守。")
            } else if (cpLoss > 100) {
                sb.append("${colorName}这步走完后略微陷入下风，需要注意${opponentName}的反击。")
            }
        }
        if (cpLoss <= 20 && captured != null) {
            sb.append("白吃一子，子力优势进一步扩大，${opponentName}压力很大。")
        }

        return sb.toString()
    }

    // ══════════════════════════════════════════════
    //  假设分析
    // ══════════════════════════════════════════════

    fun explainWhatIf(
        moveUci: String,
        colorName: String,
        notation: String,
        score: Int,
        depth: Int,
        pv: String,
        boardBefore: Board
    ): String {
        val opponentName = if (colorName == "红方") "黑方" else "红方"
        val move = Move.fromUci(moveUci, boardBefore) ?: return "走法无效"
        val piece = boardBefore.getPiece(move.from) ?: move.piece
        val isRedToMove = colorName == "红方"
        val captured = getSafeCaptured(move, boardBefore)

        val sb = StringBuilder()
        sb.append("【假设分析】如果${colorName}走${notation}：\n")

        if (captured != null) {
            sb.append("这步会吃掉${opponentName}的${captured.displayName()}。")
        }

        val afterBoard = Board().apply {
            loadFromFen(boardBefore.toFen())
            makeMove(move)
        }

        // 将军检测
        if (afterBoard.isInCheck(piece.color.opponent())) {
            sb.append("走完这步会将军对方。")
            if (afterBoard.isCheckmate(piece.color.opponent())) {
                sb.append("而且是绝杀！")
            }
        }

        sb.append("\n走完后：")
        sb.append(scoreToText(score, !isRedToMove))

        if (pv.isNotEmpty()) {
            val moves = pv.split(" ").take(3)
            val variations = StringBuilder()
            val tempBoard = Board().apply { loadFromFen(afterBoard.toFen()) }
            for (uci in moves) {
                if (uci.length < 4) continue
                val n = uciToChineseNotation(uci, tempBoard)
                if (variations.isNotEmpty()) variations.append(" → ")
                variations.append(n)
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

    // ══════════════════════════════════════════════
    //  终局总结
    // ══════════════════════════════════════════════

    fun generateGameOverSummary(
        winnerName: String,
        loserName: String,
        totalSteps: Int,
        isCheckmate: Boolean,
        mistakeSteps: List<Triple<Int, String, Pair<Quality, Int>>>
    ): String {
        val sb = StringBuilder()

        if (isCheckmate) {
            sb.append("${winnerName}已将死${loserName}，棋局结束。")
        } else {
            sb.append("棋局结束，${winnerName}获胜。")
        }

        sb.append("${loserName}总共支撑了${totalSteps}步。")

        when {
            totalSteps <= 15 -> sb.append("对局时间很短，败局来得很迅速。")
            totalSteps <= 30 -> sb.append("对局时间适中，双方有过一番较量。")
            totalSteps <= 50 -> sb.append("对局持续时间较长，${loserName}进行了较顽强的抵抗。")
            else -> sb.append("这是一场持久战，${loserName}苦撑了很长时间。")
        }

        val bigMistakes = mistakeSteps.filter {
            it.third.first == Quality.BLUNDER || it.third.first == Quality.MISTAKE
        }.sortedByDescending { it.third.second }

        if (bigMistakes.isNotEmpty()) {
            sb.append("${loserName}的最大失误出现在：")
            val topMistakes = bigMistakes.take(3)
            for ((i, triple) in topMistakes.withIndex()) {
                val (step, notation, quality_loss) = triple
                sb.append("第${step}步${notation}（${quality_loss.first.label}，损失${quality_loss.second}分）")
                if (i < topMistakes.size - 1) sb.append("、")
            }
            sb.append("。")

            if (bigMistakes.size >= 3) {
                sb.append("整体来看，${loserName}失误较多，败因主要在于判断不够准确。")
            } else if (bigMistakes.size == 1 && bigMistakes[0].third.second > 300) {
                sb.append("这盘棋的关键转折就在这一步，一着不慎满盘皆输。")
            } else {
                sb.append("整体表现尚可，但关键时刻的失误决定了胜负。")
            }
        } else {
            sb.append("${loserName}整体走得不错，但对手更强一筹。")
        }

        return sb.toString()
    }

    // ══════════════════════════════════════════════
    //  开局识别
    // ══════════════════════════════════════════════

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

    // ══════════════════════════════════════════════
    //  内部辅助方法
    // ══════════════════════════════════════════════

    /**
     * 安全获取被吃棋子：从走棋前的棋盘上读取目标位置的棋子，
     * 并验证其颜色与走棋方不同。防止"自己吃自己"的荒谬解说。
     */
    private fun getSafeCaptured(move: Move, beforeBoard: Board): Piece? {
        val boardPiece = beforeBoard.getPiece(move.to) ?: return null
        val moverPiece = beforeBoard.getPiece(move.from) ?: move.piece
        return if (boardPiece.color != moverPiece.color) boardPiece else null
    }

    /**
     * 走棋目的分析（简化版，不做复杂战术检测）
     */
    private fun analyzePurpose(
        move: Move, piece: Piece, before: Board, after: Board, captured: Piece?
    ): String {
        val sb = StringBuilder()

        if (captured != null) {
            val capturedVal = pieceValue(captured.type)
            val pieceVal = pieceValue(piece.type)
            when {
                capturedVal > pieceVal -> sb.append("用${piece.displayName()}换${captured.displayName()}，赚了子力")
                capturedVal == pieceVal -> sb.append("兑掉${captured.displayName()}")
                else -> sb.append("吃掉${captured.displayName()}")
            }
        }

        val totalPieces = countPieces(before)
        if (totalPieces > 24 && captured == null) {
            when (piece.type) {
                PieceType.KNIGHT -> {
                    if (sb.isNotEmpty()) sb.append("，")
                    sb.append("跳出马，增强机动性")
                }
                PieceType.CANNON -> {
                    if (sb.isNotEmpty()) sb.append("，")
                    if (move.to.col == 4) sb.append("炮镇中路，控制中央要道")
                    else sb.append("调整炮位，寻找进攻机会")
                }
                PieceType.ROOK -> {
                    if (sb.isNotEmpty()) sb.append("，")
                    sb.append("出车，增强攻击力")
                }
                PieceType.PAWN -> {
                    if (sb.isNotEmpty()) sb.append("，")
                    val crossedRiver = if (piece.color == PieceColor.RED) move.to.row <= 4 else move.to.row >= 5
                    if (crossedRiver) sb.append("兵过河，威胁对方阵地")
                    else sb.append("挺兵，为后续进攻做准备")
                }
                else -> {}
            }
        }

        if (captured == null && move.to.col == 4 && piece.type != PieceType.KING) {
            if (sb.isEmpty()) sb.append("抢占中路要道，控制棋盘中央")
        }

        // 威胁检测（简化版：只看这步棋是否直接威胁对方子）
        if (captured == null) {
            val threatened = findDirectThreat(after, move.to, piece.color)
            if (threatened != null) {
                if (sb.isNotEmpty()) sb.append("，")
                sb.append("威胁对方的${threatened.displayName()}")
            }
        }

        return sb.toString()
    }

    /**
     * 检查走子后是否直接威胁对方某个子
     */
    private fun findDirectThreat(board: Board, from: Position, color: PieceColor): Piece? {
        val legalMoves = board.getLegalMoves(color)
        for (m in legalMoves) {
            if (m.from == from && m.captured != null && m.captured.color != color) {
                return m.captured
            }
        }
        return null
    }

    private fun pieceValue(type: PieceType): Int = when (type) {
        PieceType.ROOK -> 1000
        PieceType.KNIGHT -> 500
        PieceType.CANNON -> 500
        PieceType.ADVISOR -> 200
        PieceType.ELEPHANT -> 200
        PieceType.PAWN -> 100
        PieceType.KING -> 0
    }

    private fun countPieces(board: Board): Int {
        var count = 0
        for (r in 0..9) for (c in 0..8) {
            if (board.getPiece(Position(r, c)) != null) count++
        }
        return count
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

    /**
     * 劣势信息（简化版，仅用于内部参考）
     */
    data class DisadvantageInfo(
        val stepNumber: Int,
        val side: String,
        val reason: String,
        val materialDiff: Int,
        val scoreAtTime: Int
    )

    /**
     * 检测当前局面是否存在劣势
     */
    fun detectDisadvantage(score: Int, isRedToMove: Boolean, board: Board): DisadvantageInfo? {
        val absScore = abs(score)
        if (absScore < 100) return null

        val disadvantagedSide = if ((score > 0) == isRedToMove) "黑方" else "红方"
        val materialDiff = analyzeMaterialDiff(board)
        val diffAbs = abs(materialDiff)
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
            scoreAtTime = score
        )
    }
}
