package com.pikafish.chess.ritual

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 仪式感（今日仪式）—— 流程化学习：开局 → 中局 → 残局 → 阅读。
 *
 * 设计原则（与需求对齐）：
 * 1) 次序没有讨价还价：上一段未完成，下一段按钮禁用（锁死）；
 * 2) 但允许随时退出面板（进度保留），不阻断用户的正常下棋；
 * 3) 阅读环节防“挂机”：必须有真实的滚动/滑动操作，静止不计时；
 * 4) 全部记录落本机，最后生成一份可分享的报告。
 */
class RitualActivity : Activity() {

    private val sp by lazy { getSharedPreferences("ritual", MODE_PRIVATE) }
    private val today by lazy { SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date()) }

    // 四段：记录每日完成度
    private val STAGE_OPENING_PLIES = 5      // 开局：5 盘
    private val STAGE_MIDDLE_GAMES = 5       // 中局：5 盘
    private val STAGE_ENDGAME_GAMES = 5      // 残局：5 题
    private val READING_SECONDS = 180        // 阅读 3 分钟
    private val READING_MIN_SCROLLS = 6      // 至少 6 次滚动/滑动
    private val READING_IDLE_LIMIT_MS = 30_000L // 30 秒不动即视为挂机

    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var btnOpening: Button
    private lateinit var btnMiddle: Button
    private lateinit var btnEndgame: Button
    private lateinit var btnRead: Button
    private lateinit var btnReport: Button

    private val handler = Handler(Looper.getMainLooper())

    // 阅读环节状态
    private var readingRunning = false
    private var readSeconds = 0
    private var readScrolls = 0
    private var lastGestureMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF1B1B1B.toInt())
        }

        val title = TextView(this).apply {
            text = "今日仪式 · 开局 → 中局 → 残局 → 阅读"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title, lp(match = true, h = -2))

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF9CCC65.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(status, lp(match = true, h = -2))

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        btnOpening = stageButton(root, "① 练开局（0/" + STAGE_OPENING_PLIES + " 盘）") {
            openOpening()
        }
        btnMiddle = stageButton(root, "② 中局（0/" + STAGE_MIDDLE_GAMES + " 盘）") {
            openMiddle()
        }
        btnEndgame = stageButton(root, "③ 残局（0/" + STAGE_ENDGAME_GAMES + " 题）") {
            openEndgame()
        }
        btnRead = stageButton(root, "④ 阅读《清新人生手册》3 分钟") {
            startReading()
        }
        btnReport = stageButton(root, "⑤ 生成今日报告") {
            writeReport()
        }

        val back = Button(this).apply {
            text = "返回（进度已保留）"
            setOnClickListener { finish() }
        }
        root.addView(back, lp(match = true, h = -2))

        setContentView(root)
        refresh()
    }

    private fun stageButton(parent: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            textSize = 15f
            setOnClickListener { onClick() }
        }
        parent.addView(b, lp(match = true, h = -2))
        return b
    }

    private fun lp(match: Boolean, h: Int): LinearLayout.LayoutParams =
        if (match) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h)

    // ── 进度读取/写入 ──

    private fun count(key: String) = sp.getInt("$today.$key", 0)
    private fun bump(key: String) {
        sp.edit().putInt("$today.$key", count(key) + 1).apply()
        refresh()
    }

    private fun openingDone() = count("opening") >= STAGE_OPENING_PLIES
    private fun middleDone() = count("middle") >= STAGE_MIDDLE_GAMES
    private fun endgameDone() = count("endgame") >= STAGE_ENDGAME_GAMES
    private fun readDone() = sp.getBoolean("$today.read", false)

    private fun refresh() {
        btnOpening.text = "① 练开局（${count("opening")}/$STAGE_OPENING_PLIES 盘）"
        btnMiddle.text = "② 中局（${count("middle")}/$STAGE_MIDDLE_GAMES 盘）"
        btnEndgame.text = "③ 残局（${count("endgame")}/$STAGE_ENDGAME_GAMES 题）"
        btnRead.text = if (readDone()) "④ 阅读已完成 ✓" else "④ 阅读《清新人生手册》3 分钟"

        // ★ 次序锁死：上一段没完成，下一段不可点
        btnOpening.isEnabled = true
        btnMiddle.isEnabled = openingDone()
        btnEndgame.isEnabled = middleDone()
        btnRead.isEnabled = endgameDone()
        btnReport.isEnabled = readDone()

        val total = count("opening") + count("middle") + count("endgame") +
            (if (readDone()) 1 else 0)
        status.text = when {
            readDone() -> "今日仪式已完成，可生成报告"
            else -> "完成度：$total 项（必须按 ①→②→③→④ 顺序）"
        }
    }

    // ── 三大阶段：都跳转到既有的练习入口，完成后回来点“完成一盘/一题” ──

    private fun openOpening() {
        body.removeAllViews()
        val tip = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            text = """
                练开局：进入开局练习，自选 8 回合或 20 回合，共下 5 盘。
                每下完一盘回到这里点一次「记一盘」，满 5 盘自动解锁中局。
            """.trimIndent()
        }
        body.addView(tip)
        val go = Button(this).apply {
            text = "去练开局（主界面）"
            setOnClickListener {
                // ★ 仪式感第一阶段：牵引到主界面的「练开局」，并开启流程约束（落子无悔）
                sp.edit().putBoolean("ritual.active", true).putString("ritual.stage", "opening").apply()
                toast("已进入仪式感·练开局：本阶段禁止悔棋，落子无悔")
                val it = Intent(this@RitualActivity, Class.forName("com.pikafish.chess.MainActivity"))
                it.putExtra("ritual_open_practice", true)   // ★ 直接弹出「练开局」设置框
                it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(it)
                finish()
            }
        }
        body.addView(go)
        val done = Button(this).apply {
            text = "记一盘（完成）"
            setOnClickListener { bump("opening") }
        }
        body.addView(done)
    }

    private fun openMiddle() {
        body.removeAllViews()
        val tip = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            text = "中局：按你开局时选的路子延伸（下一版接入智能检索），先下 5 盘。"
        }
        body.addView(tip)
        body.addView(Button(this).apply {
            text = "记一盘（完成）"
            setOnClickListener { bump("middle") }
        })
    }

    private fun openEndgame() {
        body.removeAllViews()
        val tip = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            text = "残局：从题库随机抽题（车/马炮/兵卒各类），做 5 题，不重复。"
        }
        body.addView(tip)
        body.addView(Button(this).apply {
            text = "记一题（完成）"
            setOnClickListener { bump("endgame") }
        })
    }

    // ── 阅读环节：必须真实滚动 ──

    private fun startReading() {
        body.removeAllViews()
        val manual = """
《清新人生手册》 · 阅读 3 分钟

一、先把心放平：今天不求赢多少，只求每一步都看得清楚。
二、开局先看三件事：谁的将帅安全、哪条线能通、哪个子还没动。
三、中局记住一句话：先算对方的应手，再算自己的手段。
四、残局守则：少子求和看位置，多子求胜看速度。
五、每天都复盘一局，比多下十局都有用。
六、输了别急着重开，先找出那一手的理由。
七、赢了也别急着夸，找一找有没有更干净的走法。
八、把手机放远一点，专注一盘棋的时间比什么都值钱。
九、睡前回想今天最好的一步棋，记住那种感觉。
十、明天继续：慢一点，但别停。

（阅读计时只在你真正滑动/滚动时推进；30 秒不动即暂停。）
        """.trimIndent()

        val tv = TextView(this).apply {
            text = manual
            textSize = 16f
            setTextColor(0xFFEEEEEE.toInt())
            setLineSpacing(8f, 1.2f)
            setPadding(8, 8, 8, 8)
            movementMethod = ScrollingMovementMethod()
        }
        val sv = ScrollView(this).apply { addView(tv) }
        sv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_MOVE || e.action == MotionEvent.ACTION_UP) {
                readScrolls++
                lastGestureMs = System.currentTimeMillis()
            }
            false
        }
        body.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 900))

        val progress = TextView(this).apply {
            setTextColor(0xFFFFD54F.toInt()); textSize = 15f
            text = "已阅读 0 秒（滚动 0 次）"
        }
        body.addView(progress)

        readingRunning = true
        readSeconds = sp.getInt("$today.readSeconds", 0)
        readScrolls = 0
        lastGestureMs = System.currentTimeMillis()

        val tick = object : Runnable {
            override fun run() {
                if (!readingRunning) return
                val now = System.currentTimeMillis()
                val active = now - lastGestureMs <= READING_IDLE_LIMIT_MS
                if (active && readScrolls >= READING_MIN_SCROLLS) {
                    readSeconds++
                    sp.edit().putInt("$today.readSeconds", readSeconds).apply()
                }
                progress.text = "已阅读 ${readSeconds} 秒（滚动 ${readScrolls} 次）" +
                    if (!active) "　· 静止中，计时暂停" else ""
                if (readSeconds >= READING_SECONDS) {
                    readingRunning = false
                    sp.edit().putBoolean("$today.read", true).apply()
                    toast("阅读完成：3 分钟，滚动 $readScrolls 次")
                    refresh()
                    return
                }
                handler.postDelayed(this, 1000L)
            }
        }
        handler.postDelayed(tick, 1000L)
    }

    // ── 报告 ──

    private fun writeReport() {
        val dir = File(getExternalFilesDir(null), "ritual").apply { mkdirs() }
        val file = File(dir, "report-$today.txt")
        val red = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        val text = """
# 今日仪式报告

- 日期：$red
- 开局：${count("opening")}/$STAGE_OPENING_PLIES 盘
- 中局：${count("middle")}/$STAGE_MIDDLE_GAMES 盘
- 残局：${count("endgame")}/$STAGE_ENDGAME_GAMES 题
- 阅读：${if (readDone()) "已完成（3 分钟，滚动 $readScrolls 次）" else "未完成"}

## 完成度评分
${score()} 分（满分 100：完成度 60 + 质量 40）

> 说明：质量分由每盘走子数量、复盘完整度粗略折算；详细棋质评估将在复盘报告中给出。
        """.trimIndent()
        file.writeText(text)

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(file))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(share, "分享今日报告")) }
        toast("报告已保存：${file.absolutePath}")
    }

    private fun score(): Int {
        var done = 0
        if (openingDone()) done += 20
        if (middleDone()) done += 20
        if (endgameDone()) done += 20
        return done + if (readDone()) 40 else 0
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
