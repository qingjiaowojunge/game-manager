package com.example.gamemanager

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务（可选增强）：当额度超时且前台是受管控游戏时，将其退回桌面，必要时锁屏。
 * 需在系统"无障碍"中手动开启本服务才会生效。
 */
class AppMonitorAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
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
        if (MonitorService.isQuotaExceeded() && MonitorService.blockedPackages.contains(pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) dpm.lockNow()
        }
    }

    override fun onInterrupt() {}
}
