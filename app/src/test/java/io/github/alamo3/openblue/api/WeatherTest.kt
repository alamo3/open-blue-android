package io.github.alamo3.openblue.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    private val reply = """
        {"latitude":43.65,"longitude":-79.38,"current_units":{"temperature_2m":"°C"},
         "current":{"time":"2026-10-28T20:00","interval":900,"temperature_2m":7.4,"apparent_temperature":4.1,"weather_code":3,"is_day":0}}
    """.trimIndent()

    @Test fun parsesOpenMeteo() {
        val w = WeatherClient().parse(reply, now = 1000)
        assertEquals(7.4, w.tempC, 1e-9)
        assertEquals(4.1, w.feelsLikeC!!, 1e-9)
        assertEquals(3, w.code)
        assertEquals(false, w.isDay)
        assertEquals("Cloudy", w.summary)
        assertEquals(1000, w.fetchedAt)
    }

    private fun weather(t: Double) = Weather(t, t, 0, true, 0)
    private val heat = SeatLevel.HEAT_HIGH.code
    private val cool = SeatLevel.COOL_LOW.code

    @Test fun warnsAboutSeatsThatDontSuitTheWeather() {
        assertNotNull(SeatWeather.warning(listOf(heat, 0, 0, 0), weather(26.0)))
        assertNotNull(SeatWeather.warning(listOf(0, cool, 0, 0), weather(4.0)))
        assertTrue(SeatWeather.warning(listOf(heat, heat, 0, 0), weather(26.0))!!.contains("2 seats"))
        // Suits the weather, mild, no seats, or no weather: nothing to say.
        assertNull(SeatWeather.warning(listOf(heat, 0, 0, 0), weather(3.0)))
        assertNull(SeatWeather.warning(listOf(0, cool, 0, 0), weather(28.0)))
        assertNull(SeatWeather.warning(listOf(heat, cool, 0, 0), weather(16.0)))
        assertNull(SeatWeather.warning(listOf(0, 0, 0, 0), weather(30.0)))
        assertNull(SeatWeather.warning(listOf(heat, 0, 0, 0), null))
    }

    @Test fun turnsOffOnlyTheMismatchedSeats() {
        val s = ClimateSettings(driverSeat = heat, passengerSeat = cool)
        val warm = s.withoutMismatchedSeats(weather(27.0))
        assertEquals(listOf(0, cool, 0, 0), warm.seats)
        val cold = s.withoutMismatchedSeats(weather(2.0))
        assertEquals(listOf(heat, 0, 0, 0), cold.seats)
    }
}
