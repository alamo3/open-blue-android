package io.github.alamo3.openblue.work

import io.github.alamo3.openblue.ui.CarStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class AlertsTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    private fun status(
        battery: Int? = 60,
        charging: Boolean = false,
        plugged: Boolean = false,
        limit: Int? = 80,
        locked: Boolean? = true,
        doors: List<String> = emptyList(),
        trunk: Boolean = false,
        v12: Int? = 80,
        tires: Boolean = false,
        at: Long = t0,
    ) = CarStatus(
        battery = battery, range = 300, rangeUnit = "km", charging = charging, pluggedIn = plugged,
        chargeMinutesLeft = null, chargeLimitAc = limit, chargeLimitDc = limit, chargePortOpen = null,
        preconditioning = null, locked = locked, openDoors = doors, openWindows = emptyList(),
        trunkOpen = trunk, frunkOpen = false, climateOn = false, setTempC = null, defrost = false,
        wheelHeat = false, rearHeat = false, seats = listOf(null, null, null, null), battery12v = v12,
        tireWarning = tires, updatedAt = Date(at),
    )

    private fun run(vararg steps: Pair<CarStatus, Long>): List<List<Alert>> {
        var mem = WatchMemory()
        return steps.map { (st, now) ->
            val (next, alerts) = Alerts.evaluate(mem, st, now, "Car")
            mem = next
            alerts
        }
    }

    @Test fun firstCheckNeverAlertsAboutCharging() {
        val out = run(status(charging = false, plugged = true, battery = 80) to t0)
        assertTrue(out[0].isEmpty())
    }

    @Test fun chargingFinishedAtLimit() {
        val out = run(
            status(charging = true, plugged = true, battery = 70) to t0,
            status(charging = false, plugged = true, battery = 80) to t0 + 30 * min,
            status(charging = false, plugged = true, battery = 80) to t0 + 60 * min,
        )
        assertEquals(listOf("Car finished charging"), out[1].map { it.title })
        assertTrue("fires once", out[2].isEmpty())
    }

    @Test fun chargingStoppedEarly() {
        val out = run(
            status(charging = true, plugged = true, battery = 40) to t0,
            status(charging = false, plugged = true, battery = 55) to t0 + 30 * min,
        )
        assertEquals("Car stopped charging", out[1].single().title)
        assertTrue(out[1].single().text.contains("80%"))
    }

    @Test fun unplugged() {
        val out = run(
            status(charging = true, plugged = true, battery = 40) to t0,
            status(charging = false, plugged = false, battery = 45) to t0 + 30 * min,
        )
        assertEquals("Car was unplugged", out[1].single().title)
    }

    @Test fun leftUnlockedAlertsAfterGraceOnce() {
        val out = run(
            status(locked = false, at = t0) to t0 + 1 * min,
            status(locked = false, at = t0) to t0 + 15 * min,
            status(locked = false, at = t0) to t0 + 45 * min,
            status(locked = true, at = t0 + 50 * min) to t0 + 60 * min,
            status(locked = false, trunk = true, at = t0 + 70 * min) to t0 + 71 * min,
        )
        assertTrue(out[0].isEmpty())
        assertEquals("Car is unlocked", out[1].single().title)
        assertTrue(out[2].isEmpty())
        assertTrue(out[3].isEmpty())
        assertTrue("new occurrence waits for the grace period again", out[4].isEmpty())
    }

    @Test fun oldReportOfOpenDoorAlertsStraightAway() {
        val out = run(status(doors = listOf("rear left"), at = t0) to t0 + 40 * min)
        assertEquals("Car is open", out[0].single().title)
        assertEquals("Rear left door open.", out[0].single().text)
    }

    @Test fun lowTwelveVoltWithHysteresis() {
        val out = run(
            status(v12 = 45) to t0,
            status(v12 = 38) to t0 + 30 * min,
            status(v12 = 42) to t0 + 60 * min,
            status(v12 = 39) to t0 + 90 * min,
            status(v12 = 60) to t0 + 120 * min,
            status(v12 = 35) to t0 + 150 * min,
        )
        assertEquals(listOf(0, 1, 0, 0, 0, 1), out.map { it.size })
    }

    @Test fun tireWarningOnceAndSwitchable() {
        val out = run(status(tires = true) to t0, status(tires = true) to t0 + 30 * min)
        assertEquals(listOf(1, 0), out.map { it.size })
        val (_, off) = Alerts.evaluate(WatchMemory(), status(tires = true), t0, "Car") { it != AlertKind.TIRES }
        assertTrue(off.isEmpty())
    }
}
