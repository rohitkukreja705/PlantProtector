package com.rohitkukreja.smartplant.data

/**
 * Care profile for each supported plant.
 * Moisture % is relative to the soil sensor calibration (0 % = "dry" point, 100 % = "wet" point).
 */
data class Plant(
    val id: String,
    val name: String,
    val emoji: String,
    val moistureMin: Int,
    val moistureMax: Int,
    val sunHours: Float,
    val sunLabel: String,
    val tempMin: Int,
    val tempMax: Int,
    val humidityMin: Int,
    val humidityMax: Int,
    val wateringTip: String,
    val sunTip: String,
    val extraTip: String,
)

object Plants {
    val ROSE = Plant(
        id = "rose", name = "Rose", emoji = "🌹",
        moistureMin = 40, moistureMax = 60,
        sunHours = 6f, sunLabel = "Full sun · 6+ hrs",
        tempMin = 15, tempMax = 30, humidityMin = 40, humidityMax = 70,
        wateringTip = "Water deeply when the top 2–3 cm of soil is dry. Water at the base, keep leaves dry to avoid black spot.",
        sunTip = "Needs at least 6 hours of direct sun. Morning sun is best — it dries dew off the leaves.",
        extraTip = "Good drainage is essential; roses dislike soggy roots.",
    )
    val TOMATO = Plant(
        id = "tomato", name = "Tomato", emoji = "🍅",
        moistureMin = 60, moistureMax = 80,
        sunHours = 8f, sunLabel = "Full sun · 8 hrs",
        tempMin = 18, tempMax = 32, humidityMin = 50, humidityMax = 75,
        wateringTip = "Keep soil evenly moist. Irregular watering causes fruit cracking and blossom-end rot.",
        sunTip = "Needs 6–8+ hours of direct sun to flower and fruit well.",
        extraTip = "Pots dry out fast in fruiting season — expect daily watering in summer.",
    )
    val TULSI = Plant(
        id = "tulsi", name = "Tulsi", emoji = "🌿",
        moistureMin = 45, moistureMax = 65,
        sunHours = 6f, sunLabel = "Full / partial sun · 4–6 hrs",
        tempMin = 18, tempMax = 35, humidityMin = 40, humidityMax = 70,
        wateringTip = "Keep soil lightly moist but never waterlogged. Water when the top 1–2 cm feels dry.",
        sunTip = "Loves 4–6+ hours of sun. In peak summer, afternoon shade prevents leaf scorch.",
        extraTip = "Sensitive to cold — protect when below 10 °C. Pinch flower spikes for bushier growth.",
    )
    val LEMON = Plant(
        id = "lemon", name = "Lemon", emoji = "🍋",
        moistureMin = 30, moistureMax = 50,
        sunHours = 8f, sunLabel = "Full sun · 8+ hrs",
        tempMin = 15, tempMax = 32, humidityMin = 50, humidityMax = 70,
        wateringTip = "Let the top few cm dry between waterings. Citrus hates wet feet — overwatering yellows leaves.",
        sunTip = "Needs 8+ hours of direct sun for flowering and fruit.",
        extraTip = "Use a well-draining pot; water less in winter.",
    )

    val all = listOf(ROSE, TOMATO, TULSI, LEMON)
    fun byId(id: String?): Plant = all.firstOrNull { it.id == id } ?: ROSE
}
