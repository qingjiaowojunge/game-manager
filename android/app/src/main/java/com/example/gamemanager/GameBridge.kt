package com.example.gamemanager

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * 网页(JS) ↔ 原生(Android) 桥接：把系统级真实数据与控制能力暴露给 index.html。
 * index.html 里通过 window.GameBridge.xxx() 调用以下方法。
 */
class GameBridge(private val activity: Activity) {

    private val dpm: DevicePolicyManager by lazy {
        activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val admin: ComponentName by lazy { ComponentName(activity, AdminReceiver::class.java) }

    /* ---------- 真实数据 ---------- */

    /** 真实已装可启动应用清单 JSON: [{name,pkg,sys}] */
    @JavascriptInterface
    fun listInstalledApps(): String = UsageUtil.listInstalledApps(activity)

    /** 今天所有应用真实前台时长(ms) JSON: {pkg: ms} */
    @JavascriptInterface
    fun getAllUsageToday(): String {
        val map = UsageUtil.queryUsageMap(activity, UsageUtil.dayStartMs(), System.currentTimeMillis())
        val o = org.json.JSONObject()
        for ((k, v) in map) o.put(k, v)
        return o.toString()
    }

    /** 某应用今天真实前台时长(ms) */
    @JavascriptInterface
    fun getUsageToday(pkg: String): Long {
        val map = UsageUtil.queryUsageMap(activity, UsageUtil.dayStartMs(), System.currentTimeMillis())
        return map[pkg] ?: 0L
    }

    /** 某应用最近 days 天每日真实时长(ms) JSON 数组(旧→新) */
    @JavascriptInterface
    fun getUsageSeries(pkg: String, days: Int): String = UsageUtil.usageSeries(activity, pkg, days)

    /** 当前真实前台应用包名（需用量权限） */
    @JavascriptInterface
    fun getForegroundPackage(): String {
        if (!UsageUtil.hasUsagePermission(activity)) return ""
        val usm = activity.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, end - 2000, end)
        var recent: android.app.usage.UsageStats? = null
        for (s in stats) {
            if (recent == null || s.lastTimeUsed > recent.lastTimeUsed) recent = s
        }
        return recent?.packageName ?: ""
    }

    /* ---------- 管控配置 ---------- */

    /** 设置受管控应用与限额: JSON 数组 [{pkg, limit}]，limit 单位分钟；并启动监控 */
    @JavascriptInterface
    fun setManagedApps(json: String) {
        MonitorService.setManagedApps(json)
        startMonitor()
    }

    /* ---------- 设备能力 ---------- */

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

    @JavascriptInterface
    fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于到时自动锁屏")
        activity.startActivity(intent)
    }

    @JavascriptInterface
    fun hasUsagePermission(): Boolean = UsageUtil.hasUsagePermission(activity)

    @JavascriptInterface
    fun openUsageSettings() {
        activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    @JavascriptInterface
    fun openAccessibilitySettings() {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    @JavascriptInterface
    fun isAccessibilityOn(): Boolean {
        val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (i in list) {
            if (i.resolveInfo.serviceInfo.packageName == activity.packageName) return true
        }
        return false
    }

    /** 是否已加入电池优化白名单（后台保活） */
    @JavascriptInterface
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    /** 请求加入电池优化白名单（国产 ROM 防杀后台） */
    @JavascriptInterface
    fun requestIgnoreBatteryOptimizations() {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) {
            activity.runOnUiThread { Toast.makeText(activity, "已在电池优化白名单", Toast.LENGTH_SHORT).show() }
            return
        }
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + activity.packageName)
            activity.startActivity(i)
        } catch (e: Exception) {
            // 部分 ROM 不支持直接弹窗，退回应用详情页让用户手动设
            openAppDetailsSettings()
        }
    }

    /** 打开本应用系统详情页（设置自启动/权限） */
    @JavascriptInterface
    fun openAppDetailsSettings() {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + activity.packageName)
            activity.startActivity(i)
        } catch (e: Exception) {}
    }

    /* ---------- 监控服务控制 ---------- */

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
