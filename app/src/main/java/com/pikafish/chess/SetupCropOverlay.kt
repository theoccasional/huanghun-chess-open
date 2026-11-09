package com.pikafish.chess

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * ★ 圈定覆盖层
 *
 * 叠加在放大态的识别原图之上，提供多点圈定功能：
 *  点 4~20 个点连成(任意)多边形，把多边形外部的碍事内容剔除，
 *  并可用于透视校正（把斜拍/梯形棋盘拉正）。
 *
 * 提交时由 [buildSourceBitmap] 生成一张
 * "有效区域白/无效区域黑"的掩膜位图，交给识别流程。
 *
 * 坐标约定：点用「原图宽高比显示区域」内的归一化坐标 (0..1)。
 */
class SetupCropOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 原图宽高比 (宽/高)，用于 FIT_CENTER 计算图片在控件内的显示矩形 */
    var imageAspectRatio: Float = 0f
        set(value) { field = value; invalidate() }

    // ── 多点圈定状态 ──
    private val crownPoints = ArrayList<PointF>()          // 已点的角点（归一化）
    var crownEnabled = true                                // 是否启用圈定点
    val crownCount: Int get() = crownPoints.size
    val crowningComplete: Boolean get() = crownEnabled && crownPoints.size >= 4

    /** 圈定是否有操作 */
    val hasWork: Boolean get() = crownEnabled && crownPoints.isNotEmpty()

    /** 当前选点能否组成四边形用于透视校正（≥4点） */
    val hasQuadForPerspective: Boolean get() = crownEnabled && crownPoints.size >= 4

    /**
     * ★ 导出圈定多边形的最外侧 4 个角点（按几何位置排成 左上、右上、右下、左下），
     *   供透视校正把斜拍/梯形棋盘拉正。不足4点返回 null。
     *   基于凸包外轮廓环取四个极值角点，与识别范围一致。
     */
    fun quadFourCorners(): List<PointF>? {
        if (!crownEnabled || crownPoints.size < 4) return null
        val hull = convexHull()
        if (hull.size < 4) return null
        // 在凸包环上取 最左/最右/最上/最下 四个极值点构成包围四边形
        val left = hull.minByOrNull { it.x }
        val right = hull.minByOrNull { -it.x }
        val top = hull.minByOrNull { it.y }
        val bottom = hull.minByOrNull { -it.y }
        if (left == null || right == null || top == null || bottom == null) return null
        // 重排为 左上、右上、右下、左下
        val pts = listOf(left, right, top, bottom).distinct()
        if (pts.size < 4) {
            // 极值点重合时回退到按 y 取上下各两个
            val t2 = hull.sortedBy { it.y }.take(2).sortedBy { it.x }
            val b2 = hull.sortedBy { it.y }.takeLast(2).sortedBy { it.x }
            return listOf(t2[0], t2[1], b2[1], b2[0])
        }
        val tl = pts.filter { it.x <= right.x * 0.5f && it.y <= bottom.y * 0.5f }.minByOrNull { it.x + it.y }
            ?: top
        val tr = pts.filter { it.x >= left.x * 0.5f && it.y <= bottom.y * 0.5f }.minByOrNull { -(it.x - it.y) }
            ?: right
        val br = pts.filter { it.x >= left.x * 0.5f && it.y >= top.y * 0.5f }.minByOrNull { it.x + it.y }
            ?: bottom
        val bl = pts.filter { it.x <= right.x * 0.5f && it.y >= top.y * 0.5f }.minByOrNull { it.y - it.x }
            ?: left
        return listOf(tl, tr, br, bl)
    }

    var onAnyChange: (() -> Unit)? = null

    /** 清空所有点 */
    fun clearAll() {
        crownPoints.clear()
        invalidate()
    }

    // ── 图片显示矩形(FIT_CENTER) ──
    private fun imageRect(): android.graphics.RectF {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0 || vh <= 0 || imageAspectRatio <= 0f) return android.graphics.RectF()
        var pw = vw; var ph = pw / imageAspectRatio
        if (ph > vh) { ph = vh; pw = ph * imageAspectRatio }
        val left = (vw - pw) / 2f; val top = (vh - ph) / 2f
        return android.graphics.RectF(left, top, left + pw, top + ph)
    }

    private fun toView(p: PointF): PointF {
        val r = imageRect()
        return PointF(r.left + p.x * r.width(), r.top + p.y * r.height())
    }

    /**
     * ★★★ 关键修正：不再按点击顺序连线（会交叉成蜘蛛网）。
     * 改用凸包算法把所有圈定点重新排成一个"外轮廓环"：
     *  - 一笔连成、绝不交叉
     *  - 圈住的面积最大化
     *  - 无论点了多少点(4~20)都成立
     * @return 按外轮廓顺序排列的点列表（逆时针）
     */
    private fun convexHull(): List<PointF> {
        val pts = crownPoints
        if (pts.size < 3) return ArrayList(pts)
        val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))
        // 叉积：<0 表示 b 在 oa 顺时针侧
        fun cross(o: PointF, a: PointF, b: PointF): Float =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<PointF>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) < 0f) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = ArrayList<PointF>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) < 0f) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        lower.addAll(upper)
        if (lower.size < 3) return ArrayList(pts)
        return lower
    }

    /**
     * ★ 用外轮廓环(凸包)替换按点击顺序连线 —— 供掩膜多边形使用，保证不交叉
     */
    private fun hullPath(): Path {
        val hull = convexHull()
        val r = imageRect()
        val sw = r.width(); val sh = r.height()
        val path = Path()
        if (hull.isEmpty()) return path
        val p0 = hull[0]
        path.moveTo(r.left + p0.x * sw, r.top + p0.y * sh)
        for (i in 1 until hull.size) {
            val p = hull[i]
            path.lineTo(r.left + p.x * sw, r.top + p.y * sh)
        }
        path.close()
        return path
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = imageRect()
        if (r.width() <= 0 || r.height() <= 0) return

        // 顶部提示
        val msg = when {
            crownEnabled && crownPoints.isEmpty() && !hasWork -> "圈定：点4~20个角点圈定棋盘范围"
            crownEnabled && crowningComplete -> "圈定${crownPoints.size}点 ✓（可继续加到20）·提交识别"
            crownEnabled && crownPoints.isNotEmpty() -> "已圈${crownPoints.size}/4点，至少4点可提交（上限20）"
            else -> "至少点4个角点后提交"
        }
        canvas.drawText(msg, r.left, r.top + dp(14f), textPaint)

        // 圈定点连线（多边形）
        if (crownEnabled) {
            if (crownPoints.size == 1) {
                val v = toView(crownPoints[0])
                canvas.drawCircle(v.x, v.y, dp(7f), dotPaint)
                canvas.drawCircle(v.x, v.y, dp(3f), dotCorePaint)
            } else if (crownPoints.size >= 2) {
                val path = hullPath()
                canvas.drawPath(path, lineFillPaint)
                canvas.drawPath(path, linePaint)
            }
            // 角点序号
            crownPoints.forEachIndexed { idx, p ->
                val v = toView(p)
                canvas.drawCircle(v.x, v.y, dp(7f), dotPaint)
                canvas.drawCircle(v.x, v.y, dp(3f), dotCorePaint)
                val txt = "${idx + 1}"
                val tw = textPaint.measureText(txt)
                canvas.drawText(txt, v.x - tw / 2, v.y + dp(4f) - textPaint.ascent() / 2, numPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = imageRect()
        if (r.width() <= 0 || r.height() <= 0) return true

        val inImage = { x: Float, y: Float ->
            x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
        }
        val toNorm = { x: Float, y: Float -> PointF((x - r.left) / r.width(), (y - r.top) / r.height()) }

        // 圈定点模式
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        if (!inImage(event.x, event.y)) return true
        val n = toNorm(event.x, event.y)
        when {
            crownPoints.size >= 20 -> return true
            else -> { crownPoints.add(n) }
        }
        invalidate()
        onAnyChange?.invoke()
        return true
    }

    /**
     * ★ 汇总生成"有效掩膜"位图：白色=有效(保留)，黑色=无效(圈定外区域)。
     * 尺寸与原图一致。若没有圈定，返回 null（表示全图有效，无需处理）。
     */
    private fun buildMask(srcW: Int, srcH: Int): Bitmap? {
        val hasCrown = crownEnabled && crownPoints.size >= 3
        if (!hasCrown) return null

        val mask = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        val c = Canvas(mask)
        // 默认全黑(无效)
        c.drawColor(Color.BLACK)
        val white = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        val sw = srcW.toFloat(); val sh = srcH.toFloat()

        // 画多边形内部为白(有效) —— 用凸包外轮廓环，一笔连、不交叉、面积最大化
        if (hasCrown) {
            val hull = convexHull()
            val path = Path()
            if (hull.isNotEmpty()) {
                val p0 = hull[0]
                path.moveTo(p0.x * sw, p0.y * sh)
                for (i in 1 until hull.size) {
                    val p = hull[i]; path.lineTo(p.x * sw, p.y * sh)
                }
                path.close()
            }
            c.drawPath(path, white)
        }
        return mask
    }

    /**
     * ★ 依据掩膜生成一张"圈定范围外涂黑"的原图位图。
     * 有效区保留原图，无效区填充纯黑。返回 null 表示无需处理(直接用原图)。
     */
    fun buildSourceBitmap(src: Bitmap): Bitmap? {
        val mask = buildMask(src.width, src.height) ?: return null
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, null)
        // 把 mask 黑色区域复制到 out
        val n = src.width * src.height
        val maskPx = IntArray(n)
        mask.getPixels(maskPx, 0, src.width, 0, 0, src.width, src.height)
        val srcPx = IntArray(n)
        out.getPixels(srcPx, 0, src.width, 0, 0, src.width, src.height)
        for (i in 0 until n) {
            if (Color.alpha(maskPx[i]) < 128) {
                srcPx[i] = Color.BLACK
            }
        }
        out.setPixels(srcPx, 0, src.width, 0, 0, src.width, src.height)
        mask.recycle()
        return out
    }

    // ── 画笔 ──
    private val dotPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x7400 } // 0xFFFF8C00
    private val dotCorePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFffffff.toInt() } // white
    private val linePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x9600 // 0xFFFF6A00
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val lineFillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FF6A00
        style = Paint.Style.FILL
    }
    private val numPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textSize = dp(9f)
        isFakeBoldText = true
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = dp(12f)
        setShadowLayer(dp(2f), 0f, 0f, 0x66000000)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}