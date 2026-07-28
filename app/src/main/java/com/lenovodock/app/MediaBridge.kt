package com.lenovodock.app

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import java.io.File

/**
 * JS bridge exposed to the WebView as `AndroidMedia`.
 *
 * Wallpaper videos live on device (app-private external storage), so the folder
 * is the single source of truth for which wallpapers exist.
 *
 * Transport commands are forwarded to NowPlayingRepository, which holds the
 * TransportControls of whichever Spotify session MediaListenerService has bound.
 * Now-playing state travels the other way, injected straight into the page.
 */
class MediaBridge(private val context: Context) {

    @JavascriptInterface
    fun togglePlayPause() = NowPlayingRepository.togglePlayPause()

    /** Sleep timer. The deadline is the armed state — 0 means off. Scheduling is
     *  native so it survives the page, and the page reads state back from here. */
    @JavascriptInterface
    fun armSleep(minutes: Int): Long = SleepReceiver.arm(context, minutes)

    @JavascriptInterface
    fun cancelSleep() = SleepReceiver.cancel(context)

    @JavascriptInterface
    fun sleepDeadline(): Long = SleepReceiver.deadline(context)

    @JavascriptInterface
    fun skipToNext() = NowPlayingRepository.skipToNext()

    @JavascriptInterface
    fun skipToPrevious() = NowPlayingRepository.skipToPrevious()

    /** file:// base URL of the on-device wallpapers folder, with trailing slash. */
    @JavascriptInterface
    fun wallpapersBaseUrl(): String = "file://${wallpapersDir().absolutePath}/"

    /** JSON array of the .mp4 filenames currently in the wallpapers folder, sorted. */
    @JavascriptInterface
    fun listWallpapers(): String {
        val names = wallpapersDir()
            .listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        return JSONArray(names).toString()
    }

    private fun wallpapersDir(): File {
        val dir = context.getExternalFilesDir(WALLPAPERS_SUBDIR)
            ?: File(context.filesDir, WALLPAPERS_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    companion object {
        const val NAME = "AndroidMedia"
        private const val WALLPAPERS_SUBDIR = "wallpapers"
    }
}
