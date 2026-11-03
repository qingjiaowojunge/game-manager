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
 * 网页(JS) ↔ 原生(Android) 桥接。
 *
 * ⚠️ 关键约束：@JavascriptInterface 方法里抛出的异常会抛到 WebView 的 JavaBridge 线程，
 * 直接导致整个应用崩溃（表现为"点一下功能就闪退"）。
 * 因此本文件所有对外方法**必须**走 safe / safeUnit 包一层，绝不允许异常逃逸。
 */
class GameBridge(private val activity: Activity) {

    private val dpm: DevicePolicyManager by lazy {
        activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val admin: ComponentName by lazy { ComponentName(activity, AdminReceiver::class.java) }

    /* ---------- 异常护栏 ---------- */

    private inline fun <T> safe(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            fallback
        }

    private inline fun safeUnit(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // 任何原生异常都不许把 App 带崩
        }
    }

    /* ---------- 真实数据 ---------- */

    /** 真实已装可启动应用清单 JSON: [{name,pkg,sys}] */
    @JavascriptInterface
    fun listInstalledApps(): String = safe("[]") { UsageUtil.listInstalledApps(activity) }

    /** 今天所有应用真实前台时长(ms) JSON: {pkg: ms} */
    @JavascriptInterface
    fun getAllUsageToday(): String = safe("{}") {
        val map = UsageUtil.queryUsageMap(activity, UsageUtil.dayStartMs(), System.currentTimeMillis())
        val o = org.json.JSONObject()
        for ((k, v) in map) o.put(k, v)
        o.toString()
    }

    /** 某应用今天真实前台时长(ms) */
    @JavascriptInterface
    fun getUsageToday(pkg: String): Long = safe(0L) {
        val map = UsageUtil.queryUsageMap(activity, UsageUtil.dayStartMs(), System.currentTimeMillis())
        map[pkg] ?: 0L
    }

    /** 某应用最近 days 天每日真实时长(ms) JSON 数组(旧→新) */
    @JavascriptInterface
    fun getUsageSeries(pkg: String, days: Int): String = safe("[]") {
        UsageUtil.usageSeries(activity, pkg, days)
    }

    /** 当前真实前台应用包名（需用量权限） */
    @JavascriptInterface
    fun getForegroundPackage(): String = safe("") { UsageUtil.foregroundPackage(activity) }

    /** 今日真实使用排行（含非受管应用），概览页"今天都在用什么" */
    @JavascriptInterface
    fun getTodayRanking(limit: Int): String = safe("[]") {
        UsageUtil.rankingJson(activity, limit)
    }

    /* ---------- 管控配置 ---------- */

    /** 下发完整管控配置（限额规则/总时长/免打扰/提醒），落盘并启动监控 */
    @JavascriptInterface
    fun setManagedApps(json: String) {
        safeUnit {
            Guard.applyConfig(activity, json)
            startMonitorInternal()
        }
    }

    /* ---------- 管控状态 / 暂停 / 策略 ---------- */

    /** 管控整体状态 JSON: {paused,pauseRemain,policy,fg} */
    @JavascriptInterface
    fun getGuardState(): String = safe("{}") { Guard.stateJson(activity) }

    /** 暂停管控 minutes 分钟（0=无限期） */
    @JavascriptInterface
    fun pauseGuard(minutes: Int) {
        safeUnit {
            Guard.pause(activity, minutes)
            OverlayBlocker.hide()
            Enforcer.blockShowing = false
        }
    }

    /** 恢复管控 */
    @JavascriptInterface
    fun resumeGuard() {
        safeUnit { Guard.resume(activity) }
    }

    /** 执行策略：block / block_home / lock */
    @JavascriptInterface
    fun setPolicy(p: String) {
        safeUnit { Guard.setPolicy(activity, p) }
    }

    /** 关闭正在显示的拦截层 */
    @JavascriptInterface
    fun clearBlock() {
        safeUnit {
            Enforcer.blockShowing = false
            OverlayBlocker.hide()
        }
    }

    /* ---------- 悬浮窗权限 ---------- */

