package com.pikafish.chess.qipu

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 棋谱宝库「圈二」的本地持久化中心：
 *  - 本局印象（多选打勾标签 + 其他备注）
 *  - 书签标记
 *  - 阅读程度（精读/需再读/略读/不必读）
 *  - 对局日志（含续盘快照、恢复、删除）
 *
 * 全部用 JSON 存于 SharedPreferences，键为「书#局序号」。
 * 运行时直读 CBL，不放文件。
 */
object GameJournal {

    private const val PREFS = "qipu_journal_v2"
    private const val KEY_GAMES = "games"
    private const val KEY_LOG = "log"

    // 阅读程度标签（筛选时用）
    const val TAG_INTENSIVE = "已精读"
    const val TAG_REREAD = "需再读"
    const val TAG_SKIM = "略读"
    const val TAG_SKIP = "不必读"

    // 常用本局印象勾选标签
    val IMPRESSION_TAGS = listOf(
        "妙局", "苦战", "经典", "初学者友好",
        "陷阱多", "重复率高", "异局", "值得收藏"
    )

    /** 阅读程度标签列表 */
    fun readingTags() = listOf(TAG_INTENSIVE, TAG_REREAD, TAG_SKIM, TAG_SKIP)

    /** 一局棋在库中的固定身份 */
    data class GameRef(
        val cat: String,
        val book: String,
        val file: String,
        val index: Int,
        val title: String
    ) {
        fun key(): String = "$file#$index"
    }

    /** 某局棋的读者侧记录 */
    data class GameRecord(
        val ref: GameRef,
        val tags: MutableSet<String> = LinkedHashSet(),
        var note: String = "",
        var bookmark: Boolean = false,
        var reading: String = "",
        var lastOpen: Long = 0L,
        var openCount: Int = 0,
        /** ★ 是否已隐藏（仅内置宝库用）：隐藏只记本机，原棋谱文件不受影响。 */
        var hidden: Boolean = false
    )

    /** 对局日志条目 */
    data class LogEntry(
        var id: String = "",
        var title: String = "",
        var source: String,   // "主界面"/"摆子"/"棋谱库"
        var fileName: String,
        var bookIndex: Int,
        var bookName: String,
        var catName: String,
        var createdAt: Long,
        var updatedAt: Long,
        var stateJson: String?,   // 主界面/摆子续盘快照（完整对局）
        var movesCount: Int,
        var reading: String,
        var note: String,
        var bookmark: Boolean
    )

    // ══════════════════════════ 底层读写 ══════════════════════════

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ★ 进程内缓存：目录列表「每一行」都要读一次读者记录，若每次都把整份 games JSON
    //   从头解析，遇到「5 万局」这类大书，一页 120 行就是 120 次全量解析——连点几下
    //   隐藏就把主线程拖垮（ANR/闪退）。这里只在首次读取时解析一次，之后读写复用同一份。
    private val cacheLock = Any()
    @Volatile private var gamesObjCache: JSONObject? = null

    private fun readGamesObj(c: Context): JSONObject {
        gamesObjCache?.let { return it }
        synchronized(cacheLock) {
            gamesObjCache?.let { return it }
            val raw = prefs(c).getString(KEY_GAMES, "{}") ?: "{}"
            val parsed = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
            gamesObjCache = parsed
            return parsed
        }
    }

    /** ★ 中央写入口：内存缓存与偏好同步，保证 write 之后读到的就是刚写的这份对象。 */
    private fun writeGames(c: Context, g: JSONObject) {
        gamesObjCache = g
        prefs(c).edit().putString(KEY_GAMES, g.toString()).apply()
    }

    /** ★ 实时预制存档的固定 id：每走一步都把当前对局落盘到该位，点“新局→保存”时用它顶上来。 */
    const val DRAFT_ID = "vault_draft"

