package net.stewart.transcodebuddy

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import net.stewart.mediamanager.grpc.*
import net.stewart.transcode.ChapterInfo
import net.stewart.transcode.ForBrowserProbeResult
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A single lease within a bundle. */
data class LeaseInfo(
    val leaseId: Long,
    val leaseType: String,
    val expiresAt: String?
)

/** Bundle response: all outstanding work for one file. */
data class BundleResponse(
    val transcodeId: Long,
    val relativePath: String,
    val fileSizeBytes: Long,
    val leases: List<LeaseInfo>
)

/**
 * gRPC client for the buddy bidirectional streaming protocol.
 *
 * Maintains a single long-lived WorkStream. Exposes synchronous methods matching
 * [BuddyApiClient]'s interface so TranscodeWorker can use it as a drop-in replacement.
 *
 * Thread safety: all sends are synchronized. Work responses (WorkAssignment/NoWork)
 * are routed to a blocking queue consumed by the caller of [claimWork].
 */
class BuddyGrpcClient(private val config: BuddyConfig) {

    private val log = LoggerFactory.getLogger(BuddyGrpcClient::class.java)

    private val channel: ManagedChannel = ManagedChannelBuilder
        .forTarget(config.grpcAddress)
        .apply { if (config.grpcUseTls) useTransportSecurity() else usePlaintext() }
        .build()

    private val asyncStub = BuddyServiceGrpc.newStub(channel)
    private val blockingStub = BuddyServiceGrpc.newBlockingStub(channel)

    /** Queue for work responses (WorkAssignment or NoWork). */
    private val workResponseQueue = LinkedBlockingQueue<ServerMessage>()

    /** Lock for sending on the stream (one sender at a time). */
    private val sendLock = Any()

    @Volatile private var requestObserver: StreamObserver<BuddyMessage>? = null
    @Volatile private var connected = false
    @Volatile var lastPendingCount = 0
        private set

    /** Lease IDs the server has rejected as invalid/expired. */
    private val invalidatedLeases: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // ========================================================================
    // Connection
    // ========================================================================

    /**
     * Opens the WorkStream and sends Connect. Blocks until Connected response.
     * Returns the Connected message, or null if connection failed.
     */
    fun connect(): Connected? {
        val responseQueue = LinkedBlockingQueue<Any>() // Connected or Exception

        val responseObserver = object : StreamObserver<ServerMessage> {
            override fun onNext(message: ServerMessage) {
                when (message.messageCase) {
                    ServerMessage.MessageCase.CONNECTED -> {
                        connected = true
                        lastPendingCount = message.connected.pendingCount
                        responseQueue.put(message.connected)
                        val c = message.connected
                        log.info("Connected to server v{} ({} resumable leases, {} pending)",
                            c.serverVersion, c.resumableLeasesCount, c.pendingCount)
                    }
                    ServerMessage.MessageCase.WORK_ASSIGNMENT,
                    ServerMessage.MessageCase.NO_WORK -> {
                        workResponseQueue.put(message)
                    }
                    ServerMessage.MessageCase.LEASE_EXPIRING -> {
                        val le = message.leaseExpiring
                        log.warn("Lease {} expiring: {}", le.leaseId, le.reason)
                    }
                    ServerMessage.MessageCase.ERROR -> {
                        val err = message.error
                        if (err.code == BuddyErrorCode.BUDDY_ERROR_CODE_INVALID_LEASE) {
                            // Extract lease ID from message like "Lease 12345 not found or not active"
                            val leaseId = Regex("""Lease (\d+)""").find(err.message)?.groupValues?.get(1)?.toLongOrNull()
                            if (leaseId != null) {
                                if (invalidatedLeases.add(leaseId)) {
                                    log.warn("Lease {} invalidated by server: {}", leaseId, err.message)
                                }
                                // else: already known, don't spam the log
                            } else {
                                log.warn("Server error: {} — {}", err.code, err.message)
                            }
                        } else {
                            log.warn("Server error: {} — {}", err.code, err.message)
                        }
                    }
                    else -> {
                        log.warn("Unknown server message: {}", message.messageCase)
                    }
                }
            }

            override fun onError(t: Throwable) {
                connected = false
                val status = Status.fromThrowable(t)
                log.error("WorkStream error: {} — {}", status.code, status.description)
                responseQueue.put(t)
                // Unblock any pending claimWork
                workResponseQueue.put(ServerMessage.getDefaultInstance())
            }

            override fun onCompleted() {
                connected = false
                log.info("WorkStream completed by server")
                // Unblock any pending claimWork
                workResponseQueue.put(ServerMessage.getDefaultInstance())
            }
        }

        requestObserver = asyncStub.workStream(responseObserver)

        // Send Connect
        val connectMsg = BuddyMessage.newBuilder()
            .setConnect(Connect.newBuilder()
                .setApiKey(config.apiKey)
                .setBuddyName(config.buddyName)
                .addAllSkipTypes(buildSkipTypes())
                .addAllCachedTranscodeIds(emptyList()))
            .build()

        synchronized(sendLock) {
            requestObserver?.onNext(connectMsg)
        }

        // Wait for Connected response (30s timeout)
        val response = responseQueue.poll(30, TimeUnit.SECONDS)
        return when (response) {
            is Connected -> response
            is Throwable -> {
                log.error("Connect failed: {}", response.message)
                null
            }
            else -> {
                log.error("Connect timed out")
                null
            }
        }
    }

