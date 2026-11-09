package com.pikafish.chess.ui

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ★★★ 桌布净化器（Tablecloth Sanitizer）★★★
 *
 * 目的：别人家的棋盘图（自带棋线 / 楚河汉界 / 炮位兵位十字标 / 外圈刻度数字 / 水印）
 *      导入后只留「底色」—— 棋线粗细、棋线颜色、楚河汉界文字、十字标我们本 App 自己会画，
 *      对方那些画上去只会重复打架。
 *
 * 步骤：
 *   1) 认出棋盘格：竖直/水平方向求「线强度」剖面，用「9 条竖线 + 10 条横线 + 等间距」
 *      做梳状匹配（Comb），再用棋盘格近似正方形 + 占画面比例做校验。认不出 → 视为
 *      纯纹理桌布，原样保留（绝不乱洗，避免把干净的纯纹理洗花）。
 *   2) 裁掉棋盘格以外的一切（外框、1~9 刻度、九八七…六五四三二一 刻度）。
 *   3) 建「墨迹掩膜」：格线（自适应墨宽 + 描边光晕）/ 九宫斜线 / 十四处十字标 /
 *      整条河界带（楚河汉界文字与浮雕）/ 全局残差小连通块（水印星标）。
 *   4) 把掩膜外扩 7px 后做多尺度推拉填充（push-pull），按周边底色插值把墨迹补平。
 *   5) ★鲁棒底色板重建★：把去墨结果切成约半格大的方块，每块取「中值色」再双线性放大。
 *      中值天生抗少数派墨迹 —— 线条残影、楚河汉界带、十字标光晕、水印，只要不占满半块，
 *      就完全影响不到底色；最终得到一张「只有颜色、没有任何棋具痕迹」的干净桌布。
 *
 * 全部纯像素运算，无第三方依赖；输入输出均为 ARGB_8888。
 */
object TableclothSanitizer {

    private const val TAG = "TableclothSanitizer"

    /** 工作分辨率上限（够手机棋盘纸清晰，又控住内存/耗时） */
    private const val MAX_DIM = 900

    /** 横向 9 条线、纵向 10 条线（象棋盘固定） */
    private const val COLS = 9
    private const val ROWS = 10

    /** 墨迹判定阈值（亮度差，0~255） */
    private const val INK_THR = 7f

    /** 识别出的棋盘格：以最上最左交点为原点，cw/ch 为列/行间距 */
    private class Grid(val x0: Float, val y0: Float, val cw: Float, val ch: Float, val quality: Double)
    private class Comb(val x0: Float, val cw: Float, val quality: Double)
    private class Lev(val w: Int, val h: Int, val c: FloatArray, val wt: FloatArray)

    // ═══════════════════════ 对外入口 ═══════════════════════

