package com.rohitkukreja.smartplant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rohitkukreja.smartplant.PlantRepository
import com.rohitkukreja.smartplant.alerts.AlertType
import com.rohitkukreja.smartplant.ble.ConnState
import com.rohitkukreja.smartplant.data.DeviceStatus
import com.rohitkukreja.smartplant.data.Plant
import com.rohitkukreja.smartplant.data.Plants
import com.rohitkukreja.smartplant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// =====================================================================
// Permission
// =====================================================================
@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🪴", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text("Smart Plant", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Bluetooth access is needed to talk to your plant monitor, and notifications to alert you when your plant needs care.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Allow access") }
    }
}

// =====================================================================
// Dashboard
// =====================================================================
@Composable
fun DashboardScreen(
    repo: PlantRepository,
    modifier: Modifier,
    onEnableBluetooth: () -> Unit,
    onChangePlant: () -> Unit,
) {
    val state by repo.ble.state.collectAsStateWithLifecycle()
    val status by repo.ble.status.collectAsStateWithLifecycle()
    val plant by repo.plant.collectAsStateWithLifecycle()
    val alerts by repo.activeAlerts.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PlantHeader(plant, state, onChangePlant)

        when (state) {
            ConnState.BLUETOOTH_OFF -> InfoCard("Bluetooth is off", "Turn it on to connect to your plant monitor.", "Turn on", onEnableBluetooth)
            ConnState.NOT_FOUND, ConnState.DISCONNECTED, ConnState.IDLE ->
                InfoCard(
                    "Plant monitor not connected",
                    "Make sure the ESP32 is powered and within ~10 m. Retrying automatically…",
                    "Retry now",
                ) { repo.ble.connect() }
            else -> {}
        }

        alerts.forEach { a ->
            val tank = a.type == AlertType.TANK_EMPTY
            AlertBanner(
                a,
                onDismiss = { repo.dismiss(a.type) },
                actionLabel = if (tank) "Tank refilled — resume" else null,
                onAction = if (tank) ({ repo.resumeAfterTank() }) else null,
            )
        }

        val s = status
        if (s == null) {
            if (state == ConnState.CONNECTED) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            CareTips(plant)
            return@Column
        }
        val stale = state != ConnState.CONNECTED
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (stale) Text("Showing last known readings", style = MaterialTheme.typography.labelMedium, color = Warn)
            MoistureCard(s, plant)
            SunCard(s, plant)
            ClimateCard(s, plant)
            PumpCard(s, repo, enabled = !stale)
            CareTips(plant)
        }
    }
}

@Composable
private fun PlantHeader(plant: Plant, state: ConnState, onChangePlant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(plant.emoji, fontSize = 40.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(plant.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (c, t) = when (state) {
                    ConnState.CONNECTED -> Ok to "Connected"
                    ConnState.SCANNING -> Sun to "Searching…"
                    ConnState.CONNECTING -> Sun to "Connecting…"
                    ConnState.BLUETOOTH_OFF -> Danger to "Bluetooth off"
                    else -> Danger to "Offline"
                }
                StatusDot(c)
                Spacer(Modifier.width(6.dp))
                Text(t, style = MaterialTheme.typography.labelLarge)
            }
        }
        OutlinedButton(onClick = onChangePlant) { Text("Change") }
    }
}

