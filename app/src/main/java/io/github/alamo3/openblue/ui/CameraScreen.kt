package io.github.alamo3.openblue.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
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
import io.github.alamo3.openblue.data.SvmShot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

private data class SvmView(val name: String, val image: ImageBitmap)

private val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(vm: MainViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadSurroundView() }
    var selected by remember { mutableStateOf<String?>(null) }
    val shown = vm.svm.firstOrNull { it.utcTime == selected } ?: vm.svm.firstOrNull()
    val views by produceState<List<SvmView>>(emptyList(), shown) {
        value = if (shown == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { shown.views().map { (n, b) -> SvmView(n, b.asImageBitmap()) } }.getOrDefault(emptyList())
        }
    }
    var zoomed by remember { mutableStateOf<SvmView?>(null) }
    var confirmDelete by remember { mutableStateOf<SvmShot?>(null) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Surroundings", onBack)
        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.loadSurroundView(force = true) }, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp),
            ) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (shown == null) {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (shown === vm.svm.firstOrNull()) "Latest photos" else "Earlier photos",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    shown.takenAt?.let { "${dateFormat.format(it)} · ${relativeTime(it)}" } ?: "Unknown time",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.exportSurroundView(shown) }) {
                                Icon(Icons.Rounded.Download, contentDescription = "Save all to gallery")
                            }
                            IconButton(onClick = { confirmDelete = shown }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete from this phone")
                            }
                        }
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

                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(scheme.primary)
                            .pressable(vm.canAct) { selected = null; vm.captureSurroundView() }
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("Take new photos", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
                    }
                }

                // Every set of photos kept on this phone.
                if (vm.svm.size > 1) {
                    Text(
                        "History",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 4.dp),
                    )
                    Text(
                        "Kept on this phone after Bluelink replaces them",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(vm.svm, key = { it.utcTime ?: it.hashCode().toString() }) { shot ->
                            HistoryTile(shot, selected = shot === shown, onClick = { selected = shot.utcTime })
                        }
                    }
                }
            }
        }
    }

    zoomed?.let { v ->
        Dialog(onDismissRequest = { zoomed = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { zoomed = null }, contentAlignment = Alignment.Center) {
                Image(v.image, contentDescription = v.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                Text(
                    v.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
                )
                if (shown != null) {
                    IconButton(
                        onClick = { vm.exportSurroundView(shown, only = v.name) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    ) { Icon(Icons.Rounded.Download, contentDescription = "Save to gallery", tint = Color.White) }
                }
            }
        }
    }

    confirmDelete?.let { shot ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete these photos?") },
            text = { Text("They're removed from this phone. If they're the latest, Bluelink still has a copy and they'll come back next time you refresh.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    selected = null
                    vm.deleteSurroundView(shot)
                }) { Text("Delete", color = AppTheme.colors.alert) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryTile(shot: SvmShot, selected: Boolean, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, shot) {
        value = withContext(Dispatchers.IO) { shot.thumbnail()?.asImageBitmap() }
    }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.width(96.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .size(96.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(scheme.surfaceVariant)
                .border(2.dp, if (selected) scheme.primary else Color.Transparent, MaterialTheme.shapes.medium),
        ) {
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            shot.takenAt?.let { shortDate.format(it) } ?: "Unknown",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 2,
        )
    }
}

private val shortDate = SimpleDateFormat("MMM d\nh:mm a", Locale.getDefault())

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
