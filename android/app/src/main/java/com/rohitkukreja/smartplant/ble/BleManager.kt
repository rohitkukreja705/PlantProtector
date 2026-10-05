package com.rohitkukreja.smartplant.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.rohitkukreja.smartplant.data.DeviceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID

enum class ConnState { IDLE, BLUETOOTH_OFF, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, NOT_FOUND }

/**
 * Talks to the ESP32 "SmartPlant" GATT server.
 * All GATT operations are serialised through a queue (Android allows only one at a time).
 * Callers must hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT (or location on Android ≤ 11).
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        val SERVICE: UUID = UUID.fromString("7a3e1000-5f2c-4b8e-9d41-0c8a5e6f1a01")
        val STATUS: UUID = UUID.fromString("7a3e1001-5f2c-4b8e-9d41-0c8a5e6f1a01")
        val CONFIG: UUID = UUID.fromString("7a3e1002-5f2c-4b8e-9d41-0c8a5e6f1a01")
        val COMMAND: UUID = UUID.fromString("7a3e1003-5f2c-4b8e-9d41-0c8a5e6f1a01")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val RECONNECT_DELAY_MS = 4_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _state = MutableStateFlow(ConnState.IDLE)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _status = MutableStateFlow<DeviceStatus?>(null)
    val status: StateFlow<DeviceStatus?> = _status.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /** Called once the link is ready (notifications on) — used to push time + plant profile. */
    var onReady: (() -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var userWantsConnection = false
    private var scanning = false

    // ---------------- GATT operation queue ----------------
    private sealed class Op {
        data class Write(val uuid: UUID, val payload: ByteArray) : Op()
        data class EnableNotify(val uuid: UUID) : Op()
        data class Read(val uuid: UUID) : Op()
    }
    private val queue = ArrayDeque<Op>()
    private var busy = false

    private fun enqueue(op: Op) {
        main.post {
            queue.add(op)
            if (!busy) next()
        }
    }

    private fun next() {
        val g = gatt ?: run { queue.clear(); busy = false; return }
        val op = queue.poll() ?: run { busy = false; return }
        busy = true
        val svc = g.getService(SERVICE) ?: run { busy = false; queue.clear(); return }
        val ok = when (op) {
            is Op.Write -> {
                val c = svc.getCharacteristic(op.uuid)
                if (c == null) false else writeChar(g, c, op.payload)
            }
            is Op.EnableNotify -> {
                val c = svc.getCharacteristic(op.uuid)
                if (c == null) false else {
                    g.setCharacteristicNotification(c, true)
                    val d = c.getDescriptor(CCCD)
                    if (d == null) false else writeDesc(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
            is Op.Read -> {
                val c = svc.getCharacteristic(op.uuid)
                c != null && g.readCharacteristic(c)
            }
        }
        if (!ok) { Log.w(TAG, "op failed: $op"); busy = false; main.post { next() } }
    }

    private fun opDone() {
        main.post { busy = false; next() }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = v
            g.writeCharacteristic(c)
        }

    @Suppress("DEPRECATION")
    private fun writeDesc(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == 0
        } else {
            d.value = v
            g.writeDescriptor(d)
        }

    // ---------------- Public API ----------------
    fun isBluetoothOn() = adapter?.isEnabled == true

    fun connect() {
        userWantsConnection = true
        if (!isBluetoothOn()) { _state.value = ConnState.BLUETOOTH_OFF; return }
        if (gatt != null || scanning) return
        startScan()
    }

    fun disconnect() {
        userWantsConnection = false
        stopScan()
        main.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        queue.clear(); busy = false
        _state.value = ConnState.IDLE
    }

    fun sendConfig(json: String) {
        if (_state.value == ConnState.CONNECTED) enqueue(Op.Write(CONFIG, json.toByteArray()))
    }

    fun sendCommand(json: String) {
        if (_state.value == ConnState.CONNECTED) enqueue(Op.Write(COMMAND, json.toByteArray()))
    }

    // ---------------- Scanning ----------------
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            connectGatt(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed $errorCode")
            scanning = false
            _state.value = ConnState.NOT_FOUND
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { _state.value = ConnState.BLUETOOTH_OFF; return }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        _state.value = ConnState.SCANNING
        scanner.startScan(filters, settings, scanCallback)
        main.postDelayed({
            if (scanning) {
                stopScan()
                _state.value = ConnState.NOT_FOUND
                scheduleReconnect()
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private fun scheduleReconnect() {
        if (!userWantsConnection) return
        main.postDelayed({ if (userWantsConnection && gatt == null && !scanning) connect() }, RECONNECT_DELAY_MS)
    }

    // ---------------- GATT ----------------
    private fun connectGatt(device: BluetoothDevice) {
        _state.value = ConnState.CONNECTING
        _deviceName.value = device.name ?: device.address
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // Status JSON is ~330 bytes; default MTU (23) would truncate it.
                main.post { g.requestMtu(512) }
            } else {
                main.post {
                    g.close()
                    if (gatt == g) gatt = null
                    queue.clear(); busy = false
                    _state.value = if (userWantsConnection) ConnState.DISCONNECTED else ConnState.IDLE
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU=$mtu")
            main.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || g.getService(SERVICE) == null) {
                main.post { g.disconnect() }
                return
            }
            enqueue(Op.EnableNotify(STATUS))
            enqueue(Op.Read(STATUS))
            main.post {
                _state.value = ConnState.CONNECTED
                onReady?.invoke()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            opDone()
        }

        // Android 13+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (c.uuid == STATUS) handleStatus(value)
            opDone()
        }

        @Deprecated("Android 12 and below")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid == STATUS) c.value?.let { handleStatus(it) }
            opDone()
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == STATUS) handleStatus(value)
        }

        @Deprecated("Android 12 and below")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == STATUS) c.value?.let { handleStatus(it) }
        }
    }

    private fun handleStatus(bytes: ByteArray) {
        DeviceStatus.parse(String(bytes, Charsets.UTF_8))?.let { _status.value = it }
    }
}
