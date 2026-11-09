package com.pikafish.chess.qipu

import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * CblDecoder —— Android 端直接识读象棋桥(CBL)原生格式的运行时解码器。
 *
 * 算法由已验证的 convert.py 逐行移植，可直接解码 .cbl 原文件（含 zlib 解压、
 * 记录链定位、初始局面、走法块、元数据），无需预先转为 PGN。
 *
 * 支持三种象棋桥变体：
 *  A. 【旧/单局变体】以 b"CCBridge"(8B)+12B 头开始；多数库在 offset 20 起为一条 zlib 流，
 *     inflate 得整库 blob；个别文件非压缩则以原字节为 blob。blob 是"长度前缀"记录的
 *     序列（P-4 存记录长度 L），每条含 32 字节初始局面块，走法块每 6 字节
 *     `nn 00 <from_byte> <to_byte> xx xx`（num 从 1 递增、第2字节为0）。见 decodeBook 主流程。
 *  B. 【CCBridgeLibrary 棋库变体(CBL 3.0)】头部 15B 为 "CCBridgeLibrary"，自 offset 101952
 *     起每条 "CCBridge Record\\0" 记录按 4096B 对齐排布，一条即一局。着法为递归着法树
 *     （主线 + 变着）。见 decodeLibraryContainer / parseCbrRecord。象棋桥/象棋笔记导入的
 *     多数"棋库"文件属于此类，旧版只当文本抽取致界面无法回放，本版已完整还原。
 *  C. 【裸 CBR 单局(.cbr)】整文件即一条 "CCBridge Record\0" 记录（象棋桥/象棋笔记的
 *     “单局导出 / 另存为”），记录字段布局与棋库内记录完全一致，着法同为递归树
 *     （主线 + 变着）→ 见 decodeStandaloneCbr。旧版不认该魔数、误入 A 的 zlib 路径，
 *     inflate 失败 → 一局都解不出，界面只剩一副标准 32 子、零回合；本版已修正。
 *
 * 关键格式约定（已用真实文件验证）：
 *  * 走法坐标字节：高半字节=列(col0..8)、低半字节综合得行 row=9-(byte&0x0F)，
 *    其中 col0=红方左侧、row0=红方底线 —— 与 App 的 UCI(a..i × 0..9) 一致。
 *  * 输出 UCI 供 ChessGame.importPgn() 直接回放，残局另附初始局面 FEN。
 */
object CblDecoder {

    /** 一本书解码得到的棋局。 */
    class CblGame(
        val index: Int,
        val event: String,
        val site: String,
        val date: String,
        val red: String,
        val black: String,
        val result: String,
        val fenBoard: String?,       // 残局初始局面(棋子板)；标准开局为 null
        val moversBlack: Boolean,    // 残局是否黑方先行
        val movesUci: List<String>,  // 每步 4 字符 UCI
        val introComment: String,    // 开局旁白（元数据区域的背景介绍文字）
        val endComment: String,      // 结局旁白（走法块后的总评文字）
        val midComments: List<String>,  // 中局旁白（走法块后按序排布的多段讲解文字）
        val moveComments: Map<Int, String>,  // 每步旁白（索引从0开始；由 midComments 均匀映射而来）
        val commentaryBlocks: List<CommentaryBlock>,  // 逐坨旁白（按出生顺序），含支节标记
        // ★ 变着支线（每条 = 从本局起始局面起的完整 UCI 序列；主线在 movesUci，不重复在此）。
        //   背谱引擎把主线+支线一起入谱 → 同一局面多着可选（变招），支线上每个局面
        //   的后续着法也全在谱上，走到支线里照样能背到底。
        val variationsUci: List<List<String>> = emptyList()
    ) {
        fun players(): String {
            val r = red.takeIf { it.isNotEmpty() && it != "?" } ?: ""
            val b = black.takeIf { it.isNotEmpty() && it != "?" } ?: ""
            return when {
                r.isNotEmpty() && b.isNotEmpty() -> "$r 对 $b"
                r.isNotEmpty() -> r
                b.isNotEmpty() -> b
                else -> ""
            }
        }
        fun title(): String {
            val ev = event.takeIf { it.isNotEmpty() && it != "?" } ?: ""
            val pl = players()
            return when {
                ev.isNotEmpty() && pl.isNotEmpty() -> "$ev · $pl"
                ev.isNotEmpty() -> ev
                pl.isNotEmpty() -> pl
                else -> "对局 $index"
            }
        }
        fun toPgn(): String {
            val sb = StringBuilder()
            sb.append("[Event \"${escape(event)}\"]\n")
            sb.append("[Site \"${escape(site)}\"]\n")
            sb.append("[Date \"${escape(date)}\"]\n")
            sb.append("[Round \"$index\"]\n")
            sb.append("[Red \"${escape(red)}\"]\n")
            sb.append("[Black \"${escape(black)}\"]\n")
            sb.append("[Result \"${escape(result)}\"]\n")
            fenBoard?.let { sb.append("[FEN \"$it ${if (moversBlack) "b" else "w"}\"]\n") }
            sb.append("\n")
            sb.append(renderMovetext())
            return sb.toString().trim()
        }

        /**
         * ★ 走法区渲染：主线 + 全部变着支线（RAV 括号）—— 一个变着都不许丢。
         *
         * 为什么必须这样：`variationsUci` 是 CBL 里"主线之外的所有支线"（每条都是从本局
         * 起始局面起的完整线路）。旧版 toPgn 只写 movesUci，导入端（ChessGame.importPgn）
         * 因此拿不到任何变着，整棵变着树归零 —— 主界面只显示"一根筋"，背谱也只认人脑最后
         * 走过的那一条路，谱上真有的变招（如马八进七后卒3进1/卒7进1）走不出来。
         *
         * 做法：把"主线 + 每条支线"灌进前缀树（主线先入，保证每层第一个孩子即主线延续），
         * 再逐层渲染：每层的"非主线孩子"就地输出 `( {标签} 首着 …后继… )`，位置在该分支点
         * 之后、主线下一手之前，与 importPgn 的 RAV 解析约定严格对齐（括号处已走手数=分支点）。
         */
        private fun renderMovetext(): String {
            class Node { val kids = LinkedHashMap<String, Node>() }
            val root = Node()
            fun insert(line: List<String>) {
                var cur = root
                for (u in line) {
                    val k = u.trim()
                    if (k.isEmpty()) continue
                    cur = cur.kids.getOrPut(k) { Node() }
                }
            }
            insert(movesUci)
            for (v in variationsUci) if (v.isNotEmpty()) insert(v)

            val out = StringBuilder()
            fun altLabel(i: Int): String =
                if (i < 26) ('A' + i).toString() else "${'A' + (i % 26)}${i / 26 + 1}"

            // 渲染某节点之后的"支线尾巴"（该支线首着已由调用方写在括号内）
            fun tail(node: Node): String {
                val b = StringBuilder()
                var cur = node
                while (cur.kids.isNotEmpty()) {
                    val mainUci = cur.kids.keys.first()
                    var ai = 0
                    for ((u, kid) in cur.kids) {
                        if (u == mainUci) continue
                        b.append("( {").append(altLabel(ai)).append("} ").append(u).append(' ')
                        b.append(tail(kid)).append(") ")
                        ai++
                    }
                    b.append(mainUci).append(' ')
                    cur = cur.kids[mainUci]!!
                }
                return b.toString()
            }

            var cur = root
            var ply = 0
            while (cur.kids.isNotEmpty()) {
                val mainUci = cur.kids.keys.first()
                // ① 该分支点上的变着先写（括号处已走手数即分支点）
                var ai = 0
                for ((u, kid) in cur.kids) {
                    if (u == mainUci) continue
                    out.append("( {").append(altLabel(ai)).append("} ").append(u).append(' ')
                    out.append(tail(kid)).append(") ")
                    ai++
                }
                // ② 主线走法（沿用原"回合号+走法"排版，黑先局同样兼容）
                if (ply % 2 == 0) out.append(ply / 2 + 1).append('.').append(mainUci)
                else out.append(' ').append(mainUci)
                out.append(' ')
                ply++
                cur = cur.kids[mainUci]!!
            }
            return out.toString().trim()
        }
        private fun escape(s: String): String = s.replace("\"", "").replace("\n", "")
    }

    /** 一段旁白：text 为内容，isBranch 标识是否为支节（变着）里的旁白 */
    class CommentaryBlock(
        val text: String,
        val isBranch: Boolean
    )

    // ================================================================ 入口
    /** 判定是否为 CCBridgeLibrary 棋库容器：头部 15 字节为 "CCBridgeLibrary"。 */
    fun isLibraryContainer(raw: ByteArray): Boolean =
        raw.size >= 16 && String(raw, 0, 15).startsWith("CCBridgeLibrary")

    // ==================================================================================
    //  D. 【MXQ 单局（象棋巫师 / 象棋演播室引擎对局）】
    //
    //  格式：整文件是一条「长度前缀 + GBK 文本」的 token 流（每段 1 字节长度，0..255），
    //        无压缩、无外层包壳，逐段解包即可，不需要任何猜测与启发式。
    //  字段（索引固定，已用 6923 个真实文件全量验证）：
    //        [0]赛事 [1]日期 [2]"红-和/胜/负-黑" [3]红方id [4]黑方id [5..9]五个数字
    //        [10]结果文字(判和/议和/绝杀/认负…) [11]"Moves"
    //        [12..]着法（每步恰为 "7A-6C" 形式）… 直至 "Ends"，其后 "CommentsEnd"。
    //  坐标：<列0-8><行A-J>-<列0-8><行A-J>；列 0 = 屏幕最左（红方视角），
    //        行 A = 红方底线、行 J = 黑方底线 —— 与 App 的 UCI（'a'..'i' × 0..9，0=红底线）一致。
    //        故 "7A-6C" → "h0g2"（本仓库已对 1,734,473 着逐着做过合法性校验，100% 合法）。
    // ==================================================================================

    private val MXQ_GBK: Charset = Charset.forName("GBK")
    private val MXQ_MOVE = Regex("^[0-9][A-J]-[0-9][A-J]$")

    /** ★ 内容识别 MXQ：能整文件切成 token 流、第 12 段为 "Moves"、且其后至少有一步行棋着法。 */
    fun isMxq(raw: ByteArray): Boolean {
        if (raw.size < 16 || raw.size > 16 * 1024 * 1024) return false
        val ts = mxqTokens(raw) ?: return false
        return mxqHasMoves(ts)
    }

    /**
     * 头部是否为 MXQ 结构：token[11] == "Moves" 且其后（"Ends" 之前）至少有一步行棋着法。
     *
     * ★ 不再强制要求出现 "Ends" —— 多数导出器会写 "Ends"/"CommentsEnd" 收尾，但个别变体无尾标，
     *   旧判据会把这少部分直接漏掉（表现为"文件进了宝库却一局都认不出"）。以"确有坐标着法"为准更稳。
     */
    private fun mxqHasMoves(ts: List<String>): Boolean {
        if (ts.size <= 12 || ts[11] != "Moves") return false
        val end = (12 until ts.size).firstOrNull { ts[it] == "Ends" } ?: ts.size
        for (k in 12 until end) if (MXQ_MOVE.matches(ts[k])) return true
        return false
    }

