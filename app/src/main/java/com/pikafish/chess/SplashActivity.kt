package com.pikafish.chess

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pikafish.chess.heavy.HeavyStore
import java.io.File
import java.io.FileOutputStream

/**
 * 启动入口画面：
 *   - 首次安装（filesDir 内尚无 ONNX 模型）：先展示"安装组件进行中"进度条，
 *     把 APK 内经 ZIP 压缩的 3 个 ONNX 模型（合计约 48M，压缩后约 34M）预解压到
 *     filesDir，用真实字节进度更新百分条。解压完成显示"安装成功，开始对弈"并进主界面。
 *   - 非首次：组件已就绪，直接瞬时进主界面（宣传视频由主界面右下角小窗播放）。
 *
 *   ★ 轻量操作包首装：本机没有大件时，先打开「辎重包」控制台（HeavyShareActivity）接入大件。
 *
 *   ★ 与 OnnxBoardRecognizer 天然兼容：它判断 filesDir 目录下对应的 onnx 已存在就复用，
 *     不会重复复制 —— 本页预解压正好落到同一路径，拍照识别开箱即用。
 * 注意：继承原生 Activity（而非 AppCompatActivity），以便兼容清单中的平台全屏主题，
 *       避免 AppCompat 主题校验崩溃。
 */
