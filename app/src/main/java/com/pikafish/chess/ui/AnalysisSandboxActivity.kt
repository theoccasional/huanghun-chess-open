package com.pikafish.chess.ui

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.pikafish.chess.FixScale
import com.pikafish.chess.engine.CommentaryEngine
import com.pikafish.chess.engine.JniEngine
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.utils.SoundManager

/**
 * ★★★ 独立进程解惑沙盒（"外来的和尚会念经"）★★★
 *
 * 以独立进程（android:process=":sandbox"）运行，连主线程都与主界面分开：
 *  - 沙盒再卡、再崩，也只崩/冻住这个子进程，主界面对弈、宣传视频、主引擎全程不受影响
 *  - 自己持有全新 JniEngine 实例（与主引擎互不相干），懒启动 + 轻量握手 + 最小内存
 *  - 棋子皮肤/大小跟随主界面（BoardVisualPrefs），天然 1:1 缩放，最稳妥
 *  - FEN 由主界面通过 Intent 传入，返回主界面通过 setResult 通知恢复按钮
 */
class AnalysisSandboxActivity : ComponentActivity() {
    /** ★ 八风不动：系统字体大小/字体风格怎么改都不影响本界面 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    companion object {
        private const val TAG = "AnalysisSandbox"
        const val EXTRA_FEN = "sandbox_fen"
        const val EXTRA_FLIPPED = "sandbox_flipped"

        // 轻量引擎最小配置
        private const val HASH_MB = 16
        private const val THREADS = 1
    }

    // ── 本地对局棋盘（此进程内独享）──
    private var board = Board()

    // ── 独立引擎：懒启动 ──
    private var sandboxEngine: JniEngine? = null

    // ── 音效（与主界面同源：走子/吃子/将军/绝杀）──
    private var soundManager: SoundManager? = null
    @Volatile private var engineReady = false
    @Volatile private var engineStarting = false
    @Volatile private var analyzing = false      // 防重入：一次分析未完，忽略后续点击

    // ★ 引擎未就绪时走的第一步棋缓存：引擎一就绪就立刻补评，绝不漏评
    private var pendingAnalysis: PendingAnalysis? = null

    // ── 文案区 ──
    private lateinit var infoText: TextView

    // ── 棋盘容器（高度跟随主界面「棋盘大小」，完全一样大）──
    private lateinit var boardWrap: FrameLayout

    // ★ 全局底色：根悬浮容器 + 主标题
    private var rootFrame: FrameLayout? = null
    private lateinit var titleText: TextView

    // ── 棋盘视图 ──
    private val chessBoard by lazy {
        ChessBoardView(this).also { b ->
            b.onMoveMade = { move ->
                if (analyzing) {
                    // 上一次分析还没回完，正文区已提示"评手中…"，此步仅计入局面，不再重复搜
                    onSandboxMove(move, forceRecordOnly = true)
                } else {
                    onSandboxMove(move, forceRecordOnly = false)
                }
                b.clearSelection()
                true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ 全流程防崩溃：沙盒进程一启动有任何未捕获异常，就地拦下并显示原文，
        //   绝不崩掉进程、绝不把整个 APP 带回桌面（沙盒的本分就是"再崩也不波及主界面"）。
        try {
            onCreateSafe(savedInstanceState)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "沙盒初始化被拦截（避免闪退）", t)
            val sc = ScrollView(this).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#DD211005"))
            }
            val tv = TextView(this).apply {
                text = "沙盒初始化没绷住，但已拦下，没闪退：\n\n" +
                    t.javaClass.simpleName + "：" + (t.message ?: t.toString()) + "\n\n" +
                    "请把上面这几行抄给开发者，我马上修。\n\n（已记录到日志 AnalysisSandbox）"
                setTextColor(android.graphics.Color.parseColor("#F0E8D0"))
                textSize = 14f
                setLineSpacing(2f, 1.1f)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }
            sc.addView(tv)
            setContentView(sc)
        }
    }

    private fun onCreateSafe(savedInstanceState: Bundle?) {
        val fen = intent?.getStringExtra(EXTRA_FEN) ?: return finish()
        try {
            board.loadFromFen(fen)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "FEN加载失败", e)
            return finish()
        }
        // ★ 红黑上下顺序与主棋盘神同步（红上黑下 或 红下黑上，主棋盘怎么摆就怎么摆）
        val flipped = intent?.getBooleanExtra(EXTRA_FLIPPED, false) ?: false
        chessBoard.setFlipped(flipped)

        // ★★★ 视觉跟随主界面（棋盘大小/棋子大小/棋线/背景/皮肤/个性化）：
        //   在 setContentView 前一次性套好 → 进沙盒首帧即与主界面一致，绝不闪屏跳动。
        //   （旧版各沙盒自存一套"棋盘/棋子/棋线/皮肤/背景"调节，已全部废除）
        BoardVisualPrefs.applyToBoard(this, chessBoard)

        setContentView(buildContentView())
        applyGlobalBackdrop()
        // ★ 音效初始化：与主界面同一套资源（走子/吃子/将军/绝杀），音量也跟随主界面设置。
        try { soundManager = SoundManager().also { it.loadSounds(this) } } catch (_: Exception) {}
        refreshHint()
    }

    /** 落子后播放走子/吃子/将军/绝杀音效（与主界面同源）。 */
    private fun playMoveSound(captured: Boolean, inCheck: Boolean, isMate: Boolean) {
        try {
            val sm = soundManager ?: return
            if (captured) sm.play("eat") else sm.play("move")
            if (inCheck) sm.play("check")
            if (isMate) sm.play("end")
        } catch (_: Exception) {}
    }

