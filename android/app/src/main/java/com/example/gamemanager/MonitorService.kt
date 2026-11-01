package com.example.gamemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 前台服务：持续跟踪本局额度截止时间，到点调用 DevicePolicyManager.lockNow() 真实锁屏。
 * 状态由 GameBridge（网页调用）写入。
 */
class MonitorService : Service() {

    companion object {
        @Volatile var quotaEndMs: Long = 0L
        @Volatile var blockedPackages: MutableSet<String> = HashSet()

        fun setQuotaEnd(v: Long) { quotaEndMs = v }
        fun clearQuota() { quotaEndMs = 0L }
        fun setBlocked(csv: String) {
            blockedPackages = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        }
        fun isQuotaExceeded(): Boolean = quotaEndMs > 0 && System.currentTimeMillis() >= quotaEndMs
    }

    private val handler = Handler(Looper.getMainLooper())
    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val admin by lazy { ComponentName(this, AdminReceiver::class.java) }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        loop()
    }

    private fun loop() {
        handler.postDelayed({
            if (quotaEndMs > 0 && System.currentTimeMillis() >= quotaEndMs) {
                if (dpm.isAdminActive(admin)) dpm.lockNow()
                quotaEndMs = 0
            }
            loop()
        }, 1000)
    }

    private fun buildNotification(): Notification {
        val ch = "game_monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "游戏计时", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("游戏管理软件")
            .setContentText("计时监控运行中")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(pi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { quotaEndMs = 0; super.onDestroy() }
}
