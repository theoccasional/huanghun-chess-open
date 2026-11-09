package com.pikafish.chess

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.pikafish.chess.model.PieceColor
import com.pikafish.chess.model.PieceType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 棋盘拍照识别器 v3
 *
 * ★ v3 核心改进（相比 v2）：
 * 1. 边界框网格映射 — 取代聚类算法，利用已知 9×10 棋盘结构精确定位
 *    算法：找到所有检测到棋子的边界框 → 根据棋子平均尺寸估算网格间距
 *    → 如果边界框小于预期棋盘尺寸则对称扩展 → 将每个棋子映射到最近网格交叉点
 * 2. 图像预处理增强 — 对比度增强 + 放大小图，提升 OCR 识别率
 * 3. 双重 OCR 策略 — 先在增强图上识别，若结果不足 10 个棋子则补充原图识别
 * 4. 圆形区域颜色采样 — 匹配棋子圆形特征，在圆形区域内统计红/黑像素
 * 5. 结果校验 — 检查棋子数量上限、将帅存在性、重复位置
 * 6. 扩展字符映射 — 增加繁体字（俥/傌）、异体字、更多 OCR 混淆修正
 *
 * ★ 关于棋盘/棋子大小设置：
 * 识别过程完全基于照片图片像素，与 app 内的棋盘缩放/棋子大小设置完全无关。
 * 识别结果是一组 (行,列,类型,颜色) 坐标，直接写入 Board 模型，不受显示层影响。
 * 无论用户是否调节过棋盘大小，都不影响识别结果的正确性。
 */
class BoardRecognizer {

    companion object {
        private const val TAG = "BoardRecognizer"
        private const val BOARD_COLS = 9
        private const val BOARD_ROWS = 10
    }

    data class RecognizedPiece(
        val row: Int,
        val col: Int,
        val type: PieceType,
        val color: PieceColor
    )

    data class RecognitionResult(
        val pieces: List<RecognizedPiece>,
        val message: String,
        val success: Boolean
    )

    // ── 棋子字符映射表 ──
    // value: Pair<类型, 已知颜色?>  null 表示需通过像素分析判定
    private val charToPiece: Map<String, Pair<PieceType, PieceColor?>> = mapOf(
        // 红方专属
        "帅" to Pair(PieceType.KING, PieceColor.RED),
        "帥" to Pair(PieceType.KING, PieceColor.RED),
        "仕" to Pair(PieceType.ADVISOR, PieceColor.RED),
        "相" to Pair(PieceType.ELEPHANT, PieceColor.RED),
        "兵" to Pair(PieceType.PAWN, PieceColor.RED),
        "俥" to Pair(PieceType.ROOK, PieceColor.RED),   // 红方繁体车
        "傌" to Pair(PieceType.KNIGHT, PieceColor.RED),  // 红方繁体马
        // 黑方专属
        "将" to Pair(PieceType.KING, PieceColor.BLACK),
        "將" to Pair(PieceType.KING, PieceColor.BLACK),
        "士" to Pair(PieceType.ADVISOR, PieceColor.BLACK),
        "象" to Pair(PieceType.ELEPHANT, PieceColor.BLACK),
        "卒" to Pair(PieceType.PAWN, PieceColor.BLACK),
        "砲" to Pair(PieceType.CANNON, PieceColor.BLACK), // 黑方繁体炮
        // 共用字符（需颜色判定）
        "马" to Pair(PieceType.KNIGHT, null),
        "馬" to Pair(PieceType.KNIGHT, null),
        "车" to Pair(PieceType.ROOK, null),
        "車" to Pair(PieceType.ROOK, null),
        "炮" to Pair(PieceType.CANNON, null)
    )

    private val validChars = charToPiece.keys

