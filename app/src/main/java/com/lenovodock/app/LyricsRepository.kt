package com.lenovodock.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Decides when to look lyrics up, which answer to keep, and hands the result to the
 * WebView as a flat, time-ordered list. The lookups themselves live in
 * LyricsProviders; lyrics the owner has chosen to keep live in SavedLyrics.
 *
 * ORDER OF LOOKUP for one track: the in-memory cache, then a SAVED copy on disk, then
 * the network. The saved copy comes before the network on purpose: it works with no
 * connection at all, and it spares three or four requests per play.
 *
 * The in-memory cache is capped. This object lives as long as the process, and on an
 * always-on dock that can be weeks, so an uncapped map just grows by one entry per
 * distinct song for as long as it runs.
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

    /**
     * Least-recently-used: the access-ordered LinkedHashMap drops its eldest entry
     * once the cap is passed. The synchronized wrapper is not optional — an
     * access-ordered map changes its own structure on every read, and this is
     * touched from the media-session thread and the io executor both.
     */
    private val cache: MutableMap<String, Lyrics> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Lyrics>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>?): Boolean =
                size > MAX_CACHED_TRACKS
        }
    )

    @Volatile
    var current: Lyrics = Lyrics(emptyList(), true)
        private set

    /** The track key `current` was delivered for. Saving checks it against trackKey,
     *  so a tap in the instant after a track change cannot file the PREVIOUS song's
     *  lyrics under the new song's name. */
    @Volatile
    private var currentFor: String? = null

    private var observer: ((Lyrics) -> Unit)? = null

    // Read and written from the media-session thread, the io executor and the retry
    // posted to main, so none of these can be a plain field.
    @Volatile
    private var lastKey: String? = null

    /** The track being followed, as the key and the cleaned strings it was built from.
     *  Unlike lastKey these are never cleared by the retry, so a save always has them. */
    @Volatile private var trackKey: String? = null
    @Volatile private var trackTitle: String = ""
    @Volatile private var trackArtist: String = ""
    @Volatile private var trackAlbum: String = ""

    /** The one key already given a second chance, so the retry can't become a loop. */
    @Volatile
    private var retriedKey: String? = null

    /** Kept for the delayed retry, which has no caller to borrow a Context from. */
    @Volatile
    private var appContext: Context? = null

    fun setObserver(o: ((Lyrics) -> Unit)?) {
        observer = o
        o?.let { obs -> main.post { obs(current) } }
    }

    /** Call whenever a NowPlaying snapshot arrives. No-ops if the track hasn't changed. */
    fun onTrack(context: Context, np: NowPlaying) {
        val ctx = context.applicationContext
        appContext = ctx
        // Spotify's metadata is already clean, and its " - " titles are real version
        // labels that editMatch relies on, so only other players are cleaned.
        val (title, artist) =
            if (np.isSpotify) np.title to np.artist else TitleCleaner.clean(np.title, np.artist)
        val album = np.album
        // The artist may be blank (offline files with no tags): the providers then
        // look up by title and duration alone.
        if (title.isBlank()) {
            Log.d(TAG, "skip: blank title (artist='$artist')")
            return
        }
        val key = "$artist|$title|$album"
        if (key == lastKey) return
        // Set before the ad check on purpose: an ad still has to claim lastKey, or
        // the song resuming afterwards would match the pre-ad key, be treated as
        // unchanged, and never get its lyrics re-delivered.
        lastKey = key
        trackKey = key
        trackTitle = title
        trackArtist = artist
        trackAlbum = album
        if (np.isAd) {
            Log.d(TAG, "ad break — no lookup ('$title' / '$artist')")
            deliver(Lyrics(emptyList(), true), key)
            return
        }
        // Podcasts, audiobooks and mixes: nothing to find, and not worth the requests.
        if (np.durationMs > MAX_LOOKUP_MS) {
            Log.d(TAG, "too long for a song (${np.durationMs / 60_000}min) — no lookup")
            deliver(Lyrics(emptyList(), true), key)
            return
        }
        // The exact strings the player gave us. Mismatches against lrclib's own
        // spelling start here, so this is the first line to read on a miss.
        Log.d(TAG, "track: title='$title' artist='$artist' album='$album' duration=${np.durationMs / 1000}s")

        cache[key]?.let { Log.d(TAG, "cache hit: ${it.lines.size} lines"); deliver(it, key); return }

        io.execute {
            // A saved copy is read off the main thread (it is a file) and tried before
            // any request, so a song kept earlier plays with the network down.
            val saved = SavedLyrics.load(ctx, key)
            if (saved != null) {
                Log.d(TAG, "saved copy: ${saved.lines.size} lines — no lookup")
                cache[key] = saved
                if (key == lastKey) deliver(saved, key)
                return@execute
            }

            val lyrics = fetch(title, artist, album, np.durationMs / 1000)
            // Only successes are cached. An empty result means "a timeout, a 5xx or
            // no timed record" — caching that would keep the track blank for the rest
            // of the process, so a later play gets another attempt instead.
            if (lyrics.lines.isNotEmpty()) cache[key] = lyrics
            // Guard against a track change that happened while this fetch was in flight.
            if (key != lastKey) return@execute
            deliver(lyrics, key)
            if (lyrics.lines.isEmpty()) scheduleRetry(key)
        }
    }

    /**
     * Writes the lyrics on screen to disk. False when there is nothing to save, or
     * when what is in `current` belongs to a different track than the one now playing.
     */
    fun saveCurrent(context: Context): Boolean {
        val key = trackKey ?: return false
        val lyrics = current
        if (currentFor != key || lyrics.lines.isEmpty()) return false
        return SavedLyrics.save(context, key, trackTitle, trackArtist, trackAlbum, lyrics)
    }

    /** Read from disk each time, so a delete made in Settings is reflected at once. */
    fun isCurrentSaved(context: Context): Boolean {
        val key = trackKey ?: return false
        return currentFor == key && SavedLyrics.exists(context, key)
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
            val ctx = appContext ?: return@postDelayed
            val np = NowPlayingRepository.current ?: return@postDelayed
            if (key != lastKey) return@postDelayed
            lastKey = null
            onTrack(ctx, np)
        }, RETRY_DELAY_MS)
    }

    private fun deliver(lyrics: Lyrics, key: String) {
        current = lyrics
        currentFor = key
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
    private const val MAX_LOOKUP_MS = 15 * 60_000L
    // Songs kept in memory. Replays inside this window skip even the disk read.
    private const val MAX_CACHED_TRACKS = 50
}
