package com.lenovodock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Schedules exact "ring" events (alarm fires / timer completes) via AlarmManager. */
object AlarmScheduler {
    const val EXTRA_ITEM_ID = "item_id"
    const val EXTRA_ITEM_TYPE = "item_type"
    const val TYPE_ALARM = "alarm"
    const val TYPE_TIMER = "timer"

    private fun alarmManager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context, id: String, type: String): PendingIntent {
        val intent = Intent(context, RingReceiver::class.java).apply {
            putExtra(EXTRA_ITEM_ID, id); putExtra(EXTRA_ITEM_TYPE, type)
        }
        return PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleAlarm(context: Context, alarm: Alarm) {
        alarmManager(context).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, nextTriggerMillis(alarm), pendingIntent(context, alarm.id, TYPE_ALARM)
        )
    }

    fun cancelAlarm(context: Context, alarmId: String) {
        alarmManager(context).cancel(pendingIntent(context, alarmId, TYPE_ALARM))
    }

    fun scheduleTimer(context: Context, timer: TimerItem) {
        alarmManager(context).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, timer.endEpochMillis, pendingIntent(context, timer.id, TYPE_TIMER)
        )
    }

    fun cancelTimer(context: Context, timerId: String) {
        alarmManager(context).cancel(pendingIntent(context, timerId, TYPE_TIMER))
    }

    fun nextTriggerMillis(alarm: Alarm): Long {
        val now = Calendar.getInstance()
        val candidate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour); set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (alarm.days.isEmpty()) {
            if (candidate.timeInMillis <= now.timeInMillis) candidate.add(Calendar.DAY_OF_YEAR, 1)
            return candidate.timeInMillis
        }
        for (offset in 0..7) {
            val c = candidate.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, offset)
            if (alarm.days.contains(c.get(Calendar.DAY_OF_WEEK)) && c.timeInMillis > now.timeInMillis) return c.timeInMillis
        }
        return candidate.timeInMillis + AlarmManager.INTERVAL_DAY
    }
}