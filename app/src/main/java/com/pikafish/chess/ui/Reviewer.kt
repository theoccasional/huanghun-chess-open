package com.pikafish.chess.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pikafish.chess.engine.JniEngine
import com.pikafish.chess.engine.MoveExplainer
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.PieceColor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ★★★ 复盘评估（ReviewAnalyzer）★★★
 *
 * 用途：对当前棋局"主变"（不盘变招）的指定回合区间做逐点评估，产出：
 *  1. 每个局面的评分（换算到展示刻度：红方优势为正、黑方优势为负、0=均势，±1000 显示窗口封顶）
 *  2. 找出"滑铁卢"错着：某手使走棋方自身评分明显下跌时，给出正确（引擎首选）着法
 *
 * 汇率化原则：改由与「分析开/关」完全一致的引擎原始分 1:1 画曲线——绝不做任何放大/换率。
 * 引擎照常按它自己的 centipawn 评分思考；这里直接把引擎报的原始分按当事方换算成红方视角取点：
 *  红方轮到报正分 → 红优(正半轴)；黑方轮到报的正分 → 黑优(负半轴)。超 ±1000 由曲线贴边。
 */
class ReviewAnalyzer(private val context: Context) {

    /** 最近一次失败的具体原因（供 UI 提示真实原因，不再笼统报"引擎未能就绪"） */
    @Volatile var lastError: String = ""

    companion object {
        private const val TAG = "ReviewAnalyzer"

        /** 1:1 立场换算后的安全上限：连杀分已统一 29999 封顶，这里同值兜底防溢出 */
        const val MAX_DISPLAY = JniEngine.MATE_CEIL

        /** 判定"这一手"为漏着的展示分阈值（坐标轴分值，0 基线锚定）。
         *  以 0 为基准：若本方存在一步(引擎最佳着)可让评分向本方半轴再推进 ≥ 25 分，
         *  而实际着法未达到，即判漏着；与曲线 0 基线同源，展示分 1:1 不缩放。 */
        const val BLUNDER_VIEW = 25

        /** 复盘评估深度（层）：固定 18 层（用户钦定：不设 20/24 选择，直接 18 层，稳而准）。
         *  evalAt 用 analyzeReview(depth=18) 一次算到底，不再走 movetime，评分精确、不受时长抖动影响。 */
        const val EVAL_DEPTH = 18
        /** 复盘评分评级时长：与「分析开/关」(searchBestMove timeMs=2200) 同一口径，按时长逐步加深取满级分 */
        const val REVIEW_STEP_MS = 2200L
        private const val EVAL_BUDGET_MS = 60000L

        /** ★ 复盘单点预算（毫秒）：压缩到 1.4s——曲线看的是相对走势，无需每点满额硬算。
         *  一般中局 18 层 1.4s 内即可交卷；复杂残局到点也有 `go depth 18` 层约束兜底收招，
         *  评分仍稳、但整条曲线成倍提速。 */
        const val REVIEW_POINT_MS = 1400L

        /** ★ 串行上限（点数）：≤ 此值直接用主引擎串行逐点（引擎本身多线程，秒回，不折腾）；
         *  > 此值一律走「分段并行」，把局面拆给多台引擎并发评估——彻底告别"单线程死等"。 */
        const val SERIAL_UPTO = 4

        /** ★ 设备逻辑核心数（封顶 8）。复盘分段并行即按此分核，绝不超载。 */
        private fun cores(): Int =
            (Runtime.getRuntime().availableProcessors()).coerceIn(1, 8)

        /** ★ 权威回合计法：与主界面 updateStepInfo「当前回合」完全一致（ply=已走手数）。
         *  ply=1(红第1手)→1、ply=2(黑第1手)→1、ply=3(红第2手)→2……
         *  复盘里所有回合号都从这里出，保证"棋盘/曲线/文字"三者同源、以棋盘为准。 */
        fun roundOf(plyAfter: Int): Int = (plyAfter + 1) / 2
    }

    /** 单个评估点的展示结果 */
    data class ReviewPoint(
        val ply: Int,              // 全局半回合索引（P[ply] 指的是走了 ply 手后的局面）
        val round: Int,            // 回合号
        val color: PieceColor,     // 该局面轮到方
        val viewScore: Int,        // 引擎1:1原始分（红方视角，正=红优/负=黑优，不缩放）
        val moveChinese: String,   // 进入该局面的那手中文记谱（开局则为"开局"）
        val bestMoveChinese: String // 引擎首选着（中文记谱）
    )

