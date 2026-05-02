import Foundation
import GRPCCore
import UIKit

/// Drains `LogBuffer.shared` onto a long-lived `ObservabilityService.StreamLogs`
/// client-streaming RPC. Started after authentication succeeds; stopped on
/// logout or app shutdown. Reconnects with exponential backoff on stream
/// failure.
///
/// Records yielded into `LogBuffer` before `start()` runs (e.g. logs emitted
/// during login or session restore) are buffered and shipped on first
/// successful connect.
actor LogStreamer {
    /// Snapshot of process / device identity stamped onto every record.
    /// Constructed off-actor (typically on @MainActor where UIDevice can be
    /// read) and passed in at init.
    struct Identity: Sendable {
        let serviceVersion: String
        let baseAttributes: [String: String]
    }

    private let grpcClient: GrpcClient
    private let identity: Identity
    private let log = MMLogger(category: "LogStreamer")

    private var streamTask: Task<Void, Never>?
    /// Iterator survives across reconnect attempts so buffered records aren't
    /// lost. Wrapped in a class so the mutating `next()` doesn't conflict
    /// with actor-isolated property access rules.
    /// Created lazily on first `start()` so a discarded LogStreamer (e.g. the
    /// throwaway AuthManager built before SwiftUI's @State takes over) never
    /// claims the singleton AsyncStream's only consumer iterator.
    private var iterator: IteratorBox?

    init(grpcClient: GrpcClient, identity: Identity) {
        self.grpcClient = grpcClient
        self.identity = identity
    }

    /// Start the drain loop. No-op if already running.
    func start() {
        guard streamTask == nil else { return }
        if iterator == nil {
            iterator = IteratorBox(LogBuffer.shared.stream.makeAsyncIterator())
        }
        log.info("starting log stream to Binnacle")
        streamTask = Task { [weak self] in
            await self?.runLoop()
        }
    }

    /// Cancel the drain loop. The current in-flight stream session will end
    /// at its next iteration; any records yielded after this point stay in
    /// `LogBuffer` until `start()` is called again.
    func stop() {
        guard let task = streamTask else { return }
        log.info("stopping log stream")
        task.cancel()
        streamTask = nil
    }

    private func runLoop() async {
        var attempt = 0
        while !Task.isCancelled {
            do {
                let ack = try await streamSession()
                log.info("stream session ended cleanly: forwarded=\(ack.recordsForwarded) rejected=\(ack.recordsRejected)")
                attempt = 0
            } catch is CancellationError {
                break
            } catch {
                attempt = min(attempt + 1, 5)
                log.warning("stream session failed (attempt #\(attempt)): \(error.localizedDescription)")
            }
            // Exponential backoff: 2s → 4s → 8s → 16s → 32s → 60s, with up
            // to 500ms of jitter so reconnect storms don't synchronise across
            // restarts.
            let base = min(60.0, pow(2.0, Double(attempt + 1)))
            let delay = base + Double.random(in: 0...0.5)
            try? await Task.sleep(for: .seconds(delay))
        }
    }

    private func streamSession() async throws -> MMStreamLogsAck {
        try await grpcClient.streamLogs { [weak self] writer in
            guard let self else { return }
            while !Task.isCancelled {
                guard let pending = await self.nextRecord() else { return }
                let record = await self.buildRecord(pending)
                try await writer.write(record)
            }
        }
    }

    private func nextRecord() async -> PendingLogRecord? {
        await iterator?.next()
    }

    private func buildRecord(_ pending: PendingLogRecord) -> MMLogRecord {
        var record = MMLogRecord()
        record.serviceName = "mediamanager-ios"
        record.serviceVersion = identity.serviceVersion
        record.timestamp = MMTimestamp.with {
            $0.secondsSinceEpoch = Int64(pending.timestamp.timeIntervalSince1970)
        }
        record.severity = pending.severity
        record.loggerName = pending.category
        record.message = pending.message
        if let t = pending.exceptionType { record.exceptionType = t }
        if let m = pending.exceptionMessage { record.exceptionMessage = m }
        var attrs = identity.baseAttributes
        for (k, v) in pending.attributes { attrs[k] = v }
        record.attributes = attrs
        return record
    }
}

/// Class wrapper that lets the actor call the iterator's mutating `next()`
/// without Swift complaining about mutating a stored actor property. Only
/// ever touched by the owning `LogStreamer` actor, so unchecked Sendable
/// conformance is safe.
private final class IteratorBox: @unchecked Sendable {
    private var iter: AsyncStream<PendingLogRecord>.AsyncIterator
    init(_ iter: AsyncStream<PendingLogRecord>.AsyncIterator) { self.iter = iter }
    func next() async -> PendingLogRecord? { await iter.next() }
}

extension LogStreamer.Identity {
    /// Build the identity snapshot from `Bundle` and `UIDevice`. Must be
    /// called from the main actor since `UIDevice.current` is main-isolated
    /// in iOS 16+.
    @MainActor
    static func current() -> LogStreamer.Identity {
        let version = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "unknown"
        let build = (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "unknown"

        let device = UIDevice.current
        let attributes: [String: String] = [
            "device.model": device.model,
            "device.system": "\(device.systemName) \(device.systemVersion)",
            "locale": Locale.current.identifier,
        ]

        return LogStreamer.Identity(
            serviceVersion: "\(version)-\(build)",
            baseAttributes: attributes)
    }
}