    private fun readLogArr(c: Context): JSONArray {
        val raw = prefs(c).getString(KEY_LOG, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    private fun commit(c: Context, games: JSONObject, log: JSONArray) {
        gamesObjCache = games   // ★ 与偏好保持一致，避免下次读取再整份解析
        prefs(c).edit()
            .putString(KEY_GAMES, games.toString())
            .putString(KEY_LOG, log.toString())
            .apply()
    }

    // ══════════════════════════ 记录读写（本局印象） ══════════════════════════

    private fun parseTags(o: JSONObject?): MutableSet<String> {
    val out = LinkedHashSet<String>()
    val arr = o?.optJSONArray("tags") ?: return out
    for (i in 0 until arr.length()) out.add(arr.getString(i))
    return out
}

/** 读取某局棋的读者记录；若不存在则返回带默认 lastOpen=0 的新记录 */
    fun getRecord(c: Context, ref: GameRef): GameRecord {
        val g = readGamesObj(c).optJSONObject(ref.key()) ?: return GameRecord(ref)
        return GameRecord(
            ref = ref,
            tags = parseTags(g),
            note = g.optString("note"),
            bookmark = g.optBoolean("bookmark"),
            reading = g.optString("reading"),
            lastOpen = g.optLong("lastOpen"),
            openCount = g.optInt("openCount"),
            hidden = g.optBoolean("hidden")
        )
    }

    /** 覆盖保存某局棋的读者记录 */
    fun saveRecord(c: Context, rec: GameRecord) {
        val g = readGamesObj(c)
        val o = JSONObject()
        o.put("tags", JSONArray(rec.tags.toList()))
        o.put("note", rec.note)
        o.put("bookmark", rec.bookmark)
        o.put("reading", rec.reading)
        o.put("lastOpen", rec.lastOpen)
        o.put("openCount", rec.openCount)
        o.put("hidden", rec.hidden)
        g.put(rec.ref.key(), o)
        writeGames(c, g)
    }

    /** ★ 隐藏/取消隐藏某一则（内置宝库专用）：只改读者侧记录，绝不触碰原 CBL 文件。 */
    fun setHidden(c: Context, ref: GameRef, hidden: Boolean) {
        val rec = getRecord(c, ref)
        rec.hidden = hidden
        saveRecord(c, rec)
    }

    /** ★ 某本书里被隐藏的棋则序号集合（供目录列表过滤与隐藏清单用）。 */
    fun hiddenIndices(c: Context, file: String): Set<Int> {
        val out = HashSet<Int>()
        val g = readGamesObj(c)
        val keys = g.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.startsWith("$file#")) continue
            val o = g.optJSONObject(k) ?: continue
            if (!o.optBoolean("hidden")) continue
            k.substringAfterLast('#').toIntOrNull()?.let { out.add(it) }
        }
        return out
    }

    /** 打开一局棋时调用：累计阅读记录 + 写入日志 */
    fun touch(c: Context, ref: GameRef, source: String) {
        val rec = getRecord(c, ref)
        rec.lastOpen = System.currentTimeMillis()
        rec.openCount += 1
        saveRecord(c, rec)

        // 对应日志：有根有据型（可直接恢复为重新打开该书该局）
        if (source == "棋谱库") {
            upsertLog(
                c,
                id = ref.key(),
                title = ref.title,
                source = source,
                fileName = ref.file,
                bookIndex = ref.index,
                bookName = ref.book,
                catName = ref.cat,
                stateJson = null,
                movesCount = 0,
                reading = rec.reading,
                note = rec.note,
                bookmark = rec.bookmark
            )
        }
    }

    // ══════════════════════════ 对局日志 ══════════════════════════

