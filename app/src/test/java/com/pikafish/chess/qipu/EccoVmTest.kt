package com.pikafish.chess.qipu

import com.pikafish.chess.qipu.ecco.EccoVm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * EccoVm/EccoIndex 对拍测试：跑 12000 例随机合法谱 + 官方谱例，
 * 期望编号由 Python 版 VM（已与 libecco64.so 对拍 0 差异）生成于
 * src/test/resources/ecco_corpus.txt。
 */
class EccoVmTest {

    private fun loadCorpus(): List<Pair<String, String>> {
        val lines = javaClass.classLoader.getResourceAsStream("ecco_corpus.txt")
            ?.bufferedReader()?.readLines() ?: error("corpus not found")
        return lines.mapNotNull { ln ->
            val t = ln.split('\t')
            if (t.size == 2) t[0] to t[1] else null
        }
    }

    @Test
    fun `vm matches validated corpus`() {
        val corpus = loadCorpus()
        assertNotNull(corpus)
        var bad = 0
        for ((input, expected) in corpus) {
            val got = try {
                val eax = EccoVm(input.toByteArray(Charsets.US_ASCII)).eccoIndex()
                buildString(3) {
                    append((eax and 0xff).toChar())
                    append(((eax shr 8) and 0xff).toChar())
                    append(((eax shr 16) and 0xff).toChar())
                }
            } catch (e: Exception) {
                "ERR:" + (e.message ?: "")
            }
            if (got != expected) {
                bad++
                if (bad <= 5) println("DIFF input=$input expected=$expected got=$got")
            }
        }
        println("corpus cases=${corpus.size} bad=$bad")
        assertEquals("VM mismatch count", 0, bad)
    }

    @Test
    fun `eccoIndex pipeline matches corpus`() {
        // 完整管线（语法校验 + VM + 编号转换）逐条对拍，确保 tokenize 不误伤合法谱
        val corpus = loadCorpus()
        var bad = 0
        for ((input, expected) in corpus) {
            val got = EccoIndex.eval(input)
            if (got != expected) {
                bad++
                if (bad <= 5) println("PIPE-DIFF input=$input expected=$expected got=$got")
            }
        }
        println("pipeline cases=${corpus.size} bad=$bad")
        assertEquals("pipeline mismatch count", 0, bad)
    }

    @Test
    fun `eccoIndex end-to-end`() {
        // 官方谱例：中炮过河车对屏风马（libecco64.so 直调验证：EAX=0x313443 -> "C41"）
        val demo = "C2.5N8+7N2+3R9.8R1.2P7+1R2+6N2+3P7+1C8.9R2.3C9-1"
        val code = EccoIndex.eval(demo)
        assertEquals("C41", code)
        // 单步与常见开局串
        assertEquals("B00", EccoIndex.eval("C2.5"))
        assertNotNull(EccoIndex.eval("C2.5N8+7N2+3R9.8R1.2"))
        // 空串 / 非法串 → null（由 UI 隐藏，绝不显示 .so 兜底的 A00）
        assertEquals(null, EccoIndex.eval(""))
        assertEquals(null, EccoIndex.eval("XYZ"))
        assertEquals(null, EccoIndex.eval("C2.5N8"))
        assertEquals(null, EccoIndex.eval("C2.5 N8+7"))
    }

    @Test
    fun `namer maps codes to names`() {
        val r = EccoNamer.nameOf("C43")
        assertNotNull(r)
        println("C43 -> $r")
        val r2 = EccoNamer.nameOf("B00")
        println("B00 -> $r2")
        assertNotNull(r2)
    }
}
