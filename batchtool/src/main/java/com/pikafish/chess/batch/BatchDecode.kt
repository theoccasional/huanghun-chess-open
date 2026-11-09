package com.pikafish.chess.batch

import com.pikafish.chess.qipu.CblDecoder
import com.pikafish.chess.qipu.DhtmlXqDecoder
import com.pikafish.chess.qipu.PgnTextDecoder
import com.pikafish.chess.qipu.QylDecoder
import com.pikafish.chess.qipu.XqfDecoder
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 批量工具的解码调度：按【内容】识别格式，不靠后缀。
 * 支持：CBL/CBR 合集、MXQ、XQF、QYL、东萍 DhtmlXQ 文本、PGN/中文记谱文本、ZIP 压缩包（递归）。
 */
object BatchDecode {

    /**
     * 逐单元遍历：压缩包会按“包内每个文件”回调，单文件整体回调一次。
     * sink(name, games)                      —— 解出一个单元（文件/包内条目）的棋局
     * onEntry(done, total, entryName)        —— 压缩包内进度；total<=0 表示未知
     */
    fun walkAll(
        raw: ByteArray,
        name: String,
        depth: Int = 0,
        sink: (String, List<CblDecoder.CblGame>) -> Unit,
        onEntry: ((Int, Int, String) -> Unit)? = null
    ) {
        if (raw.isEmpty() || depth > 3) return

        // ① ZIP：递归解包内每个条目
        if (isZip(raw)) {
            val total = zipEntryCount(raw)
            var done = 0
            try {
                ZipInputStream(ByteArrayInputStream(raw)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (e.isDirectory) continue
                        done++
                        onEntry?.invoke(done, total, e.name)
                        val buf = zin.readBytes()
                        if (buf.isEmpty()) continue
                        walkAll(buf, e.name, depth + 1, sink, null)
                    }
                }
            } catch (_: Exception) {
            }
            return
        }

        val gs = decodeOne(raw, name)
        if (gs.isNotEmpty()) sink(name, gs)
    }

    /** 返回该文件能解出的全部棋局（含压缩包内所有子文件） */
    fun decodeAll(
        raw: ByteArray,
        name: String,
        depth: Int = 0,
        onEntry: ((Int, Int, String) -> Unit)? = null
    ): List<CblDecoder.CblGame> {
        val out = ArrayList<CblDecoder.CblGame>()
        walkAll(raw, name, depth, { _, gs -> out.addAll(gs) }, onEntry)
        return out
    }

    /** 单文件（非压缩包）解码 */
    private fun decodeOne(raw: ByteArray, name: String): List<CblDecoder.CblGame> {
        if (raw.isEmpty()) return emptyList()

        // ② CBL 合集（"CCBridge" 头）
        val isCbl = raw.size >= 8 && String(raw, 0, 8, Charsets.US_ASCII) == "CCBridge"
        if (isCbl) {
            val byBook = try { CblDecoder.decodeBook(raw, name) } catch (e: Exception) { emptyList() }
            if (byBook.isNotEmpty()) return byBook
            val byLib = try { CblDecoder.decodeLibraryContainer(raw, name) } catch (e: Exception) { emptyList() }
            if (byLib.isNotEmpty()) return byLib
            return emptyList()
        }

        // ③ MXQ（象棋演播室单局/合集）
        if (CblDecoder.isMxq(raw)) {
            val g = try { CblDecoder.decodeMxq(raw, 0) } catch (e: Exception) { null }
            if (g != null) return listOf(g)
        }

        // ④ XQF（象棋演播室单局）
        if (XqfDecoder.isXqf(raw)) {
            val g = try { XqfDecoder.decode(raw, 0, name) } catch (e: Exception) { null }
            if (g != null) return listOf(g)
        }

        // ⑤ QYL（启阳象棋，容器内 zstd 帧 → 整册）
        if (QylDecoder.isQyl(raw) || QylDecoder.looksLikeQyl(raw)) {
            val g = try { QylDecoder.decode(raw, name) } catch (e: Exception) { emptyList() }
            if (g.isNotEmpty()) return g
        }

        // ⑥ 文本类
        if (isTextual(raw)) {
            val text = decodeText(raw)
            // 东萍 DhtmlXQ
            if (DhtmlXqDecoder.hasDhtmlXq(text)) {
                val full = try { DhtmlXqDecoder.decodePage(text, 0, name) } catch (e: Exception) { emptyList() }
                if (full.isNotEmpty()) return full
            }
            // PGN / 中文记谱
            if (PgnTextDecoder.isPgnLikeText(text)) {
                val gs = try { PgnTextDecoder.decode(raw, name) } catch (e: Exception) { emptyList() }
                if (gs.isNotEmpty()) return gs.map { it.cblGame }
            }
        }

        // ⑦ 兜底：仍当 CBL 试一次（有些文件头不标准）
        return try { CblDecoder.decodeBook(raw, name) } catch (e: Exception) { emptyList() }
    }

    /** 读出 ZIP 中央目录里的条目总数（用于进度分母）；失败返回 -1 */
    fun zipEntryCount(raw: ByteArray): Int {
        var eocd = -1
        var i = raw.size - 22
        val min = maxOf(0, raw.size - 65557)
        while (i >= min) {
            if (raw[i] == 0x50.toByte() && raw[i + 1] == 0x4B.toByte() &&
                raw[i + 2] == 0x05.toByte() && raw[i + 3] == 0x06.toByte()
            ) { eocd = i; break }
            i--
        }
        if (eocd < 0) return -1
        fun u16(o: Int): Int =
            (raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8)
        var cnt = u16(eocd + 10)
        if (cnt == 0xFFFF) {
            // ZIP64
            val loc = eocd - 20
            if (loc >= 0 && raw[loc] == 0x50.toByte() && raw[loc + 1] == 0x4B.toByte() &&
                raw[loc + 2] == 0x06.toByte() && raw[loc + 3] == 0x07.toByte()
            ) {
                val z64 = (u16(loc + 8).toLong() or (u16(loc + 10).toLong() shl 16)).toInt()
                if (z64 in 0..raw.size - 56) {
                    val lo = u16(z64 + 32).toLong() or (u16(z64 + 34).toLong() shl 16)
                    cnt = lo.toInt()
                }
            }
        }
        return cnt
    }

    private fun isZip(raw: ByteArray): Boolean =
        raw.size > 4 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte() &&
            (raw[2] == 0x03.toByte() || raw[2] == 0x05.toByte() || raw[2] == 0x07.toByte())

    private fun isTextual(raw: ByteArray): Boolean {
        val n = minOf(raw.size, 4096)
        if (n == 0) return false
        var printable = 0
        for (i in 0 until n) {
            val b = raw[i].toInt() and 0xFF
            if (b == 0) return false
            if (b == 9 || b == 10 || b == 13 || b in 32..126 || b >= 0x80) printable++
        }
        return printable * 100 / n >= 95
    }

    private fun decodeText(raw: ByteArray): String {
        val gbk = try { String(raw, charset("GBK")) } catch (e: Exception) { "" }
        val utf = try { String(raw, Charsets.UTF_8) } catch (e: Exception) { "" }
        fun cjk(s: String) = s.count { it.code in 0x4E00..0x9FFF }
        return if (cjk(utf) >= cjk(gbk)) utf else gbk
    }
}
