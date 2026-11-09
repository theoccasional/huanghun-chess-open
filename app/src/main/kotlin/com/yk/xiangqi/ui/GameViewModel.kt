package com.yk.xiangqi.ui

import android.app.Application
import android.os.Build
import android.util.Log
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yk.xiangqi.GameRuntime
import com.yk.xiangqi.Settings
import com.yk.xiangqi.XiangqiApplication
import com.yk.xiangqi.book.CloudBook
import com.yk.xiangqi.book.MoveInfo
import com.yk.xiangqi.book.Obk
import com.yk.xiangqi.book.ObkException
import com.yk.xiangqi.book.Zobrist
import com.yk.xiangqi.bridge.BoardProjection
import com.yk.xiangqi.core.GameAction
import com.yk.xiangqi.core.GameState
import com.yk.xiangqi.core.Move
import com.yk.xiangqi.core.Position
import com.yk.xiangqi.core.Side
import com.yk.xiangqi.engine.EngineFiles
import com.yk.xiangqi.engine.EngineMatch
import com.yk.xiangqi.engine.GoParams
import com.yk.xiangqi.engine.ThinkingInfo
import com.yk.xiangqi.engine.UciEngine
import com.yk.xiangqi.engine.UciOption
import com.yk.xiangqi.notation.Notation
import com.pikafish.chess.ChessGame
import com.pikafish.chess.engine.SandboxOracle
import com.pikafish.chess.engine.UserEngineStore
import com.pikafish.chess.heavy.EngineLink
import com.pikafish.chess.model.Board as HhBoard
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position as HhPosition
import com.pikafish.chess.model.RepetitionRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

enum class PlayMode {
    MANUAL, QUERY, RED_AI, BLACK_AI, BOTH_AI,
}

data class UiState(
    val board: CharArray,
    val side: Side,
    val legal: Set<String>,
    val cursor: Int,
    val length: Int,
    val mode: PlayMode,
    val redAi: Boolean,
    val blackAi: Boolean,
    val flipped: Boolean,
    val bothArrows: Boolean,
    /** 当前搜索最新的一条完整信息；不是搜索历史。 */
    val analysis: ThinkingInfo? = null,
    /** 当前主变的标准中文记谱，与 analysis 的 pv 一一对应。 */
    val pvChinese: List<String> = emptyList(),
    val book: List<MoveInfo> = emptyList(),
    /** 开局库候选着法的中文记谱，与 book 一一对应。 */
    val bookChinese: List<String> = emptyList(),
    val bookName: String? = null,
    /** 云库候选着法（在线开局 + 残局一体），与 book 同为候选列表。 */
    val cloud: List<CloudBook.Entry> = emptyList(),
    /** 云库候选着法的中文记谱，与 cloud 一一对应。 */
    val cloudChinese: List<String> = emptyList(),
    /**
     * 当前行棋方上一着走子来源标注：主变（引擎思考）、库招（本地开局库）、
     * 云库（在线云库）、均有（本地库与云库都有这一着）；手工走子或开局时为 null。
     */
    val moveSource: String? = null,
    val bookNeedsOptimization: Boolean = false,
    val status: String = "准备中",
    /** 当前加载引擎的标题：本地名称 + 引擎自报名称（含版本/日期）。 */
    val engineTitle: String = "",
    /** 引擎详细信息：作者、可执行文件、线程、Hash 等，供“信息”面板直接展示。 */
    val engineDetail: List<String> = emptyList(),
    /** 双引擎比赛是否正在进行。 */
    val matchRunning: Boolean = false,
    /** 最近一场比赛的结果文本；未开始时为 null。 */
    val matchResult: String? = null,
)

class GameViewModel(app: Application) : AndroidViewModel(app), UciEngine.Listener {
    private companion object {
        const val ENGINE_LOG_TAG = "XiangqiEngine"
        /**
         * ★ 引擎重启<b>不设次数上限</b>：崩溃/无响应就无限重拉，绝不出现「重试三次就放弃、
         * 引擎永远躺平」的死角。为了不让一台彻底坏掉的引擎把这块刷爆，用退避节流：
         * 连续第 n 次重启等 min(n,8)×{@link #ENGINE_RESTART_BACKOFF_STEP_MS}，一次成功搜索后
         * 计数清零、节流随即归位。
         */
        const val ENGINE_RESTART_BACKOFF_STEP_MS = 250L
        const val ENGINE_RESTART_BACKOFF_MAX_MS = 2_000L
        /** 连线保活自动重启的最小间隔，避免坏引擎把棋盘反复刷回开局。 */
        const val ENGINE_ENSURE_COOLDOWN_MS = 10_000L
        /**
         * 连线看门狗节拍：只要连线还在，就按这个间隔自检一次「轮到引擎走子却没人起搜」。
         *
         * <p>连线里引擎走子靠事件驱动（外部走子→refresh→起搜）。一旦某次事件被漏掉、
         * 或引擎恰好在这一拍没就绪，起搜就再也不会发生，小棋盘便停在「对得上却不动」。
         * 看门狗不依赖任何事件，纯粹按节拍复查，把漏掉的这一拍补上。</p>
         */
        const val LINK_WATCHDOG_INTERVAL_MS = 900L
        /**
         * 搜索最长允许无输出时长；超过即判引擎卡死，重启补发。
         * 收紧到 12s：UCI 引擎正常每 ~200ms 就会吐一条 info，12s 毫无输出基本可以断定
         * 进程已经躺平，早一点判死、早一点按「呼吸」重认出招，别让悬浮窗一直停在
         * 「等待引擎思考…」那种假死观感上。
         */
        const val LINK_SEARCH_TIMEOUT_MS = 12_000L
    }
    private data class BookRead(
        val candidates: List<MoveInfo>,
        val needsOptimization: Boolean,
        val failedBooks: List<String> = emptyList(),
        val error: String? = null,
    )

    private val application = app as XiangqiApplication
    private val settings = application.settings()
    private val gameRuntime = application.gameRuntime()
    private val gameListener = GameRuntime.Listener { _, _, origin ->
        if (origin == GameRuntime.Origin.EXTERNAL) {
            viewModelScope.launch {
                // 连线期间外部棋盘一有更新（校准重建、外部走子），顺手把引擎保活，
                // 免得“棋盘校准上了、引擎却不出招”。
                ensureEngineAlive()
                moveSource = null
                state = snapshot("外部棋盘已走子")
                refresh()
            }
        }
    }
    private val aiListener = GameRuntime.AiListener { _, _ ->
        viewModelScope.launch { cancelSearch(); state = snapshot(); refresh() }
    }
    /** 连线开始或结束都会重算分析：连线期间即使不由本方走子也持续思考。 */
    private val linkListener = GameRuntime.LinkListener { active ->
        viewModelScope.launch {
            if (active) startLinkWatchdog() else stopLinkWatchdog()
            ensureEngineAlive()
            state = snapshot()
            refresh()
        }
    }
    /** 悬浮窗的换引擎 / 层数 / 步时指令：引擎归这里所有，由这里落地。 */
    private val linkControlListener = object : GameRuntime.LinkControlListener {
        override fun onLinkEngineSelected(engineId: String) {
            viewModelScope.launch { selectEngine(engineId) }
        }

        override fun onLinkSearchChanged() {
            viewModelScope.launch { applyLinkSearchLimit() }
        }

        override fun onLinkCloudToggled(enabled: Boolean) {
            viewModelScope.launch { setCloudBookEnabled(enabled) }
        }

        override fun onLinkBookSelectionChanged(bookId: String, selected: Boolean) {
            viewModelScope.launch {
                setBookSelection(bookId, selected)
            }
        }

        override fun onLinkRestartEngine() {
            restartEngineWithSafeFen("引擎重启")
        }
    }
    private val bookZobrist = app.assets.open("book/zobrist.txt").use(Zobrist::read)
    private val engine = UciEngine(this)

