package com.lenovodock.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Hosts the reused web dashboard in a full-screen WebView. The tablet is an
 * always-on wall display, so the screen is kept awake and system bars hidden.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var autoDim: AutoDim
    private var pageReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Built before the WebView because it is one of the bridges registered on it.
        autoDim = AutoDim(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true                    // localStorage: wallpaper choice
            settings.mediaPlaybackRequiresUserGesture = false    // autoplay the muted wallpaper video
            settings.allowFileAccess = true                      // play wallpaper videos from app storage
            webViewClient = object : WebViewClient() {           // inject once the page is ready
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    inject(NowPlayingRepository.current)
                    injectLyrics(LyricsRepository.current)
                    injectAlarms()
                }
            }
            // Without this, console.log from the page never reaches logcat and only
            // uncaught JS errors show up (as chromium [INFO:CONSOLE]).
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d(TAG, "js: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
            addJavascriptInterface(MediaBridge(this@MainActivity), MediaBridge.NAME)
            addJavascriptInterface(AlarmBridge(this@MainActivity), AlarmBridge.NAME)
            addJavascriptInterface(autoDim, AutoDim.NAME)
            loadUrl("file:///android_asset/web/index.html")
        }
        setContentView(webView)

        NowPlayingRepository.setObserver { inject(it) }
        LyricsRepository.setObserver { injectLyrics(it) }
        TimerTicker.setObserver { injectTimerTick(it) }
        TimerTicker.start(this)
    }

    override fun onResume() {
        super.onResume()
        autoDim.onResume()
    }

    override fun onPause() {
        autoDim.onPause()
        super.onPause()
    }

     private fun injectAlarms() {
        if (!pageReady) return
        val json = AlarmBridge(this).listAlarms()
        webView.evaluateJavascript("window.LenovoDock&&LenovoDock.onAlarmsChanged($json)", null)
    }
 
    private fun injectTimerTick(timers: List<TimerItem>) {
        if (!pageReady) return
        val arr = org.json.JSONArray(); timers.forEach { arr.put(it.toJson()) }
        webView.evaluateJavascript("window.LenovoDock&&LenovoDock.onTimerTick($arr)", null)
    }

    private fun injectLyrics(lyrics: LyricsRepository.Lyrics) {
    if (!pageReady) return
    val json = LyricsRepository.toJson(lyrics)
    webView.evaluateJavascript("window.LenovoDock&&LenovoDock.onLyrics($json)", null)
    }

    private fun inject(np: NowPlaying?) {
        if (!pageReady) return
        val call = if (np == null) "window.LenovoDock&&LenovoDock.onPlaybackGone()"
        else "window.LenovoDock&&LenovoDock.onNowPlaying(${np.toJson()})"
        webView.evaluateJavascript(call, null)
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
        NowPlayingRepository.setObserver(null)
        LyricsRepository.setObserver(null)
        TimerTicker.stop()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LenovoDock" // shared with MediaListenerService / LyricsRepository
    }
}
