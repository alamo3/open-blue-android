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
    /** Model code the charging services want (vehicleKindCode in vhcllst). */
    val kindCode: String? = null,
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
    /**
     * The "genType" HTTP header on the sltvhcl answer ("Gen3" ...). The official app calls it the
     * vehicle generation and picks the climate payload by it; the body's genType ("G3") is a
     * different thing and reads G3 on older head units too. null = not fetched yet; "" = the answer
     * had no such header (the official app then sends hvacInfo).
     */
    val genHeader: String? = null,
    val hvacTempType: Int? = null,
    val strgWhlHeatingOption: Int? = null,
    /** drv, ast, rl, rr */
    val seatOptions: List<Int?> = listOf(null, null, null, null),
    val photoUrl: String? = null,
    val trim: String? = null,
    val odometer: Double? = null,
    /** 1 = km, 3 = miles (same units as range). */
    val odometerUnit: Int? = null,
    /** "ParkingSvm" in the Menu feature list: remote surround-view photos. */
    val hasSvm: Boolean = false,
    /** Model code the charging services identify the car by (vehicleKindCode, else modelCode). */
    val modelCode: String? = null,
    /** "Fod" in the feature list: Hyundai offers the Bluelink Store (features on demand) for this car. */
    val listsStore: Boolean = false,
    /** The store's web address for this car (fodWebManualUrlEn / Fr), when Bluelink gives one. */
    val storeUrlEn: String? = null,
    val storeUrlFr: String? = null,
) {
    val isGen3: Boolean get() = genType?.contains("3") == true

    /** Order a seat cycles through when tapped, starting from the warmest setting. */
    fun seatCycle(seat: Int): List<SeatLevel> {
        val allowed = seatLevels(seat)
        return listOf(
            SeatLevel.OFF, SeatLevel.HEAT_HIGH, SeatLevel.HEAT_MED, SeatLevel.HEAT_LOW,
            SeatLevel.COOL_HIGH, SeatLevel.COOL_MED, SeatLevel.COOL_LOW,
        ).filter { it in allowed }
    }

    fun nextSeatLevel(seat: Int, currentCode: Int): SeatLevel {
        val cycle = seatCycle(seat)
        val i = cycle.indexOfFirst { it.code == currentCode }
        return cycle[(i + 1).mod(cycle.size)]
    }
    val splitHeating: Boolean get() = strgWhlHeatingOption == 1

    fun heatingOptions(): List<Heating> =
        if (splitHeating) listOf(Heating.OFF, Heating.STEERING, Heating.REAR_MIRRORS, Heating.STEERING_REAR_MIRRORS)
        else listOf(Heating.OFF, Heating.ALL)

    /**
     * What a seat can be set to remotely, from its seatHeatVent option, as the official app reads
     * it (0 = no remote control of that seat). Unknown (not loaded yet) allows everything.
     */
    fun seatLevels(seat: Int): List<SeatLevel> {
        val codes = when (seatOptions.getOrNull(seat) ?: return SeatLevel.entries.toList()) {
            1 -> listOf(6, 8)
            2 -> listOf(6, 7, 8)
            3 -> listOf(3, 5)
            4 -> listOf(3, 4, 5)
            5 -> listOf(3, 5, 6, 8)
            6 -> listOf(3, 4, 5, 6, 7, 8)
            8 -> listOf(8)
            9 -> listOf(5, 8)
            else -> emptyList()
        }
        return SeatLevel.entries.filter { it.code == 0 || it.code in codes }
    }

    /** True when at least one seat can be heated or cooled remotely (or it isn't known yet). */
    val remoteSeats: Boolean get() = (0 until 4).any { seatLevels(it).size > 1 }
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

    val isHeat: Boolean get() = code >= 6
    val isCool: Boolean get() = code in 3..5
    /** Bars lit on a seat indicator, like the car's own seat buttons (3 = high). */
    val bars: Int get() = when (this) {
        OFF -> 0
        HEAT_LOW, COOL_LOW -> 1
        HEAT_MED, COOL_MED -> 2
        HEAT_HIGH, COOL_HIGH -> 3
    }

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
    val seats: List<Int> get() = listOf(driverSeat, passengerSeat, rearLeftSeat, rearRightSeat)

    fun withSeat(index: Int, code: Int) = when (index) {
        0 -> copy(driverSeat = code)
        1 -> copy(passengerSeat = code)
        2 -> copy(rearLeftSeat = code)
        else -> copy(rearRightSeat = code)
    }

    /** Short plain-language description, e.g. "22 °C for 10 min, defrost, 2 seats heated". */
    fun summary(): String {
        val parts = mutableListOf<String>()
        parts += if (climateOn) "${fmt(tempC)} °C for $durationMin min" else "Fan only for $durationMin min"
        if (defrost) parts += "defrost"
        if (heating != 0) parts += "heated wheel"
        val heated = seats.count { SeatLevel.of(it).isHeat }
        val cooled = seats.count { SeatLevel.of(it).isCool }
        if (heated > 0) parts += if (heated == 1) "1 seat heated" else "$heated seats heated"
        if (cooled > 0) parts += if (cooled == 1) "1 seat cooled" else "$cooled seats cooled"
        return parts.joinToString(", ")
    }

    companion object {
        fun fmt(c: Double) = if (c % 1.0 == 0.0) c.toInt().toString() else c.toString()
    }
}

/** A preset saved on the Bluelink server (gtfvsttng) — the ones you make on mybluelink.ca. */
data class ServerPreset(val id: Int?, val isDefault: Boolean, val settings: ClimateSettings)

/**
 * One remote surround-view capture. The car uploads a single JPEG strip:
 * front, rear, left and right camera frames (each [frameWidth] x [frameHeight]) side by side,
 * followed by the top-down view ([topWidth] x [topHeight]). Layout from the official app 2.0.47.
 */
class SvmCapture(
    val utcTime: String?,
    val jpeg: ByteArray,
    val imageSize: List<Int>,
) {
    val frameWidth: Int? get() = imageSize.getOrNull(2)
    val frameHeight: Int? get() = imageSize.getOrNull(3)
    val topWidth: Int? get() = imageSize.getOrNull(4)
    val topHeight: Int? get() = imageSize.getOrNull(5)
}

/** Where the car was, as reported by evc/fme or fndmcr. [heading] in degrees from north. */
@Serializable
data class CarLocation(
    val lat: Double,
    val lon: Double,
    val heading: Double? = null,
    /** The car's own timestamp, if it sent one. */
    val time: String? = null,
    /** When the app asked (ms since epoch). */
    val fetchedAt: Long = 0,
)
