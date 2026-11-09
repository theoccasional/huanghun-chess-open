package com.pikafish.chess.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ★★★ 主界面棋盘视觉「唯一事实源」（跨进程跟随绑定）★★★
 *
 * 挑战 / 解惑 / 背谱 三个沙盒（分别跑在 :challenge / :sandbox / 主进程）的棋盘视觉
 * 一律只认主界面在 pikafish_chess_prefs 里落盘的这份设置：
 *   - 棋盘大小（board_scale）→ 沙盒棋盘容器高度 = 基准高 × 同一倍数，并与主界面一样
 *     再把该倍数同步进棋盘视图（双保险对齐主界面渲染，误差 <0.2%，肉眼完全一致）
 *   - 棋子大小（piece_scale）→ 同一倍率
 *   - 棋线颜色/粗细（line_color_idx / line_thick_idx）→ 与主界面下拉框同一张表
 *   - 盘面背景（bg_mode）→ 同一 BgMode（纯色/竹林/青花瓷）
 *   - 棋子皮肤（piece_skin）→ 6 套 3D 立体皮肤
 *   - 「个性化」外置皮肤（红/黑棋子 + 洗净背景）→ 与主界面同源（SkinManager 落盘文件）
 *
 * SharedPreferences 同包同文件跨进程互读互写安全；三个沙盒全部在 setContentView 前
 * 一次性套好 → 进沙盒不闪屏、不跳动、不串位，跟主界面"雷打不动"。
 */
object BoardVisualPrefs {

    // ── 与 MainActivity 完全一致的键名 ──
    const val PREFS_NAME = "pikafish_chess_prefs"
    const val KEY_BOARD_SCALE = "board_scale"
    const val KEY_PIECE_SCALE = "piece_scale"
    const val KEY_LINE_COLOR_IDX = "line_color_idx"
    const val KEY_LINE_THICK_IDX = "line_thick_idx"
    const val KEY_BG_MODE = "bg_mode"
    const val KEY_PIECE_SKIN = "piece_skin"
    /** ★ 棋桌风格（ChessBoardView.TableStyle）索引：与主界面 visual 面板同键 */
    const val KEY_TABLE_STYLE = "table_style"
    /** ★ 点动/盘内讲解字号倍率：与主界面同键 */
    const val KEY_TAP_FONT_SCALE = "tap_font_scale"
    /** ★ 主界面当前真实渲染的棋盘格子像素（唯一权威）：三个沙盒直接吃同一像素，
     *  杜绝"各按自己可视带算 bandFitCell → 棋盘各自为政"的问题。 */
    const val KEY_RENDER_CELL = "render_cell_px"

    /** ★ 楚河汉界时间显示开关（与主界面 btnTimeToggle 同键）：主界面一开，
     *  挑战/背谱/解惑三个大棋盘一起开；主界面一关，三个一起关 —— 全局统一调配。 */
    const val KEY_SHOW_TIME = "show_time_on_board"

    /** ★★「棋盘坐标」主界面落盘的整盘纵向偏移（px，正=下移、负=上移）：
     *   挑战/解惑/背谱三个沙盒同样跟随，主界面怎么调、沙盒就是什么样。 */
    const val KEY_BOARD_Y_OFFSET = "board_y_offset_px"

    /** ★ 顶栏高度（dp）：与主界面 layoutStatusBar 完全一致，三个沙盒顶栏统一用这个高度。 */
    const val TOP_BAR_DP = 38

    /** 与主界面完全一致的棋线配色表（索引即主界面下拉框索引） */
    val lineColors = listOf(
        "古铜色" to "#5D4037", "朱砂红" to "#C62828", "墨玉绿" to "#1B5E20",
        "藏蓝" to "#283593", "银灰" to "#607D8B", "金色" to "#FFB300",
        "象牙白" to "#FFE0B2", "琥珀橙" to "#E65100", "紫檀" to "#4A148C",
        "青金石" to "#1565C0", "烟熏黑" to "#212121", "暗金" to "#8D6E63"
    )

    /** 与主界面完全一致的棋线粗细表（索引即主界面下拉框索引） */
    val lineThicknesses = listOf(
        "极细" to 0.8f, "细线" to 1.2f, "标准" to 1.8f,
        "粗线" to 2.5f, "超粗" to 3.5f, "极粗" to 4.5f
    )

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 与主界面顶栏完全一致的背景/文字色（status_bar_bg / status_bar_text） */
    private const val BAR_BG = "#FFF3C4"
    private const val BAR_TEXT = "#5D4037"

