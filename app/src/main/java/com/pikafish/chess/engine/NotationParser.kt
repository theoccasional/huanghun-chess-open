package com.pikafish.chess.engine

import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position

/**
 * ★ 纯静态棋谱文本解析器（不依赖引擎/上下文）。
 *
 * 从 ChessGame 里原样抽出「PGN / 中文记谱 / UCI 走法 + RAV 变着」的解析链路，
 * 供【主界面导入】与【CBL 合成区导出】共用同一份实现，避免两处各写一套导致行为分叉。
 * 只对 Board 做无副作用的重放，绝不触碰任何引擎或对局状态。
 */
object NotationParser {

    // ★★★ 黑方列号映射：兼容半角(1-9)与全角(１-９)数字。
    private val BLACK_COL_MAP = mapOf(
        '1' to 0, '2' to 1, '3' to 2, '4' to 3, '5' to 4, '6' to 5, '7' to 6, '8' to 7, '9' to 8,
        '１' to 0, '２' to 1, '３' to 2, '４' to 3, '５' to 4, '６' to 5, '７' to 6, '８' to 7, '９' to 8
    )

    // ★★★ 黑方步数/目标列映射：兼容全角数字。
    private val BLACK_STEP_MAP = mapOf(
        '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9,
        '１' to 1, '２' to 2, '３' to 3, '４' to 4, '５' to 5, '６' to 6, '７' to 7, '８' to 8, '９' to 9
    )

    private fun blackSteps(destChar: Char): Int? =
        BLACK_STEP_MAP[destChar] ?: destChar.digitToIntOrNull()

    /** 一条变着（树状，含嵌套子树与标签）。嵌套子变着的 branchStep 相对宿主走法串。 */
    class Variation(
        val branchStep: Int,
        val moves: List<String>,
        val label: String,
        val sub: List<Variation>
    )

    /** 主线的候选走法 + 变着树 + 主线注释（下标对齐主线）。 */
    class Parsed(
        val mainLine: List<String>,
        val variations: List<Variation>,
        val comments: Map<Int, String>
    )

    /**
     * 解析文本棋谱 → 主线 + 变着树 + 主线注释。
     * @param text 走法区文本（可含 PGN 头、编号、中文记谱、UCI、RAV 括号变着、{…} 注释）
     * @param initialFen 起始局面 FEN
     */
    fun parse(text: String, initialFen: String): Parsed {
        val board = Board()
        board.loadFromFen(initialFen)
        val r = parseMoves(text, board)
        return Parsed(r.mainLineMoves, r.variations, extractMainlineComments(text))
    }

    /**
     * ★ 展平变着树：返回「主线 + 每条变着」的完整 UCI 线路（每条都从起始局面起）。
     * 供 CBL 导出编码变着支线（象棋桥的扁平走法串）使用。
     */
    fun flattenLines(mainLine: List<String>, variations: List<Variation>): List<List<String>> {
        val out = ArrayList<List<String>>()
        fun expand(hostFull: List<String>, hostOffset: Int, subs: List<Variation>) {
            for (vb in subs) {
                if (vb.moves.isEmpty()) continue
                val step = (hostOffset + vb.branchStep).coerceIn(0, hostFull.size)
                val full = ArrayList<String>(step + vb.moves.size)
                full.addAll(hostFull.subList(0, step))
                full.addAll(vb.moves)
                out.add(full)
                expand(full, step, vb.sub)
            }
        }
        expand(mainLine, 0, variations)
        return out
    }

    // ════════════════════════ 内核 ════════════════════════

    /** PGN走法解析结果 */
    private class PgnParseResult(
        val mainLineMoves: List<String>,          // 主线UCI走法
        val variations: List<Variation>           // 变着分支（树状，含嵌套子树与标签）
    )

    /** 解析PGN走法文本，提取主线走法和RAV变着（递归还原嵌套树与标签）。 */
    private fun parseMoves(text: String, initialBoard: Board): PgnParseResult {
        val mainLineMoves = mutableListOf<String>()
        val variations = mutableListOf<Variation>()

        // 预处理：移除行注释与结果标记（括号变招内的 {X} 标签注释保留给 parsePgnLine 识别）
        val cleaned = text
            .replace(Regex(";.*$"), "")
            .replace(Regex("1-0|0-1|1/2-1/2|\\*"), "")

        val tokens = tokenizePgn(cleaned)
        val board2 = Board()
        board2.loadFromFen(initialBoard.toFen())

        parsePgnLine(tokens, 0, board2, mainLineMoves, variations)

        return PgnParseResult(mainLineMoves, variations)
    }

