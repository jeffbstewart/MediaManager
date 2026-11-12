package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Extracts embedded cover art from an audio file as a JPEG byte stream.
 *
 * dBpoweramp (and most modern rippers) embed the disc's cover image as a
 * FLAC PICTURE block / ID3 APIC frame / M4A `covr` atom. Using that art
 * beats the Cover Art Archive because: (a) it's guaranteed to be present
 * for the exact pressing we have, (b) no network round-trip, (c) many
 * pressings have no CAA entry at all (the 404 we were seeing).
 *
 * Implementation: one ffmpeg invocation that maps the first video stream
 * (if any) and re-encodes to a single mjpeg frame on stdout. Re-encode
 * rather than `-c:v copy` so PNG-embedded art still yields JPEG bytes our
 * downstream (PosterCacheService) knows how to serve. Audio files with no
 * embedded art produce empty stdout and a zero exit — that's treated as
 * "no art available", not an error.
 */
object AudioCoverExtractor {

    private val log = LoggerFactory.getLogger(AudioCoverExtractor::class.java)

    /** Returns JPEG bytes of the first embedded picture, or null when there's none or ffmpeg fails. */
    fun extractJpeg(audioFile: File, ffmpegPath: String = TranscoderAgent.getFfmpegPath()): ByteArray? {
        if (!audioFile.isFile) return null
        val proc = try {
            ProcessBuilder(
                ffmpegPath,
                "-nostdin", "-y", "-v", "error",
                "-i", audioFile.absolutePath,
                "-an",
                // `?` makes the video map optional so files without embedded
                // art don't cause ffmpeg to error out.
                "-map", "0:v:0?",
                "-vframes", "1",
                "-f", "mjpeg",
                "pipe:1"
            ).redirectErrorStream(false).start()
        } catch (e: Exception) {
            log.warn("ffmpeg launch failed for cover extract on {}: {}", audioFile.absolutePath, e.message)
            return null
        }

        val bytes = try {
            proc.inputStream.readAllBytes()
        } catch (e: Exception) {
            log.warn("ffmpeg stdout read failed for {}: {}", audioFile.absolutePath, e.message)
            proc.destroyForcibly()
            return null
        }
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            log.warn("ffmpeg cover extract timed out on {}", audioFile.absolutePath)
            return null
        }
        if (proc.exitValue() != 0) {
            val stderr = proc.errorStream.bufferedReader().readText().trim()
            log.debug("ffmpeg cover extract exit={} for {} (stderr: {})",
                proc.exitValue(), audioFile.absolutePath, stderr)
            return null
        }
        if (bytes.isEmpty()) return null
        return bytes
    }
}
