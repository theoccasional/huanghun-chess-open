package com.pikafish.chess.qipu

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

/** 匹配推荐：内置离线图文指南（assets/docs/cnvcs.html），不联网、不跳外链。 */
class GuideActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = WebView(this)
        wv.settings.apply { javaScriptEnabled = false; allowFileAccess = true }
        wv.webViewClient = WebViewClient()
        wv.loadUrl("file:///android_asset/docs/cnvcs.html")
        setContentView(wv)
    }
}