    private fun buildSkipTypes(): List<BuddyLeaseType> {
        val types = mutableListOf<BuddyLeaseType>()
        val wp = config.whisperPath
        if (wp == null || !java.io.File(wp).exists()) {
            types.add(BuddyLeaseType.BUDDY_LEASE_TYPE_SUBTITLES)
        }
        return types
    }

    // ========================================================================
    // Work claiming (synchronous, blocks for response)
    // ========================================================================

    fun claimWork(skipTypes: Set<String> = emptySet(), cachedTranscodeIds: Set<Long> = emptySet()): BundleResponse? {
        if (!connected) return null

        // Drain any stale responses
        workResponseQueue.clear()

        val requestWork = RequestWork.newBuilder()
            .addAllCachedTranscodeIds(cachedTranscodeIds)
            .build()

        synchronized(sendLock) {
            requestObserver?.onNext(
                BuddyMessage.newBuilder().setRequestWork(requestWork).build()
            ) ?: return null
        }

        // Block for response (30s timeout)
        val response = workResponseQueue.poll(30, TimeUnit.SECONDS) ?: return null

        return when (response.messageCase) {
            ServerMessage.MessageCase.WORK_ASSIGNMENT -> {
                val wa = response.workAssignment
                val leases = wa.leasesList.map { lease ->
                    LeaseInfo(
                        leaseId = lease.leaseId,
                        leaseType = fromBuddyLeaseType(lease.leaseType),
                        expiresAt = null
                    )
                }
                log.info("Claimed transcode_id={} with {} lease(s): {}", wa.transcodeId, leases.size,
                    leases.joinToString { "${it.leaseType}(${it.leaseId})" })
                BundleResponse(
                    transcodeId = wa.transcodeId,
                    relativePath = wa.relativePath,
                    fileSizeBytes = wa.fileSizeBytes,
                    leases = leases
                )
            }
            ServerMessage.MessageCase.NO_WORK -> {
                lastPendingCount = response.noWork.pendingCount
                null
            }
            else -> null
        }
    }

    // ========================================================================
    // Fire-and-forget messages
    // ========================================================================

    fun reportProgress(leaseId: Long, percent: Int, encoder: String?): String? {
        send(BuddyMessage.newBuilder()
            .setReportProgress(ReportProgress.newBuilder()
                .setLeaseId(leaseId)
                .setPercent(percent)
                .apply { if (encoder != null) setEncoder(encoder) })
            .build())
        return null // No response expected; lease renewal is implicit
    }

    fun reportComplete(leaseId: Long, encoder: String?, probeResult: ForBrowserProbeResult? = null, fileSize: Long? = null): Boolean {
        val builder = ReportComplete.newBuilder()
            .setLeaseId(leaseId)
        if (encoder != null) builder.encoder = encoder
        if (probeResult != null) {
            builder.probe = buildProbeData(probeResult, fileSize)
        }
        return send(BuddyMessage.newBuilder().setReportComplete(builder).build())
    }

    fun reportCompleteWithChapters(leaseId: Long, chapters: List<ChapterInfo>): Boolean {
        val builder = ReportComplete.newBuilder()
            .setLeaseId(leaseId)
            .addAllChapters(chapters.map { ch ->
                ChapterData.newBuilder()
                    .setNumber(ch.number)
                    .setStart(PlaybackOffset.newBuilder().setSeconds(ch.startSeconds))
                    .setEnd(PlaybackOffset.newBuilder().setSeconds(ch.endSeconds))
                    .setTitle(ch.title ?: "")
                    .build()
            })
        return send(BuddyMessage.newBuilder().setReportComplete(builder).build())
    }

    fun reportFailure(leaseId: Long, error: String?): Boolean {
        return send(BuddyMessage.newBuilder()
            .setReportFailure(ReportFailure.newBuilder()
                .setLeaseId(leaseId)
                .setErrorMessage(error ?: ""))
            .build())
    }

