package com.yk.xiangqi.link

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.pikafish.chess.heavy.HeavyStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.PriorityQueue

/**
 * 基于 YOLOv11 的中国象棋棋盘定位器（移植自「极简象棋」的 YoloDetector）。
 *
 * <p>与本项目原有的识别系统互补：原有的 pose/layout/yolo 融合识别<b>必须先有棋盘四角</b>，
 * 而本定位器是<b>免框选的自动找盘</b>——一次前向就同时给出「棋盘框」和每颗棋子的框，
 * 棋盘几何直接由棋盘框得到，<b>不依赖网格投影、不受棋盘大小与屏幕位置影响</b>。
 * 这正是补齐「自动框选/自动识别不能一触即发、小棋盘认不出」的那块拼图。</p>
 *
 * <p>它只负责<b>定位</b>（给出棋盘几何），认子仍走本项目原有那条高精度路径；因此即便
 * 定位失败，也只是回退到角点/梳齿检测，绝不会把识别质量拉低。</p>
 *
 * <p>模型输出为 YOLOv11 转置格式 {@code [1, 19, 8400]}（19 = 4 坐标 + 15 类，末类 '0'
 * 为棋盘框），按类做 NMS。为防止把本应用自己的界面误判成棋盘，候选棋盘框还须通过
 * 8:9 宽高比容差带（{@link #MIN_ASPECT}..{@link #MAX_ASPECT}）才会被采信。</p>
 */
class YoloBoardLocator(context: Context) : AutoCloseable {

    private companion object {
        const val TAG = "XiangqiLink"
        const val MODEL_ASSET = "link/yolov11.onnx"
        const val CACHE_NAME = "link_yolov11.onnx"
        const val SIZE = 640
        const val CONFIDENCE = 0.5f
        const val IOU_THRESHOLD = 0.45f

        /** 15 类标签：黑方 7 子 + 红方 7 子 + 棋盘框。 */
        val LABELS = charArrayOf('n', 'b', 'a', 'k', 'r', 'c', 'p', 'R', 'N', 'A', 'K', 'B', 'C', 'P', '0')
        const val NUM_CLASSES = 15
        const val STRIDE = 4 + NUM_CLASSES

        /** 棋盘框宽高比 8 列 / 9 行 ≈ 0.889，容差带用于剔除条状/方块状的误检。 */
        const val MIN_ASPECT = 0.70f
        const val MAX_ASPECT = 1.10f
    }

