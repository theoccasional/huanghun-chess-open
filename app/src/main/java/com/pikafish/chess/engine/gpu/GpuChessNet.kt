package com.pikafish.chess.engine.gpu

import android.util.Log
import com.pikafish.chess.model.Board
import com.pikafish.chess.model.PieceType

/**
 * ★★★ GPU 神经引擎 —— 神经网络评估网 ★★★
 *
 * 用 OpenGL ES 计算着色器在 GPU 上跑的多层感知机（MLP），对局面做评分。
 *
 * 网络结构（全部为可在 GPU 上批量前向的权重张量）：
 *   - 输入特征 x ∈ R^630：把棋盘按"当前走棋方视角"编码成一个带符号 one-hot 向量。
 *     - 索引 = pieceType(0..6) * 90 + square(行*9+列)
 *     - x[i] = +1 表示本方该类型棋子在对应格；-1 表示对方(镜像后的格)该类型棋子。
 *     - 方块 encoding 天然同时表达「子力 + 位置 + 双方相对位置」，SD编码。
 *   - 隐藏层：h = W·x + b，宽度 hid=8
 *     - 单元0 用线性激活（承载经典强子力+位置价值，保证棋力基线）
 *     - 单元1..7 用 ReLU（承载打击/王险/过河等辅助启发，只增不减）
 *   - 输出：score = Σ_h W2[h] * act(h)  + b2（约 centipawn，正对本方有利）
 *
 * 权重生成：默认权重由经典韦恩表(材质+位置价值表)确定性生成，保证离线可复现、
 * 棋力可靠；同时支持从外部"解压包路径"加载自定义权重（见 loadFromFile）。
 * GPU 只负责矩阵乘 + 激活的批量前向，规律简单、易于在着色器里复算。
 */
class GpuChessNet {

    companion object {
        private const val TAG = "GpuChessNet"

        const val BOARD_SQ = 90
        const val PIECE_TYPES = 7                       // King,Advisor,Elephant,Knight,Rook,Cannon,Pawn
        const val N_IN = PIECE_TYPES * BOARD_SQ         // 630
        const val HID = 8                               // 隐藏层宽度

        val TYPE_ORD = mapOf(
            PieceType.KING to 0, PieceType.ADVISOR to 1, PieceType.ELEPHANT to 2,
            PieceType.KNIGHT to 3, PieceType.ROOK to 4, PieceType.CANNON to 5, PieceType.PAWN to 6
        )
    }

    // 经典子力价值（centipawn）
    private val PIECE_VAL = doubleArrayOf(
        0.0,   // King（位置价值另计，王不跑位在此表给0）
        200.0, // Advisor
        200.0, // Elephant
        400.0, // Knight
        900.0, // Rook
        450.0, // Cannon
        100.0  // Pawn
    )

    // 位置价值表（红方视角：row 0=黑方底线(上)，row 9=红方底线(下)）。
    // 生成时会把坐标变换到 ST 视角，使得红黑对称。
    private fun pst(type: PieceType): Array<IntArray> = when (type) {
        PieceType.KING -> PST_KING
        PieceType.ADVISOR -> PST_ADVISOR
        PieceType.ELEPHANT -> PST_ELEPHANT
        PieceType.KNIGHT -> PST_KNIGHT
        PieceType.ROOK -> PST_ROOK
        PieceType.CANNON -> PST_CANNON
        PieceType.PAWN -> PST_PAWN
    }

    // ── 权重张量 ──
    @Volatile var weightW: FloatArray = FloatArray(N_IN * HID)   // 630x8
    @Volatile var weightB: FloatArray = FloatArray(HID)
    @Volatile var weightW2: FloatArray = FloatArray(HID)
    @Volatile var weightB2: Float = 0f
    @Volatile var loadedFromPath: String? = null
    @Volatile var initialized: Boolean = false

