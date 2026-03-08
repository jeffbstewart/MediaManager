package net.stewart.transcodebuddy

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.stewart.transcode.ForBrowserProbeResult
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class ClaimResponse(
    val leaseId: Long?,
    val transcodeId: Long?,
    val relativePath: String?,
    val fileSizeBytes: Long?,
    val expiresAt: String?,
    val leaseType: String = "TRANSCODE"
)

class BuddyApiClient(private val config: BuddyConfig) {

    private val log = LoggerFactory.getLogger(BuddyApiClient::class.java)
    private val gson = Gson()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun claimWork(skipTypes: Set<String> = emptySet()): ClaimResponse? {
        val data = mutableMapOf<String, Any>("buddy_name" to config.buddyName)
        if (skipTypes.isNotEmpty()) data["skip_types"] = skipTypes.toList()
        val body = gson.toJson(data)
        val json = post("claim", body) ?: return null
        val leaseId = json.get("lease_id")
        if (leaseId == null || leaseId.isJsonNull) return null
        return ClaimResponse(
            leaseId = leaseId.asLong,
            transcodeId = json.get("transcode_id")?.asLong,
            relativePath = json.get("relative_path")?.asString,
            fileSizeBytes = json.get("file_size_bytes")?.asLong,
            expiresAt = json.get("expires_at")?.asString,
            leaseType = json.get("lease_type")?.asString ?: "TRANSCODE"
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

    fun reportFailure(leaseId: Long, error: String?): Boolean {
        val data = mutableMapOf<String, Any>("lease_id" to leaseId)
        if (error != null) data["error"] = error
        return post("fail", gson.toJson(data)) != null
    }

    fun heartbeat(leaseId: Long): String? {
        val data = mapOf("lease_id" to leaseId)
        val json = post("heartbeat", gson.toJson(data))
        return json?.get("expires_at")?.asString
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
                log.warn("POST /buddy/{} returned {}: {}", endpoint, response.statusCode(), response.body())
                return null
            }
            JsonParser.parseString(response.body()).asJsonObject
        } catch (e: Exception) {
            log.error("POST /buddy/{} failed: {}", endpoint, e.message)
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
