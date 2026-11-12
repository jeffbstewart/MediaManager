import Foundation
import MediaManagerProtos

private let logger = MMLogger(category: "ReadingProgressQueue")

/// One pending reading-progress write. Captured at the moment the
/// reader's `relocated` event fired — `recordedAt` is the client
/// wall-clock at that moment, which the server uses to resolve
/// most-recent-wins on its side. Flushing later doesn't change the
/// timestamp; if a fresher write happens before the flush succeeds,
/// it overwrites this entry (same `mediaItemId` key) and the
/// flusher only ever sends the latest.
public struct ReadingProgressQueueEntry: Codable, Sendable, Hashable {
    public let mediaItemId: Int64
    public var locator: String
    public var fraction: Double
    /// Client wall-clock at the relocation moment. Carried verbatim
    /// to the server in the `client_recorded_at` proto field.
    public var recordedAt: Date
}

/// Persistent buffer of reading-progress writes that haven't been
/// confirmed by the server yet. Two reasons it exists:
///
///   1. **Offline writes.** Reader fires `relocated` → entry lands
///      in this queue regardless of network state. Flusher drains
///      when reachable.
///   2. **Local resume.** When reopening a book the reader checks
///      this queue first; if a local entry exists and is newer than
///      the server's known state, the local one wins. Lets the user
///      resume from where they left off offline even if the server
///      thinks they're earlier in the book.
///
/// Keyed by `mediaItemId` — only the most recent write per book is
/// ever pending, since older writes are strictly less-current and
/// would lose the most-recent-wins resolution anyway.
///
/// File-backed at `<Application Support>/reading-progress-queue.json`.
/// Atomic write protocol so a crash mid-flush can't leave a half-
/// encoded queue on disk.
///
/// Accessed via `ReadingProgressQueue.shared`. Not injected through
/// SwiftUI's environment because actors aren't `Observable` — and
/// the queue's only readers are bridge code (the reader and the
/// flusher) where a singleton is the simpler shape.
public actor ReadingProgressQueue {
    public static let shared = ReadingProgressQueue()

    private let path: URL
    private var entries: [Int64: ReadingProgressQueueEntry] = [:]

    private init() {
        let appSupport = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        path = appSupport.appendingPathComponent("reading-progress-queue.json")
        let loaded = Self.load(at: path)
        entries = loaded
        // Capture count into a local before logging — the autoclosure
        // for the log message is non-isolated, so reading actor state
        // through it would be a strict-concurrency violation.
        let count = loaded.count
        logger.info("ReadingProgressQueue loaded \(count) pending entries")
    }

    /// Records a relocation. Overwrites any prior entry for the same
    /// book — the server only cares about the latest position, and
    /// keeping a sequence wastes disk for no benefit. Persists
    /// immediately so a crash before the next flush still preserves
    /// the user's place.
    ///
    /// Also mirrors the position into `LocalProgressStore` so the
    /// resume marker survives the flush-and-delete cycle. The queue
    /// itself gets `markFlushed` once a record is shipped to the
    /// server, which clears its entry; without the mirror, a user
    /// who read online would have no resume locator when they went
    /// offline, because both the queue (flushed) and the server
    /// (unreachable) would be unavailable. LocalProgressStore is
    /// independent of the flush state, so it's always there.
    public func record(
        mediaItemId: Int64, locator: String, fraction: Double, recordedAt: Date
    ) async {
        let clampedFraction = fraction.clamped(to: 0...1)
        let entry = ReadingProgressQueueEntry(
            mediaItemId: mediaItemId,
            locator: locator,
            fraction: clampedFraction,
            recordedAt: recordedAt)
        entries[mediaItemId] = entry
        persist()
        await LocalProgressStore.shared.recordReading(
            mediaItemId: mediaItemId,
            locator: locator,
            fraction: clampedFraction)
    }

    /// Entry for a specific book, or nil. Used by the reader on
    /// open to resume from local state when newer than what the
    /// server knows.
    public func entry(mediaItemId: Int64) -> ReadingProgressQueueEntry? {
        entries[mediaItemId]
    }

    /// All currently-pending entries, in arbitrary order. Caller
    /// (the flusher) iterates and sends each one.
    public func pending() -> [ReadingProgressQueueEntry] {
        Array(entries.values)
    }

    /// Removes an entry, but only if its `recordedAt` still matches
    /// the timestamp the caller saw at flush start. This guards
    /// against the race where a new relocation overwrites the entry
    /// while the flush call is in flight — we'd rather keep the
    /// fresh write and re-send it next tick than drop it.
    public func markFlushed(mediaItemId: Int64, asOf recordedAt: Date) {
        guard let existing = entries[mediaItemId] else { return }
        if existing.recordedAt == recordedAt {
            entries.removeValue(forKey: mediaItemId)
            persist()
        } else {
            // Newer write landed during the flush. Keep it; next
            // tick will pick it up.
            logger.info("flush race: skipped clearing mediaItemId=\(mediaItemId) — newer entry exists")
        }
    }

    public var count: Int { entries.count }

    // MARK: - Disk persistence

    private func persist() {
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            encoder.outputFormatting = [.sortedKeys]
            // Encode as an array — JSON keys must be strings; using
            // an array round-trips the (Int64) mediaItemId verbatim
            // and is easy to read in a debugger.
            let data = try encoder.encode(Array(entries.values))
            try data.write(to: path, options: [.atomic])
        } catch {
            logger.error("failed to persist reading-progress queue", error: error)
        }
    }

    private static func load(at url: URL) -> [Int64: ReadingProgressQueueEntry] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [:] }
        do {
            let data = try Data(contentsOf: url)
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            let arr = try decoder.decode([ReadingProgressQueueEntry].self, from: data)
            var dict: [Int64: ReadingProgressQueueEntry] = [:]
            for e in arr { dict[e.mediaItemId] = e }
            return dict
        } catch {
            logger.error("failed to load reading-progress queue; starting empty", error: error)
            return [:]
        }
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        max(range.lowerBound, min(self, range.upperBound))
    }
}
