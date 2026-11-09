package com.pikafish.chess.engine

import com.pikafish.chess.model.Board

/**
 * ★★★ 象眼(ElephantEye)二进制开局库的"局面哈希 + 着法解码"复刻 ★★★
 *
 * 背景：用户手上的 BOOK.DAT（如 9894…BOOK.DAT）并非冰河五四的文本库，
 * 而是知心童/象眼引擎的私有二进制开局库。每个记录 8 字节：
 *   dwZobristLock(u32 LE) + wmv(u16 LE 着法) + wvl(u16 LE 分值)
 * 记录按 dwZobristLock 严格升序排序，查询用二分。
 *
 * 关键：这个 Zobrist 是"固定种子"生成、完全确定的：
 *   用一个全 0 密钥初始化 RC4，按固定顺序吐出 32 位随机数：
 *     - 先是 走子方 zobrPlayer（key/lock0/lock1 各一）
 *     - 再是 14 行 x 256 格的 zobrTable（每格 key/lock0/lock1）
 *   局面 lock1 = 每枚棋子在对应表行(红方 0-6/黑方 7-13)对应格子的 lock1 逐位异或；
 *   轮到黑方走再额外异或 zobrPlayer.lock1。
 *
 * 因此只要复刻这套 RC4 生成与"局面→lock1"计算，就能直接二分检索该库文件，
 * 并把库里的 wmv（象眼着法码）解码回 App 的 UCI（如 b2e2）。
 *
 * 已用用户上传的二进制 BOOK.DAT 实测验证：标准初始局面 lock1=0x052656d5
 * 恰命中国库（28 条开库着法），且解码出的都是合法红方开局着法。
 */
object ElephantEyeCrypt {

    // 棋子符号 -> 类型(与象眼 PIECE_TYPE 一致：0帅/1仕/2相/3马/4车/5炮/6兵)
    private val PIECE_T = mapOf(
        'K' to 0, 'A' to 1, 'B' to 2, 'N' to 3, 'R' to 4, 'C' to 5, 'P' to 6
    )

    @Volatile private var initialized = false

    // 14 行 x 256 格的 lock1 表（红方行 0-6，黑方行 7-13）
    private val zobrLock1 = Array(14) { IntArray(256) }
    private var playerLock1 = 0

    // ---------- RC4 状态（象眼 rc4prng.h） ----------
    private val rc4s = IntArray(256) { it }
    private var rc4x = 0
    private var rc4y = 0

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // 全 0 密钥初始化（对应 RC4Struct::InitZero → dwKey=0，4 字节）
            var j = 0
            for (i in 0 until 256) {
                j = (j + rc4s[i]) and 0xFF   // key[i % 4] = 0
                val t = rc4s[i]; rc4s[i] = rc4s[j]; rc4s[j] = t
            }
            rc4x = 0; rc4y = 0
            playerLock1 = rc4NextLong()
            for (i in 0 until 14) {
                for (k in 0 until 256) {
                    rc4NextLong(); rc4NextLong()          // 丢弃 key/lock0
                    zobrLock1[i][k] = rc4NextLong()       // 取 lock1
                }
            }
            initialized = true
        }
    }

    private fun rc4Next(): Int {
        rc4x = (rc4x + 1) and 0xFF
        rc4y = (rc4y + rc4s[rc4x]) and 0xFF
        val t = rc4s[rc4x]; rc4s[rc4x] = rc4s[rc4y]; rc4s[rc4y] = t
        return rc4s[(rc4s[rc4x] + rc4s[rc4y]) and 0xFF]
    }

    private fun rc4NextLong(): Int =
        rc4Next() or (rc4Next() shl 8) or (rc4Next() shl 16) or (rc4Next() shl 24)

    /**
     * 由 App 的局面 FEN 计算象眼 lock1（32 位无符号，返回 0..2^32-1 的 Long）。
     * @param mirror true=对局面做 180°(254-sq) 镜像后再算 lock1（象眼 BookFile 的两趟搜索）
     */
    fun lock1OfFen(fen: String, mirror: Boolean = false): Long {
        ensureInit()
        val parts = fen.trim().split(' ')
        val boardPart = parts.getOrElse(0) { "" }
        val side = parts.getOrElse(1) { "w" }
        val rows = boardPart.split('/')
        var lock = 0L
        for ((r, row) in rows.withIndex()) {
            val y = 3 + r               // 象眼 RANK 坐标
            var x = 3                   // 象眼 FILE 坐标（a=3 … i=11）
            for (ch in row) {
                if (ch in '1'..'9') {
                    x += ch - '0'
                    continue
                }
                val isRed = ch.isUpperCase()
                val pt = PIECE_T[ch.uppercaseChar()] ?: continue
                val tbl = if (isRed) pt else pt + 7
                var sq = x + (y shl 4)
                if (mirror) sq = 254 - sq
                lock = lock xor (zobrLock1[tbl][sq].toLong() and 0xFFFFFFFFL)
                x++
            }
        }
        if (side == "b") lock = lock xor (playerLock1.toLong() and 0xFFFFFFFFL)
        return lock and 0xFFFFFFFFL
    }

    /** 由棋盘对象直接算 lock1（内部转 FEN，避免调用方重复） */
    fun lock1OfBoard(board: Board, mirror: Boolean = false): Long =
        lock1OfFen(board.toFen(), mirror)

    /**
     * 象眼着法码 wmv → App UCI（如 0x8393 → "a3a4"）。
     * 与象眼 MOVE_COORD 一致：src=wmv&255, dst=wmv>>8；file=(sq&15)-3+'a'，rank='9'-(sq>>4)+3。
     */
    fun wmvToUci(wmv: Int): String {
        val src = wmv and 0xFF
        val dst = (wmv ushr 8) and 0xFF
        val sf = ('a' + ((src and 0x0F) - 3)).toChar()
        val sr = ('9' - (src ushr 4) + 3).toChar()
        val df = ('a' + ((dst and 0x0F) - 3)).toChar()
        val dr = ('9' - (dst ushr 4) + 3).toChar()
        return "" + sf + sr + df + dr
    }

    /** 对着法码做 180° 镜像（象眼 MOVE_MIRROR = MOVE(SQUARE_MIRROR(src), SQUARE_MIRROR(dst))） */
    fun mirrorWmv(wmv: Int): Int {
        val src = wmv and 0xFF
        val dst = (wmv ushr 8) and 0xFF
        return (254 - src) + ((254 - dst) shl 8)
    }
}