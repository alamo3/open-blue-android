package io.github.alamo3.openblue

import io.github.alamo3.openblue.api.VehicleCaps
import io.github.alamo3.openblue.api.VehicleInfo
import io.github.alamo3.openblue.data.SecureStore
import io.github.alamo3.openblue.widget.WidgetData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {
    @Test fun statusWritesReachObserversAcrossStores() {
        val prefs = MemoryPrefs()
        val appStore = SecureStore(prefs)
        val workerStore = SecureStore(prefs)
        val seen = mutableListOf<String?>()
        val stop = appStore.observeStatus { seen.add(it) }

        workerStore.widgetPending = "LOCK|1"
        workerStore.cachedStatus = "first"
        workerStore.cachedStatus = "second"
        assertEquals(listOf("first", "second"), seen)

        stop()
        workerStore.cachedStatus = "third"
        assertEquals(listOf("first", "second"), seen)
    }

    @Test fun widgetUsesLatestCachedStatusAndVehicleTemperatureTable() {
        val store = SecureStore(MemoryPrefs())
        store.username = "driver@example.com"
        store.vehicle = VehicleInfo("demo", "My car", "Sonata", "2024", "VIN", "G")
        store.caps = VehicleCaps(hvacTempType = 0)
        store.cachedStatus = """{"doorLock":false,"airCtrlOn":true,"airTemp":{"value":"0CH"},"evStatus":{"batteryStatus":60}}"""

        val before = WidgetData.fromStore(store, still = 0, now = 0)
        assertFalse(before.locked!!)
        assertTrue(before.climateOn)
        assertEquals(60, before.battery)
        assertTrue(before.details.any { it.text == "Climate on · 22°" })

        store.cachedStatus = """{"doorLock":true,"airCtrlOn":false,"evStatus":{"batteryStatus":72}}"""
        val after = WidgetData.fromStore(store, still = 0, now = 0)
        assertTrue(after.locked!!)
        assertFalse(after.climateOn)
        assertEquals(72, after.battery)
    }
}
