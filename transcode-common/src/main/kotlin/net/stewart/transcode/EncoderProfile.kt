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
         * Current version of the mobile encoder preset. Bump this whenever the
         * preset changes in a way that produces materially different output
         * (size, quality, codec); existing ForMobile files with a lower version
         * stamped on their Transcode row will be picked up for re-transcoding
         * at the lowest priority.
         *
         * History:
         *   1 — ABR at fixed 5 Mbps, 1080p cap. Produced bloated SD output.
         *   2 — CQ/CRF 23 with 5 Mbps maxrate cap, 720p cap. Content-adaptive.
         */
        const val CURRENT_MOBILE_ENCODER_VERSION = 2

        /**
         * Returns a mobile-optimized variant of the given encoder profile.
         *
         * Uses constant-quality VBR (CQ/CRF 23) rather than fixed ABR so file
         * size scales with content complexity and output resolution — an SD
         * source doesn't burn 5 Mbps just because the preset allows it. A
         * 5 Mbps maxrate cap is kept as a safety ceiling for pathological
         * complex scenes. AAC stereo 160 k for audio.
         *
         * Output resolution is capped at 720p in TranscodeCommand.buildMobile
         * (mobile devices don't benefit from 1080p on small screens).
         */
        fun mobileVariant(base: EncoderProfile): EncoderProfile {
            val args = when (base.name) {
                "nvenc" -> listOf(
                    "-pix_fmt", "yuv420p", "-c:v", "h264_nvenc",
                    "-preset", "p7", "-rc", "vbr",
                    "-cq", "23", "-b:v", "0",
                    "-maxrate", "5M", "-bufsize", "10M",
                    "-profile:v", "high", "-rc-lookahead", "32"
                )
                "qsv" -> listOf(
                    "-pix_fmt", "yuv420p", "-c:v", "h264_qsv",
                    "-preset", "veryslow",
                    "-global_quality", "23",
                    "-maxrate", "5M", "-bufsize", "10M",
                    "-profile:v", "high"
                )
                "videotoolbox" -> listOf(
                    // VideoToolbox doesn't support CRF-style quality. Use a lower
                    // ABR target appropriate for mobile (vs. the 8M base profile).
                    "-pix_fmt", "yuv420p", "-c:v", "h264_videotoolbox",
                    "-b:v", "1500k", "-maxrate", "3M", "-bufsize", "6M",
                    "-profile:v", "high"
                )
                else -> listOf(
                    "-pix_fmt", "yuv420p", "-c:v", "libx264",
                    "-preset", "medium",
                    "-crf", "23",
                    "-maxrate", "5M", "-bufsize", "10M",
                    "-profile:v", "high"
                )
            }
            return EncoderProfile("mobile_${base.name}", base.ffmpegEncoder, args)
        }
    }
}
