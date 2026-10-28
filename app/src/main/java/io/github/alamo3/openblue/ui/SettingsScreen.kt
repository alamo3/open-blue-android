package io.github.alamo3.openblue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.alamo3.openblue.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val busy = vm.busy != null
    var pin by remember { mutableStateOf("") }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Account") {
            InfoRow("Signed in as", vm.username ?: "—")
            vm.vehicle?.let { v ->
                InfoRow("Vehicle", v.nickname)
                InfoRow("Model", "${v.year} ${v.model}")
                InfoRow("VIN", v.vin)
            }
            TextButton(onClick = { vm.changeVehicle() }, enabled = !busy) { Text("Change vehicle") }
        }

        SectionCard("Bluelink PIN") {
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                label = { Text("New PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { vm.updatePin(pin); pin = "" }, enabled = pin.length == 4) { Text("Save PIN") }
        }

        SectionCard("Climate request format") {
            SwitchRow("Use Gen3 format (\"remoteControl\")", vm.useRemoteControl) { vm.setRemoteControlFormat(it) }
            Text(
                "Off (default) sends settings as \"hvacInfo\", which is what works for most Ioniq 5s. " +
                    "Only turn this on if climate commands are accepted but ignored by the car.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Diagnostics") {
            Text(
                "Shows the raw vehicle data Bluelink returns (feature flags, seat options, HVAC type).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { vm.loadDiagnostics() }, enabled = !busy) { Text("Show raw vehicle data") }
        }

        OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your saved email, password and PIN will be removed from this phone. Presets saved on this phone are kept.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out") } },
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
