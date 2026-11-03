package com.example.gamemanager

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 管控判定中枢 —— 全应用唯一的裁定入口。
 *
 * 规则只有一份配置（网页下发 → SharedPreferences 落盘），包含：
 *  - 每个应用的**工作日 / 周末**两套限额
 *  - **每日总时长**上限（所有受管应用合计）
 *  - **免打扰时段**（宵禁，时段内所有受管应用一律拦）
 *  - 执行策略、暂停态、今日加时、剩余提醒
 *
 * 设计原则（前几轮踩坑换来的）：
 * 1. 单一判定入口：Service / 无障碍 / 拦截页 都只问 Guard，不再各判一套。
 * 2. 前台感知：只有受管应用真在前台才可能动作；本应用、桌面、系统界面永不触发。
 * 3. 冷却：同一应用动手后 90 秒内不重复，杜绝连环弹。
 * 4. 可暂停：家长配置时全局暂停。
 * 5. 默认不锁屏：默认只拉拦截层。
 */
object Guard {

    /* ---------- 执行策略 ---------- */
    const val POLICY_BLOCK = "block"
    const val POLICY_BLOCK_HOME = "block_home"
    const val POLICY_LOCK = "lock"

    private const val PREF = "gm_guard"
    private const val K_PAUSED = "paused"
    private const val K_PAUSE_UNTIL = "pause_until"
    private const val K_POLICY = "policy"
    private const val K_BONUS = "bonus_json"
    private const val K_BONUS_DAY = "bonus_day"
    private const val K_PWD = "parent_pwd"
    private const val K_RULES = "rules_json"
    private const val K_AUDIT = "audit_json"
    private const val K_NOTIFY_DAY = "notify_day"
    private const val K_NOTIFY_DONE = "notify_done"

    private const val COOLDOWN_MS = 90_000L
    private const val AUDIT_MAX = 40

    /** 单个应用的限额规则：工作日 / 周末（分钟） */
    data class Rule(val weekday: Int, val weekend: Int)

    /* ---------- 运行态 ---------- */
    @Volatile var paused: Boolean = false
    @Volatile var pauseUntil: Long = 0L
    @Volatile var policy: String = POLICY_BLOCK

    @Volatile private var rules: Map<String, Rule> = emptyMap()
    @Volatile private var totalLimitMin: Int = 0
    @Volatile private var curfewOn: Boolean = false
    @Volatile private var curfewStartMin: Int = 22 * 60
    @Volatile private var curfewEndMin: Int = 7 * 60
    @Volatile private var notifyBeforeMin: Int = 0

    private val strikes = HashMap<String, Long>()
    private var homePkgs: Set<String> = emptySet()
    private var cacheMap: Map<String, Long> = emptyMap()
    private var cacheAt: Long = 0L
    private var booted = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

    /* ---------- 初始化 ---------- */

    @Synchronized
    fun init(ctx: Context) {
        if (!booted) {
            booted = true
            val p = prefs(ctx)
            paused = p.getBoolean(K_PAUSED, false)
            pauseUntil = p.getLong(K_PAUSE_UNTIL, 0L)
            policy = p.getString(K_POLICY, POLICY_BLOCK) ?: POLICY_BLOCK
            loadHomePackages(ctx)
            loadRules(p.getString(K_RULES, "") ?: "")
        }
        resetDailyIfNewDay(ctx)
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

    /* ---------- 配置下发 / 落盘 ---------- */

    /**
     * 应用配置。JSON 结构（由网页下发）：
     * {"apps":[{"pkg":"x","wd":30,"we":60}],"total":0,
     *  "curfewOn":false,"curfewStart":1320,"curfewEnd":420,"notify":5}
     */
    @Synchronized
    fun applyConfig(ctx: Context, json: String) {
        init(ctx)
        loadRules(json)
        try {
            prefs(ctx).edit().putString(K_RULES, json).apply()
        } catch (e: Exception) { }
        strikes.clear()
    }

    @Synchronized
    private fun loadRules(json: String) {
        val map = HashMap<String, Rule>()
        var total = 0
        var cOn = false
        var cStart = 22 * 60
        var cEnd = 7 * 60
        var notify = 0
        if (json.isNotEmpty()) {
            try {
                val o = JSONObject(json)
                val arr = o.optJSONArray("apps") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    val pkg = a.optString("pkg")
                    if (pkg.isEmpty()) continue
                    val wd = a.optInt("wd", 0)
                    val we = a.optInt("we", wd)
                    if (wd > 0 || we > 0) map[pkg] = Rule(wd, we)
                }
                total = o.optInt("total", 0)
                cOn = o.optBoolean("curfewOn", false)
                cStart = o.optInt("curfewStart", 22 * 60)
                cEnd = o.optInt("curfewEnd", 7 * 60)
                notify = o.optInt("notify", 0)
            } catch (e: Exception) { }
        }
        rules = map
        totalLimitMin = if (total > 0) total else 0
        curfewOn = cOn
        curfewStartMin = cStart
        curfewEndMin = cEnd
        notifyBeforeMin = if (notify > 0) notify else 0
    }