    /** 净化一张桌布；认不出棋盘格时原样（仅尺寸归一）返回。异常返回 null。 */
    fun sanitize(src: Bitmap): Bitmap? {
        var work: Bitmap? = null
        return try {
            val wk = downscale(src, MAX_DIM)
            work = wk
            val w = wk.width
            val h = wk.height
            if (w < 60 || h < 60) return wk
            val px = IntArray(w * h)
            wk.getPixels(px, 0, w, 0, 0, w, h)
            val lum = IntArray(px.size) { lumOf(px[it]) }

            val grid = detectGrid(lum, w, h) ?: return wk   // 纯纹理桌布：不动它

            // ── 1) 裁到棋盘格（外框 / 刻度出局） ──
            val cell = min(grid.cw, grid.ch)
            val pad = max(2f, 0.06f * cell)
            val cx0 = (grid.x0 - pad).roundToInt().coerceIn(0, w - 8)
            val cy0 = (grid.y0 - pad).roundToInt().coerceIn(0, h - 8)
            val cx1 = (grid.x0 + (COLS - 1) * grid.cw + pad).roundToInt().coerceIn(cx0 + 8, w)
            val cy1 = (grid.y0 + (ROWS - 1) * grid.ch + pad).roundToInt().coerceIn(cy0 + 8, h)
            val cw = cx1 - cx0
            val chh = cy1 - cy0
            val crop = IntArray(cw * chh)
            for (y in 0 until chh) System.arraycopy(px, (cy0 + y) * w + cx0, crop, y * cw, cw)

            // ── 2) 建墨迹掩膜（裁后坐标系） ──
            val lineX = FloatArray(COLS) { grid.x0 + it * grid.cw - cx0 }
            val lineY = FloatArray(ROWS) { grid.y0 + it * grid.ch - cy0 }
            val mask = buildInkMask(crop, cw, chh, lineX, lineY, cell)

            // ── 3) 掩膜外扩 + 羽化权重（7px 内一律 0，外圈渐升，连描边光晕一并排除） ──
            val m7 = dilate(mask, cw, chh, 7)
            val m11 = dilate(mask, cw, chh, 11)
            val m15 = dilate(mask, cw, chh, 15)
            val wt = FloatArray(mask.size)
            for (i in wt.indices) {
                wt[i] = when {
                    m7[i] -> 0f
                    m11[i] -> 0.3f
                    m15[i] -> 0.6f
                    else -> 1f
                }
            }

            // ── 4) 多尺度推拉填充：按周边底色把墨迹补平 ──
            val filled = pushPullFill(crop, wt, cw, chh)

            // ── 5) 统一重建底色板：整体降采样再放大，抹平补丁与残墨 ──
            val out = rebuildBase(filled, cw, chh, cell)
            val bmp = Bitmap.createBitmap(cw, chh, Bitmap.Config.ARGB_8888)
            bmp.setPixels(out, 0, cw, 0, 0, cw, chh)
            if (wk !== src && !wk.isRecycled) wk.recycle()
            work = null
            bmp
        } catch (t: Throwable) {
            Log.e(TAG, "sanitize failed", t)
            work?.let { if (it !== src && !it.isRecycled) it.recycle() }
            null
        }
    }

    // ═══════════════════════ 1) 棋盘格识别 ═══════════════════════

    private fun detectGrid(lum: IntArray, w: Int, h: Int): Grid? {
        if (w < 60 || h < 60) return null

        // 竖线强度剖面：|I(x+1)-I(x-1)| 沿 y 累加
        val pv = DoubleArray(w)
        for (x in 1 until w - 1) {
            var s = 0L
            for (y in 0 until h) {
                val i = y * w + x
                s += abs(lum[i + 1] - lum[i - 1]).toLong()
            }
            pv[x] = s.toDouble() / h
        }
        // 横线强度剖面
        val ph = DoubleArray(h)
        for (y in 1 until h - 1) {
            var s = 0L
            val up = (y - 1) * w
            val dn = (y + 1) * w
            for (x in 0 until w) s += abs(lum[dn + x] - lum[up + x]).toLong()
            ph[y] = s.toDouble() / w
        }
        smooth3(pv)
        smooth3(ph)

        val vx = findComb(pv, COLS) ?: return null
        val vy = findComb(ph, ROWS) ?: return null

        val cw = vx.cw
        val ch = vy.cw
        if (abs(cw - ch) > 0.35f * max(cw, ch)) return null
        if ((COLS - 1) * cw < 0.55f * w || (ROWS - 1) * ch < 0.55f * h) return null
        if (cw < 0.05f * w || cw > 0.20f * w) return null
        if (ch < 0.04f * h || ch > 0.22f * h) return null
        if (vx.quality < 1.5 || vy.quality < 1.5) return null

        return Grid(vx.x0, vy.x0, cw, ch, min(vx.quality, vy.quality))
    }

    private fun smooth3(p: DoubleArray) {
        if (p.size < 3) return
        val cp = p.copyOf()
        for (i in 1 until p.size - 1) p[i] = (cp[i - 1] + cp[i] + cp[i + 1]) / 3.0
    }

