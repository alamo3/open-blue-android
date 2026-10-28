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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.BuildConfig
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ClimateFormat

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val app = AppTheme.colors
    var editingPin by remember { mutableStateOf(false) }
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
            SectionTitle("Security")
            SettingSwitch(
                "Confirm before unlocking",
                "Ask for your fingerprint or screen lock before unlocking the car",
                vm.confirmUnlock,
            ) { vm.changeConfirmUnlock(it) }
            ClickRow("Bluelink PIN", "Used for remote commands. Change it here if you change it on Bluelink.") { editingPin = true }

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
                    "Your car reports ${vm.caps.genType ?: "an unknown generation"}, so climate starts use \"${vm.climateKey}\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 16.dp))
            SectionTitle("Developer")
            SettingSwitch(
                "Developer mode",
                "Shows a floating panel on every screen for simulating the car's state and testing commands without the car",
                vm.developerMode,
            ) { vm.changeDeveloperMode(it) }

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
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { vm.diagnostics = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun ClickRow(title: String, subtitle: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
