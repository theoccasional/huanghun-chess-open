package com.pikafish.chess

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pikafish.chess.heavy.EngineShareServer
import com.pikafish.chess.heavy.HeavyBeacon
import com.pikafish.chess.heavy.HeavyHttpServer
import com.pikafish.chess.heavy.PairingCode
import java.io.File

/**
 * ★★★ 「黄昏象棋辎重包」：打开即出一个 6 位配对码，另一台设备的操作包填上去就成。★★★
 *
 * <p>本页就是「辎重包」的手机形态。它不玩棋，做这几件事：</p>
 * <ol>
 *   <li>在屏幕上显示一个 6 位配对码；</li>
 *   <li>把本 APK 里带着的全套大件（引擎权重 / 识别模型 / 棋库）在局域网里共享出去；</li>
 *   <li>把本机这台引擎也借出去 —— 对端选「异地引擎」时，算棋全在本机跑，对端只收着法。</li>
 * </ol>
 *
 * <p>★★★ 本版新增「会话监视」：把借出会话摊到明面上。★★★ 借出时对端发来的 {@code position fen …}
 * 会被解析成一张小棋盘；引擎的思考（深度/评分/主变）与着法、以及连接状态与对端地址，都实时显示。
 * 这样主机端一眼就能看清：对端到底发了什么局面、本机引擎到底想没想、想出了什么——把排查工作
 * 从"黑夜里摸瞎"搬到"灯下看摊"，不再囫囵吞枣。</p>
 *
 * <p>仅在全量包（full）里注册为独立启动图标；轻量操作包（slim）不含本页。</p>
 */
class BoxActivity : Activity() {

    private var code: String = ""
    private var serving = false
    private var enginePort = 0

    private var statusView: TextView? = null
    private var detailView: TextView? = null
    private var moveView: TextView? = null
    private var lendView: TextView? = null
    private var boardView: MonitorBoardView? = null
    private var lastFen = ""

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshMonitor()
            handler.postDelayed(this, 500)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 常亮：配对期间屏幕不能黑，否则对端可能连不上。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        code = PairingCode.generate()

        // 共享本 APK 作为辎重包（其内部 assets/ 即全部大件），并广播配对码。
        try {
            val apk = File(applicationInfo.sourceDir)
            HeavyHttpServer.shared().start(apk)
            // ★ 同时对外借出本机引擎（供对端选「异地引擎」时替它算棋）。
            enginePort = try {
                EngineShareServer.shared().start(this)
            } catch (e: Exception) {
                0
            }
            HeavyBeacon.startResponder(code, HeavyHttpServer.shared().port(), enginePort)
            serving = true
        } catch (e: Exception) {
            serving = false
        }

        buildUi()
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
        HeavyBeacon.stopResponder()
        HeavyHttpServer.shared().stop()
        EngineShareServer.shared().stop()
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

