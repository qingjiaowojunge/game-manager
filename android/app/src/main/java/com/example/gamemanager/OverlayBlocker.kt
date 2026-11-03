package com.example.gamemanager

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮窗拦截层。
 *
 * 为什么需要它：Android 10 起，后台服务不能随意拉起 Activity，
 * 单纯 startActivity(BlockActivity) 在真机上大概率什么都不会弹出来。
 * 拿到"显示在其他应用上层"权限后，用 WindowManager 直接盖一层全屏遮罩，
 * 这条路不依赖无障碍、不依赖后台起 Activity，行为最确定。
 *
 * 遮罩带出口（加时 / 回桌面），不是死墙——上一版的教训是"关不掉的遮罩等于砖"。
 */
object OverlayBlocker {

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var currentPkg: String = ""

    fun canOverlay(ctx: Context): Boolean =
        try { Settings.canDrawOverlays(ctx) } catch (e: Exception) { false }

    fun isShowing(): Boolean = root != null

    fun show(ctx: Context, pkg: String) {
        if (root != null) {
            // 已显示：只更新内容，避免叠层
            currentPkg = pkg
            updateTexts(ctx)
            return
        }
        currentPkg = pkg
        main.post {
            try {
                val app = ctx.applicationContext
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = buildView(app)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.CENTER
                wm.addView(v, lp)
                root = v
                Enforcer.blockShowing = true
            } catch (e: Exception) {
                root = null
                Enforcer.blockShowing = false
            }
        }
    }

    fun hide() {
        main.post {
            val v = root ?: return@post
            try {
                val app = v.context.applicationContext
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            } catch (e: Exception) { }
            root = null
            Enforcer.blockShowing = false
        }
    }

    /* ---------- UI ---------- */

    private var titleView: TextView? = null
    private var detailView: TextView? = null
    private var headView: TextView? = null
    // 专注模式下第一个按钮要变成"结束专注"，所以按钮得留引用才能动态改
    private var btn1: Button? = null
    private var btn2: Button? = null

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun buildView(ctx: Context): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(ctx, 28), dp(ctx, 28), dp(ctx, 28), dp(ctx, 28))
            // 吃掉所有触摸，避免穿透到下层应用
            isClickable = true
        }

        headView = TextView(ctx).apply {
            text = "时间到"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        box.addView(headView)

        titleView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 25f
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        box.addView(titleView)

        detailView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 24))
            setLineSpacing(dp(ctx, 6).toFloat(), 1f)
        }
        box.addView(detailView)

        val b1 = button(ctx, "加时 15 分钟（需家长密码）", "#2563EB") { onPrimary(ctx) }
        btn1 = b1
        box.addView(b1)
        val b2 = button(ctx, "加时 30 分钟（需家长密码）", "#2563EB") { askPassword(ctx, 30) }
        btn2 = b2
        box.addView(b2)
        box.addView(button(ctx, "回到桌面", "#334155") {
            hide()
            goHome(ctx)
        })

        updateTexts(ctx)
        return box
    }

    private fun button(ctx: Context, label: String, color: String, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.textSize = 15f
        b.setTextColor(Color.WHITE)
        b.isAllCaps = false
        val bg = GradientDrawable()
        bg.cornerRadius = dp(ctx, 14).toFloat()
        bg.setColor(Color.parseColor(color))
        b.background = bg
        b.stateListAnimator = null
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52))
        lp.topMargin = dp(ctx, 10)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun appLabel(ctx: Context): String {
        if (currentPkg.isEmpty()) return "该应用"
        return try {
            val ai = ctx.packageManager.getApplicationInfo(currentPkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) { currentPkg }
    }

    private fun updateTexts(ctx: Context) {
        main.post {
            val name = appLabel(ctx)
            if (Guard.focusActive()) {
                // 专注模式：这不是"时间用完了"，文案必须说清楚，否则家长会以为额度出问题
                headView?.text = "专注中"
                titleView?.text = "$name 专注模式中"
                detailView?.text =
                    "专注还剩 ${Guard.focusRemainMinutes()} 分钟\n这段时间受管应用一律打开不了，专注结束就恢复。"
                btn1?.text = "家长：结束专注（需密码）"
                btn2?.visibility = View.GONE
                return@post
            }
            headView?.text = "时间到"
            titleView?.text = "$name 今日时间已用完"
            val used = Guard.usedMinutes(ctx, currentPkg)
            val limit = Guard.effectiveLimitMinutes(ctx, currentPkg)
            detailView?.text =
                "已用 $used 分钟 / 限额 $limit 分钟\n请让家长输入密码加时，或先做点别的。"
            btn1?.text = "加时 15 分钟（需家长密码）"
            btn2?.visibility = View.VISIBLE
        }
    }

    /** 第一个按钮分流：专注期里点是"结束专注"，平时是"加时 15 分钟" */
    private fun onPrimary(ctx: Context) {
        if (Guard.focusActive()) askEndFocus(ctx) else askPassword(ctx, 15)
    }

    private fun askEndFocus(ctx: Context) {
        val input = EditText(ctx).apply {
            hint = "家长密码"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        }
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0)
        }
        holder.addView(input)

        val dlg = AlertDialog.Builder(ctx)
            .setTitle("结束专注模式")
            .setView(holder)
            .setPositiveButton("结束专注") { _, _ ->
                if (Guard.checkParentPassword(ctx, input.text.toString().trim())) {
                    Guard.stopFocus(ctx)
                    Guard.clearStrike(currentPkg)
                    hide()
                } else {
                    android.widget.Toast.makeText(ctx, "密码错误", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("继续专注", null)
            .create()

        dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dlg.show()
    }

    private fun askPassword(ctx: Context, minutes: Int) {
        val input = EditText(ctx).apply {
            hint = "家长密码"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        }
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0)
        }
        holder.addView(input)

        val dlg = AlertDialog.Builder(ctx)
            .setTitle("给「${appLabel(ctx)}」加时 $minutes 分钟")
            .setView(holder)
            .setPositiveButton("确认加时") { _, _ ->
                if (Guard.checkParentPassword(ctx, input.text.toString().trim())) {
                    Guard.addBonus(ctx, currentPkg, minutes)
                    Guard.clearStrike(currentPkg)
                    hide()
                } else {
                    android.widget.Toast.makeText(ctx, "密码错误", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .create()

        // 服务没有 Activity 窗口，必须显式指定悬浮窗类型，否则会崩
        dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dlg.show()
    }

    private fun goHome(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { }
    }
}
