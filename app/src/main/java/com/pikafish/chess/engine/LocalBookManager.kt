package com.pikafish.chess.engine

import android.content.Context
import android.util.Log
import androidx.collection.LongSparseArray
import com.pikafish.chess.heavy.HeavyStore
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import com.pikafish.chess.qipu.CblDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ★★★ 外置开局库管理器（可并行挂最多 MAX_BOOKS 个库）★★★
 *
 * 为什么放这一层而不是喂给引擎：
 *   - 本项目皮卡鱼是预编译 .so,只暴露标准 UCI（EvalFile/Threads/Hash/MultiPV），
 *     没有"外部开局库"UCI 选项。所以"让引擎读库"实际是"App 读库并把这一着喂给引擎"。
 *   - 这正是云库(chessdb.cn)已经在用的模式：AI 要走→查库→命中直接落子，引擎在这一着不搜索，省时省算。
 *
 * 支持格式（外置、可被引擎"真读到"）：
 *   1) .json  结构化谱：startFen + lines(moves 走法数组 + score 分值)  ← 可靠
 *   2) .txt    文本谱：每行为一条分支线，UCI 走法空格分隔；可带 startFen 前缀 ← 可靠
 *   3) .pkb    自有紧凑二进制（本项目自己的键书格式）                      ← 可靠
 *   4) .ubb    东萍 DhtmlXQ 棋谱：解析 [DhtmlXQ_binit]/[DhtmlXQ_movelist] ← 可靠
 *   5) .pgn    标准棋谱：解析 FEN 标记 + 走法（含中文/坐标/ICCS 记谱）    ← 可靠
 *   6) .obk    外来私有库：识别魔数但私有哈希无法反解，如实标注 0 覆盖，绝不虚报
 *   7) .dat    冰河五四等引擎的 BOOK.DAT 文本库：每行 <走法> <分值> <FEN> ← 可靠
 *   8) .bin    象眼(ElephantEye)二进制开局库（记录 8 字节升序，走磁盘二分直查，几 GB 不崩）← 可靠
 *   9) .pfc    鹏飞象棋 XML 谱：<n> 树形（根可带 FEN，子节点带 UCI 走法与可选 s 分值）← 可靠
 *   A) .cbl    象棋桥(CBL)原生棋谱：运行时解码出对局后回放建成开局库索引     ← 可靠
 *
 * ★ 上限语义（MAX_BOOKS）：挂接以"来源文件"计数——单个库文件 OR 一个压缩包(内含多本
 *   库)都只算一个来源。因此多个 OBK/BIN/CBL 可打成一个 ZIP，一个包占一个档位。
 *
 * 查询：以 Board.toFen() 归一化后的局面字符串为键，加载时把每条谱重放进去建索引，
 *       命中选择"分高→线长"的着法。命中即"翻库"，供上层金色高亮 + 思考细节标注。
 */
class LocalBookManager {

    companion object {
        private const val TAG = "LocalBookManager"
        /** 最多挂接的"来源文件"数：单个库文件或一个压缩包都算一个来源 */
        const val MAX_BOOKS = 6

        /** 压缩包扫描时认可的开局库/棋谱文件扩展名（大小写不限于） */
        val BOOK_ARCHIVE_EXTS = setOf(
            "json", "txt", "pkb", "ubb", "pgn", "obk", "pfbook", "xqb", "dat", "bin", "pfc", "cbl"
        )

        /** 冰河 BOOK.DAT 行：<走法> <分值> <FEN>，走法为坐标记法（如 b2e2=炮二平五） */
        private val BOOK_DAT_LINE = Regex("^([a-i][0-9][a-i][0-9])\\s+(-?\\d+)\\s+(\\S.*)$")

        /** 内置 CBL 全集装载限量：每个 CBL 文件最多取此数量的对局回放 */
        private const val BUILTIN_CBL_PER_FILE = 400
        /** 内置 CBL 全集装载总量上限（300 个文件的样本已可覆盖开局/中局/残局全部子类） */
        private const val BUILTIN_CBL_TOTAL_CAP = 6000
        /** 内置 CBL 全集所在 assets 目录 */
        private const val BUILTIN_CBL_DIR = "qipu_cbl/data"
    }

    /** 一着候选：uci + 分(内部权重) + 该分支剩余深度(越长越有价值)，score/depth 可被更高分支覆盖 */
    data class MoveCand(
        val uci: String,
        var score: Int,
        var depth: Int,
        var winRate: Float = 0f,   // 经验胜率 0..1；OBK 有 vwin/vdraw/vlost 时用于排序，否则 0
        var totalGames: Int = 0    // 经验样本总数(vwin+vdraw+vlost)；无统计为 0（质量闸据此判"未知"）
    )

    /** OBK 直查字典里的一着（直接哈希命中，覆盖全库而非仅根重放） */
    data class VkeyMove(
        val uci: String,
        var score: Int,
        val winRate: Float,
        var totalGames: Int = 0,   // 经验样本总数(vwin+vdraw+vlost)；质量闸据此区分"未知/确实很烂"
        var enabled: Boolean = true
    )

    /** 一个已加载的库 */
    data class LoadedBook(
        val bookId: Int,
        var name: String,
        val path: String,
        val format: String,       // "json" / "txt" / "pkb" / "obk" / "dat-bin" / "pfc" / "cbl"
        var enabled: Boolean,
        val entries: MutableMap<String, MutableList<MoveCand>>,
        val vkeyDict: LongSparseArray<MutableList<VkeyMove>>? = null, // OBK 直接局面哈希字典（LongSparseArray：原始 long 键数组，免装箱，键开销最小）
        var entryCount: Int,
        // 象眼(ElephantEye)二进制库：direct 用局面 lock1 直查(库内原向着法)；mirror 用镜像 lock1 命中的已镜像着法
        var e2LockDict: MutableMap<Long, MutableList<Pair<String, Int>>>? = null,
        var e2MirrorDict: MutableMap<Long, MutableList<Pair<String, Int>>>? = null,
        // ★ 来源分组：同一压缩包解出的多本库共享同一 group（=压缩包路径），占一个来源档位
        var group: String = ""
    ) {
        /**
         * ★ SQLite 大库句柄（.obk/bhobk）：只持有只读连接，按局面 vkey 索引直查。
         *   整表不入内存 —— 库多大都只占常数内存，这是"大库不崩"的关键。
         */
        var sqliteBook: ObkParser.SqliteBookHandle? = null

        /**
         * ★ 象眼二进制大库磁盘句柄（.bin）：只持有文件句柄，按记录升序 lock1 二分直查。
         *   整本几 GB 也只占常数内存 —— 与 ElephantEye BookFile 的 mmap 直查同思路。
         */
        var diskBin: java.io.RandomAccessFile? = null

        /** ★ 2.9.299：xqb 开源格式库的只读直查句柄（整本不进内存） */
        var xqbBook: XqbBook.Handle? = null
        var diskBinRecs: Long = 0L
    }

    /** 一次命中：哪个库、哪一着（上层据此报"翻库"并金色高亮）+ 经验质量（供质量闸裁决毒着） */
    data class BookHit(
        val bookName: String,
        val format: String,
        val uci: String,
        val winRate: Float = 0f,   // 经验胜率 0..1（质量闸用）
        val totalGames: Int = 0    // 经验样本总数（质量闸用；0=未知）
    )

    // CopyOnWriteArrayList：装载可能在后台线程进行（避免启动黑屏），读/写并发安全
    private val books = CopyOnWriteArrayList<LoadedBook>()
    private var nextId = 1

    /**
     * 最近一次装载失败的原因（人话，供 UI 如实提示）。装载成功时清空。
     * 之前失败是"静默"的：面板刷新一下、库没出现，用户完全不知道为什么，这条就是补这个洞。
     */
    @Volatile
    var lastLoadError: String? = null
        private set

    fun listBooks(): List<LoadedBook> = books.toList()
    fun count(): Int = books.size

    /** 一个库属于哪个"来源档位"：优先其 group（压缩包），否则自身路径 */
    private fun sourceKey(b: LoadedBook): String = b.group.ifEmpty { b.path }

    /** 当前占用的来源档位数（单个库文件或一个压缩包都算 1） */
    fun sourceCount(): Int {
        val keys = HashSet<String>()
        for (b in books) keys.add(sourceKey(b))
        return keys.size
    }

    /** 该来源档位是否已挂接过（用于压缩包去重） */
    fun hasSource(group: String): Boolean = books.any { it.group == group }

    fun removeBook(bookId: Int): Boolean {
        // 关掉内嵌 SQLite / 象眼磁盘大库的句柄，释放文件锁
        books.filter { it.bookId == bookId }.forEach { closeHandles(it) }
        return books.removeAll { it.bookId == bookId }
    }

    /** 关掉一个库持有的磁盘/SQLite 句柄（移除或整体清空时调用） */
    private fun closeHandles(b: LoadedBook) {
        b.sqliteBook?.close()
        try { b.diskBin?.close() } catch (_: Exception) {}
        b.diskBin = null
    }

