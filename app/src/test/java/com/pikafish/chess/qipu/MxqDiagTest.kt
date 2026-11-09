package com.pikafish.chess.qipu

import org.junit.Test
import java.io.File

/** 端到端验证：真实 MXQ 压缩包能否被"自力更生"解干净（识别 + 元信息 + 逐着 + PGN）。 */
class MxqDiagTest {
    @Test
    fun diag() {
        val f = File("/workspace/.uploads/8a6cc131-327a-4069-b8bd-7d3ea4e6f0f1_2022年2月份华山和棋棋谱.zip")
        val raw = f.readBytes()
        println("size=${raw.size} isZip=${QipuLibrary.isZip(raw)}")

        val t0 = System.currentTimeMillis()
        val games = QipuLibrary.decodeGames(raw, f.name)
        val msMeta = System.currentTimeMillis() - t0
        println("解包得 ${games.size} 局，建列表(仅头部元信息) 用时 ${msMeta}ms")
        games.take(3).forEach { println("   [${it.meta().index}] ${it.title()}  date=${it.meta().date} result=${it.meta().result}") }
        println("   末局：${games.lastOrNull()?.title()}")

        // 逐着全量解码抽查：前 80 + 后 80 局
        val idxs = (0 until minOf(80, games.size)) + (maxOf(0, games.size - 80) until games.size)
        var empty = 0; var total = 0; var minM = Int.MAX_VALUE; var maxM = 0
        val t1 = System.currentTimeMillis()
        for (i in idxs) {
            val g = games[i]
            val cg = g.cblGame
            if (cg.movesUci.isEmpty()) { empty++; println("   !! 局 $i 解出 0 着：${g.title()}") }
            total += cg.movesUci.size
            if (cg.movesUci.size < minM) minM = cg.movesUci.size
            if (cg.movesUci.size > maxM) maxM = cg.movesUci.size
            g.pgn()
        }
        println("逐着抽查 ${idxs.size} 局：总着法=$total 每局[$minM..$maxM] 空着局数=$empty 用时=${System.currentTimeMillis()-t1}ms")
        println("样例 UCI：${games[0].cblGame.movesUci.take(8)}")
        println("样例 第1局前3手可读：${games[0].cblGame.movesUci.take(3)}")
    }
}
