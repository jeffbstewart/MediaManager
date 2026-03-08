package net.stewart.transcode

/**
 * FFmpeg encoder profile — bundles encoder name with its FFmpeg arguments.
 * Used by both TranscoderAgent (server) and TranscodeWorker (buddy client).
 */
data class EncoderProfile(
    val name: String,
    val ffmpegEncoder: String,
    val args: List<String>
) {
    companion object {
        val NVENC = EncoderProfile(
            name = "nvenc",
            ffmpegEncoder = "h264_nvenc",
            args = listOf(
                "-pix_fmt", "yuv420p",
                "-c:v", "h264_nvenc",
                "-preset", "p7",
                "-rc", "vbr",
                "-cq", "19",
                "-b:v", "0",
                "-profile:v", "high",
                "-rc-lookahead", "32"
            )
        )
        val QSV = EncoderProfile(
            name = "qsv",
            ffmpegEncoder = "h264_qsv",
            args = listOf(
                "-pix_fmt", "yuv420p",
                "-c:v", "h264_qsv",
                "-preset", "veryslow",
                "-global_quality", "19",
                "-profile:v", "high"
            )
        )
        val CPU = EncoderProfile(
            name = "cpu",
            ffmpegEncoder = "libx264",
            args = listOf(
                "-pix_fmt", "yuv420p",
                "-c:v", "libx264",
                "-preset", "medium",
                "-crf", "18"
            )
        )

        fun byName(name: String): EncoderProfile? = when (name.lowercase()) {
            "nvenc" -> NVENC
            "qsv" -> QSV
            "cpu" -> CPU
            else -> null
        }
    }
}
