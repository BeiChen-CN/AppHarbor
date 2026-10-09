package com.app.market.data.remote.coolapk

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CoolapkSignerTest {
    @Test
    fun tokenMatchesV2ProtocolVector() {
        val device = "MDAxMTIyMzM0NDU1NjY3Nzg4OTlBQUJCQ0NEREVFRkY7IDsgOyAwMjowMDowMDowMDowMDowMDsgR29vZ2xlOyBHb29nbGU7IFBpeGVsIDVhOyBTUTFELjIyMDEwNS4wMDc="
        val headers = CoolapkSigner(device).headers(1700000000L)
        assertEquals(device, headers["X-App-Device"])
        assertEquals("v2JDJ5JDEwJE1UY3dNREF3TURBd01BLzNiZmMxM3VFRVJoeHVTdjJpSXVQdlY1aTNTMS44b2IybHMzOGUy", headers["X-App-Token"])
        assertEquals("12.4.2", headers["X-App-Version"])
        assertEquals("2208241", headers["X-App-Code"])
    }

    @Test
    fun deviceIsStablePerClientAndTokenChangesWithTime() {
        val signer = CoolapkSigner()
        val first = signer.headers(1700000000L)
        val later = signer.headers(1700000001L)
        assertEquals(first["X-App-Device"], later["X-App-Device"])
        assertNotEquals(first["X-App-Token"], later["X-App-Token"])
        assertTrue(Base64.decode(first.getValue("X-App-Device")).decodeToString().contains("Google; Google; Pixel 5a"))
    }
}
