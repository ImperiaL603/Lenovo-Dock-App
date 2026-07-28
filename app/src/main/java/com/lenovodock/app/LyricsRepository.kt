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

    // Read and written from the media-session thread, the io executor and the retry
    // posted to main, so neither of these can be a plain field.
    @Volatile
    private var lastKey: String? = null

    /** The one key already given a second chance, so the retry can't become a loop. */
    @Volatile
    private var retriedKey: String? = null

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
            if (key != lastKey) return@execute
            deliver(lines)
            if (lines.isEmpty()) scheduleRetry(key)
        }
    }

    /**
     * An empty result is otherwise final for the rest of the song: onTrack's lastKey
     * guard swallows every later snapshot for the same track, so a request that merely
     * failed is indistinguishable from a track lrclib genuinely lacks — and stays blank
     * until the song changes. Observed on "Love Like Kids" (GRAHAM), which lrclib holds
     * eight timed copies of at the exact duration, blank for its whole 2:15. One delayed
     * attempt separates a transient failure from a real absence.
     *
     * retriedKey bounds it to a single extra lookup per track. The retry has to clear
     * lastKey to get past that same guard, and without the bound a genuinely missing
     * track would re-queue itself every RETRY_DELAY_MS for as long as it played.
     */
    private fun scheduleRetry(key: String) {
        if (key == retriedKey) return
        retriedKey = key
        Log.d(TAG, "empty result — retrying once in ${RETRY_DELAY_MS / 1000}s")
        main.postDelayed({
            val np = NowPlayingRepository.current ?: return@postDelayed
            if (key != lastKey) return@postDelayed
            lastKey = null
            onTrack(np)
        }, RETRY_DELAY_MS)
    }

    /**
     * lrclib sends `"syncedLyrics": null` for a record with no timings, and NetEase
     * does the same for `lrc.lyric`. Android's JSONObject.optString returns the
     * four-character string "null" for a JSON null — the reference org.json returns
     * the fallback instead, so this only misbehaves on the device and never in a
     * desktop replay of the same request.
     *
     * Untreated, "null" is non-blank, so it passes as lyrics: exactMatch returns it,
     * fetch() therefore never tries search or NetEase, parseLrc finds no timestamps
     * in it, and the track renders "No lyrics found" — the exact symptom on "Love
     * Like Kids" (GRAHAM), whose exact-duration record is the untimed one.
     */
    private fun JSONObject.optLyrics(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

    private fun deliver(lines: List<Line>) {
        current = lines
        main.post { observer?.invoke(lines) }
    }

    private fun fetch(title: String, artist: String, album: String, durationSec: Long): List<Line> = try {
        val lrc = exactMatch(title, artist, album, durationSec)
            ?: searchMatch(title, artist, durationSec)
            ?: editMatch(title, durationSec)
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
        val synced = o.optLyrics("syncedLyrics")
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
        searchOnce(title, artist, durationSec, LRCLIB_TOLERANCE_SEC)?.let { return it }
        val lead = artist.substringBefore(',').trim()
        if (lead == artist || lead.isEmpty()) return null
        Log.d(TAG, "search: retrying with lead artist '$lead'")
        return searchOnce(title, lead, durationSec, LRCLIB_TOLERANCE_SEC)
    }

    /**
     * Last lrclib pass, for slowed / sped-up / nightcore re-uploads. Spotify credits
     * these to whoever published the edit ("hot girl bummer - Slowed & Reverb" by
     * "adamxyz, Mr Demon"), not the original artist lrclib files them under, so every
     * artist-bearing query above returns zero records — the artist is wrong in kind,
     * not merely over-joined, which is why the lead-artist retry can't rescue it.
     *
     * Gated on the title advertising itself as an edit, because dropping the artist
     * is only safe where the artist is known to be unreliable. Duration is then the
     * ONLY filter, so the bound is tighter than the artist-gated passes: a correctly
     * retimed edit is the record whose length matches ours, and the unslowed original
     * — half a minute shorter, timings drifting the whole way — must not qualify.
     */
    private fun editMatch(title: String, durationSec: Long): String? {
        val suffix = EDIT_SUFFIX_RE.find(title) ?: return null
        val bare = title.removeRange(suffix.range).trim()
        // No duration means nothing to match on once the artist is gone.
        if (bare.isEmpty() || durationSec <= 0) return null
        Log.d(TAG, "edit: '$title' -> title-only search for '$bare'")
        return searchOnce(bare, null, durationSec, EDIT_TOLERANCE_SEC)
    }

    /**
     * Search results carry junk entries (a 3-second "record" for a 3-minute song),
     * so the duration bound is what keeps those out — and what makes this safe to
     * pick from automatically.
     *
     * A null artist searches on title alone; only editMatch does that, and only
     * after checking the duration it leaves as the sole discriminator.
     */
    private fun searchOnce(title: String, artist: String?, durationSec: Long, toleranceSec: Long): String? {
        val body = httpGet("$API/search?track_name=${enc(title)}" +
            if (artist != null) "&artist_name=${enc(artist)}" else "")
            ?: return null
        val results = JSONArray(body)
        var best: String? = null
        var bestDelta = Long.MAX_VALUE
        var bestId = 0
        var timed = 0
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val synced = o.optLyrics("syncedLyrics") ?: continue
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
            if (delta <= toleranceSec && delta < bestDelta) {
                best = synced
                bestDelta = delta
                bestId = o.optInt("id")
            }
        }
        Log.d(TAG, "search('${artist ?: "title-only"}'): ${results.length()} records, $timed timed -> " +
            if (best != null) "chose id=$bestId delta=${bestDelta}s"
            else "none within ${toleranceSec}s of ${durationSec}s")
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
        val lrc = JSONObject(lyricBody).optJSONObject("lrc")?.optLyrics("lyric")
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

    /**
     * A version label arrives either after Spotify's " - " separator ("hot girl bummer
     * - Slowed & Reverb") or bracketed ("lucid dreams (slowed)"); both are common and
     * the suffix runs from there to the end. Only titles carrying one of these words
     * reach editMatch's artist-less search.
     *
     * The two branches differ in what they may span before the keyword: the bracketed
     * one runs to the closing bracket so an inner hyphen can't split it ("(ambient
     * version - slowed)"), while the dash one stops at the next hyphen so that only
     * the last segment of "Song - Live - Remix" is taken.
     */
    private val EDIT_SUFFIX_RE = Regex(
        """(\s-\s[^-]*|\s*[(\[][^)\]]*)\b(slowed|sped\s*up|nightcore|reverb|remix)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

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
    // editMatch drops the artist, leaving duration as the only thing standing between
    // us and another song of the same name, so it gets the tightest bound of the three.
    private const val EDIT_TOLERANCE_SEC = 3L
    // Long enough that a blip has passed, short enough to still catch most of a
    // 3-minute song's lyrics rather than arriving after the last chorus.
    private const val RETRY_DELAY_MS = 20_000L
}