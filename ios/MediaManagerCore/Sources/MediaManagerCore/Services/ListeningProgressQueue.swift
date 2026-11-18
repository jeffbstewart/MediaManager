import Foundation
import MediaManagerProtos

private let logger = MMLogger(category: "ListeningProgressQueue")

/// File-backed coalescing queue for outbound ReportListeningProgress
/// RPCs. Writes that pile up while the network is gone (or that
/// arrive faster than the server can chew through them) live here
/// keyed by track id — the latest position per track wins, since
/// that's the only one the resume-prompt and recently-played
/// carousels care about.
///
/// Lives next to DownloadManager's pending-video-progress flush —
/// same shape, separate persistence so audio doesn't need a
/// per-trip-through-DownloadStore detour.
///
/// The file is `listening-progress-queue.json` in the `progress` area
/// of the current storage scope (see `StorageScope`): track ids are
/// the server's own, so a write is only ever sent to the server and
/// account that recorded it. With no scope bound, reads return
/// nothing, writes are dropped and `flush` sends nothing. A scope
/// change stops a running flush before anything more is sent, saves
/// what is pending to the old scope's folder, and reloads from the
/// new one. The file from before scopes is claimed on the first bind.
public actor ListeningProgressQueue: StorageScopeParticipant {
    public static let shared = ListeningProgressQueue(
        scope: .shared,
        legacyDirectory: FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first)

    static let fileName = "listening-progress-queue.json"

    /// One queued report. Codable so the file format is easy to
    /// inspect / migrate. `recordedAt` is the wall-clock when the
    /// position was captured; the server uses it tie-break against
    /// concurrent writes from other devices.
    public struct Entry: Codable, Sendable {
        public var trackId: Int64
        public var position: Double
        public var duration: Double?
        public var recordedAt: Date
    }

    private let scope: StorageScope
    /// Where the file was kept before scopes; claimed on bind.
    private let legacyDirectory: URL?
    private var binding: ScopedFolderBinding = .notYetRead
    /// trackId → latest entry. The dict semantics are what coalesces
    /// — same-track writes overwrite; no append-only growth.
    private var queue: [Int64: Entry] = [:]
    /// Debounce flag. We persist the queue after each enqueue so a
    /// hard kill doesn't lose progress, but coalesce bursts (a
    /// 10-second tick across N tracks during shuffle, say) into a
    /// single disk write per quiet period.
    private var saveScheduled = false
    private var dirty = false
    /// Goes up on every scope change. A flush sends only while it
    /// still matches the value it started with.
    private var epoch: UInt64 = 0
    private var flushTask: Task<Void, Never>?
    /// Tests watch this to see a scope change begin.
    var currentEpochForTesting: UInt64 { epoch }

    /// `scope` supplies the folder; tests pass one over temp folders.
    init(scope: StorageScope, legacyDirectory: URL?) {
        self.scope = scope
        self.legacyDirectory = legacyDirectory
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
        // Save what this scope has while its folder still exists:
        // signing back in to the same account sends it then.
        flushIfDirty()
        binding = .unbound
        queue.removeAll()
        dirty = false
    }

    public func storageScopeDidChange(to newScope: StorageScopeID?) {
        bind()
    }

    private func bind() {
        epoch &+= 1
        queue.removeAll()
        dirty = false
        binding = .unbound
        guard scope.current != nil, let folder = scope.directory(for: .progress) else { return }
        if let legacyDirectory {
            scope.claimLegacy(at: legacyDirectory.appendingPathComponent(Self.fileName),
                              into: .progress, as: Self.fileName)
        }
        binding = .bound(folder)
        guard let data = try? Data(contentsOf: folder.appendingPathComponent(Self.fileName)),
              let entries = try? JSONDecoder().decode([Entry].self, from: data) else {
            return
        }
        for entry in entries { queue[entry.trackId] = entry }
        if !queue.isEmpty {
            logger.info("Loaded \(entries.count) pending listening-progress entries from disk")
        }
    }

    /// The bound scope's folder, or nil when nothing is bound. On
    /// launch the scope may already be bound, restored from disk.
    private var folder: URL? {
        if case .notYetRead = binding { bind() }
        return binding.folder
    }

    /// Add or update an entry. The dict's set semantics keep only the
    /// latest position per track — if the user ticks past 30 seconds
    /// while offline, we report 30, not [10, 20, 30]. Dropped when no
    /// scope is bound.
    public func enqueue(trackId: Int64, position: Double, duration: Double?) {
        guard folder != nil else { return }
        queue[trackId] = Entry(
            trackId: trackId,
            position: position,
            duration: duration,
            recordedAt: Date())
        markDirty()
    }

    /// Snapshot the current queue. To send it, use `flush`, which
    /// stops if the scope changes.
    public func pending() -> [Entry] {
        guard folder != nil else { return [] }
        return Array(queue.values)
    }

    /// Drop a successfully-flushed entry.
    public func remove(trackId: Int64) {
        guard folder != nil else { return }
        if queue.removeValue(forKey: trackId) != nil {
            markDirty()
        }
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
    public func flush(_ send: @escaping @Sendable (Entry) async throws -> Void) async {
        if let running = flushTask {
            await running.value
            return
        }
        guard folder != nil, !queue.isEmpty else { return }
        let started = epoch
        let task = Task { await self.drain(startedIn: started, send: send) }
        flushTask = task
        await task.value
    }

    private func drain(startedIn started: UInt64, send: @Sendable (Entry) async throws -> Void) async {
        defer { if epoch == started { flushTask = nil } }
        for entry in Array(queue.values) {
            guard epoch == started, !Task.isCancelled else {
                logger.info("flush stopped: the storage scope changed")
                return
            }
            do {
                try await send(entry)
            } catch {
                // Leave on the queue; the next flush picks it up.
                // Don't break the loop — a one-track-not-found failure
                // shouldn't block the rest.
                continue
            }
            guard epoch == started else { return }
            // Keep a newer position recorded while this one was sent.
            if queue[entry.trackId]?.recordedAt == entry.recordedAt {
                remove(trackId: entry.trackId)
            }
        }
    }

    /// Clear everything. Used when the account is deleted — the
    /// server has nothing left to apply the queued writes to.
    public func clear() {
        _ = folder
        queue.removeAll()
        markDirty()
    }

    // MARK: - Persistence

    /// Mark the queue dirty and ensure exactly one save task is
    /// pending. Same shape as ImageDiskCache's debounced save.
    private func markDirty() {
        dirty = true
        guard !saveScheduled else { return }
        saveScheduled = true
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            await self?.saveAfterDelay()
        }
    }

    private func saveAfterDelay() {
        saveScheduled = false
        flushIfDirty()
    }

    /// Write the queue to the bound scope's folder if it changed.
    private func flushIfDirty() {
        guard dirty, let folder = binding.folder else { return }
        dirty = false
        let entries = Array(queue.values)
        if let data = try? JSONEncoder().encode(entries) {
            try? data.write(to: folder.appendingPathComponent(Self.fileName), options: .atomic)
        }
    }
}
