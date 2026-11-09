package com.pikafish.chess.ui

import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.pikafish.chess.FixScale
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import java.io.File

/**
 * ★★★「个性化」皮肤包 / 桌布管理界面 ★★★
 *
 *   - 「导入桌布」：直接选单张或多张纯图片（PNG/JPG…），自动洗净棋线 / 九宫斜线 /
 *     炮位兵位十字标 / 楚河汉界 / 外圈刻度 / 水印，只留底色，独立成包；
 *   - 「导入 ZIP / 选 APK 提取」：整包导入，自动拆开红/黑棋子与背景，背景同样深度洗净；
 *   - 允许「杂交选择」：红方棋子来自 A 包、黑方棋子来自 B 包、桌布来自 C 包（桌布可不选）；
 *   - 预览样本：红方棋子、黑方棋子、洗净桌布各给一张实际缩略图；
 *   - 确认校验：红、黑必须同时选定或同时留空；桌布可单选、可留空（用内置盘面）。
 */
class PersonalizeActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    private lateinit var packContainer: LinearLayout
    private lateinit var summaryText: TextView
    private lateinit var previewBox: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var bottomRow: LinearLayout
    private var packs: List<SkinManager.Pack> = listOf()

    private val zipPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (!uris.isNullOrEmpty()) importZips(uris, "皮肤包")
        }
    private val apkPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (!uris.isNullOrEmpty()) importZips(uris, "APK")
        }

    /**
     * 「仅导入桌布」：直接选 1 张或多张纯图片（PNG/JPG/WEBP…）。
     * 不要求含棋子 —— 桌布可单独换，也可与已有的红/黑棋子跨包搭配。
     */
    private val clothPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (!uris.isNullOrEmpty()) importImages(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：让内容延伸进系统栏，再手动用 insets 把内容顶出状态栏/手势条，
        // 这样底部的「返回/确认」按钮绝不会贴到手机底部手势导航键上。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val view = buildView()
        setContentView(view)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(14), sb.top + dp(14), dp(14), dp(10))
            bottomRow.setPadding(bottomRow.paddingLeft, bottomRow.paddingTop,
                bottomRow.paddingRight, sb.bottom + dp(12))
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun accent() = Color.parseColor("#8B5A2B")
    private fun darker() = Color.parseColor("#5A4322")

    private fun buildView(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4EDE2"))
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }

        root.addView(TextView(this).apply {
            text = "个性化 · 外置皮肤包"
            textSize = 20f
            setTextColor(Color.parseColor("#4A2E12"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "导入桌布：可单张或多选纯图片，自动洗净棋线/九宫斜线/炮位兵位十字标/楚河汉界/外圈刻度/水印，只留底色。\n" +
                "导入 ZIP：可一次多选（最多 $MAX_ZIP_BATCH 个），逐个提取、逐个汇报成败。"
            textSize = 12f
            setTextColor(Color.parseColor("#6A4A28"))
            setPadding(0, dp(2), 0, dp(10))
        })

        summaryText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#4A2E12"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(summaryText)

        // 两枚入口：ZIP 皮肤包 + 直接从他人 APK 里提取
        val importRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        importRow.addView(Button(this).apply {
            text = "＋ 导入 ZIP"
            setTextColor(Color.WHITE)
            textSize = 13f
            background = rounded(accent())
            setOnClickListener {
                zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        importRow.addView(Button(this).apply {
            text = "＋ 选 APK 提取"
            setTextColor(Color.WHITE)
            textSize = 13f
            background = rounded(accent())
            setOnClickListener {
                apkPicker.launch(arrayOf(
                    "application/vnd.android.package-archive",
                    "application/zip", "application/octet-stream", "*/*"))
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        root.addView(importRow)

        // ★「仅导入桌布」+「清空」：左右各半——左边换桌布（单张/多选图片），右边一键清空全部素材
        val clothRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        clothRow.addView(Button(this).apply {
            text = "＋ 导入桌布"
            setTextColor(Color.WHITE)
            textSize = 13f
            background = rounded(accent())
            setOnClickListener {
                clothPicker.launch(arrayOf("image/*"))
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        clothRow.addView(Button(this).apply {
            text = "清空"
            setTextColor(Color.WHITE)
            textSize = 13f
            background = rounded(darker())
            setOnClickListener { confirmClearAll() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        root.addView(clothRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })

        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(previewBox)
        })

        root.addView(TextView(this).apply {
            text = "已导入的皮肤包（可跨包混选）"
            textSize = 14f
            setTextColor(Color.parseColor("#6A4A28"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        packContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        scroll.addView(packContainer)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun evenBtn(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5); marginStart = dp(5) }
        bottomRow.addView(Button(this).apply {
            text = "返回"
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#8A847C"))
            setOnClickListener { finish() }
        }, evenBtn())
        bottomRow.addView(Button(this).apply {
            text = "恢复默认"
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#6A6A5C"))
            setOnClickListener { resetToDefault() }
        }, evenBtn())
        bottomRow.addView(Button(this).apply {
            text = "确认应用"
            setTextColor(Color.WHITE)
            background = rounded(accent())
            setOnClickListener { confirmApply() }
        }, evenBtn())
        root.addView(bottomRow)

        return root
    }

    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color)
    }

    private fun render() {
        packs = SkinManager.listPacks(this)
        val (r, b, bg) = SkinManager.currentSelection(this)
        summaryText.text = "当前方案：红方=${nameOf(r)}  黑方=${nameOf(b)}  桌布=${nameOf(bg)}"
        renderPreview(r, b, bg)
        packContainer.removeAllViews()
        if (packs.isEmpty()) {
            packContainer.addView(TextView(this).apply {
                text = "  尚未导入任何素材。可点上方「导入桌布」只换桌布，或「导入 ZIP / 选 APK 提取」整包导入。"
                textSize = 13f
                setTextColor(Color.parseColor("#8A847C"))
                setPadding(0, dp(6), 0, dp(6))
            })
            return
        }
        packs.forEach { p -> packContainer.addView(packCard(p, r, b, bg)) }
    }

    private fun nameOf(id: String): String {
        if (id.isBlank()) return "内置(未选)"
        return packs.firstOrNull { it.id == id }?.name ?: id
    }

    private fun renderPreview(r: String, b: String, bg: String) {
        previewBox.removeAllViews()
        previewBox.addView(previewSample("红方", pieceBmp(r, PieceColor.RED)))
        previewBox.addView(previewSample("黑方", pieceBmp(b, PieceColor.BLACK)))
        previewBox.addView(bgSample(bg))
    }

    private fun previewSample(label: String, bmp: Bitmap?): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        val iv = ImageView(this)
        if (bmp != null) iv.setImageBitmap(bmp) else iv.setBackgroundColor(Color.parseColor("#DDD5C8"))
        box.addView(iv, LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(4) })
        box.addView(TextView(this).apply {
            text = "● $label"
            textSize = 12f
            setTextColor(if (label == "红方") Color.parseColor("#B3342A") else Color.parseColor("#2A2A2A"))
        })
        return box
    }

    private fun bgSample(id: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        val iv = ImageView(this)
        val bmp = if (id.isBlank()) null else decodeThumb(File(SkinManager.packDir(this, id), "bg.png"), 224, 176)
        if (bmp != null) iv.setImageBitmap(bmp) else iv.setBackgroundColor(Color.parseColor("#DDD5C8"))
        box.addView(iv, LinearLayout.LayoutParams(dp(112), dp(88)).apply { bottomMargin = dp(4) })
        box.addView(TextView(this).apply { text = "桌布${if (id.isBlank()) "·内置" else "·洗净"}"; textSize = 12f; setTextColor(Color.parseColor("#5A4322")) })
        return box
    }

    private fun pieceBmp(packId: String, color: PieceColor): Bitmap? {
        if (packId.isBlank()) return null
        val sub = if (color == PieceColor.RED) "red" else "black"
        return decodeThumb(File(File(SkinManager.packDir(this, packId), sub), "p${PieceType.KING.fenChar}.png"), 176)
    }

    private fun decodeThumb(f: File, w: Int, h: Int = w): Bitmap? {
        if (!f.exists()) return null
        return try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opt)
            var s = 1
            while (opt.outWidth / (s * 2) >= w && opt.outHeight / (s * 2) >= h) s *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = s }
            val src = BitmapFactory.decodeFile(f.absolutePath, o) ?: return null
            if (src.width == w && src.height == h) src
            else Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) src.recycle() }
        } catch (_: Exception) { null }
    }

    private fun packCard(p: SkinManager.Pack, selR: String, selB: String, selBg: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.parseColor("#FBF6EC"))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
        card.layoutParams = lp

        card.addView(TextView(this).apply {
            text = "包「${p.name}」 · ${if (p.hasRed) "有红方" else "无红方"} / ${if (p.hasBlack) "有黑方" else "无黑方"} / ${if (p.hasBg) "有桌布" else "无桌布"}"
            textSize = 13f
            setTextColor(Color.parseColor("#4A2E12"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        if (p.source.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = "提取来源：${p.source}"
                textSize = 11f
                setTextColor(Color.parseColor("#8A6A44"))
                setPadding(0, dp(2), 0, dp(4))
            })
        }

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (p.hasRed)
            chipRow.addView(chip(if (selR == p.id) "✔ 红方" else "选为红方", selR == p.id) {
                SkinManager.setRed(this, p.id); render(); toast("已选「${p.name}」为红方，继续为黑方选一套（可在别的包选）")
            })
        if (p.hasBlack)
            chipRow.addView(chip(if (selB == p.id) "✔ 黑方" else "选为黑方", selB == p.id) {
                SkinManager.setBlack(this, p.id); render(); toast("已选「${p.name}」为黑方")
            })
        if (p.hasBg)
            chipRow.addView(chip(if (selBg == p.id) "✔ 桌布" else "用其桌布", selBg == p.id) {
                SkinManager.setBg(this, if (selBg == p.id) "" else p.id); render()
            })
        chipRow.addView(chip("删除", false) { removePack(p) })
        card.addView(chipRow)
        return card
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): Button {
        val lp = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) }
        return Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded(if (selected) accent() else darker())
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun removePack(p: SkinManager.Pack) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除皮肤包")
            .setMessage("确定删除包「${p.name}」全部素材？")
            .setPositiveButton("删除") { _: DialogInterface?, _: Int -> SkinManager.deletePack(this, p.id); render() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetToDefault() {
        SkinManager.setRed(this, "")
        SkinManager.setBlack(this, "")
        SkinManager.setBg(this, "")
        render()
        toast("已恢复默认皮肤方案（内置 3D 立体皮肤）；已导入的皮肤包仍在，可随时重新勾选")
    }

    private fun confirmApply() {
        val (r, b, _) = SkinManager.currentSelection(this)
        // 唯一约束：红、黑要么都选、要么都不选（都留空＝只用内置棋子 + 外置桌布）。
        if (r.isBlank() != b.isBlank()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("还差一方的棋子")
                .setMessage("红方与黑方棋子必须同时选定（可来自不同皮肤包），不能只选一方。\n只想换桌布、棋子仍用内置的话，请把红、黑都留空再点「确认应用」。\n当前：红方${if (r.isBlank()) "未选" else "已选"} · 黑方${if (b.isBlank()) "未选" else "已选"}。")
                .setPositiveButton("好", null)
                .show()
            return
        }
        toast("已保存个性化方案，返回主界面即自动套用")
        finish()
    }

    private var importDialog: android.app.ProgressDialog? = null
    /** ★ 一批最多导入几个 ZIP/APK（逐个排队处理，封顶防内存顶爆） */
    private val MAX_ZIP_BATCH = 10
    private var batchRunning = false
    /** ★ 用户点了「取消」：置位后后台线程在下一个检查点收工 */
    @Volatile private var importCancelled = false
    /** ★ 批次代号：旧批次迟到的收尾回调不会去动新批次的界面状态 */
    private var importGeneration = 0

    /** 把一段界面更新安全地丢回主线程；Activity 已销毁时静默忽略，绝不把异常抛回后台线程。 */
    private fun postUi(block: () -> Unit) {
        try {
            runOnUiThread { try { block() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }

    /** 「仅导入桌布」：单张 / 多选图片，逐张净化后独立成包，可与已有红/黑棋子跨包搭配。 */
    private fun importImages(uris: List<Uri>) {
        importDialog = android.app.ProgressDialog(this).apply {
            setTitle("正在净化桌布")
            setMessage("识别棋盘并洗净棋线/楚河汉界/刻度…")
            setCancelable(false)
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            show()
        }
        SkinManager.importImages(this, uris, progress = { msg -> importDialog?.setMessage(msg) },
            done = { packs, msg ->
                importDialog?.dismiss()
                importDialog = null
                if (packs.isNotEmpty()) {
                    // 新桌布自动挂上；红/黑棋子的既有选择保持不变
                    SkinManager.setBg(this, packs.first().id)
                    render()
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            })
    }

    /**
     * ★ 批量导入 ZIP / APK：一次可多选（封顶 [MAX_ZIP_BATCH] 个），【逐个排队处理】——
     *   一个好了再下一个，绝不并发。单张、多张都走同一条路，不会因为选了几张就崩。
     *
     * ★ 作风对齐「导入桌布」（那条路一直是顺顺溜溜的）：
     *   凡是一条道跑到黑、可能阻塞的活儿，全甩给后台线程 ——
     *   读文件、查文件名（contentResolver.query）、解压、解码、净化，主线程一步都不碰，
     *   只负责刷进度和弹结论。以前把「查文件名」和「整包解析」搅在主线程/回调链上，
     *   一遇到慢的 SAF 流或超大图，界面就被一个焊死的弹窗盖住，点哪都没反应。
     *
     * ★ 三条硬保证：
     *   1) 大图先探尺寸再采样解码，内存峰值可控，不会因为一张大图把整机拖卡；
     *   2) 弹窗可取消，收尾统一放在 finally —— 成功 / 失败 / 异常 / 取消，
     *      弹窗必关、[batchRunning] 必复位，界面绝不焊死；
     *   3) 临时文件在 SkinManager 里一律 finally 删净，不在 cacheDir 攒垃圾。
     */
    private fun importZips(uris: List<Uri>, source: String) {
        if (batchRunning) { toast("上一批还在导入，请稍候…"); return }
        val list = uris.take(MAX_ZIP_BATCH)
        if (list.isEmpty()) return
        val extra = if (uris.size > list.size) "（你选了 ${uris.size} 个，一次最多 $MAX_ZIP_BATCH 个，已取前 $MAX_ZIP_BATCH 个）" else ""

        batchRunning = true
        importCancelled = false
        val gen = ++importGeneration

        val dlg = android.app.ProgressDialog(this).apply {
            setTitle("正在智能提取 $source（共 ${list.size} 个）")
            setMessage("准备中…")
            setCancelable(true)
            setProgressStyle(if (list.size > 1) android.app.ProgressDialog.STYLE_HORIZONTAL
                             else android.app.ProgressDialog.STYLE_SPINNER)
            if (list.size > 1) { max = list.size; progress = 0 }
            setOnCancelListener {
                importCancelled = true
                importDialog = null
                toast("已取消，正在收尾…")
            }
            show()
        }
        importDialog = dlg

        val worker = Thread {
            val report = StringBuilder()
            val newPacks = ArrayList<SkinManager.Pack>()
            var okCount = 0
            var failCount = 0
            var cancelled = false
            try {
                list.forEachIndexed { i, uri ->
                    if (importCancelled) { cancelled = true; return@forEachIndexed }
                    // 文件名查询也在后台做：主线程绝不碰可能阻塞的 contentResolver 查询
                    val name = safeName(uri)
                    if (importCancelled) { cancelled = true; return@forEachIndexed }
                    postUi {
                        dlg.progress = i
                        dlg.setMessage("正在处理第 ${i + 1}/${list.size} 个：$name")
                    }
                    val res = SkinManager.importZipBlocking(this, uri, name, isApk = source == "APK",
                        progress = { msg ->
                            postUi {
                                if (!importCancelled)
                                    dlg.setMessage("第 ${i + 1}/${list.size} 个：$msg")
                            }
                        },
                        cancel = { importCancelled })
                    if (res.first.isNotEmpty()) {
                        okCount++
                        newPacks.addAll(res.first)
                        report.append("第 ${i + 1} 个 ✔ 成功 · $name\n      ${res.second}\n")
                    } else {
                        failCount++
                        report.append("第 ${i + 1} 个 ✘ 失败 · $name\n      ${res.second}\n")
                    }
                }
            } catch (t: Throwable) {
                report.append("处理中断：${t.message ?: "未知错误"}\n")
            } finally {
                if (importCancelled) cancelled = true
                postUi {
                    if (gen != importGeneration) return@postUi   // 旧批次迟到的收尾，忽略
                    importDialog?.dismiss(); importDialog = null
                    batchRunning = false
                    if (newPacks.isNotEmpty()) {
                        // 自动勾选：红/黑各挑第一套（多套时其余仍可手动改选）
                        var setR = SkinManager.currentSelection(this).first.isNotBlank()
                        var setB = SkinManager.currentSelection(this).second.isNotBlank()
                        newPacks.forEach { p ->
                            if (!setR && p.hasRed) { SkinManager.setRed(this, p.id); setR = true }
                            if (!setB && p.hasBlack) { SkinManager.setBlack(this, p.id); setB = true }
                        }
                        render()
                    }
                    val head = if (cancelled) "已取消（此前成功 $okCount 个，失败 $failCount 个）"
                               else "共 ${list.size} 个：成功 $okCount，失败 $failCount$extra"
                    if (cancelled) { toast(head); return@postUi }
                    try {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("批量导入完成")
                            .setMessage(head + "\n\n" + report.toString().trimEnd())
                            .setPositiveButton("好", null)
                            .show()
                    } catch (_: Exception) {
                        Toast.makeText(this, head, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    /**
     * 取一份给人看的文件名（去掉 SAF 里的 uuid 前缀），失败就退回路径末段。
     * ★ 内部会查 contentResolver，务必在后台线程调用。
     */
    private fun safeName(uri: Uri): String = try {
        SkinManager.displayName(this, uri).take(30)
    } catch (_: Exception) {
        (uri.lastPathSegment ?: "所选文件").take(30)
    }

    /** 「清空」：把已导入的皮肤包全部删掉（红/黑棋子 + 桌布一次清光），选择一并复位。 */
    private fun confirmClearAll() {
        if (SkinManager.listPacks(this).isEmpty()) { toast("本来就没有导入任何素材"); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("清空全部素材")
            .setMessage("将删除【全部已导入的皮肤包】（红方 / 黑方棋子 + 桌布），不可恢复。\n确定要一次性清空吗？")
            .setPositiveButton("清空") { _: DialogInterface?, _: Int ->
                var n = 0
                SkinManager.listPacks(this).forEach {
                    try { SkinManager.deletePack(this, it.id); n++ } catch (_: Exception) {}
                }
                render()
                toast("已清空全部皮肤包（共 $n 个），彻底干净了")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}