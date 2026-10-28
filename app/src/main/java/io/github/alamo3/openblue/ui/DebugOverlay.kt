package io.github.alamo3.openblue.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.CommandProgress
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.SeatLevel
import kotlin.math.roundToInt

/**
 * Developer mode: a draggable bug button that floats over every screen and opens a panel for
 * simulating the car's state and previewing command feedback.
 */
@Composable
fun BoxScope.DebugOverlay(vm: MainViewModel, initiallyOpen: Boolean = false) {
    if (!vm.developerMode) return
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    var drag by remember { mutableStateOf(Offset.Zero) }

    AnimatedVisibility(
        visible = open,
        enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(200)) { it / 3 } + fadeOut(tween(160)),
        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
    ) {
        DebugPanel(vm, onClose = { open = false })
    }

    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .padding(end = 12.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    drag += amount
                }
            }
            .shadow(6.dp, CircleShape)
            .size(48.dp)
            .clip(CircleShape)
            .background(if (vm.debug.simulate) AppTheme.colors.heat else MaterialTheme.colorScheme.primary)
            .pressable { open = !open },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.BugReport, contentDescription = "Developer panel", tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebugPanel(vm: MainViewModel, onClose: () -> Unit) {
    val d = vm.debug
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Developer panel", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
            }
            SettingSwitch("Simulate car status", "The app shows this panel's car instead of Bluelink's", d.simulate) { on ->
                vm.updateDebug { it.copy(simulate = on) }
            }
            SettingSwitch("Dry-run commands", "Lock, climate and camera play out without contacting the car", d.dryRun) { on ->
                vm.updateDebug { it.copy(dryRun = on) }
            }

            if (d.simulate) {
                DebugHeading("Doors and body")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Front left", "Front right", "Rear left", "Rear right").forEachIndexed { i, name ->
                        DebugToggle(name, d.doors[i]) { vm.updateDebug { it.withDoor(i, !it.doors[i]) } }
                    }
                    DebugToggle("Frunk", d.frunk) { vm.updateDebug { it.copy(frunk = !it.frunk) } }
                    DebugToggle("Tailgate", d.trunk) { vm.updateDebug { it.copy(trunk = !it.trunk) } }
                    DebugToggle("Windows", d.windowsOpen) { vm.updateDebug { it.copy(windowsOpen = !it.windowsOpen) } }
                    DebugToggle("Locked", d.locked) { vm.updateDebug { it.copy(locked = !it.locked) } }
                    DebugToggle("Tire warning", d.tireWarning) { vm.updateDebug { it.copy(tireWarning = !it.tireWarning) } }
                }

                DebugHeading("Climate")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DebugToggle("Climate on", d.climateOn) { vm.updateDebug { it.copy(climateOn = !it.climateOn) } }
                    DebugToggle("Defrost", d.defrost) { vm.updateDebug { it.copy(defrost = !it.defrost) } }
                    DebugToggle("Heated wheel", d.wheelHeat) { vm.updateDebug { it.copy(wheelHeat = !it.wheelHeat) } }
                }
                DebugSlider("Set temperature", "${ClimateSettings.fmt(d.tempC)} °C", d.tempC.toFloat(), 17f..27f, steps = 19) { v ->
                    vm.updateDebug { it.copy(tempC = Math.round(v * 2) / 2.0) }
                }
                Text("Seats (tap to cycle)", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf("Driver", "Pass.", "RL", "RR").forEachIndexed { i, name ->
                        val level = SeatLevel.of(d.seats[i])
                        SeatButton(level, name, { vm.updateDebug { it.withSeat(i, vm.caps.nextSeatLevel(i, it.seats[i]).code) } }, Modifier.weight(1f))
                    }
                }

                DebugHeading("Battery")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DebugToggle("Plugged in", d.pluggedIn) { vm.updateDebug { it.copy(pluggedIn = !it.pluggedIn) } }
                    DebugToggle("Charging", d.charging) { vm.updateDebug { it.copy(charging = !it.charging) } }
                }
                DebugSlider("Battery", "${d.battery}%", d.battery.toFloat(), 0f..100f) { v -> vm.updateDebug { it.copy(battery = v.roundToInt()) } }
                DebugSlider("12V battery", "${d.battery12v}%", d.battery12v.toFloat(), 0f..100f) { v -> vm.updateDebug { it.copy(battery12v = v.roundToInt()) } }
            }

            DebugHeading("Command feedback")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DebugToggle("Sending", vm.progress?.stage == CommandProgress.Stage.SENDING) { vm.previewProgress(CommandProgress.Stage.SENDING) }
                DebugToggle("Waiting", vm.progress?.stage == CommandProgress.Stage.WAITING) { vm.previewProgress(CommandProgress.Stage.WAITING) }
                DebugToggle("Done", vm.progress?.stage == CommandProgress.Stage.DONE) { vm.previewProgress(CommandProgress.Stage.DONE) }
                DebugToggle("Failed", vm.progress?.stage == CommandProgress.Stage.FAILED) { vm.previewProgress(CommandProgress.Stage.FAILED) }
                DebugToggle("Hide", false) { vm.clearProgressPreview() }
                DebugToggle("Test message", false) { vm.message = "This is a test message from the developer panel" }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DebugHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun DebugToggle(label: String, on: Boolean, onClick: () -> Unit) {
    OptionPill(label, on, onClick)
}

@Composable
private fun DebugSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
    Slider(value = current, onValueChange = onChange, valueRange = range, steps = steps)
}
