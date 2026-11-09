package com.pikafish.chess

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ★★★ 智能叠加（第 3 层）：笔画 / 字形校验 —— 用"关键笔画特征"复核模型结论
 *
 * 用户原始要求（务必保留原意，不得退化成"靠猜"）：
 *   · "你应该精确到那个笔画勾勒那一块，看他的走笔到底是怎么走的"
 *   · "你把那个字的关键笔画进行把它都拿捏出来……你就抓特征"
 *   · "不管是黑炮还是红炮，你就只管认识它这个包字……然后再就给它把颜色给它分配一下"
 *   · "車…抓住主要的，它的车中间是有田字"
 *   · "馬…它有四个点，这是繁体字"
 *   · "卒…你就认它的头部或者它的中部。它的头部是一点一横，然后它的中部是两个人，这和车完全是不一样的"
 *   · "这个逻辑是追加，不是替代原来的那些东西"
 *
 * 做法：把每个有子格子的字面墨迹抠出来（显著暗于盘面的笔画 + 显著红的笔画），
 * 归一化成 48×48，再抽取"关键笔画特征"与"多字体渲染模板"比对：
 *
 *   · 孔洞数 holes    —— 車 中间是个"田"（有闭口）；炮(包) 上部也有闭口；
 *                        卒 中部"两个人"、兵 下部"八" 没有闭口 → 一眼分开；
 *   · 连通块 comps    —— 卒（多个独立笔画块）与 车（上下贯通）截然不同；
 *   · 点数 dots       —— 馬（繁体）四点是其最显著特征；
 *   · 上点 topDot     —— 卒 头部"一点一横"的那一点，落在字的上三分之一且居中；
 *   · 三段孔洞 seg    —— 字分上/中/下三段看闭口落在哪段（車 的田在中段）；
 *   · 密度直方图 dens —— 12×12 粗粒度墨迹分布做"厚度不变"的归一化互相关（跨字体可迁移）。
 *
 * 颜色分配：车/炮/马 三方同形、字形分不出颜色，于是用"墨色红占比"独立判色
 * （这正是用户说的"再就给它把颜色给它分配一下"）；
 * 其余字（帅/将、仕/士、相/象、兵/卒）字形本身已唯一确定颜色，无需判色。
 *
 * ★ 纯叠加：本层只在【可动子力 {车,炮,马,兵,卒}】之间追加改判，
 *   不触碰 士/象/将帅（它们有点位约束，另有 positionBasedCorrection 负责）；
 *   并且调用方会在"改判后整盘评分不降低"时才采信（不猴子掰玉米）。
 */
object GlyphStrokeVerifier {

    private const val TAG = "GlyphStroke"

    /** 一键关停：置 false 后本层完全不参与，识别结果与未加此层时逐字节一致 */
    const val ENABLED = true

    // ── 归一化尺寸 / 密度格数（与 Python 原型 probe25 完全一致）──
    private const val N = 48
    private const val G = 12

    // ── 改判阈值 ──
    /** 异形改判所需的"字形分差"裕度：越大越保守 */
    private const val MARGIN = 0.12f
    /** 模型置信度高于该值时，需要更强字形证据（裕度 × STRONG_FACTOR）才敢改判 */
    private const val CONF_STRONG = 0.75f
    private const val STRONG_FACTOR = 1.8f
    /** 字形绝对分下限：低于此值认为字面抠得不干净，不做异形改判 */
    private const val MIN_SHAPE_ABS = 0.58f
    /** 仅改颜色时要求的最低字形分（自证"形是对的"） */
    private const val MIN_COLOR_ABS = 0.45f
    /** 墨色红占比：≥ 此值判红方，≤ 此值判黑方，中间灰区沿用模型颜色 */
    private const val RED_RATIO_RED = 0.55f
    private const val RED_RATIO_BLACK = 0.12f

    // ── warped 标准盘几何（450×500、padding 50、9 列 / 10 行）──
    private const val CELL_ORIGIN_X = 50f
    private const val CELL_ORIGIN_Y = 50f
    private const val CELL_COL_STEP = 43.75f
    private const val CELL_ROW_STEP = 44.44f
    /** 取字窗口半宽（约半格） */
    private const val CELL_HALF = 22

    // ═══════════════ 字形 / 形类定义 ═══════════════

