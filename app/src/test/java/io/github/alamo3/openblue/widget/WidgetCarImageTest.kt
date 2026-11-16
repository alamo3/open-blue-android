package io.github.alamo3.openblue.widget

import io.github.alamo3.openblue.ui.CarPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCarImageTest {
    @Test fun recolorsPaintWithoutChangingOtherPartsOrTransparency() {
        val silver = 0xffb0b0b0.toInt()
        assertEquals(silver, WidgetCarImage.recolorPixel(silver, 0, CarPaint.BLACK.swatch))
        assertEquals(0x00203040, WidgetCarImage.recolorPixel(0x00203040, 255, CarPaint.BLACK.swatch))
        val black = WidgetCarImage.recolorPixel(silver, 255, CarPaint.BLACK.swatch)
        assertEquals(0xff, black ushr 24)
        assertTrue((black and 0xff) < (silver and 0xff))
        val edge = WidgetCarImage.recolorPixel(silver, 128, CarPaint.BLACK.swatch)
        assertTrue((edge and 0xff) > (black and 0xff))
        assertTrue((edge and 0xff) < (silver and 0xff))
    }

    @Test fun paintKeepsItsColorChannels() {
        val blue = WidgetCarImage.recolorPixel(0xffc0c0c0.toInt(), 255, CarPaint.BLUE.swatch)
        assertTrue((blue and 0xff) > ((blue ushr 16) and 0xff))
    }
}
