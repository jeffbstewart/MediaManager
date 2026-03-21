package net.stewart.transcodebuddy

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.stewart.transcode.ChapterInfo
import net.stewart.transcode.ForBrowserProbeResult
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A single lease within a bundle. */
data class LeaseInfo(
    val leaseId: Long,
    val leaseType: String,
    val expiresAt: String?
)

/** Bundle response from the claim endpoint: all outstanding work for one file. */
data class BundleResponse(
    val transcodeId: Long,
    val relativePath: String,
    val fileSizeBytes: Long,
    val leases: List<LeaseInfo>
)

class BuddyApiClient(private val config: BuddyConfig) {

    private val log = LoggerFactory.getLogger(BuddyApiClient::class.java)
    private val gson = Gson()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * Claims a bundle of work: all outstanding leases for the highest-priority file.
     * Sends cached_transcode_ids so the server prefers files we already have locally.
     */
    fun claimWork(skipTypes: Set<String> = emptySet(), cachedTranscodeIds: Set<Long> = emptySet()): BundleResponse? {
        val data = mutableMapOf<String, Any>("buddy_name" to config.buddyName)
        if (skipTypes.isNotEmpty()) data["skip_types"] = skipTypes.toList()
        if (cachedTranscodeIds.isNotEmpty()) data["cached_transcode_ids"] = cachedTranscodeIds.toList()
        val body = gson.toJson(data)
        log.info("Claiming work (skipTypes={}, cachedIds={})", skipTypes, cachedTranscodeIds.size)
        val json = post("claim", body)
        if (json == null) {
            log.info("Claim returned null (server returned non-200 or request failed)")
            return null
        }
        val transcodeId = json.get("transcode_id")
        if (transcodeId == null || transcodeId.isJsonNull) {
            log.info("Claim returned no work (transcode_id is null/missing in response)")
            return null
        }

        val leases = json.getAsJsonArray("leases")?.map { el ->
            val obj = el.asJsonObject
            LeaseInfo(
                leaseId = obj.get("lease_id").asLong,
                leaseType = obj.get("lease_type").asString,
                expiresAt = obj.get("expires_at")?.asString
            )
        } ?: emptyList()

        if (leases.isEmpty()) {
            log.warn("Claim response had transcode_id={} but empty leases array", transcodeId)
            return null
        }

        log.info("Claimed transcode_id={} with {} lease(s): {}", transcodeId.asLong, leases.size,
            leases.joinToString { "${it.leaseType}(${it.leaseId})" })
        return BundleResponse(
            transcodeId = transcodeId.asLong,
            relativePath = json.get("relative_path").asString,
            fileSizeBytes = json.get("file_size_bytes")?.asLong ?: 0,
            leases = leases
        )
    }

    fun reportProgress(leaseId: Long, percent: Int, encoder: String?): String? {
        val data = mutableMapOf<String, Any>(
            "lease_id" to leaseId,
            "percent" to percent
        )
        if (encoder != null) data["encoder"] = encoder
        val json = post("progress", gson.toJson(data))
        return json?.get("expires_at")?.asString
    }

    fun reportComplete(leaseId: Long, encoder: String?, probeResult: ForBrowserProbeResult? = null, fileSize: Long? = null): Boolean {
        val data = mutableMapOf<String, Any>("lease_id" to leaseId)
        if (encoder != null) data["encoder"] = encoder
        if (probeResult != null) {
            val probeMap = mutableMapOf<String, Any?>(
                "duration_secs" to probeResult.durationSecs,
                "raw_output" to probeResult.rawOutput,
                "file_size_bytes" to fileSize,
                "streams" to probeResult.streams.map { s ->
                    mutableMapOf<String, Any?>(
                        "index" to s.index,
                        "type" to s.type,
                        "codec" to s.codec,
                        "width" to s.width,
                        "height" to s.height,
                        "sar_num" to s.sarNum,
                        "sar_den" to s.sarDen,
                        "fps" to s.fps,
                        "channels" to s.channels,
                        "channel_layout" to s.channelLayout,
                        "sample_rate" to s.sampleRate,
                        "bitrate_kbps" to s.bitrateKbps,
                        "raw_line" to s.rawLine
                    )
                }
            )
            data["probe"] = probeMap
        }
        return post("complete", gson.toJson(data)) != null
    }

    fun reportCompleteWithChapters(leaseId: Long, chapters: List<ChapterInfo>): Boolean {
        val data = mutableMapOf<String, Any>(
            "lease_id" to leaseId,
            "chapters" to chapters.map { ch ->
                mapOf(
                    "number" to ch.number,
                    "start_seconds" to ch.startSeconds,
                    "end_seconds" to ch.endSeconds,
                    "title" to (ch.title ?: "")
                )
            }
        )
        return post("complete", gson.toJson(data)) != null
    }

    fun reportFailure(leaseId: Long, error: String?): Boolean {
        val data = mutableMapOf<String, Any>("lease_id" to leaseId)
        if (error != null) data["error"] = error
        return post("fail", gson.toJson(data)) != null
    }

    /** Heartbeat multiple leases at once (keeps all bundle leases alive). */
    fun heartbeatMultiple(leaseIds: List<Long>): Boolean {
        val data = mapOf("lease_ids" to leaseIds)
        return post("heartbeat", gson.toJson(data)) != null
    }

    /** Check which transcode IDs have pending work (used for cache cleanup on startup). */
    fun checkPending(transcodeIds: List<Long>): List<Long> {
        val data = mapOf("transcode_ids" to transcodeIds)
        val json = post("check-pending", gson.toJson(data)) ?: return emptyList()
        return json.getAsJsonArray("pending")?.map { it.asLong } ?: emptyList()
    }

    fun releaseLeases(): Int {
        val data = mapOf("buddy_name" to config.buddyName)
        val json = post("release", gson.toJson(data))
        return json?.get("released")?.asInt ?: 0
    }

    fun getStatus(): JsonObject? {
        return get("status")
    }

    private fun post(endpoint: String, body: String): JsonObject? {
        val url = "${config.serverUrl}/buddy/$endpoint?key=${config.apiKey}"
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("POST /buddy/{} returned {}: {}", endpoint, response.statusCode(), response.body().take(500))
                return null
            }
            log.debug("POST /buddy/{} -> 200 ({} bytes)", endpoint, response.body().length)
            JsonParser.parseString(response.body()).asJsonObject
        } catch (e: Exception) {
            log.error("POST /buddy/{} failed: {} ({})", endpoint, e.message, e.javaClass.simpleName)
            null
        }
    }

    private fun get(endpoint: String): JsonObject? {
        val url = "${config.serverUrl}/buddy/$endpoint?key=${config.apiKey}"
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("GET /buddy/{} returned {}: {}", endpoint, response.statusCode(), response.body())
                return null
            }
            JsonParser.parseString(response.body()).asJsonObject
        } catch (e: Exception) {
            log.error("GET /buddy/{} failed: {}", endpoint, e.message)
            null
        }
    }
}
