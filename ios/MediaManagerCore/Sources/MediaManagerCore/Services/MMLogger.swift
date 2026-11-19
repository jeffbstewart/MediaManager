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

/// Bounded queue between call sites and the network streamer. Any number
/// of producers (wherever `MMLogger` is called) and one consumer (the live
/// `LogStreamer`).
///
/// Log calls never block, and memory stays bounded during outages: past
/// `capacity`, the oldest records are dropped. Records enqueued before the
/// streamer starts are kept and delivered, in order, when it does.
///
/// Cancellation-safe by design (#84). This used to be an `AsyncStream`,
/// and cancelling a task that is waiting on an `AsyncStream` iterator ends
/// the stream for good. gRPC cancels the log upload's request producer
/// whenever the connection drops, which on iOS happens every time the app
/// goes to the background, and so does `LogStreamer.stop()`. After the
/// first of either, nothing more was ever delivered until the process
/// was killed. Here a cancelled `next()` just returns nil; the queue and
/// everything in it stay usable.
public final class LogBuffer: @unchecked Sendable {
    public static let shared = LogBuffer(capacity: 2000)

    private let capacity: Int
    private let lock = NSLock()
    /// Guarded by `lock`. Oldest first.
    private var records: [PendingLogRecord] = []
    /// Guarded by `lock`. The consumer waiting in `next()`, if any.
    private var waiter: (id: UInt64, continuation: CheckedContinuation<PendingLogRecord?, Never>)?
    /// Guarded by `lock`.
    private var nextWaiterId: UInt64 = 0

    public init(capacity: Int) {
        self.capacity = max(1, capacity)
    }

    /// Records waiting to be taken. For tests and diagnostics.
    public var count: Int {
        lock.withLock { records.count }
    }

    public func enqueue(_ record: PendingLogRecord) {
        let waiting: CheckedContinuation<PendingLogRecord?, Never>? = lock.withLock {
            if let w = waiter {
                waiter = nil
                return w.continuation
            }
            records.append(record)
            if records.count > capacity {
                records.removeFirst(records.count - capacity)
            }
            return nil
        }
        waiting?.resume(returning: record)
    }

    /// Put back a record that was taken but not delivered, so it goes
    /// out first next time. Dropped if the queue has filled up meanwhile.
    public func pushFront(_ record: PendingLogRecord) {
        let waiting: CheckedContinuation<PendingLogRecord?, Never>? = lock.withLock {
            if let w = waiter {
                waiter = nil
                return w.continuation
            }
            if records.count < capacity {
                records.insert(record, at: 0)
            }
            return nil
        }
        waiting?.resume(returning: record)
    }

    /// The oldest record, waiting for one if the queue is empty. Returns
    /// nil only when the calling task is cancelled; nothing is lost and
    /// the queue stays usable. One consumer at a time: a second waiter
    /// replaces the first, which gets nil.
    public func next() async -> PendingLogRecord? {
        let id: UInt64 = lock.withLock {
            nextWaiterId &+= 1
            return nextWaiterId
        }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<PendingLogRecord?, Never>) in
                let (immediate, replaced): (PendingLogRecord??, CheckedContinuation<PendingLogRecord?, Never>?) = lock.withLock {
                    if !records.isEmpty {
                        return (.some(records.removeFirst()), nil)
                    }
                    if Task.isCancelled {
                        return (.some(nil), nil)
                    }
                    let previous = waiter?.continuation
                    waiter = (id, continuation)
                    return (nil, previous)
                }
                replaced?.resume(returning: nil)
                if let immediate {
                    continuation.resume(returning: immediate)
                }
            }
        } onCancel: {
            let cancelled: CheckedContinuation<PendingLogRecord?, Never>? = lock.withLock {
                guard let w = waiter, w.id == id else { return nil }
                waiter = nil
                return w.continuation
            }
            cancelled?.resume(returning: nil)
        }
    }
}
