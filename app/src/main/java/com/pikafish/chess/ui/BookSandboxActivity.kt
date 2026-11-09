package com.pikafish.chess.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import com.pikafish.chess.FixScale
import com.pikafish.chess.engine.BookMemorizer
import com.pikafish.chess.engine.CommentaryEngine
import com.pikafish.chess.engine.LocalBookManager
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.qipu.GameJournal
import com.pikafish.chess.qipu.QipuBook
import com.pikafish.chess.qipu.QipuBrowser
import com.pikafish.chess.qipu.QipuGame
import com.pikafish.chess.utils.SoundManager

/**
 * ★★★ 背谱沙盘（点“背谱”直接进入，100% 确定性，不弹窗绕弯）★★★
 *
 * 纯粹打谱用的沙盘 —— 不跑皮卡鱼引擎配置，用的是“谱面约束选择机制”（BookMemorizer）：
 *  - 谱源：先用主界面当前已载的 CBL 主线（EXTRA_LINE_*），也可点“选谱”套娃进棋谱宝库换一本
 *  - 只许走谱上真有的着法，谱外点不动（棋子不动就是不动）
 *  - 背红谱：黑方由谱自动应子；背黑谱：红方自动；背双方：都你手走
 *  - 同一局面有多种变化时，自动应子按【遍历穷尽】轮换，并在棋盘正中弹透明气泡提示走第几号变化
 *  - 下方打谱区逐手列出你打的谱，变招格上标出“变 N”的记号，不直接展开变化细节
 *
 * 布局“紧随棋盘”：棋盘在上，控制/打谱区在 ScrollView 里随其下移，可收起/展开，绝不与棋盘重合。
 */
