package com.rohitkukreja.smartplant

import android.app.Application
import android.content.Context
import com.rohitkukreja.smartplant.alerts.Alert
import com.rohitkukreja.smartplant.alerts.AlertEngine
import com.rohitkukreja.smartplant.alerts.AlertType
import com.rohitkukreja.smartplant.alerts.Notifier
import com.rohitkukreja.smartplant.alerts.Severity
import com.rohitkukreja.smartplant.ble.BleManager
import com.rohitkukreja.smartplant.ble.ConnState
import com.rohitkukreja.smartplant.data.Plant
import com.rohitkukreja.smartplant.data.Plants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.TimeZone

class SmartPlantApp : Application() {
    lateinit var repo: PlantRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = PlantRepository(this)
    }
}

/**
 * App-wide state: BLE link, selected plant, active alerts and alert history.
 * Lives as long as the app process, so alerts keep firing while the app is in the background.
 */
class PlantRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("smart_plant", Context.MODE_PRIVATE)

    val ble = BleManager(context)
    private val notifier = Notifier(context)

    private val _plant = MutableStateFlow(Plants.byId(prefs.getString("plant", "rose")))
    val plant: StateFlow<Plant> = _plant.asStateFlow()

    private val _sunThreshold = MutableStateFlow(prefs.getInt("sunTh", 60))
    val sunThreshold: StateFlow<Int> = _sunThreshold.asStateFlow()

    /** Conditions active right now (shown as banners on the dashboard). */
    private val _active = MutableStateFlow<List<Alert>>(emptyList())
    val activeAlerts: StateFlow<List<Alert>> = _active.asStateFlow()

    /** Log of alerts as they were raised (newest first). */
    private val _history = MutableStateFlow<List<Alert>>(emptyList())
    val history: StateFlow<List<Alert>> = _history.asStateFlow()

    private val dismissed = mutableSetOf<AlertType>()
    private val lastRaised = mutableMapOf<AlertType, Long>()
    private var wasConnected = false

    init {
        ble.onReady = {
            syncTime()
            pushPlantConfig()
        }

        scope.launch {
            combine(ble.status, _plant) { s, p -> s to p }.collect { (s, p) ->
                if (s == null || ble.state.value != ConnState.CONNECTED) return@collect
                update(AlertEngine.evaluate(s, p))
            }
        }

        scope.launch {
            ble.state.collect { st ->
                if (st == ConnState.CONNECTED) {
                    wasConnected = true
                    dismissed.remove(AlertType.DISCONNECTED)
                    _active.value = _active.value.filter { it.type != AlertType.DISCONNECTED }
                } else if (wasConnected && (st == ConnState.DISCONNECTED || st == ConnState.NOT_FOUND)) {
                    wasConnected = false
                    val a = Alert(AlertType.DISCONNECTED, "Lost connection to the plant monitor. Reconnecting…")
                    raise(a)
                    _active.value = listOf(a)
                }
            }
        }
    }

    private fun update(conditions: Map<AlertType, String>) {
        // Clear dismissals for conditions that went away, so they can alert again next time.
        dismissed.retainAll(conditions.keys)
        lastRaised.keys.retainAll(conditions.keys)

        val now = System.currentTimeMillis()
        val active = mutableListOf<Alert>()
        for ((type, msg) in conditions) {
            val alert = Alert(type, msg)
            val prev = lastRaised[type]
            val repeatMs = if (type.severity == Severity.CRITICAL) 30 * 60_000L else 3 * 3600_000L
            if (prev == null || (now - prev > repeatMs && type != AlertType.PUMP_ON)) {
                raise(alert)
            }
            if (type !in dismissed) active += alert
        }
        _active.value = active.sortedByDescending { it.type.severity.ordinal }
    }

    private fun raise(alert: Alert) {
        lastRaised[alert.type] = alert.time
        _history.value = (listOf(alert) + _history.value).take(100)
        notifier.post(alert)
    }

    fun dismiss(type: AlertType) {
        dismissed += type
        _active.value = _active.value.filter { it.type != type }
    }

    fun clearHistory() { _history.value = emptyList() }

    // ---------------- Device actions ----------------
    fun selectPlant(p: Plant) {
        _plant.value = p
        prefs.edit().putString("plant", p.id).apply()
        lastRaised.clear(); dismissed.clear()
        pushPlantConfig()
    }

    fun setSunThreshold(v: Int) {
        _sunThreshold.value = v
        prefs.edit().putInt("sunTh", v).apply()
        pushPlantConfig()
    }

    private fun pushPlantConfig() {
        val p = _plant.value
        val j = JSONObject()
            .put("plant", p.id)
            .put("min", p.moistureMin)
            .put("max", p.moistureMax)
            .put("sunH", p.sunHours.toDouble())
            .put("sunTh", _sunThreshold.value)
        ble.sendConfig(j.toString())
    }

    private fun syncTime() {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault().getOffset(now) / 1000
        cmd(JSONObject().put("cmd", "time").put("epoch", now / 1000).put("tz", tz))
    }

    private fun cmd(j: JSONObject) = ble.sendCommand(j.toString())

    fun waterNow(seconds: Int) = cmd(JSONObject().put("cmd", "pump").put("on", 1).put("sec", seconds))
    fun stopPump() = cmd(JSONObject().put("cmd", "pump").put("on", 0))
    fun setAuto(on: Boolean) = cmd(JSONObject().put("cmd", "auto").put("on", if (on) 1 else 0))
    fun setMute(on: Boolean) = cmd(JSONObject().put("cmd", "mute").put("on", if (on) 1 else 0))
    fun findDevice() = cmd(JSONObject().put("cmd", "beep"))
    fun resumeAfterTank() = cmd(JSONObject().put("cmd", "auto").put("on", 1))
    fun calibrateDry() = cmd(JSONObject().put("cmd", "calDry"))
    fun calibrateWet() = cmd(JSONObject().put("cmd", "calWet"))
    fun resetCalibration() = cmd(JSONObject().put("cmd", "calReset"))
}
