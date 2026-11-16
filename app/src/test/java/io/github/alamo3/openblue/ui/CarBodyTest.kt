package io.github.alamo3.openblue.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CarBodyTest {
    @Test fun picksDedicatedElectricModelsAndKeepsGasKonaGeneric() {
        assertEquals(CarBody.IONIQ5_STANDARD, CarBody.forModel("IONIQ 5 EV"))
        assertEquals(CarBody.IONIQ5, CarBody.forModel("IONIQ 5 N"))
        assertEquals(CarBody.IONIQ5, CarBody.forModel("IONIQ 5 EV", "E", "N"))
        assertEquals(CarBody.IONIQ5_STANDARD, CarBody.forModel("IONIQ 5 EV", "E", "N Line"))
        assertEquals(CarBody.IONIQ5_STANDARD, CarBody.forModel("IONIQ 5 N-Line"))
        assertEquals(CarBody.IONIQ9, CarBody.forModel("IONIQ 9"))
        assertEquals(CarBody.IONIQ9, CarBody.forModel("Ioniq9 EV"))
        assertEquals(CarBody.IONIQ6, CarBody.forModel("IONIQ 6 EV"))
        assertEquals(CarBody.KONA_ELECTRIC, CarBody.forModel("Kona Electric"))
        assertEquals(CarBody.KONA_ELECTRIC, CarBody.forModel("KONA", "E"))
        assertEquals(CarBody.KONA, CarBody.forModel("KONA", "G"))
        assertEquals(CarBody.SANTA_FE, CarBody.forModel("Santa Fe"))
        assertEquals(CarBody.ELANTRA_N, CarBody.forModel("Elantra N"))
        assertEquals(CarBody.ELANTRA, CarBody.forModel("Elantra N-Line"))
        assertEquals(CarBody.PALISADE, CarBody.forModel("Palisade"))
        assertEquals(CarBody.SONATA, CarBody.forModel("Sonata"))
        assertEquals(CarBody.TUCSON, CarBody.forModel("Tucson"))
        assertEquals(CarBody.VENUE, CarBody.forModel("Venue"))
    }
}