    /** 汇总（新增或更新）一条日志 */
    fun upsertLog(
        c: Context,
        id: String,
        title: String,
        source: String,
        fileName: String,
        bookIndex: Int,
        bookName: String,
        catName: String,
        stateJson: String?,
        movesCount: Int,
        reading: String,
        note: String,
        bookmark: Boolean
    ) {
        val arr = readLogArr(c)
        val now = System.currentTimeMillis()
        val list = (0 until arr.length()).map { arr.optJSONObject(it) }.toMutableList()
        val idx = list.indexOfFirst { it.optString("id") == id }
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("source", source)
        o.put("fileName", fileName)
        o.put("bookIndex", bookIndex)
        o.put("bookName", bookName)
        o.put("catName", catName)
        o.put("createdAt", if (idx >= 0) list[idx].optLong("createdAt", now) else now)
        o.put("updatedAt", now)
        // ★ 完整对局快照改为落盘保存（filesDir/vault_games/），SharedPreferences 只存文件名。
        //   好处：重启/关机后再开，宝库仍能找到并可续盘；同时避免大 JSON 塞进偏好导致
        //   "存盘后回合数丢失"。stateJson 写入文件成功后，偏好里只留一个轻量引用。
        val stateFile = storeStateToFile(c, id, stateJson)
        o.put("stateFile", stateFile)
        o.put(
            "stateJson",
            if (stateFile.isNotBlank()) JSONObject.NULL.toString()
            else (stateJson ?: JSONObject.NULL.toString())
        )
        o.put("movesCount", movesCount)
        o.put("reading", reading)
        o.put("note", note)
        o.put("bookmark", bookmark)
        if (idx >= 0) list[idx] = o else list.add(0, o)
        // 日志上限：最多保留 60 条
        if (list.size > 60) {
            list.subList(60, list.size).forEach { stale ->
                deleteStateFile(c, stale.optString("stateFile"))
            }
            list.subList(60, list.size).clear()
        }
        commit(c, readGamesObj(c), JSONArray(list))
    }

    /** ★ 外部 HHXQ 快照镜像目录：/sdcard/HHXQ/vault_games/（黄昏象棋拼音缩写）。
     *   缓存/数据被清也不会丢——它随系统外部存储永久保留，供启动时自动恢复本地存档。 */
    fun externalSnapshotDir(): java.io.File =
        java.io.File(android.os.Environment.getExternalStorageDirectory(), "HHXQ/vault_games")

