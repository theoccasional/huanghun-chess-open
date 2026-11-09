package com.pikafish.chess.engine

/**
 * ★★★ 当前引擎身份（全局唯一一份）★★★
 *
 * 引擎自己会在 UCI 握手里报出真实的 "id name / id author"（例如 "Pikafish 2026-09-25"），
 * 这是引擎内部最真实、最本质的特征 —— 本对象把它记下来，供：
 *   1) 顶栏文案（"皮卡鱼 2026-09-25 · 4线程 · 哈希256MB"）；
 *   2) 人话解说（"皮卡鱼心里原来是这么想的…" / 换成别的引擎就报它的名字）；
 *   3) 加载 / 恢复时的"引擎特征"气泡弹窗。
 *
 * 名字规则（用户钦定）：认识的中文名说中文（皮卡鱼 / 雪鱼 / 象眼），
 * 不认识的就照它的英文名念（比如 UFO 就说 UFO）。
 */
object EngineIdentity {

    /** UCI 原样 id name，如 "Pikafish 2026-09-25" */
    @Volatile var idName: String = ""
    /** UCI id author */
    @Volatile var author: String = ""
    /** 是否来自用户外置引擎包 */
    @Volatile var external: Boolean = false
    /** 实际运行的引擎二进制文件名（内置=libpikafish_bin.so） */
    @Volatile var engineFileName: String = ""
    /** 引擎二进制绝对路径 */
    @Volatile var enginePath: String = ""
    /** 引擎二进制体积（字节） */
    @Volatile var engineSize: Long = 0L
    /** 实际使用的权重文件名 */
    @Volatile var weightFileName: String = ""
    /** 权重绝对路径 */
    @Volatile var weightPath: String = ""
    /** 权重体积（字节） */
    @Volatile var weightSize: Long = 0L
    /** 外置包原文件名（"Pikafish260925.zip"），内置时为空 */
    @Volatile var packageName: String = ""
    /** 记录时间 */
    @Volatile var updatedAt: Long = 0L
    /** 是否已经拿到过真实身份（至少完成过一次 UCI 握手） */
    @Volatile var known: Boolean = false
    /**
     * ★ 是否来自「异地引擎」——算棋整个由配对上的辎重包端（电脑 / 另一台手机）完成。
     *  置真后顶栏与弹窗都会额外标出「电脑」二字，让人一眼看清：算棋的家伙在电脑那边，
     *  本机只负责界面与触摸。与 {@link external}（本机装的外置引擎包）互斥。
     */
    @Volatile var remote: Boolean = false

    /** ★ 从一批 UCI 握手回包里解出 "id name / id author"（不改全局状态，供各引擎实例各记各的） */
    fun parseUciIdentity(uciLines: List<String>): Pair<String, String> {
        var id = ""
        var au = ""
        for (line in uciLines) {
            val t = line.trim()
            if (t.startsWith("id name", ignoreCase = true)) {
                id = t.substring(7).trim()
            } else if (t.startsWith("id author", ignoreCase = true)) {
                au = t.substring(9).trim()
            }
        }
        return Pair(id, au)
    }

    /** ★ UCI 握手时吸收引擎自报的身份（最可信的来源） */
    fun absorbUci(uciLines: List<String>) {
        val (id, au) = parseUciIdentity(uciLines)
        if (id.isNotEmpty()) idName = id
        if (au.isNotEmpty()) author = au
        if (id.isNotEmpty() || au.isNotEmpty()) {
            known = true
            updatedAt = System.currentTimeMillis()
        }
    }

    /** 短名：用于顶栏与人话解说（中文优先，其次英文原名） */
    val shortName: String
        get() {
            val raw = idName.ifBlank { engineFileName }
            return chineseNameFor(raw)
        }

    /** 版本 / 构建日期文案（"2026-09-25"），取不到则为空。
     *  ★ 不只认 2026-09-25：也认 20260925、260925（Pikafish260925.zip）、2026/09/25、
     *    2026.09.25 —— 外置包名/文件名里常见的写法都要能认出来，顶栏才不至于只剩一个光秃秃的名字。
     *  ★ 异地模式只认对方引擎自报的 id name：本机的包名/文件名不代表辎重包端那台引擎。 */
    val versionText: String
        get() {
            findDate(idName)?.let { return it }
            if (remote) return ""
            return findDate(packageName)
                ?: findDate(engineFileName)
                ?: findDate(weightFileName)
                ?: ""
        }

