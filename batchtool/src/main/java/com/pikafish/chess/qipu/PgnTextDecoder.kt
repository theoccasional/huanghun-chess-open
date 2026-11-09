package com.pikafish.chess.qipu

import com.pikafish.chess.engine.NotationParser

/**
 * ★ 纯文本棋谱（PGN / 中文记谱 / 挑战棋谱 TXT）解码器。
 *
 * 为什么需要它：宝库的解码链路（[QipuLibrary.decodeGames] 与压缩包条目分流
 * [QipuLibrary.decodeEntryGames]）此前只认二进制棋谱（CBL/CBR/MXQ/XQF）、压缩包、
 * CHM 电子书、东萍 DhtmlXQ 与成批 FEN 题库 —— **独独不认「PGN / 中文记谱」这种最普通的
 * 文本棋谱**。于是：
 *   · 挑战模式存下的「挑战棋谱·xxx.txt」（含 [Event] 头 + “1. 炮八平五 砲2平5”中文记谱）
 *     进了宝库点开只会「解不出、回弹主界面」；
 *   · 把这类 txt 打进 ZIP 上传到宝库，整包一条都出不来。
 * 本类按【内容】认谱（不看后缀名），用与主界面导入同一份解析内核 [NotationParser] 解析，
 * 一次把一局（或多局）完整还原成可回放、可背谱的棋局，并保留变招支线与逐手旁白。
 *
 * 不做的事：不解二进制（CBL/MXQ/XQF…）、不解东萍 DhtmlXQ、不解成批 FEN 题库 ——
 * 这些各自有更专的分支，且都排在调用方更靠前的位置。
 */
object PgnTextDecoder {

    /** 中文记谱着法行：“1. 炮八平五” / “1．车九平八” / “1、马八进七”。 */
    private val CN_MOVE_LINE = Regex(
        "(?m)^\\s*\\d+\\s*[.．、]\\s*(?:[车車馬马象相士仕将將帅帥炮砲兵卒]|[前中后])"
    )

    /** 「新一局」的起手行（空行分块时用）：以回合序号 1 开头的中文着法行。 */
    private val FIRST_MOVE_LINE = Regex(
        "(?m)^\\s*1\\s*[.．、]\\s*(?:[车車馬马象相士仕将將帅帥炮砲兵卒]|[前中后])"
    )

    /** PGN 头标签起始（`[Event "…"]`）。 */
    private val EVENT_HEAD = Regex("(?m)(?=^\\s*\\[Event\\s)")

    /** 空行（分隔两局的常见写法）。 */
    private val BLANK_LINE = Regex("(?m)\\n[ \\t\\r]*\\n")

    /** UCI 走法（如 h2e2）：用于识别“只有走法串、没有中文”的纯 UCI 文本谱。 */
    private val UCI_MOVE = Regex("[a-i][0-9][a-i][0-9]")

    private const val CN_ACTIONS = "进進退平"

    /** 含中文字符（用于判断是否为“中文记谱”而非纯 ASCII 文本）。 */
    private val HAS_CJK = Regex("[\\u4e00-\\u9fa5]")

    /**
     * 是否像「PGN / 中文记谱」文本棋谱（只看内容，不看后缀名）。
     *
     * 先做二进制护栏（前 8KB 出现 NUL 即否决），再要求命中以下任一强特征：
     *   · PGN 头（[Event / [Red / [Black / [FEN）；
     *   · 中文记谱着法行（“N. 车马炮…”）；
     *   · 成串 UCI 走法（≥4 手）且含中文。
     */
    fun isPgnLike(raw: ByteArray): Boolean {
        if (raw.isEmpty()) return false
        val probe = minOf(raw.size, 8192)
        for (i in 0 until probe) if (raw[i] == 0.toByte()) return false
        val text = try { QipuLibrary.decodeText(raw) } catch (e: Exception) { return false }
        return isPgnLikeText(text)
    }

    /** 文本版判定（供已解码的字符串复用）。 */
    fun isPgnLikeText(text: String): Boolean {
        val c = text.trim()
        if (c.isEmpty()) return false
        if (c.contains("[Event") || c.contains("[Red") || c.contains("[Black") ||
            c.contains("[FEN") || c.contains("[Fen")) return true
        if (CN_MOVE_LINE.containsMatchIn(c)) return true
        // 纯 UCI 走法串：需含中文（避免把英文散文/日志误判）
        if (HAS_CJK.containsMatchIn(c) && UCI_MOVE.findAll(c).take(4).count() >= 4) return true
        return false
    }

