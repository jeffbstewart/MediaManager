package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.ForBrowserProbe
import net.stewart.mediamanager.entity.ForBrowserProbeStream
import net.stewart.transcode.ForBrowserProbeResult
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Records and manages ForBrowser probe data for playback diagnostics.
 * Probe rows are keyed by transcode_id (one probe per ForBrowser file).
 */
object ForBrowserProbeService {

    private val log = LoggerFactory.getLogger(ForBrowserProbeService::class.java)

    /**
     * Records probe data for a ForBrowser MP4. Upserts: if a probe already exists
     * for this transcode_id (from a previous transcode), it is replaced.
     */
    fun recordProbe(
        transcodeId: Long,
        relativePath: String,
        probeResult: ForBrowserProbeResult,
        encoder: String?,
        fileSize: Long?
    ) {
        // Delete existing probe for this transcode (upsert)
        deleteForTranscode(transcodeId)

        val probe = ForBrowserProbe(
            transcode_id = transcodeId,
            relative_path = relativePath,
            duration_secs = probeResult.durationSecs,
            stream_count = probeResult.streamCount,
            file_size_bytes = fileSize,
            encoder = encoder,
            raw_output = probeResult.rawOutput,
            probed_at = LocalDateTime.now()
        )
        probe.save()

        for (stream in probeResult.streams) {
            ForBrowserProbeStream(
                probe_id = probe.id!!,
                stream_index = stream.index,
                stream_type = stream.type,
                codec = stream.codec,
                width = stream.width,
                height = stream.height,
                sar_num = stream.sarNum,
                sar_den = stream.sarDen,
                fps = stream.fps,
                channels = stream.channels,
                channel_layout = stream.channelLayout,
                sample_rate = stream.sampleRate,
                bitrate_kbps = stream.bitrateKbps,
                raw_line = stream.rawLine
            ).save()
        }

        log.info("Recorded probe for transcode_id={}: {} streams, encoder={}",
            transcodeId, probeResult.streamCount, encoder)
    }

    /**
     * Deletes probe data for a specific transcode (e.g., before re-transcode).
     * The ON DELETE CASCADE on forbrowser_probe_stream handles child rows.
     */
    fun deleteForTranscode(transcodeId: Long) {
        val existing = ForBrowserProbe.findAll()
            .filter { it.transcode_id == transcodeId }
        for (probe in existing) {
            probe.delete()
        }
    }

    /**
     * Returns true if a probe row exists for the given transcode_id.
     */
    fun hasProbe(transcodeId: Long): Boolean {
        return ForBrowserProbe.findAll().any { it.transcode_id == transcodeId }
    }

    /**
     * Deletes probe rows where the ForBrowser file no longer exists on disk.
     */
    fun cleanupStale(nasRoot: String): Int {
        val forBrowserRoot = java.io.File(nasRoot, "ForBrowser")
        var cleaned = 0
        for (probe in ForBrowserProbe.findAll()) {
            val file = java.io.File(forBrowserRoot, probe.relative_path)
            if (!file.exists()) {
                log.info("Cleaning stale probe for missing file: {}", probe.relative_path)
                probe.delete()
                cleaned++
            }
        }
        if (cleaned > 0) {
            log.info("Cleaned {} stale probe rows", cleaned)
        }
        return cleaned
    }

    /**
     * Returns all probes with their streams, for diagnostic reporting.
     */
    fun getAllProbesWithStreams(): List<ProbeWithStreams> {
        val probes = ForBrowserProbe.findAll()
        val allStreams = ForBrowserProbeStream.findAll()
        val streamsByProbe = allStreams.groupBy { it.probe_id }

        return probes.map { probe ->
            ProbeWithStreams(probe, streamsByProbe[probe.id!!] ?: emptyList())
        }.sortedBy { it.probe.relative_path }
    }

    /**
     * Returns a summary of distinct encoding profiles across all probed files.
     */
    fun getProfileSummary(): List<ProfileGroup> {
        val all = getAllProbesWithStreams()
        return all.groupBy { pw ->
            val video = pw.streams.firstOrNull { it.stream_type == "video" }
            val audio = pw.streams.firstOrNull { it.stream_type == "audio" }
            ProfileKey(
                videoCodec = video?.codec,
                width = video?.width,
                height = video?.height,
                audioCodec = audio?.codec,
                channels = audio?.channels,
                sampleRate = audio?.sample_rate,
                streamCount = pw.probe.stream_count
            )
        }.map { (key, probes) ->
            ProfileGroup(key, probes.size, probes.map { it.probe.relative_path })
        }.sortedByDescending { it.count }
    }
}

data class ProbeWithStreams(
    val probe: ForBrowserProbe,
    val streams: List<ForBrowserProbeStream>
)

data class ProfileKey(
    val videoCodec: String?,
    val width: Int?,
    val height: Int?,
    val audioCodec: String?,
    val channels: Int?,
    val sampleRate: Int?,
    val streamCount: Int
)

data class ProfileGroup(
    val profile: ProfileKey,
    val count: Int,
    val files: List<String>
)
