package com.lenovodock.app

import android.content.Context
import org.json.JSONArray

/** SharedPreferences-backed JSON storage for alarms and timers. */
object AlarmStore {
    private const val PREFS = "lenovodock_alarms"
    private const val KEY_ALARMS = "alarms"
    private const val KEY_TIMERS = "timers"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAlarms(context: Context): MutableList<Alarm> {
        val arr = JSONArray(prefs(context).getString(KEY_ALARMS, "[]") ?: "[]")
        return (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    fun saveAlarms(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray(); alarms.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_ALARMS, arr.toString()).apply()
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