        column.addView(TextView(this).apply {
            text = "黄昏象棋辎重包"
            setTextColor(0xFFE8C44A.toInt())
            textSize = 24f
            gravity = Gravity.CENTER
        })
        column.addView(TextView(this).apply {
            text = "把下面的配对码，填到要玩的那台手机的「黄昏象棋」里"
            setTextColor(0xFFCCBB88.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = PairingCode.spaced(code)
            setTextColor(0xFFFFE27A.toInt())
            textSize = 46f
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(0, (16 * density).toInt(), 0, (10 * density).toInt())
        })
        column.addView(TextView(this).apply {
            text = if (serving)
                "保持本页面打开（屏幕亮着）·两台设备连同一个 WiFi\n" +
                    (if (enginePort > 0) "本机引擎已开启（端口 $enginePort）；对端可选「异地引擎」替它算棋"
                    else "本机引擎未就绪")
            else
                "共享服务未能启动，请退出后重进本页再试"
            setTextColor(0xFF997733.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        })

        // ── 会话监视 ──
        column.addView(divider(density, "会话监视（对端借引擎时，这里实时显示）"))
        statusView = TextView(this).apply {
            text = "未连接"
            setTextColor(0xFFFFD37A.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
        }
        column.addView(statusView)

        boardView = MonitorBoardView(this)
        val boardSide = (240 * density).toInt()
        column.addView(boardView, LinearLayout.LayoutParams(boardSide, (boardSide * 11 / 10))
            .apply { topMargin = (8 * density).toInt() })

        detailView = TextView(this).apply {
            text = "等待对端发来局面…"
            setTextColor(0xFFE8D9A8.toInt())
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        column.addView(detailView)

        moveView = TextView(this).apply {
            text = ""
            setTextColor(0xFF9CCBFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        column.addView(moveView)

        // ── 出借引擎（说明，供明察秋毫）──
        column.addView(divider(density, "出借引擎"))
        lendView = TextView(this).apply {
            setTextColor(0xFF997733.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        column.addView(lendView)

        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        refreshMonitor()
    }

    private fun divider(density: Float, title: String): TextView = TextView(this).apply {
        text = "─ $title ─"
        setTextColor(0xFFE8C44A.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, (18 * density).toInt(), 0, (4 * density).toInt())
    }

    /** 每 0.5s 拉一次会话快照，把对端局面 / 引擎思考 / 着法 / 连接状态摆到界面上。 */
    private fun refreshMonitor() {
        val m = EngineShareServer.shared().monitor()
        statusView?.text = if (m.active)
            "★ 已连接：对端 ${if (m.peer.isNullOrEmpty()) "（地址未知）" else m.peer}"
        else "未连接（等待对端借引擎）"

        if (m.fen.isNotEmpty() && m.fen != lastFen) {
            lastFen = m.fen
            boardView?.setFen(m.fen)
        }
        detailView?.text = when {
            !m.active -> "等待对端发来局面…"
            m.info.isNotEmpty() -> m.info
            m.fen.isNotEmpty() -> "已收到局面，引擎思考中…"
            else -> "已连接，等待对端发来局面…"
        }
        moveView?.text = if (m.best.isNotEmpty()) "引擎着法：${m.best}" else ""
        lendView?.text = "当前出借：" + m.lend + "\n" +
            "（＝本机「设置」里采用的那台引擎；接收端会跟着这台走）"
    }
}

/**
 * ★ 会话监视小棋盘：把一条象棋 FEN 直接画成一张小棋盘（黑在上、红在下，与标准 FEN 一致）。
 * 只用于"看清对端到底发了什么局面"，不做交互。
 */
private class MonitorBoardView(context: Context) : View(context) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A6A3A"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    /** 10 行 x 9 列；null = 空。char 为棋子字母（大写=红）。 */
    private var cells: Array<CharArray?> = emptyArray()

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
        // 本视图未知红黑朝向约定，按 FEN 原样（黑上红下）绘制。
        return rows
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cols = 9
        val rows = 10
        val pad = width * 0.06f
        val cw = (width - pad * 2) / (cols - 1)
        val ch = (height - pad * 2) / (rows - 1)
        for (i in 0 until rows) {
            val y = pad + i * ch
            canvas.drawLine(pad, y, width - pad, y, gridPaint)
        }
        for (j in 0 until cols) {
            val x = pad + j * cw
            canvas.drawLine(x, pad, x, pad + 4 * ch, gridPaint)
            canvas.drawLine(x, pad + 5 * ch, x, height - pad, gridPaint)
        }
        if (cells.isEmpty() || cells.size < 10 || cells[0] == null) {
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
                piecePaint.color = if (isRed) Color.parseColor("#F2E3B0") else Color.parseColor("#F2E3B0")
                canvas.drawCircle(cx, cy, r, piecePaint)
                piecePaint.color = if (isRed) Color.parseColor("#C0392B") else Color.parseColor("#222222")
                canvas.drawCircle(cx, cy, r, piecePaint)
                textPaint.color = Color.WHITE
                canvas.drawText(nameOf(p), cx, cy + textPaint.textSize * 0.36f, textPaint)
            }
        }
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
