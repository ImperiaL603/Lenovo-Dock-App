package com.lenovodock.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Fetches synced lyrics from lrclib.net for the current track and hands them
 * to the WebView as a flat, time-ordered list. One network fetch per distinct
 * track (title+artist+album), cached in memory so replaying a song doesn't
 * re-hit the network.
 *
 * lrclib usually holds SEVERAL records per song, and only some carry timings.
 * Its /api/get is an exact match on title+artist+album+duration, so Spotify's
 * album name decides which record we get — and for a single-release track that
 * is frequently the one with plain lyrics only. Hence the two-step lookup in
 * fetch(): exact first, then search-and-choose.
 */
object LyricsRepository {

    data class Line(val timeMs: Long, val text: String)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<String, List<Line>>()

    @Volatile
    var current: List<Line> = emptyList()
        private set

    private var observer: ((List<Line>) -> Unit)? = null
    private var lastKey: String? = null

    fun setObserver(o: ((List<Line>) -> Unit)?) {
        observer = o
        o?.let { obs -> main.post { obs(current) } }
    }

    /** Call whenever a NowPlaying snapshot arrives. No-ops if the track hasn't changed. */
    fun onTrack(title: String, artist: String, album: String, durationMs: Long) {
        if (title.isBlank() || artist.isBlank()) {
            Log.d(TAG, "skip: blank title/artist (title='$title' artist='$artist')")
            return
        }
        val key = "$artist|$title|$album"
        if (key == lastKey) return
        lastKey = key
        // The exact strings Spotify gave us. Mismatches against lrclib's own
        // spelling start here, so this is the first line to read on a miss.
        Log.d(TAG, "track: title='$title' artist='$artist' album='$album' duration=${durationMs / 1000}s")

        cache[key]?.let { Log.d(TAG, "cache hit: ${it.size} lines"); deliver(it); return }

        io.execute {
            val lines = fetch(title, artist, album, durationMs / 1000)
            // Only successes are cached. An empty result means "a timeout, a 5xx or
            // no timed record" — caching that would keep the track blank for the rest
            // of the process, so a later play gets another attempt instead.
            if (lines.isNotEmpty()) cache[key] = lines
            // Guard against a track change that happened while this fetch was in flight.
            if (key == lastKey) deliver(lines)
        }
    }

    private fun deliver(lines: List<Line>) {
        current = lines
        main.post { observer?.invoke(lines) }
    }

    private fun fetch(title: String, artist: String, album: String, durationSec: Long): List<Line> = try {
        val lrc = exactMatch(title, artist, album, durationSec)
            ?: searchMatch(title, artist, durationSec)
        val lines = if (lrc == null) emptyList() else parseLrc(lrc)
        // A count far below the tag count means parseLrc's regex rejected the
        // file's timestamp format; zero with a non-null body means the same.
        Log.d(TAG, "result: ${lines.size} lines parsed" +
            if (lrc != null) " from ${lrc.count { it == '\n' } + 1} raw lines" else " (no source)")
        lines
    } catch (e: Exception) {
        Log.w(TAG, "unparseable response", e)
        // A 200 carrying a body we can't parse. Third-party boundary, so worth
        // catching: without this the executor thread dies and nothing is delivered,
        // leaving the window blank rather than saying "No lyrics found".
        emptyList()
    }

    /**
     * Cheap path: one small response. Null both when lrclib has no matching record
     * and when the record it matched carries unsynced lyrics only — to us those are
     * the same outcome, and treating the second as "no lyrics" is what used to hide
     * lyrics that lrclib demonstrably had under a different album name.
     */
    private fun exactMatch(title: String, artist: String, album: String, durationSec: Long): String? {
        val url = "$API/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
            "&album_name=${enc(album)}&duration=$durationSec"
        val body = httpGet(url)
        if (body == null) {
            Log.d(TAG, "exact: no record -> $url")
            return null
        }
        val o = JSONObject(body)
        val synced = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
        Log.d(TAG, "exact: id=${o.optInt("id")} dur=${o.optDouble("duration")} " +
            "album='${o.optString("albumName")}' timed=${synced != null}")
        return synced
    }

    /**
     * Fallback: consider every record for this song and take the timed one whose
     * duration is closest to what Spotify reports. Search results carry junk entries
     * (a 3-second "record" for a 3-minute song), so the duration bound is what keeps
     * those out — and what makes this safe to pick from automatically.
     */
    private fun searchMatch(title: String, artist: String, durationSec: Long): String? {
        val body = httpGet("$API/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
            ?: return null
        val results = JSONArray(body)
        var best: String? = null
        var bestDelta = Long.MAX_VALUE
        var bestId = 0
        var timed = 0
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val synced = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() } ?: continue
            timed++
            // Spotify occasionally reports 0 before metadata settles; with nothing to
            // compare against, the first timed record is the best available guess.
            if (durationSec <= 0) {
                Log.d(TAG, "search: duration unknown, taking first timed id=${o.optInt("id")}")
                return synced
            }
            val delta = abs(o.optDouble("duration", -1.0).toLong() - durationSec)
            if (delta <= DURATION_TOLERANCE_SEC && delta < bestDelta) {
                best = synced
                bestDelta = delta
                bestId = o.optInt("id")
            }
        }
        Log.d(TAG, "search: ${results.length()} records, $timed timed -> " +
            if (best != null) "chose id=$bestId delta=${bestDelta}s"
            else "none within ${DURATION_TOLERANCE_SEC}s of ${durationSec}s")
        return best
    }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", USER_AGENT) // lrclib asks clients to identify themselves
        }
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        null
    }

    private val TAG_RE = Regex("""\[(\d{2}):(\d{2}(?:\.\d{1,3})?)]""")

    private fun parseLrc(lrc: String): List<Line> {
        val lines = mutableListOf<Line>()
        lrc.lineSequence().forEach { raw ->
            val tags = TAG_RE.findAll(raw).toList()
            if (tags.isEmpty()) return@forEach
            val text = raw.substring(tags.last().range.last + 1).trim()
            tags.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toDouble()
                lines += Line((min * 60_000L) + (sec * 1000).toLong(), text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    fun toJson(lines: List<Line>): String {
        val arr = JSONArray()
        lines.forEach {
            arr.put(JSONObject().apply {
                put("t", it.timeMs)
                put("text", it.text)
            })
        }
        return arr.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Shared with MediaListenerService so `adb logcat -s LenovoDock` shows the
     *  playback snapshot and the lyrics lookup for a track interleaved. */
    private const val TAG = "LenovoDock"
    private const val API = "https://lrclib.net/api"
    private const val USER_AGENT = "LenovoDock/1.0 (https://github.com/lenovodock)"
    private const val DURATION_TOLERANCE_SEC = 3L
}