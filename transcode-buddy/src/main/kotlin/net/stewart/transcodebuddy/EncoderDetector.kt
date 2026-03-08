package net.stewart.transcodebuddy

import net.stewart.transcode.EncoderProfile
import org.slf4j.LoggerFactory

/**
 * Detects available GPU encoders by running a short test encode with each.
 * Encoder profiles and probe logic live in transcode-common.
 */
object EncoderDetector {

    private val log = LoggerFactory.getLogger(EncoderDetector::class.java)

    /**
     * Detects available GPU encoders by running a short test encode with each.
     * Returns the best available encoder from the preference list.
     */
    fun detectBestEncoder(ffmpegPath: String, preference: List<String>): EncoderProfile {
        log.info("Detecting available encoders (preference: {})", preference)

        for (name in preference) {
            val profile = EncoderProfile.byName(name)
            if (profile == null) {
                log.warn("Unknown encoder: {}", name)
                continue
            }

            // CPU always works (libx264 is built into FFmpeg)
            if (name == "cpu") {
                log.info("Using CPU encoder (libx264)")
                return profile
            }

            if (testEncoder(ffmpegPath, profile)) {
                log.info("Encoder '{}' ({}) is available", name, profile.ffmpegEncoder)
                return profile
            } else {
                log.info("Encoder '{}' ({}) is NOT available", name, profile.ffmpegEncoder)
            }
        }

        log.warn("No preferred encoder available, falling back to CPU")
        return EncoderProfile.CPU
    }

    /**
     * Tests an encoder by running a 1-second synthetic test encode.
     */
    private fun testEncoder(ffmpegPath: String, profile: EncoderProfile): Boolean {
        return try {
            val command = mutableListOf(
                ffmpegPath,
                "-f", "lavfi",
                "-i", "testsrc=duration=1:size=320x240:rate=1",
                "-t", "1"
            )
            command.addAll(profile.args)
            command.addAll(listOf(
                "-f", "mp4",
                "-y",
                if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null"
            ))

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                log.debug("Test encode with {} failed (exit {}): {}",
                    profile.ffmpegEncoder, exitCode, output.takeLast(500))
            }

            exitCode == 0
        } catch (e: Exception) {
            log.debug("Test encode with {} threw: {}", profile.ffmpegEncoder, e.message)
            false
        }
    }
}
