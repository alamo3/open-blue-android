package io.github.alamo3.openblue.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.Heating
import io.github.alamo3.openblue.api.SeatLevel

private val DURATIONS = listOf(10, 15, 20, 30)
private val SEAT_NAMES = listOf("Driver", "Passenger", "Rear left", "Rear right")

@Composable
fun ClimateScreen(vm: MainViewModel, onBack: () -> Unit) {
    val e = vm.editor
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    val st = vm.status?.let { CarStatus.from(it, vm.tempTable) }
    val running = st?.climateOn == true
    val temps = vm.tempTable.filterNot { it.isLimit }.mapNotNull { it.celsiusValue }.distinct().sorted()
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Climate", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (running) "Climate is on" + (st?.setTempC?.let { " at ${ClimateSettings.fmt(it)} °C" } ?: "")
                else "Climate is off",
                style = MaterialTheme.typography.bodyLarge,
                color = if (running) (if (e.tempC >= 20) app.heat else app.cool) else scheme.onSurfaceVariant,
            )

            // Temperature
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton(Icons.Rounded.KeyboardArrowLeft, "Cooler") {
                    vm.updateEditor { s -> s.copy(tempC = temps.lastOrNull { it < s.tempC } ?: s.tempC) }
                }
                AnimatedContent(
                    targetState = e.tempC,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(tween(220)) { h -> if (up) h / 2 else -h / 2 } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(tween(180)) { h -> if (up) -h / 2 else h / 2 } + fadeOut(tween(180)))
                    },
                    label = "temp",
                    modifier = Modifier.widthIn(min = 170.dp),
                ) { t ->
                    Text(
                        "${ClimateSettings.fmt(t)}°",
                        style = MaterialTheme.typography.displayLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                StepButton(Icons.Rounded.KeyboardArrowRight, "Warmer") {
                    vm.updateEditor { s -> s.copy(tempC = temps.firstOrNull { it > s.tempC } ?: s.tempC) }
                }
            }

            // Power
            Spacer(Modifier.height(16.dp))
            PowerButton(
                running = running,
                enabled = vm.canAct,
                onClick = { if (running) vm.stopClimate() else vm.startClimate() },
            )
            Text(
                if (running) "Stop" else "Start for ${e.durationMin} min",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // Seats
            Spacer(Modifier.height(24.dp))
            CabinMap(
                seats = e.seats.map { SeatLevel.of(it) },
                onSeat = { vm.cycleSeat(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tap a seat to change it",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // Extras
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToggleChip(Icons.Rounded.Window, "Front defrost", e.defrost, { vm.updateEditor { it.copy(defrost = !it.defrost) } }, app.heat)
                if (vm.caps.splitHeating) {
                    val wheel = e.heating == Heating.STEERING.code || e.heating == Heating.STEERING_REAR_MIRRORS.code
                    val rear = e.heating == Heating.REAR_MIRRORS.code || e.heating == Heating.STEERING_REAR_MIRRORS.code
                    ToggleChip(Icons.Rounded.LocalFireDepartment, "Heated wheel", wheel, { vm.updateEditor { it.copy(heating = splitCode(!wheel, rear)) } }, app.heat)
                    ToggleChip(Icons.Rounded.LocalFireDepartment, "Rear window & mirrors", rear, { vm.updateEditor { it.copy(heating = splitCode(wheel, !rear)) } }, app.heat)
                } else {
                    val on = e.heating != 0
                    ToggleChip(
                        Icons.Rounded.LocalFireDepartment, "Heated wheel, mirrors & rear window", on,
                        { vm.updateEditor { it.copy(heating = if (on) 0 else Heating.ALL.code) } }, app.heat,
                    )
                }
            }

            // Duration
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Run for", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (DURATIONS + e.durationMin).distinct().sorted().forEach { d ->
                        OptionPill("$d min", e.durationMin == d, { vm.updateEditor { it.copy(durationMin = d) } })
                    }
                }
            }

            // Presets
            Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Presets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { saving = true }) { Text("Save current") }
            }
            val presets = vm.serverPresets.map { Triple(it.settings, true, it.isDefault) } +
                vm.localPresets.map { Triple(it, false, false) }
            if (presets.isEmpty()) {
                Text(
                    "Save these settings to start them in one tap. Presets from mybluelink.ca show up here too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    presets.forEach { (p, fromServer, isDefault) ->
                        PresetCard(
                            settings = p,
                            caption = when {
                                isDefault -> "Bluelink default"
                                fromServer -> "From Bluelink"
                                else -> "On this phone"
                            },
                            enabled = vm.canAct,
                            onStart = { vm.startClimate(p) },
                            onEdit = { vm.editPreset(p) },
                            onDelete = if (fromServer) null else ({ vm.deleteLocalPreset(p) }),
                        )
                    }
                }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save preset") },
            text = {
                Column {
                    Text(e.summary(), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveLocalPreset(name); saving = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
}

private fun splitCode(wheel: Boolean, rear: Boolean) = when {
    wheel && rear -> Heating.STEERING_REAR_MIRRORS.code
    wheel -> Heating.STEERING.code
    rear -> Heating.REAR_MIRRORS.code
    else -> Heating.OFF.code
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(32.dp)) }
}

