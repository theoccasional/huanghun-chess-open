package com.pikafish.chess.ui

import android.graphics.Bitmap
import android.util.Log
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * ★★★ Unity 资源提取引擎 ★★★
 *
 * 背景：不少国产象棋 App（如「象棋神域」）用 Unity 引擎开发，棋子/棋盘根本不是独立的 PNG，
 * 而是打包进 APK 的 `assets/bin/Data` 下的 `.assets`（大文件会被切成 `.split0/1/2…`），
 * 以 Texture2D 资源形式存在。此前「按文件名分类图片」的老路子对这种 APK 完全失效——
 * 包里剩下的只是一堆被 aapt2 混淆过的界面资源（res/Ra.png、res/0p.png），根本认不出来。
 *
 * 本引擎做的事：
 *  1. 归组：把 `xxx.assets` 与它的 `.splitN` 分片按序号拼回完整文件（Unity 分包 / APK 拆分）；
 *  2. 解析 SerializedFile 头：端序、版本、类型表、对象表（每个对象的偏移/大小/类型）；
 *  3. 只挑 Texture2D（classID=28）：读出名字、宽高、格式、图片字节；
 *  4. 解码纹理：ETC2_RGBA8 / ETC2_RGB4 / ETC_RGB4 / RGB24 / RGBA32 / ARGB32 / RGB565 /
 *     ARGB4444 / Alpha8 / DXT1 / DXT5 —— 覆盖 Unity 在 Android 上常见的压缩格式；
 *  5. 垂直翻转：Unity 纹理是「自下而上」存的，解码后必须翻过来才是正立画面。
 *
 * 解码结果交给上层（SkinManager）用既有的 classify() 定色定字：
 * RA/RB/RC/RN/RR/RK/RP = 红方七子，BA/BB/BC/BN/BR/BK/BP = 黑方七子，board = 棋盘。
 *
 * 缺失资源会静默跳过（返回空表），绝不抛出，保证导入流程不被打断。
 */
internal object UnityAssetReader {
    private const val TAG = "UnityAssetReader"
    private const val CLASSID_TEXTURE2D = 28

    /** 一个解码完成的 Unity 纹理。 */
    internal class UnityTexture(val name: String, val bmp: Bitmap)

    // ─────────────────────────── 字节读取（带端序） ───────────────────────────

    private class Buf(val d: ByteArray, var pos: Int, val be: Boolean) {
        fun u8(): Int = d[pos++].toInt() and 0xFF

        fun skip(n: Int) { pos += n }

        fun align(n: Int) { pos = (pos + n - 1) / n * n }

        fun le32(): Long {
            var v = 0L
            var i = 3
            while (i >= 0) { v = (v shl 8) or (d[pos + i].toLong() and 0xFF); i-- }
            pos += 4
            return v
        }

        fun be32(): Long {
            var v = 0L
            var i = 0
            while (i < 4) { v = (v shl 8) or (d[pos + i].toLong() and 0xFF); i++ }
            pos += 4
            return v
        }

        fun u32(): Long = if (be) be32() else le32()

        fun i32(): Int = u32().toInt()

        fun u64(): Long {
            var v = 0L
            if (be) {
                var i = 0
                while (i < 8) { v = (v shl 8) or (d[pos + i].toLong() and 0xFF); i++ }
            } else {
                var i = 7
                while (i >= 0) { v = (v shl 8) or (d[pos + i].toLong() and 0xFF); i-- }
            }
            pos += 8
            return v
        }

        fun i16(): Int {
            val a = d[pos].toInt() and 0xFF
            val b = d[pos + 1].toInt() and 0xFF
            pos += 2
            val v = if (be) (a shl 8) or b else (b shl 8) or a
            return if (v and 0x8000 != 0) v - 0x10000 else v
        }

        fun strZ(): String {
            val s = pos
            while (pos < d.size && d[pos].toInt() != 0) pos++
            val r = String(d, s, pos - s, Charsets.UTF_8)
            pos++
            return r
        }
    }

    private class Obj(val start: Int, val size: Int, val typeId: Int)

    private class TexRaw(val name: String, val w: Int, val h: Int, val fmt: Int,
                         val mips: Int, val cis: Int, val imgOff: Int)

