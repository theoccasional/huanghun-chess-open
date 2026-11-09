package com.pikafish.chess.qipu

import java.nio.charset.Charset

/**
 * QylDecoder —— 安卓端直接识读「启阳象棋(QYL)」棋谱（.qyl）的独立解码器。
 *
 * 与 CblDecoder / XqfDecoder 同属宝库的二进制棋谱解码家族：产出一批
 * [CblDecoder.CblGame]，供清单列表、全局搜索与主界面回放共用（走 UCI，一行一行解）。
 *
 * ── 文件格式（已用真实 .qyl 逆向验证）────────────────────────────
 *   · 容器头：`"QYLY"`(4B) + version(u16) ，随后是逐局的 `[u64 压缩长度][zstd 帧]` 序列；
 *     文件末尾另有一帧「赛事索引帧」（无长度前缀，其后跟一个 u64 指向自己的偏移），
 *     里面存着赛事名与全部对局索引。为了兼容不同变体，本解码器**以 zstd 魔数
 *     (28 B5 2F FD) 逐帧扫描**为主，不依赖长度前缀，遇到带前缀/不带前缀的帧都能吃下。
 *   · 每帧是标准 zstd 流，解压后为一局：
 *       u16 帧标记(=1) + u64 姓名长度 + 姓名(UTF-8) + 1B flag + u64 v [+ FEN] + u64 条数 + 6B + 着法记录区
 *     姓名形如 `"上海 顾博文 和 广东 程宇东"`（红队 红名 结果 黑队 黑名）；残局书里是棋题名
 *     （如 `"兵卒残局　第10局：三高兵必胜士象全"`）。
 *   · ★ flag 分两型（已用真实 .qyl 逆向验证，二者缺一就会"残局书读不出回合"）：
 *       flag=0 → 标准开局：v 就是"记录条数"，着法起点在该 u64 之后固定 +6；
 *       flag=1 → 残局/排局：v 是内嵌 FEN（棋子板 + " w"/" b"）的字节长度，FEN 之后才是"记录条数"。
 *     两种帧的着法记录区起点统一为：`条数 u64` 之后 + 2B + `FF FF FF FF` = 条数u64起点 + 14。
 *   · 着法记录区：每条记录 13B 头（u64 kind + u32 手数 + u8 标记 [+ u64 文本长度 + 文本]）
 *     后紧跟 6B `u16 走子 + u32 序号`；序号从 0 起连续递增，走子字节为 `低8位=起点`、`高8位=终点`，
 *     起点/终点 = row*9+col（row0 为黑方底线，与 App 的 FEN 行序一致）。kind=2 的记录头另多 4B。
 *   · ★ kind=0 是【纯讲解块】而非变着分支：`u64 kind(=0)` + `u8 标记(=1)` + `u64 文本长度` + 文本。
 *     残局/排局书里这类帧整局只有一段棋题说明（无着法），必须整体跳过并留存讲解文字 ——
 *     否则中文会被当成走子记录误判，或干脆把整帧丢掉（真机表现就是"这一则什么都没有"）。
 *   · ★ 着法必须从**内嵌 FEN** 起算合法性（照标准开局校验会在第 1 着就崩）——这是
 *     《象棋残局基础》《象棋精妙残局大观》这类排局/残局书能出回合的关键。
 *   · 稳健性：逐着对着法做**合法性校验**（按象棋规则），一旦某帧结构异常，就在
 *     **整帧剩余部分**里向前 resync 找回主着序列（窗口不能只给几百字节：残局帧常带
 *     数百字节中文讲解，窗口太窄就够不着第一条着法）。始终不抛异常，最坏只丢个别着法。
 *
 * ── zstd 解压（正是"真机非要能开"的关键）──────────────────────────
 *   用 **aircompressor 纯 Java 实现**，不用 zstd-jni：
 *     · zstd-jni 的 .so 是 glibc 构建（NEEDED libc.so.6），Android 用 bionic，`System.load`
 *       直接失败；且 .so 作为 Java 资源会被 AGP 打包剔除 —— 真机必定"打不开"。
 *     · 纯 Java 无原生依赖，全 ABI 通吃。
 *   另有一条铁律：喂给 aircompressor 的**必须是这一帧的精确字节数**，多一个字节它就会
 *   把帧后残余（长度前缀/偏移指针）当成下一帧头而报 "Invalid magic prefix"。故实现里
 *   [zstdFrameLength] 按帧头/块头精确算长，绝不用"从这到文件尾"。
 *
 * 坐标换算：索引 i → row=i/9, col=i%9 → UCI = ('a'+col) + ('0'+(9-row))，与 [com.pikafish.chess.model.Position] 一致。
 */
