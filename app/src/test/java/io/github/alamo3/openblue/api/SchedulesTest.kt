package io.github.alamo3.openblue.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulesTest {
    private fun time(h: Int, m: Int) = TimeOfDay(h, m).toJson()

    @Test fun timesAreTwelveHourWithASection() {
        assertEquals("1230", time(0, 30).str("time")); assertEquals(0, time(0, 30).int("timeSection"))
        assertEquals("1200", time(12, 0).str("time")); assertEquals(1, time(12, 0).int("timeSection"))
        assertEquals("0745", time(7, 45).str("time")); assertEquals(0, time(7, 45).int("timeSection"))
        assertEquals("1105", time(23, 5).str("time")); assertEquals(1, time(23, 5).int("timeSection"))
    }

    @Test fun timesRoundTrip() {
        for (h in 0..23) for (m in listOf(0, 5, 30, 59)) {
            assertEquals(TimeOfDay(h, m), TimeOfDay.fromJson(time(h, m)))
        }
        assertEquals(TimeOfDay(6, 30), TimeOfDay.fromJson(Json.parseToJsonElement("""{"time":"6:30","timeSection":0}""")))
        assertEquals(null, TimeOfDay.fromJson(Json.parseToJsonElement("""{"time":""}""")))
    }

    @Test fun climateSlotMatchesTheOfficialRequest() {
        val slot = ClimateSlot(on = true, days = setOf(1, 3), time = TimeOfDay(7, 30), tempC = 21.0, defrost = true, heating = 1)
        val j = slot.toJson("0AH")
        assertEquals(1, j.int("reservHVACflag"))
        assertEquals(listOf("1", "3"), j.arr("reservInfo.day").map { it.toString() })
        assertEquals("0730", j.str("reservInfo.time.time"))
        assertEquals("0AH", j.str("reservHVACSet.airTemp.value"))
        assertEquals(0, j.int("reservHVACSet.airTemp.unit"))
        assertEquals(true, j.bool("reservHVACSet.defrost"))
    }

    @Test fun noDaysSendsTheOfficialAppsNine() {
        val j = ClimateSlot(on = true).toJson("0AH")
        assertEquals(listOf("9"), j.arr("reservInfo.day").map { it.toString() })
        // And reading a 9 back means "no day picked".
        val table = TempTables.forVehicle(1, "IONIQ 5", "E")
        assertTrue(ClimateSlot.fromJson(j, table)!!.days.isEmpty())
    }

    @Test fun readsTheServersSchedule() {
        val table = TempTables.forVehicle(1, "IONIQ 5", "E")
        val result = Json.parseToJsonElement(
            """{"reservedHVACInfo1":{"reservHVACflag":1,"reservInfo":{"day":[1,2,3,4,5],"time":{"time":"0715","timeSection":0}},
               "reservHVACSet":{"airCtrl":1,"defrost":false,"heating1":0,"airTemp":{"value":"0EH","unit":0}}}}""",
        )
        val s = ClimateSchedule.fromJson(result, table)
        assertTrue(s.first.on)
        assertEquals(setOf(1, 2, 3, 4, 5), s.first.days)
        assertEquals(TimeOfDay(7, 15), s.first.time)
        assertEquals(21.0, s.first.tempC, 0.01)
        assertEquals("Weekdays", daysLabel(s.first.days))
        assertEquals(false, s.second.on)
    }

    @Test fun chargeWindowRoundTrips() {
        val w = ChargeWindow(on = true, start = TimeOfDay(23, 0), end = TimeOfDay(6, 30), offPeak = true)
        val j = w.toJson("1234")
        assertEquals(1, j.int("reservFlag"))
        assertEquals("1100", j.str("reservStartTime.time")); assertEquals(1, j.int("reservStartTime.timeSection"))
        assertEquals("0630", j.str("reservEndTime.time")); assertEquals(0, j.int("reservEndTime.timeSection"))
        assertEquals(w, ChargeWindow.fromJson(Json.parseToJsonElement(j.toString()).jsonObject))
    }
}