    /** ViewModel 是当前开局库资源的唯一所有者；每个勾选的库一个 Obk。 */
    private var obks: Map<String, Obk> = emptyMap()
    private var flipped = false
    private var bothArrows = true
    private var queryEnabled = false
    private var redSearch = loadSearch(true)
    private var blackSearch = loadSearch(false)
    /**
     * 连线悬浮窗设的搜索上限；非空时压过红黑各自的参数。
     *
     * 层数与步时互斥就落在这里：同一时刻只可能有一个非空的上限。
     */
    private var linkSearchLimit = readLinkSearchLimit()
    private var pendingAlternative = false
    private var pendingImmediate = false
    private var activeSearchId = -1L
    /** 连线看门狗协程；连线开始拉起、连线结束取消。 */
    private var linkWatchdogJob: Job? = null
    /**
     * 亚洲象棋联合会棋例判定器：与主界面 {@code ChessGame} 用的是同一个类，一字不改。
     * 连线期间用它把「会走回近历史、又属照将/捉子」的着法从候选里剔掉，引擎随即在
     * 两步之内把子力定位、变招，绝不「车将一下再将一下」地原地磨棋盘。
     */
    private val repetitionRule = RepetitionRule()
    /** 最近一次「发起搜索 / 收到引擎输出」的时刻，供看门狗判活。 */
    private var lastEngineActivityMs = 0L
    /** 已自动重启引擎的次数；成功收到一次搜索输出后清零，坏引擎不会无限重启。 */
    private var engineRestartAttempts = 0
    /** 重启正在进行中，避免多次失败触发并发重启。 */
    private var engineRestarting = false
    /** 引擎是否已完成 uci + setoption + readyok；未就绪时不发 go，避免把 searchId 悬在那。 */
    private var engineReady = false
    /** 上次“连线保活”自动重启的时间戳；给保活加冷却，坏引擎不会把棋盘反复刷回开局。 */
    private var lastEnsureAliveMs = 0L
    private var engineReadyText = "引擎已就绪"
    private var selectedNetwork: File? = null
    private var bookCandidates: List<MoveInfo> = emptyList()
    private var cloudCandidates: List<CloudBook.Entry> = emptyList()
    /** 当前行棋方上一着走子来源标注；见 UiState.moveSource。 */
    private var moveSource: String? = null
    private var bookNeedsOptimization = false
    private var refreshId = 0L
    private var analysis: ThinkingInfo? = null
    private var uciOptions: List<UciOption> = emptyList()
    private var engineDirectoryToDelete: File? = null
    /** 引擎握手自报的 {@code id name} / {@code id author}，用于区分当前加载的是哪个版本。 */
    private var engineUciName: String? = null
    private var engineAuthor: String? = null
    /** 当前引擎的可执行文件，用于在信息里给出确切位置。 */
    private var engineExecutable: File? = null
    /** 正在进行的双引擎比赛；非 null 时暂停本界面自身的分析引擎。 */
    private var match: EngineMatch? = null
    private var matchResult: String? = null
    /** 刚导入、名称还只是占位符的引擎 id；等它握手里自报 id name 后再按真名登记。 */
    private var pendingEngineId: String? = null

    var state by mutableStateOf(snapshot("正在安装 Pikafish…"))
        private set

    init {
        gameRuntime.addListener(gameListener)
        gameRuntime.addAiListener(aiListener)
        gameRuntime.addLinkListener(linkListener)
        gameRuntime.addLinkControlListener(linkControlListener)
        reloadBook()
        viewModelScope.launch {
            try {
                startSelectedEngine()
                state = snapshot("正在验证 UCI 握手…")
            } catch (error: Exception) {
                state = snapshot("引擎不可用: ${error.message}")
            }
        }
    }

    fun tap(from: Int, to: Int): Boolean {
        val move = Move(from, to)
        if (move.uci() !in state.legal || !reduce(GameAction.Play(move))) {
            state = snapshot("不是合法着")
            return false
        }
        moveSource = null
        cancelSearch()
        state = snapshot()
        refresh()
        return true
    }

    /** 丢弃当前线性历史，回到标准初始局面；AI 与查询模式保持用户当前选择。 */
    fun newGame() {
        cancelSearch()
        moveSource = null
        reduce(GameAction.Reset(Position.start()))
        state = snapshot("新对局")
        refresh()
    }

    fun previous() = navigate(GameAction.Navigate.PREVIOUS)
    fun next() = navigate(GameAction.Navigate.NEXT)
    fun first() = navigate(GameAction.Navigate.FIRST)
    fun last() = navigate(GameAction.Navigate.LAST)

    fun flip() {
        flipped = !flipped
        state = snapshot()
    }

    fun arrows() {
        bothArrows = !bothArrows
        state = snapshot()
    }

    fun setAi(red: Boolean, enabled: Boolean) {
        if (enabled) queryEnabled = false
        gameRuntime.setAi(if (red) Side.RED else Side.BLACK, enabled)
    }

    /** 查询与 AI 互斥；再次点击关闭查询，回到纯手动模式。 */
    fun query() {
        queryEnabled = !queryEnabled
        if (queryEnabled) {
            val redEnabled = gameRuntime.aiEnabled(Side.RED)
            val blackEnabled = gameRuntime.aiEnabled(Side.BLACK)
            if (redEnabled) gameRuntime.setAi(Side.RED, false)
            if (blackEnabled) gameRuntime.setAi(Side.BLACK, false)
            if (redEnabled || blackEnabled) return
        }
        cancelSearch()
        state = snapshot()
        refresh()
    }

    fun engineConfig(): Settings.Engine = activeEngineConfig()

    /** ★ 连线/信息面板用的引擎清单：直接取黄昏象棋自己的引擎仓库（UserEngineStore）。 */
    fun engines(): List<Settings.Engine> {
        val ctx = getApplication<Application>()
        val prior = settings.activeEngine()
        return UserEngineStore.list(ctx).map { entry ->
            Settings.Engine(
                entry.id,
                entry.displayName,
                hhEngineDirectory(entry.isBuiltin, entry.dirName),
                prior.threads.coerceAtLeast(1),
                prior.hashMb.coerceAtLeast(1),
                prior.options,
            )
        }
    }

    /** 当前采用引擎的配置视图：id / 名称 / 目录都来自黄昏象棋的引擎仓库。 */
    private fun activeEngineConfig(): Settings.Engine {
        val ctx = getApplication<Application>()
        val entry = UserEngineStore.activeEntry(ctx)
        val prior = settings.activeEngine()
        return Settings.Engine(
            entry.id,
            entry.displayName,
            hhEngineDirectory(entry.isBuiltin, entry.dirName),
            prior.threads.coerceAtLeast(1),
            prior.hashMb.coerceAtLeast(1),
            prior.options,
        )
    }

    /** 外置引擎目录（内置返回 null，表示回退 App 内置皮卡鱼）。 */
    private fun hhEngineDirectory(builtin: Boolean, dirName: String): String? {
        if (builtin) return null
        return File(UserEngineStore.dir(getApplication<Application>()), dirName).path
    }

    fun books(): List<Settings.Book> = settings.books()

    fun activeBookConfig(): Settings.Book? = settings.activeBook()

    /** 内置两项和 NNUE 文件有固定入口，其余由引擎声明决定。 */
    fun engineOptions(): List<UciOption> = uciOptions.filter {
        !it.name.equals("Threads", true) && !it.name.equals("Hash", true) &&
            !it.name.equals("MultiPV", true) && !it.name.equals("EvalFile", true)
    }

    fun setEngineConfig(
        threads: String,
        hashMb: String,
        options: Map<String, String>,
    ): String? {
        val threadValue = threads.toIntOrNull()
        val hashValue = hashMb.toIntOrNull()
        if (threadValue == null || hashValue == null || threadValue <= 0 || hashValue <= 0) return "线程和 Hash 都必须是正整数"
        val savedOptions = options.filterKeys { !it.equals("MultiPV", true) }
        val prior = settings.activeEngine()
        try {
            for ((name, optionValue) in savedOptions)
                uciOptions.firstOrNull { it.name.equals(name, true) }?.setOption(optionValue)
        } catch (error: IllegalArgumentException) {
            return error.message ?: "引擎选项无效"
        }
        val value = Settings.Engine(
            prior.id,
            prior.name,
            prior.directory,
            threadValue,
            hashValue,
            savedOptions
        )
        settings.saveEngine(value, true)
        engine.configure(
            linkedMapOf(
                "Threads" to threadValue.toString(),
                "Hash" to hashValue.toString(),
            ).apply { putAll(savedOptions) })
        state = snapshot("已保存引擎配置")
        return null
    }

