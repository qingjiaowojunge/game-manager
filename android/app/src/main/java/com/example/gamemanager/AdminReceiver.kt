package com.example.gamemanager

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理员接收器。
 * 只做日志埋点：真正的管控逻辑在 MonitorService / GameBridge 里。
 * 注意：父类 onEnabled/onDisabled 已有空实现，不需要（也不要）写成 Context?/Intent? 重写。
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("GameManager", "设备管理员已激活")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("GameManager", "设备管理员已取消")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.i("GameManager", "收到取消管理员请求")
        return "取消后本应用将无法锁定屏幕执行游戏时长限制。"
    }
}
