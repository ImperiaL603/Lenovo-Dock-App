package com.lenovodock.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Alarm(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(), // Calendar.SUNDAY(1)..SATURDAY(7); empty = one-time
    val label: String = "",
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("hour", hour); put("minute", minute)
        put("days", JSONArray(days.toList())); put("label", label); put("enabled", enabled)
    }
    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val daysArr = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until daysArr.length()).map { daysArr.getInt(it) }.toSet()
            return Alarm(
                id = o.optString("id", UUID.randomUUID().toString()),
                hour = o.getInt("hour"), minute = o.getInt("minute"),
                days = days, label = o.optString("label", ""),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }
}

data class TimerItem(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val durationSeconds: Long,
    val endEpochMillis: Long,
    val running: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("label", label)
        put("durationSeconds", durationSeconds); put("endEpochMillis", endEpochMillis)
        put("running", running)
    }
    companion object {
        fun fromJson(o: JSONObject): TimerItem = TimerItem(
            id = o.optString("id", UUID.randomUUID().toString()),
            label = o.optString("label", ""),
            durationSeconds = o.getLong("durationSeconds"),
            endEpochMillis = o.getLong("endEpochMillis"),
            running = o.optBoolean("running", true)
        )
    }
}