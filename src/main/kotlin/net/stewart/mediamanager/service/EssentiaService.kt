package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

/**
 * Wraps the `essentia_streaming_extractor_music` command-line binary.
 *
 * Essentia is a C++ MIR library; the streaming extractor is a CPU-only
 * standalone binary that writes a JSON/YAML file of descriptors
 * (rhythm, tonal, spectral, ...) for a given audio file. We bundle the
 * static binary in the Docker image and invoke it per track via
 * ProcessBuilder, the same shape as [AudioTagReader]'s ffprobe wrapper.
 *
 * Outputs are routed to a temp JSON file, parsed, and discarded — the
 * extractor writes everything it knows, but we keep only the rhythm
 * subset on the track row.
 */
object EssentiaService {

    private val log = LoggerFactory.getLogger(EssentiaService::class.java)
    private val mapper = ObjectMapper()

    /** What the binary produced for one track — consumed by the agent. */
    data class Rhythm(
        val bpm: Int,
        /**
         * `bpm_histogram_first_peak_weight` normalized to 0..1. Reflects
         * how dominant the detected BPM is versus alternatives — a 0.9
         * track is unambiguous, a 0.3 track is borderline (consider
         * manual review).
         */
        val confidence: Double?,
        /** Total beat count from Essentia's beat tracker. Useful for sanity checks. */
        val beatsCount: Int?
    )

    /** Returns true when the binary is on PATH and responds to `--help`. */
    fun isAvailable(binaryPath: String = "essentia_streaming_extractor_music"): Boolean {
        return try {
            // Routes through Subprocesses.current; the production runner
            // reports a non-zero exit (the extractor exits non-zero on
            // --help), but the seam keeps that as "ran at all". The
            // fake either responds (treated as available) or throws if
            // no rule matches (treated as unavailable, matching the
            // real ProcessBuilder behavior when the binary is missing).
            Subprocesses.current.run(
                command = listOf(binaryPath, "--help"),
                timeout = java.time.Duration.ofSeconds(5),
                redirectErrorStream = true,
            )
            true
        } catch (e: Exception) {
            log.warn("Essentia binary not available at '{}': {}", binaryPath, e.message)
            false
        }
    }

    /**
     * Run the extractor on [file] and return the rhythm subset.
     * Returns null on any failure — the caller (agent) logs and moves on.
     */
    fun analyzeRhythm(
        file: File,
        binaryPath: String = "essentia_streaming_extractor_music",
        timeout: java.time.Duration = java.time.Duration.ofMinutes(2)
    ): Rhythm? {
        if (!file.isFile) {
            log.warn("analyzeRhythm: {} is not a file", file)
            return null
        }
        val tempOut = Files.createTempFile("essentia-", ".json").toFile()
        try {
            // The binary writes its JSON output to argv[2]; in tests, the
            // FakeSubprocessRunner's sideEffect can drop a scripted
            // payload at that path so parseRhythm sees real bytes.
            val result = Subprocesses.current.run(
                command = listOf(binaryPath, file.absolutePath, tempOut.absolutePath),
                timeout = timeout,
                redirectErrorStream = false,
            )
            if (result.timedOut) {
                log.warn("Essentia timed out on {}", file.absolutePath)
                return null
            }
            if (result.exitCode != 0) {
                log.warn("Essentia exit={} on {}: {}", result.exitCode, file.absolutePath,
                    result.stderr.take(500))
                return null
            }
            return parseRhythm(tempOut)
        } catch (e: Exception) {
            log.warn("Essentia failed on {}: {}", file.absolutePath, e.message)
            return null
        } finally {
            runCatching { tempOut.delete() }
        }
    }

    /**
     * Parse just the rhythm section of Essentia's JSON output. The
     * streaming extractor writes a deeply-nested structure; the fields
     * we care about are:
     *   - `rhythm.bpm`                              (scalar double)
     *   - `rhythm.bpm_histogram_first_peak_weight`  (usually a wrapped
     *       aggregate, so we probe both forms)
     *   - `rhythm.beats_count`
     */
    internal fun parseRhythm(jsonFile: File): Rhythm? {
        val root = mapper.readTree(jsonFile)
        val rhythm = root.path("rhythm")
        if (rhythm.isMissingNode) return null

        val bpm = rhythm.path("bpm").asDouble(Double.NaN)
        if (bpm.isNaN() || bpm <= 0 || bpm > 400) {
            log.warn("Essentia returned implausible bpm={} on {}", bpm, jsonFile)
            return null
        }
        val confidenceNode = rhythm.path("bpm_histogram_first_peak_weight")
        val confidence = when {
            confidenceNode.isMissingNode -> null
            confidenceNode.isNumber -> confidenceNode.asDouble()
            // Aggregated form — essentia sometimes nests statistics.
            confidenceNode.path("mean").isNumber -> confidenceNode.path("mean").asDouble()
            confidenceNode.path("value").isNumber -> confidenceNode.path("value").asDouble()
            else -> null
        }
        val beatsCount = rhythm.path("beats_count").asInt(0).takeIf { it > 0 }

        return Rhythm(
            bpm = bpm.toInt(),
            confidence = confidence?.coerceIn(0.0, 1.0),
            beatsCount = beatsCount
        )
    }
}
