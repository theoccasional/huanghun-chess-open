package com.pikafish.chess

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * ★ 2.9.298：辎重包「息屏不断连」保活服务
 *
 * 安卓在息屏后会把 App 挂起（Doze）、让 WiFi 进省电（长连接被掐），
 * 发送端一黑屏，接收端就判掉线。视频/音乐类 App 之所以不受影响，
 * 是因为它们挂着**前台服务**并持有 CPU / WiFi 锁。
 *
 * 这里同样只做三件事（不改任何界面）：
 *   1. 前台服务 + 常驻通知（系统不回收）
 *   2. PARTIAL_WAKE_LOCK（息屏后 CPU 仍能跑引擎）
 *   3. WIFI_MODE_FULL_HIGH_PERF（WiFi 不休眠）
 */
class EngineKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification())
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock?.isHeld != true || wifiLock?.isHeld != true) acquireLocks()
        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hhxq:engineWake").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } catch (_: Exception) {
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hhxq:engineWifi").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "引擎出借", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        @Suppress("DEPRECATION")
        builder.setContentTitle("黄昏象棋 · 正在出借引擎")
            .setContentText("辎重包连接保持中（息屏也不会断）")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
        return builder.build()
    }

    override fun onDestroy() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "hhxq_engine_keepalive"
        private const val NOTI_ID = 2001

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, EngineKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, EngineKeepAliveService::class.java)) } catch (_: Exception) {}
        }
    }
}