object QylDecoder {

    private const val STD_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"

    /** 纯 Java zstd 解压器（线程内复用；单次调用无状态）。 */
    private val zstd = io.airlift.compress.zstd.ZstdDecompressor()

    /** 解压输出上限（防病态帧把内存吃光）。 */
    private const val MAX_OUT = 64 shl 20

    /** 内容识别：容器头四字节固定为 "QYLY"。 */
    fun isQyl(raw: ByteArray): Boolean =
        raw.size >= 8 &&
            raw[0].toInt() == 'Q'.code && raw[1].toInt() == 'Y'.code &&
            raw[2].toInt() == 'L'.code && raw[3].toInt() == 'Y'.code

    /**
     * ★ QYL「家族」嗅探（比 [isQyl] 宽）：命中 `QYLY` 头，**或** 文件头部附近出现 zstd 帧魔数
     *   (28 B5 2F FD)，都判为 QYL 家族。
     *
     * 为什么需要它：同一个软件的导出文件，容器头可能随版本变化（没有 QYLY、或魔数不同）。
     * 只认死 `QYLY` 的话，用户换一个 .qyl 文件就会"整册读不出来"——正是"这局能开、别局就蒙"的根源。
     * 这里改用**内容特征**（容器里确实是 zstd 帧）来兜底，换文件照样能开。
     * 注意：调用方一律把它放在 CBL/XQF 等既有解码**之后**，且只在解出棋局时才采用，不会误抢。
     */
    fun looksLikeQyl(raw: ByteArray): Boolean {
        if (isQyl(raw)) return true
        if (raw.size < 32) return false
        val last = minOf(raw.size - 4, 2048)          // 只探头部：真容器第一帧必在前 2KB 内
        var i = 0
        while (i <= last) {
            if (isZstd(raw, i)) return true
            i++
        }
        return false
    }

    /** 全量解码：一册 .qyl → 若干局（含赛事名、红黑双方、结果与全部着法）。 */
    fun decode(raw: ByteArray, bookName: String): List<CblDecoder.CblGame> {
        val frames = splitFrames(raw)
        if (frames.isEmpty()) return emptyList()

        val rawGames = ArrayList<RawGame>(frames.size)
        var event = ""
        for (f in frames) {
            val g = parseGame(f)
            if (g != null) rawGames.add(g)
            else {
                val ev = parseEventName(f)
                if (ev.isNotEmpty()) event = ev
            }
        }
        if (rawGames.isEmpty()) return emptyList()

        val out = ArrayList<CblDecoder.CblGame>(rawGames.size)
        rawGames.forEachIndexed { i, g ->
            val (red, black, result) = splitPlayers(g.title)
            out.add(
                CblDecoder.CblGame(
                    index = i + 1,
                    event = event.ifBlank { bookName },
                    site = "",
                    date = "",
                    red = red,
                    black = black,
                    result = result,
                    fenBoard = g.fenBoard,
                    moversBlack = g.moversBlack,
                    movesUci = g.uci,
                    introComment = g.comment,
                    endComment = "",
                    midComments = emptyList(),
                    moveComments = emptyMap(),
                    commentaryBlocks = emptyList()
                )
            )
        }
        return out
    }

    // ══════════════════ 容器：切出全部 zstd 帧 ══════════════════

    /** 一帧 zstd 的魔数。 */
    private fun isZstd(raw: ByteArray, p: Int): Boolean =
        p >= 0 && p + 4 <= raw.size &&
            raw[p] == 0x28.toByte() && raw[p + 1] == 0xB5.toByte() &&
            raw[p + 2] == 0x2F.toByte() && raw[p + 3] == 0xFD.toByte()

