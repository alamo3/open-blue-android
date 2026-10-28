package io.github.alamo3.openblue.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
class SecureStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "openblue_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
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

    var vehicle: VehicleInfo?
        get() = read("vehicle")?.let { runCatching { json.decodeFromString<VehicleInfo>(it) }.getOrNull() }
        set(v) = write("vehicle", v?.let { json.encodeToString(it) })

    var localPresets: List<ClimateSettings>
        get() = read("localPresets")?.let { runCatching { json.decodeFromString<List<ClimateSettings>>(it) }.getOrNull() } ?: emptyList()
        set(v) = write("localPresets", json.encodeToString(v))

    var lastClimate: ClimateSettings?
        get() = read("lastClimate")?.let { runCatching { json.decodeFromString<ClimateSettings>(it) }.getOrNull() }
        set(v) = write("lastClimate", v?.let { json.encodeToString(it) })

    var cachedStatus: String?
        get() = read("cachedStatus")
        set(v) = write("cachedStatus", v)

    /** Signs out but keeps the device ID (so the next login won't need a code) and local presets. */
    fun signOut() {
        val keep = mapOf(KEY_DEVICE to read(KEY_DEVICE), "localPresets" to read("localPresets"))
        prefs.edit().clear().apply()
        keep.forEach { (k, v) -> write(k, v) }
    }

    companion object {
        private const val KEY_DEVICE = "deviceId"
    }
}
