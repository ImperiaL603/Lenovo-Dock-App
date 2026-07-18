package com.lenovodock.app

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import java.io.File

/**
 * JS bridge exposed to the WebView as `AndroidMedia`.
 *
 * Wallpaper videos live on device (app-private external storage), so the folder
 * is the single source of truth for which wallpapers exist. Real now-playing /
 * position / transport wiring lands in step 2 alongside the media-session listener.
 */
class MediaBridge(private val context: Context) {

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
