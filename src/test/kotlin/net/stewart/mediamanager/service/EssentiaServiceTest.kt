package net.stewart.mediamanager.service

import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pure parse tests for [EssentiaService.parseRhythm] — doesn't shell
 * out to the binary. Covers the two forms Essentia can emit for
 * aggregated descriptors and the implausible-BPM rejection.
 */
class EssentiaServiceTest {

    @Test
    fun `parseRhythm reads a well-formed rhythm block`() {
        val rhythm = runParse("""
            {"rhythm": {
                "bpm": 128.3,
                "bpm_histogram_first_peak_weight": 0.82,
                "beats_count": 512
            }}
        """.trimIndent())
        assertNotNull(rhythm)
        assertEquals(128, rhythm.bpm)
        assertEquals(0.82, rhythm.confidence)
        assertEquals(512, rhythm.beatsCount)
    }

    @Test
    fun `parseRhythm handles aggregated confidence under mean`() {
        val rhythm = runParse("""
            {"rhythm": {
                "bpm": 85.0,
                "bpm_histogram_first_peak_weight": {"mean": 0.6, "var": 0.02}
            }}
        """.trimIndent())
        assertNotNull(rhythm)
        assertEquals(85, rhythm.bpm)
        assertEquals(0.6, rhythm.confidence)
    }

    @Test
    fun `parseRhythm rejects implausible BPM`() {
        assertNull(runParse("""{"rhythm": {"bpm": 0}}"""))
        assertNull(runParse("""{"rhythm": {"bpm": 999}}"""))
        assertNull(runParse("""{"rhythm": {}}"""))
        assertNull(runParse("""{}"""))
    }

    @Test
    fun `parseRhythm confidence missing leaves it null`() {
        val rhythm = runParse("""{"rhythm": {"bpm": 120.0}}""")
        assertNotNull(rhythm)
        assertEquals(120, rhythm.bpm)
        assertNull(rhythm.confidence)
    }

    private fun runParse(json: String): EssentiaService.Rhythm? {
        val file = createTempFile("essentia-test-", ".json")
        file.writeText(json)
        return try {
            EssentiaService.parseRhythm(File(file.toString()))
        } finally {
            file.toFile().delete()
        }
    }
}
