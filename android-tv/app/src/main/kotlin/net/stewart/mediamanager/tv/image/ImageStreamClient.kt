package net.stewart.mediamanager.tv.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.stewart.mediamanager.grpc.ImageRef
import net.stewart.mediamanager.grpc.ImageResponse
import net.stewart.mediamanager.grpc.cancelStale
import net.stewart.mediamanager.grpc.fetchImage
import net.stewart.mediamanager.grpc.imageRequest
import net.stewart.mediamanager.tv.grpc.GrpcClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages a single persistent bidirectional gRPC image stream.
 * Correlates requests to responses via monotonic request IDs.
 * Sends keepalive pings every 15 seconds to prevent server request timeout.
 */
class ImageStreamClient(private val grpcClient: GrpcClient) {
    private var requestChannel: Channel<net.stewart.mediamanager.grpc.ImageRequest>? = null
    private val pendingResponses = ConcurrentHashMap<Int, CompletableDeferred<ImageResponse?>>()
    private val nextRequestId = AtomicInteger(1)
    private val cancelWatermark = AtomicInteger(-1)
    private var streamJob: Job? = null
    private var keepaliveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val streamLock = Mutex()
    private var lastFailTime = 0L

    /** Fetch an image over the bidi stream. Returns null on timeout or stream failure. */
    suspend fun fetch(ref: ImageRef, etag: String? = null): ImageResponse? {
        ensureStream()
        val channel = requestChannel ?: return null

        val requestId = nextRequestId.getAndIncrement()
        val deferred = CompletableDeferred<ImageResponse?>()
        pendingResponses[requestId] = deferred

        val request = imageRequest {
            fetch = fetchImage {
                this.requestId = requestId
                this.ref = ref
                if (etag != null) this.ifNoneMatch = etag
            }
        }

        return try {
            channel.send(request)
            withTimeoutOrNull(15_000) { deferred.await() }
        } catch (e: Exception) {
            pendingResponses.remove(requestId)?.complete(null)
            null
        }
    }

    /** Cancel all pending requests. Call when navigating away from a screen. */
    suspend fun cancelStale() {
        val watermark = nextRequestId.get() - 1
        cancelWatermark.set(watermark)

        pendingResponses.keys.filter { it <= watermark }.forEach { id ->
            pendingResponses.remove(id)?.complete(null)
        }

        val channel = requestChannel ?: return
        try {
            channel.send(imageRequest {
                this.cancelStale = cancelStale { this.beforeRequestId = watermark }
            })
        } catch (_: Exception) { }
    }

    fun shutdown() {
        keepaliveJob?.cancel()
        streamJob?.cancel()
        requestChannel?.close()
        scope.cancel()
    }

    private suspend fun ensureStream() {
        if (streamJob?.isActive == true) return
        streamLock.withLock {
            if (streamJob?.isActive == true) return

            // Reconnect backoff
            val elapsed = System.currentTimeMillis() - lastFailTime
            if (elapsed < 2000) delay(2000 - elapsed)

            val channel = Channel<net.stewart.mediamanager.grpc.ImageRequest>(Channel.BUFFERED)
            requestChannel = channel

            // Start the bidi stream
            streamJob = scope.launch {
                try {
                    grpcClient.imageService().streamImages(channel.receiveAsFlow())
                        .collect { response ->
                            if (response.requestId <= cancelWatermark.get()) return@collect
                            pendingResponses.remove(response.requestId)?.complete(response)
                        }
                } catch (_: Exception) {
                } finally {
                    lastFailTime = System.currentTimeMillis()
                    pendingResponses.values.forEach { it.complete(null) }
                    pendingResponses.clear()
                    requestChannel = null
                }
            }

            // Start keepalive pings — CancelStale with watermark 0 is a no-op
            // that prevents the server's Armeria request timeout from killing
            // the long-lived bidi stream.
            keepaliveJob?.cancel()
            keepaliveJob = scope.launch {
                while (isActive) {
                    delay(15_000)
                    val ch = requestChannel ?: break
                    try {
                        ch.send(imageRequest {
                            this.cancelStale = cancelStale { this.beforeRequestId = 0 }
                        })
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
    }
}
