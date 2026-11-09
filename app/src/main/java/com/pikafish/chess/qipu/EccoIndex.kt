package com.pikafish.chess.qipu

import com.pikafish.chess.qipu.ecco.EccoVm

/**
 * EccoIndex —— ECCO 判定逻辑的 Kotlin 落地。
 *
 * 与《象棋巫师》libecco64.so 的 EccoIndex 完全一致：把 C2.5 走法串归一到 ECCO 编号（如 "C41"）。
 * 判定引擎是 libecco64.so 判定函数的 x86-64 最小 VM 移植（见 qipu/ecco/EccoVm.kt），
 * 指令表与数据镜像由 gen_kotlin.py 生成；Python 版已用 12000 例随机合法谱面与官方库对拍，0 差异。
 *
 * 入口先做严格语法校验（与 .so 的解析语法一致：一连串 4 字符 token，如 C2.5 / N8+7），
 * 任何杂质（空格/残缺/非法字符）直接返回 null，由 UI 层隐藏该行——绝不返回臆造的编号/名称。
 * 注：ECCO 官方把 A01~E99 之外的走法一律记为 "A00（其他开局）"，属正常编号而非兜底垃圾值，
 * 故 EccoDict 收录 A00，常见于"兵五进一""车九进一"等非常见开局；真正的非法输入已被上面的
 * 语法校验挡在门外。
 */
object EccoIndex {

    /** 对 C2.5 走法串求 ECCO 编号；串非法或判定未覆盖返回 null。 */
    fun eval(c25: String): String? {
        if (c25.isEmpty()) return null
        if (!validC25(c25)) return null
        // 与 libecco64.so 一致：输入为整条 C2.5 串（ASCII），返回 EAX = 3 字节小端编号
        return try {
            val eax = EccoVm(c25.toByteArray(Charsets.US_ASCII)).eccoIndex()
            val code = buildString(3) {
                append((eax and 0xff).toChar())
                append(((eax shr 8) and 0xff).toChar())
                append(((eax shr 16) and 0xff).toChar())
            }
            // 附加兜底：编号必须是 A-E 首字母 + 两位数字（ECCO 编号形如 "C41"）
            if (plausibleCode(code)) code else null
        } catch (e: Exception) {
            // 任何异常路径（循环保护/越界/未知指令等）一律视为“未覆盖”，由 UI 隐藏
            null
        }
    }

    /** 严格校验整条 C2.5 串：必须是一连串 4 字符 token（如 "C2.5" "N8+7" "R1.2" "P7+1"）。
     *  与 libecco64.so 的解析语法一致；带空格/残缺/非法字符一律视为非法。 */
    private fun validC25(s: String): Boolean {
        if (s.length % 4 != 0) return false
        var i = 0
        while (i < s.length) {
            if (!validToken(s, i)) return false
            i += 4
        }
        return true
    }

    /** 单个 4 字符 token：<子字母> <起点路 1..9> <进/退/平> <终点路或步数 1..9>。 */
    private fun validToken(s: String, i: Int): Boolean {
        if (s[i] !in "CPNBRKAR") return false
        if (s[i + 1] !in '1'..'9') return false
        if (s[i + 2] != '+' && s[i + 2] != '-' && s[i + 2] != '.') return false
        if (s[i + 3] !in '1'..'9') return false
        return true
    }

    /** ECCO 编号形态：首字母 A-E + 两位数字（如 "C41"）。 */
    private fun plausibleCode(code: String): Boolean {
        if (code.length != 3) return false
        if (code[0] !in 'A'..'E') return false
        return code[1] in '0'..'9' && code[2] in '0'..'9'
    }
}
