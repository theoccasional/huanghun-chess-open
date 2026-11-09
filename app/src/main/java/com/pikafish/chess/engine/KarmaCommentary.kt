package com.pikafish.chess.engine

import com.pikafish.chess.model.*
import kotlin.random.Random

/**
 * 现世报象棋 · AI解说员 — 暗黑人格、因果报应、大白话带情绪
 *
 * 核心信条：
 *  - 下棋就是害人。谁走的棋狠，就夸谁阴毒；谁走的棋臭，就骂谁少智。
 *  - 棋盘即人心，人心皆阴暗。所谓妙手，不过是更体面的"下套"。
 *  - 现世报：这一步漏了，下一步就得还。想害人没害成，反被人弄死，这就是报应。
 *
 * 三维分析框架：
 *  1. 意图分析 — 这步棋想干什么？想害谁？
 *  2. 后果预测 — 这步棋会带来什么报应？
 *  3. 阴招拆解 — 用了什么阴毒手段？
 */
object KarmaCommentary {

    // ══════════════════════════════════════════════
    //  词汇库
    // ══════════════════════════════════════════════

    // 夸奖词汇（阴毒风格）
    private val praiseWords = listOf(
        "阴毒", "狠辣", "这人下手真黑", "心比下水道还黑",
        "招招见血", "刀刀剐肉", "这手真黑", "够阴的",
        "毒辣", "杀心太重", "一点活路不给", "往死里整",
        "下手不留情", "这人心机深", "老谋深算", "阴险狡诈",
        "不给人留活路", "心狠手辣"
    )

    // 贬低词汇（少智风格）
    private val insultWords = listOf(
        "少智", "脑子缺根筋", "像个傻逼一样把自己卖了",
        "自作自受", "害人不成反害己", "偷鸡不成蚀把米",
        "蠢到家了", "这脑子是豆腐做的", "下棋不动脑",
        "搬石头砸自己脚", "送死", "白给", "自毁长城",
        "这棋走得跟闹着玩似的", "眼睛长哪去了"
    )

    // 因果报应词汇
    private val karmaWords = listOf(
        "现世报", "报应来了", "因果循环", "苍天饶过谁",
        "这就叫报应", "立马兑现，绝不隔夜",
        "天理昭昭", "因果不爽", "报应不爽",
        "不是不报，时候未到", "出来混，迟早要还的"
    )

    // 局势描述（劣势方）
    private val crisisWords = listOf(
        "要完蛋了", "命悬一线", "九死一生", "危在旦夕",
        "大难临头", "死到临头", "走投无路", "山穷水尽",
        "穷途末路", "气数已尽"
    )

    // 局势描述（优势方）
    private val advantageWords = listOf(
        "稳操胜券", "胜券在握", "高枕无忧", "大局已定",
        "十拿九稳", "赢定了", "立于不败之地", "稳如泰山",
        "占尽上风", "势如破竹"
    )

    // 开局/中局/残局氛围词
    private val moodWords = mapOf(
        "opening" to listOf("大战一触即发", "杀机初现", "暗流涌动", "各怀鬼胎"),
        "middlegame" to listOf("杀得难解难分", "刀光剑影", "血雨腥风", "战况惨烈"),
        "endgame" to listOf("残局见真章", "一着不慎满盘输", "步步惊心", "生死一线")
    )

    // ══════════════════════════════════════════════
    //  战术翻译（阴招词典）
    // ══════════════════════════════════════════════

    private val tacticTranslations = mapOf(
        "fork" to listOf("一箭双雕", "两头通吃", "左右夹击"),
        "pin" to listOf("锁喉", "掐住命门", "动弹不得"),
        "skewer" to listOf("串烧", "穿糖葫芦", "一穿到底"),
        "discovery" to listOf("暗箭伤人", "背后捅刀", "借刀杀人"),
        "double_check" to listOf("双重打击", "雪上加霜", "落井下石"),
        "trapped" to listOf("请君入瓮", "瓮中捉鳖", "关门打狗"),
        "sacrifice" to listOf("以命换命", "舍身取义", "弃子布局"),
        "checkmate_threat" to listOf("杀招", "致命一击", "一剑封喉"),
        "pawn_storm" to listOf("兵临城下", "大军压境", "咄咄逼人"),
        "counter_attack" to listOf("反咬一口", "以牙还牙", "回马枪"),
        "clearance" to listOf("清道", "扫清障碍", "开路先锋"),
        "interference" to listOf("搅局", "横插一杠", "从中作梗"),
        "blockade" to listOf("封死", "堵死退路", "插翅难飞"),
        "zwischenzug" to listOf("先声夺人", "虚晃一枪", "出其不意"),
        "undermining" to listOf("釜底抽薪", "挖墙脚", "断其后路")
    )

