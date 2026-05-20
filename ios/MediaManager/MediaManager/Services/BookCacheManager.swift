import Foundation
import Observation

private let logger = MMLogger(category: "BookCacheManager")

/// Per-book metadata persisted into the on-disk download index. Keyed
/// by `mediaItemId` (the same key the server uses for book identity).
///
/// Codable rather than protobuf because this state is iOS-local and
/// never travels back to the server — keeping it as JSON sidesteps
/// the proto-generation churn for a struct only this file reads.
struct DownloadedBook: Codable, Identifiable, Sendable, Hashable {
    /// `mediaItemId` doubles as the stable identity for SwiftUI lists.
    var id: Int64 { mediaItemId }

    let mediaItemId: Int64
    /// The book's `title_id` on the server. Captured at download time
    /// so offline browse views can look up the cover via the existing
    /// image-cache path (`CachedImage(ref: .posterThumbnail(titleId:))`)
    /// without a server round-trip.
    let titleId: Int64
    let titleName: String
    let authorName: String
    let sizeBytes: Int64
    let downloadedAt: Date
    var lastAccessedAt: Date
    /// 0.0–1.0 — last-known reading position, used by the Downloads
    /// view to surface a progress bar per row. Mirror of the server's
    /// `reading_progress.percent` for the matching media item, kept
    /// in sync by [BookCacheManager.updateLastAccessed].
    var completedFraction: Double
}

/// Surfaces the in-flight state of a single download to SwiftUI. A
/// view observes the manager and renders a progress ring when the
/// matching `mediaItemId` is in `activeDownloads`.
struct BookDownloadProgress: Sendable, Hashable {
    let mediaItemId: Int64
    /// Book title the media item belongs to. Lets aggregate views
    /// (Author / BookSeries "Download all books") answer
    /// "is any download in flight for this title?" without an
    /// external mediaItem → title map.
    let titleId: Int64
    /// Bytes received so far. `totalBytes` is unknown until the manifest
    /// arrives, so the UI shows an indeterminate spinner during the
    /// brief window between download-start and the first manifest.
    var bytesReceived: Int64
    var totalBytes: Int64?
}

/// Result of attempting to start / cancel / delete a download —
/// returned to the caller (typically a SwiftUI action handler) so it
/// can show a localised error. Throws on the failure paths instead of
/// silently no-oping; UI surfaces the message directly.
enum BookCacheError: LocalizedError {
    case alreadyDownloaded(mediaItemId: Int64)
    case downloadInFlight(mediaItemId: Int64)
    case notDownloaded(mediaItemId: Int64)
    case grpcUnavailable
    case ioFailure(underlying: Error)

    var errorDescription: String? {
        switch self {
        case .alreadyDownloaded(let id):
            return "Book \(id) is already downloaded."
        case .downloadInFlight(let id):
            return "Book \(id) is already downloading."
        case .notDownloaded(let id):
            return "Book \(id) isn't downloaded."
        case .grpcUnavailable:
            return "Server connection isn't ready yet."
        case .ioFailure(let underlying):
            return "Disk error: \(underlying.localizedDescription)"
        }
    }
}

/// Orchestrates explicit ebook downloads for offline reading.
///
/// **What this does NOT do:** the convenience cache used by
/// [BookReaderView] when the user just taps "Read" online. That
/// cache lives in `<Caches>/reader/` and is owned by the reader
/// view directly — see `ReaderStaging` over there. The two layers
/// stay separate by design: convenience-cache files are disposable;
/// downloads are pinned and survive storage-pressure eviction.
///
/// **Disk layout:**
/// ```
/// <Application Support>/Downloads/Books/
///     index.json                           // [DownloadedBook]
///     <mediaItemId>.epub                   // raw EPUB bytes
/// ```
///
/// The directory is excluded from iCloud backup (matches the video
/// downloads dir) — books re-download on a new device, no need to
/// blow up users' iCloud quotas with epub bytes.
///
/// **Single source of truth for offline state.** Reachability +
/// user-toggled offline mode live on the existing [DownloadManager]
/// (`isEffectivelyOffline`). This manager observes that flag for the
/// "should I refuse to start a new download right now?" decision but
/// doesn't duplicate the network monitor.
@Observable
@MainActor
final class BookCacheManager {