    /** 错着 / 滑铁卢 */
    data class ReviewBlunder(
        val ply: Int,              // 该错着的全局半回合下标（movesUci[ply]，跳转定位用）
        val round: Int,
        val color: PieceColor,
        val movePlayedChinese: String,
        val bestMoveChinese: String,
        val bestPv: String,
        val lossViewScore: Int,    // 本方半轴较应得少推进（展示分绝对值，0 基线锚定）
        val viewBefore: Int,       // 走前红方视角展示分
        val viewAfter: Int,        // 走后红方视角展示分
        val note: String           // 一句话说明
    )

    /** 持续走下坡趋势：某方评分连续多步(每步幅度小)向对方半轴单调滑落，标注趋势起点 */
    data class ReviewSlip(
        val ply: Int,              // 趋势起点的全局半回合下标
        val round: Int,
        val color: PieceColor,     // 走下坡的一方
        val startView: Int,        // 起点红方视角展示分
        val endView: Int,          // 终点红方视角展示分
        val note: String
    )

    /** 复盘结果 */
    data class ReviewResult(
        val points: List<ReviewPoint>,
        val blunders: List<ReviewBlunder>,
        val slips: List<ReviewSlip>,
        val curve: IntArray,          // 展示分序列（对应 points）
        val blunderCurveIdx: List<Int>, // curve 中标记"错着发生在该局面"的下标
        val slipCurveIdx: List<Int>    // curve 中标记"开始走下坡"的下标
    )

    /** 单点评估结果统一形态（类级私有嵌套类，供 run 内部与 arrayNullsEv 共用，勿再定义局部类） */
    private data class Ev(val score: Int, val best: String, val pv: String)

