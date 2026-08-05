package com.lenovodock.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * Owns the on-device wallpapers folder: what is in it, copying a picked video in,
 * and deleting one back out.
 *
 * The folder is app-private external storage, so none of this needs a storage
 * permission — and it is the single source of truth for which wallpapers exist.
 * Copying the file in rather than holding a reference to wherever the user picked
 * it from is the whole point: once it is here, the original can be deleted and the
 * wallpaper still plays.
 */
object WallpaperStore {

    private const val TAG = "LenovoDock"
    private const val SUBDIR = "wallpapers"

    /** WebView plays h264 mp4 and webm; anything else would copy in and then sit
     *  in the list refusing to play, which looks like a broken wallpaper. */
    private val ALLOWED = setOf("mp4", "webm", "m4v")
    val MIME_TYPES = arrayOf("video/mp4", "video/webm")

    fun dir(context: Context): File {
        val d = context.getExternalFilesDir(SUBDIR) ?: File(context.filesDir, SUBDIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(context: Context): List<String> =
        dir(context).listFiles { f -> f.isFile && f.extension.lowercase() in ALLOWED }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /**
     * Streams the picked document into the folder. Returns the filename it was
     * saved as, or null if the copy failed.
     *
     * Runs on whatever thread it is called from and does real IO, so callers must
     * keep it off the main thread — a 40MB video over MTP is not instant.
     */
    fun importFrom(context: Context, uri: Uri): String? {
        val name = uniqueName(context, displayName(context, uri))
        val target = File(dir(context), name)
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                target.outputStream().use { input.copyTo(it) }
            }
            Log.d(TAG, "wallpaper: imported '$name' (${target.length() / 1024}KB)")
            name
        } catch (e: Exception) {
            // The picked document can vanish, be unreadable, or the volume can fill
            // up mid-copy. A half-written file would show in the list and fail to
            // play, so it goes.
            target.delete()
            Log.w(TAG, "wallpaper: import failed", e)
            null
        }
    }

    /** Returns how many were actually removed. */
    fun delete(context: Context, names: List<String>): Int {
        val folder = dir(context)
        return names.count { name ->
            // The name comes from the page. Anything with a path separator in it is
            // not a file in this folder and must never be resolved relative to it.
            if (name.contains('/') || name.contains('\\') || name.contains("..")) {
                Log.w(TAG, "wallpaper: refused suspicious name '$name'")
                false
            } else {
                File(folder, name).delete().also {
                    if (it) Log.d(TAG, "wallpaper: deleted '$name'")
                }
            }
        }
    }

    /** The document's own display name where it has one, so the file keeps the name
     *  the owner recognises it by rather than a content-provider id. */
    private fun displayName(context: Context, uri: Uri): String {
        val raw = context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "wallpaper"
        val safe = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "-")
        return if (safe.substringAfterLast('.', "").lowercase() in ALLOWED) safe else "$safe.mp4"
    }

    /** Adding the same video twice suffixes rather than overwrites — the copy already
     *  in the folder may be the one currently on screen. */
    private fun uniqueName(context: Context, name: String): String {
        val folder = dir(context)
        if (!File(folder, name).exists()) return name
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.')
        var n = 2
        while (File(folder, "$stem-$n.$ext").exists()) n++
        return "$stem-$n.$ext"
    }
}
