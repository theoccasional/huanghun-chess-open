package com.pikafish.chess

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.pikafish.chess.engine.BookVault
import com.pikafish.chess.engine.UserEngineStore
import com.pikafish.chess.heavy.EngineShareServer
import com.pikafish.chess.heavy.HeavyBeacon
import com.pikafish.chess.heavy.HeavyHttpServer
import com.pikafish.chess.heavy.PairingCode
import java.io.File
import kotlin.random.Random

/**
 * ★★★ 「发送端」控制台：出配对码 + 借出本机引擎 + 一切状态摊在明面上。★★★
 *
 * <p>这是用户明确要的形态：<b>棋盘与局面不进本界面</b>，这里只当"主机"——</p>
 * <ul>
 *   <li>大号配对码（另一台手机填它来配）；</li>
 *   <li>连接状态：连没连上、对端 IP、当前出借的是哪台引擎；</li>
 *   <li>小棋盘 + 思考细节：对端发来的局面、引擎的深度/评分/主变、给出的着法；</li>
 *   <li>「切换引擎」：一排本机已装载/内置的引擎，点谁就换谁——换完主动断开对端，
 *       对端自动重连、重新握手，跟着新引擎走（像在本机上换引擎那样丝滑）；</li>
 *   <li>「加载开局库」：选一个库文件，进全局开局库仓（主界面对弈立刻可用）。</li>
 * </ul>
 *
 * <p>为什么要有这一页：跨设备配对最容易"不知道边界在哪"。这里把连接、引擎、局面、
 * 思考四件事全部可视化，出问题一眼能看出断在哪一环，不再靠猜。</p>
 */
class SenderActivity : Activity() {

    companion object {
        const val EXTRA_CODE = "sender_code"
        private const val REQ_BOOK = 3001
    }

    private var code: String = ""
    private var enginePort = 0

