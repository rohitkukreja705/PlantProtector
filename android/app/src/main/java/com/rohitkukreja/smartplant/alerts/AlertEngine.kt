package com.rohitkukreja.smartplant.alerts

import com.rohitkukreja.smartplant.data.DeviceStatus
import com.rohitkukreja.smartplant.data.Plant
import java.util.Calendar

enum class Severity { INFO, WARNING, CRITICAL }

enum class AlertType(val title: String, val severity: Severity) {
    SOIL_DRY("Soil is getting dry", Severity.WARNING),
    SOIL_VERY_DRY("Soil is very dry!", Severity.CRITICAL),
    OVERWATERED("Soil is too wet", Severity.WARNING),
    TANK_EMPTY("Water tank may be empty", Severity.CRITICAL),
    SOIL_SENSOR("Check soil sensor", Severity.CRITICAL),
    DHT_SENSOR("Temperature sensor not responding", Severity.WARNING),
    TOO_HOT("Too hot for your plant", Severity.WARNING),
    TOO_COLD("Too cold for your plant", Severity.WARNING),
    LOW_HUMIDITY("Air is too dry", Severity.INFO),
    LOW_SUN("Not enough sunlight today", Severity.WARNING),
    PUMP_ON("Watering started", Severity.INFO),
    DISCONNECTED("Device disconnected", Severity.WARNING),
}

data class Alert(val type: AlertType, val message: String, val time: Long = System.currentTimeMillis())

/**
 * Turns device status + plant profile into alert conditions.
 * [evaluate] returns the set of conditions that are currently active.
 */
object AlertEngine {

    fun evaluate(s: DeviceStatus, plant: Plant): Map<AlertType, String> {
        val out = linkedMapOf<AlertType, String>()

        if (s.tankEmpty) out[AlertType.TANK_EMPTY] =
            "Auto-watering ran 6 bursts but soil moisture didn't rise. Refill the tank / check the pipe, then tap Resume."
        if (s.soilFault) out[AlertType.SOIL_SENSOR] =
            "Soil sensor reading is stuck at ${s.soilRaw}. Check wiring (AO → GPIO3, VCC → 3.3V)."

        if (!s.soilFault) {
            when {
                s.moisture < plant.moistureMin - 15 -> out[AlertType.SOIL_VERY_DRY] =
                    "${plant.name} soil at ${s.moisture}% (ideal ${plant.moistureMin}–${plant.moistureMax}%). Water now."
                s.moisture < plant.moistureMin && !s.pumpOn && !s.soaking -> out[AlertType.SOIL_DRY] =
                    "${plant.name} soil at ${s.moisture}% — below the ${plant.moistureMin}% minimum."
                s.moisture > plant.moistureMax + 15 -> out[AlertType.OVERWATERED] =
                    "${plant.name} soil at ${s.moisture}% (max ${plant.moistureMax}%). Hold off watering and check drainage."
            }
        }

        val temp = s.tempC
        if (s.dhtFault || temp == null) {
            out[AlertType.DHT_SENSOR] = "No reading from DHT11. Check DATA → GPIO10 and power."
        } else {
            if (temp > plant.tempMax) out[AlertType.TOO_HOT] =
                "Air is ${"%.1f".format(temp)}°C — above ${plant.tempMax}°C for ${plant.name}. Move to light shade / water early morning."
            if (temp < plant.tempMin) out[AlertType.TOO_COLD] =
                "Air is ${"%.1f".format(temp)}°C — below ${plant.tempMin}°C for ${plant.name}. Move indoors or shelter it."
            val h = s.humidity
            if (h != null && h < plant.humidityMin - 10) out[AlertType.LOW_HUMIDITY] =
                "Humidity ${h.toInt()}% (likes ${plant.humidityMin}–${plant.humidityMax}%). Mist around the plant or group pots together."
        }

        // Sunlight: only judge late in the day (after 5 pm), when the day's sun is mostly over.
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val sunHrs = s.sunMinutes / 60f
        if (s.timeSynced && hour >= 17 && sunHrs < plant.sunHours * 0.75f) {
            out[AlertType.LOW_SUN] =
                "${plant.name} got ${"%.1f".format(sunHrs)} h of sun today; needs ~${plant.sunHours.toInt()} h. Move it to a sunnier spot."
        }

        if (s.pumpOn) out[AlertType.PUMP_ON] = "Pump running — soil at ${s.moisture}%."
        return out
    }
}
