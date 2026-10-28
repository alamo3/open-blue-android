package io.github.alamo3.openblue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.Heating
import io.github.alamo3.openblue.api.SeatLevel

private val DURATIONS = listOf(5, 10, 15, 20, 25, 30)

@Composable
fun ClimateScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val e = vm.editor
    val busy = vm.busy != null
    var saveDialog by remember { mutableStateOf(false) }
    val temps = vm.tempTable.filterNot { it.isLimit }.mapNotNull { it.celsiusValue }.distinct().sorted()
    fun update(block: (ClimateSettings) -> ClimateSettings) { vm.editor = block(vm.editor) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Climate") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                FilledTonalIconButton(
                    onClick = { update { s -> s.copy(tempC = temps.lastOrNull { it < s.tempC } ?: s.tempC) } },
                    enabled = e.climateOn,
                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text(
                    "${ClimateSettings.fmt(e.tempC)} °C",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = { update { s -> s.copy(tempC = temps.firstOrNull { it > s.tempC } ?: s.tempC) } },
                    enabled = e.climateOn,
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
            SwitchRow("Climate (A/C / heat)", e.climateOn) { on -> update { it.copy(climateOn = on) } }
            SwitchRow("Front defrost", e.defrost) { on -> update { it.copy(defrost = on) } }
            val heatingOptions = vm.caps.heatingOptions()
            val heatingSelected = Heating.of(e.heating).takeIf { it in heatingOptions }
                ?: if (e.heating == 0) Heating.OFF else heatingOptions.last()
            Picker(
                label = "Heated steering wheel / rear window / mirrors",
                options = heatingOptions,
                selected = heatingSelected,
                optionLabel = { it.label },
                onSelect = { h -> update { it.copy(heating = h.code) } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Picker(
                label = "Run for",
                options = (DURATIONS + e.durationMin).distinct().sorted(),
                selected = e.durationMin,
                optionLabel = { "$it minutes" },
                onSelect = { d -> update { it.copy(durationMin = d) } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        SectionCard("Heated / ventilated seats") {
            val caps = vm.caps
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker("Driver", caps.seatLevels(0), SeatLevel.of(e.driverSeat), { it.label },
                    { l -> update { it.copy(driverSeat = l.code) } }, Modifier.weight(1f))
                Picker("Passenger", caps.seatLevels(1), SeatLevel.of(e.passengerSeat), { it.label },
                    { l -> update { it.copy(passengerSeat = l.code) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Picker("Rear left", caps.seatLevels(2), SeatLevel.of(e.rearLeftSeat), { it.label },
                    { l -> update { it.copy(rearLeftSeat = l.code) } }, Modifier.weight(1f))
                Picker("Rear right", caps.seatLevels(3), SeatLevel.of(e.rearRightSeat), { it.label },
                    { l -> update { it.copy(rearRightSeat = l.code) } }, Modifier.weight(1f))
            }
            Text(
                "Options are limited to what Bluelink reports for each seat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.startClimate() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Start climate") }
            OutlinedButton(onClick = { vm.stopClimate() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Stop") }
        }
        TextButton(onClick = { saveDialog = true }) { Text("Save these settings as a preset on this phone") }

        SectionCard("Bluelink presets (from mybluelink.ca)") {
            if (vm.serverPresets.isEmpty()) {
                Text("None loaded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.serverPresets.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PresetRow(
                    title = p.settings.name + if (p.isDefault) "  (default)" else "",
                    settings = p.settings,
                    enabled = !busy,
                    onStart = { vm.startClimate(p.settings) },
                    onEdit = { vm.editPreset(p.settings) },
                )
            }
            TextButton(onClick = { vm.loadPresets() }, enabled = !busy) { Text("Reload") }
        }

        SectionCard("Saved on this phone") {
            if (vm.localPresets.isEmpty()) {
                Text("Nothing saved yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.localPresets.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PresetRow(
                    title = p.name,
                    settings = p,
                    enabled = !busy,
                    onStart = { vm.startClimate(p) },
                    onEdit = { vm.editPreset(p) },
                    onDelete = { vm.deleteLocalPreset(p) },
                )
            }
        }
    }

    if (saveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { vm.saveLocalPreset(name); saveDialog = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PresetRow(
    title: String,
    settings: ClimateSettings,
    enabled: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(settings.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = enabled) { Text("Start") }
            TextButton(onClick = onEdit) { Text("Edit") }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
