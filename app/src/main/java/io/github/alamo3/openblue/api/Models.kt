package io.github.alamo3.openblue.api

import kotlinx.serialization.Serializable

@Serializable
data class VehicleInfo(
    val id: String,
    val nickname: String,
    val model: String,
    val year: String,
    val vin: String,
    val fuelKindCode: String,
)

/**
 * Per-vehicle capabilities from sltvhcl. Field meanings follow the official app:
 * genType "G3" -> Gen3 climate payload ("remoteControl"); strgWhlHeatingOption 1 -> the
 * steering wheel and rear/mirror heaters are separately controllable, otherwise one combined
 * switch (heating1 = 1). seatHeatVent option 1 appears to mean heat-only (rear seats).
 */
@Serializable
data class VehicleCaps(
    val genType: String? = null,
    val hvacTempType: Int? = null,
    val strgWhlHeatingOption: Int? = null,
    /** drv, ast, rl, rr */
    val seatOptions: List<Int?> = listOf(null, null, null, null),
) {
    val isGen3: Boolean get() = genType?.contains("3") == true
    val splitHeating: Boolean get() = strgWhlHeatingOption == 1

    fun heatingOptions(): List<Heating> =
        if (splitHeating) listOf(Heating.OFF, Heating.STEERING, Heating.REAR_MIRRORS, Heating.STEERING_REAR_MIRRORS)
        else listOf(Heating.OFF, Heating.ALL)

    fun seatLevels(seat: Int): List<SeatLevel> =
        if (seatOptions.getOrNull(seat) == 1) SeatLevel.entries.filter { it.code == 0 || it.code >= 6 }
        else SeatLevel.entries.toList()
}

enum class ClimateFormat(val label: String) {
    AUTO("Automatic"),
    HVAC_INFO("Older cars (\"hvacInfo\")"),
    REMOTE_CONTROL("Gen3 cars (\"remoteControl\")"),
}

/** heating1 values the Bluelink API accepts (from the official app's preset mapping). */
enum class Heating(val code: Int, val label: String) {
    OFF(0, "Off"),
    ALL(1, "Steering wheel, mirrors & rear defrost"),
    REAR_MIRRORS(2, "Rear window + mirrors"),
    STEERING(3, "Steering wheel"),
    STEERING_REAR_MIRRORS(4, "Steering + rear + mirrors");

    companion object {
        fun of(code: Int?) = entries.firstOrNull { it.code == code } ?: OFF
    }
}

/** seatHeaterVentCMD values. 0 = off/no change, 3-5 = cooling, 6-8 = heating. */
enum class SeatLevel(val code: Int, val label: String) {
    OFF(0, "Off"),
    HEAT_LOW(6, "Heat low"),
    HEAT_MED(7, "Heat medium"),
    HEAT_HIGH(8, "Heat high"),
    COOL_LOW(3, "Cool low"),
    COOL_MED(4, "Cool medium"),
    COOL_HIGH(5, "Cool high");

    companion object {
        fun of(code: Int?) = entries.firstOrNull { it.code == code } ?: OFF

        /** Status responses report 1 for "on" and 2 for "off" as well. */
        fun statusLabel(code: Int?): String = when (code) {
            null -> "—"
            0, 2 -> "Off"
            1 -> "On"
            else -> of(code).label
        }
    }
}

@Serializable
data class ClimateSettings(
    val name: String = "",
    val climateOn: Boolean = true,
    val tempC: Double = 21.0,
    val defrost: Boolean = false,
    val heating: Int = 0,
    val durationMin: Int = 10,
    val driverSeat: Int = 0,
    val passengerSeat: Int = 0,
    val rearLeftSeat: Int = 0,
    val rearRightSeat: Int = 0,
) {
    fun summary(): String {
        val parts = mutableListOf<String>()
        parts += if (climateOn) "${fmt(tempC)} °C" else "A/C off"
        if (defrost) parts += "defrost"
        if (heating != 0) parts += Heating.of(heating).label.lowercase()
        val seats = listOf("D" to driverSeat, "P" to passengerSeat, "RL" to rearLeftSeat, "RR" to rearRightSeat)
            .filter { it.second != 0 }
            .joinToString(" ") { "${it.first}:${SeatLevel.of(it.second).label.lowercase()}" }
        if (seats.isNotEmpty()) parts += "seats $seats"
        parts += "${durationMin} min"
        return parts.joinToString(" · ")
    }

    companion object {
        fun fmt(c: Double) = if (c % 1.0 == 0.0) c.toInt().toString() else c.toString()
    }
}

/** A preset saved on the Bluelink server (gtfvsttng) — the ones you make on mybluelink.ca. */
data class ServerPreset(val id: Int?, val isDefault: Boolean, val settings: ClimateSettings)
