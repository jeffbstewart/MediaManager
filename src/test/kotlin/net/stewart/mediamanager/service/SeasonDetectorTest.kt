package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonDetectorTest {

    // --- Complete Series ---

    @Test
    fun `detects Complete Series`() {
        val result = SeasonDetector.detect("Breaking Bad Complete Series (Blu-ray)")
        assertTrue(result.hasSeason)
        assertEquals("all", result.seasons)
    }

    @Test
    fun `detects Complete Collection`() {
        val result = SeasonDetector.detect("The Wire Complete Collection [DVD]")
        assertTrue(result.hasSeason)
        assertEquals("all", result.seasons)
    }

    @Test
    fun `detects Complete Series Box Set (both season + multi-pack)`() {
        val result = SeasonDetector.detect("Downton Abbey Complete Series Box Set [Blu-ray]")
        assertTrue(result.hasSeason)
        assertEquals("all", result.seasons)
    }

    // --- Season Range ---

    @Test
    fun `detects Seasons 1-3`() {
        val result = SeasonDetector.detect("Game of Thrones Seasons 1-3 [Blu-ray]")
        assertTrue(result.hasSeason)
        assertEquals("1-3", result.seasons)
    }

    @Test
    fun `detects Seasons 1 - 5 with spaces`() {
        val result = SeasonDetector.detect("Friends Seasons 1 - 5 (DVD)")
        assertTrue(result.hasSeason)
        assertEquals("1-5", result.seasons)
    }

    // --- Single Season ---

    @Test
    fun `detects Season 1`() {
        val result = SeasonDetector.detect("Breaking Bad Season 1 [Blu-ray]")
        assertTrue(result.hasSeason)
        assertEquals("1", result.seasons)
    }

    @Test
    fun `detects Season 02`() {
        val result = SeasonDetector.detect("The Office Season 02 (DVD)")
        assertTrue(result.hasSeason)
        assertEquals("2", result.seasons)
    }

    @Test
    fun `detects Season 10`() {
        val result = SeasonDetector.detect("Supernatural Season 10 [Blu-ray]")
        assertTrue(result.hasSeason)
        assertEquals("10", result.seasons)
    }

    // --- Short Form ---

    @Test
    fun `detects S1`() {
        val result = SeasonDetector.detect("Breaking Bad S1 [Blu-ray]")
        assertTrue(result.hasSeason)
        assertEquals("1", result.seasons)
    }

    @Test
    fun `detects S01`() {
        val result = SeasonDetector.detect("The Sopranos S01 (DVD)")
        assertTrue(result.hasSeason)
        assertEquals("1", result.seasons)
    }

    @Test
    fun `detects S3 at end of string`() {
        val result = SeasonDetector.detect("Fargo S3")
        assertTrue(result.hasSeason)
        assertEquals("3", result.seasons)
    }

    // --- British Convention ---

    @Test
    fun `detects Series 1`() {
        val result = SeasonDetector.detect("Doctor Who Series 1 [Blu-ray]")
        assertTrue(result.hasSeason)
        assertEquals("1", result.seasons)
    }

    @Test
    fun `detects Series 03`() {
        val result = SeasonDetector.detect("Sherlock Series 03 (DVD)")
        assertTrue(result.hasSeason)
        assertEquals("3", result.seasons)
    }

    // --- Ordinals ---

    @Test
    fun `detects First Season`() {
        val result = SeasonDetector.detect("Lost: The First Season [DVD]")
        assertTrue(result.hasSeason)
        assertEquals("1", result.seasons)
    }

    @Test
    fun `detects Second Season`() {
        val result = SeasonDetector.detect("24: The Second Season")
        assertTrue(result.hasSeason)
        assertEquals("2", result.seasons)
    }

    @Test
    fun `detects Third Season`() {
        val result = SeasonDetector.detect("Dexter: Third Season [Blu-ray]")
        assertTrue(result.hasSeason)
        assertEquals("3", result.seasons)
    }

    @Test
    fun `detects Fourth Season`() {
        val result = SeasonDetector.detect("The Wire Fourth Season (DVD)")
        assertTrue(result.hasSeason)
        assertEquals("4", result.seasons)
    }

    @Test
    fun `detects Fifth Season`() {
        val result = SeasonDetector.detect("Breaking Bad Fifth Season [Blu-ray]")
        assertTrue(result.hasSeason)
        assertEquals("5", result.seasons)
    }

    // --- Negative cases ---

    @Test
    fun `movie title returns no season`() {
        val result = SeasonDetector.detect("The Shawshank Redemption [Blu-ray]")
        assertFalse(result.hasSeason)
        assertNull(result.seasons)
    }

    @Test
    fun `movie with number in title is not season`() {
        val result = SeasonDetector.detect("Alien 3 [Blu-ray]")
        assertFalse(result.hasSeason)
    }

    @Test
    fun `null input returns no season`() {
        val result = SeasonDetector.detect(null)
        assertFalse(result.hasSeason)
        assertNull(result.seasons)
    }

    @Test
    fun `blank input returns no season`() {
        val result = SeasonDetector.detect("   ")
        assertFalse(result.hasSeason)
    }

    @Test
    fun `empty input returns no season`() {
        val result = SeasonDetector.detect("")
        assertFalse(result.hasSeason)
    }

    // --- Priority: Complete Series wins over single season patterns ---

    @Test
    fun `Complete Series takes priority over Season keyword in same string`() {
        // "Complete Series" should match before any Season N pattern
        val result = SeasonDetector.detect("Breaking Bad The Complete Series Season 1-5 (Blu-ray)")
        assertTrue(result.hasSeason)
        assertEquals("all", result.seasons)
    }
}