    /**
     * 把正经战术翻译成阴招说法
     */
    fun translateTactic(tactic: String): String {
        val entries = tacticTranslations[tactic]
        return if (entries != null) entries.random() else tactic
    }

    // ══════════════════════════════════════════════
    //  局面阶段判断
    // ══════════════════════════════════════════════

    private fun getGamePhase(board: Board): String {
        val pieceCount = (0..9).sumOf { r -> (0..8).count { c -> board.getPiece(Position(r, c)) != null } }
        return when {
            pieceCount >= 28 -> "opening"
            pieceCount >= 14 -> "middlegame"
            else -> "endgame"
        }
    }

    // ══════════════════════════════════════════════
    //  三维分析核心方法
    // ══════════════════════════════════════════════

    /**
     * 意图分析：这步棋想干什么坏事？
     */
    private fun analyzeIntent(moveUci: String, board: Board, isHuman: Boolean): String {
        val move = Move.fromUci(moveUci, board) ?: return "看不懂这步想干啥"
        // ★ 安全吃子检测：验证颜色，防止"自己吃自己"
        val moverPiece = board.getPiece(move.from) ?: move.piece
        val capturedPiece = board.getPiece(move.to)
        val captured = if (capturedPiece != null && capturedPiece.color != moverPiece.color) capturedPiece else null
        val from = move.from
        val to = move.to
        val piece = board.getPiece(from)

        val intents = mutableListOf<String>()

        // 吃子
        if (captured != null) {
            val capturedName = captured.displayName()
            intents.add("想吃掉${capturedName}")
        }

        // 将军
        val testBoard = Board()
        testBoard.loadFromFen(board.toFen())
        testBoard.makeMove(move)
        if (testBoard.isInCheck(testBoard.currentPlayer)) {
            intents.add("将军逼宫")
        }

        // 进攻性走法（越过河界）
        val pieceColor = piece?.color
        if (pieceColor == PieceColor.RED && to.row <= 4 && from.row > 4) {
            intents.add("杀过河去")
        } else if (pieceColor == PieceColor.BLACK && to.row >= 5 && from.row < 5) {
            intents.add("打过河来")
        }

        if (intents.isEmpty()) {
            intents.add(if (isHuman) "试探性走棋" else "暗中布局")
        }

        return intents.random()
    }

    /**
     * 后果预测：这步棋会带来什么报应？
     * @param score 引擎评分（从当前走棋方视角，正数=走棋方占优）
     * @param isHuman 是否人脑走棋
     * @param isRedToMove 当前是否红方走棋（用于判断优势方）
     */
    private fun analyzeConsequences(score: Int, isHuman: Boolean, isRedToMove: Boolean): String {
        val absScore = kotlin.math.abs(score)
        // 正确判断优势方：score>0且当前走棋方=红方 → 红方占优
        // score>0且当前走棋方=黑方 → 黑方占优
        val moverAdvantaged = score > 0  // 走棋方是否占优

        return when {
            absScore > 500 -> {
                if (moverAdvantaged) {
                    if (isHuman) "这一手狠毒，对手要遭报应了"
                    else "这AI下手太黑，对手在劫难逃"
                } else {
                    if (isHuman) "这步棋后患无穷，报应马上就到"
                    else "AI这一手自掘坟墓，要遭现世报了"
                }
            }
            absScore > 200 -> {
                if (moverAdvantaged) "占了便宜，对手要吃苦头了"
                else "吃了亏，后面有得受了"
            }
            absScore > 100 -> {
                if (moverAdvantaged) "略占上风，但还没到报应的时候"
                else "稍微吃了点亏，还能补救"
            }
            else -> "不痛不痒，报应还在路上"
        }
    }

