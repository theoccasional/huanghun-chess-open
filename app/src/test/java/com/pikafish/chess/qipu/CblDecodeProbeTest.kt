package com.pikafish.chess.qipu

import org.junit.Test
import java.io.File

/** 临时探针：把真实 .cbl 丢进 CblDecoder，量一量耗时/是否卡死/能否解出棋局。 */
class CblDecodeProbeTest {
    @Test
    fun probe() {
        val files = listOf(
            "/workspace/.uploads/9f681e8e-a88f-4730-9554-f0ba146faa1b_中炮直横车对屏风马两头蛇.cbl",
            "/workspace/.uploads/3a9f106b-2516-4336-a934-5796d0a442e3_排局欣赏.CBL"
        )
        for (p in files) {
            val f = File(p)
            println("=== $p exists=${f.exists()} size=${f.length()} ===")
            if (!f.exists()) continue
            val bytes = f.readBytes()
            println("  isLibraryContainer=${CblDecoder.isLibraryContainer(bytes)}")
            val result = arrayOf("TIMEOUT(>20s，疑似死循环)")
            val t = Thread {
                val t0 = System.currentTimeMillis()
                val games = CblDecoder.decodeBook(bytes, f.name)
                val ms = System.currentTimeMillis() - t0
                result[0] = "games=${games.size} 用时=${ms}ms"
                games.take(6).forEach { g ->
                    println("    [${g.index}] title=${g.title()} moves=${g.movesUci.size} introLen=${g.introComment.length}")
                }
            }
            t.isDaemon = true
            t.start()
            t.join(20000)
            println("  ==> ${result[0]}  alive=${t.isAlive} stackTop=${t.stackTrace.firstOrNull()}")
        }
    }

    /**
     * 探针五：CBL 导出通用性对拍 —— 用真实象棋桥「旧格式」原文件
     *  解码 → writeLegacyBook → 落盘，供上位机(Python)逐字节核验：
     *  题名≤65B、固定 3 字段、走法 X/Y 与象棋桥一致（尤其竖走的"步数-1"）。
     */
    @Test
    fun probeLegacyExport() {
        val src = File("/workspace/work/cmp/orig.cbl")
        if (!src.exists()) { println("缺失 $src"); return }
        val games = CblDecoder.decodeBook(src.readBytes(), src.name)
        println("orig.cbl 解出 ${games.size} 局")
        val tmp = File("/tmp/cblprobe"); tmp.mkdirs()
        val out = java.io.ByteArrayOutputStream()
        val t0 = System.currentTimeMillis()
        CblDecoder.writeLegacyBook(out, games.size, "象棋残局基础", CblDecoder.LegacyBookMeta(), tmp,
            { i -> games.getOrNull(i) })
        val res = out.toByteArray()
        val dst = File("/workspace/work/cmp/ours_new.cbl")
        dst.writeBytes(res)
        println("导出 ${res.size} 字节 → ${dst.absolutePath}，用时 ${System.currentTimeMillis() - t0}ms")
    }

    /** 探针三：三份真实裸 CBR 单局（棋友上传，曾报"零回合"）能否解出实着。 */
    @Test
    fun probeCbrSingles() {
        val files = listOf(
            "/workspace/.uploads/3156bfab-2b30-4cb7-9e4a-79781a08aed2_急进中兵（完）.cbr",
            "/workspace/.uploads/486180e8-60bd-41e1-aff7-95a240049b64_屏风马横车.cbr",
            "/workspace/.uploads/734645ee-3071-4277-8649-9e1b4a366fc5_双杯敬酒：车炮牵制中的反将局例.cbr"
        )
        for (p in files) {
            val f = File(p)
            if (!f.exists()) { println("=== 缺失 $p ==="); continue }
            val bytes = f.readBytes()
            val single = CblDecoder.decodeStandaloneCbr(bytes, f.name)
            val variant = CblDecoder.decodeCbrVariant(bytes, f.name)
            val book = CblDecoder.decodeBook(bytes, f.name)
            println("=== ${f.name} size=${f.length()} ===")
            println("  standalone: games=${single.size} moves=${single.map { it.movesUci.size }}")
            println("  variant   : games=${variant.size} moves=${variant.map { it.movesUci.size }}")
            println("  decodeBook: games=${book.size} moves=${book.map { it.movesUci.size }}")
            book.forEach { g -> println("    [${g.index}] ${g.title()} len=${g.movesUci.size} head=${g.movesUci.take(8)}") }
        }
    }