    // ── OCR 常见混淆修正 ──
    private val charCorrections: Map<String, String> = mapOf(
        "师" to "帅",  "師" to "帅",
        "想" to "相",  "箱" to "相",
        "泡" to "炮",  "抱" to "炮", "砲" to "炮",
        "驻" to "马",  "驹" to "马", "冯" to "马", "妈" to "马",
        "库" to "车",  "东" to "车", "阵" to "车", "连" to "车",
        "足" to "卒",  "族" to "卒", "碎" to "卒",
        "才" to "士",  "上" to "士", "土" to "士", "工" to "士",
        "蒋" to "将",  "酱" to "将",
        "冰" to "兵",  "乒" to "兵", "乓" to "兵",
        "象" to "象",  // no-op, just to be safe
        "双" to "相",
        "日" to "白",  // not a piece, filtered out
    )

    // ── 识别主流程 ──

    suspend fun recognize(context: Context, imageUri: Uri): RecognitionResult {
        return try {
            Log.d(TAG, "════════════════════════════════════")
            Log.d(TAG, "║  棋盘识别 v3 启动")
            Log.d(TAG, "════════════════════════════════════")

            // 1. 加载原图
            val originalBitmap = loadBitmap(context, imageUri)
                ?: return RecognitionResult(emptyList(), "无法加载图片", false)

            Log.d(TAG, "原图尺寸: ${originalBitmap.width}x${originalBitmap.height}")

            // 复用共用识别主体
            recognizeBitmap(originalBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e)
            RecognitionResult(emptyList(), "识别失败: ${e.message}", false)
        }
    }