    /** 生成默认权重（经典子力 + 位置价值 + 少量辅助启发） */
    fun initDefaultWeights() {
        val W = FloatArray(N_IN * HID)
        val b = FloatArray(HID)
        // 单元0：子力 + 位置价值（线性 = 经典强评估基线）
        for (t in 0 until PIECE_TYPES) {
            val type = TYPE_ORD.entries.first { it.value == t }.key
            val valT = PIECE_VAL[t].toFloat()
            val table = pst(type)
            for (sq in 0 until BOARD_SQ) {
                val r = sq / 9
                val c = sq % 9
                val idx = t * BOARD_SQ + sq
                W[idx * HID + 0] = valT + table[r][c]
            }
        }
        b[0] = 0f

        // 单元1..7：辅助启发（保守小权重，ReLU 只增不减，避免破坏基线）
        // 单元1：将士/象 当中线协同（clamp 到合理权重，防止奇异）
        // 单元2：车炮开线（车在中路多给分）
        // 单元3..7 预留给外部自定义权重，默认置 0
        for (sq in 0 until BOARD_SQ) {
            val r = sq / 9
            val c = sq % 9
            // 单元1：中路(第4列)控制 —— 对车/马/炮 给少量加成
            if (c == 4) {
                W[(TYPE_ORD[PieceType.ROOK]!! * BOARD_SQ + sq) * HID + 1] = +30f
                W[(TYPE_ORD[PieceType.CANNON]!! * BOARD_SQ + sq) * HID + 1] = +20f
            }
            // 单元2：兵力过河推进（红方视角 r 越大越接近对方，黑方走子时镜像后同样适用）
            val adv = (r - 4).coerceAtLeast(0)   // 红视角: 越过中线的行数
            W[(TYPE_ORD[PieceType.PAWN]!! * BOARD_SQ + sq) * HID + 2] = 12f * adv
        }

        // 输出层：主用单元0（权重1.0），辅助单元系数给少量正系数
        val w2 = FloatArray(HID)
        w2[0] = 1.00f
        w2[1] = 0.55f
        w2[2] = 0.30f

        synchronized(this) {
            weightW = W
            weightB = b
            weightW2 = w2
            weightB2 = 0f
            loadedFromPath = null
            initialized = true
        }
        Log.i(TAG, "默认神经网络权重已生成 (N=$N_IN, H=$HID)")
    }

    /** 从外部解压包路径加载自定义权重（纯文本 SSV：W、b、W2、b2，方便手改/外部引擎包提供） */
    fun loadFromFile(path: String): String? {
        return try {
            val lines = java.io.File(path).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val W = FloatArray(N_IN * HID)
            val b = FloatArray(HID)
            val w2 = FloatArray(HID)
            var b2 = 0f
            var section = 0
            for (line in lines) {
                if (line == "[w]") { section = 0; continue }
                if (line == "[b]") { section = 1; continue }
                if (line == "[w2]") { section = 2; continue }
                if (line == "[b2]") { section = 3; continue }
                val vals = line.split(Regex("\\s+|,")).mapNotNull { it.toFloatOrNull() }
                when (section) {
                    0 -> { // W 630x8 逐行
                        val i = vals[0].toInt()
                        for (j in 0 until HID.coerceAtMost(vals.size - 1)) W[i * HID + j] = vals[j + 1]
                    }
                    1 -> { val i = vals[0].toInt(); if (i in 0 until HID) b[i] = vals.getOrNull(1) ?: 0f }
                    2 -> { val i = vals[0].toInt(); if (i in 0 until HID) w2[i] = vals.getOrNull(1) ?: 0f }
                    3 -> b2 = vals.firstOrNull() ?: 0f
                }
            }
            synchronized(this) {
                weightW = W
                weightB = b
                weightW2 = w2
                weightB2 = b2
                loadedFromPath = path
                initialized = true
            }
            Log.i(TAG, "外置神经网络权重已加载: $path")
            null
        } catch (e: Exception) {
            Log.e(TAG, "加载外部权重失败", e)
            "权重文件解析失败: ${e.message}"
        }
    }

    // ── 特征编码 ──

    /**
     * 把局面编码成 630 维带符号 one-hot 特征（当前走棋方视角）。
     * @param stRed 是否为红方视角；返回向量统一换算到传入方的角度。
     */
    fun encodeFeatures(board: Board, stRed: Boolean): FloatArray {
        val x = FloatArray(N_IN)
        for (r in 0..9) {
            for (c in 0..8) {
                val p = board.pieceAt(r, c) ?: continue
                val ord = TYPE_ORD[p.type] ?: continue
                val isSt = (p.color == com.pikafish.chess.model.PieceColor.RED) == stRed
                // 统一翻译到「走棋方在底、对方在顶」的对称坐标系：
                //  - 走棋方棋子：stRow，走棋方始终当底部（红视角 r 越大越靠前）
                //  - 对方棋子：oppRow，对方镜像到顶部，使位置价值表对称复用
                val stRow = if (stRed) r else 9 - r
                val oppRow = if (stRed) 9 - r else r
                val pr = if (isSt) stRow else oppRow
                val sq = pr * 9 + c
                val idx = ord * BOARD_SQ + sq
                if (isSt) x[idx] += 1f else x[idx] -= 1f
            }
        }
        return x
    }