    fun run(
        initialFen: String,
        movesUci: List<String>,
        startRound: Int,
        endRound: Int,
        onProgress: (done: Int, total: Int, label: String) -> Unit,
        onDone: (ReviewResult?) -> Unit,
        reuseEngine: JniEngine? = null   // ★ 复用调用方已就绪的引擎（主对局引擎），不再另起一台
    ) {
        Thread {
            val main = Handler(Looper.getMainLooper())
            val postProgress = { done: Int, total: Int, label: String -> main.post { onProgress(done, total, label) } }
            val postDone = { r: ReviewResult? -> main.post { onDone(r) } }
            // ★★★ 复盘提速（用户核心诉求）：
            //   1) 单点预算压缩（REVIEW_POINT_MS=1.4s）——曲线看相对走势，无需每点满额 8.3s 硬算。
            //   2) 复用主引擎时：引擎本身已是多线程，串行逐点即可，绝不另起并实例抢对局引擎。
            //   3) 自起时：用「分段并行 + 每段多线程」——段数≤4、每段线程数=核心数/段数，绝不超载。
            //      （每段一台引擎处理一段回合，彼此独立并行；不靠"单线程死等"慢吞吞。）
            val reuse = reuseEngine
            var ownedEngines = ArrayList<JniEngine>()   // 自起的引擎组（并行段各一台；复用的主引擎不进此组，避免被误 shutdown）
            try {

                // ── 重建全部局面 P[0..N] ──
                val boards = mutableListOf<Board>()
                val base = Board()
                base.loadFromFen(initialFen)
                boards.add(base)
                for (uci in movesUci) {
                    val b = boards.last().clone()
                    val m = Move.fromUci(uci, b) ?: break
                    if (!b.makeMove(m)) break
                    boards.add(b)
                }
                val N = boards.size - 1
                if (N <= 0) { lastError = "无可评估的局面（谱面重建失败）"; postDone(null); return@Thread }

                val plyStart = (startRound - 1) * 2
                val plyEnd = endRound * 2 - 1
                val evalFrom = plyStart.coerceIn(0, N)
                val evalTo = (plyEnd + 1).coerceIn(0, N)
                if (evalFrom > N || evalTo < evalFrom) { lastError = "评估区间越界"; postDone(null); return@Thread }
                val total = evalTo - evalFrom + 1
                val explainer = MoveExplainer
                val pointBudgetMs = REVIEW_POINT_MS

                // 单点评估结果统一形态（类级私有嵌套类，供 run 内部与 arrayNullsEv 共用）
                // (Ev 见类级私有嵌套 data class，勿在 run 内重复定义局部类)

                // ★★★ 单点评估辅助：往引擎请求一个局面的评分（阻塞当前线程直到返回/超时）
                fun evalOne(eng: JniEngine, idx: Int): Ev? {
                    val latch = CountDownLatch(1)
                    var box: Ev? = null
                    eng.analyzeReview(boards[idx], depth = EVAL_DEPTH, timeMs = pointBudgetMs.toInt()) { best, score, _, pv ->
                        box = Ev(score, best, pv); latch.countDown()
                    }
                    if (!latch.await(EVAL_BUDGET_MS, TimeUnit.MILLISECONDS)) return null
                    return box
                }

                // ★★★ 汇总评估结果 → ReviewResult（points/curve/漏着/下滑 全在此完成）
                fun buildResult(
                    evals: List<Ev?>,
                    done: (ReviewResult?) -> Unit
                ) {
                    try {
                        val totalN = evalTo - evalFrom + 1
                        fun viewOf(stmScore: Int, stm: PieceColor): Int {
                            val redRaw = if (stm == PieceColor.RED) stmScore else -stmScore
                            return redRaw.coerceIn(-MAX_DISPLAY, MAX_DISPLAY)
                        }
                        val points = ArrayList<ReviewPoint>(totalN)
                        val rawRed = IntArray(totalN)
                        for (i in evalFrom..evalTo) {
                            val ev = evals.getOrNull(i - evalFrom)
                            val stm = boards[i].currentPlayer
                            val toolTip = if (i - 1 >= 0) explainer.uciToChineseNotation(movesUci[i - 1], boards[i - 1]) else "开局"
                            val bestCh = if (ev != null && ev.best.isNotEmpty())
                                explainer.uciToChineseNotation(ev.best, boards[i]) else "—"
                            val stmScore = ev?.score ?: 0
                            var v = viewOf(stmScore, stm)
                            if (i == N && boards[N].isCheckmate(boards[N].currentPlayer)) {
                                val winner = boards[N].currentPlayer.opponent()
                                v = if (winner == PieceColor.RED) JniEngine.MATE_CEIL else -JniEngine.MATE_CEIL
                            }
                            rawRed[i - evalFrom] = v
                            points.add(ReviewPoint(i, roundOf(i), stm, v, toolTip, bestCh))
                        }
                        val curve = IntArray(totalN)
                        val redSeq = IntArray(totalN)
                        var lockSide = 0; var lockFloor = 0
                        for (k in 0 until totalN) {
                            val rv = rawRed[k]
                            if (lockSide == 0) {
                                if (kotlin.math.abs(rv) >= JniEngine.MATE_THRESHOLD) { lockSide = if (rv > 0) 1 else -1; lockFloor = kotlin.math.abs(rv) }
                                curve[k] = rv; redSeq[k] = rv
                            } else {
                                val sameSign = (rv >= 0) == (lockSide > 0)
                                if (!sameSign && kotlin.math.abs(rv) >= BLUNDER_VIEW) { lockSide = 0; curve[k] = rv; redSeq[k] = rv }
                                else { val dv = lockSide * lockFloor; curve[k] = dv; redSeq[k] = dv }
                            }
                        }
                        for (k in 0 until totalN) points.set(k, points[k].copy(viewScore = redSeq[k]))
                        val blunders = ArrayList<ReviewBlunder>(); val blunderCurveIdx = ArrayList<Int>()
                        for (k in evalFrom until evalTo) {
                            val evBefore = evals.getOrNull(k - evalFrom) ?: continue
                            val evAfter = evals.getOrNull(k + 1 - evalFrom) ?: continue
                            val mover = boards[k].currentPlayer
                            val vBefore = redSeq[k - evalFrom]; val vAfter = redSeq[k + 1 - evalFrom]
                            val ownBefore = if (mover == PieceColor.RED) vBefore else -vBefore
                            val ownAfter = if (mover == PieceColor.RED) vAfter else -vAfter
                            val loss = ownBefore - ownAfter
                            if (loss >= BLUNDER_VIEW) {
                                val played = movesUci.getOrNull(k) ?: ""
                                if (evBefore.best.isNotEmpty() && played == evBefore.best) continue
                                val moveCh = if (played.isNotEmpty()) explainer.uciToChineseNotation(played, boards[k]) else "?"
                                val bestCh = if (evBefore.best.isNotEmpty()) explainer.uciToChineseNotation(evBefore.best, boards[k]) else "?"
                                val sideName = if (mover == PieceColor.RED) "红方" else "黑方"
                                val advSide = when { vAfter > 0 -> "红方"; vAfter < 0 -> "黑方"; else -> "均势" }
                                val note = buildString {
                                    append(sideName).append("这手«").append(moveCh)
                                        .append("»在本方半轴较应得少推进约 ").append(loss).append(" 分")
                                        .append("（此后局面已偏").append(advSide)
                                        .append("，正解宜走“").append(bestCh).append("”）")
                                }
                                blunders.add(ReviewBlunder(
                                    k, points.getOrNull(k + 1 - evalFrom)?.round ?: roundOf(k + 1),
                                    mover, moveCh, bestCh, evBefore.pv,
                                    loss, vBefore, vAfter, note))
                                blunderCurveIdx.add(k + 1 - evalFrom)
                            }
                        }
                        val slips = ArrayList<ReviewSlip>(); val slipCurveIdx = ArrayList<Int>()
                        val n = redSeq.size; var s = 0
                        while (s < n - 1) {
                            val d0 = redSeq[s + 1].compareTo(redSeq[s])
                            if (d0 == 0 || kotlin.math.abs(redSeq[s + 1] - redSeq[s]) >= BLUNDER_VIEW) { s++; continue }
                            var e = s
                            while (e + 1 < n) {
                                val d = redSeq[e + 1].compareTo(redSeq[e])
                                if (d != d0 || d == 0 || kotlin.math.abs(redSeq[e + 1] - redSeq[e]) >= BLUNDER_VIEW) break
                                e++
                            }
                            if (e - s >= 2) {
                                val startView = redSeq[s]; val endView = redSeq[e]
                                val side = if (d0 > 0) PieceColor.BLACK else PieceColor.RED
                                val sideCh = if (side == PieceColor.RED) "红方" else "黑方"
                                val startPly = evalFrom + s
                                val round = points.getOrNull(startPly - evalFrom)?.round ?: roundOf(startPly)
                                val note = buildString {
                                    append(sideCh).append("从第 ").append(round).append(" 回合起连续 ")
                                        .append(e - s).append(" 步向对方半轴单调滑落（红方视角 ")
                                        .append(startView).append(" → ").append(endView).append("）")
                                }
                                slips.add(ReviewSlip(startPly, round, side, startView, endView, note))
                                slipCurveIdx.add(s); s = e + 1
                            } else { s = e + 1 }
                        }
                        done(ReviewResult(points, blunders, slips, curve, blunderCurveIdx, slipCurveIdx))
                    } catch (e: Throwable) {
                        Log.e(TAG, "复盘汇总失败", e); lastError = e.message ?: "复盘汇总异常"; done(null)
                    }
                }

                // ★★★ 评估调度核心（复盘提速：能并行就并行）★★★
                // 小范围（≤SERIAL_UPTO 点）串行逐点即可——引擎本身多线程、几十点秒回；
                // 大范围一律走「分段并行」：把 total 个局面拆给 ≤4 台引擎并发评估（每台开 核心数/段数 线程），
                // 正是"把 134 个局面拆给多线程并批、进度按段推进"的诉求，绝不全程单线程死等。
                val useReuse = reuse != null && reuse.isEngineReady() && total <= SERIAL_UPTO
                if (useReuse) {
                    // ── 小范围：复用主对局引擎串行逐点（引擎已多线程），每点 1.4s 预算 ──
                    val evals = ArrayList<Ev?>(total)
                    for (i in evalFrom..evalTo) {
                        evals.add(evalOne(reuse!!, i))
                        postProgress(i - evalFrom + 1, total, "评估第 ${roundOf(i)} 回合局面 ${i - evalFrom + 1}/$total…")
                    }
                    buildResult(evals) { r -> postDone(r) }
                } else {
                    // ── 大范围：分段并行。段数 = min(4, 点数)，每段线程数 = 核心数/段数 ──
                    //    已就绪的主引擎顺手作为第 0 段（省一次启动、不浪费）；只有自起的引擎才进 ownedEngines（复用引擎绝不 shutdown）。
                    val c = cores()
                    val segNum = minOf(4, total).coerceIn(1, c)
                    val segThreads = (c / segNum).coerceAtLeast(1)
                    val segs = ArrayList<Pair<Int, JniEngine>>()   // (段起点, 该段引擎)
                    for (s in 0 until segNum) {
                        val isReuseSeg = s == 0 && reuse != null && reuse.isEngineReady()
                        val e: JniEngine = if (isReuseSeg) reuse!! else {
                            val ne = JniEngine(context)
                            ownedEngines.add(ne)
                            try { ne.hashSize = 128 } catch (_: Exception) {}
                            try { ne.threadCount = segThreads } catch (_: Exception) {}
                            val err = ne.locateEngine() ?: ne.copyCurrentNNue() ?: ne.startEngine() ?: ne.liteInit()
                            if (err != null) { Log.e(TAG, "复盘自起段引擎失败: $err") }
                            ne
                        }
                        if (!isReuseSeg) { try { e.threadCount = segThreads } catch (_: Exception) {} }
                        segs.add(s to e)
                    }
                    val evals = arrayNullsEv(total)
                    val doneLatch = CountDownLatch(segNum)
                    // 每段一个工作线程：段内逐点（共享该段引擎），段间并行互不干扰
                    for ((s, eng) in segs) {
                        val startIdx = evalFrom + s * total / segNum
                        val endIdx = evalFrom + (s + 1) * total / segNum
                        val t = Thread {
                            try {
                                for (i in startIdx until endIdx.coerceAtMost(evalTo + 1)) {
                                    if (i > evalTo) break
                                    evals[i - evalFrom] = evalOne(eng, i)
                                }
                            } catch (e: Throwable) { Log.e(TAG, "复盘分段worker异常: ${e.message}") }
                            finally {
                                doneLatch.countDown()
                                val d = doneLatch.count.toInt()
                                main.post { postProgress(segNum - d, segNum, "并行评估推进中…") }
                            }
                        }
                        t.name = "review-seg-$s"
                        t.start()
                    }
                    doneLatch.await()
                    buildResult(evals) { r -> postDone(r) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "复盘分析失败", e)
                lastError = e.message ?: "复盘分析异常"
                postDone(null)
            } finally {
                // ★ 只关自起的引擎组；复用的主引擎绝不能 shutdown（还要继续给对局/分析用）
                try { ownedEngines.forEach { it.shutdown() } } catch (_: Exception) {}
            }
        }.apply {
            name = "review-analyzer"
        }.start()
    }

    /** 生成 `size` 长度的可空分值数组（统一形态，供并行段回填） */
    private fun arrayNullsEv(size: Int): ArrayList<ReviewAnalyzer.Ev?> {
        val arr = ArrayList<ReviewAnalyzer.Ev?>(size)
        for (i in 0 until size) arr.add(null)
        return arr
    }
}

/**
 * 复盘评分曲线视图：横轴回合（每节点一个可点圆点）、纵轴展示分(红正黑负、0 基准线)。
 * ★ 自适应坐标系：窗口=本段最大非杀棋分向上取整到百（下限 200）；刻度线/标签与曲线
 *   共用同一 fracPos(值,窗口)——曲线在某分值的点必定落在标着该分值的刻度位上，精准全关联。
 *   平方根映射让小分也可见波动；杀棋/锁定胜势分贴边并画溢出箭头。
 * 曲线用贝塞尔平滑；点击节点弹小棋盘、点击空白收起；漏着=红环、开始走下坡=琥珀三角。
 */
class CurveView(context: Context) : android.view.View(context) {