    /**
     * 阴招拆解：用了什么阴毒手段？
     */
    private fun analyzeMethod(moveUci: String, board: Board, pv: String): String {
        val tactics = detectTactics(moveUci, board, pv)
        return if (tactics.isNotEmpty()) {
            tactics.joinToString("，") { translateTactic(it) }
        } else {
            "平平无奇的一手，没什么阴招"
        }
    }

    /**
     * 检测战术手段
     */
    private fun detectTactics(moveUci: String, board: Board, pv: String): List<String> {
        val tactics = mutableListOf<String>()
        val move = Move.fromUci(moveUci, board) ?: return tactics
        val piece = board.getPiece(move.from) ?: return tactics
        val color = piece.color

        // 检测将军
        val testBoard = Board()
        testBoard.loadFromFen(board.toFen())
        testBoard.makeMove(move)
        if (testBoard.isInCheck(testBoard.currentPlayer)) {
            tactics.add("checkmate_threat")
        }

        // 检测弃子 — ★ 安全吃子检测：验证颜色
        val sacrificeMover = board.getPiece(move.from) ?: piece
        val sacrificeTarget = board.getPiece(move.to)
        val actualCaptured = if (sacrificeTarget != null && sacrificeTarget.color != sacrificeMover.color) sacrificeTarget else null
        if (actualCaptured != null) {
            val pieceValue = pieceValue(piece)
            val capturedValue = pieceValue(actualCaptured)
            if (pieceValue > capturedValue + 200) {
                tactics.add("sacrifice")
            }
        }

        // 检测捉双：走棋后同时攻击多个高价值棋子
        val opponentColor = color.opponent()
        val attackedHighValue = mutableListOf<Position>()
        for (r in 0..9) for (c in 0..8) {
            val pos = Position(r, c)
            val target = testBoard.getPiece(pos)
            if (target != null && target.color == opponentColor) {
                if (target.type == PieceType.ROOK || target.type == PieceType.CANNON || target.type == PieceType.KNIGHT) {
                    // 检查是否被攻击：用对方走法验证
                    val legalMoves = testBoard.getLegalMoves(color)
                    for (m in legalMoves) {
                        if (m.to == pos) {
                            attackedHighValue.add(pos)
                            break
                        }
                    }
                }
            }
        }
        if (attackedHighValue.size >= 2) tactics.add("fork")

        return tactics
    }

    private fun pieceValue(piece: Piece): Int {
        return when (piece.type) {
            PieceType.KING -> 0  // 与MoveExplainer统一，将帅不计子力价值
            PieceType.ROOK -> 1000
            PieceType.CANNON -> 500
            PieceType.KNIGHT -> 500  // 与MoveExplainer统一（原为400）
            PieceType.ELEPHANT -> 200
            PieceType.ADVISOR -> 200
            PieceType.PAWN -> 100
        }
    }

    // ══════════════════════════════════════════════
    //  主入口：评价人脑走棋
    // ══════════════════════════════════════════════

