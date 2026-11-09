package com.pikafish.chess.engine

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 云库着法条目（queryall 解析结果）
 */
data class CloudMove(
    val move: String,      // UCI走法
    val score: Int,        // 分值（从当前走子方视角，越大越好）
    val winrate: Float,    // 胜率（0~100）
    val rank: Int          // 排名（1=最优）
)

/**
 * 中国象棋云库客户端（chessdb.cn）
 *
 * 功能：
 *   - 查询开局/残局着法（querybest）
 *   - 查询所有着法信息（queryall）
 *   - 内存缓存 + 预取：用户回合时提前拉取子局面云库数据，
 *     用户一走完 AI 立刻从缓存秒出，不用等网络
 *   - 云库有招法时直接走，不用引擎思考
 *   - 云库脱谱时自动回归引擎搜索
 *
 * 注意：query* 方法是阻塞式的，必须在后台线程调用！
 *
 * API 文档：https://www.chessdb.cn/cloudbook_api.html
 */
class CloudBookClient {

    companion object {
        private const val TAG = "CloudBookClient"
        private const val BASE_URL = "http://www.chessdb.cn/chessdb.php"
        private const val TIMEOUT_MS = 3000  // 3秒超时，网络差时不卡
        private const val MAX_CACHE_ENTRIES = 4096  // 缓存上限，防止内存膨胀
    }

    // ── 内存缓存：FEN → 着法列表（按分值降序）──
    private val cache = ConcurrentHashMap<String, List<CloudMove>>()

    // ── 最优走法快速缓存：FEN → 最优走法UCI（querybest，秒出用）──
    private val bestCache = ConcurrentHashMap<String, String>()

    // ── 预取线程池：限制并发，避免一次预取几十个请求打爆云库 ──
    private val prefetchExecutor = Executors.newFixedThreadPool(3)

    /**
     * 查询云库所有着法（queryall），带缓存。
     * 阻塞式，必须在后台线程调用。
     *
     * @param fen 局面FEN
     * @return 着法列表（按分值降序，[0]为最优），无数据返回空列表
     */
    fun queryAllMoves(fen: String): List<CloudMove> {
        cache[fen]?.let { return it }
        val raw = queryAll(fen)
        val moves = raw.mapIndexed { i, (m, s, w) ->
            CloudMove(m, s, w, i + 1)
        }.sortedByDescending { it.score }
        if (moves.isNotEmpty()) {
            putCache(fen, moves)
            // 顺手把最优写进快速缓存
            bestCache[fen] = moves[0].move
        }
        return moves
    }

    /**
     * 读取缓存中的着法列表（不发起网络请求）。
     *
     * @param fen 局面FEN
     * @return 缓存命中返回列表，未命中返回 null
     */
    fun getCachedMoves(fen: String): List<CloudMove>? = cache[fen]

    /**
     * 读取快速缓存中的最优走法（不发起网络请求）。
     *
     * @param fen 局面FEN
     * @return 缓存命中返回最优走法UCI，未命中返回 null
     */
    fun getCachedBest(fen: String): String? = bestCache[fen]

    /**
     * 后台预取指定局面的云库最优走法（querybest，快）并写入快速缓存。
     * 非阻塞，失败静默。用于用户回合时提前拉取，实现 AI 秒出。
     *
     * @param fen 局面FEN
     */
    fun prefetch(fen: String) {
        if (bestCache.containsKey(fen) || cache.containsKey(fen)) return
        prefetchExecutor.execute {
            try {
                val best = queryBestMove(fen)
                if (best != null) {
                    bestCache[fen] = best
                }
            } catch (_: Exception) {
                // 预取失败静默，走棋时再兜底查询
            }
        }
    }

    /**
     * 清空缓存（开局/换谱时调用）
     */
    fun clearCache() {
        cache.clear()
        bestCache.clear()
    }

