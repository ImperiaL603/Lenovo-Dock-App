package com.lenovodock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Sleep timer: pauses Spotify at a wall-clock deadline set when the user arms it.
 *
 * Scheduled through AlarmManager rather than a JS timer because the whole point
 * is to stop the music after you have fallen asleep — by which time the dock may
 * be backgrounded, throttled or killed, and a setTimeout would be gone.
 *
 * The stored deadline IS the armed state. The settings UI reads it back on load
 * so the toggle still shows correctly after a restart, and clearing it disarms.
 * Scheduling and receiving live together here so the two can't drift apart.
 */
class SleepReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        clearDeadline(context)
        MediaListenerService.pauseSpotify(context)
    }

    companion object {
        private const val TAG = "LenovoDock"
        private const val PREFS = "lenovodock_alarms" // shares AlarmStore's file
        private const val KEY_DEADLINE = "sleep_deadline"
        private const val REQUEST_CODE = 9701

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun alarmManager(context: Context) =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, SleepReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** Epoch millis at which the music stops; 0 when disarmed or already past. */
        fun deadline(context: Context): Long {
            val at = prefs(context).getLong(KEY_DEADLINE, 0L)
            return if (at > System.currentTimeMillis()) at else 0L
        }

        /** Arming again replaces any pending alarm — FLAG_UPDATE_CURRENT reuses the
         *  same PendingIntent, so changing the interval restarts the countdown. */
        fun arm(context: Context, minutes: Int): Long {
            val at = System.currentTimeMillis() + minutes * 60_000L
            prefs(context).edit().putLong(KEY_DEADLINE, at).apply()
            alarmManager(context).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at, pendingIntent(context)
            )
            Log.d(TAG, "sleep: armed for ${minutes}min")
            return at
        }

        fun cancel(context: Context) {
            alarmManager(context).cancel(pendingIntent(context))
            clearDeadline(context)
            Log.d(TAG, "sleep: cancelled")
        }

        private fun clearDeadline(context: Context) {
            prefs(context).edit().remove(KEY_DEADLINE).apply()
        }
    }
}
