package com.pikafish.chess.model

import android.util.Log

/**
 * 中国象棋棋盘，包含完整的规则判断和走法生成
 */
class Board {
    // 10行 x 9列 棋盘
    private val grid: Array<Array<Piece?>> = Array(10) { arrayOfNulls(9) }

    // 当前走棋方
    var currentPlayer: PieceColor = PieceColor.RED

    // 走法历史
    private val moveHistory = mutableListOf<Move>()
    // AI 搜索用无校验走法历史
    private val uncheckedHistory = mutableListOf<Triple<Position, Position, Piece?>>()

    init {
        reset()
    }

    fun reset() {
        // 清空棋盘
        for (r in 0..9) for (c in 0..8) grid[r][c] = null

        // 黑方 (上方, row 0-4)
        grid[0][0] = Piece(PieceType.ROOK, PieceColor.BLACK)
        grid[0][1] = Piece(PieceType.KNIGHT, PieceColor.BLACK)
        grid[0][2] = Piece(PieceType.ELEPHANT, PieceColor.BLACK)
        grid[0][3] = Piece(PieceType.ADVISOR, PieceColor.BLACK)
        grid[0][4] = Piece(PieceType.KING, PieceColor.BLACK)
        grid[0][5] = Piece(PieceType.ADVISOR, PieceColor.BLACK)
        grid[0][6] = Piece(PieceType.ELEPHANT, PieceColor.BLACK)
        grid[0][7] = Piece(PieceType.KNIGHT, PieceColor.BLACK)
        grid[0][8] = Piece(PieceType.ROOK, PieceColor.BLACK)
        grid[2][1] = Piece(PieceType.CANNON, PieceColor.BLACK)
        grid[2][7] = Piece(PieceType.CANNON, PieceColor.BLACK)
        grid[3][0] = Piece(PieceType.PAWN, PieceColor.BLACK)
        grid[3][2] = Piece(PieceType.PAWN, PieceColor.BLACK)
        grid[3][4] = Piece(PieceType.PAWN, PieceColor.BLACK)
        grid[3][6] = Piece(PieceType.PAWN, PieceColor.BLACK)
        grid[3][8] = Piece(PieceType.PAWN, PieceColor.BLACK)

        // 红方 (下方, row 5-9)
        grid[9][0] = Piece(PieceType.ROOK, PieceColor.RED)
        grid[9][1] = Piece(PieceType.KNIGHT, PieceColor.RED)
        grid[9][2] = Piece(PieceType.ELEPHANT, PieceColor.RED)
        grid[9][3] = Piece(PieceType.ADVISOR, PieceColor.RED)
        grid[9][4] = Piece(PieceType.KING, PieceColor.RED)
        grid[9][5] = Piece(PieceType.ADVISOR, PieceColor.RED)
        grid[9][6] = Piece(PieceType.ELEPHANT, PieceColor.RED)
        grid[9][7] = Piece(PieceType.KNIGHT, PieceColor.RED)
        grid[9][8] = Piece(PieceType.ROOK, PieceColor.RED)
        grid[7][1] = Piece(PieceType.CANNON, PieceColor.RED)
        grid[7][7] = Piece(PieceType.CANNON, PieceColor.RED)
        grid[6][0] = Piece(PieceType.PAWN, PieceColor.RED)
        grid[6][2] = Piece(PieceType.PAWN, PieceColor.RED)
        grid[6][4] = Piece(PieceType.PAWN, PieceColor.RED)
        grid[6][6] = Piece(PieceType.PAWN, PieceColor.RED)
        grid[6][8] = Piece(PieceType.PAWN, PieceColor.RED)

        currentPlayer = PieceColor.RED
        moveHistory.clear()
        uncheckedHistory.clear()
    }

    fun getPiece(pos: Position): Piece? {
        if (!pos.isValid()) return null
        return grid[pos.row][pos.col]
    }

    fun setPiece(pos: Position, piece: Piece?) {
        if (pos.isValid()) grid[pos.row][pos.col] = piece
    }

