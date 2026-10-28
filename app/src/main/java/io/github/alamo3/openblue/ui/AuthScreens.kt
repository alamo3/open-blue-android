package io.github.alamo3.openblue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.Screen
import io.github.alamo3.openblue.api.VehicleInfo

@Composable
fun LoginScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    val busy = vm.busy != null

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Open Blue", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Sign in with your Hyundai Bluelink Canada account (mybluelink.ca).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(4) },
            label = { Text("Bluelink PIN (for remote commands)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.login(email, password, pin) },
            enabled = !busy && email.isNotBlank() && password.isNotEmpty() && pin.length == 4,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in") }
        Card {
            Text(
                "Your email, password and PIN are stored encrypted on this phone only, and are sent " +
                    "only to mybluelink.ca. This is an unofficial app and is not affiliated with Hyundai.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
fun OtpScreen(vm: MainViewModel, state: Screen.Otp, modifier: Modifier = Modifier) {
    var code by rememberSaveable { mutableStateOf("") }
    val busy = vm.busy != null
    val sent = state.challenge.otpKey != null

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Verify this phone", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Bluelink hasn't seen this device before, so it wants a one-time code. " +
                "You should only need to do this once.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.sendOtp(bySms = false) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Email code")
            }
            if (state.challenge.phone != null) {
                OutlinedButton(onClick = { vm.sendOtp(bySms = true) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Text code")
                }
            }
        }
        Text(
            "Email: ${state.challenge.email}" + (state.challenge.phone?.let { "\nPhone: $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isLetterOrDigit).take(10) },
            label = { Text("Code") }, singleLine = true, enabled = sent,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.verifyOtp(code) },
            enabled = !busy && sent && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Verify") }
        TextButton(onClick = { vm.cancelOtp() }) { Text("Cancel") }
    }
}

@Composable
fun VehiclePickerScreen(vm: MainViewModel, vehicles: List<VehicleInfo>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Choose a vehicle", style = MaterialTheme.typography.headlineMedium)
        vehicles.forEach { v ->
            OutlinedButton(onClick = { vm.chooseVehicle(v) }, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(v.nickname, style = MaterialTheme.typography.titleMedium)
                    Text("${v.year} ${v.model} · ${v.vin}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
