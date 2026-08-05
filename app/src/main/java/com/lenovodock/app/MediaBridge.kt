package com.lenovodock.app

import android.webkit.JavascriptInterface
import org.json.JSONArray

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
/**
 * Typed as MainActivity rather than Context because the wallpaper picker has to be
 * launched for a result, which only an Activity can do. Everywhere a plain Context
 * was wanted this still is one.
 */
class MediaBridge(private val context: MainActivity) {

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

    /** Fade-out window in minutes, 0 for off. Native-owned like the deadline: the
     *  alarm that acts on it fires with no page attached. */
    @JavascriptInterface
    fun setSleepFade(minutes: Int) = SleepFade.setMinutes(context, minutes)

    @JavascriptInterface
    fun sleepFade(): Int = SleepFade.minutes(context)

    @JavascriptInterface
    fun skipToNext() = NowPlayingRepository.skipToNext()

    @JavascriptInterface
    fun skipToPrevious() = NowPlayingRepository.skipToPrevious()

    /** Ad muting. Native owns the state — MediaListenerService acts on it with no
     *  page attached — so the panel reads it back rather than storing its own. */
    @JavascriptInterface
    fun setAdMute(on: Boolean) = AdMuter.setEnabled(context, on)

    @JavascriptInterface
    fun adMuteEnabled(): Boolean = AdMuter.isEnabled(context)

    /** file:// base URL of the on-device wallpapers folder, with trailing slash. */
    @JavascriptInterface
    fun wallpapersBaseUrl(): String = "file://${WallpaperStore.dir(context).absolutePath}/"

    /** JSON array of the video filenames currently in the wallpapers folder, sorted. */
    @JavascriptInterface
    fun listWallpapers(): String = JSONArray(WallpaperStore.list(context)).toString()

    /**
     * Opens the system file picker. Nothing is returned here — the result arrives
     * asynchronously in MainActivity and is pushed back into the page, because a
     * bridge call cannot wait for an Activity result.
     */
    @JavascriptInterface
    fun addWallpaper() = context.runOnUiThread { context.pickWallpaper() }

    /** Takes a JSON array of filenames; returns how many were actually removed. */
    @JavascriptInterface
    fun deleteWallpapers(namesJson: String): Int {
        val arr = JSONArray(namesJson)
        val names = (0 until arr.length()).map { arr.getString(it) }
        return WallpaperStore.delete(context, names)
    }

    companion object {
        const val NAME = "AndroidMedia"
    }
}
