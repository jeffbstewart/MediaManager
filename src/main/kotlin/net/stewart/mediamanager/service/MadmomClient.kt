package net.stewart.mediamanager.service

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import net.stewart.mediamanager.grpc.AnalyzeRequest
import net.stewart.mediamanager.grpc.AnalyzeResponse
import net.stewart.mediamanager.grpc.Empty
import net.stewart.mediamanager.grpc.HealthStatus
import net.stewart.mediamanager.grpc.MadmomAnalysisGrpcKt
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * gRPC client for the madmom sidecar. Talks to the Python process
 * over plaintext gRPC (HTTP/2) on `localhost:9091` by default — the
 * compose file binds the sidecar's port only to the NAS host's
 * loopback interface so this connection never crosses the wire.
 *
 * The connection target is configurable via the `madmom_sidecar_url`
 * AppConfig key, or the `MADMOM_SIDECAR_URL` env var, both in the
 * form `host:port`. The default matches what docker-compose.yml sets
 * up.
 */
object MadmomClient {

    private val log = LoggerFactory.getLogger(MadmomClient::class.java)
    private const val DEFAULT_TARGET = "localhost:9091"
    private const val CONFIG_KEY = "madmom_sidecar_url"

    @Volatile private var channel: ManagedChannel? = null

    private fun target(): String =
        net.stewart.mediamanager.entity.AppConfig.findAll()
            .firstOrNull { it.config_key == CONFIG_KEY }
            ?.config_val
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("MADMOM_SIDECAR_URL")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TARGET

    @Synchronized
    private fun getChannel(): ManagedChannel {
        val existing = channel
        if (existing != null && !existing.isShutdown) return existing
        val target = target()
        log.info("MadmomClient: opening gRPC channel to {}", target)
        val fresh = ManagedChannelBuilder.forTarget(target)
            .usePlaintext()  // sidecar is on loopback / internal network only
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .build()
        channel = fresh
        return fresh
    }

    /**
     * Returns true when the sidecar responds to Health() within
     * [timeoutMs]. Used by the agent on startup to decide whether to
     * self-disable. Failure is NOT noisy — the sidecar may not be
     * deployed (dev setup), which is a valid configuration.
     */
    fun isAvailable(timeoutMs: Long = 3_000L): Boolean {
        return try {
            val stub = MadmomAnalysisGrpcKt.MadmomAnalysisCoroutineStub(getChannel())
            runBlocking {
                withTimeout(timeoutMs) {
                    val status: HealthStatus = stub.health(Empty.getDefaultInstance())
                    log.info("Madmom sidecar reachable: status={} madmom_version={}",
                        status.status, status.madmomVersion)
                    true
                }
            }
        } catch (e: TimeoutCancellationException) {
            log.warn("Madmom sidecar health check timed out after {}ms", timeoutMs)
            false
        } catch (e: StatusRuntimeException) {
            log.warn("Madmom sidecar unavailable: {} {}", e.status.code, e.status.description)
            false
        } catch (e: Exception) {
            log.warn("Madmom sidecar check failed: {}", e.message)
            false
        }
    }

    /** Result subset exposed to the agent — strips proto boilerplate. */
    data class Rhythm(
        val bpm: Int?,
        val timeSignature: String?,
        val downbeatConfidence: Double?,
        val beatCount: Int?
    )

    /**
     * Send the file path to the sidecar. Returns null on any error,
     * matching Essentia's wrapper convention so the agent's
     * success/failure handling is symmetric.
     *
     * `timeoutMs` covers the full RPC including the sidecar's
     * inference time (10-25 s typical per track). 90 s leaves headroom
     * for long tracks + first-request model-load overhead.
     */
    fun analyze(filePath: String, timeoutMs: Long = 90_000L): Rhythm? {
        return try {
            val stub = MadmomAnalysisGrpcKt.MadmomAnalysisCoroutineStub(getChannel())
            val req = AnalyzeRequest.newBuilder().setFilePath(filePath).build()
            runBlocking {
                val resp: AnalyzeResponse = withTimeout(timeoutMs) { stub.analyze(req) }
                if (!resp.hasBpm() && !resp.hasTimeSignature()) {
                    // Sidecar returned the empty-response sentinel —
                    // it couldn't detect. Caller treats as failure.
                    null
                } else {
                    Rhythm(
                        bpm = if (resp.hasBpm()) resp.bpm else null,
                        timeSignature = if (resp.hasTimeSignature()) resp.timeSignature else null,
                        downbeatConfidence = if (resp.hasDownbeatConfidence()) resp.downbeatConfidence else null,
                        beatCount = if (resp.hasBeatCount()) resp.beatCount else null
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            log.warn("Madmom analyze timed out on {}", filePath)
            null
        } catch (e: StatusRuntimeException) {
            log.warn("Madmom analyze RPC failed on {}: {} {}",
                filePath, e.status.code, e.status.description)
            null
        } catch (e: Exception) {
            log.warn("Madmom analyze unexpected failure on {}: {}", filePath, e.message)
            null
        }
    }

    /** Clean channel shutdown on app stop. */
    fun shutdown() {
        val existing = channel
        if (existing != null) {
            existing.shutdown().awaitTermination(5, TimeUnit.SECONDS)
            channel = null
        }
    }
}
