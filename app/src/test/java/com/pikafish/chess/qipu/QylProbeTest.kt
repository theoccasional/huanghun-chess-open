package com.pikafish.chess.qipu

import org.junit.Test
import java.io.File

/**
 * 探针：把真实 .qyl 丢进 QylDecoder，量一量能否解出、整册多少局、每局多少着，
 * 并与 Python 参考实现算出的期望（qyl_expected.json）对拍 —— 确保「这一局能开、
 * 别的同款文件也能开」。
 */
class QylProbeTest {

    private val sample = File(
        "/workspace/.uploads/d43c60fc-8f67-47e3-a38f-51ed49a5f265_2026年第六届“上海杯”象棋大师公开赛 男子组.qyl"
    )

    @Test
    fun probe() {
        println("=== exists=${sample.exists()} size=${sample.length()} ===")
        if (!sample.exists()) return
        val bytes = sample.readBytes()

        println("isQyl=${QylDecoder.isQyl(bytes)}")
        check(QylDecoder.isQyl(bytes)) { "内容识别失败：不是 QYL" }

        val t0 = System.currentTimeMillis()
        val games = QylDecoder.decode(bytes, sample.name)
        val ms = System.currentTimeMillis() - t0
        println("decode => ${games.size} 局  (${ms}ms)")

        games.take(3).forEach { g ->
            println("  [${g.index}] title=${g.title()} result=${g.result} moves=${g.movesUci.size}")
        }
        val total = games.sumOf { it.movesUci.size }
        println("total moves = $total")

        // 期望值（来自 Python 参考实现：81 局 / 7948 着 / 仅第 70 局因含变着短一些）
        check(games.size == 81) { "局数应为 81，实际 ${games.size}" }
        check(total == 7948) { "总着数应为 7948，实际 $total" }
        check(games[0].movesUci.take(12) == listOf(
            "h2e2", "h9g7", "h0g2", "i9h9", "i0h0", "b9c7", "c3c4", "g6g5", "b0c2", "h7h3", "b2b4", "g9e7"
        )) { "首局前 12 着不符：${games[0].movesUci.take(12)}" }
        check(games[0].red == "上海 顾博文" && games[0].black == "广东 程宇东") {
            "首局红黑方解析不符：${games[0].red} / ${games[0].black}"
        }
        check(games[0].result == "1/2-1/2") { "首局结果应为和棋，实际 ${games[0].result}" }
        // 每局至少主着能回放（PGN 非空、含着法）
        check(games.all { it.movesUci.isNotEmpty() }) { "存在空局面（未解出着法）" }
        // ★ 赛事名来自文件末尾那帧「无长度前缀」的索引帧：它能解出来，才说明"精确帧长"这条
        //   铁律生效（喂多了 aircompressor 会把帧后残余当下一帧头而报 Invalid magic prefix）。
        check(games[0].event.contains("上海杯")) { "赛事名应从索引帧解出，实际 '${games[0].event}'" }
        // ★ 全册每局都要能"按象棋规则走通"：不能只有首局对、别局蒙。
        check(games.all { it.movesUci.size >= 10 }) { "存在着法过少的对局：${games.map { it.movesUci.size }.sorted().take(5)}" }

        // ★ 宝库整链：decodeGames 必须认得出 .qyl（这正是「宝库 → 我的上传」与「外部打开」走的路）
        val viaLib = QipuLibrary.decodeGames(bytes, sample.name)
        println("decodeGames => ${viaLib.size} 局")
        check(viaLib.size == 81) { "decodeGames 应出 81 局，实际 ${viaLib.size}" }

        // ★★ 换文件也要能开：把标准 `QYLY` 容器头改成别的魔数（模拟"同后缀、但容器头是变体"的
        //    另一份 .qyl），仍须整册解出 —— 这就是"这局能开、别局也蒙不了"的护栏。
        val variant = bytes.copyOf()
        variant[0] = 'Z'.code.toByte(); variant[1] = 'Z'.code.toByte()
        variant[2] = 'Z'.code.toByte(); variant[3] = 'Z'.code.toByte()
        println("variant isQyl=${QylDecoder.isQyl(variant)} looksLikeQyl=${QylDecoder.looksLikeQyl(variant)}")
        check(!QylDecoder.isQyl(variant)) { "变体不应命中标准 QYLY 头" }
        check(QylDecoder.looksLikeQyl(variant)) { "变体应被 QYL 家族嗅探认出（内含 zstd 帧）" }
        val vg = QylDecoder.decode(variant, sample.name)
        val vTotal = vg.sumOf { it.movesUci.size }
        println("variant decode => ${vg.size} 局 / $vTotal 着")
        check(vg.size == 81 && vTotal == 7948) { "变体应同样解出 81 局 / 7948 着，实际 ${vg.size} / $vTotal" }
        val vLib = QipuLibrary.decodeGames(variant, sample.name)
        println("variant decodeGames => ${vLib.size} 局")
        check(vLib.size == 81) { "变体经 decodeGames 也应出 81 局，实际 ${vLib.size}" }

        // ★★★ 抗回归：纯文本棋谱绝不能被 QYL 家族嗅探误抢（否则 PGN/TXT 会整册读不出来）
        check(!QylDecoder.looksLikeQyl("1. 炮二平五 马8进7 2. 马二进三 车9平8\n".toByteArray())) {
            "文本棋谱被误判为 QYL 家族"
        }
    }

