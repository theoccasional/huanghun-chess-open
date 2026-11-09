package com.pikafish.chess.qipu

import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType

/**
 * EccoNamer —— 把棋局走法喂给 ECCO 编号体系，得出“这是什么开局/变例”。
 *
 * 工作分两步：
 *  1. UCI 走法 → C2.5 纵线记谱串（红黑各自 1..9 路、按“进/退/平”取步数或目标路）；
 *  2. C2.5 串 → ECCO 编号（判定逻辑），再由 [EccoDict] 反查开局名/变例名。
 *
 * 其中第 2 步的判定来自《象棋巫师》ECCO 官方判定逻辑（与 libecco64.so 对拍验证）。
 * 判定逻辑未覆盖、或无法可靠判定的路径返回 null，UI 层据此隐藏该行，绝不显示错名。
 */
object EccoNamer {

    private val LETTER = mapOf(
        PieceType.KING to 'K', PieceType.ADVISOR to 'A', PieceType.ELEPHANT to 'B',
        PieceType.ROOK to 'R', PieceType.KNIGHT to 'N', PieceType.CANNON to 'C', PieceType.PAWN to 'P'
    )

    /** 走一步棋转换出的 C2.5 记法；参数含起终点、棋子类型与颜色。无法识别返回 null。 */
    fun toC25(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int, type: PieceType, color: PieceColor): String? {
        val letter = LETTER[type] ?: return null
        // 路的编号按行棋一方角度：红方右→左为1..9；黑方右→左为1..9（黑方“右”即内坐标系col0一侧）
        val fromFile = if (color == PieceColor.RED) 9 - fromCol else fromCol + 1
        val toFile = if (color == PieceColor.RED) 9 - toCol else toCol + 1
        return if (fromRow == toRow) {
            // 平
            "$letter$fromFile.$toFile"
        } else {
            val plus = (color == PieceColor.RED && toRow < fromRow) ||
                    (color == PieceColor.BLACK && toRow > fromRow)
            val sign = if (plus) '+' else '-'
            val n = when (type) {
                PieceType.ROOK, PieceType.CANNON, PieceType.PAWN, PieceType.KING -> kotlin.math.abs(toRow - fromRow)
                else -> toFile // 马/相/士 用目标路命名
            }
            "$letter$fromFile$sign$n"
        }
    }

    /** 从已走 Move 列表得到整条 C2.5 串；任一步无法转记为 null。 */
    fun uciMovesToC25(moves: List<Move>): String? {
        val sb = StringBuilder()
        for (m in moves) {
            val t = toC25(m.from.row, m.from.col, m.to.row, m.to.col, m.piece.type, m.piece.color)
                ?: return null
            sb.append(t)
        }
        return sb.toString()
    }

    /** 由编号反查 “开局名|变例名”；EccoDict 值形如 “开局名||变例名”。 */
    fun nameOf(code: String): Pair<String?, String>? {
        val entry = EccoDict.MAP[code] ?: return null
        val split = entry.split("||")
        return split.getOrNull(0) to split.getOrNull(1).orEmpty()
    }

    /** 对给定 C2.5 走法串算 ECCO 编号；判定未覆盖返回 null（由 EccoIndex 填充）。返回形如 "C45"。 */
    fun eccoIndex(c25: String): String? = EccoIndex.eval(c25)

    /** 便捷入口：直接给 UCI 走法列表，返回 (编号, 开局名, 变例名)。 */
    data class NamingResult(val code: String, val opening: String, val variation: String)

    fun ofMoves(moves: List<Move>): NamingResult? {
        val c25 = uciMovesToC25(moves) ?: return null
        val code = eccoIndex(c25) ?: return null
        val (op, var_) = nameOf(code) ?: return null
        if (op.isNullOrBlank()) return null
        return NamingResult(code, op, var_)
    }
}