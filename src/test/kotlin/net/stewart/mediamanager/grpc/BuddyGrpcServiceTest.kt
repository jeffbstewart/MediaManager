package net.stewart.mediamanager.grpc

import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.BuddyApiKey
import net.stewart.mediamanager.entity.LeaseStatus
import net.stewart.mediamanager.entity.LeaseType
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.TranscodeLease
import net.stewart.mediamanager.service.BuddyKeyService
import org.junit.After
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Coverage for [BuddyGrpcService] — both the unary `checkPending` RPC
 * (x-buddy-key metadata auth) and the bidirectional `workStream` RPC
 * (in-band Connect-message auth, plus the ReportProgress / ReportComplete /
 * ReportFailure sequence).
 *
 * The streaming side is driven through the standard async stub
 * (`BuddyServiceGrpc.newStub`) — production uses the same Java stub from
 * the buddy process. Responses arrive on a `StreamObserver` that the test
 * blocks on via an [ArrayBlockingQueue].
 */
internal class BuddyGrpcServiceTest : GrpcTestBase() {

    @After
    fun cleanBuddyKeys() {
        BuddyApiKey.deleteAll()
    }

    /** Creates a fresh API key the buddy can authenticate with. */
    private fun newBuddyKey(name: String = "test-buddy-${java.util.UUID.randomUUID()}"): String =
        BuddyKeyService.createKey(name)

    /** Build a channel that attaches `x-buddy-key: <rawKey>` metadata. */
    private fun buddyKeyChannel(rawKey: String): ManagedChannel {
        val metadata = Metadata().apply {
            put(
                Metadata.Key.of("x-buddy-key", Metadata.ASCII_STRING_MARSHALLER),
                rawKey,
            )
        }
        return InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor()
            .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
            .build()
    }

    // =========================================================================
    // checkPending unary RPC
    // =========================================================================

    @Test
    fun `checkPending without x-buddy-key returns UNAUTHENTICATED`() {
        // No metadata interceptor → the BuddyAuthInterceptor stores
        // null in BUDDY_API_KEY_CONTEXT_KEY and the service rejects.
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        try {
            val stub = BuddyServiceGrpc.newBlockingStub(channel)
            val request = CheckPendingRequest.newBuilder()
                .addAllTranscodeIds(listOf(1L, 2L, 3L))
                .build()
            try {
                stub.checkPending(request)
                fail("expected UNAUTHENTICATED")
            } catch (e: StatusRuntimeException) {
                assertEquals(Status.Code.UNAUTHENTICATED, e.status.code)
            }
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `checkPending with an invalid x-buddy-key returns UNAUTHENTICATED`() {
        val channel = buddyKeyChannel("nope-not-a-real-key")
        try {
            val stub = BuddyServiceGrpc.newBlockingStub(channel)
            val request = CheckPendingRequest.newBuilder()
                .addAllTranscodeIds(listOf(1L)).build()
            try {
                stub.checkPending(request)
                fail("expected UNAUTHENTICATED")
            } catch (e: StatusRuntimeException) {
                assertEquals(Status.Code.UNAUTHENTICATED, e.status.code)
            }
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `checkPending with a valid x-buddy-key returns the pending list`() {
        val rawKey = newBuddyKey()
        // checkPending returns empty if nas_root_path isn't set, but
        // returns BEFORE the FK lookups; an empty input yields an
        // empty output without any further work.
        val channel = buddyKeyChannel(rawKey)
        try {
            val stub = BuddyServiceGrpc.newBlockingStub(channel)
            val request = CheckPendingRequest.newBuilder()
                .addAllTranscodeIds(emptyList()).build()
            val response = stub.checkPending(request)
            assertEquals(0, response.pendingIdsCount,
                "empty input → empty output on the early-return path")
        } finally {
            channel.shutdownNow()
        }
    }

    // =========================================================================
    // workStream — bidi streaming
    // =========================================================================

    /** Convenience wrapper that buffers ServerMessages from the stream. */
    private class CollectingObserver(capacity: Int = 16) : StreamObserver<ServerMessage> {
        val messages = ArrayBlockingQueue<ServerMessage>(capacity)
        @Volatile var error: Throwable? = null
        @Volatile var completed: Boolean = false

        override fun onNext(value: ServerMessage) { messages.add(value) }
        override fun onError(t: Throwable) { error = t }
        override fun onCompleted() { completed = true }

        fun poll(timeoutMs: Long = 2000): ServerMessage? =
            messages.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `workStream sends NOT_CONNECTED error when first message is not Connect`() {
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder()
                .setHeartbeat(Heartbeat.getDefaultInstance())
                .build())
            send.onCompleted()

            val first = observer.poll() ?: fail("no server message received")
            assertTrue(first.hasError())
            assertEquals(BuddyErrorCode.BUDDY_ERROR_CODE_NOT_CONNECTED, first.error.code)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `Connect with an invalid api key fails the stream with UNAUTHENTICATED`() {
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey("garbage-key")
                .setBuddyName("buddy-1")).build())

            // Server should onError the stream — wait briefly.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (observer.error == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            val err = observer.error ?: fail("expected onError from server")
            assertTrue(err is StatusRuntimeException)
            assertEquals(Status.Code.UNAUTHENTICATED,
                (err as StatusRuntimeException).status.code)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `Connect with a blank buddy_name fails the stream with INVALID_ARGUMENT`() {
        val rawKey = newBuddyKey()
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey(rawKey)
                .setBuddyName("")).build())

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (observer.error == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            val err = observer.error as? StatusRuntimeException
                ?: fail("expected StatusRuntimeException")
            assertEquals(Status.Code.INVALID_ARGUMENT, err.status.code)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `Connect with valid api key emits a Connected ServerMessage`() {
        val rawKey = newBuddyKey()
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey(rawKey)
                .setBuddyName("happy-buddy")).build())

            val first = observer.poll() ?: fail("no Connected message")
            assertTrue(first.hasConnected(), "expected Connected oneof")
            // Wire up the server-version + heartbeat-interval the
            // production code sets; sanity-check, not an exact value lock.
            assertEquals("1.0.0", first.connected.serverVersion)
            // Close out gracefully so the buddy session releases its locks.
            send.onCompleted()
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `RequestWork on an empty queue returns NoWork with a retry hint`() {
        val rawKey = newBuddyKey()
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey(rawKey).setBuddyName("nowork-buddy")).build())
            // Drain the Connected message.
            val connected = observer.poll() ?: fail("no Connected")
            assertTrue(connected.hasConnected())

            send.onNext(BuddyMessage.newBuilder()
                .setRequestWork(RequestWork.getDefaultInstance()).build())

            val response = observer.poll() ?: fail("no NoWork")
            assertTrue(response.hasNoWork(), "expected NoWork")
            send.onCompleted()
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `ReportProgress on an unknown lease id returns INVALID_LEASE error`() {
        val rawKey = newBuddyKey()
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey(rawKey).setBuddyName("progress-buddy")).build())
            observer.poll() ?: fail("no Connected")  // drain

            send.onNext(BuddyMessage.newBuilder().setReportProgress(
                ReportProgress.newBuilder().setLeaseId(99_999L).setPercent(42)
                    .setEncoder("libx264")
            ).build())

            val msg = observer.poll() ?: fail("no error response")
            assertTrue(msg.hasError(), "expected error oneof")
            assertEquals(BuddyErrorCode.BUDDY_ERROR_CODE_INVALID_LEASE, msg.error.code)
            send.onCompleted()
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `ReportFailure on an unknown lease id returns INVALID_LEASE error`() {
        val rawKey = newBuddyKey()
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey(rawKey).setBuddyName("failure-buddy")).build())
            observer.poll() ?: fail("no Connected")

