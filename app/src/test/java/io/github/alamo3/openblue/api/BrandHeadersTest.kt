package io.github.alamo3.openblue.api

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandHeadersTest {
    private fun headers(brand: Brand) = Request.Builder()
        .url(brand.baseUrl + "v2/login")
        .brandHeaders(brand, "-5")
        .build().headers

    @Test fun kiaUsesPortalProfile() {
        val h = headers(Brand.KIA)
        assertEquals("CWP", h["from"])
        assertEquals("https://kiaconnect.ca", h["Origin"])
        assertEquals("https://kiaconnect.ca/login", h["Referer"])
        assertEquals("application/json, text/plain, */*", h["Accept"])
        assertEquals(true, h["User-Agent"]?.startsWith("Mozilla/5.0"))
        assertEquals(Brand.KIA.clientId, h["client_id"])
        assertNull(h["brand"])
    }

    @Test fun hyundaiKeepsMobileProfile() {
        val h = headers(Brand.HYUNDAI)
        assertEquals("SPA", h["from"])
        assertEquals("H", h["brand"])
        assertEquals("-5", h["offset"])
        assertNull(h["Origin"])
        assertNull(h["Referer"])
    }
}
