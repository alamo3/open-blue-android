package io.github.alamo3.openblue.update

import android.content.Context
import io.github.alamo3.openblue.BuildConfig

enum class UpdateChannel(val label: String, val key: String, val releaseTag: String) {
    PUBLIC("Public", "public", "latest"),
    STAGING("Staging changes", "staging-changes", "staging-changes");

    val baseUrl get() = "https://github.com/alamo3/open-blue-android/releases/download/$releaseTag/"

    companion object {
        fun fromKey(key: String?): UpdateChannel = entries.firstOrNull { it.key == key } ?: PUBLIC
    }
}

/** Small, non-secret update bookkeeping (plain preferences). */
object UpdateState {
    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun autoUpdate(context: Context) = prefs(context).getBoolean("auto", true)
    fun setAutoUpdate(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto", on).apply()

    fun channel(context: Context): UpdateChannel = UpdateChannel.fromKey(
        prefs(context).getString("channel", BuildConfig.DEFAULT_UPDATE_CHANNEL),
    )
    fun setChannel(context: Context, channel: UpdateChannel) =
        prefs(context).edit().putString("channel", channel.key).apply()

    /** Remembered across the restart, to say what the new version brought. */
    fun noteInstalling(context: Context, r: Release) =
        prefs(context).edit().putString("installingName", r.versionName).putString("installingMessage", r.message).apply()

    fun takeInstalledNote(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val name = p.getString("installingName", null) ?: return null
        val msg = p.getString("installingMessage", "").orEmpty()
        p.edit().remove("installingName").remove("installingMessage").apply()
        return name to msg
    }

    var lastError: String? = null
}
