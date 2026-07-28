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
import kotlin.math.roundToLong

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
    fun onTrack(np: NowPlaying) {
        val title = np.title
        val artist = np.artist
        val album = np.album
        if (title.isBlank() || artist.isBlank()) {
            Log.d(TAG, "skip: blank title/artist (title='$title' artist='$artist')")
            return
        }
        val key = "$artist|$title|$album"
        if (key == lastKey) return
        // Set before the ad check on purpose: an ad still has to claim lastKey, or
        // the song resuming afterwards would match the pre-ad key, be treated as
        // unchanged, and never get its lyrics re-delivered.
        lastKey = key
        if (np.isAd) {
            Log.d(TAG, "ad break — no lookup ('$title' / '$artist')")
            deliver(emptyList())
            return
        }
        // The exact strings Spotify gave us. Mismatches against lrclib's own
        // spelling start here, so this is the first line to read on a miss.
        Log.d(TAG, "track: title='$title' artist='$artist' album='$album' duration=${np.durationMs / 1000}s")

        cache[key]?.let { Log.d(TAG, "cache hit: ${it.size} lines"); deliver(it); return }

        io.execute {
            val lines = fetch(title, artist, album, np.durationMs / 1000)
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
            ?: neteaseMatch(title, artist, durationSec)
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
     * duration is closest to what Spotify reports.
     *
     * Two passes, because Spotify names every credited artist ("Shiloh Dynasty,
     * VAL, Zuriel") while lrclib indexes most of those tracks under the lead artist
     * alone and returns an empty array for the joined string.
     */
    private fun searchMatch(title: String, artist: String, durationSec: Long): String? {
        searchOnce(title, artist, durationSec)?.let { return it }
        val lead = artist.substringBefore(',').trim()
        if (lead == artist || lead.isEmpty()) return null
        Log.d(TAG, "search: retrying with lead artist '$lead'")
        return searchOnce(title, lead, durationSec)
    }

    /**
     * Search results carry junk entries (a 3-second "record" for a 3-minute song),
     * so the duration bound is what keeps those out — and what makes this safe to
     * pick from automatically.
     */
    private fun searchOnce(title: String, artist: String, durationSec: Long): String? {
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
            // roundToLong, not toLong: lrclib reports fractional seconds, and
            // truncating inflates every delta by up to a second against the bound.
            val delta = abs(o.optDouble("duration", -1.0).roundToLong() - durationSec)
            if (delta <= LRCLIB_TOLERANCE_SEC && delta < bestDelta) {
                best = synced
                bestDelta = delta
                bestId = o.optInt("id")
            }
        }
        Log.d(TAG, "search('$artist'): ${results.length()} records, $timed timed -> " +
            if (best != null) "chose id=$bestId delta=${bestDelta}s"
            else "none within ${LRCLIB_TOLERANCE_SEC}s of ${durationSec}s")
        return best
    }

    /**
     * Second provider, tried only after lrclib has nothing timed. lrclib is
     * community-uploaded and frequently holds a track's words with nobody's
     * timings attached; NetEase's catalogue carries timed LRC for many of those.
     *
     * This is NetEase's own web endpoint, not a documented public API — it can
     * change shape or start demanding auth without notice. Every failure path
     * here returns null, so the worst case degrades to "No lyrics found" rather
     * than to wrong lyrics.
     */
    private fun neteaseMatch(title: String, artist: String, durationSec: Long): String? {
        val body = httpGet("$NETEASE/search/get?s=${enc("$title $artist")}&type=1&limit=5", NETEASE_HEADERS)
            ?: return null
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
        if (songs == null || songs.length() == 0) {
            Log.d(TAG, "netease: no search results")
            return null
        }
        val id = pickNeteaseSong(songs, artist, durationSec)
        if (id == null) {
            Log.d(TAG, "netease: ${songs.length()} results, none matching artist + ${durationSec}s")
            return null
        }
        val lyricBody = httpGet("$NETEASE/song/lyric?id=$id&lv=1&kv=1&tv=-1", NETEASE_HEADERS)
            ?: return null
        val lrc = JSONObject(lyricBody).optJSONObject("lrc")
            ?.optString("lyric", "")?.takeIf { it.isNotBlank() }
        Log.d(TAG, "netease: chose song id=$id timed=${lrc != null}")
        return lrc
    }

    /**
     * Duration is the primary filter — it rejects the sped-up edits and piano
     * covers that share a title. The artist check then guards against a cover of
     * the same length, because showing the wrong words is worse than showing none.
     */
    private fun pickNeteaseSong(songs: JSONArray, artist: String, durationSec: Long): Long? {
        val ourArtist = artist.substringBefore(',').trim().lowercase()
        var bestId: Long? = null
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val delta = abs(s.optLong("duration") / 1000 - durationSec)
            if (durationSec > 0 && delta > NETEASE_TOLERANCE_SEC) continue
            val names = s.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
            }?.joinToString(", ")?.lowercase().orEmpty()
            // Either direction: Spotify may say "A, B, C" where NetEase says just "A".
            val artistOk = names.contains(ourArtist) ||
                (names.isNotEmpty() && artist.lowercase().contains(names))
            if (ourArtist.isNotEmpty() && !artistOk) continue
            if (delta < bestDelta) {
                bestDelta = delta
                bestId = s.optLong("id")
            }
        }
        return bestId
    }

    private fun httpGet(url: String, extra: Map<String, String> = emptyMap()): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", USER_AGENT) // lrclib asks clients to identify themselves
            extra.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        null
    }

    private val TAG_RE = Regex("""\[(\d{2}):(\d{2}(?:\.\d{1,3})?)]""")

    /**
     * NetEase prefixes its LRC with timed credit lines — `作曲 : Name` (composer),
     * `作词 : Name` (lyricist) — which would otherwise render as the song's opening
     * words. Two or more CJK characters followed by a colon identifies them without
     * a keyword list; English lyrics never take that shape.
     */
    private val CREDIT_RE = Regex("""[一-鿿]{2,}\s*[:：]""")

    private fun parseLrc(lrc: String): List<Line> {
        val lines = mutableListOf<Line>()
        lrc.lineSequence().forEach { raw ->
            val tags = TAG_RE.findAll(raw).toList()
            if (tags.isEmpty()) return@forEach
            val text = raw.substring(tags.last().range.last + 1).trim()
            if (CREDIT_RE.containsMatchIn(text)) return@forEach
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
    private const val NETEASE = "https://music.163.com/api"
    // NetEase's web endpoint rejects requests without a matching Referer, and a
    // browser UA keeps it from treating us as a bot.
    private val NETEASE_HEADERS = mapOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to "Mozilla/5.0",
    )
    private const val USER_AGENT = "LenovoDock/1.0 (https://github.com/lenovodock)"
    // Separate bounds because the two providers face different failure modes.
    // lrclib's results are already scoped to one title+artist and we take the
    // closest, so a wider bound only matters when nothing is near. NetEase's search
    // returns sped-up edits and piano covers of the same title, where duration is
    // the main thing distinguishing them — so it stays tight.
    private const val LRCLIB_TOLERANCE_SEC = 5L
    private const val NETEASE_TOLERANCE_SEC = 3L
}