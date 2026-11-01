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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray

/**
 * 前台服务：持续读取「受管控应用」的【真实】前台时长（UsageStatsManager），
 * 任一应用今日真实时长达到其限额即调用 DevicePolicyManager.lockNow() 真锁屏。
 * 受管控清单由 GameBridge.setManagedApps() 写入，并落盘到 SharedPreferences
 * （进程被系统回收后重启服务仍保留限额，不会静默失效）。
 */
class MonitorService : Service() {

    companion object {
        private const val PREF = "gm_monitor"
        private const val KEY = "managed_apps"

        /** pkg -> 每日限额(分钟) */
        @Volatile var managedApps: MutableMap<String, Int> = HashMap()

        private fun parse(json: String): HashMap<String, Int> {
            val map = HashMap<String, Int>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pkg = o.optString("pkg")
                    val limit = o.optInt("limit", 0)
                    if (pkg.isNotEmpty() && limit > 0) map[pkg] = limit
                }
            } catch (e: Exception) { /* 忽略解析错误 */ }
            return map
        }

        fun setManagedApps(ctx: Context, json: String) {
            managedApps = parse(json)
            try {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY, json).apply()
            } catch (e: Exception) { }
        }

        fun restore(ctx: Context) {
            if (managedApps.isNotEmpty()) return
            try {
                val json = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY, "") ?: ""
                if (json.isNotEmpty()) managedApps = parse(json)
            } catch (e: Exception) { }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val admin by lazy { ComponentName(this, AdminReceiver::class.java) }

    override fun onCreate() {
        super.onCreate()
        restore(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, buildNotification())
        }
        loop()
    }

    /** 每 15 秒检查一次真实用量 */
    private fun loop() {
        handler.postDelayed({
            checkAndLock()
            loop()
        }, 15000)
    }

    private fun checkAndLock() {
        if (managedApps.isEmpty()) return
        if (!UsageUtil.hasUsagePermission(this)) return
        val map = UsageUtil.queryUsageMap(this, UsageUtil.dayStartMs(), System.currentTimeMillis())
        for ((pkg, limitMin) in managedApps) {
            val usedMs = map[pkg] ?: 0L
            if (usedMs >= limitMin * 60_000L) {
                if (dpm.isAdminActive(admin)) {
                    dpm.lockNow()
                    return
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val ch = "game_monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(ch, "游戏计时", NotificationManager.IMPORTANCE_LOW)
                )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("游戏管理软件")
            .setContentText("正在监控真实使用时长")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(pi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy() }
}
