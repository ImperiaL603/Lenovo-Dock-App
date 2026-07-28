package com.lenovodock.app

import org.json.JSONObject

/**
 * Immutable now-playing snapshot handed to the WebView. `positionMs` is
 * extrapolated to the moment the snapshot was built; the page interpolates
 * forward from there using its own clock and `speed`.
 */
data class NowPlaying(
    val playing: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val speed: Float,
    val art: String?,          // https album-art URL, or null when the session has no art
    val playlistName: String?, // set only when playing from a playlist
    val isAd: Boolean,         // Spotify serves ads through the same session as tracks
) {
    private val hasArt: Boolean get() = art != null

    fun toJson(): String = JSONObject().apply {
        put("playing", playing)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs)
        put("positionMs", positionMs)
        put("speed", speed.toDouble())
        put("hasArt", hasArt)
        put("art", art ?: JSONObject.NULL)
        put("playlistName", playlistName ?: JSONObject.NULL)
    }.toString()
}
