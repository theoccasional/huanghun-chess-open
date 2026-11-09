package com.pikafish.chess.engine

import android.util.Log
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move

/**
 * OBK 开局库真实反解器。
 *
 * 原理移植自 obk2dat.py（该工具已把这些 .obk 成功转成 .dat）：
 *   1. 解析 Absolute Database（魔数 ABS0LUTEDATABASE）的 4096 字节数据页，
 *      每页含 46 字节记录：16 字节 hex vkey + 7 个 int32（vmove, vscore, vwin, vdraw, vlost, vvalid, vmemo）。
 *   2. 把每条记录 (vkey, vmove, vscore, vvalid, 胜负和) 直接交给调用方 sink，
 *      由 LocalBookManager 一次性灌入「Long vkey → 着法」索引——不保留中间字典。
 *
 * ★ 内存友好（这是大库能装下的关键）：
 *   - 二进制库按 4096 字节数据页流式读取，绝不把整本 190MB+ 文件读进内存；
 *   - 局面键直接用 Long（8 字节），不再用 16 字符十六进制 String（几十字节）；
 *   - 解析过程不产生任何中间副本，峰值内存 ≈ 最终索引本身。
 *
 * 结果：ObkParser 真正把 .obk 内容反解为「局面→着法」索引，可被 LocalBookManager 命中。
 */
object ObkParser {

    private const val TAG = "ObkParser"
    private const val PAGE = 4096
    private val MAGIC = "ABS0LUTEDATABASE".toByteArray(Charsets.US_ASCII)

    /**
     * OBK 内部棋子类型编号（与 局面HASHKEY计算.txt、obk2dat.py 一致）：
     * 红 帅0 仕1 相2 马3 车4 炮5 兵6；黑 将7 士8 象9 马10 车11 炮12 卒13
     */
    internal data class BookMove(
        val vmove: Int,     // 编码着法 (from<<8)|to
        val score: Int,
        val vvalid: Int,
        val wins: Int = 0,   // 该着法经验胜负和（供胜率排序/防中毒）
        val draws: Int = 0,
        val losses: Int = 0
    ) {
        /** 经验胜率 0..1；无统计返回 0（退化用评分排序） */
        val winRate: Float
            get() {
                val total = wins + draws + losses
                return if (total > 0) wins.toFloat() / total else 0f
            }
    }

    /* 文件头是否是 OBK 的 ABS0LUTEDATABASE 魔数 */
    internal fun isObkMagic(head: ByteArray): Boolean {
        if (head.size < MAGIC.size) return false
        return head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    }

    /** 文件头是否是 SQLite 数据库（旋风系 SQLite 开局库，如 bhobk 表） */
    internal fun isSqliteMagic(head: ByteArray): Boolean {
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        if (head.size < magic.size) return false
        return head.copyOfRange(0, magic.size).contentEquals(magic)
    }

    // ─────────────────────────────────────────────────────────────
    // 流式解析入口（大库专用：整本 190MB+ 也能装，不整包进内存）
    // ─────────────────────────────────────────────────────────────

    /**
     * 从文件路径流式解析 .obk，把每条 (vkeyLong, BookMove) 交给 sink。
     * 二进制 ABS 库按数据页读取；SQLite 库直接只读打开（不复制临时文件）。
     * @return true 表示至少产出一条有效记录
     */
    internal fun parseBookFile(path: String, sink: (Long, BookMove) -> Unit): Boolean {
        return try {
            val f = java.io.File(path)
            if (!f.exists() || f.length() <= 0) return false
            val head = java.io.FileInputStream(f).use { ins ->
                val h = ByteArray(16)
                var off = 0
                while (off < 16) {           // 循环读到 16 字节（短读安全）
                    val n = ins.read(h, off, 16 - off)
                    if (n < 0) break
                    off += n
                }
                if (off <= 0) null else h.copyOf(off)
            } ?: return false
            when {
                isObkMagic(head) -> parseAbsoluteFile(path, sink)
                isSqliteMagic(head) -> parseSqliteFile(path, sink)
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "OBK 文件解析失败", e)
            false
        }
    }

