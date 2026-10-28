package io.github.alamo3.openblue.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ClimateSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, open: (Route) -> Unit, onUnlock: () -> Unit) {
    val st = vm.status?.let { CarStatus.from(it, vm.tempTable) }
    val app = AppTheme.colors
    val v = vm.vehicle
    val caps = vm.caps

    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 120.dp),
        ) {
            // Name and freshness
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(v?.nickname ?: "Your car", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Updated ${relativeTime(st?.updatedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Avatar(caps.photoUrl, v?.nickname.orEmpty(), onClick = { open(Route.Settings) })
            }

            // Battery and range
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(st?.battery?.let { "$it%" } ?: "–", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(12.dp))
                Text(
                    st?.range?.let { "$it ${st.rangeUnit}" } ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                if (st?.charging == true) {
                    Icon(Icons.Rounded.Bolt, contentDescription = "Charging", tint = app.charge, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            BatteryBar(st?.battery, st?.charging == true, Modifier.fillMaxWidth())
            if (st != null && (st.charging || st.pluggedIn)) {
                Text(
                    chargingLine(st),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (st.charging) app.charge else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // The car, in 3D: doors, frunk and tailgate follow the status; airflow shows climate,
            // and the charging cable shows when it's plugged in.
            Car3D(st.toPose(vm.editor.tempC), Modifier.fillMaxWidth().height(290.dp))

            // One-tap controls
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val locked = st?.locked
                RoundControl(
                    icon = if (locked == false) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                    label = when (locked) { true -> "Locked"; false -> "Unlocked"; null -> "Lock" },
                    active = locked == false,
                    tint = app.alert,
                    enabled = vm.canAct,
                    onClick = { if (locked == true) onUnlock() else vm.lock() },
                )
                // Tap opens the climate screen; press and hold starts or stops with the last settings.
                val climateOn = st?.climateOn == true
                RoundControl(
                    icon = Icons.Rounded.Air,
                    label = if (climateOn) (st?.setTempC?.let { "${ClimateSettings.fmt(it)}° on" } ?: "On") else "Climate",
                    active = climateOn,
                    tint = if ((st?.setTempC ?: vm.editor.tempC) >= 20) app.heat else app.cool,
                    onClick = { open(Route.Climate) },
                    onLongClick = { if (vm.canAct) { if (climateOn) vm.stopClimate() else vm.startClimate() } },
                )
                if (caps.hasSvm) {
                    RoundControl(icon = Icons.Rounded.PhotoCamera, label = "Camera", onClick = { open(Route.Camera) })
                }
                RoundControl(icon = Icons.Rounded.Sync, label = "Wake car", enabled = vm.canAct, onClick = { vm.wakeCar() })
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = app.hairline)
            Spacer(Modifier.height(8.dp))

            NavRow(Icons.Rounded.DirectionsCar, "Vehicle", st?.let { vehicleLine(it) }, onClick = { open(Route.Vehicle) },
                iconTint = if (st != null && needsAttention(st)) app.alert else MaterialTheme.colorScheme.onSurface)
        }
    }
}

fun chargingLine(st: CarStatus): String = when {
    st.charging && st.chargeMinutesLeft != null -> "Charging, full in ${formatMinutes(st.chargeMinutesLeft)}"
    st.charging -> "Charging"
    st.pluggedIn -> "Plugged in, not charging"
    else -> "Not plugged in"
}

fun needsAttention(st: CarStatus) =
    st.locked == false || st.openDoors.isNotEmpty() || st.openWindows.isNotEmpty() || st.trunkOpen || st.frunkOpen || st.tireWarning

fun vehicleLine(st: CarStatus): String {
    val issues = buildList {
        if (st.openDoors.isNotEmpty()) add(if (st.openDoors.size == 1) "a door is open" else "${st.openDoors.size} doors open")
        if (st.openWindows.isNotEmpty()) add("windows open")
        if (st.trunkOpen) add("trunk open")
        if (st.frunkOpen) add("frunk open")
        if (st.tireWarning) add("check tire pressure")
    }
    return if (issues.isEmpty()) "Everything closed" + (st.battery12v?.let { ", 12V at $it%" } ?: "")
    else issues.joinToString(", ").replaceFirstChar { it.uppercase() }
}
