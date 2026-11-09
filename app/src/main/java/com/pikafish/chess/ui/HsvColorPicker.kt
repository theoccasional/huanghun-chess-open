package com.pikafish.chess.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * ★ 自绘 HSV 色相取色器（色谱）：
 * 上部一大块 = 饱和度(S/横) × 明度(V/纵) 平面；底部一整条 = 色相(H) 滑条。
 * 用手指在任意位置拖动即可连续取出任意颜色，拖动时实时回调 [onColorChanged]。
 * 只做取色，不改任何全局设置。
 */
class HsvColorPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 颜色变化实时回调（参数 ARGB） */
    var onColorChanged: ((Int) -> Unit)? = null

    private var hue = 210f        // 0..360 度
    private var saturation = 0.8f // 0..1
    private var value = 0.9f      // 0..1

    val color: Int
        get() = Color.HSVToColor(floatArrayOf(hue, saturation, value))

    fun setColor(argb: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
        invalidate()
    }

    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = 0xFF1E1E1E.toInt()
    }

    /** 按当前色相预生成的 SV 平面位图（只有色相变化 / 尺寸变化时才重建） */
    private var svBitmap: Bitmap? = null
    private var padX = dp(4f)
    private var padV = dp(8f)
    private var barH = dp(26f)
    private var svW = 0
    private var svH = 0

    private val hueStops = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
    )

    private fun rebuildSvBitmap() {
        if (svW <= 0 || svH <= 0) return
        val bmp = Bitmap.createBitmap(svW, svH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hueArgb = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, svW.toFloat(), 0f, Color.WHITE, hueArgb, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, svW.toFloat(), svH.toFloat(), p)
        p.shader = LinearGradient(0f, 0f, 0f, svH.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, svW.toFloat(), svH.toFloat(), p)
        svBitmap = bmp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        padX = dp(4f); padV = dp(8f); barH = dp(26f)
        svW = (w - padX * 2).toInt().coerceAtLeast(1)
        svH = (h - padV * 2 - barH).toInt().coerceAtLeast(1)
        rebuildSvBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        val left = padX; val top = padV
        // SV 平面
        svBitmap?.let { canvas.drawBitmap(it, left, top, svPaint) }
        // SV 标记点（白芯 + 深边，任何底色都清晰）
        val mx = left + saturation * (svW - 1)
        val my = top + (1f - value) * (svH - 1)
        markerFill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(mx, my, dp(7f), markerFill)
        canvas.drawCircle(mx, my, dp(7f), markerEdge)
        // 底部色相条
        val hueTop = top + svH + padV
        val hueGrad = LinearGradient(left, 0f, left + svW - 1f, 0f, hueStops, null, Shader.TileMode.CLAMP)
        huePaint.shader = hueGrad
        canvas.drawRoundRect(left, hueTop, left + svW - 1f, hueTop + barH, dp(6f), dp(6f), huePaint)
        // 色相标记（三角托底，更易看清当前停在哪个色相）
        val hx = left + (hue / 360f) * (svW - 1)
        markerFill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(hx, hueTop + barH / 2f, dp(8f), markerFill)
        canvas.drawCircle(hx, hueTop + barH / 2f, dp(8f), markerEdge)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val left = padX; val top = padV
                val sx = (event.x - left).coerceIn(0f, (svW - 1).toFloat())
                val sy = (event.y - top).coerceIn(0f, (svH - 1).toFloat())
                if (event.y <= top + svH) {
                    saturation = sx / (svW - 1)
                    value = 1f - sy / (svH - 1)
                } else {
                    val hx = sx / (svW - 1)
                    if (hx in 0f..1f) { hue = hx * 360f; rebuildSvBitmap() }
                }
                onColorChanged?.invoke(color)
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            else -> {}
        }
        return true
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}