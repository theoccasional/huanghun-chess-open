package com.pikafish.chess.reader

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ★ PDF 目录（书签/大纲）解析器
 *
 * Android 的 PdfRenderer 只负责“把页面画出来”，不暴露 PDF 自带的书签大纲。
 * 本类自己读 PDF 的对象结构，把 /Outlines 大纲树抽出来：每条含 层级深度、标题、页码（0 基）。
 *
 * 做法：不依赖交叉引用表（兼容经典 xref 与 xref 流两种 PDF），直接全文扫描 "\d+ \d+ obj"
 * 建立对象号 → 对象体偏移，再按需解析对象字典；找到 /Type /Catalog → /Pages（建页码表）
 * 与 /Outlines（遍历书签树）。全部在后台线程调用，失败一律返回空列表（阅读器自动降级为页码跳转）。
 */
object PdfOutlineParser {

    /** 一条目录：depth 为层级（0 起），page 为 0 基页码（-1 表示未定位到页） */
    data class Item(val depth: Int, val title: String, val page: Int)

    fun parse(file: File): List<Item> = try {
        if (!file.exists() || file.length() <= 0L) emptyList()
        else Impl(file.readBytes()).run()
    } catch (_: Exception) { emptyList() }

    // ────────────────────────── PDF 值类型 ──────────────────────────
    private class Ref(val num: Int)
    private class Name(val v: String)
    private class Str(val raw: ByteArray)
    private class Arr(val items: ArrayList<Any?>)

    private class Impl(private val d: ByteArray) {

        private val offsets = HashMap<Int, ArrayList<Int>>()   // 对象号 → 可能的对象体偏移
        private val parsed = HashSet<Int>()
        private val objCache = HashMap<Int, Any?>()
        private val namedDests = HashMap<String, Any?>()

        // ────────────────────────── 字节工具 ──────────────────────────
        private fun isWs(b: Int) = b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32

        private fun isDelim(b: Int): Boolean = when (b) {
            0, 9, 10, 12, 13, 32 -> true
            0x28, 0x29, 0x3C, 0x3E, 0x5B, 0x5D, 0x7B, 0x7D, 0x2F, 0x25 -> true
            else -> false
        }

        private fun isDigit(b: Int) = b in 0x30..0x39

        private fun hexVal(b: Int): Int = when (b) {
            in 0x30..0x39 -> b - 0x30
            in 0x41..0x46 -> b - 0x41 + 10
            in 0x61..0x66 -> b - 0x61 + 10
            else -> -1
        }

        private fun skipWs(i0: Int): Int {
            var i = i0
            while (i < d.size && isWs(d[i].toInt() and 0xFF)) i++
            return i
        }

        private fun indexOfSeq(needle: ByteArray, from: Int): Int {
            val n = needle.size
            if (n == 0) return -1
            val last = d.size - n
            var i = from
            outer@ while (i <= last) {
                var j = 0
                while (j < n) {
                    if (d[i + j] != needle[j]) { i++; continue@outer }
                    j++
                }
                return i
            }
            return -1
        }

        private fun toInt(from: Int, toExclusive: Int): Int? {
            if (toExclusive <= from) return null
            var v = 0L
            for (i in from until toExclusive) {
                v = v * 10 + (d[i].toInt() and 0xFF) - 0x30
                if (v > Int.MAX_VALUE) return null
            }
            return v.toInt()
        }

        // ────────────────────────── 值解析 ──────────────────────────

        private fun parseValue(start: Int): Pair<Any?, Int> {
            var i = skipWs(start)
            if (i >= d.size) return null to i
            when (d[i].toInt() and 0xFF) {
                0x3C -> {   // '<'
                    if (i + 1 < d.size && (d[i + 1].toInt() and 0xFF) == 0x3C) return parseDict(i + 2)
                    return parseHexString(i)
                }
                0x28 -> return parseLiteralString(i)     // '('
                0x5B -> {                                 // '['
                    i++
                    val arr = ArrayList<Any?>()
                    var guard = 0
                    while (guard++ < 200000) {
                        i = skipWs(i)
                        if (i >= d.size) break
                        if ((d[i].toInt() and 0xFF) == 0x5D) { i++; break }
                        val (v, ni) = parseValue(i)
                        if (ni <= i) break
                        arr.add(v); i = ni
                    }
                    return Arr(arr) to i
                }
                0x2F -> {                                 // '/'
                    var j = i + 1
                    val sb = StringBuilder()
                    while (j < d.size) {
                        val ch = d[j].toInt() and 0xFF
                        if (isDelim(ch)) break
                        if (ch == 0x23 && j + 2 < d.size) {
                            val h1 = hexVal(d[j + 1].toInt() and 0xFF)
                            val h2 = hexVal(d[j + 2].toInt() and 0xFF)
                            if (h1 >= 0 && h2 >= 0) { sb.append(((h1 shl 4) or h2).toChar()); j += 3; continue }
                        }
                        sb.append(ch.toChar()); j++
                    }
                    return Name(sb.toString()) to j
                }
            }
            matchRef(i)?.let { return it.first to it.second }
            matchNumber(i)?.let { return it.first to it.second }
            matchKeyword(i)?.let { return Name(it.first) to it.second }
            return null to (i + 1)
        }

