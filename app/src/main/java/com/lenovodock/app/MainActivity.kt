package com.lenovodock.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Hosts the reused web dashboard in a full-screen WebView. The tablet is an
 * always-on wall display, so the screen is kept awake and system bars hidden.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true                    // localStorage: wallpaper choice
            settings.mediaPlaybackRequiresUserGesture = false    // autoplay the muted wallpaper video
            settings.allowFileAccess = true                      // play wallpaper videos from app storage
            webViewClient = WebViewClient()                      // keep navigation inside the WebView
            addJavascriptInterface(MediaBridge(this@MainActivity), MediaBridge.NAME)
            loadUrl("file:///android_asset/web/index.html")
        }
        setContentView(webView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    // Android 10 (API 29): systemUiVisibility is the correct API for this device.
    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