    /**
     * 切帧：优先走「QYLY 头 + [u64 长度][帧]」容器；末尾无长度前缀的赛事索引帧也能吃下。
     * 容器形态不符时回退到整文件 zstd 魔数扫描 —— 无论带不带长度前缀都能取出全部帧。
     */
    private fun splitFrames(raw: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (isQyl(raw)) walkSizedContainer(raw, 6, out)
        if (out.isEmpty()) magicScan(raw, out)
        return out
    }

    /** 按 `[u64 长度][zstd 帧]` 顺序读取；末尾若是裸 zstd 帧（赛事索引帧）也一并解出。 */
    private fun walkSizedContainer(raw: ByteArray, start: Int, out: MutableList<ByteArray>) {
        var pos = start
        while (pos + 4 <= raw.size) {
            if (isZstd(raw, pos)) {
                // 末尾赛事索引帧：无长度前缀 → 按帧结构算出精确长度再解
                val len = zstdFrameLength(raw, pos)
                if (len > 0) decompressAt(raw, pos, len)?.let { out.add(it) }
                return
            }
            if (pos + 8 > raw.size) return
            val size = u64(raw, pos)
            if (size <= 0 || size > (raw.size - pos - 8).toLong()) return
            val frame = decompressAt(raw, pos + 8, size.toInt()) ?: return
            out.add(frame)
            pos = pos + 8 + size.toInt()
        }
    }

    /**
     * 整文件魔数扫描（容器形态不含长度前缀时的兜底）：逐个魔数位置按【精确帧长】解帧，
     * 解出一帧就跳过整帧，伪魔数解压失败即跳过。
     */
    private fun magicScan(raw: ByteArray, out: MutableList<ByteArray>) {
        var i = 0
        while (i + 4 <= raw.size) {
            if (!isZstd(raw, i)) { i++; continue }
            val len = zstdFrameLength(raw, i)
            if (len > 0) {
                val data = decompressAt(raw, i, len)
                if (data != null) { out.add(data); i += len; continue }
            }
            i += 4
        }
    }

    /**
     * 从 raw[off] 起，对长度恰好为 [len] 的一段解一个 zstd 帧。
     *
     * ★ 长度必须精确到帧尾：aircompressor 视帧后残余为"下一帧头"而报 Invalid magic prefix。
     * 输出缓冲优先取帧头声明的解压后大小（单段帧自带），取不到再逐级放大重试。失败返回 null。
     */
    private fun decompressAt(raw: ByteArray, off: Int, len: Int): ByteArray? {
        if (off < 0 || len <= 0 || off + len > raw.size) return null
        var cap = try {
            io.airlift.compress.zstd.ZstdDecompressor.getDecompressedSize(raw, off, len).toInt()
        } catch (e: Throwable) { 0 }
        if (cap <= 0 || cap > MAX_OUT) cap = 256 shl 10
        var tries = 0
        while (tries++ < 8) {
            try {
                val out = ByteArray(cap)
                val n = zstd.decompress(raw, off, len, out, 0, out.size)
                return if (n == out.size) out else out.copyOf(n)
            } catch (e: Throwable) {
                if (cap >= MAX_OUT) return null
                cap = minOf(cap * 4, MAX_OUT)
            }
        }
        return null
    }

    /**
     * 算出一帧 zstd 的精确字节长度（解析帧头 + 逐块头）；结构异常返回 -1。
     *
     * 帧头：魔数(4) + FHD(1) + [窗口描述符(1)，仅非单段帧] + Frame_Content_Size(0/1/2/4/8)
     *        + [Dictionary_ID(0/1/2/4)]；
     * 块区：每块 3 字节头（bit0=末块、bit1-2=类型、bit3+=内容长），压缩/原块内容长=该值，
     *        RLE 块内容恒为 1 字节。逐块累加至末块即帧尾。
     */
    private fun zstdFrameLength(raw: ByteArray, off: Int): Int {
        var p = off + 4
        if (p >= raw.size) return -1
        val fhd = raw[p].toInt() and 0xFF; p++
        val single = (fhd shr 5) and 1 == 1
        val fcsFlag = (fhd shr 6) and 3
        val did = fhd and 3
        if (!single) p++                                  // Window_Descriptor
        p += when (fcsFlag) { 0 -> if (single) 1 else 0; 1 -> 2; 2 -> 4; else -> 8 }
        p += when (did) { 1 -> 1; 2 -> 2; 3 -> 4; else -> 0 }
        while (p + 3 <= raw.size) {
            val h = (raw[p].toInt() and 0xFF) or
                ((raw[p + 1].toInt() and 0xFF) shl 8) or
                ((raw[p + 2].toInt() and 0xFF) shl 16)
            p += 3
            val last = h and 1
            val type = (h shr 1) and 3
            val size = h shr 3
            p += if (type == 1) 1 else size               // RLE 块内容恒 1 字节
            if (p > raw.size) return -1
            if (last == 1) return p - off
        }
        return -1
    }

