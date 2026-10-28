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

/** heating1 values the Bluelink API accepts (from the official app's preset mapping). */
enum class Heating(val code: Int, val label: String) {
    OFF(0, "Off"),
    ALL(1, "All (combined)"),
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
