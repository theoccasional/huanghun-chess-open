package com.pikafish.chess.qipu

import org.junit.Test
import java.io.File

/**
 * 探针：把用户这几份"宝库上传认不出"的真实包丢进 QipuLibrary.decodeGames，
 * 量一量 RAR(RAR4/RAR5) 与 CHM 支不支持、各能解出多少局。
 */
class ChmRarProbeTest {

    private val samples = listOf(
        "81f5bdaf-4237-4d90-a47d-cc6703e24222_象棋攻防新战术（言穆江）cbl.rar",
        "36f1021a-6af2-4a0b-96af-9b815904b022_[2020年第八届财神杯视频象棋快棋赛]CHM金华制作.rar",
        "b8af8ebb-bba0-4867-9e0c-0166294ca223_[中国象棋小寒秋著]CHM金华制作.rar",
        "5fddd24a-6571-42a7-8aff-c6027b8fda1b_[2020年第八届财神杯视频象棋快棋赛76]CHM金华制作.rar"
    )

    @Test
    fun probeAll() {
        for (name in samples) {
            val f = File("/workspace/.uploads/$name")
            println("==============================================")
            println("样本: $name  exists=${f.exists()} size=${f.length()}")
            if (!f.exists()) continue
            val raw = f.readBytes()
            println("isRar=${QipuLibrary.isRar(raw)} isZip=${QipuLibrary.isZip(raw)} isChm=${QipuLibrary.isChm(raw)}")
            val t0 = System.currentTimeMillis()
            val games = try { QipuLibrary.decodeGames(raw, name) } catch (t: Throwable) {
                println("decodeGames 抛异常: $t"); t.printStackTrace(); continue
            }
            val ms = System.currentTimeMillis() - t0
            println("=> 解出 ${games.size} 局（${ms}ms）")
            games.take(8).forEach { g ->
                val full = g.cblGame
                println("   [${g.meta().index}] ${g.title()}  主线=${full.movesUci.size}手  变着=${full.variationsUci.size}条  fen=${full.fenBoard ?: "标准"}  黑先=${full.moversBlack}")
            }
        }
    }

    @Test
    fun probeChmDirect() {
        val f = File("/workspace/tmp_rar/ex/[中国象棋小寒秋著]CHM金华制作.CHM")
        if (!f.exists()) { println("缺 CHM 样本"); return }
        val raw = f.readBytes()
        println("direct CHM isChm=${QipuLibrary.isChm(raw)}")
        val games = QipuLibrary.decodeGames(raw, f.name)
        println("=> 解出 ${games.size} 局")
        games.take(10).forEach { g ->
            println("   ${g.title()}  ${g.cblGame.movesUci.size}手 变着${g.cblGame.variationsUci.size}条 评注${g.cblGame.moveComments.size}条")
        }
    }
}