    // ─────────────────────────── 对外入口 ───────────────────────────

    /** APK 里是否存在 Unity 的 SerializedFile（assets/bin/Data 下的 .assets）。 */
    fun isUnity(zip: ZipFile): Boolean {
        return try {
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val n = es.nextElement().name
                if (n.startsWith("assets/bin/Data/") && n.contains(".assets")) return true
            }
            false
        } catch (_: Exception) { false }
    }

    /**
     * 从 APK 里解出所有 Texture2D。[maxDim] 控制单张纹理的最大边长（超出则缩放），
     * [cancel] 返回 true 时尽快收工。失败一律返回已解出的部分，不抛出。
     */
    fun extract(zip: ZipFile, maxDim: Int, cancel: () -> Boolean): List<UnityTexture> {
        val out = ArrayList<UnityTexture>()
        try {
            val groups = groupSerializedFiles(zip)
            var count = 0
            for ((_, parts) in groups) {
                if (cancel() || count >= 400) break
                val entries = parts.sortedBy { if (it.first < 0) -1 else it.first }.map { it.second }
                val bytes = concat(zip, entries) ?: continue
                if (bytes.size < 64) continue
                val parsed = parseSerialized(bytes) ?: continue
                val (objs, types) = parsed
                val texTypeIds = HashSet<Int>()
                for (i in types.indices) if (types[i] == CLASSID_TEXTURE2D) texTypeIds.add(i)
                if (texTypeIds.isEmpty()) continue
                for (o in objs) {
                    if (cancel() || count >= 400) break
                    if (o.typeId !in texTypeIds) continue
                    val t = readTexture(bytes, o.start, o.size) ?: continue
                    if (t.w < 16 || t.h < 16) continue
                    if (max(t.w, t.h) > 4096 || t.w.toLong() * t.h > 16_000_000L) continue
                    val px = decode(t.fmt, bytes, t.imgOff, t.cis, t.w, t.h) ?: continue
                    val bmp = toBitmap(px, t.w, t.h, maxDim) ?: continue
                    out.add(UnityTexture(t.name, bmp))
                    count++
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unity 资源解析失败", t)
        }
        return out
    }

    /** 把 `x.assets` 及其 `.splitN` 归到一组（键=去掉 split 后缀的完整名）。 */
    private fun groupSerializedFiles(zip: ZipFile): LinkedHashMap<String, MutableList<Pair<Int, ZipEntry>>> {
        val groups = LinkedHashMap<String, MutableList<Pair<Int, ZipEntry>>>()
        val rx = Regex("(?i)^(.+?\\.assets)(?:\\.split(\\d+))?$")
        val es = zip.entries()
        while (es.hasMoreElements()) {
            val e = es.nextElement()
            if (e.isDirectory) continue
            val n = e.name
            if (!n.startsWith("assets/bin/Data/")) continue
            val m = rx.find(n) ?: continue
            val base = m.groupValues[1]
            val idx = m.groupValues[2].toIntOrNull() ?: -1
            groups.getOrPut(base) { ArrayList() }.add(idx to e)
        }
        return groups
    }

    private fun concat(zip: ZipFile, entries: List<ZipEntry>): ByteArray? {
        return try {
            var total = 0
            for (e in entries) total += e.size.coerceAtLeast(0).toInt()
            if (total <= 0 || total > 96 * 1024 * 1024) return null
            val out = ByteArray(total)
            var off = 0
            for (e in entries) {
                zip.getInputStream(e).use { ins ->
                    var r: Int
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        r = ins.read(buf)
                        if (r <= 0) break
                        if (off + r > out.size) { System.arraycopy(buf, 0, out, off, out.size - off); off = out.size; break }
                        System.arraycopy(buf, 0, out, off, r)
                        off += r
                    }
                }
            }
            if (off != total) out.copyOf(off) else out
        } catch (_: Exception) { null }
    }

    // ─────────────────────────── SerializedFile 头 ───────────────────────────

    /** 返回 (对象表, 类型表)；不是合法 SerializedFile 时返回 null。 */
    private fun parseSerialized(d: ByteArray): Pair<List<Obj>, IntArray>? {
        return try {
            if (d.size < 48) return null
            val hb = Buf(d, 0, true)
            hb.u32()                       // metadata size（旧版）
            hb.u32()                       // file size（旧版）
            val ver = hb.u32().toInt()
            val doff0 = hb.u32().toInt()
            if (ver < 5 || ver > 100) return null
            var be = true
            var dataOffset = doff0
            if (ver >= 9) {
                be = (d[16].toInt() and 0xFF) != 0
                if (ver >= 22) {
                    val b = Buf(d, 20, true)
                    b.u32()                // metadata size
                    b.u64()                // file size
                    dataOffset = b.u64().toInt()
                    b.u64()                // unknown
                }
            }
            val rStart = if (ver >= 22) 48 else 20
            if (d.size < rStart) return null
            val r = Buf(d, rStart, be)
            if (ver >= 7) r.strZ()         // unity 版本串
            if (ver >= 8) r.i32()          // target platform
            if (ver >= 13) r.u8()          // enableTypeTree
            val typeCount = r.i32()
            if (typeCount < 0 || typeCount > 5000) return null
            val types = IntArray(typeCount)
            for (i in 0 until typeCount) {
                val cid = r.i32()
                if (ver >= 16) r.u8()
                if (ver >= 17) r.i16()
                if (ver >= 13) {
                    if ((ver >= 16 && cid == 114) || (ver < 16 && cid < 0)) r.skip(16)
                    r.skip(16)             // old type hash
                }
                types[i] = cid
            }
            if (ver in 7..13) r.i32()
            val objCount = r.i32()
            if (objCount < 0 || objCount > 1_000_000) return null
            val objs = ArrayList<Obj>(objCount)
            for (i in 0 until objCount) {
                if (d.size < r.pos + 24) return null
                if (ver >= 14) { r.align(4); r.u64() } else r.u32()   // pathID
                val bs = if (ver >= 22) r.u64() else r.u32()
                val start = (bs + dataOffset).toInt()
                val size = r.u32().toInt()
                val tid = r.i32()
                if (size > 0) objs.add(Obj(start, size, tid))
            }
            objs to types
        } catch (_: Exception) { null }
    }

    /**
     * 读 Texture2D 前缀字段。Unity 2022 的布局为：
     * m_Name / m_ForcedFallbackFormat / m_DownscaleFallback / m_IsAlphaChannelOptional /
     * m_Width / m_Height / m_CompleteImageSize / m_MipsStripped / m_TextureFormat / m_MipCount…
     * 老版本没有 m_IsAlphaChannelOptional（少 2 字节），故对 bool 段长度做两手尝试。
     */
    private fun readTexture(d: ByteArray, off: Int, size: Int): TexRaw? {
        for (boolSkip in intArrayOf(4, 1)) {
            val t = readTextureLayout(d, off, size, boolSkip)
            if (t != null) return t
        }
        return null
    }

    private fun readTextureLayout(d: ByteArray, off: Int, size: Int, boolSkip: Int): TexRaw? {
        return try {
            if (off < 0 || size <= 0 || off + size > d.size) return null
            val r = Buf(d, off, false)
            val nlen = r.i32()
            if (nlen <= 0 || nlen > 1024 || r.pos + nlen > d.size) return null
            val name = String(d, r.pos, nlen, Charsets.UTF_8)
            r.pos += nlen
            r.align(4)
            r.i32()                        // m_ForcedFallbackFormat
            r.skip(boolSkip)               // m_DownscaleFallback (+ m_IsAlphaChannelOptional)
            val w = r.i32()
            val h = r.i32()
            val cis = r.i32()              // m_CompleteImageSize
            r.i32()                        // m_MipsStripped
            val fmt = r.i32()
            val mips = r.i32()
            if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
            if (cis <= 0 || cis > size) return null
            val imgOff = off + size - cis
            if (imgOff < 0 || imgOff + cis > d.size) return null
            TexRaw(name, w, h, fmt, mips, cis, imgOff)
        } catch (_: Exception) { null }
    }

    // ─────────────────────────── 纹理解码 ───────────────────────────

    private fun decode(fmt: Int, d: ByteArray, off: Int, cis: Int, w: Int, h: Int): IntArray? = try {
        when (fmt) {
            1 -> decodeAlpha8(d, off, w, h)
            2 -> decodeArgb4444(d, off, w, h)
            3 -> decodeRgb24(d, off, w, h)
            4 -> decodeRgba32(d, off, w, h)
            5 -> decodeArgb32(d, off, w, h)
            7 -> decodeRgb565(d, off, w, h)
            10 -> decodeDxt(d, off, w, h, alpha = false)
            12 -> decodeDxt(d, off, w, h, alpha = true)
            34, 45, 46 -> decodeEtc1(d, off, w, h)
            47 -> decodeEtc2Rgba8(d, off, w, h)
            else -> null
        }
    } catch (_: Exception) { null }

    private fun argb(r: Int, g: Int, b: Int, a: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun toBitmap(px: IntArray, w: Int, h: Int, maxDim: Int): Bitmap? {
        var bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        if (max(w, h) > maxDim) {
            val s = maxDim.toFloat() / max(w, h)
            val nw = max(1, (w * s).toInt())
            val nh = max(1, (h * s).toInt())
            val sc = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            if (sc !== bmp) bmp.recycle()
            bmp = sc
        }
        return bmp
    }

    /** 行 0 是显示画面顶部：Unity 纹理自下而上，故写入时翻转行序。 */

    // ── 未压缩 ──

    private fun decodeRgb24(d: ByteArray, off: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var p = off
        for (y in 0 until h) {
            val dst = (h - 1 - y) * w
            for (x in 0 until w) {
                out[dst + x] = argb(d[p].toInt() and 0xFF, d[p + 1].toInt() and 0xFF, d[p + 2].toInt() and 0xFF, 255)
                p += 3
            }
        }
        return out
    }

    private fun decodeRgba32(d: ByteArray, off: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var p = off
        for (y in 0 until h) {
            val dst = (h - 1 - y) * w
            for (x in 0 until w) {
                out[dst + x] = argb(d[p].toInt() and 0xFF, d[p + 1].toInt() and 0xFF,
                    d[p + 2].toInt() and 0xFF, d[p + 3].toInt() and 0xFF)
                p += 4
            }
        }
        return out
    }

    /** Unity ARGB32 在小端机器上按 B,G,R,A 存储。 */
    private fun decodeArgb32(d: ByteArray, off: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var p = off
        for (y in 0 until h) {
            val dst = (h - 1 - y) * w
            for (x in 0 until w) {
                out[dst + x] = argb(d[p + 2].toInt() and 0xFF, d[p + 1].toInt() and 0xFF,
                    d[p].toInt() and 0xFF, d[p + 3].toInt() and 0xFF)
                p += 4
            }
        }
        return out
    }

    private fun decodeAlpha8(d: ByteArray, off: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var p = off
        for (y in 0 until h) {
            val dst = (h - 1 - y) * w
            for (x in 0 until w) out[dst + x] = argb(255, 255, 255, d[p + x].toInt() and 0xFF)
            p += w
        }
        return out
    }

    private fun decodeRgb565(d: ByteArray, off: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var p = off
        for (y in 0 until h) {
            val dst = (h - 1 - y) * w
            for (x in 0 until w) {
                val v = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
                val r = (v shr 11) and 0x1F
                val g = (v shr 5) and 0x3F
                val b = v and 0x1F
                out[dst + x] = argb((r shl 3) or (r shr 2), (g shl 2) or (g shr 4), (b shl 3) or (b shr 2), 255)
                p += 2
            }
        }
        return out
    }

    private fun decodeArgb4444(d: ByteArray, off: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var p = off
        for (y in 0 until h) {
            val dst = (h - 1 - y) * w
            for (x in 0 until w) {
                val v = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
                val a = (v shr 12) and 0xF
                val r = (v shr 8) and 0xF
                val g = (v shr 4) and 0xF
                val b = v and 0xF
                out[dst + x] = argb(r * 17, g * 17, b * 17, a * 17)
                p += 2
            }
        }
        return out
    }

    // ── ETC1 / ETC2（8 字节一个 4×4 块） ──

    private val WRITE_ORDER = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
    private val WRITE_ORDER_REV = intArrayOf(15, 11, 7, 3, 14, 10, 6, 2, 13, 9, 5, 1, 12, 8, 4, 0)
    private val ETC1_MOD = arrayOf(
        intArrayOf(2, 8), intArrayOf(5, 17), intArrayOf(9, 29), intArrayOf(13, 42),
        intArrayOf(18, 60), intArrayOf(24, 80), intArrayOf(33, 106), intArrayOf(47, 183))
    private val SUBBLOCK = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1),
        intArrayOf(0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1))
    private val DIST = intArrayOf(3, 6, 11, 16, 23, 32, 41, 64)
    private val ALPHA_MOD = arrayOf(
        intArrayOf(-3, -6, -9, -15, 2, 5, 8, 14), intArrayOf(-3, -7, -10, -13, 2, 6, 9, 12),
        intArrayOf(-2, -5, -8, -13, 1, 4, 7, 12), intArrayOf(-2, -4, -6, -13, 1, 3, 5, 12),
        intArrayOf(-3, -6, -8, -12, 2, 5, 7, 11), intArrayOf(-3, -7, -9, -11, 2, 6, 8, 10),
        intArrayOf(-4, -7, -8, -11, 3, 6, 7, 10), intArrayOf(-3, -5, -8, -11, 2, 4, 7, 10),
        intArrayOf(-2, -6, -8, -10, 1, 5, 7, 9), intArrayOf(-2, -5, -8, -10, 1, 4, 7, 9),
        intArrayOf(-2, -4, -8, -10, 1, 3, 7, 9), intArrayOf(-2, -5, -7, -10, 1, 4, 6, 9),
        intArrayOf(-3, -4, -7, -10, 2, 3, 6, 9), intArrayOf(-1, -2, -3, -10, 0, 1, 2, 9),
        intArrayOf(-4, -6, -8, -9, 3, 5, 7, 8), intArrayOf(-3, -5, -7, -9, 2, 4, 6, 8))

    /** ETC1/ETC2 的 8 字节颜色块解码（含 T/H/Planar/差分/独立五种模式）。 */
    private fun etc2Block(d: IntArray, out: IntArray) {
        var j = (d[6] shl 8) or d[7]
        var k = (d[4] shl 8) or d[5]
        val c = Array(3) { IntArray(3) }
        val cs = IntArray(4)

        fun app(cc: IntArray, m: Int): Int = argb(clamp(cc[0] + m), clamp(cc[1] + m), clamp(cc[2] + m), 255)
        fun raw(cc: IntArray): Int = argb(cc[0], cc[1], cc[2], 255)

        if (d[3] and 2 != 0) {
            val r = d[0] and 0xF8
            val dr = ((d[0] shl 3) and 0x18) - ((d[0] shl 3) and 0x20)
            val g = d[1] and 0xF8
            val dg = ((d[1] shl 3) and 0x18) - ((d[1] shl 3) and 0x20)
            val b = d[2] and 0xF8
            val db = ((d[2] shl 3) and 0x18) - ((d[2] shl 3) and 0x20)
            when {
                r + dr < 0 || r + dr > 255 -> {   // T 模式
                    c[0][0] = (((d[0] shl 3) and 0xC0) or ((d[0] shl 4) and 0x30) or ((d[0] shr 1) and 0xC) or (d[0] and 3)) and 0xFF
                    c[0][1] = ((d[1] and 0xF0) or (d[1] shr 4)) and 0xFF
                    c[0][2] = ((d[1] and 0x0F) or (d[1] shl 4)) and 0xFF
                    c[1][0] = ((d[2] and 0xF0) or (d[2] shr 4)) and 0xFF
                    c[1][1] = ((d[2] and 0x0F) or (d[2] shl 4)) and 0xFF
                    c[1][2] = ((d[3] and 0xF0) or (d[3] shr 4)) and 0xFF
                    val dist = DIST[((d[3] shr 1) and 6) or (d[3] and 1)]
                    cs[0] = raw(c[0]); cs[1] = app(c[1], dist); cs[2] = raw(c[1]); cs[3] = app(c[1], -dist)
                    k = k shl 1
                    for (i in 0 until 16) {
                        out[WRITE_ORDER[i]] = cs[(k and 2) or (j and 1)]
                        j = j shr 1; k = k shr 1
                    }
                }
                g + dg < 0 || g + dg > 255 -> {   // H 模式
                    c[0][0] = ((d[0] shl 1) and 0xF0) or ((d[0] shr 3) and 0xF)
                    c[0][1] = ((d[0] shl 5) and 0xE0) or (d[1] and 0x10)
                    c[0][1] = c[0][1] or (c[0][1] shr 4)
                    c[0][2] = (d[1] and 8) or ((d[1] shl 1) and 6) or (d[2] shr 7)
                    c[0][2] = c[0][2] or (c[0][2] shl 4)
                    c[1][0] = ((d[2] shl 1) and 0xF0) or ((d[2] shr 3) and 0xF)
                    c[1][1] = ((d[2] shl 5) and 0xE0) or ((d[3] shr 3) and 0x10)
                    c[1][1] = c[1][1] or (c[1][1] shr 4)
                    c[1][2] = ((d[3] shl 1) and 0xF0) or ((d[3] shr 3) and 0xF)
                    var dd = (d[3] and 4) or ((d[3] shl 1) and 2)
                    if (c[0][0] > c[1][0] || (c[0][0] == c[1][0] &&
                                (c[0][1] > c[1][1] || (c[0][1] == c[1][1] && c[0][2] >= c[1][2])))) dd += 1
                    val dist = DIST[dd]
                    cs[0] = app(c[0], dist); cs[1] = app(c[0], -dist); cs[2] = app(c[1], dist); cs[3] = app(c[1], -dist)
                    k = k shl 1
                    for (i in 0 until 16) {
                        out[WRITE_ORDER[i]] = cs[(k and 2) or (j and 1)]
                        j = j shr 1; k = k shr 1
                    }
                }
                b + db < 0 || b + db > 255 -> {   // Planar 模式
                    c[0][0] = (((d[0] shl 1) and 0xFC) or ((d[0] shr 5) and 3)) and 0xFF
                    c[0][1] = (((d[0] shl 7) and 0x80) or (d[1] and 0x7E) or (d[0] and 1)) and 0xFF
                    c[0][2] = (((d[1] shl 7) and 0x80) or ((d[2] shl 2) and 0x60) or ((d[2] shl 3) and 0x18) or ((d[3] shr 5) and 4)) and 0xFF
                    c[0][2] = (c[0][2] or (c[0][2] shr 6)) and 0xFF
                    c[1][0] = (((d[3] shl 1) and 0xF8) or ((d[3] shl 2) and 4) or ((d[3] shr 5) and 3)) and 0xFF
                    c[1][1] = ((d[4] and 0xFE) or (d[4] shr 7)) and 0xFF
                    c[1][2] = (((d[4] shl 7) and 0x80) or ((d[5] shr 1) and 0x7C)) and 0xFF
                    c[1][2] = (c[1][2] or (c[1][2] shr 6)) and 0xFF
                    c[2][0] = (((d[5] shl 5) and 0xE0) or ((d[6] shr 3) and 0x1C) or ((d[5] shr 1) and 3)) and 0xFF
                    c[2][1] = (((d[6] shl 3) and 0xF8) or ((d[7] shr 5) and 6) or ((d[6] shr 4) and 1)) and 0xFF
                    c[2][2] = ((d[7] shl 2) or ((d[7] shr 4) and 3)) and 0xFF
                    for (y in 0 until 4) for (x in 0 until 4) {
                        val rr = clamp((x * (c[1][0] - c[0][0]) + y * (c[2][0] - c[0][0]) + 4 * c[0][0] + 2) shr 2)
                        val gg = clamp((x * (c[1][1] - c[0][1]) + y * (c[2][1] - c[0][1]) + 4 * c[0][1] + 2) shr 2)
                        val bb = clamp((x * (c[1][2] - c[0][2]) + y * (c[2][2] - c[0][2]) + 4 * c[0][2] + 2) shr 2)
                        out[y * 4 + x] = argb(rr, gg, bb, 255)
                    }
                }
                else -> {                        // 差分模式
                    val code0 = d[3] shr 5
                    val code1 = (d[3] shr 2) and 7
                    val code = intArrayOf(code0, code1)
                    val table = SUBBLOCK[d[3] and 1]
                    c[0][0] = (r or (r shr 5)) and 0xFF
                    c[0][1] = (g or (g shr 5)) and 0xFF
                    c[0][2] = (b or (b shr 5)) and 0xFF
                    c[1][0] = (r + dr) and 0xFF
                    c[1][1] = (g + dg) and 0xFF
                    c[1][2] = (b + db) and 0xFF
                    c[1][0] = c[1][0] or (c[1][0] shr 5)
                    c[1][1] = c[1][1] or (c[1][1] shr 5)
                    c[1][2] = c[1][2] or (c[1][2] shr 5)
                    for (i in 0 until 16) {
                        val s = table[i]
                        var m = ETC1_MOD[code[s]][j and 1]
                        if (k and 1 != 0) m = -m
                        out[WRITE_ORDER[i]] = app(c[s], m)
                        j = j shr 1; k = k shr 1
                    }
                }
            }
        } else {                                 // 独立模式
            val code = intArrayOf(d[3] shr 5, (d[3] shr 2) and 7)
            val table = SUBBLOCK[d[3] and 1]
            c[0][0] = ((d[0] and 0xF0) or (d[0] shr 4)) and 0xFF
            c[1][0] = ((d[0] and 0x0F) or (d[0] shl 4)) and 0xFF
            c[0][1] = ((d[1] and 0xF0) or (d[1] shr 4)) and 0xFF
            c[1][1] = ((d[1] and 0x0F) or (d[1] shl 4)) and 0xFF
            c[0][2] = ((d[2] and 0xF0) or (d[2] shr 4)) and 0xFF
            c[1][2] = ((d[2] and 0x0F) or (d[2] shl 4)) and 0xFF
            for (i in 0 until 16) {
                val s = table[i]
                var m = ETC1_MOD[code[s]][j and 1]
                if (k and 1 != 0) m = -m
                out[WRITE_ORDER[i]] = app(c[s], m)
                j = j shr 1; k = k shr 1
            }
        }
    }

    /** EAC 8 字节 alpha 块（ETC2_RGBA8 的低 8 字节）。 */
    private fun etc2a8Block(d: IntArray, out: IntArray) {
        if (d[1] and 0xF0 != 0) {
            val mult = d[1] shr 4
            val table = ALPHA_MOD[d[1] and 0xF]
            var l = 0L
            for (i in 0 until 8) l = (l shl 8) or (d[i].toLong() and 0xFF)
            for (i in 0 until 16) {
                val idx = WRITE_ORDER_REV[i]
                val a = clamp(d[0] + mult * table[(l and 7L).toInt()])
                out[idx] = (out[idx] and 0x00FFFFFF) or (a shl 24)
                l = l ushr 3
            }
        } else {
            for (i in 0 until 16) out[i] = (out[i] and 0x00FFFFFF) or (d[0] shl 24)
        }
    }

    private fun decodeEtc1(d: ByteArray, off: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val bw = (w + 3) / 4
        val bh = (h + 3) / 4
        val blk = IntArray(8)
        val buf = IntArray(16)
        for (by in 0 until bh) for (bx in 0 until bw) {
            val o = off + (by * bw + bx) * 8
            if (o + 8 > d.size) return out
            for (t in 0 until 8) blk[t] = d[o + t].toInt() and 0xFF
            etc2Block(blk, buf)
            emitBlock(out, buf, w, h, bx, by)
        }
        return out
    }

    private fun decodeEtc2Rgba8(d: ByteArray, off: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val bw = (w + 3) / 4
        val bh = (h + 3) / 4
        val col = IntArray(8)
        val alp = IntArray(8)
        val buf = IntArray(16)
        for (by in 0 until bh) for (bx in 0 until bw) {
            val o = off + (by * bw + bx) * 16
            if (o + 16 > d.size) return out
            for (t in 0 until 8) {
                col[t] = d[o + 8 + t].toInt() and 0xFF
                alp[t] = d[o + t].toInt() and 0xFF
            }
            etc2Block(col, buf)
            etc2a8Block(alp, buf)
            emitBlock(out, buf, w, h, bx, by)
        }
        return out
    }

    private inline fun emitBlock(out: IntArray, buf: IntArray, w: Int, h: Int, bx: Int, by: Int) {
        for (y in 0 until 4) {
            val yy = by * 4 + y
            if (yy >= h) break
            val dst = (h - 1 - yy) * w
            for (x in 0 until 4) {
                val xx = bx * 4 + x
                if (xx >= w) break
                out[dst + xx] = buf[y * 4 + x]
            }
        }
    }

    // ── DXT / BC ──

    private fun decodeDxt(d: ByteArray, off: Int, w: Int, h: Int, alpha: Boolean): IntArray {
        val out = IntArray(w * h)
        val bw = (w + 3) / 4
        val bh = (h + 3) / 4
        val blockSize = if (alpha) 16 else 8
        for (by in 0 until bh) for (bx in 0 until bw) {
            val o = off + (by * bw + bx) * blockSize
            if (o + blockSize > d.size) return out
            val colOff = if (alpha) o + 8 else o
            val c0 = (d[colOff].toInt() and 0xFF) or ((d[colOff + 1].toInt() and 0xFF) shl 8)
            val c1 = (d[colOff + 2].toInt() and 0xFF) or ((d[colOff + 3].toInt() and 0xFF) shl 8)
            // DXT1 且 c0<=c1：索引 3 为透明黑（镂空）
            val punchThrough = !alpha && c0 <= c1
            val colors = IntArray(4)
            colors[0] = rgb565(c0)
            colors[1] = rgb565(c1)
            if (c0 > c1 || alpha) {
                colors[2] = blend(colors[0], colors[1], 1, 1, 2)
                colors[3] = blend(colors[0], colors[1], 2, 1, 2)
            } else {
                colors[2] = blend(colors[0], colors[1], 1, 1, 1)
                colors[3] = 0
            }
            var bits = 0L
            for (t in 0 until 4) bits = bits or ((d[colOff + 4 + t].toLong() and 0xFF) shl (8 * t))
            val alphaVals = IntArray(16)
            if (alpha) {
                val a0 = d[o].toInt() and 0xFF
                val a1 = d[o + 1].toInt() and 0xFF
                val at = IntArray(8)
                at[0] = a0; at[1] = a1
                if (a0 > a1) {
                    for (i in 1..6) at[i + 1] = ((7 - i) * a0 + i * a1) / 7
                } else {
                    for (i in 1..4) at[i + 1] = ((5 - i) * a0 + i * a1) / 5
                    at[6] = 0; at[7] = 255
                }
                var abits = 0L
                for (t in 0 until 6) abits = abits or ((d[o + 2 + t].toLong() and 0xFF) shl (8 * t))
                for (i in 0 until 16) {
                    alphaVals[i] = at[(abits and 7L).toInt()]
                    abits = abits ushr 3
                }
            }
            for (y in 0 until 4) {
                val yy = by * 4 + y
                if (yy >= h) break
                val dst = (h - 1 - yy) * w
                for (x in 0 until 4) {
                    val xx = bx * 4 + x
                    if (xx >= w) break
                    val idx = (y * 4 + x)
                    val ci = ((bits ushr (2 * idx)) and 3L).toInt()
                    val c = colors[ci]
                    val a = when {
                        alpha -> alphaVals[idx]
                        punchThrough && ci == 3 -> 0
                        else -> 255
                    }
                    out[dst + xx] = argb((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, a)
                }
            }
        }
        return out
    }

    private fun rgb565(v: Int): Int {
        val r = (v shr 11) and 0x1F
        val g = (v shr 5) and 0x3F
        val b = v and 0x1F
        return argb((r shl 3) or (r shr 2), (g shl 2) or (g shr 4), (b shl 3) or (b shr 2), 255)
    }

    private fun blend(a: Int, b: Int, wa: Int, wb: Int, div: Int): Int {
        val r = (((a shr 16) and 0xFF) * wa + ((b shr 16) and 0xFF) * wb) / div
        val g = (((a shr 8) and 0xFF) * wa + ((b shr 8) and 0xFF) * wb) / div
        val bl = ((a and 0xFF) * wa + (b and 0xFF) * wb) / div
        return argb(clamp(r), clamp(g), clamp(bl), 255)
    }
}
