package net.stewart.transcode

import java.io.File

/**
 * Single source of truth for building FFmpeg transcode commands.
 * Used by both TranscoderAgent (server-side, CPU) and TranscodeWorker (buddy, GPU).
 */
object TranscodeCommand {

    /**
     * Result of deciding how to transcode a file.
     * @param videoArgs FFmpeg arguments for video encoding (e.g., ["-c:v", "copy"] or encoder-specific args)
     * @param encoderName Name for logging/reporting (e.g., "copy", "libx264", "h264_nvenc")
     */
    data class VideoDecision(
        val videoArgs: List<String>,
        val encoderName: String
    )

    /**
     * Decides how to handle the video stream based on probe results and the chosen encoder.
     *
     * - H.264 sources with no problematic attributes (SAR, fps) -> copy video as-is
     * - Everything else -> re-encode with the given encoder profile + appropriate level
     */
    fun decideVideoCodec(probe: VideoProbeResult, encoder: EncoderProfile): VideoDecision {
        return if (probe.browserSafe && !probe.needsVideoFilter) {
            VideoDecision(listOf("-c:v", "copy"), "copy")
        } else {
            val level = selectLevel(probe)
            VideoDecision(encoder.args + listOf("-level:v", level), encoder.ffmpegEncoder)
        }
    }

    /**
     * Selects the appropriate H.264 level based on source resolution.
     * Level 4.1 supports up to 1920x1080@30. Level 5.1 supports up to 4096x2160@30.
     */
    private fun selectLevel(probe: VideoProbeResult): String {
        val w = probe.width ?: 0
        val h = probe.height ?: 0
        return if (w > 1920 || h > 1080) "5.1" else "4.1"
    }

    /**
     * Builds the video filter chain needed for Roku compatibility.
     * Returns empty list if no filters needed, or ["-vf", "filter1,filter2,..."].
     *
     * Handles:
     * - Interlaced video: deinterlaces with yadif (must come before scale)
     * - Anamorphic SAR: scales to square pixels (e.g., 720x480 SAR 8:9 -> 640x480 SAR 1:1)
     */
    fun buildVideoFilters(probe: VideoProbeResult): List<String> {
        val filters = mutableListOf<String>()
        if (probe.interlaced) {
            filters.add("yadif")
        }
        if (probe.isAnamorphic) {
            // Round to even dimensions — libx264/NVENC require even width and height.
            // Without rounding, odd SAR ratios like 853:720 produce odd widths (e.g., 853px)
            // which crash the encoder.
            filters.add("scale=trunc(iw*sar/2)*2:trunc(ih/2)*2")
            filters.add("setsar=1:1")
        }
        if (filters.isEmpty()) return emptyList()
        return listOf("-vf", filters.joinToString(","))
    }

    /**
     * Returns FFmpeg args for forcing a constant standard frame rate.
     * Always applied when re-encoding to prevent NVENC from producing VFR output
     * (e.g., MPEG-2 telecined DVD at 29.97fps -> NVENC outputs 24.20fps VFR).
     * Snaps to the nearest standard rate.
     */
    fun buildFpsArgs(probe: VideoProbeResult): List<String> {
        if (probe.fps == null) return emptyList()
        val nearest = STANDARD_FPS.minByOrNull { kotlin.math.abs(it - probe.fps) } ?: return emptyList()
        return listOf("-r", "%.3f".format(nearest))
    }

    /**
     * Builds an FFmpeg command for ForMobile transcoding (1080p, 5 Mbps ABR).
     * Always re-encodes video (never copies) to ensure consistent output.
     * Caps resolution at 1080p without upscaling smaller sources.
     */
    fun buildMobile(
        ffmpegPath: String,
        sourceFile: File,
        outputFile: File,
        probe: VideoProbeResult,
        encoder: EncoderProfile
    ): Pair<List<String>, String> {
        val mobileEncoder = EncoderProfile.mobileVariant(encoder)

        val filters = mutableListOf<String>()
        if (probe.interlaced) filters.add("yadif")
        if (probe.isAnamorphic) {
            filters.add("scale=trunc(iw*sar/2)*2:trunc(ih/2)*2")
            filters.add("setsar=1:1")
        }
        // Cap at 1080p without upscaling; maintain aspect ratio; round to even
        filters.add("scale='min(1920,iw)':'min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2")

        val command = mutableListOf(
            ffmpegPath,
            "-i", sourceFile.absolutePath,
            "-map", "0:v:0",
            "-map", "0:a:0",
            "-dn"
        ).apply {
            addAll(mobileEncoder.args)
            addAll(listOf("-level:v", "4.1"))
            addAll(listOf("-vf", filters.joinToString(",")))
            addAll(buildFpsArgs(probe))
            addAll(listOf("-bsf:v", "filter_units=remove_types=6"))
            addAll(listOf(
                "-c:a", "aac",
                "-ac", "2",
                "-ar", "48000",
                "-b:a", "160k",
                "-map_chapters", "-1",
                "-movflags", "+faststart",
                "-threads", "0",
                "-f", "mp4",
                "-y",
                outputFile.absolutePath
            ))
        }

        return Pair(command, mobileEncoder.ffmpegEncoder)
    }

    /**
     * Builds the complete FFmpeg transcode command.
     *
     * @param ffmpegPath path to ffmpeg binary
     * @param sourceFile input media file
     * @param outputFile output .tmp file (caller renames to .mp4 on success)
     * @param probe results from probing the source file
     * @param encoder the encoder profile to use (CPU, NVENC, QSV)
     * @return the full command list and the encoder name used
     */
    fun build(
        ffmpegPath: String,
        sourceFile: File,
        outputFile: File,
        probe: VideoProbeResult,
        encoder: EncoderProfile
    ): Pair<List<String>, String> {
        val decision = decideVideoCodec(probe, encoder)

        val command = mutableListOf(
            ffmpegPath,
            "-i", sourceFile.absolutePath,
            "-map", "0:v:0",
            "-map", "0:a:0",
            "-dn"
        ).apply {
            addAll(decision.videoArgs)
            if (decision.encoderName != "copy") {
                addAll(buildVideoFilters(probe))
                addAll(buildFpsArgs(probe))
                // Strip SEI NAL units — NVENC/QSV emit extra SEI that Roku's strict
                // decoder rejects. Harmless for libx264 (just removes optional info).
                addAll(listOf("-bsf:v", "filter_units=remove_types=6"))
            }
            addAll(listOf(
                "-c:a", "aac",
                "-ac", "2",
                "-ar", "48000",
                "-b:a", "192k",
                "-map_chapters", "-1",
                "-movflags", "+faststart",
                "-threads", "0",
                "-f", "mp4",
                "-y",
                outputFile.absolutePath
            ))
        }

        return Pair(command, decision.encoderName)
    }
}
