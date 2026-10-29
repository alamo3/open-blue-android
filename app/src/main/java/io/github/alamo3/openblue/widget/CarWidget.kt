package io.github.alamo3.openblue.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.alamo3.openblue.CommandProgress
import io.github.alamo3.openblue.MainActivity
import io.github.alamo3.openblue.R
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.SeatWeather
import io.github.alamo3.openblue.data.SecureStore
import io.github.alamo3.openblue.ui.CarStatus
import io.github.alamo3.openblue.ui.formatMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.text.DateFormat
import kotlin.math.roundToInt

/** What the widget shows, read from what the app keeps on the phone. */
data class WidgetData(
    val signedIn: Boolean,
    val name: String,
    val battery: Int?,
    val range: String?,
    val charging: Boolean,
    val line: String,
    val locked: Boolean?,
    val climateOn: Boolean,
    val tempOutside: Int?,
    /** "Locking…" while a command from the widget waits for the car. */
    val pending: String?,
    /** The last climate settings' seats don't suit the weather: ask in the app before starting. */
    val climateNeedsCheck: Boolean,
    /** Picture of the car (its model, or the stand-in for its body type). */
    val still: Int = R.drawable.car_still,
) {
    companion object {
        /** A command still waiting after this long is shown as done; the notification says how it went. */
        private const val PENDING_MS = 3 * 60_000L

        fun load(context: Context, now: Long = System.currentTimeMillis()): WidgetData {
            val store = SecureStore(context)
            val v = store.vehicle
            val signedIn = v != null && store.username != null
            val st = store.cachedStatus?.let { raw ->
                runCatching { CarStatus.from(Json.parseToJsonElement(raw).jsonObject, emptyList()) }.getOrNull()
            }
            val weather = store.lastWeather?.takeIf { now - it.fetchedAt < 3 * 3600_000L }
            val pending = store.widgetPending?.split('|')?.takeIf { it.size == 2 }?.let { (a, t) ->
                val at = t.toLongOrNull() ?: 0
                if (now - at > PENDING_MS) null
                else runCatching { CommandProgress.Action.valueOf(a).doing }.getOrNull()?.let { "$it…" }
            }
            val line = when {
                st == null -> "Open the app to load the car"
                st.charging && st.chargeMinutesLeft != null -> "Charging, full in ${formatMinutes(st.chargeMinutesLeft)}"
                st.charging -> "Charging"
                else -> st.updatedAt?.let { "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(it) } ?: ""
            }
            val climate = store.lastClimate ?: ClimateSettings()
            return WidgetData(
                signedIn = signedIn,
                name = v?.nickname?.ifBlank { null } ?: "Your car",
                battery = st?.battery,
                range = st?.range?.let { "$it ${st.rangeUnit}" },
                charging = st?.charging == true,
                line = line,
                locked = st?.locked,
                climateOn = st?.climateOn == true,
                tempOutside = weather?.tempC?.roundToInt(),
                pending = pending,
                climateNeedsCheck = SeatWeather.warning(climate.seats, weather) != null,
                still = io.github.alamo3.openblue.ui.CarBody.current(context).still,
            )
        }
    }
}

/**
 * Home-screen widget: battery, range and charging at a glance; at its larger size the car
 * itself and one-tap lock, climate and refresh. Unlocking opens the app, which can ask for a
 * fingerprint first.
 */
class CarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) { WidgetData.load(context) }
        provideContent { GlanceTheme { Content(context, data) } }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 48.dp)
        private val WIDE = DpSize(250.dp, 110.dp)
        private val LARGE = DpSize(250.dp, 200.dp)

        /** Redraws every Open Blue widget from what's stored now. */
        suspend fun updateAll(context: Context) = CarWidget().updateAll(context)
    }
}

class CarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CarWidget()
}

private val GREEN = ColorProvider(Color(0xFF34C77B))
private val ORANGE = ColorProvider(Color(0xFFFF8A4C))
private val RED = ColorProvider(Color(0xFFFF5A52))

private fun openApp(context: Context, climate: Boolean = false) = actionStartActivity(
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (climate) putExtra(MainActivity.EXTRA_START_CLIMATE, true)
    },
)

private fun run(what: String) = actionRunCallback<WidgetAction>(actionParametersOf(WidgetAction.WHAT to what))