    /** ★ 完整对局快照落盘：返回文件名；失败(空)则回退到偏好内联。
     *   同一份 JSON 同时写入「内部 filesDir/vault_games」与「外部 HHXQ/vault_games」镜像，
     *   即使手动清理缓存/数据，本地存档也仍保留在 HHXQ，可自动恢复、无需手工导入。 */
    private fun storeStateToFile(c: Context, id: String, stateJson: String?): String {
        if (stateJson.isNullOrBlank()) return ""
        return try {
            val dir = java.io.File(c.filesDir, "vault_games").apply { mkdirs() }
            val fname = "log_" + id.replace(Regex("[^\\w\\u4e00-\\u9fa5]"), "_") + ".json"
            java.io.File(dir, fname).writeText(stateJson)
            // ★ 外部 HHXQ 镜像（写失败不影响主流程，内部已兜底）
            try {
                val ext = externalSnapshotDir().apply { mkdirs() }
                java.io.File(ext, fname).writeText(stateJson)
            } catch (e: Exception) { /* 外部存储不可用则忽略 */ }
            fname
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /** 按文件名删除已落盘的对局快照文件（内部 + 外部 HHXQ 镜像都删）。 */
    private fun deleteStateFile(c: Context, fileName: String?) {
        if (fileName.isNullOrBlank()) return
        try {
            java.io.File(java.io.File(c.filesDir, "vault_games"), fileName).delete()
        } catch (e: Exception) { /* 忽略 */ }
        try {
            java.io.File(externalSnapshotDir(), fileName).delete()
        } catch (e: Exception) { /* 忽略 */ }
    }

    fun listLog(c: Context): List<LogEntry> {
        val arr = readLogArr(c)
        val out = ArrayList<LogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            var state = o.optString("stateJson").takeIf { it != "null" && it.isNotBlank() }
            // ★ 若对局快照落盘保存，则从文件读回（内部优先，被清后回退外部 HHXQ 镜像）
            val stateFile = o.optString("stateFile")
            if (state == null && stateFile.isNotBlank()) {
                state = try {
                    val f = java.io.File(java.io.File(c.filesDir, "vault_games"), stateFile)
                    if (f.exists()) f.readText().takeIf { it.isNotBlank() }
                    else {
                        val ef = java.io.File(externalSnapshotDir(), stateFile)
                        if (ef.exists()) ef.readText().takeIf { it.isNotBlank() } else null
                    }
                } catch (e: Exception) { null }
            }
            out.add(
                LogEntry(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    source = o.optString("source"),
                    fileName = o.optString("fileName"),
                    bookIndex = o.optInt("bookIndex"),
                    bookName = o.optString("bookName"),
                    catName = o.optString("catName"),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                    stateJson = state,
                    movesCount = o.optInt("movesCount"),
                    reading = o.optString("reading"),
                    note = o.optString("note"),
                    bookmark = o.optBoolean("bookmark")
                )
            )
        }
        // 按最后更新倒序
        return out.sortedByDescending { it.updatedAt }
    }

    fun findLog(c: Context, id: String): LogEntry? =
        listLog(c).firstOrNull { it.id == id }

    fun deleteLog(c: Context, id: String) {
        val arr = readLogArr(c)
        val list = (0 until arr.length()).map { arr.optJSONObject(it) }.toMutableList()
        list.removeAll {
            if (it.optString("id") == id) {
                deleteStateFile(c, it.optString("stateFile"))
                true
            } else false
        }
        commit(c, readGamesObj(c), JSONArray(list))
    }

    /** ★ 重命名一条对局日志（我的对局管理用） */
    fun renameLog(c: Context, id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        val arr = readLogArr(c)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null && o.optString("id") == id) {
                o.put("title", newTitle.take(60))
                o.put("updatedAt", System.currentTimeMillis())
                break
            }
        }
        commit(c, readGamesObj(c), arr)
    }

    /**
     * ★ 标签化搜索：限定 7 天之内、最多 200 条。
     * @param readingFilter 阅读程度（可空＝全部）
     * @param anyTag 印象标签集合（可空＝全部）— 命中任一即可
     * @param bookmarkOnly 只看书签
     * @param withinMs 时间窗口（默认 7 天）
     * @param cap 上限（默认 200）
     */
    fun search(
        c: Context,
        readingFilter: String?,
        anyTag: Set<String>?,
        bookmarkOnly: Boolean,
        withinMs: Long = 7L * 24 * 3600 * 1000,
        cap: Int = 200
    ): List<LogEntry> {
        val now = System.currentTimeMillis()
        val floor = now - withinMs
        val res = listLog(c).filter {
            it.updatedAt >= floor
        }.filter {
            readingFilter.isNullOrBlank() || it.reading == readingFilter
        }.filter {
            if (bookmarkOnly) it.bookmark else true
        }.filter {
            if (anyTag.isNullOrEmpty()) true else {
                // 命中任一印象标签（从该局 reader 记录取）
                val rec = tryGetRecordByKey(c, it.id)
                rec != null && rec.tags.any { t -> anyTag.contains(t) }
            }
        }
        return res.take(cap)
    }

    private fun tryGetRecordByKey(c: Context, key: String): GameRecord? {
        val g = readGamesObj(c).optJSONObject(key) ?: return null
        val hashIdx = key.lastIndexOf('#')
        val file = if (hashIdx >= 0) key.substring(0, hashIdx) else key
        val index = if (hashIdx >= 0) key.substring(hashIdx + 1).toIntOrNull() ?: 0 else 0
        return GameRecord(
            ref = GameRef("", "", file, index, ""),
            tags = parseTags(g),
            note = g.optString("note"),
            bookmark = g.optBoolean("bookmark"),
            reading = g.optString("reading"),
            lastOpen = g.optLong("lastOpen"),
            openCount = g.optInt("openCount")
        )
    }

    // ══════════════════════════ 阅读印象 透纱窗 ══════════════════════════

    /**
     * ★ 全屏半透明「阅读印象」窗：列出本机已标记的阅读印象（含标签/阅读程度/书签），
     * 并结合印象标签与阅读程度做 7 天内过滤。视觉效果与棋谱库「圈二」统一
     * （深檀半透明底 + 金边 + 粉白字）。
     * 每条记录即一条热链接：点行回到该局；点行尾「✎标」就地改印象。
     * @param onJump 点某条记录时的跳转回调（回到对应棋局/对局）
     * @return 构建好的 Dialog（如需直接弹出可调 show()），失败返回 null
     */
    fun openImpressionDialog(
        c: Context,
        onJump: ((LogEntry) -> Unit)? = null
    ): android.app.Dialog? {
        return try {
            val gold = 0xFFFFB300.toInt()
            val dark = 0xFF2B2015.toInt()
            val panelBg = 0xFF3A2C1E.toInt()
            val textMain = 0xFFF0E6D2.toInt()   // ★ 粉白色
            val textSub = 0xFFC0B49A.toInt()
            val translucent = 0xCC2B2015.toInt()

            val d = android.app.Dialog(c)
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            d.setCancelable(true)
            d.setCanceledOnTouchOutside(true)

            val root = android.widget.LinearLayout(c)
            root.orientation = android.widget.LinearLayout.VERTICAL
            root.setBackgroundColor(translucent)
            root.setPadding(16, 18, 16, 12)

            // ── 标题
            val title = android.widget.TextView(c)
            title.text = "阅读印象"
            title.setTextColor(gold)
            title.textSize = 18f
            title.setTypeface(null, android.graphics.Typeface.BOLD)
            title.gravity = android.view.Gravity.CENTER
            root.addView(title)

            // ── 全部阅读程度标签（点击即筛选）
            val readingRow = android.widget.LinearLayout(c)
            readingRow.orientation = android.widget.LinearLayout.HORIZONTAL
            readingRow.gravity = android.view.Gravity.CENTER
            val rTags = readingTags()
            val readingSel = arrayOf("")   // 当前筛选的阅读程度
            for (t in rTags) {
                val b = mkImpressionChip(c, t, gold, panelBg, textMain)
                b.setOnClickListener {
                    @Suppress("UNUSED_EXPRESSION")
                    val prev = readingSel[0]
                    if (prev == t) readingSel[0] = "" else readingSel[0] = t
                    /* 重建列表 */
                    rebuildImpressionList(c, root, readingSel[0], emptySet(), false, d, onJump, gold, panelBg, textMain, textSub)
                }
                readingRow.addView(b)
            }
            root.addView(readingRow)

            // ── 印象标签横排（可全选，选中即组合筛选）
            val tagRow = android.widget.LinearLayout(c)
            tagRow.orientation = android.widget.LinearLayout.HORIZONTAL
            tagRow.gravity = android.view.Gravity.CENTER
            root.addView(tagRow)
            val selTags = java.util.LinkedHashSet<String>()

            // ── 书签开关 + 关闭
            val btnRow = android.widget.LinearLayout(c)
            btnRow.orientation = android.widget.LinearLayout.HORIZONTAL
            btnRow.gravity = android.view.Gravity.CENTER
            val btnBookmark = mkImpressionChip(c, "⭐ 只看书签", gold, panelBg, textMain)
            btnBookmark.setOnClickListener {
                @Suppress("UNUSED_EXPRESSION")
                val bm = btnBookmark.text.toString()
                if (bm.startsWith("⭐")) btnBookmark.text = "☆ 只看书签" else btnBookmark.text = "⭐ 只看书签"
                rebuildImpressionList(c, root, readingSel[0], selTags, bm.startsWith("☆") , d, onJump, gold, panelBg, textMain, textSub)
            }
            btnBookmark.text = "☆ 只看书签"
            val btnClose = mkImpressionChip(c, "✕ 关闭", gold, panelBg, textMain)
            btnClose.setOnClickListener { d.dismiss() }
            btnRow.addView(btnBookmark)
            btnRow.addView(btnClose)
            root.addView(btnRow)

            // ── 标签组合筛选：动态填充
            for (t in IMPRESSION_TAGS) {
                val b = mkImpressionChip(c, t, gold, panelBg, textMain)
                b.setOnClickListener {
                    @Suppress("UNUSED_EXPRESSION")
                    val checked = selTags.contains(t)
                    if (checked) selTags.remove(t) else selTags.add(t)
                    b.setBackgroundColor(if (checked) dark else panelBg)
                    b.setTextColor(if (checked) textSub else textMain)
                    rebuildImpressionList(c, root, readingSel[0], selTags, btnBookmark.text.toString().startsWith("☆"), d, onJump, gold, panelBg, textMain, textSub)
                }
                tagRow.addView(b)
            }

            // ── 结果列表（占滚动区）
            rebuildImpressionList(c, root, readingSel[0], selTags, false, d, onJump, gold, panelBg, textMain, textSub)

            // 窗口参数：全屏 + 半透明
            d.setContentView(root)
            val wm = d.window ?: return null
            wm.setBackgroundDrawableResource(android.R.color.transparent)
            val lp = android.view.WindowManager.LayoutParams()
            lp.copyFrom(wm.attributes)
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
            wm.attributes = lp
            d
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 构建一个「印象/阅读」小胶囊按钮 */
    private fun mkImpressionChip(
        c: Context,
        label: String,
        gold: Int,
        panelBg: Int,
        textMain: Int
    ): android.widget.Button {
        val b = android.widget.Button(c)
        b.text = label
        b.textSize = 12f
        b.setTextColor(textMain)
        b.setBackgroundColor(panelBg)
        b.setPadding(12, 6, 12, 6)
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(3, 3, 3, 3)
        b.layoutParams = lp
        return b
    }

    // ════════════════════════ 本局印象编辑器 & 日志联动 ════════════════════════

    /** 读取某条对局日志的读者记录（按日志 id 定位 games 表），不存在则返回空记录。 */
    fun getRecordByLog(c: Context, e: LogEntry): GameRecord =
        tryGetRecordByKey(c, e.id)
            ?: GameRecord(GameRef(e.catName, e.bookName, e.fileName, e.bookIndex, e.title))

    /** 为某条对局日志写阅读印象（阅读程度/印象标签/备注/书签）。同时更新日志与 games 表。 */
    fun setImpressionForLog(
        c: Context, e: LogEntry,
        reading: String, tags: Set<String>, note: String, bookmark: Boolean
    ) {
        val arr = readLogArr(c)
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null && o.optString("id") == e.id) {
                o.put("reading", reading); o.put("note", note)
                o.put("bookmark", bookmark); o.put("updatedAt", now)
            }
        }
        val g = readGamesObj(c)
        val o = JSONObject()
        o.put("tags", JSONArray(tags.toList()))
        o.put("note", note); o.put("bookmark", bookmark); o.put("reading", reading)
        val old = g.optJSONObject(e.id)
        o.put("lastOpen", if (old != null) old.optLong("lastOpen", now) else now)
        o.put("openCount", if (old != null) old.optInt("openCount") + 1 else 1)
        g.put(e.id, o)
        commit(c, g, arr)
    }

    /**
     * ★ 本局印象编辑器：阅读程度(单选) + 印象标签(多选) + 写备注 + 书签，点【保存】回调保存。
     * @param onSave 回调参数 (reading, tags, note, bookmark)
     */
    fun openEditImpressionDialog(
        c: Context,
        title: String,
        initialReading: String,
        initialTags: Set<String>,
        initialNote: String,
        initialBookmark: Boolean,
        onSave: (String, Set<String>, String, Boolean) -> Unit
    ): android.app.Dialog? {
        return try {
            val gold = 0xFFFFB300.toInt(); val dark = 0xFF2B2015.toInt()
            val panelBg = 0xFF3A2C1E.toInt(); val textMain = 0xFFF0E6D2.toInt()
            val textSub = 0xFFC0B49A.toInt()
            val d = android.app.Dialog(c)
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            d.setCancelable(true); d.setCanceledOnTouchOutside(true)

            val root = android.widget.LinearLayout(c)
            root.orientation = android.widget.LinearLayout.VERTICAL
            root.setBackgroundColor(panelBg); root.setPadding(18, 16, 18, 14)

            root.addView(android.widget.TextView(c).apply {
                text = "标 本局印象"; setTextColor(gold); textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER; setPadding(0, 0, 0, 2)
            })
            root.addView(android.widget.TextView(c).apply {
                text = title; setTextColor(textMain); textSize = 14f
                gravity = android.view.Gravity.CENTER; setPadding(0, 0, 0, 10)
            })

            // ── 阅读程度（单选）
            root.addView(android.widget.TextView(c).apply {
                text = "阅读程度（单选）"; setTextColor(textSub); textSize = 12f; setPadding(0, 2, 0, 2)
            })
            val readingSel = arrayOf(initialReading.ifBlank { "" })
            val rRow = android.widget.LinearLayout(c).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
            for (t in readingTags()) {
                val b = mkImpressionChip(c, t, gold, panelBg, textMain)
                val selected = t == readingSel[0]
                b.setBackgroundColor(if (selected) dark else panelBg)
                b.setTextColor(if (selected) gold else textMain)
                b.setOnClickListener {
                    readingSel[0] = if (readingSel[0] == t) "" else t
                    for (k in 0 until rRow.childCount) {
                        val ch = rRow.getChildAt(k) as? android.widget.Button ?: continue
                        val sel = ch.text == readingSel[0]
                        ch.setBackgroundColor(if (sel) dark else panelBg)
                        ch.setTextColor(if (sel) gold else textMain)
                    }
                }
                rRow.addView(b)
            }
            root.addView(rRow)

            // ── 印象标签（多选）
            root.addView(android.widget.TextView(c).apply {
                text = "印象标签（多选）"; setTextColor(textSub); textSize = 12f; setPadding(0, 8, 0, 2)
            })
            val tagSet = java.util.LinkedHashSet(initialTags.toList())
            val tRow = android.widget.LinearLayout(c).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
            for (t in IMPRESSION_TAGS) {
                val b = mkImpressionChip(c, t, gold, panelBg, textMain)
                val selected = tagSet.contains(t)
                b.setBackgroundColor(if (selected) dark else panelBg)
                b.setTextColor(if (selected) gold else textMain)
                b.setOnClickListener {
                    if (tagSet.contains(t)) tagSet.remove(t) else tagSet.add(t)
                    b.setBackgroundColor(if (tagSet.contains(t)) dark else panelBg)
                    b.setTextColor(if (tagSet.contains(t)) gold else textMain)
                }
                tRow.addView(b)
            }
            root.addView(tRow)

            // ── 备注
            root.addView(android.widget.TextView(c).apply {
                text = "备注 / 写几句印象：" ; setTextColor(textSub); textSize = 12f; setPadding(0, 8, 0, 2)
            })
            val note = android.widget.EditText(c).apply {
                setText(initialNote)
                textSize = 14f; setTextColor(textMain)
                setHintTextColor(textSub)
                hint = "写下这局对你的印象、要点…"
                setBackgroundColor(0xFF1E1A140E.toInt())
                setPadding(10, 8, 10, 8)
                setSingleLine(false)
                minLines = 3; maxLines = 5
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            root.addView(note)

            // ── 书签 + 按钮
            val bmSel = arrayOf(initialBookmark)
            val bmBtn = mkImpressionChip(c, if (bmSel[0]) "⭐ 已书签" else "☆ 书签（快速跳转）", gold, panelBg, textMain).apply {
                setOnClickListener {
                    bmSel[0] = !bmSel[0]
                    text = if (bmSel[0]) "⭐ 已书签" else "☆ 书签（快速跳转）"
                    setBackgroundColor(if (bmSel[0]) dark else panelBg)
                    setTextColor(if (bmSel[0]) gold else textMain)
                }
            }

            val btnSave = android.widget.Button(c).apply {
                text = "✔ 保存"; textSize = 14f; setTextColor(0xFF1E3A10.toInt())
                setBackgroundColor(gold); setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(0, 44, 1f)
                setOnClickListener {
                    val tags = tagSet.toSet()
                    onSave(readingSel[0], tags, note.text.toString().trim(), bmSel[0])
                    d.dismiss()
                }
            }
            val btnCancel = android.widget.Button(c).apply {
                text = "取消"; textSize = 14f; setTextColor(textMain)
                setBackgroundColor(0xFF1E1A140E.toInt()); setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(0, 44, 1f)
                setOnClickListener { d.dismiss() }
            }
            val btnRow = android.widget.LinearLayout(c).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER; setPadding(0, 10, 0, 0)
            }
            btnRow.addView(btnCancel); btnRow.addView(bmBtn); btnRow.addView(btnSave)
            root.addView(btnRow)

            d.setContentView(root)
            val wm = d.window ?: return null
            wm.setBackgroundDrawableResource(android.R.color.transparent)
            val lp = android.view.WindowManager.LayoutParams()
            lp.copyFrom(wm.attributes)
            lp.width = (c.resources.displayMetrics.widthPixels * 0.92f).toInt()
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            wm.attributes = lp
            d
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 按当前筛选条件重建「阅读印象」结果列表 */
    private fun rebuildImpressionList(
        c: Context,
        root: android.widget.LinearLayout,
        readingFilter: String,
        anyTags: Set<String>,
        bookmarkOnly: Boolean,
        dialog: android.app.Dialog,
        onJump: ((LogEntry) -> Unit)?,
        gold: Int,
        panelBg: Int,
        textMain: Int,
        textSub: Int
    ) {
        try {
            // 移除旧列表
            for (i in root.childCount - 1 downTo 0) {
                val v = root.getChildAt(i)
                if (v.tag == "imp_list") root.removeViewAt(i)
            }
            val results = search(
                c,
                readingFilter.takeIf { it.isNotBlank() },
                anyTags.takeIf { it.isNotEmpty() },
                bookmarkOnly
            )

            val tv = android.widget.TextView(c)
            tv.text = "筛选（7天内 · 上限200条）共 ${results.size} 条 · 点行＝回到该局，点✎标＝改印象"
            tv.setTextColor(textSub)
            tv.textSize = 12f
            tv.setPadding(2, 6, 2, 2)
            tv.tag = "imp_list"
            root.addView(tv)

            if (results.isEmpty()) {
                val emptyTv = android.widget.TextView(c)
                emptyTv.text = "（暂无符合条件的记录，7天内标记的才会出现在这里）"
                emptyTv.setTextColor(textSub)
                emptyTv.textSize = 13f
                emptyTv.setPadding(4, 10, 4, 4)
                emptyTv.tag = "imp_list"
                root.addView(emptyTv)
                return
            }

            val sv = android.widget.ScrollView(c)
            sv.tag = "imp_list"
            sv.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            val inner = android.widget.LinearLayout(c)
            inner.orientation = android.widget.LinearLayout.VERTICAL
            sv.addView(inner)
            root.addView(sv)

            for (e in results) {
                val rec = getRecordByLog(c, e)
                val tagDesc = buildString {
                    append(e.reading.takeIf { it.isNotBlank() } ?: "未标")
                    if (rec.tags.isNotEmpty()) append(" · ").append(rec.tags.joinToString("、"))
                    if (e.bookmark) append(" · ⭐")
                    if (e.note.isNotBlank()) append(" · ").append(e.note)
                }
                // ── 一行：可点击主体 + 行尾「✎标」
                val row = android.widget.LinearLayout(c)
                row.orientation = android.widget.LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.setBackgroundColor(if (e.reading.isNotBlank() || rec.tags.isNotEmpty()) 0xFF2B2015.toInt() else panelBg)
                row.setPadding(6, 6, 6, 6)
                val rlp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                rlp.setMargins(0, 0, 0, 6)
                row.layoutParams = rlp

                val body = android.widget.TextView(c)
                body.text = "${e.title}\n    ${tagDesc}"
                body.setTextColor(if (e.reading.isNotBlank() || rec.tags.isNotEmpty()) textMain else textSub)
                body.textSize = 14f
                body.setPadding(4, 2, 4, 2)
                body.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                // 点行主体：热链接回到该局
                body.setOnClickListener {
                    if (onJump != null) {
                        dialog.dismiss()
                        onJump(e)
                    }
                }

                val editB = android.widget.Button(c)
                editB.text = "✎标"
                editB.textSize = 11f
                editB.setTextColor(gold)
                editB.setBackgroundColor(panelBg)
                editB.setTypeface(null, android.graphics.Typeface.BOLD)
                editB.setPadding(10, 6, 10, 6)
                editB.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // 点「✎标」：就地打开编辑器，改完刷新本列表
                editB.setOnClickListener {
                    openEditImpressionDialog(
                        c, e.title,
                        e.reading, rec.tags, e.note, e.bookmark
                    ) { reading, tags, note, bm ->
                        setImpressionForLog(c, e, reading, tags, note, bm)
                        rebuildImpressionList(c, root, readingFilter, anyTags, bookmarkOnly, dialog, onJump, gold, panelBg, textMain, textSub)
                    }?.show()
                }

                row.addView(body)
                row.addView(editB)
                inner.addView(row)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}