        private fun readUInt(start: Int): Pair<Int, Int>? {
            var i = start
            var v = 0L
            var any = false
            while (i < d.size && isDigit(d[i].toInt() and 0xFF)) {
                v = v * 10 + (d[i].toInt() and 0xFF) - 0x30
                if (v > Int.MAX_VALUE) return null
                i++; any = true
            }
            return if (any) v.toInt() to i else null
        }

        private fun matchRef(start: Int): Pair<Ref, Int>? {
            val n1 = readUInt(start) ?: return null
            var i = n1.second
            if (i >= d.size || !isWs(d[i].toInt() and 0xFF)) return null
            i = skipWs(i)
            val n2 = readUInt(i) ?: return null
            i = n2.second
            if (i >= d.size || !isWs(d[i].toInt() and 0xFF)) return null
            i = skipWs(i)
            if (i >= d.size || (d[i].toInt() and 0xFF) != 0x52) return null   // 'R'
            i++
            if (i < d.size && !isDelim(d[i].toInt() and 0xFF)) return null
            return Ref(n1.first) to i
        }

        private fun matchNumber(start: Int): Pair<Double, Int>? {
            var i = start
            if (i < d.size) {
                val c = d[i].toInt() and 0xFF
                if (c == 0x2B || c == 0x2D) i++      // + / -
            }
            var any = false
            while (i < d.size && isDigit(d[i].toInt() and 0xFF)) { i++; any = true }
            if (i < d.size && (d[i].toInt() and 0xFF) == 0x2E) {    // '.'
                i++
                while (i < d.size && isDigit(d[i].toInt() and 0xFF)) { i++; any = true }
            }
            if (!any) return null
            val s = String(d, start, i - start, Charsets.US_ASCII)
            return (s.toDoubleOrNull() ?: 0.0) to i
        }

        private fun matchKeyword(start: Int): Pair<String, Int>? {
            for (kw in arrayOf("true", "false", "null")) {
                if (start + kw.length <= d.size) {
                    var ok = true
                    for (k in kw.indices) if (d[start + k].toInt() != kw[k].code) { ok = false; break }
                    if (ok) return kw to (start + kw.length)
                }
            }
            return null
        }

        private fun parseDict(start: Int): Pair<Any?, Int> {
            var i = skipWs(start)
            val map = HashMap<String, Any?>()
            var guard = 0
            while (i < d.size && guard++ < 200000) {
                if (i + 1 < d.size && (d[i].toInt() and 0xFF) == 0x3E && (d[i + 1].toInt() and 0xFF) == 0x3E) {
                    return map to (i + 2)
                }
                if ((d[i].toInt() and 0xFF) != 0x2F) break
                var j = i + 1
                val sb = StringBuilder()
                while (j < d.size && !isDelim(d[j].toInt() and 0xFF)) {
                    sb.append((d[j].toInt() and 0xFF).toChar()); j++
                }
                val key = sb.toString()
                val (v, ni) = parseValue(j)
                if (ni <= j) break
                map[key] = v
                i = skipWs(ni)
            }
            return map to i
        }

