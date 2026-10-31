package io.github.alamo3.openblue.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.BuildConfig
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ClimateFormat
import io.github.alamo3.openblue.work.AlertKind
import io.github.alamo3.openblue.work.Notifier
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, open: (Route) -> Unit = {}) {
    val app = AppTheme.colors
    var editingPin by remember { mutableStateOf(false) }
    var editingWorldKey by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.changeCarPhoto(uri)
    }
    fun pickPhoto() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 48.dp)) {
            SectionTitle("Account")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(vm.caps.photoUrl, vm.vehicle?.nickname.orEmpty(), onClick = { pickPhoto() }, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.username ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { pickPhoto() }, enabled = vm.busy == null, contentPadding = PaddingValues(0.dp)) {
                        Text("Change car photo")
                    }
                }
            }
            vm.vehicle?.let { DetailRow("Vehicle", it.nickname) }
            ClickRow("Change vehicle", "Pick another car on this account", enabled = vm.busy == null) { vm.changeVehicle() }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            SectionTitle("Your car in 3D")
            val auto = CarBody.forModel(vm.vehicle?.model)
            Picker(
                label = "Model",
                options = listOf<CarBody?>(null) + CarBody.entries,
                selected = vm.carBodyOverride,
                optionLabel = { b ->
                    when {
                        b == null && auto == CarBody.IONIQ5 -> "Automatic (IONIQ 5)"
                        b == null -> "Automatic (${auto.label.lowercase()} stand-in)"
                        b == CarBody.IONIQ5 -> "IONIQ 5"
                        else -> "${b.label} stand-in"
                    }
                },
                onSelect = { vm.changeCarBody(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The IONIQ 5 has its own model. Other cars get a stand-in of their body type, with doors, frunk, tailgate and the charging cable all working.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Paint", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                CarPaint.entries.forEach { p ->
                    val sel = vm.carPaint == p
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(34.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Color(p.swatch))
                            .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else app.hairline, androidx.compose.foundation.shape.CircleShape)
                            .clickable { vm.changeCarPaint(p) }
                            .semantics { contentDescription = p.label },
                    )
                }
            }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 16.dp))
            SectionTitle("Security")
            SettingSwitch(
                "Confirm before unlocking",
                "Ask for your fingerprint or screen lock before unlocking the car",
                vm.confirmUnlock,
            ) { vm.changeConfirmUnlock(it) }
            ClickRow("Bluelink PIN", "Used for remote commands. Change it here if you change it on Bluelink.") { editingPin = true }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            NotificationSettings(vm)

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            SectionTitle("Climate")
            Picker(
                label = "Request format",
                options = ClimateFormat.entries.toList(),
                selected = vm.climateFormat,
                optionLabel = { it.label },
                onSelect = { vm.setFormat(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Automatic matches the official app: \"remoteControl\" for Gen3 cars, \"hvacInfo\" for older ones. " +
                    "Your car reports ${vm.caps.genHeader ?: vm.caps.genType ?: "an unknown generation"}, so climate starts use \"${vm.climateKey}\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 16.dp))
            SectionTitle("Experiments")
            ClickRow(
                "World Labs API key",
                if (vm.worldLabsKeySet) "Saved. The 3D view can have World Labs generate a full 3D world from your photos (billed to your key)."
                else "Add your own key to have World Labs generate full 3D worlds from the 3D view's photos",
            ) { editingWorldKey = true }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 16.dp))
            SectionTitle("Developer")
            SettingSwitch(
                "Developer mode",
                "Shows a floating panel on every screen for simulating the car's state and testing commands without the car",
                vm.developerMode,
            ) { vm.changeDeveloperMode(it) }
            if (vm.developerMode) {
                ClickRow("Endpoint probe", "Look for Bluelink endpoints the official app doesn't use") { open(Route.Probe) }
            }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            SectionTitle("App updates")
            SettingSwitch(
                "Update automatically",
                "Every few hours on Wi-Fi, installs the newest build from GitHub while you're not using the app (never during the engine sound). Android asks you to confirm the first one.",
                vm.autoUpdate,
            ) { vm.changeAutoUpdate(it) }
            val u = vm.update
            val r = u.release
            // Android's "install unknown apps" permission for Open Blue: ask before downloading,
            // then carry on with the install the user was after.
            val ctx = LocalContext.current
            fun canInstall() = runCatching { io.github.alamo3.openblue.update.Updater.allowedToInstall(ctx) }.getOrDefault(true)
            var allowed by remember { mutableStateOf(canInstall()) }
            var installAfterAllow by remember { mutableStateOf(false) }
            val askInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                allowed = canInstall()
                if (allowed && installAfterAllow) vm.installUpdate()
                installAfterAllow = false
            }
            fun requestInstallPermission(thenInstall: Boolean) {
                installAfterAllow = thenInstall
                runCatching {
                    askInstall.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}")))
                }
            }
            if (!allowed) {
                ClickRow(
                    "Allow Open Blue to install updates",
                    "Android needs this once (\"Install unknown apps\"). Turn it on, then come back.",
                ) { requestInstallPermission(thenInstall = false) }
            }
            when {
                u.downloading != null -> ClickRow("Downloading ${(u.downloading * 100).toInt()}%", "The app closes for a moment while Android installs it", enabled = false) {}
                r != null -> ClickRow("Install ${r.versionName} now", r.message.ifBlank { null }) {
                    if (canInstall()) vm.installUpdate() else requestInstallPermission(thenInstall = true)
                }
                u.checking -> ClickRow("Checking…", null, enabled = false) {}
                else -> ClickRow("Check for updates", if (u.upToDate) "You have the newest build (${BuildConfig.VERSION_NAME})" else "This is ${BuildConfig.VERSION_NAME}") { vm.checkForUpdate() }
            }
            (u.error ?: io.github.alamo3.openblue.update.UpdateState.lastError)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = app.alert)
            }

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
            SectionTitle("Help")
            ClickRow("Show raw vehicle data", "What Bluelink reports for your car, for troubleshooting", enabled = vm.busy == null) { vm.loadDiagnostics() }
            Spacer(Modifier.height(8.dp))
            Text(
                "Open Blue ${BuildConfig.VERSION_NAME}. An unofficial app, not affiliated with Hyundai. " +
                    "Set in Barlow, licensed under the SIL Open Font License. " +
                    "3D car based on \"2024 Hyundai Ioniq 5 N\" by Ddiaz Design (sketchfab.com/ddiaz-design), " +
                    "licensed under CC BY-NC 4.0 and modified (doors, frunk and tailgate separated, repainted, simplified, shadow and charging cable added).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { confirmSignOut = true }) {
                Text("Sign out", color = app.alert, style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (editingPin) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingPin = false },
            title = { Text("Bluelink PIN") },
            text = {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                    label = { Text("4-digit PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            },
            confirmButton = { TextButton(onClick = { vm.updatePin(pin); editingPin = false }, enabled = pin.length == 4) { Text("Save PIN") } },
            dismissButton = { TextButton(onClick = { editingPin = false }) { Text("Cancel") } },
        )
    }

    if (editingWorldKey) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingWorldKey = false },
            title = { Text("World Labs API key") },
            text = {
                Column {
                    Text(
                        "From platform.worldlabs.ai. Kept encrypted on this phone and sent only to World Labs, when you ask for a world.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = key, onValueChange = { key = it.trim() },
                        label = { Text("API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.changeWorldLabsKey(key); editingWorldKey = false }, enabled = key.isNotBlank()) { Text("Save") } },
            dismissButton = {
                if (vm.worldLabsKeySet) TextButton(onClick = { vm.changeWorldLabsKey(null); editingWorldKey = false }) { Text("Remove key", color = app.alert) }
                else TextButton(onClick = { editingWorldKey = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your email, password and PIN are removed from this phone. Presets and camera photos saved on this phone are kept.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out", color = app.alert) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }

    vm.diagnostics?.let { text ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { vm.diagnostics = null },
            title = { Text("Raw vehicle data") },
            text = {
                Column {
                    Text(
                        "IDs, the VIN's serial number, names, contact details and the car's location are removed, so this is safe to share for help.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SelectionContainer {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { vm.diagnostics = null }) { Text("Close") } },
        )
    }
}

@Composable
internal fun ClickRow(title: String, subtitle: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NotificationSettings(vm: MainViewModel) {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    var allowed by remember { mutableStateOf(inspecting || runCatching { Notifier.allowed(context) }.getOrDefault(true)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted || runCatching { Notifier.allowed(context) }.getOrDefault(false)
    }
    fun ensureAllowed() {
        if (allowed) return
        if (Build.VERSION.SDK_INT >= 33) {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }

    SectionTitle("Notifications")
    if (!allowed && (vm.notifyCommands || vm.watchEnabled)) {
        ClickRow("Allow notifications", "Android is blocking them for Open Blue, so nothing below will show") { ensureAllowed() }
    }
    SettingSwitch(
        "Command results",
        "When a lock, climate or other command finishes after you've left the app",
        vm.notifyCommands,
    ) {
        vm.changeNotifyCommands(it)
        if (it) ensureAllowed()
    }
    SettingSwitch(
        "Check the car in the background",
        "Reads the status Bluelink already has, the same as pulling to refresh, so the car isn't woken",
        vm.watchEnabled,
    ) {
        vm.changeWatch(it)
        if (it) ensureAllowed()
    }
    if (vm.watchEnabled) {
        Picker(
            label = "How often",
            options = listOf(15, 30, 60),
            selected = vm.watchMinutes,
            optionLabel = { if (it < 60) "Every $it minutes" else "Every hour" },
            onSelect = { vm.changeWatchMinutes(it) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        AlertKind.entries.forEach { kind ->
            SettingSwitch(kind.label, kind.detail, vm.alertsOn[kind] != false) { vm.changeAlert(kind, it) }
        }
        Text(
            "Android decides the exact timing and may wait longer when the phone is idle or saving battery. " +
                "Alerts are only as fresh as what the car last sent Bluelink.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
