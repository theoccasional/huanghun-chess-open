package com.pikafish.chess.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.pikafish.chess.BoardRecognizer
import com.pikafish.chess.FixScale
import com.pikafish.chess.MainActivity
import com.pikafish.chess.OnnxBoardRecognizer
import com.pikafish.chess.engine.JniEngine
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.Piece
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position
import com.pikafish.chess.model.RepetitionRule
import com.pikafish.chess.reader.PdfOutlineParser
import com.pikafish.chess.reader.ReaderShelf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream

/**
 * ★ 外置读物 · 全屏 PDF 阅读器（独立 Activity）
 *
 * 与「清新人生」阅读器同构：独立页、独立返回键 —— 退出只退到主界面，绝不一退到底把下棋也退了。
 *
 * 阅读：
 *  - 两种模式：↕ 上下滚动 / ↔ 左右翻页（真书手感，一页一页稳稳走）；
 *  - 滚屏轴线锁定：竖着滑就纯竖着走，绝不左右晃悠；纸张没超出屏宽时横向位移恒为 0；
 *  - ⚙ 裁边：把纸张白边裁掉、内容自动铺满屏宽，字变大又不越界（比放大更稳），可随时重设/重置；
 *  - A−/A＋ 缩放、对比度增强（扫描件去灰）、目录跳转、进度记忆、★标记，均按书长期保存。
 *
 * 框选（✂ 框选 → 划对角 → 抬手）：
 *  - 📝 反选·识别文字：把框住的区域（通常是棋盘）挖掉，把框外文字用 OCR 认成规整汉字；
 *  - ♟ 正选·摆子识别：把框住的棋盘直接送去主界面的摆子识别，不用再拍照/上传；
 *  - 🖍 划线标记：把框住的一段画成高亮，往后翻回来还在；
 *  - ✕ 取消。
 */
class PdfReaderActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "reader_pdf_path"        // 读物实际文件绝对路径
        const val EXTRA_TITLE = "reader_pdf_title"      // 显示名
        const val EXTRA_FILE_NAME = "reader_pdf_file"   // 书架内文件名（进度/标记的键）
    }

    private lateinit var pageView: PdfPageView
    private lateinit var tvTitle: TextView
    private lateinit var tvPageInfo: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnEnhance: Button
    private lateinit var btnMark: Button
    private lateinit var btnSelect: Button
    private lateinit var btnMode: Button
    private lateinit var btnOut: Button
    private lateinit var btnIn: Button
    private lateinit var loading: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var fileName: String = ""
    private var title: String = ""
    private var outline: List<PdfOutlineParser.Item> = emptyList()
    private var marks: MutableSet<Int> = HashSet()
    private var pageTotal = 0

    // ★ 裁边 + 模式（按书记忆）
    private var cropL = 0f
    private var cropT = 0f
    private var cropR = 0f
    private var cropB = 0f
    private var mode = PdfPageViewMode.SCROLL

    // ★ 划线高亮
    private val highlights = HashMap<Int, MutableList<FloatArray>>()

    private var recognizer: TextRecognizer? = null
    private var busy: AlertDialog? = null

    private val saveProgress = Runnable { persistProgress() }

    // ★ 浮层宿主：识别文字浮窗 / 书页 AI 棋盘都挂在这里；可用手指拖着走，且永远留在屏幕内
    private lateinit var overlayHost: FrameLayout

    // ── 识别文字浮窗（可拖动 + 可调行宽）──
    private var ocrCard: FrameLayout? = null
    private var ocrWidthFrac = 0.62f

    // ── 书页 AI 棋盘（就地小棋盘：看着书边摆边学，不进主界面）──
    private var aiCard: FrameLayout? = null
    private var aiBoardView: MiniGameBoardView? = null
    private var aiStatus: TextView? = null
    private var aiBoard: Board? = null
    private var aiRedHuman = true
    private var aiBlackHuman = true          // 默认红方人、黑方电脑
    // ★ 引擎：直接接主界面同款「皮卡鱼」引擎（JniEngine），但走轻量档、独立实例，
    //   与主界面的引擎进程完全隔离 —— 主界面的东西秋毫不犯。
    private var aiEngine: JniEngine? = null
    @Volatile private var aiEngineReady = false
    private var aiEngineStarting = false
    @Volatile private var aiThinking = false
    private var aiSel: Pair<Int, Int>? = null
    private var aiLastFrom: Pair<Int, Int>? = null
    private var aiLastTo: Pair<Int, Int>? = null
    // ★ 三档大小：小 / 中 / 大（大号按「约 1/3 屏面积」自适应，任何手机都点得准）
    private var aiSizeGear = 0
    private var aiGameOver = false
    // 重来用：记住这一盘从哪个局面开的、双方怎么设定的
    private var aiInitialPieces: List<BoardRecognizer.RecognizedPiece> = emptyList()
    private var aiRedFirst = true

    // ★ 收成小胶囊：不挡书、可随手拖、点一下还原；展开时才允许摆棋（此时冻住翻页）
    private var aiCapsule: TextView? = null
    private var aiCollapsed = false

    // ★ AI 出招设置（与主界面同一套语义）：步时（秒）/ 层数（深度）二选一，互斥
    private var aiSecCfg = 1          // 步时模式：每步思考秒数
    private var aiDepthCfg = 8        // 层数模式：搜索到第几层才出招
    private var aiUseDepth = false    // true=按层数，false=按步时

    // ★ 棋规：接主界面同款「亚洲象棋联合会棋例」（长将/长捉/循环判负判和）
    private val aiRepeat = RepetitionRule()
    private val aiFenHist = ArrayList<String>()
    private var aiWarnedViolator: PieceColor? = null

    private var onnxRecognizer: OnnxBoardRecognizer? = null
    private val boardRecognizer: BoardRecognizer by lazy { BoardRecognizer() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        } catch (_: Exception) {}
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val path = intent?.getStringExtra(EXTRA_PATH)
        fileName = intent?.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        if (path.isNullOrBlank() || !File(path).exists()) {
            Toast.makeText(this, "读物不存在或已被删除", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (title.isBlank()) title = File(path).name.substringBeforeLast('.')
        if (fileName.isBlank()) fileName = File(path).name

        marks = ReaderShelf.marks(this, fileName)
        ReaderShelf.highlights(this, fileName).forEach { (k, v) -> highlights[k] = v }
        ReaderShelf.view(this, fileName)?.let {
            cropL = it.first[0]; cropT = it.first[1]; cropR = it.first[2]; cropB = it.first[3]
            mode = it.second
        }

        pageView = PdfPageView(this)
        pageView.markedPages = marks
        pageView.highlights = toViewHighlights()
        pageView.listener = object : PdfPageView.Listener {
            override fun onPageChanged(page: Int, total: Int) {
                pageTotal = total
                tvPageInfo.text = "第 ${page + 1} 页 / 共 $total 页"
                refreshMarkButton()
                // 翻页即记忆（稍作延迟合并频繁滚动）
                ui.removeCallbacks(saveProgress)
                ui.postDelayed(saveProgress, 600)
            }
        }
        pageView.onSelection = { rect -> onRegionSelected(rect) }

        buildUi()
        // 先把版面（裁边/模式）铺好，再开书
        pageView.setCrop(cropL, cropT, cropR, cropB)
        pageView.setMode(mode)
        updateModeButton()
        loadPdf(File(path))
    }

    // ────────────────────────── 界面 ──────────────────────────

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF2E2A26.toInt())
        }

        // 顶栏：退出 / 标题 / 页码
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF3A342C.toInt())
            setPadding(dp(6), dp(4), dp(8), dp(4))
        }
        val btnExit = Button(this).apply {
            text = "✕ 退出"
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { exitReader() }
        }
        tvTitle = TextView(this).apply {
            text = title
            setTextColor(0xFFF2E4C8.toInt())
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), 0)
        }
        tvPageInfo = TextView(this).apply {
            text = "…"
            setTextColor(0xFFD8C79C.toInt())
            textSize = 12f
            gravity = Gravity.END
        }
        topBar.addView(btnExit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        topBar.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(tvPageInfo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 框选提示条（仅框选模式显示）
        tvHint = TextView(this).apply {
            text = "✂ 框选：对着纸面划个对角，抬手出菜单（反选文字 / 正选摆子 / 划线 / 取消）"
            setTextColor(0xFF2E2A26.toInt())
            textSize = 12f
            setBackgroundColor(0xFFFFD54A.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }

        // 阅读区
        val stage = FrameLayout(this)
        stage.addView(pageView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loading = TextView(this).apply {
            text = "正在打开读物…"
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
            gravity = Gravity.CENTER
        }
        stage.addView(loading, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 底栏：两排，每排 4 个，紧凑不挤
        val barWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF3A342C.toInt())
            setPadding(dp(3), dp(4), dp(3), dp(5))
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

        addBar(row1, barButton("📖 目录") { showTocDialog() })
        btnMark = barButton("☆ 标记") { toggleMark() }
        addBar(row1, btnMark)
        btnSelect = barButton("✂ 框选") { toggleSelectMode() }
        addBar(row1, btnSelect)
        addBar(row1, barButton("⚙ 裁边") { showCropDialog() })

        btnOut = barButton("A−") { onZoomOut() }
        btnIn = barButton("A＋") { onZoomIn() }
        addBar(row2, btnOut)
        addBar(row2, btnIn)
        btnMode = barButton("↔ 翻页") { toggleMode() }
        addBar(row2, btnMode)
        btnEnhance = barButton("增强 关") { toggleEnhance() }
        addBar(row2, btnEnhance)

        barWrap.addView(row1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        barWrap.addView(row2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tvHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(stage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(barWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ★ 最外层再套一层 FrameLayout 作「浮层宿主」：识别文字浮窗 / 书页 AI 棋盘盖在整页之上，
        //   可拖动、可关闭，且拖动全程被钳在屏幕内（绝不跑到屏幕外头去）。
        overlayHost = FrameLayout(this)
        overlayHost.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(overlayHost)
    }

    private fun addBar(row: LinearLayout, b: Button) {
        row.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(2); marginEnd = dp(2)
        })
    }

    private fun barButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ────────────────────────── 打开 ──────────────────────────

    private fun loadPdf(file: File) {
        Thread {
            val ok = pageView.load(file)
            val toc = try { PdfOutlineParser.parse(file) } catch (_: Exception) { emptyList() }
            ui.post {
                if (isFinishing) return@post
                loading.visibility = View.GONE
                if (!ok) {
                    Toast.makeText(this, "这本读物打不开（可能已加密或损坏）", Toast.LENGTH_LONG).show()
                    finish()
                    return@post
                }
                outline = toc
                pageTotal = pageView.pageCount
                tvPageInfo.text = "共 $pageTotal 页"
                // 恢复上次阅读位置
                val saved = ReaderShelf.progress(this, fileName)
                if (saved != null && saved.first > 0f) {
                    pageView.setRatio(saved.first)
                    Toast.makeText(this, "已回到上次读到的位置（约 ${(saved.first * 100).toInt()}%）", Toast.LENGTH_SHORT).show()
                }
                refreshMarkButton()
            }
        }.apply { isDaemon = true }.start()
    }

    // ────────────────────────── 标记 / 增强 / 缩放 / 模式 ──────────────────────────

    private fun toggleMark() {
        val p = pageView.currentPage()
        if (marks.contains(p)) marks.remove(p) else marks.add(p)
        pageView.markedPages = HashSet(marks)
        ReaderShelf.saveMarks(this, fileName, marks)
        refreshMarkButton()
        Toast.makeText(this, if (marks.contains(p)) "已标记第 ${p + 1} 页" else "已取消第 ${p + 1} 页的标记", Toast.LENGTH_SHORT).show()
    }

    private fun refreshMarkButton() {
        if (!::btnMark.isInitialized) return
        val marked = marks.contains(pageView.currentPage())
        btnMark.text = if (marked) "★ 已标记" else "☆ 标记"
    }

    private fun toggleEnhance() {
        val on = !pageView.enhance
        pageView.enhance = on
        btnEnhance.text = if (on) "增强 开" else "增强 关"
        Toast.makeText(this, if (on) "已开启对比度增强（扫描件更清楚）" else "已恢复原图色彩", Toast.LENGTH_SHORT).show()
    }

    private fun onZoomIn() {
        if (!pageView.canZoom()) {
            Toast.makeText(this, "翻页模式下用『⚙ 裁边』放大文字，或切回『↕ 滚动』再缩放", Toast.LENGTH_SHORT).show()
            return
        }
        pageView.zoomIn()
    }

    private fun onZoomOut() {
        if (!pageView.canZoom()) {
            Toast.makeText(this, "翻页模式下用『⚙ 裁边』放大文字，或切回『↕ 滚动』再缩放", Toast.LENGTH_SHORT).show()
            return
        }
        pageView.zoomOut()
    }

    private fun toggleMode() {
        mode = if (pageView.mode() == PdfPageViewMode.SCROLL) PdfPageViewMode.FLIP else PdfPageViewMode.SCROLL
        pageView.setMode(mode)
        updateModeButton()
        persistView()
        Toast.makeText(
            this,
            if (mode == PdfPageViewMode.FLIP) "已切到 ↔ 左右翻页（像翻真书一样）" else "已切到 ↕ 上下滚动",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateModeButton() {
        if (!::btnMode.isInitialized) return
        btnMode.text = if (pageView.mode() == PdfPageViewMode.FLIP) "↕ 滚动" else "↔ 翻页"
    }

    // ────────────────────────── 裁边 ──────────────────────────

    private fun showCropDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(4))
        }
        col.addView(TextView(this).apply {
            text = "裁掉纸张四周白边，内容会自动铺满屏宽 —— 字变大，又不会左右越界。\n裁多了随时可「重置」松绑重来。"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(8))
        })

        val pct = intArrayOf(
            (cropL * 100).toInt(), (cropT * 100).toInt(),
            (cropR * 100).toInt(), (cropB * 100).toInt()
        )
        val labels = arrayOf("左边", "上边", "右边", "下边")
        val bars = ArrayList<SeekBar>()

        fun apply() {
            pageView.setCrop(pct[0] / 100f, pct[1] / 100f, pct[2] / 100f, pct[3] / 100f)
        }

        for (i in 0..3) {
            val head = TextView(this).apply {
                text = "${labels[i]}：${pct[i]}%"
                textSize = 13f
                setTextColor(0xFF333333.toInt())
                setPadding(0, dp(4), 0, 0)
            }
            val sb = SeekBar(this).apply {
                max = 35
                progress = pct[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        pct[i] = progress
                        head.text = "${labels[i]}：$progress%"
                        apply()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            bars.add(sb)
            col.addView(head)
            col.addView(sb)
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("⚙ 裁边")
            .setView(col)
            .setNeutralButton("重置", null)
            .setNegativeButton("取消", null)
            .setPositiveButton("好了", null)
            .create()

        dlg.setOnShowListener {
            // 取消：回到打开弹窗前的裁边
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                pct[0] = (cropL * 100).toInt(); pct[1] = (cropT * 100).toInt()
                pct[2] = (cropR * 100).toInt(); pct[3] = (cropB * 100).toInt()
                pageView.setCrop(pct[0] / 100f, pct[1] / 100f, pct[2] / 100f, pct[3] / 100f)
                dlg.dismiss()
            }
            // 重置：全部归零
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                for (i in 0..3) { pct[i] = 0; bars[i].progress = 0 }
                pageView.setCrop(0f, 0f, 0f, 0f)
            }
            // 好了：落库
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                cropL = pct[0] / 100f; cropT = pct[1] / 100f
                cropR = pct[2] / 100f; cropB = pct[3] / 100f
                pageView.setCrop(cropL, cropT, cropR, cropB)
                persistView()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun persistView() {
        try {
            ReaderShelf.saveView(this, fileName, cropL, cropT, cropR, cropB, pageView.mode())
        } catch (_: Exception) {}
    }

    // ────────────────────────── 框选 ──────────────────────────

    private fun toggleSelectMode() {
        val on = !pageView.selectMode
        pageView.selectMode = on
        btnSelect.text = if (on) "✂ 框选中" else "✂ 框选"
        tvHint.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            pageView.clearSelection()
            // ★ 棋盘展开时纸张是冻住的，框不了；进框选就先把它收成胶囊，让开纸面
            if (aiCard != null && !aiCollapsed) {
                collapseAiBoard()
                toast("棋盘已收成小胶囊，框完再点它弹开")
            }
        }
    }

    private fun exitSelectMode() {
        pageView.selectMode = false
        pageView.clearSelection()
        if (::btnSelect.isInitialized) btnSelect.text = "✂ 框选"
        if (::tvHint.isInitialized) tvHint.visibility = View.GONE
    }

    private fun onRegionSelected(rect: RectF) {
        val mapped = pageView.mapSelection(rect)
        if (mapped == null) {
            Toast.makeText(this, "没框到纸面，请对着页面划个对角", Toast.LENGTH_SHORT).show()
            pageView.clearSelection()
            return
        }
        val page = mapped.first
        val rf = mapped.second
        val items = arrayOf(
            "📝 反选·识别文字", "♟ 正选·摆子识别",
            "🤖 书页AI棋盘（就地学练）", "🖍 划线标记", "✕ 取消"
        )
        AlertDialog.Builder(this)
            .setTitle("框选操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ocrOutsideBox(page, rf)
                    1 -> boardFromBox(page, rf)
                    2 -> pageAiBoardFromBox(page, rf)
                    3 -> addHighlight(page, rf)
                    else -> pageView.clearSelection()
                }
            }
            .setOnCancelListener { pageView.clearSelection() }
            .show()
    }

    /** 📝 反选：把框住的区域（通常是棋盘）挖白，再把框外文字认出来 */
    private fun ocrOutsideBox(page: Int, rectFull: RectF) {
        showBusy("正在识别文字…")
        pageView.capturePageForOcr(page, 1600, rectFull) { bmp ->
            pageView.clearSelection()
            if (bmp == null) { hideBusy(); toast("截取页面失败"); return@capturePageForOcr }
            runOcr(bmp)
        }
    }

    /** ♟ 正选：把框住的棋盘截下来，直接送去主界面的摆子识别 */
    private fun boardFromBox(page: Int, rectFull: RectF) {
        showBusy("正在截取棋盘…")
        pageView.captureRegionForBoard(page, rectFull, 1200) { bmp ->
            pageView.clearSelection()
            hideBusy()
            if (bmp == null) { toast("截取棋盘失败"); return@captureRegionForBoard }
            sendBoardToMainRecognizer(bmp)
        }
    }

    /** 🖍 划线：把框住的一段做成高亮，长期保存 */
    private fun addHighlight(page: Int, rectFull: RectF) {
        pageView.clearSelection()
        val list = highlights.getOrPut(page) { ArrayList() }
        list.add(floatArrayOf(rectFull.left, rectFull.top, rectFull.right, rectFull.bottom))
        pageView.highlights = toViewHighlights()
        try { ReaderShelf.saveHighlights(this, fileName, highlights) } catch (_: Exception) {}
        exitSelectMode()
        Toast.makeText(this, "已划线（第 ${page + 1} 页）", Toast.LENGTH_SHORT).show()
    }

    private fun toViewHighlights(): Map<Int, List<RectF>> {
        val out = HashMap<Int, List<RectF>>()
        for ((k, v) in highlights) {
            out[k] = v.map { RectF(it[0], it[1], it[2], it[3]) }
        }
        return out
    }

    private fun sendBoardToMainRecognizer(bmp: Bitmap) {
        try {
            val dir = File(cacheDir, "photos").apply { mkdirs() }
            val f = File(dir, "reader_board_${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            persistProgress()
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_RECOGNIZE_IMAGE, uri.toString())
            }
            startActivity(i)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "送去摆子识别失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ────────────────────────── 书页 AI 棋盘（就地小棋盘） ──────────────────────────

    /**
     * 🤖 书页 AI 棋盘：把框住的棋盘就地认出来，**在书页上浮一张小棋盘** —— 看着书、边摆边练；
     * 不必进主界面，也不必再拍照/上传。认出来先问一句「谁先走 / 双方谁人来谁电脑」，勾好确认即开局。
     * ★ 不套娃：开新盘前先把上一张盘与文字浮窗一并关掉，永远只留一个浮层。
     */
    private fun pageAiBoardFromBox(page: Int, rectFull: RectF) {
        closeOcrPanel()
        showBusy("正在识别棋盘…")
        pageView.captureRegionForBoard(page, rectFull, 1200) { bmp ->
            pageView.clearSelection()
            if (bmp == null) { hideBusy(); toast("截取棋盘失败"); return@captureRegionForBoard }
            Thread {
                val pieces = try {
                    val onnx = onnxRecognizer
                        ?: OnnxBoardRecognizer(this).also { onnxRecognizer = it }
                    runBlocking { onnx.recognizeBitmap(bmp) }.second
                } catch (_: Exception) { null }
                val finalPieces: List<BoardRecognizer.RecognizedPiece> =
                    if (!pieces.isNullOrEmpty()) pieces
                    else try {
                        runBlocking { boardRecognizer.recognizeBitmap(bmp) }.pieces
                    } catch (_: Exception) { emptyList() }
                ui.post {
                    hideBusy()
                    if (isFinishing) return@post
                    if (finalPieces.isEmpty()) {
                        toast("没认出棋盘，把棋盘框完整些再试")
                        return@post
                    }
                    showAiBoardSetupDialog(finalPieces)
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** 开局设置：谁先走 + 红/黑各由「人」还是「电脑」来走 */
    private fun showAiBoardSetupDialog(pieces: List<BoardRecognizer.RecognizedPiece>) {
        fun group(title: String, options: List<String>, checked: Int): Pair<LinearLayout, List<RadioButton>> {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(0xFF555555.toInt())
                setPadding(0, dp(8), 0, 0)
            })
            val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val rbs = ArrayList<RadioButton>()
            options.forEach { s ->
                val rb = RadioButton(this).apply {
                    text = s
                    textSize = 14f
                }
                rbs.add(rb)
                rg.addView(rb)
            }
            // ★★ 关键：必须先 addView 再 check。
            //   RadioGroup 只有在子项挂进组时才会给它生成 View id，并靠这个 id 记录"当前选中项"。
            //   若像以前那样先 isChecked=true 再 addView，组的 mCheckedId 会停在 -1，
            //   互斥随即失效 —— 点两下就成了「四个灯全亮」，红/黑两边各自都能同时选中两项。
            rg.check(rbs[checked].id)
            box.addView(rg)
            return box to rbs
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(4))
        }
        col.addView(TextView(this).apply {
            text = "共认出 ${pieces.size} 个棋子。定一下先后手、以及红黑双方谁来走："
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(2))
        })
        val (vFirst, rbFirst) = group("谁先走", listOf("红方先走", "黑方先走"), 0)
        val (vRed, rbRed) = group("红方由谁来走", listOf("人来走", "电脑走"), 0)
        val (vBlack, rbBlack) = group("黑方由谁来走", listOf("人来走", "电脑走"), 1)
        col.addView(vFirst); col.addView(vRed); col.addView(vBlack)

        // ★ 实时把"本局谁跟谁下"写清楚，确认前一眼就能核对，避免选错
        val tvSummary = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8A6B3A.toInt())
            setPadding(0, dp(10), 0, 0)
        }
        fun updateSummary() {
            val rh = rbRed[0].isChecked
            val bh = rbBlack[0].isChecked
            tvSummary.text = "本局：" + when {
                rh && bh -> "红方·人来走 对 黑方·人来走（双方对弈，电脑不参与）"
                rh && !bh -> "红方·人来走 对 黑方·电脑走（电脑陪黑方走）"
                !rh && bh -> "红方·电脑走 对 黑方·人来走（电脑陪红方走）"
                else -> "红方·电脑走 对 黑方·电脑走（自动对弈，双方都由电脑走）"
            }
        }
        // 每组内是单选（互斥），组间互不影响；这里只挂更新提示的监听
        rbRed.forEach { it.setOnCheckedChangeListener { _, _ -> updateSummary() } }
        rbBlack.forEach { it.setOnCheckedChangeListener { _, _ -> updateSummary() } }
        col.addView(tvSummary)
        updateSummary()

        AlertDialog.Builder(this)
            .setTitle("🤖 书页AI棋盘 · 开局设置")
            .setView(col)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认开局") { _, _ ->
                openPageAiBoard(
                    pieces,
                    redFirst = rbFirst[0].isChecked,
                    redHuman = rbRed[0].isChecked,
                    blackHuman = rbBlack[0].isChecked
                )
            }
            .show()
    }

    private fun openPageAiBoard(
        pieces: List<BoardRecognizer.RecognizedPiece>,
        redFirst: Boolean, redHuman: Boolean, blackHuman: Boolean
    ) {
        // ★ 不释放引擎：『重来』也要复用同一台引擎，免得每次重开都重启进程
        closeAiBoard(releaseEngine = false)
        closeOcrPanel()
        aiInitialPieces = pieces
        aiRedFirst = redFirst
        aiRedHuman = redHuman
        aiBlackHuman = blackHuman
        val b = buildBoardFromPieces(pieces, redFirst)
        aiBoard = b
        aiSel = null; aiLastFrom = null; aiLastTo = null; aiGameOver = false
        // ★ 棋规：从本局起始局面开始记 FEN 快照，供「循环着法」判定用
        aiFenHist.clear(); aiFenHist.add(b.toFen()); aiWarnedViolator = null

        buildAiCard()
        // 引擎是「一次性起好、反复用」：已经就绪就直接下，别重复起进程
        if (!aiEngineReady && (!redHuman || !blackHuman)) startAiEngineIfNeeded() else aiRefresh()
        aiMaybeAiMove()
    }

    /** 按识别结果摆盘（限数 1将/2大子/5兵，超出丢弃；位置越界丢弃） */
    private fun buildBoardFromPieces(
        pieces: List<BoardRecognizer.RecognizedPiece>, redFirst: Boolean
    ): Board {
        val b = Board()
        b.clearBoard()
        val counts = HashMap<Pair<PieceType, PieceColor>, Int>()
        for (rp in pieces) {
            if (rp.row !in 0..9 || rp.col !in 0..8) continue
            val key = rp.type to rp.color
            val max = when (rp.type) {
                PieceType.KING -> 1
                PieceType.PAWN -> 5
                else -> 2
            }
            val c = counts.getOrDefault(key, 0)
            if (c >= max) continue
            b.setPiece(Position(rp.row, rp.col), Piece(rp.type, rp.color))
            counts[key] = c + 1
        }
        b.currentPlayer = if (redFirst) PieceColor.RED else PieceColor.BLACK
        return b
    }

    private fun buildAiCard() {
        val hostH = overlayHost.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val cardW = aiWidthPx()
        val boardH = (cardW * 9.92f / 8.92f).toInt()   // 格边相等：9 行格 / 8 列格

        val card = FrameLayout(this).apply {
            background = roundRect(0xFFF7F2E7.toInt(), dp(12), 0x55000000.toInt(), dp(1))
            elevation = dp(10).toFloat()
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF5A2D2D.toInt())
            setPadding(dp(10), dp(5), dp(6), dp(5))
        }
        bar.addView(TextView(this).apply {
            text = "🤖 书页AI棋盘"
            setTextColor(0xFFF7E6C8.toInt())
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(miniBtn("收缩", 11f) { collapseAiBoard() })
        bar.addView(miniBtn("✕ 关闭", 11f) { closeAiBoard() })

        val boardView = MiniGameBoardView(this).apply {
            onTapCell = { r, c -> aiOnTapCell(r, c) }
        }
        aiBoardView = boardView
        aiBoard?.let { boardView.setBoard(it, aiLastFrom, aiLastTo) }

        val status = TextView(this).apply {
            setTextColor(0xFF333333.toInt())
            textSize = 12f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            maxLines = 2
        }
        aiStatus = status

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), dp(6))
        }
        row.addView(miniBtn("↺ 重来", 12f) { aiRestart() },
            LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
        row.addView(miniBtn("↶ 悔棋", 12f) { aiUndo() },
            LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
        row.addView(miniBtn("⚙ 设置", 12f) { showAiSettingsDialog() },
            LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
        row.addView(miniBtn("⤢ 大小", 12f) { cycleAiSize() },
            LinearLayout.LayoutParams(0, dp(36), 1f))

        col.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(boardView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, boardH))
        col.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val lp = FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(10)
            topMargin = (hostH * 0.40f).toInt().coerceAtLeast(dp(16))
        }
        overlayHost.addView(card, lp)
        attachDrag(bar, card)
        aiCard = card
        aiCollapsed = false
        pageView.locked = true          // ★ 展开摆棋中：冻住翻页，免得一边走子一边把页面翻跑
        aiRefresh()
        overlayHost.post { clampIntoHost(card) }
    }

    private fun miniBtn(label: String, sizeSp: Float, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = sizeSp
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }

    /**
     * ★ 自适应算盘面宽度（任何手机都不会超出屏幕）：
     *  - 小档 38% 屏宽；中档 58% 屏宽；
     *  - 大档按「盘面面积约占屏幕 1/3」反推（宽高比 8.92:9.92），再受屏宽/屏高双重夹逼。
     *  - 纵向也夹：标题条+状态行+按钮排的余量刨掉后，整卡必须塞得进屏高。
     */
    private fun aiWidthPx(): Int {
        val hostW = overlayHost.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val hostH = overlayHost.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val frac = when (aiSizeGear) {
            0 -> 0.38f
            1 -> 0.58f
            else -> aiLargeFrac(hostW, hostH)
        }
        var w = (hostW * frac).toInt()
        // 纵向自适应：盘高 ≈ w*9.92/8.92，再加边框/标题/状态/按钮的约 130dp 余量
        val chrome = dp(130)
        val byH = ((hostH * 0.92f - chrome) * 8.92f / 9.92f).toInt()
        w = minOf(w, byH).coerceAtLeast(dp(180))
        return w.coerceAtMost((hostW * 0.94f).toInt())
    }

    /** 「约 1/3 屏面积」对应的大号盘宽比例（盘面积 = 宽 × 高，高≈宽×1.1121） */
    private fun aiLargeFrac(hostW: Int, hostH: Int): Float {
        val target = hostW.toFloat() * hostH / 3f
        val w = kotlin.math.sqrt(target / 1.1121f)
        return (w / hostW).coerceIn(0.55f, 0.94f)
    }

    private fun cycleAiSize() {
        val card = aiCard ?: return
        aiSizeGear = (aiSizeGear + 1).mod(3)
        val w = aiWidthPx()
        val hostW = overlayHost.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = w
            lp.leftMargin = lp.leftMargin.coerceIn(0, (hostW - w).coerceAtLeast(0))
            card.layoutParams = lp
        }
        aiBoardView?.let { bv ->
            (bv.layoutParams as? LinearLayout.LayoutParams)?.let { blp ->
                blp.height = (w * 9.92f / 8.92f).toInt()
                bv.layoutParams = blp
            }
        }
        overlayHost.post { clampIntoHost(card) }
        toast("棋盘大小：" + arrayOf("小", "中", "大")[aiSizeGear] + "（${(w * 100f / hostW).toInt()}% 屏宽）")
    }

    // ── 收起成小胶囊 / 点一下还原 ──

    /**
     * 收成一颗小胶囊：不挡书、按住可随手拖（左右上下都行），**点一下即还原**。
     * 只有收起来后才能翻来翻去对书 —— 展开摆棋时纸张是冻住的。
     */
    private fun collapseAiBoard() {
        val card = aiCard ?: return
        if (aiCollapsed) return
        val cardLp = card.layoutParams as? FrameLayout.LayoutParams
        val cap = TextView(this).apply {
            // ★ 学「复盘 · 第N回合」那颗小胶囊：圆圆一粒、随走随更新手数
            text = aiCapsuleLabel()
            textSize = 13f
            setTextColor(0xFF3A2B12.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = roundRect(0xFFF6E7C4.toInt(), dp(22), 0x665A2D2D.toInt(), dp(1))
            elevation = dp(10).toFloat()
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = cardLp?.leftMargin ?: dp(10)
            topMargin = cardLp?.topMargin ?: (dp(16))
        }
        overlayHost.addView(cap, lp)
        // 按住能拖、点一下还原（同一处手势里区分「拖动 / 轻点」）
        attachDrag(cap, cap) { expandAiBoard() }
        aiCapsule = cap
        card.visibility = View.GONE
        aiCollapsed = true
        pageView.locked = false          // ★ 收起了才解锁：这下可以随便翻页对着书走了
        overlayHost.post { clampIntoHost(cap) }
    }

    /** 胶囊上的字样：跟复盘一样「书页棋 · 第N手」 */
    private fun aiCapsuleLabel(): String =
        "🤖 书页棋 · 第${aiBoard?.getMoveHistory()?.size ?: 0}手"

    /** 点胶囊还原成完整棋盘（挪回胶囊所在位置） */
    private fun expandAiBoard() {
        val cap = aiCapsule ?: return
        val capLp = cap.layoutParams as? FrameLayout.LayoutParams
        val l = capLp?.leftMargin ?: dp(10)
        val t = capLp?.topMargin ?: dp(16)
        aiCapsule = null
        try { overlayHost.removeView(cap) } catch (_: Exception) {}
        aiCard?.let { card ->
            card.visibility = View.VISIBLE
            (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = l; lp.topMargin = t
                card.layoutParams = lp
            }
        }
        aiCollapsed = false
        pageView.locked = true           // 又展开了：重新冻住翻页
        overlayHost.post { aiCard?.let { clampIntoHost(it) } }
        aiRefresh()
    }

    /**
     * ⚙ 设置：两行 —— 「步时（秒）」与「层数（深度）」，行首小方框二选一（互斥），
     * 后面各带数值框和 −/＋ 步进。语义与主界面完全一致：
     *   步时模式 → 交给引擎 go movetime（到点必走）；层数模式 → go depth（到层才出招）。
     */
    private fun showAiSettingsDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(2))
        }

        val cbTime = CheckBox(this).apply { text = "步时"; textSize = 15f; isChecked = !aiUseDepth }
        val cbDepth = CheckBox(this).apply { text = "层数"; textSize = 15f; isChecked = aiUseDepth }
        val etTime = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f; setSingleLine(); gravity = Gravity.CENTER
            setText(aiSecCfg.toString())
        }
        val etDepth = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f; setSingleLine(); gravity = Gravity.CENTER
            setText(aiDepthCfg.toString())
        }
        fun stepBtn(t: String) = Button(this).apply {
            text = t; textSize = 18f; minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
        }
        val btnTimeMinus = stepBtn("−"); val btnTimePlus = stepBtn("＋")
        val btnDepthMinus = stepBtn("−"); val btnDepthPlus = stepBtn("＋")

        // 互斥：勾一个自动取消另一个；且不允许两个都不勾（至少留一个）
        fun syncEnabled() {
            val t = cbTime.isChecked
            etTime.isEnabled = t; btnTimeMinus.isEnabled = t; btnTimePlus.isEnabled = t
            etDepth.isEnabled = !t; btnDepthMinus.isEnabled = !t; btnDepthPlus.isEnabled = !t
        }
        cbTime.setOnCheckedChangeListener { _, c ->
            if (c) cbDepth.isChecked = false else if (!cbDepth.isChecked) cbTime.isChecked = true
            syncEnabled()
        }
        cbDepth.setOnCheckedChangeListener { _, c ->
            if (c) cbTime.isChecked = false else if (!cbTime.isChecked) cbDepth.isChecked = true
            syncEnabled()
        }

        // 数值框 + −/＋ 步进（老实夹在 1..60 秒 / 1..30 层）
        fun step(et: EditText, delta: Int, min: Int, max: Int) {
            val cur = et.text.toString().trim().toIntOrNull() ?: min
            val nv = (cur + delta).coerceIn(min, max)
            et.setText(nv.toString())
            et.setSelection(et.text.length)
        }
        btnTimeMinus.setOnClickListener { step(etTime, -1, 1, 60) }
        btnTimePlus.setOnClickListener { step(etTime, +1, 1, 60) }
        btnDepthMinus.setOnClickListener { step(etDepth, -1, 1, 30) }
        btnDepthPlus.setOnClickListener { step(etDepth, +1, 1, 30) }

        val etLp = { LinearLayout.LayoutParams(dp(58), dp(42)) }
        val btnLp = { LinearLayout.LayoutParams(dp(46), dp(42)) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            addView(cbTime)
            addView(etTime, etLp())
            addView(btnTimeMinus, btnLp())
            addView(btnTimePlus, btnLp())
        })
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            addView(cbDepth)
            addView(etDepth, etLp())
            addView(btnDepthMinus, btnLp())
            addView(btnDepthPlus, btnLp())
        })
        col.addView(TextView(this).apply {
            text = "二选一（互相排斥）：勾【步时】= 每步思考这么多秒，到点必走；勾【层数】= 引擎算到这么多层才出招。与主界面设置同一套算法。中途可随时改。"
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        syncEnabled()

        AlertDialog.Builder(this)
            .setTitle("⚙ 书页AI棋盘 · 设置")
            .setView(col)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ ->
                aiUseDepth = cbDepth.isChecked
                aiSecCfg = (etTime.text.toString().trim().toIntOrNull() ?: 1).coerceIn(1, 60)
                aiDepthCfg = (etDepth.text.toString().trim().toIntOrNull() ?: 8).coerceIn(1, 30)
                toast(
                    if (aiUseDepth) "已设置为「层数」模式：算到 $aiDepthCfg 层才出招"
                    else "已设置为「步时」模式：每步思考 $aiSecCfg 秒"
                )
            }
            .show()
    }

    private fun closeAiBoard(releaseEngine: Boolean = true) {
        val c = aiCard ?: return
        aiCard = null
        aiBoardView = null
        aiStatus = null
        aiBoard = null
        aiSel = null; aiLastFrom = null; aiLastTo = null
        aiGameOver = false
        aiThinking = false
        aiCollapsed = false
        aiCapsule?.let { cap -> try { overlayHost.removeView(cap) } catch (_: Exception) {} }
        aiCapsule = null
        pageView.locked = false          // 关掉棋盘：纸张恢复可翻
        if (releaseEngine) {
            // ★ 「关闭」= 这台便携引擎下线：停搜、关进程。主界面那套引擎完全不受影响
            try { aiEngine?.stopSearch() } catch (_: Exception) {}
            try { aiEngine?.shutdown() } catch (_: Exception) {}
            aiEngine = null
            aiEngineReady = false
            aiEngineStarting = false
        }
        try { overlayHost.removeView(c) } catch (_: Exception) {}
    }

    /** 刷新小棋盘与状态行（hint 优先显示；否则按当前局面给出该谁走） */
    private fun aiRefresh(hint: String? = null) {
        val b = aiBoard ?: return
        aiBoardView?.setBoard(b, aiLastFrom, aiLastTo)
        val side = b.currentPlayer
        val who = if (side == PieceColor.RED) "红方" else "黑方"
        val human = if (side == PieceColor.RED) aiRedHuman else aiBlackHuman
        aiStatus?.text = when {
            aiGameOver -> hint ?: "对局已结束"
            aiThinking -> "电脑（$who）思考中…" + if (aiUseDepth) "【层数 $aiDepthCfg】" else "【步时 $aiSecCfg 秒】"
            !human && !aiEngineReady -> "AI 正在启动…（$who 由电脑走）"
            hint != null -> hint
            else -> "轮到$who" + if (human) "（你来走）" else "（电脑走）"
        }
        val sel = aiSel
        val targets: Set<Pair<Int, Int>> = if (sel != null && !aiThinking) {
            b.getLegalMoves(b.currentPlayer)
                .filter { it.from.row == sel.first && it.from.col == sel.second }
                .map { it.to.row to it.to.col }
                .toSet()
        } else emptySet()
        aiBoardView?.setSelection(sel, targets)
        // ★ 收起成胶囊时，胶囊上的「第N手」随走随更新（跟复盘那颗一样）
        aiCapsule?.text = aiCapsuleLabel()
    }

    private fun aiOnTapCell(r: Int, c: Int) {
        val b = aiBoard ?: return
        if (aiGameOver) { toast("本局已结束，可点『↺ 重来』"); return }
        if (aiThinking) return
        val side = b.currentPlayer
        val human = if (side == PieceColor.RED) aiRedHuman else aiBlackHuman
        if (!human) return
        val p = b.pieceAt(r, c)
        if (p != null && p.color == side) {
            aiSel = r to c
            aiRefresh()
            return
        }
        val sel = aiSel ?: return
        val piece = b.pieceAt(sel.first, sel.second)
        if (piece == null) { aiSel = null; aiRefresh(); return }
        val mv = Move(Position(sel.first, sel.second), Position(r, c), piece, b.pieceAt(r, c))
        if (b.makeMove(mv)) {
            aiSel = null
            aiLastFrom = mv.from.row to mv.from.col
            aiLastTo = mv.to.row to mv.to.col
            afterMove()
        } else {
            aiRefresh("这步走不了，换一步试试")
        }
    }

    /** 每走一步后：判终局 → 该电脑走就唤引擎 */
    private fun afterMove() {
        val b = aiBoard ?: return
        aiRefresh()
        val opp = b.currentPlayer
        if (b.isCheckmate(opp)) {
            aiGameOver = true
            aiRefresh("绝杀！" + (if (opp == PieceColor.RED) "黑方" else "红方") + "胜")
            return
        }
        if (b.isStalemate(opp)) {
            aiGameOver = true
            aiRefresh("困毙！" + (if (opp == PieceColor.RED) "黑方" else "红方") + "胜")
            return
        }
        // ★ 棋规（亚象联棋例）：长将/长捉/循环重复 → 首次警告变着，再犯判负，双方均犯判和
        if (aiCheckRepetition()) return
        aiMaybeAiMove()
    }

    /** ★ 棋规判定：把本步后的局面记进快照，交给主界面同款 RepetitionRule 判循环着法 */
    private fun aiCheckRepetition(): Boolean {
        val b = aiBoard ?: return false
        aiFenHist.add(b.toFen())
        val movesUci = b.getMoveHistory().map { it.toUci() }
        return when (val v = aiRepeat.checkRepetition(aiFenHist, movesUci, aiWarnedViolator)) {
            is RepetitionRule.Verdict.MustChange -> {
                aiWarnedViolator = v.violator
                val who = if (v.violator == PieceColor.RED) "红方" else "黑方"
                aiRefresh("棋规：$who 须变着（${v.reason}）")
                false
            }
            is RepetitionRule.Verdict.ForcedLoss -> {
                aiGameOver = true
                val who = if (v.loser == PieceColor.RED) "红方" else "黑方"
                aiRefresh("棋规判负：$who（${v.reason}）")
                true
            }
            is RepetitionRule.Verdict.ForcedDraw -> {
                aiGameOver = true
                aiRefresh("棋规判和：${v.reason}")
                true
            }
            else -> false
        }
    }

    private fun aiMaybeAiMove() {
        val b = aiBoard ?: return
        if (aiGameOver || aiThinking) return
        val side = b.currentPlayer
        val human = if (side == PieceColor.RED) aiRedHuman else aiBlackHuman
        if (human) return
        if (!aiEngineReady) { startAiEngineIfNeeded(); return }
        aiThinking = true
        aiRefresh()
        // ★ 与主界面同一套：步时模式 → go movetime（到点必走）；层数模式 → go depth（到层才出招）
        val depth: Int? = if (aiUseDepth) aiDepthCfg else null
        val thinkMs: Long? = if (aiUseDepth) null else aiSecCfg * 1000L
        aiEngine?.searchBestMove(b, timeMs = thinkMs, depth = depth, onResult = { mv ->
            ui.post {
                if (aiBoard !== b || aiGameOver) { aiThinking = false; return@post }
                aiThinking = false
                var applied = false
                if (mv != null && b.makeMove(mv)) {
                    aiLastFrom = mv.from.row to mv.from.col
                    aiLastTo = mv.to.row to mv.to.col
                    applied = true
                }
                if (!applied) {
                    // 兜底：引擎没给招也要动，绝不让一步棋悬空卡死
                    val legal = b.getLegalMoves(b.currentPlayer)
                    if (legal.isNotEmpty()) {
                        val pick = legal.random()
                        b.makeMove(pick)
                        aiLastFrom = pick.from.row to pick.from.col
                        aiLastTo = pick.to.row to pick.to.col
                        applied = true
                    }
                }
                if (applied) afterMove()
                else { aiGameOver = true; aiRefresh("无子可动，本局结束") }
            }
        })
    }

    private fun startAiEngineIfNeeded() {
        if (aiEngineReady || aiEngineStarting) return
        aiEngineStarting = true
        // ★ 接主界面同款「皮卡鱼」引擎（JniEngine），但这是本面板专用的独立实例：
        //   走轻量档（1 线程 / 32MB），不跟主界面抢线程与内存 —— 主界面那边秋毫无犯。
        val e = aiEngine ?: JniEngine(this).also {
            it.threadCount = JniEngine.DEFAULT_THREADS_LIGHT
            it.hashSize = JniEngine.DEFAULT_HASH_LIGHT
            aiEngine = it
        }
        Thread {
            val err = try {
                e.locateEngine() ?: e.copyCurrentNNue() ?: e.startEngine() ?: e.liteInit()
            } catch (ex: Exception) { ex.message ?: "引擎启动异常" }
            ui.post {
                aiEngineStarting = false
                if (aiBoard == null) return@post
                if (err == null) {
                    aiEngineReady = true
                    aiRefresh()
                    aiMaybeAiMove()
                } else {
                    // 起不来就退化成「双人对摆」，用户照样能边看边摆，不卡人
                    aiRedHuman = true
                    aiBlackHuman = true
                    aiRefresh("AI 未能启动，已切双人对摆：$err")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun aiRestart() {
        closeAiBoard(releaseEngine = false)      // 复用同一台引擎，重开一盘不重启进程
        if (aiInitialPieces.isEmpty()) return
        openPageAiBoard(aiInitialPieces, aiRedFirst, aiRedHuman, aiBlackHuman)
    }

    private fun aiUndo() {
        val b = aiBoard ?: return
        if (aiThinking) { toast("电脑正在思考，稍等"); return }
        if (b.getMoveHistory().isEmpty()) { toast("还没有可悔的棋"); return }
        aiGameOver = false
        var n = 0
        while (n < 2 && b.undoMove() != null) {
            n++
            val side = b.currentPlayer
            val human = if (side == PieceColor.RED) aiRedHuman else aiBlackHuman
            if (human) break
        }
        // ★ 棋规快照跟着退：一手一条 FEN，退到「已走手数 + 1」条为止
        while (aiFenHist.size > b.getMoveHistory().size + 1) aiFenHist.removeAt(aiFenHist.size - 1)
        aiWarnedViolator = null
        aiSel = null
        aiLastFrom = null; aiLastTo = null
        aiRefresh("已悔棋")
        aiMaybeAiMove()
    }

    // ────────────────────────── OCR ──────────────────────────

    private fun runOcr(bmp: Bitmap) {
        val r = recognizer ?: TextRecognition
            .getClient(ChineseTextRecognizerOptions.Builder().build())
            .also { recognizer = it }
        val img = InputImage.fromBitmap(bmp, 0)
        r.process(img)
            .addOnSuccessListener { res ->
                hideBusy()
                showOcrResult(res.text)
            }
            .addOnFailureListener { e ->
                hideBusy()
                toast("文字识别失败：${e.message}")
            }
    }

    /**
     * 📝 识别结果浮窗（可拖动 + 可调行宽）
     *
     * 用户要「一边看书页、一边看认出来的字」：所以不再用挡屏的对话框，改成一张**能拖着走的浮窗**——
     *  - 按住标题条随手拖到不挡棋盘的地方，拖动全程被钳在屏幕内，绝不会飘到屏幕外；
     *  - 底部三键：📋 复制 / ↔ 行宽 / ✕ 关闭；「行宽」拉滑杆实时改宽度（所见即所得），
     *    文字随宽度**自动换行**（跟 Word 段落重排一个道理），取消即还原；
     *  - 字走规整的常规字体，跟聊天文字同款，干净好读。
     */
    private fun showOcrResult(raw: String) {
        // 局部变量特意不叫 text：避免遮蔽 TextView/Button 的 text 属性（否则赋值会报 Val cannot be reassigned）
        val body = raw.trim()
        if (body.isBlank()) {
            toast("没识别到文字（可直接框小一点、或先『增强』再试）")
            return
        }
        closeOcrPanel()

        val hostW = overlayHost.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val hostH = overlayHost.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val card = FrameLayout(this).apply {
            background = roundRect(0xFFFBFBF7.toInt(), dp(12), 0x33000000.toInt(), dp(1))
            elevation = dp(10).toFloat()
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 标题条：按住它就能拖整张浮窗
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF3A342C.toInt())
            setPadding(dp(12), dp(9), dp(10), dp(9))
        }
        bar.addView(TextView(this).apply {
            text = "📝 反选·识别文字　（按住这里拖动）"
            setTextColor(0xFFF2E4C8.toInt())
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 正文：宽度一变，字自动换行
        val tv = TextView(this).apply {
            text = body
            textSize = 17f
            setTextColor(0xFF1F1F1F.toInt())
            setLineSpacing(dp(6).toFloat(), 1.15f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        col.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部三键：复制 / 行宽 / 关闭
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFF0EBE1.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun act(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 14f; minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }
        row.addView(act("📋 复制") { copyOcrText(body) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        row.addView(act("↔ 行宽") { showLineWidthDialog(card) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        row.addView(act("✕ 关闭") { closeOcrPanel() },
            LinearLayout.LayoutParams(0, dp(42), 1f))
        col.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val lp = FrameLayout.LayoutParams(
            (hostW * ocrWidthFrac).toInt(), (hostH * 0.62f).toInt()).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(12)
            topMargin = (hostH * 0.15f).toInt().coerceAtLeast(dp(16))
        }
        overlayHost.addView(card, lp)
        attachDrag(bar, card)
        ocrCard = card
        overlayHost.post { clampIntoHost(card) }
    }

    private fun copyOcrText(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("读物文字", text))
            toast("已复制全部文字")
        } catch (_: Exception) { toast("复制失败") }
    }

    private fun closeOcrPanel() {
        val c = ocrCard ?: return
        ocrCard = null
        try { overlayHost.removeView(c) } catch (_: Exception) {}
    }

    /** ↔ 行宽：拉滑杆实时改浮窗宽度（所见即所得，字随宽度自动重排），取消回原样 */
    private fun showLineWidthDialog(card: View) {
        val start = ocrWidthFrac
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        fun label(f: Float) = "浮窗宽度：${(f * 100).toInt()}%（字随宽度自动换行）"
        val head = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF444444.toInt())
            text = label(start)
        }
        col.addView(head)
        val sb = SeekBar(this).apply {
            max = 62                                  // 30% ~ 92%
            progress = ((start * 100).toInt() - 30).coerceIn(0, 62)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val f = (progress + 30) / 100f
                    head.text = label(f)
                    applyCardWidth(card, f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        col.addView(sb)

        val dlg = AlertDialog.Builder(this)
            .setTitle("↔ 行宽")
            .setView(col)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                applyCardWidth(card, start)
                dlg.dismiss()
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                ocrWidthFrac = (sb.progress + 30) / 100f
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    /** 按屏宽比例设浮窗宽度，并把浮窗钳回屏内（绝不出屏） */
    private fun applyCardWidth(card: View, frac: Float) {
        val hostW = overlayHost.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = (hostW * frac.coerceIn(0.30f, 0.92f)).toInt()
        lp.leftMargin = lp.leftMargin.coerceAtMost((hostW - lp.width).coerceAtLeast(0))
        card.layoutParams = lp
    }

    /** 圆角卡片背景（纯色 + 可选描边 + 圆角） */
    private fun roundRect(fill: Int, radius: Int, stroke: Int = 0, strokeW: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (strokeW > 0) setStroke(strokeW, stroke)
        }

    /**
     * 让 [card] 可以按住 [handle] 拖动；全程限制在宿主范围内 —— 手指怎么甩都甩不出屏幕。
     * [onTap] 非空时：轻点（没怎么挪动）也回调一次，用于「点胶囊即还原」。
     */
    private fun attachDrag(handle: View, card: View, onTap: (() -> Unit)? = null) {
        var downRawX = 0f; var downRawY = 0f
        var startL = 0; var startT = 0
        var moved = false
        val slop = dp(6).toFloat()
        handle.setOnTouchListener { _, e ->
            val lp = card.layoutParams as? FrameLayout.LayoutParams
                ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startL = lp.leftMargin; startT = lp.topMargin
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.hypot(e.rawX - downRawX, e.rawY - downRawY) > slop) moved = true
                    val maxL = (overlayHost.width - card.width).coerceAtLeast(0)
                    val maxT = (overlayHost.height - card.height).coerceAtLeast(0)
                    lp.leftMargin = (startL + (e.rawX - downRawX).toInt()).coerceIn(0, maxL)
                    lp.topMargin = (startT + (e.rawY - downRawY).toInt()).coerceIn(0, maxT)
                    card.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && onTap != null) onTap()
                    true
                }
                else -> false
            }
        }
    }

    /** 布局变化后把浮窗收回屏内（换行宽 / 旋屏用） */
    private fun clampIntoHost(card: View) {
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        val maxL = (overlayHost.width - card.width).coerceAtLeast(0)
        val maxT = (overlayHost.height - card.height).coerceAtLeast(0)
        lp.leftMargin = lp.leftMargin.coerceIn(0, maxL)
        lp.topMargin = lp.topMargin.coerceIn(0, maxT)
        card.layoutParams = lp
    }

    private fun showBusy(msg: String) {
        hideBusy()
        busy = AlertDialog.Builder(this)
            .setMessage(msg)
            .setCancelable(false)
            .show()
    }

    private fun hideBusy() {
        try { busy?.dismiss() } catch (_: Exception) {}
        busy = null
    }

    // ────────────────────────── 目录 ──────────────────────────

    private fun showTocDialog() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        // ① 页码直达（输入框只留一小格）+ 页码修正差
        val jumpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(2))
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "页码"
            textSize = 14f
            setSingleLine()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        // 页码修正差：正 / 负 二选一（互斥），后面填差值。0 或不勾 = 不修正
        val savedOff = ReaderShelf.pageOffset(this, fileName)
        val cbPos = CheckBox(this).apply { text = "正"; textSize = 14f; isChecked = savedOff > 0 }
        val cbNeg = CheckBox(this).apply { text = "负"; textSize = 14f; isChecked = savedOff < 0 }
        val etOff = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "0"
            textSize = 14f
            setSingleLine()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            if (savedOff != 0) setText(Math.abs(savedOff).toString())
        }
        cbPos.setOnCheckedChangeListener { _, checked -> if (checked) cbNeg.isChecked = false }
        cbNeg.setOnCheckedChangeListener { _, checked -> if (checked) cbPos.isChecked = false }
        fun curOffset(): Int {
            val v = etOff.text.toString().trim().toIntOrNull() ?: 0
            return if (cbPos.isChecked) v else if (cbNeg.isChecked) -v else 0
        }

        val btnJump = Button(this).apply {
            text = "跳转"
            textSize = 13f
            minWidth = 0
            setOnClickListener {
                val n = et.text.toString().trim().toIntOrNull()
                if (n == null || n < 1) {
                    Toast.makeText(this@PdfReaderActivity, "请输入要跳转的页码", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // ★ 该加到哪一页：输入的页码 + 修正差（正=实际页更大→加，负=更小→减）
                val target = n + curOffset()
                if (target < 1 || target > pageView.pageCount) {
                    Toast.makeText(
                        this@PdfReaderActivity,
                        "修正后为第 $target 页，超出 1 ~ ${pageView.pageCount} 的范围", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    ReaderShelf.savePageOffset(this@PdfReaderActivity, fileName, curOffset())
                    pageView.goToPage(target - 1)
                    dialogRef?.dismiss()
                }
            }
        }

        jumpRow.addView(TextView(this).apply {
            text = "跳到第"; textSize = 14f; setPadding(0, 0, dp(6), 0)
        })
        jumpRow.addView(et, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        jumpRow.addView(TextView(this).apply { text = "页"; textSize = 14f; setPadding(dp(6), 0, 0, 0) })
        jumpRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))   // 弹性留白，把「跳转」推到右端
        jumpRow.addView(btnJump, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        col.addView(jumpRow)

        // ★ 页码修正差（正文页码常从第 1 页重算，跟 PDF 实际页对不上时用它对齐）
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
            addView(TextView(this@PdfReaderActivity).apply {
                text = "页码修正差"; textSize = 14f; setPadding(0, 0, dp(4), 0)
            })
            addView(cbPos)
            addView(cbNeg)
            addView(etOff, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        col.addView(TextView(this).apply {
            text = "正文页码常从「第 1 页」重新起算，和 PDF 实际页会有固定偏差。勾【正】= 实际页更大（跳转时加），勾【负】= 更小（跳转时减），后面填差值；不勾或不填 = 不修正。此差值只作用于上面的『跳到第』，按书记住。"
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            setPadding(0, 0, 0, dp(4))
        })

        // ② 已标记页
        if (marks.isNotEmpty()) {
            col.addView(sectionTitle("我的标记"))
            for (p in marks.sorted()) {
                col.addView(tocRow("★ 第 ${p + 1} 页", 0) {
                    pageView.goToPage(p)
                    dialogRef?.dismiss()
                })
            }
        }

        // ③ 已划线页
        val hlPages = highlights.keys.filter { (highlights[it]?.size ?: 0) > 0 }.sorted()
        if (hlPages.isNotEmpty()) {
            col.addView(sectionTitle("我的划线"))
            for (p in hlPages) {
                col.addView(tocRow("🖍 第 ${p + 1} 页（${highlights[p]?.size ?: 0} 处）", 0) {
                    pageView.goToPage(p)
                    dialogRef?.dismiss()
                })
            }
        }

        // ④ 目录（PDF 自带书签）
        if (outline.isEmpty()) {
            col.addView(sectionTitle("目录"))
            col.addView(TextView(this).apply {
                text = "这本读物没有内置目录，可用上方页码直接跳转；阅读时会自动记住进度。"
                textSize = 13f
                setTextColor(0xFF666666.toInt())
                setPadding(dp(4), dp(4), dp(4), dp(10))
            })
        } else {
            col.addView(sectionTitle("目录（共 ${outline.size} 条）"))
            for (item in outline) {
                val label = if (item.page >= 0) "${item.title}    ·  ${item.page + 1}" else item.title
                col.addView(tocRow(label, item.depth) {
                    if (item.page >= 0) {
                        pageView.goToPage(item.page)
                        dialogRef?.dismiss()
                    } else {
                        Toast.makeText(this, "该目录项没有页码，无法跳转", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }

        scroll.addView(col)
        dialogRef = AlertDialog.Builder(this)
            .setTitle("目录")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        // ★ 关掉目录就把「页码修正差」按书记住，下次打开自动填回
        dialogRef?.setOnDismissListener { ReaderShelf.savePageOffset(this, fileName, curOffset()) }
        dialogRef?.show()
    }

    private var dialogRef: AlertDialog? = null

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(0xFF8A6A2A.toInt())
        setPadding(dp(4), dp(10), dp(4), dp(4))
    }

    private fun tocRow(label: String, depth: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6 + depth * 16), dp(9), dp(6), dp(9))
            setOnClickListener { onClick() }
        }

    // ────────────────────────── 进度 / 退出 ──────────────────────────

    private fun persistProgress() {
        if (pageTotal <= 0 || fileName.isBlank()) return
        try {
            ReaderShelf.saveProgress(this, fileName, pageView.ratio(), pageView.currentPage(), pageTotal)
        } catch (_: Exception) {}
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        persistProgress()
        persistView()
    }

    /** ★ 退出阅读：只退这一步，回到主界面继续下棋；绝不牵连整个应用 */
    private fun exitReader() {
        ui.removeCallbacksAndMessages(null)
        persistProgress()
        persistView()
        finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 浮层优先：先关浮层，再退框选，最后才退阅读（避免一退把整个阅读器退了）
        if (aiCard != null) { closeAiBoard(); return }
        if (ocrCard != null) { closeOcrPanel(); return }
        if (pageView.selectMode) {
            exitSelectMode()
            return
        }
        exitReader()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { persistProgress() } catch (_: Exception) {}
        try { persistView() } catch (_: Exception) {}
        try { closeAiBoard() } catch (_: Exception) {}
        try { aiEngine?.shutdown() } catch (_: Exception) {}
        aiEngine = null
        try { onnxRecognizer?.close() } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        try { pageView.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}

/** 阅读模式常量（与 PdfPageView 内保持一致，供 Activity 引用） */
object PdfPageViewMode {
    const val SCROLL = 0
    const val FLIP = 1
}