@Composable
private fun PowerButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    val bg by animateColorAsState(if (running) app.alert.copy(alpha = 0.14f) else scheme.primary, tween(300), label = "powerBg")
    val fg by animateColorAsState(if (running) app.alert else scheme.onPrimary, tween(300), label = "powerFg")
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(bg)
            .pressable(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = if (running) "Stop climate" else "Start climate", tint = fg, modifier = Modifier.size(38.dp))
    }
}

/** Top-down cabin: a car body outline with the four seats where they sit (left-hand drive). */
@Composable
private fun CabinMap(seats: List<SeatLevel>, onSeat: (Int) -> Unit, modifier: Modifier) {
    val outline = AppTheme.colors.hairline
    val glass = MaterialTheme.colorScheme.outline
    Box(modifier.height(330.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(250.dp).height(330.dp)) {
            val stroke = 3.dp.toPx()
            val r = 70.dp.toPx()
            drawRoundRect(outline, cornerRadius = CornerRadius(r, r), style = Stroke(stroke))
            // Windshield and rear glass
            val inset = 34.dp.toPx()
            drawLine(glass, Offset(inset, 62.dp.toPx()), Offset(size.width - inset, 62.dp.toPx()), stroke, StrokeCap.Round)
            drawLine(glass, Offset(inset + 8.dp.toPx(), size.height - 44.dp.toPx()), Offset(size.width - inset - 8.dp.toPx(), size.height - 44.dp.toPx()), stroke, StrokeCap.Round)
            // Mirrors
            val my = 78.dp.toPx()
            drawRoundRect(outline, Offset(-14.dp.toPx(), my), Size(14.dp.toPx(), 22.dp.toPx()), CornerRadius(6.dp.toPx()))
            drawRoundRect(outline, Offset(size.width, my), Size(14.dp.toPx(), 22.dp.toPx()), CornerRadius(6.dp.toPx()))
        }
        Column(
            Modifier.width(250.dp).padding(horizontal = 22.dp).padding(top = 52.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Front",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            for (row in 0..1) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (col in 0..1) {
                        val i = row * 2 + col
                        SeatButton(seats[i], SEAT_NAMES[i], { onSeat(i) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    settings: ClimateSettings,
    caption: String,
    enabled: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(220.dp)
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceContainerHigh)
            .border(1.dp, AppTheme.colors.hairline, MaterialTheme.shapes.large)
            .padding(16.dp),
    ) {
        Text(settings.name, style = MaterialTheme.typography.titleMedium)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(settings.summary(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, minLines = 3, maxLines = 3)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(CircleShape).background(scheme.primary).pressable(enabled, onStart).padding(horizontal = 18.dp, vertical = 9.dp),
            ) { Text("Start", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Load into controls") }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete") }
        }
    }
}