    /** 顶栏文案："皮卡鱼 2026-09-25（异地）· 4线程 · 哈希256MB"
     *  ★ 这里标注"哈希"，避免用户把引擎搜索哈希误读成开局库体积上限。 */
    fun topBarText(hash: Int, threads: Int): String {
        val name = shortName
        val ver = versionText
        val namePart = if (ver.isBlank()) name else "$name $ver"
        val tag = when {
            remote -> "（异地）"
            external -> "（外置）"
            else -> ""
        }
        return "$namePart$tag · ${threads}线程 · 哈希${hash}MB"
    }

    /** 简短的来源说明 */
    fun sourceText(): String = when {
        remote -> "电脑引擎（辎重包端代算）"
        external -> "外置引擎"
        else -> "内置引擎"
    }

    /** 加载 / 恢复时弹出的"引擎特征"文字（多行） */
    fun featureText(): String {
        val sb = StringBuilder()
        val rawName = idName.ifBlank { engineFileName.ifBlank { "未知引擎" } }
        sb.append("名称：").append(shortName)
        if (rawName.isNotBlank() && rawName != shortName) sb.append("（").append(rawName).append("）")
        sb.append('\n')
        if (author.isNotBlank()) sb.append("作者：").append(author).append('\n')
        sb.append("来源：").append(sourceText())
        if (external && packageName.isNotBlank()) sb.append("（").append(packageName).append("）")
        if (remote && enginePath.startsWith("remote://")) sb.append("（").append(enginePath.removePrefix("remote://")).append("）")
        sb.append('\n')
        if (versionText.isNotBlank()) sb.append("构建日期：").append(versionText).append('\n')
        if (engineFileName.isNotBlank()) {
            sb.append("引擎：").append(engineFileName)
            if (engineSize > 0) sb.append("（").append(humanSize(engineSize)).append("）")
            sb.append('\n')
        }
        if (weightFileName.isNotBlank()) {
            sb.append("权重：").append(weightFileName)
            if (weightSize > 0) sb.append("（").append(humanSize(weightSize)).append("）")
        } else {
            sb.append("权重：内置")
        }
        return sb.toString().trimEnd()
    }

    /** ★ 对外公开的中文/英文名归一：认识的说中文，不认识的说英文原名 */
    fun chineseNameFor(raw: String): String {
        if (raw.isBlank()) return if (external) "外置引擎" else "皮卡鱼"
        val lower = raw.lowercase()
        return when {
            lower.contains("pikafish") -> "皮卡鱼"
            lower.contains("fstockfish") || lower.contains("fairy") -> "雪鱼"
            lower.contains("elephanteye") || lower.contains("eleeye") -> "象眼"
            else -> firstMeaningfulToken(raw)
        }
    }

    /** 由 idName / 文件名推出展示名（供引擎列表用） */
    fun displayNameOf(idName: String, fileName: String = ""): String =
        chineseNameFor(idName.ifBlank { fileName })

    /** 取"英文原名"里最有意义的第一段（"UFO 2024-01-01" -> "UFO"） */
    private fun firstMeaningfulToken(raw: String): String {
        val tokens = raw.trim().split(Regex("\\s+"))
        for (t in tokens) {
            val cleaned = t.trim()
            if (cleaned.isBlank()) continue
            if (DATE_RE.matches(cleaned)) continue
            if (cleaned.matches(Regex("^v?\\d+(\\.\\d+)*$"))) continue
            return cleaned
        }
        return raw.trim()
    }

    private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    /** 2026-09-25 / 2026/09/25 / 2026.09.25（月日可一位） */
    private val DATE_SEP_RE = Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})")
    /** 20260925（8 位） */
    private val DATE8_RE = Regex("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?!\\d)")
    /** 260925（6 位，20xx） */
    private val DATE6_RE = Regex("(?<!\\d)(2[0-9])(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?!\\d)")

    /** 从任意文本里找构建日期，统一成 YYYY-MM-DD；找不到返回 null。
     *  先认带分隔符的，再认 8 位，最后认 6 位（260925），并校验月/日范围。 */
    fun findDate(text: String): String? {
        if (text.isBlank()) return null
        DATE_SEP_RE.find(text)?.let { m ->
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            val da = m.groupValues[3].toIntOrNull() ?: return@let
            if (mo in 1..12 && da in 1..31) {
                return String.format(java.util.Locale.US, "%s-%02d-%02d", m.groupValues[1], mo, da)
            }
        }
        DATE8_RE.find(text)?.let { m ->
            return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
        }
        DATE6_RE.find(text)?.let { m ->
            return "20${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
        }
        return null
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}