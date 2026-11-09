package com.pikafish.chess

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 图像变换工具 — 仿射变换和透视变换的纯Kotlin实现
 * 
 * 用于 ONNX 棋盘识别流水线中的图像预处理：
 * 1. warpAffine — RTMPose 关键点检测的 top-down 仿射变换
 * 2. warpPerspective — 棋盘透视矫正
 */
object ImageTransforms {

    // ── 2D 点 ──
    data class Point(val x: Float, val y: Float)

    // ── 仿射变换矩阵 (2x3) ──
    // | a b c |
    // | d e f |
    // | 0 0 1 |
    data class AffineMatrix(val a: Float, val b: Float, val c: Float,
                            val d: Float, val e: Float, val f: Float) {
        /** 应用正向变换：源坐标 -> 目标坐标 */
        fun transform(p: Point): Point {
            return Point(a * p.x + b * p.y + c, d * p.x + e * p.y + f)
        }

        /** 计算逆矩阵 */
        fun inverse(): AffineMatrix {
            val det = a * e - b * d
            if (abs(det) < 1e-6f) return AffineMatrix(1f, 0f, 0f, 0f, 1f, 0f)
            val invDet = 1f / det
            return AffineMatrix(
                e * invDet, -b * invDet, (b * f - c * e) * invDet,
                -d * invDet, a * invDet, (c * d - a * f) * invDet
            )
        }
    }