    fun setEnabled(bookId: Int, enabled: Boolean): Boolean {
        val b = books.find { it.bookId == bookId } ?: return false
        b.enabled = enabled
        return true
    }

    fun clearAll() {
        books.forEach { closeHandles(it) }
        books.clear()
    }

    // ─────────────────────────────────────────────────────────────
    // 加载入口：按扩展名路由到对应解析器
    // ─────────────────────────────────────────────────────────────
    fun loadBook(name: String, path: String): LoadedBook? {
        if (sourceCount() >= MAX_BOOKS) {
            Log.w(TAG, "已达到最多 $MAX_BOOKS 个来源，拒绝加载 $name")
            return null
        }
        return loadBookFromPath(name, path)
    }

    /**
     * ★ 大库友好装载入口：从本地路径装载一个库。
     *   - .obk：SQLite 库走句柄直查、ABS 库走流式逐页解析，绝不把整本文件读进内存；
     *   - .bin：象眼二进制库走磁盘二分直查，几 GB 也只占常数内存；
     *   - 其余格式：落盘缓存已就绪则按字节装载（与 loadBookFromBytes 同一条路），
     *     但先过大小护栏——超过堆预算的文件直接拒绝，绝不 readBytes 读崩。
     * @param group 来源分组：压缩包解出的库共享压缩包路径（不重复占档位）；单个文件留空。
     */
    fun loadBookFromPath(name: String, path: String, group: String = ""): LoadedBook? {
        if (group.isBlank() && sourceCount() >= MAX_BOOKS) {
            Log.w(TAG, "已达到最多 $MAX_BOOKS 个来源，拒绝加载 $name")
            return null
        }
        val lower = path.lowercase()
        // ★ 按魔数/扩展名识别 SQLite / ABS 开局库：.obk、.pfBook（鹏飞）、bhobk 等。
        //   一律走「SQLite 句柄直查 / 流式反解」，整本不进内存。
        //   ★ 不再只认 .obk 后缀 —— 扩名不标准（如 .pfBook/.db）也按内容认出来，绝不"看后缀放行"。
        // ★ 2.9.299：xqb（开源 SQLite 开局库）→ 只读直查，整本不入内存
        if (lower.endsWith(".xqb")) {
            val h = XqbBook.open(path)
            if (h != null) {
                val loaded = LoadedBook(
                    bookId = nextId++, name = name, path = path, format = "xqb",
                    enabled = true, entries = mutableMapOf(), entryCount = h.rowCount
                )
                loaded.xqbBook = h
                loaded.group = group.ifEmpty { path }
                books.add(loaded)
                lastLoadError = null
                Log.i(TAG, "已加载 xqb 库: $name (${h.rowCount} 个局面, 来源组=${loaded.group})")
                return loaded
            }
            Log.w(TAG, "xqb 打不开或结构不符: $name")
            return null
        }
        if (lower.endsWith(".obk") || lower.endsWith(".pfbook") || looksLikeSqliteOrAbs(path)) {
            val loaded = loadObkFromPath(name, path)
            if (loaded != null) {
                loaded.group = group.ifEmpty { path }
                books.add(loaded)
                Log.i(TAG, "已加载外置库: ${loaded.name} (${loaded.format}, ${loaded.entryCount}个局面, 来源组=${loaded.group})")
            }
            return loaded
        }
        // ★ 象眼二进制库（.bin）：走磁盘二分直查，整本不进内存（几 GB 也能装）；
        //   不是该格式（抽验非升序/尺寸异常）则返回 null，继续走下面的字节路径尝试。
        if (lower.endsWith(".bin")) {
            val disk = loadEkBinaryFromPath(name, path)
            if (disk != null) {
                disk.group = group.ifEmpty { path }
                books.add(disk)
                lastLoadError = null
                Log.i(TAG, "已加载外置库: ${disk.name} (${disk.format}, ${disk.entryCount}个局面, 来源组=${disk.group})")
                return disk
            }
        }
        // ★ 大小护栏：超过"整本进内存"安全上限的文件，绝不 readBytes（读入那一刻就已 OOM），
        //   如实拒绝而不是把 App 读崩。OBK(SQLite/流式) 与 .bin(磁盘直查) 已在上方分流，不受此限。
        val len = try { java.io.File(path).length() } catch (e: Exception) { 0L }
        val limit = inMemBookLimitBytes()
        if (len > limit) {
            lastLoadError = "文件约 ${len / 1048576}MB，该格式需整本读入内存建索引，已超出设备可用内存上限" +
                    "（约 ${limit / 1048576}MB），未装载。建议换用 .obk（SQLite）或 .bin（象眼）版本，或用「扫本地目录」原地读取。"
            Log.w(TAG, "库文件过大(${len / 1048576}MB > ${limit / 1048576}MB)，该格式需整本建索引，拒绝装载以免 OOM: $name")
            return null
        }
        val bytes = readBytes(path) ?: run {
            lastLoadError = "无法读取库文件（文件不存在、被移动或没有读取权限）。"
            return null
        }
        val loaded = loadBookFromBytes(name, path, bytes, group)
        if (loaded == null && lastLoadError == null) {
            lastLoadError = "无法识别该文件格式（支持 json/txt/pkb/ubb/pgn/dat/bin/pfc/cbl/obk/pfBook），或已达最多 $MAX_BOOKS 个来源上限。"
        }
        return loaded
    }

    /**
     * 单本库"整本进内存"的安全上限：按可用堆动态取（约 1/4，夹在 32MB~512MB）。
     * 文本格式还会再转成 UTF-16 字符串（内存翻倍），故真实可装阈值比这更保守。
     */
    private fun inMemBookLimitBytes(): Long =
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(32L * 1024 * 1024, 512L * 1024 * 1024)

    /**
     * 从字节内容装载一个库（路径/扩展名仅用于识别格式与持久化）。
     * 供 SAF 文件选择器 / 外置注入使用；真实格式由扩展名决定。
     * @param group 来源分组：压缩包解出的库共享压缩包路径（不重复占档位）；
     *              单个文件留空 → 以其自身路径为一档。
     */
    fun loadBookFromBytes(name: String, pathOrName: String, bytes: ByteArray, group: String = ""): LoadedBook? {
        // 单个来源（非压缩包）才受档位上限约束；压缩包内的库共享同一 group，不逐个占位
        if (group.isBlank() && sourceCount() >= MAX_BOOKS) {
            Log.w(TAG, "已达到最多 $MAX_BOOKS 个来源，拒绝加载 $name")
            return null
        }
        val lower = pathOrName.lowercase()
        val text = String(bytes, Charsets.UTF_8)
        val loaded: LoadedBook? = when {
            // ★ 先按魔数认「SQLite 开局库 / 二进制 ABS 库」—— .obk、.pfBook（鹏飞）及任何扩展名
            //   都按内容认出来，整本不进内存。放在最前，杜绝"看后缀放行"漏掉冷门扩展名的库。
            ObkParser.isSqliteMagic(bytes) || ObkParser.isObkMagic(bytes) ->
                loadObkFromBytes(name, pathOrName, bytes)
            lower.endsWith(".json") -> loadJsonFromText(name, pathOrName, text)
            lower.endsWith(".pkb") -> loadPkbFromBytes(name, pathOrName, bytes)
            lower.endsWith(".ubb") -> loadUbbFromText(name, pathOrName, text)
            lower.endsWith(".pgn") -> loadPgnFromText(name, pathOrName, text)
            // .obk / .pfBook（鹏飞开局库，SQLite 的 pfBook 表）→ 同一套 SQLite 反解
            lower.endsWith(".obk") || lower.endsWith(".pfbook") -> loadObkFromBytes(name, pathOrName, bytes)
            // .pfc 鹏飞象棋 XML 谱
            lower.endsWith(".pfc") -> loadPfcFromText(name, pathOrName, text)
            // .cbl 象棋桥原生棋谱，运行时解码后回放建为开局库
            lower.endsWith(".cbl") -> loadCblFromBytes(name, pathOrName, bytes)
            // .dat/.bin 或内容形如"<走法> <分值> <FEN>"：先按二进制象眼库认，失败再按文本库认
            lower.endsWith(".dat") || lower.endsWith(".bin") || looksLikeBookDat(text) ->
                loadEkBook(name, pathOrName, bytes, text)
            lower.endsWith(".txt") -> loadTxtFromText(name, pathOrName, text)
            else -> null
        }
        if (loaded != null) {
            loaded.group = group.ifEmpty { pathOrName }
            books.add(loaded)
            Log.i(TAG, "已加载外置库: ${loaded.name} (${loaded.format}, ${loaded.entryCount}个局面, 来源组=${loaded.group})")
        }
        return loaded
    }

