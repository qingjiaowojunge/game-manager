package com.example.gamemanager

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 管控判定中枢。
 *
 * 设计原则（针对"一碰就锁、界面自动关闭"的教训）：
 * 1. **单一判定入口**：MonitorService / 无障碍服务 / 拦截页 都只问 Guard，不再各自判一套。
 * 2. **前台感知**：只有受管控应用真正在前台时才可能动作；本应用、桌面、系统界面永不触发。
 * 3. **冷却**：同一应用动手后 90 秒内不重复，杜绝连环弹。
 * 4. **可暂停**：家长在配置时全局暂停，不打断操作。
 * 5. **默认不锁屏**：默认只拉全屏拦截页；锁屏必须家长显式开启。
 */
object Guard {

    /* ---------- 执行策略 ---------- */
    /** 只拉全屏拦截页（默认，最温和，不需要无障碍/设备管理员） */
    const val POLICY_BLOCK = "block"
    /** 拦截页 + 退回桌面（需无障碍服务） */
    const val POLICY_BLOCK_HOME = "block_home"
    /** 拦截页 + 锁屏（需设备管理员，最强制） */
    const val POLICY_LOCK = "lock"

    private const val PREF = "gm_guard"
    private const val K_PAUSED = "paused"
    private const val K_PAUSE_UNTIL = "pause_until"
    private const val K_POLICY = "policy"
    private const val K_BONUS = "bonus_json"
    private const val K_BONUS_DAY = "bonus_day"
    private const val K_PWD = "parent_pwd"

    /** 同一应用两次动手的最小间隔 */
    private const val COOLDOWN_MS = 90_000L

    @Volatile var paused: Boolean = false
    @Volatile var pauseUntil: Long = 0L
    @Volatile var policy: String = POLICY_BLOCK

    /** pkg -> 下一次允许动手的时间戳 */
    private val strikes = HashMap<String, Long>()

    /** 桌面（Launcher）包名集合，用来排除"退回桌面"本身被当成前台应用 */
    private var homePkgs: Set<String> = emptySet()

    private var cacheMap: Map<String, Long> = emptyMap()
    private var cacheAt: Long = 0L
    private var booted = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

    /* ---------- 初始化 / 持久化 ---------- */

    @Synchronized
    fun init(ctx: Context) {
        if (!booted) {
            booted = true
            val p = prefs(ctx)
            paused = p.getBoolean(K_PAUSED, false)
            pauseUntil = p.getLong(K_PAUSE_UNTIL, 0L)
            policy = p.getString(K_POLICY, POLICY_BLOCK) ?: POLICY_BLOCK
            loadHomePackages(ctx)
        }
        resetBonusIfNewDay(ctx)
    }

    @Suppress("DEPRECATION")
    private fun loadHomePackages(ctx: Context) {
        try {
            val list = ctx.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            homePkgs = list.map { it.activityInfo.packageName }.toSet()
        } catch (e: Exception) {
            homePkgs = emptySet()
        }
    }

    fun setPolicy(ctx: Context, value: String) {
        policy = when (value) {
            POLICY_BLOCK_HOME, POLICY_LOCK -> value
            else -> POLICY_BLOCK
        }
        prefs(ctx).edit().putString(K_POLICY, policy).apply()
    }

    /* ---------- 暂停 / 恢复 ---------- */

    fun pause(ctx: Context, minutes: Int) {
        paused = true
        pauseUntil = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        prefs(ctx).edit().putBoolean(K_PAUSED, true).putLong(K_PAUSE_UNTIL, pauseUntil).apply()
    }

    fun resume(ctx: Context) {
        paused = false
        pauseUntil = 0L
        strikes.clear()
        prefs(ctx).edit().putBoolean(K_PAUSED, false).putLong(K_PAUSE_UNTIL, 0L).apply()
    }

    /** 当前是否处于暂停态（到期自动失效） */
    fun isPausedNow(): Boolean {
        if (!paused) return false
        if (pauseUntil > 0L && System.currentTimeMillis() > pauseUntil) {
            paused = false
            pauseUntil = 0L
            return false
        }
        return true
    }

    /** 暂停剩余分钟（0 表示无限期或未暂停） */
    fun pauseRemainMinutes(): Int {
        if (!paused || pauseUntil <= 0L) return 0
        val ms = pauseUntil - System.currentTimeMillis()
        return if (ms <= 0) 0 else (ms / 60_000L).toInt() + 1
    }

    /* ---------- 今日加时（真实用量清不了零，用加时抵消） ---------- */

    private fun resetBonusIfNewDay(ctx: Context) {
        val p = prefs(ctx)
        val day = p.getString(K_BONUS_DAY, "") ?: ""
        if (day != today()) {
            p.edit().putString(K_BONUS_DAY, today()).putString(K_BONUS, "{}").apply()
        }
    }

