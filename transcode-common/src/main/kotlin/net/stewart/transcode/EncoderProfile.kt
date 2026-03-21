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
        val VIDEOTOOLBOX = EncoderProfile(
            name = "videotoolbox",
            ffmpegEncoder = "h264_videotoolbox",
            args = listOf(
                "-pix_fmt", "yuv420p",
                "-c:v", "h264_videotoolbox",
                "-b:v", "8M",
                "-maxrate", "12M",
                "-bufsize", "16M",
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
            "videotoolbox", "vtb" -> VIDEOTOOLBOX
            "cpu" -> CPU
            else -> null
        }

        /**
         * Returns a mobile-optimized variant of the given encoder profile.
         * Uses ABR at 5 Mbps (max 7.5 Mbps) for predictable file sizes.
         * 1080p H.264 High, AAC stereo 160k.
         */
        fun mobileVariant(base: EncoderProfile): EncoderProfile {
            val args = when (base.name) {
                "nvenc" -> listOf(
                    "-pix_fmt", "yuv420p", "-c:v", "h264_nvenc",
                    "-preset", "p7", "-rc", "vbr",
                    "-b:v", "5M", "-maxrate", "7.5M", "-bufsize", "10M",
                    "-profile:v", "high", "-rc-lookahead", "32"
                )
                "qsv" -> listOf(
                    "-pix_fmt", "yuv420p", "-c:v", "h264_qsv",
                    "-preset", "veryslow",
                    "-b:v", "5M", "-maxrate", "7.5M", "-bufsize", "10M",
                    "-profile:v", "high"
                )
                "videotoolbox" -> listOf(
                    "-pix_fmt", "yuv420p", "-c:v", "h264_videotoolbox",
                    "-b:v", "5M", "-maxrate", "7.5M", "-bufsize", "10M",
                    "-profile:v", "high"
                )
                else -> listOf(
                    "-pix_fmt", "yuv420p", "-c:v", "libx264",
                    "-preset", "medium",
                    "-b:v", "5M", "-maxrate", "7.5M", "-bufsize", "10M",
                    "-profile:v", "high"
                )
            }
            return EncoderProfile("mobile_${base.name}", base.ffmpegEncoder, args)
        }
    }
}
