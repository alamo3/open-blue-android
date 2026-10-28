package io.github.alamo3.openblue.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import io.github.alamo3.openblue.MainViewModel

/**
 * Settings for starting the engine sound by itself when the car connects: which car, and the
 * permissions that make it work from the background (each shown with whether it's granted).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundAutoStart(vm: MainViewModel) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val preview = LocalInspectionMode.current
    // Re-check permissions whenever we come back from a system screen.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    fun has(p: String) = preview || ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    if (tick < 0) return // reading tick re-runs the checks below after a system screen
    val bluetoothOk = Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT)
    val locationOk = has(Manifest.permission.ACCESS_FINE_LOCATION)
    val backgroundOk = Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    // runCatching: the screenshot renderer has no power service.
    val batteryOk = preview || runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(true)

    var picking by remember { mutableStateOf(false) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askBluetoothThenPick = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        if (ok) picking = true else vm.soundMessage("Open Blue needs the Nearby devices permission to see your paired car.")
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        if (!ok) openAppSettings(context)
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    // Pick from the phone's paired Bluetooth devices (the car is one of them).
    fun pickCar() {
        if (bluetoothOk) picking = true
        else if (Build.VERSION.SDK_INT >= 31) askBluetoothThenPick.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (picking) {
        val paired = remember { pairedDevices(context) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Which is your car?") },
            text = {
                if (paired.isEmpty()) {
                    Text("No paired Bluetooth devices found. Pair the phone with the car first.")
                } else {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        paired.forEach { (address, name) ->
                            ClickRow(name, address) {
                                vm.setSoundCar(address, name)
                                picking = false
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }

    HorizontalDivider(color = AppTheme.colors.hairline, modifier = Modifier.padding(top = 16.dp))
    SectionTitle("Where the phone sits")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1 to "Charging pad, top to the front", -1 to "Charging pad, top to the back", 0 to "Somewhere else").forEach { (v, label) ->
            OptionPill(label, vm.soundPlacement == v, { vm.changeSoundPlacement(v) })
        }
    }
    Text(
        if (vm.soundPlacement != 0) {
            "Lying flat on the pad, the phone's long side points along the car, so the sound knows which way is forward straight away. If it's actually the other way round, GPS notices within a few speed changes and it corrects itself."
        } else {
            "It works out which way is forward as you drive, above about 15 km/h. The phone needs to stay put."
        },
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    HorizontalDivider(color = AppTheme.colors.hairline, modifier = Modifier.padding(top = 16.dp))
    SectionTitle("With the car")
    SettingSwitch(
        "Start with the car",
        "Starts when your car's Bluetooth connects (as it does for Android Auto) and stops when it disconnects. You don't need to open the app.",
        vm.autoSound && vm.soundCarName != null,
    ) { on ->
        if (on && vm.soundCarName == null) pickCar() else vm.changeAutoSound(on)
    }
    if (vm.soundCarName != null) {
        ClickRow("Car", "${vm.soundCarName} · tap to pick a different one") {
            vm.forgetSoundCar()
            pickCar()
        }
    }
    if (vm.autoSound && vm.soundCarName != null) {
        Column {
            PermissionRow(
                "Nearby devices",
                "Lets the app notice the car connecting.",
                bluetoothOk,
            ) { if (Build.VERSION.SDK_INT >= 31) askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) }
            PermissionRow(
                "Location: allow all the time",
                "GPS speed when the sound starts by itself with the phone locked. Without it, the sound follows acceleration only.",
                locationOk && backgroundOk,
            ) {
                if (!locationOk) {
                    askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else if (Build.VERSION.SDK_INT >= 29) {
                    askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            PermissionRow(
                "Battery: unrestricted",
                "Needed: it's what lets Android start the sound in the background, and stops the app being put to sleep. On Samsung, also keep it out of Sleeping apps.",
                batteryOk,
            ) {
                @Suppress("BatteryLife")
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }.onFailure { openAppSettings(context) }
            }
        }
    }
    Text(
        "Away from the pad, the phone's position is remembered between drives, so if it sits the same way the sound follows the car from the start.",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, fix: () -> Unit) {
    ClickRow(title, if (granted) "Allowed. $why" else "Not allowed yet: tap to allow. $why", enabled = !granted) { fix() }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@android.annotation.SuppressLint("MissingPermission")
private fun pairedDevices(context: Context): List<Pair<String, String>> = runCatching {
    val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
    adapter?.bondedDevices.orEmpty().map { it.address.uppercase() to (runCatching { it.name }.getOrNull() ?: it.address) }
        .sortedBy { it.second.lowercase() }
}.getOrDefault(emptyList())
