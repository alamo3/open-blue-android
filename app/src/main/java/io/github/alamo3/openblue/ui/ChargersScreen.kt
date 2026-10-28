package io.github.alamo3.openblue.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.Station
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Speed filters, as the server's speed names. */
private enum class SpeedFilter(val label: String, val speeds: String?) {
    ALL("All", null),
    DC("DC fast", "fast,rapid,ultra"),
    L2("Level 2", "slow"),
}

/**
 * Public chargers near the car (or wherever the map is moved to), from Hyundai's charging
 * service: a map with pins coloured by availability, and a list nearest first. Tap one for its
 * chargers, prices and directions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargersScreen(vm: MainViewModel, onBack: () -> Unit) {
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    var filter by rememberSaveable { mutableStateOf(SpeedFilter.ALL) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var mapCentre by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val loc = vm.location
    val listState = rememberLazyListState()

    // Search around the car to begin with; ask the car where it is if we don't know.
    LaunchedEffect(loc != null) {
        if (loc == null) vm.locate()
        else if (vm.stationsCenter == null) vm.searchStations(loc.lat, loc.lon, filter.speeds)
    }
    fun search(f: SpeedFilter = filter) {
        val c = mapCentre ?: vm.stationsCenter ?: loc?.let { it.lat to it.lon } ?: return
        mapCentre = null
        vm.searchStations(c.first, c.second, f.speeds)
    }
    val movedFar = mapCentre?.let { m -> vm.stationsCenter?.let { distanceKm(m, it) > 2.0 } } == true

    LaunchedEffect(selected) {
        val i = vm.stations.indexOfFirst { it.id == selected }
        if (i >= 0) listState.animateScrollToItem(i)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Find chargers", onBack)
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            CarMap(
                car = loc?.let { MapCar(it.lat, it.lon, it.heading) },
                modifier = Modifier.fillMaxSize(),
                pins = vm.stations.map { MapPin(it.id, it.lat, it.lon, it.state) },
                selected = selected,
                fitKey = vm.stationsSearch,
                onPin = { selected = it },
                onMoved = { la, lo -> mapCentre = la to lo },
            )
            AnimatedVisibility(
                visible = movedFar && !vm.stationsLoading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            ) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(scheme.primary)
                        .pressable { search() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Search this area", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
                }
            }
            if (vm.stationsLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedFilter.entries.forEach { f ->
                OptionPill(f.label, filter == f, { if (filter != f) { filter = f; search(f) } })
            }
        }
        Legend()

        when {
            vm.stationsError != null -> Message(vm.stationsError!!)
            loc == null && vm.stations.isEmpty() -> Message(if (vm.progress != null) "Finding the car…" else "Locate the car first to search around it.")
            !vm.stationsLoading && vm.stations.isEmpty() && vm.stationsCenter != null -> Message("No chargers found within 15 km.")
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(vm.stations, key = { it.id }) { s ->
                StationRow(s, s.id == selected) {
                    selected = s.id
                    detail = s.id
                    vm.loadStation(s.id)
                }
            }
        }
    }

    detail?.let { id ->
        val s = vm.stations.firstOrNull { it.id == id }
        if (s != null) {
            ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                StationDetail(s)
            }
        }
    }
}

private fun distanceKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val dLat = (a.first - b.first) * 111.0
    val dLon = (a.second - b.second) * 111.0 * cos(Math.toRadians(a.first))
    return sqrt(dLat * dLat + dLon * dLon)
}

@Composable
private fun Legend() {
    val app = AppTheme.colors
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendDot(app.charge, "Free")
        LegendDot(app.heat, "Busy")
        LegendDot(Color(0xFF8A9199), "Unknown")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

private fun stateColor(s: Station, app: AppColors): Color = when (s.state) {
    0 -> app.charge
    1 -> app.heat
    else -> Color(0xFF8A9199)
}

@Composable
private fun StationRow(s: Station, selected: Boolean, onClick: () -> Unit) {
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceContainerHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else app.hairline, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(stateColor(s, app)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                listOfNotNull(s.network, s.speed?.label, s.power).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            s.availability?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = stateColor(s, app), modifier = Modifier.padding(top = 2.dp))
            }
        }
        s.distanceKm?.let {
            Spacer(Modifier.width(8.dp))
            Text(formatKm(it), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
    }
}

private fun formatKm(km: Double) = if (km < 1) "${(km * 1000).roundToInt()} m" else "%.1f km".format(km)

@Composable
private fun StationDetail(s: Station) {
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(s.name, style = MaterialTheme.typography.headlineSmall)
        listOfNotNull(s.network, s.address).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        s.availability?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = stateColor(s, app), modifier = Modifier.padding(top = 10.dp))
        }

        if (s.chargers.isNotEmpty()) {
            SectionTitle("Chargers")
            s.chargers.forEach { c ->
                c.connectors.forEach { k ->
                    DetailRow(
                        listOfNotNull(c.reference, k.types.joinToString(", ").ifEmpty { null }).joinToString(" · ").ifEmpty { "Connector" },
                        listOfNotNull(k.kw?.let { "${it.roundToInt()} kW" }, k.status?.let { connectorStatus(it) }).joinToString(" · "),
                        when (k.status?.uppercase()) {
                            "AVAILABLE" -> app.charge
                            "CHARGING", "OCCUPIED", "BLOCKED", "RESERVED" -> app.heat
                            else -> scheme.onSurface
                        },
                    )
                }
            }
        }
        s.price?.let {
            SectionTitle("Price")
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(20.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(scheme.primary)
                .pressable {
                    val gmaps = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lon}&travelmode=driving"))
                        .setPackage("com.google.android.apps.maps")
                    val any = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}(${Uri.encode(s.name)})"))
                    runCatching { context.startActivity(gmaps) }.onFailure { runCatching { context.startActivity(any) } }
                }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Directions, null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Directions", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
        }
        Text(
            "Starting a charge from the app comes later. For now, use the station's own app or card, or Plug & Charge.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private fun connectorStatus(raw: String) = when (raw.uppercase()) {
    "AVAILABLE" -> "Free"
    "CHARGING", "OCCUPIED" -> "In use"
    "RESERVED" -> "Reserved"
    "BLOCKED" -> "Blocked"
    "OUTOFORDER", "OUT_OF_ORDER", "INOPERATIVE", "FAULTED" -> "Out of order"
    "UNKNOWN" -> "Unknown"
    else -> raw.lowercase().replaceFirstChar { it.uppercase() }
}
