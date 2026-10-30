package io.github.alamo3.openblue.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatOptionsTest {
    @Test fun noRemoteSeatsMeansOnlyOff() {
        // A 2023 IONIQ 5 that reports 0 for every seat: Bluelink can't set them remotely.
        val caps = VehicleCaps(seatOptions = listOf(0, 0, 0, 0))
        assertFalse(caps.remoteSeats)
        assertEquals(listOf(SeatLevel.OFF), caps.seatCycle(0))
        val s = ClimateSettings().withSeat(0, SeatLevel.HEAT_HIGH.code).withSeat(1, SeatLevel.COOL_LOW.code)
        assertEquals(listOf(0, 0, 0, 0), s.forCar(caps).seats)
    }

    @Test fun levelsFollowTheOfficialApp() {
        val caps = VehicleCaps(seatOptions = listOf(6, 2, 1, 3))
        assertEquals(7, caps.seatLevels(0).size)
        assertEquals(setOf(0, 6, 7, 8), caps.seatLevels(1).map { it.code }.toSet())
        assertEquals(setOf(0, 6, 8), caps.seatLevels(2).map { it.code }.toSet())
        assertEquals(setOf(0, 3, 5), caps.seatLevels(3).map { it.code }.toSet())
        // A level the seat can't do is dropped; one it can is kept.
        val s = ClimateSettings().withSeat(2, SeatLevel.HEAT_MED.code).withSeat(1, SeatLevel.HEAT_MED.code)
        assertEquals(listOf(0, 7, 0, 0), s.forCar(caps).seats)
    }

    @Test fun unknownCapsAllowEverything() {
        assertTrue(VehicleCaps().remoteSeats)
    }
}
