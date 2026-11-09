package com.pikafish.chess.duel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yk.xiangqi.core.Move
import com.yk.xiangqi.core.Position
import org.json.JSONArray
import org.json.JSONObject

/**
 * 双人对战 · 房主独裁版（第 0–13 条）。
 *
 * 第 7 条：所有对局设置（颜色/时限/开局）只有房主能定；客人端一律只读。
 * 第 8 条：房主改设置立刻广播 setup，客人据此写死 guestColor，控件禁用。
 * 第 9 条：start 只由房主发；客人没有开局按钮；start 前棋盘不可点。
 * 第 10 条：禁止静默 RESET；currentTurn 只在本机走子成功、或收到对方 move 成功时变化；
 *          重开必须走「房主要求重开 → 对方同意」。
 * 第 11 条：计时权威在房主，每秒广播 clock，客人只渲染。
 * 第 12 条：开局交换颜色，若相同立即断开报错。
 * 第 13 条：每条消息 [SEND]/[RECV] 留痕。
 */
class DuelActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val TAG = "DUEL"

    private var host: DuelNet.Host? = null
    private var guest: DuelNet.Guest? = null
    private var code = ""
    private var isHost = false

    private var hostColor = "red"          // 只有房主能改
    private var minutes = 5                // 只有房主能改
    private var myColor = "red"
    // ★ 音效：与主界面对弈完全同源（同一个 SoundManager / 同一套资源）
    private val sound = com.pikafish.chess.utils.SoundManager()            // 房主=hostColor，客人=反色
    private var started = false

    private var fen: String = Position.start().toFen()
    private var position: Position = Position.start()
    private val moves = ArrayList<String>()
    private var currentTurn = "red"

    private var redSec = 300
    private var blackSec = 300

    /** ★ 续盘锁定：为 true 时，建房/加入房间都不得重置局面。 */
    private var resumed = false
    /** ★ 续盘锁定模式：只允许「继续续盘 / 取消续盘」，杜绝乱点把局面搞掉。 */
    private var sealMode = false
    private var peerSeal: JSONObject? = null
    private var peerMyColor: String? = null   // 第 12 条：用来做颜色冲突自检
    /** ★ 已发但未收到 ack 的走子消息（防丢包：TCP 也可能因重连/半开而丢写）。 */
    private val pending = HashMap<Int, JSONObject>()

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var clocks: TextView
    private lateinit var boardView: DuelBoard
    private lateinit var hostPanel: LinearLayout
    private lateinit var guestPanel: TextView
    private lateinit var startBtn: Button
    private lateinit var waitText: TextView
    private lateinit var codeEditRef: EditText
    private lateinit var sealPanel: LinearLayout
    private lateinit var sealDetail: TextView
    private lateinit var normalBtns: LinearLayout

    private val peer = object : DuelNet.Peer {
        override fun onLine(line: String) = handle(line)
        override fun onPeerReady() {
            main.post { status.text = "对面已接入" }
            if (resumed) sendSealInfo()
            if (isHost) {
                sendSetup()
                if (started) {
                    // 续盘/进行中：让客人按房主权威序列整盘对齐
                    sendStart(restart = moves.isEmpty())
                    sendSyncState()
                }
            } else {
                sendJson(JSONObject().put("type", "hello").put("code", code))
                sendJson(JSONObject().put("type", "sync_request").put("seq", moves.size))
            }
        }
        override fun onPeerLost(reason: String) {
            main.post { status.text = reason }
        }
    }

    /** 第 11 条：只有房主跑这个计时并广播。 */
    private val hostTicker = object : Runnable {
        override fun run() {
            if (isHost && started) {
                if (currentTurn == "red") redSec = (redSec - 1).coerceAtLeast(0)
                else blackSec = (blackSec - 1).coerceAtLeast(0)
                sendJson(JSONObject().put("type", "clock").put("red", redSec).put("black", blackSec))
                renderClocks(redSec, blackSec)
                // ★ 对账心跳：让"走一步就丢"这类问题在 2 秒内自愈
                sendJson(JSONObject().put("type", "beat").put("n", moves.size))
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        main.postDelayed(hostTicker, 1000)
        askResumeIfAny()
    }

    // ═══════════ 界面（房主有控件，客人只读） ═══════════

    private fun buildUi() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF1E1409.toInt())
        }
        root.addView(TextView(this).apply {
            text = "双人对战（同 WiFi / 热点）"
            textSize = 17f; setTextColor(0xFFE8C44A.toInt()); gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            text = "创建房间，或用房间码加入"
            textSize = 14f; setTextColor(0xFFD8CBA8.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        root.addView(status)
        clocks = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFFFE27A.toInt()); gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(clocks)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = "房间码"; textSize = 13f; setTextColor(0xFFD8CBA8.toInt()); setPadding(0, 0, dp(6), 0)
        })
        val codeEdit = EditText(this).apply {
            hint = "6 位"; textSize = 17f; gravity = Gravity.CENTER
            setTextColor(0xFFFFE27A.toInt()); setBackgroundColor(0x22F0E6D2.toInt())
        }
        codeEditRef = codeEdit
        row.addView(codeEdit, LinearLayout.LayoutParams(0, dp(42), 1f))
        val ipEdit = EditText(this).apply {
            hint = "房主IP(可选)"; textSize = 12f; setSingleLine(true)
            setTextColor(0xFFBFAE8A.toInt()); setBackgroundColor(0x22F0E6D2.toInt()); width = dp(110)
        }
        row.addView(ipEdit)
        root.addView(row)

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        normalBtns = btns
        btns.addView(Button(this).apply {
            text = "创建房间"; textSize = 14f; isAllCaps = false
            setOnClickListener { createRoom(codeEdit) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        btns.addView(Button(this).apply {
            text = "加入房间"; textSize = 14f; isAllCaps = false
            setOnClickListener { joinRoom(codeEdit.text.toString().trim(), ipEdit.text.toString().trim()) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(btns)

        // ★ 续盘专用面板：显示封盘详情，只给两个按钮，不需要输入任何房间码
        sealPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        sealDetail = TextView(this).apply {
            textSize = 13f; setTextColor(0xFFD8CBA8.toInt())
            setBackgroundColor(0x33F0E6D2.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        sealPanel.addView(sealDetail)
        val sealRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sealRow.addView(Button(this).apply {
            text = "继续续盘"; textSize = 14f; isAllCaps = false
            setOnClickListener { continueSeal() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        sealRow.addView(Button(this).apply {
            text = "取消续盘"; textSize = 14f; isAllCaps = false
            setOnClickListener { cancelSeal() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        sealPanel.addView(sealRow)
        root.addView(sealPanel)

        // 第 7/8 条：房主设置面板
        hostPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colorRow.addView(Button(this).apply {
            text = "房主执红（红先）"; textSize = 13f; isAllCaps = false
            setOnClickListener { setHostColor("red") }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        colorRow.addView(Button(this).apply {
            text = "房主执黑（黑后）"; textSize = 13f; isAllCaps = false
            setOnClickListener { setHostColor("black") }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        hostPanel.addView(colorRow)
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, m) in listOf("5分" to 5, "10分" to 10, "15分" to 15, "研究" to 0)) {
            timeRow.addView(Button(this).apply {
                text = label; textSize = 13f; isAllCaps = false
                setOnClickListener { setMinutes(m) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        hostPanel.addView(timeRow)
        root.addView(hostPanel)

        // 第 8 条：客人只读结果
        guestPanel = TextView(this).apply {
            text = "等待房主确定设置…"
            textSize = 13f; setTextColor(0xFFD8CBA8.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            visibility = View.GONE
        }
        root.addView(guestPanel)

        boardView = DuelBoard(this).apply { onMove = { f, t -> onLocalMove(f, t) } }
        root.addView(boardView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(320)
        ).apply { topMargin = dp(4) })

        // 第 9 条：开局按钮只给房主；客人显示灰字
        startBtn = Button(this).apply {
            text = "开局"; textSize = 14f; isAllCaps = false; visibility = View.GONE
            setOnClickListener { hostStart() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(4) })

        waitText = TextView(this).apply {
            text = "等待房主开局…"
            textSize = 13f; setTextColor(0xFF7A6A55.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            visibility = View.GONE
        }
        root.addView(waitText)

        logView = TextView(this).apply {
            textSize = 10f; setTextColor(0xFF9B8B6B.toInt())
            setPadding(dp(2), dp(4), dp(2), 0)
        }
        root.addView(logView)

        root.addView(Button(this).apply {
            text = "退出房间"; textSize = 13f; isAllCaps = false
            setOnClickListener { confirmExit() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(6) })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        main.post {
            logView.text = (msg + "\n" + logView.text).lineSequence().take(8).joinToString("\n")
        }
    }

    private fun roleUi() {
        main.post {
            if (isHost) {
                hostPanel.visibility = View.VISIBLE
                guestPanel.visibility = View.GONE
                startBtn.visibility = View.VISIBLE
                waitText.visibility = View.GONE
            } else {
                hostPanel.visibility = View.GONE
                startBtn.visibility = View.GONE
                waitText.visibility = View.VISIBLE
            }
        }
    }

    private fun showGuestReadonly() {
        val mine = if (myColor == "red") "红" else "黑"
        val time = if (minutes <= 0) "研究（不限时）" else "每方 $minutes 分钟"
        main.post { guestPanel.text = "房主已定：你执$mine · $time" }
    }

    private fun renderClocks(r: Int, b: Int) {
        main.post {
            clocks.visibility = View.VISIBLE
            clocks.text = "红 ${fmt(r)} · 黑 ${fmt(b)}"
        }
    }

    private fun fmt(sec: Int): String = String.format("%02d:%02d", sec / 60, sec % 60)

    // ═══════════ 房间 ═══════════

    private fun createRoom(codeEdit: EditText) {
        leave()
        isHost = true
        if (!resumed) {
            code = DuelNet.newCode()
            hostColor = "red"; minutes = 5; myColor = "red"
        }
        // 续盘：沿用封盘时的原配对码，绝不另生成
        codeEdit.setText(code)
        runCatching { DuelStore.bindColor(this, code, DeviceCode.of(this), myColor) }
        val h = DuelNet.Host(code, android.os.Build.MODEL.take(12))
        if (h.start(peer) == null) { status.text = "建房失败：端口 45331 被占用"; return }
        host = h
        roleUi()
        if (resumed) {
            // ★ 续盘已勾住：沿用原码、沿用原颜色，绝不刷新局面
            hostColor = myColor
            main.post { boardView.setPosition(position) }
            status.text = "房间 $code 已创建（续盘第 ${moves.size} 步），等对手加入"
            log("[RESUME] 建房保持续盘局面：${moves.size} 步，轮到${if (currentTurn == "red") "红方" else "黑方"}")
        } else {
            boardView.flipped = false
            myColor = "red"
            hostColor = "red"
            status.text = "房间 $code 已创建，等对手加入（你执红 · 5 分钟）"
            val p0 = resetPosition()
            main.post { boardView.setPosition(p0) }
            log("[RESET] reason=建房 source=host")
        }
        log("本机 IP " + DuelNet.localIp(this))
    }

    private fun joinRoom(input: String, ip: String) {
        if (input.isBlank()) { Toast.makeText(this, "请填房间码", Toast.LENGTH_SHORT).show(); return }
        leave()
        isHost = false
        code = input
        // ★ 强绑定优先：同一房间 + 同一原始码 → 强制还原颜色（杜绝两人同色）
        myColor = runCatching { DuelStore.boundColor(this, input, DeviceCode.of(this)) }
            .getOrNull() ?: "black"
        val g = DuelNet.Guest(code, android.os.Build.MODEL.take(12), ip.ifBlank { null })
        guest = g
        g.start(peer)
        boardView.flipped = true
        roleUi()
        if (resumed) {
            main.post { boardView.setPosition(position) }
            status.text = "正在找房主…（续盘第 ${moves.size} 步）"
        } else {
            val p0 = resetPosition()
            main.post { boardView.setPosition(p0) }
            status.text = "正在找房主…"
        }
    }

    private fun setHostColor(c: String) {
        if (!isHost) return
        hostColor = c
        myColor = c
        boardView.flipped = c == "black"
        sendSetup()
        status.text = "你执" + if (c == "red") "红（先走）" else "黑（后走）"
    }

    private fun setMinutes(m: Int) {
        if (!isHost) return
        minutes = m
        redSec = if (m <= 0) 0 else m * 60
        blackSec = redSec
        sendSetup()
        status.text = if (m <= 0) "研究模式（不限时）" else "每方 $m 分钟"
        renderClocks(redSec, blackSec)
    }

    private fun sendSetup() {
        // 第 11 条：时限改了要把钟一起发
        sendJson(JSONObject()
            .put("type", "setup")
            .put("hostColor", hostColor)
            .put("minutes", minutes)
            .put("red", redSec).put("black", blackSec))
    }

    private fun hostStart() {
        if (!isHost) return
        if (moves.isNotEmpty()) {
            // ★ 已走过棋：默认"继续下（续盘）"，重开才需对方同意
            AlertDialog.Builder(this)
                .setTitle("对局进行中（${moves.size} 步）")
                .setMessage("轮到${if (currentTurn == "red") "红方" else "黑方"}走。要怎么做？")
                .setPositiveButton("继续下（续盘）") { _, _ ->
                    sendStart(restart = false)
                    sendSyncState()
                    main.post { status.text = "已续盘：第 ${moves.size} 步" }
                }
                .setNeutralButton("请求重开") { _, _ ->
                    sendJson(JSONObject().put("type", "restart_req"))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        sendStart()
    }

    private fun sendStart(restart: Boolean = false) {
        if (!isHost) return
        started = true
        currentTurn = "red"
        main.post { status.text = "对局开始" }
        sendJson(JSONObject().put("type", "start").put("myColor", myColor).put("restart", restart))
    }

    private fun resetPosition(): Position {
        position = Position.start()
        fen = position.toFen()
        return position
    }

    private fun doReset(reason: String, source: String) {
        // 第 10 条：只允许房主的 start 或双方同意后的重开走到这里
        moves.clear()
        currentTurn = "red"
        // ★ 同步重置（不要放进 post，否则会晚到并覆盖局面）
        val p0 = resetPosition()
        main.post { boardView.setPosition(p0) }
        log("[RESET] reason=$reason source=$source")
    }

    /** 进房时若本机存着未完成的棋谱，主动问要不要续盘。 */
    private fun askResumeIfAny() {
        val snap = DuelStore.load(this) ?: return
        if (snap.moves.isEmpty()) return
        log("[RESUME] 发现未完成棋局 ${snap.moves.size} 步，轮到${snap.nextSideName}")
        AlertDialog.Builder(this)
            .setTitle("发现未完成的棋局")
            .setMessage("上一次下到第 ${snap.moves.size} 步，轮到${snap.nextSideName}走。\n是否续盘？（不续则从开局重下）")
            .setPositiveButton("续盘") { _, _ -> resumeFrom(snap) }
            .setNegativeButton("新对局") { _, _ ->
                DuelStore.clear(this)
                resumed = false
                val p0 = resetPosition()
                moves.clear()
                currentTurn = "red"
                main.post { boardView.setPosition(p0) }
                log("[RESET] reason=放弃续盘 source=host")
            }
            .show()
    }

    /** 按快照重建局面：逐步重放 ICCS，回合由步数推导，绝不错判先手。 */
    private fun resumeFrom(snap: DuelStore.Snapshot) {
        moves.clear()
        val p0 = resetPosition()
        main.post { boardView.setPosition(p0) }
        for (i in snap.moves.indices) {
            val parts = snap.moves[i].split("-")
            if (parts.size != 2) continue
            if (!applyMove(parts[0], parts[1])) {
                log("[RESUME] 第 ${i + 1} 步 ${snap.moves[i]} 无法重放，停止")
                break
            }
        }
        // ★ 封盘签认：原配对码、原颜色，一律强制还原
        if (snap.code.isNotBlank()) {
            code = snap.code
            main.post { codeEditRef.setText(snap.code) }
        }
        myColor = snap.myColor
        minutes = snap.minutes.coerceAtLeast(0)
        redSec = snap.redSec
        blackSec = snap.blackSec
        hostColor = snap.hostColor
        main.post { boardView.flipped = myColor == "black" }
        currentTurn = if (moves.size % 2 == 0) "red" else "black"
        started = true
        resumed = true
        sealMode = true
        enterSealUi(snap)
        log("[RESUME] 已续盘 ${moves.size} 步，轮到${if (currentTurn == "red") "红方" else "黑方"}（我执$myColor）")
        main.post {
            status.text = "已续盘：第 ${moves.size} 步，轮到${if (currentTurn == "red") "红方" else "黑方"}"
            if (!isHost) waitText.text = "已续盘，等待房主确认开局"
            renderClocks(redSec, blackSec)
        }
        saveSnapshot()
    }

    /** 续盘专用界面：显示封盘凭据，隐藏普通建房/加入按钮。 */
    private fun enterSealUi(snap: DuelStore.Snapshot) {
        val next = if (snap.moves.size % 2 == 0) "红方" else "黑方"
        main.post {
            normalBtns.visibility = View.GONE
            hostPanel.visibility = View.GONE
            startBtn.visibility = View.GONE
            waitText.visibility = View.GONE
            sealPanel.visibility = View.VISIBLE
            sealDetail.text = buildString {
                append("续盘凭据\n")
                append("原配对码：").append(snap.code.ifBlank { code }).append("\n")
                append("本机执子：").append(if (snap.myColor == "red") "红方" else "黑方").append("\n")
                append("已记录：").append(snap.moves.size).append(" 步\n")
                append("轮到：").append(next).append(" 走\n")
                append("封盘时间：").append(timeText(snap.savedAt)).append("\n\n")
                append("点「继续续盘」：本机将用原码开/进房间，颜色按签认还原。")
            }
            codeEditRef.setText(snap.code)
        }
    }

    /** 继续续盘：房主用原码建房，客人用原码加入（都不需要手动输入）。 */
    private fun continueSeal() {
        if (!resumed) return
        if (isHost) createRoom(codeEditRef) else joinRoom(code.ifBlank { codeEditRef.text.toString().trim() }, "")
    }

    /** 取消续盘：必须确认，确认后才清盘回到普通界面。 */
    private fun cancelSeal() {
        AlertDialog.Builder(this)
            .setTitle("取消续盘")
            .setMessage("取消将丢弃这份封盘棋谱，从开局重新开始。确定？")
            .setCancelable(false)
            .setPositiveButton("确定取消") { _, _ ->
                DuelStore.clear(this)
                resumed = false; sealMode = false
                moves.clear(); currentTurn = "red"
                val p0 = resetPosition()
                main.post {
                    boardView.setPosition(p0)
                    sealPanel.visibility = View.GONE
                    normalBtns.visibility = View.VISIBLE
                    roleUi()
                    status.text = "已取消续盘，从开局重新开始"
                }
                log("[RESET] reason=取消续盘 source=host")
            }
            .setNegativeButton("还是续盘", null)
            .show()
    }

    private fun saveSnapshot() {
        if (moves.isEmpty()) { DuelStore.clear(this); return }
        DuelStore.save(this, code, myColor, moves, minutes, redSec, blackSec, hostColor)
    }

    // ═══════════ 第 0/1 条：唯一走子管道 ═══════════

    private fun applyMove(from: String, to: String): Boolean {
        val p = runCatching { Position.fromFen(fen) }.getOrNull() ?: return false
        val f = iccsToSquare(from) ?: return false
        val t = iccsToSquare(to) ?: return false
        val mv = Move(f, t)
        if (!p.legalMoves().contains(mv)) { log("[REJECT] $from-$to 不合法"); return false }
        position = p.play(mv)
        fen = position.toFen()
        moves.add("$from-$to")
        // ★ 落子音效：与主界面对弈同源
        runCatching { sound.play("move") }
        main.post { boardView.setPosition(position) }
        // ★ 每走一步即落盘：无论怎么散场/杀进程，棋谱都不会丢
        DuelStore.save(this, code, myColor, moves, minutes, redSec, blackSec, hostColor)
        return true
    }

    private fun iccsToSquare(s: String): Int? {
        if (s.length < 2) return null
        val file = s[0] - 'a'
        val rank = s[1] - '0'
        if (file !in 0..8 || rank !in 0..9) return null
        return rank * 9 + file
    }

    private fun other(c: String) = if (c == "red") "black" else "red"

    private fun setTurn(c: String, why: String) {
        if (c == currentTurn) return
        log("[TURN] $currentTurn -> $c, by $why")
        currentTurn = c
        main.post { status.text = if (currentTurn == myColor) "轮到你了" else "轮到对方" }
    }

    /**
     * ★★★ 回合不是"设"出来的，而是由步数唯一推导：偶数步轮到红、奇数步轮到黑。
     * 这样只要两边的走子记录一致，回合就一定一致——再也没有"走一步就卡死"。
     */
    private fun refreshTurn(why: String) {
        setTurn(if (moves.size % 2 == 0) "red" else "black", "$why (moves=${moves.size})")
    }

    private fun onLocalMove(from: String, to: String) {
        log("[CLICK] $from-$to  mine=$myColor turn=$currentTurn moves=${moves.size} started=$started")
        if (!started) {
            log("[SKIP] 未开局")
            main.post { status.text = "还没开局：等对方/房主点「开始」" }
            return
        }
        if (currentTurn != myColor) {
            log("[SKIP] 非本机回合")
            main.post {
                status.text = "现在轮到${if (currentTurn == "red") "红方" else "黑方"}，" +
                    "你执${if (myColor == "red") "红" else "黑"}；不可代走"
            }
            return
        }
        if (!applyMove(from, to)) return
        val seq = moves.size
        val msg = JSONObject()
            .put("type", "move")
            .put("from", from).put("to", to)
            .put("seq", seq).put("side", myColor)
        sendReliable(seq, msg)
        refreshTurn("local move")
    }

    /** 可靠发送：登记后 800ms 未见 ack 就重发，最多 4 次，最后退回整局对账。 */
    private fun sendReliable(seq: Int, msg: JSONObject) {
        pending[seq] = msg
        sendJson(msg)
        var tries = 0
        val task = object : Runnable {
            override fun run() {
                if (!pending.containsKey(seq)) return
                tries++
                if (tries > 4) {
                    pending.remove(seq)
                    log("[RETRY] seq=$seq 未确认，请求补谱")
                    sendJson(JSONObject().put("type", "sync_request").put("seq", moves.size))
                    return
                }
                log("[RETRY] seq=$seq 第 $tries 次重发")
                sendJson(msg)
                main.postDelayed(this, 800)
            }
        }
        main.postDelayed(task, 800)
    }

    // ═══════════ 收消息 ═══════════

    private fun sendJson(o: JSONObject) {
        val line = o.toString()
        log("[SEND] $line")
        if (isHost) host?.send(line) else guest?.send(line)
    }

    private fun handle(line: String) {
        log("[RECV] $line")
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "setup" -> {
                if (isHost) return
                hostColor = obj.optString("hostColor", "red")
                minutes = obj.optInt("minutes", 5)
                val derived = if (hostColor == "red") "black" else "red"
                if (resumed && derived != myColor) {
                    log("[SEAL-MISMATCH] 签认颜色=${myColor} 但房主分配=$derived，拒绝落子")
                    main.post { status.text = "续盘签认不一致：颜色分配与封盘不符，请核对房间码" }
                    return
                }
                myColor = derived   // 第 8 条：写死
                redSec = obj.optInt("red", if (minutes <= 0) 0 else minutes * 60)
                blackSec = obj.optInt("black", redSec)
                main.post { boardView.flipped = myColor == "black" }
                showGuestReadonly()
                renderClocks(redSec, blackSec)
            }
            "start" -> {
                if (isHost) return   // 第 9 条：start 只由房主发
                // 第 12 条：交换颜色，发现同色立即断开
                val hostSide = obj.optString("myColor", "")
                if (hostSide.isNotEmpty() && hostSide == myColor) {
                    log("[FATAL] 颜色分配冲突：双方都是 $myColor")
                    Toast.makeText(this, "颜色分配冲突：程序有 bug，已断开", Toast.LENGTH_LONG).show()
                    leave()
                    main.post { status.text = "颜色分配冲突：程序有 bug，已断开" }
                    return
                }
                sendJson(JSONObject().put("type", "color_ack").put("myColor", myColor))
                started = true
                currentTurn = "red"
                val isRestart = obj.optBoolean("restart", false)
                // 第 10 条：房主主动重开 → 由房主的 start 触发清盘；否则若已走过棋需先确认
                if (isRestart) {
                    doReset("房主重开", "host")
                } else if (moves.size > 0) {
                    // 重开必须由房主以 restart=true 明确发起；这里的 start 视为续局：不清盘，只补谱。
                    log("[START] 续局：保留已走 ${moves.size} 步")
                    sendJson(JSONObject().put("type", "sync_request").put("seq", moves.size))
                } else {
                    doReset("房主开局", "host")
                    main.post { waitText.text = "对局已开始" }
                }
                main.post { status.text = "对局开始" }
            }
            "move" -> {
                val from = obj.optString("from")
                val to = obj.optString("to")
                val seq = obj.optInt("seq", -1)
                // 重复包（对方没收到 ack 又重发）：直接再回一次 ack，绝不重复落子
                if (seq <= moves.size) {
                    log("[DUP] seq=$seq 已应用，回 ack")
                    sendJson(JSONObject().put("type", "ack").put("seq", seq))
                    return
                }
                // 缺口：让发方把它缺的那几步补发过来（不是干等）
                if (seq > moves.size + 1) {
                    log("[GAP] seq=$seq 本地=${moves.size}，请对方补发")
                    sendJson(JSONObject().put("type", "need").put("from", moves.size))
                    return
                }
                if (!applyMove(from, to)) {
                    sendJson(JSONObject().put("type", "need").put("from", moves.size))
                    return
                }
                sendJson(JSONObject().put("type", "ack").put("seq", seq))
                refreshTurn("remote move $seq")
            }
            "ack" -> {
                val seq = obj.optInt("seq", -1)
                pending.remove(seq)
            }
            "need" -> {
                // 对方缺从 from 开始的棋：把我已走的这些按原样补发
                val from = obj.optInt("from", 0)
                log("[RESEND] 从第 ${from + 1} 步补发到 ${moves.size}")
                for (i in from until moves.size) {
                    val iccs = moves[i]
                    val parts = iccs.split("-")
                    if (parts.size != 2) continue
                    val seq = i + 1
                    sendJson(JSONObject()
                        .put("type", "move")
                        .put("from", parts[0]).put("to", parts[1])
                        .put("seq", seq)
                        .put("side", if (seq % 2 == 1) "red" else "black"))
                }
            }
            "clock" -> {
                if (isHost) return   // 第 11 条：只渲染
                redSec = obj.optInt("red", 0)
                blackSec = obj.optInt("black", 0)
                renderClocks(redSec, blackSec)
            }
            "restart_req" -> {
                if (isHost) return
                main.post {
                    AlertDialog.Builder(this)
                        .setTitle("房主要求重开")
                        .setMessage("同意从开局面重下吗？")
                        .setPositiveButton("同意") { _, _ ->
                            // ★ 客人只回同意，绝不在本地清盘（清盘必须由房主的 start 触发）
                            sendJson(JSONObject().put("type", "restart_ok"))
                        }
                        .setNegativeButton("不同意", null)
                        .show()
                }
            }
            "restart_ok" -> {
                if (!isHost) return
                doReset("双方同意重开", "host")
                sendStart(restart = true)
            }
            "color_ack" -> {
                peerMyColor = obj.optString("myColor")
                // 第 12 条：颜色冲突自检
                if (peerMyColor == myColor) {
                    log("[FATAL] 颜色分配冲突：双方都是 $myColor")
                    Toast.makeText(this, "颜色分配冲突：程序有 bug，已断开", Toast.LENGTH_LONG).show()
                    leave()
                    main.post { status.text = "颜色分配冲突：程序有 bug，已断开" }
                }
            }
            "beat" -> {
                if (isHost) return
                val n = obj.optInt("n", -1)
                if (n >= 0 && n != moves.size) {
                    log("[BEAT] 房主=${n} 本地=${moves.size}，请求补谱")
                    sendJson(JSONObject().put("type", "sync_request").put("seq", moves.size))
                }
            }
            "seal_info" -> {
                peerSeal = obj
                if (resumed) {
                    val peerCount = obj.optInt("count", moves.size)
                    val peerTime = obj.optLong("savedAt", 0)
                    reconcileSeal(peerCount, peerTime)
                }
            }
            "sync_request" -> {
                val seq = obj.optInt("seq", -1)
                if (isHost && seq > moves.size) {
                    log("[SYNC] 对方超前 seq=$seq 本地=${moves.size}，请其补发")
                    sendJson(JSONObject().put("type", "need").put("from", moves.size))
                } else if (isHost) {
                    sendSyncState()
                }
            }
            "sync_state" -> applySyncState(obj.optJSONArray("moves"))
        }
    }

    /** 把自己的封盘凭据报给对方：原码、我的颜色、我这里记到第几步、封盘时间。 */
    private fun sendSealInfo() {
        val snap = DuelStore.load(this) ?: return
        sendJson(JSONObject()
            .put("type", "seal_info")
            .put("code", snap.code)
            .put("myColor", snap.myColor)
            .put("count", snap.moves.size)
            .put("savedAt", snap.savedAt))
    }

    /**
     * ★ 取大优先：两边步数不同时，以步数多的那份为准；
     *   少的一方主动补齐（房主发 need 让对方补发；客人发 sync_request 拉取）。
     */
    /** ★ 取小优先：以双方共同确认的最大公共步数为准，多出来的（没送到对方的）作废重走。 */
    private fun truncateTo(n: Int) {
        if (n >= moves.size) return
        val keep = ArrayList(moves.subList(0, n))
        moves.clear()
        val p0 = resetPosition()
        main.post { boardView.setPosition(p0) }
        for (m in keep) {
            val parts = m.split("-")
            if (parts.size == 2) applyMove(parts[0], parts[1])
        }
        currentTurn = if (moves.size % 2 == 0) "red" else "black"
        log("[SEAL] 取小优先：裁到 ${moves.size} 步，轮到$currentTurn")
        main.post { status.text = "已按较少步数统一：第 ${moves.size} 步，轮到${if (currentTurn == "red") "红方" else "黑方"}" }
        saveSnapshot()
    }

    private fun reconcileSeal(peerCount: Int, peerTime: Long) {
        val myCount = moves.size
        val myTime = DuelStore.load(this)?.savedAt ?: 0L
        val common = minOf(myCount, peerCount)
        val next = if (common % 2 == 0) "红方" else "黑方"
        val verdict = when {
            myCount > peerCount -> "本机多记了 ${myCount - peerCount} 步（未被对方确认），按取小优先作废"
            myCount < peerCount -> "对方多记了 ${peerCount - myCount} 步（未被本机确认），按取小优先作废"
            else -> "双方记录一致"
        }
        val text = buildString {
            append("原配对码：").append(code).append("\n")
            append("红方封盘时间：").append(timeText(myTime)).append("\n")
            append("对方封盘时间：").append(timeText(peerTime)).append("\n\n")
            append("本机记录：").append(if (myColor == "red") "红" else "黑").append("方 $myCount 步\n")
            append("对方记录：").append(if (myColor == "red") "黑" else "红").append("方 $peerCount 步\n\n")
            append(verdict).append("\n\n")
            append("统一为 ").append(common).append(" 步，轮到 ").append(next).append(" 走")
        }
        log("[SEAL] 对账 我=$myCount 对方=$peerCount -> 取小 $common")
        main.post {
            AlertDialog.Builder(this)
                .setTitle("续盘对账（取大优先）")
                .setMessage(text)
                .setPositiveButton("按此续盘") { _, _ ->
                    if (myCount > peerCount) truncateTo(peerCount)
                    if (sealMode) {
                        main.post {
                            sealDetail.append("\n已统一：${minOf(myCount, peerCount)} 步，轮到${next}走")
                        }
                    }
                }
                .setNegativeButton("核对房间码", null)
                .show()
        }
    }

    private fun timeText(ms: Long): String {
        if (ms <= 0) return "未知"
        val f = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return f.format(java.util.Date(ms))
    }

    private fun sendSyncState() {
        val arr = JSONArray()
        for (m in moves) arr.put(m)
        sendJson(JSONObject().put("type", "sync_state").put("moves", arr))
    }

    private fun applySyncState(arr: JSONArray?) {
        if (arr == null) return
        // ★ 只接受"不比本地更短"的权威序列：更旧的快照一律丢弃，绝不把已走的棋撤回去
        if (arr.length() <= moves.size) {
            // 相同或更旧：本地已在这条线上，不再重放（避免棋盘"闪回/缩回"）
            log("[SYNC] 忽略非更新状态 host=${arr.length()} local=${moves.size}")
            return
        }
        val p0 = resetPosition()          // 同步重置
        main.post { boardView.setPosition(p0) }
        moves.clear()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            val parts = s.split("-")
            if (parts.size != 2) continue
            if (!applyMove(parts[0], parts[1])) break
        }
        started = true
        currentTurn = if (moves.size % 2 == 0) "red" else "black"
        log("[SYNC] 补谱完成 ${moves.size} 步 turn=$currentTurn")
        main.post {
            status.text = "已恢复对局（${moves.size} 步，轮到${if (currentTurn == "red") "红方" else "黑方"}）"
            waitText.text = "对局进行中"
        }
        saveSnapshot()
    }

    /** ★ 任何退出都必须经过确认框，避免"急着撤"把没同步的那步带走。 */
    private fun confirmExit() {
        if (moves.isEmpty()) { leave(); finish(); return }
        AlertDialog.Builder(this)
            .setTitle("确认退出房间")
            .setMessage("当前第 ${moves.size} 步，轮到${if (moves.size % 2 == 0) "红方" else "黑方"}。\n退出会立刻封盘（棋谱已保存），下次可续盘。")
            .setCancelable(false)
            .setPositiveButton("确认退出") { _, _ -> leave(); finish() }
            .setNegativeButton("继续下棋", null)
            .show()
    }

    override fun onBackPressed() {
        confirmExit()   // ★ 返回键也拦下来
    }

    private fun leave() {
        saveSnapshot()   // ★ 散场即封盘
        if (moves.isNotEmpty()) {
            log("[SEAL] code=$code myColor=$myColor moves=${moves.size} next=${if (moves.size % 2 == 0) "red" else "black"}")
        }
        host?.stop(); host = null
        guest?.stop(); guest = null
    }

    override fun onPause() {
        super.onPause()
        // ★ 随记随存：切后台/强退前也把棋谱钉住
        if (moves.isNotEmpty()) {
            saveSnapshot()
            log("[SEAL] onPause code=$code myColor=$myColor moves=${moves.size}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        leave()
    }
}

/**
 * 棋盘：按 FEN 画（黑上红下，方向恒定）；点击换算成 ICCS。
 * 执黑时整体旋转 180°（行列同镜像），自己那方永远在下方且左右不颠倒。
 */
class DuelBoard(context: Context) : View(context) {

    private var position: Position = Position.start()
    private var fenRows: Array<CharArray?> = arrayOfNulls(10)
    private var selected: String? = null
    var flipped = false
    var onMove: ((String, String) -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A6A3A"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66FFB300") }

    fun setPosition(p: Position) {
        position = p
        fenRows = parseFen(p.toFen())
        invalidate()
    }

    private fun parseFen(fen: String): Array<CharArray?> {
        val rows = arrayOfNulls<CharArray>(10)
        val head = fen.substringBefore(' ').split('/')
        for (r in 0 until minOf(10, head.size)) {
            val row = CharArray(9) { ' ' }
            var col = 0
            for (ch in head[r]) {
                if (ch.isDigit()) col += ch - '0' else if (col < 9) row[col++] = ch
            }
            rows[r] = row
        }
        return rows
    }

    private fun metrics(): Triple<Float, Float, Float> {
        val pad = width * 0.055f
        return Triple(pad, (width - pad * 2) / 8f, (height - pad * 2) / 9f)
    }

    private fun toScreen(rank: Int, file: Int): Pair<Int, Int> =
        if (flipped) rank to (8 - file) else (9 - rank) to file

    private fun toIccs(screenRow: Int, screenCol: Int): Pair<Int, Int> =
        if (flipped) screenRow to (8 - screenCol) else (9 - screenRow) to screenCol

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (pad, cw, ch) = metrics()
        for (r in 0..9) canvas.drawLine(pad, pad + r * ch, pad + 8 * cw, pad + r * ch, line)
        for (c in 0..8) {
            val x = pad + c * cw
            canvas.drawLine(x, pad, x, pad + 4 * ch, line)
            canvas.drawLine(x, pad + 5 * ch, x, pad + 9 * ch, line)
        }
        canvas.drawLine(pad + 3 * cw, pad, pad + 5 * cw, pad + 2 * ch, line)
        canvas.drawLine(pad + 5 * cw, pad, pad + 3 * cw, pad + 2 * ch, line)
        canvas.drawLine(pad + 3 * cw, pad + 7 * ch, pad + 5 * cw, pad + 9 * ch, line)
        canvas.drawLine(pad + 5 * cw, pad + 7 * ch, pad + 3 * cw, pad + 9 * ch, line)

        selected?.let { s ->
            val f = s[0] - 'a'; val rk = s[1] - '0'
            val (sr, sc) = toScreen(rk, f)
            canvas.drawCircle(pad + sc * cw, pad + sr * ch, minOf(cw, ch) * 0.5f, mark)
        }

        for (fenRow in 0 until 10) {
            for (file in 0 until 9) {
                val p = fenRows.getOrNull(fenRow)?.getOrNull(file) ?: ' '
                if (p == ' ') continue
                val rank = 9 - fenRow
                val (sr, sc) = toScreen(rank, file)
                val cx = pad + sc * cw
                val cy = pad + sr * ch
                val r = minOf(cw, ch) * 0.46f
                val isRed = p.isUpperCase()
                fill.color = Color.parseColor(if (isRed) "#B33B2E" else "#2A2A2A")
                canvas.drawCircle(cx, cy, r, fill)
                fill.color = Color.parseColor("#F9EFD2")
                canvas.drawCircle(cx, cy, r * 0.82f, fill)
                txt.color = if (isRed) Color.parseColor("#B33B2E") else Color.parseColor("#222222")
                txt.textSize = r * 1.05f
                canvas.drawText(nameOf(p), cx, cy + txt.textSize * 0.34f, txt)
            }
        }
    }

    private fun nameOf(p: Char): String {
        val red = p.isUpperCase()
        return when (p.uppercaseChar()) {
            'K' -> if (red) "帥" else "將"
            'A' -> if (red) "仕" else "士"
            'B', 'E' -> if (red) "相" else "象"
            'R' -> "車"
            'N', 'H' -> "馬"
            'C' -> if (red) "炮" else "砲"
            'P' -> if (red) "兵" else "卒"
            else -> "?"
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val (pad, cw, ch) = metrics()
        val col = Math.round((event.x - pad) / cw)
        val row = Math.round((event.y - pad) / ch)
        if (col !in 0..8 || row !in 0..9) return true
        val (rank, file) = toIccs(row, col)
        val iccs = "" + ('a' + file) + ('0' + rank)

        val cur = selected
        if (cur == null) {
            val p = fenRows.getOrNull(9 - rank)?.getOrNull(file) ?: ' '
            if (p != ' ') { selected = iccs; invalidate() }
        } else {
            if (cur == iccs) { selected = null; invalidate(); return true }
            selected = null
            onMove?.invoke(cur, iccs)
            invalidate()
        }
        return true
    }
}
