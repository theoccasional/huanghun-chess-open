package com.pikafish.chess.qipu

import android.content.Context
import android.os.AsyncTask
import com.pikafish.chess.heavy.HeavyStore
import org.json.JSONObject
import java.nio.charset.Charset

/** 一本书（对应一个 CBL 原文件；isUserUpload 标用户上传的棋谱文件；isLocalDoc 标 HHXQ 本地文档/存档）
 *
 *  ★ [groupPrefix]：大包分册用。一个几百兆、几十万个条目的压缩包（或一个装满棋谱的目录），
 *    不再当成"一本书"整体塞进内存，而是按子目录拆成若干"册"，每册只认自己那一段：
 *      · isUserUpload 且 groupPrefix 非空 → file 是 zip 文件名，groupPrefix 是包内目录前缀；
 *      · isLocalDoc  且 groupPrefix 非空 → file/groupPrefix 是 HHXQ 下的相对目录。 */
data class QipuBook(
    val name: String,
    val file: String,
    val rawSize: Int,
    val mtime: Long = 0L,
    val isUserUpload: Boolean = false,
    val isLocalDoc: Boolean = false,
    val groupPrefix: String = ""
)

/** 一个分类 */
data class QipuCategory(
    val name: String,
    val books: List<QipuBook>
)

/** 一局棋（运行时解码自 CBL/MXQ/压缩包）。
 *
 * ★ 懒解码：`meta` 只含头部元信息（赛事/日期/双方/结果），着法留空；`fullLoader` 才是
 *   真正的逐着解码。列表、搜索索引只读 [title]/[meta]，因此「一个压缩包里几千局」
 *   也不会一次性把上百万着法解进内存、更不会卡界面；只有用户真正点开某一局时才解那一局。 */