    /**
     * 从内存字节解析 .obk（用于压缩包内小库等仍以字节传入的场景），同样单遍灌入 sink。
     * 与文件版共享同一记录解析逻辑，不产生中间字典副本。
     */
    internal fun parseBookBytes(bytes: ByteArray, sink: (Long, BookMove) -> Unit): Boolean {
        return try {
            when {
                isObkMagic(bytes) -> parseAbsoluteBytes(bytes, sink)
                isSqliteMagic(bytes) -> parseSqliteBytes(bytes, sink)
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "OBK 字节解析失败", e)
            false
        }
    }

    /** 二进制 ABS 库：按 4096 字节数据页流式读取 */
    private fun parseAbsoluteFile(path: String, sink: (Long, BookMove) -> Unit): Boolean {
        var emitted = 0
        java.io.FileInputStream(path).use { fis ->
            val bis = java.io.BufferedInputStream(fis, 64 * 1024)
            val page = ByteArray(PAGE)
            while (true) {
                var off = 0
                while (off < PAGE) {
                    val n = bis.read(page, off, PAGE - off)
                    if (n < 0) break
                    off += n
                }
                if (off < PAGE) break   // 不足一页（末页残缺），结束
                emitted += parsePageRecords(page, sink)
            }
        }
        return emitted > 0
    }

    /** 二进制 ABS 库（内存版）：逐页切片解析 */
    private fun parseAbsoluteBytes(d: ByteArray, sink: (Long, BookMove) -> Unit): Boolean {
        var emitted = 0
        val nPages = d.size / PAGE
        for (p in 0 until nPages) {
            emitted += parsePageRecords(d.copyOfRange(p * PAGE, (p + 1) * PAGE), sink)
        }
        return emitted > 0
    }

    /** 解析一个 4096 字节数据页，把每条记录交给 sink；返回本页产出记录数 */
    private fun parsePageRecords(page: ByteArray, sink: (Long, BookMove) -> Unit): Int {
        var emitted = 0
        if (page.size < 0x186) return 0
        if (!(page[0x17C] == 'A'.code.toByte() && page[0x17D] == 'B'.code.toByte() &&
                    page[0x17E] == 'S'.code.toByte() && page[0x17F] == 'P'.code.toByte())) return 0
        val version = readShortLE(page, 0x184)
        if (version != 0x0A) return 0
        var i = 0x1B0
        while (i + 46 <= PAGE) {
            if (isHexKey(page, i) && page[i + 16] == 0.toByte()) {
                val hex = String(page, i, 16, Charsets.US_ASCII)
                // 16 位十六进制 → Long（两补码位型，与 Zobrist 直算结果一致）
                val vkey = java.lang.Long.parseUnsignedLong(hex, 16)
                val vmove = readIntLE(page, i + 17)
                val vscore = readIntLE(page, i + 21)
                val vwin = readIntLE(page, i + 25)
                val vdraw = readIntLE(page, i + 29)
                val vlost = readIntLE(page, i + 33)
                val vvalid = readIntLE(page, i + 37)
                // vmemo(41) 忽略
                sink(vkey, BookMove(vmove, vscore, vvalid, vwin, vdraw, vlost))
                emitted++
                i += 46
            } else {
                i += 1
            }
        }
        return emitted
    }

    /** 旋风系 SQLite 库：直接只读打开 bhobk 表（不复制临时文件） */
    private fun parseSqliteFile(path: String, sink: (Long, BookMove) -> Unit): Boolean {
        var emitted = 0
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try {
            // ★ 兼容：与句柄直查同一套表发现逻辑（不死认 bhobk）
            val table = findBookTable(db) ?: return false
            val hasValid = columnsOf(db, table).contains("vvalid")
            val validCol = if (hasValid) "vvalid" else "1"
            db.rawQuery(
                "SELECT vkey, vmove, vscore, vwin, vdraw, vlost, $validCol FROM $table WHERE vkey IS NOT NULL AND vmove IS NOT NULL",
                null).use { c ->
                while (c.moveToNext()) {
                    sink(
                        c.getLong(0),
                        BookMove(c.getInt(1), c.getInt(2), c.getInt(6), c.getInt(3), c.getInt(4), c.getInt(5))
                    )
                    emitted++
                }
            }
        } finally {
            db.close()
        }
        return emitted > 0
    }

