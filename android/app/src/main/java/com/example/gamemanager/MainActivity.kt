package com.example.gamemanager

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView

/**
 * 应用唯一入口：一个全屏 WebView 承载 assets/index.html。
 *
 * 稳定性要求（针对"一打开就闪退"）：
 * - 所有初始化步骤各自 try/catch，任何一步失败都不许让 Activity 挂掉；
 * - WebView 本身创建失败时，退化成一张能看懂的提示页，而不是白屏或闪退；
 * - 全局异常由 App 的 CrashHandler 落盘，下次启动能在「我的 → 崩溃日志」里看到。
 */
class MainActivity : Activity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 每一步都独立兜底：任何一步失败都不影响界面显示
        try { MonitorService.restore(this) } catch (e: Throwable) { }
        try { Guard.init(this) } catch (e: Throwable) { }

        val view = try {
            buildWebView()
        } catch (e: Throwable) {
            null
        }

        if (view == null) {
            setContentView(fallbackView(e = null))
        } else {
            web = view
            setContentView(view)
            loadApp(view)
        }

        askNotificationPermission()
    }

    private fun buildWebView(): WebView {
        val v = WebView(this)
        v.setBackgroundColor(0xFFF5F6FA.toInt())

        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }

        v.webViewClient = WebViewClient()
        // 没有 WebChromeClient 时 JS 的 alert/prompt 会静默失效，必须设置
        v.webChromeClient = WebChromeClient()
        v.addJavascriptInterface(GameBridge(this), "GameBridge")
        return v
    }

    private fun loadApp(v: WebView) {
        try {
            v.loadUrl("file:///android_asset/index.html")
        } catch (e: Throwable) {
            setContentView(fallbackView(e))
        }
    }

    /** 兜底页面：宁可显示一段能看懂的话，也不要白屏或闪退 */
    private fun fallbackView(e: Throwable?): TextView {
        val crash = try { App.readCrash(this) } catch (err: Throwable) { "" }
        val sb = StringBuilder()
        sb.append("界面加载失败\n\n")
        sb.append("应用本体没能启动。可以先把下面这段发给开发者。\n\n")
        if (crash.isNotEmpty()) sb.append("上次崩溃记录：\n").append(crash.take(1200))
        else if (e != null) sb.append("错误：").append(e.toString())
        else sb.append("未捕获到具体原因。")

        return TextView(this).apply {
            text = sb.toString()
            textSize = 13f
            setPadding(48, 96, 48, 48)
            setTextColor(0xFF0F172A.toInt())
            setBackgroundColor(0xFFF5F6FA.toInt())
        }
    }

    private fun askNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        } catch (e: Throwable) { }
    }

    /** 返回键：网页能回退就回退，否则退出 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val v = web
            if (v != null && v.canGoBack()) {
                v.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        try { OverlayBlocker.hide() } catch (e: Throwable) { }
        val v = web ?: return
        try { v.onResume() } catch (e: Throwable) { }
        try {
            v.post {
                try {
                    v.evaluateJavascript("window.__onNativeResume && window.__onNativeResume()", null)
                } catch (e: Throwable) { }
            }
        } catch (e: Throwable) { }
    }

    override fun onPause() {
        try { web?.onPause() } catch (e: Throwable) { }
        super.onPause()
    }

    override fun onDestroy() {
        val v = web
        web = null
        if (v != null) {
            try { v.removeJavascriptInterface("GameBridge") } catch (e: Throwable) { }
            try { v.destroy() } catch (e: Throwable) { }
        }
        super.onDestroy()
    }
}
