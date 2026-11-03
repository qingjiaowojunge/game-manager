package com.example.gamemanager

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务 —— 可选增强，不是必需的。
 *
 * 作用只有一个：家长开启"拦截页 + 退回桌面"策略时，把受管控应用真正压到后台。
 * （无障碍服务能调 performGlobalAction(GLOBAL_ACTION_HOME)，普通服务不能。）
 *
 * 行为约束（上一版反复弹锁的根源就在这儿，已改）：
 * - 只处理"窗口切到受管控应用"这一种事件；
 * - 判定统一走 Guard（含前台感知、超限判定、90 秒冷却、暂停）；
 * - 本应用、桌面、系统界面的事件一律忽略；
 * - 绝不在这里锁屏。
 */
class AppMonitorAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: AppMonitorAccessibilityService? = null

        /** 供后台服务请求"退回桌面"（只能由无障碍服务执行） */
        fun requestHome() {
            val s = instance ?: return
            s.handler.post {
                try {
                    s.performGlobalAction(GLOBAL_ACTION_HOME)
                } catch (e: Exception) { }
            }
        }

        fun isRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        Guard.init(this)
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

        // 判定集中在 Guard：非受管控应用 / 没超限 / 在冷却 / 家长已暂停 → 全部跳过
        if (!Guard.shouldHandle(this, pkg)) return

        // 走到这里才说明真该处置，交给统一执行器（拉拦截页，必要时附带退回桌面）
        Enforcer.enforce(this, pkg)
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