open class SplashActivity : Activity() {
    /** ★ 八风不动：系统字体大小/字体风格怎么改都不影响本界面 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FixScale.fixedContext(newBase))
    }

    companion object {
        /** ★ 外部应用（QQ/文件管理器「打开其他应用」）送进来的棋谱：本地缓存文件绝对路径 + 原显示名。
         *  由主界面读取后入宝库「我的上传」并直接开谱。 */
        const val EXTRA_INCOMING_PATH = "pikafish_incoming_path"
        const val EXTRA_INCOMING_NAME = "pikafish_incoming_name"
    }

    /** 外部送进来的棋谱在本地的缓存文件（绝对路径）与原始显示名 */
    private var incomingPath: String? = null
    private var incomingName: String? = null

    // ★★ 首装静默默认档位（与 MainActivity 的 prefs 键一致，模拟真人把滑条划到该位置）
    private val firstRunPrefsName = "pikafish_chess_prefs"
    private val firstRunBoardScale = 0.88f    // 棋盘 88%（安全上限区 88%~89%，再高重启边线棋子会溢出屏幕）
    private val firstRunPieceScale = 1.17f    // 棋子 117%
    private val firstRunPieceSkin = 0         // 0 = 默认（璀璨黄金 3D 皮肤）

    /** ★ 静默默认：新用户第一手拿到即棋盘88%、棋子117%、璀璨黄金皮肤，存盘，不再让他们自己调 */
    private fun applyFirstRunVisualDefaults() {
        try {
            getSharedPreferences(firstRunPrefsName, Context.MODE_PRIVATE).edit()
                .putFloat("board_scale", firstRunBoardScale)
                .putFloat("piece_scale", firstRunPieceScale)
                .putInt("piece_skin", firstRunPieceSkin)
                .putFloat("abs_cell_size", 0f)      // 清掉绝对像素，让比例档位生效
                .putFloat("abs_piece_radius", 0f)
                .apply()
        } catch (e: Exception) {
            // 写失败不影响启动流程
        }
    }

    /** 首次启动需要预解压的 ONNX 模型（与 OnnxBoardRecognizer 的文件名/路径一致） */
    private val firstRunModels = listOf(
        "pose_model.onnx",
        "layout_model.onnx",
        "yolo_model.onnx"
    )

    /** 打开「辎重包」控制台的请求码（轻量包首装引导；原生 Activity 走 startActivityForResult）。 */
    private val REQ_HEAVY_CONSOLE = 1001

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 最先处理「打开其他应用」送进来的棋谱：趁本页存活、URI 读权限仍有效，立刻读进缓存目录。
        cacheIncomingFromOuterApp()

        // 全屏无标题栏（主题已为 NoTitleBar.Fullscreen，无需再隐藏 ActionBar）
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        // ★ 首装静默默认档位：只写一次。新用户第一手拿到即棋盘88%、棋子117%、璀璨黄金皮肤。
        //   读进度条期间顺手写好并存盘，用户无感；老用户/再次冷启用已保存值（不再覆盖）。
        if (!getSharedPreferences(firstRunPrefsName, Context.MODE_PRIVATE)
                .contains("first_run_visual_defaults_done")) {
            applyFirstRunVisualDefaults()
            getSharedPreferences(firstRunPrefsName, Context.MODE_PRIVATE).edit()
                .putBoolean("first_run_visual_defaults_done", true).apply()
        }

        if (isFirstRunPending()) {
            // ★ 轻量操作包：本机没有大件时，先引导用户提供「辎重包」；
            //   全量包内置了模型（builtinExists 为真）或已提供辎重包，则直接进入解压安装。
            if (hasHeavySource()) {
                showFirstRunInstallUi()
            } else {
                launchHeavyConsole()
            }
        } else {
            // ★ 非首次安装：组件已就绪，直接进主界面（瞬时），主界面自带右下角宣传视频
            goMain()
        }
    }

    /**
     * 是否还需要首次解压：只要 3 个模型任意一个缺失，就视为待补全。
     * 已全部就绪（含上次解压到一半后被中断的情况）则跳过。
     */
    private fun isFirstRunPending(): Boolean {
        return firstRunModels.any { model ->
            !File(filesDir, model).exists() || File(filesDir, model).length() <= 0L
        }
    }

    /** 首次启动：解压 ONNX 模型 + 进度条 */
    private fun showFirstRunInstallUi() {
        // 包一层 FrameLayout，给背景（和棋盘同色 #211005），避免纯黑
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(0xFF211005.toInt())

        // 纵向布局：应用标题 / 进度条 / 百分比文字 / 提示文字
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val pad = (18 * resources.displayMetrics.density).toInt()
        column.setPadding(pad, pad, pad, pad)

        val title = TextView(this).apply {
            text = "黄昏象棋"
            setTextColor(0xFFE8C44A.toInt())
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "正在准备对弈组件…"
            setTextColor(0xFFCCBB88.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val tvPercent = TextView(this).apply {
            text = "0%"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val tvHint = TextView(this).apply {
            text = "安装组件进行中，请稍候…"
            setTextColor(0xFF997733.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        // ★ 首次安装专属提醒：告知装机即备好「大而美棋盘 + 3D 立体皮肤」，安装完成一打开就是
        //   舒适全盘视角。只在本次安装提示，进入主界面后不再出现；用户后续怎么自定义都不干预。
        val tvDefaultNote = TextView(this).apply {
            text = "安装完成后即为您备好优选视角\n『大而美棋盘 · 3D 立体皮肤』，打开即可直接开弈"
            setTextColor(0xFFE8D9A8.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (22 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        column.addView(title)
        column.addView(subtitle)
        column.addView(progress, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (14 * resources.displayMetrics.density).toInt()
        ).apply { setMargins(0, (24 * resources.displayMetrics.density).toInt(), 0, 0) })
        column.addView(tvPercent)
        column.addView(tvHint)
        column.addView(tvDefaultNote)

        root.addView(column, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        // 后台线程执行解压（真实字节进度），完成后回主线程
        Thread {
            val totalBytes = totalFirstRunBytes()
            var doneBytes = 0L
            try {
                for (model in firstRunModels) {
                    val outFile = File(filesDir, model)
                    // 已解压完成的直接跳过
                    if (outFile.exists() && outFile.length() > 0L) {
                        doneBytes += outFile.length()
                        continue
                    }
                    val assetName = "models/$model"
                    val assetFd = try {
                        assets.openFd(assetName)
                    } catch (_: Exception) {
                        null
                    }
                    val input = if (assetFd != null) {
                        assets.openFd(assetName).createInputStream()
                    } else {
                        // ★ 轻量包从辎重包读取；全量包回退内置 assets。
                        HeavyStore.openLocalFirst(this@SplashActivity, assetName)
                    }
                    val output = FileOutputStream(outFile)
                    val buffer = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        output.write(buffer, 0, n)
                        doneBytes += n
                        // 每 128KB 更新一次进度，避免刷屏
                        if (n > 0) {
                            updateInstallProgress(doneBytes, totalBytes, progress, tvPercent, tvHint)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                    output.close()
                    input.close()
                }
                // 全部完成：提示"安装成功，开始对弈"，稍后进主界面
                mainHandler.post {
                    progress.progress = 100
                    tvPercent.text = "100%"
                    tvHint.text = "安装成功，开始对弈！"
                    goMain()
                }
            } catch (e: Exception) {
                // 解压失败也放行，避免永远卡在开屏（识别功能可能缺失，但能进主界面）
                mainHandler.post {
                    tvHint.text = "组件加载完成，开始对弈！"
                    goMain()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 计算本次需要解压的总字节数（只统计未就绪的文件） */
    private fun totalFirstRunBytes(): Long {
        var total = 0L
        for (model in firstRunModels) {
            val f = File(filesDir, model)
            if (f.exists() && f.length() > 0L) {
                total += f.length()
            } else {
                // ★ .onnx 在 APK 内是 ZIP 压缩存储的，openFd() 会抛"无 fd"异常，
                //   拿不到大小。改为真正流式数出未压缩字节，作为真实进度基准。
                total += uncompressLength("models/$model")
            }
        }
        return if (total <= 0L) 1L else total
    }

    /** 流式读出某资源解压后的字节数（无论 APK 内是否压缩都精确） */
    private fun uncompressLength(assetPath: String): Long {
        return try {
            HeavyStore.openLocalFirst(this, assetPath).use { ins ->
                val buf = ByteArray(64 * 1024)
                var len = 0L
                var n: Int
                while (ins.read(buf).also { n = it } != -1) len += n
                len
            }
        } catch (_: Exception) { 0L }
    }

    /** 线程安全的进度刷新（跳转到主线程，固定 100 上限） */
    private fun updateInstallProgress(
        done: Long, total: Long,
        progress: ProgressBar, tvPercent: TextView, tvHint: TextView
    ) {
        mainHandler.post {
            if (isFinishing) return@post
            val pct = ((done * 100) / total).toInt().coerceIn(0, 99)
            progress.progress = pct
            tvPercent.text = "${pct}%"
            tvHint.text = "安装组件进行中 $pct%"
        }
    }

    // ═════════════════ 辎重包（大件仓库）接入 ═════════════════

    /** 是否已有可用的辎重包来源（本机文件或远程直读），或内置大件（全量包）。 */
    private fun hasHeavySource(): Boolean {
        return HeavyStore.hasPackage(this) || HeavyStore.builtinExists(this, "models/layout_model.onnx")
    }

    /** 轻量包首装：打开「辎重包」控制台接入大件；返回后即可继续安装。 */
    private fun launchHeavyConsole() {
        try {
            startActivityForResult(
                Intent(this, HeavyShareActivity::class.java)
                    .putExtra(HeavyShareActivity.EXTRA_FIRST_RUN, true),
                REQ_HEAVY_CONSOLE
            )
        } catch (e: Exception) {
            goMain()
        }
    }

    /** 辎重包控制台返回后：还缺组件且已有来源就继续解压安装，否则直接进主界面。 */
    private fun continueAfterHeavy() {
        if (isFirstRunPending() && hasHeavySource()) showFirstRunInstallUi() else goMain()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HEAVY_CONSOLE) continueAfterHeavy()
    }

    /** 非首次启动：主界面自带宣传视频，本页不再重复整屏播发，直接进主界面 */

    private fun goMain() {
        if (isFinishing) return
        val i = Intent(this, MainActivity::class.java)
        // ★ 有外部送进来的棋谱就把「本地缓存路径 + 原名」一并转交，主界面据此自动入宝库并开谱。
        incomingPath?.let { p ->
            i.putExtra(EXTRA_INCOMING_PATH, p)
            i.putExtra(EXTRA_INCOMING_NAME, incomingName ?: File(p).name)
        }
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
    }

    /**
     * ★ 从 QQ / 文件管理器「打开其他应用」进来的棋谱文件：趁 URI 读权限还在（本页存活期间），
     *   立刻把内容读到缓存目录 —— 避免"把 content:// 转交下一个界面时授权已失效"的经典坑。
     *   随即由 goMain() 把本地路径交给主界面，由主界面入宝库「我的上传」并直接开谱。
     */
    private fun cacheIncomingFromOuterApp() {
        try {
            val uri = intent?.data ?: return
            if (uri.scheme != "content" && uri.scheme != "file") return
            val displayName = queryDisplayName(uri)
            val safe = (displayName ?: "incoming_${System.currentTimeMillis()}")
                .replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_")
                .ifBlank { "incoming_${System.currentTimeMillis()}" }
            val dir = File(cacheDir, "outer_open").apply { mkdirs() }
            val out = File(dir, safe)
            contentResolver.openInputStream(uri)?.use { ins ->
                out.outputStream().use { os -> ins.copyTo(os) }
            } ?: return
            if (out.length() <= 0L) { out.delete(); return }
            incomingPath = out.absolutePath
            incomingName = displayName ?: safe
        } catch (e: Exception) {
            android.util.Log.e("SplashActivity", "cacheIncomingFromOuterApp: ${e.message}", e)
        }
    }

    /** 取外部 URI 的显示名（content:// 查 OpenableColumns，file:// 退化为路径末段）。 */
    private fun queryDisplayName(uri: android.net.Uri): String? {
        val fromResolver = try {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        } catch (e: Exception) { null }
        return fromResolver?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }
}
