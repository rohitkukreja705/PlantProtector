package com.rohitkukreja.smartplant

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rohitkukreja.smartplant.ui.AlertsScreen
import com.rohitkukreja.smartplant.ui.DashboardScreen
import com.rohitkukreja.smartplant.ui.PermissionScreen
import com.rohitkukreja.smartplant.ui.PlantsScreen
import com.rohitkukreja.smartplant.ui.SettingsScreen
import com.rohitkukreja.smartplant.ui.theme.SmartPlantTheme

class MainActivity : ComponentActivity() {

    private val repo by lazy { (application as SmartPlantApp).repo }
    private var permissionsGranted by mutableStateOf(false)

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private val blePermissions: List<String>
        get() = requiredPermissions.filter { it != Manifest.permission.POST_NOTIFICATIONS }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (repo.ble.isBluetoothOn()) repo.ble.connect()
        }

    private fun refreshPermissions() {
        permissionsGranted = blePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (permissionsGranted) repo.ble.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        if (!permissionsGranted) permissionLauncher.launch(requiredPermissions)

        setContent {
            SmartPlantTheme {
                if (!permissionsGranted) {
                    PermissionScreen(onGrant = { permissionLauncher.launch(requiredPermissions) })
                } else {
                    MainScaffold()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun requestEnableBluetooth() {
        try {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: SecurityException) { }
    }

    @androidx.compose.runtime.Composable
    private fun MainScaffold() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val active by repo.activeAlerts.collectAsStateWithLifecycle()
        val tabs = listOf(
            "Dashboard" to Icons.Filled.Dashboard,
            "Plants" to Icons.Filled.LocalFlorist,
            "Alerts" to Icons.Filled.Notifications,
            "Settings" to Icons.Filled.Settings,
        )
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            label = { Text(label) },
                            icon = {
                                if (i == 2 && active.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text("${active.size}") } }) {
                                        Icon(icon, label)
                                    }
                                } else Icon(icon, label)
                            },
                        )
                    }
                }
            },
        ) { pad ->
            val m = Modifier.padding(pad)
            when (tab) {
                0 -> DashboardScreen(repo, m, onEnableBluetooth = ::requestEnableBluetooth, onChangePlant = { tab = 1 })
                1 -> PlantsScreen(repo, m, onSelected = { tab = 0 })
                2 -> AlertsScreen(repo, m)
                else -> SettingsScreen(repo, m)
            }
        }
    }
}