    /** 候选字形（简繁双形）；每个字形直接对应一种"形类" */
    private val CHARS = arrayOf(
        "车", "車",   // 0,1  → 车
        "炮", "砲",   // 2,3  → 炮
        "马", "馬",   // 4,5  → 马
        "卒",         // 6    → 卒（黑）
        "兵",         // 7    → 兵（红）
        "士", "仕",   // 8,9  → 士(黑) / 仕(红)
        "象", "相",   // 10,11→ 象(黑) / 相(红)
        "将", "將",   // 12,13→ 将(黑)
        "帅", "帥"    // 14,15→ 帅(红)
    )

    // 形类编号
    private const val SHAPE_ROOK = 0
    private const val SHAPE_CANNON = 1
    private const val SHAPE_KNIGHT = 2
    private const val SHAPE_PAWN_BLACK = 3      // 卒
    private const val SHAPE_PAWN_RED = 4        // 兵
    private const val SHAPE_ADVISOR_BLACK = 5   // 士
    private const val SHAPE_ADVISOR_RED = 6     // 仕
    private const val SHAPE_ELEPHANT_BLACK = 7  // 象
    private const val SHAPE_ELEPHANT_RED = 8    // 相
    private const val SHAPE_KING_BLACK = 9      // 将
    private const val SHAPE_KING_RED = 10       // 帅
    private const val SHAPE_COUNT = 11

    private val CHAR_SHAPE = intArrayOf(
        SHAPE_ROOK, SHAPE_ROOK,
        SHAPE_CANNON, SHAPE_CANNON,
        SHAPE_KNIGHT, SHAPE_KNIGHT,
        SHAPE_PAWN_BLACK, SHAPE_PAWN_RED,
        SHAPE_ADVISOR_BLACK, SHAPE_ADVISOR_RED,
        SHAPE_ELEPHANT_BLACK, SHAPE_ELEPHANT_RED,
        SHAPE_KING_BLACK, SHAPE_KING_BLACK,
        SHAPE_KING_RED, SHAPE_KING_RED
    )

    /** 形类 → 红方布局类号（-1 = 该形类没有红方子） */
    private val SHAPE_RED_CLASS = intArrayOf(
        6, 7, 5, -1, 8, -1, 3, -1, 4, -1, 2
    )
    /** 形类 → 黑方布局类号（-1 = 该形类没有黑方子） */
    private val SHAPE_BLACK_CLASS = intArrayOf(
        13, 14, 12, 15, -1, 10, -1, 11, -1, 9, -1
    )

    /** 布局模型类号 → 形类（0/1 为 空/遮 无意义） */
    private val CLASS_TO_SHAPE = intArrayOf(
        -1, -1,
        SHAPE_KING_RED, SHAPE_ADVISOR_RED, SHAPE_ELEPHANT_RED, SHAPE_KNIGHT, SHAPE_ROOK, SHAPE_CANNON, SHAPE_PAWN_RED,
        SHAPE_KING_BLACK, SHAPE_ADVISOR_BLACK, SHAPE_ELEPHANT_BLACK, SHAPE_KNIGHT, SHAPE_ROOK, SHAPE_CANNON, SHAPE_PAWN_BLACK
    )

    /** 每方各类上限（下标 = 布局模型类号），与 OnnxBoardRecognizer.CLASS_MAX 同源 */
    private val CLASS_MAX = intArrayOf(
        0, 0,
        1, 2, 2, 2, 2, 2, 5,
        1, 2, 2, 2, 2, 2, 5
    )

    /** 允许改判的"可动子力"类号（车/炮/马/兵/卒 双方）——士象将帅有点位约束，本层不碰 */
    private val MOBILE = booleanArrayOf(
        false, false,
        false, false, false, true, true, true, true,      // 红 2..8：马5/车6/炮7/兵8
        false, false, false, true, true, true, true       // 黑 9..15：马12/车13/炮14/卒15
    )

    private val CLASS_NAME = arrayOf(
        "空", "遮", "帅", "仕", "相", "马", "车", "炮", "兵",
        "将", "士", "象", "马", "车", "炮", "卒"
    )

    // ═══════════════ 模板（多字体渲染）═══════════════

    private class Feature(
        val holes: Int,
        val comps: Int,
        val dots: Int,
        val topDot: Int,
        val seg: IntArray,
        val dens: FloatArray
    )

