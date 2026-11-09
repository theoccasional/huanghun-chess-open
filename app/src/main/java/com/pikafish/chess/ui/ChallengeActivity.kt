package com.pikafish.chess.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
// ★ 挑战模式用系统平台主题(Theme.Material.Fullscreen)，对话框必须配平台版
//   AlertDialog；若用 appcompat 版会抛"需要 Theme.AppCompat 主题"的异常。
import android.app.AlertDialog
import com.pikafish.chess.FixScale
import com.pikafish.chess.R
import com.pikafish.chess.engine.CommentaryEngine
import com.pikafish.chess.engine.JniEngine
import com.pikafish.chess.qipu.QipuLibrary
import com.pikafish.chess.engine.LocalBookManager
import com.pikafish.chess.engine.SandboxEngine
import com.pikafish.chess.engine.XueyuEngine
import com.pikafish.chess.engine.CcstockfishEngine
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.RepetitionRule
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★★★ 挑战模式（独立进程 :challenge）★★★
 *
 * 选择引擎等级（古代官衔，暗指不同算力层）与人持红/黑，与引擎对战。
 * 特点：
 *  - 完全独立进程 + 独立 SandboxEngine（桥接四套非神经网络引擎），与主界面互不相干
 *  - 只走棋，绝无思考细节 / 分析 / 任何 AI 文字输出；棋盘+可收缩沙盘式菜单
 *  - 32 级难度：街头4套引擎 × 各7档 + 封疆中段补档 + 顶段名手 + 名手·绝顶BOSS；
*    全档起步最低 600ms，档位越高想得越久越强，顶段接入名手引擎(JNI)在最高层数下压阵
 *  - 悔棋（悔掉电脑应着+你上一手，记作变招）、再开一盘、战绩
 *  - 一盘一结：不积累半局棋谱。中途退出 → 下次进入自动弹「续盘?」（续盘/重新开一局）；
 *    有输赢/和棋的终局 → 记战绩并弹「保存棋谱」（一键存进宝库「挑战棋谱」专属文件夹，
 *    命名=年月日+对手+先后手；不再走系统文件选择器，省得用户事后在宝库里找不着）
 *  - 不做手工存盘/续盘/删档按钮，存续完全由系统弹窗接管，界面更紧凑
 */
class ChallengeActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    companion object {
        private const val TAG = "Challenge"
        private const val PREFS_STATE = "challenge_state"
        private const val PREFS_RECORDS = "challenge_records"
        private const val HASH_MB = 64
        private const val THREADS = 2
        private const val MAX_RECORDS = 80

        // ★ 巅峰三档由奕狐内置的雪鱼(Fairy-Stockfish Xiangqi, UCI)压阵：
        //   NNUE+多线程+movetime 限时，既快又强。
        private const val XUEYU = XueyuEngine.KEY

        /** ★★★ 雪鱼→皮卡鱼 交接点：前 20 回个合(=40 半回合)归雪鱼本人走；
         *   第 21 回合（第 41 半回合）起交接给皮卡鱼(JniEngine)，按雪鱼层数-4 设 10/12/14 层。 */
        private const val XUEYU_HANDOFF_PLIES = 40

        /** ★★★ 开局规范化：第 8~25 关（0 基索引 7~24）的前 5 回合（=10 半回合），
         *   不分红黑、一律由皮卡鱼按 14 层代走，走完开局再交回本关引擎。
         *   目的：低阶引擎开局容易乱支着（如"起马"后黑方随手"出车"，白送炮口），
         *   先由皮卡鱼把开局走正，之后各关实力差照常在自身档位体现。 */
        // ★ 起点下探到 0：把「更夫～押司」这几档低阶也纳入开局规范化。
        //   只动下限，不动上限 24、不动 PLIES/DEPTH，也不碰 >24 档位各自的接力安排。
        private const val OPENING_PIKA_FROM_INDEX = 0
        private const val OPENING_PIKA_TO_INDEX = 24
        private const val OPENING_PIKA_PLIES = 10
        private const val OPENING_PIKA_DEPTH = 14
        private const val OPENING_PIKA_MS = 8000L

        /** ★★★ 内置 CCS(CCStockfish)：挑战模式新增顶档引擎（纯引擎走子）★★★ */
        private const val CCFISH = CcstockfishEngine.KEY

