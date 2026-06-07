import Foundation
import Network
import Observation
import os.log

private let logger = MMLogger(category: "DownloadManager")

@Observable
@MainActor
final class DownloadManager {

    private(set) var entries: [MMDownloadEntry] = []
    var isOfflineMode: Bool {
        didSet {
            UserDefaults.standard.set(isOfflineMode, forKey: "offlineMode")
            propagateOfflineState()
        }
    }
    private(set) var hasNetworkConnectivity = true

    var isEffectivelyOffline: Bool { isOfflineMode || !hasNetworkConnectivity }
    var hasCompletedDownloads: Bool { entries.contains { $0.state == .completed } }

    private var grpcClient: GrpcClient?
    private var apiClient: APIClient?
    private var downloadTasks: [Int64: Task<Void, Never>] = [:] // transcodeId → active download task
    private var networkMonitor: NWPathMonitor?
    private let store = DownloadStore.shared

    private static let maxConcurrentDownloads = 3
    /// Persist bytesDownloaded every N chunks to avoid excessive disk writes.
    private static let persistIntervalChunks = 10

    init() {
        self.isOfflineMode = UserDefaults.standard.bool(forKey: "offlineMode")
        logger.info("DownloadManager initialized")
        Task {
            await reloadEntries()
            await store.cleanOrphans()
        }
        startNetworkMonitor()
    }

    func configure(apiClient: APIClient, grpcClient: GrpcClient) {
        self.apiClient = apiClient
        self.grpcClient = grpcClient
        // Push the current offline state to the freshly-wired gRPC
        // client. The init-time UserDefaults seed inside GrpcClient
        // covers the very-early restoreSession() RPCs; this push
        // ensures the gate matches the live (post-init) state — e.g.
        // network monitor already reporting `path.status != .satisfied`.
        propagateOfflineState()
        if !isEffectivelyOffline {
            Task { await flushPendingProgress() }
        }
        // Resume any downloads that were in progress when the app was killed
        resumeInterruptedDownloads()
    }

    /// Push `isEffectivelyOffline` to GrpcClient so the gate at
    /// `requireClient()` matches our combined state — the user-set
    /// `isOfflineMode` flag OR `!hasNetworkConnectivity`. Called from
    /// `configure()` (initial sync), `isOfflineMode.didSet` (toggle),
    /// and the network monitor (connectivity changes).
    private func propagateOfflineState() {
        guard let client = grpcClient else { return }
        let offline = isEffectivelyOffline
        Task { await client.setOfflineGated(offline) }
    }

    /// Restart downloads that were active or queued before the app was killed.
    private func resumeInterruptedDownloads() {
        Task {
            // Ensure entries are loaded from disk first
            await reloadEntries()
            await store.cleanOrphans()
            await reloadEntries()

            // Re-queue any that were mid-download — they'll resume from saved offset
            for entry in entries where entry.state == .downloading || entry.state == .fetchingMetadata {
                await store.updateEntry(transcodeId: entry.transcodeID) { $0.state = .queued }
            }
            await reloadEntries()
            startNextQueued()
        }
    }

    // MARK: - Public API

    func startDownload(transcodeId: Int64, titleId: Int64, titleName: String,
                       quality: MMDownloadQuality, year: Int32, mediaType: MMMediaType,
                       contentRating: MMContentRating,
                       seasonNumber: Int32 = 0, episodeNumber: Int32 = 0, episodeTitle: String = "") {
        Task {
            guard await store.entry(for: transcodeId) == nil else { return }

            var entry = MMDownloadEntry()
            entry.transcodeID = transcodeId
            entry.titleID = titleId
            entry.titleName = titleName
            entry.quality = quality
            entry.year = year
            entry.mediaType = mediaType
            entry.contentRating = contentRating
            entry.seasonNumber = seasonNumber
            entry.episodeNumber = episodeNumber
            entry.episodeTitle = episodeTitle
            entry.contentType = "video/mp4"
            // Always queue — startNextQueued will pick up slots
            entry.state = .queued
            entry.addedAt = MMTimestamp.with { $0.secondsSinceEpoch = Int64(Date().timeIntervalSince1970) }

            _ = await store.addEntry(entry)
            await reloadEntries()

            // Kick the queue (synchronous check + start, no suspension between check and start)
            startNextQueued()
        }
    }

