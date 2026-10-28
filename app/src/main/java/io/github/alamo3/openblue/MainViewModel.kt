package io.github.alamo3.openblue

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.alamo3.openblue.api.ApiException
import io.github.alamo3.openblue.api.BluelinkClient
import io.github.alamo3.openblue.api.ClimateFormat
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.CommandResult
import io.github.alamo3.openblue.api.LoginResult
import io.github.alamo3.openblue.api.OtpChallenge
import io.github.alamo3.openblue.api.OtpRequiredException
import io.github.alamo3.openblue.api.ServerPreset
import io.github.alamo3.openblue.api.TempTables
import io.github.alamo3.openblue.api.VehicleCaps
import io.github.alamo3.openblue.api.VehicleInfo
import io.github.alamo3.openblue.data.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException

sealed interface Screen {
    data object Login : Screen
    data class Otp(val challenge: OtpChallenge) : Screen
    data class PickVehicle(val vehicles: List<VehicleInfo>) : Screen
    data object Main : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    private val api = BluelinkClient(store)
    private val prettyJson = Json { prettyPrint = true }

    var screen by mutableStateOf<Screen>(Screen.Login)
        private set
    /** Non-null while a request is running; the text describes what we're waiting on. */
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    var vehicle by mutableStateOf(store.vehicle)
        private set
    var status by mutableStateOf(store.cachedStatus?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() })
        private set
    var serverPresets by mutableStateOf<List<ServerPreset>>(emptyList())
        private set
    var localPresets by mutableStateOf(store.localPresets)
        private set
    var editor by mutableStateOf(store.lastClimate ?: ClimateSettings())
    var climateFormat by mutableStateOf(store.climateFormat)
        private set
    var caps by mutableStateOf(store.caps ?: VehicleCaps())
        private set
    var diagnostics by mutableStateOf<String?>(null)

    val username: String? get() = store.username

    // Credentials typed on the login screen, held until login fully succeeds.
    private var pendingUser: String? = null
    private var pendingPass: String? = null
    private var pendingPin: String? = null

    init {
        if (store.username != null && store.password != null && store.vehicle != null) {
            screen = Screen.Main
            val v = store.vehicle!!
            // One task for both: task() ignores calls while another is running.
            task("Refreshing…") {
                // sltvhcl returns status too, and refreshes capabilities (genType, heating, seats).
                val selected = api.selectVehicle(v.id)
                updateCaps(selected)
                (selected["status"] as? JsonObject)?.let { applyStatus(it) } ?: applyStatus(api.cachedStatus(v.id))
                serverPresets = api.serverPresets(v.id, tempTable)
            }
        }
    }

    val tempTable: List<TempTables.Entry>
        get() {
            val v = vehicle
            val type = caps.hvacTempType ?: if (v?.fuelKindCode.equals("E", true)) 1 else 0
            return TempTables.forVehicle(type, v?.model.orEmpty(), v?.fuelKindCode.orEmpty())
        }

    private fun task(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        viewModelScope.launch {
            busy = label
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OtpRequiredException) {
                pendingUser = store.username
                pendingPass = store.password
                pendingPin = store.pin
                screen = Screen.Otp(e.challenge)
                message = "Bluelink wants to verify this device again"
            } catch (e: ApiException) {
                message = e.message
            } catch (e: IOException) {
                message = "Network problem: ${e.message ?: e.javaClass.simpleName}"
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                busy = null
            }
        }
    }

    // ------------------------------------------------------------------ auth

    fun login(user: String, pass: String, pin: String) = task("Signing in…") {
        pendingUser = user.trim()
        pendingPass = pass
        pendingPin = pin.trim()
        when (val r = api.login(user.trim(), pass)) {
            is LoginResult.NeedsOtp -> screen = Screen.Otp(r.challenge)
            LoginResult.Success -> finishLogin()
        }
    }

    fun sendOtp(bySms: Boolean) {
        val s = screen as? Screen.Otp ?: return
        task("Sending code…") {
            screen = Screen.Otp(api.sendOtp(s.challenge, bySms))
            message = if (bySms) "Code sent by SMS" else "Code sent to ${s.challenge.email}"
        }
    }

    fun verifyOtp(code: String) {
        val s = screen as? Screen.Otp ?: return
        val user = pendingUser ?: return
        task("Verifying…") {
            api.verifyOtp(user, code, s.challenge)
            finishLogin()
        }
    }

    fun cancelOtp() {
        screen = if (store.vehicle != null && store.username != null) Screen.Main else Screen.Login
    }

    private suspend fun finishLogin() {
        pendingUser?.let { store.username = it }
        pendingPass?.let { store.password = it }
        pendingPin?.takeIf { it.isNotEmpty() }?.let { store.pin = it }
        pendingPass = null
        if (store.vehicle != null) {
            // Re-verification while already set up: just go back.
            screen = Screen.Main
            return
        }
        val list = api.vehicles()
        when {
            list.isEmpty() -> message = "No vehicles found on this account"
            list.size == 1 -> chooseVehicleNow(list.first())
            else -> screen = Screen.PickVehicle(list)
        }
    }

    fun changeVehicle() = task("Loading vehicles…") {
        screen = Screen.PickVehicle(api.vehicles())
    }

    fun chooseVehicle(v: VehicleInfo) = task("Loading ${v.nickname}…") { chooseVehicleNow(v) }

    private suspend fun chooseVehicleNow(v: VehicleInfo) {
        store.vehicle = v
        vehicle = v
        try {
            val selected = api.selectVehicle(v.id)
            updateCaps(selected)
            (selected["status"] as? JsonObject)?.let { applyStatus(it) }
        } catch (e: ApiException) {
            // Not fatal: fall back to defaults and the regular status call.
        }
        screen = Screen.Main
        runCatching { applyStatus(api.cachedStatus(v.id)) }
        runCatching { serverPresets = api.serverPresets(v.id, tempTable) }
    }

    private fun updateCaps(selected: JsonObject) {
        val c = api.capsFrom(selected)
        store.caps = c
        caps = c
    }

    /** The JSON key the next climate start will use, for display. */
    val climateKey: String get() = api.climateKey()

    fun signOut() {
        store.signOut()
        vehicle = null
        status = null
        serverPresets = emptyList()
        screen = Screen.Login
    }

    fun updatePin(pin: String) {
        store.pin = pin.trim()
        message = "PIN updated"
    }

    // ---------------------------------------------------------------- status

    private fun applyStatus(s: JsonObject) {
        status = s
        store.cachedStatus = s.toString()
    }

    fun refresh(live: Boolean, quiet: Boolean = false) {
        val v = vehicle ?: return
        task(if (live) "Waking the car for fresh status…" else "Refreshing…") {
            try {
                applyStatus(if (live) api.liveStatus(v.id) else api.cachedStatus(v.id))
            } catch (e: ApiException) {
                if (!quiet) throw e
            }
        }
    }

    // -------------------------------------------------------------- commands

    private fun command(label: String, okText: String, send: suspend (VehicleInfo) -> String) {
        val v = vehicle ?: return
        task("Sending $label…") {
            val tx = send(v)
            busy = "Waiting for the car to confirm $label…"
            message = when (api.awaitResult(v.id, tx)) {
                CommandResult.SUCCESS -> okText
                CommandResult.FAILED -> "The car reported that $label failed"
                CommandResult.TIMEOUT -> "No confirmation yet for $label — check the car's status in a minute"
            }
            runCatching { applyStatus(api.cachedStatus(v.id)) }
        }
    }

    fun lock() = command("lock", "Locked") { api.lock(it.id) }
    fun unlock() = command("unlock", "Unlocked") { api.unlock(it.id) }
    fun stopClimate() = command("climate stop", "Climate stopped") { api.stopClimate(it.id) }

    fun startClimate(settings: ClimateSettings = editor) {
        store.lastClimate = editor
        val fixed = settings.copy(heating = normalizeHeating(settings.heating))
        command("climate start", "Climate started") { api.startClimate(it, fixed) }
    }

    /** Map heating1 onto what this car accepts: split cars use 2/3/4, others a single on (1)/off (0). */
    private fun normalizeHeating(code: Int): Int = when {
        code == 0 -> 0
        caps.splitHeating -> if (code == 1) 4 else code
        else -> 1
    }

    // --------------------------------------------------------------- presets

    fun loadPresets(quiet: Boolean = false) {
        val v = vehicle ?: return
        task("Loading presets…") {
            try {
                serverPresets = api.serverPresets(v.id, tempTable)
            } catch (e: ApiException) {
                if (!quiet) throw e
            }
        }
    }

    fun saveLocalPreset(name: String) {
        val p = editor.copy(name = name.trim().ifEmpty { "Preset ${localPresets.size + 1}" })
        localPresets = localPresets.filterNot { it.name == p.name } + p
        store.localPresets = localPresets
        message = "Saved \"${p.name}\" on this phone"
    }

    fun deleteLocalPreset(p: ClimateSettings) {
        localPresets = localPresets - p
        store.localPresets = localPresets
    }

    fun editPreset(p: ClimateSettings) {
        editor = p
        message = "Loaded \"${p.name}\" into the editor"
    }

    // -------------------------------------------------------------- settings

    fun setFormat(f: ClimateFormat) {
        store.climateFormat = f
        climateFormat = f
    }

    fun loadDiagnostics() {
        val v = vehicle ?: return
        task("Fetching vehicle data…") {
            val raw = api.selectVehicle(v.id)
            diagnostics = prettyJson.encodeToString(JsonObject.serializer(), raw)
        }
    }
}
