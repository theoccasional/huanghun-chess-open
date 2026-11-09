package com.pikafish.chess.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★★★ 挑战对局存档系统（只读分区，防作弊）★★★
 *
 * 设计要点（贴合需求）：
 *  - 存档放在独立分区目录 challenge_archive/（优先「外部可见」的 Android/data/<包名>/files，
 *    实在不行退回内部 filesDir），可被用户看到。
 *  - 每个存档是 TXT，抬头写明：与谁对弈 / 我方先后手 / 对方实力(引擎+毫秒) / 结果 / 走法。
 *  - 存完后把文件置为「只读」，只保留读与删，不允修改；防止改写伪造成真实对局。
 *  - 文件内带完整性令牌(#token:…)，续盘时校验：不匹配一律拒绝，杜绝外部伪造棋谱冒充真实对局。
 *  - 只出不进：只有这里读出的「本机正规存档」允许续盘；不从此进口任意对局。
 */
object SaveArchive {
    private const val TAG = "SaveArchive"
    private const val SECRET = "HuanghunXiangqi.Challenge.2026.Secret"
    private const val TOKEN_PREFIX = "#token:"

    /** 一局存档的解析结果 */
    data class SavedGame(
        val fileName: String,
        val file: File,
        val ts: Long = 0L,
        val rank: String = "",       // 对手段位
        val engine: String = "",     // 引擎名(中文文案，如 相眼)
        val engineKey: String = "",  // 引擎键(eleeye/ufx/...)
        val ms: Long = 0L,
        val color: String = "红",    // 我方执子
        val result: String = "进行中",
        val moves: String = "",
        val valid: Boolean = false   // 完整性令牌是否通过（防作弊）
    ) {
        /** 存档抬头：谁 vs 谁，谁先手，对方实力（引擎+毫秒），结果 */
        val header: String
            get() {
                val first = if (color == "黑") "后手" else "先手"
                val eh = if (ms > 0) "${rank}·${engine}(${ms}ms)" else "${rank}·${engine}"
                return "$eh｜我持$color·$first｜$result"
            }
    }

    fun archiveDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, "challenge_archive")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ═══════════════ 生成 / 校验 ═══════════════

    private fun token(rank: String, engineKey: String, ms: Long, color: String, result: String, moves: String): String {
        val data = "$rank|$engineKey|$ms|$color|$result|$moves|$SECRET"
        return sha256Hex(data)
    }

    private fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 建存档正文 */
    fun buildContent(rank: String, engineLabel: String, engineKey: String, ms: Long,
                     color: String, result: String, moves: String, ts: Long): String {
        val first = if (color == "黑") "后手" else "先手"
        val sb = StringBuilder()
        sb.append("【黄昏象棋·挑战存档】\n")
        sb.append("存档时间: ").append(fmtTs(ts)).append('\n')
        sb.append("对手: ").append(rank).append("（引擎 ").append(engineLabel).append("，").append(ms).append(" 毫秒/步）\n")
        sb.append("我方执子: ").append(color).append('\n')
        sb.append("先后手: ").append(first).append('\n')
        sb.append("结果: ").append(result).append('\n')
        sb.append("引擎: ").append(engineKey).append('\n')
        sb.append("走法(UCI): ").append(moves).append('\n')
        sb.append(TOKEN_PREFIX).append(token(rank, engineKey, ms, color, result, moves)).append('\n')
        return sb.toString()
    }

    // ═══════════════ 存档 ═══════════════

    /** 存一局（TXT，只读）。返回存档文件名；失败返回 null */
    fun save(ctx: Context, rank: String, engineLabel: String, engineKey: String, ms: Long,
             color: String, result: String, moves: String): String? = try {
        val ts = System.currentTimeMillis()
        val content = buildContent(rank, engineLabel, engineKey, ms, color, result, moves.trim(), ts)
        val safe = rank.replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9_]"), "")
        val name = "挑战_${fmtFileTs(ts)}_${result}_$safe.txt"
        val f = File(archiveDir(ctx), name)
        f.writeText(content, Charsets.UTF_8)
        f.setReadOnly()          // 只读：可读、可删，不可改
        Log.d(TAG, "已存档: $name (只读)")
        name
    } catch (e: Exception) {
        Log.e(TAG, "存档失败", e); null
    }

    // ═══════════════ 列出 / 解析 ═══════════════

    fun list(ctx: Context): List<SavedGame> = try {
        archiveDir(ctx).listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", true) && it.canRead() }
            ?.mapNotNull { parse(it) }
            ?.sortedByDescending { it.ts }
            .orEmpty()
    } catch (_: Exception) { emptyList() }

    fun parse(file: File): SavedGame? = try {
        val text = file.readText(Charsets.UTF_8)
        val g = Regex("""节省?保证?时间:?\s*(.+)""").find(text)
        val ts = Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})""").find(text)?.let { m ->
            val y=m.groupValues[1].toInt(); val mo=m.groupValues[2].toInt(); val d=m.groupValues[3].toInt()
            val h=m.groupValues[4].toInt(); val mi=m.groupValues[5].toInt(); val s=m.groupValues[6].toInt()
            java.util.Calendar.getInstance().apply { set(y,mo-1,d,h,mi,s); set(java.util.Calendar.MILLISECOND,0); set(java.util.Calendar.SECOND, s) }.timeInMillis
        } ?: 0L
        val rank = Regex("""对手:\s*([^\d（(]+)""").find(text)?.groupValues?.get(1)?.trim() ?: ""
        // 对手: 国手·BOSS（引擎 神机，3000 毫秒/步）
        val engineLabel = Regex("""引擎\s+([\u4e00-\u9fa5A-Za-z·]+)[，,]""").find(text)?.groupValues?.get(1)?.trim() ?: ""
        val ms = Regex("""(\d+)\s*毫秒""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val color = Regex("""我方执子:\s*(红|黑)""").find(text)?.groupValues?.get(1) ?: "红"
        val result = Regex("""结果:\s*(.+?)\n""").find(text)?.groupValues?.get(1)?.trim() ?: "进行中"
        val engineKey = Regex("""引擎:\s*(\w+)""").find(text)?.groupValues?.get(1) ?: ""
        val moves = Regex("""走法\(UCI\):\s*(.*)""").find(text)?.groupValues?.get(1)?.trim() ?: ""
        val storedToken = Regex("""$TOKEN_PREFIX(\w+)""").find(text)?.groupValues?.get(1) ?: ""
        val valid = storedToken.isNotEmpty() &&
            storedToken.equals(token(rank, engineKey, ms, color, result, moves), ignoreCase = true)
        SavedGame(file.name, file, ts, rank, engineLabel, engineKey, ms, color, result, moves, valid)
    } catch (_: Exception) {
        null
    }

    // ═══════════════ 删除 / 导出 ═══════════════

    /** 只读、可删：删除前先放开写权限 */
    fun delete(file: File) {
        try { if (!file.canWrite()) file.setWritable(true) } catch (_: Exception) {}
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }

    /** 取正文（供导出） */
    fun contentOf(file: File): String? = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { null }

    fun fmtTs(ts: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
    fun fmtFileTs(ts: Long): String = SimpleDateFormat("yyMMdd_HHmmss", Locale.getDefault()).format(Date(ts))
}