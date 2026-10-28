package io.github.alamo3.openblue.api

import io.github.alamo3.openblue.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ApiException(val code: String?, message: String) : Exception(message)

/** Thrown when Bluelink wants an emailed/SMS code before it will issue a token. */
class OtpRequiredException(val challenge: OtpChallenge) : Exception("Bluelink needs a verification code")

data class OtpChallenge(
    val userInfoUuid: String,
    /** The login email, sent back as userAccount. */
    val account: String,
    val email: String,
    /** Phone exactly as mfa/selverifmeth returned it (possibly masked); echoed back for SMS. */
    val phone: String?,
    val otpKey: String? = null,
)

sealed interface LoginResult {
    data object Success : LoginResult
    data class NeedsOtp(val challenge: OtpChallenge) : LoginResult
}

enum class CommandResult { SUCCESS, FAILED, TIMEOUT }

/**
 * Client for the Hyundai Canada Bluelink API (https://mybluelink.ca/tods/api/).
 * Endpoints, headers and payloads mirror the official Android app 2.0.47, with
 * the login/MFA flow cross-checked against hyundai_kia_connect_api.
 */
class BluelinkClient(private val store: SecureStore) {

    companion object {
        private const val BASE_URL = "https://mybluelink.ca/tods/api/"
        private const val CLIENT_ID = "HATAHSPACA0232141ED9722C67715A0B"
        private const val CLIENT_SECRET = "CLISCR01AHSPA"
        private const val MFA_API_CODE = "0107"
        private const val OTP_REQUIRED = "7110"

        /** 7403 expired, 7602 deleted, 7606 token bound to a different IP (phone switched networks). */
        private val TOKEN_ERRORS = setOf("7403", "7602", "7606")
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private class Reply(val body: JsonObject, val headers: Headers)

    private fun offsetHours(): String =
        (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000).toString()

    private suspend fun post(
        path: String,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
        withDeviceId: Boolean = false,
    ): Reply = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(BASE_URL + path)
            .header("Content-Type", "application/json")
            .header("language", "0")
            .header("offset", offsetHours())
            .header("from", "SPA")
            .header("brand", "H")
            .header("client_id", CLIENT_ID)
            .header("client_secret", CLIENT_SECRET)
        if (withDeviceId) builder.header("deviceId", store.deviceId)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val payload = body?.toString()?.toRequestBody(JSON_MEDIA) ?: ByteArray(0).toRequestBody(null)
        builder.post(payload)

        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw ApiException(null, "Unexpected response from Bluelink (HTTP ${resp.code})")
            }
            Reply(parsed, resp.headers)
        }
    }

    private fun check(r: Reply): JsonObject {
        if (r.body.int("responseHeader.responseCode") == 0) return r.body
        val code = r.body.str("error.errorCode")
        val desc = r.body.str("error.errorDesc")
            ?: r.body.str("responseHeader.responseDesc")
            ?: "Request failed"
        throw ApiException(code, if (code != null) "$desc ($code)" else desc)
    }

    private fun saveToken(body: JsonObject) {
        val token = body.at("result.token") ?: throw ApiException(null, "Login response had no token")
        store.accessToken = token.str("accessToken") ?: throw ApiException(null, "Login response had no access token")
        val expiresIn = token.int("expireIn") ?: 1800
        store.tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000L
    }

    // ---------------------------------------------------------------- login

    suspend fun login(username: String, password: String): LoginResult {
        val r = post(
            "v2/login",
            buildJsonObject { put("loginId", username); put("password", password) },
            withDeviceId = true,
        )
        if (r.body.int("responseHeader.responseCode") == 1 && r.body.str("error.errorCode") == OTP_REQUIRED) {
            val sel = check(
                post(
                    "mfa/selverifmeth",
                    buildJsonObject { put("mfaApiCode", MFA_API_CODE); put("userAccount", username) },
                    withDeviceId = true,
                )
            )
            val result = sel.at("result")
            val uuid = result.str("userInfoUuid") ?: throw ApiException(null, "Bluelink didn't return a verification ID")
            val email = (result.arr("emailList").firstOrNull() as? JsonPrimitive)?.contentOrNull ?: username
            val phone = result.str("userPhone")?.takeIf { it.isNotBlank() }
            return LoginResult.NeedsOtp(OtpChallenge(userInfoUuid = uuid, account = username, email = email, phone = phone))
        }
        saveToken(check(r))
        return LoginResult.Success
    }

    /**
     * Asks Bluelink to send the code; returns the challenge with its otpKey filled in.
     * Matches the official app: "M" (mobile) + userPhone for SMS, "E" without a phone for email.
     */
    suspend fun sendOtp(challenge: OtpChallenge, bySms: Boolean): OtpChallenge {
        val body = check(
            post(
                "mfa/sendotp",
                buildJsonObject {
                    put("otpMethod", if (bySms) "M" else "E")
                    put("mfaApiCode", MFA_API_CODE)
                    put("userAccount", challenge.account)
                    if (bySms) put("userPhone", challenge.phone.orEmpty())
                    put("userInfoUuid", challenge.userInfoUuid)
                },
                withDeviceId = true,
            )
        )
        val key = body.str("result.otpKey") ?: throw ApiException(null, "Bluelink didn't return a code reference")
        return challenge.copy(otpKey = key)
    }

    suspend fun verifyOtp(username: String, code: String, challenge: OtpChallenge) {
        val validated = check(
            post(
                "mfa/validateotp",
                buildJsonObject {
                    put("otpNo", code.trim())
                    put("userAccount", username)
                    put("otpKey", challenge.otpKey.orEmpty())
                    put("mfaApiCode", MFA_API_CODE)
                },
                withDeviceId = true,
            )
        )
        if (validated.bool("result.verifiedOtp") != true) throw ApiException(null, "That code wasn't accepted")
        val validationKey = validated.str("result.otpValidationKey")
            ?: throw ApiException(null, "Bluelink didn't return a validation key")
        val tokenBody = check(
            post(
                "mfa/genmfatkn",
                buildJsonObject {
                    put("userAccount", username)
                    put("otpEmail", challenge.email)
                    put("mfaApiCode", MFA_API_CODE)
                    put("otpValidationKey", validationKey)
                    put("mfaYn", "Y")
                },
                withDeviceId = true,
            )
        )
        saveToken(tokenBody)
    }

    /** Canada has no refresh endpoint: re-login with stored credentials (same device ID, so no code). */
    private suspend fun relogin(): String {
        val u = store.username
        val p = store.password
        if (u == null || p == null) throw ApiException("auth", "Not signed in")
        when (val r = login(u, p)) {
            is LoginResult.NeedsOtp -> throw OtpRequiredException(r.challenge)
            LoginResult.Success -> Unit
        }
        return store.accessToken ?: throw ApiException("auth", "Login failed")
    }

    private suspend fun <T> authed(block: suspend (token: String) -> T): T {
        val current = store.accessToken
        val token = if (current == null || System.currentTimeMillis() > store.tokenExpiresAt) relogin() else current
        return try {
            block(token)
        } catch (e: ApiException) {
            if (TOKEN_ERRORS.contains(e.code ?: "")) block(relogin()) else throw e
        }
    }

    // -------------------------------------------------------------- vehicles

    suspend fun vehicles(): List<VehicleInfo> = authed { t ->
        val body = check(post("vhcllst", headers = mapOf("accessToken" to t)))
        body.arr("result.vehicles").mapNotNull { v ->
            val id = v.str("vehicleId") ?: return@mapNotNull null
            VehicleInfo(
                id = id,
                nickname = v.str("nickName") ?: v.str("modelName") ?: "Vehicle",
                model = v.str("modelName").orEmpty(),
                year = v.str("modelYear").orEmpty(),
                vin = v.str("vin").orEmpty(),
                fuelKindCode = v.str("fuelKindCode").orEmpty(),
            )
        }
    }

    fun capsFrom(selected: JsonObject) = VehicleCaps(
        genType = selected.str("vehicle.genType"),
        hvacTempType = selected.int("features.hvacTempType"),
        strgWhlHeatingOption = selected.int("features.strgWhlHeatingOption"),
        seatOptions = listOf("drv", "ast", "rl", "rr").map { selected.int("features.seatHeatVent.${it}SeatHeatOption") },
        photoUrl = selected.str("vehicle.photoUrl"),
        trim = selected.str("vehicle.trim")?.trim()?.trimEnd('-')?.trim()?.takeIf { it.isNotEmpty() },
        odometer = selected.dbl("vehicle.odometer"),
        odometerUnit = selected.int("vehicle.odometerUnit"),
        hasSvm = selected.arr("featuresModel.features").any { cat ->
            cat.arr("features").any { it.str("featureName") == "ParkingSvm" }
        },
    )

    // ------------------------------------------------------------ surround view

    /**
     * Asks the car to take surround-view photos (rfc/fndmcrsvm). The official app verifies the
     * PIN first and then sends the request with just the access token and vehicle ID.
     */
    suspend fun requestSurroundView(vehicleId: String): String? {
        val pin = store.pin ?: throw ApiException(null, "No Bluelink PIN saved")
        return authed { t ->
            pAuth(t, vehicleId, pin)
            val r = post("rfc/fndmcrsvm", headers = mapOf("accessToken" to t, "vehicleId" to vehicleId))
            check(r)
            r.headers["transactionId"]
        }
    }

    /** The most recent surround-view captures stored by Bluelink (rfc/lastmcrsvm), newest first. */
    suspend fun lastSurroundView(vehicleId: String): List<SvmCapture> = authed { t ->
        val body = check(post("rfc/lastmcrsvm", headers = mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        body.arr("result.svmLocations").mapNotNull { loc ->
            val b64 = loc.str("svmImage") ?: return@mapNotNull null
            val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
                ?: return@mapNotNull null
            SvmCapture(
                utcTime = loc.str("utcTime"),
                jpeg = bytes,
                imageSize = loc.arr("imageSize").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() },
            )
        }.sortedByDescending { it.utcTime.orEmpty() }
    }

    /** sltvhcl: vehicle + status + feature flags (hvacTempType etc). Returns the "result" object. */
    suspend fun selectVehicle(vehicleId: String): JsonObject = authed { t ->
        val body = check(post("sltvhcl", headers = mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        (body.at("result") as? JsonObject) ?: JsonObject(emptyMap())
    }

    /** Cached status from Bluelink's servers. Does not wake the car. */
    suspend fun cachedStatus(vehicleId: String): JsonObject = status("lstvhclsts", vehicleId)

    /** Asks the car itself for fresh status. Wakes the car's modem; don't spam it (12V drain). */
    suspend fun liveStatus(vehicleId: String): JsonObject = status("rltmvhclsts", vehicleId)

    private suspend fun status(path: String, vehicleId: String): JsonObject = authed { t ->
        val body = check(post(path, headers = mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        (body.at("result.status") as? JsonObject) ?: throw ApiException(null, "No status in response")
    }

    suspend fun serverPresets(vehicleId: String, table: List<TempTables.Entry>): List<ServerPreset> = authed { t ->
        val body = check(post("gtfvsttng", headers = mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        body.arr("result").map { p ->
            ServerPreset(
                id = p.int("id"),
                isDefault = p.bool("defaultFavorite") == true,
                settings = ClimateSettings(
                    name = p.str("settingName") ?: "Preset",
                    climateOn = (p.int("airCtrl") ?: 1) != 0,
                    tempC = TempTables.celsiusForHex(table, p.str("airTemp.value")) ?: 21.0,
                    defrost = p.bool("defrost") == true,
                    heating = p.int("heating1") ?: 0,
                    durationMin = p.int("igniOnDuration") ?: 10,
                    driverSeat = p.int("seatHeaterVentCMD.drvSeatOptCmd") ?: 0,
                    passengerSeat = p.int("seatHeaterVentCMD.astSeatOptCmd") ?: 0,
                    rearLeftSeat = p.int("seatHeaterVentCMD.rlSeatOptCmd") ?: 0,
                    rearRightSeat = p.int("seatHeaterVentCMD.rrSeatOptCmd") ?: 0,
                ),
            )
        }
    }

    // -------------------------------------------------------------- commands

    private suspend fun pAuth(token: String, vehicleId: String, pin: String): String {
        val body = check(
            post("vrfypin", buildJsonObject { put("pin", pin) }, mapOf("accessToken" to token, "vehicleId" to vehicleId))
        )
        return body.str("result.pAuth") ?: throw ApiException(null, "PIN was not accepted")
    }

    /** Sends a PIN-protected command and returns its transactionId. */
    private suspend fun command(path: String, vehicleId: String, extra: JsonObject = JsonObject(emptyMap())): String {
        val pin = store.pin ?: throw ApiException(null, "No Bluelink PIN saved")
        return authed { t ->
            val auth = pAuth(t, vehicleId, pin)
            val body = JsonObject(extra + ("pin" to JsonPrimitive(pin)))
            val r = post(path, body, mapOf("accessToken" to t, "vehicleId" to vehicleId, "pAuth" to auth))
            check(r)
            r.headers["transactionId"] ?: throw ApiException(null, "Command was accepted but returned no transaction ID")
        }
    }

    suspend fun lock(vehicleId: String) = command("drlck", vehicleId)
    suspend fun unlock(vehicleId: String) = command("drulck", vehicleId)
    suspend fun stopClimate(vehicleId: String) = command("evc/rfoff", vehicleId)

    /** The JSON key for climate settings: Gen3 cars use "remoteControl", older ones "hvacInfo". */
    fun climateKey(): String = when (store.climateFormat) {
        ClimateFormat.HVAC_INFO -> "hvacInfo"
        ClimateFormat.REMOTE_CONTROL -> "remoteControl"
        ClimateFormat.AUTO -> if (store.caps?.isGen3 == true) "remoteControl" else "hvacInfo"
    }

    suspend fun startClimate(vehicle: VehicleInfo, s: ClimateSettings): String {
        val hvacTempType = store.caps?.hvacTempType ?: if (vehicle.fuelKindCode.equals("E", true)) 1 else 0
        val table = TempTables.forVehicle(hvacTempType, vehicle.model, vehicle.fuelKindCode)
        val entry = TempTables.nearest(table, s.tempC)
        val settings = buildJsonObject {
            put("airCtrl", if (s.climateOn) 1 else 0)
            put("defrost", s.defrost)
            put("heating1", s.heating)
            putJsonObject("airTemp") {
                put("value", entry.hex)
                put("unit", 0)
                put("hvacTempType", hvacTempType)
            }
            put("igniOnDuration", s.durationMin)
            putJsonObject("seatHeaterVentCMD") {
                put("drvSeatOptCmd", s.driverSeat)
                put("astSeatOptCmd", s.passengerSeat)
                put("rlSeatOptCmd", s.rearLeftSeat)
                put("rrSeatOptCmd", s.rearRightSeat)
            }
        }
        return command("evc/rfon", vehicle.id, buildJsonObject { put(climateKey(), settings) })
    }

    /** Polls rmtsts until the car confirms (C), fails, or we give up. */
    suspend fun awaitResult(vehicleId: String, transactionId: String, timeoutMs: Long = 150_000): CommandResult {
        val pin = store.pin ?: return CommandResult.FAILED
        val deadline = System.currentTimeMillis() + timeoutMs
        var auth: String? = null
        while (System.currentTimeMillis() < deadline) {
            delay(6_000)
            val reply = authed { t ->
                val a = auth ?: pAuth(t, vehicleId, pin).also { auth = it }
                post(
                    "rmtsts",
                    headers = mapOf("accessToken" to t, "vehicleId" to vehicleId, "transactionId" to transactionId, "pAuth" to a),
                )
            }
            if (reply.body.int("responseHeader.responseCode") == 1) return CommandResult.FAILED
            when (reply.body.str("result.transaction.apiResult")) {
                "C" -> return CommandResult.SUCCESS
                "P", null -> continue
                else -> return CommandResult.FAILED
            }
        }
        return CommandResult.TIMEOUT
    }
}
