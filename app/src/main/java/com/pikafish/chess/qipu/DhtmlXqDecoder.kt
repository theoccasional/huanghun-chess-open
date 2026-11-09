package com.pikafish.chess.qipu

import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.Piece
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import com.pikafish.chess.model.Position

/**
 * DhtmlXQ（东萍 www_dpxq_com）网页棋谱解码器 —— 用于把 CHM 电子书里的
 * `.htm/.html` 页面还原成可回放的棋局。
 *
 * 一页一份棋谱，形如：
 *   [DhtmlXQiFrame]
 *   [DhtmlXQ_title]01自字信手炮第1局[/DhtmlXQ_title]
 *   [DhtmlXQ_binit]8979…[/DhtmlXQ_binit]                 ← 32 槽位棋子表（缺省即标准开局）
 *   [DhtmlXQ_movelist]77477242…[/DhtmlXQ_movelist]        ← 主线（每步 4 位十六进制）
 *   [DhtmlXQ_move_0_10_1]102231…[/DhtmlXQ_move_0_10_1]    ← 变着（line=分支来自哪条线, ply=第几手, id=变着编号）
 *   [DhtmlXQ_comment10]…[/DhtmlXQ_comment10]              ← 第 10 手后的评注
 *   [/DhtmlXQiFrame]
 *
 * 变着按「从本局起始局面起的完整 UCI 序列」展开（与 [CblDecoder.CblGame.variationsUci] 约定一致），
 * 主线 + 变着逐着做合法性校验，非法即截断，保证导入端回放不炸。
 */
object DhtmlXqDecoder {

    private const val STD_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"
    private const val MAX_LINES = 400          // 变着展开线路上限（防超大书 OOM）

    /** 文本里是否含 DhtmlXQ 棋谱（用于快速判定一页要不要解析）。 */
    fun hasDhtmlXq(text: String): Boolean = text.contains("[DhtmlXQ_movelist]")

    /**
     * 解析一页里的全部对局（正常一页一局，个别页面含多局）。
     * @param indexStart 起始序号（多局时递增）
     * @param name 页面/条目名，作题名兜底
     */
    fun decodePage(text: String, indexStart: Int, name: String): List<CblDecoder.CblGame> {
        val out = ArrayList<CblDecoder.CblGame>(1)
        var idx = indexStart
        for (block in splitBlocks(text)) {
            val g = decodeBlock(block, idx, name) ?: continue
            out.add(g)
            idx++
        }
        return out
    }

    /** 按 [DhtmlXQiFrame] 切块；没有该标记则按 [DhtmlXQ] 切；都没有就把整页当一个块。 */
    private fun splitBlocks(text: String): List<String> {
        val frames = Regex("\\[DhtmlXQiFrame]([\\s\\S]*?)\\[/DhtmlXQiFrame]").findAll(text)
            .map { it.groupValues[1] }.filter { hasDhtmlXq(it) }.toList()
        if (frames.isNotEmpty()) return frames
        val blocks = Regex("\\[DhtmlXQ]([\\s\\S]*?)\\[/DhtmlXQ]").findAll(text)
            .map { it.groupValues[1] }.filter { hasDhtmlXq(it) }.toList()
        if (blocks.isNotEmpty()) return blocks
        return if (hasDhtmlXq(text)) listOf(text) else emptyList()
    }

    /** 一个块解出的公共元信息（不含着法合法性校验/评注/变着）——元信息与完整解码共用。 */
    private class BlockMeta(
        val index: Int, val event: String, val date: String, val red: String, val black: String,
        val result: String, val boardFen: String?, val blackFirst: Boolean, val rawMain: List<String>
    )

