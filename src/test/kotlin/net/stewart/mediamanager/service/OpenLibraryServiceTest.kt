package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenLibraryServiceTest {

    @Test
    fun `parseSeriesLine pulls name and number`() {
        assertEquals("Foundation" to java.math.BigDecimal("1"),
            parseSeriesLine("Foundation #1"))
        assertEquals("The Foundation Series" to java.math.BigDecimal("2"),
            parseSeriesLine("The Foundation Series, #2"))
        assertEquals("Dune" to java.math.BigDecimal("3"),
            parseSeriesLine("Dune (Book 3)"))
        assertEquals("Standalone" to null,
            parseSeriesLine("Standalone"))
        assertEquals("Wheel of Time" to java.math.BigDecimal("0.5"),
            parseSeriesLine("Wheel of Time #0.5"))
    }

    @Test
    fun `parseSeries dedupes by normalized name`() {
        val json = """["Foundation #1", "foundation #1", "Empire Saga #2"]"""
        val node = ObjectMapper().readTree(json)
        val parsed = parseSeries(node)
        assertEquals(2, parsed.size)
        assertEquals("Foundation", parsed[0].name)
        assertEquals("Empire Saga", parsed[1].name)
    }

    @Test
    fun `parseSeries handles null and non-array`() {
        assertTrue(parseSeries(null).isEmpty())
        assertTrue(parseSeries(ObjectMapper().readTree("""{"not":"an array"}""")).isEmpty())
    }

    @Test
    fun `mapPhysicalFormat recognizes common variants`() {
        assertEquals("MASS_MARKET_PAPERBACK", mapPhysicalFormat("Mass Market Paperback"))
        assertEquals("MASS_MARKET_PAPERBACK", mapPhysicalFormat("mass-market paperback"))
        assertEquals("HARDBACK", mapPhysicalFormat("Hardcover"))
        assertEquals("HARDBACK", mapPhysicalFormat("Hardback"))
        assertEquals("TRADE_PAPERBACK", mapPhysicalFormat("Paperback"))
        assertEquals("TRADE_PAPERBACK", mapPhysicalFormat("Trade Paperback"))
        assertEquals("EBOOK_EPUB", mapPhysicalFormat("ebook"))
        assertEquals("EBOOK_EPUB", mapPhysicalFormat("EPUB"))
        assertEquals("EBOOK_PDF", mapPhysicalFormat("PDF"))
        assertEquals("AUDIOBOOK_CD", mapPhysicalFormat("Audio CD"))
        assertEquals("AUDIOBOOK_DIGITAL", mapPhysicalFormat("Audiobook"))
        assertNull(mapPhysicalFormat("Leather Bound"))
        assertNull(mapPhysicalFormat(null))
    }

    @Test
    fun `extractYear pulls 4-digit year out of OL date strings`() {
        assertEquals(1951, extractYear("August 1951"))
        assertEquals(2004, extractYear("2004"))
        assertEquals(1991, extractYear("Nov 1991 published by Bantam"))
        assertNull(extractYear("no year here"))
        assertNull(extractYear("3000 is out of range"))
    }

    @Test
    fun `parse a realistic edition + work payload`() {
        // Trimmed facsimile of what OL returns for Foundation (ISBN 0553293354)
        val editionJson = """
            {
              "key": "/books/OL7440033M",
              "title": "Foundation",
              "works": [ { "key": "/works/OL46125W" } ],
              "authors": [ { "key": "/authors/OL34184A" } ],
              "number_of_pages": 244,
              "physical_format": "Paperback",
              "publish_date": "August 1991",
              "description": "edition-level fallback"
            }
        """.trimIndent()
        val workJson = """
            {
              "key": "/works/OL46125W",
              "title": "Foundation",
              "description": { "type": "/type/text", "value": "Asimov's psychohistory classic." },
              "first_publish_date": "August 1951",
              "authors": [ { "author": { "key": "/authors/OL34184A" } } ],
              "series": ["Foundation #1"]
            }
        """.trimIndent()

        val svc = OpenLibraryHttpService()
        val result = svc.parse(
            isbn = "0553293354",
            editionBody = editionJson,
            workFetcher = { key -> if (key == "/works/OL46125W") workJson else null },
            authorFetcher = { id -> if (id == "OL34184A") "Isaac Asimov" else null }
        )

        assertTrue(result is OpenLibraryResult.Success, "expected success, got $result")
        val book = (result as OpenLibraryResult.Success).book
        assertEquals("OL46125W", book.openLibraryWorkId)
        assertEquals("OL7440033M", book.openLibraryEditionId)
        assertEquals("Foundation", book.workTitle)
        assertEquals(244, book.pageCount)
        assertEquals(1991, book.editionYear)
        assertEquals(1951, book.firstPublicationYear)
        assertEquals("TRADE_PAPERBACK", book.mediaFormat)
        assertEquals("0553293354", book.isbn)
        assertEquals("Asimov's psychohistory classic.", book.description)
        assertEquals(1, book.authors.size)
        assertEquals("OL34184A", book.authors[0].openLibraryAuthorId)
        assertEquals("Isaac Asimov", book.authors[0].name)
        assertEquals(1, book.series.size)
        assertEquals("Foundation", book.series[0].name)
        assertEquals(java.math.BigDecimal("1"), book.series[0].number)
        assertNotNull(book.coverUrl)
    }

    @Test
    fun `parse falls back to Unknown Author when the author fetch returns null`() {
        val editionJson = """{"key":"/books/OL1M","title":"T","works":[{"key":"/works/OL2W"}],"authors":[{"key":"/authors/OL3A"}]}"""
        val svc = OpenLibraryHttpService()
        val result = svc.parse("1234567890", editionJson, { null }, { null })
        assertTrue(result is OpenLibraryResult.Success)
        val book = (result as OpenLibraryResult.Success).book
        assertEquals("Unknown Author", book.authors.single().name)
    }

    @Test
    fun `parse rejects edition missing key`() {
        val svc = OpenLibraryHttpService()
        val result = svc.parse("1234567890",
            """{"title":"No Key", "works":[{"key":"/works/W"}]}""", { null }, { null })
        assertTrue(result is OpenLibraryResult.Error)
    }

    @Test
    fun `parse rejects edition without work reference`() {
        val svc = OpenLibraryHttpService()
        val result = svc.parse("1234567890",
            """{"key":"/books/OLxM","title":"Orphan"}""", { null }, { null })
        assertTrue(result is OpenLibraryResult.Error)
    }
}
