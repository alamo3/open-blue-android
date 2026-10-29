package io.github.alamo3.openblue.update

import android.content.Context

/** Small, non-secret update bookkeeping (plain preferences). */
object UpdateState {
    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun autoUpdate(context: Context) = prefs(context).getBoolean("auto", true)
    fun setAutoUpdate(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto", on).apply()

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
