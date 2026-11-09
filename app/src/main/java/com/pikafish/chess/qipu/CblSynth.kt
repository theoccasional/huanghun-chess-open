package com.pikafish.chess.qipu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

/**
 * CBL 合成区：把「我的上传 / 本地文档 / 挑战棋谱 / 内置宝库 / 我的对局」里的棋谱条目
 * 汇集到一处，支持 重命名 / 上移下移 / 按名或按时间排序 / 勾选 / 逐条删除 / 清空，
 * 并可按当前顺序把选中的条目合成为一个纯 CBL 棋库文件（导出位置可自定义）。
 *
 * ★ 只记「引用」不搬原文件：内置宝库只读、用户文件原地不动；
 *   真正的解码/编码只在导出那一刻进行，导出后合成区可整体清空以省空间。
 */
object CblSynth {

    private const val PREFS = "cbl_synth_v1"
    private const val KEY = "items"

    /** 条目来源：某本书里的一则（各种书通用） */
    const val KIND_BOOK = "book"

    /** 条目来源：我的对局（续盘存档） */
    const val KIND_JOURNAL = "journal"

    /** ★ index 取此值 = 「整本书全要」：导出时把该书里每一则都展开成一条，逐条编码。 */
    const val INDEX_WHOLE = -1

    /** 合成区里的一则（引用，不含棋谱本体）。 */
    data class Item(
        var id: String = "",
        var name: String = "",
        var kind: String = KIND_BOOK,
        var cat: String = "",       // 分类名（内置/我的上传/...）
        var bookName: String = "",  // 书名（显示 + 解码）
        var file: String = "",      // 书文件（QipuLibrary.loadGamesBytes 用）
        var index: Int = 0,         // 书内序号
        var isUserUpload: Boolean = false,
        var isLocalDoc: Boolean = false,
        var logId: String = "",     // 我的对局 id
        var addedAt: Long = 0L
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(c: Context): MutableList<Item> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        kind = o.optString("kind", KIND_BOOK),
                        cat = o.optString("cat"),
                        bookName = o.optString("bookName"),
                        file = o.optString("file"),
                        index = o.optInt("index"),
                        isUserUpload = o.optBoolean("isUserUpload"),
                        isLocalDoc = o.optBoolean("isLocalDoc"),
                        logId = o.optString("logId"),
                        addedAt = o.optLong("addedAt")
                    )
                )
            }
            out
        } catch (e: Exception) { ArrayList() }
    }

    fun count(c: Context): Int = list(c).size

    private fun save(c: Context, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("kind", it.kind)
                put("cat", it.cat); put("bookName", it.bookName); put("file", it.file)
                put("index", it.index); put("isUserUpload", it.isUserUpload)
                put("isLocalDoc", it.isLocalDoc); put("logId", it.logId); put("addedAt", it.addedAt)
            })
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    /** 同一来源是否已在合成区（避免重复添加同一则）。 */
    private fun isDup(items: List<Item>, item: Item): Boolean = items.any {
        it.kind == item.kind && (
            if (item.kind == KIND_JOURNAL) it.logId == item.logId
            else it.file == item.file && it.index == item.index
            )
    }

    /** 加入一则；已存在则不重复加。返回是否新增。 */
    fun add(c: Context, item: Item): Boolean {
        val items = list(c)
        if (isDup(items, item)) return false
        if (item.id.isBlank()) item.id = "s_" + System.currentTimeMillis() + "_" + (items.size + 1)
        if (item.addedAt <= 0) item.addedAt = System.currentTimeMillis()
        items.add(item)
        save(c, items)
        return true
    }

    fun remove(c: Context, id: String) {
        val items = list(c); items.removeAll { it.id == id }; save(c, items)
    }

    fun removeMany(c: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        val items = list(c); items.removeAll { it.id in ids }; save(c, items)
    }

    fun rename(c: Context, id: String, name: String) {
        val items = list(c)
        items.firstOrNull { it.id == id }?.name = name.take(60)
        save(c, items)
    }

    fun clear(c: Context) { save(c, emptyList()) }

    /** 上移一位（在合成区里的先后顺序即导出顺序）。 */
    fun moveUp(c: Context, id: String) {
        val items = list(c)
        val i = items.indexOfFirst { it.id == id }
        if (i > 0) { val t = items[i]; items[i] = items[i - 1]; items[i - 1] = t; save(c, items) }
    }

    fun moveDown(c: Context, id: String) {
        val items = list(c)
        val i = items.indexOfFirst { it.id == id }
        if (i in 0 until items.size - 1) { val t = items[i]; items[i] = items[i + 1]; items[i + 1] = t; save(c, items) }
    }

    fun sortByName(c: Context) { save(c, list(c).sortedBy { it.name }) }

    fun sortByTime(c: Context) { save(c, list(c).sortedBy { it.addedAt }) }

    // ════════════════════════ 解析与导出 ════════════════════════

    /** 解析某书为棋局列表（导出时用，按文件缓存；失败返回空表）。 */
    private fun decodeBook(
        context: Context,
        item: Item,
        bookCache: MutableMap<String, List<QipuGame>>
    ): List<QipuGame> = decodeBookFile(context, item.file, item.bookName, item.isUserUpload, item.isLocalDoc, bookCache)

    /**
     * ★ 按文件解码一本书：依次尝试 [isLocalDoc=true, isUserUpload=true, false] 三种来源
     *   （HHXQ 本地文档 / 我的上传 / 内置 assets），哪种能解出棋局就用哪种。
     *   这样即便条目的来源标志不完整（如「我的对局」回指某书时只存了文件名），也能解出来。
     */
    private fun decodeBookFile(
        context: Context,
        file: String,
        bookName: String,
        userUpload: Boolean,
        localDoc: Boolean,
        bookCache: MutableMap<String, List<QipuGame>>
    ): List<QipuGame> {
        if (file.isBlank()) return emptyList()
        bookCache[file]?.let { return it }
        // (isLocalDoc, isUserUpload) 依次尝试：HHXQ 本地文档 → 我的上传 → 内置 assets
        val combos = listOf(
            localDoc to userUpload,
            true to userUpload,
            false to true,
            false to false
        )
        for ((ld, uu) in combos) {
            try {
                val book = QipuBook(name = bookName, file = file, rawSize = 0, isUserUpload = uu, isLocalDoc = ld)
                val games = QipuLibrary.decodeGames(QipuLibrary.loadGamesBytes(context, book), bookName)
                if (games.isNotEmpty()) { bookCache[file] = games; return games }
            } catch (e: Exception) { /* 换下一种来源再试 */ }
        }
        bookCache[file] = emptyList()
        return emptyList()
    }

    /** 解析一则为棋局（导出时用）。解不出有效招法返回 null。 */
    fun resolve(
        context: Context,
        item: Item,
        bookCache: MutableMap<String, List<QipuGame>>
    ): CblDecoder.CblGame? {
        return try {
            val g = when (item.kind) {
                KIND_JOURNAL -> resolveJournal(context, item, bookCache)
                else -> decodeBook(context, item, bookCache).getOrNull(item.index)?.cblGame
            }
            // ★ 只丢弃「根本解不出」(null) 的；「只有局面、没有任何着法」的成批 FEN 习题
            //   （5-10步缓杀 50000题 这类）必须保留 —— 导出那一刻由 CblDecoder.encodeLegacyRecord
            //   对该局面补一手占位着法。原因：象棋桥/CNVCS 把「步数 == 0」的记录当成章标题/空条目，
            //   若整本书几十万则全是这种记录，第三方算出的棋谱数是 0 → 整本书被判「无法识别」。
            if (g == null) null else g
        } catch (e: Exception) { null }
    }

    /**
     * ★ 展开「整本书」(index = INDEX_WHOLE) 为逐则条目 —— 导出时每则单独编码。
     *   其余条目原样保留，顺序不变。
     */
    private fun expandWholeBooks(
        context: Context,
        items: List<Item>,
        bookCache: MutableMap<String, List<QipuGame>>
    ): List<Item> {
        val out = ArrayList<Item>(items.size)
        for (it in items) {
            if (it.kind != KIND_JOURNAL && it.index == INDEX_WHOLE) {
                val games = try { decodeBook(context, it, bookCache) } catch (e: Exception) { emptyList() }
                for (i in games.indices) out.add(it.copy(index = i))
            } else out.add(it)
        }
        return out
    }

    /**
     * ★ 我的对局（续盘存档）→ 棋局。三种存档形态都要能解：
     *   ① 完整状态快照（含 moveHistoryUci / variationBranches / moveComments）→ 直接取；
     *   ② 宝库存档（refType=txtfile，givenTxt 是"中文记谱+变招"文本）→ 用 NotationParser 重建；
     *   ③ 棋谱库"来过了"条目（无快照，只有 fileName+bookIndex）→ 回指该书该则去解。
     *   以往只认 ①，②③ 全被丢掉 —— 这正是"选了好几则只写出打头那一则"的根因。
     */
    private fun resolveJournal(
        context: Context,
        item: Item,
        bookCache: MutableMap<String, List<QipuGame>>
    ): CblDecoder.CblGame? {
        val entry = GameJournal.findLog(context, item.logId) ?: return null
        val stateJson = entry.stateJson

        // ① / ②：有快照（完整状态 或 内嵌 TXT）
        if (!stateJson.isNullOrBlank()) {
            try {
                val json = JSONObject(stateJson)
                if (json.optString("refType") == "txtfile") {
                    var given = json.optString("givenTxt").trim()
                    if (given.isBlank()) {
                        // givenTxt 缺失 → 回退读保存下来的本地 txt 文件
                        val saved = json.optString("txtPath").trim()
                        if (saved.isNotBlank()) given = readVaultTxt(context, saved)
                    }
                    if (given.isNotBlank()) fromNotationText(item.name, given)?.let { return it }
                } else {
                    val movesArr = json.optJSONArray("moveHistoryUci")
                    if (movesArr != null && movesArr.length() > 0) {
                        fromFullState(item.name, json, movesArr)?.let { return it }
                    }
                }
            } catch (e: Exception) { /* 落到下面回指解析 */ }
        }

        // ③：回指某书某则（棋谱库"来过了"条目；fileName 存的正是书文件）
        val file = entry.fileName
        if (file.isNotBlank() && !file.endsWith(".txt", true) && !file.endsWith(".json", true)) {
            val games = decodeBookFile(context, file, entry.bookName.ifBlank { item.name }, false, false, bookCache)
            games.getOrNull(entry.bookIndex)?.cblGame?.let { if (it.movesUci.isNotEmpty()) return it }
        }
        return null
    }

    /** 从完整状态快照 JSON 构造棋局（主线 + 变招 + 注释 + 残局初始局面）。 */
    private fun fromFullState(name: String, json: JSONObject, movesArr: JSONArray): CblDecoder.CblGame? {
        val moves = ArrayList<String>(movesArr.length())
        for (i in 0 until movesArr.length()) moves.add(movesArr.getString(i))
        if (moves.isEmpty()) return null
        val comments = LinkedHashMap<Int, String>()
        json.optJSONObject("moveComments")?.let { o ->
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val idx = k.toIntOrNull() ?: continue
                val v = o.optString(k)
                if (v.isNotBlank()) comments[idx] = v
            }
        }
        val vars = fullVariationLines(moves, json.optJSONArray("variationBranches"))
        // 初始局面：非标准开局（摆子/残局）要带上棋子板与轮次，否则编码会当标准开局处理。
        val fenFull = json.optJSONArray("boardSnapshots")?.optString(0)?.trim().orEmpty()
        val parts = fenFull.split(" ")
        val boardPart = parts.getOrNull(0).orEmpty()
        val moversBlack = parts.getOrNull(1) == "b"
        val fenBoard = boardPart.takeIf { it.isNotBlank() && it != CblDecoder.STD_FEN }
        return CblDecoder.CblGame(
            index = 0, event = name, site = "", date = "", red = "", black = "", result = "",
            fenBoard = fenBoard, moversBlack = moversBlack,
            movesUci = moves, introComment = "", endComment = "",
            midComments = emptyList(), moveComments = comments, commentaryBlocks = emptyList(),
            variationsUci = vars
        )
    }

    /** 从"中文记谱+变招"文本重建棋局（宝库存档 givenTxt 用）。 */
    private fun fromNotationText(name: String, text: String): CblDecoder.CblGame? {
        return try {
            val parsed = com.pikafish.chess.engine.NotationParser.parse(text, CblDecoder.STD_FEN)
            if (parsed.mainLine.isEmpty()) return null
            val vars = com.pikafish.chess.engine.NotationParser.flattenLines(parsed.mainLine, parsed.variations)
            CblDecoder.CblGame(
                index = 0, event = name, site = "", date = "", red = "", black = "", result = "",
                fenBoard = null, moversBlack = false,
                movesUci = parsed.mainLine, introComment = "", endComment = "",
                midComments = emptyList(), moveComments = parsed.comments, commentaryBlocks = emptyList(),
                variationsUci = vars
            )
        } catch (e: Exception) { null }
    }

    /**
     * 变着树 → 完整 UCI 线路（每条都从起始局面起）。
     * 与 ChessGame.bookLineAllLines 同算法：顶层 branchStep 为绝对主线步数，嵌套为相对宿主偏移。
     */
    private fun fullVariationLines(mainLine: List<String>, varArr: JSONArray?): List<List<String>> {
        if (varArr == null || varArr.length() == 0) return emptyList()
        data class V(val branchStep: Int, val moves: List<String>, val subs: List<V>)
        fun rd(o: JSONObject): V {
            val mv = ArrayList<String>()
            o.optJSONArray("moves")?.let { for (i in 0 until it.length()) mv.add(it.getString(i)) }
            val subs = ArrayList<V>()
            o.optJSONArray("sub")?.let { for (i in 0 until it.length()) rd(it.getJSONObject(i)).let(subs::add) }
            return V(o.optInt("branchStep"), mv, subs)
        }
        val tops = ArrayList<V>()
        for (i in 0 until varArr.length()) tops.add(rd(varArr.getJSONObject(i)))
        val out = ArrayList<List<String>>()
        fun expand(hostFull: List<String>, hostOffset: Int, subs: List<V>) {
            for (v in subs) {
                if (v.moves.isEmpty()) continue
                val step = (hostOffset + v.branchStep).coerceIn(0, hostFull.size)
                val full = ArrayList<String>(step + v.moves.size)
                full.addAll(hostFull.subList(0, step))
                full.addAll(v.moves)
                out.add(full)
                expand(full, step, v.subs)
            }
        }
        expand(mainLine, 0, tops)
        return out
    }

    /** 读宝库存档落盘的本地 TXT（HHXQ 或内部存储），返回文本；读不到返回空串。 */
    private fun readVaultTxt(context: Context, savedPath: String): String {
        return try {
            val fileName = savedPath.substringAfterLast('/')
            val f = if (savedPath.startsWith("内部存储"))
                java.io.File(context.filesDir, fileName)
            else
                java.io.File(java.io.File(android.os.Environment.getExternalStorageDirectory(), "HHXQ"), fileName)
            if (f.exists() && f.isFile) {
                val raw = f.readBytes()
                // GBK / UTF-8 自动识别（本地 TXT 多为 GBK）
                for (cs in listOf(Charsets.UTF_8, Charset.forName("GBK"))) {
                    try {
                        val s = String(raw, cs)
                        if (s.isNotBlank() && !s.contains('\uFFFD')) return s
                    } catch (_: Exception) {}
                }
                String(raw, Charset.forName("GBK"))
            } else ""
        } catch (e: Exception) { "" }
    }

    /** 结果：成功时 written 为编码局数、path 为最终落盘路径；失败时 err 非空。 */
    class ExportResult(val written: Int, val path: String, val err: String)

    /**
     * 把 items（按当前顺序）合成为一个纯 CBL 棋库。
     * @param dirInput 用户选择的目录（可为相对名，如 "HHXQ/导出"，相对外部存储根；也可绝对路径）
     * @param fileName 文件名（不含扩展名）
     */
    fun exportCbl(
        context: Context, items: List<Item>, fileName: String, dirInput: String
    ): ExportResult {
        if (items.isEmpty()) return ExportResult(0, "", "合成区没有条目")
        val safeName = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "合成棋谱" }
        val name = if (safeName.lowercase().endsWith(".cbl")) safeName else "$safeName.cbl"

        val dirs = ArrayList<java.io.File>()
        val di = dirInput.trim()
        if (di.isNotEmpty()) {
            dirs.add(
                if (di.startsWith("/")) java.io.File(di)
                else java.io.File(android.os.Environment.getExternalStorageDirectory(), di)
            )
        }
        // 兜底目录：外部 HHXQ/导出 → 应用外部目录 → 应用内部目录
        dirs.add(java.io.File(android.os.Environment.getExternalStorageDirectory(), "HHXQ/导出"))
        context.getExternalFilesDir(null)?.let { dirs.add(it) }
        dirs.add(context.filesDir)

        var written = 0
        var lastErr = ""
        // ★ 整本书先展开成逐则条目（每则一行），再逐条流式编码；解码结果按文件缓存，避免重复解。
        val bookCache = HashMap<String, List<QipuGame>>()
        val flat = try { expandWholeBooks(context, items, bookCache) } catch (e: Exception) { emptyList() }
        if (flat.isEmpty()) return ExportResult(0, "", "这些条目都解不出可导出的招法")
        for (d in dirs) {
            try {
                if (!d.exists()) d.mkdirs()
                val f = java.io.File(d, name)
                written = 0
                java.io.BufferedOutputStream(java.io.FileOutputStream(f)).use { bos ->
                    // ★ 流式旧格式：逐则解析 + 逐条直写，不在内存里攒整库字节。
                    CblDecoder.writeLegacyBook(
                        bos, flat.size, safeName, CblDecoder.LegacyBookMeta(), context.cacheDir,
                        gameAt = { i ->
                            val g = resolve(context, flat[i], bookCache)
                            if (g != null) written++
                            g
                        }
                    )
                }
                if (written == 0) {
                    f.delete()
                    lastErr = "这些条目都解不出可导出的招法"
                    continue
                }
                val shown = if (d.name == "导出" && d.parentFile?.name == "HHXQ") "HHXQ/导出/$name"
                else f.absolutePath
                return ExportResult(written, shown, "")
            } catch (e: Exception) {
                lastErr = e.message ?: "写入失败"
            }
        }
        return ExportResult(0, "", lastErr.ifBlank { "写入失败" })
    }
}