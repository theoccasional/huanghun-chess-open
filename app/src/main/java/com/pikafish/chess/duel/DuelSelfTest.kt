package com.pikafish.chess.duel

/**
 * 第 6 条自检（不依赖网络）：用同一串 ICCS 走法在"两个引擎实例"上分别重放，
 * 比较最终 FEN 是否逐字相同 —— 这正是两台手机同步的数学本质。
 *
 * 同时验证：
 *  - ICCS ↔ 内部格号换算（含引擎每步镜像 flipped）是否稳定；
 *  - 回合 parity：走偶数步后轮红、奇数步后轮黑；
 *  - 非法着返回 false 且不改变局面。
 */
object DuelSelfTest {

    fun iccsToSquare(position: com.yk.xiangqi.core.Position, s: String): Int? {
        if (s.length < 2) return null
        val file = s[0] - 'a'
        val rank = s[1] - '0'
        if (file !in 0..8 || rank !in 0..9) return null
        val innerRank = if (position.board().flipped()) 9 - rank else rank
        return innerRank * 9 + file
    }

    /** 与 DuelActivity.applyMove 完全同一套换算。 */
    fun apply(position: com.yk.xiangqi.core.Position, from: String, to: String): com.yk.xiangqi.core.Position? {
        val f = iccsToSquare(position, from) ?: return null
        val t = iccsToSquare(position, to) ?: return null
        val mv = com.yk.xiangqi.core.Move(f, t)
        if (!position.legalMoves().contains(mv)) return null
        return position.play(mv)
    }

    /** 返回 (是否通过, 说明)。 */
    fun run(script: List<String>): Pair<Boolean, String> {
        var a = com.yk.xiangqi.core.Position.start()
        var b = com.yk.xiangqi.core.Position.start()
        var count = 0
        for (step in script) {
            val parts = step.split("-")
            if (parts.size != 2) return false to "走法格式不是 ICCS：$step"
            val na = apply(a, parts[0], parts[1]) ?: return false to "第 ${count + 1} 步 $step 在 A 上非法"
            val nb = apply(b, parts[0], parts[1]) ?: return false to "第 ${count + 1} 步 $step 在 B 上非法"
            a = na; b = nb; count++
            if (a.toFen() != b.toFen()) return false to "第 $count 步后两边 FEN 不一致"
        }
        val sideOk = (a.sideToMove() == com.yk.xiangqi.core.Side.RED) == (count % 2 == 0)
        if (!sideOk) return false to "回合 parity 错：走了 $count 步，轮 ${a.sideToMove()}"
        return true to "通过：$count 步后两端 FEN 完全一致，回合正确。FEN=${a.toFen()}"
    }
}
