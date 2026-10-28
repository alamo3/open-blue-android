package io.github.alamo3.openblue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import kotlin.math.roundToInt

/**
 * Charging: battery and charge state, the charge port door, AC and DC charge limits, and battery
 * preconditioning for faster DC fast charging.
 */
@Composable
fun ChargingScreen(vm: MainViewModel, onBack: () -> Unit) {
    val st = vm.status?.let { CarStatus.from(it, vm.tempTable) }
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Charging", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 120.dp),
        ) {
            // Battery
            Row(verticalAlignment = Alignment.Bottom) {
                Text(st?.battery?.let { "$it%" } ?: "–", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(12.dp))
                Text(
                    st?.range?.let { "$it ${st.rangeUnit}" } ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            BatteryBar(st?.battery, st?.charging == true, Modifier.fillMaxWidth())
            if (st != null) {
                Text(
                    chargingLine(st),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (st.charging) app.charge else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Charge port
            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 20.dp))
            SectionTitle("Charge port")
            val portOpen = st?.chargePortOpen
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (portOpen) { true -> "Open"; false -> "Closed"; null -> "State unknown" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (st?.pluggedIn == true) "A cable is plugged in" else "Rear right, behind the wheel",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (portOpen == null) {
                    // The car doesn't say which way it is, so offer both.
                    ActionPill("Close", enabled = vm.canAct, primary = false) { vm.chargePort(open = false) }
                    Spacer(Modifier.width(8.dp))
                    ActionPill("Open", enabled = vm.canAct) { vm.chargePort(open = true) }
                } else {
                    ActionPill(if (portOpen) "Close" else "Open", enabled = vm.canAct) { vm.chargePort(open = !portOpen) }
                }
            }

            // Charge limits
            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 16.dp))
            SectionTitle("Charge limits")
            Text(
                "Charging stops at these levels. A lower everyday AC limit is easier on the battery; " +
                    "set it to 100% before a long trip.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            val savedAc = st?.chargeLimitAc ?: 80
            val savedDc = st?.chargeLimitDc ?: 80
            var ac by remember { mutableIntStateOf(savedAc) }
            var dc by remember { mutableIntStateOf(savedDc) }
            // Follow the car when its limits change (after saving, or set in the car).
            LaunchedEffect(savedAc, savedDc) {
                ac = savedAc
                dc = savedDc
            }
            LimitSlider("AC (home and public Level 2)", ac) { ac = it }
            LimitSlider("DC fast charging", dc) { dc = it }
            val changed = ac != savedAc || dc != savedDc
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (changed) {
                    ActionPill("Undo", enabled = true, primary = false) { ac = savedAc; dc = savedDc }
                    Spacer(Modifier.width(8.dp))
                }
                ActionPill("Save limits", enabled = changed && vm.canAct) { vm.setChargeLimits(ac, dc) }
            }

            // Preconditioning
            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 20.dp))
            SectionTitle("Battery preconditioning")
            val pre = st?.preconditioning
            SettingSwitch(
                "Precondition battery",
                "Brings the battery to the right temperature so DC fast charging is quicker, " +
                    "especially in the cold. Uses some energy; best started about 30 minutes before you plug in." +
                    if (pre == null) " The car doesn't report whether it's on." else "",
                pre == true,
            ) { on -> if (vm.canAct) vm.batteryPreconditioning(on) }
        }
    }
}

@Composable
private fun LimitSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.EvStation, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("$value%", style = MaterialTheme.typography.titleMedium)
    }
    // 50 to 100 in steps of 10, as the car allows.
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(((it / 10f).roundToInt() * 10).coerceIn(50, 100)) },
        valueRange = 50f..100f,
        steps = 4,
    )
}

@Composable
private fun ActionPill(label: String, enabled: Boolean, primary: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (primary) scheme.primary else scheme.surfaceContainerHigh
    val fg = if (primary) scheme.onPrimary else scheme.onSurface
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .pressable(enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else fg.copy(alpha = 0.6f))
    }
}
