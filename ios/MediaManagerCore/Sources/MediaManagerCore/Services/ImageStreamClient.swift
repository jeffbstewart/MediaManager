import Foundation
import GRPCCore
import os.log
import MediaManagerProtos

private let logger = MMLogger(category: "ImageStreamClient")

/// What `ImageStreamClient` needs from the connection: `GrpcClient` in
/// the app, a fake in tests.
public protocol ImageStreamTransport: Sendable {
    /// Goes up whenever the connection or the signed-in account may have
    /// changed (see `GrpcClient.connectionGeneration`).
    var connectionGeneration: UInt64 { get async }

    /// Open the image stream and run it until it ends. `requestProducer`
    /// is handed the writer; `onMessage` is called with each response.
    func streamImages(
        requestProducer: @Sendable @escaping (RPCWriter<MMImageRequest>) async throws -> Void,
        onMessage: @Sendable @escaping (MMImageResponse) async -> Void
    ) async throws
}

extension GrpcClient: ImageStreamTransport {}

/// Manages a single bidirectional gRPC stream for image delivery.
/// Opens the stream lazily on first request, reconnects transparently on failure.
///
/// The stream records the transport's connection generation when it
/// opens. A request that finds the generation has moved (a server
/// change, a sign-out, a different account) closes the stream and opens
/// a new one, so no request goes to the old server as the old account.
/// Requests still waiting on a stream that closes return nil.
public actor ImageStreamClient {

    private let transport: any ImageStreamTransport
    private var writer: (RPCWriter<MMImageRequest>)?
    private var writerContinuations: [CheckedContinuation<Void, Never>] = []
    private var pendingResponses: [Int32: CheckedContinuation<MMImageResponse?, Never>] = [:]
    private var nextRequestId: Int32 = 1
    private var streamTask: Task<Void, Never>?
    private var lastStreamFailTime: Date?
    private var cancelWatermark: Int32 = 0
    /// Identifies the current stream, so callbacks from a stream that
    /// has since been closed or replaced change nothing.
    private var streamID: UInt64 = 0
    /// The transport's connection generation when the current stream
    /// opened.
    private var streamGeneration: UInt64 = 0

    public init(transport: any ImageStreamTransport) {
        self.transport = transport
    }

    public init(grpcClient: GrpcClient) {
        self.init(transport: grpcClient)
    }

    // MARK: - Public API

    /// Fetch an image by reference. Returns the response, or nil on stream failure.
    public func fetch(ref: MMImageRef, etag: String?) async -> MMImageResponse? {
        let requestId = nextRequestId
        nextRequestId += 1

        // Ensure stream is open
        await ensureStream()

        guard let writer = self.writer else {
            return nil
        }

        // Build request
        var fetchMsg = MMFetchImage()
        fetchMsg.requestID = requestId
        fetchMsg.ref = ref
        if let etag { fetchMsg.ifNoneMatch = etag }

        var request = MMImageRequest()
        request.fetch = fetchMsg

        // Register continuation before sending
        let response: MMImageResponse? = await withCheckedContinuation { continuation in
            pendingResponses[requestId] = continuation

            Task {
                do {
                    try await writer.write(request)
                } catch {
                    // Stream broken — fail this request
                    self.failRequest(requestId)
                }
            }
        }

        return response
    }

    /// Send a cancel-stale watermark. The server will drop responses
    /// for requests before this ID.
    public func cancelStale() async {
        cancelWatermark = nextRequestId - 1
        guard let writer = self.writer else { return }

        var cancel = MMCancelStale()
        cancel.beforeRequestID = cancelWatermark

        var request = MMImageRequest()
        request.cancelStale = cancel

        try? await writer.write(request)
    }

    /// Shut down the stream. Requests waiting on it return nil; the next
    /// request opens a new one.
    public func close() {
        closeStream()
    }

    // MARK: - Stream Management

    private func ensureStream() async {
        let generation = await transport.connectionGeneration

        // A stream opened before the connection or account changed is
        // talking to the old server as the old account. Only a newer
        // generation counts: a caller that read the value just before a
        // reopen must not close the stream that replaced its own.
        if streamTask != nil, generation > streamGeneration {
            let old = streamGeneration
            logger.info("Connection changed (generation \(old) -> \(generation)); reopening image stream")
            closeStream()
        }

        // Already connected
        if writer != nil { return }

        // No stream yet, or the last one ended: open one. Otherwise one
        // is already opening; wait for it.
        if streamTask == nil {
            startStream(generation: generation)
        }

        // Resumed when the writer is ready, or the stream ends or closes.
        await withCheckedContinuation { continuation in
            writerContinuations.append(continuation)
        }
    }

    private func startStream(generation: UInt64) {
        streamID += 1
        let id = streamID
        streamGeneration = generation
        // Back off if the last stream failed recently.
        let backoff = lastStreamFailTime.map { Date().timeIntervalSince($0) < 2.0 } ?? false
        let transport = self.transport

        streamTask = Task { [weak self] in
            guard let self else { return }
            do {
                if backoff {
                    try await Task.sleep(for: .seconds(2))
                }
                logger.info("Opening image stream...")
                try await transport.streamImages(
                    requestProducer: { writer in
                        await self.setWriter(writer, streamID: id)
                        // Keep the request stream alive with periodic no-op pings.
                        // HAProxy and gRPC have idle timeouts; a CancelStale with
                        // watermark 0 is effectively a no-op keepalive.
                        while !Task.isCancelled {
                            try await Task.sleep(for: .seconds(15))
                            var ping = MMImageRequest()
                            var cancel = MMCancelStale()
                            cancel.beforeRequestID = 0
                            ping.cancelStale = cancel
                            try await writer.write(ping)
                        }
                    },
                    onMessage: { message in
                        await self.handleResponse(message, streamID: id)
                    }
                )
            } catch is CancellationError {
                logger.info("Image stream cancelled")
            } catch {
                logger.error("Image stream failed: \(String(describing: error))")
            }

            // Stream ended — clean up
            await self.onStreamEnded(streamID: id)
        }
    }

    private func setWriter(_ w: RPCWriter<MMImageRequest>, streamID id: UInt64) {
        guard id == streamID, streamTask != nil else { return }
        logger.info("Image stream writer ready")
        self.writer = w
        resumeWriterWaiters()
    }

    private func handleResponse(_ response: MMImageResponse, streamID id: UInt64) {
        guard id == streamID else { return }
        let requestId = response.requestID

        // Ignore responses for cancelled requests
        if requestId <= cancelWatermark {
            return
        }

        if let continuation = pendingResponses.removeValue(forKey: requestId) {
            continuation.resume(returning: response)
        }
    }

    private func failRequest(_ requestId: Int32) {
        if let cont = pendingResponses.removeValue(forKey: requestId) {
            cont.resume(returning: nil)
        }
    }

    private func onStreamEnded(streamID id: UInt64) {
        // A stream that was closed or replaced has already been cleaned up.
        guard id == streamID, streamTask != nil else { return }
        lastStreamFailTime = Date()
        resetStream()
    }

    /// Cancel the current stream and fail everything waiting on it.
    private func closeStream() {
        streamTask?.cancel()
        resetStream()
    }

    private func resetStream() {
        streamTask = nil
        writer = nil
        resumeWriterWaiters()
        failAllPending()
    }

    private func resumeWriterWaiters() {
        let waiting = writerContinuations
        writerContinuations.removeAll()
        for c in waiting { c.resume() }
    }

    private func failAllPending() {
        for (_, continuation) in pendingResponses {
            continuation.resume(returning: nil)
        }
        pendingResponses.removeAll()
    }
}