    fun hasRules(): Boolean = rules.isNotEmpty()

    fun ruleCount(): Int = rules.size

    fun managedPackages(): Set<String> = rules.keys

    /* ---------- 策略 / 暂停 ---------- */

    fun setPolicy(ctx: Context, value: String) {
        policy = when (value) {
            POLICY_BLOCK_HOME, POLICY_LOCK -> value
            else -> POLICY_BLOCK
        }
        prefs(ctx).edit().putString(K_POLICY, policy).apply()
    }

    fun pause(ctx: Context, minutes: Int) {
        paused = true
        pauseUntil = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        prefs(ctx).edit().putBoolean(K_PAUSED, true).putLong(K_PAUSE_UNTIL, pauseUntil).apply()
        audit(ctx, "pause", "", 0)
    }

    fun resume(ctx: Context) {
        paused = false
        pauseUntil = 0L
        strikes.clear()
        prefs(ctx).edit().putBoolean(K_PAUSED, false).putLong(K_PAUSE_UNTIL, 0L).apply()
        audit(ctx, "resume", "", 0)
    }

    fun isPausedNow(): Boolean {
        if (!paused) return false
        if (pauseUntil > 0L && System.currentTimeMillis() > pauseUntil) {
            paused = false
            pauseUntil = 0L
            return false
        }
        return true
    }

    fun pauseRemainMinutes(): Int {
        if (!paused || pauseUntil <= 0L) return 0
        val ms = pauseUntil - System.currentTimeMillis()
        return if (ms <= 0) 0 else (ms / 60_000L).toInt() + 1
    }

    /* ---------- 每日重置（加时 / 提醒去重） ---------- */

    private fun resetDailyIfNewDay(ctx: Context) {
        val p = prefs(ctx)
        val day = p.getString(K_BONUS_DAY, "") ?: ""
        if (day != today()) {
            p.edit()
                .putString(K_BONUS_DAY, today())
                .putString(K_BONUS, "{}")
                .putString(K_NOTIFY_DAY, today())
                .putString(K_NOTIFY_DONE, "[]")
                .apply()
        }
    }

    /* ---------- 今日加时 ---------- */

