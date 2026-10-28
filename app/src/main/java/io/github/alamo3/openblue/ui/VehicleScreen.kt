package io.github.alamo3.openblue.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.SeatLevel

@Composable
fun VehicleScreen(vm: MainViewModel, onBack: () -> Unit) {
    val st = vm.status?.let { CarStatus.from(it, vm.tempTable) }
    val app = AppTheme.colors
    val caps = vm.caps
    val v = vm.vehicle

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Vehicle", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 120.dp)) {
            if (st == null) {
                Text("No status yet. Pull down on the home screen to load it.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            fun state(open: Boolean) = if (open) "Open" else "Closed"

            SectionTitle("Doors and windows")
            DetailRow("Locks", when (st.locked) { true -> "Locked"; false -> "Unlocked"; null -> "Unknown" },
                if (st.locked == false) app.alert else MaterialTheme.colorScheme.onSurface)
            DetailRow("Doors", if (st.openDoors.isEmpty()) "Closed" else "Open: ${st.openDoors.joinToString()}",
                if (st.openDoors.isEmpty()) MaterialTheme.colorScheme.onSurface else app.alert)
            DetailRow("Windows", if (st.openWindows.isEmpty()) "Closed" else "Open: ${st.openWindows.joinToString()}",
                if (st.openWindows.isEmpty()) MaterialTheme.colorScheme.onSurface else app.alert)
            DetailRow("Trunk", state(st.trunkOpen), if (st.trunkOpen) app.alert else MaterialTheme.colorScheme.onSurface)
            DetailRow("Frunk", state(st.frunkOpen), if (st.frunkOpen) app.alert else MaterialTheme.colorScheme.onSurface)

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            SectionTitle("Battery and charging")
            DetailRow("Battery", st.battery?.let { "$it%" } ?: "Unknown")
            DetailRow("Range", st.range?.let { "$it ${st.rangeUnit}" } ?: "Unknown")
            DetailRow("Charging", chargingLine(st), if (st.charging) app.charge else MaterialTheme.colorScheme.onSurface)
            if (st.chargeLimitAc != null) DetailRow("Charge limit, AC", "${st.chargeLimitAc}%")
            if (st.chargeLimitDc != null) DetailRow("Charge limit, DC fast", "${st.chargeLimitDc}%")
            st.battery12v?.let { DetailRow("12V battery", "$it%", if (it < 50) app.alert else MaterialTheme.colorScheme.onSurface) }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            SectionTitle("Cabin")
            DetailRow("Climate", if (st.climateOn) "On" else "Off")
            DetailRow("Front defrost", if (st.defrost) "On" else "Off")
            DetailRow("Heated wheel", if (st.wheelHeat) "On" else "Off")
            listOf("Driver seat", "Passenger seat", "Rear left seat", "Rear right seat").forEachIndexed { i, name ->
                DetailRow(name, SeatLevel.statusLabel(st.seats.getOrNull(i)))
            }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            SectionTitle("Car")
            DetailRow("Tire pressure", if (st.tireWarning) "Check tires" else "OK", if (st.tireWarning) app.alert else MaterialTheme.colorScheme.onSurface)
            caps.odometer?.let { DetailRow("Odometer", "${it.toInt()} ${if (caps.odometerUnit == 3) "mi" else "km"}") }
            if (v != null) DetailRow("Model", listOfNotNull(v.year, v.model.ifBlank { null }, caps.trim).joinToString(" "))
            if (v != null) DetailRow("VIN", v.vin)

            Spacer(Modifier.height(16.dp))
            Text(
                "Last reported by the car ${relativeTime(st.updatedAt)}. To ask the car directly, wake it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { vm.wakeCar() }, enabled = vm.canAct, modifier = Modifier.padding(top = 12.dp)) {
                Text("Wake car for live status")
            }
        }
    }
}
