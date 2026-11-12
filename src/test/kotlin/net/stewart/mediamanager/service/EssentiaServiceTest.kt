package net.stewart.mediamanager.service

import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure parse tests for [EssentiaService.parseRhythm] plus end-to-end
 * `analyzeRhythm` / `isAvailable` tests routed through the
 * [SubprocessRule] fake — no real binary required.
 */
internal class EssentiaServiceTest {

    @get:Rule val subprocs = SubprocessRule()

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

    // ---------------------- isAvailable via SubprocessRule ----------------------

    @Test
    fun `isAvailable is true when the fake responds at all`() {
        subprocs.fake.onBinary("essentia_streaming_extractor_music",
            exitCode = 1,  // matches reality: --help exits non-zero
            stderr = "Usage: essentia_streaming_extractor_music ...")
        assertTrue(EssentiaService.isAvailable())
    }

    @Test
    fun `isAvailable is false when the fake throws (binary missing)`() {
        // No rule registered → fake throws on any invocation, mirroring
        // ProcessBuilder's IOException for a missing executable.
        assertFalse(EssentiaService.isAvailable())
    }

    @Test
    fun `isAvailable forwards a custom binary path as argv0`() {
        subprocs.fake.onBinary("essentia_streaming_extractor_music",
            stdout = "ok")
        EssentiaService.isAvailable("/opt/essentia/bin/essentia_streaming_extractor_music")
        assertEquals("/opt/essentia/bin/essentia_streaming_extractor_music",
            subprocs.fake.invocations.single().first())
    }

    // ---------------------- analyzeRhythm ----------------------

    private fun realTempAudio(): File {
        // analyzeRhythm bails when the input file isn't on disk, so the
        // test seeds an empty real file — the bytes never get read by
        // the fake binary anyway.
        val tmp = Files.createTempFile("rhythm-test-", ".flac").toFile()
        tmp.deleteOnExit()
        return tmp
    }

    @Test
    fun `analyzeRhythm returns parsed Rhythm when the binary writes good JSON to argv2`() {
        val input = realTempAudio()
        // The binary writes its output to argv[2]; the fake's sideEffect
        // simulates that.
        subprocs.fake.onBinary(
            "essentia_streaming_extractor_music",
            sideEffect = { argv ->
                File(argv[2]).writeText(
                    """{"rhythm": {"bpm": 124.7,
                        "bpm_histogram_first_peak_weight": 0.71,
                        "beats_count": 414}}"""
                )
            }
        )

        val rhythm = EssentiaService.analyzeRhythm(input)
        assertNotNull(rhythm)
        assertEquals(124, rhythm.bpm)
        assertEquals(0.71, rhythm.confidence)
        assertEquals(414, rhythm.beatsCount)

        // Argv shape is locked down: binary, input, temp output.
        val argv = subprocs.fake.invocations.single()
        assertEquals(3, argv.size)
        assertEquals(input.absolutePath, argv[1])
        assertTrue(argv[2].endsWith(".json"),
            "argv[2] is the temp output path the binary writes to")
    }

    @Test
    fun `analyzeRhythm returns null when the binary exits non-zero`() {
        val input = realTempAudio()
        subprocs.fake.onBinary("essentia_streaming_extractor_music",
            exitCode = 137,
            stderr = "OOM killed")
        assertNull(EssentiaService.analyzeRhythm(input))
    }

    @Test
    fun `analyzeRhythm returns null on subprocess timeout`() {
        val input = realTempAudio()
        subprocs.fake.onBinary("essentia_streaming_extractor_music",
            timedOut = true, exitCode = -1)
        assertNull(EssentiaService.analyzeRhythm(input))
    }

    @Test
    fun `analyzeRhythm returns null when the binary writes no output file`() {
        val input = realTempAudio()
        // Subprocess "succeeds" but doesn't write the temp output, so
        // parseRhythm reads an empty/missing file and bails.
        subprocs.fake.onBinary("essentia_streaming_extractor_music",
            exitCode = 0,
            sideEffect = null /* nothing written */)

        assertNull(EssentiaService.analyzeRhythm(input))
    }

    @Test
    fun `analyzeRhythm short-circuits without forking when input is not a file`() {
        val notAFile = File("/no/such/file.flac")
        // No rule registered — if analyzeRhythm forks, the fake throws.
        assertNull(EssentiaService.analyzeRhythm(notAFile))
        assertEquals(0, subprocs.fake.invocations.size)
    }
}
