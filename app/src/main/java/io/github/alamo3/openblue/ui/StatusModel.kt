package io.github.alamo3.openblue.ui

import io.github.alamo3.openblue.api.TempTables
import io.github.alamo3.openblue.api.arr
import io.github.alamo3.openblue.api.bool
import io.github.alamo3.openblue.api.dbl
import io.github.alamo3.openblue.api.int
import io.github.alamo3.openblue.api.str
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The parts of a Bluelink status response the UI shows, with nulls where the car didn't report. */
data class CarStatus(
    val battery: Int?,
    val range: Int?,
    val rangeUnit: String,
    val charging: Boolean,
    val pluggedIn: Boolean,
    val chargeMinutesLeft: Int?,
    val chargeLimitAc: Int?,
    val chargeLimitDc: Int?,
    /** Null when the car doesn't report it (older cars). */
    val chargePortOpen: Boolean?,
    /** Battery preconditioning on, or null when the car doesn't report it. */
    val preconditioning: Boolean?,
    val locked: Boolean?,
    val openDoors: List<String>,
    val openWindows: List<String>,
    val trunkOpen: Boolean,
    val frunkOpen: Boolean,
    val climateOn: Boolean,
    val setTempC: Double?,
    val defrost: Boolean,
    val wheelHeat: Boolean,
    val rearHeat: Boolean,
    /** drv, ast, rl, rr raw state codes */
    val seats: List<Int?>,
    val battery12v: Int?,
    val tireWarning: Boolean,
    val updatedAt: Date?,
) {
    companion object {
        private val DOORS = listOf("front left" to "frontLeft", "front right" to "frontRight", "rear left" to "backLeft", "rear right" to "backRight")

        fun from(s: JsonObject, table: List<TempTables.Entry>): CarStatus {
            val ev = "evStatus"
            val range = s.dbl("$ev.drvDistance.0.rangeByFuel.totalAvailableRange.value")
                ?: s.dbl("$ev.drvDistance.0.rangeByFuel.evModeRange.value")
                ?: s.dbl("dte.value")
            val unit = s.int("$ev.drvDistance.0.rangeByFuel.totalAvailableRange.unit") ?: s.int("dte.unit")
            val limits = s.arr("$ev.reservChargeInfos.targetSOClist")
            // plugType 0 = DC fast charging, 1 = AC (Level 2), as in hyundai_kia_connect_api.
            fun limit(plug: Int) = limits.firstOrNull { it.int("plugType") == plug }?.int("targetSOClevel")
            val minutes = s.int("$ev.remainTime2.atc.value")?.takeIf { it > 0 }
            return CarStatus(
                battery = s.int("$ev.batteryStatus"),
                range = range?.toInt(),
                rangeUnit = if (unit == 3) "mi" else "km",
                charging = s.bool("$ev.batteryCharge") == true,
                pluggedIn = (s.int("$ev.batteryPlugin") ?: 0) != 0,
                chargeMinutesLeft = minutes,
                chargeLimitAc = limit(1),
                chargeLimitDc = limit(0),
                chargePortOpen = (s.int("$ev.chargePortDoorOpenStatus") ?: s.int("$ev.chargePortDoorOpen"))?.let { it == 1 },
                preconditioning = s.bool("$ev.batteryPreconditiong") ?: s.bool("$ev.batteryPrecondition"),
                locked = s.bool("doorLock"),
                openDoors = DOORS.filter { (s.int("doorOpen.${it.second}") ?: 0) != 0 }.map { it.first },
                openWindows = DOORS.filter { (s.int("windowOpen.${it.second}") ?: 0) != 0 }.map { it.first },
                trunkOpen = s.bool("trunkOpen") == true,
                frunkOpen = s.bool("hoodOpen") == true,
                climateOn = s.bool("airCtrlOn") == true,
                setTempC = TempTables.celsiusForHex(table, s.str("airTemp.value")),
                defrost = s.bool("defrost") == true,
                wheelHeat = (s.int("steerWheelHeat") ?: 0) != 0,
                rearHeat = (s.int("sideBackWindowHeat") ?: 0) != 0,
                seats = listOf("drv", "ast", "rl", "rr").map { s.int("seatHeaterVentInfo.${it}SeatHeatState") },
                battery12v = s.int("battery.batSoc"),
                tireWarning = (s.int("tirePressureLamp.tirePressureLampAll") ?: 0) != 0,
                updatedAt = s.str("lastStatusDate")?.let { parseDate(it) },
            )
        }

        /**
         * "yyyyMMddHHmmss" in UTC. (Read as local time, a status from 7:42 PM in Toronto showed
         * as 11:42 PM, later than the phone's clock.)
         */
        fun parseDate(raw: String): Date? = runCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(raw)
        }.getOrNull()
    }
}

/** "just now", "12 min ago", "3 h ago", or a date. */
fun relativeTime(date: Date?, now: Long = System.currentTimeMillis()): String {
    if (date == null) return "unknown"
    val mins = (now - date.time) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "${mins / 60} h ago"
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
    }
}

fun formatMinutes(m: Int): String = if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
