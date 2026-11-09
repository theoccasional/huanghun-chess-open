package com.pikafish.chess.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：黑方「一将一捉」（车一将、一捉无根马），红方全程支士/落士（闲着）。
 * 曾经因为「被将军时合法着法只剩应将着」，红方支士解将后的攻击集合差集凭空变大，
 * 被误判成红方也在捉 → 双方都有攻击 → 拖到 4 次重复判和。
 * 正确结果：黑方长打（照捉混合），必须变着。
 */
class RepetitionRuleChaseTest {

    private val moves = listOf(
        "b2e2", "b9c7", "b0c2", "c6c5", "g3g4", "h7h3", "a0b0", "a9b9", "h0g2", "h9g7",
        "g2h4", "b7b1", "i0i1", "b1b3", "f0e1", "i9i8", "i1f1", "i8d8", "f1f7", "h3c3",
        "f7g7", "c5c4", "g7g9", "b3e3", "c0a2", "b9b0", "c2b0", "c3i3", "h4g2", "i3i0",
        "g0i2", "c7e8", "g9g8", "d8d2", "h2h9", "e8g9", "g8g9", "d2e2", "g9g6", "f9e8",
        "g2e3", "e2e3", "g6h6", "e3b3", "b0c2", "b3c3", "h6h2", "c3g3", "e0f0", "g3f3",
        "e1f2", "f3c3", "f2e1", "c3f3", "e1f2", "f3c3", "f2e1", "c3f3", "e1f2", "f3b3",
        "f2e1", "b3f3", "e1f2", "f3c3", "f2e1", "c3f3"
    )

    @Test
    fun oneCheckOneChase_blackMustChange() {
        val board = Board()
        val snapshots = arrayListOf(board.toFen())
        val played = ArrayList<String>()
        val rule = RepetitionRule()
        var sawBlackMustChange = false

        moves.forEachIndexed { index, uci ->
            val before = Board().apply { loadFromFen(snapshots.last()) }
            val move = requireNotNull(Move.fromUci(uci, before)) { "无法解析 $uci" }
            board.makeMoveUnchecked(move)
            played.add(uci)
            snapshots.add(board.toFen())

            when (val v = rule.checkRepetition(snapshots.toList(), played.toList(), null)) {
                is RepetitionRule.Verdict.MustChange -> {
                    assertEquals("第${index + 1}手判违规方", PieceColor.BLACK, v.violator)
                    sawBlackMustChange = true
                }
                is RepetitionRule.Verdict.ForcedLoss ->
                    assertEquals("第${index + 1}手判负方", PieceColor.BLACK, v.loser)
                is RepetitionRule.Verdict.ForcedDraw ->
                    throw AssertionError("第${index + 1}手不应判和：${v.reason}")
                else -> Unit
            }
        }

        assertTrue("应判定黑方长打（照捉混合）必须变着", sawBlackMustChange)
    }
}
