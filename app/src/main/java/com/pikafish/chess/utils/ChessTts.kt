package com.pikafish.chess.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 象棋语音播报管理器（"解放双手"）。
 *
 * 用系统 TextToSpeech 把走棋记谱（如"红方 炮二平五"）朗读出来。
 * 纯本地、无依赖、离线可用（需手机装有 TTS 引擎，绝大多数安卓自带）。
 */
class ChessTts(private val context: Context) {

    companion object {
        private const val TAG = "ChessTts"
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var enabled = true

    /** 初始化（异步，TTS 引擎加载好后 onInit 回调） */
    fun init(onReady: (() -> Unit)? = null) {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val localeResult = tts?.setLanguage(Locale.CHINESE)
                    val langOk = localeResult == TextToSpeech.LANG_AVAILABLE ||
                        localeResult == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                        localeResult == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                    ready = true
                    // 设置语速与音调，让播报更自然
                    tts?.setSpeechRate(0.95f)
                    tts?.setPitch(1.0f)
                    if (!langOk) {
                        Log.w(TAG, "中文语音包不可用，将尝试默认语言")
                    }
                    onReady?.invoke()
                } else {
                    ready = false
                    Log.e(TAG, "TTS 初始化失败，status=$status")
                }
            }
        } catch (e: Exception) {
            ready = false
            Log.e(TAG, "TTS init error", e)
        }
    }

    /** 是否可用 */
    fun isReady(): Boolean = ready && tts != null

    /** 开/关语音播报 */
    fun setEnabled(v: Boolean) {
        enabled = v
    }

    fun isEnabled(): Boolean = enabled

    /** 播报一段话（中文） */
    fun speak(text: String) {
        if (!enabled || !ready) {
            return
        }
        try {
            val t0 = tts
            if (t0 == null) return
            t0.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chess_" + System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "speak error", e)
        }
    }

    /** 释放资源（Activity onDestroy 调用） */
    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        ready = false
    }
}