package com.lenovodock.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Decides when to look lyrics up, which answer to keep, and hands the result to the
 * WebView as a flat, time-ordered list. One network fetch per distinct track
 * (title+artist+album), cached in memory so replaying a song doesn't re-hit the
 * network. The lookups themselves live in LyricsProviders.
 *
 * No provider is authoritative. lrclib usually holds SEVERAL records per song and
 * only some carry timings; NetEase sometimes lists the exact upload and stores no
 * words at all. So firstTimed() treats every provider as a candidate and keeps the
 * first whose body survives parsing, rather than trusting whichever answered first.
 */
object LyricsRepository {

    data class Line(val timeMs: Long, val text: String)

    /**
     * `synced = false` means these came from lrclib's `plainLyrics` — the words with
     * no timings attached, which many records carry when `syncedLyrics` is null.
     * Their `timeMs` is 0 and meaningless; the page scrolls them by track progress
     * instead and shows no active line, because there is nothing to be active.
     */
    data class Lyrics(val lines: List<Line>, val synced: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<String, Lyrics>()

    @Volatile
    var current: Lyrics = Lyrics(emptyList(), true)
        private set

    private var observer: ((Lyrics) -> Unit)? = null

    // Read and written from the media-session thread, the io executor and the retry
    // posted to main, so neither of these can be a plain field.
    @Volatile
    private var lastKey: String? = null

    /** The one key already given a second chance, so the retry can't become a loop. */
    @Volatile
    private var retriedKey: String? = null

    fun setObserver(o: ((Lyrics) -> Unit)?) {
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
            deliver(Lyrics(emptyList(), true))
            return
        }
        // The exact strings Spotify gave us. Mismatches against lrclib's own
        // spelling start here, so this is the first line to read on a miss.
        Log.d(TAG, "track: title='$title' artist='$artist' album='$album' duration=${np.durationMs / 1000}s")

        cache[key]?.let { Log.d(TAG, "cache hit: ${it.lines.size} lines"); deliver(it); return }

        io.execute {
            val lyrics = fetch(title, artist, album, np.durationMs / 1000)
            // Only successes are cached. An empty result means "a timeout, a 5xx or
            // no timed record" — caching that would keep the track blank for the rest
            // of the process, so a later play gets another attempt instead.
            if (lyrics.lines.isNotEmpty()) cache[key] = lyrics
            // Guard against a track change that happened while this fetch was in flight.
            if (key != lastKey) return@execute
            deliver(lyrics)
            if (lyrics.lines.isEmpty()) scheduleRetry(key)
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

    private fun deliver(lyrics: Lyrics) {
        current = lyrics
        main.post { observer?.invoke(lyrics) }
    }

    private fun fetch(title: String, artist: String, album: String, durationSec: Long): Lyrics = try {
        val timed = firstTimed(title, artist, album, durationSec)
        if (timed != null) Lyrics(timed, true) else plainFallback(title, artist, album, durationSec)
    } catch (e: Exception) {
        Log.w(TAG, "unparseable response", e)
        // A 200 carrying a body we can't parse. Third-party boundary, so worth
        // catching: without this the executor thread dies and nothing is delivered,
        // leaving the window blank rather than saying "No lyrics found".
        Lyrics(emptyList(), true)
    }

    /**
     * Asks each provider in turn and keeps the first body that actually parses to
     * timed lines.
     *
     * The parse belongs inside this loop. It used to sit after a `?:` chain of the
     * raw bodies, which committed to whichever provider answered first and only then
     * discovered whether the answer was usable — so a body that is non-blank but
     * carries no timestamp TAG_RE recognises (single-digit minutes, metadata tags
     * alone, a NetEase entry that is nothing but credit lines) took the three
     * providers behind it down with it and dropped the track to plain lyrics.
     */
    private fun firstTimed(title: String, artist: String, album: String, durationSec: Long): List<Line>? {
        val providers = listOf<Pair<String, () -> String?>>(
            "exact" to { LyricsProviders.exactMatch(title, artist, album, durationSec) },
            "search" to { LyricsProviders.searchMatch(title, artist, durationSec) },
            "edit" to { LyricsProviders.editMatch(title, durationSec) },
            "netease" to { LyricsProviders.neteaseMatch(title, artist, durationSec) },
        )
        for ((name, lookup) in providers) {
            val body = lookup() ?: continue
            val lines = parseLrc(body)
            // A count far below the raw line count means the file's timestamps are in
            // a format TAG_RE rejects; zero means none of them were readable at all.
            Log.d(TAG, "result($name): ${lines.size} lines parsed " +
                "from ${body.count { it == '\n' } + 1} raw lines")
            if (lines.isNotEmpty()) return lines
        }
        Log.d(TAG, "result: no timed source")
        return null
    }

    /**
     * Reached only when nothing timed exists anywhere. lrclib frequently holds a
     * track's words with nobody's timings attached — a record whose `syncedLyrics`
     * is null usually still has `plainLyrics` set, and some songs have a dozen such
     * records and not one synced ("Story of a Warrior" by John Michael Howell). Those
     * used to render "No lyrics found" while the full text sat in a response we had
     * already parsed and discarded.
     *
     * Same two passes as the timed lookup, just reading the other field, so a
     * multi-artist string still gets its lead-artist retry. Costs extra requests only
     * on tracks that were going to show nothing at all.
     *
     * No per-provider parse loop here, unlike firstTimed: parsePlain drops nothing but
     * blank lines, so a body that got this far cannot come back empty.
     */
    private fun plainFallback(title: String, artist: String, album: String, durationSec: Long): Lyrics {
        val plain = LyricsProviders.exactMatch(title, artist, album, durationSec, LyricsProviders.PLAIN)
            ?: LyricsProviders.searchMatch(title, artist, durationSec, LyricsProviders.PLAIN)
        val lines = if (plain == null) emptyList() else parsePlain(plain)
        Log.d(TAG, "plain: ${lines.size} lines" + if (plain == null) " (no source)" else "")
        return Lyrics(lines, false)
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

    /** Plain lyrics are one line of text per line, no timestamps to read. */
    private fun parsePlain(text: String): List<Line> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Line(0L, it) }
            .toList()

    fun toJson(lyrics: Lyrics): String {
        val arr = JSONArray()
        lyrics.lines.forEach {
            arr.put(JSONObject().apply {
                put("t", it.timeMs)
                put("text", it.text)
            })
        }
        return JSONObject().apply {
            put("synced", lyrics.synced)
            put("lines", arr)
        }.toString()
    }

    /** Shared with MediaListenerService and LyricsProviders so `adb logcat -s LenovoDock`
     *  shows the playback snapshot and the lyrics lookup for a track interleaved. */
    private const val TAG = "LenovoDock"
    // Long enough that a blip has passed, short enough to still catch most of a
    // 3-minute song's lyrics rather than arriving after the last chorus.
    private const val RETRY_DELAY_MS = 20_000L
}
