package com.pikafish.chess.qipu

import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.model.Piece
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import java.nio.charset.Charset

/**
 * XqfDecoder —— 象棋演播室(XQStudio) XQF 二进制棋谱的**独立解码器**。
 *
 * 与 ChessGame.importXqf 同源（同一套密钥派生 / 节点树读取 / 坐标换算，已用真实 0x12 加密
 * 文件验证），但去掉了对界面状态的依赖，只产出 CblDecoder.CblGame —— 这样「压缩包里的几千
 * 个 .xqf」（如"棋路内置棋谱.zip"内含 7478 个 XQF）也能像 MXQ/CBL 一样成批入宝库、逐局点开回放。
 *
 * 支持 v10 明文 + v11/v12+ 加密；支持中局/排局起点(type>=2 的 32 槽位局面)；
 * 主线 + 全部变招（变招按"从起始局面起的完整线路"展开，与 CBR/CBL 的 variationsUci 约定一致）。
 */
object XqfDecoder {

    private val GBK: Charset = Charset.forName("GBK")
    private const val MAX_LINES = 400        // 变招展开线路上限（防超大书 OOM）
    private const val STD_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"

    /** 内容识别：文件头两字节固定为 "XQ"，且不小于 XQF 最小长度(0x400)。 */
    fun isXqf(raw: ByteArray): Boolean =
        raw.size >= 0x400 && raw[0].toInt() == 'X'.code && raw[1].toInt() == 'Q'.code

    /** 只取头部元信息（题名/日期/双方），着法留空 —— 供「一包几千局」的列表与搜索索引用。 */
    fun decodeMeta(raw: ByteArray, index: Int, name: String): CblDecoder.CblGame? =
        parse(raw, index, name, withMoves = false)

    /** 全量解码（含主线 + 变招）。 */
    fun decode(raw: ByteArray, index: Int, name: String): CblDecoder.CblGame? =
        parse(raw, index, name, withMoves = true)

    private class Node(var uci: String = "") {
        var next: Node? = null
        var sibling: Node? = null
    }

    private fun parse(raw: ByteArray, index: Int, name: String, withMoves: Boolean): CblDecoder.CblGame? {
        if (!isXqf(raw)) return null
        val d = raw
        val version = d[2].toInt() and 0xFF
        val type = d[0x40].toInt() and 0xFF

        // ── 加密密钥（v11+ 才需要；v10 恒为 0）──
        val encStream = IntArray(32)
        var pieceOff = 0; var srcOff = 0; var dstOff = 0; var commentOff = 0
        if (version >= 11) {
            val t = IntArray(16) { d[it].toInt() and 0xFF }
            pieceOff = (sq54(t[13]) * t[13]) and 0xFF
            srcOff = (sq54(t[14]) * pieceOff) and 0xFF
            dstOff = (sq54(t[15]) * srcOff) and 0xFF
            commentOff = ((t[12] * 256 + t[13]) % 32000) + 767
            val arg0 = t[3]
            val args = IntArray(4) { i -> (t[8 + i] or (t[12 + i] and arg0)) and 0xFF }
            val mask = "[(C) Copyright Mr. Dong Shiwei.]"
            for (i in 0..31) encStream[i] = (args[i % 4] and mask[i].code) and 0xFF
        }

        // ── 起始局面（type>=2 为中局/排局，32 槽位定长表）──
        var startFen: String? = null
        if (type >= 2) {
            val order = pieceOrder()
            val grid = Array(10) { arrayOfNulls<Piece>(9) }
            if (version < 12) {
                for (i in 0..31) {
                    val posVal = ((d[0x10 + i].toInt() and 0xFF) - pieceOff) and 0xFF
                    if (posVal > 89) continue
                    val pos = xqfPos(posVal)
                    if (pos != null && i < order.size) grid[pos.row][pos.col] = order[i]
                }
            } else {
                for (k in 0..31) {
                    val i = (((k - pieceOff - 1) % 32) + 32) % 32
                    val posVal = ((d[0x10 + i].toInt() and 0xFF) - pieceOff) and 0xFF
                    if (posVal > 89) continue
                    val pos = xqfPos(posVal)
                    if (pos != null && k < order.size) grid[pos.row][pos.col] = order[k]
                }
            }
            startFen = gridToFen(grid)
        }

        // ── 头字段（长度前缀 + GBK；缺失/非标准时留空）──
        val title = readStr(d, 0x50, 64).ifBlank { name.substringBeforeLast('.') }
        val date = readStr(d, 0x110, 16)
        val red = readStr(d, 0x130, 16)
        val black = readStr(d, 0x140, 16)

        var moves: List<String> = emptyList()
        var variations: List<List<String>> = emptyList()
        var moversBlack = false
        if (withMoves) {
            val startOffset = startOffset(d, version, encStream, commentOff)
            val root = readNodeTree(d, version, encStream, srcOff, dstOff, commentOff, intArrayOf(startOffset), 0)
            if (root != null) {
                val rawLines = collectLines(root)
                // ★ 中局/排局(type>=2)：起点的先行方可能是黑方（XQF 记录里不含执子方字段，
                //   恒按红先加载）。由首着的起子颜色判定真正先行方，否则黑先局面会被逐着判非法 → 零着。
                val blackFirst = detectBlackFirst(rawLines, startFen)
                val lines = rawLines
                    .map { legalize(it, startFen, blackFirst) }
                    .filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) {
                    moves = lines.first()
                    variations = if (lines.size > 1) lines.subList(1, lines.size) else emptyList()
                    moversBlack = blackFirst
                }
            }
        }

