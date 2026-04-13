package net.stewart.transcode

import java.io.File
import kotlin.test.*

/**
 * Tests for TranscodeCommand.buildMobile(), the builder for ForMobile ffmpeg commands.
 */
class TranscodeCommandTest {

    private val src = File("/nas/input/episode.mkv")
    private val dst = File("/nas/output/episode.mp4")
    private val ffmpeg = "/usr/bin/ffmpeg"

    // ---- Resolution cap ----

    @Test
    fun `buildMobile caps output at 720p, not 1080p`() {
        val (cmd, _) = TranscodeCommand.buildMobile(
            ffmpeg, src, dst, dvdProbe(), EncoderProfile.NVENC
        )
        val joined = cmd.joinToString(" ")
        assertTrue("min(1280,iw)" in joined && "min(720,ih)" in joined,
            "Expected 720p scale cap; got: $joined")
    }

    @Test
    fun `buildMobile does NOT cap at 1080p (regression guard)`() {
        // v1 preset capped at 1080p and was the second half of the bloat bug:
        // SD/DVD content stayed at source resolution and got flooded with bits.
        val (cmd, _) = TranscodeCommand.buildMobile(
            ffmpeg, src, dst, hdProbe(), EncoderProfile.NVENC
        )
        val joined = cmd.joinToString(" ")
        assertFalse("min(1920,iw)" in joined || "min(1080,ih)" in joined,
            "Mobile output must not cap at 1080p; got: $joined")
    }

    // ---- Video always re-encoded ----

    @Test
    fun `buildMobile always re-encodes video, never copies`() {
        // Even if the source is already H.264, ForMobile exists precisely to
        // shrink the bitrate. Copy-codec would defeat the purpose.
        val probe = VideoProbeResult(
            codec = "h264", durationSecs = 3000.0,
            sarNum = 1, sarDen = 1, fps = 30.0,
            width = 1280, height = 720
        )
        val (cmd, encoderName) = TranscodeCommand.buildMobile(
            ffmpeg, src, dst, probe, EncoderProfile.NVENC
        )
        assertNotEquals("copy", encoderName)
        assertFalse(cmd.windowed(2).any { it == listOf("-c:v", "copy") })
    }

    // ---- Filter chain ----

    @Test
    fun `buildMobile includes yadif deinterlace for interlaced source`() {
        val probe = VideoProbeResult(
            codec = "mpeg2video", durationSecs = 3000.0,
            sarNum = 1, sarDen = 1, fps = 29.97,
            width = 720, height = 480, interlaced = true
        )
        val (cmd, _) = TranscodeCommand.buildMobile(
            ffmpeg, src, dst, probe, EncoderProfile.NVENC
        )
        val vfIdx = cmd.indexOf("-vf")
        assertTrue(vfIdx >= 0, "Expected -vf flag; got: ${cmd.joinToString(" ")}")
        val filters = cmd[vfIdx + 1]
        assertTrue("yadif" in filters, "Expected yadif in filter chain; got: $filters")
    }

    @Test
    fun `buildMobile includes anamorphic correction for non-square SAR`() {
        // DVD 720x480 SAR 8:9 -> needs scale to square pixels (640x480).
        val (cmd, _) = TranscodeCommand.buildMobile(
            ffmpeg, src, dst, dvdProbe(), EncoderProfile.NVENC
        )
        val vfIdx = cmd.indexOf("-vf")
        val filters = cmd[vfIdx + 1]
        assertTrue("setsar=1:1" in filters,
            "Expected setsar=1:1 for anamorphic source; got: $filters")
    }

    // ---- Audio / container ----

    @Test
    fun `buildMobile outputs faststart MP4 with stereo AAC 160k`() {
        val (cmd, _) = TranscodeCommand.buildMobile(
            ffmpeg, src, dst, dvdProbe(), EncoderProfile.NVENC
        )
        assertContainsPair(cmd, "-movflags", "+faststart")
        assertContainsPair(cmd, "-c:a", "aac")
        assertContainsPair(cmd, "-ac", "2")
        assertContainsPair(cmd, "-b:a", "160k")
        assertContainsPair(cmd, "-f", "mp4")
    }

    // ---- Fixtures ----

    /** 720x480 NTSC DVD rip with anamorphic SAR 8:9. */
    private fun dvdProbe() = VideoProbeResult(
        codec = "mpeg2video",
        durationSecs = 2955.5,
        sarNum = 8, sarDen = 9,
        fps = 29.97,
        width = 720, height = 480,
        interlaced = false
    )

    /** 1920x1080 Blu-ray-ish H.264. */
    private fun hdProbe() = VideoProbeResult(
        codec = "h264",
        durationSecs = 7200.0,
        sarNum = 1, sarDen = 1,
        fps = 24.0,
        width = 1920, height = 1080,
        interlaced = false
    )

    // ---- Helpers ----

    private fun assertContainsPair(args: List<String>, flag: String, value: String) {
        val idx = args.indexOf(flag)
        assertTrue(idx >= 0 && idx + 1 < args.size && args[idx + 1] == value,
            "Expected '$flag $value'; got: ${args.joinToString(" ")}")
    }
}