    /** 名称仅是本地显示信息，不应受 UCI 选项校验或引擎重启影响。 */
    fun renameEngine(name: String): String? {
        val engineName = name.trim()
        if (engineName.isEmpty()) return "请输入引擎名称"
        val prior = settings.activeEngine()
        settings.saveEngine(
            Settings.Engine(
                prior.id,
                engineName,
                prior.directory,
                prior.threads,
                prior.hashMb,
                prior.options,
            ),
            true,
        )
        state = snapshot("已保存引擎名称")
        return null
    }

    fun pressEngineOption(name: String) {
        try {
            uciOptions.firstOrNull { it.name.equals(name, true) }?.press()
                ?: throw IllegalArgumentException("引擎未声明该选项")
            engine.pressOption(name)
        } catch (error: IllegalArgumentException) {
            state = snapshot(error.message ?: "引擎选项无效")
        }
    }

    fun selectEngine(id: String) {
        val ctx = getApplication<Application>()
        cancelSearch()
        uciOptions = emptyList()
        // ★ 选了「电脑端异地引擎」：档位切到异地，算棋整个交给配对上的辎重包端，
        //   本机不加载权重、不跑搜索——长期连线也不发热。这里不启本机引擎进程，
        //   由沙盒询招通道按异地档位改连辎重包端那台。
        if (id == EngineLink.REMOTE_ENGINE_ID) {
            EngineLink.setMode(ctx, EngineLink.MODE_REMOTE)
            try {
                if (!SandboxOracle.pkMode) SandboxOracle.restart(ctx)
            } catch (_: Exception) {
            }
            state = snapshot("已切到电脑端异地引擎（辎重包端代算）")
            return
        }
        // ★ 选的是本机装载的引擎档案：档位切回本地（本机自算），再落到黄昏象棋自己的
        //   引擎仓库——连线“换引擎”与主棋盘采用同一台，用户在黄昏象棋里加载的那几台
        //   外置引擎，这里切换时都找得到、切得动。
        EngineLink.setMode(ctx, EngineLink.MODE_LOCAL)
        UserEngineStore.setActive(ctx, id)
        // 同步黄昏象棋主询招通道（非 PK 时），让“切换引擎”在全局一致生效。
        try {
            if (!SandboxOracle.pkMode) SandboxOracle.restart(ctx)
        } catch (_: Exception) {
            // 主通道重启失败不影响连线自身切换。
        }
        state = snapshot("正在切换 UCI 引擎…")
        viewModelScope.launch {
            try {
                startSelectedEngine()
            } catch (error: Exception) {
                state = snapshot("切换引擎失败：${error.message}")
            }
        }
    }

    fun deleteEngine() {
        val deleted = settings.activeEngine()
        if (deleted.id == Settings.BUNDLED_ENGINE_ID) {
            state = snapshot("不能删除内置引擎")
            return
        }
        cancelSearch()
        settings.removeEngine(deleted.id)
        uciOptions = emptyList()
        engineDirectoryToDelete = deleted.directory?.let(::File)
        state = snapshot("已删除 ${deleted.name}，正在切换至 Pikafish…")
        viewModelScope.launch {
            try {
                startSelectedEngine()
            } catch (error: Exception) {
                state = snapshot("切换内置引擎失败：${error.message}")
            }
        }
    }

    fun selectBook(id: String) {
        if (id == settings.activeBook()?.id) return
        settings.selectBook(id)
        state = snapshot("正在切换开局库…")
        reloadBook()
    }

    fun setBookConfig(name: String, enabled: Boolean, order: Settings.Book.Order): String? {
        val book = settings.activeBook() ?: return "未选择开局库"
        val bookName = name.trim()
        if (bookName.isEmpty()) return "请输入开局库名称"
        settings.saveBook(Settings.Book(book.id, bookName, book.path, enabled, order), true)
        state = snapshot("已保存开局库设置")
        reloadBook()
        return null
    }

