package com.pikafish.chess.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * ★★ 全局托底色（全局背景）★★
 *
 * 用一份统一背景同时衬托多个"文字/思考显示区"：主界面思考细节(选时)、挑战、背谱、解惑、主界面文字衬托。
 *
 * 两套并行方案，用户任选：
 *   ① 纯色 —— 点开【色谱调色板】：饱和度/明度二维色谱 + 色相滑条，点到哪取哪（保留原方案）。
 *   ② 图片 —— 直接选图片文件（PNG/JPG/WEBP/BMP/GIF… 认后缀认内容），
 *      或选一个 ZIP 由我们把它里面的图片【全部列出来】让他自己勾一张。
 *
 * 另附：★ 楚河汉界「年月日时分秒」的【时间颜色】调色板（含"跟随棋线色"档）。
 *       它严格说属于棋盘，但只有一处入口，为免用户多点两三下，一并收在这块。
 *
 * 无论纯色还是图片，最终都持久化在 global_backdrop_prefs，重启仍在（与棋子皮肤同理）。
 */
object GlobalBackdrop {

    private const val PREFS = "global_backdrop_prefs"
    private const val KEY_COLOR = "backdrop_argb"
    private const val KEY_MODE = "backdrop_mode"             // "color" | "image"
    private const val KEY_IMAGE_FILE = "backdrop_image_file" // 图片文件名（存于 filesDir/global_backdrop/）
    private const val KEY_TIME_COLOR = "river_time_argb"     // -1 = 跟随棋线色
    private const val KEY_TIME_WEIGHT = "river_time_weight"  // 0=细 1=中 2=粗
    private const val DIR = "global_backdrop"

    /** 落盘图片的最长边上限（够铺满任何手机屏，又不至于把内存顶爆）。 */
    private const val MAX_IMAGE_DIM = 2048

    /** 薄纱的"静默"默认色：粉蓝（背谱、解惑的纸面，未去改色时就是这个粉蓝）。 */
    const val DEFAULT_COLOR = 0xFFA8C7E6.toInt()

    /** 时间颜色 = 跟随棋线色（默认档）。 */
    const val TIME_FOLLOW_LINE = -1

    /** 时间颜色快捷预设（调色板下方那一排小色块）。 */
    private val TIME_PRESETS = listOf(
        "墨黑" to "#1A1A1A", "纯白" to "#FFFFFF", "朱砂" to "#C62828", "金黄" to "#FFB300",
        "深蓝" to "#1A237E", "墨绿" to "#1B5E20", "湖蓝" to "#0277BD", "棕褐" to "#5D4037"
    )

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ─────────────────────────── 本色（纯色）───────────────────────────

    fun currentColor(c: Context): Int = prefs(c).getInt(KEY_COLOR, DEFAULT_COLOR)

    /** 设纯色。写色的同时把模式切回"纯色"——否则用户选了图片又回来调色，画面上还是图片。 */
    fun setCurrentColor(c: Context, argb: Int) {
        prefs(c).edit().putInt(KEY_COLOR, argb).putString(KEY_MODE, "color").apply()
    }

    // ─────────────────────────── 背景图 ───────────────────────────

