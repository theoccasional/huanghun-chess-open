package com.pikafish.chess.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.PieceColor
import kotlin.math.roundToInt

/**
 * 节点小棋盘预览：扁平木纹盘、细网格线、
 * 扁平圆子(直接画中文字，小尺寸下比子力贴图更清爽不糊)、楚河汉界。
 *
 * ★ 走子信息：用金色 from/to 双格高亮标出「进来这一步棋子从哪走到哪」，
 *   与大棋盘光标同款；看清谁走的、走的哪一步，信息量最大且毫不啰嗦。
 */
class MiniGameBoardView(context: Context) : android.view.View(context) {

    private var board: Board? = null
    private var from: Pair<Int, Int>? = null   // 起点 (row, col)
    private var to: Pair<Int, Int>? = null     // 终点 (row, col)

    // ★ 可选的走子交互（默认全关 → 与原来的纯预览完全一致，既有用法一个字都不变）：
    //   onTapCell 非空才接管触摸；selCell 画「选中圈」；targetCells 画「可走点」。
    var onTapCell: ((Int, Int) -> Unit)? = null
    private var selCell: Pair<Int, Int>? = null
    private var targetCells: Set<Pair<Int, Int>> = emptySet()

    // 命中检测用的几何（onDraw 时记下，触摸换算同一套，所见即所点）
    private var geoCell = 0f
    private var geoOx = 0f
    private var geoOy = 0f