    /** 套用全局托底色：根背景用所选底色，标题文字换对应衬托色。 */
    private fun applyGlobalBackdrop() {
        try {
            val color = com.pikafish.chess.ui.GlobalBackdrop.currentColor(this)
            val fg = com.pikafish.chess.ui.GlobalBackdrop.foreground(this, color)
            rootFrame?.background = com.pikafish.chess.ui.GlobalBackdrop.backdropDrawable(this)
            if (::titleText.isInitialized) titleText.setTextColor(fg)
        } catch (_: Exception) { }
    }

    // ★ 正文/面板文字的前景色：随全局托底色自适应（浅底用深字、深底用浅字）。
    //   彻底解决旧版把 infoText 等写死成浅米色(#F0E8D0)，一旦托底色偏浅就"浅字配浅底"灰蒙蒙看不清的问题。
    private val sandboxFg: Int
        get() = runCatching {
            val c = com.pikafish.chess.ui.GlobalBackdrop.currentColor(this)
            com.pikafish.chess.ui.GlobalBackdrop.foreground(this, c)
        }.getOrDefault(-0x1000000)   // 兜底纯黑，避免全白

    private fun buildContentView(): FrameLayout {
        // ★ 与主界面同一套"悬浮贴纸"结构：棋盘从 y=0 铺满全屏，顶栏是悬浮框（不把棋盘往下顶），
        //   底部返回/正文为底贴纸。棋盘红方底线锚定在"屏高−底栏高"上方 —— 顶栏→首行棋子的
        //   间距、棋盘起始点、大小滑块全部与主界面同源一致，不闪屏不跳动。
        val stage = FrameLayout(this).apply {
            // ★ 根背景直接用全局底色（与主界面棋盘/全局底色同色），不另配深色，去雾不串色
            setBackgroundColor(com.pikafish.chess.ui.GlobalBackdrop.currentColor(this@AnalysisSandboxActivity))
        }
        rootFrame = stage

        // ── 棋盘：宽度铺满 + 高度铺满（同主界面全屏方案，顶部从 y=0 起）──
        boardWrap = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        boardWrap.addView(chessBoard, FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT)
        stage.addView(boardWrap)

        // ── 顶栏（悬浮贴纸，逐项克隆主界面 layoutStatusBar：38dp 框、#FFF3C4、elevation 10）──
        val bar = com.pikafish.chess.ui.BoardVisualPrefs.buildTopBar(this, "※ 解惑沙盒")
        titleText = bar.getChildAt(0) as TextView
        stage.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(BoardVisualPrefs.TOP_BAR_DP),
            android.view.Gravity.TOP))
        titleText.setTextColor(sandboxFg)

        // ── 底部悬浮层：正文点评区 + 返回行（说明文字按内容自适应高度，绝不占 42% 屏 →
        //   不再把棋盘往下压/淹没，棋盘保住主界面同源大小；返回栏沉底与背谱坐标一致）──
        // ★ 完全不透明实体底板（0xFF）：去除"半透明薄膜盖在棋盘下缘"的遮层罩感，
        //   棋盘区域清晰干净，实体点评板独立于棋盘之下，不再有雾化叠罩。
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ★ 内容区背景 = 全局底色（主界面棋盘/全局底色），去雾不另配色：
            //   用户在主界面怎么调全局底色，这里就跟成什么样；不调就用默认全局底色。
            setBackgroundColor(com.pikafish.chess.ui.GlobalBackdrop.currentColor(this@AnalysisSandboxActivity))
        }
        infoText = TextView(this).apply {
            setTextColor(sandboxFg)                          // ★ 自适应前景色，浅/深托底色都看得清
            textSize = 16f
            setLineSpacing(4f, 1.15f)
            setTypeface(null, android.graphics.Typeface.BOLD) // 正文醒目些
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        // ★ 说明文字区按内容自适应高度（WRAP_CONTENT，不固定 42% 屏）：
        //   文字多高它就占多高，棋盘拿到下方全部剩余空间，不被"淹没一大截"。
        val infoScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        infoScroll.addView(infoText)
        // ★ 返回按钮沉底：与背谱沙盘相同（返回在说明区之后 → 落到屏幕底部，坐标看齐背谱）。
        bottom.addView(infoScroll)
        bottom.addView(buildBackBar())
        stage.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM))

        // ★ 主界面同源锚定：棋盘红方底线贴在「屏高 − 底栏高」上方（2mm 间隙由棋盘自身保证），
        //   顶栏悬浮其上。据此棋盘红黑完整、首行起点与主界面逐像素一致。
        stage.post {
            try {
                val screenH = stage.height
                val topPx = dp(BoardVisualPrefs.TOP_BAR_DP)
                val bottomH = bottom.height
                if (screenH > 0 && screenH - bottomH > topPx) {
                    chessBoard.setContentBand(topPx.toFloat(), (screenH - bottomH).toFloat())
                }
            } catch (_: Exception) { }
        }

        return stage
    }

    /** 棋盘下方一行：右下角小"返回"（不占地、可用导航栏返回）。
     *  ★ 与背谱沙盘完全看齐：底部垫高 46dp、沉在屏幕底部，坐标同背谱的返回按钮。 */
    private fun buildBackBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            // ★ 返回键上移：底部垫高 46dp，比系统导航条高出一截，与背谱沙盘的返回完全同位置。
            setPadding(dp(0), dp(2), dp(2), dp(46))
        }
        val backBtn = Button(this).apply {
            text = "返回"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#E8D0A0"))
            setBackgroundColor(android.graphics.Color.parseColor("#5A4322"))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(32))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        bar.addView(backBtn)
        return bar
    }

    // 棋盘大小 1.0 基准高度（dp）
    // ★ 以“宽铺满”为准：基准高 ≈1.16×屏宽，大于盘面所需（≈1.13×屏宽），
    //   保证棋盘按宽度铺满、两侧不留空白窄条；盘面不超高，顶栏不会碰到边行棋子（对齐主界面）。
    //   最终容器高度 = 基准高 × 主界面棋盘倍数（BoardVisualPrefs.boardHeightPx），完全跟随主界面。
    private fun baseBoardHeightDp(): Int =
        ((resources.displayMetrics.widthPixels / resources.displayMetrics.density) * 1.16f).toInt().coerceAtLeast(400)

    /** 初始加载棋盘（棋子皮肤/大小等已由 BoardVisualPrefs 跟随主界面） */
    private fun refreshHint() {
        try {
            chessBoard.setBoard(board)
            // 皮肤跟随主界面（BoardVisualPrefs 已套好）、boardScale/pieceScale=1.0，绝不 setBoardScale/setPieceScale
            val cname = if (board.currentPlayer == PieceColor.RED) "红方" else "黑方"
            infoText.text = "【$cname 走棋】沙盒已就绪\n" +
                "点击本方棋子，再点目标格（白点=合法走法），\n" +
                "沙盒会用独立的引擎分析这步棋。\n" +
                "（引擎按需慢启：第一次分析约等 2~5 秒，之后秒回）"
        } catch (e: Exception) {
            android.util.Log.e(TAG, "棋盘加载失败", e)
            infoText.text = "棋盘加载失败：${e.message}"
        }
    }

    // ═══ 交互：点一步，沙盒引擎应一步（严格轮走 + 每步最强手对比）═══
    private fun onSandboxMove(move: Move, forceRecordOnly: Boolean = false) {
        // ★ 严格轮走：只能走轮到的那一方，绝不破规矩
        val mustColor = board.currentPlayer
        val moverColor = try { board.getPiece(move.from)?.color } catch (e: Exception) { null }
        if (moverColor != mustColor) {
            val cname = if (mustColor == PieceColor.RED) "红方" else "黑方"
            infoText.text = "现在轮到【$cname】走，只能走${cname}的棋子。\n"
            return
        }

        val legal = try {
            board.getLegalMoves(mustColor)
                .any { it.from == move.from && it.to == move.to }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "合法走法校验失败", e)
            false
        }
        if (!legal) {
            infoText.text = "这步棋不合法：${move.toUci()}\n"
            return
        }
        val uci = move.toUci()
        val colorName = if (mustColor == PieceColor.RED) "红方" else "黑方"
        val notation = CommentaryEngine.uciToChineseNotation(uci, board)

        // 快照走子前局面（用于分析这一手本身）
        val before = Board().also { try { it.loadFromFen(board.toFen()) } catch (_: Exception) {} }
        // 吃子判定：落点原本就有对方棋子（走子前快照上取，颜色须与走子方不同）
        val capBefore = try { before.getPiece(move.to) } catch (_: Exception) { null }
        val isCapture = capBefore != null && capBefore.color != moverColor

        // 推进沙盒棋盘 → 可续盘
        var isMate = false
        var inCheck = false
        try {
            board.makeMoveUnchecked(move)
            chessBoard.setBoard(board)
            inCheck = board.isInCheck(board.currentPlayer)
            isMate = board.isCheckmate(board.currentPlayer)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "落子失败", e)
            infoText.text = "落子失败：${e.message}"
            return
        }
        // ★ 音效：与主界面同源（走子/吃子/将军/绝杀），落子即响，不必等分析回包。
        playMoveSound(captured = isCapture, inCheck = inCheck, isMate = isMate)

        // 上一步分析未完时，此步仅计入局面，提示稍后回补，绝不并发开搜
        if (forceRecordOnly || analyzing) {
            infoText.text = "【$colorName 走 ${notation}】已计入局面\n" +
                "（引擎正在处理上一手，稍候会自动回补对本手点评）"
            return
        }

        // 引擎懒启动：尚未就绪 → 记住这一步，等就绪后立刻补评，绝不漏评
        if (!engineReady) {
            pendingAnalysis = PendingAnalysis(before, move, colorName, isMate, mustColor)
            infoText.text = "【$colorName 走 ${notation}】已计入局面\n" +
                "独立引擎首次加载中…\n" +
                "引擎一就绪，我马上对这手【${notation}】给出点评。"
            ensureEngineAsync()
            return
        }
        runAnalysis(before, move, notation, colorName, isMate, mustColor)
    }

    /** 后台懒启动独立引擎，全程异常兜底，绝不崩本进程外的东西 */
    private fun ensureEngineAsync() {
        if (engineStarting || engineReady) return
        engineStarting = true
        val t = Thread {
            try {
                val e = JniEngine(this)
                e.hashSize = HASH_MB
                e.threadCount = THREADS
                val locErr = e.locateEngine()
                if (locErr != null) { engineStarting = false; engineFail("引擎定位失败：$locErr"); return@Thread }
                val nnueErr = e.copyCurrentNNue()
                if (nnueErr != null) { engineStarting = false; engineFail("NNUE 加载失败：$nnueErr"); return@Thread }
                val startErr = e.startEngine()
                if (startErr != null) { engineStarting = false; engineFail("引擎启动失败：$startErr"); return@Thread }
                val initErr = e.liteInit()
                if (initErr != null) { engineStarting = false; engineFail("引擎初始化失败：$initErr"); return@Thread }

                sandboxEngine = e
                engineReady = true
                engineStarting = false
                // ★ 引擎就绪：若有我刚走那手还没点评，立刻补评，绝不漏评
                val pa = pendingAnalysis
                pendingAnalysis = null
                if (pa != null) {
                    val move = pa.move
                    val before = pa.before
                    val cn = pa.colorName
                    val mate = pa.isMate
                    val mc = pa.mover
                    runOnUiThread {
                        val notation = CommentaryEngine.uciToChineseNotation(move.toUci(), before)
                        runAnalysis(before, move, notation, cn, mate, mc)
                    }
                } else {
                    runOnUiThread { infoText.text = infoText.text.toString() + "\n（独立引擎已就绪 ✅）" }
                }
            } catch (e: Throwable) {
                engineStarting = false
                android.util.Log.e(TAG, "沙盒引擎启动失败", e)
                runOnUiThread { infoText.text = "独立引擎启动被中止：${e.message}\n（可关闭沙盒重试）" }
            }
        }
        t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _th, e ->
            engineStarting = false
            android.util.Log.e(TAG, "沙盒引擎线程异常", e!!)
            runOnUiThread { infoText.text = "独立引擎线程异常：${e.message}\n（可关闭沙盒重试）" }
        }
        t.start()
    }

    /**
     * 分析这一手本身：单次 evaluateMove 一把返回
     *   - bestMoveUci：走子前局面的本方最强手（与用户这一步对比）
     *   - humanScore：走后局面、站在走子方视角的评分
     *   - humanPv：走后对方预计应对
     * 不再嵌套两次搜索，串行保序，防止并发 go 弄崩沙盒引擎导致闪退。
     */
    private fun runAnalysis(before: Board, move: Move, notation: String, colorName: String, isMate: Boolean, mover: PieceColor) {
        if (analyzing) return           // 防重入：上一次还没分析完，忽略本次
        val engine = sandboxEngine
        if (engine == null || !engine.isEngineReady()) {
            infoText.text = "【$colorName 走 ${notation}】\n（独立引擎尚未就绪，请稍候再点）"
            return
        }
        analyzing = true
        infoText.text = "【$colorName 走 ${notation}】独立引擎评手中…\n"

        val userUci = move.toUci()
        val oppName = if (mover == PieceColor.RED) "黑方" else "红方"
        try {
            engine.evaluateMove(
                before, userUci,
                onResult = { bestMoveUci, _bestScore, humanScore, humanPv, _depth ->
                    analyzing = false
                    val sb = StringBuilder()
                    // ★ 挤空气行：每行都有字、不搞空白行（矮机型的棋盘不被点评区往下压），
                    //   只保留一条分隔空行做视觉分隔，成本文唯一的"空气行"。
                    sb.append("★ ${colorName} 走 ${notation}\n")
                    sb.append("━━━━━━━━━━━━━━━\n")

                    if (bestMoveUci.isNotEmpty()) {
                        val bestNotation = CommentaryEngine.uciToChineseNotation(bestMoveUci, before)
                        sb.append("◆ 本方最强手应是：$bestNotation\n")
                        if (bestMoveUci == userUci) {
                            sb.append("   ✓ 你这一手就是最强手，下得很好！\n")
                        } else {
                            val userN = CommentaryEngine.uciToChineseNotation(userUci, before)
                            sb.append("   ✗ 你走的是：$userN\n")
                            sb.append("   （与最强手不同，见上；可续走对比走势）\n")
                        }
                    } else {
                        sb.append("◆ 引擎暂未能给出最强手评分。\n")
                    }

                    val scoreStr = when {
                        com.pikafish.chess.engine.JniEngine.isMateScore(humanScore) -> {
                            val plies = com.pikafish.chess.engine.JniEngine.matePliesOf(humanScore) ?: 1
                            if (humanScore > 0) "${colorName}绝杀在望（${plies}步杀）"
                            else "$oppName 大优（${colorName}被围，${plies}步内成杀）"
                        }
                        humanScore == 0 -> "局面均势"
                        humanScore > 0 -> "${colorName}略优 +$humanScore"
                        else -> "$oppName 略优 ${-humanScore}"
                    }
                    // ★ 终局判定：最后一步若走子方已将死 / 逼得对方无子可走（困毙），
                    //   胜负已分，绝不报“均势”。直接给出定论，不误导。
                    val noMoves = try { board.getLegalMoves(board.currentPlayer).isEmpty() } catch (_: Exception) { false }
                    val endText = when {
                        isMate -> "被将杀"
                        noMoves -> "已无子可走（困毙）"
                        else -> null
                    }
                    sb.append("◆ 走后局面（走子方视角）：$scoreStr\n")
                    if (endText != null) {
                        sb.append("● 胜负已分，棋局结束（${colorName}${endText}，本盘到此为止）\n")
                    } else if (isMate) {
                        sb.append("● 并将杀！这一手直接绝杀。\n")
                    }
                    if (humanPv != null && humanPv.isNotEmpty()) {
                        val pvMoves = humanPv.trim().split(" ")
                        if (pvMoves.isNotEmpty()) {
                            val first = CommentaryEngine.uciToChineseNotation(pvMoves[0], board)
                            sb.append("◆ $oppName 预计应对：$first\n")
                        }
                    }
                    sb.append("（现在轮到${oppName}走，可继续摆${oppName}的棋看它的变着）")
                    infoText.text = sb.toString()
                }
            )
        } catch (e: Throwable) {
            analyzing = false
            android.util.Log.e(TAG, "分析失败", e)
            infoText.text = "分析失败：${e.message}"
        }
    }

    private fun engineFail(msg: String) {
        runOnUiThread { infoText.text = "$msg\n（可关闭沙盒重试）" }
    }

    override fun onDestroy() {
        try { sandboxEngine?.shutdown() } catch (_: Exception) {}
        sandboxEngine = null
        try { soundManager?.release() } catch (_: Exception) {}
        soundManager = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** 待补评的一手（引擎未就绪时缓存，就绪后立刻点评） */
    private data class PendingAnalysis(
        val before: Board,          // 走子前局面快照
        val move: Move,             // 落子
        val colorName: String,      // 走子方名
        val isMate: Boolean,        // 是否将杀
        val mover: PieceColor       // 走子方颜色
    )
}