        private fun parseLiteralString(start: Int): Pair<Any?, Int> {
            val out = ByteArrayOutputStream()
            var depth = 0
            var j = start + 1
            while (j < d.size) {
                val ch = d[j].toInt() and 0xFF
                if (ch == 0x5C) {                       // '\\'
                    val nx = if (j + 1 < d.size) d[j + 1].toInt() and 0xFF else -1
                    when {
                        nx == 0x6E -> { out.write(10); j += 2 }
                        nx == 0x72 -> { out.write(13); j += 2 }
                        nx == 0x74 -> { out.write(9); j += 2 }
                        nx == 0x62 -> { out.write(8); j += 2 }
                        nx == 0x66 -> { out.write(12); j += 2 }
                        nx == 0x28 || nx == 0x29 || nx == 0x5C -> { out.write(nx); j += 2 }
                        nx in 0x30..0x37 -> {
                            var k = 0; var v = 0
                            while (k < 3 && j + 1 + k < d.size) {
                                val o = d[j + 1 + k].toInt() and 0xFF
                                if (o < 0x30 || o > 0x37) break
                                v = v * 8 + (o - 0x30); k++
                            }
                            out.write(v and 0xFF); j += 1 + k
                        }
                        else -> j += 2                  // 续行符 / 未知转义：整体跳过
                    }
                    continue
                }
                if (ch == 0x28) depth++
                else if (ch == 0x29) {
                    if (depth == 0) { j++; break }
                    depth--
                }
                out.write(ch); j++
            }
            return Str(out.toByteArray()) to j
        }

        private fun parseHexString(start: Int): Pair<Any?, Int> {
            var j = start + 1
            while (j < d.size && (d[j].toInt() and 0xFF) != 0x3E) j++
            val sb = StringBuilder()
            for (k in start + 1 until j) if (hexVal(d[k].toInt() and 0xFF) >= 0) sb.append((d[k].toInt() and 0xFF).toChar())
            if (sb.length % 2 == 1) sb.append('0')
            val raw = ByteArray(sb.length / 2) {
                ((hexVal(sb[it * 2].code) shl 4) or hexVal(sb[it * 2 + 1].code)).toByte()
            }
            return Str(raw) to (if (j < d.size) j + 1 else j)
        }

        // ────────────────────────── 对象表 ──────────────────────────

        private fun buildOffsets() {
            val obj = byteArrayOf(0x6F, 0x62, 0x6A)     // "obj"
            var k = 0
            while (true) {
                k = indexOfSeq(obj, k)
                if (k < 0) break
                val after = k + 3
                if (after >= d.size || isDelim(d[after].toInt() and 0xFF)) {
                    var i = k - 1
                    if (i >= 0 && isWs(d[i].toInt() and 0xFF)) {
                        while (i >= 0 && isWs(d[i].toInt() and 0xFF)) i--
                        val genEnd = i
                        while (i >= 0 && isDigit(d[i].toInt() and 0xFF)) i--
                        val genStart = i + 1
                        if (genEnd >= genStart && i >= 0 && isWs(d[i].toInt() and 0xFF)) {
                            while (i >= 0 && isWs(d[i].toInt() and 0xFF)) i--
                            val numEnd = i
                            while (i >= 0 && isDigit(d[i].toInt() and 0xFF)) i--
                            val numStart = i + 1
                            if (numEnd >= numStart) {
                                toInt(numStart, numEnd + 1)?.let { num ->
                                    offsets.getOrPut(num) { ArrayList() }.add(after)
                                }
                            }
                        }
                    }
                }
                k = after
            }
        }

        /** 取对象字典（只收 Map：目录/页/大纲节点都是字典，天然过滤掉流里的伪 "obj"） */
        private fun objectAt(num: Int): Any? {
            if (num in parsed) return objCache[num]
            parsed.add(num)
            var result: Any? = null
            offsets[num]?.let { cands ->
                for (off in cands) {
                    val (v, _) = parseValue(off)
                    if (v is Map<*, *>) { result = v; break }
                }
            }
            objCache[num] = result
            return result
        }

        private fun deref(v: Any?): Any? {
            var x = v
            var guard = 0
            while (x is Ref && guard++ < 64) x = objectAt(x.num)
            return x
        }

        private fun nameOf(v: Any?) = (v as? Name)?.v

        private fun asMap(v: Any?) = v as? Map<*, *>

        // ────────────────────────── 文本解码 ──────────────────────────

        private fun decodeText(v: Any?): String? {
            val raw = (v as? Str)?.raw ?: return null
            if (raw.isEmpty()) return ""
            if (raw.size >= 2) {
                val b0 = raw[0].toInt() and 0xFF; val b1 = raw[1].toInt() and 0xFF
                if (b0 == 0xFE && b1 == 0xFF) return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
                if (b0 == 0xFF && b1 == 0xFE) return String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
            }
            if (raw.size >= 3) {
                val b0 = raw[0].toInt() and 0xFF; val b1 = raw[1].toInt() and 0xFF; val b2 = raw[2].toInt() and 0xFF
                if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) return String(raw, 3, raw.size - 3, Charsets.UTF_8)
            }
            if ((raw[0].toInt() and 0xFF) == 0) return String(raw, 0, raw.size, Charsets.UTF_16BE)
            return try { String(raw, Charsets.UTF_8) } catch (_: Exception) { String(raw, Charsets.ISO_8859_1) }
        }