    /// Current set of fully-downloaded books. Mirrors `index.json` on
    /// disk; mutations go through [BookCacheManager.persistIndex] so
    /// the in-memory and on-disk views never drift.
    private(set) var downloads: [DownloadedBook] = []

    /// `mediaItemId → progress` for downloads currently in flight.
    /// Empty between sessions. Drives the per-book download spinner
    /// in BookDetailView.
    private(set) var activeDownloads: [Int64: BookDownloadProgress] = [:]

    /// `mediaItemId → human-readable error message` for the most
    /// recent failed attempt per book. Tap-to-retry clears the row.
    /// Surfaces a "Retry" affordance with the actual failure text
    /// instead of the silent fall-back-to-Download we had before.
    private(set) var failedDownloads: [Int64: String] = [:]

    private var grpcClient: GrpcClient?
    /// Cancel tokens for in-flight downloads. Cancelling the Task
    /// causes the streaming gRPC call to bail; we then remove the
    /// partial file in the catch handler.
    private var downloadTasks: [Int64: Task<Void, Never>] = [:]

    private let booksDir: URL
    private let indexPath: URL

    init() {
        let appSupport = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let downloadsRoot = appSupport.appendingPathComponent("Downloads", isDirectory: true)
        booksDir = downloadsRoot.appendingPathComponent("Books", isDirectory: true)
        indexPath = booksDir.appendingPathComponent("index.json")

        // Best-effort directory creation here. If it fails (e.g. the
        // sandbox isn't fully set up yet on first launch),
        // [ensureBooksDir] is called again before each download as a
        // defensive backstop — earlier versions silently swallowed
        // this error and every download then failed with a confusing
        // "file doesn't exist" from FileHandle.
        do {
            try FileManager.default.createDirectory(at: booksDir, withIntermediateDirectories: true)
        } catch {
            logger.error("init: createDirectory failed at \(booksDir.path): \(error.localizedDescription)")
        }

        // Exclude from iCloud backup — books re-download cleanly on
        // a new device and we don't want to bloat user iCloud quotas.
        var dir = booksDir
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? dir.setResourceValues(rv)

        downloads = Self.loadIndex(at: indexPath)
        logger.info("BookCacheManager initialised; \(downloads.count) downloaded books at \(booksDir.path)")
    }

    /// Defensive directory-existence check. Cheap when the dir already
    /// exists (a single `fileExists` syscall); creates it otherwise.
    /// Throws so the caller surfaces a real error instead of the
    /// downstream "file doesn't exist" from FileHandle later.
    private func ensureBooksDir() throws {
        var isDir: ObjCBool = false
        let exists = FileManager.default.fileExists(atPath: booksDir.path, isDirectory: &isDir)
        if exists && isDir.boolValue { return }
        try FileManager.default.createDirectory(at: booksDir, withIntermediateDirectories: true)
    }

    func configure(grpcClient: GrpcClient) {
        self.grpcClient = grpcClient
    }

    // MARK: - Read API

    func isDownloaded(_ mediaItemId: Int64) -> Bool {
        downloads.contains { $0.mediaItemId == mediaItemId }
    }

    /// Set of catalog title IDs for which at least one media item
    /// (EPUB / PDF) is cached locally. Lets list and grid views
    /// answer "is this book on the device?" without iterating
    /// `downloads` per row. Mirrors DownloadManager.offlineTitleIds
    /// so list/grid code on the video side and book side reads the
    /// same.
    var offlineTitleIds: Set<Int64> {
        Set(downloads.map { $0.titleId })
    }

