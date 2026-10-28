package io.github.alamo3.openblue.ui

import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

/** Where the car is, on a map, with walking directions in Google Maps. */
@Composable
fun LocationScreen(vm: MainViewModel, onBack: () -> Unit) {
    val loc = vm.location
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    // Ask the car once when there's nothing to show yet.
    LaunchedEffect(Unit) { if (vm.location == null) vm.locate() }

    val address by produceState<String?>(null, loc?.lat, loc?.lon) {
        value = if (loc == null || !Geocoder.isPresent()) null else withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault()).getFromLocation(loc.lat, loc.lon, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(
                        listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").takeIf { it.isNotBlank() },
                        a.locality,
                    ).joinToString(", ").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Location", onBack)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            CarMap(loc?.let { MapCar(it.lat, it.lon, it.heading) }, Modifier.fillMaxSize())
            if (loc == null) {
                Text(
                    if (vm.progress != null) "Asking the car where it is…" else "Location unknown",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 96.dp)) {
            Text(address ?: loc?.let { "%.5f, %.5f".format(Locale.US, it.lat, it.lon) } ?: "–", style = MaterialTheme.typography.titleLarge)
            Text(
                loc?.let { "Located ${relativeTime(Date(it.fetchedAt))}" } ?: "Not located yet",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(Icons.Rounded.Directions, "Directions", primary = true, enabled = loc != null, modifier = Modifier.weight(1f)) {
                    loc ?: return@BigButton
                    // Google Maps if it's installed (walking), otherwise any maps app.
                    val gmaps = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${loc.lat},${loc.lon}&travelmode=walking"),
                    ).setPackage("com.google.android.apps.maps")
                    val any = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${loc.lat},${loc.lon}?q=${loc.lat},${loc.lon}(Car)"))
                    runCatching { context.startActivity(gmaps) }.onFailure { runCatching { context.startActivity(any) } }
                }
                BigButton(Icons.Rounded.MyLocation, "Locate again", primary = false, enabled = vm.canAct, modifier = Modifier.weight(1f)) {
                    vm.locate()
                }
            }
        }
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (primary) scheme.primary else scheme.surfaceContainerHigh
    val fg = if (primary) scheme.onPrimary else scheme.onSurface
    Row(
        modifier
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .pressable(enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}
