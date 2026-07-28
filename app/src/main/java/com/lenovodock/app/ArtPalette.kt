package com.lenovodock.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Picks the one colour that best represents the current album art, so the dock can
 * take on the record's hue instead of a fixed theme.
 *
 * Sampling has to happen natively: the art is an https URL rendered in an <img>, and
 * drawing a cross-origin image to a canvas taints it, so getImageData() in the page
 * would throw rather than return pixels.
 *
 * Same shape as LyricsRepository — one fetch per distinct URL, cached in memory,
 * with an in-flight guard so a result that arrives after the track moved on is
 * dropped rather than painted over the new one.
 */
object ArtPalette {

    /** Packed 0xRRGGBB, or null when there is no art or nothing usable in it. */
    @Volatile
    var current: Int? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<String, Int>()

    private var observer: ((Int?) -> Unit)? = null
    private var lastUrl: String? = null

    fun setObserver(o: ((Int?) -> Unit)?) {
        observer = o
        o?.let { obs -> main.post { obs(current) } }
    }

    /** Call with each snapshot's art URL. No-ops while the art hasn't changed. */
    fun onArt(url: String?) {
        if (url == lastUrl) return
        lastUrl = url
        if (url == null) {
            deliver(null)
            return
        }
        cache[url]?.let { deliver(it); return }
        io.execute {
            val colour = sample(url)
            if (colour != null) cache[url] = colour
            if (url == lastUrl) deliver(colour)
        }
    }

    private fun deliver(colour: Int?) {
        current = colour
        main.post { observer?.invoke(colour) }
    }

    private fun sample(url: String): Int? {
        val bmp = download(url) ?: return null
        return try {
            dominant(bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun download(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
        }
        if (conn.responseCode != 200) {
            null
        } else {
            conn.inputStream.use {
                // Spotify's art is 640px square. A histogram needs far fewer pixels
                // than that, and full-size decoding on a 2GB device to pick one colour
                // is waste, so it comes down to 80px on the way in.
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = DOWNSAMPLE
                })
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "art fetch failed: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Exact pixel values almost never repeat in a photograph, so pixels are quantised
     * to 4 bits per channel and allowed to reinforce each other; the winning bucket is
     * then averaged from its real members to get back a precise colour.
     *
     * Pixels that cannot carry a hue are skipped rather than counted. Sleeve art is
     * very often mostly black or mostly white, and by population alone that background
     * wins every time and hands back a grey — which is exactly the near-neutral colour
     * the themes already proved invisible at dock distance.
     */
    private fun dominant(bmp: Bitmap): Int? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val counts = HashMap<Int, Int>()
        val sums = HashMap<Int, LongArray>()
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            if (hi < MIN_CHANNEL || lo > MAX_CHANNEL) continue // near-black, near-white
            if (hi - lo < MIN_CHROMA) continue                 // grey carries no hue
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            counts[key] = (counts[key] ?: 0) + 1
            val s = sums.getOrPut(key) { LongArray(3) }
            s[0] += r.toLong()
            s[1] += g.toLong()
            s[2] += b.toLong()
        }

        val best = counts.maxByOrNull { it.value } ?: return null
        val n = best.value
        val s = sums.getValue(best.key)
        return normalise((s[0] / n).toInt(), (s[1] / n).toInt(), (s[2] / n).toInt())
    }

    /**
     * A colour sampled from art can be arbitrarily dark — it only had to be the most
     * common hue, not a legible one. Scaling every channel until the brightest reaches
     * TARGET_MAX lifts it to a readable level while holding the ratios between
     * channels, which is what the eye reads as the hue staying put.
     */
    private fun normalise(r: Int, g: Int, b: Int): Int {
        val hi = max(r, max(g, b)).coerceAtLeast(1)
        if (hi >= TARGET_MAX) return (r shl 16) or (g shl 8) or b
        val scale = TARGET_MAX.toFloat() / hi
        val nr = (r * scale).toInt().coerceAtMost(255)
        val ng = (g * scale).toInt().coerceAtMost(255)
        val nb = (b * scale).toInt().coerceAtMost(255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    private const val TAG = "LenovoDock"
    private const val DOWNSAMPLE = 8
    private const val MIN_CHANNEL = 40   // below this the pixel is effectively black
    private const val MAX_CHANNEL = 225  // above this on every channel it is white
    private const val MIN_CHROMA = 24    // spread between channels that counts as colour
    private const val TARGET_MAX = 210   // brightest channel after normalising
}