    /** 探针四：棋路内置棋谱.zip（含 7478 个 XQF 等）能否成批解出、逐局点开有实着。 */
    @Test
    fun probeQiluZip() {
        val f = File("/workspace/.uploads/7278dba5-e8b2-4767-b0e7-deeac2018f39_棋路内置棋谱.zip")
        if (!f.exists()) { println("缺失 $f"); return }
        val bytes = f.readBytes()
        val t0 = System.currentTimeMillis()
        val games = QipuLibrary.decodeGames(bytes, f.name)
        println("=== 棋路内置棋谱.zip 解出 ${games.size} 局，用时 ${System.currentTimeMillis() - t0}ms ===")
        games.take(5).forEach { println("    ${it.title()}") }
        println("    末局：${games.lastOrNull()?.title()}")
        // 抽查逐局全量解码
        val idxs = (0 until minOf(40, games.size)) + (maxOf(0, games.size - 40) until games.size)
        var empty = 0; var total = 0
        val t1 = System.currentTimeMillis()
        for (i in idxs) {
            val g = games[i]
            val cg = g.cblGame
            if (cg.movesUci.isEmpty()) { empty++; println("    !! 局 $i 零着：${g.title()}") }
            total += cg.movesUci.size
            g.pgn()
            if (i < 3) println("    样例 ${g.title()} 着数=${cg.movesUci.size} 变招=${cg.variationsUci.size} head=${cg.movesUci.take(6)}")
        }
        println("  抽查 ${idxs.size} 局：总着=$total 零着局数=$empty 用时=${System.currentTimeMillis() - t1}ms")
    }

    /** 探针二：真实 MXQ 压缩包（"2022年2月份华山和棋棋谱.zip"，6923 局 .mxq）能否被自力更生解干净。 */
    @Test
    fun probeMxqZip() {
        val f = File("/workspace/.uploads/8a6cc131-327a-4069-b8bd-7d3ea4e6f0f1_2022年2月份华山和棋棋谱.zip")
        println("=== MxqZip exists=${f.exists()} size=${f.length()} ===")
        if (!f.exists()) return
        val bytes = f.readBytes()
        val t0 = System.currentTimeMillis()
        val games = QipuLibrary.decodeGames(bytes, f.name)
        val ms = System.currentTimeMillis() - t0
        println("  解包得 ${games.size} 局，用时 ${ms}ms（只取头部元信息，着法懒解码）")
        games.take(3).forEach { g -> println("    [${g.meta().index}] ${g.title()}") }
        println("    末局：${games.lastOrNull()?.title()}")

        // 逐着全量解码抽查：前 60 局 + 后 60 局，统计每局着法数（必须与文件内 90 步量级相符）
        val idxs = (0 until minOf(60, games.size)) + (maxOf(0, games.size - 60) until games.size)
        var emptyMoves = 0
        var totalMoves = 0
        val t1 = System.currentTimeMillis()
        for (i in idxs) {
            val g = games[i]
            val cg = g.cblGame
            if (cg.movesUci.isEmpty()) { emptyMoves++; println("    !! 局 $i 解出 0 着：${g.title()}") }
            totalMoves += cg.movesUci.size
            g.pgn()  // 走一遍 PGN 生成，确保不抛异常
        }
        println("  逐着抽查 ${idxs.size} 局：总着法=$totalMoves 空着局数=$emptyMoves 用时=${System.currentTimeMillis() - t1}ms")
        println("    样例 UCI：${games[0].cblGame.movesUci.take(6)}")
    }
}
