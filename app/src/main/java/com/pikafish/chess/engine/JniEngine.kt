package com.pikafish.chess.engine

import android.content.Context
import android.util.Log
import com.pikafish.chess.heavy.HeavyStore
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.Move
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock

/**
 * 皮卡鱼 UCI 引擎 — 子进程方案
 *
 * 弈思象棋教练实际使用的就是这种方案（不是 JNI）：
 *  1. 引擎编译为独立可执行文件（libpikafish_bin.so，实际是 ELF executable）
 *  2. 通过 ProcessBuilder 启动子进程
 *  3. 用 UCI 协议通信（stdin/stdout）
 *  4. 使用 go movetime N 实现按秒计时（简单可靠）
 *
 * 相比 JNI 方案的优势：
 *  - 子进程崩溃不会带崩主进程
 *  - 无 NNUE 版本兼容问题（引擎自带 NNUE 加载逻辑）
 *  - UCI 协议成熟稳定
 *
 * 类名保持 JniEngine 不变，避免改动 ChessGame 和 MainActivity
 */
class JniEngine(private val context: Context) {
    companion object {
        private const val TAG = "PikafishEngine"

        const val ENGINE_LABEL = "UCI-2026"
        const val NNUE_ASSET_NAME = "pikafish.nnue"
        const val NNUE_LOCAL_NAME = "pikafish.nnue"
        private const val BINARY_NAME = "libpikafish_bin.so"

        // ★ 档位：默认 4 线程 / 256MB（双引擎共用下限，追求速度与棋感）
        //   MAX_THREADS=8、MAX_HASH=512：上限同样由界面/内存自适应保护控制。
        const val DEFAULT_THREADS = 4
        const val DEFAULT_HASH = 256
        const val MAX_THREADS = 8
        const val MAX_HASH = 512

        // ★ 兼容旧档位键：保留定义避免引用缺失，实际不再作为上限使用
        const val DEFAULT_THREADS_LIGHT = 1
        const val DEFAULT_HASH_LIGHT = 32
        const val MAX_THREADS_LIGHT = 2
        const val MAX_HASH_LIGHT = 128

        private const val EVAL_MOVETIME_MS = 3000

        // ★ 复盘评估专用预算：这类"逐点单局面"评估不需要像对战那么快，
        //   给足 5s+ 让 go depth 12 在复杂局面也能稳稳跑满深度、评分更准更稳。
        private const val EVAL_REVIEW_MOVETIME_MS = 5000

        // ═══════════════════════ 连杀分编码约定 ═══════════════════════
        // 引擎 UCI 返回 "score mate N"（N=到将杀的着法数，正=走子方成杀，负=被将死）。
        // 我们不把它放大成 N×10000 的"几万分/几十万分"（那正是"评分拉胯"的根源），
        // 而是统一收敛成【29999 封顶】的单调分值：真正将死/下一手成杀 = ±29999，
        // 离杀棋越远，数值越小（每远 1 着约低 2 分），始终落在 299xx 区间。
        // 这样：① 显示不会炸出十几万的裸分；② 复盘"漏着"判据里 7步杀 vs 6步杀
        //    只是 ~2 分的"快慢差"，不再被误判成 10000 分巨损，hence 不再乱提"车X平X"建议。
        const val MATE_CEIL = 29999
        /** 超过该幅度即视为进入"连杀/必胜"区间（普通子力分值在几千以内，到不了这里）。 */
        const val MATE_THRESHOLD = 9000

        /** 把引擎的 mate N 转成 29999 封顶编码分。 */
        fun encodeMate(pliesPositive: Int): Int {
            // ★ mate 0 = 轮到走棋的这一方已被将死（无着可走）→ 对走棋方就是"被杀"，必须为负。
            //   （旧版按 sign(0)>=0 判成正的 +29999，导致"走后被将死"的局面被当成"己方要大胜"，
            //    两搜相减就成了"损失了59998分"这种荒唐数 —— 正是前言不搭后语的根源之一。）
            if (pliesPositive == 0) return -MATE_CEIL
            val plies = kotlin.math.abs(pliesPositive)
            val mag = if (plies <= 1) MATE_CEIL
                      else (MATE_CEIL - 2 * (plies - 1)).coerceAtLeast(MATE_THRESHOLD)
            return if (pliesPositive > 0) mag else -mag
        }

        /** 是否处于连杀/必胜区间。 */
        fun isMateScore(score: Int): Boolean = kotlin.math.abs(score) >= MATE_THRESHOLD

        /** 由已编码分反推成杀着法数(往返)；非连杀区间返回 null。 */
        fun matePliesOf(score: Int): Int? {
            val mag = kotlin.math.abs(score)
            if (mag < MATE_THRESHOLD) return null
            return ((MATE_CEIL - mag) / 2 + 1).coerceAtLeast(1)
        }

        /** 统一显示助手：由编码分渲染“杀N步/被杀N步”；非连杀区间返回 null（调用方显示裸分/优劣）。 */
        fun mateText(score: Int): String? {
            if (kotlin.math.abs(score) < MATE_THRESHOLD) return null
            val plies = matePliesOf(score) ?: 1
            return if (score > 0) "杀${plies}步" else "被杀${plies}步"
        }
    }

    @Volatile var hashSize: Int = DEFAULT_HASH
    @Volatile var threadCount: Int = DEFAULT_THREADS

    /** ★ PK 比赛专用：强制指定的引擎二进制（不为 null 则优先于「采用」的引擎，供红/黑各用一台） */
    @Volatile var forcedEngineFile: File? = null
    /** ★ PK 比赛专用：强制指定的 NNUE 权重（不为 null 则优先于「采用」的引擎权重） */
    @Volatile var forcedNnueFile: File? = null
    /** ★ PK 比赛专用：隔离模式——只用本实例显式指定的引擎/权重，未指定则用 App 内置默认，
     *  绝不回退到「采用」的全局外置引擎。保证红黑两台各自独立、互不串台。 */
    @Volatile var isolated: Boolean = false

    // ★★★★★ 「异地引擎」：不为 null 时，本实例不再起本地子进程，而是通过 TCP 连到
    //   配对好的辎重包端（电脑 / 另一台手机），由那台设备上的引擎替本机算棋。
    //   本机因此不加载权重、不跑引擎 —— 明显不发热。格式 "host:port"。
    //   对上层的调用方式与本地引擎完全一致（同样说 UCI），所以主对弈逻辑零改动。
    @Volatile var remoteEndpoint: String? = null
    @Volatile private var socket: Socket? = null

    // ★★★ 本实例引擎自报的身份（UCI 握手里的 "id name / id author"）★★★
    //   PK 时红黑两台各记各的（绝不都往全局 EngineIdentity 里挤），
    //   挡屏「红方 X VS 黑方 Y」的对阵档案直接读这里，内置那台也能拿到真实构建日期。
    @Volatile var selfIdName: String = ""
        private set
    @Volatile var selfAuthor: String = ""
        private set

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var isReady = false
    private var enginePath: String = ""
    private var nnuePath: String = ""

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var searchJob: Job? = null
    // ★★★ 预想协程与正式搜索协程彻底分家：原先共用 searchJob 句柄，
    //   任何一处 stopPonder()（切屏/翻谱/新局等十余处调用）都会误杀正在跑的
    //   正式搜索 → onResult(null) → 重试链条 → 卡死。分家后 stopPonder 只动预想，
    //   正式搜索只能被它自己的收尾逻辑结束，绝不误伤。
    private var ponderJob: Job? = null
    private var evalJob: Job? = null

    // ── Ponder（后台思考）状态 ──
    // ★ 单引擎模型：后台思考和正常搜索共用一个引擎、一把锁（searchLock）
    //   同一时间只能做一件事：要么在正常搜索，要么在后台思考，要么空闲。
    //   这样避免了 outputQueue 数据交错、状态混乱导致的卡顿。
    @Volatile private var isPondering = false
    @Volatile private var ponderBestMove = ""   // 后台思考搜到的最佳走法
    @Volatile private var ponderPv = ""          // 后台思考的PV
    @Volatile private var ponderScore = 0        // 后台思考的评分
    @Volatile private var ponderDepth = 0        // 后台思考搜到的深度
    // ★ 后台多线预想：备份当前"人脑待走"局面起搜，用 MultiPV 同时推出多条
    //   人脑可能走法及各自的应着。按下标(multipv 序号)保存各线进度。
    private val ponderLinesMap = HashMap<Int, PonderLine>()  // 访问都用 synchronized(ponderLinesMap) 保护
    @Volatile var ponderMultiPv: Int = 3         // 后台预想线路数（①预测 + ②③其它可能走法）
    // 后台思考信息回调（节流，深度增长时触发；lines 为多线预案）
    var onPonderInfo: ((depth: Int, score: Int, pv: String, lines: List<PonderLine>) -> Unit)? = null

    // ★ 全流程串行锁：引擎是单进程（单 writer + 单 outputQueue），
    //   任何搜索（主走棋/后台思考/评价/假设分析）都必须互斥执行。
    //   否则 command/response 在多个协程间交叉污染，导致"拿到别的局面的 bestmove"或超时乱走。
    private val searchLock = ReentrantLock()

    @Volatile private var isSearching = false

    // 独立线程读取 stdout，避免死锁
    // bestmove 用 put() 阻塞写入，绝不会丢；普通 info 行用 offer()，队列满了就丢弃（不影响正确性）
    private val outputQueue = LinkedBlockingQueue<String>(10000)
    private var readerThread: Thread? = null