    @Volatile
    private var templates: List<Pair<Int, Feature>>? = null

    /** 系统里能渲染汉字的字体（用 Paint.hasGlyph 过滤，避免拿到没有 CJK 字形的字重） */
    private fun fontSpecs(): List<Typeface> {
        val cands = listOf(
            Typeface.create("serif", Typeface.BOLD),
            Typeface.create("sans-serif", Typeface.BOLD),
            Typeface.create("sans-serif-black", Typeface.NORMAL),
            Typeface.create("serif", Typeface.NORMAL)
        )
        val out = ArrayList<Typeface>(cands.size)
        for (tf in cands) {
            val p = Paint().apply { typeface = tf }
            if (p.hasGlyph("车") && p.hasGlyph("馬") && p.hasGlyph("帥")) {
                if (!out.any { it == tf }) out.add(tf)
            }
        }
        return out
    }

    private fun buildTemplates(): List<Pair<Int, Feature>> {
        val list = ArrayList<Pair<Int, Feature>>()
        for (tf in fontSpecs()) {
            for (ci in CHARS.indices) {
                val ink = renderChar(CHARS[ci], tf) ?: continue
                val cn = canon(ink.third, ink.first, ink.second) ?: continue
                list.add(CHAR_SHAPE[ci] to features(clean(cn)))
            }
        }
        if (list.isNotEmpty()) {
            Log.i(TAG, "笔画模板就绪：${list.size} 个（${fontSpecs().size} 字体 × ${CHARS.size} 字形）")
        } else {
            Log.w(TAG, "笔画模板为空（系统字体无法渲染汉字），本层自动不生效")
        }
        return list
    }

    private fun tpl(): List<Pair<Int, Feature>> {
        var t = templates
        if (t == null) {
            synchronized(this) {
                t = templates
                if (t == null) {
                    t = buildTemplates()
                    templates = t
                }
            }
        }
        return t!!
    }

