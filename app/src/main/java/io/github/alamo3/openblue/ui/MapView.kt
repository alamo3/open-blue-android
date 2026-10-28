package io.github.alamo3.openblue.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.alamo3.openblue.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Free vector maps from OpenStreetMap data (openfreemap.org); no account or key needed. */
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

/** A spot on the map, drawn as the car seen from above, pointing along [heading]. */
data class MapCar(val lat: Double, val lon: Double, val heading: Double?)

/** A charging station pin. [state]: 0 = chargers free, 1 = all busy, 2 = unknown or out of order. */
data class MapPin(val id: String, val lat: Double, val lon: Double, val state: Int)

/**
 * An embedded map (MapLibre with OpenFreeMap tiles) showing the car and, optionally, charging
 * stations. Follows the app's light or dark theme. Shows a plain placeholder where the map can't
 * run (screenshot tests, or if MapLibre fails to start).
 *
 * [fitKey] changes whenever the camera should reframe around the car and [pins]; [onMoved] gets
 * the centre after the user pans.
 */
@Composable
fun CarMap(
    car: MapCar?,
    modifier: Modifier = Modifier,
    pins: List<MapPin> = emptyList(),
    selected: String? = null,
    fitKey: Any? = null,
    onPin: (String) -> Unit = {},
    onMoved: ((Double, Double) -> Unit)? = null,
) {
    if (LocalInspectionMode.current) {
        MapPlaceholder(modifier)
        return
    }
    LiveCarMap(car, modifier, pins, selected, fitKey, onPin, onMoved)
}

@Composable
private fun MapPlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Map, contentDescription = "Map", tint = MaterialTheme.colorScheme.outline)
    }
}

/** Kept apart from [CarMap] so the screenshot renderer never loads MapLibre's classes. */
@Composable
private fun LiveCarMap(
    car: MapCar?,
    modifier: Modifier,
    pins: List<MapPin>,
    selected: String?,
    fitKey: Any?,
    onPin: (String) -> Unit,
    onMoved: ((Double, Double) -> Unit)?,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = AppTheme.colors
    val state = MapState(car, pins, selected, fitKey)
    val current by rememberUpdatedState(state)
    val pinClick by rememberUpdatedState(onPin)
    val moved by rememberUpdatedState(onMoved)
    val palette = intArrayOf(colors.charge.toArgb(), colors.heat.toArgb(), Color(0xFF8A9199).toArgb())
    // MapLibre is native code; if it can't start (or in the screenshot renderer), show a placeholder.
    val holder = remember {
        runCatching { MapHolder(context, palette, { pinClick(it) }, { la, lo -> moved?.invoke(la, lo) }) }
            .onFailure { android.util.Log.w("OpenBlue", "Map unavailable", it) }
            .getOrNull()
    }
    if (holder == null) {
        MapPlaceholder(modifier)
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.view.onPause()
            holder.view.onStop()
            holder.view.onDestroy()
        }
    }

    AndroidView(
        factory = { holder.view.also { holder.load(if (dark) STYLE_DARK else STYLE_LIGHT) { holder.show(current) } } },
        update = { holder.show(current) },
        modifier = modifier,
    )
}

private data class MapState(val car: MapCar?, val pins: List<MapPin>, val selected: String?, val fitKey: Any?)

