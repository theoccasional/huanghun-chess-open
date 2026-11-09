package com.pikafish.chess.engine

import android.util.Log

/**
 * ★ 背谱引擎（纯逻辑，不依赖 UI）
 *
 * 背谱 = 不用引擎、靠"谱面约束选择机制"完成。谱源分为两层并合并：
 *   A) 当前已载棋谱主线（CBL 宝库打开的对局，MainActivity 通过 loadLine() 注入，
 *      含该局面在已有主线上出现的"每一个着法"，同一局面多着即变招/分支）；
 *   B) 外置开局库（LocalBookManager 的 JSON/TXT/PKB/UBB/PGN/DAT）。
 *
 * 只允许走"该局面在谱上存在的着法"；谱外着法一律拒绝（棋子点不动）。
 * 单方背谱时，另一方由本引擎按谱自动应子，遇到多分支时【遍历穷尽轮换】
 * 并给出【分支序号提示】（供 UI 弹透明气泡）。
 */
class BookMemorizer(private val book: LocalBookManager) {

    companion object {
        private const val TAG = "BookMemorizer"
    }

    /** 背谱目标：只背红方谱 / 只背黑方谱 / 双方都按谱 */
    enum class Mode { RED, BLACK, BOTH }

    @Volatile var active = false
    var mode: Mode = Mode.RED

    // ── 谱源 A：当前已载棋谱主线（FEN -> 该局面名下的全部 UCI，多着=变招）──
    private val gameLine = HashMap<String, MutableList<String>>()
    private var lineName = "当前局"

    // ── 分支遍历状态：局面 FEN -> 本回合适用的分支序号增量 ──
    // 环形指针：每次自动应子到该局面时 +1 再取模候选数，保证穷尽遍历不重样。
    private val branchCursor = HashMap<String, Int>()

    /** 开始背谱 */
    fun start(m: Mode) {
        mode = m
        branchCursor.clear()
        active = true
        Log.i(TAG, "背谱开始: mode=$m line=${lineName} 库=${book.listBooks().count { it.enabled }}")
    }

    /** 结束背谱 */
    fun stop() {
        active = false
        branchCursor.clear()
        Log.i(TAG, "背谱结束")
    }

    /** 注入/重建"当前棋谱主线"。startFen 为起始局面，moves 为顺序着法（UCI）。 */
    fun loadLine(name: String, startFen: String, moves: List<String>) {
        loadLines(name, startFen, if (moves.isEmpty()) emptyList() else listOf(moves))
    }

    /**
     * ★ 注入"当前棋谱谱系"：lines[0] 为主线，其余为变着支线（每条都是从 startFen
     * 起的完整 UCI 序列，来自 CBL 的变着支线解析）。逐线回放建 FEN->候选着法 表：
     *   - 同一局面出现多着 = 变招（背谱时任选其一，不再只认主线那一手）；
     *   - 支线上每个局面的后续着法也全部在谱 —— 故意背进支线，照样一路背到底。
     */
    fun loadLines(name: String, startFen: String, lines: List<List<String>>) {
        gameLine.clear()
        lineName = if (name.isBlank()) "当前局" else name
        var kept = 0
        for (line in lines) {
            if (line.isEmpty()) continue
            val b = com.pikafish.chess.model.Board()
            try { b.loadFromFen(startFen) } catch (e: Exception) { continue }
            for (uci in line) {
                val fen = b.toFen()
                val m = com.pikafish.chess.model.Move.fromUci(uci, b)
                if (m != null) {
                    val list = gameLine.getOrPut(fen) { mutableListOf() }
                    if (list.none { it == uci }) list.add(uci)
                    b.makeMoveUnchecked(m)
                }
            }
            kept++
        }
        branchCursor.clear()
        Log.i(TAG, "背谱谱系已载: $lineName 线数=$kept 支线=${maxOf(0, kept - 1)} 覆盖局面=${gameLine.size}")
    }

    fun clearLine() {
        gameLine.clear()
        branchCursor.clear()
    }

    /** 是否有任何可用谱源（当前主线或外置库任一有内容） */
    fun hasSource(): Boolean {
        if (gameLine.isNotEmpty()) return true
        return book.listBooks().any { it.enabled && it.entries.isNotEmpty() }
    }

    /** 谱名 */
    fun currentLineName(): String = lineName

