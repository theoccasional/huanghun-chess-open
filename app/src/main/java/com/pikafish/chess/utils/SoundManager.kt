package com.pikafish.chess.utils

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log

/**
 * 音效管理器
 *
 * 职责：
 *  1. 走子/吃子/将军等短促音效 —— SoundPool，音量可调(音效音量)。
 *  2. 背景音乐(BGM) —— MediaPlayer 常驻(companion 静态对象，跨 Activity 共享)，支持：
 *       关 / 背景音一(snd/bgm1.ogg) / 背景音二(snd/bgm2.ogg)，音量独立调节。
 *  3. 音效音量与背景音选择均持久化到 Sp("pikafish_audio")，下次启动自动恢复。
 */
class SoundManager {

    companion object {
        private const val TAG = "SoundManager"
        private const val PREFS = "pikafish_audio"
        private const val KEY_BGM = "bgm_index"          // 0=关 1=背景音一 2=背景音二
        private const val KEY_SFX_VOL = "sfx_volume"     // 0..100
        private const val KEY_BGM_VOL = "bgm_volume"     // 0..100
        private const val DEF_SFX_VOL = 80
        private const val DEF_BGM_VOL = 70

        // ── BGM 资源定义（三个直接选项，全可见，次序对齐） ──
        // 音乐1=bg.mp3(原创bgm) 音乐2=bgm2.ogg(李诗然) 音乐3=bgm1.ogg(葬花吟·5.66MB OGG)
        val BGM_RES = arrayOf("snd/bg.mp3", "snd/bgm2.ogg", "snd/bgm1.ogg")
        val BGM_NAMES = arrayOf("音乐1", "音乐2", "音乐3")

        // ── 常驻背景音乐 ──
        private var bgmPlayer: MediaPlayer? = null
        private var currentBgm = 0          // 0=关
        private var bgmVolume = DEF_BGM_VOL

        @Synchronized
        fun playBgm(context: Context, index: Int) {
            currentBgm = index
            savePref(context) { it.edit().putInt(KEY_BGM, index).apply() }
            try {
                if (index <= 0) { stopBgm(); return }
                val assetIndex = index - 1
                if (bgmPlayer != null && bgmPlayer?.isPlaying == true) {
                    // 已在播且是同一条，无需重建
                }
                val assetFd = context.assets.openFd(BGM_RES[assetIndex])
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                p.setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)
                p.isLooping = true
                p.setVolume(bgmVolume / 100f, bgmVolume / 100f)
                p.prepare()
                if (bgmPlayer != null) {
                    try { bgmPlayer?.release() } catch (_: Exception) {}
                }
                bgmPlayer = p
                p.start()
            } catch (e: Exception) {
                Log.w(TAG, "playBgm($index) failed: ${e.message}")
            }
        }

        @Synchronized
        fun stopBgm() {
            try {
                bgmPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            bgmPlayer = null
        }

        /** 试听：仅播放/停止所选背景音，不写入持久化选择（真正生效靠 playBgm）。index=0 即停。 */
        @Synchronized
        fun previewBgm(context: Context, index: Int) {
            if (index <= 0) { stopBgm(); return }
            try {
                val assetFd = context.assets.openFd(BGM_RES[index - 1])
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                p.setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)
                p.isLooping = true
                p.setVolume(bgmVolume / 100f, bgmVolume / 100f)
                p.prepare()
                if (bgmPlayer != null) {
                    try { bgmPlayer?.release() } catch (_: Exception) {}
                }
                bgmPlayer = p
                p.start()
            } catch (e: Exception) {
                Log.w(TAG, "previewBgm($index) failed: ${e.message}")
            }
        }

        @Synchronized
        fun bgmIndex(): Int = currentBgm

        @Synchronized
        fun setBgmVolume(context: Context, v: Int) {
            bgmVolume = v.coerceIn(0, 100)
            savePref(context) { it.edit().putInt(KEY_BGM_VOL, bgmVolume).apply() }
            try {
                bgmPlayer?.setVolume(bgmVolume / 100f, bgmVolume / 100f)
            } catch (_: Exception) {}
        }

        @Synchronized
        fun bgmVolumePref(): Int = bgmVolume

        /** 从 prefs 恢复上次的背景音选择与音量：返回当前背景音 index(0=关) */
        fun restoreBgm(context: Context): Int {
            val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            bgmVolume = sp.getInt(KEY_BGM_VOL, DEF_BGM_VOL).coerceIn(0, 100)
            currentBgm = sp.getInt(KEY_BGM, 0)
            return currentBgm
        }

        private fun savePref(context: Context, fn: (SharedPreferences) -> Unit) {
            try {
                fn(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            } catch (_: Exception) {}
        }
    }

    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()
    private var enabled = true
    private var sfxVolume = DEF_SFX_VOL
    @Volatile private var checkAlt = false   // ★ 将军男/女声交替标记

    fun loadSounds(context: Context) {
        try {
            // 恢复音效音量偏好
            try {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                sfxVolume = sp.getInt(KEY_SFX_VOL, DEF_SFX_VOL).coerceIn(0, 100)
            } catch (_: Exception) {}

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build()

            // ★ 音效全部改用《象棋绝杀》(Chess-Chess) 音频：走子 go / 吃子 eat / 选子 select；将军分男、女两段交替。
            loadSound(context, "snd/move_ch.mp3", "move")
            loadSound(context, "snd/eat_ch.mp3", "eat")
            loadSound(context, "snd/check_m.mp3", "check_m")
            loadSound(context, "snd/check_f.mp3", "check_f")
            loadSound(context, "snd/begin.wav", "begin")
            loadSound(context, "snd/end.wav", "end")
            loadSound(context, "snd/pick_ch.mp3", "pick")
            loadSound(context, "snd/undo.wav", "undo")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init sound: ${e.message}")
            enabled = false
        }
    }

    private fun loadSound(context: Context, assetPath: String, key: String) {
        try {
            val sp = soundPool ?: return
            val fd = context.assets.openFd(assetPath)
            val id = sp.load(fd, 1)
            soundIds[key] = id
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load sound: $assetPath", e)
        }
    }

    /** 播放短音效，按「音效音量」调节响度 */
    fun play(soundName: String) {
        if (!enabled) return
        try {
            // ★ 将军：男/女声每次轮换（这次男声，下次女声，循环交替）
            val key = if (soundName == "check") {
                checkAlt = !checkAlt
                if (checkAlt) "check_m" else "check_f"
            } else soundName
            soundIds[key]?.let { id ->
                val v = sfxVolume / 100f
                soundPool?.play(id, v, v, 1, 0, 1.0f)
            }
        } catch (_: Exception) {}
    }

    /** 设置音效音量(0..100)并持久化 */
    fun setSfxVolume(context: Context, v: Int) {
        sfxVolume = v.coerceIn(0, 100)
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_SFX_VOL, sfxVolume).apply()
        } catch (_: Exception) {}
    }

    fun sfxVolume(): Int = sfxVolume

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun release() {
        try {
            soundPool?.release()
        } catch (_: Exception) {}
        soundPool = null
    }
}