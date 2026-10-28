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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** Speed filters, applied here by charger power (the server's own speed filter isn't reliable). */
private enum class SpeedFilter(val label: String, val keep: (Station) -> Boolean) {
    ALL("All", { true }),
    DC("DC fast", { it.isDc }),
    L2("Level 2", { !it.isDc }),
}

/**
 * Public chargers near the car (or wherever the map is moved to), from Hyundai's charging
 * service: a map with pins coloured by availability, and a list nearest first. Tap one for its
 * chargers, prices and directions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargersScreen(vm: MainViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadChargeService() }
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
        else if (vm.stationsCenter == null) vm.searchStations(loc.lat, loc.lon)
    }
    fun search() {
        val c = mapCentre ?: vm.stationsCenter ?: loc?.let { it.lat to it.lon } ?: return
        mapCentre = null
        vm.searchStations(c.first, c.second)
    }
    val shown = vm.stations.filter(filter.keep)
    val movedFar = mapCentre?.let { m -> vm.stationsCenter?.let { distanceKm(m, it) > 2.0 } } == true

    LaunchedEffect(selected) {
        val i = shown.indexOfFirst { it.id == selected }
        if (i >= 0) listState.animateScrollToItem(i)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Find chargers", onBack)
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            CarMap(
                car = loc?.let { MapCar(it.lat, it.lon, it.heading) },
                modifier = Modifier.fillMaxSize(),
                pins = shown.map { MapPin(it.id, it.lat, it.lon, it.state) },
                selected = selected,
                fitKey = vm.stationsSearch to filter,
                onPin = { selected = it },
                onMoved = { la, lo -> mapCentre = la to lo },
            )
            androidx.compose.animation.AnimatedVisibility(
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
                OptionPill(f.label, filter == f, { filter = f })
            }
        }
        Legend()

        when {
            vm.stationsError != null -> Message(vm.stationsError!!)
            loc == null && shown.isEmpty() -> Message(if (vm.progress != null) "Finding the car…" else "Locate the car first to search around it.")
            !vm.stationsLoading && shown.isEmpty() && vm.stationsCenter != null ->
                Message(if (vm.stations.isEmpty()) "No chargers found within 15 km." else "No ${filter.label} chargers within 15 km. Try another filter or search elsewhere.")
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.id }) { s ->
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
            ModalBottomSheet(
                onDismissRequest = { detail = null; vm.clearOffer() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                StationDetail(vm, s, onStarted = { detail = null; onBack() })
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
                listOfNotNull(s.network, s.speedLabel, s.power).joinToString(" · "),
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StationDetail(vm: MainViewModel, s: Station, onStarted: () -> Unit) {
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is androidx.fragment.app.FragmentActivity) c = c.baseContext
        c as? androidx.fragment.app.FragmentActivity
    }
    val canStart = vm.chargeService?.remoteStart != false
    var chosen by remember(s.id) { mutableStateOf<String?>(null) }
    var card by remember(s.id) { mutableStateOf(vm.savedCardId) }
    var agreed by remember(s.id) { mutableStateOf(false) }
    val offer = vm.offer?.takeIf { vm.offerFor == chosen }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(s.name, style = MaterialTheme.typography.headlineSmall)
        listOfNotNull(s.network, s.address).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        s.availability?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = stateColor(s, app), modifier = Modifier.padding(top = 10.dp))
        }

        if (s.chargers.isNotEmpty()) {
            SectionTitle(if (canStart) "Choose a charger" else "Chargers")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.chargers.forEachIndexed { i, c ->
                    val id = c.id
                    val pickable = canStart && id != null
                    val isChosen = id != null && id == chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (isChosen) scheme.primary.copy(alpha = 0.08f) else scheme.surfaceContainerHigh)
                            .border(if (isChosen) 2.dp else 1.dp, if (isChosen) scheme.primary else app.hairline, MaterialTheme.shapes.medium)
                            .let { m -> if (pickable) m.clickable { chosen = id; agreed = false; vm.loadOffer(s, c) } else m }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.reference ?: "Charger ${i + 1}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(
                                    c.connectors.flatMap { it.types }.distinct().joinToString(", ").ifEmpty { null },
                                    c.maxKw?.let { "${it.roundToInt()} kW" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        c.status?.let { st ->
                            Text(
                                connectorStatus(st),
                                style = MaterialTheme.typography.labelLarge,
                                color = when (st.uppercase()) {
                                    "AVAILABLE" -> app.charge
                                    "CHARGING", "OCCUPIED", "BLOCKED", "RESERVED" -> app.heat
                                    else -> scheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
        if (offer == null && chosen == null) {
            s.price?.let {
                SectionTitle("Price")
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Price and start, once a charger is chosen.
        if (chosen != null) {
            SectionTitle("Price")
            when {
                vm.offerLoading -> Text("Getting the price…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                vm.offerError != null -> Text("Couldn't get the price: ${vm.offerError}", style = MaterialTheme.typography.bodyMedium, color = app.alert)
                offer != null -> {
                    val lines = offer.priceLines.ifEmpty { listOfNotNull(offer.price, s.price) }
                    lines.forEach { Text(it, style = MaterialTheme.typography.titleMedium) }
                    if (lines.isEmpty()) Text("The network didn't send a price.", style = MaterialTheme.typography.bodyMedium)
                    offer.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }

                    if (offer.cards.isNotEmpty()) {
                        SectionTitle("Pay with")
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val current = card?.takeIf { id -> offer.cards.any { it.id == id } } ?: offer.cards.first().id
                            offer.cards.forEach { c -> OptionPill(c.card.label, current == c.id, { card = c.id }) }
                        }
                    }

                    if (!offer.remoteStart) {
                        Text(
                            "This charger can't be started from the app. Use the charger's screen or the network's app.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = app.alert,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 16.dp).clickable { agreed = !agreed },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                            Text(
                                "I agree to pay this price and to the charging network's terms",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            "Plug the car in first. You'll confirm with your fingerprint or screen lock.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        val enabled = agreed && !vm.sessionBusy && vm.session == null
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(CircleShape)
                                .background(if (enabled) app.charge else app.charge.copy(alpha = 0.35f))
                                .pressable(enabled) {
                                    val charger = s.chargers.firstOrNull { it.id == chosen } ?: return@pressable
                                    val payWith = card?.takeIf { id -> offer.cards.any { it.id == id } } ?: offer.cards.firstOrNull()?.id
                                    val go = { vm.startCharge(s, charger, payWith, onStarted) }
                                    if (activity != null) activity.confirmIdentity("Start charging", "${s.name}, ${charger.reference ?: "charger"}") { go() } else go()
                                }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when {
                                    vm.sessionBusy -> "Starting…"
                                    vm.session != null -> "A session is already running"
                                    else -> "Start charging"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        } else if (canStart && s.chargers.isNotEmpty()) {
            Text(
                "Pick a charger to see its price and start charging from here.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (s.chargers.isEmpty()) {
            Text(
                "Loading chargers…",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
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
            Icon(Icons.Rounded.Directions, null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Directions", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        }
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
