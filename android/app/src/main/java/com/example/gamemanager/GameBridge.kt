package com.example.gamemanager

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * 网页(JS) ↔ 原生(Android) 桥接：把系统级能力暴露给 index.html 调用。
 * index.html 里通过 window.GameBridge.xxx() 调用以下方法。
 */
class GameBridge(private val activity: Activity) {

    private val dpm: DevicePolicyManager by lazy {
        activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val admin: ComponentName by lazy { ComponentName(activity, AdminReceiver::class.java) }

    /** 真实锁屏（需已启用设备管理员） */
    @JavascriptInterface
    fun lockNow() {
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        } else {
            activity.runOnUiThread {
                Toast.makeText(activity, "未启用设备管理员，无法锁屏", Toast.LENGTH_LONG).show()
                requestAdmin()
            }
        }
    }

    @JavascriptInterface
    fun isAdminActive(): Boolean = dpm.isAdminActive(admin)

    /** 跳转到系统"激活设备管理员"页面 */
    @JavascriptInterface
    fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于到时自动锁屏")
        activity.startActivity(intent)
    }

    @JavascriptInterface
    fun hasUsagePermission(): Boolean {
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow("android:get_usage_stats", Process.myUid(), activity.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    @JavascriptInterface
    fun openUsageSettings() {
        activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /** 真实查询当前前台应用包名（需用量统计权限） */
    @JavascriptInterface
    fun getForegroundPackage(): String {
        if (!hasUsagePermission()) return ""
        val usm = activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 2000, end)
        var recent: UsageStats? = null
        for (s in stats) {
            if (recent == null || s.lastTimeUsed > recent.lastTimeUsed) recent = s
        }
        return recent?.packageName ?: ""
    }

    /** 设置本局额度截止时间（毫秒时间戳），到点由 MonitorService 真实锁屏 */
    @JavascriptInterface
    fun setQuotaEndTime(endTimeMs: Long) { MonitorService.setQuotaEnd(endTimeMs) }

    /** 设置受管控游戏包名列表（逗号分隔），超时后无障碍服务会将其退回桌面 */
    @JavascriptInterface
    fun setBlockedPackages(csv: String) { MonitorService.setBlocked(csv) }

    @JavascriptInterface
    fun clearQuota() { MonitorService.clearQuota() }

    @JavascriptInterface
    fun startMonitor() {
        activity.startForegroundService(Intent(activity, MonitorService::class.java))
    }

    @JavascriptInterface
    fun stopMonitor() {
        activity.stopService(Intent(activity, MonitorService::class.java))
    }

    @JavascriptInterface
    fun toast(msg: String) {
        activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }
}
