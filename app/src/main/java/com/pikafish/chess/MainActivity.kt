package com.pikafish.chess

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.viewModels
import androidx.core.net.toUri
import com.yk.xiangqi.link.LinkAccessibilityService
import com.yk.xiangqi.link.LinkForegroundService
import com.yk.xiangqi.ui.GameViewModel
import com.pikafish.chess.engine.LocalBookManager
import com.pikafish.chess.engine.SandboxOracle
import com.pikafish.chess.engine.UserEngineStore
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.Move
import com.pikafish.chess.ui.CurveView
import com.pikafish.chess.ui.ReviewAnalyzer
import com.pikafish.chess.ui.ReviewArchive
import com.pikafish.chess.ui.ReviewBundle
import com.pikafish.chess.ui.ReviewSegment
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position
import com.pikafish.chess.model.SetupValidator
import com.pikafish.chess.qipu.EccoNamer
import com.pikafish.chess.qipu.QipuBrowser
import com.pikafish.chess.qipu.QipuGame
import com.pikafish.chess.ui.MiniGameBoardView
import com.pikafish.chess.ui.ChessBoardView
import com.pikafish.chess.ui.AnalysisSandboxActivity
import org.json.JSONObject
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    /** ★ 八风不动：系统字体大小/字体风格怎么改都不影响本界面 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "pikafish_chess_prefs"
        // 游戏状态（完整棋局历史）
        private const val KEY_FULL_STATE_JSON = "full_state_json"
        private const val KEY_SAVED_FEN = "saved_fen"
        private const val KEY_SAVED_STEP = "saved_step"
        private const val KEY_RED_HUMAN = "red_human"
        private const val KEY_BLACK_HUMAN = "black_human"
        // 引擎参数
        private const val KEY_ENGINE_TIME = "engine_time_ms"
        private const val KEY_SEARCH_MODE = "search_mode"
        private const val KEY_ENGINE_DEPTH = "engine_depth"
        private const val KEY_SHOW_THINKING = "show_thinking"
        private const val KEY_FLOAT_OVERLAY = "float_overlay"   // ★ 分析浮透开关
        private const val KEY_ALWAYS_THINKING = "always_thinking"
        private const val KEY_ENABLE_EXPLANATION = "enable_explanation"
        private const val KEY_EXPLANATION_DEPTH = "explanation_depth"
        private const val KEY_COMMENTARY_STYLE = "commentary_style"
        private const val KEY_QIPU_COMMENTARY = "qipu_commentary_enabled"
        private const val KEY_HASH = "hash_size"
        private const val KEY_THREADS = "thread_count"
        // 视觉参数
        private const val KEY_BOARD_SCALE = "board_scale"
        private const val KEY_PIECE_SCALE = "piece_scale"
        private const val KEY_ABS_CELL_SIZE = "abs_cell_size"      // 绝对格子尺寸（像素）
        private const val KEY_ABS_PIECE_RADIUS = "abs_piece_radius"  // 绝对棋子半径（像素）
        // ★★★ 棋盘统一调度：主界面真实渲染格子像素（三个沙盒以此为准）
        private const val KEY_RENDER_CELL = "render_cell_px"
        // ★★★ 大而美：棋盘棋子最大化且不爆屏（边界锁定，显/隐切换大小不变，重启后保持）
        private const val KEY_DAMEI_LOCKED = "damei_locked"        // 大而美锁定开关
        private const val KEY_DAMEI_BOARD_H = "damei_board_h"      // 大而美锁定的棋盘区高度(px)
        private const val KEY_DAMEI_MENU_H = "damei_menu_h"        // 大而美激活时缓存的主菜单高度(px)：显/隐切换不抖帧
        private const val KEY_LINE_COLOR_IDX = "line_color_idx"
        private const val KEY_LINE_THICK_IDX = "line_thick_idx"
        private const val KEY_BG_MODE = "bg_mode"
        private const val KEY_PIECE_SKIN = "piece_skin"
        private const val KEY_BOARD_Y_OFFSET = "board_y_offset_px"  // ★★「棋盘坐标」棋盘整体纵向偏移(px,正=下移/负=上移)
        private const val KEY_MENU_Y_PX = "menu_y_px"              // ★★「组件坐标」主菜单底边距屏幕底(px,默认310)
        private const val KEY_FIRST_RUN = "first_run_setup_done"  // ★ 首次安装布置（大而美棋盘+3D立体皮肤）是否已完成
        private const val KEY_OVERLAY_MODE = "overlay_mode"
        private const val KEY_MENU_BG_IDX = "menu_bg_idx"
        private const val KEY_MENU_BTN_IDX = "menu_btn_idx"
        // ★ 字体/棋桌视觉新偏好
        private const val KEY_COMMENT_FONT_SP = "comment_font_sp"    // 解说字号(sp)
        private const val KEY_TAP_FONT_SCALE = "tap_font_scale"       // 点动/盘内讲解字号倍率
        private const val KEY_TABLE_STYLE = "table_style"             // 棋桌风格(ChessBoardView.TableStyle)索引
        /** ★ 用户是否【亲手】挑过棋桌风格：挑过就永远依他存的那一档，没挑过一律静默给暖白枫 */
        private const val KEY_TABLE_STYLE_PICKED = "table_style_picked_by_user"
        /** ★ 默认棋桌风格＝暖白枫（列表顺序不动，只换"默认"这一档：干净敞亮，第一印象不吓人） */
        private val DEFAULT_TABLE_STYLE = com.pikafish.chess.ui.ChessBoardView.TableStyle.MAPLE.ordinal
        /** ★ 默认棋盘百分比＝88%（稳妥上限区 88%~89%，再高重启后边线棋子会溢出屏幕） */
        private const val DEFAULT_BOARD_SCALE = 0.88f
        /** ★ 用户是否【亲手】拖过「棋盘」滑杆：拖过就永远依他存的那一档，没拖过一律静默落在 88% */
        private const val KEY_BOARD_SCALE_PICKED = "board_scale_picked_by_user"
        private const val KEY_LABEL_COLOR = "label_color"             // ★ 刻度(棋盘坐标)文字颜色
        private const val KEY_WHATIF_MODE = "whatif_mode"
        private const val KEY_SHOW_TIME = "show_time_on_board"
        private const val KEY_CLOUD_BOOK = "cloud_book_enabled"  // ★ 云库开关
        private const val KEY_LOCAL_BOOK = "local_book_enabled"   // ★ 本地外置开局库开关
        private const val KEY_LOCAL_BOOK_PATHS = "local_book_paths" // ★ 已挂接外置库文件路径列表(逗号分隔)
        private const val KEY_HINT_HVH_DISMISSED = "hint_hvh_dismissed"  // 人脑对人脑提示是否已关闭
        private const val KEY_VAULT_BOOK_FILE = "vault_book_file"        // 棋库当前阅读的书文件
        private const val KEY_VAULT_GAME_INDEX = "vault_game_index"      // 棋库当前阅读的局索引
        // 复盘最小化小浮条的记忆位置（0..1 相对比例，重启后按用户上次停的位置常住）
        private const val KEY_REVIEW_BAR_X = "review_bar_fx"
        private const val KEY_REVIEW_BAR_Y = "review_bar_fy"
        // ★ PK 透明挡屏上的「文案框」停靠位置（相对整屏比例 0..1，可整体上下拖、位置记忆常住）
        private const val KEY_PK_SHIELD_X = "pk_shield_fx"
        private const val KEY_PK_SHIELD_Y = "pk_shield_fy"
        // 棋谱列表（导入的棋谱记录）
        private const val KEY_GAME_RECORDS = "game_records_json"
        /** ★ 外置读物「♟ 正选·摆子识别」：PDF 阅读器框住棋盘截图后，把图片地址经 Intent 送来直接识别 */
        const val EXTRA_RECOGNIZE_IMAGE = "pikafish_recognize_image"
    }

    private lateinit var chessGame: ChessGame
    private lateinit var chessBoard: ChessBoardView
    private lateinit var frameBoardOuter: android.widget.FrameLayout  // 棋盘所在的蓝色外罩容器
    // ★ 复盘最小化（收起而非关闭）：跳去试走时只收起弹窗、结果数据留在内存，
    //   屏幕边缘浮出小条，点它原样展开，保留滚动位置与选中条目
    private var reviewMinimized = false                                             // 当前是否处于最小化
    private var reviewLivingDlg: androidx.appcompat.app.AlertDialog? = null         // 活着的复盘弹窗（收起时保留同一实例）
    private var reviewLiveBundle: com.pikafish.chess.ui.ReviewBundle? = null         // 复盘结果缓存（不重算引擎）
    private var reviewLivePage: Int = 0                                             // 当前页（批处理用）
    private var reviewLiveRound: Int = 0                                            // 收起时跳转到的回合（浮条标题用）
    private var reviewMinBar: android.widget.Button? = null                          // 屏幕边缘小浮条
    private var reviewBarHost: android.widget.FrameLayout? = null                    // 浮条宿主：整屏容器（可在主屏任意角落移动）
    // ★★ 复盘字号缩放（用户钦定）：整页文字统一放大/缩小；棋盘与曲线图永远不参与。
    private var reviewFontScale = 1.0f
    private val reviewBaseTextSizes = java.util.WeakHashMap<TextView, Float>()        // 各 TextView 原始字号（防重复缩放累积）
    private val REVIEW_NOSCALE_TAG = "review_no_font_scale"                           // 免缩放标记：打了它的子树整棵跳过
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusEmoji: TextView
    private lateinit var tvEngineInfo: TextView
    private lateinit var tvThinking: TextView
    private var lastThinkingInfo: String = ""  // 保存原始HTML字符串，用于内容变化检测
    // ★ 思考信息 UI 通道的限频合并态：只保留最新一条，100ms 汇合一次
    private var lastThinkingUiAtMs: Long = 0L
    private var thinkingUiPending: Boolean = false
    private var thinkingUiLatest: String? = null
    private val thinkingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // ★ 左侧「书库」侧滑栏相关
    private lateinit var btnBookMenu: TextView
    private var bookDrawerContainer: android.widget.FrameLayout? = null   // 全局遮罩层
    private var bookDrawerPanel: LinearLayout? = null                     // 左滑面板
    private var bookDrawerOpen = false
    private var bookPickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    // ★ 上传棋谱到宝库（「我的上传」分类）的多选启动器
    private var qipuUploadPickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    // ★ 正在后台装载的外置库名集合（线程安全）。渲染时显示"加载中…"，
    //   装完由后台线程回主线程移除。用户随时可关面板去下棋，装载不阻塞主界面。
    private val loadingBooks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private lateinit var tvExplanation: TextView
    private lateinit var scrollViewExplanation: android.widget.ScrollView
    private lateinit var layoutExplanationList: LinearLayout
    private lateinit var tvStepInfo: TextView
    private lateinit var btnNewGame: Button
    private lateinit var btnGoBack: Button
    private lateinit var btnGoForward: Button
    private lateinit var btnToggleMenu: Button  // 「隐/显」圆圈：收拢/展开整块主菜单
    private var menuCollapsedByToggle = false    // 主菜单是否被「隐」收拢
    private lateinit var btnGoStart: Button
    private lateinit var btnGoEnd: Button
    private lateinit var btnGuessMove: Button
    private lateinit var btnPlaySpeed: Button  // 播放倍速
    private lateinit var btnQipuCommentary: Button  // 棋谱旁白开关
    private lateinit var btnQipuCommentaryList: Button  // 旁白表（一坨一坨列出本局旁白）
    private lateinit var btnBookMemorize: Button  // 背谱入口
    // 背谱引擎（懒加载：背谱按钮首次被用时才创建，此时 chessGame.localBook 已就绪）
    private val bookMemorizer by lazy { com.pikafish.chess.engine.BookMemorizer(chessGame.localBook) }
    // 背谱期间把人脑/引擎状态临时改为人脑并把原值存下，退出背谱后还原
    private var bookPrevRedHuman = true
    private var bookPrevBlackHuman = false
    // 背谱变招气泡（透明居棋盘正中）
    private lateinit var bubbleBookBranch: android.widget.TextView
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bubbleDismiss: Runnable? = null
    private lateinit var btnAuthorIntent: Button  // 更多 ▸ 作者寄语（点播/再点停）
    private lateinit var btnJoinGroup: Button  // 更多 ▸ 进群反馈（跳转QQ官方群）
    private lateinit var btnOtherWorks: Button  // 更多 ▸ 其他作品（气泡提示后跳转下载）
    private lateinit var btnBgm: Button  // 更多 ▸ 背景音（选背景音乐 + 调音效音量）
    private lateinit var btnFreshLife: Button  // 更多 ▸ 清新人生（《清新人生手册》全屏阅读器）
    private lateinit var btnExternalReader: Button  // 更多 ▸ 外置读物（PDF 书架：导入/阅读/标记/进度）
    // 作者寄语真人录音播放器（复合键：点一下播放，再点一下停止，可当背景音边下棋边听）
    private var authorMsgPlayer: android.media.MediaPlayer? = null
    private var authorMsgPlaying = false
    private lateinit var btnFlip: Button
    private lateinit var btnRedSide: Button
    private lateinit var btnBlackSide: Button
    // ★ 对弈页面①（主棋盘）
    private lateinit var layoutGamePage1: LinearLayout
    // ★ 对弈子菜单固定行（新对局/红方/黑方/人话/分析）：钉在Tab栏正下方，永不随解说滚动
    private lateinit var layoutGameSub: LinearLayout
    // ★ 对弈子菜单第2行（翻转/信息/挑战/摆子/解惑）：与第1行同显同隐
    private lateinit var layoutGameSubRow2: LinearLayout
    private var gamePageNum = 1
    private lateinit var btnMode: Button
    private lateinit var btnCloudBook: Button  // ★ 云库开关按钮
    private lateinit var etValue: EditText
    private lateinit var btnMinus: Button
    private lateinit var btnPlus: Button
    private lateinit var btnDetail: Button
    private lateinit var btnAlwaysThink: Button   // ★ 后台思考开关（人脑思考时电脑先预想，命中即秒走）
    private lateinit var btnAnalyze: Button
    private lateinit var btnGameInfo: Button   // ★ 恒定菜单栏「信息」：看本局来龙去脉
    private lateinit var btnChallenge: Button  // ★ 挑战：选等级与执子颜色，与引擎对战
    private lateinit var btnExportPgn: Button
    private lateinit var btnImportPgn: Button
    private lateinit var btnReview: Button   // ★ 复盘评估：夹在 导出 与 导入 之间，逐回合评分曲线+漏着建议
    private lateinit var btnPracticeOpening: Button  // ★ 练开局：仿挑战模式，限定回合数与执子颜色
    private lateinit var layoutPracticeBar: android.widget.LinearLayout
    private lateinit var tvPracticeStatus: TextView
    private lateinit var btnPracticeUndo: Button
    private lateinit var btnPracticeReview: Button
    private lateinit var btnPracticeExit: Button
    private lateinit var btnExportFen: Button
    private lateinit var btnExportTxt: Button
    private lateinit var btnImportFen: Button
    private lateinit var btnImportTxt: Button
    private lateinit var btnGameRecords: Button
    private lateinit var btnCommentaryStyle: Button
    private lateinit var btnBgSwitch: Button
    private lateinit var btnPieceSkin: Button
    private lateinit var etHash: EditText
    private lateinit var etThreads: EditText
    private lateinit var btnApplySettings: Button
    private lateinit var btnTimeToggle: Button
    private lateinit var btnEngineRecover: Button
    private lateinit var btnSetupMode: Button
    private lateinit var btnSetupReturn: Button
    private lateinit var layoutSetupPanel: LinearLayout
    private lateinit var layoutNormalControls: LinearLayout
    private lateinit var scrollTabContent: android.widget.ScrollView

    // 横排Tab按钮
    private lateinit var btnTabSettings: Button
    private lateinit var btnTabGame: Button
    private lateinit var btnTabNotation: Button
    private lateinit var layoutParamsContent: LinearLayout
    private lateinit var layoutGameContent: LinearLayout
    private lateinit var layoutNotationContent: LinearLayout
    private lateinit var layoutVisualContent: LinearLayout
    private lateinit var layoutVisualContent2: LinearLayout   // ★★ 视觉②：坐标微调子界面（点「视觉」主菜单第二次切入）
    /** 冷启动为真 → 默认停在「更多」页，好让欢迎/作者寄语内容一打开就可见。 */
    private var coldStartFresh = false
    private lateinit var layoutMoreContent: LinearLayout

    // ★ 棋谱②入口（宝库 / 阅读印象）
    private lateinit var btnQipuVault: Button
    private lateinit var btnQipuImpression: Button
    private lateinit var btnQipuPrev: Button
    private lateinit var btnQipuNext: Button
    private lateinit var btnTagCurrent: Button   // ★ 底导航「▣标」：给当前正读这局打印象
    private lateinit var layoutQipuSub: LinearLayout
    private lateinit var layoutQipuImports: LinearLayout

    // ═══ ★ 标签页折叠状态：再点一次当前 Tab 收起其面板，只留 Tab 行 → 底部空间敞亮 ═══
    private var navActiveTab: Button? = null      // 当前高亮 Tab（可能其面板已被折叠）
    private var navPanelCollapsed = false         // 当前 Tab 的面板是否被折叠（收起）

    // ═══ ★「隐/显」圆圈：整块主菜单（Tab行+内容面板）收起/展开。
    //      收下后空间让给棋盘与思考区——眼不见为净，需要时一点弹开。════
    private var menuCollapsed = false             // 主菜单是否已被「隐」（收起）

    // ═══ ★「分析浮透」：分析内容半透明浮现在盘心（你侧），透棋读字，目不转睛。════
    private lateinit var btnFloatOverlay: Button       // 参数面板里的「分析浮透」开关
    private lateinit var tvFloatOverlay: TextView      // 盘心上的半透明浮透层
    private var floatOverlayEnabled = false            // 浮透开关状态

    // ═══ ★「观战」非无障碍连线的隐藏入口（芝麻开门）：顶栏「哈希xx MB」处连点三下 ════
    /** YK 连线的引擎驱动（逐字移植）：拥有 UciEngine，连线期间持续分析并把
     *  分值/深度/主变投影给浮层。Activity 在后台存活期间它一直有效。 */
    private val linkViewModel: GameViewModel by viewModels()
    private var spectatePendingStart = false           // 已跳转悬浮窗授权页，返回后自动继续
    private var engTripleTapCount = 0                  // 顶栏哈希区连点计数
    private var engTripleTapLastMs = 0L

    /** 悬浮窗授权返回后，若已授权则自动继续申请录屏 */
    private val spectatePermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (spectatePendingStart) {
            spectatePendingStart = false
            continueLink()
        }
    }

    /**
     * 无障碍设置页返回后不设门槛：开了就自动替你走子，没开也照样连线，
     * 只降级为「只识别分析、自己手动落子」，并提示一句。
     */
    private val spectateAccessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!LinkAccessibilityService.isEnabled(this))
            Toast.makeText(this, "未启用无障碍：本次只识别与分析，不会自动走子", Toast.LENGTH_LONG).show()
        continueLink()
    }

    /**
     * 录屏授权返回：拿到 MediaProjection 令牌后启动 YK 连线的前台服务，
     * 由它自己创建系统浮层（LinkOverlay），全屏自动定位棋盘、逐帧识别并归约走子。
     * 拒绝授权则连线不启动，主对弈照常。
     */
    private val spectateProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            try {
                LinkForegroundService.start(this, result.resultCode, data)
                Toast.makeText(this, "观战连线已启动", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "启动观战连线服务失败", e)
                Toast.makeText(this, "连线启动失败：" + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "未授予录屏权限，连线未启动", Toast.LENGTH_LONG).show()
        }
    }

    // ★ 解说弹窗：人话/现世报的解说不再就地显示在窗口区，改成像『分析浮透』一样透明的悬浮弹窗。
    //   原窗口区 tvCommentaryBanner 已停用（GONE），产出直接进这个悬浮弹窗。
    private lateinit var tvCommentaryFloat: TextView
    @Suppress("unused")
    private lateinit var tvCommentaryBanner: TextView

    // ★★ 统一「旁白/说明弹窗」：把原来一闪而过的 Toast 旁白与解说气泡合二为一——
    //    加宽变矮、置顶不挡棋，右上角✕手动关（不自动消失），长文自动分页（页码），
    //    点窗外任意处即关；关掉后本次内容没切换就不再自动弹起（切上/下一回合可再弹）。
    private var commentPopup: android.widget.LinearLayout? = null
    private lateinit var commentPopupTitle: TextView
    private lateinit var commentPopupContent: TextView
    private lateinit var commentPopupScroll: android.widget.ScrollView
    private lateinit var commentPopupPager: android.widget.LinearLayout
    private lateinit var commentPopupPageLabel: TextView
    private var commentPopupPages: List<String> = emptyList()
    private var commentPopupPageIndex = 0
    /** 用户手动关掉后置 true：同一步（未切换内容）不再自动弹起；切上/下一回合时复位。 */
    private var commentPopupDismissed = false
    /** 被手动关掉的那一版正文：正文没变就不再打扰（比方后台刷新重复触发）。 */
    private var commentPopupDismissedBody = ""
    /** 当前弹窗正文全文（分页前），供“关掉的是哪一版”比对。 */
    private var commentPopupBody = ""

    // ★ 浮透窗口拖拽状态（不依赖悬浮窗权限，限在棋盘范围内自由拖动）
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var dragStartTx = 0f
    private var dragStartTy = 0f
    private var dragBaseX = 0     // 每次按下时的 layout 位置（无 translation）
    private var dragBaseY = 0

    // 当前阅读的书和局索引（用于上一则/下一则导航）
    private var currentVaultBook: com.pikafish.chess.qipu.QipuBook? = null
    private var currentVaultGameIndex: Int = 0
    private var currentVaultGames: List<com.pikafish.chess.qipu.QipuGame> = emptyList()

    // ═══ ★ 分析模式（原“旁白”按钮改造而来）══════════════════════════════
    // 点开“分析”后强制红方人脑 + 黑方人脑，用对弈引擎持续分析当前局面（只分析不走棋）。
    @Volatile private var analysisActive = false     // 分析总开关
    @Volatile private var analysisBusy = false       // 正在跑一次引擎搜索
    @Volatile private var analysisGen = 0            // 代际：递增以作废过期搜索回调
    private var lastAnalyzedFen = ""                 // 最近一次已分析的局面
    // ★ 分析看门狗：单次引擎搜索带硬时限。引擎一旦"噎住"迟迟不回（翻步到后期常见），
    //   超时即在轮询里强制复位忙标志 + 作废旧代，立即以当前局面重跑，杜绝永久卡死/不帮想。
    private var analysisSearchToken = 0              // 每次新搜索自增，标识在途的那一次
    private var analysisSearchStartMs = 0L           // 本次搜索的启动时刻（看门狗判超时用）
    private val ANALYSIS_POLL_BUSY_MS = 400L         // 分析忙碌时的复扫间隔（更跟手）
    private val ANALYSIS_POLL_IDLE_MS = 600L         // 分析空闲时的复扫间隔（更跟手）
    private val ANALYSIS_TIMEOUT_MS = 90000L          // 单次分析硬时限：WASM 深度18 单局面可数秒~数十秒，需比原生宽裕
    // 写入分析结果时是否进入分析面板模式：为了不污染 onThinkingInfo 通道，分析直接用 tvThinking 独立渲染
    private val analysisPollRunnable = object : Runnable {
        override fun run() {
            if (!analysisActive) return
            val fen = try { chessGame.board.toFen() } catch (e: Exception) { "" }
            if (fen.isEmpty()) { analysisPoller.postDelayed(this, 600); return }
            // 无子 / 已结束：不分析
            if (chessGame.gameOver) {
                renderAnalysis("<b>【分析】</b> 本局已结束，停止分析。", selfContained = true)
                analysisPoller.postDelayed(this, 900)
                return
            }
            // 摆子模式：不打扰
            if (chessGame.isSetupMode) { analysisPoller.postDelayed(this, 900); return }
            val mc = try { chessGame.getMoveCount() } catch (e: Exception) { 0 }
            if (mc <= 0) {
                // 纯初始局面：无需分析，仅轻量轮询等待走棋
                renderAnalysis("<b>【分析】</b> 当前为初始局面，暂无需分析。走棋后自动展开。", selfContained = true)
                analysisPoller.postDelayed(this, 900)
                return
            }
            // ★★★ 分析"神同步"：若当前为 AI 回合，主心骨（走子搜索）即将/正在进行，
            //   分析模式绝不再另起炉灶跑独立搜索（否则两把火烧同一引擎、走子被抢线带跑）。
            //   屏上先给一句轻提示，具体数值由主心骨搜索经 onAnalysisFeed 实时直喂本面板。
            if (chessGame.isCurrentPlayerEngine()) {
                val curPlayer = try { chessGame.board.currentPlayer }
                    catch (e: Exception) { com.pikafish.chess.model.PieceColor.RED }
                val sideName = if (curPlayer == com.pikafish.chess.model.PieceColor.RED) "红方" else "黑方"
                if (fen != lastAnalyzedFen) {
                    lastAnalyzedFen = fen
                    analysisBusy = false
                    renderAnalysis(
                        "<b>【分析】</b> 轮到 $sideName（电脑）走棋<br/>" +
                            "<font color=\"#888888\">交由对弈搜索同步直喂本面板（绝不重复搜索）…</font>",
                        selfContained = false
                    )
                }
                analysisPoller.postDelayed(this, ANALYSIS_POLL_IDLE_MS)
                return
            }
            // ★ 正在跑一次搜索：先判"噎住超时"（看门狗），超时就强制复位重跑
            if (analysisBusy) {
                val stuck = analysisSearchToken > 0 &&
                        System.currentTimeMillis() - analysisSearchStartMs >= ANALYSIS_TIMEOUT_MS
                if (stuck) {
                    // ★★★ 看门狗自愈：作废旧代、复位忙标志、停一切在途搜索，
                    //   让下方用当前局面立即重跑 —— 这是"开始好、后期永久不帮想"的解药
                    Log.w(TAG, "★ 分析看门狗：单次搜索 ${ANALYSIS_TIMEOUT_MS}ms 未回，强制复位重跑(token=$analysisSearchToken)")
                    analysisGen++
                    analysisBusy = false
                    try { chessGame.engine.stopSearch() } catch (_: Exception) {}
                    lastAnalyzedFen = ""
                } else {
                    analysisPoller.postDelayed(this, ANALYSIS_POLL_BUSY_MS)
                    return
                }
            }
            // ★ 分析走 WASM 弈思(深度18)首选项/原生红引擎兜底；门槛只看两者其一就绪，避免红引擎异常拖死分析
            if (fen != lastAnalyzedFen &&
                chessGame.engine.isEngineReadyFor(com.pikafish.chess.model.PieceColor.RED)) {
                lastAnalyzedFen = fen
                runAnalysisOnce(fen)
            }
            analysisPoller.postDelayed(this, ANALYSIS_POLL_IDLE_MS)
        }
    }
    private val analysisPoller = android.os.Handler(android.os.Looper.getMainLooper())

    // 视觉Tab按钮
    private lateinit var btnOverlayCommentary: Button
    private lateinit var btnBoardScale: Button        // ★ 棋盘大小：点击弹横屏滑条弹窗（v3.7 摘下）
    private lateinit var btnPieceScale: Button        // ★ 棋子大小：点击弹横屏滑条弹窗（v3.7 摘下）
    private lateinit var btnDaMei: Button             // ★★★ 大而美：棋盘棋子最大化且不爆屏
    private lateinit var btnBoardCoord: Button        // ★★ 棋盘坐标：整盘上下微调（弹透明浮条）
    private lateinit var btnMenuCoord: Button         // ★★ 组件坐标：底栏上下微调（弹透明浮条）
    private lateinit var btnExternalEngine: Button    // ★★ 外置引擎：选 ZIP 装载，全局替换内置引擎（长按卸回）
    private lateinit var btnEngineList: Button        // ★★ 引擎列表：总览/采用/红黑PK测试/成绩
    private var dameiLocked = false                   // ★★★ 大而美边界锁定：显/隐切换棋盘不再串大小
    private var dameiBoardH = 0f                      // ★★★ 大而美锁定的棋盘区高度(px)
    private var dameiMenuH = 0f                       // ★★★ 大而美缓存的主菜单高度：显/隐切换实时菜单高跳变不再挤动棋盘
    private var pendingDaMeiRestore = false            // ★★★ 重启后待把大而美的"最大不爆屏"绝对格子重新钉上（可视带定下后静默重钉一次）
    private var stableMenuH = 0f                       // ★★ 普通模式缓存的主菜单高度：显/隐切换用固定值算棋盘上限，交界线不浮动
    private var bandBottomLocked = 0f                  // ★★★ 可视带下界锁线(菜单可见时的顶边)：隐菜单后棋盘不得趁虚而入，尺寸纹丝不动
    private var menuYOffsetPx = 310f                  // ★★「组件坐标」主菜单底边距屏幕底(px)：默认310保持现状，可微调
    private var boardYOffsetPending = 0f             // ★★「棋盘坐标」待应用值：等可视带定下后随 setContentBand 生效
    // ★ 全屏贴纸方案：棋盘可视带（顶栏贴纸底边 ↔ 主菜单贴纸顶边），只算一次，棋盘纹丝不动
    private var stickerBandTop = 0f
    private var stickerBandBottom = 0f
    private var stickerBandReady = false
    private var boardLockedToHeight = -1              // ★★ 当前已锁定的棋盘区高度(-1=未锁定)：锁定后显/隐不再重算棋盘大小
    private var firstLayoutSettled = false            // ★★ 首次聚焦收敛是否已完成（首帧稳定，不再等显/隐才锁定）
    private lateinit var spinnerLineColor: Spinner
    private lateinit var spinnerLineThickness: Spinner
    private lateinit var spinnerMenuBgColor: Spinner
    private lateinit var spinnerMenuBtnColor: Spinner
    // ★★ 解说字/点动字 滑杆 + 棋桌风格按钮（视觉Tab）
    private lateinit var tvCommentFontVal: TextView
    private lateinit var sbCommentFont: SeekBar
    private lateinit var tvTapFontVal: TextView
    private lateinit var sbTapFont: SeekBar
    private lateinit var btnTableStyle: Button
    private var commentFontSp = 14f          // 解说字号(sp)，默认与横幅一致
    private var tapFontScale = 1.0f          // 点动/盘内讲解字号倍率
    private var tableStyleIndex = DEFAULT_TABLE_STYLE   // 棋桌风格索引（默认暖白枫）
    private var labelColor = android.graphics.Color.parseColor("#5C4A2A")   // ★ 刻度文字颜色（红方中文/黑方阿拉伯为铁例，无样式选项）

    // 文件导入/导出
    private var importFormat: String = ""
    private val REQUEST_IMPORT_FILE = 1001
    private val REQUEST_SAVE_FILE = 1002
    private val REQUEST_SANDBOX = 1003
    private val REQUEST_VAULT_FILE = 1004   // ★ 点宝库记录→请用户选择本地TXT棋谱文件
    private val REQUEST_PDF_IMPORT = 1005   // ★ 外置读物：选择本地 PDF 导入书架
    private var pendingSaveContent: String = ""
    private var pendingSaveExt: String = ""

    // 摆子面板棋子按钮
    private lateinit var btnSetupClear: Button
    private lateinit var btnSetupStandard: Button
    private lateinit var btnSetupFirst: Button
    private lateinit var btnSetupDone: Button
    private lateinit var btnSetupCamera: Button      // ★ 拍照识别
    private lateinit var btnSetupGallery: Button     // ★ 上传图片识别
    // ★ 识别原图缩略图（右下角小图；上划可逐步放大占半屏供比对，右上收缩键返回）
    private lateinit var imgSetupThumb: ImageView
    private lateinit var frameSetupThumb: android.widget.FrameLayout  // 缩略图所在黑区容器
    private lateinit var btnSetupThumbShrink: Button                  // 收缩键
    private lateinit var setupCropOverlay: SetupCropOverlay           // 圈定覆盖层
    private lateinit var btnSetupCrop: Button                        // 工具面板开关
    private lateinit var layoutSetupTools: LinearLayout              // 圈定工具条
    private lateinit var btnCropCrown: Button                        // 圈定点模式
    private lateinit var btnCropClear: Button                        // 清空
    private lateinit var btnSetupResubmit: Button                    // 识别提交按钮
    private var setupThumbBitmap: Bitmap? = null     // 缩略景用的位图（小图裁剪后）
    private var setupThumbRawBitmap: Bitmap? = null  // 原始原图位图（放大比对用）
    private var setupThumbExpanded = false            // 是否处于放大态
    private var setupThumbFocused = false            // 是否处于自由摆子模式（防止误显示）
    private var setupCropModeOn = false              // 是否处于圈定/刷子工具面板
    // ★ 拖动上划：逐步挤压主棋盘，最多占半屏
    private var setupThumbBaseHeightPx = -1f          // 收起时黑区基准高度
    private var setupThumbStartDp: Int = 152          // ★ 摆子比对工具起步高度(dp)，用户已定值 152，固定不再弹面板
    private var setupThumbMaxHeightPx = -1f           // 放大上限（半屏）
    private var setupThumbDragStartY = 0f             // 触摸起点Y（rawY）
    private var setupThumbDragStartH = 0f             // 触摸起点黑区高度
    private var setupThumbDragging = false            // 是否正在拖动
    private lateinit var btnRedKing: Button; private lateinit var btnRedAdvisor: Button
    private lateinit var btnRedElephant: Button; private lateinit var btnRedRook: Button
    private lateinit var btnRedKnight: Button; private lateinit var btnRedCannon: Button
    private lateinit var btnRedPawn: Button
    private lateinit var btnBlackKing: Button; private lateinit var btnBlackAdvisor: Button
    private lateinit var btnBlackElephant: Button; private lateinit var btnBlackRook: Button
    private lateinit var btnBlackKnight: Button; private lateinit var btnBlackCannon: Button
    private lateinit var btnBlackPawn: Button
    private lateinit var btnSetupFlip: Button   // ★ 辅助：摆子模式翻转按钮（仅显示层，与识别数据隔离）

    // ★ 显示方向翻转状态：仅影响棋盘显示，不影响识别/摆子/对弈逻辑
    private var setupFlipped = false

    // ★ 摆子沙盒界面干净化：进入摆子前记住解说/棋谱区与盘心浮透层的可见性，
    //   进入后一律隐藏（不把后台思考/棋谱信息带进摆子白纸），退出时原样恢复。
    private var setupPrevExplanationVisible = true
    private var setupPrevFloatOverlayVisible = false

    private var selectedPieceType: PieceType? = null
    private var selectedPieceColor: PieceColor? = null

    private var isUpdatingUI = false
    private var settingsRestored = false  // 设置是否已恢复（防止在恢复前保存覆盖）
    private var pendingHash = 0   // 引擎启动前暂存的hash值
    private var pendingThreads = 0  // 引擎启动前暂存的线程数
    private var whatIfMode = false
    private var showTimeOnBoard = false  // 棋盘上显示时间

    // ── 棋谱播放 ──
    private var isPlaying = false
    private val playSpeeds = floatArrayOf(0.5f, 1f, 2f, 3f)
    private var playSpeedIndex = 1  // 默认 1x
    private val playHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun stopPlayback() {
        if (isPlaying) {
            isPlaying = false
            btnGuessMove.text = "播放"
            playHandler.removeCallbacks(playRunnable)
        }
    }

    private val playRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            if (chessGame.goForward()) {
                updateStepInfo()
                // 显示当前步的棋谱旁白（如果有）
                showCurrentQipuComment()
                // 根据倍速计算间隔：1x = 1000ms
                val interval = (1000f / playSpeeds[playSpeedIndex]).toLong()
                playHandler.postDelayed(this, interval)
            } else {
                // 走到头了，停止播放
                isPlaying = false
                btnGuessMove.text = "播放"
            }
        }
    }
    private var currentBgMode = 0  // 0=纯色, 1=竹林, 2=青花瓷
    private var currentPieceSkin = 0 // 0=璀璨黄金(默认) … 5=青花鼓形（共 6 套 3D 皮肤，点一下换一套）
    /** 皮肤显示名（与 ChessBoardView.pieceSkinNames 同序） */
    private val skinNames = listOf("璀璨黄金", "水晶琉璃", "烈焰木炭", "鼓形雕花", "羊脂白玉", "青花鼓形")
    private fun skinLabel(): String = skinNames[currentPieceSkin.coerceIn(0, skinNames.size - 1)]
    private var overlayCommentaryMode = 0  // 0=关, 1=开, 2=点动
    private var currentLineColorIndex = 0  // 棋线颜色索引
    private var currentLineThicknessIndex = 2  // 棋线粗细索引（默认"标准"）
    private var currentMenuBgIndex = 0  // 菜单栏底色索引
    private var currentMenuBtnIndex = 0  // 菜单栏按钮色索引

    // ★ 时间显示定时刷新
    private val timeUpdateHandler = Handler(Looper.getMainLooper())
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            if (showTimeOnBoard) {
                chessBoard.invalidate()
                timeUpdateHandler.postDelayed(this, 1000L)
            }
        }
    }

    // ★ 假设分析弹窗
    private var whatIfDialog: AlertDialog? = null

    // ★ 拍照识别 — ONNX 深度学习识别器（主） + OCR 识别器（备）
    private val onnxRecognizer = OnnxBoardRecognizer(this)
    private val boardRecognizer = BoardRecognizer()
    private var cameraImageUri: Uri? = null
    private var recognitionProgressDialog: AlertDialog? = null

    // ★ Activity Result：拍照
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraImageUri != null) {
            performRecognition(cameraImageUri!!)
        }
    }

    // ★ Activity Result：图库选图
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            performRecognition(uri)
        }
    }

    // ★ Activity Result：外置引擎 ZIP（内含引擎二进制＋NNUE 权重）——装载后全局替换内置引擎
    private val externalEngineLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) installExternalEngine(uri)
    }

    // ★ Activity Result：全局底色 —— 选图片（单张直接用，多张列出勾选）
    private val backdropImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (!uris.isNullOrEmpty()) {
            com.pikafish.chess.ui.GlobalBackdrop.importImagesAsBackdrop(this, uris) { applyGlobalBackdrop() }
        }
    }

    // ★ Activity Result：全局底色 —— 从 ZIP 里把图片全部列出勾选
    private val backdropZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            com.pikafish.chess.ui.GlobalBackdrop.importZipAsBackdrop(this, uri) { applyGlobalBackdrop() }
        }
    }
    
    // 颜色/粗细数据（类级别，供持久化恢复使用）
    private val lineColors = listOf(
        "古铜色" to "#5D4037", "朱砂红" to "#C62828", "墨玉绿" to "#1B5E20",
        "藏蓝" to "#283593", "银灰" to "#607D8B", "金色" to "#FFB300",
        "象牙白" to "#FFE0B2", "琥珀橙" to "#E65100", "紫檀" to "#4A148C",
        "青金石" to "#1565C0", "烟熏黑" to "#212121", "暗金" to "#8D6E63"
    )
    private val lineThicknesses = listOf(
        "极细" to 0.8f, "细线" to 1.2f, "标准" to 1.8f,
        "粗线" to 2.5f, "超粗" to 3.5f, "极粗" to 4.5f
    )
    private val menuBgColors = listOf(
        // ［高亮·暖米/奶油系（默认）］
        "暖米" to "#FFF3C4", "奶油" to "#FFF8E1", "象牙" to "#FFFDE7",
        "鹅黄" to "#FFF59D", "麦浪" to "#FFF3C4", "杏白" to "#FFE6BC",
        // ［高亮·暖色系］
        "蜜桃" to "#FFCCBC", "蜜橙" to "#FFE0B2", "柠檬" to "#FFF176",
        "暖砂" to "#FFCC80", "焦麦" to "#FFB74D", "淡粉" to "#FFEBEE",
        // ［高亮·清新系］
        "新绿" to "#DCEDC8", "薄荷" to "#C8EAE4", "淡青" to "#B2EBF2",
        "天蓝" to "#BEE7FC", "湖水" to "#B2DFDB", "香芋" to "#EDE7F6",
        // ［亮灰/中性］
        "银灰" to "#ECEFF1", "云白" to "#F5F5F5", "浅青灰" to "#E0E0E0",
        // ［深色·少量保留］
        "深木" to "#3E2723", "墨绿" to "#1B3A2A", "藏蓝" to "#1A2A5C"
    )
    private val menuBtnColors = listOf(
        "金色" to "#FFB300", "琥珀" to "#FF8F00", "银灰" to "#90A4AE",
        "铜绿" to "#2E7D32", "朱砂" to "#E53935", "宝石蓝" to "#1976D2",
        "橙红" to "#FF7043", "紫罗兰" to "#7E57C2"
    )
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val handler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Uncaught: ${e.message}", e)
        runOnUiThread {
            tvStatus.text = "程序异常: ${e.message}"
            AlertDialog.Builder(this)
                .setTitle("程序异常")
                .setMessage("发生未捕获的异常:\n${e.message}\n\n请重新启动应用")
                .setPositiveButton("退出") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }
    private val initScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + handler)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 冷启动判定：全新打开/装机（savedInstanceState==null）视为冷启动，
        //   启动后默认落到「更多」页让宣传视频在其旷区完整播放；
        //   后台恢复（进程被回收重启）时 savedInstanceState!=null → 保持原有行为，不强制跳转。
        coldStartFresh = savedInstanceState == null

        // ── 沉浸式：主界面有常驻底栏，必须隐藏系统导航键（上滑可临时唤出），
        //    否则底栏会被系统三键顶起/遮挡。此处 onCreate 即落一次，onResume/onFocus 再兜底重落。
        applyImmersiveMode()
        applyNavBarInset()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Log.e(TAG, "Crash in ${thread.name}: ${e.message}", e)
            try {
                val crashFile = java.io.File(filesDir, "crash.log")
                crashFile.writeText("${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}")
            } catch (_: Exception) {}
            // ★★★ 关键改造：任何线程崩溃都不再杀死进程，只记录并显示错误页，
            //   避免"点催/点工具就整个小棋盘+菜单闪退"。进程存活才能继续对弈。
            //   不再转交 defaultHandler.uncaughtException（那会终止进程）。
            if (thread == Looper.getMainLooper().thread) {
                // 主线程崩溃：显示错误页但不退出
                try {
                    val errView = android.widget.TextView(this).apply {
                        text = "应用崩溃: ${e.javaClass.simpleName}\n${e.message}\n\n请截图反馈，然后重启应用"
                        setTextColor(android.graphics.Color.RED)
                        textSize = 16f
                        setPadding(40, 100, 40, 40)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                    setContentView(errView)
                } catch (_: Exception) {
                    // 不kill进程
                }
            } else {
                // ★ 非主线程（引擎协程等）崩溃：记录即可，进程继续存活
            }
        }

        try {
            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
            Log.e(TAG, "setContentView failed: ${e.message}", e)
            val errView = android.widget.TextView(this).apply {
                text = "布局加载失败: ${e.message}\n\n请反馈此错误"
                setTextColor(android.graphics.Color.RED)
                textSize = 16f
                setPadding(40, 100, 40, 40)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            setContentView(errView)
            return
        }

        try {
            chessGame = ChessGame(this)
            initViews()
            setupCallbacks()

            // ★ 请求存储权限（用于保存棋谱到 /sdcard/HHXQ/）
            requestStoragePermission()

            // ★ 立即恢复所有设置参数（不依赖引擎是否启动成功）
            restoreAllSettings()

            // ★★★ 首次安装布置：装机后第一次启动，顺水推舟给一套「3D 皮肤 + 大而美视角」。
            //   只做这一次；之后用户再怎么自定义（皮肤/棋盘/棋子大小），一律不干预、不打回原形。
            if (prefs.getBoolean(KEY_FIRST_RUN, true)) {
                prefs.edit().putBoolean(KEY_FIRST_RUN, false).apply()
                if (::chessBoard.isInitialized) {
                    currentPieceSkin = 0                     // 第1套·璀璨黄金
                    chessBoard.setPieceSkin(0)
                    btnPieceSkin.text = "皮肤:${skinLabel()}"
                    applyDaMei()                             // 大而美：棋盘棋子最大且不爆屏（内部末尾会持久化）
                }
            }

            // ★ 恢复上次选择的背景音乐（若之前已开启，则启动即续播；跨次启动持久）
            try {
                val savedBgm = com.pikafish.chess.utils.SoundManager.restoreBgm(applicationContext)
                if (savedBgm > 0) com.pikafish.chess.utils.SoundManager.playBgm(this, savedBgm)
            } catch (_: Exception) {}

            tvEngineInfo.text = "引擎加载中…"
            tvStatus.text = "正在初始化…"
            setButtonsEnabled(false)

            // ★ 宣传小视频已按要求移除，不再播放/占位。

            initScope.launch {
                initializeEngineStepByStep()
            }

            // ★ 外部应用（QQ / 文件管理器「打开其他应用」）送进来的棋谱：等主界面首帧就绪后自动入宝库并开谱。
            window.decorView.post {
                handleIncomingExternalFile()
                handleIncomingRecognizeImage()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            tvStatus.text = "初始化异常: ${e.message}"
            tvStatus.setTextColor(android.graphics.Color.RED)
        }
    }

    // ── 引擎初始化 ──

    private suspend fun initializeEngineStepByStep() {
        val engine = chessGame.engine

        // ★ 先决定引擎运行模式：有懒人外置引擎/权重 → 外置皮卡鱼；否则 GPU 神经引擎
        withContext(Dispatchers.IO) { engine.resolveEngineMode() }

        updateProgress("正在准备权重文件…")
        var error = withContext(Dispatchers.IO) { engine.copyCurrentNNue() }
        if (error != null) { showErrorAndExit("NNUE权重文件准备失败\n$error"); return }
        delay(50)

        // ★ 关键步骤：定位引擎二进制文件（复制到 filesDir 确保可执行）
        updateProgress("正在定位引擎…")
        error = withContext(Dispatchers.IO) { engine.locateEngine() }
        if (error != null) { showErrorAndExit("引擎定位失败\n$error"); return }
        delay(50)

        // ★ 关键步骤：启动引擎子进程
        updateProgress("正在启动引擎…")
        error = withContext(Dispatchers.IO) { engine.startEngine() }
        if (error != null) { showErrorAndExit("引擎启动失败\n$error"); return }
        delay(50)

        // 初始化引擎（UCI 握手 + 配置 + 验证）
        updateProgress("正在初始化引擎…")
        error = withContext(Dispatchers.IO) { engine.fullInit() }
        if (error != null) { showErrorAndExit("引擎初始化失败\n$error"); return }
        delay(50)

        updateProgress("引擎就绪！")
        updateEngineInfo()
        updateSettingsUI()
        setButtonsEnabled(true)
        val extStatus = try { engine.externalPackageStatus() } catch (_: Exception) { "内置引擎/权重" }
        val engName = com.pikafish.chess.engine.EngineIdentity.shortName
        Toast.makeText(this, "${engName}引擎就绪！$extStatus", Toast.LENGTH_SHORT).show()

        chessGame.onEngineStartedSuccess()

        // ★★★ 启动独立通道巡检引擎：红黑引擎一旦卡死/无响应，巡检器自动抢救。
        //   必须放在引擎就绪之后，且早于任何对局开始，才能第一时间兜住卡死。
        chessGame.startWatchdog()
        Log.i(TAG, "★ 独立通道巡检引擎已随引擎就绪启动")

        // ★ 主界面对弈统一走原生询招沙盒（SandboxOracle）；已彻底移除 WASM 引擎与预热。
        //   引擎启动后对弈即用即走，任何异常都不阻塞，自动回退原生皮卡鱼。

        // ★ 引擎启动后，将恢复的hash/threads应用到引擎
        if (pendingHash > 0 || pendingThreads > 0) {
            chessGame.updateEngineSettings(
                hash = if (pendingHash > 0) pendingHash else null,
                threads = if (pendingThreads > 0) pendingThreads else null
            )
            updateSettingsUI()
            updateEngineInfo()
        }

        // ★ 续盘功能：第三个加载顺序「本地文档/存档」——先快速试 SharedPreferences 完整状态（主线程，毫秒级）；
        //   若已被清（手动清缓存/数据），则「存档收集」放到后台线程异步扫描 HHXQ 绝对路径下全部文件，
        //   主线程先 newGame() 兜底立即开局、绝不阻塞（避免文件多时 ANR 卡死），
        //   后台扫到本地存档后再切回主线程覆盖恢复，无需手工导入。
        if (tryRestoreGame()) {
            Toast.makeText(this, "已恢复上次棋局", Toast.LENGTH_SHORT).show()
            // ★ 恢复后检查是否轮到AI走棋，如果是则主动触发
            chessGame.checkAndTriggerAiMove()
            logCurrentGameToJournal("主界面")   // ★ 续盘即记入对局日志
        } else {
            chessGame.newGame()       // 兜底：先开局，不让用户空等
            scheduleHHXQAutoLoad()    // 后台异步扫描 HHXQ 全量收集并恢复
        }
    }

    private fun updateProgress(msg: String) {
        tvStatus.text = msg
        Log.d(TAG, "Progress: $msg")
    }

    // ★ 引擎加载失败不再强制退出：改为降级进入"无引擎模式"（双人对弈），界面完整可用，
    //   仅 AI 不可动（关闭 AI 回合的卡死等待）。适合模拟器只调界面/布局，或真机引擎损坏时照常看棋。
    private fun showErrorAndExit(msg: String) {
        runOnUiThread {
            tvEngineInfo.text = "引擎不可用（已进入无引擎模式）"
            tvStatus.text = "引擎启动失败"
            AlertDialog.Builder(this)
                .setTitle("引擎启动失败")
                .setMessage("$msg\n\n是否仍在无引擎模式下继续使用界面？\nAI 将不可走棋，但棋盘/布局/双人对弈均可正常操作。")
                .setNegativeButton("退 出") { _, _ -> finish() }
                .setPositiveButton("继续使用界面") { _, _ -> enterDegradedNoEngineMode(msg) }
                .setCancelable(false)
                .show()
        }
    }

    // ★ 降级为"无引擎模式"：双方设为人脑 → AI 永远不会被触发 → 彻底绕开引擎依赖。
    //   同时照常 newGame()、启用全部按钮，棋盘/棋子/组件坐标照常可调。
    private fun enterDegradedNoEngineMode(reason: String) {
        try {
            // 双人脑：blackIsHuman 默认 false（黑方AI），必须改成 true，否则AI回合会卡死等待。
            chessGame.redIsHuman = true
            chessGame.blackIsHuman = true
            tvEngineInfo.text = "引擎不可用"
            tvStatus.text = "无引擎模式（双人对弈 · 引擎已启用失败）"
            setButtonsEnabled(true)
            chessGame.newGame()          // 引擎未就绪时 newGame() 内部有 isEngineReady 守卫，安全
        } catch (e: Exception) {
            Log.e(TAG, "降级无引擎模式失败", e)
        }
        Log.w(TAG, "★ 引擎启动失败，已降级无引擎模式（双人对弈）: $reason")
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnNewGame.isEnabled = enabled
        btnGoBack.isEnabled = enabled
        btnGoForward.isEnabled = enabled
        btnGoStart.isEnabled = enabled
        btnGoEnd.isEnabled = enabled
        btnFlip.isEnabled = enabled
        btnRedSide.isEnabled = enabled
        btnBlackSide.isEnabled = enabled
        btnSetupMode.isEnabled = enabled
        btnApplySettings.isEnabled = enabled
        btnExportPgn.isEnabled = enabled
        btnImportPgn.isEnabled = enabled
        btnReview.isEnabled = enabled
        btnPracticeOpening.isEnabled = enabled
        btnExportFen.isEnabled = enabled
        btnExportTxt.isEnabled = enabled
        btnImportFen.isEnabled = enabled
        btnImportTxt.isEnabled = enabled
        btnGameRecords.isEnabled = enabled
        btnCommentaryStyle.isEnabled = enabled
        btnBgSwitch.isEnabled = enabled
        btnGuessMove.isEnabled = enabled
        btnPlaySpeed.isEnabled = enabled
        btnTimeToggle.isEnabled = enabled
        btnEngineRecover.isEnabled = enabled
        btnOverlayCommentary.isEnabled = enabled
        btnBoardScale.isEnabled = enabled
        btnPieceScale.isEnabled = enabled
        spinnerLineColor.isEnabled = enabled
        spinnerLineThickness.isEnabled = enabled
        spinnerMenuBgColor.isEnabled = enabled
        spinnerMenuBtnColor.isEnabled = enabled
    }

    // ── 视图初始化 ──

    private fun initViews() {
        chessBoard = findViewById(R.id.chessBoard)
        frameBoardOuter = findViewById(R.id.frameBoardOuter)
        // ★ 每次布局变化后校准棋盘区，让蓝色只包住棋盘、富余高度让给思考输出
        (window.decorView.rootView.viewTreeObserver).addOnGlobalLayoutListener {
            tuneBoardVsThinking()
        }
        bubbleBookBranch = findViewById(R.id.bubbleBookBranch)
        // 点一下气泡即关掉（不挡棋盘）
        bubbleBookBranch.setOnClickListener { hideBranchBubble() }
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusEmoji = findViewById(R.id.tvStatusEmoji)
        tvEngineInfo = findViewById(R.id.tvEngineInfo)
        setupSpectateEntry()
        // ★ 思考详情条 tvThinking：布局 XML 中已移除（不再压在棋盘/棋谱面板上方挡棋），
        //   这里运行时动态创建，挂到根布局 rootMain 底部——成为底部窄空隙里一条浅色信息条，
        //   最多显示 2 行、绝不把屏幕撑高、绝不遮挡棋盘（位置可用调试面板3微调）。
        tvThinking = ensureThinkingBar()
        btnBookMenu = findViewById(R.id.btnBookMenu)
        tvStepInfo = findViewById(R.id.tvStepInfo)
        btnNewGame = findViewById(R.id.btnNewGame)
        btnGoBack = findViewById(R.id.btnGoBack)
        btnGoForward = findViewById(R.id.btnGoForward)
        btnToggleMenu = findViewById(R.id.btnToggleMenu)
        tvFloatOverlay = findViewById(R.id.tvFloatOverlay)
        btnFloatOverlay = findViewById(R.id.btnFloatOverlay)
        btnGoStart = findViewById(R.id.btnGoStart)
        btnGoEnd = findViewById(R.id.btnGoEnd)
        btnGuessMove = findViewById(R.id.btnGuessMove)
        btnPlaySpeed = findViewById(R.id.btnPlaySpeed)
        btnQipuCommentary = findViewById(R.id.btnQipuCommentary)
        btnQipuCommentaryList = findViewById(R.id.btnQipuCommentaryList)
        btnBookMemorize = findViewById(R.id.btnBookMemorize)
        btnFlip = findViewById(R.id.btnFlip)
        btnGameInfo = findViewById(R.id.btnGameInfo)
        btnChallenge = findViewById(R.id.btnChallenge)
        btnRedSide = findViewById(R.id.btnRedSide)
        btnBlackSide = findViewById(R.id.btnBlackSide)
        // ★ 对弈页面①（主棋盘）
        layoutGamePage1 = findViewById(R.id.layoutGamePage1)
        layoutGameSub = findViewById(R.id.layoutGameSub)
        layoutGameSubRow2 = findViewById(R.id.layoutGameSubRow2)
        btnMode = findViewById(R.id.btnMode)
        btnCloudBook = findViewById(R.id.btnCloudBook)
        etValue = findViewById(R.id.etValue)
        btnMinus = findViewById(R.id.btnMinus)
        btnPlus = findViewById(R.id.btnPlus)
        btnDetail = findViewById(R.id.btnDetail)
        btnAlwaysThink = findViewById(R.id.btnAlwaysThink)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnExportPgn = findViewById(R.id.btnExportPgn)
        btnImportPgn = findViewById(R.id.btnImportPgn)
        btnReview = findViewById(R.id.btnReview)
        btnPracticeOpening = findViewById(R.id.btnPracticeOpening)
        layoutPracticeBar = findViewById(R.id.layoutPracticeBar)
        tvPracticeStatus = findViewById(R.id.tvPracticeStatus)
        btnPracticeUndo = findViewById(R.id.btnPracticeUndo)
        btnPracticeReview = findViewById(R.id.btnPracticeReview)
        btnPracticeExit = findViewById(R.id.btnPracticeExit)
        btnExportFen = findViewById(R.id.btnExportFen)
        btnExportTxt = findViewById(R.id.btnExportTxt)
        btnImportFen = findViewById(R.id.btnImportFen)
        btnImportTxt = findViewById(R.id.btnImportTxt)
        btnGameRecords = findViewById(R.id.btnGameRecords)
        btnCommentaryStyle = findViewById(R.id.btnCommentaryStyle)
        btnBgSwitch = findViewById(R.id.btnBgSwitch)
        btnPieceSkin = findViewById(R.id.btnPieceSkin)
        tvExplanation = findViewById(R.id.tvExplanation)
        scrollViewExplanation = findViewById(R.id.scrollViewExplanation)
        tvCommentaryBanner = findViewById(R.id.tvCommentaryBanner)
        // ★★ 已停用窗口区：解说改为透明悬浮弹窗，原窗口横幅整个藏起来，不再占用布局空间
        tvCommentaryBanner.visibility = View.GONE
        tvCommentaryFloat = findViewById(R.id.tvCommentaryFloat)
        layoutExplanationList = findViewById(R.id.layoutExplanationList)
        tvExplanation.text = "走棋后这里会显示棋谱…"
        etHash = findViewById(R.id.etHash)
        etThreads = findViewById(R.id.etThreads)
        btnApplySettings = findViewById(R.id.btnApplySettings)
        btnTimeToggle = findViewById(R.id.btnTimeToggle)
        btnEngineRecover = findViewById(R.id.btnEngineRecover)
        btnSetupMode = findViewById(R.id.btnSetupMode)
        btnSetupReturn = findViewById(R.id.btnSetupReturn)
        layoutSetupPanel = findViewById(R.id.layoutSetupPanel)
        layoutNormalControls = findViewById(R.id.layoutNormalControls)
        scrollTabContent = findViewById(R.id.scrollTabContent)
        // ★ 全屏贴纸方案：主菜单高度/可见性变化（宣传视频进出、页签内容变化等）时，
        //   自动重算思考/解说区贴纸的底部边距，保证与主菜单/底栏严丝合缝、绝不重叠。
        layoutNormalControls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (::frameSetupThumb.isInitialized) updateStickerMargins()
            // ★ 页签切换/菜单高度变化后，先把总高统一压成棋谱式紧凑高度，再贴棋盘
            syncGlobalMenuHeight()
            // ★★ 菜单顶边变了就重算棋盘可视带：棋盘实时跟随菜单，顶部不裁切、底部不留空气
            frameBoardOuter.post { computeContentBand() }
        }
        // ★★ 顶栏贴纸高度/边距变化（系统状态栏 inset 异步到位、字体缩放、换行等）时，
        //   立刻重算棋盘可视带：棋盘「顶排锁定」后，顶排永远完整露在顶栏之下，绝不埋进顶栏。
        findViewById<View>(R.id.layoutStatusBar)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            frameBoardOuter.post { computeContentBand() }
        }
        // ★ 首帧布局完成后校准一次贴纸边距（初始菜单显/隐状态以实际可见性为准），
        //   并算一次棋盘可视带（顶栏底 ↔ 主菜单顶），棋盘贴住带上边摆放
        frameBoardOuter.post {
            updateStickerMargins()
            syncGlobalMenuHeight()
            computeContentBand()
        }

        // ★★ 固化主菜单按键组位置（用户已确认 X=0, Y底=310）：
        //   首次布局后应用到位。调试面板已删除，位置定死不再弹窗。
        //   ★ 菜单贴底坐标应用后才真正落到「底栏之上」；此时菜单 top 才是最终值，
        //   故在贴底后**再算一次棋盘可视带**，确保读到的是菜单最终真实 Y 而非贴地前的旧值。
        frameBoardOuter.post {
            val menu = findViewById<android.widget.LinearLayout>(R.id.layoutNormalControls)
            try {
                val lp = menu.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null) {
                    lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                    lp.leftMargin = 0
                    // ★★「组件坐标」：主菜单底边距屏幕底，用户可微调（默认310=现状）
                    lp.bottomMargin = menuYOffsetPx.toInt()
                    menu.layoutParams = lp
                }
            } catch (_: Exception) { }
            // 菜单贴底布局已提交，可能有二次测量/重排，post 后重算可视带读最终坐标
            menu.post { computeContentBand() }
        }

        // 横排Tab按钮
        btnTabSettings = findViewById(R.id.btnTabSettings)
        btnTabGame = findViewById(R.id.btnTabGame)
        btnTabNotation = findViewById(R.id.btnTabNotation)
        layoutParamsContent = findViewById(R.id.layoutParamsContent)
        layoutGameContent = findViewById(R.id.layoutGameContent)
        layoutNotationContent = findViewById(R.id.layoutNotationContent)
        layoutVisualContent = findViewById(R.id.layoutVisualContent)
        layoutMoreContent = findViewById(R.id.layoutMoreContent)
        btnQipuVault = findViewById(R.id.btnQipuVault)
        btnQipuImpression = findViewById(R.id.btnQipuImpression)
        btnQipuPrev = findViewById(R.id.btnQipuPrev)
        btnQipuNext = findViewById(R.id.btnQipuNext)
        btnTagCurrent = findViewById(R.id.btnTagCurrent)
        // ★★ 摆子绝缘带：进入摆子后，底部常驻窄条(◀/上一则/▣标/隐·显/下一则/▶)一律不响应——
        //   摆子界面只认「摆子面板」自身操作；在摆子下误点窄条不会触发菜单/进谱/导航，
        //   杜绝 面板冻结或强退。退出摆子自动恢复可点。
        findViewById<android.view.ViewGroup>(R.id.layoutBottomNav)?.let { strip ->
            for (i in 0 until strip.childCount) {
                val v = strip.getChildAt(i)
                v.isClickable = true
                v.setOnTouchListener { _, _ -> chessGame.isSetupMode }
            }
        }
        layoutQipuSub = findViewById(R.id.layoutQipuSub)
        layoutQipuImports = findViewById(R.id.layoutQipuImports)
        btnAuthorIntent = findViewById(R.id.btnAuthorIntent)
        btnJoinGroup = findViewById(R.id.btnJoinGroup)
        btnOtherWorks = findViewById(R.id.btnOtherWorks)
        btnBgm = findViewById(R.id.btnBgm)
        btnFreshLife = findViewById(R.id.btnFreshLife)
        btnExternalReader = findViewById(R.id.btnExternalReader)

        // 视觉Tab按钮
        btnOverlayCommentary = findViewById(R.id.btnOverlayCommentary)
        btnBoardScale = findViewById(R.id.btnBoardScale)
        btnPieceScale = findViewById(R.id.btnPieceScale)
        btnDaMei = findViewById(R.id.btnDaMei)
        btnBoardCoord = findViewById(R.id.btnBoardCoord)
        btnMenuCoord = findViewById(R.id.btnMenuCoord)
        btnExternalEngine = findViewById(R.id.btnExternalEngine)
        btnEngineList = findViewById(R.id.btnEngineList)
        layoutVisualContent2 = findViewById(R.id.layoutVisualContent2)
        spinnerLineColor = findViewById(R.id.spinnerLineColor)
        spinnerLineThickness = findViewById(R.id.spinnerLineThickness)
        spinnerMenuBgColor = findViewById(R.id.spinnerMenuBgColor)
        spinnerMenuBtnColor = findViewById(R.id.spinnerMenuBtnColor)
        tvCommentFontVal = findViewById(R.id.tvCommentFontVal)
        sbCommentFont = findViewById(R.id.sbCommentFont)
        tvTapFontVal = findViewById(R.id.tvTapFontVal)
        sbTapFont = findViewById(R.id.sbTapFont)
        btnTableStyle = findViewById(R.id.btnTableStyle)

        // 摆子面板棋子按钮
        btnRedKing = findViewById(R.id.btnRedKing)
        btnRedAdvisor = findViewById(R.id.btnRedAdvisor)
        btnRedElephant = findViewById(R.id.btnRedElephant)
        btnRedRook = findViewById(R.id.btnRedRook)
        btnRedKnight = findViewById(R.id.btnRedKnight)
        btnRedCannon = findViewById(R.id.btnRedCannon)
        btnRedPawn = findViewById(R.id.btnRedPawn)
        btnBlackKing = findViewById(R.id.btnBlackKing)
        btnBlackAdvisor = findViewById(R.id.btnBlackAdvisor)
        btnBlackElephant = findViewById(R.id.btnBlackElephant)
        btnBlackRook = findViewById(R.id.btnBlackRook)
        btnBlackKnight = findViewById(R.id.btnBlackKnight)
        btnBlackCannon = findViewById(R.id.btnBlackCannon)
        btnBlackPawn = findViewById(R.id.btnBlackPawn)
        btnSetupClear = findViewById(R.id.btnSetupClear)
        btnSetupStandard = findViewById(R.id.btnSetupStandard)
        btnSetupFirst = findViewById(R.id.btnSetupFirst)
        btnSetupDone = findViewById(R.id.btnSetupDone)
        btnSetupCamera = findViewById(R.id.btnSetupCamera)
        btnSetupGallery = findViewById(R.id.btnSetupGallery)
        btnSetupFlip = findViewById(R.id.btnSetupFlip)

        // ── 原图缩略图：上划逐步放大占半屏供比对，点击切换，右上收缩键返回 ──
        imgSetupThumb = findViewById(R.id.imgSetupThumb)
        frameSetupThumb = findViewById(R.id.frameSetupThumb)
        // ★★ 摆子「铁板一块」捆绑：黑区(识别原图)位置/尺寸一变（拖拽伸缩/轻点切换/收起/旋转），
        //   立刻联动：摆子面板同步上移 + 棋盘以顶排为锚向下等比缩小——三者严丝合缝，
        //   绝不重叠、绝不留大片空白。
        frameSetupThumb.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (::layoutSetupPanel.isInitialized) syncSetupBundle()
        }
        btnSetupThumbShrink = findViewById(R.id.btnSetupThumbShrink)
        setupCropOverlay = findViewById(R.id.setupCropOverlay)
        btnSetupCrop = findViewById(R.id.btnSetupCrop)
        btnSetupResubmit = findViewById(R.id.btnSetupResubmit)
        layoutSetupTools = findViewById(R.id.layoutSetupTools)
        btnCropCrown = findViewById(R.id.btnCropCrown)
        btnCropClear = findViewById(R.id.btnCropClear)
        // ★ 收缩键：点击把图片从放大态缩回右下角小图
        btnSetupThumbShrink.setOnClickListener { collapseSetupThumb() }
        // ★ 翻转按钮：仅上/下红黑对调显示，方便与原图比对（纯显示层，不触碰识别/摆子数据）
        btnSetupFlip.setOnClickListener {
            setupFlipped = !setupFlipped
            chessBoard.setFlipped(setupFlipped)
            btnSetupFlip.text = if (setupFlipped) "已翻转" else "🔃 翻转"
        }
        // ★ 圈定工具开关：进入/退出圈定面板
        btnSetupCrop.setOnClickListener { toggleSetupCropMode() }
        // ★ 工具条：切换圈定点模式
        btnCropCrown.setOnClickListener {
            setupCropOverlay.crownEnabled = !setupCropOverlay.crownEnabled
            updateCropToolButtons()
        }
        // ★ 工具条：清空全部
        btnCropClear.setOnClickListener { setupCropOverlay.clearAll(); updateCropToolButtons() }
        // ★ 识别提交：按圈定范围剔除外围后重新识别
        btnSetupResubmit.setOnClickListener { submitCroppedRecognition() }
        // ★ 圈点操作变化 → 刷新工具状态
        setupCropOverlay.onAnyChange = { runOnUiThread { updateCropToolButtons() } }
        // ★ 触摸：拖动（上划放大/下划缩小，逐步挤压主棋盘）+ 轻点切换放大/收起
        imgSetupThumb.setOnTouchListener { v, ev ->
            if (!setupThumbFocused || setupThumbRawBitmap == null) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ensureSetupThumbMetrics()
                    setupThumbDragStartY = ev.rawY
                    setupThumbDragStartH = frameSetupThumb.height.toFloat()
                    setupThumbDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = setupThumbDragStartY - ev.rawY
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (!setupThumbDragging && Math.abs(dy) < slop) {
                        true
                    } else {
                        setupThumbDragging = true
                        // ★ 上划dy正→高度增（挤压棋盘），下划dy负→高度减；夹在基准与半屏之间
                        var newH = (setupThumbDragStartH + dy)
                            .coerceIn(setupThumbBaseHeightPx, setupThumbMaxHeightPx)
                        applySetupThumbHeight(newH)
                        true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = setupThumbDragStartY - ev.rawY
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (!setupThumbDragging && Math.abs(dy) < slop) {
                        // 轻点：切换放大/收起；拖动结束则保持当前高度
                        toggleSetupThumb()
                    }
                    setupThumbDragging = false
                    true
                }
                else -> false
            }
        }

        updateSettingUI()
        updateSettingsUI()
        updateSideButtons()
        updateDetailButton()
        updateFloatOverlayButton()
        updateAlwaysThinkButton()

        // ── 数值输入 ──
        etValue.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingUI) return
                val value = s?.toString()?.toIntOrNull()
                if (value != null && value > 0) { applyValue(value); saveAllSettings() }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnMinus.setOnClickListener {
            val current = getCurrentValue()
            if (current > 1) { applyValue(current - 1); updateSettingUI(); saveAllSettings() }
        }

        btnPlus.setOnClickListener {
            val current = getCurrentValue()
            val maxVal = if (chessGame.searchMode == "depth") 30 else 300
            if (current < maxVal) { applyValue(current + 1); updateSettingUI(); saveAllSettings() }
        }

        // ★ 模式切换：点 btnMode 切换「步时模式 ↔ 深度模式」
        btnMode.setOnClickListener {
            chessGame.searchMode = if (chessGame.searchMode == "depth") "time" else "depth"
            val modeName = if (chessGame.searchMode == "depth") "深度模式" else "步时模式"
            Toast.makeText(this, "切换为$modeName", Toast.LENGTH_SHORT).show()
            updateSettingUI()
            saveAllSettings()
        }

        // ★ 云库开关：点一下开，再点一关
        btnCloudBook.setOnClickListener {
            chessGame.cloudBookEnabled = !chessGame.cloudBookEnabled
            updateCloudBookButton()
            Toast.makeText(this, if (chessGame.cloudBookEnabled) "云库已开启" else "云库已关闭", Toast.LENGTH_SHORT).show()
            saveAllSettings()
        }

        // ── 思考细节 ──
        btnDetail.setOnClickListener {
            chessGame.showThinkingDetails = !chessGame.showThinkingDetails
            updateDetailButton()
            if (!chessGame.showThinkingDetails) {
                tvThinking.visibility = View.INVISIBLE
                tvThinking.text = ""
            } else {
                tvThinking.visibility = View.VISIBLE
            }
            saveAllSettings()
        }

        // ── ★ 分析浮透：开启后分析内容半透明浮现在盘心（你侧），透棋读字 ──
        btnFloatOverlay.setOnClickListener {
            floatOverlayEnabled = !floatOverlayEnabled
            updateFloatOverlayButton()
            refreshFloatOverlay()
            saveAllSettings()   // 状态持久化
        }

        // ── ★ 后台思考：人脑思考时，电脑（空闲那台引擎）先开 MultiPV 预想；
        //    人脑一走若命中预案就直接秒走，不必再等引擎现算。──
        btnAlwaysThink.setOnClickListener {
            chessGame.alwaysThinking = !chessGame.alwaysThinking
            updateAlwaysThinkButton()
            if (chessGame.alwaysThinking) {
                // ★ 修复：先清掉残留的预想任务，再按新设置重算一次；
                //   否则旧任务占着引擎，切开关后多线预想既不重算也不刷新
                try { chessGame.engine.stopPonder() } catch (_: Exception) {}
                chessGame.startBackgroundThinkingNow()
                Toast.makeText(this, "后台思考已开启：你思考时电脑先预想，命中即秒走", Toast.LENGTH_SHORT).show()
            } else {
                // 关闭：立即停掉在跑的预想，别让引擎空转耗电
                try { chessGame.engine.stopPonder() } catch (_: Exception) {}
                Toast.makeText(this, "后台思考已关闭", Toast.LENGTH_SHORT).show()
            }
            saveAllSettings()   // 状态持久化
        }

        // ── ★ 浮透窗口可拖拽：不用悬浮窗权限，限在棋盘内自由拖动 ──
        hookFloatOverlayDrag()
        // ★ 统一旁白/解说弹窗（置顶、加宽、✕、分页）
        setupCommentPopup()
        // ★ 解说弹窗同样可拖动（和『分析浮透』一个待遇）：挡住哪儿就随手挪开，绝不挡着走子
        if (::tvCommentaryFloat.isInitialized && tvCommentaryFloat.parent is android.view.ViewGroup) {
            hookDrag(tvCommentaryFloat)
        }

        // ── 解说风格切换（三态循环：人话 → 现世报 → 关 → 人话…）──
        btnCommentaryStyle.setOnClickListener {
            val styles = ChessGame.CommentaryStyle.values()
            val cur = chessGame.commentaryStyle
            chessGame.commentaryStyle = styles.getOrElse((cur.ordinal + 1) % styles.size) { cur }
            btnCommentaryStyle.text = chessGame.commentaryStyle.label
            refreshCommentaryBanner()
            updateAnalysisButton()
            Toast.makeText(this, "解说: ${chessGame.commentaryStyle.label}", Toast.LENGTH_SHORT).show()
            saveAllSettings()
        }

        // ── 棋布切换（循环16种棋盘布料样式；原「背景」改名「棋布」，指棋桌盘面那张布）──
        btnBgSwitch.setOnClickListener {
            val bgModes = com.pikafish.chess.ui.ChessBoardView.BgMode.values()
            currentBgMode = (currentBgMode + 1) % bgModes.size
            val mode = bgModes[currentBgMode]
            chessBoard.setBackgroundMode(mode)
            val label = "棋布:${mode.label}"
            btnBgSwitch.text = label
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            saveAllSettings()
        }

        // ── 棋子皮肤切换（6 套 3D 皮肤循环：璀璨黄金 → 水晶琉璃 → 烈焰木炭 → 鼓形雕花 → 羊脂白玉 → 青花鼓形）──
        btnPieceSkin.setOnClickListener {
            currentPieceSkin = (currentPieceSkin + 1) % 6
            chessBoard.setPieceSkin(currentPieceSkin)
            val label = "皮肤:${skinLabel()}"
            btnPieceSkin.text = label
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            saveAllSettings()
        }

        // ── 解惑按钮：跳转独立进程沙盒（不占用主棋盘/主引擎、连主线程都分开）──
        btnAnalyze.setOnClickListener {
            if (chessGame.isEngineThinking() || chessGame.isSetupMode) return@setOnClickListener

            // 只传一个 FEN 字符串进独立进程沙盒，主界面这边只剩极轻的 startActivity
            val fen = chessGame.board.toFen()
            btnAnalyze.text = "解惑:开"
            chessBoard.clearSelection()

            try {
                val it = android.content.Intent(this, AnalysisSandboxActivity::class.java)
                it.putExtra(AnalysisSandboxActivity.EXTRA_FEN, fen)
                it.putExtra(AnalysisSandboxActivity.EXTRA_FLIPPED, chessGame.boardFlipped)
                // 即使沙盒进程再卡/再崩，也绝不冻住主界面
                startActivityForResult(it, REQUEST_SANDBOX)
            } catch (e: Exception) {
                btnAnalyze.text = "解惑:关"
                Toast.makeText(this, "沙盒启动失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // ── ★ 恒定菜单栏「信息」按钮：看本局来龙去脉（红黑双方/胜负/日期/阶段）──
        btnGameInfo.setOnClickListener { showGameInfoDialog() }

        // ── ★ 挑战按钮：进入独立进程挑战模式（选等级+执子颜色，与引擎对战）──
        btnChallenge.setOnClickListener {
            // ★ 挑战里现在有两件事：关卡挑战（和引擎下）／双人对战（同 WiFi 和朋友下）
            android.app.AlertDialog.Builder(this)
                .setTitle("挑战")
                .setItems(arrayOf("关卡挑战（对引擎）",
                "双人对战（同 WiFi / 热点）  ·追溯码 " + com.pikafish.chess.duel.DeviceCode.of(this).take(6)
            )) { _, which ->
                    try {
                        val target = if (which == 0)
                            com.pikafish.chess.ui.ChallengeActivity::class.java
                        else com.pikafish.chess.duel.DuelUiActivity::class.java
                        startActivity(android.content.Intent(this, target))
                    } catch (e: Exception) {
                        Toast.makeText(this, "启动失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        }

        // ── 应用设置 ──
        btnApplySettings.setOnClickListener {
            val hash = etHash.text.toString().toIntOrNull()
            val threads = etThreads.text.toString().toIntOrNull()
            if (hash != null || threads != null) {
                chessGame.updateEngineSettings(hash = hash, threads = threads)
                updateSettingsUI()
                updateEngineInfo()
                saveAllSettings()
                Toast.makeText(this, "设置已应用", Toast.LENGTH_SHORT).show()
            }
        }

        // ── 时间显示开关 ──
        btnTimeToggle.setOnClickListener {
            showTimeOnBoard = !showTimeOnBoard
            btnTimeToggle.text = if (showTimeOnBoard) "时间:开" else "时间:关"
            chessBoard.setShowTime(showTimeOnBoard)
            if (showTimeOnBoard) {
                timeUpdateHandler.post(timeUpdateRunnable)
            } else {
                timeUpdateHandler.removeCallbacks(timeUpdateRunnable)
            }
            saveAllSettings()
        }

        // ── ★★ 手动恢复引擎（卡死自愈）：一键脱困 ──
        btnEngineRecover.setOnClickListener {
            Toast.makeText(this, "正在恢复引擎并清理缓存…", Toast.LENGTH_SHORT).show()
            btnEngineRecover.isEnabled = false
            playHandler.postDelayed({ btnEngineRecover.isEnabled = true }, 1500)
            try {
                // ★★ 「恢复」以【当前选定引擎】为准：先把「采用」那台重新钉一遍（同步身份
                //   与路径），再掀桌子复位并重启——恢复后跑的就是现在选定的这台，
                //   绝不沿用上一台的残影。此前若在列表里换了引擎，这里一并对齐。
                try {
                    UserEngineStore.setActive(applicationContext, UserEngineStore.activeId(applicationContext))
                } catch (_: Exception) {}
                chessGame.manualRecoverEngine()
                // ★ 一并做"清缓存/收垃圾"：模仿用户手动清数据的手感——把长时间对局(idle时间长了会卡)的
                //   应用级缓存与临时数据清掉，让后续对局恢复流畅，免去用户手动去系统里清数据的生硬操作。
                clearAppCaches()
            } catch (e: Exception) {
                Log.e(TAG, "手动恢复引擎异常: ${e.message}", e)
            }
            // ★★★ 恢复后弹"引擎特征"气泡：无论内置还是外置，据实报出引擎内部自报的真实身份
            //   （名称/作者/来源/构建日期/体积）。重启握手是异步的，稍候再读，取到真身份为准。
            playHandler.postDelayed({
                try {
                    updateEngineInfo()
                    applyEngineHealthText()
                    showEngineFeatureBubbleWhenReady()
                } catch (_: Exception) {}
            }, 1800)
        }

        // ── 棋谱播放 ──
        btnGuessMove.setOnClickListener {
            if (chessGame.totalSteps <= 0) {
                Toast.makeText(this, "还没有棋谱可以播放", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (chessGame.isAtLatest && !chessGame.isViewingHistory()) {
                // 已经在最后一步，回到开头再播
                chessGame.goToStart()
                updateStepInfo()
            }
            isPlaying = !isPlaying
            if (isPlaying) {
                btnGuessMove.text = "暂停"
                val interval = (1000f / playSpeeds[playSpeedIndex]).toLong()
                playHandler.postDelayed(playRunnable, interval)
            } else {
                btnGuessMove.text = "播放"
                playHandler.removeCallbacks(playRunnable)
            }
        }

        // ── 播放倍速切换 ──
        btnPlaySpeed.setOnClickListener {
            playSpeedIndex = (playSpeedIndex + 1) % playSpeeds.size
            btnPlaySpeed.text = "${playSpeeds[playSpeedIndex]}x"
            Toast.makeText(this, "播放速度：${playSpeeds[playSpeedIndex]}倍速", Toast.LENGTH_SHORT).show()
        }

        // ── ★ 分析模式开关（原“旁白”按钮改造而来）──
        // 点开“分析”：强制红方人脑 + 黑方人脑，用对弈引擎持续分析当前局面（只分析不走棋）。
        btnQipuCommentary.setOnClickListener {
            toggleAnalysis()
        }

        // ── 旁白表：一坨一坨列出本局所有旁白（含支节标记），供用户自找对应 ──
        btnQipuCommentaryList.setOnClickListener {
            showCommentaryListDialog()
        }

        // ── 背谱：直接进入背谱沙盘（100% 确定性，不弹窗绕弯；谱源=当前局面已载主线）──
        btnBookMemorize.setOnClickListener {
            openBookSandbox()
        }

        // ── 红方人脑/AI 切换 ──
        btnRedSide.setOnClickListener {
            chessGame.redIsHuman = !chessGame.redIsHuman
            updateSideButtons()
            chessBoard.clearHighlights()
            chessGame.onPlayerTypeChanged()
            saveGameState()
            saveAllSettings()
        }

        // ── 黑方人脑/AI 切换 ──
        btnBlackSide.setOnClickListener {
            chessGame.blackIsHuman = !chessGame.blackIsHuman
            updateSideButtons()
            chessBoard.clearHighlights()
            chessGame.onPlayerTypeChanged()
            saveGameState()
            saveAllSettings()
        }

        // ★★★ 思考细节贴纸锚定到"底栏正上方"（不同机型不再各自乱跑）★★★
        //   以前写死 marginBottom=280dp：屏小、菜单高、或用户把菜单往下挪，贴纸就被压在
        //   工具条底下。改为按实测高度锚定：贴纸底边 = 底栏高度 + 菜单高度，
        //   于是它永远坐在这两块的上面，既不遮挡、也不悬空。
        run {
            val thumb = findViewById<android.view.View>(R.id.frameSetupThumb)
            val nav = findViewById<android.view.View>(R.id.layoutBottomNav)
            val controls = findViewById<android.view.View>(R.id.layoutNormalControls)
            if (thumb != null && nav != null && controls != null) {
                val sync = {
                    val target = nav.height + controls.height
                    val lp = thumb.layoutParams
                    if (lp is android.widget.FrameLayout.LayoutParams && target > 0 && lp.bottomMargin != target) {
                        lp.bottomMargin = target
                        thumb.layoutParams = lp
                    }
                }
                nav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
                controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
                thumb.post(sync)
            }
        }

        // ── 新对局 ──
        btnNewGame.setOnClickListener {
            val doNewGame: () -> Unit = {
                if (bookMemorizer.active) stopBookMemorize()   // 换局面即退出背谱
                chessBoard.clearSelection()
                chessBoard.setFlipped(chessGame.boardFlipped)
                chessBoard.clearHighlights()
                chessBoard.viewingHistory = false
                chessBoard.setOverlayMode(com.pikafish.chess.ui.ChessBoardView.OverlayMode.OFF)
                layoutExplanationList.removeAllViews()
                layoutExplanationList.addView(tvExplanation)
                tvExplanation.text = "走棋后这里会显示棋谱…"
                chessGame.newGame()
                saveGameState()
            }
            // ★ 半局进行中时先弹窗：取消 / 清空 / 保存到宝库 / 详细保存（2×2 四宫格，界面匀称）
            if (chessGame.getMoveCount() > 0) {
                showNewGameDialog(doNewGame)
            } else {
                doNewGame()
            }
        }

        // ── ★ 回退/前进导航 ★ ──
        btnGoBack.setOnClickListener {
            stopPlayback()
            chessBoard.clearSelection()  // ★ 导航时清除选子状态
            chessGame.goBack()
            restoreHighlightsFromHistory()
            refreshExplanationDisplay()
            updateStepInfo()
            showCurrentQipuComment()
            saveGameState()
        }

        btnGoForward.setOnClickListener {
            stopPlayback()
            chessBoard.clearSelection()
            chessGame.goForward()
            restoreHighlightsFromHistory()
            refreshExplanationDisplay()
            updateStepInfo()
            showCurrentQipuComment()
            saveGameState()
        }

        // ── ★「隐/显」圆圈：点一下收起整块主菜单（腾出棋盘/思考区），再点一下全部弹开 ──
        btnToggleMenu.setOnClickListener { toggleMenuCollapse() }

        btnGoStart.setOnClickListener {
            stopPlayback()
            chessBoard.clearSelection()
            chessGame.goToStart()
            restoreHighlightsFromHistory()
            refreshExplanationDisplay()
            updateStepInfo()
            showCurrentQipuComment()
            saveGameState()
        }

        btnGoEnd.setOnClickListener {
            stopPlayback()
            chessBoard.clearSelection()
            chessGame.goToEnd()
            restoreHighlightsFromHistory()
            refreshExplanationDisplay()
            updateStepInfo()
            showCurrentQipuComment()
            saveGameState()
        }

        // ── ★ 回合/步数按钮：点开「本步操作」——写注释 / 删除招法（本步及之后全删）/ 切换变着 ──
        tvStepInfo.setOnClickListener { showStepMenuDialog() }

        // ── 翻转 ──
        btnFlip.setOnClickListener {
            chessGame.flipBoard()
            chessBoard.setFlipped(chessGame.boardFlipped)
        }

        // ── ★ 导出棋谱：合并原 PGN/FEN/TXT 三个导出，点开选格式 ──
        btnExportPgn.setOnClickListener { showExportChooserDialog() }
        btnExportFen.setOnClickListener { showExportChooserDialog() }
        btnExportTxt.setOnClickListener { showExportChooserDialog() }

        // ── ★ 导入棋谱：合并原 PGN/FEN/TXT 三个导入，点开自动识别格式 ──
        btnImportPgn.setOnClickListener { showImportChooserDialog() }
        btnImportFen.setOnClickListener { showImportChooserDialog() }
        btnImportTxt.setOnClickListener { showImportChooserDialog() }

        // ★ 合并规则：界面上只保留「导入」「导出」两个入口，其余隐藏
        btnExportPgn.text = "导出"
        btnImportPgn.text = "导入"
        btnImportFen.visibility = View.GONE
        btnImportTxt.visibility = View.GONE
        btnExportFen.visibility = View.GONE
        btnExportTxt.visibility = View.GONE

        // ── ★ 复盘评估：夹在 导出 与 导入 之间（同排三个按钮，不增行）──
        btnReview.setOnClickListener { showReviewDialog() }

        // ═══ ★ 练开局：仿挑战模式，限定回合数练开局（人执一方下满即锁盘）═══
        btnPracticeOpening.setOnClickListener {
            ritualCountedThisGame = false
            showPracticeStartDialog()
        }

        // ★ 仪式感入口：主界面一启动就直接弹「练开局」设置框（不再需要用户自己找按钮）
        if (intent?.getBooleanExtra("ritual_open_practice", false) == true) {
            runOnUiThread {
                // 主界面默认就在「对弈」页；直接弹「练开局」设置框（选步数/先后手）
                btnPracticeOpening.performClick()
            }
        }
        btnPracticeUndo.setOnClickListener { onPracticeUndo() }
        btnPracticeReview.setOnClickListener { onPracticeReviewTap() }
        btnPracticeExit.setOnClickListener { exitPractice() }

        // ── ★ 棋谱列表 ──
        btnGameRecords.setOnClickListener {
            showGameRecordList()
        }

        // ── 自定义摆子 ──
        btnSetupMode.setOnClickListener {
            chessGame.enterSetupMode()
            selectedPieceType = null
            selectedPieceColor = null
            updateSetupPieceSelection()
        }

        // ── 摆子模式返回 ──
        btnSetupReturn.setOnClickListener {
            chessGame.cancelSetup()
            chessBoard.clearHighlights()
            updateStatus()
        }

        // ── 横排Tab互斥切换 ──
        setupTabNavigation()

        // ★ 棋谱宝库入口（参数面板 + 更多菜单）——用try-catch保护防止影响主界面
        try { setupQipu() } catch (e: Exception) { Log.e(TAG, "setupQipu: ${e.message}", e) }

        // ── 视觉Tab：盘内讲解（三档循环：关→开→点动→关）──
        btnOverlayCommentary.setOnClickListener {
            overlayCommentaryMode = (overlayCommentaryMode + 1) % 3
            val modeLabel = when (overlayCommentaryMode) {
                1 -> "盘内讲解:开"
                2 -> "盘内讲解:点动"
                else -> "盘内讲解:关"
            }
            btnOverlayCommentary.text = modeLabel
            val mode = when (overlayCommentaryMode) {
                1 -> com.pikafish.chess.ui.ChessBoardView.OverlayMode.ON
                2 -> com.pikafish.chess.ui.ChessBoardView.OverlayMode.TAP
                else -> com.pikafish.chess.ui.ChessBoardView.OverlayMode.OFF
            }
            chessBoard.setOverlayMode(mode)
            if (overlayCommentaryMode == 1 || overlayCommentaryMode == 2) {
                chessBoard.clearOverlayLines()
                val visible = chessGame.getVisibleExplanations()
                val last3 = visible.takeLast(3)
                for (entry in last3) {
                    val icon = if (entry.isHuman) "🧑" else "🤖"
                    chessBoard.appendOverlayLine("${icon}${entry.color} ${entry.moveNotation}")
                }
                if (last3.isEmpty()) {
                    chessBoard.appendOverlayLine("走棋后显示棋谱")
                }
            }
            saveAllSettings()
            val toastLabel = when (overlayCommentaryMode) {
                1 -> "盘内讲解已开启（始终显示）"
                2 -> "盘内讲解已设为点动模式（点击楚河汉界处「点动模式」查看）"
                else -> "盘内讲解已关闭"
            }
            Toast.makeText(this, toastLabel, Toast.LENGTH_SHORT).show()
        }

        // ── 视觉Tab：棋盘/棋子大小 ── 点击弹「横屏滑条」弹窗，平时不再内嵌输入框占面板空间。
        //   弹窗内一根横向 SeekBar（划动即所见即所得地改大小并保存）；「确认」收起、「取消」恢复原值（v3.7 摘下）
        btnBoardScale.setOnClickListener { showScaleDialog("棋盘", true) }
        btnPieceScale.setOnClickListener { showScaleDialog("棋子", false) }

        // ── 视觉Tab：大而美 ── 一键把棋盘棋子调到「最大且不爆屏」并锁定边界：
        //   按当前棋子尺寸倒推 9列/10行+两侧圆半径 的总跨度取屏幕能放下的最大值；
        //   此后点「显/隐」棋盘大小永远不变（显示区吸收变化），底栏纹丝不动，重启后保持。
        btnDaMei.setOnClickListener { applyDaMei() }

        // ── 视觉Tab：棋盘坐标 / 组件坐标 ── 弹「透明浮条」纵坐标微调（收拾顽固机型拉长/歊住的残留问题）
        btnBoardCoord.setOnClickListener { toggleCoordPopup(0) }
        btnMenuCoord.setOnClickListener { toggleCoordPopup(1) }

        // ── ★ 外置引擎 ── 点：选一个引擎 ZIP（内含引擎二进制＋NNUE 权重）装载，全局替换内置引擎；
        //   长按：卸回内置皮卡鱼。装载/卸载都即时重启算棋通道，顶栏与人话解说随之改名。
        btnExternalEngine.setOnClickListener { pickExternalEngineZip() }
        btnExternalEngine.setOnLongClickListener {
            confirmUninstallExternalEngine()
            true
        }

        // ── ★ 引擎列表 ── 总览全部引擎：勾「采用」全局生效；勾「红/黑」两台不同引擎做
        //   全自动 PK 测试；并展示彼此交手成绩。长按引擎名可删除外置引擎。
        btnEngineList.setOnClickListener { showEngineListDialog() }

        // ── 视觉Tab：棋桌风格 ── 只选样式、固定比例随棋盘缩放（棋盘→棋子→棋桌）
        btnTableStyle.setOnClickListener { showTableStyleDialog() }

        // ── 视觉Tab：解说字号（横幅解说区字体，sp 直接可调）──
        sbCommentFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    commentFontSp = progress.coerceAtLeast(8).toFloat()
                    applyCommentFont()
                    tvCommentFontVal.text = "${commentFontSp.toInt()}sp"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { saveAllSettings() }
        })

        // ── 视觉Tab：点动字号（盘内点动/讲解浮层字号倍率）──
        sbTapFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tapFontScale = 0.6f + progress * (1.8f - 0.6f) / 120f
                    chessBoard.setTapFontScale(tapFontScale)
                    tvTapFontVal.text = "${(tapFontScale * 100).toInt()}%"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { saveAllSettings() }
        })

        // ── 视觉Tab：棋线颜色下拉菜单（丰富12色）──
        val colorNames = lineColors.map { it.first }
        val spinnerLineColorAdapter = spinnerAdapter(colorNames)
        spinnerLineColorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLineColor.adapter = spinnerLineColorAdapter
        spinnerLineColor.setSelection(currentLineColorIndex)

        spinnerLineColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUI) return
                currentLineColorIndex = position
                val colorHex = lineColors[position].second
                val color = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#3A3020") }
                chessBoard.setLineColor(color)
                saveAllSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ── 视觉Tab：棋线粗细下拉菜单（丰富6档）──
        val thicknessNames = lineThicknesses.map { it.first }
        val spinnerThicknessAdapter = spinnerAdapter(thicknessNames)
        spinnerThicknessAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLineThickness.adapter = spinnerThicknessAdapter
        spinnerLineThickness.setSelection(currentLineThicknessIndex)

        spinnerLineThickness.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUI) return
                currentLineThicknessIndex = position
                chessBoard.setLineThickness(lineThicknesses[position].second)
                saveAllSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ── 视觉Tab：菜单栏底色下拉菜单（木色棋盘系20色，覆盖全部面板区域）──
        val menuBgNames = menuBgColors.map { it.first }
        val spinnerMenuBgAdapter = spinnerAdapter(menuBgNames)
        spinnerMenuBgAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMenuBgColor.adapter = spinnerMenuBgAdapter
        spinnerMenuBgColor.setSelection(currentMenuBgIndex)

        spinnerMenuBgColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUI) return
                currentMenuBgIndex = position
                val color = try { Color.parseColor(menuBgColors[position].second) } catch (_: Exception) { Color.parseColor("#F4E9D8") }
                applyMenuBgColor(color)
                saveAllSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ── 视觉Tab：菜单栏按钮色下拉菜单（丰富8色）──
        val menuBtnNames = menuBtnColors.map { it.first }
        val spinnerMenuBtnAdapter = spinnerAdapter(menuBtnNames)
        spinnerMenuBtnAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMenuBtnColor.adapter = spinnerMenuBtnAdapter
        spinnerMenuBtnColor.setSelection(currentMenuBtnIndex)

        spinnerMenuBtnColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUI) return
                currentMenuBtnIndex = position
                val color = try { Color.parseColor(menuBtnColors[position].second) } catch (_: Exception) { Color.parseColor("#FFB300") }
                updateAllMenuButtonColors(color)
                saveAllSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ── 视觉Tab：★全局底色★（点击弹出色谱调色板，自由点选任意颜色；作用于思考区/主界面文字衬托，全局共享）──
        findViewById<Button>(R.id.btnGlobalBackdrop).setOnClickListener {
            com.pikafish.chess.ui.GlobalBackdrop.showPanel(
                this,
                onPickImage = { backdropImageLauncher.launch(arrayOf("image/*")) },
                onPickZip = { backdropZipLauncher.launch(arrayOf("*/*")) },
                onChanged = { applyGlobalBackdrop() }
            )
        }
        // ── 视觉Tab：★个性化★（导入外置Zip皮肤包，跨包混配红/黑棋子与洗净背景）──
        findViewById<Button>(R.id.btnPersonalize).setOnClickListener {
            startActivity(android.content.Intent(this, com.pikafish.chess.ui.PersonalizeActivity::class.java))
        }
        // ── 更多Tab：★辎重包★（接入/共享辎重包：操作包与辎重包分离、跨设备直读的统一控制台）──
        findViewById<Button>(R.id.btnMatchRecommend).setOnClickListener {
            startActivity(android.content.Intent(this, com.pikafish.chess.qipu.GuideActivity::class.java))
        }
        findViewById<Button>(R.id.btnHeavyPack).setOnClickListener {
            startActivity(android.content.Intent(this, HeavyShareActivity::class.java))
        }
        // 启动时把全局底色套到思考区（若之前选过）
        applyGlobalBackdrop()
        // 个性化：启动时若有方案即套用
        try { com.pikafish.chess.ui.SkinManager.applyTo(this, chessBoard) } catch (_: Exception) {}

        setupPieceButtons()
        setupSetupActionButtons()

        // ── 棋盘回调 ──
        chessBoard.onMoveMade = { move ->
            if (whatIfMode) {
                // ★ 解惑模式：不实际走棋，分析选的这步棋
                chessGame.jiedaoAnalysis(move.toUci())
                chessBoard.clearSelection()
                false
            } else if (bookMemorizer.active) {
                // ★ 背谱模式：谱面约束 + 自动应子
                handleBookMemorizeMove(move)
            } else {
                // ★ 检测是否在查看历史时走棋——如果是，将创建变着
                val wasViewingHistory = chessGame.isViewingHistory()
                val result = chessGame.makeMove(move)
                if (result) {
                    // ★ 从实际走棋历史重建光标，确保只显示真实走法
                    restoreHighlightsFromHistory()
                    updateStepInfo()
                    saveGameState()

                    if (wasViewingHistory) {
                        Toast.makeText(this, "已创建变着，原主线已保存为变着分支", Toast.LENGTH_SHORT).show()
                    }
                }
                result
            }
        }
    }

    // ── ★ 续盘：保存/恢复棋局状态（完整历史）★ ──

    // ════════════════════════════════════════════════════════════
    // ★ 背谱：谱面约束选择机制（不用引擎）
    // 单方背谱：另一方由谱自动应子，多分支遍历穷尽轮流；谱外着法力不动。
    // ════════════════════════════════════════════════════════════

    /** 背谱模式下收到用户落子：先校验是否归用户走、是否在谱上，再走棋并触发自动应子。 */
    private fun handleBookMemorizeMove(move: com.pikafish.chess.model.Move): Boolean {
        val redTurn = move.piece.color == PieceColor.RED
        val fen = chessGame.board.toFen()
        val uci = move.toUci()

        // 1) 归不归用户走？背单方谱时另一侧由谱自动应，不许手点。
        if (!bookMemorizer.isUserMove(redTurn)) {
            Toast.makeText(this, "背谱：这一步由谱面自动应子，请等它自动走完", Toast.LENGTH_SHORT).show()
            chessBoard.clearSelection()
            return false
        }
        // 2) 谱上有没这一步？没有就点不动。
        if (!bookMemorizer.validate(fen, uci)) {
            val allowed = bookMemorizer.allowedFor(fen)
            val src = bookMemorizer.sourceDesc()
            Toast.makeText(
                this,
                if (allowed.isEmpty()) "背谱：此局面谱上无着法（已出谱，源：${src.ifBlank { "无" }}）"
                else "背谱：这步不在谱上，请走谱面给出的着法（源：${src.ifBlank { "无" }}）",
                Toast.LENGTH_SHORT
            ).show()
            chessBoard.clearSelection()
            return false
        }

        val wasViewingHistory = chessGame.isViewingHistory()
        val result = chessGame.makeMove(move)
        if (result) {
            restoreHighlightsFromHistory()
            updateStepInfo()
            saveGameState()
            if (wasViewingHistory) {
                Toast.makeText(this, "已创建变着，原主线已保存为变着分支", Toast.LENGTH_SHORT).show()
            }
            // 3) 轮到谱方走就自动应子（多分支弹气泡）
            scheduleBookAuto()
        }
        return result
    }

    /** 把当前已回放棋谱（CBL 宝库载入等）的**整棵棋谱树**设为背谱谱源。 */
    private fun buildBookLineFromCurrent() {
        // ★ 整棵树入谱：主线 + 全部变着（含嵌套支线）。不再只取人脑走过的那一条主线，
        //   否则变招处"谱上真有这一步却说没谱"，背到支线也背不下去（一根筋）。
        val lines = try { chessGame.bookLineAllLines() } catch (e: Exception) { emptyList() }
        val moves = lines.firstOrNull() ?: emptyList()
        if (moves.isEmpty()) return
        val startFen = try { chessGame.bookLineStartFen() } catch (e: Exception) { chessGame.board.toFen() }
        val name = try {
            if (currentVaultGameIndex in currentVaultGames.indices) currentVaultGames[currentVaultGameIndex].title()
            else "当前棋谱"
        } catch (e: Exception) { "当前棋谱" }
        // ★ 宝库局原文件的支线再兜一层（PGN 往返万一有损，这里按原始解析补齐；防串谱做前缀校验）
        val extra = currentVaultVariationsFor(moves).filter { it.isNotEmpty() }
        bookMemorizer.loadLines(name, startFen, lines + extra)
    }

    /** 当前宝库局的变着支线（其主线须与 moves 前缀吻合才认，防串谱）。 */
    private fun currentVaultVariationsFor(moves: List<String>): List<List<String>> = try {
        val g = if (currentVaultGameIndex in currentVaultGames.indices) currentVaultGames[currentVaultGameIndex] else null
        val full = g?.cblGame?.movesUci
        if (g != null && full != null && full.size >= moves.size && full.subList(0, moves.size) == moves)
            g.cblGame.variationsUci else emptyList()
    } catch (e: Exception) { emptyList() }

    private fun scheduleBookAuto() {
        uiHandler.postDelayed({ runBookAutoChain() }, 300L)
    }

    /** 谱面自动应子链：若轮谱方走，据谱选一着（分支轮换）走掉，多分支弹气泡。 */
    private fun runBookAutoChain() {
        if (!bookMemorizer.active) return
        if (chessGame.gameOver || chessGame.isViewingHistory()) return
        val redTurn = chessGame.board.currentPlayer == PieceColor.RED
        if (!bookMemorizer.isAutoSide(redTurn)) return   // 该用户走
        val fen = chessGame.board.toFen()
        // ★ 先弹气泡（纯预览），再落账选步 —— 气泡报的分支号与实际走的那一号永远一致
        //   （修掉旧版 autoReply 与 branchInfo 双重递增导致的报号错乱/跳支）。
        bookMemorizer.branchInfo(fen)?.let { showBranchBubble(it.first, it.second, it.third) }
        val uci = bookMemorizer.autoReply(fen)
        if (uci == null) {
            Toast.makeText(this, "背谱：此局面谱上无自动应着，如已到底可退出背谱或另选分支", Toast.LENGTH_SHORT).show()
            return
        }
        val applied = chessGame.makeExternalMove(uci)
        if (!applied) return
        restoreHighlightsFromHistory()
        updateStepInfo()
        saveGameState()
        // 已自动走掉一着，接下来由用户接手（单方背谱总是交替）
    }

    private fun showBranchBubble(total: Int, index: Int, from: String) {
        bubbleDismiss?.let { bubbleBookBranch.removeCallbacks(it) }
        bubbleBookBranch.text = "『${from.ifBlank { "谱" }}』此局面共 $total 个变招\n本回合走第 ${index + 1} 号变化"
        bubbleBookBranch.visibility = View.VISIBLE
        bubbleBookBranch.animate().alpha(1f).setDuration(180).start()
        bubbleDismiss = Runnable { fadeBubbleOut() }
        // 弹窗自现自消：2.6 秒后淡出；也可点击立即关掉（见 initViews）
        bubbleBookBranch.postDelayed(bubbleDismiss!!, 2600L)
    }

    private fun fadeBubbleOut() {
        bubbleBookBranch.animate().alpha(0f).setDuration(220).withEndAction {
            if (bubbleBookBranch.alpha <= 0.01f) bubbleBookBranch.visibility = View.GONE
        }.start()
    }

    private fun hideBranchBubble() {
        bubbleDismiss?.let { bubbleBookBranch.removeCallbacks(it) }
        bubbleBookBranch.animate().cancel()
        bubbleBookBranch.alpha = 0f
        bubbleBookBranch.visibility = View.GONE
    }

    /** 开始背谱：临时把双方设为人脑（引擎不自动走），据谱自动应子。 */
    private fun startBookMemorize(mode: com.pikafish.chess.engine.BookMemorizer.Mode) {
        bookPrevRedHuman = chessGame.redIsHuman
        bookPrevBlackHuman = chessGame.blackIsHuman
        chessGame.redIsHuman = true
        chessGame.blackIsHuman = true
        try { chessGame.onPlayerTypeChanged() } catch (e: Exception) { Log.e(TAG, "背谱暂停引擎", e) }
        hideBranchBubble()
        buildBookLineFromCurrent()
        bookMemorizer.start(mode)
        updateSideButtons()
        saveAllSettings()
        saveGameState()
        scheduleBookAuto()
        val m = when (mode) {
            com.pikafish.chess.engine.BookMemorizer.Mode.RED -> "背红方谱"
            com.pikafish.chess.engine.BookMemorizer.Mode.BLACK -> "背黑方谱"
            com.pikafish.chess.engine.BookMemorizer.Mode.BOTH -> "背双方谱"
        }
        Toast.makeText(this, "已进入$m（源：${bookMemorizer.sourceDesc().ifBlank { "无" } }）", Toast.LENGTH_SHORT).show()
    }

    /** 结束背谱：还原双方人脑/引擎设置。 */
    private fun stopBookMemorize() {
        bookMemorizer.stop()
        hideBranchBubble()
        chessGame.redIsHuman = bookPrevRedHuman
        chessGame.blackIsHuman = bookPrevBlackHuman
        updateSideButtons()
        saveAllSettings()
        saveGameState()
        Toast.makeText(this, "已退出背谱", Toast.LENGTH_SHORT).show()
    }

    // ════════════════════════════════════════════════════════════
    // ★ 背谱设置面板：选库/选边（背红方谱 / 背黑方谱 / 背双方谱）→ 开始/退出
    // ════════════════════════════════════════════════════════════
    private fun showBookMemorizePanel() {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 20, 22, 14) }
        val title = TextView(this).apply {
            text = "🧠 背谱（选谱·选方·开始）"
            setTextColor(Color.parseColor("#E8D8A8")); textSize = 16f
            setPadding(4, 0, 4, 6)
        }
        c.addView(title)

        // 谱源状态
        val src = TextView(this).apply {
            text = "谱源：${bookMemorizer.sourceDesc().ifBlank { "当前未载棋谱/外置库" }}"
            setTextColor(Color.parseColor("#A8D8C8")); textSize = 13f
            setPadding(4, 2, 4, 12)
        }
        c.addView(src)

        val note = TextView(this).apply {
            text = "说明：进入后只能走谱上有的着法，谱外点不动；\n背红/黑单方时另一边由谱自动应子，到变招会弹气泡提示走第几号变化。"
            setTextColor(Color.parseColor("#B8B0A0")); textSize = 12f
            setLineSpacing(3f, 1.15f)
            setPadding(4, 0, 4, 12)
        }
        c.addView(note)

        // 选边
        val desc = TextView(this).apply {
            text = "选择背谱方式：" + if (bookMemorizer.active) "（进行中，可重新选）" else ""
            setTextColor(Color.parseColor("#E8D8A8")); textSize = 13f
            setPadding(4, 2, 4, 6)
        }
        c.addView(desc)

        val dialog = android.app.Dialog(this)

        fun modeButton(label: String, m: com.pikafish.chess.engine.BookMemorizer.Mode, active: Boolean): Button {
            return Button(this).apply {
                text = (if (active) "▶ " else "") + label
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (46 * resources.displayMetrics.density).toInt()
                )
                setOnClickListener {
                    startBookMemorize(m); dialog.dismiss()
                }
            }
        }
        val modeSel = bookMemorizer.mode
        c.addView(modeButton("背红方谱", com.pikafish.chess.engine.BookMemorizer.Mode.RED, modeSel == com.pikafish.chess.engine.BookMemorizer.Mode.RED))
        c.addView(modeButton("背黑方谱", com.pikafish.chess.engine.BookMemorizer.Mode.BLACK, modeSel == com.pikafish.chess.engine.BookMemorizer.Mode.BLACK))
        c.addView(modeButton("背双方谱", com.pikafish.chess.engine.BookMemorizer.Mode.BOTH, modeSel == com.pikafish.chess.engine.BookMemorizer.Mode.BOTH))

        // 退出背谱
        if (bookMemorizer.active) {
            val btnStop = Button(this).apply {
                text = "⏹ 退出背谱"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (46 * resources.displayMetrics.density).toInt())
                setOnClickListener { stopBookMemorize(); dialog.dismiss() }
            }
            c.addView(btnStop)
        }

        val btnClose = Button(this).apply {
            text = "关闭"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (42 * resources.displayMetrics.density).toInt())
            setOnClickListener { dialog.dismiss() }
        }
        c.addView(btnClose)

        dialog.setContentView(c)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            dialog.window?.let { w ->
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#7A2A2218")))
                val dm = resources.displayMetrics
                w.setLayout((dm.widthPixels * 0.9).toInt(), (dm.heightPixels * 0.72).toInt())
            }
        }
        dialog.show()
    }

    // ════════════════════════════════════════════════════════════
    // ★ 棋盘外层“总背景色”：窄竖条选色（不占额外行高）
    // ════════════════════════════════════════════════════════════
    private var isUpdatingBgUi = false

    /** 亮字 Spinner 适配器：展开项文字一律纯白（与菜单按钮同色的底色上清晰可读）。 */
    private fun spinnerAdapter(names: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? android.widget.TextView)?.setTextColor(0xFFFFFFFF.toInt())
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? android.widget.TextView)?.setTextColor(0xFFFFFFFF.toInt())
                return v
            }
        }

    /** 把全局底色套到思考细节(选时)区 + 棋盘外圈托底 + 主界面文字衬托色。
     *  ★ 棋盘视图自身底色也一并设为全局底色：棋盘圆角外那一圈不再露白，
     *    整片棋盘区（含外圈）都是用户选的全局底色，下棋/翻页不再串色。 */
    private fun applyGlobalBackdrop() {
        try {
            val color = com.pikafish.chess.ui.GlobalBackdrop.currentColor(this)
            // ★ 全局背景：纯色档给纯色，图片档给铺满的背景图（一个入口通吃两套方案）
            val bg = com.pikafish.chess.ui.GlobalBackdrop.backdropDrawable(this)
            val fg = com.pikafish.chess.ui.GlobalBackdrop.foreground(this, color)
            // ★ 思考细节(tvThinking)：不再用全局底色当"深色涂层"压棋盘，
            //   固定浅色可读块，作为主菜单上方空缺区里的正常信息条（用户要求）。
            tvThinking.setBackgroundColor(0xF2FFF3C4.toInt())
            tvThinking.setTextColor(0xFF5D4037.toInt())
            // ★ 主界面内容托底（棋盘外层 + 根 view）
            findViewById<View>(R.id.frameBoardOuter)?.background = bg
            // ★ 棋盘视图自身背景 = 全局底色/背景图：圆角棋盘外那一圈与全局一致，绝不露白
            if (::chessBoard.isInitialized) {
                chessBoard.background = bg
                chessBoard.invalidate()   // 图/色一改，楚河汉界上的时间字色即时刷新
            }
            // ★ 根布局同色兜底：任何缝隙都不露别的底色（图片档下作为图未及时铺到时的底衬）
            findViewById<View>(R.id.rootMain)?.setBackgroundColor(color)
            // ★ 全屏贴纸方案：状态栏/底栏/主菜单保持自身面板底色，不再被涂成棋盘同色
            //   ——贴纸与棋盘清晰分层：棋盘占满整屏、贴纸浮在棋盘之上，层次分明。
        } catch (_: Exception) { }
    }

    private fun dp4(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 保存完整棋局状态到JSON文件（跨session持久化全部走棋记录） */
    private fun saveGameStateFull() {
        try {
            val json = chessGame.getFullStateJson()
            prefs.edit()
                .putString(KEY_FULL_STATE_JSON, json.toString())
                .putInt(KEY_SAVED_STEP, chessGame.currentStep)
                .putBoolean(KEY_RED_HUMAN, chessGame.redIsHuman)
                .putBoolean(KEY_BLACK_HUMAN, chessGame.blackIsHuman)
                .putBoolean(KEY_CLOUD_BOOK, chessGame.cloudBookEnabled)  // ★ 云库
                .apply()
            // ★★ 实时预制存档：每走一步就把当前对局也落盘到宝库的“预制位”。
            //   就算用户点“新局→保存”那一瞬间出意外，上一局也早就在宝库文件里了。
            preDraftToVault()
        } catch (e: Exception) {
            Log.e(TAG, "Save full state failed: ${e.message}")
        }
    }

    /** ★★ 实时预制存档：有棋步就把当前完整对局写到宝库预制位；若局面已清空(moveCount=0)则把预制位一并清掉。 */
    private fun preDraftToVault() {
        try {
            val dl = com.pikafish.chess.qipu.GameJournal.DRAFT_ID
            val json = chessGame.getFullStateJson()
            val snap = json.optJSONArray("boardSnapshots")
            val mc = if (snap != null) snap.length() - 1 else chessGame.getMoveCount()
            if (mc <= 0) {
                com.pikafish.chess.qipu.GameJournal.deleteLog(this, dl)
                return
            }
            com.pikafish.chess.qipu.GameJournal.upsertLog(
                this,
                id = dl,
                title = "当前对局（待保存）",
                source = "当前对局",
                fileName = dl,
                bookIndex = 0, bookName = "", catName = "",
                stateJson = json.toString(),
                movesCount = mc,
                reading = "", note = "", bookmark = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "preDraftToVault: ${e.message}", e)
        }
    }

    /** 保存当前FEN（用于快速恢复） */
    private fun saveGameState() {
        try {
            saveGameStateFull()
        } catch (e: Exception) {
            Log.e(TAG, "Save state failed: ${e.message}")
        }
    }

    /** 尝试恢复完整棋局（含全部走棋记录） */
    private fun tryRestoreGame(): Boolean {
        try {
            val fullStateJson = prefs.getString(KEY_FULL_STATE_JSON, null)
            if (fullStateJson != null) {
                val json = org.json.JSONObject(fullStateJson)
                val snapshots = json.getJSONArray("boardSnapshots")
                if (snapshots.length() <= 1) {
                    // 只有初始状态或为空，清除并返回 false
                    prefs.edit().remove(KEY_FULL_STATE_JSON).remove(KEY_SAVED_STEP).apply()
                    return false
                }

                // ★ 验证快照中的 FEN 是否有效（至少验证最后一步）
                val lastFen = snapshots.getString(snapshots.length() - 1)
                if (lastFen.isBlank() || lastFen.split('/').size < 10) {
                    Log.w(TAG, "tryRestoreGame: last FEN is invalid, clearing saved state. fen=$lastFen")
                    prefs.edit().remove(KEY_FULL_STATE_JSON).remove(KEY_SAVED_STEP).apply()
                    return false
                }

                if (chessGame.loadFullStateJson(json)) {
                    // ★ 恢复后验证棋盘是否有效（检查是否有将/帅）
                    val board = chessGame.board
                    if (board.findKing(com.pikafish.chess.model.PieceColor.RED) == null ||
                        board.findKing(com.pikafish.chess.model.PieceColor.BLACK) == null) {
                        Log.w(TAG, "tryRestoreGame: restored board has no kings, clearing saved state")
                        prefs.edit().remove(KEY_FULL_STATE_JSON).remove(KEY_SAVED_STEP).apply()
                        return false
                    }

                    chessGame.redIsHuman = prefs.getBoolean(KEY_RED_HUMAN, true)
                    chessGame.blackIsHuman = prefs.getBoolean(KEY_BLACK_HUMAN, false)
                    restoreHighlightsFromHistory()
                    chessGame.onBoardChanged?.invoke()
                    updateSideButtons()
                    updateStepInfo()
                    refreshExplanationDisplay()
                    return true
                } else {
                    // loadFullStateJson 失败，清除损坏的存档
                    Log.w(TAG, "tryRestoreGame: loadFullStateJson failed, clearing saved state")
                    prefs.edit().remove(KEY_FULL_STATE_JSON).remove(KEY_SAVED_STEP).apply()
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Restore full state failed: ${e.message}")
            // 清除损坏的存档，防止下次启动仍然失败
            try {
                prefs.edit().remove(KEY_FULL_STATE_JSON).remove(KEY_SAVED_STEP).apply()
            } catch (_: Exception) {}
            return false
        }
    }

    /** 本地文档候选：后台 IO 阶段决定要加载哪一份（ext = 格式, name = 书名, content = 文本内容）。 */
    private data class ExternalCandidate(val ext: String, val name: String, val content: String)

    /** ★ 后台调度「存档收集」：引擎就绪后启动，扫描/读取 HHXQ 绝对路径全部放后台线程（绝不阻塞主线程），
     *   扫到合适的本地存档后切回主线程覆盖恢复。缓存/数据被手动清理后依然有效。 */
    private fun scheduleHHXQAutoLoad() {
        Thread {
            try {
                // ★ 先预热宝库「本地文档」分类缓存（后台扫一次盘，宝库打开直接读缓存，不卡 UI）
                com.pikafish.chess.qipu.QipuLibrary.refreshLocalDocsCache()
            } catch (e: Exception) { /* 预热失败不致命 */ }
            var cand: ExternalCandidate? = null
            try {
                cand = probeHHXQExternal()
            } catch (e: Exception) {
                Log.e(TAG, "scheduleHHXQAutoLoad: ${e.message}", e)
            }
            val c = cand
            runOnUiThread {
                try {
                    if (c != null && applyExternalCandidate(c)) {
                        Toast.makeText(this, "已恢复本地存档（HHXQ 全量收集）", Toast.LENGTH_SHORT).show()
                        chessGame.checkAndTriggerAiMove()
                        logCurrentGameToJournal("主界面")   // ★ 续盘即记入对局日志
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "scheduleHHXQAutoLoad-UI: ${e.message}", e)
                }
            }
        }.start()
    }

    /** ★ 后台线程执行（只做 IO，绝不碰棋盘/UI）：扫描 HHXQ 绝对路径下全部文件并决定要恢复的候选。
     *   顺序：① 实时预制存档 log_vault_draft.json（每走一步都同步到 HHXQ/vault_games）
     *        ② HHXQ 全量收集里最近更新的可对局文档（txt/fen/pgn/json） */
    private fun probeHHXQExternal(): ExternalCandidate? {
        // ① 实时预制存档（完整对局快照）
        val dir = com.pikafish.chess.qipu.GameJournal.externalSnapshotDir()
        val draft = java.io.File(
            dir,
            "log_" + com.pikafish.chess.qipu.GameJournal.DRAFT_ID + ".json"
        )
        if (draft.exists() && draft.isFile) {
            val text = draft.readText()
            if (text.isNotBlank()) {
                val snapshots = try {
                    org.json.JSONObject(text).optJSONArray("boardSnapshots")
                } catch (e: Exception) { null }
                val mc = if (snapshots != null) snapshots.length() - 1 else 0
                if (mc > 0) {
                    return ExternalCandidate("json", "vault_draft", text)
                }
            }
        }
        // ② ★★★ 已移除：HHXQ 全量收集"取最近更新文档"自动覆盖恢复。
        //   这是"切屏/切来切去混入陌生棋局"的元凶——盘上若有别的 txt/fen/pgn/json 文档
        //   mtime 更新，就会被当成"上次棋局"整盘替换掉正在下的棋。
        //   ★ 锁定当前局原则：自动恢复只用上面的"实时预制存档"（=当前对局快照，同盘不变），
        //     绝不自动跳转到其它棋谱文档；卡片/宝库导入只走用户显式操作（openLocalDocBook 等）。
        return null
    }

    /** ★ 主线程执行：把选定的本地文档候选载入棋盘并刷新。返回是否成功。 */
    private fun applyExternalCandidate(cand: ExternalCandidate): Boolean {
        try {
            val loaded = if (cand.ext == "json") {
                try {
                    val json = org.json.JSONObject(cand.content)
                    if (json.has("boardSnapshots")) chessGame.loadFullStateJson(json) && boardHasKings()
                    else importTextToBoard(cand.content, cand.name)
                } catch (e: Exception) { importTextToBoard(cand.content, cand.name) }
            } else {
                importTextToBoard(cand.content, cand.name)
            }
            if (!loaded) return false
            afterExternalRestore()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "applyExternalCandidate: ${e.message}", e)
            return false
        }
    }

    /** ★ 外部恢复成功后的统一收尾：刷新棋盘/步数/说明，并回写 SharedPreferences，后续正常续盘。 */
    private fun afterExternalRestore() {
        chessGame.redIsHuman = prefs.getBoolean(KEY_RED_HUMAN, true)
        chessGame.blackIsHuman = prefs.getBoolean(KEY_BLACK_HUMAN, false)
        restoreHighlightsFromHistory()
        chessGame.onBoardChanged?.invoke()
        updateSideButtons()
        updateStepInfo()
        refreshExplanationDisplay()
        saveGameStateFull()
    }

    /** 校验棋盘是否有效（红黑双将齐全）。 */
    private fun boardHasKings(): Boolean {
        return try {
            chessGame.board.findKing(com.pikafish.chess.model.PieceColor.RED) != null &&
                chessGame.board.findKing(com.pikafish.chess.model.PieceColor.BLACK) != null
        } catch (e: Exception) { false }
    }

    /** 保存所有设置到SharedPreferences */
    private fun saveAllSettings() {
        if (!settingsRestored) return  // 恢复完成前不保存，防止覆盖
        try {
            // 引擎未启动时用pendingHash/pendingThreads，启动后用引擎实际值
            val saveHash = if (chessGame.isEngineReady) chessGame.currentHash else if (pendingHash > 0) pendingHash else chessGame.currentHash
            val saveThreads = if (chessGame.isEngineReady) chessGame.currentThreads else if (pendingThreads > 0) pendingThreads else chessGame.currentThreads
            prefs.edit()
                .putInt(KEY_ENGINE_TIME, chessGame.engineTimeMs)
                .putString(KEY_SEARCH_MODE, chessGame.searchMode)
                .putInt(KEY_ENGINE_DEPTH, chessGame.engineDepth)
                .putBoolean(KEY_SHOW_THINKING, chessGame.showThinkingDetails)
                .putBoolean(KEY_FLOAT_OVERLAY, floatOverlayEnabled)
                .putBoolean(KEY_ALWAYS_THINKING, chessGame.alwaysThinking)
                .putBoolean(KEY_ENABLE_EXPLANATION, chessGame.enableExplanation)
                .putInt(KEY_EXPLANATION_DEPTH, chessGame.explanationDepth)
                .putInt(KEY_COMMENTARY_STYLE, chessGame.commentaryStyle.ordinal)
                .putBoolean(KEY_QIPU_COMMENTARY, chessGame.qipuCommentaryEnabled)
                .putInt(KEY_HASH, saveHash)
                .putInt(KEY_THREADS, saveThreads)
                .putFloat(KEY_BOARD_SCALE, chessBoard.getBoardScale())
                .putFloat(KEY_PIECE_SCALE, chessBoard.getPieceScale())
                .putFloat(KEY_ABS_CELL_SIZE, chessBoard.getAbsoluteCellSize())
                .putFloat(KEY_ABS_PIECE_RADIUS, chessBoard.getAbsolutePieceRadius())
                // ★★★ 真实渲染格子像素：三个沙盒以此为准统一棋盘大小（不再各自按可视带算）
                .putFloat(KEY_RENDER_CELL, chessBoard.getRenderCellSize())
                .putBoolean(KEY_DAMEI_LOCKED, dameiLocked)
                .putFloat(KEY_DAMEI_BOARD_H, dameiBoardH)
                .putFloat(KEY_DAMEI_MENU_H, dameiMenuH)
                .putInt(KEY_LINE_COLOR_IDX, currentLineColorIndex)
                .putInt(KEY_LINE_THICK_IDX, currentLineThicknessIndex)
                .putInt(KEY_BG_MODE, currentBgMode)
                .putInt(KEY_PIECE_SKIN, currentPieceSkin)
                .putInt(KEY_OVERLAY_MODE, overlayCommentaryMode)
                .putInt(KEY_MENU_BG_IDX, currentMenuBgIndex)
                .putInt(KEY_MENU_BTN_IDX, currentMenuBtnIndex)
                .putBoolean(KEY_WHATIF_MODE, whatIfMode)
                .putBoolean(KEY_SHOW_TIME, showTimeOnBoard)
                .putBoolean(KEY_RED_HUMAN, chessGame.redIsHuman)
                .putBoolean(KEY_BLACK_HUMAN, chessGame.blackIsHuman)
                .putBoolean(KEY_CLOUD_BOOK, chessGame.cloudBookEnabled)
                // ★★ 解说字/点动字/棋桌：视觉新偏好，持久化
                .putFloat(KEY_COMMENT_FONT_SP, commentFontSp)
                .putFloat(KEY_TAP_FONT_SCALE, tapFontScale)
                .putInt(KEY_TABLE_STYLE, tableStyleIndex)
                .putInt(KEY_LABEL_COLOR, labelColor)
                // ★★「棋盘坐标」「组件坐标」：整盘/底栏纵向微调值——必须持久，重启一律照上一次写回
                .putFloat(KEY_BOARD_Y_OFFSET, chessBoard.getBoardYOffset())
                .putFloat(KEY_MENU_Y_PX, menuYOffsetPx)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Save all settings failed: ${e.message}")
        }
    }

    /** 同步保存所有设置（用于 onPause 等关键时机，确保立即写入磁盘） */
    private fun saveAllSettingsSync() {
        if (!settingsRestored) return  // 恢复完成前不保存，防止覆盖
        try {
            val saveHash = if (chessGame.isEngineReady) chessGame.currentHash else if (pendingHash > 0) pendingHash else chessGame.currentHash
            val saveThreads = if (chessGame.isEngineReady) chessGame.currentThreads else if (pendingThreads > 0) pendingThreads else chessGame.currentThreads
            prefs.edit()
                .putInt(KEY_ENGINE_TIME, chessGame.engineTimeMs)
                .putString(KEY_SEARCH_MODE, chessGame.searchMode)
                .putInt(KEY_ENGINE_DEPTH, chessGame.engineDepth)
                .putBoolean(KEY_SHOW_THINKING, chessGame.showThinkingDetails)
                .putBoolean(KEY_FLOAT_OVERLAY, floatOverlayEnabled)
                .putBoolean(KEY_ALWAYS_THINKING, chessGame.alwaysThinking)
                .putBoolean(KEY_ENABLE_EXPLANATION, chessGame.enableExplanation)
                .putInt(KEY_EXPLANATION_DEPTH, chessGame.explanationDepth)
                .putInt(KEY_COMMENTARY_STYLE, chessGame.commentaryStyle.ordinal)
                .putBoolean(KEY_QIPU_COMMENTARY, chessGame.qipuCommentaryEnabled)
                .putInt(KEY_HASH, saveHash)
                .putInt(KEY_THREADS, saveThreads)
                .putFloat(KEY_BOARD_SCALE, chessBoard.getBoardScale())
                .putFloat(KEY_PIECE_SCALE, chessBoard.getPieceScale())
                .putFloat(KEY_ABS_CELL_SIZE, chessBoard.getAbsoluteCellSize())
                .putFloat(KEY_ABS_PIECE_RADIUS, chessBoard.getAbsolutePieceRadius())
                .putFloat(KEY_RENDER_CELL, chessBoard.getRenderCellSize())
                .putBoolean(KEY_DAMEI_LOCKED, dameiLocked)
                .putFloat(KEY_DAMEI_BOARD_H, dameiBoardH)
                .putFloat(KEY_DAMEI_MENU_H, dameiMenuH)
                .putInt(KEY_LINE_COLOR_IDX, currentLineColorIndex)
                .putInt(KEY_LINE_THICK_IDX, currentLineThicknessIndex)
                .putInt(KEY_BG_MODE, currentBgMode)
                .putInt(KEY_OVERLAY_MODE, overlayCommentaryMode)
                .putInt(KEY_MENU_BG_IDX, currentMenuBgIndex)
                .putInt(KEY_MENU_BTN_IDX, currentMenuBtnIndex)
                .putBoolean(KEY_WHATIF_MODE, whatIfMode)
                .putBoolean(KEY_SHOW_TIME, showTimeOnBoard)
                .putBoolean(KEY_RED_HUMAN, chessGame.redIsHuman)
                .putBoolean(KEY_BLACK_HUMAN, chessGame.blackIsHuman)
                .putBoolean(KEY_CLOUD_BOOK, chessGame.cloudBookEnabled)
                // ★★ 解说字/点动字/棋桌：视觉新偏好，持久化（同步落盘）
                .putFloat(KEY_COMMENT_FONT_SP, commentFontSp)
                .putFloat(KEY_TAP_FONT_SCALE, tapFontScale)
                .putInt(KEY_TABLE_STYLE, tableStyleIndex)
                // ★★「棋盘坐标」「组件坐标」：纵向微调值——同步落盘，保证 onPause/强退也能记住
                .putFloat(KEY_BOARD_Y_OFFSET, chessBoard.getBoardYOffset())
                .putFloat(KEY_MENU_Y_PX, menuYOffsetPx)
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Save all settings sync failed: ${e.message}")
        }
    }

    /** 从SharedPreferences恢复所有设置 */
    private fun restoreAllSettings() {
        try {
            // 先标记正在恢复UI，避免spinner监听器误触发保存
            isUpdatingUI = true

            chessGame.engineTimeMs = prefs.getInt(KEY_ENGINE_TIME, 5000)
            chessGame.searchMode = prefs.getString(KEY_SEARCH_MODE, "time") ?: "time"
            chessGame.engineDepth = prefs.getInt(KEY_ENGINE_DEPTH, 10)
            chessGame.showThinkingDetails = prefs.getBoolean(KEY_SHOW_THINKING, true)
            floatOverlayEnabled = prefs.getBoolean(KEY_FLOAT_OVERLAY, false)   // ★ 分析浮透
            chessGame.alwaysThinking = prefs.getBoolean(KEY_ALWAYS_THINKING, false)
            chessGame.enableExplanation = prefs.getBoolean(KEY_ENABLE_EXPLANATION, true)
            chessGame.explanationDepth = prefs.getInt(KEY_EXPLANATION_DEPTH, 8)
            val styleOrdinal = prefs.getInt(KEY_COMMENTARY_STYLE, 0)
            chessGame.commentaryStyle = ChessGame.CommentaryStyle.values()
                .getOrElse(styleOrdinal) { ChessGame.CommentaryStyle.STANDARD }
            chessGame.qipuCommentaryEnabled = prefs.getBoolean(KEY_QIPU_COMMENTARY, true)
            
            // 暂存hash/threads（引擎启动后应用）
            pendingHash = prefs.getInt(KEY_HASH, 0)
            pendingThreads = prefs.getInt(KEY_THREADS, 0)
            
            // 恢复红黑方人脑/AI设置（不依赖引擎）
            chessGame.redIsHuman = prefs.getBoolean(KEY_RED_HUMAN, true)
            chessGame.blackIsHuman = prefs.getBoolean(KEY_BLACK_HUMAN, false)
            chessGame.cloudBookEnabled = prefs.getBoolean(KEY_CLOUD_BOOK, false)  // ★ 云库默认关闭

            // ★ 本地外置开局库：恢复开关 + 恢复已挂接的外置库
            // 不再自动装载"内置默认库"（曾内置一本未经棋谱审核、含劣着/自吃子的
            // default_book.json，会让引擎开局直接落入错误局面，已移除）。
            chessGame.localBookEnabled = prefs.getBoolean(KEY_LOCAL_BOOK, true)
            // ★ 外置库反解耗时（雪花库 62 万条可达数秒），全部改在后台线程异步装载，
            //   不阻塞主线程渲染首屏，App 立即可用；侧滑栏显示"加载中…"，随时可关面板去下棋。
            val bookPaths = prefs.getString(KEY_LOCAL_BOOK_PATHS, null)
            // 先登记待装载的库名，让面板立即可见"加载中"，再开后台线程反解
            bookPaths?.split(",")?.forEach { p ->
                val nm = p.trim()
                if (nm.isNotEmpty()) loadingBooks.add(java.io.File(nm).name.substringBeforeLast('.'))
            }
            renderBookList()   // 先渲染一次（含"加载中"占位），装载完成后会再刷新
            initScope.launch(Dispatchers.IO) {
                val restored = restoreExternalLocalBooksAsync(bookPaths)
                uiHandler.post {
                    loadingBooks.clear()   // 全部装完，移除所有"加载中"占位
                    try { renderBookList() } catch (_: Exception) {}
                    tvStatus.text = if (restored > 0) "已装载 $restored 本外置开局库" else "正在初始化…"
                }
            }
            
            // 视觉设置 ── 绝对像素优先恢复 ──
            // 默认档位：棋盘88%、棋子117%（等效真人把滑条划到该位置；用户调过后按用户的来。
            //   棋盘默认 88% —— 88%~89% 是稳妥上限，再高重启后边线棋子会溢出屏幕。
            //   首装时 SplashActivity 也静默写入这两档并保存，这里 fallback 保持一致。）
            // 先恢复比例值（兼容旧数据）
            val boardScale = if (prefs.getBoolean(KEY_BOARD_SCALE_PICKED, false)) {
                // 用户亲手拖过：依他存的那一档，绝不跟用户唱反调
                prefs.getFloat(KEY_BOARD_SCALE, DEFAULT_BOARD_SCALE)
            } else {
                // 从没拖过（含旧版本机存的 96% 默认档）：静默落到 88%，并即刻写回 ——
                // 主界面、三个沙盒、下次重启看到的都是同一个 88%，不留半分隔阂
                prefs.edit().putFloat(KEY_BOARD_SCALE, DEFAULT_BOARD_SCALE).apply()
                DEFAULT_BOARD_SCALE
            }
            if (boardScale > 0) chessBoard.setBoardScale(boardScale)
            val pieceScale = prefs.getFloat(KEY_PIECE_SCALE, 1.17f)
            if (pieceScale > 0) chessBoard.setPieceScale(pieceScale)
            // 再恢复绝对像素值（覆盖比例，这才是卡尺级真实尺寸）
            // ★★ 不再用 abs_cell_size/abs_piece_radius 像素覆盖恢复——旧实现把"像素/易变
            //   基准格"经 setAbsoluteCellSize 回写 boardScale 并落盘，容器一挤基准格变小、
            //   像素不变，比值每启增长；大而美又把棋盘钉成 maxCell 大像素、同样污染保存值，
            //   导致"96变87、157疯涨"的持久性腐败。现统一只恢复滑条锚点（上面的
            //   setBoardScale/setPieceScale），渲染 = 当前基准格×锚点、封顶适配，数字永不漂移。
            //   （大而美有独立的 dameiBoardH/dameiLocked 持久，重启仍恢复到最大适配尺寸。）
            val absCellSize = prefs.getFloat(KEY_ABS_CELL_SIZE, 0f)
            // if (absCellSize > 0) chessBoard.setAbsoluteCellSize(absCellSize)
            val absPieceRadius = prefs.getFloat(KEY_ABS_PIECE_RADIUS, 0f)
            // if (absPieceRadius > 0) chessBoard.setAbsolutePieceRadius(absPieceRadius)
            // ★★★ 大而美：恢复边界锁定（重启后棋盘依然"最大且不爆屏"，显/隐切换大小不变）
            dameiLocked = prefs.getBoolean(KEY_DAMEI_LOCKED, false)
            dameiBoardH = prefs.getFloat(KEY_DAMEI_BOARD_H, 0f)
            dameiMenuH = prefs.getFloat(KEY_DAMEI_MENU_H, 0f)
            // ★★ 大而美真恢复：重启后把「最大且不爆屏」的绝对格子像素重新钉上。
            //   旧的 tuneBoardVsThinking 分支在"全屏贴纸方案"下已是死代码(函数首行即 return)，
            //   且 applyDaMei 把 dameiBoardH 存为 0，故该分支永远不触发 → 重启后大而美的
            //   精确尺寸根本没找回，只剩滑条锚点兜底。这里等可视带定下后静默重钉一次，
            //   让大而美真正"记得住"。存首装/用户未用大而美时为 false，保持原行为。
            pendingDaMeiRestore = dameiLocked

            // ★★「棋盘坐标」「组件坐标」：恢复纵向微调值。
            //   boardYOffset 走「待应用」通道（可视带定下后平移整盘），menu 偏移在菜单贴底时直接用。
            boardYOffsetPending = prefs.getFloat(KEY_BOARD_Y_OFFSET, 0f)
            menuYOffsetPx = prefs.getFloat(KEY_MENU_Y_PX, 310f)
            // 缩放输入框已取消（改为弹窗横屏滑条）；此处直接恢复棋盘/棋子比例即可（见上方 setBoardScale）
            currentLineColorIndex = prefs.getInt(KEY_LINE_COLOR_IDX, 0)
            currentLineThicknessIndex = prefs.getInt(KEY_LINE_THICK_IDX, 2)  // 默认"标准"
            currentBgMode = prefs.getInt(KEY_BG_MODE, 0)
            overlayCommentaryMode = prefs.getInt(KEY_OVERLAY_MODE, 0)
            currentMenuBgIndex = prefs.getInt(KEY_MENU_BG_IDX, 0)
            currentMenuBtnIndex = prefs.getInt(KEY_MENU_BTN_IDX, 0)
            // ★★ 解说字/点动字/棋桌：恢复视觉新偏好
            commentFontSp = prefs.getFloat(KEY_COMMENT_FONT_SP, 14f)
            tapFontScale = prefs.getFloat(KEY_TAP_FONT_SCALE, 1.0f)
            // ★★ 棋桌风格：用户亲手挑过 → 依他存的那一档（持久化，绝不跟用户唱反调）；
            //    从没挑过 → 静默给暖白枫（含老版本存下的"红酸枝"默认值，一并静默换过来）。
            tableStyleIndex = if (prefs.getBoolean(KEY_TABLE_STYLE_PICKED, false))
                prefs.getInt(KEY_TABLE_STYLE, DEFAULT_TABLE_STYLE)
            else DEFAULT_TABLE_STYLE
            labelColor = prefs.getInt(KEY_LABEL_COLOR, android.graphics.Color.parseColor("#5C4A2A"))
            whatIfMode = prefs.getBoolean(KEY_WHATIF_MODE, false)
            showTimeOnBoard = prefs.getBoolean(KEY_SHOW_TIME, false)
            
            // 恢复UI状态
            updateSettingUI()
            updateDetailButton()
            updateAlwaysThinkButton()   // ★ 后台思考开关状态
            updateCloudBookButton()  // ★ 云库按钮状态
            updateSideButtons()
            updateSettingsUI()
            btnCommentaryStyle.text = chessGame.commentaryStyle.label
            updateAnalysisButton()
            btnAnalyze.text = if (whatIfMode) "解惑:开" else "解惑:关"
            btnTimeToggle.text = if (showTimeOnBoard) "时间:开" else "时间:关"
            chessBoard.setShowTime(showTimeOnBoard)
            if (showTimeOnBoard) {
                timeUpdateHandler.post(timeUpdateRunnable)
            }
            btnBgSwitch.text = "棋布:${com.pikafish.chess.ui.ChessBoardView.BgMode.values().getOrElse(currentBgMode) { com.pikafish.chess.ui.ChessBoardView.BgMode.SOLID }.label}"
            currentPieceSkin = prefs.getInt(KEY_PIECE_SKIN, 0).coerceIn(0, 5)
            chessBoard.setPieceSkin(currentPieceSkin)
            btnPieceSkin.text = "皮肤:${skinLabel()}"
            btnOverlayCommentary.text = when (overlayCommentaryMode) {
                1 -> "盘内讲解:开"
                2 -> "盘内讲解:点动"
                else -> "盘内讲解:关"
            }

            // 恢复盘内讲解模式（应用到棋盘视图）
            val overlayMode = when (overlayCommentaryMode) {
                1 -> com.pikafish.chess.ui.ChessBoardView.OverlayMode.ON
                2 -> com.pikafish.chess.ui.ChessBoardView.OverlayMode.TAP
                else -> com.pikafish.chess.ui.ChessBoardView.OverlayMode.OFF
            }
            chessBoard.setOverlayMode(overlayMode)

            // ★★ 解说字/点动字/棋桌：把恢复值写回滑杆、标签与棋盘视图
            chessBoard.setTapFontScale(tapFontScale)
            chessBoard.setTableStyle(com.pikafish.chess.ui.ChessBoardView.TableStyle.from(tableStyleIndex))
            chessBoard.setLabelColor(labelColor)
            sbCommentFont.progress = commentFontSp.coerceIn(8f, 32f).toInt()
            sbTapFont.progress = ((tapFontScale - 0.6f) / (1.8f - 0.6f) * 120f).coerceIn(0f, 120f).toInt()
            tvCommentFontVal.text = "${commentFontSp.toInt()}sp"
            tvTapFontVal.text = "${(tapFontScale * 100).toInt()}%"
            btnTableStyle.text = "棋桌:${com.pikafish.chess.ui.ChessBoardView.TableStyle.from(tableStyleIndex).label}"
            applyCommentFont()
            
            // 恢复视觉元素
            val bgModes = com.pikafish.chess.ui.ChessBoardView.BgMode.values()
            val bgMode = bgModes.getOrElse(currentBgMode) { com.pikafish.chess.ui.ChessBoardView.BgMode.SOLID }
            chessBoard.setBackgroundMode(bgMode)
            
            // 恢复棋线颜色和粗细
            if (currentLineColorIndex >= 0 && currentLineColorIndex < lineColors.size) {
                val lineColor = try { Color.parseColor(lineColors[currentLineColorIndex].second) } catch (_: Exception) { Color.parseColor("#3A3020") }
                chessBoard.setLineColor(lineColor)
                spinnerLineColor.setSelection(currentLineColorIndex)
            }
            if (currentLineThicknessIndex >= 0 && currentLineThicknessIndex < lineThicknesses.size) {
                chessBoard.setLineThickness(lineThicknesses[currentLineThicknessIndex].second)
                spinnerLineThickness.setSelection(currentLineThicknessIndex)
            }
            
            // 恢复菜单按钮颜色
            if (currentMenuBtnIndex >= 0 && currentMenuBtnIndex < menuBtnColors.size) {
                val btnColor = try { Color.parseColor(menuBtnColors[currentMenuBtnIndex].second) } catch (_: Exception) { Color.parseColor("#FFB300") }
                updateAllMenuButtonColors(btnColor)
                spinnerMenuBtnColor.setSelection(currentMenuBtnIndex)
            }
            if (currentMenuBgIndex >= 0 && currentMenuBgIndex < menuBgColors.size) {
                spinnerMenuBgColor.setSelection(currentMenuBgIndex)
                // ★ 手动应用菜单背景色（因为isUpdatingUI=true时spinner监听器不触发）
                val menuBgColor = try { Color.parseColor(menuBgColors[currentMenuBgIndex].second) } catch (_: Exception) { Color.parseColor("#F4E9D8") }
                applyMenuBgColor(menuBgColor)
            }
            
            if (!tvThinking.text.toString().contains("异常")) {
                tvThinking.visibility = if (chessGame.showThinkingDetails) View.VISIBLE else View.INVISIBLE
            }

            // 恢复完成，解除UI更新标记
            isUpdatingUI = false
            settingsRestored = true  // 标记已恢复，允许后续保存

            // ★★★ 全局底色持久化（修复重启丢失）：initViews 里先套过一次全局底色，
            //     但随后恢复「菜单底色」会把同一批视图(状态栏/底栏/菜单面板)再刷一遍，
            //     导致用户选的全局底色被覆盖、重启后"看起来没保存"。
            //     这里在全部设置恢复完成后【最后】再套一次全局底色，让它真正持久生效。
            applyGlobalBackdrop()

            // ★ 恢复后同步「分析浮透」按钮与盘心浮透层状态（恢复发生在 initViews 之后）
            updateFloatOverlayButton()
        } catch (e: Exception) {
            isUpdatingUI = false
            settingsRestored = true  // 即使出错也标记，防止反复保存默认值
            Log.e(TAG, "Restore all settings failed: ${e.message}")
        }
    }

    // ── ★ 导入棋谱对话框 ★ ──

    private fun copyToClipboard(label: String, content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
        Toast.makeText(this, "${label}已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun saveToFile(ext: String, content: String) {
        // ★ 保存到专用文件夹 /sdcard/HHXQ/（黄昏象棋拼音缩写）
        val dir = java.io.File(Environment.getExternalStorageDirectory(), "HHXQ")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = "黄昏象棋_${System.currentTimeMillis()}.$ext"
        val file = java.io.File(dir, fileName)
        try {
            file.writeText(content)
            Toast.makeText(this, "已保存到 HHXQ/${fileName}", Toast.LENGTH_LONG).show()
            Log.i(TAG, "棋谱已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存到HHXQ失败: ${e.message}", e)
            // 回退到内部存储
            try {
                val fallback = java.io.File(filesDir, fileName)
                fallback.writeText(content)
                Toast.makeText(this, "已保存到内部存储: ${fallback.name}", Toast.LENGTH_SHORT).show()
            } catch (e2: Exception) {
                Toast.makeText(this, "保存失败: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── 横排Tab互斥切换（点击哪个Tab只显示对应面板）──

    private fun setupTabNavigation() {
        // ★ 默认落到「对弈」页；「设置」不再是底部面板，点它弹出半透明纸张设置弹窗。
        showTabPanel(btnTabGame, showGamePanel)
        highlightTab(btnTabGame)
        navActiveTab = btnTabGame
        navPanelCollapsed = false

        // ★ 每个 Tab：点一次切换并展开；若点的就是当前已展开的 Tab，则再点一次收起其面板
        //   （只留 Tab 行 + 常驻底栏，棋盘空间顿时敞亮）；再点一次面板完整弹开。
        btnTabSettings.setOnClickListener { openSettingsDialog() }
        btnTabGame.setOnClickListener {
            onTabClicked(btnTabGame, showGamePanel)
        }
        btnTabNotation.setOnClickListener {
            onTabClicked(btnTabNotation, showNotationPanel)
        }
    }

    // ── 各面板的“显示”动作（切换/展开时统一执行）──
    private val showGamePanel: () -> Unit = {
        try {
            scrollViewExplanation.visibility = View.GONE
            layoutParamsContent.visibility = View.GONE
            layoutNotationContent.visibility = View.GONE
            layoutVisualContent.visibility = View.GONE
            layoutVisualContent2.visibility = View.GONE
            layoutMoreContent.visibility = View.GONE
            layoutGameContent.visibility = View.VISIBLE
            layoutGameSub.visibility = View.VISIBLE   // 对弈子菜单固定行：仅对弈Tab露出
            layoutGameSubRow2.visibility = View.VISIBLE   // 对弈子菜单第2行：同显
            toggleGamePage()
        } catch (e: Exception) {
            Log.e(TAG, "对弈Tab切换异常: ${e.message}", e)
        }
    }
    private val showNotationPanel: () -> Unit = {
        scrollViewExplanation.visibility = View.GONE
        layoutParamsContent.visibility = View.GONE
        layoutGameContent.visibility = View.GONE
        layoutGameSub.visibility = View.GONE
        layoutGameSubRow2.visibility = View.GONE
        layoutNotationContent.visibility = View.VISIBLE
        layoutVisualContent.visibility = View.GONE
        layoutVisualContent2.visibility = View.GONE
        layoutMoreContent.visibility = View.GONE
        layoutQipuImports.visibility = View.VISIBLE
        layoutQipuSub.visibility = View.VISIBLE
        btnTabNotation.text = "棋谱"
    }

    /** 直接把某个 Tab 显示出来（程序化跳转用），并同步折叠状态。 */
    private fun showTabPanel(tab: Button, show: () -> Unit) {
        navPanelCollapsed = false
        navActiveTab = tab
        show()
        highlightTab(tab)
    }

    /** Tab 点击统一入口：已激活再点一次 → 折叠/展开切换；未激活 → 切换并展开。 */
    private fun onTabClicked(tab: Button, show: () -> Unit) {
        if (navActiveTab == tab) {
            if (navPanelCollapsed) {
                // 已折叠 → 再点一次弹开
                showTabPanel(tab, show)
            } else {
                // 已展开 → 再点一次收起（只留 Tab 行，空间敞亮）
                collapseAllPanels()
                navActiveTab = tab        // 记住是谁折叠的，再点它才弹开
                navPanelCollapsed = true
                highlightTab(tab)
            }
        } else {
            showTabPanel(tab, show)
        }
    }

    private fun collapseAllPanels() {
        // ★ 收起全部内容面板 → 露出"空地"：把棋谱/解说通用区铺进来（完整走棋记录，区内可滚动）
        scrollViewExplanation.visibility = View.VISIBLE
        layoutParamsContent.visibility = View.GONE
        layoutGameContent.visibility = View.GONE
        layoutGameSub.visibility = View.GONE
        layoutGameSubRow2.visibility = View.GONE
        layoutNotationContent.visibility = View.GONE
        layoutVisualContent.visibility = View.GONE
        layoutVisualContent2.visibility = View.GONE
        layoutMoreContent.visibility = View.GONE
    }

    // ════════════════════════════════════════════════════════════
    //  设置纸张弹窗：合并「参数 / 视觉 / 更多」三大类，
    //  分栏罗列、板正如表格，半透明纸张衬底只遮约半盘、不占满全屏、不挡底栏
    // ════════════════════════════════════════════════════════════
    private var settingsDialog: android.app.Dialog? = null
    private var settingsOrigin: android.widget.LinearLayout? = null
    private val settingsMovedPanels = mutableListOf<View>()

    /** 点「设置」：弹出设置纸张窗。四大内容面板临时挪进弹窗按类分组，关闭后原样归还。 */
    private fun openSettingsDialog() {
        if (settingsDialog?.isShowing == true) return
        try {
            val container = findViewById<android.widget.ScrollView>(R.id.scrollTabContent)
            val origin = (container?.getChildAt(0) as? android.widget.LinearLayout)
                ?: container as? android.widget.LinearLayout
            if (origin == null) { toast("当前版面无法打开设置窗"); return }
            settingsOrigin = origin

            // 先把底部内容面收拾出"空地"，只留 Tab 行 + 快捷栏
            collapseAllPanels()

            val dlg = android.app.Dialog(this)
            settingsDialog = dlg
            val dm = resources.displayMetrics
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dlg.window?.setDimAmount(0.16f)
            // ★ 覆盖约 55% 高度 × 70% 宽度：避让左右+底部，只遮半盘，棋盘仍可瞥见
            dlg.window?.setLayout((dm.widthPixels * 0.70).toInt(), (dm.heightPixels * 0.55).toInt())
            dlg.window?.setGravity(android.view.Gravity.CENTER)

            val scroll = ScrollView(this).apply {
                background = resources.getDrawable(R.drawable.bg_settings_paper, theme)
                overScrollMode = View.OVER_SCROLL_ALWAYS
                clipToPadding = false
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            scroll.addView(content)
            dlg.setContentView(scroll)

            // ★ 圈地运动（无话头分区）：不再要「参数/视觉/更多」话头行（省 3 行面积），
            //   每个片区直接铺一张不同底色的「大胶囊纸」——同片区同底色、异片区异纸色，
            //   一眼分群、不必言说。纯文字标签(线色/线粗/菜单底/菜单钮/解说字/点动字)照旧不圈；
            //   面板里的按钮临时换「小胶囊」底（弹窗关闭时全部恢复原样，主界面零残留）。
            moveToDialog(origin, layoutParamsContent, content)
            applyCapsulePaper(layoutParamsContent, R.drawable.bg_capsule_param)
            moveToDialog(origin, layoutVisualContent, content)
            applyCapsulePaper(layoutVisualContent, R.drawable.bg_capsule_visual)
            moveToDialog(origin, layoutVisualContent2, content)
            applyCapsulePaper(layoutVisualContent2, R.drawable.bg_capsule_visual)
            moveToDialog(origin, layoutMoreContent, content)
            applyCapsulePaper(layoutMoreContent, R.drawable.bg_capsule_more)

            // ★ 点弹窗外任意空白处 = 确认并退出（调整项均即时生效）
            dlg.setCanceledOnTouchOutside(true)
            dlg.setOnDismissListener { restoreSettingsPanels() }
            dlg.show()
        } catch (e: Exception) {
            Log.e(TAG, "设置弹窗异常: ${e.message}", e)
            restoreSettingsPanels()
        }
    }

    /** 面板原状快照：背景 + 内边距 + 边距（铺「大胶囊纸」前先存档，归还时恢复）。 */
    private class PanelOrig(val bg: android.graphics.drawable.Drawable?,
                            val pad: IntArray, val mar: IntArray)

    /** 「大胶囊纸」「小胶囊」底的原状档案（弹窗关闭时逐一恢复，主界面零残留）。 */
    private val capsulePanelUndo = HashMap<View, PanelOrig>()
    private val capsuleBtnUndo = HashMap<View, android.graphics.drawable.Drawable?>()

    /** 给面板临时铺一张「大胶囊纸」（圆角分区底色）+ 面板内按钮临时换「小胶囊」底。
     *  不改任何视图树结构（挪动方式与参考版一致），只换皮肤；关闭时恢复原样。 */
    private fun applyCapsulePaper(panel: View, bgRes: Int) {
        try {
            val lp = panel.layoutParams
            if (!capsulePanelUndo.containsKey(panel)) {
                capsulePanelUndo[panel] = PanelOrig(
                    panel.background,
                    intArrayOf(panel.paddingLeft, panel.paddingTop, panel.paddingRight, panel.paddingBottom),
                    if (lp is LinearLayout.LayoutParams)
                        intArrayOf(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
                    else intArrayOf(0, 0, 0, 0))
            }
            panel.background = resources.getDrawable(bgRes, theme)
            panel.setPadding(dp(10), dp(8), dp(10), dp(10))
            if (lp is LinearLayout.LayoutParams) {
                lp.topMargin = dp(7)
                panel.layoutParams = lp
            }
            capsulePanelButtons(panel)
        } catch (_: Exception) { }
    }

    /** 递归把面板里的 Button 临时换「小胶囊」底（backgroundTint 照旧着色、ripple 保留按压反馈）。
     *  ★ −/+ 步进钮例外：内容只有单个符号，用纯圆底（bg_circle_button）更周正。
     *  ★ 全局底色/个性化例外：文字固定且短，用圆角更小的紧凑胶囊（bg_pill_button_tight），
     *    圆弧提前收口、不显得那么椭，腾出横向空间让「皮肤:璀璨黄金」4 个字全显。 */
    private fun capsulePanelButtons(root: View) {
        if (root !is android.view.ViewGroup) return
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i) ?: continue
            if (c is Button) {
                if (!capsuleBtnUndo.containsKey(c)) capsuleBtnUndo[c] = c.background
                val res = when (c.id) {
                    R.id.btnMinus, R.id.btnPlus -> R.drawable.bg_circle_button
                    R.id.btnGlobalBackdrop, R.id.btnPersonalize -> R.drawable.bg_pill_button_tight
                    else -> R.drawable.bg_pill_button
                }
                c.background = resources.getDrawable(res, theme)
            } else if (c is android.view.ViewGroup) {
                capsulePanelButtons(c)
            }
        }
    }

    /** 恢复所有「大胶囊纸」「小胶囊」原状（弹窗关闭/异常时调用）。 */
    private fun restoreCapsuleLooks() {
        for ((v, st) in capsulePanelUndo) {
            try {
                v.background = st.bg
                v.setPadding(st.pad[0], st.pad[1], st.pad[2], st.pad[3])
                val lp = v.layoutParams
                if (lp is LinearLayout.LayoutParams) {
                    lp.leftMargin = st.mar[0]; lp.topMargin = st.mar[1]
                    lp.rightMargin = st.mar[2]; lp.bottomMargin = st.mar[3]
                    v.layoutParams = lp
                }
            } catch (_: Exception) { }
        }
        capsulePanelUndo.clear()
        for ((b, bg) in capsuleBtnUndo) {
            try { b.background = bg } catch (_: Exception) { }
        }
        capsuleBtnUndo.clear()
    }

    /** 把一个内容面板从原容器挪进弹窗（可见），并记下待归还。 */
    private fun moveToDialog(origin: android.widget.LinearLayout, panel: View, content: LinearLayout) {
        try { (panel.parent as? android.view.ViewGroup)?.removeView(panel) } catch (_: Exception) {}
        panel.visibility = View.VISIBLE
        content.addView(panel)
        if (panel !in settingsMovedPanels) settingsMovedPanels.add(panel)
    }

    /** 弹窗关闭：把挪出的面板原样归还，重置可见性，并重铺当前 Tab 面板。 */
    private fun restoreSettingsPanels() {
        settingsDialog = null
        // ★ 先恢复「大胶囊纸/小胶囊」原状（背景/内边距/边距/按钮底），主界面零残留
        restoreCapsuleLooks()
        val origin = settingsOrigin ?: run { settingsMovedPanels.clear(); return }
        for (v in settingsMovedPanels) {
            try { (v.parent as? android.view.ViewGroup)?.removeView(v) } catch (_: Exception) {}
            try { origin.addView(v) } catch (_: Exception) {}
        }
        settingsMovedPanels.clear()
        // 归位默认可见性：参数可见，其余隐藏
        layoutParamsContent.visibility = View.VISIBLE
        layoutVisualContent.visibility = View.GONE
        layoutVisualContent2.visibility = View.GONE
        layoutMoreContent.visibility = View.GONE
        // 把当前 Tab 内容面板铺回
        when {
            navActiveTab == btnTabNotation -> showNotationPanel()
            else -> showGamePanel()
        }
    }

    /**
     * ★「隐/显」圆圈：整块主菜单（Tab 行 + 当前内容面板）收起/展开。
     *   收起时 layoutNormalControls 变 GONE，其占用的 wrap_content 高度完全让给
     *   棋盘(fill_parent/w weight=1) —— 思考细节/棋谱文本就在棋盘正下方“堂堂正正”铺开；
     *   展开时恢复原样（记住当前高亮 Tab 面板）。圆圈文字在 隐(将收起)/显(将展开) 间切换。
     */
    private fun toggleMenuCollapse() {
        menuCollapsed = !menuCollapsed
        if (menuCollapsed) {
            // 收起：整块主菜单收掉，高度完全让给棋盘与思考区（棋盘为 weight=1，自动扩张）
            layoutNormalControls.visibility = View.GONE
            btnToggleMenu.text = "显"
            btnToggleMenu.contentDescription = "展开菜单"
        } else {
            // 展开：恢复主菜单，并把当前高亮 Tab 的内容面板重新铺出来
            layoutNormalControls.visibility = View.VISIBLE
            if (navPanelCollapsed) {
                navActiveTab?.let { restoreTabPanel(it) }
            }
            btnToggleMenu.text = "隐"
            btnToggleMenu.contentDescription = "收起菜单"
        }
        // ★ 折叠状态变了，重新测算棋盘区(蓝色)与显示区(思考输出)的分配
        frameBoardOuter.post { tuneBoardVsThinking() }
    }

    /**
     * ★ 让棋盘区(蓝色外罩)恰好包住 9×10 棋盘、富余高度让给下方显示区(思考/解说/引擎输出)。
     *   棋盘不再因外罩被竖直拉高而产生大片蓝边；显示区吃得下更多内容。
     *   菜单收拢/展开或布局变化时会重新测算，幂等、可自校正。
     */
    private fun tuneBoardVsThinking() {
        // ★★★ 全屏棋盘方案：棋盘视图填满整屏、内部按 9×10 自适应居中测算，
        //   任何贴纸(状态栏/菜单/思考区/底栏)显隐都不改变棋盘视图尺寸 → 棋盘纹丝不动，
        //   百分比锚点不再被任何派生像素污染，重启不再疯长/乱扯。
        //   旧的高度博弈逻辑(外罩高度/权重/缓存菜单高)全部作废，只保留贴纸边距校准。
        updateStickerMargins()
        return
        // ── 以下为历史代码：全屏贴纸方案下不再执行，保留仅防旧逻辑被外部引用 ──
        if (!::frameBoardOuter.isInitialized || !::frameSetupThumb.isInitialized) return
        // ★ 拍照比对模式（摆子/识别原图）期间：让主棋盘回到灵活权重，随黑区
        //    上划加高被天然按比例挤压缩小、下划复原——完全对齐 V294 的行为，
        //    本方法不再把棋盘锁成固定高度（否则黑区涨高时棋盘"躲不动"）。
        if (setupThumbFocused) {
            forceBoardFlexibleForThumb()
            return
        }
        // ★★★ 大而美锁定：棋盘区高度恒定 → 棋盘与内容区的交界线永不松动；
        //   点「显/隐」只让下方显示区吸收高度变化，底栏(常驻行)纹丝不动。
        //   ★ 用「大而美激活时」缓存的主菜单高度(dameiMenuH)，而不是实时高度：
        //     否则「显」时菜单高度在『0(隐)↔菜单高』间跳变，会把棋盘挤得忽大忽小(抖帧)。
        //   唯一例外：窗口被极端压缩(分屏/旋转/菜单超高)放不下锁定高度时，
        //   才压到能放下的最大值保底，绝不顶穿屏幕。
        if (dameiLocked && dameiBoardH > 0) {
            val lpB = frameBoardOuter.layoutParams as? LinearLayout.LayoutParams ?: return
            val parentH = (frameBoardOuter.parent as? View)?.height ?: 0
            val statusH = findViewById<View>(R.id.layoutStatusBar)?.height ?: 0
            val navH = findViewById<View>(R.id.layoutBottomNav)?.height ?: 0
            val menuH = dameiMenuH
            val displayMin = dp(60)
            val margin = dp(12)
            val maxAllowed = (parentH - statusH - navH - menuH - displayMin - margin).coerceAtLeast(dp(70).toFloat())
            val target = minOf(dameiBoardH, maxAllowed.toFloat()).coerceAtLeast(dp(70).toFloat()).toInt()
            lpB.weight = 0f
            if (lpB.height != target) {
                lpB.height = target
                frameBoardOuter.requestLayout()
            }
            return
        }
        val lpB = frameBoardOuter.layoutParams as? LinearLayout.LayoutParams ?: return
        val w = frameBoardOuter.width
        if (w <= 0) return
        // ★ 理想高度随用户设定的棋盘缩放同步放大/收窄：上滑(棋盘变大)时锁定高度跟着变大，
        //    下滑(变小时)同步收回，把富余高度让给下方显示区(思考/解说/引擎输出)。
        // ★ 用「锚点缩放」而不是视图回写的 boardScale：布局反馈循环中锚点恒定不变，
        //    杜绝「棋盘大小 ↔ 外罩高度」互相追着跑、在临界值附近来回抖串。
        val scale = runCatching { chessBoard.getEffectiveBoardScale() }.getOrElse { 1f }.coerceIn(0.5f, 2.0f)
        val idealH = ((w * 10f / 9f + 0.05f * w) * scale).toInt()

        // 显示区正被「摆子」放大（固定高度/权重0）时，不动本区高度，避免打架
        val lpTh = frameSetupThumb.layoutParams as? LinearLayout.LayoutParams
        if (lpTh != null && lpTh.height > 0 && lpTh.weight <= 0f) return

        // ★ 定盘星：底部菜单(底栏)钉在屏幕最底，永不上下位移。
        //   棋盘面积圈定上限 = 屏幕高 - 顶栏 - 稳定菜单高 - 显示区最小预留 - 边距；
        //   空间不够时棋盘自动压小，把位置让给下方菜单往上长——无论放大到多少、
        //   显/隐点多少下，底部都不会被顶出屏幕、也不会串动。
        // ★★ 交界线钉死：菜单高用「缓存稳定值」，不再随显/隐实时跳变。
        //   旧代码用「菜单实时高」算上限，显(菜单高>0)/隐(菜单高=0)让 maxAllowed 一下大
        //   一下小，棋盘就被"搂脖子"一撑一缩。现在缓存一次稳定菜单高，无论显/隐都用它，
        //   菜单高度变化只让下方解说/思考区(frameSetupThumb)伸缩，棋盘纹丝不动。
        val parentH = (frameBoardOuter.parent as? View)?.height ?: 0
        val statusH = findViewById<View>(R.id.layoutStatusBar)?.height ?: 0
        // 缓存稳定菜单高（首次可见且测得真实高度后即固定；显/隐不再刷新）
        val menu = layoutNormalControls
        if (stableMenuH <= 0f) {
            if (menu.visibility == View.VISIBLE && menu.height > 0) stableMenuH = menu.height.toFloat()
            else if (menu.visibility == View.VISIBLE && menu.width > 0) {
                menu.measure(
                    View.MeasureSpec.makeMeasureSpec(menu.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                stableMenuH = menu.measuredHeight.toFloat()
            }
        }
        val menuH = if (stableMenuH > 0f) stableMenuH.toInt() else 0
        val displayMin = dp(60)                 // 显示区(思考/解说)最小预留，绝不归零
        val margin = dp(12)
        val maxAllowed = (parentH - statusH - menuH - displayMin - margin).coerceAtLeast(dp(70))
        val minBoard = dp(70)
        // 棋盘实际目标高度：理想值与可用上限取小，且不低于最小值
        val targetH = minOf(idealH, maxAllowed).coerceAtLeast(minBoard)

        val curH = frameBoardOuter.height
        val capped = lpB.height > 0 && lpB.weight <= 0f
        // ★ 一旦已锁定到某固定高度，且当前尺寸正是该锁定值：显/隐切换直接不动棋盘，
        //   只防窗口超大变(旋转/分屏)导致可用上限不足顶穿屏幕时，压小保底。
        if (boardLockedToHeight > 0 && capped && curH == boardLockedToHeight) {
            if (curH > maxAllowed) {
                lpB.weight = 0f
                if (lpB.height != maxAllowed) { lpB.height = maxAllowed; frameBoardOuter.requestLayout() }
            } else if (curH > 0 && targetH > curH + 24 && curH < idealH - 24) {
                // ★ 空间放开就放量回弹：首帧被量小(inset/菜单/视频96dp未就绪)而锁定的棋盘，
                //   随可用空间增大自动长到新的目标高。否则那帧"小棋盘"被钉死，只能靠手点
                //   显/隐触发重测才回正——这正是"重启棋盘变小、点一下显隐才正常"的根因。
                //   死区24px：显/隐用稳定菜单高，targetH 不跳，稳得纹丝不动，不受影响。
                if (lpB.height != targetH) {
                    lpB.height = targetH
                    boardLockedToHeight = targetH
                    frameBoardOuter.requestLayout()
                }
            }
            return
        }
        // ★★★ 滞回死区(18/24px)：把「权重→固定」与「固定→权重」两个切换点拉开距离，
        //   调节到临界值(如接近大而美尺寸)时不再来回翻转——这就是"调节时串串多多、
        //   调完还在串"的根因；配合幂等写入(值没变就不 requestLayout)，一次调完即收敛。
        //   ★ 固定→放开时直接长到 targetH(理想/上限取小)，不再先跳回权重再被打回，
        //     避免"先涨后缩"的多余抖动。
        if (!capped) {
            // 权重模式：分到的高度确比棋盘目标值大 18px 以上 → 切固定到 targetH，富余让给下方
            if (curH > targetH + 18) {
                if (lpB.weight != 0f || lpB.height != targetH) {
                    lpB.weight = 0f
                    lpB.height = targetH
                    boardLockedToHeight = targetH
                    frameBoardOuter.requestLayout()
                }
            }
        } else {
            // 已固定：随可用空间收紧(调大被上限拦住)同步压小；空间放开时直接长到目标值
            if (curH > targetH + 18) {
                if (lpB.height != targetH) {
                    lpB.height = targetH
                    boardLockedToHeight = targetH
                    frameBoardOuter.requestLayout()
                }
            } else if (curH > 0 && curH < idealH - 24 && curH < maxAllowed - 24) {
                if (lpB.height != targetH) {
                    lpB.height = targetH
                    boardLockedToHeight = targetH
                    frameBoardOuter.requestLayout()
                }
            }
        }
    }

    /**
     * ★ 贴纸定位助手：思考/解说区贴纸(frameSetupThumb)的底部边距 = 底栏高 + (主菜单可见?主菜单高:0)。
     *   主菜单显/隐、摆子模式切换、菜单高度变化时自动重算，
     *   使思考区贴纸永远紧贴在主菜单/底栏之上——绝不压棋盘、绝不重叠、绝不漏缝。
     */
    private fun updateStickerMargins() {
        try {
            if (!::frameSetupThumb.isInitialized || !::layoutNormalControls.isInitialized) return
            // ★ 普通对局时整块悬浮边框收起（棋谱已搬进 Tab 空地），只在摆子/识别时露出
            frameSetupThumb.visibility = if (setupThumbFocused) View.VISIBLE else View.GONE
            val lp = frameSetupThumb.layoutParams as? FrameLayout.LayoutParams ?: return
            val navH = findViewById<View>(R.id.layoutBottomNav)?.height?.takeIf { it > 0 } ?: dp(30)
            val menuH = if (layoutNormalControls.visibility == View.VISIBLE) {
                layoutNormalControls.height.takeIf { it > 0 } ?: dp(248)
            } else 0
            val target = navH + menuH
            if (lp.bottomMargin != target) {
                lp.bottomMargin = target
                frameSetupThumb.layoutParams = lp
            }
        } catch (_: Exception) { }
    }

    /**
     * ★ 全屏贴纸方案：按贴纸真实布局坐标算「棋盘可视带」(顶栏贴纸底边 ↔ 主菜单贴纸顶边)，
     *   棋盘贴住带上边摆放——不再按整屏几何居中，顶栏到棋盘不再留大片空白。
     *   ★ 鲁棒自适应（修复不同手机遮挡/缩水）：不再用固定 dp 兜底推算可视带下界，
     *   而是直接读取两张贴纸的「实际像素坐标」——无论状态栏 inset、字体缩放、导航栏类型
     *   （三键/手势条）、屏幕长宽比怎么变，棋盘都严格贴着「状态栏底 ↔ 主菜单顶」摆放，
     *   既不会被底部菜单盖住，也不会因为面板偏高而缩得过小。
     */
    /**
     * ★ 让底部菜单的总高在所有页签、所有展开/折叠状态下都等于同一个恒定值。
     *   对弈/棋谱/设置各页内容及其子菜单都收在每个页面的滚动区(scrollTabContent)内，
     *   滚动区高度固定 = 96dp（棋谱式基准），于是任何切换菜单顶部都纹丝不动、绝不上下窜动。
     *   此前对弈子菜单钉在滚动区外、滚动区又被 44dp 下限截断对冲不掉，导致对弈总高比棋谱
     *   多出约 22dp、菜单顶部上浮串动——现子菜单已移入滚动区与棋谱对称，此处只锁统一高度。
     */
    private fun syncGlobalMenuHeight() {
        try {
            if (!::layoutGameSub.isInitialized || !::scrollTabContent.isInitialized) return
            val density = resources.displayMetrics.density
            // ★ 统一恒定高度（棋谱式）：所有页签、折叠/展开都用它，菜单总高处处一致 → 不窜
            val target = (96 * density).toInt()
            if (scrollTabContent.layoutParams?.height != target) {
                scrollTabContent.layoutParams.height = target
                scrollTabContent.requestLayout()
            }
        } catch (_: Exception) { }
    }

    private fun computeContentBand() {
        try {
            if (!::chessBoard.isInitialized) return
            val content = findViewById<View>(android.R.id.content)
            val screenH = content?.height ?: 0
            if (screenH <= 0) { frameBoardOuter.post { computeContentBand() }; return }
            val statusBar = findViewById<View>(R.id.layoutStatusBar)
            val menu = findViewById<View>(R.id.layoutNormalControls)
            val bottomNav = findViewById<View>(R.id.layoutBottomNav)
            // 顶 = 状态栏贴纸底边真实 Y（已含系统状态栏 inset，靠 applyNavBarInset 的 padding 撑出）
            val statusBottom = statusBar?.bottom?.takeIf { it > 0 }?.toFloat()
            // 底 = 主菜单贴纸顶边真实 Y（★ 菜单收起时仍沿用这条锁线，见下方 bandBottomLocked，绝不退化成底栏顶边）
            val menuVisible = menu?.visibility == View.VISIBLE
            val menuTop = if (menuVisible) menu.top.takeIf { it > 0 }?.toFloat() else null
            val navTop = bottomNav?.top?.takeIf { it > 0 }?.toFloat()
            if (statusBottom == null || (menuVisible && menuTop == null)) {
                // 贴纸尚未完成首次布局，稍后重试再定带，绝不回退到 dp 猜测值
                frameBoardOuter.post { computeContentBand() }
                return
            }
            val top = statusBottom
            // ★★★ 主菜单「隐」掉时，绝不允许棋盘趁虚而入（用户点名）：
            //   可视带下界【始终】锁定在「菜单可见时」那条线上 —— 显/隐切换棋盘尺寸纹丝不动。
            //   菜单可见 → 刷新这条锁线（并记下菜单高当二次兜底）；不可见 → 直接用锁线。
            //   绝不能退化成"底栏顶边"：那正是隐菜单后棋盘疯长、爆屏、边子看不到的根因。
            if (menuVisible && menuTop != null) {
                if (menu.height > 0) stableMenuH = menu.height.toFloat()
                bandBottomLocked = menuTop
            }
            val bottom: Float = menuTop
                ?: bandBottomLocked.takeIf { it > top + 1f }
                ?: (navTop?.minus(stableMenuH)?.takeIf { stableMenuH > 0f && it > top + 1f })
                ?: navTop
                ?: (screenH - dp(30).toFloat())
            if (bottom > top + 1f) {
                // ★★ 不再一次性锁死：菜单高度/可见性变化（页签切换、展开收起、宣传视频进出）
                //   都会再次走到这里，棋盘可视带实时跟随菜单顶边 —— 顶部永不裁切、底部永不留空气。
                val changed = kotlin.math.abs(stickerBandTop - top) > 1f ||
                        kotlin.math.abs(stickerBandBottom - bottom) > 1f
                stickerBandTop = top
                stickerBandBottom = bottom
                if (!stickerBandReady || changed) {
                    chessBoard.setContentBand(top, bottom)
                    // ★★「棋盘坐标」：可视带刚定，把首装/持久化的偏移一次性应用上（只此一次平移）
                    if (boardYOffsetPending != 0f && ::chessBoard.isInitialized) {
                        chessBoard.setBoardYOffset(boardYOffsetPending)
                        boardYOffsetPending = 0f
                    }
                    // ★★ 大而美真恢复：带已定，把「最大且不爆屏」的绝对格子静默重钉一次
                    if (pendingDaMeiRestore) {
                        pendingDaMeiRestore = false
                        applyDaMeiBandOnly()
                    }
                    stickerBandReady = true
                }
            } else {
                frameBoardOuter.post { computeContentBand() }
            }
        } catch (_: Exception) { frameBoardOuter.post { computeContentBand() } }
    }

    /** ★ 确保「思考详情条」tvThinking 存在并挂进根布局 rootMain 底部。
     *   布局 XML 里已把 tvThinking 删除（不再压在棋盘/棋谱面板上方挡棋），这里延迟到代码里
     *   动态创建，定位在底部窄空隙内：浅色可读底、最多显示 2 行——绝不把屏幕撑高、绝不遮挡棋盘。
     *   ★ 用户已拍板定值：X=0、Y距底=171（px）。 */
    private fun ensureThinkingBar(): android.widget.TextView {
        val root = findViewById<android.widget.FrameLayout>(R.id.rootMain)
        val existing = root?.findViewById<android.widget.TextView>(R.id.tvThinking)
        if (existing != null) return existing
        val tv = android.widget.TextView(this)
        tv.id = R.id.tvThinking
        tv.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.START
        ).apply {
            leftMargin = 0          // ★ 用户已拍板：X=0, Y距底=171（px）
            bottomMargin = 171
        }
        tv.setBackgroundColor(0xF2FFF3C4.toInt())   // 浅米色，可读且不遮挡棋盘
        tv.setTextColor(0xFF5D4037.toInt())         // 深棕字
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.maxLines = 2                             // ★ 最多 2 行，绝不多、绝不把屏幕撑高
        tv.isVerticalScrollBarEnabled = false
        tv.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        tv.setPadding(dp(9), dp(4), dp(9), dp(4))
        tv.elevation = 10f
        tv.text = ""
        tv.visibility = android.view.View.VISIBLE
        root?.addView(tv)
        return tv
    }

    // ★★★ 大而美：一键把棋盘棋子调到「最大且不爆屏」并锁定边界。
    //   ★ 棋子尺寸按用户核心算法反推（将/帅居中，半边棋盘分配 4.5 颗子直径：
    //     车马象士 + 半个将 = 4.5 颗直径，再留约 3% 小空隙）：
    //     恰贴缩放 = 4/(4.5×0.94−0.47) ≈ 1.064（直径=1格，互不叠），留空隙 → ≈1.032。
    //     棋子直径≈0.97格：既互不挤蹭、又匀称分布、还足够大——绝不再是"叠烙饼"。
    //   横向跨度 = 8×格子 + 2×半径(0.47×格×缩放) + 左右边缘留白；纵向 = 9×格 + 2×半径 + 刻度标签；
    //   取屏幕宽度/高度两约束的最小格子 → 棋子不顶边框、不碰屏幕，满满登登不爆屏。
    //   边界锁定：棋盘区高度定死 = 纵向跨度，此后「显/隐」切换只让显示区伸缩，
    //   交界线不动、底栏不动；锁定值与绝对格子尺寸一起持久化，重启后原样恢复。
    private fun applyDaMei() {
        if (!::chessBoard.isInitialized || !::frameBoardOuter.isInitialized) return
        val w = chessBoard.width
        val h = chessBoard.height
        if (w <= 0 || h <= 0) { frameBoardOuter.post { applyDaMei() }; return }   // 布局未就绪，稍后重试
        try {
            // ★★ 大而美归一化：先把棋子拉到健康参考值(≈103%，直径≈0.97格留小空隙)——无论用户之前怎么乱调
            //    棋盘/棋子（哪怕挤成烙饼、面目全非），一键后都是「最大且不叠」的
            //    匀称比例：半边4.5颗直径、互不挤蹭、不顶边框。
            chessBoard.setPieceScale(ChessBoardView.HEALTHY_PIECE_SCALE)

            // ★ 全屏方案（底部锚定）：棋盘视图占满整屏，在「顶栏贴纸底 ↔ 主菜单贴纸顶」可视带内
            //    求「无爆屏最大格子」；横向/纵向两约束取小（纵向含顶部黑子/标签、底部标签外延，
            //    与 ChessBoardView 底部锚定的上限公式一致）。带高取可视带，大而美绝不把棋盘撑到贴纸下面。
            val ps = ChessBoardView.HEALTHY_PIECE_SCALE
            val rRatio = 0.47f * ps
            val edgeM = dp(8).toFloat()          // 左右各留小边距：最边棋子不顶屏幕
            val gapPx = 2f * resources.displayMetrics.xdpi / 25.4f   // 2 毫米（物理长度，与 ChessBoardView 一致）
            val cellByW = (w - 2f * edgeM) / (8f + 2f * rRatio)
            val bandH = (stickerBandBottom - stickerBandTop).takeIf { it > 0 } ?: h.toFloat()
            // 顶部外延：黑子半径 与 上方刻度标签 取大，再叠加 0.10 格安全净空（与 ChessBoardView 一致）
            val kTop = maxOf(rRatio + 0.10f, 0.60f)
            val kBot = 0.62f
            val cellByH = (bandH - 2f * gapPx) / (9f + kTop + kBot)
            val maxCell = minOf(cellByW, cellByH)

            // 3) 应用绝对格子尺寸（棋子大小保持不变，棋盘随它换算）
            chessBoard.setAbsoluteCellSize(maxCell)

            // 4) 全屏方案：棋盘视图本就占满整屏、按 9×10 自适应居中，无需再锁外罩高度；
            //    仅记录锁定标志供滑条调节时退出锁定语义；重启后棋盘自动恢复全屏最大适配。
            dameiLocked = true
            dameiBoardH = 0f
            dameiMenuH = 0f
            saveAllSettings()
            Toast.makeText(this, "大而美：棋盘棋子已最大化且不爆屏（显/隐切换大小不变）", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "applyDaMei failed: ${e.message}")
        }
    }

    /**
     * ★★ 大而美的「静默复刻」：仅把「最大且不爆屏」的绝对格子像素重新钉上，
     *    供重启后把大而美锁定的棋盘尺寸原样找回。
     *    - 不改棋子大小、不弹 Toast、不改 dameiLocked 标志——只重算并下钉 maxCell。
     *    - 依赖可视带 stickerBand 已定（computeContentBand 成功后才调用）。
     */
    private fun applyDaMeiBandOnly() {
        if (!::chessBoard.isInitialized) return
        val w = chessBoard.width
        if (w <= 0) return   // 布局未就绪，保留标记，下次带定后重试
        try {
            val ps = chessBoard.getPieceScale()
            val rRatio = 0.47f * ps
            val edgeM = dp(8).toFloat()                 // 左右各留小边距：最边棋子不顶屏幕
            val gapPx = 2f * resources.displayMetrics.xdpi / 25.4f   // 2 毫米（与 ChessBoardView 一致）
            val cellByW = (w - 2f * edgeM) / (8f + 2f * rRatio)
            val bandH = (stickerBandBottom - stickerBandTop).takeIf { it > 0f } ?: chessBoard.height.toFloat()
            val kTop = maxOf(rRatio + 0.10f, 0.60f)   // 与 ChessBoardView 顶排锁定公式一致（含 0.10 格安全净空）
            val kBot = 0.62f
            val cellByH = (bandH - 2f * gapPx) / (9f + kTop + kBot)
            chessBoard.setAbsoluteCellSize(minOf(cellByW, cellByH))
            saveAllSettings()   // 把钉回的绝对格子同步落盘，彻底杜绝"每次重启都走锚点兜底"
        } catch (e: Exception) {
            Log.e(TAG, "applyDaMeiBandOnly failed: ${e.message}")
        }
    }

    // ★ 视觉Tab「棋盘/棋子大小」横屏滑条弹窗：平时面板只留一个「棋盘/棋子」按钮，不占面板空间；
    //   点开弹窗后用横排 SeekBar 划动，每划一下即所见即所得地改棋盘/棋子大小并重算棋盘外罩；
    //   「确认」收起保存、「取消」恢复原值。
    private fun showScaleDialog(title: String, isBoard: Boolean) {
        // ★★★ 手动调节 = 退出大而美锁定（边界恢复灵活，随滑条所见即所得）；再点「大而美」可重新锁定
        if (dameiLocked) {
            dameiLocked = false
            dameiBoardH = 0f
            saveAllSettings()
        }
        val prev = if (isBoard) chessBoard.getBoardScale() else chessBoard.getPieceScale()
        val apply: (Float) -> Unit =
            if (isBoard) chessBoard::setBoardScale else chessBoard::setPieceScale
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(2))
            addView(scaleRow(title, prev, apply, isBoard))
            addView(TextView(this@MainActivity).apply {
                // ★ 棋子/棋盘分别如实说明：都先引导点击「大而美」保底；并点破"调完棋子却重启爆屏"
                //   这个普遍困惑 —— 根源在没把「棋盘」百分比落回 88%~89%，而不在棋子本身。
                text = if (!isBoard)
                    "先点旁边的\u201c大而美\u201d按钮，基本保证棋盘棋子呈现到位；此后若还想微调棋子大小，" +
                    "横向划动即所见即所得，点\u201c确认\u201d收起、点\u201c取消\u201d恢复原值。\n\n" +
                    "\u2605 注意：只调棋子、不动棋盘，当下那一两盘往往看着没问题，可一重启就可能盘面撑满、" +
                    "边线棋子溢出屏幕看不见——根源是\u201c棋盘\u201d百分比偏高，并不是棋子白调。" +
                    "所以调完棋子，请顺手打开\u201c棋盘\u201d，把百分比落到 88%~89%。"
                else
                    "点\u201c大而美\u201d按钮，可基本保证棋盘视觉质量；若要自行微调，横向划动、" +
                    "点\u201c确认\u201d后才能看到实际效果，请凭手感与预判来定。\n\n" +
                    "\u2605 重要：打开这里若发现数值高于 89%，请先不要关闭，把它调回 88%~89% —— 这是稳妥的上限。" +
                    "再往上，重启后边线棋子会溢出屏幕。各机型屏幕大小略有差别，这个范围不完全一致，但总体偏差不大。"
                setTextColor(0xFF9A8262.toInt())
                textSize = 11f
                setPadding(0, dp(6), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(title + "大小")
            .setView(box)
            .setPositiveButton("确认") { d, _ -> saveAllSettings(); d.dismiss() }
            .setNegativeButton("取消") { _, _ ->
                if (isBoard) { chessBoard.setBoardScale(prev); boardLockedToHeight = -1 }
                else chessBoard.setPieceScale(prev)
                if (::frameBoardOuter.isInitialized) frameBoardOuter.post { tuneBoardVsThinking() }
                saveAllSettings()   // ★ 取消还原后同步落盘，保证与"拖动即确认"的存档一致
            }
            .show()
    }

    /** ★ 解说字号应用：解说悬浮弹窗（人话/现世报）字号跟随滑杆 */
    private fun applyCommentFont() {
        if (::tvCommentaryFloat.isInitialized) {
            tvCommentaryFloat.textSize = commentFontSp.coerceIn(8f, 32f)
        }
    }

    /** ★★ 棋桌·刻度 一体调节板（点「棋桌」按钮打开）：
     *    ① 棋桌风格 —— 色圈网格一排3个，点圈即生效、无需确认（弹窗不关闭，可继续调刻度色）；
     *    ② 刻度颜色 —— 色板取色，选好点「确认刻度色」才应用并收起。
     *    ★ 刻度文字为铁例（红方中文/黑方阿拉伯），无任何样式选项。
     *    弹窗衬底用纯白纸，不灰不蒙。 */
    private fun showTableStyleDialog() {
        try {
            val styles = ChessBoardView.TableStyle.entries
            val cellViews = ArrayList<View>()        // 每格容器（刷新选中底色）
            val circleViews = ArrayList<View>()      // 每格色圈（刷新选中描边）
            val nameViews = ArrayList<TextView>()    // 每格名称（刷新选中变色）

            /** 刷新棋桌风格色圈网格的选中态 */
            fun refreshTableCells() {
                styles.indices.forEach { i ->
                    val sel = i == tableStyleIndex
                    val circle = circleViews.getOrNull(i)
                    circle?.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        val st = styles[i]
                        // ★ 花纹款用「底色→花纹墨色」的渐变圈暗示带花边；素面款仍是纯色圈
                        if (st.pattern != ChessBoardView.TablePattern.NONE) {
                            colors = intArrayOf(st.top, st.edge)
                            orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                        } else {
                            setColor(st.top)
                        }
                        setStroke(dp(if (sel) 3 else 1), if (sel) 0xFFFFB300.toInt() else 0xFFCCB080.toInt())
                    }
                    cellViews.getOrNull(i)?.background = ColorDrawable(
                        if (sel) 0x1AB3541E.toInt() else 0x00000000)
                    nameViews.getOrNull(i)?.setTextColor(
                        if (sel) 0xFFB3541E.toInt() else 0xFF3A2B1C.toInt())
                }
            }

            /** 小节标题：深琥珀小字，不占行高 */
            fun sectionLabel(t: String) = TextView(this).apply {
                text = t
                textSize = 12f
                setTextColor(0xFF8A6D3B.toInt())
                setPadding(dp(4), dp(7), dp(4), dp(2))
            }

            // ── ① 棋桌风格：一排3个色圈网格（点圈即生效，不关弹窗） ──
            val grid = android.widget.GridLayout(this).apply {
                columnCount = 3
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            styles.forEachIndexed { i, s ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(2), dp(3), dp(2), dp(3))
                    isClickable = true
                    isFocusable = true
                }
                val circle = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                }
                val name = TextView(this).apply {
                    text = s.label
                    textSize = 10.5f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(2), 0, 0)
                    maxLines = 1
                }
                cell.addView(circle)
                cell.addView(name)
                val lp = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(i / 3, 1f),
                    android.widget.GridLayout.spec(i % 3, 1f)
                )
                lp.width = 0
                lp.height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                cell.layoutParams = lp
                val idx = i
                cell.setOnClickListener {
                    tableStyleIndex = idx
                    chessBoard.setTableStyle(styles[idx])
                    btnTableStyle.text = "棋桌:${styles[idx].label}"
                    // ★ 用户亲手挑了：立个"已挑过"记号，此后永远依他存的那一档，不再回到默认
                    prefs.edit().putBoolean(KEY_TABLE_STYLE_PICKED, true).apply()
                    saveAllSettings()
                    refreshTableCells()
                }
                cellViews.add(cell)
                circleViews.add(circle)
                nameViews.add(name)
                grid.addView(cell)
            }
            refreshTableCells()

            // ── ② 刻度颜色：色板取色，选好点「应用」才生效 ──
            //    ★ 刻度文字为铁例（红方中文/黑方阿拉伯），无样式选项、无示例文字、无多余说明。
            val picker = com.pikafish.chess.ui.HsvColorPicker(this).apply { setColor(labelColor) }

            // 组装：棋桌风格 + 刻度颜色两段（刻度段只保留「刻度颜色」四个字 + 调色板，
            //         不加任何说明文字），整块包进 ScrollView（上限约 72% 屏高，绝不顶出屏幕） ──
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
                addView(sectionLabel("棋桌风格"))
                addView(grid)
                addView(sectionLabel("刻度颜色"))
                addView(picker, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))
            }
            val scroll = ScrollView(this).apply {
                addView(content)
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            val dlg = AlertDialog.Builder(this)
                .setTitle("棋桌·刻度")
                .setView(scroll)
                .setPositiveButton("应用") { _, _ ->
                    labelColor = picker.color
                    chessBoard.setLabelColor(labelColor)
                    saveAllSettings()
                }
                .setNegativeButton("取消", null)
                .create()
            // ★ 纯白纸衬底：不灰不蒙、清清楚楚（与原设置纸张弹窗一致）
            try { dlg.window?.setBackgroundDrawable(resources.getDrawable(R.drawable.bg_settings_paper, theme)) } catch (_: Exception) {}
            dlg.window?.setDimAmount(0.18f)
            dlg.setOnShowListener {
                dlg.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.94).toInt(),
                    (resources.displayMetrics.heightPixels * 0.72).toInt())
            }
            dlg.show()
        } catch (e: Exception) {
            Log.e(TAG, "棋桌·刻度弹窗失败: ${e.message}", e)
        }
    }

    /** 一根横排滑条：标签 + 横向 SeekBar + 中文百分比。划动即应用（所见即所得），实时刷新百分比。 */
    private fun scaleRow(label: String, cur: Float, apply: (Float) -> Unit, isBoard: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(0xFF3A2B1C.toInt())
            textSize = 14f
            width = dp(44)
            setPadding(0, 0, dp(4), 0)
        })
        val valueTv = TextView(this).apply {
            text = "${(cur.coerceIn(0.5f, 2.0f) * 100).toInt()}%"
            setTextColor(0xFF7A6A52.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), 0, 0, 0)
        }
        val sb = SeekBar(this)
        // ★ 棋子滑条上限 = 150%（请求值；渲染时棋子相对格子仍封顶 ≈103% 不叠烙饼，
        //   超出部分转为棋盘向外扩张——原地对称放大、可爆屏，滑条全程"拖了就有反应"）；
        //   棋盘滑条仍为 50%~200%。
        val maxP = if (isBoard) 150 else ((ChessBoardView.MAX_PIECE_INPUT_SCALE * 100).toInt() - 50)
        sb.max = maxP
        sb.progress = ((cur.coerceIn(0.5f, if (isBoard) 2.0f else ChessBoardView.MAX_PIECE_INPUT_SCALE) * 100).toInt() - 50).coerceIn(0, maxP)
        sb.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFB300.toInt())
        sb.progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFB300.toInt())
        sb.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33663200.toInt())
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val scale = (progress + 50) / 100f
                apply(scale)
                // ★ 棋盘滑条改变理想高度 → 解除之前的锁定，让棋盘按新档位重新收敛；
                //   棋子滑条只改棋子大小、不改棋盘外框，无需解锁。
                if (isBoard) {
                    boardLockedToHeight = -1
                    // ★ 用户亲手拖过棋盘滑杆：立个"已拖过"记号，此后永远依他存的那一档
                    prefs.edit().putBoolean(KEY_BOARD_SCALE_PICKED, true).apply()
                }
                // 显示生效后的真实值（棋子超过健康上限103%时封顶显示，所见即所得）
                val eff = if (isBoard) chessBoard.getBoardScale() else chessBoard.getPieceScale()
                valueTv.text = "${(eff * 100).toInt()}%"
                if (::frameBoardOuter.isInitialized) frameBoardOuter.post { tuneBoardVsThinking() }
                // ★★★ 「拖动即确认」：滑到哪、后台就当点了确认（立刻应用并落盘锁值），
                //   但弹窗先不关——等用户真点"确认"才关闭；点"取消"则还原后同样落盘。
                saveAllSettings()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        row.addView(sb)
        row.addView(valueTv)
        return row
    }

    // ══════════════════════════════════════════════════════════════
    //  ★★ 外置引擎（「外置引擎」按钮）：装载 / 卸回 + 引擎特征气泡
    //
    //  用户点按钮选一个 ZIP（例如 Pikafish260925.zip，内含 Pikafish + Pikafish.nnue），
    //  UserEngineStore 负责解压/校验（ELF 可执行 + .nnue 权重）/落到 App 私有目录，
    //  并启动一次子进程跑 UCI 握手，读引擎自报的 "id name / id author"。
    //  装载完成后这里即时重启"算棋通道"（主界面对弈唯一通道），全局即刻换引擎；
    //  顶栏、人话解说随之改名（认识的说中文，不认识的说英文原名）。
    // ══════════════════════════════════════════════════════════════
    private var engineBusy = false
    private var engineBubble: AlertDialog? = null

    /** 点「外置引擎」：弹系统文件选择器（ZIP）。 */
    private fun pickExternalEngineZip() {
        if (engineBusy) { Toast.makeText(this, "引擎正忙，请稍候…", Toast.LENGTH_SHORT).show(); return }
        try {
            externalEngineLauncher.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
            )
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 装载：后台解压/校验/探明身份 → 主线程重启算棋通道 + 刷新顶栏 + 弹引擎特征气泡。 */
    private fun installExternalEngine(uri: Uri) {
        if (engineBusy) return
        engineBusy = true
        tvStatus.text = "正在装载外置引擎…"
        Toast.makeText(this, "正在装载外置引擎（解压并探明身份）…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val res = com.pikafish.chess.engine.UserEngineStore.install(applicationContext, uri)
            withContext(Dispatchers.Main) {
                engineBusy = false
                if (!res.ok) {
                    tvStatus.text = "外置引擎装载失败"
                    try {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("外置引擎装载失败")
                            .setMessage(res.message + "\n\n请确认 ZIP 内含安卓可执行的引擎二进制（ELF）与 .nnue 权重文件。")
                            .setPositiveButton("知道了", null)
                            .show()
                    } catch (_: Exception) {}
                    return@withContext
                }
                Toast.makeText(this@MainActivity, "${res.message}，正在重启引擎…", Toast.LENGTH_SHORT).show()
                reloadEngineForExternal { ok ->
                    updateEngineInfo()
                    applyEngineHealthText()
                    if (ok) {
                        showEngineFeatureBubble()
                    } else {
                        Toast.makeText(this@MainActivity, "引擎重启失败，请点「恢复」再试", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** 长按「外置引擎」：卸回内置皮卡鱼（清掉用户引擎目录）。 */
    private fun confirmUninstallExternalEngine() {
        if (!com.pikafish.chess.engine.UserEngineStore.isActive(this)) {
            Toast.makeText(this, "当前就是内置皮卡鱼引擎", Toast.LENGTH_SHORT).show()
            return
        }
        val cur = com.pikafish.chess.engine.EngineIdentity.shortName
        try {
            AlertDialog.Builder(this)
                .setTitle("卸回内置引擎")
                .setMessage("当前正在使用外置引擎：$cur\n\n卸载后全局回退内置皮卡鱼，重启引擎即刻生效。")
                .setNegativeButton("取消", null)
                .setPositiveButton("卸回内置") { _, _ -> uninstallExternalEngine() }
                .show()
        } catch (_: Exception) {}
    }

    private fun uninstallExternalEngine() {
        com.pikafish.chess.engine.UserEngineStore.clear(applicationContext)
        Toast.makeText(this, "已卸回内置皮卡鱼，正在重启引擎…", Toast.LENGTH_SHORT).show()
        reloadEngineForExternal { ok ->
            updateEngineInfo()
            applyEngineHealthText()
            if (ok) showEngineFeatureBubble()
            else Toast.makeText(this, "引擎重启失败，请点「恢复」再试", Toast.LENGTH_LONG).show()
        }
    }

    /** 重启算棋通道，让新引擎全局即时生效。
     *  ★ 诚实兜底：明明选了外置引擎、实际跑起来的却是内置皮卡鱼（外置进程起不来被自动回退），
     *    必须当场说清楚，绝不悄悄换芯。常见原因：引擎是 32 位 ARM 而本机无 32 位运行库。 */
    private fun reloadEngineForExternal(onDone: (Boolean) -> Unit) {
        try {
            chessGame.engine.reloadForExternalEngine { ok ->
                runOnUiThread {
                    if (ok && UserEngineStore.isActive(this) &&
                        !com.pikafish.chess.engine.EngineIdentity.external
                    ) {
                        Toast.makeText(
                            this,
                            "外置引擎在本机跑不起来，已回退内置皮卡鱼。\n" +
                                "常见原因：该引擎是 32 位 ARM（本机不支持 32 位运行库），或文件不可执行。",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onDone(ok)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "重启引擎失败: ${e.message}", e)
            onDone(false)
        }
    }

    /**
     * ★★★ 引擎特征气泡：直接报引擎"内部真实的本质"——UCI 自报的 id name / id author、
     *  来源（内置/外置＋包名）、构建日期、引擎与权重文件名及体积。
     *  弹完几秒自动关闭，无需用户点关闭。身份未就绪时会稍等重试，绝不错报。
     */
    private fun showEngineFeatureBubbleWhenReady(attempt: Int = 0) {
        val id = com.pikafish.chess.engine.EngineIdentity
        if (!id.known && attempt < 4) {
            playHandler.postDelayed({ showEngineFeatureBubbleWhenReady(attempt + 1) }, 900)
            return
        }
        showEngineFeatureBubble()
    }

    private fun showEngineFeatureBubble() {
        try { engineBubble?.dismiss() } catch (_: Exception) {}
        val id = com.pikafish.chess.engine.EngineIdentity
        val text = id.featureText()
        val title = if (id.external) "外置引擎特征" else "内置引擎特征"
        try {
            val dlg = AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setCancelable(true)
                .create()
            dlg.setCanceledOnTouchOutside(true)
            dlg.show()
            engineBubble = dlg
            // ★ 弹完自动关闭：不用用户自己去点关闭
            playHandler.postDelayed({
                try { if (dlg.isShowing) dlg.dismiss() } catch (_: Exception) {}
            }, 4200)
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════
    //  ★★ 引擎列表 / 引擎 PK（红黑各一台引擎，全自动对弈测试）
    //
    //  「引擎列表」：总览全部引擎（内置默认＋已装外置）。勾「采用」= 全局用这台
    //  （主界面/解惑/分析/复盘全走它）；勾「红」「黑」= 挑两台不同引擎做全自动 PK。
    //  采用 与 测试 两个片区互斥：以最后一次确认的为准。
    //
    //  PK 走到终局 → 专用弹窗：记成绩（胜X n/负X n/和X n），并用「四勾」挑下一局谁执红
    //  谁执黑（一红一黑、错开勾）；勾「采用」则该片区只剩一个「确认并结束测试」。
    // ══════════════════════════════════════════════════════════════

    /** PK 是否进行中（界面据此走 PK 终局弹窗；引擎路由由 SandboxOracle.pkMode 负责） */
    private var pkRunning = false
    private var pkSavedRedHuman = true
    private var pkSavedBlackHuman = false

    /** 点「引擎列表」：总览全部引擎，可「采用」全局生效，或勾红/黑两台做引擎 PK 测试。
     *  ★ PK 进行中点进来【绝不硬拦】：先告知「本局无结果」仍放行，让人能进列表重选/采用/继续。
     *    单机游戏以自由流畅为先，任何时候都进得去，只是提醒一句这一盘没有结果可汇总。 */
    private fun showEngineListDialog() {
        if (pkRunning) {
            try {
                AlertDialog.Builder(this)
                    .setTitle("引擎测试进行中")
                    .setMessage("目前对弈棋局半途中断，无法判定该局胜负引擎情况，请重新开始选择采用的引擎或继续测试。")
                    .setNegativeButton("继续测试", null)
                    .setPositiveButton("选择引擎") { _, _ -> showEngineListDialogInner(skipSave = true) }
                    .show()
            } catch (_: Exception) { showEngineListDialogInner(skipSave = true) }
            return
        }
        showEngineListDialogInner(skipSave = false)
    }

    /**
     * ★ 点「引擎列表」之前，先把「设置纸张弹窗」（那张摊开的设置总览纸）老老实实收回去。
     *   缘由：这张纸正儿八经的退场路径是「点它外面的空白处」；可引擎列表一弹出来就盖在它头上，
     *   那一纸就再也接不到「点外面」那一下，退不了场、只能被夹在中间，最后阴魂不散地
     *   化作帅前面那个「放大镜似的白光点」残影。
     *   所以抢在引擎列表弹出【之前】关掉它：走正常关闭路径，挪出去的面板原样归还、零残留。
     */
    private fun retreatSettingsPaper() {
        val d = settingsDialog ?: return
        try {
            if (d.isShowing) d.dismiss() else restoreSettingsPanels()
        } catch (_: Exception) {
            try { restoreSettingsPanels() } catch (_: Exception) {}
        }
        settingsDialog = null
    }

    private fun showEngineListDialogInner(skipSave: Boolean) {
        retreatSettingsPaper()   // ★ 先把那张设置纸收回去，别让它被压在引擎列表底下退不了场
        EngineListDialog(
            this,
            onAdopt = { entry ->
                // ★ 从 PK 里点「采用」：先收摊退出测试，再全局采用这台（两个片区互斥，采用算数）
                if (pkRunning) exitPk()
                UserEngineStore.setActive(applicationContext, entry.id)
                reloadEngineForExternal { ok ->
                    updateEngineInfo()
                    applyEngineHealthText()
                    if (ok) showEngineFeatureBubble()
                    else Toast.makeText(this, "引擎切换失败，请重试", Toast.LENGTH_SHORT).show()
                }
            },
            onStartPk = { red, black -> confirmStartPk(red, black, skipSave) },
            // ★ PK 中从挡屏进来重选：确认钮说「按已选好的继续」
            confirmLabel = if (skipSave) "按已选好的继续" else "确定"
        ).show()
    }

    /** PK 开始前确认：有未完成对局先强制保存；弹「取消 / 开始」。
     *  skipSave=true（PK 中重选）时不动主界面存档——那盘是 PK 局，无结果也不该写进人家存档。 */
    private fun confirmStartPk(
        red: UserEngineStore.Entry,
        black: UserEngineStore.Entry,
        skipSave: Boolean = false
    ) {
        val midGame = try { chessGame.getMoveCount() > 0 } catch (_: Exception) { false }
        if (midGame && !skipSave) {
            // ★ 有半腰上的局面：强制保存原来主界面未完成的内容文件，让用户安心开局
            try { saveCurrentGameToVault() } catch (_: Exception) {}
            try { saveGameStateFull() } catch (_: Exception) {}
        }
        val head = if (midGame && !skipSave)
            "比赛即将开始，已为你保存了原来主界面未完成的内容文件。\n\n"
        else
            "双方引擎已设置完毕，准备正式开始。\n\n"
        try {
            AlertDialog.Builder(this)
                .setTitle("引擎 PK 测试")
                .setMessage(head + "红方：${red.displayName}\n黑方：${black.displayName}\n\n"
                    + "红方引擎走红、黑方引擎走黑，全自动对弈。开始前请确认采用片区未启用。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始") { _, _ -> launchPk(red, black) }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "PK 确认弹窗失败: ${e.message}", e)
        }
    }

    /** 正式开始 PK：拉起红黑两台隔离引擎 → 收起面板 → 开一局全自动对弈。 */
    private fun launchPk(red: UserEngineStore.Entry, black: UserEngineStore.Entry) {
        tvStatus.text = "正在启动 PK 双引擎…"
        CoroutineScope(Dispatchers.IO).launch {
            val err = SandboxOracle.enterPk(
                applicationContext, red, black,
                chessGame.currentHash, chessGame.currentThreads
            )
            withContext(Dispatchers.Main) {
                if (err != null) {
                    tvStatus.text = "PK 启动失败"
                    Toast.makeText(this@MainActivity, "启动 PK 失败：$err", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                if (!pkRunning) {
                    pkSavedRedHuman = chessGame.redIsHuman
                    pkSavedBlackHuman = chessGame.blackIsHuman
                }
                pkRunning = true
                collapseAllPanels()
                chessBoard.clearSelection()
                chessBoard.clearHighlights()
                chessBoard.setFlipped(false)
                chessGame.startPkMatch()
                // ★ 亮起透明挡屏：全自动对弈期间盖住菜单栏，看得见按键但点不到，防误触打断引擎
                showPkShield()
                tvStatus.text = "引擎 PK：${red.displayName}（红） vs ${black.displayName}（黑）"
                Toast.makeText(this@MainActivity,
                    "PK 开始：${red.displayName} vs ${black.displayName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** PK 续弈：同一对引擎就换边就地续，否则重起。 */
    private fun continuePk(red: UserEngineStore.Entry, black: UserEngineStore.Entry) {
        if (SandboxOracle.respinPk(red, black)) {
            chessGame.startPkMatch()
            tvStatus.text = "引擎 PK：${red.displayName}（红） vs ${black.displayName}（黑）"
            // ★ 换边后重铺挡屏：档屏上的「红方 X VS 黑方 Y」跟着换边走（顺带冲掉聚光点）
            showPkShield()
            return
        }
        launchPk(red, black)
    }

    /** PK 单局终局：记成绩 → 弹「四勾换边续弈 / 采用并结束」弹窗。 */
    private fun showPkEndDialog(result: String) {
        val redId = SandboxOracle.pkRedId
        val blackId = SandboxOracle.pkBlackId
        val redName = SandboxOracle.pkRedLabel.ifBlank { "红方引擎" }
        val blackName = SandboxOracle.pkBlackLabel.ifBlank { "黑方引擎" }
        // ★ 胜负判定（象棋规则：困毙判负）：红方胜 或 黑方困毙 → 红胜；
        //   黑方胜 或 红方困毙 → 黑胜；其余（和棋等）判和。绝不把困毙记反。
        val redWon = result.contains("红方胜") || result.contains("黑方困毙")
        val blackWon = result.contains("黑方胜") || result.contains("红方困毙")
        // ★ 记成绩（本引擎视角：胜/负/和），没结果的绝不记
        try {
            when {
                redWon -> UserEngineStore.recordResult(this, redId, blackId, UserEngineStore.Outcome.WIN)
                blackWon -> UserEngineStore.recordResult(this, blackId, redId, UserEngineStore.Outcome.WIN)
                else -> UserEngineStore.recordResult(this, redId, blackId, UserEngineStore.Outcome.DRAW)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PK 记成绩失败: ${e.message}")
        }
        val outcomeLine = when {
            redWon -> "红方「$redName」胜，黑方「$blackName」负。"
            blackWon -> "黑方「$blackName」胜，红方「$redName」负。"
            else -> "红方「$redName」与黑方「$blackName」和棋。"
        }

        // 四勾状态：初始维持本局顺序（红勾红引擎，黑勾黑引擎）
        var redChoice = redId
        var blackChoice = blackId
        var adoptChoice = ""
        var syncing = false

        val pad = dp(10)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val nameColor = Color.parseColor("#2B2118")
        val subColor = Color.parseColor("#7A6A55")
        val winColor = Color.parseColor("#2E6B4F")

        lateinit var dlg: androidx.appcompat.app.AlertDialog

        fun toggleRed(id: String, checked: Boolean) {
            redChoice = if (checked) id else if (redChoice == id) "" else redChoice
            if (checked && blackChoice == id) blackChoice = ""   // 同一台不能又红又黑
        }
        fun toggleBlack(id: String, checked: Boolean) {
            blackChoice = if (checked) id else if (blackChoice == id) "" else blackChoice
            if (checked && redChoice == id) redChoice = ""       // 同一台不能又红又黑
        }
        fun toggleAdopt(id: String, checked: Boolean) {
            adoptChoice = if (checked) id else if (adoptChoice == id) "" else adoptChoice
        }

        var rebuild: () -> Unit = {}

        fun engineRow(id: String, name: String, isRedNow: Boolean): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = name + if (isRedNow) "（本局执红）" else "（本局执黑）"
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(nameColor)
            })
            val boxes = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, 0)
            }
            boxes.addView(CheckBox(this).apply {
                text = "红"
                textSize = 15f
                setTextColor(nameColor)
                isChecked = redChoice == id
                setOnCheckedChangeListener { _, c ->
                    if (syncing) return@setOnCheckedChangeListener
                    toggleRed(id, c); rebuild()
                }
            })
            boxes.addView(CheckBox(this).apply {
                text = "黑"
                textSize = 15f
                setTextColor(nameColor)
                isChecked = blackChoice == id
                setOnCheckedChangeListener { _, c ->
                    if (syncing) return@setOnCheckedChangeListener
                    toggleBlack(id, c); rebuild()
                }
            })
            boxes.addView(CheckBox(this).apply {
                text = "采用"
                textSize = 15f
                setTextColor(if (adoptChoice == id) winColor else nameColor)
                isChecked = adoptChoice == id
                setOnCheckedChangeListener { _, c ->
                    if (syncing) return@setOnCheckedChangeListener
                    toggleAdopt(id, c); rebuild()
                }
            })
            row.addView(boxes)
            return row
        }

        rebuild = {
            syncing = true
            body.removeAllViews()
            body.addView(TextView(this).apply {
                text = outcomeLine + "\n" + result
                textSize = 15f
                setTextColor(nameColor)
            })
            body.addView(TextView(this).apply {
                text = "勾「红」「黑」定下一局谁执红谁执黑（一红一黑、错开勾）；勾「采用」则全局启用该引擎并结束测试。"
                textSize = 15f
                setTextColor(subColor)
                setPadding(0, dp(4), 0, dp(2))
            })
            body.addView(engineRow(redId, redName, redChoice == redId))
            body.addView(engineRow(blackId, blackName, blackChoice == blackId))
            val redSel = UserEngineStore.entry(this, redChoice)?.displayName ?: "未选"
            val blackSel = UserEngineStore.entry(this, blackChoice)?.displayName ?: "未选"
            body.addView(TextView(this).apply {
                text = "红方：$redSel    黑方：$blackSel"
                textSize = 15f
                setTextColor(winColor)
                setPadding(0, dp(4), 0, 0)
            })
            syncing = false
            // ★ 勾了「采用」→ 下面菜单栏只剩一个「确认并结束测试」
            val adopting = adoptChoice.isNotEmpty()
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text =
                if (adopting) "确认并结束测试" else "确认（下一局）"
            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).visibility =
                if (adopting) View.GONE else View.VISIBLE
        }

        fun onConfirm() {
            // ★ 无差别存谱：不管点「确认并结束测试」还是「确认（下一局）」，先把这盘存进宝库
            savePkGameToVault()
            if (adoptChoice.isNotEmpty()) {
                val e = UserEngineStore.entry(this, adoptChoice)
                dlg.dismiss()
                exitPk()
                if (e != null) {
                    UserEngineStore.setActive(applicationContext, e.id)
                    Toast.makeText(this, "已采用「${e.displayName}」并结束 PK 测试", Toast.LENGTH_SHORT).show()
                    reloadEngineForExternal { ok ->
                        updateEngineInfo(); applyEngineHealthText()
                        if (ok) showEngineFeatureBubble()
                        else Toast.makeText(this, "引擎切换失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
            val red = UserEngineStore.entry(this, redChoice)
            val black = UserEngineStore.entry(this, blackChoice)
            when {
                red == null && black == null ->
                    toast("请为红黑双方各勾一台引擎：红方引擎 / 黑方引擎")
                red == null ->
                    toast("红方引擎还没勾，请再勾一台红方引擎")
                black == null ->
                    toast("黑方引擎还没勾，请再勾一台黑方引擎")
                red.id == black.id ->
                    toast("红黑必须是两台不同的引擎，请错开勾")
                else -> {
                    dlg.dismiss()
                    continuePk(red, black)
                }
            }
        }

        dlg = AlertDialog.Builder(this)
            .setTitle("引擎 PK · 本局结束")
            .setView(ScrollView(this).apply {
                addView(body)
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
            })
            .setNegativeButton("结束测试", null)
            .setPositiveButton("确认（下一局）", null)
            .create()
        dlg.setCancelable(false)
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { onConfirm() }
            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                // ★ 无差别存谱：点「结束测试」也先把这盘存进宝库，再收摊
                savePkGameToVault()
                dlg.dismiss(); exitPk()
            }
            rebuild()
        }
        dlg.show()
        // ★ 宽度弹开一点
        try {
            val dm = resources.displayMetrics
            dlg.window?.setLayout((dm.widthPixels * 0.96).toInt(), (dm.heightPixels * 0.72).toInt())
        } catch (_: Exception) {}
    }

    /** 收摊：退出 PK 模式，恢复常态人机设置与单引擎询招。 */
    private fun exitPk() {
        pkRunning = false
        hidePkShield()
        try { SandboxOracle.exitPk() } catch (_: Exception) {}
        chessGame.stopPkMatch()
        chessGame.redIsHuman = pkSavedRedHuman
        chessGame.blackIsHuman = pkSavedBlackHuman
        updateSideButtons()
        chessBoard.clearSelection()
        chessBoard.clearHighlights()
        chessBoard.viewingHistory = false
        chessGame.newGame()
        // ★ PK 期间红黑两台各自刷新过全局 EngineIdentity，退出时把顶栏按「采用」那台重新校准
        try { UserEngineStore.setActive(applicationContext, UserEngineStore.activeId(applicationContext)) } catch (_: Exception) {}
        updateEngineInfo()
        applyEngineHealthText()
        tvStatus.text = "已退出引擎 PK 测试"
    }

    // ══════════════════════════════════════════════════════════════
    //  ★★ PK 透明挡屏（防误触）
    //
    //  PK 全自动对弈期间，整屏罩一层【透明】挡屏：按键依然看得见，但一点也按不到
    //  （透明＝不遮视野、不造成恐慌；可点不到＝人机不串扰，红黑对磨不被打断）。
    //  罩上给出告示，并留三个出口：采用一台并退出 / 重选两台继续（套娃引擎列表）/
    //  结束测试。挡屏一直盖到本局终局弹窗出现；续弈一局就再盖回，直到收摊为止。
    // ══════════════════════════════════════════════════════════════

    /** PK 透明挡屏视图（为空表示未盖） */
    private var pkShield: View? = null

    /**
     * ★ 消掉残留「聚光点」（帅前面那个像放大镜聚出来的白光点）：
     *   用户实测——屏幕任意处点一下，那个光点就会消失。所以防护膜一罩上，
     *   就往【顶栏那一带】模拟手指快速点三下（左／中／右分布），
     *   走的是正常触摸分发链，等价于真人点一下，能把卡住的按压/高亮状态冲掉。
     *   ★ 只点屏幕最上方（y≈4%），绝碰不到下方菜单栏与「采用并退出」那排按钮；
     *     且这些点全落在透明薄膜上，本来就只会被薄膜吞掉，点哪里都不会有副作用。
     */
    private fun dismissGlintByTapping(shield: View) {
        shield.postDelayed({
            try {
                // 走 decorView 的正规分发链（等价真人点一下，会清掉残留的按压/高亮/浮层）
                val target: View = window?.decorView ?: shield
                val w = shield.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                val h = resources.displayMetrics.heightPixels
                val y = (h * 0.04f).coerceAtLeast(dp(8).toFloat())   // 顶栏那一行
                val xs = floatArrayOf(w * 0.16f, w * 0.50f, w * 0.84f)
                xs.forEachIndexed { i, x ->
                    shield.postDelayed({
                        try {
                            val t = android.os.SystemClock.uptimeMillis()
                            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
                            target.dispatchTouchEvent(down)
                            val up = MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, y, 0)
                            target.dispatchTouchEvent(up)
                            down.recycle(); up.recycle()
                        } catch (_: Exception) {}
                    }, (i * 60).toLong())
                }
            } catch (_: Exception) {}
        }, 200)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ★★★「观战」非无障碍连线 —— 芝麻开门入口：顶栏「哈希xx MB」处连点三下
    //   入口藏在不易误触的引擎信息处；连点 3 下（1.2s 内）即开观战连线：
    //   ① 先要「显示在其他应用上层」权限 → ② 申请一次录屏授权 →
    //   ③ 交给 YK 逐字移植的 LinkForegroundService 全屏自动识别 + 系统浮层连线。
    //   红必走/黑必走、红先/黑先、校准/框选/方案/信息/换引擎等都在浮层自带菜单里切。
    //   连线前先问一次「要不要开无障碍自动走子」（可选）：开了引擎自动替你落子，
    //   不开也照样连线，只降级为只识别分析、你自己手动落子。
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupSpectateEntry() {
        tvEngineInfo.setOnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - engTripleTapLastMs > 1200L) engTripleTapCount = 0
            engTripleTapLastMs = now
            engTripleTapCount++
            if (engTripleTapCount >= 3) {
                engTripleTapCount = 0
                // 芝麻开门：连点三下即开观战连线（先问一次要不要开无障碍自动走子）。
                startSpectateLink()
            }
        }
    }

    /**
     * 连线入口的前置一问（照 YK 象棋原样）：无障碍是可选能力——
     * 开了引擎能自动替你走子；不开也照常连线，框选/识别/小棋盘/引擎分析一律不变，
     * 只是不会自动走子，需要你自己手动落子。
     */
    private fun startSpectateLink() {
        if (LinkAccessibilityService.isEnabled(this)) {
            continueLink()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("无障碍权限（可选）")
            .setMessage(
                "开启无障碍后，引擎能自动替你走子。\n\n" +
                    "不开启也能正常连线：框选、识别、小棋盘与引擎分析全部照常，" +
                    "只是不会自动走子，需要你自己手动落子。"
            )
            .setPositiveButton("去开启（可自动走子）") { _, _ ->
                try {
                    spectateAccessibilityLauncher.launch(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "打开无障碍设置失败", e)
                    continueLink()
                }
            }
            .setNegativeButton("仅识别分析") { _, _ -> continueLink() }
            .show()
    }

    /** 芝麻开门后半程：悬浮窗权限就绪则申请录屏，否则先跳授权页，返回后自动继续。 */
    private fun continueLink() {
        // 确保 YK 连线的引擎驱动已就绪（首次会解压内置 Pikafish 并握手）。
        linkViewModel
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "观战需要「显示在其他应用上层」权限，请在设置里打开后返回", Toast.LENGTH_LONG).show()
            spectatePendingStart = true
            try {
                spectatePermLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                } catch (_: Exception) {
                    Toast.makeText(this, "无法打开悬浮窗权限设置页", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        // 悬浮窗权限就绪，随即申请一次录屏授权：授权后由前台服务自动定位棋盘 + 逐帧识别。
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            spectateProjectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "申请录屏授权失败", e)
            Toast.makeText(this, "申请录屏授权失败：" + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    // ══════════════ ★ PK 对阵档案（红方谁 VS 黑方谁） ══════════════
    //  一律据实：名称/构建日期取引擎自己 UCI 手报的 "id name"（内置那台读 PK 实例自报身份，
    //  绝不借全局那份，免得红黑串台）；作者/体积取导入时存下的清单条目。取不到就留空不写，
    //  绝不硬编一个年月日或体积来充数。

    /** 对阵一行：红方 X VS 黑方 Y；两侧引擎各是一条带下划线的热链接，点它弹该引擎的档案小窗。 */
    private fun pkMatchupSpannable(linkColor: Int): CharSequence {
        val redId = SandboxOracle.pkRedId
        val blackId = SandboxOracle.pkBlackId
        val red = pkSideTitle("红方", redId, SandboxOracle.pkRedSelfIdName)
        val black = pkSideTitle("黑方", blackId, SandboxOracle.pkBlackSelfIdName)
        val full = "$red　VS　$black"
        val ss = android.text.SpannableString(full)
        fun addLink(start: Int, end: Int, onClick: () -> Unit) {
            ss.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) = onClick()
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = linkColor
                    ds.isUnderlineText = true      // 下划线＝明摆着"这里还有文章"
                    ds.isFakeBoldText = true
                }
            }, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        addLink(0, red.length) {
            showPkEngineDetailDialog("红方", redId, SandboxOracle.pkRedSelfIdName, SandboxOracle.pkRedSelfAuthor)
        }
        addLink(full.length - black.length, full.length) {
            showPkEngineDetailDialog("黑方", blackId, SandboxOracle.pkBlackSelfIdName, SandboxOracle.pkBlackSelfAuthor)
        }
        return ss
    }

    /** 一侧的「名称 + 构建日期」；日期取不到就只报名称（绝不含糊硬编）。 */
    private fun pkSideTitle(side: String, id: String, selfIdName: String): String {
        val e = UserEngineStore.entry(this, id)
        val name = e?.displayName ?: if (id.isBlank()) "（未指定）" else id
        val raw = (e?.idName ?: "").ifBlank { selfIdName }
        val date = com.pikafish.chess.engine.EngineIdentity.findDate(
            raw.ifBlank { e?.engineFileName ?: "" }
        )
        return if (date.isNullOrBlank()) "$side $name" else "$side $name $date"
    }

    /**
     * ★ 点对阵热链接弹出的「引擎档案」小窗：名称/UCI 原名/构建日期/来源/作者/引擎与权重体积，
     *   右下角一个「知道了」关掉；点框外也关。数据一律据实，引擎没自报的就写"引擎未自报"。
     */
    private fun showPkEngineDetailDialog(side: String, id: String, selfIdName: String, selfAuthor: String) {
        val e = UserEngineStore.entry(this, id)
        if (e == null) { toast("$side 引擎未指定"); return }
        val raw = e.idName.ifBlank { selfIdName }
        val date = com.pikafish.chess.engine.EngineIdentity.findDate(raw.ifBlank { e.engineFileName })
        val author = e.author.ifBlank { selfAuthor }
        val sb = StringBuilder()
        sb.append("名称：").append(e.displayName)
        if (raw.isNotBlank() && raw != e.displayName) sb.append("（").append(raw).append("）")
        sb.append('\n')
        sb.append("构建日期：").append(date ?: "引擎未自报").append('\n')
        sb.append("来源：").append(if (e.isBuiltin) "内置引擎（App 自带）" else "外置引擎")
        if (!e.isBuiltin && e.packageName.isNotBlank()) sb.append("（").append(e.packageName).append("）")
        sb.append('\n')
        sb.append("作者：").append(author.ifBlank { "引擎未自报" }).append('\n')
        sb.append("引擎文件：").append(
            if (e.engineFileName.isNotBlank())
                e.engineFileName + if (e.engineSize > 0)
                    "（${com.pikafish.chess.engine.EngineIdentity.humanSize(e.engineSize)}）" else ""
            else "App 内置二进制"
        ).append('\n')
        sb.append("权重：").append(
            if (e.weightSize > 0)
                "${e.nnueFileName}（${com.pikafish.chess.engine.EngineIdentity.humanSize(e.weightSize)}）"
            else "内置权重"
        )
        // 点框外即关（AlertDialog 默认如此），右下角一个「知道了」
        AlertDialog.Builder(this)
            .setTitle("$side 引擎档案")
            .setMessage(sb.toString())
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 亮起透明挡屏：整屏一层透明薄膜罩住（连顶栏、菜单栏一起），看得见、点不到。
     *  罩上的「文案框」可整体上下拖，位置记忆常住 —— 免得遮住棋盘与思考细节那一行。 */
    private fun showPkShield() {
        // ★ 对阵是会变的（重选两台 / 换边续弈）：旧罩先撤、就地重建，
        //   让「红方 X VS 黑方 Y」的档案永远贴准当下这一对引擎——像膏药一样跟着对阵走。
        if (pkShield != null) hidePkShield()
        val root = findViewById<FrameLayout>(R.id.rootMain) ?: return

        // ── 全屏透明薄膜：必须给高 elevation —— 菜单栏自带 10~12dp 浮层，
        //    不给高浮起来就会「压不住」下边那排按钮（看得见却照样点得动）。
        val shield = object : FrameLayout(this) {
            // ★ 只吞掉「落到薄膜空白处」的触摸；绝不拦截下发——否则罩上那几个按钮自己也点不动
            override fun onTouchEvent(ev: MotionEvent): Boolean = true
        }.apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            // ★ 极浅透明膜：按键仍看得见，只是按不到
            background = ColorDrawable(Color.parseColor("#14000000"))
            elevation = dp(60).toFloat()
        }

        val gold = Color.parseColor("#FFF6E8D8")
        val dim = Color.parseColor("#E6E8D8C0")
        val frame = Color.parseColor("#FFB300")

        // ── 一整块「框」：标题＋文案＋提示＋按钮；描金边框＝这一片区可整体拖动 ──
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF01E1610.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), frame)
            }
            elevation = dp(62).toFloat()
        }
        // 可拖区：按住标题/文案/提示拖动 ＝ 整块上下滑（按钮照常点）
        val dragArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dragArea.addView(TextView(this).apply {
            text = "引擎测试中"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(gold)
        })
        dragArea.addView(TextView(this).apply {
            // ★ 「拖动提示」并入本段末尾（不再单独占一行）：省下的这一排正好让给下面的对阵档案
            text = "为避免误点击打断引擎，此窗口屏蔽操作；步时/层数请提前调妥" +
                "（此框可整体上下拖动，免得遮挡棋盘）。"
            textSize = 15f
            setTextColor(dim)
            setPadding(0, dp(4), 0, dp(3))
        })
        // ★ 对阵一行：「红方 X VS 黑方 Y」；两侧引擎名各是【带下划线的金色热链接】，
        //   点一下才弹它的「引擎档案」小窗（详情不在本框里展开，挡屏行高压到最省）。
        dragArea.addView(TextView(this).apply {
            text = pkMatchupSpannable(gold)
            textSize = 15f
            setTextColor(dim)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT      // 点链接不要整行变色块
            setPadding(0, dp(2), 0, dp(1))
        })
        panel.addView(dragArea)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        fun shieldBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 15f
            minWidth = 0
            minHeight = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) }
        }
        // ★ 无差别存谱：这排按钮点哪个都先把「当下这盘（走到哪算哪）」存进宝库，再干原本那件事
        btnRow.addView(shieldBtn("采用并退出") {
            savePkGameToVault()
            showPkAdoptAndExitDialog()
        })
        btnRow.addView(shieldBtn("重选两台") {
            savePkGameToVault()
            showEngineListDialogInner(skipSave = true)
        })
        btnRow.addView(shieldBtn("结束测试") {
            savePkGameToVault()
            exitPk()
            toast("已结束引擎 PK 测试")
        })
        panel.addView(btnRow)

        // ★ 宽度弹开：就用满「屏宽 − 两侧各 12dp」，别搞成窄窄一条
        shield.addView(panel, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(200)),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
        root.addView(shield, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        pkShield = shield
        shield.bringToFront()
        // ★ 罩上后立刻「模拟手指点三下顶栏」，冲掉残留在帅前面的聚光/白光点
        dismissGlintByTapping(shield)

        // ── 拖动：按住「文案框」整块滑（上下为主，左右也留点余地）；松手记位置 ──
        val loc = IntArray(2)
        shield.getLocationOnScreen(loc)
        var dX = 0f; var dY = 0f; var downX = 0f; var downY = 0f
        dragArea.setOnTouchListener { v, ev ->
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val pl = IntArray(2); panel.getLocationOnScreen(pl)
                    dX = ev.rawX - pl[0]; dY = ev.rawY - pl[1]
                    downX = ev.rawX; downY = ev.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.leftMargin = (ev.rawX - loc[0] - dX).toInt()
                        .coerceIn(0, (shield.width - panel.width).coerceAtLeast(0))
                    lp.topMargin = (ev.rawY - loc[1] - dY).toInt()
                        .coerceIn(0, (shield.height - panel.height).coerceAtLeast(0))
                    panel.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (Math.hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()) >= dp(6)) {
                        savePkShieldPos()
                    }
                    true
                }
                else -> true
            }
        }

        // ── 初始落位：默认让首行文案对齐「参数这一排」（设置/对弈/棋谱）上方，
        //    尽量靠下、错开棋盘；有记忆（上次拖到哪）就按记忆常住。 ──
        panel.post {
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            val maxTop = (shield.height - panel.height).coerceAtLeast(0)
            val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (p.contains(KEY_PK_SHIELD_X) && p.contains(KEY_PK_SHIELD_Y)) {
                lp.leftMargin = ((shield.width - panel.width).coerceAtLeast(0) * p.getFloat(KEY_PK_SHIELD_X, 0f)).toInt()
                lp.topMargin = (maxTop * p.getFloat(KEY_PK_SHIELD_Y, 0f)).toInt().coerceIn(0, maxTop)
            } else {
                val menuTop = findViewById<View>(R.id.layoutNormalControls)
                    ?.takeIf { it.visibility == View.VISIBLE }?.top ?: 0
                lp.leftMargin = ((shield.width - panel.width) / 2).coerceAtLeast(0)
                lp.topMargin = (if (menuTop > dp(24)) menuTop else shield.height - panel.height - dp(64))
                    .coerceIn(0, maxTop)
            }
            panel.layoutParams = lp
        }
    }

    /** 撤掉透明挡屏（文案框停靠位置已在 prefs，下回接着用）。 */
    private fun hidePkShield() {
        pkShield?.let { v -> try { (v.parent as? android.view.ViewGroup)?.removeView(v) } catch (_: Exception) {} }
        pkShield = null
    }

    /** 记住挡屏「文案框」停靠位置（相对整屏比例 0..1），下一局/重启不再重调 */
    private fun savePkShieldPos() {
        val shield = pkShield ?: return
        val panel = (shield as? android.view.ViewGroup)?.getChildAt(0) ?: return
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val fx = if (shield.width > panel.width) lp.leftMargin.toFloat() / (shield.width - panel.width) else 0f
        val fy = if (shield.height > panel.height) lp.topMargin.toFloat() / (shield.height - panel.height) else 0f
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_PK_SHIELD_X, fx).putFloat(KEY_PK_SHIELD_Y, fy).apply()
    }

    /** 挡屏出口①：挑一台引擎「采用并退出」（单选用一排小框勾）。 */
    private fun showPkAdoptAndExitDialog() {
        val engines = try { UserEngineStore.list(this) } catch (_: Exception) { emptyList() }
        if (engines.isEmpty()) { toast("暂无可用引擎"); return }
        var pick = UserEngineStore.activeId(this).ifBlank { engines.first().id }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val boxes = HashMap<String, CheckBox>(engines.size)
        for (e in engines) {
            val cb = CheckBox(this).apply {
                text = e.displayName + if (e.isBuiltin) "（默认）" else ""
                textSize = 16f
                setTextColor(Color.parseColor("#2B2118"))
                isChecked = e.id == pick
                setOnCheckedChangeListener { _, checked ->
                    if (!checked) return@setOnCheckedChangeListener
                    pick = e.id
                    for ((id, b) in boxes) b.isChecked = (id == pick)
                }
            }
            boxes[e.id] = cb
            list.addView(cb)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("采用一个引擎并退出")
            .setView(ScrollView(this).apply {
                addView(list)
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
            })
            .setNegativeButton("取消", null)
            .setPositiveButton("采用并退出", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val e = UserEngineStore.entry(this, pick)
                dlg.dismiss()
                exitPk()
                if (e != null) {
                    UserEngineStore.setActive(applicationContext, e.id)
                    Toast.makeText(this, "已采用「${e.displayName}」并结束测试", Toast.LENGTH_SHORT).show()
                    reloadEngineForExternal { ok ->
                        updateEngineInfo(); applyEngineHealthText()
                        if (ok) showEngineFeatureBubble()
                        else Toast.makeText(this, "引擎切换失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dlg.show()
        // ★ 宽度弹开一点
        try {
            val dm = resources.displayMetrics
            dlg.window?.setLayout((dm.widthPixels * 0.96).toInt(), (dm.heightPixels * 0.72).toInt())
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════
    //  ★★「棋盘坐标 / 组件坐标」透明浮条
    //    收拾顽固机型的残留问题：有的手机自适配后棋盘棋子仍被拉长一点，最下一排棋子
    //    微微"歊"到主菜单栏交界处。这靠改大小/比例容易越描越黑，此处干脆按住棋盘整体
    //    或底栏整体做纵向微调（不动任何棋子/格子尺寸），所见即所得、即拖即存。
    //    浮条做成「半透明贴纸」，只薄薄盖住棋盘上缘，绝不整屏糊黑、不挡走子。
    // ══════════════════════════════════════════════════════════════
    private var coordOverlayView: LinearLayout? = null     // ★★ 当前坐标浮条（若有）
    private var coordOverlayKind = -1                      // ★★ 当前浮条种类：0=棋盘 1=组件

    private fun toggleCoordPopup(kind: Int) {
        if (coordOverlayKind == kind && coordOverlayView != null) { dismissCoordPopup(); return }
        showCoordPopup(kind)
    }

    private fun dismissCoordPopup() {
        coordOverlayView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        coordOverlayView = null
        coordOverlayKind = -1
    }

    /** ★★「组件坐标」：主菜单底边距屏幕底(px)。改动后立刻重排底栏并重算棋盘可视带（棋盘跟随让位）。 */
    private fun applyMenuYOffset(px: Float) {
        menuYOffsetPx = px.coerceIn(180f, 640f)
        val menu = findViewById<android.widget.LinearLayout>(R.id.layoutNormalControls)
        try {
            val lp = menu.layoutParams as? android.widget.FrameLayout.LayoutParams
            if (lp != null && menu.isAttachedToWindow) {
                lp.bottomMargin = menuYOffsetPx.toInt()
                menu.layoutParams = lp
                menu.post { computeContentBand() }
            }
        } catch (_: Exception) { }
    }

    /** ★★ 弹一份坐标浮条。kind=0 棋盘坐标（整盘上下）、1 组件坐标（底栏上下）。半透明、可收起。 */
    private fun showCoordPopup(kind: Int) {
        dismissCoordPopup()
        val root = findViewById<android.widget.FrameLayout>(R.id.rootMain) ?: return
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 半透明深木底：能透过看到棋盘，又不至于看不清字；拒绝整屏糊黑
            setBackgroundColor(0xB5453527.toInt())
            elevation = dp(14).toFloat()
            setPadding(dp(12), dp(8), dp(12), dp(6))
        }

        // 标题行 + 点此收起
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        head.addView(TextView(this).apply {
            text = if (kind == 0) "棋盘坐标 · 整盘上下微调" else "组件坐标 · 底栏上下微调"
            setTextColor(0xFFFFE9C8.toInt())
            textSize = 13f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setPadding(0, 0, dp(12), 0)
        })
        head.addView(TextView(this).apply {
            text = "▲▼"
            setTextColor(0xFFFFC457.toInt())
            textSize = 13f
        })
        overlay.addView(head)

        // 滑条行：标签 + SeekBar + 值
        val isBoard = kind == 0
        val cur = if (isBoard) chessBoard.getBoardYOffset() else menuYOffsetPx
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val valueTv = TextView(this).apply {
            text = coordValueLabel(isBoard, cur)
            setTextColor(0xFFFFD58A.toInt())
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), 0, 0, 0)
        }
        val sb = SeekBar(this)
        // 两坐标都以 4px 为一步：棋盘 -2000..2000（共1000档，默认0跟可视带锚定），底栏 180..640（共115档）
        val stepPx = 4
        val (minV, maxV) = if (isBoard) (-ChessBoardView.MAX_Y_OFFSET_PX to ChessBoardView.MAX_Y_OFFSET_PX)
                           else (180f to 640f)
        val maxIdx = ((maxV - minV) / stepPx).toInt()
        sb.max = maxIdx
        sb.progress = (((cur.coerceIn(minV, maxV) - minV) / stepPx).toInt()).coerceIn(0, maxIdx)
        sb.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFC457.toInt())
        sb.progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFC457.toInt())
        sb.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55321D0A.toInt())
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = minV + progress * stepPx
                if (isBoard) chessBoard.setBoardYOffset(v) else applyMenuYOffset(v)
                valueTv.text = coordValueLabel(isBoard, v)
                saveAllSettings() // 拖动即确认：边拖边存档
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        // 标签
        row.addView(TextView(this).apply {
            text = if (isBoard) "整盘" else "底栏"
            setTextColor(0xFFBFA67E.toInt())
            textSize = 12f
            width = dp(36)
            setPadding(0, 0, dp(4), 0)
        })
        row.addView(sb)
        row.addView(valueTv)
        overlay.addView(row)

        // 说明 + 收起按钮
        overlay.addView(TextView(this).apply {
            text = if (isBoard)
                "拖动整盘（含棋子）上下让位，专治最下一排棋子微微歊住底栏；上滑升至顶/下滑降至底，滑到即存。"
            else
                "拖动底部菜单栏上下让位，与棋盘拉开/收紧间距；上滑抬高底栏、下滑降低底栏，滑到即存。"
            setTextColor(0xFFBFA67E.toInt())
            textSize = 10f
            setPadding(0, dp(2), 0, 0)
        })
        overlay.addView(TextView(this).apply {
            text = "点击此处收起浮条"
            setTextColor(0xFFFFC457.toInt())
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { dismissCoordPopup() }
        })

        val lp = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(44).toInt()   // 状态栏之下、棋盘上缘之上
        overlay.layoutParams = lp
        root.addView(overlay)
        coordOverlayView = overlay
        coordOverlayKind = kind
    }

    /** 坐标浮条的值文案：棋盘→向上/居中/向下，组件→底边距屏底 px */
    private fun coordValueLabel(isBoard: Boolean, v: Float): String =
        if (isBoard)
            when {
                v > 4f -> "下移 +${v.toInt()}px"
                v < -4f -> "上移 ${v.toInt()}px"
                else -> "居中 0"
            }
        else "${v.toInt()}px · 底边距"

    /** 展开菜单时，把某个高亮 Tab 的内容面板重新显示（若整体 collapsed 则恢复该面板）。
     *  「设置」无底部面板，点它只弹纸张设置窗，这里只恢复 对弈/棋谱。 */
    private fun restoreTabPanel(tab: Button) {
        navPanelCollapsed = false
        when (tab) {
            btnTabGame -> showGamePanel()
            btnTabNotation -> showNotationPanel()
        }
    }

    private fun highlightTab(active: Button) {
        val activeColor = 0xFFFFB300.toInt()
        val inactiveColor = 0xFF5C5040.toInt()
        btnTabSettings.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (active == btnTabSettings) activeColor else inactiveColor
        )
        btnTabGame.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (active == btnTabGame) activeColor else inactiveColor
        )
        btnTabNotation.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (active == btnTabNotation) activeColor else inactiveColor
        )
    }

    /** ★ 背谱：直接进背谱沙盘（谱源=当前棋谱的整棵树，绝对有指望；沙盘内再选边/套娃宝库） */
    private fun openBookSandbox() {
        try {
            val lineStart = runCatching { chessGame.bookLineStartFen() }.getOrNull() ?: chessGame.board.toFen()
            // ★ 整棵棋谱树一并捎进沙盘：主线 + 全部变着支线（含嵌套），变招处多着可选、
            //   故意背进任何一条支线都能一路背到底（不再"明明谱上有这步却说没谱"）。
            val lines = runCatching { chessGame.bookLineAllLines() }.getOrNull() ?: emptyList()
            val lineMoves = lines.firstOrNull() ?: emptyList()
            val lineName = runCatching {
                if (currentVaultGameIndex in currentVaultGames.indices) currentVaultGames[currentVaultGameIndex].title()
                else "当前棋谱"
            }.getOrNull() ?: "当前棋谱"
            val it = android.content.Intent(this, com.pikafish.chess.ui.BookSandboxActivity::class.java)
            // ★ 背谱=背这整份棋谱：从本局起始局面起背（不再从"主界面停在的第 N 步"起，那等于只背后半截）
            it.putExtra(com.pikafish.chess.ui.BookSandboxActivity.EXTRA_FEN, lineStart)
            it.putExtra(com.pikafish.chess.ui.BookSandboxActivity.EXTRA_FLIPPED, chessGame.boardFlipped)
            it.putExtra(com.pikafish.chess.ui.BookSandboxActivity.EXTRA_LINE_NAME, lineName)
            it.putExtra(com.pikafish.chess.ui.BookSandboxActivity.EXTRA_LINE_START_FEN, lineStart)
            it.putStringArrayListExtra(com.pikafish.chess.ui.BookSandboxActivity.EXTRA_LINE_MOVES, ArrayList(lineMoves))
            // ★ 变着支线（含嵌套）+ 宝库原文件支线兜底，一起捎进沙盘
            val vars = (lines.drop(1) + currentVaultVariationsFor(lineMoves))
                .filter { it.isNotEmpty() }
            it.putStringArrayListExtra(
                com.pikafish.chess.ui.BookSandboxActivity.EXTRA_LINE_VARIATIONS,
                ArrayList(vars.map { l -> l.joinToString(" ") })
            )
            chessBoard.clearSelection()
            startActivity(it)
        } catch (e: Exception) {
            Toast.makeText(this, "背谱沙盘启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ★ 棋谱宝库（参数面板 + 更多菜单共用的入口）
    private fun openQipuBrowser() {
        try {
            QipuBrowser(this, { _catName, book, index, games ->
                currentVaultBook = book
                currentVaultGameIndex = index
                currentVaultGames = games
                // ★ 持久化当前阅读的书和局索引（供小退/大退后恢复）
                val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                p.edit()
                    .putString(KEY_VAULT_BOOK_FILE, book.file)
                    .putInt(KEY_VAULT_GAME_INDEX, index)
                    .apply()
                loadQipuGame(games[index])
            }, { id -> resumeVaultById(id) }, { book -> openUserUploadBook(book) },
                { book -> openLocalDocBook(book) }, { showReviewLoadDialog() }).show()
        } catch (e: Exception) {
            Toast.makeText(this, "棋谱库打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ★ 从对局日志恢复续盘：重新从 SharedPreferences 载入完整对局
    fun resumeJournalGame() {
        try {
            if (tryRestoreGame()) {
                updateStepInfo()
                refreshExplanationDisplay()
                chessGame.checkAndTriggerAiMove()
            } else {
                Toast.makeText(this, "日志状态无效，无法续盘", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "续盘失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** ★ 把当前进行中的主界面/摆子对局写入对局日志（供续盘/回查） */
    private fun logCurrentGameToJournal(source: String) {
        try {
            val json = chessGame.getFullStateJson()
            val snap = json.optJSONArray("boardSnapshots")
            val movesCount = if (snap != null) snap.length() - 1 else chessGame.getMoveCount()
            if (movesCount <= 0) return   // 纯开新局但一步未走 → 不记日志
            com.pikafish.chess.qipu.GameJournal.upsertLog(
                this,
                id = "live_$source",
                title = "${source}对局 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINESE).format(java.util.Date()),
                source = source,
                fileName = "",
                bookIndex = 0,
                bookName = "",
                catName = "",
                stateJson = json.toString(),
                movesCount = movesCount,
                reading = "",
                note = "",
                bookmark = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "logCurrentGameToJournal: ${e.message}", e)
        }
    }

    /** 当前属性"属于"哪个宝库条目（决定能否「覆盖保存」；null＝没有来源，只能另存）。 */
    private var attrOwnerId: String? = null
    private var attrOwnerFile: String? = null

    /**
     * ★★★ 把某个宝库条目的属性"穿刺"成当前属性。★★★
     * 打开旧棋局练习时调用：详细保存对话框会自动预填这份内容，可看可改；
     * 同时记下来源条目，保存时才能给出「覆盖保存」（写回原条目/原文件）这个选项。
     */
    private fun adoptAttrsOf(entry: com.pikafish.chess.qipu.GameJournal.LogEntry) {
        try {
            val json = entry.stateJson ?: ""
            val attrs = LinkedHashMap<String, String>()
            if (json.contains("\"attrs\"")) {
                val obj = org.json.JSONObject(json).optJSONObject("attrs")
                obj?.keys()?.forEach { k ->
                    obj.optString(k).takeIf { it.isNotBlank() }?.let { v -> attrs[k] = v }
                }
            }
            if (attrs.isEmpty()) {
                clearStoredAttrs()
                attrOwnerId = null
                attrOwnerFile = null
            } else {
                saveStoredAttrs(attrs)
                attrOwnerId = entry.id
                attrOwnerFile = entry.fileName.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            attrOwnerId = null
            attrOwnerFile = null
        }
    }

    /** 当前这局的属性（跨保存延续；只有用户亲手删干净才会清）。 */
    private fun loadStoredAttrs(): Map<String, String> {
        val raw = getSharedPreferences("vault_attrs", android.content.Context.MODE_PRIVATE)
            .getString("current", "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            obj.keys().forEach { k -> obj.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveStoredAttrs(attrs: Map<String, String>) {
        val obj = org.json.JSONObject()
        for ((k, v) in attrs) obj.put(k, v)
        getSharedPreferences("vault_attrs", android.content.Context.MODE_PRIVATE)
            .edit().putString("current", obj.toString()).apply()
    }

    private fun clearStoredAttrs() {
        getSharedPreferences("vault_attrs", android.content.Context.MODE_PRIVATE)
            .edit().remove("current").apply()
    }

    // ★ 新开局前选「保存到宝库」：把当前进行中的半局棋存为我的对局（可续盘）
    private fun saveCurrentGameToVault() {
        // ★ 「保存到宝库」也要继承上次详细保存写下的属性——属性永不被这条路径清空。
        persistCurrentGameToVault(null, null, false)
    }

    /**
     * ★★★ 「开始新对局」四宫格弹窗：取消 / 清空（不保存）/ 保存到宝库 / 详细保存。★★★
     *
     * <p>为什么不用系统三键：多了一个「详细保存」，三键横排会挤成一团。这里用 2×2 四宫格，
     * 两行两列，界面匀称、按错概率也低。</p>
     */
    private fun showNewGameDialog(onProceed: () -> Unit) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val dialog = android.app.Dialog(this)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(0xFFFDFBF5.toInt())
        }
        root.addView(android.widget.TextView(this).apply {
            text = "开始新对局"
            textSize = 20f
            setTextColor(0xFF2B2118.toInt())
        })
        root.addView(android.widget.TextView(this).apply {
            text = "本局尚未结束（已走 ${chessGame.getMoveCount()} 步）。开始新一局前，是否保存本局？"
            textSize = 15f
            setTextColor(0xFF5A4A38.toInt())
            setPadding(0, dp(10), 0, dp(16))
        })

        // 2×2 按钮格
        val grid = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        fun row(vararg spec: Pair<String, () -> Unit>) {
            val r = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            for ((label, action) in spec) {
                r.addView(android.widget.Button(this).apply {
                    text = label
                    textSize = 15f
                    isAllCaps = false
                    setOnClickListener { dialog.dismiss(); action() }
                }, android.widget.LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            grid.addView(r)
        }
        row("取消" to { }, "清空（不保存）" to { onProceed() })
        row("保存到宝库" to { saveCurrentGameToVault(); onProceed() },
            "详细保存" to { showDetailedSaveDialog { onProceed() } })

        root.addView(grid)
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * ★★★ 「详细保存」：文件名（直接可见）+ 属性（可搜索/点开查看）。★★★
     *
     * <p>字段：赛事 / 日期 / 地点 / 红方 / 黑方 / 结果，外加「文件名称」。
     * 文件名称留空时，自动用与「保存到宝库」相同的默认名；属性则一律随谱记下。
     * 顶部有一句透明说明：文件名是直接可见的，属性要靠搜索或点「属性」才看得到。</p>
     */
    private fun showDetailedSaveDialog(onDone: () -> Unit) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val dialog = android.app.Dialog(this)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
            setBackgroundColor(0xFFFDFBF5.toInt())
        }
        root.addView(android.widget.TextView(this).apply {
            text = "详细保存"
            textSize = 19f
            setTextColor(0xFF2B2118.toInt())
        })
        root.addView(android.widget.TextView(this).apply {
            text = "文件名称：直接可见，宝库里一眼就能看到。\n属性：平时不显示，需要搜索或点「属性」才看得到。\n（两者都可以随便填；只填属性也行。）"
            textSize = 12f
            setTextColor(0xFF7A6A55.toInt())
            setPadding(0, dp(8), 0, dp(12))
        })

        val edits = LinkedHashMap<String, android.widget.EditText>()
        fun field(label: String, hint: String) {
            val line = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            line.addView(android.widget.TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(0xFF2B2118.toInt())
                width = dp(64)
            })
            val edit = android.widget.EditText(this).apply {
                setHint(hint)
                textSize = 14f
                setSingleLine(true)
            }
            line.addView(edit, android.widget.LinearLayout.LayoutParams(0, dp(42), 1f))
            root.addView(line)
            edits[label] = edit
        }
        // ★ 预填上次的属性（打开旧棋局时由 adoptAttrsOf 带过来），看得见、改得动
        val seeded = loadStoredAttrs()
        field("文件名", "留空＝用默认名")
        attrOwnerFile?.let { f ->
            edits["文件名"]!!.setText(f.substringAfterLast('/').substringBeforeLast('.'))
        }
        field("赛事", "如：网上快棋")
        field("日期", "如：2026-10-21")
        field("地点", "如：家里")
        field("红方", "如：张三")
        field("黑方", "如：李四")
        for (key in listOf("赛事", "日期", "地点", "红方", "黑方")) {
            seeded[key]?.let { edits[key]!!.setText(it) }
        }

        // 结果单选
        val resultRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        resultRow.addView(android.widget.TextView(this).apply {
            text = "结果"
            textSize = 14f
            setTextColor(0xFF2B2118.toInt())
            width = dp(64)
        })
        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        listOf("红胜", "黑胜", "和棋", "未知").forEachIndexed { i, label ->
            group.addView(android.widget.RadioButton(this).apply {
                text = label
                textSize = 13f
                id = android.view.View.generateViewId()
                isChecked = i == 3
            })
        }
        resultRow.addView(group)
        // ★ 预填上次保存的"结果"
        seeded["结果"]?.let { r ->
            for (i in 0 until group.childCount) {
                val rb = group.getChildAt(i)
                if (rb is android.widget.RadioButton && rb.text.toString() == r) rb.isChecked = true
            }
        }
        root.addView(resultRow)

        // 取值器：文件名 + 属性（结果里"未知"不算属性）
        fun collect(): Pair<String?, Map<String, String>> {
            val name = edits["文件名"]?.text?.toString()?.trim().orEmpty().ifBlank { null }
            val attrs = LinkedHashMap<String, String>()
            for (key in listOf("赛事", "日期", "地点", "红方", "黑方")) {
                val value = edits[key]?.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) attrs[key] = value
            }
            val checked = group.findViewById<android.widget.RadioButton>(group.checkedRadioButtonId)
            val result = checked?.text?.toString().orEmpty()
            if (result.isNotEmpty() && result != "未知") attrs["结果"] = result
            return name to attrs
        }

        val buttons = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        buttons.addView(android.widget.Button(this).apply {
            text = "取消"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }, android.widget.LinearLayout.LayoutParams(0, dp(44), 1f))
        // ★ 覆盖保存：仅当这盘来自某个已存条目（有属性来源）时才出现——写回原条目、原文件。
        if (attrOwnerId != null) {
            buttons.addView(android.widget.Button(this).apply {
                text = "覆盖保存"
                isAllCaps = false
                setOnClickListener {
                    val (nm, at) = collect()
                    dialog.dismiss()
                    persistCurrentGameToVault(nm, at, at.isEmpty(), overwrite = true)
                    onDone()
                }
            }, android.widget.LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        buttons.addView(android.widget.Button(this).apply {
            text = "另存"
            isAllCaps = false
            setOnClickListener {
                val (name, attrs) = collect()
                dialog.dismiss()
                // ★ 属性"咬死"规则：只要这次填了任意一个属性字节，就存下来并往后一直沿用；
                //   只有当用户把属性栏全部删得干干净净再点保存，才算他亲手取消属性。
                //   另存＝新建一条 + 新文件（不动原条目）。
                persistCurrentGameToVault(name, attrs, attrs.isEmpty(), overwrite = false)
                onDone()
            }
        }, android.widget.LinearLayout.LayoutParams(0, dp(44), 1f))

        root.addView(buttons)
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        dialog.setContentView(scroll)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * 把当前这盘存进宝库。fileName 非空时用它当文件名；attrs 非空时把属性写进条目备注
     * （备注在宝库列表里直接可见，也随条目一起被搜索覆盖），保证「属性」不是死数据。
     */
    private fun persistCurrentGameToVault(
        fileName: String?,
        attrs: Map<String, String>?,
        clearAttrs: Boolean = false,
        overwrite: Boolean = false
    ) {
        // ★★★ 属性延续：本次没填属性 → 取这一局上次存的属性（除非用户亲手删干净）。
        val effectiveAttrs: Map<String, String> = when {
            attrs != null && attrs.isNotEmpty() -> {
                saveStoredAttrs(attrs)
                attrs
            }
            clearAttrs -> {
                clearStoredAttrs()
                emptyMap()
            }
            else -> loadStoredAttrs()
        }
        try {
            val dl = com.pikafish.chess.qipu.GameJournal.DRAFT_ID
            // ★★★ 新方案：宝库条目只做"参考记忆"，实际棋谱以"通用有变招 TXT"存成本地文件。
            //   —— 这样彻底绕开"存内部状态→再续盘空回合"的老毛病，真实家底在用户自己磁盘上。
            // 1. 生成标准中文记谱 + 变招的 TXT（无变招则只有主线）
            val txtPath = chessGame.buildGivingTxt()
            if (txtPath.isBlank()) {
                Toast.makeText(this, "本局还未走棋，无需保存", Toast.LENGTH_SHORT).show()
                return
            }
            val movesCount = chessGame.getMoveCount()
            if (movesCount <= 0) {
                Toast.makeText(this, "本局还未走棋，无需保存", Toast.LENGTH_SHORT).show()
                return
            }

            // 2. 存成本地 TXT 棋谱文件（HHXQ 目录；失败则内部存储）
            //    ★ 覆盖保存：写回原来那个文件（同一个位置、同一个名字），不新增副本；
            //    ★ 另存/普通保存：用新文件名（用户填的或默认名），原文件分毫不动。
            val savedPath = if (overwrite && !attrOwnerFile.isNullOrBlank()) {
                rewriteVaultTxtFile(attrOwnerFile!!, txtPath)
            } else {
                saveToFileReturnName("txt", txtPath, fileName)
            }

            // 3. 在宝库登记一条"参考记忆"条目（含时间/文件名/路径，供翻阅）
            val now = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINESE).format(java.util.Date())
            val title = fileName?.takeIf { it.isNotBlank() }
                ?: ("我的对局 " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINESE).format(java.util.Date()))
            val id = if (overwrite && attrOwnerId != null) attrOwnerId!! else "vault_$now"
            val ref = org.json.JSONObject()
            ref.put("refType", "txtfile")
            ref.put("txtPath", savedPath)
            ref.put("givenTxt", txtPath)
            // ★ 属性随条目一起存（搜索/点「属性」时取用）
            if (effectiveAttrs.isNotEmpty()) {
                val obj = org.json.JSONObject()
                for ((k, v) in effectiveAttrs) obj.put(k, v)
                ref.put("attrs", obj)
                ref.put("hasAttrs", true)
            }
            com.pikafish.chess.qipu.GameJournal.upsertLog(
                this,
                id = id,
                title = title,
                source = "我的库",
                fileName = savedPath,
                bookIndex = 0,
                bookName = "",
                catName = "",
                stateJson = ref.toString(),
                movesCount = movesCount,
                reading = "",
                note = buildString {
                    append("$movesCount 步 · 本地文件 $savedPath")
                    if (!chessGame.variationBranchesEmpty()) append("（含变招）")
                    // ★ 属性写进备注：列表直接可见，也一并进入搜索/查看范围
                    if (effectiveAttrs.isNotEmpty()) append(" · 属性：" + effectiveAttrs.entries.joinToString(" ") { "${it.key}=${it.value}" })
                },
                bookmark = false
            )
            // 覆盖保存后，来源改为刚更新的这条，后续还能继续覆盖
            if (overwrite && attrOwnerId != null) attrOwnerFile = savedPath
            // 清掉实时预制位
            com.pikafish.chess.qipu.GameJournal.deleteLog(this, dl)
            Toast.makeText(this, "已保存到宝库：$savedPath（$movesCount 步）", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "saveCurrentGameToVault: ${e.message}", e)
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** ★★ PK 无差别存谱：不管点挡屏/终局弹窗上哪个按钮，都先把【当下这盘（走到哪算哪）】
     *  按普通存谱同样的规矩存进宝库（本地 TXT + 宝库登记），命名直观标出「谁执红 vs 谁执黑」，
     *  方便事后在宝库一眼查到是「哪台引擎（红）对哪台引擎（黑）」在 PK。 */
    private fun savePkGameToVault() {
        try {
            val movesCount = try { chessGame.getMoveCount() } catch (_: Exception) { 0 }
            val txt = try { chessGame.buildGivingTxt() } catch (_: Exception) { "" }
            if (movesCount <= 0 || txt.isBlank()) {
                toast("本局还未走棋，暂无棋谱可存")
                return
            }
            val red = SandboxOracle.pkRedLabel.ifBlank { "红方引擎" }
            val black = SandboxOracle.pkBlackLabel.ifBlank { "黑方引擎" }
            // 1. 存成本地 TXT 棋谱文件（与普通存谱同一处 HHXQ 目录）
            val savedPath = saveToFileReturnName("txt", txt)
            // 2. 宝库登记一条参考记忆（照抄普通存谱的字段结构，只是命名标出双方引擎）
            val now = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINESE).format(java.util.Date())
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINESE).format(java.util.Date())
            val title = "引擎PK · ${red}（红）vs ${black}（黑）· $stamp"
            val ref = org.json.JSONObject()
            ref.put("refType", "txtfile")
            ref.put("txtPath", savedPath)
            ref.put("givenTxt", txt)
            com.pikafish.chess.qipu.GameJournal.upsertLog(
                this,
                id = "vault_$now",
                title = title,
                source = "我的库",
                fileName = savedPath,
                bookIndex = 0,
                bookName = "",
                catName = "",
                stateJson = ref.toString(),
                movesCount = movesCount,
                reading = "",
                note = "$movesCount 步 · 引擎PK ${red}(红) vs ${black}(黑) · 本地文件 $savedPath",
                bookmark = false
            )
            com.pikafish.chess.qipu.GameJournal.deleteLog(this, com.pikafish.chess.qipu.GameJournal.DRAFT_ID)
            Toast.makeText(this, "已保存棋谱：$title\n（$movesCount 步 · 宝库可查）", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "savePkGameToVault: ${e.message}", e)
        }
    }

    /** ★ 点宝库「我的对局」条目：一键直接载入主界面拆解，完全按“人工导入”脚本化。
     *   真实家底在 HHXQ 本地文件里：读出该文件 → 走「导入」同款自动识别 → 停在主界面拆解。 */
    private fun resumeVaultById(id: String) {
        try {
            val entry = com.pikafish.chess.qipu.GameJournal.findLog(this, id)
            if (entry == null) {
                Toast.makeText(this, "找不到该存档", Toast.LENGTH_SHORT).show()
                return
            }
            // ★★★ 属性穿刺：打开这一局，就把它当初保存的属性带进"当前属性"★★★
            //   于是接着点「新对局 → 覆盖保存」时，详细保存里能原封不动看到上次填的内容，
            //   想改就改、不想改就直接覆盖保存回去，绝不会再是一片空白。
            adoptAttrsOf(entry)
            loadVaultTxtDirect(entry)   // 统一走自动识别导入管线（找不到文件会自动退回选择器）
        } catch (e: Exception) {
            Log.e(TAG, "resumeVaultById: ${e.message}", e)
            Toast.makeText(this, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从宝库条目 stateJson 里取出保存时内嵌的“可变招 TXT”内容（refType==txtfile）。 */
    private fun extractVaultGivenTxt(entry: com.pikafish.chess.qipu.GameJournal.LogEntry): String? {
        return try {
            val json = org.json.JSONObject(entry.stateJson ?: return null)
            if (json.optString("refType", "") == "txtfile") {
                json.optString("givenTxt").trim().ifBlank { null }
            } else null
        } catch (e: Exception) { null }
    }

    /** 🔁 一键打开宝库存档：完全按“人工导入”的轨迹脚本化——
     *   宝库记录 → 棋谱导入菜单 → 自动识别 → 从 HHXQ 抓真实本地文件 → 直接导入并停在主界面拆解。
     *   不再用轻量 loadFromGivingTxt（那会停在起始局面，看起来像“空”），
     *   而是走与「导入」按钮完全相同的 importNotation 自动识别管线，让棋子真正落在盘上。 */
    private fun loadVaultTxtDirect(entry: com.pikafish.chess.qipu.GameJournal.LogEntry) {
        try {
            if (bookMemorizer.active) stopBookMemorize()
            val savedPath = extractVaultPath(entry)

            // ① 先抓取 HHXQ 里对应的真实本地文件（等于人工“从文件中选取 → 定位到那一个 txt”）
            var content = if (savedPath != "内嵌内容") readRealVaultFile(savedPath) else null
            // ② 本地文件不在时，回退用条目内嵌的棋谱内容（同一份给了法）
            if (content.isNullOrBlank()) content = extractVaultGivenTxt(entry)
            if (content.isNullOrBlank()) {
                chooseVaultFile()   // 真找不到内容才退回手动选择
                return
            }

            // ③ 自动识别导入（= 人工“导入 → 自动识别”，含 PGN/中文记谱/UCI/ICCS 等）
            val resolved = detectFormat(content.trim())
            val ok = importNotation(content.trim(), resolved, false)
            if (ok) {
                // ④ 等价于人工点下“留在主界面（直接导入）”：切到棋谱拆解面板
                enterNotationAnalysisPanel()
                val src = if (savedPath != "内嵌内容") savedPath else "内嵌内容"
                Toast.makeText(
                    this,
                    "已导入《${entry.title}》到主界面拆解（源 $src）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // ⑤ 个别给了法没能被自动识别 → 兜底用专用可变招加载
            val steps = chessGame.loadFromGivingTxt(content)
            if (steps > 0) {
                enterNotationAnalysisPanel()
                updateStepInfo(); restoreHighlightsFromHistory()
                Toast.makeText(this, "已加载《${entry.title}》（$steps 步，含变招）", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "未能识别《${entry.title}》内容，已请手动选择文件", Toast.LENGTH_LONG).show()
                chooseVaultFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadVaultTxtDirect: ${e.message}", e)
            Toast.makeText(this, "载入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 按保存路径读取 HHXQ（或内部存储）里的真实本地棋谱文件，返回文本；读不到返回 null。 */
    private fun readRealVaultFile(savedPath: String): String? {
        return try {
            val fileName = savedPath.substringAfterLast('/')
            val file = if (savedPath.startsWith("内部存储"))
                java.io.File(filesDir, fileName)
            else
                java.io.File(java.io.File(Environment.getExternalStorageDirectory(), "HHXQ"), fileName)
            if (file.exists() && file.isFile) decodeChessText(file.readBytes()) else null
        } catch (e: Exception) { null }
    }

    /** 提取宝库条目的本地文件路径（用于提示；无则显示“内嵌内容”） */
    private fun extractVaultPath(entry: com.pikafish.chess.qipu.GameJournal.LogEntry): String {
        return try {
            val json = org.json.JSONObject(entry.stateJson ?: return "内嵌内容")
            json.optString("txtPath").trim().ifBlank { "内嵌内容" }
        } catch (e: Exception) { "内嵌内容" }
    }

    /** 切换到主界面棋谱拆解面板并刷新。
     *   ★ 注意：这里绝不调用 cancelSetup/clearSelection/viewingHistory 重置——
     *   否则会把刚导入的走法冲回起始局面(看起来“空”)；导入后的盘面状态由 importNotation/importPgn 自持。 */
    private fun enterNotationAnalysisPanel() {
        layoutParamsContent.visibility = View.GONE
        layoutGameContent.visibility = View.GONE
        layoutGameSub.visibility = View.GONE
        layoutGameSubRow2.visibility = View.GONE
        layoutNotationContent.visibility = View.VISIBLE
        layoutVisualContent.visibility = View.GONE
        layoutMoreContent.visibility = View.GONE
        navActiveTab = btnTabNotation; navPanelCollapsed = false
        highlightTab(btnTabNotation)
        if (scrollViewExplanation.visibility != View.VISIBLE) {
            scrollViewExplanation.visibility = View.VISIBLE
        }
        updateStepInfo()
        updateSideButtons()
        refreshExplanationDisplay()
    }

    /** ★ 恒定菜单栏「信息」按钮弹窗：展示本局来龙去脉（标题/来源、红黑身份、步数、阶段、胜负、
     *   若刚导入的谱带 PGN 头信息，还展示原始稿里的红黑名/结果/日期/赛事）。 */
    private fun showGameInfoDialog() {
        try {
            val sb = StringBuilder()
            val bookName = currentVaultBook?.name
            val title = bookName?.takeIf { it.isNotBlank() } ?: "自由对局"
            val src = if (currentVaultBook != null) "棋谱宝库 · 本地拆解" else "对弈 / 自由摆子"
            sb.append("棋局：").append(title).append("\n")
            sb.append("来源：").append(src).append("\n")

            sb.append("红方：").append(if (chessGame.redIsHuman) "人脑" else "AI")
                .append("　黑方：").append(if (chessGame.blackIsHuman) "人脑" else "AI").append("\n")

            val mc = chessGame.getMoveCount()
            val turnBlack = mc % 2 == 1
            sb.append("已走 ").append(mc).append(" 步，轮到")
                .append(if (turnBlack) "黑方" else "红方").append("\n")

            val occupied = digOcc(chessGame.board.toFen())
            val stage = when { occupied <= 14 -> "残局"; occupied <= 22 -> "中局"; else -> "开局/中前局" }
            sb.append("局面阶段：").append(stage).append("（双方共余 ").append(occupied).append(" 子）\n")

            // ★ 加塞开局行：紧贴“局面阶段”下方，按 ECCO 判当前局属于什么开局/变例。
            //   亲自走棋、AI 应子、宝库导入、外部导入后预览，一视同仁按完整主线判定；
            //   判定未覆盖、或走法失效时不显示，绝不报错名。
            val ecco = try {
                chessGame.openingLabelForCurrent()?.takeIf { it.opening.isNotBlank() }
            } catch (e: Exception) { null }
            if (ecco != null) {
                sb.append("开局：").append(ecco.opening)
                    .append(if (ecco.variation.isNotBlank()) "·" + ecco.variation else "")
                    .append("（").append(ecco.code).append("）")
                    .append(if (ecco.fromMidLine) " · 起于中途局面" else "")
                    .append("\n")
            }

            if (chessGame.gameOver) sb.append("对局结果：").append(chessGame.gameResult).append("\n")

            val h = chessGame.lastPgnHeader
            if (h != null && h.isNotEmpty()) {
                sb.append("\n—— 原始谱信息 ——\n")
                sb.append("红方棋手：").append(h["Red"] ?: "不详").append("\n")
                sb.append("黑方棋手：").append(h["Black"] ?: "不详").append("\n")
                sb.append("结果：").append(h["Result"] ?: "不详").append("\n")
                if (!h["Date"].isNullOrBlank()) sb.append("对弈日期：").append(h["Date"]).append("\n")
                if (!h["Site"].isNullOrBlank()) sb.append("地点：").append(h["Site"]).append("\n")
                if (!h["Event"].isNullOrBlank()) sb.append("赛事：").append(h["Event"]).append("\n")
            }

            // ★ 信息内容支持复制粘贴：正文可长按选中/复制（如 ECCO 编号），另给「复制全部」一键复制，
            //   方便用户拿去做重命名等操作。
            val tvInfo = TextView(this).apply {
                text = sb.toString()
                setTextIsSelectable(true)
                textSize = 14f
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
            val infoScroll = android.widget.ScrollView(this).apply { addView(tvInfo) }
            android.app.AlertDialog.Builder(this)
                .setTitle("棋局信息")
                .setView(infoScroll)
                .setPositiveButton("复制全部") { _, _ ->
                    try {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("棋局信息", sb.toString()))
                        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("知道了", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取棋局信息失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 统计 FEN 首段（棋盘段）双方总余子数：斜杠/空格/数字为分隔与空位，其余为棋子。 */
    private fun digOcc(fen: String): Int {
        var n = 0
        for (ch in fen.substringBefore(' ')) {
            if (ch == '/' || ch == ' ' || ch in '1'..'9') continue
            n++
        }
        return n
    }

    /** 打开系统本地文件选择器，让用户挑选棋谱文件（PGN/TXT、CBL/CBR、XQF、QYL 等一律可选中）。 */
    private fun chooseVaultFile() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/plain",
                "application/x-chess-pgn",
                "application/octet-stream",
                // ★ 棋谱二进制家族：部分机型/文件管理器按扩展名给出的"冷门 MIME"，
                //   漏列会被选择器滤掉（.qyl 就是典型 —— 启阳象棋棋谱，用户点不中）。
                "application/x-qyl",
                "application/x-cbl",
                "application/x-cbr",
                "application/x-xqf",
                "application/vnd.chess-pgn"
            ))
        }
        startActivityForResult(intent, REQUEST_VAULT_FILE)
    }

    // ★ 棋谱②入口：宝库 / 阅读印象（棋谱Tab复合并用）
    private fun setupQipu() {
        btnQipuVault.setOnClickListener { openQipuBrowser() }
        btnQipuImpression.setOnClickListener { showReadingImpression() }
        btnTagCurrent.setOnClickListener { tagCurrentGame() }
        btnQipuPrev.setOnClickListener { navigateVaultGame(-1) }
        btnQipuNext.setOnClickListener { navigateVaultGame(1) }
        btnAuthorIntent.setOnClickListener { toggleAuthorMessage() }
        btnJoinGroup.setOnClickListener { openJoinGroup() }
        btnOtherWorks.setOnClickListener { openOtherWorks() }
        btnBgm.setOnClickListener { showBgmDialog() }
        btnFreshLife.setOnClickListener { showFreshLifeReader() }
        btnExternalReader.setOnClickListener { showReaderShelf() }
    }

    /** ★ 背景音/音效：弹窗选择背景音乐（含试听）+ 拖条调音效音量；确定生效、关闭即停。 */
    private fun showBgmDialog() {
        try {
            val vm = androidx.appcompat.app.AlertDialog.Builder(this)
            // 用滚动容器避免小屏被拖条/说明挤掉按钮
            val scroll = android.widget.ScrollView(this)
            val content = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(32, 24, 32, 8)
            }

            // ── 背景音乐：单选（关 / 音乐1 / 音乐2 / 音乐3），三个直接可见，点选即试听 ──
            val bgmGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
            val rbBgml = arrayOf<android.widget.RadioButton>(
                android.widget.RadioButton(this).apply { text = "🚫 关闭背景音乐"; textSize = 16f; setPadding(0, 6, 0, 6) },
                android.widget.RadioButton(this).apply { text = "🎵 音乐1"; textSize = 16f; setPadding(0, 6, 0, 6) },
                android.widget.RadioButton(this).apply { text = "🎵 音乐2"; textSize = 16f; setPadding(0, 6, 0, 6) },
                android.widget.RadioButton(this).apply { text = "🎵 音乐3"; textSize = 16f; setPadding(0, 6, 0, 6) }
            )
            for ((i, rb) in rbBgml.withIndex()) {
                rb.setOnClickListener {
                    // 点选即试听（不改变已保存选择）
                    if (i == 0) com.pikafish.chess.utils.SoundManager.stopBgm()
                    else com.pikafish.chess.utils.SoundManager.previewBgm(this@MainActivity, i)
                }
                bgmGroup.addView(rb)
            }
            val cur = com.pikafish.chess.utils.SoundManager.bgmIndex()
            rbBgml[cur.coerceIn(0, 3)]?.isChecked = true
            if (cur == 0) com.pikafish.chess.utils.SoundManager.stopBgm()

            content.addView(android.widget.TextView(this).apply {
                text = "背景音乐（点击选项即试听）"; textSize = 15f
            })
            content.addView(bgmGroup)

            // ── 背景音量：拖条 0..100（拖动即实时改变正在播放的背景音乐响度）──
            content.addView(android.widget.TextView(this).apply {
                text = "背景音量（背景音乐大小）"; textSize = 15f; setPadding(0, 14, 0, 0)
            })
            val curBgmV = com.pikafish.chess.utils.SoundManager.bgmVolumePref()
            val tvBgm = android.widget.TextView(this).apply {
                text = "$curBgmV%"; textSize = 14f; textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            }
            val sbBgm = android.widget.SeekBar(this).apply { max = 100; progress = curBgmV }
            sbBgm.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    tvBgm.text = "$p%"
                    com.pikafish.chess.utils.SoundManager.setBgmVolume(this@MainActivity, p) // 拖动即实音效
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
            content.addView(sbBgm)
            content.addView(tvBgm)

            // ── 音效音量：拖条 0..100 ──
            content.addView(android.widget.TextView(this).apply {
                text = "音效音量（走子/吃子/将军等）"; textSize = 15f
                setPadding(0, 14, 0, 0)
            })
            val curSfx = chessGame.soundManager.sfxVolume()
            val tvSfx = android.widget.TextView(this).apply {
                text = "$curSfx%"; textSize = 14f; textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            }
            val sbSfx = android.widget.SeekBar(this).apply { max = 100; progress = curSfx }
            sbSfx.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { tvSfx.text = "$p%" }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
            content.addView(sbSfx)
            content.addView(tvSfx)

            scroll.addView(content)

            val dlg = vm.setTitle("背景音量 · 背景音乐 · 音效音量")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .setPositiveButton("确定", null)
                .create()
            dlg.setOnShowListener {
                dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    // 确定：真正生效（选择写入，音效音量应用到主界面音效管理器）
                    val sel = bgmGroup.checkedRadioButtonId
                    val index = when (sel) {
                        rbBgml[1]?.id -> 1
                        rbBgml[2]?.id -> 2
                        rbBgml[3]?.id -> 3
                        else -> 0
                    }
                    chessGame.soundManager.setSfxVolume(this, sbSfx.progress)
                    com.pikafish.chess.utils.SoundManager.playBgm(this, index)
                    Toast.makeText(this, if (index == 0) "已关闭背景音乐" else "已应用${com.pikafish.chess.utils.SoundManager.BGM_NAMES[index - 1]}，音效音量${sbSfx.progress}%", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                }
                dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    // 关闭：关闭背景音乐并保存为"关"，退出弹窗
                    com.pikafish.chess.utils.SoundManager.playBgm(this, 0)
                    dlg.dismiss()
                }
            }
            dlg.setCanceledOnTouchOutside(true)
            dlg.setOnDismissListener {
                // 弹窗关闭后，恢复为已保存的背景音选择（试听不落盘，除非点了确定/关闭）
                try {
                    com.pikafish.chess.utils.SoundManager.stopBgm()
                    val saved = com.pikafish.chess.utils.SoundManager.bgmIndex()
                    if (saved > 0) com.pikafish.chess.utils.SoundManager.playBgm(this, saved)
                } catch (_: Exception) {}
            }
            dlg.show()
        } catch (e: Exception) {
            Log.e(TAG, "背景音弹窗失败: ${e.message}", e)
        }
    }

    // ★ 清新人生：《清新人生手册》全屏阅读器（独立 Activity，返回可靠不卡死）
    private fun showFreshLifeReader() {
        try {
            startActivity(android.content.Intent(this, com.pikafish.chess.ui.ReaderActivity::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "清新人生阅读器打开失败: ${e.message}", e)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ★ 外置读物（PDF 书架）：导入 / 点书名直接读 / 删除 / 清空 + 阅读器入口
    // ══════════════════════════════════════════════════════════════

    /** 书单弹窗：列出外置读物。【点书名 = 直接开始阅读】；右侧「删除」删单本；下方「清空」全部删。 */
    private fun showReaderShelf() {
        try {
            val shelf = com.pikafish.chess.reader.ReaderShelf
            val books = shelf.list(this)

            val scroll = android.widget.ScrollView(this)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(4))
            }

            col.addView(TextView(this).apply {
                text = if (books.isEmpty())
                    "书架还空着。\n点下面「📥 导入 PDF」，或在 QQ 里点开 PDF →「打开其他应用」→ 选「黄昏象棋」，这本读物就会自动收进这里。"
                else
                    "共 ${books.size} 本 · 点书名直接阅读（下划线处），右侧可删单本。"
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                setPadding(0, 0, 0, dp(8))
            })

            val dlgRef = arrayOfNulls<androidx.appcompat.app.AlertDialog>(1)

            for (b in books) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                val left = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(2), 0, dp(6), 0)
                    setOnClickListener {
                        dlgRef[0]?.dismiss()
                        openReaderBook(b)
                    }
                }
                val prog = shelf.progress(this, b.fileName)
                val pct = if (prog != null) (prog.first * 100f).toInt() else 0
                left.addView(TextView(this).apply {
                    text = "📕 ${b.name}"
                    textSize = 16f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    // ★ 下划线 = 热键：点击书名即开读
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    setTextColor(0xFF2A6DB5.toInt())
                    setPadding(dp(2), dp(10), dp(2), dp(2))
                })
                left.addView(TextView(this).apply {
                    text = if (prog != null) "已读约 $pct% · ${b.sizeBytes / 1024 / 1024} MB"
                           else "尚未开始 · ${b.sizeBytes / 1024 / 1024} MB"
                    textSize = 12f
                    setTextColor(0xFF8A8A8A.toInt())
                    setPadding(dp(2), 0, dp(2), dp(8))
                })
                val btnDel = Button(this).apply {
                    text = "删除"
                    textSize = 13f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(6), 0, dp(6), 0)
                    setOnClickListener {
                        shelf.delete(this@MainActivity, b)
                        toast("已删除《${b.name}》")
                        dlgRef[0]?.dismiss()
                        showReaderShelf()
                    }
                }
                row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(btnDel, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
                col.addView(row)
                col.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(0x22000000)
                })
            }

            scroll.addView(col)

            val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📖 外置读物")
                .setView(scroll)
                .setNeutralButton("📥 导入 PDF", null)
                .setNegativeButton("🗑 清空（全部删除）", null)
                .setPositiveButton("关闭", null)
                .create()
            dlgRef[0] = dlg
            dlg.setOnShowListener {
                dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    pickPdfToImport()
                }
                dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("清空外置读物")
                        .setMessage("将删除书架里全部 ${books.size} 本读物及其进度与标记，不可恢复。确定吗？")
                        .setPositiveButton("全部删除") { _, _ ->
                            shelf.clear(this)
                            toast("外置读物已全部清空")
                            dlg.dismiss()
                            showReaderShelf()
                        }
                        .setNegativeButton("再想想", null)
                        .show()
                }
            }
            dlg.show()
        } catch (e: Exception) {
            Log.e(TAG, "外置读物书架打开失败: ${e.message}", e)
            toast("外置读物打开失败：${e.message}")
        }
    }

    /** 选择本地 PDF 导入书架 */
    private fun pickPdfToImport() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf"))
            }
            startActivityForResult(intent, REQUEST_PDF_IMPORT)
        } catch (e: Exception) {
            // 少数机型不支持按 mime 过滤，退化为任意文件（后面仍按后缀校验）
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                }
                startActivityForResult(i, REQUEST_PDF_IMPORT)
            } catch (e2: Exception) {
                toast("无法打开文件选择器：${e2.message}")
            }
        }
    }

    /** 把一份已经在本地缓存里的 PDF 收进「外置读物」书架，并直接开读。 */
    private fun importPdfFileToShelf(src: java.io.File, displayName: String) {
        try {
            val bytes = src.readBytes()
            val book = com.pikafish.chess.reader.ReaderShelf.add(this, displayName, bytes)
            if (book == null) { toast("《$displayName》无法收入外置读物"); return }
            toast("已收入外置读物《${book.name}》")
            openReaderBook(book)
        } catch (e: Exception) {
            Log.e(TAG, "importPdfFileToShelf: ${e.message}", e)
            toast("打开读物失败：${e.message}")
        }
    }

    /** 从 content:// URI 导入 PDF 到「外置读物」书架。 */
    private fun importPdfToShelf(uri: android.net.Uri, displayNameOverride: String?) {
        try {
            val name = displayNameOverride?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
                }.getOrNull()
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "读物_${System.currentTimeMillis()}.pdf"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) { toast("文件为空，无法导入"); return }
            val book = com.pikafish.chess.reader.ReaderShelf.add(this, name, bytes)
            if (book == null) { toast("导入失败"); return }
            toast("已导入外置读物《${book.name}》")
            openReaderBook(book)
        } catch (e: Exception) {
            Log.e(TAG, "importPdfToShelf: ${e.message}", e)
            toast("导入 PDF 失败：${e.message}")
        }
    }

    /** 打开书架里的一本读物：进全屏阅读器（独立页，退出只回这里，继续下棋） */
    private fun openReaderBook(b: com.pikafish.chess.reader.ReaderBook) {
        try {
            val f = com.pikafish.chess.reader.ReaderShelf.fileOf(this, b)
            if (!f.exists() || f.length() <= 0L) { toast("《${b.name}》文件不见了"); return }
            val i = android.content.Intent(this, com.pikafish.chess.ui.PdfReaderActivity::class.java).apply {
                putExtra(com.pikafish.chess.ui.PdfReaderActivity.EXTRA_PATH, f.absolutePath)
                putExtra(com.pikafish.chess.ui.PdfReaderActivity.EXTRA_TITLE, b.name)
                putExtra(com.pikafish.chess.ui.PdfReaderActivity.EXTRA_FILE_NAME, b.fileName)
            }
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "打开外置读物失败: ${e.message}", e)
            toast("打开《${b.name}》失败：${e.message}")
        }
    }

    // ★ 进群反馈：跳转QQ官方群
    private fun openJoinGroup() {
        try {
            val url = "https://qun.qq.com/universal-share/share?ac=1&authKey=oIKCTv6GAWEohmYs1P2ghFLqrXffQJCSTaSoizjwJUW8K6QJ5Q3u4q957rvU3z0E&busi_data=eyJncm91cENvZGUiOiIyMzM0Mjk4MTciLCJ0b2tlbiI6InZXQ0w1U0ZKMld2dEJscXA0UngrUnJxNlBnUXdKVEM3MzRSbTZkcnY3MUJhcWJlVGtCZzhkbkVPWCtpbGxVRGsiLCJ1aW4iOiIzNDQ4NzEyNTk5In0%3D&data=pStbW5H1yWChrVOHpkjf2sB23PwgZqx3LxtHUu3T_vSR7WQLPUu4_-UhxVz5ChZdEodD5pb9ziGM6xRCqBG7jA&svctype=4&tempid=h5_group_info"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "进群反馈跳转失败: ${e.message}", e)
            Toast.makeText(this, "无法打开QQ群链接", Toast.LENGTH_SHORT).show()
        }
    }

    // ★ 其他作品：气泡提示后跳转下载
    private fun openOtherWorks() {
        try {
            // 气泡提示：3秒后自动消失
            val toast = Toast.makeText(this, "你即将下载作者的另外一款安卓成品，单机善缘棋牌", Toast.LENGTH_LONG)
            toast.show()
            // 3秒后跳转下载链接
            android.os.Handler(mainLooper).postDelayed({
                try {
                    val url = "http://api.cc.zhuifenghanhua.cn/api/v4/download/resource/2013384"
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "其他作品跳转失败: ${e.message}", e)
                }
            }, 3000)
        } catch (e: Exception) {
            Log.e(TAG, "其他作品提示失败: ${e.message}", e)
        }
    }

    /**
     * ★ 作者寄语复合键：点一下播放真人录音，再点一下停止。
     * 不弹窗，可当背景音边下棋边听；按钮文案随状态切换。
     */
    private fun toggleAuthorMessage() {
        try {
            if (authorMsgPlaying) {
                stopAuthorMessage()
            } else {
                val fd = assets.openFd("snd/author_message.mp3")
                val mp = android.media.MediaPlayer()
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                mp.setVolume(1f, 1f)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener {
                    authorMsgPlaying = false
                    authorMsgPlayer = null
                    btnAuthorIntent.text = "🔊 作者寄语"
                }
                mp.setOnErrorListener { _, _, _ ->
                    authorMsgPlaying = false
                    authorMsgPlayer = null
                    btnAuthorIntent.text = "🔊 作者寄语"
                    true
                }
                mp.prepareAsync()
                authorMsgPlayer = mp
                authorMsgPlaying = true
                btnAuthorIntent.text = "⏸ 寄语播放中·再点停"
            }
        } catch (e: Exception) {
            Log.e(TAG, "作者寄语播放失败: ${e.message}", e)
            btnAuthorIntent.text = "🔊 作者寄语"
        }
    }

    private fun stopAuthorMessage() {
        try {
            authorMsgPlayer?.let {
                try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
                it.release()
            }
        } catch (_: Exception) {}
        authorMsgPlayer = null
        authorMsgPlaying = false
        btnAuthorIntent.text = "🔊 作者寄语"
    }

    /** ★ 宝库对局导航：上一则/下一则 */
    private fun navigateVaultGame(direction: Int) {
        // 若当前没有会话（重启/退出后），先从持久化位置自动恢复
        if (currentVaultBook == null || currentVaultGames.isEmpty()) {
            // ★ 会话恢复可能要把「几千局的压缩包」再解一遍：一律丢后台，主线程绝不碰，
            //   避免"点一下上一则/下一则就卡住"。
            initScope.launch(Dispatchers.IO) {
                val ok = restoreVaultSession(andLoad = false)
                runOnUiThread {
                    if (!ok) {
                        Toast.makeText(this@MainActivity, "请先从宝库中选书", Toast.LENGTH_SHORT).show()
                    } else if (currentVaultGameIndex in currentVaultGames.indices) {
                        loadQipuGame(currentVaultGames[currentVaultGameIndex])
                    }
                }
            }
            return
        }
        // 恢复后，若仍无会话则提示
        if (currentVaultBook == null || currentVaultGames.isEmpty()) return
        // 让"上一则"在已到开头时先尝试回到持久化起点
        val resolvedIndex = currentVaultGameIndex
        var newIndex = resolvedIndex + direction
        // 若用户上次停在最后且点了"下一则"，提示已读完
        if (newIndex < 0) {
            Toast.makeText(this, "已经是第一局了", Toast.LENGTH_SHORT).show()
            return
        }
        if (newIndex >= currentVaultGames.size) {
            Toast.makeText(this, "本书已读完", Toast.LENGTH_SHORT).show()
            return
        }
        currentVaultGameIndex = newIndex
        // ★ 持久化局索引，退出重进后可继续
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VAULT_GAME_INDEX, newIndex).apply()
        loadQipuGame(currentVaultGames[newIndex])
    }

    /**
     * ★ 从持久化记录恢复棋库阅读会话（书文件 + 局索引）。
     * @param andLoad 为 true 时恢复后立即加载当时所在局
     * @return 是否成功恢复出书和局列表
     */
    private fun restoreVaultSession(andLoad: Boolean): Boolean {
        val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val file = p.getString(KEY_VAULT_BOOK_FILE, "") ?: ""
        if (file.isBlank()) return false
        try {
            val cats = com.pikafish.chess.qipu.QipuLibrary.loadCategories(this)
            val cat = cats.firstOrNull { c -> c.books.any { it.file == file } }
                ?: return false
            val book = cat.books.first { it.file == file }
            val games = com.pikafish.chess.qipu.QipuLibrary.decodeGames(
                com.pikafish.chess.qipu.QipuLibrary.loadGamesBytes(this, book),
                book.name
            )
            if (games.isEmpty()) return false
            currentVaultBook = book
            currentVaultGames = games
            currentVaultGameIndex = p.getInt(KEY_VAULT_GAME_INDEX, 0)
                .coerceIn(0, games.size - 1)
            if (andLoad) {
                loadQipuGame(currentVaultGames[currentVaultGameIndex])
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "restoreVaultSession: ${e.message}", e)
            return false
        }
    }

    /** ★ 阅读印象面板：全屏透纱窗，列出本局印象打勾 + 阅读程度筛选
     *   每条即热链接：点行回到对应棋局，点行尾「✎标」就地改印象。 */
    private fun showReadingImpression() {
        try {
            val dlg = com.pikafish.chess.qipu.GameJournal.openImpressionDialog(this) { e -> jumpToLogEntry(e) }
            dlg?.show()
        } catch (e: Exception) {
            Toast.makeText(this, "阅读印象打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** ★ 底导航「▣标」：就地给「当前正读这一局」打阅读程度/印象/备注/书签。
     *   在宝库阅读时标到该书该局；自己摆/对弈时则把当前棋局一并存为一条对局印象。 */
    private fun tagCurrentGame() {
        try {
            val GJ = com.pikafish.chess.qipu.GameJournal
            // ── 情况一：正在宝库读某本书的某一局（上一则/下一则切换） ──
            if (currentVaultBook != null && currentVaultGames.isNotEmpty()
                && currentVaultGameIndex in currentVaultGames.indices) {
                val game = currentVaultGames[currentVaultGameIndex]
                val myFile = currentVaultBook!!.file
                val catName = try {
                    com.pikafish.chess.qipu.QipuLibrary.loadCategories(this)
                        .firstOrNull { c -> c.books.any { it.file == myFile } }?.name ?: ""
                } catch (e: Exception) { "" }
                val ref = com.pikafish.chess.qipu.GameJournal.GameRef(
                    catName,
                    currentVaultBook!!.name,
                    myFile,
                    currentVaultGameIndex,
                    game.title()
                )
                val rec = GJ.getRecord(this, ref)
                GJ.openEditImpressionDialog(
                    this, game.title(),
                    rec.reading, rec.tags, rec.note, rec.bookmark
                ) { reading, tags, note, bm ->
                    val r = GJ.getRecord(this, ref)
                    r.reading = reading; r.tags.clear(); r.tags.addAll(tags)
                    r.note = note; r.bookmark = bm
                    GJ.saveRecord(this, r)
                    GJ.touch(this, ref, "棋谱库")   // 同步更新对局日志阅读字段
                    Toast.makeText(this, "已标《${game.title()}》", Toast.LENGTH_SHORT).show()
                }?.show()
                return
            }
            // ── 情况二：主界面对弈 / 自由摆子：当前棋局存成一条印象 ──
            val json = chessGame.getFullStateJson()
            val snap = json.optJSONArray("boardSnapshots")
            val mc = if (snap != null) snap.length() - 1 else chessGame.getMoveCount()
            if (mc <= 0) {
                Toast.makeText(this, "请先走一步棋，或先从宝库打开一局再标印象", Toast.LENGTH_SHORT).show()
                return
            }
            val id = "live_tag_" + java.util.UUID.randomUUID().toString().substring(0, 8)
            val title = "对局印象 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINESE).format(java.util.Date())
            GJ.openEditImpressionDialog(
                this, "当前局（${mc}步）",
                "", emptySet(), "", false
            ) { reading, tags, note, bm ->
                GJ.upsertLog(
                    this,
                    id = id, title = title,
                    source = "主界面", fileName = "", bookIndex = 0,
                    bookName = "", catName = "",
                    stateJson = json.toString(),
                    movesCount = mc,
                    reading = reading, note = if (note.isBlank()) "$mc 步" else note,
                    bookmark = bm
                )
                Toast.makeText(this, "已标当前对局印象", Toast.LENGTH_SHORT).show()
            }?.show()
        } catch (e: Exception) {
            Log.e(TAG, "tagCurrentGame: ${e.message}", e)
            Toast.makeText(this, "标印象失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** ★ 阅读印象热链接：点某条印象记录，直接回到对应棋局 / 对局（主界面续盘）。 */
    private fun jumpToLogEntry(e: com.pikafish.chess.qipu.GameJournal.LogEntry) {
        try {
            if (e.source == "棋谱库" && e.fileName.isNotBlank()) {
                // ★ 载入该书可能要把「几千局的压缩包」整包再解一遍：一律丢后台，
                //   解完回主线程落盘面；主线程绝不碰解码。
                initScope.launch(Dispatchers.IO) {
                    var okBook: com.pikafish.chess.qipu.QipuBook? = null
                    var okGames: List<QipuGame> = emptyList()
                    try {
                        val cats = com.pikafish.chess.qipu.QipuLibrary.loadCategories(this@MainActivity)
                        val cat = cats.firstOrNull { c -> c.books.any { it.file == e.fileName } }
                        if (cat != null && e.bookIndex >= 0) {
                            val book = cat.books.first { it.file == e.fileName }
                            val games = com.pikafish.chess.qipu.QipuLibrary.decodeGames(
                                com.pikafish.chess.qipu.QipuLibrary.loadGamesBytes(this@MainActivity, book),
                                book.name)
                            if (games.isNotEmpty() && e.bookIndex in games.indices) {
                                okBook = book; okGames = games
                            }
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "jumpToLogEntry(io): ${ex.message}", ex)
                    }
                    val book = okBook
                    runOnUiThread {
                        if (book != null) {
                            currentVaultBook = book
                            currentVaultGameIndex = e.bookIndex
                            currentVaultGames = okGames
                            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                                .putString(KEY_VAULT_BOOK_FILE, book.file)
                                .putInt(KEY_VAULT_GAME_INDEX, e.bookIndex).apply()
                            loadQipuGame(okGames[e.bookIndex])
                            Toast.makeText(this@MainActivity, "已回到《${e.title}》", Toast.LENGTH_SHORT).show()
                        } else {
                            restartJournalFallback(e)
                        }
                    }
                }
                return
            }
            // 主界面对局 / 我的库 等：优先用完整快照恢复，否则回退到 txt 续盘
            val snap = e.stateJson
            if (snap.isNullOrBlank() || snap == "null") { restartJournalFallback(e); return }
            val json = org.json.JSONObject(snap)
            val arr = json.optJSONArray("boardSnapshots")
            if (arr != null && arr.length() > 1) {
                if (chessGame.loadFullStateJson(json)) {
                    restoreHighlightsFromHistory()
                    chessGame.onBoardChanged?.invoke()
                    updateSideButtons()
                    updateStepInfo()
                    refreshExplanationDisplay()
                    chessGame.checkAndTriggerAiMove()
                    Toast.makeText(this, "已回到《${e.title}》", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            restartJournalFallback(e)
        } catch (ex: Exception) {
            Log.e(TAG, "jumpToLogEntry: ${ex.message}", ex)
            Toast.makeText(this, "跳转失败: ${ex.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartJournalFallback(e: com.pikafish.chess.qipu.GameJournal.LogEntry) {
        try { resumeVaultById(e.id) } catch (_: Exception) {
            Toast.makeText(this, "无法回该局：${e.title}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 选中的一局棋谱：导入到对局并切到棋谱面板回放 */
    private fun loadQipuGame(game: QipuGame) {
        // ★ 成批 FEN 题库里的「局面习题」：没有回合/着法，按“摆好子的习题”打开（另一条路）。
        if (game.isFenPuzzle) { loadFenPuzzle(game); return }
        try {
            if (bookMemorizer.active) stopBookMemorize()   // 换谱即退出背谱
            val msg = chessGame.importPgn(game.pgn())
            // ★ 设置棋谱旁白数据
            chessGame.setQipuComments(
                game.introComment(),
                game.endComment(),
                game.moveComments(),
                game.commentaryBlocks()
            )
            updateStepInfo()
            restoreHighlightsFromHistory()
            if (!msg.startsWith("导入失败")) {
                layoutParamsContent.visibility = View.GONE
                layoutGameContent.visibility = View.GONE
                layoutGameSub.visibility = View.GONE
                layoutGameSubRow2.visibility = View.GONE
                layoutNotationContent.visibility = View.VISIBLE
                layoutVisualContent.visibility = View.GONE
                layoutMoreContent.visibility = View.GONE
                navActiveTab = btnTabNotation; navPanelCollapsed = false
                highlightTab(btnTabNotation)
                // 确保解说/棋谱面板可见
                if (scrollViewExplanation.visibility != View.VISIBLE) {
                    scrollViewExplanation.visibility = View.VISIBLE
                }
                // 停在棋谱的起始局面（棋谱从哪开始就显示哪，不自动快进到底）
                updateStepInfo()
                restoreHighlightsFromHistory()
                refreshExplanationDisplay()
                // 显示开局旁白（如果有）
                showCurrentQipuComment()
            }
            val ev = game.cblGame.event.ifBlank { "棋谱" }
            // ★ 这类 CBL 库变体只存了图文讲解、没有坐标着法：不能说"点击前进播放"（会让人以为坏了），
            //   明确告知是图文讲解，引导去看右边讲解文字。
            val showMsg = if (game.cblGame.movesUci.isEmpty() &&
                (game.introComment().isNotBlank() || game.endComment().isNotBlank())) {
                "本则为图文讲解（该 CBL 变体未存坐标着法，无法自动走子），已显示讲解文字"
            } else msg
            Toast.makeText(
                this,
                "$showMsg\n已载入《$ev》",
                Toast.LENGTH_LONG
            ).show()

            // ★ 人脑对人脑时弹提示（研究棋谱时容易点了没反应）
            if (chessGame.redIsHuman && chessGame.blackIsHuman) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(KEY_HINT_HVH_DISMISSED, false)) {
                    showHvHHintDialog()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载棋谱失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * ★ 打开「局面习题」（成批 FEN 题库里的一题）。
     *
     * 这类“棋”本来就是摆好子的一个局面 + 规定哪方走 —— 没有回合、没有着法，
     * 所以不走“回放棋谱”那条路，而是直接把局面落盘，并把「先行方」交给人脑、
     * 对方交给 AI（与“练开局”同一约定）：这样才叫“解习题”，而不是干看图。
     */
    private fun loadFenPuzzle(game: QipuGame) {
        try {
            if (bookMemorizer.active) stopBookMemorize()
            val cb = game.meta()
            val boardPart = cb.fenBoard?.trim().orEmpty()
            if (boardPart.isEmpty()) {
                Toast.makeText(this, "这一题没有局面（FEN 缺失）", Toast.LENGTH_LONG).show()
                return
            }
            val fen = if (boardPart.contains(' ')) boardPart
                      else "$boardPart ${if (cb.moversBlack) "b" else "w"}"
            val msg = chessGame.importFen(fen)
            if (!msg.startsWith("FEN局面已加载")) {
                Toast.makeText(this, "习题局面载入失败：$msg", Toast.LENGTH_LONG).show()
                return
            }
            chessGame.fenPuzzleMode = true
            // 先行方人脑、对方 AI —— 习题要自己解，不能一打开就让 AI 代走或自走。
            chessGame.redIsHuman = !cb.moversBlack
            chessGame.blackIsHuman = cb.moversBlack
            val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            p.edit()
                .putBoolean(KEY_RED_HUMAN, chessGame.redIsHuman)
                .putBoolean(KEY_BLACK_HUMAN, chessGame.blackIsHuman)
                .apply()
            updateSideButtons()

            // 与“打开棋谱”同一落场：切到棋谱面板、清选中/历史
            layoutParamsContent.visibility = View.GONE
            layoutGameContent.visibility = View.GONE
            layoutGameSub.visibility = View.GONE
            layoutGameSubRow2.visibility = View.GONE
            layoutNotationContent.visibility = View.VISIBLE
            layoutVisualContent.visibility = View.GONE
            layoutMoreContent.visibility = View.GONE
            navActiveTab = btnTabNotation; navPanelCollapsed = false
            highlightTab(btnTabNotation)
            if (scrollViewExplanation.visibility != View.VISIBLE) {
                scrollViewExplanation.visibility = View.VISIBLE
            }
            updateStepInfo()
            restoreHighlightsFromHistory()
            refreshExplanationDisplay()
            showCurrentQipuComment()
            chessBoard.clearSelection()
            chessBoard.viewingHistory = false
            chessGame.onBoardChanged?.invoke()

            val name = cb.event.ifBlank { "习题" }
            Toast.makeText(
                this,
                "《$name》已就绪 · 局面习题（无回合）\n你执${if (cb.moversBlack) "黑" else "红"}先行，走子即解题",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "打开习题失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ★ 打开宝库里的用户上传棋谱：自动识别格式并加载（绝不空白），可正常走子/快进。
    //   ★★ 读盘 + 解码一律放后台线程：无论是几百 KB 的象棋桥库、还是内含上千局的棋谱压缩包
    //   （如"2022年2月份华山和棋棋谱.zip"里 6000+ 个 .mxq），若在主线程解包识别，
    //   点一下就会整屏卡死、连系统导航键都被顶出来。解码完再回主线程落盘面。
    //   ★ 一律走 QipuLibrary.decodeGames 按【内容】分流：ZIP 压缩包（可含 MXQ/CBL 上千局）、
    //   MXQ 单局、CBL/CBR 全部兼容；解不出棋局才回退到文本自动识别管线。
    private fun openUserUploadBook(book: com.pikafish.chess.qipu.QipuBook) {
        try {
            if (bookMemorizer.active) stopBookMemorize()
            Toast.makeText(this, "正在载入《${book.name}》…", Toast.LENGTH_SHORT).show()
            initScope.launch(Dispatchers.IO) {
                val bytes = try {
                    com.pikafish.chess.qipu.QipuLibrary.readUserUpload(this@MainActivity, book.file)
                } catch (e: Exception) { null }
                if (bytes == null) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "找不到《${book.name}》，可能已被删除", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val games = try {
                    com.pikafish.chess.qipu.QipuLibrary.decodeGames(bytes, book.name)
                } catch (e: Exception) { emptyList() }
                runOnUiThread {
                    if (games.isNotEmpty()) {
                        // 与「宝库 → 点某一则」完全同一条链路：落阅读会话、持久化、回放第一局
                        currentVaultBook = book
                        currentVaultGames = games
                        currentVaultGameIndex = 0
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putString(KEY_VAULT_BOOK_FILE, book.file)
                            .putInt(KEY_VAULT_GAME_INDEX, 0)
                            .apply()
                        loadQipuGame(games[0])
                        if (games.size > 1) {
                            Toast.makeText(
                                this@MainActivity,
                                "已打开《${book.name}》，本包共 ${games.size} 则（底部「上一则/下一则」逐局翻看）",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        // 文本类棋谱（PGN/中文记谱/ICCS/FEN…）：交原文本管线自动识别（内含二进制护栏）
                        openBookBytes(book, bytes, null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "openUserUploadBook: ${e.message}", e)
            Toast.makeText(this, "打开《${book.name}》失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** ★ 后台线程预解码 CBL/CBR（非象棋桥文件返回 null，交由主线程按原管线处理）。
     *   把最耗时的象棋桥解码挡在主线程之外，保证"任何 CBL 上传都不会再点不动"。 */
    private fun predecodeCbl(book: com.pikafish.chess.qipu.QipuBook, bytes: ByteArray):
        List<com.pikafish.chess.qipu.CblDecoder.CblGame>? {
        // ★ 按内容而非扩展名判定：象棋桥的棋库(.cbl)与单局记录(.cbl/.cbr) 头部都是 "CCBridge" 魔数。
        //   这样从 QQ 下载的 .cbr 单局棋谱也能走同一条后台解码，不再被当文本硬解。
        if (bytes.size < 8 || String(bytes, 0, 8, Charsets.US_ASCII) != "CCBridge") return null
        return try {
            com.pikafish.chess.qipu.CblDecoder.decodeBook(bytes, book.name)
        } catch (e: Exception) { emptyList() }
    }

    /** ★ 外部文件的归属：按【内容】判定，不看后缀名（QQ 下载常把什么都标成 .zip / octet-stream）。 */
    private enum class IncomingKind { PDF, QIPU, SKIN_ZIP, SKIN_APK }

    /**
     * ★ 外部应用（QQ / 文件管理器「打开其他应用」）送进来的文件：先按【内容】嗅探，再分流到对应归属。
     *
     * 由 SplashActivity 趁 URI 读权限还在时把文件内容落到缓存目录，再把本地路径经 Intent 转交过来
     * （见 SplashActivity.cacheIncomingFromOuterApp）。分流规则：
     *   · PDF 读物      → 「更多 ▸ 外置读物」书架并直接开读；
     *   · 棋子皮肤包/皮肤APK → 「视觉 ▸ 个性化」里导入成皮肤包（红/黑棋子 + 洗净桌布）；
     *   · 其余棋谱（CBL/CBR/XQF/PGN/TXT/JSON…）→ 宝库「我的上传」并直接开谱。
     *
     * ★ 判定一律以文件头（魔数）与包内清单为准：后缀名相同（都叫 .zip）也能各归各位，
     *   该进张三的进张三、该进李四的进李四，绝不会无差别地全塞进宝库。
     */
    private fun handleIncomingExternalFile() {
        val path = intent?.getStringExtra(SplashActivity.EXTRA_INCOMING_PATH) ?: return
        val name = intent?.getStringExtra(SplashActivity.EXTRA_INCOMING_NAME)
            ?.takeIf { it.isNotBlank() } ?: java.io.File(path).name
        // 用过即清：避免旋屏 / 再次 onNewIntent 时重复导入同一份
        intent?.removeExtra(SplashActivity.EXTRA_INCOMING_PATH)
        intent?.removeExtra(SplashActivity.EXTRA_INCOMING_NAME)
        try {
            val f = java.io.File(path)
            if (!f.exists() || f.length() <= 0L) {
                toast("《$name》内容为空或已被清理，无法打开")
                runCatching { f.delete() }
                return
            }
            // ★ 按内容嗅探分流（后缀名不可信）
            val kind = sniffIncomingKind(f, name)
            when (kind) {
                IncomingKind.PDF -> {
                    // PDF 读物：收进「外置读物」书架并直接开读（与棋谱走不同归属，各得其所）
                    importPdfFileToShelf(f, name)
                    runCatching { f.delete() }
                }
                IncomingKind.SKIN_ZIP, IncomingKind.SKIN_APK -> {
                    // 棋子皮肤包 / 皮肤APK：导入「个性化」，成包后落到个性化界面
                    importSkinFromOutside(f, name, isApk = kind == IncomingKind.SKIN_APK)
                }
                IncomingKind.QIPU -> {
                    val bytes = f.readBytes()
                    // ① 先落进宝库「我的上传」：哪怕本次打开失败，文件也不丢，之后在宝库随时找得到。
                    val stored = com.pikafish.chess.qipu.QipuLibrary.persistUserUpload(this, name, bytes)
                    if (stored == null) { toast("《$name》无法保存到宝库"); return }
                    // ② 直接开谱：与在「宝库 → 我的上传」里点这一条完全是同一条链路。
                    val book = com.pikafish.chess.qipu.QipuBook(
                        name = stored.substringBeforeLast('.'),
                        file = stored,
                        rawSize = bytes.size,
                        isUserUpload = true
                    )
                    toast("已从外部打开《${book.name}》，并存进宝库·我的上传")
                    openUserUploadBook(book)
                    runCatching { f.delete() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleIncomingExternalFile: ${e.message}", e)
            toast("打开外部文件失败：${e.message}")
            runCatching { java.io.File(path).delete() }
        }
    }

    /**
     * ★ 内容嗅探：读文件头（魔数）+ 包内清单判定真实类型，彻底不理会后缀名。
     *   %PDF → PDF；CCBridge → 象棋桥棋库/棋谱；"XQ" → XQF；PK.. → ZIP 再细看；其余按文本棋谱。
     */
    private fun sniffIncomingKind(f: java.io.File, name: String): IncomingKind {
        val head = ByteArray(8)
        val n = try { f.inputStream().use { it.read(head) } } catch (_: Exception) { -1 }
        // 后缀名兜底：极少数 PDF 内容无 %PDF 头，按名字也认下（与既有 isPdfFile 一致）
        if (name.lowercase().endsWith(".pdf")) return IncomingKind.PDF
        if (n >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "%PDF") return IncomingKind.PDF
        if (n >= 8 && String(head, 0, 8, Charsets.US_ASCII) == "CCBridge") return IncomingKind.QIPU
        // XQF 棋谱：文件头两字节固定为 "XQ"
        if (n >= 2 && head[0] == 'X'.code.toByte() && head[1] == 'Q'.code.toByte()) return IncomingKind.QIPU
        // ZIP 家族（本地文件 50 4B 03 04 / 空档 05 06 / 分卷 07 08）
        if (n >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte())) {
            return sniffZipKind(f)
        }
        // 文本类棋谱（PGN / TXT / JSON…）→ 宝库
        return IncomingKind.QIPU
    }

    /**
     * ★ ZIP 包再细分（同样只看内容）：
     *   · 含 AndroidManifest.xml → 是 APK，当作「皮肤来源包」，走 APK 高智能提取；
     *   · 全是图片（png/jpg/棋子图/桌布图）而无棋谱 → 皮肤包 → 「个性化」；
     *   · 含棋谱条目 → 棋谱 → 宝库；
     *   · 其余兜底归宝库（与既有行为一致，不会把文件弄丢）。
     */
    private fun sniffZipKind(f: java.io.File): IncomingKind {
        return try {
            // ★ 用 ISO-8859-1 解条目名：GBK 名的包默认按 UTF-8 解会抛 IllegalArgumentException，
            //   导致整包被误判兜底。这里只关心 ASCII 的扩展名/文件名，乱码不影响判定。
            java.util.zip.ZipFile(f, Charsets.ISO_8859_1).use { zip ->
                var img = 0
                var record = 0
                var scanned = 0
                val it = zip.entries()
                while (it.hasMoreElements() && scanned < 6000) {
                    val e = it.nextElement(); scanned++
                    val lower = e.name.lowercase()
                    if (lower == "androidmanifest.xml" || lower.endsWith("/androidmanifest.xml")) {
                        return IncomingKind.SKIN_APK
                    }
                    if (e.isDirectory) continue
                    when (lower.substringAfterLast('.', "")) {
                        "png", "jpg", "jpeg", "webp", "bmp", "gif", "svg" -> img++
                        "cbl", "cbr", "xqf", "pgn", "pfc", "che", "obk", "pkb", "ubb", "mxq", "qyl", "txt", "json" -> record++
                    }
                }
                when {
                    record > 0 && img == 0 -> IncomingKind.QIPU
                    img > 0 -> IncomingKind.SKIN_ZIP
                    else -> IncomingKind.QIPU
                }
            }
        } catch (_: Exception) { IncomingKind.QIPU }
    }

    /**
     * ★ 从外部打开的是棋子皮肤包（Zip / 皮肤 APK）：走「个性化」里完全相同的那条导入链路
     *   （SkinManager.importZipBlocking），完成后把用户直接带到「个性化」界面确认勾选。
     *   重活（解压 / 解码 / 净化 / OCR）全部丢后台线程，主线程只弹提示、跳界面。
     */
    private fun importSkinFromOutside(f: java.io.File, name: String, isApk: Boolean) {
        toast("已识别为棋子皮肤《$name》，正在导入个性…")
        initScope.launch(Dispatchers.IO) {
            val (packs, msg) = try {
                com.pikafish.chess.ui.SkinManager.importZipBlocking(
                    this@MainActivity, Uri.fromFile(f), name, isApk = isApk
                )
            } catch (e: Exception) {
                emptyList<com.pikafish.chess.ui.SkinManager.Pack>() to "导入失败：${e.message}"
            } finally {
                // 缓存的临时副本用完即弃（SkinManager 已把素材正式落盘）
                runCatching { f.delete() }
            }
            withContext(Dispatchers.Main) {
                if (packs.isEmpty()) {
                    toast("《$name》没能提取出皮肤素材：$msg")
                    return@withContext
                }
                // 自动勾选：与「个性化」内批量导入后的行为完全一致（红/黑各挑第一套，其余可再手动改选）
                var setR = com.pikafish.chess.ui.SkinManager.currentSelection(this@MainActivity).first.isNotBlank()
                var setB = com.pikafish.chess.ui.SkinManager.currentSelection(this@MainActivity).second.isNotBlank()
                packs.forEach { p ->
                    if (!setR && p.hasRed) { com.pikafish.chess.ui.SkinManager.setRed(this@MainActivity, p.id); setR = true }
                    if (!setB && p.hasBlack) { com.pikafish.chess.ui.SkinManager.setBlack(this@MainActivity, p.id); setB = true }
                }
                toast("已导入『个性化』：${packs.joinToString("、") { it.name }}（可点「确认应用」启用）")
                startActivity(Intent(this@MainActivity, com.pikafish.chess.ui.PersonalizeActivity::class.java))
            }
        }
    }

    /** ★ 外置读物「♟ 正选·摆子识别」：PDF 阅读器把框住的棋盘截图存好后，用 Intent 把图片地址送来。
     *   这里直接进摆子模式并识别，用户不用再拍照/上传 —— 与主界面「摆子 → 拍照比对」是同一条识别管线。 */
    private fun handleIncomingRecognizeImage() {
        val s = intent?.getStringExtra(EXTRA_RECOGNIZE_IMAGE) ?: return
        if (s.isBlank()) return
        // 用过即清：避免旋屏 / 再次 onNewIntent 时重复识别
        intent?.removeExtra(EXTRA_RECOGNIZE_IMAGE)
        try {
            if (::chessGame.isInitialized && !chessGame.isSetupMode) {
                chessGame.enterSetupMode()
                selectedPieceType = null
                selectedPieceColor = null
                updateSetupPieceSelection()
            }
            performRecognition(Uri.parse(s))
        } catch (e: Exception) {
            Log.e(TAG, "handleIncomingRecognizeImage: ${e.message}", e)
            toast("摆子识别失败：${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ★ 阅读器在栈上把主界面拉到前台时走这里（CLEAR_TOP + SINGLE_TOP 复用本实例）
        handleIncomingExternalFile()
        handleIncomingRecognizeImage()
    }

    /** ★ 打开宝库「本地文档」：从 HHXQ 绝对路径读出该文件（存档收集到的任一文档），
     *   再走与「我的上传」完全相同的自动识别管线，直接载入主界面拆解，无需手工导入。 */
    private fun openLocalDocBook(book: com.pikafish.chess.qipu.QipuBook) {
        try {
            if (bookMemorizer.active) stopBookMemorize()
            // ★ 同「我的上传」：读盘 + CBL 解码放后台线程，绝不阻塞主界面。
            initScope.launch(Dispatchers.IO) {
                val bytes = try {
                    com.pikafish.chess.qipu.QipuLibrary.readLocalDoc(book.file)
                } catch (e: Exception) { null }
                if (bytes == null) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "找不到《${book.name}》，可能已被删除", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val preDecoded = predecodeCbl(book, bytes)
                runOnUiThread { loadLocalDocDirect(book, bytes, preDecoded) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "openLocalDocBook: ${e.message}", e)
            Toast.makeText(this, "打开《${book.name}》失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** ★ 本地文档「脚本化人工导入」：完全对齐「我的对局」那条已验证链路——
     *   真实 HHXQ 文件 → 棋谱导入“自动识别” → 直接导入并停在主界面拆解。
     *   关键：绝不调用 cancelSetup/clearSelection/viewingHistory 重置，否则刚导入的走法会被冲回起始局面（看起来“空”）。 */
    private fun loadLocalDocDirect(
        book: com.pikafish.chess.qipu.QipuBook, bytes: ByteArray,
        preDecodedCbl: List<com.pikafish.chess.qipu.CblDecoder.CblGame>? = null
    ) {
        val ext = book.file.substringAfterLast('.', "").lowercase()
        // ★ QYL 棋谱（启阳象棋）：容器内是逐局 zstd 帧，一册几十局。按内容识别，不看后缀名。
        //   除标准 QYLY 头外，还认"同后缀、容器头却是变体"的文件（.qyl 后缀 + 内含 zstd 帧），
        //   保证换一个 .qyl 文件也照样打得开。
        if (com.pikafish.chess.qipu.QylDecoder.isQyl(bytes) ||
            (ext == "qyl" && com.pikafish.chess.qipu.QylDecoder.looksLikeQyl(bytes))
        ) {
            val games = try {
                com.pikafish.chess.qipu.QylDecoder.decode(bytes, book.name)
            } catch (e: Exception) { emptyList() }
            val msg: String = if (games.isEmpty()) {
                "无法识读《${book.name}》（QYL 棋谱损坏或暂不支持的变体）"
            } else {
                val g = games[0]
                val m = chessGame.importPgn(g.toPgn())
                try { chessGame.setQipuComments(g.introComment, g.endComment, g.moveComments, g.commentaryBlocks) } catch (e: Exception) {}
                currentVaultGames = games.map { com.pikafish.chess.qipu.QipuGame(it) }
                if (games.size > 1) "$m（本册共 ${games.size} 则，底部「上一则/下一则」逐局翻看）" else m
            }
            enterNotationAnalysisPanel()
            showCurrentQipuComment()
            chessGame.onBoardChanged?.invoke()
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        // 二进制 CBL / XQF 走专用解码
        if (ext == "cbl" || ext == "xqf") {
            val msg: String = try {
                if (ext == "xqf") chessGame.importXqf(bytes)
                else {
                    val games = preDecodedCbl ?: try {
                        com.pikafish.chess.qipu.CblDecoder.decodeBook(bytes, book.name)
                    } catch (e: Exception) { emptyList() }
                    if (games.isEmpty()) "《${book.name}》未能识读出有效棋局"
                    else {
                        val g = games[0]
                        val m = chessGame.importPgn(g.toPgn())
                        try { chessGame.setQipuComments(g.introComment, g.endComment, g.moveComments, g.commentaryBlocks) } catch (e: Exception) {}
                        currentVaultGames = games.map { com.pikafish.chess.qipu.QipuGame(it) }
                        m
                    }
                }
            } catch (e: Exception) { "读《${book.name}》失败: ${e.message}" }
            enterNotationAnalysisPanel()
            showCurrentQipuComment()
            chessGame.onBoardChanged?.invoke()
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        // 文本类（含 JSON）
        val content = decodeChessText(bytes)
        if (content.trim().isEmpty()) {
            Toast.makeText(this, "《${book.name}》内容为空", Toast.LENGTH_LONG).show()
            return
        }
        if (ext == "json") {
            loadLocalDocJson(content, book)
        } else {
            loadLocalDocTxt(content, book)
        }
    }

    /** ★ 本地文档 JSON：refType=txtfile 取内嵌可变招 TXT 走自动识别；boardSnapshots 完整对局快照直接恢复。 */
    private fun loadLocalDocJson(content: String, book: com.pikafish.chess.qipu.QipuBook) {
        try {
            val json = org.json.JSONObject(content)
            val refType = json.optString("refType")
            if (refType == "txtfile") {
                val given = json.optString("givenTxt").trim()
                if (given.isNotEmpty()) loadLocalDocTxt(given, book)
                else Toast.makeText(this, "《${book.name}》未内嵌棋谱内容", Toast.LENGTH_LONG).show()
                return
            }
            if (json.has("boardSnapshots")) {
                if (chessGame.loadFullStateJson(json)) {
                    enterNotationAnalysisPanel()
                    chessGame.onBoardChanged?.invoke()
                    Toast.makeText(this, "已恢复《${book.name}》完整对局快照", Toast.LENGTH_LONG).show()
                } else Toast.makeText(this, "《${book.name}》对局快照无法解析", Toast.LENGTH_LONG).show()
                return
            }
            loadLocalDocTxt(content, book)
        } catch (e: Exception) {
            loadLocalDocTxt(content, book)
        }
    }

    /** ★ 本地文档文本「脚本化导入」：detectFormat + importNotation 优先，失败兜底 loadFromGivingTxt（可变招 TXT）。
     *   成功后 enterNotationAnalysisPanel 停在主界面，绝不重置棋盘。 */
    private fun loadLocalDocTxt(content: String, book: com.pikafish.chess.qipu.QipuBook) {
        val c = content.trim()
        if (c.isEmpty()) {
            Toast.makeText(this, "《${book.name}》内容为空", Toast.LENGTH_LONG).show()
            return
        }
        val resolved = detectFormat(c)
        if (importNotation(c, resolved, false)) {
            enterNotationAnalysisPanel()
            showCurrentQipuComment()
            chessGame.onBoardChanged?.invoke()
            Toast.makeText(this, "已导入《${book.name}》（自动识别）到主界面拆解", Toast.LENGTH_LONG).show()
            return
        }
        // 兜底：可变招通用 TXT
        val steps = chessGame.loadFromGivingTxt(c)
        if (steps > 0) {
            enterNotationAnalysisPanel()
            chessGame.onBoardChanged?.invoke()
            Toast.makeText(this, "已加载《${book.name}》（$steps 步，含变招）", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "未能识别《${book.name}》内容", Toast.LENGTH_LONG).show()
        }
    }

    /** 统一的“读入一本书”管线：按扩展名/内容分流 → 自动识别导入 → 切到棋谱拆解面板。 */
    private fun openBookBytes(
        book: com.pikafish.chess.qipu.QipuBook, bytes: ByteArray,
        preDecodedCbl: List<com.pikafish.chess.qipu.CblDecoder.CblGame>? = null
    ) {
        val ext = book.file.substringAfterLast('.', "").lowercase()

        // ★★ 先退出摆子模式再导入：若用户此前进过「摆子/摆子识别」未退出，此处会原样恢复其进入前的对局。
        //   必须放在导入【之前】——否则一旦放到导入之后，恢复动作会把刚导入的棋谱整盘覆盖掉（0/0 回合、进退键全灰）。
        chessGame.cancelSetup()

        // 按扩展名/内容分流：象棋桥 CBL/CBR、XQF(二进制) 走专用解码，JSON 走全状态快照，其余走文本自动识别
        val msg: String = when {
            preDecodedCbl != null || ext == "cbl" || ext == "cbr" -> {
                val games = preDecodedCbl ?: try {
                    com.pikafish.chess.qipu.CblDecoder.decodeBook(bytes, book.name)
                } catch (e: Exception) { emptyList() }
                if (games.isEmpty()) {
                    // ★ 绝不把二进制 CBL 当文本硬解（那会把主线程拖死，正是"点我的上传就卡死"的根源）。
                    //   解码不出就明确告知，宁可不打开，也绝不让界面卡住。
                    "无法识读《${book.name}》（暂不支持该 CBL 格式变体，或文件已损坏）"
                } else {
                    val g = games[0]
                    val m = chessGame.importPgn(g.toPgn())
                    try {
                        chessGame.setQipuComments(g.introComment, g.endComment, g.moveComments, g.commentaryBlocks)
                    } catch (e: Exception) { }
                    currentVaultGames = games.map { com.pikafish.chess.qipu.QipuGame(it) }
                    m
                }
            }
            ext == "xqf" -> chessGame.importXqf(bytes)
            // ★ QYL 棋谱（启阳象棋）：与「宝库 → 我的上传 / 外部打开」同一条解码链。
            //   放在二进制护栏之前，否则会被误报成"二进制棋谱，暂不支持该格式"。
            com.pikafish.chess.qipu.QylDecoder.isQyl(bytes) ||
                (ext == "qyl" && com.pikafish.chess.qipu.QylDecoder.looksLikeQyl(bytes)) -> {
                val games = try {
                    com.pikafish.chess.qipu.QylDecoder.decode(bytes, book.name)
                } catch (e: Exception) { emptyList() }
                if (games.isEmpty()) {
                    "无法识读《${book.name}》（QYL 棋谱损坏或暂不支持的变体）"
                } else {
                    val g = games[0]
                    val m = chessGame.importPgn(g.toPgn())
                    try {
                        chessGame.setQipuComments(g.introComment, g.endComment, g.moveComments, g.commentaryBlocks)
                    } catch (e: Exception) { }
                    currentVaultGames = games.map { com.pikafish.chess.qipu.QipuGame(it) }
                    if (games.size > 1) "$m（本册共 ${games.size} 则，底部「上一则/下一则」逐局翻看）" else m
                }
            }
            ext == "json" -> importLocalJsonState(bytes, book.name)
            else -> {
                if (com.pikafish.chess.qipu.QipuLibrary.isZip(bytes) ||
                    com.pikafish.chess.qipu.QipuLibrary.isRar(bytes)) {
                    // 压缩包：能解出的棋局已在 decodeGames 里处理；走到这说明包内没有可识别的棋谱。
                    // 绝不能把二进制当文本硬解（那会把主线程拖死，正是"点宝库就卡死"的根源）。
                    "无法识读《${book.name}》（压缩包内未找到可识别的棋谱格式）"
                } else if (com.pikafish.chess.qipu.QipuLibrary.isChm(bytes)) {
                    "无法识读《${book.name}》（CHM 电子书内未找到可识别的棋谱页面）"
                } else if (looksLikeBinary(bytes)) {
                    "无法识读《${book.name}》（二进制棋谱，暂不支持该格式）"
                } else {
                    // ★ GBK 容错解码：宝库/外部打开的 GBK 棋谱不再被当 UTF-8 读成乱码
                    val content = decodeChessText(bytes)
                    if (content.trim().isEmpty()) {
                        "《${book.name}》内容为空"
                    } else {
                        if (importTextToBoard(content.trim(), book.name)) "已载入《${book.name}》"
                        else "未能识别《${book.name}》内容"
                    }
                }
            }
        }

        // 切换回棋谱面板并刷新
        layoutParamsContent.visibility = View.GONE
        layoutGameContent.visibility = View.GONE
        layoutGameSub.visibility = View.GONE
        layoutGameSubRow2.visibility = View.GONE
        layoutNotationContent.visibility = View.VISIBLE
        layoutVisualContent.visibility = View.GONE
        layoutMoreContent.visibility = View.GONE
        navActiveTab = btnTabNotation; navPanelCollapsed = false
        highlightTab(btnTabNotation)
        if (scrollViewExplanation.visibility != View.VISIBLE) {
            scrollViewExplanation.visibility = View.VISIBLE
        }
        updateStepInfo()
        restoreHighlightsFromHistory()
        refreshExplanationDisplay()
        showCurrentQipuComment()
        chessBoard.clearSelection()
        chessBoard.viewingHistory = false
        chessGame.onBoardChanged?.invoke()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** ★ 二进制护栏：前缀含 NUL 字节即判为二进制（GBK/UTF-8 文本棋谱不会含 NUL），
     *   用于拦住"把 CBL/MXQ/压缩包当文本硬解"这种会拖死主线程的操作。 */
    private fun looksLikeBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 1024)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /** 统一文本棋谱自动识别：detectFormat+importNotation 优先，失败回退 loadFromGivingTxt（可变招 TXT）。 */
    private fun importTextToBoard(content: String, name: String): Boolean {
        return try {
            val c = content.trim()
            if (c.isEmpty()) return false
            val resolved = detectFormat(c)
            if (importNotation(c, resolved, false)) return true
            val steps = chessGame.loadFromGivingTxt(c)
            steps > 0
        } catch (e: Exception) { false }
    }

    /** 处理 HHXQ 本地 JSON 文档：完整对局快照(含 boardSnapshots)直接恢复；引用型(refType=txtfile)取内嵌文本走自动识别。 */
    private fun importLocalJsonState(bytes: ByteArray, name: String): String {
        val text = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { "" }
        if (text.trim().isEmpty()) return "《$name》内容为空"
        return try {
            val json = org.json.JSONObject(text)
            val refType = json.optString("refType")
            when {
                refType == "txtfile" -> {
                    val given = json.optString("givenTxt").trim()
                    if (given.isNotEmpty() && importTextToBoard(given, name)) "已载入《$name》（本地存档引用）"
                    else "未能识别《$name》内容"
                }
                json.has("boardSnapshots") -> {
                    if (chessGame.loadFullStateJson(json)) {
                        chessGame.redIsHuman = prefs.getBoolean(KEY_RED_HUMAN, true)
                        chessGame.blackIsHuman = prefs.getBoolean(KEY_BLACK_HUMAN, false)
                        "已恢复《$name》对局快照"
                    } else "《$name》快照无法解析"
                }
                else -> if (importTextToBoard(text.trim(), name)) "已载入《$name》" else "未能识别《$name》内容"
            }
        } catch (e: Exception) {
            if (importTextToBoard(text.trim(), name)) "已载入《$name》" else "未能识别《$name》内容"
        }
    }

    // ★ 对弈翻页（仅主棋盘页）
    private fun toggleGamePage() {
        layoutGamePage1.visibility = View.VISIBLE
        btnTabGame.text = "对弈"
    }

    // ★ 画中画广播接收器
    private var pipReceiver: android.content.BroadcastReceiver? = null

    private fun registerPipReceiver() {
        pipReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.pikafish.chess.ENTER_PIP") {
                    Log.i(TAG, "★ 收到画中画广播请求")
                    runOnUiThread {
                        enterPipMode()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter("com.pikafish.chess.ENTER_PIP")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipReceiver, filter)
        }
        Log.i(TAG, "★ 画中画广播接收器已注册")
    }

    // ★ 进入画中画模式 — 小窗口、纯净棋盘、不遮挡目标APP
    private fun enterPipMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    Log.w(TAG, "★ 系统不支持画中画")
                    Toast.makeText(this, "此设备不支持画中画", Toast.LENGTH_LONG).show()
                    moveTaskToBack(true)
                    return
                }

                // ★ 先隐藏所有非棋盘UI，让画中画只显示棋盘
                hideUiForPip()

                val boardView = findViewById<View>(R.id.chessBoard)
                val builder = android.app.PictureInPictureParams.Builder()

                // ★ 设置源区域为棋盘视图（仅用于平滑过渡动画）
                if (boardView != null && boardView.width > 0 && boardView.height > 0) {
                    val loc = IntArray(2)
                    boardView.getLocationOnScreen(loc)
                    val sourceRectHint = android.graphics.Rect(
                        loc[0], loc[1],
                        loc[0] + boardView.width,
                        loc[1] + boardView.height
                    )
                    builder.setSourceRectHint(sourceRectHint)
                }

                // ★ 使用棋盘比例9:10，系统会自动选择最小合适尺寸
                builder.setAspectRatio(android.util.Rational(9, 10))

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(true)
                }

                val params = builder.build()
                val result = enterPictureInPictureMode(params)
                Log.i(TAG, "★ 进入画中画模式: result=$result")
                if (result) {
                    Toast.makeText(this, "已进入画中画", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG, "★ enterPictureInPictureMode 返回 false")
                    restoreUiFromPip()
                    Toast.makeText(this, "无法进入画中画，请确保APP在前台", Toast.LENGTH_LONG).show()
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "★ 进入画中画失败(IllegalStateException): ${e.message}", e)
                restoreUiFromPip()
                Toast.makeText(this, "请先切回黄昏象棋再点画中画", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "★ 进入画中画失败: ${e.message}", e)
                restoreUiFromPip()
                Toast.makeText(this, "画中画失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Android版本太低，不支持画中画", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }

    // ★ 画中画模式变化处理
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // 进入画中画：隐藏非必要UI，只保留棋盘
            Log.i(TAG, "★ 进入画中画 — 简化UI")
            hideUiForPip()
        } else {
            // 退出画中画：恢复UI
            Log.i(TAG, "★ 退出画中画 — 恢复UI")
            restoreUiFromPip()
        }
    }

    private fun hideUiForPip() {
        try {
            // ★ 隐藏所有非棋盘UI，只保留棋盘
            findViewById<View>(R.id.layoutStatusBar)?.visibility = View.GONE
            findViewById<View>(R.id.layoutSetupPanel)?.visibility = View.GONE
            findViewById<View>(R.id.layoutNormalControls)?.visibility = View.GONE
            findViewById<View>(R.id.tvThinking)?.visibility = View.GONE
            findViewById<View>(R.id.scrollViewExplanation)?.visibility = View.GONE
            // ★ 设置背景透明，画中画更干净
            window.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            findViewById<View>(android.R.id.content)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } catch (e: Exception) {
            Log.e(TAG, "hideUiForPip异常: ${e.message}", e)
        }
    }

    private fun restoreUiFromPip() {
        try {
            findViewById<View>(R.id.layoutStatusBar)?.visibility = View.VISIBLE
            findViewById<View>(R.id.layoutNormalControls)?.visibility = View.VISIBLE
            findViewById<View>(R.id.tvThinking)?.visibility = View.VISIBLE
            findViewById<View>(R.id.scrollViewExplanation)?.visibility = View.VISIBLE
            // ★ 恢复背景
            findViewById<View>(android.R.id.content)?.setBackgroundColor(resources.getColor(R.color.board_bg))
            // ★ 菜单复原后重算思考区贴纸边距
            if (::frameSetupThumb.isInitialized) updateStickerMargins()
        } catch (e: Exception) {
            Log.e(TAG, "restoreUiFromPip异常: ${e.message}", e)
        }
    }

    // ── ★ 文件导入（通过文件选择器）──

    /** ★ 导入文字对话框：支持粘贴文字 + 从文件选择 + 导出并打谱。
     *  format 传 "AUTO" 时自动识别内容格式（合并后的"导入棋谱"入口）；否则按指定格式导入。 */
    // ══════════════════════════════════════════════════════
    //  ★★★ 练开局（Practice Opening）★★★
    //  仿挑战模式：人对皮卡鱼(固定14层)，可选8/10/12.../22步步数等级，
    //  红先/黑先自选，人执一方下满约定手数即锁盘；悔棋可解锁续走；
    //  弹出「可复盘/未结束」随状态变化，达成后可步入复盘主界面。
    //══════════════════════════════════════════════════════
    private val PRACTICE_HINT = "practice_state"

    private data class PracSession(val limit: Int, val side: com.pikafish.chess.model.PieceColor, val fen: String, val moves: List<String>)

    private fun hasSavedPractice(): Boolean = prefs.getString(PRACTICE_HINT, null) != null

    private fun loadSavedPractice(): PracSession? {
        try {
            val s = prefs.getString(PRACTICE_HINT, null) ?: return null
            val o = org.json.JSONObject(s)
            val arr = o.optJSONArray("moves")
            val moves = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) moves.add(arr.getString(i))
            return PracSession(
                o.optInt("limit", 8),
                if (o.optString("side", "red") == "black") com.pikafish.chess.model.PieceColor.BLACK else com.pikafish.chess.model.PieceColor.RED,
                o.optString("fen", ""),
                moves
            )
        } catch (e: Exception) { return null }
    }

    private fun savePracticeState() {
        if (!chessGame.practiceActive) return
        try {
            val o = org.json.JSONObject()
            o.put("limit", chessGame.practiceLimit)
            o.put("side", if (chessGame.practiceUserSide == com.pikafish.chess.model.PieceColor.RED) "red" else "black")
            o.put("fen", chessGame.bookLineStartFen())
            o.put("moves", org.json.JSONArray(chessGame.bookLineMoves()))
            prefs.edit().putString(PRACTICE_HINT, o.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存练开局存档失败: ${e.message}")
        }
    }

    // ★ 练开局下满约定步数后，给引擎发一次"自然关闭"标记，避免 100% 后引擎仍空转占资源
    @Volatile private var practiceEngineStoppedOnComplete = false

    /** 刷新练开局状态条与控制按钮（随每次棋盘变化触发） */
    private fun refreshPracticeUi() {
        if (::chessGame.isInitialized.not()) return
        runOnUiThread {
            if (!chessGame.practiceActive) {
                if (layoutPracticeBar.visibility != View.GONE) layoutPracticeBar.visibility = View.GONE
                practiceEngineStoppedOnComplete = false
                return@runOnUiThread
            }
            if (layoutPracticeBar.visibility != View.VISIBLE) layoutPracticeBar.visibility = View.VISIBLE
            chessGame.refreshPracticeLock()
            val limit = chessGame.practiceLimit
            val userMoves = chessGame.practiceUserMoveCount()
            val side = if (chessGame.practiceUserSide == com.pikafish.chess.model.PieceColor.RED) "红先" else "黑先"
            val locked = chessGame.practiceLocked
            tvPracticeStatus.text = "$side·$userMoves/${limit}步" + if (locked) "·已锁" else ""
            btnPracticeReview.text = if (chessGame.isPracticeComplete()) "可复盘" else "未结束"
            val ritualNow = ritualActive() && ritualStage() == "opening"
            btnPracticeUndo.isEnabled = chessGame.practiceUserMoveCount() > 0 && !ritualNow
            if (ritualNow && tvPracticeStatus.text?.contains("落子无悔") != true) {
                tvPracticeStatus.text = tvPracticeStatus.text.toString() + "·仪式感：落子无悔"
            }
            // ★ 100% 达成 → 引擎自然停掉（可复盘开关已亮）。悔棋解锁继续练时，AI 走棋的自愈逻辑会自动重启引擎。
            if (chessGame.isPracticeComplete()) {
                if (!practiceEngineStoppedOnComplete) {
                    practiceEngineStoppedOnComplete = true
                    stopPracticeEngineOnComplete()
                }
            } else {
                practiceEngineStoppedOnComplete = false
            }
            savePracticeState()
        }
    }

    /** 练开局下满约定步数后关闭引擎（只关主引擎自己，绝不碰解说引擎等其他线路） */
    private fun stopPracticeEngineOnComplete() {
        Thread {
            try {
                val e = chessGame.engine
                if (e != null && e.isEngineReady()) {
                    Log.i(TAG, "练开局已下满约定步数，可复盘达成，引擎已自然关闭")
                    e.shutdown()
                }
            } catch (_: Exception) {}
        }.apply { name = "practice-engine-stop" }.start()
    }

    private fun showPracticeStartDialog() {
        if (chessGame.isSetupMode) { toast("请先退出摆子模式再练开局"); return }
        val stepVals = listOf(8, 10, 12, 14, 16, 18, 20, 22)
        val scroll = android.widget.ScrollView(this)
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(30, 22, 30, 4)
        }

        val stepLab = android.widget.TextView(this).apply { text = "约定步数（人执一方下满即锁盘）"; textSize = 15f }

        // ★★ 双列排布（用户钦定）：8~22 八个档拆成左右两列，中间一根竖线分隔 ——
        //   左列＝小档（8/10/12/14），右列＝大档（16/18/20/22），各自仍按增序自上而下；
        //   竖线把「两段序列」标得清清楚楚，行高砍半 → 不再一竖排占去老高一块。
        val stepCols = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val leftGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rightGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val colDivider = android.view.View(this).apply {
            setBackgroundColor(0x33000000)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (1.2f * resources.displayMetrics.density).toInt(),
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        var selectedStep = 8
        fun makeStepRb(v: Int): android.widget.RadioButton = android.widget.RadioButton(this).apply {
            // ★ 程序化创建的 RadioButton 必须显式分配唯一 id，否则 checkedRadioButtonId 恒为 -1
            id = android.view.View.generateViewId()
            text = "$v 步"
            textSize = 16f
            // ★ 行高砍半：清掉 RadioButton 默认的 48dp 最小高与内边距，只留文字+圆钮本身的高度
            minimumHeight = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, checked -> if (checked) selectedStep = v }
        }

        leftGroup.addView(makeStepRb(stepVals[0]))
        leftGroup.addView(makeStepRb(stepVals[1]))
        leftGroup.addView(makeStepRb(stepVals[2]))
        leftGroup.addView(makeStepRb(stepVals[3]))
        rightGroup.addView(makeStepRb(stepVals[4]))
        rightGroup.addView(makeStepRb(stepVals[5]))
        rightGroup.addView(makeStepRb(stepVals[6]))
        rightGroup.addView(makeStepRb(stepVals[7]))
        (leftGroup.getChildAt(0) as android.widget.RadioButton).isChecked = true
        // ★ 两列互斥单选：点左列清右列、点右列清左列（clearCheck 触发的 -1 回调不改变 selectedStep）
        leftGroup.setOnCheckedChangeListener { _, id -> if (id != -1) rightGroup.clearCheck() }
        rightGroup.setOnCheckedChangeListener { _, id -> if (id != -1) leftGroup.clearCheck() }

        stepCols.addView(leftGroup)
        stepCols.addView(colDivider)
        stepCols.addView(rightGroup)

        val sideLab = android.widget.TextView(this).apply { text = "执子（红先=人红，黑先=人黑）"; textSize = 15f; setPadding(0, 10, 0, 0) }
        val sideGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        // ★ 修复：执子红/黑同样显式分配唯一 id（原代码两个按钮都是 NO_ID，checkedRadioButtonId
        //   恒等于黑方按钮 id，导致"红先"默认也被判成黑先）
        val rbRed = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "红先（人执红）"
            textSize = 16f
            setPadding(0, 6, 0, 6)
        }
        val rbBlack = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "黑先（人执黑）"
            textSize = 16f
            setPadding(0, 6, 0, 6)
        }
        rbRed.isChecked = true
        sideGroup.addView(rbRed)
        sideGroup.addView(rbBlack)

        content.addView(stepLab)
        content.addView(stepCols)
        content.addView(sideLab)
        content.addView(sideGroup)
        scroll.addView(content)

        val savedResume = loadSavedPractice()
        val b = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("练开局（皮卡鱼14层）")
            .setView(scroll)
            .setNegativeButton("取消", null)
        val dlg = b.create()
        dlg.setButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE, "新开一局") { _, _ ->
            val limit = selectedStep
            val side = if (sideGroup.checkedRadioButtonId == rbBlack.id) com.pikafish.chess.model.PieceColor.BLACK else com.pikafish.chess.model.PieceColor.RED
            startPractice(limit, side, resume = false)
        }
        dlg.setButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL,
            if (savedResume != null) "续盘(${savedResume.limit}步·${if (savedResume.side == com.pikafish.chess.model.PieceColor.RED) "红先" else "黑先"})" else "续盘") { _, _ ->
            val s = savedResume ?: return@setButton
            startPractice(s.limit, s.side, resume = true, savedFen = s.fen, savedMoves = s.moves)
        }
        dlg.setOnShowListener {
            if (savedResume == null) dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).isEnabled = false
        }
        dlg.show()
    }

    private fun startPractice(limit: Int, side: com.pikafish.chess.model.PieceColor, resume: Boolean = false, savedFen: String? = null, savedMoves: List<String> = emptyList()) {
        try { chessGame.engine.stopPonder() } catch (_: Exception) {}
        try { chessGame.engine.stopSearch() } catch (_: Exception) {}
        chessGame.practiceActive = true
        chessGame.practiceLimit = limit
        chessGame.practiceUserSide = side
        chessGame.redIsHuman = side == com.pikafish.chess.model.PieceColor.RED
        chessGame.blackIsHuman = side == com.pikafish.chess.model.PieceColor.BLACK
        chessGame.practiceLocked = false
        if (!resume || savedFen == null || savedFen.isBlank()) {
            // 新开一局：标准开局起步
            chessGame.newGame()
        } else {
            // 续盘：从存档 FEN + 走法重建
            chessGame.loadLineFromFen(savedFen, savedMoves)
            chessGame.refreshPracticeLock()
        }
        layoutPracticeBar.visibility = View.VISIBLE
        refreshPracticeUi()
        // ★★ 练习的严肃性（用户钦定）：开局/续盘这一刻「无差别」关掉所有思考提示显示。
        //    只在进入练习时关一次——用户局中自己再手动开启，我们不再插手。
        closeThinkingHintsForPractice()
        toast("练开局 · ${limit}步 · " + (if (side == com.pikafish.chess.model.PieceColor.RED) "红先" else "黑先") + (if (resume) "（续盘）" else ""))
        // ★ 告知文案（程序内置一句）：延到下一帧再弹，避开上一个对话框正在消失的窗口
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (isFinishing || isDestroyed) return@post
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("练开局 · 思考提示已关闭")
                .setMessage("为了练习的严肃性，已经关闭了所有的思考提示显示，请知悉；若有需要开启，请手动设置打开。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /** ★ 练开局专用：无差别关闭「思考提示」类显示（思考细节 / 分析面板）。
     *  仅在开局与续盘两个入口调用一次；局中用户自行开启的，一概不动。 */
    private fun closeThinkingHintsForPractice() {
        // ① 思考细节
        chessGame.showThinkingDetails = false
        updateDetailButton()
        if (::tvThinking.isInitialized) {
            tvThinking.visibility = View.INVISIBLE
            tvThinking.text = ""
        }
        // ② 分析面板（含盘心浮透层，stopAnalysis 内部会一并撤下）
        if (analysisActive) stopAnalysis()
        // ③ 顶部/浮层解说条：细节关了就一并清掉，别留着旧内容
        if (::tvFloatOverlay.isInitialized) tvFloatOverlay.visibility = View.GONE
    }

    private fun onPracticeUndo() {
        if (!chessGame.practiceActive) return
        val removed = chessGame.practiceUndoUntilUnlocked()
        if (removed > 0) {
            restoreHighlightsFromHistory()
            refreshExplanationDisplay()
            updateStepInfo()
            refreshPracticeUi()
            if (chessGame.isPracticeComplete()) toast("仍达约定回合，可继续悔棋") else toast("已悔棋解锁，可继续下")
        } else {
            toast("没有可悔的棋步")
        }
    }

    // ═══ ★ 仪式感（第一阶段·练开局）：落子无悔 + 复盘即计盘 ═══
    private var ritualCountedThisGame = false

    private fun ritualActive() = prefs.getBoolean("ritual.active", false)
    private fun ritualStage(): String = prefs.getString("ritual.stage", "") ?: ""
    private fun ritualToday(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA).format(java.util.Date())
    private fun ritualOpeningCount(): Int = prefs.getInt("ritual.${ritualToday()}.opening", 0)

    /** 复盘窗口打开即计一盘；满 5 盘提示可进入中局。 */
    private fun ritualCountOnReview() {
        if (!ritualActive() || ritualStage() != "opening" || ritualCountedThisGame) return
        ritualCountedThisGame = true
        val n = ritualOpeningCount() + 1
        prefs.edit().putInt("ritual.${ritualToday()}.opening", n)
            .putInt("ritual.opening", n).apply()
        val msg = if (n >= 5) "已完成 5 个开局 ✓ 可进入②中局阶段" else "已计入：今天完成 $n/5 个开局"
        toast(msg)
    }

    private fun onPracticeReviewTap() {
        if (!chessGame.practiceActive) return
        if (chessGame.practiceUserMoveCount() <= 0) { toast("尚无棋步，暂不可复盘"); return }
        if (chessGame.isPracticeComplete()) {
            showReviewDialog()
            ritualCountOnReview()
        } else {
            toast("尚未下满 ${chessGame.practiceLimit} 步，暂不可复盘")
        }
    }

    private fun exitPractice() {
        chessGame.practiceActive = false
        chessGame.practiceLocked = false
        chessGame.refreshPracticeLock()
        layoutPracticeBar.visibility = View.GONE
        prefs.edit().remove(PRACTICE_HINT).apply()
        toast("已退出练开局")
    }

    // ══════════════════════════════════════════════════════
    //  ★★★ 复盘评估（夹在 导入/导出 之间）★★★
    //══════════════════════════════════════════════════════
    private fun showReviewDialog() {
        val moves = try { chessGame.bookLineMoves() } catch (_: Exception) { emptyList<String>() }
        if (moves.isEmpty()) { toast("当前没有可复盘的棋步"); return }
        val initialFen = try { chessGame.bookLineStartFen() } catch (_: Exception) { "" }
        if (initialFen.isBlank()) { toast("当前局面无可复盘起点"); return }
        val totalRounds = (moves.size + 1) / 2
        val defaultEnd = minOf(20, totalRounds)
        val maxAllowed = 20   // 单次/每批最多 20 个回合

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(14), dp(28), dp(14))
        }
        row.addView(TextView(this).apply { text = "复盘评估 · 选择区间"; setTextColor(0xFFE8D5A3.toInt()); textSize = 16f; gravity = Gravity.CENTER })
        row.addView(TextView(this).apply {
            text = "红一步+黑一步记一回合；只盘主变。单次或每批最多 20 回合；批处理可一次从开局扫到终局。"
            setTextColor(0xFF9A8F7A.toInt()); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(6))
        })
        row.addView(TextView(this).apply {
            text = "📜 复盘历史：点开既往复盘，直接完整展开（不重算、不重复记档）"
            setTextColor(0xFFFFB300.toInt()); textSize = 12f; setPadding(0, dp(2), 0, dp(6))
            setOnClickListener { showReviewLoadDialog() }
        })

        fun numField(default: String): EditText = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(default)
            setTextColor(0xFFF0E6D2.toInt()); textSize = 14f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x335C5040.toInt()); cornerRadius = dp(6).toFloat(); setStroke(1, 0xFFFFB300.toInt())
            }
        }

        val hv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val etStart = numField("1")
        val etEnd = numField(defaultEnd.toString())
        val lpF = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        hv.addView(TextView(this).apply { text = "从第 "; setTextColor(0xFFF0E6D2.toInt()); textSize = 13f })
        hv.addView(etStart, lpF)
        hv.addView(TextView(this).apply { text = " 回合 → "; setTextColor(0xFFF0E6D2.toInt()); textSize = 13f })
        hv.addView(etEnd, lpF)
        hv.addView(TextView(this).apply { text = " 回合"; setTextColor(0xFFF0E6D2.toInt()); textSize = 13f })
        row.addView(hv)

        val bv = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val cbBatch = CheckBox(this).apply {
            text = "批处理（分段复盘，一次扫完）"; setTextColor(0xFFF0E6D2.toInt()); textSize = 13f; isChecked = false
        }
        val etBatch = numField("20")
        bv.addView(cbBatch)
        bv.addView(TextView(this).apply { text = "每次"; setTextColor(0xFFF0E6D2.toInt()); textSize = 12f; setPadding(dp(8), 0, dp(4), 0) })
        bv.addView(etBatch, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        bv.addView(TextView(this).apply { text = "回合"; setTextColor(0xFFF0E6D2.toInt()); textSize = 12f; setPadding(dp(5), 0, 0, 0) })
        row.addView(bv)
        row.addView(TextView(this).apply {
            text = "批处理：每批 8~20 回合，自动分段（如 1—84 每批 10 → 1-10、11-20…末段余数），每段一页翻看"
            setTextColor(0xFFB08A5A.toInt()); textSize = 11f; setPadding(0, dp(4), 0, 0)
        })

        // ★ 复盘评估深度固定 18 层（用户钦定：不要选择按钮、不设 20/24 层，直接 18 层，稳而准）
        val reviewDepth = 18

        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(row)
            .setPositiveButton("下一步", null)   // 覆盖点击，先校验
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s = etStart.text.toString().trim().toIntOrNull()
                val e = etEnd.text.toString().trim().toIntOrNull()
                val batchOn = cbBatch.isChecked
                val bs = if (batchOn) etBatch.text.toString().trim().toIntOrNull() else 0
                var err: String? = null
                if (s == null || e == null) err = "请输入整数回合号"
                else if (s < 1 || e < 1) err = "回合号从 1 开始"
                else if (s > e) err = "起始回合不能大于结束回合"
                else if (e > totalRounds) err = "本局共 $totalRounds 回合，结束回合超出"
                else if (!batchOn && (e - s + 1) > maxAllowed) err = "单次最多 $maxAllowed 回合（如 1—20 可、1—21 不可）"
                else if (batchOn && bs == null) err = "请输入每批回合数"
                else if (batchOn && (bs!! < 8 || bs > maxAllowed)) err = "每批需在 8~$maxAllowed 回合之间"
                if (err != null) { toast(err); return@setOnClickListener }
                dlg.dismiss()
                showReviewConfirm(initialFen, moves, s!!, e!!, if (batchOn) bs!! else 0, reviewDepth)
            }
        }
        styleScrimDialog(dlg, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f)
        dlg.show()
    }

    /** 加载已保存的复盘文档（入口弹窗内「📜 复盘历史」） */
    private fun showReviewLoadDialog() {
        val files = ReviewArchive.list(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(40), dp(14), dp(40), dp(14))
        }
        row.addView(TextView(this).apply {
            text = "复盘历史"
            setTextColor(0xFFE8D5A3.toInt()); textSize = 15f; gravity = Gravity.CENTER
        })
        row.addView(TextView(this).apply {
            text = "点开既往复盘即完整展开：曲线、点节点小棋盘、[进]直达、错着热链试走全都可用；只读展开，不重新计算、不再写入新历史"
            setTextColor(0xFF9A8F7A.toInt()); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(8))
        })
        val scroll = ScrollView(this)
        val listBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var picker: androidx.appcompat.app.AlertDialog? = null
        if (files.isEmpty()) {
            listBody.addView(TextView(this).apply {
                text = "（暂无历史，先做一次复盘会自动记入）"
                setTextColor(0xFF9A8F7A.toInt()); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20))
            })
        }
        files.forEach { f ->
            var label = f.name
            try {
                val j = JSONObject(f.readText(Charsets.UTF_8))
                val savedAt = j.optString("savedAt", "")
                val t = j.optString("gameTitle", "").ifBlank { "象棋复盘" }
                val mv = j.optJSONArray("moves")
                val rounds = if (mv != null) (mv.length() + 1) / 2 else 0
                val dateTxt = savedAt.ifBlank { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified())) }
                label = "🕒 $dateTxt ｜ $t ｜ ${rounds}回合"
            } catch (_: Exception) {}
            // ★ 每份复盘文件一行：左「打开」占满，右「🗑 删除」物理真删（二次确认）。
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(Button(this).apply {
                text = label
                setTextColor(0xFFF0E6D2.toInt()); textSize = 12f
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x335C5040.toInt()); cornerRadius = dp(6).toFloat(); setStroke(1, 0xFFFFB300.toInt())
                }
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val load = ReviewArchive.load(this@MainActivity, f)
                    if (load == null || load.segments.isEmpty()) { toast("复盘文件无法读取"); return@setOnClickListener }
                    picker?.dismiss()
                    try {
                        chessGame.loadLineFromFen(load.initialFen, load.movesUci)
                        chessBoard.viewingHistory = false
                    } catch (_: Exception) {}
                    updateStepInfo(); updateSideButtons(); refreshExplanationDisplay()
                    showReviewResultPaged(load, 0)
                }
            })
            line.addView(Button(this).apply {
                text = "🗑 删除"; setTextColor(0xFFE0B0A0.toInt()); textSize = 12f
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x335C5040.toInt()); cornerRadius = dp(6).toFloat(); setStroke(1, 0xFFB0412E.toInt())
                }
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除复盘文件")
                        .setMessage("将删除该复盘文件（物理真删，删除后无法恢复）。确认删除？")
                        .setPositiveButton("确认删除") { _, _ ->
                            f.delete()
                            picker?.dismiss()
                            showReviewLoadDialog()
                        }
                        .setNegativeButton("取消", null)
                        .create()
                        .also { styleScrimDialog(it, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f) }
                        .show()
                }
            })
            listBody.addView(line)
        }
        scroll.addView(listBody)
        row.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setView(row).setPositiveButton("取消", null).create()
        picker = dlg
        styleScrimDialog(dlg, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f)
        dlg.show()
    }

    private fun showReviewConfirm(initialFen: String, moves: List<String>, startRound: Int, endRound: Int, batchSize: Int, reviewDepth: Int = 18) {
        val cnt = endRound - startRound + 1
        if (batchSize <= 0) {
            val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("确认复盘")
                .setMessage("复盘范围：第 $startRound — $endRound 回合（共 $cnt 个回合，红黑各一步记一回合）。\n\n将后台逐回合计算双方得分，换算为红方正分/黑方负分、0 均势的评分曲线（刻度随数据自适应，曲线与刻度一一对应），并标出错着与正解。\n\n每步思考 18 层（固定）。只盘主变，不盘变招。")
                .setPositiveButton("确定复盘") { _, _ -> launchReview(initialFen, moves, startRound, endRound, 0, reviewDepth) }
                .setNegativeButton("返回修改", null)
                .create()
            styleScrimDialog(dlg, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f)
            dlg.show()
            return
        }
        val segs = ArrayList<Pair<Int, Int>>()
        var cur = startRound
        while (cur <= endRound) {
            val m = minOf(cur + batchSize - 1, endRound)
            segs.add(cur to m); cur = m + 1
        }
        val segText = segs.joinToString("、") { "${it.first}-${it.second}" }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("确认批处理复盘")
            .setMessage("将第 $startRound — $endRound 回合按每批 $batchSize 回合拆为 ${segs.size} 段：\n\n$segText\n\n（末段可能不足 $batchSize，为余数段，同样照常评估。）\n\n逐段后台计算评分曲线（刻度随数据自适应，曲线与刻度一一对应）并标出错着与正解，结果分页翻看。\n\n每步思考 18 层（固定）。只盘主变，不盘变招。")
            .setPositiveButton("开始批处理") { _, _ -> launchReview(initialFen, moves, startRound, endRound, batchSize, reviewDepth) }
            .setNegativeButton("返回修改", null)
            .create()
        styleScrimDialog(dlg, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f)
        dlg.show()
    }

    private fun launchReview(initialFen: String, moves: List<String>, startRound: Int, endRound: Int, batchSize: Int, reviewDepth: Int = 18) {
        val segs = ArrayList<Pair<Int, Int>>()
        if (batchSize <= 0) {
            segs.add(startRound to endRound)
        } else {
            var cur = startRound
            while (cur <= endRound) {
                val m = minOf(cur + batchSize - 1, endRound)
                segs.add(cur to m); cur = m + 1
            }
        }
        val progress = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (batchSize > 0) "批处理复盘中" else "复盘计算中")
            .setMessage("正在逐回合评估…（视手机性能需数秒到一两分钟）")
            .setCancelable(false)
            .create()
        styleScrimDialog(progress, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f)
        progress.show()

        val collected = ArrayList<ReviewSegment>()
        val analyzer = ReviewAnalyzer(this)

        // ★ 复用已就绪的主引擎做复盘（绝不另起一台、杜绝"引擎未能就绪"）；
        //   复盘期间暂关持续分析以免与复用引擎抢线，结束后安静恢复。
        val reuseEngine = chessGame.engine.engineFor(com.pikafish.chess.model.PieceColor.RED)
        val restoreAnalysis = analysisActive
        if (restoreAnalysis) stopAnalysis()
        try { chessGame.engine.stopPonder() } catch (_: Exception) {}

        /** 复盘占用引擎结束后，安静恢复持续分析（不弹提示、不改双方模式） */
        fun resumeAnalysisQuiet() {
            if (analysisActive) return
            analysisActive = true
            analysisGen++
            chessGame.emitAnalysis = true   // ★ 复盘结束安静恢复分析：同步"神同步"直喂开关
            if (chessGame.alwaysThinking) chessGame.alwaysThinking = false
            lastAnalyzedFen = ""
            analysisPoller.removeCallbacks(analysisPollRunnable)
            analysisPoller.post(analysisPollRunnable)
        }

        fun runSeg(segIdx: Int) {
            if (segIdx >= segs.size) {
                if (progress.isShowing) progress.dismiss()
                if (restoreAnalysis) resumeAnalysisQuiet()
                if (collected.isEmpty()) { toast("复盘失败：未能取得任何评估结果"); return }
                val savedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                // ★ 局名：取当前开局名，让历史列表一眼看懂"复盘了什么东西"
                val openingName = try { chessGame.getCurrentOpeningName() } catch (_: Exception) { null }
                val title = openingName?.takeIf { it.isNotBlank() } ?: "象棋复盘"
                val bundle = ReviewBundle(initialFen, moves, title, savedAt, collected)
                // ★ 只在"深层复盘(跑数据)"成功时写一条历史；从历史打开是只读展开，绝不重复写档
                val sf = ReviewArchive.save(this, bundle)
                if (sf != null) toast("已完成，已存入复盘历史：${sf.name}")
                showReviewResultPaged(bundle, 0)
                return
            }
            val (s, e) = segs[segIdx]
            val header = if (batchSize > 0) "批处理 ${segIdx + 1}/${segs.size} · 第 $s—$e 回合" else "第 $s—$e 回合"
            progress.setMessage("$header\n正在评估…")
            analyzer.run(
                initialFen, moves, s, e,
                onProgress = { _, _, label -> progress.setMessage("$header\n$label") },
                onDone = { result ->
                    if (result == null) {
                        if (progress.isShowing) progress.dismiss()
                        if (restoreAnalysis) resumeAnalysisQuiet()
                        val why = analyzer.lastError.takeIf { it.isNotBlank() } ?: "引擎未能就绪"
                        toast("复盘失败：第 $s—$e 回合段评估失败（$why）")
                        return@run
                    }
                    collected.add(ReviewSegment(s, e, result.points, result.blunders, result.slips, result.curve, result.blunderCurveIdx, result.slipCurveIdx))
                    runSeg(segIdx + 1)
                },
                reuseEngine = reuseEngine
            )
        }
        runSeg(0)
    }

    private fun showReviewResultPaged(bundle: ReviewBundle, pageIndex: Int) {
        val segments = bundle.segments
        if (segments.isEmpty()) { toast("复盘结果为空"); return }
        val idx = pageIndex.coerceIn(0, segments.size - 1)
        val seg = segments[idx]
        val startRound = seg.startRound
        val endRound = seg.endRound
        val isBatch = segments.size > 1
        // ★ 以棋盘为权威：本段实际覆盖的首/末节点回合号，取自节点自身（与主界面 updateStepInfo 同源），
        //   避免"批处理从第R回合起"时首节点实为第R-1回合(上一回合结束位)、以及曲线/标题对不上棋盘。
        val firstNodeRound = seg.points.firstOrNull()?.round ?: startRound
        val lastNodeRound = seg.points.lastOrNull()?.round ?: endRound

        // ★ 复盘字号：读取上次的记忆值，本页视图树建好后统一套用（棋盘/曲线图除外）
        reviewFontScale = try { prefs.getFloat("review_font_scale", 1.0f) } catch (_: Exception) { 1.0f }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        // ★★ 根治"矮屏按钮被挤出屏幕外、按不到"：弹窗拆成「可滚动内容区」+「固定底栏」两段。
        //   标题/小棋盘/曲线/文字全部装进带权重的滚动区（内容再长也只是内部滚动，不会把底栏顶出去），
        //   底栏独立固定在最后、永远留在屏内；窗口高度再被屏幕可视区钳死（clampDialogToVisibleScreen），
        //   → 任何机型、矮屏/异形屏，五个底栏按钮都按得到，绝不窜出屏幕。
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply {
            text = if (isBatch) "复盘评估 · 批处理 第 ${idx + 1}/${segments.size} 段（第 $firstNodeRound—$lastNodeRound 回合局面）"
                   else "复盘评估 · 第 $firstNodeRound — $lastNodeRound 回合局面"
            setTextColor(0xFFF0E6D2.toInt()); textSize = 16f; gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = "红正分 / 黑负分 / 0 均势；上方红优、下方黑优；杀棋贴边箭头；红环=漏着，琥珀三角=走下坡"
            setTextColor(0xFF9A8F7A.toInt()); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(6))
        })
        val curve = CurveView(this).apply {
            values = seg.curve
            blunderIdx = seg.blunderCurveIdx
            slipIdx = seg.slipCurveIdx
            labelStartRound = firstNodeRound
            labelEndRound = lastNodeRound
            nodeColorArr = IntArray(seg.points.size) { i ->
                when (seg.points[i].color) { PieceColor.RED -> 1; PieceColor.BLACK -> 2; else -> 0 }
            }
            highlightIdx = -1
        }
        // ★ 纸张底色：整页一个不透明的可自选底色（读取上次记忆；默认暖暗色，保证浅色文字清楚）。
        //   不再用固定色块选择，改由底栏【底色调节】弹出整条色谱取色板，让用户自选任意颜色。
        var curPaper = try { prefs.getInt("review_paper", 0xFF211D1F.toInt()) } catch (_: Exception) { 0xFF211D1F.toInt() }
        fun applyReviewPaper() {
            val gd = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(curPaper)   // ★ 覆盖整页：棋盘、曲线、文字区……整个弹窗都上这一层纸色
            }
            body.background = gd
        }
        applyReviewPaper()

        // ★ 常开小棋盘面板：回合侧条(回合/谁已走/进) + 小棋盘，固定在纸张上方（棋盘在上、曲线在下）
        val dockedBoard = MiniGameBoardView(this)
        val tvRound = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(0xFFF0E6D2.toInt()); textSize = 22f; isSingleLine = true
        }
        val tvMover = TextView(this).apply { gravity = Gravity.CENTER; textSize = 10f; isSingleLine = true }
        val btnEnter = verticalChip("进") { }
        val dockCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(1), dp(2), dp(1), dp(2))
        }
        dockCol.addView(tvRound, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        dockCol.addView(TextView(this).apply {
            text = "回合"; gravity = Gravity.CENTER; setTextColor(0xFF9A8F7A.toInt()); textSize = 10f
        })
        dockCol.addView(tvMover)
        dockCol.addView(TextView(this).apply { /* 弹性占位，进贴底 */ }, LinearLayout.LayoutParams(0, 0, 1f))
        dockCol.addView(btnEnter)
        val dockPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isClickable = true
            setPadding(dp(2), dp(2), dp(2), dp(2))
            // ★ 打标记：棋盘坞定宽定高（142dp），字号缩放整块跳过 —— 棋盘及周边一个像素都不动
            tag = REVIEW_NOSCALE_TAG
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF2292B2F.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1).toInt(), 0x66FFB300.toInt())
            }
            elevation = dp(8).toFloat()
        }
        // ★ 锁定区定高件（棋盘坞 / 曲线）在矮屏上按比例收身：
        //   锁定区不滚动，必须保证"锁定区 + 棋谱区 + 底栏"整体塞得进可视区，绝不裁切、绝不外溢。 ──
        val visHdp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val ultraShort = visHdp < 620f
        val sfBoard = when { ultraShort -> 0.68f; visHdp < 760f -> 0.80f; else -> 1f }
        val sfCurve = when { ultraShort -> 0.55f; visHdp < 760f -> 0.72f; else -> 1f }
        val boardH = dp((142 * sfBoard).toInt())
        dockPanel.addView(dockCol, LinearLayout.LayoutParams(dp(44), boardH))
        dockPanel.addView(dockedBoard, LinearLayout.LayoutParams(0, boardH, 1f))
        // ★ 棋盘右侧空地：实时局面评估（红优/黑优/略优/均势 + 分值），以及 [进] 一跳直达主界面
        val tvEvalCap = TextView(this).apply {
            text = "当前局面"; gravity = Gravity.CENTER; setTextColor(0xFF9A8F7A.toInt()); textSize = 9f
        }
        val tvEvalSide = TextView(this).apply { gravity = Gravity.CENTER; textSize = 15f; isSingleLine = true }
        val tvEvalScore = TextView(this).apply { gravity = Gravity.CENTER; textSize = 11f; isSingleLine = true }
        val btnEnterR = verticalChip("进") { }
        val dockRightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(1), dp(2), dp(1), dp(2))
        }
        dockRightCol.addView(tvEvalCap)
        dockRightCol.addView(tvEvalSide)
        dockRightCol.addView(tvEvalScore)
        dockRightCol.addView(TextView(this).apply { /* 弹性占位，进贴底 */ }, LinearLayout.LayoutParams(0, 0, 1f))
        dockRightCol.addView(btnEnterR)
        dockPanel.addView(dockRightCol, LinearLayout.LayoutParams(dp(56), boardH))
        content.addView(dockPanel)   // ★ 棋盘在上（先 addView）

        // ★ 曲线宿主：放在棋盘下方（后 addView）
        val curveHost = FrameLayout(this)
        curveHost.addView(curve, FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp((168 * sfCurve).toInt())))
        content.addView(curveHost)   // ★ 曲线在下

        // ★ 小棋盘管理：游标/点选 → 常开棋盘跟到那一步；[进] 一键直达主界面对应局面
        var resultDlg: androidx.appcompat.app.AlertDialog? = null   // 供错着热链/分页/进 关闭本弹窗
        fun fenForPly(ply: Int): String {
            val b = Board().apply { loadFromFen(bundle.initialFen) }
            val ucs = bundle.movesUci
            for (i in 0 until minOf(ply, ucs.size)) {
                val m = Move.fromUci(ucs[i], b) ?: break
                b.makeMoveUnchecked(m)
            }
            return b.toFen()
        }
        fun enterNode(ply: Int, round: Int) {
            minimizeReview(resultDlg, bundle, idx, round)   // ★ 收起而非关闭：保留结果与弹窗状态；浮条显示本节点回合
            try {
                chessGame.loadLineFromFen(bundle.initialFen, bundle.movesUci)   // 确保整条主变在位（含自定义摆子起点）
                chessGame.goToStep(ply)                                          // 光标与棋盘都切到该节点 FEN
                chessBoard.viewingHistory = chessGame.isViewingHistory()
                restoreHighlightsFromHistory()
                chessGame.onBoardChanged?.invoke()
                updateSideButtons(); updateStepInfo(); refreshExplanationDisplay()
                toast("已定位到第 $round 回合局面")
            } catch (ex: Exception) {
                Log.e(TAG, "进入节点失败：${ex.message}", ex)
                toast("跳转失败：${ex.message}")
            }
        }
        // ★ 更新常开小棋盘内容到指定节点（游标滑动/点选共用）
        fun updateDockedBoard(localIdx: Int) {
            val pt = seg.points.getOrNull(localIdx) ?: return
            curve.highlightIdx = localIdx
            curve.cursorIdx = localIdx
            curve.invalidate()
            val fen = fenForPly(pt.ply)
            // ★ 走子标识：重建"走前局面"，取进入该节点的那一手 UCI 的 from/to，供小棋盘高亮"从哪到哪"
            var fromPair: Pair<Int, Int>? = null
            var toPair: Pair<Int, Int>? = null
            if (pt.ply > 0) {
                try {
                    val pre = Board().apply { loadFromFen(bundle.initialFen) }
                    for (i in 0 until pt.ply - 1) {
                        val m = Move.fromUci(bundle.movesUci[i], pre) ?: break
                        pre.makeMoveUnchecked(m)
                    }
                    val lm = Move.fromUci(bundle.movesUci[pt.ply - 1], pre)
                    if (lm != null) { fromPair = lm.from.row to lm.from.col; toPair = lm.to.row to lm.to.col }
                } catch (_: Exception) {}
            }
            dockedBoard.setBoard(Board().apply { loadFromFen(fen) }, fromPair, toPair)
            tvRound.text = "${pt.round}"
            // ★ 谁已走（客观真实）：pt.ply==0 → 没走棋，写「设局X先」；否则刚走的那步属于对手方 → 对手方"已走"。
            if (pt.ply == 0) {
                tvMover.text = if (pt.color == PieceColor.RED) "设局红先" else "设局黑先"
                tvMover.setTextColor(0xFFFFB300.toInt())
            } else {
                val mover = if (pt.color == PieceColor.RED) PieceColor.BLACK else PieceColor.RED
                if (mover == PieceColor.RED) { tvMover.text = "红已走"; tvMover.setTextColor(0xFFFF6E6E.toInt()) }
                else { tvMover.text = "黑已走"; tvMover.setTextColor(0xFF64B5F6.toInt()) }
            }
            btnEnter.setOnClickListener { enterNode(pt.ply, pt.round) }
            btnEnterR.setOnClickListener { enterNode(pt.ply, pt.round) }
            // ★ 实时局面评估：游标停在哪一步，就显示那一步红/黑谁优、优多少，或略优/均势。
            //   viewScore 是引擎 1:1 原始分（红方视角）：正=红优、负=黑优、0=均势；不再用几何压缩。
            val v = pt.viewScore
            val mag = Math.abs(v)
            val evSide = if (v >= 0) "红" else "黑"
            // ★ 连杀分已统一为 29999 封顶编码分；这里直接显示真实编码分，并附 N步杀。
            val mate = com.pikafish.chess.engine.JniEngine.isMateScore(v)
            tvEvalSide.text = when {
                mate -> "${evSide}杀棋"
                mag < 15 -> "均势"
                mag < 400 -> "${evSide}略优"
                mag < 1500 -> "${evSide}优"
                else -> "${evSide}大优"
            }
            tvEvalScore.text = when {
                mate -> {
                    val plies = com.pikafish.chess.engine.JniEngine.matePliesOf(v) ?: 1
                    "$mag 分（${plies}步杀）"
                }
                else -> "$mag 分"
            }
            val evalColor = when {
                mate -> if (v > 0) 0xFFFF6E6E.toInt() else 0xFF64B5F6.toInt()
                mag < 80 -> 0xFF9CCC65.toInt()
                mag < 400 -> 0xFFFFB300.toInt()
                v > 0 -> 0xFFFF6E6E.toInt()
                else -> 0xFF64B5F6.toInt()
            }
            tvEvalSide.setTextColor(evalColor)
            tvEvalScore.setTextColor(evalColor)
        }
        curve.onCursorMove = { updateDockedBoard(it) }                    // 拖动游标：常开小棋盘实时跟随
        curve.onNodeTap = { i, _, _ -> updateDockedBoard(i) }             // 点节点：小棋盘跳到该步（保留直点跳转）
        curve.onBlankTap = { }                                            // 小棋盘常开，空白点选落到最近节点（已在 Curve 内吸附）
        // 初始定位到末节点，展示最终结果
        curve.cursorIdx = seg.points.size - 1
        updateDockedBoard(seg.points.size - 1)

        // ★ 摘要文案：紧接曲线之下，属于「锁定区」，不参与滚动
        content.addView(TextView(this).apply {
            text = "本段共评估 ${seg.points.size} 处局面，漏着 ${seg.blunders.size} 处、连续下滑趋势 ${seg.slips.size} 处："
            setTextColor(0xFFE8D5A3.toInt()); textSize = 12f; setPadding(0, dp(8), 0, dp(1))
        })
        // ★ 装饰分隔线：短实横线 + 居中圆点相间排开。
        //   它是一道界线 —— 线以上(小棋盘/曲线/摘要)整体锁定不滚动，线以下只有棋谱区自己滚。
        content.addView(
            com.pikafish.chess.ui.DashDotDividerView(this),
            LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
        )

        // 批处理：分页导航（属控件，放在锁定区，翻页随手可按）
        if (isBatch) {
            val navRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            navRow.addView(navBtn("◀ 上一页", idx > 0) { resultDlg?.dismiss(); showReviewResultPaged(bundle, idx - 1) })
            navRow.addView(TextView(this).apply {
                text = "　第 ${idx + 1}/${segments.size} 页　"
                setTextColor(0xFFE8D5A3.toInt()); textSize = 12f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            navRow.addView(navBtn("下一页 ▶", idx < segments.size - 1) { resultDlg?.dismiss(); showReviewResultPaged(bundle, idx + 1) })
            content.addView(navRow)
        }

        // ★ 棋谱区（本段唯一可滚动的地方）：漏着/正解/主变/「进入」热链/说明/下行趋势
        val listBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (seg.blunders.isEmpty()) {
            listBody.addView(TextView(this).apply {
                text = "✓ 该段双方行棋稳健，未发现明显漏着。"
                setTextColor(0xFF9CCC65.toInt()); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
            })
        }
        seg.blunders.forEach { b ->
            val color = if (b.color == PieceColor.RED) 0xFFE57373.toInt() else 0xFF90A4AE.toInt()
            // ★ 正解保护：实走已是最佳着则不多此一举给"建议"，只标两个字"正解"
            if (b.movePlayedChinese == b.bestMoveChinese) {
                listBody.addView(TextView(this).apply {
                    text = "✓ 第 ${b.round} 回合 · ${if (b.color == PieceColor.RED) "红方" else "黑方"}«${b.movePlayedChinese}» 正解"
                    setTextColor(0xFFA5D6A7.toInt()); textSize = 13f; setPadding(0, dp(6), 0, 0)
                })
                return@forEach
            }
            // ★ 热链：点击即跳转到该错着回合，原地改走建议着、与后续主变对比
            listBody.addView(TextView(this).apply {
                text = "【第 ${b.round} 回合 · ${if (b.color == PieceColor.RED) "红方" else "黑方"}】　▶ 跳到该着试走"
                setTextColor(color); textSize = 13f; setPadding(0, dp(6), 0, 0)
                setOnClickListener {
                    minimizeReview(resultDlg, bundle, idx, b.round)   // ★ 收起而非关闭：保留结果与弹窗状态；浮条显示错着回合
                    jumpToReviewBlunder(b)
                }
            })
            listBody.addView(TextView(this).apply {
                text = "  实际«${b.movePlayedChinese}»（评分下滑 ${b.lossViewScore} 分）"
                setTextColor(0xFFD0C8B8.toInt()); textSize = 13f
            })
            listBody.addView(TextView(this).apply {
                text = "  ★ 正解：${b.bestMoveChinese}"
                setTextColor(0xFFA5D6A7.toInt()); textSize = 13f
            })
            listBody.addView(TextView(this).apply {
                text = "  主变：${b.bestPv.ifBlank { "—" }}"
                setTextColor(0xFF9A8F7A.toInt()); textSize = 11f
            })
            // ★ 主变末尾的「进入」热链：就地弹小棋盘，把这段主变一步步走给人看
            //   （上一步 / 下一步 / 播放·暂停 / 速度 / 关闭）。与上面的「跳到该着试走」不同 ——
            //   那个是跳回主界面演示；这个是原地开小窗把主变看明白，关掉即回到本复盘页。
            if (b.bestPv.isNotBlank()) {
                listBody.addView(TextView(this).apply {
                    text = "  进入 ▶　（小棋盘演示此主变）"
                    setTextColor(0xFF8CD7FF.toInt()); textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener { showReviewVariationPlayer(b, fenForPly(b.ply)) }
                })
            }
            listBody.addView(TextView(this).apply {
                text = "  说明：${b.note}"
                setTextColor(0xFFB0A88A.toInt()); textSize = 12f
            })
        }
        // 持续走向下坡趋势提示（0 基线上某方连续向对方半轴滑落）
        if (seg.slips.isNotEmpty()) {
            listBody.addView(TextView(this).apply {
                text = "— 持续下行趋势（开始走下坡） —"
                setTextColor(0xFFFFB300.toInt()); textSize = 12f; setPadding(0, dp(10), 0, dp(2))
            })
            seg.slips.forEach { sp ->
                listBody.addView(TextView(this).apply {
                    text = "▽ 开始走下坡 · ${sp.note}"
                    setTextColor(0xFFFFB300.toInt()); textSize = 12f; setPadding(0, dp(2), 0, 0)
                })
            }
        }
        // ★ 棋谱区自成一块（weight=1 吃掉锁定区与底栏之间的全部高度）：
        //   棋谱再长也只在这里内部滚动，绝不牵动上面的小棋盘、曲线与摘要。
        val listScroll = ScrollView(this).apply { isFillViewport = false; addView(listBody) }

        // ★ 底栏菜单：彻底退出 / 暂时收缩 / 底色调节 / 复制要点
        val copyAction: () -> Unit = {
            val sb = StringBuilder(
                if (isBatch) "复盘评估·批处理第${idx + 1}/${segments.size}段（第 $startRound—$endRound 回合）\n"
                else "复盘评估（第 $startRound—$endRound 回合）\n"
            )
            if (seg.blunders.isEmpty()) sb.append("该段行棋稳健，无漏着。\n")
            seg.blunders.forEach { b ->
                sb.append("第${b.round}回合·${if (b.color == PieceColor.RED) "红方" else "黑方"}«${b.movePlayedChinese}»漏着")
                    .append(" → 正解${b.bestMoveChinese}\n")
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("复盘要点", sb.toString()))
            toast("本段复盘要点已复制")
        }
        fun barChip(text: String, color: Int = 0xFFF0E6D2.toInt(), onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = text; b.isAllCaps = false; b.textSize = 11f; b.minimumWidth = 0; b.maxLines = 1
            b.setPadding(dp(6), dp(4), dp(6), dp(4))
            b.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x335C5040.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1).toInt(), 0xFFFFB300.toInt())
            }
            b.setTextColor(color)
            b.setOnClickListener { onClick() }
            return b
        }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(2))
            // ★ 打标记：字号缩放时整体跳过底栏（5 个等分按钮被放大就会撑破排版）
            tag = REVIEW_NOSCALE_TAG
        }
        val barKids = mutableListOf<Button>()
        barKids.add(barChip("复制要点") { copyAction() })
        barKids.add(barChip("暂时收缩") {
            // ★ 暂时收缩：只收起为屏幕边缘小条，随时点开还原；结果数据留在内存不重算
            minimizeReview(resultDlg, bundle, idx, null)
        })
        barKids.add(barChip("字号", 0xFF8CD7FF.toInt()) {
            // ★ 字号调节：滑杆实时放大/缩小整页文字（棋盘与曲线图不参与）
            showReviewFontSlider(body)
        })
        barKids.add(barChip("底色调节", 0xFFFFD24A.toInt()) {
            // ★ 底色调节：弹出色谱取色板，用户自选任意整页底色
            showColorPicker(curPaper) { c ->
                curPaper = c
                applyReviewPaper()
                try { prefs.edit().putInt("review_paper", c).apply() } catch (_: Exception) {}
            }
        })
        // ★ 彻底退出放最后（逻辑递进：复制/收缩/调色都是中间操作，退出是最终的收尾动作）
        barKids.add(barChip("彻底退出", 0xFFFF8A80.toInt()) {
            // ★ 彻底退出：真正关闭复盘，清缓存、不保留浮条（用户点它会较慎重）
            reviewMinimized = false
            resultDlg?.dismiss()
        })
        barKids.forEach { b ->
            val lp = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(4); lp.marginEnd = dp(4)
            bottomBar.addView(b, lp)
        }
        // ★★ 三段式，互不牵连：
        //   ① content 锁定区（小棋盘 / 曲线 / 摘要 / 分隔线 / 分页）—— 不滚动，注意力锚点恒定；
        //   ② listScroll 棋谱区 —— 本段唯一滚动块，weight=1 吃掉余高，只滚棋谱；
        //   ③ bottomBar 底栏 —— 恒定贴底。
        body.addView(content, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(listScroll, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(bottomBar)   // ★ 固定底栏（不参与滚动）

        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(body)
            .create()
        resultDlg = dlg
        // 新开一次复盘展示：收起上一次的最小化状态与小条，回到正常状态
        reviewMinimized = false
        hideReviewMinBar()
        // ★ 记录活实例与结果缓存：最小化后用它原样还原（滚动位置/选中条目不变），不重算引擎
        reviewLivingDlg = dlg
        reviewLiveBundle = bundle
        reviewLivePage = idx
        dlg.setOnDismissListener {
            // 非最小化地被关闭（"完成"/取消/点外部/按返回）→ 才算真正关闭，清掉结果缓存与浮条
            if (!reviewMinimized) {
                hideReviewMinBar()
                reviewLivingDlg = null
                reviewLiveBundle = null
            }
        }
        styleScrimDialog(dlg, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f)
        // ★ 页面建好后统一套用记忆中的复盘字号（棋盘、曲线图、底栏都在排除之列）
        applyReviewFontScale(body, reviewFontScale)
        dlg.show()
        // ★ 窗口高度钳到屏幕可视区（已扣掉状态栏/导航栏）→ 内容再多也只在滚动区内部消化，
        //   底栏永远落在屏内。这是"矮屏也绝不出现按不到的按钮"的最后一道保险。
        clampDialogToVisibleScreen(dlg, content, bottomBar, widthFactor = 0.94f, heightFactor = 0.96f,
            extraBodyMin = dp(150))
    }

    /**
     * ★ 把弹窗窗口尺寸钳进"屏幕可视区"，任何机型/矮屏/异形屏都不会有内容窜出屏幕：
     *   1) 先按可视区的比例把窗口高度钳死（默认宽 94%、高 96%，并各设上限防平板无限拉伸）——
     *      这样弹窗内「滚动内容区(weight=1) + 固定底栏」的权重才生效，底栏恒定落在屏内可按；
     *   2) 再量一次内容真实高度做"紧凑收口"：内容短 → 弹窗贴着内容不留空旷；
     *      内容长 → 顶到可视区上限，多余部分在滚动区内部消化（外部结构保证不溢出）。
     * 可视区取自本 Activity 的 windowVisibleDisplayFrame，已扣掉状态栏与导航栏，
     * 故底部按钮不会被系统导航条压住。
     */
    private fun clampDialogToVisibleScreen(
        d: android.app.Dialog?,
        content: View? = null,
        footer: View? = null,
        widthFactor: Float = 0.94f,
        heightFactor: Float = 0.96f,
        extraBodyMin: Int = 0
    ) {
        if (d == null) return
        try {
            val dm = resources.displayMetrics
            val r = android.graphics.Rect()
            window?.decorView?.getWindowVisibleDisplayFrame(r)
            val visW = if (r.width() > 0) r.width() else dm.widthPixels
            val visH = if (r.height() > 0) r.height() else dm.heightPixels
            val w = (visW * widthFactor).toInt().coerceAtMost((560 * dm.density).toInt())
            // 高度上限：既不超过可视区的 96%，也不超过 820dp（平板/横屏不至于空旷）
            val capH = (visH * heightFactor).toInt().coerceAtMost((820 * dm.density).toInt())
            d.window?.setLayout(w, capH)
            d.window?.setGravity(Gravity.CENTER)
            // ── 紧凑收口：等首帧把 content/footer 量出真实高度后再定最终高度 ──
            //   extraBodyMin：给内部"只有一个滚动区(weight=1)"的弹窗预留的可滚区最小高度，
            //   否则 content 高度里不含滚动区，紧凑收口会把滚动区压成 0。
            val c = content ?: return
            c.post {
                try {
                    val bodyPad = dp(24)   // 与 body 上下内边距(14+10dp)对齐，避免裁掉边距
                    val desired = c.height + (footer?.height ?: 0) + bodyPad + extraBodyMin
                    if (desired > 0) d.window?.setLayout(w, desired.coerceAtMost(capH))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "clampDialogToVisibleScreen: ${e.message}")
        }
    }

    /** ★ 复盘收起：跳去试走时把弹窗"最小化"而非关闭。结果数据留在内存（不重算引擎），
     *    弹窗只隐藏不销毁（同一实例），屏幕边缘浮出小条，点它原样展开。
     *    @param roundNo 跳转到的回合（小条标题显示用）；为空则取本段起始回合 */
    private fun minimizeReview(dlg: androidx.appcompat.app.AlertDialog?, bundle: com.pikafish.chess.ui.ReviewBundle?, page: Int, roundNo: Int? = null) {
        if (bundle == null) { dlg?.dismiss(); return }   // 没有结果可保留，直接关闭
        reviewMinimized = true
        reviewLiveBundle = bundle
        reviewLivePage = page
        reviewLivingDlg = dlg
        reviewLiveRound = roundNo ?: 0
        dlg?.dismiss()                      // 仅隐藏：ScrollView 滚动位置、旁白/选中条目仍留在同一视图实例里
        showReviewMinBar()
    }

    /** 在棋盘边缘浮出一个小条（如"复盘 · 第13回合"），可拖动、位置记忆常住；点击即原样展开复盘 */
    private fun showReviewMinBar() {
        val b = reviewLiveBundle ?: return
        val segs = b.segments
        val cur = segs.getOrNull(reviewLivePage.coerceIn(0, segs.size - 1)) ?: segs.lastOrNull() ?: return
        val round = if (reviewLiveRound > 0) reviewLiveRound else cur.startRound
        val label = if (segs.size > 1)
            "复盘 · 第${round}回合（${reviewLivePage + 1}/${segs.size}页）"
        else
            "复盘 · 第${round}回合"
        var bar = reviewMinBar
        if (bar == null) {
            bar = Button(this)
            bar.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1E1610.toInt()); cornerRadius = dp(12).toFloat()   // 两端圆弧包住内容
                setStroke(dp(1).toInt(), 0xFFFFB300.toInt())
            }
            bar.setTextColor(0xFFF0E6D2.toInt()); bar.textSize = 12f; bar.isAllCaps = false
            bar.minHeight = 0          // ★ 关掉 Button 自带的最小高度，让条只贴着字高，竖向最省面积
            bar.minimumHeight = 0
            bar.minimumWidth = 0
            bar.setPadding(dp(10), dp(2), dp(10), dp(2))   // ★ 上下仅 2dp，几乎只比一行字略高
            bar.elevation = dp(8).toFloat()
            reviewMinBar = bar
            // ★ 挂到整屏装饰根（而非棋盘区）：可在主界面任意角落（含四角、上下工具区之上）拖动
            val host = window.decorView as? android.widget.FrameLayout ?: frameBoardOuter
            reviewBarHost = host
            host.addView(bar, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val loc = IntArray(2)
            host.getLocationOnScreen(loc)
            // ★ 可拖动 + 点击恢复；拖动结束保存位置，重启后按上次停的位置常住
            var dX = 0f; var dY = 0f; var downX = 0f; var downY = 0f
            bar.setOnTouchListener { v, ev ->
                val lp = v.layoutParams as android.widget.FrameLayout.LayoutParams
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dX = ev.rawX - (loc[0] + lp.leftMargin)
                        dY = ev.rawY - (loc[1] + lp.topMargin)
                        downX = ev.rawX; downY = ev.rawY
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        lp.leftMargin = (ev.rawX - loc[0] - dX).toInt()
                            .coerceIn(0, (host.width - v.width).coerceAtLeast(0))
                        lp.topMargin = (ev.rawY - loc[1] - dY).toInt()
                            .coerceIn(0, (host.height - v.height).coerceAtLeast(0))
                        v.layoutParams = lp
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val moved = Math.hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble())
                        if (moved < dp(8)) restoreReview() else saveReviewBarPos()
                        true
                    }
                    else -> true
                }
            }
            // 位置记忆：首次按上次停的位置摆放；没有记忆则顶部居中
            bar.post {
                val lp = bar.layoutParams as android.widget.FrameLayout.LayoutParams
                val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (p.contains(KEY_REVIEW_BAR_X) && p.contains(KEY_REVIEW_BAR_Y)) {
                    lp.leftMargin = (((host.width - bar.width).coerceAtLeast(0)) * p.getFloat(KEY_REVIEW_BAR_X, 0f)).toInt()
                    lp.topMargin = (((host.height - bar.height).coerceAtLeast(0)) * p.getFloat(KEY_REVIEW_BAR_Y, 0f)).toInt()
                } else {
                    lp.leftMargin = ((host.width - bar.width) / 2).coerceAtLeast(0)
                    lp.topMargin = dp(6)
                }
                bar.layoutParams = lp
            }
        }
        bar.text = label
        bar.visibility = android.view.View.VISIBLE
        bar.bringToFront()
    }

    /** 把浮条当前停靠位置（相对比例 0..1，相对整屏宿主）写入配置，重启后恢复 */
    private fun saveReviewBarPos() {
        val bar = reviewMinBar ?: return
        val host = reviewBarHost ?: return
        val lp = bar.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val cw = host.width; val bw = bar.width
        val top = host.height; val bh = bar.height
        val fx = if (cw > bw) lp.leftMargin.toFloat() / (cw - bw) else 0f
        val fy = if (top > bh) lp.topMargin.toFloat() / (top - bh) else 0f
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_REVIEW_BAR_X, fx).putFloat(KEY_REVIEW_BAR_Y, fy).apply()
    }

    /** 隐藏最小化小条（保留引用，可重复使用） */
    private fun hideReviewMinBar() {
        reviewMinBar?.visibility = android.view.View.GONE
    }

    /** ★★ 复盘字体缩放：遍历整棵视图树，把所有 TextView 的字号统一乘倍率。
     *  以下三类整棵子树直接跳过 —— 用户钦定：
     *    · 棋盘（MiniGameBoardView / ChessBoardView）：排版已定，一个字都不许变；
     *    · 曲线图（CurveView）：怕放大后崩排版，不参与缩放；
     *    · 打了 REVIEW_NOSCALE_TAG 的子树（棋盘坞 + 底栏按钮条）：定宽定高/等分按钮，放大就会被撑破。
     *  原始字号记在 WeakHashMap 里，反复应用也不会累积跑偏。 */
    private fun applyReviewFontScale(root: android.view.View, scale: Float) {
        when (root) {
            is com.pikafish.chess.ui.MiniGameBoardView,
            is com.pikafish.chess.ui.ChessBoardView,
            is com.pikafish.chess.ui.CurveView -> return
        }
        if (root.tag == REVIEW_NOSCALE_TAG) return
        if (root is TextView) {
            val base = reviewBaseTextSizes[root] ?: root.textSize.also { reviewBaseTextSizes[root] = it }
            root.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * scale)
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) applyReviewFontScale(root.getChildAt(i), scale)
        }
    }

    /** ★ 复盘字号滑杆：拖动即实时缩放整页文字（棋盘、曲线图原地不动），松手写入记忆。 */
    private fun showReviewFontSlider(root: android.view.View) {
        val pct = { (reviewFontScale * 100).toInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), dp(6))
        }
        val valLab = TextView(this).apply {
            text = "当前字号：${pct()}%"; textSize = 15f; gravity = Gravity.CENTER
        }
        val bar = SeekBar(this).apply {
            max = 24   // 80% ~ 200%，步长 5%
            progress = (((reviewFontScale - 0.8f) / 0.05f).toInt()).coerceIn(0, max)
        }
        val tip = TextView(this).apply {
            text = "只放大/缩小文字；棋盘与曲线图不参与缩放。放大后可直接上下滚动查看。"
            textSize = 12f; setPadding(0, dp(6), 0, 0)
        }
        box.addView(valLab); box.addView(bar); box.addView(tip)

        var pending = reviewFontScale
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                pending = 0.8f + p * 0.05f
                valLab.text = "当前字号：${(pending * 100).toInt()}%"
                applyReviewFontScale(root, pending)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                reviewFontScale = pending
                try { prefs.edit().putFloat("review_font_scale", pending).apply() } catch (_: Exception) {}
            }
        })
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("复盘字号")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                reviewFontScale = pending
                try { prefs.edit().putFloat("review_font_scale", pending).apply() } catch (_: Exception) {}
            }
            .setNeutralButton("复位") { _, _ ->
                reviewFontScale = 1.0f
                applyReviewFontScale(root, 1.0f)
                try { prefs.edit().putFloat("review_font_scale", 1.0f).apply() } catch (_: Exception) {}
            }
            .setNegativeButton("取消") { _, _ ->
                // 取消：退回拖动前的字号
                applyReviewFontScale(root, reviewFontScale)
            }
            .show()
    }

    /** ★ 底色调节：弹出一条完整 HSV 色谱取色板，让用户自选任意颜色作为整页纸张底色。
     *    选好点【采用】才回调 onPick；先前已自选的底色会作为初始值展示。 */
    private fun showColorPicker(initial: Int, onPick: (Int) -> Unit) {
        val picker = com.pikafish.chess.ui.HsvColorPicker(this).apply { setColor(initial) }
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(8))
        }
        host.addView(TextView(this).apply {
            text = "挑一个喜欢的整页底色（横拖色相条、点在色板上随意选色）："
            setTextColor(0xFFE8D5A3.toInt()); textSize = 13f
        })
        host.addView(picker, LinearLayout.LayoutParams(dp(250), dp(250)).apply { topMargin = dp(8); bottomMargin = dp(4) })
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("底色调节 · 色谱取色")
            .setView(host)
            .setPositiveButton("采用") { _, _ -> onPick(picker.color) }
            .setNegativeButton("取消", null)
            .create()
        styleScrimDialog(dlg, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f)
        dlg.show()
    }

    /** 点击小条：把复盘弹窗原样展开（同一实例，保留滚动位置与选中条目） */
    private fun restoreReview() {
        hideReviewMinBar()
        val wasMinimized = reviewMinimized
        reviewMinimized = false
        if (wasMinimized && reviewLivingDlg != null) {
            reviewLivingDlg?.show()
            return
        }
        // 兜底：实例已销毁（如页面重建），用内存缓存重建，同样不重算引擎
        val b = reviewLiveBundle
        if (b != null) showReviewResultPaged(b, reviewLivePage)
    }

    /** 小棋盘浮窗顶部的迷你按钮（✕ / 进），延续风格：暗底 + 琥珀描边 */
    private fun miniChip(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(0xFFF0E6D2.toInt()); b.textSize = 12f; b.minimumWidth = dp(46)
        b.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x335C5040.toInt()); cornerRadius = dp(6).toFloat(); setStroke(1, 0xFFFFB300.toInt())
        }
        b.setPadding(dp(10), dp(3), dp(10), dp(3))
        b.setOnClickListener { onClick() }
        return b
    }

    /** 小棋盘左侧竖木条用的按钮（✕ / 进）：纵向窄条，暗底 + 琥珀描边 */
    private fun verticalChip(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(0xFFF0E6D2.toInt()); b.textSize = 13f
        b.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x335C5040.toInt()); cornerRadius = dp(7).toFloat(); setStroke(1, 0xFFFFB300.toInt())
        }
        b.minimumHeight = dp(34)
        b.setPadding(dp(0), dp(3), dp(0), dp(3))
        b.setOnClickListener { onClick() }
        return b
    }

    /** 批处理结果弹窗的分页导航按钮（◀ 上一页 / 下一页 ▶） */
    private fun navBtn(text: String, enabled: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(0xFFF0E6D2.toInt()); b.textSize = 12f
        b.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x335C5040.toInt()); cornerRadius = dp(6).toFloat(); setStroke(1, 0xFFFFB300.toInt())
        }
        b.setPadding(dp(10), dp(5), dp(10), dp(5))
        b.isEnabled = enabled
        b.setOnClickListener { onClick() }
        return b
    }

    /**
     * ★ 复盘"错着"热链：点击后自动跳到该错着回合（该着走之前的局面），
     *   让用户原地把棋改走成建议正解、与后续原走法进行比较。复盘只盘主变，
     *   跳转重走会以"变招分支"截断原后续，便于对比试招。
     */
    private fun jumpToReviewBlunder(b: ReviewAnalyzer.ReviewBlunder) {
        val side = if (b.color == PieceColor.RED) "红方" else "黑方"
        try {
            chessGame.goToStep(b.ply)   // 跳到错着走之前的局面
            // ★ 切到历史视图，允许用户原地落子 → 触发"变招分支"改走对比
            chessBoard.viewingHistory = chessGame.isViewingHistory()
            restoreHighlightsFromHistory()
            chessGame.onBoardChanged?.invoke()
            updateSideButtons()
            updateStepInfo()
            refreshExplanationDisplay()
            toast("已跳到第 ${b.round} 回合${side}错着前：可直接改走正解「${b.bestMoveChinese}」试走对比")
        } catch (ex: Exception) {
            Log.e(TAG, "jumpToReviewBlunder: ${ex.message}", ex)
            toast("跳转失败：${ex.message}")
        }
    }

    /** ★ 主变演示的单个帧：局面 + 进入该帧那一手(起点/终点) + 该手中文记谱 */
    private data class VariationFrame(
        val board: Board,
        val from: Pair<Int, Int>?,
        val to: Pair<Int, Int>?,
        val label: String
    )

    /**
     * ★ 复盘「进入」→ 主变小棋盘演示窗。
     *   与「跳到该着试走」不同：那个跳回主界面演示；这个就地弹一个小窗，用常开小棋盘
     *   把引擎主变一步一步走给人看：上一步 / 下一步 / 播放·暂停 / 速度 / 关闭。
     *   关闭只关本小窗，复盘弹窗与结果原样保留（等于回到"没点进入"的状态），不彻底管死。
     *
     * @param b 该错着（取其 bestPv 作主变）
     * @param startFen 主变起算的局面（错着走之前的局面，与引擎给出 PV 时同一局面）
     */
    private fun showReviewVariationPlayer(b: ReviewAnalyzer.ReviewBlunder, startFen: String) {
        val pvUci = b.bestPv.split(' ', '\t', '\n').filter { it.length >= 4 }
        if (pvUci.isEmpty()) { toast("此主变为空，暂无可演示的着法"); return }

        // ── 预演全帧：0 = 错着前局面；1..n = 依次走完主变 ──
        val frames = ArrayList<VariationFrame>()
        val start = Board().apply { loadFromFen(startFen) }
        frames.add(VariationFrame(start, null, null, "错着前局面"))
        var curBoard = start
        for (u in pvUci) {
            val nb = curBoard.clone()
            val mv = Move.fromUci(u, nb) ?: break
            val fr = mv.from.row to mv.from.col
            val to = mv.to.row to mv.to.col
            val ch = try { com.pikafish.chess.engine.MoveExplainer.uciToChineseNotation(u, curBoard) } catch (_: Exception) { u }
            if (!nb.makeMove(mv)) break
            frames.add(VariationFrame(nb, fr, to, ch))
            curBoard = nb
        }
        if (frames.size <= 1) { toast("此主变无法回放（着法解析失败）"); return }
        val lastIdx = frames.size - 1
        val sideCh = if (b.color == PieceColor.RED) "红方" else "黑方"

        var idx = 0
        var playing = false
        val speeds = listOf("慢" to 1100L, "中" to 700L, "快" to 420L, "极快" to 200L)
        var speedIdx = 1
        val handler = Handler(Looper.getMainLooper())
        var playBtn: Button? = null

        // ── 小棋盘高度：既够看，又要保证「棋盘+按钮」整块 + 底下棋谱区都在屏内，外层绝不滚动。
        //   按 9列×10行 的盘形给足 1.12 倍宽的高；矮屏时再按屏高收回，最矮到底 140dp。 ──
        val dm = resources.displayMetrics
        val screenHdp = dm.heightPixels / dm.density
        val screenWdp = dm.widthPixels / dm.density
        val dialogWdp = minOf(screenWdp * 0.94f, 560f)
        val boardWdp = dialogWdp - 28f                        // 减 root 左右内边距(14+14)
        val boardHdp = minOf(boardWdp * 1.12f, screenHdp * 0.92f - 320f).coerceIn(140f, 360f)

        val titleTv = TextView(this).apply {
            text = "主变演示 · 第 ${b.round} 回合 · $sideCh"
            setTextColor(0xFFF0E6D2.toInt()); textSize = 15f; gravity = Gravity.CENTER
        }
        val subTv = TextView(this).apply {
            text = "错着«${b.movePlayedChinese}»　正解«${b.bestMoveChinese}»　绿叶箭头=下一手"
            setTextColor(0xFF9A8F7A.toInt()); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }
        val mini = MiniGameBoardView(this)
        val stepTv = TextView(this).apply {
            setTextColor(0xFFF0E6D2.toInt()); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(3))
        }
        val movesTv = TextView(this).apply {
            setTextColor(0xFFC8BF9E.toInt()); textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(4))
        }

        fun render() {
            val f = frames[idx]
            mini.setBoard(f.board, f.from, f.to)
            val nxt = frames.getOrNull(idx + 1)
            mini.setSuggestedMove(nxt?.from, nxt?.to)   // 绿色箭头：下一手往哪走
            stepTv.text = if (idx == 0) "错着前局面（第 ${b.round} 回合，轮到$sideCh）"
                           else "第 $idx / $lastIdx 步　刚走：${f.label}"
            movesTv.text = (1..lastIdx).joinToString(" → ") { i ->
                if (i == idx) "【${frames[i].label}】" else frames[i].label
            }
        }

        val tick = object : Runnable {
            override fun run() {
                if (!playing) return
                if (idx >= lastIdx) { playing = false; playBtn?.text = "▶ 播放"; return }
                idx++; render()
                handler.postDelayed(this, speeds[speedIdx].second)
            }
        }

        fun stopPlay() {
            playing = false
            handler.removeCallbacks(tick)
            playBtn?.text = "▶ 播放"
        }

        fun chip(text: String, color: Int = 0xFFF0E6D2.toInt(), onClick: () -> Unit): Button {
            val btn = Button(this)
            btn.text = text; btn.isAllCaps = false; btn.textSize = 12f
            btn.minimumWidth = 0; btn.maxLines = 1
            btn.setPadding(dp(8), dp(5), dp(8), dp(5))
            btn.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x335C5040.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(dp(1).toInt(), 0xFFFFB300.toInt())
            }
            btn.setTextColor(color)
            btn.setOnClickListener { onClick() }
            return btn
        }

        val btnPrev = chip("◀ 上一步") { if (idx > 0) { stopPlay(); idx--; render() } }
        val btnPlay = chip("▶ 播放", 0xFF8CD7FF.toInt()) {
            if (playing) {
                stopPlay()
            } else {
                if (idx >= lastIdx) idx = 0          // 走到头再点播放 → 从头放
                playing = true; playBtn?.text = "⏸ 暂停"
                render()
                handler.postDelayed(tick, speeds[speedIdx].second)
            }
        }
        playBtn = btnPlay
        val btnNext = chip("下一步 ▶") { if (idx < lastIdx) { stopPlay(); idx++; render() } }
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        listOf(btnPrev, btnPlay, btnNext).forEach {
            val lp = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(4); lp.marginEnd = dp(4)
            ctrlRow.addView(it, lp)
        }

        lateinit var btnSpeed: Button
        btnSpeed = chip("速度:${speeds[speedIdx].first}", 0xFFFFD24A.toInt()) {
            speedIdx = (speedIdx + 1) % speeds.size
            btnSpeed.text = "速度:${speeds[speedIdx].first}"
        }
        var dlgRef: androidx.appcompat.app.AlertDialog? = null
        val btnClose = chip("关闭", 0xFFFF8A80.toInt()) { dlgRef?.dismiss() }
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        listOf(btnSpeed, btnClose).forEach {
            val lp = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(4); lp.marginEnd = dp(4)
            speedRow.addView(it, lp)
        }
        // ── 顺序一口气排完（天然无缝、任何窄屏都不干涉）：
        //   标题 → 小棋盘 → 步数 → 上一步/播放/下一步 → 速度/关闭 → 棋谱(自成一块，只滚它)。
        //   棋盘与按钮是同一个固定整块，恒在屏内、位置不随棋谱长短漂移；棋谱接在按钮之后，
        //   变化多也只在它自己的格子里滚动，绝不牵动上面的棋盘与按钮。 ──
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        root.addView(titleTv)
        root.addView(subTv)
        root.addView(mini, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(boardHdp)))
        root.addView(stepTv)
        root.addView(ctrlRow)
        root.addView(speedRow)
        val movesScroll = ScrollView(this).apply { isFillViewport = false; addView(movesTv) }
        root.addView(movesScroll, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setView(root).create()
        dlgRef = dlg
        styleScrimDialog(dlg, 0xB3302820.toInt(), radiusDp = 16, dim = 0.32f)
        // 关闭/返回/点外部：只停表关本小窗，复盘弹窗与结果原样保留（回到没点「进入」的状态）
        dlg.setOnDismissListener { playing = false; handler.removeCallbacks(tick) }
        render()
        dlg.show()
        // ★ 窗口尺寸：宽 94%；高 = min(可视区 92%, 固定块 + 棋谱自然高 + 边距)。
        //   短主变不留大空旷，长主变只在棋谱格内滚动；棋盘与按钮自始至终都在屏内。 ──
        try {
            val vis = android.graphics.Rect()
            window?.decorView?.getWindowVisibleDisplayFrame(vis)
            val visW = if (vis.width() > 0) vis.width() else dm.widthPixels
            val visH = if (vis.height() > 0) vis.height() else dm.heightPixels
            val winW = (visW * 0.94f).toInt().coerceAtMost((560 * dm.density).toInt())
            val capH = (visH * 0.92f).toInt().coerceAtMost((820 * dm.density).toInt())
            dlg.window?.setGravity(Gravity.CENTER)
            dlg.window?.setLayout(winW, capH)
            root.post {
                try {
                    val fixedH = titleTv.height + subTv.height + mini.height + stepTv.height +
                        ctrlRow.height + speedRow.height + dp(22)
                    val listH = (movesTv.height + dp(10)).coerceIn(dp(70), dp(240))
                    dlg.window?.setLayout(winW, (fixedH + listH).coerceAtMost(capH))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** ★ 导入棋谱：点开弹出各格式小按钮子菜单（与导出对称）。XQF 为二进制走文件选择。 */
    private fun showImportChooserDialog() {
        val labels = arrayOf("自动识别", "中文 PGN", "WXF 记谱", "ICCS 坐标", "文本 TXT", "鹏飞 PFC", "东萍 DhtmlXQ", "FEN 局面", "演播室 XQF (文件)", "象棋桥 CBL (文件)")
        val keys = arrayOf("AUTO", "PGN", "WXF", "ICCS", "TXT", "PFC", "DHTMLXQ", "FEN", "XQF", "CBL")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        layout.addView(TextView(this).apply {
            text = "选择导入格式"
            setTextColor(0xFFE8D5A3.toInt()); textSize = 16f; gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "文字格式粘贴或选文件；XQF / CBL 直接选文件"
            setTextColor(0xFF9A8F7A.toInt()); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        })
        // ★ 一排两个网格：占地砍半，每格内容一字不差（10 项 → 5 行）
        keys.indices.chunked(2).forEach { rowIdx ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowIdx.forEach { i ->
                val key = keys[i]
                val b = Button(this).apply {
                    text = labels[i]
                    setTextColor(0xFFF0E6D2.toInt()); textSize = 13f
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x335C5040.toInt()); cornerRadius = dp(6).toFloat()
                        setStroke(1, 0xFFFFB300.toInt())
                    }
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (i > 0) lp.marginStart = dp(6)
                lp.topMargin = dp(5)
                row.addView(b, lp)
                b.setOnClickListener {
                    if (key == "XQF" || key == "CBL") { importFormat = key; importFile(key) }
                    else showImportTextDialog(key)
                }
            }
            layout.addView(row)
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(true)
            .create()
        styleScrimDialog(dlg, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f)
        dlg.show()
    }

    private fun showImportTextDialog(format: String) {
        val auto = format == "AUTO"
        val dialogView = layoutInflater.inflate(R.layout.dialog_import_text, null)
        val etContent = dialogView.findViewById<android.widget.EditText>(R.id.etImportContent)
        val btnFromFile = dialogView.findViewById<Button>(R.id.btnFromFile)
        val btnPaste = dialogView.findViewById<Button>(R.id.btnPasteFromClipboard)
        val btnImport = dialogView.findViewById<Button>(R.id.btnImport)
        val btnImportPlay = dialogView.findViewById<Button>(R.id.btnImportPlay)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (auto) "导入棋谱（自动识别：FEN/PGN/TXT）" else "导入${format}（粘贴文字或选择文件）")
            .setView(dialogView)
            .create()

        fun doImport(andPlay: Boolean) {
            val content = etContent.text.toString().trim()
            if (content.isEmpty()) {
                Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show()
                return
            }
            dialog.dismiss()
            val fmt = if (auto) detectFormat(content) else format
            importNotation(content, fmt, andPlay)
        }

        // 从文件选择
        btnFromFile.setOnClickListener {
            dialog.dismiss()
            importFile(if (auto) "AUTO" else format)
        }

        // 从剪贴板粘贴
        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                etContent.setText(text)
                // 粘贴后即时提示识别到的格式
                val fmt = if (auto) detectFormat(text) else format
                Toast.makeText(this, "已粘贴（识别为 $fmt）", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        // 导入并打谱（导入成功即跳转背谱沙盘）
        btnImportPlay.setOnClickListener { doImport(true) }
        // 普通导入（导入后可自行AI拆解）
        btnImport.setOnClickListener { doImport(false) }
        // 取消在最后
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /** ★ 导出棋谱：清爽的分组一键式面板。
     *  文本格式点格式名 = 复制（可直接转发），右侧「保存」= 落成文件；
     *  含变招的格式会标注「含 N 个变招」，一眼知道变招有没有带上，不再逐项确认。 */
    private fun showExportChooserDialog() {
        val varCount = runCatching { chessGame.variationCount() }.getOrDefault(0)
        // 分组：变招无损的文本格式 → 主推；纯文本/散排格式 → 次要；二进制文件格式 → 文件区。
        data class Fmt(val label: String, val key: String, val hint: String, val binary: Boolean, val varFmt: Boolean)
        val textVar = listOf(
            Fmt("中文 PGN", "PGN", "含变招·最通用", false, true),
            Fmt("东萍 DhtmlXQ", "DHTMLXQ", "含变招·网页谱", false, true),
            Fmt("鹏飞 PFC", "PFC", "含变招·xml", false, true)
        )
        val textPlain = listOf(
            Fmt("WXF 记谱", "WXF", "", false, false),
            Fmt("ICCS 坐标", "ICCS", "", false, false),
            Fmt("文字棋盘", "TEXTBOARD", "", false, false),
            Fmt("文本 TXT", "TXT", "", false, false),
            Fmt("FEN 局面", "FEN", "仅初始局面", false, false)
        )
        val files = listOf(
            Fmt("演播室 XQF", "XQF", "含变招·文件", true, true),
            Fmt("象棋桥 CBL", "CBL", "含变招·文件", true, true)
        )

        val scroller = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(14))
        }
        layout.addView(TextView(this).apply {
            text = "导出本局棋谱"
            setTextColor(0xFFE8D5A3.toInt()); textSize = 17f; gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "共 ${runCatching { chessGame.getMoveCount() }.getOrDefault(0)} 手" +
                    if (varCount > 0) " · 含 $varCount 个变招" else " · 无变招"
            setTextColor(0xFFF0B74A.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        })

        fun section(title: String) {
            layout.addView(TextView(this).apply {
                text = title
                setTextColor(0xFFC9AE7C.toInt()); textSize = 12f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setPadding(0, dp(6), 0, dp(2))
            })
        }
        // ★ 网格化：每格一个格式按钮，长按=仅复制；点击=导出并保存（与原行为一致）
        fun grid(items: List<Fmt>) {
            items.chunked(2).forEach { rowItems ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                rowItems.forEachIndexed { c, fmt ->
                    val cell = Button(this).apply {
                        val actionHint = if (fmt.binary) "·保存文件" else "·复制转发"
                        text = "${fmt.label}\n${actionHint}"
                        setTextColor(0xFFF0E6D2.toInt()); textSize = 13f
                        gravity = Gravity.CENTER
                        setLineSpacing(2f, 1f)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (fmt.varFmt) 0x334A7A3A.toInt() else 0x335C5040.toInt())
                            cornerRadius = dp(6).toFloat()
                            setStroke(1, if (fmt.varFmt) 0xFF8FC981.toInt() else 0xFFFFB300.toInt())
                        }
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        // 点击=导出并保存（文本格式也复制）
                        setOnClickListener { doExportGame(fmt.key, fmt.label) }
                        // 长按=仅复制（文本格式），二进制格式长按提示
                        setOnLongClickListener {
                            if (fmt.binary) {
                                Toast.makeText(this@MainActivity, fmt.hint, Toast.LENGTH_SHORT).show()
                            } else {
                                copyExportText(fmt.key, fmt.label)
                                Toast.makeText(this@MainActivity, "已复制${fmt.label}到剪贴板", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                    }
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    if (c > 0) lp.marginStart = dp(6)
                    lp.topMargin = dp(5)
                    row.addView(cell, lp)
                }
                layout.addView(row)
            }
        }

        section("▸ 文本格式（点击=复制·转发，长按=仅复制）")
        val allText = textVar + textPlain
        allText.chunked(2).forEach { rowItems ->
            grid(rowItems)
        }
        section("▸ 文件格式（存成文件，长按看说明）")
        grid(files)
        layout.addView(TextView(this).apply {
            text = "提示：PGN / DhtmlXQ / PFC / XQF / CBL 均会连同变招一起导出，" +
                    if (varCount > 0) "本局的 $varCount 个变招已默认带上。" else "当前局没有变招。"
            setTextColor(0xFF8A8178.toInt()); textSize = 11f
            setPadding(0, dp(8), 0, dp(2))
        })
        scroller.addView(layout)

        val dlg = android.app.AlertDialog.Builder(this)
            .setView(scroller)
            .setCancelable(true)
            .create()
        styleScrimDialog(dlg, 0xB3302820.toInt(), radiusDp = 16, dim = 0.22f)
        dlg.show()
    }

    /** 生成某格式的导出文本（复制与保存共用同一份内容，保证一致）。 */
    private fun buildExportText(key: String): String = when (key) {
        "FEN" -> chessGame.exportFen()
        "PGN" -> chessGame.exportPgn()
        "WXF" -> chessGame.exportWxf()
        "TEXTBOARD" -> chessGame.exportTextBoard()
        "TXT" -> chessGame.exportTxt()
        "ICCS" -> chessGame.exportIccs()
        "DHTMLXQ" -> chessGame.exportDhtmlXq()
        "PFC" -> chessGame.exportPfc()
        else -> ""
    }

    /** 一键复制导出内容（与保存的文件内容逐字符一致）。 */
    private fun copyExportText(key: String, label: String) {
        val text = buildExportText(key)
        if (text.isBlank()) {
            Toast.makeText(this, "当前没有可复制的${label}内容", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard(label, text)
    }

    /** ★ 真正导出：生成内容 → 复制 → 写文件 → 成功弹窗 */
    private fun doExportGame(key: String, label: String) {
        // XQF / CBL 是二进制格式：直接写文件，成功弹窗说明
        if (key == "XQF" || key == "CBL") {
            val bytes = try {
                if (key == "XQF") chessGame.exportXqf() else chessGame.exportCbl()
            } catch (e: Exception) { null }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(this, "当前没有可导出的内容", Toast.LENGTH_SHORT).show()
                return
            }
            val saved = saveBytesToFileReturnName(key.lowercase(), bytes)
            android.app.AlertDialog.Builder(this)
                .setTitle("导出成功")
                .setMessage("「$label」已导出\n\n已保存：$saved")
                .setPositiveButton("好的", null)
                .setCancelable(true)
                .show()
            return
        }
        val text = buildExportText(key)
        if (text.isBlank()) {
            Toast.makeText(this, "当前没有可导出的内容", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard(label, text)
        val saved = saveToFileReturnName(key.lowercase(), text)
        // ★ 成功弹窗：说明导出了什么格式、结果如何
        android.app.AlertDialog.Builder(this)
            .setTitle("导出成功")
            .setMessage("${label} 已导出\n\n已复制到剪贴板\n已保存：${saved}")
            .setPositiveButton("好的", null)
            .setCancelable(true)
            .show()
    }

    /** 保存二进制文件（供 XQF 等二进制格式导出）并返回路径 */
    private fun saveBytesToFileReturnName(ext: String, content: ByteArray): String {
        val dir = java.io.File(Environment.getExternalStorageDirectory(), "HHXQ")
        if (!dir.exists()) dir.mkdirs()
        val fileName = "黄昏象棋_${System.currentTimeMillis()}.$ext"
        val file = java.io.File(dir, fileName)
        try {
            file.writeBytes(content)
            return "HHXQ/$fileName"
        } catch (e: Exception) {
            return try {
                val fallback = java.io.File(filesDir, fileName)
                fallback.writeBytes(content)
                "内部存储/${fallback.name}"
            } catch (e2: Exception) {
                "保存失败: ${e2.message}"
            }
        }
    }

    /**
     * ★ 覆盖保存：把内容写回【原来那个文件】，位置与名字都不变（相当于"原稿保存"）。
     * 路径形如 "HHXQ/xxx.txt" 或 "内部存储/xxx.txt"；找不到时退回按原文件名新建。
     */
    private fun rewriteVaultTxtFile(originalPath: String, content: String): String {
        val dirName = originalPath.substringBeforeLast('/', "")
        val onlyName = originalPath.substringAfterLast('/')
        val dir = when (dirName) {
            "内部存储" -> filesDir
            else -> java.io.File(Environment.getExternalStorageDirectory(), "HHXQ").apply { mkdirs() }
        }
        val target = java.io.File(dir, onlyName)
        return try {
            target.writeText(content)
            Log.i(TAG, "覆盖保存：${target.absolutePath}")
            originalPath
        } catch (e: Exception) {
            Log.w(TAG, "覆盖写回失败，改为另存：${e.message}")
            saveToFileReturnName("txt", content, onlyName.substringBeforeLast('.'))
        }
    }

    /** 保存到文件并返回保存路径/文件名（供成功提示使用） */
    private fun saveToFileReturnName(ext: String, content: String, preferredName: String? = null): String {
        val dir = java.io.File(Environment.getExternalStorageDirectory(), "HHXQ")
        if (!dir.exists()) dir.mkdirs()
        // ★ 详细保存时可以指定文件名；同名则加时间戳后缀，绝不覆盖别人。
        val wanted = preferredName?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var fileName = if (wanted != null) "$wanted.$ext" else "黄昏象棋_${System.currentTimeMillis()}.$ext"
        var file = java.io.File(dir, fileName)
        if (wanted != null && file.exists()) {
            fileName = "${wanted}_${System.currentTimeMillis()}.$ext"
            file = java.io.File(dir, fileName)
        }
        try {
            file.writeText(content)
            Log.i(TAG, "棋谱已保存: ${file.absolutePath}")
            return "HHXQ/$fileName"
        } catch (e: Exception) {
            Log.e(TAG, "保存到HHXQ失败: ${e.message}", e)
            return try {
                val fallback = java.io.File(filesDir, fileName)
                fallback.writeText(content)
                "内部存储/${fallback.name}"
            } catch (e2: Exception) {
                "保存失败: ${e2.message}"
            }
        }
    }

    /**
     * ★ 自动识别棋谱文本格式：东萍 / PFC / ICCS / PGN / FEN / TXT。
     *   - 东萍 DhtmlXQ：含 [DhtmlXQ] 或 [DhtmlXQ_movelist] 标记；
     *   - PFC：XML，根节点带 m 属性 / 形如 <qipu ...> / <n m="走法">；
     *   - ICCS：大写列坐标，如 1. H2-E2 H9-G7；
     *   - PGN：含头标签(如 [Event) 或着法序号或结果符号；
     *   - FEN：单行、含 9 个 '/' 分隔的 10 段盘面描述；
     *   - 其余（中文记谱 / UCI）按 TXT 处理。
     */
    private fun detectFormat(content: String): String {
        val c = content.trim()
        if (c.isEmpty()) return "TXT"
        // 东萍：DhtmlXQ 标记
        if (c.contains("[DhtmlXQ]") || c.contains("[DhtmlXQ_movelist]")) return "DHTMLXQ"
        // PFC：鹏飞 XML
        if (Regex("<(qipu|pf|n)[^>]*m\\s*=").containsMatchIn(c) &&
            (c.contains("<qipu") || c.contains("</qipu>") || c.contains("<pf") ||
                Regex("<n\\s+[^>]*m\\s*=").containsMatchIn(c))) {
            if (c.trimStart().startsWith("<?xml") || c.contains("<qipu") || c.contains("</qipu>")) return "PFC"
        }
        // ★ UCI position 格式：形如 "fen(w) moves e2e4 e7e5 ..."（很多棋谱站/棋软导谱格式）。
        //   必须在 FEN 判定之前拦截——否则会因"第一个空格前是棋盘段"被误判成纯 FEN，
        //   导致 moves 之后整串走法被丢弃、退化成只识别一个局面。
        val rawC = c
        val movesKw = Regex("\\bmoves\\b").containsMatchIn(rawC)
        if (movesKw) {
            val movesIdx = rawC.indexOf("moves")
            val fenPart = rawC.substring(0, movesIdx).trim()
            if (fenPart.count { it == '/' } == 9) return "UCI"
        }
        // ICCS：大写坐标 如 H2-E2
        if (Regex("[A-I][0-9]-[A-I][0-9]").containsMatchIn(c)) return "ICCS"
        if (c.contains("[Event") || c.contains("[Red") || c.contains("[Black") ||
            Regex("\\d+\\.\\s*\\.?").containsMatchIn(c) ||
            Regex("\\b1-0\\b|\\b0-1\\b|\\b1/2-1/2\\b").containsMatchIn(c)) return "PGN"
        if (!c.contains("\n")) {
            val board = c.substringBefore(' ').trim()
            if (board.count { it == '/' } == 9 &&
                board.matches(Regex("^[1-9A-Za-z]+(/[1-9A-Za-z]+){9}$"))) return "FEN"
        }
        return "TXT"
    }

    private fun importFile(format: String) {
        importFormat = format
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/plain",
                "application/x-chess-pgn",
                "application/octet-stream"
            ))
        }
        startActivityForResult(intent, REQUEST_IMPORT_FILE)
    }

    /** ★ 字符集容错读取导入文本（URI 版）：委托 decodeChessText。
     *   象棋桥/象棋笔记/多数国产棋软导出的 PGN、中文记谱常是 GBK，一律按 UTF-8 读会把整篇着法读成乱码。 */
    private fun readImportText(uri: android.net.Uri): String {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        } catch (_: Exception) { ByteArray(0) }
        return decodeChessText(bytes)
    }

    /**
     * ★ 棋谱文本解码：委托 QipuLibrary.decodeText（BOM → 严格 UTF-8 → GB18030 → ISO-8859-1）。
     *
     * 为什么必须集中在一处：同一份 GBK 编码的 PGN，导入菜单能读、宝库却读不出，
     * 就是因为各条读文件路径各写各的（`bufferedReader()`/`String(bytes, UTF_8)` 默认都是 UTF-8），
     * 一旦遇到非 UTF-8 就把中文着法整篇读成 U+FFFD，自动识别随即 0 步失败。
     */
    private fun decodeChessText(bytes: ByteArray): String =
        com.pikafish.chess.qipu.QipuLibrary.decodeText(bytes)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // ★ 独立进程解惑沙盒返回 → 恢复按钮为"解惑:关"
        if (requestCode == REQUEST_SANDBOX) {
            btnAnalyze.text = "解惑:关"
            updateStatus()
            return
        }

        if (requestCode == REQUEST_IMPORT_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val forced = importFormat
                // ★ XQF / CBL 为二进制格式：解析较重（大书/全集可达数十万节点），放后台线程，防主界面 ANR/闪退
                val magic = if (forced == "XQF") "XQF" else if (forced == "CBL") "CBL"
                            else probeMagic(uri)   // AUTO/未知 → 按魔数嗅探，实现免选导入
                if (magic == "XQF" || magic == "CBL") {
                    importBinaryFile(uri, magic)
                } else {
                    try {
                        // ★ 必须走 decodeChessText：GBK 编码的 PGN 用 bufferedReader()（默认 UTF-8）读会整篇乱码
                        val content = readImportText(uri)
                        if (content.isNotBlank()) {
                            // 自动识别模式下，先按文件内容判断格式（FEN/PGN/TXT）
                            val resolved = if (forced == "AUTO" || forced.isBlank())
                                detectFormat(content.trim()) else forced
                            importFormat = resolved
                            // 文件导入 → 先导入到主界面；成功后弹窗让用户选：导入并打谱 / 留在主界面
                            val ok = importNotation(content.trim(), resolved, false)
                            if (ok) {
                                askWhereToGoAfterImport()
                            }
                        } else {
                            Toast.makeText(this, "文件内容为空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else if (requestCode == REQUEST_PDF_IMPORT && resultCode == RESULT_OK) {
            // ★ 外置读物：选中的本地 PDF → 收进书架并直接开读
            data?.data?.let { uri -> importPdfToShelf(uri, null) }
        } else if (requestCode == REQUEST_VAULT_FILE && resultCode == RESULT_OK) {
            // ★ 点宝库参考条目后选中的本地棋谱文件 → 加载
            data?.data?.let { uri ->
                try {
                    // ★ 与「导入」同一条解码：GBK 棋谱不再被 bufferedReader() 读成乱码
                    val content = readImportText(uri)
                    if (content.isNotBlank()) {
                        val steps = chessGame.loadFromGivingTxt(content)
                        if (steps > 0) {
                            updateStepInfo()
                            restoreHighlightsFromHistory()
                            if (bookMemorizer.active) stopBookMemorize()
                            chessGame.cancelSetup()
                            chessBoard.clearSelection()
                            chessBoard.viewingHistory = false
                            chessGame.onBoardChanged?.invoke()
                            updateSideButtons()
                            refreshExplanationDisplay()
                            chessGame.checkAndTriggerAiMove()
                            Toast.makeText(this, "已加载本地棋谱（$steps 步，含变招）", Toast.LENGTH_SHORT).show()
                        } else {
                            // 该 TXT 不是标准的记谱给法 → 回退到通用自动识别导入
                            val resolved = detectFormat(content.trim())
                            importNotation(content, resolved, false)
                        }
                    } else {
                        Toast.makeText(this, "文件内容为空", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "REQUEST_VAULT_FILE: ${e.message}", e)
                    Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else if (requestCode == REQUEST_SAVE_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(pendingSaveContent.toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                    }
                    Toast.makeText(this, "已保存 ${pendingSaveExt.uppercase()} 文件", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "SAF write failed: ${e.message}")
                    Toast.makeText(this, "写入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            pendingSaveContent = ""
            pendingSaveExt = ""
        }
    }

    /** 轻量嗅探导入文件魔数：XQF="XQ"、CBL="CCBridge"；非命中返回空串（按文本处理）。 */
    private fun probeMagic(uri: android.net.Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(9)
                val n = ins.read(buf, 0, buf.size)
                when {
                    n >= 2 && buf[0].toInt() == 'X'.code && buf[1].toInt() == 'Q'.code -> "XQF"
                    n >= 8 && String(buf, 0, 8) == "CCBridge" -> "CBL"
                    else -> ""
                }
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * XQF / CBL 二进制棋谱导入：解析较重（大书/全集可达数十万节点），
     * 放后台协程执行，避免在主线程同步展开整棵变招树导致 ANR/闪退。
     */
    private fun importBinaryFile(uri: android.net.Uri, format: String) {
        initScope.launch(Dispatchers.IO) {
            val msg = try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.isEmpty()) "文件内容为空"
                else {
                    // ★ 熔断：AUTO/空白时按魔数嗅探（XQF="XQ"、CBL="CCBridge"），实现真正的免选导入
                    var fmt = format
                    if (fmt != "XQF" && fmt != "CBL") {
                        fmt = when {
                            bytes.size >= 2 && bytes[0].toInt() == 'X'.code && bytes[1].toInt() == 'Q'.code -> "XQF"
                            bytes.size >= 8 && String(bytes, 0, 8) == "CCBridge" -> "CBL"
                            else -> "CBL"
                        }
                    }
                    when (fmt) {
                        "XQF" -> chessGame.importXqf(bytes)
                        else -> {
                            val games = try {
                                com.pikafish.chess.qipu.CblDecoder.decodeBook(bytes, "导入棋谱")
                            } catch (ex: Exception) { emptyList() }
                            if (games.isEmpty()) "CBL 文件未能识读出有效棋局"
                            else chessGame.importCblGame(games[0])   // ★ 直接导入，绕开 toPgn 丢注释的有损链
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "REQUEST_IMPORT_FILE:binary", e)
                "${format}读取失败: ${e.message}"
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                updateStepInfo()
                restoreHighlightsFromHistory()
                if (msg.startsWith("成功")) {
                    val fmtName = if (importFormat == "XQF") "XQF 二进制棋谱文件" else "CBL 象棋桥棋谱文件"
                    try { saveImportedGameRecord("($fmtName)", "XQF", msg) }
                    catch (e: Exception) { Log.e(TAG, "saveImportedGameRecord", e) }
                    askWhereToGoAfterImport()
                }
            }
        }
    }

    private fun importFenContent(fen: String) {
        val msg = chessGame.importFen(fen)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        updateStepInfo()
    }

    private fun importNotation(content: String, format: String, andPlay: Boolean = false): Boolean {
        var ok = false
        try {
            val result = when (format) {
                "PGN" -> {
                    // 使用ChessGame的完整PGN导入（支持RAV变着 + 中文记谱 + UCI）
                    val msg = chessGame.importPgn(content)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    updateStepInfo()
                    restoreHighlightsFromHistory()
                    ok = msg.startsWith("成功")
                    msg
                }
                "FEN" -> {
                    importFenContent(content)
                    ok = true
                    "FEN局面已加载"
                }
                "UCI" -> {
                    // ★★ UCI position 格式（"fen w moves ..."）专门导入：
                    //   拆出 FEN 起点 + moves 走法串，从该起点完整回放，绝不丢走法、不退化单局面。
                    val msg = chessGame.importUciPosition(content)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    updateStepInfo(); restoreHighlightsFromHistory()
                    ok = msg.startsWith("成功"); msg
                }
                "WXF" -> {
                    // WXF / 中文记谱（如 炮二平五、马8进7）：复用中文记谱导入
                    val msg = chessGame.importChineseNotationTxt(content)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    updateStepInfo(); restoreHighlightsFromHistory()
                    ok = msg.startsWith("成功"); msg
                }
                "TXT" -> {
                    // 先尝试中文记谱导入
                    val msg = chessGame.importChineseNotationTxt(content)
                    if (msg.startsWith("成功")) {
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        updateStepInfo()
                        restoreHighlightsFromHistory()
                        ok = true
                        msg
                    } else {
                        // 中文记谱失败，尝试UCI格式导入
                        val uciMsg = chessGame.importUciMoves(content)
                        Toast.makeText(this, uciMsg, Toast.LENGTH_SHORT).show()
                        updateStepInfo()
                        restoreHighlightsFromHistory()
                        ok = uciMsg.startsWith("成功")
                        uciMsg
                    }
                }
                "ICCS" -> {
                    val msg = chessGame.importIccs(content)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    updateStepInfo(); restoreHighlightsFromHistory()
                    ok = msg.startsWith("成功"); msg
                }
                "DHTMLXQ" -> {
                    val msg = chessGame.importDhtmlXq(content)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    updateStepInfo(); restoreHighlightsFromHistory()
                    ok = msg.startsWith("成功"); msg
                }
                "PFC" -> {
                    val msg = chessGame.importPfc(content)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    updateStepInfo(); restoreHighlightsFromHistory()
                    ok = msg.startsWith("成功"); msg
                }
                else -> {
                    val msg = chessGame.importUciMoves(content)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    updateStepInfo()
                    restoreHighlightsFromHistory()
                    ok = msg.startsWith("成功")
                    msg
                }
            }
            // ★ 导入后保存到棋谱列表
            saveImportedGameRecord(content, format, result)
            // ★ 导入并打谱：成功则直接跳转背谱沙盘，外来棋谱也能打到谱
            if (andPlay && ok) {
                openBookSandbox()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
        return ok
    }

    /** ★ 文件导入成功后，让用户确认接下来怎么走：去背谱打谱，还是留在主界面拆解 */
    private fun askWhereToGoAfterImport() {
        val isFen = importFormat == "FEN"
        AlertDialog.Builder(this)
            .setTitle("导入成功")
            .setMessage(
                if (isFen) "局面已就绪。接下来怎么走？"
                else "棋谱已导入主界面。接下来怎么走？"
            )
            .setPositiveButton("导入并打谱 →") { _, _ -> openBookSandbox() }
            .setNegativeButton("留在主界面（直接导入）", null)
            .show()
    }

    private fun importPgnContent(pgn: String) {
        // 已由 ChessGame.importPgn() 处理，此方法保留兼容
        val msg = chessGame.importPgn(pgn)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        updateStepInfo()
        // 确保解说/棋谱面板可见（停在棋谱起始局面）
        if (scrollViewExplanation.visibility != View.VISIBLE) {
            scrollViewExplanation.visibility = View.VISIBLE
        }
        updateStepInfo()
        restoreHighlightsFromHistory()
        refreshExplanationDisplay()
    }

    private fun importMovesContent(content: String) {
        val moves = mutableListOf<String>()
        // 提取所有UCI格式走法
        val uciPattern = Regex("[a-i][0-9][a-i][0-9][nbrq]?")
        for (match in uciPattern.findAll(content)) {
            moves.add(match.value)
        }

        if (moves.isEmpty()) {
            Toast.makeText(this, "未找到有效走法", Toast.LENGTH_SHORT).show()
            return
        }

        replayMoves(moves)
    }

    private fun replayMoves(moves: List<String>) {
        chessGame.newGame()
        layoutExplanationList.removeAllViews()
        layoutExplanationList.addView(tvExplanation)
        tvExplanation.text = "正在导入${moves.size}步棋谱…"

        // 逐步执行走法
        var successCount = 0
        for (uci in moves) {
            val move = com.pikafish.chess.model.Move.fromUci(uci, chessGame.board)
            if (move != null && chessGame.board.getLegalMoves(chessGame.board.currentPlayer).any { it.toUci() == uci }) {
                chessGame.board.makeMove(move)
                successCount++
            } else {
                break
            }
        }

        chessGame.onBoardChanged?.invoke()
        updateStepInfo()
        Toast.makeText(this, "成功导入${successCount}步棋谱", Toast.LENGTH_SHORT).show()
    }

    // ── ★ 棋谱列表：保存与显示导入的棋谱 ★ ──

    /**
     * 保存导入的棋谱到SharedPreferences（持久化）
     * 记录：导入时间、格式、内容摘要、完整内容、走法数量
     */
    private fun saveImportedGameRecord(content: String, format: String, resultMsg: String) {
        try {
            val recordsJson = prefs.getString(KEY_GAME_RECORDS, null) ?: "[]"
            val arr = org.json.JSONArray(recordsJson)

            val record = org.json.JSONObject()
            record.put("timestamp", System.currentTimeMillis())
            record.put("format", format)
            record.put("content", content)
            record.put("result", resultMsg)
            // 提取走法数量
            val moveCount = resultMsg.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            record.put("moveCount", moveCount)
            // 生成摘要（前30字）
            val summary = content.lines().filter { it.isNotBlank() }.take(3).joinToString(" ")
            record.put("summary", if (summary.length > 50) summary.substring(0, 50) + "…" else summary)

            arr.put(record)

            // 最多保留50条记录
            while (arr.length() > 50) {
                arr.remove(0)
            }

            prefs.edit().putString(KEY_GAME_RECORDS, arr.toString()).apply()
            Log.d(TAG, "棋谱记录已保存，共${arr.length()}条")
        } catch (e: Exception) {
            Log.e(TAG, "保存棋谱记录失败: ${e.message}")
        }
    }

    /**
     * 显示棋谱列表对话框
     */
    private fun showGameRecordList() {
        try {
            val recordsJson = prefs.getString(KEY_GAME_RECORDS, null) ?: "[]"
            val arr = org.json.JSONArray(recordsJson)

            if (arr.length() == 0) {
                Toast.makeText(this, "暂无导入的棋谱记录", Toast.LENGTH_SHORT).show()
                return
            }

            // 构建列表项
            val items = mutableListOf<String>()
            val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            val records = mutableListOf<org.json.JSONObject>()

            for (i in arr.length() - 1 downTo 0) {
                val record = arr.getJSONObject(i)
                records.add(record)
                val ts = record.optLong("timestamp", 0)
                val dateStr = if (ts > 0) dateFormatter.format(java.util.Date(ts)) else "未知时间"
                val format = record.optString("format", "?")
                val moveCount = record.optInt("moveCount", 0)
                val summary = record.optString("summary", "")
                items.add("[$format] $dateStr  ${moveCount}步\n$summary")
            }

            val itemsArray = items.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("棋谱列表（共${records.size}条）")
                .setItems(itemsArray) { _, which ->
                    val record = records[which]
                    val content = record.optString("content", "")
                    val format = record.optString("format", "TXT")
                    if (content.isNotEmpty()) {
                        importNotation(content, format)
                    } else {
                        Toast.makeText(this, "棋谱内容为空", Toast.LENGTH_SHORT).show()
                    }
                }
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空全部") { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle("确认清空")
                        .setMessage("确定要清空所有棋谱记录吗？")
                        .setPositiveButton("清空") { _, _ ->
                            prefs.edit().remove(KEY_GAME_RECORDS).apply()
                            Toast.makeText(this, "已清空所有棋谱记录", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示棋谱列表失败: ${e.message}")
            Toast.makeText(this, "读取棋谱列表失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ── ★ 爬楼显示刷新 ★ ──

    // ── 从实际走棋历史恢复光标高亮 ──

    /**
     * 根据当前棋局的走棋历史，重建棋盘上的光标高亮
     * 确保只显示实际走过的棋的起点和终点，不显示任何"建议"或"假设"的走法
     */
    private fun restoreHighlightsFromHistory() {
        // ★ 同步浏览历史状态到棋盘视图（仅用于UI显示，不再阻止走棋）
        chessBoard.viewingHistory = chessGame.isViewingHistory()
        
        val recentMoves = chessGame.getRecentMovesForHighlight()
        if (recentMoves.isEmpty()) {
            chessBoard.clearHighlights()
            return
        }
        // 直接从UCI走法重建光标，不依赖棋盘状态
        chessBoard.rebuildHighlightsFromHistory(recentMoves)
    }

    // ── 旁白表：一坨一坨列出本局所有旁白（含支节标记）──

    /**
     * 弹出旁白表：把本局所有旁白按"出生顺序"一坨一坨列出来。
     * - 每条旁白独立成坨，不混杂、不拆分
     * - 支节（变着）里的旁白会标出「支节」
     * - 用户自行对照棋局，决定每条旁白对应的节点（自找氛围）
     * 弹窗为半透明暖色底，隐约透出底层棋盘。
     */
    private fun showCommentaryListDialog() {
        val c = chessGame.qipuComments ?: run {
            Toast.makeText(this, "本局暂无旁白，请先从宝库打开一局棋谱", Toast.LENGTH_SHORT).show()
            return
        }
        val blocks = c.commentaryBlocks
        if (blocks.isEmpty()) {
            Toast.makeText(this, "本局暂无旁白", Toast.LENGTH_SHORT).show()
            return
        }

        // 居中弹窗容器
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 10)
        }

        // 标题说明
        val tvTitle = TextView(this).apply {
            text = "本局旁白一览（按顺序，用户自找对应）："
            setTextColor(Color.parseColor("#E8D8A8"))
            textSize = 15f
            setPadding(8, 4, 8, 8)
        }
        container.addView(tvTitle)

        // 滚动列表
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var seq = 0
        for (b in blocks) {
            seq++
            // 每条旁白一坨
            val item = TextView(this).apply {
                val tag = if (b.isBranch) "〔支节〕" else ""
                val header = "◆ 旁白 $seq $tag"
                val body = b.text
                text = "$header\n$body"
                if (b.isBranch) {
                    // 支节旁白：文字偏青色以区分，内容仍完整
                    setTextColor(Color.parseColor("#A8D8C8"))
                } else {
                    setTextColor(Color.parseColor("#F0E8D0"))
                }
                textSize = 14f
                setLineSpacing(4f, 1.2f)
                setPadding(10, 8, 10, 8)
                setBackgroundColor(if (b.isBranch) 0x0E00AA00 else 0x0A000000)
            }
            list.addView(item)
            // 坨与坨之间的下划线：纯色半透明细线，色泽分明但不干扰观察棋盘
            val rule = View(this).apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * resources.displayMetrics.density).toInt()
                )
                lp.leftMargin = (6 * resources.displayMetrics.density).toInt()
                lp.rightMargin = (6 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setBackgroundColor(
                    if (b.isBranch) Color.parseColor("#66226644")
                    else Color.parseColor("#663A2C1E")
                )
            }
            list.addView(rule)
        }
        scroll.addView(list)
        container.addView(scroll)

        // 关闭按钮
        val btnClose = Button(this).apply {
            text = "关闭"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * resources.displayMetrics.density).toInt()
            )
        }
        container.addView(btnClose)

        val dialog = android.app.Dialog(this)
        dialog.setContentView(container)

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            dialog.window?.let { window ->
                // 更透明的暖褐底衬，透出底层棋盘
                window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#7A2A2218")))
                val dm = resources.displayMetrics
                window.setLayout((dm.widthPixels * 0.9).toInt(), (dm.heightPixels * 0.70).toInt())
            }
        }
        // 点击外部关闭
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    // ── 棋谱旁白显示 ──

    /**
     * 根据当前步数显示对应的棋谱旁白。
     * - 第0步（起始局面）：显示开局旁白
     * - 中间步：显示该步的旁白（如果有）
     * - 最后一步：显示结局旁白
     * 优先级：每步旁白 > 结局旁白（最后一步时同时有则合并）
     */
    private fun showCurrentQipuComment() {
        // ★ 这是「手势刷进」（上/下一回合、播放、载入新谱）的统一入口：先复位“已关”标记，
        //   让本次切换后的旁白能重新弹起（用户手动关掉后，未切换内容前不会再弹）。
        commentPopupDismissed = false
        commentPopupDismissedBody = ""
        refreshCommentaryBanner()
    }

    private var isRefreshingExplanation = false

    private fun refreshExplanationDisplay() {
        // ★ 防重入：如果正在刷新，跳过本次（防止异步回调并发导致渲染混乱）
        if (isRefreshingExplanation) {
            Log.d(TAG, "★ refreshExplanationDisplay 被跳过（正在刷新中）")
            return
        }
        isRefreshingExplanation = true

        // ★ 同步固定解说横幅：走一步说一句（人话/现世报），关则留提示
        refreshCommentaryBanner()

        val explanations = chessGame.getVisibleExplanations()
        layoutExplanationList.removeAllViews()

        // ★ 常驻开局注解横幅：识别到开局（如"E00 仙人指路"）就在棋谱/解说区顶部固定显示，
        //   不再只闪一瞬 Toast，让开局注解始终可见、可发现。
        //   ★ 增厚注释：除开局名外，再附一行"战略要点"，把这一步的战术意图讲清楚，
        //     对齐路径参考的节点象棋/象棋笔记风格，而不是只有名字。
        val openingName = chessGame.getCurrentOpeningName()
        val openingStrategy = chessGame.getOpeningStrategy()
        if (openingName != null || openingStrategy != null) {
            val banner = TextView(this)
            val titleTxt = if (openingName != null) "🏁 $openingName" else "🏁 当前开局"
            val hintTxt = openingStrategy?.let { "\n${it}" } ?: ""
            banner.text = titleTxt + hintTxt
            banner.textSize = 15f
            banner.setTypeface(banner.typeface, android.graphics.Typeface.BOLD)
            banner.setTextColor(0xFF9A6B00.toInt())
            banner.setBackgroundColor(0x22FFB300.toInt())
            banner.setPadding(8, 6, 8, 6)
            banner.setLineSpacing(1f, 1f)
            layoutExplanationList.addView(banner)
        }

        if (explanations.isEmpty()) {
            layoutExplanationList.addView(tvExplanation)
            tvExplanation.text = "走棋后这里会显示棋谱…"
            isRefreshingExplanation = false
            return
        }

        // ★ 纯棋谱模式：按回合分组显示，一回合一行
        // 第1步  红方：炮二平五    黑方：马8进7
        // 第2步  红方：马二进三    黑方：车9平4
        val stepMap = LinkedHashMap<Int, Pair<ExplanationEntry?, ExplanationEntry?>>()
        for (entry in explanations) {
            val step = entry.stepNumber
            val pair = stepMap.getOrPut(step) { Pair(null, null) }
            if (entry.color == "红方") {
                stepMap[step] = Pair(entry, pair.second)
            } else {
                stepMap[step] = Pair(pair.first, entry)
            }
        }

        for ((stepNum, pair) in stepMap) {
            val (redEntry, blackEntry) = pair
            val rowView = TextView(this)
            val sb = StringBuilder()
            sb.append("第${stepNum}步  ")

            // ★ 兜底重建：若某方条目缺失，但从走走史能重建，就显示真实棋谱，
            //   不再误显示"（无）"（例如士五退六这类走法在AI对弈中偶发丢失）。
            var redNotation: String? = redEntry?.moveNotation
            if (redNotation == null) {
                redNotation = chessGame.getMoveNotationByStep(stepNum * 2 - 1)
            }
            val redIcon = if (redEntry?.isHuman == true) "🧑" else "🤖"
            if (redNotation != null) {
                sb.append("红方：$redIcon$redNotation")
            } else {
                sb.append("红方：（无）")
            }

            sb.append("    ")

            var blackNotation: String? = blackEntry?.moveNotation
            if (blackNotation == null) {
                blackNotation = chessGame.getMoveNotationByStep(stepNum * 2)
            }
            if (blackNotation != null) {
                val blackIcon = if (blackEntry?.isHuman == true) "🧑" else "🤖"
                sb.append("黑方：$blackIcon$blackNotation")
            } else {
                // ★ 只在最新步显示"待走"，历史步显示"（无）"
                val isLatestStep = (redEntry?.totalStepIndex == chessGame.currentStep)
                if (isLatestStep) {
                    sb.append("黑方：待走")
                } else {
                    sb.append("黑方：（无）")
                }
            }

            rowView.text = sb.toString()
            rowView.textSize = 14f
            rowView.setTextColor(0xFF333333.toInt())
            rowView.setPadding(8, 6, 8, 6)
            rowView.setLineSpacing(2f, 1f)

            // ★ 高亮当前步
            val currentRed = redEntry?.totalStepIndex == chessGame.currentStep
            val currentBlack = blackEntry?.totalStepIndex == chessGame.currentStep
            if (currentRed || currentBlack) {
                rowView.setBackgroundColor(0xFFE8F5E9.toInt())
                rowView.setTypeface(null, android.graphics.Typeface.BOLD)
            }

            layoutExplanationList.addView(rowView)
        }

        // ★ 状态行：只在最后显示"轮到XX走棋"
        if (!chessGame.gameOver && chessGame.isAtLatest) {
            val currentSide = chessGame.board.currentPlayer
            val currentSideName = if (currentSide == com.pikafish.chess.model.PieceColor.RED) "红方" else "黑方"
            val isAi = chessGame.isCurrentPlayerEngine()
            val statusText = if (isAi) "─── 轮到${currentSideName}（引擎思考中）───"
                             else "─── 轮到${currentSideName}走棋 ───"
            val statusView = TextView(this)
            statusView.text = statusText
            statusView.textSize = 12f
            statusView.setTextColor(0xFF666666.toInt())
            statusView.gravity = android.view.Gravity.CENTER
            statusView.setPadding(0, 2, 0, 4)
            statusView.setTypeface(null, android.graphics.Typeface.BOLD)
            layoutExplanationList.addView(statusView)
        }

        // 滚动到当前步位置
        scrollViewExplanation.post {
            scrollViewExplanation.fullScroll(View.FOCUS_DOWN)
        }

        // ★ 盘内浮层：只显示棋谱，不显示人话
        if (overlayCommentaryMode == 1 || overlayCommentaryMode == 2) {
            chessBoard.clearOverlayLines()
            val visible = chessGame.getVisibleExplanations()
            val last3 = visible.takeLast(3)
            for (entry in last3) {
                val icon = if (entry.isHuman) "🧑" else "🤖"
                chessBoard.appendOverlayLine("${icon}${entry.color} ${entry.moveNotation}")
            }
        }

        isRefreshingExplanation = false
    }

    /**
     * ★ 同步解说弹窗：人话/现世报的解说改用在棋盘上透明悬浮弹窗展示（像『分析浮透』）——
     *   关：隐藏弹窗；开着：把最新那句人话/现世报浮出来，透棋读字、不挡走子手势。
     */
    private fun refreshCommentaryBanner() {
        // ★★ 统一弹窗内容 =「棋谱旁白」（开局/每步/结局） + 「解说」（人话/现世报）。
        //   原来旁白走 Toast（一闪而过、点不着、也不进旁白表），解说走悬浮气泡（挡屏），
        //   现合二为一进同一个可读、可关、可分页的旁白弹窗。
        val parts = ArrayList<String>(4)

        // ① 棋谱旁白：开局/当前步/结局（与旁白开关联动，关闭则不出）
        chessGame.getIntroComment()?.takeIf { it.isNotBlank() }?.let { parts.add("【开局】$it") }
        chessGame.getCurrentMoveComment()?.takeIf { it.isNotBlank() }?.let {
            parts.add("【第${chessGame.currentStep}步】$it")
        }
        chessGame.getEndComment()?.takeIf { it.isNotBlank() }?.let { parts.add("【结局】$it") }

        // ② 引擎解说（人话/现世报）：永不"沉默"，当前步没说过就回落到最近一句已讲的
        if (chessGame.commentaryStyle != ChessGame.CommentaryStyle.OFF) {
            val all = chessGame.getVisibleExplanations().asReversed()
            val current = chessGame.currentStep
            val text = all.firstOrNull { it.totalStepIndex == current && it.text.isNotBlank() }?.text
                ?: all.firstOrNull { it.text.isNotBlank() }?.text
            if (!text.isNullOrBlank()) parts.add(text)
        }

        val body = parts.joinToString("\n\n").trim()
        if (body.isEmpty()) {
            hideCommentPopup()
            return
        }
        val title = when {
            parts.size > 1 -> "旁白 · 解说"
            chessGame.getCurrentMoveComment() != null || chessGame.getIntroComment() != null ||
                chessGame.getEndComment() != null -> "本局旁白"
            else -> "解说"
        }
        showCommentPopup(title, body)
    }

    // ═══════════════ 统一「旁白/说明弹窗」 ═══════════════

    /** 构建旁白弹窗：置顶加宽、右上角✕、长文分页、标题条可拖动。挂在棋盘上层，不遮住操作按钮。 */
    private fun setupCommentPopup() {
        if (commentPopup != null) return
        if (!::frameBoardOuter.isInitialized) return

        val pop = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_float_overlay)
            visibility = View.GONE
            alpha = 0.97f
            isClickable = true          // 自身可点：点弹窗内部不穿透到棋盘
            elevation = 14f
            setPadding(0, 0, 0, 0)
        }

        // ── 标题条（拖动把手）：标题 + 右上角 ✕ ──
        val titleRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "本局旁白"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFE0A0.toInt())
            setPadding(dp(14), dp(8), dp(6), dp(8))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFFFFE0A0.toInt())
            setPadding(dp(14), dp(2), dp(14), dp(2))
            isClickable = true
            setOnClickListener { dismissCommentPopup() }
        }
        titleRow.addView(title)
        titleRow.addView(close)
        pop.addView(titleRow)

        // ── 正文（宽度拉满，故高度自然矮；超限时可滚动兜底）──
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        val content = TextView(this).apply {
            textSize = commentFontSp.coerceIn(8f, 32f)
            setTextColor(0xFFF6E8D8.toInt())
            setLineSpacing(dp(3).toFloat(), 1.15f)
            setPadding(dp(14), dp(2), dp(14), dp(10))
        }
        scroll.addView(
            content,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        pop.addView(scroll)

        // ── 页码行：‹上一页  第 x/y 页  下一页›（只有一页时整个隐藏）──
        val pager = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val prev = TextView(this).apply {
            text = "‹ 上一页"
            textSize = 13f
            setTextColor(0xFFFFE0A0.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(8))
            isClickable = true
            setOnClickListener { showCommentPage(commentPopupPageIndex - 1) }
        }
        val pageLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFD8C8A8.toInt())
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val next = TextView(this).apply {
            text = "下一页 ›"
            textSize = 13f
            setTextColor(0xFFFFE0A0.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(8))
            isClickable = true
            setOnClickListener { showCommentPage(commentPopupPageIndex + 1) }
        }
        pager.addView(prev)
        pager.addView(pageLabel)
        pager.addView(next)
        pop.addView(pager)

        // ── 位置：置顶、占屏宽 94%（加宽→变矮，少挡棋盘），标题条可拖动微调 ──
        val dm = resources.displayMetrics
        pop.layoutParams = android.widget.FrameLayout.LayoutParams(
            (dm.widthPixels * 0.94f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = dp(46)
        }
        frameBoardOuter.addView(pop)
        // ★ 拖标题条 = 拖着整窗自由挪（跟拖「浮透」一个手感）：嫌挡屏就随手拖开，不挡就放着
        hookDragHandle(titleRow, pop)

        commentPopup = pop
        commentPopupTitle = title
        commentPopupContent = content
        commentPopupScroll = scroll
        commentPopupPager = pager
        commentPopupPageLabel = pageLabel
    }

    /** 显示旁白弹窗（内容变化会重新弹出；被手动关掉且内容未变则不再打扰）。 */
    private fun showCommentPopup(titleText: String, text: String) {
        setupCommentPopup()
        val pop = commentPopup ?: return
        if (titleText.isBlank() && text.isBlank()) { hideCommentPopup(); return }
        // ★ 用户刚手动关掉、而这一版正文又没变：不再自动弹起（避免“关掉又立刻弹”的纠缠）。
        //   切上/下一回合走 showCurrentQipuComment，会先复位该标记，于是照常弹出新内容。
        if (commentPopupDismissed && text == commentPopupDismissedBody) {
            commentPopupBody = text
            return
        }
        commentPopupDismissed = false
        commentPopupDismissedBody = ""
        commentPopupBody = text
        commentPopupTitle.text = titleText
        commentPopupContent.textSize = commentFontSp.coerceIn(8f, 32f)

        val pages = paginateComment(text)
        commentPopupPages = pages
        commentPopupPageIndex = 0
        if (pages.size <= 1) {
            commentPopupPager.visibility = View.GONE
            commentPopupContent.text = text
        } else {
            commentPopupPager.visibility = View.VISIBLE
            commentPopupContent.text = pages[0]
            commentPopupPageLabel.text = "第 1/${pages.size} 页"
        }
        pop.visibility = View.VISIBLE
        // 高度封顶：最多占屏高 42%，超出走滚动（保证永远是「宽而矮」的一条，不糊满屏幕）
        pop.post { capCommentPopupHeight() }
    }

    /** 翻到第 idx 页（0 基；越界自动夹持）。 */
    private fun showCommentPage(idx: Int) {
        if (commentPopupPages.isEmpty()) return
        val i = idx.coerceIn(0, commentPopupPages.size - 1)
        commentPopupPageIndex = i
        commentPopupContent.text = commentPopupPages[i]
        commentPopupPageLabel.text = "第 ${i + 1}/${commentPopupPages.size} 页"
        commentPopupScroll.scrollTo(0, 0)
        commentPopup?.post { capCommentPopupHeight() }
    }

    /** 把正文高度夹到屏高 42% 以内（多余交给滚动）。 */
    private fun capCommentPopupHeight() {
        val pop = commentPopup ?: return
        val dm = resources.displayMetrics
        val maxContentH = (dm.heightPixels * 0.42f).toInt()
        val titleH = commentPopupTitle.height
        val pagerH = if (commentPopupPager.visibility == View.VISIBLE) commentPopupPager.height else 0
        val cap = (maxContentH - titleH - pagerH - dp(6)).coerceAtLeast(dp(60))
        val lp = commentPopupScroll.layoutParams
        lp.height = if (commentPopupContent.height > cap) cap
                    else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        commentPopupScroll.layoutParams = lp
    }

    /** 手动关闭（✕ / 点窗外）：记住“已关 + 关掉的是哪一版”，内容未变不再自动弹起。 */
    private fun dismissCommentPopup() {
        commentPopup?.visibility = View.GONE
        commentPopupDismissed = true
        commentPopupDismissedBody = commentPopupBody
    }

    /** 无声隐藏（内容为空时用，不复位也不置位“已关”）。 */
    private fun hideCommentPopup() {
        commentPopup?.visibility = View.GONE
    }

    /**
     * 长文分页：按弹窗实际宽度（CJK 每字约一字宽）与屏高上限，估出每页可容字数，
     * 并在换行/句读处断开，避免拦腰截断句子。只有一页时返回单元素表。
     */
    private fun paginateComment(text: String): List<String> {
        val dm = resources.displayMetrics
        val contentW = (dm.widthPixels * 0.94f).toInt() - dp(28)
        val maxH = (dm.heightPixels * 0.42f).toInt() - dp(52)
        val sizePx = commentFontSp.coerceIn(8f, 32f) * resources.displayMetrics.scaledDensity
        val perLine = (contentW / sizePx.coerceAtLeast(1f)).toInt().coerceAtLeast(6)
        val lines = (maxH / (sizePx * 1.35f)).toInt().coerceAtLeast(3)
        val perPage = (perLine * lines).coerceAtLeast(40)
        if (text.length <= perPage) return listOf(text)

        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + perPage).coerceAtMost(text.length)
            if (end < text.length) {
                // 就近找断点：优先换行，其次句读，再次空格
                var cut = -1
                for (k in end downTo (start + perPage / 2)) {
                    val ch = text[k - 1]
                    if (ch == '\n') { cut = k; break }
                }
                if (cut < 0) for (k in end downTo (start + perPage / 2)) {
                    val ch = text[k - 1]
                    if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '.' || ch == '!' || ch == '?') {
                        cut = k; break
                    }
                }
                if (cut < 0) for (k in end downTo (start + perPage / 2)) {
                    if (text[k - 1].isWhitespace()) { cut = k; break }
                }
                if (cut > start) end = cut
            }
            out.add(text.substring(start, end).trim())
            start = end
        }
        return out.ifEmpty { listOf(text) }
    }

    /** 触摸落在某视图范围内？（原始坐标判定，用于“点窗外即关”）。 */
    private fun isTouchInsideView(v: android.view.View, ev: android.view.MotionEvent): Boolean {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val x = ev.rawX
        val y = ev.rawY
        return x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height
    }

    /**
     * ★ 点弹窗外任意处即关：在 Activity 分发触摸【之前】判定，若落在弹窗外就先关掉，
     *   但【不消费】事件——于是那一下照常落到背后的按钮上（如「下一步」）。
     *   这样「关掉弹窗」与「按了那个按钮」一次动作同时完成，不必先点✕。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val pop = commentPopup
        if (pop != null && pop.visibility == View.VISIBLE &&
            ev.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
            !isTouchInsideView(pop, ev)) {
            dismissCommentPopup()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ── 步数信息更新 ──

    /**
     * ★ 恢复键增强：清空应用级缓存 + 主动收一次内存垃圾。
     * 在后台线程执行，避免阻塞主线程；只清 transient 缓存(图片/临时/网络缓存)，
     * 不动棋谱、开局库等持久数据。模仿用户手动"清除数据"的手感，让长时间对局后的卡顿恢复流畅。
     */
    private fun clearAppCaches() {
        Thread {
            try {
                try { deleteTree(cacheDir) } catch (_: Exception) {}
                try { deleteTree(codeCacheDir) } catch (_: Exception) {}
                try { System.gc() } catch (_: Exception) {}
                Log.d(TAG, "★★ 恢复键：应用缓存已清理")
            } catch (e: Exception) {
                Log.e(TAG, "清缓存失败: ${e.message}", e)
            }
        }.apply { name = "cache-cleaner" }.start()
    }

    private fun deleteTree(f: java.io.File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }

    /**
     * ★ 每走一手后的轻量自动清理：只做"异步、节流"的垃圾回收。
     * AI对弈时间越长，引擎评估/快照/解释器产生的大量可达而可回收的 JVM 对象越积越多，
     * 造成界面缓慢。这里每满 N 手在后台线程主动触发一次 GC，把"与当前回合不相干的堆积"
     * 及时还掉；因为只是软清理、不影响任何状态与走法，绝无副作用。
     */
    private var aiMoveCleanCounter = 0
    private fun autoCleanupAfterMove() {
        if ((++aiMoveCleanCounter) % 8 != 0) return
        Thread {
            try { System.gc() } catch (_: Exception) {}
        }.apply { name = "move-gc" }.start()
    }

    private fun updateStepInfo() {
        // ★ 如果当前步有变着，显示"变"标记提示用户可点击切换
        val variations = chessGame.getVariationsAtCurrentStep()
        val varMark = if (variations.isNotEmpty()) " ★${variations.size}" else ""
        // ★ 回合计法：初始局面=0回合；第一个走子=第1回合（红+黑各一手合成一行）。
        //   原先按"半回合(子)数"显示(如红1黑1后显示 2/…) 会比对局列表"第1回合"多一回合，
        //   造成"快速播放错位一回合"的观感，这里统一改成回合数。
        val ply = chessGame.currentStep          // 已走手数(半回合数)
        val totalPly = chessGame.totalSteps
        val round: Int = (ply + 1) / 2           // ceil(ply/2)：红1/黑1都属第1回合
        val totalRounds: Int = (totalPly + 1) / 2
        // ★ 局面习题：没有回合可数，直接标“习题 · 红先/黑先”，别显示 0/0 让人以为坏了。
        tvStepInfo.text = if (chessGame.fenPuzzleMode && totalPly == 0) {
            "习题 · ${if (chessGame.board.currentPlayer == PieceColor.BLACK) "黑先" else "红先"}$varMark"
        } else {
            "$round/$totalRounds 回合$varMark"
        }
        // 更新导航按钮状态
        btnGoBack.isEnabled = !chessGame.isAtStart
        btnGoStart.isEnabled = !chessGame.isAtStart
        btnGoForward.isEnabled = !chessGame.isAtLatest
        btnGoEnd.isEnabled = !chessGame.isAtLatest
    }

    /**
     * ★「本步操作」小菜单：点步数（几比几回合）弹出——
     *   ① 注释：整行宽编辑框（限 200 字）+「保存注释」，保存后气泡「注释已保存成功」；
     *   ② 删除招法：删除本步及其之后的全部招法（二次确认）；
     *   ③ 本步若有变着：切换变着（保留原行为）。
     *   增招无需入口：自己随手走一着即增；
     *   删/注都只改内存，原棋谱文件只读、不受影响，点「新局→保存」时属另存。
     *
     * ★ 面板可自由摆放：底排四等分按钮「移动 / 白底 / 透明 / 关闭」——
     *   · 移动：开启后按住标题栏即可把整个面板拖到任意位置，避免挡住棋盘；
     *   · 白底 / 透明：互斥切换「底幕」（衬托菜单栏与字体的那层底色）。
     *     白底=不透明，看得最清；透明=半透明，能一边看棋盘一边写注释。
     *   · 关闭：收起面板。底排这四个按钮本身始终不透明，保证找得到。
     */
    private fun showStepMenuDialog() {
        val ply = chessGame.currentStep
        if (ply <= 0) {
            Toast.makeText(this, "当前在开局，先走到某一步再写注释 / 删招", Toast.LENGTH_SHORT).show()
            return
        }
        val round = (ply + 1) / 2
        val remain = chessGame.totalSteps - ply + 1
        val variations = chessGame.getVariationsAtCurrentStep()

        // ★ 用普通 Dialog（而非 AlertDialog），才能自由改「底幕」（白底/透明）与整体拖动。
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)

        // ★ 持久化：底幕（白底/透明）与弹窗位置都按用户上次的选择恢复，
        //   不再每次弹回系统默认位置——用户拖到哪儿、选透明还是白底，下回照办。
        var transparentMode = prefs.getBoolean("step_menu_transparent", false)   // false=白底；true=透明（互斥）
        var moveMode = false          // true=按住标题可拖动

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        val title = TextView(this).apply {
            text = "第 $round 回合 · 第 $ply 着"
            setTextColor(0xFF9A6B00.toInt()); textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }
        box.addView(title)
        box.addView(TextView(this).apply {
            text = "注释（最多 200 字）："
            setTextColor(0xFF3A2C1E.toInt()); textSize = 13f
        })
        val edit = EditText(this).apply {
            setText(chessGame.getEditableMoveComment())
            hint = "写下这一步的评注…"
            textSize = 14f
            setTextColor(0xFF2B2015.toInt())
            setHintTextColor(0xFF8C7A63.toInt())
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 5
            maxLines = 12
            setPadding(dp(8), dp(6), dp(8), dp(6))
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xE6FFFFFF.toInt())
                setStroke(dp(1), 0xFFD8C7A0.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(edit)
        // ★★ 注释「自动保存（毫秒级）」：写多少存多少 —— 每敲一个字先改内存（当场生效、误关不丢），
        //    再节流落盘；弹窗被误关/被别的操作挤掉时立刻 flush，开开关关手机也照样还在。
        val autoSaveHandler = Handler(Looper.getMainLooper())
        val autoSaveRunnable = Runnable { saveGameState() }
        fun flushComment() {
            chessGame.setEditableMoveComment(edit.text.toString())
            refreshCommentaryBanner()
            saveGameState()
        }
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // ① 内存即时生效：此刻哪怕窗口被误关，这一步的注释也已经在了
                chessGame.setEditableMoveComment(s?.toString() ?: "")
                // ② 落盘节流：停笔后再整体存一次，避免每敲一字都做全量序列化而卡手
                autoSaveHandler.removeCallbacks(autoSaveRunnable)
                autoSaveHandler.postDelayed(autoSaveRunnable, 350)
            }
        })
        val commentBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(6) }
        }
        commentBtnRow.addView(Button(this).apply {
            text = "保存注释"; textSize = 14f
            setTextColor(0xFFF0E6D2.toInt()); setBackgroundColor(0xFF2B2015.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginEnd = dp(3) }
            setOnClickListener {
                flushComment()
                Toast.makeText(this@MainActivity, "注释已保存成功", Toast.LENGTH_SHORT).show()
            }
        })
        // ★ 删除注释：把本步注释清空（原先写得不满意可撤掉）；原稿只读，不受影响。
        commentBtnRow.addView(Button(this).apply {
            text = "删除注释"; textSize = 14f
            setTextColor(0xFFFFE0A0.toInt()); setBackgroundColor(0xFF5A4636.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginStart = dp(3) }
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("删除注释")
                    .setMessage("将清除第 $round 回合这一着已写的注释。原棋谱文件只读、不受影响。确定删除？")
                    .setPositiveButton("确认删除") { _, _ ->
                        edit.setText("")
                        chessGame.setEditableMoveComment("")
                        refreshCommentaryBanner()
                        saveGameState()
                        Toast.makeText(this@MainActivity, "注释已删除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        box.addView(commentBtnRow)
        box.addView(Button(this).apply {
            text = "删除招法（本步及之后全部删除）"; textSize = 14f
            setTextColor(0xFFFFE0A0.toInt()); setBackgroundColor(0xFF7A2E22.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("删除招法")
                    .setMessage("将删除第 $round 回合这一着，及其之后的全部招法（共 $remain 着）。\n\n原棋谱文件只读、不受影响；之后点「新局→保存」即属另存。确定删除？")
                    .setPositiveButton("确认删除") { _, _ ->
                        val n = chessGame.deleteCurrentMoveAndFollowing()
                        restoreHighlightsFromHistory()
                        refreshExplanationDisplay()
                        updateStepInfo()
                        updateStatus()
                        refreshCommentaryBanner()
                        saveGameState()
                        Toast.makeText(this@MainActivity, "已删除 $n 着", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        if (variations.isNotEmpty()) {
            box.addView(Button(this).apply {
                text = "切换变着（本步共 ${variations.size} 个）"; textSize = 14f
                setTextColor(0xFFF0E6D2.toInt()); setBackgroundColor(0xFF2B4A3A.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                ).apply { topMargin = dp(10) }
                setOnClickListener {
                    dialog.dismiss()
                    showVariationDialog(variations)
                }
            })
        }

        // ★ 底排：移动 / 白底 / 透明 / 关闭 —— 四等分
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
            ).apply { topMargin = dp(12) }
        }
        fun barButton(label: String): Button = Button(this).apply {
            text = label; textSize = 14f
            setTextColor(0xFFF0E6D2.toInt())
            setBackgroundColor(0xFF2B2015.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginStart = dp(3); marginEnd = dp(3) }
        }
        val btnMove = barButton("移动")
        val btnWhite = barButton("白底")
        val btnClear = barButton("透明")
        val btnClose = barButton("关闭")
        bar.addView(btnMove); bar.addView(btnWhite); bar.addView(btnClear); bar.addView(btnClose)
        box.addView(bar)

        // ★「底幕」：衬托菜单栏与字体的那层底色。白底=不透明；透明=半透明（能看到棋盘）。
        fun applyBackdrop() {
            val gd = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (transparentMode) 0xB3FFFFFF.toInt() else 0xFFFFFFFF.toInt())
                if (!transparentMode) setStroke(dp(1), 0xFFD8C7A0.toInt())
            }
            box.background = gd
        }
        fun refreshBar() {
            // 白底 / 透明 互斥高亮；四个按钮本身始终不透明
            btnMove.setBackgroundColor(if (moveMode) 0xFF9A6B00.toInt() else 0xFF2B2015.toInt())
            btnWhite.setBackgroundColor(if (!transparentMode) 0xFF9A6B00.toInt() else 0xFF2B2015.toInt())
            btnClear.setBackgroundColor(if (transparentMode) 0xFF9A6B00.toInt() else 0xFF2B2015.toInt())
            btnClose.setBackgroundColor(0xFF2B2015.toInt())
            title.text = "第 $round 回合 · 第 $ply 着" +
                if (moveMode) "   ⇕ 按住此标题拖动" else ""
        }
        applyBackdrop()
        refreshBar()

        btnMove.setOnClickListener {
            moveMode = !moveMode
            refreshBar()
            Toast.makeText(this, if (moveMode) "已开启移动：按住标题栏即可拖动" else "已关闭移动", Toast.LENGTH_SHORT).show()
        }
        btnWhite.setOnClickListener {
            transparentMode = false
            prefs.edit().putBoolean("step_menu_transparent", false).apply()
            applyBackdrop(); refreshBar()
            Toast.makeText(this, "底幕：白底", Toast.LENGTH_SHORT).show()
        }
        btnClear.setOnClickListener {
            transparentMode = true
            prefs.edit().putBoolean("step_menu_transparent", true).apply()
            applyBackdrop(); refreshBar()
            Toast.makeText(this, "底幕：透明（可边看棋盘边写注释）", Toast.LENGTH_SHORT).show()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(box)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val width = minOf(screenW - dp(24), dp(430))
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            w.setDimAmount(0f)
            val p = w.attributes
            p.width = width
            p.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            p.gravity = Gravity.TOP or Gravity.START
            // ★ 恢复用户上次拖到的点位；只有从未拖过才用默认（居中偏上）。
            val defX = (screenW - width) / 2
            val defY = (screenH * 0.16f).toInt()
            val savedX = prefs.getInt("step_menu_x", defX)
            val savedY = prefs.getInt("step_menu_y", defY)
            // 夹进当前屏幕内：换机/转屏后也不会弹到屏幕外找不回来。
            p.x = savedX.coerceIn(0, (screenW - width).coerceAtLeast(0))
            p.y = savedY.coerceIn(0, (screenH - dp(140)).coerceAtLeast(0))
            w.attributes = p
        }
        // ★ 弹窗关闭（不管点关闭、点外部、还是被别的操作挤掉）时立刻把注释 flush 落盘：
        //   用户随手写的东西，绝不能因为误碰关窗就没了。
        dialog.setOnDismissListener {
            autoSaveHandler.removeCallbacks(autoSaveRunnable)
            flushComment()
        }
        dialog.show()

        // ★ 拖动：仅在「移动」模式开启时，按住标题栏可整体拖拽
        dialog.window?.let { w ->
            val wlp = w.attributes
            var downX = 0f; var downY = 0f
            var startX = 0; var startY = 0
            title.setOnTouchListener { _, e ->
                if (!moveMode) return@setOnTouchListener false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = wlp.x; startY = wlp.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        wlp.x = startX + (e.rawX - downX).toInt()
                        wlp.y = startY + (e.rawY - downY).toInt()
                        w.attributes = wlp
                        true
                    }
                    // ★ 松手即把最终点位落盘：下回打开直接续用，无需再拖。
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        prefs.edit()
                            .putInt("step_menu_x", wlp.x)
                            .putInt("step_menu_y", wlp.y)
                            .apply()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /**
     * ★ 变着选择对话框：列出当前步的变着，选择后切换主线
     */
    private fun showVariationDialog(variations: List<String>) {
        val items = variations.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("选择变着（切换后原主线保存为变着）")
            .setItems(items) { _, which ->
                chessGame.switchToVariation(which)
                restoreHighlightsFromHistory()
                refreshExplanationDisplay()
                updateStepInfo()
                updateStatus()
                saveGameState()
                Toast.makeText(this, "已切换到${items[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 摆子面板 ──

    /**
     * ★ 摆子底座：固定高度，不可拖拽伸缩，棋盘不再联动缩放。
     *   仅按屏幕高度取一个舒适的固定底座高度（至少140dp）。
     */
    private fun setupSetupDockResize() {
        try {
            val dock = findViewById<LinearLayout>(R.id.layoutSetupPanel) ?: return
            val basePx = (resources.displayMetrics.heightPixels * 0.20f).toInt().coerceAtLeast(dp(140))
            val lp = dock.layoutParams
            if (lp is FrameLayout.LayoutParams) {
                lp.height = basePx
                dock.layoutParams = lp
            }
        } catch (_: Exception) {}
    }

    private fun setupPieceButtons() {
        val redPieces = listOf(
            btnRedKing to Pair(PieceType.KING, PieceColor.RED),
            btnRedAdvisor to Pair(PieceType.ADVISOR, PieceColor.RED),
            btnRedElephant to Pair(PieceType.ELEPHANT, PieceColor.RED),
            btnRedRook to Pair(PieceType.ROOK, PieceColor.RED),
            btnRedKnight to Pair(PieceType.KNIGHT, PieceColor.RED),
            btnRedCannon to Pair(PieceType.CANNON, PieceColor.RED),
            btnRedPawn to Pair(PieceType.PAWN, PieceColor.RED)
        )
        val blackPieces = listOf(
            btnBlackKing to Pair(PieceType.KING, PieceColor.BLACK),
            btnBlackAdvisor to Pair(PieceType.ADVISOR, PieceColor.BLACK),
            btnBlackElephant to Pair(PieceType.ELEPHANT, PieceColor.BLACK),
            btnBlackRook to Pair(PieceType.ROOK, PieceColor.BLACK),
            btnBlackKnight to Pair(PieceType.KNIGHT, PieceColor.BLACK),
            btnBlackCannon to Pair(PieceType.CANNON, PieceColor.BLACK),
            btnBlackPawn to Pair(PieceType.PAWN, PieceColor.BLACK)
        )

        for ((btn, pair) in redPieces + blackPieces) {
            btn.setOnClickListener {
                if (!chessGame.isSetupMode) return@setOnClickListener
                val (type, color) = pair
                if (selectedPieceType == type && selectedPieceColor == color) {
                    selectedPieceType = null
                    selectedPieceColor = null
                } else {
                    selectedPieceType = type
                    selectedPieceColor = color
                }
                updateSetupPieceSelection()
            }
        }
    }

    private fun setupSetupActionButtons() {
        btnSetupClear.setOnClickListener {
            chessGame.setupClearAll()
            selectedPieceType = null
            selectedPieceColor = null
            updateSetupPieceSelection()
        }

        btnSetupStandard.setOnClickListener {
            chessGame.setupFillStandard()
            selectedPieceType = null
            selectedPieceColor = null
            updateSetupPieceSelection()
        }

        btnSetupFirst.setOnClickListener {
            if (!chessGame.isSetupMode) return@setOnClickListener
            chessGame.setupFirstPlayer = if (chessGame.setupFirstPlayer == PieceColor.RED) PieceColor.BLACK else PieceColor.RED
            btnSetupFirst.text = if (chessGame.setupFirstPlayer == PieceColor.RED) "红先" else "黑先"
            Toast.makeText(this, if (chessGame.setupFirstPlayer == PieceColor.RED) "红方先走" else "黑方先走", Toast.LENGTH_SHORT).show()
        }

        btnSetupDone.setOnClickListener {
            // ★ 辅助外壳：完成摆子确认弹窗（纯 UI 层，与识别/摆子数据隔离）
            val dialog = AlertDialog.Builder(this)
                .setTitle("完成摆子")
                .setMessage("摆子生成会影响当下对弈进度；未实质有效操作该界面，不影响对弈进度。\n\n确认按当前摆好的局面开始对弈吗？")
                .setPositiveButton("确认生成") { d, _ ->
                    d.dismiss()
                    val error = chessGame.finishSetup()
                    if (error != null) {
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("再想想", null)
                .create()
            dialog.setOnShowListener {
                dialog.window?.let { window ->
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#7A2A2218")))
                }
                try {
                    val titleId = resources.getIdentifier("alertTitle", "id", "android")
                    val titleView = dialog.findViewById<TextView>(titleId)
                    titleView?.setTextColor(Color.parseColor("#E8D8A8"))
                } catch (_: Exception) {}
            }
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
        }

        // ★ 拍照识别
        btnSetupCamera.setOnClickListener {
            if (!chessGame.isSetupMode) return@setOnClickListener
            checkCameraPermissionAndLaunch()
        }

        // ★ 上传图片识别
        btnSetupGallery.setOnClickListener {
            if (!chessGame.isSetupMode) return@setOnClickListener
            galleryLauncher.launch("image/*")
        }
    }

    /**
     * ★ 检查相机权限并启动拍照
     */
    private fun checkCameraPermissionAndLaunch() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.CAMERA),
                1001
            )
        }
    }

    /**
     * ★ 请求外部存储权限（用于保存棋谱到 /sdcard/HHXQ/）
     */
    private fun requestStoragePermission() {
        val perms = arrayOf(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        )
        val needsRequest = perms.any {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needsRequest) {
            requestPermissions(perms, 1003)
        }
    }

    /**
     * ★ 启动相机拍照
     */
    private fun launchCamera() {
        try {
            val photoFile = File.createTempFile(
                "chess_photo_",
                ".jpg",
                File(cacheDir, "photos").apply { mkdirs() }
            )
            cameraImageUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            cameraLauncher.launch(cameraImageUri!!)
        } catch (e: Exception) {
            Log.e(TAG, "启动相机失败", e)
            Toast.makeText(this, "启动相机失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * ★ 把对话框底色改为"薄纱透明、可透视"风格：
     * 用半透明圆角矩形替换整块白底，并降低背景压暗度，让棋盘/棋局能透过来——
     * 避免下棋时弹个不透明白卡把整盘棋遮住。返回原 dialog 便于链式使用。
     */
    private fun styleScrimDialog(d: android.app.Dialog?, fillColor: Int, radiusDp: Int = 16, dim: Float = 0.30f): android.app.Dialog? {
        if (d == null) return null
        try {
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(fillColor)
                cornerRadius = (radiusDp * resources.displayMetrics.density).toFloat()
            }
            d.window?.setBackgroundDrawable(gd)
            d.window?.setDimAmount(dim.coerceIn(0f, 1f))
        } catch (e: Exception) {
            Log.w(TAG, "styleScrimDialog: ${e.message}")
        }
        return d
    }

    /**
     * ★ 执行棋盘识别 — 优先使用 ONNX 深度学习模型，失败则回退到 OCR
     */
    private fun performRecognition(imageUri: Uri) {
        // 显示进度对话框（薄纱透明底，识别期间棋盘仍可透视，不整块遮死）
        recognitionProgressDialog = AlertDialog.Builder(this)
            .setTitle("识别中…")
            .setMessage("正在分析图片，请稍候\n（涉及多种逻辑综合分析图片，请耐心等待，最多 5 秒即可完成）")
            .setCancelable(false)
            .show()
        styleScrimDialog(recognitionProgressDialog, 0x99FFFFFF.toInt(), radiusDp = 18, dim = 0.25f)

        // 在后台线程执行识别
        MainScope().launch(Dispatchers.IO) {
            var resultAsLink: BoardRecognizer.RecognitionResult? = null
            try {
                // ★ 加载并缓存识别原图（用于右下角缩略图比对）
                val rawBmp = decodeImageUri(imageUri)
                setupThumbRawBitmap = rawBmp
                fillSetupCompareImage(rawBmp)

                // ★ 优先尝试 ONNX 深度学习识别
                var result = onnxRecognizer.recognize(imageUri)

                // ★ ONNX 失败或无结果，回退到 OCR 识别
                if (!result.success || result.pieces.isEmpty()) {
                    Log.w(TAG, "ONNX 识别失败，回退到 OCR: ${result.message}")
                    result = boardRecognizer.recognize(this@MainActivity, imageUri)
                }
                resultAsLink = result
            } catch (e: Exception) {
                Log.e(TAG, "识别线程异常: ${e.message}", e)
                resultAsLink = BoardRecognizer.RecognitionResult(
                    pieces = emptyList(),
                    message = "识别过程出错: ${e.message}",
                    success = false
                )
            }

            withContext(Dispatchers.Main) {
                // ★ 先安全关掉“识别中”对话框（try-catch 兜底，防止窗口异常导致卡屏）
                try {
                    recognitionProgressDialog?.dismiss()
                } catch (e: Exception) {
                    Log.w(TAG, "识别对话框下拉失败: ${e.message}")
                }
                recognitionProgressDialog = null

                // ★ 识别完成后，在黑色区域右下角显示原图缩略图（不介入其他逻辑）
                if (setupThumbRawBitmap != null && setupThumbFocused) {
                    showSetupThumb(setupThumbRawBitmap!!)
                }

                val result = resultAsLink
                if (result != null && result.success && result.pieces.isNotEmpty()) {
                    val setupMsg = chessGame.setupFromRecognizedBoard(result.pieces)
                    selectedPieceType = null
                    selectedPieceColor = null
                    updateSetupPieceSelection()
                    updateStatus()
                    Toast.makeText(
                        this@MainActivity,
                        "$setupMsg\n${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        result?.message ?: "识别失败，未获取到结果",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * ★ 从 Uri 解码图片（限制采样，避免大图 OOM；返回可绘制位图）
     */
    private fun decodeImageUri(uri: Uri): Bitmap? {
        return try {
            // 第一次：只读尺寸，计算采样率，避免相机大图 OOM
            var sample = 1
            contentResolver.openInputStream(uri)?.use { ins ->
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeStream(ins, null, this)
                    sample = computeInSampleSize(outWidth, outHeight, 2048)
                }
            }
            // 第二次：按采样率实际解码（限制到合适大小供比对，长边≤2048）
            contentResolver.openInputStream(uri)?.use { ins ->
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                // 拍照可能带 EXIF 旋转，这里不做旋转处理（识别用同一份 Uri，保持一致性）
                BitmapFactory.decodeStream(ins, null, opts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解码识别原图失败: ${e.message}", e)
            null
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, reqSize: Int): Int {
        var inSampleSize = 1
        var w = width
        var h = height
        while (w / 2 >= reqSize && h / 2 >= reqSize) {
            w /= 2; h /= 2; inSampleSize *= 2
        }
        return inSampleSize
    }

    /**
     * ★ 在自由摆子下方黑色区域的右下角显示原图缩略图（小图，不介入其他逻辑）
     *   识别完成后调用：默认保持收起态，用户可上划放大比对。
     */
    private fun showSetupThumb(raw: Bitmap) {
        if (!setupThumbFocused) return
        // ★ 无条件先把右下角小图显示出来（保证一定可见），再把黑区复位为默认 25% 权重布局
        setupThumbExpanded = false
        setSetupThumbSmall()                     // 显示 60dp 右下角小图
        restoreSetupThumbLayout()                // 黑区按原权重 0.25 复位，不残留固定高度
        // 布局完成后记一次收起基准高度（用于上划挤压计算）
        ensureSetupThumbMetrics()
    }

    /**
     * ★ 把思考/解说区贴纸(缩略图容器)复位为摆子模式收起态（小高度条，贴住底栏），收缩键隐藏
     */
    private fun restoreSetupThumbLayout() {
        val lp = frameSetupThumb.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.height = dp(setupThumbStartDp)
        frameSetupThumb.layoutParams = lp
        btnSetupThumbShrink.visibility = View.GONE
        frameSetupThumb.requestLayout()
    }

    /**
     * ★ 初始化缩略图区域的高度基准与上限（半屏）
     *   基准 = 黑区当前实际高度；首次若尚未布局拿到 0，则在布局后 post 再取并缓存。
     */
    private fun ensureSetupThumbMetrics() {
        if (setupThumbBaseHeightPx < 0f) {
            if (frameSetupThumb.height > 0) {
                setupThumbBaseHeightPx = frameSetupThumb.height.toFloat()
            } else {
                frameSetupThumb.post {
                    if (setupThumbBaseHeightPx < 0f && frameSetupThumb.height > 0) {
                        setupThumbBaseHeightPx = frameSetupThumb.height.toFloat()
                    }
                }
            }
        }
        if (setupThumbMaxHeightPx < 0f) {
            val content = window.decorView.findViewById<View>(android.R.id.content)
            val avail = (if (content != null) content.height else resources.displayMetrics.heightPixels).toFloat()
            // 上限：半屏
            setupThumbMaxHeightPx = avail * 0.5f
            if (setupThumbBaseHeightPx > 0f && setupThumbMaxHeightPx < setupThumbBaseHeightPx + dp2px(80f)) {
                setupThumbMaxHeightPx = setupThumbBaseHeightPx + dp2px(80f)
            }
        }
    }

    /**
     * ★ 设置缩略图区域高度（上划挤压主棋盘：棋子区黑区加高，主棋盘被顶上去）
     *   夹在基准（收起）与半屏（最大）之间；放大态切换全图/小图并控制收缩键显隐。
     */
    /** ★ 全屏贴纸方案：棋盘视图始终占满整屏（match_parent），无需在权重/固定高度间切换。
     *  摆子缩略图扩大只是盖住棋盘更多面积，不再压缩棋盘；保留此方法以兼容旧调用点。 */
    private fun forceBoardFlexibleForThumb() {
        // 全屏方案：无事可做
    }

    private fun applySetupThumbHeight(h: Float) {
        val validBase = setupThumbBaseHeightPx.coerceAtLeast(dp2px(60f).toFloat())
        val validMax = setupThumbMaxHeightPx.coerceAtLeast(validBase + dp2px(20f))
        val target = h.coerceIn(validBase, validMax)
        // ★ 只要黑区在扩大（超过收起基准），主棋盘一定回到权重模式，才能被顶上去按比例缩小
        if (target > setupThumbBaseHeightPx.coerceAtLeast(dp2px(60f).toFloat()) + 1f) {
            forceBoardFlexibleForThumb()
        }
        val lp = frameSetupThumb.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.height = target.toInt().coerceAtLeast(1)
            frameSetupThumb.layoutParams = lp
        }
        val fill = target > validBase + dp2px(8f)
        if (fill) {
            if (!setupThumbExpanded) { setupThumbExpanded = true; setSetupThumbFull() }
        } else {
            if (setupThumbExpanded) { setupThumbExpanded = false; setSetupThumbSmall() }
        }
        val showShrink = target > validBase + 1f
        if (btnSetupThumbShrink.visibility != if (showShrink) View.VISIBLE else View.GONE) {
            btnSetupThumbShrink.visibility = if (showShrink) View.VISIBLE else View.GONE
        }
        frameSetupThumb.requestLayout()
        // ★★ 铁板一块：黑区高度已变 → 摆子面板上移 + 棋盘顶排锚定缩小，立即联动
        syncSetupBundle()
    }

    /**
     * ★★ 摆子「铁板一块」捆绑（核心联动）：
     *   垂直链「棋盘底边 → 摆子面板 → 黑区(识别原图)顶边」三者严丝合缝——
     *   黑区上顶 → 面板同步上移（按键大小不变、只平移）→ 棋盘以「最上一排」为锚向下等比缩小。
     *   棋盘底边（含底部刻度标签）永远停在面板顶上沿之上，绝不重叠、绝不留大片空白。
     */
    private fun syncSetupBundle() {
        try {
            if (!setupThumbFocused) return
            if (!::frameSetupThumb.isInitialized || !::layoutSetupPanel.isInitialized || !::chessBoard.isInitialized) return
            val root = findViewById<View>(R.id.rootMain) ?: return
            val rootH = root.height
            if (rootH <= 0) return
            val lpTh = frameSetupThumb.layoutParams as? FrameLayout.LayoutParams ?: return
            val thumbH = if (lpTh.height > 0) lpTh.height.toFloat() else frameSetupThumb.height.toFloat()
            // 黑区顶边（rootMain 坐标系）= 根高 - 底边距 - 黑区高
            val blackTop = rootH - lpTh.bottomMargin - thumbH
            // 摆子面板：固定高度、按键不缩放，只上下平移；底边紧贴黑区顶边
            val panelH = layoutSetupPanel.layoutParams?.height?.takeIf { it > 0 }?.toFloat()
                ?: layoutSetupPanel.height.toFloat().takeIf { it > 0 }
                ?: dp(140).toFloat()
            val gapPx = dp2px(4f).toFloat()
            var panelTop = blackTop - panelH - gapPx
            // 面板顶不越过屏幕顶端（至少留 8dp 边距，保证按键可点）
            panelTop = panelTop.coerceAtLeast(dp2px(8f).toFloat())
            val lpP = layoutSetupPanel.layoutParams as? FrameLayout.LayoutParams
            if (lpP != null) {
                val newTop = panelTop.toInt()
                if (lpP.topMargin != newTop) {
                    lpP.topMargin = newTop
                    layoutSetupPanel.layoutParams = lpP
                }
            }
            // 棋盘：底边（含底部刻度标签）不得超过「面板顶 - 间隙」→ 顶排锚定等比缩小
            chessBoard.setBoardBottomLimit(panelTop - gapPx)
        } catch (_: Exception) { }
    }

    /**
     * ★ 放大态：图片铺满整个黑区（自适应显示全部已识别棋子，方便比对）
     */
    private fun setSetupThumbFull() {
        val raw = setupThumbRawBitmap ?: return
        imgSetupThumb.scaleType = ImageView.ScaleType.FIT_CENTER
        imgSetupThumb.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = android.view.Gravity.CENTER }
        imgSetupThumb.setImageBitmap(raw)
        imgSetupThumb.visibility = View.VISIBLE
        // 放大态：显示“圈定”入口，并告知覆盖层原图宽高比（用于 FIT_CENTER 定位）
        btnSetupCrop.visibility = View.VISIBLE
        setupCropOverlay.imageAspectRatio = if (raw.height > 0) raw.width.toFloat() / raw.height else 0f
    }

    /**
     * ★ 收起态：右下角小图
     */
    private fun setSetupThumbSmall() {
        // 收起：退出圈定模式，隐藏圈定/识别提交控件
        exitSetupCropMode()
        btnSetupCrop.visibility = View.GONE
        btnSetupResubmit.visibility = View.GONE
        val raw = setupThumbRawBitmap ?: return
        imgSetupThumb.scaleType = ImageView.ScaleType.CENTER_CROP
        imgSetupThumb.layoutParams = FrameLayout.LayoutParams(
            dp2px(60f), dp2px(60f)
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            setMargins(dp2px(6f), 0, dp2px(6f), dp2px(6f))
        }
        val cropped = scaleAndCropToSquare(raw, 120, 120)
        setupThumbBitmap?.let { if (it !== cropped && !it.isRecycled) it.recycle() }
        setupThumbBitmap = cropped
        imgSetupThumb.setImageBitmap(cropped)
        imgSetupThumb.visibility = View.VISIBLE
    }

    /**
     * ★ 辅助外壳：把识别/上传的原始原图同步到面板中间"原图对比"区，
     *   拍照/上传后原图始终保留在这里，供与生成的棋盘逐点比对（不与识别数据耦合）。
     */
    private fun fillSetupCompareImage(bmp: android.graphics.Bitmap?) {
        // 原图对比区已移除（原图自带拉伸预览），此处不再展示任何内容，保留入口避免影响调用方。
    }

    /**
     * ★ 进入/退出圈定工具面板
     */
    private fun toggleSetupCropMode() {
        if (!setupThumbExpanded || setupThumbRawBitmap == null) return
        if (setupCropModeOn) {
            setupCropModeOn = false
            setupCropOverlay.visibility = View.GONE
            layoutSetupTools.visibility = View.GONE
            btnSetupCrop.text = "✏️工具"
        } else {
            setupCropModeOn = true
            setupCropOverlay.clearAll()
            setupCropOverlay.crownEnabled = true
            setupCropOverlay.visibility = View.VISIBLE
            layoutSetupTools.visibility = View.VISIBLE
            btnSetupCrop.text = "✏️完成"
            updateCropToolButtons()
        }
    }

    /**
     * ★ 退出圈定面板（清空、隐藏覆盖层）
     */
    private fun exitSetupCropMode() {
        setupCropModeOn = false
        setupCropOverlay.clearAll()
        setupCropOverlay.visibility = View.GONE
        layoutSetupTools.visibility = View.GONE
        btnSetupCrop.text = "✏️工具"
    }

    /**
     * ★ 刷新工具条按钮状态（当前模式高亮、提交可见性）
     */
    private fun updateCropToolButtons() {
        if (!::btnCropCrown.isInitialized) return
        btnCropCrown.text = if (setupCropOverlay.crownEnabled) "🔺圈点●" else "🔺圈点"
        // 圈定≥4点 → 允许提交
        val crownReady = setupCropOverlay.crownEnabled && setupCropOverlay.crownCount >= 4
        btnSetupResubmit.visibility = if (crownReady) View.VISIBLE else View.GONE
    }

    /**
     * ★ 识别提交：两步处理后再走正常识别
     *   第1步：掩膜剔除（把 圈定轮廓外部 + 刷子涂抹区域 涂黑，剔除外围碍事内容）
     *   第2步：若圈定构成四边形(≥4点) → 透视校正，把斜拍/梯形棋盘拉正扶直
     */
    private fun submitCroppedRecognition() {
        val raw = setupThumbRawBitmap ?: run { return }
        // 保证坐标映射正确：覆盖层需保持原图宽高比
        setupCropOverlay.imageAspectRatio = if (raw.height > 0) raw.width.toFloat() / raw.height else 0f
        // —— 第1步：掩膜剔除 ——
        val cleaned = setupCropOverlay.buildSourceBitmap(raw)
        // —— 第2步：透视校正（恢复“拉正”能力，四点圈定的核心价值） ——
        val quad = if (cleaned != null) setupCropOverlay.quadFourCorners() else null
        var processed = cleaned
        if (quad != null) {
            val warped = warpQuadToRect(if (cleaned != null) cleaned else raw, quad)
            if (warped != null) {
                if (cleaned != null && !cleaned.isRecycled) cleaned.recycle()
                processed = warped
            }
        }
        // 退出圈定态，避免覆盖层拦截后续识别界面
        exitSetupCropMode()
        val bmp = processed ?: raw
        val file = File(File(cacheDir, "photos").apply { mkdirs() }, "crop_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } catch (e: Exception) {
            Log.e(TAG, "保存裁剪图片失败: ${e.message}", e)
            if (processed != null && !processed.isRecycled) processed.recycle()
            Toast.makeText(this, "保存裁剪图片失败", Toast.LENGTH_SHORT).show()
            return
        }
        if (processed != null && !processed.isRecycled) processed.recycle()
        val cropUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        performRecognition(cropUri)
    }

    /**
     * ★ 将位图按 4 个归一化角点做透视校正为全幅矩形（梯形/平行四边形拉正扶直）
     *   pts 顺序：左上、右上、右下、左下（归一化坐标）
     */
    private fun warpQuadToRect(src: Bitmap, pts: List<PointF>): Bitmap? {
        return try {
            val sw = src.width.toFloat(); val sh = src.height.toFloat()
            val tl = PointF(pts[0].x * sw, pts[0].y * sh)
            val tr = PointF(pts[1].x * sw, pts[1].y * sh)
            val br = PointF(pts[2].x * sw, pts[2].y * sh)
            val bl = PointF(pts[3].x * sw, pts[3].y * sh)
            // 源四点在位图上；目标为整图大小的矩形
            val srcPts = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
            val dstPts = floatArrayOf(0f, 0f, sw - 1f, 0f, sw - 1f, sh - 1f, 0f, sh - 1f)
            val m = Matrix()
            if (!m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return null
            val out = Bitmap.createBitmap(sw.toInt(), sh.toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.BLACK)
            c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            out
        } catch (e: Exception) {
            Log.e(TAG, "四点校正失败: ${e.message}", e)
            null
        }
    }

    /**
     * ★ 缩放并居中裁剪为正方形小图
     */
    private fun scaleAndCropToSquare(src: Bitmap, tw: Int, th: Int): Bitmap {
        val w = src.width
        val h = src.height
        val scale = maxOf(tw.toFloat() / w, th.toFloat() / h)
        val m = Matrix()
        m.postScale(scale, scale)
        var scaled = Bitmap.createBitmap(src, 0, 0, w, h, m, true)
        // 居中裁剪到 tw×th
        val sx = (scaled.width - tw) / 2
        val sy = (scaled.height - th) / 2
        val cx = sx.coerceAtLeast(0)
        val cy = sy.coerceAtLeast(0)
        val cw = minOf(tw, scaled.width)
        val ch = minOf(th, scaled.height)
        if (scaled.width == tw && scaled.height == th) return scaled
        val cropped = Bitmap.createBitmap(scaled, cx, cy, cw, ch)
        if (cropped != scaled) scaled.recycle()
        return cropped
    }

    /**
     * ★ 轻点缩略图：收起态→放大到半屏；放大态→缩回收起
     */
    private fun toggleSetupThumb() {
        if (!setupThumbFocused || setupThumbRawBitmap == null) return
        ensureSetupThumbMetrics()
        if (setupThumbExpanded) {
            collapseSetupThumb()
        } else {
            applySetupThumbHeight(setupThumbMaxHeightPx)
        }
    }

    /**
     * ★ 收缩键：点击把放大态缩回右下角小图（整个黑区收起，主棋盘恢复）
     */
    private fun collapseSetupThumb() {
        if (!setupThumbFocused) return
        setupThumbExpanded = false
        setSetupThumbSmall()            // 右下角小图
        restoreSetupThumbLayout()       // 黑区回默认权重 25%（不残留固定高度）
        ensureSetupThumbMetrics()
        // ★★ 铁板一块：黑区已收起 → 面板回落、棋盘解除压缩，整块复位
        syncSetupBundle()
        // ★ 黑区收起后棋盘已不处于被顶状态 → 重新贴合(棋盘恰好包住9×10)
        frameBoardOuter.post { tuneBoardVsThinking() }
    }

    private fun dp2px(dp: Float): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * ★ 处理相机权限请求结果
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能拍照识别", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateSetupPieceSelection() {
        val selType = selectedPieceType
        val selColor = selectedPieceColor

        btnRedKing.isSelected = selType == PieceType.KING && selColor == PieceColor.RED
        btnRedAdvisor.isSelected = selType == PieceType.ADVISOR && selColor == PieceColor.RED
        btnRedElephant.isSelected = selType == PieceType.ELEPHANT && selColor == PieceColor.RED
        btnRedRook.isSelected = selType == PieceType.ROOK && selColor == PieceColor.RED
        btnRedKnight.isSelected = selType == PieceType.KNIGHT && selColor == PieceColor.RED
        btnRedCannon.isSelected = selType == PieceType.CANNON && selColor == PieceColor.RED
        btnRedPawn.isSelected = selType == PieceType.PAWN && selColor == PieceColor.RED

        btnBlackKing.isSelected = selType == PieceType.KING && selColor == PieceColor.BLACK
        btnBlackAdvisor.isSelected = selType == PieceType.ADVISOR && selColor == PieceColor.BLACK
        btnBlackElephant.isSelected = selType == PieceType.ELEPHANT && selColor == PieceColor.BLACK
        btnBlackRook.isSelected = selType == PieceType.ROOK && selColor == PieceColor.BLACK
        btnBlackKnight.isSelected = selType == PieceType.KNIGHT && selColor == PieceColor.BLACK
        btnBlackCannon.isSelected = selType == PieceType.CANNON && selColor == PieceColor.BLACK
        btnBlackPawn.isSelected = selType == PieceType.PAWN && selColor == PieceColor.BLACK

        if (selType != null && selColor != null) {
            val validPositions = when (selType) {
                PieceType.KING -> if (selColor == PieceColor.RED) SetupValidator.RED_KING_POSITIONS else SetupValidator.BLACK_KING_POSITIONS
                PieceType.ADVISOR -> if (selColor == PieceColor.RED) SetupValidator.RED_ADVISOR_POSITIONS else SetupValidator.BLACK_ADVISOR_POSITIONS
                PieceType.ELEPHANT -> if (selColor == PieceColor.RED) SetupValidator.RED_ELEPHANT_POSITIONS else SetupValidator.BLACK_ELEPHANT_POSITIONS
                PieceType.PAWN -> if (selColor == PieceColor.RED) SetupValidator.RED_PAWN_POSITIONS else SetupValidator.BLACK_PAWN_POSITIONS
                else -> {
                    val all = mutableSetOf<Position>()
                    for (r in 0..9) for (c in 0..8) all.add(Position(r, c))
                    all
                }
            }
            chessBoard.setSetupValidPositions(validPositions)
        } else {
            chessBoard.setSetupValidPositions(null)
        }
    }

    // ── 设置栏 ──

    private fun getCurrentValue(): Int {
        return if (chessGame.searchMode == "depth") {
            chessGame.engineDepth
        } else {
            chessGame.engineTimeMs / 1000
        }
    }

    private fun applyValue(value: Int) {
        if (chessGame.searchMode == "depth") {
            chessGame.engineDepth = value.coerceIn(1, 30)
        } else {
            chessGame.engineTimeMs = value.coerceAtLeast(1) * 1000
        }
    }

    private fun updateSettingUI() {
        isUpdatingUI = true
        val value = getCurrentValue()
        etValue.setText(value.toString())
        if (chessGame.searchMode == "depth") {
            btnMode.text = "深度:${value}层"
        } else {
            btnMode.text = "步时:${value}秒"
        }
        isUpdatingUI = false
    }

    private fun updateCloudBookButton() {
        btnCloudBook.text = if (chessGame.cloudBookEnabled) "云库:开" else "云库:关"
    }

    // ─────────────────────────────────────────────
    // ★★ 本地外置开局库管理
    // ─────────────────────────────────────────────

    /** ★ 已废弃：不再自动装载任何内置默认库。
     *  曾把 assets/books/default_book.json 作为"内置·主流开局"装载，但其内容未经
     *  棋谱审核，含自吃己方子等低劣着法（如黑车平到红炮射程内送吃）。该 JSON 已删除，
     *  保留此空函数仅为避免历史调用点编译报错；开局库现完全由用户自加外置谱控制。 */
    private fun ensureDefaultLocalBookLoaded() {
        // no-op：已移除内置默认库，杜绝劣谱被无脑信任
    }

    /** 恢复已挂接的外置库文件路径（逗号分隔，挂到 ChessGame.localBook）。
     *  在后台线程调用（反解耗时），返回成功装载的库数量。
     *  单个库文件直接装载；压缩包路径则整包解包挂其内所有库。 */
    private fun restoreExternalLocalBooksAsync(pathsCsv: String?): Int {
        if (pathsCsv.isNullOrBlank()) return 0
        var count = 0
        for (p in pathsCsv.split(",")) {
            val raw = p.trim()
            if (raw.isEmpty()) continue
            if (chessGame.localBook.hasSource(raw)) continue   // 该来源（文件/压缩包）已挂
            if (raw.lowercase().endsWith(".zip")) {
                count += loadZipFromPath(raw)                  // 压缩包：整包一个档位
            } else {
                val name = java.io.File(raw).name.substringBeforeLast('.')
                val loaded = chessGame.localBook.loadBook(name, raw)
                if (loaded != null) count++
            }
        }
        return count
    }

    /** 外置加载一个库文件（供文件选择器/侧滑栏调用），并持久化 */
    fun addExternalLocalBook(path: String): Boolean {
        val loaded = chessGame.localBook.loadBook(java.io.File(path).name.substringBeforeLast('.'), path)
        if (loaded != null) persistLocalBookPaths()
        return loaded != null
    }

    /** 按 id 移除一个已挂接的外部库并持久化 */
    fun removeExternalLocalBook(bookPath: String) {
        chessGame.localBook.listBooks().filter { it.path == bookPath }.forEach {
            chessGame.localBook.removeBook(it.bookId)
        }
        persistLocalBookPaths()
    }

    /** 把当前加载的外置库路径持久化到 prefs（按来源档位去重；压缩包整包只存一条） */
    private fun persistLocalBookPaths() {
        val sources = LinkedHashSet<String>()
        for (b in chessGame.localBook.listBooks()) {
            val key = b.group.ifEmpty { b.path }
            if (key.isNotEmpty()) sources.add(key)
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCAL_BOOK_PATHS, sources.joinToString(","))
            .putBoolean(KEY_LOCAL_BOOK, chessGame.localBookEnabled)
            .apply()
    }

    // ════════════════════════════════════════════════════════════
    //  左侧「书库」侧滑栏
    // ════════════════════════════════════════════════════════════
    private fun initBookPicker() {
        // ★ 支持连续多选：一次可勾选多个库文件/压缩包，一股脑批量导入。
        //   开局库是"来源文件"计数、上限 MAX_BOOKS(6) 个；真正开局库加满后，
        //   多选里多出来的会在此处给出"已满、跳过"的明确提示。
        bookPickerLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) addBooksFromUris(uris)
        }
        qipuUploadPickerLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) addQipuToVaultFromUris(uris)
        }
    }

    /** ★ 批量把选中的棋谱文件持久化到宝库「我的上传」（无数量限制，重启后仍在）。 */
    private fun addQipuToVaultFromUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        var ok = 0
        var fail = 0
        for (uri in uris) {
            if (uploadQipuToVault(uri)) ok++ else fail++
        }
        if (ok > 0) {
            toast("已上传到宝库·我的上传 $ok 个棋谱文件（$fail 个失败）。打开宝库即可看到。")
        } else {
            toast("上传失败或文件无法读取，请重试")
        }
    }

    /** 把单个文件读取并持久化到宝库「我的上传」目录；成功返回 true。 */
    private fun uploadQipuToVault(uri: android.net.Uri): Boolean {
        return try {
            val name = queryDisplayName(uri)?.substringBeforeLast('.')?.ifBlank { "upload_${System.currentTimeMillis()}" }
                ?: "upload_${System.currentTimeMillis()}"
            val ext = (queryDisplayName(uri)?.substringAfterLast('.', "")?.lowercase())?.let {
                if (it.length in 1..6 && it.all(Char::isLetterOrDigit)) it else null
            } ?: "cbl"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return false
            com.pikafish.chess.qipu.QipuLibrary.persistUserUpload(this, "$name.$ext", bytes) != null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "uploadQipuToVault 异常", e)
            false
        }
    }

    /** ★ 批量导入选中的多个文件：开局库来源 ≤ MAX_BOOKS(6)，超出部分跳过并明确提示。 */
    private fun addBooksFromUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val limit = com.pikafish.chess.engine.LocalBookManager.MAX_BOOKS
        var added = 0
        var skipped = 0
        for (uri in uris) {
            // 占用判定 = 已挂来源 + 仍在“加载中”待挂装的项（避免并发把上限冲爆）
            val used = chessGame.localBook.sourceCount() + loadingBooks.size
            if (used >= limit) {
                skipped++
                continue
            }
            addBookFromUri(uri)
            added++
        }
        if (skipped > 0) {
            toast("开局库已满（最多 $limit 个来源），第 ${added + 1} 个起已跳过未添加。请先在开局库里移除某些来源再添加。")
        }
    }

    /** 构建左侧书库侧滑面板（叠加在 android.R.id.content 之上，左上角滑入） */
    private fun setupBookDrawer() {
        val content = findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return

        val container = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true   // 拦截点击，避免穿透到下棋板
            setBackgroundColor(0x99000000.toInt())
            visibility = android.view.View.GONE
        }
        // 点击遮罩关闭
        container.setOnClickListener { closeBookDrawer() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F0E6.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (resources.displayMetrics.density * 300).toInt(),
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = android.view.Gravity.START }
            elevation = 20f
        }
        container.addView(panel)
        content.addView(container)

        bookDrawerContainer = container
        bookDrawerPanel = panel
        renderBookList()
    }

    private fun toggleBookDrawer() {
        if (bookDrawerOpen) closeBookDrawer() else openBookDrawer()
    }

    private fun openBookDrawer() {
        val container = bookDrawerContainer ?: return
        val panel = bookDrawerPanel ?: return
        bookDrawerOpen = true
        renderBookList()          // 每次打开刷新一次，看有没有新增/移除
        container.visibility = android.view.View.VISIBLE
        panel.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeBookDrawer() {
        val container = bookDrawerContainer ?: return
        val panel = bookDrawerPanel ?: return
        if (!bookDrawerOpen) return
        bookDrawerOpen = false
        panel.animate().translationX(-panel.width.toFloat()).setDuration(200).withEndAction {
            container.visibility = android.view.View.GONE
        }.start()
    }

    /** 重绘书库面板内容 */
    private fun renderBookList() {
        val container = bookDrawerContainer ?: return
        container.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F0E6.toInt())
            isClickable = true   // 面板自身可点，避免点面板空白处把侧滑栏关了
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (resources.displayMetrics.density * 300).toInt(),
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = android.view.Gravity.START }
            elevation = 20f
            translationX = if (bookDrawerOpen) 0f else -(300 * resources.displayMetrics.density)
        }
        container.addView(panel)
        bookDrawerPanel = panel

        // --- 标题栏 ---
        panel.addView(bookDrawerTitleBar())
        // --- 全局开关 ---
        panel.addView(bookGlobalSwitch())
        // --- 库容量预警 ---
        panel.addView(bookCapacityView())
        // --- 库列表 ---
        panel.addView(bookListArea())
        // --- 底部：添加按钮 + 说明 ---
        panel.addView(bookAddButton())
        panel.addView(bookFolderAddButton())
        panel.addView(qipuUploadButton())
        panel.addView(bookHintFooter())
        // ★ 修复：只有侧滑栏真正打开时才显示整屏遮罩容器；关闭状态必须保持 GONE。
        //   否则启动时 setupBookDrawer()/restoreAllSettings() 调用本函数会把遮罩置为
        //   VISIBLE，整屏 isClickable 的暗色遮罩拦截所有点击，导致主界面"冻死"、
        //   按钮全无响应、侧滑栏也滑不出来。
        if (bookDrawerOpen) {
            container.visibility = android.view.View.VISIBLE
            panel.animate().translationX(0f).setDuration(220).start()
        } else {
            container.visibility = android.view.View.GONE
        }
    }

    private fun bookDrawerTitleBar(): android.view.View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16, 16, 12, 14)
        }
        val title = TextView(this).apply {
            text = "开局库"
            textSize = 17f
            setTextColor(0xFF2A2320.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sub = TextView(this).apply {
            text = if (loadingBooks.isNotEmpty()) "装载中 ${loadingBooks.size} 项…" else "上限 ${LocalBookManager.MAX_BOOKS} 个文件/压缩包"
            textSize = 11f
            setTextColor(0xFF8A8178.toInt())
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(0xFF2A2320.toInt())
            setPadding(8, 4, 4, 4)
            setOnClickListener { closeBookDrawer() }
        }
        bar.addView(title); bar.addView(sub); bar.addView(close)
        return bar
    }

    private fun bookGlobalSwitch(): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16, 6, 16, 6)
        }
        val label = TextView(this).apply {
            text = "使用开局库"
            textSize = 14f
            setTextColor(0xFF2A2320.toInt())
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = android.widget.Switch(this).apply {
            isChecked = chessGame.localBookEnabled
            setOnCheckedChangeListener { _, checked ->
                chessGame.localBookEnabled = checked
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_LOCAL_BOOK, checked).apply()
            }
        }
        row.addView(label); row.addView(sw)
        return row
    }

    /**
     * ★ 库容量预警条：给用户"还能再装多少"的抓手，避免盲目加载到内存打爆(OOM)崩溃。
     * 抓手维度（自上而下）：
     *   1) 来源档位  n/6    —— 档位是否已满；
     *   2) 局面总数  —— 当前库共可命中多少局面；
     *   3) 内存占用  —— 实时堆使用%（距 OOM 的距离，这是崩掉的直接原因）；
     *   4) 还能加塞  —— 按现库平均局面密度估算，剩余堆里大约还能承载多少局面。
     * 状态分级：绿=充裕 → 橙=偏紧 → 红=紧张/告急；数值均为估算，作参考方向。
     */
    private fun bookCapacityView(): android.view.View {
        val cs = chessGame.localBook.capacityStats()
        val rt = Runtime.getRuntime()
        val usedHeap = rt.totalMemory() - rt.freeMemory()
        val maxHeap = rt.maxMemory().coerceAtLeast(1L)
        val heapPct = (usedHeap.toFloat() / maxHeap).coerceIn(0f, 1f)

        // 预留 35% 堆给 App 常态开销（棋盘/引擎/识别模型）作安全缓冲，其余才可用于开局库
        val heapLeftForBooks = (maxHeap * 65L / 100 - usedHeap).coerceAtLeast(0L)
        val perPos = cs.perPositionBytes.coerceAtLeast(120)
        val headroom = heapLeftForBooks / perPos   // 还能承载的局面数（约）

        // 状态分级：先看堆使用；档位满也强制提示
        val slotsFull = cs.sources >= cs.sourcesMax && cs.sourcesMax > 0
        val level = when {
            heapPct >= 0.90f || slotsFull && heapPct >= 0.80f -> 3
            heapPct >= 0.80f -> 2
            heapPct >= 0.60f -> 1
            else -> 0
        }
        val bg: Int
        val fg: Int
        val lip: Int
        val chip: String
        val tip: String
        when (level) {
            3 -> { bg = 0xFFF4D6CE.toInt(); fg = 0xFF8E2F1D.toInt(); lip = 0xFFC0392B.toInt(); chip = "内存告急"; tip = "再加载可能直接崩溃，请先移除较多库" }
            2 -> { bg = 0xFFFBE4D6.toInt(); fg = 0xFF8E4A21.toInt(); lip = 0xFFD2691E.toInt(); chip = "内存紧张"; tip = "建议先移除若干大库，再加新库" }
            1 -> { bg = 0xFFFBF3DD.toInt(); fg = 0xFF7A5A1E.toInt(); lip = 0xFFC8882E.toInt(); chip = "内存偏紧"; tip = "请谨慎添加大库；可留意下面可用局面数" }
            else -> { bg = 0xFFEAF2E3.toInt(); fg = 0xFF33501F.toInt(); lip = 0xFF5A8F3C.toInt(); chip = "内存充裕"; tip = "按现库密度估算，还可再装不少局面" }
        }
        val chipText = if (slotsFull) "档位已满" else chip
        val chipColor = if (slotsFull) 0xFFC0392B.toInt() else lip

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(bg)
        }
        // 顶部：标题 + 状态
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row1.addView(TextView(this).apply {
            text = "库容量预警"
            textSize = 13.5f
            setTextColor(0xFF3C332E.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row1.addView(TextView(this).apply {
            text = chipText
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(chipColor)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        card.addView(row1)

        // 档位 + 局面数
        card.addView(TextView(this).apply {
            text = "已挂 ${cs.sources}/${cs.sourcesMax} 档来源 · 共" + fmtBig(cs.positions) + " 个局面"
            textSize = 12f
            setTextColor(fg)
            setPadding(0, dp(6), 0, 0)
        })

        // 内存进度条（横向加权填充，宽度自适应面板）
        val barTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
            )
            setPadding(0, dp(4), 0, 0)
        }
        val wUsed = (heapPct * 10000).toInt().coerceIn(0, 10000)
        val wFree = (10000 - wUsed).coerceAtLeast(0)
        val fillColor = if (heapPct >= 0.8f) 0xFFC0392B.toInt()
            else if (heapPct >= 0.6f) 0xFFD69A2D.toInt()
            else 0xFF6B9E4B.toInt()
        barTrack.addView(android.view.View(this).apply {
            setBackgroundColor(fillColor)
            layoutParams = LinearLayout.LayoutParams(0, dp(8), wUsed.toFloat())
        })
        barTrack.addView(android.view.View(this).apply {
            setBackgroundColor(0xFFD9D2C4.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(8), wFree.toFloat())
        })
        card.addView(barTrack)

        // 内存占用 % 
        card.addView(TextView(this).apply {
            text = "内存占用 ${(heapPct * 100).toInt()}%（${fmtMB(usedHeap)}/${fmtMB(maxHeap)}）"
            textSize = 11f
            setTextColor(fg)
            setPadding(0, dp(2), 0, 0)
        })
        // 还能加塞：档位未满时按内存密度估算；满档则以档位为准
        if (slotsFull) {
            card.addView(TextView(this).apply {
                text = "档位已满（${cs.sources}/${cs.sourcesMax}）：需先「移除」若干库/压缩包，才可再添加"
                textSize = 11f
                setTextColor(0xFFC0392B.toInt())
            })
        } else {
            card.addView(TextView(this).apply {
                text = "按当前密度估算，还剩约 " + fmtBig(headroom) + " 个局面可承载"
                textSize = 11f
                setTextColor(0xFFC0392B.toInt())
            })
        }
        // 提示语
        card.addView(TextView(this).apply {
            text = tip
            textSize = 10.5f
            setTextColor(fg)
            setPadding(0, dp(3), 0, 0)
        })
        return card
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 大数格式化：≥1 亿→亿，≥1 万→万，其余原样 */
    private fun fmtBig(n: Long): String = when {
        n >= 100_000_000 -> String.format(java.util.Locale.US, "%.1f亿", n / 1e8)
        n >= 10_000 -> String.format(java.util.Locale.US, "%.1f万", n / 1e4)
        else -> n.toString()
    }

    private fun fmtMB(b: Long): String = String.format(java.util.Locale.US, "%.0fMB", b / 1048576f)

    private fun bookListArea(): android.view.View {
        val books = chessGame.localBook.listBooks()
        val sv = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (books.isEmpty() && loadingBooks.isEmpty()) {
            wrap.addView(TextView(this).apply {
                text = "尚未挂接任何外置库\n点击下方「＋ 添加库文件」从设备选择"
                textSize = 13f
                setTextColor(0xFF8A8178.toInt())
                setPadding(16, 20, 16, 20)
            })
        } else {
            // ★ 正在后台装载的库置顶显示"加载中…"，装完自动更新；随时可关面板去下棋
            loadingBooks.forEach { name -> wrap.addView(bookLoadingRow(name)) }
            books.forEach { b -> wrap.addView(bookRow(b)) }
        }
        sv.addView(wrap)
        return sv
    }

    /** 后台装载中的库占位行：灰字"加载中…" + 转圈，不阻塞、不阻塞主界面 */
    private fun bookLoadingRow(name: String): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(14, 12, 14, 12)
        }
        val spinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            visibility = android.view.View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.density * 18).toInt(),
                (resources.displayMetrics.density * 18).toInt()
            ).apply { rightMargin = (resources.displayMetrics.density * 10).toInt() }
        }
        val label = TextView(this).apply {
            text = "加载中… $name"
            textSize = 13f
            setTextColor(0xFFB0855C.toInt())
        }
        val tip = TextView(this).apply {
            text = "(后台装载，可先对弈，无需等待)"
            textSize = 11f
            setTextColor(0xFF8A8178.toInt())
            setPadding(14, 0, 14, 12)
        }
        row.addView(spinner); row.addView(label)
        tip.layoutParams = LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(row)
        col.addView(tip)
        return col
    }

    private fun bookRow(b: LocalBookManager.LoadedBook): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
        }
        val line1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val name = TextView(this).apply {
            text = b.name
            textSize = 14f
            setTextColor(0xFF2A2320.toInt())
            text = if (text.isEmpty()) "(未命名)" else text
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val enable = android.widget.CheckBox(this).apply {
            isChecked = b.enabled
            setOnCheckedChangeListener { _, checked -> chessGame.localBook.setEnabled(b.bookId, checked) }
        }
        val remove = TextView(this).apply {
            text = "移除"
            textSize = 12f
            setTextColor(0xFFB4432E.toInt())
            setPadding(8, 4, 4, 4)
            setOnClickListener {
                chessGame.localBook.removeBook(b.bookId)
                persistLocalBookPaths()
                renderBookList()   // 移除后立即重建面板
            }
        }
        line1.addView(name); line1.addView(enable); line1.addView(remove)
        row.addView(line1)

        val fmt = b.format.uppercase()
        val statusText = if (b.entryCount > 0) "$fmt · 命中 ${b.entryCount} 局面 · 启用"
        else "$fmt · 已识别，0 覆盖（私有/加密库无法反解，命中由其它库或引擎接管）"
        row.addView(TextView(this).apply {
            text = statusText
            textSize = 11f
            setTextColor(if (b.entryCount > 0) 0xFF5A6B45.toInt() else 0xFFB0855C.toInt())
        })
        return row
    }

    private fun bookAddButton(): android.view.View {
        val btn = TextView(this).apply {
            text = "＋ 添加库文件/压缩包"
            gravity = android.view.Gravity.CENTER
            setPadding(16, 13, 16, 13)
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF8B6B45.toInt())
            setOnClickListener {
                bookPickerLauncher?.launch(arrayOf("*/*"))
            }
        }
        return btn
    }

    /** ★ 扫本地目录批量导入：把库文件放进「存储/HHXQ/books」即可一次全挂上，
     *  每个文件占一个档位（受 MAX_BOOKS 上限约束）；OBK 大库走路径流式装载。 */
    private fun bookFolderAddButton(): android.view.View {
        val btn = TextView(this).apply {
            text = "📂 扫本地目录 HHXQ/books 批量导入"
            gravity = android.view.Gravity.CENTER
            setPadding(16, 13, 16, 13)
            textSize = 14f
            setTextColor(0xFF2B2115.toInt())
            setBackgroundColor(0xFFE4C98A.toInt())
            setOnClickListener { importBooksFromLocalFolder() }
        }
        return btn
    }

    /** ★ 从「存储/HHXQ/books」目录批量导入开局库（含子目录递归）。
     *  该目录只需用文件管理器把 .obk/.cbl/.bin/.pgn 等放进去即可——App 在本地路径里找库，
     *  不占 APK 体积、不给应用添额外负担；Android 11+ 需先授予「所有文件访问权限」。 */
    private fun importBooksFromLocalFolder() {
        if (android.os.Build.VERSION.SDK_INT >= 30 &&
            !android.os.Environment.isExternalStorageManager()) {
            toast("需要「所有文件访问权限」才能读外部目录\n请在弹出的系统设置中打开权限，再回来点一次")
            try {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                try { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) { /* 个别机型无此入口，忽略 */ }
            }
            return
        }
        val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "HHXQ/books")
        if (!dir.exists() || !dir.isDirectory) {
            dir.mkdirs()
            toast("已创建目录：${dir.absolutePath}\n把开局库文件放进去，再点一次即可批量挂接")
            return
        }
        val files = scanBookFiles(dir)
        if (files.isEmpty()) {
            toast("目录 ${dir.absolutePath} 里还没有可识别的开局库文件\n（.json/.txt/.pkb/.ubb/.pgn/.obk/.dat/.bin/.pfc/.cbl）")
            return
        }
        val label = "📂 本地目录导入(${files.size})"
        loadingBooks.add(label)
        renderBookList()
        initScope.launch(Dispatchers.IO) {
            var added = 0; var failed = 0; var skipped = 0
            var firstErr: String? = null
            for (f in files) {
                if (chessGame.localBook.sourceCount() + loadingBooks.size >= LocalBookManager.MAX_BOOKS) { skipped++; continue }
                val loaded = try {
                    chessGame.localBook.loadBookFromPath(f.name.substringBeforeLast('.'), f.absolutePath)
                } catch (e: Exception) { Log.e(TAG, "导入本地库失败: $f", e); null }
                if (loaded != null) added++ else {
                    failed++
                    if (firstErr == null) firstErr = "「${f.name}」：" + (chessGame.localBook.lastLoadError ?: "未识别")
                }
            }
            uiHandler.post {
                loadingBooks.remove(label)
                if (added > 0) persistLocalBookPaths()
                renderBookList()
                if (failed > 0 && firstErr != null) {
                    // ★ 有失败就弹窗说清原因（不再只给一个干巴巴的计数）
                    showBookLoadFailure(
                        "本地目录导入：成功 $added 个，失败 $failed 个",
                        (if (skipped > 0) "另有 $skipped 个因已达上限被跳过。\n\n" else "") + "首个失败原因：\n$firstErr"
                    )
                } else {
                    toast("本地目录导入：成功 $added 个${if (skipped > 0) "，已满跳过 $skipped 个" else ""}")
                }
            }
        }
    }

    /** ★ 上传棋谱到宝库（持久保存，重启后仍在宝库「我的上传」里找得到）。 */
    private fun qipuUploadButton(): android.view.View {
        val btn = TextView(this).apply {
            text = "📚 上传棋谱到宝库「我的上传」"
            gravity = android.view.Gravity.CENTER
            setPadding(16, 13, 16, 13)
            textSize = 14f
            setTextColor(0xFF2B2115.toInt())
            setBackgroundColor(0xFFFFB300.toInt())
            setOnClickListener {
                qipuUploadPickerLauncher?.launch(arrayOf("*/*"))
            }
        }
        return btn
    }

    private fun bookHintFooter(): android.view.View {
        return TextView(this).apply {
            text = "支持：.json、.txt、.pkb、.ubb（东萍棋谱）、.pgn\n" +
                    "以及 .dat / .bin 开局库（含象眼引擎 BOOK.DAT 二进制库与冰河五四文本库，自动识别）、.obk（旋风/Shiga 电脑库，App 内直接反解命中）、.pfc（鹏飞象棋 XML 谱）、.cbl（象棋桥棋谱，自动回放建库）。\n" +
                    "★ 支持 ZIP 压缩包：可把多本 .cbl/.obk/.bin 等打成一个包直接添加，App 会自动解包并递归扫描其内所有库（含子目录），整包只占一个档位。\n" +
                    "★ 本地目录：把库文件放进「存储/HHXQ/books」，点「扫本地目录」即可整目录批量挂接。\n" +
                    "★ 大库不设体积上限：.obk（SQLite/旋风系）走句柄按局面索引直查、.bin（象眼系）走磁盘二分直查——整本都不进内存，几 GB 也照样能挂、只占常数内存；其它需整本建索引的格式则受设备内存限制，上方「库容量预警」实时显示余量。\n" +
                    "命中库招会以金黄色在思考细节中标注「翻库」。"
            textSize = 11f
            setTextColor(0xFF8A8178.toInt())
            setPadding(16, 12, 16, 16)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  书库侧滑栏 · SAF 选库挂接
    // ════════════════════════════════════════════════════════════
    /** SAF 选到库文件：把内容落到本应用缓存目录再挂接，保证可持久化、重启可用。
     *  若选到的是压缩包（ZIP），自动解包并"顺藤摸瓜"递归扫描其中所有开局库/棋谱文件
     *  （含子目录），整包只占一个档位一起挂接。
     *  读取+反解全部在后台线程进行（大库可能数秒），侧滑栏先显示"加载中…"，
     *  用户可立即关面板去下棋，装完自动刷新面板。 */
    private fun addBookFromUri(uri: android.net.Uri) {
        try {
            // ★ 占用判定计入“加载中”待挂装的项，与批量导入保持一致。
            if (chessGame.localBook.sourceCount() + loadingBooks.size >= LocalBookManager.MAX_BOOKS) {
                toast("最多挂接 ${LocalBookManager.MAX_BOOKS} 个库文件/压缩包，请先移除后再添加")
                return
            }
            val name = queryDisplayName(uri)?.substringBeforeLast('.')?.ifBlank { "book_${System.currentTimeMillis()}" }
                ?: "book_${System.currentTimeMillis()}"
            val ext = (queryDisplayName(uri)?.substringAfterLast('.', "")?.lowercase())?.let {
                if (it.length in 1..6 && it.all(Char::isLetterOrDigit)) it else null
            } ?: "pkb"

            // 登记为"加载中"，面板立即可见；随后在后台线程读取+反解
            loadingBooks.add(name)
            renderBookList()
            initScope.launch(Dispatchers.IO) {
                val cachedPath: String
                val head: ByteArray
                try {
                    // ★ 空间预检：2GB+ 的库若本机空间不够，复制必然中途失败。之前这里的异常被吞掉，
                    //   表现就是"没反应、也没提示，用户以为装上了" —— 现在提前拦下并如实说明。
                    val cacheDir = java.io.File(filesDir, "books").apply { mkdirs() }
                    val srcSize = queryFileSize(uri)
                    val usable = cacheDir.usableSpace
                    if (srcSize > 0L && srcSize > usable - 64L * 1024 * 1024) {
                        uiHandler.post {
                            loadingBooks.remove(name); renderBookList()
                            showBookLoadFailure(
                                "空间不足，未能添加开局库",
                                "所选文件约 ${fmtMB(srcSize)}，本机可用空间约 ${fmtMB(usable)}，无法复制到应用内部存储。\n\n" +
                                        "建议：把库文件放进「存储/HHXQ/books」后点侧滑栏里的「📂 扫本地目录」——App 会原地读取、不复制，超大库也不额外占空间。"
                            )
                        }
                        return@launch
                    }
                    // ★ 大库友好：不再 readBytes() 把整本 190MB+ 文件读进内存（这正是 OOM 报警根源），
                    //   改为 64KB 缓冲块流式落盘缓存，只额外保留前 16 字节做魔数识别。
                    val ins = contentResolver.openInputStream(uri)
                        ?: run { uiHandler.post { loadingBooks.remove(name); renderBookList(); toast("无法读取所选文件") }; return@launch }
                    val safe = name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")
                    cachedPath = java.io.File(cacheDir, "$safe.$ext").absolutePath
                    head = ins.use { input ->
                        val headBuf = ByteArray(16)
                        var h = 0
                        while (h < 16) {     // 循环读到 16 字节（SAF 流短读安全）
                            val n = input.read(headBuf, h, 16 - h)
                            if (n < 0) break
                            h += n
                        }
                        val hd = if (h > 0) headBuf.copyOf(h) else ByteArray(0)
                        java.io.FileOutputStream(cachedPath).use { output ->
                            if (h > 0) output.write(headBuf, 0, h)
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                            }
                        }
                        hd
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "addBookFromUri 读取异常", e)
                    uiHandler.post {
                        loadingBooks.remove(name); renderBookList()
                        showBookLoadFailure(
                            "读取/复制库文件失败",
                            (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName) +
                                    "\n\n若文件很大，通常是应用内部存储空间不足；可改用「📂 扫本地目录」原地读取。"
                        )
                    }
                    return@launch
                }

                // ★ 压缩包识别（按魔数，扩展名仅兜底）
                val otherArc = otherArchiveMagic(head)
                if (otherArc != null) {
                    uiHandler.post { loadingBooks.remove(name); renderBookList(); toast("暂不支持 $otherArc 压缩包，请改选 ZIP 或直接选库文件") }
                    return@launch
                }
                if (zipMagic(head) || ext == "zip") {
                    val ok = loadZipFromPath(cachedPath)   // 解包 + 递归扫描 + 整包占一档挂接
                    uiHandler.post {
                        loadingBooks.remove(name); renderBookList()
                        if (ok > 0) { persistLocalBookPaths(); toast("已从压缩包 $name 导入 $ok 本开局库") }
                        else toast("该压缩包里没有可识别为开局库的文件（cbl/obk/pfBook/bin/dat/pfc/json/pkb/ubb/pgn/txt），未添加")
                    }
                    return@launch
                }

                // ★ 统一走路径装载：.obk(SQLite 句柄/流式) 与 .bin(磁盘二分直查) 一律不整本进内存，
                //   几 GB 也装得下；其余格式在 loader 内有大小护栏，超限如实拒绝而非把 App 读 OOM。
                val loaded = chessGame.localBook.loadBookFromPath(name, cachedPath)
                uiHandler.post {
                    loadingBooks.remove(name)
                    if (loaded != null) {
                        persistLocalBookPaths()
                        renderBookList()
                        toast("已挂接开局库：${loaded.name}（${loaded.format}，${loaded.entryCount} 局面）")
                    } else {
                        renderBookList()
                        // ★ 不再"静默失败"：把真实原因（格式/大小/表结构）如实弹出来
                        showBookLoadFailure(
                            "未能装载开局库「$name」",
                            chessGame.localBook.lastLoadError ?: "该库未识别或已达上限，未添加。"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "addBookFromUri 异常", e)
            toast("添加库失败: ${e.message}")
        }
    }

    /**
     * ★ 装载失败时把"为什么没装上"如实弹出来。
     *   之前的失败是静默的：面板刷新一下、库没出现，用户完全不知道原因，只会觉得"没装上"。
     *   这里用对话框（比一闪而过的 toast 更不容易被忽略）。
     */
    private fun showBookLoadFailure(title: String, message: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
        } catch (e: Exception) {
            toast(message)
        }
    }

    /** 通过 SAF 查询所选文件的字节大小；未知返回 -1 */
    private fun queryFileSize(uri: android.net.Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
            } ?: -1L
        } catch (e: Exception) { -1L }
    }

    // ── 压缩包（ZIP）解包 + 递归扫描 + 整包挂接 ─────────────────────────────
    private fun zipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** 其它常见压缩魔数：能识别但暂不支持的，给出友好提示 */
    private fun otherArchiveMagic(bytes: ByteArray): String? {
        if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'a'.code.toByte() &&
            bytes[2] == 'r'.code.toByte() && bytes[3] == '!'.code.toByte()) return "RAR"
        if (bytes.size >= 6 && bytes[0] == '7'.code.toByte() && bytes[1] == 'z'.code.toByte() &&
            bytes[2] == 0xBC.toByte() && bytes[3] == 0xAF.toByte() && bytes[4] == 0x27.toByte()) return "7z"
        return null
    }

    /**
     * 把一个 ZIP（缓存文件）解包到其同名子目录并按需挂接其中的开局库。
     * 已在后台线程调用。整包共享一个档位（来源组 = zipPath）。返回成功装载的库数量。
     */
    private fun loadZipFromPath(zipPath: String): Int {
        val zipFile = java.io.File(zipPath)
        if (!zipFile.exists()) return 0
        val base = zipFile.name.substringBeforeLast('.')
        val parent = zipFile.parentFile ?: filesDir
        val dir = java.io.File(parent, "${base}_files")
        if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
            dir.deleteRecursively()
            // ★ 大库友好：ZIP 本体也走文件流解包，不再整包 readBytes 进内存
            val ok = try {
                java.io.FileInputStream(zipFile).use { unwrapZipToDir(it, dir) }
            } catch (e: Exception) { Log.e(TAG, "loadZip read", e); false }
            if (!ok) return 0
        }
        if (chessGame.localBook.hasSource(zipPath)) return 0   // 该包已挂接过
        val bookFiles = scanBookFiles(dir)
        if (bookFiles.isEmpty()) return 0
        var ok = 0
        for (bf in bookFiles) {
            val fname = bf.name.substringBeforeLast('.')
            // ★ OBK 条目走路径流式装载（整本不进内存）；其余小格式由 loadBookFromPath 按字节装载
            val loaded = chessGame.localBook.loadBookFromPath(fname, bf.absolutePath, group = zipPath)
            if (loaded != null) ok++
        }
        return ok
    }

    /** 解压 ZIP 到目标目录（防目录穿越），失败返回 false */
    private fun unwrapZipToDir(zipInput: java.io.InputStream, destDir: java.io.File): Boolean {
        return try {
            java.util.zip.ZipInputStream(zipInput).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val clean = entry.name.replace('\\', '/').trimStart('/')
                    if (!entry.isDirectory && clean.isNotEmpty() &&
                        clean != ".." && !clean.startsWith("../") && !clean.contains("/../")) {
                        val target = java.io.File(destDir, clean)
                        target.parentFile?.mkdirs()
                        java.io.FileOutputStream(target).use { zis.copyTo(it, 64 * 1024) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "unzip 失败", e)
            false
        }
    }

    /** 递归扫描一个目录，收集所有可识别为开局库/棋谱的文件（含子目录） */
    private fun scanBookFiles(dir: java.io.File): List<java.io.File> {
        val out = ArrayList<java.io.File>()
        fun scan(d: java.io.File) {
            val kids = d.listFiles() ?: return
            for (f in kids) {
                if (f.isDirectory) scan(f)
                else {
                    val ext = f.extension.lowercase()
                    if (ext in LocalBookManager.BOOK_ARCHIVE_EXTS) out.add(f)
                }
            }
        }
        scan(dir)
        return out.sortedBy { it.name }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) { null }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun updateDetailButton() {
        btnDetail.text = if (chessGame.showThinkingDetails) "细节:开" else "细节:关"
    }

    /** ★「分析浮透」开关按钮文案随状态切换（已并入「细节」同行，用短名） */
    private fun updateFloatOverlayButton() {
        if (::btnFloatOverlay.isInitialized) {
            btnFloatOverlay.text = if (floatOverlayEnabled) "浮透:开" else "浮透:关"
        }
    }

    /** ★「后台思考」开关按钮文案随状态切换（人脑思考时电脑先预想） */
    private fun updateAlwaysThinkButton() {
        if (::btnAlwaysThink.isInitialized) {
            btnAlwaysThink.text = if (chessGame.alwaysThinking) "后台:开" else "后台:关"
        }
    }

    /** ★ 根据浮透开关刷新盘心浮透层：开启且有分析内容则浮现；关闭或没内容则撤下。 */
    private fun refreshFloatOverlay() {
        if (!::tvFloatOverlay.isInitialized) return
        if (floatOverlayEnabled && tvThinking.text != null && tvThinking.text.isNotEmpty()) {
            tvFloatOverlay.visibility = View.VISIBLE
            tvFloatOverlay.text = tvThinking.text
            // ★ 保持与思考条同一份富文本（颜色区分“我应/若走”），不再退化成纯文本
            if (tvThinking.text is android.text.Spanned) tvFloatOverlay.setText(tvThinking.text, TextView.BufferType.SPANNABLE)
        } else {
            tvFloatOverlay.visibility = View.GONE
            tvFloatOverlay.text = ""
        }
        updateFloatOverlayButton()
    }

    /**
     * ★ 让悬浮窗口在棋盘范围内可拖拽。
     *   纯手势驱动（DOWN/MOVE 实时平移），不申请任何悬浮窗权限；
     *   拖动手感顺滑，位置被夹持在棋盘上下左右边界内，不会跑出局外。
     *   分析浮透 / 解说弹窗 共用这一套手势。
     */
    private fun hookDrag(view: android.view.View) {
        if (!view.isAttachedToWindow && (view.parent as? android.view.ViewGroup) == null) return
        val parent = view.parent as? android.view.ViewGroup ?: return
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragDownX = ev.rawX
                    dragDownY = ev.rawY
                    dragStartTx = v.translationX
                    dragStartTy = v.translationY
                    dragBaseX = v.left       // 每次按下重新取基准，布局变化时也能自校正
                    dragBaseY = v.top
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val tx = dragStartTx + (ev.rawX - dragDownX)
                    val ty = dragStartTy + (ev.rawY - dragDownY)
                    val left = dragBaseX + tx
                    val top = dragBaseY + ty
                    val maxLeft = (parent.width - v.width).coerceAtLeast(0)
                    val maxTop = (parent.height - v.height).coerceAtLeast(0)
                    // 夹持在棋盘上下左右边界内，绝不拖出局外
                    v.translationX = left.coerceIn(0f, maxLeft.toFloat()) - dragBaseX
                    v.translationY = top.coerceIn(0f, maxTop.toFloat()) - dragBaseY
                    true
                }
                else -> false
            }
        }
    }

    private fun hookFloatOverlayDrag() {
        if (::tvFloatOverlay.isInitialized && tvFloatOverlay.parent is android.view.ViewGroup) {
            hookDrag(tvFloatOverlay)
        }
    }

    /**
     * ★ 拖「把手」移动「整窗」：浮透那种是拖窗本身，可旁白窗正文要滚动、不能被拖走，
     *   于是把拖动动作绑在标题条（把手）上，但位移施加到整个弹窗 target 上——
     *   拖标题条 = 拖着整个窗口跑，和拖浮透一个手感，限在父容器（整屏）内自由挪动。
     */
    private fun hookDragHandle(handle: android.view.View, target: android.view.View) {
        val parent = target.parent as? android.view.ViewGroup ?: return
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragDownX = ev.rawX
                    dragDownY = ev.rawY
                    dragStartTx = target.translationX
                    dragStartTy = target.translationY
                    dragBaseX = target.left       // 每次按下重新取基准，布局变化时也能自校正
                    dragBaseY = target.top
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val tx = dragStartTx + (ev.rawX - dragDownX)
                    val ty = dragStartTy + (ev.rawY - dragDownY)
                    val left = dragBaseX + tx
                    val top = dragBaseY + ty
                    val maxLeft = (parent.width - target.width).coerceAtLeast(0)
                    val maxTop = (parent.height - target.height).coerceAtLeast(0)
                    // 夹持在父容器（整屏）边界内，绝不拖出局外
                    target.translationX = left.coerceIn(0f, maxLeft.toFloat()) - dragBaseX
                    target.translationY = top.coerceIn(0f, maxTop.toFloat()) - dragBaseY
                    true
                }
                else -> false
            }
        }
    }

    private fun updateSideButtons() {
        btnRedSide.text = if (chessGame.redIsHuman) "红方:人脑" else "红方:AI"
        btnBlackSide.text = if (chessGame.blackIsHuman) "黑方:人脑" else "黑方:AI"
    }

    // ════════════════════════════════════════════════════════
    // ★ 分析模式：人脑 vs 人脑 + 引擎持续分析当前局面（不走棋）
    // ════════════════════════════════════════════════════════

    private fun updateAnalysisButton() {
        btnQipuCommentary.text = if (analysisActive) "分析:开" else "分析:关"
    }

    private fun toggleAnalysis() {
        if (!analysisActive) {
            // ── 开启分析 ──
            analysisActive = true
            analysisGen++
            chessGame.emitAnalysis = true   // ★ 分析开启：主心骨走子搜索直喂分析面板
            // ★ 强制红方人脑 + 黑方人脑（含面板上的红方/黑方按钮文字）
            chessGame.redIsHuman = true
            chessGame.blackIsHuman = true
            // ★ 通过对弈自身的“玩家切换”路径，安全停掉任何在途 AI 走棋搜索
            //   （内部会 stopSearch/stopPonder、作废旧代际；因双方均已为人脑，不会触发 AI 走子）
            try { chessGame.onPlayerTypeChanged() } catch (e: Exception) {}
            // ★ 后台思考让位给分析：独占引擎，避免预想抢线
            if (chessGame.alwaysThinking) {
                chessGame.alwaysThinking = false
                chessGame.engine.stopPonder()
            }
            updateSideButtons()
            // ★ 展示思考面板
            chessGame.showThinkingDetails = true
            updateDetailButton()
            // ★ 标记传染病代，作废任何在途分析结果
            lastAnalyzedFen = ""
            if (!tvThinking.isAttachedToWindow || tvThinking.visibility != View.VISIBLE) {
                tvThinking.visibility = View.VISIBLE
            }
            renderAnalysis("<b>【分析】</b> 开启该功能后，若需 AI 配合对弈走棋，每个初始回合需手工确认红方电脑或黑方电脑；否则默认双方均为人脑。每逢局面变化自动分析优劣，不替你走棋。", selfContained = true)
            updateAnalysisButton()
            analysisPoller.removeCallbacks(analysisPollRunnable)
            analysisPoller.post(analysisPollRunnable)
            Toast.makeText(this, "开启该功能后，若需AI配合走棋，初始回合请手工确认红方电脑或黑方电脑；否则默认双方均为人脑", Toast.LENGTH_LONG).show()
        } else {
            // ── 关闭分析 ──
            stopAnalysis()
        }
        saveAllSettings()
    }

    private fun stopAnalysis() {
        analysisActive = false
        analysisGen++
        analysisBusy = false
        chessGame.emitAnalysis = false   // ★ 分析关闭：停止"神同步"直喂，交还常规显示
        analysisSearchToken = 0
        analysisSearchStartMs = 0L
        analysisPoller.removeCallbacks(analysisPollRunnable)
        try { chessGame.engine.stopSearch() } catch (e: Exception) {}
        // ★ 还原思考面板显示规则：按“细节”开关决定是否可见
        tvThinking.visibility = if (chessGame.showThinkingDetails) View.VISIBLE else View.INVISIBLE
        if (!chessGame.showThinkingDetails) tvThinking.text = ""
        // ★ 分析结束：撤下盘心浮透层（分析时才会浮现）
        if (::tvFloatOverlay.isInitialized) tvFloatOverlay.visibility = View.GONE
        updateAnalysisButton()
    }

    /** 把一条分析文本渲染到思考面板（tvThinking）；开启「分析浮透」时同一段内容不再在
        原始位置重复冒泡，而只半透明浮现在盘心（tvFloatOverlay），透棋读字、守护专注。 */
    private fun renderAnalysis(html: String, selfContained: Boolean) {
        if (!analysisActive && !selfContained) return
        if (selfContained && !analysisActive) return
        runOnUiThread {
            try {
                val h = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                if (floatOverlayEnabled && ::tvFloatOverlay.isInitialized) {
                    // ★ 浮透开启：分析只上盘心浮透层，原始位置不再冒出分析稿
                    tvThinking.text = h
                    tvFloatOverlay.visibility = View.VISIBLE
                    tvFloatOverlay.text = h
                    tvThinking.visibility = View.INVISIBLE
                } else {
                    if (tvThinking.visibility != View.VISIBLE) tvThinking.visibility = View.VISIBLE
                    tvThinking.text = h
                }
            } catch (e: Exception) { }
        }
    }

    /** ★★★ 分析"神同步"渲染：由主心骨（对弈走子搜索）的思考细节直喂分析面板。
     *  final=false 为进行中（深度实时爬升刷新）；final=true 为 AI 落子定论（深度/评分/主变已定格）。
     *  分析不再自己跑深度18搜索，全程复用主心骨一遍结果——省算力、与走子零冲突。 */
    private fun renderAnalysisFromSearch(fen: String, d: Int, sd: Int, sc: Int, nodes: Long, nps: Long, pv: String, final: Boolean) {
        if (!analysisActive) return
        try {
            val board = com.pikafish.chess.model.Board()
            board.loadFromFen(fen)
            val player = board.currentPlayer
            val moverName = if (player == com.pikafish.chess.model.PieceColor.RED) "红方" else "黑方"
            val oppName = if (player == com.pikafish.chess.model.PieceColor.RED) "黑方" else "红方"
            val sb = StringBuilder()
            if (!final) {
                // 进行中：正在实时爬层（与走子搜索天然同一遍，绝不重复搜）
                sb.append("<b>【分析】</b> 轮到 $moverName（电脑）思考…<br/>")
                sb.append("深度 ${d}/${sd}　评分 <b style=\"color:${if (sc >= 0) "#C62828" else "#1B5E20"}\">${scoreText(sc)}</b>（当前轮到方视角）<br/>")
                val nodesStr = (if (nodes > 0) "节点 ${fmtBig(nodes)}" else "")
                val npsStr = (if (nps > 0) "　NPS ${fmtBig(nps)}/s" else "")
                if (nodesStr.isNotBlank() || npsStr.isNotBlank()) {
                    sb.append("<font color=\"#888888\">$nodesStr$npsStr</font><br/>")
                }
                val pvCn = pvToCn(pv, fen)
                if (pvCn.isNotBlank()) sb.append("推荐：$pvCn")
                renderAnalysis(sb.toString(), selfContained = false)
                return
            }
            // 落子定论：主心骨搜索收官，给出最终评估与推荐（无有效数据则给出等候提示）
            if (sd <= 0 && pv.trim().isBlank() && sc == 0) {
                renderAnalysis(
                    "<b>【分析】</b> 轮到 $moverName（电脑）走棋<br/><font color=\"#888888\">引擎正在准备…请稍候（未取得有效评分）。</font>",
                    selfContained = false
                )
                return
            }
            sb.append("<b>【分析】</b> 轮到 $moverName（电脑）走棋<br/>")
            val (assessment, scoreStr) = assessmentText(sc, moverName, oppName)
            sb.append("深度 ${d}/${sd}　评分 <b>$scoreStr</b>（当前轮到方为优则 +，否则对方优）<br/>")
            sb.append("局面评估：<b>$assessment</b><br/>")
            val pvFirst = pv.trim().split(" ").firstOrNull { it.length >= 4 }
            if (!pvFirst.isNullOrBlank()) {
                val bestNot = try {
                    com.pikafish.chess.engine.CommentaryEngine.uciToChineseNotation(pvFirst, board)
                } catch (e: Exception) { pvFirst }
                sb.append("引擎推荐：<font color=\"#2E7D32\"><b>$moverName $bestNot</b></font><br/>")
            }
            val pvCn = pvToCn(pv, fen)
            if (pvCn.isNotBlank()) sb.append("后续走势：$pvCn<br/>")
            sb.append("<font color=\"#888888\">电脑已走棋，自动继续下一局面分析。</font>")
            renderAnalysis(sb.toString(), selfContained = false)
        } catch (e: Exception) { }
    }

    /** 开局库/评分着色助手：把评分转成直观文本（含杀局提示）。score 为当前轮到方视角。 */
    private fun scoreText(sc: Int): String {
        return com.pikafish.chess.engine.JniEngine.mateText(sc)
            ?: "${if (sc >= 0) "+" else ""}$sc"
    }

    /** 局面评估文案：score 为当前轮到方视角，正=走子方优，负=对方优。 */
    private fun assessmentText(sc: Int, moverName: String, oppName: String): Pair<String, String> {
        val abs = kotlin.math.abs(sc)
        val bad = (sc < 0)
        val leadSide = if (bad) oppName else moverName
        val assessment = when {
            abs <= 18 -> "双方势均力敌"
            abs <= 55 -> "$leadSide 稍占优势"
            abs <= 110 -> "$leadSide 略占上风"
            abs <= 230 -> "$leadSide 明显占优"
            abs <= 480 -> "$leadSide 大占优势"
            else -> "$leadSide 胜势已现"
        }
        return Pair(assessment, scoreText(sc))
    }

    /** 把引擎返回的 PV（UCI 空格串）翻译成中文走法序列。 */
    private fun pvToCn(pv: String, fen: String): String {
        return try {
            com.pikafish.chess.engine.CommentaryEngine.pvToChinese(pv, fen)
        } catch (e: Exception) { pv }
    }

    /** 单次分析：用引擎搜索当前局面，仅评估不走棋。★ 优先 WASM 弈思(深度18)，原生兜底。 */
    private fun runAnalysisOnce(fen: String) {
        analysisBusy = true
        analysisSearchToken++
        analysisSearchStartMs = System.currentTimeMillis()
        val myGen = analysisGen
        val board = Board()
        try { board.loadFromFen(fen) } catch (e: Exception) { analysisBusy = false; return }
        if (!chessGame.engine.isEngineReadyFor(com.pikafish.chess.model.PieceColor.RED)) { analysisBusy = false; return }
        val player = board.currentPlayer
        val moverName = if (player == com.pikafish.chess.model.PieceColor.RED) "红方" else "黑方"
        val oppName = if (player == com.pikafish.chess.model.PieceColor.RED) "黑方" else "红方"
        val bestHolder = arrayOfNulls<com.pikafish.chess.model.Move>(1)
        val lastScHolder = IntArray(1) { 0 }
        val depthHolder = IntArray(1) { 0 }
        val lastPvHolder = arrayOfNulls<String>(1)

        // ★ 分析统一走原生询招沙盒（按深度18，与主界面同源评分，真实可信，绝不“深度0/乱报均势”）。
        chessGame.engine.searchBestMoveExcluding(
            color = player,
            board = board,
            excludedUci = emptySet(),
            timeMs = null,
            depth = 18,
            onThinking = { d, sd, sc, nodes, nps, pv, pvLines ->
                if (!analysisActive || myGen != analysisGen) return@searchBestMoveExcluding
                depthHolder[0] = sd
                lastScHolder[0] = sc
                lastPvHolder[0] = pv
                runOnUiThread {
                    if (!analysisActive || myGen != analysisGen) return@runOnUiThread
                    val sb = StringBuilder()
                    sb.append("<b>【分析】</b> 轮到 $moverName（人脑）思考…<br/>")
                    sb.append("深度 ${d}/${sd}　评分 <b style=\"color:${if (sc >= 0) "#C62828" else "#1B5E20"}\">${scoreText(sc)}</b>（当前轮到方视角）<br/>")
                    // ★ 节点/NPS 真实回显：引擎每上报一次思考即刷新（数值>=0 才显示，避免0节点/未就绪时刷0误导）
                    val nodesStr = (if (nodes > 0) "节点 ${fmtBig(nodes)}" else "")
                    val npsStr = (if (nps > 0) "　NPS ${fmtBig(nps)}/s" else "")
                    if (nodesStr.isNotBlank() || npsStr.isNotBlank()) {
                        sb.append("<font color=\"#888888\">$nodesStr$npsStr</font><br/>")
                    }
                    val pvCn = pvToCn(pv, fen)
                    if (pvCn.isNotBlank()) sb.append("推荐：$pvCn")
                    renderAnalysis(sb.toString(), selfContained = false)
                }
            },
            onResult = { best ->
                // ★ 只有当前代才允许清忙：旧代搜索的迟到结果绝不能误清新一代状态，
                //   否则新旧搜索会在锁上互相顶撞，导致分析越翻越卡甚至假死。
                if (myGen == analysisGen) analysisBusy = false
                bestHolder[0] = best
                if (!analysisActive || myGen != analysisGen) return@searchBestMoveExcluding
                runOnUiThread {
                    if (!analysisActive || myGen != analysisGen) return@runOnUiThread
                    // ★ 若引擎连一个“思考”结果都未提供（深度0/无推荐，多为引擎临时未就绪），
                    //   直接显示“等待引擎”，绝不把“0分/均势”当结论糊弄用户。
                    if (depthHolder[0] <= 0 && lastPvHolder[0].isNullOrBlank() && best == null) {
                        renderAnalysis(
                            "<b>【分析】</b> 轮到 $moverName（人脑）走棋<br/><font color=\"#888888\">引擎正在准备…请稍候（未取得有效评分）。</font>" +
                                "<br/><font color=\"#888888\">分析到此局面，走棋后自动继续。</font>",
                            selfContained = false
                        )
                        return@runOnUiThread
                    }
                    val sb = StringBuilder()
                    sb.append("<b>【分析】</b> 轮到 $moverName（人脑）走棋<br/>")
                    // ★ 终局检测：轮到的一方无子可走（被将死 / 困毙）即胜负已分、棋局结束，
                    //   不再套用普通评分文案报"均势"，避免绝杀/无子可走还被说成平局。
                    val cur = board.currentPlayer
                    val gameOver = board.isCheckmate(cur) || board.isStalemate(cur)
                    val (assessment, scoreStr) =
                        if (gameOver) Pair("胜负已分，棋局结束（$oppName 获胜）", scoreText(lastScHolder[0]))
                        else assessmentText(lastScHolder[0], moverName, oppName)
                    sb.append("深度 ${depthHolder[0]}　评分 <b>${scoreStr}</b>（当前轮到方为优则 +，否则对方优）<br/>")
                    sb.append("局面评估：<b>$assessment</b><br/>")
                    val best = bestHolder[0]
                    // ★ 让"引擎推荐"与"后续走势"保证同源一致：推荐首着 = 主变(PV)第一手。
                    val recUci: String? = run {
                        val pv = lastPvHolder[0]?.trim()
                        if (!pv.isNullOrBlank()) pv.split(" ").firstOrNull { it.length >= 4 }
                        else null
                    }
                    val uci = recUci ?: best?.toUci() ?: ""
                    if (uci.isNotBlank()) {
                        val bestNot = try { com.pikafish.chess.engine.CommentaryEngine.uciToChineseNotation(uci, board) } catch (e: Exception) { uci }
                        sb.append("引擎推荐：<font color=\"#2E7D32\"><b>$moverName $bestNot</b></font><br/>")
                    }
                    val pvCn = pvToCn(lastPvHolder[0] ?: "", fen)
                    if (pvCn.isNotBlank()) sb.append("后续走势：$pvCn<br/>")
                    sb.append("<font color=\"#888888\">分析到此局面，走棋后自动继续。</font>")
                    renderAnalysis(sb.toString(), selfContained = false)
                }
            }
        )
    }

    private fun updateSettingsUI() {
        isUpdatingUI = true
        etHash.setText(chessGame.currentHash.toString())
        etThreads.setText(chessGame.currentThreads.toString())
        isUpdatingUI = false
    }

    private fun updateEngineInfo() {
        val hash = chessGame.currentHash
        val threads = chessGame.currentThreads
        // ★ 顶栏跟随"当前真实引擎"：内置=皮卡鱼；装了外置引擎就报它的名字/日期
        val healthInfo = com.pikafish.chess.engine.EngineIdentity.topBarText(hash, threads)
        if (!tvEngineInfo.text.toString().contains("异常")) {
            tvEngineInfo.text = healthInfo
        }
    }

    /**
     * ★ 无条件把引擎健康文案写回状态栏（不经过 contains("异常") 守卫）。
     * 供引擎恢复流程调用，确保"异常/自动恢复…"措辞被真正覆盖，不留残留。
     */
    private fun applyEngineHealthText() {
        try {
            val hash = chessGame.currentHash
            val threads = chessGame.currentThreads
            tvEngineInfo.text = com.pikafish.chess.engine.EngineIdentity.topBarText(hash, threads)
        } catch (e: Exception) {
            Log.e(TAG, "applyEngineHealthText: ${e.message}", e)
        }
    }

    // ── 回调 ──

    private fun setupCallbacks() {
        // ★★ 左侧「书库」侧滑栏：初始化面板 + 文件选择器 + 打开按钮 ★★
        initBookPicker()
        setupBookDrawer()
        btnBookMenu.setOnClickListener { toggleBookDrawer() }

        chessGame.onBoardChanged = {
            runOnUiThread {
                chessBoard.setBoard(chessGame.board)
                chessBoard.setFlipped(chessGame.boardFlipped)
                // ★ 同步浏览历史状态：回到最新步时允许选子，浏览历史时不允许
                chessBoard.viewingHistory = chessGame.isViewingHistory()
                // ★ 修复：每次棋盘变化都重建光标，防止残留旧光标
                restoreHighlightsFromHistory()
                // ★ 修复：AI回合或引擎思考时清除选子，防止残留合法走法光标
                if (chessGame.isEngineThinking() || !chessGame.isCurrentPlayerHuman()) {
                    chessBoard.clearSelection()
                }
                updateStatus()
                updateStepInfo()
                refreshPracticeUi()
            }
        }

        chessGame.onAiTurnState = { _ ->
            // ★ 静默：AI交接状态（进场/离场）不再展示黑条，引擎内部自行处理，界面无打扰
        }

        chessGame.onThinkingInfo = { info ->
            // ★ 分析"神同步"开启时抑制原始走子思考直写 tvThinking：
            //   该屏已交由分析面板（onAnalysisFeed→renderAnalysis）统管，
            //   杜绝"分析一条/思考一条"互相顶屏、看不清分析。
            if (!analysisActive) {
                // ★★ 思考信息 UI 摘要通道（思考日志已按用户要求整体移除，询招架构一次一问、
                //   随问随走，不存在日志管理）。
                //   限频汇合、只保留最新一条——主线程永远只碰"当前一条"HTML，
                //   不拼接、不 append、不反复长文重排，从根上消除"思考越久越卡"。
                if (!thinkingUiPending) {
                    thinkingUiPending = true
                    thinkingUiLatest = info
                    thinkingHandler.postDelayed({
                        thinkingUiPending = false
                        val latest = thinkingUiLatest ?: return@postDelayed
                        thinkingUiLatest = null
                        if (chessGame.showThinkingDetails && latest != lastThinkingInfo) {
                            lastThinkingInfo = latest
                            tvThinking.visibility = View.VISIBLE
                            tvThinking.text = android.text.Html.fromHtml(latest, android.text.Html.FROM_HTML_MODE_LEGACY)
                            // ★ 浮透同步：tvThinking 每次刷新都必须同步到盘心浮透层，
                            //   否则浮透层会停在“打开开关那一刻”的内容上，走多少步都不变。
                            refreshFloatOverlay()
                        }
                    }, 100L)
                } else {
                    // 合并期内只留最新一条，上一条即被丢弃
                    thinkingUiLatest = info
                }
            }
        }

        // ★★★ 分析"神同步"直喂通道：主心骨（对弈走子搜索）的思考细节直接渲染成分析面板，
        //   分析模式全程复用主心骨结果，绝不再重复搜索——省算力、不抢线、走子不再被带跑。
        chessGame.onAnalysisFeed = { fen, d, sd, sc, nodes, nps, pv ->
            if (analysisActive) renderAnalysisFromSearch(fen, d, sd, sc, nodes, nps, pv, final = false)
        }
        chessGame.onAnalysisFeedResult = { fen, depth, sc, pv ->
            if (analysisActive) renderAnalysisFromSearch(fen, depth, depth, sc, 0L, 0L, pv, final = true)
        }

        chessGame.onAiMove = { move ->
            runOnUiThread {
                // ★ AI走棋后清除任何残留选子，防止多余光标
                chessBoard.clearSelection()
                // ★ 从实际走棋历史重建光标，确保只显示真实走法
                restoreHighlightsFromHistory()
            }
            // ★ 走子完成后的自动轻量清理（异步节流GC，防长时间AI对弈越积越卡）
            autoCleanupAfterMove()
        }


        // ★ onExplanationAppend 已废弃：不再直接操作View
        // 所有解说显示统一走 refreshExplanationDisplay()（由 onExplanationHistoryChanged 触发）
        // 这样只有一个出口，不会"抢进去"任何乌七八糟的东西
        chessGame.onExplanationAppend = { _ ->
            // 空实现：保留接口兼容，但什么都不做
            // 真正的刷新由 onExplanationHistoryChanged -> refreshExplanationDisplay 完成
        }

        // ★ 假设分析结果走独立通道，显示为半透明弹窗
        chessGame.onWhatIfResult = { text ->
            runOnUiThread {
                showWhatIfDialog(text)
            }
        }

        chessGame.onExplanationHistoryChanged = {
            runOnUiThread {
                refreshExplanationDisplay()
                updateStepInfo()
            }
        }

        chessGame.onAnalyzing = { analyzing ->
            runOnUiThread {
                if (analyzing) {
                    // ★ 不霸屏：只在状态栏末尾追加小字提示，不替换主状态
                    val currentStatus = tvStatus.text.toString()
                    if (!currentStatus.contains("评价中")) {
                        tvStatus.text = "$currentStatus ｜评价中…"
                    }
                } else {
                    updateStatus()  // 分析结束后恢复正常状态文字
                }
            }
        }

        chessGame.onEngineFailed = { error ->
            runOnUiThread {
                tvEngineInfo.text = "引擎不可用"
                AlertDialog.Builder(this)
                    .setTitle("引擎启动失败")
                    .setMessage("$error\n\n是否切换为无引擎模式（双人对弈）？\n界面与布局照常可用，仅 AI 不可走棋。")
                    .setNegativeButton("退 出") { _, _ -> finish() }
                    .setPositiveButton("切换无引擎模式") { _, _ -> enterDegradedNoEngineMode(error) }
                    .setCancelable(false)
                    .show()
            }
        }

        chessGame.onEngineRestarting = {
            runOnUiThread {
                tvStatus.text = "引擎重启中…"
                tvEngineInfo.text = "引擎异常，正在自动恢复…"
            }
        }

        // ★★★ 引擎恢复（无论成败）后，必须立即清除"异常/恢复中"残留。
        //   关键点：先把 tvEngineInfo 无条件写回正常健康文案（绕过两个
        //   contains("异常") 守卫），再走 updateStatus() 让主状态栏反映真实，
        //   避免出现"明明已恢复，字幕却永久停在【引擎异常，正在自动恢复…】"。
        chessGame.onEngineRecovered = {
            Log.i(TAG, "★ 引擎恢复回调触发，清除状态残留")
            runOnUiThread {
                applyEngineHealthText()
                updateStatus()
            }
        }

        chessGame.onSettingsChanged = {
            runOnUiThread {
                updateSettingsUI()
                updateEngineInfo()
            }
        }

        // ★ ECO开局识别回调 — 用Toast提示，不霸占状态栏
        chessGame.onOpeningIdentified = { openingName ->
            runOnUiThread {
                Toast.makeText(this, "开局: $openingName", Toast.LENGTH_LONG).show()
                updateStatus()  // ★ 保持状态栏正确显示
            }
        }

        chessGame.onRepetitionWarning = { msg ->
            runOnUiThread {
                // ★ 弹窗显示违规信息，用户手动关闭后继续走棋
                //   改用"薄纱透明"底：判例提示弹出时棋局必须仍能透视看到，不整块遮挡对局。
                val dlg = android.app.AlertDialog.Builder(this)
                    .setTitle("棋例提示")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .setCancelable(true)
                    .show()
                styleScrimDialog(dlg, 0xB3222222.toInt(), radiusDp = 18, dim = 0.22f)
                try {
                    dlg.findViewById<android.widget.TextView>(android.R.id.title)?.apply {
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                    }
                    dlg.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(0xFFE8E2D8.toInt())
                    dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFFB74D.toInt())
                } catch (e: Exception) {
                    Log.w(TAG, "棋例弹窗样式: ${e.message}")
                }
                updateStatus()
            }
        }

        chessGame.onGameOver = { result ->
            runOnUiThread {
                updateStatus()
                // ★ PK 比赛终局：走专用弹窗（记成绩 + 四勾换边续弈/采用并结束），
                //   绝不落到常规「再来一局/换边」，也不污染主界面的存档。
                if (pkRunning && chessGame.pkMode) {
                    showPkEndDialog(result)
                    return@runOnUiThread
                }
                saveGameState()
                // 使用自定义布局，带关闭按钮
                val dialogView = layoutInflater.inflate(R.layout.dialog_game_over, null)
                val tvGameResult = dialogView.findViewById<TextView>(R.id.tvGameResult)
                val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)
                tvGameResult.text = result

                val dialog = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setCancelable(true)
                    .create()
                // ★ 点弹窗外的任意位置即关（不必非找右上角那个✕）；返回键同样可关。
                dialog.setCanceledOnTouchOutside(true)

                // 关闭按钮（右上角X）：关闭弹窗，保留棋局供查看
                btnClose.setOnClickListener {
                    dialog.dismiss()
                }

                // 再来一局
                dialogView.findViewById<Button>(R.id.btnRematch).setOnClickListener {
                    dialog.dismiss()
                    chessBoard.setFlipped(chessGame.boardFlipped)
                    chessBoard.clearHighlights()
                    layoutExplanationList.removeAllViews()
                    layoutExplanationList.addView(tvExplanation)
                    tvExplanation.text = "走棋后这里会显示解说…"
                    chessGame.newGame()
                    saveGameState()
                }

                // 换边
                dialogView.findViewById<Button>(R.id.btnSwapSides).setOnClickListener {
                    dialog.dismiss()
                    chessGame.redIsHuman = !chessGame.redIsHuman
                    chessGame.blackIsHuman = !chessGame.blackIsHuman
                    updateSideButtons()
                    chessBoard.setFlipped(chessGame.boardFlipped)
                    chessBoard.clearHighlights()
                    layoutExplanationList.removeAllViews()
                    layoutExplanationList.addView(tvExplanation)
                    tvExplanation.text = "走棋后这里会显示解说…"
                    chessGame.newGame()
                    saveGameState()
                }

                dialog.show()
            }
        }

        chessGame.onSetupModeChanged = { inSetup ->
            runOnUiThread {
                if (inSetup) {
                    chessGame.setupFirstPlayer = PieceColor.RED
                    btnSetupFirst.text = "红先"
                    layoutSetupPanel.visibility = View.VISIBLE
                    layoutNormalControls.visibility = View.GONE
                    // ★ 摆子白纸干净化：进入摆子前记住解说/棋谱区与盘心浮透层的可见性，
                    //   进入后一律隐藏，绝不把后台思考/棋谱信息带进摆子界面占面积。
                    setupPrevExplanationVisible = scrollViewExplanation.visibility == View.VISIBLE
                    setupPrevFloatOverlayVisible = ::tvFloatOverlay.isInitialized && tvFloatOverlay.visibility == View.VISIBLE
                    tvThinking.visibility = View.GONE
                    if (::tvFloatOverlay.isInitialized) tvFloatOverlay.visibility = View.GONE
                    scrollViewExplanation.visibility = View.GONE
                    tvStatus.text = "摆子模式 - 点击棋子再点击棋盘放置"
                    chessBoard.onSetupTap = { row, col ->
                        handleSetupBoardTap(row, col)
                    }
                    // ★ 进入自由摆子模式：开启识别原图缩略图开关（不介入其他逻辑）
                    setupThumbFocused = true
                    // ★ 全屏方案：思考/解说区贴纸收成小条贴住底栏（主菜单已收掉），
                    //   棋盘几乎全露，方便在棋盘上自由摆子；起步高度按面板固定值
                    val slp = frameSetupThumb.layoutParams
                    if (slp is FrameLayout.LayoutParams) slp.height = dp(setupThumbStartDp)
                    updateStickerMargins()
                    // ★★ 铁板一块：进入摆子即按黑区收起态把「棋盘↔面板↔黑区」捆成严丝合缝的整块
                    syncSetupBundle()
                } else {
                    layoutSetupPanel.visibility = View.GONE
                    layoutNormalControls.visibility = View.VISIBLE
                    chessBoard.onSetupTap = null
                    chessBoard.setSetupValidPositions(null)
                    selectedPieceType = null
                    selectedPieceColor = null
                    updateSetupPieceSelection()
                    // ★ 摆子白纸退出：原样恢复进入前的解说/棋谱区与盘心浮透层可见性
                    scrollViewExplanation.visibility = if (setupPrevExplanationVisible) View.VISIBLE else View.GONE
                    if (::tvFloatOverlay.isInitialized) {
                        tvFloatOverlay.visibility = if (setupPrevFloatOverlayVisible) View.VISIBLE else View.GONE
                    }
                    refreshFloatOverlay()
                    if (!tvThinking.text.toString().contains("异常")) {
                        tvThinking.visibility = if (chessGame.showThinkingDetails) View.VISIBLE else View.INVISIBLE
                    }
                    // ★ 退出自由摆子模式：关闭缩略图开关并隐藏原图缩略图（释放内存）
                    setupThumbFocused = false
                    // ★★ 铁板一块：退出摆子，解除棋盘底边压缩，恢复自然尺寸
                    chessBoard.setBoardBottomLimit(Float.NaN)
                    setupThumbExpanded = false
                    setupThumbDragging = false
                    imgSetupThumb.visibility = View.GONE
                    btnSetupThumbShrink.visibility = View.GONE
                    // ★ 退出圈定态并隐藏圈定相关控件
                    setupCropModeOn = false
                    setupCropOverlay.clearAll()
                    setupCropOverlay.visibility = View.GONE
                    layoutSetupTools.visibility = View.GONE
                    btnSetupCrop.visibility = View.GONE
                    btnSetupCrop.text = "✏️工具"
                    btnSetupResubmit.visibility = View.GONE
                    setupThumbBaseHeightPx = -1f
                    setupThumbMaxHeightPx = -1f
                    setupThumbRawBitmap?.let { if (!it.isRecycled) it.recycle() }
                    setupThumbBitmap?.let { if (!it.isRecycled) it.recycle() }
                    setupThumbRawBitmap = null
                    setupThumbBitmap = null
                    fillSetupCompareImage(null)
                    // 恢复思考/解说区贴纸为默认固定高度（收起态），并重算贴纸边距（菜单已复原）
                    val flp = frameSetupThumb.layoutParams
                    if (flp is FrameLayout.LayoutParams) {
                        flp.height = dp(190)
                        frameSetupThumb.layoutParams = flp
                    }
                    updateStickerMargins()
                }
            }
        }

        chessGame.onSetupError = { error ->
            runOnUiThread {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }

        chessGame.onPlayerTypeChanged = {
            runOnUiThread {
                updateSideButtons()
                updateStatus()
            }
        }
    }

    private fun handleSetupBoardTap(row: Int, col: Int) {
        val type = selectedPieceType
        val color = selectedPieceColor

        if (type == null || color == null) {
            chessGame.setupRemovePiece(Position(row, col))
            return
        }

        val error = chessGame.setupPlacePiece(type, color, Position(row, col))
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    // ── 菜单栏按钮颜色批量更新（所有按钮）──

    /** 应用菜单底色：状态栏 + 底部导航条贴纸铺色；★ 主菜单面板「去纸」——不铺底色，
     *  按钮一个个直接浮在棋盘上（组件区域尺寸/排布逻辑分毫不动，只是不再画纸）。 */
    private fun applyMenuBgColor(color: Int) {
        findViewById<LinearLayout>(R.id.layoutStatusBar)?.setBackgroundColor(color)
        findViewById<LinearLayout>(R.id.layoutBottomNav)?.setBackgroundColor(color)
        // ★ 去纸：面板本体与其各分组容器一律透明；background=transparent 不参与测量，
        //   菜单总高、按钮定位、滚动区高度全部照旧 → 「组件的区域」逻辑依然存在。
        val normalControls = findViewById<LinearLayout>(R.id.layoutNormalControls)
        normalControls?.setBackgroundColor(Color.TRANSPARENT)
        if (normalControls != null) {
            for (i in 0 until normalControls.childCount) {
                val child = normalControls.getChildAt(i)
                if (child is LinearLayout) child.setBackgroundColor(Color.TRANSPARENT)
            }
        }
        val lum = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114)
        val fg = if (lum > 140) 0xFF2A2418.toInt() else 0xFFFFFFFF.toInt()
        runCatching {
            findViewById<android.widget.TextView>(R.id.tvStatus)?.setTextColor(fg)
            findViewById<android.widget.TextView>(R.id.tvEngineInfo)?.setTextColor(fg)
            findViewById<android.widget.TextView>(R.id.btnBookMenu)?.setTextColor(fg)
            // ★ 面板内普通文字随菜单底明暗自适应：暗底一律白色，保证对比强烈
            findViewById<android.widget.ScrollView>(R.id.scrollTabContent)?.let { applyMenuTextRecursive(it, fg) }
            findViewById<LinearLayout>(R.id.layoutNormalControls)?.let { applyMenuTextRecursive(it, fg) }
        }
    }

    /** 递归为菜单面板内的普通 TextView 套用前景色（按钮/输入框/下拉框各自单独处理，不在此覆盖）。 */
    private fun applyMenuTextRecursive(view: View, fg: Int) {
        if (view is android.widget.TextView && view !is android.widget.Button
            && view !is android.widget.EditText && view !is android.widget.Spinner) {
            view.setTextColor(fg)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyMenuTextRecursive(view.getChildAt(i), fg)
            }
        }
    }

    /** 递归为菜单块内的所有 LinearLayout 容器套用统一底色（保证切页/分组后颜色一致）。 */
    private fun applyMenuBgRecursive(view: View, color: Int) {
        view.setBackgroundColor(color)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is LinearLayout) applyMenuBgRecursive(child, color)
            }
        }
    }

    private fun updateAllMenuButtonColors(color: Int) {
        val colorStateList = android.content.res.ColorStateList.valueOf(color)
        // Tab栏按钮
        btnTabSettings.backgroundTintList = colorStateList
        btnTabGame.backgroundTintList = colorStateList
        btnTabNotation.backgroundTintList = colorStateList
        // 底部快捷栏
        btnNewGame.backgroundTintList = colorStateList
        btnFlip.backgroundTintList = colorStateList
        btnSetupMode.backgroundTintList = colorStateList
        btnAnalyze.backgroundTintList = colorStateList
        // ★ 补齐先前遗漏：信息 / 挑战 也要跟着“菜单钮”换色
        btnChallenge.backgroundTintList = colorStateList
        btnGameInfo.backgroundTintList = colorStateList
        // ★ 补齐：上一则 / 下一则 与左右箭头都统一换色
        btnQipuNext.backgroundTintList = colorStateList
        btnQipuPrev.backgroundTintList = colorStateList
        btnTagCurrent.backgroundTintList = colorStateList
        // 参数面板
        btnMode.backgroundTintList = colorStateList
        btnMinus.backgroundTintList = colorStateList
        btnPlus.backgroundTintList = colorStateList
        btnDetail.backgroundTintList = colorStateList
        btnAlwaysThink.backgroundTintList = colorStateList
        btnApplySettings.backgroundTintList = colorStateList
        btnTimeToggle.backgroundTintList = colorStateList
        btnEngineRecover.backgroundTintList = colorStateList
        // 对弈面板
        btnRedSide.backgroundTintList = colorStateList
        btnBlackSide.backgroundTintList = colorStateList
        btnCommentaryStyle.backgroundTintList = colorStateList
        btnBgSwitch.backgroundTintList = colorStateList
        btnGoBack.backgroundTintList = colorStateList
        btnGoForward.backgroundTintList = colorStateList
        btnGoStart.backgroundTintList = colorStateList
        btnGoEnd.backgroundTintList = colorStateList
        btnGuessMove.backgroundTintList = colorStateList
        btnPlaySpeed.backgroundTintList = colorStateList
        btnTimeToggle.backgroundTintList = colorStateList
        // 棋谱面板
        btnExportPgn.backgroundTintList = colorStateList
        btnExportFen.backgroundTintList = colorStateList
        btnExportTxt.backgroundTintList = colorStateList
        btnImportPgn.backgroundTintList = colorStateList
        btnImportFen.backgroundTintList = colorStateList
        btnImportTxt.backgroundTintList = colorStateList
        // 视觉面板
        btnOverlayCommentary.backgroundTintList = colorStateList
        // ★ 补齐：更多面板按钮 / 摆子模式翻转钮 一并换色
        btnAuthorIntent.backgroundTintList = colorStateList
        btnJoinGroup.backgroundTintList = colorStateList
        btnOtherWorks.backgroundTintList = colorStateList
        btnBgm.backgroundTintList = colorStateList
        if (::btnFreshLife.isInitialized) btnFreshLife.backgroundTintList = colorStateList
        if (::btnExternalReader.isInitialized) btnExternalReader.backgroundTintList = colorStateList
        btnSetupFlip.backgroundTintList = colorStateList
        // 摆子面板
        btnSetupReturn.backgroundTintList = colorStateList
        btnSetupClear.backgroundTintList = colorStateList
        btnSetupStandard.backgroundTintList = colorStateList
        btnSetupFirst.backgroundTintList = colorStateList
        btnSetupDone.backgroundTintList = colorStateList
        // ★ 红黑棋子为鼓型圆形样式，保持红/黑本色，不参与换色
        // ★ 输入框/下拉框与菜单按钮同色：底色用同一按钮色，文字一律纯白，显示成一体
        val fieldBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp4(5).toFloat()
            setColor(color)
            setStroke(dp4(1), 0xFFFFFFFF.toInt())
        }
        runCatching {
            // 数字输入框：选时、哈希、线程（棋盘/棋子大小已改为弹窗横屏滑条）
            listOf(etValue, etHash, etThreads).forEach { et ->
                et.background = fieldBg
                et.setTextColor(0xFFFFFFFF.toInt())
                et.setHintTextColor(0xAAFFFFFF.toInt())
            }
            // 下拉框：线色 / 线粗 / 菜单底 / 菜单钮
            listOf(spinnerLineColor, spinnerLineThickness, spinnerMenuBgColor, spinnerMenuBtnColor).forEach { sp ->
                sp.background = fieldBg
                (sp.selectedView as? android.widget.TextView)?.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    private fun updateStatus() {
        val game = chessGame
        // ★ 练开局已达成（人执一方下满约定手数）：无论引擎是否在跑、按钮被改成什么，
        //   顶栏一律显示"开局练习已结束"（人脑对人脑，彻底停摆，绝不再报"引擎启动中"）
        if (game.practiceActive && game.isPracticeComplete()) {
            tvStatus.text = "开局练习已结束"
            tvStatusEmoji.text = "✅"
        } else if (game.isSetupMode) {
            tvStatus.text = "摆子模式 - 点击棋子再点击棋盘放置"
            tvStatusEmoji.text = "♟️"
        } else if (game.isViewingHistory()) {
            // ★ 修复：查看历史时，如果轮到AI且AI已就绪，也允许触发AI走棋
            val canPlay = game.isCurrentPlayerHuman()
            val hint = if (canPlay) "（可直接走新变招）" else ""
            tvStatus.text = "查看历史 第${game.currentStep}/${game.totalSteps}步${hint}"
            tvStatusEmoji.text = "📜"
            // ★ 如果当前轮到AI，主动触发AI走棋（截断历史创建变招）
            if (!canPlay && !game.isEngineThinking() && game.isEngineReady && !game.gameOver) {
                Log.i(TAG, "★ 查看历史时检测到轮到AI，主动触发走棋")
                chessGame.checkAndTriggerAiMove()
            }
        } else if (game.gameOver) {
            tvStatus.text = game.gameResult
            tvStatusEmoji.text = "🏁"
        } else if (game.isEngineThinking()) {
            // ★★★ 恢复顶部状态栏（用户明确要求保留）：AI思考中显示"表情包 + 引擎思考中……"
            //   之前被静默成空，界面像"哑巴"一样不表态；现在恢复，让用户一眼看到引擎在工作。
            tvStatus.text = "引擎思考中……"
            tvStatusEmoji.text = "🤔"
        } else if (!game.engine.isEngineReady()) {
            tvStatus.text = "引擎启动中…"
            tvStatusEmoji.text = "⏳"
        } else {
            val isRed = game.board.currentPlayer == PieceColor.RED
            val human = if (isRed) game.redIsHuman else game.blackIsHuman
            if (human) {
                // 人脑回合
                tvStatus.text = if (isRed) "请红方走棋了" else "请黑方走棋了"
                tvStatusEmoji.text = if (isRed) "🔴" else "⚫"
            } else {
                // ★ AI回合但引擎未在思考 — 这是卡死状态！
                // 静默文案（不闪黑条），但保留"主动触发AI走棋"的兜底逻辑
                tvStatus.text = if (isRed) "红方走棋中…" else "黑方走棋中…"
                tvStatusEmoji.text = if (isRed) "🔴" else "⚫"
                // 主动触发AI走棋，防止"杵着不干活"
                if (!game.isEngineThinking() && game.isEngineReady && !game.gameOver) {
                    Log.w(TAG, "★ 检测到AI卡死状态（轮到AI但引擎未思考），主动触发")
                    chessGame.checkAndTriggerAiMove()
                }
            }
        }

        if (!tvEngineInfo.text.toString().contains("异常")) {
            updateEngineInfo()
        }
    }

    override fun onPause() {
        super.onPause()
        saveGameState()
        saveAllSettingsSync()
    }

    override fun onResume() {
        super.onResume()
        // ★ 沉浸式兜底：从后台/其他界面回到主界面时重落一次，防系统导航键"复现"
        applyImmersiveMode()
        // ★ 个性化：每次回到主界面套用当前「杂交」皮肤方案（若已导入）
        try { com.pikafish.chess.ui.SkinManager.applyTo(this, chessBoard) } catch (_: Exception) {}
        // ★★★ 修复"切屏/小退/关窗回来 AI 乱走"：回到前台立即重同步引擎状态——
        //   作废后台残留的陈旧搜索、复位思考开关、若轮到AI则用当前真实局面重新驱动；
        //   引擎进程若已被系统回收，立即重启续走（绝不超时兜底乱落子）。
        chessGame.onAppResumed()
    }

    /**
     * ★★ 首次聚焦收敛：让棋盘首帧就稳定。
     * 背景：首开时 frameBoardOuter 处于"权重(weight=1)"模式，棋盘高度要等用户点过
     * 「显/隐」触发 layout 变化后才被 tuneBoardVsThinking 收敛成固定高度——于是"第一下才
     * 锁脖子"。这里在首次聚焦后跑 3 趟收敛(间隔拉远)，让棋盘在用户手指碰「显/隐」之前
     * 就已经稳定到目标高度，杜绝第一次点击时屏幕/棋盘明显窜动。
     */
    private fun settleFirstLayout() {
        firstLayoutSettled = true
        if (!::frameBoardOuter.isInitialized) return
        try {
            frameBoardOuter.post {
                tuneBoardVsThinking()
                frameBoardOuter.postDelayed({ tuneBoardVsThinking() }, 60L)
                frameBoardOuter.postDelayed({ tuneBoardVsThinking() }, 180L)
                frameBoardOuter.postDelayed({ tuneBoardVsThinking() }, 420L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "settleFirstLayout failed: ${e.message}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // ★ 沉浸式：隐藏系统导航键（用户上滑可临时唤出），底部窄条贴屏幕最底、
            //    不与导航键冲突；导航键下方不放任何交互内容，最安全。
            //    ★ 这里同时兜住"弹窗（宝库等）关闭回到主界面"的情形：重新获得焦点即重落一次。
            applyImmersiveMode()
            applyNavBarInset()
            // ★★ 首次聚焦即收敛：让棋盘首帧就稳定，不再等用户点「显/隐」才"锁定脖子"。
            if (!firstLayoutSettled) settleFirstLayout()
        }
    }

    /** ★ 统一的沉浸式设置：隐藏状态栏/导航栏，且 IMMERSIVE_STICKY —— 用户临时上滑唤出后
     *   会自动再收起。onCreate / onResume / onWindowFocusChanged 三处一起落，避免任何一次
     *   焦点变化（弹窗、返回、切后台再回来）后系统三键"原形毕露"。 */
    private fun applyImmersiveMode() {
        try {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        } catch (e: Exception) {
            // 个别 ROM 差异时忽略，不影响功能
        }
    }

    /** ★ 边缘到边缘：状态栏内容避开状态栏；底部导航条背景贴到屏幕最底，
     *    按钮悬在导航键上方（背景延伸到底部、不在导航键下方画交互内容）。
     *    底距封顶，避免按钮被顶得太高；导航键隐藏时也预留手势条空间。 */
    private fun applyNavBarInset() {
        try {
            val content = findViewById<View>(android.R.id.content)
                ?: window.decorView.findViewById<View>(android.R.id.content)
            if (content == null) return
            ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                // 状态栏：内容下移，背景贴到屏幕最顶
                findViewById<View>(R.id.layoutStatusBar)?.let { sb ->
                    val h = sb.paddingLeft
                    sb.setPadding(h, statusBar, h, 0)
                }
                // 底部导航条：背景延伸到底，按钮悬在导航键上方；底距封顶 28dp 防“太靠上”
                findViewById<View>(R.id.layoutBottomNav)?.let { bn ->
                    val pad = if (navBar > 0) navBar.coerceAtMost(dp(28)) else dp(28)
                    bn.setPadding(0, 1, 0, pad + 1)
                }
                insets
            }
            content.requestApplyInsets()
        } catch (e: Exception) {
            // 布局/系统差异时忽略，不阻断
        }
    }

    /**
     * ★ 人脑对人脑提示弹窗：研究棋谱时点击没反应的说明
     * 含"不再提示"复选框
     */
    private fun showHvHHintDialog() {
        val context = this
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 外层垂直布局
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 12)
        }

        // 提示文本（可滚动）
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val tvMsg = TextView(this).apply {
            text = "对弈请点击旁边「对弈」页提前设置红黑双方；\n" +
                    "完成一局点「下一则」，自动进入下一局。\n" +
                    "研究拆解也是同理。\n" +
                    "切换主菜单区域不影响当前设置。"
            setTextColor(Color.parseColor("#F0E8D0"))
            textSize = 15f
            setLineSpacing(8f, 1.25f)
            setPadding(16, 12, 16, 16)
        }
        scrollView.addView(tvMsg)
        container.addView(scrollView)

        // "不再提示"复选框
        val cbDontShow = android.widget.CheckBox(this).apply {
            text = "不再提示"
            setTextColor(Color.parseColor("#C0B8A0"))
            textSize = 13f
            setPadding(16, 8, 16, 16)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_HINT_HVH_DISMISSED, isChecked).apply()
            }
        }
        container.addView(cbDontShow)

        // 构建弹窗
        val dialog = AlertDialog.Builder(this)
            .setTitle("操作提示")
            .setView(container)
            .setPositiveButton("知道了") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .create()

        dialog.setOnShowListener {
            dialog.window?.let { window ->
                // 淡暖褐半透明底，透出下层棋盘
                window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#7A2A2218")))
                val dm = resources.displayMetrics
                window.setLayout((dm.widthPixels * 0.82).toInt(), (dm.heightPixels * 0.42).toInt())
            }
            // 标题颜色
            try {
                val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
                val titleView = dialog.findViewById<TextView>(titleId)
                titleView?.setTextColor(Color.parseColor("#E8D8A8"))
            } catch (_: Exception) {}
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    /**
     * ★ 假设分析/猜谱结果弹窗：半透明、大面积、点击外部关闭
     */
    private fun showWhatIfDialog(text: String) {
        // 先关闭旧弹窗
        whatIfDialog?.dismiss()

        // 创建 ScrollView + TextView 组合，内容多时可滚动
        val scrollView = android.widget.ScrollView(this).apply {
            setPadding(48, 36, 48, 36)
        }
        val textView = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#F0E8D0"))
            textSize = 14f
            setLineSpacing(6f, 1.2f)
            setPadding(24, 16, 24, 16)
        }
        scrollView.addView(textView)

        // ★ 构建半透明弹窗
        val dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setCancelable(true)
            .create()

        dialog.setOnShowListener {
            dialog.window?.let { window ->
                // 淡暖褐半透明底，透出下层棋盘
                window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#6E2A2218")))
                // 弹窗面积更大：占屏幕宽度 88%，高度最高 60%
                val dm = resources.displayMetrics
                window.setLayout((dm.widthPixels * 0.88).toInt(), (dm.heightPixels * 0.60).toInt())
            }
        }

        // ★ 点击弹窗外部关闭，点击内部不关闭
        dialog.setCanceledOnTouchOutside(true)

        dialog.show()
        whatIfDialog = dialog
    }

    override fun onDestroy() {
        super.onDestroy()
        // ★ 清理画中画广播接收器
        try { pipReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        pipReceiver = null
        // ★ 清理时间刷新定时器
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable)
        // ★ 关闭弹窗
        whatIfDialog?.dismiss()
        // ★ 释放作者寄语播放器
        try { stopAuthorMessage() } catch (_: Exception) {}
        // ★ 释放背景音乐（选择已持久化，下次启动自动续播）
        try { com.pikafish.chess.utils.SoundManager.stopBgm() } catch (_: Exception) {}
        saveGameState()
        saveAllSettingsSync()
        initScope.cancel()
        chessGame.stopWatchdog()
        chessGame.destroy()
    }
}