    @JavascriptInterface
    fun hasOverlayPermission(): Boolean = safe(false) { OverlayBlocker.canOverlay(activity) }

    @JavascriptInterface
    fun requestOverlayPermission() {
        safeUnit { activity.startActivity(overlayIntent()) }
    }

    /* ---------- 今日加时 / 豁免 ---------- */

    @JavascriptInterface
    fun addBonus(pkg: String, minutes: Int) {
        safeUnit {
            Guard.addBonus(activity, pkg, minutes)
            Guard.clearStrike(pkg)
        }
    }

    @JavascriptInterface
    fun exemptToday(pkg: String) {
        safeUnit {
            Guard.exemptToday(activity, pkg)
            Guard.clearStrike(pkg)
        }
    }

    @JavascriptInterface
    fun getBonus(pkg: String): Int = safe(0) { Guard.bonus(activity, pkg) }

    /* ---------- 操作记录（家长审计） ---------- */

    @JavascriptInterface
    fun getAuditLog(): String = safe("[]") { Guard.auditJson(activity) }

    @JavascriptInterface
    fun clearAuditLog() {
        safeUnit { Guard.clearAudit(activity) }
    }

    @JavascriptInterface
    fun setParentPassword(pwd: String) {
        safeUnit { Guard.setParentPassword(activity, pwd) }
    }

    /* ---------- 设备能力 ---------- */

    @JavascriptInterface
    fun lockNow() {
        safeUnit {
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
            } else {
                activity.runOnUiThread {
                    Toast.makeText(activity, "未启用设备管理员，无法锁屏", Toast.LENGTH_LONG).show()
                }
                requestAdmin()
            }
        }
    }

    @JavascriptInterface
    fun isAdminActive(): Boolean = safe(false) { dpm.isAdminActive(admin) }

    @JavascriptInterface
    fun requestAdmin() {
        safeUnit {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于到时自动锁屏")
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun hasUsagePermission(): Boolean = safe(false) { UsageUtil.hasUsagePermission(activity) }

    @JavascriptInterface
    fun openUsageSettings() {
        safeUnit { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    @JavascriptInterface
    fun openAccessibilitySettings() {
        safeUnit { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    @JavascriptInterface
    fun isAccessibilityOn(): Boolean = safe(false) {
        val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (i in list) {
            if (i.resolveInfo.serviceInfo.packageName == activity.packageName) return@safe true
        }
        false
    }

    @JavascriptInterface
    fun isIgnoringBatteryOptimizations(): Boolean = safe(false) {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    @JavascriptInterface
    fun requestIgnoreBatteryOptimizations() {
        safeUnit {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "已在电池优化白名单", Toast.LENGTH_SHORT).show()
                }
                return@safeUnit
            }
            activity.startActivity(ignoreBatteryIntent())
        }
    }

    @JavascriptInterface
    fun openAppDetailsSettings() {
        safeUnit { activity.startActivity(appDetailsIntent()) }
    }

    /* ---------- 监控服务 ---------- */

    @JavascriptInterface
    fun startMonitor() {
        safeUnit { startMonitorInternal() }
    }

    /** Android 12+ 在后台启动前台服务会抛异常，必须先兜住再降级 */
    private fun startMonitorInternal() {
        try {
            activity.startForegroundService(Intent(activity, MonitorService::class.java))
        } catch (e: Throwable) {
            try {
                activity.startService(Intent(activity, MonitorService::class.java))
            } catch (ignored: Throwable) { }
        }
    }

    @JavascriptInterface
    fun stopMonitor() {
        safeUnit { activity.stopService(Intent(activity, MonitorService::class.java)) }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        safeUnit {
            activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    /* ---------- 崩溃日志 ---------- */

    @JavascriptInterface
    fun getLastCrash(): String = safe("") { App.readCrash(activity) }

    @JavascriptInterface
    fun clearCrash() {
        safeUnit { App.clearCrash(activity) }
    }

    /* ---------- Intent 收口 ---------- */

    private fun overlayIntent() = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + activity.packageName)
    )

    private fun ignoreBatteryIntent() = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:" + activity.packageName)
    )

    private fun appDetailsIntent() = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + activity.packageName)
    )
}
