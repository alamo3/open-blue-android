package io.github.alamo3.openblue.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.CommandProgress
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.SeatWeather
import io.github.alamo3.openblue.api.Weather
import io.github.alamo3.openblue.api.withoutMismatchedSeats
import io.github.alamo3.openblue.data.SecureStore
import io.github.alamo3.openblue.ui.AppTheme
import io.github.alamo3.openblue.ui.OpenBlueTheme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The small card the widget's Climate button opens: the last settings and every preset (from
 * Bluelink and from this phone) to choose from. Picking one starts it through the same worker as
 * the widget's other buttons, then the card closes.
 */
class ClimatePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureStore(this)
        val weather = store.lastWeather?.takeIf { System.currentTimeMillis() - it.fetchedAt < 3 * 3600_000L }
        val options = buildList {
            add("Last settings" to (store.lastClimate ?: ClimateSettings()))
            store.serverPresetsCache.forEach { add(it.name.ifBlank { "Preset" } to it) }
            store.localPresets.forEach { add(it.name.ifBlank { "Preset" } to it) }
        }
        setContent {
            OpenBlueTheme {
                Picker(options, weather, onPick = { start(store, it, weather) }, onDismiss = ::finish)
            }
        }
    }

    private fun start(store: SecureStore, picked: ClimateSettings, weather: Weather?) {
        // Seats that don't suit the weather are left off, as the app's "start without them" does.
        val settings = picked.withoutMismatchedSeats(weather)
        store.lastClimate = picked
        store.widgetPending = "${CommandProgress.Action.CLIMATE_START.name}|${System.currentTimeMillis()}"
        WidgetAction.enqueue(this, WidgetAction.CLIMATE_START, Json.encodeToString(settings))
        finish()
    }
}

@Composable
private fun Picker(
    options: List<Pair<String, ClimateSettings>>,
    weather: Weather?,
    onPick: (ClimateSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surface,
            modifier = Modifier.padding(24.dp).fillMaxWidth().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {},
            ),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Start climate", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    options.forEachIndexed { i, (name, settings) ->
                        if (i > 0) HorizontalDivider(color = scheme.outlineVariant)
                        Column(Modifier.fillMaxWidth().clickable { onPick(settings) }.padding(vertical = 12.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(settings.summary(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            if (SeatWeather.warning(settings.seats, weather) != null) {
                                Text(
                                    "Seats that don't suit today's weather are left off.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTheme.colors.alert,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
