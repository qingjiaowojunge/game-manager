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

    /**
     * 前台应用判定用的增量游标。
     * 事件类型 1 == MOVE_TO_FOREGROUND（API29 起叫 ACTIVITY_RESUMED，数值相同）。
     */
    private var evCursor: Long = 0L
    @Volatile private var evFg: String = ""

    /**
     * 当前真实前台应用包名。
     * 用 UsageEvents 增量回溯（首次回溯到今天 0 点），比 queryUsageStats 的 lastTimeUsed 准，
     * 且不会每次重扫一整天事件（避免监控循环里反复做重活）。
     */
    @Synchronized
    fun foregroundPackage(ctx: Context): String {
        if (!hasUsagePermission(ctx)) return ""
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return evFg
        val now = System.currentTimeMillis()
        // 冷启动 / 时钟回拨 / 距上次查询过久 → 回溯到今天 0 点重建状态
        if (evCursor <= 0L || evCursor > now || now - evCursor > 10 * 60_000L) {
            evCursor = dayStartMs()
        }
        try {
            val events = usm.queryEvents(evCursor, now) ?: return evFg
            val e = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == 1) {           // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                    val p = e.packageName
                    if (!p.isNullOrEmpty()) evFg = p
                }
            }
            evCursor = now
        } catch (e: Exception) {
            // 查事件失败就退回上一次已知结果，不要因此误判
        }
        return evFg
    }

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

    /**
     * 查询 [startMs, endMs) 内各应用真实前台时长（毫秒）。
     * 用 queryAndAggregateUsageStats（系统已按包名聚合），避免 queryUsageStats 多桶重复累加导致时长翻倍。
     */
    fun queryUsageMap(ctx: Context, startMs: Long, endMs: Long): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        if (!hasUsagePermission(ctx)) return map
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val agg: Map<String, UsageStats> = try {
            usm.queryAndAggregateUsageStats(startMs, endMs)
        } catch (e: Exception) {
            return map
        }
        for ((pkg, s) in agg) {
            map[pkg] = s.totalTimeInForeground
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

    /** 今日真实使用排行（含非受管应用），[{pkg,name,ms}] 按毫秒降序 */
    fun rankingJson(ctx: Context, limit: Int): String {
        val arr = JSONArray()
        val map = queryUsageMap(ctx, dayStartMs(), System.currentTimeMillis())
        val rows = mutableListOf<Pair<String, Long>>()
        for ((pkg, ms) in map) if (ms >= 60_000L) rows.add(pkg to ms)
        rows.sortByDescending { it.second }
        val pm = ctx.packageManager
        val n = if (limit > 0) limit else 10
        for ((pkg, ms) in rows.take(n)) {
            val name = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            arr.put(JSONObject().apply {
                put("pkg", pkg)
                put("name", name)
                put("ms", ms)
            })
        }
        return arr.toString()
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