    fun evaluateHumanMove(
        humanMoveUci: String,
        bestMoveUci: String,
        humanScore: Int,
        bestScore: Int,
        depth: Int,
        pv: String,
        board: Board,
        disadvantageInfo: MoveExplainer.DisadvantageInfo? = null
    ): String {
        val cpLoss = bestScore - humanScore
        // ★ 一致性铁律：引擎推荐的正是你走的这一手 → 一律按妙手夸，绝不"推荐同一手又骂同一手"。
        val quality = if (CommentaryEngine.sameAsRecommended(humanMoveUci, bestMoveUci))
            MoveExplainer.MoveQuality.BRILLIANT
        else MoveExplainer.classifyQuality(cpLoss)
        val phase = getGamePhase(board)
        val mood = moodWords[phase]?.random() ?: ""

        // 走棋方的颜色（走棋前board的currentPlayer就是走棋方）
        val isRedToMove = board.currentPlayer == PieceColor.RED

        val intent = analyzeIntent(humanMoveUci, board, true)
        val consequence = analyzeConsequences(humanScore, true, isRedToMove)
        val method = analyzeMethod(humanMoveUci, board, pv)

        val sb = StringBuilder()

        // 判断走棋方是否处于劣势
        val moverAdvantaged = humanScore > 0

        when (quality) {
            MoveExplainer.MoveQuality.BRILLIANT, MoveExplainer.MoveQuality.GOOD -> {
                if (moverAdvantaged) {
                    sb.append("【${praiseWords.random()}】")
                    sb.append("$intent。")
                    sb.append("${method}。")
                    sb.append("$consequence")
                } else {
                    // 走对了但仍然处于下风 — 逻辑通顺
                    sb.append("这步走得没错，$intent。")
                    sb.append("${method}。")
                    if (disadvantageInfo != null) {
                        val lossDesc = when {
                            kotlin.math.abs(disadvantageInfo.materialDiff) >= 1000 -> "不可挽回的损失"
                            kotlin.math.abs(disadvantageInfo.materialDiff) >= 500 -> "较大损失"
                            kotlin.math.abs(disadvantageInfo.materialDiff) >= 200 -> "中等损失"
                            else -> "较小的损失"
                        }
                        sb.append("但前期已经造成了${lossDesc}。")
                        sb.append("尽管这步在所有能走的棋中算是最顽强的，但仍然处于下风。")
                        sb.append("对方优势显著，只有在对方出错的情况下才能赶回平局状态。")
                        sb.append("这就是因果——前债未清，还得继续熬。")
                    } else {
                        sb.append("但局面仍然不乐观，还得继续熬。")
                    }
                }
                if (mood.isNotEmpty()) sb.append("（$mood）")
            }
            MoveExplainer.MoveQuality.STANDARD, MoveExplainer.MoveQuality.PLAYABLE -> {
                sb.append("$intent。")
                sb.append("${method}。")
                sb.append("$consequence")
            }
            MoveExplainer.MoveQuality.QUESTIONABLE -> {
                sb.append("【${insultWords.random()}】")
                sb.append("$intent，但$method。")
                sb.append("$consequence")
                if (disadvantageInfo != null) {
                    sb.append(" 第${disadvantageInfo.stepNumber}步就${disadvantageInfo.reason}，现在还在还债。")
                }
            }
            MoveExplainer.MoveQuality.MISTAKE, MoveExplainer.MoveQuality.BLUNDER -> {
                sb.append("【${insultWords.random()}！${karmaWords.random()}！】")
                sb.append("$intent，结果$method。")
                sb.append("$consequence")
                if (disadvantageInfo != null) {
                    sb.append(" ${disadvantageInfo.reason}，这债还没还完呢！")
                }
            }
        }

        // 如果分差很大，加报应语
        if (kotlin.math.abs(humanScore) > 300) {
            sb.append(" ${karmaWords.random()}！")
        }

        return sb.toString()
    }

    // ══════════════════════════════════════════════
    //  主入口：评价AI走棋
    // ══════════════════════════════════════════════

    fun evaluateAiMove(
        aiMoveUci: String,
        score: Int,
        depth: Int,
        pv: String,
        board: Board,
        disadvantageInfo: MoveExplainer.DisadvantageInfo? = null
    ): String {
        val phase = getGamePhase(board)
        val mood = moodWords[phase]?.random() ?: ""
        val intent = analyzeIntent(aiMoveUci, board, false)
        val method = analyzeMethod(aiMoveUci, board, pv)
        
        // 走棋方颜色（走棋前board的currentPlayer就是走棋方）
        val isRedToMove = board.currentPlayer == PieceColor.RED
        // AI是否占优：score>0表示走棋方（AI）占优
        val aiAdvantaged = score > 0
        val absScore = kotlin.math.abs(score)

        val sb = StringBuilder()

        when {
            absScore > 500 && aiAdvantaged -> {
                sb.append("【${praiseWords.random()}！】")
                sb.append("AI这手$intent。")
                sb.append("${method}。")
                sb.append("对手这下要遭殃了，${karmaWords.random()}。")
            }
            absScore > 200 && aiAdvantaged -> {
                sb.append("AI$intent。")
                sb.append("${method}。")
                sb.append("稳扎稳打的阴招。")
            }
            absScore > 500 && !aiAdvantaged -> {
                sb.append("【${insultWords.random()}！${karmaWords.random()}！】")
                sb.append("AI这手$intent，但$method。")
                sb.append("这一手把自己坑了，现世报来了——害人不成反害己。")
            }
            absScore > 200 && !aiAdvantaged -> {
                sb.append("AI$intent。")
                sb.append("${method}。")
                sb.append("这步走臭了，要吃亏。")
            }
            else -> {
                sb.append("AI$intent。")
                sb.append("${method}。")
            }
        }

        if (disadvantageInfo != null && aiAdvantaged && absScore > 300) {
            sb.append(" ${disadvantageInfo.reason}，这是追着痛点打，够狠！")
        }

        if (mood.isNotEmpty()) sb.append("（$mood）")

        return sb.toString()
    }