    /** 梳状匹配：找 n 条等间距直线（锚点 → 扫间距 → 最小二乘拟合原点/间距）。 */
    private fun findComb(p: DoubleArray, n: Int): Comb? {
        if (p.size < 40) return null
        val sorted = p.copyOf()
        sorted.sort()
        val med = sorted[sorted.size / 2]
        if (med <= 0.0001) return null
        var mean = 0.0
        for (v in p) mean += v
        mean /= p.size
        var vs = 0.0
        for (v in p) vs += (v - mean) * (v - mean)
        val std = sqrt(vs / p.size)
        val peakThr = mean + 0.9 * std

        val mg = max(6, (0.02 * p.size).toInt())
        val anchors = ArrayList<Int>(64)
        for (i in 2 until p.size - 2) {
            if (i < mg || i >= p.size - mg) continue
            if (p[i] >= peakThr && p[i] > p[i - 1] && p[i] >= p[i + 1] &&
                p[i] > p[i - 2] && p[i] > p[i + 2]) anchors.add(i)
        }
        if (anchors.isEmpty()) return null

        val span = p.size.toDouble()
        val sMin = 0.045 * span
        val sMax = 0.20 * span
        if (sMax <= sMin) return null

        var bestA = -1
        var bestS = 0.0
        var bestQ = -1.0
        for (a in anchors) {
            var s = sMin
            while (s <= sMax) {
                val frac = (n - 1) * s / span
                if (frac in 0.55..0.95 && a + (n - 1) * s < span) {
                    var score = 0.0
                    var midSum = 0.0
                    var last = -1
                    var ok = true
                    for (k in 0 until n) {
                        val want = (a + k * s).roundToInt()
                        if (want < 0 || want >= p.size) { ok = false; break }
                        val pos = snap(p, want)
                        if (pos < 0 || pos <= last) { ok = false; break }
                        last = pos
                        score += p[pos]
                    }
                    if (ok) {
                        for (k in 0 until n - 1) {
                            val m = (a + (k + 0.5) * s).roundToInt().coerceIn(0, p.size - 1)
                            midSum += p[m]
                        }
                        val q = (score / n - midSum / (n - 1)) / med
                        if (q > bestQ) { bestQ = q; bestA = a; bestS = s }
                    }
                }
                s += 0.25
            }
        }
        if (bestA < 0 || bestQ <= 0.0) return null

        val pos = FloatArray(n)
        var last = -1
        for (k in 0 until n) {
            val want = (bestA + k * bestS).roundToInt()
            val pp = snap(p, want)
            if (pp < 0 || pp <= last) return null
            last = pp
            pos[k] = pp.toFloat()
        }
        val m = n.toDouble()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (k in 0 until n) {
            val x = k.toDouble(); val y = pos[k].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val den = m * sxx - sx * sx
        if (abs(den) < 1e-6) return null
        val cw = (m * sxy - sx * sy) / den
        if (cw <= 1.0) return null
        val x0 = (sy - cw * sx) / m
        return Comb(x0.toFloat(), cw.toFloat(), bestQ)
    }

    private fun snap(p: DoubleArray, want: Int): Int {
        val a = max(0, want - 2)
        val b = min(p.size - 1, want + 2)
        var best = -1
        var bv = -1.0
        for (i in a..b) if (p[i] > bv) { bv = p[i]; best = i }
        return best
    }

    // ═══════════════════════ 2) 墨迹掩膜 ═══════════════════════

    /**
     * 建墨迹掩膜：格线（自适应墨宽，连光晕）/ 九宫斜线 / 十四处十字标 / 整条河界带 /
     * 全局残差小连通块（水印）。
     */
    private fun buildInkMask(px: IntArray, w: Int, h: Int,
                             lineX: FloatArray, lineY: FloatArray, cell: Float): BooleanArray {
        val lum = IntArray(px.size) { lumOf(px[it]) }
        val mask = BooleanArray(px.size)
        val W = max(5, (0.16f * cell).roundToInt())
        val mar = max(2, (0.035f * cell).roundToInt())

        // 格线：逐线取垂直剖面，自适应找出「墨宽 + 描边」
        for (lx in lineX) {
            val c = lx.roundToInt()
            val prof = FloatArray(2 * W + 1)
            for (k in -W..W) {
                val xx = c + k
                if (xx < 0 || xx >= w) { prof[k + W] = prof[(k + W).coerceAtLeast(0).let { if (k + W == 0) 0 else k + W - 1 }]; continue }
                var s = 0L
                for (y in 0 until h) s += lum[y * w + xx]
                prof[k + W] = s.toFloat() / h
            }
            val span = inkSpan(prof)
            val xa = max(0, c + (span[0] - W) - mar)
            val xb = min(w - 1, c + (span[1] - W) + mar)
            for (x in xa..xb) { var i = x; for (y in 0 until h) { mask[i] = true; i += w } }
        }
        for (ly in lineY) {
            val c = ly.roundToInt()
            val prof = FloatArray(2 * W + 1)
            for (k in -W..W) {
                val yy = c + k
                if (yy < 0 || yy >= h) { prof[k + W] = if (k + W == 0) 0f else prof[k + W - 1]; continue }
                var s = 0L
                val off = yy * w
                for (x in 0 until w) s += lum[off + x]
                prof[k + W] = s.toFloat() / w
            }
            val span = inkSpan(prof)
            val ya = max(0, c + (span[0] - W) - mar)
            val yb = min(h - 1, c + (span[1] - W) + mar)
            for (y in ya..yb) { val off = y * w; for (x in 0 until w) mask[off + x] = true }
        }

        markPalaceDiagonals(mask, w, h, lineX, lineY, lum, W, mar)
        markCrosses(mask, w, h, lineX, lineY, max(9f, 0.30f * cell))
        // 整条河界带（楚河汉界文字 + 浮雕 + 云纹一律重铺）
        val yT = (lineY[4] + mar).roundToInt().coerceIn(0, h - 1)
        val yB = (lineY[5] - mar).roundToInt().coerceIn(0, h - 1)
        if (yB > yT) for (y in yT..yB) { val off = y * w; for (x in 0 until w) mask[off + x] = true }

        markResidualBlobs(px, mask, w, h, max(6f, 0.45f * cell))
        return mask
    }

    /** 以剖面两侧外缘为底色，找中心附近连续偏离 > INK_THR 的像素区间 [lo, hi]。 */
    private fun inkSpan(prof: FloatArray): IntArray {
        val n = prof.size
        val half = n / 2
        val edge = max(2, half / 3)
        val edgeVals = FloatArray(edge * 2)
        for (i in 0 until edge) edgeVals[i] = prof[i]
        for (i in 0 until edge) edgeVals[edge + i] = prof[n - edge + i]
        edgeVals.sort()
        val base = edgeVals[edgeVals.size / 2]
        var lo = half
        while (lo > 0 && abs(prof[lo - 1] - base) > INK_THR) lo--
        var hi = half
        while (hi < n - 1 && abs(prof[hi + 1] - base) > INK_THR) hi++
        return intArrayOf(lo, hi)
    }

    /** 九宫斜线：上下两个九宫各两条对角线（同属「人家的棋线」）。 */
    private fun markPalaceDiagonals(mask: BooleanArray, w: Int, h: Int,
                                    lineX: FloatArray, lineY: FloatArray,
                                    lum: IntArray, W: Int, mar: Int) {
        val segs = arrayOf(
            intArrayOf(3, 0, 5, 2), intArrayOf(5, 0, 3, 2),
            intArrayOf(3, 7, 5, 9), intArrayOf(5, 7, 3, 9)
        )
        for (s in segs) {
            val p1x = lineX[s[0]]; val p1y = lineY[s[1]]
            val p2x = lineX[s[2]]; val p2y = lineY[s[3]]
            val dx = p2x - p1x; val dy = p2y - p1y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 4f) continue
            val ux = dx / len; val uy = dy / len
            val nx = -uy; val ny = ux
            val prof = FloatArray(2 * W + 1)
            for (k in -W..W) {
                var sum = 0f; var cnt = 0
                var t = 0.03f
                while (t <= 0.97f) {
                    val ix = (p1x + t * dx + nx * k).roundToInt()
                    val iy = (p1y + t * dy + ny * k).roundToInt()
                    if (ix in 0 until w && iy in 0 until h) { sum += lum[iy * w + ix]; cnt++ }
                    t += (0.94f / 31f)
                }
                prof[k + W] = if (cnt > 0) sum / cnt else if (k + W == 0) 0f else prof[k + W - 1]
            }
            val span = inkSpan(prof)
            val d1 = (span[0] - W - mar).toFloat()
            val d2 = (span[1] - W + mar).toFloat()
            val xa = max(0, (min(p1x, p2x) - W - mar).roundToInt())
            val xb = min(w - 1, (max(p1x, p2x) + W + mar).roundToInt())
            val ya = max(0, (min(p1y, p2y) - W - mar).roundToInt())
            val yb = min(h - 1, (max(p1y, p2y) + W + mar).roundToInt())
            val l2 = len * len
            for (y in ya..yb) {
                val off = y * w
                for (x in xa..xb) {
                    val dd = (x - p1x) * nx + (y - p1y) * ny
                    if (dd < d1 || dd > d2) continue
                    var t = ((x - p1x) * dx + (y - p1y) * dy) / l2
                    if (t < 0f) t = 0f else if (t > 1f) t = 1f
                    if (t in -0.02f..1.02f) mask[off + x] = true
                }
            }
        }
    }

