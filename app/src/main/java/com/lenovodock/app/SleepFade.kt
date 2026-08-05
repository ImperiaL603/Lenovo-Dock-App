package com.lenovodock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

/**
 * Winds the music down to silence over a set window AFTER the sleep deadline, then
 * pauses and puts the volume back where it was.
 *
 * So "Sleep 30 min" with a 5 min fade means: full volume for 30 minutes, fading for
 * the next 5, silent and paused at 35. The sleep interval is when the wind-down
 * starts, not when the music stops.
 *
 * EACH STEP IS ITS OWN ALARM, chained — one fires, drops the volume a notch and
 * schedules the next. A Handler loop would be simpler and would die with the
 * process, which is precisely the state the dock is in once you have fallen asleep;
 * alarms outlive it. The step count is the volume index itself (typically 15 on this
 * device), so a 5-minute fade is about one alarm every 20 seconds, not a busy timer.
 *
 * Assumes mains power, which a dock has: Doze throttles setExactAndAllowWhileIdle to
 * roughly once every 9 minutes, and Doze only engages on battery.
 */
object SleepFade {

    private const val TAG = "LenovoDock"
    private const val PREFS = "lenovodock_alarms" // shares AlarmStore's file
    private const val KEY_MINUTES = "sleep_fade_minutes"
    private const val KEY_ORIGINAL = "sleep_fade_volume"
    private const val KEY_INTERVAL = "sleep_fade_interval"
    private const val REQUEST_CODE = 9702 // distinct from SleepReceiver's 9701
    const val ACTION_STEP = "com.lenovodock.app.FADE_STEP"

    /**
     * The fade stops here rather than at 0 and lets the pause finish the job.
     * Driving a stream to index 0 counts as muting it on Android N and up, which
     * wants Do-Not-Disturb access; one notch is inaudible at dock distance anyway,
     * and a beat later it is paused outright.
     */
    private const val FLOOR = 1

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun audio(context: Context) =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun stepIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, SleepReceiver::class.java).setAction(ACTION_STEP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 0 means no fade — the sleep timer stops the music outright, as it always did. */
    fun minutes(context: Context): Int = prefs(context).getInt(KEY_MINUTES, 0)

    fun setMinutes(context: Context, m: Int) {
        prefs(context).edit().putInt(KEY_MINUTES, m.coerceAtLeast(0)).apply()
        Log.d(TAG, "fade: length set to ${m}min")
    }

    /**
     * Called when the sleep deadline fires. Returns false when there is nothing to
     * fade — no window set, or the volume is already at the floor — leaving the
     * caller to stop the music the old way.
     */
    fun begin(context: Context): Boolean {
        val fadeMs = minutes(context) * 60_000L
        val start = audio(context).getStreamVolume(AudioManager.STREAM_MUSIC)
        val steps = start - FLOOR
        if (fadeMs <= 0 || steps <= 0) return false
        prefs(context).edit()
            .putInt(KEY_ORIGINAL, start)
            .putLong(KEY_INTERVAL, fadeMs / steps)
            .apply()
        Log.d(TAG, "fade: begin at volume $start, $steps steps over ${minutes(context)}min")
        schedule(context, fadeMs / steps)
        return true
    }

    /** One notch down. The volume is re-read rather than counted, so a nudge of the
     *  hardware keys during the fade is respected instead of being overwritten. */
    fun step(context: Context) {
        val am = audio(context)
        val next = am.getStreamVolume(AudioManager.STREAM_MUSIC) - 1
        am.setStreamVolume(AudioManager.STREAM_MUSIC, next.coerceAtLeast(FLOOR), 0)
        if (next > FLOOR) {
            schedule(context, prefs(context).getLong(KEY_INTERVAL, 0L))
        } else {
            Log.d(TAG, "fade: floor reached — pausing")
            MediaListenerService.pauseSpotify(context)
            restore(context)
        }
    }

    /**
     * Cancels an in-flight fade and hands the volume back. Safe to call when no fade
     * is running, which is why arming, disarming and finishing can all just call it.
     */
    fun abort(context: Context) {
        alarmManager(context).cancel(stepIntent(context))
        restore(context)
    }

    private fun schedule(context: Context, intervalMs: Long) {
        alarmManager(context).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMs, stepIntent(context)
        )
    }

    /**
     * Putting the volume back is the last thing that happens, and the stored value is
     * cleared with it — so a second call cannot re-raise the volume later, and a fade
     * interrupted by the process dying is repaired the next time anything arms or
     * cancels the timer.
     */
    private fun restore(context: Context) {
        val original = prefs(context).getInt(KEY_ORIGINAL, -1)
        prefs(context).edit().remove(KEY_ORIGINAL).remove(KEY_INTERVAL).apply()
        if (original < 0) return
        audio(context).setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
        Log.d(TAG, "fade: volume restored to $original")
    }
}
