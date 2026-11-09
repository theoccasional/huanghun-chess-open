package com.pikafish.chess.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ★ 外置读物 · 书架
 *
 * 存放用户从外部（QQ / 文件管理器「打开其他应用」）送进来、或自己在应用内导入的 PDF 读物。
 * 实物存放在 filesDir/readers 目录下的 .pdf（应用私有，卸载才清），索引写在同目录 index.json。
 * 阅读进度（比例/页码）与书签标记按「文件名」存进 SharedPreferences，随书长期保留。
 */
data class ReaderBook(
    val name: String,        // 显示名（不带扩展名，供书单展示与点击阅读）
    val fileName: String,    // 实际落盘文件名（含 .pdf）
    val sizeBytes: Long,
    val addedAt: Long
)

object ReaderShelf {

    private const val DIR = "readers"
    private const val INDEX = "index.json"
    private const val PREF = "reader_shelf_prefs"

    // ────────────────────────── 目录/索引 ──────────────────────────

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun indexFile(ctx: Context) = File(dir(ctx), INDEX)

    fun fileOf(ctx: Context, book: ReaderBook): File = File(dir(ctx), book.fileName)

    /** 读取书架全部读物（按加入时间倒序：最新的在最上） */
    fun list(ctx: Context): MutableList<ReaderBook> {
        val out = ArrayList<ReaderBook>()
        try {
            val f = indexFile(ctx)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val fn = o.optString("file")
                if (fn.isBlank()) continue
                if (!File(dir(ctx), fn).exists()) continue          // 实物没了就不列出
                out.add(
                    ReaderBook(
                        name = o.optString("name").ifBlank { fn.substringBeforeLast('.') },
                        fileName = fn,
                        sizeBytes = o.optLong("size"),
                        addedAt = o.optLong("added")
                    )
                )
            }
        } catch (_: Exception) {}
        out.sortByDescending { it.addedAt }
        return out
    }

    private fun writeIndex(ctx: Context, books: List<ReaderBook>) {
        try {
            val arr = JSONArray()
            for (b in books) {
                arr.put(JSONObject().apply {
                    put("name", b.name)
                    put("file", b.fileName)
                    put("size", b.sizeBytes)
                    put("added", b.addedAt)
                })
            }
            indexFile(ctx).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    /** 生成一个在目录内不重名的安全文件名 */
    private fun uniqueFileName(ctx: Context, displayName: String): String {
        var base = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .ifBlank { "read_${System.currentTimeMillis()}" }
        // 名字过长会顶到文件系统 255 字节上限，按字符截断兜底（60 字已足够辨识）
        if (base.length > 60) base = base.take(60)
        var name = base
        if (!name.lowercase().endsWith(".pdf")) name += ".pdf"
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "pdf")
        var candidate = name
        var n = 1
        while (File(dir(ctx), candidate).exists()) {
            n++
            candidate = "$stem($n).$ext"
        }
        return candidate
    }

    // ────────────────────────── 增 / 删 / 清 ──────────────────────────

    /** 从字节流导入一本书（外部打开、或应用内选择文件后都走这里）。成功返回新书条目。 */
    fun add(ctx: Context, displayName: String, bytes: ByteArray): ReaderBook? {
        if (bytes.isEmpty()) return null
        return try {
            val fileName = uniqueFileName(ctx, displayName)
            val out = File(dir(ctx), fileName)
            out.writeBytes(bytes)
            val book = ReaderBook(
                name = displayName.substringBeforeLast('.').ifBlank { fileName.substringBeforeLast('.') },
                fileName = fileName,
                sizeBytes = bytes.size.toLong(),
                addedAt = System.currentTimeMillis()
            )
            val books = list(ctx)
            books.removeAll { it.fileName == fileName }
            books.add(book)
            writeIndex(ctx, books)
            book
        } catch (_: Exception) { null }
    }

    /** 从已有文件复制导入 */
    fun addFromFile(ctx: Context, displayName: String, src: File): ReaderBook? {
        return try {
            if (!src.exists() || src.length() <= 0L) return null
            add(ctx, displayName.ifBlank { src.name }, src.readBytes())
        } catch (_: Exception) { null }
    }

    fun delete(ctx: Context, book: ReaderBook) {
        try { fileOf(ctx, book).delete() } catch (_: Exception) {}
        try {
            val books = list(ctx)
            books.removeAll { it.fileName == book.fileName }
            writeIndex(ctx, books)
        } catch (_: Exception) {}
        // 顺手清掉它的进度、标记、版面与划线
        try { progressPref(ctx).edit().remove(book.fileName).apply() } catch (_: Exception) {}
        try { marksPref(ctx).edit().remove(book.fileName).apply() } catch (_: Exception) {}
        try { viewPref(ctx).edit().remove(book.fileName).remove(book.fileName + "#off").apply() } catch (_: Exception) {}
        try { hlPref(ctx).edit().remove(book.fileName).apply() } catch (_: Exception) {}
    }

    fun deleteByFileName(ctx: Context, fileName: String) {
        try { File(dir(ctx), fileName).delete() } catch (_: Exception) {}
        try {
            val books = list(ctx)
            books.removeAll { it.fileName == fileName }
            writeIndex(ctx, books)
        } catch (_: Exception) {}
        try { progressPref(ctx).edit().remove(fileName).apply() } catch (_: Exception) {}
        try { marksPref(ctx).edit().remove(fileName).apply() } catch (_: Exception) {}
        try { viewPref(ctx).edit().remove(fileName).remove(fileName + "#off").apply() } catch (_: Exception) {}
        try { hlPref(ctx).edit().remove(fileName).apply() } catch (_: Exception) {}
    }

    /** 清空：全部删除（书 + 进度 + 标记 + 版面 + 划线一个不留） */
    fun clear(ctx: Context) {
        try { dir(ctx).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        try { progressPref(ctx).edit().clear().apply() } catch (_: Exception) {}
        try { marksPref(ctx).edit().clear().apply() } catch (_: Exception) {}
        try { viewPref(ctx).edit().clear().apply() } catch (_: Exception) {}
        try { hlPref(ctx).edit().clear().apply() } catch (_: Exception) {}
    }

    // ────────────────────────── 阅读进度 ──────────────────────────

    private fun progressPref(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 保存阅读进度：ratio 0..1 为整本比例（不同缩放级别都稳定），page 为当前页（0 基），total 为总页数 */
    fun saveProgress(ctx: Context, fileName: String, ratio: Float, page: Int, total: Int) {
        try {
            progressPref(ctx).edit().putString(fileName, "$ratio|$page|$total").apply()
        } catch (_: Exception) {}
    }

    /** 取回阅读进度：返回 (ratio, page, total)；没有记录返回 null */
    fun progress(ctx: Context, fileName: String): Triple<Float, Int, Int>? {
        return try {
            val s = progressPref(ctx).getString(fileName, null) ?: return null
            val p = s.split('|')
            if (p.size < 3) return null
            Triple(p[0].toFloatOrNull() ?: 0f, p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0)
        } catch (_: Exception) { null }
    }

    // ────────────────────────── 版面（裁边 + 阅读模式） ──────────────────────────

    private fun viewPref(ctx: Context) =
        ctx.getSharedPreferences(PREF + "_view", Context.MODE_PRIVATE)

    /**
     * 保存版面设置：裁边比例（左/上/右/下，0..0.35）与阅读模式（0=上下滚动，1=左右翻页）。
     * 每本书独立记忆，下次打开自动还原。
     */
    fun saveView(ctx: Context, fileName: String, l: Float, t: Float, r: Float, b: Float, mode: Int) {
        try {
            viewPref(ctx).edit().putString(fileName, "$l|$t|$r|$b|$mode").apply()
        } catch (_: Exception) {}
    }

    /** 取回版面设置：FloatArray(左,上,右,下) 与 mode；没有记录返回 null */
    fun view(ctx: Context, fileName: String): Pair<FloatArray, Int>? {
        return try {
            val s = viewPref(ctx).getString(fileName, null) ?: return null
            val p = s.split('|')
            if (p.size < 5) return null
            Pair(
                floatArrayOf(
                    p[0].toFloatOrNull() ?: 0f,
                    p[1].toFloatOrNull() ?: 0f,
                    p[2].toFloatOrNull() ?: 0f,
                    p[3].toFloatOrNull() ?: 0f
                ),
                p[4].toIntOrNull() ?: 0
            )
        } catch (_: Exception) { null }
    }

    // ────────────────────────── 页码修正差 ──────────────────────────

    /** 用户自定的「目录页码 ↔ PDF 实际页」偏差（正=实际页更大），按书长期保留；0 表示不修正 */
    fun pageOffset(ctx: Context, fileName: String): Int =
        try { viewPref(ctx).getInt(fileName + "#off", 0) } catch (_: Exception) { 0 }

    fun savePageOffset(ctx: Context, fileName: String, off: Int) {
        try { viewPref(ctx).edit().putInt(fileName + "#off", off).apply() } catch (_: Exception) {}
    }

    // ────────────────────────── 标记（书签） ──────────────────────────

    private fun marksPref(ctx: Context) =
        ctx.getSharedPreferences(PREF + "_marks", Context.MODE_PRIVATE)

    fun marks(ctx: Context, fileName: String): MutableSet<Int> {
        val out = HashSet<Int>()
        try {
            val s = marksPref(ctx).getString(fileName, null) ?: return out
            for (t in s.split(',')) t.trim().toIntOrNull()?.let { if (it >= 0) out.add(it) }
        } catch (_: Exception) {}
        return out
    }

    fun saveMarks(ctx: Context, fileName: String, marks: Set<Int>) {
        try {
            marksPref(ctx).edit().putString(fileName, marks.sorted().joinToString(",")).apply()
        } catch (_: Exception) {}
    }

    // ────────────────────────── 划线高亮（每页若干矩形，全页归一化坐标） ──────────────────────────

    private fun hlPref(ctx: Context) =
        ctx.getSharedPreferences(PREF + "_hl", Context.MODE_PRIVATE)

    /** 取回某本书的全部划线：页序号 → 矩形列表(left, top, right, bottom ∈ 0..1) */
    fun highlights(ctx: Context, fileName: String): MutableMap<Int, MutableList<FloatArray>> {
        val out = HashMap<Int, MutableList<FloatArray>>()
        try {
            val s = hlPref(ctx).getString(fileName, null) ?: return out
            val root = JSONObject(s)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val page = k.toIntOrNull() ?: continue
                val arr = root.optJSONArray(k) ?: continue
                val list = ArrayList<FloatArray>()
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONArray(i) ?: continue
                    if (a.length() < 4) continue
                    list.add(
                        floatArrayOf(
                            a.optDouble(0, 0.0).toFloat(),
                            a.optDouble(1, 0.0).toFloat(),
                            a.optDouble(2, 0.0).toFloat(),
                            a.optDouble(3, 0.0).toFloat()
                        )
                    )
                }
                if (list.isNotEmpty()) out[page] = list
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveHighlights(ctx: Context, fileName: String, hl: Map<Int, List<FloatArray>>) {
        try {
            val root = JSONObject()
            for ((page, list) in hl) {
                if (list.isEmpty()) continue
                val arr = JSONArray()
                for (r in list) {
                    arr.put(JSONArray().apply {
                        put(r[0].toDouble()); put(r[1].toDouble()); put(r[2].toDouble()); put(r[3].toDouble())
                    })
                }
                root.put(page.toString(), arr)
            }
            hlPref(ctx).edit().putString(fileName, root.toString()).apply()
        } catch (_: Exception) {}
    }
}
