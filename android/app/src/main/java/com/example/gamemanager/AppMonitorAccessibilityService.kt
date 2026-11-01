package com.example.gamemanager

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务（增强拦截）：当【真实】用量已超限、且前台正是最前面的受管控应用时，
 * 将其退回桌面，必要时锁屏。需在系统"无障碍"中手动开启本服务才会生效。
 */
class AppMonitorAccessibilityService : AccessibilityService() {

    // 缓存真实用量，避免每次窗口事件都查
    private var cacheMap: Map<String, Long> = HashMap()
    private var cacheTime: Long = 0L

    private fun usageMap(): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (now - cacheTime > 10_000L) {
            cacheMap = UsageUtil.queryUsageMap(this, UsageUtil.dayStartMs(), now)
            cacheTime = now
        }
        return cacheMap
    }

    override fun onServiceConnected() {
        MonitorService.restore(this)   // 无障碍服务可能先于主界面/前台服务被系统拉起
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val limitMin = MonitorService.managedApps[pkg] ?: return
        val usedMs = usageMap()[pkg] ?: 0L
        if (usedMs >= limitMin * 60_000L) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) dpm.lockNow()
        }
    }

    override fun onInterrupt() {}
}