class BookSandboxActivity : ComponentActivity() {
    /** ★ 八风不动：系统字体大小/字体风格怎么改都不影响本界面 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    companion object {
        private const val TAG = "BookSandbox"
        const val EXTRA_FEN = "book_fen"
        const val EXTRA_FLIPPED = "book_flipped"
        const val EXTRA_LINE_NAME = "book_line_name"
        const val EXTRA_LINE_START_FEN = "book_line_start_fen"
        const val EXTRA_LINE_MOVES = "book_line_moves"
        // ★ 变着支线：每条支线一根字符串（空格分隔的 UCI 序列），与主线一起入谱
        const val EXTRA_LINE_VARIATIONS = "book_line_variations"
        const val STANDARD_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"
    }

    // ── 本沙盘自用棋盘（纯棋盘，不跑引擎）──
    private var board = Board()

    // ── 背谱引擎：对外置库（空）+ 注入主线 都走 BookMemorizer 统一约束 ──
    private val localBook = LocalBookManager()
    private val bookMem = BookMemorizer(localBook)

    // ── 音效（与主界面同源：走子/吃子/将军/绝杀）──
    private var soundManager: SoundManager? = null

    // ── 打谱记录：每手 uci + 走子前FEN + 中文记谱 + 该局面候选变招数 ──
    private data class Step(val uci: String, val fenBefore: String, val notation: String, val branches: Int)

    // ── 棋子加成记录（用于给谱面“自动应”的一方几步后继续，防止与用户手点冲突）──
    // 用一个简单环形：需要自动应时，不再让用户点那一步，直接由引擎给。
    private val steps = ArrayList<Step>()

    // ★ 背谱完成提示去重：同一则谱只弹一次“此变化背谱已完成”，换谱/换边后重置。
    private var completionShown = false
    // ★ 当前谱起始局面（“重新背”时回到谱头）
    private var currentStartFen = ""

    // ── UI 件 ──
    private lateinit var boardWrap: FrameLayout
    private lateinit var panelMoves: LinearLayout      // 打谱展示区（标题+固定高滚动棋谱+状态）
    private lateinit var movesText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView   // 菜单下一排：本谱/目录/第几局/进度
    private lateinit var bubble: TextView
    private lateinit var chipRed: Button
    private lateinit var chipBlack: Button
    private lateinit var chipBoth: Button
    private lateinit var btnPrev: Button          // 底部固定：上一则
    private lateinit var btnNext: Button          // 底部固定：下一则
    private lateinit var btnFlip: Button          // 导航排最左：翻转棋盘（背黑方时看黑方视角）

    // ── 当前背谱的书本会话（点选谱/上一则/下一则 在同一本内逐则切换）──
    private var sessionCat = ""
    private var sessionBook: QipuBook? = null
    private var sessionGames: List<QipuGame> = emptyList()
    private var sessionIndex = -1
    private var sessionTitle = ""   // 当前局的完整题目（赛事·对局者，指名道姓）
    private var sessionGame: QipuGame? = null   // 当前局（取 开局旁白/每手旁白/结局总评 用）

    // ── 透明悬浮文字窗（可拖动/可关闭；装 背谱说明+本局介绍+半中腰旁白+总评，不占底部版面）──
    private lateinit var floatWin: LinearLayout
    private lateinit var floatScroll: ScrollView
    private lateinit var floatText: TextView
    private var floatShown = false
    private var floatClosedByUser = false   // 用户手动关过 → 只在出现新旁白时再自动亮
    private var lastFloatKey = ""           // 已展示过的内容指纹（避免每次走子都弹）

    // ★ 全局托底色：根悬浮容器 + 主标题
    private var rootFrame: FrameLayout? = null
    private lateinit var titleText: TextView

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
    //   彻底解决旧版把提示/进度/打谱/滑条文字写死成浅米色，一旦托底色偏浅就"浅字配浅底"灰蒙蒙看不清的问题。
    private val sandboxFg: Int
        get() = runCatching {
            val c = com.pikafish.chess.ui.GlobalBackdrop.currentColor(this)
            com.pikafish.chess.ui.GlobalBackdrop.foreground(this, c)
        }.getOrDefault(-0x1000000)   // 兜底纯黑，避免全白

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("book_sandbox", MODE_PRIVATE) }
    private val movesCollapsed: Boolean get() = prefs.getBoolean("moves_collapsed", false)

    // ═══ 菜单栏固定下移量（dp）：与棋盘拉开缓冲间距，棋盘/棋子调大也不挤菜单。
    //   按用户定死 12dp（截图确认）+ 长文已移入悬浮窗，底栏更矮，再放宽到 18dp 保安全距离。
    private val MENU_GAP_DP = 18

    private val chessBoard by lazy {
        ChessBoardView(this).also { b ->
            b.onMoveMade = { move ->
                handleUserMove(move)
                b.clearSelection()
                true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { onCreateSafe(savedInstanceState) } catch (t: Throwable) {
            android.util.Log.e(TAG, "背谱沙盘初始化被拦截", t)
            val sc = ScrollView(this).apply { setBackgroundColor(0xFF211005.toInt()) }
            val tv = TextView(this).apply {
                text = "背谱沙盘没绷住，但已拦下：\n${t.javaClass.simpleName}：${t.message}"
                setTextColor(0xFFF0E8D0.toInt()); textSize = 14f
                setPadding(mdp(12), mdp(12), mdp(12), mdp(12))
            }
            sc.addView(tv); setContentView(sc)
        }
    }

    private fun onCreateSafe(savedInstanceState: Bundle?) {
        val fen = intent?.getStringExtra(EXTRA_FEN) ?: return finish()
        try { board.loadFromFen(fen) } catch (e: Exception) {
            android.util.Log.e(TAG, "FEN加载失败", e); return finish()
        }
        // ★ 音效初始化：与主界面同一套资源（走子/吃子/将军/绝杀），音量也跟随主界面设置。
        try { soundManager = SoundManager().also { it.loadSounds(this) } } catch (_: Exception) {}
        chessBoard.setFlipped(intent?.getBooleanExtra(EXTRA_FLIPPED, false) ?: false)
        // ★★★ 视觉跟随主界面（棋盘大小/棋子大小/棋线/背景/皮肤/个性化）：
        //   在 setContentView 前一次性套好 → 进沙盒首帧即与主界面一致，绝不闪屏跳动。
        //   （旧版背谱沙盘自存一套"棋盘/棋子/棋线/皮肤/背景"调节，已全部废除）
        BoardVisualPrefs.applyToBoard(this, chessBoard)

        setContentView(buildContentView())
        applyGlobalBackdrop()

        // 载入默认谱源（主界面当前主线）→ 进入背谱
        val lineName = intent?.getStringExtra(EXTRA_LINE_NAME) ?: "当前棋谱"
        val ls = intent?.getStringExtra(EXTRA_LINE_START_FEN) ?: fen
        val lm = intent?.getStringArrayListExtra(EXTRA_LINE_MOVES) ?: arrayListOf()
        val lv = (intent?.getStringArrayListExtra(EXTRA_LINE_VARIATIONS) ?: arrayListOf())
            .mapNotNull { s -> s.split(' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() } }
        loadLineSource(lineName, ls, lm, lv)
        sessionTitle = lineName   // ★ 初始主线也把谱名放进悬浮窗（无旁白时显示题名兜底）

        bookMem.active = true
        bookMem.mode = BookMemorizer.Mode.RED   // 默认先背红方谱
        refreshChips()
        chessBoard.setBoard(board)
        // ★ 沉浸式避让：隐藏系统导航键 + 底部菜单按导航键高度动态上移（学自主界面底部导航条）。
        applyNavBarInset()
        refreshAll()
        refreshNav()   // ★ 初始主线无上一则/下一则 → 两键先置灰
        maybeAutoReply(500)   // ★ 进入时若正轮到"谱方自动应"的那侧（如背黑·红先），立即自动开走
    }

    // ★★★ 沉浸式：默认隐藏系统导航键（用户上滑可临时唤出）。导航键不显示 → 底部菜单永不与它干涉。
    //   这与主界面 layoutBottomNav 完全同一做法。导航键下方不放任何交互内容，最安全。
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            applyNavBarInset()
        }
    }

    /**
     * ★ 底部菜单行按"系统导航键高度"自动上移（学自主界面 applyNavBarInset）：
     *   - 读 insets 的 navigationBars().bottom 得到导航键真实高度；
     *   - 给 stage（rootFrame）底部加该高度 padding → 菜单行整体悬在导航键上方；
     *   - 底距封顶 28dp，防止被顶得太高；
     *   - 导航键隐藏时也预留手势条空间。
     *   结果：任何手机/模拟器上，底部菜单都不贴在导航键上、也不过高。
     */
    private fun applyNavBarInset() {
        try {
            val content = window.decorView.findViewById<View>(android.R.id.content)
            if (content == null) return
            ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                rootFrame?.let { st ->
                    val pad = if (navBar > 0) navBar.coerceAtMost(mdp(28)) else mdp(28)
                    st.setPadding(0, 0, 0, pad)
                }
                insets
            }
            content.requestApplyInsets()
        } catch (e: Exception) {
            // 布局/系统差异时忽略，不阻断
        }
    }

    // ═══════════════ 构建界面 ═══════════════
    // 三段式固定布局：上(顶栏/棋盘/单行菜单/进度) 固定不乱动；中(设置+棋谱) 独立滚屏；
    // 下(上一则/下一则) 固定在屏幕底，中间全留给棋谱位。
    private fun buildContentView(): View {
        // ★ 与主界面同一套"悬浮贴纸"结构：棋盘从 y=0 铺满全屏，顶栏是悬浮框（不把棋盘往下顶），
        //   底部（菜单/进度/打谱滚动区/返回）为底贴纸。棋盘锚定规则同主界面 ——
        //   顶栏→首行棋子间距、起始点、大小全一致，绝不因多一排控件而错位。
        val stage = FrameLayout(this).apply {
            // ★ 根背景直接用全局底色（与主界面棋盘/全局底色同色），不另配深色，去雾不串色
            setBackgroundColor(com.pikafish.chess.ui.GlobalBackdrop.currentColor(this@BookSandboxActivity))
        }
        rootFrame = stage

        // ── 棋盘：宽度铺满 + 高度铺满（同主界面全屏方案，顶部从 y=0 起）──
        boardWrap = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        boardWrap.addView(chessBoard, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        // 居中变招气泡（透明、点一下即关）
        bubble = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            visibility = android.view.View.GONE; alpha = 0f
            setPadding(mdp(16), mdp(12), mdp(16), mdp(12))
            setTextColor(0xFFF6E8C8.toInt())
            setBackgroundResource(com.pikafish.chess.R.drawable.bubble_branch_bg)
            textSize = 15f
            setOnClickListener { hideBubble() }
        }
        val bubbleLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        bubbleLp.gravity = android.view.Gravity.CENTER
        boardWrap.addView(bubble, bubbleLp)
        stage.addView(boardWrap)

        // ── 顶部悬浮贴纸：标题框（逐项克隆主界面 layoutStatusBar：38dp 框、#FFF3C4、elevation 10）+ 提示行 ──
        val topArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val bar = com.pikafish.chess.ui.BoardVisualPrefs.buildTopBar(this, "※ 背谱沙盘", rightLabel = "背谱 · 记谱")
        titleText = bar.getChildAt(0) as TextView
        topArea.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, mdp(BoardVisualPrefs.TOP_BAR_DP)))
        // ★ 提示语已从顶栏下方移走（不再遮挡上边棋子观感），改放到下方菜单栏那排之后。
        stage.addView(topArea, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP))

        // ── 底部悬浮层：菜单 + 进度 + 打谱滚动区 + 返回（对齐"解惑沙盒"：内容自适应高度，
        //   不固定 45% 屏 → 消除模拟器上超高/内容塌陷/溢出，剩余空间全部让给棋盘）──
        // ★ 全屏统一全局底色：彻底去掉独立的"土黄菜单面板"色块（menuPanel 已废除），
        //   菜单/提示/进度/打谱/返回直接坐在全局背景色上，风格与"解惑沙盒"第一张一致。
        // ★ 底部容器背景 = 全局底色；上边距 12dp 缓冲，与棋盘拉开稳定间距，绝不压边行棋子。
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, mdp(MENU_GAP_DP + 4), 0, 0)
            setBackgroundColor(com.pikafish.chess.ui.GlobalBackdrop.currentColor(this@BookSandboxActivity))
        }
        // 直接以 bottom 作为承载面板（全局底色即菜单底色，无额外色块）
        // ── 单行菜单：背红/背黑/背双方 | 选谱 | 棋谱（次序不变；「设置」已废除，菜单均匀拨开挤成一行）──
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            // ★ 菜单内上边距并入容器(见 bottom)，土黄底色从按钮顶边开始，不留黄边。
            setPadding(0, 0, 0, mdp(2))
        }
        // ★★ 六个按键（背红/背黑/背双方/说明/选谱/返回）同排一行，各占屏幕 1/6、宽窄一律均等。
        //   旧版把前三键塞进一个 weight=1 的子行去"撑满剩余宽度"，后三键却按固定 dp 窄着排，
        //   于是前三键宽得离谱、后三键挤成窄条 —— 现统一成 6 等分（每键权重 1，合计满屏）。
        val oneSixth = { LinearLayout.LayoutParams(0, mdp(30), 1f) }
        chipRed = chip("背红") { bookMem.mode = BookMemorizer.Mode.RED; onModePicked(false) }
        chipBlack = chip("背黑") { bookMem.mode = BookMemorizer.Mode.BLACK; onModePicked(true) }
        chipBoth = chip("背双方") { bookMem.mode = BookMemorizer.Mode.BOTH; onModePicked(null) }
        menu.addView(chipRed, oneSixth())
        menu.addView(chipBlack, oneSixth())
        menu.addView(chipBoth, oneSixth())
        // ★ 「说明」开关：点一下弹窗，显示——当前背的是哪个谱段、已经背到什么开局/变例。
        menu.addView(menuBtn("说明") { showExplainDialog() }, oneSixth())
        menu.addView(menuBtn("选谱") { openBookVault() }, oneSixth())
        // ★ 返回也并入底排最右（和选谱并肩），不再单独沉底散开 —— 与背谱/选谱同一条兜底线。
        menu.addView(buildBackBtn(), oneSixth())
        // ★ 按钮行(含返回)在下方 midScroll 之后才加入 bottom → 沉到底部兜底线；
        //   此处先构建、稍后追加（见 buildContentView 末尾）。

        // ── 提示（顶多两行，放不下一行就自动换行，不再遮上边棋子）──
        // ★ 已废除：背谱说明长文改放「透明悬浮文字窗」（见 buildFloatWindow），
        //   底栏不再留常驻提示行 → 长文再多也不会把底栏往上拱、压到棋盘。

        // ── 菜单下一排（固定）：现在背的是哪本书、哪个目录、第几局、进度多少 ──
        progressText = TextView(this).apply {
            textSize = 13f; setTextColor(sandboxFg); setTypeface(null, android.graphics.Typeface.BOLD)   // ★ 自适应前景色
            setPadding(0, mdp(2), 0, mdp(2))
            maxLines = 2
        }
        bottom.addView(progressText)
        refreshProgress()

        // ── 打谱滚动区：固定高度（约 4 行回合 = 8 个回合可见），回合再多也只在区内滚，
        //   绝不向上涌、不把下面的菜单按钮行往上顶、更不会淹没棋盘 ──
        val midScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                mdp(112))                       // ★ 固定高：约 4 行回合（8 回合）可见
            isFillViewport = true
            setBackgroundColor(0x00FFFFFF)
            isVerticalScrollBarEnabled = true   // ★ 显示滚动条，回合多时用户知道可滚
            scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        val midRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, mdp(4), 0)
        }
        midScroll.addView(midRoot)

        // ★ 棋盘/棋子/棋线/皮肤/背景调节已全部废除：本背谱沙盒视觉只跟随主界面
        //   （BoardVisualPrefs 在 setContentView 前一次性套好），界面不再设任何调节入口。

        // ── 打谱面板：标题 + 固定高滚动棋谱（状态行已移到下方「上一则/下一则」导航排居中）──
        panelMoves = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        panelMoves.addView(TextView(this).apply {
            text = "本局打谱（回合上标 变N = 该处有 N 个变招）"
            textSize = 13f; setTextColor(sandboxFg); setTypeface(null, android.graphics.Typeface.BOLD)   // ★ 自适应前景色
            setPadding(0, mdp(6), 0, mdp(2))
        })
        movesText = TextView(this).apply {
            textSize = 15f; setLineSpacing(2f, 1.15f); setTextColor(sandboxFg); setTypeface(null, android.graphics.Typeface.BOLD)   // ★ 自适应前景色
            setPadding(0, 0, 0, mdp(8))
        }
        midRoot.addView(movesText)             // ★ 棋谱文本直接放进固定高滚动区 midScroll(经 midRoot)
        panelMoves.addView(midScroll)
        bottom.addView(panelMoves)

        // ── 导航排（按钮排的上一排，与按钮排同一块铁板、一气呵成）：
        //   [上一则] [模式/谱源 状态居中] [下一则] —— 背完一则点「下一则」紧锣密鼓背下一则 ──
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, mdp(2), 0, mdp(2))
        }
        btnPrev = navBtn("上一则") { prevGame() }
        btnNext = navBtn("下一则") { nextGame() }
        statusText = TextView(this).apply {
            textSize = 12f; setLineSpacing(2f, 1.15f); setTextColor(sandboxFg)   // ★ 自适应前景色
            gravity = android.view.Gravity.CENTER
            setPadding(mdp(4), mdp(2), mdp(4), mdp(2))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
        }
        // ★ 翻转棋盘：按用户要求放在"上一则/下一则"这一排的最左（下面按钮排太挤）。
        //   背黑方时点一下 → 黑方在下、红方在上，主角身份名副其实；再点一下转回来。
        // ★ 按键等宽自适应：翻转/上一则/下一则 各占 1/6 屏，中间状态条占 1/2 —— 权重合计 6，
        //   窄机上三者自动收窄、绝不被挤出屏幕（不再用固定 dp 硬排）。
        btnFlip = navBtn("翻转") { toggleBoardFlip() }
        navRow.addView(btnFlip, LinearLayout.LayoutParams(0, mdp(34), 1f))
        navRow.addView(btnPrev, LinearLayout.LayoutParams(0, mdp(34), 1f))
        navRow.addView(statusText)
        navRow.addView(btnNext, LinearLayout.LayoutParams(0, mdp(34), 1f))
        bottom.addView(navRow)
        refreshNav()

        // ★★ 菜单按钮行（含返回）在 bottom 最末 → 与导航排同一块铁板，天然沉底兜底，绝不随棋谱上涌。
        //   棋谱区固定高（见 midScroll），版面再长也只在自己区内滚，不会向上顶菜单、淹没棋盘。
        bottom.addView(menu)
        stage.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM))

        // ★ 主界面同源锚定：棋盘红方底线贴在「屏高 − 底栏高」上方（2mm 间隙由棋盘自身保证），
        //   顶栏悬浮其上。据此棋盘红黑完整、首行起点与主界面逐像素一致。
        stage.post {
            try {
                val screenH = stage.height
                val topPx = dpSafe()
                val bottomH = bottom.height
                if (screenH > 0 && screenH - bottomH > topPx) {
                    chessBoard.setContentBand(topPx.toFloat(), (screenH - bottomH).toFloat())
                }
            } catch (_: Exception) { }
        }

        // ── 底部导航键（上一则/下一则）已按需移除：背谱/记谱时易误碰，删掉以免干扰 ──

        // ★★ 透明悬浮文字窗：盖在棋盘上（最上层），可拖动/可关闭/半透明。
        //   背谱说明 + 本局介绍 + 半中腰旁白 + 结局总评 全装在这里 —— 不占底部版面，
        //   任何手机上长文都不会把底栏拱上棋盘。默认落在顶栏正下方，按住标题条可拖走。
        val floatLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.START)
        floatLp.leftMargin = mdp(10); floatLp.rightMargin = mdp(10)
        floatLp.topMargin = dpSafe() + mdp(6)
        stage.addView(buildFloatWindow(), floatLp)

        return stage
    }

    // ═══════════════ 透明悬浮文字窗（可拖动/可关闭/半透明）═══════
    /**
     * 背谱说明 + 本局介绍(开局旁白) + 半中腰逐手旁白 + 结局总评，全装进这个悬浮窗：
     *  - 盖在棋盘上、不占底部版面 → 长文再多也不会把底栏往上拱、压到棋盘；
     *  - 按住「☰ 按住拖动」标题条可上下/左右拖动，✕ 可关闭，随时点底部「说明」再唤出；
     *  - 半透明底（约 88% 不透明），关掉前仍能透出底下棋盘。
     */
    private fun buildFloatWindow(): LinearLayout {
        val base = com.pikafish.chess.ui.GlobalBackdrop.currentColor(this)
        val fg = com.pikafish.chess.ui.GlobalBackdrop.foreground(this, base)
        val win = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = mdp(10).toFloat()
                setColor((base and 0x00FFFFFF) or 0xE2000000.toInt())   // ★ 半透明：透得出背后棋盘
                setStroke(mdp(1), 0x44000000)
            }
            elevation = mdp(8).toFloat()
            visibility = android.view.View.GONE
        }
        // ── 标题条：左半「☰ 按住拖动」可拖窗，右端 ✕ 关闭 ──
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0x33000000)
        }
        var downX = 0f; var downY = 0f; var baseX = 0; var baseY = 0
        val dragLabel = TextView(this).apply {
            text = "☰ 按住拖动 · 本局介绍"
            textSize = 13f; setTextColor(fg); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(mdp(10), mdp(8), mdp(4), mdp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        val lp = win.layoutParams as FrameLayout.LayoutParams
                        baseX = lp.leftMargin; baseY = lp.topMargin
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val parent = win.parent as? View ?: return@setOnTouchListener true
                        val maxX = (parent.width - win.width).coerceAtLeast(0)
                        val maxY = (parent.height - win.height).coerceAtLeast(dpSafe() + mdp(4))
                        val lp = win.layoutParams as FrameLayout.LayoutParams
                        lp.leftMargin = (baseX + (e.rawX - downX).toInt()).coerceIn(0, maxX)
                        lp.topMargin = (baseY + (e.rawY - downY).toInt()).coerceIn(dpSafe() + mdp(4), maxY)
                        win.layoutParams = lp
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        titleRow.addView(dragLabel)
        titleRow.addView(TextView(this).apply {
            text = "✕"; textSize = 16f; setTextColor(fg); gravity = android.view.Gravity.CENTER
            setPadding(mdp(10), mdp(6), mdp(10), mdp(6))
            setOnClickListener { hideFloatWindow() }
        })
        win.addView(titleRow)
        // ── 内容区：先自适应高，显示时若超 45% 屏再封顶成滚动区（见 showFloatWindow）──
        //   ★ 绝不能用 weight 撑高：父容器自适应高时 weight 子件会被量成 0，整窗隐形 → 点击"说明"看着没反应。
        floatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isVerticalScrollBarEnabled = true
            scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        floatText = TextView(this).apply {
            textSize = 14f; setLineSpacing(3f, 1.12f); setTextColor(fg)
            setPadding(mdp(12), mdp(10), mdp(12), mdp(12))
        }
        floatScroll.addView(floatText)
        win.addView(floatScroll)
        return win
    }

    private fun showFloatWindow() {
        if (!::floatWin.isInitialized) return
        floatShown = true
        floatWin.visibility = android.view.View.VISIBLE
        // 两趟测量：先回到全自适应量出真实高度；超 45% 屏再封顶并把滚动区改成填满剩余。
        floatWin.post {
            floatScroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val lp = floatWin.layoutParams as FrameLayout.LayoutParams
            lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
            floatWin.layoutParams = lp
            floatWin.post {
                val maxH = resources.displayMetrics.heightPixels * 45 / 100
                if (floatWin.height > maxH) {
                    val titleH = (floatScroll.top).coerceAtLeast(mdp(30))   // 滚动区在窗内的 y = 标题条高
                    floatScroll.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (maxH - titleH).coerceAtLeast(mdp(40)))
                    val lp2 = floatWin.layoutParams as FrameLayout.LayoutParams
                    lp2.height = maxH
                    floatWin.layoutParams = lp2
                }
            }
        }
    }

    private fun hideFloatWindow() {
        if (!::floatWin.isInitialized) return
        floatShown = false
        floatWin.visibility = android.view.View.GONE
        floatClosedByUser = true
    }

    /** 组装悬浮窗正文（说明/本局介绍/当前手旁白/总评），内容指纹变了才自动亮窗。 */
    private fun refreshFloatContent() {
        if (!::floatText.isInitialized) return
        val sb = StringBuilder()
        sb.append("【背谱说明】\n只能走谱上真有着法；谱外点不动。单方谱对侧自动应，变招处弹气泡提示第几号变化。")
        val intro = sessionGame?.introComment()?.takeIf { it.isNotBlank() }
            ?: sessionTitle.takeIf { it.isNotBlank() }
        if (intro != null) sb.append("\n\n【本局】\n").append(intro)
        val ci = steps.size - 1
        val mv = if (ci >= 0) sessionGame?.moveComments()?.get(ci)?.takeIf { it.isNotBlank() } else null
        if (mv != null) sb.append("\n\n【第 ${ci + 1} 手旁白】\n").append(mv)
        val total = sessionGame?.cblGame?.movesUci?.size ?: -1
        if (total > 0 && steps.size >= total) {
            sessionGame?.endComment()?.takeIf { it.isNotBlank() }?.let { sb.append("\n\n【总评】\n").append(it) }
        }
        floatText.text = sb.toString()
        // ★ 内容指纹变了才自动亮窗；用户手动关过后，只在出现「新旁白」时再亮
        val key = "${sessionIndex}:${steps.size}:${mv ?: ""}:${intro ?: ""}"
        if (key != lastFloatKey) {
            lastFloatKey = key
            // 高度回到自适应，showFloatWindow 会按需重新封顶 45% 屏
            runCatching {
                val lp = floatWin.layoutParams as FrameLayout.LayoutParams
                if (lp.height != FrameLayout.LayoutParams.WRAP_CONTENT) {
                    lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
                    floatWin.layoutParams = lp
                }
            }
            if (!floatClosedByUser || mv != null) showFloatWindow()
        }
    }

    /** 顶栏高的 dp→px（沿用本类 mdp 取整倍率） */
    private fun dpSafe(): Int =
        (BoardVisualPrefs.TOP_BAR_DP * resources.displayMetrics.density).toInt()

    /** 底排右上角小"返回"（并入菜单行最右，与选谱并肩；不再单独沉底散开） */
    private fun buildBackBtn(): Button = Button(this).apply {
        text = "返回"
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#E8D0A0"))
        setBackgroundColor(android.graphics.Color.parseColor("#5A4322"))
        gravity = android.view.Gravity.CENTER
        tighten(this, 11)
        // ★ 直接等同于「系统导航键·小退」：走 onBackPressed 分发链，行为与按返回键完全一致
        //   （若上层弹窗/菜单正开着，先关它们；否则退出本页），响应更快、行为统一。
        setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    // ═══════════════ 会话导航（上一则/下一则 & 进度信息）═══
    private fun openCatGame(catName: String, book: QipuBook?, index: Int, games: List<QipuGame>) {
        val g = games.getOrNull(index) ?: return
        sessionCat = catName; sessionBook = book; sessionGames = games; sessionIndex = index
        sessionTitle = g.title().ifBlank { "第 ${index + 1} 则" }
        sessionGame = g                    // ★ 记录当前局 → 悬浮窗可精准调出 开局旁白/每手旁白/总评
        floatClosedByUser = false          // ★ 换了一局 → 新介绍自动再亮出来
        val cbl = g.cblGame
        val startFen = if (!cbl.fenBoard.isNullOrBlank())
            "${cbl.fenBoard} ${if (cbl.moversBlack) "b" else "w"} - - 0 1"
        else STANDARD_FEN
        loadLineSource(sessionTitle, startFen, cbl.movesUci, cbl.variationsUci)
        bookMem.active = true
        if (book != null) {
            GameJournal.touch(this,
                GameJournal.GameRef(catName, book.name, book.file, index, sessionTitle), "棋谱库")
        }
        refreshProgress(); refreshNav(); refreshAll()
        maybeAutoReply(400)   // ★ 背黑方翻到新一则时，红方（谱方）立即自动开走
    }

    private fun prevGame() {
        if (sessionGames.isNotEmpty() && sessionIndex > 0) openCatGame(sessionCat, sessionBook, sessionIndex - 1, sessionGames)
    }
    private fun nextGame() {
        if (sessionGames.isNotEmpty() && sessionIndex in 0 until sessionGames.size - 1) openCatGame(sessionCat, sessionBook, sessionIndex + 1, sessionGames)
    }

    /** 菜单下一排：背边 + 目录/第几局/进度（本局长篇题名已移入「透明悬浮文字窗」，不再占这行） */
    private fun refreshProgress() {
        val mode = when (bookMem.mode) {
            BookMemorizer.Mode.RED -> "背红方·黑自动"
            BookMemorizer.Mode.BLACK -> "背黑方·红自动"
            else -> "背双方"
        }
        progressText.text = "◆ $mode ｜ " + if (sessionGames.isNotEmpty() && sessionIndex in sessionGames.indices)
            "《${sessionCat}·${sessionBook?.name ?: "当前书"}》 第 ${sessionIndex + 1}/${sessionGames.size} 则"
        else "当前棋谱（点选谱载入整本，可逐则翻）"
    }

    /** 导航排（上一则/下一则）可用性：第一则不能回、最后一则不能进，禁用置灰 */
    private fun refreshNav() {
        // ★ 底部导航键已移除：仅当按钮仍初始化（兼容）时更新，平时空操作
        if (!::btnPrev.isInitialized || !::btnNext.isInitialized) return
        val has = sessionGames.isNotEmpty() && sessionIndex in sessionGames.indices
        btnPrev.isEnabled = has && sessionIndex > 0
        btnNext.isEnabled = has && sessionIndex < sessionGames.size - 1
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else 0.32f
        btnNext.alpha = if (btnNext.isEnabled) 1f else 0.32f
    }

    // ═══════════════ 棋盘尺寸（完全跟随主界面）═══════════════

    /** 棋盘基准高（dp）：与主界面同源（≈1.16×屏宽，略大于盘面所需 ≈1.13×屏宽），
     *  最终容器高度 = 基准高 × 主界面棋盘倍数（BoardVisualPrefs.boardHeightPx），完全跟随主界面。 */
    private fun baseBoardHeightDp(): Int =
        ((resources.displayMetrics.widthPixels / resources.displayMetrics.density) * 1.16f).toInt().coerceAtLeast(400)

    private fun rememberValue(key: String, value: Any) {
        val e = prefs.edit()
        when (value) {
            is Float -> e.putFloat(key, value)
            is Int -> e.putInt(key, value)
            is Boolean -> e.putBoolean(key, value)
            else -> {}
        }
        e.apply()
    }

    /** 收缩条按钮（并排窄条，weight 平分） */
    private fun stripBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f
        setTextColor(0xFFF0E8D0.toInt())
        setBackgroundColor(0xFF4A3826.toInt())
        gravity = android.view.Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, mdp(30), 1f)
    }

    private fun smallBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f
        setTextColor(0xFFF0E8D0.toInt())
        setBackgroundColor(0xFF5A4322.toInt())
        gravity = android.view.Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, mdp(32))
    }

    /** 挤到只剩 1/6 屏宽也能放下字：内边距压到最小 + 单行 + 字号自适应收缩。 */
    private fun tighten(btn: Button, maxSp: Int) {
        btn.setPadding(mdp(1), mdp(1), mdp(1), mdp(1))
        btn.maxLines = 1
        try {
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                btn, 8, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        } catch (_: Exception) {}
    }

    /** 单行菜单里的小按钮（等宽 1/6 由调用方权重给定） */
    private fun menuBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 11f
        setTextColor(0xFFF0E8D0.toInt())
        setBackgroundColor(0xFF6A4F2A.toInt())
        gravity = android.view.Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, mdp(30), 1f)
        tighten(this, 11)
    }

    /** 底部上一则/下一则导航按钮 */
    private fun navBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f
        setTextColor(0xFFF0E8D0.toInt())
        setBackgroundColor(0xFF6A4F2A.toInt())
        setTypeface(null, android.graphics.Typeface.BOLD)
        gravity = android.view.Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, mdp(40), 1f)
        tighten(this, 12)
    }

    private fun chip(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 11f
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(0xFF3A2C1E.toInt())
        setPadding(mdp(1), mdp(1), mdp(1), mdp(1))  // 顶部"背红/背黑/背双方"三键更紧凑
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, mdp(30), 1f)
        tighten(this, 11)
    }

    /** 背红/背黑/背双方 选中色 = 主界面菜单底色（金黄），未选中 = 灰褐；字色随之明暗，选中一眼可辨。 */
    private fun refreshChips() {
        val sel = com.pikafish.chess.ui.GlobalBackdrop.menuBackgroundColor(this)   // ★ 金黄（跟随主界面底色）
        val nrm = 0xFF6F6356.toInt()                                               // 灰褐（未选中）
        val selTxt = com.pikafish.chess.ui.GlobalBackdrop.foreground(this, sel)    // 金黄底上深字，看得清
        val nrmTxt = 0xFFF0E8D0.toInt()
        fun apply(b: Button, on: Boolean) {
            b.setBackgroundColor(if (on) sel else nrm)
            b.setTextColor(if (on) selTxt else nrmTxt)
        }
        apply(chipRed, bookMem.mode == BookMemorizer.Mode.RED)
        apply(chipBlack, bookMem.mode == BookMemorizer.Mode.BLACK)
        apply(chipBoth, bookMem.mode == BookMemorizer.Mode.BOTH)
    }

    // ═══════════════ 谱源（套娃宝库）═══
    private fun loadLineSource(name: String, startFen: String, moves: List<String>, variations: List<List<String>> = emptyList()) {
        // ★ 主线+全部支线一起入谱：变招处多着可选、支线上的后续着法也全在谱，
        //   背进支线照样能一路背到底（不再"明明谱上有这步却说没谱"）。
        bookMem.loadLines(name, startFen,
            (if (moves.isEmpty()) emptyList() else listOf(moves)) + variations.filter { it.isNotEmpty() })
        completionShown = false                       // ★ 换了一则/一本谱，重新开启“完成”提示资格
        currentStartFen = startFen.ifBlank { STANDARD_FEN }
        try { board.loadFromFen(startFen.ifBlank { STANDARD_FEN }) } catch (e: Exception) {
            board.loadFromFen(STANDARD_FEN)
        }
        steps.clear()
        chessBoard.setBoard(board)
    }

    private fun openBookVault() {
        try {
            QipuBrowser(this,
                { catName, book, index, games ->
                    openCatGame(catName, book, index, games)
                },
                null
            ).show()
        } catch (e: Exception) { toast("选谱失败：${e.message}") }
    }

    // ═══════════════ 背谱核心 ═══════════════
    private fun handleUserMove(move: Move) {
        val mustColor = board.currentPlayer
        val moverColor = try { board.getPiece(move.from)?.color } catch (e: Exception) { null }
        if (moverColor != mustColor) { toast("现在轮到【${cname(mustColor)}】走"); return }

        val redTurn = mustColor == PieceColor.RED
        if (!bookMem.isUserMove(redTurn)) { toast("这一步由谱面自动应，请等它自动走完"); return }

        val fen = board.toFen()
        val uci = move.toUci()
        if (!bookMem.validate(fen, uci)) {
            val allowed = bookMem.allowedFor(fen).size
            toast(if (allowed == 0) "此局面谱上无着法（已出谱），可‘选谱’换一本或换边"
                  else "这步不在谱上，请走谱上给出的着法")
            return
        }

        // 走棋
        val before = Board().also { try { it.loadFromFen(fen) } catch (_: Exception) {} }
        try { board.makeMoveUnchecked(move) } catch (e: Exception) { toast("落子失败：${e.message}"); return }
        playMoveSound(before, move)   // ★ 落子即响（走子/吃子/将军/绝杀），与主界面同源
        val branches = (bookMem.allowedFor(fen).size)
        val notation = runCatching { CommentaryEngine.uciToChineseNotation(uci, before) }.getOrDefault(uci)
        steps.add(Step(uci, fen, notation, branches))
        chessBoard.setBoard(board)
        refreshAll()

        // ★ 本手若已把整条谱（含分支最后一节）走完 → 立刻提示“此变化背谱已完成”，省得用户没个底瞎点。
        maybeShowCompletionDialog()

        // 轮到谱方走 → 自动应（多分支时弹气泡 + 遍历轮换）
        maybeAutoReply(280)
    }

    /**
     * ★ 统一的自动应子入口：只要当前轮到"谱方自动应"的那一侧（背红→黑自动、
     * 背黑→红自动），就延时安排一次自动走子；轮用户走则原地不动。
     * 修"选了背黑方后红方干等不走"的卡死 —— 换边/翻书/重背/进页都会来这里报到。
     */
    private fun maybeAutoReply(delayMs: Long = 300) {
        if (isFinishing || !bookMem.active) return
        if (!bookMem.isAutoSide(board.currentPlayer == PieceColor.RED)) return
        ui.postDelayed({ runAutoReply() }, delayMs)
    }

    /** ★ 翻转棋盘（黑方视角 ↔ 红方视角来回切）。 */
    private fun toggleBoardFlip() {
        chessBoard.setFlipped(!chessBoard.isFlipped())
    }

    /**
     * ★ 换边即换主角视角：背黑 → 黑方在下、红方在上（自动翻转）；背红 → 回红方在下；
     *   背双方 → 维持现状（手动「翻转」按钮随时可覆盖）。
     *   棋盘局面与打谱记录原样保留（只换身份不洗盘）；若换边后正轮到谱方自动应，
     *   立即自动开走 —— 修复"点背黑后红方不主动走棋"。
     */
    private fun onModePicked(blackView: Boolean?) {
        completionShown = false
        if (blackView != null && chessBoard.isFlipped() != blackView) chessBoard.setFlipped(blackView)
        refreshChips(); refreshProgress(); refreshAll()
        maybeAutoReply()
    }

    private fun runAutoReply() {
        if (isFinishing) return
        if (!bookMem.isAutoSide(board.currentPlayer == PieceColor.RED)) return
        val fen = board.toFen()
        // 先弹气泡（含分支序号），再选着应子 —— branchInfo 现为纯预览，气泡报的号
        // 与 autoReply 实际走的那一号永远一致（修复旧版双重递增报号错乱）。
        bookMem.branchInfo(fen)?.let { showBubble("『${it.third.ifBlank { "谱" }}』共 ${it.first} 变招，本回合走第 ${it.second + 1} 号") }
        val uci = bookMem.autoReply(fen)
        if (uci == null) { maybeShowCompletionDialog(); return }   // ★ 谱上无后继=已背完，交给完成提示
        val before = Board().also { try { it.loadFromFen(fen) } catch (_: Exception) {} }
        val m = Move.fromUci(uci, before) ?: return
        try { board.makeMoveUnchecked(m) } catch (e: Exception) { return }
        playMoveSound(before, m)   // ★ 谱方自动应子同样出声（与用户手点一致）
        val branches = bookMem.allowedFor(fen).size
        val notation = runCatching { CommentaryEngine.uciToChineseNotation(uci, before) }.getOrDefault(uci)
        steps.add(Step(uci, fen, notation, branches))
        chessBoard.setBoard(board)
        refreshAll()
        // ★ 自动应子把谱走完也提示"已完成"；万一还轮到谱方（异常局面）就再链一节。
        maybeShowCompletionDialog()
        maybeAutoReply(280)
    }

    // ═══════════════ 背谱完成提示 + 「说明」弹窗 ═══════════════

    /** ★ 本手已把这条谱（含分支最后一节）走完、谱上无后继时，只提示一次“此变化背谱已完成”。 */
    private fun maybeShowCompletionDialog() {
        if (completionShown || isFinishing) return
        if (steps.isEmpty()) return
        val allowed = runCatching { bookMem.allowedFor(board.toFen()) }.getOrDefault(listOf())
        if (allowed.isNotEmpty()) return
        completionShown = true
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("此变化背谱已完成")
                .setMessage("本则谱（含该分支的最后一节）已经背完，谱上无后继着法。\n\n点「下一则」背下一谱，或换边/洗谱重新背这一谱。")
                .setPositiveButton("知道了", null)
                .setNegativeButton("重新背", { _, _ ->
                    steps.clear()
                    completionShown = false
                    try {
                        board.loadFromFen(currentStartFen.ifBlank { STANDARD_FEN })
                    } catch (e: Exception) { board.loadFromFen(STANDARD_FEN) }
                    chessBoard.setBoard(board)
                    refreshAll()
                    maybeAutoReply(400)   // ★ 重背后若轮到谱方（如背黑·红先），自动先走
                })
                .setCancelable(true)
                .show()
        } catch (_: Exception) {}
    }

    /** ★ 「说明」：按需弹窗，展示当前背的是哪个谱段、谱源，以及已经背到的开局/变例名。是什么就是什么。 */
    private fun showExplainDialog() {
        val lineName = runCatching { bookMem.currentLineName() }.getOrElse { "当前局" }
        val src = runCatching { bookMem.sourceDesc() }.getOrDefault("")
        val opening = currentOpeningDesc()
        val msg = buildString {
            append("当前谱段：《").append(lineName).append("》")
            if (src.isNotBlank()) append("\n谱源：").append(src)
            append("\n\n").append(opening)
        }
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("背谱说明")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .setCancelable(true)
                .show()
        } catch (_: Exception) {}
    }

    /** ★ 由已背的步序列交给 ECCO 编号体系，得到“背到了哪个开局/变例”。 */
    private fun currentOpeningDesc(): String {
        val mvs = ArrayList<Move>()
        for (s in steps) {
            val b = Board()
            runCatching { b.loadFromFen(s.fenBefore) }
            val m = Move.fromUci(s.uci, b) ?: continue
            mvs.add(m)
        }
        if (mvs.isEmpty()) return "尚未走棋：从起始局面开局，走几步后这里会显示背到了哪个开局。"
        val nr = com.pikafish.chess.qipu.EccoNamer.ofMoves(mvs)
        return when {
            nr == null -> "已走 ${mvs.size} 手，尚未进入 ECCO 可识别的具体开局变例（已入中局/多分支）。"
            nr.variation.isBlank() -> "当前开局：${nr.code} ${nr.opening}"
            else -> "当前开局：${nr.code} ${nr.opening}（${nr.variation}）"
        }
    }

    private fun refreshAll() {
        refreshMoves()
        refreshStatus()
    }

    private fun refreshMoves() {
        // ★ 两个回合一行（节省纵向空间）：`1.相三进五 马2进3  2.兵三进一 卒3进1`
        //   序号、红着、黑着紧凑排；第 1、3、5…回合在行首，第 2、4、6…回合接在同一行。
        val rowSb = StringBuilder()     // 一行内两个回合
        val sb = StringBuilder()
        var i = 0
        var inRow = 0                   // 当前行已排的第几个回合（0/1）
        while (i < steps.size) {
            val n = i / 2 + 1
            val red = steps.getOrNull(i)
            val blk = steps.getOrNull(i + 1)
            // 回合之间、上下之间加个显眼分隔（段宽固定，两个回合正好占满一行宽）
            if (inRow == 1) rowSb.append("   ")            // 同一行里两个回合之间空开
            rowSb.append(n).append(".")
            if (red != null) rowSb.append(mark(red))
            if (blk != null) rowSb.append(" ").append(mark(blk))
            inRow++
            i += 2
            // 第 2、4、6…回合后换行，两条回合恰好排满一行
            if (inRow == 2) {
                sb.append(rowSb).append('\n')
                rowSb.setLength(0)
                inRow = 0
            }
        }
        if (rowSb.isNotEmpty()) sb.append(rowSb)          // 最后不足一行的残回合收尾
        movesText.text = if (sb.isEmpty()) "（尚未走棋，点棋盘红/黑棋按谱走）" else sb.toString().trim('\n')
    }

    /** 变招数标记：branches>1 则在记谱后标“ⓃN” */
    private fun mark(s: Step): String = if (s.branches > 1) "${s.notation}[${s.branches}变]" else s.notation

    private fun refreshStatus() {
        val src = bookMem.sourceDesc().ifBlank { "未选谱" }
        val fenCand = bookMem.allowedFor(board.toFen()).size
        val mTxt = when (bookMem.mode) {
            BookMemorizer.Mode.RED -> "背红方谱 · 黑方自动"
            BookMemorizer.Mode.BLACK -> "背黑方谱 · 红方自动"
            BookMemorizer.Mode.BOTH -> "背双方谱"
        }
        statusText.text = "◆ $mTxt\n◆ 谱源：$src（当前局面候选 ${fenCand} 着）"
        refreshFloatContent()   // ★ 悬浮窗正文随局面刷新：当前手若有旁白，精准亮出来
    }

    private fun cname(c: PieceColor) = if (c == PieceColor.RED) "红方" else "黑方"

    // ═══════════════ 变招气泡 ═══════════════
    private var bubbleDismiss: Runnable? = null
    private fun showBubble(msg: String) {
        bubbleDismiss?.let { bubble.removeCallbacks(it) }
        bubble.text = msg; bubble.visibility = android.view.View.VISIBLE
        bubble.animate().alpha(1f).setDuration(150).start()
        bubbleDismiss = Runnable { fadeBubble() }
        bubble.postDelayed(bubbleDismiss!!, 2400)
    }
    private fun fadeBubble() {
        bubble.animate().alpha(0f).setDuration(200).withEndAction {
            if (bubble.alpha <= 0.01f) bubble.visibility = android.view.View.GONE
        }.start()
    }
    private fun hideBubble() {
        bubbleDismiss?.let { bubble.removeCallbacks(it) }
        bubble.animate().cancel(); bubble.alpha = 0f; bubble.visibility = android.view.View.GONE
    }

    /**
     * 落子后播放走子/吃子/将军/绝杀音效（与主界面同源）。
     * @param before 走子前局面快照（用于判定是否吃子）
     * @param move   刚落下的这一手
     */
    private fun playMoveSound(before: Board, move: Move) {
        try {
            val sm = soundManager ?: return
            val cap = try { before.getPiece(move.to) } catch (_: Exception) { null }
            val mover = try { before.getPiece(move.from)?.color } catch (_: Exception) { null }
            if (cap != null && cap.color != mover) sm.play("eat") else sm.play("move")
            if (board.isInCheck(board.currentPlayer)) sm.play("check")
            if (board.isCheckmate(board.currentPlayer)) sm.play("end")
        } catch (_: Exception) {}
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun mdp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { soundManager?.release() } catch (_: Exception) {}
        soundManager = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // ★ 棋盘视觉调节已全部废除：视觉只跟随主界面（BoardVisualPrefs），这里不再保存任何
        //   棋盘/棋子/棋线/皮肤/背景设置。仅保留"棋谱面板收起状态"由 rememberValue 就地记忆。
    }
}