            send.onNext(BuddyMessage.newBuilder().setReportFailure(
                ReportFailure.newBuilder().setLeaseId(123_456L)
                    .setErrorMessage("ffmpeg crashed")
            ).build())

            val msg = observer.poll() ?: fail("no error response")
            assertTrue(msg.hasError())
            assertEquals(BuddyErrorCode.BUDDY_ERROR_CODE_INVALID_LEASE, msg.error.code)
            send.onCompleted()
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `ReportComplete on an unknown lease id returns INVALID_LEASE error`() {
        val rawKey = newBuddyKey()
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey(rawKey).setBuddyName("complete-buddy")).build())
            observer.poll() ?: fail("no Connected")

            send.onNext(BuddyMessage.newBuilder().setReportComplete(
                ReportComplete.newBuilder().setLeaseId(404_404L)
            ).build())

            val msg = observer.poll() ?: fail("no error response")
            assertTrue(msg.hasError())
            assertEquals(BuddyErrorCode.BUDDY_ERROR_CODE_INVALID_LEASE, msg.error.code)
            send.onCompleted()
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `Connect releases orphaned leases left behind by a dead server process`() {
        // When there's no in-memory session for the buddy at Connect
        // time, any DB leases under that buddy_name are presumed
        // orphaned (server crash before grace timer fired) and
        // released back to the queue. resumable_leases is populated
        // *after* the release, so the orphan is gone by the time
        // the Connected message is built.
        val rawKey = newBuddyKey()
        val buddyName = "orphan-buddy-${java.util.UUID.randomUUID()}"

        val title = createTitle(name = "OrphanMovie")
        val tc = createTranscode(title.id!!, filePath = "/nas/orphan.mkv")
        val lease = TranscodeLease(
            transcode_id = tc.id!!,
            buddy_name = buddyName,
            relative_path = "orphan.mkv",
            file_size_bytes = 1_000_000,
            claimed_at = LocalDateTime.now(),
            expires_at = LocalDateTime.now().plusMinutes(5),
            status = LeaseStatus.CLAIMED.name,
            lease_type = LeaseType.TRANSCODE.name,
            progress_percent = 30,
        ).apply { save() }

        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey(rawKey).setBuddyName(buddyName)).build())

            val connected = observer.poll() ?: fail("no Connected")
            assertTrue(connected.hasConnected())
            // resumable_leases is empty — orphan was released first.
            assertEquals(0, connected.connected.resumableLeasesCount)

            // The lease was either deleted or moved to RELEASED — DB state
            // is the source of truth; assert it's no longer CLAIMED.
            val refreshed = TranscodeLease.findById(lease.id!!)
            assertTrue(
                refreshed == null || refreshed.status != LeaseStatus.CLAIMED.name,
                "orphaned lease should be released; was: ${refreshed?.status}"
            )
            send.onCompleted()
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `second Connect on the same stream is rejected with an error message`() {
        val rawKey = newBuddyKey()
        val channel = InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor().build()
        val observer = CollectingObserver()
        try {
            val stub = BuddyServiceGrpc.newStub(channel)
            val send = stub.workStream(observer)
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey(rawKey).setBuddyName("double-connect-buddy")).build())
            observer.poll() ?: fail("no first Connected")

            // Second Connect on the same stream → server emits an
            // error ServerMessage, not a second Connected.
            send.onNext(BuddyMessage.newBuilder().setConnect(Connect.newBuilder()
                .setApiKey(rawKey).setBuddyName("double-connect-buddy")).build())

            val msg = observer.poll() ?: fail("no response to second Connect")
            assertTrue(msg.hasError())
            send.onCompleted()
        } finally {
            channel.shutdownNow()
        }
    }
}