    /**
     * ★ 沙盒顶栏：与主界面顶栏（layoutStatusBar）逐项克隆 —— 悬浮贴纸式，高 38dp、全宽、
     *   背景 #FFF3C4、elevation 10dp、水平内边距 10dp、垂直居中的正标题。
     *   用法：把返回值 addView 到 FrameLayout 根，布局参数用
     *   FrameLayout.LayoutParams(MATCH_PARENT, TOP_BAR_DP 的 px, gravity=Gravity.TOP)。
     *   @param rightLabel 右侧小字（可选，类似主界面右侧引擎信息；null 则不显示）
     */
    fun buildTopBar(ctx: Context, title: String, rightLabel: String? = null): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        // ★ 顶栏底色/文字色 全程跟随主界面「菜单底色」：主界面在视觉里怎么选菜单底色，
        //   挑战/背谱/解惑的顶栏就跟着变成什么样，跨界面颜色统一。
        val menuBg = GlobalBackdrop.menuBackgroundColor(ctx)
        val menuFg = GlobalBackdrop.menuForeground(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
            background = android.graphics.drawable.ColorDrawable(menuBg)
            elevation = 10 * d
            addView(TextView(ctx).apply {
                text = title
                setTextColor(menuFg)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!rightLabel.isNullOrBlank()) {
                addView(TextView(ctx).apply {
                    text = rightLabel
                    setTextColor(menuFg)
                    textSize = 11f
                })
            }
        }
    }

    /** 主界面落盘的棋盘缩放倍数（滑条锚点，永不漂移）；缺省 88%（稳妥上限区 88%~89%） */
    fun boardScale(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_BOARD_SCALE, 0.88f).coerceIn(0.5f, 2.0f)

    /** 主界面落盘的棋子缩放倍数 */
    fun pieceScale(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_PIECE_SCALE, 1.17f).coerceIn(0.5f, 1.5f)

    fun lineColorIdx(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LINE_COLOR_IDX, 0).coerceIn(0, lineColors.size - 1)

    fun lineThickIdx(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LINE_THICK_IDX, 2).coerceIn(0, lineThicknesses.size - 1)

    fun bgModeIdx(ctx: Context): Int = prefs(ctx).getInt(KEY_BG_MODE, 0)

    fun pieceSkinIdx(ctx: Context): Int =
        prefs(ctx).getInt(KEY_PIECE_SKIN, 0).coerceIn(0, 5)

    /** 主界面当前棋线颜色（解析后的 Int） */
    fun lineColor(ctx: Context): Int = try {
        Color.parseColor(lineColors[lineColorIdx(ctx)].second)
    } catch (_: Exception) {
        Color.parseColor("#5D4037")
    }

    /** 主界面当前棋线粗细 */
    fun lineThickness(ctx: Context): Float = lineThicknesses[lineThickIdx(ctx)].second

    /** 读主界面落盘的真实渲染格子像素（0=尚无/未就绪） */
    fun renderCellPx(ctx: Context): Float = prefs(ctx).getFloat(KEY_RENDER_CELL, 0f)

    /** ★ 楚河汉界时间显示开关：主界面「时间:开/关」落盘的这一份，三个沙盒据此跟齐 */
    fun showTime(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHOW_TIME, false)

    /** ★★ 主界面落盘的「棋盘坐标」整盘纵向偏移（px，正=下移、负=上移），越界由棋盘自身钳制 */
    fun boardYOffset(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_BOARD_Y_OFFSET, 0f).coerceIn(
            -ChessBoardView.MAX_Y_OFFSET_PX, ChessBoardView.MAX_Y_OFFSET_PX
        )

