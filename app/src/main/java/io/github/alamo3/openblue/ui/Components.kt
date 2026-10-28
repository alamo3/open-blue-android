package io.github.alamo3.openblue.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.CommandProgress
import io.github.alamo3.openblue.api.SeatLevel

/** Press feedback shared by tappable controls: a slight springy shrink plus a haptic tick. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "press")
    val haptics = LocalHapticFeedback.current
    return this
        .scale(scale)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick?.let { long ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    long()
                }
            },
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/** Round icon control with a label underneath. [tint] colours it when [active]. */
@Composable
fun RoundControl(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    size: Dp = 64.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (active) tint.copy(alpha = 0.16f) else scheme.surfaceContainerHigh, tween(250), label = "bg")
    val fg by animateColorAsState(if (active) tint else scheme.onSurface, tween(250), label = "fg")
    Column(
        modifier.alpha(if (enabled) 1f else 0.45f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(bg)
                .pressable(enabled, onLongClick, onClick),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(icon, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "icon") { i ->
                Icon(i, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.42f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Full-width list row that opens a screen. */
@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, iconTint: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Read-only label/value row. */
@Composable
fun DetailRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

/** Screen header with a back arrow. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Thin battery bar that animates to its level. */
@Composable
fun BatteryBar(percent: Int?, charging: Boolean, modifier: Modifier = Modifier) {
    val app = AppTheme.colors
    val fraction by animateFloatAsState((percent ?: 0) / 100f, tween(900), label = "battery")
    val color = when {
        charging -> app.charge
        (percent ?: 100) <= 15 -> app.alert
        else -> MaterialTheme.colorScheme.primary
    }
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(app.hairline)) {
        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Pill toggle used for climate extras. */
@Composable
fun ToggleChip(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit, tint: Color, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (on) tint.copy(alpha = 0.16f) else scheme.surfaceContainerHigh, tween(220), label = "chipBg")
    val fg by animateColorAsState(if (on) tint else scheme.onSurface, tween(220), label = "chipFg")
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Selectable pill in a row of options (duration). */
@Composable
fun OptionPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.primary else scheme.surfaceContainerHigh, tween(200), label = "pillBg")
    val fg by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurface, tween(200), label = "pillFg")
    Box(
        modifier.clip(CircleShape).background(bg).pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = fg) }
}

/**
 * A seat on the cabin map. Tapping cycles its level; three bars show the level like the car's
 * own seat buttons, coloured for heat or cooling.
 */
@Composable
fun SeatButton(level: SeatLevel, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        level.isHeat -> app.heat
        level.isCool -> app.cool
        else -> scheme.onSurfaceVariant
    }
    val bg by animateColorAsState(if (level == SeatLevel.OFF) scheme.surfaceContainerHigh else tint.copy(alpha = 0.18f), tween(220), label = "seatBg")
    val fg by animateColorAsState(tint, tween(220), label = "seatFg")
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .border(1.dp, if (level == SeatLevel.OFF) app.hairline else tint.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .pressable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                val lit = i < level.bars
                val barColor by animateColorAsState(if (lit) fg else app.hairline, tween(200), label = "bar$i")
                Box(Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(2.dp)).background(barColor))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                level.isHeat -> "Heat"
                level.isCool -> "Cool"
                else -> "Off"
            },
            style = MaterialTheme.typography.labelLarge,
            color = fg,
        )
    }
}

/** Floating strip showing a remote command's progress. Tap to dismiss once it's finished. */
@Composable
fun CommandStrip(p: CommandProgress, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    val finished = p.stage == CommandProgress.Stage.DONE || p.stage == CommandProgress.Stage.FAILED
    Surface(
        modifier = modifier.fillMaxWidth().clickable(enabled = finished, onClick = onDismiss),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                when (p.stage) {
                    CommandProgress.Stage.DONE -> Icon(Icons.Rounded.Check, null, tint = app.charge)
                    CommandProgress.Stage.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = app.alert)
                    else -> PulseDots()
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (p.stage) {
                        CommandProgress.Stage.SENDING -> "${p.action.doing}…"
                        CommandProgress.Stage.WAITING -> "${p.action.doing}, waiting for the car"
                        CommandProgress.Stage.DONE -> p.action.done
                        CommandProgress.Stage.FAILED -> "${p.action.doing} didn't work"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
                if (p.note != null) {
                    Text(p.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PulseDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "phase")
    val color = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a = if (phase.toInt() == i) 1f else 0.25f
            Box(Modifier.size(6.dp).alpha(a).clip(CircleShape).background(color))
        }
    }
}

/** A labelled button that opens a menu of options (used in settings). */
@Composable
fun <T> Picker(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(optionLabel(selected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}

/** Round car photo (Bluelink's vehicle picture), or the first letter of [name] when there isn't one. */
@Composable
fun Avatar(url: String?, name: String, onClick: () -> Unit, size: Dp = 44.dp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).background(scheme.surfaceContainerHigh).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = "Car photo",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(name.take(1).uppercase().ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium)
        }
    }
}