    /** 十四处十字标：炮位 (1,2)(7,2)(1,7)(7,7) + 兵卒位 (0/2/4/6/8,3) 与 (0/2/4/6/8,6)。 */
    private fun markCrosses(mask: BooleanArray, w: Int, h: Int,
                            lineX: FloatArray, lineY: FloatArray, mr: Float) {
        val pts = ArrayList<Pair<Int, Int>>(14)
        pts.add(1 to 2); pts.add(7 to 2); pts.add(1 to 7); pts.add(7 to 7)
        for (c in 0 until COLS step 2) { pts.add(c to 3); pts.add(c to 6) }
        val r = mr.roundToInt().coerceAtLeast(1)
        for ((c, row) in pts) {
            val cx = lineX[c].roundToInt(); val cy = lineY[row].roundToInt()
            val xa = max(0, cx - r); val xb = min(w - 1, cx + r)
            val ya = max(0, cy - r); val yb = min(h - 1, cy + r)
            for (y in ya..yb) { val off = y * w; for (x in xa..xb) mask[off + x] = true }
        }
    }

    /**
     * 全局残差小连通块补扫：拿「大半径均值」当参考底，最大通道差超阈的孤立小块
     * （水印星标、零星残留）一并抹掉。带面积上限 + 总量上限双保险，防误伤纹理。
     */
    private fun markResidualBlobs(px: IntArray, mask: BooleanArray, w: Int, h: Int, radius: Float) {
        val ref = smoothReference(px, w, h, max(3, (radius * 0.5f).roundToInt()))
        val cand = BooleanArray(px.size)
        var candCount = 0
        for (i in px.indices) {
            if (mask[i]) continue
            if (maxChannelDiff(px[i], ref[i]) > 60) { cand[i] = true; candCount++ }
        }
        if (candCount == 0) return

        val areaCap = (0.02f * px.size).toInt().coerceAtLeast(64)
        val totalCap = (0.10f * px.size).toInt()
        val cap = areaCap + 8
        val stack = IntArray(cap)
        val comp = IntArray(cap)
        var total = 0
        val visited = BooleanArray(px.size)
        for (start in px.indices) {
            if (!cand[start] || visited[start]) continue
            var sp = 0; var cp = 0; var acc = 0; var overflow = false
            stack[sp++] = start
            visited[start] = true
            while (sp > 0) {
                val idx = stack[--sp]
                comp[cp] = idx; cp++; acc++
                if (acc > areaCap) { overflow = true; break }
                val x = idx % w; val y = idx / w
                if (x > 0 && cand[idx - 1] && !visited[idx - 1]) { visited[idx - 1] = true; stack[sp++] = idx - 1 }
                if (x < w - 1 && cand[idx + 1] && !visited[idx + 1]) { visited[idx + 1] = true; stack[sp++] = idx + 1 }
                if (y > 0 && cand[idx - w] && !visited[idx - w]) { visited[idx - w] = true; stack[sp++] = idx - w }
                if (y < h - 1 && cand[idx + w] && !visited[idx + w]) { visited[idx + w] = true; stack[sp++] = idx + w }
            }
            if (!overflow && acc >= 8) {
                if (total + acc > totalCap) return
                for (k in 0 until cp) mask[comp[k]] = true
                total += acc
            }
        }
    }

