package net.stewart.mediamanager.service

import kotlin.test.*

class FilenameSanitizerTest {

    @Test
    fun `needsSanitization detects colon`() {
        assertTrue(FilenameSanitizer.needsSanitization("Star Trek: The Motion Picture.mkv"))
    }

    @Test
    fun `needsSanitization detects question mark`() {
        assertTrue(FilenameSanitizer.needsSanitization("What If?.mkv"))
    }

    @Test
    fun `needsSanitization detects asterisk`() {
        assertTrue(FilenameSanitizer.needsSanitization("M*A*S*H.mkv"))
    }

    @Test
    fun `needsSanitization detects angle brackets`() {
        assertTrue(FilenameSanitizer.needsSanitization("Title <Special>.mkv"))
    }

    @Test
    fun `needsSanitization detects pipe`() {
        assertTrue(FilenameSanitizer.needsSanitization("Title | Edition.mkv"))
    }

    @Test
    fun `needsSanitization detects double quotes`() {
        assertTrue(FilenameSanitizer.needsSanitization("Title \"Edition\".mkv"))
    }

    @Test
    fun `needsSanitization returns false for clean filename`() {
        assertFalse(FilenameSanitizer.needsSanitization("The Matrix (1999).mkv"))
    }

    @Test
    fun `sanitize replaces colon with dash`() {
        assertEquals(
            "Star Trek - The Motion Picture.mkv",
            FilenameSanitizer.sanitize("Star Trek: The Motion Picture.mkv")
        )
    }

    @Test
    fun `sanitize removes question mark`() {
        assertEquals("What If.mkv", FilenameSanitizer.sanitize("What If?.mkv"))
    }

    @Test
    fun `sanitize replaces asterisk with underscore`() {
        assertEquals("M_A_S_H.mkv", FilenameSanitizer.sanitize("M*A*S*H.mkv"))
    }

    @Test
    fun `sanitize replaces angle brackets with underscore`() {
        assertEquals("Title _Special_.mkv", FilenameSanitizer.sanitize("Title <Special>.mkv"))
    }

    @Test
    fun `sanitize replaces pipe with underscore`() {
        assertEquals("Title _ Edition.mkv", FilenameSanitizer.sanitize("Title | Edition.mkv"))
    }

    @Test
    fun `sanitize replaces double quotes with underscore`() {
        assertEquals("Title _Edition_.mkv", FilenameSanitizer.sanitize("Title \"Edition\".mkv"))
    }

    @Test
    fun `sanitize handles multiple colons`() {
        assertEquals(
            "Star Trek - Deep Space Nine - S01E01.mkv",
            FilenameSanitizer.sanitize("Star Trek: Deep Space Nine: S01E01.mkv")
        )
    }

    @Test
    fun `sanitize collapses whitespace after colon replacement`() {
        // "Title: Name" -> "Title - Name" (colon at end of word + space)
        assertEquals(
            "Title - Name.mkv",
            FilenameSanitizer.sanitize("Title: Name.mkv")
        )
    }

    @Test
    fun `sanitize handles mixed disallowed characters`() {
        assertEquals(
            "What - A _Great_ Movie.mkv",
            FilenameSanitizer.sanitize("What: A \"Great\" Movie?.mkv")
        )
    }

    @Test
    fun `sanitize preserves clean filename`() {
        val clean = "The Matrix (1999).mkv"
        assertEquals(clean, FilenameSanitizer.sanitize(clean))
    }

    @Test
    fun `sanitize handles TV episode with colon in show name`() {
        assertEquals(
            "Star Trek - Discovery S01E01.mkv",
            FilenameSanitizer.sanitize("Star Trek: Discovery S01E01.mkv")
        )
    }

    @Test
    fun `sanitize handles directory name with colon`() {
        assertEquals(
            "Star Trek - Discovery",
            FilenameSanitizer.sanitize("Star Trek: Discovery")
        )
    }

    @Test
    fun `needsSanitization detects colon in directory name`() {
        assertTrue(FilenameSanitizer.needsSanitization("Star Trek: Discovery"))
    }
}