    func pauseDownload(transcodeId: Int64) {
        downloadTasks[transcodeId]?.cancel()
        downloadTasks.removeValue(forKey: transcodeId)
        Task {
            await store.updateEntry(transcodeId: transcodeId) { e in
                e.state = .paused
            }
            await reloadEntries()
        }
    }

    func resumeDownload(transcodeId: Int64) {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId }),
              entry.state == .paused || entry.state == .failed else { return }

        logger.info("resumeDownload tcId=\(transcodeId): manual retry — resetting retryCount from \(entry.retryCount)")
        Task {
            await store.updateEntry(transcodeId: transcodeId) { e in
                e.state = .downloading
                e.errorMessage = ""
                // Manual Retry is the user's "give it another fresh
                // shot" signal — clear the auto-retry budget so we
                // don't immediately hit the 8-attempt cap and fail
                // again in <1s. Without this, a download that
                // exhausted the auto-retry budget could never
                // succeed without a delete-and-redownload cycle.
                e.retryCount = 0
            }
            await reloadEntries()
        }
        beginDownload(transcodeId: transcodeId, sequence: entries.first(where: { $0.transcodeID == transcodeId })?.sequence ?? 0)
    }

    func deleteDownload(transcodeId: Int64) {
        downloadTasks[transcodeId]?.cancel()
        downloadTasks.removeValue(forKey: transcodeId)

        // Unpin images if no other downloads remain for this title
        if let entry = entries.first(where: { $0.transcodeID == transcodeId }) {
            let titleId = entry.titleID
            let remainingForTitle = entries.filter { $0.titleID == titleId && $0.transcodeID != transcodeId }
            if remainingForTitle.isEmpty {
                Task { await unpinImagesForTitle(titleId) }
            }
        }

        Task {
            await store.removeEntry(transcodeId: transcodeId)
            await reloadEntries()
        }
    }

    func localVideoURL(for transcodeId: Int64) -> URL? {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId }),
              entry.state == .completed else { return nil }
        let seq = String(format: "%07d.mp4", entry.sequence)
        let url = DownloadStore.shared.downloadsDir.appendingPathComponent(seq)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func state(for transcodeId: Int64) -> MMDownloadState {
        entries.first(where: { $0.transcodeID == transcodeId })?.state ?? .unknown
    }

    var activeDownloadCount: Int {
        downloadTasks.count
    }

    var totalStorageUsed: Int64 {
        entries.filter { $0.state == .completed }.map { $0.fileSizeBytes }.reduce(0, +)
    }

    var offlineTitleIds: Set<Int64> {
        Set(entries.filter { $0.state == .completed }.map { $0.titleID })
    }

    /// Aggregate progress across an explicit set of transcode IDs.
    /// Used by container views (Collection grid, TV-series bulk
    /// action) to render a single status row above the items list.
    /// Pure-compute on `entries` — no extra state to keep coherent.
    struct BulkStatus: Equatable {
        let total: Int
        let completed: Int
        /// In-flight = queued + fetchingMetadata + downloading + paused.
        /// Anything that's not terminal and not idle.
        let inFlight: Int
        let failed: Int
        var pending: Int { max(0, total - completed - inFlight - failed) }
        var fraction: Double {
            total > 0 ? Double(completed) / Double(total) : 0
        }
        /// "Work remaining" — drives whether the action button shows.
        var hasWork: Bool { pending > 0 || failed > 0 }
    }

    func bulkStatus(forTranscodes ids: [Int64]) -> BulkStatus {
        guard !ids.isEmpty else {
            return BulkStatus(total: 0, completed: 0, inFlight: 0, failed: 0)
        }
        var completed = 0, inFlight = 0, failed = 0
        for id in ids {
            switch state(for: id) {
            case .completed: completed += 1
            case .downloading, .fetchingMetadata, .queued, .paused: inFlight += 1
            case .failed: failed += 1
            default: break
            }
        }
        return BulkStatus(total: ids.count, completed: completed,
                          inFlight: inFlight, failed: failed)
    }

    /// TV-series variant: SeasonsView knows the expected episode
    /// total (sum of season.episodeCount) but doesn't yet hold the
    /// list of transcode IDs at first paint. Count by titleID so
    /// the row's status reflects every download for the show even
    /// if some episodes were started individually before the bulk
    /// action fired.
    func bulkStatus(forShowId showId: Int64, expectedTotal: Int) -> BulkStatus {
        var c = 0, f = 0, x = 0
        for entry in entries where entry.titleID == showId {
            switch entry.state {
            case .completed: c += 1
            case .downloading, .fetchingMetadata, .queued, .paused: f += 1
            case .failed: x += 1
            default: break
            }
        }
        return BulkStatus(total: expectedTotal, completed: c,
                          inFlight: f, failed: x)
    }

    /// True when there is at least one completed download for every
    /// episode number 1..expectedEpisodeCount of the given season.
    /// Compares the deduplicated set of completed episode numbers
    /// against the expected count, so historical entries from a prior
    /// catalog numbering won't false-positive on a season that has
    /// since shrunk.
    func isSeasonFullyDownloaded(
        titleId: Int64, season: Int32, expectedEpisodeCount: Int
    ) -> Bool {
        guard expectedEpisodeCount > 0 else { return false }
        let completed: Set<Int32> = Set(
            entries
                .filter { $0.state == .completed
                    && $0.titleID == titleId
                    && $0.seasonNumber == season }
                .map { $0.episodeNumber })
        return completed.count >= expectedEpisodeCount
    }

    func localDir(for transcodeId: TranscodeID) -> URL? {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId.protoValue }),
              entry.state == .completed else { return nil }
        return store.downloadsDir
    }

    func sequencePrefix(for transcodeId: TranscodeID) -> String? {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId.protoValue }),
              entry.state == .completed else { return nil }
        return String(format: "%07d", entry.sequence)
    }

    // MARK: - Progress Queue

    func queueProgressUpdate(transcodeId: Int64, position: Double, duration: Double?) {
        Task {
            await store.updateEntry(transcodeId: transcodeId) { e in
                e.playbackPosition = MMPlaybackOffset.with { $0.seconds = position }
                if let duration { e.duration = MMPlaybackOffset.with { $0.seconds = duration } }
                e.positionUpdatedAt = MMTimestamp.with { $0.secondsSinceEpoch = Int64(Date().timeIntervalSince1970) }
                e.positionSynced = false
            }
        }
    }

    func flushPendingProgress() async {
        guard let grpcClient else { return }
        let unsynced = await store.entries.filter { !$0.positionSynced && $0.playbackPosition.seconds > 0 }
        for entry in unsynced {
            do {
                try await grpcClient.reportProgress(
                    transcodeId: entry.transcodeID,
                    position: entry.playbackPosition.seconds,
                    duration: entry.duration.seconds > 0 ? entry.duration.seconds : nil
                )
                await store.updateEntry(transcodeId: entry.transcodeID) { e in
                    e.positionSynced = true
                }
            } catch { }
        }
        await flushPendingListeningProgress()
    }

    /// Drain the audio listening-progress queue. Each successful
    /// ReportListeningProgress RPC removes the corresponding entry;
    /// failures stay queued for the next flush. Called on
    /// network restore (alongside the video flush) and at startup
    /// after configure().
    func flushPendingListeningProgress() async {
        guard let grpcClient else { return }
        let pending = await ListeningProgressQueue.shared.pending()
        for entry in pending {
            do {
                try await grpcClient.reportListeningProgress(
                    trackId: entry.trackId,
                    position: entry.position,
                    duration: entry.duration)
                await ListeningProgressQueue.shared.remove(trackId: entry.trackId)
            } catch {
                // Leave on the queue; the next flush picks it up.
                // Don't break the loop — a one-track-not-found failure
                // shouldn't block the rest.
                continue
            }
        }
    }

    /// Pin images for a downloaded title so they survive cache eviction.
    func pinImagesForTitle(_ titleId: Int64) async {
        let cache = ImageDiskCache.shared
        await cache.pin(ref: .posterThumbnail(titleId: titleId))
        await cache.pin(ref: .posterFull(titleId: titleId))
        await cache.pin(ref: .backdrop(titleId: titleId))

        // Pin cast headshots from cached detail
        if let detail = loadCachedTitleDetail(for: TitleID(rawValue: Int(titleId))) {
            for member in detail.cast {
                await cache.pin(ref: .headshot(tmdbPersonId: member.tmdbPersonId.protoValue))
            }
        }
    }

    /// Unpin images for a title being deleted.
    func unpinImagesForTitle(_ titleId: Int64) async {
        let cache = ImageDiskCache.shared
        await cache.unpin(ref: .posterThumbnail(titleId: titleId))
        await cache.unpin(ref: .posterFull(titleId: titleId))
        await cache.unpin(ref: .backdrop(titleId: titleId))

        if let detail = loadCachedTitleDetail(for: TitleID(rawValue: Int(titleId))) {
            for member in detail.cast {
                await cache.unpin(ref: .headshot(tmdbPersonId: member.tmdbPersonId.protoValue))
            }
        }
    }

    // MARK: - TV metadata cache (offline browse parity)
    //
    // For each TV title the user touches, we persist the parent's
    // MMTitleDetail (via .detail.pb above) plus per-season MMSeason
    // and per-episode MMEpisode protos under tv/<titleId>/. That
    // gives SeasonsView and EpisodesView the same data offline that
    // they receive from grpcClient.seasons() / .episodes() online.
    // Cached only for shows the user actually downloads from — we
    // don't speculatively prefetch metadata for shows they're only
    // browsing.

    private func tvMetadataDir(for titleId: Int64) -> URL {
        store.downloadsDir
            .appendingPathComponent("tv", isDirectory: true)
            .appendingPathComponent("\(titleId)", isDirectory: true)
    }

    /// Persist a season's metadata so SeasonsView can render it
    /// offline. Called by SeasonsView before kicking off downloads
    /// and by EpisodesView when starting any per-episode download.
    func cacheSeasonMetadata(titleId: TitleID, season: ApiSeason) {
        let dir = tvMetadataDir(for: titleId.protoValue)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("season-\(season.seasonNumber).pb")
        guard let data = try? season.proto.serializedData() else { return }
        try? data.write(to: path)
    }

    /// Persist an episode's metadata. Called whenever an episode
    /// download starts (single or bulk).
    func cacheEpisodeMetadata(titleId: TitleID, episode: ApiEpisode) {
        let dir = tvMetadataDir(for: titleId.protoValue)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("episode-\(episode.seasonNumber)x\(episode.episodeNumber).pb")
        guard let data = try? episode.proto.serializedData() else { return }
        try? data.write(to: path)
    }

    /// All cached seasons for a TV title, sorted by season number.
    /// Empty when the user has never downloaded anything from this
    /// show — OfflineDataModel falls back to throwing in that case
    /// so the user just doesn't see the show in the offline list.
    func loadCachedSeasons(for titleId: TitleID) -> [ApiSeason] {
        let dir = tvMetadataDir(for: titleId.protoValue)
        guard let urls = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return [] }
        return urls.compactMap { url -> ApiSeason? in
            guard url.lastPathComponent.hasPrefix("season-") else { return nil }
            guard let data = try? Data(contentsOf: url),
                  let proto = try? MMSeason(serializedData: data) else { return nil }
            return ApiSeason(proto: proto)
        }.sorted { $0.seasonNumber < $1.seasonNumber }
    }

    /// Cached episodes for one season, sorted by episode number.
    func loadCachedEpisodes(for titleId: TitleID, season: Int) -> [ApiEpisode] {
        let dir = tvMetadataDir(for: titleId.protoValue)
        guard let urls = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return [] }
        let prefix = "episode-\(season)x"
        return urls.compactMap { url -> ApiEpisode? in
            guard url.lastPathComponent.hasPrefix(prefix) else { return nil }
            guard let data = try? Data(contentsOf: url),
                  let proto = try? MMEpisode(serializedData: data) else { return nil }
            return ApiEpisode(proto: proto)
        }.sorted { $0.episodeNumber < $1.episodeNumber }
    }

    /// Load cached title detail protobuf for offline browse.
    func loadCachedTitleDetail(for titleId: TitleID) -> ApiTitleDetail? {
        // Find any download entry for this title that has a cached detail
        guard let entry = entries.first(where: { $0.titleID == titleId.protoValue }) else { return nil }
        let path = store.downloadsDir.appendingPathComponent(String(format: "%07d.detail.pb", entry.sequence))
        guard let data = try? Data(contentsOf: path),
              let proto = try? MMTitleDetail(serializedData: data) else { return nil }
        return ApiTitleDetail(proto: proto)
    }

    /// Returns the local chapters file URL for a downloaded transcode, if it exists.
    func localChaptersURL(for transcodeId: TranscodeID) -> URL? {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId.protoValue }),
              entry.hasChapters_p else { return nil }
        let path = store.downloadsDir.appendingPathComponent(String(format: "%07d.chapters.json", entry.sequence))
        return FileManager.default.fileExists(atPath: path.path) ? path : nil
    }

    /// Returns the local subtitle file URL for a downloaded transcode, if it exists.
    func localSubtitleURL(for transcodeId: TranscodeID) -> URL? {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId.protoValue }),
              entry.hasSubtitles_p else { return nil }
        let path = store.downloadsDir.appendingPathComponent(String(format: "%07d.subs.vtt", entry.sequence))
        return FileManager.default.fileExists(atPath: path.path) ? path : nil
    }



    // MARK: - Private: Download Flow

    private func beginDownload(transcodeId: Int64, sequence: Int32) {
        downloadTasks[transcodeId] = Task {
            await performDownload(transcodeId: transcodeId, sequence: sequence)
        }
    }

    private func performDownload(transcodeId: Int64, sequence: Int32) async {
        logger.info("performDownload: transcodeId=\(transcodeId) seq=\(sequence)")
        await store.updateEntry(transcodeId: transcodeId) { $0.state = .fetchingMetadata }
        await reloadEntries()

        guard let grpcClient else {
            logger.error("performDownload tcId=\(transcodeId): grpcClient is nil — not configured")
            await markFailed(transcodeId: transcodeId,
                             error: "Not configured — app didn't finish connecting to the server before download started")
            return
        }

        // Fetch manifest — transient failures (server hiccup, brief
        // disconnect) auto-retry with backoff via scheduleRetry.
        do {
            let manifest = try await grpcClient.getManifest(transcodeId: transcodeId)
            await store.updateEntry(transcodeId: transcodeId) { e in
                e.fileSizeBytes = manifest.fileSizeBytes
                e.hasSubtitles_p = manifest.hasSubtitles_p
                e.hasThumbnails_p = manifest.hasThumbnails_p
                e.hasChapters_p = manifest.hasChapters_p
            }
            logger.info("Manifest fetched: \(manifest.fileSizeBytes) bytes")
        } catch {
            logger.error("performDownload tcId=\(transcodeId): manifest fetch failed: \(String(describing: error))")
            await scheduleRetry(transcodeId: transcodeId,
                                sequence: sequence,
                                lastError: "Manifest: \(error.localizedDescription)",
                                isRetryable: true)
            return
        }

        // Check disk space — not a transient failure; the user must
        // intervene to free up storage. Don't auto-retry.
        if let entry = await store.entry(for: transcodeId) {
            let required = entry.fileSizeBytes + 500_000_000
            if let free = try? FileManager.default.attributesOfFileSystem(
                forPath: NSHomeDirectory())[.systemFreeSize] as? Int64, free < required {
                logger.error("performDownload tcId=\(transcodeId): not enough space (need \(required), have \(free))")
                await markFailed(transcodeId: transcodeId,
                                 error: "Not enough space — need \(required / 1_000_000) MB free")
                return
            }
        }

        // Download supporting files
        if let entry = await store.entry(for: transcodeId) {
            await downloadSupportingFiles(entry: entry)
        }

        // Start gRPC streaming download
        guard let entry = await store.entry(for: transcodeId) else { return }
        let resumeOffset = entry.bytesDownloaded
        let manifestSize = entry.fileSizeBytes

        await store.updateEntry(transcodeId: transcodeId) { e in
            e.state = .downloading
        }
        await reloadEntries()

        let filePath = await store.videoPath(for: entry, downloading: true)
        logger.info("Starting gRPC download: offset=\(resumeOffset) -> \(filePath.lastPathComponent)")

        // Short-circuit: we already have every byte the manifest
        // promised. Opening a gRPC stream with offset == size makes
        // the server send 0 bytes (or sit open waiting for them),
        // which is exactly the "stuck at 100%" symptom seen with
        // Wicked.mp4. Finalize directly and skip the stream.
        if manifestSize > 0 && resumeOffset >= manifestSize {
            await finalizeAlreadyDownloaded(
                entry: entry, sequence: sequence, downloadingPath: filePath)
            return
        }

        let progress = DownloadProgress(initial: resumeOffset)
        do {
            // Open file for writing (append if resuming)
            if resumeOffset > 0 {
                guard FileManager.default.fileExists(atPath: filePath.path) else {
                    await store.updateEntry(transcodeId: transcodeId) { $0.bytesDownloaded = 0 }
                    await performDownload(transcodeId: transcodeId, sequence: sequence)
                    return
                }
            } else {
                FileManager.default.createFile(atPath: filePath.path, contents: nil)
            }

            let fileHandle = try FileHandle(forWritingTo: filePath)
            if resumeOffset > 0 {
                try fileHandle.seek(toOffset: UInt64(resumeOffset))
            }

            try await grpcClient.downloadFile(transcodeId: transcodeId, offset: resumeOffset) { chunk in
                let data = chunk.data
                if data.isEmpty {
                    return // skip empty chunks
                }
                fileHandle.write(data)
                let (newTotal, shouldPersist) = await progress.add(Int64(data.count))

                if shouldPersist {
                    // Flush to disk before persisting metadata so the file size
                    // matches bytesDownloaded on crash/cancel recovery
                    try? fileHandle.synchronize()
                    await self.store.updateEntry(transcodeId: transcodeId) { e in
                        e.bytesDownloaded = newTotal
                    }
                    await self.reloadEntries()
                }
            }

            try fileHandle.synchronize()
            try fileHandle.close()

            let finalBytes = await progress.total

            // Finalize: rename .downloading → .mp4
            try await store.finalizeVideo(for: entry)

            await store.updateEntry(transcodeId: transcodeId) { e in
                e.state = .completed
                e.bytesDownloaded = finalBytes
                e.completedAt = MMTimestamp.with { $0.secondsSinceEpoch = Int64(Date().timeIntervalSince1970) }
            }
            await reloadEntries()
            downloadTasks.removeValue(forKey: transcodeId)
            logger.info("Download complete: \(finalBytes) bytes")

            // Pin images so they survive cache eviction for offline browse
            await pinImagesForTitle(entry.titleID)

            startNextQueued()

        } catch is CancellationError {
            // User-initiated pause — bytesDownloaded already saved periodically
            logger.info("Download paused: transcodeId=\(transcodeId)")
            let saved = await progress.total
            await store.updateEntry(transcodeId: transcodeId) { $0.bytesDownloaded = saved }
        } catch {
            let saved = await progress.total
            logger.error("performDownload tcId=\(transcodeId): in-stream/finalize error after \(saved) bytes: \(String(describing: error))")
            await store.updateEntry(transcodeId: transcodeId) { e in
                e.bytesDownloaded = saved
            }
            await scheduleRetry(transcodeId: transcodeId,
                                sequence: sequence,
                                lastError: error.localizedDescription,
                                isRetryable: true)
        }
    }

    /// Recover a download whose bytes already match the manifest
    /// size, without going through the gRPC stream. Hit when the
    /// stream completed in a prior attempt but finalize / completion
    /// metadata didn't land — most commonly because finalize threw
    /// (e.g. .mp4 already exists from an even earlier attempt) and
    /// the retry path re-entered performDownload with bytesDownloaded
    /// at the manifest size.
    ///
    /// Reconciles the on-disk state:
    ///   - .downloading only → rename to .mp4 (the normal finalize)
    ///   - .mp4 only         → a previous finalize did succeed; just
    ///                          update state metadata
    ///   - both exist        → discard stale .downloading, keep .mp4
    ///   - neither exists    → bytesDownloaded is lying; reset and
    ///                          re-enter performDownload from offset 0
    private func finalizeAlreadyDownloaded(
        entry: MMDownloadEntry, sequence: Int32, downloadingPath: URL
    ) async {
        let transcodeId = entry.transcodeID
        let finalPath = await store.videoPath(for: entry, downloading: false)
        let fm = FileManager.default
        let hasDownloading = fm.fileExists(atPath: downloadingPath.path)
        let hasFinal = fm.fileExists(atPath: finalPath.path)
        logger.info("finalizeAlreadyDownloaded tcId=\(transcodeId): hasDownloading=\(hasDownloading) hasFinal=\(hasFinal)")

        do {
            switch (hasDownloading, hasFinal) {
            case (true, false):
                try await store.finalizeVideo(for: entry)
            case (false, true):
                break // .mp4 already in place from a previous run
            case (true, true):
                logger.warning("finalizeAlreadyDownloaded tcId=\(transcodeId): both files present — discarding stale .downloading")
                try? fm.removeItem(at: downloadingPath)
            case (false, false):
                logger.warning("finalizeAlreadyDownloaded tcId=\(transcodeId): bytesDownloaded=\(entry.bytesDownloaded) but no file on disk — restarting from offset 0")
                await store.updateEntry(transcodeId: transcodeId) { $0.bytesDownloaded = 0 }
                await performDownload(transcodeId: transcodeId, sequence: sequence)
                return
            }

            await store.updateEntry(transcodeId: transcodeId) { e in
                e.state = .completed
                e.bytesDownloaded = entry.fileSizeBytes
                e.completedAt = MMTimestamp.with { $0.secondsSinceEpoch = Int64(Date().timeIntervalSince1970) }
                e.errorMessage = ""
                e.retryCount = 0
            }
            await reloadEntries()
            downloadTasks.removeValue(forKey: transcodeId)
            logger.info("Download complete (no stream needed): tcId=\(transcodeId) \(entry.fileSizeBytes) bytes")
            await pinImagesForTitle(entry.titleID)
            startNextQueued()
        } catch {
            logger.error("finalizeAlreadyDownloaded tcId=\(transcodeId): \(String(describing: error))")
            await scheduleRetry(transcodeId: transcodeId, sequence: sequence,
                                lastError: "Finalize: \(error.localizedDescription)",
                                isRetryable: true)
        }
    }

    /// Unified retry orchestrator. Either schedules another
    /// performDownload attempt with exponential backoff, or — when
    /// the retry budget is exhausted (or the failure is explicitly
    /// non-retryable) — calls markFailed so the user sees a
    /// permanent "Retry" affordance.
    ///
    /// Backoff schedule: 2, 4, 8, 16, 32, 60, 60, 60 seconds (cap at
    /// 60s). Eight total attempts including the initial — about ~3
    /// minutes of wall-clock self-healing before the user is asked.
    /// The HAProxy + server combo's transient hiccups consistently
    /// resolve within 5–15 seconds in practice, so 8 attempts is
    /// generous headroom.
    private func scheduleRetry(
        transcodeId: Int64, sequence: Int32, lastError: String, isRetryable: Bool
    ) async {
        // Persist + bump the attempt counter so a subsequent crash /
        // app kill / manual Retry observes accurate state.
        await store.updateEntry(transcodeId: transcodeId) { $0.retryCount += 1 }
        await reloadEntries()
        let attempt = (await store.entry(for: transcodeId))?.retryCount ?? 1

        guard isRetryable, attempt < 8 else {
            logger.error("scheduleRetry tcId=\(transcodeId): giving up after \(attempt) attempts. Last error: \(lastError)")
            await markFailed(
                transcodeId: transcodeId,
                error: "Failed after \(attempt) attempt\(attempt == 1 ? "" : "s"): \(lastError)")
            return
        }

        let delay = min(60.0, pow(2.0, Double(attempt)))
        logger.warning("scheduleRetry tcId=\(transcodeId): attempt \(attempt + 1)/8 in \(Int(delay))s — last error: \(lastError)")
        // Surface the retry state in the UI by writing it into
        // errorMessage. State stays at .downloading / .fetchingMetadata
        // so the row doesn't flicker to "Retry" between auto-attempts.
        await store.updateEntry(transcodeId: transcodeId) { e in
            e.errorMessage = "Retrying in \(Int(delay))s — \(lastError)"
        }
        await reloadEntries()

        try? await Task.sleep(for: .seconds(delay))
        if Task.isCancelled {
            logger.info("scheduleRetry tcId=\(transcodeId): cancelled during backoff")
            return
        }

        await store.updateEntry(transcodeId: transcodeId) { $0.errorMessage = "" }
        await reloadEntries()
        await performDownload(transcodeId: transcodeId, sequence: sequence)
    }

    private func downloadSupportingFiles(entry: MMDownloadEntry) async {
        guard let apiClient else { return }
        let tcId = entry.transcodeID

        // Subtitles
        if let data = try? await apiClient.getRaw("stream/\(tcId)/subs.vtt"),
           let content = String(data: data, encoding: .utf8), content.contains("-->") {
            let path = await store.subtitlesPath(for: entry)
            try? data.write(to: path)
            await store.updateEntry(transcodeId: tcId) { $0.hasSubtitles_p = true }
        }

        // Chapters
        if let grpcClient,
           let response = try? await grpcClient.getChapters(transcodeId: tcId),
           !response.chapters.isEmpty || !response.skipSegments.isEmpty {
            if let jsonData = try? response.jsonUTF8Data() {
                let path = await store.chaptersPath(for: entry)
                try? jsonData.write(to: path)
                await store.updateEntry(transcodeId: tcId) { $0.hasChapters_p = true }
            }
        }

        // Poster
        if let posterData = try? await apiClient.getRaw("/posters/w185/\(entry.titleID)") {
            let path = await store.posterPath(for: entry)
            try? posterData.write(to: path)
            await store.updateEntry(transcodeId: tcId) { $0.hasPoster_p = true }
        }

        // Title detail (for offline browse)
        if let grpcClient {
            do {
                let detail = try await grpcClient.getTitleDetail(id: entry.titleID)
                let data = try detail.serializedData()
                let path = await store.detailPath(for: entry)
                try data.write(to: path)
            } catch {
                // Non-critical — offline browse won't work for this title
            }
        }
    }

    // MARK: - Private: Helpers

    private func markFailed(transcodeId: Int64, error: String) async {
        // Final-failure log so Binnacle has a record even when the
        // auto-retry path didn't fire (e.g. permanent storage error,
        // misconfiguration). scheduleRetry already logs each
        // intermediate attempt with full underlying-error detail.
        logger.error("markFailed tcId=\(transcodeId): \(error)")
        await store.updateEntry(transcodeId: transcodeId) { e in
            e.state = .failed
            e.errorMessage = error
        }
        await reloadEntries()
        downloadTasks.removeValue(forKey: transcodeId)
    }

    private func reloadEntries() async {
        entries = await store.entries
    }

    /// Start queued downloads up to the concurrency limit.
    /// Prioritizes entries that already have progress (were interrupted mid-download).
    /// IMPORTANT: This is synchronous (no await) between the count check
    /// and beginDownload to prevent races.
    private func startNextQueued() {
        let queued = entries
            .filter { $0.state == .queued }
            .sorted { $0.bytesDownloaded > $1.bytesDownloaded } // resume in-progress first
        for next in queued {
            guard downloadTasks.count < Self.maxConcurrentDownloads else { break }
            // beginDownload synchronously sets downloadTasks[id], so the
            // count check on the next iteration sees the updated value.
            beginDownload(transcodeId: next.transcodeID, sequence: next.sequence)
        }
    }

    // MARK: - Private: Network Monitor

    private func startNetworkMonitor() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let wasOffline = self.isEffectivelyOffline
                self.hasNetworkConnectivity = (path.status == .satisfied)
                self.propagateOfflineState()
                if wasOffline && !self.isEffectivelyOffline {
                    await self.flushPendingProgress()
                }
            }
        }
        monitor.start(queue: .main)
        networkMonitor = monitor
    }
}

/// Thread-safe progress counter for use in @Sendable download closures.
private actor DownloadProgress {
    private(set) var total: Int64
    private var chunkCount = 0

    init(initial: Int64) {
        self.total = initial
    }

    /// Add bytes, returns (newTotal, shouldPersist).
    /// Persists on first chunk and every 10th chunk thereafter.
    func add(_ bytes: Int64) -> (Int64, Bool) {
        total += bytes
        chunkCount += 1
        // Always persist first chunk, then every 10
        if chunkCount == 1 || chunkCount % 10 == 0 {
            return (total, true)
        }
        return (total, false)
    }
}
