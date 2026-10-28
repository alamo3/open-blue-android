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
import io.github.alamo3.openblue.api.ActiveSession
import io.github.alamo3.openblue.api.ChargeHistory
import io.github.alamo3.openblue.api.ChargeOffer
import io.github.alamo3.openblue.api.Charger
import io.github.alamo3.openblue.api.SessionStatus
import io.github.alamo3.openblue.api.ChargePlan
import io.github.alamo3.openblue.api.ChargeService
import io.github.alamo3.openblue.api.Station
import io.github.alamo3.openblue.data.Gallery
import io.github.alamo3.openblue.data.SvmArchive
import io.github.alamo3.openblue.data.SvmShot
import io.github.alamo3.openblue.api.TempTables
import io.github.alamo3.openblue.api.VehicleCaps
import io.github.alamo3.openblue.api.VehicleInfo
import io.github.alamo3.openblue.data.SecureStore
import io.github.alamo3.openblue.debug.DebugState
import io.github.alamo3.openblue.sound.EngineSound
import io.github.alamo3.openblue.sound.EngineSoundService
import io.github.alamo3.openblue.sound.SoundController
import io.github.alamo3.openblue.sound.SoundReadout
import io.github.alamo3.openblue.work.AlertKind
import io.github.alamo3.openblue.work.CommandWorker
import io.github.alamo3.openblue.work.StatusWatchWorker
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
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
        HORN("Sounding the horn and flashing the lights", "Horn and lights done"),
        LIGHTS("Flashing the lights", "Lights flashed"),
        PORT_OPEN("Opening the charge port", "Charge port open"),
        PORT_CLOSE("Closing the charge port", "Charge port closed"),
        PRECONDITION_ON("Turning on battery preconditioning", "Battery preconditioning on"),
        PRECONDITION_OFF("Turning off battery preconditioning", "Battery preconditioning off"),
        CHARGE_LIMITS("Saving charge limits", "Charge limits saved"),
        LOCATE("Asking the car where it is", "Found the car"),
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
    private val archive: SvmArchive? = null,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, SecureStore(app), online = true, archive = SvmArchive(java.io.File(app.filesDir, "svm")))

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
    /** The last status Bluelink reported. Screens read [status], which developer mode can override. */
    private var realStatus by mutableStateOf(store.cachedStatus?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() })

    var developerMode by mutableStateOf(store.developerMode)
        private set
    var debug by mutableStateOf(DebugState())
        private set

    /** What the UI shows: Bluelink's status, or the simulated car when developer mode says so. */
    val status: JsonObject?
        get() = if (developerMode && debug.simulate) debug.toStatusJson(tempTable) else realStatus
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
    /** Surround-view photos, newest first: everything archived on this phone plus Bluelink's latest. */
    var svm by mutableStateOf<List<SvmShot>>(emptyList())
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
            runCatching {
                io.github.alamo3.openblue.work.Notifier.createChannels(getApplication())
                StatusWatchWorker.sync(getApplication(), store)
            }
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
        if (store.vehicle?.id != v.id) {
            store.lastLocation = null
            location = null
            chargeService = null
            stations = emptyList()
            chargeHistory = null
        }
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
        if (online) StatusWatchWorker.stop(getApplication())
        store.signOut()
        notifyCommands = store.notifyCommands
        watchEnabled = store.watchEnabled
        watchMinutes = store.watchMinutes
        alertsOn = AlertKind.entries.associateWith { store.alertOn(it.key) }
        vehicle = null
        realStatus = null
        caps = VehicleCaps()
        serverPresets = emptyList()
        svm = emptyList()
        svmLoaded = false
        location = null
        chargeService = null
        stations = emptyList()
        chargeHistory = null
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
        realStatus = s
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
        if (developerMode && debug.dryRun) {
            dryRun(CommandProgress.Action.WAKE) { it }
            return
        }
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

    private fun command(
        action: CommandProgress.Action,
        simulate: (DebugState) -> DebugState = { it },
        send: suspend (VehicleInfo) -> String,
    ) {
        val v = vehicle ?: return
        if (!canAct) return
        if (developerMode && debug.dryRun) {
            dryRun(action, simulate)
            return
        }
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val tx = send(v)
            showProgress(CommandProgress(action, CommandProgress.Stage.WAITING))
            when (awaitCommand(v, tx, action)) {
                CommandResult.SUCCESS -> finish(action, true)
                CommandResult.FAILED -> finish(action, false, "The car didn't accept the command.")
                CommandResult.TIMEOUT -> finish(action, false, "No answer from the car yet. It may still go through, so check again in a minute.")
            }
        }
    }

    /**
     * Waits for the car's answer. The waiting itself runs as background work, so it carries on
     * (and ends in a notification) if the app is closed; the app just follows along while open.
     * Leaves the latest status in [status] either way.
     */
    private suspend fun awaitCommand(v: VehicleInfo, tx: String, action: CommandProgress.Action): CommandResult {
        if (!online) {
            val r = api.awaitResult(v.id, tx)
            runCatching { applyStatus(api.cachedStatus(v.id)) }
            return r
        }
        val ctx = getApplication<Application>()
        CommandWorker.watching += tx
        try {
            val id = CommandWorker.enqueue(ctx, v.id, tx, action, v.nickname.ifBlank { "Your car" })
            val info = WorkManager.getInstance(ctx).getWorkInfoByIdFlow(id).first { it?.state?.isFinished == true }
            store.cachedStatus?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() }?.let { realStatus = it }
            return info?.outputData?.getString(CommandWorker.KEY_RESULT)
                ?.let { runCatching { CommandResult.valueOf(it) }.getOrNull() }
                ?: CommandResult.TIMEOUT
        } finally {
            CommandWorker.watching -= tx
        }
    }

    fun lock() = command(CommandProgress.Action.LOCK, { it.copy(locked = true) }) { api.lock(it.id) }
    fun unlock() = command(CommandProgress.Action.UNLOCK, { it.copy(locked = false) }) { api.unlock(it.id) }
    fun stopClimate() = command(CommandProgress.Action.CLIMATE_STOP, {
        it.copy(climateOn = false, defrost = false, wheelHeat = false, seats = listOf(2, 2, 2, 2))
    }) { api.stopClimate(it.id) }

    fun startClimate(settings: ClimateSettings = editor) {
        val fixed = settings.copy(heating = normalizeHeating(settings.heating))
        command(CommandProgress.Action.CLIMATE_START, {
            it.copy(
                climateOn = fixed.climateOn, tempC = fixed.tempC, defrost = fixed.defrost,
                wheelHeat = fixed.heating != 0, seats = fixed.seats.map { s -> if (s == 0) 2 else s },
            )
        }) { api.startClimate(it, fixed) }
    }

    // ------------------------------------------------- public charging (read-only)

    var chargeService by mutableStateOf<ChargeService?>(null)
        private set
    var chargeServiceError by mutableStateOf<String?>(null)
        private set
    /** What was sent and received the last time the service was asked, for troubleshooting. */
    val chargeServiceDetails: String? get() = api.lastExchange

    fun loadChargeService(force: Boolean = false) {
        val v = vehicle ?: return
        if (!online || (chargeService != null && !force)) return
        chargeServiceError = null
        launchSafely(onError = { chargeServiceError = describe(it) }) {
            // The service identifies the car by its vehicleKindCode from the vehicle list, as the
            // official app does; sltvhcl may not carry it, so fetch the list if we don't have it.
            var code = v.kindCode
            if (code == null) {
                api.vehicles().firstOrNull { it.id == v.id }?.let { fresh ->
                    store.vehicle = fresh
                    vehicle = fresh
                    code = fresh.kindCode
                }
            }
            if (code == null) code = caps.modelCode
            val r = api.iacServiceInfo(v.id, code, v.year)
            var service = ChargeService.from(r)
            if (service.plans.isEmpty()) {
                // Plans may only come from the subscription call.
                runCatching { api.iacSubscriptions(v.id, code, v.year) }.getOrNull()?.let { list ->
                    service = service.copy(plans = list.map { ChargePlan.from(it) })
                }
            }
            chargeService = service
        }
    }

    /** Stations found around [stationsCenter], nearest first. */
    var stations by mutableStateOf<List<Station>>(emptyList())
        private set
    var stationsLoading by mutableStateOf(false)
        private set
    var stationsError by mutableStateOf<String?>(null)
        private set
    var stationsCenter by mutableStateOf<Pair<Double, Double>?>(null)
        private set
    /** Bumped on every search, so the map reframes around the results. */
    var stationsSearch by mutableStateOf(0)
        private set

    /** Finds stations of every speed (the screen filters AC/DC itself; the server's speed filter isn't reliable). */
    fun searchStations(lat: Double, lon: Double, speeds: String? = null, radiusKm: Int = 15) {
        val v = vehicle ?: return
        if (!online) return
        stationsLoading = true
        stationsError = null
        stationsCenter = lat to lon
        launchSafely(onDone = { stationsLoading = false }, onError = { stationsError = describe(it) }) {
            stations = api.iacStations(v.id, lat, lon, radiusKm, speeds)
                .mapNotNull { Station.from(it) }
                .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            stationsSearch++
        }
    }

    /** Full details for one station (chargers and connectors), merged into the list. */
    fun loadStation(id: String) {
        val v = vehicle ?: return
        if (!online) return
        launchSafely(onError = { }) {
            val detail = api.iacStation(v.id, id)?.let { Station.from(it) } ?: return@launchSafely
            stations = stations.map { if (it.id == id) detail.copy(distanceKm = it.distanceKm ?: detail.distanceKm) else it }
        }
    }

    /**
     * Developer mode and screenshots: made-up stations around the car (or downtown Toronto),
     * a plan, a card and a month of history, to see the charging screens without the service.
     */
    fun showDemoCharging() {
        val (lat, lon) = location?.let { it.lat to it.lon } ?: (43.6534 to -79.3841)
        chargeService = ChargeService(
            inAppCharging = true, remoteStart = true, stationSearch = true, plugAndCharge = true, routePlanning = true,
            cardRegistered = true,
            cards = listOf(io.github.alamo3.openblue.api.PayCard("Visa", "Visa", "************4242")),
            plans = listOf(ChargePlan("Demo charging plan", "ACTIVE", "20260101", "20271231")),
        )
        fun st(i: Int, name: String, net: String, dLat: Double, dLon: Double, kw: Double, speed: String, free: Int, total: Int, down: Int = 0) = Station(
            id = "demo$i", name = name, address = "${100 + i * 7} Example Street", network = net,
            lat = lat + dLat, lon = lon + dLon, distanceKm = kotlin.math.sqrt(dLat * dLat + dLon * dLon) * 111,
            maxKw = kw, speed = io.github.alamo3.openblue.api.ChargeSpeed.of(speed), available = free, total = total, outOfService = down,
            price = if (speed == "slow") "\$2.00 per hour" else "\$0.45 per kWh", phone = null,
            chargers = (1..total).map { n ->
                io.github.alamo3.openblue.api.Charger(
                    "c$n", "Stall $n",
                    listOf(io.github.alamo3.openblue.api.Connector(listOf(if (speed == "slow") "J1772" else "CCS1"), kw, if (n <= free) "AVAILABLE" else if (n > total - down) "OUTOFORDER" else "CHARGING", null)),
                    null,
                )
            },
        )
        stations = listOf(
            st(1, "Market Square", "ChargePoint", 0.004, 0.003, 7.2, "slow", 2, 4),
            st(2, "Harbour Hub", "Electrify Canada", -0.012, 0.018, 350.0, "ultra", 3, 6),
            st(3, "Union Plaza", "FLO", 0.015, -0.01, 100.0, "rapid", 0, 2),
            st(4, "Library Garage", "ChargePoint", -0.02, -0.022, 7.2, "slow", 0, 2, down = 2),
            st(5, "Highway Services", "Petro-Canada", 0.03, 0.035, 200.0, "ultra", 1, 4),
        ).sortedBy { it.distanceKm }
        stationsCenter = lat to lon
        stationsSearch++
        chargeHistory = ChargeHistory(
            orders = listOf(
                io.github.alamo3.openblue.api.ChargeOrder("1", "20261024183000", "20261024190500", "Harbour Hub", "107 Example Street, Toronto", 41.6, 18.72, "CAD", "4242", null, "COMPLETE"),
                io.github.alamo3.openblue.api.ChargeOrder("2", "20261012120000", "20261012124000", "Highway Services", "135 Example Street, Toronto", 52.3, 23.54, "CAD", "4242", null, "COMPLETE"),
            ),
            kwh = 93.9, total = 42.26,
        )
    }

    // ------------------------------------------------- paid charging sessions

    var offer by mutableStateOf<ChargeOffer?>(null)
        private set
    var offerFor by mutableStateOf<String?>(null)
        private set
    var offerLoading by mutableStateOf(false)
        private set
    var offerError by mutableStateOf<String?>(null)
        private set
    val savedCardId: String? get() = store.payCardId

    /** Station ID the charge is addressed to (Tesla uses the charger's own station ID). */
    private fun poiFor(station: Station, charger: Charger) =
        if (station.isTesla) charger.stationId ?: station.id else station.id

    /** Price and payment details for one charger. */
    fun loadOffer(station: Station, charger: Charger) {
        val v = vehicle ?: return
        val chargerId = charger.id ?: return
        offer = null
        offerError = null
        offerFor = chargerId
        if (!online || dryRunning) {
            offer = demoOffer(charger)
            return
        }
        offerLoading = true
        launchSafely(onDone = { offerLoading = false }, onError = { offerError = describe(it) }) {
            val o = ChargeOffer.from(api.iacOffer(v.id, poiFor(station, charger), chargerId))
            if (offerFor == chargerId) offer = o
        }
    }

    fun clearOffer() {
        offer = null
        offerFor = null
        offerError = null
    }

    var session by mutableStateOf(store.activeSession)
        private set
    var sessionStatus by mutableStateOf<SessionStatus?>(null)
        private set
    var sessionBusy by mutableStateOf(false)
        private set

    /** Starts a paid charge at [charger]. The screen confirms the price and identity first. */
    fun startCharge(station: Station, charger: Charger, cardId: String?, onStarted: () -> Unit) {
        val v = vehicle ?: return
        val o = offer ?: return
        val paymentId = o.paymentId ?: run { message = "The charging service didn't give a payment ID"; return }
        if (sessionBusy) return
        val poi = poiFor(station, charger)
        sessionBusy = true
        launchSafely(onDone = { sessionBusy = false }) {
            val (code, err) = if (dryRunning) { delay(1_200); 0 to null } else api.iacStartCharge(v.id, paymentId, poi, cardId)
            if (code == 0 || code == null && err == null) {
                cardId?.let { store.payCardId = it }
                val s = ActiveSession(paymentId, poi, station.name, charger.reference, System.currentTimeMillis(), o.remoteStop)
                store.activeSession = s
                session = s
                sessionStatus = null
                clearOffer()
                message = "Charging requested at ${station.name}"
                onStarted()
            } else {
                message = "The charger didn't start" + (err?.let { " ($it)" } ?: "")
            }
        }
    }

    /** Fetches live numbers for the running session. */
    fun refreshSession(refresh: Boolean = false) {
        val v = vehicle ?: return
        val s = session ?: return
        if (!online || dryRunning) {
            sessionStatus = demoSessionStatus(s)
            return
        }
        launchSafely(onError = { }) {
            sessionStatus = SessionStatus.from(api.iacSessionStatus(v.id, v.vin, s.paymentId, refresh))
        }
    }

    fun stopCharge() {
        val v = vehicle ?: return
        val s = session ?: return
        if (sessionBusy) return
        sessionBusy = true
        launchSafely(onDone = { sessionBusy = false }) {
            val (code, err) = if (dryRunning) { delay(1_000); 0 to null } else api.iacStopCharge(v.id, s.paymentId, s.poiId)
            if (code == 0 || code == null && err == null) {
                message = "Charging stopped"
                refreshSession(true)
            } else {
                message = "The charger didn't stop" + (err?.let { " ($it)" } ?: "") + ". Unplug or use the charger's screen."
            }
        }
    }

    /** Developer mode's dry run, which also covers paid charging. */
    private val dryRunning get() = developerMode && debug.dryRun

    private fun demoOffer(charger: Charger) = ChargeOffer(
        paymentId = "demo-payment", productId = "demo", productName = "Pay as you go",
        price = null, description = "Demo prices; nothing is charged in a dry run.",
        priceLines = if ((charger.maxKw ?: 0.0) >= 25) listOf("\$0.45 per kWh", "\$0.40 per minute idle after 10 minutes") else listOf("\$2.00 per hour"),
        remoteStart = true, remoteStop = true,
        cards = listOf(io.github.alamo3.openblue.api.PayCardRef("demo-card", io.github.alamo3.openblue.api.PayCard("Visa", "Visa", "************4242"))),
        resultCode = 0, message = null,
    )

    private fun demoSessionStatus(s: ActiveSession): SessionStatus {
        val minutes = ((System.currentTimeMillis() - s.startedAt) / 60_000.0).coerceAtLeast(1.0)
        val kwh = minutes * 2.3
        return SessionStatus(kwh = kwh, powerKw = 138.0, cost = kwh * 0.45, currency = "CAD", battery = (42 + minutes).toInt().coerceAtMost(80), charging = true, minutesLeft = 24)
    }

    /** Screenshots: a session in progress. */
    fun showDemoSession() {
        val s = ActiveSession("demo-payment", "demo2", "Harbour Hub", "Stall 3", System.currentTimeMillis() - 12 * 60_000, true)
        session = s
        sessionStatus = demoSessionStatus(s)
    }

    /** Stops showing the session (it's finished, or was started elsewhere). */
    fun dismissSession() {
        store.activeSession = null
        session = null
        sessionStatus = null
    }

    // ------------------------------------------------- trip planning (iac/rp)

    /** A place to drive to, from the phone's geocoder. */
    data class Place(val name: String, val detail: String?, val lat: Double, val lon: Double)

    /** Route option for trip planning: 22 Recommended, 21 Fastest, 23 Economical (no tolls). Remembered. */
    var routeStyle by mutableStateOf(store.routeStyle)
        private set

    fun changeRouteStyle(code: Int) {
        store.routeStyle = code
        routeStyle = code
    }

    var placeResults by mutableStateOf<List<Place>>(emptyList())
        private set
    var placeSearching by mutableStateOf(false)
        private set
    var tripDestination by mutableStateOf<Place?>(null)
    var trip by mutableStateOf<io.github.alamo3.openblue.api.TripPlan?>(null)
        private set
    var tripLoading by mutableStateOf(false)
        private set
    var tripError by mutableStateOf<String?>(null)
        private set

    /** Looks up places by name with Android's geocoder, biased toward the car. */
    fun searchPlaces(query: String) {
        if (query.isBlank()) return
        val context = getApplication<Application>()
        placeSearching = true
        viewModelScope.launch {
            placeResults = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val g = android.location.Geocoder(context, java.util.Locale.getDefault())
                    val near = location
                    val found = if (near != null) {
                        // About 300 km around the car first, then anywhere.
                        g.getFromLocationName(query, 6, near.lat - 3, near.lon - 4, near.lat + 3, near.lon + 4)?.takeIf { it.isNotEmpty() }
                            ?: g.getFromLocationName(query, 6)
                    } else {
                        g.getFromLocationName(query, 6)
                    }
                    found.orEmpty().map { a ->
                        val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").ifBlank { null }
                        Place(
                            name = a.featureName?.takeIf { it != a.subThoroughfare } ?: street ?: a.locality ?: query,
                            detail = listOfNotNull(street, a.locality, a.adminArea).distinct().joinToString(", ").ifBlank { null },
                            lat = a.latitude,
                            lon = a.longitude,
                        )
                    }
                }.getOrDefault(emptyList())
            }
            placeSearching = false
            if (placeResults.isEmpty()) message = "No places found for \"$query\""
        }
    }

    fun clearPlaces() {
        placeResults = emptyList()
    }

    /** Plans a trip from the car to [tripDestination] with Hyundai's EV route planner. */
    fun planTrip(chargerSoc: Int, destinationSoc: Int, options: List<Int> = listOf(22)) {
        val v = vehicle ?: return
        val dest = tripDestination ?: return
        val from = location ?: run {
            tripError = "Locate the car first"
            locate()
            return
        }
        tripError = null
        trip = null
        if (!online || dryRunning) {
            demoOptions = options.ifEmpty { listOf(22) }
            showDemoTrip()
            return
        }
        tripLoading = true
        launchSafely(onDone = { tripLoading = false }, onError = { tripError = describe(it) }) {
            val plan = io.github.alamo3.openblue.api.TripPlan.from(
                api.iacRoute(v.id, v.vin, from.lat to from.lon, dest.lat to dest.lon, chargerSoc, destinationSoc, chargeService?.vendorNum ?: 0, options.ifEmpty { listOf(22) }),
            )
            trip = plan
            tripError = when {
                plan.status == 1 -> "The route planner rejected the request (status 1)"
                plan.status == 2 -> "The route planner had an error (status 2)"
                plan.status == 3 -> "No EV route found to there"
                plan.routes.isEmpty() -> "The route planner answered but sent no route"
                else -> null
            }
        }
    }

    /** One route option code tried on its own (developer scan). */
    data class OptionResult(val code: Int, val status: Int?, val route: io.github.alamo3.openblue.api.TripRoute?, val error: String?)

    var scanResults by mutableStateOf<List<OptionResult>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set

    /**
     * Developer: asks the planner for each code in [codes] separately (one bad code can fail a
     * whole request), then shows every route that came back so they can be compared.
     */
    fun scanRouteOptions(chargerSoc: Int, destinationSoc: Int, codes: List<Int>) {
        val v = vehicle ?: return
        val dest = tripDestination ?: return
        val from = location ?: run { tripError = "Locate the car first"; locate(); return }
        if (scanning || codes.isEmpty()) return
        tripError = null
        trip = null
        scanResults = emptyList()
        scanning = true
        viewModelScope.launch {
            val results = mutableListOf<OptionResult>()
            for (code in codes) {
                val r = try {
                    if (!online || dryRunning) {
                        delay(150)
                        demoOptions = listOf(code)
                        showDemoTrip()
                        OptionResult(code, 0, trip?.routes?.firstOrNull(), null)
                    } else {
                        val plan = io.github.alamo3.openblue.api.TripPlan.from(
                            api.iacRoute(v.id, v.vin, from.lat to from.lon, dest.lat to dest.lon, chargerSoc, destinationSoc, chargeService?.vendorNum ?: 0, listOf(code)),
                        )
                        OptionResult(code, plan.status, plan.routes.firstOrNull()?.copy(option = code), null)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    OptionResult(code, null, null, describe(e))
                }
                results += r
                scanResults = results.toList()
            }
            trip = io.github.alamo3.openblue.api.TripPlan(0, results.mapNotNull { it.route }, null)
            if (trip?.routes.isNullOrEmpty()) tripError = "None of the codes returned a route"
            scanning = false
        }
    }

    /** Screenshots and dry run: a made-up trip with two charging stops. */
    fun showDemoTrip() {
        val (lat, lon) = location?.let { it.lat to it.lon } ?: (43.6534 to -79.3841)
        if (tripDestination == null) tripDestination = Place("Demo destination", "About 450 km away", lat + 0.8, lon - 4.5)
        val dest = tripDestination!!
        val pts = (0..40).map { i -> val f = i / 40.0; (lat + (dest.lat - lat) * f + 0.15 * kotlin.math.sin(f * 3)) to (lon + (dest.lon - lon) * f) }
        fun stop(i: Int, name: String, net: String, a: Int, b: Int, km: Int) = io.github.alamo3.openblue.api.TripStop(
            name, net, "demo$i", pts[i].first, pts[i].second, a, b, 22 * 60L, km * 40, km * 1000, 350,
        )
        trip = io.github.alamo3.openblue.api.TripPlan(
            status = 0,
            reachedDestination = true,
            routes = demoOptions.mapIndexed { n, opt ->
                io.github.alamo3.openblue.api.TripRoute(
                    option = opt,
                    distanceM = 452_000 + n * 23_000, seconds = 5 * 3600 + 20 * 60 + n * 900, fee = 31 - n * 2,
                    stops = listOf(stop(14, "Highway Services", "Electrify Canada", 18, 75, 160), stop(29, "Lakeside Plaza", "Petro-Canada", 21, 70, 325)),
                    line = listOf(0 to pts.take(15), 1 to pts.subList(14, 21), 0 to pts.drop(20)),
                    arriveSoc = 34, unreachable = 0,
                )
            },
        )
    }

    /** Route option codes the demo trip answers (dry run uses what the screen asked for). */
    private var demoOptions = listOf(22)

    var chargeHistory by mutableStateOf<ChargeHistory?>(null)
        private set
    var chargeHistoryMonth by mutableStateOf(java.time.YearMonth.now())
        private set
    var chargeHistoryLoading by mutableStateOf(false)
        private set
    var chargeHistoryError by mutableStateOf<String?>(null)
        private set

    fun loadChargeHistory(month: java.time.YearMonth = chargeHistoryMonth) {
        val v = vehicle ?: return
        chargeHistoryMonth = month
        if (!online) return
        val profile = store.username ?: return
        chargeHistoryLoading = true
        chargeHistoryError = null
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
        launchSafely(onDone = { chargeHistoryLoading = false }, onError = { chargeHistoryError = describe(it) }) {
            val r = api.iacOrders(v.id, profile, month.atDay(1).format(f), month.atEndOfMonth().format(f))
            if (chargeHistoryMonth == month) chargeHistory = ChargeHistory.from(r)
        }
    }

    // ------------------------------------------------------- developer: endpoint probe

    var probeResults by mutableStateOf<List<BluelinkClient.ProbeResult>>(emptyList())
        private set
    var probing by mutableStateOf(false)
        private set

    /**
     * Posts a send-to-car style body to each path in turn and records what comes back.
     * The destination is the car's own location (or the CN Tower), so a hit is harmless.
     */
    fun probeEndpoints(paths: List<String>, authorise: Boolean) {
        val v = vehicle ?: return
        if (!online || probing || paths.isEmpty()) return
        val (lat, lon) = location?.let { it.lat to it.lon } ?: (43.6426 to -79.3871)
        val body = kotlinx.serialization.json.buildJsonObject {
            put("vin", kotlinx.serialization.json.JsonPrimitive(v.vin))
            put("userName", kotlinx.serialization.json.JsonPrimitive(store.username.orEmpty()))
            store.pin?.let { put("pin", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("actionType", kotlinx.serialization.json.JsonPrimitive("SEND"))
            put("poiInfoList", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive("Open Blue test"))
                    put("addr", kotlinx.serialization.json.JsonPrimitive(""))
                    put("src", kotlinx.serialization.json.JsonPrimitive("HERE"))
                    put("waypointID", kotlinx.serialization.json.JsonPrimitive(0))
                    put("lang", kotlinx.serialization.json.JsonPrimitive(1))
                    put("coord", kotlinx.serialization.json.buildJsonObject {
                        put("lat", kotlinx.serialization.json.JsonPrimitive(lat))
                        put("lon", kotlinx.serialization.json.JsonPrimitive(lon))
                        put("alt", kotlinx.serialization.json.JsonPrimitive(0))
                        put("type", kotlinx.serialization.json.JsonPrimitive(0))
                    })
                })
            })
        }
        probing = true
        probeResults = emptyList()
        viewModelScope.launch {
            val out = mutableListOf<BluelinkClient.ProbeResult>()
            for (p in paths) {
                out += try {
                    api.probe(p, v.id, body, authorise)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BluelinkClient.ProbeResult(p, -1, null, null, describe(e), "")
                }
                probeResults = out.toList()
                delay(400)
            }
            probing = false
        }
    }

    /** The car's last known location; [locate] asks the car for a fresh one. */
    var location by mutableStateOf(store.lastLocation)
        private set

    fun locate() {
        val v = vehicle ?: return
        if (!canAct || !online) return
        val action = CommandProgress.Action.LOCATE
        if (developerMode && debug.dryRun) {
            dryRun(action) { it }
            if (location == null) location = io.github.alamo3.openblue.api.CarLocation(43.6534, -79.3841, 90.0, fetchedAt = System.currentTimeMillis())
            return
        }
        showProgress(CommandProgress(action, CommandProgress.Stage.WAITING, "This can take up to a minute."))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val loc = api.locate(v.id, isEv = v.fuelKindCode.equals("E", ignoreCase = true))
            store.lastLocation = loc
            location = loc
            finish(action, true)
        }
    }

    fun hornLights(horn: Boolean) = command(if (horn) CommandProgress.Action.HORN else CommandProgress.Action.LIGHTS) {
        api.hornLights(it.id, horn)
    }

    fun chargePort(open: Boolean) = command(
        if (open) CommandProgress.Action.PORT_OPEN else CommandProgress.Action.PORT_CLOSE,
        { it.copy(chargePortOpen = open) },
    ) { api.chargePort(it.id, open) }

    fun batteryPreconditioning(on: Boolean) = command(
        if (on) CommandProgress.Action.PRECONDITION_ON else CommandProgress.Action.PRECONDITION_OFF,
        { it.copy(preconditioning = on) },
    ) { api.batteryPreconditioning(it.id, on) }

    /** Saves AC and DC charge limits (percent, steps of 10). */
    fun setChargeLimits(ac: Int, dc: Int) {
        val v = vehicle ?: return
        if (!canAct) return
        val action = CommandProgress.Action.CHARGE_LIMITS
        if (developerMode && debug.dryRun) {
            dryRun(action) { it.copy(limitAc = ac, limitDc = dc) }
            return
        }
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val tx = api.setChargeLimits(v.id, ac, dc)
            if (tx != null) {
                showProgress(CommandProgress(action, CommandProgress.Stage.WAITING))
                when (awaitCommand(v, tx, action)) {
                    CommandResult.SUCCESS -> finish(action, true)
                    CommandResult.FAILED -> finish(action, false, "The car didn't accept the new limits.")
                    CommandResult.TIMEOUT -> finish(action, false, "No answer from the car yet. It may still go through, so check again in a minute.")
                }
            } else {
                finish(action, true, "The car picks them up next time it connects.")
                try {
                    applyStatus(api.cachedStatus(v.id))
                } catch (e: ApiException) {
                    // The limits are saved; the status will catch up on the next refresh.
                }
            }
        }
    }

    // ------------------------------------------------------------ developer mode

    /** Plays a command's progress locally and applies its effect to the simulated car. */
    private fun dryRun(action: CommandProgress.Action, effect: (DebugState) -> DebugState) {
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING, "Dry run: the car isn't contacted."))
        viewModelScope.launch {
            delay(900)
            showProgress(CommandProgress(action, CommandProgress.Stage.WAITING, "Dry run: the car isn't contacted."))
            delay(1_800)
            debug = effect(debug)
            finish(action, true, "Dry run")
        }
    }

    fun changeDeveloperMode(on: Boolean) {
        store.developerMode = on
        developerMode = on
        if (!on) debug = DebugState()
    }

    fun updateDebug(block: (DebugState) -> DebugState) {
        debug = block(debug)
    }

    /** Shows the progress strip in a given state, to check how it looks. */
    fun previewProgress(stage: CommandProgress.Stage) {
        val action = CommandProgress.Action.CLIMATE_START
        when (stage) {
            CommandProgress.Stage.DONE -> finish(action, true)
            CommandProgress.Stage.FAILED -> finish(action, false, "Preview of a failure message.")
            else -> showProgress(CommandProgress(action, stage))
        }
    }

    fun clearProgressPreview() {
        clearProgressJob?.cancel()
        progress = null
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
        if (svmLoaded && !force) return
        // Archived photos show straight away, even offline.
        if (svm.isEmpty() && archive != null) {
            viewModelScope.launch { svm = withContext(Dispatchers.IO) { archive.list(v.id) } }
        }
        if (!online) {
            svmLoaded = true
            return
        }
        if (force) refreshing = true
        launchSafely(onDone = { svmLoaded = true; refreshing = false }) {
            keepSurroundView(v.id, api.lastSurroundView(v.id))
        }
    }

    /** Archives [latest] and shows everything we have. */
    private suspend fun keepSurroundView(vehicleId: String, latest: List<SvmCapture>) {
        svm = if (archive != null) {
            withContext(Dispatchers.IO) {
                archive.add(vehicleId, latest)
                archive.list(vehicleId)
            }
        } else {
            latest.map { SvmShot.of(it) }
        }
    }

    /** Asks the car for new photos, then polls until a capture newer than the last one shows up. */
    fun captureSurroundView() {
        val v = vehicle ?: return
        if (!canAct) return
        if (developerMode && debug.dryRun) {
            dryRun(CommandProgress.Action.CAMERA) { it }
            return
        }
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
                    keepSurroundView(v.id, latest)
                    svmLoaded = true
                    finish(action, true)
                    return@launchSafely
                }
            }
            finish(action, false, "The car hasn't sent photos yet. Pull down on this screen in a minute to check again.")
        }
    }

    /** Removes a set of photos from this phone's archive (Bluelink keeps its own latest copy). */
    fun deleteSurroundView(shot: SvmShot) {
        val v = vehicle ?: return
        val time = shot.utcTime ?: return
        viewModelScope.launch {
            svm = withContext(Dispatchers.IO) {
                archive?.remove(v.id, time)
                archive?.list(v.id) ?: svm.filter { it !== shot }
            }
        }
    }

    /** Saves the photos (all views, or just [only]) to the phone's gallery. */
    fun exportSurroundView(shot: SvmShot, only: String? = null) {
        if (!Gallery.supported) {
            message = "Saving to the gallery needs Android 10 or newer"
            return
        }
        val context = getApplication<Application>()
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val views = shot.views().filter { only == null || it.first == only }
                    Gallery.save(context, shot.takenAt, views)
                }.getOrDefault(0)
            }
            message = when (saved) {
                0 -> "Couldn't save to the gallery"
                1 -> "Saved to Pictures/Open Blue"
                else -> "Saved $saved photos to Pictures/Open Blue"
            }
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

    // ----------------------------------------------------------- engine sound

    private val soundCtl: SoundController? get() = EngineSoundService.current
    var engineSound by mutableStateOf(store.engineSound?.let { n -> EngineSound.entries.firstOrNull { it.name == n } } ?: EngineSound.STARSHIP)
        private set
    var soundVolume by mutableStateOf(store.soundVolume)
        private set
    var holdRevs by mutableStateOf(store.holdRevs)
        private set
    var gearshifts by mutableStateOf(store.gearshifts)
        private set
    var soundLookaheadMs by mutableStateOf(store.soundLookaheadMs)
        private set
    var soundOn by mutableStateOf(online && EngineSoundService.current != null)
        private set
    /** Live gauge values while the sound runs. */
    var soundReadout by mutableStateOf<SoundReadout?>(null)
        private set
    /** Result of the last delay measurement, for the screen. */
    var soundDelayNote by mutableStateOf<String?>(null)
        private set
    var measuringDelay by mutableStateOf(false)
        private set
    private var soundPoll: Job? = null

    init {
        if (soundOn) pollSound()
    }

    /** Starts the engine sound (as a foreground service, so it keeps going under Android Auto). */
    fun startSound() {
        if (soundOn || !online) return
        EngineSoundService.start(getApplication())
        soundOn = true
        pollSound()
    }

    private fun pollSound() {
        soundPoll?.cancel()
        soundPoll = viewModelScope.launch {
            var missing = 0
            while (true) {
                val c = soundCtl
                soundReadout = c?.readout
                // Stopped from the notification: reflect it here.
                missing = if (c == null) missing + 1 else 0
                if (missing > 40) {
                    soundOn = false
                    soundReadout = null
                    break
                }
                delay(50)
            }
        }
    }

    fun stopSound() {
        soundPoll?.cancel()
        soundPoll = null
        if (online && soundOn) EngineSoundService.stop(getApplication())
        soundOn = false
        soundReadout = null
    }

    /** Revving in place: 0..1 while the slider is held, null when let go. */
    fun revEngine(throttle: Float?) {
        soundCtl?.revThrottle = throttle
    }

    fun changeEngineSound(s: EngineSound) {
        engineSound = s
        store.engineSound = s.name
        soundCtl?.sound = s
    }

    fun changeSoundVolume(v: Float) {
        soundVolume = v
        store.soundVolume = v
        soundCtl?.volume = v
    }

    fun changeHoldRevs(on: Boolean) {
        holdRevs = on
        store.holdRevs = on
        soundCtl?.holdRevs = on
    }

    fun changeGearshifts(on: Boolean) {
        gearshifts = on
        store.gearshifts = on
        soundCtl?.gearshifts = on
    }

    var soundPlacement by mutableStateOf(store.soundPlacement)
        private set

    fun changeSoundPlacement(p: Int) {
        soundPlacement = p
        store.soundPlacement = p
        soundCtl?.padSign = p.takeIf { it != 0 }
    }

    var autoSound by mutableStateOf(store.autoSound)
        private set
    var soundCarName by mutableStateOf(store.soundCarName)
        private set

    fun changeAutoSound(on: Boolean) {
        autoSound = on
        store.autoSound = on
    }

    /** The car whose Bluetooth starts and stops the sound. */
    fun setSoundCar(address: String, name: String) {
        store.soundCarAddress = address
        store.soundCarName = name
        soundCarName = name
        changeAutoSound(true)
    }

    fun forgetSoundCar() {
        store.soundCarAddress = null
        store.soundCarName = null
        soundCarName = null
        changeAutoSound(false)
    }

    fun soundMessage(text: String) {
        message = text
    }

    fun changeSoundLookahead(ms: Int) {
        soundLookaheadMs = ms
        store.soundLookaheadMs = ms
        soundCtl?.lookaheadMs = ms.toFloat()
    }

    /** Plays clicks through the current speakers and times them with the microphone. */
    fun measureSoundDelay() {
        val c = soundCtl ?: return
        if (measuringDelay) return
        measuringDelay = true
        soundDelayNote = null
        viewModelScope.launch {
            val ms = withContext(Dispatchers.IO) { runCatching { c.measureDelayMs() }.getOrNull() }
            measuringDelay = false
            soundDelayNote = if (ms == null) {
                "Couldn't hear the clicks. Turn the volume up and try again, with the phone near a speaker."
            } else {
                // Predicting much past ~250 ms does more harm than good.
                val use = (ms - 15).coerceIn(0, 250)
                changeSoundLookahead((use / 10) * 10)
                "Measured ${ms} ms from the phone to your ears. Look-ahead set to ${soundLookaheadMs} ms."
            }
        }
    }

    /** Fills the sound screen with a mid-pull reading, for screenshots. */
    fun showDemoSound() {
        soundOn = true
        soundReadout = SoundReadout(4650f, -1, 92f, 2.6f, 0.68f, 1f, gpsFix = true, hasSensors = true)
        soundDelayNote = "Measured 182 ms from the phone to your ears. Look-ahead set to 160 ms."
        setSoundCar("00:11:22:33:44:55", "IONIQ 5")
        soundLookaheadMs = 160
    }

    override fun onCleared() {
        // The sound keeps running in its service; only stop watching it.
        soundPoll?.cancel()
        super.onCleared()
    }

    // ---------------------------------------------------------- notifications

    var notifyCommands by mutableStateOf(store.notifyCommands)
        private set
    var watchEnabled by mutableStateOf(store.watchEnabled)
        private set
    var watchMinutes by mutableStateOf(store.watchMinutes)
        private set
    var alertsOn by mutableStateOf(AlertKind.entries.associateWith { store.alertOn(it.key) })
        private set

    /** True the first time it's asked (per sign-in), so the app requests notification permission once. */
    fun takeNotificationPrompt(): Boolean {
        if (!online || store.askedNotifications) return false
        store.askedNotifications = true
        return true
    }

    fun changeNotifyCommands(on: Boolean) {
        store.notifyCommands = on
        notifyCommands = on
    }

    fun changeWatch(on: Boolean) {
        store.watchEnabled = on
        watchEnabled = on
        // Start fresh, so a state that was already there doesn't count as a change.
        if (on) store.watchMemory = io.github.alamo3.openblue.work.WatchMemory()
        if (online) StatusWatchWorker.sync(getApplication(), store, replace = true)
    }

    fun changeWatchMinutes(minutes: Int) {
        store.watchMinutes = minutes
        watchMinutes = minutes
        if (online) StatusWatchWorker.sync(getApplication(), store, replace = true)
    }

    fun changeAlert(kind: AlertKind, on: Boolean) {
        store.setAlertOn(kind.key, on)
        alertsOn = alertsOn + (kind to on)
    }

    fun loadDiagnostics() {
        val v = vehicle ?: return
        task("Fetching vehicle data") {
            val raw = api.selectVehicle(v.id)
            diagnostics = prettyJson.encodeToString(JsonObject.serializer(), raw)
        }
    }
}
