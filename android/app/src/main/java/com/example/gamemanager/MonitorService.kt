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

/**
 * 前台监控服务。
 *
 * 职责只有一个：每 10 秒看一眼"此刻在前台的应用"，把判断交给 Guard，处置交给 Enforcer。
 * 服务本身不做任何规则判断，也不感知限额数字——这样规则只有一处，不会两套逻辑打架。
 *
 * 附带一件事：在"剩余 N 分钟"时发一次提醒通知（每个应用每天一次）。
 */
class MonitorService : Service() {

    companion object {
        private const val CHANNEL = "game_monitor"
        private const val NOTIF_ID_SERVICE = 1
        private const val NOTIF_ID_BLOCK = 2
        private const val NOTIF_ID_WARN = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 上一次通知栏文案，没变化就不重复 notify，省电也避免通知栏抖 */
    private var lastState: String = ""

    override fun onCreate() {
        super.onCreate()
        try { Guard.init(this) } catch (e: Throwable) { }

        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID_SERVICE, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID_SERVICE, buildNotification())
            }
            true
        } catch (e: Throwable) {
            try {
                startForeground(NOTIF_ID_SERVICE, buildNotification())
                true
            } catch (e2: Throwable) {
                false
            }
        }

        if (!started) {
            stopSelf()
            return
        }
        try { lastState = stateText() } catch (e: Throwable) { }
        loop()
    }

    private fun loop() {
        handler.postDelayed({
            worker.execute {
                try {
                    tick()
                } catch (e: Throwable) {
                    // 任何异常都不许让监控循环断掉
                }
            }
            loop()
        }, 10_000L)
    }

    private fun tick() {
        // 通知栏文案先刷新：即使后面因为暂停/没权限提前返回，通知也应该是当前的
        try { refreshNotification() } catch (e: Throwable) { }

        if (Guard.isPausedNow()) return
        if (!Guard.hasRules()) return
        if (!UsageUtil.hasUsagePermission(this)) return

        val fg = UsageUtil.foregroundPackage(this)
        if (fg.isEmpty()) return

        // 家长打开了本应用 → 立刻撤掉拦截层，绝不能让家长自己被挡住
        if (fg == packageName) {
            OverlayBlocker.hide()
            return
        }

        // 剩余时间提醒（每应用每天一次）
        try {
            if (Guard.shouldNotifyRemaining(this, fg)) notifyRemaining(fg)
        } catch (e: Throwable) { }

        if (!Guard.shouldHandle(this, fg)) return
        Enforcer.enforce(this, fg)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "游戏计时", NotificationManager.IMPORTANCE_LOW)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val warn = NotificationChannel(
                    "game_warn", "时间提醒", NotificationManager.IMPORTANCE_HIGH
                )
                warn.description = "剩余时间不足时提醒"
                nm.createNotificationChannel(warn)
            }
        }
    }

    private fun notifyRemaining(pkg: String) {
        try {
            ensureChannel()
            val name = appLabel(pkg)
            val remain = (Guard.effectiveLimitMinutes(this, pkg) - Guard.usedMinutes(this, pkg)).coerceAtLeast(0)
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(
                NOTIF_ID_WARN,
                NotificationCompat.Builder(this, "game_warn")
                    .setContentTitle("$name 还能用 $remain 分钟")
                    .setContentText("到时间会自动拦截，需要更多时间请让家长加时。")
                    .setSmallIcon(R.drawable.ic_notif)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Throwable) { }
    }

    private fun appLabel(pkg: String): String = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (e: Exception) {
        pkg
    }

    /**
     * 通知栏那一行写什么。
     * 原则：写"最紧张的那个"——家长下拉通知栏就能看到还剩多少，不用点进来翻两页。
     */
    private fun stateText(): String {
        return try {
            when {
                !Guard.hasRules() -> "未配置受管控应用"
                Guard.isPausedNow() -> {
                    val r = Guard.pauseRemainMinutes()
                    if (r > 0) "管控已暂停，还剩 $r 分钟" else "管控已暂停（无限期）"
                }
                Guard.focusActive() -> "专注模式中，还剩 ${Guard.focusRemainMinutes()} 分钟"
                Guard.inCurfew() -> "免打扰时段，受管应用已拦截"
                else -> remainText()
            }
        } catch (e: Throwable) {
            "正在监控"
        }
    }

    /** 找出受管应用里剩得最少的那个（总时长更紧就用总时长） */
    private fun remainText(): String {
        var bestPkg = ""
        var best = Int.MAX_VALUE
        for (pkg in Guard.managedPackages()) {
            val limit = Guard.effectiveLimitMinutes(this, pkg)
            if (limit <= 0) continue
            val remain = limit - Guard.usedMinutes(this, pkg)
            if (remain < best) {
                best = remain
                bestPkg = pkg
            }
        }
        if (Guard.totalLimit() > 0) {
            val tr = Guard.totalLimit() - Guard.totalUsedMinutes(this)
            if (tr < best) {
                return if (tr <= 0) "今日总时长已用完" else "今日总时长还剩 $tr 分钟"
            }
        }
        if (bestPkg.isEmpty()) return "正在监控 ${Guard.ruleCount()} 个应用"
        val name = appLabel(bestPkg)
        return if (best <= 0) "$name 今日已用完" else "$name 还剩 $best 分钟"
    }

    /** 只在文案真的变了才更新，避免每 10 秒刷一次通知 */
    private fun refreshNotification() {
        val s = stateText()
        if (s == lastState) return
        lastState = s
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID_SERVICE, buildNotification())
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("游戏管理软件")
            .setContentText(stateText())
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { worker.shutdownNow() } catch (e: Throwable) { }
        super.onDestroy()
    }
}
