package com.pikafish.chess.duel

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pikafish.chess.model.Board as ModelBoard
import com.pikafish.chess.model.Move as ModelMove
import com.pikafish.chess.model.Piece
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position as ModelPosition
import com.pikafish.chess.ui.BoardVisualPrefs
import com.pikafish.chess.ui.ChessBoardView
import com.yk.xiangqi.core.Move as CoreMove
import com.yk.xiangqi.core.Position as CorePosition
import org.json.JSONArray
import org.json.JSONObject

/**
 * 双人对战 · 挑战模式同款界面（副本）。
 *
 * 设计原则：**挑战模式的文件一个字节都不改**——这里直接只读复用 [ChessBoardView]，
 * 自己新建一套界面（顶栏状态行 + 挑战同款棋盘 + 底部菜单行），把双人的走子/网络接进来。
 * 棋盘绘制、棋子皮肤、棋桌背景全部由 ChessBoardView 自己按用户设置渲染。
 */
class DuelUiActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val TAG = "DUELUI"

    private var host: DuelNet.Host? = null
    private var guest: DuelNet.Guest? = null
    private var code = ""
    private var isHost = false

    private var hostColor = "red"
    private var minutes = 5
    private var myColor = "red"
    private var started = false

    private var fen: String = CorePosition.start().toFen()
    private var position: CorePosition = CorePosition.start()
    private val moves = ArrayList<String>()
    private var currentTurn = "red"
    private var redSec = 300
    private var blackSec = 300
    private val pending = HashMap<Int, JSONObject>()

    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var boardView: ChessBoardView
    private lateinit var codeEdit: EditText
    private lateinit var setupRow: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var boardWrap: FrameLayout
    private lateinit var topInfo: TextView
    private lateinit var cfgPanel: LinearLayout
    private var stepSec = 30
    private var gameOver = false
    private var peerReady = false
    /** ★ 悔棋协商期间双方都停表。 */
    private var paused = false
    private var undoDlg: AlertDialog? = null
    private var undoPending = false
    private var undoDlgShown = false
    private var undoRid = 0L        // 本次悔棋请求的唯一编号
    private var handledRid = 0L     // 已处理过的请求编号（同一个只处理一次）
    /** ★ 棋谱窗：默认固定（可滚屏），点「移动」才可拖。 */
    private var mvDraggable = false
    /** ★ 流程：0=签到 1=选色 2=选时 3=对弈 */
    private var stage = 0
    private var peerSigned = false
    private var roundNo = 1
    /** 当前生效通道：true=本机监听(host)，false=本机主动连(guest) */
    private var useHostChannel = false
    /** 房主是否已开房（业务层房主），房客永远 false */
    private var roomOwner = false
    private var colorMode = "red"   // red / black / random（仅决策者可改）
    /** ★ 决策者：由房间码唯一确定，双方算出的结果必然相同。 */
    private fun iAmDecider(): Boolean {
        val sum = code.filter { it.isDigit() }.sumOf { it - '0' }
        val ownerDecides = sum % 2 == 0
        return if (ownerDecides) roomOwner else !roomOwner
    }
    private var notation = ArrayList<String>()
    private lateinit var movePanel: TextView
    private lateinit var startBtn: Button

    /** 监听通道：对方来连我。 */
    private val peerHost = object : DuelNet.Peer {
        override fun onLine(line: String) = handle(line)
        override fun onPeerReady() { useHostChannel = true; onConnected() }
        override fun onPeerLost(reason: String) {
            peerReady = false; logUi("对手已断开：$reason"); main.post { renderStatus() }
        }
    }

    /** 主动连接通道：我去连对方；一旦通了就关掉自己的监听，避免双通道。 */
    private val peerGuest = object : DuelNet.Peer {
        override fun onLine(line: String) = handle(line)
        override fun onPeerReady() {
            useHostChannel = false
            runCatching { host?.stop(); host = null }
            onConnected()
        }
        override fun onPeerLost(reason: String) {
            peerReady = false; logUi("对手已断开：$reason"); main.post { renderStatus() }
        }
    }

    private val peer = object : DuelNet.Peer {
        override fun onLine(line: String) = handle(line)
        override fun onPeerReady() {
            logUi("对面已接入")
            if (useHostChannel) { sendSetup(); if (started) { sendStart(false); sendSyncState() } }
            else { sendJson(JSONObject().put("type", "hello").put("code", code))
                   sendJson(JSONObject().put("type", "sync_request").put("seq", moves.size)) }
        }
        override fun onPeerLost(reason: String) {
            peerReady = false
            logUi("对手已断开：$reason")
            main.post { renderStatus() }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (roomOwner && peerConnected && phase != "LOBBY") publish()
            if (roomOwner && voteRid != 0L && System.currentTimeMillis() > voteDeadline) {
                sendJson(JSONObject().put("type","undo_done").put("rid",voteRid).put("ok",false))
                voteRid = 0L; voteDeadline = 0L; voteRequester = ""; paused = false
                publish()
            }
            // ★ 兜底：对方定规则但提案一直没到 → 8 秒后自动开局，绝不死等
            if (roomOwner && peerConnected && phase == "SETUP_ASSIGN" && !setterIsOwner) {
                if (waitStart == 0L) waitStart = System.currentTimeMillis()
                else if (System.currentTimeMillis() - waitStart > 8000) {
                    propColor = if (Math.random() < 0.5) "red" else "black"
                    propMinutes = 10
                    phase = "PLAYING"
                    waitStart = 0L
                    publish()
                }
            } else waitStart = 0L
            if (isHost && started && !gameOver && peerReady && !paused) {
                if (currentTurn == "red") redSec = (redSec - 1).coerceAtLeast(0)
                else blackSec = (blackSec - 1).coerceAtLeast(0)
                stepSec = (stepSec - 1).coerceAtLeast(0)
                sendJson(JSONObject().put("type", "clock")
                    .put("red", redSec).put("black", blackSec)
                    .put("step", stepSec).put("side", currentTurn))
                sendJson(JSONObject().put("type", "beat").put("n", moves.size))
                checkTimeout()
                renderStatus()
            }
            main.postDelayed(this, 1000)
        }
    }

    /** 局时或步时归零 → 判负（双方各自在收到 clock 时也会判）。 */
    private fun checkTimeout() {
        if (gameOver) return
        val loser = when {
            stepSec <= 0 -> currentTurn
            currentTurn == "red" && redSec <= 0 -> "red"
            currentTurn == "black" && blackSec <= 0 -> "black"
            else -> return
        }
        gameOver = true
        archiveGame("分出胜负")
        val loserName = if (loser == "red") "红方" else "黑方"
        logUi("[TIMEOUT] $loserName 超时判负")
        main.post {
            AlertDialog.Builder(this).setTitle("超时判负")
                .setMessage("$loserName 超时，本局结束。")
                .setPositiveButton("知道了", null).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        main.postDelayed(ticker, 1000)
    }

    // ═══════════ 房间权威层（唯一状态源 = SNAPSHOT） ═══════════
    // 服务端=开房端(监听方)；客户端只读快照。阶段机：
    // LOBBY → WAIT_READY → SETUP_ASSIGN → SETUP_PROPOSED → PLAYING
    private var lastSeq = 0
    private var phase = "LOBBY"
    private var guestReady = false
    private var setterIsOwner = false
    private var proposalId = 0
    private var propColor = "red"
    private var propMinutes = 5
    private var peerConnected = false
    private var lastSnapshotAt = 0L
    private var lastPeerAt = 0L
    private var waitStart = 0L
    // ★ 悔棋协议（对照在线棋类标准）：服务端权威表决 + deadline + 次数
    private var undoLeftRed = 3
    private var undoLeftBlack = 3
    private var voteRid = 0L
    private var voteDeadline = 0L
    private var voteRequester = ""      // red / black
    private var roomDlg: AlertDialog? = null
    private var roomDlgPhase = ""

    /** 服务端：每次状态变化 seq++ → 广播完整快照；自己也按快照渲染。 */
    private fun publish(withMoves: Boolean = false, rid: Long = 0L) {
        lastSeq++
        val o = JSONObject()
            .put("type", "snapshot")
            .put("seq", lastSeq)
            .put("phase", phase)
            .put("setter", if (setterIsOwner) "host" else "guest")
            .put("pid", proposalId)
            .put("c", propColor)
            .put("m", propMinutes)
            .put("guestReady", guestReady)
            .put("guestConn", peerConnected)
            .put("undoRed", undoLeftRed).put("undoBlack", undoLeftBlack)
            .put("voteRid", voteRid).put("voteDl", voteDeadline).put("voteBy", voteRequester)
        if (withMoves) {
            val arr = JSONArray(); for (m in moves) arr.put(m)
            o.put("moves", arr).put("rid", rid)
        }
        sendJson(o)
        applySnapshot(o, force = true)
    }

    /** 客户端：只接受 seq 更大的快照（旧包直接丢弃）。 */
    private fun applySnapshot(o: JSONObject, force: Boolean = false) {
        val seq = o.optInt("seq", 0)
        if (!force && seq <= lastSeq && seq != 0) return
        lastSeq = seq
        phase = o.optString("phase", "LOBBY")
        setterIsOwner = o.optString("setter") == "host"
        proposalId = o.optInt("pid", 0)
        propColor = o.optString("c", "red")
        propMinutes = o.optInt("m", 5)
        guestReady = o.optBoolean("guestReady", false)
        undoLeftRed = o.optInt("undoRed", undoLeftRed)
        undoLeftBlack = o.optInt("undoBlack", undoLeftBlack)
        peerConnected = o.optBoolean("guestConn", false)
        lastSnapshotAt = System.currentTimeMillis()
        lastPeerAt = lastSnapshotAt
        if (phase == "PLAYING" && started) return
        renderRoomUi()
        if (phase == "PLAYING") {
            myColor = if (roomOwner) propColor else if (propColor == "red") "black" else "red"
            hostColor = propColor
            minutes = propMinutes
            if (!started) beginMatch()
        }
    }

    /** 我是否本轮 setter。 */
    private fun iAmSetter(): Boolean = if (roomOwner) setterIsOwner else !setterIsOwner

    /** UI 完全由 phase 驱动，不做任何本地猜测。 */
    private fun renderRoomUi() {
        main.post {
            if (roomDlgPhase == phase && roomDlg?.isShowing == true) {
                renderStatus()   // 同阶段且窗口在，不重建（防闪）
                return@post
            }
            roomDlg?.dismiss(); roomDlg = null
            roomDlgPhase = phase
            val roleText = if (roomOwner) "房主（已锁定）" else "房客（只读）"
            when (phase) {
                "LOBBY" -> { statusText.text = "$roleText · 等待对手进入" ; return@post }
                "WAIT_READY" -> {
                    statusText.text = "$roleText · 等待就绪"
                    roomDlg = if (roomOwner) AlertDialog.Builder(this)
                        .setTitle("① 房间已建立").setMessage("房客已进入，等待 TA 点准备。\n（房主：$code）")
                        .setCancelable(false)
                        .create()
                    else AlertDialog.Builder(this)
                        .setTitle("① 请确认就绪").setMessage("房主已建立房间 $code，确认后由房主定规则。")
                        .setCancelable(false)
                        .setPositiveButton("准备") { _, _ -> startReadyPulse() }
                        
                        .create()
                    roomDlg?.show()
                }
                "SETUP_ASSIGN" -> {
                    statusText.text = "$roleText · 定规则（${if (iAmSetter()) "你来定" else "等对方定"}）"
                    roomDlg = if (iAmSetter()) buildSetterDialog() else AlertDialog.Builder(this)
                        .setTitle("② 对方正在定规则")
                        .setMessage("对方正在选择先后手与局时，定完立刻开局。")
                        .setCancelable(false)
                        .create()
                    roomDlg?.show()
                }
                "SETUP_PROPOSED" -> statusText.text = "$roleText · 开局中"
                else -> statusText.text = "$roleText · 对局中"
            }
        }
    }

    /** ★ 提案脉冲：每秒重发提案，直到进入对局（服务端收到即开局）。 */
    private fun startProposalPulse(c: String, m: Int) {
        statusText.text = "已提交规则，正在开局…"
        val t = object : Runnable {
            var n = 0
            override fun run() {
                if (phase == "PLAYING" || n >= 20) return
                n++
                sendJson(JSONObject().put("type", "proposal").put("c", c).put("m", m))
                main.postDelayed(this, 1000)
            }
        }
        main.post(t)
    }

    /** ★ 就绪脉冲：每秒重发一次 ready，直到服务端把阶段推进（彻底消除单次丢包死锁）。 */
    private fun startReadyPulse() {
        val t = object : Runnable {
            var n = 0
            override fun run() {
                if (phase != "WAIT_READY" || n >= 20) return
                n++
                sendJson(JSONObject().put("type", "ready"))
                main.postDelayed(this, 1000)
            }
        }
        main.post(t)
        statusText.text = "已发就绪，等待房主确认…"
    }

    private fun buildSetterDialog(): AlertDialog {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dpLocal(16), dpLocal(8), dpLocal(16), 0)
        }
        box.addView(TextView(this).apply { text = "先后手（点一下切换）"; textSize = 13f })
        val tvColor = TextView(this).apply {
            text = if (propColor == "red") "我先手（红）" else "我后手（黑）"
            textSize = 16f; setPadding(0, dpLocal(4), 0, dpLocal(8))
        }
        box.addView(TextView(this).apply { text = "局时（点一下切换）"; textSize = 13f })
        val tvTime = TextView(this).apply { text = "$propMinutes 分钟"; textSize = 16f }
        var c = propColor; var m = propMinutes
        tvColor.setOnClickListener {
            c = if (c == "red") "black" else "red"
            tvColor.text = if (c == "red") "我先手（红）" else "我后手（黑）"
        }
        tvTime.setOnClickListener {
            m = when (m) { 5 -> 10; 10 -> 15; else -> 5 }
            tvTime.text = "$m 分钟"
        }
        box.addView(tvColor); box.addView(tvTime)
        return AlertDialog.Builder(this)
            .setTitle("② 由你来定规则")
            .setView(box).setCancelable(false)
            .setPositiveButton("确定，立即开局") { _, _ ->
                propColor = c; propMinutes = m
                if (roomOwner) { proposalId++; phase = "PLAYING"; publish() }
                else startProposalPulse(c, m)
            }
            .create()
    }

    private fun onConnected() {
        peerReady = true
        peerConnected = true
        if (roomOwner && started && moves.isNotEmpty()) {
            publish(); sendSyncState()
        }
        if (roomOwner && !started) {
            if (phase == "LOBBY" || phase == "WAIT_READY") {
                phase = "SETUP_ASSIGN"
                setterIsOwner = Math.random() < 0.5
            }
            publish()
            main.postDelayed({ if (roomOwner && !started) publish() }, 400)
            main.postDelayed({ if (roomOwner && !started) publish() }, 1200)
        }
        runCatching { sendJson(JSONObject().put("type", "sign").put("code", code)) }
        main.post {
            renderStatus()
            if (stage == 0) main.post { renderRoomUi() }
        }
    }

    // ═══════════ 界面（顶栏状态行 + 挑战棋盘 + 底部菜单） ═══════════

    private fun buildUi() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val w = resources.displayMetrics.widthPixels
        val cream = 0xFFF6EFC8.toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFFBFD3E6.toInt())   // 与挑战/其它模式同系的浅蓝桌布
        }

        // ── 顶栏：与挑战模式同宽同厚（单行，40dp，米色），右侧挂双方用时 ──
        // ★ 顶栏 = 悬浮贴纸（与挑战模式同：38dp 高、米色、elevation），覆盖在棋盘之上
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cream)
            setPadding(dp(12), 0, dp(12), 0)
            elevation = dp(10).toFloat()
        }
        top.addView(TextView(this).apply {
            text = "※ 双人对战"
            textSize = 15f; setTextColor(0xFF2A1A05.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topInfo = TextView(this).apply {
            text = "房间 —"
            textSize = 12f; setTextColor(0xFF5A4A28.toInt())
        }
        top.addView(topInfo)
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38), Gravity.TOP
        ))

        // ── 棋盘：挑战同比例（宽度铺满、四周留 6dp、外框浅蓝） ──
        // ★★ 与挑战模式完全同构：棋盘容器全屏铺满（MATCH_PARENT×MATCH_PARENT，从 y=0 起）
        boardWrap = FrameLayout(this).apply { setBackgroundColor(0xFFBFD3E6.toInt()) }
        root.addView(boardWrap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        boardView = ChessBoardView(this).apply { onMoveMade = { mv -> onBoardMove(mv) } }
        // 视觉一次套齐（皮肤/棋桌/棋线/背景/个性化），尺寸交给视图自己按全屏空间算
        BoardVisualPrefs.applyToBoard(this, boardView)
        boardWrap.addView(boardView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ── 底部整体：菜单行/状态行/房间条，整块可上下拖动 ──
        bottomPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 第一移动条：挑战同款五格白色按钮 ──
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFBFD3E6.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        menu.addView(menuBtn("悔棋") { requestUndo() })
        menu.addView(menuBtn("再开一盘") { requestRestart() })
        menu.addView(menuBtn("战绩") { showRecord() })
        menu.addView(menuBtn("存谱") { saveRecord() })
        menu.addView(menuBtn("返回") { confirmExit() })
        bottomPanel.addView(menu)

        // ── 状态行 + ECCO 行（挑战同款米色块、左对齐两行） ──
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cream)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        statusText = TextView(this).apply {
            text = "我执红 ｜ 第 0 手 ｜ 该你走棋"
            textSize = 15f; setTextColor(0xFF1A1205.toInt())
        }
        info.addView(statusText)
        subText = TextView(this).apply {
            text = "本局开局：起手位"
            textSize = 13f; setTextColor(0xFF3A2A10.toInt()); setPadding(0, dp(4), 0, 0)
        }
        info.addView(subText)
        bottomPanel.addView(info)

        // ── 第二移动条：房间配置（房主有控件，客人只读） ──
        val cfg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cream)
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        val cfgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        codeEdit = EditText(this).apply {
            hint = "6 位房间码"; textSize = 14f; gravity = Gravity.CENTER; setSingleLine(true)
            setTextColor(0xFF2A1A05.toInt())
        }
        cfgRow.addView(codeEdit, LinearLayout.LayoutParams(0, dp(40), 1f))
        cfgRow.addView(smallBtn("进入房间") { enterRoom() })
        cfg.addView(cfgRow)

        setupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        fun cfgBtn(label: String, w2: Float, onClick: () -> Unit) {
            setupRow.addView(smallBtn(label) { onClick() },
                LinearLayout.LayoutParams(0, dpLocal(38), w2))
        }
        cfgBtn("我执红", 1f) { proposeColor("red") }
        cfgBtn("我执黑", 1f) { proposeColor("black") }
        cfgBtn("局时设置", 1.4f) { showTimeDialog2() }
        cfgBtn("开局", 1.2f) { if (stage < 3) showTimeDialog2() else hostStart() }
        cfg.addView(setupRow)
        cfgPanel = cfg
        startBtn = smallBtn("开局") { hostStart() }.apply { visibility = View.GONE }
        bottomPanel.addView(cfg)
        root.addView(bottomPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
        installDrag(bottomPanel, menu)

        // ★ 棋谱浮层：半透明可拖动，红/黑中文着法，一屏两回合（4 手/行），超出可滚动
        movePanel = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF1A1205.toInt())
            setBackgroundColor(0x99FFFFFF.toInt())
            setPadding(dpLocal(8), dpLocal(6), dpLocal(8), dpLocal(6))
            text = "棋谱\n红：—\n黑：—"
        }
        val mvScroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(0x99FFFFFF.toInt())
            addView(movePanel)
        }
        val mvWrap = FrameLayout(this).apply {
            setBackgroundColor(0x33FFFFFF.toInt())
            addView(mvScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            translationY = 0f
        }
        root.addView(mvWrap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpLocal(96),
            Gravity.TOP or Gravity.START
        ).apply { leftMargin = dpLocal(6); rightMargin = dpLocal(6); topMargin = dpLocal(52) })
        // ★ 工具条：固定（默认，可滚屏） / 移动（可拖动）
        val mvBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val btnFix = Button(this).apply {
            text = "固定"; textSize = 12f; isAllCaps = false; minHeight = 0; minWidth = 0
            setPadding(dpLocal(4), 0, dpLocal(4), 0)
            setOnClickListener { mvDraggable = false; toast("棋谱窗已固定，可上下滚屏") }
        }
        val btnMove = Button(this).apply {
            text = "移动"; textSize = 12f; isAllCaps = false; minHeight = 0; minWidth = 0
            setPadding(dpLocal(4), 0, dpLocal(4), 0)
            setOnClickListener { mvDraggable = true; toast("棋谱窗可拖动（再点固定即可滚屏）") }
        }
        val barH = dpLocal(34)
        mvBar.addView(btnFix, LinearLayout.LayoutParams(dpLocal(56), barH))
        mvBar.addView(btnMove, LinearLayout.LayoutParams(dpLocal(56), barH))
        // ★ 钉在白布内部右上角：白布移到哪，它就跟着到哪
        mvWrap.addView(mvBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { topMargin = dpLocal(2); rightMargin = dpLocal(2) })
        movePanel.setPadding(dpLocal(8), dpLocal(40), dpLocal(8), dpLocal(6))

        // ★ 拖动判定：点「移动」后，直接按住棋谱窗任意位置即可拖动；点「固定」则交回滚屏
        val prefsMv = getSharedPreferences("duel_ui", MODE_PRIVATE)
        mvWrap.translationY = prefsMv.getFloat("moves_dy", 0f)
        var dragging = false
        var downY = 0f
        var startTy = 0f
        mvScroll.setOnTouchListener { _, e ->
            if (!mvDraggable) return@setOnTouchListener false   // 固定 → 交给 ScrollView 滚屏
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragging = true; downY = e.rawY; startTy = mvWrap.translationY; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (dragging) { mvWrap.translationY = startTy + (e.rawY - downY); true } else false
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    prefsMv.edit().putFloat("moves_dy", mvWrap.translationY).apply(); true
                }
                else -> false
            }
        }

        setContentView(root)
        main.post { askSign() }
        // ★ 与挑战同：布局稳定后设可视带（顶栏底 ↔ 底部面板顶），并随之钳制棋盘与位移
        bottomPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncContentBand() }
        main.post {
            runCatching { BoardVisualPrefs.clampBoardToFit(boardWrap, root, root) }
            syncContentBand()
        }
        refreshBoard()
    }

    private fun smallBtn(label: String, weight: Float = 0f, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false
            minHeight = 0; minWidth = 0
            setPadding(0, 0, 0, 0)
            setBackgroundColor(0xFFFFFFFF.toInt()); setTextColor(0xFF1A1A1A.toInt())
            setOnClickListener { onClick() }
            if (weight > 0f) layoutParams = LinearLayout.LayoutParams(0, dpLocal(38), weight)
            else layoutParams = LinearLayout.LayoutParams(dpLocal(66), dpLocal(40))
        }

    /**
     * ★★★ 与挑战模式同一套：把「顶栏底 ↔ 底部面板顶」告诉棋盘。
     *   不设这条带，用户调"棋盘纵向位移"时棋盘不会被钳制，就会钻到顶栏下面（只露一半）。
     */
    private fun syncContentBand() {
        if (!::boardView.isInitialized) return
        val topPx = dpLocal(38).toFloat()
        val bottomPx = when {
            ::bottomPanel.isInitialized && bottomPanel.height > 0 -> bottomPanel.top.toFloat()
            else -> resources.displayMetrics.heightPixels * 0.75f
        }.coerceAtLeast(topPx + dpLocal(60))
        runCatching {
            boardView.setContentBand(topPx, bottomPx)
            boardView.requestLayout()
        }
    }

    /** ★ 底部整块可拖动：在菜单行上按住上下拖，松手记住偏移。 */
    private fun installDrag(panel: View, handle: View, key: String = "bottom_dy",
                            onlyWhen: (() -> Boolean)? = null) {
        val prefs = getSharedPreferences("duel_ui", MODE_PRIVATE)
        panel.translationY = prefs.getFloat(key, 0f)
        // ★ 兜底：存下来的偏移若把面板推出屏外，一律归零（否则重进就是光秃秃没按钮）
        panel.post {
            val h = resources.displayMetrics.heightPixels
            val dy = panel.translationY
            if (dy > h * 0.5f || dy < -h || panel.top < 0) {
                panel.translationY = 0f
                prefs.edit().putFloat(key, 0f).apply()
            }
        }
        var startY = 0f
        var startTrans = 0f
        handle.setOnTouchListener { _, e ->
            if (onlyWhen != null && !onlyWhen.invoke() && e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                return@setOnTouchListener false
            }
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY; startTrans = panel.translationY; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    panel.translationY = startTrans + (e.rawY - startY); true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putFloat(key, panel.translationY).apply()
                    syncContentBand(); true
                }
                else -> true
            }
        }
    }

    private fun dpLocal(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 挑战同款按钮：白底、细黑描边、等分五格。 */
    private fun menuBtn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false
            minHeight = 0; minWidth = 0
            setPadding(0, 0, 0, 0)
            setTextColor(0xFF1A1A1A.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                setStroke(dpLocal(1), 0xFF111111.toInt())
                cornerRadius = dpLocal(3).toFloat()
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dpLocal(28), 1f).apply {
                leftMargin = dpLocal(2); rightMargin = dpLocal(2)
            }
        }

    /**
     * ★ 悔棋规则：只能悔「自己刚刚走出的那一手」，且对方还没落子。
     *   请求期间双方停表；对方点同意才真正回退。
     */
    private fun requestUndo() {
        if (moves.isEmpty()) { toast("还没有走过棋"); return }
        val lastIsMine = (moves.size % 2 == 1 && myColor == "red") ||
                (moves.size % 2 == 0 && myColor == "black")
        if (!lastIsMine) { toast("只能悔自己刚走的那一步"); return }
        val left = if (myColor == "red") undoLeftRed else undoLeftBlack
        if (left <= 0) { toast("悔棋次数已用尽"); return }
        if (paused) { toast("正在等待对方确认"); return }
        paused = true
        runCatching { sendJson(JSONObject().put("type", "sync_request").put("seq", moves.size)) }
        undoPending = true
        undoRid = System.currentTimeMillis()
        val myRid = undoRid
        val pulse = object : Runnable {
            var n = 0
            override fun run() {
                if (!undoPending || undoRid != myRid) return
                n++
                if (n > 12) {
                    // 对方一直没回：解除冻结，提示一句
                    undoPending = false; paused = false
                    undoDlg?.dismiss(); undoDlg = null
                    renderStatus()
                    toast("对方未响应悔棋")
                    return
                }
                sendJson(JSONObject().put("type", "undo_req").put("side", myColor)
                    .put("n", 1).put("rid", myRid))
                main.postDelayed(this, 1000)
            }
        }
        main.post(pulse)
        undoDlg = AlertDialog.Builder(this)
            .setTitle("已请求悔棋")
            .setMessage("已通知对方：${if (myColor == "red") "红方" else "黑方"}请求悔一步棋。\n双方计时已暂停，等待对方确认…")
            .setCancelable(false)
            .setNegativeButton("撤回请求") { _, _ ->
                sendJson(JSONObject().put("type", "undo_no"))
                paused = false
                undoDlg = null
                renderStatus()
            }
            .create()
        undoDlg?.show()
    }

    /** 悔棋生效后，请求方必须明确收到"请你重走"的契机（只有一个确定）。 */
    private fun notifyPlayAgain() {
        AlertDialog.Builder(this)
            .setTitle("对方已同意悔棋")
            .setMessage("那一步已收回，现在轮到你重新走棋。")
            .setCancelable(false)
            .setPositiveButton("确定") { _, _ -> toast("请重走") }
            .show()
    }

    private fun doUndo(n: Int) {
        val k = minOf(n, moves.size)
        if (k <= 0) return
        val keep = ArrayList(moves.subList(0, moves.size - k))
        val keepNot = ArrayList(notation.subList(0, (notation.size - k).coerceAtLeast(0)))
        position = CorePosition.start(); fen = position.toFen(); moves.clear()
        notation = keepNot
        for (m in keep) {
            val p = m.split("-")
            if (p.size == 2) applyMove(p[0], p[1])
        }
        refreshTurn("undo")
        refreshBoard()
        logUi("悔棋 ${k} 手 → 第 ${moves.size} 手")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun logUi(msg: String) {
        Log.i(TAG, msg)
        main.post { subText.text = msg.take(60) }
    }

    // ═══════════ 局面 → 挑战棋盘（适配层） ═══════════

    /** 用规范 FEN 造一份 model.Board 喂给 ChessBoardView；棋规仍由我们自己的引擎判定。 */
    private fun modelBoardFromFen(fen: String): ModelBoard {
        val b = ModelBoard()
        b.clearBoard()
        val rows = fen.substringBefore(' ').split('/')
        for (r in 0 until minOf(10, rows.size)) {
            var col = 0
            for (ch in rows[r]) {
                if (ch.isDigit()) { col += ch - '0'; continue }
                if (col > 8) break
                val color = if (ch.isUpperCase()) PieceColor.RED else PieceColor.BLACK
                val type = PieceType.fromFenChar(ch)
                b.setPiece(ModelPosition(r, col), Piece(type, color))
                col++
            }
        }
        b.currentPlayer = if (fen.contains(" w ")) PieceColor.RED else PieceColor.BLACK
        return b
    }

    /** 从 FEN 取某点棋子字符（用于中文着法）。 */
    private fun fenPieceAt(fen: String, iccs: String): Char {
        val rows = fen.substringBefore(' ').split('/')
        val file = iccs[0] - 'a'
        val rank = iccs[1] - '0'
        val r = 9 - rank
        val row = rows.getOrNull(r) ?: return ' '
        var col = 0
        for (ch in row) {
            if (ch.isDigit()) col += ch - '0'
            else { if (col == file) return ch; col++ }
        }
        return ' '
    }

    private val CN_NUM = arrayOf("一","二","三","四","五","六","七","八","九")

    /** ICCS 着法 → 中文记谱（简化版：子名 + 起点纵线 + 进/退/平 + 终点）。 */
    private fun chineseNotation(piece: Char, from: String, to: String): String {
        if (piece == ' ') return "$from-$to"
        val red = piece.isUpperCase()
        val name = when (piece.uppercaseChar()) {
            'K' -> if (red) "帅" else "将"
            'A' -> if (red) "仕" else "士"
            'B', 'E' -> if (red) "相" else "象"
            'N', 'H' -> "马"
            'R' -> "车"
            'C' -> if (red) "炮" else "砲"
            'P' -> if (red) "兵" else "卒"
            else -> "?"
        }
        val f0 = from[0] - 'a'; val r0 = from[1] - '0'
        val f1 = to[0] - 'a'; val r1 = to[1] - '0'
        fun label(f: Int): String = if (red) CN_NUM[8 - f] else (f + 1).toString()
        return if (r0 == r1) name + label(f0) + "平" + label(f1)
        else {
            val forward = if (red) r1 > r0 else r1 < r0
            val dir = if (forward) "进" else "退"
            val dist = Math.abs(r1 - r0)
            val tail = if (name == "马" || name == "相" || name == "象" || name == "仕" || name == "士")
                label(f1) else if (red) CN_NUM[dist - 1] else dist.toString()
            name + label(f0) + dir + tail
        }
    }

    /** 棋谱浮层：一行 = 两个回合（4 手），当前行用 ▶ 标出，超出自动滚到末行。 */
    private fun renderMovePanel() {
        if (notation.isEmpty()) {
            main.post { movePanel.text = "棋谱（尚未走子）" }
            return
        }
        val sb = StringBuilder()
        var i = 0
        while (i < notation.size) {
            val r1 = i / 2 + 1
            val red1 = notation[i]
            val black1 = if (i + 1 < notation.size) notation[i + 1] else ""
            val r2 = if (i + 2 < notation.size) i / 2 + 2 else -1
            val red2 = if (i + 2 < notation.size) notation[i + 2] else ""
            val black2 = if (i + 3 < notation.size) notation[i + 3] else ""
            val current = i + 4 >= notation.size
            sb.append(if (current) "▶ " else "  ")
            sb.append(String.format("%2d.", r1))
            sb.append("红 ").append(red1)
            if (black1.isNotEmpty()) sb.append("  黑 ").append(black1)
            if (r2 > 0) {
                sb.append("     ").append(String.format("%2d.", r2))
                sb.append("红 ").append(red2)
                if (black2.isNotEmpty()) sb.append("  黑 ").append(black2)
            }
            sb.append("\n")
            i += 4
        }
        main.post {
            movePanel.text = sb.toString().trimEnd()
            (movePanel.parent as? android.widget.ScrollView)?.post {
                (movePanel.parent as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun refreshBoard() {
        val mb = modelBoardFromFen(fen)
        main.post {
            boardView.setBoard(mb)
            boardView.setFlipped(myColor == "black")
            // ★ 最后一步着色（与主界面同一 API）
            try {
                val last = moves.lastOrNull()
                if (last != null) {
                    val p = last.split("-")
                    if (p.size == 2) {
                        val f = ModelPosition(9 - (p[0][1] - '0'), p[0][0] - 'a')
                        val t = ModelPosition(9 - (p[1][1] - '0'), p[1][0] - 'a')
                        val pc = mb.getPiece(t) ?: mb.getPiece(f)
                        if (pc != null) boardView.setLastMove(ModelMove(f, t, pc))
                    }
                } else {
                    boardView.setLastMove(null)
                }
            } catch (_: Throwable) { }
            renderStatus()
        }
    }

    private fun fmt(sec: Int): String = String.format("%02d:%02d", sec / 60, sec % 60)

    private fun stepBudget(moveCount: Int): Int = if (moveCount < 4) 30 else 60

    /** ★ ECO 开局名：与挑战模式同源——把本局已走的 ICCS 序列重放，交给官方 ECCO 判定
     *   （EccoNamer.ofMoves，判定逻辑与 libecco64.so 对拍 0 差异），随走子实时播报最准确的
     *   开局/变例；判不出细分时如实报"其他开局"，绝不臆造错名。 */
    private fun ecoName(): String {
        if (moves.isEmpty()) return "起手位"
        val b = ModelBoard()
        b.reset()
        val mvs = ArrayList<ModelMove>()
        for (u in moves) {
            val p = u.split("-")
            if (p.size != 2) break
            val m = ModelMove.fromUci(p[0] + p[1], b) ?: break
            mvs.add(m)
            if (!b.makeMove(m)) break
        }
        if (mvs.isEmpty()) return "起手位"
        val nr = com.pikafish.chess.qipu.EccoNamer.ofMoves(mvs) ?: return "其他开局（ECCO 未细分）"
        return if (nr.variation.isBlank()) "${nr.code} ${nr.opening}" else "${nr.code} ${nr.opening}（${nr.variation}）"
    }

    private fun renderStatus() {
        val mineName = if (myColor == "red") "红方" else "黑方"
        val oppColor = if (myColor == "red") "black" else "red"
        val oppName = if (oppColor == "red") "红方" else "黑方"
        val oppSec = if (oppColor == "red") redSec else blackSec
        val mySec = if (myColor == "red") redSec else blackSec
        val turnName = if (currentTurn == "red") "红方" else "黑方"
        val turnText = if (currentTurn == myColor) "该你走棋" else "轮到$turnName"

        // 顶栏：对手没到位 → 只播报"未到，时间与棋局暂未启动"；到位才播时钟
        val room = if (code.isBlank()) "—" else code
        topInfo.text = if (!peerReady)
            "房间 $room ｜ 对手未到 · 时间与棋局暂未启动"
        else if (!started)
            "房间 $room ｜ 对手已到 · 等待开局"
        else
            "房间 $room ｜ 对方$oppName " +
                    (if (currentTurn == oppColor) "走棋中" else "已走完") +
                    " ${fmt(oppSec)}·步${stepSec}s"

        // 状态行：自己一方
        statusText.text = "我执$mineName ｜ 第 ${moves.size} 手 ｜ " +
                (if (currentTurn == myColor) "该你走棋" else "${turnName}走完") +
                "　${fmt(mySec)}·步${stepSec}s"
        // ECCO 单独一行（后续接开局库时只改这一行，不干扰状态行）
        subText.text = "本局开局：" + ecoName()
        subText.maxLines = 2
        syncConfigVisibility()
    }

    /** 棋盘点击：model.Move → ICCS → 我们的唯一管道 applyMove。 */
    private fun onBoardMove(mv: ModelMove): Boolean {
        Log.i(TAG, "[CLICK] ${iccs(mv.from)}-${iccs(mv.to)} mine=$myColor turn=$currentTurn moves=${moves.size} started=$started peer=$peerReady")
        val fromIccs = iccs(mv.from)
        val toIccs = iccs(mv.to)
        if (!started) { logUi("未开局"); return false }
        if (paused) { toast("悔棋表决中，暂不能走子"); return false }   // ★ 冻结对局
        if (currentTurn != myColor) { logUi("非本机回合"); return false }
        if (!applyMove(fromIccs, toIccs)) { logUi("本机拒绝：$fromIccs-$toIccs 非法"); return false }
        val seq = moves.size
        val msg = JSONObject().put("type", "move")
            .put("from", fromIccs).put("to", toIccs).put("seq", seq).put("side", myColor)
        sendReliable(seq, msg)
        refreshTurn("local move $seq")
        stepSec = stepBudget(moves.size)
        renderMovePanel()
        renderStatus()
        return true
    }

    private fun iccs(p: ModelPosition): String = "" + ('a' + p.col) + ('0' + (9 - p.row))

    // ═══════════ 唯一走子管道 ═══════════

    private fun applyMove(from: String, to: String): Boolean {
        val p = runCatching { CorePosition.fromFen(fen) }.getOrNull() ?: return false
        val f = (from[1] - '0') * 9 + (from[0] - 'a')
        val t = (to[1] - '0') * 9 + (to[0] - 'a')
        val mv = CoreMove(f, t)
        if (!p.legalMoves().contains(mv)) { logUi("非法：$from-$to"); return false }
        val pieceCh = fenPieceAt(fen, from)
        position = p.play(mv)
        fen = position.toFen()
        moves.add("$from-$to")
        notation.add(chineseNotation(pieceCh, from, to))
        saveRoomState()
        autoArchive()
        refreshBoard()
        return true
    }

    /** ★ 回合不由"谁收到"决定，而由步数唯一推导：偶数步轮到红、奇数步轮到黑。 */
    private fun refreshTurn(why: String) {
        val want = if (moves.size % 2 == 0) "red" else "black"
        setTurn(want, "$why (moves=${moves.size})")
    }

    private fun setTurn(c: String, why: String) {
        if (c == currentTurn) return
        Log.i(TAG, "[TURN] $currentTurn -> $c, by $why")
        currentTurn = c
        renderStatus()
    }

    // ═══════════ 网络 ═══════════

    private fun sendJson(o: JSONObject) {
        val line = o.toString()
        Log.i(TAG, "[SEND] $line")
        // ★ 通道兜底：优先生效通道，该通道不存在就换另一条，避免 ready/setup 丢失
        // ★ 双通道双发：哪条能走通都能到；重复由 seq / rid 幂等去重
        runCatching { host?.send(line) }
        runCatching { guest?.send(line) }
    }

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
                    sendJson(JSONObject().put("type", "sync_request").put("seq", moves.size))
                    return
                }
                sendJson(msg)
                main.postDelayed(this, 800)
            }
        }
        main.postDelayed(task, 800)
    }

    private fun handle(line: String) {
        Log.i(TAG, "[RECV] $line")
        lastPeerAt = System.currentTimeMillis()
        if (!peerReady) {
            peerReady = true
            Log.i(TAG, "[PEER] 已到位（收到对端消息）")
            main.post { renderStatus() }
        }
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "sign" -> {
                peerSigned = true
                renderStatus()
            }
            "deal_prop_removed" -> {
                val c = obj.optString("c", "red")
                val m = obj.optInt("m", 5)
                main.post {
                    val et = EditText(this).apply { hint = "有异议就写一句（可留空）"; textSize = 13f }
                    AlertDialog.Builder(this).setTitle("对方提议")
                        .setMessage("对方执${if (c == "red") "红（先手）" else "黑（后手）"}，每方 $m 分钟。\n确认即开局；有异议就在下面写一句回过去。")
                        .setView(et)
                        .setCancelable(false)
                        .setPositiveButton("确认") { _, _ ->
                            sendJson(JSONObject().put("type", "deal_ok"))
                            myColor = if (c == "red") "black" else "red"
                            hostColor = c; minutes = m
                            beginMatch()
                        }
                        .setNegativeButton("有异议") { _, _ ->
                            val msg = et.text.toString().trim().ifBlank { "换一个方案吧" }
                            sendJson(JSONObject().put("type", "deal_no").put("msg", msg))
                            stage = 1
                            main.post { showDealDialog(false) }
                        }
                        .show()
                }
            }
            "rules_offer" -> {
                val c = obj.optString("c", "red")
                val m = obj.optInt("m", 5)
                main.post {
                    AlertDialog.Builder(this)
                        .setTitle("对方的规则")
                        .setMessage("对方执：${if (c == "red") "红（先手）" else "黑（后手）"}\n" +
                                "你执：${if (c == "red") "黑（后手）" else "红（先手）"}\n局时：每方 $m 分钟")
                        .setCancelable(false)
                        .setPositiveButton("确认下") { _, _ ->
                            sendJson(JSONObject().put("type", "rules_ok"))
                            myColor = if (c == "red") "black" else "red"
                            hostColor = c; minutes = m
                            beginMatch()
                        }
                        
                        .show()
                }
            }
            "rules_ok" -> {
                myColor = colorMode
                hostColor = colorMode
                minutes = obj.optInt("m", minutes)
                beginMatch()
            }
            "ready" -> {
                if (!roomOwner) return
                if (phase != "WAIT_READY") return
                guestReady = true
                phase = "SETUP_ASSIGN"
                setterIsOwner = Math.random() < 0.5
                publish()
            }
            "proposal" -> {
                if (!roomOwner) return
                proposalId++
                propColor = obj.optString("c", "red")
                propMinutes = obj.optInt("m", 5)
                phase = "PLAYING"          // ★ 强制上桌：不接受"不同意"
                publish()
            }
            "confirm" -> {
                if (!roomOwner) return
                if (obj.optInt("pid", 0) != proposalId) return
                phase = "PLAYING"
                publish()
            }
            "reject" -> {
                if (!roomOwner) return
                if (obj.optInt("pid", 0) != proposalId) return
                phase = "SETUP_ASSIGN"
                setterIsOwner = !setterIsOwner
                publish()
            }
            "snapshot" -> {
                lastPeerAt = System.currentTimeMillis()
                applySnapshot(obj)
                val arr = obj.optJSONArray("moves")
                if (arr != null) {
                    val list = ArrayList<String>()
                    for (i in 0 until arr.length()) list.add(arr.optString(i))
                    if (list.size != moves.size) applyMovesList(list)
                    val rid = obj.optLong("rid", -1L)
                    if (rid != 0L && rid == undoRid) {
                        paused = false; undoPending = false
                        undoDlg?.dismiss(); undoDlg = null
                        main.post { notifyPlayAgain() }
                    }
                }
                return
            }
            "room_start" -> {
                val c = obj.optString("color", "red")
                myColor = if (c == "red") "black" else "red"
                hostColor = c
                minutes = obj.optInt("m", minutes)
                beginMatch()
            }
            "deal_ok" -> beginMatch()
            "deal_no" -> {
                val msg = obj.optString("msg", "对方有异议")
                main.post {
                    AlertDialog.Builder(this).setTitle("对方有异议")
                        .setMessage(msg + "\n\n重新定一个方案发过去？")
                        .setCancelable(false)
                        .setPositiveButton("重新定") { _, _ -> stage = 1; showDealDialog(true) }
                        .setNegativeButton("退出房间") { _, _ -> leave(); finish() }
                        .show()
                }
            }
            "color_prop_removed" -> {
                val c = obj.optString("c", "red")
                main.post {
                    AlertDialog.Builder(this).setTitle("第②步 · 颜色（第 $roundNo 轮）")
                        .setMessage("对方想执${if (c == "red") "红（先手）" else "黑（后手）"}，你同意吗？")
                        .setCancelable(false)
                        .setPositiveButton("同意") { _, _ ->
                            sendJson(JSONObject().put("type", "color_ok"))
                            myColor = if (c == "red") "black" else "red"
                            hostColor = c
                            enterTimeStage()
                        }
                        .setNegativeButton("不同意") { _, _ ->
                            sendJson(JSONObject().put("type", "color_no"))
                            roundNo++
                            if (roundNo > 3) { main.post { abortAll() } }
                            else showColorChoiceForReply()
                        }
                        .show()
                }
            }
            "color_ok" -> enterTimeStage()
            "color_no" -> { roundNo++; if (roundNo > 3) abortAll() else main.post { showColorChoice() } }
            "time_prop_removed" -> {
                val m = obj.optInt("m", 5)
                main.post {
                    AlertDialog.Builder(this).setTitle("第③步 · 局时（第 $roundNo 轮）")
                        .setMessage("对方提议每方 $m 分钟，你同意吗？")
                        .setCancelable(false)
                        .setPositiveButton("同意") { _, _ ->
                            sendJson(JSONObject().put("type", "time_ok"))
                            minutes = m; beginMatch()
                        }
                        .setNegativeButton("不同意") { _, _ ->
                            sendJson(JSONObject().put("type", "time_no"))
                            roundNo++
                            if (roundNo > 3) abortAll() else showTimeDialog2()
                        }
                        .show()
                }
            }
            "time_ok" -> { minutes = obj.optInt("m", minutes); beginMatch() }
            "time_no" -> { roundNo++; if (roundNo > 3) abortAll() else main.post { showTimeDialog2() } }
            "setup_placeholder" -> { }
            "setup" -> {
                if (!roomOwner) renderRoomUi()
            }
            "setup_legacy" -> {
                if (isHost) return
                hostColor = obj.optString("hostColor", "red")
                minutes = obj.optInt("minutes", 5)
                myColor = if (hostColor == "red") "black" else "red"
                redSec = obj.optInt("red", minutes * 60)
                blackSec = obj.optInt("black", redSec)
                refreshBoard()
            }
            "start_req" -> {
                if (isHost) { sendStart(false) } else {
                    started = true; currentTurn = "red"; hideConfigAfterStart(); refreshBoard()
                }
            }
            "start" -> {
                if (isHost) return
                started = true; currentTurn = "red"
                stepSec = stepBudget(moves.size)
                hideConfigAfterStart()
                refreshBoard()
            }
            "move" -> {
                val from = obj.optString("from"); val to = obj.optString("to")
                val seq = obj.optInt("seq", -1)
                if (seq <= moves.size) { sendJson(JSONObject().put("type", "ack").put("seq", seq)); return }
                if (seq > moves.size + 1) { sendJson(JSONObject().put("type", "need").put("from", moves.size)); return }
                if (!applyMove(from, to)) { sendJson(JSONObject().put("type", "need").put("from", moves.size)); return }
                sendJson(JSONObject().put("type", "ack").put("seq", seq))
                started = true
                refreshTurn("remote move $seq")
            }
            "abort" -> abortAll()
            "undo_req" -> {
                val rid = obj.optLong("rid", -1L)
                val side = obj.optString("side", if (myColor == "red") "black" else "red")
                if (rid != -1L && rid == handledRid) return          // 同一请求只办一次
                handledRid = rid
                paused = true                                        // ★ 双方停表
                main.post {
                    AlertDialog.Builder(this)
                        .setTitle("悔棋请求")
                        .setMessage("${if (side == "red") "红方" else "黑方"} 请求悔一步棋（只悔他刚走的那一手）。\n双方计时已暂停，10 秒未答视为拒绝。")
                        .setCancelable(false)
                        .setPositiveButton("同意") { _, _ ->
                            // ★ 由被请求方执行回退：只退一手，以完整走子表广播（幂等）
                            val keep = moves.dropLast(1)
                            applyMovesList(keep)
                            if (side == "red") undoLeftRed = (undoLeftRed - 1).coerceAtLeast(0)
                            else undoLeftBlack = (undoLeftBlack - 1).coerceAtLeast(0)
                            paused = false
                            undoDlgShown = false
                            val arr = JSONArray(); for (m in moves) arr.put(m)
                            sendJson(JSONObject().put("type", "undo_done").put("rid", rid).put("ok", true)
                                .put("red", undoLeftRed).put("black", undoLeftBlack).put("moves", arr))
                            publish(withMoves = true, rid = rid)
                        }
                        .setNegativeButton("拒绝") { _, _ ->
                            paused = false
                            undoDlgShown = false
                            sendJson(JSONObject().put("type", "undo_done").put("rid", rid).put("ok", false))
                        }
                        .show()
                    // 10 秒无人应答 → 视为拒绝（与服务端 deadline 一致）
                    main.postDelayed({
                        if (paused) { paused = false; undoDlgShown = false
                            sendJson(JSONObject().put("type", "undo_done").put("rid", rid).put("ok", false)) }
                    }, 10_000)
                }
            }
            "undo_reply" -> {
                if (!roomOwner) return
                val rid = obj.optLong("rid", -1L)
                if (rid != voteRid) return
                val accept = obj.optBoolean("accept", false)
                if (accept) {
                    applyMovesList(moves.dropLast(1))
                    if (voteRequester == "red") undoLeftRed-- else undoLeftBlack--
                }
                voteRid = 0L; voteDeadline = 0L; voteRequester = ""
                paused = false
                publish(withMoves = accept, rid = rid)
                sendJson(JSONObject().put("type","undo_done").put("rid",rid).put("ok",accept)
                    .put("red",undoLeftRed).put("black",undoLeftBlack))
            }
            "undo_done" -> {
                val rid = obj.optLong("rid", -1L)
                undoLeftRed = obj.optInt("red", undoLeftRed)
                undoLeftBlack = obj.optInt("black", undoLeftBlack)
                voteRid = 0L; voteDeadline = 0L
                paused = false
                undoPending = false
                undoDlg?.dismiss(); undoDlg = null
                val ok = obj.optBoolean("ok", false)
                val arr = obj.optJSONArray("moves")
                if (ok && arr != null) {
                    val list = ArrayList<String>()
                    for (i in 0 until arr.length()) list.add(arr.optString(i))
                    applyMovesList(list)
                }
                if (ok && rid == undoRid) main.post { notifyPlayAgain() }
                else if (!ok) main.post { toast("对方拒绝了悔棋") }
                renderStatus()
            }
            "undo_apply" -> {
                val arr: JSONArray = obj.optJSONArray("moves") ?: return
                val rid = obj.optLong("rid", -1L)
                val list = ArrayList<String>()
                for (i in 0 until arr.length()) list.add(arr.optString(i))
                undoPending = false
                paused = false
                undoDlg?.dismiss(); undoDlg = null
                applyMovesList(list)
                if (rid == undoRid) main.post { notifyPlayAgain() }
            }
            "undo" -> doUndo(obj.optInt("n", 1))
            "ack" -> pending.remove(obj.optInt("seq", -1))
            "need" -> {
                val from = obj.optInt("from", 0)
                for (i in from until moves.size) {
                    val p = moves[i].split("-")
                    if (p.size != 2) continue
                    val seq = i + 1
                    sendJson(JSONObject().put("type", "move").put("from", p[0]).put("to", p[1])
                        .put("seq", seq).put("side", if (seq % 2 == 1) "red" else "black"))
                }
            }
            "clock" -> {
                if (isHost || paused) return
                redSec = obj.optInt("red", 0); blackSec = obj.optInt("black", 0)
                stepSec = obj.optInt("step", stepSec).coerceAtLeast(0)
                renderStatus()
                checkTimeout()
            }
            "beat" -> {
                if (isHost) return
                val n = obj.optInt("n", -1)
                if (n >= 0 && n != moves.size)
                    sendJson(JSONObject().put("type", "sync_request").put("seq", moves.size))
            }
            "sync_request" -> {
                if (isHost) {
                    val seq = obj.optInt("seq", -1)
                    if (seq > moves.size) sendJson(JSONObject().put("type", "need").put("from", moves.size))
                    else sendSyncState()
                }
            }
            "sync_state" -> {
                val arr: JSONArray = obj.optJSONArray("moves") ?: return
                if (arr.length() <= moves.size) return
                position = CorePosition.start(); fen = position.toFen(); moves.clear(); currentTurn = "red"
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i).split("-")
                    if (p.size == 2) applyMove(p[0], p[1])
                }
                started = true
                refreshTurn("sync_state")
                refreshBoard()
            }
            "restart_req" -> {
                if (isHost) return
                main.post {
                    AlertDialog.Builder(this).setTitle("房主要求重开").setMessage("同意从开局重下？")
                        .setPositiveButton("同意") { _, _ -> sendJson(JSONObject().put("type", "restart_ok")) }
                        .setNegativeButton("拒绝", null).show()
                }
            }
            "restart_ok" -> if (isHost) { resetGame(); sendStart(true); sendSyncState() }
        }
    }

    /** ★ 续盘存档：按房间码把走子序列/棋谱/执色/局时写到本机。 */
    private fun saveRoomState() {
        if (code.isBlank()) return
        runCatching {
            getSharedPreferences("duel_room", MODE_PRIVATE).edit()
                .putString("mv_$code", moves.joinToString(","))
                .putString("nt_$code", notation.joinToString("|"))
                .putString("col_$code", hostColor)
                .putInt("min_$code", minutes)
                .putBoolean("started_$code", started)
                .putString("role_$code", if (roomOwner) "host" else "guest")
                .apply()
        }
    }

    /** ★ 进入房间时按码取回上次局面（任何人进出都续到同一节点）。 */
    private fun loadRoomState() {
        val p = getSharedPreferences("duel_room", MODE_PRIVATE)
        val mv = p.getString("mv_$code", "") ?: ""
        if (mv.isBlank()) return
        val list = mv.split(",").filter { it.contains("-") }
        if (list.isEmpty()) return
        position = CorePosition.start(); fen = position.toFen(); moves.clear(); currentTurn = "red"
        for (m in list) {
            val q = m.split("-")
            if (q.size == 2) applyMove(q[0], q[1])
        }
        notation = ArrayList((p.getString("nt_$code", "") ?: "").split("|").filter { it.isNotBlank() })
        hostColor = p.getString("col_$code", hostColor) ?: hostColor
        myColor = if (roomOwner) hostColor else if (hostColor == "red") "black" else "red"
        minutes = p.getInt("min_$code", minutes)
        started = p.getBoolean("started_$code", false)
        logUi("已续盘：$code 共 ${moves.size} 手，我执 $myColor，$minutes 分钟")
    }

    /** ★ 悔棋结果走状态同步通道：广播权威手数，另一方按手数回放到位。 */
    private fun publishRollback(rid: Long) {
        val arr = JSONArray(); for (m in moves) arr.put(m)
        sendJson(JSONObject().put("type", "undo_apply").put("rid", rid).put("moves", arr))
    }

    /** ★ 以绝对走子表重建局面：任何一端执行后状态完全一致，重复执行也不会多退。 */
    private fun applyMovesList(list: List<String>) {
        position = CorePosition.start(); fen = position.toFen()
        moves.clear(); notation.clear(); currentTurn = "red"
        for (m in list) {
            val q = m.split("-")
            if (q.size == 2) applyMove(q[0], q[1])
        }
        started = true
        stepSec = stepBudget(moves.size)     // ★ 回退后步时重置
        refreshTurn("undo_apply")
        refreshBoard()
        saveRoomState()
        renderStatus()
    }

    /** 把局面裁剪到指定手数（幂等：已到该手数则不动）。 */
    private fun doUndoTo(target: Int) {
        val k = moves.size - target
        if (k <= 0) return
        doUndo(k)
    }

    private fun sendSyncState() {
        val arr = JSONArray(); for (m in moves) arr.put(m)
        sendJson(JSONObject().put("type", "sync_state").put("moves", arr))
    }

    private fun sendSetup() = sendJson(JSONObject().put("type", "setup")
        .put("hostColor", hostColor).put("minutes", minutes).put("red", redSec).put("black", blackSec))

    private fun sendStart(restart: Boolean) {
        if (!isHost) return
        started = true; currentTurn = "red"
        sendJson(JSONObject().put("type", "start").put("myColor", myColor).put("restart", restart))
    }

    // ═══════════ 房间与底部菜单 ═══════════

    /**
     * ★ 统一入口：不分房主房客。同一个房间码，谁先按谁监听，另一个自动连过去；
     *   连不上就每 1.5 秒重试。
     */
    private fun enterRoom() {
        var c = codeEdit.text.toString().trim()
        if (c.length != 6) { c = DuelNet.newCode(); codeEdit.setText(c) }
        leave()
        code = c
        val h = DuelNet.Host(c, android.os.Build.MODEL.take(12))
        val ok = h.start(peer) != null
        if (ok) {
            isHost = true; host = h
            resetGame()
            main.post {
                setupRow.visibility = View.VISIBLE
                startBtn.visibility = View.VISIBLE
                cfgPanel.visibility = View.VISIBLE
            }
            statusText.text = "已进房 $c，等对手用同一号码进入"
            logUi("本机 IP " + DuelNet.localIp(this))
        } else {
            // 端口被占 → 对面已经在监听，直接连过去
            isHost = false
            joinWithRetry(c)
            statusText.text = "正在进入房间 $c …"
        }
        refreshBoard()
    }

    private fun createRoom() {
        leave(); isHost = true
        code = DuelNet.newCode(); codeEdit.setText(code)
        hostColor = "red"; myColor = "red"; minutes = 5
        peerReady = false
        val h = DuelNet.Host(code, android.os.Build.MODEL.take(12))
        if (h.start(peer) == null) { toast("建房失败：端口被占用"); return }
        host = h
        resetGame()
        setupRow.visibility = View.VISIBLE
        startBtn.visibility = View.VISIBLE
        main.post { if (::cfgPanel.isInitialized) cfgPanel.visibility = View.VISIBLE }
        statusText.text = "房间 $code 已创建，等对手加入"
        logUi("本机 IP " + DuelNet.localIp(this))
    }

    private fun joinRoom() {
        val c = codeEdit.text.toString().trim()
        if (c.isBlank()) { toast("请填房间码"); return }
        leave(); isHost = false; code = c; myColor = "black"
        peerReady = false
        joinWithRetry(c)
        setupRow.visibility = View.GONE
        startBtn.visibility = View.GONE
        statusText.text = "正在找房主…"
        refreshBoard()
    }

    /** ★ 房客在任何节点都能进：连不上就每隔 1.5 秒重试（最多 8 次）。 */
    private fun joinWithRetry(c: String, attempt: Int = 1) {
        guest?.stop()
        guest = DuelNet.Guest(c, android.os.Build.MODEL.take(12), null).also { it.start(peerGuest) }
        if (attempt >= 12) return
        main.postDelayed({
            if (!peerReady) {
                logUi("未连上房主，第 $attempt 次重试…")
                joinWithRetry(c, attempt + 1)
            }
        }, 1500)
    }

    private fun setHostColor(c: String) {
        hostColor = c; myColor = c
        // ★ 任何一方都能定自己执红/执黑，并同步给对方（不再分房主）
        sendSetup()
        sendSetup(); refreshBoard()
        statusText.text = "你执" + if (c == "red") "红（先）" else "黑（后）"
    }

    /** 局时设置：5 / 10 / 15 分钟，三选一互斥，确认即同步给对面。 */
    private fun enterColorStage() {
        stage = 1; roundNo = 1; renderStatus()
        cfgPanel.visibility = View.GONE      // 阶段面板不再出现在底部
        main.post { if (peerReady) showDealDialog(true) }
    }

    /** ★ 居中弹窗一次性决定：执红/执黑 + 5/10/15 局时 → 一并发给对方。 */
    private fun showDealDialog(initiator: Boolean) {
        val colors = arrayOf("我执红（先手）", "我执黑（后手）")
        val times = arrayOf("5 分钟", "10 分钟", "15 分钟")
        var ci = 0; var ti = 0
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dpLocal(16), dpLocal(8), dpLocal(16), 0)
        }
        box.addView(TextView(this).apply { text = "选择你的颜色"; textSize = 14f })
        box.addView(android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            colors.forEachIndexed { i, label ->
                addView(android.widget.RadioButton(this@DuelUiActivity).apply {
                    text = label; textSize = 13f; isChecked = i == 0
                    setOnCheckedChangeListener { _, checked -> if (checked) ci = i }
                })
            }
        })
        box.addView(TextView(this).apply { text = "选择局时（每方）"; textSize = 14f; setPadding(0, dpLocal(8), 0, 0) })
        box.addView(android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            times.forEachIndexed { i, label ->
                addView(android.widget.RadioButton(this@DuelUiActivity).apply {
                    text = label; textSize = 13f; isChecked = i == 1
                    setOnCheckedChangeListener { _, checked -> if (checked) ti = i }
                })
            }
        })
        AlertDialog.Builder(this)
            .setTitle(if (initiator) "第②步 · 定颜色与局时" else "你来定颜色与局时")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("决定好了，发给对方") { _, _ ->
                sendJson(JSONObject().put("type", "deal_prop")
                    .put("c", if (ci == 0) "red" else "black").put("m", intArrayOf(5, 10, 15)[ti]))
                stage = 2
                main.post { toast("已发送，等待对方确认…"); renderStatus() }
            }
            .setNegativeButton("取消") { _, _ -> stage = 1; renderStatus() }
            .show()
    }

    private fun showColorChoice() {
        AlertDialog.Builder(this).setTitle("第②步 · 选颜色（第 $roundNo 轮）")
            .setMessage("你先选，对方确认即定。")
            .setCancelable(false)
            .setPositiveButton("我要红（先手）") { _, _ -> proposeColor("red") }
            .setNegativeButton("我要黑（后手）") { _, _ -> proposeColor("black") }
            .show()
    }

    private fun showColorChoiceForReply() {
        AlertDialog.Builder(this).setTitle("第②步 · 选颜色（第 $roundNo 轮）")
            .setMessage("对方不同意，现在轮到你提方案。")
            .setCancelable(false)
            .setPositiveButton("我要红") { _, _ -> proposeColor("red") }
            .setNegativeButton("我要黑") { _, _ -> proposeColor("black") }
            .show()
    }

    private fun enterTimeStage() { stage = 2; roundNo = 1; renderStatus(); main.post { showTimeDialog2() } }

    /** 三轮谈不拢 → 双方全部退回主界面。 */
    private fun abortAll() {
        AlertDialog.Builder(this).setTitle("协商失败")
            .setMessage("三轮仍未达成一致，本局取消，回到主界面。")
            .setCancelable(false).setPositiveButton("知道了") { _, _ -> leave(); finish() }.show()
        sendJson(JSONObject().put("type", "abort"))
    }

    /** ① 签到：进来先输同一个房间码；输完即视为本人签到，等对方输同一个码。 */
    private fun askSign() {
        val et = EditText(this).apply { hint = "6 位房间码（开房可留空自动生成）"; setSingleLine(true); textSize = 16f }
        AlertDialog.Builder(this)
            .setTitle("第①步 · 签到")
            .setMessage("两人用同一个房间码。\n先按的一方点「开房」，另一方点「加入」。")
            .setView(et)
            .setCancelable(false)
            .setPositiveButton("开房签到") { _, _ ->
                var c = et.text.toString().trim()
                if (c.length != 6) c = DuelNet.newCode()
                signIn(c, true)
            }
            .setNeutralButton("加入签到") { _, _ ->
                val c = et.text.toString().trim()
                if (c.length != 6) { toast("请先填 6 位房间码"); askSign(); return@setNeutralButton }
                signIn(c, false)
            }
            .show()
    }

    private fun signIn(c: String, asHost: Boolean) {
        leave(); code = c; codeEdit.setText(c)
        peerReady = false; useHostChannel = asHost
        roomOwner = asHost          // 先定角色，再按角色恢复执色
        loadRoomState()
        // ★ 识别：本机存过这局的走子 → 这局已开局，绝不再走"协商红黑/局时"
        if (started && moves.isNotEmpty()) {
            phase = "PLAYING"
            logUi("识别到已开局（${moves.size} 手）→ 直接续盘，不再协商")
            refreshBoard()
            renderStatus()
        }
        if (asHost) {
            isHost = true
            val h = DuelNet.Host(c, android.os.Build.MODEL.take(12))
            if (h.start(peerHost) != null) {
                host = h
                statusText.text = "已开房 $c，等对方加入"
                logUi("本机 IP " + DuelNet.localIp(this))
            } else { toast("开房失败：端口被占用"); return }
        } else {
            isHost = false
            joinWithRetry(c)
            statusText.text = "正在加入 $c …"
            // ★ 4 秒还找不到房主 → 自己也开监听（此事后另一方来连我即可接入），
            //   同时保留重连，谁先通用谁，保证"任何人进出都能续上同一节点"。
            main.postDelayed({
                if (!peerReady && phase != "PLAYING") {
                    val h = DuelNet.Host(c, android.os.Build.MODEL.take(12))
                    if (h.start(peerHost) != null) {
                        host = h; isHost = true; roomOwner = true
                        statusText.text = "原房主不在，本机已接管房间 $c"
                        logUi("接管房间 $c")
                    }
                }
            }, 4000)
        }
        stage = 0; peerSigned = false
        renderStatus()
    }

    /** ② 选色：发起方自选红/黑，对方同意即定；不同意则轮次+1，满 3 轮散场。 */
    private fun proposeColor(c: String) {
        sendJson(JSONObject().put("type", "color_prop").put("c", c))
        myColor = c; hostColor = c
        stage = 1
        main.post {
            AlertDialog.Builder(this).setTitle("第②步 · 颜色（第 $roundNo 轮）")
                .setMessage("你选择了${if (c == "red") "红方（先手）" else "黑方（后手）"}，等待对方确认…")
                .setCancelable(false).setNegativeButton("撤回重选") { _, _ -> roundNo++; proposeColor(if (c == "red") "black" else "red") }
                .show()
        }
        renderStatus()
    }

    /** ③ 选时：5/10/15 分钟，同样征得对方同意。 */
    private fun proposeTime(m: Int) {
        sendJson(JSONObject().put("type", "time_prop").put("m", m))
        stage = 2
        main.post {
            AlertDialog.Builder(this).setTitle("第③步 · 局时（第 $roundNo 轮）")
                .setMessage("你选择了 $m 分钟（每方局时），等待对方确认…")
                .setCancelable(false)
                .setNegativeButton("撤回重选") { _, _ -> roundNo++; showTimeDialog2() }
                .show()
        }
        renderStatus()
    }

    private fun showTimeDialog2() {
        val labels = arrayOf("5 分钟", "10 分钟", "15 分钟")
        val values = intArrayOf(5, 10, 15)
        AlertDialog.Builder(this).setTitle("第③步 · 局时（第 $roundNo 轮）")
            .setItems(labels) { _, which -> proposeTime(values[which]) }
            .setCancelable(false).show()
    }

    /** 双方谈妥 → 正式开始的动画气泡 → 起表 */
    private fun beginMatch() {
        stage = 3; started = true; gameOver = false; paused = false
        currentTurn = "red"
        redSec = minutes * 60; blackSec = minutes * 60
        stepSec = stepBudget(moves.size)
        hideConfigAfterStart()
        main.post {
            val tv = TextView(this).apply {
                text = "正式开始\n${if (myColor == "red") "你执红先行" else "你执黑后行"}"
                textSize = 26f; gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC000000.toInt())
            }
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpLocal(120), Gravity.CENTER)
            (window.decorView as ViewGroup).addView(tv, lp)
            tv.alpha = 0f; tv.scaleX = 0.7f; tv.scaleY = 0.7f
            tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).withEndAction {
                tv.postDelayed({
                    tv.animate().alpha(0f).setDuration(300).withEndAction {
                        (window.decorView as ViewGroup).removeView(tv)
                    }
                }, 700)
            }
        }
        refreshBoard()
        renderStatus()
    }

    private fun showTimeDialog() {
        val labels = arrayOf("5 分钟", "10 分钟", "15 分钟")
        val values = intArrayOf(5, 10, 15)
        val cur = values.indexOfFirst { it == minutes }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("棋局时间（每方局时）")
            .setSingleChoiceItems(labels, cur) { d, which ->
                setMinutes(values[which])
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setMinutes(m: Int) {
        minutes = m
        // ★ 任何一方都能发起局时
        runCatching { sendSetup() }; redSec = if (m <= 0) 0 else m * 60; blackSec = redSec
        sendSetup(); renderStatus()
    }

    private fun hostStart() {
        if (!isHost) {
            // ★ 不是传输层监听方也能开局：发给对方执行
            sendJson(JSONObject().put("type", "start_req"))
            sendStart(false)
            hideConfigAfterStart()
            return
        }
        if (moves.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("对局进行中（${moves.size} 步）")
                .setMessage("继续下（续盘）还是重开？")
                .setPositiveButton("继续下") { _, _ -> sendStart(false); sendSyncState() }
                .setNeutralButton("请求重开") { _, _ -> sendJson(JSONObject().put("type", "restart_req")) }
                .setNegativeButton("取消", null).show()
            return
        }
        sendStart(false)
        hideConfigAfterStart()
        statusText.text = "对局开始"
    }

    private fun requestRestart() {
        if (!isHost) { toast("只有房主能重开"); return }
        if (moves.isEmpty()) { resetGame(); sendStart(true); return }
        AlertDialog.Builder(this).setTitle("再开一盘").setMessage("请求对方同意重开？")
            .setPositiveButton("请求") { _, _ -> sendJson(JSONObject().put("type", "restart_req")) }
            .setNegativeButton("取消", null).show()
    }

    /** 双方都进场且已开局 → 房间/设置条整体隐藏，面积让给棋盘与 ECCO。 */
    private fun hideConfigAfterStart() {
        syncConfigVisibility()
    }

    /** ★ 只有「双方到位 + 已开局」才收起房间条；其它任何情况都必须还原，避免重进后光秃秃。 */
    private fun syncConfigVisibility() {
        main.post {
            if (!::cfgPanel.isInitialized) return@post
            // ★ 底部阶段面板不再出现：签到/协商全用居中弹窗，只有对弈时也保持隐藏
            cfgPanel.visibility = View.GONE
            setupRow.visibility = View.GONE
            if (::bottomPanel.isInitialized) bottomPanel.visibility = View.VISIBLE
        }
    }

    private fun resetGame() {
        position = CorePosition.start(); fen = position.toFen()
        moves.clear(); notation.clear(); currentTurn = "red"
        renderMovePanel()
        refreshBoard()
    }

    private fun showRecord() {
        AlertDialog.Builder(this).setTitle("战绩")
            .setMessage("本局已走 ${moves.size} 手\n红 ${redSec / 60}:${redSec % 60} ｜ 黑 ${blackSec / 60}:${blackSec % 60}")
            .setPositiveButton("知道了", null).show()
    }

    /** 存谱：导出的就是屏幕上那份汉字谱（红=纯汉字，黑=汉字+阿拉伯数字）。 */
    private fun saveRecord() {
        if (notation.isEmpty()) { toast("还没走过棋"); return }
        val sb = StringBuilder()
        var i = 0
        while (i < notation.size) {
            val r = i / 2 + 1
            sb.append(String.format("%d. ", r))
            sb.append("红 ").append(notation[i])
            if (i + 1 < notation.size) sb.append("　黑 ").append(notation[i + 1])
            sb.append("\n")
            i += 2
        }
        val text = sb.toString().trimEnd()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("duel", text))
        archiveGame("手动存谱")
        logUi("存谱 ${notation.size} 手（汉字谱）")
        main.post {
            AlertDialog.Builder(this).setTitle("汉字棋谱（已复制）")
                .setMessage(text).setPositiveButton("知道了", null).show()
        }
    }

    private var archived = false

    /** ★ 自动存谱：每走一步无差别覆盖写入「双人对战/自动_房间.txt」。 */
    private fun autoArchive() {
        runCatching {
            val dir = java.io.File(filesDir, "双人对战")
            if (!dir.exists()) dir.mkdirs()
            val f = java.io.File(dir, "自动_${if (code.isBlank()) "无码" else code}.txt")
            val sb = StringBuilder()
            sb.append("房间：${code}\n")
            sb.append("局时：${minutes} 分钟　我执：${if (myColor == "red") "红方" else "黑方"}\n")
            sb.append("开局：${ecoName()}\n")
            sb.append("共 ${notation.size} 手（自动存档，每步更新）\n\n")
            var i = 0
            while (i < notation.size) {
                sb.append(String.format("%d. 红 %s", i / 2 + 1, notation[i]))
                if (i + 1 < notation.size) sb.append("　黑 ").append(notation[i + 1])
                sb.append("\n"); i += 2
            }
            f.writeText(sb.toString(), Charsets.UTF_8)
        }
    }

    /** ★ 把本局汉字谱归档到「双人对战」目录：正常结束或强退都存。 */
    private fun archiveGame(reason: String) {
        if (archived || notation.isEmpty()) return
        archived = true
        runCatching {
            val dir = java.io.File(filesDir, "双人对战")
            if (!dir.exists()) dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val f = java.io.File(dir, "${stamp}_${if (code.isBlank()) "无码" else code}.txt")
            val sb = StringBuilder()
            sb.append("房间：${code}\n")
            sb.append("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n")
            sb.append("我执：${if (myColor == "red") "红方" else "黑方"}　局时：${minutes} 分钟\n")
            sb.append("结果：$reason　共 ${notation.size} 手\n\n")
            var i = 0
            while (i < notation.size) {
                sb.append(String.format("%d. 红 %s", i / 2 + 1, notation[i]))
                if (i + 1 < notation.size) sb.append("　黑 ").append(notation[i + 1])
                sb.append("\n"); i += 2
            }
            f.writeText(sb.toString(), Charsets.UTF_8)
            logUi("已归档棋谱 → ${f.absolutePath}")
        }
    }

    private fun confirmExit() {
        if (moves.isEmpty()) { leave(); finish(); return }
        AlertDialog.Builder(this).setTitle("确认退出")
            .setMessage("已走 ${moves.size} 步，退出后本局结束。")
            .setCancelable(false)
            .setPositiveButton("确认退出") { _, _ -> archiveGame("中途退出"); leave(); finish() }
            .setNegativeButton("继续下棋", null).show()
    }

    override fun onBackPressed() = confirmExit()

    private fun leave() {
        host?.stop(); host = null
        guest?.stop(); guest = null
    }

    override fun onDestroy() { archiveGame("异常/强制退出"); super.onDestroy(); leave() }
}
