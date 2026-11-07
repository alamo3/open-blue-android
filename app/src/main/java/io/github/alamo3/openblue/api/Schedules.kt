package io.github.alamo3.openblue.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Schedules kept on Bluelink's servers (so they run with the phone off): two climate starts and
 * one daily charging window. Formats follow the official Canadian app's v2/rc/ev calls.
 */

/** A clock time. Bluelink sends it as 12-hour "HHmm" plus a section, 0 = AM and 1 = PM. */
data class TimeOfDay(val hour: Int, val minute: Int) {
    init { require(hour in 0..23 && minute in 0..59) }

    /** `{time: "0745", timeSection: 0}` */
    fun toJson(): JsonObject {
        val h12 = (hour % 12).let { if (it == 0) 12 else it }
        return buildJsonObject {
            put("time", "%02d%02d".format(h12, minute))
            put("timeSection", if (hour >= 12) 1 else 0)
        }
    }

    val label: String
        get() {
            val h12 = (hour % 12).let { if (it == 0) 12 else it }
            return "%d:%02d %s".format(h12, minute, if (hour >= 12) "PM" else "AM")
        }

    companion object {
        /** Reads `{time: "0745", timeSection: 0}`, also tolerating "7:45" and a missing section. */
        fun fromJson(e: JsonElement?): TimeOfDay? {
            val text = e.str("time")?.filter { it.isDigit() } ?: return null
            if (text.length < 3) return null
            val padded = text.padStart(4, '0').takeLast(4)
            val h12 = padded.substring(0, 2).toIntOrNull() ?: return null
            val m = padded.substring(2).toIntOrNull() ?: return null
            if (h12 !in 0..12 || m !in 0..59) return null
            val pm = e.int("timeSection") == 1
            val hour = (h12 % 12) + if (pm) 12 else 0
            return TimeOfDay(hour, m)
        }
    }
}

/** One of the two climate-start slots. Days are 0 = Sunday … 6 = Saturday; none means "next time only". */
data class ClimateSlot(
    val on: Boolean = false,
    val days: Set<Int> = emptySet(),
    val time: TimeOfDay = TimeOfDay(7, 30),
    val tempC: Double = 21.0,
    val defrost: Boolean = false,
    /** Heated steering wheel / rear window level as Bluelink counts it (0 = off). */
    val heating: Int = 0,
    /** Fields we don't edit but send back unchanged. */
    val airCtrl: Int? = null,
    val airPurifier: Int? = null,
) {
    /** The slot as the server wants it. [hex] is the set-point from the car's temperature table. */
    fun toJson(hex: String): JsonObject = buildJsonObject {
        put("reservHVACflag", if (on) 1 else 0)
        put("reservInfo", buildJsonObject {
            put("day", buildJsonArray {
                // The official app sends 9 when no day is chosen.
                (days.sorted().ifEmpty { listOf(NO_DAY) }).forEach { add(JsonPrimitive(it)) }
            })
            put("time", time.toJson())
        })
        putJsonObject("reservHVACSet") {
            airCtrl?.let { put("airCtrl", it) } ?: put("airCtrl", 1)
            put("defrost", defrost)
            put("heating1", heating)
            airPurifier?.let { put("airPurifierControl", it) }
            putJsonObject("airTemp") {
                put("value", hex)
                put("unit", 0)
            }
        }
    }

    companion object {
        const val NO_DAY = 9

        fun fromJson(e: JsonElement?, table: List<TempTables.Entry>): ClimateSlot? {
            if (e == null || e !is JsonObject) return null
            val time = TimeOfDay.fromJson(e.at("reservInfo.time")) ?: TimeOfDay(7, 30)
            val days = e.arr("reservInfo.day").mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                .filter { it in 0..6 }.toSet()
            val set = e.at("reservHVACSet")
            return ClimateSlot(
                on = e.int("reservHVACflag") == 1,
                days = days,
                time = time,
                tempC = TempTables.celsiusForHex(table, set.str("airTemp.value")) ?: 21.0,
                defrost = set.bool("defrost") == true,
                heating = set.int("heating1") ?: 0,
                airCtrl = set.int("airCtrl"),
                airPurifier = set.int("airPurifierControl"),
            )
        }
    }
}

data class ClimateSchedule(val first: ClimateSlot, val second: ClimateSlot) {
    companion object {
        /** The server's `result` object: reservedHVACInfo1 and reservedHVACInfo2. */
        fun fromJson(result: JsonElement?, table: List<TempTables.Entry>) = ClimateSchedule(
            ClimateSlot.fromJson(result.at("reservedHVACInfo1"), table) ?: ClimateSlot(),
            ClimateSlot.fromJson(result.at("reservedHVACInfo2"), table) ?: ClimateSlot(),
        )
    }
}

/** The daily charging window: the car charges only between [start] and [end] while it's plugged in. */
data class ChargeWindow(
    val on: Boolean = false,
    val start: TimeOfDay = TimeOfDay(23, 0),
    val end: TimeOfDay = TimeOfDay(6, 0),
    /** Charge only when electricity is off-peak, on cars that report it. */
    val offPeak: Boolean = false,
) {
    fun toJson(pin: String): JsonObject = buildJsonObject {
        put("reservFlag", if (on) 1 else 0)
        put("offpeakPowerFlag", if (offPeak) 1 else 0)
        put("reservStartTime", start.toJson())
        put("reservEndTime", end.toJson())
        put("pin", pin)
    }

    companion object {
        fun fromJson(result: JsonElement?): ChargeWindow? {
            if (result == null || result !is JsonObject) return null
            return ChargeWindow(
                on = result.int("reservFlag") == 1,
                start = TimeOfDay.fromJson(result.at("reservStartTime")) ?: TimeOfDay(23, 0),
                end = TimeOfDay.fromJson(result.at("reservEndTime")) ?: TimeOfDay(6, 0),
                offPeak = result.int("offpeakPowerFlag") == 1,
            )
        }
    }
}

/** Sunday-first day names, matching the server's 0…6. */
val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

fun daysLabel(days: Set<Int>): String = when {
    days.isEmpty() -> "Next time only"
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    days == setOf(0, 6) -> "Weekends"
    else -> days.sorted().joinToString(", ") { DAY_NAMES[it] }
}
