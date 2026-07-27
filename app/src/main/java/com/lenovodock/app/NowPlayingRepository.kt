package com.lenovodock.app

import android.media.session.MediaController
import android.os.Handler
import android.os.Looper

/**
 * In-process bridge between the media-session reader (MediaListenerService) and
 * the WebView host (MainActivity): playback state flows out, transport commands
 * flow back in. Both live in the same process, so a single main-thread observer
 * plus a volatile snapshot is enough. Updates are always delivered on the main
 * thread (WebView.evaluateJavascript requires it).
 */
object NowPlayingRepository {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var current: NowPlaying? = null
        private set

    private var observer: ((NowPlaying?) -> Unit)? = null

    fun setObserver(o: ((NowPlaying?) -> Unit)?) {
        observer = o
        o?.let { obs -> main.post { obs(current) } }
    }

    fun update(np: NowPlaying?) {
        current = np
        val o = observer ?: return
        main.post { o(np) }
    }

    @Volatile
    private var transport: MediaController.TransportControls? = null

    /**
     * Held only while a Spotify session is bound. MediaListenerService clears it
     * when the session goes away, so we never dispatch to a dead controller.
     */
    fun setTransport(controls: MediaController.TransportControls?) {
        transport = controls
    }

    fun togglePlayPause() = dispatch { if (current?.playing == true) it.pause() else it.play() }

    fun skipToNext() = dispatch { it.skipToNext() }

    fun skipToPrevious() = dispatch { it.skipToPrevious() }

    /**
     * Commands arrive on the WebView's JS-bridge thread, not the main thread, so
     * they hop to main — every interaction with the session then happens on one
     * thread, and `current` is read at dispatch time rather than tap time.
     */
    private fun dispatch(action: (MediaController.TransportControls) -> Unit) {
        val controls = transport ?: return
        main.post { action(controls) }
    }
}
