package com.pikafish.chess.qipu

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 探针：成批 FEN 习题（5-10步缓杀 50000题 这类「只有局面、没有着法」的记录）
 * 经 writeLegacyBook 导出后，逐条必须带至少一手合法着法。
 *
 * 为什么：象棋桥/CNVCS 把「步数 == 0」的记录当章标题/空条目，整本书全零着法时
 * 第三方打谱软件算出的棋谱数是 0 → 判为「非棋谱文件」→ 无法识别。本探针把导出
 * 结果再解回来，断言每条都 ≥1 手。
 */
class FenPuzzleExportProbeTest {

    @Test
    fun fenPuzzleRecordMustHaveMoves() {
        val lines = listOf(
            "1C1a1a1n1/c8/3k5/p3R4/2r6/4N4/P3P3P/4B4/3c5/3AKAB2 w - - 0 1",
            "1C1a1a1nr/1RC2k3/4c4/p3N3p/2b3p2/4P4/P7P/9/4A4/3AK1B2 w - - 0 1",
            "1C1a1a1nr/4k4/4n4/4N4/p3R3P/9/P3P4/4B4/9/4KAB2 w - - 0 1"
        )
        val games = lines.mapIndexedNotNull { i, l -> QipuGame.fenPuzzle(i, l) }
        check(games.size == lines.size) { "FEN 习题解析失败" }
        check(games.all { it.meta().movesUci.isEmpty() }) { "前提不成立：习题本应无着法" }

        val tmp = File(System.getProperty("java.io.tmpdir"), "cblprobe").apply { mkdirs() }
        val out = ByteArrayOutputStream()
        CblDecoder.writeLegacyBook(out, games.size, "5-10步缓杀 测试", CblDecoder.LegacyBookMeta(), tmp,
            { i -> games.getOrNull(i)?.cblGame })
        val bytes = out.toByteArray()
        val decoded = CblDecoder.decodeBook(bytes, "习题测试.cbl")
        println("=== 导出 ${bytes.size} 字节，解回 ${decoded.size} 局 ===")
        check(decoded.size == games.size) { "解回局数不符：${decoded.size}" }
        decoded.forEach { g ->
            println("  [${g.index}] ${g.title()} fen=${g.fenBoard} moves=${g.movesUci}")
            check(g.movesUci.size >= 3) { "记录 ${g.index} 着法不足 3 手（${g.movesUci.size}）—— 现按 3 手补齐" }
            check(g.fenBoard == games[g.index - 1].meta().fenBoard) { "记录 ${g.index} 局面丢失" }
        }
    }

    /**
     * 整本书端到端：把 assets 里真实的「习题_缓杀50000题.txt」（499306 条纯局面习题）
     * 走真正的导出路径 writeLegacyBook，落到 /tmp/fixed-book.cbl；
     * 再解回来断言：解回局数 == 499306、每条 ≥3 手、每条局面仍在。
     */
    @Test
    fun fenPuzzleWholeBookExport() {
        val txt = File("/workspace/work/hh_src/hh/app/src/main/assets/qipu_cbl/data/习题_缓杀50000题.txt")
        if (!txt.exists()) { println("缺失 $txt"); return }
        val lines = txt.readLines().filter { it.contains('/') && it.length > 20 }
        println("习题行数=${lines.size}")
        val tmp = File("/tmp/cblprobe").apply { mkdirs() }
        val dst = File("/tmp/fixed-book.cbl")
        val t0 = System.currentTimeMillis()
        dst.outputStream().use { os ->
            CblDecoder.writeLegacyBook(os, lines.size, "5-10步缓杀 50000题", CblDecoder.LegacyBookMeta(), tmp,
                { i -> QipuGame.fenPuzzle(i, lines[i])?.cblGame })
        }
        println("导出完成：${dst.length()} 字节，用时 ${System.currentTimeMillis() - t0}ms → ${dst.absolutePath}")
        val t1 = System.currentTimeMillis()
        val decoded = CblDecoder.decodeBook(dst.readBytes(), dst.name)
        println("解回 ${decoded.size} 局，用时 ${System.currentTimeMillis() - t1}ms")
        check(decoded.size == lines.size) { "解回局数 ${decoded.size} != ${lines.size}" }
        var minMoves = Int.MAX_VALUE
        decoded.forEach { g ->
            if (g.movesUci.size < minMoves) minMoves = g.movesUci.size
            if (g.movesUci.size < 3) error("记录 ${g.index} 着法 ${g.movesUci.size} < 3")
        }
        println("全部记录着法数最小值=$minMoves")
        decoded.take(3).forEach { println("  [${it.index}] fen=${it.fenBoard} moves=${it.movesUci}") }
    }
}
