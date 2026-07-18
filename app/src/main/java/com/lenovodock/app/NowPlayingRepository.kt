package com.lenovodock.app

import android.os.Handler
import android.os.Looper

/**
 * In-process bridge between the media-session reader (MediaListenerService)
 * and the WebView host (MainActivity). Both live in the same process, so a
 * single main-thread observer plus a volatile snapshot is enough. Updates are
 * always delivered on the main thread (WebView.evaluateJavascript requires it).
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
}