    /**
     * ★ 一键跟随：把主界面落盘的棋盘/棋子/棋线/背景/皮肤全部套到任意棋盘视图上。
     *   必须在 setContentView 之前调用，进沙盒首帧即与主界面完全一致，绝不闪屏跳动。
     *   ★ 棋盘格尺寸：优先用主界面真实渲染像素(KEY_RENDER_CELL)——这是"统一调度"的
     *   关键。若未就绪(0)则退回比例路径(boardScale)兜底。
     */
    fun applyToBoard(ctx: Context, board: ChessBoardView) {
        try {
            // 棋盘：主界面真实格子像素优先（与主界面逐像素一致）；否则退回比例锚点。
            val renderCell = renderCellPx(ctx)
            if (renderCell > 0f) {
                board.setAbsoluteCellSize(renderCell)
            } else {
                board.setBoardScale(boardScale(ctx))
            }
            // 棋子：与主界面同倍率（渲染直径 = 格 × 0.47 × 倍数）；absolute 像素路径下
            // 仍需单独设棋子倍率，保证棋子大小与主界面一致。
            board.setPieceScale(pieceScale(ctx))
            // ★★「棋盘坐标」整盘纵向偏移：主界面怎么调、沙盒就怎么跟随（不调=保持0=不调状态）
            board.setBoardYOffset(boardYOffset(ctx))
            // 棋线颜色 / 粗细
            board.setLineColor(lineColor(ctx))
            board.setLineThickness(lineThickness(ctx))
            // 盘面背景（纯色 / 竹林 / 青花瓷）
            board.setBackgroundMode(
                ChessBoardView.BgMode.values().getOrElse(bgModeIdx(ctx)) { ChessBoardView.BgMode.SOLID }
            )
            // 棋子皮肤（6 套 3D：璀璨黄金/水晶琉璃/烈焰木炭/鼓形雕花/羊脂白玉/青花鼓形）
            board.setPieceSkin(pieceSkinIdx(ctx))
            // ★ 棋桌：主界面视觉里怎么选棋桌、沙盒就怎么跟（新增，之前漏了）
            board.setTableStyle(
                ChessBoardView.TableStyle.from(prefs(ctx).getInt(KEY_TABLE_STYLE, 0))
            )
            // ★ 点动/盘内讲解字号：与主界面同倍率
            board.setTapFontScale(
                prefs(ctx).getFloat(KEY_TAP_FONT_SCALE, 1.0f).coerceIn(0.6f, 1.8f)
            )
            // ★★ 楚河汉界时间：主界面「时间:开/关」全局统一调配 —— 挑战/背谱/解惑三个沙盒
            //   大棋盘同步跟随（棋盘自带秒针，进沙盒即实时走字，不用各宿主另写定时器）。
            board.setShowTime(showTime(ctx))
            // ★「个性化」外置皮肤（红/黑棋子 + 洗净背景）：与主界面同源同文件，跟随到底
            try { SkinManager.applyTo(ctx, board) } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /**
     * 沙盒棋盘容器高度（px）：基准高(dp) × 主界面棋盘倍数。
     *   基准高与主界面棋盘外罩理想高度同源（≈1.16×屏宽，略大于盘面所需 ≈1.13×屏宽，
     *   按宽度铺满、两侧不留空白窄条）→ 沙盒棋盘与主界面棋盘"完全一样大"。
     */
    fun boardHeightPx(ctx: Context, baseDp: Int): Int =
        (baseDp * ctx.resources.displayMetrics.density * boardScale(ctx)).toInt()
            .coerceAtLeast(200)

    /**
     * ★ 沙盒棋盘"可用空间钳制"（复刻主界面 tuneBoardVsThinking 的"棋盘高度 ≤ 可用空间"）：
     *   首次布局稳定后，把棋盘容器高度钳到本沙盒剩余纵空间内——若把红方底线顶到下方
     *   按钮后面/挤出首屏（棋盘过高），就自动按可用高度收窄，保证红底线完整露出。
     *   只有"过高"才收窄；主界面给的比例刚好放得下时保持原值，因此沙盒棋盘与主界面
     *   尺寸仍完全一致、不闪烁不跳动。
     *
     * @param viewport 拥有固定高度的纵空间基准（ScrollView 传 ScrollView 本身；普通
     *                 垂直 LinearLayout 传该根容器），其 height = 可用视口高。
     * @param hostRoot 与棋盘同级的兄弟所在容器（用于累加固定高度兄弟占用的纵空间）。
     *                 weight>0 的弹性/滚动区不计入——它们负责吸收剩余空间，不挤占棋盘。
     */
    fun clampBoardToFit(boardWrap: View, viewport: View, hostRoot: ViewGroup) {
        try {
            viewport.post {
                val vh = viewport.height
                if (vh <= 0) return@post
                var reserved = 0
                for (i in 0 until hostRoot.childCount) {
                    val c = hostRoot.getChildAt(i) ?: continue
                    if (c === boardWrap) continue              // 棋盘本身不计
                    if (c.visibility != View.VISIBLE) continue // 已隐藏不计
                    val lp = c.layoutParams
                    if (lp is LinearLayout.LayoutParams && lp.weight > 0f) continue // 弹性/滚动区
                    reserved += c.height
                }
                val margin = (6f * viewport.context.resources.displayMetrics.density).toInt()
                val avail = (vh - reserved - margin).coerceAtLeast(180)
                val blp = boardWrap.layoutParams
                if (blp is LinearLayout.LayoutParams && blp.height > avail) {
                    blp.height = avail
                    boardWrap.requestLayout()
                }
            }
        } catch (_: Exception) {}
    }
}