    companion object {
        /** 满刻度：±29999（对标天天象棋"30000-1"封顶），红胜/黑胜触顶即可见。 */
        const val MAX_SCORE = JniEngine.MATE_CEIL

        /** ★ 显示窗口下限：±200。复盘曲线按"本段数据"自适应取窗——
         *  窗口 = 本段非杀棋分最大绝对值向上取整到百（下限 200）。
         *  开局小分（±20~±60）在 200 窗里也能明显看出波动；大优段（如 300+）窗口放大到 300/400，
         *  曲线 300+ 便高高越过 200 线、逼近顶——刻度与曲线永远一一对应，绝不错位。 */
        const val MIN_WINDOW = 200

        /** ★ 每段自适应坐标窗（刻度与曲线共用，值→位置严格同函数）：
         *  杀棋/锁定胜势分（≥MATE_THRESHOLD）不参与取窗，统一贴边 + 溢出箭头；其余分取最大绝对值。 */
        fun windowOf(values: IntArray): Int {
            var maxAbs = 0
            for (v in values) {
                val a = if (v < 0) -v else v
                if (a < JniEngine.MATE_THRESHOLD && a > maxAbs) maxAbs = a
            }
            return if (maxAbs <= MIN_WINDOW) MIN_WINDOW else ((maxAbs + 99) / 100) * 100
        }

        /** 幅度→纵向比例：fracPos(mag, w)=sqrt(mag/w)，0→0、w/4→0.5、w/2→0.707、w→1.0。
         *  小分也可见波动；超窗（杀棋/锁定胜势）一律 1.0 贴边 + 溢出箭头。
         *  刻度线/标签与曲线用同一函数：曲线在某分值的点必定落在标着该分值的刻度位上。 */
        fun fracPos(mag: Int, window: Int): Float {
            val m = mag.coerceIn(0, window)
            return kotlin.math.sqrt(m.toFloat() / window.toFloat())
        }
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF8A50.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2.4f); strokeCap = Paint.Cap.ROUND
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB08A5A.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.6f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33444444.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val majorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x524A4A4A.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC9E9E9E.toInt() }
    private val dotRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val dotBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF272B30.toInt() }
    private val ringBlunder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.0f); color = 0xFFE53935.toInt()
    }
    private val ringSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.8f); color = 0xFF00E5FF.toInt()
    }
    // 游标竖线：下方贴较宽的白色半透明晕衬，保证深/浅纸张上都看得清
    private val cursorHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(5f); color = 0x66FFFFFF.toInt()
    }
    private val cursorLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.2f); color = 0xFFD32F2F.toInt()
    }
    private val amberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB300.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF999999.toInt(); textSize = sp(10f) }
    private val redText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE57373.toInt(); textSize = sp(10f) }
    private val blackText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF90A4AE.toInt(); textSize = sp(10f) }
    private val zeroText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB08A5A.toInt(); textSize = sp(10f) }

    var values: IntArray = IntArray(0)
    var blunderIdx: List<Int> = emptyList()
    var slipIdx: List<Int> = emptyList()
    var labelStartRound: Int = 1
    var labelEndRound: Int = 1
    var highlightIdx: Int = -1
    /** 与 values 对齐的轮到方落点色：0=中性，1=红，2=黑 */
    var nodeColorArr: IntArray? = null
    var onNodeTap: ((localIdx: Int, x: Float, y: Float) -> Unit)? = null
    var onBlankTap: (() -> Unit)? = null
    /** 当前游标对准的节点下标（-1=未定位） */
    var cursorIdx: Int = -1
    /** 拖动游标实时回调（棋盘跟随），参数为当前对准的节点下标 */
    var onCursorMove: ((localIdx: Int) -> Unit)? = null
    private var activeIdx = -1
    private var downX = 0f
    private var downY = 0f
    private var dragged = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty() || width <= 0 || height <= 0) return
        val padL = dp(12).toFloat()
        val padR = dp(12).toFloat()
        val padT = dp(10).toFloat()
        val padB = dp(22).toFloat()
        val w = width - padL - padR
        val h = height - padT - padB
        val cy = padT + h / 2f
        val half = h / 2f
        val stepX = if (values.size > 1) w / (values.size - 1).toFloat() else 0f

        fun x(i: Int): Float = if (values.size > 1) padL + i * stepX else padL
        // ★ 自适应平方根刻度：window 由本段数据取（最大非杀棋分向上取整到百，下限 200）。
        //   刻度线/标签与曲线共用同一 fracPos(值, window)——曲线在某分值的点
        //   必定落在标着该分值的刻度位上；小分也见波动；杀棋/锁定胜势贴边 + 溢出箭头。
        val window = windowOf(values)
        fun yOf(v: Int): Float {
            val up = if (v >= 0) fracPos(v, window) else -fracPos(-v, window)
            return cy - up * half
        }
        val cxs = FloatArray(values.size) { x(it) }
        val yys = FloatArray(values.size) { yOf(values[it]) }

        // 刻度网格线：¼ / ½ / ¾ 窗口（正负两半轴各一条，与曲线同一 fracPos 精确落位）
        val q1 = window / 4; val q2 = window / 2; val q3 = window * 3 / 4
        val f1p = cy - fracPos(q1, window) * half
        val f2p = cy - fracPos(q2, window) * half
        val f3p = cy - fracPos(q3, window) * half
        val f1n = cy + fracPos(q1, window) * half
        val f2n = cy + fracPos(q2, window) * half
        val f3n = cy + fracPos(q3, window) * half
        canvas.drawLine(padL, f1p, padL + w, f1p, gridPaint)
        canvas.drawLine(padL, f2p, padL + w, f2p, gridPaint)
        canvas.drawLine(padL, f3p, padL + w, f3p, gridPaint)
        canvas.drawLine(padL, f1n, padL + w, f1n, gridPaint)
        canvas.drawLine(padL, f2n, padL + w, f2n, gridPaint)
        canvas.drawLine(padL, f3n, padL + w, f3n, gridPaint)

        // 0 基准线（贯穿全宽，明显区别于网格）
        canvas.drawLine(padL, cy, padL + w, cy, zeroPaint)
        // 满刻度上下边线（±window 显示窗口顶）
        canvas.drawLine(padL, padT, padL + w, padT, majorGridPaint)
        canvas.drawLine(padL, padT + h, padL + w, padT + h, majorGridPaint)

        // 坐标轴标签（上半=红优，下半=黑优；与 fracPos 同函数精确落位，标签值即刻度值）
        canvas.drawText("+$window 红优", padL + 2, padT + dp(6).toFloat(), redText)
        canvas.drawText("+$q2", padL + 2, f2p - dp(3).toFloat(), redText)
        canvas.drawText("+$q1", padL + 2, f1p - dp(3).toFloat(), redText)
        canvas.drawText("0 均势", padL + 2, cy - dp(3).toFloat(), zeroText)
        canvas.drawText("-$q1", padL + 2, f1n + dp(13).toFloat(), blackText)
        canvas.drawText("-$q2", padL + 2, f2n + dp(13).toFloat(), blackText)
        canvas.drawText("-$window 黑优", padL + 2, padT + h - dp(3).toFloat(), blackText)

        // 平滑曲线（横向中点控制点的贝塞尔）
        val path = Path()
        if (values.size >= 2) {
            path.moveTo(cxs[0], yys[0])
            for (i in 1 until values.size) {
                val mx = (cxs[i - 1] + cxs[i]) / 2f
                path.cubicTo(mx, yys[i - 1], mx, yys[i], cxs[i], yys[i])
            }
            canvas.drawPath(path, linePaint)
        } else if (values.size == 1) {
            canvas.drawCircle(cxs[0], yys[0], dp(4.2f).toFloat(), dotPaint)
        }

        // 节点圆点（轮到方着色）：小而纯，红=红点、黑=黑点，不叠深色外圈（避免"葡萄"感）
        for (i in values.indices) {
            val cx = cxs[i]; val cyy = yys[i]
            when (nodeColorArr?.getOrNull(i)) {
                1 -> canvas.drawCircle(cx, cyy, dp(2.8f).toFloat(), dotRed)
                2 -> canvas.drawCircle(cx, cyy, dp(2.8f).toFloat(), dotBlack)
                else -> canvas.drawCircle(cx, cyy, dp(2.4f).toFloat(), dotPaint)
            }
        }
        // 杀棋/锁定胜势分贴边：画溢出箭头（顶部=红方杀胜、底部=黑方杀胜），刻度与曲线一致触顶
        for (i in values.indices) {
            if (Math.abs(values[i]) >= JniEngine.MATE_THRESHOLD) {
                val v = values[i]
                drawArrow(canvas, cxs[i], if (v > 0) padT else padT + h, v > 0)
            }
        }
        // 漏着红环
        for (i in blunderIdx) if (i in values.indices) {
            canvas.drawCircle(cxs[i], yys[i], dp(7.0f).toFloat(), ringBlunder)
        }
        // 走下坡起点：琥珀三角
        for (i in slipIdx) if (i in values.indices) {
            val cx = cxs[i]; val baseY = yOf(values[i]) - dp(9).toFloat()
            val tp = Path()
            tp.moveTo(cx, baseY - dp(5).toFloat())
            tp.lineTo(cx - dp(5).toFloat(), baseY + dp(3).toFloat())
            tp.lineTo(cx + dp(5).toFloat(), baseY + dp(3).toFloat())
            tp.close()
            canvas.drawPath(tp, amberPaint)
        }
        // 当前预览节点选中环
        if (highlightIdx in values.indices) {
            canvas.drawCircle(cxs[highlightIdx], yys[highlightIdx], dp(9.0f).toFloat(), ringSel)
        }
        // 游标竖线：贯穿纵坐标窗，穿过当前节点；颜色随节点红/黑而变
        if (cursorIdx in values.indices) {
            val cxi = cxs[cursorIdx]
            val nodeC = when (nodeColorArr?.getOrNull(cursorIdx)) {
                1 -> 0xFFD32F2F.toInt()
                2 -> 0xFF14171A.toInt()
                else -> 0xFFB0A88A.toInt()
            }
            val top = padT - dp(2).toFloat()
            val bot = padT + h + dp(4).toFloat()
            canvas.drawLine(cxi, top, cxi, bot, cursorHalo)
            cursorLine.color = nodeC
            canvas.drawLine(cxi, top, cxi, bot, cursorLine)
            // 顶部倒三角指示
            val tri = Path()
            tri.moveTo(cxi, top - dp(2).toFloat())
            tri.lineTo(cxi - dp(5).toFloat(), top + dp(5).toFloat())
            tri.lineTo(cxi + dp(5).toFloat(), top + dp(5).toFloat())
            tri.close()
            canvas.drawPath(tri, cursorLine)
        }

        // 底部回合标签
        if (values.size > 1) {
            canvas.drawText("第$labelStartRound 回合", padL, padT + h + dp(19).toFloat(), textPaint)
            val endLabel = "第$labelEndRound 回合"
            canvas.drawText(endLabel, padL + w - textPaint.measureText(endLabel), padT + h + dp(19).toFloat(), textPaint)
        }
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, up: Boolean) {
        val p = Path()
        if (up) {
            p.moveTo(x, y - dp(5).toFloat())
            p.lineTo(x - dp(4).toFloat(), y + dp(3).toFloat())
            p.lineTo(x + dp(4).toFloat(), y + dp(3).toFloat())
        } else {
            p.moveTo(x, y + dp(5).toFloat())
            p.lineTo(x - dp(4).toFloat(), y - dp(3).toFloat())
            p.lineTo(x + dp(4).toFloat(), y - dp(3).toFloat())
        }
        p.close()
        canvas.drawPath(p, amberPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (values.isEmpty()) return false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activeIdx = hitTest(event.x, event.y)
                downX = event.x; downY = event.y; dragged = false
                setCursorFromX(event.x)     // 手指按下即定位游标
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX; val dy = event.y - downY
                if (!dragged && Math.hypot(dx.toDouble(), dy.toDouble()) > dp(6f).toDouble()) dragged = true
                if (dragged) setCursorFromX(event.x)   // 拖动：游标随手指吸附，竖向竖线穿过节点
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (!dragged) {
                    val idx = hitTest(event.x, event.y)
                    if (activeIdx >= 0 && idx == activeIdx) {
                        onNodeTap?.invoke(activeIdx, nodeX(activeIdx), nodeY(activeIdx))
                    } else {
                        setCursorFromX(event.x)   // 点空白：游标吸附最近节点，棋盘跟随（小棋盘常开不收起）
                        onBlankTap?.invoke()
                    }
                }
                activeIdx = -1; dragged = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 横坐标吸附到最近节点并同步游标（实时回调 onCursorMove） */
    private fun setCursorFromX(x: Float) {
        var best = 0; var bd = Float.MAX_VALUE
        for (i in values.indices) {
            val d = Math.abs(nodeX(i) - x)
            if (d < bd) { bd = d; best = i }
        }
        cursorIdx = best
        invalidate()
        onCursorMove?.invoke(best)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun nodeX(i: Int): Float {
        if (values.size <= 1) return dp(12).toFloat()
        return dp(12).toFloat() + i * ((width - dp(24)) / (values.size - 1).toFloat())
    }
    private fun nodeY(i: Int): Float {
        val v = values[i]
        val half = (height - dp(32)) / 2f
        val cyy = dp(10).toFloat() + half
        val win = windowOf(values)
        val up = if (v >= 0) fracPos(v, win) else -fracPos(-v, win)
        return cyy - up * half
    }
    private fun hitTest(x: Float, y: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        val rad = dp(16).toFloat()
        for (i in values.indices) {
            val d = (nodeX(i) - x) * (nodeX(i) - x) + (nodeY(i) - y) * (nodeY(i) - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD <= rad * rad) best else -1
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun dp(v: Int): Float = dp(v.toFloat())
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
    private fun sp(v: Int): Float = sp(v.toFloat())
}

/** 单段复盘结果（批处理中一段 = startRound~endRound 的完整评估）。 */
data class ReviewSegment(
    val startRound: Int,
    val endRound: Int,
    val points: List<ReviewAnalyzer.ReviewPoint>,
    val blunders: List<ReviewAnalyzer.ReviewBlunder>,
    val slips: List<ReviewAnalyzer.ReviewSlip>,
    val curve: IntArray,
    val blunderCurveIdx: List<Int>,
    val slipCurveIdx: List<Int>
)

/** 整份复盘文档（单次或批处理若干段），可整体存档/加载。 */
data class ReviewBundle(
    val initialFen: String,
    val movesUci: List<String>,
    val gameTitle: String,
    val savedAt: String,
    val segments: List<ReviewSegment>
)