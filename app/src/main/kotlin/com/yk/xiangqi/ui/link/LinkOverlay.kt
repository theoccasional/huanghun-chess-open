package com.yk.xiangqi.ui.link

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yk.xiangqi.GameRuntime
import com.yk.xiangqi.Settings
import com.yk.xiangqi.bridge.BoardProjection
import com.yk.xiangqi.core.GameState
import com.yk.xiangqi.core.Move
import com.yk.xiangqi.core.Side
import com.yk.xiangqi.link.BoardGeometry
import com.yk.xiangqi.ui.BoardDecor
import kotlin.math.max
import kotlin.math.min

/**
 * 连线功能的系统悬浮 UI：棋盘框选与运行控制。
 *
 * 它只负责绘制和触摸，具体的录屏、识别、同步由服务通过回调处理。
 */
class LinkOverlay(private val context: Context, private val listener: Listener) {
    private enum class Drag {
        NONE, CREATE, MOVE, LEFT, TOP, RIGHT, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    }

    private companion object {
        const val MIN_SIZE = 48f
        const val HANDLE_RADIUS = 40f
        const val CONTROL_HEIGHT = 110f
        const val RUNNING_PANEL_WIDTH_DP = 400
        val MINI_BOARD_SIZES_DP = intArrayOf(100, 120, 140, 180)
        const val BUTTON_NONE = 0
        const val BUTTON_CANCEL = 1
        const val BUTTON_CONFIRM = 2
        const val BUTTON_SIDE = 3
        /** 选中态统一用同一抹绿，红黑两侧都看得清。 */
        val ACTIVE_COLOR = 0xff66bb6a.toInt()
        /** 菜单最多三行，再多也不让面板长高。 */
        const val MAX_MENU_ROWS = 3
        /** 连线信息弹窗页数：第 1 页原有信息，第 2 页算棋归属 / 辎重包 / 开局库。 */
        const val INFO_PAGE_COUNT = 2
    }

    interface Listener {
        fun onConfigured(geometry: BoardGeometry, sideToMove: Side)
        fun onReconfigured(geometry: BoardGeometry, sideToMove: Side)
        fun onSelectionCancelled()

        /** 引擎执子方；null 表示不让引擎接管任何一方。 */
        fun onAiSideChanged(side: Side?)

        fun onNewGameSideChanged(sideToMove: Side)

        /** 同步当前局面：按给定先手重新识别整盘。 */
        fun onSynchronize(sideToMove: Side)

        /**
         * 快捷校准：不回框选，直接复用已记住的框选区域，重新识别当前屏幕并重建棋盘。
         * 9×10 框选是它的前提，所以还没框选过时什么也不做。
         */
        fun onQuickCalibrate(sideToMove: Side)

        /**
         * 免框选：无需手动框选，直接全屏扫描当前画面自动定位棋盘、认出整盘，
         * 成功即直接开始连线。与「框选」是两条并行的上手方式，认不出时由服务提示重试。
         */
        fun onAutoCalibrate()

        /**
         * 初始化：不停止连线，只把上一盘遗留的缓存、惯性与开局判定统统清干净，
         * 让随后不管先「同步」还是先「校准」，都按大棋盘上的真实局面重新建盘、重新判定开局。
         */
        fun onInitialize()

        fun onStop()

        /** 已保存的连线方案，按最近使用排序。 */
        fun schemes(): List<Settings.Scheme>

        /** 保存当前框选为可复用方案；名称为 null 时新建，否则覆盖同 id 方案。 */
        fun onSchemeSaved(id: String?, name: String, app: String, geometry: BoardGeometry, sideToMove: Side)

        /** 套用历史方案：直接按保存的几何与先手开始连线，不再重新拉线。 */
        fun onSchemeApplied(scheme: Settings.Scheme)

        fun onSchemeDeleted(id: String)

        /** 已保存的小棋盘尺寸（dp），用于恢复上次选择；从未设置过时返回最小一档。 */
        fun savedMiniBoardSize(): Int

        /** 用户切换小棋盘大小后落盘，下次连线沿用。 */
        fun onMiniBoardSizeChanged(sizeDp: Int)

        /** 可选引擎档案：应用当前仍持有、加载得起来的那些，直接给悬浮窗列。 */
        fun engines(): List<Settings.Engine>

        /** 当前正在使用的引擎档案 id。 */
        fun activeEngineId(): String

        /** 用了哪个引擎档案。 */
        fun onEngineSelected(id: String)

        /** 悬浮窗当前的搜索上限：类型 DEPTH / MOVETIME，空串表示不限。 */
        fun searchLimitKind(): String

        /** 搜索上限数值：DEPTH 为层数，MOVETIME 为毫秒。 */
        fun searchLimitValue(): Long

        /** 改搜索上限；类型与数值同时落盘，层数与步时天然互斥。 */
        fun onSearchLimitChanged(kind: String, value: Long)

        /**
         * 连线信息弹窗内容：当前正在连线使用哪个引擎、什么版本、哪些细节。
         * 由服务端根据运行期引擎身份与连线方案组装，悬浮窗只负责展示。
         */
        fun linkInfoLines(): List<String>

        /**
         * 连线信息弹窗「第二页」内容：算棋到底交给谁——
         * 本地引擎（本机自算）还是异地引擎（跨设备、由辎重包端代算），以及辎重包来源、
         * 开局库与权重来自哪里。让用户一眼看清「是不是真的配上了、是不是真的在异地算」。
         */
        fun linkEngineLines(): List<String>
    }

    private val windows = context.getSystemService(WindowManager::class.java)
    private var selection: View? = null
    /** 菜单组：纯菜单栏一坨，拖它只动它自己。 */
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    /** 棋盘组：小棋盘与思考细节同属一伙，与菜单栏分开成两块独立悬浮窗。 */
    private var boardPanel: LinearLayout? = null
    private var boardParams: WindowManager.LayoutParams? = null
    private var miniBoard: MiniBoardView? = null
    private var miniState: GameState? = null
    private var miniArrows: List<Move> = emptyList()
    private var thinkingView: TextView? = null
    private var thinkingScroll: ScrollView? = null
    /** 思考细节是否跟随最新内容；用户手动往上翻后置为 false。 */
    private var pinnedToBottom = true
    private var thinking: GameRuntime.Analysis = GameRuntime.Analysis.EMPTY
    /** ECCO 开局判定：常驻思考细节末尾两行；null 表示尚未判出。 */
    private var openingCode: String? = null
    private var openingName: String? = null
    private var openingVariation: String? = null
    /** 连线操作的一行即时状态（免框选/同步/校准/初始化等），常驻思考细节顶部，让按钮一按就有回应。 */
    private var statusMessage: String? = null
    private var miniVisible = true
    /** 小棋盘尺寸：默认最小一档，并且沿用上次选择（持久化在 Settings）。 */
    private var miniSizeDp = MINI_BOARD_SIZES_DP.first().let { default ->
        listener.savedMiniBoardSize().takeIf { it in MINI_BOARD_SIZES_DP } ?: default
    }
    /** 小棋盘是否上下翻转：只影响显示，框选与识别几何完全不动。 */
    private var flipped = false
    /** 引擎当前执子方；由服务端状态同步过来。 */
    private var engineSide: Side? = null
    private var redAiButton: TextView? = null
    private var blackAiButton: TextView? = null
    /** 层数 / 步时两个互斥上限按钮，用来把当前生效的那个点亮。 */
    private var depthButton: TextView? = null
    private var moveTimeButton: TextView? = null
    private var syncSide = Side.RED
    private var synchronizeAfterSelection = false
    /** 当前正在运行或已保存方案对应的框选几何；为 null 表示尚未框选。 */
    private var geometry: BoardGeometry? = null
    private var dialog: View? = null