    private fun putCache(fen: String, moves: List<CloudMove>) {
        if (cache.size > MAX_CACHE_ENTRIES) {
            cache.clear()  // 简单兜底：超限直接清空，避免复杂淘汰逻辑
        }
        cache[fen] = moves
    }

    /**
     * 查询云库最佳走法（阻塞式，必须在后台线程调用）
     *
     * @param fen 局面FEN
     * @return 最佳走法（UCI格式），没有则返回 null
     */
    fun queryBestMove(fen: String): String? {
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = "$BASE_URL?action=querybest&board=$encodedFen"
            val result = httpGet(url)
            Log.d(TAG, "querybest: fen=$fen, result=$result")

            if (result.isNullOrEmpty() || result == "nobestmove" || result == "invalid board" || result == "unknown") {
                return null
            }

            // 格式：move:h2e2 或 egtb:h2e2（残局库）
            parseMoveFromResult(result)
        } catch (e: Exception) {
            Log.w(TAG, "queryBestMove error: ${e.message}")
            null
        }
    }

    /**
     * 查询云库所有着法信息（阻塞式，必须在后台线程调用）
     *
     * @param fen 局面FEN
     * @return 着法信息列表，每项 Triple(走法UCI, 分值, 胜率)
     */
    fun queryAll(fen: String): List<Triple<String, Int, Float>> {
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = "$BASE_URL?action=queryall&board=$encodedFen"
            val result = httpGet(url)
            Log.d(TAG, "queryall: fen=$fen, result=${result?.take(100)}")

            if (result.isNullOrEmpty() || result == "unknown" || result == "invalid board") {
                return emptyList()
            }

            val moves = mutableListOf<Triple<String, Int, Float>>()
            // 格式：move:h2e2,score:20,rank:1,winrate:55.3|move:...
            val parts = result.split("|")
            for (part in parts) {
                val move = parseMoveEntry(part)
                if (move != null) moves.add(move)
            }
            moves
        } catch (e: Exception) {
            Log.w(TAG, "queryAll error: ${e.message}")
            emptyList()
        }
    }

    /**
     * 提交局面到云库后台计算（当云库没有数据时，顺便提交让云库算）
     * 阻塞式，但忽略返回结果，失败不影响主流程
     */
    fun queuePosition(fen: String) {
        try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = "$BASE_URL?action=queue&board=$encodedFen"
            httpGet(url)
        } catch (_: Exception) {
            // 忽略失败
        }
    }

    // ── 私有方法 ──

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "PikafishChess/1.0")

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 从 querybest 结果中解析走法
     * 格式：move:h2e2 或 egtb:h2e2
     */
    private fun parseMoveFromResult(result: String): String? {
        val parts = result.split("|")
        for (part in parts) {
            val trimmed = part.trim()
            // 优先普通开局库走法，其次残局库
            if (trimmed.startsWith("move:")) {
                return trimmed.removePrefix("move:").trim()
            }
        }
        // 如果只有残局库走法，也返回
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("egtb:")) {
                return trimmed.removePrefix("egtb:").trim()
            }
        }
        return null
    }

    /**
     * 从 queryall 的单条着法中解析信息
     * 格式：move:h2e2,score:20,rank:1,winrate:55.3
     */
    private fun parseMoveEntry(entry: String): Triple<String, Int, Float>? {
        try {
            var move = ""
            var score = 0
            var winrate = 0f
            val fields = entry.split(",")
            for (field in fields) {
                val kv = field.split(":", limit = 2)
                if (kv.size != 2) continue
                when (kv[0]) {
                    "move" -> move = kv[1]
                    "score" -> score = kv[1].toIntOrNull() ?: 0
                    "winrate" -> winrate = kv[1].toFloatOrNull() ?: 0f
                }
            }
            if (move.isEmpty()) return null
            return Triple(move, score, winrate)
        } catch (e: Exception) {
            return null
        }
    }
}
