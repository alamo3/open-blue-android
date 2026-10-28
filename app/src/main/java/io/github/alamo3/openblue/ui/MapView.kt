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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import io.github.alamo3.openblue.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/** Free vector maps from OpenStreetMap data (openfreemap.org); no account or key needed. */
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

/** A spot on the map, drawn as the car seen from above, pointing along [heading]. */
data class MapCar(val lat: Double, val lon: Double, val heading: Double?)

/**
 * An embedded map (MapLibre with OpenFreeMap tiles) showing the car. Follows the app's light or
 * dark theme. Shows a plain placeholder where the map can't run (previews, screenshot tests).
 */
@Composable
fun CarMap(car: MapCar?, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        MapPlaceholder(modifier)
        return
    }
    LiveCarMap(car, modifier)
}

@Composable
private fun MapPlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Map, contentDescription = "Map", tint = MaterialTheme.colorScheme.outline)
    }
}

/** Kept apart from [CarMap] so the screenshot renderer never loads MapLibre's classes. */
@Composable
private fun LiveCarMap(car: MapCar?, modifier: Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val current by rememberUpdatedState(car)
    // MapLibre is native code; if it can't start (or in the screenshot renderer), show a placeholder.
    val holder = remember {
        runCatching { MapHolder(context) }.onFailure { android.util.Log.w("OpenBlue", "Map unavailable", it) }.getOrNull()
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
        factory = { holder.view.also { holder.load(if (dark) STYLE_DARK else STYLE_LIGHT) { holder.show(current, animate = false) } } },
        update = { holder.show(current, animate = true) },
        modifier = modifier,
    )
}

/** Owns the MapLibre view, its style and the car marker. */
private class MapHolder(context: Context) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var shown: MapCar? = null
    private val carIcon: Bitmap = carIcon(context)

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
    }

    fun load(url: String, onReady: () -> Unit) {
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = true
            fun apply(u: String) {
                m.setStyle(Style.Builder().fromUri(u)) { s ->
                    style = s
                    s.addImage("car", carIcon)
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

    fun show(car: MapCar?, animate: Boolean) {
        val m = map ?: return
        val s = style ?: return
        if (car == null || car == shown) return
        val first = shown == null
        shown = car
        s.getSourceAs<GeoJsonSource>("car")?.setGeoJson(Point.fromLngLat(car.lon, car.lat))
        (s.getLayer("car") as? SymbolLayer)?.setProperties(PropertyFactory.iconRotate((car.heading ?: 0.0).toFloat()))
        val camera = CameraPosition.Builder().target(LatLng(car.lat, car.lon)).zoom(if (first) 16.5 else m.cameraPosition.zoom).build()
        if (animate && !first) m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 600) else m.cameraPosition = camera
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
