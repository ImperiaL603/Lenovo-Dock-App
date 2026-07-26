package com.lenovodock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reschedules alarms/timers after a device reboot (AlarmManager entries don't survive reboot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AlarmStore.loadAlarms(context).forEach { AlarmScheduler.scheduleAlarm(context, it) }
        val now = System.currentTimeMillis()
        AlarmStore.loadTimers(context).filter { it.endEpochMillis > now }
            .forEach { AlarmScheduler.scheduleTimer(context, it) }
    }
}