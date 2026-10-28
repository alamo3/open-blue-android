package io.github.alamo3.openblue

import android.app.Application
import android.content.SharedPreferences
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.alamo3.openblue.ui.CarCamera
import io.github.alamo3.openblue.ui.ChargingScreen
import io.github.alamo3.openblue.ui.CarEffects
import io.github.alamo3.openblue.ui.ClimateFx
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.VehicleCaps
import io.github.alamo3.openblue.api.VehicleInfo
import io.github.alamo3.openblue.data.SecureStore
import io.github.alamo3.openblue.ui.CameraScreen
import io.github.alamo3.openblue.ui.ClimateScreen
import io.github.alamo3.openblue.ui.CommandStrip
import io.github.alamo3.openblue.ui.DebugOverlay
import io.github.alamo3.openblue.ui.HomeScreen
import io.github.alamo3.openblue.ui.LoginScreen
import io.github.alamo3.openblue.ui.OpenBlueTheme
import io.github.alamo3.openblue.ui.SettingsScreen
import io.github.alamo3.openblue.ui.VehicleScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Renders every screen from made-up demo data (no real account or vehicle details),
 * in light and dark. CI publishes the PNGs to the `screenshots` branch for review.
 */
@RunWith(Parameterized::class)
class ScreenshotTest(private val night: Boolean) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT),
        theme = "android:Theme.Material.NoActionBar",
        maxPercentDifference = 1.0,
    )

    private fun demoVm(signedIn: Boolean = true): MainViewModel {
        val store = SecureStore(MemoryPrefs())
        if (signedIn) {
            store.username = "driver@example.com"
            store.password = "x"
            store.pin = "0000"
            store.vehicle = VehicleInfo("demo", "My IONIQ 5", "IONIQ 5 EV", "2027", "KM8DEMO0000000000", "E")
            store.caps = VehicleCaps(
                genType = "G3", hvacTempType = 1, strgWhlHeatingOption = 2,
                seatOptions = listOf(6, 6, 1, 1), trim = "Ultimate", odometer = 1234.0, odometerUnit = 1, hasSvm = true,
            )
            store.cachedStatus = DEMO_STATUS
            store.lastClimate = ClimateSettings(tempC = 22.0, driverSeat = 8, passengerSeat = 3, heating = 1, durationMin = 15)
            store.localPresets = listOf(
                ClimateSettings(name = "Winter morning", tempC = 24.0, defrost = true, heating = 1, driverSeat = 8, durationMin = 20),
                ClimateSettings(name = "Summer", tempC = 19.0, driverSeat = 5, passengerSeat = 5, durationMin = 10),
            )
        }
        return MainViewModel(Application(), store, online = false)
    }

    private fun shot(content: @Composable () -> Unit) = paparazzi.snapshot {
        OpenBlueTheme {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    @Test fun home() = demoVm().let { vm -> shot { HomeScreen(vm, open = {}, onUnlock = {}) } }
    @Test fun climate() = demoVm().let { vm -> shot { ClimateScreen(vm, onBack = {}) } }

    /** Whole climate screen, including the presets below the fold. */
    @Test fun climateTall() {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_5.copy(
                screenHeight = 4200,
                nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
            ),
        )
        val vm = demoVm()
        shot { ClimateScreen(vm, onBack = {}) }
    }
    @Test fun charging() = demoVm().let { vm -> shot { ChargingScreen(vm, onBack = {}) } }
    @Test fun vehicle() = demoVm().let { vm -> shot { VehicleScreen(vm, onBack = {}) } }
    @Test fun settings() = demoVm().let { vm ->
        shot {
            // The photo picker needs an activity-result registry, which the renderer doesn't provide.
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoopRegistryOwner) {
                SettingsScreen(vm, onBack = {})
            }
        }
    }
    @Test fun camera() = demoVm().let { vm -> shot { CameraScreen(vm, onBack = {}) } }
    @Test fun login() = demoVm(signedIn = false).let { vm -> shot { LoginScreen(vm) } }

    /** Home screen driven by the developer panel's simulated car, with the panel open. */
    @Test fun debugPanel() {
        val vm = demoVm()
        vm.changeDeveloperMode(true)
        vm.updateDebug { it.copy(simulate = true, frunk = true, climateOn = true, tempC = 18.5, defrost = true, seats = listOf(8, 3, 6, 2)).withDoor(0, true) }
        shot {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                HomeScreen(vm, open = {}, onUnlock = {})
                DebugOverlay(vm, initiallyOpen = true)
            }
        }
    }

    /**
     * Effects over a still of the car from the charge-port side (rendered from the same model and
     * camera by tools/model/appview.py), to check they line up: cooling airflow with defrost, and
     * current running down the cable.
     */
    @Test fun carEffects() {
        val still = javaClass.getResourceAsStream("/car_still_port.webp")!!.use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        val camera = CarCamera().apply { azimuth = CarCamera.PORT_AZIMUTH }
        shot {
            Box(Modifier.fillMaxWidth().aspectRatio(still.width.toFloat() / still.height)) {
                Image(still, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                CarEffects(camera, ClimateFx.COOL, defrost = true, charging = true, cablePlugged = { 1f }, Modifier.fillMaxSize(), ownsCamera = true)
            }
        }
    }

    @Test fun commandStrip() = shot {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            CommandStrip(
                CommandProgress(CommandProgress.Action.CLIMATE_START, CommandProgress.Stage.WAITING),
                onDismiss = {},
            )
        }
    }
}

private const val DEMO_STATUS = """
{"lastStatusDate":"20261027202614","airCtrlOn":true,"doorLock":true,
 "doorOpen":{"frontLeft":0,"frontRight":0,"backLeft":0,"backRight":0},
 "windowOpen":{"frontLeft":0,"frontRight":0,"backLeft":0,"backRight":0},
 "trunkOpen":false,"hoodOpen":false,"defrost":false,"airTemp":{"value":"10H","unit":0},
 "evStatus":{"batteryCharge":true,"batteryStatus":62,"batteryPlugin":1,
   "remainTime2":{"atc":{"value":95,"unit":1}},
   "drvDistance":[{"rangeByFuel":{"totalAvailableRange":{"value":309.0,"unit":1},"evModeRange":{"value":309.0,"unit":1}},"type":2}],
   "reservChargeInfos":{"targetSOClist":[{"plugType":0,"targetSOClevel":90},{"plugType":1,"targetSOClevel":80}]}},
 "steerWheelHeat":1,"sideBackWindowHeat":1,
 "battery":{"batSoc":89},"tirePressureLamp":{"tirePressureLampAll":0},
 "seatHeaterVentInfo":{"drvSeatHeatState":8,"astSeatHeatState":3,"rlSeatHeatState":2,"rrSeatHeatState":2}}
"""

/** Minimal in-memory SharedPreferences for tests. */
private class MemoryPrefs : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) map.clear()
            pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}

private object NoopRegistryOwner : ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {}
    }
}
