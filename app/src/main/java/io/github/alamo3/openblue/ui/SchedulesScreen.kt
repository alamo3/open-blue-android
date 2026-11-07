package io.github.alamo3.openblue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ChargeWindow
import io.github.alamo3.openblue.api.ClimateSchedule
import io.github.alamo3.openblue.api.ClimateSlot
import io.github.alamo3.openblue.api.DAY_NAMES
import io.github.alamo3.openblue.api.TimeOfDay
import io.github.alamo3.openblue.api.daysLabel

/**
 * Climate starts and the daily charging window. They're stored on Bluelink's servers, so they run
 * whether or not the phone is on; this screen just reads and edits them.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SchedulesScreen(vm: MainViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadSchedules() }
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Schedules", onBack)
        if (vm.schedulesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "These are kept on Bluelink's servers, so they run even when your phone is off. " +
                    "Times are the car's local time.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )

            SectionTitle("Climate starts")
            val climate = vm.climateSchedule
            when {
                climate != null -> ClimateEditor(vm, climate)
                vm.climateScheduleError != null -> ErrorLine("Couldn't read the climate schedule: ${vm.climateScheduleError}") { vm.loadSchedules() }
                !vm.schedulesLoading -> Text("Not loaded yet.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }

            if (vm.hasChargeSchedule) {
                HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 20.dp))
                SectionTitle("Charging window")
                val charge = vm.chargeSchedule
                when {
                    charge != null -> ChargeEditor(vm, charge)
                    vm.chargeScheduleError != null -> ErrorLine("Couldn't read the charging schedule: ${vm.chargeScheduleError}") { vm.loadSchedules() }
                    !vm.schedulesLoading -> Text("Not loaded yet.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ErrorLine(text: String, retry: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = retry) { Text("Try again") }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ClimateEditor(vm: MainViewModel, saved: ClimateSchedule) {
    var draft by remember(saved) { mutableStateOf(saved) }
    val temps = vm.tempTable.filterNot { it.isLimit }.mapNotNull { it.celsiusValue }.distinct().sorted()
    SlotEditor("Schedule 1", draft.first, temps) { draft = draft.copy(first = it) }
    HorizontalDivider(color = AppTheme.colors.hairline, modifier = Modifier.padding(vertical = 12.dp))
    SlotEditor("Schedule 2", draft.second, temps) { draft = draft.copy(second = it) }
    Spacer(Modifier.height(12.dp))
    Button(onClick = { vm.saveClimateSchedule(draft) }, enabled = vm.canAct && draft != saved) { Text("Save climate schedule") }
    Text(
        "Bluelink's schedules don't include seat or steering-wheel heating.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SlotEditor(title: String, slot: ClimateSlot, temps: List<Double>, onChange: (ClimateSlot) -> Unit) {
    SettingSwitch(
        title,
        if (slot.on) "${slot.time.label} · ${daysLabel(slot.days)} · ${slot.tempC.fmt()} °C" else "Off",
        slot.on,
    ) { onChange(slot.copy(on = it)) }
    if (!slot.on) return
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text("Start at", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { picking = true }) { Text(slot.time.label) }
    }
    if (picking) TimeDialog(slot.time, { picking = false }) { onChange(slot.copy(time = it)) }

    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        DAY_NAMES.forEachIndexed { i, name ->
            OptionPill(name, selected = i in slot.days, onClick = {
                onChange(slot.copy(days = if (i in slot.days) slot.days - i else slot.days + i))
            })
        }
    }
    if (slot.days.isEmpty()) {
        Text(
            "No days picked: it runs next time the time comes round, then stops.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text("Temperature", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        val i = temps.indexOfFirst { it >= slot.tempC }.let { if (it < 0) temps.lastIndex else it }
        TextButton(onClick = { temps.getOrNull(i - 1)?.let { onChange(slot.copy(tempC = it)) } }, enabled = i > 0) { Text("−") }
        Text("${(temps.getOrNull(i) ?: slot.tempC).fmt()} °C", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { temps.getOrNull(i + 1)?.let { onChange(slot.copy(tempC = it)) } }, enabled = i < temps.lastIndex) { Text("+") }
    }
    SettingSwitch("Defrost", null, slot.defrost) { onChange(slot.copy(defrost = it)) }
}

@Composable
private fun ChargeEditor(vm: MainViewModel, saved: ChargeWindow) {
    var draft by remember(saved) { mutableStateOf(saved) }
    SettingSwitch(
        "Only charge in a window",
        if (draft.on) "${draft.start.label} to ${draft.end.label}, every day" else "Charges whenever it's plugged in",
        draft.on,
    ) { draft = draft.copy(on = it) }
    if (draft.on) {
        var pick by remember { mutableStateOf<String?>(null) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Start", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { pick = "start" }) { Text(draft.start.label) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Stop", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { pick = "end" }) { Text(draft.end.label) }
        }
        when (pick) {
            "start" -> TimeDialog(draft.start, { pick = null }) { draft = draft.copy(start = it) }
            "end" -> TimeDialog(draft.end, { pick = null }) { draft = draft.copy(end = it) }
        }
        SettingSwitch("Off-peak electricity only", "Where the car reports off-peak hours", draft.offPeak) { draft = draft.copy(offPeak = it) }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { vm.saveChargeSchedule(draft) }, enabled = vm.canAct && draft != saved) { Text("Save charging window") }
    Text(
        "Every day, the same window. Switch it off to charge whenever the car is plugged in.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: TimeOfDay, onDismiss: () -> Unit, onPick: (TimeOfDay) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(TimeOfDay(state.hour, state.minute)); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { TimePicker(state = state) } },
    )
}

private fun Double.fmt(): String = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)