    /**
     * 解码一份文本棋谱为棋局列表。
     * 一局都解不出（无有效着法）时返回空表 —— 调用方据此回退到原有分支，绝不误抢。
     */
    fun decode(raw: ByteArray, bookName: String): List<QipuGame> {
        val text = try { QipuLibrary.decodeText(raw) } catch (e: Exception) { return emptyList() }
        if (!isPgnLikeText(text)) return emptyList()
        val out = ArrayList<QipuGame>()
        var idx = 0
        for (block in splitGames(text)) {
            idx++
            val g = try { parseBlock(block, idx, bookName) } catch (e: Exception) { null } ?: continue
            if (g.movesUci.isEmpty()) continue
            out.add(QipuGame(g))
        }
        return out
    }

    /**
     * 把整份文本切成「一局一块」：
     *   · 有多个 [Event 头 → 直接按 [Event 头切（标准 PGN 多局文件）；
     *   · 否则按空行分块，并把“以 1. 起手的块”视为新一局（文件头与走法区被空行分开的写法也能归回同一局）。
     */
    private fun splitGames(text: String): List<String> {
        val byEvent = text.split(EVENT_HEAD).map { it.trim() }.filter { it.isNotEmpty() }
        if (byEvent.size > 1) return byEvent
        val chunks = text.split(BLANK_LINE).map { it.trim() }.filter { it.isNotEmpty() }
        if (chunks.size <= 1) return chunks
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var curHasMoves = false
        for (ch in chunks) {
            val newGame = cur.isNotEmpty() && curHasMoves &&
                (FIRST_MOVE_LINE.containsMatchIn(ch) || ch.startsWith("[Event"))
            if (newGame) { out.add(cur.toString()); cur.setLength(0); curHasMoves = false }
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(ch)
            if (ch.any { it in CN_ACTIONS }) curHasMoves = true
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** 解析一局：读头标签 → 剥掉头行留走法区 → 交给统一解析内核还原主线/变招/旁白。 */
    private fun parseBlock(block: String, index: Int, bookName: String): CblDecoder.CblGame? {
        val event = header(block, "Event")
        val red = header(block, "Red")
        val black = header(block, "Black")
        val site = header(block, "Site")
        val date = header(block, "Date")
        val result = header(block, "Result")
        val fenTag = header(block, "FEN").ifBlank { header(block, "Fen") }

        // 剥掉 [Tag "…"] 头行，只留走法区交给解析内核（避免头标签混进着法分词）
        val movetext = block.lineSequence()
            .filterNot { line -> line.trimStart().startsWith("[") && line.contains("]") }
            .joinToString("\n")
        if (movetext.isBlank()) return null

        val initialFen = fenTag.trim().ifBlank { CblDecoder.STD_FEN }
        val parsed = NotationParser.parse(movetext, initialFen)
        if (parsed.mainLine.isEmpty()) return null

        val vars = NotationParser.flattenLines(parsed.mainLine, parsed.variations)

        val fenParts = initialFen.split(' ')
        val boardPart = fenParts.getOrNull(0).orEmpty()
        val moversBlack = fenParts.getOrNull(1) == "b"
        val fenBoard = boardPart.takeIf { it.isNotBlank() && it != CblDecoder.STD_FEN }

        return CblDecoder.CblGame(
            index = index,
            event = event.ifBlank { bookName },
            site = site, date = date, red = red, black = black, result = result,
            fenBoard = fenBoard, moversBlack = moversBlack,
            movesUci = parsed.mainLine,
            introComment = "", endComment = "",
            midComments = emptyList(),
            moveComments = parsed.comments,
            commentaryBlocks = emptyList(),
            variationsUci = vars
        )
    }

    /** 取一个 `[Key "value"]` 头标签的值（无则空串）。 */
    private fun header(block: String, key: String): String =
        Regex("""\[$key\s+"([^"]*)"]""").find(block)?.groupValues?.get(1)?.trim().orEmpty()
}