    // ★★★★★ 读线程存活标记：读线程一旦异常退出/流关闭，引擎就再也读不到结果，
    //   若 isReady 仍为 true，搜索会永久"卡死"等待。标记 false → isEngineReady() 返回 false
    //   → Watchdog 巡检发现并重启引擎，闭环自愈。
    @Volatile private var readerAlive = false

    var onEngineRestarted: (() -> Unit)? = null

    // ── NNUE 初始化 ──

    fun copyCurrentNNue(): String? {
        // ★ 异地引擎：权重在辎重包端加载，本机一字节都不碰（这正是"本机不发热"的关键之一）
        if (remoteEndpoint != null) {
            nnuePath = ""
            return null
        }
        return try {
            // ★ PK 强制权重优先（红/黑各用一台时，各自指定自己那台的权重）
            forcedNnueFile?.let { ext ->
                if (ext.isFile && ext.length() >= 1_000_000L) {
                    nnuePath = ext.absolutePath
                    EngineIdentity.weightFileName = ext.name
                    EngineIdentity.weightPath = ext.absolutePath
                    EngineIdentity.weightSize = ext.length()
                    Log.i(TAG, "PK 强制权重: ${ext.absolutePath} (${ext.length()}B)")
                    return null
                }
            }
            // ★★ 全局优先：用户装了外置引擎包 → 用它自带的 NNUE 权重（主对弈/解惑/复盘…全走这里）
            //   隔离模式（PK）跳过这层，未显式指定权重时直接用 App 内置权重，绝不借用"采用"那台。
            if (!isolated) UserEngineStore.nnueFile(context)?.let { ext ->
                nnuePath = ext.absolutePath
                EngineIdentity.weightFileName = ext.name
                EngineIdentity.weightPath = ext.absolutePath
                EngineIdentity.weightSize = ext.length()
                Log.i(TAG, "使用外置权重: ${ext.absolutePath} (${ext.length()}B)")
                return null
            }
            // ★ 回退：App 内置的官方 NNUE 权重（稳定、确定、单一来源）
            val nnueFile = File(context.filesDir, NNUE_LOCAL_NAME)
            if (!nnueFile.exists() || nnueFile.length() < 10_000_000L) {
                Log.d(TAG, "Copying NNUE: $NNUE_ASSET_NAME")
                // ★ 权重优先取自辎重包，读不到再回退内置 assets。
                val input = HeavyStore.openLocalFirst(context, NNUE_ASSET_NAME)
                val output = FileOutputStream(nnueFile)
                val buffer = ByteArray(65536)
                var total = 0L
                var n: Int
                while (input.read(buffer).also { n = it } != -1) {
                    output.write(buffer, 0, n)
                    total += n
                }
                output.flush(); output.fd.sync(); output.close(); input.close()
                Log.d(TAG, "NNUE copied: $total bytes")
            }
            nnuePath = nnueFile.absolutePath
            EngineIdentity.weightFileName = nnueFile.name
            EngineIdentity.weightPath = nnueFile.absolutePath
            EngineIdentity.weightSize = nnueFile.length()
            val size = nnueFile.length()
            if (size < 1_000_000) return "NNUE 文件异常（${size} 字节）"
            null
        } catch (e: Exception) {
            Log.e(TAG, "copyCurrentNNue failed", e)
            "NNUE 复制失败: ${e.message}"
        }
    }

    // ── 引擎生命周期 ──

    /**
     * 定位引擎二进制文件
     *
     * 必须从 nativeLibraryDir 执行（该目录有执行权限）。
     * 不能复制到 filesDir — Android 10+ 的 W^X 策略禁止从 filesDir 执行二进制文件（Permission denied）。
     */
    fun locateEngine(): String? {
        // ★ 异地引擎：引擎二进制在辎重包端，本机无需定位、无需可执行文件
        if (remoteEndpoint != null) {
            enginePath = ""
            return null
        }
        return try {
            // ★ PK 强制引擎优先（红/黑各用一台时，强制走指定二进制）
            forcedEngineFile?.let { ext ->
                if (ext.isFile) {
                    if (!ext.canExecute()) ext.setExecutable(true, false)
                    enginePath = ext.absolutePath
                    EngineIdentity.engineFileName = ext.name
                    EngineIdentity.enginePath = ext.absolutePath
                    EngineIdentity.engineSize = ext.length()
                    Log.i(TAG, "PK 强制引擎: ${ext.absolutePath} (${ext.length()}B)")
                    return null
                }
            }
            // ★★ 全局优先：用户外置引擎（装了就用它，主对弈/解惑/复盘/打谱全生效）
            //   隔离模式（PK）跳过这层，未显式指定二进制时直接用 App 内置皮卡鱼，绝不借用"采用"那台。
            if (!isolated) UserEngineStore.engineFile(context)?.let { ext ->
                if (!ext.canExecute()) ext.setExecutable(true, false)
                enginePath = ext.absolutePath
                EngineIdentity.engineFileName = ext.name
                EngineIdentity.enginePath = ext.absolutePath
                EngineIdentity.engineSize = ext.length()
                EngineIdentity.external = true
                Log.i(TAG, "使用外置引擎: ${ext.absolutePath} (${ext.length()}B)")
                return null
            }
            // ★ 回退：nativeLibraryDir 里的内置官方引擎二进制（该目录保证可执行，规避 W^X）
            val bin = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
            if (!bin.exists()) return "引擎文件未找到: ${bin.absolutePath}"
            Log.d(TAG, "Engine binary: ${bin.absolutePath} (${bin.length()} bytes), canExecute=${bin.canExecute()}")
            if (!bin.canExecute()) {
                bin.setExecutable(true, false)
            }
            enginePath = bin.absolutePath
            EngineIdentity.engineFileName = bin.name
            EngineIdentity.enginePath = bin.absolutePath
            EngineIdentity.engineSize = bin.length()
            EngineIdentity.external = false
            null
        } catch (e: Exception) {
            Log.e(TAG, "locateEngine failed", e)
            "引擎定位失败: ${e.message}"
        }
    }

    /**
     * 启动引擎子进程
     */
    fun startEngine(): String? {
        // ★ 异地引擎：不是起本地进程，而是连到辎重包端的引擎端口（那边替本机算棋）
        remoteEndpoint?.let { return startRemote(it) }
        // ★ 本地档：确认身份不是"异地"，否则回退后顶栏还会挂着「（异地）」。
        EngineIdentity.remote = false
        val bundledPath = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME).absolutePath
        var chosen = enginePath.ifBlank { bundledPath }
        for (attempt in 1..2) {
            try {
                Log.d(TAG, "Starting engine(attempt $attempt): $chosen")
                val pb = ProcessBuilder(chosen)
                pb.directory(context.filesDir)
                pb.redirectErrorStream(true)
                pb.environment()["HOME"] = context.filesDir.absolutePath
                process = pb.start()
                writer = BufferedWriter(OutputStreamWriter(process!!.outputStream, Charsets.UTF_8))
                reader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))