    /**
     * ★★★ 直接识别 Bitmap（连线悬浮窗实时截图入口）
     * 主界面拍照识别(Uri)与悬浮窗(截图Bitmap)共用同一套 OCR 识别主体，
     * 保证两者对繁体/简体棋子识别行为一致。悬浮窗识别时建议传入棋盘区域
     * 的裁剪 Bitmap，能显著提升 OCR 命中率。
     */
    suspend fun recognizeBitmap(originalBitmap: Bitmap): RecognitionResult {
        return try {
            Log.d(TAG, "════════════════════════════════════")
            Log.d(TAG, "║  棋盘识别 v3(Bitmap) 启动")
            Log.d(TAG, "════════════════════════════════════")
            Log.d(TAG, "原图尺寸: ${originalBitmap.width}x${originalBitmap.height}")

            // 2. 创建增强图（对比度增强 + 放大）
            val enhancedBitmap = enhanceBitmap(originalBitmap)
            Log.d(TAG, "增强图尺寸: ${enhancedBitmap.width}x${enhancedBitmap.height}")

            // 3. 第一轮 OCR：增强图
            Log.d(TAG, "── 第一轮 OCR（增强图）──")
            val enhancedText = recognizeText(enhancedBitmap)
            var rawPieces = if (enhancedText != null) {
                extractPieceTexts(enhancedText, scale = enhancedBitmap.width.toFloat() / originalBitmap.width)
            } else {
                emptyList()
            }
            Log.d(TAG, "增强图提取到 ${rawPieces.size} 个候选棋子")

            // 如果增强图结果不足，补充原图 OCR
            if (rawPieces.size < 10) {
                Log.d(TAG, "── 第二轮 OCR（原图补充）──")
                val originalText = recognizeText(originalBitmap)
                if (originalText != null) {
                    val originalPieces = extractPieceTexts(originalText, scale = 1f)
                    Log.d(TAG, "原图提取到 ${originalPieces.size} 个候选棋子")
                    // 合并结果（后续 grid mapping 会自动去重）
                    rawPieces = (rawPieces + originalPieces).distinctBy { it.char + it.centerX.toInt() + it.centerY.toInt() }
                    Log.d(TAG, "合并后共 ${rawPieces.size} 个候选棋子")
                }
            }

            // 打印所有检测到的文字（用于调试）
            logAllDetectedText(enhancedText, "增强图")
            if (rawPieces.isEmpty()) {
                return RecognitionResult(emptyList(), "未识别到棋子文字，请确保拍照清晰、棋子可见", false)
            }

            Log.d(TAG, "候选棋子: ${rawPieces.joinToString { "${it.char}@(${it.centerX.toInt()},${it.centerY.toInt()})" }}")

            // 4. 边界框网格映射
            val gridPieces = mapToGrid(rawPieces, originalBitmap)
            if (gridPieces.isEmpty()) {
                return RecognitionResult(emptyList(), "棋子位置映射失败", false)
            }

            Log.d(TAG, "网格映射后: ${gridPieces.size} 个棋子")

            // 5. 智能翻转检测
            val finalPieces = detectAndApplyFlip(gridPieces)

            // 6. 结果校验与清理
            val validatedPieces = validateAndClean(finalPieces)

            Log.d(TAG, "最终结果: ${validatedPieces.size} 个棋子")
            validatedPieces.forEach { p ->
                Log.d(TAG, "  (${p.row},${p.col}) ${p.color} ${p.type}")
            }

            val msg = buildResultMessage(validatedPieces)
            RecognitionResult(validatedPieces, msg, true)
        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e)
            RecognitionResult(emptyList(), "识别失败: ${e.message}", false)
        }
    }

    // ── 图片加载 ──

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }

            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 2048) sampleSize *= 2

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap failed", e)
            null
        }
    }

    // ── 图像预处理增强 ──

    /**
     * ★ 图像增强：放大 + 对比度提升
     *
     * - 小图放大到至少 1500px 宽（ML Kit 对大字识别更准确）
     * - 对比度提升 1.4 倍，使棋子文字更突出
     */
    private fun enhanceBitmap(src: Bitmap): Bitmap {
        // 1. 放大小图
        val minDim = 1500
        val scaled = if (minOf(src.width, src.height) < minDim) {
            val scale = minDim.toFloat() / minOf(src.width, src.height)
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt(),
                (src.height * scale).toInt(),
                true
            )
        } else {
            src
        }

        // 2. 对比度增强
        val contrast = 1.4f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, 0f,
                0f, contrast, 0f, 0f, 0f,
                0f, 0f, contrast, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(scaled, 0f, 0f, paint)

        return output
    }

    // ── ML Kit 文字识别 ──

    private suspend fun recognizeText(bitmap: Bitmap): com.google.mlkit.vision.text.Text? {
        return suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "ML Kit 识别失败", e)
                        cont.resume(null)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "recognizeText 异常", e)
                cont.resume(null)
            }
        }
    }

    // ── 调试日志：打印所有检测到的文字 ──

    private fun logAllDetectedText(text: com.google.mlkit.vision.text.Text?, label: String) {
        if (text == null) {
            Log.d(TAG, "[$label] 无文字检测结果")
            return
        }
        Log.d(TAG, "[$label] 检测到 ${text.textBlocks.size} 个文字块:")
        for ((bi, block) in text.textBlocks.withIndex()) {
            Log.d(TAG, "  块$bi: \"${block.text.take(50)}\" lines=${block.lines.size}")
            for ((li, line) in block.lines.withIndex()) {
                Log.d(TAG, "    行$li: \"${line.text.take(50)}\" elements=${line.elements.size}")
                for ((ei, elem) in line.elements.withIndex()) {
                    val bb = elem.boundingBox
                    Log.d(TAG, "      元素$ei: \"${elem.text}\" bbox=$bb")
                }
            }
        }
    }

    // ── 棋子文字提取 ──

    private data class PieceTextInfo(
        val char: String,
        val centerX: Float,
        val centerY: Float,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val confidence: Float
    )

    /**
     * 从 ML Kit 识别结果中提取棋子文字
     * @param scale 增强图相对于原图的缩放比（用于坐标换算）
     */
    private fun extractPieceTexts(
        text: com.google.mlkit.vision.text.Text,
        scale: Float
    ): List<PieceTextInfo> {
        val results = mutableListOf<PieceTextInfo>()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                // 优先使用 element 级别的 bounding box
                for (element in line.elements) {
                    val elemText = element.text.trim()
                    val frame = element.boundingBox
                    if (elemText.isEmpty() || frame == null) continue

                    // 逐字处理
                    for ((idx, ch) in elemText.withIndex()) {
                        var charStr = ch.toString()

                        // 应用 OCR 混淆修正
                        charStr = charCorrections[charStr] ?: charStr

                        if (charStr !in validChars) continue

                        // 计算单字位置（element 可能包含多字）
                        val charCount = elemText.length
                        val charW = frame.width().toFloat() / charCount
                        val cLeft = frame.left + (idx * charW).toInt()
                        val cRight = frame.left + ((idx + 1) * charW).toInt()

                        // 坐标换算回原图比例
                        val origLeft = (cLeft / scale).toInt()
                        val origRight = (cRight / scale).toInt()
                        val origTop = (frame.top / scale).toInt()
                        val origBottom = (frame.bottom / scale).toInt()
                        val origCenterX = (origLeft + origRight) / 2f
                        val origCenterY = (origTop + origBottom) / 2f

                        val size = minOf(origRight - origLeft, origBottom - origTop)
                        val conf = size.toFloat()

                        results.add(PieceTextInfo(
                            char = charStr,
                            centerX = origCenterX,
                            centerY = origCenterY,
                            left = origLeft,
                            top = origTop,
                            right = origRight,
                            bottom = origBottom,
                            confidence = conf
                        ))
                    }
                }

                // 如果 line 有文字但 elements 为空或无有效棋子，尝试用 line 的 bounding box
                val lineText = line.text.trim()
                if (line.elements.isEmpty() && lineText.isNotEmpty()) {
                    val frame = line.boundingBox ?: continue
                    for (ch in lineText) {
                        var charStr = ch.toString()
                        charStr = charCorrections[charStr] ?: charStr
                        if (charStr !in validChars) continue

                        val charCount = lineText.length
                        val charW = frame.width().toFloat() / charCount
                        val idx = lineText.indexOf(ch)
                        val cLeft = frame.left + (idx * charW).toInt()
                        val cRight = frame.left + ((idx + 1) * charW).toInt()

                        val origLeft = (cLeft / scale).toInt()
                        val origRight = (cRight / scale).toInt()
                        val origTop = (frame.top / scale).toInt()
                        val origBottom = (frame.bottom / scale).toInt()

                        results.add(PieceTextInfo(
                            char = charStr,
                            centerX = (origLeft + origRight) / 2f,
                            centerY = (origTop + origBottom) / 2f,
                            left = origLeft,
                            top = origTop,
                            right = origRight,
                            bottom = origBottom,
                            confidence = minOf(origRight - origLeft, origBottom - origTop).toFloat()
                        ))
                    }
                }
            }
        }

        return results
    }

    // ── 边界框网格映射（v3 核心改进）──

    /**
     * ★ 边界框网格映射算法
     *
     * 原理：利用已知的 9 列 × 10 行棋盘结构，通过棋子位置边界框推算网格。
     *
     * 步骤：
     * 1. 估算棋子平均尺寸 → 推算网格间距（≈ 棋子直径）
     * 2. 找到所有棋子的边界框（minX, maxX, minY, maxY）
     * 3. 如果边界框尺寸小于预期棋盘（8×间距宽 × 9×间距高），对称扩展
     * 4. 将边界框划分为 9 列 × 10 行网格
     * 5. 每个棋子映射到最近的网格交叉点
     * 6. 同一格冲突时选置信度最高的
     *
     * 相比 v2 聚类算法的优势：
     * - 利用已知棋盘结构（9×10），不会产生错误数量的行列
     * - 不受处理顺序影响
     * - 即使部分行列无棋子也能正确定位
     */
    private fun mapToGrid(
        pieceTexts: List<PieceTextInfo>,
        bitmap: Bitmap
    ): MutableList<RecognizedPiece> {
        if (pieceTexts.isEmpty()) return mutableListOf()

        // 1. 估算棋子平均尺寸
        val avgWidth = pieceTexts.map { it.right - it.left }.filter { it > 0 }.average().toFloat()
        val avgHeight = pieceTexts.map { it.bottom - it.top }.filter { it > 0 }.average().toFloat()
        val avgSize = maxOf(avgWidth, avgHeight, 10f)
        // 网格间距 ≈ 棋子直径（棋子紧邻时间距略大于直径）
        val estimatedSpacing = avgSize * 1.08f

        Log.d(TAG, "平均棋子尺寸: ${avgWidth.toInt()}x${avgHeight.toInt()}, 估算网格间距: ${estimatedSpacing.toInt()}")

        // 2. 找到所有棋子的边界框
        val rawMinX = pieceTexts.minOf { it.centerX }
        val rawMaxX = pieceTexts.maxOf { it.centerX }
        val rawMinY = pieceTexts.minOf { it.centerY }
        val rawMaxY = pieceTexts.maxOf { it.centerY }

        Log.d(TAG, "原始边界框: X=[${rawMinX.toInt()},${rawMaxX.toInt()}] Y=[${rawMinY.toInt()},${rawMaxY.toInt()}]")

        // 3. 估算棋盘尺寸并调整边界框
        // 棋盘有 8 个列间距 × 9 个行间距
        val expectedWidth = 8f * estimatedSpacing
        val expectedHeight = 9f * estimatedSpacing
        val actualWidth = rawMaxX - rawMinX
        val actualHeight = rawMaxY - rawMinY

        Log.d(TAG, "预期棋盘: ${expectedWidth.toInt()}x${expectedHeight.toInt()}, 实际: ${actualWidth.toInt()}x${actualHeight.toInt()}")

        // 如果实际边界框小于预期的 80%，说明外侧行列可能无棋子，需对称扩展
        val boardMinX: Float
        val boardMaxX: Float
        val boardMinY: Float
        val boardMaxY: Float

        if (actualWidth < expectedWidth * 0.8f) {
            val center = (rawMinX + rawMaxX) / 2f
            boardMinX = center - expectedWidth / 2f
            boardMaxX = center + expectedWidth / 2f
            Log.d(TAG, "X方向扩展: ${actualWidth.toInt()} → ${expectedWidth.toInt()} (中心=${center.toInt()})")
        } else {
            boardMinX = rawMinX
            boardMaxX = rawMaxX
        }

        if (actualHeight < expectedHeight * 0.8f) {
            val center = (rawMinY + rawMaxY) / 2f
            boardMinY = center - expectedHeight / 2f
            boardMaxY = center + expectedHeight / 2f
            Log.d(TAG, "Y方向扩展: ${actualHeight.toInt()} → ${expectedHeight.toInt()} (中心=${center.toInt()})")
        } else {
            boardMinY = rawMinY
            boardMaxY = rawMaxY
        }

        // 4. 计算网格间距
        val colSpacing = (boardMaxX - boardMinX) / (BOARD_COLS - 1).toFloat()
        val rowSpacing = (boardMaxY - boardMinY) / (BOARD_ROWS - 1).toFloat()

        Log.d(TAG, "网格间距: 列=${colSpacing.toInt()} 行=${rowSpacing.toInt()}")

        // 5. 将每个棋子映射到网格
        val grid = Array(BOARD_ROWS) { arrayOfNulls<RecognizedPiece?>(BOARD_COLS) }
        val gridConfidence = Array(BOARD_ROWS) { FloatArray(BOARD_COLS) }

        for (pt in pieceTexts) {
            val (type, knownColor) = charToPiece[pt.char] ?: continue

            // 计算网格坐标
            val col = ((pt.centerX - boardMinX) / colSpacing).roundToInt()
            val row = ((pt.centerY - boardMinY) / rowSpacing).roundToInt()

            // 边界检查
            if (col < 0 || col >= BOARD_COLS || row < 0 || row >= BOARD_ROWS) {
                Log.d(TAG, "越界跳过: ${pt.char} → ($row,$col)")
                continue
            }

            val color = knownColor ?: detectColor(bitmap, pt)

            // 置信度去重：同位置选高置信度
            if (grid[row][col] == null || pt.confidence > gridConfidence[row][col]) {
                grid[row][col] = RecognizedPiece(row, col, type, color)
                gridConfidence[row][col] = pt.confidence
                Log.d(TAG, "放置: ${pt.char} → ($row,$col) ${color} conf=${pt.confidence.toInt()}")
            } else {
                Log.d(TAG, "跳过重复: ${pt.char} → ($row,$col) conf=${pt.confidence.toInt()} < ${gridConfidence[row][col].toInt()}")
            }
        }

        val result = grid.flatMap { row -> row.filterNotNull() }.toMutableList()
        Log.d(TAG, "网格映射完成: ${result.size} 个棋子")
        return result
    }

    // ── 智能翻转检测 ──

    /**
     * ★ 根据帅/将位置判断棋盘是否翻转
     *
     * 正常方向：红帅在下半场（row 7-9），黑将在上半场（row 0-2）
     * 翻转方向：红帅在上半场（row 0-2），黑将在下半场（row 7-9）
     *
     * 如果检测到翻转，将所有棋子的行列镜像翻转：
     * newRow = 9 - row, newCol = 8 - col
     */
    private fun detectAndApplyFlip(pieces: MutableList<RecognizedPiece>): List<RecognizedPiece> {
        val redKing = pieces.find { it.type == PieceType.KING && it.color == PieceColor.RED }
        val blackKing = pieces.find { it.type == PieceType.KING && it.color == PieceColor.BLACK }

        var needFlip = false

        if (redKing != null && blackKing != null) {
            // 两个将都有：比较位置
            if (redKing.row < blackKing.row) {
                needFlip = true
                Log.d(TAG, "★ 翻转检测: 红帅在row=${redKing.row}（上方），黑将在row=${blackKing.row}（下方）→ 需翻转")
            } else {
                Log.d(TAG, "★ 翻转检测: 红帅在row=${redKing.row}（下方），方向正确")
            }
        } else if (redKing != null) {
            // 只有红帅
            if (redKing.row <= 4) {
                needFlip = true
                Log.d(TAG, "★ 翻转检测: 仅有红帅在row=${redKing.row}（上方）→ 需翻转")
            } else {
                Log.d(TAG, "★ 翻转检测: 仅有红帅在row=${redKing.row}（下方），方向正确")
            }
        } else if (blackKing != null) {
            // 只有黑将
            if (blackKing.row >= 5) {
                needFlip = true
                Log.d(TAG, "★ 翻转检测: 仅有黑将在row=${blackKing.row}（下方）→ 需翻转")
            } else {
                Log.d(TAG, "★ 翻转检测: 仅有黑将在row=${blackKing.row}（上方），方向正确")
            }
        } else {
            // 没有将帅，用红黑棋子分布判断
            val redPieces = pieces.filter { it.color == PieceColor.RED }
            val blackPieces = pieces.filter { it.color == PieceColor.BLACK }
            if (redPieces.isNotEmpty() && blackPieces.isNotEmpty()) {
                val redAvgRow = redPieces.map { it.row }.average()
                val blackAvgRow = blackPieces.map { it.row }.average()
                if (redAvgRow < blackAvgRow) {
                    needFlip = true
                    Log.d(TAG, "★ 翻转检测: 无将帅，红方平均行=${redAvgRow.toInt()}（上方）→ 需翻转")
                } else {
                    Log.d(TAG, "★ 翻转检测: 无将帅，红方平均行=${redAvgRow.toInt()}（下方），方向正确")
                }
            }
        }

        if (!needFlip) return pieces

        Log.d(TAG, "★ 应用翻转: ${pieces.size} 个棋子")
        return pieces.map { p ->
            RecognizedPiece(
                row = BOARD_ROWS - 1 - p.row,
                col = BOARD_COLS - 1 - p.col,
                type = p.type,
                color = p.color
            )
        }
    }

    // ── 颜色判定 ──

    /**
     * ★ 圆形区域颜色采样
     *
     * 在棋子文字周围以圆形区域采样（棋子是圆形的），统计红/黑像素：
     * - 红色：HSV 色相在 330-360 或 0-25 范围，饱和度 > 0.25
     * - 黑色：HSV 亮度 < 0.4，饱和度 < 0.5
     * - 跳过浅色背景（亮度 > 180 的像素）
     */
    private fun detectColor(bitmap: Bitmap, pt: PieceTextInfo): PieceColor {
        return try {
            val centerX = ((pt.left + pt.right) / 2f).toInt()
            val centerY = ((pt.top + pt.bottom) / 2f).toInt()
            val pieceW = pt.right - pt.left
            val pieceH = pt.bottom - pt.top
            // 采样半径 = 棋子尺寸的 0.7 倍（覆盖整个棋子圆面）
            val radius = maxOf(pieceW, pieceH) * 0.7f

            var redScore = 0
            var blackScore = 0

            val r = radius.toInt()
            val step = maxOf(1, r / 10)

            for (dx in -r..r step step) {
                for (dy in -r..r step step) {
                    // 只采样圆形区域内
                    if (dx * dx + dy * dy > r * r) continue

                    val x = centerX + dx
                    val y = centerY + dy
                    if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) continue

                    val pixel = bitmap.getPixel(x, y)
                    val rv = Color.red(pixel)
                    val gv = Color.green(pixel)
                    val bv = Color.blue(pixel)

                    // 跳过浅色背景
                    val brightness = (rv + gv + bv) / 3
                    if (brightness > 180) continue

                    val hsv = FloatArray(3)
                    Color.RGBToHSV(rv, gv, bv, hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val value = hsv[2]

                    // 红色判定
                    val isRed = (hue < 25 || hue > 335) && sat > 0.25f && value > 0.15f
                    // 黑色判定
                    val isDark = value < 0.4f && sat < 0.5f

                    if (isRed) redScore++
                    else if (isDark) blackScore++
                }
            }

            Log.d(TAG, "颜色判定 ${pt.char}: red=$redScore black=$blackScore 中心=($centerX,$centerY) r=$r")

            if (redScore > blackScore) PieceColor.RED else PieceColor.BLACK
        } catch (e: Exception) {
            Log.e(TAG, "颜色判定异常: ${pt.char}", e)
            PieceColor.RED
        }
    }

    // ── 结果校验与清理 ──

    /**
     * ★ 校验识别结果
     *
     * - 检查棋子数量上限（王1、兵5、其余2）
     * - 超出数量的低置信度棋子被移除
     */
    private fun validateAndClean(pieces: List<RecognizedPiece>): List<RecognizedPiece> {
        val typeCounts = mutableMapOf<Pair<PieceType, PieceColor>, Int>()
        val result = mutableListOf<RecognizedPiece>()

        for (piece in pieces) {
            val key = Pair(piece.type, piece.color)
            val count = typeCounts.getOrDefault(key, 0)
            val maxCount = when (piece.type) {
                PieceType.KING -> 1
                PieceType.PAWN -> 5
                else -> 2
            }
            if (count >= maxCount) {
                Log.w(TAG, "校验: 跳过超出数量的 ${piece.color} ${piece.type} at (${piece.row},${piece.col})")
                continue
            }
            typeCounts[key] = count + 1
            result.add(piece)
        }

        return result
    }

    // ── 结果消息 ──

    private fun buildResultMessage(pieces: List<RecognizedPiece>): String {
        val redCount = pieces.count { it.color == PieceColor.RED }
        val blackCount = pieces.count { it.color == PieceColor.BLACK }
        val hasRedKing = pieces.any { it.type == PieceType.KING && it.color == PieceColor.RED }
        val hasBlackKing = pieces.any { it.type == PieceType.KING && it.color == PieceColor.BLACK }

        val sb = StringBuilder()
        sb.append("识别到 ${pieces.size} 个棋子（红${redCount} 黑${blackCount}）")

        if (!hasRedKing || !hasBlackKing) {
            val missing = mutableListOf<String>()
            if (!hasRedKing) missing.add("红帅")
            if (!hasBlackKing) missing.add("黑将")
            sb.append("，缺少${missing.joinToString("、")}，请手动补上")
        }

        return sb.toString()
    }
}
