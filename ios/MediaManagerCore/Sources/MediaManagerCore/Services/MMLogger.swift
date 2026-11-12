import Foundation
import os.log
import MediaManagerProtos

/// Subsystem-keyed logger. Every call:
///   1. Forwards to Apple's `Logger` so Xcode console / Console.app /
///      `log stream` continues to work without changes.
///   2. Enqueues a record onto `LogBuffer.shared` for shipment to
///      Binnacle via `ObservabilityService.StreamLogs`.
///
/// Call sites declare a logger at file scope:
/// ```
/// private let log = MMLogger(category: "Auth")
/// ```
/// then call `log.info("…")`, `log.error("…", error: err)`, etc.
public struct MMLogger: Sendable {
    public let category: String
    private let osLogger: Logger

    public init(category: String) {
        self.category = category
        self.osLogger = Logger(subsystem: "net.stewart.mediamanager", category: category)
    }

    public func trace(_ message: @autoclosure () -> String, attributes: [String: String] = [:]) {
        let m = message()
        osLogger.trace("\(m, privacy: .public)")
        emit(.trace, m, attributes: attributes)
    }

    public func debug(_ message: @autoclosure () -> String, attributes: [String: String] = [:]) {
        let m = message()
        osLogger.debug("\(m, privacy: .public)")
        emit(.debug, m, attributes: attributes)
    }

    public func info(_ message: @autoclosure () -> String, attributes: [String: String] = [:]) {
        let m = message()
        osLogger.info("\(m, privacy: .public)")
        emit(.info, m, attributes: attributes)
    }

    public func warning(_ message: @autoclosure () -> String, attributes: [String: String] = [:]) {
        let m = message()
        osLogger.warning("\(m, privacy: .public)")
        emit(.warn, m, attributes: attributes)
    }

    public func error(_ message: @autoclosure () -> String,
               error: Error? = nil,
               attributes: [String: String] = [:]) {
        let m = message()
        if let error {
            osLogger.error("\(m, privacy: .public): \(String(describing: error), privacy: .public)")
        } else {
            osLogger.error("\(m, privacy: .public)")
        }
        emit(.error, m, error: error, attributes: attributes)
    }

    private func emit(_ severity: MMLogSeverity,
                      _ message: String,
                      error: Error? = nil,
                      attributes: [String: String]) {
        let (eType, eMsg) = unpack(error: error)
        LogBuffer.shared.enqueue(PendingLogRecord(
            timestamp: Date(),
            severity: severity,
            category: category,
            message: message,
            exceptionType: eType,
            exceptionMessage: eMsg,
            attributes: attributes))
    }
}

/// Decompose a Swift error into (typeName, localizedMessage). Swift errors
/// don't carry stack traces unless explicitly captured, so the third proto
/// field (exception_stacktrace) stays nil.
private func unpack(error: Error?) -> (type: String?, message: String?) {
    guard let error else { return (nil, nil) }
    let typeName = String(reflecting: type(of: error))
    return (typeName, error.localizedDescription)
}

/// Internal record format used between call sites and the streamer. Holds
/// only the fields the call site can know; service identity and global
/// attributes (device model, locale) are stamped in by `LogStreamer` when
/// it builds the proto on the way out.
public struct PendingLogRecord: Sendable {
    public let timestamp: Date
    public let severity: MMLogSeverity
    public let category: String
    public let message: String
    public let exceptionType: String?
    public let exceptionMessage: String?
    public let attributes: [String: String]
}

/// Bounded ring buffer between call sites and the network streamer.
/// Single producer side (anywhere `MMLogger` is called) and single
/// consumer side (the live `LogStreamer` instance).
///
/// Backed by an `AsyncStream` with a drop-oldest buffering policy so that
/// log calls never block and never grow memory unboundedly during outages.
/// Records yielded before the streamer starts iterating are buffered and
/// drained in order on first iteration.
public final class LogBuffer: @unchecked Sendable {
    public static let shared = LogBuffer(capacity: 2000)

    public let stream: AsyncStream<PendingLogRecord>
    private let continuation: AsyncStream<PendingLogRecord>.Continuation

    public init(capacity: Int) {
        var storedContinuation: AsyncStream<PendingLogRecord>.Continuation!
        self.stream = AsyncStream(
            PendingLogRecord.self,
            bufferingPolicy: .bufferingNewest(capacity)
        ) { c in
            storedContinuation = c
        }
        self.continuation = storedContinuation
    }

    public func enqueue(_ record: PendingLogRecord) {
        continuation.yield(record)
    }
}
