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
    val art: String?,          // https or file:// album-art URL, or null when there is no art
    val playlistName: String?, // set only for Spotify playing from a playlist
    val isAd: Boolean,         // set by any player that flags ad breaks in its session
    val source: String,        // package name of the player this came from
) {
    private val hasArt: Boolean get() = art != null

    val isSpotify: Boolean get() = source == SPOTIFY_PKG

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
        // The page needs this to tell an ad break apart from a track we simply
        // found no lyrics for — both arrive as an empty lyric list.
        put("ad", isAd)
    }.toString()

    companion object {
        const val SPOTIFY_PKG = "com.spotify.music"
    }
}