    fun deleteBook() {
        val deleted = settings.activeBook() ?: return
        cancelSearch()
        closeBooks()
        settings.removeBook(deleted.id)
        bookCandidates = emptyList()
        bookNeedsOptimization = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) { deleteImportedBook(File(deleted.path)) }
            state = snapshot("已删除 ${deleted.name}")
            refresh()
        }
    }

    fun setGoParams(red: Boolean, kind: SearchConfig.Kind, text: String): String? {
        val value = text.trim().toLongOrNull() ?: return "请输入正整数"
        if (value <= 0L) return "限制必须大于 0"
        if (kind == SearchConfig.Kind.DEPTH && value > Int.MAX_VALUE) return "深度不能超过 ${Int.MAX_VALUE}"
        if (red) redSearch = SearchConfig(kind, value)
        else blackSearch = SearchConfig(kind, value)
        settings.setSearch(red, kind.name, value)
        state = snapshot("${if (red) "红" else "黑"}方参数：${goParamsLabel(kind, value)}")
        return null
    }

    private fun loadSearch(red: Boolean): SearchConfig {
        val kind = SearchConfig.Kind.entries.firstOrNull { it.name == settings.searchKind(red) }
            ?: SearchConfig.Kind.MOVETIME
        val value = settings.searchValue(red).takeIf { it > 0L } ?: 1_000L
        return SearchConfig(kind, value)
    }

    /** 连线悬浮窗设的上限：类型与数值缺一不可，缺了就当没设。 */
    private fun readLinkSearchLimit(): SearchConfig? {
        val kind = SearchConfig.Kind.entries.firstOrNull { it.name == settings.linkSearchKind() } ?: return null
        val value = settings.linkSearchValue().takeIf { it > 0L } ?: return null
        return SearchConfig(kind, value)
    }

    /** 悬浮窗改了层数/步时：重新读取上限并立即按新参数重开分析。 */
    private fun applyLinkSearchLimit() {
        linkSearchLimit = readLinkSearchLimit()
        val limit = linkSearchLimit
        state = snapshot(if (limit == null) "搜索上限已清除" else "搜索上限：${goParamsLabel(limit.kind, limit.value)}")
        refresh()
    }

    /** 主界面或悬浮窗切云库开关：落到黄昏象棋同一套云库并重查着法。 */
    fun setCloudBookEnabled(enabled: Boolean) {
        ChessGame.shared?.cloudBookEnabled = enabled
        state = snapshot(if (enabled) "云库已开启" else "云库已关闭")
        refresh()
    }

    fun cloudBookEnabled(): Boolean = ChessGame.shared?.cloudBookEnabled == true

    /** 悬浮窗勾选/取消勾选本地开局库：落到黄昏象棋同一套库（LocalBookManager）。 */
    fun setBookSelection(bookId: String, selected: Boolean) {
        val book = ChessGame.shared?.localBook
        val id = bookId.toIntOrNull()
        if (book != null && id != null) book.setEnabled(id, selected)
        state = snapshot("开局库勾选已更新")
        refresh()
    }

    /** 连线期间引擎的搜索参数：悬浮窗设了上限就用它，没设就无限思考只看细节。 */
    private fun linkGoParams(searchMoves: List<String>? = null): GoParams =
        linkSearchLimit?.toGoParams(searchMoves) ?: GoParams.infinite(searchMoves)

    /** 引擎真正走子时的搜索参数：连线上限最优先，其次才回到本方设置。 */
    private fun searchParams(side: Side, searchMoves: List<String>? = null): GoParams {
        val params = linkSearchLimit?.toGoParams() ?: goParams(side)
        searchMoves?.let(params.searchMoves::addAll)
        return params
    }

    fun immediate() {
        if (activeSearchId < 0L || state.analysis?.pv?.firstOrNull() !in state.legal) {
            state = snapshot("当前没有可立即执行的最佳着")
            return
        }
        pendingImmediate = true
        engine.stop()
    }

    fun alternative() {
        if (mode() == PlayMode.MANUAL) {
            state = snapshot("请先开启查询或 AI 模式")
            return
        }
        val bestMove = state.analysis?.pv?.firstOrNull() ?: return
        val choices = state.legal.filter { it != bestMove }
        if (choices.isEmpty()) {
            state = snapshot("没有可用变招")
            return
        }
        pendingAlternative = true
        analysis = null
        state = snapshot("正在搜索变招")
        val game = gameRuntime.state()
        activeSearchId = if (mode() == PlayMode.QUERY) engine.go(game, GoParams.infinite(choices))
        else engine.go(game, goParams(state.side, choices))
    }

    override fun onSearchFailed(searchId: Long, message: String) {
        viewModelScope.launch {
            if (searchId != activeSearchId) return@launch
            activeSearchId = -1L
            // 引擎遇到它不认的局面时会直接报错并退出进程。此时必须把它重新拉起来，
            // 否则之后所有思考都石沉大海，悬浮窗只会一直停在「等待引擎思考」。
            // ★ 不再设「最多重启几次」的上限：只要连线还在，失败多少次就重拉多少次，
            //   否则一旦爬到上限，引擎就永久躺平，正是用户看到的「假死」。
            Log.w(ENGINE_LOG_TAG, "搜索失败，自动重启引擎（累计第 ${engineRestartAttempts + 1} 次）：$message")
            restartEngineWithSafeFen("引擎已中断")
        }
    }

    override fun onInfo(searchId: Long, value: ThinkingInfo) {
        viewModelScope.launch {
            if (searchId != activeSearchId) return@launch
            engineRestartAttempts = 0
            lastEngineActivityMs = android.os.SystemClock.elapsedRealtime()
            analysis = value
            publishArrows()
            publishAnalysis()
            state = snapshot("分析中")
        }
    }

    override fun onEngineIdentity(name: String?, author: String?) {
        viewModelScope.launch {
            engineUciName = name?.trim()?.takeIf { it.isNotEmpty() }
            engineAuthor = author?.trim()?.takeIf { it.isNotEmpty() }
            adoptPendingEngineName()
            engineReadyText = engineTitle()
            publishEngineInfo()
            if (name.isNullOrBlank()) return@launch
            Log.i(ENGINE_LOG_TAG, "引擎身份：$name${if (author.isNullOrBlank()) "" else " / $author"}")
            state = snapshot(state.status)
        }
    }

    /** 刚导入的引擎在握手里自报 id name 后按真名登记，免得列表与比赛里一堆「自定义引擎」分不清。 */
    private fun adoptPendingEngineName() {
        val id = pendingEngineId ?: return
        val uci = engineUciName ?: return
        pendingEngineId = null
        val config = settings.engines().firstOrNull { it.id == id } ?: return
        if (config.name == uci) return
        settings.saveEngine(
            Settings.Engine(config.id, uci, config.directory, config.threads, config.hashMb, config.options),
            config.id == settings.activeEngine().id,
        )
    }

    override fun onUciOptions(options: List<com.yk.xiangqi.engine.UciOption>) {
        viewModelScope.launch {
            uciOptions = options
            val config = activeEngineConfig()
            val values = linkedMapOf(
                "Threads" to config.threads.toString(),
                "Hash" to config.hashMb.toString(),
            )
            // EvalFile 由当前引擎的实际权重决定，绝不沿用上一次留下的旧路径。
            values.putAll(config.options.filterKeys { !it.equals("MultiPV", true) && !it.equals("EvalFile", true) })
            options.firstOrNull { it.name.equals("MultiPV", true) }?.let { values[it.name] = "1" }
            val evalFile = options.firstOrNull { it.name.equals("EvalFile", ignoreCase = true) }
            if (evalFile != null) selectedNetwork?.let { values[evalFile.name] = it.absolutePath }
            engine.configure(values)
            state = snapshot("正在配置 UCI 引擎…")
        }
    }

    override fun onReady() {
        viewModelScope.launch {
            engineReady = true
            engineDirectoryToDelete?.let { directory ->
                engineDirectoryToDelete = null
                try {
                    withContext(Dispatchers.IO) { EngineFiles.deleteBundle(getApplication(), directory) }
                } catch (_: Exception) {
                    // 新引擎已经可用；旧资源清理失败不影响本次切换。
                }
            }
            state = snapshot(engineReadyText)
            Log.i(ENGINE_LOG_TAG, engineReadyText)
            refresh()
        }
    }

    override fun onEngineFailed(message: String) {
        viewModelScope.launch {
            activeSearchId = -1L
            // ★ 引擎进程可能已退出：必须把就绪标志清掉，否则「保活/看门狗」会以为它还活着
            //   （engineReady 仍为 true），怎么都不重启，小棋盘就永久停在「对得上却不动」。
            engineReady = false
            state = snapshot(message)
            Log.e(ENGINE_LOG_TAG, message)
            if (gameRuntime.linkActive())
                restartEngineWithSafeFen("引擎异常退出", resetBoard = false)
        }
    }

    override fun onBestMove(searchId: Long, info: com.yk.xiangqi.engine.BestMoveInfo) {
        viewModelScope.launch {
            if (searchId != activeSearchId) return@launch
            engineRestartAttempts = 0
            lastEngineActivityMs = android.os.SystemClock.elapsedRealtime()
            // ★ 以权威局面为准校验合法性：UiState 里的 legal 可能因某次没走到的 snapshot 而滞后，
            //   用它判非法会把一条好着白白扔掉，小棋盘就卡在「对得上却不动」。
            val realPosition = gameRuntime.state().position()
            if (info.bestMove !in realPosition.legalMoves().map { it.uci() }) {
                Log.w(ENGINE_LOG_TAG, "忽略非法 bestmove：${info.bestMove}")
                return@launch
            }
            val active = mode()
            val realSide = realPosition.sideToMove()
            val aiTurn =
                active == PlayMode.BOTH_AI || (active == PlayMode.RED_AI && realSide == Side.RED) || (active == PlayMode.BLACK_AI && realSide == Side.BLACK)
            val shouldPlay =
                pendingImmediate || aiTurn || (pendingAlternative && active != PlayMode.QUERY)
            activeSearchId = -1L
            if (shouldPlay) {
                Log.i(ENGINE_LOG_TAG, "自动走子：${info.bestMove}")
                pendingImmediate = false
                pendingAlternative = false
                if (reduce(GameAction.Play(Move.parse(info.bestMove)))) {
                    moveSource = "主变"
                    state = snapshot("引擎走子")
                    refresh()
                }
            } else {
                state = snapshot("已得到最佳着")
            }
        }
    }

    override fun onCleared() {
        match?.stop()
        match = null
        gameRuntime.removeListener(gameListener)
        gameRuntime.removeAiListener(aiListener)
        gameRuntime.removeLinkListener(linkListener)
        gameRuntime.removeLinkControlListener(linkControlListener)
        gameRuntime.setAnalysis(GameRuntime.Analysis.EMPTY)
        gameRuntime.setEngineInfo(GameRuntime.EngineInfo.EMPTY)
        engine.close()
        closeBooks()
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            val bookId = UUID.randomUUID().toString()
            val result = withContext(Dispatchers.IO) {
                val target = File(
                    File(getApplication<Application>().filesDir, "books").also { it.mkdirs() },
                    "book-$bookId.obk"
                )
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { input -> target.outputStream().use(input::copyTo) }
                    ?: return@withContext Result.failure(IllegalStateException("无法读取所选文件"))
                try {
                    Result.success(Obk.open(target, bookZobrist))
                } catch (error: ObkException) {
                    target.delete()
                    Result.failure(error)
                }
            }
            result.fold(
                onSuccess = { opened ->
                    opened.close()
                    val config = Settings.Book(
                        bookId, "开局库", opened.path().path, true, Settings.Book.Order.MAX_SCORE
                    )
                    settings.saveBook(config, true)
                    state = snapshot("已导入开局库")
                    reloadBook()
                },
                onFailure = { error -> state = snapshot("导入开局库失败：${error.message}") },
            )
        }
    }

    /**
     * 一次导入解决引擎和权重：压缩包里的引擎与权重一起装好，只选到权重时放进当前引擎的权重位。
     *
     * 用户不必分两次分别导入，选一个文件即可。
     */
    fun importEngine(uri: Uri) {
        viewModelScope.launch {
            try {
                val current = settings.activeEngine().directory?.let(::File)
                val imported = withContext(Dispatchers.IO) {
                    EngineFiles.importSelection(getApplication(), uri, current, Build.SUPPORTED_ABIS.toList())
                }
                cancelSearch()
                if (imported.kind == EngineFiles.Kind.NETWORK) {
                    engineReadyText = "权重已放进当前引擎（${imported.network?.name}，${EngineFiles.sizeText(imported.network)}）"
                    startEngine(imported.engine, imported.directory, imported.network)
                    state = snapshot("正在验证引擎与权重…")
                    return@launch
                }
                pendingEngineId = saveEngine(imported.directory.path)
                engineReadyText = if (imported.network != null)
                    "引擎与权重已就绪（${EngineFiles.architecture(imported.engine)}，权重 ${EngineFiles.sizeText(imported.network)}）"
                else
                    "引擎已就绪（${EngineFiles.architecture(imported.engine)}），压缩包里没有权重"
                startEngine(imported.engine, imported.directory, imported.network)
                state = snapshot("正在验证自定义 UCI 引擎…")
            } catch (error: Exception) {
                state = snapshot("引擎导入失败：${error.message}")
            }
        }
    }

    fun optimizeBook() {
        viewModelScope.launch {
            val file = selectedBookFile()
            if (file == null) {
                state = snapshot("未选择开局库")
                return@launch
            }
            state = snapshot("正在建立旁路索引…")
            closeBooks()
            try {
                val result = withContext(Dispatchers.IO) {
                    Obk.open(file, bookZobrist).use { it.optimize() }
                }
                state =
                    snapshot("旁路索引完成：${result.indexedRows} 行，跳过 ${result.skippedRows} 行")
                reloadBook()
            } catch (error: Exception) {
                state = snapshot("旁路索引失败：${error.message}")
            }
        }
    }

    fun removeBook() {
        settings.clearActiveBook()
        reloadBook()
    }

    private fun snapshot(status: String = "准备就绪"): UiState {
        val game = gameRuntime.state()
        val position = game.position()
        return UiState(
            board = BoardProjection.characters(position),
            side = position.sideToMove(),
            legal = position.legalMoves().map { it.uci() }.toSet(),
            cursor = game.cursor(),
            length = game.allMoves().size,
            mode = mode(),
            redAi = gameRuntime.aiEnabled(Side.RED),
            blackAi = gameRuntime.aiEnabled(Side.BLACK),
            flipped = flipped,
            bothArrows = bothArrows,
            analysis = analysis,
            // 主变记谱与引擎思考同视角：引擎只分析真实局面，记谱便用同一个真实局面，第一手中文数字自然对齐。
            pvChinese = Notation.chineseVariation(position, analysis?.pv.orEmpty()),
            book = bookCandidates,
            bookChinese = bookCandidates.map { candidate ->
                runCatching { Notation.chinese(position, Move.parse(candidate.move)) }.getOrDefault(candidate.move)
            },
            bookName = ChessGame.shared?.localBook?.listBooks()?.firstOrNull { it.enabled }?.name,
            cloud = cloudCandidates,
            cloudChinese = cloudCandidates.map { candidate ->
                runCatching { Notation.chinese(position, Move.parse(candidate.move)) }.getOrDefault(candidate.move)
            },
            moveSource = moveSource,
            bookNeedsOptimization = bookNeedsOptimization,
            status = status,
            engineTitle = engineTitle(),
            engineDetail = engineDetailLines(),
            matchRunning = match != null,
            matchResult = matchResult,
        )
    }

    private fun navigate(action: GameAction) {
        cancelSearch()
        if (!reduce(action)) return
        state = snapshot()
        refresh()
    }

    private fun reduce(action: GameAction): Boolean {
        return gameRuntime.reduce(action, GameRuntime.Origin.LOCAL)
    }

    private fun mode(): PlayMode {
        val red = gameRuntime.aiEnabled(Side.RED)
        val black = gameRuntime.aiEnabled(Side.BLACK)
        return when {
            queryEnabled -> PlayMode.QUERY
            red && black -> PlayMode.BOTH_AI
            red -> PlayMode.RED_AI
            black -> PlayMode.BLACK_AI
            else -> PlayMode.MANUAL
        }
    }

    private fun goParams(side: Side, searchMoves: List<String>? = null): GoParams {
        return searchConfig(side).toGoParams(searchMoves)
    }

    private fun searchConfig(side: Side): SearchConfig {
        return if (side == Side.RED) redSearch else blackSearch
    }

    fun goParamsKind(red: Boolean): SearchConfig.Kind =
        searchConfig(if (red) Side.RED else Side.BLACK).kind

    fun goParamsValue(red: Boolean): Long = searchConfig(if (red) Side.RED else Side.BLACK).value

    fun goParamsLabel(red: Boolean): String = goParamsLabel(goParamsKind(red), goParamsValue(red))

    fun linkConfig(): Settings.Link = settings.link()

    fun setLinkConfig(frameSettleMs: String, tapDurationMs: String, tapIntervalMs: String, modelThreads: String): String? = try {
        settings.setLink(Settings.Link(frameSettleMs.toLong(), tapDurationMs.toLong(), tapIntervalMs.toLong(), modelThreads.toInt()))
        null
    } catch (error: Exception) {
        "连线参数无效"
    }

    /** 可选参赛引擎，含内置 Pikafish。 */
    fun enginesForMatch(): List<Settings.Engine> = engines()

    /** 用两个引擎开一场比赛：各自独立进程，分执红黑自动对弈。 */
    fun startMatch(redId: String, blackId: String) {
        if (match != null) return
        val all = engines()
        val redConfig = all.firstOrNull { it.id == redId }
        val blackConfig = all.firstOrNull { it.id == blackId }
        if (redConfig == null || blackConfig == null) {
            state = snapshot("请选择双方引擎")
            return
        }
        viewModelScope.launch {
            cancelSearch()
            matchResult = null
            state = snapshot("正在准备比赛引擎…")
            val red = prepareParticipant(redConfig, Side.RED)
            val black = prepareParticipant(blackConfig, Side.BLACK)
            if (red == null || black == null) {
                state = snapshot("参赛引擎不可用")
                return@launch
            }
            // 重置对局并关闭人机开关，比赛期间只由两个引擎决定走子。
            queryEnabled = false
            gameRuntime.setAi(Side.RED, false)
            gameRuntime.setAi(Side.BLACK, false)
            reduce(GameAction.Reset(Position.start()))
            val running = EngineMatch(
                red = red,
                black = black,
                runtime = gameRuntime,
                onStatus = { text -> state = snapshot(text) },
                onFinished = { text ->
                    matchResult = text
                    match = null
                    state = snapshot(text)
                    refresh()
                },
            )
            match = running
            state = snapshot("比赛开始")
            running.start()
        }
    }

    /** 终止正在进行的比赛；两个引擎进程都会关闭。 */
    fun stopMatch() {
        val running = match ?: return
        match = null
        running.stop()
        state = snapshot("比赛已停止")
        refresh()
    }

    /** 解析一方引擎的可执行文件与工作目录；内置与自定义引擎走不同路径。 */
    private suspend fun prepareParticipant(config: Settings.Engine, side: Side): EngineMatch.Participant? {
        // ★ 参赛引擎同样按 id 取自黄昏象棋自己的引擎仓库（内置 → App 内置皮卡鱼）。
        val ctx = getApplication<Application>()
        val entry = UserEngineStore.entry(ctx, config.id)
        val binary = entry?.let { UserEngineStore.binaryFor(ctx, it) }
        val directory: File
        val executable: File
        val network: File?
        if (binary != null) {
            directory = binary.parentFile ?: UserEngineStore.dir(ctx)
            executable = binary
            network = UserEngineStore.weightFor(ctx, entry)
        } else {
            directory = withContext(Dispatchers.IO) { EngineFiles.installBundled(getApplication()) }
            executable = File(directory, "pikafish")
            network = File(directory, "pikafish.nnue").takeIf { it.isFile }
        }
        if (!executable.isFile) return null
        val search = if (side == Side.RED) redSearch else blackSearch
        return EngineMatch.Participant(
            label = config.name,
            executable = executable,
            workingDir = directory,
            network = network,
            threads = config.threads,
            hashMb = config.hashMb,
            options = config.options,
            goParams = { search.toGoParams() },
        )
    }

    private fun goParamsLabel(kind: SearchConfig.Kind, value: Long): String = when (kind) {
        SearchConfig.Kind.MOVETIME -> "${value / 1000.0} 秒"
        SearchConfig.Kind.DEPTH -> "$value 层"
        SearchConfig.Kind.NODES -> "$value 节点"
    }

    private fun refresh() {
        // 比赛中两个引擎独占对局；界面分析引擎必须停手，避免三方同时走子。
        if (match != null) {
            state = snapshot(state.status)
            return
        }
        val game = gameRuntime.state()
        val requestedRefreshId = ++refreshId
        if (activeSearchId >= 0L) cancelSearch()
        if (game.isOver()) {
            state = snapshot("对局结束")
            return
        }
        val active = mode()
        // ★ 行棋方一律取权威局面，不取 UiState.side：某条刷新路径可能没先 snapshot，
        //   用滞后的 side 判「是否轮到 AI」会把该走的一步判成不该走，引擎就此停摆。
        val sideToMove = game.position().sideToMove()
        val aiTurn =
            active == PlayMode.BOTH_AI || (active == PlayMode.RED_AI && sideToMove == Side.RED) || (active == PlayMode.BLACK_AI && sideToMove == Side.BLACK)
        val position = game.position()
        // ★ 连线复用黄昏象棋自己的库/云渠道（同一个 source of truth），不再另起一套 YK 象棋的库。
        val hhGame = ChessGame.shared
        val hasLocalBook = hhGame?.localBook?.listBooks()?.any { it.enabled } == true
        val cloudEnabled = hhGame?.cloudBookEnabled == true
        if (!hasLocalBook && !cloudEnabled) {
            bookCandidates = emptyList()
            cloudCandidates = emptyList()
            bookNeedsOptimization = false
            beginAnalysis(active, aiTurn)
            return
        }
        state = snapshot("正在查询开局库…")
        viewModelScope.launch {
            val fen = position.toFen()
            val read = withContext(Dispatchers.IO) { readHhBooks(fen) }
            if (requestedRefreshId != refreshId || position !== gameRuntime.state()
                    .position()
            ) return@launch
            bookCandidates = read.candidates
            bookNeedsOptimization = false
            cloudCandidates = if (cloudEnabled) {
                withContext(Dispatchers.IO) { readHhCloud(fen) }
            } else {
                emptyList()
            }
            state = snapshot(read.error ?: when {
                bookCandidates.isNotEmpty() || cloudCandidates.isNotEmpty() -> "开局库命中"
                else -> "引擎分析"
            })
            beginAnalysis(active, aiTurn)
        }
    }

    /** ★ 复用黄昏象棋同一套外置开局库（LocalBookManager）：按当前局面 FEN 取 top-N 候选。 */
    private fun readHhBooks(fen: String): BookRead {
        val game = ChessGame.shared ?: return BookRead(emptyList(), false)
        return try {
            val board = HhBoard().apply { loadFromFen(fen) }
            val hits = game.localBook.queryTop(board, 8)
            BookRead(hits.map { MoveInfo(it.uci, 0, 0, 0, 0) }, false)
        } catch (error: Exception) {
            BookRead(emptyList(), false, error = "开局库不可用：${error.message}")
        }
    }

    /** ★ 复用黄昏象棋同一套云库（CloudBookClient）：按当前局面 FEN 取候选并统一成连线条目。 */
    private fun readHhCloud(fen: String): List<CloudBook.Entry> {
        val game = ChessGame.shared ?: return emptyList()
        return runCatching {
            game.cloudMovesFor(fen).map { CloudBook.Entry(it.move, it.score, it.winrate.toDouble()) }
        }.getOrDefault(emptyList())
    }

    private fun beginAnalysis(active: PlayMode, aiTurn: Boolean) {
        // 引擎还在握手或重启中时不发 go；否则 searchId 会被悬在那，
        // 界面永远停在「思考中」、AI 永远不走子。等 onReady 触发的 refresh 再来。
        if (!engineReady) return
        val order = Settings.Book.Order.MAX_SCORE
        val bookMove = when (order) {
            Settings.Book.Order.MAX_SCORE -> bookCandidates.firstOrNull()?.move ?: cloudCandidates.firstOrNull()?.move
            Settings.Book.Order.RANDOM -> bookCandidates.randomOrNull()?.move ?: cloudCandidates.randomOrNull()?.move
        }
        if (aiTurn && bookMove != null && reduce(GameAction.Play(Move.parse(bookMove)))) {
            moveSource = sourceOf(bookMove)
            state = snapshot("开局库走子")
            refresh()
        } else if (active == PlayMode.QUERY) {
            activeSearchId = engine.go(gameRuntime.state(), GoParams.infinite(null))
            lastEngineActivityMs = android.os.SystemClock.elapsedRealtime()
        } else if (aiTurn) {
            val side = gameRuntime.state().position().sideToMove()
            // ★ 连线期间连「引擎接管那方要走子」也先剔掉磨棋盘走法，让它走正招、别循环。
            activeSearchId = engine.go(gameRuntime.state(), searchParams(side, linkRepetitionSearchMoves()))
            lastEngineActivityMs = android.os.SystemClock.elapsedRealtime()
            Log.i(ENGINE_LOG_TAG, "请求自动走子：$side，搜索=$activeSearchId")
        } else if (gameRuntime.linkActive()) {
            // ★ 连线期间只分析真实局面，绝不虚构。
            //   指定了红必走/黑必走时，只有真轮到那一方走子才分析；若此刻并不该他走
            //   （他刚走完、对方还没落子），就干脆不分析——绝不把行棋方改判成他、
            //   硬造一个“归他走”的假局面。对着一个不存在的棋局算出来的形势是镜花水月，
            //   一步真、一步假地掺着算，稳定性极差，还会误导判断。
            //   行棋方取权威局面，避免 UiState.side 滞后导致误判「不该他走」。
            val side = gameRuntime.state().position().sideToMove()
            val assigned = engineSide()
            if (assigned != null && side != assigned) {
                analysis = null
                activeSearchId = -1L
                state = snapshot("等待对方走子（${if (assigned == Side.RED) "红" else "黑"}方执子）")
                return
            }
            // 没指定执子方时，谁走就替谁算——这本身就是真实局面，照常思考。
            activeSearchId = engine.go(gameRuntime.state(), linkGoParams(linkRepetitionSearchMoves()))
            lastEngineActivityMs = android.os.SystemClock.elapsedRealtime()
            Log.i(ENGINE_LOG_TAG, "连线持续思考：真实行棋=$side，搜索=$activeSearchId")
        }
    }

    /** 连线指定的引擎执子方；两边都没接管时返回 null。 */
    private fun engineSide(): Side? = when {
        gameRuntime.aiEnabled(Side.RED) -> Side.RED
        gameRuntime.aiEnabled(Side.BLACK) -> Side.BLACK
        else -> null
    }

    /**
     * ★ 棋例落地：连线期间 AI 主动避免循环违规（长照 / 长捉）。
     *
     * <p>与主界面 {@code ChessGame.getRepetitionViolatingMoves} 同源同逻辑，只是把历史快照
     * 换成连线自己的 {@code GameState} 局面序列：只要走完这步会把局面送回近 16 步内的历史、
     * 且这步属照将或捉子（将帅兵卒长捉不犯例，不计），就把它从候选里剔掉。这样
     * 「车将一下、再将一下」的无用往复在第一步就被钳住，引擎两步之内就能定位、随即变招。</p>
     *
     * <p>返回可交给引擎的 {@code searchmoves} 白名单——全部合法着减去违规着；无可剔时返回
     * {@code null}，不给引擎加任何额外约束。若所有着法都会循环（极端情形），同样返回
     * {@code null}，宁可让引擎自由走一步，也比无棋可走强。</p>
     */
    private fun linkRepetitionSearchMoves(): List<String>? {
        if (!gameRuntime.linkActive()) return null
        val game = gameRuntime.state()
        val cursor = game.cursor()
        if (cursor < 8) return null  // 开局前几手不检查

        val boardFen = game.position().toFen()
        val board = HhBoard().apply { loadFromFen(boardFen) }
        val aiColor = board.currentPlayer
        val legal = board.getLegalMoves(aiColor)
        if (legal.isEmpty()) return null

        // 近窗口快照（最后 16 步）：只在这圈子里找循环，避免很旧的「复原局面」被误判成磨棋盘。
        val window = (maxOf(0, cursor - 15)..cursor).map { game.positionAt(it).toFen() }
        val currentKey = repetitionRule.positionKey(boardFen)
        val currentOcc = window.count { repetitionRule.positionKey(it) == currentKey }
        val attacksBefore = repetitionAttackTargets(board, aiColor)

        val excluded = mutableSetOf<String>()
        for (move in legal) {
            val uci = move.toUci()
            val temp = HhBoard().apply { loadFromFen(boardFen) }
            temp.makeMoveUnchecked(move)
            val afterKey = repetitionRule.positionKey(temp.toFen())
            val afterOcc = window.count { repetitionRule.positionKey(it) == afterKey }

            // 走后局面一回近历史即介入排查；不回到历史 → 不构成循环，放行。
            if (currentOcc < 1 && afterOcc < 1) continue
            if (afterOcc < 1) continue

            val after = HhBoard().apply { loadFromFen(boardFen) }
            after.makeMoveUnchecked(move)
            val isCheck = after.isInCheck(after.currentPlayer)
            val newAttacks = repetitionAttackTargets(after, aiColor) - attacksBefore
            val isChase = newAttacks.isNotEmpty() &&
                move.piece.type != PieceType.KING &&
                move.piece.type != PieceType.PAWN

            if (isCheck || isChase) excluded.add(uci)
        }

        if (excluded.isEmpty() || excluded.size >= legal.size) return null
        Log.d(ENGINE_LOG_TAG, "★ 棋例：连线提前排除磨棋盘走法 ${excluded.size} 个 → $excluded")
        return legal.map { it.toUci() }.filterNot { it in excluded }
    }

    /** 某方能吃到的对方棋子位置（将/帅除外）——用于重复违规检测，与主界面同义。 */
    private fun repetitionAttackTargets(board: HhBoard, attackerColor: PieceColor): Set<HhPosition> {
        val targets = mutableSetOf<HhPosition>()
        for (m in board.getLegalMoves(attackerColor)) {
            if (m.captured != null) {
                val captured = board.getPiece(m.to)
                if (captured != null && captured.type != PieceType.KING) targets.add(m.to)
            }
        }
        return targets
    }

    private fun selectedBookFile(): File? {
        val book = settings.activeBook() ?: return null
        return File(book.path).takeIf { book.enabled && it.isFile }
    }

    private fun saveEngine(directory: String): String {
        val prior = settings.activeEngine()
        val id = UUID.randomUUID().toString()
        settings.saveEngine(
            Settings.Engine(
                id,
                "自定义引擎",
                directory,
                prior.threads,
                prior.hashMb,
                prior.options
            ), true
        )
        return id
    }

    private fun startEngine(executable: File, directory: File, network: File?) {
        selectedNetwork = network?.takeIf { it.isFile }
        engineExecutable = executable
        engineUciName = null
        engineAuthor = null
        engineReady = false
        publishEngineInfo()
        // 工作目录取引擎所在目录，让引擎能按自己认的名字在旁边找到权重。
        engine.start(executable, executable.parentFile ?: directory)
    }

    /** 引擎标题：本地名称 + 引擎自报名称（通常含版本号与构建日期）。 */
    private fun engineTitle(): String {
        val config = activeEngineConfig()
        val uci = engineUciName
        return when {
            uci == null -> config.name
            config.name == uci -> uci
            else -> "${config.name}（$uci）"
        }
    }

    /** “信息”面板里的引擎明细，逐行可直接展示。 */
    private fun engineDetailLines(): List<String> {
        val config = activeEngineConfig()
        val lines = mutableListOf<String>()
        lines += "本地名称：${config.name}"
        lines += "引擎自报：${engineUciName ?: "未上报"}"
        engineAuthor?.let { lines += "作者：$it" }
        lines += "可执行文件：${engineExecutable?.absolutePath ?: (config.directory ?: "内置 Pikafish")}"
        engineExecutable?.let { lines += "架构：${EngineFiles.architecture(it)}" }
        lines += "线程 ${config.threads} · 哈希 ${config.hashMb} MB"
        lines += if (selectedNetwork != null)
            "NNUE：${selectedNetwork?.name}（${EngineFiles.sizeText(selectedNetwork)}）"
        else
            "NNUE：未加载（0 MB）"
        return lines
    }

    /** 把当前引擎身份广播给连线悬浮窗，保证连线时也能看到用的是哪个引擎。 */
    private fun publishEngineInfo() {
        val config = activeEngineConfig()
        gameRuntime.setEngineInfo(
            GameRuntime.EngineInfo(
                config.id,
                config.name,
                engineUciName.orEmpty(),
                engineAuthor.orEmpty(),
                engineExecutable?.absolutePath.orEmpty(),
                config.threads,
                config.hashMb,
                selectedNetwork?.name.orEmpty(),
                EngineFiles.sizeText(selectedNetwork),
            )
        )
    }

    private fun deleteImportedBook(file: File) {
        val directory = File(getApplication<Application>().filesDir, "books").canonicalFile
        val target = file.canonicalFile
        if (target.parentFile != directory || !target.name.startsWith("book-"))
            return
        File(target.path + ".idx").delete()
        target.delete()
    }

    private suspend fun startSelectedEngine() {
        // ★ 引擎来源改成黄昏象棋自己的引擎仓库：内置 → App 内置皮卡鱼；外置 → 用户装载的那台。
        val ctx = getApplication<Application>()
        val entry = UserEngineStore.activeEntry(ctx)
        val binary = UserEngineStore.binaryFor(ctx, entry)
        if (binary != null) {
            engineReadyText = "${entry.displayName} 已就绪"
            selectedNetwork = UserEngineStore.weightFor(ctx, entry)
            startEngine(binary, binary.parentFile ?: File(UserEngineStore.dir(ctx), entry.dirName), selectedNetwork)
        } else {
            engineReadyText = "Pikafish 已就绪"
            val directory = withContext(Dispatchers.IO) { EngineFiles.installBundled(getApplication()) }
            startEngine(File(directory, "pikafish"), directory, File(directory, "pikafish.nnue"))
        }
    }

    /**
     * 重启引擎并把它盘活：用户主动点“重启”或引擎自行退出（退出码 1）时，都先把局面
     * 重置为[标准开局 FEN]（任何引擎都认识、绝不会报警的局面），再重新拉起引擎进程。
     *
     * <p>这条“重置到安全局面”不只是给用户一个全新开局，更是引擎的<b>自愈关键</b>：
     * 若保留当前（可能是识别认飞、引擎一喂就报错的）局面重启，新进程会立刻在同一处
     * 再次报错退出，于是陷入「重启→再崩→再重启」的死循环，最多三次就彻底躺平，
     * 表现为“引擎死活加载不上、重启也救不活”。先落到标准开局，引擎必然能握手成功、
     * 正常思考；随后连线的归约会发现与真实棋盘对不上而自行重建，一步校准都省。</p>
     *
     * @param resetBoard 是否把棋局一并重置为标准开局。用户点“重启”与崩溃自愈都取 true；
     *                   仅“连线保活”（棋盘已与真实棋局对齐、只是引擎需要重新拉起）取 false，
     *                   免得把一份已经校准好的局面又刷回开局。
     */
    private fun restartEngineWithSafeFen(reason: String, resetBoard: Boolean = true) {
        if (engineRestarting) return
        engineRestarting = true
        engineRestartAttempts++
        val attempts = engineRestartAttempts
        viewModelScope.launch {
            try {
                // ★ 无限重启靠退避节流：连续崩得越勤等得越久（封顶 2s），
                //   既保证「引擎一旦躺平就一定会被重新拉起」，又不让一台彻底坏掉的引擎
                //   以零间隔疯狂起停、把界面和日志刷爆。一次成功搜索后计数清零，节流归位。
                val backoff = kotlin.math.min(
                    ENGINE_RESTART_BACKOFF_MAX_MS,
                    ENGINE_RESTART_BACKOFF_STEP_MS * kotlin.math.min(attempts, 8),
                )
                if (backoff > 0L) delay(backoff)
                cancelSearch()
                moveSource = null
                if (resetBoard) {
                    // 先重置到安全局面再拉起引擎：onReady 后的 refresh 会让引擎
                    // 用 position fen 把当前局面发过去并立即开始思考。
                    reduce(GameAction.Reset(Position.fromFen(Position.START_FEN)))
                    state = snapshot("$reason：已重置标准开局，正在重启引擎…")
                } else {
                    state = snapshot("$reason：正在重启引擎…")
                }
                try {
                    startSelectedEngine()
                } catch (error: Exception) {
                    state = snapshot("引擎重启失败：${error.message}")
                }
                refresh()
            } finally {
                engineRestarting = false
            }
        }
    }

    /**
     * 连线期间的“引擎保活”：只要连线还没结束，引擎就必须活着。
     *
     * <p>用户点“校准”只能把棋盘对齐，若引擎此时已经躺平，光校准棋盘是没用的——
     * 小棋盘对上了，引擎却始终不出招。所以凡是连线期间外部棋盘有更新（含校准重建、
     * 外部走子）或连线状态变化，都顺手确认一次引擎；不活就立刻重新拉起，
     * 绝不让用户去手点重启。</p>
     */
    private fun ensureEngineAlive() {
        if (!gameRuntime.linkActive()) return
        if (engineReady || engineRestarting) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEnsureAliveMs < ENGINE_ENSURE_COOLDOWN_MS) return
        lastEnsureAliveMs = now
        Log.w(ENGINE_LOG_TAG, "连线保活：引擎未就绪，自动重新拉起")
        restartEngineWithSafeFen("连线保活", resetBoard = false)
    }

    /** 连线开始：拉起看门狗协程（已在跑则忽略）。 */
    private fun startLinkWatchdog() {
        if (linkWatchdogJob != null) return
        linkWatchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(LINK_WATCHDOG_INTERVAL_MS)
                if (!gameRuntime.linkActive()) break
                linkWatchdogTick()
            }
        }
    }

    /** 连线结束：停掉看门狗。 */
    private fun stopLinkWatchdog() {
        linkWatchdogJob?.cancel()
        linkWatchdogJob = null
    }

    /**
     * 连线看门狗一拍：不依赖任何事件，纯按节拍复查「轮到引擎走子却没人起搜」。
     *
     * <p>事件驱动的起搜只要漏掉一次，引擎就再也不会动——小棋盘明明和大棋盘对得上，
     * 却一直不出招，非得手点校准才恢复。这里每一拍都用<b>权威局面</b>复核：若该引擎走、
     * 引擎就绪、又没有任何搜索在跑，就补发一次；若搜索在跑却长时间毫无输出，就判它卡死，
     * 重启后补发。如此便把「漏掉的那一拍」兜住，流通不再靠运气。</p>
     */
    private fun linkWatchdogTick() {
        if (!gameRuntime.linkActive()) return
        // ★ 进程级硬检查（合并自另一路实现）：引擎进程若已真的不在了，而失败回调恰好没送达，
        //   engineReady 会一直为 true，看门狗就会误以为它还活着、只「补发搜索」，而 go 因 stdin
        //   为空石沉大海，界面永远停在「思考中」。这里每拍先确认真实存活，不在就清掉就绪标志，
        //   交给下面的 ensureEngineAlive 重新拉起。
        if (!engine.isAlive() && !engineRestarting) {
            engineReady = false
        }
        // 引擎躺平就拉活（自身带冷却与并发保护）。
        ensureEngineAlive()
        if (match != null || !engineReady) return
        val active = mode()
        if (active == PlayMode.MANUAL || active == PlayMode.QUERY) return
        val game = gameRuntime.state()
        if (game.isOver()) return
        val side = game.position().sideToMove()
        if (active == PlayMode.RED_AI && side != Side.RED) return
        if (active == PlayMode.BLACK_AI && side != Side.BLACK) return
        if (activeSearchId >= 0L) {
            // 搜索在跑：只要还在吐思考信息就不打扰；长时间毫无输出就判卡死。
            // ★ 同样不设重启上限：无响应多少次就重拉多少次，绝不让引擎永久假死。
            val idleMs = android.os.SystemClock.elapsedRealtime() - lastEngineActivityMs
            if (idleMs > LINK_SEARCH_TIMEOUT_MS) {
                Log.w(ENGINE_LOG_TAG, "连线看门狗：搜索 ${idleMs}ms 无输出，判卡死并重启补发")
                restartEngineWithSafeFen("连线看门狗：引擎无响应", resetBoard = false)
            }
            return
        }
        Log.i(ENGINE_LOG_TAG, "连线看门狗：轮到${if (side == Side.RED) "红" else "黑"}方，补发走子搜索")
        state = snapshot("轮到${if (side == Side.RED) "红" else "黑"}方走子")
        refresh()
    }

    /**
     * ★ 连线不再自持一套 YK 开局库：勾选 / 装载都由黄昏象棋的 LocalBookManager 负责，
     *   这里只需重查一次当前局面（refresh 会走黄昏象棋同一套库）。
     */
    private fun reloadBook() {
        bookCandidates = emptyList()
        bookNeedsOptimization = false
        refresh()
    }

    private fun openBooks(books: List<Settings.Book>): Map<String, Result<Obk>> =
        books.associate { book -> book.id to openBook(File(book.path)) }

    private fun openBook(file: File): Result<Obk> = try {
        Result.success(Obk.open(file, bookZobrist))
    } catch (error: ObkException) {
        Result.failure(error)
    }

    private fun readBooks(books: List<Settings.Book>, position: Position): BookRead {
        val candidates = ArrayList<MoveInfo>()
        val failedBooks = ArrayList<String>()
        var needsOptimization = false
        var error: String? = null
        for (book in books) {
            val opened = obks[book.id] ?: continue
            try {
                candidates += opened.query(position)
                needsOptimization = needsOptimization || opened.needsOptimization()
            } catch (fail: ObkException) {
                failedBooks += book.id
                error = "开局库不可用：${fail.message}"
            }
        }
        return BookRead(candidates, needsOptimization, failedBooks, error)
    }

    private fun closeBook(id: String) {
        obks[id]?.close()
        obks = obks - id
    }

    private fun closeBooks() {
        obks.values.forEach(Obk::close)
        obks = emptyMap()
    }

    /** 当前行棋方上一着走子来源标注：库招 / 云库 / 均有；其余为引擎思考（主变）。 */
    private fun sourceOf(uci: String): String {
        val inBook = bookCandidates.any { it.move == uci }
        val inCloud = cloudCandidates.any { it.move == uci }
        return when {
            inBook && inCloud -> "均有"
            inCloud -> "云库"
            inBook -> "库招"
            else -> "主变"
        }
    }

    private fun cancelSearch() {
        activeSearchId = -1L
        pendingImmediate = false
        pendingAlternative = false
        analysis = null
        gameRuntime.setArrows(emptyList())
        gameRuntime.setAnalysis(GameRuntime.Analysis.EMPTY)
        engine.cancelSearch()
    }

    private fun publishArrows() {
        val moves = analysis?.pv.orEmpty().take(2)
            .filter(Move::isUciCoordinate)
            .map(Move::parse)
        gameRuntime.setArrows(moves)
    }

    /** 把最新思考内容投影给连线悬浮窗：分值统一换算到红方视角，主变翻译成中文记谱。 */
    private fun publishAnalysis() {
        val info = analysis
        if (info == null) {
            gameRuntime.setAnalysis(GameRuntime.Analysis.EMPTY)
            return
        }
        // ★ 用真实局面：引擎只对真实局面思考，轮次与主变记谱因此天然对齐，
        //   思考细节首行「轮到 X 方」与主变第一手不会错位。
        val position = gameRuntime.state().position()
        val side = position.sideToMove()
        val toRed = if (side == Side.RED) 1 else -1
        val cp = info.score?.let { it * toRed }
        val mate = info.mate?.let { it * toRed }
        gameRuntime.setAnalysis(
            GameRuntime.Analysis(
                scoreText(cp, mate),
                verdictText(cp, mate),
                info.depth?.toString(),
                info.timeMs?.let { "$it ms" },
                info.nodes?.toString(),
                info.nps?.toString(),
                side,
                Notation.chineseVariation(position, info.pv),
                moveSource,
            )
        )
    }

    private fun scoreText(cp: Int?, mate: Int?): String? = when {
        mate != null -> "绝杀 ${if (mate > 0) "+" else "-"}${abs(mate)}"
        cp != null -> String.format(Locale.US, "%+.2f", cp / 100.0)
        else -> null
    }

    private fun verdictText(cp: Int?, mate: Int?): String? = when {
        mate != null -> if (mate > 0) "红方胜势" else "黑方胜势"
        cp == null -> null
        cp > 30 -> "红方占优"
        cp < -30 -> "黑方占优"
        else -> "均势"
    }
}