    /** SQLite 库（内存版）：写临时文件后走文件版解析 */
    private fun parseSqliteBytes(bytes: ByteArray, sink: (Long, BookMove) -> Unit): Boolean {
        val tmp = java.io.File.createTempFile("obk_sqlite", ".db")
        try {
            tmp.writeBytes(bytes)
            return parseSqliteFile(tmp.absolutePath, sink)
        } catch (e: Exception) {
            Log.e(TAG, "OBK SQLite 字节解析失败", e)
            return false
        } finally {
            tmp.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ★ SQLite 大库：只保留只读句柄，按局面 vkey 直查（常数内存，整本多大都不怕）
    //
    //   旋风/极速清风系 .obk 实为 SQLite 库，表 bhobk(vkey,vmove,vscore,vwin,vdraw,vlost,vvalid)。
    //   其 vkey 列混存两种形态（原库转换时遗留）：
    //     · 高位为 0 的键 → SQLite 整数（int64）
    //     · 高位为 1 的键 → 按 8 字节 IEEE-754 位型误存为 REAL(double)
    //   故直查需两种口径各来一发（与极速清风 OpeningBook 的两条 SQL 完全一致）：
    //     CAST(vkey AS INTEGER)=<key>              → 命中整数存储行
    //     CAST(vkey AS DOUBLE) =<Double.fromBits(key)> → 命中位型存储行
    //   并为这两条表达式建索引（一次建好随库持久化），使查询 O(log n) 而非全表扫描。
    // ─────────────────────────────────────────────────────────────

    /** 一个已打开的 SQLite 开局库句柄：只持有连接与行数，不驻留任何数据行 */
    class SqliteBookHandle(
        val db: android.database.sqlite.SQLiteDatabase,
        val rowCount: Int,
        /** 实际开局库表名：优先 bhobk，否则为第一个含 vkey+vmove 列的表（兼容改过表名的库） */
        val table: String = "bhobk",
        /** 是否有 vvalid 列（没有时查询不带该过滤，兼容精简库） */
        val hasValid: Boolean = true
    ) {
        fun close() { try { db.close() } catch (_: Exception) {} }
    }

    /** 列出库内各表的列名，用于失败时如实说明"库里到底有什么表" */
    private fun columnsOf(db: android.database.sqlite.SQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        try {
            db.rawQuery("PRAGMA table_info('$table')", null).use { c ->
                val idx = c.getColumnIndex("name")
                while (c.moveToNext()) if (idx >= 0) c.getString(idx)?.lowercase()?.let { out.add(it) }
            }
        } catch (_: Exception) {}
        return out
    }

    /** 找开局库表：优先名为 bhobk 的表；否则取第一个同时含 vkey 与 vmove 列的表。找不到返回 null */
    private fun findBookTable(db: android.database.sqlite.SQLiteDatabase): String? {
        val tables = ArrayList<String>()
        try {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null)
                .use { c -> while (c.moveToNext()) c.getString(0)?.let { tables.add(it) } }
        } catch (_: Exception) { return null }
        if (tables.isEmpty()) return null
        tables.firstOrNull { it.equals("bhobk", ignoreCase = true) }?.let { return it }
        for (t in tables) {
            val cols = columnsOf(db, t)
            if (cols.contains("vkey") && cols.contains("vmove")) return t
        }
        return null
    }

    /**
     * 若该文件是 SQLite 库，返回"库内表结构"摘要（形如 "bhobk(vkey,vmove,vscore…)"），
     * 否则返回 null。仅用于装载失败时向用户如实说明原因，不改动任何数据。
     */
    internal fun sqliteDiagnosis(path: String): String? {
        return try {
            val f = java.io.File(path)
            if (!f.exists() || f.length() <= 0) return null
            val head = java.io.FileInputStream(f).use { ins ->
                val h = ByteArray(16)
                var off = 0
                while (off < 16) { val n = ins.read(h, off, 16 - off); if (n < 0) break; off += n }
                h.copyOf(off)
            }
            if (!isSqliteMagic(head)) return null
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            try {
                val names = ArrayList<String>()
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' LIMIT 4", null)
                    .use { c -> while (c.moveToNext()) c.getString(0)?.let { names.add(it) } }
                if (names.isEmpty()) return "是 SQLite 库，但库内没有任何用户表"
                names.joinToString("；") { t -> "$t(" + columnsOf(db, t).joinToString(",") + ")" }
            } finally { db.close() }
        } catch (e: Exception) {
            Log.w(TAG, "SQLite 结构诊断失败", e)
            null
        }
    }

    /**
     * 打开 SQLite 开局库（bhobk 表）：仅建句柄 + 取行数，不读任何数据行。
     * 若可写则补齐两个表达式索引；老 SQLite 不支持表达式索引时自动忽略（查询退化为扫描，仍可命中）。
     * 非 bhobk 库 / 打开失败返回 null —— 调用方据此回退到二进制 ABS 流式解析。
     */
    internal fun openSqliteBook(path: String): SqliteBookHandle? {
        var opened: android.database.sqlite.SQLiteDatabase? = null
        try {
            val db = try {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            } catch (e: Exception) {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            }
            opened = db
            // ★ 兼容：不再死认表名 bhobk —— 找不到时退而求其次，认"含 vkey+vmove 列"的表
            val table = findBookTable(db) ?: run {
                Log.w(TAG, "SQLite 库内未找到开局库表(vkey/vmove): ${java.io.File(path).name}")
                db.close(); opened = null; return null
            }
            val hasValid = columnsOf(db, table).contains("vvalid")
            // 表达式索引：一次建好、随库持久化；不支持则忽略
            try { db.execSQL("CREATE INDEX IF NOT EXISTS qf_x_vkey_i ON $table(CAST(vkey AS INTEGER))") } catch (_: Exception) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS qf_x_vkey_d ON $table(CAST(vkey AS DOUBLE))") } catch (_: Exception) {}
            val rows = db.rawQuery("SELECT COUNT(*) FROM $table", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            Log.i(TAG, "SQLite 开局库已就绪(句柄直查): ${java.io.File(path).name}（表=$table，$rows 行，vvalid=$hasValid）")
            return SqliteBookHandle(db, rows, table, hasValid)
        } catch (e: Exception) {
            Log.w(TAG, "非 SQLite 开局库或打开失败，回退二进制解析: $path")
            try { opened?.close() } catch (_: Exception) {}
            return null
        }
    }

    /**
     * 按局面 vkey 直查 SQLite 大库（整数/位型两种口径各查一次），命中记录交给 sink。
     * 全程零整表装载——内存占用与库大小无关。
     */
    internal fun querySqlite(handle: SqliteBookHandle, vkey: Long, sink: (BookMove) -> Unit) {
        val db = handle.db
        val t = handle.table
        val valid = if (handle.hasValid) " AND vvalid=1" else ""
        try {
            db.rawQuery(
                "SELECT vmove, vscore, vwin, vdraw, vlost FROM $t WHERE CAST(vkey AS INTEGER)=?$valid",
                arrayOf(vkey.toString())
            ).use { c ->
                while (c.moveToNext()) sink(BookMove(c.getInt(0), c.getInt(1), 1, c.getInt(2), c.getInt(3), c.getInt(4)))
            }
        } catch (e: Exception) { Log.e(TAG, "SQLite 直查(整数)失败", e) }
        try {
            // vkey 的 8 字节位型即原库里被写成 REAL 的那个 double
            val asDouble = Double.fromBits(vkey).toString()
            db.rawQuery(
                "SELECT vmove, vscore, vwin, vdraw, vlost FROM $t WHERE CAST(vkey AS DOUBLE)=?$valid",
                arrayOf(asDouble)
            ).use { c ->
                while (c.moveToNext()) sink(BookMove(c.getInt(0), c.getInt(1), 1, c.getInt(2), c.getInt(3), c.getInt(4)))
            }
        } catch (e: Exception) { Log.e(TAG, "SQLite 直查(位型)失败", e) }
    }

    private fun isHexKey(seg: ByteArray, i: Int): Boolean {
        if (i + 16 > seg.size) return false
        for (k in 0 until 16) {
            val c = seg[i + k].toInt()
            val valid = (c in '0'.code..'9'.code) || (c in 'A'.code..'F'.code)
            if (!valid) return false
        }
        return true
    }

    private fun readShortLE(b: ByteArray, off: Int): Int {
        if (off + 2 > b.size) return -1
        return (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
    }

    private fun readIntLE(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return (b[off].toInt() and 0xff) or
                ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or
                ((b[off + 3].toInt() and 0xff) shl 24)
    }

    // ─────────────────────────────────────────────────────────────
    // Zobrist：整数棋盘 + 哈希
    // ─────────────────────────────────────────────────────────────
    private const val RED_KING = 0; private const val RED_ADVISOR = 1; private const val RED_ELEPHANT = 2
    private const val RED_KNIGHT = 3; private const val RED_ROOK = 4; private const val RED_CANNON = 5
    private const val RED_PAWN = 6
    private const val BLK_KING = 7; private const val BLK_ADVISOR = 8; private const val BLK_ELEPHANT = 9
    private const val BLK_KNIGHT = 10; private const val BLK_ROOK = 11; private const val BLK_CANNON = 12
    private const val BLK_PAWN = 13

    private fun initIntBoard(): Array<IntArray> {
        val b = Array(10) { IntArray(9) { -1 } }
        b[0][0] = BLK_ROOK; b[0][1] = BLK_KNIGHT; b[0][2] = BLK_ELEPHANT; b[0][3] = BLK_ADVISOR
        b[0][4] = BLK_KING; b[0][5] = BLK_ADVISOR; b[0][6] = BLK_ELEPHANT; b[0][7] = BLK_KNIGHT; b[0][8] = BLK_ROOK
        b[2][1] = BLK_CANNON; b[2][7] = BLK_CANNON
        b[3][0] = BLK_PAWN; b[3][2] = BLK_PAWN; b[3][4] = BLK_PAWN; b[3][6] = BLK_PAWN; b[3][8] = BLK_PAWN
        b[9][0] = RED_ROOK; b[9][1] = RED_KNIGHT; b[9][2] = RED_ELEPHANT; b[9][3] = RED_ADVISOR
        b[9][4] = RED_KING; b[9][5] = RED_ADVISOR; b[9][6] = RED_ELEPHANT; b[9][7] = RED_KNIGHT; b[9][8] = RED_ROOK
        b[7][1] = RED_CANNON; b[7][7] = RED_CANNON
        b[6][0] = RED_PAWN; b[6][2] = RED_PAWN; b[6][4] = RED_PAWN; b[6][6] = RED_PAWN; b[6][8] = RED_PAWN
        return b
    }

    private fun sq(row: Int, col: Int) = ((row + 3) shl 4) or (col + 3)

    private fun computeVkey(board: Array<IntArray>, redToMove: Boolean): Long {
        var lock = 0L
        for (r in 0 until 10) for (c in 0 until 9) {
            val t = board[r][c]
            if (t >= 0) {
                lock = lock xor ObkZobrist.TABLE[t * 256 + sq(r, c)]
            }
        }
        if (redToMove) lock = lock xor ObkZobrist.ZOBRIST_PLAYER
        return lock
    }

    internal fun sqToUcci(sq: Int): String {
        val row = (sq shr 4) - 3
        val col = (sq and 0xF) - 3
        return (('a'.code + col).toChar()).toString() + (9 - row)
    }

    internal fun decodeMove(vmove: Int): String? {
        val frm = (vmove ushr 8) and 0xFF
        val to = vmove and 0xFF
        if (!(0x33 <= frm && frm <= 0xcb && 0x33 <= to && to <= 0xcb)) return null
        return sqToUcci(frm) + sqToUcci(to)
    }

    /**
     * 对给定棋盘直接计算 OBK 的 vkey（Long，与库内索引键一致）。
     * 整本库任意局面都能 O(1) 命中。
     */
    fun vkeyOf(board: Board): Long {
        val ib = Array(10) { IntArray(9) { -1 } }
        for (r in 0..9) for (c in 0..8) {
            val p = board.pieceAt(r, c) ?: continue
            ib[r][c] = pieceToInt(p)
        }
        return computeVkey(ib, board.currentPlayer == com.pikafish.chess.model.PieceColor.RED)
    }

    /** Board Piece → OBK 内部 int 编号（红0-6、黑7-13）；null → -1 */
    private fun pieceToInt(piece: com.pikafish.chess.model.Piece?): Int {
        if (piece == null) return -1
        val ordinal = piece.type.ordinal // KING0 ADVISOR1 ELEPHANT2 KNIGHT3 ROOK4 CANNON5 PAWN6
        return if (piece.color == com.pikafish.chess.model.PieceColor.RED) ordinal else ordinal + 7
    }
}
