package com.example.gamemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
 * 前台监控服务。
 *
 * 与上一版的根本区别（上一版是"一碰就锁、界面自动退出"的元凶）：
 * - 只对**真正处于前台**的受管控应用动作，本应用/桌面/系统界面永不触发；
 * - 默认只拉全屏拦截页，不锁屏；锁屏需要家长在设置里显式开启；
 * - 判定与冷却统一走 Guard，同一应用 90 秒内不重复；
 * - 家长暂停期间完全不动手。
 */
class MonitorService : Service() {

    companion object {
        private const val PREF = "gm_monitor"
        private const val KEY = "managed_apps"

        /** pkg -> 每日限额(分钟)，基准值，不含今日加时 */
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
            } catch (e: Exception) { }
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
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        restore(this)
        Guard.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, buildNotification())
        }
        loop()
    }

    /** 每 10 秒检查一次；只关心"此刻在前台的那个应用"。
     *  查询用量要跨进程，放后台线程跑，避免卡住界面。 */
    private fun loop() {
        handler.postDelayed({
            worker.execute {
                try {
                    tick()
                } catch (e: Exception) {
                    // 任何异常都不许让监控循环断掉
                }
            }
            loop()
        }, 10_000L)
    }

    private fun tick() {
        if (Guard.isPausedNow()) return
        if (managedApps.isEmpty()) return
        if (!UsageUtil.hasUsagePermission(this)) return

        val fg = UsageUtil.foregroundPackage(this)
        if (fg.isEmpty()) return
        // 家长打开了本应用 → 立刻撤掉拦截层，绝不能让家长自己被挡住
        if (fg == packageName) {
            OverlayBlocker.hide()
            return
        }
        // 非受管控应用、桌面、系统界面 → Guard 内部直接跳过
        if (!Guard.shouldHandle(this, fg)) return
        // 冷却票据已由 Guard 占用，执行统一交给 Enforcer（与无障碍链路同一套动作）
        Enforcer.enforce(this, fg)
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
        val state = when {
            Guard.isPausedNow() -> "已暂停管控"
            managedApps.isEmpty() -> "未配置受管控应用"
            else -> "正在监控 ${managedApps.size} 个应用"
        }
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("游戏管理软件")
            .setContentText(state)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { worker.shutdownNow() } catch (e: Exception) { }
        super.onDestroy()
    }
}