    /** 解析公共元信息：只做字符串/hex 解析（含一次首着判色），不跑合法性校验，故极快。 */
    private fun parseMeta(block: String, index: Int, name: String): BlockMeta? {
        val moveStr = tag(block, "DhtmlXQ_movelist")?.replace(Regex("\\s+"), "") ?: return null
        if (moveStr.length < 4) return null
        val boardFen = decodeBinit(tag(block, "DhtmlXQ_binit"))
        val rawMain = decodeMoves(moveStr)
        if (rawMain.isEmpty()) return null
        val blackFirst = detectBlackFirst(rawMain, boardFen)
        val title = tag(block, "DhtmlXQ_title")?.trim().orEmpty()
        // ★ 题名为空、或通篇都叫"无标题"时，改用来源名（天天象棋题库那类一包上万 .txt 全叫
        //   "无标题"，照搬进宝库会满屏同名、分不清哪题是哪题）。文件名本身带编号/分值，更可读。
        //   压缩包条目名常是"目录/子目录/文件.txt"，只取末段文件名，免得十列长路径把列表撑爆。
        val fallbackName = name.substringAfterLast('/').substringAfterLast('\\')
            .substringBeforeLast('.').ifBlank { name }
        val event = if (title.isBlank() || title == "无标题") fallbackName else title
        return BlockMeta(
            index, event,
            tag(block, "DhtmlXQ_date")?.trim().orEmpty(),
            tag(block, "DhtmlXQ_red")?.trim().orEmpty(),
            tag(block, "DhtmlXQ_black")?.trim().orEmpty(),
            mapResult(tag(block, "DhtmlXQ_result")),
            boardFen, blackFirst, rawMain
        )
    }

    private fun fenOf(m: BlockMeta): String? = if (m.boardFen != null && m.boardFen != STD_FEN) m.boardFen else null

    /** 仅元信息（movesUci 为空 → QipuGame 视为"待懒加载"）。 */
    private fun toMetaGame(m: BlockMeta): CblDecoder.CblGame = CblDecoder.CblGame(
        index = m.index, event = m.event, site = "", date = m.date, red = m.red, black = m.black,
        result = m.result, fenBoard = fenOf(m), moversBlack = m.blackFirst,
        movesUci = emptyList(), introComment = "", endComment = "", midComments = emptyList(),
        moveComments = emptyMap(), commentaryBlocks = emptyList()
    )

    /**
     * ★ 只解元信息、不解着法 —— 供「一包上万个 .txt 东萍谱」这类超大条目集建列表用。
     *   若在这里就把 1 万题逐着做合法性校验，载入会卡几十秒；改成元信息 + 懒加载后，
     *   只有真正点开的那一题才解码。顺序与 [decodePage] 一致。
     */
    fun decodeMetaPage(text: String, indexStart: Int, name: String): List<CblDecoder.CblGame> {
        val out = ArrayList<CblDecoder.CblGame>(1)
        var idx = indexStart
        for (block in splitBlocks(text)) {
            val m = parseMeta(block, idx, name) ?: continue
            out.add(toMetaGame(m))
            idx++
        }
        return out
    }

    /** 懒加载：解出该页第 [k] 个可解块的完整棋局（与 [decodeMetaPage] 同序）。 */
    fun decodePageBlock(text: String, k: Int, index: Int, name: String): CblDecoder.CblGame? {
        var n = 0
        for (block in splitBlocks(text)) {
            parseMeta(block, index, name) ?: continue
            if (n == k) return decodeBlock(block, index, name)
            n++
        }
        return null
    }

    private fun decodeBlock(block: String, index: Int, name: String): CblDecoder.CblGame? {
        val m = parseMeta(block, index, name) ?: return null
        val main = legalize(m.rawMain, m.boardFen, m.blackFirst)
        if (main.isEmpty()) return null
        return CblDecoder.CblGame(
            index = m.index, event = m.event, site = "", date = m.date, red = m.red, black = m.black,
            result = m.result, fenBoard = fenOf(m), moversBlack = m.blackFirst,
            movesUci = main, introComment = "", endComment = "", midComments = emptyList(),
            moveComments = parseComments(block, main.size),
            commentaryBlocks = emptyList(),
            variationsUci = expandVariations(block, main, m.boardFen, m.blackFirst)
        )
    }

    // ══════════════════ 变着展开 ══════════════════

    private class VarLine(val line: Int, val ply: Int, val id: Int, val moves: List<String>)