    /** 单条检测：类别标签 + 原图坐标下的框。 */
    private data class Detection(
        val label: Char,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
    )

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        // 从 assets 复制到缓存目录再按文件路径加载，避免一次性把十几 MB 读进内存造成峰值。
        val cacheFile = File(context.cacheDir, CACHE_NAME)
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            HeavyStore.openLocalFirst(context, MODEL_ASSET).use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val options = OrtSession.SessionOptions()
        session = environment.createSession(cacheFile.absolutePath, options)
    }

    /**
     * 在整张截图上定位棋盘。
     *
     * @return 棋盘框在原图坐标下的 {@code [left, top, right, bottom]}，未命中返回 {@code null}。
     */
    fun detectBoardRect(frame: Frame?): FloatArray? {
        if (frame == null) return null
        val bitmap = frame.toBitmap() ?: return null
        return try {
            detectBoardRect(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 同 {@link #detectBoardRect(Frame)}，但直接吃 {@link Bitmap}（便于单元测试与复用）。
     */
    fun detectBoardRect(bitmap: Bitmap): FloatArray? {
        val detections = predict(bitmap) ?: return null
        var best: FloatArray? = null
        var bestArea = 0f
        for (d in detections) {
            if (d.label != '0') continue
            val w = d.right - d.left
            val h = d.bottom - d.top
            if (w <= 0f || h <= 0f) continue
            val aspect = w / h
            if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) {
                Log.w(TAG, "YOLO 候选棋盘框比例异常 " + aspect + "，丢弃")
                continue
            }
            val area = w * h
            if (area > bestArea) {
                bestArea = area
                best = floatArrayOf(d.left, d.top, d.right, d.bottom)
            }
        }
        return best
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
    }

    // ═══ 内部实现 ═══

    private fun predict(image: Bitmap): List<Detection>? {
        return try {
            val rate = SIZE.toFloat() / maxOf(image.width, image.height).toFloat()
            val input = preprocess(image, rate)
            val inputName = session.inputNames.first()
            val tensor = OnnxTensor.createTensor(environment, arrayOf(input))
            val output = session.run(mapOf(inputName to tensor))
            tensor.close()
            val resultTensor = output.firstOrNull()?.value as? OnnxTensor ?: run {
                output.close()
                return null
            }
            try {
                parseOutput(resultTensor.floatBuffer, image.width, image.height, rate)
            } finally {
                output.close()
            }
        } catch (error: Exception) {
            Log.e(TAG, "YOLO 棋盘定位推理失败", error)
            null
        }
    }

    /** Bitmap → float[3][640][640] letterbox（空余处填灰 114）。 */
    private fun preprocess(image: Bitmap, rate: Float): Array<Array<FloatArray>> {
        val destW = (image.width * rate).toInt().coerceAtLeast(1)
        val destH = (image.height * rate).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(image, destW, destH, true)
        val pixels = IntArray(destW * destH)
        resized.getPixels(pixels, 0, destW, 0, 0, destW, destH)
        if (resized !== image) resized.recycle()

        val leftMargin = (SIZE - destW) / 2
        val topMargin = (SIZE - destH) / 2
        val gray = 114f / 255f

        val arr = Array(3) { Array(SIZE) { FloatArray(SIZE) } }
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (y >= topMargin && x >= leftMargin && y < topMargin + destH && x < leftMargin + destW) {
                    val px = pixels[(y - topMargin) * destW + (x - leftMargin)]
                    arr[0][y][x] = ((px shr 16) and 0xFF) / 255f
                    arr[1][y][x] = ((px shr 8) and 0xFF) / 255f
                    arr[2][y][x] = (px and 0xFF) / 255f
                } else {
                    arr[0][y][x] = gray
                    arr[1][y][x] = gray
                    arr[2][y][x] = gray
                }
            }
        }
        return arr
    }

    /** 解析 YOLOv11 转置输出（stride = 4 + 类数）。 */
    private fun parseOutput(buffer: FloatBuffer, imgW: Int, imgH: Int, rate: Float): List<Detection> {
        val output = FloatArray(buffer.limit())
        buffer.get(output)

        val xPadding = (SIZE - imgW * rate) / 2f
        val yPadding = (SIZE - imgH * rate) / 2f

        val size = output.size / STRIDE
        val candidates = mutableListOf<Detection>()
        for (i in 0 until size) {
            val base = i * STRIDE
            var maxClass = 0f
            var maxIndex = 0
            for (c in 0 until NUM_CLASSES) {
                val idx = reshape(base + c + 4, STRIDE, size)
                if (idx < output.size && output[idx] > maxClass) {
                    maxClass = output[idx]
                    maxIndex = c
                }
            }
            if (maxClass <= CONFIDENCE) continue
            val xIdx = reshape(base, STRIDE, size)
            val yIdx = reshape(base + 1, STRIDE, size)
            val wIdx = reshape(base + 2, STRIDE, size)
            val hIdx = reshape(base + 3, STRIDE, size)
            if (xIdx >= output.size || yIdx >= output.size || wIdx >= output.size || hIdx >= output.size) continue
            val cx = (output[xIdx] - xPadding) / rate
            val cy = (output[yIdx] - yPadding) / rate
            val w = output[wIdx] / rate
            val h = output[hIdx] / rate
            candidates.add(Detection(LABELS[maxIndex], cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, maxClass))
        }
        return nms(candidates)
    }

    /** YOLOv11 转置索引映射：把「通道优先」的线性下标还原成「框优先」。 */
    private fun reshape(i: Int, stride: Int, size: Int): Int {
        val n = i / stride
        val m = i % stride
        return n + m * size
    }

    /** 按类别分组的 NMS。 */
    private fun nms(list: List<Detection>): List<Detection> {
        val results = mutableListOf<Detection>()
        for (k in 0 until NUM_CLASSES) {
            val label = LABELS[k]
            val queue = PriorityQueue<Detection>(50) { a, b -> b.confidence.compareTo(a.confidence) }
            for (d in list) if (d.label == label) queue.add(d)
            while (queue.isNotEmpty()) {
                val a = queue.toTypedArray()
                results.add(a[0])
                queue.clear()
                for (j in 1 until a.size) {
                    if (boxIou(a[0], a[j]) < IOU_THRESHOLD) queue.add(a[j])
                }
            }
        }
        return results
    }

    private fun boxIou(a: Detection, b: Detection): Double {
        val inter = boxIntersection(a, b)
        val union = (a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union > 0.0) inter / union else 0.0
    }

    private fun boxIntersection(a: Detection, b: Detection): Double {
        val w = overlap(a.left, a.right, b.left, b.right)
        val h = overlap(a.top, a.bottom, b.top, b.bottom)
        return if (w > 0f && h > 0f) w.toDouble() * h.toDouble() else 0.0
    }

    private fun overlap(a1: Float, a2: Float, b1: Float, b2: Float): Float {
        val left = maxOf(a1, b1)
        val right = minOf(a2, b2)
        return right - left
    }
}