    // ══════════════════ 单帧：赛事索引帧的赛事名 ══════════════════

    /** 从赛事索引帧取赛事名：`u64 姓名长度 + UTF-8 文本`。非索引帧返回空串。 */
    private fun parseEventName(o: ByteArray): String {
        if (o.size < 12) return ""
        val len = u64(o, 0)
        if (len <= 4 || len > 400) return ""
        val end = 8 + len.toInt()
        if (end > o.size) return ""
        val s = String(o, 8, len.toInt(), Charset.forName("UTF-8"))
        return if (s.indexOf('\uFFFD') >= 0) "" else s
    }

    // ══════════════════ 单帧：一局棋 ══════════════════

    private class RawGame(
        val title: String, val uci: List<String>,
        val fenBoard: String?, val moversBlack: Boolean,
        val comment: String
    )

    /** 解析一帧为棋局；不是棋局帧（如赛事索引帧）返回 null。 */
    private fun parseGame(o: ByteArray): RawGame? {
        if (o.size < 20) return null
        val nlen = u64(o, 2)
        if (nlen <= 0 || nlen >= 400 || 10 + nlen >= o.size.toLong()) return null
        val title = String(o, 10, nlen.toInt(), Charset.forName("UTF-8"))
        if (title.indexOf('\uFFFD') >= 0) return null
        val p2 = 10 + nlen.toInt()
        if (p2 + 9 > o.size) return null

        // ★ 帧头两型：flag=0 标准开局（紧随 u64 即「记录条数」）；flag=1 残局/排局（u64 是 FEN 长度，
        //   FEN 之后才是「记录条数」）。两型的着法记录区都从「条数 u64」起点 +14 开始。
        var fenBoard: String? = null
        var moversBlack = false
        val flag = o[p2].toInt() and 0xFF
        val v = u64(o, p2 + 1)
        var countPos = p2 + 1
        if (flag == 1 && v in 20L..120L && p2 + 9 + v <= o.size.toLong()) {
            val cand = String(o, p2 + 9, v.toInt(), Charset.forName("UTF-8"))
            val t = cand.trim()
            if (t.count { it == '/' } == 9 && (t.endsWith(" w") || t.endsWith(" b"))) {
                fenBoard = t.substringBeforeLast(' ').trim()
                moversBlack = t.endsWith(" b")
                countPos = p2 + 9 + v.toInt()
            }
        }
        if (countPos + 14 > o.size) return null
        val cnt = u64(o, countPos)
        if (cnt <= 0 || cnt >= 4000) return null

        val board = if (fenBoard != null) loadFen(fenBoard!!) else loadStd()
        if (board == null) return null

        val moves = ArrayList<String>(minOf(cnt.toInt(), 256))
        val intro = StringBuilder()
        var exp = 0
        var pos = countPos + 14
        // ★ 循环不以 `cnt` 为止：残局/排局书里"纯讲解帧"的 cnt 常写作 1（本就没有着法），
        //   用它当循环上界会把这类帧的讲解一并跳过。改为"能续就续，续不动即停"——
        //   每轮都要求「序号==exp 且走子合法」，一旦接不上自会 break，不会误吃后面的元数据。
        while (pos + 13 <= o.size) {
            val kind = u64(o, pos)
            if (kind == 1L || kind == 2L) {
                val tag = o[pos + 12].toInt() and 0xFF
                var mpos = pos + 13 + (if (kind == 2L) 4 else 0)
                if (tag == 1) {
                    if (mpos + 8 > o.size) { pos += 1; continue }
                    val slen = u64(o, mpos)
                    if (slen < 0 || slen > (o.size - mpos - 8).toLong()) { pos += 1; continue }
                    mpos = mpos + 8 + slen.toInt()
                }
                if (mpos + 6 <= o.size) {
                    val mv = u16(o, mpos); val idx = u32(o, mpos + 2)
                    val fr = mv and 0xFF; val to = mv ushr 8
                    if (idx.compareTo(exp) == 0 && okMove(board, fr, to)) {
                        apply(board, fr, to); moves.add(uci(fr, to)); exp++; pos = mpos + 6; continue
                    }
                }
            } else if (kind == 0L) {
                // ★ 纯注释块（本局无着法的"棋题说明"帧）：`kind u64` + `tag u8` + `u64 文本长度` + 文本。
                //   残局/排局书里这类帧整局只有一段讲解（如《学点排局》的起手说明），
                //   必须把它当注释块整体跳过，既拿到讲解文字，又不会把中文当成走子记录误判。
                val tag = o[pos + 8].toInt() and 0xFF
                val tlen = u64(o, pos + 9)
                if (tag == 1 && tlen > 0 && tlen < (o.size - pos - 17).toLong()) {
                    if (intro.isNotEmpty()) intro.append('\n')
                    intro.append(String(o, pos + 17, tlen.toInt(), Charset.forName("UTF-8")))
                    pos = pos + 17 + tlen.toInt(); continue
                }
            }
            // resync：向前扫描，找下一条「序号 == exp 且走子合法」的记录。
            // ★ 扫描窗口必须是【整帧剩余部分】而非固定的 pos+400：残局/排局帧在着法前常带
            //   长达数百字节的中文讲解，400 字节根本够不着第一条着法 —— 正是"残局书读不出回合"的根源。
            var found = false
            var p = pos + 1
            val limit = o.size - 6
            while (p < limit) {
                val idx = u32(o, p + 2)
                if (idx.compareTo(exp) == 0) {
                    val mv = u16(o, p); val fr = mv and 0xFF; val to = mv ushr 8
                    if (okMove(board, fr, to)) {
                        apply(board, fr, to); moves.add(uci(fr, to)); exp++; pos = p + 6; found = true; break
                    }
                }
                p++
            }
            if (!found) break
        }
        if (moves.isEmpty() && intro.isEmpty() && cnt > 1) return null
        return RawGame(title, moves, fenBoard, moversBlack, intro.toString().trim())
    }