    fun backdropDir(c: Context): File =
        File(c.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun mode(c: Context): String = prefs(c).getString(KEY_MODE, "color") ?: "color"

    /** 当前生效的背景图片文件（模式=图片且文件确实存在才认）。 */
    fun currentImageFile(c: Context): File? {
        if (mode(c) != "image") return null
        val n = prefs(c).getString(KEY_IMAGE_FILE, null) ?: return null
        val f = File(backdropDir(c), n)
        return if (f.exists() && f.length() > 0) f else null
    }

    /** 是否正在用图片当全局背景。 */
    fun isImageMode(c: Context): Boolean = currentImageFile(c) != null

    // 已解码的背景图缓存：避免每次 applyGlobalBackdrop 都重新解码一遍大图。
    private var cachePath: String? = null
    private var cacheBmp: Bitmap? = null

    private fun loadBitmap(c: Context, f: File): Bitmap? {
        val path = f.absolutePath
        cacheBmp?.let { if (!it.isRecycled && cachePath == path) return it }
        val dm = c.resources.displayMetrics
        val bmp = decodeFileScaled(f, max(dm.widthPixels, dm.heightPixels).coerceAtLeast(1080))
        cachePath = path
        cacheBmp = bmp
        return bmp
    }

    /**
     * ★ 全局背景 Drawable —— 各宿主一律用这一个：图片模式返回铺满的背景图，否则返回纯色。
     *   永不返回 null。
     */
    fun backdropDrawable(c: Context): Drawable {
        val f = currentImageFile(c)
        if (f != null) {
            loadBitmap(c, f)?.let { bmp ->
                return BitmapDrawable(c.resources, bmp).apply { setGravity(Gravity.FILL) }
            }
        }
        return ColorDrawable(currentColor(c))
    }

    /** 还原成默认纯色（并清掉图片模式）。 */
    fun resetToDefault(c: Context) {
        prefs(c).edit()
            .putString(KEY_MODE, "color")
            .putInt(KEY_COLOR, DEFAULT_COLOR)
            .apply()
    }

    // ─────────────────────────── 时间颜色 ───────────────────────────

    /** 楚河汉界时间的字色：TIME_FOLLOW_LINE(-1) = 跟随棋线色。 */
    fun riverTimeColor(c: Context): Int = prefs(c).getInt(KEY_TIME_COLOR, TIME_FOLLOW_LINE)

    fun setRiverTimeColor(c: Context, argb: Int) {
        prefs(c).edit().putInt(KEY_TIME_COLOR, argb).apply()
    }

    /** 楚河汉界时间的字粗：0=细 1=中（默认） 2=粗。 */
    fun riverTimeWeight(c: Context): Int = prefs(c).getInt(KEY_TIME_WEIGHT, 1).coerceIn(0, 2)

    fun setRiverTimeWeight(c: Context, w: Int) {
        prefs(c).edit().putInt(KEY_TIME_WEIGHT, w.coerceIn(0, 2)).apply()
    }

    // ─────────────────────────── 前景色 ───────────────────────────

    /** 根据底色明暗返回适合写在上面、对比强烈的前景色（暗底纯白 / 亮底深褐）。 */
    fun foreground(c: Context, argb: Int): Int {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return if (0.299 * r + 0.587 * g + 0.114 * b > 140) Color.parseColor("#2A2418")
        else Color.parseColor("#FFFFFF")
    }

    /** 当前全局背景（纯色档）上可读的前景色。 */
    fun foreground(c: Context): Int = foreground(c, currentColor(c))

    // ─────────────────────────── 主界面菜单底色（原样保留） ───────────────────────────

    private val MENU_BG_HEX = listOf(
        "#FFF3C4", "#FFF8E1", "#FFFDE7", "#FFF59D", "#FFF3C4", "#FFE6BC",
        "#FFCCBC", "#FFE0B2", "#FFF176", "#FFCC80", "#FFB74D", "#FFEBEE",
        "#DCEDC8", "#C8EAE4", "#B2EBF2", "#BEE7FC", "#B2DFDB", "#EDE7F6",
        "#ECEFF1", "#F5F5F5", "#E0E0E0", "#3E2723", "#1B3A2A", "#1A2A5C"
    )

    fun menuBackgroundColor(c: Context): Int {
        val p = c.getSharedPreferences("pikafish_chess_prefs", Context.MODE_PRIVATE)
        val idx = p.getInt("menu_bg_idx", 0).coerceIn(0, MENU_BG_HEX.size - 1)
        return Color.parseColor(MENU_BG_HEX[idx])
    }

    fun menuForeground(c: Context): Int = foreground(c, menuBackgroundColor(c))

    // ═══════════════════════ 图片导入 ═══════════════════════

    /** 认图的扩展名（三方文件管理器常给不出 MIME，只能认后缀）。 */
    private val IMAGE_EXTS = setOf(
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif", "jfif", "avif", "tif", "tiff", "wbmp"
    )

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isImageName(n: String): Boolean = extOf(n) in IMAGE_EXTS

    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxDim) s *= 2
        return s
    }

    private fun decodeFileScaled(f: File, maxDim: Int): Bitmap? = try {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) null
        else BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(probe.outWidth, probe.outHeight, maxDim) }
        )
    } catch (_: Throwable) { null }

    private fun decodeUriScaled(c: Context, uri: Uri, maxDim: Int): Bitmap? = try {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, probe) }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) null
        else c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize(probe.outWidth, probe.outHeight, maxDim) })
        }
    } catch (_: Throwable) { null }

    /**
     * 把一张外部图片落盘成全局背景图。返回 null=成功，否则为给人看的错误说明。
     * 一律降采样到 MAX_IMAGE_DIM 内再存：原图再大也不会一口气把内存吃光。
     */
    fun saveBackdropFromUri(c: Context, uri: Uri): String? {
        val bmp = decodeUriScaled(c, uri, MAX_IMAGE_DIM)
            ?: return "这张图读不出来，换一张试试"
        if (max(bmp.width, bmp.height) < 64) {
            bmp.recycle(); return "这张图太小了，当不了背景"
        }
        return try {
            persistBitmap(c, bmp)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    /** 把 Bitmap 落盘并切到图片模式。返回 null=成功。 */
    private fun persistBitmap(c: Context, bmp: Bitmap): String? {
        val hasAlpha = bmp.hasAlpha()
        val name = if (hasAlpha) "backdrop.png" else "backdrop.jpg"
        val fmt = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val dir = backdropDir(c)
        val tmp = File(dir, "$name.tmp")
        return try {
            FileOutputStream(tmp).use { bmp.compress(fmt, 92, it) }
            val dst = File(dir, name)
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            // 两种情况可能留下另一种格式的旧文件，一并清掉，免得缓存路径打架
            File(dir, if (hasAlpha) "backdrop.jpg" else "backdrop.png").delete()
            cachePath = null; cacheBmp = null
            prefs(c).edit().putString(KEY_MODE, "image").putString(KEY_IMAGE_FILE, name).apply()
            null
        } catch (t: Throwable) {
            try { tmp.delete() } catch (_: Exception) {}
            "保存背景图失败：${t.message ?: "未知错误"}"
        }
    }

    /** 复制外部 ZIP 到 cacheDir（后台调用）。 */
    fun copyZipToCache(c: Context, uri: Uri): File? = try {
        val dir = File(c.cacheDir, "backdrop_zip").apply { if (!exists()) mkdirs() }
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        val tmp = File(dir, "src_${System.currentTimeMillis()}.zip")
        c.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out) }
        }
        if (tmp.exists() && tmp.length() > 0) tmp else null
    } catch (_: Throwable) { null }

    /** 列出 ZIP 内所有图片条目名（按名称排序，稳定可预期）。 */
    fun listZipImageNames(zip: File): List<String> = try {
        ZipFile(zip).use { z ->
            val out = ArrayList<String>()
            val es = z.entries()
            while (es.hasMoreElements()) {
                val e = es.nextElement()
                if (!e.isDirectory && isImageName(e.name) && !e.name.startsWith("__MACOSX")) out.add(e.name)
            }
            out.sorted()
        }
    } catch (_: Throwable) { emptyList() }

    private fun decodeZipEntry(zip: File, entry: String, maxDim: Int): Bitmap? = try {
        ZipFile(zip).use { z ->
            z.getEntry(entry)?.let { e -> z.getInputStream(e).use { BitmapFactory.decodeStream(it) } }
        }?.let { raw ->
            val s = sampleSize(raw.width, raw.height, maxDim)
            if (s <= 1) raw
            else Bitmap.createScaledBitmap(raw, max(1, raw.width / s), max(1, raw.height / s), true)
                .also { if (it !== raw) raw.recycle() }
        }
    } catch (_: Throwable) { null }

    /** 从 ZIP 取指定条目设为全局背景。返回 null=成功。 */
    fun saveBackdropFromZipEntry(c: Context, zip: File, entry: String): String? = try {
        ZipFile(zip).use { z ->
            val e = z.getEntry(entry) ?: return "ZIP 里找不到这张图"
            val bmp = z.getInputStream(e).use { BitmapFactory.decodeStream(it) }
                ?: return "这张图解不出来"
            try { persistBitmap(c, bmp) } finally { if (!bmp.isRecycled) bmp.recycle() }
        }
    } catch (t: Throwable) { "从 ZIP 取图失败：${t.message ?: "未知错误"}" }

    // ═══════════════════════ 对外入口：选图 / 选 ZIP ═══════════════════════

    /** 单张图片 → 直接设为背景；多张 → 列出来让用户勾。 */
    fun importImagesAsBackdrop(activity: Activity, uris: List<Uri>, onApplied: () -> Unit) {
        Thread {
            val choices = ArrayList<Choice>()
            uris.forEach { uri ->
                val thumb = decodeUriScaled(activity, uri, 360)
                choices.add(Choice(SkinManager.displayName(activity, uri), thumb) {
                    Thread {
                        val err = saveBackdropFromUri(activity, uri)
                        post(activity) {
                            if (err == null) { onApplied(); toast(activity, "已设为全局底色") }
                            else toast(activity, err)
                        }
                    }.start()
                })
            }
            post(activity) {
                when {
                    choices.isEmpty() -> toast(activity, "没读出可用图片，换一张试试")
                    choices.size == 1 -> choices[0].onPick()
                    else -> showChoiceGrid(activity, "选一张作为全局底色", choices)
                }
            }
        }.start()
    }

    /** ZIP → 把里面所有图片列出来（不管几张，全排列开）让用户勾一张。 */
    fun importZipAsBackdrop(activity: Activity, uri: Uri, onApplied: () -> Unit) {
        Thread {
            val zip = copyZipToCache(activity, uri)
            if (zip == null) { post(activity) { toast(activity, "读不到这个 ZIP 文件") }; return@Thread }
            val names = listZipImageNames(zip)
            if (names.isEmpty()) { post(activity) { toast(activity, "这个 ZIP 里没有图片") }; return@Thread }
            val choices = names.map { n ->
                Choice(n, decodeZipEntry(zip, n, 360)) {
                    Thread {
                        val err = saveBackdropFromZipEntry(activity, zip, n)
                        post(activity) {
                            if (err == null) { onApplied(); toast(activity, "已设为全局底色") }
                            else toast(activity, err)
                        }
                    }.start()
                }
            }
            post(activity) {
                if (choices.size == 1) choices[0].onPick()
                else showChoiceGrid(activity, "ZIP 内共 ${choices.size} 张，选一张当底色", choices)
            }
        }.start()
    }

    private class Choice(val label: String, val thumb: Bitmap?, val onPick: () -> Unit)

    private fun post(activity: Activity, block: () -> Unit) {
        Handler(Looper.getMainLooper()).post { if (!activity.isFinishing) block() }
    }

    private fun toast(c: Context, msg: String) {
        android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 缩略图宫格：ZIP 里几张就排几张，点哪张用哪张。 */
    private fun showChoiceGrid(activity: Activity, title: String, choices: List<Choice>) {
        val d = activity.resources.displayMetrics.density
        val grid = GridView(activity).apply {
            numColumns = 3
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            adapter = object : BaseAdapter() {
                override fun getCount(): Int = choices.size
                override fun getItem(position: Int): Any = choices[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val cell = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
                    }
                    val iv = ImageView(activity).apply {
                        setImageBitmap(choices[position].thumb)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0x22000000)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, (96 * d).toInt())
                    }
                    val tv = TextView(activity).apply {
                        text = choices[position].label
                        textSize = 10f
                        maxLines = 2
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#3A3020"))
                    }
                    cell.addView(iv); cell.addView(tv)
                    return cell
                }
            }
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(grid)
            .setNegativeButton("取消", null)
            .create()
        grid.setOnItemClickListener { _, _, position, _ ->
            choices[position].onPick()
            dlg.dismiss()
        }
        dlg.show()
    }

    // ═══════════════════════ 主面板 ═══════════════════════

    /**
     * 「全局底色」主面板：底色（纯色 / 图片）+ 楚河汉界时间颜色，都在这一屏里。
     *
     * @param onPickImage 点「选图片」时由宿主拉起 SAF（宿主才有 ActivityResult 注册权）
     * @param onPickZip   点「从 ZIP 选图」时同上
     * @param onChanged   任何一项落盘后回调，宿主据此重新 applyGlobalBackdrop()
     */
    fun showPanel(
        activity: Activity,
        onPickImage: () -> Unit,
        onPickZip: () -> Unit,
        onChanged: () -> Unit
    ) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = Color.parseColor("#8B5A2B")
        val ink = Color.parseColor("#3A3020")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        val scroll = ScrollView(activity).apply { addView(root) }

        lateinit var bgSwatch: View
        lateinit var bgText: TextView
        lateinit var timeSwatch: View
        lateinit var timeText: TextView
        lateinit var panelDialog: AlertDialog
        val weightViews = ArrayList<TextView>()

        fun refresh() {
            val imgFile = currentImageFile(activity)
            bgSwatch.background = if (imgFile != null) {
                loadBitmap(activity, imgFile)?.let { BitmapDrawable(activity.resources, it) }
                    ?: ColorDrawable(currentColor(activity))
            } else ColorDrawable(currentColor(activity))
            bgText.text = if (imgFile != null) "当前：自定义图片（${imgFile.name}）"
            else "当前：纯色 #" + String.format("%06X", 0xFFFFFF and currentColor(activity))

            val tc = riverTimeColor(activity)
            val wt = riverTimeWeight(activity)
            val wtName = listOf("细", "中", "粗")[wt]
            timeSwatch.background = ColorDrawable(if (tc == TIME_FOLLOW_LINE) 0xFFB9A88A.toInt() else tc)
            timeText.text = (if (tc == TIME_FOLLOW_LINE) "当前：跟随棋线色（推荐）"
            else "当前：自定义 #" + String.format("%06X", 0xFFFFFF and tc)) + " · 字粗：$wtName"
            // 三档粗细：选中的那档点亮，其余压暗，一眼看出当前用的是哪档
            weightViews.forEachIndexed { i, v ->
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (i == wt) accent else 0xFFB9A88A.toInt())
                }
            }
        }

        fun title(t: String, topGap: Int = 14) {
            root.addView(TextView(activity).apply {
                text = t
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(accent)
                setPadding(0, dp(topGap), 0, dp(4))
            })
        }

        fun pill(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(accent)
            }
            setOnClickListener { onClick() }
        }

        fun pillRow(vararg children: View) {
            root.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
                children.forEach { c ->
                    addView(c)
                    addView(View(activity), LinearLayout.LayoutParams(dp(8), 1))
                }
            })
        }

        fun infoRow(swatch: View, text: TextView, size: Int = 44) {
            swatch.layoutParams = LinearLayout.LayoutParams(dp(size), dp(30))
            root.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(swatch)
                addView(text.apply {
                    textSize = 12f
                    setTextColor(ink)
                    setPadding(dp(10), 0, 0, 0)
                })
            })
        }

        // ── 一、底色 ──
        title("【底色】", 2)
        bgSwatch = View(activity)
        bgText = TextView(activity)
        infoRow(bgSwatch, bgText)

        pillRow(
            pill("色谱调色") {
                showColorPicker(activity, "全局底色 · 色谱调色（点哪取哪）", currentColor(activity)) { argb ->
                    setCurrentColor(activity, argb)
                    refresh(); onChanged()
                }
            },
            // 拉起相册/文件选择器前先收起面板：回来时画面已换新，面板文字不会停在旧状态
            pill("选图片") { panelDialog.dismiss(); onPickImage() },
            pill("从 ZIP 选图") { panelDialog.dismiss(); onPickZip() }
        )
        pillRow(
            pill("还原默认纯色") {
                resetToDefault(activity)
                refresh(); onChanged()
            }
        )

        // ── 二、楚河汉界 · 时间颜色 ──
        title("【楚河汉界 · 时间颜色】")
        timeSwatch = View(activity)
        timeText = TextView(activity)
        infoRow(timeSwatch, timeText)

        pillRow(
            pill("色谱调色") {
                val cur = riverTimeColor(activity).let { if (it == TIME_FOLLOW_LINE) Color.parseColor("#5D4037") else it }
                showColorPicker(activity, "时间颜色 · 色谱调色（点哪取哪）", cur) { argb ->
                    setRiverTimeColor(activity, argb)
                    refresh(); onChanged()
                }
            },
            pill("跟随棋线色") {
                setRiverTimeColor(activity, TIME_FOLLOW_LINE)
                refresh(); onChanged()
            }
        )

        // 快捷预设：一排小色块，省得为常用色每次都进色谱
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            TIME_PRESETS.forEach { (name, hex) ->
                addView(View(activity).apply {
                    val c = Color.parseColor(hex)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(c)
                        setStroke(dp(1), 0x44000000)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).also {
                        it.rightMargin = dp(6)
                    }
                    contentDescription = name
                    setOnClickListener {
                        setRiverTimeColor(activity, c)
                        refresh(); onChanged()
                    }
                })
            }
        })

        // ── 三、时间字粗：细 / 中 / 粗 三档 ──
        title("【时间字粗】", 12)
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
            listOf("细", "中", "粗").forEachIndexed { i, name ->
                val tv = TextView(activity).apply {
                    text = name
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .also { it.rightMargin = dp(8) }
                    setOnClickListener {
                        setRiverTimeWeight(activity, i)
                        refresh(); onChanged()
                    }
                }
                weightViews.add(tv)
                addView(tv)
            }
        })

        root.addView(TextView(activity).apply {
            text = "时间「无辉」：与棋线同款——纯实心、不加任何浅色光晕，看得干净利落。字粗细/中/粗三档，选中即生效。"
            textSize = 11f
            setTextColor(0xFF7A6A55.toInt())
            setPadding(0, dp(8), 0, dp(2))
        })

        refresh()

        panelDialog = AlertDialog.Builder(activity)
            .setTitle("全局底色")
            .setView(scroll)
            .setPositiveButton("完成", null)
            .create()
        panelDialog.show()
    }

    // ─────────────────────────── 色谱调色板 ───────────────────────────

    /** 通用色谱调色：SV 二维色谱 + 色相滑条，点哪取哪。 */
    private fun showColorPicker(activity: Activity, title: String, initial: Int, onApplied: (Int) -> Unit) {
        val cur = initial
        val picker = SpectrumPickerView(activity).apply { setColor(cur) }

        val swatch = View(activity).apply {
            background = ColorDrawable(cur)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 46), dp(activity, 32))
        }
        val hexText = TextView(activity).apply {
            text = String.format("#%06X", 0xFFFFFF and cur)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.parseColor("#2A2418"))
            setPadding(dp(activity, 10), 0, 0, 0)
        }
        val previewRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 10), 0, 0)
            addView(swatch)
            addView(hexText)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 6))
            addView(picker, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 250)))
            addView(previewRow)
        }
        picker.onColorChanged = { argb ->
            swatch.background = ColorDrawable(argb)
            hexText.text = String.format("#%06X", 0xFFFFFF and argb)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(content)
            .setPositiveButton("应用") { _, _ -> onApplied(picker.color) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()
}

/**
 * 色谱调色板自定义视图：
 * 上半区为 饱和度(X) × 明度(Y) 二维色谱，下半区为色相(H) 滑条。
 * 手指在色谱上点哪一点，就取那一点的精确颜色。
 */
private class SpectrumPickerView(context: Context) : View(context) {

    var color: Int = 0xFFFFF3C4.toInt()
        private set
    var onColorChanged: ((Int) -> Unit)? = null

    private val pad = dp(8).toFloat()
    private val hueBarH = dp(30).toFloat()
    private val gap = dp(10).toFloat()
    private var hue = 45f
    private var sat = 0.35f
    private var value = 1f

    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = Color.WHITE
    }
    private val indicatorShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(5).toFloat(); color = 0x66000000.toInt()
    }
    private val hueColors = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
    )

    init {
        isClickable = true
    }

    fun setColor(argb: Int) {
        color = argb
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        invalidate()
    }

    private fun svWidth(): Float = (width - pad * 2).coerceAtLeast(1f)
    private fun svHeight(): Float = (height - pad * 2 - gap - hueBarH).coerceAtLeast(1f)

    override fun onDraw(canvas: Canvas) {
        val w = svWidth()
        val h = svHeight()
        val base = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        // 饱和度渐变：左白 → 右纯色相
        svPaint.shader = LinearGradient(pad, 0f, pad + w, 0f, Color.WHITE, base, Shader.TileMode.CLAMP)
        canvas.drawRect(pad, pad, pad + w, pad + h, svPaint)
        // 明度渐变：上不透明 → 下全黑
        svPaint.shader = LinearGradient(0f, pad, 0f, pad + h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(pad, pad, pad + w, pad + h, svPaint)
        // 色相滑条
        val hueTop = pad + h + gap
        huePaint.shader = LinearGradient(pad, 0f, pad + w, 0f, hueColors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(pad, hueTop, pad + w, hueTop + hueBarH, huePaint)
        // 指示器
        val sx = pad + sat * w
        val sy = pad + (1f - value) * h
        canvas.drawCircle(sx, sy, dp(9).toFloat(), indicatorShadow)
        canvas.drawCircle(sx, sy, dp(9).toFloat(), indicatorPaint)
        val hx = pad + (hue / 360f) * w
        canvas.drawRect(hx - dp(4).toFloat(), hueTop - dp(2).toFloat(), hx + dp(4).toFloat(), hueTop + hueBarH + dp(2).toFloat(), indicatorShadow)
        canvas.drawRect(hx - dp(3).toFloat(), hueTop - dp(1).toFloat(), hx + dp(3).toFloat(), hueTop + hueBarH + dp(1).toFloat(), indicatorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = svWidth()
        val h = svHeight()
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (y <= pad + h) {
                    sat = ((x - pad) / w).coerceIn(0f, 1f)
                    value = (1f - (y - pad) / h).coerceIn(0f, 1f)
                } else {
                    hue = (((x - pad) / w).coerceIn(0f, 1f) * 360f).coerceIn(0f, 359f)
                }
                color = Color.HSVToColor(floatArrayOf(hue, sat, value))
                onColorChanged?.invoke(color)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
