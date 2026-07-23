package com.lenovodock.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fetches synced lyrics from lrclib.net for the current track and hands them
 * to the WebView as a flat, time-ordered list. One network fetch per distinct
 * track (title+artist+album) per process lifetime — results are cached in
 * memory so replaying a song doesn't re-hit the network. An empty list is a
 * valid, cached result meaning "lrclib has no lyrics for this track."
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
        if (title.isBlank() || artist.isBlank()) return
        val key = "$artist|$title|$album"
        if (key == lastKey) return
        lastKey = key

        cache[key]?.let { deliver(it); return }

        io.execute {
            val lines = fetch(title, artist, album, durationMs / 1000)
            cache[key] = lines
            // Guard against a track change that happened while this fetch was in flight.
            if (key == lastKey) deliver(lines)
        }
    }

    private fun deliver(lines: List<Line>) {
        current = lines
        main.post { observer?.invoke(lines) }
    }

    private fun fetch(title: String, artist: String, album: String, durationSec: Long): List<Line> {
        return try {
            val url = "https://lrclib.net/api/get" +
                "?track_name=${enc(title)}" +
                "&artist_name=${enc(artist)}" +
                "&album_name=${enc(album)}" +
                "&duration=$durationSec"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            val synced = JSONObject(body).optString("syncedLyrics", "")
            if (synced.isBlank()) emptyList() else parseLrc(synced)
        } catch (e: Exception) {
            emptyList()
        }
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
}