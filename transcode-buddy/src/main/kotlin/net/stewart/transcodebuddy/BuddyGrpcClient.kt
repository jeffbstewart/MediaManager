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
 * Thread safety: all access to [connected] and [requestObserver] is guarded by
 * [streamLock]. Reconnection attempts are serialized by [connectLock] and never
 * hold [streamLock] across blocking waits.
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

    /**
     * Guards all stream state: [connected], [requestObserver], and sends.
     * Held briefly — never across blocking waits (poll, sleep).
     */
    private val streamLock = Any()

    /**
     * Serializes [connect] / [reconnectIfNeeded] so only one thread opens a
     * stream at a time. Held across the full connect() duration including the
     * 30-second blocking wait for the Connected response.
     */
    private val connectLock = Any()

    // Guarded by streamLock. @Volatile for lock-free reads in isConnected().
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
     * Serialized — only one thread connects at a time.
     */
    fun connect(): Connected? = synchronized(connectLock) { connectInternal() }

    /**
     * If disconnected, reconnects. Safe to call from any thread (heartbeat,
     * worker, etc.) — concurrent calls are serialized and only one actually
     * opens a new stream.
     *
     * @return true if connected after the call
     */
    fun reconnectIfNeeded(): Boolean {
        if (connected) return true
        synchronized(connectLock) {
            if (connected) return true
            log.info("Attempting stream reconnection...")
            return connectInternal() != null
        }
    }

    private fun connectInternal(): Connected? {
        // Tear down any existing stream
        synchronized(streamLock) {
            try { requestObserver?.onCompleted() } catch (_: Exception) {}
            connected = false
            requestObserver = null
        }

        // Drain stale work responses from the previous stream
        workResponseQueue.clear()

        val connectQueue = LinkedBlockingQueue<Any>() // receives Connected or Throwable

        val responseObserver = object : StreamObserver<ServerMessage> {
            override fun onNext(message: ServerMessage) {
                when (message.messageCase) {
                    ServerMessage.MessageCase.CONNECTED -> {
                        synchronized(streamLock) { connected = true }
                        lastPendingCount = message.connected.pendingCount
                        connectQueue.put(message.connected)
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
                            val leaseId = Regex("""Lease (\d+)""").find(err.message)?.groupValues?.get(1)?.toLongOrNull()
                            if (leaseId != null) {
                                if (invalidatedLeases.add(leaseId)) {
                                    log.warn("Lease {} invalidated by server: {}", leaseId, err.message)
                                }
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
                synchronized(streamLock) { connected = false }
                val status = Status.fromThrowable(t)
                log.error("WorkStream error: {} — {}", status.code, status.description)
                connectQueue.put(t)
                // Unblock any pending claimWork
                workResponseQueue.put(ServerMessage.getDefaultInstance())
            }

            override fun onCompleted() {
                synchronized(streamLock) { connected = false }
                log.info("WorkStream completed by server")
                workResponseQueue.put(ServerMessage.getDefaultInstance())
            }
        }

        // Open new stream and install the observer
        val newObserver = try {
            asyncStub.workStream(responseObserver)
        } catch (e: Exception) {
            log.error("Failed to open WorkStream: {}", e.message)
            return null
        }

        synchronized(streamLock) {
            requestObserver = newObserver
        }

        // Send Connect
        val connectMsg = BuddyMessage.newBuilder()
            .setConnect(Connect.newBuilder()
                .setApiKey(config.apiKey)
                .setBuddyName(config.buddyName)
                .addAllSkipTypes(buildSkipTypes())
                .addAllCachedTranscodeIds(emptyList()))
            .build()

        synchronized(streamLock) {
            try {
                requestObserver?.onNext(connectMsg)
            } catch (e: Exception) {
                log.error("Failed to send Connect: {}", e.message)
                connected = false
                requestObserver = null
                return null
            }
        }

        // Wait for Connected response — NOT under any lock
        val response = connectQueue.poll(30, TimeUnit.SECONDS)
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
        // Drain any stale responses
        workResponseQueue.clear()

        val requestWork = RequestWork.newBuilder()
            .addAllCachedTranscodeIds(cachedTranscodeIds)
            .build()

        // Atomic check + send under streamLock
        synchronized(streamLock) {
            if (!connected) return null
            try {
                requestObserver?.onNext(
                    BuddyMessage.newBuilder().setRequestWork(requestWork).build()
                ) ?: return null
            } catch (e: Exception) {
                log.warn("claimWork send failed: {}", e.message)
                connected = false
                return null
            }
        }

        // Block for response — NOT under lock
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
        if (fileSize != null && fileSize > 0) builder.outputSizeBytes = fileSize
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
        synchronized(streamLock) {
            try { requestObserver?.onCompleted() } catch (_: Exception) {}
        }
        return 0
    }

    /** Returns cached pending count from last Connected or NoWork message. */
    fun getPendingCount(): Int = lastPendingCount

    /** Lock-free read — volatile guarantees visibility across threads. */
    fun isConnected(): Boolean = connected

    /** Returns true if any of the given lease IDs have been rejected by the server. */
    fun hasInvalidatedLeases(leaseIds: List<Long>): Boolean =
        leaseIds.any { it in invalidatedLeases }

    /** Clears invalidated lease tracking (call after abandoning a bundle). */
    fun clearInvalidatedLeases() = invalidatedLeases.clear()

    fun shutdown() {
        synchronized(streamLock) {
            try { requestObserver?.onCompleted() } catch (_: Exception) {}
            connected = false
            requestObserver = null
        }
        channel.shutdown()
        channel.awaitTermination(5, TimeUnit.SECONDS)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Send a message on the stream. Atomic check-then-send under [streamLock].
     * On send failure, marks the stream as disconnected.
     */
    private fun send(message: BuddyMessage): Boolean {
        synchronized(streamLock) {
            if (!connected) return false
            return try {
                requestObserver?.onNext(message)
                true
            } catch (e: Exception) {
                log.warn("Send failed: {}", e.message)
                connected = false
                false
            }
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
