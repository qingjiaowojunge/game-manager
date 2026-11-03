package com.example.gamemanager

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 应用唯一入口：一个全屏 WebView 承载 assets/index.html。
 * HTML/JS/CSS 是应用本体；需要系统能力时通过 window.GameBridge 调到 GameBridge。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先把管控状态装好：限额清单、暂停态、执行策略、今日加时
        MonitorService.restore(this)
        Guard.init(this)

        web = WebView(this)
        web.setBackgroundColor(0xFFF5F6FA.toInt())
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage：保存限额配置
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

        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()

        // 原生能力注入：JS 里用 window.GameBridge.xxx()
        web.addJavascriptInterface(GameBridge(this), "GameBridge")

        web.loadUrl("file:///android_asset/index.html")

        // Android 13+ 通知权限（前台服务通知需要）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    /** 返回键：先让网页处理（网页可返回上一屏），网页不处理则退出 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && this::web.isInitialized && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // 家长进到本应用，拦截层必须让开
        OverlayBlocker.hide()
        if (this::web.isInitialized) web.onResume()
        // 每次回到前台，通知网页刷新真实数据（时长会变、授权状态可能刚改）
        if (this::web.isInitialized) {
            web.post { web.evaluateJavascript("window.__onNativeResume && window.__onNativeResume()", null) }
        }
    }

    override fun onPause() {
        if (this::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (this::web.isInitialized) {
            web.removeJavascriptInterface("GameBridge")
            web.destroy()
        }
        super.onDestroy()
    }
}