    /** 按行列直接读子（供 OBK 直接哈希查库等外部编码用） */
    fun pieceAt(row: Int, col: Int): Piece? {
        if (row !in 0..9 || col !in 0..8) return null
        return grid[row][col]
    }

    /**
     * 清空棋盘（用于自定义摆子模式）
     */
    fun clearBoard() {
        for (r in 0..9) for (c in 0..8) grid[r][c] = null
        moveHistory.clear()
        uncheckedHistory.clear()
    }

    /**
     * 统计棋盘上某方某种棋子的数量
     */
    fun countPieces(type: PieceType, color: PieceColor): Int {
        var count = 0
        for (r in 0..9) for (c in 0..8) {
            val p = grid[r][c]
            if (p != null && p.type == type && p.color == color) count++
        }
        return count
    }

    /**
     * 统计棋盘上某方所有棋子数量
     */
    fun countAllPieces(color: PieceColor): Map<PieceType, Int> {
        val counts = mutableMapOf<PieceType, Int>()
        for (type in PieceType.values()) counts[type] = 0
        for (r in 0..9) for (c in 0..8) {
            val p = grid[r][c]
            if (p != null && p.color == color) {
                counts[p.type] = (counts[p.type] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * 执行走法（严格校验：必须在合法走法列表中）
     */
    fun makeMove(move: Move): Boolean {
        val piece = getPiece(move.from) ?: return false
        if (piece.color != currentPlayer) return false

        // 核心约束：必须在 getLegalMoves() 生成的合法走法列表中
        // 防止人脑"天马行空"乱走——每步都必须遵守棋子规则 + 不能送将
        val legalMoves = getLegalMoves(currentPlayer)
        if (!legalMoves.any { it.from == move.from && it.to == move.to }) {
            return false
        }

        val captured = getPiece(move.to)
        setPiece(move.to, piece)
        setPiece(move.from, null)

        val actualMove = Move(move.from, move.to, piece, captured)
        moveHistory.add(actualMove)
        currentPlayer = currentPlayer.opponent()
        return true
    }

    /**
     * 悔棋
     */
    fun undoMove(): Move? {
        if (moveHistory.isEmpty()) return null
        val lastMove = moveHistory.removeAt(moveHistory.size - 1)
        setPiece(lastMove.from, lastMove.piece)
        setPiece(lastMove.to, lastMove.captured)
        currentPlayer = currentPlayer.opponent()
        return lastMove
    }

    /**
     * 无校验走法（供 AI 搜索使用，不检查合法性，不记录正式历史）
     */
    fun makeMoveUnchecked(move: Move) {
        val captured = getPiece(move.to)
        uncheckedHistory.add(Triple(move.from, move.to, captured))
        setPiece(move.to, move.piece)
        setPiece(move.from, null)
        currentPlayer = currentPlayer.opponent()
    }

    /**
     * 无校验悔棋（供 AI 搜索回溯使用）
     */
    fun undoMoveUnchecked() {
        if (uncheckedHistory.isEmpty()) return
        val (from, to, captured) = uncheckedHistory.removeAt(uncheckedHistory.size - 1)
        val piece = getPiece(to) ?: return
        setPiece(from, piece)
        setPiece(to, captured)
        currentPlayer = currentPlayer.opponent()
    }

    /**
     * 获取所有合法走法
     * ★ 线程安全铁律（"凭空多子"根治）：本方法【绝不写当前棋盘】——走法模拟一律在
     *   私有副本上做"模拟→恢复"。此前直接在 this.grid 上模拟：询招沙盒问答线程与
     *   主线程一旦同时对同一个活棋盘调用（人脑落子校验 / AI 落子校验 / 合法着法查询），
     *   两边的 setPiece 写入交错，就会把同一枚棋子同时留在"起点"和"终点"两个格子上——
     *   凭空多出一枚"真能走、能吃子、悔棋才消失"的车/马/炮（快照与棋谱都干净，
     *   唯独活棋盘被污染）。改成副本模拟后，并发调用彼此互不可见、互不干扰。
     */
    fun getLegalMoves(color: PieceColor): List<Move> {
        val sim = clone()
        val moves = mutableListOf<Move>()
        for (r in 0..9) {
            for (c in 0..8) {
                val piece = sim.grid[r][c] ?: continue
                if (piece.color != color) continue
                val from = Position(r, c)
                val rawMoves = sim.getRawMoves(from, piece)
                for (to in rawMoves) {
                    // 模拟走法，检查是否会导致己方被将军
                    val captured = sim.getPiece(to)
                    sim.setPiece(to, piece)
                    sim.setPiece(from, null)
                    if (!sim.isInCheck(color)) {
                        moves.add(Move(from, to, piece, captured))
                    }
                    // 恢复
                    sim.setPiece(from, piece)
                    sim.setPiece(to, captured)
                }
            }
        }
        return moves
    }

    /**
     * 获取指定棋子的原始走法（不考虑将军）
     */
    private fun getRawMoves(from: Position, piece: Piece): List<Position> {
        return when (piece.type) {
            PieceType.KING -> getKingMoves(from, piece.color)
            PieceType.ADVISOR -> getAdvisorMoves(from, piece.color)
            PieceType.ELEPHANT -> getElephantMoves(from, piece.color)
            PieceType.KNIGHT -> getKnightMoves(from, piece.color)
            PieceType.ROOK -> getRookMoves(from, piece.color)
            PieceType.CANNON -> getCannonMoves(from, piece.color)
            PieceType.PAWN -> getPawnMoves(from, piece.color)
        }
    }

    private fun getKingMoves(from: Position, color: PieceColor): List<Position> {
        val moves = mutableListOf<Position>()
        val dirs = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        val (minRow, maxRow) = if (color == PieceColor.RED) 7 to 9 else 0 to 2
        for ((dr, dc) in dirs) {
            val nr = from.row + dr
            val nc = from.col + dc
            if (nr in minRow..maxRow && nc in 3..5) {
                val target = grid[nr][nc]
                if (target == null || target.color != color) {
                    moves.add(Position(nr, nc))
                }
            }
        }
        // 将帅对面（飞将）—— 在同列且中间无棋子
        moves.addAll(getFlyingGeneralMoves(from, color))
        return moves
    }

    private fun getFlyingGeneralMoves(from: Position, color: PieceColor): List<Position> {
        val moves = mutableListOf<Position>()
        // 查找对方将/帅
        for (r in 0..9) {
            val piece = grid[r][from.col]
            if (piece != null && piece.type == PieceType.KING && piece.color != color) {
                // 检查中间是否有棋子
                val minR = minOf(from.row, r)
                val maxR = maxOf(from.row, r)
                var blocked = false
                for (mr in minR + 1 until maxR) {
                    if (grid[mr][from.col] != null) {
                        blocked = true
                        break
                    }
                }
                if (!blocked) {
                    moves.add(Position(r, from.col))
                }
                break
            }
        }
        return moves
    }

    private fun getAdvisorMoves(from: Position, color: PieceColor): List<Position> {
        val moves = mutableListOf<Position>()
        val dirs = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        val (minRow, maxRow) = if (color == PieceColor.RED) 7 to 9 else 0 to 2
        for ((dr, dc) in dirs) {
            val nr = from.row + dr
            val nc = from.col + dc
            if (nr in minRow..maxRow && nc in 3..5) {
                val target = grid[nr][nc]
                if (target == null || target.color != color) {
                    moves.add(Position(nr, nc))
                }
            }
        }
        return moves
    }

    private fun getElephantMoves(from: Position, color: PieceColor): List<Position> {
        val moves = mutableListOf<Position>()
        val dirs = listOf(2 to 2, 2 to -2, -2 to 2, -2 to -2)
        val eyes = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        val (minRow, maxRow) = if (color == PieceColor.RED) 5 to 9 else 0 to 4
        for (i in dirs.indices) {
            val (dr, dc) = dirs[i]
            val (er, ec) = eyes[i]
            val nr = from.row + dr
            val nc = from.col + dc
            val eyeR = from.row + er
            val eyeC = from.col + ec
            // 象眼不能被堵
            if (nr in minRow..maxRow && nc in 0..8 && grid[eyeR][eyeC] == null) {
                val target = grid[nr][nc]
                if (target == null || target.color != color) {
                    moves.add(Position(nr, nc))
                }
            }
        }
        return moves
    }

    private fun getKnightMoves(from: Position, color: PieceColor): List<Position> {
        val moves = mutableListOf<Position>()
        // 马走日字，蹩脚检查
        val knightMoves = arrayOf(
            // dr, dc, legR, legC (蹩脚位置)
            intArrayOf(-2, -1, -1, 0),
            intArrayOf(-2, 1, -1, 0),
            intArrayOf(2, -1, 1, 0),
            intArrayOf(2, 1, 1, 0),
            intArrayOf(-1, -2, 0, -1),
            intArrayOf(-1, 2, 0, 1),
            intArrayOf(1, -2, 0, -1),
            intArrayOf(1, 2, 0, 1)
        )
        for (km in knightMoves) {
            val nr = from.row + km[0]
            val nc = from.col + km[1]
            val legR = from.row + km[2]
            val legC = from.col + km[3]
            if (nr in 0..9 && nc in 0..8 && grid[legR][legC] == null) {
                val target = grid[nr][nc]
                if (target == null || target.color != color) {
                    moves.add(Position(nr, nc))
                }
            }
        }
        return moves
    }

    private fun getRookMoves(from: Position, color: PieceColor): List<Position> {
        val moves = mutableListOf<Position>()
        val dirs = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        for ((dr, dc) in dirs) {
            var nr = from.row + dr
            var nc = from.col + dc
            while (nr in 0..9 && nc in 0..8) {
                val target = grid[nr][nc]
                if (target == null) {
                    moves.add(Position(nr, nc))
                } else {
                    if (target.color != color) moves.add(Position(nr, nc))
                    break
                }
                nr += dr
                nc += dc
            }
        }
        return moves
    }

    private fun getCannonMoves(from: Position, color: PieceColor): List<Position> {
        val moves = mutableListOf<Position>()
        val dirs = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        for ((dr, dc) in dirs) {
            var nr = from.row + dr
            var nc = from.col + dc
            // 移动：不翻山
            while (nr in 0..9 && nc in 0..8) {
                if (grid[nr][nc] == null) {
                    moves.add(Position(nr, nc))
                } else {
                    // 吃子：翻山后吃第一个遇到的敌方棋子
                    var cr = nr + dr
                    var cc = nc + dc
                    while (cr in 0..9 && cc in 0..8) {
                        val target = grid[cr][cc]
                        if (target != null) {
                            if (target.color != color) moves.add(Position(cr, cc))
                            break
                        }
                        cr += dr
                        cc += dc
                    }
                    break
                }
                nr += dr
                nc += dc
            }
        }
        return moves
    }

    private fun getPawnMoves(from: Position, color: PieceColor): List<Position> {
        val moves = mutableListOf<Position>()
        val forward = if (color == PieceColor.RED) -1 else 1
        val hasCrossedRiver = if (color == PieceColor.RED) from.row <= 4 else from.row >= 5

        // 向前
        val nr = from.row + forward
        if (nr in 0..9) {
            val target = grid[nr][from.col]
            if (target == null || target.color != color) {
                moves.add(Position(nr, from.col))
            }
        }

        // 过河后可以左右移动
        if (hasCrossedRiver) {
            for (dc in intArrayOf(-1, 1)) {
                val nc = from.col + dc
                if (nc in 0..8) {
                    val target = grid[from.row][nc]
                    if (target == null || target.color != color) {
                        moves.add(Position(from.row, nc))
                    }
                }
            }
        }
        return moves
    }

    /**
     * 判断是否被将军
     */
    fun isInCheck(color: PieceColor): Boolean {
        // 找到该方的将/帅位置
        val kingPos = findKing(color) ?: return false
        // 检查对方所有棋子是否能攻击到将/帅
        val opponent = color.opponent()
        for (r in 0..9) {
            for (c in 0..8) {
                val piece = grid[r][c] ?: continue
                if (piece.color != opponent) continue
                val rawMoves = getRawMoves(Position(r, c), piece)
                if (rawMoves.any { it == kingPos }) return true
            }
        }
        return false
    }

    /**
     * 判断是否被将死
     */
    fun isCheckmate(color: PieceColor): Boolean {
        return isInCheck(color) && getLegalMoves(color).isEmpty()
    }

    /**
     * 判断是否困毙（无子可走）
     */
    fun isStalemate(color: PieceColor): Boolean {
        return !isInCheck(color) && getLegalMoves(color).isEmpty()
    }

    fun findKing(color: PieceColor): Position? {
        for (r in 0..9) {
            for (c in 0..8) {
                val piece = grid[r][c]
                if (piece != null && piece.type == PieceType.KING && piece.color == color) {
                    return Position(r, c)
                }
            }
        }
        return null
    }

    /**
     * 生成FEN字符串
     */
    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 0..9) {
            var emptyCount = 0
            for (c in 0..8) {
                val piece = grid[r][c]
                if (piece == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    val fenChar = piece.type.fenChar
                    sb.append(if (piece.color == PieceColor.RED) fenChar.uppercaseChar() else fenChar)
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            if (r < 9) sb.append('/')
        }
        sb.append(' ')
        sb.append(if (currentPlayer == PieceColor.RED) 'w' else 'b')
        sb.append(" - - 0 1")
        return sb.toString()
    }

    /**
     * 从FEN加载棋盘
     * ★ 防御性解析：遇到无效 FEN 时回退到标准初始局面，防止空棋盘
     */
    fun loadFromFen(fen: String) {
        // 清空
        for (r in 0..9) for (c in 0..8) grid[r][c] = null
        moveHistory.clear()
        uncheckedHistory.clear()

        try {
            val trimmed = fen.trim()
            if (trimmed.isEmpty()) {
                Log.w("Board", "loadFromFen: empty FEN, resetting to initial position")
                reset()
                return
            }

            val parts = trimmed.split(' ')
            val rows = parts[0].split('/')

            // 验证：必须有 10 行
            if (rows.size < 10) {
                Log.w("Board", "loadFromFen: invalid FEN (rows=${rows.size}), resetting. fen=$fen")
                reset()
                return
            }

            for (r in 0..9) {
                var c = 0
                for (ch in rows[r]) {
                    if (c > 8) break  // 防止越界
                    if (ch.isDigit()) {
                        c += ch.toString().toInt()
                    } else {
                        val color = if (ch.isUpperCase()) PieceColor.RED else PieceColor.BLACK
                        val type = try { PieceType.fromFenChar(ch) } catch (_: Exception) { null }
                        if (type != null && c in 0..8) {
                            grid[r][c] = Piece(type, color)
                        }
                        c++
                    }
                }
            }

            // 验证：棋盘上至少要有两个将/帅
            val hasRedKing = findKing(PieceColor.RED) != null
            val hasBlackKing = findKing(PieceColor.BLACK) != null
            if (!hasRedKing || !hasBlackKing) {
                Log.w("Board", "loadFromFen: missing kings (red=$hasRedKing, black=$hasBlackKing), resetting. fen=$fen")
                reset()
                return
            }

            currentPlayer = if (parts.size > 1 && parts[1] == "b") PieceColor.BLACK else PieceColor.RED
        } catch (e: Exception) {
            Log.w("Board", "loadFromFen: exception, resetting. fen=$fen, error=${e.message}")
            reset()
        }
    }

    fun clone(): Board {
        val newBoard = Board()
        for (r in 0..9) for (c in 0..8) newBoard.grid[r][c] = this.grid[r][c]
        newBoard.currentPlayer = this.currentPlayer
        newBoard.moveHistory.addAll(this.moveHistory)
        newBoard.uncheckedHistory.addAll(this.uncheckedHistory)
        return newBoard
    }

    fun getMoveHistory(): List<Move> = moveHistory.toList()
}