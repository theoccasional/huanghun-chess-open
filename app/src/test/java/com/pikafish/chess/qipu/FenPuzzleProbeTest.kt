package com.pikafish.chess.qipu

import org.junit.Test
import java.io.File

/**
 * 探针：把真实「成批 FEN 题库」丢进 QipuLibrary.decodeGames，
 * 验证能否识别为习题集、题目数、首题/末题解析是否正确（针对“放宝库里、按序号当习题”）。
 */
class FenPuzzleProbeTest {
    private val file = File("/workspace/.uploads/e168a908-168c-4b81-9413-234abb56ede3_5-10步缓杀 50000题.txt")

    @Test
    fun probe() {
        println("=== exists=${file.exists()} size=${file.length()} ===")
        if (!file.exists()) return
        val bytes = file.readBytes()

        val t0 = System.currentTimeMillis()
        val isBatch = QipuLibrary.isFenBatch(bytes)
        val g0ms = System.currentTimeMillis() - t0
        println("isFenBatch=$isBatch (${g0ms}ms)")
        check(isBatch) { "成批 FEN 题库未被识别" }

        val t1 = System.currentTimeMillis()
        val games = QipuLibrary.decodeGames(bytes, file.name)
        val t1ms = System.currentTimeMillis() - t1
        println("decodeGames => ${games.size} 题 (${t1ms}ms)")
        check(games.size >= 400000) { "题目数异常：${games.size}" }
        check(games[0].isFenPuzzle) { "首题未标记为习题" }
        check(games.all { it.isFenPuzzle }) { "存在非习题条目" }

        val first = games[0]
        println("首题 title=${first.title()}")
        println("首题 fen=${first.meta().fenBoard} moverBlack=${first.meta().moversBlack}")
        check(first.meta().fenBoard == "1C1a1a1n1/c8/3k5/p3R4/2r6/4N4/P3P3P/4B4/3c5/3AKAB2") {
            "首题棋盘不符：${first.meta().fenBoard}"
        }
        check(!first.meta().moversBlack) { "首题应为红先" }
        check(first.title() == "习题") { "无题名时应叫“习题”，实际=${first.title()}" }

        val last = games[games.size - 1]
        println("末题 title=${last.title()} fen=${last.meta().fenBoard} moverBlack=${last.meta().moversBlack}")
        check(!last.meta().fenBoard.isNullOrBlank()) { "末题棋盘为空" }

        // 随机抽几题确认能按需解析
        listOf(0, 1, 1000, 12345, games.size - 2).forEach { i ->
            val g = games[i]
            check(!g.meta().fenBoard.isNullOrBlank()) { "第 $i 题解析失败" }
        }
        println("抽样解析 OK")
    }
}