    // ★ 引擎推荐着法箭头：绿箭头 from→to
    private var suggestFrom: Pair<Int, Int>? = null
    private var suggestTo: Pair<Int, Int>? = null
    private val suggestArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2ECC40.toInt()   // 亮绿
        style = Paint.Style.STROKE; strokeWidth = dp(2.6f); strokeCap = Paint.Cap.ROUND
    }
    private val suggestArrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2ECC40.toInt(); style = Paint.Style.FILL
    }

    // 扁平木纹盘
    private val wood = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC6A06B.toInt() }
    private val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0xFF8A6438.toInt()
    }
    private val majorGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0xFF7E5A30.toInt()
    }
    private val riverText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF744C22.toInt(); textAlign = Paint.Align.CENTER
    }
    // ★ 走子标识改为高对比富蓝色四方角标（截图框选样式）：终点=目标位=走过的棋子，粗蓝；起点=来源位，浅蓝
    private val toFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
        color = 0xFF1E88FF.toInt()   // 亮蓝
    }
    private val toFrameGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f); strokeCap = Paint.Cap.ROUND
        color = 0x3366B3FF.toInt()   // 外围柔光，加深对比
    }
    private val fromFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.6f); strokeCap = Paint.Cap.ROUND
        color = 0xCC3D9BFF.toInt()   // 浅蓝
    }
    // ★ 走子交互：选中圈的柔光描边 + 可走点的小圆点（书页小棋盘用）
    private val selRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.2f)
        color = 0xFFFFC53D.toInt()   // 琥珀金
    }
    private val targetDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xAA2ECC40.toInt()   // 半透明绿点
    }
    // 扁平棋子
    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5484D.toInt() }
    private val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF44505E.toInt() }
    private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(0.8f)
    }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    fun setBoard(b: Board, from: Pair<Int, Int>?, to: Pair<Int, Int>?) {
        board = b; this.from = from; this.to = to; invalidate()
    }

    /** 设置/清除引擎推荐着法箭头（绿箭头 from→to）。传 null 清除。 */
    fun setSuggestedMove(from: Pair<Int, Int>?, to: Pair<Int, Int>?) {
        suggestFrom = from; suggestTo = to; invalidate()
    }

    /** 设置走子选中态：选中格画金圈，可走格画绿点。传 null / 空集即清除。 */
    fun setSelection(sel: Pair<Int, Int>?, targets: Set<Pair<Int, Int>>) {
        selCell = sel
        targetCells = targets
        invalidate()
    }

    /** 触摸→格点换算：只有 onTapCell 非空时才接管，其余场景一律不碰触摸（纯预览不受影响）。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cb = onTapCell ?: return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val cell = geoCell
        if (cell <= 0f) return true
        val c = ((event.x - geoOx) / cell).roundToInt()
        val r = ((event.y - geoOy) / cell).roundToInt()
        if (r in 0..9 && c in 0..8) cb(r, c)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = board ?: return
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val r = dp(8f)
        // 扁平木纹盘底（圆角）
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, wood)

        // ── 棋盘几何：对照主棋盘画法，取区域宽高中较小者作统一格边，
        //    按 9 列 × 10 行交叉点等比铺排，整体在区域内居中，不拉伸不裁切。
        //    ★ 关键：先为「最外圈棋子半径(0.45格)」留出余量再算格边 ——
        //     可用尺寸按 8.9 格宽 / 9.9 格高折算，于是左右边框(ox)与上下边框(oy)
        //     必然 ≥ 子半径，棋子(含边框)永不被盘边裁缺；外框随之加宽，看着更从容。 ──
        val outer = dp(7f)                       // 盘外沿留白（比原先 6dp 更宽）
        val cell = minOf((w - outer * 2) / 8.92f, (h - outer * 2) / 9.92f)
        val ox = (w - cell * 8f) / 2f   // 水平居中（含子半径余量）
        val oy = (h - cell * 9f) / 2f   // 垂直居中（含子半径余量）
        geoCell = cell; geoOx = ox; geoOy = oy   // ★ 记下几何，供触摸命中检测复用
        fun cx(c: Int) = ox + cell * c
        fun cy(rr: Int) = oy + cell * rr

        // ── 网格：上下半区纵线全画；河界中间只保留左右两侧纵线边框（贯穿河界）。
        //    ★ 横向线一律全画（含楚河汉界上下两条横线）──
        for (c in 0..8) {
            if (c == 0 || c == 8) {
                canvas.drawLine(cx(c), cy(0), cx(c), cy(9), gridLine)   // 左右边框贯通河界
            } else {
                canvas.drawLine(cx(c), cy(0), cx(c), cy(4), gridLine)
                canvas.drawLine(cx(c), cy(5), cx(c), cy(9), gridLine)
            }
        }
        for (rr in 0..9)   canvas.drawLine(cx(0), cy(rr), cx(8), cy(rr), gridLine)
        // 九宫斜线
        fun palace(r0: Int, r1: Int) {
            val x0 = cx(3); val x1 = cx(5); val a = cy(r0); val b = cy(r1)
            canvas.drawLine(x0, a, x1, b, majorGrid); canvas.drawLine(x1, a, x0, b, majorGrid)
        }
        palace(0, 2); palace(7, 9)
        // 楚河 汉界
        val y4 = cy(4); val y5 = cy(5)
        riverText.textSize = cell * 0.62f
        val midY = (y4 + y5) / 2f
        val fm = riverText.fontMetrics
        val base = midY - (fm.ascent + fm.descent) / 2f
        canvas.drawText("楚河", cx(2), base, riverText)
        canvas.drawText("汉界", cx(6), base, riverText)

        // ── 走子标识：高对比富蓝色四方角标框住「走过的棋子」；终点粗蓝+柔光、起点浅蓝 ──
        //    四方角标贴方格四角（截图框选样式），棋子画中不遮角，醒目且不唬人
        fun frameSquare(rr: Int, cc: Int, p: Paint) {
            val x = cx(cc); val y = cy(rr); val h = cell * 0.5f; val L = cell * 0.30f
            canvas.drawLine(x - h, y - h, x - h + L, y - h, p)   // 左上
            canvas.drawLine(x - h, y - h, x - h, y - h + L, p)
            canvas.drawLine(x + h, y - h, x + h - L, y - h, p)   // 右上
            canvas.drawLine(x + h, y - h, x + h, y - h + L, p)
            canvas.drawLine(x - h, y + h, x - h + L, y + h, p)   // 左下
            canvas.drawLine(x - h, y + h, x - h, y + h - L, p)
            canvas.drawLine(x + h, y + h, x + h - L, y + h, p)   // 右下
            canvas.drawLine(x + h, y + h, x + h, y + h - L, p)
        }
        to?.let { (rr, cc) -> frameSquare(rr, cc, toFrameGlow); frameSquare(rr, cc, toFrame) }
        from?.let { (rr, cc) -> frameSquare(rr, cc, fromFrame) }

        // ── ★ 可走点：选中棋子后，每个能落子的交叉点画个绿点（有子处画大圈，空处画小点）──
        for ((rr, cc) in targetCells) {
            val x = cx(cc); val y = cy(rr)
            if (b.pieceAt(rr, cc) != null) {
                canvas.drawCircle(x, y, cell * 0.50f, targetDot)
            } else {
                canvas.drawCircle(x, y, cell * 0.16f, targetDot)
            }
        }

        // ── 扁平圆子（中文字）：直径≈0.9格边，字体随棋子等比缩放 ──
        val radius = cell * 0.45f
        chipText.textSize = cell * 0.62f
        for (rr in 0..9) for (cc in 0..8) {
            val p = b.pieceAt(rr, cc) ?: continue
            val x = cx(cc); val y = cy(rr)
            val isRed = p.color == PieceColor.RED
            canvas.drawCircle(x, y, radius, if (isRed) redFill else blackFill)
            chipStroke.color = if (isRed) 0xFFB93A3F.toInt() else 0xFF2B333D.toInt()
            canvas.drawCircle(x, y, radius, chipStroke)
            chipText.color = if (isRed) 0xFFFFF6E0.toInt() else 0xFFE9EEF3.toInt()
            val fm2 = chipText.fontMetrics
            val baseline = y - (fm2.ascent + fm2.descent) / 2f
            canvas.drawText(p.displayName(), x, baseline, chipText)
        }

        // ── ★ 选中圈：压在棋子之上画金圈，一眼看清「我现在点的是哪颗子」──
        selCell?.let { (rr, cc) ->
            canvas.drawCircle(cx(cc), cy(rr), radius + dp(1.5f), selRing)
        }

        // ── 引擎推荐着法箭头：最上层绘制，绿箭头 from→to，尾端粗头端尖 ──
        val sf = suggestFrom; val st = suggestTo
        if (sf != null && st != null) {
            val x0 = cx(sf.second); val y0 = cy(sf.first)
            val x1 = cx(st.second); val y1 = cy(st.first)
            // 相对目标点往回缩一点，避免箭头全被棋子盖住，但整体醒目
            val dx = x1 - x0; val dy = y1 - y0
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val ux = dx / len; val uy = dy / len
            val ts = (x1 - ux * cell * 0.55f); val te = (y1 - uy * cell * 0.55f)
            canvas.drawLine(x0, y0, ts, te, suggestArrow)
            // 箭头头部（等腰三角形）
            val headLen = cell * 0.34f; val headHalf = cell * 0.18f
            val hx = ux; val hy = uy
            val px = -hy; val py = hx
            val tipX = x1 - ux * cell * 0.30f; val tipY = y1 - uy * cell * 0.30f
            val baseX = x1 - ux * cell * 0.92f; val baseY = y1 - uy * cell * 0.92f
            val aX = baseX + px * headHalf; val aY = baseY + py * headHalf
            val aX2 = baseX - px * headHalf; val aY2 = baseY - py * headHalf
            val hPath = android.graphics.Path()
            hPath.moveTo(tipX, tipY); hPath.lineTo(aX, aY); hPath.lineTo(aX2, aY2); hPath.close()
            canvas.drawPath(hPath, suggestArrowHead)
        }
    }

    private fun dp(f: Float): Float = f * resources.displayMetrics.density
    private fun dp(i: Int): Float = dp(i.toFloat())
}