    private var statusView: TextView? = null
    /** ★ 实验：开局库标识（加载成功后把“当前出借”这行变蓝并附库名） */
    private var bookBadge: String? = null
    private var lastLend: String = "皮卡鱼（内置）"
    private var detailView: TextView? = null
    private var moveView: TextView? = null
    private var lendView: TextView? = null
    private var boardView: MonitorBoard? = null
    private var lastFen = ""
    /** ★ 收官版：底部随机价值观文案 */
    private var quoteView: TextView? = null
    private var lastQuote = -1

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshMonitor()
            handler.postDelayed(this, 500)
        }
    }

    /**
     * 收官版：把用户给的产品立场，压成一小段一小段，15 秒换一条。
     * 不做“反象棋宣传”，只做去神话、说真代价、给台阶。
     */
    private val quotes = listOf(
        "象棋不是人人适合的修身方式。\n它是高失误惩罚、低容错的对抗游戏。\n小玩可以，失衡就停。",
        "不会下象棋，不影响一个人活得清醒、体面、自在。\n它不是必修课。",
        "若你越下越焦躁、越下越自责，那就少下为佳。",
        "象棋有技术价值，不等于人人适合。\n玩归玩，别拿它折自己。",
        "这游戏不替象棋说漂亮话。\n觉得受用就玩，觉得伤心气就撤。",
        "象棋只是游戏，不是人格考试。\n这盘输的是局面，不是你整个人。",
        "输棋不等于低人一等。\n这类游戏容错低，难受很正常。",
        "它会抓你的失误，也会放大你的自责。\n下到想骂自己时，就该撤了。",
        "一步走漏，后面就可能一路挨打。\n别把一盘棋，打成对自己的公审。",
        "连败最伤的不是分数，是心气。\n先停一停，不丢人。",
        "复盘是看问题，不是审判自己。\n棋可以拆，别把人也拆烂了。",
        "不会下棋，不丢人。\n下棋下到怀疑自己，才真犯不着。",
        "小玩可以，深陷未必值得。\n失衡就停，少下为佳。",
        "我们不把象棋说成人人适合的修身工具。\n对有些人，它带来的不是修养，而是持续的挫败与自责。"
    )
    private val quoteTick = object : Runnable {
        override fun run() {
            showQuote()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        code = intent?.getStringExtra(EXTRA_CODE)?.takeIf { it.length == 6 }
            ?: PairingCode.generate()
        startHosting()
        EngineKeepAliveService.start(this)   // ★ 2.9.298 息屏保活（前台服务 + CPU/WiFi 锁）
        buildUi()
        showQuote()
        handler.postDelayed(quoteTick, 15_000)
    }

    /** 开启：大件共享 + 借出引擎 + 配对广播。 */
    private fun startHosting() {
        try {
            HeavyHttpServer.shared().start(File(applicationInfo.sourceDir))
        } catch (_: Exception) {
        }
        enginePort = try {
            EngineShareServer.shared().start(this)
        } catch (_: Exception) {
            0
        }
        try {
            HeavyBeacon.startResponder(code, HeavyHttpServer.shared().port(), enginePort)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(poll)
        handler.removeCallbacks(quoteTick)
        HeavyBeacon.stopResponder()
        HeavyHttpServer.shared().stop()
        EngineShareServer.shared().stop()
        EngineKeepAliveService.stop(this)
    }

    // ═════════════════ 界面 ═════════════════

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF211005.toInt()) }
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val pad = (20 * density).toInt()
        column.setPadding(pad, pad, pad, pad)

        column.addView(text("黄昏象棋 · 发送端", 24f, 0xFFE8C44A.toInt()))
        column.addView(text("把下面的配对码，填到要玩的那台手机里", 15f, 0xFFCCBB88.toInt()))
        column.addView(TextView(this).apply {
            text = PairingCode.spaced(code)
            setTextColor(0xFFFFE27A.toInt())
            textSize = 46f
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(0, (14 * density).toInt(), 0, (8 * density).toInt())
        })
        column.addView(text(
            if (enginePort > 0) "本机引擎已开启（端口 $enginePort）· 保持本页打开" else "引擎未就绪",
            13f, 0xFF997733.toInt()
        ))

        column.addView(divider(density, "连接状态"))
        statusView = text("未连接（等待对端借引擎）", 16f, 0xFFFFD37A.toInt())
        column.addView(statusView)

        boardView = MonitorBoard(this)
        val side = (230 * density).toInt()
        column.addView(boardView, LinearLayout.LayoutParams(side, (side * 11 / 10))
            .apply { topMargin = (8 * density).toInt() })

        detailView = TextView(this).apply {
            text = "等待对端发来局面…"
            setTextColor(0xFFE8D9A8.toInt())
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        column.addView(detailView)

        moveView = text("", 15f, 0xFF9CCBFF.toInt())
        column.addView(moveView)

        lendView = text("", 13f, 0xFF997733.toInt())
        column.addView(lendView)

        // ── 操作：三个按钮并排一行，下面整块留给“说句实话” ──
        column.addView(divider(density, "操作"))
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun rowButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 14f
            setAllCaps(false)
            minWidth = 0
            minimumWidth = 0
            setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                }
            setOnClickListener { action() }
        }
        actionRow.addView(rowButton("切换引擎") { pickEngine() })
        actionRow.addView(rowButton("加载开局库") { pickBook() })
        actionRow.addView(rowButton("进入主界面") { finish() })
        column.addView(actionRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() })

        // ── 收起说明：随机价值观（15 秒一换）──
        column.addView(divider(density, "说句实话"))
        quoteView = TextView(this).apply {
            setTextColor(0xFFE8D9A8.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(6f, 1f)
            setPadding((16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt())
            minHeight = (78 * density).toInt()
            background = GradientDrawable().apply {
                setColor(0xFF2B180B.toInt())
                cornerRadius = 8 * density
                setStroke((1 * density).toInt(), 0x66E8C44A.toInt())
            }
        }
        column.addView(quoteView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() })

        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        refreshMonitor()
    }

    private fun text(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun divider(density: Float, title: String): TextView = TextView(this).apply {
        text = "─ $title ─"
        setTextColor(0xFFE8C44A.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, (18 * density).toInt(), 0, (4 * density).toInt())
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 16f
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
    }

    // ═════════════════ 状态刷新 ═════════════════

    private fun refreshMonitor() {
        val m = EngineShareServer.shared().monitor()
        statusView?.text = when {
            m.active && m.engineReady ->
                "★ 已连接：对端 ${if (m.peer.isNullOrEmpty()) "（地址未知）" else m.peer}"
            m.active -> "已连接，但引擎未就绪：${m.engineStatus}"
            else -> "未连接（等待对端借引擎）· ${m.engineStatus}"
        }

        if (m.fen.isNotEmpty() && m.fen != lastFen) {
            lastFen = m.fen
            boardView?.setFen(m.fen)
        }
        if (m.best.length >= 4) boardView?.setLastMove(m.best.take(4))
        detailView?.text = when {
            !m.active -> "等待对端发来局面…"
            m.info.isNotEmpty() -> m.info
            m.fen.isNotEmpty() -> "已收到局面，引擎思考中…"
            else -> "已连接，等待对端发来局面…"
        }
        moveView?.text = if (m.best.isNotEmpty()) "引擎着法：${m.best}" else ""
        lastLend = m.lend
        lendView?.text = "当前出借：" + m.lend + "\n引擎：" + m.engineStatus +
            (bookBadge?.let { " · 开局库：$it" } ?: "")
    }

    // ═════════════════ 切换引擎 ═════════════════

    /**
     * ★ 切换引擎：列出内置 + 本机已装载的外置引擎，点谁就采用谁。
     * 采用后会作废热引擎并断开在连对端 —— 对端自动重连、重新握手，拿到新引擎。
     */
    private fun pickEngine() {
        val list = UserEngineStore.list(this)
        if (list.isEmpty()) {
            toast("没有可用引擎")
            return
        }
        val active = UserEngineStore.activeId(this)
        val labels = list.map { entry ->
            val mark = if (entry.id == active) "▶ " else "   "
            val kind = if (entry.isBuiltin) "（内置）" else "（外置）"
            "$mark${entry.displayName}$kind"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("切换引擎（对端会自动跟着换）")
            .setItems(labels) { _, which ->
                val entry = list[which]
                UserEngineStore.setActive(applicationContext, entry.id)
                // ★ 断开发送端刚换下的那台：让对端重连、重新握手，丝滑跟进新引擎
                EngineShareServer.shared().resetEngine()
                EngineShareServer.shared().start(this)
                toast("已切换：${entry.displayName}（对端将自动重连）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ═════════════════ 加载开局库 ═════════════════

    private fun pickBook() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQ_BOOK
            )
        } catch (e: Exception) {
            toast("无法打开文件选择器：${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BOOK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            toast("已取消")
            return
        }
        Thread {
            try {
                // 先落到私有目录，再按路径装载（OBK 等大库走流式，不整本进内存）
                val dir = File(filesDir, "books").apply { mkdirs() }
                val name = queryDisplayName(uri) ?: "book_${System.currentTimeMillis()}"
                val target = File(dir, name.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_"))
                contentResolver.openInputStream(uri)?.use { ins ->
                    target.outputStream().use { out -> ins.copyTo(out) }
                }
                val loaded = BookVault.manager()
                    .loadBookFromPath(target.name.substringBeforeLast('.'), target.absolutePath)
                mainHandler.post {
                    if (loaded != null) {
                        // ★ 实验：发送端正反馈 —— “当前出借”一行变蓝并显示库名
                        bookBadge = loaded.name
                        lendView?.setTextColor(0xFF4FC3F7.toInt())
                        lendView?.text = "当前出借：${lastLend} · 开局库：${loaded.name}"
                        toast("开局库已加载：${loaded.name}（蓝色标识=已生效）")
                    } else toast("这个文件没能识别成开局库")
                }
            } catch (e: Exception) {
                mainHandler.post { toast("加载失败：${e.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = try {
        contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 随机换一条价值观：15 秒一次，淡出淡入，尽量不连着重复。 */
    private fun showQuote() {
        val v = quoteView ?: return
        val size = quotes.size
        if (size == 0) return
        var i = Random.nextInt(size)
        if (size > 1 && i == lastQuote) i = (i + 1) % size
        lastQuote = i
        v.animate().alpha(0f).setDuration(200).withEndAction {
            v.text = quotes[i]
            v.animate().alpha(1f).setDuration(400).start()
        }.start()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
}

/**
 * ★ 会话监视小棋盘：把一条象棋 FEN 画成小棋盘（黑上红下，与 FEN 一致），只读不交互。
 */
private class MonitorBoard(context: Context) : View(context) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A6A3A")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    /** 炮位 / 兵位 / 卒位的四角小角标 */
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A6803F")
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    /** 楚河汉界 */
    private val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A5C30")
        textAlign = Paint.Align.CENTER
    }

    private var cells: Array<CharArray?> = emptyArray()

    fun setLastMove(uci: String) {
        if (uci.length < 4) return
        val f = mapOf('a' to 0, 'b' to 1, 'c' to 2, 'd' to 3, 'e' to 4, 'f' to 5, 'g' to 6, 'h' to 7, 'i' to 8)
        val fx = f[uci[0]] ?: return; val fy = 9 - (uci[1] - '1')
        val tx = f[uci[2]] ?: return; val ty = 9 - (uci[3] - '1')
        lastFromX = fx; lastFromY = fy; lastToX = tx; lastToY = ty
        invalidate()
    }

    fun setFen(fen: String) {
        cells = parseFen(fen)
        invalidate()
    }

    private fun parseFen(fen: String): Array<CharArray?> {
        val head = fen.substringBefore(' ').split('/')
        val rows = arrayOfNulls<CharArray>(10)
        for (r in 0 until minOf(10, head.size)) {
            val row = CharArray(9) { ' ' }
            var col = 0
            for (ch in head[r]) {
                if (ch.isDigit()) col += (ch - '0') else if (col < 9) row[col++] = ch
            }
            rows[r] = row
        }
        return rows
    }

    private var lastFromX = -1
    private var lastFromY = -1
    private var lastToX = -1
    private var lastToY = -1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cols = 9
        val rows = 10
        val pad = width * 0.06f
        val cw = (width - pad * 2) / (cols - 1)
        val ch = (height - pad * 2) / (rows - 1)
        for (i in 0 until rows) {
            canvas.drawLine(pad, pad + i * ch, width - pad, pad + i * ch, gridPaint)
        }
        for (j in 0 until cols) {
            val x = pad + j * cw
            canvas.drawLine(x, pad, x, pad + 4 * ch, gridPaint)
            canvas.drawLine(x, pad + 5 * ch, x, height - pad, gridPaint)
        }
        drawPalace(canvas, pad, cw, ch)
        drawPositionMarks(canvas, pad, cw, ch)
        drawRiver(canvas, pad, cw, ch)
        if (cells.isEmpty() || cells.size < 10) {
            textPaint.color = Color.parseColor("#997733")
            textPaint.textSize = height * 0.08f
            canvas.drawText("（暂无局面）", width / 2f, height / 2f, textPaint)
            return
        }
        textPaint.textSize = minOf(cw, ch) * 0.9f
        val r = minOf(cw, ch) * 0.44f
        for (row in 0 until rows) {
            val rowArr = cells[row] ?: continue
            for (col in 0 until cols) {
                val p = rowArr[col]
                if (p == ' ') continue
                val cx = pad + col * cw
                val cy = pad + row * ch
                val isRed = p.isUpperCase()
                fillPaint.color = Color.parseColor("#F2E3B0")
                canvas.drawCircle(cx, cy, r, fillPaint)
                fillPaint.color = if (isRed) Color.parseColor("#C0392B") else Color.parseColor("#222222")
                canvas.drawCircle(cx, cy, r, fillPaint)
                textPaint.color = Color.WHITE
                canvas.drawText(nameOf(p), cx, cy + textPaint.textSize * 0.36f, textPaint)
            }
        }
    }

    /** 九宫格：双方各两条斜对角线（列 3–5，黑 0–2 行、红 7–9 行）。 */
    private fun drawPalace(canvas: Canvas, pad: Float, cw: Float, ch: Float) {
        for (top in intArrayOf(0, 7)) {
            val x1 = pad + 3 * cw
            val x2 = pad + 5 * cw
            val y1 = pad + top * ch
            val y2 = pad + (top + 2) * ch
            canvas.drawLine(x1, y1, x2, y2, gridPaint)
            canvas.drawLine(x2, y1, x1, y2, gridPaint)
        }
    }

    /**
     * 炮位（列 1、7；行 2、7）与兵位/卒位（行 3、6；列 0、2、4、6、8）的定位角标。
     * 与主棋盘 BoardDecor 同一套几何：直角顶点贴着交叉点，两臂朝外张开；
     * 边线上的点只画朝内两角。
     */
    private fun drawPositionMarks(canvas: Canvas, pad: Float, cw: Float, ch: Float) {
        val points = ArrayList<IntArray>(14)
        for (c in intArrayOf(1, 7)) for (r in intArrayOf(2, 7)) points.add(intArrayOf(c, r))
        for (r in intArrayOf(3, 6)) for (c in intArrayOf(0, 2, 4, 6, 8)) points.add(intArrayOf(c, r))

        val gapX = cw * 0.05f
        val gapY = ch * 0.05f
        val armX = cw * 0.19f
        val armY = ch * 0.19f
        for (p in points) {
            val x = pad + p[0] * cw
            val y = pad + p[1] * ch
            if (p[0] > 0) {
                // 左上角
                canvas.drawLine(x - gapX, y - gapY, x - gapX - armX, y - gapY, markPaint)
                canvas.drawLine(x - gapX, y - gapY, x - gapX, y - gapY - armY, markPaint)
                // 左下角
                canvas.drawLine(x - gapX, y + gapY, x - gapX - armX, y + gapY, markPaint)
                canvas.drawLine(x - gapX, y + gapY, x - gapX, y + gapY + armY, markPaint)
            }
            if (p[0] < 8) {
                // 右上角
                canvas.drawLine(x + gapX, y - gapY, x + gapX + armX, y - gapY, markPaint)
                canvas.drawLine(x + gapX, y - gapY, x + gapX, y - gapY - armY, markPaint)
                // 右下角
                canvas.drawLine(x + gapX, y + gapY, x + gapX + armX, y + gapY, markPaint)
                canvas.drawLine(x + gapX, y + gapY, x + gapX, y + gapY + armY, markPaint)
            }
        }
    }

    /** 楚河 · 汉界。 */
    private fun drawRiver(canvas: Canvas, pad: Float, cw: Float, ch: Float) {
        riverPaint.textSize = ch * 0.40f
        val y = pad + 4.5f * ch + ch * 0.14f
        canvas.drawText("楚 河", pad + 2 * cw, y, riverPaint)
        canvas.drawText("汉 界", pad + 6 * cw, y, riverPaint)
    }

    private fun nameOf(p: Char): String {
        val red = p.isUpperCase()
        return when (p.uppercaseChar()) {
            'K' -> if (red) "帥" else "將"
            'A' -> if (red) "仕" else "士"
            'B', 'E' -> if (red) "相" else "象"
            'N', 'H' -> "馬"
            'R' -> "車"
            'C' -> "炮"
            'P' -> if (red) "兵" else "卒"
            else -> "?"
        }
    }
}