    /**
     * 当前界面默认的新局先手（红先/黑先）。
     *
     * <p>供「免框选」在用户没另点「红必走/黑必走」时沿用：界面按钮上显示的是「红先」还是
     * 「黑先」，就按那一方当先手，绝不一律当红先——否则界面显示黑先时，引擎会照着错的
     * 一方思考，第一步就错。合并自另一路实现的真实修正。</p>
     */
    fun currentNewGameSide(): Side = syncSide

    /** 录屏启动后先展示轻量入口；用户切到第三方棋盘后再主动开始框选。 */
    fun showReady() {
        closeDialog()
        removeBoardPanel()
        miniVisible = false
        miniBoard = null
        miniState = null
        miniArrows = emptyList()
        thinkingView = null
        thinkingScroll = null
        redAiButton = null
        blackAiButton = null
        depthButton = null
        moveTimeButton = null
        val menu = menuPanel(
            dragHandle({ panel }, { panelParams }),
            textButton("框选", "框选棋盘：拖动对角框出棋盘（免框选认不出时用它）", Color.WHITE) { showSelection(false) },
            textButton("免框选", "免框选：全屏自动识别棋盘并开始连线，无需手动框选", 0xff66bb6a.toInt()) {
                // ★ 一步进主界面：点了免框选立刻切到「多按钮」的连线主菜单，不再卡在轻量入口干等；
                //   识别在后台跑，成与不成随后弹气泡回执（识别有效 / 免框选失败），点了就有反应。
                showControls(syncSide)
                listener.onAutoCalibrate()
            },
            textButton("方案", "连线方案", Color.WHITE) { showSchemes { showReady() } },
            textButton("信息", "连线信息", Color.WHITE) { showInfoDialog() },
            autoClickButton(),
            autoClickIntervalButton(),
            textButton("停止", "停止连线", 0xffe57373.toInt()) { listener.onStop() },
        )
        replacePanel(menu)
    }

