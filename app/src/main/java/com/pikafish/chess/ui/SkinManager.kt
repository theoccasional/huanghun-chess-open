package com.pikafish.chess.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.HashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ★★★「个性化」皮肤包管理 ★★★
 *
 * 职责：把外置的 Zip 皮肤包 / 别人的 APK 一键导入 → 高智能提取 → 自动分类拆分 →
 *       剔除无字皮肤 → 剥离棋盘经纬线/坐标 → 预览/勾选 → 应用到棋盘。
 *
 * ★ APK 提取引擎（v2）设计要点：
 *  - 「一网打尽」：一个 APK 按【目录】+【Cocos 图集】分组，能出几套就出几套
 *    （如多乐象棋一个包能拆出 14 套），每套独立成包，互不覆盖。
 *  - 「无字剔除」：中心墨量检测（center_ink）先把空白底座/无字皮肤踢掉；
 *    再用 ML Kit 中文 OCR 校验棋子上的字，读出的字反查类型与阵营，防止错位。
 *  - 「鲁棒定色」：文件名猜色 + 像素红/黑占比分析 + OCR 字阵营，三路互相兜底。
 *  - 「Cocos 图集」：解析 .atlas 精灵表，帧名 xq_txt_RA~RR/BA~BR 直接定色定字，
 *    qizi01 底座帧与文字帧自动合成完整棋子。
 *  - 「杂交选择」：红方棋子可与黑方棋子来自不同包；背景可选。
 */
object SkinManager {
    private const val TAG = "SkinManager"

    /** 一套导入素材的快照，供 UI 展示预览与勾选 */
    data class Pack(
        val id: String,
        val name: String,
        val hasRed: Boolean,
        val hasBlack: Boolean,
        val hasBg: Boolean,
        /** 这套素材是从包的哪个位置、用哪条通道扒出来的（Unity 资源 / 图集 / 目录 / OCR / 字体） */
        val source: String = ""
    )

    private const val DIR = "skins"
    private const val PREFS = "personalize"
    private const val KEY_RED = "red"   // 红方来源包 id
    private const val KEY_BLACK = "black" // 黑方来源包 id
    private const val KEY_BG = "bg"      // 背景来源包 id（空串=不用外置背景）

    /** 中心墨量阈值：低于此值视为"无字/空白底座"，直接剔除 */
    private const val INK_RATIO = 0.008f

    /** OCR 次数预算：防超大 APK 把识别时间拖爆 */
    private const val OCR_BUDGET = 260

    /** 单次 OCR 最长等待（秒）：ML Kit 万一卡住，就当识别失败兜底，绝不让整条导入线吊死 */
    private const val OCR_TIMEOUT_SEC = 8L

    /** 解码上限：棋子图 / 桌布图各自的最大边长（先探尺寸再 inSampleSize 采样，控住内存峰值） */
    private const val PIECE_DECODE_MAX = 1024
    private const val BG_DECODE_MAX = 1600

    /** 可识别的棋子汉字全集（OCR 白名单） */
    private val ALL_CHARS = "帅将士相象马车炮兵卒仕"

    /** 展开归档时在内存里最多攒多少张图片（防超大/恶意包把内存顶爆）；皮卡鱼这类 124 套主题约 2600 张，取 6000 留足余量。 */
    private const val MAX_IMAGES = 6000

    /** 单个「嵌套归档」允许的最大字节数；超过则不再往内存里读（上面的包嵌套 RAR 多在 6MB 内）。 */
    private const val MAX_NESTED_BYTES = 32 * 1024 * 1024

    /** 从 APK 取棋盘背景时，文件名命中这些词的图片一律不算棋盘（启动页/图标/引导页/分享二维码…）。 */
    private val bgNoiseWords = listOf(
        "splash", "launch", "loading", "guide", "logo", "icon", "avatar", "head",
        "share", "weixin", "wechat", "qr", "screenshot", "default", "cover"
    )

    // ────────────────────────────── 目录布局 ──────────────────────────────
    private fun packsRoot(ctx: Context): File = File(ctx.filesDir, DIR)
    fun packDir(ctx: Context, id: String): File = File(packsRoot(ctx), "pack_$id")
    private fun redDir(p: File): File = File(p, "red")
    private fun blackDir(p: File): File = File(p, "black")
    private fun bgFile(p: File): File = File(p, "bg.png")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ═══════════════════════ 列出/删除/选择 ═══════════════════════

    fun listPacks(ctx: Context): List<Pack> {
        val root = packsRoot(ctx)
        if (!root.exists()) return emptyList()
        return root.listFiles { f -> f.isDirectory && f.name.startsWith("pack_") }
            ?.map { f ->
                val id = f.name.removePrefix("pack_")
                Pack(id, metaField(f, "name") ?: id,
                    redDir(f).listFiles()?.isNotEmpty() ?: false,
                    blackDir(f).listFiles()?.isNotEmpty() ?: false,
                    bgFile(f).exists(),
                    metaField(f, "source") ?: "")
            } ?: emptyList()
    }

    /** 读回包元信息里的某个字段（导入时写进 meta.txt），让列表能显示包名与提取来源。 */
    private fun metaField(p: File, key: String): String? {
        val f = File(p, "meta.txt")
        if (!f.exists()) return null
        return try {
            f.readLines().firstOrNull { it.startsWith("$key=") }
                ?.removePrefix("$key=")?.trim()?.ifBlank { null }
        } catch (_: Exception) { null }
    }

    fun deletePack(ctx: Context, id: String) {
        val p = packDir(ctx, id)
        if (p.exists()) p.deleteRecursively()
        val prefs = prefs(ctx)
        if (prefs.getString(KEY_RED, "") == id) prefs.edit().putString(KEY_RED, "").apply()
        if (prefs.getString(KEY_BLACK, "") == id) prefs.edit().putString(KEY_BLACK, "").apply()
        if (prefs.getString(KEY_BG, "") == id) prefs.edit().putString(KEY_BG, "").apply()
    }

    fun currentSelection(ctx: Context): Triple<String, String, String> {
        val p = prefs(ctx)
        return Triple(
            p.getString(KEY_RED, "").orEmpty(),
            p.getString(KEY_BLACK, "").orEmpty(),
            p.getString(KEY_BG, "").orEmpty()
        )
    }

    fun setRed(ctx: Context, id: String) { prefs(ctx).edit().putString(KEY_RED, id).apply() }
    fun setBlack(ctx: Context, id: String) { prefs(ctx).edit().putString(KEY_BLACK, id).apply() }
    fun setBg(ctx: Context, id: String) { prefs(ctx).edit().putString(KEY_BG, id).apply() }

    /** 取某包红/黑某类棋子的位图；找不到返回 null */
    private fun pieceBitmap(p: File, color: PieceColor, type: PieceType): Bitmap? {
        val dir = if (color == PieceColor.RED) redDir(p) else blackDir(p)
        if (!dir.exists()) return null
        val key = pieceFileName(type)
        val f = File(dir, key)
        if (f.exists()) {
            try { return BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) {}
        }
        // 兼容旧的无前缀命名
        val f2 = File(dir, type.fenChar.toString() + ".png")
        if (f2.exists()) {
            try { return BitmapFactory.decodeFile(f2.absolutePath) } catch (_: Exception) {}
        }
        return null
    }

    private fun pieceFileName(type: PieceType): String = "p${type.fenChar}.png"

    /** 把当前「杂交选择」应用到一个棋盘视图（红/黑棋子 + 可选洗净背景），并持久化标记。 */
    fun applyTo(ctx: Context, view: ChessBoardView): String {
        val (rid, bid, bgid) = currentSelection(ctx)
        val redMap = HashMap<PieceType, Bitmap>()
        val blackMap = HashMap<PieceType, Bitmap>()
        if (rid.isNotBlank()) {
            PieceType.entries.forEach { t ->
                pieceBitmap(packDir(ctx, rid), PieceColor.RED, t)?.let { redMap[t] = it }
            }
        }
        if (bid.isNotBlank()) {
            PieceType.entries.forEach { t ->
                pieceBitmap(packDir(ctx, bid), PieceColor.BLACK, t)?.let { blackMap[t] = it }
            }
        }
        view.setCustomPieces(redMap, blackMap)
        var bgBmp: Bitmap? = null
        if (bgid.isNotBlank()) {
            val f = bgFile(packDir(ctx, bgid))
            if (f.exists()) { try { bgBmp = BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) {} }
        }
        view.setCustomBackground(bgBmp)
        val r = if (rid.isBlank()) "内置" else "包$rid"
        val b = if (bid.isBlank()) "内置" else "包$bid"
        val g = if (bgid.isBlank()) "内置盘面" else "包$bgid 背景"
        return "红方:$r · 黑方:$b · 背景:$g"
    }

    fun hasSelection(ctx: Context): Boolean {
        val (r, b, _) = currentSelection(ctx)
        return r.isNotBlank() || b.isNotBlank()
    }

    // ═══════════════════════ 导入入口 ═══════════════════════

    /**
     * 导入一个 Zip（皮肤包或他人 APK）—— ★同步版★：在【调用方所在线程】里一条道跑完，
     * 调用方负责把它放到后台线程（见 PersonalizeActivity.importZips）。
     *
     *  - 皮肤包（isApk=false）：文件名快速分类，出一套。
     *  - 他人 APK（isApk=true）：高智能提取 —— 目录分组 + Cocos 图集 + OCR 无字剔除，
     *    一个 APK 可拆出多套；每套独立成包。
     *
     * ★ 为什么改成同步：原来内部自己开 Thread + 回调链，一旦中途卡住（读超慢的 SAF 流、
     *   超大图解码把内存顶爆），主线程那边就只剩一个焊死在屏幕上的弹窗，点哪都没反应。
     *   现在收口在调用方：所有可能阻塞的活儿都在同一个后台线程里排队做完，
     *   收尾统一走调用方的 finally，弹窗必关。
     *
     * 约定：
     *  - 绝不抛出；失败一律返回（空列表 + 说明），由调用方汇报；
     *  - 落盘的临时文件一律在 finally 里删干净，不在 cacheDir 里攒垃圾；
     *  - 大图一律「先探尺寸、再采样解码」，原图再大也不会一口气吃掉几十 MB；
     *  - [cancel] 返回 true 时在关键节点尽快收工（上一个已落盘的包保留，不算失败）。
     *
     * @return Pair(成功提取出的皮肤包列表, 给人看的结果说明)
     */
    fun importZipBlocking(ctx: Context, zipUri: Uri, nameHint: String? = null,
                          isApk: Boolean = false, progress: ((String) -> Unit)? = null,
                          cancel: (() -> Boolean)? = null): Pair<List<Pack>, String> {
        val id = System.currentTimeMillis().toString()
        var tmp: File? = null
        return try {
            tmp = copyToTemp(ctx, zipUri) ?: return emptyList<Pack>() to "无法读取所选文件"
            if (cancel?.invoke() == true) return emptyList<Pack>() to "已取消"
            // ★ 先按文件头认归档类型（RAR / ZIP），不靠扩展名 —— 有些包扩展名被改过。
            val kind = detectArchive(tmp)
            val packs: List<Pack> = when {
                kind == ArcKind.RAR ->
                    // RAR / RAR5（含嵌套 RAR）：junrar 解到「虚拟文件表」再统一分套。
                    parseAndStoreArchive(ctx, tmp, id, nameHint, progress, cancel)
                kind == ArcKind.ZIP && isApk ->
                    // 他人 APK：高智能提取（目录分组 + Cocos/plist 图集 + OCR 无字剔除），一个包拆多套。
                    parseAndStoreApk(ctx, tmp, id, nameHint, progress, cancel)
                kind == ArcKind.ZIP ->
                    // ZIP 皮肤包：同样「按目录 / 按样式序号」拆成多套，丰富素材一套都不丢。
                    parseAndStoreArchive(ctx, tmp, id, nameHint, progress, cancel)
                else ->
                    return emptyList<Pack>() to "无法识别所选文件（支持 ZIP / RAR / APK）"
            }
            if (packs.isNotEmpty()) {
                packs to "提取完成：${packs.size} 套（${packs.joinToString("、") { it.name }}）"
            } else {
                emptyList<Pack>() to if (isApk)
                    "该 APK 里没有图片棋子素材（棋子可能是矢量绘制或用字体渲染，非图片），无法提取"
                else
                    "未在所选文件中找到可用的棋子或背景素材"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "导入失败", t)
            emptyList<Pack>() to "导入失败：${t.message ?: "未知错误"}"
        } finally {
            try { tmp?.delete() } catch (_: Exception) {}
        }
    }