    // ══════════════════════════════════════════════
    //  局面分析（现世报风格）
    // ══════════════════════════════════════════════

    fun explainPosition(score: Int, depth: Int, pv: String, board: Board): String {
        val absScore = kotlin.math.abs(score)
        val phase = getGamePhase(board)
        val mood = moodWords[phase]?.random() ?: ""

        // 正确判断优势方：score>0且当前走棋方=红方 → 红方占优
        val isRedToMove = board.currentPlayer == PieceColor.RED
        val advantageSide = if ((score > 0) == isRedToMove) "红方" else "黑方"

        val sb = StringBuilder()

        when {
            absScore > 500 -> {
                sb.append("${advantageSide}${advantageWords.random()}。")
                sb.append("下手狠毒，对手${crisisWords.random()}。")
            }
            absScore > 200 -> {
                sb.append("${advantageSide}${advantageWords.random()}。")
                sb.append("但报应还没完全兑现，对手还有机会。")
            }
            absScore > 50 -> {
                sb.append("${advantageSide}稍微占点便宜，但差距不大。")
            }
            else -> {
                sb.append("半斤八两，谁也别想占谁的便宜。")
            }
        }

        if (mood.isNotEmpty()) sb.append("（$mood）")

        return sb.toString()
    }

    // ══════════════════════════════════════════════
    //  假设分析（现世报风格）
    // ══════════════════════════════════════════════

    fun explainWhatIf(uciMove: String, score: Int, depth: Int, pv: String, board: Board): String {
        val absScore = kotlin.math.abs(score)
        val notation = MoveExplainer.uciToChineseNotation(uciMove, board)

        return when {
            score > 200 -> "如果走${notation}，那可就厉害了——${praiseWords.random()}，${advantageWords.random()}！"
            score < -200 -> "如果走${notation}，那就是${insultWords.random()}，${karmaWords.random()}！"
            else -> "走${notation}的话，不痛不痒，没啥意思。"
        }
    }

    // ══════════════════════════════════════════════
    //  终局总结（现世报风格）
    // ══════════════════════════════════════════════

    /**
     * 终局总结（现世报风格）
     */
    fun generateGameOverSummary(
        winnerName: String,
        loserName: String,
        totalSteps: Int,
        isCheckmate: Boolean,
        mistakeSteps: List<Triple<Int, String, Pair<MoveExplainer.MoveQuality, Int>>>
    ): String {
        val sb = StringBuilder()

        if (isCheckmate) {
            sb.append("${winnerName}已经把${loserName}将死了，棋就结束了。")
        } else {
            sb.append("棋结束了，${winnerName}赢了。")
        }

        sb.append("${loserName}苦撑了${totalSteps}步，")
        when {
            totalSteps <= 15 -> sb.append("很快就撑不住了。")
            totalSteps <= 30 -> sb.append("也算是较量了一番。")
            totalSteps <= 50 -> sb.append("抵抗得还算顽强。")
            else -> sb.append("硬是熬了很久。")
        }

        val bigMistakes = mistakeSteps.filter {
            it.third.first == MoveExplainer.MoveQuality.BLUNDER || it.third.first == MoveExplainer.MoveQuality.MISTAKE
        }.sortedByDescending { it.third.second }

        if (bigMistakes.isNotEmpty()) {
            sb.append("${loserName}最大的一次失误是第${bigMistakes[0].first}步${bigMistakes[0].second}，")
            sb.append("一下子损失了${bigMistakes[0].third.second}分。")
            if (bigMistakes.size >= 3) {
                sb.append("整体来看失误太多，${karmaWords.random()}——该还的债迟早要还。")
            } else if (bigMistakes[0].third.second > 300) {
                sb.append("就是这一步要了命，一着不慎满盘皆输。")
            } else {
                sb.append("关键时刻没顶住，${karmaWords.random()}。")
            }
        } else {
            sb.append("${loserName}整体走得还行，但对手更狠，技不如人只能认栽。")
        }

        return sb.toString()
    }
}