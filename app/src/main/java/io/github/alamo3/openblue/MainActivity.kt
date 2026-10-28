package io.github.alamo3.openblue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alamo3.openblue.ui.ClimateScreen
import io.github.alamo3.openblue.ui.LoginScreen
import io.github.alamo3.openblue.ui.OpenBlueTheme
import io.github.alamo3.openblue.ui.OtpScreen
import io.github.alamo3.openblue.ui.SettingsScreen
import io.github.alamo3.openblue.ui.StatusScreen
import io.github.alamo3.openblue.ui.VehiclePickerScreen

class MainActivity : ComponentActivity() {
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

private val TABS = listOf("Status", "Climate", "Settings")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val screen = vm.screen

    LaunchedEffect(vm.message) {
        // Clear only after showing: clearing first would change the key and cancel this effect.
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (screen is Screen.Main) {
                TopAppBar(title = { Text(TABS[tab]) })
            }
        },
        bottomBar = {
            if (screen is Screen.Main) {
                NavigationBar {
                    TABS.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = {
                                Icon(
                                    when (i) { 0 -> Icons.Filled.Home; 1 -> Icons.Filled.Star; else -> Icons.Filled.Settings },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            vm.busy?.let { label ->
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when (screen) {
                Screen.Login -> LoginScreen(vm)
                is Screen.Otp -> OtpScreen(vm, screen)
                is Screen.PickVehicle -> VehiclePickerScreen(vm, screen.vehicles)
                Screen.Main -> when (tab) {
                    0 -> StatusScreen(vm)
                    1 -> ClimateScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}
