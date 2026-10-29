package io.github.alamo3.openblue.debug

import io.github.alamo3.openblue.api.TempTables
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer mode: a simulated car the whole UI can be driven from. When [simulate] is on, the
 * app reads [toStatusJson] instead of Bluelink's status; it has the same shape as a real response.
 * With [dryRun] on, commands play out their progress without contacting the car and update this state.
 */
data class DebugState(
    val simulate: Boolean = false,
    val dryRun: Boolean = false,
    /** front left, front right, rear left, rear right */
    val doors: List<Boolean> = listOf(false, false, false, false),
    val frunk: Boolean = false,
    val trunk: Boolean = false,
    val windowsOpen: Boolean = false,
    val locked: Boolean = true,
    val climateOn: Boolean = false,
    val tempC: Double = 21.0,
    val defrost: Boolean = false,
    val wheelHeat: Boolean = false,
    /** Raw seat state codes: drv, ast, rl, rr (0/2 off, 3-5 cool, 6-8 heat). */
    val seats: List<Int> = listOf(0, 0, 0, 0),
    val battery: Int = 62,
    val charging: Boolean = false,
    val pluggedIn: Boolean = false,
    val chargeMinutes: Int = 95,
    val chargePortOpen: Boolean = false,
    val preconditioning: Boolean = false,
    val limitAc: Int = 80,
    val limitDc: Int = 90,
    val battery12v: Int = 89,
    val tireWarning: Boolean = false,
) {
    fun withDoor(i: Int, open: Boolean) = copy(doors = doors.toMutableList().also { it[i] = open })
    fun withSeat(i: Int, code: Int) = copy(seats = seats.toMutableList().also { it[i] = code })

    fun toStatusJson(table: List<TempTables.Entry>): JsonObject = buildJsonObject {
        fun flag(b: Boolean) = if (b) 1 else 0
        put("lastStatusDate", SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date()))
        put("airCtrlOn", climateOn)
        put("doorLock", locked)
        putJsonObject("doorOpen") {
            put("frontLeft", flag(doors[0])); put("frontRight", flag(doors[1]))
            put("backLeft", flag(doors[2])); put("backRight", flag(doors[3]))
        }
        putJsonObject("windowOpen") {
            val w = flag(windowsOpen)
            put("frontLeft", w); put("frontRight", w); put("backLeft", w); put("backRight", w)
        }
        put("trunkOpen", trunk)
        put("hoodOpen", frunk)
        put("defrost", defrost)
        putJsonObject("airTemp") {
            put("value", if (climateOn) TempTables.nearest(table, tempC).hex else "OFF")
            put("unit", 0)
        }
        put("steerWheelHeat", flag(wheelHeat))
        put("sideBackWindowHeat", flag(wheelHeat))
        putJsonObject("evStatus") {
            put("batteryCharge", charging)
            put("batteryStatus", battery)
            put("batteryPlugin", if (pluggedIn || charging) 1 else 0)
            put("chargePortDoorOpenStatus", if (chargePortOpen || pluggedIn || charging) 1 else 2)
            put("batteryPreconditiong", preconditioning)
            putJsonObject("remainTime2") { putJsonObject("atc") { put("value", if (charging) chargeMinutes else 0); put("unit", 1) } }
            putJsonArray("drvDistance") {
                add(buildJsonObject {
                    putJsonObject("rangeByFuel") {
                        putJsonObject("totalAvailableRange") { put("value", battery * 5.0); put("unit", 1) }
                        putJsonObject("evModeRange") { put("value", battery * 5.0); put("unit", 1) }
                    }
                    put("type", 2)
                })
            }
            putJsonObject("reservChargeInfos") {
                putJsonArray("targetSOClist") {
                    add(buildJsonObject { put("plugType", 0); put("targetSOClevel", limitDc) })
                    add(buildJsonObject { put("plugType", 1); put("targetSOClevel", limitAc) })
                }
            }
        }
        putJsonObject("battery") { put("batSoc", battery12v) }
        putJsonObject("tirePressureLamp") { put("tirePressureLampAll", flag(tireWarning)) }
        putJsonObject("seatHeaterVentInfo") {
            put("drvSeatHeatState", seats[0]); put("astSeatHeatState", seats[1])
            put("rlSeatHeatState", seats[2]); put("rrSeatHeatState", seats[3])
        }
    }
}
