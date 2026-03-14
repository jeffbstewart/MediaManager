package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.service.BuddyKeyService
import net.stewart.mediamanager.service.ForBrowserProbeService
import net.stewart.mediamanager.service.ReclassifyService
import net.stewart.mediamanager.service.TranscodeLeaseService
import net.stewart.transcode.ForBrowserProbeResult
import net.stewart.transcode.StreamInfo
import org.slf4j.LoggerFactory

/**
 * REST API for transcode buddy workers.
 *
 * All endpoints require authentication via `?key=` query parameter or
 * `X-Buddy-Key` header, validated against bcrypt-hashed keys in the
 * `buddy_api_key` table.
 */
@WebServlet(urlPatterns = ["/buddy/*"])
class BuddyApiServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(BuddyApiServlet::class.java)
    private val gson = Gson()

    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        if (!authenticate(req, resp)) return

        val path = req.pathInfo?.removePrefix("/") ?: ""
        try {
            when (path) {
                "claim" -> handleClaim(req, resp)
                "progress" -> handleProgress(req, resp)
                "complete" -> handleComplete(req, resp)
                "fail" -> handleFail(req, resp)
                "heartbeat" -> handleHeartbeat(req, resp)
                "release" -> handleRelease(req, resp)
                "clear-failures" -> handleClearFailures(resp)
                "reclassify" -> handleReclassify(req, resp)
                else -> resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            }
        } catch (e: Exception) {
            log.error("Buddy API error on /{}: {}", path, e.message, e)
            sendJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                mapOf("error" to "Internal server error"))
        }
    }

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        if (!authenticate(req, resp)) return

        val path = req.pathInfo?.removePrefix("/") ?: ""
        when (path) {
            "status" -> handleStatus(resp)
            "failures" -> handleFailures(resp)
            "probes" -> handleProbes(resp)
            "probe-profiles" -> handleProbeProfiles(resp)
            else -> resp.sendError(HttpServletResponse.SC_NOT_FOUND)
        }
    }

    private fun handleClaim(req: HttpServletRequest, resp: HttpServletResponse) {
        val body = parseBody(req)
        val buddyName = body?.get("buddy_name")?.asString
        if (buddyName.isNullOrBlank()) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                mapOf("error" to "buddy_name is required"))
            return
        }

        val skipTypes = body.get("skip_types")?.asJsonArray
            ?.map { it.asString }?.toSet() ?: emptySet()

        val lease = TranscodeLeaseService.claimWork(buddyName, skipTypes)
        if (lease == null) {
            sendJson(resp, HttpServletResponse.SC_OK, mapOf(
                "lease_id" to null,
                "message" to "No work available"
            ))
            return
        }

        sendJson(resp, HttpServletResponse.SC_OK, mapOf(
            "lease_id" to lease.id,
            "transcode_id" to lease.transcode_id,
            "relative_path" to lease.relative_path,
            "file_size_bytes" to lease.file_size_bytes,
            "expires_at" to lease.expires_at?.toString(),
            "lease_type" to lease.lease_type
        ))
    }

    private fun handleProgress(req: HttpServletRequest, resp: HttpServletResponse) {
        val body = parseBody(req)
        val leaseId = body?.get("lease_id")?.asLong
        val percent = body?.get("percent")?.asInt ?: 0
        val encoder = body?.get("encoder")?.asString

        if (leaseId == null) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                mapOf("error" to "lease_id is required"))
            return
        }

        val lease = TranscodeLeaseService.reportProgress(leaseId, percent, encoder)
        if (lease == null) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND,
                mapOf("error" to "Lease not found or not active"))
            return
        }

        sendJson(resp, HttpServletResponse.SC_OK, mapOf(
            "ok" to true,
            "expires_at" to lease.expires_at?.toString()
        ))
    }

    private fun handleComplete(req: HttpServletRequest, resp: HttpServletResponse) {
        val body = parseBody(req)
        val leaseId = body?.get("lease_id")?.asLong
        val encoder = body?.get("encoder")?.asString

        if (leaseId == null) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                mapOf("error" to "lease_id is required"))
            return
        }

        val lease = TranscodeLeaseService.reportComplete(leaseId, encoder)
        if (lease == null) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND,
                mapOf("error" to "Lease not found or not active"))
            return
        }

        // Parse optional probe data from the complete request
        val probeObj = body.getAsJsonObject("probe")
        if (probeObj != null) {
            try {
                val rawStreams = probeObj.getAsJsonArray("streams")
                if (rawStreams != null && rawStreams.size() > 100) {
                    log.warn("Probe data rejected: stream array too large ({} items, max 100)", rawStreams.size())
                    sendJson(resp, HttpServletResponse.SC_OK, mapOf("ok" to true))
                    return
                }
                val streams = rawStreams?.map { streamEl ->
                    val s = streamEl.asJsonObject
                    StreamInfo(
                        index = s.get("index")?.asInt ?: 0,
                        type = s.get("type")?.asString ?: "",
                        codec = s.get("codec")?.asString,
                        width = s.get("width")?.asInt,
                        height = s.get("height")?.asInt,
                        sarNum = s.get("sar_num")?.asInt,
                        sarDen = s.get("sar_den")?.asInt,
                        fps = s.get("fps")?.asDouble,
                        channels = s.get("channels")?.asInt,
                        channelLayout = s.get("channel_layout")?.asString,
                        sampleRate = s.get("sample_rate")?.asInt,
                        bitrateKbps = s.get("bitrate_kbps")?.asInt,
                        rawLine = s.get("raw_line")?.asString ?: ""
                    )
                } ?: emptyList()

                val probeResult = ForBrowserProbeResult(
                    durationSecs = probeObj.get("duration_secs")?.asDouble,
                    streams = streams,
                    rawOutput = probeObj.get("raw_output")?.asString ?: ""
                )

                ForBrowserProbeService.recordProbe(
                    transcodeId = lease.transcode_id,
                    relativePath = lease.relative_path,
                    probeResult = probeResult,
                    encoder = encoder,
                    fileSize = probeObj.get("file_size_bytes")?.asLong
                )
            } catch (e: Exception) {
                log.warn("Failed to parse probe data from buddy complete: {}", e.message)
            }
        }

        sendJson(resp, HttpServletResponse.SC_OK, mapOf("ok" to true))
    }

    private fun handleFail(req: HttpServletRequest, resp: HttpServletResponse) {
        val body = parseBody(req)
        val leaseId = body?.get("lease_id")?.asLong
        val errorMessage = body?.get("error")?.asString

        if (leaseId == null) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                mapOf("error" to "lease_id is required"))
            return
        }

        val lease = TranscodeLeaseService.reportFailure(leaseId, errorMessage)
        if (lease == null) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND,
                mapOf("error" to "Lease not found or not active"))
            return
        }

        sendJson(resp, HttpServletResponse.SC_OK, mapOf("ok" to true))
    }

    private fun handleHeartbeat(req: HttpServletRequest, resp: HttpServletResponse) {
        val body = parseBody(req)
        val leaseId = body?.get("lease_id")?.asLong

        if (leaseId == null) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                mapOf("error" to "lease_id is required"))
            return
        }

        val lease = TranscodeLeaseService.heartbeat(leaseId)
        if (lease == null) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND,
                mapOf("error" to "Lease not found or not active"))
            return
        }

        sendJson(resp, HttpServletResponse.SC_OK, mapOf(
            "ok" to true,
            "expires_at" to lease.expires_at?.toString()
        ))
    }

    private fun handleRelease(req: HttpServletRequest, resp: HttpServletResponse) {
        val body = parseBody(req)
        val buddyName = body?.get("buddy_name")?.asString
        if (buddyName.isNullOrBlank()) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                mapOf("error" to "buddy_name is required"))
            return
        }

        val released = TranscodeLeaseService.releaseLeases(buddyName)
        sendJson(resp, HttpServletResponse.SC_OK, mapOf(
            "ok" to true,
            "released" to released
        ))
    }

    private fun handleStatus(resp: HttpServletResponse) {
        val summary = TranscodeLeaseService.getStatusSummary()

        sendJson(resp, HttpServletResponse.SC_OK, mapOf(
            "pending" to TranscodeLeaseService.countPendingTranscodes(),
            "active_leases" to summary.activeLeases,
            "completed_today" to summary.completedToday,
            "poison_pills" to summary.poisonPills
        ))
    }

    private fun handleFailures(resp: HttpServletResponse) {
        val failedLeases = net.stewart.mediamanager.entity.TranscodeLease.findAll()
            .filter { it.status == net.stewart.mediamanager.entity.LeaseStatus.FAILED.name ||
                      it.status == net.stewart.mediamanager.entity.LeaseStatus.EXPIRED.name }
            .sortedByDescending { it.completed_at }

        sendJson(resp, HttpServletResponse.SC_OK, failedLeases.map { lease ->
            mapOf(
                "lease_id" to lease.id,
                "transcode_id" to lease.transcode_id,
                "buddy_name" to lease.buddy_name,
                "relative_path" to lease.relative_path,
                "status" to lease.status,
                "error_message" to lease.error_message,
                "completed_at" to lease.completed_at?.toString()
            )
        })
    }

    private fun handleClearFailures(resp: HttpServletResponse) {
        val cleared = TranscodeLeaseService.clearAllFailures()
        sendJson(resp, HttpServletResponse.SC_OK, mapOf(
            "ok" to true,
            "cleared" to cleared
        ))
    }

    private fun handleProbes(resp: HttpServletResponse) {
        val probes = ForBrowserProbeService.getAllProbesWithStreams()
        sendJson(resp, HttpServletResponse.SC_OK, probes.map { pw ->
            mapOf(
                "transcode_id" to pw.probe.transcode_id,
                "relative_path" to pw.probe.relative_path,
                "duration_secs" to pw.probe.duration_secs,
                "stream_count" to pw.probe.stream_count,
                "file_size_bytes" to pw.probe.file_size_bytes,
                "encoder" to pw.probe.encoder,
                "probed_at" to pw.probe.probed_at?.toString(),
                "streams" to pw.streams.map { s ->
                    mapOf(
                        "index" to s.stream_index,
                        "type" to s.stream_type,
                        "codec" to s.codec,
                        "width" to s.width,
                        "height" to s.height,
                        "channels" to s.channels,
                        "channel_layout" to s.channel_layout,
                        "sample_rate" to s.sample_rate,
                        "bitrate_kbps" to s.bitrate_kbps
                    )
                }
            )
        })
    }

    private fun handleProbeProfiles(resp: HttpServletResponse) {
        val profiles = ForBrowserProbeService.getProfileSummary()
        sendJson(resp, HttpServletResponse.SC_OK, profiles.map { pg ->
            mapOf(
                "video_codec" to pg.profile.videoCodec,
                "resolution" to if (pg.profile.width != null) "${pg.profile.width}x${pg.profile.height}" else null,
                "audio_codec" to pg.profile.audioCodec,
                "audio_channels" to pg.profile.channels,
                "sample_rate" to pg.profile.sampleRate,
                "stream_count" to pg.profile.streamCount,
                "count" to pg.count,
                "files" to pg.files
            )
        })
    }

    private fun handleReclassify(req: HttpServletRequest, resp: HttpServletResponse) {
        val body = parseBody(req)
        val transcodeId = body?.get("transcode_id")?.asLong
        val targetFormatStr = body?.get("target_format")?.asString

        if (transcodeId == null || targetFormatStr.isNullOrBlank()) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                mapOf("error" to "transcode_id and target_format are required"))
            return
        }

        val targetFormat = try {
            MediaFormat.valueOf(targetFormatStr)
        } catch (e: IllegalArgumentException) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST,
                mapOf("error" to "Invalid target_format: $targetFormatStr. Valid: ${MediaFormat.entries.joinToString()}"))
            return
        }

        try {
            val result = ReclassifyService.reclassify(transcodeId, targetFormat)
            sendJson(resp, HttpServletResponse.SC_OK, mapOf(
                "ok" to true,
                "old_path" to result.oldPath,
                "new_path" to result.newPath,
                "old_format" to result.oldFormat,
                "new_format" to result.newFormat,
                "forbrowser_deleted" to result.forBrowserDeleted,
                "leases_cleared" to result.leasesCleared
            ))
        } catch (e: IllegalArgumentException) {
            log.warn("Reclassify validation error: {}", e.message)
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, mapOf("error" to "Reclassify validation failed"))
        } catch (e: IllegalStateException) {
            log.warn("Reclassify state error: {}", e.message)
            sendJson(resp, HttpServletResponse.SC_CONFLICT, mapOf("error" to "Reclassify precondition failed"))
        }
    }

    private fun authenticate(req: HttpServletRequest, resp: HttpServletResponse): Boolean {
        val providedKey = req.getParameter("key")
            ?: req.getHeader("X-Buddy-Key")

        if (providedKey == null) {
            sendJson(resp, HttpServletResponse.SC_UNAUTHORIZED,
                mapOf("error" to "API key required (query param 'key' or header 'X-Buddy-Key')"))
            return false
        }

        if (!BuddyKeyService.validate(providedKey)) {
            sendJson(resp, HttpServletResponse.SC_UNAUTHORIZED,
                mapOf("error" to "Invalid API key"))
            return false
        }

        return true
    }

    private val MAX_BODY_SIZE = 1_048_576 // 1 MB

    private fun parseBody(req: HttpServletRequest): JsonObject? {
        val contentLength = req.contentLength
        if (contentLength > MAX_BODY_SIZE) return null
        return try {
            val body = req.inputStream.readNBytes(MAX_BODY_SIZE).toString(Charsets.UTF_8)
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun sendJson(resp: HttpServletResponse, status: Int, data: Any) {
        resp.status = status
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.writer.write(gson.toJson(data))
    }
}
