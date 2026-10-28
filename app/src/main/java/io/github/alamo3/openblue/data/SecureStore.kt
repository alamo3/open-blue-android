package io.github.alamo3.openblue.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.alamo3.openblue.api.CarLocation
import io.github.alamo3.openblue.api.ClimateFormat
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.VehicleCaps
import io.github.alamo3.openblue.api.VehicleInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Everything is kept in EncryptedSharedPreferences (AES-256, key in the Android
 * Keystore). Nothing leaves the phone except the requests to mybluelink.ca.
 */
class SecureStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(encryptedPrefs(context))

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val KEY_DEVICE = "deviceId"

        private fun encryptedPrefs(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                "openblue_secure",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private fun read(k: String): String? = prefs.getString(k, null)
    private fun write(k: String, v: String?) = prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()

    /**
     * Stable per-install device ID. Bluelink asks for an emailed/SMS code the
     * first time it sees a device ID, so this must never change between logins.
     */
    val deviceId: String
        get() = read(KEY_DEVICE) ?: UUID.randomUUID().toString().also { write(KEY_DEVICE, it) }

    var username: String?
        get() = read("username")
        set(v) = write("username", v)
    var password: String?
        get() = read("password")
        set(v) = write("password", v)
    var pin: String?
        get() = read("pin")
        set(v) = write("pin", v)
    var accessToken: String?
        get() = read("accessToken")
        set(v) = write("accessToken", v)
    var tokenExpiresAt: Long
        get() = prefs.getLong("tokenExpiresAt", 0L)
        set(v) = prefs.edit().putLong("tokenExpiresAt", v).apply()
    var caps: VehicleCaps?
        get() = read("caps")?.let { runCatching { json.decodeFromString<VehicleCaps>(it) }.getOrNull() }
        set(v) = write("caps", v?.let { json.encodeToString(it) })

    /** Which JSON key climate settings go under; AUTO picks from the vehicle's genType. */
    var climateFormat: ClimateFormat
        get() = read("climateFormat")?.let { runCatching { ClimateFormat.valueOf(it) }.getOrNull() } ?: ClimateFormat.AUTO
        set(v) = write("climateFormat", v.name)

    /** Shows the floating developer panel. */
    var developerMode: Boolean
        get() = prefs.getBoolean("developerMode", false)
        set(v) = prefs.edit().putBoolean("developerMode", v).apply()

    /** Trip planning avoids toll roads. */
    var avoidTolls: Boolean
        get() = prefs.getBoolean("avoidTolls", false)
        set(v) = prefs.edit().putBoolean("avoidTolls", v).apply()

    /** Ask for fingerprint/screen lock before unlocking the car. */
    var confirmUnlock: Boolean
        get() = prefs.getBoolean("confirmUnlock", true)
        set(v) = prefs.edit().putBoolean("confirmUnlock", v).apply()

    var vehicle: VehicleInfo?
        get() = read("vehicle")?.let { runCatching { json.decodeFromString<VehicleInfo>(it) }.getOrNull() }
        set(v) = write("vehicle", v?.let { json.encodeToString(it) })

    var localPresets: List<ClimateSettings>
        get() = read("localPresets")?.let { runCatching { json.decodeFromString<List<ClimateSettings>>(it) }.getOrNull() } ?: emptyList()
        set(v) = write("localPresets", json.encodeToString(v))

    var lastClimate: ClimateSettings?
        get() = read("lastClimate")?.let { runCatching { json.decodeFromString<ClimateSettings>(it) }.getOrNull() }
        set(v) = write("lastClimate", v?.let { json.encodeToString(it) })

    /** The car's last known location (kept encrypted like everything else here). */
    var lastLocation: CarLocation?
        get() = read("lastLocation")?.let { runCatching { json.decodeFromString<CarLocation>(it) }.getOrNull() }
        set(v) = write("lastLocation", v?.let { json.encodeToString(it) })

    /** A paid charge started from this phone, while it may still be running. */
    var activeSession: io.github.alamo3.openblue.api.ActiveSession?
        get() = read("activeSession")?.let { runCatching { json.decodeFromString<io.github.alamo3.openblue.api.ActiveSession>(it) }.getOrNull() }
        set(v) = write("activeSession", v?.let { json.encodeToString(it) })

    /** The card the user last charged with. */
    var payCardId: String?
        get() = read("payCardId")
        set(v) = write("payCardId", v)

    var cachedStatus: String?
        get() = read("cachedStatus")
        set(v) = write("cachedStatus", v)

    /** Signs out but keeps the device ID (so the next login won't need a code) and local presets. */
    fun signOut() {
        val keep = mapOf(KEY_DEVICE to read(KEY_DEVICE), "localPresets" to read("localPresets"))
        prefs.edit().clear().apply()
        keep.forEach { (k, v) -> write(k, v) }
    }

}
