package com.pikafish.chess

import android.app.Application
import org.junit.Test
import java.io.File

/**
 * 探针：把真实 .xqf 丢进 ChessGame.importXqf，量一量
 * 能否解出、解出多少手、快照/步数是否与棋谱一致（针对“宝库能识别、主界面 0/0 回合点不动”）。
 */
class XqfProbeTest {
    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { val f = c.getDeclaredField(name); f.isAccessible = true; return f.get(o) } catch (_: Throwable) {}
            c = c.superclass
        }
        return null
    }

    @Test
    fun probe() {
        val f = File("/workspace/.uploads/1a4b2994-12a3-4830-8631-291c6b06f2ae_孙晓东[钻石3星] 先负 竹子开局-刘霸天[钻石2星](1).xqf")
        println("=== exists=${f.exists()} size=${f.length()} ===")
        if (!f.exists()) return
        val bytes = f.readBytes()
        val g = ChessGame(Application())
        val t0 = System.currentTimeMillis()
        val msg = g.importXqf(bytes)
        val ms = System.currentTimeMillis() - t0
        println("importXqf => $msg  (${ms}ms)")
        val moves = field(g, "moveHistoryUci") as? List<*> ?: emptyList<Any>()
        val snaps = field(g, "boardSnapshots") as? List<*> ?: emptyList<Any>()
        println("getMoveCount=${g.getMoveCount()}  currentStep=${g.currentStep}")
        println("moveHistoryUci.size=${moves.size}")
        println("boardSnapshots.size=${snaps.size}")
        println("moves=${moves.take(12)}")
        println("movesTail=${moves.takeLast(6)}")
        println("snap0=${snaps.getOrNull(0)}")
        println("snap1=${snaps.getOrNull(1)}")

        // ★ 关键：宝库/我的上传走的其实是 decodeGames 这条链路。看它对 XQF 会不会"错误地"吐出棋局。
        val games = com.pikafish.chess.qipu.QipuLibrary.decodeGames(bytes, f.name)
        println("decodeGames => ${games.size} 局")
        games.take(5).forEach { gg ->
            println("   [${gg.meta().index}] title=${gg.title()} moves=${gg.cblGame.movesUci.size}")
        }

        // ★★ 回归：openBookBytes 导入后还会调一次 cancelSetup()（退出摆子）。
        //   旧实现里"不在摆子模式"会走"恢复标准开局"分支，把刚导入的棋谱整盘抹掉 → 0/0 回合、进退键全灰。
        g.cancelSetup()
        val movesAfter = (field(g, "moveHistoryUci") as? List<*>)?.size ?: -1
        val snapsAfter = (field(g, "boardSnapshots") as? List<*>)?.size ?: -1
        println("cancelSetup 后：moveCount=${g.getMoveCount()} moves=$movesAfter snaps=$snapsAfter")
        check(movesAfter == 80 && snapsAfter == 81) {
            "cancelSetup() 把刚导入的棋谱抹掉了（moves=$movesAfter snaps=$snapsAfter），应保持 80/81"
        }
    }
}
