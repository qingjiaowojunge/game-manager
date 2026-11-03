package com.example.gamemanager

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 全屏拦截页 —— 超限后的默认处置手段。
 *
 * 为什么不用"锁屏"：锁屏会把整台手机锁死，家长想操作也被打断，实测表现为
 * "一碰就锁定、界面随即退出"。这里改成只覆盖屏幕：
 * - 孩子退不出去（返回键无效），但手机本身没有锁；
 * - 家长可以当场输密码加时，或回到桌面；
 * - 不依赖无障碍服务、不依赖设备管理员，权限要求最低、行为最可预期。
 */
class BlockActivity : Activity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private var pkg: String = ""
    private var titleView: TextView? = null
    private var detailView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent?.getStringExtra(EXTRA_PKG) ?: ""
        Guard.init(this)
        buildUi()
        refresh()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_PKG)?.let { pkg = it }
        refresh()
    }

    /** 返回键无效：拦截页必须家长处理才会消失 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 故意留空：不允许孩子用返回键绕过
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val icon = TextView(this).apply {
            text = "时间到"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        root.addView(icon)

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(titleView)

        detailView = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        root.addView(detailView)

        root.addView(makeButton("加时 15 分钟（需家长密码）", "#2563EB") { askPassword(15) })
        root.addView(makeButton("加时 30 分钟（需家长密码）", "#2563EB") { askPassword(30) })
        root.addView(makeButton("回到桌面", "#334155") { goHome() })

        setContentView(root)
    }

    private fun makeButton(label: String, color: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 15f
        b.setTextColor(Color.WHITE)
        b.isAllCaps = false
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(Color.parseColor(color))
        b.background = bg
        b.stateListAnimator = null
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        )
        lp.topMargin = dp(10)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun appLabel(): String {
        if (pkg.isEmpty()) return "该应用"
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun refresh() {
        val name = appLabel()
        titleView?.text = "$name 今日时间已用完"
        val used = Guard.usedMinutes(this, pkg)
        val limit = Guard.effectiveLimitMinutes(this, pkg)
        detailView?.text =
            "已用 $used 分钟 / 限额 $limit 分钟\n" +
            "请让家长输入密码加时，或先做点别的。"
    }

    private fun askPassword(minutes: Int) {
        val input = EditText(this).apply {
            hint = "家长密码"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("给「${appLabel()}」加时 $minutes 分钟")
            .setView(box)
            .setPositiveButton("确认加时") { _, _ ->
                if (Guard.checkParentPassword(this, input.text.toString().trim())) {
                    Guard.addBonus(this, pkg, minutes)
                    Guard.clearStrike(pkg)
                    finish()
                } else {
                    toastAndStay("密码错误")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toastAndStay(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun goHome() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { }
        finish()
    }

    /** 拦截页自身不允许被误当作普通窗口处理 */
    override fun onResume() {
        super.onResume()
        Guard.init(this)
        refresh()
    }

    override fun onDestroy() {
        Enforcer.blockShowing = false
        super.onDestroy()
    }
}
