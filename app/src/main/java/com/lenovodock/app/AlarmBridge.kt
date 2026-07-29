package com.lenovodock.app

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/** JS bridge exposed to the WebView as `AndroidAlarms`. */
class AlarmBridge(private val context: Context) {

    /**
     * The page needs each alarm's fire time to pick the next one and put a day on it,
     * but the store must not carry it: it is derived from hour/minute/days and goes
     * stale the moment one rings. AlarmStore.saveAlarms persists whatever toJson()
     * returns, so the field is added here, on the way out to JS, and nowhere else.
     */
    private fun Alarm.toPageJson(): JSONObject =
        toJson().put("nextTriggerMillis", AlarmScheduler.nextTriggerMillis(this))

    @JavascriptInterface
    fun listAlarms(): String {
        val arr = JSONArray(); AlarmStore.loadAlarms(context).forEach { arr.put(it.toPageJson()) }
        return arr.toString()
    }

    @JavascriptInterface
    fun listTimers(): String {
        val arr = JSONArray(); AlarmStore.loadTimers(context).forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /** json: {"hour":7,"minute":30,"days":[1,3,5],"label":"Wake up"} */
    @JavascriptInterface
    fun addAlarm(json: String): String {
        val o = JSONObject(json)
        val daysArr = o.optJSONArray("days") ?: JSONArray()
        val days = (0 until daysArr.length()).map { daysArr.getInt(it) }.toSet()
        val alarm = Alarm(hour = o.getInt("hour"), minute = o.getInt("minute"), days = days, label = o.optString("label", ""))
        val alarms = AlarmStore.loadAlarms(context)
        alarms.add(alarm); AlarmStore.saveAlarms(context, alarms)
        AlarmScheduler.scheduleAlarm(context, alarm)
        return alarm.toPageJson().toString()
    }

    @JavascriptInterface
    fun deleteAlarm(id: String) {
        val alarms = AlarmStore.loadAlarms(context)
        alarms.removeAll { it.id == id }
        AlarmStore.saveAlarms(context, alarms)
        AlarmScheduler.cancelAlarm(context, id)
    }

    /** json: {"durationSeconds":600,"label":"Pasta"} */
    @JavascriptInterface
    fun addTimer(json: String): String {
        val o = JSONObject(json)
        val duration = o.getLong("durationSeconds")
        val timer = TimerItem(label = o.optString("label", ""), durationSeconds = duration,
            endEpochMillis = System.currentTimeMillis() + duration * 1000)
        val timers = AlarmStore.loadTimers(context)
        timers.add(timer); AlarmStore.saveTimers(context, timers)
        AlarmScheduler.scheduleTimer(context, timer)
        return timer.toJson().toString()
    }

    @JavascriptInterface
    fun cancelTimer(id: String) {
        val timers = AlarmStore.loadTimers(context)
        timers.removeAll { it.id == id }
        AlarmStore.saveTimers(context, timers)
        AlarmScheduler.cancelTimer(context, id)
    }

    companion object { const val NAME = "AndroidAlarms" }
}