package com.pikafish.chess.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ★ 外置读物 · PDF 页面视图
 *
 * 用系统 PdfRenderer 把页面按需光栅化为 ARGB_8888 位图（彩色/图片原样保真）。
 * 两种阅读模式，随时可切：
 *  - MODE_SCROLL：上下连续滚屏（老样子）；
 *  - MODE_FLIP ：左右整页翻页，像翻真书一样一页一页走，位置稳、不晃悠。
 *
 * 关键体验修正：
 *  - ★ 轴线锁定：一次拖动只认「主方向」——竖向滑动就纯竖向，绝不跟着手指左右飘（治左甩右晃）；
 *    并且当纸张宽度没超出屏幕时，横向位移恒为 0，压根没有左右可晃。
 *  - ★ 裁边：把纸张四周白边按比例裁掉。裁完自动铺满屏宽 → 字变大、又不越界，比放大更稳。
 *  - ★ 框选：进入框选模式后，划个对角、抬手即可框出一块区域（返回全页归一化坐标），
 *    交给上层做「反选识别文字 / 正选摆子识别」。
 *
 * 其他：高清重渲染（按当前显示宽度 1:1，放大/裁边后自动重画）、对比度增强、进度比例记忆、★标记。
 */
class PdfPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onPageChanged(page: Int, total: Int)
    }

    var listener: Listener? = null

    /** 框选完成回调：参数为视图坐标系下的矩形（左上/右下） */
    var onSelection: ((RectF) -> Unit)? = null

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    var pageCount: Int = 0
        private set

    private val pw = ArrayList<Int>()     // 整页原始宽（PDF 单位）
    private val ph = ArrayList<Int>()     // 整页原始高

    // ── 裁边比例（相对整页，0..0.35）──
    var cropL = 0f; private set
    var cropT = 0f; private set
    var cropR = 0f; private set
    var cropB = 0f; private set

    // ── 阅读模式 ──
    private var mode = MODE_SCROLL
    fun mode(): Int = mode

    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var pendingRatio = -1f

    // 翻页模式状态
    private var flipPage = 0
    private var flipDragX = 0f
    private var flipScrollY = 0f
    private var flipAnim: ValueAnimator? = null

    // 框选状态
    var selectMode: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                selRect = null
                invalidate()
            }
        }

    /**
     * ★ 阅读锁：书页AI棋盘「展开」时置 true —— 此时纸张不再响应触摸，
     *   杜绝「一边摆棋、一边把页面翻跑/晃跑」。收成小胶囊后解锁，才能翻来翻去对着书走。
     */
    var locked: Boolean = false
    private var selStartX = 0f
    private var selStartY = 0f
    private var selRect: RectF? = null

    /** 对比度增强（专治扫描件发灰） */
    var enhance: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                paint.colorFilter = if (v) ColorMatrixColorFilter(ENHANCE_MATRIX) else null
                invalidate()
            }
        }

    var markedPages: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }

    /** ★ 划线高亮：页序号 → 该页若干高亮矩形（全页归一化坐标） */
    var highlights: Map<Int, List<RectF>> = emptyMap()
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val paperPaint = Paint().apply { color = 0xFFFBFBF7.toInt() }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8A33D.toInt() }
    private val markTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16f; textAlign = Paint.Align.CENTER
    }
    private val pageNoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt(); textSize = 13f; textAlign = Paint.Align.CENTER
    }
    private val selFillPaint = Paint().apply { color = 0x332A6DB5 }
    private val selEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A6DB5.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val highlightPaint = Paint().apply { color = 0x66FFD54A.toInt() }
    private val dst = RectF()

    // ────────────────────────── 渲染线程与缓存 ──────────────────────────
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var released = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderWidth = HashMap<Int, Int>()
    private val inflight = HashSet<Int>()
    private val pending = HashMap<Int, Int>()

    private val cache = object : LruCache<Int, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) {
                renderWidth.remove(key)
                try { oldValue.recycle() } catch (_: Exception) {}
            }
        }
    }

    private val debounce = Runnable {
        val snapshot = HashMap(pending)
        pending.clear()
        for ((page, w) in snapshot) submitRender(page, w)
    }

    // ────────────────────────── 手势 ──────────────────────────
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** 轴线锁定：0 未定，1 竖向，2 横向。锁定后整段手势只走一个方向，杜绝斜着飘。 */
    private var axisLock = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (mode == MODE_FLIP || selectMode) return true   // 翻页/框选时不缩放
                val old = zoom
                val nz = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                if (nz == old) return true
                val oldMax = maxOffsetY()
                val rel = if (oldMax > 0f) offsetY / oldMax else 0f
                zoom = nz
                offsetY = (rel * maxOffsetY()).coerceIn(0f, maxOffsetY())
                if (maxOffsetX() <= 0f) offsetX = 0f
                invalidate()
                notifyPage()
                return true
            }
        }
    )

    private var lastReportedPage = -2

    init {
        setBackgroundColor(0xFF2E2A26.toInt())
        isClickable = true
        isFocusable = true
    }

    private companion object {
        const val MODE_SCROLL = 0
        const val MODE_FLIP = 1
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 4.0f
        const val PAGE_GAP = 12f
        const val MAX_PAGE_PIXELS = 4_000_000.0
        const val FLIP_TURN_FRACTION = 0.22f
        const val FLIP_TURN_VELOCITY = 900f

        val ENHANCE_MATRIX = ColorMatrix(
            floatArrayOf(
                1.32f, 0f, 0f, 0f, -41f,
                0f, 1.32f, 0f, 0f, -41f,
                0f, 0f, 1.32f, 0f, -41f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        fun cacheBytes(): Int {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 8).coerceIn(24L * 1024 * 1024, 160L * 1024 * 1024).toInt()
        }
    }

    // ────────────────────────── 载入 / 释放 ──────────────────────────

    /** ★ 可在后台线程调用（内部不做直接 UI 操作）：打开 PDF 并读取各页尺寸。 */
    fun load(file: File): Boolean {
        return try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(descriptor)
            pfd = descriptor
            renderer = r
            pageCount = r.pageCount
            pw.clear(); ph.clear()
            for (i in 0 until pageCount) {
                val p = r.openPage(i)
                pw.add(max(1, p.width)); ph.add(max(1, p.height))
                p.close()
            }
            postInvalidate()
            mainHandler.post { notifyPage() }
            pageCount > 0
        } catch (e: Exception) {
            false
        }
    }

    fun release() {
        released = true
        flipAnim?.cancel()
        try {
            executor.execute {
                try { renderer?.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
                renderer = null
                pfd = null
            }
        } catch (_: Exception) {}
        try { executor.shutdown() } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ────────────────────────── 裁边 / 模式 ──────────────────────────

    /** 设置裁边比例（相对整页）；会按当前阅读比例重排并清缓存重渲染。 */
    fun setCrop(l: Float, t: Float, r: Float, b: Float) {
        val nl = l.coerceIn(0f, 0.35f)
        val nt = t.coerceIn(0f, 0.35f)
        val nr = r.coerceIn(0f, 0.35f)
        val nb = b.coerceIn(0f, 0.35f)
        if (nl == cropL && nt == cropT && nr == cropR && nb == cropB) return
        val keepRatio = ratio()
        val keepFlipFrac = if (maxFlipScrollY() > 0f) flipScrollY / maxFlipScrollY() else 0f
        cropL = nl; cropT = nt; cropR = nr; cropB = nb
        cache.evictAll()
        renderWidth.clear()
        pending.clear()
        if (mode == MODE_FLIP) {
            flipScrollY = keepFlipFrac * maxFlipScrollY()
        } else {
            offsetY = (keepRatio * maxOffsetY()).coerceIn(0f, maxOffsetY())
        }
        if (maxOffsetX() <= 0f) offsetX = 0f
        invalidate()
        notifyPage()
    }

    fun setMode(m: Int) {
        if (m == mode) return
        val cur = currentPage()
        mode = m
        zoom = 1f
        offsetX = 0f
        flipAnim?.cancel()
        flipDragX = 0f
        if (mode == MODE_FLIP) {
            flipPage = cur.coerceIn(0, max(0, pageCount - 1))
            flipScrollY = 0f
        } else {
            goToPageInternal(cur)
        }
        invalidate()
        notifyPage()
    }

    // ────────────────────────── 几何 ──────────────────────────

    private fun croppedAspect(i: Int): Float {
        if (i < 0 || i >= pageCount) return 1.4f
        val w = pw[i].toFloat() * (1f - cropL - cropR)
        val h = ph[i].toFloat() * (1f - cropT - cropB)
        if (w <= 1f || h <= 1f) return 1.4f
        return h / w
    }

    private fun scrollPageWidth(): Float = if (width <= 0) 0f else width.toFloat() * zoom
    private fun scrollPageHeight(i: Int): Float = scrollPageWidth() * croppedAspect(i)

    private fun flipPageWidth(): Float = width.toFloat()
    private fun flipPageHeight(i: Int): Float = flipPageWidth() * croppedAspect(i)

    private fun contentHeight(): Float {
        if (pageCount == 0) return 0f
        var h = 0f
        for (i in 0 until pageCount) h += scrollPageHeight(i) + PAGE_GAP
        return h - PAGE_GAP
    }

    private fun maxOffsetY(): Float = max(0f, contentHeight() - height.toFloat())
    private fun maxOffsetX(): Float = max(0f, scrollPageWidth() - width.toFloat())
    private fun maxFlipScrollY(): Float =
        max(0f, flipPageHeight(flipPage) - height.toFloat())

    fun ratio(): Float {
        if (pageCount == 0) return 0f
        return if (mode == MODE_FLIP) {
            if (pageCount <= 1) 0f else flipPage.toFloat() / (pageCount - 1)
        } else {
            val m = maxOffsetY()
            if (m <= 0f) 0f else (offsetY / m).coerceIn(0f, 1f)
        }
    }

    fun setRatio(r: Float) {
        pendingRatio = r.coerceIn(0f, 1f)
        if (width <= 0 || height <= 0) return
        pendingRatio = -1f
        if (mode == MODE_FLIP) {
            flipPage = (r.coerceIn(0f, 1f) * max(0, pageCount - 1)).roundToInt()
                .coerceIn(0, max(0, pageCount - 1))
            flipScrollY = 0f
        } else {
            offsetY = r.coerceIn(0f, 1f) * maxOffsetY()
        }
        invalidate()
        notifyPage()
    }

    fun currentPage(): Int {
        if (pageCount == 0 || width <= 0) return 0
        if (mode == MODE_FLIP) return flipPage.coerceIn(0, pageCount - 1)
        val target = offsetY + height * 0.30f
        var y = 0f
        for (i in 0 until pageCount) {
            val h = scrollPageHeight(i)
            if (target < y + h) return i
            y += h + PAGE_GAP
        }
        return pageCount - 1
    }

    fun goToPage(page: Int) {
        if (pageCount == 0) return
        val i = page.coerceIn(0, pageCount - 1)
        if (mode == MODE_FLIP) {
            flipPage = i
            flipScrollY = 0f
            flipDragX = 0f
        } else {
            goToPageInternal(i)
        }
        invalidate()
        notifyPage()
    }

    private fun goToPageInternal(i0: Int) {
        val i = i0.coerceIn(0, max(0, pageCount - 1))
        var y = 0f
        for (k in 0 until i) y += scrollPageHeight(k) + PAGE_GAP
        offsetY = y.coerceIn(0f, maxOffsetY())
        offsetX = 0f
    }

    fun zoomIn() = setZoom(zoom * 1.25f)
    fun zoomOut() = setZoom(zoom / 1.25f)
    fun zoomLevel(): Float = zoom

    /** 翻页模式不支持缩放（用「裁边」放大更稳） */
    fun canZoom(): Boolean = mode == MODE_SCROLL

    private fun setZoom(z: Float) {
        if (mode == MODE_FLIP) return
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (nz == zoom) return
        val oldMax = maxOffsetY()
        val rel = if (oldMax > 0f) offsetY / oldMax else 0f
        zoom = nz
        offsetY = (rel * maxOffsetY()).coerceIn(0f, maxOffsetY())
        offsetX = offsetX.coerceIn(0f, maxOffsetX())
        invalidate()
        notifyPage()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pendingRatio >= 0f) {
            val r = pendingRatio
            pendingRatio = -1f
            if (mode == MODE_FLIP) {
                flipPage = (r * max(0, pageCount - 1)).roundToInt().coerceIn(0, max(0, pageCount - 1))
            } else {
                offsetY = r * maxOffsetY()
            }
        }
        offsetY = offsetY.coerceIn(0f, maxOffsetY())
        offsetX = offsetX.coerceIn(0f, maxOffsetX())
        flipScrollY = flipScrollY.coerceIn(0f, maxFlipScrollY())
        invalidate()
        notifyPage()
    }

    // ────────────────────────── 绘制 ──────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageCount == 0 || width <= 0) return
        if (mode == MODE_FLIP) drawFlip(canvas) else drawScroll(canvas)
        drawSelection(canvas)
    }

    private fun drawScroll(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val pageW = scrollPageWidth()
        val x0 = if (pageW <= vw) (vw - pageW) / 2f else -offsetX
        val renderW = targetRenderWidth(pageW)

        var y = -offsetY
        for (i in 0 until pageCount) {
            val h = scrollPageHeight(i)
            val top = y
            val bottom = y + h
            y = bottom + PAGE_GAP
            if (bottom <= -2f) continue
            if (top >= vh + 2f) break
            drawPageBitmap(canvas, i, x0, top, pageW, h, renderW)
        }
    }

    private fun drawFlip(canvas: Canvas) {
        val vw = width.toFloat()
        val cur = flipPage.coerceIn(0, pageCount - 1)
        val renderW = targetRenderWidth(vw)
        val draw = flipDragX
        // 当前页
        val curH = flipPageHeight(cur)
        val sy = flipScrollY.coerceIn(0f, max(0f, curH - height.toFloat()))
        drawPageBitmap(canvas, cur, draw, -sy, vw, curH, renderW)
        // 邻居页（拖动时露出一角，提前渲染，翻过去即清晰）
        if (draw > 0f && cur > 0) {
            drawPageBitmap(canvas, cur - 1, draw - vw, 0f, vw, flipPageHeight(cur - 1), renderW)
        } else if (draw < 0f && cur < pageCount - 1) {
            drawPageBitmap(canvas, cur + 1, draw + vw, 0f, vw, flipPageHeight(cur + 1), renderW)
        }
    }

    private fun drawPageBitmap(
        canvas: Canvas, index: Int, left: Float, top: Float, w: Float, h: Float, renderW: Int
    ) {
        dst.set(left, top, left + w, top + h)
        val bmp = cache.get(index)
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, dst, paint)
            if (renderWidth[index] != renderW) scheduleRender(index, renderW)
        } else {
            canvas.drawRect(dst, paperPaint)
            scheduleRender(index, renderW)
        }
        if (markedPages.contains(index)) {
            val cx = left + w - 20f
            val cy = top + 20f
            canvas.drawCircle(cx, cy, 14f, markPaint)
            canvas.drawText("★", cx, cy + 5.5f, markTextPaint)
        }
        // ★ 划线高亮（按裁边后的可见区域映射）
        val hs = highlights[index]
        if (!hs.isNullOrEmpty()) {
            val cw = 1f - cropL - cropR
            val chh = 1f - cropT - cropB
            for (r in hs) {
                val l = dst.left + (((r.left - cropL) / cw).coerceIn(0f, 1f)) * dst.width()
                val t = dst.top + (((r.top - cropT) / chh).coerceIn(0f, 1f)) * dst.height()
                val rr = dst.left + (((r.right - cropL) / cw).coerceIn(0f, 1f)) * dst.width()
                val bb = dst.top + (((r.bottom - cropT) / chh).coerceIn(0f, 1f)) * dst.height()
                canvas.drawRect(min(l, rr), min(t, bb), max(l, rr), max(t, bb), highlightPaint)
            }
        }
        canvas.drawText((index + 1).toString(), left + w - 22f, top + h - 8f, pageNoPaint)
    }

    private fun drawSelection(canvas: Canvas) {
        val r = selRect ?: return
        canvas.drawRect(r, selFillPaint)
        canvas.drawRect(r, selEdgePaint)
    }

    /** 目标渲染宽度：1:1 于当前显示宽度，再按单页像素上限收敛，避免大页撑爆内存 */
    private fun targetRenderWidth(pageW: Float): Int {
        if (pageW <= 0f) return 1
        val aspect = if (pageCount > 0) croppedAspect(0) else 1.4f
        var w = pageW.roundToInt().coerceAtLeast(80)
        val maxW = sqrt(MAX_PAGE_PIXELS / aspect).toInt().coerceAtLeast(80)
        if (w > maxW) w = maxW
        return w
    }

    private fun scheduleRender(page: Int, targetW: Int) {
        if (released) return
        if (cache.get(page) != null && renderWidth[page] == targetW) return
        if (inflight.contains(page)) return
        pending[page] = targetW
        removeCallbacks(debounce)
        postDelayed(debounce, 60)
    }

    private fun submitRender(page: Int, targetW: Int) {
        if (released) return
        if (!inflight.add(page)) return
        try {
            executor.execute {
                val bmp = renderCropped(page, targetW)
                mainHandler.post {
                    inflight.remove(page)
                    if (!released && bmp != null) {
                        renderWidth[page] = targetW
                        cache.put(page, bmp)
                        invalidate()
                    } else if (bmp != null) {
                        bmp.recycle()
                    }
                }
            }
        } catch (_: Exception) {
            inflight.remove(page)
        }
    }

    /** 渲染整页原始位图（未裁边），像素宽 pixelWidth（受总像素上限约束）。 */
    private fun renderRawPage(page: Int, pixelWidth: Int): Bitmap? {
        if (released) return null
        val r = renderer ?: return null
        if (page < 0 || page >= pageCount) return null
        return try {
            val p = r.openPage(page)
            try {
                val aspect = p.height.toFloat() / p.width.toFloat()
                var w = pixelWidth.coerceAtLeast(80)
                var h = (w * aspect).roundToInt()
                val px = w.toLong() * h.toLong()
                if (px > MAX_PAGE_PIXELS) {
                    val f = sqrt(MAX_PAGE_PIXELS / px.toDouble())
                    w = (w * f).roundToInt()
                    h = (h * f).roundToInt()
                }
                w = w.coerceAtLeast(80)
                h = h.coerceAtLeast(80)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                try { p.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { null }
    }

    /** 渲染「已裁边」的显示位图（宽 = targetCroppedW）。 */
    private fun renderCropped(page: Int, targetCroppedW: Int): Bitmap? {
        if (released) return null
        val cw = 1f - cropL - cropR
        val ch = 1f - cropT - cropB
        if (cw < 0.04f || ch < 0.04f) return null
        val fullTarget = (targetCroppedW / cw).roundToInt().coerceAtLeast(80)
        val full = renderRawPage(page, fullTarget) ?: return null
        val l = (full.width * cropL).roundToInt().coerceIn(0, max(0, full.width - 2))
        val t = (full.height * cropT).roundToInt().coerceIn(0, max(0, full.height - 2))
        val w = (full.width * cw).roundToInt().coerceIn(2, full.width - l)
        val h = (full.height * ch).roundToInt().coerceIn(2, full.height - t)
        if (l == 0 && t == 0 && w == full.width && h == full.height) return full
        val out = try { Bitmap.createBitmap(full, l, t, w, h) } catch (_: Exception) { null }
        if (out != null && out !== full) full.recycle()
        return out ?: full
    }

    // ────────────────────────── 区域截取（供上层 OCR / 摆子识别）──────────────────────────

    private fun runCapture(work: () -> Bitmap?, onDone: (Bitmap?) -> Unit) {
        if (released) { mainHandler.post { onDone(null) }; return }
        try {
            executor.execute {
                val b = try { work() } catch (_: Exception) { null }
                mainHandler.post { onDone(b) }
            }
        } catch (_: Exception) {
            mainHandler.post { onDone(null) }
        }
    }

    /**
     * 截取「当前可见内容（已按裁边裁好）」的整页位图，供 OCR 使用。
     * maskFull 为需要在成品上涂白的矩形（全页归一化坐标，用于「反选」把棋盘挖掉），可为 null。
     */
    fun capturePageForOcr(page: Int, baseWidth: Int, maskFull: RectF?, onDone: (Bitmap?) -> Unit) {
        runCapture({
            val out = renderCropped(page, baseWidth) ?: return@runCapture null
            if (maskFull != null) {
                try {
                    val cw = 1f - cropL - cropR
                    val ch = 1f - cropT - cropB
                    val l = (((maskFull.left - cropL) / cw).coerceIn(0f, 1f)) * out.width
                    val t = (((maskFull.top - cropT) / ch).coerceIn(0f, 1f)) * out.height
                    val r = (((maskFull.right - cropL) / cw).coerceIn(0f, 1f)) * out.width
                    val b = (((maskFull.bottom - cropT) / ch).coerceIn(0f, 1f)) * out.height
                    val c = Canvas(out)
                    c.drawRect(min(l, r), min(t, b), max(l, r), max(t, b), Paint().apply { color = Color.WHITE })
                } catch (_: Exception) {}
            }
            out
        }, onDone)
    }

    /** 截取整页中的一个矩形（全页归一化坐标）供摆子识别，输出像素宽不小于 minOutWidth。 */
    fun captureRegionForBoard(page: Int, rectFull: RectF, minOutWidth: Int, onDone: (Bitmap?) -> Unit) {
        runCapture({
            val rw = (rectFull.right - rectFull.left).coerceAtLeast(0.02f)
            val fullW = (minOutWidth / rw).roundToInt().coerceAtLeast(240)
            val bmp = renderRawPage(page, fullW) ?: return@runCapture null
            val l = (rectFull.left.coerceIn(0f, 1f) * bmp.width).roundToInt().coerceIn(0, max(0, bmp.width - 2))
            val t = (rectFull.top.coerceIn(0f, 1f) * bmp.height).roundToInt().coerceIn(0, max(0, bmp.height - 2))
            val w = ((rectFull.right - rectFull.left).coerceIn(0.02f, 1f) * bmp.width)
                .roundToInt().coerceIn(2, max(2, bmp.width - l))
            val h = ((rectFull.bottom - rectFull.top).coerceIn(0.02f, 1f) * bmp.height)
                .roundToInt().coerceIn(2, max(2, bmp.height - t))
            val out = try { Bitmap.createBitmap(bmp, l, t, w, h) } catch (_: Exception) { null }
            if (out != null && out !== bmp) bmp.recycle()
            out ?: bmp
        }, onDone)
    }

    // ────────────────────────── 坐标映射 ──────────────────────────

    /** 视图坐标 → (页序号, 全页归一化 x, 全页归一化 y)；未命中返回 null。 */
    fun hitFull(vx: Float, vy: Float): Triple<Int, Float, Float>? {
        if (pageCount == 0 || width <= 0) return null
        val cw = 1f - cropL - cropR
        val ch = 1f - cropT - cropB
        return if (mode == MODE_FLIP) {
            if (vx < -width || vx > width * 2) return null
            val w = flipPageWidth()
            val h = flipPageHeight(flipPage)
            if (w <= 0f || h <= 0f) return null
            val dispY = vy + flipScrollY
            val fxD = (vx / w).coerceIn(0f, 1f)
            val fyD = (dispY / h).coerceIn(0f, 1f)
            Triple(flipPage, cropL + fxD * cw, cropT + fyD * ch)
        } else {
            val pageW = scrollPageWidth()
            if (pageW <= 0f) return null
            val x0 = if (pageW <= width) (width - pageW) / 2f else -offsetX
            var y = -offsetY
            for (i in 0 until pageCount) {
                val h = scrollPageHeight(i)
                if (vy >= y && vy < y + h) {
                    val fxD = ((vx - x0) / pageW).coerceIn(0f, 1f)
                    val fyD = ((vy - y) / h).coerceIn(0f, 1f)
                    return Triple(i, cropL + fxD * cw, cropT + fyD * ch)
                }
                y += h + PAGE_GAP
            }
            null
        }
    }

    /** 把一次框选（视图坐标）映射为 (页序号, 全页归一化矩形)。 */
    fun mapSelection(rect: RectF): Pair<Int, RectF>? {
        val center = hitFull(rect.centerX(), rect.centerY()) ?: return null
        val i = center.first
        val dr = pageDisplayRect(i) ?: return null
        if (dr.width() <= 0f || dr.height() <= 0f) return null
        val cw = 1f - cropL - cropR
        val ch = 1f - cropT - cropB
        fun fx(x: Float) = cropL + (((x - dr.left) / dr.width()).coerceIn(0f, 1f)) * cw
        fun fy(y: Float) = cropT + (((y - dr.top) / dr.height()).coerceIn(0f, 1f)) * ch
        val l = fx(min(rect.left, rect.right))
        val r = fx(max(rect.left, rect.right))
        val t = fy(min(rect.top, rect.bottom))
        val b = fy(max(rect.top, rect.bottom))
        return i to RectF(min(l, r), min(t, b), max(l, r), max(t, b))
    }

    private fun pageDisplayRect(i: Int): RectF? {
        if (pageCount == 0 || width <= 0) return null
        return if (mode == MODE_FLIP) {
            if (i != flipPage) return null
            val h = flipPageHeight(i)
            RectF(0f, -flipScrollY, flipPageWidth(), -flipScrollY + h)
        } else {
            val pageW = scrollPageWidth()
            if (pageW <= 0f) return null
            val x0 = if (pageW <= width) (width - pageW) / 2f else -offsetX
            var y = -offsetY
            for (k in 0 until pageCount) {
                val h = scrollPageHeight(k)
                if (k == i) return RectF(x0, y, x0 + pageW, y + h)
                y += h + PAGE_GAP
            }
            null
        }
    }

    // ────────────────────────── 触摸 ──────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return true          // ★ 棋盘展开中：纸张不接管触摸，翻页/滑动全冻结
        if (selectMode) return handleSelectTouch(event)

        if (mode == MODE_SCROLL) scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flipAnim?.cancel()
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                dragging = true
                axisLock = 0
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_SCROLL && scaleDetector.isInProgress) {
                    dragging = false
                    axisLock = 2
                } else if (dragging) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x; lastY = event.y

                    // ★ 轴线锁定：越过触摸阈值后定一次主方向，整段手势只走这个方向
                    if (axisLock == 0 && hypot(event.x - downX, event.y - downY) > touchSlop) {
                        val horizontal = abs(event.x - downX) > abs(event.y - downY)
                        // 纸张没超出屏宽时，横滑没有意义 —— 一律锁竖向，绝不左右晃
                        val canPanH = if (mode == MODE_FLIP) true else maxOffsetX() > 0f
                        axisLock = if (horizontal && canPanH) 2 else 1
                    }

                    if (mode == MODE_FLIP) {
                        if (axisLock == 2) {
                            flipDragX += dx
                            val lim = width.toFloat()
                            if (flipDragX > lim) flipDragX = lim
                            if (flipDragX < -lim) flipDragX = -lim
                        } else if (axisLock == 1) {
                            flipScrollY = (flipScrollY - dy).coerceIn(0f, maxFlipScrollY())
                        }
                    } else {
                        if (axisLock == 1) {
                            offsetY = (offsetY - dy).coerceIn(0f, maxOffsetY())
                        } else if (axisLock == 2) {
                            offsetX = (offsetX - dx).coerceIn(0f, maxOffsetX())
                        }
                    }
                    invalidate()
                    notifyPage()
                    velocity?.addMovement(event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (mode == MODE_FLIP) endFlipGesture() else startFling()
                }
                velocity?.recycle(); velocity = null
                axisLock = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                dragging = false
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    // ── 框选手势 ──
    private fun handleSelectTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selStartX = event.x; selStartY = event.y
                selRect = RectF(event.x, event.y, event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                selRect = RectF(
                    min(selStartX, event.x), min(selStartY, event.y),
                    max(selStartX, event.x), max(selStartY, event.y)
                )
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = selRect
                val minSide = 28f * resources.displayMetrics.density
                if (r != null && r.width() >= minSide && r.height() >= minSide) {
                    onSelection?.invoke(RectF(r))
                } else {
                    selRect = null
                    invalidate()
                }
            }
        }
        return true
    }

    /** 由上层在处理完框选后调用，清掉选框 */
    fun clearSelection() {
        selRect = null
        invalidate()
    }

    private fun startFling() {
        val vt = velocity ?: return
        vt.computeCurrentVelocity(1000, 9000f)
        val vx = -vt.xVelocity
        val vy = -vt.yVelocity
        if (abs(vx) < 150f && abs(vy) < 150f) return
        scroller.fling(
            0, offsetY.roundToInt(),
            0, vy.roundToInt(),
            0, 0,
            0, maxOffsetY().roundToInt()
        )
        postInvalidateOnAnimation()
    }

    private fun endFlipGesture() {
        val vx = velocity?.let { it.computeCurrentVelocity(1000, 9000f); it.xVelocity } ?: 0f
        val w = width.toFloat()
        val turn = abs(flipDragX) > w * FLIP_TURN_FRACTION || abs(vx) > FLIP_TURN_VELOCITY
        val cur = flipPage
        when {
            turn && flipDragX < 0f && cur < pageCount - 1 ->
                animateFlipTo(-w) { flipPage = cur + 1; flipDragX = 0f; flipScrollY = 0f; notifyPage() }
            turn && flipDragX > 0f && cur > 0 ->
                animateFlipTo(w) { flipPage = cur - 1; flipDragX = 0f; flipScrollY = 0f; notifyPage() }
            else -> animateFlipTo(0f) { }
        }
    }

    private fun animateFlipTo(target: Float, onEnd: () -> Unit) {
        flipAnim?.cancel()
        val a = ValueAnimator.ofFloat(flipDragX, target)
        a.duration = 200
        a.addUpdateListener {
            flipDragX = it.animatedValue as Float
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) onEnd()
                invalidate()
            }
        })
        flipAnim = a
        a.start()
    }

    override fun computeScroll() {
        if (mode != MODE_SCROLL) return
        if (scroller.computeScrollOffset()) {
            offsetY = scroller.currY.toFloat().coerceIn(0f, maxOffsetY())
            postInvalidateOnAnimation()
            notifyPage()
        }
    }

    private fun notifyPage() {
        val p = currentPage()
        if (p != lastReportedPage) {
            lastReportedPage = p
            listener?.onPageChanged(p, pageCount)
        }
    }

    /** 清空位图缓存（例如换书、改裁边时） */
    fun clearCache() {
        pending.clear()
        renderWidth.clear()
        try { cache.evictAll() } catch (_: Exception) {}
    }
}