@Composable
private fun Content(context: Context, d: WidgetData) {
    val size = LocalSize.current
    val c = GlanceTheme.colors
    Box(
        GlanceModifier.fillMaxSize().background(c.widgetBackground).cornerRadius(22.dp)
            .clickable(openApp(context)),
    ) {
        if (!d.signedIn) {
            Column(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Open Blue", style = TextStyle(color = c.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp))
                Text("Sign in to see your car here", style = TextStyle(color = c.onSurfaceVariant, fontSize = 13.sp))
            }
            return@Box
        }
        if (size.height < 100.dp) Small(d) else Full(context, d, tall = size.height >= 190.dp)
    }
}

@Composable
private fun Small(d: WidgetData) {
    val c = GlanceTheme.colors
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(d.battery?.let { "$it%" } ?: "–", style = TextStyle(color = c.onSurface, fontWeight = FontWeight.Bold, fontSize = 22.sp))
        if (d.charging) Image(ImageProvider(R.drawable.ic_w_bolt), "Charging", GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(GREEN))
        Spacer(GlanceModifier.defaultWeight())
        LockIcon(d.locked, 20)
    }
}

@Composable
private fun LockIcon(locked: Boolean?, dp: Int) {
    Image(
        ImageProvider(if (locked == false) R.drawable.ic_w_lock_open else R.drawable.ic_w_lock),
        when (locked) { true -> "Locked"; false -> "Unlocked"; null -> "Lock state unknown" },
        GlanceModifier.size(dp.dp),
        colorFilter = ColorFilter.tint(if (locked == false) RED else GlanceTheme.colors.onSurfaceVariant),
    )
}

@Composable
private fun Full(context: Context, d: WidgetData, tall: Boolean) {
    val c = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(d.name, style = TextStyle(color = c.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            d.tempOutside?.let { Text("$it°", style = TextStyle(color = c.onSurfaceVariant, fontSize = 14.sp)) }
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(d.battery?.let { "$it%" } ?: "–", style = TextStyle(color = c.onSurface, fontWeight = FontWeight.Bold, fontSize = 30.sp))
            Spacer(GlanceModifier.width(8.dp))
            d.range?.let { Text(it, style = TextStyle(color = c.onSurfaceVariant, fontSize = 15.sp), modifier = GlanceModifier.padding(bottom = 5.dp)) }
            if (d.charging) {
                Spacer(GlanceModifier.width(6.dp))
                Image(ImageProvider(R.drawable.ic_w_bolt), "Charging", GlanceModifier.size(20.dp).padding(bottom = 4.dp), colorFilter = ColorFilter.tint(GREEN))
            }
        }
        Text(
            d.pending ?: d.line,
            style = TextStyle(color = if (d.pending != null) c.primary else if (d.charging) GREEN else c.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 1,
        )
        if (tall) {
            Image(
                ImageProvider(d.still), d.name,
                GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 4.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Locking is one tap; unlocking opens the app (which can ask for a fingerprint).
            Button(
                icon = if (d.locked == false) R.drawable.ic_w_lock_open else R.drawable.ic_w_lock,
                label = when (d.locked) { false -> "Lock"; true -> "Locked"; null -> "Lock" },
                tint = if (d.locked == false) RED else c.onSurface,
                action = if (d.locked == true) openApp(context) else run(WidgetAction.LOCK),
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(8.dp))
            Button(
                icon = R.drawable.ic_w_air,
                label = if (d.climateOn) "Stop" else "Climate",
                tint = if (d.climateOn) ORANGE else c.onSurface,
                action = when {
                    d.climateOn -> run(WidgetAction.CLIMATE_STOP)
                    // Heated seats on a warm day (or cooled on a cold one): let the app ask.
                    d.climateNeedsCheck -> openApp(context, climate = true)
                    else -> run(WidgetAction.CLIMATE_START)
                },
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(8.dp))
            Button(
                icon = R.drawable.ic_w_refresh, label = "Refresh", tint = c.onSurface,
                action = run(WidgetAction.REFRESH),
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }
}

@Composable
private fun Button(icon: Int, label: String, tint: ColorProvider, action: androidx.glance.action.Action, modifier: GlanceModifier) {
    Row(
        modifier.height(40.dp).background(GlanceTheme.colors.secondaryContainer).cornerRadius(20.dp).clickable(action),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(ImageProvider(icon), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(tint))
        Spacer(GlanceModifier.width(5.dp))
        Text(label, style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}