    /**
     * 变着标签 `[DhtmlXQ_move_<line>_<ply>_<id>]`：line 指明分支来自哪条线（0=主线，
     * n=编号 n 的变着线），ply 是「被替换的第几手」（1 基），id 是该变着编号。
     * 展开为「该线前 ply-1 手 + 变着着法」的完整线路，与 comments 的 `comment<id>_<ply>` 对应。
     */
    private fun expandVariations(
        block: String, main: List<String>, boardFen: String?, blackFirst: Boolean
    ): List<List<String>> {
        val vars = ArrayList<VarLine>()
        val re = Regex("\\[DhtmlXQ_move_(\\d+)_(\\d+)_(\\d+)]([^\\[]*)\\[/DhtmlXQ_move_\\d+_\\d+_\\d+]")
        for (m in re.findAll(block)) {
            val moves = decodeMoves(m.groupValues[4].replace(Regex("\\s+"), ""))
            if (moves.isNotEmpty()) {
                vars.add(VarLine(m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(), moves))
            }
        }
        if (vars.isEmpty()) return emptyList()
        vars.sortBy { it.id }

        val byId = HashMap<Int, List<String>>()
        byId[0] = main
        val out = ArrayList<List<String>>()
        for (v in vars) {
            if (out.size >= MAX_LINES) break
            val base = byId[v.line] ?: byId[0] ?: continue
            val prefix = base.take((v.ply - 1).coerceIn(0, base.size))
            val legal = legalize(prefix + v.moves, boardFen, blackFirst)
            if (legal.size <= prefix.size) continue      // 变着一步都走不通，丢弃
            byId[v.id] = legal
            out.add(legal)
        }
        return out
    }

    // ══════════════════ 评注 ══════════════════

    /** `[DhtmlXQ_commentN]` → 主线第 N 手（1 基）后的评注，映射到 0 基索引。 */
    private fun parseComments(block: String, mainSize: Int): Map<Int, String> {
        val out = HashMap<Int, String>()
        val re = Regex("\\[DhtmlXQ_comment(\\d+)]([^\\[]*)\\[/DhtmlXQ_comment\\d+]")
        for (m in re.findAll(block)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            val txt = m.groupValues[2].trim()
            if (txt.isEmpty()) continue
            val idx = n - 1
            if (idx in 0 until mainSize) out[idx] = txt
        }
        return out
    }

    // ══════════════════ 局面/着法解码 ══════════════════

    /** 东萍坐标值 = 高 4 位列 + 低 4 位行（v = col*16 + row）→ Position(row,col) */
    private fun dhtmlPos(v: Int): Position? {
        val col = (v ushr 4) and 0x0F
        val row = v and 0x0F
        return if (row in 0..9 && col in 0..8) Position(row, col) else null
    }

    /** 解码 binit：FEN 直存原样返回；否则按 32 槽位十六进制解成棋板 FEN；空/非法返回 null（标准开局）。 */
    private fun decodeBinit(hex: String?): String? {
        if (hex.isNullOrBlank()) return null
        val cleaned = hex.replace(Regex("\\s+"), "")
        if (cleaned.count { it == '/' } == 9) return cleaned
        if (cleaned.length < 4) return null
        val order = xqfPieceOrder()
        val grid = Array(10) { arrayOfNulls<Piece>(9) }
        var byteIndex = 0
        var i = 0
        while (byteIndex < 32 && i + 2 <= cleaned.length) {
            val v = cleaned.substring(i, i + 2).toIntOrNull(16) ?: 0xFF
            val pos = dhtmlPos(v)
            if (pos != null) grid[pos.row][pos.col] = order[byteIndex]
            i += 2
            byteIndex++
        }
        return gridToFen(grid)
    }