    /** 姓名 → (红方, 黑方, 结果)。`"上海 顾博文 和 广东 程宇东"` → ("上海 顾博文","广东 程宇东","1/2-1/2")。
     *  非对局式标题（残局/排局的棋题名，如 `"兵卒残局　第10局：三高兵必胜士象全"`）→ 整串作红方名。 */
    private fun splitPlayers(title: String): Triple<String, String, String> {
        val toks = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var ri = -1
        for (i in toks.indices) if (toks[i] == "胜" || toks[i] == "负" || toks[i] == "和") { ri = i; break }
        if (ri > 0) {
            val red = toks.subList(0, ri).joinToString(" ")
            // 黑方名后常缀「2026-09-25 04-35」式日期时间，一并剔除，免得显示成"人名+日期"
            val tail = toks.subList(ri + 1, toks.size)
                .dropLastWhile { it.matches(Regex("\\d{2,4}(-\\d{2}){1,2}")) }
            val black = tail.joinToString(" ")
            val res = when (toks[ri]) {
                "胜" -> "1-0"
                "负" -> "0-1"
                else -> "1/2-1/2"
            }
            return Triple(red, black, res)
        }
        return Triple(toks.firstOrNull() ?: "", if (toks.size > 1) toks.last() else "", "*")
    }

    // ══════════════════ 棋盘 / 着法合法性（自足实现，避免主线程依赖）══════════════════

    private fun loadStd(): IntArray {
        val b = IntArray(90)
        var r = 0
        for (row in STD_FEN.split('/')) {
            var c = 0
            for (ch in row) {
                if (ch.isDigit()) c += ch - '0' else { b[r * 9 + c] = ch.code; c++ }
            }
            r++
        }
        return b
    }

