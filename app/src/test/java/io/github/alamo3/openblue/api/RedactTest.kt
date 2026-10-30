package io.github.alamo3.openblue.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactTest {
    // Made-up data in the shape sltvhcl returns.
    private val raw = """
        {"vehicle":{"vehicleId":"AbC123==","nickName":"Omar's car","vin":"KM8KRDAF0PU000001","modelName":"IONIQ 5 EV","modelYear":"2023",
          "photoUrl":"https://example.com/p.jpg","genType":"G3"},
         "features":{"seatHeatVent":{"drvSeatHeatOption":0},"hvacTempType":1},
         "featuresModel":{"features":[{"category":"Menu","features":[{"featureName":"Home"}]}]},
         "status":{"vehicleLocation":{"coord":{"lat":43.65,"lon":-79.38,"alt":90},"heading":10},
           "note":"VIN KM8KRDAF0PU000001 seen","doorLock":true}}
    """.trimIndent()

    @Test fun removesWhatIdentifiesTheCar() {
        val out = Redact.json(Json.parseToJsonElement(raw)).toString()
        for (s in listOf("AbC123", "Omar", "000001", "43.65", "-79.38", "example.com")) assertFalse("$s left in $out", s in out)
        val v = Json.parseToJsonElement(out).jsonObject["vehicle"]!!.jsonObject
        assertEquals("\"KM8KRDAF0PU******\"", v["vin"].toString())
        // What helps debugging stays.
        for (s in listOf("IONIQ 5 EV", "2023", "G3", "drvSeatHeatOption", "featureName", "doorLock")) assert(s in out) { s }
    }
}