    // ═══════════════════════ 归档识别 / 统一展开（ZIP + RAR，含嵌套） ═══════════════════════

    private enum class ArcKind { RAR, ZIP, NONE }

    /** 按文件头认归档类型：RAR 头 "Rar!\x1A\x07"，ZIP 头 "PK\x03\x04"。 */
    private fun detectArchive(f: File): ArcKind {
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val b = ByteArray(8)
                val n = raf.read(b)
                when {
                    n >= 6 && b[0] == 0x52.toByte() && b[1] == 0x61.toByte() && b[2] == 0x72.toByte() &&
                        b[3] == 0x21.toByte() && b[4] == 0x1A.toByte() && b[5] == 0x07.toByte() -> ArcKind.RAR
                    n >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                        (b[2] == 0x03.toByte() || b[2] == 0x05.toByte() || b[2] == 0x07.toByte()) -> ArcKind.ZIP
                    else -> ArcKind.NONE
                }
            }
        } catch (_: Exception) { ArcKind.NONE }
    }

    /**
     * ★ 打开 ZIP：很多国内工具打的包把文件名按 GBK/GB18030 存，却没置 UTF-8 标志位，
     *   严格 UTF-8 的 java.util.zip.ZipFile 会直接抛 "invalid CEN header (bad entry name)"，
     *   整个包就"打不开"。这里逐级兜底：UTF-8 → GB18030 → ISO-8859-1（单字节，必成）。
     *   （标志位为 UTF-8 的条目仍按 UTF-8 解，不受 charset 参数影响。）
     */
    private fun openZip(f: File): ZipFile {
        return try {
            ZipFile(f)
        } catch (t: Throwable) {
            Log.w(TAG, "ZIP 默认 UTF-8 解析失败，改用 GB18030 重试：${t.message}")
            try {
                ZipFile(f, Charset.forName("GB18030"))
            } catch (t2: Throwable) {
                ZipFile(f, Charset.forName("ISO-8859-1"))
            }
        }
    }

    /** 展开后的一个「虚拟文件」：归档内部相对路径 + 已读出的字节。 */
    private class VImg(val path: String, val bytes: ByteArray)

    private fun isNestedArchiveName(n: String): Boolean {
        val e = n.substringAfterLast('.').lowercase()
        return e == "rar" || e == "zip"
    }

    /** 解压单个条目到内存（字节数组）。 */
    private fun zipBytes(zip: ZipFile, e: ZipEntry): ByteArray? =
        try { zip.getInputStream(e).use { it.readBytes() } } catch (_: Exception) { null }

    /** junrar 解压单个条目到内存。 */
    private fun rarBytes(ar: Archive, h: FileHeader): ByteArray? =
        try { ByteArrayOutputStream().use { bos -> ar.extractFile(h, bos); bos.toByteArray() } }
        catch (_: Exception) { null }

    /** 递归展开归档：图片进 [out]；嵌套归档落到临时文件后继续展开（限深度，防炸弹/死循环）。 */
    private fun flattenArchive(ctx: Context, file: File, depth: Int,
                               out: MutableList<VImg>, cancel: (() -> Boolean)?) {
        if (depth > 3 || out.size >= MAX_IMAGES) return
        when (detectArchive(file)) {
            ArcKind.RAR -> flattenRar(ctx, file, depth, out, cancel)
            ArcKind.ZIP -> flattenZip(ctx, file, depth, out, cancel)
            ArcKind.NONE -> {}
        }
    }

    private fun flattenZip(ctx: Context, file: File, depth: Int,
                           out: MutableList<VImg>, cancel: (() -> Boolean)?) {
        val zip = try { openZip(file) } catch (t: Throwable) { Log.w(TAG, "打开 ZIP 失败", t); return }
        try {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (out.size >= MAX_IMAGES || cancel?.invoke() == true) return
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name ?: continue
                when {
                    isNestedArchiveName(name) -> zipBytes(zip, e)?.let { recurseInto(ctx, it, depth, out, cancel) }
                    isImageName(name) -> zipBytes(zip, e)?.let { out.add(VImg(name, it)) }
                }
            }
        } finally {
            try { zip.close() } catch (_: Exception) {}
        }
    }

    private fun flattenRar(ctx: Context, file: File, depth: Int,
                           out: MutableList<VImg>, cancel: (() -> Boolean)?) {
        val ar = try { Archive(file) } catch (t: Throwable) { Log.w(TAG, "打开 RAR 失败", t); return }
        try {
            var h: FileHeader? = ar.nextFileHeader()
            while (h != null) {
                if (out.size >= MAX_IMAGES || cancel?.invoke() == true) return
                val item = h
                if (!item.isDirectory) {
                    val name = item.fileName ?: ""
                    when {
                        isNestedArchiveName(name) -> rarBytes(ar, item)?.let { recurseInto(ctx, it, depth, out, cancel) }
                        isImageName(name) -> rarBytes(ar, item)?.let { out.add(VImg(name, it)) }
                    }
                }
                h = ar.nextFileHeader()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "解析 RAR 失败", t)
        } finally {
            try { ar.close() } catch (_: Exception) {}
        }
    }

    /** 把嵌套归档字节落到 cacheDir 临时文件，递归展开，用完即删。 */
    private fun recurseInto(ctx: Context, bytes: ByteArray, depth: Int,
                            out: MutableList<VImg>, cancel: (() -> Boolean)?) {
        if (bytes.size > MAX_NESTED_BYTES) return
        val tmp = File(ctx.cacheDir, "nested_${depth}_${System.nanoTime()}.arc")
        try {
            tmp.writeBytes(bytes)
            flattenArchive(ctx, tmp, depth + 1, out, cancel)
        } catch (_: Exception) {
        } finally {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    /**
     * ★ 统一「分套提取」：把展开后的图片表按【目录】分组，目录内再按【样式序号】细分，
     *   每小组独立成包 —— 一个压缩包能出几套就出几套（如 18 种棋子样式 / 124 套主题棋盘），
     *   绝不把多套素材胡乱合并成一套。
     */
    private fun parseAndStoreArchive(ctx: Context, file: File, baseId: String, nameHint: String?,
                                     progress: ((String) -> Unit)?,
                                     cancel: (() -> Boolean)?): List<Pack> {
        report(progress, "展开压缩包…")
        val imgs = ArrayList<VImg>()
        flattenArchive(ctx, file, 0, imgs, cancel)
        if (imgs.isEmpty()) return emptyList()
        report(progress, "共 ${imgs.size} 张图片，开始分套提取…")

        val out = ArrayList<Pack>()
        var idx = 0
        val byDir = LinkedHashMap<String, MutableList<VImg>>()
        for (im in imgs) {
            val d = im.path.substringBeforeLast('/', "").ifEmpty { "" }
            byDir.getOrPut(d) { ArrayList() }.add(im)
        }
        var di = 0
        val dirTotal = byDir.size
        for ((dir, files) in byDir) {
            di++
            if (cancel?.invoke() == true) break
            report(progress, "分套提取 $di/$dirTotal：${dir.ifEmpty { "根目录" }.substringAfterLast('/').take(16)}…")
            for ((suffix, group) in splitIntoStyles(files)) {
                if (cancel?.invoke() == true) break
                val name = stylePackName(nameHint, dir, suffix)
                val pack = buildOnePack(ctx, "${baseId}_${idx++}", group, name, "$dir#${suffix}")
                if (pack != null) out.add(pack)
            }
        }
        return out
    }

    /**
     * 目录内分套：
     *  - 若棋子文件名带「样式序号」后缀（如 rK1…rK18、bA1…bA18），且每种序号都凑齐 ≥5 类棋子，
     *    则按序号拆成多套（一天天 288 张 = 18 套，一套不丢）；
     *  - 否则整目录算一套（如皮卡鱼每个主题目录、RAR 里每套棋子目录）。
     */
    private fun splitIntoStyles(files: List<VImg>): List<Pair<String, List<VImg>>> {
        val styles = LinkedHashMap<String, MutableSet<PieceType>>()
        for (f in files) {
            val cls = classify(f.path)
            if (cls.kind != Kind.RED && cls.kind != Kind.BLACK) continue
            val t = cls.type ?: continue
            styles.getOrPut(styleKey(f.path)) { LinkedHashSet() }.add(t)
        }
        val goodKeys = styles.filterValues { it.size >= 5 }.keys
        if (goodKeys.size < 2) return listOf("" to files)

        val ordered = styles.keys.filter { it in goodKeys }
        val bgKey = if ("" in ordered) "" else ordered.firstOrNull() ?: ""
        val res = ArrayList<Pair<String, List<VImg>>>()
        for (k in ordered) {
            val group = ArrayList<VImg>()
            for (f in files) {
                when (classify(f.path).kind) {
                    Kind.RED, Kind.BLACK -> if (styleKey(f.path) == k) group.add(f)
                    Kind.BACKGROUND -> if (k == bgKey) group.add(f)
                    Kind.SKIP -> {}
                }
            }
            res.add(k to group)
        }
        return res
    }

    /** 棋子文件名里的「样式序号」：形如 rk1 / ba12 / wc07 才有；其余返回 ""。 */
    private fun styleKey(path: String): String {
        val stem = path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val m = Regex("""^[rwb][a-z](\d+)$""").find(stem) ?: return ""
        return m.groupValues[1]
    }

    private fun stylePackName(nameHint: String?, dir: String, suffix: String): String {
        val base = nameHint?.takeIf { it.isNotBlank() }?.let { sanitize(it) }
        val name = when {
            suffix.isNotBlank() -> "${base ?: "包"}-$suffix"
            dir.isNotBlank() -> sanitize(dir.substringAfterLast('/'))
            else -> base ?: "包"
        }
        return name.take(24).ifBlank { "包" }
    }

    /** 从一小组素材里落一个包：拆红/黑棋子 + 选最大的一张背景洗净。 */
    private fun buildOnePack(ctx: Context, id: String, files: List<VImg>, name: String, source: String): Pack? {
        val dest = packDir(ctx, id)
        if (dest.exists()) dest.deleteRecursively()
        val pieces = ArrayList<PieceCandidate>()
        var bgImg: Bitmap? = null
        var bgArea = 0L
        for (f in files.sortedBy { it.path }) {
            val cls = classify(f.path)
            when (cls.kind) {
                Kind.RED, Kind.BLACK -> {
                    val t = cls.type ?: continue
                    val bmp = decodeBytes(f.bytes, f.path, PIECE_DECODE_MAX) ?: continue
                    if (bmp.width < 30 || bmp.height < 30 || maxOf(bmp.width, bmp.height) > 2000) { bmp.recycle(); continue }
                    if (centerInk(bmp) < INK_RATIO) { bmp.recycle(); continue }   // 无字底座/背面直接剔除
                    val color = if (cls.kind == Kind.RED) PieceColor.RED else PieceColor.BLACK
                    pieces.add(PieceCandidate(t, color, bmp))
                }
                Kind.BACKGROUND -> {
                    val lower = f.path.lowercase()
                    // 走法提示/选中框/图集纹理等不是棋盘底图
                    if (lower.contains("piece") || lower.contains("sprite") || lower.contains("sheet") ||
                        lower.contains("plist") || lower.contains("path_") || lower.contains("selection") ||
                        lower.contains("_go") || lower.contains("_eat") || lower.contains("_move")) continue
                    if (f.bytes.size < 4000) continue
                    val bmp = decodeBytes(f.bytes, f.path, BG_DECODE_MAX) ?: continue
                    val w = bmp.width; val h = bmp.height
                    if (w >= 300 && h >= 300 && w.toLong() * h <= 16_000_000L) {
                        val area = w.toLong() * h
                        if (bgImg == null || area > bgArea) {
                            bgImg?.recycle(); bgImg = bmp; bgArea = area
                        } else bmp.recycle()
                    } else bmp.recycle()
                }
                Kind.SKIP -> {}
            }
        }
        if (pieces.isEmpty() && bgImg == null) { bgImg?.recycle(); dest.deleteRecursively(); return null }
        val pack = saveSet(dest, pieces, bgImg, name, source)
        bgImg?.recycle()
        return pack
    }

    // ═══════════════════════ 只导入桌布：单张 / 多选纯图片 ═══════════════════════

    /**
     * 「仅换桌布」通路：选一张或多张纯图片（PNG/JPG/WEBP…），逐张净化成桌布包。
     *  - 不要求包含棋子 —— 桌布可以单独换，也可以和已有棋子跨包搭配；
     *  - 每张图片独立成一个包，方便在列表里逐张切换；
     *  - 一律走净化：认出棋盘的洗掉棋线/九宫斜线/十字标/楚河汉界/外圈刻度；
     *    认不出棋盘的（纯纹理桌布）原样保留，绝不乱洗。
     */
    fun importImages(ctx: Context, uris: List<Uri>, progress: ((String) -> Unit)? = null,
                     done: (List<Pack>, String) -> Unit) {
        Thread {
            val out = ArrayList<Pack>()
            try {
                uris.forEachIndexed { i, uri ->
                    report(progress, "净化桌布 ${i + 1}/${uris.size}…")
                    val label = displayName(ctx, uri)
                    val bmp = decodeUri(ctx, uri, 1600) ?: return@forEachIndexed
                    try {
                        if (max(bmp.width, bmp.height) < 320) return@forEachIndexed
                        val id = "${System.currentTimeMillis()}_$i"
                        val pack = saveSet(packDir(ctx, id), emptyList(), bmp,
                            "桌布_" + label.take(22), "图片 → 桌布")
                        if (pack != null) out.add(pack)
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "导入桌布失败", t)
            }
            Handler(Looper.getMainLooper()).post {
                if (out.isNotEmpty()) {
                    val names = out.joinToString("、") { it.name }
                    done(out, "桌布净化完成：${out.size} 张（棋线/九宫斜线/十字标/楚河汉界/外圈刻度已抽干净）\n$names")
                } else {
                    done(emptyList(), "没能在所选文件里得到可用桌布（请选 PNG/JPG 等图片，或改用「导入 ZIP / 选 APK 提取」）")
                }
            }
        }.start()
    }

    /** 取 SAF 里的显示名，并去掉上传文件名常见的 32 位 uuid 前缀、扩展名。 */
    fun displayName(ctx: Context, uri: Uri): String {
        var raw: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { c -> if (c.moveToFirst()) raw = c.getString(0) }
        } catch (_: Exception) {}
        var s = raw?.trim().orEmpty().ifBlank { uri.lastPathSegment ?: "" }
        // ★ 兜底：有些三方文件管理器/SAF 提供方查不到 DISPLAY_NAME，lastPathSegment 会是
        //   primary:/storage/emulated/0/Android/data/… 这种整条路径；直接拿去当包名，
        //   列表里就会出现 [primary:/storage/emulated/0/an] 这种鬼名字。一律只取最后一段文件名。
        if (s.contains('/') || s.contains(':')) {
            s = s.substringAfterLast('/').substringAfterLast(':')
        }
        if (s.isBlank()) s = "文件"
        return shortenName(s.substringBeforeLast('.'))
    }

    /** 去掉 uuid 前缀、非法字符，截到 24 字以内。 */
    private fun shortenName(s0: String): String {
        var s = s0
        if (s.length > 37 && s[36] == '_' && s.take(36).count { it == '-' } == 4) s = s.substring(37)
        s = sanitize(s)
        if (s.length > 24) s = s.substring(0, 24)
        return s.ifBlank { "图片" }
    }

    /** 按需采样解码（bounds 先探尺寸，再 inSampleSize 降采样），异常返回 null。 */
    private fun decodeUri(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        return try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
            if (opt.outWidth <= 0 || opt.outHeight <= 0) return null
            var s = 1
            while (max(opt.outWidth, opt.outHeight) / (s * 2) >= maxDim) s *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = s }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        } catch (_: Exception) { null }
    }

    private fun copyToTemp(ctx: Context, uri: Uri): File? {
        val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.zip")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            return tmp
        } catch (_: Exception) { return null }
    }

    private fun report(progress: ((String) -> Unit)?, msg: String) {
        progress?.let { Handler(Looper.getMainLooper()).post { it(msg) } }
    }

    // ═══════════════════════ 快速路径：ZIP 皮肤包（出一套） ═══════════════════════

    private fun parseAndStoreZip(ctx: Context, zipFile: File, id: String, nameHint: String?,
                                 cancel: (() -> Boolean)? = null): Pack? {
        val dest = packDir(ctx, id)
        if (dest.exists()) dest.deleteRecursively()
        redDir(dest).mkdirs()
        blackDir(dest).mkdirs()

        val zip = ZipFile(zipFile)
        val entries = zip.entries()
        val redPlan = HashMap<PieceType, ZipEntry>()
        val blackPlan = HashMap<PieceType, ZipEntry>()
        var bgCandidate: Pair<ZipEntry, Long>? = null // entry -> 大小
        var maxSize = 0L

        var scanned = 0
        while (entries.hasMoreElements()) {
            if (++scanned % 64 == 0 && cancel?.invoke() == true) { zip.close(); dest.deleteRecursively(); return null }
            val e = entries.nextElement()
            if (e.isDirectory) continue
            val raw = e.name
            if (!isImageName(raw)) continue
            val cls = classify(raw)
            when (cls.kind) {
                Kind.RED -> if (cls.type != null && !redPlan.containsKey(cls.type)) redPlan[cls.type] = e
                Kind.BLACK -> if (cls.type != null && !blackPlan.containsKey(cls.type)) blackPlan[cls.type] = e
                Kind.BACKGROUND -> {
                    val lower = raw.lowercase()
                    if (!lower.contains("piece") && !lower.contains("sprite") && !lower.contains("sheet")) {
                        val n = e.size.coerceAtLeast(0)
                        if (n > maxSize) { maxSize = n; bgCandidate = e to n }
                    }
                }
                Kind.SKIP -> {}
            }
        }

        var redCount = 0
        var blackCount = 0
        redPlan.forEach { (type, e) ->
            val bmp = decodeEntry(zip, e, PIECE_DECODE_MAX) ?: return@forEach
            savePiece(redDir(dest), type, bmp)
            bmp.recycle(); redCount++
        }
        blackPlan.forEach { (type, e) ->
            val bmp = decodeEntry(zip, e, PIECE_DECODE_MAX) ?: return@forEach
            savePiece(blackDir(dest), type, bmp)
            bmp.recycle(); blackCount++
        }
        var bgOk = false
        if (bgCandidate != null && cancel?.invoke() != true) {
            // ★ 桌布常常是包里最大的那张图：先探尺寸再采样解码，别让原图直接把内存顶爆
            val bmp = decodeEntry(zip, bgCandidate!!.first, BG_DECODE_MAX)
            if (bmp != null) {
                val clean = TableclothSanitizer.sanitize(bmp)
                bmp.recycle()
                if (clean != null) {
                    saveBg(dest, clean)
                    clean.recycle()
                    bgOk = true
                }
            }
        }
        zip.close()

        if (redCount == 0 && blackCount == 0 && !bgOk) {
            dest.deleteRecursively()
            return null
        }

        var name = nameHint?.takeIf { it.isNotBlank() } ?: "包"
        if (name.length > 30) name = name.substring(0, 30)
        meta(dest, name, redCount > 0, blackCount > 0, bgOk, "ZIP 皮肤包")
        return Pack(id, name, redCount > 0, blackCount > 0, bgOk, "ZIP 皮肤包")
    }

    // ═══════════════════════ 高智能路径：APK 提取（出多套） ═══════════════════════

    private data class PieceCandidate(val type: PieceType, val color: PieceColor, val bmp: Bitmap)
    private data class FrameRect(val x: Int, val y: Int, val w: Int, val h: Int, val rotated: Boolean)

    /** ML Kit 中文 OCR 引擎：每个导入流程一个实例，串行调用，带次数预算与失败熔断。 */
    private class OcrEngine(private val budget: Int) {
        var used = 0
        private var recognizer: TextRecognizer? = null
        private var failed = false
        val hasBudget: Boolean get() = used < budget && !failed

        fun chars(bmp: Bitmap): Set<Char> {
            if (used >= budget) return emptySet()
            used++
            return try {
                if (recognizer == null) {
                    recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                }
                val img = InputImage.fromBitmap(preprocessForOcr(bmp), 0)
                // ★ 带超时：ML Kit 万一迟迟不返回，就直接当识别失败兜底，绝不把导入线吊死
                val text = Tasks.await(recognizer!!.process(img), OCR_TIMEOUT_SEC, TimeUnit.SECONDS)
                val chars = LinkedHashSet<Char>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        for (el in line.elements) {
                            for (ch in el.text) {
                                if (ch in ALL_CHARS) chars.add(ch)
                            }
                        }
                    }
                }
                chars
            } catch (e: Exception) {
                failed = true   // 熔断：后续不再反复尝试失败调用
                Log.w(TAG, "ML Kit OCR 不可用，改用墨量+文件名兜底", e)
                emptySet()
            }
        }
    }

    private fun parseAndStoreApk(ctx: Context, zipFile: File, baseId: String,
                                 nameHint: String?, progress: ((String) -> Unit)?,
                                 cancel: (() -> Boolean)? = null): List<Pack> {
        val zip = ZipFile(zipFile)
        val all = zip.entries().toList()
        val imgs = all.filter { !it.isDirectory && isImageName(it.name) }
        val atlases = all.filter { !it.isDirectory && it.name.lowercase().endsWith(".atlas") }
        val ocr = OcrEngine(OCR_BUDGET)
        val out = ArrayList<Pack>()
        var idx = 0
        // 已按文件名提取到的红/黑棋子数。只要有一方为 0，就说明名字这条路没走通
        // （名字被混淆、或是纯装饰图），需要放手让全图 OCR 去「认字」。
        var nRed = 0
        var nBlack = 0
        fun countPieces(list: List<PieceCandidate>) {
            for (p in list) if (p.color == PieceColor.RED) nRed++ else nBlack++
        }

        // ── 第 0 遍：Unity 引擎 APK（assets/bin/Data/*.assets[.splitN]） ──
        //   ★ 象棋神域这类 Unity 游戏：棋子/棋盘根本不是独立 PNG，而是打包进 SerializedFile
        //     里的 Texture2D（名字 RA/RB…RK / BA/BB…BK / board）；包里剩下的 PNG 全是被
        //     aapt2 混淆过的界面图（res/Ra.png、res/0p.png），按名字/OCR 怎么扒都是垃圾。
        //     先把 Unity 资源整出来交给同一套 classify() 定色定字 —— 一旦拿齐红黑两方，
        //     下面的目录分组 / OCR 兜底全部跳过，免得那堆混淆图再造出假棋子。
        if (UnityAssetReader.isUnity(zip)) {
            report(progress, "检测到 Unity 资源，解析 assets/bin/Data 纹理…")
            val tex = UnityAssetReader.extract(zip, PIECE_DECODE_MAX) { cancel?.invoke() == true }
            if (tex.isNotEmpty()) {
                val uPieces = ArrayList<PieceCandidate>()
                var uBg: Bitmap? = null
                var uBgArea = 0L
                var uBgScore = 0L
                for (t in tex) {
                    val cls = classify(t.name)
                    when (cls.kind) {
                        Kind.RED, Kind.BLACK -> {
                            val ty = cls.type
                            if (ty == null || t.bmp.width < 24 || t.bmp.height < 24) { t.bmp.recycle(); continue }
                            uPieces.add(PieceCandidate(ty,
                                if (cls.kind == Kind.RED) PieceColor.RED else PieceColor.BLACK, t.bmp))
                        }
                        Kind.BACKGROUND -> {
                            val ar = t.bmp.width.toFloat() / maxOf(1, t.bmp.height)
                            if (t.bmp.width < 300 || t.bmp.height < 300 || ar < 0.6f || ar > 1.6f) {
                                t.bmp.recycle(); continue
                            }
                            // ★ Unity 包在被当背景的候选里除了棋盘，还塞满启动图 / 特效序列帧 / UI 九宫格。
                            //   只看面积会挑错：象棋神域的 Blast01_8x5_40（爆炸帧 2048×1280）就比真正的
                            //   board（1080×1217）更大。这里按名字打分——明确叫 board/qipan/棋盘 的最优先，
                            //   命中特效/UI 噪声词一律丢弃，同档再比面积。
                            val score = unityBgScore(t.name)
                            if (score < 0) { t.bmp.recycle(); continue }
                            val area = t.bmp.width.toLong() * t.bmp.height
                            val better = uBg == null || score > uBgScore ||
                                (score == uBgScore && area > uBgArea)
                            if (better) { uBg?.recycle(); uBg = t.bmp; uBgArea = area; uBgScore = score }
                            else t.bmp.recycle()
                        }
                        Kind.SKIP -> t.bmp.recycle()
                    }
                }
                if (uPieces.isNotEmpty()) {
                    val pname = "Unity_" + (nameHint ?: "APK").take(16)
                    val pack = saveSet(packDir(ctx, "${baseId}_${idx++}"), uPieces, uBg, pname,
                        "APK → Unity assets Texture2D")
                    uBg?.recycle()
                    if (pack != null) {
                        out.add(pack); countPieces(uPieces)
                        // Unity 资源是这类 APK 的权威来源：拿齐棋子就不再跑后续可能造假的通道
                        if (nRed > 0 && nBlack > 0) { zip.close(); return out }
                    }
                } else uBg?.recycle()
            }
        }

        // ── 第一遍：A级·plist 图集（TexturePacker 精灵表，帧名自带颜色/类型，如多乐 ChessStyle_1_RK） ──
        val plists = all.filter { !it.isDirectory && it.name.lowercase().endsWith(".plist") }
        val plistPathRe = Regex("(?i)(chess|piece|qizi|skin|qi|棋)")
        var pi = 0
        for (pl in plists.sortedBy { it.name }) {
            pi++
            if (cancel?.invoke() == true) break
            if (!plistPathRe.containsMatchIn(pl.name)) continue
            report(progress, "解析 plist 图集 $pi/${plists.size}：${pl.name.substringAfterLast('/').take(20)}…")
            val stylePacks = extractPlistStylePacks(zip, pl.name)
            if (stylePacks.isEmpty()) continue
            for ((style, pieces) in stylePacks) {
                val src = pl.name.substringAfterLast('/').substringBeforeLast('.').take(10)
                val name = "样式" + style + "_" + src
                val pack = saveSet(packDir(ctx, "${baseId}_${idx++}"), pieces, null, name, "APK → ${pl.name} 样式$style")
                if (pack != null) { out.add(pack); countPieces(pieces) }
            }
        }

        // ── 第二遍：A级·Cocos 图集（帧名自带颜色/类型，无需逐个 OCR） ──
        val pieceAtlasRe = Regex("(?i)(piece|qizi|qi|chess|棋子|棋)")
        var ai = 0
        for (at in atlases.sortedBy { it.name }) {
            ai++
            if (cancel?.invoke() == true) break
            if (!pieceAtlasRe.containsMatchIn(at.name)) continue
            report(progress, "解析 Cocos 图集 $ai/${atlases.size}：${at.name.substringAfterLast('/').take(20)}…")
            val pieces = extractAtlasPieces(zip, at.name)
            if (pieces.isNullOrEmpty()) continue
            val name = "图集_" + at.name.substringAfterLast('/').removeSuffix(".atlas").take(16)
            val pack = saveSet(packDir(ctx, "${baseId}_${idx++}"), pieces, null, name, "APK → ${at.name}")
            if (pack != null) { out.add(pack); countPieces(pieces) }
        }

        // ── 第二遍：按目录分组提取（文件名分类 + 墨量无字剔除 + OCR 校验 + 像素定色） ──
        val byDir = LinkedHashMap<String, MutableList<ZipEntry>>()
        for (e in imgs) {
            val d = e.name.substringBeforeLast('/', "").ifEmpty { "root" }
            byDir.getOrPut(d) { mutableListOf() }.add(e)
        }
        val dirKeys = byDir.keys.sorted()
        var di = 0
        for (d in dirKeys) {
            di++
            if (cancel?.invoke() == true) break
            val names = byDir[d] ?: continue
            if (names.size > 600) continue  // 资源大杂烩目录跳过（防耗时爆炸）
            report(progress, "按目录提取 $di/${dirKeys.size}：${d.substringAfterLast('/').take(20)}…")
            val pieces = ArrayList<PieceCandidate>()
            var bgImg: Bitmap? = null
            var bgArea = 0L
            for (e in names.sortedBy { it.name }) {
                val cls = classify(e.name)
                when (cls.kind) {
                    Kind.RED, Kind.BLACK -> {
                        val ptype = cls.type ?: continue
                        val data = readEntry(zip, e) ?: continue
                        val bmp = decodeBytes(data, e.name, PIECE_DECODE_MAX) ?: continue
                        if (bmp.width < 30 || bmp.height < 30 || maxOf(bmp.width, bmp.height) > 1300) { bmp.recycle(); continue }
                        val fnameColor = if (cls.kind == Kind.RED) PieceColor.RED else PieceColor.BLACK
                        val v = if (ocr.hasBudget) verifyPiece(bmp, ptype, fnameColor, ocr)
                                 else if (centerInk(bmp) >= INK_RATIO) ptype to fnameColor else null
                        if (v == null) { bmp.recycle(); continue }
                        pieces.add(PieceCandidate(v.first, v.second, bmp))
                    }
                    Kind.BACKGROUND -> {
                        val lower = e.name.lowercase()
                        // 图集纹理（plist/atlas 配套 png、sprite sheet）不是棋盘背景
                        if (lower.contains("piece") || lower.contains("sprite") || lower.contains("sheet") ||
                            lower.contains("plist")) continue
                        // 动效/界面/装饰目录不可能提供棋盘背景（如多乐 assets/res/action 特效）
                        val dlow = d.lowercase()
                        val noiseDir = listOf("action", "effect", "anim", "particle", "icon", "button",
                            "font", "head", "lucky", "result", "popup", "dialog", "tip",
                            "light", "sign", "exps", "vip", "privilege", "gxhd", "fusha",
                            "gameover", "gameresult", "dh_match", "dlxq", "xybx", "common")
                        if (noiseDir.any { dlow.contains(it) }) continue
                        // 启动图 / logo / 引导页 / 分享图 / 二维码…都不是棋盘背景
                        if (bgNoiseWords.any { lower.contains(it) }) continue
                        val data = readEntry(zip, e) ?: continue
                        if (data.size < 4000) continue
                        val bmp = decodeBytes(data, e.name, BG_DECODE_MAX) ?: continue
                        if (bmp.width < 300 || bmp.height < 300) { bmp.recycle(); continue }
                        // 棋盘基本是近方形；竖图(启动页/插画)与超宽图(横幅)一律不是棋盘背景
                        val ar = bmp.width.toFloat() / bmp.height
                        if (ar < 0.6f || ar > 1.6f) { bmp.recycle(); continue }
                        val area = bmp.width.toLong() * bmp.height
                        if (bgImg == null || area > bgArea) {
                            if (bgImg != null) bgImg.recycle()
                            bgImg = bmp; bgArea = area
                        } else bmp.recycle()
                    }
                    Kind.SKIP -> {}
                }
            }
            if (pieces.isEmpty() && bgImg == null) { bgImg?.recycle(); continue }
            var name = sanitize(d.substringAfterLast('/')).take(20).ifEmpty { "root" }
            if (pieces.isEmpty()) name = "背景_" + name
            val pack = saveSet(packDir(ctx, "${baseId}_${idx++}"), pieces, bgImg, name, "APK → $d")
            if (bgImg != null) bgImg.recycle()
            if (pack != null) { out.add(pack); countPieces(pieces) }
        }

        // ── 第三遍：全图 OCR 兜底（名字被混淆成 res/0p.png 这类无序短名时，只剩「认字」一条路） ──
        //   ★ 判定条件用「红或黑缺了一方」而不是「一个包都没产生」：像象棋神域这种混淆资源的
        //     APK 会先产出一个「纯红/纯背景」的假结果，用 out.isEmpty() 判会被它挡住，永远走不到 OCR。
        if (nRed == 0 || nBlack == 0) {
            report(progress, "未识别出棋子名，开始全图 OCR 扫描…")
            val imgRe = Regex("""(?i)\.(png|jpe?g)$""")
            val cands = imgs
                .filter { imgRe.containsMatchIn(it.name) && !it.name.lowercase().endsWith(".9.png") }
                .sortedBy { it.name }.take(500)
            val sweep = ArrayList<PieceCandidate>()
            for (e in cands) {
                if (e.size < 1500 || e.size > 900000) continue
                if (!ocr.hasBudget) break
                if (cancel?.invoke() == true) break
                val data = readEntry(zip, e) ?: continue
                val bmp = decodeBytes(data, e.name, PIECE_DECODE_MAX) ?: continue
                if (bmp.width < 30 || bmp.height < 30 || maxOf(bmp.width, bmp.height) > 1300) { bmp.recycle(); continue }
                val ar = bmp.width.toFloat() / maxOf(1, bmp.height)
                if (ar < 0.6f || ar > 1.7f) { bmp.recycle(); continue }
                if (centerInk(bmp) < INK_RATIO) { bmp.recycle(); continue }
                val chars = ocr.chars(bmp)
                if (chars.isEmpty()) { bmp.recycle(); continue }
                // ★ 只要能认出一个棋子字就够：颜色优先取字的固有阵营（帅仕相兵=红 / 将士象卒=黑），
                //   认不出的（马/车/炮 红黑同字）再看像素红黑占比。旧逻辑要求「字同时给出颜色」，
                //   导致马/车/炮 一律被丢，一套棋缺一半。
                val c = chars.firstOrNull { typeOfChar(it) != null }
                if (c == null) { bmp.recycle(); continue }
                val t = typeOfChar(c)!!
                val col = colorOfChar(c) ?: dominantPieceColor(bmp)
                if (col == null) { bmp.recycle(); continue }
                sweep.add(PieceCandidate(t, col, bmp))
            }
            if (sweep.isNotEmpty()) {
                val pack = saveSet(packDir(ctx, "${baseId}_${idx++}"), sweep, null,
                    "OCR扫描套", "${nameHint ?: "APK"} OCR 兜底")
                if (pack != null) { out.add(pack); countPieces(sweep) }
            }
        }

        // ── 第四遍：字体渲染兜底 ──
        //   ★ 另一类 APK（如弈狐中象）：整包不带一张棋子图，棋子是拿包内那套「棋体」字体
        //     （assets/.../fonts/qiti.ttf）现画出来的。图片这条路怎么扒都是空，只能自己渲染：
        //     找到能画出棋子字的字体 → 把 帅/仕/相/马/车/炮/兵 与 将/士/象/马/车/炮/卒
        //     渲染成图片，红方描红、黑方描黑，凑齐两方。
        if (nRed == 0 || nBlack == 0) {
            report(progress, "未找到棋子图片，尝试用包内棋体字体渲染…")
            val fontPieces = renderPiecesFromFont(ctx, zip, all) { cancel?.invoke() == true }
            if (!fontPieces.isNullOrEmpty()) {
                val pack = saveSet(packDir(ctx, "${baseId}_${idx++}"), fontPieces, null,
                    "字体_" + (nameHint ?: "APK").take(12), "APK → 棋体字体渲染")
                if (pack != null) { out.add(pack); countPieces(fontPieces) }
            }
        }

        zip.close()
        return out
    }

    /**
     * 从包内挑一个「棋体」字体，把 14 颗棋子渲染成图片。
     * 只认体积够大的 ttf/otf（小字体基本是图标字体）；用 [Paint.hasGlyph] 先确认该字体
     * 真的收录了「帅/车/马…」这些字，画不出字的字体直接换下一个。返回 null 表示包内无可用棋体。
     */
    private fun renderPiecesFromFont(ctx: Context, zip: ZipFile, all: List<ZipEntry>,
                                     cancel: () -> Boolean): List<PieceCandidate>? {
        val fontRe = Regex("(?i)\\.(ttf|otf|ttc)$")
        val cands = all.filter { !it.isDirectory && fontRe.containsMatchIn(it.name) && it.size >= 50_000 }
            .sortedByDescending { it.size }.take(8)
        for (e in cands) {
            if (cancel()) return null
            val tmp = File(ctx.cacheDir, "skin_font_${System.nanoTime()}.ttf")
            try {
                zip.getInputStream(e).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                val tf = try { Typeface.createFromFile(tmp) } catch (_: Throwable) { null } ?: continue
                val pieces = renderChessGlyphs(tf)
                if (pieces.size >= 10) return pieces
                for (p in pieces) p.bmp.recycle()
            } catch (_: Throwable) {
            } finally {
                try { tmp.delete() } catch (_: Exception) {}
            }
        }
        return null
    }

    /** 用给定字体渲染红/黑两方 14 颗棋子；某字若字体没有则尝试繁体变体（马/馬、车/車、炮/砲）。 */
    private fun renderChessGlyphs(tf: Typeface): List<PieceCandidate> {
        val out = ArrayList<PieceCandidate>()
        val red = 0xFFC81E1E.toInt()
        val black = 0xFF1B1B1B.toInt()
        fun add(type: PieceType, color: PieceColor, ink: Int, chars: List<Char>) {
            for (ch in chars) {
                val b = renderGlyph(tf, ch, ink, 256)
                if (b != null && findAlphaBounds(b) != null && centerInk(b) >= INK_RATIO) {
                    out.add(PieceCandidate(type, color, b)); return
                }
                b?.recycle()
            }
        }
        add(PieceType.KING, PieceColor.RED, red, listOf('帅', '帥'))
        add(PieceType.ADVISOR, PieceColor.RED, red, listOf('仕'))
        add(PieceType.ELEPHANT, PieceColor.RED, red, listOf('相'))
        add(PieceType.KNIGHT, PieceColor.RED, red, listOf('马', '馬'))
        add(PieceType.ROOK, PieceColor.RED, red, listOf('车', '車'))
        add(PieceType.CANNON, PieceColor.RED, red, listOf('炮', '砲'))
        add(PieceType.PAWN, PieceColor.RED, red, listOf('兵'))
        add(PieceType.KING, PieceColor.BLACK, black, listOf('将', '將'))
        add(PieceType.ADVISOR, PieceColor.BLACK, black, listOf('士'))
        add(PieceType.ELEPHANT, PieceColor.BLACK, black, listOf('象'))
        add(PieceType.KNIGHT, PieceColor.BLACK, black, listOf('马', '馬'))
        add(PieceType.ROOK, PieceColor.BLACK, black, listOf('车', '車'))
        add(PieceType.CANNON, PieceColor.BLACK, black, listOf('炮', '砲'))
        add(PieceType.PAWN, PieceColor.BLACK, black, listOf('卒'))
        return out
    }

    /** 把一个汉字居中画到 256×256 透明画布上；字体没有该字时返回 null（避免落进豆腐块 notdef）。 */
    private fun renderGlyph(tf: Typeface, ch: Char, color: Int, size: Int): Bitmap? {
        return try {
            val s = ch.toString()
            if (!Paint().apply { typeface = tf }.hasGlyph(s)) return null
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                textSize = size * 0.76f
                this.color = color
                textAlign = Paint.Align.CENTER
            }
            val fm = p.fontMetrics
            c.drawText(s, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
            bmp
        } catch (_: Throwable) { null }
    }

    /**
     * Unity 背景候选打分：10=明确是棋盘（board/qipan/棋盘…），0=普通候选，-1=噪声丢弃。
     * 特效序列帧、图标、启动图、UI 元素等都不是棋盘底图，必须先在名字上排除，否则会因面积大而被选中。
     */
    private fun unityBgScore(name: String): Long {
        val low = name.lowercase()
        if (listOf("board", "qipan", "chessboard", "棋盘", "盘", "桌布", "cloth").any { low.contains(it) }) return 10L
        val noise = listOf("blast", "effect", "particle", "sprite", "arrow", "shadow", "point",
            "close", "check", "knob", "slider", "mask", "dropdown", "input", "button",
            "prompt", "record", "pencil", "coin", "avatar", "gift", "vip", "dialog",
            "icon", "logo", "splash", "home", "menu", "light", "sign", "cloud",
            "biankuang", "frame", "banner", "promo")
        if (noise.any { low.contains(it) }) return -1L
        if (bgNoiseWords.any { low.contains(it) }) return -1L
        return 0L
    }

    // ── Cocos2d 图集解析 ──

    private fun parseAtlas(text: String): HashMap<String, FrameRect> {
        val props = HashMap<String, HashMap<String, String>>()
        var cur: String? = null
        for (raw in text.split("\n")) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val indented = line.startsWith(" ") || line.startsWith("\t")
            if (!indented && ":" !in line.split(",", limit = 2)[0]) {
                val name = line.trim()
                val lower = name.lowercase()
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    name.startsWith("size:") || name.startsWith("format:") ||
                    name.startsWith("filter:") || name.startsWith("repeat:")) continue
                cur = name
                props[cur!!] = HashMap()
            } else if (line.contains(":") && cur != null) {
                val k = line.substringBefore(':').trim()
                val v = line.substringAfter(':').trim()
                props[cur]!![k] = v
            }
        }
        val out = HashMap<String, FrameRect>()
        for ((name, p) in props) {
            val xy = p["xy"] ?: continue
            val size = p["size"] ?: continue
            try {
                val x = xy.substringBefore(',').trim().toInt()
                val y = xy.substringAfter(',').trim().toInt()
                val w = size.substringBefore(',').trim().toInt()
                val h = size.substringAfter(',').trim().toInt()
                val rot = p["rotate"]?.equals("true", ignoreCase = true) ?: false
                out[name] = FrameRect(x, y, w, h, rot)
            } catch (_: Exception) {}
        }
        return out
    }

    /** 从 Cocos2d atlas 裁出棋子帧：xq_txt_RA~RR/BA~BR 直接定色定字；qizi01 底座与文字帧合成完整棋子。 */
    private fun extractAtlasPieces(zip: ZipFile, atlasPath: String): List<PieceCandidate>? {
        val pngPath = atlasPath.substringBeforeLast('.') + ".png"
        val text = try {
            zip.getInputStream(zip.getEntry(atlasPath)).readBytes().toString(Charsets.UTF_8)
        } catch (_: Exception) { return null }
        val frames = parseAtlas(text)
        if (frames.isEmpty()) return null
        val pngData = try { zip.getInputStream(zip.getEntry(pngPath)).readBytes() } catch (_: Exception) { return null }
        val sheet = decodeSheet(pngData) ?: return null

        // 底座帧（圆形棋子底）：优先游戏默认底座 qizi_bg_back，其次 qizi/qizi01~10 动画帧
        var baseFrame: String? = null
        for (nm in listOf("qizi_bg_back", "qizi/qizi01", "qizi/qizi02", "qizi/qizi06")) {
            if (frames.containsKey(nm)) { baseFrame = nm; break }
        }
        if (baseFrame == null) {
            baseFrame = frames.keys.firstOrNull { it.startsWith("qizi/qizi") || it.startsWith("qizi") }
        }
        var baseImg: Bitmap? = null
        baseFrame?.let { nm ->
            val fr = frames[nm] ?: return@let
            try {
                baseImg = cropAtlasFrame(sheet, fr)
            } catch (_: Exception) {}
        }

        val out = ArrayList<PieceCandidate>()
        for ((name, fr) in frames) {
            val nm = name.trim()
            if (!nm.startsWith("xq_txt_")) continue          // 只要文字帧
            val code = nm.removePrefix("xq_txt_")            // RA / BA / RK …
            if (code.length != 2) continue
            val colorCh = code[0]; val typeCh = code[1]
            if (colorCh != 'R' && colorCh != 'B') continue
            if (typeCh.lowercaseChar() == 'd') continue   // D=暗棋变体（背面纹样），剔除
            val color = if (colorCh == 'R') PieceColor.RED else PieceColor.BLACK
            val t = typeFromAtlasChar(typeCh.lowercaseChar()) ?: continue
            try {
                var frame = cropAtlasFrame(sheet, fr)
                val base = baseImg
                if (base != null) frame = composeOnBase(base, frame)
                if (centerInk(frame) < INK_RATIO) { frame.recycle(); continue }  // 空文字帧剔除
                out.add(PieceCandidate(t, color, frame))
            } catch (_: Exception) {}
        }
        sheet.recycle()
        baseImg?.recycle()
        return out
    }

    // ═══════════════════════ plist 图集（TexturePacker）═══════════════════════

    /** 解析 TexturePacker plist 文本 → {帧名: FrameRect} */
    private fun parsePlistText(text: String): HashMap<String, FrameRect> {
        val out = HashMap<String, FrameRect>()
        val frameRe = Regex("<key>([^<]+\\.png)</key>\\s*<dict>(.*?)</dict>", RegexOption.DOT_MATCHES_ALL)
        val rectRe = Regex("\\{\\{(-?\\d+),\\s*(-?\\d+)\\},\\{\\s*(-?\\d+),\\s*(-?\\d+)\\}\\}")
        val rotRe = Regex("<key>rotated</key>\\s*<true/>")
        for (m in frameRe.findAll(text)) {
            val name = m.groupValues[1]
            val body = m.groupValues[2]
            val r = rectRe.find(body) ?: continue
            val x = r.groupValues[1].toIntOrNull() ?: continue
            val y = r.groupValues[2].toIntOrNull() ?: continue
            val w = r.groupValues[3].toIntOrNull() ?: continue
            val h = r.groupValues[4].toIntOrNull() ?: continue
            if (w <= 0 || h <= 0) continue
            out[name] = FrameRect(x, y, w, h, rotRe.containsMatchIn(body))
        }
        return out
    }

    /** plist 帧名 → (颜色, 类型)；非棋子帧（底座/阴影/暗色变体等）返回 null */
    private fun classifyPlistFrame(name: String): Pair<PieceColor, PieceType>? {
        val stem = name.substringBeforeLast('.').substringAfterLast('/')
        val low = stem.lowercase()
        if (low.startsWith("qizi")) return null
        if (low.contains("bg_") || low.contains("shadow") || low.contains("_bg") ||
            low.contains("_bd") || low.contains("_rd") || low.contains("_gd")) return null
        if (stem == "qizi_bg_front.png" || stem == "qizi_bg_back.png") return null
        var s = stem
        if (s.endsWith("_opp", ignoreCase = true)) s = s.dropLast(4)
        val m = Regex("""(.+)_([brw])([kabnrcp])$""", RegexOption.IGNORE_CASE).find(s) ?: return null
        val c = m.groupValues[2].lowercase()
        val color = when (c) {
            "r", "w" -> PieceColor.RED
            "b" -> PieceColor.BLACK
            else -> return null
        }
        val code = m.groupValues[3]
        if (code.length != 1) return null
        val t = typeFromAtlasChar(code[0].lowercaseChar()) ?: return null
        return color to t
    }

    /**
     * 从 plist 图集裁出棋子帧并按样式分组：帧名 ChessStyle_<N>_XX.png / chesspad_XX.png 直接定色定字，
     * 裁出即完整棋子图（含底座），无需合成。每类取第一张（正常版优先，_opp 变体作后备）。
     * 返回：样式键 → 该样式的棋子候选；无有效棋子返回空。
     */
    private fun extractPlistStylePacks(zip: ZipFile, plistPath: String): List<Pair<String, List<PieceCandidate>>> {
        val text = try {
            zip.getInputStream(zip.getEntry(plistPath)).readBytes().toString(Charsets.UTF_8)
        } catch (_: Exception) { return emptyList() }
        val frames = parsePlistText(text)
        if (frames.isEmpty()) return emptyList()

        // 先按帧名分类统计：无棋子帧的图集（动画/粒子 plist）直接跳过，不解码纹理
        val styleRe = Regex("ChessStyle_(\\d+)_", RegexOption.IGNORE_CASE)
        val byStyle = LinkedHashMap<String, MutableList<Pair<String, FrameRect>>>()
        var classified = 0
        for ((name, fr) in frames) {
            val cls = classifyPlistFrame(name) ?: continue
            classified++
            val key = styleRe.find(name)?.groupValues?.get(1) ?: run {
                var stem = name.substringBeforeLast('.').substringAfterLast('/')
                if (stem.endsWith("_opp", ignoreCase = true)) stem = stem.dropLast(4) // _opp 并入正常版
                stem.substringBeforeLast('_')
            }
            if (key.isBlank()) continue
            byStyle.getOrPut(key) { mutableListOf() }.add(name to fr)
        }
        if (classified < 4 || byStyle.isEmpty()) return emptyList()

        val pngPath = plistPath.substringBeforeLast('.') + ".png"
        val pngData = try { zip.getInputStream(zip.getEntry(pngPath)).readBytes() } catch (_: Exception) { return emptyList() }
        val sheet = decodeSheet(pngData) ?: return emptyList()

        val out = ArrayList<Pair<String, List<PieceCandidate>>>()
        for ((style, items) in byStyle) {
            val bestR = HashMap<PieceType, Bitmap>()
            val bestB = HashMap<PieceType, Bitmap>()
            for ((name, fr) in items) {
                val (color, t) = classifyPlistFrame(name) ?: continue
                val bmp = try { cropAtlasFrame(sheet, fr) } catch (_: Exception) { null } ?: continue
                if (centerInk(bmp) < INK_RATIO) { bmp.recycle(); continue }   // 空白帧剔除
                val map = if (color == PieceColor.RED) bestR else bestB
                if (!map.containsKey(t)) map[t] = bmp else bmp.recycle()      // 每类取第一张（正常版）
            }
            val list = ArrayList<PieceCandidate>()
            for ((t, b) in bestR) list.add(PieceCandidate(t, PieceColor.RED, b))
            for ((t, b) in bestB) list.add(PieceCandidate(t, PieceColor.BLACK, b))
            if (list.isNotEmpty()) out.add(style to list)
        }
        sheet.recycle()
        return out
    }

    private fun typeFromAtlasChar(c: Char): PieceType? = when (c) {
        'k' -> PieceType.KING
        'a', 'd' -> PieceType.ADVISOR
        'b', 'e', 'x' -> PieceType.ELEPHANT
        'n', 'h' -> PieceType.KNIGHT
        'r' -> PieceType.ROOK
        'c' -> PieceType.CANNON
        'p' -> PieceType.PAWN
        else -> null
    }

    private fun rotate90(src: Bitmap): Bitmap {
        // 逆时针 90°：TexturePacker 打包 rotate:true 帧时是顺时针旋转 90° 存储的，回正要逆时针
        val m = android.graphics.Matrix().apply { postRotate(-90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
            if (it !== src) src.recycle()
        }
    }

    /**
     * 图集帧裁切：rotate:true 时 size 是原始尺寸，纹理里实际存储宽高已交换（宽=h 高=w），
     * 裁交换尺寸后再逆时针转 90° 回正（否则棋子底座被压扁、字形歪斜）。
     */
    private fun cropAtlasFrame(sheet: Bitmap, fr: FrameRect): Bitmap {
        return if (fr.rotated) {
            rotate90(Bitmap.createBitmap(sheet, fr.x, fr.y, fr.h, fr.w))
        } else {
            Bitmap.createBitmap(sheet, fr.x, fr.y, fr.w, fr.h)
        }
    }

    /** 文字帧贴到底座中心（文字约为底座 76% 宽，与游戏内 Txt_pt 比例一致）。 */
    private fun composeOnBase(base: Bitmap, textFrame: Bitmap): Bitmap {
        val bw = base.width; val bh = base.height
        val scale = (bw * 0.76f) / maxOf(textFrame.width, 1)
        var tf = textFrame
        if (abs(scale - 1f) > 0.02f) {
            val nw = maxOf(1, (textFrame.width * scale).toInt())
            val nh = maxOf(1, (textFrame.height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(textFrame, nw, nh, true)
            if (scaled !== textFrame) textFrame.recycle()
            tf = scaled
        }
        val full = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        c.drawBitmap(base, 0f, 0f, null)
        c.drawBitmap(tf, ((bw - tf.width) / 2f), ((bh - tf.height) / 2f), null)
        tf.recycle()
        return full
    }

    private fun decodeSheet(data: ByteArray): Bitmap? {
        return try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, opt)
            var s = 1
            while (maxOf(opt.outWidth, opt.outHeight) / (s * 2) >= 2048) s *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = s }
            BitmapFactory.decodeByteArray(data, 0, data.size, o)
        } catch (_: Exception) { null }
    }

    // ═══════════════════════ 校验与归一化 ═══════════════════════

    /**
     * 棋子校验（无字剔除 + OCR 确认）：
     *  1) 中心墨量必须达标 —— 空白底座/无字皮肤直接踢掉；
     *  2) OCR 读出棋子字 → 反查类型与阵营，修正文件名错位；
     *  3) OCR 读不出（篆体/花体）但墨量达标 → 信任文件名兜底，防误杀。
     */
    private fun verifyPiece(bmp: Bitmap, fnameType: PieceType, fnameColor: PieceColor,
                            ocr: OcrEngine): Pair<PieceType, PieceColor>? {
        if (centerInk(bmp) < INK_RATIO) return null
        val chars = ocr.chars(bmp)
        if (chars.isEmpty()) {
            // OCR 无结果：文件名兜底；颜色若文件名给不出再交给像素分析（这里名字必给色）
            return fnameType to fnameColor
        }
        val info = chars.mapNotNull { c -> typeOfChar(c)?.let { it to colorOfChar(c) } }
        if (info.isEmpty()) return null   // 有字但不是棋子字 → 剔除

        // 优先：OCR 类型与文件名一致（颜色以 OCR/像素为准）
        val sameType = info.firstOrNull { (t, _) -> t == fnameType }
        if (sameType != null) {
            val color = sameType.second ?: dominantPieceColor(bmp) ?: fnameColor
            return sameType.first to color
        }
        // 其次：同阵营不同字（文件名可能张冠李戴，按 OCR 修正）
        val sameCamp = info.firstOrNull { (_, c) -> c == fnameColor }
        if (sameCamp != null) {
            return sameCamp.first to (sameCamp.second ?: fnameColor)
        }
        // 完全冲突：不信任文件名，取 OCR 第一个字；颜色靠像素/阵营
        val first = info.first()
        return first.first to (first.second ?: dominantPieceColor(bmp) ?: fnameColor)
    }

    /** 像素亮度（0~255），用于墨量/定色等判定。 */
    private fun lum(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    /** 中心 40% 区域的深色像素占比；≈0 = 无字(空白底座/无字皮肤)。 */
    private fun centerInk(bmp: Bitmap): Float {
        try {
            val w = bmp.width; val h = bmp.height
            val cx0 = (w * 0.30f).toInt()
            val cy0 = (h * 0.30f).toInt()
            val cx1 = (w * 0.70f).toInt().coerceIn(cx0 + 1, w)
            val cy1 = (h * 0.70f).toInt().coerceIn(cy0 + 1, h)
            val cw = cx1 - cx0; val ch = cy1 - cy0
            val px = IntArray(cw * ch)
            bmp.getPixels(px, 0, cw, cx0, cy0, cw, ch)
            var dark = 0
            for (c in px) if (lum(c) < 110) dark++
            return dark.toFloat() / px.size
        } catch (_: Exception) { return 0f }
    }

    /** 像素级红/黑占比分析：红方棋子红字多、黑方棋子黑字多，用于鲁棒定色。 */
    private fun dominantPieceColor(bmp: Bitmap): PieceColor? {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var red = 0; var dark = 0; var total = 0
        for (c in px) {
            if (((c ushr 24) and 0xFF) < 40) continue
            total++
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (r > 90 && r > g * 1.25f && r > b * 1.25f) red++
            else if (lum(c) < 70) dark++
        }
        if (total == 0) return null
        val rr = red.toFloat() / total
        val dr = dark.toFloat() / total
        return when {
            rr > 0.03f && rr > dr * 1.8f -> PieceColor.RED
            dr > 0.03f && dr > rr * 1.8f -> PieceColor.BLACK
            else -> null
        }
    }

    /** OCR 预处理：放大到 300~600px 宽 + Otsu 二值化（与离线批量脚本一致）。 */
    private fun preprocessForOcr(src: Bitmap): Bitmap {
        var g = src
        if (g.width < 300) {
            val nw = 300
            val nh = maxOf(1, (g.height * 300f / g.width).toInt())
            g = Bitmap.createScaledBitmap(g, nw, nh, true)
        } else if (g.width > 600) {
            val nw = 600
            val nh = maxOf(1, (g.height * 600f / g.width).toInt())
            g = Bitmap.createScaledBitmap(g, nw, nh, true)
        }
        val w = g.width; val h = g.height
        val px = IntArray(w * h)
        g.getPixels(px, 0, w, 0, 0, w, h)
        val lumArr = IntArray(w * h)
        val hist = IntArray(256)
        for (i in px.indices) {
            lumArr[i] = lum(px[i])
            hist[lumArr[i]]++
        }
        var wb = 0
        var bestT = 127
        var bestVar = -1.0
        val n = w * h.toDouble()
        for (t in 0 until 256) {
            wb += hist[t]
            if (wb == 0) continue
            val wf = n - wb
            if (wf <= 0) break
            var mb = 0.0
            for (i in 0..t) mb += i * hist[i]
            mb /= wb
            var mf = 0.0
            for (i in (t + 1) until 256) mf += i * hist[i]
            mf /= wf
            val v = wb * wf * (mb - mf) * (mb - mf)
            if (v > bestVar) { bestVar = v; bestT = t }
        }
        val outp = IntArray(w * h)
        for (i in px.indices) {
            val v = if (lumArr[i] > bestT) 255 else 0
            outp[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outp, 0, w, 0, 0, w, h)
        if (g !== src) g.recycle()
        return out
    }

    private fun typeOfChar(c: Char): PieceType? = when (c) {
        '帅', '将' -> PieceType.KING
        '仕', '士' -> PieceType.ADVISOR
        '相', '象' -> PieceType.ELEPHANT
        '马', '馬' -> PieceType.KNIGHT
        '车', '車' -> PieceType.ROOK
        '炮', '砲', '包' -> PieceType.CANNON
        '兵', '卒' -> PieceType.PAWN
        else -> null
    }

    private fun colorOfChar(c: Char): PieceColor? = when (c) {
        '帅', '仕', '相', '兵' -> PieceColor.RED
        '将', '士', '象', '卒' -> PieceColor.BLACK
        else -> null   // 马/车/炮 需靠像素或文件名定色
    }

    // ═══════════════════════ 成套保存 ═══════════════════════

    /**
     * 把一组候选棋子存成一套皮肤包：
     *  - 同类型去重（留墨量最高者）；
     *  - 归一化到 256×256 透明底；
     *  - 背景洗净经纬线后存 bg.png。
     */
    private fun saveSet(dest: File, pieces: List<PieceCandidate>, bgSrc: Bitmap?,
                        name: String, source: String): Pack? {
        val rDir = redDir(dest); val bDir = blackDir(dest)
        rDir.mkdirs(); bDir.mkdirs()
        val bestR = HashMap<PieceType, Bitmap>()
        val bestB = HashMap<PieceType, Bitmap>()
        for (pc in pieces) {
            val map = if (pc.color == PieceColor.RED) bestR else bestB
            val cur = map[pc.type]
            if (cur == null || centerInk(pc.bmp) > centerInk(cur)) {
                if (cur != null && cur !== pc.bmp) cur.recycle()
                map[pc.type] = pc.bmp
            } else {
                pc.bmp.recycle()
            }
        }
        var nR = 0
        for ((t, b) in bestR) {
            val norm = normalizePiece(b)
            if (norm != null) { savePng(File(rDir, pieceFileName(t)), norm); norm.recycle(); nR++ }
            b.recycle()
        }
        var nB = 0
        for ((t, b) in bestB) {
            val norm = normalizePiece(b)
            if (norm != null) { savePng(File(bDir, pieceFileName(t)), norm); norm.recycle(); nB++ }
            b.recycle()
        }
        var bgOk = false
        if (bgSrc != null) {
            val clean = TableclothSanitizer.sanitize(bgSrc)
            if (clean != null) {
                savePng(bgFile(dest), clean)
                clean.recycle()
                bgOk = true
            }
        }
        if (nR == 0 && nB == 0 && !bgOk) {
            dest.deleteRecursively()
            return null
        }
        meta(dest, name, nR > 0, nB > 0, bgOk, source)
        val id = dest.name.removePrefix("pack_")
        return Pack(id, name, nR > 0, nB > 0, bgOk, source)
    }

    /** 归一化：透明裁边 → 正方形画布 → 256×256。 */
    private fun normalizePiece(src: Bitmap): Bitmap? {
        return try {
            var img = src
            val b = findAlphaBounds(img) ?: return null
            if (b[0] > 0 || b[1] > 0 || b[2] < img.width || b[3] < img.height) {
                val cropped = Bitmap.createBitmap(src, b[0], b[1], b[2] - b[0], b[3] - b[1])
                if (cropped !== img) img = cropped
            }
            val w = img.width; val h = img.height
            val side = maxOf(w, h).coerceAtLeast(1)
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(img, ((side - w) / 2f), ((side - h) / 2f), null)
            if (img !== src) img.recycle()
            if (side != 256) {
                val scaled = Bitmap.createScaledBitmap(out, 256, 256, true)
                if (scaled !== out) out.recycle()
                scaled
            } else out
        } catch (_: Exception) { null }
    }

    private fun findAlphaBounds(bmp: Bitmap): IntArray? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                if (((px[off + x] ushr 24) and 0xFF) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX + 1, maxY + 1)
    }

    private fun meta(p: File, name: String, r: Boolean, b: Boolean, g: Boolean, source: String) {
        val f = File(p, "meta.txt")
        try { f.writeText("name=$name\nred=$r\nblack=$b\nbg=$g\nsource=$source\n") } catch (_: Exception) {}
    }

    private fun savePiece(dir: File, type: PieceType, bmp: Bitmap) {
        savePng(File(dir, pieceFileName(type)), bmp)
    }

    private fun saveBg(p: File, bmp: Bitmap) {
        savePng(bgFile(p), bmp)
    }

    private fun savePng(f: File, bmp: Bitmap) {
        try {
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } catch (_: Exception) {}
    }

    private fun readEntry(zip: ZipFile, e: ZipEntry): ByteArray? {
        return try { zip.getInputStream(e).readBytes() } catch (_: Exception) { null }
    }

    /**
     * 解码一个 zip 条目。
     * [maxDim] > 0 时走「先探尺寸 → inSampleSize 采样 → 再解码」：
     * 图片动不动几千像素见方，直接全尺寸解码就是几十 MB 的瞬时占用，
     * 几个大图叠一起足够把内存顶爆、把界面拖死 —— 采样解码是「导入桌布」一直顺溜的关键，
     * 这里同样照做。棋子/背景最终都会被缩到 256 或 900 上下，采样不损失可用质量。
     */
    private fun decodeEntry(zip: ZipFile, e: ZipEntry, maxDim: Int = 0): Bitmap? {
        return try {
            val isSvg = e.name.substringAfterLast('.').lowercase() == "svg"
            if (isSvg) {
                decodeSvg(zip.getInputStream(e))
            } else if (maxDim <= 0) {
                zip.getInputStream(e).use { BitmapFactory.decodeStream(it) }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var s = 1
                while (max(bounds.outWidth, bounds.outHeight) / (s * 2) >= maxDim) s *= 2
                val o = BitmapFactory.Options().apply { inSampleSize = s }
                zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, o) }
            }
        } catch (_: Exception) { null }
    }

    /** 同 [decodeEntry]，但输入是已读进内存的字节数组；[maxDim] > 0 时同样采样解码。 */
    private fun decodeBytes(data: ByteArray, name: String, maxDim: Int = 0): Bitmap? {
        return try {
            if (name.substringAfterLast('.').lowercase() == "svg") {
                decodeSvg(data.inputStream())
            } else if (maxDim <= 0) {
                BitmapFactory.decodeByteArray(data, 0, data.size)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var s = 1
                while (max(bounds.outWidth, bounds.outHeight) / (s * 2) >= maxDim) s *= 2
                val o = BitmapFactory.Options().apply { inSampleSize = s }
                BitmapFactory.decodeByteArray(data, 0, data.size, o)
            }
        } catch (_: Exception) { null }
    }

    /** 用 AndroidSVG 把 svg 渲染成位图（处理 .svg 皮肤包）。 */
    private fun decodeSvg(input: java.io.InputStream): Bitmap? {
        return try {
            val svg = com.caverock.androidsvg.SVG.getFromInputStream(input)
            val vb = svg.documentViewBox
            val scale = 2f // 放大 2 倍保证清晰
            val w = when {
                svg.documentWidth != null -> svg.documentWidth.toInt()
                vb.width() > 0f -> vb.width().toInt()
                else -> 512
            }
            val h = when {
                svg.documentHeight != null -> svg.documentHeight.toInt()
                vb.height() > 0f -> vb.height().toInt()
                else -> 512
            }
            val bw = (w * scale).toInt().coerceAtLeast(1)
            val bh = (h * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            svg.renderToCanvas(c)
            bmp
        } catch (_: Exception) { null }
    }

    // ═══════════════════════ 分类器 ═══════════════════════

    private enum class Kind { RED, BLACK, BACKGROUND, SKIP }
    private data class Cls(val kind: Kind, val type: PieceType?)

    private fun isImageName(n: String): Boolean {
        val e = n.substringAfterLast('.').lowercase()
        return e in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "svg")
    }

    private fun sanitize(s: String): String = s.replace(Regex("""[\\/:*?"<>|]"""), "_")

    /** 把文件名切成「字母数字 token」（下划线 / 短横 / 数字 / 空格分隔），供拼音识别使用。 */
    private fun nameTokens(s: String): List<String> =
        s.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * 拼音颜色词：hong=红、hei=黑。
     * 国产 Cocos2d / 原生游戏常把棋子命名成 qipan_hong_bing、hei_ma 这类全拼，
     * 只认英文 red_/black_ 的旧逻辑会把它们整批当成「背景」丢掉。
     */
    private fun pinyinColorOf(fileName: String): PieceColor? {
        for (t in nameTokens(fileName)) when (t) {
            "hong" -> return PieceColor.RED
            "hei" -> return PieceColor.BLACK
        }
        return when {
            fileName.contains("hong") -> PieceColor.RED
            fileName.contains("hei") -> PieceColor.BLACK
            else -> null
        }
    }

    /**
     * 拼音棋子词（全拼）：bing/zu=兵卒、che/ju=车、ma=马、pao=炮、
     * shi=士仕、shuai/jiang=帅将、xiang=相象。先按 token 精确匹配（qipan_hong_bing），
     * 再对不会误伤的长词做整串兜底（qipanhongbing）。
     */
    private fun pinyinTypeOf(fileName: String): PieceType? {
        for (t in nameTokens(fileName)) PINYIN_TYPE[t]?.let { return it }
        for ((k, v) in PINYIN_TYPE_SUBSTR) if (fileName.contains(k)) return v
        return null
    }

    private val PINYIN_TYPE = mapOf(
        "shuai" to PieceType.KING, "jiang" to PieceType.KING,
        "shi" to PieceType.ADVISOR,
        "xiang" to PieceType.ELEPHANT,
        "ma" to PieceType.KNIGHT,
        "che" to PieceType.ROOK, "ju" to PieceType.ROOK,
        "pao" to PieceType.CANNON,
        "bing" to PieceType.PAWN, "zu" to PieceType.PAWN
    )

    /** 无分隔全拼兜底：只用不会误伤的长词（短词如 ma/zu/che 易被别的单词撞中，不做整串匹配；
     *  jiang/xiang 去掉——"jiangjun"(将军特效)、"xiangqi"(象棋) 会被误判成棋子）。 */
    private val PINYIN_TYPE_SUBSTR = listOf(
        "shuai" to PieceType.KING, "bing" to PieceType.PAWN, "pao" to PieceType.CANNON
    )

    /**
     * 尝试解析一个 Zip 条目：
     *  关键点：只取「文件名」判定，彻底忽略前面可能存在的任意目录层级——
     *  因为很多皮肤包把棋子放在子文件夹/乱码文件夹里（如 黄木纹理/bk.png、大祥隷/…/bk.png），
     *  若把整条路径拿去扫双字母码，就会把目录名当颜色码，导致棋子被误判成背景。
     * 顺序：明显背景名 → red_/black_/红/黑 前缀 → 拼音(hong_/hei_) → 双字母码(rk/wk/bk…) → 中文单字 → 兜底背景。
     */
    private fun classify(name: String): Cls {
        // 只取文件名（去掉一切父目录）+ 去掉扩展名
        val fileName = name.substringBeforeLast('.').substringAfterLast('/').lowercase().trim()
        if (fileName.isEmpty()) return Cls(Kind.SKIP, null)

        // 1. 明显的背景名（仅针对文件名，且文件名本身不含 piece 字样）
        if (fileName == "board" || fileName.contains("board") || fileName.contains("background") ||
            fileName.startsWith("bg") || fileName.contains("back") || fileName.contains("texture") ||
            fileName.contains("wood") || fileName.contains("盘")) {
            if (!fileName.contains("piece")) return Cls(Kind.BACKGROUND, null)
        }

        // 2. red_/black_ / 红 / 黑 前缀（优先于双字母码：red_rook 不能被当成 elephant）
        val lp = fileName
        if (lp.startsWith("red") || lp.startsWith("r_") || lp.startsWith("w_") || lp.contains("红")) {
            return Cls(Kind.RED, typeFromNameRest2(lp, 'r') ?: typeByNameGuess(lp))
        }
        if (lp.startsWith("black") || lp.startsWith("b_") || lp.contains("黑")) {
            return Cls(Kind.BLACK, typeFromNameRest2(lp, 'b') ?: typeByNameGuess(lp))
        }

        // 2.5 全拼命名: qipan_hong_bing / hei_ma / qipanhongche（国产 Cocos2d 系常用）
        val pyColor = pinyinColorOf(lp)
        if (pyColor != null) {
            val t = pinyinTypeOf(lp)
            if (t != null) return Cls(if (pyColor == PieceColor.RED) Kind.RED else Kind.BLACK, t)
        }

        // 3. 颜色码 + 棋子类型字母（红方 r/w，黑方 b），如 rk.png / wk.png / bd.png / rk_1.png。
        //    ★ 只认「纯短码」（字母后最多跟数字），避免把 weixinpublic / result 这类整词
        //      按前两个字母误判成棋子（w+e→红相），平白造出假棋子。
        if (fileName.length <= 6 && Regex("^[rwb][a-z](_?\\d+)?$").matches(fileName)) {
            val color = if (fileName[0] == 'b') PieceColor.BLACK else PieceColor.RED
            val t = typeFromChar(fileName[1])
            if (t != null) return Cls(if (color == PieceColor.RED) Kind.RED else Kind.BLACK, t)
            return Cls(Kind.SKIP, null)
        }

        // 4. 中文单字
        if (fileName.length == 1) {
            val ch = fileName.last()
            val m = typeFromChinese(ch)
            if (m != null) {
                val color = when (ch) {
                    '帅', '仕', '相', '兵' -> PieceColor.RED
                    '将', '士', '象', '卒' -> PieceColor.BLACK
                    else -> null // 马/车/炮 交由颜色取样（此版本兜底为红方）
                }
                if (color != null) return Cls(if (color == PieceColor.RED) Kind.RED else Kind.BLACK, m)
            }
        }

        // 5. 其余非棋子图当作背景候选
        return Cls(Kind.BACKGROUND, null)
    }

    /** 处理 red_/black_ 前缀后的剩余部分，取第一个类型字母；返回 null 则交给 typeByNameGuess。 */
    private fun typeFromNameRest2(rest: String, colorChar: Char): PieceType? {
        var s = rest.removePrefix("_").removePrefix("red_").removePrefix("black_")
        if (colorChar == 'r') s = s.removePrefix("red").removePrefix("红")
        if (colorChar == 'b') s = s.removePrefix("black").removePrefix("黑")
        s = s.removePrefix("_")
        if (s.isEmpty()) return null
        val c = s.first()
        if (c == colorChar) return null // 孤零零一个 r/b 是颜色本身，非类型
        return typeFromChar(c) ?: typeByNameGuess(s)
    }

    private fun typeFromChar(c: Char): PieceType? = when (c.lowercaseChar()) {
        'k' -> PieceType.KING
        'a', 'd' -> PieceType.ADVISOR
        'b', 'e', 'x' -> PieceType.ELEPHANT
        'n', 'h' -> PieceType.KNIGHT
        'r' -> PieceType.ROOK
        'c' -> PieceType.CANNON
        'p' -> PieceType.PAWN
        else -> null
    }

    private fun typeByNameGuess(s: String): PieceType? {
        pinyinTypeOf(s)?.let { return it }
        return when {
            s.contains("king") || s.contains("帅") || s.contains("将") -> PieceType.KING
            s.contains("advisor") || s.contains("guard") || s.contains("仕") || s.contains("士") -> PieceType.ADVISOR
            s.contains("bishop") || s.contains("elephant") || s.contains("相") || s.contains("象") -> PieceType.ELEPHANT
            s.contains("knight") || s.contains("horse") || s.contains("马") || s.contains("馬") -> PieceType.KNIGHT
            s.contains("rook") || s.contains("chariot") || s.contains("车") || s.contains("車") -> PieceType.ROOK
            s.contains("cannon") || s.contains("炮") || s.contains("砲") || s.contains("包") -> PieceType.CANNON
            s.contains("pawn") || s.contains("soldier") || s.contains("兵") || s.contains("卒") -> PieceType.PAWN
            else -> null
        }
    }

    private fun typeFromChinese(ch: Char): PieceType? = when (ch) {
        '帅', '将' -> PieceType.KING
        '仕', '士' -> PieceType.ADVISOR
        '相', '象' -> PieceType.ELEPHANT
        '马', '馬' -> PieceType.KNIGHT
        '车', '車' -> PieceType.ROOK
        '炮', '砲', '包' -> PieceType.CANNON
        '兵', '卒' -> PieceType.PAWN
        else -> null
    }

    private val RED: PieceColor get() = PieceColor.RED

    // 棋盘洗净（剥离棋线 / 九宫斜线 / 炮位兵位十字标 / 楚河汉界 / 外圈刻度 / 水印）
    // 统一交给 TableclothSanitizer.sanitize：无论素材来自 Zip 包里的背景图，
    // 还是「仅导入桌布」的纯图片，都走同一套深度净化，只留底色。
}
