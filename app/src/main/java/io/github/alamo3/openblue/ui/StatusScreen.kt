package io.github.alamo3.openblue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.SeatLevel
import io.github.alamo3.openblue.api.TempTables
import io.github.alamo3.openblue.api.bool
import io.github.alamo3.openblue.api.dbl
import io.github.alamo3.openblue.api.int
import io.github.alamo3.openblue.api.str
import kotlinx.serialization.json.JsonObject

private fun onOff(b: Boolean?) = when (b) { true -> "On"; false -> "Off"; null -> "—" }

/** lastStatusDate is "yyyyMMddHHmmss" in whatever zone the car is set to. */
private fun formatStatusDate(raw: String?): String {
    if (raw == null || raw.length < 12) return "unknown"
    return "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)} ${raw.substring(8, 10)}:${raw.substring(10, 12)}"
}

@Composable
fun StatusScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val s: JsonObject? = vm.status
    val busy = vm.busy != null
    val v = vm.vehicle

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (v != null) {
            Text(v.nickname, style = MaterialTheme.typography.headlineSmall)
            Text("${v.year} ${v.model}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (s == null) {
            Text("No status yet. Tap Refresh.")
        } else {
            val battery = s.int("evStatus.batteryStatus")
            val range = s.dbl("evStatus.drvDistance.0.rangeByFuel.totalAvailableRange.value")
                ?: s.dbl("evStatus.drvDistance.0.rangeByFuel.evModeRange.value")
            val rangeUnit = (s.int("evStatus.drvDistance.0.rangeByFuel.totalAvailableRange.unit")
                ?: s.int("evStatus.drvDistance.0.rangeByFuel.evModeRange.unit")).let { if (it == 3) "mi" else "km" }
            val charging = s.bool("evStatus.batteryCharge") == true
            val plugged = (s.int("evStatus.batteryPlugin") ?: 0) != 0

            SectionCard("Battery") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        battery?.let { "$it%" } ?: "—",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        range?.let { "  ${it.toInt()} $rangeUnit" } ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    when {
                        charging -> "Charging"
                        plugged -> "Plugged in, not charging"
                        else -> "Not plugged in"
                    }
                )
                s.int("battery.batSoc")?.let { InfoRow("12V battery", "$it%") }
            }

            SectionCard("Doors") {
                InfoRow("Locks", when (s.bool("doorLock")) { true -> "Locked"; false -> "UNLOCKED"; null -> "—" })
                val open = listOf(
                    "Front left" to "doorOpen.frontLeft", "Front right" to "doorOpen.frontRight",
                    "Rear left" to "doorOpen.backLeft", "Rear right" to "doorOpen.backRight",
                ).filter { (s.int(it.second) ?: 0) != 0 }.map { it.first }
                InfoRow("Doors", if (open.isEmpty()) "Closed" else "Open: ${open.joinToString()}")
                InfoRow("Trunk", if (s.bool("trunkOpen") == true) "Open" else "Closed")
                InfoRow("Frunk/hood", if (s.bool("hoodOpen") == true) "Open" else "Closed")
            }

            SectionCard("Climate") {
                val temp = TempTables.celsiusForHex(vm.tempTable, s.str("airTemp.value"))
                InfoRow("Climate", onOff(s.bool("airCtrlOn")) + (temp?.let { " · ${it} °C" } ?: ""))
                InfoRow("Front defrost", onOff(s.bool("defrost")))
                s.int("steerWheelHeat")?.let { InfoRow("Steering wheel heat", onOff(it != 0)) }
                s.int("sideBackWindowHeat")?.let { InfoRow("Rear window / mirrors", onOff(it != 0)) }
                InfoRow("Driver seat", SeatLevel.statusLabel(s.int("seatHeaterVentInfo.drvSeatHeatState")))
                InfoRow("Passenger seat", SeatLevel.statusLabel(s.int("seatHeaterVentInfo.astSeatHeatState")))
                InfoRow("Rear left seat", SeatLevel.statusLabel(s.int("seatHeaterVentInfo.rlSeatHeatState")))
                InfoRow("Rear right seat", SeatLevel.statusLabel(s.int("seatHeaterVentInfo.rrSeatHeatState")))
            }

            Text(
                "Last reported by car: ${formatStatusDate(s.str("lastStatusDate"))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.lock() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                Text("  Lock")
            }
            FilledTonalButton(onClick = { vm.unlock() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Unlock")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.refresh(live = false) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text("  Refresh")
            }
            OutlinedButton(onClick = { vm.refresh(live = true) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Wake car")
            }
        }
        Text(
            "Refresh shows what Bluelink last stored. Wake car asks the car directly (slower, and uses a little 12V battery).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