    /** 谱源描述（供面板/气泡显示） */
    fun sourceDesc(): String {
        val parts = mutableListOf<String>()
        if (gameLine.isNotEmpty()) parts.add("当前局(${gameLine.size}局面)")
        val enabledN = book.listBooks().count { it.enabled && it.entries.isNotEmpty() }
        if (enabledN > 0) parts.add("外置库(${enabledN}本)")
        return if (parts.isEmpty()) "" else parts.joinToString(" + ")
    }

    /** 一个候选着法：uci + 来源描述 + 分支序号 */
    class Cand(val uci: String, val from: String, val branchIndex: Int)

    /**
     * 合并谱源，返回该局面在谱上存在的全部候选着法（多着=变招/分支）。
     * 顺序：当前主线优先，其后外置库（按书名->库内稳定序）。
     */
    private fun merged(fen: String): List<Cand> {
        val out = ArrayList<Cand>()
        var idx = 0
        gameLine[fen]?.let { line ->
            for (uci in line) { out.add(Cand(uci, lineName, idx)); idx++ }
        }
        for (b in book.listBooks()) {
            if (!b.enabled) continue
            val cands = b.entries[fen] ?: continue
            for (c in cands) {
                // 当前主线已给出该走法时，合并去重（保留主线来源）
                if (out.any { it.uci == c.uci }) continue
                out.add(Cand(c.uci, b.name, idx)); idx++
            }
        }
        return out
    }

    /**
     * 当前局面在谱上允许的全部着法；返回空 = 谱上无着法（脱谱/未覆盖）。
     */
    fun allowedFor(fen: String): List<String> = merged(fen).map { it.uci }

    /**
     * ★ 用户落子校验：仅当该着法确实是谱上存在的才放行。
     * redTurn=true 表示当前轮到红方。据此判断这一步是否归用户自己走。
     */
    fun isUserMove(redTurn: Boolean): Boolean = when (mode) {
        Mode.RED -> redTurn                    // 只背红 → 红方归用户
        Mode.BLACK -> !redTurn                 // 只背黑 → 黑方归用户
        Mode.BOTH -> true                      // 双方都归用户
    }

    /** 是否应由引擎/谱面自动应子（非用户走的那一方）。 */
    fun isAutoSide(redTurn: Boolean): Boolean = when (mode) {
        Mode.RED -> !redTurn                   // 背红 → 黑方自动
        Mode.BLACK -> redTurn                  // 背黑 → 红方自动
        Mode.BOTH -> false                     // 双方都手走，不自动
    }

    /** 该落子是否在谱上合法 */
    fun validate(fen: String, uci: String): Boolean {
        val cands = merged(fen)
        if (cands.isEmpty()) return false
        return cands.any { it.uci == uci }
    }

    /**
     * ★ 自动应子：从该局面谱上的候选里选出本回合应走的那步（分支轮换遍历）。
     * @return 选中的 UCI；无候选返回 null（谱上没覆盖到，无法自动应）
     */
    fun autoReply(fen: String): String? {
        val cands = merged(fen)
        if (cands.isEmpty()) return null
        val ucis = cands.map { it.uci }.distinct()
        if (ucis.size == 1) return ucis[0]
        val cur = branchCursor.getOrDefault(fen, -1)
        val next = (cur + 1) % ucis.size
        branchCursor[fen] = next
        return ucis[next]
    }

    /**
     * ★ 分支提示：该局面谱上有几个候选变招，以及"本回合应走第几个"+该着来源。
     * ★ 纯预览、不落账：真正移动环形指针的是 autoReply —— 修掉旧版"branchInfo 与
     *   autoReply 各自 +1"造成的双重递增（气泡报的号和实际走的招对不上、还跳支）。
     * @return (候选总数, 应采用分支号[0起], 来源描述)；候选<=1 时返回 null
     */
    fun branchInfo(fen: String): Triple<Int, Int, String>? {
        val cands = merged(fen)
        if (cands.size <= 1) return null
        val ucis = cands.map { it.uci }.distinct()
        if (ucis.size <= 1) return null
        val cur = branchCursor.getOrDefault(fen, -1)
        val next = (cur + 1) % ucis.size   // 只预览，不写回
        val pick = cands.firstOrNull { it.uci == ucis[next] }?.from ?: ""
        return Triple(ucis.size, next, pick)
    }

    /** 供调试/日志 */
    override fun toString(): String {
        val m = when (mode) { Mode.RED -> "背红方" ; Mode.BLACK -> "背黑方"; Mode.BOTH -> "背双方" }
        return "背谱[$m] ${sourceDesc()}"
    }
}