        private fun cleanTitle(s: String?): String =
            (s ?: "").replace('\u0000', ' ').replace(Regex("\\s+"), " ").trim()

        // ────────────────────────── 页对象 → 页码 ──────────────────────────

        private fun walkPages(node: Map<*, *>, index: HashMap<Int, Int>, depth: Int) {
            if (depth > 64) return
            val kids = deref(node["Kids"]) as? Arr ?: return
            if (nameOf(node["Type"]) == "Page") return
            for (k in kids.items) {
                if (k !is Ref) continue
                val o = asMap(objectAt(k.num)) ?: continue
                if (nameOf(o["Type"]) == "Pages") walkPages(o, index, depth + 1)
                else if (!index.containsKey(k.num)) index[k.num] = index.size
            }
        }

        // ────────────────────────── 命名目标 ──────────────────────────

        private fun buildNamedDests(catalog: Map<*, *>) {
            asMap(deref(catalog["Dests"]))?.let { m ->
                for ((k, v) in m) if (k is String) namedDests[k] = v
            }
            val names = asMap(deref(catalog["Names"])) ?: return
            collectNameTree(deref(names["Dests"]), 0)
        }

        private fun collectNameTree(node: Any?, depth: Int) {
            if (depth > 32) return
            val m = asMap(deref(node)) ?: return
            val arr = (deref(m["Names"]) as? Arr)?.items
            if (arr != null) {
                var i = 0
                while (i + 1 < arr.size) {
                    val key = when (val k = arr[i]) {
                        is Str -> decodeText(k)
                        is Name -> k.v
                        else -> null
                    }
                    if (key != null) namedDests[key] = arr[i + 1]
                    i += 2
                }
            }
            (deref(m["Kids"]) as? Arr)?.items?.forEach { collectNameTree(it, depth + 1) }
        }

        // ────────────────────────── 目标 → 页码 ──────────────────────────

        private fun destPage(item: Map<*, *>, index: Map<Int, Int>, depth: Int): Int {
            var dv = item["Dest"]
            if (dv == null) {
                val a = asMap(deref(item["A"]))
                if (a != null) dv = a["D"]
            }
            return destToPage(deref(dv), index, depth)
        }

        private fun destToPage(dv: Any?, index: Map<Int, Int>, depth: Int): Int {
            if (depth > 8) return -1
            when (dv) {
                is Arr -> {
                    val f = dv.items.firstOrNull()
                    if (f is Ref) return index[f.num] ?: -1
                    if (f is Double) return f.toInt()
                }
                is Str -> {
                    val key = decodeText(dv)
                    if (key != null) namedDests[key]?.let { return destToPage(deref(it), index, depth + 1) }
                }
                is Name -> namedDests[dv.v]?.let { return destToPage(deref(it), index, depth + 1) }
            }
            return -1
        }

        // ────────────────────────── 大纲遍历 ──────────────────────────

        private fun walkOutline(
            first: Any?, depth: Int,
            index: Map<Int, Int>, out: MutableList<Item>, count: IntArray
        ) {
            var item = first
            while (item is Map<*, *> && count[0] < 2000) {
                count[0]++
                out.add(
                    Item(
                        depth = depth,
                        title = cleanTitle(decodeText(item["Title"])),
                        page = destPage(item, index, 0)
                    )
                )
                item["First"]?.let { walkOutline(deref(it), depth + 1, index, out, count) }
                val nx = item["Next"]
                item = if (nx != null) deref(nx) else null
            }
        }

        fun run(): List<Item> {
            buildOffsets()
            if (offsets.isEmpty()) return emptyList()

            var catalog: Map<*, *>? = null
            for (num in offsets.keys) {
                val o = asMap(objectAt(num)) ?: continue
                if (nameOf(o["Type"]) == "Catalog") { catalog = o; break }
            }
            val cat = catalog ?: return emptyList()

            buildNamedDests(cat)

            val pageIndex = HashMap<Int, Int>()
            asMap(deref(cat["Pages"]))?.let { walkPages(it, pageIndex, 0) }

            val outlines = asMap(deref(cat["Outlines"])) ?: return emptyList()
            val out = ArrayList<Item>()
            walkOutline(deref(outlines["First"]), 0, pageIndex, out, intArrayOf(0))
            return out
        }
    }
}
