package io.github.alamo3.openblue.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.TripRoute
import io.github.alamo3.openblue.api.TripStop
import kotlin.math.roundToInt

/**
 * Plan a trip with Hyundai's EV route planner: pick a destination, set how much charge to keep
 * in reserve, and get the route with its charging stops. "Navigate" hands it to Google Maps
 * (charging stops as waypoints), which Android Auto shows on the car's screen.
 */
@Composable
fun TripScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var chargerSoc by rememberSaveable { mutableIntStateOf(15) }
    var destinationSoc by rememberSaveable { mutableIntStateOf(30) }
    var codes by rememberSaveable { mutableStateOf("22") }
    var routeIndex by rememberSaveable { mutableIntStateOf(0) }
    val dest = vm.tripDestination
    val routes = vm.trip?.routes.orEmpty()
    val route = routes.getOrNull(routeIndex) ?: routes.firstOrNull()
    LaunchedEffect(vm.trip) { routeIndex = 0 }
    val loc = vm.location

    LaunchedEffect(Unit) {
        vm.loadChargeService()
        if (vm.location == null) vm.locate()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Plan a trip", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
            // Destination
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (dest == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Where to?") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (vm.placeSearching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.searchPlaces(query) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    vm.placeResults.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.tripDestination = p; vm.clearPlaces() }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Place, null, tint = scheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                p.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                            }
                        }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(scheme.surfaceContainerHigh)
                            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Place, null, tint = scheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(dest.name, style = MaterialTheme.typography.titleMedium)
                            dest.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                        }
                        IconButton(onClick = { vm.tripDestination = null }) { Icon(Icons.Rounded.Close, contentDescription = "Change destination") }
                    }
                }

                // Reserves
                Text("Arrive at chargers with at least", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 20, 25).forEach { v -> OptionPill("$v%", chargerSoc == v, { chargerSoc = v }) }
                }
                Text("Arrive at the destination with", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 30, 50).forEach { v -> OptionPill("$v%", destinationSoc == v, { destinationSoc = v }) }
                }

                // Developer mode: try other route option codes (comma separated); each gets its own route.
                if (vm.developerMode) {
                    OutlinedTextField(
                        value = codes,
                        onValueChange = { v -> codes = v.filter { it.isDigit() || it == ',' || it == ' ' || it == '-' } },
                        label = { Text("Route option codes (developer)") },
                        supportingText = { Text("The official app sends 22. Lists and ranges work: 22, 8, 0-31.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    )
                    val enabled = dest != null && loc != null && !vm.scanning && !vm.tripLoading
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(CircleShape)
                            .background(scheme.surfaceContainerHigh)
                            .pressable(enabled) { vm.scanRouteOptions(chargerSoc, destinationSoc, parseCodes(codes)) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (vm.scanning) "Trying ${vm.scanResults.size + 1} of ${parseCodes(codes).size}…" else "Try each code on its own",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (vm.scanResults.isNotEmpty()) {
                        val base = vm.scanResults.firstOrNull { it.code == 22 }?.route
                        Column(Modifier.padding(top = 10.dp)) {
                            vm.scanResults.forEach { r ->
                                val rt = r.route
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .let { m ->
                                            if (rt != null) m.clickable { routeIndex = vm.trip?.routes?.indexOfFirst { it.option == r.code }?.coerceAtLeast(0) ?: 0 } else m
                                        }
                                        .padding(vertical = 6.dp),
                                ) {
                                    Text("${r.code}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(40.dp))
                                    Text(
                                        when {
                                            rt != null -> buildString {
                                                append("${(rt.distanceM / 1000.0).roundToInt()} km, ${hours(rt.seconds.toLong())}, ${rt.stops.size} stops")
                                                if (base != null && rt !== base) {
                                                    val dKm = (rt.distanceM - base.distanceM) / 1000.0
                                                    val dMin = (rt.seconds - base.seconds) / 60
                                                    append("  (%+.0f km, %+d min vs 22)".format(dKm, dMin))
                                                }
                                            }
                                            r.status == 3 -> "No EV route found"
                                            r.status == 1 -> "Rejected (status 1)"
                                            r.status == 2 -> "Error (status 2)"
                                            r.error != null -> r.error
                                            else -> "No route (status ${r.status})"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (rt != null) scheme.onSurface else scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                val ready = dest != null && loc != null && !vm.tripLoading
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                        .clip(CircleShape)
                        .background(if (ready) scheme.primary else scheme.primary.copy(alpha = 0.35f))
                        .pressable(ready) {
                            val options = if (vm.developerMode) parseCodes(codes) else listOf(22)
                            vm.planTrip(chargerSoc, destinationSoc, options)
                        }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            vm.tripLoading -> "Planning…"
                            loc == null -> "Finding the car…"
                            else -> "Plan route"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onPrimary,
                    )
                }
                vm.tripError?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = app.alert, modifier = Modifier.padding(top = 10.dp))
                }
            }

            // Result
            if (route != null && dest != null) {
                if (routes.size > 1) {
                    // One route per option code: compare them.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        routes.forEachIndexed { i, r ->
                            OptionPill(
                                "${r.option}: ${(r.distanceM / 1000.0).roundToInt()} km, ${hours(r.seconds.toLong())}",
                                route === r,
                                { routeIndex = i },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                CarMap(
                    car = loc?.let { MapCar(it.lat, it.lon, it.heading) },
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    pins = route.stops.mapIndexed { i, s -> MapPin("stop$i", s.lat, s.lon, 0) } + MapPin("dest", dest.lat, dest.lon, 3),
                    fitKey = route,
                    line = route.line,
                )
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Summary(route)
                    route.stops.forEachIndexed { i, s -> StopRow(i + 1, s) }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Place, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(dest.name, style = MaterialTheme.typography.titleMedium)
                            route.arriveSoc?.let { Text("Arrive with about $it%", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(CircleShape)
                            .background(scheme.primary)
                            .pressable { navigate(context, route, dest.lat, dest.lon) }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Navigation, null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Navigate in Google Maps", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
                    }
                    Text(
                        "Charging stops are added as waypoints. On the road, Android Auto shows it on the car's screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** "22, 8, 0-3" to [22, 8, 0, 1, 2, 3]. */
private fun parseCodes(text: String): List<Int> = text.split(',', ' ').flatMap { part ->
    val p = part.trim()
    val range = Regex("^(\\d+)-(\\d+)$").find(p)
    when {
        range != null -> {
            val (a, b) = range.destructured
            val lo = a.toInt()
            val hi = b.toInt()
            if (hi >= lo && hi - lo <= 64) (lo..hi).toList() else emptyList()
        }
        else -> listOfNotNull(p.toIntOrNull())
    }
}.distinct()

private fun hours(seconds: Long): String {
    val m = (seconds / 60.0).roundToInt()
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

@Composable
private fun Summary(r: TripRoute) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .background(scheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Figure("Distance", "${(r.distanceM / 1000.0).roundToInt()} km")
        Figure("Time", hours(r.seconds.toLong()))
        Figure("Stops", r.stops.size.toString())
        if (r.fee > 0) Figure("Charging", "$${r.fee}")
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StopRow(n: Int, s: TripStop) {
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .border(1.dp, app.hairline, MaterialTheme.shapes.large)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(app.charge), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                listOfNotNull(s.network, s.powerKw?.takeIf { it > 0 }?.let { "$it kW" }, s.distanceM?.let { "${(it / 1000.0).roundToInt()} km in" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            val soc = if (s.arriveSoc != null && s.targetSoc != null) "${s.arriveSoc}% → ${s.targetSoc}%" else null
            Text(
                listOfNotNull(soc, s.chargeSeconds?.takeIf { it > 0 }?.let { "about ${hours(it)}" }).joinToString(", "),
                style = MaterialTheme.typography.labelMedium,
                color = app.charge,
            )
        }
        Icon(Icons.Rounded.EvStation, null, tint = scheme.onSurfaceVariant)
    }
}

/** Opens Google Maps driving directions with the charging stops as waypoints. */
private fun navigate(context: android.content.Context, r: TripRoute, lat: Double, lon: Double) {
    val waypoints = r.stops.joinToString("|") { "${it.lat},${it.lon}" }
    val url = buildString {
        append("https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=$lat,$lon")
        if (waypoints.isNotEmpty()) append("&waypoints=").append(Uri.encode(waypoints))
    }
    val gmaps = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.apps.maps")
    runCatching { context.startActivity(gmaps) }.onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
}
