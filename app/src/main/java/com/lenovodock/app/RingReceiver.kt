package com.lenovodock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ITEM_ID) ?: return
        val type = intent.getStringExtra(AlarmScheduler.EXTRA_ITEM_TYPE) ?: return
        val label: String

        if (type == AlarmScheduler.TYPE_ALARM) {
            val alarms = AlarmStore.loadAlarms(context)
            val alarm = alarms.find { it.id == id }
            label = alarm?.label?.ifBlank { "Alarm" } ?: "Alarm"
            if (alarm != null) {
                if (alarm.days.isEmpty()) {
                    alarms.remove(alarm); AlarmStore.saveAlarms(context, alarms)
                } else {
                    AlarmScheduler.scheduleAlarm(context, alarm)
                    // Nothing was written, so saveAlarms' notification doesn't cover
                    // this — but the time the clock face is showing just moved on.
                    AlarmStore.notifyAlarmsChanged()
                }
            }
        } else {
            val timers = AlarmStore.loadTimers(context)
            val timer = timers.find { it.id == id }
            label = timer?.label?.ifBlank { "Timer" } ?: "Timer"
            if (timer != null) { timers.remove(timer); AlarmStore.saveTimers(context, timers) }
        }

        RingService.start(context, label)
    }
}