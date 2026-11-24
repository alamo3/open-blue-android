package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiStatusTest {
    @Test fun nativePiWithThePlayersState() {
        // as virtual-car-sound's ble_control.py sends it with --native-sound
        val s = parsePiStatus("""{"v":"Hypercar","vol":0.6,"kmh":0.0,"info":"","car":false,"fx":true,"ss":false,"vg":true,"cq":true,"ih":0.0,"msg":"","nat":{"gears":true,"g":2,"rpm":3416,"kmh":40.5,"lim":0,"td":"hard to 100 km/h","ur":3,"cpu":1.6,"live":0}}""")
        assertNotNull(s)
        assertEquals("Hypercar", s!!.voice)
        assertEquals(0.6f, s.volume, 1e-6f)
        val n = s.native!!
        assertTrue(n.gears)
        assertEquals(2, n.gear)
        assertEquals(3416, n.rpm)
        assertEquals("hard to 100 km/h", n.testDrive)
        assertEquals(3, n.underruns)
        assertFalse(n.live)
    }

    @Test fun nativePiBeforeThePlayerReports() {
        val n = parsePiStatus("""{"v":"Flat-6","vol":1.0,"car":true,"msg":"","nat":{"gears":false}}""")!!.native!!
        assertFalse(n.gears)
        assertNull(n.gear)
        assertNull(n.testDrive)
    }

    @Test fun engineStateFromTheBluetoothService() {
        // the engine updating: the Bluetooth service reports systemd's state, no player state
        val s = parsePiStatus("""{"v":"Flat-6","vol":1.0,"car":false,"cq":true,"svc":"activating","msg":"The engine is starting (or updating)."}""")!!
        assertEquals("activating", s.engineState)
        assertNull(s.native)
        assertNull(parsePiStatus("""{"v":"Flat-6","vol":1.0,"car":true,"msg":""}""")!!.engineState)   // an older Pi
    }

    @Test fun dataOnlyPiHasNoRemote() {
        assertNull(parsePiStatus("""{"v":"Starship","vol":1.0,"car":true,"msg":""}""")!!.native)
        assertNull(parsePiStatus("not json"))
    }
}
