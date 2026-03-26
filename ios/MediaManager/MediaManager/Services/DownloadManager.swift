import Foundation
import Network
import Observation
import os.log

private let logger = Logger(subsystem: "net.stewart.mediamanager", category: "DownloadManager")

@Observable
@MainActor
final class DownloadManager {

    private(set) var entries: [MMDownloadEntry] = []
    var isOfflineMode: Bool {
        didSet { UserDefaults.standard.set(isOfflineMode, forKey: "offlineMode") }
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
        logger.error("DownloadManager initialized")
        Task {
            await reloadEntries()
            await store.cleanOrphans()
        }
        startNetworkMonitor()
    }

    func configure(apiClient: APIClient, grpcClient: GrpcClient) {
        self.apiClient = apiClient
        self.grpcClient = grpcClient
        if !isEffectivelyOffline {
            Task { await flushPendingProgress() }
        }
        // Resume any downloads that were in progress when the app was killed
        resumeInterruptedDownloads()
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

        Task {
            await store.updateEntry(transcodeId: transcodeId) { e in
                e.state = .downloading
                e.errorMessage = ""
            }
            await reloadEntries()
        }
        beginDownload(transcodeId: transcodeId, sequence: entries.first(where: { $0.transcodeID == transcodeId })?.sequence ?? 0)
    }

    func deleteDownload(transcodeId: Int64) {
        downloadTasks[transcodeId]?.cancel()
        downloadTasks.removeValue(forKey: transcodeId)
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
    }

    /// Returns the local subtitle file URL for a downloaded transcode, if it exists.
    func localSubtitleURL(for transcodeId: TranscodeID) -> URL? {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId.protoValue }),
              entry.hasSubtitles_p else { return nil }
        let path = store.downloadsDir.appendingPathComponent(String(format: "%07d.subs.vtt", entry.sequence))
        return FileManager.default.fileExists(atPath: path.path) ? path : nil
    }

    // MARK: - Offline Image Cache

    func loadCachedImage(for titleId: TitleID, name: String) -> Data? {
        let entry = entries.first { $0.titleID == titleId.protoValue && $0.hasPoster_p }
        guard let entry else { return nil }
        let path = store.downloadsDir.appendingPathComponent("posters/\(String(format: "%07d", entry.sequence)).jpg")
        return try? Data(contentsOf: path)
    }

    // MARK: - Private: Download Flow

    private func beginDownload(transcodeId: Int64, sequence: Int32) {
        downloadTasks[transcodeId] = Task {
            await performDownload(transcodeId: transcodeId, sequence: sequence)
        }
    }

    private func performDownload(transcodeId: Int64, sequence: Int32) async {
        logger.error("performDownload: transcodeId=\(transcodeId) seq=\(sequence)")
        await store.updateEntry(transcodeId: transcodeId) { $0.state = .fetchingMetadata }
        await reloadEntries()

        guard let grpcClient else {
            await markFailed(transcodeId: transcodeId, error: "Not configured")
            return
        }

        // Fetch manifest
        do {
            let manifest = try await grpcClient.getManifest(transcodeId: transcodeId)
            await store.updateEntry(transcodeId: transcodeId) { e in
                e.fileSizeBytes = manifest.fileSizeBytes
                e.hasSubtitles_p = manifest.hasSubtitles_p
                e.hasThumbnails_p = manifest.hasThumbnails_p
                e.hasChapters_p = manifest.hasChapters_p
            }
            logger.error("Manifest fetched: \(manifest.fileSizeBytes) bytes")
        } catch {
            await markFailed(transcodeId: transcodeId, error: "Manifest failed: \(error.localizedDescription)")
            return
        }

        // Check disk space
        if let entry = await store.entry(for: transcodeId) {
            let required = entry.fileSizeBytes + 500_000_000
            if let free = try? FileManager.default.attributesOfFileSystem(
                forPath: NSHomeDirectory())[.systemFreeSize] as? Int64, free < required {
                await markFailed(transcodeId: transcodeId, error: "Not enough space")
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

        await store.updateEntry(transcodeId: transcodeId) { e in
            e.state = .downloading
        }
        await reloadEntries()

        let filePath = await store.videoPath(for: entry, downloading: true)
        logger.error("Starting gRPC download: offset=\(resumeOffset) -> \(filePath.lastPathComponent)")

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
            logger.error("Download complete: \(finalBytes) bytes")

            startNextQueued()

        } catch is CancellationError {
            // User-initiated pause — bytesDownloaded already saved periodically
            logger.error("Download paused: transcodeId=\(transcodeId)")
            let saved = await progress.total
            await store.updateEntry(transcodeId: transcodeId) { $0.bytesDownloaded = saved }
        } catch {
            let saved = await progress.total
            logger.error("Saving progress before retry: \(saved) bytes")
            await store.updateEntry(transcodeId: transcodeId) { e in
                e.bytesDownloaded = saved
                e.retryCount += 1
            }

            // Verify the save took
            let entry = await store.entry(for: transcodeId)
            let retries = entry?.retryCount ?? 0
            logger.error("Verified saved: bytesDownloaded=\(entry?.bytesDownloaded ?? -1) retries=\(retries)")

            if retries > 50 {
                // Too many retries — give up
                logger.error("Download failed after \(retries) retries: \(error)")
                await markFailed(transcodeId: transcodeId, error: "Failed after \(retries) retries")
            } else {
                // Auto-retry with backoff — connection drops are expected through HAProxy
                logger.error("Download interrupted (retry \(retries)): \(String(describing: error))")
                try? await Task.sleep(for: .seconds(2))
                if !Task.isCancelled {
                    await performDownload(transcodeId: transcodeId, sequence: sequence)
                }
            }
        }
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
    }

    // MARK: - Private: Helpers

    private func markFailed(transcodeId: Int64, error: String) async {
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