class QipuGame(
    private val meta: CblDecoder.CblGame,
    private val fullLoader: (() -> CblDecoder.CblGame)? = null,
    /** ★ 是否为「局面习题」（成批 FEN 题库里的一题）：只有摆好的局面与先行方，没有回合/着法。 */
    val isFenPuzzle: Boolean = false
) {
    /** 完整棋局（含全部着法）；归档里的懒局在此按需解出并缓存。 */
    val cblGame: CblDecoder.CblGame by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (fullLoader != null && meta.movesUci.isEmpty()) {
            try { fullLoader.invoke() } catch (e: Exception) { meta }
        } else meta
    }

    /** 仅头部元信息（绝不解码着法）——供列表渲染与搜索建索引使用。 */
    fun meta(): CblDecoder.CblGame = meta

    fun title(): String = meta.title()
    fun pgn(): String = cblGame.toPgn()

    /** 开局旁白（对局背景介绍） */
    fun introComment(): String = cblGame.introComment

    /** 结局旁白（总评文字） */
    fun endComment(): String = cblGame.endComment

    /** 每步旁白（索引从0开始） */
    fun moveComments(): Map<Int, String> = cblGame.moveComments

    /** 逐坨旁白（按出生顺序），含支节标记；可能包含多条旁白 */
    fun commentaryBlocks(): List<CblDecoder.CommentaryBlock> = cblGame.commentaryBlocks

    /** 是否有任何旁白内容 */
    fun hasAnyComment(): Boolean =
        cblGame.introComment.isNotEmpty() || cblGame.endComment.isNotEmpty() || cblGame.moveComments.isNotEmpty()
            || cblGame.commentaryBlocks.isNotEmpty()

    companion object {
        /** 象棋 FEN 棋盘词：10 条横线 → 恰好 9 个 '/'。 */
        fun boardSlashes(token: String): Int = token.count { it == '/' }

        /** 一行里是否含“棋盘词 + w/b”的 FEN 片段（成批题库按行判定用）。 */
        fun lineHasFen(line: String): Boolean {
            val toks = line.trim().split(WHITESPACE)
            for (i in 0 until toks.size - 1) {
                if (boardSlashes(toks[i]) == 9 && (toks[i + 1] == "w" || toks[i + 1] == "b")) return true
            }
            return false
        }

        /**
         * ★ 解析一行「局面习题」：`[题名] <棋盘> w|b - - 0 1`。
         * 题名可省：省略时按序号命名（显示为“习题 N”，宝库行首另有序号）。
         * 解不出棋盘返回 null（该行不是 FEN，跳过）。
         */
        fun fenPuzzle(index: Int, line: String, forcedName: String? = null): QipuGame? {
            val toks = line.trim().split(WHITESPACE)
            var bi = -1
            for (i in 0 until toks.size - 1) {
                if (boardSlashes(toks[i]) == 9 && (toks[i + 1] == "w" || toks[i + 1] == "b")) { bi = i; break }
            }
            if (bi < 0) return null
            val boardFen = toks[bi]
            val moverBlack = toks[bi + 1] == "b"
            val inlineName = if (bi > 0) toks.subList(0, bi).joinToString(" ").trim() else ""
            val name = (forcedName?.trim().takeUnless { it.isNullOrEmpty() } ?: inlineName)
                .removePrefix("${index + 1}.").trim()   // 去行首“12.”式编号
                .removePrefix("${index + 1}、").trim()
            val g = CblDecoder.CblGame(
                index = index + 1,
                event = name.ifBlank { "习题" },
                site = "", date = "", red = "", black = "", result = "*",
                fenBoard = boardFen, moversBlack = moverBlack,
                movesUci = emptyList(),
                introComment = "", endComment = "",
                midComments = emptyList(), moveComments = emptyMap(),
                commentaryBlocks = emptyList()
            )
            return QipuGame(g, null, isFenPuzzle = true)
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * 本机棋谱库数据层（内置直读 CBL）。
 *
 * assets/qipu_cbl/ 下直接存放 150 个象棋桥(CBL)原文件，App 启动后运行时
 * 由 CblDecoder 直接解码，无需预先转 PGN —— 与桌面象棋桥的识读方式一致。
 *
 * 结构：
 *   assets/qipu_cbl/index.json            # 分类 -> 书（含文件名）
 *   assets/qipu_cbl/data/<cat>_<n>.cbl   # 每本原始 CBL 文件
 */
object QipuLibrary {

    const val INDEX_PATH = "qipu_cbl/index.json"
    private const val DATA_DIR = "qipu_cbl/data/"
    /** 用户上传到宝库的棋谱目录（filesDir 下，重启后仍在） */
    const val USER_DIR = "qipu_user"

    /** 大包分册的目录索引后缀（就放在 zip 旁边，形如 `xxx.zip.catalog.json`） */
    const val CATALOG_SUFFIX = ".catalog.json"

    /** 一册最多解多少局：单册再多也不会把内存顶爆（超出部分提示用别的方式看） */
    private const val GROUP_CAP = 4000

    /** 内置里「可接受本分类上传」的分类名（与 assets/qipu_cbl/index.json 对齐） */
    val BUILTIN_CATS = linkedSetOf("习题", "专著", "专辑", "五羊杯", "其它", "古谱", "开局", "残局", "赛事", "名手对局")

    /** 分册上限：病态包（几十万个子目录）也不至于把索引撑爆 */
    private const val MAX_GROUPS = 20000

    /** 分册索引里的一个分组 */
    data class ZipGroup(val prefix: String, val name: String, val count: Int)

    /** 这些扩展名才算"棋谱"，分册时只统计它们 */
    private val QIPU_EXT = setOf(
        "xqf", "mxq", "cbl", "cbr", "pgn", "txt", "ubb", "json", "qyl", "chm", "rar", "zip"
    )

    private fun isQipuName(n: String): Boolean {
        val e = n.substringAfterLast('.', "").lowercase()
        return e in QIPU_EXT
    }

    /**
     * ★ 给一个大压缩包建"分册索引"：只读 ZIP 中央目录（不解压任何条目），
     *   按每个条目所在的目录分组。327,251 个条目也只花一两秒、几 MB 内存。
     *   返回分组数；0 表示没建成（不是 zip / 打不开）。
     */
    fun buildCatalogForZip(zipFile: java.io.File): Int {
        return try {
            val counts = LinkedHashMap<String, Int>()
            java.util.zip.ZipFile(zipFile, Charsets.ISO_8859_1).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    val name = gbkName(e.name)
                    if (!isQipuName(name)) continue
                    val slash = name.lastIndexOf('/')
                    if (slash <= 0) continue          // 根目录散文件不参与分册
                    val prefix = name.substring(0, slash + 1)
                    if (counts.size >= MAX_GROUPS && !counts.containsKey(prefix)) continue  // 病态包保护
                    counts[prefix] = (counts[prefix] ?: 0) + 1
                }
            }
            if (counts.isEmpty()) return 0
            val arr = StringBuilder()
            arr.append("{\"groups\":[")
            var first = true
            for ((prefix, n) in counts) {
                if (!first) arr.append(',')
                first = false
                val gname = prefix.trimEnd('/').substringAfterLast('/')
                arr.append("{\"prefix\":").append(JSONObject.quote(prefix))
                    .append(",\"name\":").append(JSONObject.quote(gname))
                    .append(",\"count\":").append(n).append('}')
            }
            arr.append("],\"total\":").append(counts.values.sum()).append('}')
            java.io.File(zipFile.parentFile, zipFile.name + CATALOG_SUFFIX).writeText(arr.toString())
            counts.size
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    /** 读某个上传文件旁边的分册索引；没有/坏掉返回空表。 */
    fun readCatalog(f: java.io.File): List<ZipGroup> {
        val cf = java.io.File(f.parentFile, f.name + CATALOG_SUFFIX)
        if (!cf.isFile || cf.length() <= 0) return emptyList()
        return try {
            val o = JSONObject(cf.readText())
            val arr = o.optJSONArray("groups") ?: return emptyList()
            val out = ArrayList<ZipGroup>(arr.length())
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                out.add(
                    ZipGroup(
                        prefix = g.optString("prefix"),
                        name = g.optString("name"),
                        count = g.optInt("count")
                    )
                )
            }
            out.sortedBy { it.prefix }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * ★ 打开任意一本书（含"分册"）：统一入口，别处不要再自己 loadGamesBytes + decodeGames。
     *   · 申请上传的大包分册 → 只解该前缀下的条目（逐个读、逐个放，内存不随包体涨）；
     *   · HHXQ 目录分册      → 只解该目录下的文件；
     *   · 普通文件           → 老路径（整本读入后按内容分流）。
     */
    fun catDirName(cat: String): String =
        cat.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    fun decodeBook(context: Context, book: QipuBook): List<QipuGame> {
        val gp = book.groupPrefix
        if (gp.isNotBlank()) {
            return if (book.isUserUpload) decodeZipGroup(context, book.file, gp, book.name)
            else decodeLocalDir(gp, book.name)
        }
        val games = decodeGames(loadGamesBytes(context, book), book.name)
        // ★ 内置书（随包的古谱/赛事/名手）：把注解、开场白、评述全部去掉 —— 一个标点都不带，
        //   只保留书名、双方、赛果、着法与变招。用户自己导入的书不动，注解照常显示。
        if (book.isUserUpload || book.isLocalDoc) return games
        return games.map { g ->
            QipuGame(g.meta(), fullLoader = { g.cblGame.withoutComments() }, isFenPuzzle = g.isFenPuzzle)
        }
    }

    /** 解一个上传压缩包里的某一册（prefix 目录下的全部条目）。 */
    fun decodeZipGroup(context: Context, zipName: String, prefix: String, bookName: String): List<QipuGame> {
        val f = java.io.File(java.io.File(context.filesDir, USER_DIR), zipName)
        if (!f.isFile) return emptyList()
        val out = ArrayList<QipuGame>()
        try {
            java.util.zip.ZipFile(f, Charsets.ISO_8859_1).use { zf ->
                val list = ArrayList<java.util.zip.ZipEntry>(64)
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.isDirectory || !e.name.startsWith(prefix)) continue
                    if (!isQipuName(gbkName(e.name))) continue
                    list.add(e)
                }
                list.sortBy { it.name }
                var idx = 0
                for (e in list) {
                    if (out.size >= GROUP_CAP) break
                    val bytes = try { zf.getInputStream(e).use { it.readBytes() } } catch (ex: Exception) { null }
                        ?: continue
                    if (bytes.size < 8) continue
                    idx++
                    val nm = gbkName(e.name.substringAfterLast('/')).substringBeforeLast('.')
                    out.addAll(decodeEntryGames(bytes, nm, bookName, bookName, 0, idx))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return out
    }

    /** 解 HHXQ 下某个目录里的全部棋谱（一次只读一个文件）。 */
    fun decodeLocalDir(relDir: String, bookName: String): List<QipuGame> {
        val dir = java.io.File(localDocRoot(), relDir)
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name }
            ?: return emptyList()
        val out = ArrayList<QipuGame>()
        var idx = 0
        for (f in files) {
            if (out.size >= GROUP_CAP) break
            val bytes = try { f.readBytes() } catch (e: Exception) { null } ?: continue
            if (bytes.size < 8) continue
            idx++
            out.addAll(decodeEntryGames(bytes, f.name.substringBeforeLast('.'), bookName, bookName, 0, idx))
        }
        return out
    }

    /** ★ 挑战模式棋谱的专属子目录名（宝库里单列一个「挑战棋谱」菜单，就在 HHXQ 下）。 */
    const val CHALLENGE_DIR_NAME = "挑战棋谱"

    /**
     * ★ 挑战模式棋谱专属目录：HHXQ/挑战棋谱/。
     *   挑战模式终局点「保存棋谱」一律存到这里；宝库单列「挑战棋谱」分类实时显示，
     *   与「本地文档」的递归收集互不重复（收集时跳过本目录）。
     */
    fun challengeDir(): java.io.File =
        java.io.File(localDocRoot(), CHALLENGE_DIR_NAME)

    /**
     * ★ 把挑战模式终局棋谱写进专属「挑战棋谱」文件夹；返回落盘文件名（失败返回 null）。
     *   文件名原样沿用（含中文/下划线），仅做基本非法字符清洗。
     */
    fun persistChallengeRecord(displayName: String, bytes: ByteArray): String? {
        return try {
            val dir = challengeDir().apply { mkdirs() }
            val safe = displayName.substringBeforeLast('.')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifBlank { "挑战棋谱_${System.currentTimeMillis()}" }
            val ext = displayName.substringAfterLast('.', "").lowercase().takeIf {
                it.length in 1..6 && it.all(Char::isLetterOrDigit)
            } ?: "txt"
            val name = "$safe.$ext"
            java.io.File(dir, name).writeBytes(bytes)
            name
        } catch (e: Exception) {
            null
        }
    }

    /** 读取分类索引（第 0 级）。同步，开销小。 */
    fun loadCategories(context: Context): List<QipuCategory> {
        val raw = HeavyStore.openLocalFirst(context, INDEX_PATH)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val arr = JSONObject(raw).getJSONArray("categories")
        val cats = ArrayList<QipuCategory>(arr.length())
        for (i in 0 until arr.length()) {
            val co = arr.getJSONObject(i)
            val bArr = co.getJSONArray("books")
            val books = ArrayList<QipuBook>(bArr.length())
            for (j in 0 until bArr.length()) {
                val bo = bArr.getJSONObject(j)
                books.add(
                    QipuBook(
                        name = bo.getString("name"),
                        file = bo.getString("file"),
                        rawSize = bo.optInt("size")
                    )
                )
            }
            cats.add(QipuCategory(co.getString("name"), books))
        }
        // ★ 每个内置分类各自的「本分类上传」：文件放在 qipu_user/<分类名>/ 下，
        //   列在该分类书单末尾。清应用数据只会清掉这些上传，内置骨架在 assets 里，删不掉。
        try {
            val root = java.io.File(context.filesDir, USER_DIR)
            for (i in cats.indices) {
                val catName = cats[i].name
                if (catName !in BUILTIN_CATS) continue
                val sub = java.io.File(root, catDirName(catName))
                if (!sub.isDirectory) continue
                val ups = sub.listFiles()?.filter { it.isFile && it.length() > 0 }
                    ?.sortedBy { it.name }?.map { f ->
                        QipuBook(
                            name = f.name.substringBeforeLast('.'),
                            file = catDirName(catName) + "/" + f.name,
                            rawSize = f.length().toInt(),
                            mtime = f.lastModified(),
                            isUserUpload = true
                        )
                    } ?: emptyList()
                if (ups.isNotEmpty()) cats[i] = cats[i].copy(books = cats[i].books + ups)
            }
        } catch (e: Exception) { /* 忽略 */ }

        // ★ 追加「我的上传」分类：用户在开局库/宝库上传的棋谱文件，落盘到此目录，
        //   重启后依然在宝库里找得到。文件名即书名。
        //   ★ 即使一份都没有也照样列出（本数显示 0）——清空后仍能看到「（0本）」，数目真实可核对。
        try {
            val dir = java.io.File(context.filesDir, USER_DIR)
            val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
            val books = ArrayList<QipuBook>()
            for (f in files.sortedBy { it.name }) {
                if (f.name.endsWith(CATALOG_SUFFIX)) continue
                // ★ 大包：有 catalog 就拆成"册"列出来，zip 本身不再作为一本书（否则一点就整包解，必 OOM）
                val groups = readCatalog(f)
                if (groups.isNotEmpty()) {
                    val base = f.name.substringBeforeLast('.')
                    for (g in groups) {
                        books.add(
                            QipuBook(
                                name = "$base · ${g.name}（${g.count}局）",
                                file = f.name,
                                rawSize = g.count * 2048,
                                mtime = f.lastModified(),
                                isUserUpload = true,
                                groupPrefix = g.prefix
                            )
                        )
                    }
                } else {
                    books.add(
                        QipuBook(
                            name = f.name.substringBeforeLast('.'),
                            file = f.name,
                            rawSize = f.length().toInt(),
                            mtime = f.lastModified(),
                            isUserUpload = true
                        )
                    )
                }
            }
            cats.add(QipuCategory("我的上传", books))
        } catch (e: Exception) { cats.add(QipuCategory("我的上传", emptyList())) }
        // ★ 追加「挑战棋谱」分类：挑战模式终局「保存棋谱」一键存到 HHXQ/挑战棋谱/，
        //   这里单列一个专属菜单，用户一眼就知道"挑战模式存下的棋谱在这儿"。
        //   实时扫盘（不走本地文档缓存），保证挑战模式刚存完、回到宝库立刻看得到。
        try {
            val cfs = challengeDir().listFiles()?.filter { it.isFile && it.length() > 0 }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            val books = cfs.map { f ->
                QipuBook(
                    name = f.name.substringBeforeLast('.'),
                    file = "$CHALLENGE_DIR_NAME/${f.name}",
                    rawSize = f.length().toInt(),
                    mtime = f.lastModified(),
                    isLocalDoc = true
                )
            }
            cats.add(QipuCategory("挑战棋谱", books))
        } catch (e: Exception) { /* 忽略 */ }
        // ★ 追加「本地文档」分类：取 HHXQ 文件清单（缓存优先，避免打开宝库时同步扫盘导致 ANR 卡死）。
        //   清缓存/清数据也不丢（外部存储永久保留），无需手工导入，宝库直接可见可读。
        //   ★ 同样即使一份都没有也照样列出（本数显示 0）——清空后数目真实归零、可核对。
        try {
            cats.add(QipuCategory("本地文档", getLocalDocsCached(context)))
        } catch (e: Exception) { cats.add(QipuCategory("本地文档", emptyList())) }
        return cats
    }

    // ═══════════「本地文档」存档收集（/storage/emulated/0/HHXQ/，外部存储，清缓存不丢）═══════

    /** HHXQ 根目录名（黄昏象棋拼音缩写） */
    const val LOCAL_DIR_NAME = "HHXQ"

    /** HHXQ 根目录绝对路径：/storage/emulated/0/HHXQ/ */
    fun localDocRoot(): java.io.File =
        java.io.File(android.os.Environment.getExternalStorageDirectory(), LOCAL_DIR_NAME)

    /** HHXQ 全量收集清单的进程内缓存——启动后台线程扫描一次后，宝库打开直接读缓存，毫秒级。 */
    @Volatile
    private var localDocsCache: List<QipuBook>? = null

    /** 正在后台扫盘（避免重复起线程） */
    @Volatile
    private var docsScanning = false
    private val docsLock = Any()

    /** 上次扫盘结果的磁盘快照（重启后直接读它，不再重新遍历几十万个文件） */
    private const val DOCS_CACHE_FILE = "local_docs_index.json"

    /** 快照多久算"新鲜"：新鲜时连后台核对都不做，启动零扫盘 */
    private const val DOCS_FRESH_MS = 5 * 60 * 1000L

    @Volatile
    private var docsSnapshotTs = 0L

    /**
     * ★ 获取 HHXQ 本地文档清单 —— **绝不在调用线程扫盘**。
     *   顺序：内存缓存 → 磁盘快照 → （快照旧了才在后台扫一次，先返回快照）。
     *   这样哪怕 HHXQ 里躺着 32 万个文件、10 GB，打开宝库也是毫秒级。
     */
    fun getLocalDocsCached(context: Context): List<QipuBook> {
        localDocsCache?.let { return it }
        synchronized(docsLock) {
            localDocsCache?.let { return it }
            val disk = readDocsCacheFromDisk(context)
            if (disk != null) {
                localDocsCache = disk
                // 只有快照过期才在后台核对一次（新增/删除的文件过一会儿出现）
                if (System.currentTimeMillis() - docsSnapshotTs > DOCS_FRESH_MS) kickDocsScan(context)
                return disk
            }
        }
        kickDocsScan(context)
        return emptyList()
    }

    /** 后台扫盘（不阻塞调用方）。 */
    fun kickDocsScan(context: Context) {
        if (docsScanning) return
        docsScanning = true
        Thread {
            try { scanLocalDocs(context) } catch (e: Exception) { /* 忽略 */ } finally { docsScanning = false }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** 是否正在后台核对 HHXQ（供宝库显示"正在建索引"提示，不阻塞任何操作）。 */
    fun isDocsScanning(): Boolean = docsScanning

    /** ★ 刷新 HHXQ 清单（阻塞，调用方必须在后台线程）。 */
    fun refreshLocalDocsCache(context: Context): List<QipuBook> = scanLocalDocs(context)

    /**
     * ★ 存档收集：递归扫描 HHXQ 绝对路径（子目录按"册"归并，见 collectLocalFiles）。
     *   注意：此方法做真实磁盘遍历，**只能在后台线程调用**。
     */
    fun scanLocalDocs(context: Context): List<QipuBook> {
        val root = localDocRoot()
        val out = ArrayList<QipuBook>()
        try {
            if (!root.exists() || !root.isDirectory) {
                localDocsCache = emptyList()
                saveDocsCacheToDisk(context, emptyList())
                return out
            }
            collectLocalFiles(root, root, out)
        } catch (e: Exception) { /* 忽略 */ }
        val sorted = out.sortedByDescending { it.mtime }
        localDocsCache = sorted
        saveDocsCacheToDisk(context, sorted)
        return sorted
    }

    private fun saveDocsCacheToDisk(context: Context, list: List<QipuBook>) {
        try {
            val sb = StringBuilder(64 + list.size * 96)
            sb.append("{\"ts\":").append(System.currentTimeMillis()).append(",\"books\":[")
            var first = true
            for (b in list) {
                if (!first) sb.append(',')
                first = false
                sb.append("{\"n\":").append(JSONObject.quote(b.name))
                    .append(",\"f\":").append(JSONObject.quote(b.file))
                    .append(",\"s\":").append(b.rawSize)
                    .append(",\"m\":").append(b.mtime)
                    .append(",\"g\":").append(JSONObject.quote(b.groupPrefix))
                    .append('}')
            }
            sb.append("]}")
            java.io.File(context.filesDir, DOCS_CACHE_FILE).writeText(sb.toString())
        } catch (e: Exception) { /* 快照失败不影响使用 */ }
    }

    private fun readDocsCacheFromDisk(context: Context): List<QipuBook>? {
        val f = java.io.File(context.filesDir, DOCS_CACHE_FILE)
        if (!f.isFile || f.length() <= 2) return null
        return try {
            val root = JSONObject(f.readText())
            docsSnapshotTs = root.optLong("ts")
            val arr = root.optJSONArray("books") ?: return null
            val out = ArrayList<QipuBook>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    QipuBook(
                        name = o.optString("n"),
                        file = o.optString("f"),
                        rawSize = o.optInt("s"),
                        mtime = o.optLong("m"),
                        isLocalDoc = true,
                        groupPrefix = o.optString("g")
                    )
                )
            }
            out
        } catch (e: Exception) { null }
    }

    /**
     * ★ 收集 HHXQ 下的棋谱。
     *
     * 老做法是"一个文件一本书"——把 32 万个 xqf 解压进 HHXQ 后，光清单里就有 32 万条，
     * 一打开宝库就 OOM/闪退。现在：**除了根目录，任何一个子目录直接装着 ≥ 2 个文件，
     * 就把这个目录登记成一"册"**（groupPrefix=相对目录），册内再按局懒解。
     * 于是 32 万文件变成两千多册，列表、内存都在可控范围。
     */
    private fun collectLocalFiles(root: java.io.File, dir: java.io.File, out: MutableList<QipuBook>) {
        val kids = dir.listFiles() ?: return
        val isRoot = dir.absolutePath == root.absolutePath
        val files = ArrayList<java.io.File>()
        for (f in kids) {
            if (f.isDirectory) {
                // ★ 挑战棋谱有专属分类，不再并入「本地文档」，免得同一份棋谱两处重复显示
                if (f.name == CHALLENGE_DIR_NAME) continue
                collectLocalFiles(root, f, out)
            } else if (f.isFile) {
                files.add(f)
            }
        }
        if (files.isEmpty()) return
        if (!isRoot && files.size >= 2) {
            val rel = dir.absolutePath.removePrefix(root.absolutePath).trimStart('/')
            var size = 0
            if (files.size <= 300) for (f in files) size += f.length().toInt() else size = files.size * 2048
            out.add(
                QipuBook(
                    name = "${dir.name}（${files.size}局）",
                    file = rel,
                    rawSize = size,
                    mtime = dir.lastModified(),
                    isLocalDoc = true,
                    groupPrefix = rel
                )
            )
            return
        }
        for (f in files) {
            if (f.length() <= 0) continue
            val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
            out.add(
                QipuBook(
                    name = f.name.substringBeforeLast('.'),
                    file = rel,
                    rawSize = f.length().toInt(),
                    mtime = f.lastModified(),
                    isLocalDoc = true
                )
            )
        }
    }

    /** 读取 HHXQ 里某份本地文档的原始字节（relPath 为相对 HHXQ 根目录的路径；不存在返回 null）。 */
    fun readLocalDoc(relPath: String): ByteArray? {
        return try {
            val f = java.io.File(localDocRoot(), relPath)
            if (f.exists() && f.isFile) f.readBytes() else null
        } catch (e: Exception) { null }
    }

    /**
     * 读取某本书全部棋局（第 2 级）。CBL 解码较耗时，须在后台线程调用；
     * 这里返回 Data 包装，由调用方控制线程。
     */
    fun loadGamesBytes(context: Context, book: QipuBook): ByteArray {
        // ★ 分册书没有"整本字节"这一说：硬读会把几百兆整包读进内存。一律走 decodeBook。
        if (book.groupPrefix.isNotBlank()) {
            throw java.io.IOException("分册书请使用 QipuLibrary.decodeBook")
        }
        // ★ 本地文档（HHXQ 外部存储目录下的 CBL/XQF/JSON/TXT/PGN 等）：按相对路径直接读盘，
        //   供全局搜索建索引与打开共用；读不到即抛异常，由调用方决定用什么分支回退。
        if (book.isLocalDoc) {
            val local = readLocalDoc(book.file)
            if (local != null) return local
            throw java.io.IOException("本地文档不存在: ${book.file}")
        }
        // ★ 优先读用户上传的持久化棋谱文件（filesDir/qipu_user），否则回退内置 assets
        try {
            val userFile = java.io.File(java.io.File(context.filesDir, USER_DIR), book.file)
            if (userFile.exists()) return userFile.readBytes()
        } catch (e: Exception) { /* 回退 assets */ }
        return HeavyStore.openLocalFirst(context, DATA_DIR + book.file).use { it.readBytes() }
    }

    /** 把一个用户上传文件持久化到「我的上传」目录；返回保存后的文件名（失败返回 null）。 */
    fun persistUserUpload(context: Context, displayName: String, bytes: ByteArray): String? {
        return try {
            val dir = java.io.File(context.filesDir, USER_DIR).apply { mkdirs() }
            val safe = displayName.substringBeforeLast('.')
                .replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")
                .ifBlank { "upload_${System.currentTimeMillis()}" }
            val ext = displayName.substringAfterLast('.', "").lowercase().takeIf {
                it.length in 1..6 && it.all(Char::isLetterOrDigit)
            } ?: "cbl"
            val name = "$safe.$ext"
            java.io.File(dir, name).writeBytes(bytes)
            name
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 后台解码一本书（不含 IO），供 loadGames 异步执行。
     *
     * 分流（一律按【内容】判定，不看后缀名）：
     *   · ZIP 压缩包 → 逐个条目解包，MXQ/CBL/CBR 各按本格式解出 → 合成一个"多局书"；
     *   · MXQ 单局   → 直接解出；
     *   · CBL/CBR    → 交给 CblDecoder.decodeBook（棋库容器/裸记录/旧变体全兼容）。
     */
    fun decodeGames(raw: ByteArray, bookName: String): List<QipuGame> {
        if (isZip(raw)) return decodeArchiveGames(raw, bookName, bookName, 0)
        // ★ RAR（RAR4/RAR5，含分卷外的普通包）：包内 CBL/CHM/XQF/MXQ 逐一体识别。
        if (isRar(raw)) return decodeRarGames(raw, bookName, bookName, 0)
        // ★ CHM（微软编译帮助电子书）：枚举条目、解压页面、抽出东萍 DhtmlXQ 棋谱。
        if (isChm(raw)) return decodeChmGames(raw, bookName, bookName)
        // ★ QYL 棋谱（启阳象棋）：容器内是逐局的 zstd 帧，一出即整册（几十局）。
        //   与 XQF/CBL 同路，直接解出可回放的整册棋局。
        if (QylDecoder.isQyl(raw)) return QylDecoder.decode(raw, bookName).map { QipuGame(it) }
        val isCbl = raw.size >= 8 && String(raw, 0, 8, Charsets.US_ASCII) == "CCBridge"
        if (!isCbl && CblDecoder.isMxq(raw)) {
            return listOfNotNull(CblDecoder.decodeMxq(raw, 0)).map { QipuGame(it) }
        }
        // ★ 单份 XQF 棋谱（象棋演播室二进制）：与 CBL/CBR 同路，直接解出可回放的一局。
        if (!isCbl && XqfDecoder.isXqf(raw)) {
            val meta = XqfDecoder.decodeMeta(raw, 0, bookName)
            if (meta != null) return listOf(QipuGame(meta, fullLoader = { XqfDecoder.decode(raw, 0, bookName) ?: meta }))
        }
        // ★ 单份纯文本东萍谱（.txt / .ubb，内容是一行或多行 [DhtmlXQ]…）：按内容识别并解出。
        //   放在 FEN 题库判定之前 —— 两者都是文本，但东萍谱没有 FEN 行，先认特征更强的东萍标记。
        if (isTextual(raw)) {
            val text = decodeChmText(raw)
            if (DhtmlXqDecoder.hasDhtmlXq(text)) {
                val metas = DhtmlXqDecoder.decodeMetaPage(text, 0, bookName)
                if (metas.isNotEmpty()) return metas.mapIndexed { k, m ->
                    QipuGame(m, fullLoader = { DhtmlXqDecoder.decodePageBlock(text, k, m.index, bookName) ?: m })
                }
            }
        }
        // ★ 成批 FEN 题库（「5-10步缓杀 50000题.txt」这类：一行一题，摆好子并写明哪方走）：
        //   每行 = 一题「局面习题」，题名自带就用题名，没有就按序号（1、2、3…）。
        //   必须在 CblDecoder.decodeBook 之前判定 —— 否则纯文本 FEN 会被当 CBL 硬解，什么都出不来。
        if (isFenBatch(raw)) return decodeFenPuzzles(raw, bookName)
        // ★★ 纯文本棋谱（PGN / 中文记谱 / 挑战棋谱 TXT）：按【内容】认谱，用与主界面导入同一份解析
        //   内核（NotationParser）还原主线/变招/旁白。此前这类最普通的文本棋谱没有任何分支接管，
        //   会被 CblDecoder.decodeBook 当二进制硬解、一局都出不来 —— 表现正是「挑战棋谱上传到宝库
        //   不能识别、点开回弹」。这里放在 CBL 之前，解不出（非棋谱文本）则空表回退，绝不误抢。
        if (isTextual(raw) && PgnTextDecoder.isPgnLike(raw)) {
            val pgn = PgnTextDecoder.decode(raw, bookName)
            if (pgn.isNotEmpty()) return pgn
        }
        // ★ QYL 家族兜底：没有标准 QYLY 头、但容器里确实是 zstd 帧的变体（同后缀换一个文件也要能开）。
        //   放在 CBL 之前 —— 「容器内是一串 zstd 帧」是强特征，而 CBL/XQF 都不含 zstd 帧；
        //   且只在真解出棋局时才采用，解不出仍交 CBL，绝不误抢。
        if (QylDecoder.looksLikeQyl(raw)) {
            val q = try { QylDecoder.decode(raw, bookName).map { QipuGame(it) } } catch (e: Exception) { emptyList() }
            if (q.isNotEmpty()) return q
        }
        return CblDecoder.decodeBook(raw, bookName).map { QipuGame(it) }
    }

    /** ★ 是否为「成批 FEN 题库」文本：非二进制，且抽样的行过半都是 `<棋盘> w|b …`。
     *  仅看内容，不看后缀名（用户上传常被标成 .txt/.cbl）。 */
    fun isFenBatch(raw: ByteArray): Boolean {
        if (raw.isEmpty()) return false
        val probe = minOf(raw.size, 1024)
        for (i in 0 until probe) if (raw[i] == 0.toByte()) return false   // 二进制护栏
        // ★ 还得是「像文本的」：抽样字节里 ASCII 可打印/空白占比过低的，一律是二进制（CBL/MXQ/压缩包），
        //   避免把二进制误判成题库（那会让宝库搜索跳过本该逐局索引的 CBL 书）。
        val sample = minOf(raw.size, 65536)
        var textish = 0
        for (i in 0 until sample) {
            val b = raw[i].toInt() and 0xFF
            if (b == 9 || b == 10 || b == 13 || (b in 32..126)) textish++
        }
        if (textish * 100 / sample < 70) return false
        val head = try {
            decodeText(if (raw.size > 65536) raw.copyOf(65536) else raw)
        } catch (e: Exception) { return false }
        var checked = 0
        var hits = 0
        for (line in head.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            checked++
            if (QipuGame.lineHasFen(t)) hits++
            if (checked >= 10) break
        }
        return hits >= 1 && hits * 2 >= checked
    }

    /**
     * ★ 解析「成批 FEN 题库」为习题列表。
     *
     * 内存关键（一册可能几十万题，绝不能一次性建几十万个对象，否则必 OOM）：
     *   · 只扫描一次行边界，保存每题的起止偏移（IntArray）与可选题名；
     *   · 全文只留一份 String，取第 N 题时才按偏移切出那一行、构造 QipuGame —— 真正用到的
     *     只有当前列表页那 120 题，其余按需生成。
     */
    fun decodeFenPuzzles(raw: ByteArray, bookName: String): List<QipuGame> {
        val text = try { decodeText(raw) } catch (e: Exception) { return emptyList() }
        val n = text.length
        val starts = ArrayList<Int>(4096)
        val ends = ArrayList<Int>(4096)
        val names = ArrayList<String?>(4096)
        var pendingName: String? = null
        var i = 0
        while (i < n) {
            var j = i
            while (j < n && text[j] != '\n' && text[j] != '\r') j++
            val lineStart = i
            val lineEnd = j
            i = j
            while (i < n && (text[i] == '\n' || text[i] == '\r')) i++
            if (lineEnd <= lineStart) continue
            val line = text.substring(lineStart, lineEnd).trim()
            if (line.isEmpty()) continue
            if (QipuGame.lineHasFen(line)) {
                starts.add(lineStart); ends.add(lineEnd); names.add(pendingName)
                pendingName = null
            } else {
                // 非 FEN 行当作「下一题的题名」（支持“题名独占一行 + FEN 一行”的题库）
                pendingName = line
            }
        }
        if (starts.isEmpty()) return emptyList()
        return FenPuzzleList(text, starts.toIntArray(), ends.toIntArray(), names.toTypedArray())
    }

    /**
     * ★ 习题集的“虚拟列表”：不预先建对象，get(i) 时才解析第 i 行 → 几十万题也只占
     *   一份原文（+ 偏移表）的内存。
     */
    private class FenPuzzleList(
        private val text: String,
        private val starts: IntArray,
        private val ends: IntArray,
        private val names: Array<String?>
    ) : AbstractList<QipuGame>() {
        override val size: Int get() = starts.size
        override fun get(index: Int): QipuGame {
            val line = text.substring(starts[index], ends[index])
            return QipuGame.fenPuzzle(index, line, names[index])
                ?: QipuGame.fenPuzzle(index, "9/9/9/9/9/9/9/9/9/9 w - - 0 1", names[index])!!
        }
    }

    /** 是否为 ZIP 包（本地 50 4B 03 04 / 空档 05 06 / 分卷 07 08）。 */
    fun isZip(raw: ByteArray): Boolean =
        raw.size >= 4 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte() &&
            (raw[2] == 0x03.toByte() || raw[2] == 0x05.toByte() || raw[2] == 0x07.toByte())

    /** 是否为 RAR 包（RAR4："Rar!\x1A\x07\x00"；RAR5："Rar!\x1A\x07\x01\x00"）。 */
    fun isRar(raw: ByteArray): Boolean =
        raw.size >= 7 && raw[0] == 0x52.toByte() && raw[1] == 0x61.toByte() && raw[2] == 0x72.toByte() &&
            raw[3] == 0x21.toByte() && raw[4] == 0x1A.toByte() && raw[5] == 0x07.toByte() &&
            (raw[6] == 0x00.toByte() || raw[6] == 0x01.toByte())

    /** 是否为 CHM（微软编译帮助电子书，文件头恒为 "ITSF"）。 */
    fun isChm(raw: ByteArray): Boolean =
        raw.size >= 4 && raw[0] == 'I'.code.toByte() && raw[1] == 'T'.code.toByte() &&
            raw[2] == 'S'.code.toByte() && raw[3] == 'F'.code.toByte()

    /**
     * 是否像"纯文本"（前 8KB 无 NUL 字节）。
     * 用于把压缩包里的 .txt/.ubb 东萍谱按内容认出来 —— 不靠扩展名，
     * 与其余"按内容判定"的分流保持一致。
     */
    private fun isTextual(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val probe = minOf(bytes.size, 8192)
        for (i in 0 until probe) if (bytes[i] == 0.toByte()) return false
        return true
    }

    /** 套娃压缩包的最大递归层数（防病态包体无限套娃）。 */
    private const val MAX_NEST = 4

    private fun decodeArchiveGames(raw: ByteArray, bookName: String, pathPrefix: String): List<QipuGame> =
        decodeArchiveGames(raw, bookName, pathPrefix, 0)

    /**
     * ★ 解包出来的单个条目：按【内容】再分流一次 —— 套娃压缩包（ZIP/RAR）递归解包，
     *   CHM 电子书抽 DhtmlXQ 页面，CBL/XQF/MXQ 各按本格式解出。取不到棋局返回空表。
     *   @param idx 条目序号（仅作 MXQ/XQF 题名兜底）
     */
    private fun decodeEntryGames(
        bytes: ByteArray, name: String, bookName: String, pathPrefix: String, depth: Int, idx: Int
    ): List<QipuGame> {
        if (depth < MAX_NEST && isZip(bytes)) return decodeArchiveGames(bytes, bookName, "$pathPrefix/$name", depth + 1)
        if (depth < MAX_NEST && isRar(bytes)) return decodeRarGames(bytes, bookName, "$pathPrefix/$name", depth + 1)
        if (isChm(bytes)) return decodeChmGames(bytes, bookName, "$pathPrefix/$name")
        // ★ 包内 QYL 棋谱（启阳象棋 .qyl）：与 CBL/XQF 一样逐条解出，整册入宝库。
        if (QylDecoder.isQyl(bytes)) return QylDecoder.decode(bytes, name).map { QipuGame(it) }
        val isCbl = bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == "CCBridge"
        if (isCbl) return CblDecoder.decodeBook(bytes, name).map { QipuGame(it) }
        if (XqfDecoder.isXqf(bytes)) {
            // ★ 一次分词：识别 + 头部元信息一起出（不是 XQF 直接跳过）
            val meta = XqfDecoder.decodeMeta(bytes, idx, name) ?: return emptyList()
            return listOf(QipuGame(meta, fullLoader = { XqfDecoder.decode(bytes, idx, name) ?: meta }))
        }
        // ★★ 包内「纯文本东萍谱」（天天象棋题库那类：一包上万个 .txt，每个里是一行 [DhtmlXQ]…）
        //    之前只按二进制格式分流，.txt 条目全部落到空表 —— 表现正是"整包解不出、宝库识别不了"。
        //    这里按【内容】认：无 NUL 的文本 + 含 [DhtmlXQ_movelist] 即按东萍谱解出。
        //    ★ 懒解码：上万个条目只先解元信息建列表，着法等用户点开哪题才解哪题，
        //      与包内 MXQ/XQF 同一套机制，避免整包上万题一次性逐着校验把打开拖成几十秒。
        if (isTextual(bytes)) {
            val text = decodeChmText(bytes)          // UTF-8 优先，含替换符再退 GBK
            if (DhtmlXqDecoder.hasDhtmlXq(text)) {
                val metas = DhtmlXqDecoder.decodeMetaPage(text, idx, name)
                if (metas.isNotEmpty()) return metas.mapIndexed { k, m ->
                    QipuGame(m, fullLoader = { DhtmlXqDecoder.decodePageBlock(text, k, m.index, name) ?: m })
                }
            }
        }
        // ★★ 包内「纯文本 PGN / 中文记谱」（如把挑战棋谱 .txt、中文记谱 PGN 打进 ZIP 上传宝库）：
        //    与东萍谱同法，按【内容】认谱 —— 用统一解析内核还原主线/变招。此前这些文本条目
        //    只会落到最后的 MXQ 判定、返回空表，整包一条都出不来。
        if (isTextual(bytes) && PgnTextDecoder.isPgnLike(bytes)) {
            val pgn = PgnTextDecoder.decode(bytes, name)
            if (pgn.isNotEmpty()) return pgn
        }
        // ★ 一次分词：识别 + 头部元信息一起出（不是 MXQ 直接跳过）
        val meta = CblDecoder.decodeMxqMetaIfMxq(bytes, idx) ?: return emptyList()
        return listOf(QipuGame(meta, { CblDecoder.decodeMxq(bytes, idx) ?: meta }))
    }

    /**
     * ★ 解一个「棋谱压缩包」（如"2022年2月份华山和棋棋谱.zip"内含 6923 个 .mxq；"棋路内置棋谱.zip"
     *   内含 7478 个 .xqf）。
     *
     * 关键设计（否则几千局必然卡死/OOM）：
     *   · 只把每个条目的【头部元信息】解出来建列表，着法一律懒解码（点开哪局才解哪局）；
     *   · 条目名按 GBK 还原（原始包常是 GBK 名且未置 UTF-8 标志位），仅作题名兜底；
     *   · 解包在调用方的后台线程里跑（见 Loader / openUserUploadBook），主线程全程不碰。
     *
     * ★ 套娃：条目自身若还是 ZIP（如"CBL 打包成 ZIP"、多层压缩包），递归解包 —— 深度上限
     *   [MAX_NEST] 防病态包体无限套娃。条目可为 MXQ / CBL / CBR / XQF，各按本格式解出。
     */
    private fun decodeArchiveGames(raw: ByteArray, bookName: String, pathPrefix: String, depth: Int): List<QipuGame> {
        val entries = ArrayList<Pair<String, ByteArray>>(1024)
        try {
            // ★★ 关键修复：ZIP 条目名常为 GBK 且未置 UTF-8 标志位（如"2022年2月份华山和棋棋谱.zip"
            //   里的 "2.02和棋谱/…"）。ZipInputStream 默认按 UTF-8 解条目名，遇到非法字节会直接抛
            //   IllegalArgumentException("malformed input")；若它在【首条目】就抛，整包 6000+ 局会被
            //   连锅端——表现正是"文件已进宝库、点开却解不出/回弹主界面"。
            //   这里改用 ISO-8859-1 解名（逐字节一一映射，永不判非法），随后再按 GBK 还原成中文名。
            java.util.zip.ZipInputStream(
                java.io.ByteArrayInputStream(raw), Charsets.ISO_8859_1
            ).use { zis ->
                while (true) {
                    val e = try { zis.nextEntry } catch (ex: Exception) { null }
                    if (e == null) break
                    if (e.isDirectory) continue
                    val bytes = try { zis.readBytes() } catch (ex: Exception) { ByteArray(0) }
                    if (bytes.isEmpty()) continue
                    entries.add(gbkName(e.name) to bytes)
                    if (entries.size >= 20000) break   // 极端包体保护
                }
            }
        } catch (e: Exception) {
            // 包体尾部损坏时，保留已经读出来的条目，不整包丢弃
        }
        entries.sortBy { it.first }
        val out = ArrayList<QipuGame>(entries.size)
        var idx = 0
        for ((name, bytes) in entries) {
            idx++
            out.addAll(decodeEntryGames(bytes, name, bookName, pathPrefix, depth, idx))
        }
        return out
    }

    /**
     * ★ 解一个 RAR 棋谱包（象棋攻防新战术 cbl.rar、[中国象棋小寒斋著]CHM金华制作.rar…）。
     *
     * 用 junrar 8.x（唯一支持 RAR5 的版本，皮肤包已在用）按条目读到内存，再交给统一条目分流：
     *   · 包内 .CBL        → CblDecoder 解出上千局；
     *   · 包内 .CHM 电子书 → 抽出东萍 DhtmlXQ 页面成棋局；
     *   · 包内 ZIP/RAR     → 递归解包（深度上限 [MAX_NEST]）。
     * 读盘/解包都在后台线程（Loader / openUserUploadBook），主线程全程不碰。
     */
    private fun decodeRarGames(raw: ByteArray, bookName: String, pathPrefix: String, depth: Int): List<QipuGame> {
        val entries = ArrayList<Pair<String, ByteArray>>(64)
        try {
            com.github.junrar.Archive(java.io.ByteArrayInputStream(raw)).use { ar ->
                var h = ar.nextFileHeader()
                var guard = 0
                while (h != null && guard < 20000) {   // 极端包体保护
                    guard++
                    if (!h.isDirectory) {
                        val nm = h.fileName ?: ""
                        val bytes = try {
                            java.io.ByteArrayOutputStream().use { bos -> ar.extractFile(h, bos); bos.toByteArray() }
                        } catch (e: Exception) { ByteArray(0) }
                        if (bytes.isNotEmpty()) entries.add(nm to bytes)
                    }
                    h = ar.nextFileHeader()
                }
            }
        } catch (e: Exception) {
            // 包体尾部损坏时，保留已经读出来的条目，不整包丢弃
        }
        entries.sortBy { it.first }
        val out = ArrayList<QipuGame>(entries.size)
        var idx = 0
        for ((name, bytes) in entries) {
            idx++
            out.addAll(decodeEntryGames(bytes, name, bookName, pathPrefix, depth, idx))
        }
        return out
    }

    /**
     * ★ 解一本 CHM 电子书（[中国象棋小寒秋著]CHM金华制作.CHM 这类：把棋谱做成网页页面）。
     *
     * 用内嵌的 chmlib 移植版（org.apache.tika.parser.microsoft.chm）解析 ITSF/ITSP 头 + PMGL 目录 +
     * LZX 解压，枚举全部页面，逐页抽 [DhtmlXQ_movelist]/[DhtmlXQ_binit] 成可回放棋局。
     * 页面文本常为 GB2312/GBK，按替换符数选编码。全程后台线程。
     */
    private fun decodeChmGames(raw: ByteArray, bookName: String, pathPrefix: String): List<QipuGame> {
        val out = ArrayList<QipuGame>()
        try {
            val ex = org.apache.tika.parser.microsoft.chm.ChmExtractor(java.io.ByteArrayInputStream(raw))
            val dirs = ex.chmDirList?.directoryListingEntryList ?: return out
            var idx = 0
            for (e in dirs) {
                if (out.size >= 5000) break        // 极端电子书保护
                val nm = e.name ?: continue
                val low = nm.lowercase()
                if (!(low.endsWith(".htm") || low.endsWith(".html") || low.endsWith(".ubb"))) continue
                val bytes = try { ex.extractChmEntry(e) } catch (t: Throwable) { continue }
                if (bytes == null || bytes.isEmpty()) continue
                val text = decodeChmText(bytes)
                if (!DhtmlXqDecoder.hasDhtmlXq(text)) continue
                val games = DhtmlXqDecoder.decodePage(text, idx, nm)
                for (g in games) out.add(QipuGame(g))
                idx += games.size
            }
        } catch (e: Exception) {
            // 头部/目录损坏时保留已解出的棋局
        }
        return out
    }

    /** CHM 页面文本解码：UTF-8 无替换符即用；否则 GB2312/GBK（老棋书几乎都是 GBK）。 */
    private fun decodeChmText(bytes: ByteArray): String {
        val utf = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { "" }
        if (utf.isNotEmpty() && utf.indexOf('\uFFFD') < 0) return utf
        val gbk = try { String(bytes, Charset.forName("GBK")) } catch (e: Exception) { "" }
        if (gbk.isEmpty()) return utf
        val badUtf = utf.count { it == '\uFFFD' }
        val badGbk = gbk.count { it == '\uFFFD' }
        return if (badGbk <= badUtf) gbk else utf
    }

    /**
     * ★ 棋谱/文本文档的统一容错解码：BOM → 严格 UTF-8 → GB18030 → ISO-8859-1 逐级兜底。
     *
     * 国内棋软（象棋桥 / 象棋笔记 / 天天象棋等）导出的 PGN、中文记谱相当比例是 GBK，
     * 一律按 UTF-8 读会把整篇中文着法读成 U+FFFD（替换符），宝库检索/导入随即失效——
     * 这正是"同一份 PGN 阅读器能读、本 App 宝库读不出"的根因。GB18030 是 GBK/GB2312 超集，兜底不漏字。
     */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        // 严格 UTF-8：合法即为 UTF-8，绝不吞
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { }
        // GB18030（GBK/GB2312 超集）
        try {
            return Charset.forName("GB18030").newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { }
        return String(bytes, Charsets.ISO_8859_1)
    }


    /** ZIP 条目名还原：ISO-8859-1 的逐字节串 → GBK 中文名（解不出则原样返回）。纯 ASCII 名不受影响。 */
    private fun gbkName(raw: String): String {
        if (raw.all { it.code < 0x80 }) return raw
        // ★ 已经解成正常中文（含拉丁字母表以外的字符）说明这个包的条目名本就带 UTF-8 标志位、
        //   ZipInputStream 已按 UTF-8 解对了 —— 此时再套一次 ISO-8859-1→GBK 会把每个汉字压成
        //   '?'（toByteArray(ISO_8859_1) 对不可表示字符一律给 0x3F），整包名字就全花了。
        if (raw.any { it.code > 0xFF }) return raw
        return try {
            val s = String(raw.toByteArray(Charsets.ISO_8859_1), Charset.forName("GBK"))
            if (s.indexOf('\uFFFD') >= 0) raw else s
        } catch (e: Exception) { raw }
    }

    // ═══════════ 任意格式用户棋谱的轻量检索元数据（PGN / 中文记谱 / ICCS / FEN 等）═══
    /** 一条文本棋局的可检索元信息，各字段已归一。index 为 -1 时整文件只有一条（点击打开整个文件）。 */
    data class TextGameMeta(
        val index: Int,
        val title: String,
        val text: String,      // 全部字段（"全部"维度用）
        val persons: String,   // 红名+黑名（人名维度专用；未知则为空）
        val bookName: String,  // 来源书名（书名维度专用）
        val layout: String,    // 开局/布局文字（布局维度专用）
        val years: Set<Int>    // 年份集合
    )

    /**
     * 对任意文本棋谱（PGN/中文记谱/ICCS/FEN...）抽取可检索元信息，供宝库全局搜索使用。
     * CBL/XQF 为二进制格式，不在此处理（走 decodeGames）；仅针对解码不出棋局的文本文件。
     */
    fun extractTextGameMetas(raw: ByteArray, bookName: String): List<TextGameMeta> {
        val content = try { decodeText(raw) } catch (e: Exception) { return emptyList() }
        val c = content.trim()
        if (c.isEmpty()) return emptyList()
        val bookNameNorm = norm(bookName)

        // 优先按 PGN 对局块切分（每条以 [Event ... 表头开行），逐局抽取 局名/红黑/年代
        val blocks = splitPgnBlocks(c)
        if (blocks.isNotEmpty()) {
            val out = ArrayList<TextGameMeta>(blocks.size)
            blocks.forEachIndexed { gi, block ->
                val event = pgnHeader(block, "Event")
                val red = pgnHeader(block, "Red")
                val black = pgnHeader(block, "Black")
                val date = pgnHeader(block, "Date")
                val players = listOf(red, black).filter { it.isNotBlank() }.ifEmpty { emptyList() }
                val title = buildString {
                    if (event.isNotBlank()) append(event)
                    if (players.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(players.joinToString(" 对 "))
                    }
                    if (isEmpty()) append("$bookName ${gi + 1}")
                }
                val text = norm(listOf(event, red, black, date, "$bookName ${gi + 1}").joinToString(" "))
                out.add(TextGameMeta(
                    gi + 1, title, text,
                    norm("$red $black"),
                    bookNameNorm,
                    norm("$event $bookName ${gi + 1}"),
                    yearSet(date + " " + event)))
            }
            return out
        }

        // 其它文本（中文记谱/ICCS/单局 FEN 等）：整文件视作一条，按文件名命名；人名未知
        return listOf(TextGameMeta(
            -1, bookName, norm(c + " " + bookName),
            "", bookNameNorm, norm(c), yearSet(c)))
    }

    private fun splitPgnBlocks(content: String): List<String> {
        val parts = content.split(Regex("(?=^\\[Event\\s)", RegexOption.MULTILINE))
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun pgnHeader(block: String, key: String): String {
        val m = Regex("""\[$key\s+"([^"]*)"\]""").find(block) ?: return ""
        return m.groupValues[1].trim()
    }

    private fun yearSet(s: String): Set<Int> =
        Regex("(?:19|20)\\d{2}").findAll(s).map { it.value.toInt() }.toSet()

    private fun norm(s: String): String =
        s.lowercase(java.util.Locale.ROOT).replace(Regex("[^0-9a-z\\u4e00-\\u9fa5]"), "")

    /** 在分类列表里按文件名定位某本书。 */
    fun findBook(cats: List<QipuCategory>, file: String): Pair<Int, Int>? {
        if (file.isBlank()) return null
        for (ci in cats.indices) {
            val books = cats[ci].books
            for (bi in books.indices) {
                if (books[bi].file == file) return ci to bi
            }
        }
        return null
    }

    // ═══════════「我的上传」用户棋谱文件管理（filesDir/qipu_user，重启后仍在）═══════

    fun userUploadDir(context: Context): java.io.File =
        java.io.File(context.filesDir, USER_DIR).apply { mkdirs() }

    /** 读取某个用户上传棋谱文件的原始字节（不存在返回 null）。 */
    fun readUserUpload(context: Context, fileName: String): ByteArray? {
        return try {
            val f = java.io.File(userUploadDir(context), fileName)
            if (f.exists() && f.isFile) f.readBytes() else null
        } catch (e: Exception) { null }
    }

    /** 重命名用户上传棋谱（保留原扩展名；返回 true 成功）。 */
    fun renameUserUpload(context: Context, oldName: String, newTitle: String): Boolean {
        return try {
            val dir = userUploadDir(context)
            val old = java.io.File(dir, oldName)
            if (!old.exists()) return false
            val safe = newTitle.trim()
                .replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")
                .ifBlank { "棋谱" }
            val ext = oldName.substringAfterLast('.', "").takeIf {
                it.length in 1..6 && it.all(Char::isLetterOrDigit)
            }
            val newName = if (ext == null || ext.isBlank()) "$safe.cbl" else "$safe.$ext"
            val target = java.io.File(dir, newName)
            if (target.exists() && target.name != old.name) return false  // 重名不覆盖
            old.renameTo(target)
        } catch (e: Exception) { false }
    }

    /** 删除用户上传棋谱文件（返回 true 成功）。 */
    fun deleteUserUpload(context: Context, fileName: String): Boolean {
        return try {
            val f = java.io.File(userUploadDir(context), fileName)
            f.exists() && f.delete()
        } catch (e: Exception) { false }
    }

    // ═══════════ 物理删除 / 清空（宝库列表用：真删，路径下确实消失）═══════════

    /**
     * ★ 物理删除一本书对应的原始文件（我的上传 / 挑战棋谱 / 本地文档）。
     *   内置 assets 书无法删除 → 返回 false。删除成功返回 true。
     */
    fun deleteBook(context: Context, book: QipuBook): Boolean {
        return try {
            when {
                book.isLocalDoc -> {
                    val f = java.io.File(localDocRoot(), book.file)
                    f.isFile && f.delete()
                }
                book.isUserUpload -> deleteUserUpload(context, book.file)
                else -> false       // 内置 assets：只读，不能删
            }
        } catch (e: Exception) { false }
    }

    /**
     * ★ 物理清空一个分类：逐本删除登记文件，返回实际删除的份数。
     *   删完刷新「本地文档」清单缓存，保证下次打开重新扫盘、数目真实归零。
     */
    fun clearCategory(context: Context, cat: QipuCategory): Int {
        var n = 0
        for (b in cat.books) if (deleteBook(context, b)) n++
        try { refreshLocalDocsCache(context) } catch (e: Exception) { /* 忽略 */ }
        return n
    }

    /** 便捷异步加载器：解析书名 -> 棋局列表，回调在主线程。 */
    class Loader(
        private val context: Context,
        private val book: QipuBook,
        private val onResult: (List<QipuGame>?) -> Unit
    ) : AsyncTask<Void, Void, List<QipuGame>?>() {
        override fun doInBackground(vararg params: Void?): List<QipuGame>? {
            return try {
                decodeBook(context, book)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        override fun onPostExecute(result: List<QipuGame>?) {
            onResult(result)
        }
    }
}