package com.lenovodock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Turns whatever album art a player offers into one URL the page can put in an <img>.
 *
 * Spotify gives an https URL, which is passed straight through. Most other players
 * embed a bitmap in the session or point at a content:// or file:// URI, neither of
 * which the WebView can read and neither of which can be handed over as a URL. Those
 * are written to a small JPEG in the app's cache and returned as a file:// URL, the
 * same way the wallpapers reach the page.
 *
 * Runs on the main thread, from the media-session callback. A track costs one decode
 * and one small write, remembered by key afterwards. A miss is NOT remembered: some
 * players publish the title first and the art a moment later, so the next snapshot
 * for the same track has to be allowed to find it.
 */
object ArtCache {

    private const val TAG = "LenovoDock"
    private const val SUBDIR = "art"
    private const val KEEP = 20       // files kept; the oldest beyond this are deleted
    private const val MAX_PX = 640    // same size Spotify serves, plenty for the sleeve
    private const val SPOTIFY_ART_HTTPS_URI = "com.spotify.music.extra.ART_HTTPS_URI"

    private val known = ConcurrentHashMap<String, String>()

    fun urlFor(context: Context, md: MediaMetadata, key: String): String? {
        known[key]?.let { return it }

        md.getString(SPOTIFY_ART_HTTPS_URI)?.takeIf { it.isNotBlank() }
            ?.let { return remember(key, it) }

        val uri = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.isNotBlank() } }
        if (uri != null && (uri.startsWith("http://") || uri.startsWith("https://"))) {
            return remember(key, uri)
        }

        // Owned by the metadata object, so it is read but never recycled here.
        val embedded = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (embedded != null) return save(context, key, embedded, recycle = false)

        if (uri != null) {
            val decoded = decodeUri(context, uri) ?: return null
            return save(context, key, decoded, recycle = true)
        }
        return null
    }

    private fun remember(key: String, url: String): String {
        known[key] = url
        return url
    }

    /** Two passes: bounds first, so a 4000px cover is decoded at a fraction of its size. */
    private fun decodeUri(context: Context, uri: String): Bitmap? = try {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        if (bounds.outWidth > 0) {
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_PX * 2) sample *= 2
        }
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        Log.w(TAG, "art: could not read $uri: ${e.javaClass.simpleName}")
        null
    }

    private fun save(context: Context, key: String, bmp: Bitmap, recycle: Boolean): String? = try {
        val longest = max(bmp.width, bmp.height)
        val scaled = if (longest > MAX_PX) {
            val f = MAX_PX.toFloat() / longest
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1),
                (bmp.height * f).toInt().coerceAtLeast(1), true)
        } else bmp
        val dir = File(context.cacheDir, SUBDIR).apply { mkdirs() }
        val file = File(dir, "${hash(key)}.jpg")
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (scaled !== bmp) scaled.recycle()
        if (recycle) bmp.recycle()
        prune(dir)
        Log.d(TAG, "art: saved ${file.name} (${file.length() / 1024}KB)")
        remember(key, "file://${file.absolutePath}")
    } catch (e: Exception) {
        Log.w(TAG, "art: save failed: ${e.javaClass.simpleName}")
        null
    }

    private fun prune(dir: File) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
