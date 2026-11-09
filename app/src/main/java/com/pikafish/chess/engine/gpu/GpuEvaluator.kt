package com.pikafish.chess.engine.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log

/**
 * ★★★ GPU 神经引擎 —— OpenGL ES 3.0 片元着色器批量推理（与优步同源的 GPU 加速方式） ★★★
 *
 * 说明：此前版本采用了 OpenGL ES 3.1 的「计算着色器(Compute Shader)+SSBO」来做推理，
 * 但很多中低端手机只支持到 GLES 3.0，不支持 GLES 3.1，于是出现"计算着色器编译失败"→引擎起不来。
 * 优步象棋能在任意手机上跑，正是因为它用 ES 3.0 就有的**片元着色器(Fragment Shader)**做网络前向。
 *
 * 本实现改为与优步同架构：
 *   - 离屏 EGL 上下文：EGL 3.0（GLES 3.0，兼容面极广）
 *   - 网络权重(Tensor)上传为纹理(GLenum R32F)，批量局面特征也上传为纹理
 *   - 用一块全屏三角形 + 片元着色器，把「一批局面」在一次 draw 里并行算出每个局面评分
 *   - 渲染到一个 FBO(浮点颜色缓冲)，再 glReadPixels 把每条评分读回
 *
 * 网络结构与 GpuChessNet.forwardCpu 完全一致（CPU 参考用于离线校验 + GPU 不可用时兜底）。
 * GPU 也不可用时自动回退 CPU 前向，引擎任何设备都能走棋。
 */
class GpuEvaluator {

    companion object {
        private const val TAG = "GpuEvaluator"
        const val N_IN = GpuChessNet.N_IN
        const val HID = GpuChessNet.HID
        const val MAX_BATCH = 512
        private const val WEIGHT_SIZE = N_IN * HID + HID + HID + 1   // W + b + w2 + b2
        private const val GL_R32F = 0x822E          // GL_R32F
        private const val GL_RGBA32F = 0x8814       // GL_RGBA32F
        private const val GL_RED = 0x1903           // 单通道上传格式
    }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var fbo = 0
    private var colorTex = 0       // RGBA32F 输出，宽 MAX_BATCH 高 1
    private var featTex = 0        // R32F 输入，(宽 630, 高 MAX_BATCH)，行=sample，列=feature
    private var weightTex = 0      // R32F 权重，(宽 630, 高 8)，像素(i,j)=W[i*HID+j]

    private var uFeatLoc = -1
    private var uWeightLoc = -1
    private var uBLoc = -1
    private var uW2Loc = -1
    private var uB2Loc = -1

    @Volatile private var initialized = false

    /** 读取回用缓冲（RGBA，MAX_BATCH*4 个 float） */
    private var readBuffer: java.nio.FloatBuffer? = null

    /** 网络实例（CPU 参考 / 权重快照来源） */
    @Volatile private var net: GpuChessNet? = null

    // 已上传权重快照，避免重复上传
    private var uploadedWeights: FloatArray? = null

    val isReady: Boolean get() = initialized

    fun bindNet(n: GpuChessNet) { net = n }

    private val VERTEX_SHADER = """
        #version 300 es
        void main() {
            int id = gl_VertexID;
            vec2 p;
            if (id == 0)      p = vec2(-1.0, -1.0);
            else if (id == 1) p = vec2( 3.0, -1.0);
            else              p = vec2(-1.0,  3.0);
            gl_Position = vec4(p, 0.0, 1.0);
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        const int N_IN = $N_IN;
        const int HID  = $HID;
        uniform sampler2D uFeat;    // 行=sample, 列=feature；texel(i,y)=第y个局面的第i个特征
        uniform sampler2D uWeight;  // 行=hidden, 列=feature；texel(i,j)=W[i*HID+j]
        uniform float uB[HID];
        uniform float uW2[HID];
        uniform float uB2;
        out vec4 o;
        void main() {
            int y = int(gl_FragCoord.x);       // 每个像素列 = 一个局面(sample)
            float h[HID];
            for (int j = 0; j < HID; j++) {
                float acc = uB[j];
                for (int i = 0; i < N_IN; i++) {
                    float f = texelFetch(uFeat, ivec2(i, y), 0).r;
                    float w = texelFetch(uWeight, ivec2(i, j), 0).r;
                    acc += f * w;
                }
                h[j] = acc;
            }
            // 单元0 线性，单元1..HID-1 ReLU（与 CPU 参考一致）
            float s = uB2 + uW2[0] * h[0];
            for (int j = 1; j < HID; j++) {
                s += uW2[j] * max(h[j], 0.0);
            }
            o = vec4(s, 0.0, 0.0, 1.0);
        }
    """.trimIndent()