        /** 和棋判定：连续 120 半回合（=60回合，红黑各60手）互无吃子 → 判和 */
        private const val DRAW_NO_CAPTURE_PLIES = 120
        /** 挑战模式违规判定上限：人脑连续违规 5 次判负；AI 连续 3 次改走合法次优 */
        private const val HUMAN_ILLEGAL_LIMIT = 5
        private const val AI_ILLEGAL_LIMIT = 3
    }

    // ═══ 引擎等级：4 套非神经网络引擎 × 7 档 = 28 级 ═══
    //  engine = 引擎键；ms = 单步思考毫秒；depth = 计算层数（>0 时按层数制，优先于 ms）。
    //  棋力由「层数」拉开：最强档(国手)=18层，向下每档 -2，最低到 10 层，10 层以下交给别的引擎。
    data class EngineLevel(val rank: String, val engine: String, val ms: Long, val desc: String, val depth: Int = 0, val relay: Boolean = false)

    /** 引擎键 → 界面上人类可读的中文名 */
    private fun engineLabel(key: String): String = when (key) {
        SandboxEngine.UFX -> "UFX·试水"
        SandboxEngine.ELEEYE -> "相眼"
        SandboxEngine.CHAMELEON -> "变色龙"
        SandboxEngine.NEWGG -> "神机"
        XUEYU -> "雪鱼"
        CCFISH -> "鲟(CCS)"
        else -> key
    }

    /** 挑战档：从最弱街头到最强 BOSS（名手·绝顶）逐级加难。
      *  低段四套非神经网络引擎（UFX/相眼/变色龙/神机）桥接 SandboxEngine；
      *  中段在四套引擎内补齐中间官衔；顶段插入名手引擎（JNI 接入棋者象棋存档），末位排"名手·绝顶"BOSS。
      *  棋力既看引擎强度(engine)，也看思考毫秒(ms)与计算层数(depth)。 */
    private val engineLevels = listOf(
        // ── 街头·UFX（最弱，逐级加点思考）──
        EngineLevel("更夫", SandboxEngine.UFX, 600, "步法松"),
        EngineLevel("皂隶", SandboxEngine.UFX, 800, "偶有妙手"),
        EngineLevel("衙役", SandboxEngine.UFX, 1000, "稳扎稳打"),
        EngineLevel("捕快", SandboxEngine.UFX, 1300, "小有章法"),
        EngineLevel("捕头", SandboxEngine.UFX, 1500, "攻守有度"),
        EngineLevel("仵作", SandboxEngine.UFX, 2000, "渐入佳境"),
        EngineLevel("押司", SandboxEngine.UFX, 3000, "UFX·满档"),
        // ── 乡野·相眼(ELEEYE) ──
        EngineLevel("典史", SandboxEngine.ELEEYE, 600, "家学渊源"),
        EngineLevel("主簿", SandboxEngine.ELEEYE, 800, "熟读古谱"),
        EngineLevel("县尉", SandboxEngine.ELEEYE, 1000, "算度渐深"),
        EngineLevel("县丞", SandboxEngine.ELEEYE, 1300, "谋定后动"),
        EngineLevel("县令", SandboxEngine.ELEEYE, 1500, "坐镇一方"),
        EngineLevel("通判", SandboxEngine.ELEEYE, 2000, "老辣"),
        EngineLevel("知州", SandboxEngine.ELEEYE, 3000, "相眼·满档"),
        // ── 府道·变色龙(CHAMELEON) ──
        EngineLevel("同知", SandboxEngine.CHAMELEON, 600, "稳中带变"),
        EngineLevel("知府", SandboxEngine.CHAMELEON, 800, "章法森严"),
        EngineLevel("按察使", SandboxEngine.CHAMELEON, 1000, "滴水不漏"),
        EngineLevel("转运使", SandboxEngine.CHAMELEON, 1300, "进退自如"),
        EngineLevel("提刑", SandboxEngine.CHAMELEON, 1500, "杀伐果断"),
        EngineLevel("布政使", SandboxEngine.CHAMELEON, 2000, "攻守兼备"),
        EngineLevel("巡抚", SandboxEngine.CHAMELEON, 3000, "变色龙·满档"),
        // ── 封疆·神机(NEWGG，层数制：10→16) ──
        EngineLevel("都指挥使", SandboxEngine.CHAMELEON, 800, "变色龙·补充"),
        EngineLevel("总督", SandboxEngine.CHAMELEON, 1000, "变色龙·补充"),
        EngineLevel("提督", SandboxEngine.NEWGG, 1000, "运筹帷幄", 10),
        EngineLevel("都督", SandboxEngine.NEWGG, 1300, "坐镇三军", 12),
        EngineLevel("兵部尚书", SandboxEngine.NEWGG, 1500, "谋国如棋", 14),
        EngineLevel("骠骑将军", SandboxEngine.NEWGG, 2000, "纵横无敌", 16),
        EngineLevel("车骑将军", SandboxEngine.NEWGG, 2500, "神挡杀神", 18),
        EngineLevel("太尉·天子近臣", SandboxEngine.NEWGG, 3000, "神机·满档", 20),
        // ── 巅峰·雪鱼(奕狐 Fairy-Stockfish Xiangqi，NNUE UCI 引擎) ──
        // ★ 按层数(深度)搜索 14/16/18：不再只按秒限时，越顶层越深越强。
        //   ms 仅为超时兜底(层数未到时的安全上限)，主控是层数 depth。
        EngineLevel("国手·雪鱼", XUEYU, 8000, "雪鱼·入室", 14),
        EngineLevel("宗师·雪鱼", XUEYU, 12000, "雪鱼·巅峰", 16),
        // ── 绝顶·雪鱼(原 BOSS，最高层) ──
        EngineLevel("雪鱼·绝顶", XUEYU, 16000, "快刀尽出", 18),
        // ── 巅峰·鲟(内置 CCStockfish，NNUE UCI 纯引擎走子) ──
        //   新增 3 档：16层全程、18层全程、18层前20手自走后皮卡鱼接力。
        EngineLevel("国手·鲟", CCFISH, 12000, "16层·全程自走", 16),
        EngineLevel("宗师·鲟", CCFISH, 14000, "18层·全程自走", 18),
        EngineLevel("鲟·绝顶·接龙", CCFISH, 16000, "18层·前20手自走，后皮卡鱼接力", 18, relay = true)
    )

    // ★ 轻引擎统一接口：让 SandboxEngine(4 套内存管道引擎) 与雪鱼/鲟/皮卡鱼同型驱动
    private interface ChallengeEngine {
        fun isEngineReady(): Boolean
        fun searchBestMove(b: Board, timeMs: Long?, depth: Int?, onResult: (Move?) -> Unit)
        fun stopSearch()
        fun shutdown()
    }

    /** 把底层引擎（SandboxEngine / XueyuEngine / CcstockfishEngine / JniEngine皮卡鱼）包成统一接口适配器 */
    private fun adapt(raw: Any): ChallengeEngine = object : ChallengeEngine {
        override fun isEngineReady(): Boolean = when (raw) {
            is SandboxEngine -> raw.isEngineReady()
            is XueyuEngine -> raw.isEngineReady()
            is CcstockfishEngine -> raw.isEngineReady()
            is JniEngine -> raw.isEngineReady()
            else -> false
        }
        override fun searchBestMove(b: Board, timeMs: Long?, depth: Int?, onResult: (Move?) -> Unit) {
            when (raw) {
                is SandboxEngine -> raw.searchBestMove(b, timeMs = timeMs, depth = depth, onResult = onResult)
                is XueyuEngine -> raw.searchBestMove(b, timeMs = timeMs, depth = depth, onResult = onResult)
                is CcstockfishEngine -> raw.searchBestMove(b, timeMs = timeMs, depth = depth, onResult = onResult)
                is JniEngine -> raw.searchBestMove(b, timeMs = timeMs, depth = depth, onResult = onResult)
                else -> onResult(null)
            }
        }
        override fun stopSearch() {
            when (raw) {
                is SandboxEngine -> raw.stopSearch()
                is XueyuEngine -> raw.stopSearch()
                is CcstockfishEngine -> raw.stopSearch()
                is JniEngine -> raw.stopSearch()
                else -> {}
            }
        }
        override fun shutdown() {
            when (raw) {
                is SandboxEngine -> raw.shutdown()
                is XueyuEngine -> raw.shutdown()
                is CcstockfishEngine -> raw.shutdown()
                is JniEngine -> raw.shutdown()
                else -> {}
            }
        }
    }

    // ═══ 对局状态 ═══
    private var board = Board()

    // ★ 走子/吃子/将军短音效（独立进程也读取同款音效音量偏好）
    private var soundManager: com.pikafish.chess.utils.SoundManager? = null
    private val moves = mutableListOf<String>()      // UCI 走法序列（存档/续盘/记录用）
    private var levelIndex = 0
    private var humanColor = PieceColor.RED           // 人持颜色，开局商议定死，中途不可换
    @Volatile private var aiThinking = false
    @Volatile private var gameOver = false
    @Volatile private var searchGen = 0               // 撤销/重开时作废在途搜索回调
    @Volatile private var pendingAiMove = false       // 引擎未就绪时缓存"该电脑走"
    @Volatile private var hasVariation = false        // 是否悔过棋（存档标为"含变招"）
    @Volatile private var currentResult = "对局中"    // 终局结果（胜/负）供存档/导出抬头

    // ═══ 独立沙盒引擎（SandboxEngine 桥接四套非神经网络引擎；顶段接雪鱼/鲟/皮卡鱼）═══
    private var engine: ChallengeEngine? = null
    @Volatile private var engineReady = false
    @Volatile private var engineStarting = false
    @Volatile private var currentEngineKey: String? = null

    // ★★★ 雪鱼后程交接引擎：皮卡鱼(JniEngine 主引擎)专用于"第 21 回合起"的残局段。
    //   前 20 回合归雪鱼(Fairy-Stockfish NNUE)，21 回合起按 10/12/14 层交接给皮卡鱼，
    //   解决雪鱼"占据优势却不知如何延续、慢慢磨成和棋"的后程拉胯。
    private var pikaEngine: ChallengeEngine? = null
    @Volatile private var pikaReady = false
    @Volatile private var pikaStarting = false
    @Volatile private var pikaFailed = false     // 皮卡鱼不可用：回退本关引擎，避免开局段卡死

    // ★★★ BOSS 档雪鱼的中国象棋棋谱全集(CBL)开局/中局/残局库 ★★★
    @Volatile private var bossBook: LocalBookManager? = null
    private val bossBookLock = Any()
    @Volatile private var bossBookLoading = false

    // ═══ 挑战模式违规（下非法着）判定计数：人脑连续5次判负；AI连续3次强制改走合法着 ═══
    private var humanIllegalStreak = 0
    private var aiIllegalStreak = 0

    // ═══ 棋例（亚洲棋规·循环着法判定）：与外部"探讨型"共用同一套 RepetitionRule（原封不动），
    //  但挑战模式是"严肃型对弈"：AI 违规 → 撤销并强制变着；人脑违规 → 记连续违规，连 5 次判负。═══
    private val repetitionRule = RepetitionRule()
    private var warnedViolator: PieceColor? = null
    private val boardSnapshots = mutableListOf<String>()   // FEN 快照：boardSnapshots[0]=开局，boardSnapshots[i]=第i手后

    // ★ 全局底色：本界面根悬浮容器（套用全局托底色，标题/状态文字自动换衬托色）
    private var rootFrame: FrameLayout? = null

    // ═══ 棋盘视图 ═══
    private val chessBoard by lazy {
        ChessBoardView(this).also { b ->
            b.onMoveMade = { move ->
                handleHumanMove(move)
                b.clearSelection()
                true
            }
        }
    }

    // ═══ UI 部件 ═══
    // ★ 顶栏/抬头已移除：不再有 titleText，挑战对象等级由底部状态行清晰标示，空间全留给棋盘
    private lateinit var statusText: TextView
    private lateinit var eccoText: TextView     // ★ ECCO/开局同步行（挂在状态行下，只往下填空白，不顶棋盘/按钮）
    private lateinit var boardWrap: FrameLayout
    private lateinit var btnUndo: Button

    // ★★★ 底部菜单面板：以「面板顶边距屏幕底」为唯一基准定位（可长按拖动，位置持久）。
    //   面板顶边一旦钉死，状态行/开局行内容怎么变高变矮，顶边都不动 → 不再"往上窜"；
    //   棋盘可视带下界就取这条顶边 → 棋盘与菜单恒定互斥，任何机型/字号都不重叠。
    private lateinit var bottomPanel: LinearLayout
    private var bottomPanelLp: FrameLayout.LayoutParams? = null
    private var menuTopFromBottomDp = -1f          // 面板顶边距屏幕底(dp)；-1=首次按原观感初始化

    // ═══ 底部操作菜单行（悔棋/再开一盘/战绩/存谱/返回）═══
    private lateinit var actionRow: LinearLayout        // 悔棋/再开一盘/战绩/存谱/返回 所在行

    private val statePrefs by lazy { getSharedPreferences(PREFS_STATE, MODE_PRIVATE) }
    private val recordPrefs by lazy { getSharedPreferences(PREFS_RECORDS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            onCreateSafe(savedInstanceState)
        } catch (t: Throwable) {
            val stack = android.util.Log.getStackTraceString(t)
            Log.e(TAG, "挑战初始化被拦截（避免闪退）", t)
            // ★ 把完整堆栈原样落一份到应用私有目录，便于排查
            try {
                java.io.FileOutputStream(
                    java.io.File(filesDir, "challenge_error.txt")).use { out ->
                    out.write(stack.toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) { }
            val sc = ScrollView(this).apply { setBackgroundColor(android.graphics.Color.parseColor("#DD211005")) }
            val tv = TextView(this).apply {
                text = "挑战初始化没绷住，但已拦下，没闪退。\n\n" +
                    "【异常】" + t.javaClass.simpleName + "：" + (t.message ?: t.toString()) + "\n\n" +
                    "【出错位置（请把这开头几行抄/截图给我）】\n" + stack + "\n\n" +
                    "（已记录到日志 Challenge 与私有目录 challenge_error.txt，可卸载重装前用 adb 导出）"
                setTextColor(android.graphics.Color.parseColor("#F0E8D0"))
                textSize = 12f
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            sc.addView(tv)
            setContentView(sc)
        }
    }

    private fun onCreateSafe(savedInstanceState: Bundle?) {
        // ★ 初始化短音效（复用/简化版音效管理器，走子/吃子/将军有响）
        soundManager = com.pikafish.chess.utils.SoundManager().also { it.loadSounds(this) }

        // ★★★ 视觉跟随主界面（棋盘大小/棋子大小/棋线/背景/皮肤/个性化）：
        //   在 setContentView 前一次性套好 → 进沙盒首帧即与主界面一致，绝不闪屏跳动。
        BoardVisualPrefs.applyToBoard(this, chessBoard)

        // ★ 恢复用户拖动定下的底部菜单位置（面板顶边距屏幕底 dp）；-1=首次按原观感初始化
        menuTopFromBottomDp = statePrefs.getFloat("menu_top_from_bottom_dp", -1f)

        setContentView(buildContentView())
        // 套用全局托底色 + 让标题/状态文字自动换衬托色
        applyGlobalBackdrop()
        // ★ 红黑上下与人持颜色同步：人持黑 → 黑子在下，天然顺手
        chessBoard.setFlipped(humanColor == PieceColor.BLACK)

        // 有未下完的残局 → 弹"是否续盘"；否则直接开新盘设置
        if (statePrefs.getBoolean("active", false)) {
            showResumeDialog()
        } else {
            showSetupDialog()
        }
    }

    /** 套用全局托底色：根背景用所选底色，标题/状态文字换对应衬托色便于阅读。 */
    private fun applyGlobalBackdrop() {
        try {
            val color = com.pikafish.chess.ui.GlobalBackdrop.currentColor(this)
            val fg = com.pikafish.chess.ui.GlobalBackdrop.foreground(this, color)
            rootFrame?.background = com.pikafish.chess.ui.GlobalBackdrop.backdropDrawable(this)
            if (::statusText.isInitialized) statusText.setTextColor(fg)
            if (::eccoText.isInitialized) eccoText.setTextColor(fg)
        } catch (_: Exception) { }
    }

    // ══════════════════════════════════════════════════════
    //  UI 构建（沙盘式：棋盘 + 可收缩调节面板 + 底部状态）
    // ══════════════════════════════════════════════════════
    private fun buildContentView(): FrameLayout {
        // ★ 与主界面同一套"悬浮贴纸"结构：棋盘从 y=0 铺满全屏，顶栏是悬浮框（不把棋盘往下顶），
        //   底部操作/状态为底贴纸。棋盘锚定规则同主界面 —— 顶栏→首行棋子间距、起始点、大小全一致。
        val stage = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#DD211005"))
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
        //   挑战对象等级由底部状态行标示，顶栏只作标题框。
        val bar = com.pikafish.chess.ui.BoardVisualPrefs.buildTopBar(this, "※ 挑战模式")
        stage.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(BoardVisualPrefs.TOP_BAR_DP),
            android.view.Gravity.TOP))

        // ── 底部悬浮层：操作菜单行 + 状态行 + 开局行 ──
        // ★ 菜单底色 = 主界面「菜单底色」，顶栏/底部菜单都和主界面颜色统一、跟着变。
        val menuBgCh = com.pikafish.chess.ui.GlobalBackdrop.menuBackgroundColor(this)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(menuBgCh)
        }
        bottomPanel = bottom
        // 操作菜单行：悔棋/再开一盘/战绩/导出/返回
        bottom.addView(buildActionRow())
        statusText = TextView(this).apply {
            setTextColor(adaptiveFg)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bottom.addView(statusText)
        // ★ ECCO/开局同步行：紧贴信息行下方，随走子实时更新“现在走到什么开局”。
        eccoText = TextView(this).apply {
            setTextColor(adaptiveFg)
            textSize = 13f
            setPadding(dp(8), dp(2), dp(8), dp(5))
            maxLines = 2
            text = "本局开局：—"
        }
        bottom.addView(eccoText)
        updateStatus()
        // ★ 整块底部面板（按钮行+状态行+ECCO行一起）整体定位：位置以「面板顶边距屏幕底」为基准，
        //   见 applyChallengeMenuPosition()。① 内容高度变化不再顶动顶边（治"往上窜"）；
        //   ② 棋盘可视带下界取面板顶边 → 恒定互斥、任何机型不重叠；③ 可长按文字行上下拖动并持久。
        val bottomLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM)
        bottomPanelLp = bottomLp
        stage.addView(bottom, bottomLp)

        // ★ 长按/拖动这两排文字，整块菜单上下移动（只上下、不左右），松手即记住。
        attachMenuVerticalDrag(statusText)
        attachMenuVerticalDrag(eccoText)

        // 首次定位：等面板量出真实高度后钉上顶边。
        applyChallengeMenuPosition()

        return stage
    }

    /**
     * ★★★ 底部菜单整体定位（唯一基准 = 面板顶边距屏幕底）。
     *   bottomMargin = 顶距底 − 面板高 ⇒ 面板内容变高变矮，顶边永远钉死，绝不"往上窜"。
     *   同时把棋盘可视带下界设成这条顶边 ⇒ 棋盘与菜单恒定互斥，绝无重叠（任何机型/缩放）。
     */
    private fun applyChallengeMenuPosition(retries: Int = 0) {
        val stage = rootFrame ?: return
        val lp = bottomPanelLp ?: return
        if (!::bottomPanel.isInitialized) return
        val screenH = stage.height
        if (screenH <= 0) {
            if (retries < 8) stage.post { applyChallengeMenuPosition(retries + 1) }
            return
        }
        try {
            val panelH = bottomPanel.height
            if (panelH <= 0) {
                if (retries < 8) stage.post { applyChallengeMenuPosition(retries + 1) }
                return
            }
            val density = resources.displayMetrics.density
            val topBarPx = dp(BoardVisualPrefs.TOP_BAR_DP)
            // 首次：沿用原观感（原 = 距底 271dp + 按钮行 + 状态行，ECCO 行不计入顶边）
            if (menuTopFromBottomDp <= 0f) {
                val eccoH = if (::eccoText.isInitialized) eccoText.height else 0
                menuTopFromBottomDp = ((dp(271) + dp(10)) + (panelH - eccoH)) / density
            }
            val minTop = 70f
            val maxTop = ((screenH - topBarPx - dp(70)) / density).coerceAtLeast(minTop + 20f)
            menuTopFromBottomDp = menuTopFromBottomDp.coerceIn(minTop, maxTop)
            val topFromBottomPx = dp(menuTopFromBottomDp.toInt())
            lp.bottomMargin = (topFromBottomPx - panelH).coerceAtLeast(0)
            bottomPanel.layoutParams = lp
            // 棋盘可视带下界 = 面板顶边：棋盘顶排锚定、按带内可容纳上限自适应 → 永不压到菜单
            val bandBottom = (screenH - topFromBottomPx).coerceAtLeast(topBarPx + dp(60))
            chessBoard.setContentBand(topBarPx.toFloat(), bandBottom.toFloat())
            chessBoard.requestLayout()
        } catch (_: Exception) { }
    }

    /** 记住拖动后的菜单位置（重启原样恢复）。 */
    private fun saveChallengeMenuPosition() {
        try { statePrefs.edit().putFloat("menu_top_from_bottom_dp", menuTopFromBottomDp).apply() } catch (_: Exception) { }
    }

    /**
     * ★ 长按拖动底部菜单文字行：只上下移动整块菜单，松手落盘。
     *   拖动阈值 8dp，避免误触；拖动时即时重排（顶边恒定、棋盘互斥让位）。
     */
    private fun attachMenuVerticalDrag(handle: View) {
        val density = resources.displayMetrics.density
        var dragging = false
        var downRawY = 0f
        var startTopFromBottom = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    dragging = false
                    startTopFromBottom = if (menuTopFromBottomDp > 0f) menuTopFromBottomDp else 385f
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = downRawY - e.rawY          // 上划为正 → 菜单上移 → 顶距底变大
                    if (!dragging && kotlin.math.abs(dy) > 8f * density) dragging = true
                    if (dragging) {
                        menuTopFromBottomDp = startTopFromBottom + dy / density
                        applyChallengeMenuPosition()
                        true
                    } else false
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) saveChallengeMenuPosition()
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun actionButton(label: String, weight: Float, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            // ★ 菜单栏白底黑字：清晰简单，一眼明了（不再用灰/棕底压暗字）
            setTextColor(android.graphics.Color.parseColor("#111111"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#FFFFFF"))
                setStroke(dp(1), android.graphics.Color.parseColor("#333333"))
                cornerRadius = dp(4).toFloat()
            }
            isAllCaps = false
            gravity = Gravity.CENTER
            // ★ 按钮高度定死 36dp：X/Y 辅助面板只挪整块面板位置，绝不拉伸单个按钮。
            layoutParams = LinearLayout.LayoutParams(0, dp(36), weight)
            setOnClickListener { onClick() }
        }

    /** ★ 五点式菜单行：悔棋/再开一盘/战绩/存谱/返回 —— 5 个菜单并成一行，省空间给棋盘棋子。
     *  ★ 按钮高度定死 36dp；整块面板按用户定好位置（Y底=271、X=0）固定，不再拉伸按钮。 */
    private fun buildActionRow(): LinearLayout {
        actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        btnUndo = actionButton("悔棋", 1f) { onUndo() }
        actionRow.addView(btnUndo)
        actionRow.addView(actionButton("再开一盘", 1f) { restartGame() })
        actionRow.addView(actionButton("战绩", 1f) { showRecordsDialog() })
        actionRow.addView(actionButton("存谱", 1f) { doSaveToFile() })
        // ★ 复盘按钮已按用户要求移除（含其弹窗逻辑）。悔棋/续盘仍照常用 replayBoard 重建棋盘。
        //   返回并入本行：顶栏已移除，返回就点这里
        actionRow.addView(actionButton("返回", 1f) { finish() })
        return actionRow
    }

    private fun panelTitle(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(adaptiveFg)
        textSize = 13f
        setTypeface(null, android.graphics.Typeface.BOLD)
    }

    /** ★ 自适应前景色：随全局托底色深浅自动切深/浅字，杜绝"浅字配浅底"灰蒙蒙看不清。 */
    private val adaptiveFg: Int
        get() = runCatching {
            val c = com.pikafish.chess.ui.GlobalBackdrop.currentColor(this)
            com.pikafish.chess.ui.GlobalBackdrop.foreground(this, c)
        }.getOrDefault(android.graphics.Color.parseColor("#202020"))

    // ═══ 棋盘基准高度（dp）：与主界面棋盘外罩理想高度同源 ≈1.16×屏宽 ═══
    private fun baseBoardHeightDp(): Int =
        ((resources.displayMetrics.widthPixels / resources.displayMetrics.density) * 1.16f).toInt().coerceAtLeast(400)

    // ══════════════════════════════════════════════════════
    //  开局设置 / 续盘
    // ══════════════════════════════════════════════════════
    // ★ 进入挑战：系统检测到上一盘没下完 → 弹「续盘?」；否则直接开新局。
    //  选项只有：续盘 / 重新开一局（放弃上一盘）/ 返回。不积累半局棋谱，一盘一结。
    private fun showResumeDialog() {
        val savedLevel = statePrefs.getInt("level", 0).coerceIn(0, engineLevels.size - 1)
        val savedColor = if (statePrefs.getString("humanColor", "red") == "black") PieceColor.BLACK else PieceColor.RED
        val savedMoves = statePrefs.getString("moves", "").orEmpty()
        val stepCount = if (savedMoves.isBlank()) 0 else savedMoves.split(" ").size
        val rank = engineLevels[savedLevel].rank
        val colorName = if (savedColor == PieceColor.RED) "红" else "黑"
        AlertDialog.Builder(this, R.style.Theme_ChallengeDialogLight)
            .setTitle("请选择")
            .setMessage("上一盘还未下完（对手：$rank，我持$colorName，已走 $stepCount 手）。\n\n「续盘」接着下；「重新开一局」则放弃上一盘重新设局。")
            .setPositiveButton("续盘") { _, _ ->
                loadSavedGame()
            }
            .setNeutralButton("重新开一局", { _, _ ->
                clearSavedState()
                showSetupDialog()
            })
            .setNegativeButton("返回", { _, _ -> finish() })
            .setCancelable(false)
            .show()
    }

    private fun loadSavedGame() {
        levelIndex = statePrefs.getInt("level", 0).coerceIn(0, engineLevels.size - 1)
        humanColor = if (statePrefs.getString("humanColor", "red") == "black") PieceColor.BLACK else PieceColor.RED
        chessBoard.setFlipped(humanColor == PieceColor.BLACK)
        val savedMoves = statePrefs.getString("moves", "").orEmpty()
        moves.clear()
        if (savedMoves.isNotBlank()) moves.addAll(savedMoves.split(" ").filter { it.isNotEmpty() })
        currentResult = "对局中"
        hasVariation = false
        replayBoard(restartAiIfNeeded = true)
        // ★ 视觉已由 onCreateSafe 里的 BoardVisualPrefs.applyToBoard 跟随主界面，这里不再自设
    }

    private fun showSetupDialog() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(6))
        }
        val DARK = android.graphics.Color.parseColor("#2B2118")
        view.addView(panelTitle("对手等级（32 级，从街头到名手·绝顶 BOSS，越往上引擎越强）").apply {
            setTextColor(DARK)
        })

        val levelScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300))
        }
        val levelGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        engineLevels.forEachIndexed { i, lv ->
            val rb = RadioButton(this@ChallengeActivity).apply {
                text = "${i + 1}.${lv.rank}·${lv.desc}（${if (lv.depth > 0) "层${lv.depth}" else "${lv.ms}毫秒/步"}）"
                setTextColor(DARK)
                textSize = 13f
                isChecked = (i == levelIndex)
                id = 1000 + i
            }
            levelGroup.addView(rb)
        }
        levelScroll.addView(levelGroup)
        view.addView(levelScroll)

        view.addView(panelTitle("我方执子").apply {
            setTextColor(DARK)
        })
        val colorGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbRed = RadioButton(this).apply {
            text = "我持红"
            setTextColor(android.graphics.Color.parseColor("#C0392B"))
            textSize = 14f
            isChecked = (humanColor == PieceColor.RED)
            id = 2001
        }
        val rbBlack = RadioButton(this).apply {
            text = "我持黑"
            setTextColor(DARK)
            textSize = 14f
            isChecked = (humanColor == PieceColor.BLACK)
            id = 2002
        }
        colorGroup.addView(rbRed)
        colorGroup.addView(rbBlack)
        view.addView(colorGroup)

        AlertDialog.Builder(this, R.style.Theme_ChallengeDialogLight)
            .setTitle("挑战对战")
            .setView(view)
            .setPositiveButton("开始对战") { _, _ ->
                val lvId = levelGroup.checkedRadioButtonId
                levelIndex = if (lvId >= 1000) (lvId - 1000).coerceIn(0, engineLevels.size - 1) else levelIndex
                humanColor = if (colorGroup.checkedRadioButtonId == 2002) PieceColor.BLACK else PieceColor.RED
                startNewGame()
            }
            .setNegativeButton("取消") { _, _ ->
                // ★ 关键修复：取消 = 回到老历史节点，绝不重置当前残局
                if (moves.isEmpty()) {
                    finish()
                } else {
                    replayBoard(restartAiIfNeeded = true)
                    toast("已取消，回到原局")
                }
            }
            .setCancelable(true)
            .show()
    }

    /** 新开局：重置棋盘、清空走法，若电脑执先则请它先走 */
    private fun startNewGame() {
        gameOver = false
        aiThinking = false
        moves.clear()
        board = Board()
        boardSnapshots.clear()
        boardSnapshots.add(board.toFen())
        warnedViolator = null
        humanIllegalStreak = 0
        aiIllegalStreak = 0
        hasVariation = false
        currentResult = "对局中"
        chessBoard.setBoard(board)
        chessBoard.setFlipped(humanColor == PieceColor.BLACK)
        chessBoard.clearSelection()
        chessBoard.setLastMove(null)
        searchGen++
        clearSavedState()          // 旧续盘状态作废，新开一局
        updateStatus()
        resetEngineIfNeeded(engineLevels[levelIndex].engine)
        ensureEngineAsync()
        if (board.currentPlayer != humanColor) {
            triggerAiMove()
        }
    }

    // ══════════════════════════════════════════════════════
    //  再开一盘（一盘一结）：当前这盘若没下完，放弃才需确认；下完的走终局弹窗，到这里直接设新局
    // ══════════════════════════════════════════════════════
    private fun restartGame() {
        // 没有任何历史（既没走棋、也无残局续盘态）→ 直接进开局设置
        if (moves.isEmpty() && !statePrefs.getBoolean("active", false)) {
            showSetupDialog()
            return
        }
        val hasMoves = moves.isNotEmpty()
        if (hasMoves) {
            AlertDialog.Builder(this, R.style.Theme_ChallengeDialogLight)
                .setTitle("放弃当前这盘？")
                .setMessage("当前这一盘已走了 ${moves.size} 手，还没下完（一盘一结，没结果不存谱）。\n「重新开一局」将放弃它；「返回」则回到原局继续下。")
                .setPositiveButton("重新开一局", { _, _ ->
                    clearSavedState()
                    showSetupDialog()
                })
                .setNegativeButton("返回", { _, _ ->
                    replayBoard(restartAiIfNeeded = true)
                    toast("已取消，回到原局")
                })
                .setCancelable(false)
                .show()
        } else {
            clearSavedState()
            showSetupDialog()
        }
    }

    // ══════════════════════════════════════════════════════
    //  人机对弈流程（只走棋，无任何 AI 文字输出）
    // ══════════════════════════════════════════════════════

    /** 落子后播放走子/吃子/将军音效。capBefore 为落子前的目标格棋子（非空即吃子）。 */
    private fun playMoveSound(capBefore: com.pikafish.chess.model.Piece?) {
        try {
            val sm = soundManager ?: return
            if (capBefore != null) sm.play("eat") else sm.play("move")
            if (board.isInCheck(board.currentPlayer)) sm.play("check")
        } catch (_: Exception) {}
    }

    private fun handleHumanMove(move: Move) {
        if (gameOver) return
        if (aiThinking) { toast("电脑正在思考，稍候…"); return }
        if (board.currentPlayer != humanColor) { toast("现在轮到电脑走棋"); return }

        val legal = board.getLegalMoves(humanColor).any { it.from == move.from && it.to == move.to }
        if (!legal) {
            // ★ 违规判定：人脑下非法着，记连续违规；同一次落子紧锣密鼓连违5次 → 判负
            humanIllegalStreak++
            if (humanIllegalStreak >= HUMAN_ILLEGAL_LIMIT) {
                humanIllegalStreak = 0
                judgeHumanIllegalLoss()
            } else {
                toast("这步棋不合法（连续违规 $humanIllegalStreak/$HUMAN_ILLEGAL_LIMIT，走对即清零）")
            }
            return
        }
        // 走对 / 合法：先落子，再走棋例判定（违规则撤销并记连续违规）
        val capBefore = board.getPiece(move.to)
        board.makeMoveUnchecked(move)
        moves.add(move.toUci())
        boardSnapshots.add(board.toFen())
        chessBoard.setBoard(board)
        chessBoard.setLastMove(move)
        playMoveSound(capBefore)
        if (checkGameOver()) return
        if (applyRepetitionRule()) return
        // 走对且不违规：违规计数清零
        humanIllegalStreak = 0
        updateStatus()
        triggerAiMove()
    }

    /** 人脑连续违规达 5 次：直接判负（一盘一结，不记积分异常）。 */
    private fun judgeHumanIllegalLoss() {
        gameOver = true
        currentResult = "负"
        recordResult("负")
        clearSavedState()
        updateStatus()
        showResultDialog("电脑", "负", "连续违规 $HUMAN_ILLEGAL_LIMIT 次（非法着/违反棋例）判负")
    }

    /** 是否最终 BOSS 档（雪鱼·绝顶）——只有该档挂载 CBL 棋谱全集库 */
    private fun isBossLevel(): Boolean = levelIndex == engineLevels.size - 1

    /** 后台装载 BOSS 档的 CBL 棋谱全集开局/中局/残局库（只装一次，不阻塞 UI/引擎） */
    private fun ensureBossBookAsync() {
        if (bossBook != null || bossBookLoading) return
        synchronized(bossBookLock) {
            if (bossBook != null) return
            if (bossBookLoading) return
            bossBookLoading = true
        }
        Thread {
            try {
                val mgr = LocalBookManager()
                val loaded = mgr.loadBuiltinCblCollection(this)
                if (loaded != null) {
                    synchronized(bossBookLock) { bossBook = mgr }
                    Log.i(TAG, "BOSS 雪鱼 CBL 棋谱全集就绪：${loaded.entryCount} 个局面可直查")
                } else {
                    Log.w(TAG, "BOSS CBL 全集装载失败（将退化为纯引擎搜索）")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "BOSS CBL 装载异常", e)
            } finally {
                bossBookLoading = false
            }
        }.also { it.name = "boss-cbl-book"; it.start() }
    }

    private fun triggerAiMove() {
        if (gameOver || aiThinking) return
        if (board.currentPlayer == humanColor) return
        val lv = engineLevels[levelIndex]
        val openDepth = openingPikaDepth()            // 第 8~25 关开局段：前 5 回合皮卡鱼14层代走
        val pikaDepth = openDepth ?: handoffPikaDepth(lv)   // 皮卡鱼段：雪鱼档已走满 20 回合 → 第 21 回合起
        val eng = if (pikaDepth != null) pikaEngine else engine
        if (pikaDepth != null) {
            // ★ 已交接皮卡鱼段：等皮卡鱼引擎就绪
            if (pikaEngine == null || !pikaReady) {
                pendingAiMove = true
                ensurePikaAsync()
                updateStatus()
                return
            }
        } else {
            if (engine == null || !engineReady) {
                pendingAiMove = true
                ensureEngineAsync()
                updateStatus()
                return
            }
        }
        aiThinking = true
        val gen = ++searchGen
        // ★ 开局段用充裕预算走满 14 层；其余仍按本关毫秒/层数。
        val thinkMs = if (openDepth != null) OPENING_PIKA_MS else lv.ms
        val depth = when {
            openDepth != null -> openDepth                                                 // 开局段：皮卡鱼14层
            pikaDepth != null -> pikaDepth                                                 // 交接段：皮卡鱼N层
            lv.depth > 0 -> lv.depth                                                        // 本关层数制引擎
            else -> null
        }
        val b = board.clone()
        updateStatus()
        // ★★★ BOSS 档雪鱼·开局段：先用中国象棋棋谱全集(CBL)当开局/中局/残局库应着。
        //   命中即照谱直着（快、不耗引擎时间）；未命中/未装载时照常交引擎搜索。
        //   ★ 皮卡鱼段不再翻库——直接把残局交给皮卡鱼的后程算力。★★★
        if (pikaDepth == null && isBossLevel()) {
            ensureBossBookAsync()
            val book = bossBook
            if (book != null && book.count() > 0) {
                val hit = book.queryByBoard(board)
                if (hit != null) {
                    val bm = Move.fromUci(hit.uci, board)
                    if (bm != null && isLegalMove(bm)) {
                        aiThinking = false
                        Log.i(TAG, "BOSS 翻库命中(CBL棋谱全集 招=${hit.uci} 库=${hit.bookName})")
                        applyAiMove(bm)
                        return
                    }
                }
            }
        }
        // ★ 防御：交接段引擎尚未就绪（理论上前述守卫已保证，此处兜底避免空指针）
        if (eng == null) {
            aiThinking = false
            pendingAiMove = true
            if (pikaDepth != null) ensurePikaAsync() else ensureEngineAsync()
            updateStatus()
            return
        }
        try {
            eng.searchBestMove(b, timeMs = thinkMs, depth = depth, onResult = { move ->
                runOnUiThread {
                    if (gen != searchGen) return@runOnUiThread   // 已被悔棋/重开作废
                    aiThinking = false
                    if (gameOver) return@runOnUiThread
                    when {
                        move != null && isLegalMove(move) -> {
                            aiIllegalStreak = 0
                            applyAiMove(move)
                        }
                        // ★ AI 违规：给出的着法非法。连续 3 次 → 强制改走一个合法次优着
                        move != null -> {
                            aiIllegalStreak++
                            if (aiIllegalStreak < AI_ILLEGAL_LIMIT) {
                                updateStatus()
                                toast("电脑违规 $aiIllegalStreak/$AI_ILLEGAL_LIMIT，自动另走…")
                                triggerAiMove()
                            } else {
                                aiIllegalStreak = 0
                                aiFallbackMove()
                            }
                        }
                        else -> {
                            if (!checkGameOver()) {
                                // ★ 引擎未回合法着时兜底走一个合法着，绝不再卡死（不再弹"暂未找到走法"）
                                val fb = board.getLegalMoves(board.currentPlayer).firstOrNull()
                                if (fb != null) {
                                    aiIllegalStreak = 0
                                    applyAiMove(fb)
                                } else {
                                    updateStatus(); toast("电脑暂未找到走法")
                                }
                            }
                        }
                    }
                }
            })
        } catch (e: Throwable) {
            aiThinking = false
            Log.e(TAG, "引擎搜索异常", e)
            updateStatus()
            toast("引擎搜索出错：${e.message}")
        }
    }

    private fun isLegalMove(move: Move): Boolean =
        !gameOver && board.getLegalMoves(board.currentPlayer).any { it.toUci() == move.toUci() }

    /** AI 连续违规达限：强制改走一个合法着（兜底，通常为引擎给出的次优/合法首着）。 */
    private fun aiFallbackMove() {
        if (gameOver) return
        val fb = board.getLegalMoves(board.currentPlayer).firstOrNull()
        if (fb != null) {
            applyAiMove(fb)
        } else {
            if (!checkGameOver()) { updateStatus(); toast("电脑无法走棋") }
        }
    }

    private fun applyAiMove(move: Move) {
        if (gameOver) return
        val legal = board.getLegalMoves(board.currentPlayer).any { it.toUci() == move.toUci() }
        if (!legal) {
            if (!checkGameOver()) { updateStatus(); toast("电脑走法异常") }
            return
        }
        val capBefore = board.getPiece(move.to)
        board.makeMoveUnchecked(move)
        moves.add(move.toUci())
        boardSnapshots.add(board.toFen())
        chessBoard.setBoard(board)
        chessBoard.setLastMove(move)
        playMoveSound(capBefore)
        if (checkGameOver()) return
        // ★ 棋例判定：AI 违规 → 撤销并强制变着（提醒 AI 变步法）
        if (applyRepetitionRule()) return
        updateStatus()
        if (board.currentPlayer != humanColor) triggerAiMove()
    }

    // ══════════════════════════════════════════════════════
    //  棋例判定（亚洲棋规·循环着法，严肃版）
    //  RepetitionRule 与外部"探讨型"完全同一套，一字不改；仅处理方式不同：
    //   - 刚走棋方违规 → 撤销这一手：人脑记连续违规（连5次判负），AI 强制改走合法着
    //   - 待走方违规     → 警告其必须变着（下一步仍循环则再判）
    //   - 棋例判和       → 直接和棋
    // ══════════════════════════════════════════════════════
    private fun applyRepetitionRule(): Boolean {
        val verdict = repetitionRule.checkRepetition(boardSnapshots, moves, warnedViolator)
        when (verdict) {
            is RepetitionRule.Verdict.NoRepetition -> {
                warnedViolator = null
                return false
            }
            is RepetitionRule.Verdict.MustChange -> {
                warnedViolator = verdict.violator
                val lastMover = lastMoveColor()
                if (verdict.violator == lastMover) {
                    // 刚走棋方违规 → 撤销这一手
                    undoLastMove()
                    if (verdict.violator == humanColor) humanRuleViolation()
                    else aiRuleViolation()
                    return true
                }
                // 待走方违规 → 警告（其下一步仍循环则再判）
                val side = if (verdict.violator == humanColor) "你" else "电脑"
                toast("$side${verdict.reason}，必须变着")
                return false
            }
            is RepetitionRule.Verdict.ForcedLoss -> {
                warnedViolator = verdict.loser
                val lastMover = lastMoveColor()
                if (verdict.loser == lastMover) {
                    undoLastMove()
                    if (verdict.loser == humanColor) humanRuleViolation()
                    else aiRuleViolation()
                    return true
                }
                val side = if (verdict.loser == humanColor) "你" else "电脑"
                toast("$side${verdict.reason}，必须变着")
                return false
            }
            is RepetitionRule.Verdict.ForcedDraw -> {
                warnedViolator = null
                gameOver = true
                currentResult = "和"
                recordResult("和")
                clearSavedState()
                updateStatus()
                showResultDialog("双方", "和", "棋例判和（${verdict.reason}）")
                return true
            }
        }
        return false
    }

    /** 最后一手走棋方（从最新快照反推：快照的 currentPlayer 是下一步走棋方） */
    private fun lastMoveColor(): PieceColor {
        val lastFen = boardSnapshots.lastOrNull() ?: return humanColor
        val b = Board()
        b.loadFromFen(lastFen)
        return b.currentPlayer.opponent()
    }

    /** 撤销最后一手（moves + 快照 + 重建棋盘） */
    private fun undoLastMove() {
        if (moves.isNotEmpty()) moves.removeAt(moves.size - 1)
        if (boardSnapshots.size > 1) boardSnapshots.removeAt(boardSnapshots.size - 1)
        board = Board()
        for (uci in moves) {
            val m = Move.fromUci(uci, board) ?: continue
            board.makeMoveUnchecked(m)
        }
        chessBoard.setBoard(board)
        chessBoard.clearSelection()
        val last = moves.lastOrNull()?.let { Move.fromUci(it, board) }
        chessBoard.setLastMove(last)
    }

    /** 人脑棋例违规：记连续违规，连 5 次判负（与下非法着共用同一计数）。 */
    private fun humanRuleViolation() {
        humanIllegalStreak++
        if (humanIllegalStreak >= HUMAN_ILLEGAL_LIMIT) {
            humanIllegalStreak = 0
            judgeHumanIllegalLoss()
        } else {
            toast("这步违反棋例（连续违规 $humanIllegalStreak/$HUMAN_ILLEGAL_LIMIT，变着即清零）")
        }
    }

    /** AI 棋例违规：撤销后强制改走一个不违规的合法着（提醒 AI 变步法）。 */
    private fun aiRuleViolation() {
        if (gameOver) return
        val legalMoves = board.getLegalMoves(board.currentPlayer)
        for (m in legalMoves) {
            val b = board.clone()
            b.makeMoveUnchecked(m)
            val testSnapshots = boardSnapshots + b.toFen()
            val testMoves = moves + m.toUci()
            val v = repetitionRule.checkRepetition(testSnapshots, testMoves, warnedViolator)
            val bad = when (v) {
                is RepetitionRule.Verdict.MustChange -> v.violator != humanColor
                is RepetitionRule.Verdict.ForcedLoss -> v.loser != humanColor
                else -> false
            }
            if (!bad) {
                toast("电脑违规，自动变着")
                applyAiMove(m)
                return
            }
        }
        // 全部违规 → 走第一个合法着兜底
        val fb = legalMoves.firstOrNull()
        if (fb != null) applyAiMove(fb)
        else if (!checkGameOver()) { updateStatus(); toast("电脑无法走棋") }
    }

    /** 胜负判定：无合法着法即终局（将被将死 / 困毙）；连续60回合无吃子判和。一盘一结：有结果 → 记战绩并提示保存棋谱 */
    private fun checkGameOver(): Boolean {
        val cur = board.currentPlayer
        // ★ 和棋：双方连续 60 回合（=120 半回合）互无吃子，判和（不吃子地求和，长将躲闪不算和）
        if (drawByNoCapture()) {
            gameOver = true
            currentResult = "和"
            recordResult("和")
            clearSavedState()
            updateStatus()
            showResultDialog("双方", "和", "${DRAW_NO_CAPTURE_PLIES / 2}回合无吃子，判和")
            return true
        }
        val noLegal = board.getLegalMoves(cur).isEmpty()
        if (!noLegal) return false
        gameOver = true
        val inCheck = board.isInCheck(cur)
        val humanTrapped = (cur == humanColor)
        val result = when {
            humanTrapped -> "负"
            else -> "胜"
        }
        currentResult = result
        val reason = if (inCheck) "被将死" else "困毙"
        val winnerName = if (cur == PieceColor.RED) "黑方" else "红方"
        recordResult(result)          // 记入战绩
        clearSavedState()             // 本盘已结束，不再续盘
        updateStatus()
        showResultDialog(winnerName, result, reason)
        return true
    }

    /** 连续60回合(120半回合)互无吃子 → 判和。吃子就清零重新计；长将/被逼躲将是合法着，不因重复判和，只看吃子。 */
    private fun drawByNoCapture(): Boolean {
        if (moves.size < 2) return false
        var pliesSinceCapture = 0
        val b = Board()
        for (u in moves) {
            val m = Move.fromUci(u, b) ?: continue
            val isCapture = m.captured != null
            b.makeMoveUnchecked(m)
            pliesSinceCapture = if (isCapture) 0 else pliesSinceCapture + 1
            if (pliesSinceCapture >= DRAW_NO_CAPTURE_PLIES) return true
        }
        return false
    }

    // ═══ 悔棋：与主界面"快退"同道理，悔掉电脑应着 + 你上一手 ═══
    private fun onUndo() {
        if (gameOver) return
        if (aiThinking) {
            engine?.stopSearch()
            aiThinking = false
            searchGen++   // 作废在途搜索回调
        }
        if (moves.isEmpty()) { toast("无棋可悔"); return }
        // 现在轮到谁：
        //  - 电脑走棋中/轮到电脑 → 最后一手是你的 → 只悔你这一手
        //  - 轮到你（电脑已应）  → 悔掉电脑应手 + 你上一手
        if (board.currentPlayer != humanColor) {
            moves.removeAt(moves.size - 1)
        } else {
            if (moves.isNotEmpty()) moves.removeAt(moves.size - 1)   // 电脑应手
            if (moves.isNotEmpty()) moves.removeAt(moves.size - 1)   // 你上一手
        }
        hasVariation = true        // ★ 悔过棋 → 续下后存的是"变招"
        replayBoard(restartAiIfNeeded = true)
    }

    /** 按走法序列重建棋盘（悔棋 / 续盘共用） */
    private fun replayBoard(restartAiIfNeeded: Boolean) {
        gameOver = false
        aiThinking = false
        searchGen++
        board = Board()
        boardSnapshots.clear()
        boardSnapshots.add(board.toFen())
        for (uci in moves) {
            val m = Move.fromUci(uci, board) ?: continue
            board.makeMoveUnchecked(m)
            boardSnapshots.add(board.toFen())
        }
        warnedViolator = null
        humanIllegalStreak = 0
        aiIllegalStreak = 0
        chessBoard.setBoard(board)
        chessBoard.setFlipped(humanColor == PieceColor.BLACK)
        chessBoard.clearSelection()
        val last = moves.lastOrNull()?.let { Move.fromUci(it, board) }
        chessBoard.setLastMove(last)
        if (!checkGameOver()) {
            updateStatus()
            if (restartAiIfNeeded && board.currentPlayer != humanColor) {
                triggerAiMove()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  引擎懒启动（独立进程内的独立 SandboxEngine）
    // ══════════════════════════════════════════════════════
    private fun resetEngineIfNeeded(newKey: String) {
        if (engine != null && currentEngineKey != newKey) {
            try { engine?.shutdown() } catch (_: Exception) {}
            engine = null
            engineReady = false
            engineStarting = false
        }
        currentEngineKey = newKey
    }

    private fun ensureEngineAsync() {
        if (engineStarting || engineReady) return
        engineStarting = true
        val key = currentEngineKey ?: engineLevels[levelIndex].engine
        val t = Thread {
            try {
                // ★ BOSS 档：尽早并行装载 CBL 棋谱全集（与引擎启动重叠，不占用首次应着时间）
                if (isBossLevel()) ensureBossBookAsync()
                // ★ 顶段（雪鱼）接 XueyuEngine(Fairy-Stockfish NNUE UCI 子进程)；
                //   CCS 档接 CcstockfishEngine(CCStockfish UCI)；其余四套引擎走 SandboxEngine(UCCI/UCI 内存管道桥)
                val useXueyu = (key == XUEYU)
                val useCcs = (key == CCFISH)
                val raw: Any = when {
                    useXueyu -> XueyuEngine(this)
                    useCcs -> CcstockfishEngine(this)
                    else -> SandboxEngine(this, key, SandboxEngine.isUcci(key))
                }
                val e = adapt(raw)
                when (raw) {
                    is SandboxEngine -> { raw.hashSize = HASH_MB; raw.threadCount = THREADS }
                    is XueyuEngine -> { raw.hashSize = 64; raw.threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4) }
                    is CcstockfishEngine -> { raw.hashSize = 64; raw.threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4) }
                    else -> {}
                }
                val locErr = when (raw) {
                    is SandboxEngine -> raw.locateEngine()
                    is XueyuEngine -> raw.locateEngine()
                    is CcstockfishEngine -> raw.locateEngine()
                    else -> "未知引擎"
                }
                if (locErr != null) { engineStarting = false; toastOnUi(locErr); return@Thread }
                val nnueErr = when (raw) {
                    is SandboxEngine -> raw.copyCurrentNNue()
                    is XueyuEngine -> raw.copyCurrentNNue()
                    is CcstockfishEngine -> raw.copyCurrentNNue()
                    else -> null
                }
                if (nnueErr != null) { engineStarting = false; toastOnUi(nnueErr); return@Thread }
                val startErr = when (raw) {
                    is SandboxEngine -> raw.startEngine()
                    is XueyuEngine -> raw.startEngine()
                    is CcstockfishEngine -> raw.startEngine()
                    else -> "未知引擎"
                }
                if (startErr != null) { engineStarting = false; toastOnUi(startErr); return@Thread }
                val initErr = when (raw) {
                    is SandboxEngine -> raw.liteInit()
                    is XueyuEngine -> raw.liteInit()
                    is CcstockfishEngine -> raw.liteInit()
                    else -> null
                }
                if (initErr != null) { engineStarting = false; toastOnUi(initErr); return@Thread }
                engine = e
                engineReady = true
                engineStarting = false
                runOnUiThread {
                    if (pendingAiMove) {
                        pendingAiMove = false
                        triggerAiMove()
                    }
                }
            } catch (t2: Throwable) {
                engineStarting = false
                Log.e(TAG, "挑战引擎启动失败", t2)
                toastOnUi("引擎启动被中止：${t2.message}")
            }
        }
        t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _th, e ->
            engineStarting = false
            Log.e(TAG, "挑战引擎线程异常", e!!)
            toastOnUi("引擎线程异常：${e.message}")
        }
        t.start()
    }

    // ══════════════════════════════════════════════════════
    //  雪鱼→皮卡鱼 交接（残局段）
    // ══════════════════════════════════════════════════════

    /** 是否已到皮卡鱼段（雪鱼档 或 CCS接龙档 已走满 20 回合=40 半回合）。
     *  续盘时 moves 从存档完整恢复，因此"当前归谁走"由 moves.size 即可精确判定，
     *  无需额外存档字段——交接点在续盘后依旧正确。 */
    private fun isPikaPhase(): Boolean = handoffPikaDepth(engineLevels[levelIndex]) != null

    /** 皮卡鱼应走层数：雪鱼档、或 CCS 接龙档且已满 20 回合才返回；否则 null。
     *  雪鱼层 14/16/18 → 皮卡鱼 10/12/14（层数-4）。 */
    private fun handoffPikaDepth(level: EngineLevel): Int? {
        if (pikaFailed) return null
        // 雪鱼全部档位后程交接；CCS 只有「接龙」档交接，纯自走档(16/18全程)不交接
        val relay = (level.engine == XUEYU) || level.relay
        if (!relay) return null
        if (moves.size < XUEYU_HANDOFF_PLIES) return null
        return (level.depth - 4).coerceIn(1, 30)
    }

    /** 开局归一化：第 8~25 关的前 5 回合内一律返回皮卡鱼 14 层，否则 null。
     *  仅用于"谁替本关引擎落子"，不参与任何界面文案，客户端无从察觉。 */
    private fun openingPikaDepth(): Int? {
        if (pikaFailed) return null
        if (levelIndex < OPENING_PIKA_FROM_INDEX || levelIndex > OPENING_PIKA_TO_INDEX) return null
        if (moves.size >= OPENING_PIKA_PLIES) return null
        return OPENING_PIKA_DEPTH
    }

    /** 当前是否由皮卡鱼代走（开局归一化段 或 雪鱼后程交接段）。 */
    private fun pikaTakesOverNow(): Boolean = openingPikaDepth() != null || isPikaPhase()

    /** 懒启动皮卡鱼交接引擎（独立子进程，与雪鱼并存，互不干扰）。 */
    private fun ensurePikaAsync() {
        if (pikaStarting || pikaReady || pikaFailed) return
        pikaStarting = true
        val t = Thread {
            try {
                val raw = JniEngine(this)
                val e = adapt(raw)
                raw.hashSize = 64
                raw.threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val locErr = raw.locateEngine()
                if (locErr != null) { failPika("对手引擎定位失败，已改用本关对手"); return@Thread }
                val nnueErr = raw.copyCurrentNNue()
                if (nnueErr != null) { failPika("对手引擎权重缺失，已改用本关对手"); return@Thread }
                val startErr = raw.startEngine()
                if (startErr != null) { failPika("对手引擎启动失败，已改用本关对手"); return@Thread }
                val initErr = raw.liteInit()
                if (initErr != null) { failPika("对手引擎初始化失败，已改用本关对手"); return@Thread }
                pikaEngine = e
                pikaReady = true
                pikaStarting = false
                runOnUiThread {
                    if (pendingAiMove && pikaTakesOverNow()) {
                        pendingAiMove = false
                        triggerAiMove()
                    }
                }
            } catch (t2: Throwable) {
                Log.e(TAG, "皮卡鱼交接引擎启动失败", t2)
                failPika("对手引擎异常，已改用本关对手")
            }
        }
        t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _th, e ->
            Log.e(TAG, "皮卡鱼交接引擎线程异常", e!!)
            failPika("对手引擎异常，已改用本关对手")
        }
        t.start()
    }

    /** 皮卡鱼不可用时：标记失败并让待走的 AI 立即回退到本关引擎，绝不让棋局卡住。 */
    private fun failPika(msg: String) {
        pikaStarting = false
        pikaFailed = true
        toastOnUi(msg)
        runOnUiThread {
            if (pendingAiMove) { pendingAiMove = false; triggerAiMove() }
        }
    }

    private fun toastOnUi(msg: String) {
        runOnUiThread { toast(msg) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ══════════════════════════════════════════════════════
    //  状态 / 战绩 / 存档 / 续盘 / 导出
    // ══════════════════════════════════════════════════════
    private fun colorName(): String = if (humanColor == PieceColor.RED) "红" else "黑"

    private fun updateStatus() {
        val lv = engineLevels[levelIndex]
        val rank = lv.rank
        val ms = lv.ms
        val human = colorName()
        val turn = when {
            gameOver -> "本局已结束"
            aiThinking -> "电脑思考中…"
            board.currentPlayer == humanColor -> "该你走棋"
            else -> "电脑走棋"
        }
        // ★ 顶栏/抬头已移除：挑战对象等级统一在这条状态行里给出（谁 vs 谁一眼即明）。
        //   雪鱼等层数制引擎显示"层N"；其余仍显示毫秒/步。雪鱼段/皮卡鱼段标注交接阶段。
        val strength = if (lv.depth > 0) "层${lv.depth}" else "${ms}ms"
        val seg = when {
            isPikaPhase() -> "｜皮卡鱼段${handoffPikaDepth(lv)}层"
            lv.engine == XUEYU -> "｜雪鱼段"
            else -> ""
        }
        // ★ 菜单栏不再显示"对局中"（下棋本就在对局中，三个字纯占位）：
        //   仅在终局后附上本局结果（胜/负/和），常态省去一段，行宽更稳、更不易换行。
        val resultSeg = if (currentResult == "对局中") "" else "｜$currentResult"
        statusText.text = "$rank($strength)｜我持$human｜第${moves.size}手$seg$resultSeg｜$turn"
        // ★ ECCO/开局同步行：每走一手（含电脑自动应）都跟着更新——走到哪个开局，这里就报到哪个开局。
        //   "起手位"只在一步未走时出现；已走子但 ECCO 判不出细分时，报"其他开局"，绝不误报成起手位。
        if (::eccoText.isInitialized) {
            val opening = runCatching { currentOpeningName() }.getOrNull()
            eccoText.text = when {
                moves.isEmpty() -> "本局开局：起手位"
                opening.isNullOrBlank() -> "本局开局：其他开局（ECCO 未细分）"
                else -> "本局开局：$opening"
            }
        }
        // ★ 内容（状态行/开局行）高矮变了 → 立刻按"顶边恒定"重排面板：
        //   内容变高变矮只吃掉面板内部空间，顶边纹丝不动，绝不"往上窜"。
        if (::bottomPanel.isInitialized) applyChallengeMenuPosition()
    }

    /** ★ 由本局已走 UCI 序列重放，交给 ECCO 编号体系，得到“现在走到什么开局/变例”。 */
    private fun currentOpeningName(): String {
        val b = Board()
        b.reset()
        val mvs = ArrayList<Move>()
        for (u in moves) {
            val m = Move.fromUci(u, b) ?: break
            mvs.add(m)
            if (!b.makeMove(m)) break
        }
        if (mvs.isEmpty()) return ""
        val nr = com.pikafish.chess.qipu.EccoNamer.ofMoves(mvs) ?: return ""
        return if (nr.variation.isBlank()) "${nr.code} ${nr.opening}" else "${nr.code} ${nr.opening}（${nr.variation}）"
    }

    private fun recordResult(result: String) {
        try {
            val arr = JSONArray(recordPrefs.getString("records", "[]"))
            val o = org.json.JSONObject()
            o.put("time", System.currentTimeMillis())
            o.put("rank", engineLevels[levelIndex].rank)
            o.put("engine", engineLevels[levelIndex].engine)
            o.put("ms", engineLevels[levelIndex].ms)
            o.put("color", colorName())
            o.put("result", result)
            arr.put(o)
            while (arr.length() > MAX_RECORDS) arr.remove(0)
            recordPrefs.edit().putString("records", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    // ★ 终局弹窗：一盘一结。有输赢/和棋 → 问是否把棋谱一键存进宝库「挑战棋谱」文件夹
    private fun showResultDialog(winnerName: String, result: String, reason: String) {
        val human = if (humanColor == PieceColor.RED) "红方" else "黑方"
        val lv = engineLevels[levelIndex]
        val varTip = if (hasVariation) "\n（本局曾悔棋改走，记作变招）" else ""
        val strength = if (lv.depth > 0) "层${lv.depth}" else "${lv.ms}ms"
        AlertDialog.Builder(this, R.style.Theme_ChallengeDialogLight)
            .setTitle("本局结束")
            .setMessage("$winnerName ${if (result == "和") "判和" else "获胜"}（$reason）\n\n等级：${lv.rank}·${engineLabel(lv.engine)}($strength)\n你持$human，本局：$result$varTip\n\n是否把本局棋谱存进宝库「挑战棋谱」文件夹？")
            .setPositiveButton("保存棋谱") { _, _ -> doSaveToFile() }
            .setNeutralButton("再开一盘", { _, _ -> restartGame() })
            .setNegativeButton("返回", { _, _ -> finish() })
            .setCancelable(false)
            .show()
    }

    // ═══════════════ 把本局一键存进宝库「挑战棋谱」专属文件夹（终局自动引导）═══
    //   ★ 不再弹系统文件选择器：直接落盘到 HHXQ/挑战棋谱/ —— 打开「棋谱→宝库」即见，
    //     省得用户自定义存到别处、回头在宝库里怎么翻都找不到。
    private fun doSaveToFile() {
        if (moves.isEmpty()) { toast("本局没有走棋，无谱可存"); return }
        try {
            val name = buildSaveFileName()
            val saved = QipuLibrary.persistChallengeRecord(
                name, buildCurrentGameText().toByteArray(Charsets.UTF_8)
            )
            if (saved != null) {
                toast("已存进宝库·挑战棋谱：$saved\n（打开「棋谱 → 宝库」即可看到并逐步回放）")
            } else {
                toast("保存失败：无法写入「挑战棋谱」文件夹")
            }
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }

    /** 默认文件名：黄昏挑战_年月日时分_vs对手_先手特征.txt。例：黄昏挑战_20260930_1949_vs国手_红先.txt */
    private fun buildSaveFileName(): String {
        val lv = engineLevels[levelIndex]
        val df = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val first = if (colorName() == "黑") "黑先" else "红先"
        return "黄昏挑战_${df.format(Date())}_vs${lv.rank}_${first}.txt"
    }

    private fun showRecordsDialog() {
        val arr = try { JSONArray(recordPrefs.getString("records", "[]")) } catch (_: Exception) { JSONArray() }
        if (arr.length() == 0) {
            toast("还没有战绩记录")
            return
        }
        val sb = StringBuilder()
        val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val time = df.format(Date(o.optLong("time", 0L)))
            val rank = o.optString("rank", "?")
            val engine = o.optString("engine", "?")
            val ms = o.optLong("ms", 0L)
            val color = o.optString("color", "?")
            val result = o.optString("result", "?")
            val eh = if (ms > 0) "$rank(${ms}ms)" else rank
            sb.append("$time ｜ $eh · 我持$color · $result\n")
        }
        AlertDialog.Builder(this, R.style.Theme_ChallengeDialogLight)
            .setTitle("战绩（近${arr.length()}局）")
            .setMessage(sb.toString().trim())
            .setPositiveButton("知道了", null)
            .show()
    }

    // ══════════════════════════════════════════════════════
    //  生成棋谱正文（存进宝库「挑战棋谱」后，点开即被 importPgn 识别、逐步回放）
    // ══════════════════════════════════════════════════════
    /**
     * ★ 标准 PGN 抬头 + 中文记谱主线 —— 与 ChessGame 的「中文记谱」往返格式同源，
     *   所以宝库里点开能被 自动识别/importPgn 认出来并从头逐步播放；
     *   抬头信息（对手/我持子/先后手/结果/变招）存为 PGN 标签，宝库「信息」里也能看到来龙去脉。
     */
    private fun buildCurrentGameText(): String {
        val lv = engineLevels[levelIndex]
        val df = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
        val strength = if (lv.depth > 0) "层${lv.depth}" else "${lv.ms}ms/步"
        val sb = StringBuilder()
        // ── PGN 抬头（必须是 [Tag "value"] 形式，否则 importPgn 不认）──
        sb.append("[Event \"黄昏象棋·挑战对局\"]\n")
        sb.append("[Date \"").append(df.format(Date())).append("\"]\n")
        sb.append("[Red \"").append(if (humanColor == PieceColor.RED) "玩家" else lv.rank).append("\"]\n")
        sb.append("[Black \"").append(if (humanColor == PieceColor.RED) lv.rank else "玩家").append("\"]\n")
        sb.append("[Result \"").append(pgnResult()).append("\"]\n")
        sb.append("[Opponent \"").append(lv.rank).append("·").append(engineLabel(lv.engine))
            .append("（").append(strength).append("）\"]\n")
        sb.append("[PlayerSide \"").append(colorName()).append("\"]\n")
        sb.append("[Remark \"").append(currentResult)
            .append(if (hasVariation) "·曾悔棋改走" else "").append("\"]\n")
        sb.append("\n")
        // ── 主线中文记谱（逐手转记谱；红用一~九、黑用 1~9，每回合一行）──
        val b = Board()
        val body = StringBuilder()
        moves.forEachIndexed { i, uci ->
            val notation = try { CommentaryEngine.uciToChineseNotation(uci, b) } catch (_: Exception) { "" }
            if (i % 2 == 0) body.append("${i / 2 + 1}. ")
            body.append(notation).append("  ")
            if (i % 2 == 1) body.append('\n')
            val m = Move.fromUci(uci, b) ?: return@forEachIndexed
            b.makeMoveUnchecked(m)
        }
        sb.append(body.toString().trimEnd()).append('\n')
        return sb.toString()
    }

    /** 我方视角结果 → PGN 结果标记（红方胜=1-0、黑方胜=0-1、和=1/2-1/2）。 */
    private fun pgnResult(): String = when (currentResult) {
        "胜" -> if (humanColor == PieceColor.RED) "1-0" else "0-1"
        "负" -> if (humanColor == PieceColor.RED) "0-1" else "1-0"
        "和" -> "1/2-1/2"
        else -> "*"
    }

    /** 强退 / 正规返回统一存档（终局则清档）。
     * ★ 续盘持久化：只要本轮没走新棋（moves 为空），即使此前已有挂起的续盘，也必须原样保留——
     *   用户"进入挑战→看到续盘弹窗→以任意方式返回/关屏"都一律不得清档。续盘只在两种情况清除：
     *   ① 玩家主动点「重新开一局/再开一盘」——其内部会 clearSavedState() 作废旧局；
     *   ② 本盘真正分出胜负（终局处理里 clearSavedState()）。
     *   其余任何返回路径：保留续盘，下次进档仍弹「续盘?」。 */
    private fun saveState() {
        try {
            if (gameOver) { clearSavedState(); return }
            if (moves.isEmpty()) {
                // 本轮没有实际走棋：若此前已有未下完的续盘则原样保留（不清档），没有则维持现状
                return
            }
            statePrefs.edit()
                .putBoolean("active", true)
                .putString("fen", board.toFen())
                .putString("moves", moves.joinToString(" "))
                .putInt("level", levelIndex)
                .putString("humanColor", if (humanColor == PieceColor.RED) "red" else "black")
                .putLong("ts", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }

    private fun clearSavedState() {
        try { statePrefs.edit().clear().apply() } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        saveState()
    }

    override fun onDestroy() {
        try { engine?.shutdown() } catch (_: Exception) {}
        try { pikaEngine?.shutdown() } catch (_: Exception) {}
        engine = null
        pikaEngine = null
        // ★ 释放短音效
        try { soundManager?.release() } catch (_: Exception) {}
        soundManager = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}