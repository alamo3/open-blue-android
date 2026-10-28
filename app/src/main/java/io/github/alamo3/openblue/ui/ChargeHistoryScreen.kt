package io.github.alamo3.openblue.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ChargeOrder
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Sessions paid through Hyundai's charging service, a month at a time, with receipts. */
@Composable
fun ChargeHistoryScreen(vm: MainViewModel, onBack: () -> Unit) {
    val month = vm.chargeHistoryMonth
    val history = vm.chargeHistory
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { vm.loadChargeHistory() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Charging history", onBack)
        if (vm.chargeHistoryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.loadChargeHistory(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.loadChargeHistory(month.plusMonths(1)) }, enabled = month < YearMonth.now()) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next month")
            }
        }

        if (history != null && history.orders.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Total("Energy", (history.kwh ?: history.orders.sumOf { it.kwh ?: 0.0 }).let { "%.1f kWh".format(it) }, Modifier.weight(1f))
                Total("Spent", money(history.total ?: history.orders.sumOf { it.total ?: 0.0 }, history.orders.firstNotNullOfOrNull { it.currency }), Modifier.weight(1f))
                Total("Sessions", history.orders.size.toString(), Modifier.weight(1f))
            }
        }

        when {
            vm.chargeHistoryError != null -> Note(vm.chargeHistoryError!!)
            history != null && history.orders.isEmpty() && !vm.chargeHistoryLoading ->
                Note("No sessions paid through Hyundai's charging service this month. Sessions paid with a network's own app or card don't show up here.")
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(history?.orders.orEmpty()) { OrderRow(it) }
        }
    }
}

@Composable
private fun Total(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private fun money(v: Double, currency: String?) =
    if (currency == null || currency == "CAD" || currency == "USD") "$%.2f".format(v) else "%.2f %s".format(v, currency)

/** Server times look like yyyyMMddHHmmss or ISO; show what we can. */
private fun prettyTime(raw: String?): String? {
    if (raw == null) return null
    val patterns = listOf("yyyyMMddHHmmss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss")
    for (p in patterns) {
        val t = runCatching { LocalDateTime.parse(raw.take(p.replace("'", "").length), DateTimeFormatter.ofPattern(p)) }.getOrNull()
        if (t != null) return t.format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.getDefault()))
    }
    return raw
}

@Composable
private fun OrderRow(o: ChargeOrder) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .border(1.dp, AppTheme.colors.hairline, MaterialTheme.shapes.large)
            .let { m -> if (o.invoiceUrl != null) m.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.invoiceUrl))) } } else m }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.place ?: "Charging session", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            o.total?.let { Text(money(it, o.currency), style = MaterialTheme.typography.titleMedium) }
        }
        Text(
            listOfNotNull(prettyTime(o.started), o.kwh?.let { "%.1f kWh".format(it) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        o.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1) }
        Row(Modifier.padding(top = 4.dp)) {
            o.card?.let { Text("Card ···· $it", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            if (o.invoiceUrl != null) Text("Receipt", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
        Spacer(Modifier.width(0.dp))
    }
}
