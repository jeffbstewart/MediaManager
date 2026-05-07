import Foundation

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
actor ListeningProgressQueue {
    static let shared = ListeningProgressQueue()

    /// One queued report. Codable so the file format is easy to
    /// inspect / migrate. `recordedAt` is the wall-clock when the
    /// position was captured; the server uses it tie-break against
    /// concurrent writes from other devices.
    struct Entry: Codable, Sendable {
        var trackId: Int64
        var position: Double
        var duration: Double?
        var recordedAt: Date
    }

    private let fileURL: URL
    /// trackId → latest entry. The dict semantics are what coalesces
    /// — same-track writes overwrite; no append-only growth.
    private var queue: [Int64: Entry] = [:]
    private var loaded = false
    /// Debounce flag. We persist the queue after each enqueue so a
    /// hard kill doesn't lose progress, but coalesce bursts (a
    /// 10-second tick across N tracks during shuffle, say) into a
    /// single disk write per quiet period.
    private var saveScheduled = false
    private var dirty = false

    private init() {
        let appSupport = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first!
        try? FileManager.default.createDirectory(
            at: appSupport, withIntermediateDirectories: true)
        fileURL = appSupport.appendingPathComponent("listening-progress-queue.json")
    }

    /// Add or update an entry. The dict's set semantics keep only the
    /// latest position per track — if the user ticks past 30 seconds
    /// while offline, we report 30, not [10, 20, 30].
    func enqueue(trackId: Int64, position: Double, duration: Double?) {
        ensureLoaded()
        queue[trackId] = Entry(
            trackId: trackId,
            position: position,
            duration: duration,
            recordedAt: Date())
        markDirty()
    }

    /// Snapshot the current queue. Used by the flush pump.
    func pending() -> [Entry] {
        ensureLoaded()
        return Array(queue.values)
    }

    /// Drop a successfully-flushed entry. Called by the pump after
    /// each successful RPC.
    func remove(trackId: Int64) {
        ensureLoaded()
        if queue.removeValue(forKey: trackId) != nil {
            markDirty()
        }
    }

    /// Clear everything. Used when the user logs out — the next user
    /// shouldn't inherit queued writes for a different account.
    func clear() {
        ensureLoaded()
        queue.removeAll()
        markDirty()
    }

    // MARK: - Persistence

    private func ensureLoaded() {
        guard !loaded else { return }
        loaded = true
        guard let data = try? Data(contentsOf: fileURL),
              let entries = try? JSONDecoder().decode([Entry].self, from: data) else {
            return
        }
        for entry in entries { queue[entry.trackId] = entry }
        if !queue.isEmpty {
            logger.info("Loaded \(self.queue.count) pending listening-progress entries from disk")
        }
    }

    /// Mark the queue dirty and ensure exactly one save task is
    /// pending. Same shape as ImageDiskCache's debounced save.
    private func markDirty() {
        dirty = true
        guard !saveScheduled else { return }
        saveScheduled = true
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            await self?.flushIfDirty()
        }
    }

    private func flushIfDirty() {
        saveScheduled = false
        guard dirty else { return }
        dirty = false
        let entries = Array(queue.values)
        if let data = try? JSONEncoder().encode(entries) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }
}