/** Owns the MapLibre view, its style, the car marker and the station pins. */
private class MapHolder(
    context: Context,
    private val palette: IntArray,
    private val onPin: (String) -> Unit,
    private val onMoved: (Double, Double) -> Unit,
) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var shown: MapState? = null
    private var userMoved = false
    private val carIcon: Bitmap = carIcon(context)

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
    }

    fun load(url: String, onReady: () -> Unit) {
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnMapClickListener { point ->
                val screen = m.projection.toScreenLocation(point)
                val hit = m.queryRenderedFeatures(screen, "pins").firstOrNull()?.getStringProperty("id")
                if (hit != null) onPin(hit)
                hit != null
            }
            m.addOnCameraMoveStartedListener { reason ->
                userMoved = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
            }
            m.addOnCameraIdleListener {
                if (userMoved) {
                    userMoved = false
                    m.cameraPosition.target?.let { onMoved(it.latitude, it.longitude) }
                }
            }
            fun apply(u: String) {
                m.setStyle(Style.Builder().fromUri(u)) { s ->
                    style = s
                    s.addImage("car", carIcon)
                    s.addSource(GeoJsonSource("pins"))
                    s.addLayer(
                        CircleLayer("pins", "pins").withProperties(
                            PropertyFactory.circleColor(
                                Expression.match(
                                    Expression.get("state"),
                                    Expression.color(palette[2]),
                                    Expression.stop(0, Expression.color(palette[0])),
                                    Expression.stop(1, Expression.color(palette[1])),
                                ),
                            ),
                            PropertyFactory.circleRadius(Expression.switchCase(Expression.get("selected"), Expression.literal(11f), Expression.literal(7.5f))),
                            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                            PropertyFactory.circleStrokeWidth(Expression.switchCase(Expression.get("selected"), Expression.literal(3.5f), Expression.literal(2f))),
                        ),
                    )
                    s.addSource(GeoJsonSource("car"))
                    s.addLayer(
                        SymbolLayer("car", "car").withProperties(
                            PropertyFactory.iconImage("car"),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            PropertyFactory.iconRotationAlignment("map"),
                            PropertyFactory.iconSize(0.5f),
                        ),
                    )
                    shown = null
                    onReady()
                }
            }
            // If the dark style isn't available, fall back to the light one (once).
            var fellBack = url == STYLE_LIGHT
            view.addOnDidFailLoadingMapListener {
                if (!fellBack) {
                    fellBack = true
                    apply(STYLE_LIGHT)
                }
            }
            apply(url)
        }
    }

    fun show(next: MapState) {
        val m = map ?: return
        val s = style ?: return
        val prev = shown
        if (next == prev) return
        shown = next

        val car = next.car
        if (car != prev?.car) {
            s.getSourceAs<GeoJsonSource>("car")?.setGeoJson(
                if (car == null) FeatureCollection.fromFeatures(emptyList()) else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(car.lon, car.lat))),
            )
            (s.getLayer("car") as? SymbolLayer)?.setProperties(PropertyFactory.iconRotate((car?.heading ?: 0.0).toFloat()))
        }
        if (next.pins != prev?.pins || next.selected != prev?.selected) {
            s.getSourceAs<GeoJsonSource>("pins")?.setGeoJson(
                FeatureCollection.fromFeatures(
                    next.pins.map { p ->
                        Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                            addStringProperty("id", p.id)
                            addNumberProperty("state", p.state)
                            addBooleanProperty("selected", p.id == next.selected)
                        }
                    },
                ),
            )
        }

        // Camera: frame the car and pins on first show and whenever fitKey changes;
        // otherwise only follow the car if it moved, and centre a newly selected pin.
        val reframe = prev == null || next.fitKey != prev.fitKey
        val points = buildList {
            car?.let { add(LatLng(it.lat, it.lon)) }
            next.pins.forEach { add(LatLng(it.lat, it.lon)) }
        }
        when {
            reframe && points.size >= 2 -> {
                val bounds = LatLngBounds.Builder().includes(points).build()
                val pad = (48 * view.resources.displayMetrics.density).toInt()
                if (prev == null) m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad))
                else m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad), 600)
            }
            reframe && points.size == 1 -> {
                val camera = CameraPosition.Builder().target(points[0]).zoom(16.5).build()
                if (prev == null) m.cameraPosition = camera else m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 600)
            }
            next.selected != null && next.selected != prev?.selected -> {
                next.pins.firstOrNull { it.id == next.selected }?.let {
                    m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(it.lat, it.lon)), 400)
                }
            }
            car != null && car != prev?.car && next.pins.isEmpty() -> {
                m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(car.lat, car.lon)), 600)
            }
        }
    }

    companion object {
        /** The cabin render from above, with a soft shadow, as the car marker (nose up). */
        fun carIcon(context: Context): Bitmap {
            val src = BitmapFactory.decodeResource(context.resources, R.drawable.cabin_top)
            val h = 220
            val w = (src.width * h / src.height.toFloat()).toInt()
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            val pad = 16
            val out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val shadow = Paint().apply {
                color = 0x66000000
                maskFilter = android.graphics.BlurMaskFilter(10f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            c.drawRoundRect(pad.toFloat() + 4, pad.toFloat() + 6, (pad + w).toFloat() - 4, (pad + h).toFloat() + 2, 40f, 40f, shadow)
            c.drawBitmap(scaled, pad.toFloat(), pad.toFloat(), null)
            return out
        }
    }
}
