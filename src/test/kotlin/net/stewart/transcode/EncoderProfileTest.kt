package net.stewart.transcode

import kotlin.test.*

/**
 * Tests for EncoderProfile, focused on the mobileVariant() preset.
 *
 * The mobile preset is critical to get right — a bad preset silently inflates
 * every mobile transcode on the NAS and the regression only shows up when
 * someone compares sizes. See claude.log 2026-04-12 for the original incident.
 */
class EncoderProfileTest {

    @Test
    fun `CURRENT_MOBILE_ENCODER_VERSION is bumped past the v1 bloated preset`() {
        // Any value >= 2 is acceptable; the check is that v1 is no longer current
        // so the V072 migration's stamped v1 rows become re-transcode candidates.
        assertTrue(EncoderProfile.CURRENT_MOBILE_ENCODER_VERSION >= 2,
            "Version should be >= 2 so existing v1-stamped rows queue for re-transcode")
    }

    // ---- NVENC mobile preset ----

    @Test
    fun `nvenc mobile uses constant-quality VBR (CQ 23), not fixed ABR`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.NVENC).args
        assertContainsPair(args, "-rc", "vbr")
        assertContainsPair(args, "-cq", "23")
    }

    @Test
    fun `nvenc mobile disables bitrate target (b_v 0) so CQ governs size`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.NVENC).args
        assertContainsPair(args, "-b:v", "0")
    }

    @Test
    fun `nvenc mobile caps max bitrate at 5M`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.NVENC).args
        assertContainsPair(args, "-maxrate", "5M")
        assertContainsPair(args, "-bufsize", "10M")
    }

    @Test
    fun `nvenc mobile does NOT set the old 5M fixed bitrate target`() {
        // Regression guard: v1 preset used "-b:v 5M" which produced 2x browser sizes.
        val args = EncoderProfile.mobileVariant(EncoderProfile.NVENC).args
        assertFalse(hasPair(args, "-b:v", "5M"),
            "Mobile preset must not hard-code 5M as the video bitrate target")
    }

    @Test
    fun `nvenc mobile keeps H264 High profile`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.NVENC).args
        assertContainsPair(args, "-profile:v", "high")
    }

    // ---- libx264 (CPU) mobile preset ----

    @Test
    fun `libx264 mobile uses CRF 23, not fixed ABR`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.CPU).args
        assertContainsPair(args, "-crf", "23")
    }

    @Test
    fun `libx264 mobile caps max bitrate at 5M`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.CPU).args
        assertContainsPair(args, "-maxrate", "5M")
    }

    @Test
    fun `libx264 mobile does NOT set the old 5M fixed bitrate target`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.CPU).args
        assertFalse(hasPair(args, "-b:v", "5M"),
            "Mobile preset must not hard-code 5M as the video bitrate target")
    }

    // ---- QSV mobile preset ----

    @Test
    fun `qsv mobile uses global_quality 23`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.QSV).args
        assertContainsPair(args, "-global_quality", "23")
    }

    @Test
    fun `qsv mobile caps max bitrate at 5M`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.QSV).args
        assertContainsPair(args, "-maxrate", "5M")
    }

    @Test
    fun `qsv mobile does NOT set the old 5M fixed bitrate target`() {
        val args = EncoderProfile.mobileVariant(EncoderProfile.QSV).args
        assertFalse(hasPair(args, "-b:v", "5M"))
    }

    // ---- Name prefix ----

    @Test
    fun `mobile variant name is prefixed with mobile_`() {
        assertEquals("mobile_nvenc", EncoderProfile.mobileVariant(EncoderProfile.NVENC).name)
        assertEquals("mobile_qsv", EncoderProfile.mobileVariant(EncoderProfile.QSV).name)
        assertEquals("mobile_cpu", EncoderProfile.mobileVariant(EncoderProfile.CPU).name)
    }

    @Test
    fun `mobile variant preserves base ffmpegEncoder name`() {
        assertEquals("h264_nvenc", EncoderProfile.mobileVariant(EncoderProfile.NVENC).ffmpegEncoder)
        assertEquals("h264_qsv", EncoderProfile.mobileVariant(EncoderProfile.QSV).ffmpegEncoder)
        assertEquals("libx264", EncoderProfile.mobileVariant(EncoderProfile.CPU).ffmpegEncoder)
    }

    // ---- Helpers ----

    /** True if [args] contains [flag] immediately followed by [value]. */
    private fun hasPair(args: List<String>, flag: String, value: String): Boolean {
        val idx = args.indexOf(flag)
        return idx >= 0 && idx + 1 < args.size && args[idx + 1] == value
    }

    private fun assertContainsPair(args: List<String>, flag: String, value: String) {
        assertTrue(hasPair(args, flag, value),
            "Expected args to contain '$flag $value'; got: ${args.joinToString(" ")}")
    }
}