    /** 直接以文本内容添加一个 JSON 库（不落盘，供内置/注入用） */
    fun addJsonBook(name: String, jsonText: String): LoadedBook? {
        if (sourceCount() >= MAX_BOOKS) return null
        val book = LoadedBook(nextId++, name, "", "json", true, mutableMapOf(), null, 0)
        val ok = parseJsonInto(jsonText, book.entries)
        if (!ok) { Log.w(TAG, "JSON 书解析失败: $name"); return null }
        book.entryCount = book.entries.size
        books.add(book)
        return book
    }

    // ─────────────────────────────────────────────────────────────
    // 查询：命中即返回"翻库"结果
    // ─────────────────────────────────────────────────────────────
    /**
     * @param fen 当前局面（取 Board.toFen()）
     * @param excludedUci 本回合要排除的着法（如循环违规），命中跳过
     * @return 命中的库与着法；无命中返回 null
     */
    fun query(fen: String, excludedUci: Set<String> = emptySet()): BookHit? {
        val pool = mutableListOf<Pair<MoveCand, LoadedBook>>()
        for (book in books) {
            if (!book.enabled) continue
            // ★ xqb：按局面直查（不建索引）
            // ★ xqb：按 FEN 直查（内部还原 Board 算规范键）
            book.xqbBook?.let { h ->
                for ((uci, sc) in h.query(fen)) {
                    if (uci in excludedUci) continue
                    pool.add(MoveCand(uci, sc, 0) to book)
                }
                return@let
            }
            if (book.xqbBook != null) continue
            val cands = book.entries[fen] ?: continue
            for (c in cands) {
                if (c.uci in excludedUci) continue
                pool.add(c to book)
            }
        }
        if (pool.isEmpty()) return null
        val chosen = pickFromPool(pool) ?: return null
        return BookHit(chosen.second.name, chosen.second.format, chosen.first.uci,
            chosen.first.winRate, chosen.first.totalGames)
    }

    /**
     * 按当前棋盘查库：OBK 走「vkey 直查」（整本库任意局面 O(1) 命中），
     * 其余格式走 FEN 索引。这是主对弈路径（ChessGame）使用的查询入口。
     * @param board 当前棋盘（以棋盘真源计算 OBK 哈希 / FEN）
     * @param excludedUci 本回合排除的着法
     * @return 命中的库与着法；无命中返回 null
     */
    fun queryByBoard(board: Board, excludedUci: Set<String> = emptySet()): BookHit? {
        val pool = mutableListOf<Pair<MoveCand, LoadedBook>>()
        for (book in books) {
            if (!book.enabled) continue
            if (book.diskBin != null) {
                // 象眼磁盘大库：对当前局面 lock1 直查（常数内存，库多大都 O(log n)）
                collectDiskBinCands(board, book, excludedUci, pool)
            } else if (book.e2LockDict != null) {
                collectEkCands(board, book, excludedUci, pool)
            } else if (book.xqbBook != null) {
                for ((uci, sc) in book.xqbBook!!.query(board)) {
                    if (uci in excludedUci) continue
                    pool.add(MoveCand(uci, sc, 0) to book)
                }
            } else if (book.sqliteBook != null) {
                // SQLite 大库：对当前棋盘算 vkey 直查（常数内存，库多大都 O(log n)）
                val key = try { ObkParser.vkeyOf(board) } catch (e: Exception) { null }
                if (key != null) collectSqliteCands(book, key, excludedUci, pool)
            } else if (book.vkeyDict != null) {
                // OBK：直接对当前棋盘算 vkey 直查
                val key = try { ObkParser.vkeyOf(board) } catch (e: Exception) { null }
                val cands = key?.let { book.vkeyDict[it] } ?: continue
                for (v in cands) {
                    if (!v.enabled || v.uci in excludedUci) continue
                    pool.add(MoveCand(v.uci, v.score, 1, v.winRate, v.totalGames) to book)
                }
            } else {
                val cands = book.entries[board.toFen()] ?: continue
                for (c in cands) {
                    if (c.uci in excludedUci) continue
                    pool.add(c to book)
                }
            }
        }
        if (pool.isEmpty()) return null
        val chosen = pickFromPool(pool) ?: return null
        return BookHit(chosen.second.name, chosen.second.format, chosen.first.uci,
            chosen.first.winRate, chosen.first.totalGames)
    }

    /**
     * ★ 多源平行汇审：返回当前局面开局库候选里的 top-N 着法（按"胜率+得分"降序），
     * 供与云库候选合并后一并交给引擎 searchmoves 汇审。OBK 走 vkey 直查，其余走 FEN 索引。
     * @param n 最多取几着
     * @param excludedUci 本回合排除的着法（如循环违规）
     * @return 已按 UCI 去重、降序的着法列表；无命中返回空表
     */
    fun queryTop(board: Board, n: Int, excludedUci: Set<String> = emptySet()): List<BookHit> {
        val pool = mutableListOf<Pair<MoveCand, LoadedBook>>()
        for (book in books) {
            if (!book.enabled) continue
            if (book.diskBin != null) {
                collectDiskBinCands(board, book, excludedUci, pool)
            } else if (book.e2LockDict != null) {
                collectEkCands(board, book, excludedUci, pool)
            } else if (book.xqbBook != null) {
                // ★ xqb 直查（与 queryByBoard 同一套规范键）
                for ((uci, sc) in book.xqbBook!!.query(board)) {
                    if (uci in excludedUci) continue
                    pool.add(MoveCand(uci, sc, 1) to book)
                }
            } else if (book.sqliteBook != null) {
                // SQLite 大库：对当前棋盘算 vkey 直查
                val key = try { ObkParser.vkeyOf(board) } catch (e: Exception) { null }
                if (key != null) collectSqliteCands(book, key, excludedUci, pool)
            } else if (book.vkeyDict != null) {
                // OBK：直接对当前棋盘算 vkey 直查
                val key = try { ObkParser.vkeyOf(board) } catch (e: Exception) { null }
                val cands = key?.let { book.vkeyDict[it] } ?: continue
                for (v in cands) {
                    if (!v.enabled || v.uci in excludedUci) continue
                    pool.add(MoveCand(v.uci, v.score, 1, v.winRate, v.totalGames) to book)
                }
            } else {
                val cands = book.entries[board.toFen()] ?: continue
                for (c in cands) {
                    if (c.uci in excludedUci) continue
                    pool.add(c to book)
                }
            }
        }
        if (pool.isEmpty()) return emptyList()
        // 按 UCI 去重（同名取高分），再按"胜率权重+得分"降序取 top-N
        val byUci = HashMap<String, Pair<MoveCand, LoadedBook>>()
        for (p in pool) {
            val prev = byUci[p.first.uci]
            if (prev == null || p.first.score > prev.first.score) byUci[p.first.uci] = p
        }
        val merged = ArrayList(byUci.values)
        merged.sortByDescending { it.first.winRate * 1000 - it.first.score }
        return merged.take(n).map {
            BookHit(it.second.name, it.second.format, it.first.uci, it.first.winRate, it.first.totalGames)
        }
    }

    /**
     * ★ 返回当前局面在所有已启用库中的全部着法集合（UCI 去重）。
     * 专供"思考细节着色"使用：只做字典命中判断，不做选招/概率抽取，开销小、绝不卡顿。
     * 三种库类型统一处理：FEN 索引 / OBK vkey 直查 / 象眼 lock1 直查。
     * @param board 当前棋盘
     * @return 含库着法的 UCI 集合（可能为空）
     */
    fun bookMoveSet(board: Board): Set<String> {
        val fen = board.toFen()
        val out = HashSet<String>()
        for (book in books) {
            if (!book.enabled) continue
            try {
                // ★ xqb：着色判断同样按规范键直查
                val xqb = book.xqbBook
                if (xqb != null) {
                    for ((uci, _) in xqb.query(board)) out.add(uci)
                    continue
                }
                if (book.diskBin != null) {
                    diskBinMoveSet(board, book, out)
                } else if (book.e2LockDict != null) {
                    var moves = book.e2LockDict?.get(ElephantEyeCrypt.lock1OfFen(fen, false))
                    if (moves.isNullOrEmpty()) moves = book.e2MirrorDict?.get(ElephantEyeCrypt.lock1OfFen(fen, true))
                    if (moves != null) for ((uci, _) in moves) out.add(uci)
                } else if (book.sqliteBook != null) {
                    val key = ObkParser.vkeyOf(board)
                    book.sqliteBook?.let { h ->
                        ObkParser.querySqlite(h, key) { bm ->
                            ObkParser.decodeMove(bm.vmove)?.let { out.add(it) }
                        }
                    }
                } else if (book.vkeyDict != null) {
                    val key = ObkParser.vkeyOf(board)
                    val cands = key?.let { book.vkeyDict[it] } ?: continue
                    for (v in cands) if (v.enabled) out.add(v.uci)
                } else {
                    val cands = book.entries[fen] ?: continue
                    for (c in cands) out.add(c.uci)
                }
            } catch (e: Exception) { /* 单个库失败不影响其它库 */ }
        }
        return out
    }

