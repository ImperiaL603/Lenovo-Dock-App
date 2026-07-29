package com.lenovodock.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray

/** SharedPreferences-backed JSON storage for alarms and timers. */
object AlarmStore {
    private const val PREFS = "lenovodock_alarms"
    private const val KEY_ALARMS = "alarms"
    private const val KEY_TIMERS = "timers"

    private val main = Handler(Looper.getMainLooper())
    private var alarmObserver: (() -> Unit)? = null

    /**
     * Set by MainActivity so the clock face can re-read when something other than the
     * page changed the alarms — RingReceiver dropping a one-shot it has just rung, for
     * instance. Nothing needed this while the alarm list only existed inside a panel
     * the page itself owned and mutated.
     */
    fun setAlarmObserver(o: (() -> Unit)?) {
        alarmObserver = o
    }

    /**
     * Reached through saveAlarms for anything that changes the set, and called
     * directly by RingReceiver when a repeating alarm reschedules: that writes
     * nothing, but the fire time the clock face is displaying has still moved.
     */
    fun notifyAlarmsChanged() {
        // Receivers are already on the main thread; the bridge's add/delete are not.
        main.post { alarmObserver?.invoke() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAlarms(context: Context): MutableList<Alarm> {
        val arr = JSONArray(prefs(context).getString(KEY_ALARMS, "[]") ?: "[]")
        return (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    fun saveAlarms(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray(); alarms.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_ALARMS, arr.toString()).apply()
        notifyAlarmsChanged()
    }

    fun loadTimers(context: Context): MutableList<TimerItem> {
        val arr = JSONArray(prefs(context).getString(KEY_TIMERS, "[]") ?: "[]")
        return (0 until arr.length()).map { TimerItem.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    fun saveTimers(context: Context, timers: List<TimerItem>) {
        val arr = JSONArray(); timers.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_TIMERS, arr.toString()).apply()
    }
}