    /// Aggregate progress across an explicit set of media-item IDs.
    /// Used by container views (Author, BookSeries "Download all")
    /// to drive a status row. Pure-compute on the existing state.
    struct BulkBookStatus: Equatable {
        let total: Int
        let completed: Int
        let inFlight: Int
        let failed: Int
        var pending: Int { max(0, total - completed - inFlight - failed) }
        var fraction: Double {
            total > 0 ? Double(completed) / Double(total) : 0
        }
        var hasWork: Bool { pending > 0 || failed > 0 }
    }

    /// Aggregate progress across a set of book *titles* (each may
    /// carry multiple editions; the user typically downloads one).
    /// A title counts as completed when any of its media items is
    /// cached; in-flight when an active download exists for any of
    /// its editions. Failures aren't reported here — per-edition
    /// retry lives on BookDetailView and the bulk row's idle-state
    /// label correctly says "Download remaining N books" the next
    /// render.
    func bulkStatus(forTitleIds ids: [Int64]) -> BulkBookStatus {
        guard !ids.isEmpty else {
            return BulkBookStatus(total: 0, completed: 0, inFlight: 0, failed: 0)
        }
        let completedSet = offlineTitleIds
        let inFlightSet = Set(activeDownloads.values.map { $0.titleId })
        var c = 0, f = 0
        for id in ids {
            if completedSet.contains(id) { c += 1 }
            else if inFlightSet.contains(id) { f += 1 }
        }
        return BulkBookStatus(total: ids.count, completed: c, inFlight: f, failed: 0)
    }

    func bulkStatus(forMediaItems ids: [Int64]) -> BulkBookStatus {
        guard !ids.isEmpty else {
            return BulkBookStatus(total: 0, completed: 0, inFlight: 0, failed: 0)
        }
        let completedSet = Set(downloads.map { $0.mediaItemId })
        let inFlightSet = Set(activeDownloads.keys)
        let failedSet = Set(failedDownloads.keys)
        var c = 0, f = 0, x = 0
        for id in ids {
            if completedSet.contains(id) { c += 1 }
            else if inFlightSet.contains(id) { f += 1 }
            else if failedSet.contains(id) { x += 1 }
        }
        return BulkBookStatus(total: ids.count, completed: c, inFlight: f, failed: x)
    }