@Composable
private fun InfoCard(title: String, text: String, button: String, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun MoistureCard(s: DeviceStatus, plant: Plant) {
    SectionCard("Soil moisture", Icons.Filled.WaterDrop, Water) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MoistureGauge(s.moisture, plant.moistureMin, plant.moistureMax)
        }
        val (label, color) = when {
            s.soilFault -> "Sensor fault" to Danger
            s.moisture < plant.moistureMin - 15 -> "Very dry — needs water now" to Danger
            s.moisture < plant.moistureMin -> "A bit dry" to Warn
            s.moisture > plant.moistureMax + 15 -> "Waterlogged" to Danger
            s.moisture > plant.moistureMax -> "Slightly wet" to Warn
            else -> "Just right" to Ok
        }
        Text(label, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            "Ideal for ${plant.name}: ${plant.moistureMin}–${plant.moistureMax}%",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SunCard(s: DeviceStatus, plant: Plant) {
    SectionCard("Sunlight", Icons.Filled.WbSunny, Sun) {
        val hrs = s.sunMinutes / 60f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabeledValue("Light now", "${s.lightPct}%", if (s.sunNow) "☀ Direct sun" else "Shade / indoor")
            LabeledValue("Sun today", "%.1f h".format(hrs), "Needs ${plant.sunHours.roundToInt()} h")
        }
        Spacer(Modifier.height(10.dp))
        val progress = (hrs / plant.sunHours).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = if (progress >= 1f) Ok else Sun,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        Spacer(Modifier.height(8.dp))
        Text("${plant.sunLabel}. ${plant.sunTip}", style = MaterialTheme.typography.bodySmall)
        if (!s.timeSynced) {
            Text("Daily count resets 24 h after power-on until the app syncs the clock.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClimateCard(s: DeviceStatus, plant: Plant) {
    SectionCard("Air (DHT11)", Icons.Filled.Thermostat, Danger) {
        val t = s.tempC
        if (t == null || s.dhtFault) {
            Text("No reading from DHT11 — check wiring.", color = Danger)
            return@SectionCard
        }
        val h = s.humidity ?: 0f
        val tColor = if (t < plant.tempMin || t > plant.tempMax) Warn else Ok
        val hColor = if (h < plant.humidityMin - 10 || h > plant.humidityMax + 15) Warn else Ok
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabeledValue("Temperature", "%.1f°C".format(t), "Ideal ${plant.tempMin}–${plant.tempMax}°C", tColor)
            LabeledValue("Humidity", "${h.roundToInt()}%", "Ideal ${plant.humidityMin}–${plant.humidityMax}%", hColor)
        }
    }
}

@Composable
private fun PumpCard(s: DeviceStatus, repo: PlantRepository, enabled: Boolean) {
    SectionCard("Watering", Icons.Filled.Opacity, Water) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (s.pumpOn) Water else if (s.soaking) Sun else Color.Gray)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    s.pumpOn -> "Pump running"
                    s.soaking -> "Soaking — re-checking soil shortly"
                    else -> "Pump idle"
                },
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text("${s.pumpRunsToday} runs today", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.pumpOn || s.soaking) {
                Button(
                    onClick = { repo.stopPump() }, enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop") }
            } else {
                Button(onClick = { repo.waterNow(5) }, enabled = enabled) { Text("Water 5 s") }
                FilledTonalButton(onClick = { repo.waterNow(10) }, enabled = enabled) { Text("Water 10 s") }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SwitchRow("Auto-watering", "Waters in short bursts when soil drops below the minimum", s.autoMode, enabled) {
            repo.setAuto(it)
        }
        SwitchRow("Buzzer alerts", "Beeps on the device when soil is very dry or tank is empty", !s.muted, enabled) {
            repo.setMute(!it)
        }
        TextButton(onClick = { repo.findDevice() }, enabled = enabled) {
            Icon(Icons.Filled.NotificationsActive, null); Spacer(Modifier.width(6.dp)); Text("Beep to find device")
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun CareTips(plant: Plant) {
    SectionCard("${plant.name} care tips", Icons.Filled.Spa) {
        Text("💧 ${plant.wateringTip}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text("☀️ ${plant.sunTip}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text("🌱 ${plant.extraTip}", style = MaterialTheme.typography.bodyMedium)
    }
}

// =====================================================================
// Plant selection
// =====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantsScreen(repo: PlantRepository, modifier: Modifier, onSelected: () -> Unit) {
    val current by repo.plant.collectAsStateWithLifecycle()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Choose your plant", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "The monitor will use this plant's moisture range for auto-watering and alerts.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(Plants.all, key = { it.id }) { p ->
            val selected = p.id == current.id
            OutlinedCard(
                onClick = { repo.selectPlant(p); onSelected() },
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.emoji, fontSize = 44.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("💧 Moisture ${p.moistureMin}–${p.moistureMax}%", style = MaterialTheme.typography.bodyMedium)
                        Text("☀️ ${p.sunLabel}", style = MaterialTheme.typography.bodyMedium)
                        Text("🌡 ${p.tempMin}–${p.tempMax}°C", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (selected) Icon(Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// =====================================================================
// Alerts
// =====================================================================
@Composable
fun AlertsScreen(repo: PlantRepository, modifier: Modifier) {
    val active by repo.activeAlerts.collectAsStateWithLifecycle()
    val history by repo.history.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Active now", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (active.isEmpty()) {
            item { Text("✅ All good — no issues right now.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(active, key = { "a" + it.type.name }) { a -> AlertBanner(a, onDismiss = { repo.dismiss(a.type) }) }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) TextButton(onClick = { repo.clearHistory() }) { Text("Clear") }
            }
        }
        if (history.isEmpty()) {
            item { Text("No alerts yet.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(history, key = { "h" + it.time + it.type.name }) { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                StatusDot(severityColor(a.type.severity))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.type.title, fontWeight = FontWeight.Medium)
                    Text(a.message, style = MaterialTheme.typography.bodySmall)
                    Text(fmt.format(Date(a.time)), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}

// =====================================================================
// Settings (connection, calibration, sun threshold)
// =====================================================================
@Composable
fun SettingsScreen(repo: PlantRepository, modifier: Modifier) {
    val state by repo.ble.state.collectAsStateWithLifecycle()
    val name by repo.ble.deviceName.collectAsStateWithLifecycle()
    val status by repo.ble.status.collectAsStateWithLifecycle()
    val sunTh by repo.sunThreshold.collectAsStateWithLifecycle()
    val connected = state == ConnState.CONNECTED

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("Device", Icons.Filled.Bluetooth) {
            Text("Status: ${state.name.lowercase().replace('_', ' ')}")
            if (name != null) Text("Device: $name", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) OutlinedButton(onClick = { repo.ble.disconnect() }) { Text("Disconnect") }
                else Button(onClick = { repo.ble.connect() }) { Text("Connect") }
            }
        }

        SectionCard("Soil sensor calibration", Icons.Filled.Tune) {
            Text(
                "Every sensor reads differently. For accurate %, calibrate once:\n" +
                    "1. Hold the probe in dry air → tap Set DRY\n" +
                    "2. Dip it in a glass of water (up to the line) → tap Set WET",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            status?.let { s ->
                Text("Raw now: ${s.soilRaw}   ·   Dry: ${s.calDry}   ·   Wet: ${s.calWet}",
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { repo.calibrateDry() }, enabled = connected) { Text("Set DRY") }
                Button(onClick = { repo.calibrateWet() }, enabled = connected) { Text("Set WET") }
                TextButton(onClick = { repo.resetCalibration() }, enabled = connected) { Text("Reset") }
            }
        }

        SectionCard("Sunlight detection (LDR)", Icons.Filled.WbSunny, Sun) {
            Text(
                "Light at or above this level counts as direct sun for the daily sunlight total.",
                style = MaterialTheme.typography.bodySmall,
            )
            var slider by remember(sunTh) { mutableFloatStateOf(sunTh.toFloat()) }
            Text("Threshold: ${slider.roundToInt()}%", fontWeight = FontWeight.Medium)
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { repo.setSunThreshold(slider.roundToInt()) },
                valueRange = 10f..95f,
            )
            status?.let { s ->
                Text("Current light: ${s.lightPct}%  (raw ${s.ldrRaw})", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { repo.setSunThreshold((s.lightPct - 5).coerceIn(10, 95)) }, enabled = connected) {
                    Text("Place in direct sun & use current light")
                }
            }
        }

        SectionCard("Wiring reference", Icons.Filled.Cable) {
            Text(
                "Soil AO → GPIO3   ·   LDR AO → GPIO4\n" +
                    "Relay IN → GPIO6  ·   Buzzer I/O → GPIO7\n" +
                    "DHT11 DATA → GPIO10\n" +
                    "Sensors on 3.3V · Relay & pump on 5V",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
