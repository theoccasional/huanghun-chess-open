package com.pikafish.chess.duel

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 设备原始码（追溯码）：首次启动生成一次，双份落盘，清数据也能恢复。
 * 方案 A：不提供重置入口，强绑定。
 */
object DeviceCode {
    private const val PREF = "duel_device"
    private const val KEY = "code"
    private const val FILE_NAME = "device.code"

    @Volatile private var cached: String? = null

    fun of(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val fromPref = sp.getString(KEY, null)
            val f = File(context.filesDir, FILE_NAME)
            val fromFile = runCatching { if (f.exists()) f.readText().trim() else null }.getOrNull()
            val code = when {
                !fromPref.isNullOrBlank() -> fromPref
                !fromFile.isNullOrBlank() -> fromFile
                else -> UUID.randomUUID().toString().replace("-", "").uppercase().take(12)
            }
            if (fromPref != code) sp.edit().putString(KEY, code).apply()
            if (fromFile != code) runCatching { f.writeText(code) }
            cached = code
            return code
        }
    }
}