    fun heartbeatMultiple(leaseIds: List<Long>): Boolean {
        // Heartbeat renews ALL leases for this buddy, so no need for IDs
        return send(BuddyMessage.newBuilder().setHeartbeat(Heartbeat.getDefaultInstance()).build())
    }

    // ========================================================================
    // Unary RPCs
    // ========================================================================

    fun checkPending(transcodeIds: List<Long>): List<Long> {
        return try {
            val response = blockingStub
                .withDeadlineAfter(30, TimeUnit.SECONDS)
                .checkPending(CheckPendingRequest.newBuilder()
                    .addAllTranscodeIds(transcodeIds)
                    .build())
            response.pendingIdsList
        } catch (e: StatusRuntimeException) {
            log.error("checkPending failed: {}", e.status)
            emptyList()
        }
    }

    // ========================================================================
    // Legacy compatibility (TranscodeWorker / Main.kt expect these)
    // ========================================================================

    /** Release leases by closing the stream gracefully. */
    fun releaseLeases(): Int {
        // Graceful close triggers server-side releaseLeases
        try {
            synchronized(sendLock) {
                requestObserver?.onCompleted()
            }
        } catch (_: Exception) {}
        return 0
    }

    /** Returns cached pending count from last Connected or NoWork message. */
    fun getPendingCount(): Int = lastPendingCount

    fun isConnected(): Boolean = connected

    /** Returns true if any of the given lease IDs have been rejected by the server. */
    fun hasInvalidatedLeases(leaseIds: List<Long>): Boolean =
        leaseIds.any { it in invalidatedLeases }

    /** Clears invalidated lease tracking (call after abandoning a bundle). */
    fun clearInvalidatedLeases() = invalidatedLeases.clear()

    fun shutdown() {
        try {
            synchronized(sendLock) {
                requestObserver?.onCompleted()
            }
        } catch (_: Exception) {}
        channel.shutdown()
        channel.awaitTermination(5, TimeUnit.SECONDS)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun send(message: BuddyMessage): Boolean {
        if (!connected) return false
        return try {
            synchronized(sendLock) {
                requestObserver?.onNext(message)
            }
            true
        } catch (e: Exception) {
            log.warn("Send failed: {}", e.message)
            false
        }
    }

    private fun fromBuddyLeaseType(type: BuddyLeaseType): String = when (type) {
        BuddyLeaseType.BUDDY_LEASE_TYPE_TRANSCODE -> "TRANSCODE"
        BuddyLeaseType.BUDDY_LEASE_TYPE_THUMBNAILS -> "THUMBNAILS"
        BuddyLeaseType.BUDDY_LEASE_TYPE_SUBTITLES -> "SUBTITLES"
        BuddyLeaseType.BUDDY_LEASE_TYPE_CHAPTERS -> "CHAPTERS"
        BuddyLeaseType.BUDDY_LEASE_TYPE_LOW_STORAGE_TRANSCODE -> "MOBILE_TRANSCODE"
        else -> "UNKNOWN"
    }

    private fun buildProbeData(probeResult: ForBrowserProbeResult, fileSize: Long?): ProbeData {
        return ProbeData.newBuilder()
            .setDuration(PlaybackOffset.newBuilder().setSeconds(probeResult.durationSecs ?: 0.0))
            .setFileSizeBytes(fileSize ?: 0)
            .addAllStreams(probeResult.streams.map { s ->
                ProbeStream.newBuilder()
                    .setIndex(s.index)
                    .setType(toProbeStreamType(s.type))
                    .setCodec(s.codec ?: "")
                    .setWidth(s.width ?: 0)
                    .setHeight(s.height ?: 0)
                    .setSarNum(s.sarNum ?: 0)
                    .setSarDen(s.sarDen ?: 0)
                    .setFps(s.fps ?: 0.0)
                    .setChannels(s.channels ?: 0)
                    .setChannelLayout(s.channelLayout ?: "")
                    .setSampleRate(s.sampleRate ?: 0)
                    .setBitrateKbps(s.bitrateKbps ?: 0)
                    .setRawLine(s.rawLine)
                    .build()
            })
            .setRawOutput(probeResult.rawOutput)
            .build()
    }

    private fun toProbeStreamType(type: String): ProbeStreamType = when (type.lowercase()) {
        "video" -> ProbeStreamType.PROBE_STREAM_TYPE_VIDEO
        "audio" -> ProbeStreamType.PROBE_STREAM_TYPE_AUDIO
        "subtitle" -> ProbeStreamType.PROBE_STREAM_TYPE_SUBTITLE
        "data" -> ProbeStreamType.PROBE_STREAM_TYPE_DATA
        else -> ProbeStreamType.PROBE_STREAM_TYPE_UNKNOWN
    }
}
