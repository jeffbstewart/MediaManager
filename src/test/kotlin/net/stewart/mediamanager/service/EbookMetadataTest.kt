package net.stewart.mediamanager.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EbookMetadataTest {

    @Test
    fun `normalizeIsbn accepts ISBN-13 plain`() {
        assertEquals("9780553293357", EbookMetadataExtractor.normalizeIsbn("9780553293357"))
    }

    @Test
    fun `normalizeIsbn accepts ISBN-13 with hyphens`() {
        assertEquals("9780553293357", EbookMetadataExtractor.normalizeIsbn("978-0-553-29335-7"))
    }

    @Test
    fun `normalizeIsbn accepts ISBN-10 and uppercases the X checksum`() {
        assertEquals("043942089X", EbookMetadataExtractor.normalizeIsbn("043942089x"))
    }

    @Test
    fun `normalizeIsbn strips 'urn isbn' prefix`() {
        assertEquals("9780553293357", EbookMetadataExtractor.normalizeIsbn("urn:isbn:9780553293357"))
    }

    @Test
    fun `normalizeIsbn strips 'isbn' colon prefix`() {
        assertEquals("9780553293357", EbookMetadataExtractor.normalizeIsbn("ISBN: 978-0-553-29335-7"))
    }

    @Test
    fun `normalizeIsbn rejects non-ISBN strings`() {
        assertNull(EbookMetadataExtractor.normalizeIsbn("not-an-isbn"))
        assertNull(EbookMetadataExtractor.normalizeIsbn("12345"))
        assertNull(EbookMetadataExtractor.normalizeIsbn(""))
    }

    @Test
    fun `parseContainerForOpfPath returns full-path from rootfile element`() {
        val xml = """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent().byteInputStream()
        assertEquals("OEBPS/content.opf", EbookMetadataExtractor.parseContainerForOpfPath(xml))
    }

    @Test
    fun `parseOpf extracts title, creator, and ISBN identifier`() {
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"
                        xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>Foundation</dc:title>
                <dc:creator opf:role="aut">Isaac Asimov</dc:creator>
                <dc:identifier id="uuid" opf:scheme="UUID">PLACEHOLDER-UUID-FOR-TEST</dc:identifier>
                <dc:identifier id="bookid" opf:scheme="ISBN">978-0-553-29335-7</dc:identifier>
              </metadata>
            </package>
        """.trimIndent().byteInputStream()
        val m = EbookMetadataExtractor.parseOpf(opf)
        assertEquals("Foundation", m.title)
        assertEquals("Isaac Asimov", m.author)
        assertEquals("9780553293357", m.isbn)
    }

    @Test
    fun `parseOpf picks ISBN identifier even when not tagged with scheme`() {
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Untagged</dc:title>
                <dc:identifier>urn:uuid:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx</dc:identifier>
                <dc:identifier>9780553293357</dc:identifier>
              </metadata>
            </package>
        """.trimIndent().byteInputStream()
        val m = EbookMetadataExtractor.parseOpf(opf)
        assertEquals("9780553293357", m.isbn)
    }

    @Test
    fun `findIsbnIn picks 13-digit ISBN out of a keywords string`() {
        val hit = EbookMetadataExtractor.findIsbnIn(listOf(
            "fiction, sci-fi, ISBN 978-0-553-29335-7"
        ))
        assertEquals("9780553293357", hit)
    }

    @Test
    fun `findIsbnIn picks ISBN-10 with X checksum`() {
        val hit = EbookMetadataExtractor.findIsbnIn(listOf(
            "Subject: see catalog — isbn: 043942089X"
        ))
        assertEquals("043942089X", hit)
    }

    @Test
    fun `findIsbnIn returns null when nothing matches`() {
        val hit = EbookMetadataExtractor.findIsbnIn(listOf(
            "no valid identifier, not here either"
        ))
        assertNull(hit)
    }

    @Test
    fun `findIsbnIn picks the first valid ISBN across multiple candidates`() {
        val hit = EbookMetadataExtractor.findIsbnIn(listOf(
            "some keywords without any identifier",
            "ISBN 978-0-553-29335-7 and also 978-0-123-45678-9"
        ))
        assertEquals("9780553293357", hit)
    }

    @Test
    fun `parseOpf returns nulls when metadata is missing`() {
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
              </metadata>
            </package>
        """.trimIndent().byteInputStream()
        val m = EbookMetadataExtractor.parseOpf(opf)
        assertNull(m.title)
        assertNull(m.author)
        assertNull(m.isbn)
    }
}
