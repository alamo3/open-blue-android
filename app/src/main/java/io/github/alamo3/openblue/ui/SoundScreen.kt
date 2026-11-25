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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.alamo3.openblue.sound.PiLink
import io.github.alamo3.openblue.sound.SoundReadout
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
    // Bluetooth permission for the Pi, asked again from its section if it was refused
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.changeUsePi(vm.usePi)               // starts the link now that it may search
    }
    val pickFmod = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFmod(uri)
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
                        } else if (soundPermissions().all(::granted)) {
                            vm.startSound()
                        } else {
                            askLocation.launch(soundPermissions())
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

                // A scripted drive, so a sound can be heard across its range while parked.
                val driving = r?.testDrive != null
                TextButton(onClick = { if (driving) vm.stopTestDrive() else vm.runTestDrive() }) {
                    Text(if (driving) "Stop test drive" else "Test drive: 0 to 100 km/h and back")
                }
            }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 12.dp))
            SectionTitle("Sound")
            Text("Made for an EV", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineSound.entries.filter { !it.isEngine && it.kind != EngineSound.Kind.BAKED }.forEach { s -> OptionPill(s.label, s == sound, { vm.changeEngineSound(s) }) }
            }
            Text("Cinematic", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineSound.entries.filter { it.kind == EngineSound.Kind.BAKED }.forEach { s -> OptionPill(s.label, s == sound, { vm.changeEngineSound(s) }) }
            }
            Text("Engines", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineSound.entries.filter { it.isEngine && it.kind != EngineSound.Kind.FMOD }.forEach { s -> OptionPill(s.label, s == sound, { vm.changeEngineSound(s) }) }
                vm.fmodSounds.forEach { f ->
                    OptionPill(f.name, sound == EngineSound.FMOD && vm.fmodSet == f.id, { vm.changeFmodSound(f.id) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { pickFmod.launch(null) }) { Text("Import FMOD sound…") }
                val chosen = vm.fmodSounds.firstOrNull { it.id == vm.fmodSet }
                if (sound == EngineSound.FMOD && chosen != null) {
                    TextButton(onClick = { vm.removeFmod(chosen.id) }) { Text("Remove ${chosen.name}") }
                }
            }
            vm.fmodMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            r?.fmodStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.primary) }
            Text(
                "Imported sounds play with FMOD Studio by Firelight Technologies Pty Ltd.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
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
                "Settle when cruising or idling",
                "On: after a few seconds of steady speed (from 20 km/h) or standing still, the engine eases down so it doesn't drone; it comes straight back when you press or lift.",
                vm.cruiseSettle,
            ) { vm.changeCruiseSettle(it) }
            SettingSwitch(
                "Hold revs when not accelerating",
                "Off: revs drop back to idle whenever you're not on the power. On: they stay where the gear would put them.",
                vm.holdRevs,
            ) { vm.changeHoldRevs(it) }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            SectionTitle("Raspberry Pi")
            Text(
                "With the virtual-car-sound Raspberry Pi plugged into the car's OBD port, the sound follows the " +
                    "car's real speed and pedal instead of guessing from the phone's sensors. It connects over " +
                    "Bluetooth by itself while the sound plays; no pairing needed.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            SettingSwitch(
                "Use car data from the Pi",
                "Off: always use the phone's sensors and GPS.",
                vm.usePi,
            ) { vm.changeUsePi(it) }
            if (vm.usePi) {
                SettingSwitch(
                    "Only play with car data",
                    "Off: fall back to the phone's sensors when the Pi isn't there. On: stay quiet until it is.",
                    vm.piOnly,
                ) { vm.changePiOnly(it) }
                Text(
                    piStatus(vm.soundOn, r, vm.piRemoteLink, vm.piRemote),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r?.piCar != null) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // The Pi's own sound (when it plays it): a remote
                androidx.compose.runtime.DisposableEffect(Unit) {
                    vm.attachPiRemote()
                    onDispose { vm.detachPiRemote() }
                }
                vm.piRemote?.native?.let { n -> PiSoundRemote(vm, vm.piRemote!!, n) }
                // what the Pi is doing: its engine's state and log (its separate Bluetooth service)
                vm.piRemote?.let { PiActivity(it, vm.piLog) }
                r?.dropouts?.let {
                    Text("Hiccups this session: $it", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                if (r?.piNoPermission == true && android.os.Build.VERSION.SDK_INT >= 31) {
                    TextButton(onClick = {
                        askBluetooth.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                    }) { Text("Allow Bluetooth") }
                }
            }

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
    EngineSound.Kind.RECORDED -> "A real engine recorded at several revs, played back the way racing games do: it follows the revs and your pedal, with its own shifts and pops."
    EngineSound.Kind.FMOD -> "A game's own FMOD sound design (imported from your phone), played by FMOD Studio by Firelight Technologies Pty Ltd., " +
        "following a virtual gearbox: its engine, shifts, limiter and backfires."
    EngineSound.Kind.BAKED -> "A cinematic sound from the Raspberry Pi engine, recorded across the speed range: it glides with your speed and swells with the pedal."
    EngineSound.Kind.FLAT6 -> "A boxer six built cylinder by cylinder: its exhaust pulses, intake howl and overrun crackle follow the gearbox's revs and your pedal."
    EngineSound.Kind.HYPERCAR -> "An electric hypercar in layers: an aggressive tone that shifts with the gearbox over the car's own motor whine, a snarl under load, air with speed."
    EngineSound.Kind.JET -> "A fighter's turbofan run through the gearbox: it spools up through each gear, buzzsaws at high power and lights the afterburner when floored."
    EngineSound.Kind.WARP -> "A warp drive: an endless rising drone as you accelerate, a pulsing field, debris streaking past, and a jump to warp when you launch hard."
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

/** Location for GPS; on Android 12+ also Bluetooth, to reach the car's Raspberry Pi. */
private fun soundPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}.toTypedArray()

private val PI_SOUNDS = listOf("Flat-6", "Hypercar", "Turbofan", "Warp bubble")

/** The remote for the Pi's own sound (virtual-car-sound --native-sound). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PiSoundRemote(vm: MainViewModel, s: io.github.alamo3.openblue.sound.PiStatus, n: io.github.alamo3.openblue.sound.PiNative) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Raspberry Pi sound", style = MaterialTheme.typography.titleMedium)
        Text(
            "The Pi plays the sound through its own speakers." +
                if (vm.soundOn) " The app's sound is on too: stop it to hear only the Pi." else "",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PI_SOUNDS.forEach { name -> OptionPill(name, s.voice == name, { vm.piSound(name) }) }
        }
        // volume: sent when let go (the Pi saves every change)
        var vol by remember(s.volume) { mutableFloatStateOf(s.volume) }
        Text("Volume ${(vol * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(value = vol, onValueChange = { vol = it }, onValueChangeFinished = { vm.piVolume(vol) }, colors = sliderColors(scheme.primary))
        SettingSwitch("Gearbox", "The dual-clutch gearbox: shifts, kickdown, the rev limiter.", n.gears) { vm.piGearshifts(it) }
        SettingSwitch("Settle when cruising or idling", "Eases the engine down after a few seconds of steady speed or standing still; back the moment you press or lift.", s.cruiseSettle) { vm.piCruiseSettle(it) }
        // rev it in place: springs back when let go
        var rev by remember { mutableFloatStateOf(0f) }
        Text("Rev it", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = rev,
            onValueChange = { rev = it; vm.piRev(it) },
            onValueChangeFinished = { rev = 0f; vm.piRev(null) },
            colors = sliderColors(scheme.tertiary),
        )
        val driving = n.testDrive != null
        TextButton(onClick = { vm.piTestDrive(!driving) }) {
            Text(if (driving) "Stop test drive" else "Test drive: 0 to 100 km/h and back")
        }
        Text(piLive(s, n), style = MaterialTheme.typography.bodySmall, color = if (n.live || driving) scheme.primary else scheme.onSurfaceVariant)
        if (s.message.isNotEmpty()) Text(s.message, style = MaterialTheme.typography.bodySmall, color = scheme.error)
    }
}

/** The Pi's engine state when it isn't simply running, and its log (newest at the bottom). */
@Composable
private fun PiActivity(s: io.github.alamo3.openblue.sound.PiStatus, log: List<String>) {
    val scheme = MaterialTheme.colorScheme
    val state = s.engineState
    if (state == null && log.isEmpty()) return               // a Pi without the Bluetooth service
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state != null && state != "running") {
            Text(
                when (state) {
                    "activating" -> "The Pi's engine is starting or updating…"
                    "failed" -> "The Pi's engine stopped after a failure: see its log."
                    "inactive" -> "The Pi's engine isn't running."
                    "deactivating" -> "The Pi's engine is stopping…"
                    else -> "The Pi's engine: $state"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == "failed") scheme.error else scheme.primary,
            )
        }
        if (log.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide the Pi's log" else "Show the Pi's log (${log.size} lines)") }
            if (open) {
                val scroll = rememberScrollState()
                androidx.compose.runtime.LaunchedEffect(log.size) { scroll.scrollTo(scroll.maxValue) }   // follow the newest
                Box(
                    Modifier.fillMaxWidth().height(260.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(scheme.surfaceVariant).padding(8.dp).verticalScroll(scroll),
                ) {
                    Text(
                        log.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun piLive(s: io.github.alamo3.openblue.sound.PiStatus, n: io.github.alamo3.openblue.sound.PiNative): String {
    if (n.gear == null) return "Waiting for the Pi's sound player…"
    val what = when {
        n.testDrive != null -> "Test drive: ${n.testDrive}"
        n.live -> "Following the car"
        else -> "No car data: quiet until the car is on"
    }
    val gear = if (n.gear >= 1) "gear ${n.gear}" else "neutral"
    return "$what · %.0f km/h · $gear · ${n.rpm} rpm".format(n.kmh) +
        (if (n.limiter) " · limiter" else "") +
        (if (n.settled) " · settled" else "") +
        " · CPU %.0f%%".format(n.cpu) +
        (if (n.underruns > 0) " · ${n.underruns} underruns" else "")
}

/** The Pi link, for its own section: state, and the live reading when there is one. */
private fun piStatus(
    soundOn: Boolean, r: SoundReadout?,
    link: PiLink.State, remote: io.github.alamo3.openblue.sound.PiStatus?,
): String {
    if (!soundOn || r == null) return when {
        // the sound is off, but the remote keeps a link to the Pi
        remote != null -> if (remote.carLive) "Connected: the Pi has live car data." else "Connected: the Pi is waiting for car data."
        link == PiLink.State.CONNECTED -> "Connected to the Pi."
        link == PiLink.State.CONNECTING -> "Found the Pi, connecting…"
        link == PiLink.State.SEARCHING -> "Searching for the Pi…"
        else -> "Connects when the sound starts."
    }
    r.piCar?.let { c ->
        return "Live: ${c.speedKmh.roundToInt()} km/h, pedal ${(c.pedal * 100).roundToInt()}%" +
            (if (c.motorRpm != 0) ", motor ${c.motorRpm} rpm" else "") +
            (if (c.reversing) ", reversing" else "")
    }
    return when {
        r.piNoPermission -> "Needs Bluetooth permission (Nearby devices) to find the Pi."
        r.piState == PiLink.State.CONNECTED -> "Connected. Waiting for car data: is the car on and the OBD adapter plugged in?"
        r.piState == PiLink.State.CONNECTING -> "Found the Pi, connecting…"
        r.piState == PiLink.State.SEARCHING -> "Searching for the Pi…"
        else -> "Off."
    }
}

private fun statusLine(r: SoundReadout?): String = when {
    r == null -> "Starting…"
    r.testDrive != null -> "Test drive: ${r.testDrive}, %.0f km/h.".format(r.speedKmh)
    r.carData -> "Following the car: live data from the Raspberry Pi."
    r.waitingForPi -> "Quiet until the Raspberry Pi has car data (\"Only play with car data\" is on)."
    r.piState == PiLink.State.CONNECTED ->
        "Raspberry Pi connected, waiting for car data. Using the phone's sensors meanwhile."
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