    // ── 透视变换矩阵 (3x3) ──
    // | a b c |
    // | d e f |
    // | g h 1 |
    data class PerspMatrix(val a: Float, val b: Float, val c: Float,
                           val d: Float, val e: Float, val f: Float,
                           val g: Float, val h: Float) {
        /** 应用正向变换 */
        fun transform(p: Point): Point {
            val w = g * p.x + h * p.y + 1f
            return Point((a * p.x + b * p.y + c) / w, (d * p.x + e * p.y + f) / w)
        }

        /** 计算逆矩阵 (3x3 matrix inverse) */
        fun inverse(): PerspMatrix {
            // 3x3 matrix:
            // | a b c |
            // | d e f |
            // | g h 1 |
            val det = a * (e * 1 - f * h) - b * (d * 1 - f * g) + c * (d * h - e * g)
            if (abs(det) < 1e-10f) return PerspMatrix(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
            val invDet = 1f / det
            // Adjugate / cofactor approach
            val ia = (e * 1 - f * h) * invDet
            val ib = -(b * 1 - c * h) * invDet
            val ic = (b * f - c * e) * invDet
            val id = -(d * 1 - f * g) * invDet
            val ie = (a * 1 - c * g) * invDet
            val iff = -(a * f - c * d) * invDet
            val ig = (d * h - e * g) * invDet
            val ih = -(a * h - b * g) * invDet
            // The inverse matrix last row should have 1 in bottom-right
            // But we need to normalize so that bottom-right = 1
            // Inverse of | a b c |   =  | ia ib ic |
            //            | d e f |      | id ie if |
            //            | g h 1 |      | ig ih ii |
            // where ii = (a*e - b*d) * invDet
            val ii = (a * e - b * d) * invDet
            // Normalize by dividing by ii so bottom-right = 1
            return PerspMatrix(ia / ii, ib / ii, ic / ii,
                               id / ii, ie / ii, iff / ii,
                               ig / ii, ih / ii)
        }
    }

    /**
     * 计算3点对应的仿射变换矩阵
     * @param src 3个源点
     * @param dst 3个目标点
     * @return 仿射变换矩阵 (src -> dst)
     */
    fun getAffineTransform(src: List<Point>, dst: List<Point>): AffineMatrix {
        require(src.size == 3 && dst.size == 3)
        // Solve: x' = a*x + b*y + c, y' = d*x + e*y + f
        // Using 3 point pairs
        val (s0, s1, s2) = Triple(src[0], src[1], src[2])
        val (d0, d1, d2) = Triple(dst[0], dst[1], dst[2])

        // Solve for a, b, c using Cramer's rule
        val det = (s1.x - s0.x) * (s2.y - s0.y) - (s2.x - s0.x) * (s1.y - s0.y)
        if (abs(det) < 1e-6f) return AffineMatrix(1f, 0f, 0f, 0f, 1f, 0f)

        val a = ((d1.x - d0.x) * (s2.y - s0.y) - (d2.x - d0.x) * (s1.y - s0.y)) / det
        val b = ((d2.x - d0.x) * (s1.x - s0.x) - (d1.x - d0.x) * (s2.x - s0.x)) / det
        val c = d0.x - a * s0.x - b * s0.y

        val d = ((d1.y - d0.y) * (s2.y - s0.y) - (d2.y - d0.y) * (s1.y - s0.y)) / det
        val e = ((d2.y - d0.y) * (s1.x - s0.x) - (d1.y - d0.y) * (s2.x - s0.x)) / det
        val f = d0.y - d * s0.x - e * s0.y

        return AffineMatrix(a, b, c, d, e, f)
    }

    /**
     * 计算4点对应的透视变换矩阵
     * @param src 4个源点 (TL, TR, BL, BR)
     * @param dst 4个目标点
     * @return 透视变换矩阵 (src -> dst)
     */
    fun getPerspectiveTransform(src: List<Point>, dst: List<Point>): PerspMatrix {
        require(src.size == 4 && dst.size == 4)

        // Solve: x' = (a*x + b*y + c) / (g*x + h*y + 1)
        //         y' = (d*x + e*y + f) / (g*x + h*y + 1)
        // Rearranged: 
        //   a*x + b*y + c - g*x*x' - h*y*x' = x'
        //   d*x + e*y + f - g*x*y' - h*y*y' = y'
        // 8 equations, 8 unknowns: a,b,c,d,e,f,g,h

        // Build 8x8 matrix A and 8x1 vector B
        val A = Array(8) { FloatArray(8) }
        val B = FloatArray(8)

        for (i in 0 until 4) {
            val (x, y) = src[i].x to src[i].y
            val (xp, yp) = dst[i].x to dst[i].y

            // Equation for x': a*x + b*y + c - g*x*x' - h*y*x' = x'
            A[i * 2][0] = x
            A[i * 2][1] = y
            A[i * 2][2] = 1f
            A[i * 2][3] = 0f
            A[i * 2][4] = 0f
            A[i * 2][5] = 0f
            A[i * 2][6] = -x * xp
            A[i * 2][7] = -y * xp
            B[i * 2] = xp

            // Equation for y': d*x + e*y + f - g*x*y' - h*y*y' = y'
            A[i * 2 + 1][0] = 0f
            A[i * 2 + 1][1] = 0f
            A[i * 2 + 1][2] = 0f
            A[i * 2 + 1][3] = x
            A[i * 2 + 1][4] = y
            A[i * 2 + 1][5] = 1f
            A[i * 2 + 1][6] = -x * yp
            A[i * 2 + 1][7] = -y * yp
            B[i * 2 + 1] = yp
        }

        // Solve using Gaussian elimination with partial pivoting
        val solution = solveLinearSystem(A, B)

        return PerspMatrix(
            solution[0], solution[1], solution[2],
            solution[3], solution[4], solution[5],
            solution[6], solution[7]
        )
    }

    /**
     * 高斯消元法求解线性方程组 Ax=B
     */
    private fun solveLinearSystem(A: Array<FloatArray>, B: FloatArray): FloatArray {
        val n = B.size
        val aug = Array(n) { FloatArray(n + 1) }
        for (i in 0 until n) {
            for (j in 0 until n) aug[i][j] = A[i][j]
            aug[i][n] = B[i]
        }

        // Forward elimination with partial pivoting
        for (col in 0 until n) {
            var maxRow = col
            var maxVal = abs(aug[col][col])
            for (row in col + 1 until n) {
                if (abs(aug[row][col]) > maxVal) {
                    maxVal = abs(aug[row][col])
                    maxRow = row
                }
            }
            if (maxRow != col) {
                val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp
            }
            if (abs(aug[col][col]) < 1e-10f) continue
            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (j in col..n) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }

        // Back substitution
        val x = FloatArray(n)
        for (i in n - 1 downTo 0) {
            x[i] = aug[i][n]
            for (j in i + 1 until n) {
                x[i] -= aug[i][j] * x[j]
            }
            if (abs(aug[i][i]) > 1e-10f) x[i] /= aug[i][i]
        }
        return x
    }

    /**
     * 仿射变换 — 对 Bitmap 应用 2x3 仿射矩阵（批量像素优化版）
     * @param src 源 Bitmap
     * @param matrix 正向仿射矩阵 (src -> dst)
     * @param dstW 输出宽度
     * @param dstH 输出高度
     * @return 变换后的 Bitmap
     */
    fun warpAffine(src: Bitmap, matrix: AffineMatrix, dstW: Int, dstH: Int): Bitmap {
        val output = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val invMatrix = matrix.inverse()
        val srcW = src.width
        val srcH = src.height

        // ★ 批量读取源像素（避免逐像素 JNI 调用）
        val srcPixels = IntArray(srcW * srcH)
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val dstPixels = IntArray(dstW * dstH)

        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val srcX = invMatrix.a * x + invMatrix.b * y + invMatrix.c
                val srcY = invMatrix.d * x + invMatrix.e * y + invMatrix.f
                dstPixels[y * dstW + x] = sampleBilinearArray(srcPixels, srcX, srcY, srcW, srcH)
            }
        }
        output.setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
        return output
    }

    /**
     * 透视变换 — 对 Bitmap 应用 3x3 透视矩阵（批量像素优化版）
     * @param src 源 Bitmap
     * @param matrix 正向透视矩阵 (src -> dst)
     * @param dstW 输出宽度
     * @param dstH 输出高度
     * @return 变换后的 Bitmap
     */
    fun warpPerspective(src: Bitmap, matrix: PerspMatrix, dstW: Int, dstH: Int): Bitmap {
        val output = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val invMatrix = matrix.inverse()
        val srcW = src.width
        val srcH = src.height

        // ★ 批量读取源像素
        val srcPixels = IntArray(srcW * srcH)
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val dstPixels = IntArray(dstW * dstH)

        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val w = invMatrix.g * x + invMatrix.h * y + 1f
                val srcX = (invMatrix.a * x + invMatrix.b * y + invMatrix.c) / w
                val srcY = (invMatrix.d * x + invMatrix.e * y + invMatrix.f) / w
                dstPixels[y * dstW + x] = sampleBilinearArray(srcPixels, srcX, srcY, srcW, srcH)
            }
        }
        output.setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
        return output
    }

    /**
     * 双线性插值采样 — 基于像素数组（无 JNI 开销）
     */
    private fun sampleBilinearArray(pixels: IntArray, x: Float, y: Float, w: Int, h: Int): Int {
        if (x < 0 || x >= w - 1 || y < 0 || y >= h - 1) {
            // ★ 必须用黑色填充，匹配 cv2.warpAffine 的默认 BORDER_CONSTANT 行为
            //   之前用白色，导致模型看到的输入与训练数据完全不同
            //   经过 ImageNet 归一化后：黑色=-2.1，白色=+2.6，差异巨大
            return Color.BLACK
        }
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = x0 + 1
        val y1 = y0 + 1
        val dx = x - x0
        val dy = y - y0

        val p00 = pixels[y0 * w + x0]
        val p10 = pixels[y0 * w + x1]
        val p01 = pixels[y1 * w + x0]
        val p11 = pixels[y1 * w + x1]

        val r = bilinear(Color.red(p00), Color.red(p10), Color.red(p01), Color.red(p11), dx, dy)
        val g = bilinear(Color.green(p00), Color.green(p10), Color.green(p01), Color.green(p11), dx, dy)
        val b = bilinear(Color.blue(p00), Color.blue(p10), Color.blue(p01), Color.blue(p11), dx, dy)
        return Color.rgb(r, g, b)
    }

    private fun bilinear(v00: Int, v10: Int, v01: Int, v11: Int, dx: Float, dy: Float): Int {
        val v0 = v00 + ((v10 - v00) * dx).toInt()
        val v1 = v01 + ((v11 - v01) * dx).toInt()
        return v0 + ((v1 - v0) * dy).toInt()
    }

    /**
     * 获取第三个点（用于仿射变换）
     * 给定点 a, b，返回垂直于 ab 方向的点
     */
    fun getThirdPoint(a: Point, b: Point): Point {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Point(b.x + (-dy), b.y + dx)
    }

    /**
     * RTMPose 的 warp matrix 计算
     * @param center 棋盘中心
     * @param scale 棋盘尺度 [w, h]
     * @param inputSize 目标尺寸 [w, h] (256, 256)
     * @param inv 是否计算逆矩阵
     */
    fun getWarpMatrix(center: Point, scale: Point, inputW: Int, inputH: Int, inv: Boolean): AffineMatrix {
        // ★ scale 直接是像素值（匹配 Python get_warp_matrix: src_w, src_h = scale[:2]）
        //   之前错误地除以 200，导致仿射变换区域缩小 200 倍
        val srcW = scale.x
        val dstW = inputW.toFloat()
        val dstH = inputH.toFloat()

        // ★ 方向向量沿 x 轴（匹配 Python: src_dir = [src_w * -0.5, 0.]）
        //   之前错误地用 y 轴方向，相当于旋转 90 度
        val srcDir = Point(-0.5f * srcW, 0f)
        val dstDir = Point(-0.5f * dstW, 0f)

        // Source points
        val srcCenter = center
        val srcP1 = Point(srcCenter.x + srcDir.x, srcCenter.y + srcDir.y)
        val srcP2 = getThirdPoint(srcCenter, srcP1)

        // Destination points
        val dstCenter = Point(dstW * 0.5f, dstH * 0.5f)
        val dstP1 = Point(dstCenter.x + dstDir.x, dstCenter.y + dstDir.y)
        val dstP2 = getThirdPoint(dstCenter, dstP1)

        val src = listOf(srcCenter, srcP1, srcP2)
        val dst = listOf(dstCenter, dstP1, dstP2)

        return if (inv) {
            getAffineTransform(dst, src)
        } else {
            getAffineTransform(src, dst)
        }
    }

    private fun abs(v: Float): Float = if (v < 0) -v else v
}