    /**
     * 象眼二进制库：用当前局面 lock1 直查（含镜像）。
     * 与象眼 BookFile 两趟搜索一致：先按"原局面 lock1"命中；未命中再按"镜像局面 lock1"
     * 命中（此时用已 180° 镜像回当前局面的着法）。命中后做棋规校验，排除非法/非本方着法。
     */
    private fun collectEkCands(
        board: Board,
        book: LoadedBook,
        excludedUci: Set<String>,
        pool: MutableList<Pair<MoveCand, LoadedBook>>
    ) {
        val fen = board.toFen()
        val lock = ElephantEyeCrypt.lock1OfFen(fen, false)
        var moves = book.e2LockDict?.get(lock)
        if (moves.isNullOrEmpty()) {
            val mlock = ElephantEyeCrypt.lock1OfFen(fen, true)
            moves = book.e2MirrorDict?.get(mlock)
        }
        if (moves.isNullOrEmpty()) return
        for ((uci, score) in moves) {
            if (uci in excludedUci) continue
            val mv = Move.fromUci(uci, board)
            if (mv == null || mv.piece.color != board.currentPlayer) continue
            pool.add(MoveCand(uci, score, 1) to book)
        }
    }

    /**
     * ★ SQLite 大库：按当前局面 vkey 直查（整数/位型两种存储口径），命中着法入池。
     * 只走索引取回该局面的几行，绝不扫描整表；内存占用与库大小无关。
     */
    private fun collectSqliteCands(
        book: LoadedBook,
        vkey: Long,
        excludedUci: Set<String>,
        pool: MutableList<Pair<MoveCand, LoadedBook>>
    ) {
        val handle = book.sqliteBook ?: return
        ObkParser.querySqlite(handle, vkey) { bm ->
            val uci = ObkParser.decodeMove(bm.vmove) ?: return@querySqlite
            if (uci in excludedUci) return@querySqlite
            pool.add(MoveCand(uci, bm.score, 1, bm.winRate, bm.wins + bm.draws + bm.losses) to book)
        }
    }

    /**
     * 在候选池里挑一着：最高分及其容差内(默认 12 分)参与加权随机；
     * 有经验胜率(vwin/vdraw/vlost)的候选按胜率再加权，避免"高分但实战很烂"的假招上位。
     */
    private fun pickFromPool(pool: List<Pair<MoveCand, LoadedBook>>): Pair<MoveCand, LoadedBook>? {
        if (pool.isEmpty()) return null
        val tolerance = 12
        var bestScore = Int.MIN_VALUE
        for ((c, _) in pool) if (c.score > bestScore) bestScore = c.score
        val nearBest = pool.filter { it.first.score >= bestScore - tolerance }
        if (nearBest.isEmpty()) return null
        return if (nearBest.size == 1) {
            nearBest.first()
        } else {
            // ★ 加权随机：分数越接近最高分权重越大；胜率>0 时叠加胜率权重（0..8 分）
            val sum = nearBest.fold(0) { acc, (c, _) ->
                acc + (c.score - bestScore + tolerance + 1) + (c.winRate * 8).toInt()
            }
            var r = kotlin.random.Random.nextInt(sum.coerceAtLeast(1))
            var pick = nearBest.first()
            for ((c, b) in nearBest) {
                r -= (c.score - bestScore + tolerance + 1) + (c.winRate * 8).toInt()
                if (r < 0) { pick = c to b; break }
            }
            pick
        }
    }

    /** 调试用：某局面的候选数 */
    fun probe(fen: String): Int {
        var n = 0
        for (book in books) n += book.entries[fen]?.size ?: 0
        return n
    }

    /**
     * ★ 背谱用：返回"当前局面在已启用库中存在的全部候选着法"（含来源书名）。
     * 背谱模式据此约束——用户只能走这里列出的着法；多着法即"变招/分支"。
     * 返回顺序即分支序号（0 起）：函数内部按书名 -> 库内顺序稳定排列。
     * 同时返回每分支的深度（剩余深度，越大说明主线越完整）。
     */
    data class BackCand(val uci: String, val bookName: String, val branchIndex: Int, val depth: Int)
    fun candidatesFor(fen: String): List<BackCand> {
        val out = ArrayList<BackCand>()
        var idx = 0
        for (book in books) {
            if (!book.enabled) continue
            val cands = book.entries[fen] ?: continue
            for (c in cands) {
                out.add(BackCand(c.uci, book.name, idx, c.depth))
                idx++
            }
        }
        return out
    }

    fun books(): List<LoadedBook> = books.toList()

    // ─────────────────────────────────────────────────────────────
    // 容量/内存摸底（供侧滑栏"库容量预警"使用；数值均为估算，作参考方向）
    // ─────────────────────────────────────────────────────────────
    data class CapacityStats(
        val sources: Int,          // 已占来源档位（1 库文件 或 1 压缩包 = 1 档）
        val sourcesMax: Int,       // 档位上限（= MAX_BOOKS）
        val positions: Long,       // 全部库可命中的局面总数
        val bookBytes: Long,       // 开局库索引估算占用堆字节
        val perPositionBytes: Int  // 平均每局面估算占用字节（自适应）
    )

    /** 全库容量摸底：局面数 + 估算占用 + 档位占用。常量型估值，括号内为每局面平均字节。 */
    fun capacityStats(): CapacityStats {
        var pos = 0L
        var bytes = 0L
        for (b in books) {
            val p = positionCount(b)
            pos += p
            bytes += p * bytesPerPosition(b)
        }
        // 有实据时用实测均值；无库时给一个保守经验值（约 320B/局面）
        val per = if (pos > 0) (bytes / pos).toInt() else 320
        return CapacityStats(sourceCount(), MAX_BOOKS, pos, bytes, per)
    }

    /** 一本书的可命中局面数（不同索引结构口径统一为"局面/键"） */
    private fun positionCount(b: LoadedBook): Long {
        b.sqliteBook?.let { return it.rowCount.toLong() }
        if (b.diskBin != null) return b.diskBinRecs
        val v = b.vkeyDict
        if (v != null) return v.size().toLong()
        val d = b.e2LockDict
        if (d != null) return d.size.toLong()
        return b.entries.size.toLong()
    }

    /**
     * 每局面的估算内存：按索引结构类型给经验系数——
     *   OBK vkey 字典：LongSparseArray 原始 long 键 + VkeyMove 多字段 ≈ 230B/局面
     *   象眼 BIN 两套 Long 字典(direct+mirror) ≈ 260B/局面
     *   FEN 索引(json/txt/pgn/ubb/pfc/cbl/dat/pkb)：FEN 字符串 + MoveCand 列表 ≈ 320B/局面
     */
    private fun bytesPerPosition(b: LoadedBook): Int = when {
        // SQLite 大库 / 象眼磁盘库只驻留一个句柄，堆占用恒定（≈ 0），与行数无关
        b.sqliteBook != null -> 1
        b.diskBin != null -> 1
        b.vkeyDict != null -> 230
        b.e2LockDict != null -> 260
        else -> 320
    }

    // ─────────────────────────────────────────────────────────────
    // 解析实现
    // ─────────────────────────────────────────────────────────────
    private fun loadJsonFromText(name: String, path: String, text: String): LoadedBook? {
        val book = LoadedBook(nextId, name, path, "json", true, mutableMapOf(), null, 0)
        if (!parseJsonInto(text, book.entries)) return null
        book.entryCount = book.entries.size
        bumpId()
        return book
    }

    private fun parseJsonInto(text: String, entries: MutableMap<String, MutableList<MoveCand>>): Boolean {
        return try {
            val root = JSONObject(text)
            val startFen = root.optString("startFen", "").trim().ifEmpty { null }
            val lines = root.optJSONArray("lines") ?: JSONArray()
            for (i in 0 until lines.length()) {
                val line = lines.optJSONObject(i)
                if (line == null) continue
                val movesArr = line.optJSONArray("moves") ?: continue
                val score = line.optInt("score", 50)
                val lineStartFen = line.optString("startFen", "").trim().ifEmpty { startFen }
                val uciList = mutableListOf<String>()
                for (j in 0 until movesArr.length()) {
                    val m = movesArr.optString(j).trim()
                    if (m.isNotEmpty()) uciList.add(m)
                }
                replayLine(lineStartFen, uciList, score, entries)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "JSON 书解析失败", e)
            false
        }
    }