    private fun bonusMap(ctx: Context): MutableMap<String, Int> {
        val raw = prefs(ctx).getString(K_BONUS, "{}") ?: "{}"
        val map = HashMap<String, Int>()
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) map[k] = o.optInt(k, 0)
        } catch (e: Exception) { }
        return map
    }

    private fun saveBonusMap(ctx: Context, map: Map<String, Int>) {
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            prefs(ctx).edit().putString(K_BONUS, o.toString()).apply()
        } catch (e: Exception) { }
    }

    fun bonus(ctx: Context, pkg: String): Int {
        resetDailyIfNewDay(ctx)
        return bonusMap(ctx)[pkg] ?: 0
    }

    fun addBonus(ctx: Context, pkg: String, minutes: Int) {
        resetDailyIfNewDay(ctx)
        val map = bonusMap(ctx)
        map[pkg] = (map[pkg] ?: 0) + minutes
        saveBonusMap(ctx, map)
        strikes.remove(pkg)
        audit(ctx, "bonus", pkg, minutes)
    }

    fun exemptToday(ctx: Context, pkg: String) {
        val usedMin = (usageMs(ctx, pkg) / 60_000L).toInt() + 1
        resetDailyIfNewDay(ctx)
        val map = bonusMap(ctx)
        map[pkg] = (map[pkg] ?: 0) + usedMin
        saveBonusMap(ctx, map)
        strikes.remove(pkg)
        audit(ctx, "exempt", pkg, usedMin)
    }

    /* ---------- 家长密码 ---------- */

    fun setParentPassword(ctx: Context, pwd: String) {
        prefs(ctx).edit().putString(K_PWD, pwd).apply()
    }

    fun checkParentPassword(ctx: Context, input: String): Boolean {
        val saved = prefs(ctx).getString(K_PWD, "1234") ?: "1234"
        val effective = if (saved.isEmpty()) "1234" else saved
        return input == effective
    }

    /* ---------- 操作记录（家长审计） ---------- */

    fun audit(ctx: Context, type: String, pkg: String, minutes: Int) {
        try {
            val p = prefs(ctx)
            val raw = p.getString(K_AUDIT, "[]") ?: "[]"
            val old = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            val out = JSONArray()
            out.put(JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("type", type)
                put("pkg", pkg)
                put("min", minutes)
            })
            var i = 0
            while (i < old.length() && out.length() < AUDIT_MAX) {
                out.put(old.get(i))
                i++
            }
            p.edit().putString(K_AUDIT, out.toString()).apply()
        } catch (e: Exception) { }
    }

    fun auditJson(ctx: Context): String = try {
        prefs(ctx).getString(K_AUDIT, "[]") ?: "[]"
    } catch (e: Exception) {
        "[]"
    }

    fun clearAudit(ctx: Context) {
        try {
            prefs(ctx).edit().putString(K_AUDIT, "[]").apply()
        } catch (e: Exception) { }
    }

    /* ---------- 用量缓存（10 秒） ---------- */

    @Synchronized
    private fun usageAll(ctx: Context): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (now - cacheAt > 10_000L) {
            cacheMap = UsageUtil.queryUsageMap(ctx, UsageUtil.dayStartMs(), now)
            cacheAt = now
        }
        return cacheMap
    }

    private fun usageMs(ctx: Context, pkg: String): Long = usageAll(ctx)[pkg] ?: 0L

    fun usedMinutes(ctx: Context, pkg: String): Int =
        (usageMs(ctx, pkg) / 60_000L).toInt()

    /* ---------- 规则计算 ---------- */

    private fun isWeekend(): Boolean {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
    }

    /** 今天的基准限额（不含加时），0 表示未配置 */
    fun baseLimitMinutes(pkg: String): Int {
        val r = rules[pkg] ?: return 0
        return if (isWeekend()) r.weekend else r.weekday
    }

    /** 有效限额 = 当日基准 + 今日加时 */
    fun effectiveLimitMinutes(ctx: Context, pkg: String): Int {
        val base = baseLimitMinutes(pkg)
        if (base <= 0) return 0
        return base + bonus(ctx, pkg)
    }

    fun totalLimit(): Int = totalLimitMin

    /** 所有受管应用今日真实用量合计（分钟） */
    fun totalUsedMinutes(ctx: Context): Int {
        if (rules.isEmpty()) return 0
        val map = usageAll(ctx)
        var sum = 0L
        for (pkg in rules.keys) sum += (map[pkg] ?: 0L)
        return (sum / 60_000L).toInt()
    }

    /** 是否处于免打扰时段 */
    fun inCurfew(): Boolean {
        if (!curfewOn) return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (curfewStartMin <= curfewEndMin) {
            now >= curfewStartMin && now < curfewEndMin
        } else {
            now >= curfewStartMin || now < curfewEndMin   // 跨零点，如 22:00 → 07:00
        }
    }

    fun curfewEnabled(): Boolean = curfewOn

    fun notifyBefore(): Int = notifyBeforeMin

    /* ---------- 核心判定 ---------- */

    private fun isIgnoredPackage(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return true
        if (pkg == ctx.packageName) return true
        if (pkg == "com.android.systemui") return true
        if (homePkgs.contains(pkg)) return true
        if (pkg.contains("launcher", true)) return true
        if (pkg.contains("inputmethod", true)) return true
        return false
    }

    /** 该应用此刻是否应当被拦（单应用超限 / 总时长超限 / 免打扰时段） */
    fun isBlockedNow(ctx: Context, pkg: String): Boolean {
        if (rules[pkg] == null) return false

        if (inCurfew()) return true

        if (totalLimitMin > 0 && totalUsedMinutes(ctx) >= totalLimitMin) return true

        val base = baseLimitMinutes(pkg)
        if (base <= 0) return false
        val eff = base + bonus(ctx, pkg)
        return usageMs(ctx, pkg) >= eff * 60_000L
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
        if (rules[pkg] == null) return false
        if (!isBlockedNow(ctx, pkg)) return false
        val now = System.currentTimeMillis()
        val next = strikes[pkg] ?: 0L
        if (now < next) return false
        strikes[pkg] = now + COOLDOWN_MS
        audit(ctx, "block", pkg, 0)
        return true
    }

    fun clearStrike(pkg: String) { strikes.remove(pkg) }

    fun clearAllStrikes() { strikes.clear() }

    /* ---------- 剩余时间提醒（每应用每天一次） ---------- */

    @Synchronized
    fun shouldNotifyRemaining(ctx: Context, pkg: String): Boolean {
        if (notifyBeforeMin <= 0) return false
        resetDailyIfNewDay(ctx)   // 跨天后提醒去重清单必须已重置，否则当天只发一次就哑了
        if (rules[pkg] == null) return false
        val eff = effectiveLimitMinutes(ctx, pkg)
        if (eff <= 0) return false
        val remain = eff - (usageMs(ctx, pkg) / 60_000L)
        if (remain <= 0 || remain > notifyBeforeMin) return false

        return try {
            val p = prefs(ctx)
            val day = p.getString(K_NOTIFY_DAY, "") ?: ""
            if (day != today()) return false
            val raw = p.getString(K_NOTIFY_DONE, "[]") ?: "[]"
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            for (i in 0 until arr.length()) if (arr.optString(i) == pkg) return false
            arr.put(pkg)
            p.edit().putString(K_NOTIFY_DONE, arr.toString()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /* ---------- 供网页展示的完整状态 ---------- */

    fun stateJson(ctx: Context): String {
        init(ctx)
        val o = JSONObject()
        o.put("paused", isPausedNow())
        o.put("pauseRemain", pauseRemainMinutes())
        o.put("policy", policy)
        o.put("fg", UsageUtil.foregroundPackage(ctx))
        o.put("curfew", inCurfew())
        o.put("totalLimit", totalLimitMin)
        o.put("totalUsed", totalUsedMinutes(ctx))
        o.put("weekend", isWeekend())
        return o.toString()
    }
}
