import Foundation
import GRPCCore
import GRPCNIOTransportHTTP2
import GRPCProtobuf
import UIKit
import os.log

private let logger = Logger(subsystem: "net.stewart.mediamanager", category: "ImageStreamClient")

/// Manages a single bidirectional gRPC stream for image delivery.
/// Opens the stream lazily on first request, reconnects transparently on failure.
actor ImageStreamClient {

    private let grpcClient: GrpcClient
    private var writer: (RPCWriter<MMImageRequest>)?
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

    private func ensureStream() async {
        guard streamTask == nil || writer == nil else { return }

        // Backoff: if stream failed recently, wait
        if let lastFail = lastStreamFailTime,
           Date().timeIntervalSince(lastFail) < 2.0 {
            try? await Task.sleep(for: .seconds(2))
        }

        streamTask?.cancel()
        startStream()
    }

    private func startStream() {
        streamTask = Task { [weak self] in
            guard let self else { return }

            do {
                let service = try await self.grpcClient.imageService
                let metadata = await self.grpcClient.authMetadataForImageStream()

                try await service.streamImages(
                    metadata: metadata,
                    requestProducer: { writer in
                        await self.setWriter(writer)
                        // Keep the request stream open until cancelled
                        while !Task.isCancelled {
                            try await Task.sleep(for: .seconds(60))
                        }
                    },
                    onResponse: { response in
                        for try await message in response.messages {
                            await self.handleResponse(message)
                        }
                    }
                )
            } catch is CancellationError {
                // Normal shutdown
            } catch {
                logger.warning("Image stream ended: \(error.localizedDescription)")
            }

            // Stream ended — clean up
            await self.onStreamEnded()
        }
    }

    private func setWriter(_ w: RPCWriter<MMImageRequest>) {
        self.writer = w
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
        failAllPending()
    }

    private func failAllPending() {
        for (_, continuation) in pendingResponses {
            continuation.resume(returning: nil)
        }
        pendingResponses.removeAll()
    }
}
