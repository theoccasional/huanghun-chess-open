package com.pikafish.chess.ui

import android.content.Context
import android.util.Log
import com.pikafish.chess.model.PieceColor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 复盘存档：把一次复盘（ReviewBundle）整体存成 JSON 到「存档/复盘文件/」，
 * 命名「复盘_yyyyMMdd_HHmmss.json」；加载时从 JSON 还原整份复盘供分页查看与试走。
 *
 * ★ 只做"复盘结果的持久化"，不改动引擎、不改动评分算法；字段与基准 APK 一致。
 */
object ReviewArchive {
    private const val TAG = "ReviewArchive"
    private const val DIR_NAME = "复盘文件"
    private const val VERSION = 1

    /** 复盘文件目录（首次自动创建） */
    fun dir(context: Context): File {
        val f = File(context.filesDir, DIR_NAME)
        if (!f.exists()) f.mkdirs()
        return f
    }

    /** 按最后修改时间降序列出所有复盘文件（.json） */
    fun list(context: Context): List<File> =
        (dir(context).listFiles(FileFilter { it.isFile && it.name.endsWith(".json") })
            ?.sortedByDescending { it.lastModified() } ?: emptyList())

    /**
     * 保存整份复盘 → 命名「复盘_yyyyMMdd_HHmmss_第N回合.json」。
     * 关键词「复盘」一目了然，年月日时分 + 回合数让用户不看内容也知道这是哪盘、走了几回合。
     * 成功返回 File，失败返回 null。
     */
    fun save(context: Context, bundle: ReviewBundle): File? = try {
        val rounds = (bundle.movesUci.size + 1) / 2
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val name = "复盘_${stamp}_第${rounds}回合.json"
        val file = File(dir(context), name)
        file.writeText(bundleToJson(bundle).toString(2), Charsets.UTF_8)
        file
    } catch (e: Exception) {
        Log.e(TAG, "复盘保存失败", e)
        null
    }

    /** 读取复盘文件 → ReviewBundle，失败返回 null */
    fun load(context: Context, f: File): ReviewBundle? = try {
        jsonToBundle(JSONObject(f.readText(Charsets.UTF_8)))
    } catch (e: Exception) {
        Log.e(TAG, "复盘加载失败", e)
        null
    }

    fun bundleToJson(b: ReviewBundle): JSONObject {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("savedAt", b.savedAt)
        root.put("gameTitle", b.gameTitle)
        root.put("initialFen", b.initialFen)
        root.put("moves", JSONArray(b.movesUci))
        val segJson = JSONArray()
        b.segments.forEach { seg ->
            val sj = JSONObject()
            sj.put("startRound", seg.startRound)
            sj.put("endRound", seg.endRound)
            val pts = JSONArray()
            seg.points.forEach { p ->
                pts.put(JSONObject().apply {
                    put("ply", p.ply); put("round", p.round)
                    put("color", p.color.name); put("viewScore", p.viewScore)
                    put("moveChinese", p.moveChinese); put("bestMoveChinese", p.bestMoveChinese)
                })
            }
            sj.put("points", pts)
            val bls = JSONArray()
            seg.blunders.forEach { bd ->
                bls.put(JSONObject().apply {
                    put("ply", bd.ply); put("round", bd.round)
                    put("color", bd.color.name); put("movePlayedChinese", bd.movePlayedChinese)
                    put("bestMoveChinese", bd.bestMoveChinese); put("bestPv", bd.bestPv)
                    put("lossViewScore", bd.lossViewScore)
                    put("viewBefore", bd.viewBefore); put("viewAfter", bd.viewAfter)
                    put("note", bd.note)
                })
            }
            sj.put("blunders", bls)
            val sls = JSONArray()
            seg.slips.forEach { sp ->
                sls.put(JSONObject().apply {
                    put("ply", sp.ply); put("round", sp.round); put("color", sp.color.name)
                    put("startView", sp.startView); put("endView", sp.endView); put("note", sp.note)
                })
            }
            sj.put("slips", sls)
            sj.put("curve", JSONArray(seg.curve.toList()))
            sj.put("blunderCurveIdx", JSONArray(seg.blunderCurveIdx))
            sj.put("slipCurveIdx", JSONArray(seg.slipCurveIdx))
            segJson.put(sj)
        }
        root.put("segments", segJson)
        return root
    }

    fun jsonToBundle(root: JSONObject): ReviewBundle {
        val segments = ArrayList<ReviewSegment>()
        val segArr = root.getJSONArray("segments")
        for (i in 0 until segArr.length()) {
            val sj = segArr.getJSONObject(i)
            val points = ArrayList<ReviewAnalyzer.ReviewPoint>()
            val ptsArr = sj.getJSONArray("points")
            for (j in 0 until ptsArr.length()) {
                val pj = ptsArr.getJSONObject(j)
                points.add(ReviewAnalyzer.ReviewPoint(
                    pj.getInt("ply"), pj.getInt("round"),
                    PieceColor.valueOf(pj.getString("color")), pj.getInt("viewScore"),
                    pj.getString("moveChinese"), pj.getString("bestMoveChinese")
                ))
            }
            val blunders = ArrayList<ReviewAnalyzer.ReviewBlunder>()
            val blsArr = sj.getJSONArray("blunders")
            for (j in 0 until blsArr.length()) {
                val bj = blsArr.getJSONObject(j)
                blunders.add(ReviewAnalyzer.ReviewBlunder(
                    bj.getInt("ply"), bj.getInt("round"),
                    PieceColor.valueOf(bj.getString("color")),
                    bj.getString("movePlayedChinese"), bj.getString("bestMoveChinese"),
                    bj.getString("bestPv"), bj.getInt("lossViewScore"),
                    bj.getInt("viewBefore"), bj.getInt("viewAfter"), bj.getString("note")
                ))
            }
            val curveArr = sj.getJSONArray("curve")
            val curve = IntArray(curveArr.length()) { curveArr.getInt(it) }
            val bIdx = ArrayList<Int>()
            val bIdxArr = sj.getJSONArray("blunderCurveIdx")
            for (j in 0 until bIdxArr.length()) bIdx.add(bIdxArr.getInt(j))
            val slips = ArrayList<ReviewAnalyzer.ReviewSlip>()
            if (sj.has("slips")) {
                val slsArr = sj.getJSONArray("slips")
                for (j in 0 until slsArr.length()) {
                    val spj = slsArr.getJSONObject(j)
                    slips.add(ReviewAnalyzer.ReviewSlip(
                        spj.getInt("ply"), spj.getInt("round"),
                        PieceColor.valueOf(spj.getString("color")),
                        spj.getInt("startView"), spj.getInt("endView"), spj.getString("note")
                    ))
                }
            }
            val spIdx = ArrayList<Int>()
            if (sj.has("slipCurveIdx")) {
                val spArr = sj.getJSONArray("slipCurveIdx")
                for (j in 0 until spArr.length()) spIdx.add(spArr.getInt(j))
            }
            segments.add(ReviewSegment(sj.getInt("startRound"), sj.getInt("endRound"),
                points, blunders, slips, curve, bIdx, spIdx))
        }
        val moves = ArrayList<String>()
        val movesArr = root.getJSONArray("moves")
        for (i in 0 until movesArr.length()) moves.add(movesArr.getString(i))
        return ReviewBundle(
            root.getString("initialFen"), moves,
            root.optString("gameTitle", ""), root.optString("savedAt", ""), segments
        )
    }
}