    /** 在调用线程（专用 GPU 线程）上初始化 EGL 离屏上下文 + 编译片元着色器 + 建 FBO/纹理 */
    fun init(): String? {
        return try {
            teardown()
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return "EGL 无显示"
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "EGL initialize 失败"

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val numCfg = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, cfgs, 0, 1, numCfg, 0) || numCfg[0] == 0) {
                val fallback = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_NONE
                )
                if (!EGL14.eglChooseConfig(display, fallback, 0, cfgs, 0, 1, numCfg, 0) || numCfg[0] == 0) {
                    return "EGL 无 3.x 配置"
                }
            }

            surface = EGL14.eglCreatePbufferSurface(
                display, cfgs[0]!!,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (surface == EGL14.EGL_NO_SURFACE) return "EGL Pbuffer 创建失败"

            context = EGL14.eglCreateContext(
                display, cfgs[0]!!, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return "EGL 3 上下文创建失败"

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return "EGL makeCurrent 失败"

            val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return "顶点着色器编译失败"
            val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return "片元着色器编译失败"
            program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            val linkOk = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkOk, 0)
            if (linkOk[0] == 0) return "着色器链接失败: ${GLES30.glGetProgramInfoLog(program)}"

            uFeatLoc = GLES30.glGetUniformLocation(program, "uFeat")
            uWeightLoc = GLES30.glGetUniformLocation(program, "uWeight")
            uBLoc = GLES30.glGetUniformLocation(program, "uB")
            uW2Loc = GLES30.glGetUniformLocation(program, "uW2")
            uB2Loc = GLES30.glGetUniformLocation(program, "uB2")

            GLES30.glUseProgram(program)

            // ── 输入特征纹理 R32F (630 x MAX_BATCH) ──
            val tx = IntArray(1)
            GLES30.glGenTextures(1, tx, 0)
            featTex = tx[0]
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, featTex)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GL_R32F, N_IN, MAX_BATCH, 0,
                GL_RED, GLES30.GL_FLOAT, null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            // ── 权重纹理 R32F (630 x HID) ──
            GLES30.glGenTextures(1, tx, 0)
            weightTex = tx[0]
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, weightTex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            // ── 输出颜色缓冲 RGBA32F (MAX_BATCH x 1) + FBO ──
            GLES30.glGenTextures(1, tx, 0)
            colorTex = tx[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GL_RGBA32F, MAX_BATCH, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val fb = IntArray(1)
            GLES30.glGenFramebuffers(1, fb, 0)
            fbo = fb[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, colorTex, 0
            )
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                return "FBO 不完整(浮点缓冲)"
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            readBuffer = java.nio.ByteBuffer.allocateDirect(MAX_BATCH * 4 * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            uploadedWeights = null

            initialized = true
            Log.i(TAG, "GPU 神经推理就绪（OpenGL ES 3.0 片元着色器）")
            null
        } catch (e: Exception) {
            Log.e(TAG, "GPU 初始化失败", e)
            teardown()
            "GPU 初始化失败: ${e.message}"
        }
    }

    private fun compileShader(type: Int, src: String): Int? {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "shader compile error: ${GLES30.glGetShaderInfoLog(s)}")
            GLES30.glDeleteShader(s)
            return null
        }
        return s
    }

    /**
     * 批量评估。@param feats 长度为 batchN * N_IN 的扁平特征
     * @return 长度为 batchN 的评分（正=对各行走棋方有利）
     */
    @Synchronized
    fun evaluateBatch(feats: FloatArray, batchN: Int): FloatArray {
        val n = batchN.coerceIn(1, MAX_BATCH)
        val out = FloatArray(n)
        val nRef = net
        if (!initialized || feats.size < n * N_IN || nRef == null) {
            // GPU 不可用：CPU 参考逐条前向，保证引擎始终可用
            for (i in 0 until n) {
                val x = FloatArray(N_IN)
                System.arraycopy(feats, i * N_IN, x, 0, N_IN)
                out[i] = nRef?.forwardCpuNet(x) ?: 0f
            }
            return out
        }
        // 小批量（≤3，单局面评估）：GPU 调度+同步往返开销>收益，用 CPU 快路径，确保"快"
        if (n < 4) {
            for (i in 0 until n) {
                val x = FloatArray(N_IN)
                System.arraycopy(feats, i * N_IN, x, 0, N_IN)
                out[i] = nRef.forwardCpuNet(x)
            }
            return out
        }
        try {
            // 确保权重与 net 同步（只在变化时重传）
            val current = weightsSnapshot()
            if (uploadedWeights == null || !java.util.Arrays.equals(uploadedWeights, current)) {
                uploadWeights(current)
                uploadedWeights = current
            }
            runBatch(feats, n, out)
            return out
        } catch (e: Exception) {
            Log.e(TAG, "GPU 批量评估失败，回退 CPU", e)
            for (i in 0 until n) {
                val x = FloatArray(N_IN)
                System.arraycopy(feats, i * N_IN, x, 0, N_IN)
                out[i] = nRef.forwardCpuNet(x)
            }
            return out
        }
    }

    /** 上传权重到 GPU：wTex + uniform b/w2/b2 */
    private fun uploadWeights(snap: FloatArray) {
        val bOff = N_IN * HID
        val w2Off = bOff + HID
        GLES30.glUseProgram(program)

        // 将 W[i*HID+j] 重新打包为 行=j 列=i 的纹理布局
        val wTex = FloatArray(N_IN * HID)
        for (j in 0 until HID) {
            for (i in 0 until N_IN) {
                wTex[j * N_IN + i] = snap[i * HID + j]
            }
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, weightTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GL_R32F, N_IN, HID, 0,
            GL_RED, GLES30.GL_FLOAT, java.nio.FloatBuffer.wrap(wTex)
        )

        if (uBLoc >= 0) {
            val b = FloatArray(HID)
            System.arraycopy(snap, bOff, b, 0, HID)
            GLES30.glUniform1fv(uBLoc, HID, b, 0)
        }
        if (uW2Loc >= 0) {
            val w2 = FloatArray(HID)
            System.arraycopy(snap, w2Off, w2, 0, HID)
            GLES30.glUniform1fv(uW2Loc, HID, w2, 0)
        }
        if (uB2Loc >= 0) GLES30.glUniform1f(uB2Loc, snap[WEIGHT_SIZE - 1])
    }

    /** 一次性把 n 个局面特征渲染到 FBO（宽 n 高 1）并读回 */
    private fun runBatch(feats: FloatArray, n: Int, out: FloatArray) {
        GLES30.glUseProgram(program)

        // 上传特征：行 0..n-1（每个局面一行 630 特征）
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, featTex)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, N_IN, n,
            GL_RED, GLES30.GL_FLOAT, java.nio.FloatBuffer.wrap(feats, 0, n * N_IN)
        )

        GLES30.glUniform1i(uFeatLoc, 0)
        GLES30.glUniform1i(uWeightLoc, 1)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, n, 1)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 读回评分（RGBA，每像素4个float，评分在 .r）
        val rb = readBuffer ?: run {
            val nb = java.nio.ByteBuffer.allocateDirect(MAX_BATCH * 4 * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            readBuffer = nb; nb
        }
        rb.clear()
        GLES30.glReadPixels(0, 0, n, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, rb)
        rb.rewind()
        for (i in 0 until n) out[i] = rb.get(i * 4)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** 单局面评估（GPU 一次，失败回 CPU） */
    fun evaluateSingle(x: FloatArray): Float {
        if (initialized) {
            val r = evaluateBatch(x, 1)
            if (r.isNotEmpty()) return r[0]
        }
        val n = net ?: return 0f
        return n.forwardCpuNet(x)
    }

    /** 当前 GpuChessNet 里的权重快照（供上传 GPU） */
    private fun weightsSnapshot(): FloatArray {
        return net?.copyLiveWeights() ?: FloatArray(WEIGHT_SIZE)
    }

    fun teardown() {
        initialized = false
        if (program != 0) { GLES30.glDeleteProgram(program); program = 0 }
        if (featTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(featTex), 0); featTex = 0 }
        if (weightTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(weightTex), 0); weightTex = 0 }
        if (colorTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(colorTex), 0); colorTex = 0 }
        if (fbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
        if (display != EGL14.EGL_NO_DISPLAY) {
            try { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Exception) {}
            try { EGL14.eglDestroySurface(display, surface) } catch (_: Exception) {}
            try { EGL14.eglDestroyContext(display, context) } catch (_: Exception) {}
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }
    }
}