    /** 渲染单字 → 墨迹掩膜（宽、高、掩膜） */
    private fun renderChar(ch: String, tf: Typeface, size: Int = 200): Triple<Int, Int, BooleanArray>? {
        try {
            val w = size + 100
            val bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.TRANSPARENT)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                textSize = size.toFloat()
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
            }
            val fm = p.fontMetrics
            c.drawText(ch, w / 2f, w / 2f - (fm.ascent + fm.descent) / 2f, p)
            val px = IntArray(w * w)
            bmp.getPixels(px, 0, w, 0, 0, w, w)
            bmp.recycle()
            val mask = BooleanArray(w * w) { (px[it] ushr 24) > 110 }
            var n = 0
            for (b in mask) if (b) n++
            if (n < 40) return null
            return Triple(w, w, mask)
        } catch (e: Exception) {
            Log.w(TAG, "渲染字形[$ch]失败: ${e.message}")
            return null
        }
    }

    // ═══════════════ 单格笔画提取 ═══════════════

    private class CellInk(val mask: BooleanArray, val inkCount: Int, val redRatio: Float)

    /**
     * 从 warped 盘面上抠出某格的字面笔画。
     *
     * 判据（与 Python probe28/probe31 一致）：
     *   · 暗笔画：灰度显著低于"窗内盘面亮度 82 分位"
     *   · 红笔画：R 明显高于 G/B（红方字）
     *   · 圆形窗口裁掉盘沿（棋子外圈暗环必然在窗外）
     *   · 去掉碰窗的小碎块与"铺满窗又稀疏"的盘沿残弧
     */
    private fun extractCell(px: IntArray, W: Int, H: Int, cx: Float, cy: Float): CellInk? {
        val x0 = max(0, (cx - CELL_HALF).toInt())
        val x1 = min(W, (cx + CELL_HALF).toInt())
        val y0 = max(0, (cy - CELL_HALF).toInt())
        val y1 = min(H, (cy + CELL_HALF).toInt())
        val w = x1 - x0
        val h = y1 - y0
        if (w < 26 || h < 26) return null

        // 1) 灰度 + 背景亮度（82 分位）
        val gray = IntArray(w * h)
        var k = 0
        for (y in y0 until y1) {
            val base = y * W + x0
            for (x in 0 until w) {
                val c = px[base + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[k++] = (r * 77 + g * 151 + b * 28) shr 8
            }
        }
        val sorted = gray.clone()
        sorted.sort()
        val bg = sorted[(sorted.size * 82 / 100).coerceAtMost(sorted.size - 1)].toFloat()
        val thr = bg - max(42f, 0.34f * bg)

        // 2) 墨迹 = 显著暗 或 显著红
        val ink = BooleanArray(w * h)
        k = 0
        for (y in y0 until y1) {
            val base = y * W + x0
            for (x in 0 until w) {
                val c = px[base + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val dark = gray[k] < thr
                val red = (r - max(g, b)) > 60 && r > 110
                ink[k] = dark || red
                k++
            }
        }
        var closed = close2(ink, w, h)

        // 3) 圆形窗口：盘沿暗环在窗外
        val rad = 0.70f * min(w, h) / 2f
        val cxf = w / 2f
        val cyf = h / 2f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cxf
                val dy = y - cyf
                if (dx * dx + dy * dy > rad * rad) closed[y * w + x] = false
            }
        }

        // 4) 连通块过滤：去碎点、去碰圆的盘沿残弧
        val lab = IntArray(w * h)
        val comps = label(closed, w, h, true, lab)
        val keep = BooleanArray(w * h)
        var inkCount = 0
        var redCount = 0
        for (ci in comps.indices) {
            val s = comps[ci]
            if (s.area < 12) continue
            val bw = s.maxX - s.minX + 1
            val bh = s.maxY - s.minY + 1
            val fill = s.area.toFloat() / max(1, bw * bh)
            // 盘沿残弧：外接框几乎铺满窗口却稀疏
            if (bw >= 0.78f * w && bh >= 0.78f * h && fill < 0.32f) continue
            // 碰到圆窗边界：必是盘沿残弧
            val dx = max(abs(s.minX - cxf), abs(s.maxX - cxf))
            val dy = max(abs(s.minY - cyf), abs(s.maxY - cyf))
            if (dx * dx + dy * dy > (rad - 2f) * (rad - 2f)) continue
            for (y in s.minY..s.maxY) {
                for (x in s.minX..s.maxX) {
                    val p = y * w + x
                    if (lab[p] != ci + 1) continue
                    keep[p] = true
                    inkCount++
                    val c = px[(y0 + y) * W + (x0 + x)]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val bb = c and 0xFF
                    if ((r - max(g, bb)) > 60 && r > 110) redCount++
                }
            }
        }
        if (inkCount < 25) return null

        val cn = canon(keep, w, h) ?: return null
        return CellInk(cn, inkCount, redCount.toFloat() / inkCount.toFloat())
    }

    // ═══════════════ 对外入口 ═══════════════

    class Result(val classes: IntArray, val confs: FloatArray, val changes: List<String>)

    /**
     * 用笔画特征复核整盘 90 格。
     *
     * @return null 表示"无改判"（绝大多数情况），调用方照旧用原结论；
     *         否则返回改判后的类号 / 置信度与可读的改判说明。
     */
    fun verify(
        board: Bitmap,
        cells: IntArray,
        confs: FloatArray,
        originX: Float = CELL_ORIGIN_X,
        originY: Float = CELL_ORIGIN_Y,
        colStep: Float = CELL_COL_STEP,
        rowStep: Float = CELL_ROW_STEP
    ): Result? {
        if (!ENABLED) return null
        if (cells.size != 90 || confs.size != 90) return null
        try {
            val tpl = tpl()
            if (tpl.isEmpty()) return null

            val W = board.width
            val H = board.height
            val px = IntArray(W * H)
            board.getPixels(px, 0, W, 0, 0, W, H)

            val t0 = System.currentTimeMillis()
            val cnt = IntArray(16)
            for (c in cells) if (c in 2..15) cnt[c]++

            class Adj(val i: Int, val from: Int, val to: Int, val margin: Float, val desc: String)

            val adjs = ArrayList<Adj>()
            var probed = 0
            for (i in 0 until 90) {
                val cls = cells[i]
                if (cls < 2 || cls > 15) continue
                if (!MOBILE[cls]) continue          // 士/象/将帅 有固定点位，本层不碰
                val row = i / 9
                val col = i % 9
                val ci = extractCell(px, W, H, originX + col * colStep, originY + row * rowStep) ?: continue
                probed++
                val q = features(clean(ci.mask))

                // 各形类的字形分（同一形类取"变体×字体"的最高分，规避字体差异）
                val shapeScore = FloatArray(SHAPE_COUNT) { -9f }
                for ((sh, tf) in tpl) {
                    val s = score(q, tf)
                    if (s > shapeScore[sh]) shapeScore[sh] = s
                }
                val modelShape = CLASS_TO_SHAPE[cls]
                if (modelShape < 0) continue
                var bestShape = 0
                for (s in 1 until SHAPE_COUNT) if (shapeScore[s] > shapeScore[bestShape]) bestShape = s
                val best = shapeScore[bestShape]
                val self = shapeScore[modelShape]
                val feat = "孔=%d 块=%d 点=%d 上点=%d 红占比=%.2f".format(
                    q.holes, q.comps, q.dots, q.topDot, ci.redRatio
                )

                if (bestShape == modelShape) {
                    // ── 形对了但颜色可能配错：用墨色重新分配颜色 ──
                    if (best < MIN_COLOR_ABS) continue
                    val decisive = ci.redRatio >= RED_RATIO_RED || ci.redRatio <= RED_RATIO_BLACK
                    if (!decisive) continue
                    val target = resolveClass(bestShape, ci.redRatio, cls) ?: continue
                    if (target == cls) continue
                    adjs.add(Adj(i, cls, target, 0.05f, "仅配色($feat)"))
                } else {
                    // ── 形不对：需要笔画证据明显压过模型结论 ──
                    if (best < MIN_SHAPE_ABS) continue
                    val margin = best - self
                    val need = if (confs[i] >= CONF_STRONG) MARGIN * STRONG_FACTOR else MARGIN
                    if (margin < need) continue
                    val target = resolveClass(bestShape, ci.redRatio, cls) ?: continue
                    if (target == cls) continue
                    if (!MOBILE[target]) continue
                    adjs.add(Adj(i, cls, target, margin, "形差=%.3f $feat 判为%s".format(
                        margin, feat, glyphDesc(bestShape)
                    )))
                }
            }

            if (adjs.isEmpty()) {
                Log.d(TAG, "笔画校验：探${probed}格，无改判（耗时${System.currentTimeMillis() - t0}ms）")
                return null
            }

            // 证据强（分差大）的先改；目标类已满则否决（不被"改出一个超限盘面"带偏）
            adjs.sortByDescending { it.margin }
            val outCls = cells.copyOf()
            val outConf = confs.copyOf()
            val changes = ArrayList<String>()
            var applied = 0
            for (a in adjs) {
                val pos = "(${a.i / 9},${a.i % 9})"
                if (a.to in 2..15 && cnt[a.to] >= CLASS_MAX[a.to]) {
                    changes.add("$pos ${CLASS_NAME[a.from]}→${CLASS_NAME[a.to]} 否决(目标类已满) [${a.desc}]")
                    continue
                }
                outCls[a.i] = a.to
                outConf[a.i] = 0.90f
                if (a.from in 2..15) cnt[a.from]--
                if (a.to in 2..15) cnt[a.to]++
                applied++
                changes.add("$pos ${CLASS_NAME[a.from]}→${CLASS_NAME[a.to]} [${a.desc}]")
            }
            Log.i(TAG, "★ 笔画校验：探${probed}格，采纳改判${applied}处（${System.currentTimeMillis() - t0}ms） " +
                    changes.joinToString(", "))
            if (applied == 0) return null
            return Result(outCls, outConf, changes)
        } catch (e: Exception) {
            Log.e(TAG, "笔画校验失败，回退原结论: ${e.message}", e)
            return null
        }
    }

    /** 由"形类 + 墨色"定出布局类号；颜色不明时沿用模型所在颜色家族 */
    private fun resolveClass(shape: Int, redRatio: Float, modelCls: Int): Int? {
        val r = SHAPE_RED_CLASS[shape]
        val b = SHAPE_BLACK_CLASS[shape]
        return when {
            r > 0 && b < 0 -> r
            b > 0 && r < 0 -> b
            redRatio >= RED_RATIO_RED && r > 0 -> r
            redRatio <= RED_RATIO_BLACK && b > 0 -> b
            else -> {
                // 墨色不明：沿用模型的红/黑家族，避免"颜色跟着乱翻"
                if (modelCls <= 8) (if (r > 0) r else b) else (if (b > 0) b else r)
            }
        }
    }

    private fun glyphDesc(shape: Int): String = when (shape) {
        SHAPE_ROOK -> "车(中段有田字)"
        SHAPE_CANNON -> "炮(上包有闭口)"
        SHAPE_KNIGHT -> "马(四点+马头)"
        SHAPE_PAWN_BLACK -> "卒(一点一横+两个人)"
        SHAPE_PAWN_RED -> "兵(上六下八)"
        SHAPE_ADVISOR_BLACK -> "士"
        SHAPE_ADVISOR_RED -> "仕"
        SHAPE_ELEPHANT_BLACK -> "象"
        SHAPE_ELEPHANT_RED -> "相"
        SHAPE_KING_BLACK -> "将"
        else -> "帅"
    }

    // ═══════════════ 笔画特征抽取（与 probe25 原型一致）═══════════════

    private class Comp(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val area: Int)

    /** 连通块标记；conn8=false 时用 4 邻域（数孔洞要用 4 邻域） */
    private fun label(mask: BooleanArray, w: Int, h: Int, conn8: Boolean, labOut: IntArray): List<Comp> {
        java.util.Arrays.fill(labOut, 0)
        val comps = ArrayList<Comp>()
        val stack = IntArray(mask.size)
        var next = 0
        for (s in mask.indices) {
            if (!mask[s] || labOut[s] != 0) continue
            next++
            var sp = 0
            stack[sp++] = s
            labOut[s] = next
            var minX = w; var minY = h; var maxX = -1; var maxY = -1; var area = 0
            while (sp > 0) {
                val p = stack[--sp]
                val py = p / w
                val pxx = p - py * w
                if (pxx < minX) minX = pxx
                if (pxx > maxX) maxX = pxx
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                area++
                for (dy in -1..1) {
                    val ny = py + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (!conn8 && dx != 0 && dy != 0) continue
                        val nx = pxx + dx
                        if (nx < 0 || nx >= w) continue
                        val q = ny * w + nx
                        if (mask[q] && labOut[q] == 0) {
                            labOut[q] = next
                            stack[sp++] = q
                        }
                    }
                }
            }
            comps.add(Comp(minX, minY, maxX, maxY, area))
        }
        return comps
    }

    /** 孔洞数（不与字面外框相通的封闭区域数）——車的"田"、炮的"包"都靠它现形 */
    private fun holesOf(mask: BooleanArray): Int {
        val inv = BooleanArray(mask.size) { !mask[it] }
        val lab = IntArray(mask.size)
        val comps = label(inv, N, N, false, lab)
        var n = 0
        for (c in comps) {
            if (c.minX > 0 && c.minY > 0 && c.maxX < N - 1 && c.maxY < N - 1) n++
        }
        return n
    }

    private fun features(mask: BooleanArray): Feature {
        val lab = IntArray(mask.size)
        val comps = label(mask, N, N, true, lab)
        var tot = 0
        for (c in comps) tot += c.area
        if (tot == 0) tot = 1
        var compCnt = 0
        var dots = 0
        var topDot = 0
        for (c in comps) {
            if (c.area < 0.02f * tot) continue
            compCnt++
            val cw = c.maxX - c.minX + 1
            val chh = c.maxY - c.minY + 1
            if (chh <= 0.22f * N && cw.toFloat() / max(1, chh) in 0.5f..2.0f && c.area < 0.10f * tot) {
                dots++
                val ccx = (c.minX + c.maxX) / 2f
                if (c.minY < 0.30f * N && abs(ccx - N / 2f) < 0.25f * N) topDot = 1
            }
        }
        // 上/中/下三段各自的孔洞数：車的田在中段 → 与"卒/兵"一眼分开
        val seg = IntArray(3)
        for (s in 0 until 3) {
            val band = BooleanArray(N * N)
            for (y in s * N / 3 until (s + 1) * N / 3) {
                System.arraycopy(mask, y * N, band, y * N, N)
            }
            seg[s] = holesOf(band)
        }
        return Feature(holesOf(mask), compCnt, dots, topDot, seg, density(mask))
    }

    /** 12×12 粗粒度墨迹密度，归一化成概率分布（跨字体、跨笔画粗细稳定） */
    private fun density(mask: BooleanArray): FloatArray {
        val d = FloatArray(G * G)
        for (gy in 0 until G) {
            for (gx in 0 until G) {
                var c = 0
                var t = 0
                for (y in gy * N / G until (gy + 1) * N / G) {
                    for (x in gx * N / G until (gx + 1) * N / G) {
                        t++
                        if (mask[y * N + x]) c++
                    }
                }
                d[gy * G + gx] = if (t > 0) c.toFloat() / t else 0f
            }
        }
        var s = 0f
        for (v in d) s += v
        if (s > 1e-6f) for (i in d.indices) d[i] /= s
        return d
    }

    /** 归一化互相关（带 ±2 格平移搜索，容忍对齐误差） */
    private fun densSim(a: FloatArray, b: FloatArray): Float {
        var best = 0f
        for (dy in -2..2) {
            for (dx in -2..2) {
                var num = 0f
                var aa = 0f
                var bb = 0f
                for (gy in 0 until G) {
                    val ty = (gy + dy + G) % G
                    for (gx in 0 until G) {
                        val tx = (gx + dx + G) % G
                        val av = a[gy * G + gx]
                        val bv = b[ty * G + tx]
                        num += av * bv
                        aa += av * av
                        bb += bv * bv
                    }
                }
                val v = num / (sqrt(aa * bb) + 1e-6f)
                if (v > best) best = v
            }
        }
        return best
    }

    /** 结构 + 密度 组合度量，越大越像（与 probe25.score 一致） */
    private fun score(q: Feature, t: Feature): Float {
        var s = 0f
        s -= 0.09f * abs(q.holes - t.holes)
        s -= 0.05f * abs(q.comps - t.comps)
        s -= 0.07f * abs(q.dots - t.dots)
        s -= 0.05f * abs(q.topDot - t.topDot)
        for (k in 0 until 3) s -= 0.03f * abs(q.seg[k] - t.seg[k])
        s += densSim(q.dens, t.dens)
        return s
    }

    // ═══════════════ 形态学 / 归一化 ═══════════════

    private fun dilate2(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val o = BooleanArray(m.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * w + x
                if (m[p] || (x + 1 < w && m[p + 1]) || (y + 1 < h && m[p + w]) ||
                    (x + 1 < w && y + 1 < h && m[p + w + 1])
                ) o[p] = true
            }
        }
        return o
    }

    private fun erode2(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val o = BooleanArray(m.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * w + x
                if (!m[p]) continue
                if (x + 1 < w && !m[p + 1]) continue
                if (y + 1 < h && !m[p + w]) continue
                if (x + 1 < w && y + 1 < h && !m[p + w + 1]) continue
                o[p] = true
            }
        }
        return o
    }

    private fun close2(m: BooleanArray, w: Int, h: Int) = erode2(dilate2(m, w, h), w, h)
    private fun open2(m: BooleanArray, w: Int, h: Int) = dilate2(erode2(m, w, h), w, h)
    private fun clean(m: BooleanArray) = open2(close2(m, N, N), N, N)

    /** 按墨迹外接框等比例归一化到 N×N 并居中 */
    private fun canon(mask: BooleanArray, w: Int, h: Int): BooleanArray? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                if (!mask[base + x]) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        if (bw < 4 || bh < 4) return null
        val sc = min(N.toFloat() / bw, N.toFloat() / bh)
        val nw = max(1, (bw * sc).roundToInt().coerceAtMost(N))
        val nh = max(1, (bh * sc).roundToInt().coerceAtMost(N))
        val ox = (N - nw) / 2
        val oy = (N - nh) / 2
        val out = BooleanArray(N * N)
        for (yy in 0 until nh) {
            val sy0 = minY + (yy * bh) / nh
            val sy1 = min(maxY + 1, max(sy0 + 1, minY + ((yy + 1) * bh) / nh))
            for (xx in 0 until nw) {
                val sx0 = minX + (xx * bw) / nw
                val sx1 = min(maxX + 1, max(sx0 + 1, minX + ((xx + 1) * bw) / nw))
                var c = 0
                var t = 0
                for (sy in sy0 until sy1) {
                    val base = sy * w
                    for (sx in sx0 until sx1) {
                        t++
                        if (mask[base + sx]) c++
                    }
                }
                if (t > 0 && c * 2 >= t) out[(oy + yy) * N + (ox + xx)] = true
            }
        }
        return out
    }
}