package io.github.alamo3.openblue.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.sound.EngineSound
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TACH_MAX = 8000f

/** A made-up engine sound that follows the car's motion, through the phone or the car via Android Auto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    val context = LocalContext.current
    val r = vm.soundReadout
    val sound = vm.engineSound

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.startSound()
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.measureSoundDelay()
    }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Engine sound", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 48.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                val gauge = Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f)
                if (sound.isEngine) {
                    Tachometer(
                        rpm = r?.rpm ?: if (vm.soundOn) sound.idleRpm else 0f,
                        redline = sound.redline,
                        load = r?.load ?: 0f,
                        modifier = gauge,
                    )
                } else {
                    PowerRing(load = r?.load ?: 0f, modifier = gauge)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (sound.isEngine) {
                        Text(
                            when {
                                r == null -> "–"
                                r.gear == 0 -> "N"
                                r.gear < 0 -> "D"
                                else -> r.gear.toString()
                            },
                            fontSize = 64.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            r?.let { "${it.speedKmh.roundToInt()} km/h" } ?: sound.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        if (r != null) {
                            Text("${(r.rpm / 10).roundToInt() * 10} rpm", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    } else {
                        Text(r?.speedKmh?.roundToInt()?.toString() ?: "–", fontSize = 64.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (r != null) "km/h" else sound.label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                        if (r != null) {
                            Text("Power ${(r.load * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(CircleShape)
                    .background(if (vm.soundOn) scheme.surfaceContainerHigh else scheme.primary)
                    .pressable {
                        if (vm.soundOn) {
                            vm.stopSound()
                        } else if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                            vm.startSound()
                        } else {
                            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (vm.soundOn) "Stop" else "Start",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (vm.soundOn) scheme.onSurface else scheme.onPrimary,
                )
            }
            if (vm.soundOn) {
                Text(
                    statusLine(r),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                // Rev it in place: springs back when let go.
                var rev by remember { mutableFloatStateOf(0f) }
                Text("Rev it", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Slider(
                    value = rev,
                    onValueChange = {
                        rev = it
                        vm.revEngine(it)
                    },
                    onValueChangeFinished = {
                        rev = 0f
                        vm.revEngine(null)
                    },
                    colors = sliderColors(app.heat),
                )
            }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 12.dp))
            SectionTitle("Sound")
            Text("Made for an EV", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineSound.entries.filter { !it.isEngine }.forEach { s -> OptionPill(s.label, s == sound, { vm.changeEngineSound(s) }) }
            }
            Text("Engines", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineSound.entries.filter { it.isEngine }.forEach { s -> OptionPill(s.label, s == sound, { vm.changeEngineSound(s) }) }
            }
            Text(
                soundBlurb(sound),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text("Volume", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Slider(value = vm.soundVolume, onValueChange = { vm.changeSoundVolume(it) }, colors = sliderColors(scheme.primary))
            if (sound.isEngine) {
                SettingSwitch(
                    "Gearshifts",
                    "Off: one long gear like the car itself, revs rising smoothly with speed. On: a 6-speed gearbox with shifts and kickdowns.",
                    vm.gearshifts,
                ) { vm.changeGearshifts(it) }
            }
            SettingSwitch(
                "Hold revs when not accelerating",
                "Off: revs drop back to idle whenever you're not on the power. On: they stay where the gear would put them.",
                vm.holdRevs,
            ) { vm.changeHoldRevs(it) }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            SectionTitle("Delay")
            Text(
                "To play through the car, connect Android Auto and start the sound; it goes out with your media. " +
                    "Android Auto adds its own delay (more over wireless than a cable), so measure it here and the " +
                    "sound will predict that far ahead to make up for it.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Look-ahead", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${vm.soundLookaheadMs} ms", style = MaterialTheme.typography.titleMedium)
            }
            Slider(
                value = vm.soundLookaheadMs.toFloat(),
                onValueChange = { vm.changeSoundLookahead(((it / 10f).roundToInt() * 10)) },
                valueRange = 0f..300f,
                colors = sliderColors(scheme.primary),
            )
            val canMeasure = vm.soundOn && !vm.measuringDelay
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (canMeasure) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0.5f))
                    .pressable(canMeasure) {
                        if (granted(Manifest.permission.RECORD_AUDIO)) vm.measureSoundDelay()
                        else askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    when {
                        vm.measuringDelay -> "Listening…"
                        vm.soundOn -> "Measure delay"
                        else -> "Measure delay (start the sound first)"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canMeasure) scheme.onSurface else scheme.onSurfaceVariant,
                )
            }
            vm.soundDelayNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            SoundAutoStart(vm)
            Spacer(Modifier.height(16.dp))
            Text(
                "Mount the phone so it can't move; it works out which way is forward the first time you accelerate. " +
                    "Set things up before you drive.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

private fun soundBlurb(s: EngineSound) = when (s.kind) {
    EngineSound.Kind.STARSHIP -> "A deep synth drone that climbs about two octaves with speed and opens up as you accelerate."
    EngineSound.Kind.HYPERDRIVE -> "Tones that seem to rise forever while you accelerate, and hold when you ease off."
    EngineSound.Kind.ION -> "A growling bass that throbs faster the quicker you go."
    EngineSound.Kind.CRUISER -> "A cinematic chord that swells and brightens as you push, adding a top note near full power."
    EngineSound.Kind.PISTON -> "A made-up engine, synthesized from scratch."
}

/** How hard the car is pulling, as a glowing ring. */
@Composable
private fun PowerRing(load: Float, modifier: Modifier = Modifier) {
    val app = AppTheme.colors
    val base = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = size.minDimension * 0.05f
        val inset = stroke * 1.6f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val tl = Offset(inset, inset)
        drawArc(app.hairline, 135f, 270f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val f = load.coerceIn(0f, 1f)
        if (f > 0.005f) {
            drawArc(lerpColor(base, app.cool, f), 135f, 270f * f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

private fun statusLine(r: io.github.alamo3.openblue.sound.SoundReadout?): String = when {
    r == null -> "Starting…"
    !r.hasSensors -> "This phone lacks the motion sensors the sound needs."
    !r.gpsFix -> "Waiting for GPS. Until then, revs follow acceleration alone."
    r.calibrated < 0.2f -> "Accelerate gently once so it can learn which way is forward."
    else -> "Following the car."
}

@Composable
private fun sliderColors(active: Color) = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = active,
    inactiveTrackColor = AppTheme.colors.hairline,
)

/** A rev counter: 0 to 8000 rpm over 270°, red past the redline, glowing warmer with load. */
@Composable
private fun Tachometer(rpm: Float, redline: Float, load: Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    val track = app.hairline
    val needle = scheme.primary
    val red = app.alert
    val heat = app.heat
    val label = scheme.onSurfaceVariant
    val textPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier) {
        val stroke = size.minDimension * 0.045f
        val inset = stroke * 1.6f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val tl = Offset(inset, inset)
        val start = 135f
        val sweep = 270f
        fun angleOf(v: Float) = start + sweep * (v / TACH_MAX).coerceIn(0f, 1f)

        drawArc(track, start, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(red.copy(alpha = 0.85f), angleOf(redline), sweep - (angleOf(redline) - start), false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        if (rpm > 0f) {
            val fill = lerpColor(needle, heat, load.coerceIn(0f, 1f))
            drawArc(fill, start, angleOf(rpm) - start, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }

        // Ticks and numbers every 1000 rpm.
        val c = center
        val radius = arcSize.width / 2
        textPaint.color = android.graphics.Color.argb(
            (label.alpha * 255).toInt(), (label.red * 255).toInt(), (label.green * 255).toInt(), (label.blue * 255).toInt(),
        )
        textPaint.textSize = size.minDimension * 0.06f
        textPaint.textAlign = android.graphics.Paint.Align.CENTER
        for (k in 0..8) {
            val a = Math.toRadians(angleOf(k * 1000f).toDouble())
            val inner = radius - stroke * 1.4f
            val outer = radius - stroke * 0.6f
            drawLine(
                if (k * 1000f >= redline) red else label,
                Offset(c.x + (cos(a) * inner).toFloat(), c.y + (sin(a) * inner).toFloat()),
                Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat()),
                strokeWidth = stroke * 0.25f,
            )
            val tr = radius - stroke * 2.8f
            drawContext.canvas.nativeCanvas.drawText(
                k.toString(),
                c.x + (cos(a) * tr).toFloat(),
                c.y + (sin(a) * tr).toFloat() + textPaint.textSize * 0.35f,
                textPaint,
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    a.alpha + (b.alpha - a.alpha) * t,
)