    /** 大半径均值参考底：先按 factor 降采样做块平均，再双线性升回原尺寸。 */
    private fun smoothReference(px: IntArray, w: Int, h: Int, factor: Int): IntArray {
        val f = factor.coerceAtLeast(2)
        val sw = max(1, (w + f - 1) / f)
        val sh = max(1, (h + f - 1) / f)
        val sr = FloatArray(sw * sh); val sg = FloatArray(sw * sh); val sb = FloatArray(sw * sh)
        val cnt = IntArray(sw * sh)
        for (y in 0 until h) {
            val sy = min(sh - 1, y / f)
            val off = y * w
            for (x in 0 until w) {
                val si = sy * sw + min(sw - 1, x / f)
                val c = px[off + x]
                sr[si] += ((c shr 16) and 0xFF).toFloat()
                sg[si] += ((c shr 8) and 0xFF).toFloat()
                sb[si] += (c and 0xFF).toFloat()
                cnt[si]++
            }
        }
        for (i in cnt.indices) { val n = cnt[i].coerceAtLeast(1).toFloat(); sr[i] /= n; sg[i] /= n; sb[i] /= n }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt().coerceIn(0, sh - 1); val y1 = min(sh - 1, y0 + 1); val ty = fy - y0
            val off = y * w
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt().coerceIn(0, sw - 1); val x1 = min(sw - 1, x0 + 1); val tx = fx - x0
                val i00 = y0 * sw + x0; val i10 = y0 * sw + x1
                val i01 = y1 * sw + x0; val i11 = y1 * sw + x1
                val r = bilerpF(sr[i00], sr[i10], sr[i01], sr[i11], tx, ty).roundToInt().coerceIn(0, 255)
                val g = bilerpF(sg[i00], sg[i10], sg[i01], sg[i11], tx, ty).roundToInt().coerceIn(0, 255)
                val b = bilerpF(sb[i00], sb[i10], sb[i01], sb[i11], tx, ty).roundToInt().coerceIn(0, 255)
                out[off + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun bilerpF(c00: Float, c10: Float, c01: Float, c11: Float, tx: Float, ty: Float): Float {
        val a = c00 + (c10 - c00) * tx
        val b = c01 + (c11 - c01) * tx
        return a + (b - a) * ty
    }

    // ═══════════════════════ 3) 多尺度推拉填充 ═══════════════════════

    /**
     * push-pull（推-拉）插值：逐级降采样时按权重求均值（pull），再从粗到细把
     * 被掩膜处用上层插值结果补上（push）。天然平滑、无方向性伪影。
     */
    private fun pushPullFill(px: IntArray, wt0: FloatArray, w: Int, h: Int): IntArray {
        val levels = ArrayList<Lev>(16)
        val l0 = Lev(w, h, FloatArray(w * h * 3), wt0.copyOf())
        for (i in px.indices) {
            val c = px[i]
            l0.c[i * 3] = ((c shr 16) and 0xFF).toFloat()
            l0.c[i * 3 + 1] = ((c shr 8) and 0xFF).toFloat()
            l0.c[i * 3 + 2] = (c and 0xFF).toFloat()
        }
        levels.add(l0)

        var cur = l0
        while (cur.w > 2 && cur.h > 2) {
            val nw = (cur.w + 1) / 2
            val nh = (cur.h + 1) / 2
            val nl = Lev(nw, nh, FloatArray(nw * nh * 3), FloatArray(nw * nh))
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var sw = 0f; var r = 0f; var g = 0f; var b = 0f
                    for (dy in 0..1) {
                        val sy = y * 2 + dy
                        if (sy >= cur.h) continue
                        for (dx in 0..1) {
                            val sx = x * 2 + dx
                            if (sx >= cur.w) continue
                            val si = sy * cur.w + sx
                            val ww = cur.wt[si]
                            if (ww <= 0f) continue
                            sw += ww
                            r += ww * cur.c[si * 3]
                            g += ww * cur.c[si * 3 + 1]
                            b += ww * cur.c[si * 3 + 2]
                        }
                    }
                    val ni = y * nw + x
                    if (sw > 1e-6f) {
                        nl.c[ni * 3] = r / sw
                        nl.c[ni * 3 + 1] = g / sw
                        nl.c[ni * 3 + 2] = b / sw
                        nl.wt[ni] = (sw * 0.25f).coerceAtMost(1f)
                    } else nl.wt[ni] = 0f
                }
            }
            levels.add(nl)
            cur = nl
        }

        val top = levels[levels.size - 1]
        var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
        for (i in top.wt.indices) if (top.wt[i] > 0f) {
            sr += top.c[i * 3]; sg += top.c[i * 3 + 1]; sb += top.c[i * 3 + 2]; n++
        }
        if (n > 0) {
            sr /= n; sg /= n; sb /= n
            for (i in top.wt.indices) if (top.wt[i] <= 0f) {
                top.c[i * 3] = sr; top.c[i * 3 + 1] = sg; top.c[i * 3 + 2] = sb; top.wt[i] = 1f
            }
        }

        for (li in levels.size - 2 downTo 0) {
            val c = levels[li]
            val par = levels[li + 1]
            for (y in 0 until c.h) {
                for (x in 0 until c.w) {
                    val i = y * c.w + x
                    val t = c.wt[i]
                    if (t >= 0.999f) continue
                    val fx = (x + 0.5f) * 0.5f - 0.5f
                    val fy = (y + 0.5f) * 0.5f - 0.5f
                    val pr = bilerpLev(par, fx, fy, 0)
                    val pg = bilerpLev(par, fx, fy, 1)
                    val pb = bilerpLev(par, fx, fy, 2)
                    val tt = t.coerceIn(0f, 1f)
                    c.c[i * 3] = c.c[i * 3] * tt + pr * (1f - tt)
                    c.c[i * 3 + 1] = c.c[i * 3 + 1] * tt + pg * (1f - tt)
                    c.c[i * 3 + 2] = c.c[i * 3 + 2] * tt + pb * (1f - tt)
                }
            }
        }

        val out = IntArray(px.size)
        val base = levels[0]
        for (i in px.indices) {
            val r = base.c[i * 3].roundToInt().coerceIn(0, 255)
            val g = base.c[i * 3 + 1].roundToInt().coerceIn(0, 255)
            val b = base.c[i * 3 + 2].roundToInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun bilerpLev(l: Lev, fx: Float, fy: Float, ch: Int): Float {
        val x0 = fx.toInt().coerceIn(0, l.w - 1)
        val y0 = fy.toInt().coerceIn(0, l.h - 1)
        val x1 = min(l.w - 1, x0 + 1)
        val y1 = min(l.h - 1, y0 + 1)
        val tx = (fx - x0).coerceIn(0f, 1f)
        val ty = (fy - y0).coerceIn(0f, 1f)
        val c00 = l.c[(y0 * l.w + x0) * 3 + ch]
        val c10 = l.c[(y0 * l.w + x1) * 3 + ch]
        val c01 = l.c[(y1 * l.w + x0) * 3 + ch]
        val c11 = l.c[(y1 * l.w + x1) * 3 + ch]
        return bilerpF(c00, c10, c01, c11, tx, ty)
    }

    // ═══════════════════════ 4) 鲁棒底色板重建 ═══════════════════════

    /**
     * ★鲁棒底色板重建★：把去墨后的画面按约「半格」大小切块，每块每通道取中值（50% 分位），
     * 再双线性放大回原尺寸。
     *
     * 为什么用中值而不是均值：均值会把「少数派」的墨迹一并算进底色（于是楚河汉界带、
     * 十字标光晕、水印都留下淡淡痕迹）；中值只取中间那个像素，只要墨迹不占满半块，
     * 就丝毫影响不到底色 —— 于是得到一张只剩颜色、干干净净的底板。
     *
     * 求中值用 256 桶直方图（O(n+256)），比排序快得多，整幅 900×900 也就几十毫秒。
     */
    private fun rebuildBase(px: IntArray, w: Int, h: Int, cell: Float): IntArray {
        val b = (0.55f * cell).roundToInt().coerceIn(12, 160)
        val sw = max(2, w / b)
        val sh = max(2, h / b)
        val medR = IntArray(sw * sh)
        val medG = IntArray(sw * sh)
        val medB = IntArray(sw * sh)
        val hist = IntArray(256)
        for (sy in 0 until sh) {
            val y0 = sy * h / sh
            val y1 = max(y0 + 1, (sy + 1) * h / sh)
            for (sx in 0 until sw) {
                val x0 = sx * w / sw
                val x1 = max(x0 + 1, (sx + 1) * w / sw)
                val half = ((y1 - y0) * (x1 - x0)) / 2
                val si = sy * sw + sx
                java.util.Arrays.fill(hist, 0)
                for (y in y0 until y1) { val off = y * w; for (x in x0 until x1) hist[(px[off + x] shr 16) and 0xFF]++ }
                medR[si] = medianOfHist(hist, half)
                java.util.Arrays.fill(hist, 0)
                for (y in y0 until y1) { val off = y * w; for (x in x0 until x1) hist[(px[off + x] shr 8) and 0xFF]++ }
                medG[si] = medianOfHist(hist, half)
                java.util.Arrays.fill(hist, 0)
                for (y in y0 until y1) { val off = y * w; for (x in x0 until x1) hist[px[off + x] and 0xFF]++ }
                medB[si] = medianOfHist(hist, half)
            }
        }
        // 双线性放大：块中值 → 平滑的大尺度底色场
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt().coerceIn(0, sh - 1); val y1 = min(sh - 1, y0 + 1); val ty = fy - y0
            val off = y * w
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt().coerceIn(0, sw - 1); val x1 = min(sw - 1, x0 + 1); val tx = fx - x0
                val i00 = y0 * sw + x0; val i10 = y0 * sw + x1
                val i01 = y1 * sw + x0; val i11 = y1 * sw + x1
                val r = bilerpI(medR[i00], medR[i10], medR[i01], medR[i11], tx, ty)
                val g = bilerpI(medG[i00], medG[i10], medG[i01], medG[i11], tx, ty)
                val bl = bilerpI(medB[i00], medB[i10], medB[i01], medB[i11], tx, ty)
                out[off + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        return out
    }

    /** 256 桶直方图求中值：累计到超过一半时即为中值。 */
    private fun medianOfHist(hist: IntArray, half: Int): Int {
        var acc = 0
        for (v in 0..255) { acc += hist[v]; if (acc > half) return v }
        return 255
    }

    private fun bilerpI(c00: Int, c10: Int, c01: Int, c11: Int, tx: Float, ty: Float): Int {
        val a = c00 + (c10 - c00) * tx
        val b = c01 + (c11 - c01) * tx
        return (a + (b - a) * ty).roundToInt().coerceIn(0, 255)
    }

    // ═══════════════════════ 小工具 ═══════════════════════

    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        var cur = src
        repeat(r.coerceAtLeast(0)) {
            val nxt = BooleanArray(cur.size)
            for (y in 0 until h) {
                val off = y * w
                for (x in 0 until w) {
                    val i = off + x
                    if (cur[i]) { nxt[i] = true; continue }
                    if ((x > 0 && cur[i - 1]) || (x < w - 1 && cur[i + 1]) ||
                        (y > 0 && cur[i - w]) || (y < h - 1 && cur[i + w])) nxt[i] = true
                }
            }
            cur = nxt
        }
        return cur
    }

    /** 缩到工作分辨率。★ 大图直接缩放，不要先 copy 一份全尺寸副本（那是白占几十 MB 的峰值）。 */
    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxDim) {
            return try { src.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { src }
        }
        val s = maxDim.toFloat() / m
        val w = max(1, (src.width * s).roundToInt())
        val h = max(1, (src.height * s).roundToInt())
        return try { Bitmap.createScaledBitmap(src, w, h, true) } catch (_: Throwable) { src }
    }

    private fun lumOf(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    private fun maxChannelDiff(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }
}