package com.pikafish.chess.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint

/**
 * ★ 复盘页的装饰分隔线：短实横线 —— 小圆点 —— 短实横线 …… 一路排过去。
 *
 * 要点：圆点与横线严格落在同一条水平中线上（不是句号那种沉在基线下的实心点），
 * 所以看上去就是一根"虚实相间"的复合横线；用来把
 * 「上方锁定不滚动的区域（小棋盘 / 曲线 / 摘要）」与
 * 「下方只滚自己的棋谱区」在视觉上利落地划开。
 *
 * 高度：未指定时取 12dp，圆点恒在 height/2 处。
 */
class DashDotDividerView(context: Context) : android.view.View(context) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFB300.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFB300.toInt()
        style = Paint.Style.FILL
    }

    private val dashLen = dp(15f)   // 短实横线长度
    private val dotR = dp(1.9f)     // 小圆点半径
    private val gap = dp(5f)        // 横线与圆点之间的留白

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else dp(12f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w <= 0) return
        val cy = height / 2f          // ★ 中线：横线与圆点共用，圆点绝不偏下
        var x = 0f
        while (x < w) {
            val end = (x + dashLen).coerceAtMost(w)
            if (end > x) canvas.drawLine(x, cy, end, cy, line)
            x = end + gap
            if (x >= w) break
            val dx = (x + dotR).coerceAtMost(w)
            canvas.drawCircle(dx, cy, dotR, dot)
            x = dx + dotR + gap
        }
    }

    private fun dp(f: Float): Float = f * resources.displayMetrics.density
    private fun dp(i: Int): Float = dp(i.toFloat())
}