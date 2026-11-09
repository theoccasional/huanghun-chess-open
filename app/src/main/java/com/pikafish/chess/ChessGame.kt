package com.pikafish.chess

import android.content.Context
import android.util.Log
import com.pikafish.chess.engine.CommentaryEngine
import com.pikafish.chess.engine.EcoDatabase
import com.pikafish.chess.engine.EngineManager
import com.pikafish.chess.engine.EngineWatchdog
import com.pikafish.chess.engine.LocalBookManager
import com.pikafish.chess.engine.NotationParser
import com.pikafish.chess.engine.OpeningTactics
import com.pikafish.chess.engine.WatchdogHost
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.Piece
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position
import com.pikafish.chess.model.RepetitionRule
import com.pikafish.chess.model.SetupValidator
import com.pikafish.chess.qipu.EccoNamer
import com.pikafish.chess.utils.SoundManager

/**
 * 人话解释条目（爬楼用）
 */
data class ExplanationEntry(
    val stepNumber: Int,       // 第几步（按方计数：红方第1步、黑方第1步...）
    val isHuman: Boolean,      // 人脑还是AI
    val moveNotation: String,  // 中文棋谱（如"炮二平五"）
    val text: String,          // 人话解释全文
    val boardFen: String,      // 走棋后棋盘FEN（用于悔棋时恢复对应解释）
    val color: String = "",    // 走棋方颜色（"红方"/"黑方"）
    val totalStepIndex: Int = 0 // 总步数索引（用于正确过滤显示）
)

/**
 * 棋谱旁白数据（来自 CBL 棋谱注解）
 */
data class QipuComments(
    val intro: String,          // 开局旁白（背景介绍）
    val end: String,            // 结局旁白（总评）
    val moveComments: Map<Int, String>,  // 每步旁白（总步索引，从0开始）
    val commentaryBlocks: List<com.pikafish.chess.qipu.CblDecoder.CommentaryBlock>  // 逐坨旁白（按出生顺序，含支节标记）
) {
    fun hasAny(): Boolean =
        intro.isNotEmpty() || end.isNotEmpty() || moveComments.isNotEmpty() || commentaryBlocks.isNotEmpty()
}

/**
 * 象棋游戏控制核心
 */
class ChessGame(private val context: Context) : WatchdogHost {
    companion object {
        private const val TAG = "ChessGame"
        const val MAX_EXPLANATION_HISTORY = 50  // 最多保存50条爬楼记录
        private const val MAX_CLOUD_PREFETCH = 10  // 云库预取子局面数量上限（保证预取及时完成）
        const val XQF_VAR_NODE_BUDGET = 8000  // XQF 导入全局变招展开预算（防超大书/全集 OOM）

        // ★★★ 黑方列号映射：兼容半角(1-9)与全角(１-９)数字。
        //   带花括号注释的 PGN（如 "卒３进１{-6}"）常用全角数字，缺一不可。
        private val BLACK_COL_MAP = mapOf(
            '1' to 0, '2' to 1, '3' to 2, '4' to 3, '5' to 4, '6' to 5, '7' to 6, '8' to 7, '9' to 8,
            '１' to 0, '２' to 1, '３' to 2, '４' to 3, '５' to 4, '６' to 5, '７' to 6, '８' to 7, '９' to 8
        )

        // ★★★ 黑方步数/目标列映射：兼容全角数字（"卒３进１"里的"１"）。
        private val BLACK_STEP_MAP = mapOf(
            '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9,
            '１' to 1, '２' to 2, '３' to 3, '４' to 4, '５' to 5, '６' to 6, '７' to 7, '８' to 8, '９' to 9
        )

        // ★★★ 解析黑方步数（车炮兵帅将"进N/退N"），兼容半角与全角数字
        private fun blackSteps(destChar: Char): Int? =
            BLACK_STEP_MAP[destChar] ?: destChar.digitToIntOrNull()

        // ★★★ 外置开局库"吸收力 vs 防中毒"参数 ★★★
        /** 开局谱有效半回合上限：只在前 N 个半回合用库，之后交还引擎（防库在残局误导） */
        const val BOOK_OPENING_PLY_LIMIT = 24
        /** 经验样本足够才信胜率：某着 vwin/vdraw/vlost 总和 ≥ 此值才据此判"毒着" */
        const val BOOK_GATE_MIN_GAMES = 10
        /** 毒着阈值：样本足够且胜率低于此值(30%)的库招不放行，交还引擎（防中毒） */
        const val BOOK_GATE_WIN_RATE = 0.30f

        /** ★ 引擎思考总开关自愈超时：原来 40s 太长，用户没耐心等；压到 6s */
        private const val ENGINE_THINK_TIMEOUT_MS = 6_000L
        /** ★ 分析安全超时：同样压到 6s */
        private const val ANALYZE_TIMEOUT_MS = 6_000L

        // ★★★ 看门狗抢救冷却：距上次真正重启引擎不足该窗口时，看门狗绝不二次抢救。
        //   这是打断"重启→慢思→又重启"死亡螺旋（蚂蚁转死圈）的最后防线——窗口内交给
        //   止血自愈"硬步时"先强制落一着走出棋来，而不是无休止重启。
        const val WATCHDOG_RESCUE_COOLDOWN_MS = 40_000L

        /** ★ 云库查询硬兜底时限：断网/挂起/被丢弃时，到点必切引擎搜索（保证有出口，杜绝死圈）。
         *   大于云库 3s 网络超时（网络正常时结果能在时限内落地），小于 6s 看门狗
         *   （确保兜底先于看门狗介入，避免又被踢回同一条云库路径）。 */
        private const val CLOUD_RESOLVE_MS = 4_000L

        /**
         * ★ 应用进程内共享的 ChessGame 实例。
         *   观战连线（com.yk.xiangqi.link）据此复用黄昏象棋同一套外置开局库（localBook）
         *   与云库（cloudBookClient），不再另起一套 YK 象棋的库/云渠道。
         */
        @Volatile
        @JvmStatic
        var shared: ChessGame? = null
            private set
    }

    /** 解说风格（三态：人话 → 现世报 → 关）。关=不开口，只记棋谱。 */
    enum class CommentaryStyle(val label: String) {
        STANDARD("人话"),
        KARMA("现世报"),
        OFF("解说:关")
    }

    val board = Board()
    val engine = EngineManager(context)
    val soundManager = SoundManager()

    init {
        // ★ 登记为进程内共享实例：观战连线据此复用黄昏象棋同一套外置开局库与云库。
        shared = this
    }

    // ★★★ 独立通道巡检引擎：监视红/黑引擎健康，异常时快速重接。
    // 用独立的 HandlerThread，与象棋搜索完全无关；ChessGame 只提供观测回调。
    private val watchdog = EngineWatchdog(this)
    fun startWatchdog() = watchdog.start()
    fun stopWatchdog() = watchdog.stop()

    // ── 每方独立的人脑/AI 切换 ──
    var redIsHuman: Boolean = true
    var blackIsHuman: Boolean = false

    // ── ★ 练开局模式状态 ──
    // 练开局：人对皮卡鱼，限定回合数(以人执一方满 N 手为准)，下满即锁盘；悔棋可解锁。
    @Volatile var practiceActive: Boolean = false
    // 锁盘：达到约定回合后，红黑都不许再走（悔棋解锁）
    @Volatile var practiceLocked: Boolean = false
    // 约定回合数（人执方的步数上限），8/10/12.../22
    @Volatile var practiceLimit: Int = 8
    // 人执哪一方（红先=人红，黑先=人黑）
    @Volatile var practiceUserSide: PieceColor = PieceColor.RED

    // ── ★ PK 比赛模式（红黑各一台引擎，全自动对弈）──
    /** PK 是否进行中（界面据此走 PK 终局弹窗；引擎路由由 SandboxOracle.pkMode 负责） */
    @Volatile var pkMode: Boolean = false
    /** PK 单局半回合上限：到这里仍无结果就判和，给引擎互磨一个出口 */
    private val PK_DRAW_PLIES = 400
    // PK 期间临时关掉开局库（保证"纯引擎对弈、成绩干净"），结束后原样恢复
    private var pkBookSaved = false
    private var pkPrevLocalBook = true
    private var pkPrevCloudBook = false

    // 棋盘翻转（纯视觉，不改变 playerColor）
    var boardFlipped: Boolean = false
        private set

    // 引擎设置
    // searchMode: "time" = 按秒走（到点必须走），"depth" = 按层走（到层必须走）
    var searchMode: String = "time"
    var engineTimeMs: Int = 5_000
    var engineDepth: Int = 10  // 深度模式默认10层
    var showThinkingDetails: Boolean = true
    var alwaysThinking: Boolean = false  // 后台思考开关
    /**
     * ★ 后台预想命中"秒走"的最低深度闸门。
     *   人脑想得久 → 预想已推进到深水区，命中即秒走；人脑秒走 → 预想还很浅，
     *   此时秒走会下出浅着，宁可不秒走、乖乖回前台正常搜索（引擎现算到应到的深度）。
     *   底线 10 层（与"深度模式默认10层"齐平）；用户把层数调更高时同步抬高。
     */
    private val ponderHitMinDepth: Int get() = maxOf(10, engineDepth)
    private val cloudBookClient by lazy { com.pikafish.chess.engine.CloudBookClient() }
    var cloudBookEnabled: Boolean = false  // ★ 云库开关

    // ★★ 本地外置开局库：可并行挂最多 LocalBookManager.MAX_BOOKS 本外置库
    //    引擎是预编译 .so 没暴露开局库 UCI 选项，所以书放在这一层，命中直接把这着喂给引擎（省时省算）
    // ★ 全局唯一的开局库仓：发送端界面加载的库，主界面对弈立刻就能用（同一份，不再各建一套）
    val localBook by lazy { com.pikafish.chess.engine.BookVault.manager() }
    var localBookEnabled: Boolean = true   // ★ 本地库开关
    val localBookHitNames: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * ★ 供观战连线复用：对给定 FEN 查黄昏象棋同一套云库（在线开局 + 残局一体）。
     *   连线不再另起一套 YK 象棋的云库渠道，与主棋盘同源。
     */
    fun cloudMovesFor(fen: String): List<com.pikafish.chess.engine.CloudMove> =
        runCatching { cloudBookClient.queryAllMoves(fen) }.getOrDefault(emptyList())
    // ★ 命中库着法回调（上层据此把这一着金色高亮 + 报"翻库"）
    var onBookHit: ((LocalBookManager.BookHit) -> Unit)? = null
    // ★ 云库预取去重：记录已预取过的局面FEN，避免重复请求
    private val cloudBookPrefetchedFens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ★★★ 修复：云库查询用有界线程池（原来每次走棋都 new Thread，快速对弈时线程无限堆积，
    //   网络慢时一堆线程卡 3s，态势失控）。固定 3 线程 + 有界队列，多出的请求直接丢弃。
    private val cloudBookQueryExecutor = java.util.concurrent.ThreadPoolExecutor(
        3, 3, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(8),
        java.util.concurrent.ThreadFactory { r ->
            Thread(r, "cloudbook-query").apply { isDaemon = true }
        },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
    )

    // 人话解释开关（暂时关闭，只显示纯棋谱）
    var enableExplanation: Boolean = false
    var explanationDepth: Int = 12

    // 解说风格切换
    var commentaryStyle: CommentaryStyle = CommentaryStyle.STANDARD

    // ── 棋谱旁白（CBL 注解）──
    var qipuComments: QipuComments? = null     // 当前棋谱的旁白数据
    var qipuCommentaryEnabled: Boolean = true   // 旁白开关

    // 自定义摆子模式
    var isSetupMode: Boolean = false
        private set
    var setupFirstPlayer: PieceColor = PieceColor.RED

    /** ★ 当前局面是否为「局面习题」（成批 FEN 题库里的一题）：无回合、无着法，先行方交人脑去解。
     *   仅用于状态栏显示“习题”而非“0/0 回合”，以及避免被当作坏棋谱误判。 */
    var fenPuzzleMode: Boolean = false

    var gameOver = false
    var gameResult: String = ""

    // ── 回退/前进导航 ──
    // boardSnapshots: 每一步走棋后的棋盘FEN快照
    // currentStepIndex: 当前查看的步数（0=初始，1=第1步后，...）
    private val boardSnapshots = mutableListOf<String>()
    private var currentStepIndex = 0
    private var isViewingHistory = false  // 是否正在查看历史（非最新步）

    // ── 变招分支（树状 + 标签，用于PGN/TXT导出） ──
    // 每个变招记录：从哪一步分支、被截断的走法列表、A/B/C标签，以及内部再嵌套的子变招。
    // branchStep 语义：顶层挂主线上 = 主线全局步数索引；嵌套挂父变招内 = 相对父 moves 起点的偏移。
    data class VariationBranch(
        val branchStep: Int,                 // 分支点（顶层=主线步数索引；嵌套=相对宿主 moves 偏移）
        val truncatedMoves: List<String>,    // 本变招的UCI走法
        val label: String = "",              // A/B/C 标签，空=未编号
        val subVariations: MutableList<VariationBranch> = mutableListOf()  // 嵌套子变招树
    )
    private val variationBranches = mutableListOf<VariationBranch>()

    /** XQF 导入全局“变招展开预算”。防止《全集》这类 10 万级节点大书一次性把整棵变招树物化
     * 成 Board/走法列表造成 OOM/主线程超时（表现为“导入后闪退”）。预算在每次 importXqf 开头重置，
     * 耗尽后原生侧不再展开更深的变招（主线始终完整），需要看全集可转微思播放器。 */
    private var xqfVarBudget = 0

    /** 按分支点已有变招数，生成下一个字母标签（A、B、C…）。 */
    private fun nextVariationLabel(siblings: List<VariationBranch>): String {
        val n = siblings.size
        return if (n < 26) ('A' + n).toString() else "${('A' + (n % 26)).toString()}${n / 26 + 1}"
    }

    /** 顶层变招按主线步骤分组。 */
    private fun topLevelVariationsByStep(): Map<Int, List<VariationBranch>> =
        variationBranches.groupBy { it.branchStep }

    // 初始FEN（第0步）
    private val initialFen: String get() = boardSnapshots.firstOrNull() ?: Board().let { it.reset(); it.toFen() }

    val totalSteps: Int get() = boardSnapshots.size - 1  // 减去初始状态
    val currentStep: Int get() = currentStepIndex
    val isAtLatest: Boolean get() = currentStepIndex >= boardSnapshots.size - 1
    val isAtStart: Boolean get() = currentStepIndex <= 0

    // ── 人话解释爬楼 ──
    private val explanationHistory = mutableListOf<ExplanationEntry>()

    // ── PGN 主线的注释 {…} 无损往返 ──
    // key = 该注释所标注的走法在 moveHistoryUci 中的下标（0 起，主线不含变招）。
    // 导入收集、导出写回、存档持久化，避免“导入前有注释、导入后没了”。
    private val moveComments = LinkedHashMap<Int, String>()
    fun getMoveComment(index: Int): String? = moveComments[index]
    fun getMoveComments(): Map<Int, String> = moveComments.toMap()
    fun setMoveComments(map: Map<Int, String>) { moveComments.clear(); moveComments.putAll(map) }

    /** ★ 观棋/拆谱：取当前步的可编辑注释（空串表示没写过）。 */
    fun getEditableMoveComment(): String = moveComments[currentStepIndex - 1] ?: ""

    /** ★ 观棋/拆谱：写/清当前步的注释。只改内存，点「新局→保存」时随「另存」落盘，不动任何原稿。 */
    fun setEditableMoveComment(text: String) {
        val idx = currentStepIndex - 1
        if (idx < 0) return
        val t = text.trim()
        if (t.isEmpty()) moveComments.remove(idx) else moveComments[idx] = t
    }

    /**
     * ★ 观棋/拆谱：删除当前着法及其之后的全部着法（后面的招法一次性全删）。
     *   只在内存里截断，原棋谱文件只读、不受影响；点「新局→保存」即属另存。
     * @return 实际删掉的着法数；0 表示当前在开局、无可删。
     */
    fun deleteCurrentMoveAndFollowing(): Int {
        val k = currentStepIndex            // 当前局面 = 第 k 着之后
        if (k <= 0) return 0
        val removed = moveHistoryUci.size - (k - 1)
        if (removed <= 0) return 0
        moveComments.keys.filter { it >= k - 1 }.forEach { moveComments.remove(it) }
        variationBranches.removeAll { it.branchStep >= k }
        truncatePracticeTo(k - 1)
        return removed
    }

    var onBoardChanged: (() -> Unit)? = null
    var onEngineFailed: ((String) -> Unit)? = null
    var onGameOver: ((String) -> Unit)? = null
    var onThinkingInfo: ((String) -> Unit)? = null
    // ★★★ 分析"神同步"派生通道（用户钦定：主心骨 = 对弈走子搜索）。
    //   Analysis 开启 + AI 对弈时，主心骨搜索"只想一遍"，思考细节实时分发一份给分析面板，
    //   绝不再另起炉灶跑独立深度搜索——消除"分析一遍 + 走子一遍"双烧 CPU、走子被抢线程跑掉。
    @Volatile var emitAnalysis = false
    var onAnalysisFeed: ((fen: String, d: Int, sd: Int, sc: Int, nodes: Long, nps: Long, pv: String) -> Unit)? = null
    var onAnalysisFeedResult: ((fen: String, depth: Int, sc: Int, pv: String) -> Unit)? = null
    /** ★★ AI 对弈回合交接状态机：把「谁在思考 → 带预判离场 → 下一位进场」可视化，
     *  避免双AI对弈时看不出卡在哪。每次相位变化回调一条人类可读的状态行。 */
    var onAiTurnState: ((String) -> Unit)? = null
    var onAiMove: ((Move) -> Unit)? = null
    var onEngineRestarting: (() -> Unit)? = null
    /** ★ 引擎恢复/抢救完成回调：上层收到后即时清除"异常/恢复中"残留并恢复正常状态文案 */
    var onEngineRecovered: (() -> Unit)? = null
    var onSettingsChanged: (() -> Unit)? = null
    var onSetupModeChanged: ((Boolean) -> Unit)? = null
    var onSetupError: ((String) -> Unit)? = null
    var onPlayerTypeChanged: (() -> Unit)? = null

    // 人话解释回调（爬楼模式：追加而非覆盖）
    var onExplanationAppend: ((ExplanationEntry) -> Unit)? = null
    var onExplanationHistoryChanged: (() -> Unit)? = null  // 爬楼列表变化（悔棋/前进时）
    var onAnalyzing: ((Boolean) -> Unit)? = null
    var onStepChanged: (() -> Unit)? = null  // 步数变化
    var onOpeningIdentified: ((String) -> Unit)? = null  // 开局识别回调
    var onRepetitionWarning: ((String) -> Unit)? = null  // 循环着法警告（棋例约束）

    @Volatile private var engineThinking = false
    private var pendingRetry = false

    // ★ 引擎初始化是否已完成（冷启动完成标记）：用于"恢复前台"时区分
    //   "引擎还没起（冷启动中）" 与 "引擎本来可用但后台被系统回收（需重启）"
    @Volatile private var engineInitCompleted = false

    // ── 后台思考（Ponder）状态 ──
    // 预测的对手走法（UCI格式），AI走完后根据PV预测对方会走哪一步
    private var predictedOpponentMoveUci: String = ""
    // 后台思考是否在进行中（用于上层UI显示）
    fun isPondering(): Boolean = engine.isPondering()

    // ★★★ 引擎重启中标记：防止并发多次重启
    @Volatile private var engineRestarting = false

    // ★ AI走法失败重试计数，防止无限重试
    private var aiRetryCount = 0
    private val MAX_AI_RETRY = 3

    // ★★★ 引擎"该出子却没出"的连续观察钟次数：达到阈值即强制合法应手兜底落子，
    //   保证无论哪条搜索/锁/结果链出问题，这一步棋都绝不会"永远悬空、卡死不出子"。
    private var stallEscalation = 0

    // ★ 杀棋加速追踪：当引擎报告的评分显示已找到杀棋时，后续走棋加速
    @Volatile private var inMateSequence = false
    @Volatile private var mateStepsRemaining = 0  // 剩余杀棋步数
    @Volatile private var playerTypeChanging = false

    // ── 巡检器心跳字段（识别"引擎活着但挂起不走"）──
    // 记录每方引擎最近一次"开始本次走棋搜索"的时间戳；若该方引擎轮值、
    // 正在搜索、且距搜索开始已远超步时仍未落子，即视为"挂起"，由巡检器抢救。
    @Volatile private var engineSearchStartByColor = LongArray(2) { -1L }

    // ★ 上次真正重启（看门狗抢救）的时间戳，用于抢救冷却判定
    @Volatile private var lastWatchdogRescueAt: Long = 0L

    // ★ 走棋代际令牌：每次走棋递增，用于作废过期的异步解说回调
    // 防止"人脑走完→异步评价中→又走了下一步→旧评价迟到混入"的乱序问题
    @Volatile private var moveGeneration = 0

    // ★ 外部走法注入标志：允许绕过"必须人脑回合才能走棋"的检查
    // 用于背谱自动应子：轮谱方由谱自动走掉一着
    @Volatile private var allowExternalMove = false

    // ★ 解惑分析结果回调（独立通道，不混入主讲解列表）
    var onWhatIfResult: ((String) -> Unit)? = null

    // 走棋计数器（用于爬楼编号）
    private var moveCount = 0
    // 按方计数：红方走第几步、黑方走第几步（各自从1开始）
    private var redMoveCount = 0
    private var blackMoveCount = 0

    // ── 劣势追踪 ──
    // 记录首次失子失势的时刻，锁定追溯，不会跳脱
    private var currentDisadvantage: CommentaryEngine.DisadvantageInfo? = null

    // ── 正在分析安全超时 ──
    private var analyzingTimeoutHandler: android.os.Handler? = null

    // ── ★★ 引擎思考总开关安全超时（硬步时 + 自愈兜底） ──
    // 走棋主路径（startEngineSearch / executeCloudMove / executePonderHitMove）
    // 设 engineThinking=true 后，若任一异常路径漏复位，开关永久卡 true →"点棋子不动"。
    // 这里用独立兜底超时强制复位，保证引擎思考总开关绝不死锁。
    //
    // ★ 硬步时逻辑（对应"6秒必走"）：
    //  - 引擎正在搜索：到点立刻 stopSearch()，引擎内部有 pv 兜底/首合法招兜底，必然落子；
    //    绝不等待或重启正常引擎。
    //  - 引擎没在搜索但开关挂着（云库查询中/状态残留）：复位开关并重新触发走棋，
    //    此时不该重启引擎（引擎是健康的，盲目重启反而更卡）。
    //  - 只有该方引擎"应就绪却未就绪 / 真无响应"才交给巡检抢救重启。
    // ★★★ 单时钟收敛（代际自证）：thinkingEpoch 每发起一手搜索就 +1；
    //   自愈钟 postDelayed 时捕获当时的 epoch，触发时若 epoch 已变 → 旧钟作废直接退出，
    //   绝不允许"上一手的钟"误杀"这一手的搜索"（多时钟踩踏的实锤根因）。
    @Volatile private var thinkingEpoch = 0
    private var thinkingTimeoutHandler: android.os.Handler? = null
    private val thinkingTimeoutRunnable = Runnable {
        val nowEngine = engine
        val searching = nowEngine.isSearching()
        val stuckColor = if (board != null) board.currentPlayer else null
        val ready = stuckColor != null && nowEngine.isEngineReadyFor(stuckColor)

        if (searching) {
            // ★ 引擎在正常搜索但超出了步时 → 硬步时：强制收一着，立刻落子。
            //   ★ 深度模式：看门狗预算已放大到覆盖引擎自身死线（目标层×1000ms+10s 封顶
            //     180s + 8s 宽限 + 5s 余量），正常对弈引擎到层自然 bestmove、根本走不到
            //     这里；能走到这里说明引擎已超自身死线仍未返回（真卡死/异常），收一着
            //     PV 兜底是正确的最后出口，绝不会"提前掐停"。
            Log.w(TAG, "★ 步时到点，强制收一着落子（硬步时）")
            try { nowEngine.stopSearch() } catch (_: Exception) {}
            // ★★★ 单时钟收敛：stop 后引擎 doSearch 必在 300ms 内以 PV 兜底返回 bestmove，
            //   onResult 自然走落子路径。这里**只**设一个 1s 观察钟：若 onResult 因代际
            //   判废/协程取消被丢弃（极端情况），1s 后仍无人落子，才"安全续走"复拉一着。
            //   ★ 不再立即 ensureEngineDriven：否则 stop 的 onResult 还在途中就又发起
            //   一次重复搜索 → 双驱动 → 引擎同一局面搜两遍 → 又慢又乱（旧卡顿元凶之一）。
            gameHandler.removeCallbacks(thinkingTailRunnable)
            gameHandler.postDelayed(thinkingTailRunnable, 1000L)
            return@Runnable
        } else if (engineThinking) {
            // ★ 开关挂着但引擎没在搜索：云库查询中 / 状态残留。复位并重新走棋。
            Log.w(TAG, "★ 引擎思考开关超时残留(${ENGINE_THINK_TIMEOUT_MS}ms)，复位后重新走棋")
            engineThinking = false
            onAnalyzing?.invoke(false)
            if (stuckColor != null && !gameOver && !isCurrentPlayerHuman() && ready) {
                gameHandler.post {
                    if (!gameOver && isCurrentPlayerEngine()) requestEngineMove()
                }
            }
            return@Runnable
        } else {
            Log.d(TAG, "★ 引擎思考计时器残留，已复位（无卡死）")
        }
        engineThinking = false
        onAnalyzing?.invoke(false)
    }

    /**
     * ★★★ 硬步时之后的"观察钟"：stop 已发，引擎 doSearch 的 onResult 正常情况下
     *   300ms 内就会落子。若 1s 后仍无人落子（onResult 被代际判废/协程取消等极端情况），
     *   说明这一步掉进了"引擎回合空转、无人续走"的闷死死角 → 安全续走复拉一着。
     *   ★ 只在该方确实仍轮到引擎、对局未结束时才续，幂等（requestEngineMove 有防重入）。
     */
    private val thinkingTailRunnable = Runnable {
        val stuckColor = if (board != null) board.currentPlayer else null
        if (stuckColor == null || gameOver || isSetupMode) return@Runnable
        if (isCurrentPlayerHuman()) return@Runnable
        if (!engine.isEngineReadyFor(stuckColor)) {
            // 引擎进程已死：交给重启链（不在搜索却超时 → 重启恢复）
            pendingRetry = true
            onEngineRestarting?.invoke()
            restartEngineIfNeeded(stuckColor)
            return@Runnable
        }
        Log.w(TAG, "★ 硬步时观察钟到期仍无落子，复位思考开关后安全续走复拉一着")
        engineThinking = false
        onAnalyzing?.invoke(false)
        // ★★★ 观察钟不留"无限自愈循环"：连续多次"复拉仍无落子"即判定引擎结果链路已不可靠，
        //   强制用最稳合法应手兜底落子——宁可走一步合法棋，也绝不把这一步棋永久卡死。
        stallEscalation++
        if (stallEscalation >= 2) {
            stallEscalation = 0
            Log.e(TAG, "★★★ 多次复拉仍无落子，强制最稳合法应手兜底落子（绝不停摆）")
            val bbm = Board()
            bbm.loadFromFen(board.toFen())
            playEngineFallbackMove(stuckColor, bbm)
            return@Runnable
        }
        ensureEngineDriven(stuckColor)
    }

    /**
     * ★ 统一设置"引擎思考开关"，带自愈兜底超时。
     *  - 时间模式：兜底 = 用户步时 + 800ms 温和余量（到点强制收着落子 = 硬步时）。
     *  - 深度模式：固定 8s（深度搜索本就可能略超，给够时间）。
     * 确保任何情况下开关都不会卡死。
     */
    private fun setEngineThinking(thinking: Boolean): Boolean {
        if (!thinking) {
            // ★★★ 单时钟收敛：复位开关同时作废旧钟（epoch+1），旧钟即使残留触发也会自证作废
            thinkingEpoch++
            thinkingTimeoutHandler?.removeCallbacks(thinkingTimeoutRunnable)
            engineThinking = false
            return false
        }
        if (thinkingTimeoutHandler == null) {
            thinkingTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        }
        thinkingTimeoutHandler?.removeCallbacks(thinkingTimeoutRunnable)
        // ★★★ 单时钟收敛：每一手搜索独占一个自愈钟（epoch+1，旧钟作废）
        thinkingEpoch++
        val myEpoch = thinkingEpoch
        engineThinking = true
        // ★ 硬步时：兜底超时与用户步时联动，而不是固定死值。
        //   ★★★ 防死圈关键：深度模式必须覆盖引擎自身的搜索预算（engineDepth×600）
        //   再加余量，绝不能是固定 8s——否则"三线(MultiPV=3)齐头并进本来就慢"时，
        //   会在引擎还没滚到目标层之前就提前 stop/被看门狗重启，永远落不了子（蚂蚁转死圈）。
        //   到点若引擎还在搜，stopSearch() 会用 pv/首合法招兜底强制落一着（这就是"出口"）；
        //   所以预算放大不会卡死，只会让引擎有完整时机滚到目标层再走。
        val budget = if (searchMode == "time") {
            (engineTimeMs.coerceAtLeast(100)) + 800L
        } else {
            // ★★★ 深度模式看门狗预算 = 引擎侧唯一预算(目标层×1000ms+10s，15s~180s封顶)
            //   + 引擎宽限8s + 5s 余量。必须 ≥ 引擎自身硬死线，否则看门狗会在引擎滚到
            //   目标层之前就掐停/复拉 → "设17层永远只算到16层、还反复从浅层绕弯重算"
            //   （用户核心顽疾的根因之一）。旧预算"目标层×400ms 封顶8s +2s"比引擎死线
            //   短一半，到点必走"复位+重新走棋"分支 → 旧搜索作废、新搜索从浅层重算，
            //   温哈希让前 10~12 层秒过 → 界面看起来"从10/12层又开始算到16层"，循环往复。
            //   ★ 引擎自身到层自然返回 bestmove，预算内绝不强停；只有引擎真卡死超预算，
            //     stopSearch() 才以已搜出的 PV 兜底落一着（这是唯一出口，不会卡死）。
            (engineDepth.coerceAtLeast(1).toLong() * 1000L + 10000L).coerceIn(15000L, 180000L) + 13000L
        }
        thinkingTimeoutHandler?.postDelayed(Runnable {
            if (myEpoch == thinkingEpoch) thinkingTimeoutRunnable.run() else Log.d(TAG, "★ 自愈钟已换代，跳过")
        }, budget)
        return true
    }
    private val analyzingTimeoutRunnable = Runnable {
        Log.w(TAG, "★ 正在分析超时，强制清除分析状态（自愈兜底）")
        // ★★★ 自愈兜底：不只清分析态，连带强制复位"引擎思考总开关"。
        //   若某条路径即使有 finally 仍因极端并发/引擎挂起漏复位，
        //   engineThinking 会永久卡 true →"点棋子不动、不催不走、不报错"。
        //   这里强行放行，让后续 requestEngineMove() 能再次走棋。这是
        //   "怎么戳都不卡"闭环的最后一道防线。
        engineThinking = false
        onAnalyzing?.invoke(false)
    }

    // ── 终局总结数据 ──
    private val mistakeSteps = mutableListOf<Triple<Int, String, Pair<CommentaryEngine.Quality, Int>>>()

    // ── ECO开局识别 ──
    private val moveHistoryUci = mutableListOf<String>()  // 走法历史（UCI格式）
    private var currentOpeningName: String? = null  // 当前识别到的开局名称
    private var currentOpeningEntry: EcoDatabase.EcoEntry? = null  // 当前匹配到的开局条目（含分类，供注解/战略要点使用）
    private var openingAnnounced = false  // 是否已播报过开局

    // ── 循环着法判定（棋例约束）──
    private val repetitionRule = RepetitionRule()
    private var warnedViolator: PieceColor? = null  // 已被警告必须变着的一方

    val isEngineReady: Boolean get() = engine.isEngineReadyFor(board.currentPlayer)

    private val gameHandler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        soundManager.loadSounds(context)

        engine.onEngineRestarted = {
            Log.d(TAG, "Engine restarted, pendingRetry=$pendingRetry")
            if (pendingRetry && !engineThinking && !gameOver) {
                pendingRetry = false
                if (!isSetupMode && !isCurrentPlayerHuman()) {
                    requestEngineMove()
                } else {
                    // ★ 引擎恢复后非AI回合，也要刷新状态栏
                    onBoardChanged?.invoke()
                }
            } else {
                // ★ 引擎恢复但无需重试，仍需刷新状态栏（清除"引擎重启中…"）
                onBoardChanged?.invoke()
            }
        }

