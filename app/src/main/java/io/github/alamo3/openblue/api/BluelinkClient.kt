package io.github.alamo3.openblue.api

import io.github.alamo3.openblue.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        method: String = "POST",
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
        if (method == "GET") {
            builder.get()
        } else {
            builder.post(body?.toString()?.toRequestBody(JSON_MEDIA) ?: ByteArray(0).toRequestBody(null))
        }

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
                kindCode = v.str("vehicleKindCode")?.takeIf { it.isNotBlank() } ?: v.str("modelCode")?.takeIf { it.isNotBlank() },
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
        modelCode = selected.str("vehicle.vehicleKindCode") ?: selected.str("vehicle.modelCode"),
    )

    /**
     * Replaces the car photo Bluelink shows as the vehicle's picture (vin/photo).
     * [jpegBase64] is a small JPEG (the official app keeps it under ~100 KB). Returns the new URL.
     */
    suspend fun uploadVehiclePhoto(vehicleId: String, jpegBase64: String): String? = authed { t ->
        val body = check(
            post(
                "vin/photo",
                buildJsonObject { put("photo", jpegBase64); put("extension", "jpeg") },
                mapOf("accessToken" to t, "vehicleId" to vehicleId),
            )
        )
        body.str("result.photoUrl")
    }

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

    /**
     * Where the car is. EVs use evc/fme with PIN authorisation; others use fndmcr. The car
     * reports it over its modem, so this can take a few seconds.
     */
    suspend fun locate(vehicleId: String, isEv: Boolean): CarLocation {
        val pin = store.pin ?: throw ApiException(null, "No Bluelink PIN saved")
        val body = authed { t ->
            val r = if (isEv) {
                post("evc/fme", JsonObject(emptyMap()), mapOf("accessToken" to t, "vehicleId" to vehicleId, "pAuth" to pAuth(t, vehicleId, pin)))
            } else {
                post("fndmcr", JsonObject(emptyMap()), mapOf("accessToken" to t, "vehicleId" to vehicleId))
            }
            check(r)
        }
        fun pick(vararg paths: String) = paths.firstNotNullOfOrNull { body.dbl(it) }
        val lat = pick("result.coord.lat", "result.gpsDetail.coord.lat")
        val lon = pick("result.coord.lon", "result.gpsDetail.coord.lon")
        if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) throw ApiException(null, "The car didn't send its location")
        return CarLocation(
            lat = lat,
            lon = lon,
            heading = pick("result.head", "result.gpsDetail.head"),
            time = body.str("result.time") ?: body.str("result.gpsDetail.time"),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------- Hyundai in-app charging (iac/*)
    // Read-only for now: what the account has, stations nearby and charging history.

    /** The iac endpoints carry their own result codes; accept anything Bluelink didn't reject. */
    private fun iacCheck(r: Reply): JsonObject {
        val code = r.body.int("responseHeader.responseCode")
        if (code == null || code == 0) return r.body
        return check(r)
    }

    private fun vehicleInfoBody(modelCode: String?, modelYear: String?) = buildJsonObject {
        putJsonObject("vehicleInfo") {
            put("modelCode", modelCode.orEmpty())
            put("modelYear", modelYear.orEmpty())
        }
    }

    /** Which charging services the account and car have, plus cards on file and the plan. */
    suspend fun iacServiceInfo(vehicleId: String, modelCode: String?, modelYear: String?): JsonObject = authed { t ->
        val req = vehicleInfoBody(modelCode, modelYear)
        val r = post("iac/serviceInfo", req, mapOf("accessToken" to t, "vehicleId" to vehicleId))
        lastExchange = "POST iac/serviceInfo\n" + pretty.encodeToString(JsonObject.serializer(), req) +
            "\n\nResponse:\n" + pretty.encodeToString(JsonObject.serializer(), r.body)
        val body = iacCheck(r)
        (body["result"] as? JsonObject) ?: JsonObject(emptyMap())
    }

    /** The last charging-service request and its raw answer, for troubleshooting. */
    var lastExchange: String? = null
        private set
    private val pretty = Json { prettyPrint = true }

    suspend fun iacSubscriptions(vehicleId: String, modelCode: String?, modelYear: String?): JsonArray = authed { t ->
        val body = iacCheck(post("iac/subscriptionInfo", vehicleInfoBody(modelCode, modelYear), mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        body.arr("result.icpSubscriptInfo")
    }

    /**
     * Charging stations within [radiusKm] of a point, with live availability. [speeds] filters
     * by charger speed: any of "slow" (Level 2), "fast", "rapid", "ultra", comma separated.
     */
    suspend fun iacStations(vehicleId: String, lat: Double, lon: Double, radiusKm: Int, speeds: String?): JsonArray = authed { t ->
        val req = buildJsonObject {
            put("country", "CA")
            putJsonObject("coordinate") { put("lat", lat); put("lon", lon) }
            put("radius", radiusKm)
            put("radiusUnit", "km")
            if (speeds != null) put("chargingSpeeds", speeds)
            put("language", "en")
            put("size", 100)
            put("page", 0)
        }
        iacCheck(post("iac/csList", req, mapOf("accessToken" to t, "vehicleId" to vehicleId))).arr("result.chargingStations")
    }

    /** One station in full: chargers, connectors and their status, prices. */
    suspend fun iacStation(vehicleId: String, stationId: String): JsonObject? = authed { t ->
        val body = iacCheck(
            post(
                "iac/csDetail",
                headers = mapOf("accessToken" to t, "vehicleId" to vehicleId, "country" to "CA", "ids" to stationId),
                method = "GET",
            )
        )
        body["result"] as? JsonObject
    }

    // Starting and stopping a paid charge, as the official app does. The store is always
    // "CHARGEPOINT" (Hyundai's charging partner) and the charger is identified by its ID in
    // URL-safe base64; Tesla stations are addressed by the charger's own station ID.

    private fun genType() = store.caps?.genType?.filter { it.isDigit() }?.ifEmpty { null } ?: "3"

    /** Price, payment ID and cards for charging at [chargerId] of station [poiId]. */
    suspend fun iacOffer(vehicleId: String, poiId: String, chargerId: String): JsonObject = authed { t ->
        val product = android.util.Base64.encodeToString(chargerId.toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        val req = buildJsonObject {
            putJsonArray("storeInfo") {
                add(buildJsonObject {
                    put("storeId", "CHARGEPOINT")
                    put("poiId", poiId)
                    put("productId", product)
                    put("storeType", "E001")
                })
            }
            put("genType", genType())
        }
        val body = iacCheck(post("iac/productInfo", req, mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        (body["result"] as? JsonObject) ?: JsonObject(emptyMap())
    }

    /** Starts a paid charge. Returns (resultCode, error case) from the server. */
    suspend fun iacStartCharge(vehicleId: String, paymentId: String, poiId: String, payCardId: String?): Pair<Int?, String?> = authed { t ->
        val req = buildJsonObject {
            put("paymentId", paymentId)
            put("storeId", "CHARGEPOINT")
            put("poiId", poiId)
            putJsonObject("orderInfo") { put("payCardId", payCardId.orEmpty()) }
            put("tncAgree", true)
            put("genType", genType())
        }
        val body = iacCheck(post("iac/startCharge", req, mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        body.int("result.resultCode") to (body.str("result.errorCaseId") ?: body.str("result.resultMsg"))
    }

    suspend fun iacStopCharge(vehicleId: String, paymentId: String, poiId: String): Pair<Int?, String?> = authed { t ->
        val req = buildJsonObject {
            put("paymentId", paymentId)
            put("storeId", "CHARGEPOINT")
            put("poiId", poiId)
            put("genType", genType())
        }
        val body = iacCheck(post("iac/stopCharge", req, mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        body.int("result.resultCode") to (body.str("result.errorCaseId") ?: body.str("result.resultMsg"))
    }

    /** Live energy, power and cost for a session (iac/chargeStatus). */
    suspend fun iacSessionStatus(vehicleId: String, vin: String, paymentId: String, refresh: Boolean): JsonObject = authed { t ->
        val req = buildJsonObject {
            put("vin", vin)
            put("genType", genType())
            put("paymentId", paymentId)
            put("needToRefresh", refresh)
            put("needBWData", true)
        }
        val body = iacCheck(post("iac/chargeStatus", req, mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        (body["result"] as? JsonObject) ?: JsonObject(emptyMap())
    }

    /**
     * EV route planning (iac/rp): a route from [from] to [to] with charging stops, planned so
     * the car reaches each charger with at least [chargerSoc]% and the destination with
     * [destinationSoc]%. Route option 22 is what the official app always asks for; several
     * [options] get one route each (what the other codes mean is still being worked out).
     */
    suspend fun iacRoute(
        vehicleId: String,
        vin: String,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        chargerSoc: Int,
        destinationSoc: Int,
        vendorNum: Int,
        options: List<Int> = listOf(22),
    ): JsonObject = authed { t ->
        val req = buildJsonObject {
            put("version", "1.0")
            put("clientType", 6)
            put("id", vin)
            putJsonObject("origin") { put("lat", from.first); put("lon", from.second) }
            putJsonObject("destination") {
                putJsonArray("points") { add(buildJsonObject { put("lat", to.first); put("lon", to.second) }) }
            }
            put("evChargingStationCandidates", 0)
            putJsonArray("route") { options.forEach { o -> add(buildJsonObject { put("routeOption", o) }) } }
            putJsonObject("vehicleInfo") {
                put("chargerType", 37) // CCS and NACS
                put("chargerSpeed", 7) // every speed
                put("destinationArrivalSoc", destinationSoc)
                put("chargingStationArrivalSoc", chargerSoc)
                put("genType", genType())
                put("vendorNum", vendorNum)
            }
        }
        val body = iacCheck(post("iac/rp", req, mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        (body["result"] as? JsonObject) ?: JsonObject(emptyMap())
    }

    /** Charging sessions paid through the app between two dates (yyyyMMdd). */
    suspend fun iacOrders(vehicleId: String, profileId: String, start: String, end: String): JsonObject = authed { t ->
        val req = buildJsonObject {
            put("profileId", profileId)
            put("startDate", start)
            put("endDate", end)
            put("brandIndicator", "H")
        }
        val body = iacCheck(post("iac/orderInfo", req, mapOf("accessToken" to t, "vehicleId" to vehicleId)))
        (body["result"] as? JsonObject) ?: JsonObject(emptyMap())
    }

    // ------------------------------------------------------- developer: endpoint probe

    /** What the server said to a probe: HTTP status, Bluelink codes and the start of the body. */
    data class ProbeResult(val path: String, val http: Int, val responseCode: Int?, val errorCode: String?, val message: String?, val body: String)

    /**
     * Posts [body] to [path] with the usual headers (and PIN authorisation when [authorise]),
     * reporting whatever comes back instead of throwing. Used to look for endpoints the
     * official app doesn't call.
     */
    suspend fun probe(path: String, vehicleId: String, body: JsonObject, authorise: Boolean): ProbeResult = authed { t ->
        val headers = mutableMapOf("accessToken" to t, "vehicleId" to vehicleId)
        if (authorise) store.pin?.let { pin -> headers["pAuth"] = pAuth(t, vehicleId, pin) }
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(BASE_URL + path.trimStart('/'))
                .header("Content-Type", "application/json")
                .header("language", "0")
                .header("offset", offsetHours())
                .header("from", "SPA")
                .header("brand", "H")
                .header("client_id", CLIENT_ID)
                .header("client_secret", CLIENT_SECRET)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.post(body.toString().toRequestBody(JSON_MEDIA))
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ProbeResult(
                    path = path,
                    http = resp.code,
                    responseCode = json?.int("responseHeader.responseCode"),
                    errorCode = json?.str("error.errorCode"),
                    message = json?.str("error.errorDesc") ?: json?.str("responseHeader.responseDesc") ?: json?.str("message"),
                    body = text.take(300),
                )
            }
        }
    }

    /** Flashes the hazard lights, sounding the horn too when [horn] is set (hrnlght). */
    suspend fun hornLights(vehicleId: String, horn: Boolean) =
        command("hrnlght", vehicleId, buildJsonObject { put("horn", horn) })

    suspend fun chargePort(vehicleId: String, open: Boolean) =
        command(if (open) "evc/chportopn" else "evc/chportcls", vehicleId)

    /** Warms (or cools) the battery for faster DC charging. */
    suspend fun batteryPreconditioning(vehicleId: String, on: Boolean) =
        command(if (on) "evc/prcndtngon" else "evc/prcndtngoff", vehicleId)

    /**
     * Sets the charge limits (percent, in steps of 10). Gen3 cars take them straight away with
     * v2/ev/reserv/socset and return null; older cars get a PIN command (evc/setsoc) whose
     * transactionId is returned for polling. plugType 0 = DC fast charging, 1 = AC.
     */
    suspend fun setChargeLimits(vehicleId: String, ac: Int, dc: Int): String? {
        if (store.caps?.isGen3 == true) {
            authed { t ->
                check(
                    post(
                        "v2/ev/reserv/socset",
                        buildJsonObject {
                            putJsonArray("targetSOClist") {
                                add(buildJsonObject { put("plugType", 0); put("targetSOClevel", dc) })
                                add(buildJsonObject { put("plugType", 1); put("targetSOClevel", ac) })
                            }
                        },
                        mapOf("accessToken" to t, "vehicleId" to vehicleId),
                    )
                )
            }
            return null
        }
        return command(
            "evc/setsoc", vehicleId,
            buildJsonObject {
                putJsonArray("tsoc") {
                    add(buildJsonObject { put("plugType", 0); put("level", dc) })
                    add(buildJsonObject { put("plugType", 1); put("level", ac) })
                }
            },
        )
    }
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