                // 启动独立线程读取 stdout
                startReaderThread()
                enginePath = chosen
                // ★ 诚实登记实际跑起来的引擎（外置启动失败会自动回退内置，这里据实标注）
                try {
                    val cf = File(chosen)
                    EngineIdentity.engineFileName = cf.name
                    EngineIdentity.enginePath = chosen
                    EngineIdentity.engineSize = cf.length()
                    EngineIdentity.external = chosen != bundledPath
                } catch (_: Exception) {}
                return null
            } catch (e: Exception) {
                Log.e(TAG, "startEngine failed(attempt $attempt): $chosen", e)
                // ★ 若是懒人外置引擎无法执行（Android 10+ 对用户目录 W^X 禁止 exec），
                //   自动回退到内置官方引擎重试一次，绝不因此让 App 启动失败。
                if (attempt == 1 && chosen != bundledPath) { chosen = bundledPath; continue }
                return "引擎启动失败: ${e.message}"
            }
        }
        return "引擎启动失败: 未知错误"
    }

    /**
     * ★ 异地引擎：连到辎重包端的引擎端口，把本地子进程的 stdin/stdout 换成 TCP 套接字流。
     * 其余（reader 线程、UCI 收发、搜索纪律）与本地子进程方案完全一致。
     */
    private fun startRemote(endpoint: String): String? {
        try {
            val colon = endpoint.lastIndexOf(':')
            if (colon <= 0) return "异地引擎地址无效：$endpoint"
            val host = endpoint.substring(0, colon)
            val port = endpoint.substring(colon + 1).trim().toIntOrNull()
                ?: return "异地引擎端口无效：$endpoint"
            Log.i(TAG, "连接异地引擎 $host:$port …")
            val s = Socket()
            s.tcpNoDelay = true
            s.soTimeout = 0
            s.connect(InetSocketAddress(host, port), 8000)
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            startReaderThread()
            enginePath = "remote://$endpoint"
            // ★ 据实登记：算棋的家伙在电脑（辎重包端）那边，本机只是遥控。
            //   顶栏/弹窗据此额外标出「电脑」二字，让人明白无误地知道电脑引擎已接通。
            EngineIdentity.remote = true
            EngineIdentity.external = false
            EngineIdentity.engineFileName = "电脑端引擎"
            EngineIdentity.engineSize = 0L
            EngineIdentity.weightFileName = ""
            EngineIdentity.weightSize = 0L
            Log.i(TAG, "★ 异地引擎已连上：$endpoint")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "异地引擎连接失败: $endpoint", e)
            socket = null
            return "连不上异地引擎（$endpoint）：${e.message}"
        }
    }

    /**
     * 轻量初始化引擎 — UCI 握手 + 配置，跳过验证搜索（省时省内存）。
     * 仅供"独立解惑沙盒"这类旁路用途：沙盒本身用 doUciSearch/evaluateMove，
     * 自带超时兜底，不需要 fullInit 里那次深度5的验证搜索。
     * 注意：必须与 locateEngine()/copyCurrentNNue()/startEngine() 配套调用。
     */
    fun liteInit(): String? {
        return try {
            sendCommand("uci")
            val uciLines = waitForLine("uciok", 10000)
            if (uciLines == null) return "引擎无响应（uciok 超时）"
            // ★ 本实例身份：先各记各的，再吸收入全局（顶栏/解说用全局那份）
            EngineIdentity.parseUciIdentity(uciLines).let { (sid, sau) ->
                if (sid.isNotBlank()) selfIdName = sid
                if (sau.isNotBlank()) selfAuthor = sau
            }
            // ★ 吸收引擎自报的真实身份（id name / id author）——顶栏/解说/弹窗都以它为准
            EngineIdentity.absorbUci(uciLines)

            // ★ 异地引擎：权重由辎重包端自行加载，本机不发送 EvalFile（本机并无该文件）
            if (remoteEndpoint == null) sendCommand("setoption name EvalFile value $nnuePath")
            sendCommand("setoption name Threads value $threadCount")
            sendCommand("setoption name Hash value $hashSize")
            sendCommand("setoption name MultiPV value 1")

            sendCommand("isready")
            // ★ 异地引擎首次连接时，辎重包端可能在加载权重（数秒~十几秒）。把 readyok 的
            //   等待放宽到 30 秒，避免"权重还在加载就被判超时→询招空手→必走子保险乱走一着"。
            //   只是上限放宽，引擎已就绪时依旧瞬时返回，不拖慢正常情形。
            if (waitForLine("readyok", 30000) == null) return "引擎就绪超时（readyok）"

            isReady = true
            Log.d(TAG, "Engine lite-ready! ($ENGINE_LABEL, threads=$threadCount, hash=$hashSize)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "liteInit error", e)
            "初始化异常: ${e.message}"
        }
    }

    /**
     * 初始化引擎 — UCI 握手 + 配置 + 验证
     */
    fun fullInit(): String? {
        return try {
            // UCI 握手
            sendCommand("uci")
            val uciLines = waitForLine("uciok", 10000)
            if (uciLines == null) return "引擎无响应（uciok 超时）"
            // ★ 本实例身份：先各记各的，再吸收入全局（顶栏/解说用全局那份）
            EngineIdentity.parseUciIdentity(uciLines).let { (sid, sau) ->
                if (sid.isNotBlank()) selfIdName = sid
                if (sau.isNotBlank()) selfAuthor = sau
            }
            // ★ 吸收引擎自报的真实身份（id name / id author）——顶栏/解说/弹窗都以它为准
            EngineIdentity.absorbUci(uciLines)

            // 设置 NNUE
            // ★ 异地引擎：权重由辎重包端自行加载，本机不发送 EvalFile（本机并无该文件）
            if (remoteEndpoint == null) sendCommand("setoption name EvalFile value $nnuePath")
            sendCommand("setoption name Threads value $threadCount")
            sendCommand("setoption name Hash value $hashSize")
            sendCommand("setoption name MultiPV value 1")

            // isready
            sendCommand("isready")
            // ★ 异地引擎首次连接时，辎重包端可能在加载权重（数秒~十几秒）。把 readyok 的
            //   等待放宽到 30 秒，避免"权重还在加载就被判超时→询招空手→必走子保险乱走一着"。
            //   只是上限放宽，引擎已就绪时依旧瞬时返回，不拖慢正常情形。
            if (waitForLine("readyok", 30000) == null) return "引擎就绪超时（readyok）"

            // ★ 验证搜索：测试 NNUE 是否正确加载
            Log.d(TAG, "★ 验证搜索 (depth=5)...")
            sendCommand("position fen rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1")
            sendCommand("go depth 5")
            val testLines = waitForLine("bestmove", 15000)
            if (testLines == null) return "引擎测试搜索超时"

            var testNodes = 0L
            var testBestmove = ""
            for (line in testLines) {
                val nm = Regex("nodes (\\d+)").find(line)
                if (nm != null) testNodes = nm.groupValues[1].toLong()
                if (line.startsWith("bestmove")) {
                    val parts = line.split(" ")
                    if (parts.size >= 2) testBestmove = parts[1]
                }
            }
            Log.d(TAG, "★ 测试搜索: bestmove=$testBestmove nodes=$testNodes")

            if (testBestmove.isEmpty() || testBestmove == "(none)") {
                return "引擎测试搜索未返回走法"
            }
            if (testNodes < 5000) {
                Log.w(TAG, "★ NNUE 可能未正确加载: nodes=$testNodes")
            }

            isReady = true
            Log.d(TAG, "Engine ready! ($ENGINE_LABEL, threads=$threadCount, hash=$hashSize)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "fullInit error", e)
            "初始化异常: ${e.message}"
        }
    }

    // ── 运行时更新 ──

    fun updateSettings(hash: Int? = null, threads: Int? = null) {
        if (hash != null) hashSize = hash.coerceIn(16, MAX_HASH)
        if (threads != null) threadCount = threads.coerceIn(1, MAX_THREADS)

        if (isReady) {
            sendCommand("setoption name Threads value $threadCount")
            sendCommand("setoption name Hash value $hashSize")
            sendCommand("isready")
            waitForLine("readyok", 5000)
            Log.d(TAG, "Settings updated: threads=$threadCount, hash=$hashSize")
        }
    }

    // ── 状态查询 ──

    fun isEngineReady(): Boolean {
        if (!isReady) return false
        // ★ 读线程已退出（异常/流关闭）→ 引擎读不到结果，视为异常，交给 Watchdog 重启
        if (!readerAlive) {
            Log.w(TAG, "isEngineReady: 读线程已退出，标记引擎异常")
            isReady = false
            return false
        }
        // ★ 异地引擎：以套接字存活为准（本机没有引擎进程）
        val s = socket
        if (s != null) {
            if (!s.isConnected || s.isClosed) {
                isReady = false
                return false
            }
            return true
        }
        // 检查进程是否还活着
        val p = process ?: return false
        try {
            p.exitValue()
            // 进程已退出
            isReady = false
            return false
        } catch (_: IllegalThreadStateException) {
            // 进程仍在运行
            return true
        }
    }

    fun isProcessAlive(): Boolean = isEngineReady()
    fun isReaderRunning(): Boolean = isReady
    fun isSearching(): Boolean = isSearching

    /**
     * ★★★ 双AI空档期优化：引擎是否"已知空闲"（当前没有在途搜索、也没有后台思考）。
     *   为 true 时上层可跳过 stopAndWaitForIdle 握手直接发 position+go——
     *   双 AI 交接时"即将走棋"的引擎上一轮早已 bestmove 收尾、本就空闲，
     *   跳过握手把空档期压到毫秒级（"这个走完，那个砰的一下就走"）。
     */
    fun isKnownIdle(): Boolean = !isSearching && !isPondering

    fun getLastPonderMoveUci(): String? = null
    fun isPonderingOnPrediction(): Boolean = false

    // ── 对局控制 ──

    /**
     * MultiPV 多线同步思考用：一条主变（该候选着法的评分与后续变化）。
     * 引擎开启 MultiPV=N 后，会同时搜多条候选线，逐层齐头并进，界面同步展示。
     */
    data class PvLine(val score: Int, val pv: String)

    /**
     * 后台多线预想（MultiPV Ponder）用：一条候选预案。
     * 每条线从"人脑待走"的局面起搜，所以：
     *  - pv[0] = 预测人脑可能走的这一步
     *  - pv[1] = 相对于该步，引擎预演好的【应着】（人脑真走了 pv[0] 就秒出 pv[1]）
     *  - pv[2..] = 后续变化
     */
    data class PonderLine(val depth: Int, val score: Int, val pv: String) {
        /** 预测的人脑走法（UCI） */
        val predictedMove: String get() = pv.split(" ").firstOrNull { it.isNotEmpty() } ?: ""
        /** 引擎预演好的应着（UCI） */
        val responseMove: String get() = pv.split(" ").getOrNull(1) ?: ""
    }

    fun newGame() {
        if (isReady) sendCommand("ucinewgame")
    }

    /**
     * 搜索最佳走法（按秒计时）
     */
    fun searchBestMove(
        board: Board,
        timeMs: Long? = null,
        depth: Int? = null,
        onThinking: ((depth: Int, selDepth: Int, score: Int, nodes: Long, nps: Long, pv: String, pvLines: List<PvLine>) -> Unit)? = null,
        onResult: (Move?) -> Unit,
        multiPv: Int = 1
    ) {
        searchBestMoveExcluding(board, emptySet(), timeMs, depth, onThinking, onResult, restrictToUci = null, multiPv = multiPv)
    }

    /**
     * 搜索最佳走法，可排除指定走法；可选 restrictToUci 限定只在给定候选集内搜索。
     *
     * - excludedUci：排除这些走法（从全部合法走法里剔除后搜索）
     * - restrictToUci：非空时，改为「只在这几个候选着法里搜索并由引擎汇总选最优」
     *   （多源平行汇审用：把云库 + 开局库扒出来的几招一次性searchmoves，引擎汇总得出最终结果）。
     *   二者同时给出时 restrictToUci 优先。
     *
     * 支持两种模式（二选一，depth 优先）：
     *   - timeMs：按时间搜索（go movetime N），到点必须走
     *   - depth：按深度搜索（go depth N），到层必须走
     *
     * ★ 严格限时：到了时间/深度，引擎必须立即返回最优步。
     *   加了硬超时保护，防止引擎 movetime 不准时。
     */
    fun searchBestMoveExcluding(
        board: Board,
        excludedUci: Set<String>,
        timeMs: Long? = null,
        depth: Int? = null,
        onThinking: ((depth: Int, selDepth: Int, score: Int, nodes: Long, nps: Long, pv: String, pvLines: List<PvLine>) -> Unit)? = null,
        onResult: (Move?) -> Unit,
        restrictToUci: Set<String>? = null,
        multiPv: Int = 1
    ) {
        if (!isReady) {
            Log.e(TAG, "searchBestMove: engine not ready!")
            onResult(null)
            return
        }

        // ★ 模式判断：depth 优先（指定了 depth 就用深度模式，否则用时间模式）
        val useDepthMode = depth != null && depth > 0
        val actualDepth = if (useDepthMode) depth!!.coerceIn(1, 30) else 0
        val actualTimeMs = if (useDepthMode) {
            // ★★★ 单时钟收敛：深度模式的唯一预算就这里。
            //   ★ 预算收紧（v2.9.22）：目标层×400ms，软下界 1.5s / 硬上界 8s。
            //     - 旧版 ×800ms 封顶 20s：手机端 654K nps 下深度 20+ 根本算不满目标层，
            //       每步硬等 8~20s（"设多少层都麻 / 设步时也麻"的根因之一）；
            //     - 收紧后每步最多 8s，配合杀棋提前收招 + 对弈单线搜索，出招"砰的一下"。
            //   - 简单局面目标层 17 霎时跑完直接 bestmove 返回；
            //   - 复杂局面预算内滚到目标层 → 引擎到层自然返回；滚不到 → movetime 双保险
            //     按时 stop，用已搜出的 PV 兜底，绝不无限等。
            (actualDepth * 400L).coerceIn(1500L, 8000L)
        } else {
            (timeMs ?: 5000L).toInt().coerceAtLeast(100).toLong()
        }

        // ★ 硬超时时间：时间模式 = 设置时间 + 200ms缓冲；深度模式 = 兜底超时
        val hardStopAt = System.currentTimeMillis() + actualTimeMs + if (useDepthMode) 0 else 200L
        val deadline = hardStopAt + 500L  // 收 stop 后的 bestmove 的时间

        // ★ 克隆棋盘，避免数据竞争
        val boardCopy = board.clone()
        val fen = boardCopy.toFen()
        val player = boardCopy.currentPlayer

        // 计算 searchMoves（排除指定走法；若给定了 restrictToUci 则改为只在这几个候选里搜）
        val searchMovesStr = try {
            val legalUci = boardCopy.getLegalMoves(player).map { it.toUci() }
            val moves: List<String> = if (restrictToUci != null) {
                // ★ 多源汇审：候选集 ∩ 合法走法 = 只搜索这几个着法，引擎在它们之间汇总选最优
                legalUci.filter { it in restrictToUci }
            } else {
                legalUci.filter { it !in excludedUci }
            }
            if (moves.isEmpty()) "" else "searchmoves ${moves.joinToString(" ")}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute searchMoves", e)
            ""
        }

        // 预计算合法走法列表（用于验证）
        val legalMovesForValidation: List<Move> = try {
            boardCopy.getLegalMoves(player)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get legal moves", e)
            emptyList()
        }

        Log.i(TAG, "★★★ doSearch: fen=$fen, movetime=${actualTimeMs}ms, excluded=$excludedUci, sideToMove=$player")

        isSearching = true
        // ★★★ 单飞栅栏：新搜索先取消旧协程，再等待锁释放，杜绝并发搜索导致的
        //    outputQueue 互清/读串线程崩溃（"点催就崩"的根因之一）
        try {
            searchJob?.cancel()
        } catch (_: Exception) {}
        // ★★★ 参考 P象棋 1.5.2 _stopEngineBeforeBoardMutation：
        //    在启动新搜索前，先强制停止引擎并通过 isready→readyok 握手确认引擎已空闲。
        //    原来的 fire-and-forget stop 不等确认就继续，双 AI 对弈时引擎还没停稳就
        //    收到新 position+go → 命令交错 → 引擎状态混乱 → 走得慢/卡死。
        //    ★ 注意：先取消旧协程 + 发 stop 让引擎开始停手（非阻塞），
        //    真正的 stopAndWaitForIdle 放在协程内、获锁之后执行（避免阻塞主线程）。
        try { sendCommand("stop") } catch (_: Exception) {}
        searchJob = scope.launch {
            var locked = false
            try {
                locked = searchLock.tryLock(3, TimeUnit.SECONDS)
            } catch (_: Exception) {
                locked = false
            }
            if (!locked) {
                try {
                    sendCommand("stop")
                } catch (_: Exception) {}
                withContext(Dispatchers.Main) { onResult(null) }
                return@launch
            }
            try {
                try {
                // ★★★ 获锁成功 = 旧协程的 finally 块已执行完毕（锁已释放），
                //   此时引擎管道里不会再有新命令。现在安全地执行"停止+等待空闲"：
                //   stop → isready → readyok，确保引擎完全停稳后再发新命令。
                //   ★ 这是参考 P象棋 1.5.2 _stopEngineBeforeBoardMutation 的核心修复。
                // ★★★ 双AI空档期优化：若引擎已"已知空闲"（无在途搜索、无后台思考），
                //   上一轮 bestmove 早已收尾，直接跳过 1.5s 握手 → 空档期压到毫秒级。
                //   仅在引擎可能仍在忙（isSearching 仍 true / 后台思考中）时才握手。
                if (!isKnownIdle()) {
                    stopAndWaitForIdle(1500)
                }

                // ★★★ 快速排空：stopAndWaitForIdle 已经消费了旧搜索的 bestmove+readyok，
                //   这里只需清空队列中可能残留的零散 info 行（通常 0~2 条）。
                //   ★ 优化：不再固定等 300ms，改为"有就清、没有就走"，双 AI 每步省 300ms。
                while (outputQueue.poll(10, TimeUnit.MILLISECONDS) != null) {
                    // 快速排空残留行
                }

                // ★★ MultiPV：多线同步思考。开启后引擎同时搜多条候选线，逐层齐头并进，
                //   界面把每条线的深度/评分同步展示。思考完由上层按主变(multipv 1)落子汇总。
                val mpv = multiPv.coerceIn(1, 5)
                try { sendCommand("setoption name MultiPV value $mpv") } catch (_: Exception) {}
                // ★★★★★ 【性能优化】每步搜索不再强制 ucinewgame 清空哈希表。
                //   哈希置换表是所有局面共用的缓存：zobrist key 区分局面，position fen
                //   已完整描述局面，哈希并不会"污染"。真正会越算越慢的根因恰恰是每步
                //   清空 → 每步从零搜索、残局越走越卡（本机双引擎+53MB权重下尤其致命）。
                //   → 全程保留哈希积累，中残局搜索大幅提速，与 startPonder 的取舍保持一致。
                //   整局开始时（newGame()）已经统一发过一次 ucinewgame 完成初始复位。
                // 发送搜索命令
                sendCommand("position fen $fen")
                val goCmd = if (useDepthMode) {
                    // ★ 深度模式：go depth N movetime M —— 层数/时间双保险，先到先停。
                    //   到层自然返回；到预算（手机端算不满目标层）也按时 stop 兜底，
                    //   杜绝"go depth N 单发"时引擎在复杂局面长拖不落子（用户麻在等）。
                    if (searchMovesStr.isNotEmpty()) {
                        "go depth $actualDepth movetime $actualTimeMs $searchMovesStr"
                    } else {
                        "go depth $actualDepth movetime $actualTimeMs"
                    }
                } else {
                    // ★ 时间模式：go movetime N
                    if (searchMovesStr.isNotEmpty()) {
                        "go movetime $actualTimeMs $searchMovesStr"
                    } else {
                        "go movetime $actualTimeMs"
                    }
                }
                Log.i(TAG, "GO_CMD: $goCmd")
                sendCommand(goCmd)

                // 读取输出，直到收到 bestmove
                var bestMoveUci = ""
                var score = 0
                var searchDepth = 0
                var selDepth = 0
                var nodes = 0L
                var nps = 0L
                var pv = ""
                // MultiPV 各条主变（下标即 multipv 序号，multipv 1 为最优）
                val pvLinesArr = arrayOfNulls<PvLine>(6)

                // ★★★ 思考回调节流：引擎 info 行非常密集（每 10~50ms 一条），
                //   若每条都 withContext(Main) 更新 UI，主线程被洪泛打满，
                //   bestmove 结果回调会排在思考更新后面迟迟不执行 →
                //   "2秒一步却想很久 / 等老半天不走 / 切屏回来才走"。
                //   节流到每 100ms 最多回调一次（10次/秒足够UI显示）。
                var lastThinkingAtMs = 0L

                // ★★★ 严格限时 + 硬超时检测
                // ★★★ 单时钟收敛：深度模式下"到点"就是硬上界，stop 后给引擎 300ms 收尾
                //   （引擎收到 stop 后会在数十ms内吐出 bestmove）；时间模式保持 500ms 收尾。
                var hardStopped = false
                val tailWindowMs = if (useDepthMode) 300L else 500L
                val actualDeadline = if (useDepthMode) {
                    // 深度模式：一旦硬超时发出 stop，不再死等整个 500ms 收尾窗口，
                    //   300ms 内没收到 bestmove 就用已搜出的 PV 兜底（绝不无限等）。
                    System.currentTimeMillis() + actualTimeMs + tailWindowMs
                } else {
                    deadline
                }

                while (System.currentTimeMillis() < actualDeadline) {
                    // ★ 硬超时检测：到点还没出结果就强制 stop
                    if (!hardStopped && System.currentTimeMillis() >= hardStopAt && bestMoveUci.isEmpty()) {
                        val modeStr = if (useDepthMode) "depth=$actualDepth" else "time=${actualTimeMs}ms"
                        Log.w(TAG, "★ 硬超时！强制 stop（$modeStr）")
                        try { sendCommand("stop") } catch (_: Exception) {}
                        hardStopped = true
                    }

                    val line = outputQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    // ★ 不打印每条 info 行：info 行太密集，频繁打日志会拖慢性能、造成卡顿。
                    //   bestmove 等关键行会在后面单独打日志。

                    if (line.startsWith("info")) {
                        // 解析思考信息
                        val depthMatch = Regex(" depth (\\d+)").find(line)
                        val selDepthMatch = Regex(" seldepth (\\d+)").find(line)
                        val scoreCpMatch = Regex("score cp (-?\\d+)").find(line)
                        val scoreMateMatch = Regex("score mate (-?\\d+)").find(line)
                        val nodesMatch = Regex("nodes (\\d+)").find(line)
                        val npsMatch = Regex("nps (\\d+)").find(line)
                        val pvMatch = Regex(" pv (.+)$").find(line)
                        val multipvMatch = Regex(" multipv (\\d+)").find(line)
                        val multiIdx = multipvMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                        depthMatch?.let { searchDepth = it.groupValues[1].toInt() }
                        selDepthMatch?.let { selDepth = it.groupValues[1].toInt() }
                        nodesMatch?.let { nodes = it.groupValues[1].toLong() }
                        npsMatch?.let { nps = it.groupValues[1].toLong() }

                        // 本行评分（cp 或 mate）
                        var lineScore = score
                        scoreCpMatch?.let { lineScore = it.groupValues[1].toInt() }
                        scoreMateMatch?.let {
                            // ★ 连杀分统一收敛成 29999 封顶（不再是 N×10000 的爆炸值）。
                            lineScore = encodeMate(it.groupValues[1].toInt())
                            // ★★★ 杀棋提前收招（v2.9.22）：引擎确认必杀（≤6步杀、已搜≥3层）
                            //   立即 stop 收 bestmove——"两三步就能看出来的棋"不必等满深度预算
                            //   （旧版深度模式要把用户设的层数算满/等满 20s 才动身，正是"麻"的主因）。
                            //   皮卡鱼对 mate 分数随深度收敛，depth≥3 报 mate≤6 已相当可靠；
                            //   误杀风险极低，且即使误判，stop 返回的仍是当前最佳着法，不影响正确性。
                            if (!hardStopped && searchDepth >= 3 && bestMoveUci.isEmpty()) {
                                val plies = kotlin.math.abs(it.groupValues[1].toInt())
                                if (plies in 1..6) {
                                    Log.w(TAG, "★ 杀棋确认(${plies}步杀, depth=$searchDepth)，提前stop收招")
                                    try { sendCommand("stop") } catch (_: Exception) {}
                                    hardStopped = true
                                }
                            }
                        }

                        // 只有主变（multipv 1）覆盖主 score/pv，供 bestmove 兜底与 lastPv 使用
                        if (multiIdx == 1) {
                            score = lineScore
                            pvMatch?.let { pv = it.groupValues[1] }
                        }
                        // 登记该 multipv 主变（所有线都记录，供多线同步展示）
                        if (multiIdx in 1..5) {
                            pvMatch?.let { pvLinesArr[multiIdx] = PvLine(lineScore, it.groupValues[1]) }
                        }

                        // 实时回调思考信息 — ★★★ 节流到每100ms一次
                        // ★★★ 关键修复：这里绝不能 withContext(Dispatchers.Main)！
                        //   读引擎输出的核心循环若切到主线程，一旦UI忙（走子动画/棋谱
                        //   重建/记谱缓存），本协程就被挂起，停止消费outputQueue，
                        //   导致引擎已算完bestmove却迟迟读不到 → 误判"超时"→ 上层
                        //   重启引擎 → 顶部"引擎异常/启动中"、等几分钟不走。
                        //   思考回调本身很轻（ChessGame只更新几个数字；真正改UI的
                        //   onThinkingInfo 已在 MainActivity 里 runOnUiThread），
                        //   所以直接在 IO 线程调用即可，读循环永不被主线程卡死。
                        if (onThinking != null && searchDepth > 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastThinkingAtMs >= 100) {
                                lastThinkingAtMs = now
                                val d = searchDepth
                                val sd = selDepth
                                val sc = score
                                val n = nodes
                                val np = nps
                                val p = pv
                                val lines = (1..5).mapNotNull { pvLinesArr[it] }.filter { it.pv.isNotEmpty() }
                                onThinking(d, sd, sc, n, np, p, lines)
                            }
                        }
                    } else if (line.startsWith("bestmove")) {
                        val parts = line.split(" ")
                        if (parts.size >= 2) bestMoveUci = parts[1]
                        break
                    }
                }

                Log.i(TAG, "★★★ doSearch result: bestMove=$bestMoveUci, score=$score, depth=$searchDepth, nodes=$nodes, hardStopped=$hardStopped")

                // ★ 兜底：硬停了还是没有 bestmove，且本局确已搜出足够深的真实主变（≥2层），
                //   才从最后一条 pv 取第一步（这是引擎真实思考过的走法，不是乱走）。
                //   ★ 修复"切屏/小退回来 AI 乱走"：后台被限流/引擎被系统回收时，深度1的
                //   主变与"第一个合法走法"无异，绝不能用；至少搜过 2 层才算数。
                if (bestMoveUci.isEmpty() && hardStopped && pv.isNotEmpty() && searchDepth >= 2) {
                    val firstMove = pv.split(" ")[0].trim()
                    if (firstMove.isNotEmpty() && firstMove.length >= 4) {
                        bestMoveUci = firstMove
                        Log.w(TAG, "★ 硬停兜底：从 pv 取第一步(已搜${searchDepth}层) = $bestMoveUci")
                    }
                }

                // ★★★ 修复"切屏/小退回来 AI 乱走"（核心）：
                //   到了 deadline 仍无 bestmove（引擎被系统回收 / 后台被限流 / 死管道），
                //   绝不"从合法走法里选第一个"硬落子——那正是"炮1平2送炮口"这类没脑子棋的
                //   唯一出口。宁可返回 null 让上层走"重试→重启引擎→重新搜索"抢救链，
                //   也绝不用一个未经思考的错棋污染棋局。
                if (bestMoveUci.isEmpty()) {
                    Log.e(TAG, "★★★ 引擎超时未返回走法（引擎可能已死/被限流），返回null交由上层抢救，不硬落子")
                }

                // 解析走法并验证合法性
                var bestMove: Move? = null
                if (bestMoveUci.isNotEmpty() && bestMoveUci != "0000" && bestMoveUci != "(none)") {
                    bestMove = try { Move.fromUci(bestMoveUci, boardCopy) } catch (e: Exception) {
                        Log.e(TAG, "fromUci failed: ${e.message}")
                        null
                    }
                }

                val bestMoveVal = bestMove
                if (bestMoveVal != null) {
                    val isLegal = legalMovesForValidation.any { it.from == bestMoveVal.from && it.to == bestMoveVal.to }
                    if (!isLegal) {
                        Log.e(TAG, "★★★ 引擎返回了非法走法! bestMove=${bestMoveVal.toUci()}, fen=$fen")
                        bestMove = null
                    }
                } else {
                    Log.e(TAG, "★★★ 引擎未返回 bestmove! fen=$fen")
                    bestMove = null
                }

                isSearching = false
                withContext(Dispatchers.Main) { onResult(bestMove) }
                } catch (e: java.util.concurrent.CancellationException) {
                    isSearching = false
                    sendCommand("stop")
                    withContext(Dispatchers.Main) { onResult(null) }
                } catch (e: Exception) {
                    Log.e(TAG, "Search error", e)
                    isSearching = false
                    withContext(Dispatchers.Main) { onResult(null) }
                }
            } finally {
                // ★ 归还 MultiPV 为单线，避免影响后续单变搜索的深度与速度
                try { sendCommand("setoption name MultiPV value 1") } catch (_: Exception) {}
                searchLock.unlock()
            }
        }
    }

    // ── Ponder（后台思考）──
    //
    // 简化版：引擎只负责"启动无限时搜索"和"停止"，
    //   命中逻辑由上层 ChessGame 处理。
    // ── Ponder（后台思考）──
    //
    // ★ 设计原则：
    //   1. 单引擎、单锁（searchLock）：正常搜索和后台思考完全互斥，同一时间只做一件事
    //   2. 干净切换：启动新搜索前必须确保前一个完全停止
    //   3. Hash 复用：不清空 hash 表，前后台搜索共享已有成果，不重复计算
    //   4. 低负载：后台思考不打印密集日志，只更新深度/评分/PV 状态变量
    //
    // 使用方式：
    //   1. AI走完后，构造"对方走了预测步之后"的局面
    //   2. 调用 startPonder(board) 启动后台搜索（go infinite）
    //   3. 用户思考期间，引擎一直搜，深度越来越深
    //   4. 用户走棋后：
    //      - 命中（走了预测步）→ stopPonder()，直接用 getPonderBestMove() 的结果
    //      - 没命中 → stopPonder()，重新正常搜索（hash 表保留，加速新搜索）

    fun isPondering(): Boolean = isPondering

    /** 获取后台思考搜到的最佳走法（UCI格式） */
    fun getPonderBestMove(): String = ponderBestMove

    /** 获取后台思考的评分 */
    fun getPonderScore(): Int = ponderScore

    /** 获取后台思考搜到的深度 */
    fun getPonderDepth(): Int = ponderDepth

    /** 获取后台思考的完整PV */
    fun getPonderPv(): String = ponderPv

    /** 后台多线预案（按 multipv 升序，已过滤空线） */
    fun getPonderLines(): List<PonderLine> = synchronized(ponderLinesMap) {
        (1..ponderMultiPv).mapNotNull { ponderLinesMap[it] }.filter { it.pv.isNotEmpty() }
    }

    /** 当前一批预测的人脑走法（每条线的 pv[0]） */
    fun getPredictedMoves(): List<String> = getPonderLines().map { it.predictedMove }.filter { it.isNotEmpty() }

    /** 命中的应查：若某条预案预测了 uci，返回该线的应着（pv[1]）；未命中返回空串 */
    fun respondToPredictedMove(uci: String): String =
        getPonderLines().firstOrNull { it.predictedMove == uci }?.responseMove ?: ""

    /**
     * 启动后台多线预想（不限时，一直搜）
     *
     * ★ 设计核心（对应"预测人脑几手可能走法 + 各自预演应着"）：
     *   在当前局面（人脑待走）上开 MultiPV=N 跑 go infinite，逐层齐头并进。
     *   每条 info 主变 multipv k = 一条预案：
     *     - pv[0] = 预测人脑可能走的第 k 手
     *     - pv[1] = 引擎针对该手预演好的应着（人脑真走了 pv[0] 就秒出 pv[1]）
     *   引擎思考期间，界面把多条预案与各自深度/评分以 ①②③ 三行展示。
     *
     * @param board 要预想的局面（备份当前"人脑待走"的局面）
     */
    fun startPonder(board: Board) = startPonder(board, 0)

    /**
     * ★ 2.9.298 修复「①②③ 空壳」：原来 isSearching 时直接放弃、lock 抢不到也直接放弃，
     *   结果 UI 只画了 ①②③ 三个空位却永远等不到数据。
     *   现在改为有限次退避重试（主搜索结束/锁释放后自动补上），保证多线预想一定能起。
     */
    private fun startPonder(board: Board, attempt: Int) {
        if (!isReady) return
        if (isSearching) {
            if (attempt < 6) {
                Log.w(TAG, "startPonder: 正在搜索中，${300L * (attempt + 1)}ms 后重试(${attempt + 1}/6)")
                scope.launch { delay(300L * (attempt + 1)); startPonder(board, attempt + 1) }
            } else Log.w(TAG, "startPonder: 重试耗尽，放弃本轮预想")
            return
        }

        // 先停止任何已有的后台思考
        stopPonder()

        isPondering = true
        ponderBestMove = ""
        ponderPv = ""
        ponderScore = 0
        ponderDepth = 0
        synchronized(ponderLinesMap) { ponderLinesMap.clear() }

        val fen = board.toFen()
        val mpv = ponderMultiPv.coerceIn(1, 5)
        // ★ 分家：预想协程登记到 ponderJob（绝不再占用 searchJob 句柄）
        ponderJob = scope.launch {
            var locked = false
            try {
                locked = searchLock.tryLock(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                locked = false
            }
            if (!locked) {
                Log.w(TAG, "startPonder: 获取锁失败，稍后重试(${attempt + 1}/6)")
                isPondering = false
                if (attempt < 6) { delay(300L * (attempt + 1)); startPonder(board, attempt + 1) }
                return@launch
            }
            try {
                // 排空旧输出
                outputQueue.clear()

                // ★ MultiPV：同时搜多条"人脑可能走法"预案
                try { sendCommand("setoption name MultiPV value $mpv") } catch (_: Exception) {}
                // 发送 position 和 go infinite
                // ★ 注意：不调用 ucinewgame，保留 hash 表中的历史搜索成果
                sendCommand("position fen $fen")
                sendCommand("go infinite")
                Log.d(TAG, "★ Ponder 启动（go infinite）MultiPV=$mpv")

                var lastCallbackMaxDepth = 0  // 节流：最大深度增长了才回调一次

                while (isPondering && isActive) {
                    val line = outputQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue

                    if (line.startsWith("info ")) {
                        // ★ 逐条解析各 multipv 线的 深度/评分/PV
                        val parts = line.split(" ").toTypedArray()
                        var lineIdx = 1
                        var lineMultipv = 1
                        var lineDepth = 0
                        var lineScore = 0
                        var linePv = ""
                        while (lineIdx < parts.size) {
                            when (parts[lineIdx]) {
                                "multipv" -> lineMultipv = parts.getOrNull(lineIdx + 1)?.toIntOrNull() ?: 1
                                "depth" -> lineDepth = parts.getOrNull(lineIdx + 1)?.toIntOrNull() ?: 0
                                "score" -> {
                                    val scoreType = parts.getOrNull(lineIdx + 1)
                                    val scoreVal = parts.getOrNull(lineIdx + 2)?.toIntOrNull() ?: 0
                                    lineScore = if (scoreType == "mate") {
                                        encodeMate(scoreVal)
                                    } else {
                                        scoreVal
                                    }
                                }
                                "pv" -> {
                                    linePv = parts.drop(lineIdx + 1).takeWhile { it.isNotEmpty() }.joinToString(" ")
                                    break
                                }
                            }
                            lineIdx++
                        }
                        if (linePv.isNotEmpty()) {
                            synchronized(ponderLinesMap) {
                                ponderLinesMap[lineMultipv] = PonderLine(lineDepth, lineScore, linePv)
                            }
                            // 主变（multipv 1）同步覆盖整体状态，供兜底使用
                            if (lineMultipv == 1) {
                                ponderPv = linePv
                                ponderScore = lineScore
                                ponderDepth = lineDepth
                                ponderBestMove = linePv.split(" ").firstOrNull() ?: ""
                            }
                            // ★ 节流回调：任一线最大深度增长了才通知UI，避免刷屏
                            val maxD = synchronized(ponderLinesMap) {
                                ponderLinesMap.values.maxOfOrNull { it.depth } ?: 0
                            }
                            if (maxD > lastCallbackMaxDepth) {
                                lastCallbackMaxDepth = maxD
                                onPonderInfo?.invoke(lineDepth, lineScore, linePv, getPonderLines())
                            }
                        }
                    } else if (line.startsWith("bestmove")) {
                        val parts = line.split(" ")
                        if (parts.size >= 2) {
                            ponderBestMove = parts[1]
                        }
                        break
                    }
                }

                Log.d(TAG, "★ Ponder 结束：lines=${getPonderLines().map { it.predictedMove }} depth=$ponderDepth")

            } catch (e: Exception) {
                Log.e(TAG, "Ponder error: ${e.message}")
            } finally {
                // ★ 归还 MultiPV 为单线，避免影响后续单变搜索
                try { sendCommand("setoption name MultiPV value 1") } catch (_: Exception) {}
                try { searchLock.unlock() } catch (_: Exception) {}
                isPondering = false
            }
        }
    }

    /**
     * 停止后台思考
     * ★ 干净停止：发送 stop 命令，等待引擎输出 bestmove，确保状态一致
     *   不清空 hash 表，保留搜索成果供后续使用
     */
    fun stopPonder() {
        if (isPondering) {
            Log.d(TAG, "★ Ponder 停止")
            try { sendCommand("stop") } catch (_: Exception) {}
            isPondering = false
        }
        synchronized(ponderLinesMap) { ponderLinesMap.clear() }
        // ★ 分家：只取消预想协程（协程会在 finally 中释放锁）；
        //   绝不碰 searchJob —— 正式搜索不因停预想而被误杀。
        try { ponderJob?.cancel() } catch (_: Exception) {}
    }

    // ── 停止 ──

    fun stopSearch() {
        if (isReady) sendCommand("stop")
    }

    /**
     * ★★★ 参考 P象棋 1.5.2 的 _stopEngineBeforeBoardMutation：
     *   在棋盘变化 / 新搜索启动前，强制停止引擎并通过 isready→readyok 握手
     *   确认引擎已完全空闲。
     *
     *   原来的 fire-and-forget sendCommand("stop") 不等引擎确认就继续发命令，
     *   双 AI 对弈时引擎还没完全停下来就收到新 position+go → 命令交错 → 引擎
     *   内部状态混乱 → 走得慢吞吞 / 卡死。
     *
     *   @param timeoutMs 等待 readyok 的超时（默认 2s，足够引擎停手）
     *   @return true=引擎已确认空闲，false=超时未确认（调用方应视情况重试或重启）
     */
    fun stopAndWaitForIdle(timeoutMs: Long = 2000): Boolean {
        if (!isReady) return true
        return try {
            // 1. 发送 stop 终止任何进行中的搜索/ponder
            sendCommand("stop")
            // 2. 发送 isready 请求引擎确认空闲
            sendCommand("isready")
            // 3. 等待 readyok（引擎确认已停稳）
            //    注意：这里直接从 outputQueue 读，不走 waitForLine（后者会收集所有中间行），
            //    因为 stop 后引擎可能吐出最后几条 info + bestmove + readyok，我们只关心 readyok。
            val deadline = System.currentTimeMillis() + timeoutMs
            var gotReady = false
            while (System.currentTimeMillis() < deadline && !gotReady) {
                val line = outputQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (line.startsWith("readyok")) {
                    gotReady = true
                }
                // 其他行（info/bestmove）直接丢弃——它们是旧搜索的尾巴
            }
            if (!gotReady) {
                Log.w(TAG, "★ stopAndWaitForIdle: readyok 超时 (${timeoutMs}ms)")
            } else {
                Log.d(TAG, "★ stopAndWaitForIdle: 引擎已确认空闲")
            }
            gotReady
        } catch (e: Exception) {
            Log.e(TAG, "stopAndWaitForIdle error", e)
            false
        }
    }

    fun stop() {
        sendCommand("stop")
        searchJob?.cancel()
        ponderJob?.cancel()
        evalJob?.cancel()
        isSearching = false
    }

    /**
     * 彻底关闭引擎（发送 quit + 销毁进程 + 关闭流）
     */
    fun shutdown() {
        isReady = false
        isSearching = false
        try { sendCommand("quit") } catch (_: Exception) {}
        searchJob?.cancel()
        ponderJob?.cancel()
        evalJob?.cancel()
        readerThread?.interrupt()
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { process?.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        try { process?.destroyForcibly() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        process = null
        writer = null
        reader = null
        outputQueue.clear()
        Log.d(TAG, "Engine shut down")
    }

    /**
     * ★★★ 重启引擎进程（崩溃/无响应自愈）
     * 关闭旧进程 → 重新定位 → 启动 → 完整初始化（UCI握手+NNUE+验证搜索）。
     * 阻塞调用，应在后台线程执行。
     */
    fun restartEngine(): String? {
        return try {
            Log.w(TAG, "★★★ 重启引擎进程...")
            shutdown()
            val locErr = locateEngine()
            if (locErr != null) return locErr
            val startErr = startEngine()
            if (startErr != null) return startErr
            val initErr = fullInit()
            if (initErr != null) return initErr
            Log.i(TAG, "★★★ 引擎重启成功")
            null
        } catch (e: Exception) {
            Log.e(TAG, "restartEngine异常", e)
            "引擎重启异常: ${e.message}"
        }
    }

    // ── 人话解释专用分析接口 ──

    fun analyzePosition(
        board: Board,
        depth: Int = 12,
        onResult: (bestMoveUci: String, score: Int, searchDepth: Int, pv: String) -> Unit
    ) {
        if (!isReady) {
            onResult("", 0, 0, "")
            return
        }

        val fen = board.clone().toFen()

        evalJob = scope.launch {
            try {
                val parsed = doUciSearch(fen, EVAL_MOVETIME_MS, "")
                withContext(Dispatchers.Main) {
                    onResult(parsed.bestMoveUci, parsed.score, parsed.depth, parsed.pv)
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyzePosition error", e)
                withContext(Dispatchers.Main) { onResult("", 0, 0, "") }
            }
        }
    }

    fun analyzeReview(
        board: Board,
        depth: Int = 0,
        timeMs: Int? = null,
        onResult: (bestMoveUci: String, score: Int, searchDepth: Int, pv: String) -> Unit
    ) {
        // 复盘专用：默认按时长(go movetime N)走满、逐步加深，与「分析开/关」同一口径 → 分与满级分析一致。
        // 传入 depth>0 时仍可按指定深度搜索；score 为对"当前行棋方(stm)"的原始评分，红黑方向的换算由调用方按当事方处理。
        if (!isReady) {
            onResult("", 0, 0, "")
            return
        }
        val fen = board.clone().toFen()
        evalJob = scope.launch {
            try {
                val depthArg = if (depth > 0) depth.coerceIn(1, 30) else null
                // ★★★ 复盘评分修复（针对"曲线一片均势"）：绝不再每局面 ucinewgame 清空换局表(TT)。
                //   复盘复用主对局引擎，其 TT 里本局各局面早已从真实对弈搜索累积了深评（AI回合搜过、
                //   逐局面累加），清表反而把深评全扔 → 每局面从零浅搜、预算内算不满层、只拿浅层/边界分，
                //   于是明明一方大优的棋也被画成 0 均势平线。
                //   保留 TT（zobrist 按局面+走子方区分，位置独立不串，同 searchBestMoveExcluding 取舍），
                //   并以 "go depth N movetime M" 双保险走满 → 总能收 bestmove、落到真实深评稳定分。
                try { sendCommand("setoption name MultiPV value 1") } catch (_: Exception) {}
                val dLev = (depthArg?.coerceAtLeast(1) ?: 1).toLong()
                val capMs = timeMs ?: ((EVAL_REVIEW_MOVETIME_MS).coerceAtLeast((dLev * 350L + 2000L).toInt())).coerceAtMost(15000)
                val parsed = doUciSearch(fen, capMs, "", depth = depthArg, resetFresh = false)
                withContext(Dispatchers.Main) { onResult(parsed.bestMoveUci, parsed.score, parsed.depth, parsed.pv) }
            } catch (e: Exception) {
                Log.e(TAG, "analyzeReview error", e)
                withContext(Dispatchers.Main) { onResult("", 0, 0, "") }
            }
        }
    }

    fun evaluateMove(
        board: Board,
        humanMoveUci: String,
        depth: Int = 12,
        onResult: (bestMoveUci: String, bestScore: Int, humanScore: Int, humanPv: String, searchDepth: Int) -> Unit
    ) {
        if (!isReady) {
            onResult("", 0, 0, "", 0)
            return
        }

        val fen = board.clone().toFen()

        evalJob = scope.launch {
            try {
                val parsed1 = doUciSearch(fen, EVAL_MOVETIME_MS, "")

                val tempBoard = Board()
                tempBoard.loadFromFen(fen)
                val humanMove = Move.fromUci(humanMoveUci, tempBoard)
                if (humanMove == null) {
                    withContext(Dispatchers.Main) {
                        onResult(parsed1.bestMoveUci, parsed1.score, 0, "", parsed1.depth)
                    }
                    return@launch
                }
                tempBoard.makeMove(humanMove)
                val afterFen = tempBoard.toFen()

                val parsed2 = doUciSearch(afterFen, EVAL_MOVETIME_MS, "")
                val humanScore = -parsed2.score

                withContext(Dispatchers.Main) {
                    onResult(parsed1.bestMoveUci, parsed1.score, humanScore, parsed2.pv, parsed1.depth)
                }
            } catch (e: Exception) {
                Log.e(TAG, "evaluateMove error", e)
                withContext(Dispatchers.Main) { onResult("", 0, 0, "", 0) }
            }
        }
    }

    // ── 内部工具 ──

    /** ★ 询招事务的返回（一次一问，问完即消） */
    data class OracleReply(
        val bestMoveUci: String = "",
        val score: Int = 0,
        val depth: Int = 0,
        val pv: String = ""
    )

    /**
     * ★★★ 询招事务（解惑沙盒同款纪律的公共入口，供 SandboxOracle 调用）：
     *
     *   stop → isready(站稳) → position fen → go movetime/depth → 硬死线内收 bestmove → 归零
     *
     *  - 阻塞【调用线程】（应只在 SandboxOracle 的单飞问答线程上跑）；
     *  - 有界锁(2s) + 硬死线(预算+8s)，必然返回，绝不无限等待；
     *  - 问完即消：不留驻任何后台思考状态，下个问题先 stop 再来，天然归零；
     *  - 深度模式按层问（go depth），时间模式按秒问（go movetime），规矩跟用户设置走。
     *
     * @param onThinking 每收到一条带主变的 info 摘要回调一次（在调用线程上，回调方自行切主线程）
     */
    fun askBestMove(
        board: com.pikafish.chess.model.Board,
        timeMs: Long?,
        depth: Int?,
        restrictToUci: Set<String>?,
        excludedUci: Set<String>,
        onThinking: ((Int, Int, Int, Long, Long, String) -> Unit)? = null
    ): OracleReply {
        // ★ 引擎未就绪绝不"瞬间空手回家"——那正是"秒走一着晕招"的元凶之一
        //   （上层拿空结果就去挑"第一个合法着法"，某个安静的局面里第一手往往是飞边相等废棋）。
        //   先给一段就绪窗口（≤4s）等引擎站稳，再谈搜索；真等不到才空手。
        if (!isReady) {
            var waitedMs = 0L
            while (!isReady && waitedMs < 4000) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                waitedMs += 100
            }
            if (!isReady) return OracleReply()
        }
        // ★ 沙盒单飞串行保证了本引擎同一时刻只有一处在问，锁理应空闲；
        //   把 2s 超时放宽到 12s，杜绝"锁恰好被上一条尾巴占了一瞬→瞬间空手→乱走一着"。
        if (!searchLock.tryLock(12, TimeUnit.SECONDS)) {
            Log.w(TAG, "askBestMove: 获取引擎锁超时（12s），快速失败（上层兜底）")
            try { sendCommand("stop") } catch (_: Exception) {}
            return OracleReply()
        }
        try {
            // ① 对接前先喊停：无论上一问残留什么，stop + isready 站稳再接新任务
            //    （"对接完了就消"的标准纪律 —— 每次任务前先发 stop，绝不搞混）
            try { stopAndWaitForIdle(1500) } catch (_: Exception) {}

            // ② 局面 + 候选限定（searchmoves：多源汇审候选集 或 "合法着法-排除集"）
            outputQueue.clear()
            val fen = board.toFen()
            sendCommand("position fen $fen")
            val side = board.currentPlayer
            val legal = try { board.getLegalMoves(side) } catch (e: Exception) { emptyList<com.pikafish.chess.model.Move>() }
            val cands: List<String> = if (!restrictToUci.isNullOrEmpty()) {
                // ★★ 多源汇审：候选集也要同步剔除"磨棋盘/重复违规"走法——否则云库+开局库汇审时
                //   这些走法会漏拦（正是"明明在磨棋盘，引擎却一直载入库变"的隐患之一）。
                legal.filter { it.toUci() in restrictToUci && it.toUci() !in excludedUci }.map { it.toUci() }
            } else if (excludedUci.isNotEmpty()) {
                legal.filter { it.toUci() !in excludedUci }.map { it.toUci() }
            } else {
                emptyList()
            }
            val sm = if (cands.isNotEmpty()) " searchmoves ${cands.joinToString(" ")}" else ""
            val movetime = (timeMs?.toInt() ?: 2000).coerceIn(100, 60_000)
            val cmd = when {
                depth != null && depth > 0 -> "go depth ${depth.coerceIn(1, 40)}$sm"
                else -> "go movetime $movetime$sm"
            }
            sendCommand(cmd)
            // ★★★ 询招搜索期间必须置位"正在搜索"：此前 askBestMove 从不置 isSearching
            //   （该标志只在 doSearch 里置位），主线程"硬步时看门狗"因此永远误判
            //   "引擎没在搜索"→ 每 8.8s 就走"复位+重新走棋"分支 → 旧搜索被代际作废、
            //   新搜索从浅层重算 → "设17层永远只算到16层、还反复从10/12层绕弯重算"
            //   （用户核心顽疾的根因之一）。置位后看门狗才能正确看到"引擎正在搜索"。
            isSearching = true

            // ③ 硬死线内收 bestmove（预算 + 8 秒宽限，必然有出口）
            //   ★ 深度模式预算大幅放大：目标层×1000ms+10s（15s~180s 封顶），给足引擎
            //     滚到目标层的时间——绝不在它算满层数之前掐停。旧预算"目标层×600ms
            //     封顶9s"在复杂局面里 17 层根本滚不完，正是"规定17层却只算到16层"
            //     的根因之一。时间模式预算仍等于用户步时，原样不动。
            val baseBudget = if (depth != null && depth > 0) {
                (depth.toLong() * 1000L + 10000L).coerceIn(15000L, 180000L)
            } else {
                movetime.toLong()
            }
            val deadline = System.currentTimeMillis() + baseBudget + 8000L

            var best = ""
            var score = 0
            var d = 0
            var pv = ""
            var nodes = 0L
            var nps = 0L
            while (System.currentTimeMillis() < deadline) {
                val line = outputQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (line.startsWith("info")) {
                    Regex("score cp (-?\\d+)").find(line)?.let { score = it.groupValues[1].toInt() }
                    Regex("score mate (-?\\d+)").find(line)?.let { score = encodeMate(it.groupValues[1].toInt()) }
                    Regex(" depth (\\d+)").find(line)?.let { d = it.groupValues[1].toInt() }
                    Regex(" nodes (\\d+)").find(line)?.let { nodes = it.groupValues[1].toLong() }
                    Regex(" nps (\\d+)").find(line)?.let { nps = it.groupValues[1].toLong() }
                    Regex(" pv (.+)$").find(line)?.let { pv = it.groupValues[1] }
                    if (onThinking != null && pv.isNotEmpty()) {
                        try { onThinking(d, d, score, nodes, nps, pv) } catch (_: Exception) {}
                    }
                } else if (line.startsWith("bestmove")) {
                    val parts = line.split(" ")
                    if (parts.size >= 2) best = parts[1]
                    break
                }
            }
            if (best.isEmpty()) Log.w(TAG, "askBestMove: 死线内未见 bestmove（空手而归，上层兜底）")
            return OracleReply(best, score, d, pv)
        } finally {
            // ★ 复位"正在搜索"标志：无论正常收招还是异常退出，都必须归零，
            //   否则看门狗会永远认为引擎在搜（或认为残留），影响后续判断。
            isSearching = false
            searchLock.unlock()
        }
    }

    private data class UciSearchResult(
        val bestMoveUci: String = "",
        val score: Int = 0,
        val depth: Int = 0,
        val pv: String = ""
    )

    /**
     * 执行 UCI 搜索（同步，用于分析）
     */
    private suspend fun doUciSearch(fen: String, movetimeMs: Int, searchMoves: String, depth: Int? = null, resetFresh: Boolean = false): UciSearchResult {
        // ★★★ 修复：原用 searchLock.lock() 无限期阻塞，协程 cancel() 无法打断它，
        //   导致解惑/分析在引擎忙着别的事（后台思考/走棋）时永久卡死、线程泄漏，
        //   且重启时 fullInit 会去等一把永远不放的锁 →"引擎异常自动恢复"反复出现。
        //   改为有界 tryLock(2s)，抢不到立即快速失败返回空结果，上层自行兜底/回归。
        if (!searchLock.tryLock(2, TimeUnit.SECONDS)) {
            Log.w(TAG, "doUciSearch: 获取引擎锁超时(fen=$fen)，快速失败")
            try { sendCommand("stop") } catch (_: Exception) {}
            return UciSearchResult()
        }
        try {
            outputQueue.clear()

            // ★ 复盘/独立评估：清空换局表(TT)，让"同一个局面"无论被放在长区间还是
            //   短区间里评估，都得到只与该局面相关、不含前序搜索污染的稳定评分，
            //   从而彻底解决"漏着大分"随区间宽度时隐时现的问题。
            if (resetFresh) {
                sendCommand("ucinewgame")
                sendCommand("isready")
            }
            sendCommand("position fen $fen")
            val cmd = when {
                depth != null && depth > 0 && searchMoves.isNotEmpty() -> "go depth $depth movetime $movetimeMs $searchMoves"
                depth != null && depth > 0 -> "go depth $depth movetime $movetimeMs"
                searchMoves.isNotEmpty() -> "go movetime $movetimeMs $searchMoves"
                else -> "go movetime $movetimeMs"
            }
            sendCommand(cmd)

            var bestMoveUci = ""
            var score = 0
            var searchDepth = 0
            var pv = ""

            val deadline = System.currentTimeMillis() + movetimeMs + 5000

            while (System.currentTimeMillis() < deadline) {
                val line = outputQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue

                if (line.startsWith("info")) {
                    Regex("score cp (-?\\d+)").find(line)?.let { score = it.groupValues[1].toInt() }
                    Regex("score mate (-?\\d+)").find(line)?.let {
                        score = encodeMate(it.groupValues[1].toInt())
                    }
                    Regex(" depth (\\d+)").find(line)?.let { searchDepth = it.groupValues[1].toInt() }
                    Regex(" pv (.+)$").find(line)?.let { pv = it.groupValues[1] }
                } else if (line.startsWith("bestmove")) {
                    val parts = line.split(" ")
                    if (parts.size >= 2) bestMoveUci = parts[1]
                    break
                }
            }

            return UciSearchResult(bestMoveUci, score, searchDepth, pv)
        } finally {
            searchLock.unlock()
        }
    }

    private fun startReaderThread() {
        readerAlive = true
        readerThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader?.readLine()
                    if (line == null) {
                        // reader 返回 null 表示进程已退出或流关闭
                        Log.w(TAG, "Reader: stream closed (engine process may have died)")
                        break
                    }
                    if (line.isBlank()) continue
                    // ★ 思考日志直排本地已按用户要求移除（询招架构一次一问，无需日志管理）。
                    // ★★★★★ 修复"残局/复杂长思考闷死卡死"的致命点 ★★★★★
                    // 错误做法：bestmove 用 put() 阻塞等待。一旦复杂局面引擎爆量输出
                    // info 行把有界队列(1万)塞满，读线程就永久卡在 put(bestmove) 上，
                    // 不再读引擎 stdout → 引擎 stdout 管道被塞满 → 引擎阻塞写 stdout、
                    // 永远吐不出 bestmove → 整局死锁。
                    // 正确做法：读线程绝不能静候队列；bestmove 必须到达，但若队列满，
                    // 丢最旧的 info 行腾出空间。info 可丢，bestmove 必达，读线程绝不阻塞。
                    if (line.startsWith("bestmove")) {
                        while (!outputQueue.offer(line, 500, TimeUnit.MILLISECONDS)) {
                            // 队列满：丢队头最旧的 info 行腾位置，回归正常读取管道
                            outputQueue.poll(1, TimeUnit.MILLISECONDS)
                        }
                    } else {
                        // info 行可丢弃：满则超时丢弃，绝不阻塞读线程
                        outputQueue.offer(line, 50L, TimeUnit.MILLISECONDS)
                    }
                }
            } catch (_: InterruptedException) {
                // 正常退出
            } catch (e: Exception) {
                Log.e(TAG, "Reader thread error", e)
            }
            // 无论正常/异常退出，标记读线程死亡 → isEngineReady() 返回 false → Watchdog 重启
            readerAlive = false
            // ★★★ 异地引擎被对端断开（对方换了引擎/关了共享）→ 立刻判"未就绪"。★★★
            //   否则本机仍以为连着，后续搜索命令全打进一条死 socket，界面就永远停在
            //   "引擎恢复中/思考中"。判未就绪后，下一次询招会重新连上去、重新握手，
            //   拿到对方当前采用的那台新引擎——这就是"换引擎丝滑跟进"的关键一环。
            if (remoteEndpoint != null) {
                isReady = false
                Log.w(TAG, "异地引擎连接已被对端断开，标记未就绪，等待自动重连：$remoteEndpoint")
            }
            Log.d(TAG, "Reader thread exited")
        }
        readerThread?.isDaemon = true
        readerThread?.start()
    }

    private fun sendCommand(cmd: String) {
        try {
            Log.d(TAG, "> $cmd")
            writer?.write(cmd)
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send: $cmd", e)
        }
    }

    private fun waitForLine(keyword: String, timeoutMs: Long): List<String>? {
        val lines = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = outputQueue.poll(100L, TimeUnit.MILLISECONDS)
            if (line != null) {
                Log.d(TAG, "< $line")
                lines.add(line)
                if (line.startsWith(keyword)) return lines
            }
        }
        Log.w(TAG, "Timeout waiting for '$keyword'")
        return null
    }
}