    /** 解码 movelist：优先 UCI 串；否则按标准十六进制（每步 4 字符：起点2位+终点2位）。 */
    private fun decodeMoves(raw: String): List<String> {
        val cleaned = raw.replace(Regex("\\s+"), "")
        if (cleaned.length < 4) return emptyList()
        if (cleaned.length % 4 == 0) {
            var uciOk = true
            val tmp = ArrayList<String>(cleaned.length / 4)
            for (i in 0 until cleaned.length step 4) {
                val t = cleaned.substring(i, i + 4)
                if (!Regex("[a-i][0-9][a-i][0-9]").matches(t)) { uciOk = false; break }
                tmp.add(t)
            }
            if (uciOk && tmp.isNotEmpty()) return tmp
        }
        val out = ArrayList<String>(cleaned.length / 4)
        var i = 0
        while (i + 4 <= cleaned.length) {
            val fv = cleaned.substring(i, i + 2).toIntOrNull(16) ?: break
            val tv = cleaned.substring(i + 2, i + 4).toIntOrNull(16) ?: break
            i += 4
            val f = dhtmlPos(fv) ?: break
            val t = dhtmlPos(tv) ?: break
            out.add(f.toUci() + t.toUci())
        }
        return out
    }

    /** 由首着起子颜色判定先行方（排局/中局可能黑先）。 */
    private fun detectBlackFirst(line: List<String>, boardFen: String?): Boolean {
        val b = Board()
        if (boardFen != null) b.loadFromFen(boardFen)
        val first = line.firstOrNull() ?: return false
        val piece = Move.fromUci(first, b)?.piece ?: return false
        return piece.color == PieceColor.BLACK
    }

    /** 逐着合法性校验：非法着即截断该线（保证导入端回放不炸）。 */
    private fun legalize(line: List<String>, boardFen: String?, blackFirst: Boolean): List<String> {
        val b = Board()
        if (boardFen != null) b.loadFromFen(boardFen)
        if (blackFirst) b.currentPlayer = PieceColor.BLACK
        val out = ArrayList<String>(line.size)
        for (u in line) {
            val m = Move.fromUci(u, b) ?: break
            if (b.getLegalMoves(b.currentPlayer).none { it.toUci() == u }) break
            b.makeMove(m)
            out.add(u)
        }
        return out
    }

    // ══════════════════ 坐标 / 棋子 / 文本 ══════════════════

    /** 象棋演播室(XQStudio)棋子槽位顺序：0-15 红，16-31 黑（东萍 binit 共用）。 */
    private fun xqfPieceOrder(): List<Piece> {
        val out = ArrayList<Piece>(32)
        val backs = listOf(PieceType.ROOK, PieceType.KNIGHT, PieceType.ELEPHANT, PieceType.ADVISOR,
            PieceType.KING, PieceType.ADVISOR, PieceType.ELEPHANT, PieceType.KNIGHT, PieceType.ROOK)
        for (t in backs) out.add(Piece(t, PieceColor.RED))
        out.add(Piece(PieceType.CANNON, PieceColor.RED))
        out.add(Piece(PieceType.CANNON, PieceColor.RED))
        repeat(5) { out.add(Piece(PieceType.PAWN, PieceColor.RED)) }
        for (t in backs) out.add(Piece(t, PieceColor.BLACK))
        out.add(Piece(PieceType.CANNON, PieceColor.BLACK))
        out.add(Piece(PieceType.CANNON, PieceColor.BLACK))
        repeat(5) { out.add(Piece(PieceType.PAWN, PieceColor.BLACK)) }
        return out
    }

    /** 棋板 → 仅棋子行的 FEN（不含先行方，与 CblGame.fenBoard 约定一致）。 */
    private fun gridToFen(g: Array<Array<Piece?>>): String {
        val sb = StringBuilder()
        for (r in 0..9) {
            var empty = 0
            for (c in 0..8) {
                val p = g[r][c]
                if (p == null) empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    val fc = p.type.fenChar
                    sb.append(if (p.color == PieceColor.RED) fc.uppercaseChar() else fc)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 9) sb.append('/')
        }
        return sb.toString()
    }

    private fun tag(block: String, name: String): String? =
        Regex("\\[$name]([\\s\\S]*?)\\[/$name]").find(block)?.groupValues?.get(1)

    private fun mapResult(r: String?): String {
        val s = r?.trim().orEmpty()
        return when {
            s.contains("红胜") -> "1-0"
            s.contains("黑胜") -> "0-1"
            s.contains("和") -> "1/2-1/2"
            else -> "*"
        }
    }
}
