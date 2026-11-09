package com.pikafish.chess.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 象棋棋盘自定义视图
 */
class ChessBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BOARD_ROWS = 10
        private const val BOARD_COLS = 9

        /** ★ 棋子皮肤资源名片段（6 套 3D 立体皮肤，顺序与界面「皮肤:xx」循环一致）：
         *    6=璀璨黄金 7=水晶琉璃 8=烈焰木炭 9=鼓形雕花 10=羊脂白玉 11=青花鼓形 */
        private val SKIN_TAGS = listOf("6", "7", "8", "9", "10", "11")

        /** ★ 棋子健康参考值(≈103%)：直径≈0.97格，格间留约3%小空隙——匀称不互挤。
         *   由用户核心算法反推（将/帅居中，半边棋盘分配 4.5 颗子直径）：
         *     恰贴(零空隙)缩放 = 4 / (4.5×0.94 − 0.47) ≈ 1.064（直径=1格，互不叠）
         *     留 3% 小空隙 → 1.064×0.97 ≈ 1.032，直径≈0.97格
         *   ★ 它只是「大而美」归一化等处的健康参考值，不是渲染硬上限：
         *   棋子滑条/请求值（0.5~1.5）直接线性映射渲染直径——棋盘纹丝不动，
         *   棋子以自身圆心为锚点原地变大（吃掉格间缝隙、间距自然变小），
         *   该多大就多大，绝不外扩棋盘、不偏心、不顶外框。 */
        const val HEALTHY_PIECE_SCALE = 1.032f

        /** ★ 棋子滑条/请求值上限（150%）：直接线性映射渲染，不设健康封顶 */
        const val MAX_PIECE_INPUT_SCALE = 1.5f

        /** ★「棋盘坐标」纵向偏移软上限（px）：从半格放宽到一大段，够把棋盘从容拉上/拉下、
         *   专治矮屏、异形屏上"整盘离底太远/想再往下放却卡死在160px"的憋屈。默认0=跟随可视带锚定 */
        const val MAX_Y_OFFSET_PX = 2000f
    }

    /** 盘内讲解三档模式 */
    enum class OverlayMode {
        OFF,    // 关：不显示
        ON,     // 开：始终显示
        TAP     // 点动：点击棋盘弹出，带叉关闭
    }

    /** 背景模式 */
    enum class BgMode(val label: String) {
        SOLID("纯色"), BAMBOO("竹林"), PORCELAIN("青花瓷"),
        WOOD("木纹暖棕"), PARCHMENT("羊皮纸"), GREEN("墨绿绒"),
        BLUE_GRAY("深蓝灰"), MARBLE("暖灰大理石"),
        ROSE_GOLD("玫瑰金"), FOREST("深林绿"), OCEAN("海洋蓝"),
        COFFEE("咖啡棕"), SLATE("石板灰"), AMBER("琥珀色"),
        BURGUNDY("勃艮第红"), CHARCOAL("炭黑")
    }

    /** ★ 桌沿花纹（照片画框式花边）：棋桌是棋布的"边框"，所以除了纯色还要能给花纹。
     *  全部纯代码矢量画法，不占资源、随棋盘等比缩放。
     *  ★★ 关键风格：**写实多色 + 间隙性循环** ——
     *     框上是一幅「画」：绿叶是真绿、花有粉/红/黄/白、蔬果有白菜/茄子/辣椒/胡萝卜；
     *     元素沿带按「疏朗 + 抖动」排布（间距/大小/朝向/花色都随机），绝不机械等距重复，
     *     尤其柳条、缠枝这种自然物，宁可长短错落，也不搞"打毛衣"式的均匀密排。 */
    enum class TablePattern(val label: String) {
        NONE("素面"),
        GARLAND("缠枝花"),  // 手绘缠枝：细藤 + 粉/红/黄/白五瓣花 + 绿叶 + 花苞（主推）
        FLORAL("折枝花"),   // 折枝：一枝斜出，枝头大花 + 两叶，间距最大最疏朗
        VINE("藤蔓"),       // 长藤 + 间挂叶/花苞/小花，最简最透气
        WILLOW("柳条"),     // 垂柳：一二条柳丝自然垂拂，柳叶错落挂满，最写实
        VEGGIE("蔬果"),     // 田园：白菜—茄子—辣椒—胡萝卜 一串挂满，颜色最丰富
        BIRD("花鸟"),       // 花 + 翠鸟 + 叶 交替
        FRUIT("花果"),      // 花 + 红果 + 叶 交替
        CLOUD("云纹"),      // 如意云头：大小云头交替（青花/朱漆的传统边饰）
        MEANDER("回纹")     // 方折雷纹（青花/朱漆的传统边饰）
    }

    /** 花纹带上的元素种类（内部用，不与 UI 挂钩） */
    private enum class Motif { FLOWER, FLOWER_S, LEAF, LEAF_L, BUD, TENDRIL, SPROUT, BIRD, FRUIT, CLOUD, CLOUD_S }

    /** 棋桌（棋盘纸下方衬台）风格：只选样式、固定比例随棋盘缩放。
     *  pattern=桌沿花纹；patternInk=花纹墨色（0=用 edge 色自动）。 */
    enum class TableStyle(
        val label: String, val top: Int, val mid: Int, val low: Int, val edge: Int,
        val pattern: TablePattern = TablePattern.NONE, val patternInk: Int = 0
    ) {
        ROSEWOOD("红酸枝", 0xFF8C4A2B.toInt(), 0xFF7A3F22.toInt(), 0xFF6A341A.toInt(), 0xFFD9A878.toInt()),
        WALNUT("深胡桃", 0xFF6B4A32.toInt(), 0xFF5A3C26.toInt(), 0xFF4A2E1A.toInt(), 0xFFC8A878.toInt()),
        GOLD_SANDAL("金檀", 0xFFB8863B.toInt(), 0xFFA8762E.toInt(), 0xFF966620.toInt(), 0xFFEEDFA0.toInt()),
        OAK("暖橡木", 0xFFC8956E.toInt(), 0xFFB8875F.toInt(), 0xFFA6784E.toInt(), 0xFFF0DCC0.toInt()),
        JADE("墨玉", 0xFF3E5C54.toInt(), 0xFF33504A.toInt(), 0xFF28443E.toInt(), 0xFF8FB8AC.toInt()),
        SLATE_BLUE("青石台", 0xFF5A6674.toInt(), 0xFF4C5764.toInt(), 0xFF3E4854.toInt(), 0xFFAAB6C4.toInt()),
        RED_BLACK("黑檀红", 0xFF4A2020.toInt(), 0xFF3A1818.toInt(), 0xFF2E1010.toInt(), 0xFFC88080.toInt()),
        RETRO("古案木", 0xFF6E5A3E.toInt(), 0xFF5E4A30.toInt(), 0xFF4E3A24.toInt(), 0xFFD8C4A0.toInt()),
        // ★ 浅色系（用户点名多上浅色，干净敞亮）
        LIGHT_OAK("浅橡木", 0xFFC9A879.toInt(), 0xFFBE9A6E.toInt(), 0xFFB08A5E.toInt(), 0xFFF0DDC0.toInt()),
        LIGHT_BIRCH("米白桦木", 0xFFD9C9A8.toInt(), 0xFFCFBE9C.toInt(), 0xFFC0AE8C.toInt(), 0xFFF4EAD5.toInt()),
        IVORY("象牙白", 0xFFE7DCC0.toInt(), 0xFFDDD0B2.toInt(), 0xFFCFC0A0.toInt(), 0xFFFBF5E8.toInt()),
        MAPLE("暖白枫", 0xFFE0CDA4.toInt(), 0xFFD5C092.toInt(), 0xFFC5AF7E.toInt(), 0xFFF7EBD2.toInt()),
        SANDAL_LIGHT("素檀浅", 0xFFC2A97E.toInt(), 0xFFB59A6D.toInt(), 0xFFA2885C.toInt(), 0xFFEDD9B8.toInt()),
        PALE_TEAK("淡柚木", 0xFFC7A477.toInt(), 0xFFBC9B6C.toInt(), 0xFFAC8A5C.toInt(), 0xFFF0DCC0.toInt()),
        LINEN_WOOD("亚麻原木", 0xFFD8C7A4.toInt(), 0xFFCDBB96.toInt(), 0xFFBEAA84.toInt(), 0xFFF2E7CE.toInt()),
        // ★ 花哨款（雕龙雕凤／描金描银，走深底金银反差）
        DRAGON_GOLD("雕龙金檀", 0xFFB8863B.toInt(), 0xFFA8762E.toInt(), 0xFF966620.toInt(), 0xFFF7D16B.toInt()),
        PHOENIX_RED("雕凤朱漆", 0xFF9A2E2E.toInt(), 0xFF8A2525.toInt(), 0xFF781E1E.toInt(), 0xFFE8A3A3.toInt()),
        GILDED_PURPLE("鎏金紫檀", 0xFF7A4A6D.toInt(), 0xFF6B3F5E.toInt(), 0xFF5A334E.toInt(), 0xFFE8C98F.toInt()),
        SILVER_EBONY("描银黑檀", 0xFF4A4A4E.toInt(), 0xFF3D3D42.toInt(), 0xFF303036.toInt(), 0xFFD9DCE3.toInt()),
        // ★★ 花纹边框桌布（用户点名）：桌台＝棋布的画框，所以框上要走花纹。
        //    纹样统一走「间歇性缠枝」（疏朗成串、不密排），深浅底色 × 冷暖墨色自由组合，
        //    青花系／金檀系／朱漆系／墨玉系／素檀象牙系／玫瑰炭黑系各来几款 → 有复合层次、不单一。
        PORC_GARLAND("青花缠枝", 0xFF7E95B0.toInt(), 0xFF6C86A6.toInt(), 0xFF5A7798.toInt(), 0xFFEAF1F7.toInt(),
            TablePattern.GARLAND, 0xFFF4F8FC.toInt()),
        PORC_VINE("青花藤蔓", 0xFF5E7A98.toInt(), 0xFF506C8A.toInt(), 0xFF435F7C.toInt(), 0xFFE6EEF6.toInt(),
            TablePattern.VINE, 0xFFF2F7FC.toInt()),
        PORC_FLORAL("青花折枝", 0xFF93A9C0.toInt(), 0xFF8299B3.toInt(), 0xFF7089A6.toInt(), 0xFFF7FAFD.toInt(),
            TablePattern.FLORAL, 0xFFFCFEFF.toInt()),
        GOLD_GARLAND("金檀缠枝", 0xFFB8863B.toInt(), 0xFFA8762E.toInt(), 0xFF966620.toInt(), 0xFFF3DFA2.toInt(),
            TablePattern.GARLAND, 0xFFFBEEC2.toInt()),
        GOLD_BIRD("金檀花鸟", 0xFFA8762E.toInt(), 0xFF976722.toInt(), 0xFF84581A.toInt(), 0xFFF7D16B.toInt(),
            TablePattern.BIRD, 0xFFFFE9A8.toInt()),
        CINNABAR_FRUIT("朱漆花果", 0xFF9A2E2E.toInt(), 0xFF8A2525.toInt(), 0xFF781E1E.toInt(), 0xFFEFC9A8.toInt(),
            TablePattern.FRUIT, 0xFFF6D9BC.toInt()),
        CINNABAR_CLOUD("朱漆云纹", 0xFF7E2424.toInt(), 0xFF6E1D1D.toInt(), 0xFF5E1717.toInt(), 0xFFE8A3A3.toInt(),
            TablePattern.CLOUD, 0xFFF2C4C4.toInt()),
        JADE_GARLAND("墨玉缠枝", 0xFF3E5C54.toInt(), 0xFF33504A.toInt(), 0xFF28443E.toInt(), 0xFFA8CFC2.toInt(),
            TablePattern.GARLAND, 0xFFBEE0D4.toInt()),
        JADE_CLOUD("墨玉云纹", 0xFF33504A.toInt(), 0xFF2B4640.toInt(), 0xFF223A35.toInt(), 0xFF9AC4B6.toInt(),
            TablePattern.CLOUD, 0xFFB0D6C8.toInt()),
        SLATE_MEANDER("青石回纹", 0xFF5A6674.toInt(), 0xFF4C5764.toInt(), 0xFF3E4854.toInt(), 0xFFC6D2DE.toInt(),
            TablePattern.MEANDER, 0xFFD6E0EA.toInt()),
        SLATE_GARLAND("青石缠枝", 0xFF4C5764.toInt(), 0xFF414B57.toInt(), 0xFF35404A.toInt(), 0xFFB8C6D4.toInt(),
            TablePattern.GARLAND, 0xFFCAD6E2.toInt()),
        SANDAL_FLORAL("素檀折枝", 0xFFC2A97E.toInt(), 0xFFB59A6D.toInt(), 0xFFA2885C.toInt(), 0xFF7A6340.toInt(),
            TablePattern.FLORAL, 0xFF6E5836.toInt()),
        IVORY_GARLAND("象牙缠枝", 0xFFE7DCC0.toInt(), 0xFFDDD0B2.toInt(), 0xFFCFC0A0.toInt(), 0xFF9C8558.toInt(),
            TablePattern.GARLAND, 0xFF9C8558.toInt()),
        IVORY_FRUIT("象牙花果", 0xFFDDD0B2.toInt(), 0xFFD2C4A4.toInt(), 0xFFC4B492.toInt(), 0xFF8C7548.toInt(),
            TablePattern.FRUIT, 0xFF8C7548.toInt()),
        ROSE_GARLAND("玫瑰缠枝", 0xFF8A5A6E.toInt(), 0xFF7A4C60.toInt(), 0xFF6A4052.toInt(), 0xFFEBC7D2.toInt(),
            TablePattern.GARLAND, 0xFFF3D6DF.toInt()),
        // ★★ 写实多色款（用户点名：要真颜色、要写实、不要素描）：
        //    柳条／蔬果／花鸟走真彩（绿叶/彩花/蔬果本色），深浅底任意搭。
        PORC_WILLOW("青花柳条", 0xFF8FA6BC.toInt(), 0xFF7E97AF.toInt(), 0xFF6C87A2.toInt(), 0xFFF2F7FB.toInt(),
            TablePattern.WILLOW),
        PORC_VEGGIE("青花蔬果", 0xFF93A9C0.toInt(), 0xFF8299B3.toInt(), 0xFF7089A6.toInt(), 0xFFF7FAFD.toInt(),
            TablePattern.VEGGIE),
        GARDEN_VEGGIE("田园蔬果", 0xFFC9A879.toInt(), 0xFFBE9A6E.toInt(), 0xFFB08A5E.toInt(), 0xFFF0DDC0.toInt(),
            TablePattern.VEGGIE),
        IVORY_WILLOW("象牙柳条", 0xFFE0D2B2.toInt(), 0xFFD6C6A4.toInt(), 0xFFC8B694.toInt(), 0xFFF8F1E2.toInt(),
            TablePattern.WILLOW),
        JADE_WILLOW("墨玉柳条", 0xFF3E5C54.toInt(), 0xFF33504A.toInt(), 0xFF28443E.toInt(), 0xFFA8CFC2.toInt(),
            TablePattern.WILLOW),
        CINNABAR_BIRD("朱漆花鸟", 0xFF9A2E2E.toInt(), 0xFF8A2525.toInt(), 0xFF781E1E.toInt(), 0xFFEFC9A8.toInt(),
            TablePattern.BIRD),
        GOLD_FRUIT("金檀花果", 0xFFB8863B.toInt(), 0xFFA8762E.toInt(), 0xFF966620.toInt(), 0xFFF3DFA2.toInt(),
            TablePattern.FRUIT);

        companion object {
            fun labels(): Array<String> = entries.map { it.label }.toTypedArray()
            fun from(index: Int): TableStyle = entries.getOrNull(index.coerceIn(0, entries.size - 1)) ?: MAPLE
        }
    }

    // 棋盘参数
    private var boardLeft = 0f
    private var boardTop = 0f
    private var cellSize = 0f
    private var pieceRadius = 0f
    private var boardPaddingH = 0f   // 水平边距
    private var boardPaddingTop = 0f  // 上方（含刻度标签）
    private var boardPaddingBot = 0f  // 下方（含刻度标签）

    // ★ 全屏贴纸方案：棋盘可视带（px）——顶栏贴纸底边 ↔ 主菜单贴纸顶边。
    //   由 MainActivity 按贴纸高度一次算好；显/隐、摆子切换不重算 → 棋盘纹丝不动、只动贴纸。
    private var contentBandTop = 0f
    private var contentBandBottom = 0f
    private var contentBandValid = false

    // ★ 调试定位（临时面板用）：用户手动填的棋盘左上角坐标（px）。
    //   NaN = 未设置，走自动定位（底部锚定）；设置后强制使用该坐标，直到清除。
    //   ★ 统一用自动定位：
    //     水平：NaN → 始终“左右等距居中”（棋盘不管多宽，左/右空隙都一样，彻底消除单侧悬空感，
    //           挑战/背谱/解惑一个样，不再出现右宽左窄的偏移）；
    //     垂直：Y=210 维持实测锚点，让红方底排贴住底栏上方。
    private var debugPosLeft = Float.NaN
    private var debugPosTop = 210f

    // ★★ 「棋盘坐标」用户微调纵向偏移（px）：正值=棋盘整体下移、负值=上移。
    //   作用在 recalcLayout 最后的 boardTop 上，只整体平移、不改变格子/棋子大小、
    //   不参与 bandFitCell 计算 → 不破坏「大而美/滑条」的尺寸与爆屏防护；默认 0=不改行为。
    private var boardYOffset = 0f

    // ★★ 摆子「铁板一块」压缩：黑区(识别原图)上顶时，棋盘以「顶排」为锚等比缩小——
    //   顶排那条线守着位置不动（不往前走、不往后缩），下面各行向顶排方向均匀挤压，
    //   棋盘高度/宽度/棋子全部等比例变小；配合面板同步上移，形成棋盘↔面板↔黑区
    //   严丝合缝的整块，绝不留大片空白、绝不互相遮盖。
    //   boardBottomLimitPx = 棋盘底边（含底部刻度标签）最大允许 Y（px）；NaN = 不压缩。
    private var boardBottomLimitPx = Float.NaN
    private var topAnchorScale = 1f   // 顶排锚定压缩比例（1=不压缩；<1 向下均匀挤压）

    /** ★★ 摆子「铁板一块」：设置棋盘底边（含底部刻度标签）最大允许 Y（px）；NaN=不压缩。
     *   压缩以「最上一排」为锚：顶排线守住原位不动，棋盘/棋子/刻度向下均匀等比缩小，
     *   底边恰好停在摆子面板顶上沿。由 MainActivity 在黑区(识别原图)上顶时调用。 */
    fun setBoardBottomLimit(limitPx: Float) {
        val nl = if (limitPx.isNaN() || limitPx <= 0f) Float.NaN else limitPx
        val changed = nl.isNaN() != boardBottomLimitPx.isNaN() ||
            (!nl.isNaN() && kotlin.math.abs(nl - boardBottomLimitPx) > 0.5f)
        if (changed) {
            boardBottomLimitPx = nl
            if (width > 0 && height > 0) {
                recalcLayout(width, height)
                invalidate()
            }
        }
    }

    // ── 视觉缩放参数 ──
    // 绝对像素尺寸（优先于比例缩放）：存储卡尺级精确像素值，强退后原样恢复
    private var absoluteCellSize = 0f       // 绝对格子尺寸（px），0=未设置，用比例计算

    // ★ 滑杆实时棋盘倍率(-1=未启用；启用时 cellSize = 可视带基准格 × 该倍率，直达渲染)。
    //   与 absoluteCellSize 互斥：滑杆走 liveBoardScale 实时改、不被可视带钳死；
    //   大而美走 absoluteCellSize 像素钉死(liveBoardScale 复位为 -1)。
    private var liveBoardScale = -1f
    private var pendingRatioApply = -1f     // ★ 待应用比例：布局未就绪(基准格未测得)时先存，首次测得后自动落位
    private var baseCellSize = 0f           // 100%基准格子尺寸（px），由屏幕尺寸算出
    private var boardScale = 1.0f      // 棋盘缩放比例（仅用于输入框联动，实际渲染用absoluteCellSize）
    private var boardSliderScale = 1.0f // 「棋盘大小」滑条的独立锚点值（0.5~2.0）
    private var pieceScale = 1.0f      // 棋子缩放请求值（滑条值 0.5~1.5；直接线性映射渲染直径，棋盘不动）
    private var lineColor = Color.parseColor("#3A3020")  // 棋线颜色（可变）
    private var lineThickness = 1.5f   // 棋线粗细（1.0f~4.0f）

    // ── 盘内讲解浮层（3行交替历史） ──
    private val overlayLines = mutableListOf<String>()  // 最多3行
    private var overlayMode = OverlayMode.OFF
    private var tapOverlayVisible = false  // 点动模式：当前是否正在显示
    private var tapOverlayBounds: RectF? = null  // 点动浮层边界（用于点击检测）
    private var tapCloseBounds: RectF? = null   // 点动浮层关闭按钮边界
    private var tapZoneBounds: RectF? = null    // 点动触发区域（底部讲解区）
    private var overlayScrollOffset = 0f        // 滚屏偏移量
    private var overlayTotalContentHeight = 0f  // 浮层内容总高度
    private var overlayVisibleHeight = 0f       // 浮层可视高度
    private var lastTouchY = 0f                 // 上一次触摸Y坐标（用于滚屏）
    private var isScrolling = false             // 是否正在滚屏

    // ── 背景模式 ──
    private var bgMode = BgMode.SOLID

    // ── 时间显示 ──
    private var showTime = false  // 是否在楚河汉界显示当前时间

    // ── 棋桌（棋盘纸下方衬台）──
    private var tableStyle: TableStyle = TableStyle.MAPLE   // ★ 默认暖白枫（浅色干净，第一印象敞亮）
    private var tableMarginFactor = 0.55f   // 桌台相对棋盘纸的外扩倍率（木条子≈半格宽，包边更窄）
    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val patternBandPath = Path()    // 复用：桌沿花纹的路径（每帧重建，省 GC）

    // ★★ 桌沿花纹＝「画框」：先把框立起来（浅色托纸 + 内外两道细硬轨），再在托纸上作画。
    //    —— 画面走写实多色（绿叶／粉花／红黄花／蔬果／柳条），托纸浅、轨线深、花叶艳，
    //       框是框、花是花，层层分明，绝不糊成一团。
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)          // 托纸 / 轨线
    private val motifFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val motifInkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val motifPath = Path()          // 复用：写实花纹的临时路径
    private val motifRect = RectF()         // 复用：写实花纹的临时矩形

    // 当前花纹带的画布变换（drawBand 每带写入）：用于把「蔬果」等有明确上下的元件
    // 反算回全局落点并直立绘制 —— 左右两带虽被旋转90°，菜还是正着挂在框上。
    private var bandOx = 0f
    private var bandOy = 0f
    private var bandCos = 1f
    private var bandSin = 0f
    private var bandMirror = false

    /** 把花纹带局部坐标 (x,y) 映射回屏幕全局 X。 */
    private fun bandGX(x: Float, y: Float): Float {
        val yy = if (bandMirror) -y else y
        return bandOx + x * bandCos - yy * bandSin
    }

    /** 把花纹带局部坐标 (x,y) 映射回屏幕全局 Y。 */
    private fun bandGY(x: Float, y: Float): Float {
        val yy = if (bandMirror) -y else y
        return bandOy + x * bandSin + yy * bandCos
    }

    // ── 点动/盘内讲解浮层字号倍率（滑杆可调）──
    private var tapFontScale = 1.0f

    // 颜色（高端木纹配色）
    private var boardBgColor = Color.parseColor("#E8D5A3")   // 纯色背景色（可调）
    private val pieceRedFill = Color.parseColor("#FFF5E8")
    private val pieceRedStroke = Color.parseColor("#B01818")
    private val pieceRedText = Color.parseColor("#B01818")
    private val pieceBlackFill = Color.parseColor("#F0F0F0")
    private val pieceBlackStroke = Color.parseColor("#1A1A1A")
    private val pieceBlackText = Color.parseColor("#1A1A1A")
    private val selectedHighlight = Color.parseColor("#66FF4444")
    private val lastMoveHighlight = Color.parseColor("#66FFD700")
    // ★ 黑方（AI走棋）也使用橙色高亮，与红方保持一致
    private val aiMoveHighlight = Color.parseColor("#66FF8C00")
    private val legalMoveDot = Color.parseColor("#66000000")
    private val legalMoveCapture = Color.parseColor("#66FF0000")
    private var labelColor = Color.parseColor("#5C4A2A")   // 刻度文字颜色（可变，调色板可改）

    // ── 走棋光标历史 ──
    // 保留最近4步棋的起点和终点（共8个光标点），交替显示
    // 红方上一步、红方这一步、黑方上一步、黑方这一步
    private data class MoveHighlight(val move: Move, val isAi: Boolean)
    private val moveHighlightHistory = mutableListOf<MoveHighlight>()
    private val MAX_HIGHLIGHT_MOVES = 1

    // 棋盘数据
    private var board: Board? = null
    private var flipped: Boolean = false
    private var selectedPosition: Position? = null
    private var legalMoves: List<Move> = mutableListOf()

    // ── 背景图支持 ──
    private var backgroundBitmap: Bitmap? = null
    private var backgroundResId: Int = 0

    // ── 「个性化」外置皮肤：红/黑棋子位图（可跨包混配）+ 洗净背景衬体 ──
    private var customRedPieces: Map<PieceType, Bitmap> = emptyMap()
    private var customBlackPieces: Map<PieceType, Bitmap> = emptyMap()
    private var customBg: Bitmap? = null

    /** 套用「个性化」棋子（红/黑可来自不同 Zip 包）；传空 Map 即回归内置棋子。 */
    fun setCustomPieces(red: Map<PieceType, Bitmap>, black: Map<PieceType, Bitmap>) {
        customRedPieces = red
        customBlackPieces = black
        pieceBitmapCache.clear()
        invalidate()
    }
    fun clearCustomPieces() { customRedPieces = emptyMap(); customBlackPieces = emptyMap(); pieceBitmapCache.clear(); invalidate() }

    /** 套用「个性化」洗净棋盘衬体（无经纬线/坐标）；传 null 即回归内置盘面。 */
    fun setCustomBackground(bmp: Bitmap?) {
        customBg?.recycle()
        customBg = bmp
        invalidate()
    }

    // ── 牛津棋子图片缓存 ──
    private val pieceBitmapCache = mutableMapOf<Int, Bitmap?>()

    // ── 棋子皮肤：资源名片段（全套 6 种 3D 立体皮肤）──
    // "6"=璀璨黄金 "7"=水晶琉璃 "8"=烈焰木炭 "9"=鼓形雕花 "10"=羊脂白玉 "11"=青花鼓形
    private var pieceSkinTag = SKIN_TAGS[0]
    private val pieceSkinNames = listOf(
        "璀璨黄金", "水晶琉璃", "烈焰木炭", "鼓形雕花", "羊脂白玉", "青花鼓形")

    /** 设置棋子皮肤：index 对应 pieceSkinNames（0..5） */
    fun setPieceSkin(index: Int) {
        val tag = SKIN_TAGS[index.coerceIn(0, SKIN_TAGS.size - 1)]
        if (tag == pieceSkinTag) return
        pieceSkinTag = tag
        pieceBitmapCache.clear()  // 皮肤不同，位图缓存失效
        invalidate()
    }
    fun getPieceSkinIndex(): Int = SKIN_TAGS.indexOf(pieceSkinTag).coerceAtLeast(0)

    // 摆子模式
    var onMoveMade: ((Move) -> Boolean)? = null
    var onSetupTap: ((Int, Int) -> Unit)? = null
    private var setupValidPositions: Set<Position> = emptySet()

    // ★ 浏览历史模式：为true时不允许选子走棋，只能查看
    var viewingHistory: Boolean = false

    // 画笔
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }
    private val pieceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f  // 单圈浓稠描边
    }
    private val pieceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40000000")
    }

    fun setBoard(board: Board) {
        this.board = board
        invalidate()
    }

    fun setFlipped(flipped: Boolean) {
        this.flipped = flipped
        invalidate()
    }

    /** ★ 当前是否处于翻转视角（黑方在下）——背谱页"翻转"按钮据此来回切。 */
    fun isFlipped(): Boolean = flipped

    /**
     * 设置棋盘背景图（竹林/青花瓷）
     * @param resId drawable资源ID，传0则使用纯色背景
     */
    fun setBackgroundImage(resId: Int) {
        if (resId == backgroundResId && bgMode == BgMode.BAMBOO) return
        if (resId == 0 && bgMode == BgMode.SOLID) return
        backgroundResId = resId
        backgroundBitmap?.recycle()
        backgroundBitmap = null
        if (resId != 0) {
            try {
                backgroundBitmap = BitmapFactory.decodeResource(resources, resId)
            } catch (_: Exception) {
                backgroundBitmap = null
            }
        }
        invalidate()
    }

    /** 设置背景模式（纯色/图片/渐变） */
    fun setBackgroundMode(mode: BgMode) {
        bgMode = mode
        backgroundResId = 0
        backgroundBitmap?.recycle()
        backgroundBitmap = null
        when (mode) {
            BgMode.BAMBOO -> {
                backgroundResId = resources.getIdentifier("bg_bamboo", "drawable", context.packageName)
                if (backgroundResId != 0) {
                    try { backgroundBitmap = BitmapFactory.decodeResource(resources, backgroundResId) } catch (_: Exception) {}
                }
            }
            BgMode.PORCELAIN -> {
                backgroundResId = resources.getIdentifier("bg_porcelain", "drawable", context.packageName)
                if (backgroundResId != 0) {
                    try { backgroundBitmap = BitmapFactory.decodeResource(resources, backgroundResId) } catch (_: Exception) {}
                }
            }
            else -> {} // 渐变模式：纯代码生成
        }
        invalidate()
    }

    fun getBgMode(): BgMode = bgMode

    /** 设置纯色背景色（仅 SOLID 模式生效；渐变/图样模式不受影响） */
    fun setBoardBackgroundColor(color: Int) {
        boardBgColor = color
        invalidate()
    }

    /** 设置棋桌风格（只选项，固定比例随棋盘缩放） */
    fun setTableStyle(style: TableStyle) {
        tableStyle = style
        invalidate()
    }
    fun getTableStyle(): TableStyle = tableStyle

    /** 设置点动/盘内讲解浮层字号倍率（滑杆，0.6~1.8） */
    fun setTapFontScale(scale: Float) {
        tapFontScale = scale.coerceIn(0.6f, 1.8f)
        invalidate()
    }
    fun getTapFontScale(): Float = tapFontScale

    fun getBoardBackgroundColor(): Int = boardBgColor

    /** 设置是否显示当前时间（显示在楚河汉界中间） */
    fun setShowTime(show: Boolean) {
        showTime = show
        // ★ 秒针：开着才走、关掉即停 —— 主界面/挑战/背谱/解惑四个棋盘共用这一份心跳，
        //   不需要各宿主各自写 Handler（沙盒里没有主界面那套定时器也能自己跳秒）。
        removeCallbacks(timeTicker)
        if (show) postDelayed(timeTicker, 1000L)
        invalidate()
    }

    fun isShowTime(): Boolean = showTime

    /** 每秒重绘一次（仅在显示时间时自转），使楚河汉界中间的「年月日时分秒」实时走字 */
    private val timeTicker = object : Runnable {
        override fun run() {
            if (!showTime) return
            invalidate()
            postDelayed(this, 1000L)
        }
    }

    override fun onDetachedFromWindow() {
        // 视图离屏即停表，避免后台空转（返回/切换界面后不再无谓重绘）
        super.onDetachedFromWindow()
        removeCallbacks(timeTicker)
    }

    fun setLastMove(move: Move?) {
        if (move != null) {
            moveHighlightHistory.add(MoveHighlight(move, false))
            while (moveHighlightHistory.size > MAX_HIGHLIGHT_MOVES) {
                moveHighlightHistory.removeAt(0)
            }
        }
        invalidate()
    }

    fun setAiLastMove(move: Move?) {
        if (move != null) {
            moveHighlightHistory.add(MoveHighlight(move, true))
            while (moveHighlightHistory.size > MAX_HIGHLIGHT_MOVES) {
                moveHighlightHistory.removeAt(0)
            }
        }
        invalidate()
    }

    fun clearSelection() {
        selectedPosition = null
        legalMoves = emptyList()
        invalidate()
    }

    fun clearHighlights() {
        moveHighlightHistory.clear()
        invalidate()
    }

    /**
     * 从实际走棋历史重建光标高亮
     * @param moves 走法列表，每个元素是(UCI走法, 是否AI)
     * 直接从UCI字符串解析起止位置，不依赖棋盘状态
     */
    fun rebuildHighlightsFromHistory(moves: List<Pair<String, Boolean>>) {
        moveHighlightHistory.clear()
        for ((uci, isAi) in moves) {
            try {
                if (uci.length >= 4) {
                    val from = Position.fromUci(uci.substring(0, 2))
                    val to = Position.fromUci(uci.substring(2, 4))
                    // 创建一个占位Move，只需要from和to位置用于高亮
                    val dummyPiece = com.pikafish.chess.model.Piece(
                        com.pikafish.chess.model.PieceType.PAWN,
                        com.pikafish.chess.model.PieceColor.RED
                    )
                    val move = Move(from, to, dummyPiece, null)
                    moveHighlightHistory.add(MoveHighlight(move, isAi))
                }
            } catch (_: Exception) {}
        }
        invalidate()
    }

    fun setSetupValidPositions(positions: Set<Position>?) {
        setupValidPositions = positions ?: emptySet()
        invalidate()
    }

    // ── 视觉控制方法 ──

    /** 设置棋盘缩放倍率（0.5~2.0）。★ 直达渲染：以「可视带基准格」为 100% 基线，
     *   cellSize = 基准格 × 倍率，拖动滑杆每一帧都随之放大/缩小，不再被可视带钳死。 */
    fun setBoardScale(scale: Float) {
        boardSliderScale = scale.coerceIn(0.5f, 2.0f)
        boardScale = boardSliderScale
        liveBoardScale = boardSliderScale      // 走「基准格 × 倍率」实时渲染路径
        requestLayout()
        if (width > 0 && height > 0) recalcLayout(width, height)
    }

    /** ★ 稳定锚点：用户/大而美设定的棋盘缩放值（不被视图尺寸回写，供布局测算防抖串）。
     *    ★★ getBoardScale 也返回锚点，而非被派生像素回写过的值——杜绝"每启增长/96变87"：
     *    旧的实现把 absoluteCellSize(像素)/baseCellSize(易变) 回写 boardScale，再落盘，
     *    容器一挤基准格变小、像素不变，比值就每启变大一次，数字疯长画面没变。现在统一
     *    以滑条锚点为唯一持久值，派生像素只用于当次渲染、绝不回写。 */
    fun getBoardSliderScale(): Float = boardSliderScale

    /** 稳定棋盘缩放（同锚点；保存/弹窗显示用，永不被视图尺寸污染） */
    fun getBoardScale(): Float = boardSliderScale

    /** ★ 有效棋盘缩放 = 棋盘滑条锚点值（布局理想高度测算用；只随「棋盘大小」滑条，
     *   与棋子滑条完全无关——调棋子时棋盘外框纹丝不动） */
    fun getEffectiveBoardScale(): Float = boardSliderScale

    /**
     * ★ 纯棋子缩放：只改棋子直径，棋盘纹丝不动。
     * 设置棋子缩放请求值（0.5~1.5）直接线性映射渲染直径（半径 = cellSize×0.47×请求值）：
     * 棋子以自身圆心为锚点原地变大/变小——变大时吃掉棋子之间的格间缝隙（间距自然变小），
     * 绝不外扩棋盘、不顶开外框、不偏心；缩小时按同规则对称收回。
     */
    fun setPieceScale(scale: Float) {
        pieceScale = scale.coerceIn(0.5f, MAX_PIECE_INPUT_SCALE)
        invalidate()
    }

    fun getPieceScale(): Float = pieceScale

    /** 当前渲染采用的棋子相对格系数（= 请求值，直接线性映射，无健康封顶） */
    private val renderPieceRatio: Float get() = pieceScale

    // ── 绝对像素尺寸接口（卡尺级精度，强退后原样恢复）──

    /** 设置绝对格子尺寸（像素），0=用基准值。★ 只用于当次渲染（大而美等像素钉死），
     *  绝不回写 boardSliderScale/boardScale —— 那会让"像素/易变基准格"污染持久锚点，
     *  造成每启百分比增长/96变87的腐败。持久与显示一律走滑条锚点（getBoardScale）。 */
    fun setAbsoluteCellSize(px: Float) {
        absoluteCellSize = px
        liveBoardScale = -1f          // 大而美像素钉死优先：关闭滑杆倍率路径，避免互相覆盖
        requestLayout()
        if (width > 0 && height > 0) recalcLayout(width, height)
    }

    fun getAbsoluteCellSize(): Float = absoluteCellSize

    /** ★ 设置棋盘可视带（px）：棋盘贴住「顶栏贴纸底边」下移小边距竖直摆放（不再按整屏
     *  几何居中）——顶栏到棋盘不再留大片空白。由 MainActivity 一次算好（顶栏底 ↔ 主菜单顶），
     *  显/隐、摆子切换、菜单高度变化都不重算：棋盘纹丝不动，只动贴纸。 */
    fun setContentBand(top: Float, bottom: Float) {
        contentBandTop = top
        contentBandBottom = bottom
        contentBandValid = bottom > top + 1f
        requestLayout()
        if (width > 0 && height > 0) recalcLayout(width, height)
    }

    /**
     * ★★「棋盘坐标」：设棋盘整体纵向偏移（px，正=下移、负=上移）。
     *   只整体平移棋盘（含棋子/刻度/命中检测），不改格子与棋子大小；超出可视带的
     *   越界由 recalcLayout 内的钳制兜底（绝不把棋盘推出顶栏/压进底部菜单）。默认0=无偏移。
     */
    fun setBoardYOffset(px: Float) {
        val clamped = px.coerceIn(-MAX_Y_OFFSET_PX, MAX_Y_OFFSET_PX)
        if (kotlin.math.abs(clamped - boardYOffset) < 0.5f) return
        boardYOffset = clamped
        requestLayout()
        if (width > 0 && height > 0) recalcLayout(width, height)
        invalidate()
    }

    fun getBoardYOffset(): Float = boardYOffset

    /**
     * 设置绝对棋子半径（像素）。
     * ★ 线性同步：这里只把它换算成棋子缩放请求值保存，绘制时半径始终跟随
     * 当前 cellSize 动态重算，绝不锁死像素 —— 保证棋盘与棋子严格同步缩放。
     */
    fun setAbsolutePieceRadius(px: Float) {
        if (px > 0 && baseCellSize > 0) {
            pieceScale = (px / (baseCellSize * 0.47f)).coerceIn(0.5f, MAX_PIECE_INPUT_SCALE)
        }
        invalidate()
    }

    /** 返回当前动态棋子半径像素（跟随格子尺寸，按请求系数线性映射） */
    fun getAbsolutePieceRadius(): Float = pieceRadius * renderPieceRatio

    /** 获取基准格子尺寸（100%时的像素值） */
    fun getBaseCellSize(): Float = baseCellSize

    /** ★ 当前渲染最终采用的格子像素（真实呈现尺寸，唯一权威）。主界面落盘这个值，
     *  三个沙盒(挑战/背谱/解惑)直接吃同一像素 → 无论各自底部面板高矮，棋盘格大小
     *  一律与主界面完全一致，不再"各自按可视带算 bandFitCell"而彼此各不相干。 */
    fun getRenderCellSize(): Float = cellSize

    /** 设置棋线颜色 */
    fun setLineColor(color: Int) {
        lineColor = color
        invalidate()
    }

    fun getLineColor(): Int = lineColor

    /** 设置棋线粗细（1.0~4.0） */
    fun setLineThickness(thickness: Float) {
        lineThickness = thickness.coerceIn(1.0f, 4.0f)
        invalidate()
    }

    fun getLineThickness(): Float = lineThickness

    /** 设置刻度文字颜色（棋盘坐标 1~9/一~九 的颜色） */
    fun setLabelColor(color: Int) {
        labelColor = color
        invalidate()
    }

    fun getLabelColor(): Int = labelColor

    /** 追加一行盘内讲解（自动维护3行交替） */
    fun appendOverlayLine(line: String) {
        if (overlayMode == OverlayMode.OFF) return
        overlayLines.add(line)
        if (overlayLines.size > 3) {
            overlayLines.removeAt(0)  // 旧三行变新三行
        }
        // ON模式立即刷新显示；TAP模式存储内容等待用户点击
        if (overlayMode == OverlayMode.ON) {
            invalidate()
        }
    }

    /** 设置盘内讲解模式 */
    fun setOverlayMode(mode: OverlayMode) {
        overlayMode = mode
        if (mode == OverlayMode.OFF) {
            overlayLines.clear()
            dismissTapOverlay()
        }
        if (mode == OverlayMode.TAP) {
            // 点动模式不清空内容，等待用户点击显示
            dismissTapOverlay()  // 切换模式时关闭浮层
        }
        if (mode == OverlayMode.ON) {
            dismissTapOverlay()
        }
        invalidate()
    }

    fun getOverlayMode(): OverlayMode = overlayMode

    /** 兼容旧接口 */
    fun setOverlayEnabled(enabled: Boolean) {
        setOverlayMode(if (enabled) OverlayMode.ON else OverlayMode.OFF)
    }

    fun isOverlayEnabled(): Boolean = overlayMode != OverlayMode.OFF

    /** 清空盘内讲解浮层（保留开关状态） */
    fun clearOverlayLines() {
        overlayLines.clear()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // ★ 防崩溃：任何测量异常都就地拦下，绝不让进程闪退
        try {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            // ★ 高度直接吃满外罩（棋盘区）高度，不再封顶 w×10/9：
            //   棋盘居中由 recalcLayout 的 (h - boardHeight)/2 保证，上下留白对称，
            //   不会出现棋盘顶上部、底部空一大块的"偏心"观感。
            val finalHeight = height.coerceAtLeast(1)
            setMeasuredDimension(width, finalHeight)
        } catch (t: Throwable) {
            android.util.Log.e("ChessBoardView", "onMeasure拦截", t)
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalcLayout(w, h)
    }

    private fun recalcLayout(w: Int, h: Int) {
        // 减少水平边距，让棋盘更饱满
        boardPaddingH = w * 0.04f
        // 刻度标签空间
        val labelSpace = w * 0.045f
        boardPaddingTop = labelSpace
        boardPaddingBot = labelSpace

        val availableWidth = w - 2 * boardPaddingH
        val availableHeight = h - boardPaddingTop - boardPaddingBot

        val cellSizeByWidth = availableWidth / (BOARD_COLS - 1)
        val cellSizeByHeight = availableHeight / (BOARD_ROWS - 1)
        // 基准格子尺寸（100%时的值）
        baseCellSize = minOf(cellSizeByWidth, cellSizeByHeight)
        // ★ 待应用比例落位：布局首次测出基准格后，把启动时早设的比例换算成绝对像素，棋盘自动收敛。
        if (pendingRatioApply > 0f && baseCellSize > 0f) {
            absoluteCellSize = baseCellSize * pendingRatioApply
            pendingRatioApply = -1f
        }
        // 绝对像素优先：有保存值就用保存的，否则用基准值。
        // ★ 自适应兜底：棋盘滑条/大而美的绝对尺寸最多不能超过当前空间按高度能放下的上限；
        //   若用户曾把棋盘放大到超出当前可视空间（小屏/面板挤压导致高度、宽度不足），
        //   自动回退到放得下的值，避免棋子挤成一堆/重叠出界。
        // ★ 全屏贴纸方案（底部锚定）：最大格子 = 可视带（顶栏贴纸底 ↔ 思考/解说贴纸顶）内
        //   「顶部黑子/刻度标签 + 棋盘格 + 红方底排棋子/刻度标签 + 两端2毫米间隙」完整放下的上限。
        //   从根上杜绝棋子顶到顶栏/被裁切、红方底线被贴纸盖住——上限即正确，不用再猜。
        val bandH = if (contentBandValid) (contentBandBottom - contentBandTop) else availableHeight
        val gapPx = 2f * resources.displayMetrics.xdpi / 25.4f   // 2 毫米（物理长度）
        // ★ 顶部外延系数：黑子半径(含棋子滑条放大) 与 上方刻度标签 取大者，再叠加 0.10 格安全净空。
        //   顶排最上沿(黑子/刻度标签)至少离顶栏底边 0.10 格——紧凑但互不干涉，
        //   任何机型/字体缩放/状态栏高度差异都不会再把顶排埋进顶栏。
        val kTop = maxOf(0.47f * pieceScale + 0.10f, 0.60f)
        // 底部外延系数：红方底排棋子之下的底部刻度标签（基线≈0.55格 + 文字下伸）
        val kBot = 0.62f
        val bandFitCell = (bandH - 2f * gapPx) / ((BOARD_ROWS - 1) + kTop + kBot)
        val fullFitCell = minOf(cellSizeByWidth, cellSizeByHeight)   // 整屏可容纳上限（已含刻度/边距留白）
        val minCell = 4f * resources.displayMetrics.density            // 保底格子，极端缩放下仍可辨
        // ★★★ 「棋盘大小」滑杆直接接管渲染：100% = 标准尺寸(可视带适配格)，滑到几%就放大/缩小到几%，
        //   上限放宽到整屏不爆屏、下限保底。可视为"滑到哪、后台就点了确认"的实时效果。
        //   （棋子走独立渲染支路，故不受此钳制；棋盘现在也一样实时。）
        val stdCell = if (contentBandValid) bandFitCell else baseCellSize * 0.88f
        // ★★ 上限修正（补刻度后自适应用户诟病的根因）：可视带有效时，棋盘放大/保存值
        //   一律钳在「带内可容纳」bandFitCell 之内——棋盘顶排黑子+刻度标签绝不会顶出顶栏
        //   被裁切，底部红方底排+刻度也不会压进菜单；只有带未定时才回退整屏上限。
        val capCell = if (contentBandValid) bandFitCell else fullFitCell
        cellSize = when {
            liveBoardScale > 0f ->
                (stdCell * liveBoardScale).coerceIn(minCell, capCell)
            absoluteCellSize > 0f ->
                absoluteCellSize.coerceIn(minCell, capCell)
            else -> minOf(baseCellSize, if (contentBandValid) bandFitCell else baseCellSize)
        }
        // ★ 大而美：比例值始终与真实绝对尺寸同步；滑杆路径由锚点 boardSliderScale 直给，不在此回写
        if (absoluteCellSize > 0 && baseCellSize > 0 && liveBoardScale <= 0f) {
            boardScale = (absoluteCellSize / baseCellSize).coerceIn(0.5f, 2.0f)
        }
        // ── ★★ 摆子「铁板一块」顶排锚定压缩 ──
        //   先按正常逻辑算出「自然布局」（不压缩时的格子/位置）；
        //   若设置了 boardBottomLimitPx（黑区上顶时由 MainActivity 算出棋盘底边最大允许Y），
        //   则以此压缩：以「最上一排」那条线为锚，顶排守着原位不动（不往前走、不往后缩），
        //   棋盘/棋子/刻度等比例向下均匀挤压，底边恰好停在面板顶上沿——不留空白、不互相遮盖。
        val naturalCell = cellSize
        val naturalWidth = naturalCell * (BOARD_COLS - 1)
        val naturalHeight = naturalCell * (BOARD_ROWS - 1)
        // ★ 调试定位优先：用户填了坐标就用坐标；没填走自动定位（X 水平居中，Y 底部锚定）。
        val naturalLeft = if (!debugPosLeft.isNaN()) debugPosLeft else (w - naturalWidth) / 2f
        // ★ 全屏贴纸方案（用户定的新规则）：棋盘「顶排锁定」——
        //   以顶栏贴纸底边为基准，棋盘顶排（黑子/红子上方刻度标签）紧贴其下 2 毫米摆放，
        //   紧凑但互不干涉；下方棋盘怎么展开自然落到菜单之上，不再从底边往上顶。
        //   未设可视带(旧路径)时退回整屏几何居中。
        val naturalTop = if (contentBandValid) {
            // ★ 顶排锁定：顶栏底边 + 2毫米 + 顶排外延(黑子半径/上方刻度标签，取大者)
            //   顶排永远完整露在顶栏之下——任何机型/字体/状态栏高度都不再把顶排埋进顶栏。
            contentBandTop + gapPx + naturalCell * kTop
        } else if (!debugPosTop.isNaN()) {
            debugPosTop
        } else {
            (h - naturalHeight) / 2f
        }
        // ★ 顶排锚定压缩：棋盘底边(含底部刻度标签外延 kBot)不得超过 boardBottomLimitPx。
        //   超了就以「最上一排」那条线为锚向下均匀等比缩小——顶排纹丝不动。
        if (!boardBottomLimitPx.isNaN() && naturalCell > 0f) {
            val bottomExt = (BOARD_ROWS - 1) + kBot
            val naturalBottom = naturalTop + naturalCell * bottomExt
            if (naturalBottom > boardBottomLimitPx) {
                val maxCell = (boardBottomLimitPx - naturalTop) / bottomExt
                val minCell = 4f * resources.displayMetrics.density   // 极端情况保底 4dp
                cellSize = maxOf(maxCell, minCell)
                topAnchorScale = cellSize / naturalCell
            } else {
                topAnchorScale = 1f
            }
        } else {
            topAnchorScale = 1f
        }
        pieceRadius = cellSize * 0.47f  // 基础棋子大小

        val boardWidth = cellSize * (BOARD_COLS - 1)
        // ★ 顶排锚定落位（transform-origin: top center）：顶排那条线守住 naturalTop 不动；
        //   水平以自然棋盘中心线为锚，左右对称内收，绝不出现单侧大片空白。
        boardLeft = naturalLeft + (naturalWidth - boardWidth) / 2f
        boardTop = naturalTop
        // ★★「棋盘坐标」用户纵向偏移：整体平移棋盘（含棋子/刻度/命中检测）。
        //   在可视带内钳制——顶不外越顶栏（顶部黑子外延 kTop）、底不压进底部菜单
        //   （底部刻度外延 kBot），杜绝偏移导致的爆屏/裁切；默认0=行为与原来完全一致。
        if (boardYOffset != 0f && contentBandValid && naturalCell > 0f) {
            val topLimit = (contentBandTop + naturalCell * kTop)
                .coerceAtMost(naturalTop)                       // 只许往带内靠，不许越过带顶
            val botLimit = (contentBandBottom - naturalCell * kBot)
                .coerceAtLeast(naturalTop + naturalHeight)       // 只许往带内靠，不许越过带底
            boardTop = (naturalTop + boardYOffset)
                .coerceIn(topLimit, botLimit - naturalHeight)
        } else {
            boardTop = naturalTop + boardYOffset
        }
    }

    override fun onDraw(canvas: Canvas) {
        // ★ 防崩溃：单帧绘制任何异常都就地拦下，绝不让任一个进程闪退
        try {
            super.onDraw(canvas)
            drawTable(canvas)
            drawBoardBackground(canvas)
            // ★ 坐标刻度标签改到纸/桌之后、棋线之前：厨桌外扩一盖，原来画在下面的坐标
            //   就被盖没了（顶部尤其看不见）。放这里才稳稳显示在桌面/棋盘纸上层。
            drawCoordinateLabels(canvas)
            drawGridLines(canvas)
            drawRiver(canvas)
            drawPalace(canvas)
            drawPositionMarkers(canvas)
            drawHighlights(canvas)
            drawPieces(canvas)
            drawOverlayText(canvas)
            // ★ 河界时间永远压在最上层（棋子、解说浮层都盖不住它）→ 不管怎么走子都看得见
            drawRiverTime(canvas)
        } catch (t: Throwable) {
            android.util.Log.e("ChessBoardView", "onDraw拦截", t)
        }
    }

    // ── 坐标刻度标签 ──
    // 红方始终用中文数字（一～九），黑方始终用阿拉伯数字（1～9）
    // 翻转时标签跟随各侧移动，坐标系不变

    private val sideLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private fun drawCoordinateLabels(canvas: Canvas) {
        // ── 列标签 ──
        labelPaint.color = labelColor
        labelPaint.textSize = cellSize * 0.38f
        labelPaint.style = Paint.Style.FILL
        labelPaint.textAlign = Paint.Align.CENTER

        val boardHeight = cellSize * (BOARD_ROWS - 1)

        // 红方列标签：从红方视角，右边是一（col8），左边是九（col0）
        val redColLabels = listOf("九", "八", "七", "六", "五", "四", "三", "二", "一")
        // 黑方列标签：从黑方视角，右边是1（col0），左边是9（col8）
        val blackColLabels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")

        // ★ 铁例（无选项、不可改）：红方一侧恒用中文（一～九），黑方一侧恒用阿拉伯（1～9）。
        //   不翻转：上=黑方(阿拉伯)、下=红方(中文)；翻转后：上=红方(中文)、下=黑方(阿拉伯)。
        val topIsRed = flipped
        val bottomIsRed = !flipped

        // 上方标签（boardTop 之上）
        val topLabelY = boardTop - cellSize * 0.15f
        // 下方标签（boardBottom 之下）
        val bottomLabelY = boardTop + boardHeight + cellSize * 0.55f

        for (displayCol in 0 until BOARD_COLS) {
            val px = boardLeft + displayCol * cellSize
            val topLabel = if (topIsRed) redColLabels[8 - displayCol] else blackColLabels[displayCol]
            val bottomLabel = if (bottomIsRed) redColLabels[displayCol] else blackColLabels[8 - displayCol]
            canvas.drawText(topLabel, px, topLabelY, labelPaint)
            canvas.drawText(bottomLabel, px, bottomLabelY, labelPaint)
        }

        // ── 侧边"红方"/"黑方"红黑圆点标识 ── 已按需移除（纯装饰，不影响走棋逻辑）
    }

    /** 棋桌：画在棋盘纸下层的衬台，外扩量与棋盘成正比（赖皮膏药式跟随缩放），
     *  平直桌面、不画"受光斜面"亮框——边缘收敛为极细的同色描边，干净贴合参考图中
     *  "无高耸金属框"的平面感，避免亮边立起、仿佛碰到外侧棋子边缘。 */
    private fun drawTable(canvas: Canvas) {
        val boardWidth = cellSize * (BOARD_COLS - 1)
        val boardHeight = cellSize * (BOARD_ROWS - 1)
        val paperL = boardLeft - 8f
        val paperT = boardTop - 8f
        val paperR = boardLeft + boardWidth + 8f
        val paperB = boardTop + boardHeight + 8f
        if (paperR - paperL <= 0f || paperB - paperT <= 0f) return

        // 外扩量＝相邻格大小 × 倍率，且至少留一层约4dp的边，跟随棋盘等比例缩放
        val margin = maxOf(cellSize * tableMarginFactor, 4f * resources.displayMetrics.density)
        val left = maxOf(0f, paperL - margin)
        val top = maxOf(0f, paperT - margin)
        val right = minOf(width.toFloat(), paperR + margin)
        val bottom = minOf(height.toFloat(), paperB + margin)
        if (right - left <= 0f || bottom - top <= 0f) return

        // ★ 桌角R做小：顶边本来就给顶栏盖住看不到圆弧，只有下边露出来；
        //    R做小后底部弧线收敛，不再和顶部不对称地"翘一大弯"。 
        val radius = minOf(margin * 0.5f + 5f, 16f)

        // 主面：整块衬台渐变（上→下）
        tablePaint.shader = LinearGradient(left, top, left, bottom,
            intArrayOf(tableStyle.top, tableStyle.mid, tableStyle.low),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        tablePaint.style = Paint.Style.FILL
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, tablePaint)
        tablePaint.shader = null

        // ★ 桌沿花纹（照片画框式花边）：棋桌＝棋布的画框，画框可以带花纹。
        //   纯矢量、不占资源；四边按整单元排满；上/下两带刻意淡一档，不压坐标刻度。
        drawTablePattern(canvas, left, top, right, bottom, margin)

        // ★ 去掉金属边框：不再画 tableStyle.edge 的受光斜面亮边、也不压内圈深线。
        //   只沿用桌面最深色描一条极细平边，纯粹勾勒桌沿轮廓，平面不立起、不碰棋子。
        tablePaint.color = tableStyle.low
        tablePaint.style = Paint.Style.STROKE
        val hair = maxOf(0.7f, 1f * resources.displayMetrics.density)
        tablePaint.strokeWidth = hair
        canvas.drawRoundRect(left + hair, top + hair, right - hair, bottom - hair,
            radius, radius, tablePaint)
    }

    /** ★★ 桌沿花纹＝棋布的「画框」：
     *  ① 先把框立起来 —— 浅色托纸（环形）＋ 内外两道细硬轨线，框的轮廓清清楚楚；
     *  ② 再在托纸上作画 —— 写实多色（绿叶/粉花/红黄花/蔬果/柳条），元素疏朗、间距大小随机；
     *  浅托纸把艳花绿叶衬得极分明，深轨线又把「框」和「花」彻底分开，绝不糊成一团。
     *  上/下两带正压着坐标刻度（1~9 / 一~九），故画只淡一档、不抢刻度；左右两带全彩。 */
    private fun drawTablePattern(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, margin: Float) {
        val pattern = tableStyle.pattern
        if (pattern == TablePattern.NONE) return
        if (right - left <= 0f || bottom - top <= 0f) return

        // 画框几何：外沿留 pad → 托纸带宽 bw → 内侧再留 pad（三段相加 ≈ margin）
        val pad = margin * 0.12f
        val bw = margin * 0.76f
        if (bw < 3.5f) return                       // 太窄画不出写实细节，宁可不画
        val hPad = pad + bw + pad
        if ((right - left) <= 2f * hPad || (bottom - top) <= 2f * hPad) return

        // 裁到圆角桌内，框/花不外溢圆角
        canvas.save()
        val clip = Path()
        clip.addRoundRect(left, top, right, bottom, 16f, 16f, Path.Direction.CW)
        canvas.clipPath(clip)

        val ox0 = left + pad; val ox1 = right - pad
        val oy0 = top + pad;  val oy1 = bottom - pad
        val ix0 = ox0 + bw;   val ix1 = ox1 - bw
        val iy0 = oy0 + bw;   val iy1 = oy1 - bw
        val rOut = minOf(bw * 0.5f, 12f)
        val rIn = minOf(bw * 0.4f, 9f)

        // ── ① 框：浅色托纸（even-odd 环形，中间挖空）＋ 内外硬轨 ──
        val ring = Path()
        ring.fillType = Path.FillType.EVEN_ODD
        ring.addRoundRect(ox0, oy0, ox1, oy1, rOut, rOut, Path.Direction.CW)
        ring.addRoundRect(ix0, iy0, ix1, iy1, rIn, rIn, Path.Direction.CW)
        framePaint.style = Paint.Style.FILL
        framePaint.color = matColor()
        canvas.drawPath(ring, framePaint)

        framePaint.style = Paint.Style.STROKE
        framePaint.color = railColor()
        framePaint.strokeWidth = maxOf(0.9f, 1.2f * resources.displayMetrics.density)
        canvas.drawRoundRect(ox0, oy0, ox1, oy1, rOut, rOut, framePaint)
        canvas.drawRoundRect(ix0, iy0, ix1, iy1, rIn, rIn, framePaint)

        // ── ② 花：托纸上作画 ──
        val inkFull = deepInk()
        patternPaint.color = inkFull
        patternPaint.style = Paint.Style.STROKE
        patternPaint.strokeWidth = maxOf(0.9f, bw * 0.075f)

        val aSide = 255
        val aTopBot = 190                     // 上/下淡一档：刻度就压在这两带上
        val hs = ox0; val he = ox1
        val vs = iy0; val ve = iy1
        val hLen = (he - hs).coerceAtLeast(1f)
        val vLen = (ve - vs).coerceAtLeast(1f)
        // 横带铺满整桌宽（含四角），竖带只填两横带之间的中段 → 四角被横带填满，框闭合不留豁口
        drawBand(canvas, pattern, hs, oy0, 0f, false, hLen, bw, 1101, aTopBot)
        drawBand(canvas, pattern, hs, oy1, 0f, true, hLen, bw, 2202, aTopBot)
        if (vLen > bw) {
            drawBand(canvas, pattern, ix0, vs, 90f, false, vLen, bw, 3303, aSide)
            drawBand(canvas, pattern, ix1, ve, -90f, false, vLen, bw, 4404, aSide)
        }

        canvas.restore()
    }

    /** 画单条花纹带：把局部坐标（x∈[0,len], y∈[0,bandH]）映射到实际边框上再作画。
     *  传统几何边饰（回纹/云纹）走单色墨线；其余写实纹样走多色。 */
    private fun drawBand(
        canvas: Canvas, pattern: TablePattern, ox: Float, oy: Float,
        deg: Float, mirrorY: Boolean, len: Float, bandH: Float, seed: Int, alpha: Int
    ) {
        canvas.save()
        canvas.translate(ox, oy)
        if (deg != 0f) canvas.rotate(deg)
        if (mirrorY) canvas.scale(1f, -1f)
        // 记下本带的变换，供「直立元件」（蔬果等有明确上下者）反算全局落点
        bandOx = ox; bandOy = oy; bandMirror = mirrorY
        val rr = Math.toRadians(deg.toDouble())
        bandCos = cos(rr).toFloat(); bandSin = sin(rr).toFloat()
        when (pattern) {
            TablePattern.MEANDER, TablePattern.CLOUD -> {
                patternPaint.alpha = alpha
                buildBandPath(pattern, len, bandH)
                canvas.drawPath(patternBandPath, patternPaint)
                patternPaint.alpha = 255
            }
            else -> drawBandColored(canvas, pattern, len, bandH, Rnd(seed), alpha)
        }
        canvas.restore()
    }

    // ═══════════════════ 写实多色花纹（真颜色，非素描） ═══════════════════

    // 真彩配色：叶是真绿、花有粉/红/黄/白、蔬果各是本味本色
    private val cLeafM = Color.rgb(0x4E, 0x8B, 0x3A)
    private val cLeafD = Color.rgb(0x33, 0x63, 0x28)
    private val cLeafL = Color.rgb(0x8B, 0xC4, 0x62)
    private val cStem = Color.rgb(0x60, 0x7C, 0x3C)
    private val cPink = Color.rgb(0xE8, 0x7A, 0xA8)
    private val cRed = Color.rgb(0xD6, 0x42, 0x30)
    private val cYellow = Color.rgb(0xF2, 0xC1, 0x4E)
    private val cWhite = Color.rgb(0xFB, 0xF8, 0xF0)
    private val cCenter = Color.rgb(0xF7, 0xCF, 0x4A)
    private val cBirdBlue = Color.rgb(0x3C, 0x6F, 0xB4)
    private val cBirdTeal = Color.rgb(0x2E, 0x8C, 0x86)
    private val cCabbW = Color.rgb(0xF7, 0xF4, 0xE9)
    private val cCabbG = Color.rgb(0xBF, 0xD8, 0xA0)
    private val cEggP = Color.rgb(0x6B, 0x4B, 0x8A)
    private val cEggH = Color.rgb(0x9A, 0x77, 0xBA)
    private val cChili = Color.rgb(0xCC, 0x2E, 0x2E)
    private val cCarrot = Color.rgb(0xE0, 0x7B, 0x2E)

    /** 极简确定性伪随机：同一条带每帧结果一致（不闪），但间距/大小/花色各不相同。 */
    private class Rnd(seed: Int) {
        private var s = (seed * 1103515245 + 12345) or 1
        fun f(): Float {
            s = s * 1103515245 + 12345
            return ((s ushr 9) and 0x7FFF) / 32767f
        }
        fun r(a: Float, b: Float): Float = a + (b - a) * f()
    }

    /** 托纸色：取风格亮色再提亮 → 青花约月白、朱漆约藕荷、金檀约牙黄，浅底衬艳花。 */
    private fun matColor(): Int {
        val base = if (tableStyle.edge != 0) tableStyle.edge else tableStyle.top
        return blendColor(base, Color.WHITE, 0.44f)
    }

    /** 轨线色：取风格深色再压深 → 两条硬轨把「框」和「画」彻底分开。 */
    private fun railColor(): Int = blendColor(tableStyle.low, Color.BLACK, 0.16f)

    /** 传统几何边饰的墨色：取风格深色压深（比 patternInk 更适合浅托纸）。 */
    private fun deepInk(): Int {
        val base = if (tableStyle.patternInk != 0) blendColor(tableStyle.patternInk, tableStyle.low, 0.55f)
                   else tableStyle.low
        return blendColor(base, Color.BLACK, 0.14f)
    }

    /** 花色抽签：粉多、红次、黄又次、白点缀 —— 一片花里颜色不重样。 */
    private fun pickPetal(r: Rnd): Int {
        val v = r.f()
        return when {
            v < 0.40f -> cPink
            v < 0.64f -> cRed
            v < 0.85f -> cYellow
            else -> cWhite
        }
    }

    /** 写实多色花纹带分派。 */
    private fun drawBandColored(canvas: Canvas, pattern: TablePattern, len: Float, bandH: Float, r: Rnd, alpha: Int) {
        motifFillPaint.alpha = alpha
        motifInkPaint.alpha = alpha
        when (pattern) {
            TablePattern.WILLOW -> willowBand(canvas, len, bandH, r)
            TablePattern.VEGGIE -> veggieBand(canvas, len, bandH, r)
            TablePattern.FLORAL -> floralBand(canvas, len, bandH, r)
            TablePattern.BIRD -> sprigBand(canvas, len, bandH, r, Motif.BIRD)
            TablePattern.FRUIT -> sprigBand(canvas, len, bandH, r, Motif.FRUIT)
            TablePattern.VINE -> vineBand(canvas, len, bandH, r)
            else -> garlandBand(canvas, len, bandH, r)
        }
        motifFillPaint.alpha = 255
        motifInkPaint.alpha = 255
    }

    // ── 写实元件：全部「填色 + 淡墨勾」──

    /** 一片真绿叶（沿 ang 伸出 len）。 */
    private fun fillLeaf(c: Canvas, bx: Float, by: Float, len: Float, ang: Float, col: Int) {
        val ca = cos(ang); val sa = sin(ang)
        val nx = -sa; val ny = ca
        val tx = bx + len * ca; val ty = by + len * sa
        val w = len * 0.33f
        val p = motifPath
        p.reset()
        p.moveTo(bx, by)
        p.quadTo(bx + len * 0.40f * ca + w * nx, by + len * 0.40f * sa + w * ny, tx, ty)
        p.quadTo(bx + len * 0.40f * ca - w * nx, by + len * 0.40f * sa - w * ny, bx, by)
        p.close()
        motifFillPaint.color = col
        c.drawPath(p, motifFillPaint)
        motifInkPaint.color = cLeafD
        motifInkPaint.strokeWidth = maxOf(0.55f, len * 0.045f)
        c.drawLine(bx, by, tx, ty, motifInkPaint)
    }

    /** 一朵真花：[petals] 枚圆瓣 + 花心。[petals]=5 即用户说的五瓣花。 */
    private fun fillFlower(c: Canvas, cx: Float, cy: Float, r: Float, petal: Int, petals: Int = 5) {
        val pr = r * (if (petals <= 5) 0.50f else 0.40f)
        for (k in 0 until petals) {
            val a = (k * 360f / petals - 90f) * PI.toFloat() / 180f
            motifFillPaint.color = petal
            c.drawCircle(cx + r * 0.52f * cos(a), cy + r * 0.52f * sin(a), pr, motifFillPaint)
        }
        motifFillPaint.color = cCenter
        c.drawCircle(cx, cy, r * 0.26f, motifFillPaint)
    }

    /** 花苞：一枚小瓣 + 褐绿萼。 */
    private fun fillBud(c: Canvas, cx: Float, cy: Float, r: Float, petal: Int) {
        motifFillPaint.color = petal
        c.drawCircle(cx, cy, r * 0.62f, motifFillPaint)
        fillLeaf(c, cx, cy + r * 0.45f, r * 1.35f, 1.45f, cLeafM)
    }

    /** 翠鸟：身/头/翅/尾/喙/眼，真彩。 */
    private fun fillBird(c: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        val p = motifPath
        motifFillPaint.color = col
        p.reset()
        p.addOval(RectF(cx - s * 0.52f, cy - s * 0.28f, cx + s * 0.50f, cy + s * 0.36f), Path.Direction.CW)
        p.addCircle(cx - s * 0.44f, cy - s * 0.38f, s * 0.24f, Path.Direction.CW)
        c.drawPath(p, motifFillPaint)
        motifFillPaint.color = blendColor(col, Color.BLACK, 0.38f)
        p.reset()
        p.addOval(RectF(cx - s * 0.18f, cy - s * 0.14f, cx + s * 0.38f, cy + s * 0.28f), Path.Direction.CW)
        p.moveTo(cx + s * 0.42f, cy + s * 0.02f)
        p.lineTo(cx + s * 0.92f, cy - s * 0.16f)
        p.lineTo(cx + s * 0.92f, cy + s * 0.16f)
        p.close()
        c.drawPath(p, motifFillPaint)
        motifFillPaint.color = cCenter
        p.reset()
        p.moveTo(cx - s * 0.62f, cy - s * 0.40f)
        p.lineTo(cx - s * 0.86f, cy - s * 0.34f)
        p.lineTo(cx - s * 0.62f, cy - s * 0.28f)
        p.close()
        c.drawPath(p, motifFillPaint)
        motifFillPaint.color = Color.BLACK
        c.drawCircle(cx - s * 0.48f, cy - s * 0.42f, s * 0.05f, motifFillPaint)
    }

    /** 红果：圆果 + 高光 + 顶上一对托叶。 */
    private fun fillFruitC(c: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        motifFillPaint.color = col
        c.drawCircle(cx, cy + s * 0.12f, s * 0.44f, motifFillPaint)
        motifFillPaint.color = blendColor(col, Color.WHITE, 0.5f)
        c.drawCircle(cx - s * 0.15f, cy - s * 0.02f, s * 0.11f, motifFillPaint)
        fillLeaf(c, cx, cy - s * 0.28f, s * 0.66f, -0.85f, cLeafM)
        fillLeaf(c, cx, cy - s * 0.28f, s * 0.66f, -2.30f, cLeafL)
    }

    /** 白菜：绿外叶 + 白亮菜心。 */
    private fun drawCabbage(c: Canvas, cx: Float, cy: Float, h: Float) {
        val w = h * 0.78f
        val p = motifPath
        motifFillPaint.color = cCabbG
        p.reset()
        p.addOval(RectF(cx - w * 0.5f, cy - h * 0.34f, cx + w * 0.5f, cy + h * 0.5f), Path.Direction.CW)
        c.drawPath(p, motifFillPaint)
        fillLeaf(c, cx - w * 0.17f, cy - h * 0.12f, h * 0.46f, -2.42f, cLeafL)
        fillLeaf(c, cx + w * 0.17f, cy - h * 0.12f, h * 0.46f, -0.72f, cLeafL)
        motifFillPaint.color = cCabbW
        p.reset()
        p.addOval(RectF(cx - w * 0.30f, cy - h * 0.24f, cx + w * 0.30f, cy + h * 0.40f), Path.Direction.CW)
        c.drawPath(p, motifFillPaint)
    }

    /** 茄子：紫身 + 高光 + 绿萼 + 短柄。 */
    private fun drawEggplant(c: Canvas, cx: Float, cy: Float, h: Float) {
        val w = h * 0.44f
        val p = motifPath
        motifFillPaint.color = cEggP
        p.reset()
        p.addOval(RectF(cx - w * 0.5f, cy - h * 0.22f, cx + w * 0.5f, cy + h * 0.50f), Path.Direction.CW)
        c.drawPath(p, motifFillPaint)
        motifFillPaint.color = cEggH
        p.reset()
        p.addOval(RectF(cx - w * 0.30f, cy + h * 0.02f, cx - w * 0.02f, cy + h * 0.34f), Path.Direction.CW)
        c.drawPath(p, motifFillPaint)
        motifInkPaint.color = cStem
        motifInkPaint.strokeWidth = maxOf(0.7f, h * 0.055f)
        c.drawLine(cx, cy - h * 0.22f, cx, cy - h * 0.46f, motifInkPaint)
        fillLeaf(c, cx, cy - h * 0.22f, h * 0.40f, -2.50f, cLeafM)
        fillLeaf(c, cx, cy - h * 0.22f, h * 0.40f, -0.65f, cLeafM)
    }

    /** 辣椒：红弯椒 + 绿柄。 */
    private fun drawChili(c: Canvas, cx: Float, cy: Float, h: Float) {
        val w = h * 0.32f
        val p = motifPath
        p.reset()
        p.moveTo(cx - w * 0.5f, cy - h * 0.26f)
        p.quadTo(cx + w * 0.60f, cy - h * 0.02f, cx + w * 0.72f, cy + h * 0.34f)
        p.quadTo(cx + w * 0.10f, cy + h * 0.48f, cx - w * 0.5f, cy + h * 0.18f)
        p.close()
        motifFillPaint.color = cChili
        c.drawPath(p, motifFillPaint)
        motifInkPaint.color = cStem
        motifInkPaint.strokeWidth = maxOf(0.7f, h * 0.06f)
        c.drawLine(cx - w * 0.5f, cy - h * 0.26f, cx - w * 0.6f, cy - h * 0.46f, motifInkPaint)
    }

    /** 胡萝卜：橙锥身 + 横纹 + 绿缨。 */
    private fun drawCarrot(c: Canvas, cx: Float, cy: Float, h: Float) {
        val w = h * 0.38f
        val p = motifPath
        p.reset()
        p.moveTo(cx - w * 0.5f, cy - h * 0.14f)
        p.lineTo(cx + w * 0.5f, cy - h * 0.14f)
        p.lineTo(cx, cy + h * 0.50f)
        p.close()
        motifFillPaint.color = cCarrot
        c.drawPath(p, motifFillPaint)
        motifInkPaint.color = blendColor(cCarrot, Color.BLACK, 0.32f)
        motifInkPaint.strokeWidth = maxOf(0.5f, h * 0.035f)
        for (k in 0 until 3) {
            val yy = cy - h * 0.04f + h * 0.16f * k
            val ww = w * (0.34f - 0.08f * k)
            c.drawLine(cx - ww, yy, cx + ww * 0.7f, yy, motifInkPaint)
        }
        fillLeaf(c, cx, cy - h * 0.14f, h * 0.34f, -1.95f, cLeafM)
        fillLeaf(c, cx, cy - h * 0.14f, h * 0.40f, -1.35f, cLeafL)
        fillLeaf(c, cx, cy - h * 0.14f, h * 0.34f, -0.78f, cLeafM)
    }

    // ── 各纹样的整条带画法（疏朗 + 抖动，不机械循环）──

    /** 缠枝花：一条细藤贯穿，沿藤疏朗挂「花/叶/苞/小花」，花色与间距全随机。 */
    private fun garlandBand(c: Canvas, len: Float, bandH: Float, r: Rnd) {
        val mid = bandH * 0.54f
        val amp = bandH * 0.15f
        val vine = motifPath
        vine.reset()
        vine.moveTo(0f, mid)
        val steps = maxOf(10, (len / maxOf(1f, bandH * 0.30f)).toInt())
        for (i in 1..steps) {
            val x = len * i / steps
            vine.lineTo(x, mid + amp * sin((i.toFloat() / steps) * 2f * PI.toFloat()))
        }
        motifInkPaint.color = cStem
        motifInkPaint.style = Paint.Style.STROKE
        motifInkPaint.strokeWidth = maxOf(0.8f, bandH * 0.05f)
        c.drawPath(vine, motifInkPaint)

        var x = bandH * 0.60f
        var i = 0
        while (x < len - bandH * 0.45f) {
            val cy = mid + amp * sin(x / len * 2f * PI.toFloat())
            val s = bandH * r.r(0.33f, 0.42f)
            when ((i + (r.f() * 3f).toInt()) % 7) {
                0, 1 -> fillFlower(c, x, cy - s * 0.24f, s * 1.12f, pickPetal(r))
                2 -> fillFlower(c, x, cy - s * 0.24f, s * 0.60f, pickPetal(r), 6)
                3 -> fillLeaf(c, x, cy, s * 1.55f, -0.60f + r.r(-0.30f, 0.30f), cLeafM)
                4 -> fillLeaf(c, x, cy, s * 1.40f, 2.35f + r.r(-0.25f, 0.25f), cLeafL)
                5 -> fillBud(c, x, cy - s * 0.20f, s * 0.62f, pickPetal(r))
                else -> fillLeaf(c, x, cy, s * 1.25f, -2.55f, cLeafM)
            }
            x += bandH * r.r(0.80f, 1.20f)
            i++
        }
    }

    /** 藤蔓：细藤 + 间挂叶/花苞，最疏。 */
    private fun vineBand(c: Canvas, len: Float, bandH: Float, r: Rnd) {
        val mid = bandH * 0.52f
        val amp = bandH * 0.22f
        val vine = motifPath
        vine.reset()
        vine.moveTo(0f, mid)
        val steps = maxOf(10, (len / maxOf(1f, bandH * 0.35f)).toInt())
        for (i in 1..steps) {
            val x = len * i / steps
            vine.lineTo(x, mid + amp * sin((i.toFloat() / steps) * 3f * PI.toFloat()))
        }
        motifInkPaint.color = cStem
        motifInkPaint.strokeWidth = maxOf(0.8f, bandH * 0.05f)
        c.drawPath(vine, motifInkPaint)
        var x = bandH * 0.7f
        var i = 0
        while (x < len - bandH * 0.5f) {
            val s = bandH * r.r(0.30f, 0.40f)
            val cy = mid + amp * sin(x / len * 3f * PI.toFloat())
            if (i % 3 == 1) fillBud(c, x, cy, s * 0.62f, pickPetal(r))
            else fillLeaf(c, x, cy, s * 1.5f, if (i % 2 == 0) -0.6f else 2.4f, if (i % 2 == 0) cLeafM else cLeafL)
            x += bandH * r.r(0.85f, 1.30f)
            i++
        }
    }

    /** 折枝花：一枝斜出，枝头一朵大花 + 两片叶；枝与枝之间留大空当，最疏朗。 */
    private fun floralBand(c: Canvas, len: Float, bandH: Float, r: Rnd) {
        var x = bandH * 0.85f
        while (x < len - bandH * 0.6f) {
            val h = bandH * r.r(0.78f, 0.92f)
            val br = motifPath
            br.reset()
            br.moveTo(x + h * 0.42f, bandH * 0.95f)
            br.quadTo(x + h * 0.30f, bandH * 0.68f, x + h * 0.04f, bandH * 0.54f)
            motifInkPaint.color = cStem
            motifInkPaint.strokeWidth = maxOf(0.8f, bandH * 0.055f)
            c.drawPath(br, motifInkPaint)
            fillLeaf(c, x + h * 0.24f, bandH * 0.66f, h * 0.72f, -0.55f + r.r(-0.2f, 0.2f), cLeafM)
            fillLeaf(c, x - h * 0.10f, bandH * 0.74f, h * 0.60f, 2.50f, cLeafL)
            fillFlower(c, x + h * 0.02f, bandH * 0.42f, h * 0.62f, pickPetal(r))
            x += bandH * r.r(1.55f, 2.15f)
        }
    }

    /** 花鸟 / 花果：细藤 + 「花 → 鸟/果 → 叶」疏朗交替。 */
    private fun sprigBand(c: Canvas, len: Float, bandH: Float, r: Rnd, kind: Motif) {
        val mid = bandH * 0.54f
        val amp = bandH * 0.15f
        val vine = motifPath
        vine.reset()
        vine.moveTo(0f, mid)
        val steps = maxOf(10, (len / maxOf(1f, bandH * 0.35f)).toInt())
        for (i in 1..steps) {
            val x = len * i / steps
            vine.lineTo(x, mid + amp * sin((i.toFloat() / steps) * 2f * PI.toFloat()))
        }
        motifInkPaint.color = cStem
        motifInkPaint.strokeWidth = maxOf(0.8f, bandH * 0.05f)
        c.drawPath(vine, motifInkPaint)
        var x = bandH * 0.75f
        var i = 0
        while (x < len - bandH * 0.5f) {
            val s = bandH * r.r(0.34f, 0.44f)
            val cy = mid + amp * sin(x / len * 2f * PI.toFloat()) - s * 0.18f
            when (i % 3) {
                0 -> fillFlower(c, x, cy, s * 1.05f, pickPetal(r))
                1 -> if (kind == Motif.BIRD) fillBird(c, x, cy, s * 1.15f,
                        if (r.f() < 0.5f) cBirdBlue else cBirdTeal)
                     else fillFruitC(c, x, cy, s * 1.05f,
                        if (r.f() < 0.5f) cRed else cCarrot)
                else -> fillLeaf(c, x, cy, s * 1.5f, if (r.f() < 0.5f) -0.6f else 2.4f, cLeafM)
            }
            x += bandH * r.r(0.95f, 1.35f)
            i++
        }
    }

    /** 蔬果：细秧串起「白菜—茄子—辣椒—胡萝卜」，高低错落、大小随机，真彩最丰富。 */
    private fun veggieBand(c: Canvas, len: Float, bandH: Float, r: Rnd) {
        val vine = motifPath
        vine.reset()
        vine.moveTo(0f, bandH * 0.30f)
        vine.quadTo(len * 0.5f, bandH * 0.22f, len, bandH * 0.30f)
        motifInkPaint.color = cStem
        motifInkPaint.strokeWidth = maxOf(0.8f, bandH * 0.05f)
        c.drawPath(vine, motifInkPaint)

        var x = bandH * 0.85f
        var i = 0
        while (x < len - bandH * 0.6f) {
            val h = bandH * r.r(0.86f, 1.0f)
            val cy = bandH * 0.56f + bandH * r.r(-0.05f, 0.05f)
            // 反算全局落点后「复位矩阵」直立绘制：菜不会被侧带的 90° 旋转和底带的镜像放倒
            val gx = bandGX(x, cy)
            val gy = bandGY(x, cy)
            c.save()
            c.setMatrix(null)
            when ((i + (r.f() * 2f).toInt()) % 4) {
                0 -> drawCabbage(c, gx, gy, h)
                1 -> drawEggplant(c, gx, gy, h)
                2 -> drawChili(c, gx, gy, h)
                else -> drawCarrot(c, gx, gy, h)
            }
            c.restore()
            x += bandH * r.r(1.00f, 1.30f)
            i++
        }
    }

    /** 柳条：一二条柳丝沿带「自然生长」——丝身轻微摆动、绝不拉直，
     *  柳叶疏朗错落下垂，间距/大小/摆幅全随机；**绝不逐叶等距、绝不左右严格交替**，
     *  免得成了"打毛衣"的锯齿纹。 */
    private fun willowBand(c: Canvas, len: Float, bandH: Float, r: Rnd) {
        val strands = if (bandH > 34f) 2 else 1
        for (k in 0 until strands) {
            val yBase = bandH * (if (strands == 1) 0.34f else 0.26f + 0.32f * k) + bandH * r.r(-0.05f, 0.05f)
            val amp = bandH * r.r(0.08f, 0.16f)          // 丝身横向轻摆
            val cycles = r.r(1.1f, 2.2f)
            val phase = r.f()
            val steps = maxOf(18, (len / maxOf(1f, bandH * 0.22f)).toInt())
            val stem = motifPath
            stem.reset()
            stem.moveTo(0f, yBase)
            for (i in 1..steps) {
                val x = len * i / steps
                stem.lineTo(x, yBase + amp * sin((x / len * cycles + phase) * 2f * PI.toFloat()))
            }
            motifInkPaint.color = cStem
            motifInkPaint.style = Paint.Style.STROKE
            motifInkPaint.strokeWidth = maxOf(0.9f, bandH * 0.05f)
            c.drawPath(stem, motifInkPaint)

            // 叶：垂向下、左右分披；间距随叶长、换边不严格 → 天然疏密，绝不成锯齿
            var x = bandH * r.r(0.30f, 0.85f)
            var side = if (r.f() < 0.5f) 1f else -1f
            while (x < len - bandH * 0.25f) {
                val y = yBase + amp * sin((x / len * cycles + phase) * 2f * PI.toFloat())
                val ln = bandH * r.r(0.30f, 0.52f)
                val base = 1.42f + r.r(-0.55f, 0.35f)     // 基本垂下，左右小幅摆动
                val ang = if (side > 0f) base else PI.toFloat() - base
                fillLeaf(c, x, y, ln, ang, if (r.f() < 0.45f) cLeafM else cLeafL)
                x += ln * r.r(0.72f, 1.60f)
                if (r.f() < 0.62f) side = -side           // 多数左右交替，偶有连续同侧
            }
        }
    }

    /** 生成「传统几何边饰」的路径（局部坐标：x 沿带长、y 沿带宽，y=bandH 一侧贴纸心）。
     *  只有回纹／云纹走这条单色墨线支路；写实纹样另走 drawBandColored。 */
    private fun buildBandPath(pattern: TablePattern, len: Float, bandH: Float) {
        val p = patternBandPath
        p.reset()
        patternPaint.style = Paint.Style.STROKE
        val u = when (pattern) {
            TablePattern.MEANDER -> bandH * 1.70f     // 方折回纹：单元留大空当
            TablePattern.CLOUD -> bandH * 2.60f       // 云头：大小云头交替
            else -> bandH * 2.50f
        }
        val n = (len / u).toInt().coerceAtLeast(1)
        val uu = len / n                              // 整单元排满，两端不留半格
        when (pattern) {
            TablePattern.MEANDER -> buildMeander(p, n, uu, bandH)
            TablePattern.CLOUD -> buildCloud(p, n, uu, bandH)
            else -> {}
        }
    }

    // ───────────────────────── 传统几何边饰（单色墨线） ─────────────────────────
    /** 如意云头：两枚同心拱 + 云脚内卷。 */
    private fun motifCloud(p: Path, cx: Float, cy: Float, r: Float) {
        val w = r; val h = r * 0.72f
        p.addArc(cx - w, cy - h, cx + w, cy + h, 180f, 180f)
        p.addArc(cx - w * 0.55f, cy - h * 0.60f, cx + w * 0.55f, cy + h * 0.60f, 180f, 180f)
        p.addArc(cx - w * 0.34f, cy + h * 0.05f, cx + w * 0.34f, cy + h * 1.15f, 200f, 220f)
    }

    /** 云纹：大云头 → 小云头 → 留白 三拍交替，不再密密排云。 */
    private fun buildCloud(p: Path, n: Int, uu: Float, bandH: Float) {
        val mid = bandH * 0.50f
        for (i in 0 until n) {
            when (i % 3) {
                0 -> motifCloud(p, i * uu + uu * 0.5f, mid, bandH * 0.44f)
                1 -> motifCloud(p, i * uu + uu * 0.5f, mid, bandH * 0.27f)
                else -> { }
            }
        }
    }

    /** 回纹（方折雷纹）：一单元一道连绵方折 + 底轨；单元拉长，不再密排方阵。 */
    private fun buildMeander(p: Path, n: Int, uu: Float, bandH: Float) {
        val railY = bandH                                  // 底轨贴纸心
        p.moveTo(0f, railY)
        p.lineTo(n * uu, railY)
        val rel = floatArrayOf(0.50f, 0.06f, 0.90f, 0.06f, 0.90f, 0.62f, 0.30f, 0.62f, 0.30f, 0.32f, 0.68f, 0.32f)
        for (i in 0 until n) {
            val bx = i * uu
            p.moveTo(bx + rel[0] * uu, bandH)
            var k = 2
            while (k < rel.size) {
                p.lineTo(bx + rel[k] * uu, rel[k + 1] * bandH)
                k += 2
            }
        }
    }

    /** 两色线性混合：t=0 取 a，t=1 取 b。 */
    private fun blendColor(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
    )

    private fun drawBoardBackground(canvas: Canvas) {
        val boardWidth = cellSize * (BOARD_COLS - 1)
        val boardHeight = cellSize * (BOARD_ROWS - 1)
        val left = boardLeft - 8
        val top = boardTop - 8
        val right = boardLeft + boardWidth + 8
        val bottom = boardTop + boardHeight + 8

        // 先画阴影
        canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, shadowPaint)

        // ★「个性化」外置洗净衬体优先：只铺底色，不画经纬线/坐标（我们的棋线在 onDraw 后段再画上）
        val cb = customBg
        if (cb != null && !cb.isRecycled) {
            canvas.save()
            val cpath = Path()
            cpath.addRoundRect(left, top, right, bottom, 12f, 12f, Path.Direction.CW)
            canvas.clipPath(cpath)
            val csrc = Rect(0, 0, cb.width, cb.height)
            val cdst = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            canvas.drawBitmap(cb, csrc, cdst, null)
            canvas.restore()
            return
        }

        // 如果有背景图(BAMBOO/PORCELAIN)，绘制背景图
        val bgBmp = backgroundBitmap
        if (bgBmp != null && !bgBmp.isRecycled) {
            canvas.save()
            val path = Path()
            path.addRoundRect(left, top, right, bottom, 12f, 12f, Path.Direction.CW)
            canvas.clipPath(path)
            val srcRect = Rect(0, 0, bgBmp.width, bgBmp.height)
            val dstRect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            canvas.drawBitmap(bgBmp, srcRect, dstRect, null)
            // ★ 新青花瓷桌布本身已素雅（暖白宣纸+灰蓝缠枝），只需极轻一层中性灰
            //   压住花纹、保证棋线/棋子在其上依然清晰；不再用重蒙层，免得把青花压成灰泥。
            if (bgMode == BgMode.PORCELAIN) {
                bgPaint.shader = null
                bgPaint.style = Paint.Style.FILL
                bgPaint.color = Color.parseColor("#33383838")   // 中性灰，轻降饱和不抹黑
                canvas.drawRect(left, top, right, bottom, bgPaint)
            }
            canvas.restore()
            return
        }

        // 渐变模式：根据bgMode选择不同的渐变方案
        when (bgMode) {
            BgMode.WOOD -> {
                // 木纹暖棕：从上到下的暖棕色渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#C8A87C"), Color.parseColor("#B8956A"),
                              Color.parseColor("#A6845C"), Color.parseColor("#C8A87C")),
                    floatArrayOf(0f, 0.4f, 0.6f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.PARCHMENT -> {
                // 羊皮纸：淡黄到浅棕
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#F5E6C8"), Color.parseColor("#EDD7A8"),
                              Color.parseColor("#E8D098"), Color.parseColor("#F0DEB8")),
                    floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.GREEN -> {
                // 墨绿绒：深绿渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#2D4A38"), Color.parseColor("#243D2E"),
                              Color.parseColor("#1C3024"), Color.parseColor("#2D4A38")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.BLUE_GRAY -> {
                // 深蓝灰：冷色调渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#3A4A5A"), Color.parseColor("#2E3C4A"),
                              Color.parseColor("#24303C"), Color.parseColor("#3A4A5A")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.MARBLE -> {
                // 暖灰大理石：微妙的灰白渐变
                val gradient = LinearGradient(left, top, right, bottom,
                    intArrayOf(Color.parseColor("#D8D0C8"), Color.parseColor("#C8C0B8"),
                              Color.parseColor("#D0C8C0"), Color.parseColor("#DCD4CC")),
                    floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.ROSE_GOLD -> {
                // 玫瑰金：暖粉金色渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#E8C8B8"), Color.parseColor("#D4A890"),
                              Color.parseColor("#C89878"), Color.parseColor("#E0C0A8")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.FOREST -> {
                // 深林绿：浓郁森林绿渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#1A3A24"), Color.parseColor("#152E1C"),
                              Color.parseColor("#102218"), Color.parseColor("#1A3A24")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.OCEAN -> {
                // 海洋蓝：深邃海洋渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#1A3A5C"), Color.parseColor("#152E4A"),
                              Color.parseColor("#102438"), Color.parseColor("#1A3A5C")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.COFFEE -> {
                // 咖啡棕：浓郁咖啡渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#6B4A30"), Color.parseColor("#5A3C24"),
                              Color.parseColor("#4A2E1A"), Color.parseColor("#6B4A30")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.SLATE -> {
                // 石板灰：中性石板渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#5A5A5A"), Color.parseColor("#4A4A4A"),
                              Color.parseColor("#3E3E3E"), Color.parseColor("#5A5A5A")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.AMBER -> {
                // 琥珀色：温暖琥珀渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#D4A040"), Color.parseColor("#C89030"),
                              Color.parseColor("#B87820"), Color.parseColor("#D4A040")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.BURGUNDY -> {
                // 勃艮第红：深红酒红渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#4A1A24"), Color.parseColor("#3A1420"),
                              Color.parseColor("#2E1018"), Color.parseColor("#4A1A24")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            BgMode.CHARCOAL -> {
                // 炭黑：深邃炭黑渐变
                val gradient = LinearGradient(left, top, left, bottom,
                    intArrayOf(Color.parseColor("#2A2A2A"), Color.parseColor("#222222"),
                              Color.parseColor("#1A1A1A"), Color.parseColor("#2A2A2A")),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
                bgPaint.shader = gradient
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                bgPaint.shader = null
            }
            else -> {
                // 纯色（象牙白）
                bgPaint.shader = null
                bgPaint.color = boardBgColor
                bgPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
            }
        }
    }

    private fun drawGridLines(canvas: Canvas) {
        linePaint.color = lineColor
        linePaint.strokeWidth = lineThickness
        linePaint.style = Paint.Style.STROKE

        val boardWidth = cellSize * (BOARD_COLS - 1)
        val boardHeight = cellSize * (BOARD_ROWS - 1)

        linePaint.strokeWidth = lineThickness * 2f
        canvas.drawRect(boardLeft, boardTop, boardLeft + boardWidth, boardTop + boardHeight, linePaint)
        linePaint.strokeWidth = lineThickness

        for (r in 0 until BOARD_ROWS) {
            val y = boardTop + r * cellSize
            canvas.drawLine(boardLeft, y, boardLeft + boardWidth, y, linePaint)
        }

        for (c in 0 until BOARD_COLS) {
            val x = boardLeft + c * cellSize
            canvas.drawLine(x, boardTop, x, boardTop + 4 * cellSize, linePaint)
            canvas.drawLine(x, boardTop + 5 * cellSize, x, boardTop + 9 * cellSize, linePaint)
        }

        canvas.drawLine(boardLeft, boardTop, boardLeft, boardTop + boardHeight, linePaint)
        canvas.drawLine(boardLeft + boardWidth, boardTop, boardLeft + boardWidth, boardTop + boardHeight, linePaint)
    }

    private fun drawRiver(canvas: Canvas) {
        linePaint.color = lineColor
        linePaint.strokeWidth = lineThickness
        linePaint.style = Paint.Style.FILL
        linePaint.textAlign = Paint.Align.CENTER

        // ★ 竖中线 = 棋盘真实水平中心。盘宽 = (BOARD_COLS-1) 格 → 中心 = boardLeft + 4 格。
        //   旧代码按「9 格」取中（boardLeft + 4.5 格），整条河界文字右偏半格 → 楚河汉界/时间全偏右。
        val boardWidth = cellSize * (BOARD_COLS - 1)

        // ── 楚河 / 汉界：把河界「均分两边」——左半幅中心 25%、右半幅中心 75%，
        //    两心以竖中线(50%)严格互为镜像；位置全部由棋盘几何算出 →
        //    换任何机型、任何棋盘尺寸都恒对称。正中那段留白让给「年月日时分秒」。──
        val leftHalfCenter = boardLeft + boardWidth * 0.25f
        val rightHalfCenter = boardLeft + boardWidth * 0.75f
        val riverTextSize = cellSize * 0.42f
        linePaint.textSize = riverTextSize
        linePaint.isFakeBoldText = true
        val riverBaseline = boardTop + 4.5f * cellSize + riverTextSize * 0.35f
        canvas.drawText("楚河", leftHalfCenter, riverBaseline, linePaint)
        canvas.drawText("汉界", rightHalfCenter, riverBaseline, linePaint)

        linePaint.isFakeBoldText = false
        linePaint.style = Paint.Style.STROKE
    }

    // ★ 年月日时分秒专用画笔：描边打底 + 实心叠加，即便有棋子压在河界上也读得清（不铺任何底纸）
    private val riverTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** ★ 年月日时分秒：正落在竖中线上、与楚河/汉界同一条水平线，居于两者正中（用户钦定"最中"）。
     *  本函数在 drawPieces 之后调用 → 它永远画在棋子之上，不管怎么走子都盖不到它。 */
    private fun drawRiverTime(canvas: Canvas) {
        if (!showTime) return
        val boardWidth = cellSize * (BOARD_COLS - 1)
        val centerX = boardLeft + boardWidth / 2f
        val riverCenterY = boardTop + 4.5f * cellSize

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val timeStr = sdf.format(java.util.Date())
        val timeSize = cellSize * 0.24f
        riverTimePaint.textSize = timeSize
        val baseline = riverCenterY + timeSize * 0.36f

        // ★ 时间字色：用户在「全局底色」里选的调色板颜色；-1 = 跟随棋线色。
        val chosen = GlobalBackdrop.riverTimeColor(context)
        val fill = if (chosen == GlobalBackdrop.TIME_FOLLOW_LINE) lineColor else chosen
        // ★ 无辉：不做任何浅色光晕描边，纯实心——跟棋线一个道理，干净利落。
        // ★ 字粗三档（细/中/粗）：细＝轻字重不描边；中/粗用"同色"描边把笔画加厚
        //   （同色加粗，不是发光；所以依然无辉）。
        val weight = GlobalBackdrop.riverTimeWeight(context)
        riverTimePaint.typeface = when (weight) {
            0 -> android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            2 -> android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            else -> android.graphics.Typeface.DEFAULT
        }
        riverTimePaint.color = fill
        riverTimePaint.alpha = 255
        val strokeW = when (weight) { 0 -> 0f; 1 -> timeSize * 0.05f; else -> timeSize * 0.11f }
        if (strokeW > 0f) {
            riverTimePaint.style = Paint.Style.STROKE
            riverTimePaint.strokeWidth = strokeW
            canvas.drawText(timeStr, centerX, baseline, riverTimePaint)
        }
        // 实心字：纯色、不透明、无辉
        riverTimePaint.style = Paint.Style.FILL
        canvas.drawText(timeStr, centerX, baseline, riverTimePaint)
    }

    private fun drawPalace(canvas: Canvas) {
        linePaint.color = lineColor
        linePaint.strokeWidth = lineThickness
        linePaint.style = Paint.Style.STROKE

        val topPalaceLeft = boardLeft + 3 * cellSize
        val topPalaceRight = boardLeft + 5 * cellSize
        val topPalaceTop = boardTop
        val topPalaceBottom = boardTop + 2 * cellSize
        canvas.drawLine(topPalaceLeft, topPalaceTop, topPalaceRight, topPalaceBottom, linePaint)
        canvas.drawLine(topPalaceRight, topPalaceTop, topPalaceLeft, topPalaceBottom, linePaint)

        val botPalaceTop = boardTop + 7 * cellSize
        val botPalaceBottom = boardTop + 9 * cellSize
        canvas.drawLine(topPalaceLeft, botPalaceTop, topPalaceRight, botPalaceBottom, linePaint)
        canvas.drawLine(topPalaceRight, botPalaceTop, topPalaceLeft, botPalaceBottom, linePaint)
    }

    /** ★★★ 炮位 / 兵卒位「十字角标」★★★（对齐皮卡鱼图纸，共 14 个位置）
     *   - 炮位：行 2/7 × 列 1/7（4 个）→ 完整四角「十」字；
     *   - 兵卒位：行 3/6 × 列 0/2/4/6/8（10 个）→ 列 0/8 正落在左右边线上，只画朝盘内的两角。
     *  ★ 一切跟随棋线：颜色 = lineColor、线宽 = lineThickness；角标整体尺寸也随线宽等比放大，
     *    并以格子为下限兜底 —— 用户把棋线调极细时角标仍清晰，调极粗时也不会挤成一团。 */
    private fun drawPositionMarkers(canvas: Canvas) {
        linePaint.color = lineColor
        linePaint.strokeWidth = lineThickness
        linePaint.style = Paint.Style.STROKE

        val u = maxOf(cellSize * 0.045f, lineThickness * 1.8f)  // 角尖离交点的空隙
        val arm = u * 2f                                        // 每根角臂的长度

        // 炮位：完整四角
        for (r in intArrayOf(2, 7)) {
            for (c in intArrayOf(1, 7)) {
                drawCrossMarker(
                    canvas, boardLeft + c * cellSize, boardTop + r * cellSize,
                    u, arm, tl = true, tr = true, bl = true, br = true
                )
            }
        }
        // 兵卒位：左右边线列只留朝盘内的两角
        for (r in intArrayOf(3, 6)) {
            for (c in intArrayOf(0, 2, 4, 6, 8)) {
                val left = c != 0
                val right = c != 8
                drawCrossMarker(
                    canvas, boardLeft + c * cellSize, boardTop + r * cellSize,
                    u, arm, tl = left, tr = right, bl = left, br = right
                )
            }
        }
    }

    /** 单个交点上的十字角标：四角各一个「L」形折角，折角尖朝交点、两臂朝外。 */
    private fun drawCrossMarker(
        canvas: Canvas, cx: Float, cy: Float, gap: Float, arm: Float,
        tl: Boolean, tr: Boolean, bl: Boolean, br: Boolean
    ) {
        if (tl) {
            canvas.drawLine(cx - gap, cy - gap - arm, cx - gap, cy - gap, linePaint)
            canvas.drawLine(cx - gap - arm, cy - gap, cx - gap, cy - gap, linePaint)
        }
        if (tr) {
            canvas.drawLine(cx + gap, cy - gap - arm, cx + gap, cy - gap, linePaint)
            canvas.drawLine(cx + gap, cy - gap, cx + gap + arm, cy - gap, linePaint)
        }
        if (bl) {
            canvas.drawLine(cx - gap, cy + gap, cx - gap, cy + gap + arm, linePaint)
            canvas.drawLine(cx - gap - arm, cy + gap, cx - gap, cy + gap, linePaint)
        }
        if (br) {
            canvas.drawLine(cx + gap, cy + gap, cx + gap, cy + gap + arm, linePaint)
            canvas.drawLine(cx + gap, cy + gap, cx + gap + arm, cy + gap, linePaint)
        }
    }

    private val setupValidDotColor = Color.parseColor("#8844CC44")

    private fun drawHighlights(canvas: Canvas) {
        val b = board ?: return

        if (setupValidPositions.isNotEmpty()) {
            for (pos in setupValidPositions) {
                val (px, py) = getCellCenter(pos.row, pos.col)
                val occupied = b.getPiece(pos) != null
                if (occupied) {
                    dotPaint.color = Color.parseColor("#88FF4444")
                    dotPaint.style = Paint.Style.STROKE
                    dotPaint.strokeWidth = 2f
                    canvas.drawCircle(px, py, pieceRadius * 0.35f, dotPaint)
                } else {
                    dotPaint.color = setupValidDotColor
                    dotPaint.style = Paint.Style.FILL
                    canvas.drawCircle(px, py, pieceRadius * 0.30f, dotPaint)
                }
            }
        }

        // ★ 铁律：只绘制当前步走棋的光标（1步=2个点），不显示任何其他走法
        // 人走用金色，AI走用蓝色，只有一个走法在显示
        for (entry in moveHighlightHistory) {
            val move = entry.move
            val baseColor = if (entry.isAi) aiMoveHighlight else lastMoveHighlight
            highlightPaint.color = baseColor
            drawCellHighlight(canvas, move.from.row, move.from.col, highlightPaint)
            drawCellHighlight(canvas, move.to.row, move.to.col, highlightPaint)
        }

        selectedPosition?.let { pos ->
            highlightPaint.color = selectedHighlight
            drawCellHighlight(canvas, pos.row, pos.col, highlightPaint)

            for (move in legalMoves) {
                val target = b.getPiece(move.to)
                if (target != null) {
                    dotPaint.color = legalMoveCapture
                    dotPaint.style = Paint.Style.STROKE
                    dotPaint.strokeWidth = 3f
                    val (px, py) = getCellCenter(move.to.row, move.to.col)
                    canvas.drawCircle(px, py, pieceRadius + 4f, dotPaint)
                } else {
                    dotPaint.color = legalMoveDot
                    dotPaint.style = Paint.Style.FILL
                    val (px, py) = getCellCenter(move.to.row, move.to.col)
                    canvas.drawCircle(px, py, pieceRadius * 0.25f, dotPaint)
                }
            }
        }
    }

    private fun drawCellHighlight(canvas: Canvas, row: Int, col: Int, paint: Paint) {
        val (px, py) = getCellCenter(row, col)
        paint.style = Paint.Style.FILL
        canvas.drawRect(
            px - pieceRadius - 2, py - pieceRadius - 2,
            px + pieceRadius + 2, py + pieceRadius + 2,
            paint
        )
    }

    private fun drawPieces(canvas: Canvas) {
        val b = board ?: return
        for (r in 0 until BOARD_ROWS) {
            for (c in 0 until BOARD_COLS) {
                val piece = b.getPiece(Position(r, c)) ?: continue
                drawPiece(canvas, r, c, piece)
            }
        }
    }

    // ── 棋子图片资源ID映射（支持皮肤切换）──
    private fun getPieceImageResId(type: PieceType, color: PieceColor): Int {
        val short = when (color) {
            PieceColor.RED -> when (type) {
                PieceType.KING -> "wk"
                PieceType.ADVISOR -> "wa"
                PieceType.ELEPHANT -> "wb"
                PieceType.ROOK -> "wr"
                PieceType.KNIGHT -> "wn"
                PieceType.CANNON -> "wc"
                PieceType.PAWN -> "wp"
            }
            PieceColor.BLACK -> when (type) {
                PieceType.KING -> "bk"
                PieceType.ADVISOR -> "ba"
                PieceType.ELEPHANT -> "bb"
                PieceType.ROOK -> "br"
                PieceType.KNIGHT -> "bn"
                PieceType.CANNON -> "bc"
                PieceType.PAWN -> "bp"
            }
        }
        // 资源名：piece{tag}_{short}，tag=""→piece_wk，tag="2"→piece2_wk
        val resName = "piece$pieceSkinTag" + "_" + short
        return resources.getIdentifier(resName, "drawable", context.packageName)
    }

    private fun drawPiece(canvas: Canvas, row: Int, col: Int, piece: com.pikafish.chess.model.Piece) {
        val (px, py) = getCellCenter(row, col)
        val isRed = piece.color == PieceColor.RED
        // ★ 纯棋子缩放：半径 = 基础半径 × 请求系数，直接线性映射（棋盘不动、不封顶）
        //   棋子原地变大：吃掉格间缝隙，间距自然变小；该多大就多大，不外扩棋盘。
        val scaledRadius = pieceRadius * renderPieceRatio

        // ★「个性化」外置棋子优先（红/黑可来自不同包）
        val customPool = if (isRed) customRedPieces else customBlackPieces
        customPool[piece.type]?.let { customBmp ->
            if (!customBmp.isRecycled) {
                canvas.drawBitmap(customBmp, null,
                    Rect((px - scaledRadius).toInt(), (py - scaledRadius).toInt(),
                         (px + scaledRadius).toInt(), (py + scaledRadius).toInt()), null)
                return
            }
        }

        // 阴影增强立体感
        canvas.drawCircle(px + 3, py + 4, scaledRadius, shadowPaint)

        // ── 优先尝试 3D 立体皮肤棋子图片 ──
        val pieceResId = getPieceImageResId(piece.type, piece.color)
        if (pieceResId != 0) {
            var bmp = pieceBitmapCache[pieceResId]
            if (bmp == null) {
                try {
                    bmp = BitmapFactory.decodeResource(resources, pieceResId)
                    if (bmp != null) {
                        pieceBitmapCache[pieceResId] = bmp
                    }
                } catch (_: Exception) {
                    pieceBitmapCache[pieceResId] = null
                }
            }
            if (bmp != null && !bmp.isRecycled) {
                // 各套 3D 皮肤盘面均按纯直径裁剪对齐（占满画布），共用同一绘制半径
                canvas.drawBitmap(bmp, null,
                    Rect((px - scaledRadius).toInt(), (py - scaledRadius).toInt(),
                         (px + scaledRadius).toInt(), (py + scaledRadius).toInt()), null)
                return
            }
        }

        // ── 图片加载失败时回退到原生文字棋子 ──
        pieceFillPaint.color = if (isRed) pieceRedFill else pieceBlackFill
        canvas.drawCircle(px, py, scaledRadius, pieceFillPaint)

        // 单圈浓稠描边（描边随渲染系数，与实绘半径一致）
        pieceStrokePaint.color = if (isRed) pieceRedStroke else pieceBlackStroke
        pieceStrokePaint.strokeWidth = 4.5f * renderPieceRatio
        pieceStrokePaint.style = Paint.Style.STROKE
        canvas.drawCircle(px, py, scaledRadius, pieceStrokePaint)

        // 文字更大更霸气
        pieceTextPaint.color = if (isRed) pieceRedText else pieceBlackText
        pieceTextPaint.textSize = scaledRadius * 1.2f
        pieceTextPaint.isFakeBoldText = true
        val displayName = piece.displayName()
        val textY = py - (pieceTextPaint.descent() + pieceTextPaint.ascent()) / 2
        canvas.drawText(displayName, px, textY, pieceTextPaint)
    }

    // ── 盘内透明浮层解说（支持滚屏） ──
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = false
    }
    private val overlayBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tapZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }

    private fun drawOverlayText(canvas: Canvas) {
        val boardWidth = cellSize * (BOARD_COLS - 1)
        val boardHeight = cellSize * (BOARD_ROWS - 1)

        // ── 点动模式下且未显示浮层时，在楚河汉界绘制透明触发条 ──
        if (overlayMode == OverlayMode.TAP && !tapOverlayVisible) {
            // 楚河汉界区域：row4~row5之间，中心在 boardTop + 4.5 * cellSize
            val riverCenterY = boardTop + 4.5f * cellSize
            // 小长条：宽度约2.5格，高度约0.55格，居中放在楚河汉界
            val stripWidth = cellSize * 2.5f
            val stripHeight = cellSize * 0.55f
            val stripLeft = boardLeft + boardWidth / 2f - stripWidth / 2f
            val stripRight = stripLeft + stripWidth
            val stripTop = riverCenterY - stripHeight / 2f
            val stripBottom = riverCenterY + stripHeight / 2f
            tapZoneBounds = RectF(stripLeft, stripTop, stripRight, stripBottom)

            // 透明半透明底条
            overlayBgPaint.color = Color.parseColor("#441A1814")
            overlayBgPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(stripLeft, stripTop, stripRight, stripBottom, 6f, 6f, overlayBgPaint)

            // 细边框
            overlayBgPaint.color = Color.parseColor("#66E8D5A3")
            overlayBgPaint.style = Paint.Style.STROKE
            overlayBgPaint.strokeWidth = 1f
            canvas.drawRoundRect(stripLeft, stripTop, stripRight, stripBottom, 6f, 6f, overlayBgPaint)

            // 提示文字
            tapZonePaint.textSize = cellSize * 0.26f
            tapZonePaint.color = Color.parseColor("#DDE8D5A3")
            tapZonePaint.textAlign = Paint.Align.CENTER
            val zoneText = "点动模式"
            val zoneTextY = stripTop + stripHeight / 2f - (tapZonePaint.descent() + tapZonePaint.ascent()) / 2f
            canvas.drawText(zoneText, boardLeft + boardWidth / 2f, zoneTextY, tapZonePaint)
            return
        }

        // OFF模式不显示；TAP模式需要tapOverlayVisible才显示；ON模式有内容就显示
        if (overlayMode == OverlayMode.OFF) { tapZoneBounds = null; return }
        if (overlayMode == OverlayMode.TAP && !tapOverlayVisible) return
        if (overlayLines.isEmpty()) return

        // 浮层放在棋盘下半部分（河界下方），避免遮挡上半棋子
        val overlayLeft = boardLeft + cellSize * 0.8f
        val overlayRight = boardLeft + boardWidth - cellSize * 0.8f
        val overlayWidth = overlayRight - overlayLeft
        val overlayTop = boardTop + 5.2f * cellSize  // 河界下方开始
        val lineHeight = cellSize * 0.44f * tapFontScale
        val textSize = cellSize * 0.3f * tapFontScale

        overlayPaint.textSize = textSize

        // 先计算总行数（自动换行后）
        val wrappedLines = mutableListOf<String>()
        for (line in overlayLines) {
            val wrapped = wrapText(line, overlayPaint, overlayWidth)
            wrappedLines.addAll(wrapped)
        }

        val totalLines = wrappedLines.size
        if (totalLines == 0) return

        val totalContentHeight = totalLines * lineHeight + cellSize * 0.4f
        // 限定浮层最大可视高度（不超过半块棋盘）
        val maxVisibleHeight = boardHeight * 0.48f
        val visibleHeight = minOf(totalContentHeight, maxVisibleHeight)
        overlayTotalContentHeight = totalContentHeight
        overlayVisibleHeight = visibleHeight

        // 如果内容超出可视区，允许滚屏
        val canScroll = totalContentHeight > visibleHeight
        val clampedOffset = if (canScroll) {
            overlayScrollOffset.coerceIn(0f, totalContentHeight - visibleHeight)
        } else {
            0f
        }
        overlayScrollOffset = clampedOffset

        val bgLeft = overlayLeft - cellSize * 0.2f
        val bgTop = overlayTop
        val bgRight = overlayRight + cellSize * 0.2f
        val bgBottom = overlayTop + visibleHeight

        // 存储浮层边界（用于点击检测）
        tapOverlayBounds = RectF(bgLeft, bgTop, bgRight, bgBottom)

        // 半透明毛玻璃背景
        canvas.save()
        val clipPath = Path()
        clipPath.addRoundRect(bgLeft, bgTop, bgRight, bgBottom, 10f, 10f, Path.Direction.CW)
        canvas.clipPath(clipPath)

        overlayBgPaint.color = Color.parseColor("#AA1A1814")
        overlayBgPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, 10f, 10f, overlayBgPaint)

        // ── 点动模式：绘制关闭按钮（右上角小叉） ──
        if (overlayMode == OverlayMode.TAP) {
            val closeSize = cellSize * 0.32f
            val closeX = bgRight - closeSize - cellSize * 0.15f
            val closeY = bgTop + cellSize * 0.15f
            tapCloseBounds = RectF(closeX, closeY, closeX + closeSize, closeY + closeSize)

            // 关闭按钮背景圆
            overlayBgPaint.color = Color.parseColor("#99222222")
            canvas.drawCircle(closeX + closeSize / 2f, closeY + closeSize / 2f, closeSize / 2f, overlayBgPaint)

            // 画叉
            val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#CCCCCC")
                strokeWidth = 2.5f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            val padding = closeSize * 0.3f
            canvas.drawLine(closeX + padding, closeY + padding, closeX + closeSize - padding, closeY + closeSize - padding, closePaint)
            canvas.drawLine(closeX + closeSize - padding, closeY + padding, closeX + padding, closeY + closeSize - padding, closePaint)
        } else {
            tapCloseBounds = null
        }

        // 逐行绘制文字（应用滚屏偏移）
        overlayPaint.color = Color.parseColor("#FFE8D5A3")
        overlayPaint.style = Paint.Style.FILL
        val textStartY = overlayTop + cellSize * 0.2f - clampedOffset
        for (i in wrappedLines.indices) {
            val textY = textStartY + (i + 1) * lineHeight - overlayPaint.descent() * 0.3f
            // 只在可视区域内绘制
            if (textY > overlayTop - lineHeight && textY < overlayTop + visibleHeight + lineHeight) {
                canvas.drawText(wrappedLines[i], overlayLeft, textY, overlayPaint)
            }
        }

        // 如果可滚屏，绘制滚动指示器
        if (canScroll) {
            val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#44E8D5A3")
                style = Paint.Style.FILL
            }
            val indicatorWidth = cellSize * 0.06f
            val indicatorHeight = visibleHeight * (visibleHeight / totalContentHeight)
            val indicatorX = bgRight - indicatorWidth - cellSize * 0.1f
            val indicatorY = bgTop + (clampedOffset / (totalContentHeight - visibleHeight)) * (visibleHeight - indicatorHeight)
            canvas.drawRoundRect(indicatorX, indicatorY, indicatorX + indicatorWidth, indicatorY + indicatorHeight, 4f, 4f, indicatorPaint)
        }

        canvas.restore()
    }

    /** 简单自动换行：按宽度截断 */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            val test = sb.toString() + ch
            if (paint.measureText(test) > maxWidth) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.clear()
                }
                sb.append(ch)
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    /** 点动模式：显示解说浮层 */
    private fun showTapOverlay() {
        tapOverlayVisible = true
        overlayScrollOffset = 0f  // 重置滚屏位置
        invalidate()
    }

    /** 关闭点动浮层（不干扰走棋） */
    fun dismissTapOverlay() {
        tapOverlayVisible = false
        tapOverlayBounds = null
        tapCloseBounds = null
        overlayScrollOffset = 0f
        isScrolling = false
        invalidate()
    }

    private fun getCellCenter(row: Int, col: Int): Pair<Float, Float> {
        val displayRow = if (flipped) 9 - row else row
        val displayCol = if (flipped) 8 - col else col
        val px = boardLeft + displayCol * cellSize
        val py = boardTop + displayRow * cellSize
        return Pair(px, py)
    }

    private fun getPositionFromTouch(x: Float, y: Float): Position? {
        for (r in 0 until BOARD_ROWS) {
            for (c in 0 until BOARD_COLS) {
                val (px, py) = getCellCenter(r, c)
                if (Math.abs(x - px) < pieceRadius * renderPieceRatio && Math.abs(y - py) < pieceRadius * renderPieceRatio) {
                    return Position(r, c)
                }
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = board ?: return true

        // ── 点动模式：全新交互逻辑 ──
        if (overlayMode == OverlayMode.TAP) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.y
                    isScrolling = false

                    // 如果浮层正在显示
                    if (tapOverlayVisible) {
                        // 检查是否点击了关闭按钮
                        val closeBounds = tapCloseBounds
                        if (closeBounds != null && closeBounds.contains(event.x, event.y)) {
                            dismissTapOverlay()
                            return true
                        }
                        // 检查是否在浮层区域内 → 准备滚屏，不处理走棋
                        val overlayBounds = tapOverlayBounds
                        if (overlayBounds != null && overlayBounds.contains(event.x, event.y)) {
                            isScrolling = true
                            return true
                        }
                        // 点击浮层外部 → 关闭浮层，然后继续走棋逻辑
                        dismissTapOverlay()
                        // 继续走棋逻辑（不return，继续往下）
                    } else {
                        // 浮层未显示，检查是否点击了底部触发区域
                        val zoneBounds = tapZoneBounds
                        if (zoneBounds != null && zoneBounds.contains(event.x, event.y)) {
                            showTapOverlay()
                            return true
                        }
                        // 未在触发区域 → 正常走棋（不return，继续往下）
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScrolling && tapOverlayVisible) {
                        val dy = lastTouchY - event.y  // 手指向上滑 → 内容向上滚
                        lastTouchY = event.y
                        overlayScrollOffset += dy
                        // 边界钳制在drawOverlayText中处理
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isScrolling) {
                        isScrolling = false
                        return true
                    }
                }
            }
            // 如果已经处理了（浮层正在显示且点击在浮层内），不继续走棋
            if (tapOverlayVisible && event.action == MotionEvent.ACTION_DOWN) {
                val overlayBounds = tapOverlayBounds
                if (overlayBounds != null && overlayBounds.contains(event.x, event.y)) {
                    return true
                }
            }
        }

        // ── 正常走棋逻辑 ──
        if (event.action != MotionEvent.ACTION_DOWN) return true

        // ★ 浏览历史时也允许选子走棋——走棋即创建变着（原主线自动保存为变着分支）
        // viewingHistory 标志仅用于UI显示，不再阻止走棋

        val pos = getPositionFromTouch(event.x, event.y)
        if (pos == null) {
            return true
        }

        if (onSetupTap != null) {
            onSetupTap?.invoke(pos.row, pos.col)
            return true
        }

        if (selectedPosition == null) {
            val piece = b.getPiece(pos)
            if (piece != null && piece.color == b.currentPlayer) {
                selectedPosition = pos
                legalMoves = b.getLegalMoves(b.currentPlayer).filter { it.from == pos }
                invalidate()
            }
        } else {
            if (pos == selectedPosition) {
                selectedPosition = null
                legalMoves = emptyList()
                invalidate()
                return true
            }

            val piece = b.getPiece(selectedPosition!!)
            if (piece != null && piece.color == b.currentPlayer) {
                val otherPiece = b.getPiece(pos)
                if (otherPiece != null && otherPiece.color == b.currentPlayer) {
                    selectedPosition = pos
                    legalMoves = b.getLegalMoves(b.currentPlayer).filter { it.from == pos }
                    invalidate()
                    return true
                }

                val move = Move(selectedPosition!!, pos, piece, b.getPiece(pos))
                val success = onMoveMade?.invoke(move) ?: false
                // ★ 不在此处添加高亮，由 onMoveMade 回调中 restoreHighlightsFromHistory 统一处理
                // 避免高亮与实际走棋不同步
                selectedPosition = null
                legalMoves = emptyList()
                invalidate()
            }
        }
        return true
    }
}