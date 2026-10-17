package com.lenovodock.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Reads every active media session and pushes now-playing snapshots of ONE of them
 * to NowPlayingRepository. Lives inside the system-bound, long-lived
 * NotificationListenerService — no separate foreground service needed. The
 * notification-access grant is what unlocks MediaSessionManager here.
 *
 * WHICH PLAYER IS FOLLOWED: whichever started playing most recently. A callback is
 * registered on every session so a second player starting takes over at once. When
 * nothing is playing the current one is kept, so a paused player stays on screen
 * (and the page's own grace period decides when to go back to the clock).
 */
class MediaListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var lastLogged: String? = null

    private val controllers = HashMap<MediaSession.Token, MediaController>()
    private val callbacks = HashMap<MediaSession.Token, SessionCallback>()
    // elapsedRealtime at which each session last went from not-playing to playing.
    private val startedAt = HashMap<MediaSession.Token, Long>()
    private val wasPlaying = HashMap<MediaSession.Token, Boolean>()
    private var current: MediaController? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { bindSessions(it ?: emptyList()) }

    private inner class SessionCallback(private val c: MediaController) : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onSessionChanged(c)
        override fun onPlaybackStateChanged(state: PlaybackState?) = onSessionChanged(c)
        override fun onSessionDestroyed() = onSessionEnded(c)
    }

    override fun onListenerConnected() {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = msm
        val self = ComponentName(this, MediaListenerService::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsListener, self)
        bindSessions(msm.getActiveSessions(self))
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        detachAll()
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun bindSessions(sessions: List<MediaController>) {
        val live = sessions.associateBy { it.sessionToken }
        (controllers.keys - live.keys).toList().forEach { drop(it) }
        for ((token, c) in live) if (token !in controllers) attach(c)
        if (reselect()) publish()
    }

    private fun attach(c: MediaController) {
        val t = c.sessionToken
        controllers[t] = c
        val cb = SessionCallback(c)
        callbacks[t] = cb
        c.registerCallback(cb, handler)
        val playing = isPlaying(c)
        wasPlaying[t] = playing
        startedAt[t] = if (playing) SystemClock.elapsedRealtime() else 0L
        Log.d(TAG, "session: attached ${c.packageName} playing=$playing")
    }

    private fun drop(t: MediaSession.Token) {
        callbacks.remove(t)?.let { controllers[t]?.unregisterCallback(it) }
        controllers.remove(t)
        startedAt.remove(t)
        wasPlaying.remove(t)
        if (current?.sessionToken == t) current = null
    }

    private fun detachAll() {
        controllers.keys.toList().forEach { drop(it) }
        current = null
        NowPlayingRepository.setTransport(null)
        NowPlayingRepository.update(null)
        AdMuter.onNowPlaying(this, null)
    }

    private fun onSessionChanged(c: MediaController) {
        val t = c.sessionToken
        if (t !in controllers) return
        val playing = isPlaying(c)
        if (playing && wasPlaying[t] != true) startedAt[t] = SystemClock.elapsedRealtime()
        wasPlaying[t] = playing
        val changed = reselect()
        // Updates from a player we are not following are not worth a snapshot.
        if (changed || current?.sessionToken == t) publish()
    }

    private fun onSessionEnded(c: MediaController) {
        drop(c.sessionToken)
        if (reselect()) publish()
    }

    /**
     * Picks the session to follow. Playing sessions with metadata win, newest start
     * first; on a tie the one already followed is kept so equal candidates cannot
     * flip back and forth. With nothing playing, the current one stays. Returns
     * whether the followed session changed.
     */
    private fun reselect(): Boolean {
        val playing = controllers.values.filter { isPlaying(it) && it.metadata != null }
        val best = playing.maxByOrNull { startedAt[it.sessionToken] ?: 0L }
        val keep = current?.takeIf { cur ->
            playing.any { it.sessionToken == cur.sessionToken } &&
                (startedAt[cur.sessionToken] ?: 0L) >= (best?.let { startedAt[it.sessionToken] } ?: 0L)
        }
        val next = keep ?: best
            ?: current?.takeIf { it.sessionToken in controllers }
            ?: controllers.values.filter { it.metadata != null }
                .maxByOrNull { startedAt[it.sessionToken] ?: 0L }
        if (next?.sessionToken == current?.sessionToken) {
            current = next
            return false
        }
        current = next
        NowPlayingRepository.setTransport(next?.transportControls)
        Log.d(TAG, "session: following ${next?.packageName}")
        return true
    }

    private fun publish() {
        val np = current?.let(::snapshot)
        NowPlayingRepository.update(np)
        logSnapshot(np)
        np?.let { LyricsRepository.onTrack(this, it) }
        ArtPalette.onArt(np?.art)
        // Takes the null too: a vanished session has to release the mute, so this
        // cannot sit inside the let above.
        AdMuter.onNowPlaying(this, np)
    }

    /**
     * Logged only when a field that matters changes — periodic position updates
     * would otherwise drown the log. `duration=0` or a `playing=false` that
     * never flips back are what freeze the lyrics window mid-song, so both are here.
     */
    private fun logSnapshot(np: NowPlaying?) {
        val signature = np?.let { "${it.source}|${it.title}|${it.playing}|${it.durationMs}" } ?: "gone"
        if (signature == lastLogged) return
        lastLogged = signature
        if (np == null) {
            Log.d(TAG, "playback: session gone")
        } else {
            Log.d(TAG, "playback: src=${np.source} playing=${np.playing} pos=${np.positionMs}ms " +
                "duration=${np.durationMs}ms speed=${np.speed} art=${np.art != null} ad=${np.isAd}")
        }
    }

    private fun snapshot(c: MediaController): NowPlaying? {
        val md = c.metadata ?: return null
        val ps = c.playbackState ?: return null
        val pkg = c.packageName
        val playing = ps.state == PlaybackState.STATE_PLAYING
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val drift = if (playing)
            ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        else 0L
        val position = (ps.position + drift).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        val title = (md.getText(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.toString().orEmpty()
        val artist = (md.getText(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.toString().orEmpty()
        val album = md.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString().orEmpty()
        val speed = if (playing) ps.playbackSpeed else 0f
        // An https URL where the player gives one; otherwise ArtCache has saved the
        // player's bitmap or content URI to a file and this is a file:// URL.
        val art = ArtCache.urlFor(this, md, "$pkg|$title|$artist|$album")
        val isSpotify = pkg == NowPlaying.SPOTIFY_PKG
        // Spotify-only: no other player publishes these keys.
        val playlist = if (isSpotify && md.getString(SPOTIFY_CONTEXT_URI).orEmpty().contains(":playlist:"))
            md.getString(SPOTIFY_CONTEXT_TITLE)?.takeIf { it.isNotBlank() } else null
        // Read for every player: any app that sets the flag gets muted, and an app
        // that doesn't simply reads as 0.
        val isAd = md.getLong(KEY_ADVERTISEMENT) != 0L
        return NowPlaying(playing, title, artist, album, duration, position, speed, art, playlist, isAd, pkg)
    }

    companion object {
        /**
         * Pauses every player that is currently playing, without depending on this
         * service being bound. The sleep alarm can fire into a cold process where
         * NowPlayingRepository holds no controller yet, so the sessions are looked
         * up fresh each time.
         *
         * getActiveSessions throws if the notification-listener grant has been
         * revoked — a real possibility at any time, hence the catch.
         */
        fun pauseActivePlayers(context: Context) {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val self = ComponentName(context, MediaListenerService::class.java)
            val sessions = try {
                msm.getActiveSessions(self)
            } catch (e: SecurityException) {
                Log.w(TAG, "sleep: no notification-listener access", e)
                emptyList()
            }
            val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            playing.forEach { it.transportControls.pause() }
            Log.d(TAG, "sleep: pause dispatched to ${playing.size} player(s): " +
                playing.joinToString { it.packageName })
        }

        private const val TAG = "LenovoDock" // same tag as LyricsRepository, one logcat filter
        // Declared as a literal because this key only exists as a constant on
        // MediaMetadataCompat (androidx.media), which this app doesn't depend on —
        // the framework's android.media.MediaMetadata has no such field. The key
        // string is the same one MediaMetadataCompat uses, and is a Long.
        private const val KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"
        private const val SPOTIFY_CONTEXT_URI = "com.spotify.music.extra.CONTEXT_URI"
        private const val SPOTIFY_CONTEXT_TITLE = "com.spotify.music.extra.CONTEXT_TITLE"
    }
}