    private fun bonusMap(ctx: Context): MutableMap<String, Int> {
        val raw = prefs(ctx).getString(K_BONUS, "{}") ?: "{}"
        val map = HashMap<String, Int>()
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) map[k] = o.optInt(k, 0)
        } catch (e: Exception) { }
        return map
    }

    /** 今日已加时的分钟数 */
    fun bonus(ctx: Context, pkg: String): Int {
        resetBonusIfNewDay(ctx)
        return bonusMap(ctx)[pkg] ?: 0
    }

    fun addBonus(ctx: Context, pkg: String, minutes: Int) {
        resetBonusIfNewDay(ctx)
        val map = bonusMap(ctx)
        map[pkg] = (map[pkg] ?: 0) + minutes
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        prefs(ctx).edit().putString(K_BONUS, o.toString()).apply()
        // 加时后清掉冷却，允许立即重新判定
        strikes.remove(pkg)
    }

    /** 今日豁免：把该应用今日已用时长整体抵消掉 */
    fun exemptToday(ctx: Context, pkg: String) {
        val usedMin = (usageMs(ctx, pkg) / 60_000L).toInt() + 1
        addBonus(ctx, pkg, usedMin)
    }

    /* ---------- 家长密码（拦截页验证用，与网页共用一份） ---------- */

    fun setParentPassword(ctx: Context, pwd: String) {
        prefs(ctx).edit().putString(K_PWD, pwd).apply()
    }

    fun checkParentPassword(ctx: Context, input: String): Boolean {
        val saved = prefs(ctx).getString(K_PWD, "1234") ?: "1234"
        val effective = if (saved.isEmpty()) "1234" else saved
        return input == effective
    }

    fun hasParentPassword(ctx: Context): Boolean =
        (prefs(ctx).getString(K_PWD, "") ?: "").isNotEmpty()

    /* ---------- 用量缓存（10 秒） ---------- */

    @Synchronized
    private fun usageMs(ctx: Context, pkg: String): Long {
        val now = System.currentTimeMillis()
        if (now - cacheAt > 10_000L) {
            cacheMap = UsageUtil.queryUsageMap(ctx, UsageUtil.dayStartMs(), now)
            cacheAt = now
        }
        return cacheMap[pkg] ?: 0L
    }

    /** 有效限额 = 设定限额 + 今日加时 */
    fun effectiveLimitMinutes(ctx: Context, pkg: String): Int {
        val base = MonitorService.managedApps[pkg] ?: 0
        return base + bonus(ctx, pkg)
    }

    fun usedMinutes(ctx: Context, pkg: String): Int =
        (usageMs(ctx, pkg) / 60_000L).toInt()

    /* ---------- 核心判定 ---------- */

    /** 这个包是不是"永远不该动"的（自己 / 桌面 / 系统界面） */
    private fun isIgnoredPackage(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return true
        if (pkg == ctx.packageName) return true
        if (pkg == "com.android.systemui") return true
        if (homePkgs.contains(pkg)) return true
        if (pkg.contains("launcher", true)) return true
        if (pkg.contains("inputmethod", true)) return true
        return false
    }

    /** 是否已超限（只看数据，不看我能不能动手） */
    fun isOverLimit(ctx: Context, pkg: String): Boolean {
        if (MonitorService.managedApps[pkg] == null) return false
        val limit = effectiveLimitMinutes(ctx, pkg)
        if (limit <= 0) return false
        return usageMs(ctx, pkg) >= limit * 60_000L
    }

    /**
     * 最终裁定：这一刻是否应该对 pkg 动手。
     * 通过则同时占用冷却票据，调用方可以放心执行处置。
     */
    @Synchronized
    fun shouldHandle(ctx: Context, pkg: String): Boolean {
        init(ctx)
        if (isPausedNow()) return false
        if (isIgnoredPackage(ctx, pkg)) return false
        if (!isOverLimit(ctx, pkg)) return false
        val now = System.currentTimeMillis()
        val next = strikes[pkg] ?: 0L
        if (now < next) return false
        strikes[pkg] = now + COOLDOWN_MS
        return true
    }

    fun clearStrike(pkg: String) { strikes.remove(pkg) }

    fun clearAllStrikes() { strikes.clear() }

    /* ---------- 供网页展示的完整状态 ---------- */

    fun stateJson(ctx: Context): String {
        init(ctx)
        val o = JSONObject()
        o.put("paused", isPausedNow())
        o.put("pauseRemain", pauseRemainMinutes())
        o.put("policy", policy)
        o.put("fg", UsageUtil.foregroundPackage(ctx))
        return o.toString()
    }
}
