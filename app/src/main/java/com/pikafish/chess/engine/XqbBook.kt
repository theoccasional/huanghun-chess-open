package com.pikafish.chess.engine

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.PieceColor
import java.io.File

/**
 * ★ 2.9.299：xqb 开局库（开源格式，SQLite）直查句柄。
 *
 * 格式依据 https://github.com/fisherfan/xqbook （main.c）：
 *  - 表 book(key BLOB, move INTEGER, score INTEGER, win, draw, lost, valid, memo)
 *  - key：逐格 1 位占用标志；占用再读 4 位棋子编码
 *        1..7  = 走子方（车马象士将炮兵）
 *        9..15 = 对方同序棋子
 *  - key 以“走子方在下方”的规范方向保存，并做左右镜像归一化（左小右大）
 *  - move = fromRow(4) | fromCol(4) | toRow(4) | toCol(4)
 *
 * 因为规范盘丢了“走子方是红是黑”“是否左右镜像”两条信息，
 * 这里同样按“先算我方规范键再查”的方式：对当前局面做与写库时一致的变换即可命中，
 * 查到的着法再按变换逆映射回来 —— 不需要像转换器那样铺 4 份候选。
 */
object XqbBook {

    private const val TAG = "XqbBook"

    class Handle(private val db: SQLiteDatabase, private val path: String) {
        var rowCount: Int = 0
        var name: String = File(path).name

        /** 按 FEN 查库（主路径 query(fen) 用）：内部先还原成 Board 再算规范键 */
        fun query(fen: String): List<Pair<String, Int>> {
            return try {
                val b = Board(); b.loadFromFen(fen); query(b)
            } catch (_: Exception) { emptyList() }
        }

        fun close() = try { db.close() } catch (_: Exception) {}

        /** 当前局面查库：返回 (uci, score) 列表；不命中返回空 */
        fun query(board: Board): List<Pair<String, Int>> {
            val (key, flipUD, mirrorLR) = encode(board) ?: return emptyList()
            val out = ArrayList<Pair<String, Int>>(8)
            try {
                // SQLite 只支持字符串型 selectionArgs，BLOB 用 x'..' 十六进制字面量绑定
                val hex = buildString { for (b in key) append("%02X".format(b)) }
                db.rawQuery("select move,score from book where key=x'$hex'", null).use { c ->
                    while (c.moveToNext()) {
                        val mv = c.getInt(0)
                        val sc = if (c.isNull(1)) 0 else c.getInt(1)
                        val uci = decodeMove(mv, flipUD, mirrorLR) ?: continue
                        out.add(uci to sc)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "xqb 查询失败: ${e.message}")
            }
            return out
        }
    }

    /** 打开 .xqb（校验 information/book 两张表存在，否则返回 null） */
    fun open(path: String): Handle? = try {
        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        val tables = HashSet<String>()
        db.rawQuery("select name from sqlite_master where type='table'", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { tables.add(it) }
        }
        if (!tables.contains("book")) { db.close(); null } else {
            val h = Handle(db, path)
            h.rowCount = try {
                db.rawQuery("select count(*) from book", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            } catch (_: Exception) { 0 }
            h
        }
    } catch (e: Exception) {
        Log.w(TAG, "打开 xqb 失败: ${e.message}")
        null
    }

    // ── 局面 → 规范键 ─────────────────────────────────────────────
    // 返回 (28 字节 key, 是否做过上下翻转(黑方走子), 是否做过左右镜像)
    private fun encode(board: Board): Triple<ByteArray, Boolean, Boolean>? {
        val cells = IntArray(90) { -1 }   // -1 空；1..7 走子方；9..15 对方
        val redToMove = board.currentPlayer == PieceColor.RED
        for (r in 0..9) for (c in 0..8) {
            val p = board.pieceAt(r, c) ?: continue
            val t = p.type.ordinal  // KING0 ADVISOR1 ELEPHANT2 KNIGHT3 ROOK4 CANNON5 PAWN6
            // 编码：车R=1 马N=2 象B=3 士A=4 将K=5 炮C=6 兵P=7
            val code = when (t) {
                4 -> 1; 3 -> 2; 2 -> 3; 1 -> 4; 0 -> 5; 5 -> 6; else -> 7
            }
            val own = (p.color == PieceColor.RED) == redToMove
            cells[r * 9 + c] = if (own) code else code + 8
        }
        // 规范方向：走子方在下（黑走 → 上下翻转 180°）
        var a = cells
        val flipUD = !redToMove
        if (flipUD) {
            a = IntArray(90) { i -> a[(9 - (i / 9)) * 9 + (8 - (i % 9))] }
        }
        // 左右镜像归一化：左大右小则镜像（与写库逻辑一致）
        var mirrorLR = false
        outer@ for (r in 0..9) for (c in 0..4) {
            val l = a[r * 9 + c]; val rr = a[r * 9 + (8 - c)]
            if (l != rr) { mirrorLR = rr > l; break@outer }
        }
        if (mirrorLR) {
            a = IntArray(90) { i -> a[(i / 9) * 9 + (8 - (i % 9))] }
        }
        return Triple(pack(a), flipUD, mirrorLR)
    }

    /** 位流：逐格 1 位；占用再 4 位。长度按实际子力数截断（库内 key 为 23..32 子力对应的 24..28 字节） */
    private fun pack(cells: IntArray): ByteArray {
        val occupied = cells.count { it >= 0 }
        val nbytes = ((90 + 4 * occupied) + 7) / 8
        val out = ByteArray(nbytes)
        var bit = 0
        fun set(v: Int) {
            if (v != 0) out[bit / 8] = (out[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
            bit++
        }
        for (cell in cells) {
            if (cell < 0) set(0) else {
                set(1)
                val c = cell
                for (k in 3 downTo 0) set((c shr k) and 1)
            }
        }
        return out
    }

    /** move 逆映射回真实盘面坐标 → uci（a0=左下，与引擎约定一致） */
    private fun decodeMove(mv: Int, flipUD: Boolean, mirrorLR: Boolean): String? {
        var fr = (mv shr 12) and 0xF
        var fc = (mv shr 8) and 0xF
        var tr = (mv shr 4) and 0xF
        var tc = mv and 0xF
        if (fr !in 0..9 || tr !in 0..9 || fc !in 0..8 || tc !in 0..8) return null
        if (mirrorLR) { fc = 8 - fc; tc = 8 - tc }
        if (flipUD) { fr = 9 - fr; tr = 9 - tr; fc = 8 - fc; tc = 8 - tc }
        fun sq(r: Int, c: Int) = (('a'.code + c).toChar()).toString() + (9 - r)
        return sq(fr, fc) + sq(tr, tc)
    }
}
