package com.lenovodock.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The network half of the lyrics lookup: every way we know of asking a third party
 * for one track's LRC. Split out of LyricsRepository, which had grown to hold both
 * this and the state machine that decides when to ask and what to do with the answer.
 *
 * Each entry point returns a raw lyric body or null, and null always means the same
 * thing — "this provider has nothing for us". None of them parse, cache, or decide
 * what happens next; that is the repository's job, and keeping it there is what lets
 * it try the next provider when a body turns out to be unusable.
 *
 * The passes are ordered cheapest and most-certain first. lrclib's /api/get is one
 * small response but matches on album name, which for a single release is often the
 * untimed record; /api/search sees every record for the song; editMatch and NetEase
 * exist for the tracks the first two structurally cannot find.
 */
object LyricsProviders {

    /**
     * Cheap path: one small response. Null both when lrclib has no matching record
     * and when the record it matched carries unsynced lyrics only — to us those are
     * the same outcome, and treating the second as "no lyrics" is what used to hide
     * lyrics that lrclib demonstrably had under a different album name.
     */
    fun exactMatch(
        title: String,
        artist: String,
        album: String,
        durationSec: Long,
        field: String = SYNCED,
    ): String? {
        val url = "$API/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
            "&album_name=${enc(album)}&duration=$durationSec"
        val body = httpGet(url)
        if (body == null) {
            Log.d(TAG, "exact: no record -> $url")
            return null
        }
        val o = JSONObject(body)
        val found = o.optLyrics(field)
        Log.d(TAG, "exact($field): id=${o.optInt("id")} dur=${o.optDouble("duration")} " +
            "album='${o.optString("albumName")}' hit=${found != null}")
        return found
    }

    /**
     * Fallback: consider every record for this song and take the timed one whose
     * duration is closest to what Spotify reports.
     *
     * Two passes, because Spotify names every credited artist ("Shiloh Dynasty,
     * VAL, Zuriel") while lrclib indexes most of those tracks under the lead artist
     * alone and returns an empty array for the joined string.
     */
    fun searchMatch(
        title: String,
        artist: String,
        durationSec: Long,
        field: String = SYNCED,
    ): String? {
        searchOnce(title, artist, durationSec, LRCLIB_TOLERANCE_SEC, field)?.let { return it }
        val lead = artist.substringBefore(',').trim()
        if (lead == artist || lead.isEmpty()) return null
        Log.d(TAG, "search: retrying with lead artist '$lead'")
        return searchOnce(title, lead, durationSec, LRCLIB_TOLERANCE_SEC, field)
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
    fun editMatch(title: String, durationSec: Long): String? {
        val suffix = EDIT_SUFFIX_RE.find(title) ?: return null
        val bare = title.removeRange(suffix.range).trim()
        // No duration means nothing to match on once the artist is gone.
        if (bare.isEmpty() || durationSec <= 0) return null
        Log.d(TAG, "edit: '$title' -> title-only search for '$bare'")
        return searchOnce(bare, null, durationSec, EDIT_TOLERANCE_SEC)
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
    fun neteaseMatch(title: String, artist: String, durationSec: Long): String? {
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
     * Search results carry junk entries (a 3-second "record" for a 3-minute song),
     * so the duration bound is what keeps those out — and what makes this safe to
     * pick from automatically.
     *
     * A null artist searches on title alone; only editMatch does that, and only
     * after checking the duration it leaves as the sole discriminator.
     */
    private fun searchOnce(
        title: String,
        artist: String?,
        durationSec: Long,
        toleranceSec: Long,
        field: String = SYNCED,
    ): String? {
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
            val synced = o.optLyrics(field) ?: continue
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
        Log.d(TAG, "search('${artist ?: "title-only"}' $field): ${results.length()} records, $timed with -> " +
            if (best != null) "chose id=$bestId delta=${bestDelta}s"
            else "none within ${toleranceSec}s of ${durationSec}s")
        return best
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

    /**
     * lrclib sends `"syncedLyrics": null` for a record with no timings, and NetEase
     * does the same for `lrc.lyric`. Android's JSONObject.optString returns the
     * four-character string "null" for a JSON null — the reference org.json returns
     * the fallback instead, so this only misbehaves on the device and never in a
     * desktop replay of the same request.
     *
     * Untreated, "null" is non-blank, so it passes as lyrics: exactMatch returns it,
     * the repository therefore never tries search or NetEase, parseLrc finds no
     * timestamps in it, and the track renders "No lyrics found" — the exact symptom
     * on "Love Like Kids" (GRAHAM), whose exact-duration record is the untimed one.
     */
    private fun JSONObject.optLyrics(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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

    /** Shared with LyricsRepository so `adb logcat -s LenovoDock` shows one track's
     *  provider attempts and the parse that followed them interleaved. */
    private const val TAG = "LenovoDock"
    private const val API = "https://lrclib.net/api"
    private const val NETEASE = "https://music.163.com/api"

    // The two lyric fields on an lrclib record. Every lookup runs for one of them,
    // which is why the passes take the field rather than duplicating URL building.
    // SYNCED is every pass's default, so only PLAIN is ever named from outside.
    private const val SYNCED = "syncedLyrics"
    const val PLAIN = "plainLyrics"

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
}
