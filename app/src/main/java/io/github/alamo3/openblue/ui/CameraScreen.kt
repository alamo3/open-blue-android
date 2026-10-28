package io.github.alamo3.openblue.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.SvmCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private data class SvmView(val name: String, val image: ImageBitmap)

/** Splits the car's JPEG strip into its views: front, rear, left, right frames, then the top view. */
private fun splitCapture(c: SvmCapture): List<SvmView> {
    val full = BitmapFactory.decodeByteArray(c.jpeg, 0, c.jpeg.size) ?: return emptyList()
    val w = c.frameWidth
    val h = c.frameHeight
    if (w == null || h == null || w <= 0) return listOf(SvmView("Photo", full.asImageBitmap()))
    fun crop(x: Int, width: Int, height: Int): Bitmap? {
        if (x >= full.width) return null
        val cw = minOf(width, full.width - x)
        val ch = minOf(height, full.height)
        return if (cw > 0 && ch > 0) Bitmap.createBitmap(full, x, 0, cw, ch) else null
    }
    val views = mutableListOf<SvmView>()
    val tw = c.topWidth
    val th = c.topHeight
    if (tw != null && th != null) crop(4 * w, tw, th)?.let { views += SvmView("Top", it.asImageBitmap()) }
    listOf("Front" to 0, "Rear" to 1, "Left" to 2, "Right" to 3).forEach { (name, i) ->
        crop(i * w, w, h)?.let { views += SvmView(name, it.asImageBitmap()) }
    }
    return views
}

private fun captureTime(utc: String?): String {
    if (utc == null) return "unknown time"
    val parsed = runCatching {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(utc)
    }.getOrNull()
    return parsed?.let { relativeTime(it) } ?: utc
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(vm: MainViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadSurroundView() }
    val latest = vm.svm.firstOrNull()
    val views by produceState<List<SvmView>>(emptyList(), latest) {
        value = if (latest == null) emptyList() else withContext(Dispatchers.Default) { splitCapture(latest) }
    }
    var zoomed by remember { mutableStateOf<SvmView?>(null) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Surroundings", onBack)
        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.loadSurroundView(force = true) }, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 120.dp),
            ) {
                if (latest == null) {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        if (vm.svmLoaded) "No photos yet" else "Loading photos…",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "Ask the car to take photos with its surround-view cameras to see what's around it.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text("Taken ${captureTime(latest.utcTime)}", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    val top = views.firstOrNull { it.name == "Top" }
                    if (top != null) {
                        ViewTile(top, Modifier.fillMaxWidth(), onClick = { zoomed = top })
                        Spacer(Modifier.height(12.dp))
                    }
                    views.filter { it.name != "Top" }.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                            pair.forEach { v -> ViewTile(v, Modifier.weight(1f), onClick = { zoomed = v }) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(scheme.primary)
                        .pressable(vm.canAct) { vm.captureSurroundView() }
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PhotoCamera, null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(10.dp))
                    Text("Take new photos", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
                }
            }
        }
    }

    zoomed?.let { v ->
        Dialog(onDismissRequest = { zoomed = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable { zoomed = null }, contentAlignment = Alignment.Center) {
                Image(v.image, contentDescription = v.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                Text(
                    v.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ViewTile(v: SvmView, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Image(
            v.image,
            contentDescription = v.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(v.image.width.toFloat() / v.image.height.coerceAtLeast(1))
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick),
        )
        Text(v.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}
