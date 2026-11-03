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

    @Test fun tryingSeatsAnywayHeatsTheSeatsBluelinkSaysItCant() {
        // A 2024 IONIQ 6 reporting 0 for every seat, with Try seat heating anyway on.
        val real = VehicleCaps(seatOptions = listOf(0, 0, 0, 0))
        val caps = real.trialSeats(true)
        assertTrue(caps.remoteSeats)
        assertEquals(setOf(0, 6, 7, 8), caps.seatLevels(0).map { it.code }.toSet())
        assertEquals(setOf(0, 6, 7, 8), caps.seatLevels(1).map { it.code }.toSet())
        assertEquals(setOf(0, 6, 8), caps.seatLevels(2).map { it.code }.toSet())
        assertEquals(setOf(0, 6, 8), caps.seatLevels(3).map { it.code }.toSet())
        // Heat goes out; cooling isn't tried.
        val s = ClimateSettings().withSeat(0, SeatLevel.HEAT_HIGH.code).withSeat(1, SeatLevel.COOL_LOW.code).withSeat(2, SeatLevel.HEAT_LOW.code)
        val sent = s.forCar(caps)
        assertEquals(listOf(8, 0, 6, 0), sent.seats)
        // The real caps know those seats were a try, which is when a refusal means "without the seats".
        assertTrue(real.triesUnreportedSeats(sent))
        assertFalse(real.triesUnreportedSeats(sent.withoutSeats()))
        assertEquals(listOf(0, 0, 0, 0), sent.withoutSeats().seats)
    }

    @Test fun tryingSeatsLeavesTheRestAlone() {
        val zeros = VehicleCaps(seatOptions = listOf(0, 0, 0, 0))
        assertEquals(zeros, zeros.trialSeats(false))
        // Seats Bluelink does report (or hasn't yet) keep their own levels.
        val mixed = VehicleCaps(seatOptions = listOf(6, 2, 0, null)).trialSeats(true)
        assertEquals(7, mixed.seatLevels(0).size)
        assertEquals(setOf(0, 6, 7, 8), mixed.seatLevels(1).map { it.code }.toSet())
        assertEquals(setOf(0, 6, 8), mixed.seatLevels(2).map { it.code }.toSet())
        assertEquals(7, mixed.seatLevels(3).size)
        val supported = VehicleCaps(seatOptions = listOf(6, 6, 1, 1))
        assertEquals(supported, supported.trialSeats(true))
        assertFalse(supported.triesUnreportedSeats(ClimateSettings().withSeat(0, SeatLevel.HEAT_HIGH.code)))
    }
}