        // ★ 后台多线预想信息回调（深度增长时触发，已在引擎层节流）
        //   把多条预案（①预测人脑走法 → 我的应着）以三行 ①②③ 展示，带上层数/评分
        engine.onPonderInfo = { depth, score, pv, lines ->
            if (showThinkingDetails) {
                // 预测第一手（①）时记录为命中基准；未命中时用本法案统一兜底
                if (predictedOpponentMoveUci.isEmpty() && lines.isNotEmpty()) {
                    predictedOpponentMoveUci = lines[0].predictedMove
                }
                val info = formatPonderThinking(lines, depth)
                onThinkingInfo?.invoke(info)
            }
        }
    }

    /** ★ 引擎初始化成功回调：标记"引擎已可用"，供恢复前台逻辑区分冷启动/引擎被杀 */
    fun onEngineStartedSuccess() {
        engineInitCompleted = true
    }

    // ── 哈希/线程设置 ──

    fun updateEngineSettings(hash: Int? = null, threads: Int? = null) {
        engine.updateSettings(hash, threads)
        onSettingsChanged?.invoke()
    }

    val currentHash: Int get() = engine.hashSize
    val currentThreads: Int get() = engine.threadCount

    // ── 判断当前局面轮到谁 ──

    fun isCurrentPlayerHuman(): Boolean {
        return when (board.currentPlayer) {
            PieceColor.RED -> redIsHuman
            PieceColor.BLACK -> blackIsHuman
        }
    }

    fun isCurrentPlayerEngine(): Boolean {
        // ★ 修复：移除 !isViewingHistory 检查，允许在查看历史时AI也能走棋
        // 就绪判断改成"当前走子方"对应引擎的就绪状态（红方AI→红方引擎，黑方AI→黑方引擎）
        return !isSetupMode && !gameOver && engine.isEngineReadyFor(board.currentPlayer) && !isCurrentPlayerHuman()
    }

    // ── 对局 ──

    fun newGame() {
        board.reset()
        gameOver = false
        gameResult = ""
        engineThinking = false
        pendingRetry = false
        aiRetryCount = 0
        moveCount = 0
        redMoveCount = 0
        blackMoveCount = 0
        isViewingHistory = false

        // 初始化快照和爬楼
        boardSnapshots.clear()
        boardSnapshots.add(board.toFen())
        currentStepIndex = 0
        explanationHistory.clear()
        moveComments.clear()
        moveHistoryUci.clear()
        currentOpeningName = null
        openingAnnounced = false
        currentDisadvantage = null  // 清除劣势追踪
        variationBranches.clear()   // 清除变招分支
        mistakeSteps.clear()        // 清除终局总结失误记录
        warnedViolator = null       // 清除循环着法警告
        inMateSequence = false     // 清除杀棋加速模式
        mateStepsRemaining = 0
        qipuComments = null        // 清除棋谱旁白数据
        fenPuzzleMode = false      // ★ 新开局不再是习题局面
        pendingPvQueue.clear()     // ★ 清除PV链待走序列（新开局不继承旧主变）
        setAnalyzing(false)  // 清除分析状态

        if (engine.isEngineReady()) {
            engine.stopSearch()
            engine.stopPonder()
            engine.newGame()
        }

        soundManager.play("begin")
        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()

        // ★ 新开局清空云库预取记录，重新预取
        cloudBookPrefetchedFens.clear()
        cloudBookClient.clearCache()
        maybePrefetchCloudBook()

        if (!isCurrentPlayerHuman() && engine.isEngineReady()) {
            requestEngineMove()
        }
    }

    /**
     * ★★ PK 比赛：开一局红黑引擎全自动对弈。
     *   - 红黑都拨成 AI（全自动），关掉开局库（纯引擎对弈，成绩干净）；
     *   - 从标准开局重新摆盘、走子；
     *   - 引擎路由由 SandboxOracle.pkMode 负责（红走红引擎、黑走黑引擎）。
     * 调用前请确保 SandboxOracle.enterPk(...) 已就绪。
     */
    fun startPkMatch() {
        pkMode = true
        if (!pkBookSaved) {
            pkPrevLocalBook = localBookEnabled
            pkPrevCloudBook = cloudBookEnabled
            pkBookSaved = true
        }
        localBookEnabled = false
        cloudBookEnabled = false
        practiceActive = false
        practiceLocked = false
        isSetupMode = false
        redIsHuman = false
        blackIsHuman = false
        newGame()
    }

    /** ★ PK 比赛：收摊（退出 PK，恢复开局库设置与常态标记）。 */
    fun stopPkMatch() {
        pkMode = false
        if (pkBookSaved) {
            localBookEnabled = pkPrevLocalBook
            cloudBookEnabled = pkPrevCloudBook
            pkBookSaved = false
        }
    }

    /**
     * 从给定局面(FEN)+走法序列重建整局（供「加载已保存复盘」使用）。
     * 与 newGame 的清理范围一致，但不触发引擎自动走棋；走法用 unchecked 重放，
     * 最后停在最新一步并重建 boardSnapshots/moveHistoryUci，供复盘跳转试走。
     */
    fun loadLineFromFen(fen: String, ucis: List<String>) {
        gameOver = false
        gameResult = ""
        engineThinking = false
        pendingRetry = false
        aiRetryCount = 0
        isViewingHistory = false

        boardSnapshots.clear()
        moveHistoryUci.clear()
        explanationHistory.clear()
        moveComments.clear()
        variationBranches.clear()
        mistakeSteps.clear()
        warnedViolator = null
        inMateSequence = false
        mateStepsRemaining = 0
        qipuComments = null
        currentDisadvantage = null
        pendingPvQueue.clear()     // ★ 清除PV链待走序列（载入棋谱不继承旧主变）
        setAnalyzing(false)

        if (engine.isEngineReady()) {
            engine.stopSearch()
            engine.stopPonder()
            engine.newGame()
        }

        // 重建快照：FEN 起点 + 逐 UCI 重放（unchecked，绕过走棋守卫/引擎交互）
        val replay = Board().apply { loadFromFen(fen) }
        boardSnapshots.add(replay.toFen())
        moveCount = 0; redMoveCount = 0; blackMoveCount = 0
        for (uci in ucis) {
            val m = Move.fromUci(uci, replay)
            if (m == null) break
            replay.makeMoveUnchecked(m)
            boardSnapshots.add(replay.toFen())
            moveHistoryUci.add(uci)
            moveCount++
            redMoveCount = (moveCount + 1) / 2
            blackMoveCount = moveCount / 2
        }
        currentStepIndex = boardSnapshots.size - 1
        board.loadFromFen(boardSnapshots[currentStepIndex])

        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()
    }

    fun makeMove(move: Move): Boolean {
        if (gameOver) {
            // 游戏结束后继续走棋：重置游戏状态，相当于"加赛"
            gameOver = false
            gameResult = ""
        }
        if (engineThinking) return false
        if (isSetupMode) return false
        // ★ 练开局锁盘：下满约定回合后禁走（悔棋解锁前不再落子）
        if (practiceLocked) return false

        // 必须是人脑的回合才能走棋（背谱自动应子等外部注入除外）
        if (!isCurrentPlayerHuman() && !allowExternalMove) return false

        // ── 变招支持：如果正在查看历史（非最新步），走棋即创建变招分支 ──
        if (isViewingHistory) {
            // 保存被截断的走法作为变招分支
            val truncatedMoves = mutableListOf<String>()
            for (i in currentStepIndex + 1 until boardSnapshots.size) {
                val prevFen = boardSnapshots[i - 1]
                val curFen = boardSnapshots[i]
                val tempBoard = Board()
                tempBoard.loadFromFen(prevFen)
                val m = detectMove(tempBoard, curFen)
                if (m != null) truncatedMoves.add(m.toUci())
            }
            if (truncatedMoves.isNotEmpty()) {
                val siblings = variationBranches.filter { it.branchStep == currentStepIndex }
                // ★ 被截断的旧主线尾巴上原本挂着的变着 → 归入"旧主线变着"的嵌套子树，一个都不丢。
                //   旧版只把尾巴走法存成一条变着，尾巴上的其它变招（如第30手的另一种走法）
                //   因 branchStep 超出新主线长度而被整体丢弃 —— 这正是"主界面走出花来也只记一根筋"。
                val oldTailVars = variationBranches.filter { it.branchStep > currentStepIndex }.toMutableList()
                val tailSubs = mutableListOf<VariationBranch>()
                for (tv in oldTailVars) {
                    val rel = tv.branchStep - currentStepIndex   // 相对尾巴走法串的偏移
                    if (rel in 1..truncatedMoves.size) {
                        tailSubs.add(VariationBranch(rel, tv.truncatedMoves, tv.label, tv.subVariations.toMutableList()))
                    }
                }
                variationBranches.removeAll(oldTailVars)
                variationBranches.add(
                    VariationBranch(currentStepIndex, truncatedMoves, nextVariationLabel(siblings), tailSubs)
                )
            }
            // 截断快照列表
            while (boardSnapshots.size > currentStepIndex + 1) {
                boardSnapshots.removeAt(boardSnapshots.size - 1)
            }
            // 截断解释历史
            explanationHistory.removeAll { it.totalStepIndex > currentStepIndex }
            // 截断走法历史
            while (moveHistoryUci.size > currentStepIndex) {
                moveHistoryUci.removeAt(moveHistoryUci.size - 1)
            }
            // ★ 重新计算走棋计数（截断后必须重算，否则步数编号错误）
            moveCount = moveHistoryUci.size
            redMoveCount = 0
            blackMoveCount = 0
            val countBoard = Board()
            countBoard.loadFromFen(initialFen)
            for (uci in moveHistoryUci) {
                val m = Move.fromUci(uci, countBoard)
                if (m != null) {
                    countBoard.makeMoveUnchecked(m)
                    if (m.piece.color == PieceColor.RED) redMoveCount++ else blackMoveCount++
                }
            }
            isViewingHistory = false
        }

        // 保存走棋前的棋盘快照（用于人话评价）
        val boardBeforeMove = Board()
        boardBeforeMove.loadFromFen(board.toFen())
        val humanMoveUci = move.toUci()
        val humanNotation = CommentaryEngine.uciToChineseNotation(humanMoveUci, boardBeforeMove)

        if (!board.makeMove(move)) return false
        // ★ 子力完整性自愈：落子后必查——该是多少就是多少，超限即回退，绝不可能越下越多
        if (!enforcePieceIntegrity()) return false

        // ★ 走棋代际令牌递增：作废所有旧异步解说回调
        moveGeneration++

        // ★ 音效：安全吃子检测——只用棋盘快照，验证颜色，不用 move.captured（可能过期）
        val moverPiece = boardBeforeMove.getPiece(move.from) ?: move.piece
        val actualCaptured = boardBeforeMove.getPiece(move.to)
        val isCapture = actualCaptured != null && actualCaptured.color != moverPiece.color
        if (isCapture) soundManager.play("eat")
        else soundManager.play("move")
        Log.d(TAG, "音效: move=${move.toUci()}, captured=$actualCaptured, isCapture=$isCapture")

        if (board.isInCheck(board.currentPlayer)) {
            soundManager.play("check")
        }

        // 记录快照
        moveCount++
        // 按方计数
        val moverColor = move.piece.color
        if (moverColor == PieceColor.RED) redMoveCount++ else blackMoveCount++
        boardSnapshots.add(board.toFen())
        currentStepIndex = boardSnapshots.size - 1

        // 记录走法用于ECO开局识别
        moveHistoryUci.add(humanMoveUci)
        identifyOpening()
        // ★ 练开局：人执一方下满约定手数 → 立即锁盘（红黑都不许再续走，悔棋可解锁）
        if (practiceActive) refreshPracticeLock()

        // ★ 始终记录棋谱（解说文字由下方按风格异步生成，若关则只留纯棋谱）
        val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
        val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
        recordMoveEntry(humanNotation, colorName, sideStep, true)

        // ★ AI解说：按当前解说风格（人话/现世报/关）异步说这一句。
        //   只读评估人脑自家引擎，引擎忙时自动跳过，绝不干预下方 AI 走棋流程。
        periodMoveCommentary(boardBeforeMove, humanMoveUci, humanNotation, moverColor)

        onBoardChanged?.invoke()
        onStepChanged?.invoke()

        if (checkGameOver()) return true

        // ── 循环着法判定（棋例约束）──
        checkRepetition()
        if (gameOver) return true

        // ★ 人话解说暂时关闭：不再调用 evaluateHumanMove* 函数
        if (isCurrentPlayerEngine()) {
            // ── 后台多线预想 命中检测 ──
            // 后台在"人脑待走"局面开 MultiPV 推出多条预案（预测人脑可能走法 + 预演应着）。
            // 若人脑真走了预案中的某一步，就直接用那条预案已预演好的应着秒出；
            // 只赌一步命中率低，多线（① ② ③）大大提升"人脑一走完我噌地就走"的概率。
            var ponderHit = false
            if (engine.isPondering()) {
                val respondEngineColor = board.currentPlayer  // 人已走完，当前轮到 AI
                // ★★ 预想住在"人脑一方的空闲引擎"上（与应手引擎分家），查预案也去那台查
                val ponderHostColor = respondEngineColor.opponent()
                // ★ 查遍所有预案：人脑这步棋是预案预测的哪一步 → 取该线的预演应着
                val respUci = engine.respondToPredictedMove(ponderHostColor, humanMoveUci)
                val respLine = engine.ponderLineForPredictedMove(ponderHostColor, humanMoveUci)

                if (respUci.isNotEmpty() && respLine != null && respLine.depth >= ponderHitMinDepth) {
                    val respMove = Move.fromUci(respUci, board)
                    if (respMove != null) {
                        val legalMoves = board.getLegalMoves(board.currentPlayer)
                        val isLegal = legalMoves.any { it.from == respMove.from && it.to == respMove.to }
                        if (isLegal) {
                            // ★ 命中成功！直接使用该预案预演好的应着（秒走）
                            Log.d(TAG, "★ 后台多线命中！人脑走 $humanMoveUci，秒出应着 $respUci (深度=${respLine.depth}, 评分=${respLine.score})")
                            engine.stopPonder()
                            ponderHit = true
                            gameHandler.post {
                                executePonderHitMove(respMove, respLine.depth, respLine.score)
                            }
                        }
                    }
                }

                // 不管命中与否，都停止后台思考并清除预测
                if (!ponderHit) {
                    Log.d(TAG, "★ 后台多线未命中：人脑走 $humanMoveUci，重新搜索")
                    engine.stopPonder()
                    predictedOpponentMoveUci = ""
                }
            }

            if (!ponderHit) {
                requestEngineMove()
            }
        }

        return true
    }

    /**
     * ★ 外部走法注入 — 绕过"必须人脑回合才能走棋"的检查，直接执行走法。
     * 用于背谱自动应子：轮谱方由谱自动走掉一着。
     *
     * @param uci UCI格式走法，如 "e2e4"
     * @return 是否执行成功
     */
    fun makeExternalMove(uci: String): Boolean {
        if (gameOver) {
            Log.w(TAG, "★ 外部走法失败: 对局已结束")
            return false
        }
        if (engineThinking) {
            Log.w(TAG, "★ 外部走法失败: 引擎正在思考")
            return false
        }
        if (isSetupMode) {
            Log.w(TAG, "★ 外部走法失败: 摆子模式")
            return false
        }

        val move = Move.fromUci(uci, board)
        if (move == null) {
            Log.e(TAG, "★ 外部走法失败: 无法解析UCI=$uci, FEN=${board.toFen()}")
            return false
        }

        // 验证走法合法性
        val legalMoves = board.getLegalMoves(board.currentPlayer)
        val isLegal = legalMoves.any { it.from == move.from && it.to == move.to }
        if (!isLegal) {
            Log.e(TAG, "★ 外部走法不合法: $uci, 当前方=${board.currentPlayer}, FEN=${board.toFen()}")
            Log.e(TAG, "★ 合法走法: ${legalMoves.take(5).map { it.toUci() }}")
            return false
        }

        Log.i(TAG, "★ 执行外部走法: $uci (${move.piece.type} ${move.from.toUci()}→${move.to.toUci()})")

        // 设置标志，允许走棋
        allowExternalMove = true
        try {
            val result = makeMove(move)
            if (result) {
                Log.i(TAG, "★ 外部走法执行成功: $uci")
            } else {
                Log.e(TAG, "★ 外部走法执行失败: $uci (makeMove返回false)")
            }
            return result
        } finally {
            allowExternalMove = false
        }
    }

    // ── 回退/前进导航 ──

    /**
     * 回退一步。如果当前在查看历史，则回退到更早的步；如果在最新步，则回退一步。
     */
    fun goBack(): Boolean {
        if (currentStepIndex <= 0) return false
        if (gameOver) {
            // 对局结束后回退：直接恢复局面
            gameOver = false
            gameResult = ""
        }

        engine.stopSearch()
        engine.stopPonder()
        engineThinking = false
        pendingRetry = false
        warnedViolator = null  // 回退时清除循环着法警告

        currentStepIndex--
        board.loadFromFen(boardSnapshots[currentStepIndex])
        isViewingHistory = currentStepIndex < boardSnapshots.size - 1

        soundManager.play("undo")
        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()
        return true
    }

    /**
     * 前进一步
     */
    fun goForward(): Boolean {
        if (currentStepIndex >= boardSnapshots.size - 1) return false

        engine.stopSearch()
        engine.stopPonder()
        engineThinking = false
        pendingRetry = false

        currentStepIndex++
        board.loadFromFen(boardSnapshots[currentStepIndex])
        isViewingHistory = currentStepIndex < boardSnapshots.size - 1

        soundManager.play("move")
        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()
        return true
    }

    /**
     * 跳到起始局面
     */
    fun goToStart(): Boolean {
        if (currentStepIndex <= 0) return false
        engine.stopSearch()
        engine.stopPonder()
        engineThinking = false
        pendingRetry = false

        currentStepIndex = 0
        board.loadFromFen(boardSnapshots[0])
        isViewingHistory = boardSnapshots.size > 1

        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()
        return true
    }

    /**
     * 跳到最新局面
     */
    fun goToEnd(): Boolean {
        if (currentStepIndex >= boardSnapshots.size - 1) return false
        engine.stopSearch()
        engine.stopPonder()
        engineThinking = false
        pendingRetry = false

        currentStepIndex = boardSnapshots.size - 1
        board.loadFromFen(boardSnapshots[currentStepIndex])
        isViewingHistory = false

        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()
        return true
    }

    /**
     * 直接跳到指定的半回合下标位置（0=开局）。用于复盘"错着"热链跳转：
     * 跳到该着走之前的局面，让用户原地改走建议着法、与后续会员比较。
     */
    fun goToStep(targetPly: Int): Boolean {
        val target = targetPly.coerceIn(0, boardSnapshots.size - 1)
        if (target == currentStepIndex) return false
        engine.stopSearch()
        engine.stopPonder()
        engineThinking = false
        pendingRetry = false
        warnedViolator = null

        currentStepIndex = target
        board.loadFromFen(boardSnapshots[currentStepIndex])
        isViewingHistory = currentStepIndex < boardSnapshots.size - 1

        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()
        return true
    }

    // ═══ 练开局（Practice Opening）辅助 ═══

    /** 人执一方已走的手数（红先=红方，黑先=黑方；标准开局红在第0半着） */
    fun practiceUserMoveCount(): Int {
        val total = moveHistoryUci.size
        return if (practiceUserSide == PieceColor.RED) (total + 1) / 2 else total / 2
    }

    /** 是否已达到约定回合（可复盘） */
    fun isPracticeComplete(): Boolean = practiceActive && practiceUserMoveCount() >= practiceLimit

    /** 依据人执一方手数刷新锁盘状态；返回新状态 */
    fun refreshPracticeLock(): Boolean {
        practiceLocked = practiceActive && isPracticeComplete()
        // ★ 练开局达成：人脑对人脑彻底停摆；未达成：人执一方=人脑、对方=引擎。
        //   即使有人手动把"机/人"按钮改来改去，下一手落子/状态刷新都会按练习契约重新校准，
        //   顶栏"开局练习已结束"的判定（isPracticeComplete 权威计算）也照样生效。
        if (practiceActive) {
            if (practiceLocked) {
                redIsHuman = true
                blackIsHuman = true
            } else {
                redIsHuman = practiceUserSide == PieceColor.RED
                blackIsHuman = practiceUserSide == PieceColor.BLACK
            }
        }
        return practiceLocked
    }

    /**
     * 练开局悔棋：从尾部移除走法，直到「轮到人走 且 未达约定回合」为止。
     * @return 实际撤掉的半着手数
     */
    fun practiceUndoUntilUnlocked(): Int {
        if (!practiceActive || moveHistoryUci.isEmpty()) return 0
        var keep = moveHistoryUci.size
        while (keep > 0) {
            val userTurnNow = if (keep % 2 == 0) practiceUserSide == PieceColor.RED else practiceUserSide == PieceColor.BLACK
            val um = if (practiceUserSide == PieceColor.RED) (keep + 1) / 2 else keep / 2
            if (userTurnNow && um < practiceLimit) break
            keep--
        }
        if (keep >= moveHistoryUci.size) return 0
        val removed = moveHistoryUci.size - keep
        truncatePracticeTo(keep)
        return removed
    }

    /** 截断走法/快照/解说至 keepPlies，并重建棋盘与计数 */
    private fun truncatePracticeTo(keepPlies: Int) {
        try { engine.stopSearch() } catch (_: Exception) {}
        try { engine.stopPonder() } catch (_: Exception) {}
        engineThinking = false
        pendingRetry = false
        var k = keepPlies.coerceIn(0, moveHistoryUci.size)
        if (k > boardSnapshots.size - 1) k = boardSnapshots.size - 1
        while (moveHistoryUci.size > k) moveHistoryUci.removeAt(moveHistoryUci.size - 1)
        while (boardSnapshots.size > k + 1) boardSnapshots.removeAt(boardSnapshots.size - 1)
        explanationHistory.removeAll { it.totalStepIndex > k }
        moveCount = moveHistoryUci.size
        redMoveCount = 0
        blackMoveCount = 0
        val cb = Board().apply { loadFromFen(initialFen) }
        for (uci in moveHistoryUci) {
            val m = Move.fromUci(uci, cb)
            if (m != null) {
                cb.makeMoveUnchecked(m)
                if (m.piece.color == PieceColor.RED) redMoveCount++ else blackMoveCount++
            }
        }
        board.loadFromFen(cb.toFen())
        currentStepIndex = boardSnapshots.size - 1
        isViewingHistory = false
        gameOver = false
        gameResult = ""
        refreshPracticeLock()
        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()
    }

    /**
     * 快速跳过N步（正数=前进，负数=后退）
     */
    fun skipSteps(n: Int): Boolean {
        val target = (currentStepIndex + n).coerceIn(0, boardSnapshots.size - 1)
        if (target == currentStepIndex) return false

        engine.stopSearch()
        engine.stopPonder()
        engineThinking = false
        pendingRetry = false

        currentStepIndex = target
        board.loadFromFen(boardSnapshots[currentStepIndex])
        isViewingHistory = currentStepIndex < boardSnapshots.size - 1

        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()
        return true
    }

    /**
     * 获取当前步对应的解说列表（只显示到当前步）
     * ★ 线程安全 + 严格过滤+排序
     */
    fun getVisibleExplanations(): List<ExplanationEntry> {
        synchronized(explanationLock) {
            return explanationHistory
                .filter { it.totalStepIndex <= currentStepIndex && it.color != "假设" && it.color.isNotEmpty() }
                .sortedBy { it.totalStepIndex }
                .toList()
        }
    }

    /** 获取最后一条解说文字（用于盘内浮层讲解） */
    fun getLastExplanation(): String? {
        val visible = explanationHistory.filter { it.totalStepIndex <= currentStepIndex }
        return visible.lastOrNull()?.text
    }

    /**
     * ★ 兜底重建指定总步索引的中文棋谱。
     * 当某一步的解说条目缺失（如对局中断/并发丢失）时，从 moveHistoryUci 重放重建，
     * 彻底杜绝界面误显示"（无）"。
     * @param totalStepIndex 总步索引（1 = 第1个半着，2 = 第2个半着，…）
     */
    fun getMoveNotationByStep(totalStepIndex: Int): String? {
        if (totalStepIndex < 1 || totalStepIndex > moveHistoryUci.size) return null
        val idx = totalStepIndex - 1
        val b = Board().apply { loadFromFen(initialFen) }
        try {
            for (i in 0 until idx) {
                val pm = Move.fromUci(moveHistoryUci[i], b) ?: continue
                b.makeMoveUnchecked(pm)
            }
            val uci = moveHistoryUci[idx]
            val m = Move.fromUci(uci, b) ?: return null
            return com.pikafish.chess.engine.CommentaryEngine.moveToChineseNotation(m, b)
        } catch (e: Exception) {
            Log.e(TAG, "getMoveNotationByStep 重建失败 step=$totalStepIndex: ${e.message}")
            return null
        }
    }

    fun isViewingHistory(): Boolean = isViewingHistory

    // ── 变着查看与切换 ──

    /**
     * 获取当前步的变着信息
     * @return 变着描述列表（如 "变着1: 炮八平五 马8进7"），空列表表示无变着
     */
    fun getVariationsAtCurrentStep(): List<String> {
        val result = mutableListOf<String>()
        val stepVariations = variationBranches.filter { it.branchStep == currentStepIndex }
        for ((idx, vb) in stepVariations.withIndex()) {
            val branchBoard = Board()
            val fenBefore = if (vb.branchStep >= 0 && vb.branchStep < boardSnapshots.size) {
                boardSnapshots[vb.branchStep]
            } else {
                initialFen
            }
            branchBoard.loadFromFen(fenBefore)

            val notations = mutableListOf<String>()
            for (uci in vb.truncatedMoves) {
                val notation = CommentaryEngine.uciToChineseNotation(uci, branchBoard)
                notations.add(notation)
                val m = Move.fromUci(uci, branchBoard)
                if (m != null) branchBoard.makeMoveUnchecked(m)
            }
            val label = vb.label.ifBlank { nextVariationLabel(stepVariations.subList(0, idx)) }
            val preview = notations.joinToString(" ")
            val nestedHint = if (vb.subVariations.isNotEmpty()) "（内含${vb.subVariations.size}子变招）" else ""
            result.add("变着${label}: $preview $nestedHint".trim())
        }
        return result
    }

    /**
     * 切换到指定变着——将变着走法加载为主线，原主线保存为新变着
     * @param variationIndex 在当前步变着列表中的索引（0-based）
     * @return true=切换成功
     */
    fun switchToVariation(variationIndex: Int): Boolean {
        val stepVariations = variationBranches.filter { it.branchStep == currentStepIndex }
        if (variationIndex < 0 || variationIndex >= stepVariations.size) return false

        val targetVb = stepVariations[variationIndex]
        val branchStep = currentStepIndex

        // 1. 保存当前主线（branchStep+1 到末尾）为新变着
        val currentMoves = mutableListOf<String>()
        for (i in branchStep + 1 until boardSnapshots.size) {
            val prevFen = boardSnapshots[i - 1]
            val curFen = boardSnapshots[i]
            val tempBoard = Board()
            tempBoard.loadFromFen(prevFen)
            val m = detectMove(tempBoard, curFen)
            if (m != null) currentMoves.add(m.toUci())
        }

        // ★ 旧主线尾巴步上挂着的顶层变招（分支点 > branchStep），将作为旧主线新变招的嵌套子树保留
        val oldTailTopVars = variationBranches.filter { it.branchStep > branchStep }.toMutableList()

        // 2. 截断主线快照和历史
        while (boardSnapshots.size > branchStep + 1) {
            boardSnapshots.removeAt(boardSnapshots.size - 1)
        }
        while (moveHistoryUci.size > branchStep) {
            moveHistoryUci.removeAt(moveHistoryUci.size - 1)
        }
        explanationHistory.removeAll { it.totalStepIndex > branchStep }

        // ★ 重新计算走棋计数（截断后必须重算）
        moveCount = moveHistoryUci.size
        redMoveCount = 0
        blackMoveCount = 0
        val recountBoard = Board()
        recountBoard.loadFromFen(initialFen)
        for (uci in moveHistoryUci) {
            val m = Move.fromUci(uci, recountBoard)
            if (m != null) {
                recountBoard.makeMoveUnchecked(m)
                if (m.piece.color == PieceColor.RED) redMoveCount++ else blackMoveCount++
            }
        }

        // 3. 从变着列表中移除目标变着（其嵌套子树先保留，稍后提拔为新主线上层变招）
        variationBranches.remove(targetVb)

        // 4. 原主线保存为新变着（如果有走法），其尾巴上的顶层变招连同自身子树归并为其嵌套子树
        if (currentMoves.isNotEmpty()) {
            val oldMainSubs = mutableListOf<VariationBranch>()
            for (tailVb in oldTailTopVars) {
                // 旧主线程位置 tailVb.branchStep → 相对 oldMain.moves 的偏移（走完 N 手后分支）
                val rel = tailVb.branchStep - branchStep
                if (rel > 0 && rel <= currentMoves.size) {
                    oldMainSubs.add(
                        VariationBranch(rel, tailVb.truncatedMoves, tailVb.label, tailVb.subVariations.toMutableList())
                    )
                } else {
                    variationBranches.add(tailVb)  // 兜底：映射不上的保留原态
                }
            }
            variationBranches.removeAll(oldTailTopVars)
            variationBranches.add(VariationBranch(branchStep, currentMoves, "", oldMainSubs))
        }

        // 5. 将变着走法加载为主线
        val branchBoard = Board()
        branchBoard.loadFromFen(boardSnapshots[branchStep])
        for (uci in targetVb.truncatedMoves) {
            val move = Move.fromUci(uci, branchBoard)
            if (move != null && branchBoard.getLegalMoves(branchBoard.currentPlayer).any { it.toUci() == uci }) {
                val boardBefore = Board()
                boardBefore.loadFromFen(branchBoard.toFen())
                val notation = CommentaryEngine.uciToChineseNotation(uci, boardBefore)
                branchBoard.makeMove(move)
                moveCount++
                val moverColor = move.piece.color
                if (moverColor == PieceColor.RED) redMoveCount++ else blackMoveCount++
                val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
                val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
                boardSnapshots.add(branchBoard.toFen())
                currentStepIndex = boardSnapshots.size - 1
                moveHistoryUci.add(uci)
                recordMoveEntry(notation, colorName, sideStep, false)
            }
        }

        // ★ 目标变招内部的嵌套子树提拔为新主线上的顶层变招（分支点映射为全局步数）
        for (sv in targetVb.subVariations) {
            val globalStep = branchStep + sv.branchStep
            if (globalStep > branchStep && globalStep <= moveHistoryUci.size) {
                variationBranches.add(
                    VariationBranch(globalStep, sv.truncatedMoves, sv.label, sv.subVariations.toMutableList())
                )
            }
        }

        // 6. 跳回分支点，让用户逐步前进查看
        currentStepIndex = branchStep
        board.loadFromFen(boardSnapshots[branchStep])
        isViewingHistory = boardSnapshots.size > branchStep + 1

        identifyOpening()
        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()
        return true
    }

    // ── 人话解释爬楼追加 ──

    private val explanationLock = Any()

    /**
     * ★ 唯一解说入口：所有解说都必须经过这里
     * 线程安全 + 代际校验 + 去重 + 排序
     */
    private fun appendExplanation(entry: ExplanationEntry) {
        synchronized(explanationLock) {
            // ★ 代际校验：过期解说直接丢弃
            if (entry.totalStepIndex > currentStepIndex) {
                Log.w(TAG, "★ 拒绝过期解说: entry.step=${entry.totalStepIndex}, current=$currentStepIndex, color=${entry.color}")
                return
            }
            // ★ 假设分析不进入主列表
            if (entry.color == "假设" || entry.color.isEmpty()) {
                Log.w(TAG, "★ 拒绝非真实走棋解说: color=${entry.color}")
                return
            }
            // ★ 去重：同一步的解说替换而非追加
            val existing = explanationHistory.indexOfFirst { it.totalStepIndex == entry.totalStepIndex }
            if (existing >= 0) {
                explanationHistory[existing] = entry
                Log.d(TAG, "★ 更新解说 step=${entry.totalStepIndex} color=${entry.color} notation=${entry.moveNotation}")
            } else {
                explanationHistory.add(entry)
                Log.d(TAG, "★ 新增解说 step=${entry.totalStepIndex} color=${entry.color} notation=${entry.moveNotation}")
            }
            // 排序：确保严格按步数顺序
            explanationHistory.sortBy { it.totalStepIndex }
            // 超过上限时移除最旧的
            while (explanationHistory.size > MAX_EXPLANATION_HISTORY) {
                explanationHistory.removeAt(0)
            }
        }
        // ★ 统一走onExplanationHistoryChanged触发refreshExplanationDisplay重建
        // 必须在主线程调用
        gameHandler.post {
            onExplanationHistoryChanged?.invoke()
        }
    }

    /**
     * ★ 记录走棋棋谱（不依赖解说开关，始终记录）
     * 当enableExplanation=false时，只记录棋谱不生成解说文字
     * 当enableExplanation=true时，先记录棋谱，异步解说回调会替换text字段
     */
    private fun recordMoveEntry(
        notation: String,
        colorName: String,
        sideStep: Int,
        isHuman: Boolean
    ) {
        // ★ 防御性检查：如果棋谱异常短，记录详细日志以便追踪问题根源
        if (notation.length < 3) {
            Log.e(TAG, "★★★ recordMoveEntry: 棋谱异常短! notation='$notation' (len=${notation.length}) step=$sideStep color=$colorName totalIdx=$currentStepIndex isHuman=$isHuman")
        }
        val entry = ExplanationEntry(
            stepNumber = sideStep,
            isHuman = isHuman,
            moveNotation = notation,
            text = "",
            boardFen = board.toFen(),
            color = colorName,
            totalStepIndex = currentStepIndex
        )
        Log.d(TAG, "★ 记录棋谱: step=$sideStep color=$colorName notation=$notation totalIdx=$currentStepIndex isHuman=$isHuman")
        appendExplanation(entry)
    }

    /**
     * 根据当前步数移除超出的爬楼记录（回退时调用）
     */
    private fun trimExplanationsToStep(step: Int) {
        // 不移除，保留所有历史记录，UI层根据currentStepIndex过滤显示
    }

    fun flipBoard() {
        boardFlipped = !boardFlipped
        onBoardChanged?.invoke()
    }

    // ── 背谱谱源：当前已回放棋谱主线的起点与着法序列 ──
    fun bookLineStartFen(): String = initialFen
    fun bookLineMoves(): List<String> = moveHistoryUci.toList()

    /**
     * ★ 导出"整棵棋谱树"：主线 + 全部变着（含嵌套子变着），每条都是从起始局面起的完整 UCI 线路。
     *
     * 背谱据此建谱 —— 任一局面上"谱上真有的着法"全都收进来：
     *   · 变招处多着可选（如马八进七后卒3进1与卒7进1 都是谱上着法，任选其一都能走）；
     *   · 故意背进任何一条支线，支线上的后续着法也全在谱，一路背到底；
     *   · 不再只认人脑最后走过的那"一根筋"（那是纯 PGN 单线，变招全被丢弃）。
     *
     * 约定：`variationBranches` 里都是顶层变着，branchStep 为**绝对**手数；
     * 嵌套子变着只存在于 `subVariations` 内，其 branchStep 是相对宿主走法串的偏移。
     */
    fun bookLineAllLines(): List<List<String>> {
        val main = moveHistoryUci.toList()
        val out = ArrayList<List<String>>()
        if (main.isEmpty()) return out
        out.add(main)
        // hostFull: 宿主完整线；hostOffset: 宿主起点在 hostFull 中的下标
        fun expand(hostFull: List<String>, hostOffset: Int, subs: List<VariationBranch>) {
            for (vb in subs) {
                if (vb.truncatedMoves.isEmpty()) continue
                val step = (hostOffset + vb.branchStep).coerceIn(0, hostFull.size)
                val full = ArrayList<String>(step + vb.truncatedMoves.size)
                full.addAll(hostFull.subList(0, step))
                full.addAll(vb.truncatedMoves)
                out.add(full)
                expand(full, step, vb.subVariations)
            }
        }
        expand(main, 0, variationBranches)
        return out
    }

    fun onPlayerTypeChanged() {
        if (playerTypeChanging) {
            Log.d(TAG, "onPlayerTypeChanged: 忽略（正在切换中）")
            return
        }

        if (isSetupMode || gameOver) {
            onPlayerTypeChanged?.invoke()
            return
        }

        playerTypeChanging = true
        try {
            val snapshotFen = board.toFen()
            val snapshotPlayer = board.currentPlayer

            engine.stopSearch()
            engine.stopPonder()
            engineThinking = false
            pendingRetry = false

            // ★ 作废任何仍在进行的搜索回调：切换 AI/人脑 可能叠加两次 requestEngineMove，
            //   旧搜索的回调若不丢弃，会用一个旧局面/错局面的结果去动当前棋盘 → 乱走棋 + 乱指棋
            moveGeneration++

            if (board.toFen() != snapshotFen || board.currentPlayer != snapshotPlayer) {
                Log.e(TAG, "严重错误：onPlayerTypeChanged 中局面被意外修改！回滚...")
                board.loadFromFen(snapshotFen)
                board.currentPlayer = snapshotPlayer
            }

            // ★★★ 关键修复：轮到AI但引擎未就绪时也必须调用 requestEngineMove()，
            //   由它内部处理引擎重启。旧代码 isCurrentPlayerEngine() 内含
            //   engine.isEngineReady() 判断 → 引擎一旦死亡，这里永远不触发，
            //   AI 永久卡死（"不点催不走 / 催不动"的根因之一）。
            //   requestEngineMove() 内部：引擎就绪→正常搜索；未就绪→pendingRetry+重启。
            if (!isSetupMode && !gameOver && !isCurrentPlayerHuman()) {
                requestEngineMove()
            }

            onPlayerTypeChanged?.invoke()
            onBoardChanged?.invoke()
        } finally {
            playerTypeChanging = false
        }
    }

    // ── 自定义摆子模式 ──

    /** ★ 辅助外壳（摆子沙盒）：与识别/摆子数据隔离——仅备份/恢复「对局状态」，绝不触碰识别结果与摆子校验层。
     *  进入时备份整局，取消时原样恢复，只有真正完成(finishSetup)才替换对局。 */
    private data class SetupBackup(
        val stateJson: String,
        val gameOver: Boolean,
        val gameResult: String,
        val currentDisadvantage: CommentaryEngine.DisadvantageInfo?,
        val redIsHuman: Boolean,
        val blackIsHuman: Boolean,
        val setupFirstPlayer: PieceColor
    )
    private var setupBackup: SetupBackup? = null

    fun enterSetupMode() {
        if (gameOver) return
        engine.stopSearch()
        engine.stopPonder()
        engineThinking = false
        // ★ 辅助外壳：进入前备份对局状态（隔离带，不触碰识别数据）
        setupBackup = SetupBackup(
            stateJson = getFullStateJson().toString(),
            gameOver = gameOver,
            gameResult = gameResult,
            currentDisadvantage = currentDisadvantage,
            redIsHuman = redIsHuman,
            blackIsHuman = blackIsHuman,
            setupFirstPlayer = setupFirstPlayer
        )
        isSetupMode = true
        // ★ 不再清空棋盘：默认保留当前局面，用户可手动清空
        gameOver = false
        gameResult = ""
        pendingRetry = false
        setupFirstPlayer = board.currentPlayer
        isViewingHistory = false
        boardSnapshots.clear()
        explanationHistory.clear()
        // ★ 辅助外壳：清空旧棋谱，避免用「新摆子 FEN + 旧走法」重建而失败（隔离带）
        moveHistoryUci.clear()
        currentDisadvantage = null
        moveCount = 0
        redMoveCount = 0
        blackMoveCount = 0
        onSetupModeChanged?.invoke(true)
        onBoardChanged?.invoke()
        Log.d(TAG, "Entered setup mode (keeping current board position)")
    }

    fun setupPlacePiece(type: PieceType, color: PieceColor, pos: Position): String? {
        if (!isSetupMode) return "不在摆子模式"
        val existing = board.getPiece(pos)
        if (existing != null) {
            board.setPiece(pos, null)
            onBoardChanged?.invoke()
            return null
        }
        val error = SetupValidator.canPlacePiece(type, color, pos, board)
        if (error != null) return error
        board.setPiece(pos, Piece(type, color))
        onBoardChanged?.invoke()
        return null
    }

    fun setupRemovePiece(pos: Position) {
        if (!isSetupMode) return
        board.setPiece(pos, null)
        onBoardChanged?.invoke()
    }

    fun setupClearAll() {
        if (!isSetupMode) return
        board.clearBoard()
        onBoardChanged?.invoke()
    }

    fun setupFillStandard() {
        if (!isSetupMode) return
        board.reset()
        onBoardChanged?.invoke()
    }

    /**
     * ★ 从拍照识别结果设置棋盘
     * 清空当前棋盘，然后按识别结果逐一放置棋子
     * 跳过位置校验（因为识别可能有误差），但保留数量上限检查
     */
    fun setupFromRecognizedBoard(pieces: List<BoardRecognizer.RecognizedPiece>): String {
        if (!isSetupMode) return "不在摆子模式"

        board.clearBoard()

        var placed = 0
        var skipped = 0
        val typeCounts = mutableMapOf<Pair<PieceType, PieceColor>, Int>()

        for (rp in pieces) {
            val key = Pair(rp.type, rp.color)
            val count = typeCounts.getOrDefault(key, 0)

            // 检查数量上限
            val maxCount = when (rp.type) {
                PieceType.KING -> 1
                PieceType.PAWN -> 5
                else -> 2
            }
            if (count >= maxCount) {
                skipped++
                continue
            }

            // 直接放置，跳过位置校验（识别可能有偏差）
            board.setPiece(Position(rp.row, rp.col), Piece(rp.type, rp.color))
            typeCounts[key] = count + 1
            placed++
        }

        onBoardChanged?.invoke()

        val msg = if (skipped > 0) {
            "已放置 $placed 个棋子（跳过 $skipped 个超限）"
        } else {
            "已放置 $placed 个棋子"
        }
        Log.d(TAG, "setupFromRecognizedBoard: placed=$placed skipped=$skipped")
        return msg
    }

    fun finishSetup(): String? {
        if (!isSetupMode) return "不在摆子模式"
        val error = SetupValidator.validateSetupComplete(board, setupFirstPlayer)
        if (error != null) return error
        // ★ 辅助外壳：真正完成摆子 → 用新局面替换对局，备份作废（隔离带）
        setupBackup = null
        board.currentPlayer = setupFirstPlayer
        isSetupMode = false
        gameOver = false
        gameResult = ""
        engineThinking = false
        pendingRetry = false
        isViewingHistory = false
        moveCount = 0
        redMoveCount = 0
        blackMoveCount = 0
        boardSnapshots.clear()
        boardSnapshots.add(board.toFen())
        currentStepIndex = 0
        explanationHistory.clear()
        // ★ 辅助外壳：完成摆子 → 全新对局起点，作废旧棋谱，避免复盘用「新手面+旧走法」失败（隔离带）
        moveHistoryUci.clear()
        currentDisadvantage = null

        if (engine.isEngineReady()) {
            engine.newGame()
        }

        onSetupModeChanged?.invoke(false)
        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        onExplanationHistoryChanged?.invoke()

        if (isCurrentPlayerEngine() && engine.isEngineReady()) {
            requestEngineMove()
        }
        Log.d(TAG, "Setup completed, first player: $setupFirstPlayer")
        return null
    }

    fun cancelSetup() {
        // ★★ 关键修复：不在摆子模式、且没有"被摆子覆盖的旧对局"时，本函数必须【什么都不做】。
        //   原先无论有无备份都会走末尾的"恢复标准开局"分支，把棋盘重摆、boardSnapshots 清成 1 张。
        //   而「打开棋谱」的正常流程末尾会顺手调一次 cancelSetup()（见 MainActivity.openBookBytes），
        //   于是刚导入的棋谱（XQF / PGN / 中文记谱 / ICCS / 本地 JSON…）整盘被抹掉：
        //   进度显示 0/0 回合、播放/前进/后退全灰点不动 —— 正是"宝库认得、主界面却认不了"的真凶。
        //   （CBL/MXQ 走 loadQipuGame 不经过这里，所以那两类一直是好的。）
        val wasInSetup = isSetupMode
        isSetupMode = false
        val backup = setupBackup
        setupBackup = null
        if (!wasInSetup && backup == null) return
        if (backup != null) {
            // ★ 辅助外壳：未实质有效操作/直接返回 → 原样恢复进入前的对局（隔离带，不触碰识别数据）
            try {
                loadFullStateJson(org.json.JSONObject(backup.stateJson))
                gameOver = backup.gameOver
                gameResult = backup.gameResult
                currentDisadvantage = backup.currentDisadvantage
                redIsHuman = backup.redIsHuman
                blackIsHuman = backup.blackIsHuman
                setupFirstPlayer = backup.setupFirstPlayer
                engineThinking = false
                pendingRetry = false
                Log.d(TAG, "cancelSetup: 已原样恢复进入摆子前的对局（沙盒退出，进度无损）")
            } catch (e: Exception) {
                Log.e(TAG, "cancelSetup restore failed: ${e.message}", e)
                // 兜底：恢复失败才退回标准开局
                board.reset()
                gameOver = false
                gameResult = ""
                engineThinking = false
                pendingRetry = false
                isViewingHistory = false
                boardSnapshots.clear()
                boardSnapshots.add(board.toFen())
                currentStepIndex = 0
                explanationHistory.clear()
                currentDisadvantage = null
                moveCount = 0
                redMoveCount = 0
                blackMoveCount = 0
            }
        } else {
            // 兜底：无备份（异常路径）→ 恢复标准开局
            board.reset()
            gameOver = false
            gameResult = ""
            engineThinking = false
            pendingRetry = false
            isViewingHistory = false
            boardSnapshots.clear()
            boardSnapshots.add(board.toFen())
            currentStepIndex = 0
            explanationHistory.clear()
            currentDisadvantage = null
            moveCount = 0
            redMoveCount = 0
            blackMoveCount = 0
        }
        onSetupModeChanged?.invoke(false)
        onBoardChanged?.invoke()
    }

    // ── 引擎走棋 ──

    /**
     * ★ AI主动避免循环违规
     *
     * 检查当前局面下，AI走哪些走法会导致重复违规（长照/长捉）。
     * 返回应排除的UCI走法集合，引擎将不会选择这些走法。
     *
     * 核心逻辑：
     * 1. 如果当前局面已在历史中出现过2次以上（即将形成循环）
     * 2. 检查AI的每种走法是否会被分类为"照将"或"捉子"（攻击性着法）
     * 3. 如果该走法会延续违规循环，则排除该走法
     * 4. 引擎被迫选择次优但不违规的走法
     */
    private fun getRepetitionViolatingMoves(currentBoard: Board): Set<String> {
        val excluded = mutableSetOf<String>()
        if (boardSnapshots.size < 8) return excluded  // 前几手不检查

        val aiColor = currentBoard.currentPlayer
        val legalMoves = currentBoard.getLegalMoves(aiColor)
        if (legalMoves.isEmpty()) return excluded

        val currentKey = repetitionRule.positionKey(currentBoard.toFen())

        // ★ 近窗口快照（最后16步）：只在这圈子里找循环，避免很旧的"复原局面"被误判成磨棋盘
        val window = boardSnapshots.takeLast(16)
        val currentOcc = window.count { repetitionRule.positionKey(it) == currentKey }

        for (move in legalMoves) {
            val uci = move.toUci()
            // 模拟走棋
            val tempBoard = Board()
            tempBoard.loadFromFen(currentBoard.toFen())
            tempBoard.makeMoveUnchecked(move)
            val afterKey = repetitionRule.positionKey(tempBoard.toFen())

            // 走后局面是否回到近窗口历史（形成闭环倾向）
            val afterOcc = window.count { repetitionRule.positionKey(it) == afterKey }

            // ★★★ 提前拦截（用户核心顽疾：磨棋盘）：
            //   只要【当前局面已进入近历史循环】或【走这步会把局面送回近历史】，就开始排查攻击性着法。
            //   旧代码门槛是"当前局面必须完整重复≥2次才介入"——正是这个门槛让 AI 能放心把
            //   "将下→将上"循环磨上好几下（每次都在门槛下面绕，磨上头了就变着乱走）。
            //   现在：走后局面一回历史即拦，等于"将一下把你的老将从底调到中线、再一下调回"这种
            //   无用往复在第 1 下就可能被钳住，逼引擎把计算花在真正有进展的正招（如平车捉炮）上。
            if (currentOcc < 1 && afterOcc < 1) continue
            if (afterOcc < 1) continue  // 走后不回到历史 → 不构成循环，放行

            // 走这步会形成循环 → 检查是否为攻击性着法（照将/捉子）
            val boardAfter = Board()
            boardAfter.loadFromFen(currentBoard.toFen())
            boardAfter.makeMoveUnchecked(move)
            val isCheck = boardAfter.isInCheck(boardAfter.currentPlayer)
            val attacksBefore = getAttackTargetsForRepetition(currentBoard, aiColor)
            val attacksAfter = getAttackTargetsForRepetition(boardAfter, aiColor)
            val newAttacks = attacksAfter - attacksBefore
            val isChase = newAttacks.isNotEmpty() &&
                move.piece.type != PieceType.KING &&
                move.piece.type != PieceType.PAWN

            if (isCheck || isChase) {
                Log.d(TAG, "★ 提前排除磨棋盘走法: $uci (${if (isCheck) "照将" else "捉子"}) — 走这步会把局面送回近历史，形成无用循环")
                excluded.add(uci)
            }
        }

        // ★ 安全阀：如果所有走法都被排除（极端情况），不排除任何走法
        // 让引擎自由选择，宁可违规也比无棋可走好
        if (excluded.size >= legalMoves.size) {
            Log.w(TAG, "★ 所有走法都会违规，放弃排除，让引擎自由选择")
            return emptySet()
        }

        return excluded
    }

    /**
     * 获取某方能攻击的对方棋子位置（将/帅除外）— 用于重复违规检测
     */
    private fun getAttackTargetsForRepetition(board: Board, attackerColor: PieceColor): Set<Position> {
        val targets = mutableSetOf<Position>()
        val moves = board.getLegalMoves(attackerColor)
        for (m in moves) {
            if (m.captured != null) {
                val capturedPiece = board.getPiece(m.to)
                if (capturedPiece != null && capturedPiece.type != PieceType.KING) {
                    targets.add(m.to)
                }
            }
        }
        return targets
    }

    /**
     * ★ 截断历史走法，创建变招分支（从当前查看位置开始新分支）
     * 用于：人脑走变招 / AI在历史位置走棋
     */
    private fun truncateHistoryForVariation() {
        if (!isViewingHistory) return

        // 保存被截断的走法作为变招分支
        val truncatedMoves = mutableListOf<String>()
        for (i in currentStepIndex + 1 until boardSnapshots.size) {
            val prevFen = boardSnapshots[i - 1]
            val curFen = boardSnapshots[i]
            val tempBoard = Board()
            tempBoard.loadFromFen(prevFen)
            val m = detectMove(tempBoard, curFen)
            if (m != null) truncatedMoves.add(m.toUci())
        }
        if (truncatedMoves.isNotEmpty()) {
            val siblings = variationBranches.filter { it.branchStep == currentStepIndex }
            variationBranches.add(VariationBranch(currentStepIndex, truncatedMoves, nextVariationLabel(siblings)))
        }
        // 截断快照列表
        while (boardSnapshots.size > currentStepIndex + 1) {
            boardSnapshots.removeAt(boardSnapshots.size - 1)
        }
        // 截断解释历史
        explanationHistory.removeAll { it.totalStepIndex > currentStepIndex }
        // 截断走法历史
        while (moveHistoryUci.size > currentStepIndex) {
            moveHistoryUci.removeAt(moveHistoryUci.size - 1)
        }
        // 重新计算走棋计数
        moveCount = moveHistoryUci.size
        redMoveCount = 0
        blackMoveCount = 0
        val countBoard = Board()
        countBoard.loadFromFen(initialFen)
        for (uci in moveHistoryUci) {
            val m = Move.fromUci(uci, countBoard)
            if (m != null) {
                countBoard.makeMoveUnchecked(m)
                if (m.piece.color == PieceColor.RED) redMoveCount++ else blackMoveCount++
            }
        }
        isViewingHistory = false
        Log.i(TAG, "★ 历史已截断至第 $currentStepIndex 步，创建变招分支")
    }

    /**
     * ============================================================
     * ★★★ 独立通道巡检引擎 WatchdogHost 实现 ★★★
     * 巡检器只调用这些"观测/抢救"接口，不直接触碰引擎内部。
     * 对应"人脑接管释放 → 真正重启引擎 → 恢复 AI"的急诊流程，
     * 全程在内部完成，界面不暴露切人脑/切AI动作。
     * ============================================================
     */

    /** 该方是否正轮到引擎 */
    override fun watchdogIsEngineTurn(color: PieceColor): Boolean {
        val cur = if (board.currentPlayer == color) true else false
        return cur && !isCurrentPlayerHuman() && !isSetupMode
    }

    /** 该方引擎是否就绪 */
    override fun watchdogIsReady(color: PieceColor): Boolean = engine.isEngineReadyFor(color)

    /** 该方引擎是否正在搜索（★ 询招架构：全局唯一询招引擎，红黑通用一台） */
    override fun watchdogIsSearching(color: PieceColor): Boolean = engine.isSearching()

    // ★★★ 单时钟收敛：巡检器心跳检测只保留唯一职责——进程/就绪是否健康。
    //   搜索"想多久"已由引擎层唯一预算裁决（深度模式 2~9s 封顶、时间模式步时+余量），
    //   棋局层自愈钟只做"到点 stopSearch 收尾"。巡检器**不再**用另一把钟判"超时未落子"，
    //   否则多时钟互相踩踏（一边 stop 收尾、一边判挂起重启、重启清哈希后更慢 → 又触发
    //   → 蚂蚁转死圈 = 用户看到的"卡16层不上层也不走 / 重启后才14层动身"）。
    //   ★ 进程活着 → 搜索时长交给唯一时钟，巡检器绝不干预；进程没了 → 才必须重启恢复。
    override fun watchdogShouldRescue(color: PieceColor): Boolean {
        if (!watchdogIsEngineTurn(color)) return false
        // 未就绪（进程异常/刚崩溃）才是真正的"必须抢救"
        return !watchdogIsReady(color)
    }

    /** 记录某方引擎开始本次搜索的时间（巡检器心跳依据） */
    private fun markEngineSearchStart(color: PieceColor) {
        engineSearchStartByColor[color.ordinal] = android.os.SystemClock.elapsedRealtime()
    }

    /** 对局是否结束 */
    override fun watchdogIsGameOver(): Boolean = gameOver

    /** 巡检循环是否暂停（后台 / 画面不在前台时） */
    override fun watchdogIsPaused(): Boolean = false

    /** 复位该方"引擎思考"总开关（等效于人脑接管，释放死锁），返回是否进行 */
    override fun watchdogResetThinking(color: PieceColor): Boolean {
        // ★ 勘误修复：原代码在"新建的临时 Handler"上 removeCallbacksAndMessages(空队列)，
        //   根本没清到真正挂着 thinkingTimeoutRunnable 的那把 Handler，导致复位后旧的
        //   硬步时仍会在主线程又触发一次多余 stopSearch。这里清真实那把，并连带清分析超时。
        // ★★★ 单时钟收敛：同时作废自愈钟（epoch+1），残留钟即使触发也会自证作废。
        thinkingEpoch++
        thinkingTimeoutHandler?.removeCallbacksAndMessages(null)
        analyzingTimeoutHandler?.removeCallbacksAndMessages(null)
        engineThinking = false
        setAnalyzing(false)
        onEngineRestarting?.invoke()
        return true
    }

    /**
     * ★ 巡检抢救：真正重启该方引擎进程并恢复 AI 走棋。
     * 多线程/多触发去重：走 engineRestarting 锁，避免并发抢着重启。
     */
    override fun watchdogRescueEngine(color: PieceColor) {
        if (engineRestarting) { pendingRetry = true; return }
        // ★★★ "掀桌子"级自愈：看门狗一旦判定要抢救（连续异常 + 距上次真正重启 ≥40s），
        //   不再只重启引擎子进程——先把整条对局状态机清零重建（作废在途搜索回调、复位
        //   代际/开关/重试计数），再重启该方引擎进程并恢复 AI 走棋。堵死"子进程换新、
        //   状态机仍卡"的死角（这正是"重启了却没实质性反应"的根因）。
        fullMachineReset(color)
        restartEngineIfNeeded(color)
    }

    /**
     * ★★★ 某方引擎崩溃/无响应自愈：后台线程真正重启该方引擎进程
     * 重启成功后若仍轮到AI，则继续走棋。
     *
     * @param color 需要重启的引擎所属方（分离式红/黑引擎，只重启坏的那一方）
     */
    private fun restartEngineIfNeeded(color: PieceColor) {
        if (engineRestarting) return
        engineRestarting = true
        // ★★★ 记录本次真正重启时刻，供看门狗抢救冷却判定（打断"重启→慢思→又重启"死圈）
        lastWatchdogRescueAt = android.os.SystemClock.elapsedRealtime()
        val sideName = if (color == PieceColor.RED) "红方" else "黑方"
        Thread {
            try {
                Log.w(TAG, "★ 重启${sideName}引擎...")
                // ★ 抢救日志：仅记录、不改变对外状态文案（避免界面残留"异常/恢复中"）
                val err = engine.restartEngineFor(color)
                gameHandler.post {
                    engineRestarting = false
                    if (err == null) {
                        Log.i(TAG, "★ ${sideName}引擎重启成功，继续AI走棋")
                        // ★ 恢复后即时通知上层刷新状态栏（清除"异常/恢复中"残留）
                        onEngineRecovered?.invoke()
                        ensureEngineDriven(board.currentPlayer)
                    } else {
                        Log.e(TAG, "★ ${sideName}引擎重启失败: $err")
                        // ★ 重启失败也通知（界面改成"重启中"但不留异常，等下一次巡检或手动）
                        onEngineRecovered?.invoke()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "引擎重启线程异常: ${e.message}", e)
                gameHandler.post { engineRestarting = false }
            }
        }.start()
    }

    /**
     * ★ 手动恢复引擎（"恢复"按钮）：游戏卡死时一键自愈。
     * 停掉所有搜索 → 复位引擎思考开关 → 后台真正重启当前走子方引擎 → 恢复AI走棋。
     * 无论什么原因卡死（超时、崩溃、标志漏复位），点一下即可脱困。
     */
    fun manualRecoverEngine() {
        Log.w(TAG, "★ 用户手动恢复引擎（掀桌子级状态机复位）")
        try { engine.stopSearch() } catch (_: Exception) {}
        try { engine.stopPonder() } catch (_: Exception) {}
        val side = board.currentPlayer ?: return
        // ★★ 「恢复」必须切到【当前选定】的那台引擎：先把重启去重锁清零——
        //   若上一回自动抢救的重启线程曾卡住，engineRestarting 会一直挂 true，
        //   把这一次手动重启也挡在门外（点了「恢复」却毫无反应，引擎仍旧拖着上一台
        //   死折腾）。这里手动放行，再走到重启，重启的引擎由询招沙盒按「采用」
        //   那台（UserEngineStore.activeId）重新拉起，绝不会沿用旧进程/旧路径。
        engineRestarting = false
        fullMachineReset(side)     // 状态机一并清零重建，而非只换引擎子进程
        restartEngineIfNeeded(side)
    }

    /**
     * ★★★ "掀桌子"级全量复位：把整条对局状态机清零重建，而非只重启引擎子进程。
     *
     * 什么时候真正需要它：第一线抢救（watchdogResetThinking + restartEngineFor）在复杂/缠斗
     * 局面上反复失败时（卡点往往不在引擎进程，而在走棋接力状态机——孤儿搜索回调、代际错位、
     * 残留的 engineThinking/pendingRetry/搜索job 没被清干净）。本方法把这些全部归零，再用
     * 当前真实棋盘强制重新驱动当前走棋方，等效"整张桌子掀掉重立"。它只清状态、不动引擎进程，
     * 必须与 restartEngineIfNeeded 配套使用（清状态 ≠ 换进程，两件事都要做才能自愈）。
     */
    private fun fullMachineReset(color: PieceColor) {
        Log.w(TAG, "★ [状态机] 掀桌子级全量复位 (color=$color)")
        try { engine.stopSearch() } catch (_: Exception) {}
        try { engine.stopPonder() } catch (_: Exception) {}
        engineThinking = false
        setAnalyzing(false)
        aiRetryCount = 0
        pendingRetry = false
        // ★ 代际 +1：作废所有在途搜索回调，杜绝旧棋盘克隆上的孤儿 bestmove 被应用到当前局面
        moveGeneration++
        // ★★★ 单时钟收敛：自愈钟代际也 +1，残留钟自证作废
        thinkingEpoch++
        engineSearchStartByColor[PieceColor.RED.ordinal] = -1L
        engineSearchStartByColor[PieceColor.BLACK.ordinal] = -1L
        thinkingTimeoutHandler?.removeCallbacksAndMessages(null)
        analyzingTimeoutHandler?.removeCallbacksAndMessages(null)
    }

    /**
     * ★★★ 引擎回合安全续走：当且仅当"确实还轮到该方引擎、对局在继续、引擎就绪"时，
     * 强制把 requestEngineMove 重新推进调度队列，把可能掉进"引擎回合空转、无人续走"的
     * 对局拉活。幂等：任何恢复路径可安全重复调用，重复调度会被 removeCallbacks 去重。
     */
    // ═══════════ AI 回合交接状态机：可视化"谁思考→带预判离场→下位进场" ═══════════
    @Volatile private var aiPhaseSide: PieceColor? = null

    /** ★★★ PV 链式秒走：引擎主变里"下一方应手"的待走序列（双AI直读主变，空档期≈0） */
    private val pendingPvQueue = ArrayDeque<String>()
    /** 当前是否双 AI 对弈（两边都拨成引擎） */
    private val dualAiMode: Boolean get() = !redIsHuman && !blackIsHuman

    private fun emitAiPhase(state: String) {
        onAiTurnState?.invoke(state)
    }

    private fun sideName(c: PieceColor?): String = if (c == PieceColor.RED) "红方" else "黑方"

    /**
     * ★ 子力完整性自愈（"该是多少就是多少"铁律）：
     *   每次落子（人脑/AI 皆经此闸）后统计棋盘上每种、每方的子数，只要某一方某一兵种
     *   超过其规则上限——将(1)士(2)象(2)车(2)马(2)炮(2)兵(5)——就认定当前棋盘状态被
     *   污染（多出的子绝对是状态残留/串错位，合法对弈永远不可能出现）。
     *   一旦超限：立即把棋盘回退到最近一份已知良好的快照并强制重绘，从根上保证
     *   屏幕上绝不可能滞留"多出来的子"。正常运行逻辑绝不会触发（合法棋局永不超限）。
     */
    private fun enforcePieceIntegrity(): Boolean {
        try {
            for (color in arrayOf(PieceColor.RED, PieceColor.BLACK)) {
                for (t in PieceType.values()) {
                    val cap = when (t) {
                        PieceType.KING -> 1
                        PieceType.ADVISOR -> 2
                        PieceType.ELEPHANT -> 2
                        PieceType.ROOK -> 2
                        PieceType.KNIGHT -> 2
                        PieceType.CANNON -> 2
                        PieceType.PAWN -> 5
                    }
                    val count = board.countPieces(t, color)
                    if (count > cap) {
                        Log.e(TAG, "★★★ 子力超限自愈: $color $t=$count(上限$cap) — 棋盘被污染，回退最近良好快照")
                        boardSnapshots.lastOrNull()?.let { board.loadFromFen(it) }
                        onBoardChanged?.invoke()
                        return false
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "enforcePieceIntegrity 异常", e)
            return true
        }
    }

    /** 把引擎 PV 主线翻译成"预判对方应 / 我方再应"的中文预判（仅看前两段），用于离场携带。 */
    private fun buildAIprephrase(base: Board, pvStr: String): String {
        val ucis = pvStr.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (ucis.size < 2) return "（暂无预判线路）"
        val b = base.clone()   // base = AI 已落子后的局面
        val out = StringBuilder()
        var seg = 0
        try {
            for (idx in 1 until ucis.size) {   // 第0段=刚走的本手，跳过；从对方应手开始
                val uci = ucis[idx]
                val before = b.clone()
                val m = Move.fromUci(uci, b) ?: continue
                val ch = try { CommentaryEngine.uciToChineseNotation(uci, before) } catch (_: Exception) { uci }
                if (!b.makeMove(m)) continue
                seg++
                out.append(if (seg == 1) "预判对方应：$ch" else "｜我方再应：$ch")
                if (seg >= 2) break
            }
        } catch (_: Exception) {}
        return if (out.isEmpty()) "（暂无预判线路）" else out.toString()
    }

    private fun ensureEngineDriven(color: PieceColor? = null, delayMs: Long = 0L) {
        if (gameOver || isSetupMode) return
        val side = color ?: board.currentPlayer ?: return
        if (side != board.currentPlayer) return
        val task = Runnable {
            if (!gameOver && board.currentPlayer == side && isCurrentPlayerEngine()) {
                requestEngineMove()
            }
        }
        gameHandler.removeCallbacks(task)
        if (delayMs > 0L) gameHandler.postDelayed(task, delayMs) else gameHandler.post(task)
    }

    /**
     * ★★★ 双 AI 交接调度（空档期优化版）
     *
     * 核心问题（用户实测反馈）：双 AI 对弈时"这个走完和那个走之间空档期总是那么长"。
     * 旧实现：先在后台线程对"即将走棋"的引擎做一次 stopAndWaitForIdle 握手（最多等
     *   1.5 秒），再回主线程触发搜索；而搜索内部（searchBestMoveExcluding）启动前还会
     *   再做一次 stopAndWaitForIdle。同一台空闲引擎被"停"两遍 = 每步白等。
     *
     * 修复方案：
     *   双 AI 模式下，"即将走棋"的引擎上一轮搜索早已结束（bestmove 已收），本就空闲；
     *   搜索内部的 stopAndWaitForIdle 已保证命令不乱。这里直接回主线程触发走棋，
     *   把空档期压缩到毫秒级（配合 PV 链式秒走 = "砰的一下就走"）。
     */
    private fun scheduleNextAiMove() {
        if (gameOver || !isCurrentPlayerEngine()) return
        gameHandler.post {
            if (!gameOver && isCurrentPlayerEngine() && engine.isEngineReady()) {
                requestEngineMove()
            }
        }
    }

    private fun requestEngineMove() {
        if (engineThinking) return
        // ★★★ 防重搜铁律（用户核心要求：同一回合绝不从浅层重算）：
        //   若询招引擎此刻【正在搜索】，说明上一次搜索还没收招，任何复拉都只会把它
        //   代际作废、再从浅层重算一遍（"设17层反复绕弯、永远不到层"的入口之一）。
        //   直接让位等它算完——它到层后自然 bestmove 落子。isSearching 在 askBestMove
        //   全程置位、收招归零（修复后），此判断可信。
        if (engine.isSearching()) return
        if (gameOver) {
            // 游戏结束后继续走棋：重置游戏状态
            gameOver = false
            gameResult = ""
        }
        // ★ 摆子模式不走AI
        if (isSetupMode) return

        // ★ 练开局锁盘：下满约定回合后，引擎也绝不续走
        if (practiceLocked) return

        // ★★★ 前台优先：如果后台思考在跑，立即停止
        //   前台搜索是必须立即响应的，后台思考要给前台让路
        if (engine.isPondering()) {
            engine.stopPonder()
            predictedOpponentMoveUci = ""
        }

        // ★ 修复：查看历史时不再阻止AI走棋，而是截断历史创建变招
        if (isViewingHistory) {
            truncateHistoryForVariation()
        }
        val aiColor = board.currentPlayer
        // ★ 每次进入 AI 回合即刷新巡检计时戳（含云库/开局库等不走引擎的路径），
        //   避免"上次正经搜索距今过久"被巡检新逻辑误判为挂起而错误重启。
        markEngineSearchStart(aiColor)
        // ★★ 交接状态机：当前方进场思考（同方重试/自愈时不重复刷"进场"，但绝不阻断搜索）
        val isNewPhaseSide = aiPhaseSide != aiColor
        aiPhaseSide = aiColor
        if (isNewPhaseSide) {
            emitAiPhase(
                "🔄 ${sideName(aiColor)}进场思考中…（计算最佳着法 → 预判${if (aiColor == PieceColor.RED) "黑" else "红"}方应手 → 我方再应）"
            )
        }
        // ★★★ 询招架构：走棋只认全局唯一一台询招引擎（沙盒）。就绪检查只查它；
        //   未就绪就重启它（restartEngineFor 内部重启的就是询招沙盒），绝不查错对象。
        if (!engine.isEngineReadyFor(aiColor)) {
            pendingRetry = true
            onEngineRestarting?.invoke()
            // ★★★ 关键修复：真正重启询招引擎，而不是只显示"引擎重启中"文字。
            //   否则引擎一旦死亡（崩溃/被杀），AI 永远卡死，"催"也没用。
            restartEngineIfNeeded(aiColor)
            return
        }

        val boardBeforeAiMove = Board()
        boardBeforeAiMove.loadFromFen(board.toFen())

        // ★ 代际令牌：本次搜索的回调以此为准，过期回调直接丢弃
        moveGeneration++
        val myGeneration = moveGeneration

        // ★ 唯一应将快捷出招：如果AI被将军且只有一种合法应将方式，
        // 直接走出这步，不需要调引擎搜索（省时间，本质结果不变）
        if (boardBeforeAiMove.isInCheck(aiColor)) {
            val legalMoves = boardBeforeAiMove.getLegalMoves(aiColor)
            if (legalMoves.size == 1) {
                val forcedMove = legalMoves[0]
                Log.d(TAG, "★ 唯一应将：${forcedMove.toUci()}，直接执行不走引擎")
                engineThinking = true
                pendingRetry = false
                setAnalyzing(false)

                // ★ 颜色验证：确保走法是当前方的棋子
                val forcedMoverPiece = boardBeforeAiMove.getPiece(forcedMove.from)
                if (forcedMoverPiece == null || forcedMoverPiece.color != aiColor) {
                    Log.e(TAG, "★★★ 唯一应将走法颜色错误! expected=$aiColor piece=${forcedMoverPiece?.color} uci=${forcedMove.toUci()}")
                    engineThinking = false
                    onBoardChanged?.invoke()
                    return
                }

                val aiMoveUci = forcedMove.toUci()
                val aiNotation = CommentaryEngine.uciToChineseNotation(aiMoveUci, boardBeforeAiMove)

                // ★ 关键修复：检查 makeMove 返回值，走法失败则中止所有后续处理
                if (!board.makeMove(forcedMove)) {
                    Log.e(TAG, "★★★ 唯一应将 makeMove 失败! uci=$aiMoveUci fen=${boardBeforeAiMove.toFen()}")
                    engineThinking = false
                    // ★ 重试机制：走法失败时重试，防止AI卡死
                    aiRetryCount++
                    if (aiRetryCount <= MAX_AI_RETRY) {
                        Log.w(TAG, "★ AI走法失败，第${aiRetryCount}次重试...")
                        gameHandler.post {
                            if (!gameOver && isCurrentPlayerEngine() && engine.isEngineReady()) {
                                requestEngineMove()
                            }
                        }
                    } else {
                        Log.e(TAG, "★★★ AI重试${MAX_AI_RETRY}次仍失败，放弃")
                        aiRetryCount = 0
                    }
                    onBoardChanged?.invoke()
                    return
                }
                aiRetryCount = 0  // 走法成功，重置重试计数
                stallEscalation = 0  // 真实落子成功：清零"观察钟兜底"累积

                // 走法成功后才播放音效
                val captured = boardBeforeAiMove.getPiece(forcedMove.to)
                val isCapture = captured != null && captured.color != forcedMoverPiece.color
                if (isCapture) soundManager.play("eat")
                else soundManager.play("move")
                if (board.isInCheck(board.currentPlayer)) {
                    soundManager.play("check")
                }

                moveCount++
                val moverColor = forcedMoverPiece.color
                if (moverColor == PieceColor.RED) redMoveCount++ else blackMoveCount++
                val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
                val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
                boardSnapshots.add(board.toFen())
                currentStepIndex = boardSnapshots.size - 1
                moveHistoryUci.add(aiMoveUci)
                identifyOpening()
                onAiMove?.invoke(forcedMove)

                onBoardChanged?.invoke()
                onStepChanged?.invoke()

                recordMoveEntry(aiNotation, colorName, sideStep, false)

                engineThinking = false

                val over = checkGameOver()
                if (!over) {
                    checkRepetition()
                }
                if (!over && !gameOver) {
                    if (isCurrentPlayerEngine()) {
                        // ★★★ 双AI交接安全调度：先停稳引擎再触发下一步
                        scheduleNextAiMove()
                    } else {
                        // ★ 轮到用户走棋：预取云库，保证用户走完 AI 秒出
                        maybePrefetchCloudBook()
                    }
                }
                return
            }
        }

        // ★━━━ 库招进沙盒审核（用户钦定新规，替代旧版的整体旁路）━━━★
        //   开局库 / 云库给出的候选【不再绕过引擎直接秒走，也不封死 searchmoves 逼引擎跳坑】，
        //   而是分成两段全走沙盒：
        //     ① 沙盒浅探：先用很浅的层(≤8)快速拿到引擎自己的最佳方向（自证方案）；
        //     ② 合并候选：沙盒自证 + 开局库top2 + 云库top2，去重、剔磨棋盘违规、只留合法着；
        //     ③ 沙盒深度审核：在合并后的候选里按用户设定深度(如17层)一次算到底，
        //        引擎在候选内迭代加深、按评分选出真正最优——库招只是"多面性参考"，落子永远
        //        由沙盒裁决，绝无秒走（每个候选都被足层审核），也绝不丢沙盒自己算好的正招。
        //   ★ 铁一样的边界：云库只用已缓存着法（零网络等待），候选算不满/败时直接回退
        //     全着法沙盒搜索，质量与当前顶级引擎持平；searchmoves 裁剪只会让同层更快。
        if (trySandboxBookAudit(boardBeforeAiMove, aiColor, myGeneration)) return
        startEngineSearch(boardBeforeAiMove, aiColor, myGeneration)
    }

    /**
     * ★★★ 库招进沙盒审核（用户钦定，替代旁路/封死的旧路）：
     *
     * 要解决的顽疾：开局库 / 云库若【绕过引擎直接秒走】会掉坑；若【封死 searchmoves 只算库候选】
     * 又会把引擎自己最好的正招挤出候选、逼引擎在坑里挑（"没头没脑送马"）。所以这里走
     * 【两段全沙盒】：先浅探拿到引擎自证方向，再把库招并进候选，用一次深层搜索让引擎在
     * 候选内按评分裁决最优。库招永远是"多面性参考"，落子永远由沙盒足层审核后定夺。
     *
     *  ① 开局库 top2（内存O(1)，限开局谱半回合内）+ 云库缓存 top2（零网络等待）合并去重、
     *     剔除磨棋盘违规、只留合法着 —— 候选至多 4 个库着；
     *  ② 浅探沙盒（≤8 层，几十ms）：拿到引擎自证最佳方向并入候选（总数 ≤5）；
     *  ③ 把合并候选交给 startEngineSearch(restrictToUci)，沙盒按用户设定深度(如17层)在候选内
     *     一次算到底、按评分选最优落子 —— searchmoves 裁剪同层更快，绝不比全着法慢。
     *
     *  铁一样的边界：
     *   - 云库只用已缓存着法，绝不在本函数等到网络；缓存空则后台续热供下回合；
     *   - 沙盒浅探失败（引擎未就绪/超时）→ 立即 return false 走全着法深搜，宁不少用库招也
     *     绝不降质量、绝不以秒走/封死的方式把库招硬塞给引擎；
     *   - 开启局库/云库后，本函数即接管本回合（return true），落子校验/兜底全部走 startEngineSearch
     *     复用的 aiResultHandler，无第二条出口，绝不卡死。
     *
     * @return true=已接管本回合（沙盒结果回调负责落子）；false=无库候选/浅探失败，走原有全着法流程
     */
    private fun trySandboxBookAudit(boardBeforeAiMove: Board, aiColor: PieceColor, myGeneration: Int): Boolean {
        val excluded = getRepetitionViolatingMoves(boardBeforeAiMove)

        // 1) 开局库 top2 + 云库缓存 top2 合并去重，只留合法着
        val bookCands = LinkedHashSet<String>()
        if (localBookEnabled && localBook.count() > 0 &&
            (redMoveCount + blackMoveCount) <= BOOK_OPENING_PLY_LIMIT) {
            for (h in localBook.queryTop(boardBeforeAiMove, 2, excluded)) {
                if (h.uci !in excluded) bookCands.add(h.uci)
            }
        }
        val cloudCands = LinkedHashSet<String>()
        if (cloudBookEnabled) {
            val fen = boardBeforeAiMove.toFen()
            val cached = cloudBookClient.getCachedMoves(fen)
            if (!cached.isNullOrEmpty()) {
                for (m in cached.take(2)) if (m.move !in excluded) cloudCands.add(m.move)
            } else {
                cloudBookClient.getCachedBest(fen)?.let { if (it !in excluded) cloudCands.add(it) }
            }
            // 云库没缓存 → 后台续热（本回合绝不等待网络）
            if (cloudCands.isEmpty() && bookCands.isEmpty()) {
                cloudBookQueryExecutor.execute { cloudBookClient.queryAllMoves(fen) }
            }
        }
        // 两个库都没注出候选 → 不凑数，走原有全着法搜索（当前顶级质量原样保留）
        if (bookCands.isEmpty() && cloudCands.isEmpty()) return false

        val legalUci = try {
            boardBeforeAiMove.getLegalMoves(aiColor).map { it.toUci() }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "库招进沙盒审核: 取合法走法失败 ${e.message}"); emptySet()
        }
        val validBook = bookCands.filter { it in legalUci }
        val validCloud = cloudCands.filter { it in legalUci }
        if (validBook.isEmpty() && validCloud.isEmpty()) return false

        // 2) 沙盒浅探：极浅层(≤6)快速拿引擎自证方向（几十ms 内返回），并入候选保证"不丢沙盒正招"。
        //    ★ 浅探不叠加深度：它只是快速扒出引擎自己认定的最佳方向，真正决定落子还是后面
        //      按用户设定深度的那次 deep search（restrictToUci 裁剪让同层比全着法更快，
        //      所以浅探的开销完全抵消且有富余——绝不拖慢 17 层）。
        //    ★ 先统一置思考开关，让看门狗把浅探窗口正确视为"引擎在搜索"，衔接 UI 不空档。
        setEngineThinking(true)
        pendingRetry = false
        setAnalyzing(false)
        onBoardChanged?.invoke()
        val probeDepth = engineDepth.coerceIn(1, 6)
        if (showThinkingDetails) {
            onThinkingInfo?.invoke("【库招审核】开局库 ${validBook.size} + 云库 ${validCloud.size}，沙盒初判中…")
        }
        // 复用 EngineManager 单走询招沙盒做浅探（onResult 已切主线程）
        engine.searchBestMoveExcluding(
            color = aiColor,
            board = boardBeforeAiMove,
            excludedUci = excluded,
            timeMs = null,
            depth = probeDepth,
            onThinking = null,
            onResult = { probeBest ->
                var selfUci: String? = probeBest?.toUci()
                // 浅探为空（引擎未就绪/超时）→ 放弃库招审核，直接回退全着法深搜（质量优先）
                if (selfUci.isNullOrEmpty()) {
                    Log.w(TAG, "库招进沙盒审核: 浅探无结果，回退全着法搜索（不降质量）")
                    startEngineSearch(boardBeforeAiMove, aiColor, myGeneration)
                    return@searchBestMoveExcluding
                }
                // 3) 合并：沙盒自证 + 开局库 + 云库 → 去重（自证可能已含库招，天然去重）
                val merged = LinkedHashSet<String>().apply { add(selfUci!!); addAll(validBook); addAll(validCloud) }
                Log.i(TAG, "★ 库招沙盒审核: 沙盒自证($selfUci) + 开局库(${validBook.size}) + 云库(${validCloud.size}) → 候选 $merged 交给沙盒深层裁决")
                if (showThinkingDetails) {
                    onThinkingInfo?.invoke("【库招审核】汇总 ${merged.size} 条候选：沙盒 $selfUci + 开局库 ${validBook.size} + 云库 ${validCloud.size}，沙盒深层定夺…")
                }
                startEngineSearch(
                    boardBeforeAiMove, aiColor, myGeneration,
                    restrictToUci = merged.toSet(),
                    multiPv = minOf(merged.size, 5)  // 候选至多 5 条，超出封顶不失控
                )
            }
        )
        return true
    }

    /**
     * 从云库着法列表中挑选一步：
     *  - 排除会导致循环违规（棋规约束）的走法
     *  - 最优/次优结合：次优与最优差距不大时，随机用次优，避免变化过于单一
     *
     * @param moves 云库着法列表（按分值降序，[0]为最优）
     * @param boardBeforeMove 走棋前局面（用于违规检测）
     * @return 选中的走法 UCI，无可用走法返回 null
     */
    private fun selectCloudMove(
        moves: List<com.pikafish.chess.engine.CloudMove>,
        boardBeforeMove: Board
    ): String? {
        if (moves.isEmpty()) return null

        // 排除会导致循环违规的走法（棋规约束）
        val excluded = getRepetitionViolatingMoves(boardBeforeMove)
        var valid = if (excluded.isEmpty()) moves else moves.filter { it.move !in excluded }
        // 安全阀：全部被排除则退回原列表，宁可违规也不无棋可走
        if (valid.isEmpty()) valid = moves

        val best = valid[0]
        val second = valid.getOrNull(1)

        // 次优与最优差距不大（≤40分）时，30%概率用次优，避免变化单一
        if (second != null && second.score >= best.score - 40 && kotlin.random.Random.nextFloat() < 0.30f) {
            Log.d(TAG, "★ 云库选次优: ${second.move} (score=${second.score}, 最优=${best.move} score=${best.score})")
            return second.move
        }
        return best.move
    }

    /**
     * 用户回合时预取云库数据：把当前局面 + 用户所有合法走法之后局面的云库数据
     * 提前拉到缓存。这样用户一走完，AI 立刻从缓存秒出，不用等网络。
     *
     * 只在"轮到用户走棋"且"云库开启"时生效；每个局面只预取一次。
     * ★ 预取数量限制：只预取前 MAX_PREFETCH 个走法，避免请求过多排队，
     *   用户走棋快时预取还没完成，导致缓存未命中。
     */
    private fun maybePrefetchCloudBook() {
        if (!cloudBookEnabled) return
        if (isCurrentPlayerEngine()) return  // 只有用户回合才预取
        if (gameOver || isSetupMode) return
        try {
            val fen = board.toFen()
            // 每个局面只预取一次，避免重复请求
            if (!cloudBookPrefetchedFens.add(fen)) return
            // 当前局面自身也预取（可能直接有用）
            cloudBookClient.prefetch(fen)
            // 预取用户所有合法走法之后的局面（限制数量，保证预取及时完成）
            val legalMoves = board.getLegalMoves(board.currentPlayer)
            val limit = minOf(legalMoves.size, MAX_CLOUD_PREFETCH)
            for (i in 0 until limit) {
                val m = legalMoves[i]
                val child = Board()
                child.loadFromFen(board.toFen())
                child.makeMoveUnchecked(m)
                cloudBookClient.prefetch(child.toFen())
            }
            Log.d(TAG, "★ 云库预取: 当前局面 + $limit/${legalMoves.size} 个子局面")
        } catch (e: Exception) {
            Log.w(TAG, "云库预取异常: ${e.message}")
        }
    }

    /**
     * 启动引擎搜索（云库没开或云库没招法时调用）
     * @param restrictToUci 非空时为"多源汇审"：引擎只在云库+开局库合并出的这几个候选里搜索汇总；
     *                      为 null 时走正常全着法搜索。
     * @param multiPv 多线同步思考：>1 时引擎同时搜多条候选线齐头并进，界面同步显示再汇总；
     *                =1 时单线搜索（深度更深）。
     */
    private fun startEngineSearch(boardBeforeAiMove: Board, aiColor: PieceColor, myGeneration: Int, restrictToUci: Set<String>? = null, multiPv: Int = 1) {
        setEngineThinking(true)  // ★ 统一置思考开关（带自愈兜底，杜绝引擎思考卡死）
        pendingRetry = false
        setAnalyzing(false)  // ★ 引擎开始思考时，清除任何残留的分析状态
        onBoardChanged?.invoke()
        // ★ 巡检器心跳：记录该方引擎本次开始搜索的时间，用于识别"挂起不落子"
        markEngineSearchStart(aiColor)

        // ★★★ 双AI强制"单线搜到底"（与分析模式同款 multiPv=1）——AI卡顿的根本修复：
        //   多线(multiPv=3)三线齐头并进，每层慢约3倍；分析模式单线搜到底、蹭蹭上三十几层。
        //   双AI对弈时双方都不需要多线展示，直接单线极速，走子"砰的一下就出"。
        //   （人类参与的单AI对弈保留多线思考展示，不受影响。）
        val effectiveMultiPv = if (dualAiMode) 1 else multiPv

        // ★ 搜索参数：根据模式决定是时间模式还是深度模式
        val useTimeMode = searchMode == "time" && !practiceActive
        val searchTimeMs = if (useTimeMode) {
            // 时间模式：杀棋加速时减少思考时间
            if (inMateSequence) {
                Log.d(TAG, "★ 杀棋加速（时间模式）：思考时间从 ${engineTimeMs}ms 降至 200ms")
                200L
            } else {
                engineTimeMs.toLong()
            }
        } else null
        val searchDepth = if (useTimeMode) {
            null
        } else {
                // 深度模式：★ 彻底按用户设定的层数走，杀棋加速不降层——
                //   用户设 17 层就真算 17 层，绝不让"评分虚高的杀棋误判"把层数偷偷压到 5 层
                //   （那正是"我标 17 层，它却 13/14 层就动身"的元凶之一）。
                //   杀棋提速只保留给时间模式（见上方 searchTimeMs），深度模式层数说话算数。
                if (practiceActive) 14 else engineDepth  // ★ 练开局固定皮卡鱼 14 层
            }

        // ★★ FEN（含归谁走）硬锁定：每次搜索都明确把"当前局面"发给引擎，杜绝
        //   摆中残局却想开局 / 载入棋谱后走法序列残留 / 界面棋盘与思考脱节。
        try {
            val rootFen = boardBeforeAiMove.toFen()
            Log.d(TAG, "★ AI搜索根局面 fen=$rootFen 轮到方=${boardBeforeAiMove.currentPlayer} aiColor=$aiColor rootSameAsLive=${rootFen == board.toFen()}")
        } catch (_: Exception) {}

        // ★ AI主动避免循环违规：检查哪些走法会导致重复违规，将其排除
        val excludedMoves = getRepetitionViolatingMoves(boardBeforeAiMove)
        if (excludedMoves.isNotEmpty()) {
            Log.d(TAG, "★ AI主动避免违规：排除走法 $excludedMoves")
        }

        var lastScore = 0
        var lastDepth = 0
        var lastPv = ""

        // ★ 始终创建思考回调：即使不显示思考过程，也需要检测杀棋分数来加速
        val thinkingCb: ((Int, Int, Int, Long, Long, String, List<com.pikafish.chess.engine.JniEngine.PvLine>) -> Unit)? =
            { d, sd, sc, nodes, nps, pv, pvLines ->
                lastScore = sc
                lastDepth = d
                lastPv = pv
                // ★ 杀棋检测：进入连杀/必胜区间即启用加速模式
                if (com.pikafish.chess.engine.JniEngine.isMateScore(sc)) {
                    val mateIn = com.pikafish.chess.engine.JniEngine.matePliesOf(sc) ?: 1
                    if (mateIn in 1..10) {
                        inMateSequence = true
                        mateStepsRemaining = mateIn * 2  // 双方各走mateIn步
                        Log.d(TAG, "★ 杀棋检测：${mateIn}步杀，进入加速模式")
                    }
                }
                // ★ 杀棋结束：如果评分回到正常范围，退出杀棋模式
                if (kotlin.math.abs(sc) <= 9000 && inMateSequence) {
                    inMateSequence = false
                    mateStepsRemaining = 0
                    Log.d(TAG, "★ 杀棋模式退出：评分回归正常")
                }
                if (showThinkingDetails) {
                    val fen = boardBeforeAiMove.toFen()
                    if (pvLines.isNotEmpty() && pvLines.size > 1) {
                        // ★★ MultiPV 多线同步思考：多条候选线齐头并进，界面同步显示各线深度/评分
                        val markers = listOf("①", "②", "③", "④", "⑤", "⑥")
                        val colors = listOf("#4CAF50", "#F6C344", "#BBBBBB", "#BBBBBB", "#BBBBBB", "#BBBBBB")
                        val sb = StringBuilder()
                        for ((idx, line) in pvLines.take(6).withIndex()) {
                            val m = markers.getOrElse(idx) { "·" }
                            sb.append("<font color=\"${colors.getOrElse(idx) { "#BBBBBB" }}\">$m</font> ")
                            sb.append(formatThinking(d, sd, line.score, nodes, nps, line.pv, fen))
                            sb.append("<br/>")
                        }
                        onThinkingInfo?.invoke(sb.toString())
                    } else {
                        onThinkingInfo?.invoke(formatThinking(d, sd, sc, nodes, nps, pv, fen))
                    }
                }
                // ★★★ 分析"神同步"：主心骨（本次走子搜索）思考细节实时分发一份给分析面板。
                //   只想一遍、多处投递——分析不再另起炉灶跑第二遍深度搜索，杜绝双烧CPU、走子被抢线跑掉。
                if (emitAnalysis && onAnalysisFeed != null) {
                    try {
                        onAnalysisFeed?.invoke(boardBeforeAiMove.toFen(), d, sd, sc, nodes, nps, pv)
                    } catch (_: Exception) {}
                }
            }

        // ★★★ 单通道铁律（用户钦定）：主界面对弈（人vsAI / AI vs AI / 练开局 / 摆盘）
        //   的走棋搜索只走询招沙盒一条路——绝不再碰 WASM、绝无第二引擎、绝无多头兜底。
        //   卡了就是沙盒这一处，排查有唯一针对性；WASM 只保留在分析/复盘等旁路功能里。

        // onResult 回调（独立标签 aiResult，与具体引擎方法名解耦，红/黑分离路由共用）
        val aiResultHandler: (Move?) -> Unit = aiResult@{ engineMove ->
                engineThinking = false

                // ★ 代际令牌检查：如果期间有新操作发生，丢弃这个过期回调
                if (myGeneration != moveGeneration) {
                    Log.w(TAG, "★ 过期的AI搜索回调(myGen=$myGeneration curGen=$moveGeneration)，丢弃")
                    return@aiResult
                }

                // ★★★ 汇总必走子（用户核心要求）：多源汇审(restrictToUci)下，引擎若在
                //   步时内最终没有吐出合法的最佳着法（超时/引擎并发覆盖/被误判非法），
                //   绝不允许再回头"重新分析→定夺→又忘→再想→不拍板"的循环拉扯，也绝不
                //   走"重启引擎→再想"的循环。而是直接从候选集里挑选一着【强制落子】——
                //   "汇总之后的出口就是必走子"。这条路径没有第二个出口。
                var move = engineMove
                // ★★★ 必走子保险（用户核心顽疾：算明白却死活不落子）：
                //   无论多源汇审(restrictToUci)还是普通对弈，只要引擎思考过(lastPv 有主变)
                //   却最终没吐出一个合法最佳着法——超时/并发覆盖/WASM 回调异常/代际误判——
                //   就取引擎自己最后报的那条主变第一步【强制落子】。那是引擎亲口推荐的走法，
                //   安全，且正是用户要的"把想好的那招做实、落到棋盘上"。
                if (move == null) {
                    try {
                        val fallbackPv = lastPv.split(" ").firstOrNull()
                        val legalNow = try { boardBeforeAiMove.getLegalMoves(aiColor) }
                            catch (e: Exception) { emptyList() }
                        var fallback: Move? = null
                        // 首选：引擎自我报告的主变第一步（必须清洗出结果是当前方的合法着法）。
                        if (fallbackPv != null && fallbackPv.length >= 4 &&
                            (restrictToUci.isNullOrEmpty() || restrictToUci.contains(fallbackPv))) {
                            val cand = Move.fromUci(fallbackPv, boardBeforeAiMove)
                            if (cand != null && legalNow.any { it.from == cand.from && it.to == cand.to }) {
                                fallback = cand
                            }
                        }
                        // 次选（仅多源汇审）：从候选集∩合法着法里挑第一着。
                        if (fallback == null && !restrictToUci.isNullOrEmpty()) {
                            fallback = legalNow.firstOrNull { it.toUci() in restrictToUci }
                        }
                        // ★ 打死不改的底线：绝不用"第一个合法着法"滥竽充数。
                        //   引擎连一条主变都没报时，宁可让 move 保持 null，走下面的有界重试——
                        //   重试是拿真实局面【重新搜一遍】，而不是"瞬间乱走一着废棋"。
                        //   只有反复重试(MAX_AI_RETRY)仍无果，才会走到最后的强制兜底
                        //   playEngineFallbackMove（那里同样优先真思考、绝不拿瞎招顶包）。
                        if (fallback != null) {
                            Log.w(TAG, "★ 必走子保险：引擎算明白却没落子，强制落 ${fallback.toUci()}（pv='$lastPv'）")
                            move = fallback
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "必走子保险选招异常: ${e.message}")
                    }
                }

                if (move != null) {
                    // ★ 颜色验证：确保走法是当前方(aiColor)的棋子
                    val aiMoverPiece3 = boardBeforeAiMove.getPiece(move.from)
                    if (aiMoverPiece3 == null || aiMoverPiece3.color != aiColor) {
                        Log.e(TAG, "★★★ AI返回了错误方的走法! expected=$aiColor piece=${aiMoverPiece3?.color} uci=${move.toUci()} fen=${boardBeforeAiMove.toFen()}")
                        onBoardChanged?.invoke()
                        return@aiResult
                    }

                    val moverPiece = aiMoverPiece3  // 非空局部变量，供后续使用
                    val captured = boardBeforeAiMove.getPiece(move.to)
                    val aiMoveUci = move.toUci()
                    val aiNotation = CommentaryEngine.uciToChineseNotation(aiMoveUci, boardBeforeAiMove)
                    Log.i(TAG, "★★★ AI走棋: uci=$aiMoveUci notation=$aiNotation score=$lastScore depth=$lastDepth fen=${boardBeforeAiMove.toFen()}")

                    // ★ 练开局：算完却已锁盘（人执方已下满）→ 弃掉这手 AI 应着
                    if (practiceLocked) {
                        onBoardChanged?.invoke()
                        return@aiResult
                    }

                    // ★ 关键修复：检查 makeMove 返回值，走法失败则中止所有后续处理
                    if (!board.makeMove(move)) {
                        Log.e(TAG, "★★★ AI makeMove 失败! uci=$aiMoveUci fen=${boardBeforeAiMove.toFen()}")
                        aiRetryCount++
                        if (aiRetryCount <= MAX_AI_RETRY) {
                            Log.w(TAG, "★ AI走法失败，第${aiRetryCount}次重试...")
                            gameHandler.post {
                                if (!gameOver && isCurrentPlayerEngine() && engine.isEngineReady()) {
                                    requestEngineMove()
                                }
                            }
                        } else {
                            Log.e(TAG, "★★★ AI重试${MAX_AI_RETRY}次仍失败，放弃")
                            aiRetryCount = 0
                        }
                        onBoardChanged?.invoke()
                        return@aiResult
                    }
                    aiRetryCount = 0
                    // ★ 子力完整性自愈：AI 落子后必查——该是多少就是多少，超限即回退并让
                    //   当前方在完好局面上重新思考，绝不可能"走着走着凭空多子"。
                    if (!enforcePieceIntegrity()) {
                        gameHandler.post {
                            if (!gameOver && isCurrentPlayerEngine()) ensureEngineDriven(null, 0L)
                        }
                        return@aiResult
                    }
                    // ★★★ 分析"神同步"落子定论：主心骨走子搜索结论（最终深度/评分/主变）投递分析面板。
                    if (emitAnalysis && onAnalysisFeedResult != null) {
                        try {
                            onAnalysisFeedResult?.invoke(boardBeforeAiMove.toFen(), lastDepth, lastScore, lastPv)
                        } catch (_: Exception) {}
                    }
                    stallEscalation = 0  // 真实落子成功：清零"观察钟兜底"累积
                    val isCapture = captured != null && captured.color != moverPiece.color
                    if (isCapture) soundManager.play("eat")
                    else soundManager.play("move")
                    Log.d(TAG, "AI音效(normal): move=$aiMoveUci, captured=$captured, isCapture=$isCapture")
                    if (board.isInCheck(board.currentPlayer)) {
                        soundManager.play("check")
                    }

                    moveCount++
                    val aiMoverColor3 = moverPiece.color
                    if (aiMoverColor3 == PieceColor.RED) redMoveCount++ else blackMoveCount++
                    val aiSideStep3 = if (aiMoverColor3 == PieceColor.RED) redMoveCount else blackMoveCount
                    val aiColorName3 = if (aiMoverColor3 == PieceColor.RED) "红方" else "黑方"
                    boardSnapshots.add(board.toFen())
                    currentStepIndex = boardSnapshots.size - 1

                    moveHistoryUci.add(aiMoveUci)
                    identifyOpening()
                    onAiMove?.invoke(move)

                    onBoardChanged?.invoke()
                    onStepChanged?.invoke()

                    recordMoveEntry(aiNotation, aiColorName3, aiSideStep3, false)

                    // ★★ 交接状态机：当前方已携带预判离场（预判对方应手+我方再应），交棒给下一位
                    runCatching {
                        val predLine = buildAIprephrase(board, lastPv)
                        emitAiPhase("✅ ${sideName(aiMoverColor3)}带预判离场：走«$aiNotation»｜$predLine　→　交给${if (aiMoverColor3 == PieceColor.RED) "黑方进场" else "红方进场"}")
                    }

                    // ★ AI走完后，启动后台思考（如果满足条件）
                    startPonderIfNeeded(lastPv)

                    // ★★★ 双AI"每一手独立思考"（重大修复：杜绝跳飞/走偏）★★★
                    //   此前"PV链式秒走"会把对方引擎预判的主变后续(含对方应手)整串预存，
                    //   轮到对方时"盲走"而不让引擎针对真实局面重搜——这正是"平局被走成劣势、
                    //   分析17层能走对、双AI却走偏"的根源。
                    //   例：红方17层搜出主变里"预测黑方应手炮7进4"，链式直读就替黑方走炮7进4；
                    //   可黑方对真实局面重搜17层却应走卒7进1(差近180分)。预测≠己方独立算出。
                    //   ★ 现在：链队列一律清空，轮到谁就让谁在自己真实局面上按设定层数重搜——
                    //     双方棋力完全对等，平局沿线保持，优势方只会稳步推进，永不自摆乌龙。
                    pendingPvQueue.clear()
                    if (dualAiMode) {
                        Log.d(TAG, "★ 双AI独立重搜（不盲走对方预判主变）")
                    }

                    val over = checkGameOver()
                    if (!over) {
                        checkRepetition()
                    }
                    if (!over && !gameOver) {
                        if (isCurrentPlayerEngine()) {
                            // ★★★ 双AI交接：轮到谁就让谁独立思考（不再直读对方预判主变）
                            if (dualAiMode) {
                                pendingPvQueue.clear()
                            }
                            if (isCurrentPlayerEngine()) {
                                scheduleNextAiMove()
                            }
                        } else {
                            // ★ 轮到用户走棋：预取云库，保证用户走完 AI 秒出
                            maybePrefetchCloudBook()
                        }
                    }
                    // ★★★ 移除 System.gc()：它在主线程回调里强制全量GC，
                    //   每次AI走棋后都停顿数百ms，是"走着走着越来越慢"的元凶之一。
                    //   ART 会自动管理内存，无需手动触发。
                } else {
                Log.e(TAG, "★★★ AI未返回合法走法 (timeout/被并发覆盖?) fen=${boardBeforeAiMove.toFen()}")
                if (!engine.isEngineReady()) {
                    pendingRetry = true
                    onEngineRestarting?.invoke()
                    // ★★★ 引擎可能已死/无响应：真正重启当前走子方引擎
                    restartEngineIfNeeded(aiColor)
                } else if (!gameOver && isCurrentPlayerEngine()) {
                    // ★ 有界重试：宁可多等一瞬，也绝不动一个"乱走"的错棋（铁滑车/弃子的元凶）
                    if (aiRetryCount < MAX_AI_RETRY) {
                        aiRetryCount++
                        Log.w(TAG, "★ AI未取得合法走法，第${aiRetryCount}次重试...")
                        gameHandler.post {
                            if (!gameOver && isCurrentPlayerEngine() && engine.isEngineReady()) {
                                requestEngineMove()
                            }
                        }
                    } else {
                        aiRetryCount = 0
                        stallEscalation = 0
                        Log.e(TAG, "★★★ AI多次未取得合法走法：强制最稳合法应手兜底落子（绝不停摆）")
                        restartEngineIfNeeded(aiColor)
                        val bbm = Board()
                        bbm.loadFromFen(board.toFen())
                        playEngineFallbackMove(aiColor, bbm, lastPv)
                    }
                }
                onBoardChanged?.invoke()
            }
        }

        // ★★★ 唯一出口：询招沙盒（一次一问、随问随走）。按秒算的对接成 go movetime，
        //   按层算的对接成 go depth，规矩跟用户设置原样走。WASM 已从对弈链路整体摘除。
        //   ★ 传"冻结快照"boardBeforeAiMove 而非活棋盘 board——沙盒线程只碰快照，
        //     绝不与主线程在活棋盘上并发读写（那是"凭空多子"的根源之一）。
        engine.searchBestMoveExcluding(
            color = aiColor,
            board = boardBeforeAiMove,
            excludedUci = excludedMoves,
            timeMs = searchTimeMs,
            depth = searchDepth,
            onThinking = thinkingCb,
            onResult = aiResultHandler,
            restrictToUci = restrictToUci,
            multiPv = effectiveMultiPv   // ★ 双AI强制单线(multiPv=1)，极速出招
        )
    }

    /**
     * 执行云库走法（云库有招法时直接走，不用引擎搜索）
     */
    private fun executeCloudMove(cloudMoveUci: String, boardBeforeMove: Board, aiColor: PieceColor) {
        val cloudMove = Move.fromUci(cloudMoveUci, boardBeforeMove)
        if (cloudMove == null) {
            Log.w(TAG, "云库走法解析失败: $cloudMoveUci")
            engineThinking = false
            // 解析失败则回退到引擎搜索
            val gen = moveGeneration
            startEngineSearch(boardBeforeMove, aiColor, gen)
            return
        }

        // 颜色验证
        val moverPiece = boardBeforeMove.getPiece(cloudMove.from)
        if (moverPiece == null || moverPiece.color != aiColor) {
            Log.w(TAG, "云库走法颜色错误: $cloudMoveUci")
            engineThinking = false
            val gen = moveGeneration
            startEngineSearch(boardBeforeMove, aiColor, gen)
            return
        }

        // 验证合法性
        val legalMoves = boardBeforeMove.getLegalMoves(aiColor)
        val isLegal = legalMoves.any { it.from == cloudMove.from && it.to == cloudMove.to }
        if (!isLegal) {
            Log.w(TAG, "云库走法不合法: $cloudMoveUci")
            engineThinking = false
            val gen = moveGeneration
            startEngineSearch(boardBeforeMove, aiColor, gen)
            return
        }

        val aiNotation = com.pikafish.chess.engine.CommentaryEngine.uciToChineseNotation(cloudMoveUci, boardBeforeMove)
        Log.i(TAG, "★ 云库走棋: uci=$cloudMoveUci notation=$aiNotation fen=${boardBeforeMove.toFen()}")

        // 执行走法
        try {
        if (!board.makeMove(cloudMove)) {
            Log.e(TAG, "云库 makeMove 失败: $cloudMoveUci")
            engineThinking = false
            aiRetryCount++
            if (aiRetryCount <= MAX_AI_RETRY) {
                gameHandler.post {
                    if (!gameOver && isCurrentPlayerEngine() && engine.isEngineReady()) {
                        requestEngineMove()
                    }
                }
            } else {
                aiRetryCount = 0
            }
            onBoardChanged?.invoke()
            return
        }
        aiRetryCount = 0
        stallEscalation = 0  // 真实落子成功：清零"观察钟兜底"累积

        // 显示云库思考信息
        if (showThinkingDetails) {
            onThinkingInfo?.invoke("【云库】$aiNotation")
        }

        // 播放音效
        val captured = boardBeforeMove.getPiece(cloudMove.to)
        val isCapture = captured != null && captured.color != moverPiece.color
        if (isCapture) soundManager.play("eat")
        else soundManager.play("move")
        if (board.isInCheck(board.currentPlayer)) {
            soundManager.play("check")
        }

        // 更新历史
        moveCount++
        val moverColor = moverPiece.color
        if (moverColor == PieceColor.RED) redMoveCount++ else blackMoveCount++
        val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
        val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
        boardSnapshots.add(board.toFen())
        currentStepIndex = boardSnapshots.size - 1
        moveHistoryUci.add(cloudMoveUci)
        identifyOpening()
        onAiMove?.invoke(cloudMove)

        onBoardChanged?.invoke()
        onStepChanged?.invoke()

        recordMoveEntry(aiNotation, colorName, sideStep, false)

        engineThinking = false

        // 游戏结束检测
        val over = checkGameOver()
        if (!over) {
            checkRepetition()
        }
        if (!over && !gameOver) {
            if (isCurrentPlayerEngine()) {
                // ★★★ 双AI交接安全调度：先停稳引擎再触发下一步
                scheduleNextAiMove()
            } else {
                // ★ 轮到用户走棋：预取云库，保证用户走完 AI 秒出
                maybePrefetchCloudBook()
            }
        }
        } catch (e: Exception) {
            // ★★★ 修复：云库走棋过程任何异常都不能让 engineThinking 永久卡 true
            Log.e(TAG, "executeCloudMove异常(引擎防卡死复位)", e)
            engineThinking = false
            onBoardChanged?.invoke()
        } finally {
            engineThinking = false
        }
    }

    /**
     * ★★★ 引擎"该出子却没出"的绝对兜底：无论哪条搜索/锁/结果链没给到可落子结果，
     *   都从当前合法着法中挑一步落到棋盘上（优先后台预想的最佳着法，否则首个合法着法），
     *   复用 executeCloudMove 这条"已验证的完整落子链路"(记谱/历史/交接/收尾)。
     *   ★ 目的：永远不让一步棋"悬空"——宁可走一步合法但未必最优的棋，也绝不停摆卡死。
     *   ★ 双驱动安全：代际令牌 `moveGeneration` 已先行推进，任何陈旧迟到结果到达时
     *     按代际判废丢弃，不会在同一局面反复走两遍。
     */
    private fun playEngineFallbackMove(color: PieceColor, boardBeforeMove: Board, lastPv: String = "") {
        try {
            var chosenUci = ""
            // 优先用后台预想/已有最佳着法（若它仍是合法走法）——预想已搬到空闲引擎，
            // 这里跨红黑两台引擎取（EngineManager.getPonderBestMove 红黑都查）
            runCatching {
                val pondered = engine.getPonderBestMove()
                if (pondered.isNotEmpty()) {
                    val pm = Move.fromUci(pondered, boardBeforeMove)
                    val legal = boardBeforeMove.getLegalMoves(color)
                    if (pm != null && legal.any { it.from == pm.from && it.to == pm.to }) {
                        chosenUci = pondered
                    }
                }
            }
            // ★ 再优先用引擎自己最后报的那条主变第一步（真思考的线路），绝不让"第一个
            //   合法着法"顶包——那正是"秒走飞边相等废棋"的直接来源。
            if (chosenUci.isEmpty() && lastPv.isNotEmpty()) {
                runCatching {
                    val firstPv = lastPv.trim().split(" ").firstOrNull().orEmpty()
                    if (firstPv.length >= 4) {
                        val pm = Move.fromUci(firstPv, boardBeforeMove)
                        val legal = boardBeforeMove.getLegalMoves(color)
                        if (pm != null && legal.any { it.from == pm.from && it.to == pm.to }) {
                            chosenUci = firstPv
                        }
                    }
                }
            }
            if (chosenUci.isEmpty()) {
                val legal = boardBeforeMove.getLegalMoves(color)
                if (legal.isEmpty()) {
                    engineThinking = false
                    stallEscalation = 0
                    onBoardChanged?.invoke()
                    return
                }
                // ★ 仅此一步走到"第一个合法着法"是绝对最终手段（重试+重启都无用后），
                //   且首选能直接吃子/将军的动子手，尽量别把好好的局面白白送出去。
                chosenUci = pickSaneFallbackMove(legal).toUci()
            }
            // 代际令牌推进：作废任何陈旧迟到结果，防同局面走两遍
            moveGeneration++
            engineThinking = false
            pendingRetry = false
            setAnalyzing(false)
            stallEscalation = 0
            Log.w(TAG, "★★★ 强制兜底落子: color=$color uci=$chosenUci")
            executeCloudMove(chosenUci, boardBeforeMove, color)
        } catch (e: Exception) {
            Log.e(TAG, "playEngineFallbackMove 异常", e)
            engineThinking = false
            stallEscalation = 0
            onBoardChanged?.invoke()
        }
    }

    /**
     * 在绝对兜底时从合法着法里挑一个"不那么废"的：优先吃子 → 其次将军 → 最后才安静着。
     * 仅此一处允许离开引擎真思考走棋（此时已重启+3 次重试都无果），绝不让"第一个
     * 合法着法"顶包成飞边相等瞎招白白送掉大好局面。
     */
    private fun pickSaneFallbackMove(candidates: List<com.pikafish.chess.model.Move>): com.pikafish.chess.model.Move {
        // 1) 能吃子就先吃（合法着法不会吃己方子，落点有子即对方子）
        candidates.firstOrNull { board.getPiece(it.to) != null }?.let { return it }
        // 2) 其次选"一步将死/将军"的动子手（clone 模拟，不改动真实棋盘）
        candidates.firstOrNull { m ->
            runCatching {
                val tmp = Board()
                tmp.loadFromFen(board.toFen())
                if (!tmp.makeMove(m)) false else tmp.isInCheck(tmp.currentPlayer)
            }.getOrElse { false }
        }?.let { return it }
        // 3) 最后才是安静着
        return candidates.first()
    }

    /**
     * ≈≈≈ 库招"质量闸"（吸收力 + 防中毒，纯数据、绝无第二次引擎搜索）≈≈≈
     *   - 保留吸收力：未知统计（样本不足）的着法按"未知"放行，整本库照常吸收；
     *   - 防中毒：经验样本足够(≥BOOK_GATE_MIN_GAMES)且胜率过低(<BOOK_GATE_WIN_RATE)的着法
     *     判为"毒着"不放行，交还引擎正常搜索——既不被烂谱带偏，也不会因二次搜索
     *     与巡检自愈互锁而"深度冻结/落不了子/反复重启"。
     *   注意：此处绝不动引擎（不搜索、不改心跳），从根上远离卡死。
     */
    private fun bookMovePassesQualityGate(hit: LocalBookManager.BookHit): Boolean {
        if (hit.totalGames < BOOK_GATE_MIN_GAMES) return true   // 样本太少/无统计 → 未知，放行保吸收力
        return hit.winRate >= BOOK_GATE_WIN_RATE               // 样本足且胜率过低 → 毒着，不放行
    }

    /**
     * 执行本地外置开局库命中走法（书里查到就直接走，引擎不搜索）
     * @param hit 翻库命中结果（哪个库、哪一着）
     */
    private fun executeBookMove(hit: LocalBookManager.BookHit, boardBeforeMove: Board, aiColor: PieceColor) {
        val bookMove = Move.fromUci(hit.uci, boardBeforeMove)
        if (bookMove == null) {
            Log.w(TAG, "书库走法解析失败: ${hit.uci}, 库=${hit.bookName}")
            engineThinking = false
            startEngineSearch(boardBeforeMove, aiColor, moveGeneration)
            return
        }

        // 颜色验证
        val moverPiece = boardBeforeMove.getPiece(bookMove.from)
        if (moverPiece == null || moverPiece.color != aiColor) {
            Log.w(TAG, "书库走法颜色错误: ${hit.uci}, 库=${hit.bookName}")
            engineThinking = false
            startEngineSearch(boardBeforeMove, aiColor, moveGeneration)
            return
        }

        // 验证合法性
        val legalMoves = boardBeforeMove.getLegalMoves(aiColor)
        val isLegal = legalMoves.any { it.from == bookMove.from && it.to == bookMove.to }
        if (!isLegal) {
            Log.w(TAG, "书库走法不合法: ${hit.uci}, 库=${hit.bookName}，回退引擎")
            engineThinking = false
            startEngineSearch(boardBeforeMove, aiColor, moveGeneration)
            return
        }

        val aiNotation = com.pikafish.chess.engine.CommentaryEngine.uciToChineseNotation(hit.uci, boardBeforeMove)
        Log.i(TAG, "★ 翻库走棋: 库=${hit.bookName} uci=${hit.uci} notation=$aiNotation fen=${boardBeforeMove.toFen()}")

        // 记录命中库名（供上层展示已翻过的库）
        localBookHitNames.add(hit.bookName)

        try {
            if (!board.makeMove(bookMove)) {
                Log.e(TAG, "书库 makeMove 失败: ${hit.uci}")
                engineThinking = false
                aiRetryCount++
                if (aiRetryCount <= MAX_AI_RETRY) {
                    gameHandler.post {
                        if (!gameOver && isCurrentPlayerEngine() && engine.isEngineReady()) {
                            requestEngineMove()
                        }
                    }
                } else {
                    aiRetryCount = 0
                }
                onBoardChanged?.invoke()
                return
            }
            aiRetryCount = 0
            stallEscalation = 0  // 真实落子成功：清零"观察钟兜底"累积

            // 显示翻库思考信息（金色高亮库招：tvThinking 用 Html.fromHtml 渲染）
            if (showThinkingDetails) {
                onThinkingInfo?.invoke(
                    "<font color='#E8C44A'><b>【翻库·${hit.format.uppercase()}·${hit.bookName}】$aiNotation</b></font>"
                )
            }
            // ★ 命中库着法：通知上层，这一着可金色高亮
            onBookHit?.invoke(hit)

            // 播放音效
            val captured = boardBeforeMove.getPiece(bookMove.to)
            val isCapture = captured != null && captured.color != moverPiece.color
            if (isCapture) soundManager.play("eat")
            else soundManager.play("move")
            if (board.isInCheck(board.currentPlayer)) {
                soundManager.play("check")
            }

            // 更新历史
            moveCount++
            val moverColor = moverPiece.color
            if (moverColor == PieceColor.RED) redMoveCount++ else blackMoveCount++
            val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
            val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
            boardSnapshots.add(board.toFen())
            currentStepIndex = boardSnapshots.size - 1
            moveHistoryUci.add(hit.uci)
            identifyOpening()
            onAiMove?.invoke(bookMove)

            onBoardChanged?.invoke()
            onStepChanged?.invoke()

            recordMoveEntry(aiNotation, colorName, sideStep, false)

            engineThinking = false

            // 游戏结束检测
            val over = checkGameOver()
            if (!over) {
                checkRepetition()
            }
            if (!over && !gameOver) {
                if (isCurrentPlayerEngine()) {
                    // ★★★ 双AI交接安全调度：先停稳引擎再触发下一步
                    scheduleNextAiMove()
                } else {
                    // ★ 轮到用户走棋：预取云库，保证用户走完 AI 秒出
                    maybePrefetchCloudBook()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeBookMove异常(引擎防卡死复位)", e)
            engineThinking = false
            onBoardChanged?.invoke()
        } finally {
            engineThinking = false
        }
    }

    /**
     * 执行后台思考命中后的AI走棋
     * 直接使用后台思考搜到的最佳走法，跳过正常搜索流程
     */
    private fun executePonderHitMove(move: Move, depth: Int, score: Int) {
        if (gameOver) return
        if (engineThinking) return
        // ★ 练开局锁盘后：引擎不得再续走
        if (practiceLocked) { try { engine.stopPonder() } catch (_: Exception) {}; return }

        val aiColor = board.currentPlayer
        val boardBeforeAiMove = Board()
        boardBeforeAiMove.loadFromFen(board.toFen())

        engineThinking = true
        setAnalyzing(false)
        onBoardChanged?.invoke()

        // 颜色验证
        val aiMoverPiece = boardBeforeAiMove.getPiece(move.from)
        if (aiMoverPiece == null || aiMoverPiece.color != aiColor) {
            Log.e(TAG, "★★★ Ponder命中走法颜色错误! expected=$aiColor piece=${aiMoverPiece?.color} uci=${move.toUci()}")
            engineThinking = false
            requestEngineMove()  // 降级为正常搜索
            return
        }

        val moverPiece = aiMoverPiece
        val captured = boardBeforeAiMove.getPiece(move.to)
        val aiMoveUci = move.toUci()
        val aiNotation = CommentaryEngine.uciToChineseNotation(aiMoveUci, boardBeforeAiMove)
        Log.i(TAG, "★★★ Ponder命中走棋: uci=$aiMoveUci notation=$aiNotation score=$score depth=$depth")

        // 执行走棋
        try {
        if (!board.makeMove(move)) {
            Log.e(TAG, "★★★ Ponder命中 makeMove 失败! uci=$aiMoveUci")
            engineThinking = false
            requestEngineMove()  // 降级为正常搜索
            return
        }

        // 播放音效
        val isCapture = captured != null && captured.color != moverPiece.color
        if (isCapture) soundManager.play("eat")
        else soundManager.play("move")
        if (board.isInCheck(board.currentPlayer)) {
            soundManager.play("check")
        }

        // 更新步数和快照
        moveCount++
        val moverColor = moverPiece.color
        if (moverColor == PieceColor.RED) redMoveCount++ else blackMoveCount++
        val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
        val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
        boardSnapshots.add(board.toFen())
        currentStepIndex = boardSnapshots.size - 1

        moveHistoryUci.add(aiMoveUci)
        identifyOpening()
        onAiMove?.invoke(move)

        onBoardChanged?.invoke()
        onStepChanged?.invoke()

        recordMoveEntry(aiNotation, colorName, sideStep, false)

        // 显示思考信息（命中时也显示一下深度和评分）
        if (showThinkingDetails) {
            val ponderPv = engine.getPonderPv().ifEmpty { aiMoveUci }
            onThinkingInfo?.invoke(formatThinking(depth, depth, score, 0, 0, ponderPv, boardBeforeAiMove.toFen()))
        }

        engineThinking = false
        predictedOpponentMoveUci = ""

        val over = checkGameOver()
        if (!over) {
            checkRepetition()
        }

        if (!over && !gameOver) {
            if (isCurrentPlayerEngine()) {
                // 双AI模式：继续走下一步AI（使用安全交接）
                scheduleNextAiMove()
            } else {
                // ★ 命中走棋后，继续启动下一轮后台思考
                // 直接用后台思考的PV（ponderPv[0]是AI刚走的步，ponderPv[1]是预测对手下一步）
                startPonderIfNeeded(engine.getPonderPv())
                // ★ 轮到用户走棋：预取云库，保证用户走完 AI 秒出
                maybePrefetchCloudBook()
            }
        }
        } catch (e: Exception) {
            // ★★★ 修复：任何异常都不能让 engineThinking 永久卡 true（否则点不动/走不动）
            Log.e(TAG, "executePonderHitMove异常(引擎防卡死复位)", e)
            engineThinking = false
            predictedOpponentMoveUci = ""
            onBoardChanged?.invoke()
        } finally {
            engineThinking = false
        }
    }

    /**
     * ★★★ PV 链式秒走（双AI"砰的一下就走"的核心实现）
     *
     * 用户核心诉求：黑方搜完、思考主变已递到红方，红方就该"直接走"，而不是重新现想。
     * 实现：把上一轮搜索主变里"后续应手"逐手直读落子——黑方搜完主变第2手就是红方应手、
     * 第3手又是黑方应手……按主变连续落子，空档期≈0。每手都做合法性/循环违规校验，
     * 任一不合规立即中止（返回 false，交回正常搜索兜底）。
     *
     * @return true=链完整消费（或对局已终）；false=链失效，调用方应清空队列走正常搜索
     */
    private fun tryPlayPvChain(): Boolean {
        if (gameOver || isSetupMode) return true
        while (pendingPvQueue.isNotEmpty() && !gameOver) {
            // ★ 练开局锁盘：链内也绝不续走
            if (practiceLocked) { pendingPvQueue.clear(); return false }
            val uci = pendingPvQueue.removeFirst()
            val move = Move.fromUci(uci, board)
            if (move == null) return false
            // ★ 合法性校验：必须是当前走子方的合法着法
            val legal = board.getLegalMoves(board.currentPlayer).any { it.toUci() == uci }
            if (!legal) return false
            // ★ 循环违规校验（棋例：长将长捉禁止重复）
            if (uci in getRepetitionViolatingMoves(board)) return false
            playChainMove(move)
        }
        return true
    }

    /** 链式直读落子的记账（与 AI 正常落子完全一致：音效/步数/快照/棋谱/回调） */
    private fun playChainMove(move: Move) {
        val boardBefore = Board()
        boardBefore.loadFromFen(board.toFen())
        val aiColor = board.currentPlayer
        val moverPiece = boardBefore.getPiece(move.from)
        if (moverPiece == null || moverPiece.color != aiColor) {
            pendingPvQueue.clear()
            return
        }
        val captured = boardBefore.getPiece(move.to)
        val aiMoveUci = move.toUci()
        val aiNotation = try { CommentaryEngine.uciToChineseNotation(aiMoveUci, boardBefore) } catch (e: Exception) { aiMoveUci }
        Log.i(TAG, "★★★ PV链直读走棋: uci=$aiMoveUci notation=$aiNotation")

        if (!board.makeMove(move)) { pendingPvQueue.clear(); return }

        val isCapture = captured != null && captured.color != moverPiece.color
        if (isCapture) soundManager.play("eat") else soundManager.play("move")
        if (board.isInCheck(board.currentPlayer)) soundManager.play("check")

        moveCount++
        val moverColor = moverPiece.color
        if (moverColor == PieceColor.RED) redMoveCount++ else blackMoveCount++
        val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
        val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
        boardSnapshots.add(board.toFen())
        currentStepIndex = boardSnapshots.size - 1
        moveHistoryUci.add(aiMoveUci)
        identifyOpening()
        onAiMove?.invoke(move)
        onBoardChanged?.invoke()
        onStepChanged?.invoke()
        recordMoveEntry(aiNotation, colorName, sideStep, false)

        // ★ 思考面板直读提示：让用户看到"对方已在主变上直接应手，无需现想"
        if (showThinkingDetails) {
            onThinkingInfo?.invoke("⚡ 主变直读：${colorName} ${aiNotation}（思考已递到，无需现想）")
        }

        val over = checkGameOver()
        if (!over) checkRepetition()
    }

    /**
     * 启动后台思考（Ponder）——延迟启动
     *
     * ★ 为什么延迟？
     *   AI 走完棋后，UI 要播放走子动画、更新棋谱、刷新棋盘，这些都在主线程做。
     *   如果马上启动后台思考，引擎占满 CPU，UI 动画会卡。
     *   所以等 500ms，让 UI 先忙完，再启动后台琢磨。
     *
     * 条件：
     *   1. 开启了后台思考开关
     *   2. 不是双AI模式（双AI时后台思考自动关闭）
     *   3. 游戏没结束
     *   4. 轮到人脑走了（对方是人脑）
     *   5. 引擎就绪
     *   6. 有可用的 PV 来预测对手走法
     *
     * @param pv 上一次搜索的PV，用于预测对手下一步（pv[0]是AI刚走的，pv[1]是预测对手的）
     */
    private fun startPonderIfNeeded(pv: String) {
        val bothAi = !redIsHuman && !blackIsHuman

        // ★ 云库与后台思考时间点不冲突：后台思考在人机回合（对方思考空档）预想，
        //   云库在 AI 回合查表，二者并存互不干涉。开后门思考的条件仅由下方开关决定，
        //   不再被"开了云库"一刀切停掉。

        if (!alwaysThinking || bothAi || gameOver || !isCurrentPlayerHuman() || !engine.isEngineReady()) {
            // 双AI时确保停止
            if (bothAi) {
                engine.stopPonder()
                predictedOpponentMoveUci = ""
            }
            return
        }

        // ★ 本轮要为"即将回应"的引擎预想（AI 刚走完，轮到人脑；AI = 人脑的对手方）
        //   后台思考改为在"当前局面(人脑待走)"上开 MultiPV 多线预想：
        //   同时推出多条"人脑可能走法 + 我的应着"，不再只赌一步。
        val aiColor = board.currentPlayer.opponent()

        // ★ 延迟 500ms 启动，让 UI 先更新完
        //   用 moveGeneration 做代际检查：如果延迟期间有新操作发生，就不启动了
        val myGeneration = moveGeneration
        gameHandler.postDelayed({
            // 代际检查：过期了就不启动
            if (myGeneration != moveGeneration) {
                Log.d(TAG, "startPonderIfNeeded: 过期，不启动")
                return@postDelayed
            }
            // 再检查一次条件（游戏可能结束了、玩家可能切了）
            val stillBothAi = !redIsHuman && !blackIsHuman
            if (!alwaysThinking || stillBothAi || gameOver || !isCurrentPlayerHuman() || !engine.isEngineReady()) {
                return@postDelayed
            }
            if (engine.isSearching()) return@postDelayed  // 前台在搜，就别抢

            predictedOpponentMoveUci = ""

            // 用当前局面（人脑待走）启动后台多线预想：预测人脑几手可能走法 + 各自预演应着
            // ★★★ 预想搬到"人脑一方的空闲引擎"进程上跑（参考神匠象棋 red/black 双引擎分家做法）：
            //    应手引擎（AI 方）从此全程不被 go infinite 预想占锁/占管道——
            //    人脑一走，正式搜索立刻能在干净引擎上 stop→isready→go，
            //    从根上杜绝"停预想让不出锁 → 正式搜索拿不到引擎 → 该走不走卡死"的死结。
            val ponderHostColor = aiColor.opponent()   // 人脑一方 = 空闲引擎
            engine.startPonderFor(ponderHostColor, board)
            Log.d(TAG, "★ 后台多线预想启动(空闲引擎host=$ponderHostColor)：lines=${engine.getPonderLinesFor(ponderHostColor).size}")

            // 立即显示一条提示，让用户知道已经开始了
            if (showThinkingDetails) {
                onThinkingInfo?.invoke("【后台】多线预想中… 预测人脑几手可能走法并预演应着 <font color=\"#888888\">① ② ③</font>")
            }
        }, 500)
    }

    /**
     * ★ 供设置面板「后台」开关即时生效：开关一开，若此刻正轮到人脑且引擎空闲，
     *   立即在空闲引擎上开预想；条件不满足则安静作罢（下一回合 startPonderIfNeeded 会接管）。
     *   关闭时由调用方直接 engine.stopPonder()。
     */
    fun startBackgroundThinkingNow() {
        try { startPonderIfNeeded("") } catch (e: Exception) {
            Log.w(TAG, "startBackgroundThinkingNow 忽略异常: ${e.message}")
        }
    }

    private fun formatThinking(depth: Int, selDepth: Int, score: Int, nodes: Long, nps: Long, pv: String, startFen: String): String {
        val mt = com.pikafish.chess.engine.JniEngine.mateText(score)
        val scoreStr = mt ?: "${if (score >= 0) "+" else ""}${score}"
        val npsK = if (nps >= 1000000) "${nps / 1000000}M" else if (nps >= 1000) "${nps / 1000}K" else "$nps"
        val nodesK = if (nodes >= 1000000) "${nodes / 1000000}M" else if (nodes >= 1000) "${nodes / 1000}K" else "$nodes"
        var pvCn = try {
            com.pikafish.chess.engine.CommentaryEngine.pvToChinese(pv, startFen)
        } catch (e: Exception) { pv }
        // ★ 开局库着色：首着命中库则在思考行标金色「谱」。
        val first = pv.trim().substringBefore(' ')
        if (first.isNotEmpty() && first.length <= 4) {
            val inBook = try { first in localBook.bookMoveSet(board) } catch (e: Exception) { false }
            if (inBook) pvCn = "<font color=\"#E8C44A\">$pvCn «谱»</font>"
        }
        // ★ 无节点数据(秒走/云库/主变直读等路径没现算引擎)时不拼"节点0 nps0"，避免难看的零占位
        val stats = if (nodes > 0L || nps > 0L) " 节点${nodesK} nps$npsK" else ""
        return "深度$depth/$selDepth 评分$scoreStr$stats $pvCn"
    }

    /**
     * 格式化后台多线预想的显示信息（HTML富文本，三行 ①②③）
     *
     * 【后台】多线预想：
     *   ① 若 <红>炮二平五</红> → 我应 <绿>马八进七</绿>  深度18 评分+20
     *   ② 若 <红>马八进七</红> → 我应 <绿>炮八平五</绿>  深度17 评分+10
     *   ③ 若 <红>车一平二</红> → 我应 <绿>炮八进二</绿>  深度16 评分+5
     *
     * 每条线 = 一个"预测人脑可能走法 + 预演好的应着"，人脑真走了其中一步就秒出应着。
     *
     * 评分统一转化为"我方（引擎方）视角"：后台从"人脑待走"局面起搜，原始评分是人脑
     * 视角，取负即为我方评估。
     *
     * @param lines 后台多线预案（按线路序）
     * @param maxDepth 当前最大深度
     */
    private fun formatPonderThinking(lines: List<com.pikafish.chess.engine.JniEngine.PonderLine>, maxDepth: Int): String {
        if (lines.isEmpty()) return "【后台】多线预想中…"

        val markers = listOf("①", "②", "③", "④", "⑤")
        val colors = listOf("#4CAF50", "#F6C344", "#BBBBBB", "#BBBBBB", "#BBBBBB")
        val currentFen = board.toFen()
        // ★ 开局库着色：一次取当前局面全部库着法（纯字典命中，不选招，极快），
        //   任一预想路线里命中库的走法就标成金色「谱」，证明其启发来自库。
        val bookMoves = try { localBook.bookMoveSet(board) } catch (e: Exception) { emptySet<String>() }
        val sb = StringBuilder("【后台】多线预想：<br/>")
        for ((idx, line) in lines.take(5).withIndex()) {
            val pred = line.predictedMove
            val resp = line.responseMove
            if (pred.isEmpty()) continue
            val predCn = try {
                CommentaryEngine.uciToChineseNotation(pred, board)
            } catch (_: Exception) { pred }
            var respCn = resp
            if (resp.isNotEmpty()) {
                respCn = try {
                    val b = Board()
                    b.loadFromFen(currentFen)
                    val pm = Move.fromUci(pred, b)
                    if (pm != null) {
                        b.makeMoveUnchecked(pm)
                        CommentaryEngine.uciToChineseNotation(resp, b)
                    } else resp
                } catch (_: Exception) { resp }
            }
            // 后台从"人脑待走"起搜，评分取负转为我方视角
            val aiScore = -line.score
            val mt = com.pikafish.chess.engine.JniEngine.mateText(aiScore)
            val scoreStr = mt ?: "${if (aiScore >= 0) "+" else ""}$aiScore"
            val mark = markers.getOrElse(idx) { "·" }
            val baseColor = colors.getOrElse(idx) { "#BBBBBB" }
            // ★ 库着法着色：金色加「谱」标记，与纯引擎思考区分
            val fromBook = pred in bookMoves
            val predColor = if (fromBook) "#E8C44A" else "#FF4444"
            val bookTag = if (fromBook) " <font color=\"#E8C44A\">«谱»</font>" else ""
            sb.append("<font color=\"$baseColor\">$mark</font> 若 <font color=\"$predColor\">$predCn</font>$bookTag → 我应 <font color=\"#4CAF50\">$respCn</font> <font color=\"#888888\">深度${line.depth} 评分$scoreStr</font>")
            sb.append("<br/>")
        }
        sb.append("<font color=\"#777777\">已达 ${maxDepth} 层，人脑走其中任一步即可秒应</font>")
        return sb.toString()
    }

    /**
     * 循环着法判定（棋例约束）
     *
     * 在每步走棋后调用，检测是否形成循环归原局面。
     * 根据亚洲象棋联合会棋例判定：
     * - 长照（连续照将）→ 攻击方必须变着，不变作负
     * - 长捉（连续捉子）→ 攻击方必须变着，不变作负
     * - 长拦、长献、长兑 → 守方可以坚持，作和
     * - 双方犯同一禁例 → 作和
     * - 双方均无犯例 → 作和
     *
     * @return true 如果循环判定导致游戏结束或需要警告
     */
    private fun checkRepetition(): Boolean {
        val verdict = repetitionRule.checkRepetition(
            boardSnapshots,
            moveHistoryUci,
            warnedViolator
        )

        when (verdict) {
            is RepetitionRule.Verdict.NoRepetition -> {
                // 没有循环，清除警告状态
                if (warnedViolator != null) {
                    Log.d(TAG, "循环着法: 局面已变化，清除变着警告")
                    warnedViolator = null
                }
                return false
            }

            is RepetitionRule.Verdict.MustChange -> {
                // 首次检测到违规循环 → 警告变着（不结束棋局）
                warnedViolator = verdict.violator
                val sideName = if (verdict.violator == PieceColor.RED) "红方" else "黑方"
                val msg = "$sideName${verdict.reason}，必须变着，不变作负"
                Log.d(TAG, "循环着法警告: $msg")
                onRepetitionWarning?.invoke(msg)
                return true
            }

            is RepetitionRule.Verdict.ForcedLoss -> {
                // ★ 违规方在警告后仍重复 → 只弹窗警告，不结束棋局
                // 用户要求：违规只提示，不死不困毙不结束
                // 他可以继续磨，你继续弹窗就好
                warnedViolator = verdict.loser
                val sideName = if (verdict.loser == PieceColor.RED) "红方" else "黑方"
                val winnerName = if (verdict.loser == PieceColor.RED) "黑方" else "红方"
                val msg = "${sideName}${verdict.reason}！${winnerName}可申诉胜，但棋局继续。"
                Log.d(TAG, "循环着法违规(继续): $msg")
                onRepetitionWarning?.invoke(msg)
                return true
            }

            is RepetitionRule.Verdict.ForcedDraw -> {
                // ★ 判和也只弹窗提示，不强制结束
                warnedViolator = null
                val msg = "循环着法（${verdict.reason}），双方不变作和，但棋局继续。"
                Log.d(TAG, "循环着法判和(继续): $msg")
                onRepetitionWarning?.invoke(msg)
                return true
            }
        }
        return false
    }

    private fun checkGameOver(silent: Boolean = false): Boolean {
        if (board.isCheckmate(board.currentPlayer)) {
            gameOver = true
            val winnerColor = board.currentPlayer.opponent()
            gameResult = if (winnerColor == PieceColor.RED) "红方胜!" else "黑方胜!"
            if (!silent) {
                soundManager.play("end")
                // ★ 人话解说暂时关闭：不生成终局总结，只显示结果
                onGameOver?.invoke(gameResult)
            }
            onBoardChanged?.invoke()
            return true
        }
        if (board.isStalemate(board.currentPlayer)) {
            gameOver = true
            gameResult = if (board.currentPlayer == PieceColor.RED) "红方困毙!" else "黑方困毙!"
            if (!silent) {
                soundManager.play("end")
                onGameOver?.invoke(gameResult)
            }
            onBoardChanged?.invoke()
            return true
        }
        // ★★ PK 比赛专用判和出口：两台引擎互磨时，若出现"同一局面三度出现"或走到
        //    半回合上限，就判和收局（否则引擎对吊着能磨到天荒地老，成绩永远出不来）。
        if (pkMode) {
            val repeated = try {
                val cur = board.toFen()
                boardSnapshots.count { it == cur } >= 3
            } catch (_: Exception) { false }
            if (repeated || moveCount >= PK_DRAW_PLIES) {
                gameOver = true
                gameResult = "和棋!"
                if (!silent) {
                    soundManager.play("end")
                    onGameOver?.invoke(gameResult)
                }
                onBoardChanged?.invoke()
                return true
            }
        }
        return false
    }

    fun isEngineThinking(): Boolean = engineThinking

    /** 获取完整快照列表（用于持久化） */
    fun getBoardSnapshots(): List<String> = boardSnapshots.toList()

    /** 获取解释历史（用于持久化） */
    fun getExplanationHistory(): List<ExplanationEntry> = explanationHistory.toList()

    /** 获取走法历史UCI（用于持久化） */
    fun getMoveHistoryUci(): List<String> = moveHistoryUci.toList()

    /**
     * 获取用于棋盘光标高亮的走法
     *
     * ★ 铁律：只返回当前步实际走的那一步棋（1步=2个光标点）
     * 人家走哪步棋，就只显示那一步棋的起点和终点，不乱跳
     * 无论浏览历史还是正常对局，都只显示当前步的走法
     *
     * 每步棋包含：走法UCI + 是否AI走
     */
    fun getRecentMovesForHighlight(): List<Pair<String, Boolean>> {
        if (currentStepIndex <= 0 || moveHistoryUci.isEmpty()) return emptyList()

        // ★ 只返回当前步的那一步走法（从stepIndex-1到stepIndex的走法）
        val i = currentStepIndex - 1
        if (i >= 0 && i < moveHistoryUci.size) {
            return listOf(Pair(moveHistoryUci[i], isMoveByAi(i)))
        }
        return emptyList()
    }

    /**
     * 判断第i步走法是否由AI走出
     * boardSnapshots[i] 是走第i步之后的棋盘状态，其currentPlayer是下一步的走棋方
     * 所以走第i步的方 = boardSnapshots[i].currentPlayer.opponent()
     */
    private fun isMoveByAi(i: Int): Boolean {
        val fen = boardSnapshots.getOrNull(i) ?: return false
        val tempBoard = Board()
        tempBoard.loadFromFen(fen)
        // ★ 修复：boardSnapshots[i]是走棋后的状态，currentPlayer是下一个走棋方
        // 走第i步的方 = currentPlayer.opponent()
        val moverColor = tempBoard.currentPlayer.opponent()
        return if (moverColor == PieceColor.RED) !redIsHuman else !blackIsHuman
    }

    /** 获取走棋计数 */
    fun getMoveCount(): Int = moveCount

    // ── 人话解释方法 ──

    /**
     * 安全设置分析状态
     * - 设置true时启动超时保护（30秒后强制清除）
     * - 设置false时取消超时保护
     */
    private fun setAnalyzing(analyzing: Boolean) {
        if (analyzing) {
            // 启动超时保护
            if (analyzingTimeoutHandler == null) {
                analyzingTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
            }
            analyzingTimeoutHandler?.removeCallbacks(analyzingTimeoutRunnable)
            analyzingTimeoutHandler?.postDelayed(analyzingTimeoutRunnable, 30_000)
            onAnalyzing?.invoke(true)
        } else {
            analyzingTimeoutHandler?.removeCallbacks(analyzingTimeoutRunnable)
            onAnalyzing?.invoke(false)
        }
    }

    /**
     * ★ AI解说（人话/现世报/关）：人脑走完一步后，按当前解说风格异步生成一句解说。
     *
     * 关键安全设计（绝不破坏皮卡鱼引擎）：
     *  - 只在"走棋方自家引擎"上评估——人是红方就用红方引擎、人是黑方就用黑方引擎，
     *    而 AI 走棋永远用的是另一侧引擎，两者是独立进程，天然互不抢占。
     *  - 空闲闸门：引擎正在思考/后台预测/未就绪时直接作罢，绝不打断 AI 走棋。
     *  - 回调带代际校验：用户又走了一步或切了局，旧结果作废，由新一步接管。
     */
    private fun periodMoveCommentary(before: Board, uci: String, notation: String, moverColor: PieceColor) {
        val style = commentaryStyle
        if (style == CommentaryStyle.OFF) return                       // 关：不开口
        if (gameOver || isSetupMode) return
        // ★ 引擎安全闸：只检查"走棋方自家引擎"是否忙碌——AI 走棋用的是对面那台独立进程。
        //   人机对弈时对方引擎预想/搜索并不影响这侧开口，因此只看本方引擎。
        val ownEngine = engine.engineFor(moverColor)
        if (ownEngine.isSearching() || ownEngine.isPondering()) return // 本方引擎真的在忙才跳过
        if (!ownEngine.isEngineReady()) return
        val gen = moveGeneration                                        // 代际令牌
        val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
        val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
        val totalStep = currentStepIndex
        engine.evaluateMoveFor(moverColor, before, uci, explanationDepth) { best, bScore, hScore, pv, d ->
            if (gen != moveGeneration) return@evaluateMoveFor             // 旧代际作废
            if (best.isEmpty()) return@evaluateMoveFor
            try {
                val base = when (style) {
                    CommentaryStyle.KARMA ->
                        com.pikafish.chess.engine.KarmaCommentary.evaluateHumanMove(
                            uci, best, hScore, bScore, d, pv, board
                        )
                    else ->
                        CommentaryEngine.explainHumanMove(
                            uci, colorName, notation, best, hScore, bScore, d, pv, before
                        )
                }
                if (base.isBlank()) return@evaluateMoveFor
                // ★ 皮卡鱼嘴替：把引擎的闷葫芦（评分/推荐/主变）翻成人话，叠在最前面那两句下面
                val thought = CommentaryEngine.pikafishThought(colorName, best, uci, hScore, bScore, d, pv, before)
                val text = "$base\n$thought"
                try { updateDisadvantageTracking(hScore, board) } catch (_: Exception) {}
                val entry = ExplanationEntry(
                    stepNumber = sideStep, isHuman = true, moveNotation = notation,
                    text = text, boardFen = board.toFen(),
                    color = colorName, totalStepIndex = totalStep
                )
                appendExplanation(entry)
            } catch (e: Exception) {
                Log.e(TAG, "periodMoveCommentary error: ${e.message}")
            }
        }
    }

    private fun evaluateHumanMoveAsync(boardBeforeMove: Board, humanMoveUci: String, humanNotation: String) {
        if (!engine.isEngineReady()) return
        setAnalyzing(true)

        // ★ 按方计数：当前走棋方的步数
        val moverColor = boardBeforeMove.currentPlayer
        val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
        val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
        val totalStep = currentStepIndex  // ★ 捕获当前总步数索引（异步回调时可能已变化）
        val gen = moveGeneration  // ★ 捕获代际令牌

        engine.evaluateMove(boardBeforeMove, humanMoveUci, explanationDepth) { bestMoveUci, bestScore, humanScore, humanPv, depth ->
            // ★ 代际校验：如果走棋代际已变，说明用户又走了新棋，旧回调作废
            if (gen != moveGeneration) {
                Log.d(TAG, "★ 旧解说回调被丢弃 gen=$gen, current=$moveGeneration")
                setAnalyzing(false)
                return@evaluateMove
            }
            try {
                if (bestMoveUci.isNotEmpty()) {
                    // 更新劣势追踪
                    updateDisadvantageTracking(humanScore, board)
                    val evaluation = CommentaryEngine.explainHumanMove(
                        humanMoveUci, colorName, humanNotation,
                        bestMoveUci, humanScore, bestScore, depth, humanPv, boardBeforeMove
                    )
                    val entry = ExplanationEntry(
                        stepNumber = sideStep,
                        isHuman = true,
                        moveNotation = humanNotation,
                        text = evaluation,
                        boardFen = board.toFen(),
                        color = colorName,
                        totalStepIndex = totalStep
                    )
                    appendExplanation(entry)

                    // 记录走棋质量（用于终局总结）
                    // ★ 一致性闸门：引擎推荐的正是这一手 → 绝不算作失误（杜绝"推荐同一手又记它一笔亏分"）
                    val cpLoss = bestScore - humanScore
                    val quality = if (CommentaryEngine.sameAsRecommended(humanMoveUci, bestMoveUci))
                        CommentaryEngine.Quality.BRILLIANT
                    else CommentaryEngine.classifyQuality(cpLoss)
                    if (quality == CommentaryEngine.Quality.MISTAKE || quality == CommentaryEngine.Quality.BLUNDER) {
                        mistakeSteps.add(Triple(sideStep, humanNotation, Pair(quality, cpLoss)))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "evaluateHumanMoveAsync error: ${e.message}")
            } finally {
                setAnalyzing(false)
            }
        }
    }

    private fun evaluateHumanMoveThenEngineMove(boardBeforeMove: Board, humanMoveUci: String, humanNotation: String) {
        if (!engine.isEngineReady()) {
            requestEngineMove()
            return
        }
        setAnalyzing(true)

        // ★ 按方计数：当前走棋方的步数
        val moverColor = boardBeforeMove.currentPlayer
        val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
        val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
        val totalStep = currentStepIndex  // ★ 捕获当前总步数索引
        val gen = moveGeneration  // ★ 捕获代际令牌

        engine.evaluateMove(boardBeforeMove, humanMoveUci, explanationDepth) { bestMoveUci, bestScore, humanScore, humanPv, depth ->
            // ★ 代际校验：如果走棋代际已变，旧回调作废，但仍需触发AI走棋
            if (gen != moveGeneration) {
                Log.d(TAG, "★ 旧解说回调被丢弃(thenEngine) gen=$gen, current=$moveGeneration")
                setAnalyzing(false)
                if (!gameOver && isCurrentPlayerEngine() && engine.isEngineReady()) {
                    requestEngineMove()
                }
                return@evaluateMove
            }
            try {
                if (bestMoveUci.isNotEmpty()) {
                    // 更新劣势追踪
                    updateDisadvantageTracking(humanScore, board)
                    val evaluation = CommentaryEngine.explainHumanMove(
                        humanMoveUci, colorName, humanNotation,
                        bestMoveUci, humanScore, bestScore, depth, humanPv, boardBeforeMove
                    )
                    val entry = ExplanationEntry(
                        stepNumber = sideStep,
                        isHuman = true,
                        moveNotation = humanNotation,
                        text = evaluation,
                        boardFen = board.toFen(),
                        color = colorName,
                        totalStepIndex = totalStep
                    )
                    appendExplanation(entry)
                }
            } catch (e: Exception) {
                Log.e(TAG, "evaluateHumanMoveThenEngineMove error: ${e.message}")
            } finally {
                setAnalyzing(false)
            }
            // ★ 无论评价是否成功，只要轮到AI就走棋，绝不卡住
            if (!gameOver && isCurrentPlayerEngine() && engine.isEngineReady()) {
                requestEngineMove()
            }
        }
    }

    fun analyzeCurrentPosition() {
        // ★ 已停用：分析局面功能改为复盘模式，不再调用引擎分析
        // 原因：analyzePosition与searchBestMove共用引擎线路，容易导致卡死
        return
    }

    /** ★ 检查是否轮到AI走棋，如果是则主动触发（用于恢复棋局后） */
    fun checkAndTriggerAiMove() {
        if (gameOver || isSetupMode || engineThinking) return
        if (!engine.isEngineReady()) {
            pendingRetry = true
            return
        }
        // ★ 轮到用户走棋时预取云库，保证用户走完 AI 秒出
        maybePrefetchCloudBook()
        if (isCurrentPlayerEngine()) {
            requestEngineMove()
        }
    }

    /**
     * ★★★ 恢复前台同步（修复"切屏/小退/关窗回来 AI 乱走"的核心修复之二）：
     *
     * 现象：用户把 App 切到后台（Home/切屏/暂时不理），引擎子进程被系统限流或回收，
     * 回来看时 AI 已经走了一手"没脑子"的棋（如炮1平2送炮口）。
     * 根因：后台期间在途搜索超时（引擎死/被限流），旧逻辑直接"选第一个合法走法"硬落子。
     * 修复：① 引擎层已废除"第一个合法走法"兜底（见 JniEngine.doSearch）；
     *       ② 这里在回到前台时重同步：作废后台残留的陈旧搜索回调（代际+1）、复位思考开关、
     *          若轮到AI则用当前真实局面重新驱动搜索；若引擎进程已被系统回收，立即重启续走。
     */
    fun onAppResumed() {
        try {
            if (gameOver || isSetupMode) return
            if (isCurrentPlayerHuman()) {
                // 轮到人脑：只停掉后台残留的 ponder，避免切屏后引擎在后台空转耗电
                try { engine.stopPonder() } catch (_: Exception) {}
                return
            }
            val side = board.currentPlayer
            if (engineThinking) {
                // 引擎标记"思考中"：若该方引擎进程已不在（后台被系统回收），立即接管重启；
                // 否则搜索仍在健康进行，不打断（它会照常落出正确的一手）。
                if (!engine.isEngineReadyFor(side)) {
                    engineThinking = false
                    pendingRetry = false
                    Log.w(TAG, "★ 恢复前台：引擎标记思考中但进程已不在，强制重启${if (side == PieceColor.RED) "红" else "黑"}方")
                    restartEngineIfNeeded(side)
                }
                return
            }
            // 轮到AI且引擎空闲：作废后台期间残留的陈旧搜索回调，用当前真实局面重新驱动
            moveGeneration++
            engineThinking = false
            pendingRetry = false
            try { engine.stopPonder() } catch (_: Exception) {}
            if (engine.isEngineReadyFor(side)) {
                gameHandler.post {
                    if (!gameOver && !isCurrentPlayerHuman() && engine.isEngineReadyFor(side)) {
                        requestEngineMove()
                    }
                }
            } else if (engineInitCompleted) {
                Log.w(TAG, "★ 恢复前台：轮到AI但${if (side == PieceColor.RED) "红" else "黑"}方引擎不在，立即重启")
                restartEngineIfNeeded(side)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onAppResumed: ${e.message}", e)
        }
    }

    // ── 解惑分析（合并：猜谱 + 假设 + 复盘）──
    //
    // 选一步棋，同时告诉你：
    //   1. 引擎推荐走什么（猜谱对比）
    //   2. 你选的这步走了之后局面怎么样（假设分析）
    //   3. 对方会怎么应、后续怎么走（局面推演）
    //
    // 不实际走棋，保持当前局面不变，方便反复分析。
    fun jiedaoAnalysis(uciMove: String) {
        if (gameOver || isSetupMode) return
        if (!engine.isEngineReady()) {
            onWhatIfResult?.invoke("引擎未就绪，无法分析")
            return
        }

        val boardBefore = Board()
        boardBefore.loadFromFen(board.toFen())

        val move = Move.fromUci(uciMove, boardBefore)
        if (move == null) {
            onWhatIfResult?.invoke("走法无效: $uciMove")
            return
        }

        // 验证走法是当前方的
        val moverPiece = boardBefore.getPiece(move.from)
        if (moverPiece == null || moverPiece.color != boardBefore.currentPlayer) {
            onWhatIfResult?.invoke("这不是当前应走方的棋子")
            return
        }

        // 验证走法合法
        val legalMoves = boardBefore.getLegalMoves(boardBefore.currentPlayer)
        if (!legalMoves.any { it.from == move.from && it.to == move.to }) {
            onWhatIfResult?.invoke("这步棋不合法")
            return
        }

        val moverColor = moverPiece.color
        val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
        val userNotation = CommentaryEngine.uciToChineseNotation(uciMove, boardBefore)
        val captured = boardBefore.getPiece(move.to)

        onThinkingInfo?.invoke("【解惑】引擎思考中...")

        // ★ 第一步：搜当前局面，拿到引擎推荐的最佳走法（猜谱对比用）
        var bestMoveUciBefore = ""
        var bestScoreBefore = 0
        engine.searchBestMove(
            board = boardBefore,
            timeMs = 2000L,
            onThinking = { d, sd, sc, nodes, nps, pv, pvLines ->
                onThinkingInfo?.invoke("【解惑】第1步/共2步 深度$d/$sd")
                bestScoreBefore = sc
            },
            onResult = { bestMove ->
                bestMoveUciBefore = bestMove?.toUci() ?: ""

                // ★ 第二步：模拟走用户的走法，搜对方视角的局面评估
                val afterBoard = Board()
                afterBoard.loadFromFen(boardBefore.toFen())
                afterBoard.makeMoveUnchecked(move)

                val isCheck = afterBoard.isInCheck(afterBoard.currentPlayer)
                val isMate = afterBoard.isCheckmate(afterBoard.currentPlayer)

                val lastPvHolder = arrayOfNulls<String>(1)
                val lastScoreHolder = IntArray(1)
                engine.searchBestMove(
                    board = afterBoard,
                    timeMs = 3000L,
                    onThinking = { d, sd, sc, nodes, nps, pv, pvLines ->
                        onThinkingInfo?.invoke("【解惑】第2步/共2步 深度$d/$sd")
                        lastPvHolder[0] = pv
                        lastScoreHolder[0] = sc
                    },
                    onResult = { bestResponse ->
                        val sb = StringBuilder()
                        sb.append("【解惑】${colorName}若走 $userNotation\n")
                        sb.append("════════════════════════\n\n")

                        // ── 引擎推荐对比（猜谱部分）──
                        sb.append("◆ 引擎推荐\n")
                        if (bestMoveUciBefore.isNotEmpty()) {
                            val bestNotation = CommentaryEngine.uciToChineseNotation(bestMoveUciBefore, boardBefore)
                            val isMatch = bestMoveUciBefore == uciMove
                            if (isMatch) {
                                sb.append("  $bestNotation  ← 和你选的一样！\n")
                            } else {
                                sb.append("  $bestNotation\n")
                                sb.append("  你选的：$userNotation\n")
                            }
                            val mtb = com.pikafish.chess.engine.JniEngine.mateText(bestScoreBefore)
                            val scoreStr = mtb ?: "${if (bestScoreBefore >= 0) "+" else ""}$bestScoreBefore"
                            sb.append("  当前局面评分：$scoreStr\n")
                        } else {
                            sb.append("  （引擎未给出推荐）\n")
                        }
                        sb.append("\n")

                        // ── 走这步之后的局面（假设分析部分）──
                        sb.append("◆ 走后局面\n")
                        if (captured != null && captured.color != moverColor) {
                            sb.append("  吃掉对方${captured.displayName()}。\n")
                        }
                        if (isMate) {
                            sb.append("  绝杀！对方无解。\n")
                        } else if (isCheck) {
                            sb.append("  将军！对方必须应将。\n")
                        }

                        val score = lastScoreHolder[0]
                        // 注意：score 是从对方视角的评分，取反就是走棋方的视角
                        val scoreFromMover = -score
                        val mtm = com.pikafish.chess.engine.JniEngine.mateText(scoreFromMover)
                            val scoreStr = if (scoreFromMover != 0) (mtm ?: "${if (scoreFromMover >= 0) "+" else ""}$scoreFromMover") else "0"
                            val advantageSide = if ((scoreFromMover > 0) == (moverColor == PieceColor.RED)) "红方" else "黑方"
                            val absScore = kotlin.math.abs(scoreFromMover)
                            val assessment = when {
                                absScore <= 20 -> "双方势均力敌"
                                absScore <= 60 -> "${advantageSide}稍占优势"
                                absScore <= 120 -> "${advantageSide}略占上风"
                                absScore <= 250 -> "${advantageSide}明显占优"
                                absScore <= 500 -> "${advantageSide}大占优势"
                                else -> "${advantageSide}胜势已现"
                            }
                            sb.append("  局面评估：$assessment（评分 $scoreStr）\n")
                        sb.append("\n")

                        // ── 后续走法预测（PV展开）──
                        val pvStr = lastPvHolder[0]
                        if (!pvStr.isNullOrBlank()) {
                            val pvMoves = pvStr.trim().split("\\s+".toRegex()).filter { it.length >= 4 }
                            if (pvMoves.isNotEmpty()) {
                                sb.append("◆ 后续预测（最多5步）\n")
                                val simBoard = Board()
                                simBoard.loadFromFen(afterBoard.toFen())
                                val maxMoves = minOf(5, pvMoves.size)
                                for (i in 0 until maxMoves) {
                                    val uci = pvMoves[i]
                                    val simMove = Move.fromUci(uci, simBoard)
                                    if (simMove == null) break
                                    val lmvs = simBoard.getLegalMoves(simBoard.currentPlayer)
                                    if (!lmvs.any { it.from == simMove.from && it.to == simMove.to }) break
                                    val moveNotation = CommentaryEngine.uciToChineseNotation(uci, simBoard)
                                    val moveColorName = if (simBoard.currentPlayer == PieceColor.RED) "红" else "黑"
                                    val stepNum = i + 1
                                    sb.append("  ${stepNum}. ${moveColorName}方 $moveNotation\n")
                                    simBoard.makeMoveUnchecked(simMove)
                                }
                                sb.append("\n")
                            }
                        }

                        // ── 对方最佳应着──
                        if (bestResponse != null) {
                            val respNotation = CommentaryEngine.uciToChineseNotation(bestResponse.toUci(), afterBoard)
                            val respColorName = if (afterBoard.currentPlayer == PieceColor.RED) "红方" else "黑方"
                            sb.append("◆ 对方最佳应着\n")
                            sb.append("  $respColorName $respNotation\n")
                        }

                        onWhatIfResult?.invoke(sb.toString())
                    }
                )
            }
        )
    }

    // ── 棋谱导出 ──

    /**
     * 导出当前局面为FEN格式
     */
    fun exportFen(): String {
        return board.toFen()
    }

    /**
     * 导入FEN局面，重置游戏状态
     */
    fun importFen(fen: String): String {
        try {
            board.loadFromFen(fen)
            gameOver = false
            gameResult = ""
            engineThinking = false
            pendingRetry = false
            fenPuzzleMode = false   // 普通 FEN 导入默认不是习题（由「打开习题」另行置真）
            moveCount = 0
            redMoveCount = 0
            blackMoveCount = 0
            isViewingHistory = false
            currentDisadvantage = null
            warnedViolator = null

            boardSnapshots.clear()
            boardSnapshots.add(board.toFen())
            currentStepIndex = 0
            moveHistoryUci.clear()
            variationBranches.clear()
            explanationHistory.clear()
            openingAnnounced = false
            currentOpeningName = null

            onBoardChanged?.invoke()
            onStepChanged?.invoke()
            onExplanationHistoryChanged?.invoke()

            return "FEN局面已加载"
        } catch (e: Exception) {
            Log.e(TAG, "FEN导入失败: ${e.message}")
            return "FEN格式错误: ${e.message}"
        }
    }

    /**
     * 导出UCI走法列表（每行一个走法，兼容多种象棋软件导入）
     */
    fun exportUciMoves(): String {
        val sb = StringBuilder()
        sb.append("[Event \"黄昏象棋对局\"]\n")
        sb.append("[Date \"${java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()).format(java.util.Date())}\"]\n")
        sb.append("[FEN \"${boardSnapshots[0]}\"]\n\n")

        for (i in moveHistoryUci.indices) {
            sb.append("${i + 1}. ${moveHistoryUci[i]}\n")
        }
        return sb.toString()
    }

    /**
     * ★ WXF 记谱导出（与 xiaxiangqi 网站的“WXF 格式”一致）。
     * 标准中象联 WXF 坐标记谱：红方用中文数码（一二三…），黑方用阿拉伯数码（1 2 3…），
     * 每回合红黑成一行为对。复用中文记谱规则，保证导入回读可靠。
     */
    fun exportWxf(): String {
        val sb = StringBuilder()
        sb.append("[Event \"黄昏象棋对局\"]\n")
        sb.append("[FEN \"${boardSnapshots[0]}\"]\n\n")
        val replay = Board()
        replay.loadFromFen(initialFen)
        var step = 0
        var redNotation = ""
        for (uci in moveHistoryUci) {
            val mover = replay.currentPlayer
            val notation = CommentaryEngine.uciToChineseNotation(uci, replay)
            val m = Move.fromUci(uci, replay)
            if (m != null) replay.makeMoveUnchecked(m)
            if (mover == PieceColor.RED) {
                step++
                redNotation = notation
            } else {
                sb.append("${step}. $redNotation   $notation\n")
            }
        }
        return sb.toString()
    }

    /**
     * ★ 文字棋盘导出：把当前局面渲染成纯文本棋盘（顶层黑方、底层红方）。
     * 便于在任何纯文本环境（聊天、邮件、记事本）中展示局面。
     */
    fun exportTextBoard(): String {
        val fen = boardSnapshots.lastOrNull() ?: initialFen
        val rows = fen.split('/')
        val grid = Array(10) { arrayOfNulls<String>(9) }
        for (r in 0..9) {
            var c = 0
            for (ch in rows[r]) {
                if (c > 8) break
                if (ch.isDigit()) { c += ch.toString().toInt(); continue }
                grid[r][c] = ch.toString()
                c++
            }
        }
        val names = mapOf(
            "k" to "将", "a" to "士", "b" to "象", "n" to "马", "r" to "车",
            "c" to "砲", "p" to "卒",
            "K" to "帅", "A" to "仕", "B" to "相", "N" to "马", "R" to "車",
            "C" to "炮", "P" to "兵"
        )
        val sb = StringBuilder()
        sb.append("黑方（上）：车马象士将士象马车\n")
        sb.append("┌───────────────────────────────┐\n")
        for (r in 0..9) {
            sb.append("${10 - r}│")
            for (c in 0..8) {
                val p = grid[r][c]
                val ch = if (p == null) " ・ " else " ${names[p]!!} "
                sb.append(ch)
            }
            sb.append("│\n")
        }
        sb.append("└───────────────────────────────┘\n")
        sb.append("红方（下）：九 八 七 六 五 四 三 二 一\n")
        return sb.toString()
    }

    /**
     * 导出当前对局为TXT格式（纯中文记谱法，标准中国象棋棋谱格式）
     */
    /**
     * ★★ 合法走法主序列：只保留能从 initialFen 逐步合法落子的 UCI，
     *    丢弃混入的悬空/非法走法（如复盘分析残留的 h2e2）——
     *    这样导出的棋谱干净、自洽、可往返，绝不出现烂尾谱。
     */
    fun legalMoveHistory(): List<String> {
        val out = mutableListOf<String>()
        val b = Board()
        b.loadFromFen(initialFen)
        for (uci in moveHistoryUci) {
            try {
                val m = Move.fromUci(uci, b) ?: continue
                if (b.getPiece(m.from) == null) continue   // 起点无子 → 悬空/UCI，丢弃
                if (!b.makeMove(m)) continue               // 不是合法走法 → 丢弃
                out.add(uci)
            } catch (_: Exception) { continue }
        }
        return out
    }

    fun exportTxt(): String {
        val sb = StringBuilder()
        sb.append("黄昏象棋对局\n")
        sb.append("日期: ${java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm", java.util.Locale.CHINA).format(java.util.Date())}\n")
        sb.append("红方: ${if (redIsHuman) "人脑" else "AI"}\n")
        sb.append("黑方: ${if (blackIsHuman) "人脑" else "AI"}\n")
        sb.append("结果: ${if (gameOver) gameResult else "未结束"}\n")
        // ★ 文字棋谱抬头一律带起始 FEN（正式开局与半途/黑先开局都记录起点）：
        //   [SetUp]/[FEN] 为标准 PGN 头，兼容软件可据此还原局面，导入亦能回到该起点。
        sb.append("[SetUp \"1\"]\n")
        sb.append("[FEN \"${boardSnapshots[0]}\"]\n")
        sb.append("\n")

        // ★ 修复：直接使用 moveHistoryUci 回放，不再依赖 detectMove 推断
        // detectMove 在快照重复或异常时会返回 null，导致编号错乱
        val replayBoard = Board()
        replayBoard.loadFromFen(initialFen)
        // ★ 黑方先行：起始 FEN 执子方为 'b' 时，首个回合红方未有落子——
        //   红方位置用 6 个冒号占住（抬头已给出 FEN，读者自明），黑方着法仍落在黑列。
        //   例： 1. :::::: 炮8平4   2. 马二进三 马8进7 ...
        val startsBlack = (boardSnapshots.firstOrNull() ?: initialFen).trim().split(' ').getOrNull(1) == "b"
        var fullMove = 1
        var pendingRed = false      // 红方已走，等待黑方配对
        var blackFirstShown = false // 黑先：红空位占位是否已打出

        val validUci = legalMoveHistory()   // ★ 只导合法主序列，杜绝 h2e2 烂尾

        for (i in validUci.indices) {
            val uci = validUci[i]
            val isRed = (i % 2 == 0) != startsBlack
            val notation = CommentaryEngine.uciToChineseNotation(uci, replayBoard)
            val m = Move.fromUci(uci, replayBoard)
            if (m != null) {
                replayBoard.makeMoveUnchecked(m)
            }

            if (isRed) {
                sb.append("$fullMove. ")
                sb.append(notation)
                sb.append("  ")
                pendingRed = true
                fullMove++
            } else if (startsBlack && !blackFirstShown) {
                // 黑先首回合：红方空位 → 6 个冒号占位，黑方着法落黑列，回合号从 1 起
                sb.append("1. ")
                sb.append("::::::")
                sb.append("  ")
                sb.append(notation)
                sb.append("\n")
                blackFirstShown = true
                pendingRed = false
                fullMove++   // 下一回合红方着法即为 2.
            } else {
                sb.append(notation)
                sb.append("\n")
                pendingRed = false
            }
        }
        // 如果最后一步是红方（没有配对的黑色），补一个换行
        if (pendingRed) {
            sb.append("\n")
        }

        // ★ 追加变着信息
        if (variationBranches.isNotEmpty()) {
            sb.append("\n── 变着记录 ──\n")
            for (vb in variationBranches) {
                val branchBoard = Board()
                // ★ branchStep = 分支点的步数索引，变着从此局面的下一步开始
                // boardSnapshots[branchStep] 就是分支时的棋盘局面
                val fenBefore = if (vb.branchStep >= 0 && vb.branchStep < boardSnapshots.size) {
                    boardSnapshots[vb.branchStep]
                } else {
                    initialFen
                }
                branchBoard.loadFromFen(fenBefore)

                sb.append("[第${vb.branchStep}步变着] ")
                for (uci in vb.truncatedMoves) {
                    val notation = CommentaryEngine.uciToChineseNotation(uci, branchBoard)
                    sb.append(notation).append(" ")
                    val m = Move.fromUci(uci, branchBoard)
                    if (m != null) branchBoard.makeMoveUnchecked(m)
                }
                sb.append("\n")
            }
        }

        return sb.toString()
    }

    /**
     * 导出当前对局为PGN格式（含变招分支，使用RAV括号格式）
     * PGN标准中，变招用括号 ( ) 表示，称为RAV (Recursive Annotation Variation)
     * ★ 使用UCI走法（如 h2e2）而非中文记谱，确保导入导出可靠
     */
    fun exportPgn(): String {
        val sb = StringBuilder()
        sb.append("[Event \"黄昏象棋对局\"]\n")
        sb.append("[Site \"Dusk Chess\"]\n")
        sb.append("[Date \"${java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()).format(java.util.Date())}\"]\n")
        sb.append("[Red \"${if (redIsHuman) "人脑" else "AI"}\"]\n")
        sb.append("[Black \"${if (blackIsHuman) "人脑" else "AI"}\"]\n")
        sb.append("[Result \"${if (gameOver) gameResult else "*"}\"]\n")
        // ★ 文字棋谱抬头一律带起始 FEN（正式开局与半途/黑先开局都记录起点）：
        //   [SetUp]/[FEN] 为标准的 PGN 自定义头，任何 PGN 兼容软件都能据此还原局面。
        sb.append("[SetUp \"1\"]\n")
        sb.append("[FEN \"${boardSnapshots[0]}\"]\n")
        sb.append("\n")

        // ★ 修复：直接使用 moveHistoryUci，不再依赖 detectMove
        // 同时剔除悬空/烂尾走法（如复盘残留的 h2e2），保证 PGN 主线每一步都能在 FEN 起点连续合法落子
        val validSet = legalMoveHistory().toSet()
        val movesUci = moveHistoryUci.toMutableList().also { list ->
            for (i in list.indices.reversed()) if (list[i] !in validSet) list.removeAt(i)
        }

        // ── 递归渲染一个变招 RAV（含嵌套子树），输出 PGN 括号 + 标签注释 ──
        fun renderVariationPgn(vb: VariationBranch): String {
            val sb = StringBuilder()
            val label = vb.label.ifBlank { "?" }
            sb.append("( {${label}} ")
            // 遍历本变招走法；走完第 N 手后，若存在 branchStep==N 的嵌套子变招，则在该处递归插入 RAV。
            for (uciIndex in vb.truncatedMoves.indices) {
                sb.append(vb.truncatedMoves[uciIndex]).append(" ")
                val played = uciIndex + 1   // 已走手数
                for (sv in vb.subVariations.filter { it.branchStep == played }.sortedBy { it.label }) {
                    sb.append(renderVariationPgn(sv)).append(" ")
                }
            }
            // 变招结束处也可能挂子变招（分支点=总手数+1 之外，通常发生在"切到某便着后再走了一步"场景）
            for (sv in vb.subVariations.filter { it.branchStep > vb.truncatedMoves.size }.sortedBy { it.label }) {
                sb.append(renderVariationPgn(sv)).append(" ")
            }
            sb.append(")")
            return sb.toString()
        }

        // 按步骤输出走法，并在变招分支处插入RAV括号
        val variationMap = variationBranches.groupBy { it.branchStep }

        // ★ 第0步(初始局面)上挂着的变招——即“第一回合开局的多种先手”：
        //   如主线是兵七进一(仙人指路)，同时还有马八进七(起马)等开局选择。
        //   必须放在第一手走法之前输出，重新导入时才能回到 branchStep=0，
        //   否则两种开局在导出/导入往返中会丢失其中一个。
        variationMap[0]?.sortedBy { it.label }?.forEach { branch ->
            sb.append(renderVariationPgn(branch)).append(" ")
        }

        // ★ 黑方先行：起始 FEN 执子方为 'b' 时，本局首个着法是黑方。
        //   按 PGN 标准（§8.2.2）用"回合号+三点(1...)"标记黑方首着以区别红先的"1."，
        //   更红首发第一回合的回合号随之顺延（例如 1... 炮8平4 2. 马二进三 马8进7 ...）。
        val startsBlack = (boardSnapshots.firstOrNull() ?: initialFen).trim().split(' ').getOrNull(1) == "b"
        var moveIndex = 0
        var fullMove = 1
        while (moveIndex < movesUci.size) {
            val isRed = (moveIndex % 2 == 0) != startsBlack
            when {
                isRed -> sb.append("$fullMove. ")
                moveIndex == 0 -> sb.append("$fullMove... ")   // 黑先：黑方首着用"回合号+三点"占住，红方位留空
                else -> { /* 黑方后续着法跟在红方回合号后，无需再编号 */ }
            }
            sb.append("${movesUci[moveIndex]} ")
            // ★ 无损往返：把导入时收集的主线注释 {…} 写回 PGN（下标对齐 moveHistoryUci）
            moveComments[moveIndex]?.let { c -> if (c.isNotBlank()) sb.append("{$c} ") }

            // 检查是否有变招分支
            val branchStep = moveIndex + 1
            val branches = variationMap[branchStep]
            if (branches != null) {
                for (branch in branches.sortedBy { it.label }) {
                    sb.append(renderVariationPgn(branch)).append(" ")
                }
            }

            if (isRed || moveIndex == 0) fullMove++
            moveIndex++
        }
        sb.append(if (gameOver) gameResult else "*")
        sb.append("\n")
        return sb.toString()
    }

    // ── ★ 固定保存格式：TXT + 变招（中文记谱）──

    /**
     * ★ 生成"中文记谱 + 变招"的固定保存格式文本（TXT记谱）。
     * 主线用标准中文记谱（红用 一~九、黑用 1~9），每个完整回合一行；
     * 变招分支以括号“（ … ）”内联在分支点所在回合之后，仅在存在变招时才输出括号；
     * 无变招时只有主线文本。该文本可作为宝库存档的唯一真相源，往返可靠。
     */
    fun buildGivingTxt(): String {
        try {
            if (moveHistoryUci.isEmpty()) return ""
            val sb = StringBuilder()
            val board = Board()
            board.loadFromFen(initialFen)
            val variationMap = variationBranches.groupBy { it.branchStep }  // branchStep = 已走子数
            var stepNum = 1
            var any = false
            val validSet = legalMoveHistory().toSet()   // ★ 存档只拿合法主序列，剔除烂尾/悬空走法
            // 递归渲染一个变招（含嵌套子树）为 "(L: 走法 ...)" 中文记谱
            fun renderVariationTxt(vb: VariationBranch): String {
                val b = Board()
                b.loadFromFen(board.toFen())
                val parts = mutableListOf<String>()
                for (mUci in vb.truncatedMoves) {
                    val n = CommentaryEngine.uciToChineseNotation(mUci, b)
                    val m = Move.fromUci(mUci, b)
                    if (m != null) {
                        b.makeMoveUnchecked(m)
                        parts.add(n)
                    }
                }
                if (parts.isEmpty()) return ""
                var inner = parts.joinToString(" ")
                // 嵌套子变招：在本变招对应走子阶段之后并列嵌套括号（纯阅读展示，往返以PGN/JSON为准）
                for (sv in vb.subVariations.sortedBy { it.branchStep }) {
                    inner += " ${renderVariationTxt(sv)}"
                }
                return "($inner)"
            }

            // ★ 第0步(初始局面)挂着的变招：即“第一回合两种先手”(如兵七进一 vs 马八进七)。
            //   放在第一回合正文之前输出，保证开局多个先手在 TXT 存档里也不丢。
            val rootVars = variationMap[0] ?: emptyList()
            for (rv in rootVars.sortedBy { it.label }) {
                val rootTxt = renderVariationTxt(rv)
                if (rootTxt.isNotEmpty()) {
                    sb.append(rootTxt).append("  ")
                    any = true
                }
            }

            for (i in moveHistoryUci.indices) {
                val uci = moveHistoryUci[i]
                // ★ 悬空/烂尾走法（如复盘残留的 h2e2）绝不写入存档，保证主线每一步都能在 FEN 起点连续合法落子
                if (uci !in validSet) continue
                val notation = CommentaryEngine.uciToChineseNotation(uci, board)
                if (i % 2 == 0) {
                    if (any) sb.append("\n")
                    sb.append("$stepNum. ")
                }
                sb.append(notation).append("  ")
                any = true

                // 变招分支：插在走完第 i+1 步之后，仅在有变招时才输出
                val branches = variationMap[i + 1]
                if (branches != null) {
                    for (br in branches.sortedBy { it.label }) {
                        val txt = renderVariationTxt(br)
                        if (txt.isNotEmpty()) {
                            sb.append(" ").append(txt).append("  ")
                        }
                    }
                }

                val m = Move.fromUci(uci, board)
                if (m != null) board.makeMoveUnchecked(m)
                if (i % 2 == 1) stepNum++
            }
            return if (any) sb.toString().trim() else ""
        } catch (e: Exception) {
            Log.e(TAG, "buildGivingTxt: ${e.message}", e)
            return ""
        }
    }

    /**
     * ★ 从"中文记谱 + 变招"文本（buildGivingTxt 的产物）恢复对局。
     * 复用 PGN 主线/RAV 变招解析链路（支持中文记谱 + 括号变招），
     * 把主线走法与变招分支一并还原到当前棋局。
     * @return 成功导入的主线步数；<=0 表示失败/无有效走法
     */
    fun loadFromGivingTxt(giving: String): Int {
        if (giving.isBlank()) return 0
        val msg = importPgn(giving)
        val m = Regex("成功导入(\\d+)步").find(msg)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** 当前对局是否存在变招分支 */
    fun variationBranchesEmpty(): Boolean = variationBranches.isEmpty()

    /** 当前对局变招分支数（供导出面板展示“含 N 个变招”，让用户一眼知道变招有没有带上） */
    fun variationCount(): Int = variationBranches.size

    // ── PGN导入（支持RAV变着分支 + 中文记谱 + UCI走法）──

    /**
     * 导入PGN格式棋谱
     * 支持：
     * - PGN标准头信息（包括FEN标签自定义初始局面）
     * - UCI走法（如 b2e2）
     * - 中文记谱（如 炮二平五）
     * - RAV变着分支（括号格式）
     *
     * @param pgn PGN格式文本
     * @return 导入结果消息
     */
    /** ★ 最近一次 PGN 导入的头信息（红黑名/结果/日期/赛事等），供主界面「信息」按钮展示来龙去脉。 */
    @Volatile var lastPgnHeader: MutableMap<String, String>? = null

    fun importPgn(pgn: String): String {
        try {
            // 1. 解析PGN头信息
            val headers = mutableMapOf<String, String>()
            val moveText = StringBuilder()
            val lines = pgn.lines()
            var inMoves = false

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[")) {
                    // 解析头信息
                    val match = Regex("""\[(\w+)\s+"(.*)"\]""").find(trimmed)
                    if (match != null) {
                        headers[match.groupValues[1]] = match.groupValues[2]
                    }
                    continue
                }
                if (trimmed.isEmpty() && !inMoves) {
                    inMoves = true
                    continue
                }
                if (inMoves || !trimmed.startsWith("[")) {
                    moveText.append(" ").append(trimmed)
                }
            }
            lastPgnHeader = headers

            // 2. 初始化棋盘
            newGame()
            val fenHeader = headers["FEN"]
            if (fenHeader != null && fenHeader.isNotEmpty()) {
                board.loadFromFen(fenHeader)
                boardSnapshots.clear()
                boardSnapshots.add(board.toFen())
                currentStepIndex = 0
                moveCount = 0
                moveHistoryUci.clear()
            }

            // 3. 解析走法（含RAV变着）
            val parseResult = parsePgnMoves(moveText.toString(), board)

            // 4. 回放主线走法
            var successCount = 0
            for (uci in parseResult.mainLineMoves) {
                val move = Move.fromUci(uci, board)
                if (move != null && board.getLegalMoves(board.currentPlayer).any { it.toUci() == uci }) {
                    val boardBefore = Board()
                    boardBefore.loadFromFen(board.toFen())
                    val notation = CommentaryEngine.uciToChineseNotation(uci, boardBefore)
                    board.makeMove(move)
                    moveCount++
                    if (move.piece.color == PieceColor.RED) redMoveCount++ else blackMoveCount++
                    val sideStep = if (move.piece.color == PieceColor.RED) redMoveCount else blackMoveCount
                    val colorName = if (move.piece.color == PieceColor.RED) "红方" else "黑方"
                    boardSnapshots.add(board.toFen())
                    currentStepIndex = boardSnapshots.size - 1
                    moveHistoryUci.add(uci)
                    // ★ 记录棋谱条目，使棋谱面板能显示导入的走法
                    recordMoveEntry(notation, colorName, sideStep, false)
                    successCount++
                } else {
                    Log.w(TAG, "PGN导入: 走法 $uci 无效，停止回放")
                    break
                }
            }

            // 5. 导入变着分支（树状：顶层分支点=主线绝对步数；解析时相对偏移需换算）
            // 注意：parser 给顶层变招的 branchStep 是"主线当前已走手数"（即绝对步数，因为主线从0手对齐）。
            // 嵌套子变招的 branchStep 是相对父变招 moves 的偏移，保持原样即可。
            for (variation in parseResult.variations) {
                variationBranches.add(
                    VariationBranch(
                        variation.branchStep,
                        variation.truncatedMoves,
                        variation.label,
                        variation.subVariations.toMutableList()
                    )
                )
            }

            // 6. 收集主线注释 {…}（变招内括号与标签在放弃时一并剔除，保证注释下标对齐 moveHistoryUci）
            moveComments.clear()
            try { moveComments.putAll(extractMainlineComments(moveText.toString())) } catch (_: Exception) {}

            // 7. 检查游戏是否结束（静默模式：只设置状态，不弹窗）
            checkGameOver(silent = true)

            // ★ 导入后跳到起始局面，让用户可以逐步前进播放
            currentStepIndex = 0
            board.loadFromFen(boardSnapshots[0])
            isViewingHistory = boardSnapshots.size > 1

            // ★ 识别开局：让导入的棋谱也能立即显示开局名 + 战略要点注解（而非只有棋谱行）
            identifyOpening()

            onBoardChanged?.invoke()
            onStepChanged?.invoke()
            onExplanationHistoryChanged?.invoke()

            val varInfo = if (parseResult.variations.isNotEmpty()) "，${parseResult.variations.size}个变着分支" else ""
            return "成功导入${successCount}步棋谱${varInfo}，点击前进逐步播放"
        } catch (e: Exception) {
            Log.e(TAG, "PGN导入失败: ${e.message}")
            return "导入失败: ${e.message}"
        }
    }

    // ── 棋谱旁白（CBL 注解）──

    /**
     * 设置棋谱旁白数据（导入 CBL 棋谱时调用）。
     */
    fun setQipuComments(
        intro: String,
        end: String,
        moveComments: Map<Int, String>,
        commentaryBlocks: List<com.pikafish.chess.qipu.CblDecoder.CommentaryBlock> = emptyList()
    ) {
        qipuComments = if (intro.isNotEmpty() || end.isNotEmpty() || moveComments.isNotEmpty() || commentaryBlocks.isNotEmpty()) {
            QipuComments(intro, end, moveComments, commentaryBlocks)
        } else {
            null
        }
    }

    /**
     * ★ 直接导入 CBL 棋局：不再走"转 PGN 再重放"的有损链。
     * 把主线回放 + 走法旁白/中局旁白一并写入，并接入注释往返（导出 PGN 时也能带出注解）。
     */
    fun importCblGame(game: com.pikafish.chess.qipu.CblDecoder.CblGame): String {
        try {
            newGame()
            // 残局初始局面（含先行方）；标准开局跳过
            val startFen = game.fenBoard?.takeIf { it.isNotBlank() }
            if (startFen != null) {
                val withSide = if (startFen.contains(" ")) startFen else "$startFen ${if (game.moversBlack) "b" else "w"}"
                board.loadFromFen(withSide)
                boardSnapshots.clear()
                boardSnapshots.add(board.toFen())
                currentStepIndex = 0
                moveCount = 0
                moveHistoryUci.clear()
            }
            // 回放主线
            var success = 0
            for (uci in game.movesUci) {
                val move = Move.fromUci(uci, board)
                if (move != null && board.getLegalMoves(board.currentPlayer).any { it.toUci() == uci }) {
                    val boardBefore = Board(); boardBefore.loadFromFen(board.toFen())
                    val notation = CommentaryEngine.uciToChineseNotation(uci, boardBefore)
                    board.makeMove(move)
                    moveCount++
                    if (move.piece.color == PieceColor.RED) redMoveCount++ else blackMoveCount++
                    val colorName = if (move.piece.color == PieceColor.RED) "红方" else "黑方"
                    val sideStep = if (move.piece.color == PieceColor.RED) redMoveCount else blackMoveCount
                    boardSnapshots.add(board.toFen())
                    currentStepIndex = boardSnapshots.size - 1
                    moveHistoryUci.add(uci)
                    recordMoveEntry(notation, colorName, sideStep, false)
                    success++
                } else {
                    break
                }
            }
            // 走法旁白 -> 注释往返映射（键 = 走法下标），保证导出 PGN 时注解不丢
            moveComments.clear()
            for ((k, v) in game.moveComments) {
                if (k in moveHistoryUci.indices && v.isNotBlank()) moveComments[k] = v
            }
            // CBL 旁白挂载（开局 / 结局 / 逐坨支节）
            setQipuComments(game.introComment, game.endComment, game.moveComments, game.commentaryBlocks)

            currentStepIndex = 0
            board.loadFromFen(boardSnapshots[0])
            isViewingHistory = boardSnapshots.size > 1
            identifyOpening()
            onBoardChanged?.invoke()
            onStepChanged?.invoke()
            onExplanationHistoryChanged?.invoke()
            val ann = if (moveComments.isNotEmpty()) "，含 ${moveComments.size} 条图解注解" else ""
            return "成功导入${success}步棋谱$ann"
        } catch (e: Exception) {
            return "CBL导入失败: ${e.message}"
        }
    }

    /**
     * 获取开局旁白（仅在起始局面且开关打开时有效）。
     */
    fun getIntroComment(): String? {
        if (!qipuCommentaryEnabled) return null
        val c = qipuComments ?: return null
        if (c.intro.isEmpty()) return null
        // 只在起始局面（第0步）显示开局旁白
        if (currentStepIndex != 0) return null
        return c.intro
    }

    /**
     * 获取结局旁白（仅在最后一步且开关打开时有效）。
     */
    fun getEndComment(): String? {
        if (!qipuCommentaryEnabled) return null
        val c = qipuComments ?: return null
        if (c.end.isEmpty()) return null
        // 只在最后一步显示结局旁白
        if (currentStepIndex < boardSnapshots.size - 1) return null
        return c.end
    }

    /**
     * 获取当前步的旁白。
     * currentStepIndex 表示走完第几步后的局面（0=初始，1=第1步后...）
     * 第 N 步后的旁白对应 moveComments[N-1]（走法索引从0开始）。
     */
    fun getCurrentMoveComment(): String? {
        // 优先 PGN 导入的 {…} 注释（无需旁白开关，进得来就要看得见）
        val imported = moveComments[currentStepIndex - 1]
        if (!imported.isNullOrBlank()) return imported
        if (!qipuCommentaryEnabled) return null
        val c = qipuComments ?: return null
        if (c.moveComments.isEmpty()) return null
        // currentStepIndex=0 是初始局面，没有对应走法
        // currentStepIndex=1 对应第1步走法（索引0）
        val moveIndex = currentStepIndex - 1
        if (moveIndex < 0) return null
        return c.moveComments[moveIndex]
    }

    /**
     * PGN走法解析结果
     */
    private data class PgnParseResult(
        val mainLineMoves: List<String>,          // 主线UCI走法
        val variations: List<VariationBranch>     // 变着分支（树状，含嵌套子树与标签）
    )

    /**
     * 解析PGN走法文本，提取主线走法和RAV变着（递归还原嵌套树与标签）。
     * ★ 解析内核已抽到 NotationParser：主界面导入与 CBL 合成区导出共用同一份实现，
     *   避免两处各写一套导致「导入能认、合成认不出」的分叉。
     */
    private fun parsePgnMoves(text: String, initialBoard: Board): PgnParseResult {
        val parsed = NotationParser.parse(text, initialBoard.toFen())
        fun conv(v: NotationParser.Variation): VariationBranch =
            VariationBranch(v.branchStep, v.moves, v.label, v.sub.map { conv(it) }.toMutableList())
        return PgnParseResult(parsed.mainLine, parsed.variations.map { conv(it) })
    }

    /** 提取 PGN 主线 {…} 注释（下标对齐主线走法）。委托给 NotationParser。 */
    private fun extractMainlineComments(moveText: String): Map<Int, String> =
        NotationParser.extractMainlineComments(moveText)

    /**
     * 将中文记谱转换为UCI走法
     * 支持：
     * - 红方记谱：炮二平五、车九进一、马八进七、兵七进一 等
     * - 黑方记谱：炮2平5、车1进1、马8进7、卒7进1 等
     * - 前后标记：前车进一、后马退三 等
     *
     * @param notation 中文记谱字符串
     * @param board 当前棋盘
     * @return UCI走法字符串，如无法匹配返回null
     */
    private fun chineseNotationToUci(notation: String, board: Board): String? {
        if (notation.length < 4) return null

        val isRed = board.currentPlayer == PieceColor.RED
        // ★ 红方列号/步数同样兼容半角(1-9)与全角(１-９)数字（与黑方 BLACK_COL_MAP/BLACK_STEP_MAP 对齐）。
        val redColMap = mapOf(
            '九' to 0, '八' to 1, '七' to 2, '六' to 3, '五' to 4, '四' to 5, '三' to 6, '二' to 7, '一' to 8,
            '1' to 0, '2' to 1, '3' to 2, '4' to 3, '5' to 4, '6' to 5, '7' to 6, '8' to 7, '9' to 8,
            '１' to 0, '２' to 1, '３' to 2, '４' to 3, '５' to 4, '６' to 5, '７' to 6, '８' to 7, '９' to 8
        )
        val blackColMap = BLACK_COL_MAP
        val redStepMap = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9,
            '１' to 1, '２' to 2, '３' to 3, '４' to 4, '５' to 5, '６' to 6, '７' to 7, '８' to 8, '９' to 9
        )

        // 检查是否带前后中标记（同列同型两子用"前/后"，三子用"前/中/后"）
        val hasFrontBack = notation.startsWith("前") || notation.startsWith("中") || notation.startsWith("后")

        // 提取棋子名称（"前兵平五"→"兵"、"后卒进１"→"卒"）
        val pieceName = if (hasFrontBack) {
            notation.substring(1, 2)
        } else {
            notation.substring(0, 1)
        }

        // 棋子名称映射
        val pieceTypeMap = mapOf(
            "车" to PieceType.ROOK, "車" to PieceType.ROOK,
            "馬" to PieceType.KNIGHT, "马" to PieceType.KNIGHT,
            "炮" to PieceType.CANNON, "砲" to PieceType.CANNON,
            "兵" to PieceType.PAWN, "卒" to PieceType.PAWN,
            "帅" to PieceType.KING, "將" to PieceType.KING, "将" to PieceType.KING,
            "仕" to PieceType.ADVISOR, "士" to PieceType.ADVISOR,
            "相" to PieceType.ELEPHANT, "象" to PieceType.ELEPHANT
        )

        val pieceType = pieceTypeMap[pieceName] ?: return null
        val color = board.currentPlayer

        if (hasFrontBack) {
            // ★★★ 前/中/后 只用于"同一列上有 ≥2 枚同类己方棋子"时的定位。
            //   旧实现是全盘取行号最极端那枚，会把别的列的兵错当成"前兵"，例如"前兵平五"
            //   被走成"七路兵平五"而把六路上的真"前兵"落下，导致后续所有谱都对不上而断裂。
            //   正确做法：按列分组，在同一列内区分 前(靠近对方)/中/后(靠近己方)，再试探合法走法。
            val prefix = notation.substring(0, 1)   // 前/中/后
            val action = notation.substring(2, 3)
            val dest = notation.substring(3)

            val redForward = color == PieceColor.RED   // 红"前"=行号小(更靠近对方底线)；黑"前"=行号大(更靠近对方底线)，与导出端一致
            val byCol = mutableMapOf<Int, MutableList<Position>>()
            for (r in 0..9) for (c in 0..8) {
                val p = board.getPiece(Position(r, c))
                if (p != null && p.color == color && p.type == pieceType) {
                    byCol.getOrPut(c) { mutableListOf() }.add(Position(r, c))
                }
            }

            // "前/后"需要同列≥2枚，"中"需要同列≥3枚
            val needCnt = if (prefix == "中") 3 else 2
            for ((_, colPieces) in byCol) {
                if (colPieces.size < needCnt) continue
                // 同列内排序：红方(底边朝上行)行号越小越靠前→升序；黑方行号越大越靠前→降序
                colPieces.sortWith(Comparator { a, b ->
                    if (redForward) a.row - b.row else b.row - a.row
                })
                val idx = when (prefix) {
                    "前" -> 0
                    "后" -> colPieces.size - 1
                    else -> 1   // 中
                }
                val selectedPos = colPieces[idx]
                val uci = findMoveByNotation(selectedPos, pieceType, color, action, dest, board)
                if (uci != null) return uci
            }
            return null
        }

        // 解析列号
        val colChar = notation[1]
        val colMap = if (isRed) redColMap else blackColMap
        val fromCol = colMap[colChar] ?: return null

        // 解析动作
        val action = notation.substring(2, 3)  // 进/退/平
        val dest = notation.substring(3)        // 目标列或步数

        // 找到该列上对应类型的棋子
        val fromPos = findPieceOnColumn(pieceType, color, fromCol, board) ?: return null

        return findMoveByNotation(fromPos, pieceType, color, action, dest, board)
    }

    /**
     * 在指定列上找到指定类型的棋子
     * 如果同列有多枚同类型棋子，返回离对方最近的那枚（红方选行号最小的，黑方选行号最大的）
     * 注：标准记谱中同列两子应用"前/后"区分，此函数仅处理单子情况
     */
    private fun findPieceOnColumn(type: PieceType, color: PieceColor, col: Int, board: Board): Position? {
        var found: Position? = null
        for (r in 0..9) {
            val p = board.getPiece(Position(r, col))
            if (p != null && p.color == color && p.type == type) {
                // 红方取行号最小（最靠前），黑方取行号最大（最靠前）
                if (found == null) {
                    found = Position(r, col)
                } else if (color == PieceColor.RED && r < found.row) {
                    found = Position(r, col)
                } else if (color == PieceColor.BLACK && r > found.row) {
                    found = Position(r, col)
                }
            }
        }
        return found
    }

    /**
     * 根据中文记谱的组成部分查找UCI走法
     */
    private fun findMoveByNotation(
        fromPos: Position, pieceType: PieceType, color: PieceColor,
        action: String, dest: String, board: Board
    ): String? {
        val isRed = color == PieceColor.RED
        // ★ 红方列号/步数同样兼容半角与全角数字（与 chineseNotationToUci / 黑方映射对齐）。
        val redColMap = mapOf(
            '九' to 0, '八' to 1, '七' to 2, '六' to 3, '五' to 4, '四' to 5, '三' to 6, '二' to 7, '一' to 8,
            '1' to 0, '2' to 1, '3' to 2, '4' to 3, '5' to 4, '6' to 5, '7' to 6, '8' to 7, '9' to 8,
            '１' to 0, '２' to 1, '３' to 2, '４' to 3, '５' to 4, '６' to 5, '７' to 6, '８' to 7, '９' to 8
        )
        val blackColMap = BLACK_COL_MAP
        val redStepMap = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9,
            '１' to 1, '２' to 2, '３' to 3, '４' to 4, '５' to 5, '６' to 6, '７' to 7, '８' to 8, '９' to 9
        )

        // 获取该棋子的所有合法走法
        val legalMoves = board.getLegalMoves(color).filter { it.from == fromPos }
        if (legalMoves.isEmpty()) return null

        when (action) {
            "平" -> {
                // 平移到指定列
                val destChar = dest.first()
                val destCol = (if (isRed) redColMap else blackColMap)[destChar] ?: return null
                val move = legalMoves.find { it.to.col == destCol && it.to.row == fromPos.row }
                return move?.toUci()
            }
            "进" -> {
                // 红方进 = 行号减小，黑方进 = 行号增大
                if (pieceType == PieceType.KNIGHT || pieceType == PieceType.ELEPHANT || pieceType == PieceType.ADVISOR) {
                    // 马、相、士：进到指定列
                    val destChar = dest.first()
                    val destCol = (if (isRed) redColMap else blackColMap)[destChar] ?: return null
                    val move = legalMoves.find { it.to.col == destCol &&
                        if (isRed) it.to.row < fromPos.row else it.to.row > fromPos.row }
                    return move?.toUci()
                } else {
                    // 车、炮、兵、帅：前进指定步数
                    val destChar = dest.first()
                    val steps = if (isRed) {
                        redStepMap[destChar] ?: destChar.digitToIntOrNull()
                    } else {
                        blackSteps(destChar)
                    } ?: return null
                    val targetRow = if (isRed) fromPos.row - steps else fromPos.row + steps
                    val move = legalMoves.find { it.to.row == targetRow && it.to.col == fromPos.col }
                    return move?.toUci()
                }
            }
            "退" -> {
                // 红方退 = 行号增大，黑方退 = 行号减小
                if (pieceType == PieceType.KNIGHT || pieceType == PieceType.ELEPHANT || pieceType == PieceType.ADVISOR) {
                    val destChar = dest.first()
                    val destCol = (if (isRed) redColMap else blackColMap)[destChar] ?: return null
                    val move = legalMoves.find { it.to.col == destCol &&
                        if (isRed) it.to.row > fromPos.row else it.to.row < fromPos.row }
                    return move?.toUci()
                } else {
                    val destChar = dest.first()
                    val steps = if (isRed) {
                        redStepMap[destChar] ?: destChar.digitToIntOrNull()
                    } else {
                        blackSteps(destChar)
                    } ?: return null
                    val targetRow = if (isRed) fromPos.row + steps else fromPos.row - steps
                    val move = legalMoves.find { it.to.row == targetRow && it.to.col == fromPos.col }
                    return move?.toUci()
                }
            }
        }
        return null
    }

    /**
     * 导入中文记谱TXT格式棋谱
     * 支持：1. 炮二平五  马8进7  2. 马二进三  车9平8 ...
     * ★ 同时支持变着记录：[第N步变着] 走法1 走法2 ...
     */
    fun importChineseNotationTxt(content: String): String {
        try {
            newGame()

            // ★ 分离变着部分和主线部分
            val variationSection = "── 变着记录 ──"
            val parts = content.split(variationSection)
            val mainContent = parts[0]
            val variationContent = if (parts.size > 1) parts[1] else ""

            // 提取所有中文记谱走法（主线）
            // ★ 兼容全角数字（如「卒３进１」「炮２平５」），否则这类含全角着法的谱会整步被吞、漏走法。
            val moves = mutableListOf<String>()
            val notationPattern = Regex("([前后]?[车車馬马炮砲兵卒帅將将士仕相象])([一二三四五六七八九1-9１-９])([进退平])([一二三四五六七八九1-9１-９])")

            for (match in notationPattern.findAll(mainContent)) {
                val notation = match.value
                val uci = chineseNotationToUci(notation, board)
                if (uci != null) {
                    val move = Move.fromUci(uci, board)
                    if (move != null && board.getLegalMoves(board.currentPlayer).any { it.toUci() == uci }) {
                        moves.add(uci)
                        board.makeMove(move)
                        moveCount++
                        val moverColor = move.piece.color
                        if (moverColor == PieceColor.RED) redMoveCount++ else blackMoveCount++
                        val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
                        val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
                        boardSnapshots.add(board.toFen())
                        currentStepIndex = boardSnapshots.size - 1
                        moveHistoryUci.add(uci)
                        recordMoveEntry(notation, colorName, sideStep, false)
                    } else {
                        Log.w(TAG, "TXT导入: 走法 $notation (→$uci) 无效")
                    }
                }
            }

            // ★ 解析变着记录
            if (variationContent.isNotBlank()) {
                val varLinePattern = Regex("\\[第(\\d+)步变着\\]\\s*(.+)")
                for (match in varLinePattern.findAll(variationContent)) {
                    val branchStep = match.groupValues[1].toIntOrNull() ?: continue
                    val varMovesStr = match.groupValues[2].trim()

                    // 用中文记谱解析变着走法
                    val varBoard = Board()
                    // ★ 与导出一致：boardSnapshots[branchStep] 是分支时的局面
                    val fenBefore = if (branchStep >= 0 && branchStep < boardSnapshots.size) {
                        boardSnapshots[branchStep]
                    } else {
                        initialFen
                    }
                    varBoard.loadFromFen(fenBefore)

                    val varUciMoves = mutableListOf<String>()
                    for (vm in notationPattern.findAll(varMovesStr)) {
                        val uci = chineseNotationToUci(vm.value, varBoard)
                        if (uci != null) {
                            val m = Move.fromUci(uci, varBoard)
                            if (m != null && varBoard.getLegalMoves(varBoard.currentPlayer).any { it.toUci() == uci }) {
                                varUciMoves.add(uci)
                                varBoard.makeMove(m)
                            }
                        }
                    }
                    if (varUciMoves.isNotEmpty()) {
                        variationBranches.add(VariationBranch(branchStep, varUciMoves))
                    }
                }
            }

            // 检查游戏是否结束（静默模式：只设置状态，不弹窗）
            checkGameOver(silent = true)

            // ★ 导入后跳到起始局面，让用户可以逐步前进播放
            currentStepIndex = 0
            board.loadFromFen(boardSnapshots[0])
            isViewingHistory = boardSnapshots.size > 1

            onBoardChanged?.invoke()
            onStepChanged?.invoke()
            onExplanationHistoryChanged?.invoke()

            val varCount = variationBranches.size
            return if (moves.isNotEmpty()) {
                val varInfo = if (varCount > 0) "，${varCount}个变着" else ""
                "成功导入${moves.size}步棋谱${varInfo}，点击前进逐步播放"
            } else "未找到有效走法"
        } catch (e: Exception) {
            Log.e(TAG, "TXT导入失败: ${e.message}")
            return "导入失败: ${e.message}"
        }
    }

    /**
     * 导入UCI格式走法列表（如 "e6e5 b0c2 ..." 或带编号的PGN走法部分）
     * 正确更新所有游戏状态：boardSnapshots, currentStepIndex, moveHistoryUci, 棋谱条目
     */
    fun importUciMoves(content: String): String {
        try {
            newGame()

            val moves = mutableListOf<String>()
            // 提取所有UCI格式走法
            val uciPattern = Regex("[a-i][0-9][a-i][0-9][nbrq]?")
            for (match in uciPattern.findAll(content)) {
                val uci = match.value
                val move = Move.fromUci(uci, board)
                if (move != null && board.getLegalMoves(board.currentPlayer).any { it.toUci() == uci }) {
                    moves.add(uci)
                    val notation = CommentaryEngine.uciToChineseNotation(uci, board)
                    board.makeMove(move)
                    moveCount++
                    val moverColor = move.piece.color
                    if (moverColor == PieceColor.RED) redMoveCount++ else blackMoveCount++
                    val sideStep = if (moverColor == PieceColor.RED) redMoveCount else blackMoveCount
                    val colorName = if (moverColor == PieceColor.RED) "红方" else "黑方"
                    boardSnapshots.add(board.toFen())
                    currentStepIndex = boardSnapshots.size - 1
                    moveHistoryUci.add(uci)
                    recordMoveEntry(notation, colorName, sideStep, false)
                } else {
                    break
                }
            }

            // 检查游戏是否结束（静默模式：只设置状态，不弹窗）
            checkGameOver(silent = true)

            // ★ 导入后跳到起始局面，让用户可以逐步前进播放
            currentStepIndex = 0
            board.loadFromFen(boardSnapshots[0])
            isViewingHistory = boardSnapshots.size > 1

            onBoardChanged?.invoke()
            onStepChanged?.invoke()
            onExplanationHistoryChanged?.invoke()

            return if (moves.isNotEmpty()) "成功导入${moves.size}步棋谱，点击前进逐步播放"
                   else "未找到有效走法"
        } catch (e: Exception) {
            Log.e(TAG, "UCI导入失败: ${e.message}")
            return "导入失败: ${e.message}"
        }
    }

    // ══════════════════ 更多棋谱格式：ICCS / 东萍(DhtmlXQ) / 鹏飞PFC ══════════════════

    /**
     * ★★ UCI position 格式专门导入：形如
     *   "rnbakab1r/9/... w moves g3g4 i9h9 h0g2 ..."
     * 很多棋谱站/棋软用这种格式导谱。此前会被误判成纯 FEN 而把 moves 后的
     * 整串走法全部丢弃（只剩一个空开局）。本函数把 FEN 起点 + moves 走法串拆开，
     * 交由 importMovesFrom 从该起点完整回放，绝不丢走法、绝不退化为"只识别一个局面"。
     */
    fun importUciPosition(content: String): String {
        try {
            val c = content.trim()
            val movesIdx = c.indexOf("moves")
            if (movesIdx < 0) return "未识别到 UCI position 格式（缺少 moves 关键字）"
            val fenPart = c.substring(0, movesIdx).trim()
            val movePart = c.substring(movesIdx + 5).trim()
            if (fenPart.count { it == '/' } != 9) return "UCI position 起始 FEN 无效"
            val uciPattern = Regex("[a-i][0-9][a-i][0-9][nbrq]?")
            val uciList = uciPattern.findAll(movePart).map { it.value }.toList()
            if (uciList.isEmpty()) return "UCI position 中未找到有效走法"
            return importMovesFrom(fenPart, uciList)
        } catch (e: Exception) {
            return "UCI position 导入失败: ${e.message}"
        }
    }

    /** 从指定初始局面回放一串 UCI 走法（供 ICCS/东萍/PFC 等格式导入复用）。startFen 为空则用标准开局。 */
    fun importMovesFrom(startFen: String?, uciList: List<String>): String {
        try {
            newGame()
            if (!startFen.isNullOrBlank()) {
                board.loadFromFen(startFen)
            }
            // ★ 自动对边：东萍 binit / PFC 根局面不含执子方信息（恒记红先），
            //   当中局/黑先棋谱时首着是被动方，按首着行棋颜色翻边，确保导入真正落子。
            //   ICCS 若带 [FEN] 其执子方信息已由 loadFromFen 载入，此处首着同色则不动。
            if (uciList.isNotEmpty()) {
                val m1 = Move.fromUci(uciList[0], board)
                if (m1 != null && m1.piece.color != board.currentPlayer) {
                    board.currentPlayer = board.currentPlayer.opponent()
                }
            }
            boardSnapshots.clear(); boardSnapshots.add(board.toFen())
            currentStepIndex = 0; moveCount = 0; moveHistoryUci.clear()
            var ok = 0
            for (uci in uciList) {
                val move = Move.fromUci(uci, board)
                if (move != null && board.getLegalMoves(board.currentPlayer).any { it.toUci() == uci }) {
                    val notation = CommentaryEngine.uciToChineseNotation(uci, board)
                    board.makeMove(move); moveCount++
                    if (move.piece.color == PieceColor.RED) redMoveCount++ else blackMoveCount++
                    val sideStep = if (move.piece.color == PieceColor.RED) redMoveCount else blackMoveCount
                    val colorName = if (move.piece.color == PieceColor.RED) "红方" else "黑方"
                    boardSnapshots.add(board.toFen()); currentStepIndex = boardSnapshots.size - 1
                    moveHistoryUci.add(uci)
                    recordMoveEntry(notation, colorName, sideStep, false)
                    ok++
                } else break
            }
            checkGameOver(silent = true)
            currentStepIndex = 0
            board.loadFromFen(boardSnapshots[0])
            isViewingHistory = boardSnapshots.size > 1
            onBoardChanged?.invoke(); onStepChanged?.invoke(); onExplanationHistoryChanged?.invoke()
            return if (ok > 0) "成功导入${ok}步棋谱，点击前进逐步播放" else "未找到有效走法"
        } catch (e: Exception) {
            return "导入失败: ${e.message}"
        }
    }

    /**
     * 导出 ICCS 格式（英文字母列 + 数字行的坐标记谱，如 H2-E2 H9-G7，与用户提供的线路一致）。
     */
    fun exportIccs(): String {
        val sb = StringBuilder()
        sb.append("[Game \"Chinese Chess\"]\n")
        sb.append("[Title \"黄昏象棋 导出\"]\n")
        sb.append("[Event \"智能推演\"]\n")
        sb.append("[Result \"${if (gameOver) gameResult else "*"}\"]\n")
        // ★ 写入起始 FEN 头：若本局从非标准/中局面开始，导回时必须按原开局回放，
        //   否则 importIccs 会退化成标准开局而错位、丢回合（这是"导回识别不了/只剩几招"的根因）。
        if (initialFen.isNotBlank() && initialFen.count { it == '/' } == 9) {
            sb.append("[FEN \"$initialFen\"]\n")
        }
        sb.append("[Format \"ICCS\"]\n")
        val plies = moveHistoryUci.mapNotNull { u ->
            if (u.length != 4) return@mapNotNull null
            "" + u[0].uppercaseChar() + u[1] + "-" + u[2].uppercaseChar() + u[3]
        }
        var i = 0
        var n = 1
        while (i < plies.size) {
            val ln = StringBuilder().append(n).append(". ")
            ln.append(plies[i]); i++
            if (i < plies.size) { ln.append(' ').append(plies[i]); i++ }
            sb.append(ln).append('\n')
            n++
        }
        sb.append(if (gameOver) gameResult else "*").append('\n')
        return sb.toString()
    }

    /** 导入 ICCS：解析 [FEN] 起点（可为中局/黑先），归一化大写/连字符坐标 → UCI 回放。 */
    fun importIccs(content: String): String {
        val fen = Regex("\\[FEN\\s+\"([^\"]*)\"\\]").find(content)?.groupValues?.get(1)?.trim()
        val tokens = content
            .lowercase()
            .replace("-", "")
            .replace(Regex("\\d+\\.\\.?"), " ")
            .split(Regex("\\s+"))
        val uciList = mutableListOf<String>()
        for (t in tokens) {
            if (Regex("^[a-i][0-9][a-i][0-9]$").matches(t)) uciList.add(t)
        }
        if (uciList.isEmpty()) return "未在ICCS中找到有效走法"
        return importMovesFrom(fen, uciList)
    }

    /**
     * 导出 东萍 DhtmlXQ（.ubb）。binit 仅在非标准开局时写入起始 FEN（本程序读写同约定）,
     * movelist 为连续 UCI 串（与程序内部 .ubb 定义一致）。
     */
    fun exportDhtmlXq(): String {
        val sb = StringBuilder("")
        sb.append("[DhtmlXQ]\n")
        sb.append("[DhtmlXQ_ver]www_dpxq_com[/DhtmlXQ_ver]\n")
        sb.append("[DhtmlXQ_init]500,350[/DhtmlXQ_init]\n")
        sb.append("[DhtmlXQ_title]黄昏象棋 导出[/DhtmlXQ_title]\n")
        sb.append("[DhtmlXQ_event]智能推演[/DhtmlXQ_event]\n")
        sb.append("[DhtmlXQ_red][/DhtmlXQ_red]\n")
        sb.append("[DhtmlXQ_redteam][/DhtmlXQ_redteam]\n")
        sb.append("[DhtmlXQ_black][/DhtmlXQ_black]\n")
        sb.append("[DhtmlXQ_blackteam][/DhtmlXQ_blackteam]\n")
        sb.append("[DhtmlXQ_round][/DhtmlXQ_round]\n")
        val b0 = boardSnapshots.getOrNull(0) ?: ""
        val isStd = b0.substringBefore(' ').let {
            it == "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR" || it.isBlank()
        }
        if (!b0.isBlank() && !isStd) {
            sb.append("[DhtmlXQ_binit]").append(encodeXqFenHex(b0)).append("[/DhtmlXQ_binit]\n")
        }
        // ★ 真·东萍编码：movelist 每步 4 个十六进制字符（起点/终点各 2 位，高4位=列、低4位=行）
        sb.append("[DhtmlXQ_movelist]").append(moveHistoryUci.joinToString("") { mv ->
            val f = Position.fromUci(mv.substring(0, minOf(2, mv.length)))
            val t = Position.fromUci(mv.substring(minOf(2, mv.length), minOf(4, mv.length)))
            "%02X%02X".format(dhtmlEncode(f), dhtmlEncode(t))
        }).append("[/DhtmlXQ_movelist]\n")
        sb.append("[DhtmlXQ_length]").append(moveHistoryUci.size).append("[/DhtmlXQ_length]\n")
        sb.append("[/DhtmlXQ]\n")
        return sb.toString()
    }

    /** 导入 东萍 DhtmlXQ：支持 binit（FEN 或十六进制 32 字节）起点 + movelist（标准十六进制或旧版 UCI 串）。 */
    fun importDhtmlXq(content: String): String {
        val binit = Regex("\\[DhtmlXQ_binit]([^\\[]*)\\[/DhtmlXQ_binit]")
            .find(content)?.groupValues?.get(1)?.trim()
        val rawMoves = Regex("\\[DhtmlXQ_movelist]([^\\[]*)\\[/DhtmlXQ_movelist]")
            .find(content)?.groupValues?.get(1)?.trim() ?: ""
        if (rawMoves.isBlank()) return "东萍棋谱缺少 movelist，无法导入"
        val startFen = decodeXqFen(binit?.trim())
        val uciList = decodeDhtmlMoves(rawMoves)
        if (uciList.isEmpty()) {
            return "东萍 movelist 编码无法识别（既非标准十六进制也非UCI），请改用 PGN/ICCS/TXT"
        }
        return importMovesFrom(startFen, uciList)
    }

    /**
     * 导出 鹏飞象棋 .pfc：XML 树。根节点带初始局面 m=，主线用嵌套 <n m="走法"> 表示。
     */
    fun exportPfc(): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        val start = boardSnapshots.getOrNull(0) ?: "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"
        sb.append("<qipu m=\"").append(start).append("\">\n")
        buildPfcXml(sb, moveHistoryUci, 1)
        sb.append("</qipu>\n")
        return sb.toString()
    }

    private fun buildPfcXml(sb: StringBuilder, plies: List<String>, depth: Int) {
        if (plies.isEmpty()) return
        val ind = "  ".repeat(depth + 1)
        sb.append(ind).append("<n m=\"").append(plies[0]).append("\" s=\"0\">\n")
        buildPfcXml(sb, plies.subList(1, plies.size), depth + 1)
        sb.append(ind).append("</n>\n")
    }

    /** 导入 鹏飞 .pfc：读根节点起始 FEN，沿最左主线取 UCI 走法回放。 */
    fun importPfc(content: String): String {
        try {
            val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
            dbf.setExpandEntityReferences(false)
            val doc = dbf.newDocumentBuilder().parse(java.io.ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
            val root = doc.documentElement ?: return "导入失败: 空XML"
            val rootM = root.getAttribute("m").trim()
            val startFen = if (rootM.count { it == '/' } == 9) rootM else null
            val uciList = mutableListOf<String>()
            var node: org.w3c.dom.Node = root
            while (true) {
                val kids = node.childNodes
                var child: org.w3c.dom.Element? = null
                for (i in 0 until kids.length) {
                    val k = kids.item(i)
                    if (k is org.w3c.dom.Element) {
                        val m = k.getAttribute("m").trim()
                        if (m.isNotEmpty() && m.count { it == '/' } == 0) { child = k; break }
                    }
                }
                if (child == null) break
                val mv = child.getAttribute("m").trim().lowercase()
                if (!Regex("[a-i][0-9][a-i][0-9]").matches(mv)) break
                uciList.add(mv)
                node = child
            }
            if (uciList.isEmpty()) return "未在PFC中找到走法"
            return importMovesFrom(startFen, uciList)
        } catch (e: Exception) {
            return "PFC导入失败: ${e.message}"
        }
    }

    // ══════════════════ 东萍 / XQF 通用坐标与棋子槽位 ══════════════════

    /** 象棋演播室(XQStudio)棋子槽位顺序：0-15 红，16-31 黑（东萍 binit / XQF squares 共用） */
    private fun xqfPieceOrder(): List<Piece> {
        val out = ArrayList<Piece>(32)
        val R = PieceColor.RED; val B = PieceColor.BLACK
        val backs = listOf(PieceType.ROOK, PieceType.KNIGHT, PieceType.ELEPHANT, PieceType.ADVISOR,
            PieceType.KING, PieceType.ADVISOR, PieceType.ELEPHANT, PieceType.KNIGHT, PieceType.ROOK)
        for (t in backs) out.add(Piece(t, R))
        out.add(Piece(PieceType.CANNON, R)); out.add(Piece(PieceType.CANNON, R))
        repeat(5) { out.add(Piece(PieceType.PAWN, R)) }
        for (t in backs) out.add(Piece(t, B))
        out.add(Piece(PieceType.CANNON, B)); out.add(Piece(PieceType.CANNON, B))
        repeat(5) { out.add(Piece(PieceType.PAWN, B)) }
        return out
    }

    /** 每种（颜色,类型）允许占用的槽位，与 XQStudio 顺序一致 */
    private val xqfPieceSlots: Map<String, List<Int>> by lazy {
        mapOf(
            "R_ROOK" to listOf(0, 8), "R_KNIGHT" to listOf(1, 7), "R_ELEPHANT" to listOf(2, 6),
            "R_ADVISOR" to listOf(3, 5), "R_KING" to listOf(4),
            "R_CANNON" to listOf(9, 10), "R_PAWN" to listOf(11, 12, 13, 14, 15),
            "B_ROOK" to listOf(16, 24), "B_KNIGHT" to listOf(17, 23), "B_ELEPHANT" to listOf(18, 22),
            "B_ADVISOR" to listOf(19, 21), "B_KING" to listOf(20),
            "B_CANNON" to listOf(25, 26), "B_PAWN" to listOf(27, 28, 29, 30, 31)
        )
    }

    /** 东萍坐标值 = 高4位列 + 低4位行（v = col*16 + row）→ app Position(row,col)，非法返回 null */
    private fun dhtmlPos(v: Int): Position? {
        val col = (v ushr 4) and 0x0F
        val row = v and 0x0F
        return if (row in 0..9 && col in 0..8) Position(row, col) else null
    }
    private fun dhtmlEncode(p: Position): Int = (p.col shl 4) or (p.row and 0x0F)

    /** XQF 坐标值 = col*10 + y（y=9-row）→ app Position(row,col），非法返回 null */
    private fun xqfPos(v: Int): Position? {
        val col = v / 10
        val y = v % 10
        val row = 9 - y
        return if (row in 0..9 && col in 0..8) Position(row, col) else null
    }
    private fun xqfEncode(p: Position): Int = (p.col * 10) + (9 - p.row)

    /** 把 FEN 起始局面编码成东萍/XQF 的 32 字节十六进制（缺失棋子用 FF） */
    private fun encodeXqFenHex(fen: String): String {
        val rows = fen.split('/')
        val bytes = IntArray(32) { 0xFF }
        val used = BooleanArray(32)
        for (r in 0..9) {
            if (r >= rows.size) break
            var c = 0
            for (ch in rows[r]) {
                if (c > 8) break
                if (ch.isDigit()) { c += ch.toString().toInt(); continue }
                val color = if (ch.isUpperCase()) PieceColor.RED else PieceColor.BLACK
                val type = try { PieceType.fromFenChar(ch) } catch (_: Exception) { null }
                if (type != null && c in 0..8) {
                    val key = (if (color == PieceColor.RED) "R" else "B") + "_" + type.name
                    val slots = xqfPieceSlots[key] ?: emptyList()
                    for (s in slots) {
                        if (!used[s]) { used[s] = true; bytes[s] = dhtmlEncode(Position(r, c)); break }
                    }
                }
                c++
            }
        }
        val sb = StringBuilder()
        for (i in 0..31) sb.append("%02X".format(bytes[i] and 0xFF))
        return sb.toString()
    }

    /** 解码东萍 binit → 起始 FEN。FEN 直存原样返回；十六进制解 32 棋子；否则 null（标准开局）。 */
    private fun decodeXqFen(hex: String?): String? {
        if (hex.isNullOrBlank()) return null
        val cleaned = hex.replace(Regex("\\s+"), "")
        if (cleaned.contains('/') && cleaned.count { it == '/' } == 9) return cleaned
        if (cleaned.length < 4) return null
        val order = xqfPieceOrder()
        val grid = Array(10) { arrayOfNulls<Piece>(9) }
        var byteIndex = 0
        var i = 0
        while (byteIndex < 32 && i + 2 <= cleaned.length) {
            val v = cleaned.substring(i, i + 2).toIntOrNull(16) ?: 0xFF
            val pos = dhtmlPos(v)
            if (pos != null && byteIndex < order.size) grid[pos.row][pos.col] = order[byteIndex]
            i += 2; byteIndex++
        }
        return gridToFen(grid)
    }

    private fun gridToFen(grid: Array<Array<Piece?>>): String {
        val sb = StringBuilder()
        for (r in 0..9) {
            var e = 0
            for (c in 0..8) {
                val pc = grid[r][c]
                if (pc == null) e++
                else {
                    if (e > 0) { sb.append(e); e = 0 }
                    sb.append(if (pc.color == PieceColor.RED) pc.type.fenChar.uppercaseChar() else pc.type.fenChar)
                }
            }
            if (e > 0) sb.append(e)
            if (r < 9) sb.append('/')
        }
        sb.append(" w - - 0 1")
        return sb.toString()
    }

    /** 解码东萍 movelist：优先 UCI（a-i + 数字），否则按标准十六进制（每步 4 字符）。 */
    private fun decodeDhtmlMoves(raw: String): List<String> {
        val cleaned = raw.replace(Regex("\\s+"), "")
        val out = mutableListOf<String>()
        if (cleaned.length % 4 == 0) {
            var uciOk = true
            for (i in 0 until cleaned.length step 4) {
                val t = cleaned.substring(i, i + 4)
                if (!Regex("[a-i][0-9][a-i][0-9]").matches(t)) { uciOk = false; break }
                out.add(t)
            }
            if (uciOk && out.isNotEmpty()) return out
        }
        out.clear()
        var i = 0
        while (i + 4 <= cleaned.length) {
            val fv = cleaned.substring(i, i + 2).toIntOrNull(16) ?: break
            val tv = cleaned.substring(i + 2, i + 4).toIntOrNull(16) ?: break
            i += 4
            val f = dhtmlPos(fv) ?: break
            val t = dhtmlPos(tv) ?: break
            out.add(f.toUci() + t.toUci())
        }
        return out
    }

    // ══════════════════ 象棋演播室 XQF 二进制 导入/导出 ══════════════════

    /** 导出 XQF 二进制（v10 无加密，坐标=col*10+y）。供保存为 .xqf 文件。 */
    /**
     * 生成 XQF v1.1 树格式（版本 $0B）—— 与 象棋演播室/XQStudio 及"象棋之美"同构：
     * 扁平 0x400 头 + 走法树（根哨兵 'X','Q' → LChild=主线，RChild=变招），流加密。
     * 使得导出的棋谱带变招，且其它支持变招的阅读器能直接读全。
     */
    fun exportXqf(): ByteArray {
        val rand = java.util.Random()
        // ── 密钥与加密偏移（与 XQStudio dCalculateSecurityKeys / xqf2pgn 一致）──
        val keyXY = rand.nextInt(254) + 1
        val keyXYf = rand.nextInt(254) + 1
        val keyXYt = rand.nextInt(254) + 1
        val keysSum = (256 - ((keyXY + keyXYf + keyXYt) and 0xFF)) and 0xFF
        val keyMask = rand.nextInt(256) or 0xAA
        val keyOrA = rand.nextInt(256)
        val keyOrB = rand.nextInt(256)
        val keyOrC = rand.nextInt(256)
        val keyOrD = (256 - ((keyOrA + keyOrB + keyOrC) and 0xFF)) and 0xFF
        val pieceOff = (xqfSquare54Plus221(keyXY) * keyXY) and 0xFF
        val srcOff = (xqfSquare54Plus221(keyXYf) * pieceOff) and 0xFF
        val dstOff = (xqfSquare54Plus221(keyXYt) * srcOff) and 0xFF
        val args = intArrayOf(
            (keyOrA or (keysSum and keyMask)) and 0xFF,
            (keyOrB or (keyXY and keyMask)) and 0xFF,
            (keyOrC or (keyXYf and keyMask)) and 0xFF,
            (keyOrD or (keyXYt and keyMask)) and 0xFF)
        val stream = xqfStreamKeyArray(args)

        // ── 0x000-0x3FF 扁平头部 ──
        val head = ByteArray(0x400)
        head[0] = 'X'.code.toByte(); head[1] = 'Q'.code.toByte()
        // ★ 版本号用 0x12（象棋演播室 dCFileVersion=18）。
        //   只有 Version>=12 时读方才做"32 槽位按 derived KeyXY 旋转"；
        //   若写 0x0B 就会与主流阅读器（象棋之美等）对不上，棋盘错位导致卒/兵误判。
        head[2] = 0x12.toByte()
        head[3] = keyMask.toByte()
        head[8] = keyOrA.toByte(); head[9] = keyOrB.toByte()
        head[10] = keyOrC.toByte(); head[11] = keyOrD.toByte()
        head[12] = keysSum.toByte(); head[13] = keyXY.toByte()
        head[14] = keyXYf.toByte(); head[15] = keyXYt.toByte()
        // 32 槽位局面：coord + pieceOff（死子 90+随机）
        val b0 = boardSnapshots.getOrNull(0) ?: ""
        val squares = IntArray(32) { -1 }
        if (b0.isNotBlank()) {
            val rows = b0.split('/')
            val used = BooleanArray(32)
            for (r in 0..9) {
                if (r >= rows.size) break
                var c = 0
                for (ch in rows[r]) {
                    if (c > 8) break
                    if (ch.isDigit()) { c += ch.toString().toInt(); continue }
                    val color = if (ch.isUpperCase()) PieceColor.RED else PieceColor.BLACK
                    val type = try { PieceType.fromFenChar(ch) } catch (_: Exception) { null }
                    if (type != null && c in 0..8) {
                        val key = (if (color == PieceColor.RED) "R" else "B") + "_" + type.name
                        val slots = xqfPieceSlots[key] ?: emptyList()
                        for (s in slots) { if (!used[s]) { used[s] = true; squares[s] = xqfEncode(Position(r, c)); break } }
                    }
                    c++
                }
            }
        }
        for (i in 0..31) {
            // ★ v1.2+：32 槽位先按 derived pieceOff 旋转（与 XQStudio iSaveXQFile 一致），
            //   读方（含 象棋之美）会旋转回来并减 pieceOff 还原棋盘，缺了这步棋子会全部错位。
            val src = ((i + 1 + pieceOff) % 32)
            val base = squares[src]
            val v = if (base < 0) 90 + rand.nextInt(155)
                    else (base + pieceOff) and 0xFF
            head[0x10 + i] = v.toByte()
        }
        head[0x33] = when {
            gameOver && gameResult == "1-0" -> 1
            gameOver && gameResult == "0-1" -> 2
            gameOver && (gameResult == "1/2-1/2" || gameResult == "=") -> 3
            else -> 0
        }.toByte()
        head[0x40] = 2    // 中局/自定义局面：读方按 32 槽位摆子
        // 字符串字段按 XQStudio 头记录实际偏移（TitleA=0x60, MatchName=0xE0,
        // MatchTime=0x120, RedPlayer=0x140, BlkPlayer=0x150），只影响显示元数据。
        writeXqfStrInto(head, 0x60, 0x40, "黄昏象棋 导出")
        writeXqfStrInto(head, 0xE0, 0x40, "智能推演")
        writeXqfStrInto(head, 0x120, 0x10, java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.CHINA).format(java.util.Date()))
        writeXqfStrInto(head, 0x140, 0x10, if (redIsHuman) "人脑" else "AI")
        writeXqfStrInto(head, 0x150, 0x10, if (blackIsHuman) "人脑" else "AI")

        // ── 走法树（v1.1）：根哨兵 'X','Q'，主线 LChild，变招 RChild 链 ──
        val main = mutableListOf<XqfOutNode>()
        for (mv in moveHistoryUci) {
            if (mv.length < 4) continue
            val from = Position.fromUci(mv.substring(0, 2))
            val to = Position.fromUci(mv.substring(2, 4))
            main.add(XqfOutNode(xqfEncode(from), xqfEncode(to)))
        }
        for (i in 0 until main.size - 1) main[i].child = main[i + 1]  // 主线下一手 = LChild
        val varMap = variationBranches.groupBy { it.branchStep }
        for ((b, branches) in varMap) {
            val target = if (b in 0 until main.size) main[b] else main.lastOrNull()
            for (vb in branches.sortedBy { it.label }) {
                buildXqfOutVariation(vb)?.let { appendXqfSibling(target, it) }
            }
        }

        val out = java.io.ByteArrayOutputStream()
        out.write(head)
        // 根哨兵：XYf='X' XYt='Q'（读端对其坐标做"第一手"特殊化处理，忽略具体数值）
        val root = XqfOutNode(0, 0)
        root.child = main.firstOrNull()
        writeXqfNodeEnc(out, root, stream, srcOff, dstOff, isRoot = true)
        return out.toByteArray()
    }

    /** 由 4 字节密钥 args 生成 32 字节密钥流（掩码 "[(C) Copyright Mr. Dong Shiwei.]"）。 */
    private fun xqfStreamKeyArray(args: IntArray): IntArray {
        val mask = "[(C) Copyright Mr. Dong Shiwei.]"
        val stream = IntArray(32)
        for (i in 0..31) stream[i] = (args[i % 4] and mask[i].code) and 0xFF
        return stream
    }

    /** 按绝对文件偏移对流加密一个字节：存值 = 明文 + 密钥流[偏移%32]。 */
    private fun xqfEncByte(pos: Int, plain: Int, stream: IntArray): Int =
        (plain + stream[pos and 31]) and 0xFF

    /** 在固定长度字段内写入"长度字节 + 内容(GBK)"，不足补零（XQF 扁平头字符串约定）。 */
    private fun writeXqfStrInto(arr: ByteArray, off: Int, fieldSize: Int, s: String) {
        if (off < 0 || off >= arr.size) return
        val gbk = s.toByteArray(java.nio.charset.Charset.forName("GBK"))
        val cap = fieldSize - 1
        val clip = if (gbk.size > cap) gbk.copyOf(cap) else gbk
        arr[off] = clip.size.toByte()
        for (i in clip.indices) {
            val pos = off + 1 + i
            if (pos < arr.size) arr[pos] = clip[i]
        }
    }

    /** XQF 导出的走法节点（child=LChild 续棋/下一手，sibling=RChild 变招链）。 */
    private class XqfOutNode(val xyf: Int, val xyt: Int) {
        var child: XqfOutNode? = null
        var sibling: XqfOutNode? = null
    }

    /** 把一个变招分支（含嵌套子变招）展开成 XQF 节点树，返回其根节点。 */
    private fun buildXqfOutVariation(vb: VariationBranch): XqfOutNode? {
        if (vb.truncatedMoves.isEmpty()) return null
        val nodes = mutableListOf<XqfOutNode>()
        for (mv in vb.truncatedMoves) {
            if (mv.length < 4) continue
            val from = Position.fromUci(mv.substring(0, 2))
            val to = Position.fromUci(mv.substring(2, 4))
            nodes.add(XqfOutNode(xqfEncode(from), xqfEncode(to)))
        }
        if (nodes.isEmpty()) return null
        for (i in 0 until nodes.size - 1) nodes[i].child = nodes[i + 1]
        // 嵌套子变招：branchStep = 相对父 moves 的偏移，即挂在父的 nodes[branchStep] 的 RChild 上
        for (sv in vb.subVariations.sortedBy { it.label }) {
            val idx = if (sv.branchStep in 0 until nodes.size) sv.branchStep else nodes.size - 1
            buildXqfOutVariation(sv)?.let { appendXqfSibling(nodes[idx], it) }
        }
        return nodes[0]
    }

    /** 把 node 追加到 head 的 RChild(sibling) 链尾（多着并列）。 */
    private fun appendXqfSibling(head: XqfOutNode?, node: XqfOutNode) {
        val h = head ?: return
        var t = h
        while (t.sibling != null) t = t.sibling!!
        t.sibling = node
    }

    /**
     * v1.1 节点写出（含注解位与流加密）：节点本身 → LChild(续棋)子树 → RChild(变招)子树。
     * 每个节点 4 字节 [XYf][XYt][ChildTag][Reserved]；坐标按 +$18/+$20 偏移后流加密。
     * 根哨兵特殊：XYf='X' XYt='Q'（不套坐标偏移），读端把它当作"第0手"忽略其数值。
     */
    private fun writeXqfNodeEnc(
        out: java.io.ByteArrayOutputStream, n: XqfOutNode,
        stream: IntArray, srcOff: Int, dstOff: Int, isRoot: Boolean
    ) {
        val start = out.size()
        val xyfStored = if (isRoot) 'X'.code else (n.xyf + 0x18 + srcOff) and 0xFF
        val xytStored = if (isRoot) 'Q'.code else (n.xyt + 0x20 + dstOff) and 0xFF
        var tag = 0
        if (n.child != null) tag = tag or 0x80
        if (n.sibling != null) tag = tag or 0x40
        val reserved = (java.util.Random().nextInt(256)) and 0xFF
        out.write(xqfEncByte(start, xyfStored, stream))
        out.write(xqfEncByte(start + 1, xytStored, stream))
        out.write(xqfEncByte(start + 2, tag, stream))
        out.write(xqfEncByte(start + 3, reserved, stream))
        if (n.child != null) writeXqfNodeEnc(out, n.child!!, stream, srcOff, dstOff, false)
        if (n.sibling != null) writeXqfNodeEnc(out, n.sibling!!, stream, srcOff, dstOff, false)
    }

    /** 导出单局象棋桥 CBL 文件字节（CCBridge 容器 + zlib 压缩的时长前缀记录）。 */
    fun exportCbl(): ByteArray {
        val start = boardSnapshots.getOrNull(0) ?: return ByteArray(0)
        val moves = moveHistoryUci
        if (moves.isEmpty()) return ByteArray(0)
        return try {
            buildCblFile(start, moves)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /** 把起始 FEN + UCI 走法构建成一个可由本机 CblDecoder 直接读回的单局 CBL 文件。 */
    private fun buildCblFile(startFen: String, moves: List<String>): ByteArray {
        // ── 初始局面 32 槽位（象棋桥坐标：高4位=列+1，低4位=10-行；0 表示该槽位空）──
        val slots = IntArray(32) { 0 }
        val used = BooleanArray(32)
        val rows = startFen.split('/')
        for (r in 0..9) {
            if (r >= rows.size) break
            var c = 0
            for (ch in rows[r]) {
                if (c > 8) break
                if (ch.isDigit()) { c += ch.toString().toInt(); continue }
                val color = if (ch.isUpperCase()) PieceColor.RED else PieceColor.BLACK
                val type = try { PieceType.fromFenChar(ch) } catch (_: Exception) { null }
                if (type != null) {
                    val key = (if (color == PieceColor.RED) "R" else "B") + "_" + type.name
                    val list = xqfPieceSlots[key] ?: emptyList()
                    for (s in list) {
                        if (!used[s]) {
                            used[s] = true
                            // 象棋桥坐标：row0=红方底线(与 app row9 同侧)。低半字节 = appRow+1
                            slots[s] = ((c + 1) shl 4) or (r + 1)
                            break
                        }
                    }
                }
                c++
            }
        }

        // ── 记录内容：32 字节局面 + 短元数据(GBK 标题) + 走法块 ──
        val meta = "黄昏象棋 导出".toByteArray(java.nio.charset.Charset.forName("GBK"))
        val recBody = java.io.ByteArrayOutputStream()
        for (v in slots) recBody.write(v and 0xFF)
        recBody.write(meta)
        var num = 1
        for (m in moves) {
            if (m.length < 4) continue
            val fr = Position.fromUci(m.substring(0, 2))
            val to = Position.fromUci(m.substring(2, 4))
            // 象棋桥走法坐标：col=高半字节，低半字节=appRow（row0=红方底线）
            val fByte = (fr.col shl 4) or (fr.row and 0x0F)
            val tByte = (to.col shl 4) or (to.row and 0x0F)
            recBody.write(num and 0xFF); recBody.write(0); recBody.write(fByte); recBody.write(tByte); recBody.write(0); recBody.write(0)
            num++
        }
        val record = recBody.toByteArray()

        // ── blob：4 字节小端长度 + 记录 ──
        val blob = java.io.ByteArrayOutputStream()
        val len = record.size
        blob.write(len and 0xFF); blob.write((len shr 8) and 0xFF); blob.write((len shr 16) and 0xFF); blob.write((len shr 24) and 0xFF)
        blob.write(record)

        // ── 文件：CCBridge(8) + 12 头 + zlib(blob) ──
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION)
        val raw = blob.toByteArray()
        deflater.setInput(raw)
        deflater.finish()
        val cb = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!deflater.finished()) { val n = deflater.deflate(buf); if (n > 0) cb.write(buf, 0, n) }
        deflater.end()
        val out = java.io.ByteArrayOutputStream()
        out.write("CCBridge".toByteArray())
        out.write(ByteArray(12))
        out.write(cb.toByteArray())
        return out.toByteArray()
    }

    /**
     * 导入 XQF 二进制。支持 v10 明文 + v11/v12（0x0B/0x0C…0x12）加密版本。
     * 加密解密算法与象棋演播室官方 XQF 加密一致（密钥流 XOR/减法、局面 32 槽位旋转），
     * 已用真实 0x12 加密文件验证。支持中局/排局起点 + 主线走法回放。
     */
    fun importXqf(bytes: ByteArray): String {
        try {
            if (bytes.size < 0x400) return "XQF文件过小或损坏"
            val d = bytes
            if (d[0].toInt() != 'X'.code || d[1].toInt() != 'Q'.code) return "不是有效的XQF文件（缺少 XQ 标记）"
            val version = d[2].toInt() and 0xFF
            val type = d[0x40].toInt() and 0xFF

            // ── 加密密钥（v11+ 才需要；v10 恒为 0）──
            val encStream = IntArray(32)
            var pieceOff = 0; var srcOff = 0; var dstOff = 0; var commentOff = 0
            if (version >= 11) {
                val t = IntArray(16) { d[it].toInt() and 0xFF }
                pieceOff = (xqfSquare54Plus221(t[13]) * t[13]) and 0xFF
                srcOff = (xqfSquare54Plus221(t[14]) * pieceOff) and 0xFF
                dstOff = (xqfSquare54Plus221(t[15]) * srcOff) and 0xFF
                commentOff = ((t[12] * 256 + t[13]) % 32000) + 767
                val arg0 = t[3]
                val args = IntArray(4) { i -> (t[8 + i] or (t[12 + i] and arg0)) and 0xFF }
                val mask = "[(C) Copyright Mr. Dong Shiwei.]"
                for (i in 0..31) encStream[i] = (args[i % 4] and mask[i].code) and 0xFF
            }

            var startFen: String? = null
            if (type >= 2) {
                val order = xqfPieceOrder()
                val grid = Array(10) { arrayOfNulls<Piece>(9) }
                if (version < 12) {
                    for (i in 0..31) {
                        // 棋盘坐标解密按 Byte 环绕（Pascal 无符号减法，&0xFF 取模 256），>89 视为已吃子
                        val posVal = ((d[0x10 + i].toInt() and 0xFF) - pieceOff) and 0xFF
                        if (posVal > 89) continue
                        val pos = xqfPos(posVal)
                        if (pos != null && i < order.size) grid[pos.row][pos.col] = order[i]
                    }
                } else {
                    // v12+：32 槽位再按 pieceOff 旋转躲位
                    for (k in 0..31) {
                        val i = (((k - pieceOff - 1) % 32) + 32) % 32
                        // 同理按 Byte 环绕取模 256，>89 视为已吃子（关键：coord+pieceOff 会越过 255）
                        val posVal = ((d[0x10 + i].toInt() and 0xFF) - pieceOff) and 0xFF
                        if (posVal > 89) continue
                        val pos = xqfPos(posVal)
                        if (pos != null && k < order.size) grid[pos.row][pos.col] = order[k]
                    }
                }
                startFen = gridToFen(grid)
            }

            // ── 走法区：按节点树解析（主线 next + 变招 sibling），与象棋演播室/节点象棋同构 ──
            val startOffset = xqfStartOffset(d, version, encStream, commentOff)
            val encStreamKey = encStream
            val rootNode = readXqfNodeTree(d, version, encStreamKey, srcOff, dstOff, commentOff, XqfOff(startOffset), 0)
            if (rootNode == null) return "未在XQF中找到走法节点"

            // 主线 = rootNode 起沿 next 链
            val uciList = mutableListOf<String>()
            var run: XqfMv? = rootNode
            while (run != null && uciList.size < 100000) {
                if (run.uci.isNotEmpty()) uciList.add(run.uci)
                run = run.next
            }
            if (uciList.isEmpty()) return "未在XQF中找到走法"

            // 建立主线局面快照（含执子方自动对边），以便给每个变招挂上对应棋盘
            val mainResult = importMovesFrom(startFen, uciList)
            if (!mainResult.startsWith("成功导入")) return mainResult
            val snap = boardSnapshots      // snap[k] = 主线走完 k 手后的局面

            // 把变招挂到主线各分支点：ml[k].sibling = 第 k 手处的替代着法
            val ml = mutableListOf<XqfMv>()
            run = rootNode
            while (run != null && ml.size < uciList.size) { ml.add(run); run = run.next }
            // ★ 重置本轮 XQF 导入的全局变招展开预算（防止超大书/全集把所有变招树一次物化导致 OOM 闪退）
            xqfVarBudget = XQF_VAR_NODE_BUDGET
            for (k in ml.indices) {
                if (k >= snap.size) break  // 主线尚未走到该步，跳过
                var sib = ml[k].sibling
                while (sib != null) {
                    val atBoard = Board()
                    atBoard.loadFromFen(snap[k])
                    val branch = buildXqfVariation(sib, atBoard, k,
                        nextVariationLabel(variationBranches.filter { it.branchStep == k }), 0)
                    if (branch != null) variationBranches.add(branch)
                    sib = sib.sibling
                }
            }
            onStepChanged?.invoke(); onBoardChanged?.invoke()
            val hasVar = variationBranches.isNotEmpty()
            return if (hasVar)
                "成功导入${moveHistoryUci.size}步棋谱，识别出${variationBranches.size}个变招，可点变着查看"
            else mainResult
        } catch (e: Exception) {
            return "XQF导入失败: ${e.message}"
        }
    }

    /** XQF 节点树：主线(next)+变招(sibling)，与象棋演播室/节点象棋一致。 */
    private class XqfMv(
        var uci: String = "",
        var hasNext: Boolean = false,
        var hasVariation: Boolean = false,
        var next: XqfMv? = null,
        var sibling: XqfMv? = null
    )

    private class XqfOff(var p: Int)

    /** 走法区起始（0x400 之后跳过空根招节点及其注释）。 */
    private fun xqfStartOffset(d: ByteArray, version: Int, encStream: IntArray, commentOff: Int): Int {
        val area = 0x400
        if (area + 2 >= d.size) return area + 4
        val fl = xqfDecByteAt(d, area + 2, encStream) ?: return area + 4
        var cursor = area + 4
        if (version > 10) {
            if ((fl and 0x20) != 0) {
                val len = xqfDecIntLE(d, area + 4, encStream) - commentOff
                if (len in 1..4096) cursor = area + 8 + len
            }
        } else {
            // v10：根哨兵也是 8 字节定长节点（XYf,XYt,ChildTag,Reserved + RemarkSize），
            // 无注解时首手节点从 area+8 起（原实现默认 area+4 会读进哨兵内部导致解析错乱）。
            cursor = area + 8
            val len = xqfDecIntLE(d, area + 4, encStream)
            if (len in 1..4096) cursor = area + 8 + len
        }
        return cursor
    }

    private fun xqfDecByteAt(d: ByteArray, absPos: Int, encStream: IntArray): Int? {
        if (absPos < 0 || absPos >= d.size) return null
        return ((d[absPos].toInt() and 0xFF) - encStream[absPos % 32]) and 0xFF
    }

    private fun xqfReadIntLE(d: ByteArray, absPos: Int): Int {
        if (absPos + 3 >= d.size) return 0
        return (d[absPos].toInt() and 0xFF) or ((d[absPos + 1].toInt() and 0xFF) shl 8) or
                ((d[absPos + 2].toInt() and 0xFF) shl 16) or ((d[absPos + 3].toInt() and 0xFF) shl 24)
    }

    /** 读取已解密后的 4 字节小端无符号整数。XQF 节点的“注释长度”字段必须读解密流（与官方演播室一致），
     *  否则对非零密钥的加密棋谱，偏移会漂移导致整局解析错乱（“导入的不对”）。 */
    private fun xqfDecIntLE(d: ByteArray, absPos: Int, encStream: IntArray): Int {
        var v = 0
        var mul = 1
        for (i in 0..3) {
            val b = xqfDecByteAt(d, absPos + i, encStream) ?: return v
            v += b * mul
            mul *= 256
        }
        return v
    }

    /** 递归读取 XQF 节点树（主线子节点 next + 变招兄弟 sibling）。 */
    private fun readXqfNodeTree(
        d: ByteArray, version: Int, encStream: IntArray,
        srcOff: Int, dstOff: Int, commentOff: Int, off: XqfOff, depth: Int
    ): XqfMv? {
        val start = off.p
        if (start + 4 > d.size || depth > 20000) return null
        val b0 = xqfDecByteAt(d, start, encStream) ?: return null
        val b1 = xqfDecByteAt(d, start + 1, encStream) ?: return null
        val fl = xqfDecByteAt(d, start + 2, encStream) ?: return null
        var hasNext: Boolean; var hasVar: Boolean; var frVal: Int; var toVal: Int
        var cursor: Int
        if (version <= 10) {
            // v10：8 字节定长结构的线性棋谱。第3字节高半字节 $F0=有后续、$00=末着；
            // 该版本在格式上「不支持变招」，因此不再把低半字节当 RChild。
            hasNext = (fl and 0xF0) != 0; hasVar = false
            frVal = (b0 - 24) and 0xFF; toVal = (b1 - 32) and 0xFF
            cursor = start + 8
            if (start + 8 <= d.size) {
                val clen = xqfDecIntLE(d, start + 4, encStream)
                if (clen in 1..4096 && start + 8 + clen <= d.size) cursor = start + 8 + clen
            }
        } else {
            // v11+：4 字节结构；注释长度在 start+4，注释正文从 start+8 起，其后才是下一节点
            hasNext = (fl and 0x80) != 0; hasVar = (fl and 0x40) != 0
            frVal = (b0 - 24 - srcOff) and 0xFF; toVal = (b1 - 32 - dstOff) and 0xFF
            cursor = start + 4
            if ((fl and 0x20) != 0 && start + 6 <= d.size) {
                val clen = xqfDecIntLE(d, start + 4, encStream) - commentOff
                if (clen in 1..4096 && start + 8 + clen <= d.size) cursor = start + 8 + clen
            }
        }
        val node = XqfMv(xqfAppUci(frVal, toVal))
        node.hasNext = hasNext; node.hasVariation = hasVar
        if (hasNext) { off.p = cursor; node.next = readXqfNodeTree(d, version, encStream, srcOff, dstOff, commentOff, off, depth + 1); cursor = off.p }
        if (hasVar) { off.p = cursor; node.sibling = readXqfNodeTree(d, version, encStream, srcOff, dstOff, commentOff, off, depth + 1); cursor = off.p }
        off.p = cursor
        return node
    }

    private fun xqfAppUci(frVal: Int, toVal: Int): String {
        val from = xqfPos(frVal) ?: return ""
        val to = xqfPos(toVal) ?: return ""
        return from.toUci() + to.toUci()
    }

    /** 深度优先构建一个变招分支（含嵌套子变招），并做合法性校验。 */
    private fun buildXqfVariation(startNode: XqfMv?, boardAt: Board, branchStep: Int, label: String, depth: Int): VariationBranch? {
        if (startNode == null || depth > 200) return null
        if (xqfVarBudget <= 0) return null   // 全局预算耗尽：不再展开更深变招（防 OOM）
        val b = Board(); b.loadFromFen(boardAt.toFen())
        val moves = mutableListOf<String>()
        val subs = mutableListOf<VariationBranch>()
        var n: XqfMv? = startNode
        var guard = 0
        while (n != null && guard < 200) {
            guard++
            if (xqfVarBudget <= 0) break
            xqfVarBudget--
            val nUci = n.uci
            val m = Move.fromUci(nUci, b)
            if (m == null || !b.getLegalMoves(b.currentPlayer).any { it.toUci() == nUci }) break
            b.makeMove(m); moves.add(nUci)
            // 嵌套子变招挂在本手之后（moves.size 手后）
            val child = n.next
            if (child != null) {
                var sc = child.sibling
                while (sc != null) {
                    val subBr = buildXqfVariation(sc, b, moves.size, nextVariationLabel(subs), depth + 1)
                    if (subBr != null) subs.add(subBr)
                    sc = sc.sibling
                }
            }
            n = n.next
        }
        if (moves.isEmpty()) return null
        return VariationBranch(branchStep, moves, label, subs)
    }

    /** XQF 加密辅助：x*x*54+221（官方 XQF 密钥生成基础式）。 */
    private fun xqfSquare54Plus221(x: Int): Int = x * x * 54 + 221

    /** XQF 加密解码 4 字节（起点 encIndex，每字节减去密钥流后自增，mod 32）。 */
    private fun xqfDecrypt4(d: ByteArray, pos: Int, encStream: IntArray, encIndex: Int): IntArray {
        val out = IntArray(4)
        if (pos + 3 >= d.size) { out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0; return out }
        var idx = encIndex
        for (i in 0..3) {
            out[i] = ((d[pos + i].toInt() and 0xFF) - encStream[idx]) and 0xFF
            idx = (idx + 1) % 32
        }
        return out
    }

    /**
     * 导出完整游戏状态为JSON（用于持久化，跨session保存全部走棋记录）
     */
    fun getFullStateJson(): org.json.JSONObject {
        val json = org.json.JSONObject()
        val snapshotsArr = org.json.JSONArray()
        for (snap in boardSnapshots) {
            snapshotsArr.put(snap)
        }
        json.put("boardSnapshots", snapshotsArr)
        json.put("currentStepIndex", currentStepIndex)
        json.put("moveCount", moveCount)
        
        // 走法历史
        val movesArr = org.json.JSONArray()
        for (uci in moveHistoryUci) {
            movesArr.put(uci)
        }
        json.put("moveHistoryUci", movesArr)
        
        // 解释历史
        val explArr = org.json.JSONArray()
        for (entry in explanationHistory) {
            val entryObj = org.json.JSONObject()
            entryObj.put("step", entry.stepNumber)
            entryObj.put("totalStep", entry.totalStepIndex)
            entryObj.put("isHuman", entry.isHuman)
            entryObj.put("notation", entry.moveNotation)
            entryObj.put("text", entry.text)
            entryObj.put("fen", entry.boardFen)
            entryObj.put("color", entry.color)
            explArr.put(entryObj)
        }
        json.put("explanationHistory", explArr)
        
        // 开局信息
        json.put("openingAnnounced", openingAnnounced)
        if (currentOpeningName != null) {
            json.put("openingName", currentOpeningName)
        }

        // 变着分支（递归序列化：含标签 + 嵌套子树）
        fun serVariation(vb: VariationBranch): org.json.JSONObject {
            val o = org.json.JSONObject()
            o.put("branchStep", vb.branchStep)
            o.put("label", vb.label)
            val movesArr = org.json.JSONArray()
            for (m in vb.truncatedMoves) movesArr.put(m)
            o.put("moves", movesArr)
            val subs = org.json.JSONArray()
            for (sv in vb.subVariations) subs.put(serVariation(sv))
            o.put("sub", subs)
            return o
        }
        val varArr = org.json.JSONArray()
        for (vb in variationBranches) varArr.put(serVariation(vb))
        json.put("variationBranches", varArr)

        // PGN 主线注释无损持久化
        val mc = org.json.JSONObject()
        for ((k, v) in moveComments) if (v.isNotBlank()) mc.put(k.toString(), v)
        json.put("moveComments", mc)

        return json
    }

    /**
     * 从JSON导入完整游戏状态
     */
    fun loadFullStateJson(json: org.json.JSONObject): Boolean {
        try {
            boardSnapshots.clear()
            val snapshotsArr = json.getJSONArray("boardSnapshots")
            for (i in 0 until snapshotsArr.length()) {
                boardSnapshots.add(snapshotsArr.getString(i))
            }
            
            currentStepIndex = json.getInt("currentStepIndex")
            moveCount = json.getInt("moveCount")
            // 从moveCount推算按方计数（红方先走，奇数步=红方，偶数步=黑方）
            redMoveCount = (moveCount + 1) / 2
            blackMoveCount = moveCount / 2
            
            moveHistoryUci.clear()
            val movesArr = json.getJSONArray("moveHistoryUci")
            for (i in 0 until movesArr.length()) {
                moveHistoryUci.add(movesArr.getString(i))
            }

            // ★★★ 硬兜底：若棋盘快照意外丢失/为空（只剩初始局面），但走法历史仍在，
            //    就用手头 UCI 从初始局面原样重放，重建完整的 boardSnapshots + 步数 + 当前步。
            //    ——这直接保证“存盘后回合数/快进退条”绝不为 0（之前的“续盘保险”只补了
            //    explanationHistory，没补 boardSnapshots，步数/快进条仍显示 0）。
            if (boardSnapshots.size <= 1 && moveHistoryUci.isNotEmpty()) {
                try {
                    val rebuild = Board()
                    rebuild.loadFromFen(initialFen)
                    val list = mutableListOf(initialFen)
                    for (uci in moveHistoryUci) {
                        val m = Move.fromUci(uci, rebuild)
                        if (m != null) {
                            rebuild.makeMoveUnchecked(m)
                            list.add(rebuild.toFen())
                        }
                    }
                    if (list.size > boardSnapshots.size) {
                        boardSnapshots.clear()
                        boardSnapshots.addAll(list)
                        moveCount = moveHistoryUci.size
                        redMoveCount = (moveCount + 1) / 2
                        blackMoveCount = moveCount / 2
                        currentStepIndex = boardSnapshots.size - 1
                        Log.i(TAG, "★★★ 硬兜底：从走法历史重放重建快照 ${boardSnapshots.size - 1} 步")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "★★★ 快照重建失败: ${e.message}", e)
                }
            }
            
            explanationHistory.clear()
            val explArr = json.getJSONArray("explanationHistory")
            for (i in 0 until explArr.length()) {
                val entryObj = explArr.getJSONObject(i)
                var notation = entryObj.getString("notation")
                val totalStep = if (entryObj.has("totalStep")) entryObj.getInt("totalStep") else entryObj.getInt("step")

                // ★ 棋谱完整性验证：如果加载的棋谱异常短（<3字符），尝试从UCI走法历史恢复
                if (notation.length < 3) {
                    Log.e(TAG, "★★★ loadFullStateJson: 棋谱异常短! notation='$notation' step=$totalStep, 尝试恢复")
                    if (totalStep in 1..moveHistoryUci.size) {
                        val uci = moveHistoryUci[totalStep - 1]
                        if (totalStep in 1..boardSnapshots.size) {
                            val restoreBoard = Board()
                            restoreBoard.loadFromFen(boardSnapshots[totalStep - 1])
                            val recovered = CommentaryEngine.uciToChineseNotation(uci, restoreBoard)
                            if (recovered.length >= 3) {
                                Log.d(TAG, "★★★ 棋谱恢复成功: '$notation' → '$recovered' (uci=$uci)")
                                notation = recovered
                            } else {
                                Log.e(TAG, "★★★ 棋谱恢复失败: recovered='$recovered' uci=$uci")
                                notation = uci  // 至少返回UCI走法，比残缺棋谱好
                            }
                        }
                    }
                }

                val entry = ExplanationEntry(
                    stepNumber = entryObj.getInt("step"),
                    isHuman = entryObj.getBoolean("isHuman"),
                    moveNotation = notation,
                    text = entryObj.getString("text"),
                    boardFen = entryObj.getString("fen"),
                    color = if (entryObj.has("color")) entryObj.getString("color") else "",
                    totalStepIndex = totalStep
                )
                explanationHistory.add(entry)
            }

            // ★ 续盘保险：若解说/棋谱列表为空但走法历史存在，则从 UCI 重建，
            //   保证回放/回合快进始终能看到真实回合数（针对"存盘后回合不见了"的防漏）。
            if (explanationHistory.isEmpty() && moveHistoryUci.isNotEmpty()) {
                for (i in moveHistoryUci.indices) {
                    val uci = moveHistoryUci[i]
                    val fenBefore = if (i < boardSnapshots.size) boardSnapshots[i] else board.toFen()
                    var notation = uci
                    try {
                        val rb = Board()
                        rb.loadFromFen(fenBefore)
                        val c = CommentaryEngine.uciToChineseNotation(uci, rb)
                        if (c.length >= 3) notation = c
                    } catch (e: Exception) { /* 保留 UCI 走法兜底 */ }
                    explanationHistory.add(ExplanationEntry(
                        stepNumber = (i + 2) / 2,
                        isHuman = true,
                        moveNotation = notation,
                        text = "",
                        boardFen = fenBefore,
                        color = if (i % 2 == 0) "红方" else "黑方",
                        totalStepIndex = i + 1
                    ))
                }
                explanationHistory.sortBy { it.totalStepIndex }
                Log.i(TAG, "★★ 续盘保险：从走法历史重建 ${explanationHistory.size} 步棋谱")
            }

            // 恢复局面到当前步
            if (boardSnapshots.isNotEmpty()) {
                board.loadFromFen(boardSnapshots[currentStepIndex.coerceIn(0, boardSnapshots.size - 1)])
            }
            
            // 开局信息
            if (json.has("openingAnnounced")) {
                openingAnnounced = json.getBoolean("openingAnnounced")
            }
            if (json.has("openingName")) {
                currentOpeningName = json.getString("openingName")
            }
            
            isViewingHistory = currentStepIndex < boardSnapshots.size - 1
            gameOver = false
            gameResult = ""
            engineThinking = false
            pendingRetry = false
            variationBranches.clear()
            if (json.has("variationBranches")) {
                // 递归反序列化（含标签 + 嵌套子树）；兼容旧扁平格式（无 label/sub 字段）
                fun deSerVar(o: org.json.JSONObject): VariationBranch {
                    val movesArr = o.getJSONArray("moves")
                    val moves = mutableListOf<String>()
                    for (j in 0 until movesArr.length()) moves.add(movesArr.getString(j))
                    val label = if (o.has("label")) o.optString("label", "") else ""
                    val subs = mutableListOf<VariationBranch>()
                    if (o.has("sub")) {
                        val subArr = o.getJSONArray("sub")
                        for (k in 0 until subArr.length()) {
                            subs.add(deSerVar(subArr.getJSONObject(k)))
                        }
                    }
                    return VariationBranch(o.getInt("branchStep"), moves, label, subs)
                }
                val varArr = json.getJSONArray("variationBranches")
                for (i in 0 until varArr.length()) {
                    variationBranches.add(deSerVar(varArr.getJSONObject(i)))
                }
            }
            // PGN 主线注释恢复
            moveComments.clear()
            if (json.has("moveComments")) {
                val mc = json.getJSONObject("moveComments")
                for (k in mc.keys()) {
                    val v = mc.optString(k, "")
                    if (v.isNotBlank()) k.toIntOrNull()?.let { ix -> moveComments[ix] = v }
                }
            }
            warnedViolator = null  // 清除循环着法警告

            return true
        } catch (e: Exception) {
            Log.e(TAG, "loadFullStateJson failed: ${e.message}")
            return false
        }
    }

    private fun detectMove(board: Board, targetFen: String): Move? {
        val target = Board()
        target.loadFromFen(targetFen)
        val currentPlayer = board.currentPlayer
        val legalMoves = board.getLegalMoves(currentPlayer)
        for (move in legalMoves) {
            val testBoard = Board()
            testBoard.loadFromFen(board.toFen())
            testBoard.makeMove(move)
            if (testBoard.toFen() == targetFen) return move
        }
        return null
    }

    // ── ECO开局识别 ──

    /**
     * 根据当前走法历史识别开局
     */
    /** 当前识别到的开局名称（如"E00 仙人指路"），供主界面做常驻注解展示。 */
    fun getCurrentOpeningName(): String? = currentOpeningName

    /** 当前识别到的开局条目（含分类），供主界面注解/战略要点使用；未识别返回 null。 */
    fun getCurrentOpeningEntry(): EcoDatabase.EcoEntry? = currentOpeningEntry

    /** 当前开局的战略要点文字：优先取自分类匹配，回退到通用提示；未识别开局返回 null。 */
    fun getOpeningStrategy(): String? {
        val entry = currentOpeningEntry ?: return null
        return OpeningTactics.hintFor(entry.category, entry.name)
    }

    // ── 开局名（ECCO）──

    /**
     * ★ 完整主线走法（供 ECCO 开局判定）。
     *
     * 亲自走棋 / AI 应子 / 导入棋谱 都会同步维护 moveHistoryUci；但浏览/预览历史时棋盘用
     * loadFromFen 切局面，会把 board.moveHistory 清空。因此这里绝不依赖 board.moveHistory，
     * 一律从起始局面 boardSnapshots[0] 重放 moveHistoryUci 重建 Move 列表——
     * 这样“从宝库导入/外部导入后直接预览”的谱，也能和亲自走棋一样判出开局名。
     *
     * 任一步无法重建（走法失效）则返回空列表，调用方据此不显示开局名，绝不报错名。
     */
    fun getMainlineMovesForNaming(): List<Move> {
        if (moveHistoryUci.isEmpty()) return emptyList()
        val fen0 = boardSnapshots.firstOrNull() ?: return emptyList()
        val b = Board()
        try {
            b.loadFromFen(fen0)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Move>(moveHistoryUci.size)
        for (uci in moveHistoryUci) {
            val mv = Move.fromUci(uci, b) ?: return emptyList()
            if (b.getLegalMoves(b.currentPlayer).none { it.toUci() == uci }) return emptyList()
            b.makeMove(mv)
            out.add(mv)
        }
        return out
    }

    /** 开局名结果：code=ECCO 编号，opening=开局名，variation=变例名，fromMidLine=是否按中途局面(FEN)匹配得出。 */
    data class OpeningLabel(
        val code: String,
        val opening: String,
        val variation: String,
        val fromMidLine: Boolean
    )

    /**
     * ★ 当前棋局的开局名（ECCO），供“棋局信息”等界面使用：
     *  1. 标准开局起步且有走法 → 按完整主线走法判 ECCO 编号/开局名/变例名（亲自走、导入、预览一视同仁）；
     *  2. 否则（残局/摆子起谱，或 ECCO 未覆盖）→ 若初始局面恰是某条开局线中途，按该线填开局名并标注中途；
     *  3. 都判不出返回 null，UI 层据此隐藏该行，绝不显示错名。
     */
    fun openingLabelForCurrent(): OpeningLabel? {
        val fen0 = boardSnapshots.firstOrNull() ?: return null
        try {
            val standard = Board()
            standard.reset()
            val startIsStandard =
                fen0.substringBefore(' ').trim() == standard.toFen().substringBefore(' ').trim()
            if (startIsStandard) {
                val moves = getMainlineMovesForNaming()
                if (moves.isNotEmpty()) {
                    val r = EccoNamer.ofMoves(moves)
                    if (r != null && r.opening.isNotBlank()) {
                        return OpeningLabel(r.code, r.opening, r.variation, false)
                    }
                }
            }
            // FEN 中途匹配：没有从开局走起（残局/摆子/中途起谱），但初始局面与某条开局线对得上
            val e = identifyOpeningByFen(fen0)
            if (e != null) return OpeningLabel(e.code, e.name, "", true)
        } catch (e: Exception) {
            Log.w(TAG, "openingLabelForCurrent: ${e.message}", e)
        }
        return null
    }

    /**
     * 给定初始 FEN，从标准开局逐条重放 EcoDatabase 开局线；若该局面恰是某条线中途（含首步后），
     * 返回该开局条目。棋谱用中文记谱存线，重放靠 chineseNotationToUci 逐手转 UCI 再落子。
     */
    private fun identifyOpeningByFen(fen: String): EcoDatabase.EcoEntry? {
        val wantBoard = fen.substringBefore(' ').trim()
        if (wantBoard.isEmpty()) return null
        for (entry in EcoDatabase.entries) {
            val b = Board()
            b.reset()
            var ok = true
            for (m in entry.moves.split(",")) {
                val n = m.trim()
                if (n.isEmpty()) continue
                val uci = chineseNotationToUci(n, b)
                if (uci == null) { ok = false; break }
                val mv = Move.fromUci(uci, b)
                if (mv == null || b.getLegalMoves(b.currentPlayer).none { it.toUci() == uci }) {
                    ok = false
                    break
                }
                b.makeMove(mv)
                if (b.toFen().substringBefore(' ').trim() == wantBoard) {
                    return entry
                }
            }
        }
        return null
    }

    private fun identifyOpening() {
        if (openingAnnounced) return  // 已经播报过，不再重复
        if (moveHistoryUci.size < 2) return  // 至少需要2步

        val moves = moveHistoryUci.toList()
        val entry = EcoDatabase.identifyEntry(moves) ?: return

        // 只有走法数达到开局定义的长度时才播报（避免过早匹配）
        val entryMoveCount = entry.moves.split(",").size
        if (moves.size < entryMoveCount) return

        val openingName = "${entry.code} ${entry.name}"
        if (openingName == currentOpeningName) {
            currentOpeningEntry = entry   // ★ 已识别过，仅刷新条目（保留分类），本身不变
            return
        }

        currentOpeningName = openingName
        currentOpeningEntry = entry   // ★ 记住匹配到的开局条目，供战略注解使用
        openingAnnounced = true
        onOpeningIdentified?.invoke(openingName)
        Log.d(TAG, "Opening identified: $openingName")
    }

    fun destroy() {
        setAnalyzing(false)
        engine.stopPonder()
        engine.stop()
        engine.shutdown()
        soundManager.release()
    }

    // ── 劣势追踪 ──

    /**
     * 更新劣势追踪状态
     * 锁定首次失子失势的时刻，之后不再改变，确保追溯不跳脱
     */
    private fun updateDisadvantageTracking(score: Int, board: Board) {
        // 如果已经锁定了劣势，不要再改
        if (currentDisadvantage != null) return

        // 检测是否出现劣势（评分差距>100分）
        val isRedToMove = board.currentPlayer == PieceColor.RED
        val absScore = kotlin.math.abs(score)
        if (absScore < 100) return  // 差距太小，不视为劣势

        // 首次检测到劣势，锁定
        val disadvantagedSide = if ((score > 0) == isRedToMove) "黑方" else "红方"
        val materialDiff = CommentaryEngine.analyzeMaterialDiff(board)
        val diffAbs = kotlin.math.abs(materialDiff)
        val reason = when {
            absScore > 500 && diffAbs >= 500 -> "${disadvantagedSide}失子，子力落后${diffAbs / 100}兵以上"
            absScore > 500 && diffAbs < 500 -> "${disadvantagedSide}失势，局面被压制严重"
            absScore > 200 -> "${disadvantagedSide}局面被动，多处受制"
            else -> "${disadvantagedSide}略处下风"
        }

        // ★ 用按方计数而非总步数，避免人脑执黑时第一步被显示为"第2步"
        val moverSideStep = if (isRedToMove) redMoveCount else blackMoveCount
        currentDisadvantage = CommentaryEngine.DisadvantageInfo(
            stepNumber = moverSideStep,
            side = disadvantagedSide,
            reason = reason,
            materialDiff = materialDiff,
            scoreAtTime = score
        )
        Log.d(TAG, "Disadvantage locked: $disadvantagedSide - $reason (sideStep=$moverSideStep, moveCount=$moveCount)")
    }
}