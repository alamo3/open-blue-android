package io.github.alamo3.openblue.api

/**
 * The HVAC set-point is sent as a hex index ("0EH"), not a temperature.
 * Which table applies depends on the vehicle's hvacTempType. These tables and
 * the selection logic are copied from the official Bluelink Canada app (2.0.47).
 */
object TempTables {
    data class Entry(val hex: String, val fahrenheit: String, val celsius: String) {
        val celsiusValue: Double? get() = celsius.toDoubleOrNull()
        val isLimit: Boolean get() = celsius == "LO" || celsius == "HI"
        val label: String get() = if (isLimit) celsius else "$celsius °C"
    }

    private fun parse(s: String) = s.split(',').map {
        val (h, f, c) = it.split(':')
        Entry(h, f, c)
    }

    private val TYPE_0 = parse("02H:62:17,03H:63:17.5,04H:63:18,05H:64:18.5,06H:65:19,07H:66:19.5,08H:67:20,09H:68:20.5,0AH:69:21,0BH:70:21.5,0CH:71:22,0DH:72:22.5,0EH:73:23,0FH:74:23.5,10H:75:24,11H:76:24.5,12H:77:25,13H:78:25.5,14H:79:26,15H:80:26.5,16H:81:27,17H:82:27.5,18H:83:28,19H:84:28.5,1AH:85:29,1BH:86:29.5,1CH:87:30,1DH:88:30.5,1EH:89:31,1FH:89:31.5,20H:90:32")
    private val TYPE_1 = parse("01H:LO:LO,06H:62:17,07H:63:17.5,08H:64:18,09H:65:18.5,0AH:66:19,0BH:67:19.5,0CH:68:20,0DH:69:20.5,0EH:70:21,0FH:71:21.5,10H:72:22,11H:73:22.5,12H:74:23,13H:75:23.5,14H:76:24,15H:77:24.5,16H:78:25,17H:79:25.5,18H:80:26,19H:81:26.5,1AH:82:27,FEH:HI:HI")
    private val TYPE_OTHER = parse("02H:LO:LO,03H:63:17.5,04H:64:18,05H:65:18.5,06H:66:19,07H:67:19.5,08H:68:20,09H:69:20.5,0AH:70:21,0BH:71:21.5,0CH:72:22,0DH:73:22.5,0EH:74:23,0FH:75:23.5,10H:76:24,11H:77:24.5,12H:78:25,13H:79:25.5,14H:80:26,15H:81:26.5,16H:82:27,17H:83:27.5,18H:84:28,19H:85:28.5,1AH:86:29,1BH:87:29.5,1CH:88:30,1DH:89:30.5,1EH:90:31,1FH:91:31.5,20H:HI:HI")
    private val TYPE_OTHER_IONIQ = parse("02H:62:17,03H:63:17.5,04H:64:18,05H:65:18.5,06H:66:19,07H:67:19.5,08H:68:20,09H:69:20.5,0AH:70:21,0BH:71:21.5,0CH:72:22,0DH:73:22.5,0EH:74:23,0FH:75:23.5,10H:76:24,11H:77:24.5,12H:78:25,13H:79:25.5,14H:80:26,15H:81:26.5,16H:82:27,17H:83:27.5,18H:84:28,19H:85:28.5,1AH:86:29,1BH:87:29.5,1CH:88:30,1DH:89:30.5,1EH:90:31,1FH:91:31.5,20H:92:32")

    /** Mirrors ab.b.a() in the official app. */
    fun forVehicle(hvacTempType: Int?, modelName: String, fuelKindCode: String): List<Entry> = when {
        hvacTempType == null || hvacTempType == 0 -> TYPE_0
        hvacTempType == 1 -> if (fuelKindCode.equals("G", true)) TYPE_1 else TYPE_1.filterNot { it.isLimit }
        modelName == "IONIQ EV" -> TYPE_OTHER_IONIQ
        else -> TYPE_OTHER
    }

    /** Decode a hex set-point, preferring the vehicle's own table and falling back to the others. */
    fun celsiusForHex(table: List<Entry>, hex: String?): Double? =
        table.firstOrNull { it.hex.equals(hex, true) }?.celsiusValue
            ?: listOf(TYPE_1, TYPE_0, TYPE_OTHER).firstNotNullOfOrNull { t -> t.firstOrNull { it.hex.equals(hex, true) }?.celsiusValue }

    fun nearest(table: List<Entry>, celsius: Double): Entry {
        val numeric = table.filterNot { it.isLimit }
        return numeric.minByOrNull { kotlin.math.abs((it.celsiusValue ?: 0.0) - celsius) } ?: table.first()
    }
}
