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
            authorFetcher = { id -> if (id == "OL34184A") OpenLibraryHttpService.AuthorMeta("Isaac Asimov", hasBio = true) else null }
        )

        assertTrue(result is OpenLibraryResult.Success, "expected success, got $result")
        val book = result.book
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
        // Authors come from work.authors (no edition fallback). The work
        // fetch returns the author key but the per-author meta fetch
        // resolves to null — exercise the "Unknown Author" path.
        val editionJson = """{"key":"/books/OL1M","title":"T","works":[{"key":"/works/OL2W"}]}"""
        val workJson = """{"key":"/works/OL2W","title":"T","authors":[{"author":{"key":"/authors/OL3A"}}]}"""
        val svc = OpenLibraryHttpService()
        val result = svc.parse(
            isbn = "1234567890",
            editionBody = editionJson,
            workFetcher = { key -> if (key == "/works/OL2W") workJson else null },
            authorFetcher = { null },
        )
        assertTrue(result is OpenLibraryResult.Success)
        val book = result.book
        assertEquals("Unknown Author", book.authors.single().name)
    }

    @Test
    fun `parse drops skeleton OL co-authors when at least one author has a bio`() {
        // OL's work.authors regularly lists illustrators / translators
        // alongside the real author with no role discrimination —
        // Laura Ellen Anderson on The Shepherd's Crown is the
        // canonical case. The disambiguator is that the real author's
        // OL record has a populated `bio` (or personal_name /
        // birth_date / alternate_names) while the contributor's is a
        // bare-name skeleton. When the parser sees both shapes in one
        // work, the skeletons get filtered out.
        val editionJson = """{"key":"/books/OL10M","title":"T","works":[{"key":"/works/OL20W"}]}"""
        val workJson = """{"key":"/works/OL20W","title":"T","authors":[
            {"author":{"key":"/authors/OL_REAL"}},
            {"author":{"key":"/authors/OL_ILLU"}}
        ]}"""
        val svc = OpenLibraryHttpService()
        val result = svc.parse(
            isbn = "1234567890",
            editionBody = editionJson,
            workFetcher = { key -> if (key == "/works/OL20W") workJson else null },
            authorFetcher = { id ->
                when (id) {
                    "OL_REAL" -> OpenLibraryHttpService.AuthorMeta("Real Author", hasBio = true)
                    "OL_ILLU" -> OpenLibraryHttpService.AuthorMeta("Skeleton Illustrator", hasBio = false)
                    else -> null
                }
            },
        )
        assertTrue(result is OpenLibraryResult.Success)
        assertEquals(listOf("Real Author"), result.book.authors.map { it.name })
    }

    @Test
    fun `parse keeps every author when nobody has a bio`() {
        // Without a fleshed-out record to anchor the heuristic the
        // parser shouldn't drop the entire author list — better to
        // keep what we have and let admin curate.
        val editionJson = """{"key":"/books/OL11M","title":"T","works":[{"key":"/works/OL21W"}]}"""
        val workJson = """{"key":"/works/OL21W","title":"T","authors":[
            {"author":{"key":"/authors/OL_A"}},
            {"author":{"key":"/authors/OL_B"}}
        ]}"""
        val svc = OpenLibraryHttpService()
        val result = svc.parse(
            isbn = "1234567890",
            editionBody = editionJson,
            workFetcher = { key -> if (key == "/works/OL21W") workJson else null },
            authorFetcher = { id ->
                when (id) {
                    "OL_A" -> OpenLibraryHttpService.AuthorMeta("Author A", hasBio = false)
                    "OL_B" -> OpenLibraryHttpService.AuthorMeta("Author B", hasBio = false)
                    else -> null
                }
            },
        )
        assertTrue(result is OpenLibraryResult.Success)
        assertEquals(listOf("Author A", "Author B"), result.book.authors.map { it.name })
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

    @Test
    fun `parseAuthorWorks extracts id, title, year, cover, series`() {
        val body = """
            {
              "entries": [
                {
                  "key": "/works/OL46125W",
                  "title": "Foundation",
                  "first_publish_date": "1951",
                  "covers": [8739161],
                  "series": ["Foundation #1"]
                },
                {
                  "key": "/works/OL46126W",
                  "title": "Foundation and Empire",
                  "first_publish_date": "1952",
                  "series": ["Foundation #2"]
                },
                {
                  "key": "/works/OL46127W",
                  "title": "The Caves of Steel",
                  "first_publish_date": "1954"
                }
              ]
            }
        """.trimIndent()
        val works = OpenLibraryHttpService().parseAuthorWorks(body)
        assertEquals(3, works.size)
        assertEquals("OL46125W", works[0].openLibraryWorkId)
        assertEquals("Foundation", works[0].title)
        assertEquals(1951, works[0].firstPublishYear)
        assertTrue(works[0].coverUrl?.contains("8739161") == true)
        assertEquals("Foundation #1", works[0].seriesRaw)

        assertNull(works[1].coverUrl, "Covers entry absent → null coverUrl")
        assertNull(works[2].seriesRaw, "Series entry absent → null seriesRaw")
    }

    @Test
    fun `parseAuthorWorks handles empty and malformed inputs`() {
        val svc = OpenLibraryHttpService()
        assertTrue(svc.parseAuthorWorks("""{"entries":[]}""").isEmpty())
        assertTrue(svc.parseAuthorWorks("""{"other":"shape"}""").isEmpty())
        // Skip entries missing required fields rather than crashing.
        val partial = """{"entries":[{"title":"no key"},{"key":"/works/W","title":"ok"}]}"""
        val result = svc.parseAuthorWorks(partial)
        assertEquals(1, result.size)
        assertEquals("W", result[0].openLibraryWorkId)
    }
}
