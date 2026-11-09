package com.pikafish.chess.model

/**
 * 棋盘位置 (行, 列), row: 0-9, col: 0-8
 */
data class Position(val row: Int, val col: Int) {
    companion object {
        fun fromUci(uci: String): Position {
            // UCI format: a0-i9, where 'a'=col 0, '0'=row 9
            val col = uci[0] - 'a'
            val row = 9 - (uci[1] - '0')
            return Position(row, col)
        }
    }

    fun toUci(): String {
        val colChar = 'a' + col
        val rowChar = '0' + (9 - row)
        return "$colChar$rowChar"
    }

    fun isValid(): Boolean = row in 0..9 && col in 0..8
}

/**
 * 走法
 */
data class Move(
    val from: Position,
    val to: Position,
    val piece: Piece,
    val captured: Piece? = null
) {
    fun toUci(): String = "${from.toUci()}${to.toUci()}"

    companion object {
        fun fromUci(uci: String, board: Board): Move? {
            if (uci.length < 4) return null
            val from = Position.fromUci(uci.substring(0, 2))
            val to = Position.fromUci(uci.substring(2, 4))
            val piece = board.getPiece(from) ?: return null
            val captured = board.getPiece(to)
            return Move(from, to, piece, captured)
        }
    }
}