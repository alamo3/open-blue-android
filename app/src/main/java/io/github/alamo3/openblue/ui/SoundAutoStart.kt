package io.github.alamo3.openblue.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import io.github.alamo3.openblue.sound.CarLink

/**
 * Settings for starting the engine sound by itself when the car connects: which car, and the
 * permissions that make it work from the background (each shown with whether it's granted).
 */
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
    val batteryOk = preview || context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        if (!ok) openAppSettings(context)
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val car = CarLink.picked(context, r.data)
            if (car != null) {
                vm.setSoundCar(car.first, car.second)
                if (!bluetoothOk && Build.VERSION.SDK_INT >= 31) askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                vm.soundMessage("Couldn't read which car was picked. Try again.")
            }
        }
    }
    fun pickCar() = CarLink.pick(
        context,
        launch = { chooser.launch(IntentSenderRequest.Builder(it).build()) },
        failed = { vm.soundMessage(it) },
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
                "Stops Android putting the app to sleep, so it hears the car connect.",
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
        "The phone's position in its mount is remembered between drives, so if it sits the same way the sound follows the car from the start.",
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