    /**
     * ★ 换文件实测：把 .uploads 下**所有** .qyl 都过一遍（开局库、残局书、名手棋路、赛事集），
     *   每一册都必须解得出来、且每局都有着法。任何一个文件出 0 局，即视为不通过。
     */
    @Test
    fun allSamples() {
        val dir = File("/workspace/.uploads")
        val files = dir.listFiles { f: File -> f.name.endsWith(".qyl") }?.sortedBy { it.name } ?: return
        println("=== 共 ${files.size} 个 .qyl ===")
        var bad = 0
        var fenGames = 0
        for (f in files) {
            val raw = f.readBytes()
            val shown = f.name.substringAfter("_")
            val games = try { QipuLibrary.decodeGames(raw, shown) } catch (e: Exception) { emptyList() }
            val moves = games.sumOf { it.cblGame.movesUci.size }
            val withFen = games.count { it.cblGame.fenBoard != null }
            fenGames += withFen
            val ev = games.firstOrNull()?.cblGame?.event ?: ""
            println(
                "${if (games.isEmpty()) "✗" else "✓"} ${games.size}局/${moves}着/${withFen}残局  " +
                    "size=${raw.size}  event=${ev.take(30)}  title=${games.firstOrNull()?.cblGame?.title()?.take(40) ?: "-"}  「$shown」"
            )
            if (games.isEmpty() || moves == 0) bad++
            // ★ 残局/排局书里存在"纯讲解帧"（如《残局基础》车兵残局第102局(1)(2)(3)）：
            //   整帧就是一段棋题说明、本就无着法 —— 这类只要求讲解放得出来，不要求有着法。
            //   真正要防的是"有着法却一个都没解出来"，故只有【无讲解】的空局才算不过。
            val emptyButExplained = games.count {
                it.cblGame.movesUci.size < 3 && it.cblGame.introComment.isNotBlank()
            }
            val emptyAndUnexplained = games.filter {
                it.cblGame.movesUci.size < 3 && it.cblGame.introComment.isBlank()
            }
            if (emptyAndUnexplained.isNotEmpty()) {
                bad++
                println("   ✗ 有无讲解又无着法的空局：${emptyAndUnexplained.map { it.cblGame.title().take(30) }}")
            }
            if (emptyButExplained > 0) {
                println("   · 纯讲解帧 $emptyButExplained 则（无着法，仅棋题说明，属正常）")
            }
        }
        println("=== 解不出的册数 = $bad ；带 FEN 起始局面的残局局数 = $fenGames ===")
        check(bad == 0) { "有 $bad 册 .qyl 解不出棋局（应为 0）" }
        // ★ 残局书必须真出残局局面（《残局基础》256 + 《残局大观》96 等），否则 FEN 帧头这一型没吃住
        check(fenGames >= 300) { "带内嵌 FEN 的残局局数过少：$fenGames（应 ≥300）" }
    }
}
