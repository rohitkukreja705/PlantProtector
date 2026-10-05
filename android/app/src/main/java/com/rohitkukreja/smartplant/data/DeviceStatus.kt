package com.rohitkukreja.smartplant.data

import org.json.JSONObject

/** Live status notified by the ESP32 every ~2 s (see firmware buildStatus()). */
data class DeviceStatus(
    val moisture: Int,
    val soilRaw: Int,
    val tempC: Float?,
    val humidity: Float?,
    val lightPct: Int,
    val ldrRaw: Int,
    val sunMinutes: Int,
    val pumpOn: Boolean,
    val soaking: Boolean,
    val autoMode: Boolean,
    val muted: Boolean,
    val plantId: String,
    val moistMin: Int,
    val moistMax: Int,
    val sunHoursReq: Float,
    val sunThreshold: Int,
    val calDry: Int,
    val calWet: Int,
    val pumpRunsToday: Int,
    val alarmFlags: Int,
    val timeSynced: Boolean,
    val uptimeSec: Long,
) {
    val tankEmpty get() = alarmFlags and 8 != 0
    val soilFault get() = alarmFlags and 16 != 0
    val dhtFault get() = alarmFlags and 32 != 0
    val sunNow get() = lightPct >= sunThreshold

    companion object {
        fun parse(json: String): DeviceStatus? = try {
            val o = JSONObject(json)
            DeviceStatus(
                moisture = o.optInt("m"),
                soilRaw = o.optInt("raw"),
                tempC = if (o.isNull("t")) null else o.optDouble("t").toFloat(),
                humidity = if (o.isNull("h")) null else o.optDouble("h").toFloat(),
                lightPct = o.optInt("l"),
                ldrRaw = o.optInt("lraw"),
                sunMinutes = o.optInt("sun"),
                pumpOn = o.optInt("pump") == 1,
                soaking = o.optInt("soak") == 1,
                autoMode = o.optInt("auto", 1) == 1,
                muted = o.optInt("mute") == 1,
                plantId = o.optString("plant"),
                moistMin = o.optInt("min"),
                moistMax = o.optInt("max"),
                sunHoursReq = o.optDouble("sunH", 6.0).toFloat(),
                sunThreshold = o.optInt("sunTh", 60),
                calDry = o.optInt("dry"),
                calWet = o.optInt("wet"),
                pumpRunsToday = o.optInt("runs"),
                alarmFlags = o.optInt("al"),
                timeSynced = o.optInt("ts") == 1,
                uptimeSec = o.optLong("up"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
