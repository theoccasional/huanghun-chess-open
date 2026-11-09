package com.pikafish.chess.model

/**
 * 自定义摆子时的位置合法性校验器
 *
 * 约束规则：
 *  - 帅/将：只能放在己方九宫（3×3）
 *  - 仕/士：只能放在己方九宫的5个斜角点
 *  - 相/象：只能放在己方半场的7个象位点
 *  - 兵/卒：起始位置、前进一步、过河后任意位置
 *  - 车/马/炮：任意位置
 *  - 每种棋子数量不超过标准上限
 */
object SetupValidator {

    // ── 红方（下方，坐标系：中文数字一～九，row 0-9 从上到下） ──

    /** 帅（红方）合法位置：rows 7-9, cols 3-5 */
    val RED_KING_POSITIONS: Set<Position> = buildSet {
        for (r in 7..9) for (c in 3..5) add(Position(r, c))
    }

    /** 仕（红方）合法位置：5个斜角点 */
    val RED_ADVISOR_POSITIONS: Set<Position> = setOf(
        Position(7, 3), Position(7, 5),
        Position(8, 4),
        Position(9, 3), Position(9, 5)
    )

    /** 相（红方）合法位置：7个象位点 */
    val RED_ELEPHANT_POSITIONS: Set<Position> = setOf(
        Position(5, 2), Position(5, 6),
        Position(7, 0), Position(7, 4), Position(7, 8),
        Position(9, 2), Position(9, 6)
    )

    /** 兵（红方）合法位置 */
    val RED_PAWN_POSITIONS: Set<Position> = buildSet {
        // 起始位置
        add(Position(6, 0)); add(Position(6, 2)); add(Position(6, 4))
        add(Position(6, 6)); add(Position(6, 8))
        // 前进一步
        add(Position(5, 0)); add(Position(5, 2)); add(Position(5, 4))
        add(Position(5, 6)); add(Position(5, 8))
        // 过河后（rows 0-4）：任意位置
        for (r in 0..4) for (c in 0..8) add(Position(r, c))
    }

    // ── 黑方（上方，坐标系：阿拉伯数字1～9） ──

    /** 将（黑方）合法位置：rows 0-2, cols 3-5 */
    val BLACK_KING_POSITIONS: Set<Position> = buildSet {
        for (r in 0..2) for (c in 3..5) add(Position(r, c))
    }

    /** 士（黑方）合法位置：5个斜角点 */
    val BLACK_ADVISOR_POSITIONS: Set<Position> = setOf(
        Position(0, 3), Position(0, 5),
        Position(1, 4),
        Position(2, 3), Position(2, 5)
    )

    /** 象（黑方）合法位置：7个象位点 */
    val BLACK_ELEPHANT_POSITIONS: Set<Position> = setOf(
        Position(0, 2), Position(0, 6),
        Position(2, 0), Position(2, 4), Position(2, 8),
        Position(4, 2), Position(4, 6)
    )

    /** 卒（黑方）合法位置 */
    val BLACK_PAWN_POSITIONS: Set<Position> = buildSet {
        // 起始位置
        add(Position(3, 0)); add(Position(3, 2)); add(Position(3, 4))
        add(Position(3, 6)); add(Position(3, 8))
        // 前进一步
        add(Position(4, 0)); add(Position(4, 2)); add(Position(4, 4))
        add(Position(4, 6)); add(Position(4, 8))
        // 过河后（rows 5-9）：任意位置
        for (r in 5..9) for (c in 0..8) add(Position(r, c))
    }

    /** 每种棋子每方最大数量 */
    val MAX_COUNTS: Map<PieceType, Int> = mapOf(
        PieceType.KING to 1,
        PieceType.ADVISOR to 2,
        PieceType.ELEPHANT to 2,
        PieceType.ROOK to 2,
        PieceType.KNIGHT to 2,
        PieceType.CANNON to 2,
        PieceType.PAWN to 5
    )

    /**
     * 检查某个棋子能否摆放到指定位置。
     * @return null 表示可以摆放，否则返回错误信息
     */
    fun canPlacePiece(type: PieceType, color: PieceColor, pos: Position, board: Board): String? {
        if (!pos.isValid()) return "坐标无效"

        // 该位置是否已被占用
        val existing = board.getPiece(pos)
        if (existing != null) {
            return "此位置已有棋子（${existing.displayName()}）"
        }

        // 检查数量上限
        val currentCount = board.countPieces(type, color)
        val maxCount = MAX_COUNTS[type] ?: 0
        if (currentCount >= maxCount) {
            return when (type) {
                PieceType.KING -> "每方只能有1个${if (color == PieceColor.RED) "帅" else "将"}"
                PieceType.ADVISOR -> "每方最多2个${if (color == PieceColor.RED) "仕" else "士"}"
                PieceType.ELEPHANT -> "每方最多2个${if (color == PieceColor.RED) "相" else "象"}"
                PieceType.ROOK -> "每方最多2个车"
                PieceType.KNIGHT -> "每方最多2个马"
                PieceType.CANNON -> "每方最多2个炮"
                PieceType.PAWN -> "每方最多5个${if (color == PieceColor.RED) "兵" else "卒"}"
            }
        }

        // 检查位置合法性
        return when (type) {
            PieceType.KING -> {
                val valid = if (color == PieceColor.RED) RED_KING_POSITIONS else BLACK_KING_POSITIONS
                if (pos !in valid) "帅/将必须放在己方九宫" else null
            }
            PieceType.ADVISOR -> {
                val valid = if (color == PieceColor.RED) RED_ADVISOR_POSITIONS else BLACK_ADVISOR_POSITIONS
                if (pos !in valid) "仕/士必须放在己方九宫斜角点" else null
            }
            PieceType.ELEPHANT -> {
                val valid = if (color == PieceColor.RED) RED_ELEPHANT_POSITIONS else BLACK_ELEPHANT_POSITIONS
                if (pos !in valid) "相/象必须放在己方半场象位点" else null
            }
            PieceType.PAWN -> {
                val valid = if (color == PieceColor.RED) RED_PAWN_POSITIONS else BLACK_PAWN_POSITIONS
                if (pos !in valid) {
                    "兵/卒只能放在起始位置、前进一步或过河后"
                } else null
            }
            // 车、马、炮：任意位置
            else -> null
        }
    }

    /**
     * 摆子完成后检查局面是否合法（不荒谬）。
     * 检查项：
     *  1. 双方必须都有帅/将
     *  2. 帅将不能对面（同列无阻隔）
     *  3. 当前走棋方不能已将死对方
     */
    fun validateSetupComplete(board: Board, currentPlayer: PieceColor): String? {
        val redKing = board.findKing(PieceColor.RED)
        val blackKing = board.findKing(PieceColor.BLACK)

        if (redKing == null) return "红方必须放置帅"
        if (blackKing == null) return "黑方必须放置将"

        // 检查帅将对面
        if (redKing.col == blackKing.col) {
            val minR = minOf(redKing.row, blackKing.row)
            val maxR = maxOf(redKing.row, blackKing.row)
            var blocked = false
            for (r in minR + 1 until maxR) {
                if (board.getPiece(Position(r, redKing.col)) != null) {
                    blocked = true
                    break
                }
            }
            if (!blocked) return "帅将不能对面（同列无阻隔）"
        }

        // 当前走棋方不能已将军对方
        if (board.isInCheck(currentPlayer)) {
            return "当前走棋方不能处于被将军状态"
        }

        return null
    }
}