    /** 解析残局帧内嵌的棋子板 FEN（10 行、每行 9 格）；结构不合法返回 null。 */
    private fun loadFen(fen: String): IntArray? {
        val rows = fen.split('/')
        if (rows.size != 10) return null
        val b = IntArray(90)
        for (r in 0 until 10) {
            var c = 0
            for (ch in rows[r]) {
                if (ch in '0'..'9') c += ch - '0'
                else { if (c > 8) return null; b[r * 9 + c] = ch.code; c++ }
            }
            if (c != 9) return null
        }
        return b
    }

    private fun apply(b: IntArray, fr: Int, to: Int) {
        b[to] = b[fr]; b[fr] = 0
    }

    private fun uci(fr: Int, to: Int): String = uciOf(fr) + uciOf(to)

    private fun uciOf(i: Int): String {
        val row = i / 9; val col = i % 9
        return "" + ('a' + col) + ('0' + (9 - row))
    }

    /** 走子合法性：与象棋规则一致（含马腿/象眼/塞象眼/炮架/过河兵/九宫）。 */
    private fun okMove(b: IntArray, fr: Int, to: Int): Boolean {
        if (fr < 0 || fr >= 90 || to < 0 || to >= 90 || fr == to) return false
        val r1 = fr / 9; val c1 = fr % 9; val r2 = to / 9; val c2 = to % 9
        val pc = b[fr]
        if (pc == 0) return false
        val target = b[to]
        val p = pc.toChar().uppercaseChar()
        val red = pc.toChar().isUpperCase()
        if (target != 0 && target.toChar().isUpperCase() == red) return false
        val dr = r2 - r1; val dc = c2 - c1
        when (p) {
            'R' -> {
                if (dr != 0 && dc != 0) return false
                if (dr == 0) {
                    val s = if (dc > 0) 1 else -1
                    var c = c1 + s
                    while (c != c2) { if (b[r1 * 9 + c] != 0) return false; c += s }
                    return true
                }
                val s = if (dr > 0) 1 else -1
                var r = r1 + s
                while (r != r2) { if (b[r * 9 + c1] != 0) return false; r += s }
                return true
            }
            'C' -> {
                if (dr != 0 && dc != 0) return false
                var cnt = 0
                if (dr == 0) {
                    val s = if (dc > 0) 1 else -1
                    var c = c1 + s
                    while (c != c2) { if (b[r1 * 9 + c] != 0) cnt++; c += s }
                } else {
                    val s = if (dr > 0) 1 else -1
                    var r = r1 + s
                    while (r != r2) { if (b[r * 9 + c1] != 0) cnt++; r += s }
                }
                return if (target == 0) cnt == 0 else cnt == 1
            }
            'N' -> {
                val lr: Int; val lc: Int
                if (kotlin.math.abs(dr) == 2 && kotlin.math.abs(dc) == 1) { lr = r1 + dr / 2; lc = c1 }
                else if (kotlin.math.abs(dr) == 1 && kotlin.math.abs(dc) == 2) { lr = r1; lc = c1 + dc / 2 }
                else return false
                return b[lr * 9 + lc] == 0
            }
            'B' -> {
                if (kotlin.math.abs(dr) != 2 || kotlin.math.abs(dc) != 2) return false
                if (b[(r1 + r2) / 2 * 9 + (c1 + c2) / 2] != 0) return false
                return if (red) r2 >= 5 else r2 <= 4
            }
            'A' -> {
                if (kotlin.math.abs(dr) != 1 || kotlin.math.abs(dc) != 1) return false
                return if (red) r2 in 7..9 && c2 in 3..5 else r2 in 0..2 && c2 in 3..5
            }
            'K' -> {
                if (kotlin.math.abs(dr) + kotlin.math.abs(dc) != 1) return false
                return if (red) r2 in 7..9 && c2 in 3..5 else r2 in 0..2 && c2 in 3..5
            }
            'P' -> {
                val fwd = if (red) -1 else 1
                if (dr == fwd && dc == 0) return true
                val crossed = if (red) r1 <= 4 else r1 >= 5
                return crossed && dr == 0 && kotlin.math.abs(dc) == 1
            }
        }
        return false
    }

    // ══════════════════ 小端读取 ══════════════════

    private fun u64(o: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 0..7) v = v or ((o[p + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun u32(o: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 0..3) v = v or ((o[p + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun u16(o: ByteArray, p: Int): Int =
        (o[p].toInt() and 0xFF) or ((o[p + 1].toInt() and 0xFF) shl 8)
}
