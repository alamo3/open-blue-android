package io.github.alamo3.openblue

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alamo3.openblue.ui.CameraScreen
import io.github.alamo3.openblue.ui.ChargingScreen
import io.github.alamo3.openblue.ui.ChargersScreen
import io.github.alamo3.openblue.ui.ChargeHistoryScreen
import io.github.alamo3.openblue.ui.ClimateScreen
import io.github.alamo3.openblue.ui.DebugOverlay
import io.github.alamo3.openblue.ui.CommandStrip
import io.github.alamo3.openblue.ui.HomeScreen
import io.github.alamo3.openblue.ui.LocationScreen
import io.github.alamo3.openblue.ui.LoginScreen
import io.github.alamo3.openblue.ui.OpenBlueTheme
import io.github.alamo3.openblue.ui.OtpScreen
import io.github.alamo3.openblue.ui.Route
import io.github.alamo3.openblue.ui.SettingsScreen
import io.github.alamo3.openblue.ui.ProbeScreen
import io.github.alamo3.openblue.ui.TripScreen
import io.github.alamo3.openblue.ui.VehicleScreen
import io.github.alamo3.openblue.ui.VehiclePickerScreen
import io.github.alamo3.openblue.ui.confirmIdentity

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenBlueTheme {
                App()
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val activity = LocalContext.current as? FragmentActivity
    val stack = remember { mutableStateListOf(Route.Home) }
    var forward by remember { mutableStateOf(true) }
    val screen = vm.screen

    fun open(r: Route) {
        forward = true
        stack.add(r)
    }

    fun back() {
        if (stack.size > 1) {
            forward = false
            stack.removeAt(stack.lastIndex)
        }
    }

    fun unlock() {
        val name = vm.vehicle?.nickname ?: "your car"
        if (vm.confirmUnlock && activity != null) {
            activity.confirmIdentity("Unlock $name", "Confirm it's you") { vm.unlock() }
        } else {
            vm.unlock()
        }
    }

    BackHandler(enabled = screen is Screen.Main && stack.size > 1) { back() }

    // Android 13+ needs permission for notifications; ask once after signing in.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(screen is Screen.Main) {
        if (screen is Screen.Main && Build.VERSION.SDK_INT >= 33 && vm.takeNotificationPrompt()) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(vm.message) {
        // Clear only after showing: clearing first would change the key and cancel this effect.
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    var lastProgress by remember { mutableStateOf<CommandProgress?>(null) }
    LaunchedEffect(vm.progress) { vm.progress?.let { lastProgress = it } }

    LaunchedEffect(screen) {
        if (screen !is Screen.Main) {
            stack.clear()
            stack.add(Route.Home)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.Login -> LoginScreen(vm)
                is Screen.Otp -> OtpScreen(vm, screen)
                is Screen.PickVehicle -> VehiclePickerScreen(vm, screen.vehicles)
                Screen.Main -> AnimatedContent(
                    targetState = stack.last(),
                    transitionSpec = {
                        val spec = tween<androidx.compose.ui.unit.IntOffset>(320, easing = FastOutSlowInEasing)
                        if (forward) {
                            (slideInHorizontally(spec) { it / 3 } + fadeIn(tween(240))) togetherWith
                                (slideOutHorizontally(spec) { -it / 6 } + fadeOut(tween(160)))
                        } else {
                            (slideInHorizontally(spec) { -it / 6 } + fadeIn(tween(240))) togetherWith
                                (slideOutHorizontally(spec) { it / 3 } + fadeOut(tween(160)))
                        }
                    },
                    label = "route",
                ) { route ->
                    when (route) {
                        Route.Home -> HomeScreen(vm, open = ::open, onUnlock = ::unlock)
                        Route.Climate -> ClimateScreen(vm, onBack = ::back)
                        Route.Charging -> ChargingScreen(vm, onBack = ::back, open = ::open)
                        Route.Chargers -> ChargersScreen(vm, onBack = ::back)
                        Route.ChargeHistory -> ChargeHistoryScreen(vm, onBack = ::back)
                        Route.Trip -> TripScreen(vm, onBack = ::back)
                        Route.Location -> LocationScreen(vm, onBack = ::back)
                        Route.Camera -> CameraScreen(vm, onBack = ::back)
                        Route.Vehicle -> VehicleScreen(vm, onBack = ::back)
                        Route.Settings -> SettingsScreen(vm, onBack = ::back, open = ::open)
                        Route.Probe -> ProbeScreen(vm, onBack = ::back)
                        Route.Sound -> io.github.alamo3.openblue.ui.SoundScreen(vm, onBack = ::back)
                    }
                }
            }

            if (vm.busy != null && screen is Screen.Main) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }

            AnimatedVisibility(
                visible = vm.progress != null,
                enter = slideInVertically(tween(280)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                // While animating out, progress is already null, so keep showing the last value.
                (vm.progress ?: lastProgress)?.let { CommandStrip(it, onDismiss = { vm.dismissProgress() }) }
            }

            // Developer mode: floating panel over every screen.
            DebugOverlay(vm)
        }
    }
}
