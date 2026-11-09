package com.pikafish.chess.qipu

import org.junit.Test
import java.io.File

/** 临时探针：验证挑战棋谱 txt（PGN+中文记谱）能否被宝库识别并解出实着。 */
class ChallengeTxtProbeTest {
    private val dir = File("/workspace/work/ch_test/挑战棋谱")

    @Test
    fun probe() {
        val files = dir.listFiles()?.filter { it.name.endsWith(".txt") }?.sorted() ?: emptyList()
        println("挑战棋谱文件数=${files.size}")
        var ok = 0
        for (f in files) {
            val bytes = f.readBytes()
            val games = try { QipuLibrary.decodeGames(bytes, f.name) } catch (e: Exception) { emptyList() }
            val head = games.firstOrNull()?.cblGame?.movesUci?.take(6)
            val moves = games.sumOf { it.cblGame.movesUci.size }
            if (games.isNotEmpty() && moves > 0) ok++
            println("=== ${f.name} (${f.length()}B) -> ${games.size} 局, 首局步数=${games.firstOrNull()?.cblGame?.movesUci?.size} ===")
            println("    isPgnLike=${PgnTextDecoder.isPgnLike(bytes)} isFenBatch=${QipuLibrary.isFenBatch(bytes)}")
            println("    前6手=$head")
        }
        println(">>> 成功识别并解出实着的文件数=$ok / ${files.size}")

        // ★ 打包成 ZIP（模拟上传到宝库）再走一遍压缩包条目分流
        val zip = File("/tmp/challenge_zip_probe.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            for (f in files) {
                zos.putNextEntry(java.util.zip.ZipEntry("挑战棋谱/${f.name}"))
                zos.write(f.readBytes()); zos.closeEntry()
            }
        }
        val zGames = QipuLibrary.decodeGames(zip.readBytes(), "challenge_zip_probe.zip")
        val zWithMoves = zGames.count { it.cblGame.movesUci.isNotEmpty() }
        println(">>> ZIP 包解出=${zGames.size} 局，其中含实着=${zWithMoves}")
    }
}
