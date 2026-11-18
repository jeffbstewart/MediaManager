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
/// File-backed at `reading-progress-queue.json` in the `progress` area
/// of the current storage scope (see `StorageScope`): the ids are the
/// server's own, so a write is only ever sent to the server and
/// account that recorded it. With no scope bound, reads return
/// nothing, writes are dropped and `flush` sends nothing. A scope
/// change stops a running flush before anything more is sent, drops
/// the queue from memory and reloads from the new scope's folder. The
/// file from before scopes is claimed on the first bind. Atomic write
/// protocol so a crash mid-flush can't leave a half-encoded queue on
/// disk.
///
/// Accessed via `ReadingProgressQueue.shared`. Not injected through
/// SwiftUI's environment because actors aren't `Observable` — and
/// the queue's only readers are bridge code (the reader and the
/// flusher) where a singleton is the simpler shape.
public actor ReadingProgressQueue: StorageScopeParticipant {
    public static let shared = ReadingProgressQueue(
        scope: .shared,
        legacyDirectory: FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first,
        localStore: .shared)

    static let fileName = "reading-progress-queue.json"

    private let scope: StorageScope
    /// Where the file was kept before scopes; claimed on bind.
    private let legacyDirectory: URL?
    /// Mirror of every recorded position; see `record`.
    private let localStore: LocalProgressStore
    private var binding: ScopedFolderBinding = .notYetRead
    private var entries: [Int64: ReadingProgressQueueEntry] = [:]
    /// Goes up on every scope change. A flush sends only while it
    /// still matches the value it started with.
    private var epoch: UInt64 = 0
    private var flushTask: Task<Void, Never>?
    /// Tests watch this to see a scope change begin.
    var currentEpochForTesting: UInt64 { epoch }

    /// `scope` supplies the folder; tests pass one over temp folders.
    init(scope: StorageScope, legacyDirectory: URL?, localStore: LocalProgressStore) {
        self.scope = scope
        self.legacyDirectory = legacyDirectory
        self.localStore = localStore
        // Register before the first read of `scope.current`, so no
        // change is missed.
        scope.register(self)
    }

    // MARK: - Scope

    public func storageScopeWillChange() async {
        epoch &+= 1
        // Nothing queued under this scope may be sent once it changes:
        // stop the flush and wait for its send to finish or fail.
        if let running = flushTask {
            running.cancel()
            await running.value
            flushTask = nil
        }
        binding = .unbound
        entries.removeAll()
    }

    public func storageScopeDidChange(to newScope: StorageScopeID?) {
        bind()
    }

    private func bind() {
        epoch &+= 1
        entries.removeAll()
        binding = .unbound
        guard scope.current != nil, let folder = scope.directory(for: .progress) else { return }
        if let legacyDirectory {
            scope.claimLegacy(at: legacyDirectory.appendingPathComponent(Self.fileName),
                              into: .progress, as: Self.fileName)
        }
        binding = .bound(folder)
        let loaded = Self.load(at: folder.appendingPathComponent(Self.fileName))
        entries = loaded
        // Capture count into a local before logging — the autoclosure
        // for the log message is non-isolated, so reading actor state
        // through it would be a strict-concurrency violation.
        let count = loaded.count
        logger.info("ReadingProgressQueue loaded \(count) pending entries")
    }

    /// The bound scope's folder, or nil when nothing is bound. On
    /// launch the scope may already be bound, restored from disk.
    private var folder: URL? {
        if case .notYetRead = binding { bind() }
        return binding.folder
    }

    /// Records a relocation. Overwrites any prior entry for the same
    /// book — the server only cares about the latest position, and
    /// keeping a sequence wastes disk for no benefit. Persists
    /// immediately so a crash before the next flush still preserves
    /// the user's place. Dropped when no scope is bound.
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
        guard folder != nil else { return }
        let clampedFraction = fraction.clamped(to: 0...1)
        let entry = ReadingProgressQueueEntry(
            mediaItemId: mediaItemId,
            locator: locator,
            fraction: clampedFraction,
            recordedAt: recordedAt)
        entries[mediaItemId] = entry
        persist()
        await localStore.recordReading(
            mediaItemId: mediaItemId,
            locator: locator,
            fraction: clampedFraction)
    }

    /// Entry for a specific book, or nil. Used by the reader on
    /// open to resume from local state when newer than what the
    /// server knows.
    public func entry(mediaItemId: Int64) -> ReadingProgressQueueEntry? {
        guard folder != nil else { return nil }
        return entries[mediaItemId]
    }

    /// All currently-pending entries, in arbitrary order. To send
    /// them, use `flush`, which stops if the scope changes.
    public func pending() -> [ReadingProgressQueueEntry] {
        guard folder != nil else { return [] }
        return Array(entries.values)
    }

    /// Send every pending entry with `send`, removing each one it
    /// sends without error. Failures stay queued for the next flush.
    ///
    /// Each send happens only if the scope is still the one the
    /// entries were loaded from, checked just before it starts; a
    /// scope change cancels the send in progress and waits for it, so
    /// nothing queued under one server or account reaches another.
    /// Does nothing when no scope is bound. A call while a flush is
    /// running waits for that one instead of starting another.
    public func flush(
        _ send: @escaping @Sendable (ReadingProgressQueueEntry) async throws -> Void
    ) async {
        if let running = flushTask {
            await running.value
            return
        }
        guard folder != nil, !entries.isEmpty else { return }
        let started = epoch
        let task = Task { await self.drain(startedIn: started, send: send) }
        flushTask = task
        await task.value
    }

    private func drain(
        startedIn started: UInt64,
        send: @Sendable (ReadingProgressQueueEntry) async throws -> Void
    ) async {
        defer { if epoch == started { flushTask = nil } }
        let batch = Array(entries.values)
        logger.info("flushing \(batch.count) pending reading-progress entries")
        for entry in batch {
            guard epoch == started, !Task.isCancelled else {
                logger.info("flush stopped: the storage scope changed")
                return
            }
            do {
                try await send(entry)
            } catch {
                guard epoch == started else { return }
                // Single failure shouldn't abort the rest — partial
                // progress beats none. Next flush re-tries the
                // unflushed entries.
                logger.warning("flush failed for mediaItemId=\(entry.mediaItemId): \(error.localizedDescription)")
                continue
            }
            guard epoch == started else { return }
            markFlushed(mediaItemId: entry.mediaItemId, asOf: entry.recordedAt)
        }
    }

    /// Removes an entry, but only if its `recordedAt` still matches
    /// the timestamp the caller saw at flush start. This guards
    /// against the race where a new relocation overwrites the entry
    /// while the flush call is in flight — we'd rather keep the
    /// fresh write and re-send it next tick than drop it.
    public func markFlushed(mediaItemId: Int64, asOf recordedAt: Date) {
        guard folder != nil, let existing = entries[mediaItemId] else { return }
        if existing.recordedAt == recordedAt {
            entries.removeValue(forKey: mediaItemId)
            persist()
        } else {
            // Newer write landed during the flush. Keep it; next
            // tick will pick it up.
            logger.info("flush race: skipped clearing mediaItemId=\(mediaItemId) — newer entry exists")
        }
    }

    public var count: Int {
        guard folder != nil else { return 0 }
        return entries.count
    }

    /// Drop every queued write. Used when the account is deleted: the
    /// server has nothing left to apply them to.
    public func clear() {
        _ = folder
        entries.removeAll()
        persist()
    }

    // MARK: - Disk persistence

    private func persist() {
        guard let folder else { return }
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            encoder.outputFormatting = [.sortedKeys]
            // Encode as an array — JSON keys must be strings; using
            // an array round-trips the (Int64) mediaItemId verbatim
            // and is easy to read in a debugger.
            let data = try encoder.encode(Array(entries.values))
            try data.write(to: folder.appendingPathComponent(Self.fileName), options: [.atomic])
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
