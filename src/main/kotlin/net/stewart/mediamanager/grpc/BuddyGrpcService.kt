package net.stewart.mediamanager.grpc

import com.github.vokorm.findAll
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.stub.StreamObserver
import net.stewart.mediamanager.entity.Chapter
import net.stewart.mediamanager.entity.LeaseStatus
import net.stewart.mediamanager.entity.LeaseType
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.SkipSegment
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.TranscodeLease
import net.stewart.mediamanager.service.BuddyKeyService
import net.stewart.mediamanager.service.ForBrowserProbeService
import net.stewart.mediamanager.service.TranscodeLeaseService
import net.stewart.transcode.ForBrowserProbeResult
import net.stewart.transcode.StreamInfo
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * gRPC service implementing the buddy bidirectional streaming protocol.
 *
 * Registered WITHOUT the user [AuthInterceptor]. The WorkStream RPC authenticates
 * in-band via the Connect message's API key. The CheckPending unary RPC authenticates
 * via "x-buddy-key" gRPC metadata, extracted by [BuddyAuthInterceptor].
 *
 * A per-IP rate limiter prevents brute-force connection attempts.
 */
class BuddyGrpcService : BuddyServiceGrpc.BuddyServiceImplBase() {

    private val log = LoggerFactory.getLogger(BuddyGrpcService::class.java)

    companion object {
        const val HEARTBEAT_INTERVAL_SECONDS = 15L
        const val IDLE_TIMEOUT_SECONDS = 60L
        const val RECONNECT_GRACE_SECONDS = 60L

        private const val MAX_CONNECTS_PER_MINUTE = 10
        private const val RATE_LIMIT_WINDOW_MS = 60_000L
    }

