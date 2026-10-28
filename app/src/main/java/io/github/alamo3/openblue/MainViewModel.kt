package io.github.alamo3.openblue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import io.github.alamo3.openblue.api.SvmCapture
import io.github.alamo3.openblue.api.TempTables
import io.github.alamo3.openblue.api.VehicleCaps
import io.github.alamo3.openblue.api.VehicleInfo
import io.github.alamo3.openblue.data.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** A remote command in flight, shown as a progress strip until the car answers. */
data class CommandProgress(val action: Action, val stage: Stage, val note: String? = null) {
    enum class Stage { SENDING, WAITING, DONE, FAILED }

    enum class Action(val doing: String, val done: String) {
        LOCK("Locking", "Locked"),
        UNLOCK("Unlocking", "Unlocked"),
        CLIMATE_START("Starting climate", "Climate is on"),
        CLIMATE_STOP("Stopping climate", "Climate is off"),
        WAKE("Asking the car for its status", "Status updated"),
        CAMERA("Asking the car for photos", "New photos are in"),
    }
}

/**
 * [online] = false keeps the ViewModel from making network calls on its own (used by the
 * screenshot tests, which render screens from stored demo data).
 */
class MainViewModel(
    app: Application,
    private val store: SecureStore,
    private val online: Boolean,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, SecureStore(app), online = true)

    private val api = BluelinkClient(store)
    private val prettyJson = Json { prettyPrint = true }

    var screen by mutableStateOf<Screen>(Screen.Login)
        private set

    /** Non-null while a foreground request (sign-in, loading) runs. Blocks other actions. */
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<CommandProgress?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    var vehicle by mutableStateOf(store.vehicle)
        private set
    var caps by mutableStateOf(store.caps ?: VehicleCaps())
        private set
    var status by mutableStateOf(store.cachedStatus?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() })
        private set
    var serverPresets by mutableStateOf<List<ServerPreset>>(emptyList())
        private set
    var localPresets by mutableStateOf(store.localPresets)
        private set
    var editor by mutableStateOf(store.lastClimate ?: ClimateSettings())
        private set
    var climateFormat by mutableStateOf(store.climateFormat)
        private set
    var confirmUnlock by mutableStateOf(store.confirmUnlock)
        private set
    var svm by mutableStateOf<List<SvmCapture>>(emptyList())
        private set
    var svmLoaded by mutableStateOf(false)
        private set
    var diagnostics by mutableStateOf<String?>(null)


    val username: String? get() = store.username

    /** True when no command or blocking request is running. */
    val canAct: Boolean
        get() = busy == null && progress.let {
            it == null || it.stage == CommandProgress.Stage.DONE || it.stage == CommandProgress.Stage.FAILED
        }

    // Credentials typed on the login screen, held until login fully succeeds.
    private var pendingUser: String? = null
    private var pendingPass: String? = null
    private var pendingPin: String? = null
    private var clearProgressJob: Job? = null

    init {
        val v = store.vehicle
        if (store.username != null && store.password != null && v != null) {
            screen = Screen.Main
        }
        if (online && screen == Screen.Main && v != null) {
            refreshing = true
            launchSafely(onDone = { refreshing = false }) {
                loadVehicle(v)
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

    /** The JSON key the next climate start will use, for display. */
    val climateKey: String get() = api.climateKey()

    // ---------------------------------------------------------------- plumbing

    private fun describe(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Bluelink returned an error"
        is IOException -> "Couldn't reach Bluelink. Check your connection and try again."
        else -> e.message ?: e.toString()
    }

    /** Runs [block], routing errors to [onError] and device re-verification to the code screen. */
    private fun launchSafely(
        onDone: () -> Unit = {},
        onError: (Throwable) -> Unit = { message = describe(it) },
        block: suspend () -> Unit,
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OtpRequiredException) {
            pendingUser = store.username
            pendingPass = store.password
            pendingPin = store.pin
            screen = Screen.Otp(e.challenge)
            message = "Bluelink wants to verify this phone again"
            progress = null
        } catch (e: Exception) {
            onError(e)
        } finally {
            onDone()
        }
    }

    private fun task(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        launchSafely(onDone = { busy = null }, block = block)
    }

    // ------------------------------------------------------------------ auth

    fun login(user: String, pass: String, pin: String) = task("Signing in") {
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
        task("Sending code") {
            screen = Screen.Otp(api.sendOtp(s.challenge, bySms))
            message = if (bySms) "Code sent by text message" else "Code sent to ${s.challenge.email}"
        }
    }

    fun verifyOtp(code: String) {
        val s = screen as? Screen.Otp ?: return
        val user = pendingUser ?: return
        task("Checking code") {
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
            list.isEmpty() -> message = "This Bluelink account has no vehicles"
            list.size == 1 -> chooseVehicleNow(list.first())
            else -> screen = Screen.PickVehicle(list)
        }
    }

    fun changeVehicle() = task("Loading vehicles") {
        screen = Screen.PickVehicle(api.vehicles())
    }

    fun chooseVehicle(v: VehicleInfo) = task("Loading ${v.nickname}") { chooseVehicleNow(v) }

    private suspend fun chooseVehicleNow(v: VehicleInfo) {
        store.vehicle = v
        vehicle = v
        svm = emptyList()
        svmLoaded = false
        try {
            loadVehicle(v)
        } catch (e: ApiException) {
            message = describe(e)
        }
        screen = Screen.Main
        try {
            serverPresets = api.serverPresets(v.id, tempTable)
        } catch (e: ApiException) {
            // Presets are optional; the Climate tab offers a reload.
        }
    }

    /** sltvhcl: capabilities + status in one call; falls back to the plain status call. */
    private suspend fun loadVehicle(v: VehicleInfo) {
        val selected = api.selectVehicle(v.id)
        val c = api.capsFrom(selected)
        store.caps = c
        caps = c
        val s = selected["status"] as? JsonObject ?: api.cachedStatus(v.id)
        applyStatus(s)
    }

    fun signOut() {
        store.signOut()
        vehicle = null
        status = null
        caps = VehicleCaps()
        serverPresets = emptyList()
        svm = emptyList()
        svmLoaded = false
        progress = null
        screen = Screen.Login
    }

    /** Replaces the car photo on Bluelink (the picture shown top right), like the official app. */
    fun changeCarPhoto(uri: Uri) {
        val v = vehicle ?: return
        task("Uploading photo") {
            val b64 = withContext(Dispatchers.IO) { encodePhoto(uri) }
            val url = api.uploadVehiclePhoto(v.id, b64)
            if (url != null) {
                val c = caps.copy(photoUrl = url)
                store.caps = c
                caps = c
            }
            message = "Car photo updated"
        }
    }

    /** Downscale to ~800 px wide and compress to under 100 KB, as the official app does. */
    private fun encodePhoto(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = if (bounds.outWidth >= 800) bounds.outWidth / 800 else 1
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Couldn't read that photo")
        var quality = 90
        var bytes: ByteArray
        do {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 10
        } while (bytes.size > 100 * 1024 && quality > 20)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
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

    /** Pull-to-refresh: reloads what Bluelink has stored. Doesn't wake the car. */
    fun refresh() {
        val v = vehicle ?: return
        if (refreshing) return
        refreshing = true
        launchSafely(onDone = { refreshing = false }) {
            applyStatus(api.cachedStatus(v.id))
        }
    }

    /** Asks the car itself for fresh status. Slower and wakes the car's modem. */
    fun wakeCar() {
        val v = vehicle ?: return
        if (!canAct) return
        val action = CommandProgress.Action.WAKE
        showProgress(CommandProgress(action, CommandProgress.Stage.WAITING, "This can take up to a minute."))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            applyStatus(api.liveStatus(v.id))
            finish(action, true)
        }
    }

    // -------------------------------------------------------------- commands

    private fun showProgress(p: CommandProgress) {
        clearProgressJob?.cancel()
        progress = p
    }

    private fun finish(action: CommandProgress.Action, ok: Boolean, note: String? = null) {
        showProgress(CommandProgress(action, if (ok) CommandProgress.Stage.DONE else CommandProgress.Stage.FAILED, note))
        clearProgressJob = viewModelScope.launch {
            delay(if (ok) 3_500 else 10_000)
            progress = null
        }
    }

    fun dismissProgress() {
        val stage = progress?.stage
        if (stage == CommandProgress.Stage.DONE || stage == CommandProgress.Stage.FAILED) progress = null
    }

    private fun command(action: CommandProgress.Action, send: suspend (VehicleInfo) -> String) {
        val v = vehicle ?: return
        if (!canAct) return
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val tx = send(v)
            showProgress(CommandProgress(action, CommandProgress.Stage.WAITING))
            when (api.awaitResult(v.id, tx)) {
                CommandResult.SUCCESS -> finish(action, true)
                CommandResult.FAILED -> finish(action, false, "The car didn't accept the command.")
                CommandResult.TIMEOUT -> finish(action, false, "No answer from the car yet. It may still go through, so check again in a minute.")
            }
            try {
                applyStatus(api.cachedStatus(v.id))
            } catch (e: ApiException) {
                // The command result is what matters; status will refresh next time.
            }
        }
    }

    fun lock() = command(CommandProgress.Action.LOCK) { api.lock(it.id) }
    fun unlock() = command(CommandProgress.Action.UNLOCK) { api.unlock(it.id) }
    fun stopClimate() = command(CommandProgress.Action.CLIMATE_STOP) { api.stopClimate(it.id) }

    fun startClimate(settings: ClimateSettings = editor) {
        val fixed = settings.copy(heating = normalizeHeating(settings.heating))
        command(CommandProgress.Action.CLIMATE_START) { api.startClimate(it, fixed) }
    }

    /** Map heating1 onto what this car accepts: split cars use 2/3/4, others a single on (1)/off (0). */
    private fun normalizeHeating(code: Int): Int = when {
        code == 0 -> 0
        caps.splitHeating -> if (code == 1) 4 else code
        else -> 1
    }

    fun updateEditor(block: (ClimateSettings) -> ClimateSettings) {
        editor = block(editor)
        store.lastClimate = editor
    }

    fun cycleSeat(index: Int) = updateEditor { e -> e.withSeat(index, caps.nextSeatLevel(index, e.seats[index]).code) }

    // --------------------------------------------------------- surround view

    fun loadSurroundView(force: Boolean = false) {
        val v = vehicle ?: return
        if (!online || (svmLoaded && !force)) return
        if (force) refreshing = true
        launchSafely(onDone = { svmLoaded = true; refreshing = false }) {
            svm = api.lastSurroundView(v.id)
        }
    }

    /** Asks the car for new photos, then polls until a capture newer than the last one shows up. */
    fun captureSurroundView() {
        val v = vehicle ?: return
        if (!canAct) return
        val action = CommandProgress.Action.CAMERA
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val before = svm.firstOrNull()?.utcTime
            api.requestSurroundView(v.id)
            showProgress(CommandProgress(action, CommandProgress.Stage.WAITING, "This usually takes about a minute."))
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                delay(10_000)
                val latest = try {
                    api.lastSurroundView(v.id)
                } catch (e: ApiException) {
                    continue
                }
                if (latest.isNotEmpty() && latest.first().utcTime != before) {
                    svm = latest
                    svmLoaded = true
                    finish(action, true)
                    return@launchSafely
                }
            }
            finish(action, false, "The car hasn't sent photos yet. Pull down on this screen in a minute to check again.")
        }
    }

    // --------------------------------------------------------------- presets

    fun loadPresets() {
        val v = vehicle ?: return
        task("Loading presets") { serverPresets = api.serverPresets(v.id, tempTable) }
    }

    fun saveLocalPreset(name: String) {
        val p = editor.copy(name = name.trim().ifEmpty { "Preset ${localPresets.size + 1}" })
        localPresets = localPresets.filterNot { it.name == p.name } + p
        store.localPresets = localPresets
        message = "Saved \"${p.name}\""
    }

    fun deleteLocalPreset(p: ClimateSettings) {
        localPresets = localPresets - p
        store.localPresets = localPresets
        message = "Deleted \"${p.name}\""
    }

    fun editPreset(p: ClimateSettings) {
        updateEditor { p }
        message = "Loaded \"${p.name}\" into the controls above"
    }

    // -------------------------------------------------------------- settings

    fun setFormat(f: ClimateFormat) {
        store.climateFormat = f
        climateFormat = f
    }

    fun changeConfirmUnlock(on: Boolean) {
        store.confirmUnlock = on
        confirmUnlock = on
    }

    fun loadDiagnostics() {
        val v = vehicle ?: return
        task("Fetching vehicle data") {
            val raw = api.selectVehicle(v.id)
            diagnostics = prettyJson.encodeToString(JsonObject.serializer(), raw)
        }
    }
}
