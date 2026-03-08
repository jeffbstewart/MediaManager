package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpcItemDbLookupServiceTest {

    private val service = UpcItemDbLookupService()

    // --- parseResponse: HTTP status handling ---

    @Test
    fun `400 returns not found without api error`() {
        val result = service.parseResponse(400, "Not a valid UPC code.")
        assertFalse(result.found)
        assertFalse(result.apiError)
    }

    @Test
    fun `404 returns not found without api error`() {
        val result = service.parseResponse(404, "")
        assertFalse(result.found)
        assertFalse(result.apiError)
    }

    @Test
    fun `429 returns api error for rate limiting`() {
        val result = service.parseResponse(429, "")
        assertFalse(result.found)
        assertTrue(result.apiError)
        assertEquals("Rate limited (429)", result.errorMessage)
    }

    @Test
    fun `500 returns api error`() {
        val result = service.parseResponse(500, "")
        assertFalse(result.found)
        assertTrue(result.apiError)
        assertEquals("HTTP 500", result.errorMessage)
    }

    @Test
    fun `401 returns api error`() {
        val result = service.parseResponse(401, "")
        assertFalse(result.found)
        assertTrue(result.apiError)
        assertEquals("HTTP 401", result.errorMessage)
    }

    // --- parseResponse: 200 success body parsing ---

    @Test
    fun `200 with valid item returns found`() {
        val body = """
        {
            "code": "OK",
            "total": 1,
            "offset": 0,
            "items": [{
                "ean": "0883929301843",
                "title": "The Dark Knight (Blu-ray)",
                "upc": "883929301843",
                "brand": "Warner Bros",
                "description": "Batman faces the Joker",
                "category": "Movies > Blu-ray"
            }]
        }
        """.trimIndent()

        val result = service.parseResponse(200, body)
        assertTrue(result.found)
        assertFalse(result.apiError)
        assertEquals("The Dark Knight (Blu-ray)", result.productName)
        assertEquals("Warner Bros", result.brand)
        assertEquals("Batman faces the Joker", result.description)
        assertEquals("BLURAY", result.mediaFormat)
        assertNotNull(result.rawJson)
    }

    @Test
    fun `200 with empty items array returns not found`() {
        val body = """{"code": "OK", "total": 0, "offset": 0, "items": []}"""
        val result = service.parseResponse(200, body)
        assertFalse(result.found)
        assertFalse(result.apiError)
    }

    @Test
    fun `200 with missing items field returns not found`() {
        val body = """{"code": "OK", "total": 0, "offset": 0}"""
        val result = service.parseResponse(200, body)
        assertFalse(result.found)
        assertFalse(result.apiError)
    }

    @Test
    fun `200 with malformed json returns api error`() {
        val result = service.parseResponse(200, "not json at all")
        assertFalse(result.found)
        assertTrue(result.apiError)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage.orEmpty().startsWith("JSON parse error"))
    }

    @Test
    fun `200 with null fields in item still parses`() {
        val body = """
        {
            "code": "OK",
            "total": 1,
            "offset": 0,
            "items": [{
                "ean": "0000000000000",
                "title": "Some Product",
                "brand": null,
                "description": null,
                "category": null
            }]
        }
        """.trimIndent()

        val result = service.parseResponse(200, body)
        assertTrue(result.found)
        assertEquals("Some Product", result.productName)
        assertNull(result.brand)
        assertNull(result.description)
        assertNull(result.mediaFormat)
    }

    @Test
    fun `200 with blank fields treated as null`() {
        val body = """
        {
            "code": "OK",
            "total": 1,
            "offset": 0,
            "items": [{
                "ean": "0000000000000",
                "title": "A Title",
                "brand": "",
                "description": "   ",
                "category": ""
            }]
        }
        """.trimIndent()

        val result = service.parseResponse(200, body)
        assertTrue(result.found)
        assertEquals("A Title", result.productName)
        assertNull(result.brand)
        assertNull(result.description)
    }

    // --- detectMediaFormat ---

    @Test
    fun `detects UHD from 4K UHD in title`() {
        assertEquals("UHD_BLURAY", service.detectMediaFormat("Movie Title 4K UHD", null, null))
    }

    @Test
    fun `detects UHD from Ultra HD in category`() {
        assertEquals("UHD_BLURAY", service.detectMediaFormat(null, "Movies > Ultra HD", null))
    }

    @Test
    fun `detects UHD from uhd in description`() {
        assertEquals("UHD_BLURAY", service.detectMediaFormat(null, null, "Available in UHD"))
    }

    @Test
    fun `detects HD DVD`() {
        assertEquals("HD_DVD", service.detectMediaFormat("Movie Title [HD DVD]", null, null))
    }

    @Test
    fun `detects HD-DVD with hyphen`() {
        assertEquals("HD_DVD", service.detectMediaFormat(null, "HD-DVD", null))
    }

    @Test
    fun `detects Blu-ray with hyphen`() {
        assertEquals("BLURAY", service.detectMediaFormat("The Matrix (Blu-ray)", null, null))
    }

    @Test
    fun `detects bluray without hyphen`() {
        assertEquals("BLURAY", service.detectMediaFormat(null, "Bluray Disc", null))
    }

    @Test
    fun `detects blu ray with space`() {
        assertEquals("BLURAY", service.detectMediaFormat(null, null, "Available on Blu Ray"))
    }

    @Test
    fun `detects DVD`() {
        assertEquals("DVD", service.detectMediaFormat("Jurassic Park (DVD)", null, null))
    }

    @Test
    fun `returns null when no format keywords found`() {
        assertNull(service.detectMediaFormat("Some Random Product", "Electronics", "A gadget"))
    }

    @Test
    fun `returns null for all null fields`() {
        assertNull(service.detectMediaFormat(null, null, null))
    }

    @Test
    fun `UHD takes priority over Blu-ray`() {
        assertEquals("UHD_BLURAY", service.detectMediaFormat("Movie 4K UHD Blu-ray", null, null))
    }

    @Test
    fun `HD DVD takes priority over DVD`() {
        assertEquals("HD_DVD", service.detectMediaFormat("Movie [HD DVD]", null, null))
    }

    @Test
    fun `case insensitive matching`() {
        assertEquals("BLURAY", service.detectMediaFormat("BLU-RAY edition", null, null))
        assertEquals("DVD", service.detectMediaFormat(null, null, "dvd"))
        assertEquals("UHD_BLURAY", service.detectMediaFormat("4k uhd", null, null))
    }
}

