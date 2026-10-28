package io.github.alamo3.openblue.work

import io.github.alamo3.openblue.ui.CarStatus
import kotlinx.serialization.Serializable

/** The alerts background checks can raise; each can be switched off in Settings. */
enum class AlertKind(val key: String, val label: String, val detail: String) {
    CHARGE("charge", "Charging", "Finished, reached the limit, or stopped early"),
    OPEN("open", "Left unlocked or open", "Unlocked, or a door, window, trunk or frunk open for 10 minutes"),
    BATTERY_12V("battery12v", "Low 12V battery", "Below 40%"),
    TIRES("tires", "Tire pressure", "The tire pressure warning comes on"),
}

/** What the last check saw, so an alert fires once per occurrence rather than every check. */
@Serializable
data class WatchMemory(
    val seen: Boolean = false,
    val charging: Boolean = false,
    val pluggedIn: Boolean = false,
    val battery: Int? = null,
    val limit: Int? = null,
    /** When the car was first seen unlocked/open (0 = closed), and whether that was already announced. */
    val openSince: Long = 0L,
    val openAlerted: Boolean = false,
    val lowBattery12v: Boolean = false,
    val tireWarning: Boolean = false,
    /** The status timestamp last looked at; an unchanged report isn't re-evaluated. */
    val statusTime: Long = 0L,
)

data class Alert(val kind: AlertKind, val title: String, val text: String)

object Alerts {
    const val OPEN_GRACE_MS = 10 * 60_000L
    private const val LOW_12V = 40
    private const val OK_12V = 50

    /**
     * Compares a fresh status with the last one. [now] is the phone's clock; the open-for-10-minutes
     * timer runs from when a check first saw the car open.
     */
    fun evaluate(
        mem: WatchMemory,
        st: CarStatus,
        now: Long,
        car: String,
        enabled: (AlertKind) -> Boolean = { true },
    ): Pair<WatchMemory, List<Alert>> {
        val out = mutableListOf<Alert>()

        // Charging
        val limit = st.chargeLimitAc ?: st.chargeLimitDc ?: mem.limit
        if (mem.seen && mem.charging && !st.charging && enabled(AlertKind.CHARGE)) {
            val pct = st.battery?.let { "$it%" }
            val reached = st.battery != null && (limit == null || st.battery >= limit - 1)
            out += when {
                st.battery == 100 -> Alert(AlertKind.CHARGE, "$car is fully charged", "Battery at 100%.")
                reached -> Alert(AlertKind.CHARGE, "$car finished charging", "Battery at ${pct ?: "its limit"}.")
                st.pluggedIn -> Alert(AlertKind.CHARGE, "$car stopped charging", "Still plugged in${pct?.let { " at $it" } ?: ""}${limit?.let { ", below the $it% limit" } ?: ""}.")
                else -> Alert(AlertKind.CHARGE, "$car was unplugged", "Charging stopped${pct?.let { " at $it" } ?: ""}.")
            }
        }

        // Unlocked or open
        val open = buildList {
            if (st.locked == false) add("unlocked")
            if (st.openDoors.isNotEmpty()) add(if (st.openDoors.size == 1) "${st.openDoors[0]} door open" else "${st.openDoors.size} doors open")
            if (st.openWindows.isNotEmpty()) add(if (st.openWindows.size == 1) "${st.openWindows[0]} window open" else "${st.openWindows.size} windows open")
            if (st.trunkOpen) add("trunk open")
            if (st.frunkOpen) add("frunk open")
        }
        var openSince = mem.openSince
        var openAlerted = mem.openAlerted
        if (open.isEmpty()) {
            openSince = 0L
            openAlerted = false
        } else {
            // The car's own report time is the best guess at when it was left that way.
            if (openSince == 0L) openSince = minOf(now, st.updatedAt?.time ?: now)
            if (!openAlerted && now - openSince >= OPEN_GRACE_MS && enabled(AlertKind.OPEN)) {
                val what = open.joinToString(", ").replaceFirstChar { it.uppercase() }
                out += Alert(AlertKind.OPEN, if (st.locked == false) "$car is unlocked" else "$car is open", "$what.")
                openAlerted = true
            }
        }

        // 12V battery, with hysteresis so it doesn't flap around the threshold
        var low = mem.lowBattery12v
        val v12 = st.battery12v
        if (v12 != null) {
            if (!low && v12 < LOW_12V) {
                low = true
                if (enabled(AlertKind.BATTERY_12V)) {
                    out += Alert(AlertKind.BATTERY_12V, "$car's 12V battery is low", "At $v12%. Driving or charging the main battery tops it up.")
                }
            } else if (low && v12 > OK_12V) {
                low = false
            }
        }

        // Tires
        if (st.tireWarning && !mem.tireWarning && enabled(AlertKind.TIRES)) {
            out += Alert(AlertKind.TIRES, "$car: check tire pressure", "The tire pressure warning is on.")
        }

        val next = WatchMemory(
            seen = true,
            charging = st.charging,
            pluggedIn = st.pluggedIn,
            battery = st.battery,
            limit = limit,
            openSince = openSince,
            openAlerted = openAlerted,
            lowBattery12v = low,
            tireWarning = st.tireWarning,
            statusTime = st.updatedAt?.time ?: mem.statusTime,
        )
        return next to out
    }
}