    /// Returns the on-disk URL for a downloaded book, or `nil` if
    /// the book isn't downloaded or the file went missing (e.g.
    /// user deleted via Files app).
    func localBookURL(_ mediaItemId: Int64) -> URL? {
        guard isDownloaded(mediaItemId) else { return nil }
        let url = booksDir.appendingPathComponent("\(mediaItemId).epub")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Total bytes consumed by downloaded books. Cheap — sums the
    /// already-known sizes from the index; doesn't stat the
    /// filesystem.
    var totalBytes: Int64 {
        downloads.reduce(0) { $0 + $1.sizeBytes }
    }

    /// Bumps `lastAccessedAt` and `completedFraction` for the matching
    /// row. Called by the reader on every relocation so the Downloads
    /// view can surface "currently reading" books at the top and an
    /// up-to-date progress bar per row.
    func updateLastAccessed(_ mediaItemId: Int64, completedFraction: Double) {
        guard let idx = downloads.firstIndex(where: { $0.mediaItemId == mediaItemId }) else { return }
        downloads[idx].lastAccessedAt = Date()
        downloads[idx].completedFraction = completedFraction.clamped(to: 0...1)
        persistIndex()
    }

    // MARK: - Download orchestration

    /// Begins a streaming download. Throws if the book is already
    /// downloaded, already downloading, or the gRPC client hasn't
    /// been configured yet (e.g. the user hasn't logged in).
    /// Otherwise returns immediately; progress is observable via
    /// `activeDownloads[mediaItemId]`.
    func startDownload(
        mediaItemId: Int64,
        titleId: Int64,
        titleName: String,
        authorName: String,
    ) throws {
        if isDownloaded(mediaItemId) { throw BookCacheError.alreadyDownloaded(mediaItemId: mediaItemId) }
        if downloadTasks[mediaItemId] != nil { throw BookCacheError.downloadInFlight(mediaItemId: mediaItemId) }
        guard let grpcClient else { throw BookCacheError.grpcUnavailable }

        // Clear any prior failure for this book so the UI returns to
        // "downloading" instead of "Retry — error".
        failedDownloads.removeValue(forKey: mediaItemId)

        // Defensive: if booksDir didn't get created in init (or got
        // deleted out from under us), make it now. Without this, the
        // FileHandle below throws "file doesn't exist" with no
        // visible explanation in the UI.
        do {
            try ensureBooksDir()
        } catch {
            recordFailure(mediaItemId: mediaItemId,
                message: "Couldn't prepare downloads folder: \(error.localizedDescription)")
            throw BookCacheError.ioFailure(underlying: error)
        }

        activeDownloads[mediaItemId] = BookDownloadProgress(
            mediaItemId: mediaItemId, titleId: titleId, bytesReceived: 0, totalBytes: nil)

        let dest = booksDir.appendingPathComponent("\(mediaItemId).epub")
        // Wipe any leftover partial from a previous failed attempt so
        // we always start at offset 0 with a fresh handle.
        if FileManager.default.fileExists(atPath: dest.path) {
            try? FileManager.default.removeItem(at: dest)
        }
        // createFile returns false silently on parent-dir issues —
        // check the return value so we surface a real error instead
        // of the misleading "file doesn't exist" the FileHandle below
        // would otherwise throw.
        guard FileManager.default.createFile(atPath: dest.path, contents: nil) else {
            let msg = "Couldn't create file at \(dest.path)"
            activeDownloads.removeValue(forKey: mediaItemId)
            recordFailure(mediaItemId: mediaItemId, message: msg)
            throw BookCacheError.ioFailure(
                underlying: NSError(domain: "BookCacheManager", code: 1, userInfo: [
                    NSLocalizedDescriptionKey: msg,
                ]))
        }

        // The chunk closure is @Sendable so it can't mutate a captured
        // counter; route running totals through a small reference-typed
        // box. The MainActor consumer sees only the latest value.
        let counter = ByteCounter()

        // Strong self capture — matches the existing DownloadManager
        // pattern. The Task is bounded by the gRPC streaming call;
        // BookCacheManager itself lives for the app lifetime, so no
        // retain cycle to worry about.
        let task = Task {
            do {
                let handle = try FileHandle(forWritingTo: dest)
                defer { try? handle.close() }

                try await grpcClient.downloadBookFile(mediaItemId: mediaItemId) { chunk in
                    do {
                        try handle.write(contentsOf: chunk.data)
                        let received = counter.add(Int64(chunk.data.count))
                        let total = chunk.totalSize > 0 ? Int64(chunk.totalSize) : nil
                        await self.recordProgress(
                            mediaItemId: mediaItemId,
                            bytesReceived: received,
                            totalBytes: total)
                    } catch {
                        logger.error("download chunk write failed", error: error)
                    }
                }
                self.finishDownload(
                    mediaItemId: mediaItemId,
                    titleId: titleId,
                    titleName: titleName,
                    authorName: authorName,
                    fileSize: counter.value)
            } catch is CancellationError {
                logger.info("download cancelled for mediaItemId=\(mediaItemId)")
                try? FileManager.default.removeItem(at: dest)
                self.clearActive(mediaItemId)
            } catch {
                let detail = error.localizedDescription
                // Inline the underlying message so log scrapes (Binnacle
                // / os_log) carry it in the message field rather than
                // an opaque `error:` attribute that some viewers strip.
                logger.error("download failed for mediaItemId=\(mediaItemId) at \(dest.path): \(detail)")
                try? FileManager.default.removeItem(at: dest)
                self.clearActive(mediaItemId)
                self.recordFailure(mediaItemId: mediaItemId, message: detail)
            }
        }
        downloadTasks[mediaItemId] = task
    }

    /// Records a download failure so the UI can render a Retry
    /// affordance with the actual error text. Cleared on the next
    /// successful start.
    private func recordFailure(mediaItemId: Int64, message: String) {
        failedDownloads[mediaItemId] = message
    }

    /// Cancels the in-flight task and discards the partial file.
    /// No-op if no download is running for this id.
    func cancelDownload(_ mediaItemId: Int64) {
        downloadTasks[mediaItemId]?.cancel()
        // The Task's cancellation handler will clean up the partial
        // and clear `activeDownloads` itself; we just drop our handle.
        downloadTasks.removeValue(forKey: mediaItemId)
    }

    /// Deletes a completed download. Throws if the book isn't
    /// downloaded — callers can swallow this to be idempotent if
    /// they want.
    func deleteDownload(_ mediaItemId: Int64) throws {
        guard let idx = downloads.firstIndex(where: { $0.mediaItemId == mediaItemId }) else {
            throw BookCacheError.notDownloaded(mediaItemId: mediaItemId)
        }
        let url = booksDir.appendingPathComponent("\(mediaItemId).epub")
        try? FileManager.default.removeItem(at: url)
        downloads.remove(at: idx)
        persistIndex()
        logger.info("deleted download mediaItemId=\(mediaItemId)")
    }

    // MARK: - Internal

    private func recordProgress(
        mediaItemId: Int64, bytesReceived: Int64, totalBytes: Int64?
    ) {
        // Preserve the original titleId from the active record set
        // by startDownload — never overwrite it on a progress tick.
        guard let existing = activeDownloads[mediaItemId] else { return }
        activeDownloads[mediaItemId] = BookDownloadProgress(
            mediaItemId: mediaItemId,
            titleId: existing.titleId,
            bytesReceived: bytesReceived,
            totalBytes: totalBytes)
    }

    private func finishDownload(
        mediaItemId: Int64,
        titleId: Int64,
        titleName: String,
        authorName: String,
        fileSize: Int64,
    ) {
        let now = Date()
        let entry = DownloadedBook(
            mediaItemId: mediaItemId,
            titleId: titleId,
            titleName: titleName,
            authorName: authorName,
            sizeBytes: fileSize,
            downloadedAt: now,
            lastAccessedAt: now,
            completedFraction: 0)
        downloads.append(entry)
        persistIndex()
        downloadTasks.removeValue(forKey: mediaItemId)
        activeDownloads.removeValue(forKey: mediaItemId)
        logger.info("download complete mediaItemId=\(mediaItemId) size=\(fileSize)B")
    }

    private func clearActive(_ mediaItemId: Int64) {
        activeDownloads.removeValue(forKey: mediaItemId)
        downloadTasks.removeValue(forKey: mediaItemId)
    }

    // MARK: - Index persistence

    private func persistIndex() {
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(downloads)
            // Atomic write — rename-on-success leaves the old index in
            // place if encoding or write fails partway through. Avoids
            // a half-written index.json after a crash.
            try data.write(to: indexPath, options: [.atomic])
        } catch {
            logger.error("failed to persist book download index", error: error)
        }
    }

    private static func loadIndex(at url: URL) -> [DownloadedBook] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        do {
            let data = try Data(contentsOf: url)
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            return try decoder.decode([DownloadedBook].self, from: data)
        } catch {
            logger.error("failed to load book download index; treating as empty", error: error)
            return []
        }
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        max(range.lowerBound, min(self, range.upperBound))
    }
}

/// Reference-typed monotonic counter so a `@Sendable` closure can
/// accumulate bytes across invocations. NSLock makes it safe under
/// concurrent chunk callbacks (gRPC streaming guarantees serial
/// delivery in practice, but the type checker doesn't know that).
private final class ByteCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: Int64 = 0
    var value: Int64 { lock.withLock { _value } }
    @discardableResult
    func add(_ delta: Int64) -> Int64 {
        lock.withLock { _value += delta; return _value }
    }
}