    private fun showSelection(synchronizeAfterSelection: Boolean) {
        if (selection != null) return
        this.synchronizeAfterSelection = synchronizeAfterSelection
        selection = SelectionView(context)
        windows.addView(selection, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ))
    }

    /**
     * 连线运行期拆成两块互不绑定的悬浮窗：菜单栏一坨，「小棋盘＋思考细节」另一坨。
     *
     * 拖菜单只动菜单，拖棋盘只动棋盘和它旁边的思考细节，两边各走各的。
     * 菜单一律汉字，挤不下就提前换行，最多三行。
     */
    fun showControls(side: Side?) {
        miniVisible = true
        engineSide = side
        // 菜单组：纯菜单栏。
        val menu = menuPanel(
            dragHandle({ panel }, { panelParams }),
            aiButton(Side.RED),
            aiButton(Side.BLACK),
            textButton(if (syncSide == Side.RED) "红先" else "黑先", "新局先手", Color.WHITE) { button ->
                syncSide = if (syncSide == Side.RED) Side.BLACK else Side.RED
                button.text = if (syncSide == Side.RED) "红先" else "黑先"
                button.contentDescription = if (syncSide == Side.RED) "新局先手：红先" else "新局先手：黑先"
                listener.onNewGameSideChanged(syncSide)
            },
            textButton("同步", "同步当前局面", Color.WHITE) { listener.onSynchronize(syncSide) },
            textButton("校准", "快捷校准：用已记住的框选重新识别当前棋盘", Color.WHITE) {
                listener.onQuickCalibrate(syncSide)
            },
            flipButton(),
            textButton(if (miniVisible) "收起" else "展开", "收起小棋盘", 0xff81d4fa.toInt()) { button ->
                toggleMiniBoard(button)
            },
            textButton("大小", "切换小棋盘大小，当前 ${miniSizeDp}dp", Color.WHITE) { button ->
                cycleMiniBoardSize(button)
            },
            textButton("框选", "重新框选棋盘（免框选认不出时用它）", Color.WHITE) { showSelection(true) },
            textButton("免框选", "免框选：全屏自动识别棋盘并重新开始连线", Color.WHITE) {
                listener.onAutoCalibrate()
            },
            textButton("方案", "连线方案：列出已保存方案，点即套用；也可存下当前框选", Color.WHITE) {
                showSchemes { showControls(engineSide) }
            },
            textButton("信息", "连线信息", Color.WHITE) { showInfoDialog() },
            engineButton(),
            limitButton("DEPTH"),
            limitButton("MOVETIME"),
            textButton("初始化", "清空上一盘的缓存与开局判定，下一次同步/校准按大棋盘真实局面重建",
                0xffffb74d.toInt()) { listener.onInitialize() },
            autoClickButton(),
            autoClickIntervalButton(),
            textButton("停止", "停止连线", 0xffe57373.toInt()) { listener.onStop() },
        )
        replacePanel(menu)

        // 棋盘组：小棋盘左侧定宽，右侧剩余空间放思考细节，整组一起搬。
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = panelBackground()
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        miniBoard = MiniBoardView(context).apply { setFlipped(flipped) }
        val boardSide = dp(miniSizeDp)
        val boardHeight = dp(miniSizeDp * 10 / 9)
        content.addView(miniBoard, LinearLayout.LayoutParams(boardSide, boardHeight))

        // 思考细节固定与小棋盘等高，内容再多也在内部滚屏，绝不撑高整组去遮挡大棋盘。
        thinkingView = TextView(context).apply {
            setTextColor(0xffe0e0e0.toInt())
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            text = thinkingText(thinking)
        }
        thinkingScroll = ScrollView(context).apply {
            setPadding(dp(10), dp(2), dp(2), dp(2))
            isFillViewport = true
            addView(thinkingView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = getChildAt(0)
                // 用户自己往上翻了就停住，不再抢着往下拉。
                pinnedToBottom = child == null || scrollY >= child.height - height - dp(4)
            }
        }
        pinnedToBottom = true
        content.addView(thinkingScroll, LinearLayout.LayoutParams(0, boardHeight, 1f))
        root.addView(content)
        attachDrag(root, { boardPanel }, { boardParams })
        replaceBoardPanel(root, min(dp(RUNNING_PANEL_WIDTH_DP), context.resources.displayMetrics.widthPixels))
        miniState?.let { miniBoard?.update(it, miniArrows) }
    }

    /**
     * 观棋存档判出的当前开局名与变例名；由服务在每次走子/建盘时推送。
     *
     * 它常驻思考细节末尾两行，观棋时无需任何点击即可直接看到「这是什么开局」；
     * 校准或轻微刷新后仍由独立存档判定回填，不会丢失。
     */
    fun showOpening(code: String?, opening: String?, variation: String?) {
        openingCode = code
        openingName = opening
        openingVariation = variation
        thinkingView?.text = thinkingText(thinking)
    }

    /**
     * 一行即时状态：免框选/同步/校准/初始化等操作一发起就回显「正在…」「已完成」「失败原因」，
     * 不再让用户点了半天看不出端倪。传 null 清空。
     */
    fun showStatus(text: String?) {
        statusMessage = text
        thinkingView?.text = thinkingText(thinking)
    }

    /** 引擎每次给出新的思考信息时刷新右侧面板；高度不变，只滚动内部内容。 */
    fun showThinking(value: GameRuntime.Analysis) {
        thinking = value
        thinkingView?.text = thinkingText(thinking)
        if (pinnedToBottom)
            thinkingScroll?.post { thinkingScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun thinkingText(value: GameRuntime.Analysis): String {
        val out = StringBuilder("思考细节")
        // 状态行放在最前面：恒定高度、始终可见，按钮一按下就能看到「正在…/结果」。
        statusMessage?.takeIf { it.isNotBlank() }?.let { out.append("\n状态 ").append(it) }
        if (value.isEmpty()) {
            out.append("\n等待引擎思考…")
        } else {
            value.sideToMove?.let { out.append("\n轮到 ").append(if (it == Side.RED) "红方" else "黑方") }
            if (value.score != null || value.verdict != null) {
                out.append("\n分值 ").append(value.score ?: "—")
                value.verdict?.let { out.append("（").append(it).append("）") }
            }
            if (value.depth != null || value.time != null)
                out.append("\n深度 ").append(value.depth ?: "—").append(" · 用时 ").append(value.time ?: "—")
            if (value.nps != null || value.nodes != null)
                out.append("\nNPS ").append(value.nps ?: "—").append(" · 节点 ").append(value.nodes ?: "—")
            if (value.pv.isNotEmpty()) out.append("\n主变 ").append(value.pv.joinToString(" "))
        }
        // ECCO 开局常驻两行：放在内容最末尾——思考细节窗口恒定高度、自动滚到底，
        // 因此这两行任何时候都直接可见，无需点击；窗口尺寸不变，内容仅多滚两行。
        // 存档独立于单帧识别，校准/轻微刷新后开局名依旧常驻。
        out.append("\n开局 ")
        openingCode?.takeIf { it.isNotBlank() }?.let { out.append(it).append(' ') }
        out.append(openingName?.takeIf { it.isNotBlank() } ?: "—")
        out.append("\n变例 ").append(openingVariation?.takeIf { it.isNotBlank() } ?: "—")
        return out.toString()
    }

    /**
     * 请求悬浮窗做一次几乎看不见的视觉微变，逼合成器重新产出一帧。
     *
     * <p>只在需要取帧而静态画面又不产帧时调用（免框选/校准/静止期自愈）。菜单栏与
     * 小棋盘两块窗口都抖一下，避免只抖一块时合成器没把变化当真。悬浮窗在主线程，
     * 故服务端 post 过来调用；服务端不直接触碰 View。</p>
     */
    fun nudgeForFreshFrame() {
        jitterForFreshFrame(panel, panelParams)
        jitterForFreshFrame(boardPanel, boardParams)
    }

    /**
     * 对一块悬浮窗做一次几乎看不见的视觉微变：alpha 轻变 + 1px 位移。
     *
     * <p>只改 alpha 有时会被合成器当成“没动”，配上 1px 位移把窗口新旧区域都标脏，
     * 就能确定逼出新的合成帧；1px 位移与几档 alpha 肉眼都不可辨，不影响观感。</p>
     */
    private fun jitterForFreshFrame(view: View?, params: WindowManager.LayoutParams?) {
        if (view == null || params == null) return
        val originalAlpha = params.alpha
        val originalX = params.x
        params.alpha = if (originalAlpha > 0.9f) 0.93f else 1.0f
        params.x = originalX + 1
        runCatching { windows.updateViewLayout(view, params) }
        view.postDelayed({
            params.alpha = originalAlpha
            params.x = originalX
            runCatching { windows.updateViewLayout(view, params) }
        }, 60L)
    }

    private fun replacePanel(next: LinearLayout, width: Int = WindowManager.LayoutParams.WRAP_CONTENT) {
        val metrics = context.resources.displayMetrics
        val oldX = panelParams?.x ?: 16
        val x = if (width > 0) oldX.coerceIn(0, max(0, metrics.widthPixels - width)) else oldX
        val y = panelParams?.y ?: defaultControlY()
        remove(panel)
        panel = next
        panelParams = overlayParams(x, y, width)
        windows.addView(next, panelParams)
    }

    /** 棋盘组的独立悬浮窗：换内容时沿用旧位置，首次出现时贴到菜单栏上方。 */
    private fun replaceBoardPanel(next: LinearLayout, width: Int = WindowManager.LayoutParams.WRAP_CONTENT) {
        val metrics = context.resources.displayMetrics
        val first = boardParams == null
        val oldX = boardParams?.x ?: 16
        val x = if (width > 0) oldX.coerceIn(0, max(0, metrics.widthPixels - width)) else oldX
        val y = boardParams?.y ?: defaultBoardY()
        remove(boardPanel)
        boardPanel = next
        boardParams = overlayParams(x, y, width)
        windows.addView(next, boardParams)
        // 高度要等布局完成才知道，先摆个大概，落位后再往上贴，免得和菜单栏叠在一起。
        if (first) next.post { settleBoardPanel() }
    }

    private fun settleBoardPanel() {
        val target = boardPanel ?: return
        val layout = boardParams ?: return
        val menuTop = panelParams?.y ?: defaultControlY()
        layout.y = (menuTop - target.height - dp(8)).coerceAtLeast(0)
        windows.updateViewLayout(target, layout)
    }

    private fun defaultControlY(): Int {
        val metrics = context.resources.displayMetrics
        return (metrics.heightPixels - 140 * metrics.density).toInt().coerceAtLeast(0)
    }

    private fun defaultBoardY(): Int {
        val metrics = context.resources.displayMetrics
        return (defaultControlY() - dp(miniSizeDp * 10 / 9) - dp(8)).coerceAtLeast(0)
    }

    private fun removeBoardPanel() {
        remove(boardPanel)
        boardPanel = null
        boardParams = null
    }

    /** 识别或本地走子后由服务刷新小棋盘与当前主变箭头。 */
    fun showMiniBoard(state: GameState, arrows: List<Move>) {
        miniState = state
        miniArrows = arrows
        miniBoard?.update(state, arrows)
    }

    /** 框选完成后由服务调用：只有几何尚未保存过时才提示命名保存。 */
    fun offerSchemeSave() {
        val geometry = geometry ?: return
        if (matchingScheme(geometry) == null) showSchemeSaveDialog()
    }

    private fun showSchemes(back: () -> Unit) {
        closeDialog()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        root.addView(panelText("连线方案", Color.WHITE, 18f))
        val schemes = listener.schemes()
        if (schemes.isEmpty()) {
            root.addView(panelText("暂无方案：框选棋盘后按提示命名保存。", 0xffbdbdbd.toInt(), 14f))
        } else {
            // ★ 方案一多就滚屏翻：整列塞进 ScrollView，最多占约半屏高，条数再多也只在这个框里滚，
            //   不把面板撑上天；点中哪条立刻套用哪条，无需再弹保存/确认。
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            schemes.forEach { scheme ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = panelText(if (scheme.app.isEmpty()) scheme.name else "${scheme.name}（${scheme.app}）", Color.WHITE, 15f) {
                    geometry = BoardGeometry(scheme.left, scheme.top, scheme.right, scheme.bottom)
                    syncSide = scheme.sideToMove
                    listener.onSchemeApplied(scheme)
                }
                label.layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(label)
                row.addView(panelText("删除", 0xffe57373.toInt(), 14f) {
                    listener.onSchemeDeleted(scheme.id)
                    showSchemes(back)
                })
                list.addView(row)
            }
            val maxHeight = min(dp(320), context.resources.displayMetrics.heightPixels / 2)
            root.addView(ScrollView(context).apply {
                isFillViewport = false
                addView(list, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ))
            }, LinearLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, maxHeight))
        }
        // 手边已有框选时，列表末尾再留一条「保存当前框选」，切方案与存方案两不误。
        if (geometry != null)
            root.addView(panelText("保存当前框选为方案", 0xffa5d6a7.toInt(), 15f) { showSchemeSaveDialog() })
        root.addView(panelText("返回", 0xff81d4fa.toInt(), 15f) { back() })
        replacePanel(root, dp(300))
    }

    /**
     * 连线信息弹窗（分两页）：
     *  - 第一页：连线软件、引擎身份、框选、执子方等（原有内容，一字不改）。
     *  - 第二页：算棋到底交给谁 —— 本地引擎（本机自算）还是异地引擎（跨设备代算），
     *    以及辎重包来源、开局库与权重的来路，让人一眼看清「是不是真的配上了」。
     *
     * <p>两页之间用「上一页 / 下一页」翻；第二页不做复制功能，纯看信息，免得看花眼。
     * 关闭它只是移除这个信息窗，面板与连线本身照常运行。</p>
     */
    private fun showInfoDialog() {
        showInfoPage(0)
    }

    /** 信息弹窗共两页；page 0 为原有信息，page 1 为「算棋交给谁 / 辎重包 / 开局库」。 */
    private fun showInfoPage(page: Int) {
        closeDialog()
        val current = page.coerceIn(0, INFO_PAGE_COUNT - 1)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        root.addView(panelText(
            if (current == 0) "连线信息（第 1 / $INFO_PAGE_COUNT 页）"
            else "连线信息（第 ${current + 1} / $INFO_PAGE_COUNT 页）",
            Color.WHITE, 18f,
        ))
        val lines = if (current == 0) listener.linkInfoLines() else listener.linkEngineLines()
        if (lines.isEmpty()) root.addView(panelText("暂无信息", 0xffbdbdbd.toInt(), 14f))
        lines.forEach { line ->
            root.addView(TextView(context).apply {
                text = line
                setTextColor(0xffe0e0e0.toInt())
                textSize = 14f
                setPadding(dp(4), dp(6), dp(4), dp(6))
            })
        }
        // 翻页栏：首页只出「下一页」，末页只出「上一页」，两端都带「关闭」。第二页无复制。
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (current > 0)
            buttons.addView(panelText("上一页", 0xff81d4fa.toInt(), 15f) { showInfoPage(current - 1) })
        if (current < INFO_PAGE_COUNT - 1)
            buttons.addView(panelText("下一页", 0xff81d4fa.toInt(), 15f) { showInfoPage(current + 1) })
        buttons.addView(panelText("关闭", Color.WHITE, 15f) { closeDialog() })
        root.addView(buttons)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        dialog = root
        windows.addView(root, params)
    }

    /** 方案命名弹窗：名称与对应软件均由用户填写，确认后持久化保存。 */
    private fun showSchemeSaveDialog() {
        val geometry = geometry
        if (geometry == null) {
            showReady()
            return
        }
        if (dialog != null) return
        val existing = matchingScheme(geometry)
        val name = EditText(context).apply {
            hint = "方案名称"
            setSingleLine()
            setText(existing?.name ?: "方案 ${listener.schemes().size + 1}")
            setTextColor(Color.WHITE)
            setHintTextColor(0xff9e9e9e.toInt())
        }
        val app = EditText(context).apply {
            hint = "对应软件（如：天天象棋）"
            setSingleLine()
            setText(existing?.app ?: listener.schemes().firstOrNull()?.app.orEmpty())
            setTextColor(Color.WHITE)
            setHintTextColor(0xff9e9e9e.toInt())
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        root.addView(panelText("保存连线方案", Color.WHITE, 18f))
        root.addView(name)
        root.addView(app)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(panelText("跳过", Color.WHITE, 15f) { closeDialog() })
        buttons.addView(panelText("保存", 0xff81d4fa.toInt(), 15f) {
            val schemeName = name.text.toString().trim()
            if (schemeName.isEmpty()) {
                name.error = "请输入方案名称"
                return@panelText
            }
            listener.onSchemeSaved(existing?.id, schemeName, app.text.toString().trim(), geometry, syncSide)
            closeDialog()
        })
        root.addView(buttons)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        dialog = root
        windows.addView(root, params)
        name.requestFocus()
    }

    private fun closeDialog() {
        val view = dialog ?: return
        dialog = null
        windows.removeView(view)
    }

    /** 与已有方案几何几乎重合时，视为同一方案，避免重复保存。 */
    private fun matchingScheme(geometry: BoardGeometry): Settings.Scheme? = listener.schemes().firstOrNull {
        kotlin.math.abs(it.left - geometry.left()) < 12f && kotlin.math.abs(it.top - geometry.top()) < 12f &&
            kotlin.math.abs(it.right - geometry.right()) < 12f && kotlin.math.abs(it.bottom - geometry.bottom()) < 12f
    }

    private fun panelText(text: String, color: Int, size: Float, click: (() -> Unit)? = null): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (click != null) setOnClickListener { click() }
        }

    private fun toggleMiniBoard(button: TextView) {
        miniVisible = !miniVisible
        miniBoard?.visibility = if (miniVisible) View.VISIBLE else View.GONE
        button.text = if (miniVisible) "收起" else "展开"
        button.contentDescription = if (miniVisible) "收起小棋盘" else "展开小棋盘"
        button.setTextColor(if (miniVisible) 0xff81d4fa.toInt() else Color.WHITE)
    }

    private fun cycleMiniBoardSize(button: TextView) {
        val index = MINI_BOARD_SIZES_DP.indexOf(miniSizeDp)
        miniSizeDp = MINI_BOARD_SIZES_DP[(index + 1) % MINI_BOARD_SIZES_DP.size]
        val boardHeight = dp(miniSizeDp * 10 / 9)
        miniBoard?.layoutParams = miniBoard?.layoutParams?.apply {
            width = dp(miniSizeDp)
            height = boardHeight
        }
        // 思考细节必须跟着棋盘一起缩，否则棋盘变小后它会露出来，盖住主界面棋盘。
        thinkingScroll?.layoutParams = thinkingScroll?.layoutParams?.apply { height = boardHeight }
        button.contentDescription = "切换小棋盘大小，当前 ${miniSizeDp}dp"
        listener.onMiniBoardSizeChanged(miniSizeDp)
    }

    private fun overlayParams(x: Int, y: Int, width: Int = WindowManager.LayoutParams.WRAP_CONTENT) = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    /** 汉字按钮：一行两三个字，靠整条菜单换行来保证不挤。 */
    private fun textButton(label: String, description: String, color: Int, size: Float = 13f, action: (TextView) -> Unit): TextView =
        TextView(context).apply {
            text = label
            contentDescription = description
            setTextColor(color)
            textSize = size
            maxLines = 1
            gravity = Gravity.CENTER
            background = iconBackground()
            setPadding(dp(7), 0, dp(7), 0)
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(36),
            ).apply { marginEnd = dp(2) }
            setOnClickListener { action(this) }
        }

    /**
     * 按屏幕宽度把按钮提前换行排成菜单，最多三行；再挤也不继续长高。
     *
     * 顺带把拖动绑到整条菜单上，菜单空白处可直接拖。
     */
    private fun menuPanel(vararg buttons: View): LinearLayout {
        val maxWidth = (context.resources.displayMetrics.widthPixels - dp(16)).coerceAtLeast(dp(120))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var row = menuRow()
        var used = 0
        for (button in buttons) {
            button.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val margin = (button.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0
            val width = button.measuredWidth + margin
            if (used > 0 && used + width > maxWidth && column.childCount < MAX_MENU_ROWS - 1) {
                column.addView(row)
                row = menuRow()
                used = 0
            }
            row.addView(button)
            used += width
        }
        if (row.childCount > 0) column.addView(row)
        attachDrag(column, { panel }, { panelParams })
        return column
    }

    private fun menuRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 引擎执子二选一：点了红必走就不能点黑必走；再点一次即为交还手动。 */
    private fun aiButton(side: Side): TextView {
        val label = if (side == Side.RED) "红必走" else "黑必走"
        val button = textButton(label, "引擎执子：$label", if (engineSide == side) ACTIVE_COLOR else Color.WHITE) {
            engineSide = if (engineSide == side) null else side
            paintAiButtons()
            listener.onAiSideChanged(engineSide)
        }
        if (side == Side.RED) redAiButton = button else blackAiButton = button
        return button
    }

    private fun paintAiButtons() {
        redAiButton?.setTextColor(if (engineSide == Side.RED) ACTIVE_COLOR else Color.WHITE)
        blackAiButton?.setTextColor(if (engineSide == Side.BLACK) ACTIVE_COLOR else Color.WHITE)
    }

    /** 换引擎：点开就是一份竖排档案，现役的那个点亮，点谁换谁。 */
    private fun engineButton(): TextView =
        textButton("换引擎", "切换引擎档案", Color.WHITE) { showEngineDialog() }

    /** 层数 / 步时：同一条上限的两种量法，点亮当前生效的那一个。 */
    private fun limitButton(kind: String): TextView {
        val depth = kind == "DEPTH"
        val label = if (depth) "层数" else "步时"
        val button = textButton(
            label,
            "$label 上限",
            if (listener.searchLimitKind() == kind) ACTIVE_COLOR else Color.WHITE,
        ) { if (depth) showDepthDialog() else showMoveTimeDialog() }
        if (depth) depthButton = button else moveTimeButton = button
        return button
    }

    private fun paintLimitButtons() {
        val active = listener.searchLimitKind()
        depthButton?.setTextColor(if (active == "DEPTH") ACTIVE_COLOR else Color.WHITE)
        moveTimeButton?.setTextColor(if (active == "MOVETIME") ACTIVE_COLOR else Color.WHITE)
    }

    /**
     * 引擎档案弹窗：只列应用此刻仍持有、能加载的引擎，每个一行。
     *
     * 每个档案只给名称加一句特征（内置 / 目录），不铺详情；要细节请开「信息」。
     */
    private fun showEngineDialog() {
        if (dialog != null) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        root.addView(panelText("换引擎", Color.WHITE, 18f))
        val engines = listener.engines()
        if (engines.isEmpty()) {
            root.addView(panelText("暂无可切换的引擎：还没有加载过任何引擎档案。", 0xffbdbdbd.toInt(), 14f))
        }
        val activeId = listener.activeEngineId()
        engines.forEach { engine ->
            val current = engine.id == activeId
            // ★ 「电脑端异地引擎」这一行：名称里已自带「（电脑）」标记，直接原样列出，
            //   不再套「（内置 Pikafish）」之类的来源后缀，免得标成两回。
            val label = if (engine.id == com.pikafish.chess.heavy.EngineLink.REMOTE_ENGINE_ID) {
                "${engine.name}${if (current) " · 当前" else ""}"
            } else {
                val trait = engine.directory?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "内置 Pikafish"
                "${engine.name}（$trait）${if (current) " · 当前" else ""}"
            }
            val row = panelText(label, if (current) ACTIVE_COLOR else Color.WHITE, 15f) {
                if (!current) listener.onEngineSelected(engine.id)
                closeDialog()
            }
            row.setPadding(dp(6), dp(10), dp(6), dp(10))
            root.addView(row)
        }
        root.addView(panelText("取消", Color.WHITE, 15f) { closeDialog() })
        showCenteredDialog(root)
    }

    /** 层数：只填层数，填了它步时就让位（两者互斥）。 */
    private fun showDepthDialog() {
        val current = listener.searchLimitValue().takeIf { listener.searchLimitKind() == "DEPTH" && it > 0L }
        showLimitInput(
            title = "层数上限",
            hint = "层数，如 12",
            prefill = (current ?: 12L).toString(),
            note = "设定后按层数思考，步时不再生效。",
        ) { text ->
            val value = text.toLongOrNull()?.takeIf { it > 0L && it <= Int.MAX_VALUE } ?: return@showLimitInput "请输入正整数层数"
            listener.onSearchLimitChanged("DEPTH", value)
            null
        }
    }

    /** 步时：填秒数，填了它层数就让位（两者互斥）。 */
    private fun showMoveTimeDialog() {
        val current = listener.searchLimitValue().takeIf { listener.searchLimitKind() == "MOVETIME" && it > 0L }
        showLimitInput(
            title = "步时上限",
            hint = "秒，如 1.5",
            prefill = (current?.div(1000.0))?.toString() ?: "1",
            note = "设定后按步时思考，层数不再生效。",
        ) { text ->
            val seconds = text.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@showLimitInput "请输入正的秒数"
            listener.onSearchLimitChanged("MOVETIME", (seconds * 1000).toLong())
            null
        }
    }

    /** 通用数值弹窗；返回非空字符串表示校验不过，弹窗留在原地并提示。 */
    private fun showLimitInput(
        title: String,
        hint: String,
        prefill: String,
        note: String,
        apply: (String) -> String?,
    ) {
        if (dialog != null) return
        val input = EditText(context).apply {
            this.hint = hint
            setSingleLine()
            setText(prefill)
            setTextColor(Color.WHITE)
            setHintTextColor(0xff9e9e9e.toInt())
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        root.addView(panelText(title, Color.WHITE, 18f))
        root.addView(input)
        root.addView(panelText(note, 0xffbdbdbd.toInt(), 13f))
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(panelText("取消", Color.WHITE, 15f) { closeDialog() })
        buttons.addView(panelText("确定", 0xff81d4fa.toInt(), 15f) {
            val error = apply(input.text.toString().trim())
            if (error != null) {
                input.error = error
                return@panelText
            }
            paintLimitButtons()
            closeDialog()
        })
        root.addView(buttons)
        showCenteredDialog(root, focusInput = true)
        input.requestFocus()
    }

    /** 居中弹窗：要输入时自动弹出键盘，纯列表则不弹。 */
    private fun showCenteredDialog(root: LinearLayout, focusInput: Boolean = false) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                if (focusInput) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        dialog = root
        windows.addView(root, params)
    }

    /** 上下翻转小棋盘：只把显示倒个个儿，框选区域与识别几何完全不动。 */
    private fun flipButton(): TextView =
        textButton("翻转", "上下翻转小棋盘", if (flipped) 0xff81d4fa.toInt() else Color.WHITE, 13f) { button ->
            flipped = !flipped
            button.setTextColor(if (flipped) 0xff81d4fa.toInt() else Color.WHITE)
            miniBoard?.setFlipped(flipped)
        }

    /** 拖动手柄：按住它才移动整块菜单，旁边按钮仍保持即时点击。 */
    private fun dragHandle(window: () -> View?, params: () -> WindowManager.LayoutParams?): TextView {
        val handle = textButton("移动", "按住可移动整块菜单", Color.WHITE) {}
        attachDrag(handle, window, params)
        return handle
    }

    /**
     * 把拖动绑定到某个悬浮窗：只搬它自己那一坨，别的悬浮窗纹丝不动。
     *
     * 绑在整块面板上时，面板空白处也能直接拖；面板里的图标照旧即时响应点击。
     */
    private fun attachDrag(view: View, window: () -> View?, params: () -> WindowManager.LayoutParams?) {
        var rawX = 0f
        var rawY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            val target = window() ?: return@setOnTouchListener true
            val layout = params() ?: return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX
                    rawY = event.rawY
                    startX = layout.x
                    startY = layout.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = context.resources.displayMetrics
                    val maxX = max(0, metrics.widthPixels - target.width)
                    val maxY = max(0, metrics.heightPixels - target.height)
                    layout.x = (startX + (event.rawX - rawX).toInt()).coerceIn(0, maxX)
                    layout.y = (startY + (event.rawY - rawY).toInt()).coerceIn(0, maxY)
                    windows.updateViewLayout(target, layout)
                }
                MotionEvent.ACTION_UP -> view.performClick()
            }
            true
        }
    }

    fun close() {
        closeDialog()
        remove(selection)
        remove(panel)
        remove(boardPanel)
        selection = null
        panel = null
        panelParams = null
        boardPanel = null
        boardParams = null
        miniBoard = null
        thinkingView = null
        thinkingScroll = null
        miniVisible = false
    }

    private fun removeSelection() {
        remove(selection)
        selection = null
    }

    private fun remove(view: View?) {
        if (view != null) windows.removeView(view)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun iconBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(0xdd404040.toInt())
    }

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(0xdd202020.toInt())
    }

    private inner class SelectionView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
        private var left = 0f
        private var top = 0f
        private var right = 0f
        private var bottom = 0f
        private var ready = false
        private var drag = Drag.NONE
        private var previousX = 0f
        private var previousY = 0f
        private var pressedButton = BUTTON_NONE
        // 悬浮窗局部触点不一定以物理屏幕左上为原点；棋盘几何必须使用投屏帧的绝对坐标。
        private var screenOffsetX = 0f
        private var screenOffsetY = 0f

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0x22000000)
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.WHITE
            paint.textSize = 34f
            canvas.drawText(
                if (ready) "拖动框内移动；拖动边或圆点微调；底部可选先手并确认"
                else "拖拽左上、右下交叉点中心",
                30f, 60f, paint,
            )
            if (!ready && drag != Drag.CREATE) return
            val displayLeft = min(left, right)
            val displayRight = max(left, right)
            val displayTop = min(top, bottom)
            val displayBottom = max(top, bottom)
            paint.style = Paint.Style.STROKE
            paint.color = 0xff00e5ff.toInt()
            canvas.drawRect(displayLeft, displayTop, displayRight, displayBottom, paint)
            for (x in 0 until 9)
                for (y in 0 until 10)
                    canvas.drawCircle(displayLeft + x * (displayRight - displayLeft) / 8f, displayTop + y * (displayBottom - displayTop) / 9f, 5f, paint)
            if (!ready) return
            paint.style = Paint.Style.FILL
            canvas.drawCircle(left, top, 12f, paint)
            canvas.drawCircle(right, top, 12f, paint)
            canvas.drawCircle(left, bottom, 12f, paint)
            canvas.drawCircle(right, bottom, 12f, paint)
            paint.color = 0xcc222222.toInt()
            canvas.drawRect(0f, height - CONTROL_HEIGHT, width.toFloat(), height.toFloat(), paint)
            paint.textSize = 32f
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.WHITE
            canvas.drawText("取消", width / 6f, height - 42f, paint)
            paint.color = if (syncSide == Side.RED) 0xffff5252.toInt() else 0xffe0e0e0.toInt()
            canvas.drawText(if (syncSide == Side.RED) "红先" else "黑先", width / 2f, height - 42f, paint)
            paint.color = Color.WHITE
            canvas.drawText("确认", width * 5f / 6f, height - 42f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> beginDrag(event)
                MotionEvent.ACTION_MOVE -> updateDrag(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag(event)
            }
            return true
        }

        private fun beginDrag(event: MotionEvent) {
            previousX = event.x
            previousY = event.y
            screenOffsetX = event.rawX - event.x
            screenOffsetY = event.rawY - event.y
            if (ready && event.y >= height - CONTROL_HEIGHT) {
                pressedButton = when {
                    event.x < width / 3f -> BUTTON_CANCEL
                    event.x < width * 2f / 3f -> BUTTON_SIDE
                    else -> BUTTON_CONFIRM
                }
                return
            }
            if (!ready) {
                drag = Drag.CREATE
                left = event.x
                right = event.x
                top = event.y
                bottom = event.y
            } else {
                drag = hit(event.x, event.y)
            }
        }

        private fun updateDrag(event: MotionEvent) {
            if (pressedButton != BUTTON_NONE) return
            if (drag == Drag.CREATE) {
                right = event.x
                bottom = event.y
            } else {
                resize(event.x - previousX, event.y - previousY)
                previousX = event.x
                previousY = event.y
            }
            invalidate()
        }

        private fun endDrag(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                drag = Drag.NONE
                pressedButton = BUTTON_NONE
                invalidate()
                return
            }
            if (pressedButton != BUTTON_NONE) {
                val button = pressedButton
                pressedButton = BUTTON_NONE
                if (event.y >= height - CONTROL_HEIGHT) {
                    when (button) {
                        BUTTON_SIDE -> {
                            syncSide = if (syncSide == Side.RED) Side.BLACK else Side.RED
                            invalidate()
                        }
                        BUTTON_CANCEL -> {
                            val synchronize = synchronizeAfterSelection
                            synchronizeAfterSelection = false
                            removeSelection()
                            if (!synchronize) listener.onSelectionCancelled()
                        }
                        else -> {
                            val synchronize = synchronizeAfterSelection
                            synchronizeAfterSelection = false
                            val box = screenGeometry()
                            removeSelection()
                            this@LinkOverlay.geometry = box
                            if (synchronize) listener.onReconfigured(box, syncSide)
                            else listener.onConfigured(box, syncSide)
                        }
                    }
                }
                return
            }
            if (drag == Drag.CREATE) {
                right = event.x
                bottom = event.y
                val startLeft = left
                val startTop = top
                left = min(startLeft, right).coerceIn(0f, width.toFloat())
                right = max(startLeft, right).coerceIn(0f, width.toFloat())
                top = min(startTop, bottom).coerceIn(0f, height.toFloat())
                bottom = max(startTop, bottom).coerceIn(0f, height.toFloat())
                ready = right - left >= MIN_SIZE && bottom - top >= MIN_SIZE
            }
            drag = Drag.NONE
            invalidate()
        }

        private fun screenGeometry(): BoardGeometry {
            return BoardGeometry(
                left + screenOffsetX,
                top + screenOffsetY,
                right + screenOffsetX,
                bottom + screenOffsetY,
            )
        }

        private fun hit(x: Float, y: Float): Drag {
            val nearLeft = x.distance(left) <= HANDLE_RADIUS
            val nearRight = x.distance(right) <= HANDLE_RADIUS
            val nearTop = y.distance(top) <= HANDLE_RADIUS
            val nearBottom = y.distance(bottom) <= HANDLE_RADIUS
            if (nearLeft && nearTop) return Drag.TOP_LEFT
            if (nearRight && nearTop) return Drag.TOP_RIGHT
            if (nearLeft && nearBottom) return Drag.BOTTOM_LEFT
            if (nearRight && nearBottom) return Drag.BOTTOM_RIGHT
            if (nearLeft && y in top..bottom) return Drag.LEFT
            if (nearRight && y in top..bottom) return Drag.RIGHT
            if (nearTop && x in left..right) return Drag.TOP
            if (nearBottom && x in left..right) return Drag.BOTTOM
            if (x in left..right && y in top..bottom) return Drag.MOVE
            return Drag.NONE
        }

        private fun resize(dx: Float, dy: Float) {
            when (drag) {
                Drag.MOVE -> move(dx, dy)
                Drag.LEFT, Drag.TOP_LEFT, Drag.BOTTOM_LEFT -> left = (left + dx).coerceIn(0f, right - MIN_SIZE)
                Drag.RIGHT, Drag.TOP_RIGHT, Drag.BOTTOM_RIGHT -> right = (right + dx).coerceIn(left + MIN_SIZE, width.toFloat())
                else -> Unit
            }
            when (drag) {
                Drag.TOP, Drag.TOP_LEFT, Drag.TOP_RIGHT -> top = (top + dy).coerceIn(0f, bottom - MIN_SIZE)
                Drag.BOTTOM, Drag.BOTTOM_LEFT, Drag.BOTTOM_RIGHT -> bottom = (bottom + dy).coerceIn(top + MIN_SIZE, height.toFloat())
                else -> Unit
            }
        }

        private fun move(dx: Float, dy: Float) {
            val actualX = dx.coerceIn(-left, width - right)
            val actualY = dy.coerceIn(-top, height - bottom)
            left += actualX
            right += actualX
            top += actualY
            bottom += actualY
        }
    }

    private fun Float.distance(other: Float): Float = kotlin.math.abs(this - other)

    /** 连线时始终显示应用当前局面；红方在下，箭头只投影主变前两着。 */
    private class MiniBoardView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var board = CharArray(90) { '.' }
        private var arrows: List<Move> = emptyList()
        /** 上下翻转：行列各倒过来，楚河汉界也跟着换边。 */
        private var flipped = false

        fun update(state: GameState, arrows: List<Move>) {
            board = BoardProjection.characters(state.position())
            this.arrows = arrows.take(2)
            invalidate()
        }

        fun setFlipped(value: Boolean) {
            if (flipped == value) return
            flipped = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cell = min((width - 24f) / 8f, (height - 24f) / 9f)
            val left = (width - cell * 8f) / 2f
            val top = (height - cell * 9f) / 2f
            paint.style = Paint.Style.FILL
            paint.color = 0xffe8bd79.toInt()
            canvas.drawRect(left - cell / 2f, top - cell / 2f, left + cell * 8.5f, top + cell * 9.5f, paint)
            drawDecor(canvas, left, top, cell)
            arrows.forEachIndexed { index, move ->
                paint.strokeWidth = 4f
                paint.color = if (index == 0) Color.RED else 0xff1976d2.toInt()
                val from = point(move.from.toInt(), left, top, cell)
                val to = point(move.to.toInt(), left, top, cell)
                canvas.drawLine(from.first, from.second, to.first, to.second, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(to.first, to.second, 5f, paint)
                paint.style = Paint.Style.STROKE
            }
            for (square in board.indices) {
                val piece = board[square]
                if (piece == '.') continue
                val point = point(square, left, top, cell)
                paint.style = Paint.Style.FILL
                paint.color = 0xfff8edd1.toInt()
                canvas.drawCircle(point.first, point.second, cell * .36f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = if (piece.isUpperCase()) Color.RED else 0xff202020.toInt()
                canvas.drawCircle(point.first, point.second, cell * .36f, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = cell * .43f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(label(piece), point.first, point.second + cell * .15f, paint)
            }
        }

        private fun point(square: Int, left: Float, top: Float, cell: Float): Pair<Float, Float> {
            val file = square % 9
            val rank = square / 9
            val column = if (flipped) 8 - file else file
            val row = if (flipped) rank else 9 - rank
            return left + column * cell to top + row * cell
        }

        /** 楚河汉界断开、九宫米字、炮位与兵卒位标记，与主棋盘保持一致。 */
        private fun drawDecor(canvas: Canvas, left: Float, top: Float, cell: Float) {
            fun segments(list: List<BoardDecor.Segment>, width: Float) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = width
                paint.color = 0xff64431f.toInt()
                for (segment in list) canvas.drawLine(
                    left + segment.x1 * cell, top + segment.y1 * cell,
                    left + segment.x2 * cell, top + segment.y2 * cell, paint,
                )
            }
            segments(BoardDecor.fileLines(), 1.5f)
            segments(BoardDecor.rankLines(), 1.5f)
            segments(BoardDecor.palaceLines(), 1.5f)
            for ((file, row) in BoardDecor.CANNON_POINTS + BoardDecor.PAWN_POINTS)
                segments(BoardDecor.markerSegments(file, row, BoardDecor.MARKER_GAP, BoardDecor.MARKER_ARM), 1.2f)
            if (cell < 14f) return
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = cell * .4f
            canvas.drawText(if (flipped) "漢 界" else "楚 河", left + cell * 2f, top + cell * 4.5f + cell * .14f, paint)
            canvas.drawText(if (flipped) "楚 河" else "漢 界", left + cell * 6f, top + cell * 4.5f + cell * .14f, paint)
        }

        /** 小棋盘同样要区分红黑用字，否则黑方会显示成红方的卒/象/将/砲。 */
        private fun label(piece: Char): String {
            val red = piece.isUpperCase()
            return when (piece.lowercaseChar()) {
                'r' -> "车"
                'n' -> "马"
                'b' -> if (red) "相" else "象"
                'a' -> if (red) "仕" else "士"
                'k' -> if (red) "帅" else "将"
                'c' -> if (red) "炮" else "砲"
                else -> if (red) "兵" else "卒"
            }
        }
    }
    // ═════════ ★★ 连点器（物理点击）★★ ═════════
    //  三态：0=关 / 1=定位(红点可拖) / 2=运行(红点触摸穿透 + 每次点击闪烁计数)
    //  ★ 关键修正：运行态必须加 FLAG_NOT_TOUCHABLE，否则红点自己吃掉无障碍点击，
    //    表现为「对准了却不点」。
    private var clickDot: View? = null
    private var clickDotParams: WindowManager.LayoutParams? = null
    private var autoClickState = 0
    private var autoClickIntervalMs = 15000L
    private var autoClickCount = 0
    private val clickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clickTick = object : Runnable {
        override fun run() {
            if (autoClickState != 2) return
            // ★ 只在“真的卡住”时补点：画面静止 ≥8s 且引擎不在思考。
            //   否则正常对弈里每 3 秒一记「同步」会把回合强行重启，引擎就会“目中无人”连走。
            // ★ 纯超时触发：只要 10 秒内没有任何“推动局面”的动作，就补点一次。
            //   不再用“引擎是否在思考”做门禁——那个状态一旦卡住会把连点器一起憋死。
            val idle = com.yk.xiangqi.link.LinkForegroundService.idleMillis()
            if (idle < autoClickIntervalMs) {
                clickHandler.removeCallbacks(this)
                clickHandler.postDelayed(this, autoClickIntervalMs)
                return
            }
            val v = clickDot ?: return
            val lp = clickDotParams ?: return
            val ok = com.yk.xiangqi.link.LinkAccessibilityService.tapAt(
                lp.x + v.width / 2f, lp.y + v.height / 2f
            )
            if (ok) {
                autoClickCount++
                // ★ 可见反馈：每次点击闪一下（放大+变亮），并刷新按钮计数
                v.animate().scaleX(1.35f).scaleY(1.35f).setDuration(90).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }.start()
                updateAutoClickLabel()
            }
            clickHandler.removeCallbacks(this)
            clickHandler.postDelayed(this, autoClickIntervalMs)
        }
    }

    private fun toastClick(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun updateAutoClickLabel() {
        val v = autoClickButtonRef ?: return
        if (v is android.widget.Button || v is TextView) {
            (v as TextView).text = when (autoClickState) {
                2 -> "连点:$autoClickCount"
                1 -> "连点:定位"
                else -> "连点"
            }
        }
    }

    private var autoClickButtonRef: View? = null
    private var autoClickIntervalButtonRef: View? = null

    private fun autoClickButton(): View {
        val b = textButton(
            when (autoClickState) { 2 -> "连点:$autoClickCount"; 1 -> "连点:定位"; else -> "连点" },
            "连点器：点一下出现红点→拖到目标（如校准）→再点一次开始（之后红点不再挡手指）→再点一次停止",
            0xffba68c8.toInt()
        ) { advanceAutoClick() }
        autoClickButtonRef = b
        return b
    }

    private fun autoClickIntervalButton(): View {
        val b = textButton(
            "间隔 ${autoClickIntervalMs / 1000}s",
            "点开选间隔：弹出列表，点哪档就是哪档，运行中也能随时改",
            Color.WHITE
        ) { showIntervalPopup(it) }
        autoClickIntervalButtonRef = b
        return b
    }

    /** ★ 间隔列表弹窗：列出可选秒数，点一行即选中并保存；随时可重选。 */
    private fun showIntervalPopup(anchor: View) {
        val presets = longArrayOf(1000, 2000, 3000, 5000, 7000, 9000, 11000, 13000, 15000, 20000, 30000)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(0xF21E1E1E.toInt())
                setStroke(2, 0xFF555555.toInt())
            }
            setPadding(12, 10, 12, 10)
        }
        val pop = android.widget.PopupWindow(
            list, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true
        )
        presets.forEach { ms ->
            val chosen = ms == autoClickIntervalMs
            val row = TextView(context).apply {
                text = (if (chosen) "✓ " else "") + "${ms / 1000} 秒"
                textSize = 15f
                setTextColor(if (chosen) 0xFF9CCC65.toInt() else Color.WHITE)
                setPadding(28, 18, 28, 18)
                setOnClickListener {
                    applyInterval(ms)
                    pop.dismiss()
                }
            }
            list.addView(row)
        }
        try {
            pop.showAsDropDown(anchor, 0, -anchor.height - 8)
        } catch (_: Exception) {
            pop.showAtLocation(anchor, Gravity.CENTER, 0, 0)
        }
    }

    private fun applyInterval(ms: Long) {
        autoClickIntervalMs = ms
        clickHandler.removeCallbacks(clickTick)
        if (autoClickState == 2) clickHandler.postDelayed(clickTick, autoClickIntervalMs)
        (autoClickIntervalButtonRef as? TextView)?.text = "间隔 ${ms / 1000}s"
        updateAutoClickLabel()
    }


    /** 0→1（出点定位）→2（开始连点，红点穿透）→0（停止） */
    private fun advanceAutoClick() {
        when (autoClickState) {
            0 -> {
                if (!com.yk.xiangqi.link.LinkAccessibilityService.isActive()) {
                    toastClick("无障碍服务未启用：连点无法生效，请先在系统设置里开启")
                    return
                }
                showClickDot()
                autoClickState = 1
                toastClick("红点已出现：拖到「校准」或「同步」按钮上，再点一次「连点」开始")
            }
            1 -> {
                val lp = clickDotParams
                val v = clickDot
                if (lp != null && v != null) {
                    // ★ 关键：运行态让红点“不吃触摸”，点击才会落到下方真实按钮
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    windows.updateViewLayout(v, lp)
                }
                autoClickState = 2
                autoClickCount = 0
                clickHandler.removeCallbacks(clickTick)
                clickHandler.postDelayed(clickTick, autoClickIntervalMs)
                updateAutoClickLabel()
            }
            else -> {
                autoClickState = 0
                clickHandler.removeCallbacks(clickTick)
                clickDot?.let { runCatching { windows.removeView(it) } }
                clickDot = null
                clickDotParams = null
                autoClickCount = 0
                toastClick("连点已停止")
            }
        }
        updateAutoClickLabel()
    }

    private fun showClickDot() {
        val density = context.resources.displayMetrics.density
        val size = (26 * density).toInt()
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCEF5350.toInt())
                setStroke((2 * density).toInt(), Color.WHITE)
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 240
            y = 900
        }
        var downX = 0
        var downY = 0
        var baseX = lp.x
        var baseY = lp.y
        dot.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX.toInt(); downY = e.rawY.toInt()
                    baseX = lp.x; baseY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = baseX + (e.rawX.toInt() - downX)
                    lp.y = baseY + (e.rawY.toInt() - downY)
                    windows.updateViewLayout(dot, lp)
                    true
                }
                else -> true
            }
        }
        windows.addView(dot, lp)
        clickDot = dot
        clickDotParams = lp
    }

}
