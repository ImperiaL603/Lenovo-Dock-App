package com.lenovodock.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Reads Spotify's media session and pushes now-playing snapshots to
 * NowPlayingRepository. Lives inside the system-bound, long-lived
 * NotificationListenerService — no separate foreground service needed. The
 * notification-access grant is what unlocks MediaSessionManager here.
 */
class MediaListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { bindSpotify(it ?: emptyList()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = detachAndClear()
    }

    override fun onListenerConnected() {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = msm
        val self = ComponentName(this, MediaListenerService::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsListener, self)
        bindSpotify(msm.getActiveSessions(self))
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        detachAndClear()
    }

    private fun bindSpotify(sessions: List<MediaController>) {
        val next = sessions.firstOrNull { it.packageName == SPOTIFY_PKG }
        if (next?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = next
        if (next == null) {
            NowPlayingRepository.update(null)
        } else {
            next.registerCallback(callback, handler)
            publish()
        }
    }

    private fun detachAndClear() {
        controller?.unregisterCallback(callback)
        controller = null
        NowPlayingRepository.update(null)
    }

    private fun publish() = NowPlayingRepository.update(controller?.let(::snapshot))

    private fun snapshot(c: MediaController): NowPlaying? {
        val md = c.metadata ?: return null
        val ps = c.playbackState ?: return null
        val playing = ps.state == PlaybackState.STATE_PLAYING
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val drift = if (playing)
            ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        else 0L
        val position = (ps.position + drift).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        val title = md.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString().orEmpty()
        val artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString().orEmpty()
        val album = md.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString().orEmpty()
        val speed = if (playing) ps.playbackSpeed else 0f
        // Spotify's embedded art bitmap is unreliable; its https art URL is stable.
        val art = md.getString(SPOTIFY_ART_HTTPS_URI)?.takeIf { it.isNotBlank() }
        val playlist = if (md.getString(SPOTIFY_CONTEXT_URI).orEmpty().contains(":playlist:"))
            md.getString(SPOTIFY_CONTEXT_TITLE)?.takeIf { it.isNotBlank() } else null
        return NowPlaying(playing, title, artist, album, duration, position, speed, art, playlist)
    }

    companion object {
        private const val SPOTIFY_PKG = "com.spotify.music"
        private const val SPOTIFY_ART_HTTPS_URI = "com.spotify.music.extra.ART_HTTPS_URI"
        private const val SPOTIFY_CONTEXT_URI = "com.spotify.music.extra.CONTEXT_URI"
        private const val SPOTIFY_CONTEXT_TITLE = "com.spotify.music.extra.CONTEXT_TITLE"
    }
}
