package com.pikafish.chess.model

/**
 * 棋子颜色
 */
enum class PieceColor {
    RED, BLACK;

    fun opponent(): PieceColor = if (this == RED) BLACK else RED

    fun toChar(): Char = if (this == RED) 'w' else 'b'

    companion object {
        fun fromChar(c: Char): PieceColor = if (c == 'w' || c == 'r') RED else BLACK
    }
}

/**
 * 棋子类型
 */
enum class PieceType(val chineseName: String, val fenChar: Char) {
    KING("将/帅", 'k'),      // 将帅
    ADVISOR("士/仕", 'a'),   // 士仕
    ELEPHANT("象/相", 'b'),  // 象相 (皮卡鱼标准: b = bishop)
    KNIGHT("马", 'n'),       // 马
    ROOK("车", 'r'),         // 车
    CANNON("炮", 'c'),       // 炮
    PAWN("兵/卒", 'p');      // 兵卒

    companion object {
        fun fromFenChar(c: Char): PieceType = when (c.lowercaseChar()) {
            'k' -> KING
            'a' -> ADVISOR
            'e', 'b' -> ELEPHANT
            'n', 'h' -> KNIGHT
            'r' -> ROOK
            'c' -> CANNON
            'p' -> PAWN
            else -> throw IllegalArgumentException("Unknown piece FEN char: $c")
        }
    }
}

/**
 * 棋子
 */
data class Piece(
    val type: PieceType,
    val color: PieceColor
) {
    fun displayName(): String = when (color) {
        PieceColor.RED -> when (type) {
            PieceType.KING -> "帅"
            PieceType.ADVISOR -> "仕"
            PieceType.ELEPHANT -> "相"
            PieceType.KNIGHT -> "馬"
            PieceType.ROOK -> "車"
            PieceType.CANNON -> "炮"
            PieceType.PAWN -> "兵"
        }
        PieceColor.BLACK -> when (type) {
            PieceType.KING -> "将"
            PieceType.ADVISOR -> "士"
            PieceType.ELEPHANT -> "象"
            PieceType.KNIGHT -> "马"
            PieceType.ROOK -> "车"
            PieceType.CANNON -> "砲"
            PieceType.PAWN -> "卒"
        }
    }
}