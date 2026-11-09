package com.pikafish.chess

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.pikafish.chess.heavy.HeavyStore
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ONNX 深度学习棋盘识别器
 *
 * 使用两阶段神经网络模型：
 * 1. 关键点检测模型 (pose_model.onnx) — RTMPose 架构，检测棋盘四角
 * 2. 棋子分类模型 (layout_model.onnx) — SwinV2-tiny，整盘 10×9 格子 16 类分类
 *
 * 流程：图片 → 关键点检测 → 透视变换矫正 → 整盘分类 → 10×9 棋子布局
 *
 * 模型来源：cheese2020/Chess-Recognition (MIT License)
 * https://github.com/cheese2020/Chess-Recognition
 */
class OnnxBoardRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "OnnxBoardRecognizer"
        private const val POSE_MODEL_NAME = "pose_model.onnx"
        private const val LAYOUT_MODEL_NAME = "layout_model.onnx"
        // ★ 借鉴"象棋笔记"的 YOLO 棋子检测模型（yolov5），作为辅助识别层
        private const val YOLO_MODEL_NAME = "yolo_model.onnx"
        // ★ 新版：YOLO11（img2fen，opset19，输出 [1,19,8400]，无 objectness，15 类顺序同旧模型）
        private const val YOLO11_MODEL_NAME = "yolo11_model.onnx"
        /** ★ v11 开关：默认关（v11 在实测中线识别稳定性不如 v5，先回退，等有 A/B 数据再开）。 */
        private const val YOLO11_ENABLED = false

        // 模型输入尺寸
        private const val POSE_INPUT_SIZE = 256
        // ★★★ RTMPose 角点检测的 bbox padding —— 必须与训练/参考实现一致。
        //   参考实现 Chinese_Chess_Recognition(4_v6-0301.onnx) 用的是 1.25；
        //   这里原本写成 1.5，棋盘在 256×256 里被压得更小、偏离训练分布，
        //   实测角点置信度从 0.307/0.316 掉到 0.246/0.300，角点也更偏。
        private const val POSE_PADDING = 1.25f
        // 备用 padding：极窄/极宽屏幕上 1.25 可能仍不理想，再试 1.5，取置信度更高者。
        private const val POSE_PADDING_ALT = 1.5f
        // 主 padding 平均置信度高于此值就直接采信，不再试备用（省一次推理）。
        private const val POSE_PADDING_GOOD_CONF = 0.20f
        private const val LAYOUT_INPUT_W = 280
        private const val LAYOUT_INPUT_H = 315
        // ★★ 金基准原值 400×450（宽高比8:9）— 必须与模型输入 LAYOUT_INPUT 280×315(8:9) 匹配
        //   ★★★ 重要：这是共享常量，自由摆子识别和悬浮窗校准验证都用它。
        //   不得改成其他宽高比，否则 centerCrop 后 Resize 会拉伸棋盘导致识别失败。
        private const val LAYOUT_CROP_W = 400
        private const val LAYOUT_CROP_H = 450

        // ★★★ YOLO 辅助层常量
        // YOLO 15 类（于此模型实测锁定）：黑方 cls0-6=马象士将车炮卒，红方 cls7-13=车马仕帅相炮兵，cls14=空
        // 与布局模型类号(红2-8帅仕相马车炮兵、黑9-15将士象马车炮卒)不同，故做映射：
        //   yolo 0黑马→12, 1黑象→11, 2黑士→10, 3黑将→9, 4黑车→13, 5黑炮→14, 6黑卒→15
        //   7红车→6, 8红马→5, 9红仕→3, 10红帅→2, 11红相→4, 12红炮→7, 13红兵→8, 14空→0
        private val YOLO_TO_CLASS = intArrayOf(
            12, 11, 10, 9, 13, 14, 15,   // yolo 0..6 黑方
            6, 5, 3, 2, 4, 7, 8,         // yolo 7..13 红方
            0                              // yolo 14 空
        )
        // YOLO 网格到棋盘单元格的几何（warped 450×500 + padding 50，col间隔43.75 / row间隔44.44）
        private const val YOLO_COL_STEP = 43.75f
        private const val YOLO_ROW_STEP = 44.44f
        // 融合阈值：
        //   rescue：YOLO 确认为棋子且 layout 为空格/低置信 → 采信 YOLO 救回漏识（避免误插，用较高阈值）
        private const val YOLO_RESCUE_CONF = 0.55f
        //   suppress：YOLO 高置信判空，且 layout 是弱棋子 → 抑制误报
        private const val YOLO_SUPPRESS_CONF = 0.70f
        //   单图最多保留的检测框
        private const val YOLO_MAX_DETS = 64
        // YOLO 输入尺寸（模型固定 640）
        private const val YOLO_INPUT_SIZE = 640
        // 是否启用 YOLO 辅助（可一键关停）
        const val YOLO_AUX_ENABLED = true

        // 透视变换目标尺寸
        private const val BOARD_W = 450
        private const val BOARD_H = 500
        private const val BOARD_PADDING = 50

        // ImageNet 归一化参数
        private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
        private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)

        // 16 类标签
        // 0: 空点, 1: 遮挡, 2-8: 红方, 9-15: 黑方
        private val CLASS_TO_PIECE = arrayOf(
            null to null,                       // 0: 空点
            null to null,                       // 1: 遮挡
            PieceType.KING to PieceColor.RED,    // 2: red_king
            PieceType.ADVISOR to PieceColor.RED, // 3: red_advisor
            PieceType.ELEPHANT to PieceColor.RED,// 4: red_bishop
            PieceType.KNIGHT to PieceColor.RED,  // 5: red_knight
            PieceType.ROOK to PieceColor.RED,    // 6: red_rook
            PieceType.CANNON to PieceColor.RED,  // 7: red_cannon
            PieceType.PAWN to PieceColor.RED,    // 8: red_pawn
            PieceType.KING to PieceColor.BLACK,   // 9: black_king
            PieceType.ADVISOR to PieceColor.BLACK,// 10: black_advisor
            PieceType.ELEPHANT to PieceColor.BLACK,// 11: black_bishop
            PieceType.KNIGHT to PieceColor.BLACK, // 12: black_knight
            PieceType.ROOK to PieceColor.BLACK,   // 13: black_rook
            PieceType.CANNON to PieceColor.BLACK, // 14: black_cannon
            PieceType.PAWN to PieceColor.BLACK    // 15: black_pawn
        )

        // ── 位置约束常量 ──
        // 士的合法位置（九宫格对角线交点）
        val RED_ADVISOR_POS = setOf(
            Pair(7, 3), Pair(7, 5), Pair(8, 4), Pair(9, 3), Pair(9, 5)
        )
        val BLACK_ADVISOR_POS = setOf(
            Pair(0, 3), Pair(0, 5), Pair(1, 4), Pair(2, 3), Pair(2, 5)
        )
        // 相象的合法位置（田字格对角线点，不过河）
        val RED_ELEPHANT_POS = setOf(
            Pair(5, 2), Pair(5, 6), Pair(7, 0), Pair(7, 4), Pair(7, 8),
            Pair(9, 2), Pair(9, 6)
        )
        val BLACK_ELEPHANT_POS = setOf(
            Pair(0, 2), Pair(0, 6), Pair(2, 0), Pair(2, 4), Pair(2, 8),
            Pair(4, 2), Pair(4, 6)
        )
        // 帅将的合法位置（九宫格内）
        val RED_KING_POS = setOf(
            Pair(7, 3), Pair(7, 4), Pair(7, 5),
            Pair(8, 3), Pair(8, 4), Pair(8, 5),
            Pair(9, 3), Pair(9, 4), Pair(9, 5)
        )
        val BLACK_KING_POS = setOf(
            Pair(0, 3), Pair(0, 4), Pair(0, 5),
            Pair(1, 3), Pair(1, 4), Pair(1, 5),
            Pair(2, 3), Pair(2, 4), Pair(2, 5)
        )
        const val EMPTY_THRESHOLD = 0.3f
        const val PIECE_THRESHOLD = 0.15f

        // ★★★ 智能叠加开关（第1层：自动棋盘外沿净化）
        // 针对"棋盘旁摆放的挑出来的子 + 棋盘旁写的汉字"等外围干扰，
        // 在透视变换前，用关键点四角确定棋盘范围，把棋盘外像素淡化。
        // 可一键置 false 完全关闭此增强，不影响原有识别。
        const val AUTO_OUTSIDE_CLEAN_ENABLED = true
        // 向外淡化到多远的比例（相对棋盘对角线），0.18 ≈ 棋盘外延扩 18%
        const val OUTSIDE_CLEAN_MARGIN = 0.20f
        // 淡化透明度的上限：1.0=完全抹成棋盘底色，0.0=不淡化
        const val OUTSIDE_CLEAN_MAX_ALPHA = 0.85f

        // ★★★ 智能叠加（第2层）：透视"提角"多视角 + 共识加固（"准度叠加"）
        //
        //   ★ 实测结论（务必保留这段说明，防止后人再走回头路）：
        //   原先用的是"整幅仿射缩放"（Matrix.postScale 横拉/纵拉）。但识别流水线紧接着
        //   会把检测到的四角**透视矫正到固定的 450×500 标准矩形** —— 缩放被这一步原样
        //   抵消，模型每张图看到的棋盘几何几乎一模一样，逐格结论 100% 相同（实测：缩放
        //   1.1 倍后与原图"不同格子数 = 0"）。这就是用户说的"灯下黑"：拉伸来拉伸去，
        //   都落在同一个盲区里，永远投同一票。
        //
        //   真正的破法（用户思路）：不动原图，改**透视变换的目标四边形** —— 把目标矩形
        //   的一个角"往上提一下/往外拉一下"，横纵两个方向的曲度同时大变。这才是真正的
        //   不同视角（实测：能产生与原图不同的逐格结论，而仿射缩放做不到）。
        //
        //   纯叠加：可一键置 false 完全关闭，关掉后与原有识别逐字节一致（不猴子掰玉米）。
        const val PERSPECTIVE_STACK_ENABLED = true
        /** "提角"幅度：0.12 = 目标矩形边长的 12%（用户强调"不能拉得太离谱"，故取中等幅度） */
        const val PERSPECTIVE_PULL = 0.12f
        /** 提角前先把目标矩形整体缩到该比例，给"提角"留出安全边距，避免棋盘被裁出画外 */
        const val PERSPECTIVE_SHRINK = 0.94f
        /** 共识投票：只采信评分不低于"最佳分 − 该裕度"的视角（滤掉旋转路径的垃圾结果） */
        const val CONSENSUS_SCORE_MARGIN = 12
        /** 共识投票：改判某格所需的最低加权支持率 */
        const val CONSENSUS_MIN_SHARE = 0.60f
        /** 共识投票：至少要有这么多个可信视角才允许改判 */
        const val CONSENSUS_MIN_VIEWS = 3
        /**
         * 共识投票：基准视角"非常确信"的格子直接锁定，不受任何视角改判。
         * 实测：模型对某些子（如本盘的"车/炮"）置信度高达 0.9+ 且对各种形变都稳定，
         * 此时多视角分歧多半是"提角"造成的格子错位假象，必须锁死以免越改越差。
         */
        const val CONSENSUS_LOCK_CONF = 0.80f
        /**
         * ★★★ 合法性先验表决（破解"灯下黑"顽固误判的关键一步）★★★
         *
         * 实测结论（务必保留，防止后人再走回头路）：
         *   "透视提角"多视角对某些子会**众口一词**——本盘 48 个视角里 48 票都投"车"，
         *   纯多数票永远翻不动（这正是用户说的"灯下黑"：拉伸来拉伸去都落在同一盲区）。
         *   但"同一方出现 3 个车"在象棋里**物理上不可能**（车/炮/马各≤2、士/象≤2、兵/卒≤5、
         *   将/帅≤1）。所以：只要基准盘面出现"超限"，就说明必然存在读错的格 → 用合法性
         *   先验把"必须改判"这件事**确定下来**，就不再依赖多数票是否愿意翻。
         *
         *   改成什么？只在"自身盘面完全合法的干净视角"里，对该超限类**置信度最低**的格做
         *   置信度加权表决，取能消除超限的那个类别。实测：红方 3 个车中，置信度仅 0.31 的
         *   那格被干净视角以 13.02 : 1.11 判为"炮"，改判后盘面合法，正好修掉用户的争议子。
         *
         *   纯叠加：置 false 即完全关闭，关掉后与原有共识加固逐字节一致（不猴子掰玉米）。
         */
        const val LEGAL_PRIOR_ENABLED = true
        /** 合法性表决：改判类的票权须超过"原类在该格干净视角票权"的该倍数，才敢翻 */
        const val LEGAL_PRIOR_MARGIN = 1.5f
        /** 每方各类上限（下标=布局模型类号 0..15；0空/1遮 为0）：红2-8、黑9-15 */
        private val CLASS_MAX = intArrayOf(
            0, 0,
            1, 2, 2, 2, 2, 2, 5,   // 红 2..8 帅仕相马车炮兵
            1, 2, 2, 2, 2, 2, 5    // 黑 9..15 将士象马车炮卒
        )
    }

    private val ortEnv = OrtEnvironment.getEnvironment()
    private var poseSession: OrtSession? = null
    private var layoutSession: OrtSession? = null
    // ★ YOLO 辅助识别会话（可选加载，失败不影响主流程）
    private var yoloSession: OrtSession? = null
    private var initialized = false

    // 保存最近一次关键点检测的平均置信度
    private var lastKeypointConfidence: Float = 0f

    /**
     * ★★★ 最近一次完整管线识别是否应用了上下翻转
     *
     * detectAndApplyFlip 的语义：物理棋盘红方在上（红帅row<=4）时翻转成
     * "红下黑上"的标准朝向输出。因此：
     *   lastFlipApplied == true  → 物理棋盘是红上黑下（翻转局面）
     *   lastFlipApplied == false → 物理棋盘是红下黑上（标准局面）
     *
     * 连线模式（FastBoardMonitor）用它自动同步 ShadowChessConfig.targetFlipped，
     * 保证点击坐标映射方向正确。将帅永远出不了九宫（红row 7-9、黑row 0-2），
     * 所以只要双将帅都被识别，这个判断 100% 可靠。
     */
    var lastFlipApplied: Boolean = false
        private set

    /** ★ 最近一次Pose关键点检测的平均置信度（0~1），供外部决定是否采信角点 */
    val keypointConfidence: Float
        get() = lastKeypointConfidence

    /**
     * 初始化：从 assets 复制模型到 filesDir，创建 ONNX 会话
     */
    fun initialize(): Boolean {
        if (initialized) return true
        return try {
            Log.d(TAG, "初始化 ONNX 识别器...")

            val posePath = copyAssetToFile(POSE_MODEL_NAME)
            val layoutPath = copyAssetToFile(LAYOUT_MODEL_NAME)
            // ★ 回退：默认用已验证的 yolo_model.onnx（v5）；v11 仅在 YOLO11_ENABLED=true 时启用
            val yoloPath = if (YOLO11_ENABLED) {
                runCatching { copyAssetToFile(YOLO11_MODEL_NAME) }.getOrElse { copyAssetToFile(YOLO_MODEL_NAME) }
            } else copyAssetToFile(YOLO_MODEL_NAME)

            Log.d(TAG, "模型路径: pose=$posePath, layout=$layoutPath, yolo=$yoloPath")

            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            poseSession = ortEnv.createSession(posePath, options)
            layoutSession = ortEnv.createSession(layoutPath, options)

            // ★ YOLO 辅助层：可选加载，失败仅禁用辅助、不影响主识别
            try {
                yoloSession = ortEnv.createSession(yoloPath, options)
                Log.d(TAG, "YOLO 辅助模型加载成功: ${yoloSession!!.inputNames} / ${yoloSession!!.outputNames}")
            } catch (e: Exception) {
                yoloSession = null
                Log.w(TAG, "YOLO 辅助模型加载失败（已禁用 YOLO 辅助）: ${e.message}")
            }

            Log.d(TAG, "Pose 模型输入: ${poseSession!!.inputNames}")
            Log.d(TAG, "Pose 模型输出: ${poseSession!!.outputNames}")
            Log.d(TAG, "Layout 模型输入: ${layoutSession!!.inputNames}")
            Log.d(TAG, "Layout 模型输出: ${layoutSession!!.outputNames}")

            initialized = true
            Log.d(TAG, "ONNX 识别器初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ONNX 初始化失败", e)
            false
        }
    }

    /**
     * 释放 ONNX 会话资源，重置初始化状态
     * 调用后可以重新调用 initialize() 重新初始化
     */
    fun close() {
        try {
            poseSession?.close()
            Log.d(TAG, "Pose 会话已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭 Pose 会话失败", e)
        }
        try {
            layoutSession?.close()
            Log.d(TAG, "Layout 会话已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭 Layout 会话失败", e)
        }
        poseSession = null
        layoutSession = null
        try {
            yoloSession?.close()
            Log.d(TAG, "YOLO 会话已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭 YOLO 会话失败", e)
        }
        yoloSession = null
        initialized = false
        Log.d(TAG, "ONNX 识别器已关闭")
    }

    private fun copyAssetToFile(assetName: String): String {
        val outFile = File(context.filesDir, assetName)
        if (outFile.exists() && outFile.length() > 0) {
            Log.d(TAG, "$assetName 已存在: ${outFile.absolutePath}")
            return outFile.absolutePath
        }
        Log.d(TAG, "复制 $assetName 到 ${outFile.absolutePath}")
        HeavyStore.openLocalFirst(context, "models/$assetName").use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile.absolutePath
    }

    /**
     * 直接识别 Bitmap 中的棋盘，返回角点及棋子位置
     * @param bitmap 输入的图片 Bitmap
     * @return Pair(4个棋盘角点, 可选的棋子列表)
     */
    suspend fun recognizeBitmap(bitmap: Bitmap): Pair<Array<ImageTransforms.Point>?, List<BoardRecognizer.RecognizedPiece>?> {
        if (!initialize()) {
            return null to null
        }
        return try {
            val keypoints = detectKeypoints(bitmap)
            if (keypoints == null) {
                return null to null
            }
            val attempt = tryRecognition(bitmap, "Bitmap输入")
            keypoints to attempt?.pieces
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap 识别失败", e)
            null to null
        }
    }

    /**
     * ★★★ 扩展角点 — 向外扩展10%的边距，确保棋盘完整被捕获
     */
    private fun expandCorners(
        corners: Array<ImageTransforms.Point>,
        imageW: Int,
        imageH: Int
    ): Array<ImageTransforms.Point> {
        val cx = corners.map { it.x }.average().toFloat()
        val cy = corners.map { it.y }.average().toFloat()
        return Array(4) { i ->
            val dx = corners[i].x - cx
            val dy = corners[i].y - cy
            val newX = (cx + dx * 1.10f).coerceIn(0f, imageW.toFloat())
            val newY = (cy + dy * 1.10f).coerceIn(0f, imageH.toFloat())
            ImageTransforms.Point(newX, newY)
        }
    }

    /**
     * ★★★ 内部重试方法 — 跳过日志，直接返回结果
     */
    private fun tryRecognizeWithCorners(
        bitmap: Bitmap,
        corners: Array<ImageTransforms.Point>
    ): List<BoardRecognizer.RecognizedPiece>? {
        return try {
            val sorted = validateAndSortCorners(corners)
            val warped = perspectiveTransform(bitmap, sorted)
            val (classes, confs) = classifyCellsWithConfidence(warped)
            val corrected = positionBasedCorrection(classes, confs)
            val pieces = classesToPieces(corrected).toMutableList()
            anchorKingPositions(pieces)
            validateByRules(pieces)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * ★★★ 使用已知角点识别棋盘 — 跳过Pose模型，直接用校准角点做透视变换
     *
     * 这是给FastBoardMonitor实时监控用的方法：
     * - 校准阶段已经用Pose模型检测了4个角点
     * - 实时监控时不需要每帧重新检测角点
     * - 直接用已知角点做透视变换 → Layout分类 → 棋子列表
     *
     * @param bitmap 屏幕截图（全屏，未裁剪）
     * @param corners 校准好的4个角点 [TL, TR, BL, BR]
     * @return 棋子列表或null
     */
    fun recognizeWithCorners(
        bitmap: Bitmap,
        corners: Array<ImageTransforms.Point>
    ): List<BoardRecognizer.RecognizedPiece>? {
        if (!initialize()) return null
        lastFlipApplied = false  // ★ 此路径不做翻转检测，重置状态避免误读
        return try {
            Log.i(TAG, "════════════════════════════════════")
            Log.i(TAG, "★★★ 使用校准角点识别 — 跳过Pose模型")
            Log.i(TAG, "  截图尺寸: ${bitmap.width}x${bitmap.height}")
            Log.i(TAG, "  输入角点: TL=(${corners[0].x.toInt()},${corners[0].y.toInt()}) " +
                    "TR=(${corners[1].x.toInt()},${corners[1].y.toInt()}) " +
                    "BL=(${corners[2].x.toInt()},${corners[2].y.toInt()}) " +
                    "BR=(${corners[3].x.toInt()},${corners[3].y.toInt()})")

            // ★ 借鉴P象棋：校准角点已经是正确顺序(TL,TR,BL,BR)，用相对位置验证而非图像中心
            // 旧代码用 fixKeypointOrder(corners, bitmap.width, bitmap.height) 会因棋盘不占满全屏而错乱角点
            val sortedCorners = validateAndSortCorners(corners)
            Log.i(TAG, "  验证后角点: TL=(${sortedCorners[0].x.toInt()},${sortedCorners[0].y.toInt()}) " +
                    "TR=(${sortedCorners[1].x.toInt()},${sortedCorners[1].y.toInt()}) " +
                    "BL=(${sortedCorners[2].x.toInt()},${sortedCorners[2].y.toInt()}) " +
                    "BR=(${sortedCorners[3].x.toInt()},${sortedCorners[3].y.toInt()})")

            // 透视变换
            val warpedBoard = perspectiveTransform(bitmap, sortedCorners)
            Log.i(TAG, "  透视变换完成: ${warpedBoard.width}x${warpedBoard.height}")

            // Layout 分类
            val (cellClasses, cellConfidences) = classifyCellsWithConfidence(warpedBoard)

            // 日志：原始分类结果
            logRawClassification(cellClasses, cellConfidences)

            // 像素密度检测
            val piecePresence = detectPiecePresence(warpedBoard)
            logPiecePresenceCheck(cellClasses, cellConfidences, piecePresence)

            // ★★★ 智能叠加（第3层）：笔画/字形校验（**追加层**）—— 校准角点路径同样享用
            var layoutClasses = cellClasses
            var layoutConfs = cellConfidences
            if (GlyphStrokeVerifier.ENABLED) {
                try {
                    val g = GlyphStrokeVerifier.verify(warpedBoard, cellClasses, cellConfidences)
                    if (g != null) {
                        layoutClasses = g.classes
                        layoutConfs = g.confs
                        Log.i(TAG, "★ 笔画校验改判 ${g.changes.size} 处：${g.changes.joinToString("; ")}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "笔画校验异常，忽略: ${e.message}", e)
                }
            }

            // ★★★ 像素密度验证（金基准层，仅“校准角点”这条连线专用路径启用）：
            //   模型说有子、但该格像素密度判定为空、且置信度不高(<0.5)时强制置空。
            //   专治“平白无故多出来一颗子”（如底角偶尔冒出的兵）。摆子自身的识别入口
            //   不走这里，故不影响摆子的既有质量。
            val densityVerified = verifyWithPixelDensity(layoutClasses, layoutConfs, piecePresence)

            // 位置纠错
            val correctedClasses = positionBasedCorrection(densityVerified, layoutConfs)

            // 转棋子列表
            val pieces = classesToPieces(correctedClasses).toMutableList()
            Log.i(TAG, "  分类→棋子: ${pieces.size}个")

            // 将帅锚定
            anchorKingPositions(pieces)

            // 规则校验
            val validatedPieces = validateByRules(pieces)

            Log.i(TAG, "★★★ 识别完成: ${validatedPieces.size}个棋子")
            logFinalBoard(validatedPieces)
            Log.i(TAG, "════════════════════════════════════")

            // ★★★ 回退机制：如果识别结果偏少（<18个棋子），尝试用扩展角点重试
            if (validatedPieces.size < 18 && validatedPieces.size > 0) {
                Log.w(TAG, "⚠ 识别结果偏少(${validatedPieces.size}子)，尝试扩展角点重试...")
                val expandedCorners = expandCorners(corners, bitmap.width, bitmap.height)
                val retryResult = tryRecognizeWithCorners(bitmap, expandedCorners)
                if (retryResult != null && retryResult.size > validatedPieces.size) {
                    Log.i(TAG, "★★★ 扩展角点重试成功: ${retryResult.size}个棋子 (之前${validatedPieces.size}个)")
                    return retryResult
                }
                Log.w(TAG, "  扩展角点重试未改善: ${retryResult?.size ?: 0}个棋子")
            }

            validatedPieces
        } catch (e: Exception) {
            Log.e(TAG, "★★★ 使用已知角点识别失败: ${e.message}", e)
            null
        }
    }

    /**
     * ★ 借鉴P象棋的角点验证方法 — 基于角点之间的相对位置排序
     *
     * P象棋的 calibrationTopLeft/TopRight/BottomRight/BottomLeft 存储4个角点，
     * 运行时直接用 Matrix4 透视变换，不做基于图像中心的"修正"。
     *
     * 本方法用角点质心(centroid)作为参考点，按象限分配TL/TR/BL/BR：
     * - 质心左上方 → TL (左上)
     * - 质心右上方 → TR (右上)
     * - 质心左下方 → BL (左下)
     * - 质心右下方 → BR (右下)
     *
     * 这种方法不依赖图像尺寸，无论棋盘占屏幕10%还是100%都能正确排序。
     */
    private fun validateAndSortCorners(corners: Array<ImageTransforms.Point>): Array<ImageTransforms.Point> {
        require(corners.size == 4) { "需要4个角点" }

        // 计算4个角点的质心
        val cx = corners.map { it.x }.average().toFloat()
        val cy = corners.map { it.y }.average().toFloat()

        // 按象限分配：以质心为原点
        val sorted = Array(4) { ImageTransforms.Point(0f, 0f) }
        val used = BooleanArray(4) { false }

        // 目标象限: TL(左上), TR(右上), BL(左下), BR(右下)
        val targetQuadrants = arrayOf(
            Pair(-1, -1), // TL: x < cx, y < cy
            Pair(1, -1),  // TR: x > cx, y < cy
            Pair(-1, 1),  // BL: x < cx, y > cy
            Pair(1, 1)    // BR: x > cx, y > cy
        )

        for (targetIdx in 0 until 4) {
            val (dxSign, dySign) = targetQuadrants[targetIdx]
            var bestDist = Float.MAX_VALUE
            var bestSrcIdx = -1

            for (srcIdx in 0 until 4) {
                if (used[srcIdx]) continue
                val p = corners[srcIdx]
                val dx = p.x - cx
                val dy = p.y - cy

                // 检查是否在目标象限
                val matchesQuadrant = (dx * dxSign >= 0) && (dy * dySign >= 0)
                if (matchesQuadrant) {
                    val dist = dx * dx + dy * dy
                    if (dist < bestDist) {
                        bestDist = dist
                        bestSrcIdx = srcIdx
                    }
                }
            }

            if (bestSrcIdx >= 0) {
                sorted[targetIdx] = corners[bestSrcIdx]
                used[bestSrcIdx] = true
            }
        }

        // 处理未分配的角点（退化情况：角点在质心上）
        for (i in 0 until 4) {
            if (!used[i]) {
                for (j in 0 until 4) {
                    if (sorted[j].x == 0f && sorted[j].y == 0f) {
                        sorted[j] = corners[i]
                        used[i] = true
                        break
                    }
                }
            }
        }

        return sorted
    }

    /**
     * ★★★ 依 RTMPose 语义标签精修角点顺序 —— 借鉴"象棋笔记"神经网络角点方案
     *
     * pose.onnx 输出的 4 个关键点带语义：A0=棋盘左上、A8=棋盘右上、J0=棋盘左下、J8=棋盘右下。
     * 象棋笔记之所以对正方/长方/梯形/平行四边形都能定准四角，核心是【直接采信模型语义标签】，
     * 而不是按"到图像四角距离 / 质心象限"重排 —— 后两者在棋盘不占满画面、偏位、梯形变形时反而会错序。
     *
     * 处理流程：
     *  1. 几何自洽校验（仅相对 4 点自身判断，与图像位置无关）：
     *     - topAboveBottom 上排在下排上方：max(A0.y, A8.y) <= min(J0.y, J8.y)
     *     - rowOrder       每排左右正确：A0.x <= A8.x 且 J0.x <= J8.x
     *     - leftSideLeft   左列在左：左列中点 x <= 右列中点 x
     *  2. 自洽 → 直接按 [A0, A8, J0, J8] = [TL, TR, BL, BR] 采信返回。
     *  3. 不自洽（模型自身标签乱序）→ 回退到质心象限排序兜底纠错。
     *
     * @return [TL, TR, BL, BR]
     */
    private fun refinePoseCorners(
        keypoints: Array<ImageTransforms.Point>
    ): Array<ImageTransforms.Point> {
        require(keypoints.size == 4) { "需要4个关键点" }
        val a0 = keypoints[0]  // 棋盘左上
        val a8 = keypoints[1]  // 棋盘右上
        val j0 = keypoints[2]  // 棋盘左下
        val j8 = keypoints[3]  // 棋盘右下

        // 几何自洽判定（抗梯形 / 平行四边形 / 偏位 / 不占满画面）
        val topAboveBottom = maxOf(a0.y, a8.y) <= minOf(j0.y, j8.y)
        val rowOrder = a0.x <= a8.x && j0.x <= j8.x
        val leftSideLeft = (a0.x + j0.x) <= (a8.x + j8.x)

        if (topAboveBottom && rowOrder && leftSideLeft) {
            // 语义顺序自洽 → 直接采信模型标签（这是象棋笔记定准四角的根本）
            Log.d(TAG, "  [角点精修] RTMPose语义标签自洽，直接采信 [左上,右上,左下,右下]")
            return arrayOf(a0, a8, j0, j8)
        }

        Log.w(TAG, "  [角点精修] RTMPose语义标签几何不自洽，回退质心象限排序纠错: " +
                "topAboveBottom=$topAboveBottom, rowOrder=$rowOrder, leftSideLeft=$leftSideLeft")
        return validateAndSortCorners(keypoints)
    }

    /**
     * 日志：输出原始分类结果（90格）
     */
    private fun logRawClassification(classes: IntArray, confidences: FloatArray) {
        val pieceCount = classes.count { it >= 2 }
        Log.i(TAG, "  原始分类: ${pieceCount}个棋子, 平均置信度=${"%.3f".format(confidences.filter { it > 0 }.average())}")

        // 输出10x9网格
        val sb = StringBuilder("\n  ┌─────────────────────────────────┐\n")
        for (row in 0 until 10) {
            sb.append("  │")
            for (col in 0 until 9) {
                val idx = row * 9 + col
                val cls = classes[idx]
                val conf = confidences[idx]
                val symbol = if (cls < 2) {
                    if (cls == 0) " ·" else " ?"
                } else {
                    pieceShortName(cls)
                }
                sb.append(symbol)
                if (conf < 0.15f && cls >= 2) sb.append("!") else sb.append(" ")
            }
            sb.append("│\n")
        }
        sb.append("  └─────────────────────────────────┘")
        Log.i(TAG, sb.toString())
    }

    private fun pieceShortName(cls: Int): String = when (cls) {
        2 -> "帅"; 3 -> "仕"; 4 -> "相"; 5 -> "马"; 6 -> "车"; 7 -> "炮"; 8 -> "兵"
        9 -> "将"; 10 -> "士"; 11 -> "象"; 12 -> "马"; 13 -> "车"; 14 -> "炮"; 15 -> "卒"
        else -> " ?"
    }

    /**
     * 日志：输出最终棋盘布局
     */
    private fun logFinalBoard(pieces: List<BoardRecognizer.RecognizedPiece>) {
        val board = Array(10) { Array(9) { "·" } }
        for (p in pieces) {
            if (p.row in 0..9 && p.col in 0..8) {
                val name = when (p.type) {
                    PieceType.KING -> if (p.color == PieceColor.RED) "帅" else "将"
                    PieceType.ADVISOR -> if (p.color == PieceColor.RED) "仕" else "士"
                    PieceType.ELEPHANT -> if (p.color == PieceColor.RED) "相" else "象"
                    PieceType.KNIGHT -> "马"
                    PieceType.ROOK -> "车"
                    PieceType.CANNON -> "炮"
                    PieceType.PAWN -> if (p.color == PieceColor.RED) "兵" else "卒"
                }
                board[p.row][p.col] = name
            }
        }
        val sb = StringBuilder("\n  最终棋盘:\n  ┌───────────────────┐\n")
        for (row in 0 until 10) {
            sb.append("  │")
            for (col in 0 until 9) {
                sb.append(board[row][col])
            }
            sb.append("│\n")
        }
        sb.append("  └───────────────────┘")
        Log.i(TAG, sb.toString())
    }

    /**
     * 仅检测棋盘四角（用于校准，速度更快）
     * @param bitmap 屏幕截图
     * @return 4个角点 [TL, TR, BL, BR] 或 null
     */
    fun detectBoardCorners(bitmap: Bitmap): Array<ImageTransforms.Point>? {
        if (!initialize()) return null
        return try {
            Log.i(TAG, "════ ONNX 棋盘角点检测 ════")
            Log.i(TAG, "图片尺寸: ${bitmap.width}x${bitmap.height}")

            // 尝试原图：先按训练一致的 padding(1.25)，若置信度不理想再试 1.5，取更优者
            var keypoints = detectKeypoints(bitmap, POSE_PADDING)
            var bestConf = if (keypoints != null) lastKeypointConfidence else -1f
            if (bestConf < POSE_PADDING_GOOD_CONF) {
                val alt = detectKeypoints(bitmap, POSE_PADDING_ALT)
                if (alt != null && lastKeypointConfidence > bestConf) {
                    keypoints = alt
                    bestConf = lastKeypointConfidence
                }
            }
            if (keypoints != null) {
                val fixed = refinePoseCorners(keypoints)
                Log.i(TAG, "★ 原图检测成功(置信度=${"%.3f".format(bestConf)}): ${fixed.map { "(${it.x.toInt()},${it.y.toInt()}" }}")
                return fixed
            }

            // 原图失败 → 尝试裁剪
            val cropped = autoCropBoardRegion(bitmap)
            if (cropped != null && cropped.width != bitmap.width) {
                val cropKeypoints = detectKeypoints(cropped)
                if (cropKeypoints != null) {
                    // 需要把裁剪后图片上的坐标映射回原图
                    // autoCropBoardRegion 返回的是 createBitmap(bitmap, cropX, cropY, cropW, cropH)
                    // 我们不知道 cropX/cropY，但可以用 autoCropBoardRegion 返回的尺寸推算
                    // 实际上这里需要重新实现裁剪以获取偏移量
                    Log.w(TAG, "裁剪后检测到角点，但无法映射回原图坐标")
                }
            }

            Log.w(TAG, "★ ONNX 棋盘角点检测失败")
            null
        } catch (e: Exception) {
            Log.e(TAG, "角点检测异常", e)
            null
        }
    }

    /**
     * 裁剪并检测角点 — 返回映射回原图的坐标
     * @param bitmap 屏幕截图
     * @return 4个角点 [TL, TR, BL, BR] 或 null
     */
    fun detectBoardCornersWithCrop(bitmap: Bitmap): Array<ImageTransforms.Point>? {
        if (!initialize()) return null
        return try {
            // 1. 先尝试原图
            val direct = detectBoardCorners(bitmap)
            if (direct != null) return direct

            // 2. 尝试裁剪
            Log.i(TAG, "原图检测失败，尝试裁剪后检测...")

            // 计算裁剪区域（复用 autoCropBoardRegion 的逻辑但记录偏移）
            val w = bitmap.width
            val h = bitmap.height
            val smallW = 200
            val smallH = (h.toFloat() / w * smallW).toInt()
            val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)

            val gray = IntArray(smallW * smallH)
            val pixels = IntArray(smallW * smallH)
            small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
            for (i in pixels.indices) {
                val r = Color.red(pixels[i])
                val g = Color.green(pixels[i])
                val b = Color.blue(pixels[i])
                gray[i] = (r * 299 + g * 587 + b * 114) / 1000
            }

            val gradient = IntArray(smallW * smallH) { 0 }
            for (y in 1 until smallH - 1) {
                for (x in 1 until smallW - 1) {
                    val idx = y * smallW + x
                    val gx = abs(gray[idx + 1] - gray[idx - 1])
                    val gy = abs(gray[idx + smallW] - gray[idx - smallW])
                    gradient[idx] = gx + gy
                }
            }

            val rowProj = IntArray(smallH)
            val colProj = IntArray(smallW)
            for (y in 0 until smallH) {
                var sum = 0
                for (x in 0 until smallW) sum += gradient[y * smallW + x]
                rowProj[y] = sum
            }
            for (x in 0 until smallW) {
                var sum = 0
                for (y in 0 until smallH) sum += gradient[y * smallW + x]
                colProj[x] = sum
            }

            val rowAvg = rowProj.average()
            val colAvg = colProj.average()
            val rowThreshold = rowAvg * 1.3
            val colThreshold = colAvg * 1.3

            var rowStart = 0; var rowEnd = smallH - 1
            var colStart = 0; var colEnd = smallW - 1
            for (y in 0 until smallH) { if (rowProj[y] > rowThreshold) { rowStart = y; break } }
            for (y in smallH - 1 downTo 0) { if (rowProj[y] > rowThreshold) { rowEnd = y; break } }
            for (x in 0 until smallW) { if (colProj[x] > colThreshold) { colStart = x; break } }
            for (x in smallW - 1 downTo 0) { if (colProj[x] > colThreshold) { colEnd = x; break } }

            val rowMargin = (rowEnd - rowStart) / 10
            val colMargin = (colEnd - colStart) / 10
            rowStart = max(0, rowStart - rowMargin)
            rowEnd = min(smallH - 1, rowEnd + rowMargin)
            colStart = max(0, colStart - colMargin)
            colEnd = min(smallW - 1, colEnd + colMargin)

            val scaleX = w.toFloat() / smallW
            val scaleY = h.toFloat() / smallH
            val cropX = (colStart * scaleX).toInt()
            val cropY = (rowStart * scaleY).toInt()
            val cropW = ((colEnd - colStart) * scaleX).toInt()
            val cropH = ((rowEnd - rowStart) * scaleY).toInt()

            if (cropW < 100 || cropH < 100 || cropX + cropW > w || cropY + cropH > h) {
                Log.w(TAG, "裁剪区域异常")
                return null
            }

            val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
            val cropKeypoints = detectKeypoints(cropped)
            if (cropKeypoints == null) {
                Log.w(TAG, "裁剪后仍检测失败")
                return null
            }

            // 映射回原图坐标（先语义精修，再平移回原图）
            val fixed = refinePoseCorners(cropKeypoints)
            val mapped = Array(4) { idx ->
                ImageTransforms.Point(fixed[idx].x + cropX, fixed[idx].y + cropY)
            }
            Log.i(TAG, "★ 裁剪检测成功(映射回原图): ${mapped.map { "(${it.x.toInt()},${it.y.toInt()})" }}")
            return mapped
        } catch (e: Exception) {
            Log.e(TAG, "裁剪角点检测异常", e)
            null
        }
    }

    /**
     * 识别棋盘
     * @param imageUri 图片 URI
     * @return 识别结果
     */
    suspend fun recognize(imageUri: Uri): BoardRecognizer.RecognitionResult {
        if (!initialize()) {
            return BoardRecognizer.RecognitionResult(
                emptyList(), "ONNX 模型初始化失败", false
            )
        }

        return try {
            Log.d(TAG, "════ ONNX 识别开始 ════")

            // 1. 加载图片
            val bitmap = loadBitmap(imageUri)
                ?: return BoardRecognizer.RecognitionResult(
                    emptyList(), "无法加载图片", false
                )
            Log.d(TAG, "图片尺寸: ${bitmap.width}x${bitmap.height}")

            // ── 深度思考：多策略识别 ──
            // 用户要求：不要赶时间，像DeepSeek一样深度思考
            // 对各种棋盘分别对待：正面、侧位(90度旋转)、棋书截图等
            //
            // 策略列表（按优先级）：
            // A. 原图直接识别（适用于大多数APP截图）
            // B. 原图旋转90度（适用于侧位棋盘）
            // C. 原图旋转-90度（另一个方向的侧位）
            // D. 自动裁剪后识别（适用于复杂背景）
            // E. 裁剪+旋转（棋书截图等）

            val strategies = mutableListOf<Triple<String, Bitmap, Float>>()
            // ★ 派生图（旋转/裁剪）识别完统一回收，压住峰值内存 —— 好给下面的拉伸多视角腾地方
            val derived = mutableListOf<Bitmap>()

            // 策略A: 原图
            strategies.add(Triple("原图", bitmap, 0f))

            // 策略B: 旋转90度（顺时针）
            val rotated90 = rotateBitmap(bitmap, 90f)
            strategies.add(Triple("旋转90°", rotated90, 0f)); derived.add(rotated90)

            // 策略C: 旋转-90度（逆时针）
            val rotated270 = rotateBitmap(bitmap, -90f)
            strategies.add(Triple("旋转-90°", rotated270, 0f)); derived.add(rotated270)

            // 策略D: 自动裁剪
            val cropped = autoCropBoardRegion(bitmap)
            if (cropped != null && cropped.width != bitmap.width) {
                strategies.add(Triple("裁剪", cropped, 0f)); derived.add(cropped)
            }

            // 对每个策略尝试识别，计算逻辑评分
            val results = mutableListOf<RecognitionAttempt>()

            for ((name, bmp, _) in strategies) {
                Log.d(TAG, "\n── 尝试策略: $name (${bmp.width}x${bmp.height}) ──")
                // ★ 借鉴"象棋笔记"神经网络实践：所有路径统一走 RTMPose 语义标签精修（refinePoseCorners）
                val attempt = tryRecognition(bmp, name, useImageCenterOrdering = true)
                if (attempt != null) {
                    results.add(attempt)
                    Log.d(TAG, "  策略[$name] 评分=${attempt.score} 棋子=${attempt.pieces.size}")
                }
            }
            derived.forEach { runCatching { it.recycle() } }

            // ★★★ 智能叠加（第2层）：透视"提角"多视角
            //   ★ 这里**不能**再用"整幅仿射缩放"：后面的透视矫正会把缩放原样抵消，
            //     实测 0 格变化（灯下黑）。必须改"透视变换的目标四边形"才有效。
            //   对原图检测一次四角，再把目标矩形的一个角分别"提一下"（上/下/左/右各来一次），
            //   每次独立矫正+分类，得到真正不同的几何视角，交给下面的共识加固表决。
            if (PERSPECTIVE_STACK_ENABLED && results.isNotEmpty()) {
                try {
                    var viewBmp: Bitmap = bitmap
                    var ownedCrop = false
                    var viewKps = detectKeypoints(bitmap)
                    if (viewKps == null) {
                        // 原图检测不到（如带黑边的截图）→ 走裁剪图兜底
                        val c = autoCropBoardRegion(bitmap)
                        if (c != null) {
                            val k = detectKeypoints(c)
                            if (k != null) { viewBmp = c; viewKps = k; ownedCrop = true }
                            else runCatching { c.recycle() }
                        }
                    }
                    if (viewKps != null) {
                        results.addAll(collectPerspectiveViews(viewBmp, viewKps))
                    } else {
                        Log.d(TAG, "透视提角多视角：原图角点检测失败，跳过")
                    }
                    if (ownedCrop) runCatching { viewBmp.recycle() }
                } catch (e: Exception) {
                    Log.e(TAG, "透视提角多视角失败，忽略: ${e.message}", e)
                }
            }

            if (results.isEmpty()) {
                return BoardRecognizer.RecognitionResult(
                    emptyList(), "所有策略均识别失败", false
                )
            }

            // 选出评分最高的结果作为"金基准"
            results.sortByDescending { it.score }
            val baseBest = results.first()
            // ★★★ 智能叠加（第2层）：共识加固 —— 多视角逐格表决，一致锁定、可疑改判
            val best = reinforceByConsensus(results, baseBest)
            lastFlipApplied = best.flipped
            Log.i(TAG, "\n════ 最终选择: 策略[${best.strategyName}] 评分=${best.score} ════")
            Log.i(TAG, "  识别到 ${best.pieces.size} 个棋子")

            // 对所有结果评分排序记录
            results.forEachIndexed { idx, r ->
                val tag = if (idx == 0) "★ 最佳" else "  #$idx"
                Log.i(TAG, "$tag 策略[${r.strategyName}] 评分=${r.score} 棋子=${r.pieces.size} ${r.issues}")
            }

            val msg = buildResultMessage(best.pieces)
            BoardRecognizer.RecognitionResult(best.pieces, msg, true)
        } catch (e: Exception) {
            Log.e(TAG, "ONNX 识别失败", e)
            BoardRecognizer.RecognitionResult(
                emptyList(), "识别失败: ${e.message}", false
            )
        }
    }

    // ── 旋转Bitmap ──
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ── 单次识别尝试 ──
    private data class RecognitionAttempt(
        val strategyName: String,
        val pieces: List<BoardRecognizer.RecognizedPiece>,
        val score: Int,
        val issues: String,
        // ★ 共识加固用：改判前的原始 90 格分类、置信度、本视角是否做了上下翻转、棋盘四角
        val cells: IntArray = IntArray(90),
        val cellConf: FloatArray = FloatArray(90),
        val flipped: Boolean = false,
        val corners: Array<ImageTransforms.Point>? = null
    )

    /**
     * 对一个Bitmap执行完整识别流程，返回评分结果
     *
     * 流程：关键点检测 → 修正 → 透视变换 → 分类 → 纠错 → 规则校验 → 翻转 → 评分
     */
    private fun tryRecognition(
        bitmap: Bitmap,
        strategyName: String,
        useImageCenterOrdering: Boolean = false,
        useYolo: Boolean = true,
        useGlyph: Boolean = true
    ): RecognitionAttempt? {
        // 1. 关键点检测
        var keypoints = detectKeypoints(bitmap)
        if (keypoints == null) {
            // 如果是原图策略，尝试裁剪后检测
            if (strategyName == "原图") {
                val croppedBmp = autoCropBoardRegion(bitmap)
                if (croppedBmp != null) {
                    try {
                        keypoints = detectKeypoints(croppedBmp)
                        if (keypoints != null) {
                            // 使用裁剪后的图片
                            return tryRecognitionInternal(
                                croppedBmp, keypoints, strategyName + "(裁剪)", useImageCenterOrdering, useYolo, useGlyph
                            )
                        }
                    } finally {
                        // ★ 释放 croppedBmp — 检测完成后不再需要
                        croppedBmp.recycle()
                    }
                }
            }
            Log.d(TAG, "  [$strategyName] 关键点检测失败")
            return null
        }

        // ★★★ 智能叠加（第1层）：自动棋盘外沿净化
        // 用关键点四角把棋盘旁的外置子/旁字淡化。
        // ★ 绝不"猴子掰玉米"：净化只在"确实更优"时采信。
        //   先跑-净化图-和-原图-两条路径，比较逻辑评分，取分高者返回；
        //   这样即使某张图（如竹林、复杂背景）被净化误伤了，也一定会回退到
        //   与"金基准"一致的原图结果，新增增强永远不会拖累原有识别。
        var cleanedResult: RecognitionAttempt? = null
        if (AUTO_OUTSIDE_CLEAN_ENABLED) {
            val cleaned = cleanOutsideBoard(bitmap, keypoints)
            if (cleaned != null) {
                try {
                    cleanedResult = tryRecognitionInternal(
                        cleaned, keypoints, strategyName + "(净化)", useImageCenterOrdering, useYolo, useGlyph = false
                    )
                } finally {
                    cleaned.recycle()
                }
            }
        }
        val cleanEnabled = AUTO_OUTSIDE_CLEAN_ENABLED
        val originalResult = tryRecognitionInternal(bitmap, keypoints, strategyName, useImageCenterOrdering, useYolo, useGlyph)

        // 择优：净化结果与金基准原图结果比较，分高者胜；平分优先原图（最稳）。
        return when {
            !cleanEnabled || cleanedResult == null -> originalResult
            originalResult == null -> cleanedResult
            cleanedResult.score > originalResult.score -> cleanedResult
            else -> originalResult
        }
    }

    /**
     * ★★★ 智能叠加（第1层）：自动棋盘外沿净化
     *
     * 针对"棋盘旁摆放的挑出来的子"、"棋盘旁写的汉字"等外围干扰：
     * 用关键点四角确定棋盘范围，构造一个略微收缩的棋盘内多边形，
     * 把落在该多边形之外的像素淡化为棋盘底色附近，从而不让外围杂物
     * 干扰透视变换与整盘分类。
     *
     * 只返回一张"已净化"的新位图；失败时返回 null，调用方照常用原图，
     * 因此是纯叠加、绝对安全可回退。
     */
    private fun cleanOutsideBoard(
        bitmap: Bitmap,
        keypoints: Array<ImageTransforms.Point>
    ): Bitmap? {
        try {
            if (keypoints.size < 4) return null
            // 取归一化中心与对角线一半，决定向外的淡化边界
            val xs = keypoints.map { it.x }
            val ys = keypoints.map { it.y }
            val cx = xs.average().toFloat()
            val cy = ys.average().toFloat()
            val diag = ((
                    Math.hypot((keypoints[0].x - keypoints[2].x).toDouble(),
                        (keypoints[0].y - keypoints[2].y).toDouble()) +
                            Math.hypot((keypoints[1].x - keypoints[3].x).toDouble(),
                                (keypoints[1].y - keypoints[3].y).toDouble())) * 0.5f).toFloat()
            val margin = diag * OUTSIDE_CLEAN_MARGIN

            // 构建一个略收缩的棋盘四边形（保留棋盘本身，仅面向外淡化）
            val quad = Array(4) { i ->
                ImageTransforms.Point(
                    cx + (keypoints[i].x - cx) * (1f - 0.05f),
                    cy + (keypoints[i].y - cy) * (1f - 0.05f)
                )
            }

            val w = bitmap.width
            val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)

            // 判断点是否在棋盘多边形的外侧
            fun inside(x: Float, y: Float): Boolean {
                var inside = false
                var j = 3
                for (i in 0 until 4) {
                    val xi = quad[i].x; val yi = quad[i].y
                    val xj = quad[j].x; val yj = quad[j].y
                    if (((yi > y) != (yj > y)) &&
                        (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-6f) + xi)) {
                        inside = !inside
                    }
                    j = i
                }
                return inside
            }

            // 从棋盘四角稍向内的一个点出发，向外放射淡化：
            // 对每个像素，若它在棋盘外，则按其距离淡化到棋盘底色。
            var changed = false
            // 棋盘底色 = 四角附近的平均色（代表性采样）
            val bg = sampleBackgroundColor(px, w, h, quad)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (inside(x.toFloat() + 0.5f, y.toFloat() + 0.5f)) continue
                    // 在棋盘外，计算到棋盘边的近似距离 → 淡化强度
                    val d = min(
                        abs(x + 0.5f - cx),
                        abs(y + 0.5f - cy)
                    )
                    val t = (d / (margin + 1f)).coerceIn(0f, 1f) * OUTSIDE_CLEAN_MAX_ALPHA
                    if (t <= 0.05f) continue
                    val idx = row + x
                    val c = px[idx]
                    val r = ((c shr 16 and 0xFF) * (1 - t) + (bg shr 16 and 0xFF) * t).toInt()
                    val g = ((c shr 8 and 0xFF) * (1 - t) + (bg shr 8 and 0xFF) * t).toInt()
                    val b = ((c and 0xFF) * (1 - t) + (bg and 0xFF) * t).toInt()
                    px[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    changed = true
                }
            }
            if (!changed) {
                Log.d(TAG, "  外沿净化：无变化，跳过")
                return null
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            Log.d(TAG, "  外沿净化完成 (${w}x${h})")
            return out
        } catch (e: Exception) {
            Log.e(TAG, "外沿净化失败: ${e.message}", e)
            return null
        }
    }

    /** 采样棋盘四角附近的平均背景色，用于淡化填充 */
    private fun sampleBackgroundColor(
        px: IntArray, w: Int, h: Int,
        quad: Array<ImageTransforms.Point>
    ): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val offsets = intArrayOf(0, 4) // 距角点的内外偏移采样
        for (i in 0 until 4) {
            for (off in offsets) {
                val dirX = (quad[i].x - quad[(i + 1) % 4].x + quad[i].x - quad[(i + 3) % 4].x) * 0.5f
                val dirY = (quad[i].y - quad[(i + 1) % 4].y + quad[i].y - quad[(i + 3) % 4].y) * 0.5f
                val len = Math.hypot(dirX.toDouble(), dirY.toDouble()).toFloat() + 1e-6f
                val sx = (quad[i].x + dirX / len * 4f).toInt().coerceIn(0, w - 1)
                val sy = (quad[i].y + dirY / len * 4f).toInt().coerceIn(0, h - 1)
                val c = px[sy * w + sx]
                r += (c shr 16 and 0xFF); g += (c shr 8 and 0xFF); b += (c and 0xFF); n++
            }
        }
        if (n == 0) return 0xFFF5F0E1.toInt()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun tryRecognitionInternal(
        bitmap: Bitmap,
        keypoints: Array<ImageTransforms.Point>,
        strategyName: String,
        useImageCenterOrdering: Boolean = false,
        useYolo: Boolean = true,
        useGlyph: Boolean = false
    ): RecognitionAttempt? {
        try {
            val kpConfidence = lastKeypointConfidence
            Log.d(TAG, "  [$strategyName] 关键点置信度: ${"%.4f".format(kpConfidence)}")

            // 2. 角点顺序精修
            // ★ 借鉴"象棋笔记"神经网络方案：直接采信 RTMPose 语义标签 [A0,A8,J0,J8]=[上左,上右,下左,下右]，
            //   对所有形状（正方/长方/梯形/平行四边形）及偏位/不占满画面棋盘都能定准四角；
            //   仅当模型自身标签几何不自洽时，refinePoseCorners 内部才回退质心象限排序兜底。
            //   useImageCenterOrdering 保留（区分摆子/浮窗调用路径，便于日志观察，不再参与排序）。
            Log.d(TAG, "  [角点精修] 语义标签优先；调用路径 useImageCenterOrdering=$useImageCenterOrdering")
            val fixedKeypoints = refinePoseCorners(keypoints)

            // 3. 透视变换
            val warpedBoard = perspectiveTransform(bitmap, fixedKeypoints)

            // 4. 整盘分类
            val (baseClasses, baseConfs) = classifyCellsWithConfidence(warpedBoard)

            // ★ YOLO 辅助层：在原图上检测并做保守融合（救回漏识 / 抑制弱误报）
            //   拉伸多视角走 useYolo=false（省时；YOLO 的结论已由原图路径给出）
            var cellClasses = baseClasses
            var cellConfidences = baseConfs
            if (useYolo) {
                val yoloAux = detectPiecesWithYolo(bitmap, fixedKeypoints)
                if (yoloAux != null) {
                    val fused = fuseYoloClasses(cellClasses, cellConfidences, yoloAux)
                    cellClasses = fused.first
                    cellConfidences = fused.second
                }
            }

            // 4.1 像素密度检测（只记录）
            val piecePresence = detectPiecePresence(warpedBoard)
            logPiecePresenceCheck(cellClasses, cellConfidences, piecePresence)

            val plain = buildAttempt(cellClasses, cellConfidences, fixedKeypoints, strategyName)

            // ★★★ 智能叠加（第3层）：笔画/字形校验（**追加层**，不替代原有任何判断）
            //   用户要求："精确到笔画勾勒那一块，看走笔到底怎么走"、"抓特征"、
            //   "扛不住时要把车/炮/马/卒的关键笔画拿捏出来"。
            //   这里对 warped 盘面逐格抠字面墨迹 → 抽笔画特征（孔洞/连通块/点/三段孔洞/密度）
            //   → 与多字体模板比对；只在证据充分且改判后整盘评分不降低时才采信。
            if (useGlyph && GlyphStrokeVerifier.ENABLED) {
                val g = try {
                    GlyphStrokeVerifier.verify(warpedBoard, cellClasses, cellConfidences)
                } catch (e: Exception) {
                    Log.e(TAG, "  [$strategyName] 笔画校验异常，忽略: ${e.message}", e)
                    null
                }
                warpedBoard.recycle()
                if (g != null) {
                    val glyphed = buildAttempt(g.classes, g.confs, fixedKeypoints, "$strategyName(笔画)")
                    Log.i(TAG, "★ 笔画校验改判 ${g.changes.size} 处：${g.changes.joinToString("; ")}" +
                            " | 原分=${plain.score} 笔画分=${glyphed.score}")
                    return if (glyphed.score >= plain.score) glyphed else plain
                }
                Log.d(TAG, "  [$strategyName] 笔画校验：无改判，沿用原结论")
                return plain
            }

            // ★ 释放 warpedBoard — 分类和密度检测已完成，不再需要
            warpedBoard.recycle()

            return plain
        } catch (e: Exception) {
            Log.e(TAG, "  [$strategyName] 识别异常", e)
            return null
        }
    }

    /**
     * 由 90 格分类结果走完"位置纠错 → 棋子 → 将帅锚定 → 规则校验 → 翻转 → 评分"。
     *
     * 单独抽出来，是为了让"共识加固"能拿多视角融合后的格子，重新跑**同一条**流水线
     * （同一套纠错与校验规则），保证加固结果与常规结果口径完全一致。
     */
    private fun buildAttempt(
        cellClasses: IntArray,
        cellConfidences: FloatArray,
        corners: Array<ImageTransforms.Point>,
        strategyName: String
    ): RecognitionAttempt {
        // 5. 位置纠错
        val correctedClasses = positionBasedCorrection(cellClasses, cellConfidences)

        // 6. 转换为棋子列表
        val pieces = classesToPieces(correctedClasses).toMutableList()

        // 7. 将帅锚定
        anchorKingPositions(pieces)

        // 8. 规则校验
        val validatedPieces = validateByRules(pieces)

        // 9. 翻转检测
        // ★ 记录翻转前的将帅位置，推断物理棋盘朝向（供连线模式同步点击方向）
        val redKingRaw = validatedPieces.find { it.type == PieceType.KING && it.color == PieceColor.RED }
        val blackKingRaw = validatedPieces.find { it.type == PieceType.KING && it.color == PieceColor.BLACK }
        val finalPieces = detectAndApplyFlip(validatedPieces)
        val flipped = when {
            redKingRaw != null -> redKingRaw.row <= 4   // 红帅在上半区 → 物理棋盘红上黑下
            blackKingRaw != null -> blackKingRaw.row >= 5 // 黑将在下半区 → 物理棋盘红上黑下
            else -> false
        }

        // 10. 逻辑评分
        val (score, issues) = scoreRecognition(finalPieces)

        Log.d(TAG, "  [$strategyName] 评分=$score 棋子=${finalPieces.size} 问题=$issues")

        return RecognitionAttempt(
            strategyName, finalPieces, score, issues,
            cellClasses, cellConfidences, flipped, corners
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ★★★ 智能叠加（第2层）：透视"提角"多视角 + 共识加固（"准度叠加"）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 生成"透视提角"多视角 —— 用户说的"某一个角上往上提一下"。
     *
     * 关键：**改的是透视变换的目标四边形**，不是原图。
     * 因为流水线会把检测到的四角矫正到固定矩形，若只缩放原图会被原样抵消（实测 0 格变化）。
     * 这里先把目标矩形整体缩到 [PERSPECTIVE_SHRINK]（留安全边距防止裁出画外），
     * 再对每个角沿"中心→该角"方向分别外提 / 内收 [PERSPECTIVE_PULL]，共 4×2=8 个视角。
     * 每个视角独立"矫正 + 分类"，拿到的是真正不同的几何。
     */
    private fun collectPerspectiveViews(
        bitmap: Bitmap,
        keypoints: Array<ImageTransforms.Point>
    ): List<RecognitionAttempt> {
        val out = mutableListOf<RecognitionAttempt>()
        val fixed = refinePoseCorners(keypoints)
        val stdDst = arrayOf(
            ImageTransforms.Point(BOARD_PADDING.toFloat(), BOARD_PADDING.toFloat()),
            ImageTransforms.Point((BOARD_W - BOARD_PADDING).toFloat(), BOARD_PADDING.toFloat()),
            ImageTransforms.Point(BOARD_PADDING.toFloat(), (BOARD_H - BOARD_PADDING).toFloat()),
            ImageTransforms.Point((BOARD_W - BOARD_PADDING).toFloat(), (BOARD_H - BOARD_PADDING).toFloat())
        )
        val cx = BOARD_W / 2f
        val cy = BOARD_H / 2f
        // 先整体收缩，给"提角"留边距
        val base = Array(4) { i ->
            ImageTransforms.Point(
                cx + (stdDst[i].x - cx) * PERSPECTIVE_SHRINK,
                cy + (stdDst[i].y - cy) * PERSPECTIVE_SHRINK
            )
        }
        val cornerNames = arrayOf("提左上", "提右上", "提左下", "提右下")
        for (c in 0 until 4) {
            for ((dirSign, dirName) in listOf(1f to "外提", -1f to "内收")) {
                val dst = Array(4) { base[it] }
                dst[c] = ImageTransforms.Point(
                    base[c].x + (base[c].x - cx) * PERSPECTIVE_PULL * dirSign,
                    base[c].y + (base[c].y - cy) * PERSPECTIVE_PULL * dirSign
                )
                // 一次只留一张矫正图，用完即回收（避免大图叠加 OOM）
                val bmp = try {
                    val m = ImageTransforms.getPerspectiveTransform(fixed.toList(), dst.toList())
                    ImageTransforms.warpPerspective(bitmap, m, BOARD_W, BOARD_H)
                } catch (t: Throwable) { null }
                if (bmp == null) { Log.w(TAG, "  视角[${cornerNames[c]}-$dirName] 矫正失败，跳过"); continue }
                try {
                    val (cellCls, cellConf) = classifyCellsWithConfidence(bmp)
                    val attempt = buildAttempt(cellCls, cellConf, fixed, "${cornerNames[c]}-$dirName")
                    out.add(attempt)
                    Log.d(TAG, "  视角[${cornerNames[c]}-$dirName] 评分=${attempt.score} 棋子=${attempt.pieces.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "  视角[${cornerNames[c]}-$dirName] 分类异常", e)
                } finally {
                    runCatching { bmp.recycle() }
                }
            }
        }
        return out
    }

    /** 翻转后的格子下标：(row,col) → (9-row, 8-col)，与 detectAndApplyFlip 同一套映射。 */
    private fun flipIndex(i: Int): Int {
        val row = i / 9
        val col = i % 9
        return (9 - row) * 9 + (8 - col)
    }

    /** 把某视角的 90 格朝向统一到基准视角（[base] 的 flipped 为参考朝向） */
    private fun alignToBase(a: RecognitionAttempt, base: RecognitionAttempt): IntArray {
        if (a.flipped == base.flipped) return a.cells
        return IntArray(90) { i -> a.cells[flipIndex(i)] }
    }

    /** 棋盘盘面是否"物理合法"：每方每类棋子都不超过上限（车/炮/马≤2、士/象≤2、兵/卒≤5、将/帅≤1） */
    private fun isLegalBoard(cells: IntArray): Boolean {
        val cnt = IntArray(16)
        for (c in cells) if (c in 2..15) cnt[c]++
        for (c in 2..15) if (cnt[c] > CLASS_MAX[c]) return false
        return true
    }

    /**
     * ★★★ 智能叠加（第2层）：共识加固（"准度叠加"）
     *
     * 用户思路：同一张棋盘，拉伸一下再认，某些子就变了 —— 变了的那个子就是"可疑"的，
     * 一致的子则板上钉钉。这里把多个视角（原图/旋转/裁剪/净化/横拉/纵拉/自动拉）的
     * 逐格结论拿来做加权表决：
     *   · 所有可信视角都一致的格子 → 锁定，绝不动；
     *   · 有分歧的格子 → 按"视角评分"加权投票，支持率够高才改判；
     *   · 改判后整盘重跑规则校验与评分，只有不低于原基准分才采信（绝不越改越差）。
     *
     * @param attempts 全部视角的结果
     * @param base 评分最高的那个视角（金基准）
     * @return 加固后的结果；无法改善时原样返回 base
     */
    private fun reinforceByConsensus(
        attempts: List<RecognitionAttempt>,
        base: RecognitionAttempt
    ): RecognitionAttempt {
        try {
            // 1. 选出"可信视角"：评分不低于 最佳分−裕度，且格子齐全
            val trusted = attempts.filter {
                it.score >= base.score - CONSENSUS_SCORE_MARGIN && it.cells.size == 90
            }
            if (trusted.size < CONSENSUS_MIN_VIEWS) {
                Log.d(TAG, "共识加固：可信视角仅${trusted.size}个（<${CONSENSUS_MIN_VIEWS}），不做表决")
                return base
            }

            // 2. 逐格加权表决（先把各视角的格子朝向统一到 base 的朝向）
            val fusedCells = base.cells.copyOf()
            val fusedConf = base.cellConf.copyOf()
            var changed = 0
            val changedDesc = mutableListOf<String>()
            for (i in 0 until 90) {
                val baseCls = base.cells[i]
                val votes = HashMap<Int, Int>()
                var total = 0
                var baseWeight = 0
                for (a in trusted) {
                    val cls = if (a.flipped == base.flipped) a.cells[i] else a.cells[flipIndex(i)]
                    val w = (a.score + 60).coerceAtLeast(1)   // 平移成正值，分高者权重大
                    votes[cls] = (votes[cls] ?: 0) + w
                    total += w
                    if (a === base) baseWeight = w
                }
                if (total <= 0) continue
                // 全体一致（含"都判空"）→ 板上钉钉，锁定不动
                if (votes.size <= 1) continue
                val winner = votes.maxByOrNull { it.value } ?: continue
                if (winner.key == baseCls) continue
                val share = winner.value.toFloat() / total
                if (share < CONSENSUS_MIN_SHARE) continue
                if (winner.value <= baseWeight) continue   // 基准视角自身权重最高 → 不被杂音翻掉
                fusedCells[i] = winner.key
                fusedConf[i] = 0.9f
                changed++
                changedDesc.add("(${i / 9},${i % 9}) ${pieceName(baseCls)}→${pieceName(winner.key)}")
            }
            // ★★★ 合法性先验表决：破解"多视角众口一词"的灯下黑 ★★★
            //   纯多数票对"48/48 都投车"无能为力；但"同方 3 个车"物理不可能，
            //   于是先用合法性先验把"必须改判"确定下来，再在"干净视角"里表决改成什么。
            if (LEGAL_PRIOR_ENABLED) {
                val cleanViews = trusted.filter { a ->
                    isLegalBoard(alignToBase(a, base))
                }
                val cnt = IntArray(16)
                for (c in base.cells) if (c in 2..15) cnt[c]++
                if (cleanViews.size >= CONSENSUS_MIN_VIEWS) {
                    for (c in 2..15) {
                        var excess = cnt[c] - CLASS_MAX[c]
                        if (excess <= 0) continue
                        // 该类各格：按基准置信度升序（先改最没把握的那格）
                        val idxs = (0 until 90).filter { base.cells[it] == c }
                            .sortedBy { base.cellConf[it] }
                        for (i in idxs) {
                            if (excess <= 0) break
                            val weight = HashMap<Int, Float>()
                            for (a in cleanViews) {
                                val ai = if (a.flipped == base.flipped) i else flipIndex(i)
                                val cls = a.cells[ai]
                                if (cls >= 2) weight[cls] = (weight[cls] ?: 0f) + a.cellConf[ai]
                            }
                            if (weight.isEmpty()) continue
                            val best = weight.maxByOrNull { it.value } ?: continue
                            if (best.key == c) continue
                            val selfW = weight[c] ?: 0f
                            if (best.value < selfW * LEGAL_PRIOR_MARGIN) continue
                            fusedCells[i] = best.key
                            fusedConf[i] = 0.9f
                            excess--
                            changed++
                            changedDesc.add("(${i / 9},${i % 9}) ${pieceName(c)}→${pieceName(best.key)}[合法性]")
                        }
                    }
                }
            }

            if (changed == 0) {
                Log.d(TAG, "共识加固：${trusted.size}个视角逐格表决，无不一致，锁定基准")
                return base
            }

            // 3. 用融合后的格子重跑整条流水线，只有"不比基准差"才采信
            val corners = base.corners ?: return base
            val fused = buildAttempt(fusedCells, fusedConf, corners, "共识加固")
            Log.i(TAG, "★ 共识加固：${trusted.size}个视角表决，改判${changed}格 " +
                    "[${changedDesc.joinToString(", ")}] 基准分=${base.score} 加固分=${fused.score}")
            return if (fused.score >= base.score) fused else base
        } catch (e: Exception) {
            Log.e(TAG, "共识加固失败，回退基准: ${e.message}", e)
            return base
        }
    }

    // ── 逻辑一致性评分系统 ──
    /**
     * 对识别结果进行深度逻辑评分
     *
     * 评分维度：
     * 1. 将帅存在性 (+20) — 双方将帅都在
     * 2. 将帅位置合法性 (+10) — 在九宫格内
     * 3. 棋子数量合理性 (+10) — 总数<=32
     * 4. 各类棋子不超限 (+10) — 每类不超过标准上限
     * 5. 士象位置合法性 (+10) — 士在九宫、象不过河
     * 6. 红黑方配套一致性 (+10) — 红方棋子在红方半区为主
     * 7. 无明显荒谬性 (+10) — 如象过河、士出宫等
     *
     * 扣分项：
     * - 将帅缺失 -15/个
     * - 棋子超限 -5/个
     * - 位置荒谬 -3/个
     * - 总数>32 -10
     */
    private fun scoreRecognition(pieces: List<BoardRecognizer.RecognizedPiece>): Pair<Int, String> {
        var score = 0
        val issues = mutableListOf<String>()

        // 1. 将帅存在性
        val redKing = pieces.find { it.type == PieceType.KING && it.color == PieceColor.RED }
        val blackKing = pieces.find { it.type == PieceType.KING && it.color == PieceColor.BLACK }

        if (redKing != null) {
            score += 10
        } else {
            score -= 15
            issues.add("缺红帅")
        }

        if (blackKing != null) {
            score += 10
        } else {
            score -= 15
            issues.add("缺黑将")
        }

        // 2. 将帅位置合法性
        if (redKing != null) {
            val pos = Pair(redKing.row, redKing.col)
            if (pos in RED_KING_POS) {
                score += 5
            } else {
                score -= 5
                issues.add("红帅位置异常(${redKing.row},${redKing.col})")
            }
        }

        if (blackKing != null) {
            val pos = Pair(blackKing.row, blackKing.col)
            if (pos in BLACK_KING_POS) {
                score += 5
            } else {
                score -= 5
                issues.add("黑将位置异常(${blackKing.row},${blackKing.col})")
            }
        }

        // 3. 棋子总数
        if (pieces.size <= 32) {
            score += 5
        } else {
            score -= 10
            issues.add("棋子总数${pieces.size}>32")
        }

        // 4. 各类棋子数量检查
        val maxCounts = mapOf(
            PieceType.KING to 1,
            PieceType.ADVISOR to 2,
            PieceType.ELEPHANT to 2,
            PieceType.KNIGHT to 2,
            PieceType.ROOK to 2,
            PieceType.CANNON to 2,
            PieceType.PAWN to 5
        )

        var countOk = true
        for (color in listOf(PieceColor.RED, PieceColor.BLACK)) {
            val colorName = if (color == PieceColor.RED) "红" else "黑"
            for ((type, maxCount) in maxCounts) {
                val count = pieces.count { it.type == type && it.color == color }
                if (count > maxCount) {
                    score -= 5
                    countOk = false
                    issues.add("${colorName}${pieceName(type, color)}x${count}>$maxCount")
                }
            }
        }
        if (countOk) score += 5

        // 5. 士象位置合法性
        var positionOk = true
        for (piece in pieces) {
            val pos = Pair(piece.row, piece.col)
            when {
                piece.type == PieceType.ADVISOR && piece.color == PieceColor.RED -> {
                    if (pos !in RED_ADVISOR_POS) {
                        score -= 3
                        positionOk = false
                        issues.add("红仕位置异常(${piece.row},${piece.col})")
                    }
                }
                piece.type == PieceType.ADVISOR && piece.color == PieceColor.BLACK -> {
                    if (pos !in BLACK_ADVISOR_POS) {
                        score -= 3
                        positionOk = false
                        issues.add("黑士位置异常(${piece.row},${piece.col})")
                    }
                }
                piece.type == PieceType.ELEPHANT && piece.color == PieceColor.RED -> {
                    if (pos !in RED_ELEPHANT_POS) {
                        score -= 3
                        positionOk = false
                        issues.add("红相位置异常(${piece.row},${piece.col})")
                    }
                }
                piece.type == PieceType.ELEPHANT && piece.color == PieceColor.BLACK -> {
                    if (pos !in BLACK_ELEPHANT_POS) {
                        score -= 3
                        positionOk = false
                        issues.add("黑象位置异常(${piece.row},${piece.col})")
                    }
                }
            }
        }
        if (positionOk) score += 5

        // 6. 红黑方区域一致性
        // 红方棋子主要在下半区(row>=5)，黑方主要在上半区(row<=4)
        // （开局局面下，实际中局可能有棋子过河，但大量错位说明方向错误）
        if (redKing != null && blackKing != null) {
            // 只有将帅在同一列或同一边时才检查
            if (redKing.row < blackKing.row) {
                // 红帅在上方 — 可能需要翻转
                score -= 8
                issues.add("红帅在黑方半区")
            }
        }

        // 棋子数量过多时严重扣分
        if (pieces.size > 32) {
            score -= (pieces.size - 32) * 2
        }

        // 完全没有棋子
        if (pieces.isEmpty()) {
            score = -100
            issues.add("无棋子")
        }

        val issueStr = if (issues.isEmpty()) "无问题" else issues.joinToString("; ")
        return Pair(score, issueStr)
    }

    // ── 图片加载 ──

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            var sampleSize = 1
            // ★ 提高分辨率上限到 4096，避免高分辨率截图被过度缩小
            //   之前 2048 导致 2712px 的截图被缩小到 1356px，丢失棋盘细节
            while (maxDim / sampleSize > 4096) sampleSize *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap failed", e)
            null
        }
    }

    // ── 自动裁剪棋盘区域 ──

    /**
     * 自动检测并裁剪棋盘区域
     *
     * 对于棋书截图、带UI元素的应用截图等复杂背景图片，
     * Pose模型在整图上无法正确定位棋盘四角。
     * 本方法通过边缘密度分析找到棋盘所在的大致区域。
     *
     * 原理：棋盘区域有大量网格线，边缘密度（梯度变化）远高于纯色背景。
     * 1. 缩小图片到小尺寸进行快速分析
     * 2. 计算每个像素的梯度强度
     * 3. 对行和列分别做投影（求和）
     * 4. 找到边缘密度最高的行范围和列范围 → 棋盘区域
     */
    private fun autoCropBoardRegion(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height

        // 缩小到200px宽进行快速分析
        val smallW = 200
        val smallH = (h.toFloat() / w * smallW).toInt()
        val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)

        // 转灰度 + 计算梯度
        val gray = IntArray(smallW * smallH)
        val pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)

        // ★ 释放 small — 像素数据已提取，不再需要
        small.recycle()

        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // 计算每个像素的梯度强度（Sobel近似）
        val gradient = IntArray(smallW * smallH) { 0 }
        for (y in 1 until smallH - 1) {
            for (x in 1 until smallW - 1) {
                val idx = y * smallW + x
                // 水平梯度
                val gx = abs(gray[idx + 1] - gray[idx - 1])
                // 垂直梯度
                val gy = abs(gray[idx + smallW] - gray[idx - smallW])
                gradient[idx] = gx + gy
            }
        }

        // 行投影 — 每行的梯度总和
        val rowProj = IntArray(smallH)
        for (y in 0 until smallH) {
            var sum = 0
            for (x in 0 until smallW) {
                sum += gradient[y * smallW + x]
            }
            rowProj[y] = sum
        }

        // 列投影 — 每列的梯度总和
        val colProj = IntArray(smallW)
        for (x in 0 until smallW) {
            var sum = 0
            for (y in 0 until smallH) {
                sum += gradient[y * smallW + x]
            }
            colProj[x] = sum
        }

        // 计算阈值 — 行/列平均梯度的1.5倍
        val rowAvg = rowProj.average()
        val colAvg = colProj.average()
        val rowThreshold = rowAvg * 1.3
        val colThreshold = colAvg * 1.3

        // 找到梯度高于阈值的行范围
        var rowStart = 0
        var rowEnd = smallH - 1
        for (y in 0 until smallH) {
            if (rowProj[y] > rowThreshold) {
                rowStart = y
                break
            }
        }
        for (y in smallH - 1 downTo 0) {
            if (rowProj[y] > rowThreshold) {
                rowEnd = y
                break
            }
        }

        // 找到梯度高于阈值的列范围
        var colStart = 0
        var colEnd = smallW - 1
        for (x in 0 until smallW) {
            if (colProj[x] > colThreshold) {
                colStart = x
                break
            }
        }
        for (x in smallW - 1 downTo 0) {
            if (colProj[x] > colThreshold) {
                colEnd = x
                break
            }
        }

        // 添加边距（10%）
        val rowMargin = (rowEnd - rowStart) / 10
        val colMargin = (colEnd - colStart) / 10
        rowStart = max(0, rowStart - rowMargin)
        rowEnd = min(smallH - 1, rowEnd + rowMargin)
        colStart = max(0, colStart - colMargin)
        colEnd = min(smallW - 1, colEnd + colMargin)

        // 映射回原图坐标
        val scaleX = w.toFloat() / smallW
        val scaleY = h.toFloat() / smallH
        val cropX = (colStart * scaleX).toInt()
        val cropY = (rowStart * scaleY).toInt()
        val cropW = ((colEnd - colStart) * scaleX).toInt()
        val cropH = ((rowEnd - rowStart) * scaleY).toInt()

        // 安全检查
        if (cropW < 100 || cropH < 100 || cropX + cropW > w || cropY + cropH > h) {
            Log.w(TAG, "⚠ 裁剪区域异常: crop=(${cropX},${cropY},${cropW}x${cropH})")
            return null
        }

        Log.d(TAG, "裁剪区域: (${cropX},${cropY}) ${cropW}x${cropH} (原图 ${w}x${h})")
        return Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
    }

    // ── 将帅位置锚定 ──

    /**
     * 将帅位置锚定
     *
     * 用户建议：如果一方将帅位置识别准确，可推算对方将帅位置。
     * 在中国象棋中，双方将帅各自限制在九宫格内（3x3区域）。
     * 如果已知一方将帅位置，可以：
     * 1. 确定棋盘方向（哪边是红方，哪边是黑方）
     * 2. 检查对方将帅是否在合法的九宫格范围内
     * 3. 如果对方将帅缺失，尝试在九宫格内搜索
     *
     * 飞将规则：双方将帅不能在同一列上直接对面（中间无子）。
     * 如果两个将帅在同一列，中间必须有至少一个棋子。
     */
    private fun anchorKingPositions(pieces: MutableList<BoardRecognizer.RecognizedPiece>) {
        val redKing = pieces.find { it.type == PieceType.KING && it.color == PieceColor.RED }
        val blackKing = pieces.find { it.type == PieceType.KING && it.color == PieceColor.BLACK }

        Log.d(TAG, "将帅锚定: 红帅=${redKing?.let { "(${it.row},${it.col})" } ?: "缺失"} " +
                "黑将=${blackKing?.let { "(${it.row},${it.col})" } ?: "缺失"}")

        // 检查将帅是否在合法位置
        if (redKing != null) {
            val pos = Pair(redKing.row, redKing.col)
            if (pos !in RED_KING_POS) {
                Log.w(TAG, "  ⚠ 红帅位置(${redKing.row},${redKing.col})不在合法九宫格内")
            }
        }

        if (blackKing != null) {
            val pos = Pair(blackKing.row, blackKing.col)
            if (pos !in BLACK_KING_POS) {
                Log.w(TAG, "  ⚠ 黑将位置(${blackKing.row},${blackKing.col})不在合法九宫格内")
            }
        }

        // 如果双方将帅都在，检查飞将规则
        if (redKing != null && blackKing != null) {
            if (redKing.col == blackKing.col) {
                // 同一列，检查中间是否有棋子
                val minRow = min(redKing.row, blackKing.row)
                val maxRow = max(redKing.row, blackKing.row)
                val piecesBetween = pieces.filter {
                    it.col == redKing.col && it.row > minRow && it.row < maxRow
                }
                if (piecesBetween.isEmpty()) {
                    Log.w(TAG, "  ⚠ 飞将! 红帅和黑将在同一列(${redKing.col})且中间无子，这不符合规则")
                } else {
                    Log.d(TAG, "  ✓ 将帅同列(${redKing.col})，中间有${piecesBetween.size}个棋子")
                }
            }
        }

        // 如果只有一方将帅，用其位置确定棋盘方向
        if (redKing != null && blackKing == null) {
            Log.d(TAG, "  红帅在(${redKing.row},${redKing.col})，黑将缺失")
        }
        if (blackKing != null && redKing == null) {
            Log.d(TAG, "  黑将在(${blackKing.row},${blackKing.col})，红帅缺失")
        }
    }

    // ── 关键点检测 (Pose 模型) ──

    // ─────────────────────────────────────────────────────────
    //  YOLO 辅助识别层（借鉴"象棋笔记"）
    //  在【原图】上跑 YOLO，用检测框位置对整盘分类结果做辅助融合：
    //    - 救回 layout 漏识的棋子（高置信 YOLO 命中空格/低置信格）
    //    - 抑制 layout 的弱误报（高置信 YOLO 判空）
    //  只在高置信 & 弱布局处生效，正常已判准的格子一律保持原状，绝不回退。
    // ─────────────────────────────────────────────────────────

    /** YOLO 检测框（中心 + 宽高为 letterbox 640 空间） */
    private data class YoloBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val cls: Int, val conf: Float)

    /** YOLO 辅助聚合结果 */
    private data class YoloAux(
        val classes: IntArray,  // 90，内部类号（0=空/判空）
        val confs: FloatArray   // 90，该格最高置信；<0 表示无检测
    )

    /**
     * 在【原图】上运行 YOLO 棋子检测，把每个检测框中心映射到棋盘网格 (row,col)。
     * @return YoloAux 或 null（禁用/模型缺失/失败）
     */
    private fun detectPiecesWithYolo(
        bitmap: Bitmap,
        keypoints: Array<ImageTransforms.Point>
    ): YoloAux? {
        val session = yoloSession
        if (!YOLO_AUX_ENABLED || session == null) return null
        return try {
            Log.d(TAG, "  [YOLO辅助] 开始原图检测 ${bitmap.width}x${bitmap.height}")
            val srcW = bitmap.width
            val srcH = bitmap.height
            val inputName = session.inputNames.first()

            // 1. letterbox 到 640x640
            val scale = min(YOLO_INPUT_SIZE.toFloat() / srcW, YOLO_INPUT_SIZE.toFloat() / srcH)
            val nw = max(1, (srcW * scale).toInt())
            val nh = max(1, (srcH * scale).toInt())
            val padX = (YOLO_INPUT_SIZE - nw) / 2
            val padY = (YOLO_INPUT_SIZE - nh) / 2
            val letterbox = Bitmap.createBitmap(YOLO_INPUT_SIZE, YOLO_INPUT_SIZE, Bitmap.Config.ARGB_8888)
            letterbox.eraseColor(Color.rgb(114, 114, 114))
            val canvas = android.graphics.Canvas(letterbox)
            canvas.drawBitmap(bitmap, null,
                android.graphics.Rect(padX, padY, padX + nw, padY + nh), null)

            // 2. 转 float（YOLO：0-255，RGB，不减均值）
            val inputData = yoloToFloatArray(letterbox)
            letterbox.recycle()

            val inputTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(inputData),
                longArrayOf(1, 3, YOLO_INPUT_SIZE.toLong(), YOLO_INPUT_SIZE.toLong())
            )
            val results = session.run(mapOf(inputName to inputTensor))
            inputTensor.close()
            val raw = results[0].value
            results.close()

            // 3. 解码 + NMS
            val boxes = decodeYoloBoxes(raw)

            // 4. 用 pose 四角建单应矩阵，把检测中心映射到棋盘网格
            val homo = ImageTransforms.getPerspectiveTransform(
                listOf(keypoints[0], keypoints[1], keypoints[2], keypoints[3]),
                listOf(
                    ImageTransforms.Point(BOARD_PADDING.toFloat(), BOARD_PADDING.toFloat()),
                    ImageTransforms.Point((BOARD_W - BOARD_PADDING).toFloat(), BOARD_PADDING.toFloat()),
                    ImageTransforms.Point(BOARD_PADDING.toFloat(), (BOARD_H - BOARD_PADDING).toFloat()),
                    ImageTransforms.Point((BOARD_W - BOARD_PADDING).toFloat(), (BOARD_H - BOARD_PADDING).toFloat())
                )
            )

            val classes = IntArray(90) { 0 }
            val confs = FloatArray(90) { -1f }
            for (b in boxes) {
                // letterbox → 原图
                val ox = (b.cx - padX) / scale
                val oy = (b.cy - padY) / scale
                // 原图 → warp 空间
                val m = homo.transform(ImageTransforms.Point(ox, oy))
                val col = ((m.x - BOARD_PADDING) / YOLO_COL_STEP).roundToInt()
                val row = ((m.y - BOARD_PADDING) / YOLO_ROW_STEP).roundToInt()
                if (row !in 0..9 || col !in 0..8) continue
                val idx = row * 9 + col
                val internal = if (b.cls in 0..13) YOLO_TO_CLASS[b.cls] else 0
                if (b.conf > confs[idx]) {
                    classes[idx] = internal
                    confs[idx] = b.conf
                }
            }

            val realDet = boxes.count { it.cls in 0..13 }
            Log.d(TAG, "  [YOLO辅助] 检测框=${boxes.size}（盘内命中=${classes.count { confs[it] in 0f..1f && classes[it] in 2..15 }}，有效棋子=${realDet}）")
            YoloAux(classes, confs)
        } catch (e: Exception) {
            Log.w(TAG, "  [YOLO辅助] 检测失败: ${e.message}")
            null
        }
    }

    /** 逐个像素转 YOLO 输入（RGB，0-255） */
    private fun yoloToFloatArray(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val out = FloatArray(3 * w * h)
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            out[i] = (p shr 16 and 0xFF).toFloat()        // R
            out[w * h + i] = (p shr 8 and 0xFF).toFloat() // G
            out[2 * w * h + i] = (p and 0xFF).toFloat()    // B
        }
        return out
    }

    /** 解码 yolov5 输出 (1,25200,20) + 按类 NMS，返回 letterbox 中心坐标的检测框 */
    private fun decodeYoloBoxes(value: Any?): List<YoloBox> {
        @Suppress("UNCHECKED_CAST")
        val arr = value as? Array<FloatArray> ?: return emptyList()
        val all = mutableListOf<YoloBox>()
        val transposed = arr.isNotEmpty() && arr[0].isNotEmpty() && arr[0].size > arr.size
        if (transposed) {
            // ★ YOLO11：形状 [4+15, N]（通道在前，无 objectness）→ conf 直接取类别最大分
            val n = arr[0].size
            for (i in 0 until n) {
                var bestC = -1
                var bestS = 0f
                for (c in 0 until 15) {
                    val s = arr[4 + c][i]
                    if (s > bestS) { bestS = s; bestC = c }
                }
                if (bestS < 0.25f) continue
                all.add(YoloBox(arr[0][i], arr[1][i], arr[2][i], arr[3][i], bestC, bestS))
            }
        } else {
            for (row in arr) {
                if (row.size < 20) continue
                val obj = row[4]
                var bestC = -1
                var bestS = 0f
                for (c in 0 until 15) {
                    val s = row[5 + c]
                    if (s > bestS) { bestS = s; bestC = c }
                }
                val conf = obj * bestS
                if (conf < 0.25f) continue
                all.add(YoloBox(row[0], row[1], row[2], row[3], bestC, conf))
            }
        }
        all.sortByDescending { it.conf }
        val kept = mutableListOf<YoloBox>()
        val sup = BooleanArray(all.size)
        for (i in all.indices) {
            if (sup[i]) continue
            kept.add(all[i])
            if (kept.size >= YOLO_MAX_DETS) break
            for (j in i + 1 until all.size) {
                if (sup[j]) continue
                if (all[i].cls != all[j].cls) continue
                if (yoloIou(all[i], all[j]) > 0.45f) sup[j] = true
            }
        }
        return kept
    }

    /** 从 cxcywh 计算两框 IoU */
    private fun yoloIou(a: YoloBox, b: YoloBox): Float {
        val ax1 = a.cx - a.w / 2; val ay1 = a.cy - a.h / 2
        val ax2 = a.cx + a.w / 2; val ay2 = a.cy + a.h / 2
        val bx1 = b.cx - b.w / 2; val by1 = b.cy - b.h / 2
        val bx2 = b.cx + b.w / 2; val by2 = b.cy + b.h / 2
        val ix1 = max(ax1, bx1); val iy1 = max(ay1, by1)
        val ix2 = min(ax2, bx2); val iy2 = min(ay2, by2)
        val interW = max(0f, ix2 - ix1)
        val interH = max(0f, iy2 - iy1)
        val inter = interW * interH
        val union = a.w * a.h + b.w * b.h - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * YOLO 辅助与整盘分类的融合（保守：只在高置信 YOLO 且弱布局处生效）
     */
    private fun fuseYoloClasses(
        cellClasses: IntArray,
        cellConfidences: FloatArray,
        yolo: YoloAux
    ): Pair<IntArray, FloatArray> {
        val c = cellClasses.copyOf()
        val conf = cellConfidences.copyOf()
        var rescueCount = 0
        var suppressCount = 0
        for (i in 0 until 90) {
            val yc = yolo.confs[i]
            if (yc < 0f) continue  // 该格无 YOLO 检测
            val ycls = yolo.classes[i]
            val lcls = c[i]
            val lconf = conf[i]
            // 救回：layout 空格/遮挡/低置信，且 YOLO 高置信确认是棋子
            if (ycls in 2..15 && yc >= YOLO_RESCUE_CONF &&
                (lcls == 0 || lcls == 1 || lconf < PIECE_THRESHOLD)
            ) {
                c[i] = ycls
                conf[i] = yc
                rescueCount++
            }
            // 抑制：YOLO 高置信判空，且 layout 是弱置信棋子 → 判为空
            else if (ycls == 0 && yc >= YOLO_SUPPRESS_CONF &&
                lcls >= 2 && lconf < EMPTY_THRESHOLD
            ) {
                c[i] = 0
                conf[i] = 0f
                suppressCount++
            }
        }
        if (rescueCount > 0 || suppressCount > 0) {
            Log.d(TAG, "  [YOLO辅助] 融合应用: 救回漏识=$rescueCount, 抑制误报=$suppressCount")
        }
        return c to conf
    }

    // ── 关键点检测 (Pose 模型) ──

    /**
     * RTMPose 关键点检测
     * 输入: (1, 3, 256, 256) — 整图仿射变换到 256x256
     * 输出: simcc_x (1, 4, 512), simcc_y (1, 4, 512)
     * 4个关键点: A0(左上), A8(右上), J0(左下), J8(右下)
     */
    private fun detectKeypoints(bitmap: Bitmap, padding: Float = POSE_PADDING): Array<ImageTransforms.Point>? {
        val session = poseSession ?: return null
        val srcW = bitmap.width
        val srcH = bitmap.height

        // 1. 计算 bbox = 整图, center, scale (匹配 Python RTMPOSE_ONNX.get_bbox_center_scale)
        val center = ImageTransforms.Point(srcW / 2f, srcH / 2f)
        var scaleW = srcW.toFloat() * padding
        var scaleH = srcH.toFloat() * padding

        // min scale 100
        if (scaleW < 100f) {
            scaleH = scaleH * 100f / scaleW
            scaleW = 100f
        }
        if (scaleH < 100f) {
            scaleW = scaleW * 100f / scaleH
            scaleH = 100f
        }

        // ★ aspect ratio 修正 — 把 scale 修正为正方形
        //   匹配 Python get_warp_size_with_input_size: input_size=(256,256), aspect_ratio=1.0
        //   取 scaleW 和 scaleH 的较大值，确保仿射变换不拉伸变形
        if (scaleW > scaleH) {
            scaleH = scaleW
        } else {
            scaleW = scaleH
        }
        val scale = ImageTransforms.Point(scaleW, scaleH)

        // 2. 计算 warp matrix (src -> 256x256)
        val warpMat = ImageTransforms.getWarpMatrix(center, scale, POSE_INPUT_SIZE, POSE_INPUT_SIZE, false)

        // 3. 应用仿射变换
        val warpedBitmap = ImageTransforms.warpAffine(bitmap, warpMat, POSE_INPUT_SIZE, POSE_INPUT_SIZE)

        // 4. 转换为模型输入张量 (1, 3, 256, 256)
        val inputData = bitmapToFloatArray(warpedBitmap, POSE_INPUT_SIZE, POSE_INPUT_SIZE)

        // ★ 释放 warpedBitmap — 不再需要
        warpedBitmap.recycle()

        // 5. ONNX 推理
        val inputName = session.inputNames.first()
        val inputTensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(inputData), longArrayOf(1, 3, POSE_INPUT_SIZE.toLong(), POSE_INPUT_SIZE.toLong())
        )
        val results = session.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        // 6. 提取 SimCC 输出 — shape (1, 4, 512) → 3D Java 数组
        @Suppress("UNCHECKED_CAST")
        val simccXRaw = results[0].value as Array<Array<FloatArray>> // (1, 4, 512)
        @Suppress("UNCHECKED_CAST")
        val simccYRaw = results[1].value as Array<Array<FloatArray>> // (1, 4, 512)
        val simccX = simccXRaw[0] // (4, 512)
        val simccY = simccYRaw[0] // (4, 512)
        results.close()

        // 7. SimCC 解码 — argmax + 归一化 + 置信度计算
        val keypoints = Array(4) { ImageTransforms.Point(0f, 0f) }
        val scores = FloatArray(4)
        for (i in 0 until 4) {
            val xIdx = argmax(simccX[i])
            val yIdx = argmax(simccY[i])
            // 置信度 = max(simcc_x) * max(simcc_y)（匹配 Python get_simcc_maximum）
            scores[i] = simccX[i][xIdx] * simccY[i][yIdx]
            // 归一化到 0-1 (除以 512 = 256*2)
            val nx = xIdx.toFloat() / (POSE_INPUT_SIZE * 2)
            val ny = yIdx.toFloat() / (POSE_INPUT_SIZE * 2)
            // 缩放到 256 像素空间
            val px = nx * POSE_INPUT_SIZE
            val py = ny * POSE_INPUT_SIZE
            keypoints[i] = ImageTransforms.Point(px, py)
        }

        // 8. 逆仿射变换 — 映射回原图坐标
        val invWarpMat = ImageTransforms.getWarpMatrix(center, scale, POSE_INPUT_SIZE, POSE_INPUT_SIZE, true)
        val boneNames = arrayOf("A0(左上)", "A8(右上)", "J0(左下)", "J8(右下)")
        for (i in 0 until 4) {
            val kp = keypoints[i]
            val origX = invWarpMat.a * kp.x + invWarpMat.b * kp.y + invWarpMat.c
            val origY = invWarpMat.d * kp.x + invWarpMat.e * kp.y + invWarpMat.f
            keypoints[i] = ImageTransforms.Point(
                origX.coerceIn(0f, srcW.toFloat()),
                origY.coerceIn(0f, srcH.toFloat())
            )
            Log.d(TAG, "  关键点 ${boneNames[i]}: (${keypoints[i].x.toInt()}, ${keypoints[i].y.toInt()}) 置信度=${"%.3f".format(scores[i])}")
        }

        // 9. 置信度检查 — 如果关键点置信度过低，说明可能未检测到棋盘
        val avgScore = scores.average().toFloat()
        lastKeypointConfidence = avgScore
        Log.d(TAG, "  关键点平均置信度: ${"%.3f".format(avgScore)}")
        if (avgScore < 0.05f) {
            Log.w(TAG, "⚠ 关键点置信度过低 (${"%.3f".format(avgScore)})，可能未检测到棋盘")
            return null
        }

        return keypoints
    }

    private fun argmax(arr: FloatArray): Int {
        var maxIdx = 0
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in arr.indices) {
            if (arr[i] > maxVal) {
                maxVal = arr[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    // ── 像素密度检测 ──

    /**
     * 检测每个格子是否有棋子存在
     *
     * 用户建议：棋子是圆的，看有几颗圆的就是几颗
     * 实现方式：在矫正后的棋盘图上，对每个网格交点周围的区域
     * 计算像素密度（非背景像素占比），有棋子的格子密度明显高于空格子
     *
     * @return BooleanArray(90) — true表示该位置有棋子
     */
    private fun detectPiecePresence(boardBitmap: Bitmap): BooleanArray {
        val presence = BooleanArray(90) { false }
        val w = boardBitmap.width
        val h = boardBitmap.height

        // 棋盘区域: [BOARD_PADDING, BOARD_W-BOARD_PADDING] x [BOARD_PADDING, BOARD_H-BOARD_PADDING]
        val playW = BOARD_W - 2 * BOARD_PADDING
        val playH = BOARD_H - 2 * BOARD_PADDING
        val colSpacing = playW.toFloat() / 8f  // 9列, 8个间距
        val rowSpacing = playH.toFloat() / 9f  // 10行, 9个间距

        // 棋子半径约为格距的35%
        val pieceRadius = (min(colSpacing, rowSpacing) * 0.35f).toInt()
        val sampleRadius = max(5, pieceRadius * 2 / 3)  // 采样半径

        // 获取整个棋盘的像素
        val pixels = IntArray(w * h)
        boardBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 计算整体背景色（取棋盘边角的像素作为背景参考）
        val bgSamples = mutableListOf<Int>()
        // 取4个角的像素
        for (cy in 0 until min(10, h)) {
            for (cx in 0 until min(10, w)) {
                bgSamples.add(pixels[cy * w + cx])
            }
            for (cx in max(0, w - 10) until w) {
                bgSamples.add(pixels[cy * w + cx])
            }
        }
        val avgBgR = bgSamples.map { Color.red(it) }.average().toInt()
        val avgBgG = bgSamples.map { Color.green(it) }.average().toInt()
        val avgBgB = bgSamples.map { Color.blue(it) }.average().toInt()

        // 密度阈值：与背景色差异超过30的像素算作"非背景"
        val colorThreshold = 30
        // 密度比例阈值：非背景像素占比超过15%认为有棋子
        val densityThreshold = 0.12f

        for (row in 0 until 10) {
            for (col in 0 until 9) {
                val cx = (BOARD_PADDING + col * colSpacing).toInt()
                val cy = (BOARD_PADDING + row * rowSpacing).toInt()

                var nonBgCount = 0
                var totalCount = 0

                // 在棋子位置周围采样
                val r = sampleRadius
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        // 只采样圆形区域
                        if (dx * dx + dy * dy > r * r) continue
                        val px = cx + dx
                        val py = cy + dy
                        if (px < 0 || px >= w || py < 0 || py >= h) continue

                        val pixel = pixels[py * w + px]
                        val dr = abs(Color.red(pixel) - avgBgR)
                        val dg = abs(Color.green(pixel) - avgBgG)
                        val db = abs(Color.blue(pixel) - avgBgB)
                        val colorDiff = max(dr, max(dg, db))

                        totalCount++
                        if (colorDiff > colorThreshold) {
                            nonBgCount++
                        }
                    }
                }

                val density = if (totalCount > 0) nonBgCount.toFloat() / totalCount else 0f
                presence[row * 9 + col] = density > densityThreshold

                if (presence[row * 9 + col]) {
                    Log.v(TAG, "  像素密度 ($row,$col): ${"%.2f".format(density)} → 有子")
                }
            }
        }

        val pieceCount = presence.count { it }
        Log.d(TAG, "  像素密度检测: $pieceCount 个位置有棋子")
        return presence
    }

    /**
     * 像素密度验证 — 只记录日志，不修改识别结果
     *
     * 避免误删有效棋子（竹林背景等复杂背景下背景色估计不准）
     */
    private fun logPiecePresenceCheck(
        classes: IntArray,
        confidences: FloatArray,
        piecePresence: BooleanArray
    ) {
        var missCount = 0
        var falseAlarmCount = 0

        for (i in 0 until 90) {
            val modelSaysPiece = classes[i] >= 2
            val pixelSaysPiece = piecePresence[i]

            if (!modelSaysPiece && pixelSaysPiece) {
                missCount++
                val row = i / 9
                val col = i % 9
                Log.w(TAG, "  ⚠ 疑似漏识 ($row,$col): 模型=空 但像素密度检测到棋子")
            }

            if (modelSaysPiece && !pixelSaysPiece) {
                falseAlarmCount++
                val row = i / 9
                val col = i % 9
                Log.w(TAG, "  ⚠ 疑似误识 ($row,$col): 模型=${pieceName(classes[i])} 但像素密度检测为空, conf=${"%.3f".format(confidences[i])}")
            }
        }

        if (missCount > 0 || falseAlarmCount > 0) {
            Log.i(TAG, "  像素密度验证: 疑似漏识=$missCount, 疑似误识=$falseAlarmCount")
        }
    }

    /**
     * 用像素密度验证模型分类结果
     *
     * 策略：
     * 1. 如果模型说"有子"但像素密度说"空" → 可能是误识，降低置信度
     * 2. 如果模型说"空"但像素密度说"有子" → 可能是漏识，标记为"遮挡"待后续处理
     * 3. 两者一致 → 保持不变
     */
    private fun verifyWithPixelDensity(
        classes: IntArray,
        confidences: FloatArray,
        piecePresence: BooleanArray
    ): IntArray {
        val verified = classes.copyOf()
        var missCount = 0
        var falseAlarmCount = 0

        for (i in 0 until 90) {
            val modelSaysPiece = verified[i] >= 2
            val pixelSaysPiece = piecePresence[i]

            if (!modelSaysPiece && pixelSaysPiece) {
                // 模型说空，但像素密度说有子 → 可能漏识
                // 标记为"遮挡"（类别1），让后续纠错有机会处理
                // 不直接覆盖，因为可能是误检
                missCount++
                val missRow = i / 9
                val missCol = i % 9
                Log.w(TAG, "  ⚠ 疑似漏识 ($missRow,$missCol): 模型=空 但像素密度检测到棋子")
                // 保持模型结果，但记录日志供分析
            }

            if (modelSaysPiece && !pixelSaysPiece) {
                // 模型说有子，但像素密度说空 → 可能误识
                // 如果置信度不高，更可能是误识
                if (confidences[i] < 0.5f) {
                    falseAlarmCount++
                    val farRow = i / 9
                    val farCol = i % 9
                    Log.w(TAG, "  ⚠ 疑似误识 ($farRow,$farCol): 模型=${pieceName(verified[i])} 但像素密度检测为空, conf=${"%.3f".format(confidences[i])}")
                    verified[i] = 0  // 标记为空
                }
            }
        }

        if (missCount > 0 || falseAlarmCount > 0) {
            Log.i(TAG, "  像素密度验证: 疑似漏识=$missCount, 疑似误识=$falseAlarmCount")
        }

        return verified
    }

    // ── 透视变换 ──

    /**
     * 将棋盘从透视视角矫正为俯视图 (450x500)
     * 源点: A0(左上), A8(右上), J0(左下), J8(右下)
     * 目标点: [[50,50], [400,50], [50,450], [400,450]] (padding=50)
     */
    private fun perspectiveTransform(bitmap: Bitmap, keypoints: Array<ImageTransforms.Point>): Bitmap {
        val src = keypoints.toList()
        val dst = listOf(
            ImageTransforms.Point(BOARD_PADDING.toFloat(), BOARD_PADDING.toFloat()),                         // TL
            ImageTransforms.Point((BOARD_W - BOARD_PADDING).toFloat(), BOARD_PADDING.toFloat()),             // TR
            ImageTransforms.Point(BOARD_PADDING.toFloat(), (BOARD_H - BOARD_PADDING).toFloat()),             // BL
            ImageTransforms.Point((BOARD_W - BOARD_PADDING).toFloat(), (BOARD_H - BOARD_PADDING).toFloat())  // BR
        )
        val matrix = ImageTransforms.getPerspectiveTransform(src, dst)
        return ImageTransforms.warpPerspective(bitmap, matrix, BOARD_W, BOARD_H)
    }

    // ── 棋子分类 (Layout 模型) ──

    /**
     * 整盘棋子分类（含置信度）
     * @return (classes: IntArray(90), confidences: FloatArray(90))
     */
    private fun classifyCellsWithConfidence(boardBitmap: Bitmap): Pair<IntArray, FloatArray> {
        val session = layoutSession ?: return IntArray(90) to FloatArray(90)

        // 1. Center crop 到 400x450
        val cropped = centerCrop(boardBitmap, LAYOUT_CROP_W, LAYOUT_CROP_H)

        // 2. Resize 到 280x315
        val resized = Bitmap.createScaledBitmap(cropped, LAYOUT_INPUT_W, LAYOUT_INPUT_H, true)

        // 3. 转换为模型输入张量 (1, 3, 315, 280)
        val inputData = bitmapToFloatArray(resized, LAYOUT_INPUT_W, LAYOUT_INPUT_H)

        // ★ 释放 cropped 和 resized — 不再需要
        cropped.recycle()
        resized.recycle()

        // 4. ONNX 推理
        val inputName = session.inputNames.first()
        val inputTensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(inputData),
            longArrayOf(1, 3, LAYOUT_INPUT_H.toLong(), LAYOUT_INPUT_W.toLong())
        )
        val results = session.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        // 5. 提取输出 (1, 90, 16)
        @Suppress("UNCHECKED_CAST")
        val output = results[0].value as Array<Array<FloatArray>> // (1, 90, 16)
        results.close()

        // 6. Argmax — 每个格子选概率最高的类别 + 记录置信度
        val classes = IntArray(90)
        val confidences = FloatArray(90)
        for (i in 0 until 90) {
            classes[i] = argmax(output[0][i])
            confidences[i] = output[0][i][classes[i]]
        }
        return classes to confidences
    }

    /**
     * Center crop
     */
    private fun centerCrop(bitmap: Bitmap, cropW: Int, cropH: Int): Bitmap {
        val x = max(0, (bitmap.width - cropW) / 2)
        val y = max(0, (bitmap.height - cropH) / 2)
        val w = min(cropW, bitmap.width)
        val h = min(cropH, bitmap.height)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    // ── 位置约束纠错（用户建议的核心改进）──

    /**
     * 基于位置约束的纠错
     *
     * 用户核心思路：
     * 1. 士象只能出现在固定点位 — 不在合法位置的士象一定是串行了
     * 2. 用士象作为锚点，修正其他棋子的行偏移
     * 3. 精准捕捉空点 — 低置信度格子标记为空，约束棋子只能在有子的位置
     */
    private fun positionBasedCorrection(classes: IntArray, confidences: FloatArray): IntArray {
        val corrected = classes.copyOf()
        val corrections = mutableListOf<String>()

        // ── 第一步：精准捕捉空点 ──
        // 只有置信度极低（<0.10）的棋子才标记为空，避免误删有效棋子
        val EMPTY_CORRECTION_THRESHOLD = 0.10f
        for (i in 0 until 90) {
            if (corrected[i] >= 2 && confidences[i] < EMPTY_CORRECTION_THRESHOLD) {
                val row = i / 9
                val col = i % 9
                corrections.add("空点修正: ($row,$col) 极低置信度(${"%.2f".format(confidences[i])}) → 空")
                corrected[i] = 0 // 标记为空
            }
        }

        // ── 第二步：士象位置约束 ──
        // 只对低置信度（<0.50）的士象进行位置修正
        // 高置信度的士象即使位置"不合法"也保留原样（可能是自定义局面）
        val POSITION_CORRECTION_CONFIDENCE = 0.50f
        for (i in 0 until 90) {
            val row = i / 9
            val col = i % 9
            val cls = corrected[i]
            val pos = Pair(row, col)

            // 跳过高置信度棋子 — 模型很确定，不要乱动
            if (confidences[i] >= POSITION_CORRECTION_CONFIDENCE) continue

            val validPos = when (cls) {
                3 -> RED_ADVISOR_POS    // red_advisor
                4 -> RED_ELEPHANT_POS   // red_bishop
                10 -> BLACK_ADVISOR_POS  // black_advisor
                11 -> BLACK_ELEPHANT_POS // black_bishop
                2 -> RED_KING_POS       // red_king
                9 -> BLACK_KING_POS     // black_king
                else -> null
            }

            if (validPos != null && pos !in validPos) {
                // 士象在不合法位置且置信度低！尝试找到最近的合法位置
                val nearestValid = findNearestValid(pos, validPos, corrected, confidences)
                if (nearestValid != null) {
                    val (targetRow, targetCol) = nearestValid
                    val targetIdx = targetRow * 9 + targetCol
                    // 交换：当前位置清空，目标位置放棋子
                    val oldTarget = corrected[targetIdx]
                    corrected[targetIdx] = cls
                    corrected[i] = oldTarget
                    val pieceName = pieceName(cls)
                    corrections.add("位置纠错: $pieceName ($row,$col)→($targetRow,$targetCol) conf=${"%.2f".format(confidences[i])}")
                }
            }
        }

        // ── 第三步：检测整行偏移 ──
        // 如果多个士象都在不合法位置且偏移方向一致，可能是整行串行
        val shiftResult = detectAndApplyRowShift(corrected, confidences)
        if (shiftResult > 0) {
            corrections.add("行偏移修正: 检测到整体偏移 ${shiftResult} 行")
        }

        if (corrections.isNotEmpty()) {
            Log.i(TAG, "🔧 位置纠错 ${corrections.size} 处:")
            corrections.forEach { Log.i(TAG, "  $it") }
        }

        return corrected
    }

    /**
     * 找到最近的合法位置
     * 优先选择当前为空或低置信度的格子
     */
    private fun findNearestValid(
        pos: Pair<Int, Int>,
        validPos: Set<Pair<Int, Int>>,
        classes: IntArray,
        confidences: FloatArray
    ): Pair<Int, Int>? {
        val (row, col) = pos
        var best: Pair<Int, Int>? = null
        var bestDist = Int.MAX_VALUE

        for (vp in validPos) {
            // 计算曼哈顿距离
            val dist = kotlin.math.abs(vp.first - row) + kotlin.math.abs(vp.second - col)
            if (dist >= bestDist) continue

            val targetIdx = vp.first * 9 + vp.second
            // 目标位置必须是空的或低置信度的
            if (classes[targetIdx] == 0 || classes[targetIdx] == 1 || confidences[targetIdx] < PIECE_THRESHOLD) {
                best = vp
                bestDist = dist
            }
        }

        // 如果没找到空位，放宽条件：允许覆盖低置信度的棋子
        if (best == null) {
            for (vp in validPos) {
                val dist = kotlin.math.abs(vp.first - row) + kotlin.math.abs(vp.second - col)
                if (dist >= bestDist) continue
                val targetIdx = vp.first * 9 + vp.second
                if (confidences[targetIdx] < EMPTY_THRESHOLD) {
                    best = vp
                    bestDist = dist
                }
            }
        }

        return best
    }

    /**
     * 检测并应用整行偏移
     *
     * 如果多个士象都在不合法位置且偏移方向一致，
     * 说明可能是透视变换导致网格错位，尝试整体行偏移修正
     */
    private fun detectAndApplyRowShift(classes: IntArray, confidences: FloatArray): Int {
        // 先计算 shift=0 的基准分数
        var baselineScore = 0
        for (i in 0 until 90) {
            val row = i / 9
            val col = i % 9
            val cls = classes[i]
            val pos = Pair(row, col)
            when (cls) {
                3 -> if (pos in RED_ADVISOR_POS) baselineScore += 3
                4 -> if (pos in RED_ELEPHANT_POS) baselineScore += 2
                10 -> if (pos in BLACK_ADVISOR_POS) baselineScore += 3
                11 -> if (pos in BLACK_ELEPHANT_POS) baselineScore += 2
                2 -> if (pos in RED_KING_POS) baselineScore += 3
                9 -> if (pos in BLACK_KING_POS) baselineScore += 3
                0 -> {} // 空点不加分
                else -> baselineScore += 1 // 其他棋子在合法行范围内 +1
            }
        }

        // 尝试 -1, +1 行偏移，只有比基准分数高出至少3分才应用
        // （至少多一个士/将回到合法位置才值得偏移）
        var bestShift = 0
        var bestScore = baselineScore
        val minImprovement = 3

        for (shift in listOf(-1, 1)) {
            var score = 0
            for (i in 0 until 90) {
                val row = i / 9
                val col = i % 9
                val cls = classes[i]
                val shiftedRow = row + shift
                if (shiftedRow < 0 || shiftedRow > 9) continue

                val pos = Pair(shiftedRow, col)
                when (cls) {
                    3 -> if (pos in RED_ADVISOR_POS) score += 3
                    4 -> if (pos in RED_ELEPHANT_POS) score += 2
                    10 -> if (pos in BLACK_ADVISOR_POS) score += 3
                    11 -> if (pos in BLACK_ELEPHANT_POS) score += 2
                    2 -> if (pos in RED_KING_POS) score += 3
                    9 -> if (pos in BLACK_KING_POS) score += 3
                    0 -> {} // 空点不加分
                    else -> score += 1 // 其他棋子在合法行范围内 +1
                }
            }
            // 只有严格超过基准分数 + 最小改善阈值才接受
            if (score > bestScore + minImprovement - 1) {
                bestScore = score
                bestShift = shift
            }
        }

        // 只有当偏移确实改善时才应用
        if (bestShift != 0) {
            val shifted = IntArray(90) { 0 }
            for (i in 0 until 90) {
                val row = i / 9
                val col = i % 9
                val newRow = row + bestShift
                if (newRow in 0..9) {
                    shifted[newRow * 9 + col] = classes[i]
                }
            }
            // 复制回 classes
            for (i in 0 until 90) {
                classes[i] = shifted[i]
            }
        }

        return bestShift
    }

    private fun pieceName(cls: Int): String = when (cls) {
        2 -> "红帅"
        3 -> "红仕"
        4 -> "红相"
        5 -> "红马"
        6 -> "红车"
        7 -> "红炮"
        8 -> "红兵"
        9 -> "黑将"
        10 -> "黑士"
        11 -> "黑象"
        12 -> "黑马"
        13 -> "黑车"
        14 -> "黑炮"
        15 -> "黑卒"
        else -> "?"
    }

    // ── Bitmap 转 Float 数组 ──

    /**
     * 将 Bitmap 转换为 NCHW float 数组
     * - RGB 顺序
     * - ImageNet 归一化: (pixel - mean) / std
     * - 不除以 255，直接用 0-255 像素值
     */
    private fun bitmapToFloatArray(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        val floatArray = FloatArray(3 * width * height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // NCHW: [channel][row][col]
        // Channel 0 = R, 1 = G, 2 = B
        for (c in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val value = when (c) {
                        0 -> Color.red(pixel).toFloat()
                        1 -> Color.green(pixel).toFloat()
                        else -> Color.blue(pixel).toFloat()
                    }
                    floatArray[c * width * height + y * width + x] = (value - MEAN[c]) / STD[c]
                }
            }
        }
        return floatArray
    }

    // ── 分类结果转棋子 ──

    /**
     * 将 90 个格子的分类结果转换为 RecognizedPiece 列表
     * 90 个格子按行优先排列: 第0行(9格) → 第1行(9格) → ... → 第9行(9格)
     * 行0对应棋盘顶部（黑方），行9对应底部（红方）
     */
    private fun classesToPieces(classes: IntArray): List<BoardRecognizer.RecognizedPiece> {
        val pieces = mutableListOf<BoardRecognizer.RecognizedPiece>()
        for (i in 0 until 90) {
            val row = i / 9
            val col = i % 9
            val cls = classes[i]
            val (type, color) = CLASS_TO_PIECE[cls]
            if (type != null && color != null) {
                pieces.add(BoardRecognizer.RecognizedPiece(row, col, type, color))
            }
        }
        return pieces
    }

    // ── 规则校验 + 智能纠错 ──

    /**
     * 中国象棋规则校验与智能纠错
     *
     * 核心思路（用户要求）：
     * "红的就是帅，黑的就是将。人家管他标成什么士啊象的，我们按标配给它弄过来"
     *
     * 当模型遇到非标准标注的棋子（如红方写"士"而非"仕"）时，可能把颜色搞混。
     * 此函数通过以下策略修正：
     * 1. 检查各类棋子数量是否超限
     * 2. 如果红方某类超限且黑方同类不足，将"越界"的棋子颜色翻转
     * 3. 利用棋子位置辅助判断：靠近黑方半区的棋子更可能是黑方
     *
     * @return 修正后的 pieces 列表
     */
    private fun validateByRules(pieces: MutableList<BoardRecognizer.RecognizedPiece>): List<BoardRecognizer.RecognizedPiece> {
        val maxCounts = mapOf(
            PieceType.KING to 1,
            PieceType.ADVISOR to 2,
            PieceType.ELEPHANT to 2,
            PieceType.KNIGHT to 2,
            PieceType.ROOK to 2,
            PieceType.CANNON to 2,
            PieceType.PAWN to 5
        )

        val corrections = mutableListOf<String>()

        // 逐类检查：如果某颜色超限，尝试把多出来的翻转到对方
        for ((type, maxCount) in maxCounts) {
            val redPieces = pieces.filter { it.type == type && it.color == PieceColor.RED }
            val blackPieces = pieces.filter { it.type == type && it.color == PieceColor.BLACK }
            val redExcess = redPieces.size - maxCount
            val blackExcess = blackPieces.size - maxCount

            if (redExcess > 0 && blackExcess < maxCount) {
                // 红方超限，黑方不足 → 把红方多出来的翻转为黑方
                // 按位置排序：row 越小（越靠近黑方半区）的越可能原本是黑棋
                val sortedRed = redPieces.sortedBy { it.row }
                val toFlip = minOf(redExcess, maxCount - blackPieces.size)
                for (i in 0 until toFlip) {
                    val idx = pieces.indexOf(sortedRed[i])
                    pieces[idx] = pieces[idx].copy(color = PieceColor.BLACK)
                    val typeName = pieceName(type, PieceColor.BLACK)
                    corrections.add("位置(${sortedRed[i].row},${sortedRed[i].col}) 红→黑 $typeName")
                }
            } else if (blackExcess > 0 && redExcess < maxCount) {
                // 黑方超限，红方不足 → 把黑方多出来的翻转为红方
                // 按位置排序：row 越大（越靠近红方半区）的越可能原本是红棋
                val sortedBlack = blackPieces.sortedByDescending { it.row }
                val toFlip = minOf(blackExcess, maxCount - redPieces.size)
                for (i in 0 until toFlip) {
                    val idx = pieces.indexOf(sortedBlack[i])
                    pieces[idx] = pieces[idx].copy(color = PieceColor.RED)
                    val typeName = pieceName(type, PieceColor.RED)
                    corrections.add("位置(${sortedBlack[i].row},${sortedBlack[i].col}) 黑→红 $typeName")
                }
            }
        }

        // 纠错后再次验证
        val warnings = mutableListOf<String>()
        if (pieces.size > 32) warnings.add("棋子总数 ${pieces.size} 超过32")

        for (color in listOf(PieceColor.RED, PieceColor.BLACK)) {
            val colorName = if (color == PieceColor.RED) "红" else "黑"
            for ((type, maxCount) in maxCounts) {
                val count = pieces.count { it.type == type && it.color == color }
                if (count > maxCount) {
                    warnings.add("${colorName}方${pieceName(type, color)}数量${count}超过${maxCount}")
                }
            }
        }

        val redKings = pieces.count { it.type == PieceType.KING && it.color == PieceColor.RED }
        val blackKings = pieces.count { it.type == PieceType.KING && it.color == PieceColor.BLACK }
        if (redKings != 1) warnings.add("红帅数量${redKings}（应为1）")
        if (blackKings != 1) warnings.add("黑将数量${blackKings}（应为1）")

        if (corrections.isNotEmpty()) {
            Log.i(TAG, "🔧 智能纠错 ${corrections.size} 处:")
            corrections.forEach { Log.i(TAG, "  $it") }
        }
        if (warnings.isNotEmpty()) {
            Log.w(TAG, "⚠ 纠错后仍有警告: ${warnings.joinToString("; ")}")
        } else {
            Log.d(TAG, "✓ 规则校验通过（纠错${corrections.size}处）")
        }

        return pieces
    }

    private fun pieceName(type: PieceType, color: PieceColor): String = when (color) {
        PieceColor.RED -> when (type) {
            PieceType.KING -> "帅"
            PieceType.ADVISOR -> "仕"
            PieceType.ELEPHANT -> "相"
            PieceType.KNIGHT -> "马"
            PieceType.ROOK -> "车"
            PieceType.CANNON -> "炮"
            PieceType.PAWN -> "兵"
        }
        PieceColor.BLACK -> when (type) {
            PieceType.KING -> "将"
            PieceType.ADVISOR -> "士"
            PieceType.ELEPHANT -> "象"
            PieceType.KNIGHT -> "马"
            PieceType.ROOK -> "车"
            PieceType.CANNON -> "炮"
            PieceType.PAWN -> "卒"
        }
    }

    // ── 智能翻转检测 ──

    private fun detectAndApplyFlip(pieces: List<BoardRecognizer.RecognizedPiece>): List<BoardRecognizer.RecognizedPiece> {
        val redKing = pieces.find { it.type == PieceType.KING && it.color == PieceColor.RED }
        val blackKing = pieces.find { it.type == PieceType.KING && it.color == PieceColor.BLACK }

        var needFlip = false
        if (redKing != null && blackKing != null) {
            if (redKing.row < blackKing.row) {
                needFlip = true
                Log.d(TAG, "★ 翻转检测: 红帅在上方(row=${redKing.row})，需翻转")
            }
        } else if (redKing != null) {
            if (redKing.row <= 4) {
                needFlip = true
                Log.d(TAG, "★ 翻转检测: 仅有红帅在上方(row=${redKing.row})，需翻转")
            }
        } else if (blackKing != null) {
            if (blackKing.row >= 5) {
                needFlip = true
                Log.d(TAG, "★ 翻转检测: 仅有黑将在下方(row=${blackKing.row})，需翻转")
            }
        }

        if (!needFlip) return pieces

        Log.d(TAG, "★ 应用翻转: ${pieces.size} 个棋子")
        return pieces.map { p ->
            BoardRecognizer.RecognizedPiece(
                row = 9 - p.row,
                col = 8 - p.col,
                type = p.type,
                color = p.color
            )
        }
    }

    // ── 结果消息 ──

    private fun buildResultMessage(pieces: List<BoardRecognizer.RecognizedPiece>): String {
        val redCount = pieces.count { it.color == PieceColor.RED }
        val blackCount = pieces.count { it.color == PieceColor.BLACK }
        val hasRedKing = pieces.any { it.type == PieceType.KING && it.color == PieceColor.RED }
        val hasBlackKing = pieces.any { it.type == PieceType.KING && it.color == PieceColor.BLACK }

        val sb = StringBuilder()
        sb.append("ONNX识别到 ${pieces.size} 个棋子（红${redCount} 黑${blackCount}）")

        if (!hasRedKing || !hasBlackKing) {
            val missing = mutableListOf<String>()
            if (!hasRedKing) missing.add("红帅")
            if (!hasBlackKing) missing.add("黑将")
            sb.append("，缺少${missing.joinToString("、")}，请手动补上")
        }
        return sb.toString()
    }
}