        return CblDecoder.CblGame(
            index = index,
            event = title.ifBlank { name },
            site = "",
            date = date,
            red = red,
            black = black,
            result = "*",
            fenBoard = if (startFen != null && startFen != STD_FEN) startFen else null,
            moversBlack = moversBlack,
            movesUci = moves,
            introComment = "",
            endComment = "",
            midComments = emptyList(),
            moveComments = emptyMap(),
            commentaryBlocks = emptyList(),
            variationsUci = variations
        )
    }

    // ══════════════════ 着法区解析 ══════════════════

    private fun startOffset(d: ByteArray, version: Int, encStream: IntArray, commentOff: Int): Int {
        val area = 0x400
        if (area + 2 >= d.size) return area + 4
        val fl = decByteAt(d, area + 2, encStream) ?: return area + 4
        var cursor = area + 4
        if (version > 10) {
            if ((fl and 0x20) != 0) {
                val len = decIntLE(d, area + 4, encStream) - commentOff
                if (len in 1..4096) cursor = area + 8 + len
            }
        } else {
            // v10：根哨兵同样是 8 字节定长节点（From,To,ChildTag,Reserved + RemarkSize）。
            // ★ 无注解时首手节点从 area+8 起；原实现默认 area+4 会读进哨兵内部（一片 0），
            //   解析出的坐标非法 → 整条线路被合法性校验截断为空 → 宝库里「有元信息、点开棋盘是空的、回合 0」。
            cursor = area + 8
            val len = decIntLE(d, area + 4, encStream)
            if (len in 1..4096) cursor = area + 8 + len
        }
        return cursor
    }

    private fun decByteAt(d: ByteArray, absPos: Int, encStream: IntArray): Int? {
        if (absPos < 0 || absPos >= d.size) return null
        return ((d[absPos].toInt() and 0xFF) - encStream[absPos % 32]) and 0xFF
    }

    private fun decIntLE(d: ByteArray, absPos: Int, encStream: IntArray): Int {
        var v = 0
        var mul = 1
        for (i in 0..3) {
            val b = decByteAt(d, absPos + i, encStream) ?: return v
            v += b * mul
            mul *= 256
        }
        return v
    }

    /** 递归读取 XQF 节点树（主线子节点 next + 变招兄弟 sibling）。 */
    private fun readNodeTree(
        d: ByteArray, version: Int, encStream: IntArray,
        srcOff: Int, dstOff: Int, commentOff: Int, off: IntArray, depth: Int
    ): Node? {
        val start = off[0]
        if (start + 4 > d.size || depth > 20000) return null
        val b0 = decByteAt(d, start, encStream) ?: return null
        val b1 = decByteAt(d, start + 1, encStream) ?: return null
        val fl = decByteAt(d, start + 2, encStream) ?: return null
        val hasNext: Boolean; val hasVar: Boolean; val frVal: Int; val toVal: Int; var cursor: Int
        if (version <= 10) {
            // v10：8 字节定长结构的线性棋谱；第3字节高半字节非0=有后续；该版本不支持变招。
            hasNext = (fl and 0xF0) != 0; hasVar = false
            frVal = (b0 - 24) and 0xFF; toVal = (b1 - 32) and 0xFF
            cursor = start + 8
            if (start + 8 <= d.size) {
                val clen = decIntLE(d, start + 4, encStream)
                if (clen in 1..4096 && start + 8 + clen <= d.size) cursor = start + 8 + clen
            }
        } else {
            // v11+：4 字节结构；注释长度在 start+4，注释正文从 start+8 起，其后才是下一节点。
            hasNext = (fl and 0x80) != 0; hasVar = (fl and 0x40) != 0
            frVal = (b0 - 24 - srcOff) and 0xFF; toVal = (b1 - 32 - dstOff) and 0xFF
            cursor = start + 4
            if ((fl and 0x20) != 0 && start + 6 <= d.size) {
                val clen = decIntLE(d, start + 4, encStream) - commentOff
                if (clen in 1..4096 && start + 8 + clen <= d.size) cursor = start + 8 + clen
            }
        }
        val node = Node(appUci(frVal, toVal))
        if (hasNext) { off[0] = cursor; node.next = readNodeTree(d, version, encStream, srcOff, dstOff, commentOff, off, depth + 1); cursor = off[0] }
        if (hasVar) { off[0] = cursor; node.sibling = readNodeTree(d, version, encStream, srcOff, dstOff, commentOff, off, depth + 1); cursor = off[0] }
        off[0] = cursor
        return node
    }

    /** 展开节点树为「从起始局面起的完整线路」；首条为纯主线(沿 next 链)，其余含变招。 */
    private fun collectLines(root: Node): List<List<String>> {
        val out = ArrayList<List<String>>()
        val guard = intArrayOf(0)
        fun walk(n: Node?, path: MutableList<String>) {
            if (n == null) {
                if (path.isNotEmpty()) out.add(ArrayList(path))
                return
            }
            var s: Node? = n
            while (s != null) {
                if (guard[0] > 300000 || out.size >= MAX_LINES) return
                guard[0]++
                val u = s.uci
                if (u.isEmpty()) { s = s.sibling; continue }
                path.add(u)
                walk(s.next, path)
                path.removeAt(path.size - 1)
                if (out.size >= MAX_LINES) return
                s = s.sibling
            }
        }
        walk(root, ArrayList())
        return out.distinctBy { it.joinToString(" ") }
    }

    /** 起点先行方判定：取第一条线路的首着，按其在起始棋盘上的起子颜色定红先/黑先。 */
    private fun detectBlackFirst(rawLines: List<List<String>>, startFen: String?): Boolean {
        if (startFen == null) return false          // 标准开局恒红先
        val b = Board()
        b.loadFromFen(startFen)
        for (line in rawLines) {
            val first = line.firstOrNull() ?: continue
            val piece = Move.fromUci(first, b)?.piece ?: continue
            return piece.color == PieceColor.BLACK
        }
        return false
    }

    /** 逐着合法性校验：非法着即截断该线（保证导入端回放不炸）。 */
    private fun legalize(line: List<String>, startFen: String?, blackFirst: Boolean): List<String> {
        val b = Board()
        if (startFen != null) b.loadFromFen(startFen)
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

    // ══════════════════ 坐标 / 棋子 / 字符串 ══════════════════

    private fun sq54(x: Int): Int = x * x * 54 + 221

    /** XQF 坐标值 = col*10 + y（y=9-row）→ app Position(row,col) */
    private fun xqfPos(v: Int): com.pikafish.chess.model.Position? {
        val col = v / 10
        val y = v % 10
        val row = 9 - y
        return if (row in 0..9 && col in 0..8) com.pikafish.chess.model.Position(row, col) else null
    }

    private fun appUci(frVal: Int, toVal: Int): String {
        val from = xqfPos(frVal) ?: return ""
        val to = xqfPos(toVal) ?: return ""
        return from.toUci() + to.toUci()
    }

    /** 象棋演播室棋子槽位顺序：0-15 红，16-31 黑。 */
    private fun pieceOrder(): List<Piece> {
        val out = ArrayList<Piece>(32)
        val backs = listOf(PieceType.ROOK, PieceType.KNIGHT, PieceType.ELEPHANT, PieceType.ADVISOR,
            PieceType.KING, PieceType.ADVISOR, PieceType.ELEPHANT, PieceType.KNIGHT, PieceType.ROOK)
        for (t in backs) out.add(Piece(t, PieceColor.RED))
        out.add(Piece(PieceType.CANNON, PieceColor.RED)); out.add(Piece(PieceType.CANNON, PieceColor.RED))
        repeat(5) { out.add(Piece(PieceType.PAWN, PieceColor.RED)) }
        for (t in backs) out.add(Piece(t, PieceColor.BLACK))
        out.add(Piece(PieceType.CANNON, PieceColor.BLACK)); out.add(Piece(PieceType.CANNON, PieceColor.BLACK))
        repeat(5) { out.add(Piece(PieceType.PAWN, PieceColor.BLACK)) }
        return out
    }

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

    /** 长度前缀 + GBK 字符串（首字节为长度）。非法/越界/含替换符时返回空串。 */
    private fun readStr(d: ByteArray, off: Int, size: Int): String {
        if (off < 0 || off >= d.size) return ""
        val l = d[off].toInt() and 0xFF
        if (l <= 0 || l >= size) return ""
        val end = minOf(off + 1 + l, d.size)
        if (end - off - 1 <= 0) return ""
        val s = String(d, off + 1, end - off - 1, GBK)
        if (s.indexOf('\uFFFD') >= 0 || s.indexOf('\u0000') >= 0) return ""
        return s.trim()
    }
}