    private fun loadTxtFromText(name: String, path: String, text: String): LoadedBook? {
        val book = LoadedBook(nextId, name, path, "txt", true, mutableMapOf(), null, 0)
        text.lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith("//") }.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            var startFen: String? = null
            var rest = line
            // 支持 "# startFen: ..." 前缀指定起始局面
            if (line.startsWith("#")) {
                val head = line.removePrefix("#").trim()
                if (head.startsWith("startFen")) {
                    val parts = head.split(Regex("="), limit = 2)
                    if (parts.size == 2) { startFen = parts[1].trim(); rest = "" }
                }
            }
            val uciList = rest.split(Regex("\\s+")).filter { it.length >= 4 }
            if (uciList.isEmpty()) return@forEach
            replayLine(startFen, uciList, 50, book.entries)
        }
        book.entryCount = book.entries.size
        bumpId()
        return book
    }

    private fun loadPkbFromBytes(name: String, path: String, bytes: ByteArray): LoadedBook? {
        val book = LoadedBook(nextId, name, path, "pkb", true, mutableMapOf(), null, 0)
        val ok = parsePkb(bytes, book.entries)
        if (!ok) return null
        book.entryCount = book.entries.size
        bumpId()
        return book
    }

    /**
     * 东萍 DhtmlXQ（.ubb）棋谱：形如
     *   [DhtmlXQ_binit]初始局面[/DhtmlXQ_binit]
     *   [DhtmlXQ_movelist]h2e2h7e7h0g2...[/DhtmlXQ_movelist]
     * binit 缺省即标准开局；movelist 是 UCI 走法串（无分隔）。可 100% 可靠解析。
     */
    private fun loadUbbFromText(name: String, path: String, text: String): LoadedBook? {
        val book = LoadedBook(nextId, name, path, "ubb", true, mutableMapOf(), null, 0)
        val binit = Regex("\\[DhtmlXQ_binit]([^\\[]*)\\[/DhtmlXQ_binit]").find(text)?.groupValues?.get(1)?.trim()
        val movelist = Regex("\\[DhtmlXQ_movelist]([^\\[]*)\\[/DhtmlXQ_movelist]").find(text)?.groupValues?.get(1)?.trim()
        if (movelist.isNullOrEmpty()) {
            Log.w(TAG, "UBB 无 movelist，跳过: $name")
            return null
        }
        // 东萍 movelist 为连续 UCI 串；个别带空格/换行则清洗
        val cleaned = movelist.replace(Regex("\\s+"), "")
        val uciList = cleaned.chunked(4).filter { it.length == 4 }
        if (uciList.isEmpty()) return null
        val startFen = binit?.ifEmpty { null }
        replayLine(startFen, uciList, 60, book.entries)
        book.entryCount = book.entries.size
        bumpId()
        Log.i(TAG, "UBB 解析成功: $name（${uciList.size} 手）")
        return book
    }

    /**
     * PGN 棋谱：解析 [FEN "..."] 标记与走法（支持 UCI / 中文记谱 / ICCS 坐标）。
     * 中文记谱如"炮二平五"、ICCS 如"h2e2"、标准坐标如"h2-h2e2"均归一为 UCI。
     */
    private fun loadPgnFromText(name: String, path: String, text: String): LoadedBook? {
        val book = LoadedBook(nextId, name, path, "pgn", true, mutableMapOf(), null, 0)
        val fenMatch = Regex("\\[FEN\\s+\"([^\"]+)\"\\]").find(text)
        val startFen = fenMatch?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        // 去掉标记头与注释，只留走法 token
        var body = text
        Regex("\\[[^\\]]*]").findAll(text).forEach { body = body.replace(it.value, " ") }
        body = body.replace(Regex("\\{[^}]*}"), " ")
        body = body.replace(Regex("\\d+\\.\\.\\.?"), " ")
        body = body.replace(Regex("\\d+\\."), " ")
        val tokens = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val uciList = mutableListOf<String>()
        for (t in tokens) {
            val uci = pgnTokenToUci(t)
            if (uci != null) uciList.add(uci)
        }
        if (uciList.isEmpty()) return null
        replayLine(startFen, uciList, 50, book.entries)
        book.entryCount = book.entries.size
        bumpId()
        Log.i(TAG, "PGN 解析成功: $name（${uciList.size} 手）")
        return book
    }

    /** 单个 PGN 走法 token → UCI；无法识别返回 null */
    private fun pgnTokenToUci(t: String): String? {
        // 已是 UCI（4 位字母数字，如 h2e2）
        if (t.length == 4 && t.all { it.isLetterOrDigit() } && t[0].isLetter() && t[2].isLetter()) return t.lowercase()
        // 带连字符/结束符的坐标，如 h2-e2、h2e2+
        val m = Regex("^([a-i][0-9])[- ]?([a-i][0-9])[+#]?$").find(t)
        if (m != null) return m.groupValues[1] + m.groupValues[2]
        // 中文记谱：炮二平五 / 马8进7 等
        val c = Regex("^([车马炮相象仕将士卒兵帅])([一二三四五六七八九])([进退平])([一二三四五六七八九])$").find(t)
        if (c != null) return chineseToUci(c.groupValues[1], c.groupValues[2], c.groupValues[3], c.groupValues[4])
        return null
    }

    /** 中文记谱 → UCI（基于标准开局坐标，供 PGN 导入；非标准开局无法可靠转换则返回 null） */
    private fun chineseToUci(piece: String, fromCol: String, action: String, target: String): String? {
        val red = piece in setOf("车", "马", "炮", "相", "仕", "帅", "兵")
        val colMap = mapOf("一" to 0, "二" to 1, "三" to 2, "四" to 3, "五" to 4,
            "六" to 5, "七" to 6, "八" to 7, "九" to 8)
        val col = colMap[fromCol] ?: return null
        val t = colMap[target] ?: return null
        // 红方：列 0-8 对应 a-i；黑方：列 0-8 对应 i-a
        val fromFile = if (red) ('a' + col) else ('i' - col)
        val toFile = if (red) ('a' + t) else ('i' - t)
        // 行：红方底线为 0（rank 0），黑方底线为 9
        val fromRank = if (red) 0 else 9
        val toRank = when (action) {
            "平" -> fromRank
            "进" -> if (red) fromRank + 1 else fromRank - 1
            "退" -> if (red) fromRank - 1 else fromRank + 1
            else -> return null
        }
        if (toRank !in 0..9) return null
        return "" + fromFile + fromRank + toFile + toRank
    }

    /**
     * 鹏飞象棋 .pfc：XML 树形棋谱。根节点 <n m="FEN">，子节点逐层嵌套，
     * 每个 <n m="UCI走法" s="分值"> 表示"走此步前的局面 → 此步"。DFS 建为开局库索引。
     */
    private fun loadPfcFromText(name: String, path: String, text: String): LoadedBook? {
        val book = LoadedBook(nextId, name, path, "pfc", true, mutableMapOf(), null, 0)
        try {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            // 安全加固：禁止 DTD/外部实体，防 XXE
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
            dbf.setExpandEntityReferences(false)
            val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            val root = doc.documentElement ?: return null
            var startFen: String? = null
            val rootM = root.getAttribute("m").trim()
            if (isFenText(rootM)) startFen = rootM
            val b = Board()
            if (startFen != null) b.loadFromFen(startFen) else b.reset()
            pfcWalk(root, b, 55, book.entries)
        } catch (e: Exception) {
            Log.e(TAG, "PFC 解析失败: $name", e)
            return null
        }
        if (book.entries.isEmpty()) {
            Log.w(TAG, "PFC 无可用的开局变化: $name")
            return null
        }
        book.entryCount = book.entries.size
        bumpId()
        Log.i(TAG, "鹏飞 PFC 开局库解析成功: $name（${book.entryCount} 局面可命中）")
        return book
    }

    /** DFS 遍历 PFC 树：把每个 <n> 走法子节点记入"走此步前的局面 → 此步"索引 */
    private fun pfcWalk(node: Element, board: Board, score: Int, entries: MutableMap<String, MutableList<MoveCand>>) {
        val nl = node.childNodes
        for (i in 0 until nl.length) {
            val nd = nl.item(i)
            if (nd !is Element) continue
            val mvStr = nd.getAttribute("m").trim()
            if (mvStr.isEmpty()) continue
            val childScore = nd.getAttribute("s").toIntOrNull() ?: score
            // 该子节点若实为"换起始局面"标记（FEN），则以其为新起点继续
            if (isFenText(mvStr)) {
                val sub = Board()
                try { sub.loadFromFen(mvStr) } catch (e: Exception) { continue }
                pfcWalk(nd, sub, childScore, entries)
                continue
            }
            val uci = mvStr.lowercase()
            val fenBefore = board.toFen()
            val mv = try { Move.fromUci(uci, board) } catch (e: Exception) { null } ?: continue
            if (!board.makeMove(mv)) continue
            val cands = entries.getOrPut(fenBefore) { mutableListOf() }
            val depth = pfcSubDepth(nd)
            val existing = cands.find { it.uci == uci }
            if (existing == null) cands.add(MoveCand(uci, childScore, depth))
            else if (childScore > existing.score) { existing.score = childScore; existing.depth = depth }
            pfcWalk(nd, board, childScore, entries)
            board.undoMove()
        }
    }

    /** 该 <n> 子树剩余深度（主线越长越有价值），用于深度排序 */
    private fun pfcSubDepth(node: Element): Int {
        var d = 0
        fun rec(n: Element, depth: Int) {
            if (depth > d) d = depth
            val nl = n.childNodes
            for (i in 0 until nl.length) { val c = nl.item(i); if (c is Element) rec(c, depth + 1) }
        }
        rec(node, 1)
        return d
    }

    /** 标准棋盘 FEN 有 9 个 '/'；UCI 走法（如 b2e2）则没有 */
    private fun isFenText(s: String): Boolean = s.count { it == '/' } >= 9

    /**
     * 象棋桥 .cbl 原生棋谱：用 CblDecoder 运行时解码出对局列表，
     * 把每局回放成"局面 → 着法"索引（多个对局共局面即汇聚候选），可作为开局库被命中。
     * 开局阶段为主：每局只取前 30 手，最多 500 局，避免过度占用内存。
     */
    private fun loadCblFromBytes(name: String, path: String, bytes: ByteArray): LoadedBook? {
        val book = LoadedBook(nextId, name, path, "cbl", true, mutableMapOf(), null, 0)
        val games = try {
            CblDecoder.decodeBook(bytes, name)
        } catch (e: Exception) {
            Log.e(TAG, "CBL 解码失败: $name", e)
            emptyList()
        }
        if (games.isEmpty()) {
            Log.w(TAG, "CBL 无可识别的对局: $name")
            return null
        }
        var used = 0
        for (g in games) {
            if (used >= 500) break
            if (g.movesUci.size < 3) continue
            val uciList = g.movesUci.take(30)
            val startFen = g.fenBoard?.let { "$it ${if (g.moversBlack) "b" else "w"} - - 0 1" }
            replayLine(startFen, uciList, 55, book.entries)
            used++
        }
        if (book.entries.isEmpty()) {
            Log.w(TAG, "CBL 回放后无有效局面: $name")
            return null
        }
        book.entryCount = book.entries.size
        bumpId()
        Log.i(TAG, "CBL 开局库构建成功: $name（${book.entryCount} 局面 / $used 局）")
        return book
    }

    /**
     * ★★★ 内置 CBL 全集装载（作为一个合并开局库）★★★
     *
     * 把 assets/qipu_cbl/data/ 下的全部象棋桥(CBL)原文件（开局/中局/残局/古谱/名手/赛事等
     * 全部分类）各回放一截对局，合并进同一个 LoadedBook —— 只占一个来源档位，且单个书对象
     * 可被 queryByBoard 在任意局面（开局库/中局库/残局库，即任意一步之后的 FEN）命中。
     *
     * 用途：挑战模式最终 BOSS 档雪鱼，把整部中国象棋棋谱集装为它的"开局+中局+残局库"。
     * 限量（per-file / total）用于把装载时间和内存控制在可接受的秒级范围。
     */
    fun loadBuiltinCblCollection(context: Context): LoadedBook? {
        val files = try {
            HeavyStore.list(context, BUILTIN_CBL_DIR)
                ?.filter { it.endsWith(".cbl", true) }
                ?.sorted() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "读取内置 CBL 目录失败", e)
            emptyList()
        }
        if (files.isEmpty()) {
            Log.w(TAG, "内置 CBL 全集目录为空")
            return null
        }
        val book = LoadedBook(nextId, "中国象棋棋谱全集(CBL)", "builtin:cbl-collection", "cbl", true, mutableMapOf(), null, 0)
        var totalGames = 0
        var gotBytes = 0
        for (f in files) {
            val bytes = try {
                HeavyStore.openLocalFirst(context, "$BUILTIN_CBL_DIR/$f").use { it.readBytes() }
            } catch (e: Exception) {
                continue
            }
            gotBytes++
            val games = try { CblDecoder.decodeBook(bytes, f) } catch (e: Exception) { emptyList() }
            var used = 0
            for (g in games) {
                if (totalGames >= BUILTIN_CBL_TOTAL_CAP) break
                if (used >= BUILTIN_CBL_PER_FILE) break
                if (g.movesUci.size < 3) continue
                val uciList = g.movesUci.take(30)
                val startFen = g.fenBoard?.let { "$it ${if (g.moversBlack) "b" else "w"} - - 0 1" }
                replayLine(startFen, uciList, 55, book.entries)
                used++; totalGames++
            }
            if (totalGames >= BUILTIN_CBL_TOTAL_CAP) break
        }
        if (book.entries.isEmpty()) {
            Log.w(TAG, "内置 CBL 全集回放后无有效局面")
            return null
        }
        book.entryCount = book.entries.size
        bumpId()
        Log.i(TAG, "内置 CBL 全集装载完成：${book.entryCount} 局面 / $totalGames 局 / 读取 $gotBytes 个文件")
        return book
    }

    /**
     * OBK：真实反解装载（内存字节版，用于压缩包内小库等仍以字节传入的场景）。
     * 该族格式（ABS0LUTEDATABASE）是 Windows 私有二进制开局库，使用 Zobrist 哈希保存局面（vkey）
     * + 每局面多着（vmove/vscore/vwin/经过/失败/启用位）。
     *
     * 反解原理（移植自 obk2dat.py，该工具已验证可把这些 .obk 完整转成 .dat）：
     *   解析 4096 字节数据页，提取每条 46 字节记录 → (vkey, vmove, vscore, vvalid)，
     *   单遍直接灌入「Long vkey → 着法」索引，不产生中间字典副本。
     */
    private fun loadObkFromBytes(name: String, path: String, bytes: ByteArray): LoadedBook? {
        // 先做格式识别（魔数不符则拒绝，避免无关二进制被当成 OBK）
        if (!ObkParser.isObkMagic(bytes) && !ObkParser.isSqliteMagic(bytes)) {
            Log.w(TAG, "OBK 魔数不符，拒绝装载（避免挂空库）: $name")
            return null
        }
        val book = LoadedBook(nextId, name, path, "obk", true, mutableMapOf(), LongSparseArray(), 0)
        // ★ 真实反解为「vkey局面 → 着法」。保留整本 vkey 字典，走子时用当前棋盘
        //   的 Zobrist 直查（O(1)），整本库任意局面都能命中，不再被"根局面重放"砍掉。
        if (!ObkParser.parseBookBytes(bytes) { vkey, bm -> feedVkeyRecord(book, vkey, bm) }) {
            Log.w(TAG, "OBK 反解后无可用着法，拒绝装载: $name")
            return null
        }
        book.entryCount = book.vkeyDict!!.size()   // ★ 命中局面数 = 全库可用局面数（不再只有几十）
        bumpId()
        Log.i(TAG, "OBK 反解成功: $name（${book.entryCount} 个局面可直查命中）")
        return book
    }

    /** 读文件头 16 字节，判断是否为 SQLite 开局库（含 .pfBook）/ 二进制 ABS 库 —— 不看扩展名，按内容认。 */
    private fun looksLikeSqliteOrAbs(path: String): Boolean = try {
        val f = java.io.File(path)
        if (!f.exists() || f.length() <= 0) false else {
            java.io.FileInputStream(f).use { ins ->
                val h = ByteArray(16)
                var off = 0
                while (off < 16) { val n = ins.read(h, off, 16 - off); if (n < 0) break; off += n }
                val head = if (off == 16) h else h.copyOf(off)
                ObkParser.isSqliteMagic(head) || ObkParser.isObkMagic(head)
            }
        }
    } catch (e: Exception) { false }

    /**
     * ★ 大库专用：从文件路径流式装载 OBK（整本 190MB+ 也能装）。
     * 逐 4096 字节数据页解析（或 SQLite 直读），把每条记录直接灌入 vkey 字典，
     * 全程不把整本文件读进内存——峰值内存 ≈ 最终索引本身。
     */
    private fun loadObkFromPath(name: String, path: String): LoadedBook? {
        // ★ 首选：SQLite 大库（极速清风/旋风系 .obk = bhobk 表）—— 只建句柄、按局面 vkey 索引直查。
        //   整表不入内存，190MB+ 亦只占常数内存；这正是参考实现"大库不崩"的正解。
        ObkParser.openSqliteBook(path)?.let { handle ->
            val b = LoadedBook(nextId, name, path, "obk", true, mutableMapOf(), null, handle.rowCount)
            b.sqliteBook = handle
            bumpId()
            lastLoadError = null
            Log.i(TAG, "OBK 装载成功(SQLite 句柄直查): $name（表=${handle.table}，${b.entryCount} 行，零整表装载）")
            return b
        }
        // ★ 确实是 SQLite 库、却没找到开局库表 —— 如实说明并附上库内真实表结构（便于定位不同厂家的库）
        ObkParser.sqliteDiagnosis(path)?.let { diag ->
            lastLoadError = "这是 SQLite 库，但没找到开局库表（需要含 vkey/vmove 列的表，如 bhobk）。库内实际表：$diag"
            Log.w(TAG, "OBK 是 SQLite 但无开局库表: $name → $diag")
            return null
        }
        // 回退：二进制 ABS 库 —— 逐页流式反解建 vkey 索引（峰值内存 ≈ 索引本身）
        val book = LoadedBook(nextId, name, path, "obk", true, mutableMapOf(), LongSparseArray(), 0)
        if (!ObkParser.parseBookFile(path) { vkey, bm -> feedVkeyRecord(book, vkey, bm) }) {
            lastLoadError = "该 .obk 既不是 SQLite 开局库，也不是可识别的二进制 ABS 库（或库内无有效着法）。"
            Log.w(TAG, "OBK 反解后无可用着法，拒绝装载: $name")
            return null
        }
        lastLoadError = null
        book.entryCount = book.vkeyDict!!.size()
        bumpId()
        Log.i(TAG, "OBK 反解成功(流式): $name（${book.entryCount} 个局面可直查命中）")
        return book
    }

    /** 把一条 OBK 记录灌入 vkey 字典（同局面同着法去重，取更高分） */
    private fun feedVkeyRecord(book: LoadedBook, vkey: Long, bm: ObkParser.BookMove) {
        if (bm.vvalid == 0) return                  // 仅收录启用着法
        val uci = ObkParser.decodeMove(bm.vmove) ?: return
        val dict = book.vkeyDict!!
        var list = dict.get(vkey)
        if (list == null) { list = mutableListOf(); dict.put(vkey, list) }
        val exists = list.find { it.uci == uci }
        if (exists == null) list.add(VkeyMove(uci, bm.score, bm.winRate, bm.wins + bm.draws + bm.losses))
        else if (bm.score > exists.score) exists.score = bm.score
    }

    // ─────────────────────────────────────────────────────────────
    // 组合装载器：先按二进制象眼库认，失败再按文本 BOOK.DAT 认
    // ─────────────────────────────────────────────────────────────
    private fun loadEkBook(name: String, path: String, bytes: ByteArray, text: String): LoadedBook? {
        // 1. 尝试二进制象眼库（如实现，可解析 e2LockDict/e2MirrorDict）
        val binary = try { loadEkBinary(name, path, bytes) } catch (e: Exception) { null }
        if (binary != null) return binary
        // 2. 尝试文本 BOOK.DAT
        if (looksLikeBookDat(text)) {
            return loadBookDatFromText(name, path, text)
        }
        Log.w(TAG, "既不是象眼二进制库，也不是 BOOK.DAT 文本，无法装载: $name")
        return null
    }

    /**
     * 解析象眼(ElephantEye)二进制开局库。
     * 结构：每条记录 8 字节 = dwZobristLock(u32 LE) + wmv(u16 LE) + wvl(u16 LE)，
     * 记录按 dwZobristLock 严格升序。装载时：
     *   - 直接字典 e2LockDict:  key=局面lock1, 值=该局面在库内的着法(原向 UCI + 分值)
     *   - 镜像字典 e2MirrorDict: key=镜像局面lock1, 值=已做 180° 镜像的着法(镜像回当前局面用)
     * 查询时用"当前局面 lock1 直查"命中即用；未命中再查镜像 lock1 用镜像着法，
     * 与象眼 BookFile.GetBookMoves 的原局面/镜像局面两趟搜索完全一致。
     */
    private fun loadEkBinary(name: String, path: String, bytes: ByteArray): LoadedBook? {
        val n = bytes.size
        if (n < 8 || n % 8 != 0) {
            Log.w(TAG, "象眼二进制库尺寸异常(非8字节整), 拒绝装载: $name size=$n")
            return null
        }
        val recs = n / 8

        // ── 格式嗅探：抽验 key 非降序（象眼库按 lock1 严格升序排序）──
        var prev = -1L
        var sorted = true
        var step = (recs / 2000).coerceAtLeast(1)
        var i = 0
        while (i < recs) {
            val k = u32le(bytes, i * 8)
            if (k < prev) { sorted = false; break }
            prev = k
            i += step
        }
        if (!sorted) {
            Log.w(TAG, "象眼二进制库 key 非升序, 判定非该格式: $name")
            return null
        }

        // ── 装载：按 key 分组，一次性建好 direct/mirror 两套字典 ──
        val book = LoadedBook(nextId, name, path, "dat-bin", true, mutableMapOf(), null, 0)
        val direct = HashMap<Long, MutableList<Pair<String, Int>>>()
        val mirrorMap = HashMap<Long, MutableList<Pair<String, Int>>>()
        var rec = 0
        while (rec < recs) {
            val key = u32le(bytes, rec * 8)
            var end = rec
            while (end < recs && u32le(bytes, end * 8) == key) end++
            val dl = mutableListOf<Pair<String, Int>>()
            val ml = mutableListOf<Pair<String, Int>>()
            for (r in rec until end) {
                val o = r * 8
                val wmv = (bytes[o + 4].toInt() and 0xFF) or ((bytes[o + 5].toInt() and 0xFF) shl 8)
                val wvl = (bytes[o + 6].toInt() and 0xFF) or ((bytes[o + 7].toInt() and 0xFF) shl 8)
                val uci = ElephantEyeCrypt.wmvToUci(wmv)
                if (uci.length == 4) {
                    dl.add(uci to wvl)
                    ml.add(ElephantEyeCrypt.wmvToUci(ElephantEyeCrypt.mirrorWmv(wmv)) to wvl)
                }
            }
            if (dl.isNotEmpty()) { direct[key] = dl; mirrorMap[key] = ml }
            rec = end
        }
        if (direct.isEmpty()) {
            Log.w(TAG, "象眼二进制库解码为空, 拒绝装载: $name")
            return null
        }
        book.e2LockDict = direct
        book.e2MirrorDict = mirrorMap
        book.entryCount = direct.size
        bumpId()
        Log.i(TAG, "象眼二进制库解析成功: $name（${direct.size} 局面可直查命中）")
        return book
    }

    private fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or
                ((b[o + 1].toLong() and 0xff) shl 8) or
                ((b[o + 2].toLong() and 0xff) shl 16) or
                ((b[o + 3].toLong() and 0xff) shl 24)

    /**
     * ★ 象眼二进制大库专用（磁盘版）：只保留文件句柄，按 8 字节记录升序 lock1 二分直查，
     *   整本几 GB 也不进内存（与 ElephantEye BookFile 的 mmap 直查同思路）。
     *   记录结构同 loadEkBinary：dwZobristLock(u32 LE) + wmv(u16 LE) + wvl(u16 LE)，按 key 升序。
     *   非该格式（尺寸非 8 字节整 / 抽验非升序）返回 null，交回字节路径处理。
     */
    private fun loadEkBinaryFromPath(name: String, path: String): LoadedBook? {
        val f = java.io.File(path)
        if (!f.exists() || !f.isFile) return null
        val n = f.length()
        if (n < 8L || n % 8L != 0L) return null
        val recs = n / 8L
        // 抽验升序（与内存版同一判据，最多取 2000 个采样点）
        val sorted = try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val buf = ByteArray(4)
                var prev = -1L
                var ok = true
                val step = (recs / 2000L).coerceAtLeast(1L)
                var i = 0L
                while (i < recs) {
                    raf.seek(i * 8L)
                    raf.readFully(buf)
                    val k = u32le(buf, 0)
                    if (k < prev) { ok = false; break }
                    prev = k
                    i += step
                }
                ok
            }
        } catch (e: Exception) { false }
        if (!sorted) return null
        val raf = try { java.io.RandomAccessFile(f, "r") } catch (e: Exception) { return null }
        val book = LoadedBook(nextId, name, path, "bin", true, mutableMapOf(), null, 0)
        book.diskBin = raf
        book.diskBinRecs = recs
        book.entryCount = if (recs > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else recs.toInt()
        bumpId()
        Log.i(TAG, "象眼二进制库装载成功(磁盘二分直查): $name（$recs 条记录，零整本装载）")
        return book
    }

    /** 磁盘库按 lock1 取一个 key 的全部记录（lower_bound 定位首条，再顺序读出同 key 段） */
    private fun readDiskBinKey(raf: java.io.RandomAccessFile, recs: Long, key: Long): List<Pair<Int, Int>> {
        val buf = ByteArray(8)
        var lo = 0L
        var hi = recs
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            raf.seek(mid * 8L)
            raf.readFully(buf)
            if (u32le(buf, 0) < key) lo = mid + 1 else hi = mid
        }
        if (lo >= recs) return emptyList()
        raf.seek(lo * 8L)
        raf.readFully(buf)
        if (u32le(buf, 0) != key) return emptyList()
        val out = ArrayList<Pair<Int, Int>>(4)
        var i = lo
        while (i < recs) {
            raf.seek(i * 8L)
            raf.readFully(buf)
            if (u32le(buf, 0) != key) break
            val wmv = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
            val wvl = (buf[6].toInt() and 0xFF) or ((buf[7].toInt() and 0xFF) shl 8)
            out.add(wmv to wvl)
            i++
        }
        return out
    }

    /** 磁盘象眼库直查：原局面 lock1 命中用原向着法；未命中再按镜像局面 lock1 查并用镜像着法 */
    private fun collectDiskBinCands(
        board: Board,
        book: LoadedBook,
        excludedUci: Set<String>,
        pool: MutableList<Pair<MoveCand, LoadedBook>>
    ) {
        val raf = book.diskBin ?: return
        val recs = book.diskBinRecs
        try {
            val hits = readDiskBinKey(raf, recs, ElephantEyeCrypt.lock1OfBoard(board, false))
            if (hits.isNotEmpty()) {
                for ((wmv, wvl) in hits) {
                    val uci = ElephantEyeCrypt.wmvToUci(wmv)
                    if (uci.length == 4 && uci !in excludedUci) pool.add(MoveCand(uci, wvl, 1) to book)
                }
            } else {
                val mHits = readDiskBinKey(raf, recs, ElephantEyeCrypt.lock1OfBoard(board, true))
                for ((wmv, wvl) in mHits) {
                    val uci = ElephantEyeCrypt.wmvToUci(ElephantEyeCrypt.mirrorWmv(wmv))
                    if (uci.length == 4 && uci !in excludedUci) pool.add(MoveCand(uci, wvl, 1) to book)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "磁盘库直查失败: ${book.name}", e)
        }
    }

    /** 磁盘象眼库：当前局面全部着法（供"思考细节"着色），含镜像趟 */
    private fun diskBinMoveSet(board: Board, book: LoadedBook, out: MutableSet<String>) {
        val raf = book.diskBin ?: return
        val recs = book.diskBinRecs
        try {
            val hits = readDiskBinKey(raf, recs, ElephantEyeCrypt.lock1OfBoard(board, false))
            if (hits.isNotEmpty()) {
                for ((wmv, _) in hits) {
                    val uci = ElephantEyeCrypt.wmvToUci(wmv)
                    if (uci.length == 4) out.add(uci)
                }
            } else {
                val mHits = readDiskBinKey(raf, recs, ElephantEyeCrypt.lock1OfBoard(board, true))
                for ((wmv, _) in mHits) {
                    val uci = ElephantEyeCrypt.wmvToUci(ElephantEyeCrypt.mirrorWmv(wmv))
                    if (uci.length == 4) out.add(uci)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "磁盘库着色直查失败: ${book.name}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 冰河五四等引擎的 BOOK.DAT 文本开局库
    //   每行：<走法> <分值> <FEN>。走法为坐标记法（如 b2e2=炮二平五、
    //   b0c2=马二进三），FEN 是该走法之前的局面（标准 FEN，黑方底线在前）。
    //   与 App 的 UCI / Board.toFen() 约定完全一致，可直接建索引。
    // ─────────────────────────────────────────────────────────────
    private fun loadBookDatFromText(name: String, path: String, text: String): LoadedBook? {
        val book = LoadedBook(nextId, name, path, "dat", true, mutableMapOf(), null, 0)
        data class Line(val move: String, val score: Int, val fen: String)
        val lines = mutableListOf<Line>()
        for (raw in text.lineSequence()) {
            val s = raw.trim()
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) continue
            val m = BOOK_DAT_LINE.find(s) ?: continue
            val move = m.groupValues[1]
            val score = m.groupValues[2].toIntOrNull() ?: continue
            val parts = m.groupValues[3].trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            // 归一化：只保留 棋盘+行棋方，与 Board.toFen() 输出一致（半回合/回合数固定为 0 1）
            lines.add(Line(move, score, parts[0] + " " + parts[1] + " - - 0 1"))
        }
        if (lines.isEmpty()) {
            Log.w(TAG, "冰河开局库无有效行，拒绝装载: $name")
            return null
        }
        // 按局面分组，每局面只建一次棋盘做轻校验（着法方必须等于行棋方，防脏数据）
        var validMoves = 0
        for ((fen, group) in lines.groupBy { it.fen }) {
            val b = Board()
            b.loadFromFen(fen)
            val cands = mutableListOf<MoveCand>()
            for (ln in group) {
                val mv = Move.fromUci(ln.move, b)
                if (mv == null || mv.piece.color != b.currentPlayer) continue
                cands.add(MoveCand(ln.move, ln.score, 1))
            }
            if (cands.isNotEmpty()) {
                book.entries[fen] = cands
                validMoves += cands.size
            }
        }
        if (book.entries.isEmpty()) {
            Log.w(TAG, "冰河开局库校验后为空，拒绝装载: $name")
            return null
        }
        book.entryCount = book.entries.size
        bumpId()
        Log.i(TAG, "冰河开局库解析成功: $name（$validMoves 着 / ${book.entryCount} 局面）")
        return book
    }

    /** 内容嗅探：形如"<走法> <分值> <FEN>"的文本库（冰河 BOOK.DAT） */
    private fun looksLikeBookDat(text: String): Boolean {
        var checked = 0
        for (raw in text.lineSequence()) {
            val s = raw.trim()
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) continue
            if (!BOOK_DAT_LINE.matches(s)) return false
            checked++
            if (checked >= 5) return true
        }
        return checked > 0
    }

    // ─────────────────────────────────────────────────────────────
    // 重放一条谱线 → 建索引
    // ─────────────────────────────────────────────────────────────
    private fun replayLine(
        startFen: String?,
        uciList: List<String>,
        score: Int,
        entries: MutableMap<String, MutableList<MoveCand>>
    ) {
        val b = Board()
        if (startFen != null) {
            b.loadFromFen(startFen)
        } else {
            b.reset()
        }
        val depth = uciList.size
        for ((idx, uci) in uciList.withIndex()) {
            // ★★ 先记录"走这一步之前"的局面 FEN（只有这一步校验通过才写候选到该局面）
            val fenBefore = b.toFen()
            val mv = Move.fromUci(uci, b)
            if (mv == null) {
                Log.w(TAG, "书谱着法无效, 已截断并弃用: $uci")
                return
            }
            // ★★★ 棋规校验（防劣谱被信任的根因）：先用 makeMove 验证着法合法、且确为
            //     当前行棋方的子。通过了才把这个"局面 -> 该着法"写入候选索引。
            //     原先的顺序是先写候选、后用 makeMove 验证，导致自吃己方子等非法步
            //     也进了命中候选 → 引擎开局落入错误局面/送吃劣着。
            if (!b.makeMove(mv)) {
                Log.w(TAG, "书谱着法落子失败(非法/自吃/越界), 已截断并弃用: $uci")
                return
            }
            val cands = entries.getOrPut(fenBefore) { mutableListOf() }
            val existing = cands.find { it.uci == uci }
            val remDepth = depth - idx
            if (existing == null) {
                cands.add(MoveCand(uci, score, remDepth))
            } else if (score > existing.score) {
                existing.score = score
                existing.depth = remDepth
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PKB 自有二进制：简单紧凑（entries序列化）
    //   header: "PKBV1" + int32 count
    //   每条:   int32 fenLen + utf8 fen + int32 candCount + [int32 uciLen+utf8 uci + int32 score + int32 depth]
    // ─────────────────────────────────────────────────────────────
    private fun parsePkb(bytes: ByteArray, entries: MutableMap<String, MutableList<MoveCand>>): Boolean {
        return try {
            if (bytes.size < 8) return false
            val hdr = String(bytes, 0, 5, Charsets.US_ASCII)
            if (hdr != "PKBV1") return false
            var pos = 5
            fun i32(): Int {
                val v = (bytes[pos].toInt() and 0xff) shl 24 or
                        ((bytes[pos + 1].toInt() and 0xff) shl 16) or
                        ((bytes[pos + 2].toInt() and 0xff) shl 8) or
                        (bytes[pos + 3].toInt() and 0xff)
                pos += 4; return v
            }
            fun str(): String {
                val n = i32(); val s = String(bytes, pos, n, Charsets.UTF_8); pos += n; return s
            }
            val count = i32()
            repeat(count) {
                val fen = str(); val candCount = i32()
                val list = mutableListOf<MoveCand>()
                repeat(candCount) {
                    val uci = str(); val score = i32(); val depth = i32()
                    list.add(MoveCand(uci, score, depth))
                }
                entries[fen] = list
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "PKB 解析失败", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
    private fun readFile(path: String): String? {
        return try { java.io.File(path).readText(Charsets.UTF_8) } catch (e: Exception) { Log.e(TAG, "readFile fail $path", e); null }
    }
    private fun readBytes(path: String): ByteArray? {
        return try { java.io.File(path).readBytes() } catch (e: Exception) { Log.e(TAG, "readBytes fail $path", e); null }
    }
    private fun bumpId() { nextId++ }
}