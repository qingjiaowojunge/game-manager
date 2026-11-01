package com.example.gamemanager

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * 真实设备数据工具：已装应用清单 + 真实使用时长（UsageStatsManager）。
 * GameBridge / MonitorService / 无障碍服务 共用，避免重复实现。
 */
object UsageUtil {

    /** 今天 00:00 的毫秒时间戳（本地时区） */
    fun dayStartMs(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 是否已授予「使用情况访问」权限 */
    fun hasUsagePermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            "android:get_usage_stats",
            Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 查询 [startMs, endMs) 内各应用真实前台时长（毫秒），按包名聚合 */
    fun queryUsageMap(ctx: Context, startMs: Long, endMs: Long): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        if (!hasUsagePermission(ctx)) return map
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats: List<UsageStats> = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startMs, endMs
        )
        for (s in stats) {
            map[s.packageName] = (map[s.packageName] ?: 0L) + s.totalTimeInForeground
        }
        return map
    }

    /** 真实已装且可启动的应用清单：[{name, pkg, sys}]，按名称排序 */
    fun listInstalledApps(ctx: Context): String {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val list = mutableListOf<JSONObject>()
        for (ai in apps) {
            // 只保留可启动（有 launcher intent）的应用，跳过后台服务类
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue
            val name = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { ai.packageName }
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            list.add(JSONObject().apply {
                put("name", name)
                put("pkg", ai.packageName)
                put("sys", sys)
            })
        }
        list.sortBy { it.getString("name") }
        return JSONArray(list).toString()
    }

    /** 某应用最近 days 天每日真实时长（毫秒），返回 JSON 数组（旧→新） */
    fun usageSeries(ctx: Context, pkg: String, days: Int): String {
        val arr = JSONArray()
        val cal = java.util.Calendar.getInstance()
        for (i in (days - 1) downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val end = cal.timeInMillis
            val map = queryUsageMap(ctx, start, end)
            arr.put(map[pkg] ?: 0L)
        }
        return arr.toString()
    }
}