class MockUpcLookupServiceTest {

    private val service = MockUpcLookupService()

    @Test
    fun `returns found for each last digit 0 through 9`() {
        for (digit in '0'..'9') {
            val penultimate = (digit.digitToInt() + 1) % 10
            val upc = "12345678${penultimate}${digit}" // second-to-last always differs from last
            val result = service.lookup(upc)
            assertTrue(result.found, "Expected found for UPC ending in $digit (upc=$upc)")
            assertNotNull(result.productName)
            assertNotNull(result.brand)
            assertNotNull(result.rawJson)
        }
    }

    @Test
    fun `returns not found when last two digits match`() {
        val result = service.lookup("123456788") // ends in "88"
        assertFalse(result.found)
    }

    @Test
    fun `returns correct product for last digit 0`() {
        val result = service.lookup("123456780")
        assertTrue(result.found)
        assertEquals("The Shawshank Redemption", result.productName)
        assertEquals("Warner Bros", result.brand)
        assertEquals("BLURAY", result.mediaFormat)
        assertEquals(1994, result.releaseYear)
    }

    @Test
    fun `returns correct product for last digit 7`() {
        val result = service.lookup("123456787")
        assertTrue(result.found)
        assertEquals("The Godfather", result.productName)
        assertEquals("Paramount", result.brand)
        assertEquals("UHD_BLURAY", result.mediaFormat)
        assertEquals(1972, result.releaseYear)
    }

    @Test
    fun `never returns api error`() {
        for (digit in '0'..'9') {
            val penultimate = (digit.digitToInt() + 1) % 10
            val result = service.lookup("12345678${penultimate}${digit}")
            assertFalse(result.apiError)
        }
    }
}
