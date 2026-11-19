import Foundation
import GRPCCore
import UIKit
import MediaManagerProtos

/// What `LogStreamer` needs from the gRPC layer. `GrpcClient` conforms;
/// tests use a fake.
public protocol LogStreamTransport: Sendable {
    /// Goes up whenever the connection or the signed-in account may have
    /// changed (see `GrpcClient.connectionGeneration`).
    var connectionGeneration: UInt64 { get async }

    /// Run one `ObservabilityService/StreamLogs` call: `producer` writes
    /// records until it returns, then the server's ack comes back.
    func streamLogs(
        producer: @Sendable @escaping (RPCWriter<MMLogRecord>) async throws -> Void
    ) async throws -> MMStreamLogsAck
}

extension GrpcClient: LogStreamTransport {}

/// Thrown out of a session to end it early because the connection or
/// account changed under it; the run loop reconnects at once.
struct LogStreamConnectionChanged: Error {}

/// Drains `LogBuffer` onto a long-lived `ObservabilityService.StreamLogs`
/// client-streaming RPC. Started after authentication succeeds; stopped on
/// logout or app shutdown. Reconnects with exponential backoff on stream
/// failure.
///
/// Records logged before `start()` (during login or session restore, say)
/// wait in the buffer and go out on the first successful connect.
///
/// Recovery (#84):
/// - The buffer survives cancellation, so a connection dropped while the
///   app is in the background, or a `stop()`, never silences later logs.
/// - A record taken but not delivered (the write failed, or the session
///   was cancelled) goes back to the front of the buffer.
/// - Each session records `connectionGeneration` when it opens. Before each
///   write it checks again; if the connection or account has changed, it
///   puts the record back and ends, and the next session opens on the
///   current connection. A graceful shutdown would otherwise let the old
///   session carry on, on a connection that may be dead.
public actor LogStreamer {
    /// Snapshot of process / device identity stamped onto every record.
    /// Constructed off-actor (typically on @MainActor where UIDevice can be
    /// read) and passed in at init.
    public struct Identity: Sendable {
        public let serviceVersion: String
        public let baseAttributes: [String: String]
        public init(serviceVersion: String, baseAttributes: [String: String]) {
            self.serviceVersion = serviceVersion
            self.baseAttributes = baseAttributes
        }
    }

    private let transport: any LogStreamTransport
    private let buffer: LogBuffer
    private let identity: Identity
    private let log = MMLogger(category: "LogStreamer")

    private var streamTask: Task<Void, Never>?

    /// Called when the server rejects the stream's Bearer token with
    /// UNAUTHENTICATED. Wired by AuthManager to `performTokenRefresh()`
    /// so a long-running app that's been suspended past its access-token
    /// TTL self-heals without the user noticing. Throttled to one call
    /// per 30 seconds; the retry-loop's exponential backoff handles the
    /// rest.
    private var onAuthFailed: (@Sendable () async -> Void)?
    private var lastAuthRefreshAt: Date = .distantPast

    /// Base of the retry delay, in seconds (2s, 4s, ... capped at 60s).
    /// Tests shrink it.
    private let backoffUnit: Double

    public init(grpcClient: GrpcClient, identity: Identity) {
        self.init(transport: grpcClient, identity: identity)
    }

    init(transport: any LogStreamTransport, identity: Identity,
         buffer: LogBuffer = .shared, backoffUnit: Double = 1.0) {
        self.transport = transport
        self.identity = identity
        self.buffer = buffer
        self.backoffUnit = backoffUnit
    }

    /// Register the callback that AuthManager uses to refresh the
    /// access token when a stream connection is rejected with
    /// UNAUTHENTICATED. Called once from AuthManager.init.
    public func setAuthFailedHandler(_ handler: @escaping @Sendable () async -> Void) {
        self.onAuthFailed = handler
    }

    /// Start the drain loop. No-op if already running.
    public func start() {
        guard streamTask == nil else { return }
        log.info("starting log stream to Binnacle")
        streamTask = Task { [weak self] in
            await self?.runLoop()
        }
    }

    /// Cancel the drain loop. Records logged from now on wait in the
    /// buffer (up to its capacity) until `start()` is called again.
    public func stop() {
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
            } catch is LogStreamConnectionChanged {
                // Not a failure: reopen on the current connection now.
                log.info("connection changed; reopening the log stream")
                attempt = 0
                continue
            } catch is CancellationError {
                if Task.isCancelled { break }
                // The session was cancelled by the transport (connection
                // dropped), not by stop(): treat it as a failure and retry.
                attempt = min(attempt + 1, 5)
                log.warning("stream session cancelled by the connection (attempt #\(attempt))")
            } catch {
                attempt = min(attempt + 1, 5)
                log.warning("stream session failed (attempt #\(attempt)): \(Self.describe(error))")
                // If the server kicked us out for an expired / invalid
                // token, nudge AuthManager to rotate before the next
                // reconnect — without this the retry loop spins
                // forever on UNAUTHENTICATED and Binnacle goes silent.
                // Throttle to one nudge per 30s so a stuck refresh
                // path doesn't get hammered.
                if isUnauthenticatedError(error),
                   let handler = onAuthFailed,
                   Date().timeIntervalSince(lastAuthRefreshAt) > 30 {
                    lastAuthRefreshAt = Date()
                    log.warning("stream session rejected with UNAUTHENTICATED — requesting token refresh")
                    Task { await handler() }
                }
            }
            // Exponential backoff: 2s → 4s → 8s → 16s → 32s → 60s, with up
            // to 500ms of jitter so reconnect storms don't synchronise across
            // restarts.
            let base = min(60.0, pow(2.0, Double(attempt + 1))) * backoffUnit
            let delay = base + Double.random(in: 0...0.5) * backoffUnit
            try? await Task.sleep(for: .seconds(delay))
        }
    }

    private func streamSession() async throws -> MMStreamLogsAck {
        let generation = await transport.connectionGeneration
        let buffer = self.buffer
        let transport = self.transport
        return try await transport.streamLogs { [weak self] writer in
            guard let self else { return }
            while true {
                // nil only when this task is cancelled; nothing is lost.
                guard let pending = await buffer.next() else {
                    throw CancellationError()
                }
                if await transport.connectionGeneration != generation {
                    buffer.pushFront(pending)
                    throw LogStreamConnectionChanged()
                }
                let record = await self.buildRecord(pending)
                do {
                    try await writer.write(record)
                } catch {
                    buffer.pushFront(pending)
                    throw error
                }
            }
        }
    }

    /// For the log: the gRPC status and message, not the user-facing text
    /// (`localizedDescription` of an `RPCError` here reads "Something went
    /// wrong on the server", which says nothing about what happened).
    static func describe(_ error: Error) -> String {
        if let rpc = error as? RPCError {
            return rpc.message.isEmpty ? "\(rpc.code)" : "\(rpc.code): \(rpc.message)"
        }
        return String(describing: error)
    }

    /// Match swift-grpc's UNAUTHENTICATED across both the typed
    /// `RPCError.code` and the localized-description fallback some
    /// transport wrappers produce.
    private func isUnauthenticatedError(_ error: Error) -> Bool {
        if let rpc = error as? RPCError, rpc.code == .unauthenticated { return true }
        let s = error.localizedDescription.lowercased()
        return s.contains("unauthenticated")
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

public extension LogStreamer.Identity {
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
