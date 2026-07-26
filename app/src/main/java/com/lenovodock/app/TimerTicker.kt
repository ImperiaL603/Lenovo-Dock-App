package com.lenovodock.app

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Pushes live timer countdowns to the WebView every second while the app is foregrounded.
 *  Completion (sound/notification) is handled separately by AlarmManager + RingService,
 *  so timers still fire even if the app is killed. */
object TimerTicker {
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ((List<TimerItem>) -> Unit)? = null
    private var appContext: Context? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            appContext?.let { ctx -> observer?.invoke(AlarmStore.loadTimers(ctx).filter { it.running }) }
            handler.postDelayed(this, 1000)
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    fun stop() = handler.removeCallbacks(tickRunnable)
    fun setObserver(cb: ((List<TimerItem>) -> Unit)?) { observer = cb }
}