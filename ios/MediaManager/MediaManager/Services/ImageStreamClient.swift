import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2
import GRPCProtobuf
import UIKit
import os.log

private let logger = MMLogger(category: "ImageStreamClient")

/// Manages a single bidirectional gRPC stream for image delivery.
/// Opens the stream lazily on first request, reconnects transparently on failure.
actor ImageStreamClient {

    private let grpcClient: GrpcClient
    private var writer: (RPCWriter<MMImageRequest>)?
    private var writerContinuations: [CheckedContinuation<Void, Never>] = []
    private var pendingResponses: [Int32: CheckedContinuation<MMImageResponse?, Never>] = [:]
    private var nextRequestId: Int32 = 1
    private var streamTask: Task<Void, Never>?
    private var lastStreamFailTime: Date?
    private var cancelWatermark: Int32 = 0

    init(grpcClient: GrpcClient) {
        self.grpcClient = grpcClient
    }

    // MARK: - Public API

    /// Fetch an image by reference. Returns the response, or nil on stream failure.
    func fetch(ref: MMImageRef, etag: String?) async -> MMImageResponse? {
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
                    if let cont = pendingResponses.removeValue(forKey: requestId) {
                        cont.resume(returning: nil)
                    }
                }
            }
        }

        return response
    }

    /// Send a cancel-stale watermark. The server will drop responses
    /// for requests before this ID.
    func cancelStale() async {
        cancelWatermark = nextRequestId - 1
        guard let writer = self.writer else { return }

        var cancel = MMCancelStale()
        cancel.beforeRequestID = cancelWatermark

        var request = MMImageRequest()
        request.cancelStale = cancel

        try? await writer.write(request)
    }

    /// Shut down the stream.
    func close() {
        streamTask?.cancel()
        streamTask = nil
        writer = nil
        failAllPending()
    }

    // MARK: - Stream Management

    private var connecting = false

    private func ensureStream() async {
        // Already connected
        if writer != nil { return }

        // Another caller is already connecting — wait for it
        if connecting {
            await withCheckedContinuation { continuation in
                writerContinuations.append(continuation)
            }
            return
        }

        connecting = true

        // Backoff: if stream failed recently, wait
        if let lastFail = lastStreamFailTime,
           Date().timeIntervalSince(lastFail) < 2.0 {
            try? await Task.sleep(for: .seconds(2))
        }

        startStream()

        // Wait for the writer to be set by the requestProducer closure
        await withCheckedContinuation { continuation in
            writerContinuations.append(continuation)
        }
        connecting = false
    }

    private func startStream() {
        streamTask = Task { [weak self] in
            guard let self else { return }

            do {
                logger.info("Opening image stream...")
                let service = try await self.grpcClient.imageService
                let metadata = await self.grpcClient.authMetadataForImageStream()
                logger.info("Got service + metadata, calling streamImages")

                try await service.streamImages(
                    metadata: metadata,
                    requestProducer: { writer in
                        await self.setWriter(writer)
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
                    onResponse: { response in
                        for try await message in response.messages {
                            await self.handleResponse(message)
                        }
                    }
                )
            } catch is CancellationError {
                logger.info("Image stream cancelled")
            } catch {
                logger.error("Image stream failed: \(String(describing: error))")
            }

            // Stream ended — clean up
            await self.onStreamEnded()
        }
    }

    private func setWriter(_ w: RPCWriter<MMImageRequest>) {
        logger.info("Image stream writer ready")
        self.writer = w
        let waiting = writerContinuations
        writerContinuations.removeAll()
        for c in waiting { c.resume() }
    }

    private func handleResponse(_ response: MMImageResponse) {
        let requestId = response.requestID

        // Ignore responses for cancelled requests
        if requestId <= cancelWatermark {
            return
        }

        if let continuation = pendingResponses.removeValue(forKey: requestId) {
            continuation.resume(returning: response)
        }
    }

    private func onStreamEnded() {
        lastStreamFailTime = Date()
        writer = nil
        streamTask = nil
        connecting = false
        let waiting = writerContinuations
        writerContinuations.removeAll()
        for c in waiting { c.resume() }
        failAllPending()
    }

    private func failAllPending() {
        for (_, continuation) in pendingResponses {
            continuation.resume(returning: nil)
        }
        pendingResponses.removeAll()
    }
}
