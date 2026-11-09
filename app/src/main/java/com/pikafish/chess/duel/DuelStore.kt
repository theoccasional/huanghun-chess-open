package com.pikafish.chess.duel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 续盘快照仓库：同 WiFi 对局的未完成棋谱，落盘到本机 SharedPreferences。
 *
 * <p>只存"事实"，不存"推测"：完整走子序列（ICCS）+ 双方剩余时间 + 房主当时的颜色 +
 * 保存时间。轮到谁<b>不存</b>——它由步数奇偶唯一推导，因此永远不会出现
 * "明明轮到红方却记成黑方"这类错乱。</p>
 */
object DuelStore {

    private const val PREF = "duel_resume_v1"
    private const val KEY = "snapshot"
    /** 超过 7 天视为过期，避免拿一盘古老残局去误导用户。 */
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    data class Snapshot(
        /** ★ 封盘时的原配对码：续盘沿用它，绝不另生成。 */
        val code: String,
        /** ★ 封盘时"我"执的颜色：续盘时强制还原，避免张冠李戴。 */
        val myColor: String,
        val moves: List<String>,
        val minutes: Int,
        val redSec: Int,
        val blackSec: Int,
        val hostColor: String,
        val savedAt: Long
    ) {
        /** 该谁走：偶数步轮到红、奇数步轮到黑（与对局内规则完全一致）。 */
        val nextSide: String get() = if (moves.size % 2 == 0) "red" else "black"
        val nextSideName: String get() = if (nextSide == "red") "红方" else "黑方"
    }

    fun save(
        context: Context,
        code: String,
        myColor: String,
        moves: List<String>,
        minutes: Int,
        redSec: Int,
        blackSec: Int,
        hostColor: String
    ) {
        if (moves.isEmpty()) { clear(context); return }
        val arr = JSONArray()
        for (m in moves) arr.put(m)
        val o = JSONObject()
            .put("code", code)
            .put("myColor", myColor)
            .put("moves", arr)
            .put("minutes", minutes)
            .put("red", redSec)
            .put("black", blackSec)
            .put("hostColor", hostColor)
            .put("savedAt", System.currentTimeMillis())
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }

    fun load(context: Context): Snapshot? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val arr = o.optJSONArray("moves") ?: return null
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
        if (list.isEmpty()) return null
        val savedAt = o.optLong("savedAt", 0)
        if (savedAt > 0 && System.currentTimeMillis() - savedAt > MAX_AGE_MS) {
            clear(context)
            return null
        }
        return Snapshot(
            code = o.optString("code", ""),
            myColor = o.optString("myColor", "red"),
            moves = list,
            minutes = o.optInt("minutes", 5),
            redSec = o.optInt("red", 300),
            blackSec = o.optInt("black", 300),
            hostColor = o.optString("hostColor", "red"),
            savedAt = savedAt
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    // ★ 颜色强绑定：房间号 + 设备原始码 → 颜色（重进时强制还原，绝不先到先得）
    fun bindColor(context: Context, code: String, device: String, color: String) {
        if (code.isBlank() || device.isBlank()) return
        val sp = context.getSharedPreferences("duel_bind", Context.MODE_PRIVATE)
        sp.edit().putString("$code.$device", color).apply()
    }

    fun boundColor(context: Context, code: String, device: String): String? {
        if (code.isBlank() || device.isBlank()) return null
        return context.getSharedPreferences("duel_bind", Context.MODE_PRIVATE)
            .getString("$code.$device", null)
    }
}