    private val connectAttempts = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val activeSessions = ConcurrentHashMap<String, BuddySession>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "buddy-timeout").apply { isDaemon = true }
    }

    init {
        // Periodically expire stale leases (catches orphans from crashed buddies
        // whose grace timer didn't fire, e.g., server restart).
        scheduler.scheduleAtFixedRate({
            try {
                TranscodeLeaseService.expireStaleLeases()
            } catch (e: Exception) {
                log.warn("Error expiring stale leases: {}", e.message)
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    // ========================================================================
    // WorkStream — bidirectional streaming RPC
    // ========================================================================

    override fun workStream(responseObserver: StreamObserver<ServerMessage>): StreamObserver<BuddyMessage> {
        return BuddyStreamHandler(responseObserver)
    }

    private inner class BuddyStreamHandler(
        private val out: StreamObserver<ServerMessage>
    ) : StreamObserver<BuddyMessage> {

        private var authenticated = false
        private var buddyName: String? = null
        private var skipTypes = emptySet<String>()
        private var cachedTranscodeIds = emptySet<Long>()
        @Volatile private var lastMessageTime = System.currentTimeMillis()
        private var idleTimeoutFuture: ScheduledFuture<*>? = null

        override fun onNext(message: BuddyMessage) {
            lastMessageTime = System.currentTimeMillis()

            try {
                // Connect is the only message allowed before authentication
                if (message.messageCase == BuddyMessage.MessageCase.CONNECT) {
                    handleConnect(message.connect)
                    return
                }

                // All other messages require prior authentication
                if (!authenticated) {
                    sendError(BuddyErrorCode.BUDDY_ERROR_CODE_NOT_CONNECTED,
                        "Must send Connect before other messages")
                    return
                }

                // Any authenticated message implicitly renews leases
                renewLeases()

                when (message.messageCase) {
                    BuddyMessage.MessageCase.HEARTBEAT -> {} // renewal already done above
                    BuddyMessage.MessageCase.REQUEST_WORK -> handleRequestWork(message.requestWork)
                    BuddyMessage.MessageCase.REPORT_PROGRESS -> handleReportProgress(message.reportProgress)
                    BuddyMessage.MessageCase.REPORT_COMPLETE -> handleReportComplete(message.reportComplete)
                    BuddyMessage.MessageCase.REPORT_FAILURE -> handleReportFailure(message.reportFailure)
                    BuddyMessage.MessageCase.MESSAGE_NOT_SET, null -> {
                        sendError(BuddyErrorCode.BUDDY_ERROR_CODE_UNKNOWN, "Empty message")
                    }
                    else -> {} // CONNECT handled above
                }
            } catch (e: Exception) {
                log.error("Error processing buddy message from '{}': {}", buddyName, e.message, e)
                sendError(BuddyErrorCode.BUDDY_ERROR_CODE_UNKNOWN, "Internal error")
            }
        }

        override fun onError(t: Throwable) {
            log.info("Buddy '{}' stream error: {}", buddyName ?: "unauthenticated", t.message)
            cleanup(graceful = false)
        }

        override fun onCompleted() {
            log.info("Buddy '{}' stream closed", buddyName ?: "unauthenticated")
            cleanup(graceful = true)
        }

        // -- Message handlers --

        private fun handleConnect(connect: Connect) {
            if (authenticated) {
                sendError(BuddyErrorCode.BUDDY_ERROR_CODE_UNKNOWN, "Already connected")
                return
            }

            val clientIp = resolveClientIp()
            if (isRateLimited(clientIp)) {
                log.warn("Buddy connect rate limited for IP {}", clientIp)
                out.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription("Too many connection attempts. Try again later.")
                    .asRuntimeException())
                return
            }

            if (!BuddyKeyService.validate(connect.apiKey)) {
                log.warn("Buddy auth failed from IP {} with name '{}'", clientIp, connect.buddyName)
                out.onError(Status.UNAUTHENTICATED
                    .withDescription("Invalid API key")
                    .asRuntimeException())
                return
            }

            val name = connect.buddyName
            if (name.isBlank()) {
                out.onError(Status.INVALID_ARGUMENT
                    .withDescription("buddy_name is required")
                    .asRuntimeException())
                return
            }

            buddyName = name
            skipTypes = connect.skipTypesList.map { it.name }.toSet()
            cachedTranscodeIds = connect.cachedTranscodeIdsList.toSet()
            authenticated = true

            // Cancel any pending reconnect-grace expiry for this buddy
            val previousSession = activeSessions.put(name, BuddySession(this))
            previousSession?.cancelGraceTimer()

            startIdleTimeout()

            val resumableLeases = findResumableLeases(name)

            val connected = Connected.newBuilder()
                .setServerVersion("1.0.0")
                .setHeartbeatInterval(durationOfSeconds(HEARTBEAT_INTERVAL_SECONDS))
                .setIdleTimeout(durationOfSeconds(IDLE_TIMEOUT_SECONDS))
                .setReconnectGrace(durationOfSeconds(RECONNECT_GRACE_SECONDS))
                .setPendingCount(TranscodeLeaseService.countPendingTranscodes())
                .addAllResumableLeases(resumableLeases)
                .build()

            out.onNext(ServerMessage.newBuilder().setConnected(connected).build())
            log.info("Buddy '{}' connected from {} ({} resumable leases)",
                name, clientIp, resumableLeases.size)
        }

        private fun handleRequestWork(request: RequestWork) {
            if (request.cachedTranscodeIdsList.isNotEmpty()) {
                cachedTranscodeIds = request.cachedTranscodeIdsList.toSet()
            }

            val bundle = TranscodeLeaseService.claimWork(
                buddyName!!, skipTypes, cachedTranscodeIds
            )

            if (bundle == null) {
                val noWork = NoWork.newBuilder()
                    .setRetryAfter(durationOfSeconds(30))
                    .setPendingCount(TranscodeLeaseService.countPendingTranscodes())
                    .build()
                out.onNext(ServerMessage.newBuilder().setNoWork(noWork).build())
                return
            }

            val assignment = WorkAssignment.newBuilder()
                .setTranscodeId(bundle.transcodeId)
                .setRelativePath(bundle.relativePath)
                .setFileSizeBytes(bundle.fileSizeBytes)
                .addAllLeases(bundle.leases.map { lease ->
                    WorkLease.newBuilder()
                        .setLeaseId(lease.id!!)
                        .setLeaseType(toBuddyLeaseType(lease.lease_type))
                        .build()
                })
                .build()

            out.onNext(ServerMessage.newBuilder().setWorkAssignment(assignment).build())
            log.info("Assigned transcode_id={} to buddy '{}' ({} leases)",
                bundle.transcodeId, buddyName, bundle.leases.size)
        }

        private fun handleReportProgress(progress: ReportProgress) {
            val lease = TranscodeLeaseService.reportProgress(
                progress.leaseId, progress.percent,
                progress.encoder.ifBlank { null }
            )
            if (lease == null) {
                sendError(BuddyErrorCode.BUDDY_ERROR_CODE_INVALID_LEASE,
                    "Lease ${progress.leaseId} not found or not active")
            }
        }

        private fun handleReportComplete(complete: ReportComplete) {
            val encoder = complete.encoder.ifBlank { null }
            val lease = TranscodeLeaseService.reportComplete(complete.leaseId, encoder)
            if (lease == null) {
                sendError(BuddyErrorCode.BUDDY_ERROR_CODE_INVALID_LEASE,
                    "Lease ${complete.leaseId} not found or not active")
                return
            }

            if (complete.chaptersCount > 0) {
                storeChapters(lease.transcode_id, complete.chaptersList)
            }
            if (complete.hasProbe()) {
                storeProbe(lease.transcode_id, lease.relative_path, complete.probe, encoder)
            }
        }

        private fun handleReportFailure(failure: ReportFailure) {
            val lease = TranscodeLeaseService.reportFailure(
                failure.leaseId, failure.errorMessage.ifBlank { null }
            )
            if (lease == null) {
                sendError(BuddyErrorCode.BUDDY_ERROR_CODE_INVALID_LEASE,
                    "Lease ${failure.leaseId} not found or not active")
            }
        }

        // -- Lease renewal --

        /** Lease expiry window for gRPC-connected buddies: 2 minutes.
         *  Short because the bidi stream gives real-time disconnect detection;
         *  this is just a safety net for expireStaleLeases() to catch orphans. */
        private val GRPC_LEASE_DURATION_MINUTES = 2L

        private fun renewLeases() {
            val name = buddyName ?: return
            val activeIds = TranscodeLease.findAll()
                .filter {
                    it.buddy_name == name &&
                        (it.status == LeaseStatus.CLAIMED.name || it.status == LeaseStatus.IN_PROGRESS.name)
                }
                .mapNotNull { it.id }
            if (activeIds.isNotEmpty()) {
                TranscodeLeaseService.heartbeatMultiple(activeIds, GRPC_LEASE_DURATION_MINUTES)
            }
        }

        // -- Idle timeout --

        private fun startIdleTimeout() {
            idleTimeoutFuture = scheduler.scheduleAtFixedRate({
                val elapsed = System.currentTimeMillis() - lastMessageTime
                if (elapsed > IDLE_TIMEOUT_SECONDS * 1000) {
                    log.warn("Buddy '{}' idle for {}s, disconnecting", buddyName, elapsed / 1000)
                    try {
                        out.onError(Status.DEADLINE_EXCEEDED
                            .withDescription("Idle timeout — no messages for ${IDLE_TIMEOUT_SECONDS}s")
                            .asRuntimeException())
                    } catch (_: Exception) {}
                    cleanup(graceful = false)
                }
            }, IDLE_TIMEOUT_SECONDS, IDLE_TIMEOUT_SECONDS / 2, TimeUnit.SECONDS)
        }

        // -- Cleanup --

        private fun cleanup(graceful: Boolean) {
            idleTimeoutFuture?.cancel(false)
            val name = buddyName ?: return

            if (graceful) {
                TranscodeLeaseService.releaseLeases(name)
                activeSessions.remove(name, activeSessions[name])
                log.info("Buddy '{}' disconnected gracefully, leases released", name)
            } else {
                val session = activeSessions[name]
                if (session?.handler === this) {
                    session.startGraceTimer(name)
                    log.info("Buddy '{}' disconnected, {}s grace period started",
                        name, RECONNECT_GRACE_SECONDS)
                }
            }
        }

        // -- Helpers --

        private fun sendError(code: BuddyErrorCode, message: String) {
            try {
                out.onNext(ServerMessage.newBuilder()
                    .setError(ServerError.newBuilder().setCode(code).setMessage(message))
                    .build())
            } catch (_: Exception) {}
        }

        private fun resolveClientIp(): String {
            return try {
                CLIENT_IP_CONTEXT_KEY.get() ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
        }
    }

    // ========================================================================
    // Session tracking
    // ========================================================================

    private inner class BuddySession(val handler: BuddyStreamHandler) {
        @Volatile var graceTimerFuture: ScheduledFuture<*>? = null

        fun startGraceTimer(buddyName: String) {
            graceTimerFuture = scheduler.schedule({
                log.info("Reconnect grace expired for buddy '{}', releasing leases", buddyName)
                TranscodeLeaseService.releaseLeases(buddyName)
                activeSessions.remove(buddyName)
            }, RECONNECT_GRACE_SECONDS, TimeUnit.SECONDS)
        }

        fun cancelGraceTimer() {
            graceTimerFuture?.cancel(false)
            graceTimerFuture = null
        }
    }

    // ========================================================================
    // Rate limiting
    // ========================================================================

    private fun isRateLimited(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = connectAttempts.computeIfAbsent(ip) { ConcurrentLinkedDeque() }
        while (timestamps.peekFirst()?.let { it < now - RATE_LIMIT_WINDOW_MS } == true) {
            timestamps.pollFirst()
        }
        if (timestamps.size >= MAX_CONNECTS_PER_MINUTE) return true
        timestamps.addLast(now)
        return false
    }

    // ========================================================================
    // CheckPending — unary RPC (buddy key auth via metadata)
    // ========================================================================

    override fun checkPending(
        request: CheckPendingRequest,
        responseObserver: StreamObserver<CheckPendingResponse>
    ) {
        val key = BUDDY_API_KEY_CONTEXT_KEY.get()
        if (key == null || !BuddyKeyService.validate(key)) {
            responseObserver.onError(Status.UNAUTHENTICATED
                .withDescription("Invalid or missing x-buddy-key")
                .asRuntimeException())
            return
        }

        val pending = TranscodeLeaseService.checkPending(request.transcodeIdsList)
        responseObserver.onNext(
            CheckPendingResponse.newBuilder()
                .addAllPendingIds(pending)
                .build()
        )
        responseObserver.onCompleted()
    }

    // ========================================================================
    // Data storage helpers
    // ========================================================================

    private fun storeChapters(transcodeId: Long, chapterDataList: List<ChapterData>) {
        try {
            val chapters = chapterDataList.map { cd ->
                Chapter(
                    transcode_id = transcodeId,
                    chapter_number = cd.number,
                    start_seconds = cd.start.seconds,
                    end_seconds = cd.end.seconds,
                    title = cd.title.ifBlank { null }
                )
            }

            if (chapters.isNotEmpty()) {
                Chapter.findAll()
                    .filter { it.transcode_id == transcodeId }
                    .forEach { it.delete() }
                for (chapter in chapters) { chapter.create() }
                log.info("Stored {} chapters for transcode_id={}", chapters.size, transcodeId)
                autoCreateIntroSkipSegment(transcodeId, chapters)
            }
        } catch (e: Exception) {
            log.warn("Failed to store chapter data for transcode_id={}: {}", transcodeId, e.message)
        }
    }

    private fun autoCreateIntroSkipSegment(transcodeId: Long, chapters: List<Chapter>) {
        try {
            val transcode = Transcode.findById(transcodeId) ?: return
            val title = Title.findById(transcode.title_id) ?: return
            if (title.media_type != MMMediaType.MOVIE.name) return

            val chapter1 = chapters.firstOrNull { it.chapter_number == 1 } ?: return
            val durationSecs = chapter1.end_seconds - chapter1.start_seconds
            if (durationSecs > 300) return

            val existing = SkipSegment.findAll()
                .any { it.transcode_id == transcodeId && it.segment_type == "INTRO" }
            if (existing) return

            SkipSegment(
                transcode_id = transcodeId,
                segment_type = "INTRO",
                start_seconds = chapter1.start_seconds,
                end_seconds = chapter1.end_seconds,
                detection_method = "CHAPTER"
            ).create()
            log.info("Auto-created INTRO skip segment for transcode_id={} (chapter 1: {}s)",
                transcodeId, "%.1f".format(durationSecs))
        } catch (e: Exception) {
            log.warn("Failed to auto-create intro skip segment for transcode_id={}: {}",
                transcodeId, e.message)
        }
    }

    private fun storeProbe(transcodeId: Long, relativePath: String, probe: ProbeData, encoder: String?) {
        try {
            if (probe.streamsCount > 100) {
                log.warn("Probe data rejected: {} streams (max 100)", probe.streamsCount)
                return
            }

            val streams = probe.streamsList.map { s ->
                StreamInfo(
                    index = s.index,
                    type = toStreamTypeString(s.type),
                    codec = s.codec.ifBlank { null },
                    width = s.width.takeIf { it > 0 },
                    height = s.height.takeIf { it > 0 },
                    sarNum = s.sarNum.takeIf { it > 0 },
                    sarDen = s.sarDen.takeIf { it > 0 },
                    fps = s.fps.takeIf { it > 0.0 },
                    channels = s.channels.takeIf { it > 0 },
                    channelLayout = s.channelLayout.ifBlank { null },
                    sampleRate = s.sampleRate.takeIf { it > 0 },
                    bitrateKbps = s.bitrateKbps.takeIf { it > 0 },
                    rawLine = s.rawLine
                )
            }

            ForBrowserProbeService.recordProbe(
                transcodeId = transcodeId,
                relativePath = relativePath,
                probeResult = ForBrowserProbeResult(
                    durationSecs = probe.duration.seconds.takeIf { it > 0.0 },
                    streams = streams,
                    rawOutput = probe.rawOutput
                ),
                encoder = encoder,
                fileSize = probe.fileSizeBytes.takeIf { it > 0 }
            )
        } catch (e: Exception) {
            log.warn("Failed to store probe data for transcode_id={}: {}", transcodeId, e.message)
        }
    }

    // ========================================================================
    // Enum conversions
    // ========================================================================

    private fun findResumableLeases(buddyName: String): List<ResumableLease> {
        return TranscodeLease.findAll()
            .filter {
                it.buddy_name == buddyName &&
                    (it.status == LeaseStatus.CLAIMED.name || it.status == LeaseStatus.IN_PROGRESS.name)
            }
            .map { lease ->
                ResumableLease.newBuilder()
                    .setLeaseId(lease.id!!)
                    .setTranscodeId(lease.transcode_id)
                    .setLeaseType(toBuddyLeaseType(lease.lease_type))
                    .setRelativePath(lease.relative_path)
                    .setFileSizeBytes(lease.file_size_bytes ?: 0)
                    .setProgressPercent(lease.progress_percent)
                    .build()
            }
    }

    private fun toBuddyLeaseType(leaseType: String?): BuddyLeaseType = when (leaseType) {
        LeaseType.TRANSCODE.name -> BuddyLeaseType.BUDDY_LEASE_TYPE_TRANSCODE
        LeaseType.THUMBNAILS.name -> BuddyLeaseType.BUDDY_LEASE_TYPE_THUMBNAILS
        LeaseType.SUBTITLES.name -> BuddyLeaseType.BUDDY_LEASE_TYPE_SUBTITLES
        LeaseType.CHAPTERS.name -> BuddyLeaseType.BUDDY_LEASE_TYPE_CHAPTERS
        LeaseType.MOBILE_TRANSCODE.name -> BuddyLeaseType.BUDDY_LEASE_TYPE_LOW_STORAGE_TRANSCODE
        else -> BuddyLeaseType.BUDDY_LEASE_TYPE_UNKNOWN
    }

    private fun toStreamTypeString(type: ProbeStreamType): String = when (type) {
        ProbeStreamType.PROBE_STREAM_TYPE_VIDEO -> "video"
        ProbeStreamType.PROBE_STREAM_TYPE_AUDIO -> "audio"
        ProbeStreamType.PROBE_STREAM_TYPE_SUBTITLE -> "subtitle"
        ProbeStreamType.PROBE_STREAM_TYPE_DATA -> "data"
        else -> ""
    }

    private fun durationOfSeconds(seconds: Long): Duration {
        return Duration.newBuilder()
            .setNanos(seconds * 1_000_000_000L)
            .build()
    }
}

/** Context key for the buddy API key extracted from metadata by [BuddyAuthInterceptor]. */
val BUDDY_API_KEY_CONTEXT_KEY: Context.Key<String> = Context.key("buddy-api-key")

/**
 * Lightweight interceptor that extracts the "x-buddy-key" metadata header and
 * client IP into the gRPC context. Does NOT validate the key — that's done
 * by the service method (streaming auth is in-band via Connect).
 */
class BuddyAuthInterceptor : ServerInterceptor {
    private val buddyKeyMeta = Metadata.Key.of(
        "x-buddy-key", Metadata.ASCII_STRING_MARSHALLER
    )

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val key = headers.get(buddyKeyMeta)
        val transport = GrpcRequestContext.resolve(headers, call)
        val clientIp = transport?.clientIp ?: "unknown"

        val ctx = Context.current()
            .withValue(BUDDY_API_KEY_CONTEXT_KEY, key)
            .withValue(CLIENT_IP_CONTEXT_KEY, clientIp)
        return Contexts.interceptCall(ctx, call, headers, next)
    }
}