    /** 长度前缀 GBK token 流；任一段越界（不是这种流式结构）立即返回 null。 */
    private fun mxqTokens(raw: ByteArray): List<String>? {
        val out = ArrayList<String>(minOf(raw.size / 4 + 8, 4096))
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            i++
            if (i + len > raw.size) return null
            out.add(String(raw, i, len, MXQ_GBK))
            i += len
        }
        return out
    }

    /**
     * 全量解码一个 MXQ（含逐着 UCI）。识别失败/结构不符返回 null。
     * 着法以外的 token（如注释）一律跳过，绝不误当走法 —— 保证"解析干净"。
     */
    fun decodeMxq(raw: ByteArray, index: Int = 0): CblGame? {
        val ts = mxqTokens(raw) ?: return null
        if (ts.size <= 11 || ts[11] != "Moves") return null
        val ends = (12 until ts.size).firstOrNull { ts[it] == "Ends" } ?: ts.size
        val moves = ArrayList<String>(maxOf(0, ends - 12))
        for (k in 12 until ends) {
            val m = ts[k]
            if (MXQ_MOVE.matches(m)) moves.add(mxqToUci(m))
        }
        return buildMxqGame(ts, moves, index)
    }

    /**
     * ★ 只取头部元信息（赛事/日期/双方/结果），着法留空 —— 供「一包几千局」的列表与
     *   全局搜索索引秒开（列表只读 title()/meta，绝不触发逐着解码，故千局也不卡）。
     */
    fun decodeMxqMeta(raw: ByteArray, index: Int = 0): CblGame? {
        val ts = mxqTokens(raw) ?: return null
        if (ts.size <= 11 || ts[11] != "Moves") return null
        return buildMxqGame(ts, emptyList(), index)
    }

    /**
     * ★ 一次分词同时完成「是不是 MXQ」+「头部元信息解码」（不是则返回 null）。
     *
     * 供「一个压缩包里几千局」批量建列表用：逐局只切一遍 token，避免 isMxq 切一遍、
     * decodeMxqMeta 又切一遍，把 6000+ 局的建列表耗时砍半。
     */
    fun decodeMxqMetaIfMxq(raw: ByteArray, index: Int = 0): CblGame? {
        if (raw.size < 16 || raw.size > 16 * 1024 * 1024) return null
        val ts = mxqTokens(raw) ?: return null
        if (!mxqHasMoves(ts)) return null
        return buildMxqGame(ts, emptyList(), index)
    }

    private fun buildMxqGame(ts: List<String>, moves: List<String>, index: Int): CblGame {
        val rawDate = ts.getOrNull(1) ?: ""
        val date = mxqDate(rawDate)
        val (red, black) = mxqPlayers(ts.getOrNull(2) ?: "")
        val event = ts.getOrNull(0).orEmpty().ifBlank { "象棋棋谱" }
        // ★ 赛事带上"月-日 时:分"：华山/弈天上同名对手一天内多局，只写赛事+双方无法分辨，
        //   带上时间后列表每一行都能一眼认出来（日期本身仍完整存在 date 字段里供检索）。
        val timeTag = rawDate.split('-').let {
            if (it.size >= 4) "${it[1]}-${it[2]} ${it[3].padStart(4, '0').let { t -> t.substring(0, 2) + ":" + t.substring(2, 4) }}" else ""
        }
        return CblGame(
            index = index,
            event = if (timeTag.isNotEmpty()) "$event $timeTag" else event,
            site = "象棋巫师",
            date = date,
            red = red,
            black = black,
            result = mxqResult(ts),
            fenBoard = null,
            moversBlack = false,
            movesUci = moves,
            introComment = "",
            endComment = "",
            midComments = emptyList(),
            moveComments = emptyMap(),
            commentaryBlocks = emptyList()
        )
    }

    /** "7A-6C" → "h0g2"（列 0..8 → a..i；行 A..J → 0..9，A 为红方底线）。 */
    private fun mxqToUci(m: String): String =
        "" + ('a' + (m[0] - '0')) + (m[1] - 'A') + ('a' + (m[3] - '0')) + (m[4] - 'A')

    /** "22-02-02-1713" → "2022-02-02 17:13"（取不出则原样返回）。 */
    private fun mxqDate(s: String): String {
        val p = s.split('-')
        if (p.size < 4) return s
        val yy = p[0].toIntOrNull() ?: return s
        val t = p[3].padStart(4, '0')
        if (t.length != 4) return s
        return "%04d-%s-%s %s:%s".format(2000 + yy, p[1], p[2], t.substring(0, 2), t.substring(2, 4))
    }

    /** "红(9星)-和-黑(北斗)" → Pair(红, 黑)。 */
    private fun mxqPlayers(s: String): Pair<String, String> {
        val m = Regex("^(.+?)-(?:和|胜|负)-(.+)$").find(s) ?: return s.trim() to ""
        return m.groupValues[1].trim() to m.groupValues[2].trim()
    }

    /** 结果：token[2] 中间的胜负字优先（和/胜/负），否则按 token[10] 的结果文字兜底。 */
    private fun mxqResult(ts: List<String>): String {
        val mid = ts.getOrNull(2)?.let { Regex("^.+?-(和|胜|负)-.+$").find(it)?.groupValues?.get(1) }
        return when (mid) {
            "和" -> "1/2-1/2"
            "胜" -> "1-0"
            "负" -> "0-1"
            else -> when (ts.getOrNull(10)) {
                "判和", "议和", "和棋" -> "1/2-1/2"
                else -> "*"
            }
        }
    }

    /**
     * 解码一本书，raw 为该 .cbl 完整字节流。
     *
     * 若头部为 "CCBridgeLibrary"，按“棋库容器”(CBL 3.0) 处理：自 offset 101952 起每条
     * 4096 字节对齐的 "CCBridge Record" 记录即一局，逐字段还原题名/赛事/红黑/结果/初始局面
     * 与全部着法（主线 + 支线），可直接回放——与象棋桥/象棋笔记的识读结果一致。
     */
    fun decodeBook(raw: ByteArray, hint: String): List<CblGame> {
        if (isLibraryContainer(raw)) return decodeLibraryContainer(raw, hint)
        // ★ 裸 CBR 单局(.cbr)：头 16B 即 "CCBridge Record\0"（象棋桥/象棋笔记的单局导出）。
        //   旧版不认这个魔数，会掉进 A 的 zlib 路径，inflate 失败 → 一局都解不出，
        //   界面就只剩一副标准 32 子、零回合。此处直接按 CBR 记录解析。
        if (hasStandaloneCbr(raw)) {
            val single = decodeStandaloneCbr(raw, hint)
            // ★ 只有解出实打实的着法才算数：某些"棋路/象棋笔记 v1 单局记录"字段偏移与标准记录
            //   不同（初始局面不在 2120、坐标也换成半字节编码），按标准路会解出"零回合"的空局。
            //   此时退回变体解析（扫描初始局面块 + 半字节坐标）。
            if (single.any { it.movesUci.size >= 3 || it.variationsUci.isNotEmpty() }) return single
            val variant = decodeCbrVariant(raw, hint)
            if (variant.any { it.movesUci.size >= 3 }) return variant
            if (single.isNotEmpty()) return single
        }
        val blob = loadBlob(raw)
        val chain = longestChain(blob, candidates(blob))
        val out = ArrayList<CblGame>()
        if (chain.isEmpty()) return out
        var idx = 1
        for (p in chain) {
            if (p < 4) continue
            val len = leU32(blob, p - 4)
            val game = decodeGame(blob, p, len) ?: continue
            if (game.movesUci.size < 3) continue
            val ms = findMoveBlock(blob, p, len) ?: (p + 32)
            val recEnd = minOf(p + len, blob.size)
            // ★ 变着支线：主线走法块之后还有"支线走法串"，逐串挂到父线（已验证算法，
            //   全内置书库实测 17062 串挂上 17041、仅丢弃 21 条噪声）。
            val variations = extractVariations(blob, p, ms, recEnd)
            val meta = extractMeta(blob, p, ms, hint)
            // ★ 旧格式记录的题名是"结构字段"（body+44：i32 长度 + GBK），比启发式挑最长中文段
            //   可靠得多：启发式会把整段旁白误当对局名（导出时就成了超长题名 → 上机闪退）。
            //   有结构题名就优先用，没有才退回启发式。
            val structTitle = legacyStructTitle(blob, p)
            // ★ 专书结构头子解析：题头/红/黑/整篇开场白是 [长度+GBK文本] 连续字段组，
            //   签名匹配才启用（顺炮/屏风马这类专书），不匹配走原启发式 → 对其它书零影响。
            val spec = parseSpecHeader(blob, p, ms)
            val introComment = sanitizeComment(spec?.intro ?: extractIntroComment(blob, p, ms))
            val commentaryBlocks = extractCommentaryBlocks(blob, ms, game.movesUci.size, recEnd)
            // 兼容字段：仅提取主线（非支节）的旁白文本，供按步提示用
            val midComments = commentaryBlocks.filter { !it.isBranch }.map { it.text }
            val endComment = midComments.lastOrNull() ?: ""
            val moveComments = distributeMoveComments(midComments, game.movesUci.size)
            out.add(
                CblGame(
                    index = idx,
                    event = sanitizeComment(
                        spec?.title?.takeIf { it.isNotBlank() } ?: structTitle ?: meta[0]
                    ).ifEmpty { hint },
                    site = meta[1],
                    date = meta[2],
                    red = spec?.red?.takeIf { it.isNotBlank() } ?: meta[3],
                    black = spec?.black?.takeIf { it.isNotBlank() } ?: meta[4],
                    // ★ 结局以记录里的结局码为准（1-0/0-1/1/2）；读不到才退回走法启发式。
                    result = legacyResultTag(blob, p)?.let {
                        when (it) { 1 -> "1-0"; 2 -> "0-1"; else -> "1/2-1/2" }
                    } ?: game.result,
                    fenBoard = game.fenBoard,
                    moversBlack = game.firstIsBlack,
                    movesUci = game.movesUci,
                    introComment = introComment,
                    endComment = endComment,
                    midComments = midComments,
                    moveComments = moveComments,
                    commentaryBlocks = commentaryBlocks,
                    variationsUci = variations
                )
            )
            idx++
        }
        return out
    }

    // ================================================================ blob
    fun loadBlob(raw: ByteArray): ByteArray {
        return try {
            val inflater = Inflater()
            inflater.setInput(raw, 20, raw.size - 20)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray()
        } catch (e: Exception) {
            raw
        }
    }

    // ================================================================ CCBridgeLibrary 棋库 (CBL 3.0)
    // ★ 象棋桥“棋库”(.cbl，头为 "CCBridgeLibrary") 的真实二进制结构 —— 由开源实现 cchess
    //   与象棋笔记(cbl_io.dart / _parseRecordAt / _readStepRecursive) 双向印证后逐字段落地：
    //   · 头：16B "CCBridgeLibrary\0" + 44B + int32 书容量(offset 60) + 512B 书名(UTF-16LE, offset 64)；
    //   · 自 offset 101952 起，每条 "CCBridge Record\0" 记录按 4096 字节对齐排布，一条即一局；
    //   · 记录头 2214B：+180 题名(128)、+692 赛事(64)、+1076 红(64)、+1300 黑(64) 均 UTF-16LE；
    //     +2076 结果(1B: 1-0/0-1/和)、+2116 先行方(H, 1=红)、+2120 初始局面(90B，
    //     每格 0x11..0x17=红 / 0x21..0x27=黑，索引 i -> (x=i%9, y=9-i/9))；
    //   · +2214 起为着法流：先 4B(int，非 0 则再 4B 长度 + UTF-16LE 开局旁白)；
    //     随后递归着法，每步 4B `[flag][seq][from][to]`：flag&1=本线终止、flag&2=其后是变着、
    //     flag&4=本步带旁白(再 4B 长度 + 文本)；坐标 p -> (p%9, 9-p/9)，
    //     与 App 的 UCI(a..i × 0..9) 完全一致(如 炮二平五 = h2e2)。
    //   着法树读法：先读“续着”(下一手)递归，再读“变着”(同一父节点、回退棋盘的兄弟线)递归。
    //   由此主线 + 全部支线(每条均为从起始局面起的完整序列)可 100% 还原，与 cchess 逐着一致。
    //   旧版只把记录当“文本”抽题名、拿不到任何走法 → 界面无法回放；本版彻底修正。

    private val CBR_MAGIC: ByteArray = "CCBridge Record\u0000".toByteArray(Charsets.US_ASCII)
    private const val CBR_HEADER = 2214       // 记录头长度
    private const val CBR_ALIGN = 4096        // 记录对齐粒度
    private const val CBR_LIB_SCAN = 101952   // 棋库中首条记录的偏移(参考实现约定)
    private const val CATALOG_ENTRY = 276     // 棋库目录项定长
    private const val CATALOG_PREFIX = 20     // 目录项中 GUID 之前的定长前缀(5×u32)

    private fun startsWithAt(raw: ByteArray, off: Int, pat: ByteArray): Boolean {
        if (off < 0 || off + pat.size > raw.size) return false
        for (k in pat.indices) if (raw[off + k] != pat[k]) return false
        return true
    }

    private fun indexOfCbrMagic(raw: ByteArray, from: Int): Int {
        var i = maxOf(0, from)
        while (i + CBR_MAGIC.size <= raw.size) {
            if (startsWithAt(raw, i, CBR_MAGIC)) return i
            i++
        }
        return -1
    }

    /** 从 raw[off, off+size) 取 UTF-16LE 文本，遇 0x0000 截断。 */
    private fun utf16z(raw: ByteArray, off: Int, size: Int): String {
        val end = minOf(off + size, raw.size)
        if (off < 0 || off >= end) return ""
        val s = String(raw, off, end - off, Charsets.UTF_16LE)
        val z = s.indexOf('\u0000')
        return (if (z >= 0) s.substring(0, z) else s).trim()
    }

    private fun decodeCbrPos(p: Int): Pair<Int, Int> = (p % 9) to (9 - p / 9)

    /** 变体坐标字节：col = b>>4，row = 9-(b&0x0F)（棋路/象棋笔记 v1 记录用）。 */
    private fun decodeCbrPosNibble(b: Int): Pair<Int, Int> = (b shr 4) to (9 - (b and 0x0F))

    /** 由 90 字节局面块构造棋盘：i -> (x=i%9, y=9-i/9)；0x11..0x17 红，0x21..0x27 黑。 */
    private fun boardFrom90(raw: ByteArray, off: Int): Board {
        val b = Board()
        for (c in 0..8) for (r in 0..9) b.grid[c][r] = null
        for (i in 0 until 90) {
            val at = off + i
            if (at >= raw.size) break
            val v = raw[at].toInt() and 0xFF
            if (v == 0) continue
            val ch = when (v and 0x0F) {
                1 -> 'R'; 2 -> 'N'; 3 -> 'B'; 4 -> 'A'; 5 -> 'K'; 6 -> 'C'; 7 -> 'P'
                else -> continue
            }
            b.grid[i % 9][9 - i / 9] = if (v in 0x10..0x17) ch else ch.lowercaseChar()
        }
        return b
    }

    private fun fenBoardOf(g: Array<Array<Char?>>): String {
        val rows = ArrayList<String>(10)
        for (r in 9 downTo 0) {
            val sb = StringBuilder(); var empty = 0
            for (c in 0..8) {
                val p = g[c][r]
                if (p == null) empty++
                else { if (empty > 0) { sb.append(empty); empty = 0 }; sb.append(p) }
            }
            if (empty > 0) sb.append(empty)
            rows.add(sb.toString())
        }
        return rows.joinToString("/")
    }

    private fun cbrKing(g: Array<Array<Char?>>, red: Boolean): Pair<Int, Int>? {
        val t = if (red) 'K' else 'k'
        for (c in 0..8) for (r in 0..9) if (g[c][r] == t) return c to r
        return null
    }

    private fun countBetween(g: Array<Array<Char?>>, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        var n = 0
        if (x1 == x2) {
            for (y in minOf(y1, y2) + 1 until maxOf(y1, y2)) if (g[x1][y] != null) n++
        } else {
            for (x in minOf(x1, x2) + 1 until maxOf(x1, x2)) if (g[x][y1] != null) n++
        }
        return n
    }

    /**
     * 象棋桥/cchess 的“宽松”走子校验：只查棋子自身走法与目标格颜色，不判将军/自杀
     * (打开棋谱所需的最小校验)。规则严格照搬 cchess piece.py，确保着法树切分与原生一致。
     */
    private fun cbrStepOk(g: Array<Array<Char?>>, fr: Pair<Int, Int>, to: Pair<Int, Int>): Boolean {
        val x1 = fr.first; val y1 = fr.second; val x2 = to.first; val y2 = to.second
        if (x2 !in 0..8 || y2 !in 0..9) return false
        val p = g[x1][y1] ?: return false
        val red = p.isUpperCase()
        val q = g[x2][y2]
        if (q != null && q.isUpperCase() == red) return false
        val dx = x2 - x1; val dy = y2 - y1
        val adx = if (dx < 0) -dx else dx
        val ady = if (dy < 0) -dy else dy
        when (p.uppercaseChar()) {
            'R' -> {
                if (x1 != x2 && y1 != y2) return false
                return countBetween(g, x1, y1, x2, y2) == 0
            }
            'C' -> {
                if (x1 != x2 && y1 != y2) return false
                val c = countBetween(g, x1, y1, x2, y2)
                return (c == 0 && q == null) || (c == 1 && q != null)
            }
            'N' -> {
                if (!((adx == 2 && ady == 1) || (adx == 1 && ady == 2))) return false
                return if (adx == 2) g[(x1 + x2) / 2][y1] == null else g[x1][(y1 + y2) / 2] == null
            }
            'B' -> {
                if (!(adx == 2 && ady == 2)) return false
                if (g[(x1 + x2) / 2][(y1 + y2) / 2] != null) return false
                if (red && y2 > 4) return false
                if (!red && y2 < 5) return false
                return true
            }
            'A' -> {
                if (!(adx == 1 && ady == 1)) return false
                return x2 in 3..5 && (if (red) y2 <= 2 else y2 >= 7)
            }
            'K' -> {
                val ok2 = cbrKing(g, !red)
                if (ok2 != null && x1 == ok2.first && x2 == ok2.first && y2 == ok2.second &&
                    countBetween(g, x1, y1, x2, y2) == 0) return true   // 白脸将
                if (!(x2 in 3..5 && (if (red) y2 <= 2 else y2 >= 7))) return false
                return adx + ady == 1
            }
            'P' -> {
                val fwd = if (red) 1 else -1
                val crossed = if (red) y1 > 4 else y1 < 5
                if (!crossed) return dx == 0 && dy == fwd
                return (adx == 1 && dy == 0) || (dx == 0 && dy == fwd)
            }
        }
        return false
    }

    /** 着法流顺序读取器。 */
    private class CbrReader(val raw: ByteArray, var pos: Int) {
        fun remaining(): Int = raw.size - pos
        fun read(size: Int): ByteArray {
            val s = pos; val e = minOf(pos + size, raw.size); pos = e
            return raw.copyOfRange(s, e)
        }
        fun readInt(): Int {
            val b = read(4)
            if (b.size < 4) return 0
            return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                    ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
        }
        fun readStr(size: Int): String {
            val b = read(size)
            var end = b.size
            var k = 0
            while (k + 2 <= b.size) {
                if (b[k].toInt() == 0 && b[k + 1].toInt() == 0) { end = k; break }
                k += 2
            }
            return if (end <= 0) "" else String(b, 0, end, Charsets.UTF_16LE).trim()
        }
    }

    /** 着法树节点；kids[0] 为主线续着，其余为变着。 */
    private class CbrNode(val fr: Pair<Int, Int>?, val to: Pair<Int, Int>?, val annote: String?) {
        val kids = ArrayList<CbrNode>()
    }

    /** 递归读取一局着法树：先读续着(下一手)，再读变着(同一父节点、回退棋盘的兄弟线)。
     *
     *  [nibble]=false 走标准坐标(位置索引 0..89)；=true 走"棋路/象棋笔记 v1 记录"的
     *  半字节坐标(col=b>>4, row=9-(b&0x0F))。两种记录的着法块布局一致，仅坐标编码不同。 */
    private fun readCbrSteps(
        dec: CbrReader,
        sink: MutableList<CbrNode>,
        board: Board,
        nibble: Boolean = false
    ) {
        if (dec.remaining() == 1) return
        val si = dec.read(4)
        if (si.size < 4) return
        val mark = si[0].toInt() and 0xFF
        val seq = si[1].toInt() and 0xFF
        val stepFrom = si[2].toInt() and 0xFF
        val stepTo = si[3].toInt() and 0xFF
        if (mark == 0 && seq == 0 && stepFrom == 0 && stepTo == 0) return
        val hasNext = (mark and 0x01) == 0
        val hasVar = (mark and 0x02) != 0
        var annoteLen = 0
        if ((mark and 0x04) != 0) annoteLen = dec.readInt()
        val bak = cloneBoard(board)
        val fr = if (nibble) decodeCbrPosNibble(stepFrom) else decodeCbrPos(stepFrom)
        val to = if (nibble) decodeCbrPosNibble(stepTo) else decodeCbrPos(stepTo)
        val annote = if (annoteLen > 0 && annoteLen < 1 shl 20) dec.readStr(annoteLen) else null
        if (board.grid[fr.first][fr.second] == null) return
        if (!cbrStepOk(board.grid, fr, to)) return
        val node = CbrNode(fr, to, annote)
        sink.add(node)
        board.grid[to.first][to.second] = board.grid[fr.first][fr.second]
        board.grid[fr.first][fr.second] = null
        if (hasNext) readCbrSteps(dec, node.kids, board, nibble)
        if (hasVar) readCbrSteps(dec, sink, bak, nibble)
    }

    /** 展开着法树的全部“从起始局面起”的完整线路：首条为取 kids[0] 的主线，其余为变着。 */
    private fun allCbrLines(root: CbrNode): List<List<String>> {
        val leaves = ArrayList<List<String>>()
        fun dfs(node: CbrNode, path: MutableList<String>) {
            path.add(uci(node.fr!!, node.to!!))
            if (node.kids.isEmpty()) leaves.add(ArrayList(path))
            else for (k in node.kids) dfs(k, path)
            path.removeAt(path.size - 1)
        }
        for (k in root.kids) dfs(k, ArrayList())
        val main = ArrayList<String>()
        var cur: CbrNode? = root.kids.firstOrNull()
        while (cur != null) {
            main.add(uci(cur.fr!!, cur.to!!))
            cur = cur.kids.firstOrNull()
        }
        val out = ArrayList<List<String>>()
        val seen = HashSet<String>()
        if (main.isNotEmpty()) { seen.add(main.joinToString(" ")); out.add(main) }
        for (ln in leaves) {
            if (ln.isEmpty()) continue
            if (seen.add(ln.joinToString(" "))) out.add(ln)
        }
        return out
    }

    /**
     * 解码一个 CCBridgeLibrary 棋库：逐条(CBR 记录)还原题名、赛事、红黑、结果、初始局面与
     * 全部着法(主线 + 支线)，与象棋桥/象棋笔记的识读结果逐着一致。
     */
    fun decodeLibraryContainer(raw: ByteArray, hint: String): List<CblGame> {
        val physical = ArrayList<CblGame>()
        var rec = indexOfCbrMagic(raw, if (raw.size > CBR_LIB_SCAN) CBR_LIB_SCAN else 0)
        if (rec < 0) rec = indexOfCbrMagic(raw, 0)
        if (rec < 0) return physical
        while (rec < raw.size) {
            if (startsWithAt(raw, rec, CBR_MAGIC)) {
                parseCbrRecord(raw, rec, hint, physical.size + 1)?.let { physical.add(it) }
            }
            rec += CBR_ALIGN
        }
        // ★ 记录是"按作者编辑顺序随意落位"的；原书阅读次序（第几编/第几卷/第几局）另存于头部
        //   目录(catalog)。目录项与物理记录一一对应，故据目录序键把整库重排为原书章节次序
        //   （与 P 象棋/象棋桥一致）。读不出目录时退回物理次序，绝不影响其它棋库。
        val order = catalogOrder(raw, physical.size) ?: return physical
        val res = ArrayList<CblGame>(physical.size)
        for ((newIdx, oldIdx) in order.withIndex()) res.add(withIndex(physical[oldIdx], newIdx + 1))
        return res
    }

    /**
     * 读取棋库头部目录(catalog)，返回「阅读次序 -> 物理记录下标」的排列；读不出返回 null。
     *
     * 目录项定长 [CATALOG_ENTRY]：+0 类型(5/7)、+4 全书序键(升序即阅读次序)、+8 层级、
     * +12 记录字节数、+16 保留；+20 UTF-16 记录 GUID(38 字符)、+100 UTF-16 题名。
     * 目录整体紧邻首条记录之前，以全 0 项收尾。序键相同者(原书个别重出局)按"正局(类型 7)
     * 在前、变着/简局(类型 5)在后"，再按物理次序稳定排列。
     */
    private fun catalogOrder(raw: ByteArray, recCount: Int): IntArray? {
        if (recCount <= 1) return null
        val firstRec = indexOfCbrMagic(raw, 0)
        if (firstRec <= 0) return null
        // 目录从首条记录向前是定长项，其 GUID 以 UTF-16 的 '{'(0x7B 0x00) 起。取首个 '{' 定位。
        var guid = -1
        var i = 0
        while (i + 2 <= firstRec) {
            if (raw[i] == 0x7B.toByte() && raw[i + 1] == 0.toByte()) { guid = i; break }
            i += 2
        }
        if (guid < CATALOG_PREFIX) return null
        val start = guid - CATALOG_PREFIX
        val keys = IntArray(recCount)
        val types = IntArray(recCount)
        var o = start
        var n = 0
        while (n < recCount && o + CATALOG_ENTRY <= firstRec) {
            val type = u32(raw, o)
            if (type != 5 && type != 7) break
            keys[n] = u32(raw, o + 4)
            types[n] = type
            n++
            o += CATALOG_ENTRY
        }
        if (n != recCount) return null
        return (0 until recCount)
            .sortedWith(compareBy({ keys[it] }, { -types[it] }, { it }))
            .toIntArray()
    }

    private fun u32(raw: ByteArray, off: Int): Int =
        (raw[off].toInt() and 0xFF) or
            ((raw[off + 1].toInt() and 0xFF) shl 8) or
            ((raw[off + 2].toInt() and 0xFF) shl 16) or
            ((raw[off + 3].toInt() and 0xFF) shl 24)

    /** 复制一局并改写其序号（重排后用于重新编号）。 */
    private fun withIndex(g: CblGame, i: Int): CblGame = CblGame(
        index = i, event = g.event, site = g.site, date = g.date, red = g.red, black = g.black,
        result = g.result, fenBoard = g.fenBoard, moversBlack = g.moversBlack, movesUci = g.movesUci,
        introComment = g.introComment, endComment = g.endComment, midComments = g.midComments,
        moveComments = g.moveComments, commentaryBlocks = g.commentaryBlocks,
        variationsUci = g.variationsUci
    )

    /**
     * 判定是否为**裸 CBR 单局记录**（.cbr 本体）：文件最前面 4KB 内能找到
     * "CCBridge Record\0" 魔数即认定。棋库(.cbl) 内的记录由 decodeLibraryContainer 走另一路，
     * 且棋库头部是 "CCBridgeLibrary"（已在 decodeBook 中先行分流），故此处不冲突。
     */
    private fun hasStandaloneCbr(raw: ByteArray): Boolean {
        val at = indexOfCbrMagic(raw, 0)
        return at >= 0 && at < minOf(raw.size, CBR_ALIGN)
    }

    /**
     * 解码**裸 CBR 单局**：自首条 "CCBridge Record\0" 起逐条还原（.cbr 单局文件只出一条；
     * 若文件里串了多条记录则逐条解析）。字段布局与棋库内记录完全一致，故复用 parseCbrRecord。
     */
    fun decodeStandaloneCbr(raw: ByteArray, hint: String): List<CblGame> {
        val out = ArrayList<CblGame>()
        var pos = 0
        while (true) {
            val rec = indexOfCbrMagic(raw, pos)
            if (rec < 0) break
            parseCbrRecord(raw, rec, hint, out.size + 1)?.let { out.add(it) }
            pos = rec + CBR_ALIGN
        }
        return out
    }

    /** 解析单条 CBR 记录(base 指向 "CCBridge Record\0")。 */
    private fun parseCbrRecord(raw: ByteArray, base: Int, hint: String, index: Int): CblGame? {
        if (base + CBR_HEADER > raw.size) return null
        // ★ "无标题" 是象棋桥单局导出时的占位题名 → 视为空，改用文件名(hint)，列表里才不会是清一色“无标题”。
        val title = utf16z(raw, base + 180, 128).takeIf { it.isNotEmpty() && it != "无标题" } ?: ""
        val site = utf16z(raw, base + 692, 64)
        val red = utf16z(raw, base + 1076, 64)
        val black = utf16z(raw, base + 1300, 64)
        val result = when (raw[base + 2076].toInt() and 0xFF) {
            1 -> "1-0"; 2 -> "0-1"; 3 -> "1/2-1/2"; 4 -> "1/2-1/2"; else -> "*"
        }
        val moveSide = (raw[base + 2116].toInt() and 0xFF) or ((raw[base + 2117].toInt() and 0xFF) shl 8)
        val board = boardFrom90(raw, base + 2120)
        val fen = fenBoardOf(board.grid)
        val dec = CbrReader(raw, base + CBR_HEADER)
        val aLen = dec.readInt()
        val intro = if (aLen != 0) dec.readStr(dec.readInt()) else ""
        val root = CbrNode(null, null, null)
        readCbrSteps(dec, root.kids, board)
        val lines = allCbrLines(root)
        val main = lines.firstOrNull() ?: emptyList()
        val variations = if (lines.size > 1) lines.subList(1, lines.size) else emptyList()

        // 主线旁白：按主线步序取每步注解，可直接用于按步提示。
        val moveComments = LinkedHashMap<Int, String>()
        val blocks = ArrayList<CommentaryBlock>()
        val mid = ArrayList<String>()
        var cur = root.kids.firstOrNull()
        var ply = 0
        while (cur != null) {
            val an = cur.annote
            if (!an.isNullOrEmpty()) {
                moveComments[ply] = an
                mid.add(an)
                blocks.add(CommentaryBlock(an, false))
            }
            cur = cur.kids.firstOrNull()
            ply++
        }
        return CblGame(
            index = index,
            event = title.ifEmpty { "$hint 第${index}局" },
            site = site,
            date = "",
            red = red,
            black = black,
            result = result,
            fenBoard = if (fen != STD_FEN) fen else null,
            moversBlack = moveSide != 1,
            movesUci = main,
            introComment = sanitizeComment(intro),
            endComment = mid.lastOrNull() ?: "",
            midComments = mid,
            moveComments = moveComments,
            commentaryBlocks = blocks,
            variationsUci = variations
        )
    }

    // ================================================================ CBR 变体（棋路/象棋笔记 v1）
    /**
     * 扫描文件中的「90 字节初始局面块」。判定极严（几乎不会误判）：
     *   · 90 格只含 0 或合法棋子码（0x11..0x17 红 / 0x21..0x27 黑）；
     *   · 恰有 1 个红帅(0x15)与 1 个黑将(0x25)，且红黑子数相等、总数不超过 32。
     * 实测对真实变体文件只命中初始局面那一处。
     */
    private fun findCbrBoards(raw: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var off = 0
        while (off + 90 <= raw.size) {
            var ok = true
            var reds = 0; var blacks = 0; var rk = 0; var bk = 0
            for (i in 0 until 90) {
                val v = raw[off + i].toInt() and 0xFF
                when {
                    v == 0 -> {}
                    v in 0x11..0x17 -> { reds++; if (v == 0x15) rk++ }
                    v in 0x21..0x27 -> { blacks++; if (v == 0x25) bk++ }
                    else -> { ok = false }
                }
                if (!ok) break
            }
            if (ok && rk == 1 && bk == 1 && reds == blacks && reds in 1..16) out.add(off)
            off++
        }
        return out
    }

    /**
     * 解码「棋路/象棋笔记 v1 单局记录」：头 16B 也是 "CCBridge Record\0"，但字段偏移与标准记录
     * 不同（初始局面不落在 2120），且着法坐标改用半字节编码。做法：扫描初始局面块，
     * 逐块按半字节坐标解着法树，取"能解出最多合法着法"的那块作为本局。
     */
    fun decodeCbrVariant(raw: ByteArray, hint: String): List<CblGame> {
        var bestGame: CblGame? = null
        var bestLen = 0
        for (off in findCbrBoards(raw)) {
            val board = boardFrom90(raw, off)
            val dec = CbrReader(raw, off + 90)
            val aLen = dec.readInt()
            if (aLen != 0) {
                val n = dec.readInt()
                if (n in 1..(1 shl 20) && dec.pos + n <= raw.size) dec.pos += n
            }
            val root = CbrNode(null, null, null)
            readCbrSteps(dec, root.kids, board, nibble = true)
            val lines = allCbrLines(root)
            val main = lines.firstOrNull() ?: continue
            if (main.size <= bestLen) continue
            val variations = if (lines.size > 1) lines.subList(1, lines.size) else emptyList()
            val moveComments = LinkedHashMap<Int, String>()
            val blocks = ArrayList<CommentaryBlock>()
            val mid = ArrayList<String>()
            var cur = root.kids.firstOrNull()
            var ply = 0
            while (cur != null) {
                val an = cur.annote
                if (!an.isNullOrEmpty()) {
                    moveComments[ply] = an; mid.add(an); blocks.add(CommentaryBlock(an, false))
                }
                cur = cur.kids.firstOrNull(); ply++
            }
            val fen = fenBoardOf(board.grid)
            bestLen = main.size
            bestGame = CblGame(
                index = 1,
                event = hint,
                site = "", date = "",
                red = "", black = "",
                result = "*",
                fenBoard = if (fen != STD_FEN) fen else null,
                moversBlack = false,
                movesUci = main,
                introComment = "",
                endComment = mid.lastOrNull() ?: "",
                midComments = mid,
                moveComments = moveComments,
                commentaryBlocks = blocks,
                variationsUci = variations
            )
        }
        return listOfNotNull(bestGame)
    }

    private fun leU32(b: ByteArray, off: Int): Int =
        if (off < 0 || off + 4 > b.size) 0
        else (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

    // 初始局面坐标字节：col=(b>>4)-1，row=10-(b&0x0F)。b==0 -> null。
    private fun initCoord(b: Byte): Pair<Int, Int>? {
        val v = b.toInt() and 0xFF
        if (v == 0) return null
        val f = (v shr 4) - 1
        val r = 10 - (v and 0x0F)
        return if (f in 0..8 && r in 0..9) f to r else null
    }

    // ================================================================ 初始局面
    private fun validInit(blk: ByteArray, off: Int): Boolean {
        if (off + 32 > blk.size) return false
        val seen = java.util.HashSet<Long>()
        for (i in 0 until 32) {
            val b = blk[off + i]
            val sq = initCoord(b)
            if (sq == null) {
                if (b.toInt() != 0) return false
                continue
            }
            val key = sq.first.toLong() * 16 + sq.second
            if (!seen.add(key)) return false
        }
        val rk = initCoord(blk[off + 4]) ?: return false
        val bk = initCoord(blk[off + 20]) ?: return false
        if (rk.first !in 3..5 || rk.second !in 0..2) return false
        if (bk.first !in 3..5 || bk.second !in 7..9) return false
        return true
    }

    private fun candidates(blob: ByteArray): List<Int> {
        val res = java.util.TreeSet<Int>()
        var i = 0
        while (i + 32 <= blob.size) {
            if (validInit(blob, i)) res.add(i)
            i++
        }
        return res.toList()
    }

    private fun longestChain(blob: ByteArray, cands: List<Int>): List<Int> {
        val set = cands.toHashSet()
        var best: List<Int> = emptyList()
        var bl = -1
        for (p0 in cands) {
            val chain = ArrayList<Int>()
            var p = p0
            while (p in set) {
                chain.add(p)
                if (p < 4) break
                val len = leU32(blob, p - 4)
                if (len < 20 || len > 400000) break
                p += len
                if (p - 4 < 0) break
            }
            if (chain.size > bl) { bl = chain.size; best = chain }
        }
        return best
    }

    // ================================================================ 棋盘引擎
    private class Board {
        val grid = Array(9) { arrayOfNulls<Char>(10) }
        var red = true

        fun initBoard() {
            for (c in 0..8) for (r in 0..9) grid[c][r] = null
            val top = charArrayOf('R', 'N', 'B', 'A', 'K', 'A', 'B', 'N', 'R')
            for (c in 0..8) { grid[c][0] = top[c]; grid[c][9] = (top[c] + 32).toChar() }
            grid[1][2] = 'C'; grid[7][2] = 'C'
            grid[1][7] = 'c'; grid[7][7] = 'c'
            for (c in intArrayOf(0, 2, 4, 6, 8)) { grid[c][3] = 'P'; grid[c][6] = 'p' }
            red = true
        }

        fun apply(fr: Pair<Int, Int>, to: Pair<Int, Int>) {
            grid[to.first][to.second] = grid[fr.first][fr.second]
            grid[fr.first][fr.second] = null
            red = !red
        }

        private fun inBoard(c: Int, r: Int) = c in 0..8 && r in 0..9
        private fun isRedP(p: Char?) = p != null && p.isUpperCase()

        private fun kingsFace(): Boolean {
            var rk: Pair<Int, Int>? = null
            var bk: Pair<Int, Int>? = null
            for (c in 0..8) for (r in 0..9) {
                val p = grid[c][r]
                if (p == 'K') rk = c to r
                else if (p == 'k') bk = c to r
            }
            if (rk != null && bk != null && rk.first == bk.first) {
                var clear = true
                for (rr in (minOf(rk.second, bk.second) + 1) until maxOf(rk.second, bk.second)) {
                    if (grid[rk.first][rr] != null) { clear = false; break }
                }
                if (clear) return true
            }
            return false
        }

        fun legalMovesFrom(col: Int, row: Int): List<Pair<Int, Int>> {
            val p = grid[col][row] ?: return emptyList()
            val redP = isRedP(p)
            val moves = ArrayList<Pair<Int, Int>>()
            fun add(c: Int, r: Int) {
                if (inBoard(c, r)) {
                    val q = grid[c][r]
                    if (q == null || isRedP(q) != redP) moves.add(c to r)
                }
            }
            when (p.uppercaseChar()) {
                'R' -> for ((dc, dr) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    var c = col + dc; var r = row + dr
                    while (inBoard(c, r)) {
                        val q = grid[c][r]
                        if (q == null) add(c, r)
                        else { if (isRedP(q) != redP) add(c, r); break }
                        c += dc; r += dr
                    }
                }
                'C' -> for ((dc, dr) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    var c = col + dc; var r = row + dr; var jumped = false
                    while (inBoard(c, r)) {
                        val q = grid[c][r]
                        if (!jumped) { if (q == null) add(c, r) else jumped = true }
                        else { if (q != null) { if (isRedP(q) != redP) add(c, r); break } }
                        c += dc; r += dr
                    }
                }
                'N' -> for ((dc, dr, lc, lr) in arrayOf(
                    intArrayOf(2, 1, 1, 0), intArrayOf(2, -1, 1, 0), intArrayOf(-2, 1, -1, 0), intArrayOf(-2, -1, -1, 0),
                    intArrayOf(1, 2, 0, 1), intArrayOf(1, -2, 0, -1), intArrayOf(-1, 2, 0, 1), intArrayOf(-1, -2, 0, -1)
                )) {
                    if (inBoard(col + lc, row + lr) && grid[col + lc][row + lr] == null) add(col + dc, row + dr)
                }
                'B' -> {
                    val inHalf: (Int) -> Boolean = if (redP) ({ r -> r <= 4 }) else ({ r -> r >= 5 })
                    if (!inHalf(row)) return emptyList()
                    for ((dc, dr, mc, mr) in arrayOf(
                        intArrayOf(2, 2, 1, 1), intArrayOf(2, -2, 1, -1), intArrayOf(-2, 2, -1, 1), intArrayOf(-2, -2, -1, -1)
                    )) {
                        if (inBoard(col + mc, row + mr) && grid[col + mc][row + mr] == null) {
                            if (inHalf(row + dr)) add(col + dc, row + dr)
                        }
                    }
                }
                'A' -> for ((dc, dr) in arrayOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)) {
                    val c = col + dc; val r = row + dr
                    if (c in 3..5 && ((redP && r in 0..2) || (!redP && r in 7..9))) add(c, r)
                }
                'K' -> for ((dc, dr) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val c = col + dc; val r = row + dr
                    if (c in 3..5 && ((redP && r in 0..2) || (!redP && r in 7..9))) add(c, r)
                }
                'P' -> {
                    val tar = ArrayList<Pair<Int, Int>>()
                    tar.add(col to (if (redP) row + 1 else row - 1))
                    if ((redP && row >= 5) || (!redP && row <= 4)) { tar.add(col - 1 to row); tar.add(col + 1 to row) }
                    for ((c, r) in tar) add(c, r)
                }
            }
            val legal = ArrayList<Pair<Int, Int>>()
            for ((c, r) in moves) {
                val saved = grid[c][r]
                grid[c][r] = p; grid[col][row] = null
                if (!kingsFace()) legal.add(c to r)
                grid[col][row] = p; grid[c][r] = saved
            }
            return legal
        }

        fun hasLegal(): Boolean {
            for (c in 0..8) for (r in 0..9) {
                val p = grid[c][r]
                if (p != null && isRedP(p) == red && legalMovesFrom(c, r).isNotEmpty()) return true
            }
            return false
        }
    }

    private fun setupBoard(blk: ByteArray, off: Int): Board {
        val b = Board(); b.initBoard()
        for (c in 0..8) for (r in 0..9) b.grid[c][r] = null
        b.red = true
        val reds = charArrayOf('R', 'N', 'B', 'A', 'K', 'A', 'B', 'N', 'R', 'C', 'C', 'P', 'P', 'P', 'P', 'P')
        val blacks = charArrayOf('r', 'n', 'b', 'a', 'k', 'a', 'b', 'n', 'r', 'c', 'c', 'p', 'p', 'p', 'p', 'p')
        for (idx in 0 until 16) { initCoord(blk[off + idx])?.let { b.grid[it.first][it.second] = reds[idx] } }
        for (idx in 0 until 16) { initCoord(blk[off + 16 + idx])?.let { b.grid[it.first][it.second] = blacks[idx] } }
        return b
    }

    private fun fenBoardStr(blk: ByteArray, off: Int): String {
        val g = setupBoard(blk, off).grid
        val rows = ArrayList<String>()
        for (r in 9 downTo 0) {
            val sb = StringBuilder(); var empty = 0
            for (c in 0..8) {
                val p = g[c][r]
                if (p == null) empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(p)
                }
            }
            if (empty > 0) sb.append(empty)
            rows.add(sb.toString())
        }
        return rows.joinToString("/")
    }

    // ================================================================ 走法块
    private fun findMoveBlock(blob: ByteArray, p: Int, len: Int): Int? {
        val recEnd = minOf(p + len, blob.size)
        var best: Int? = null; var bestLen = 0
        var s = p + 32
        while (s < recEnd - 6) {
            if ((blob[s].toInt() and 0xFF) == 1 && blob[s + 1].toInt() == 0) {
                var num = 1; var k = s
                while (k + 6 <= recEnd && (blob[k].toInt() and 0xFF) == num && blob[k + 1].toInt() == 0) {
                    num++; k += 6
                }
                if (num - 1 > bestLen) { bestLen = num - 1; best = s }
            }
            s++
        }
        return best
    }

    // 走法坐标字节 -> (col,row)：col=byte>>4，row=9-(byte&0x0F)
    private fun moveCoord(b0: Byte): Pair<Int, Int> {
        val v = b0.toInt() and 0xFF
        return (v shr 4) to (9 - (v and 0x0F))
    }

    private fun uci(fr: Pair<Int, Int>, to: Pair<Int, Int>): String {
        val sb = StringBuilder(4)
        sb.append(('a'.code + fr.first).toChar())
        sb.append(('0'.code + fr.second).toChar())
        sb.append(('a'.code + to.first).toChar())
        sb.append(('0'.code + to.second).toChar())
        return sb.toString()
    }

    /** 标准开局局面（仅棋子板，不含轮次）。公开给合成导出等复用。 */
    val STD_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"

    private class DecodedGame(
        val movesUci: List<String>, val result: String, val fenBoard: String?, val firstIsBlack: Boolean
    )

    private fun decodeGame(blob: ByteArray, p: Int, len: Int): DecodedGame? {
        val ms = findMoveBlock(blob, p, len) ?: return null
        val recEnd = minOf(p + len, blob.size)
        val b = setupBoard(blob, p)
        if (ms + 6 > recEnd) return null
        // 第一个走法：判断先手
        val fr0 = moveCoord(blob[ms + 2])
        if (fr0.first !in 0..8 || fr0.second !in 0..9) return null
        val p0 = b.grid[fr0.first][fr0.second] ?: return null
        if (p0.isLowerCase()) b.red = false
        val resFen = fenBoardStr(blob, p)
        val ucis = ArrayList<String>()
        var num = 1; var k = ms
        while (k + 6 <= recEnd && (blob[k].toInt() and 0xFF) == num && blob[k + 1].toInt() == 0) {
            val fr = moveCoord(blob[k + 2])
            val to = moveCoord(blob[k + 3])
            if (fr.first !in 0..8 || fr.second !in 0..9 || to.first !in 0..8 || to.second !in 0..9) break
            val pc = b.grid[fr.first][fr.second]
            if (pc == null || isRedP(pc) != b.red || !isLegal(b, fr, to)) break
            ucis.add(uci(fr, to))
            b.apply(fr, to)
            num++; k += 6
        }
        var result = "*"
        if (ucis.isNotEmpty() && !b.hasLegal()) result = if (b.red) "0-1" else "1-0"
        return DecodedGame(ucis, result, if (resFen != STD_FEN) resFen else null, p0.isLowerCase())
    }

    private fun isRedP(p: Char) = p.isUpperCase()

    private fun isLegal(b: Board, fr: Pair<Int, Int>, to: Pair<Int, Int>): Boolean =
        b.legalMovesFrom(fr.first, fr.second).contains(to)

    // ================================================================ 变着支线提取
    /** 变着一步：坐标 (fr,to)，与主线同坐标系（col=0..8、row=0..9）。 */
    private class VMv(val fr: Pair<Int, Int>, val to: Pair<Int, Int>)

    /** 走法坐标字节是否在盘内（宽松判定：允许 0，只要求字节形状像坐标）。 */
    private fun okCoordByte(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v <= 0x89 && (v and 0x0F) <= 9
    }

    /**
     * ★ 宽松切段：在 [from,recEnd) 内识别"支线走法串"。
     * 每串形如 `nn 00 <from> <to> xx xx`×N：第2字节为0、起始序号 1..200、串内序号连续
     * （255 回绕到 1）、坐标字节形状在盘内（允许 0）。长度 ≥2 的串才收，单步噪声丢弃。
     * @return (起始序号, 串内走法) 列表
     */
    private fun segRunsRelaxed(blob: ByteArray, from: Int, recEnd: Int): List<Pair<Int, List<VMv>>> {
        val runs = ArrayList<Pair<Int, List<VMv>>>()
        var pos = from
        while (pos + 6 <= recEnd) {
            val b0 = blob[pos].toInt() and 0xFF
            val b1 = blob[pos + 1].toInt() and 0xFF
            if (b1 == 0 && b0 in 1..200 && okCoordByte(blob[pos + 2]) && okCoordByte(blob[pos + 3])) {
                var exp = b0; var end = pos; var cnt = 0
                while (end + 6 <= recEnd && cnt < 400) {
                    val t0 = blob[end].toInt() and 0xFF
                    val t1 = blob[end + 1].toInt() and 0xFF
                    if (t1 == 0 && t0 == exp && okCoordByte(blob[end + 2]) && okCoordByte(blob[end + 3])) {
                        exp = (exp % 255) + 1; end += 6; cnt++
                    } else break
                }
                if (cnt >= 2) {
                    val moves = ArrayList<VMv>(cnt)
                    var k = pos
                    while (k < end) {
                        moves.add(VMv(moveCoord(blob[k + 2]), moveCoord(blob[k + 3]))); k += 6
                    }
                    runs.add(b0 to moves)
                    pos = end
                    continue
                }
            }
            pos++
        }
        return runs
    }

    private fun cloneBoard(b: Board): Board {
        val n = Board()
        for (c in 0..8) for (r in 0..9) n.grid[c][r] = b.grid[c][r]
        n.red = b.red
        return n
    }

    /** 一步能否在 b 上走（含轮次与行棋合法性）；走得通就地落子。 */
    private fun stepOk(b: Board, fr: Pair<Int, Int>, to: Pair<Int, Int>): Boolean {
        if (fr.first !in 0..8 || fr.second !in 0..9 || to.first !in 0..8 || to.second !in 0..9) return false
        val pc = b.grid[fr.first][fr.second] ?: return false
        if (isRedP(pc) != b.red) return false
        if (!b.legalMovesFrom(fr.first, fr.second).contains(to)) return false
        b.apply(fr, to)
        return true
    }

    /**
     * ★ 提取一局的全部变着支线（每条 = 从本局起始局面起的完整 UCI 序列）。
     * 算法与已验证脚本一致：
     *  1) 主线串后按"宽松切段"识别支线串，起始序号 snum 即分叉点序号；
     *  2) 每串候选父线 = [先前已挂支线逆序] + [主线]（嵌套变着优先挂最近支线）：
     *     父线前 snum-1 步 + 本串全部步必须逐步行得通，第一个全合法者胜；
     *  3) 挂不上的串丢弃（多为二进制噪声），对主线解析零影响。
     */
    private fun extractVariations(blob: ByteArray, p: Int, ms: Int, recEnd: Int): List<List<String>> {
        // —— 主线原始长度（仅看序号连续，不做行棋校验；正常与合法主线等长）——
        var rawMainLen = 0
        var k = ms
        var num = 1
        while (k + 6 <= recEnd && (blob[k].toInt() and 0xFF) == num && blob[k + 1].toInt() == 0) {
            num++; k += 6; rawMainLen++
        }
        if (rawMainLen < 1) return emptyList()
        val runs = segRunsRelaxed(blob, ms + rawMainLen * 6, recEnd)
        if (runs.isEmpty()) return emptyList()
        val base = setupBoard(blob, p)
        // 首着颜色：与 decodeGame 同判（残局黑先则支线也按黑先挂）
        val fr0 = moveCoord(blob[ms + 2])
        if (fr0.first in 0..8 && fr0.second in 0..9) {
            val pc0 = base.grid[fr0.first][fr0.second]
            if (pc0 != null && pc0.isLowerCase()) base.red = false
        }
        val rawMain = ArrayList<VMv>(rawMainLen)
        for (i in 0 until rawMainLen) {
            rawMain.add(VMv(moveCoord(blob[ms + i * 6 + 2]), moveCoord(blob[ms + i * 6 + 3])))
        }
        val lines = ArrayList<List<VMv>>()
        lines.add(rawMain)
        val out = ArrayList<List<String>>()
        for ((snum, rmoves) in runs) {
            var full: List<VMv>? = null
            var li = lines.size - 1
            while (li >= 0 && full == null) {
                val parent = lines[li]
                if (parent.size >= snum - 1) {
                    val b = cloneBoard(base)
                    var good = true
                    for (i in 0 until snum - 1) {
                        if (!stepOk(b, parent[i].fr, parent[i].to)) { good = false; break }
                    }
                    if (good) {
                        for (m in rmoves) {
                            if (!stepOk(b, m.fr, m.to)) { good = false; break }
                        }
                    }
                    if (good) {
                        val f = ArrayList<VMv>(snum - 1 + rmoves.size)
                        for (i in 0 until snum - 1) f.add(parent[i])
                        f.addAll(rmoves)
                        full = f
                    }
                }
                li--
            }
            if (full != null) {
                lines.add(full)
                out.add(full.map { uci(it.fr, it.to) })
            }
        }
        return out
    }

    // ================================================================ 元数据
    // 从 [P+32, ms) 的 GBK 文本中提取 [event, site, date, red, black]。
    // 移植 convert.py extract_meta 的启发式逻辑：日期识别 + 文本段(runs)切分 +
    // 最长非日期段作事件、日期前 2~4 字作赛事、日期后纯中文名作红黑棋手。
    private fun extractMeta(blob: ByteArray, p: Int, ms: Int, hint: String): Array<String> {
        val seg = if (ms > p + 32) blob.copyOfRange(p + 32, ms) else ByteArray(0)
        val txt = try {
            String(seg, Charset.forName("GBK"))
        } catch (e: Exception) {
            ""
        }

        // ---- runs：连续合法的文本片段 ----
        val runRe = Regex("""[\u4e00-\u9fff0-9A-Za-z“”（）()《》．。·、…．－\-—/:：，,]+""")
        val runs = runRe.findAll(txt).map { it.value }.toList()
        val dateStr = Regex("""(\d{4})[年\-](\d{1,2})[月\-](\d{1,2})日?""").find(txt)
        val date = dateStr?.let { "%s.%02d.%02d".format(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt()) } ?: ""

        fun isDateRun(s: String): Boolean = Regex("""\d{4}\s*[年\-/].*[月日]|\d{4}-\d""").containsMatchIn(s)
        fun isRealDate(s: String): Boolean =
            Regex("""\d{4}\s*[年].*\d{1,2}\s*[月].*\d{1,2}\s*[日]""").containsMatchIn(s) ||
                    Regex("""\d{4}-\d{1,2}-\d{1,2}\s+\d{1,2}:\d{1,2}""").containsMatchIn(s)
        fun cjkCount(s: String): Int = s.count { it in '\u4e00'..'\u9fff' }
        fun isPureCjkName(s: String): Boolean =
            cjkCount(s) in 2..3 && s.any { it in '\u4e00'..'\u9fff' } && s.all { it in '\u4e00'..'\u9fff' }

        // ---- event = 最长的非日期、中文>=3 的文本段 ----
        var event = hint
        var bestK = 0
        for (s in runs) {
            if (isDateRun(s)) continue
            val k = cjkCount(s)
            if (k >= 3 && k > bestK) { bestK = k; event = s.trim('\u201c', '\u201d', '、', '：', '《', '》', '(', ')', ' ', '，', ',', '：') }
        }

        // ---- 日期前的 2~4 字段作赛事(site) ----
        var site = ""
        val realIdx = runs.indexOfFirst { isRealDate(it) }
        if (realIdx >= 1) {
            for (j in realIdx - 1 downTo 0) {
                val s = runs[j]
                if (isDateRun(s) || cjkCount(s) == 0) continue
                if (cjkCount(s) in 2..4) site = s
                break
            }
        }

        // ---- 日期后的纯中文名段作红/黑棋手 ----
        var red = "?"
        var black = "?"
        if (realIdx >= 0) {
            val names = ArrayList<String>()
            for (j in realIdx + 1 until runs.size) {
                val s = runs[j]
                if (isDateRun(s)) break
                if (isPureCjkName(s)) names.add(s)
                if (names.size >= 2) break
            }
            if (names.isNotEmpty()) red = names[0]
            if (names.size > 1) black = names[1]
        }
        if (red == "?" && black == "?") {
            // 无日期时退而取前两个纯中文名段
            val all = runs.filter { isPureCjkName(it) }
            if (all.isNotEmpty()) red = all[0]
            if (all.size > 1) black = all[1]
        }
        return arrayOf(event, site, date, red, black)
    }

    /**
     * 结构题名：旧格式记录 body+32 起为 i32 类型(1..4) / i32 0 / i32 1，body+44 为
     * i32 题名长度 + GBK 题名。布局不符（专书/变体记录）返回 null，调用方回退启发式。
     */
    private fun legacyStructTitle(blob: ByteArray, p: Int): String? {
        if (p + 48 > blob.size) return null
        val typ = leU32(blob, p + 32)
        if (typ !in 1..4) return null
        val tl = leU32(blob, p + 44)
        if (tl <= 0 || tl > 200 || p + 48 + tl > blob.size) return null
        val s = try {
            String(blob, p + 48, tl, GBK)
        } catch (e: Exception) {
            return null
        }
        if (s.count { it in '\u4e00'..'\u9fff' } < 2) return null
        return s.trim()
    }

    /**
     * 旧格式记录第 1 字段的标记 = 结局码（1=红胜 / 2=黑胜 / 3=和或其它）。
     * 象棋桥只把结局写在这里，别处不留；解谱时必须读回，否则导出时一律写成 3，
     * 所有红胜/黑胜局都会被当成和棋。读不到（非旧格式或越界）返回 null，退回启发式。
     */
    private fun legacyResultTag(blob: ByteArray, p: Int): Int? {
        if (p + 48 > blob.size) return null
        val tl = leU32(blob, p + 44)
        if (tl < 0 || tl > 200 || p.toLong() + 48 + tl + 24 + 4 > blob.size) return null
        val tag = leU32(blob, p + 48 + tl + 24)
        return if (tag in 1..3) tag else null
    }

    // ================================================================ 开局旁白
    /**
     * 从元数据区域（p+32 到 ms）提取开局旁白。
     * 元数据中 || 是分隔符，|| 之前较长的中文描述（>10个中文字符，
     * 且不是纯标题/人名/日期）即为 introComment。
     */
    private fun extractIntroComment(blob: ByteArray, p: Int, ms: Int): String {
        val seg = if (ms > p + 32) blob.copyOfRange(p + 32, ms) else return ""
        val txt = try {
            String(seg, Charset.forName("GBK"))
        } catch (e: Exception) {
            return ""
        }
        if (txt.isEmpty()) return ""

        fun cjkCount(s: String): Int = s.count { it in '\u4e00'..'\u9fff' }
        fun isDateLike(s: String): Boolean =
            Regex("""\d{4}\s*[年\-/].*[月日]|\d{4}-\d""").containsMatchIn(s)
        fun isPureName(s: String): Boolean =
            cjkCount(s) in 2..3 && s.all { it in '\u4e00'..'\u9fff' }

        // 按 || 分隔符拆分
        val segments = txt.split("||").map { it.trim() }
        for (segStr in segments) {
            if (segStr.isEmpty()) continue
            val cjk = cjkCount(segStr)
            // 超过 10 个中文字符，且不是日期、不是纯人名
            if (cjk > 10 && !isDateLike(segStr) && !isPureName(segStr)) {
                // 清理：去掉前后空白和常见符号
                val cleaned = segStr.trim(' ', '\u3000', '　', '《', '》', '“', '”', '"', '\t', '\n', '\r')
                if (cleaned.isNotEmpty() && cjkCount(cleaned) > 10) {
                    return cleaned
                }
            }
        }
        return ""
    }

    // ================================================================ 专书头子结构解析
    /**
     * ★ 专书（顺炮/屏风马/列炮等《象棋兵法》类）的元数据区里，紧邻走法块的尾声是一串
     *   "长度前缀 + GBK文本" 的字段：<红len><红GBK><00000000><黑len><黑GBK><00000000>
     *   <开len><开GBK>。开GBK 正文以 || 分隔多段（如 "||《象棋兵法》之一：顺炮篇||这一盘棋来介绍…"）。
     *
     * 解析策略（对实测文件布局可靠、且不误伤其它书）：
     *   · 在 blob[p+32 .. ms]（不含走法块）内按长度前缀扫描，找最大的、"正文含 || 且中文≥4"
     *     的文本块作为整篇开场白 —— 按长度精确切片可干净避开后面的日期/二进制噪声；
     *   · 获不到合理解读就返回 null → 调用方回退到通用启发式，对古谱/赛事/残局零影响。
     */
    data class SpecHeader(
        val title: String,   // 题头（对局名）
        val red: String,
        val black: String,
        val env: String,     // 备用
        val intro: String    // 整篇开场白（干净，已去 || 拆段拼接、去日期噪声）
    )

    private fun parseSpecHeader(blob: ByteArray, p: Int, ms: Int): SpecHeader? {
        if (ms <= p + 32) return null
        val seg = blob.copyOfRange(p + 32, ms)
        fun le(pos: Int): Int = leU32(seg, pos)
        val n = seg.size
        if (n < 16) return null

        // —— 扫出"最像整篇开场白"的长度前缀字段：正文含 ||，中文足量，块最长 ——
        var bestLen = 0
        var bestAt = -1
        for (k in 12 until n - 8) {
            val L = le(k)
            if (L < 8 || L > 1600 || k + 4 + L > n) continue
            if (L <= bestLen) continue
            val raw = try { String(seg, k + 4, L, Charset.forName("GBK")) } catch (e: Exception) { continue }
            if (raw.count { it in '\u4e00'..'\u9fff' } < 4) continue
            if (!raw.contains("||")) continue
            if (raw.any { it == '\ufffd' }) continue
            bestLen = L
            bestAt = k
        }
        if (bestAt < 0) return null
        val introData = try { String(seg, bestAt + 4, bestLen, Charset.forName("GBK")) } catch (e: Exception) { return null }

        // —— 拆段去噪：|| 分隔，丢空段/日期段/含控制字节的段，拼成干净开场白 ——
        fun isDateLike(s: String): Boolean =
            Regex("""\d{4}[年\-/].*[月日]|\d{4}-\d|\d{1,2}:\d{1,2}""").containsMatchIn(s)
        val kept = introData.split("||")
            .map { it.trim(' ', '\u3000', '\t', '\n', '\r') }
            .filter {
                it.isNotEmpty() &&
                it.count { cc -> cc in '\u4e00'..'\u9fff' } >= 2 &&
                !isDateLike(it) &&
                it.none { cc -> cc.code in 0..0x1f } && it.none { cc -> cc == '\ufffd' }
            }
        if (kept.isEmpty()) return null
        val intro = kept.joinToString("\n")

        // —— 红/黑：从开场白长度前缀往前回溯两个名字段（best-effort，失败置空由调用方回退 meta）——
        var i = bestAt - 4
        fun readBack(): String? {
            // 期望：<名len><名GBK> <00000000>；从 i 往前找 00000000，再往前 4B 是 len
            if (i - 4 < 0 || le(i) != 0) return null
            var j = i - 4                           // 名段终止处
            if (j - 4 < 0) return null
            val L = le(j - 4)
            if (L < 2 || L > 16 || j - L < 0) return null
            val s = specCjk(seg, j - L, L) ?: return null
            i = j - L - 4                            // 越过 <00000000> + 名段，准备读前一段
            if (i >= 0 && le(i) == 0) i -= 4         // 若又是分隔零，再往前一段
            return s
        }
        var black = readBack() ?: ""
        var red = readBack() ?: ""

        return SpecHeader(title = "", red = red, black = black, env = "", intro = intro)
    }

    /** 判读一个"长度前缀 + GBK 文本"字段：全为可读中文字符才返回。 */
    private fun specCjk(seg: ByteArray, off: Int, len: Int): String? {
        if (len <= 0 || off < 0 || off + len > seg.size) return null
        val s = try { String(seg, off, len, Charset.forName("GBK")) } catch (e: Exception) { return null }
        if (s.any { it == '\ufffd' }) return null
        var cjk = 0
        for (c in s) {
            val v = c.code
            val ok = v in 0x4e00..0x9fff || v in 0x3000..0x303f || v in 0xff00..0xff5e ||
                    (v in '0'.code..'9'.code) || v == ' '.code ||
                    v == ','.code || v == ':'.code || v == '-'.code || v == '.'.code || v == '，'.code
            if (!ok) return null
            if (v in 0x4e00..0x9fff) cjk++
        }
        if (cjk < 2) return null
        return s.trim(' ', '\u3000', '\t', '\n', '\r')
    }

    /** 防乱码兜底：旁白/题名若混入控制字符或 GBK 解码失败符，抽成干净中文段；抽不出则置空。 */
    private fun sanitizeComment(s: String): String {
        if (s.isEmpty()) return s
        if (s.any { it.code in 0..0x1f || it == '\ufffd' }) {
            val clean = Regex("""[\u4e00-\u9fff“”()（）《》．。、，：；·0-9a-zA-Z 　]+""")
                .findAll(s).joinToString("") { it.value }.trim()
            return if (clean.length >= 4) clean else ""
        }
        return s
    }

    // ================================================================ 中局旁白
    /**
     * 从走法块结束位置到记录结束之间提取多段中局旁白。
     *
     * CBL 的中局讲解文字不以"步号引用"存储，而是排布在主线走法块之后、
     * 变着数据之后的一片区域里，按对局推进顺序排列。本函数：
     * 1. 跳过主线后紧跟的变着数据（连续 6 字节组：nn 00 xx xx xx xx）
     * 2. 将剩余区域按 GBK 解码，按行切分、聚合成"含较多中文字"的段落
     * 3. 过滤掉元数据尾部的噪声（人名、赛事名、日期等短串），返回有序段落列表
     */
    /**
     * 逐坨解析走法块之后的旁白区域，按"出生顺序"返回一坨一坨的旁白，
     * 并标出哪些属于支节（变着）里的旁白。
     *
     * CBL 结构：主走法块结束之后，交替出现
     *   - 变着块（6字节一组 move：nn 00 from to xx xx，序号递增）
     *   - 旁白文本块（GBK 中文）
     * 变着块之后的连续文本块，视为该变着（支节）里的旁白。
     * 本函数逐物理块推进：变着块整体跳过并将其后文本标为 isBranch=true；
     * 非变着块的文本按段落聚合成一坨（保留出生顺序）。
     */
    private fun extractCommentaryBlocks(blob: ByteArray, ms: Int, moveCount: Int, recEnd: Int): List<CommentaryBlock> {
        var pos = ms + moveCount * 6
        if (pos >= recEnd) return emptyList()
        val result = ArrayList<CommentaryBlock>()

        // 变着块识别：自 pos 起的连续 6 字节组，第2字节为0、第1字节递增、from/to 有效
        fun variationStart(at: Int): Int {
            var at0 = at
            if (at0 + 6 > recEnd) return -1
            val b0 = blob[at0].toInt() and 0xFF
            val b1 = blob[at0 + 1].toInt() and 0xFF
            if (b1 != 0 || b0 !in 1..200) return -1
            // 校验 from/to 有效
            val fr = blob[at0 + 2].toInt() and 0xFF
            val to = blob[at0 + 3].toInt() and 0xFF
            if (fr == 0 || to == 0) return -1
            var exp = b0
            var end = at0
            var cnt = 0
            while (end + 6 <= recEnd && cnt < 400) {
                val tb0 = blob[end].toInt() and 0xFF
                val tb1 = blob[end + 1].toInt() and 0xFF
                val tf = blob[end + 2].toInt() and 0xFF
                val tt = blob[end + 3].toInt() and 0xFF
                if (tb1 == 0 && tb0 == exp && tf != 0 && tt != 0) {
                    exp = (exp % 255) + 1
                    end += 6
                    cnt++
                } else {
                    break
                }
            }
            return if (cnt >= 1) end else -1
        }

        var inBranch = false
        while (pos < recEnd) {
            // 尝试识别当前为变着块
            val vEnd = variationStart(pos)
            if (vEnd > pos) {
                pos = vEnd
                inBranch = true   // 变着块之后紧接的文本属于支节
                continue
            }
            // 否则是文本区：收集到下一个变着块起始（或记录结束）
            var segEnd = pos
            while (segEnd < recEnd) {
                val tEnd = variationStart(segEnd)
                if (tEnd > segEnd) break
                segEnd += 1
            }
            if (segEnd > pos) {
                val txt = decodeTextBlock(blob, pos, segEnd)
                if (txt.isNotEmpty()) {
                    result.add(CommentaryBlock(txt, inBranch))
                }
                // 一段文本后，若立即又接变着，则此后的文本属新支节；但正常情况下支节状态延续
                pos = segEnd
                // 若在文本区里没遇到变着，判断下一个是变着还是直接结束
                val nEnd = variationStart(pos)
                inBranch = if (nEnd > pos) true else false
            } else {
                pos = segEnd + 1
            }
        }
        return result
    }

    /** 解码一段字节为干净的 GBK 中文文本，过滤噪声；返回空串表示无可读旁白。 */
    private fun decodeTextBlock(blob: ByteArray, from: Int, to: Int): String {
        if (to <= from) return ""
        val seg = try {
            String(blob.copyOfRange(from, to), Charset.forName("GBK"))
        } catch (e: Exception) {
            return ""
        }
        val merged = seg.replace(Regex("[\u0000-\u0008\u000b\u000c\u000e-\u001f]+"), " ")
        // 按连续空行切段，逐段清洗
        val segs = merged.split(Regex("""(?:\r?\n\s*){2,}"""))
        val out = StringBuilder()
        for (segIn in segs) {
            val cleaned = segIn
                .replace(Regex("[\r\n]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '\u3000', '　', '“', '”', '"', '\t', '|')
            if (cleaned.count { it in '\u4e00'..'\u9fff' } < 4) continue
            val firstCjkIdx = cleaned.indexOfFirst { it in '\u4e00'..'\u9fff' }
            if (firstCjkIdx < 0) continue
            val sub = cleaned.substring(firstCjkIdx)
            if (sub.contains("自动生成")) continue
            out.append(sub).append('\n')
        }
        return out.toString().trim('\n')
    }

    /** 从多段中局旁白里取出"结局旁白"（取最后一段，若有）。仅保留给兼容调用。 */
    private fun extractEndCommentFromMid(mid: List<String>): String =
        mid.lastOrNull() ?: ""

    /**
     * 把多段中局旁白按走法序号均匀映射到"走法索引 -> 注解"。
     * 注解段数等于走法数时一一对应；否则按比例把 N 段分配进 M 步。
     * 返回的 map 键为走法索引（从 0 开始），值是每步的注解。
     */
    private fun distributeMoveComments(mid: List<String>, moveCount: Int): Map<Int, String> {
        if (mid.isEmpty() || moveCount <= 0) return emptyMap()
        val map = HashMap<Int, String>()
        // 最后一段已被当作结局旁白移出，这里只用前面的（不含最后一段）
        val usable = if (mid.size >= 2) mid.subList(0, mid.size - 1) else mid
        if (usable.isEmpty()) return emptyMap()
        // 均匀映射：把 usable[i] 分配给步号 round((i+1)*moveCount/(usable.size+1))
        for (i in usable.indices) {
            val step = ((i + 1).toDouble() * moveCount / (usable.size + 1).toDouble()).toInt()
            if (step > 0 && step <= moveCount) {
                map[step - 1] = usable[i]
            }
        }
        return map
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CBL 3.0 棋库编码器（导出整册用）
    //
    //  把若干局 CblGame 反向写成象棋桥「棋库」(.cbl) 原生二进制，与上面的
    //  decodeLibraryContainer / parseCbrRecord / readCbrSteps 完全互逆：
    //   · 头 101952 字节：16B "CCBridgeLibrary\0" + int32 版本(3) + 16B GUID
    //     + int32@52(0x7fffffff) + int32@60(容量=局数) + 512B 书名(UTF-16LE)；
    //   · 自 offset 101952 起每条 "CCBridge Record\0" 记录按 4096B 对齐，一条即一局；
    //   · 着法流：int32 开局旁白标记(0=无) [+ int32 字节数 + UTF-16LE]，
    //     随后递归着法 [flag][seq=0][from][to]（flag&1=本线终止、flag&2=身后有变着、
    //     flag&4=本步带旁白 + int32 字节数 + UTF-16LE），坐标 index=(9-row)*9+col。
    //  说明：写出的记录只填“识读必需的字段”，其余保留 0 —— 与开源实现一致，
    //        象棋桥/象棋笔记/本机都可正常打开，且比原压缩壳更干净（纯 CBL）。
    // ════════════════════════════════════════════════════════════════════════

    /** 棋库头+目录区固定长度（首条记录偏移）。 */
    private const val CBL_LIB_HEAD = 101952
    private const val CBL_REC_MAGIC = "CCBridge Record\u0000"

    /** 导出用着法树节点：kids[0] 为主线续着，其余为同一父局面下的变着。 */
    private class EncNode(val fr: Int, val to: Int, var annote: String?) {
        val kids = ArrayList<EncNode>()
    }

    /**
     * 编码整个棋库：games 逐局写为一条 CBR 记录，bookName 写入棋库头。
     * 产出为可直接被本机 CblDecoder 以及象棋桥/象棋笔记识读的 .cbl 字节。
     */
    fun encodeLibrary(games: List<CblGame>, bookName: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        writeLibrary(out, games.size, bookName) { i -> games.getOrNull(i) }
        return out.toByteArray()
    }

    /**
     * ★ 流式写出棋库：边编码边写目标流，不在内存里攒出整库字节 —— 上千局的大书导出也不会顶爆内存。
     * @param count  局数（写进棋库头）
     * @param gameAt 取第 i 局（i 从 0 起）；返回 null 表示该局解不出、跳过
     */
    fun writeLibrary(out: java.io.OutputStream, count: Int, bookName: String, gameAt: (Int) -> CblGame?) {
        val head = ByteArray(CBL_LIB_HEAD)
        val magic = "CCBridgeLibrary\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, head, 0, minOf(magic.size, head.size))
        putI32(head, 16, 3)                                  // 版本
        val guid = java.util.UUID.randomUUID()               // 16B 唯一标识（识读无实质影响）
        putI64(head, 20, guid.mostSignificantBits)
        putI64(head, 28, guid.leastSignificantBits)
        putI32(head, 52, 0x7fffffff)
        putI32(head, 60, count)                              // 容量 = 局数
        writeUtf16z(head, 64, 512, bookName)                 // 书名
        out.write(head)

        for (i in 0 until count) {
            val g = gameAt(i) ?: continue
            val rec = encodeRecord(g, i + 1)
            out.write(rec)
            val pad = (CBR_ALIGN - (rec.size % CBR_ALIGN)) % CBR_ALIGN
            if (pad > 0) out.write(ZERO_PAD, 0, pad)
        }
        out.flush()
    }

    private val ZERO_PAD = ByteArray(CBR_ALIGN)

    /** 编码一局 → 一条 CBR 记录（2214 头 + 着法流；外层再补 4096 对齐）。 */
    private fun encodeRecord(g: CblGame, index: Int): ByteArray {
        // ① 着法流：先开局旁白，再着法树
        val ms = java.io.ByteArrayOutputStream()
        if (g.introComment.isNotEmpty()) {
            putI32(ms, 1)
            val b = g.introComment.toByteArray(Charsets.UTF_16LE)
            putI32(ms, b.size)
            ms.write(b)
        } else {
            putI32(ms, 0)
        }
        // 主线先入（保证 kids[0] 即主线延续），支线按公共前缀并入（变招）
        val root = EncNode(-1, -1, null)
        insertLine(root, g.movesUci)
        for (v in g.variationsUci) insertLine(root, v)
        // 主线每步旁白挂到主线节点上
        if (g.moveComments.isNotEmpty()) {
            var cur: EncNode? = root.kids.firstOrNull()
            var ply = 0
            while (cur != null) {
                val node = cur
                val an = g.moveComments[ply]
                if (!an.isNullOrEmpty()) node.annote = an
                cur = node.kids.firstOrNull()
                ply++
            }
        }
        writeLevel(root.kids, ms)

        // ② 2214 记录头
        val rec = ByteArray(CBR_HEADER)
        val rm = CBL_REC_MAGIC.toByteArray(Charsets.US_ASCII)
        System.arraycopy(rm, 0, rec, 0, minOf(rm.size, rec.size))
        writeUtf16z(rec, 180, 128, g.event.ifBlank { g.title() })   // 题名
        writeUtf16z(rec, 692, 64, g.site)                            // 赛事
        writeUtf16z(rec, 1076, 64, g.red)                            // 红方
        writeUtf16z(rec, 1300, 64, g.black)                          // 黑方
        rec[2076] = when (g.result) {
            "1-0" -> 1; "0-1" -> 2; "1/2-1/2" -> 3; else -> 0
        }.toByte()
        rec[2116] = if (g.moversBlack) 0 else 1                      // 先行方(1=红先)
        encodeBoard90(rec, 2120, g.fenBoard ?: STD_FEN)              // 初始局面

        val mv = ms.toByteArray()
        val all = ByteArray(CBR_HEADER + mv.size)
        System.arraycopy(rec, 0, all, 0, rec.size)
        System.arraycopy(mv, 0, all, CBR_HEADER, mv.size)
        return all
    }

    /**
     * 写一层“兄弟着法链”：逐个写「步字节 + 本步旁白」，再写其后继子树，最后写下一兄弟(变着)。
     * 与 readCbrSteps 的读取顺序一致：hasNext 子树在前、hasVar 兄弟在后。
     */
    private fun writeLevel(kids: List<EncNode>, out: java.io.ByteArrayOutputStream) {
        for (i in kids.indices) {
            val node = kids[i]
            val hasNext = node.kids.isNotEmpty()
            val hasVar = i < kids.size - 1
            val hasAnnote = !node.annote.isNullOrEmpty()
            var mark = 0
            if (!hasNext) mark = mark or 0x01
            if (hasVar) mark = mark or 0x02
            if (hasAnnote) mark = mark or 0x04
            out.write(mark); out.write(0); out.write(node.fr); out.write(node.to)
            if (hasAnnote) {
                val b = node.annote!!.toByteArray(Charsets.UTF_16LE)
                putI32(out, b.size); out.write(b)
            }
            if (hasNext) writeLevel(node.kids, out)
        }
    }

    /** 把一条「从起始局面起的完整 UCI 线路」并入着法树；公共前缀复用，分叉处新增兄弟。 */
    private fun insertLine(root: EncNode, line: List<String>) {
        var cur = root
        for (m in line) {
            if (m.length < 4) continue
            val f = uciIdx(m, 0); val t = uciIdx(m, 2)
            if (f < 0 || t < 0) continue
            val next = cur.kids.firstOrNull { it.fr == f && it.to == t }
                ?: EncNode(f, t, null).also { cur.kids.add(it) }
            cur = next
        }
    }

    /** UCI 两字符 → 记录坐标索引：x=a..i(0..8)、y=0..9（0=红方底线）；index=(9-y)*9+x。 */
    private fun uciIdx(uci: String, at: Int): Int {
        if (at + 1 >= uci.length) return -1
        val x = uci[at] - 'a'
        val y = uci[at + 1] - '0'
        if (x !in 0..8 || y !in 0..9) return -1
        return (9 - y) * 9 + x
    }

    /** FEN 棋盘词 → 记录里的 90 字节初始局面（每格 0x11..0x17 红 / 0x21..0x27 黑）。 */
    private fun encodeBoard90(dst: ByteArray, off: Int, fenBoard: String) {
        for (i in 0 until 90) if (off + i < dst.size) dst[off + i] = 0
        val rows = fenBoard.trim().split('/')
        for (ri in 0 until minOf(10, rows.size)) {
            val y = 9 - ri                       // rows[0]=黑方底线 → y=9
            var x = 0
            for (ch in rows[ri]) {
                if (x > 8) break
                if (ch.isDigit()) { x += ch - '0'; continue }
                val code = when (ch.uppercaseChar()) {
                    'R' -> 1; 'N' -> 2; 'B' -> 3; 'A' -> 4; 'K' -> 5; 'C' -> 6; 'P' -> 7
                    else -> 0
                }
                if (code != 0 && off + (9 - y) * 9 + x < dst.size) {
                    dst[off + (9 - y) * 9 + x] =
                        (if (ch.isUpperCase()) 0x10 or code else 0x20 or code).toByte()
                }
                x++
            }
        }
    }

    private fun putI32(o: java.io.OutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v ushr 8) and 0xFF)
        o.write((v ushr 16) and 0xFF); o.write((v ushr 24) and 0xFF)
    }

    private fun putI32(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte()
        a[off + 1] = ((v ushr 8) and 0xFF).toByte()
        a[off + 2] = ((v ushr 16) and 0xFF).toByte()
        a[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putI64(a: ByteArray, off: Int, v: Long) {
        for (k in 0 until 8) a[off + k] = ((v ushr (8 * k)) and 0xFF).toByte()
    }

    /** 以 UTF-16LE 写入定长字段（留 2 字节给结尾 0；超长截断）。 */
    private fun writeUtf16z(a: ByteArray, off: Int, size: Int, text: String) {
        if (text.isEmpty()) return
        val b = text.toByteArray(Charsets.UTF_16LE)
        val n = minOf(b.size, maxOf(0, size - 2))
        System.arraycopy(b, 0, a, off, n)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  象棋桥「旧格式」(CCBridge 2.x) 整书写出
    //
    //  为什么单列一套：第三方打谱软件（中国象棋对弈打谱 1.87 等）只认这一种老格式，
    //  不认下面的 CCBridgeLibrary(CBL 3.0) 棋库。结构与字节取值均以象棋桥自身导出的
    //  象棋残局基础.cbl 逐字节实测校准：
    //   · 外层：0..7 "CCBridge"；8..11 i32 版本(21)；12..15 i32 压缩法(1=zlib)；
    //          16..19 i32 未压缩长度；20.. 起整段 zlib 流。
    //   · blob 书头：i32书名长+书名(GBK) + 8个0 + i32系列长+系列(GBK) + i32作者长+作者(GBK)
    //              + 19B 日期A + 19B 日期B + i32局数 + i32简介长+简介(GBK)。
    //   · 记录：i32 长度(=记录体长+4) + 记录体。
    //   · 记录体：32B 初始局面 + i32(4) + i32(0) + i32(1) + i32题名长+题名(GBK) + 24个0
    //           + 字段[ i32标记 + i32长度 + GBK ]* + 1B 标志(1) + 19B 日期A + 19B 日期B
    //           + i32步数 + 走法[每步6字节：序号,0,from,to,0,0] + 结尾旁白(GBK, 可选)。
    //   · 初始局面 32B：前16=红方按 [车马相仕帅仕相马车炮炮兵兵兵兵兵] 顺序，后16=黑方同序；
    //           每格字节 = (列+1)<<4 | (10-行)，缺子写 0。
    //   · 走法字节：from = 列<<4 | (9-行)，to 同（列 0..8 红左→右，行 0 红底线）。
    // ════════════════════════════════════════════════════════════════════════

    private val GBK: Charset = Charset.forName("GBK")

    /** 旧格式书头里除书名外的附带信息。 */
    class LegacyBookMeta(val series: String = "", val author: String = "", val intro: String = "")

    private val LEGACY_SLOT_TYPES =
        charArrayOf('R', 'N', 'B', 'A', 'K', 'A', 'B', 'N', 'R', 'C', 'C', 'P', 'P', 'P', 'P', 'P')

    /**
     * ★ 流式写出「旧格式」整册 .cbl（第三方打谱软件可读）。
     *
     * 大书也不顶内存：先落一份未压缩 blob 到临时文件（顺带回填真实局数），再以 zlib 边读边压
     * 写入 out。写完即删临时文件。
     *
     * @param count  待导出局数（gameAt 返回 null 的局自动跳过，最终以实际写入数为准）
     * @param tmpDir 临时文件目录（传 context.cacheDir）
     * @param gameAt 取第 i 局（i 从 0 起）；返回 null 表示该局解不出、跳过
     * @param onEncoded  逐局编码进度回调（done 为已处理局数、total 为 count）；回调很密，调用方自行节流
     * @param onCompress 压缩落盘进度回调（done 为已压缩字节、total 为未压缩总字节）
     */
    fun writeLegacyBook(
        out: java.io.OutputStream,
        count: Int,
        bookName: String,
        meta: LegacyBookMeta,
        tmpDir: java.io.File,
        gameAt: (Int) -> CblGame?,
        onEncoded: ((done: Int, total: Int) -> Unit)? = null,
        onCompress: ((done: Long, total: Long) -> Unit)? = null
    ) {
        val tmp = java.io.File.createTempFile("cblblob", ".tmp", tmpDir)
        var blobLen: Long
        try {
            java.io.RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                // ---- 书头 ----
                raf.write(gbkField(bookName))
                raf.write(ByteArray(8))
                raf.write(gbkField(meta.series))
                raf.write(gbkField(meta.author))
                raf.write(legacyDateShort())
                raf.write(legacyDateFull())
                val countPos = raf.filePointer
                raf.write(i32Bytes(0))                       // 局数（写完记录回填）
                raf.write(gbkField(meta.intro))
                // ---- 逐局记录 ----
                var written = 0
                for (i in 0 until count) {
                    val g = gameAt(i) ?: continue
                    val body = encodeLegacyRecord(g)
                    raf.write(i32Bytes(body.size + 4))       // 长度 = 记录体长 + 4
                    raf.write(body)
                    written++
                    onEncoded?.invoke(written, count)
                }
                blobLen = raf.length()
                raf.seek(countPos)
                raf.write(i32Bytes(written))                 // 回填真实局数
            }
            // ---- 外层：CCBridge + 版本 + 压缩法 + 未压缩长度 + zlib ----
            val magic = "CCBridge".toByteArray(Charsets.US_ASCII)
            out.write(magic, 0, magic.size)
            val ver = i32Bytes(21); out.write(ver, 0, 4)
            val comp = i32Bytes(1); out.write(comp, 0, 4)
            val rawLen = i32Bytes(blobLen.toInt()); out.write(rawLen, 0, 4)
            val dos = java.util.zip.DeflaterOutputStream(
                out, java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION), 1 shl 16
            )
            java.io.BufferedInputStream(java.io.FileInputStream(tmp)).use { ins ->
                val buf = ByteArray(1 shl 16)
                var copied = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    dos.write(buf, 0, n)
                    copied += n
                    onCompress?.invoke(copied, blobLen)
                }
            }
            dos.finish()
            dos.flush()
            out.flush()
        } finally {
            tmp.delete()
        }
    }

    /**
     * 编码一局 → 一条旧格式记录体。
     *
     * 布局与象棋桥自身导出逐字节对齐（已用真实文件 re-encode 校验为完全相同）：
     *   32B 初始局面 | i32 类型(4) | i32 0 | i32 1 | 题名(GBK 长度前缀，≤65B)
     *   | 24 个 0 | 字段[(i32 标记, i32 长度, GBK)…] | 1B 标志(1) | 19B 日期A | 19B 日期B
     *   | i32 走法条数 | 走法条目[6B: 手数,0,from,to,X,Y]×N | 结尾旁白(GBK)
     *
     * 三处关键校准（都是"在我们这边能读、到象棋桥/CNVCS 那边读不动/闪退"的根因）：
     *   1) 题名必须 ≤65 字节：象棋桥把题名读进定长缓冲，超长会溢出崩溃。原样写
     *      元数据启发式挑出的长旁白（上百字节）正是闪退主因 —— 这里按 GBK 边界截断到 65B。
     *   2) 每条记录固定带第 3 个字段（标记 0，正文以 "||" 起头，可为空 = "||"）：象棋桥
     *      无条件按三字段读，缺一就会把后面的标志/日期/走法读错位。
     *   3) 走法条目第 5/6 字节不是 0 占位，而是 X/Y 描述字节（见 encodeLegacyMoveEntry）；
     *      6 条走法上位机实测规律，全部对齐。
     */
    private fun encodeLegacyRecord(g: CblGame): ByteArray {
        val b = java.io.ByteArrayOutputStream(1024)
        fun w(bytes: ByteArray) = b.write(bytes, 0, bytes.size)
        val board = ByteArray(32)
        encodeLegacyBoard32(board, 0, g.fenBoard ?: STD_FEN)
        w(board)
        w(i32Bytes(4))                                       // 类型
        w(i32Bytes(0))
        w(i32Bytes(1))
        w(legacyTitleField(g))                               // 题名（≤65B）
        w(ByteArray(24))
        // —— 字段区：红(标记=结局) / 黑(tag0) / 旁白(tag0，"||" 起头)。三个都写，缺位补空。——
        // ★ 第 1 个字段的标记不是固定值：象棋桥把它当“结局码”用（1=红胜 2=黑胜 3=和/其它），
        //   与红方姓名同处一个字段。原先硬写 1，害得所有非红胜局在象棋桥/CNVCS 里被当成红胜。
        w(gbkFieldAt(resultTagOf(g.result), g.red.takeIf { it.isNotEmpty() && it != "?" } ?: ""))
        w(gbkFieldAt(0, g.black.takeIf { it.isNotEmpty() && it != "?" } ?: ""))
        w(gbkFieldAt(0, "||" + g.introComment))
        // 日期块：象棋桥记录内的"日期A/日期B"都是短格式（`yy-M-d H:mm:ss`，19B 右补空格）；
        // 只有书头（writeLegacyBook）的第二枚日期才是完整格式。原先此处误写完整格式，虽不致命，
        // 但与象棋桥自身导出的字节不一致——对齐成短格式，向基准文件靠齐。
        // ★ 标志字节 = 先行方（1=红先，0=黑先）。这是象棋桥唯一交代“轮到谁走”的地方：
        //   初始局面只记棋子位置、不记轮次。原先无脑写 1，黑先的残局到了 CNVCS 那边被当成红先，
        //   第一手就是黑子行棋 → 开局即非法走法 → 一点即闪退。全库 246 局黑先局零例外。
        b.write(if (g.moversBlack) 0 else 1)
        w(legacyDateShort())
        w(legacyDateShort())
        // —— 走法块：主线 + 全部变着还原成"手数扁平串"（象棋桥的着法树存储法）——
        // ★ 只有局面、没有任何着法的记录（成批 FEN 习题：5-10步缓杀 50000题 这类）：
        //   象棋桥/CNVCS 把「0 着法」的记录当作章标题/空条目处理，不可打开；整本几
        //   十万题全是这种记录时，第三方打谱软件就"无法识别"整本书。这里对空着法
        //   记录按 synthLegacyOpener 补 3 手合法着法，使每条记录都成为一盘可打开的对局
        //   （局面仍是习题原局面）。
        val entries = encodeLegacyMoveTree(g).ifEmpty { synthLegacyOpener(g) }
        w(i32Bytes(entries.size))
        for (e in entries) w(e)
        // —— 旁白区（走法块之后）——
        // ★ 象棋桥/CNVCS 的硬约定：走法块之后必须"每一条走法各跟一个 \r\n 标记"，
        //   某条走法若带旁白，正文紧接在该走法的 \r\n 之后；旁白区里 \r\n 的个数
        //   恒等于走法条数（象棋桥自身导出的 256 局逐局实测：crlf==movcnt）。
        //   读方正是靠数 \r\n 来定位手数、切分旁白的 —— 旧版把整段旁白当成裸文本
        //   直接续在走法后、又不写这些标记，读方手数错位、把旁白当二进制解，
        //   顶栏就出现 `□*:JJz姊(?7Ww?…` 这种"棋盘字节被当文字"的乱码。
        writeLegacyCommentRegion(b, g, entries.size)
        return b.toByteArray()
    }

    /** 题名（GBK，按字符边界截到 ≤65 字节）。优先用对局名(event)，退回 title()。 */
    private fun legacyTitleField(g: CblGame): ByteArray {
        val src = g.event.takeIf { it.isNotEmpty() && it != "?" } ?: g.title()
        val sb = StringBuilder()
        var len = 0
        for (ch in src) {
            val n = ch.toString().toByteArray(GBK).size
            if (len + n > 65) break
            len += n
            sb.append(ch)
        }
        val d = sb.toString().toByteArray(GBK)
        val a = ByteArray(4 + d.size); putI32(a, 0, d.size)
        System.arraycopy(d, 0, a, 4, d.size)
        return a
    }

    /**
     * 写出旧格式记录的"旁白区"：走法块之后，为每一条走法各写一个 `\r\n` 标记；
     * 某条走法若带旁白，正文紧接在该走法的 `\r\n` 之后。旁白区里 `\r\n` 的个数
     * 恒等于走法条数 —— 这是象棋桥/CNVCS 定位手数、切分旁白所依赖的唯一依据。
     *
     * 旁白从 midComments（有序段落）按与解码端 distributeMoveComments 对称的比例
     * 分配到各走法；末尾总评（endComment）附在最后一条走法之后。
     */
    private fun writeLegacyCommentRegion(
        out: java.io.ByteArrayOutputStream,
        g: CblGame,
        moveCount: Int
    ) {
        val crlf = byteArrayOf(0x0D, 0x0A)
        // 段落集合：优先 midComments（含结局段），缺失则退回 endComment
        val segs = g.midComments.filter { it.isNotBlank() }
            .ifEmpty { listOfNotNull(g.endComment.takeIf { it.isNotBlank() }) }
        // 逐条走法落一个 \r\n；按比例把旁白挂到对应走法之后
        val attach = HashMap<Int, String>()
        if (segs.isNotEmpty() && moveCount > 0) {
            for (i in segs.indices) {
                val step = ((i + 1).toDouble() * moveCount / (segs.size + 1).toDouble()).toInt()
                val idx = (step - 1).coerceIn(0, moveCount - 1)
                val prev = attach[idx]
                attach[idx] = if (prev == null) segs[i] else "$prev ${segs[i]}"
            }
        }
        for (i in 0 until moveCount) {
            out.write(crlf, 0, crlf.size)
            val c = attach[i]?.replace(Regex("[\\r\\n]+"), " ")?.trim()
            if (!c.isNullOrEmpty()) {
                val d = c.toByteArray(GBK)
                out.write(d, 0, d.size)
            }
        }
    }

    /**
     * 把"主线 + 变着支线"还原为象棋桥的扁平走法串：对局面着法树做先序遍历，
     * 每层第一个孩子即主线延续，其余孩子（变着）在该子树走完后逐个补出 —— 与象棋桥
     * 自身导出的字节序完全一致（手数字节 = 该步所处的层数，回溯时层数自然回退）。
     */
    private fun encodeLegacyMoveTree(g: CblGame): List<ByteArray> {
        class N { val kids = LinkedHashMap<String, N>() }
        val root = N()
        fun insert(line: List<String>) {
            var cur = root
            for (m in line) {
                val k = m.trim()
                if (k.length < 4) continue
                cur = cur.kids.getOrPut(k) { N() }
            }
        }
        insert(g.movesUci)
        for (v in g.variationsUci) if (v.isNotEmpty()) insert(v)

        val grid = legacyGridOf(g.fenBoard ?: STD_FEN)
        val startRed = !g.moversBlack
        val out = ArrayList<ByteArray>()
        fun step(node: N, ply: Int, board: Array<Array<Char?>>) {
            for ((mv, child) in node.kids) {
                val fc = mv[0] - 'a'; val fr = mv[1] - '0'
                val tc = mv[2] - 'a'; val tr = mv[3] - '0'
                if (fc !in 0..8 || fr !in 0..9 || tc !in 0..8 || tr !in 0..9) continue
                val pc = board[fc][fr] ?: continue
                val red = if (ply % 2 == 1) startRed else !startRed
                out.add(encodeLegacyMoveEntry(pc, fc, fr, tc, tr, red, ply))
                val ng = Array(9) { board[it].clone() }
                ng[tc][tr] = pc; ng[fc][fr] = null
                step(child, ply + 1, ng)
            }
        }
        step(root, 1, grid)
        return out
    }

    /**
     * 一条 6 字节走法：`手数, 0, from, to, X, Y`。
     *   from/to：列<<4 | (9-行)（列 0..8 红左→右，行 0 红底线）。
     *   X = (子种码<<4) | 己方视角列；子种码 车0 马1 相2 仕3 帅4 炮5 兵6，
     *       己方视角列 = 红 (8-列) / 黑 列。
     *   Y：横走 = 0x20 | 己方视角目标列；
     *      竖走 = (向前?0x00:0x10) | (步数-1)；★ 步数必须写进去，单写方向位
     *            会让象棋桥/CNVCS 把"车二进三"当成"进一"，走法错乱＝闪退；
     *      斜走 = (向前?0x00:0x10) | 己方视角目标列。
     *       向前 = 红行增大 / 黑行减小。
     */
    private fun encodeLegacyMoveEntry(
        pc: Char, fc: Int, fr: Int, tc: Int, tr: Int, red: Boolean, ply: Int
    ): ByteArray {
        val code = when (pc.uppercaseChar()) {
            'R' -> 0; 'N' -> 1; 'B' -> 2; 'A' -> 3; 'K' -> 4; 'C' -> 5; 'P' -> 6; else -> 0
        }
        val ownFrom = if (red) 8 - fc else fc
        val ownTo = if (red) 8 - tc else tc
        val x = (code shl 4) or (ownFrom and 0x0F)
        val dr = tr - fr
        val dc = tc - fc
        val forward = if (red) dr > 0 else dr < 0
        val dirHi = if (forward) 0x00 else 0x10
        val y = when {
            dc == 0 -> dirHi or ((kotlin.math.abs(dr) - 1) and 0x0F)   // 竖走：方向 | (步数-1)
            dr == 0 -> 0x20 or (ownTo and 0x0F)                        // 横走：0x20 | 己方视角目标列
            else -> dirHi or (ownTo and 0x0F)                          // 斜走：方向 | 己方视角目标列
        }
        return byteArrayOf(
            (ply and 0xFF).toByte(), 0,
            ((fc shl 4) or (9 - fr)).toByte(),
            ((tc shl 4) or (9 - tr)).toByte(),
            x.toByte(), y.toByte()
        )
    }

    /** 纯局面记录补出的着法手数（见 synthLegacyOpener 注释）。 */
    private const val SYNTH_PLIES = 3

    /**
     * ★ 给「只有局面、没有任何着法」的记录（成批 FEN 习题：5-10步缓杀 50000题 这类）补着法。
     *
     * 根因：象棋桥/CNVCS 的读方把 `步数 == 0` 的记录当作「章标题 / 空条目」处理，不会把它
     * 当成一盘可打开的棋谱；整本书若 499306 条全是这种记录，第三方打谱软件算出的「棋谱数」
     * 就是 0，于是整本书被判成「非棋谱文件」——表现就是"无法识别 / 不显示"。
     *
     * 做法：从该局面出发，按「先行方 → 对方 → 先行方」补 3 手**真实合法**的着法（每手都取
     * 该局面下第一条合法着法，且走后己方帅/将不被攻击）。3 手这个数不是随手定的：
     *   · 象棋桥自家的成批题库（古谱_11 桔中秘残局谱、古谱_28 适情雅趣…）里，真实记录的
     *     着法数就大量落在 1~3 手（实测：1 手 101 条、2 手 10 条、3 手 32 条），补 1~3 手都
     *     属于象棋桥"正常记录"的取值范围，绝不会被当成空条目；
     *   · App 自己的解码器（decodeBook）把「< 3 手」的记录当噪声丢弃，补 3 手才能让导出文件
     *     被 App 自己重新导入时也认得出，不至于"自己导的自己也读不出"。
     * 局面本体（32 字节棋盘块）原样保留 —— 第三方打开看到的仍是习题原局面。
     * 若该局面先行方无合法着法（理论上的杀局不会出现），返回空列表，该条保持 0 着法。
     */
    private fun synthLegacyOpener(g: CblGame): List<ByteArray> {
        val board = Board()
        val src = legacyGridOf(g.fenBoard ?: STD_FEN)
        for (c in 0..8) for (r in 0..9) board.grid[c][r] = src[c][r]
        board.red = !g.moversBlack
        val out = ArrayList<ByteArray>(SYNTH_PLIES)
        var ply = 1
        while (ply <= SYNTH_PLIES) {
            val redTurn = board.red
            var fc = -1; var fr = -1; var tc = -1; var tr = -1
            // legalMovesFrom 已排除"照面"着法；isMoveSafe 再验一次不得自将（走后己方帅/将
            // 不被攻击），确保补出的每一手都是真合法着法 —— 第三方若校验着法合法也不会读崩。
            outer@ for (c in 0..8) for (r in 0..9) {
                val pc = board.grid[c][r] ?: continue
                if (pc.isUpperCase() != redTurn) continue
                for (to in board.legalMovesFrom(c, r)) {
                    if (!isMoveSafe(board, c, r, to.first, to.second, redTurn)) continue
                    fc = c; fr = r; tc = to.first; tr = to.second
                    break@outer
                }
            }
            if (fc < 0) break
            val pc = board.grid[fc][fr] ?: break
            out.add(encodeLegacyMoveEntry(pc, fc, fr, tc, tr, redTurn, ply))
            board.apply(fc to fr, tc to tr)
            ply++
        }
        return out
    }

    /** 己方帅/将所在格；找不到返回 null。 */
    private fun kingSquare(grid: Array<Array<Char?>>, red: Boolean): Pair<Int, Int>? {
        for (c in 0..8) for (r in 0..9) {
            val p = grid[c][r] ?: continue
            if (p.uppercaseChar() == 'K' && p.isUpperCase() == red) return c to r
        }
        return null
    }

    /**
     * 在 [board] 上试走 (fc,fr)→(tc,tr) 后，己方帅/将是否会被对方任何一子攻击。
     * 只在内存里试走再原样还原，不改动棋盘。
     */
    private fun isMoveSafe(board: Board, fc: Int, fr: Int, tc: Int, tr: Int, red: Boolean): Boolean {
        val moved = board.grid[fc][fr]
        val captured = board.grid[tc][tr]
        board.grid[tc][tr] = moved
        board.grid[fc][fr] = null
        val ks = kingSquare(board.grid, red)
        var safe = true
        if (ks != null) {
            outer@ for (c in 0..8) for (r in 0..9) {
                val p = board.grid[c][r] ?: continue
                if (p.isUpperCase() == red) continue
                if (board.legalMovesFrom(c, r).any { it.first == ks.first && it.second == ks.second }) {
                    safe = false
                    break@outer
                }
            }
        }
        board.grid[fc][fr] = moved
        board.grid[tc][tr] = captured
        return safe
    }

    /** FEN 棋盘词 → 9×10 字符格（列 0..8 红左→右，行 0 红底线），供走法 X/Y 推算。 */
    private fun legacyGridOf(fen: String): Array<Array<Char?>> {
        val g = Array(9) { arrayOfNulls<Char>(10) }
        val rows = fen.trim().split('/')
        for (ri in 0 until minOf(10, rows.size)) {
            val row = 9 - ri
            var col = 0
            for (ch in rows[ri]) {
                if (col > 8) break
                if (ch.isDigit()) { col += ch - '0'; continue }
                g[col][row] = ch
                col++
            }
        }
        return g
    }

    /** FEN 棋盘词 → 旧格式 32 字节初始局面（按固定子序落位）。 */
    private fun encodeLegacyBoard32(dst: ByteArray, off: Int, fenBoard: String) {
        java.util.Arrays.fill(dst, off, off + 32, 0.toByte())
        val redMap = HashMap<Char, ArrayList<Byte>>()
        val blackMap = HashMap<Char, ArrayList<Byte>>()
        val rows = fenBoard.trim().split('/')
        for (ri in 0 until minOf(10, rows.size)) {
            val row = 9 - ri
            var col = 0
            for (ch in rows[ri]) {
                if (col > 8) break
                if (ch.isDigit()) { col += ch - '0'; continue }
                val code = ((col + 1) shl 4) or (10 - row)
                if (ch.isUpperCase()) redMap.getOrPut(ch) { ArrayList() }.add(code.toByte())
                else blackMap.getOrPut(ch.uppercaseChar()) { ArrayList() }.add(code.toByte())
                col++
            }
        }
        for (i in 0 until 16) {
            val t = LEGACY_SLOT_TYPES[i]
            dst[off + i] = popCoord(redMap, t)
            dst[off + 16 + i] = popCoord(blackMap, t)
        }
    }

    private fun popCoord(map: HashMap<Char, ArrayList<Byte>>, type: Char): Byte {
        val list = map[type] ?: return 0
        return if (list.isEmpty()) 0 else list.removeAt(0)
    }

    private fun i32Bytes(v: Int): ByteArray {
        val a = ByteArray(4); putI32(a, 0, v); return a
    }

    /** i32 长度前缀 + GBK 字节。 */
    private fun gbkField(s: String): ByteArray {
        val d = s.toByteArray(GBK)
        val a = ByteArray(4 + d.size); putI32(a, 0, d.size)
        System.arraycopy(d, 0, a, 4, d.size)
        return a
    }

    /** 记录字段：i32 标记 + i32 长度 + GBK 内容。 */
    private fun gbkFieldAt(tag: Int, s: String): ByteArray {
        val d = s.toByteArray(GBK)
        val a = ByteArray(8 + d.size); putI32(a, 0, tag); putI32(a, 4, d.size)
        System.arraycopy(d, 0, a, 8, d.size)
        return a
    }

    /**
     * 旧格式记录第 1 个字段的标记 = 结局码（与红方姓名同字段）：
     * 1=红胜、2=黑胜、3=和棋（其余未知一律归 3）。象棋桥/CNVCS 靠它判定胜负。
     */
    private fun resultTagOf(result: String): Int = when (result.trim()) {
        "1-0" -> 1
        "0-1" -> 2
        else -> 3
    }

    /** 19 字节日期 A：如 "<yy>-<M>-<d> <H>:<mm>:<ss>"，右补空格。 */
    private fun legacyDateShort(): ByteArray =
        fixed19(java.text.SimpleDateFormat("yy-M-d H:mm:ss", java.util.Locale.CHINESE).format(java.util.Date()))

    /** 19 字节日期 B：如 "yyyy-MM-dd HH:mm:ss"。 */
    private fun legacyDateFull(): ByteArray =
        fixed19(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINESE).format(java.util.Date()))

    private fun fixed19(s: String): ByteArray {
        val a = ByteArray(19)
        java.util.Arrays.fill(a, ' '.code.toByte())
        val d = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(d, 0, a, 0, minOf(d.size, 19))
        return a
    }
}