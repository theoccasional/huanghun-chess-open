package com.pikafish.chess.qipu

import android.content.Context
import com.pikafish.chess.heavy.HeavyStore
import org.json.JSONArray
import org.json.JSONObject

/** 当代名局 · 一位名手的档案（来自内置 modern.json，数据真实，取自公开棋谱库） */
data class ModernPlayer(
    val name: String,
    val note: String,        // 称号 · 籍贯
    val bio: String,         // 主要战绩/绰号/生日/等级分 等简介
    val win: Int, val loss: Int, val draw: Int, val total: Int,
    val openings: List<String>
) {
    /** 战绩行文案 */
    fun recordLine(): String = "战绩：胜 $win · 负 $loss · 和 $draw（共计 $total 局）"
}

/** 当代名局 数据加载器：从 assets/qipu_cbl/modern.json 读取名手档案。 */
object ModernData {
    const val ASSET = "qipu_cbl/modern.json"

    /** 块缓存，避免重复解析。 */
    private var _players: List<ModernPlayer>? = null

    fun load(context: Context): List<ModernPlayer> {
        _players?.let { return it }
        return try {
            val raw = HeavyStore.openLocalFirst(context, ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(raw)
            val arr: JSONArray = root.getJSONArray("players")
            val out = ArrayList<ModernPlayer>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val opens = ArrayList<String>()
                val oa = o.optJSONArray("openings")
                if (oa != null) for (j in 0 until oa.length()) opens.add(oa.getString(j))
                out.add(ModernPlayer(
                    name = o.getString("name"),
                    note = o.optString("note", ""),
                    bio = o.optString("bio", ""),
                    win = o.optInt("win", 0), loss = o.optInt("loss", 0),
                    draw = o.optInt("draw", 0), total = o.optInt("total", 0),
                    openings = opens
                ))
            }
            _players = out
            out
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun clear() { _players = null }
}