    /**
     * 解析一条走法线（主线或某个变招内部均适用），递归处理嵌套 RAV。
     * @return 处理到的 token 下标（遇 ")" 返回其下标，调用方据此推进）
     */
    private fun parsePgnLine(
        tokens: List<String>,
        start: Int,
        board: Board,
        lineMoves: MutableList<String>,
        outVars: MutableList<Variation>
    ): Int {
        var i = start
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token == "(" -> {
                    // ★ 变招开始：branchStep = 当前线已走的手数（相对）
                    val branchStep = lineMoves.size
                    val subBoard = Board()
                    subBoard.loadFromFen(board.toFen())
                    val varMoves = mutableListOf<String>()
                    val varSubs = mutableListOf<Variation>()

                    var label = ""
                    var subStart = i + 1
                    val labelToken = tokens.getOrNull(subStart)
                    labelToken?.let { lt ->
                        val lm = Regex("^\\{([^{}]*)\\}$").find(lt.trim())
                        if (lm != null) {
                            label = lm.groupValues[1].trim()
                            subStart++
                        }
                    }

                    val endParen = parsePgnLine(tokens, subStart, subBoard, varMoves, varSubs)
                    if (varMoves.isNotEmpty()) {
                        outVars.add(Variation(branchStep, varMoves, label, varSubs))
                    }
                    i = endParen + 1
                }
                token == ")" -> return i
                else -> {
                    val uci = parseMoveToken(token, board)
                    if (uci != null) {
                        val move = Move.fromUci(uci, board)
                        if (move != null && board.getLegalMoves(board.currentPlayer).any { it.toUci() == uci }) {
                            lineMoves.add(uci)
                            board.makeMove(move)
                        }
                    }
                    i++
                }
            }
        }
        return i
    }

    /** 将PGN文本分词 */
    private fun tokenizePgn(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()

        for (ch in text) {
            when {
                // ★ 兼容全角括号（中文变招存档 buildGivingTxt 输出的是"（…）"）
                ch == '(' || ch == '（' -> {
                    if (sb.isNotBlank()) { tokens.add(sb.toString().trim()); sb.clear() }
                    tokens.add("(")
                }
                ch == ')' || ch == '）' -> {
                    if (sb.isNotBlank()) { tokens.add(sb.toString().trim()); sb.clear() }
                    tokens.add(")")
                }
                ch.isWhitespace() -> {
                    if (sb.isNotBlank()) { tokens.add(sb.toString().trim()); sb.clear() }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) tokens.add(sb.toString().trim())
        return tokens.filter { it.isNotEmpty() }
    }

    /**
     * 提取 PGN 主线的 {…} 注释，返回 {主线走法下标 -> 注释文本}。
     * 先用括号深度把 RAV 变招 (…) 连同其内部 {A} 标签整块剥掉，只保主线；
     * 再按“注释紧随其前的主线走法”绑定下标。
     */
    fun extractMainlineComments(moveText: String): Map<Int, String> {
        val res = LinkedHashMap<Int, String>()
        val sb = StringBuilder(moveText.length)
        var depth = 0
        for (ch in moveText) {
            when (ch) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        var moveIdx = -1
        var i = 0
        val n = sb.length
        while (i < n) {
            val c = sb[i]
            when {
                c == '{' -> {
                    val end = sb.indexOf('}', i)
                    if (end < 0) break
                    val txt = sb.substring(i + 1, end).trim()
                    if (txt.isNotEmpty() && moveIdx >= 0) res[moveIdx] = txt
                    i = end + 1
                }
                c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u00a0' || c == '\u3000' -> i++
                else -> {
                    var j = i
                    while (j < n && !sb[j].isWhitespace() && sb[j] != '{') j++
                    val tok = sb.substring(i, j)
                    i = j
                    if (isMainlineMoveToken(tok)) moveIdx++
                }
            }
        }
        return res
    }

    /** 判定某个 token 是否属于主线走法（用于注释下标对齐）。 */
    private fun isMainlineMoveToken(tok: String): Boolean {
        val t = tok.trim()
        if (t.isEmpty()) return false
        if (Regex("^[0-9０-９]+[.．]{1,3}").containsMatchIn(t)) return false
        if (t == "1-0" || t == "0-1" || t == "1/2-1/2" || t == "*") return false
        if (t.lowercase().replace("-", "").matches(Regex("^[a-i][0-9][a-i][0-9]$"))) return true
        val pieces = t.any { it in "车車馬马象相士仕将將帅帥炮砲兵卒" }
        val direction = Regex("[进進退平]").containsMatchIn(t)
        return pieces && direction
    }

    /**
     * 解析单个走法token，支持UCI和中文记谱
     * @return UCI走法字符串，如无法解析返回null
     */
    fun parseMoveToken(token: String, board: Board): String? {
        val clean = token.replace(Regex("^[0-9０-９]+\\.\\.?\\.*"), "").trim()
        if (clean.isEmpty()) return null
        if (clean.matches(Regex("[a-i][0-9][a-i][0-9][nbrq]?"))) return clean
        return chineseNotationToUci(clean, board)
    }

    /** 将中文记谱转换为UCI走法。 */
    fun chineseNotationToUci(notation: String, board: Board): String? {
        if (notation.length < 4) return null

        val isRed = board.currentPlayer == PieceColor.RED
        val redColMap = mapOf(
            '九' to 0, '八' to 1, '七' to 2, '六' to 3, '五' to 4, '四' to 5, '三' to 6, '二' to 7, '一' to 8,
            '1' to 0, '2' to 1, '3' to 2, '4' to 3, '5' to 4, '6' to 5, '7' to 6, '8' to 7, '9' to 8,
            '１' to 0, '２' to 1, '３' to 2, '４' to 3, '５' to 4, '６' to 5, '７' to 6, '８' to 7, '９' to 8
        )
        val blackColMap = BLACK_COL_MAP
        val redStepMap = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9,
            '１' to 1, '２' to 2, '３' to 3, '４' to 4, '５' to 5, '６' to 6, '７' to 7, '８' to 8, '９' to 9
        )

        val hasFrontBack = notation.startsWith("前") || notation.startsWith("中") || notation.startsWith("后")

        val pieceName = if (hasFrontBack) notation.substring(1, 2) else notation.substring(0, 1)

        val pieceTypeMap = mapOf(
            "车" to PieceType.ROOK, "車" to PieceType.ROOK,
            "馬" to PieceType.KNIGHT, "马" to PieceType.KNIGHT,
            "炮" to PieceType.CANNON, "砲" to PieceType.CANNON,
            "兵" to PieceType.PAWN, "卒" to PieceType.PAWN,
            "帅" to PieceType.KING, "將" to PieceType.KING, "将" to PieceType.KING,
            "仕" to PieceType.ADVISOR, "士" to PieceType.ADVISOR,
            "相" to PieceType.ELEPHANT, "象" to PieceType.ELEPHANT
        )

        val pieceType = pieceTypeMap[pieceName] ?: return null
        val color = board.currentPlayer

        if (hasFrontBack) {
            val prefix = notation.substring(0, 1)
            val action = notation.substring(2, 3)
            val dest = notation.substring(3)

            val redForward = color == PieceColor.RED
            val byCol = mutableMapOf<Int, MutableList<Position>>()
            for (r in 0..9) for (c in 0..8) {
                val p = board.getPiece(Position(r, c))
                if (p != null && p.color == color && p.type == pieceType) {
                    byCol.getOrPut(c) { mutableListOf() }.add(Position(r, c))
                }
            }

            val needCnt = if (prefix == "中") 3 else 2
            for ((_, colPieces) in byCol) {
                if (colPieces.size < needCnt) continue
                colPieces.sortWith(Comparator { a, b ->
                    if (redForward) a.row - b.row else b.row - a.row
                })
                val idx = when (prefix) {
                    "前" -> 0
                    "后" -> colPieces.size - 1
                    else -> 1
                }
                val selectedPos = colPieces[idx]
                val uci = findMoveByNotation(selectedPos, pieceType, color, action, dest, board)
                if (uci != null) return uci
            }
            return null
        }

        val colChar = notation[1]
        val colMap = if (isRed) redColMap else blackColMap
        val fromCol = colMap[colChar] ?: return null

        val action = notation.substring(2, 3)
        val dest = notation.substring(3)

        val fromPos = findPieceOnColumn(pieceType, color, fromCol, board) ?: return null

        return findMoveByNotation(fromPos, pieceType, color, action, dest, board)
    }

    /**
     * 在指定列上找到指定类型的棋子。
     * 同列多子时返回离对方最近那枚（红取行号最小、黑取行号最大）。
     */
    private fun findPieceOnColumn(type: PieceType, color: PieceColor, col: Int, board: Board): Position? {
        var found: Position? = null
        for (r in 0..9) {
            val p = board.getPiece(Position(r, col))
            if (p != null && p.color == color && p.type == type) {
                if (found == null) {
                    found = Position(r, col)
                } else if (color == PieceColor.RED && r < found.row) {
                    found = Position(r, col)
                } else if (color == PieceColor.BLACK && r > found.row) {
                    found = Position(r, col)
                }
            }
        }
        return found
    }

    /** 根据中文记谱的组成部分查找UCI走法。 */
    private fun findMoveByNotation(
        fromPos: Position, pieceType: PieceType, color: PieceColor,
        action: String, dest: String, board: Board
    ): String? {
        val isRed = color == PieceColor.RED
        val redColMap = mapOf(
            '九' to 0, '八' to 1, '七' to 2, '六' to 3, '五' to 4, '四' to 5, '三' to 6, '二' to 7, '一' to 8,
            '1' to 0, '2' to 1, '3' to 2, '4' to 3, '5' to 4, '6' to 5, '7' to 6, '8' to 7, '9' to 8,
            '１' to 0, '２' to 1, '３' to 2, '４' to 3, '５' to 4, '６' to 5, '７' to 6, '８' to 7, '９' to 8
        )
        val blackColMap = BLACK_COL_MAP
        val redStepMap = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9,
            '１' to 1, '２' to 2, '３' to 3, '４' to 4, '５' to 5, '６' to 6, '７' to 7, '８' to 8, '９' to 9
        )

        val legalMoves = board.getLegalMoves(color).filter { it.from == fromPos }
        if (legalMoves.isEmpty()) return null

        when (action) {
            "平" -> {
                val destChar = dest.first()
                val destCol = (if (isRed) redColMap else blackColMap)[destChar] ?: return null
                val move = legalMoves.find { it.to.col == destCol && it.to.row == fromPos.row }
                return move?.toUci()
            }
            "进" -> {
                if (pieceType == PieceType.KNIGHT || pieceType == PieceType.ELEPHANT || pieceType == PieceType.ADVISOR) {
                    val destChar = dest.first()
                    val destCol = (if (isRed) redColMap else blackColMap)[destChar] ?: return null
                    val move = legalMoves.find { it.to.col == destCol &&
                        if (isRed) it.to.row < fromPos.row else it.to.row > fromPos.row }
                    return move?.toUci()
                } else {
                    val destChar = dest.first()
                    val steps = if (isRed) {
                        redStepMap[destChar] ?: destChar.digitToIntOrNull()
                    } else {
                        blackSteps(destChar)
                    } ?: return null
                    val targetRow = if (isRed) fromPos.row - steps else fromPos.row + steps
                    val move = legalMoves.find { it.to.row == targetRow && it.to.col == fromPos.col }
                    return move?.toUci()
                }
            }
            "退" -> {
                if (pieceType == PieceType.KNIGHT || pieceType == PieceType.ELEPHANT || pieceType == PieceType.ADVISOR) {
                    val destChar = dest.first()
                    val destCol = (if (isRed) redColMap else blackColMap)[destChar] ?: return null
                    val move = legalMoves.find { it.to.col == destCol &&
                        if (isRed) it.to.row > fromPos.row else it.to.row < fromPos.row }
                    return move?.toUci()
                } else {
                    val destChar = dest.first()
                    val steps = if (isRed) {
                        redStepMap[destChar] ?: destChar.digitToIntOrNull()
                    } else {
                        blackSteps(destChar)
                    } ?: return null
                    val targetRow = if (isRed) fromPos.row + steps else fromPos.row - steps
                    val move = legalMoves.find { it.to.row == targetRow && it.to.col == fromPos.col }
                    return move?.toUci()
                }
            }
        }
        return null
    }
}