package com.pikafish.chess.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * ★ 清新人生：《清新人生手册》全屏阅读器（独立 Activity，返回键 100% 可靠）
 *
 * 设计要点：
 *  - 全屏 WebView 承载 assets/manual/qingxin.html（全本一页贯穿，可整页划拉、顶部有「随笔」套娃）。
 *  - 底部底栏提供 后退/前进/目录/彻底返回，与下棋底栏操作同构，顺手度高。
 *  - 彻底返回：先于屏幕中央动态倒计时 3→2→1→正在退出，让用户感知“确实在退出”而非卡死，随后秒撤回主界面。
 *  - 阅读进度用 localStorage 按比例记忆，再次进入自动回到上次位置（100% 保底）。
 */
class ReaderActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var exitOverlay: FrameLayout
    private lateinit var countdownText: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private var exiting = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.setBackgroundColor(0xFFF6EFE3.toInt())
        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        ws.setSupportZoom(false)
        ws.builtInZoomControls = false
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        ws.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.setLayerType(WebView.LAYER_TYPE_NONE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                try {
                    view?.evaluateJavascript(
                        "try{window.Reader_onBack&&window.Reader_onBack.name||'ok';" +
                        "if(typeof window.Reader_onBack!=='function'){" +
                        "window.Reader_onBack=function(){return 'exit';};}('ok');}catch(e){('exit');}",
                        null
                    )
                } catch (_: Exception) {}
            }
        }

        // 屏幕中央的退出倒计时浮层（先灭灯，再动态 3→2→1→正在退出）
        exitOverlay = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
        }
        countdownText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 96f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        exitOverlay.addView(countdownText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val root = FrameLayout(this)
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(exitOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // JS 桥：页面「彻底返回」→ 触发退出倒计时 → 秒回主界面
        val bridge = object : Any() {
            @JavascriptInterface
            fun closeReader() { runOnUiThread { startExitCountdown() } }
        }
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.loadUrl("file:///android_asset/manual/qingxin.html")
    }

    /** 系统返回键：先把控制权交给页面逐级返回（收搜索→收目录→退出）；
     *  页面返回"handled"表示已处理一层并驻留；其余一律触发退出倒计时，保证不会“卡死退不出”。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (exiting) return
        try {
            webView.evaluateJavascript("Reader_onBack();", { r ->
                val res = (r ?: "").trim().trim('"')
                if (!"handled".equals(res, ignoreCase = true)) startExitCountdown()
            })
        } catch (e: Exception) {
            startExitCountdown()
        }
    }

    /** 屏幕中央动态倒计时 3→2→1→正在退出，让人感知“真的在退”，随后撤走过渡动画秒回主界面。 */
    private fun startExitCountdown() {
        if (exiting) return
        exiting = true
        exitOverlay.visibility = View.VISIBLE
        val steps = arrayOf("3", "2", "1", "正在退出")
        var i = 0
        fun tick() {
            if (i < steps.size) {
                countdownText.text = steps[i]
                i++
                uiHandler.postDelayed({ tick() }, 380)
            } else {
                snapExit()
            }
        }
        tick()
    }

    /** 秒回主界面：取消窗口过渡动画，一触即发。 */
    private fun snapExit() {
        try { finish() } catch (_: Exception) {}
        try { overridePendingTransition(0, 0) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        exiting = true
        uiHandler.removeCallbacksAndMessages(null)
        try {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.stopLoading()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}