    // ── CPU 参考前向（离线验证 / GPU 不可用时做兜底）──

    private fun forwardCpu(x: FloatArray): Float {
        val h = FloatArray(HID)
        val W = weightW; val b = weightB
        for (j in 0 until HID) {
            var acc = b[j]
            var i = 0
            while (i < N_IN) { acc += x[i] * W[i * HID + j]; i++ }
            h[j] = acc
        }
        // 单元0 线性；1..7 ReLU
        var out = weightB2
        out += weightW2[0] * h[0]
        for (j in 1 until HID) out += weightW2[j] * if (h[j] > 0) h[j] else 0f
        return out
    }

    /** 从 ST 视角求分（正=对走棋方有利，单位约 1/40 子，即 100=1米子）。缩放成整数厘值。 */
    fun evaluate(board: Board): Int {
        if (!initialized) initDefaultWeights()
        val stRed = board.currentPlayer == com.pikafish.chess.model.PieceColor.RED
        val x = encodeFeatures(board, stRed)
        return (forwardCpu(x) * 2).toInt()   // 放大2倍，得到接近「厘」的尺度便于搜索比较
    }

    /** 供 GpuEvaluator 调用的 CPU 参考前向（GPU 兜底 / 离线校验用） */
    internal fun forwardCpuNet(x: FloatArray): Float {
        if (!initialized) initDefaultWeights()
        return forwardCpu(x)
    }

    /** 导出当前权重快照：[W(N*H)] [b(H)] [w2(H)] [b2(1)]，供上传 GPU */
    internal fun copyLiveWeights(): FloatArray {
        if (!initialized) initDefaultWeights()
        val out = FloatArray(N_IN * HID + HID + HID + 1)
        var k = 0
        for (i in weightW) out[k++] = i
        for (i in weightB) out[k++] = i
        for (i in weightW2) out[k++] = i
        out[k] = weightB2
        return out
    }

    // ── 位置价值表（红方视角；红走子时 r 即行，黑走子时镜像）──

    // 王：只在九宫，分成上下两区
    private val PST_KING = Array(10) { r -> IntArray(9) { c ->
        when {
            r < 3 -> if (c in 3..5) -10 else -2000
            r > 6 -> if (c in 3..5) 0 else -2000
            else -> -2000
        }
    } }

    // 士：九宫斜线
    private val PST_ADVISOR = Array(10) { r -> IntArray(9) { c ->
        val inPalace = (r in 0..2 || r in 7..9) && c in 3..5
        if (!inPalace) -3000 else when {
            c == 4 -> 20 + if (r == 1 || r == 8) 5 else 0
            else -> if ((r == 0 && c == 3) || (r == 0 && c == 5) || (r == 2 && c == 3) || (r == 2 && c == 5)) 10 else 5
        }
    } }

    // 象：不能过河
    private val PST_ELEPHANT = Array(10) { r -> IntArray(9) { c ->
        when {
            r > 4 -> -4000   // 红底象区域到对方河界内不允许
            r in 0 until 5 && (r + c) % 2 == 0 && c in 0..8 -> (10 + if (c == 4) 5 else 0)
            else -> -4000
        }
    } }

    // 马：中前场更有价值
    private val PST_KNIGHT = Array(10) { r -> IntArray(9) { c ->
        val center = 20 - Math.abs(c - 4) * 4
        val advance = if (r > 4) (r - 4) * 3 else (4 - r) * (-2)
        center + advance
    } }

    // 车：开线中路价值高
    private val PST_ROOK = Array(10) { r -> IntArray(9) { c ->
        val center = (18 - Math.abs(c - 4) * 6).coerceAtLeast(0)
        val advance = if (r < 5) (5 - r) * 4 else -r  // 越深入对方越有利
        center + advance
    } }

    // 炮：应保留在中后场，偷袭价值
    private val PST_CANNON = Array(10) { r -> IntArray(9) { c ->
        val center = (8 - Math.abs(c - 4) * 2).coerceAtLeast(0)
        val keep = (10 - r).coerceAtLeast(0)
        center + keep
    } }

    // 兵：未过河价值小，过河后位置价值快速上升
    private val PST_PAWN = Array(10) { r -> IntArray(9) { c ->
        when {
            r < 5 -> (r - 4) * -5 - 5      // 未过河
            else -> (r - 4) * 30 + (if (c == 4) 20 else 0)   // 过河推进
        }
    } }
}