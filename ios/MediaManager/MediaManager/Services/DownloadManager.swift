import Foundation
import Network
import Observation

@Observable
@MainActor
final class DownloadManager: NSObject, URLSessionDownloadDelegate {

    private(set) var entries: [MMDownloadEntry] = []
    var isOfflineMode: Bool {
        didSet { UserDefaults.standard.set(isOfflineMode, forKey: "offlineMode") }
    }
    private(set) var hasNetworkConnectivity = true

    var isEffectivelyOffline: Bool { isOfflineMode || !hasNetworkConnectivity }
    var hasCompletedDownloads: Bool { entries.contains { $0.state == .completed } }

    private var backgroundSession: URLSession!
    private var apiClient: APIClient?
    private var grpcClient: GrpcClient?
    private var cachedBaseURL: URL?
    private var activeTaskMap: [Int: Int64] = [:]  // URLSession taskId → transcodeId
    private var backgroundCompletionHandler: (() -> Void)?
    private var networkMonitor: NWPathMonitor?
    private let store = DownloadStore.shared

    private static let sessionIdentifier = "net.stewart.mediamanager.downloads"
    private static let maxConcurrentDownloads = 3

    override init() {
        self.isOfflineMode = UserDefaults.standard.bool(forKey: "offlineMode")
        super.init()
        let config = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.httpCookieStorage = .shared
        backgroundSession = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        Task {
            await reloadEntries()
            await store.cleanOrphans()
            reconcileTasks()
        }
        startNetworkMonitor()
    }

    func configure(apiClient: APIClient, grpcClient: GrpcClient) {
        self.apiClient = apiClient
        self.grpcClient = grpcClient
        Task {
            self.cachedBaseURL = await apiClient.getBaseURL()
            if !isEffectivelyOffline {
                await flushPendingProgress()
            }
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
            entry.state = .fetchingMetadata
            entry.addedAt = MMTimestamp.with { $0.secondsSinceEpoch = Int64(Date().timeIntervalSince1970) }

            let assigned = await store.addEntry(entry)
            await reloadEntries()

            await performDownload(transcodeId: transcodeId, sequence: assigned.sequence)
        }
    }

    func pauseDownload(transcodeId: Int64) {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId }),
              entry.state == .downloading else { return }

        if let taskId = activeTaskMap.first(where: { $0.value == transcodeId })?.key {
            backgroundSession.getAllTasks { [weak self] tasks in
                if let task = tasks.first(where: { $0.taskIdentifier == taskId }) as? URLSessionDownloadTask {
                    task.cancel(byProducingResumeData: { data in
                        Task { @MainActor [weak self] in
                            guard let self else { return }
                            await self.store.updateEntry(transcodeId: transcodeId) { e in
                                e.resumeData = data ?? Data()
                                e.state = .paused
                            }
                            self.activeTaskMap.removeValue(forKey: taskId)
                            await self.reloadEntries()
                        }
                    })
                }
            }
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

            if let entry = await store.entry(for: transcodeId), !entry.resumeData.isEmpty {
                let task = backgroundSession.downloadTask(withResumeData: entry.resumeData)
                activeTaskMap[task.taskIdentifier] = transcodeId
                task.resume()
                await store.updateEntry(transcodeId: transcodeId) { e in
                    e.resumeData = Data()
                }
            } else {
                guard let url = downloadURL(for: transcodeId) else {
                    await markFailed(transcodeId: transcodeId, error: "Cannot build download URL")
                    return
                }
                let task = backgroundSession.downloadTask(with: url)
                activeTaskMap[task.taskIdentifier] = transcodeId
                task.resume()
            }
        }
    }

    func deleteDownload(transcodeId: Int64) {
        if let taskId = activeTaskMap.first(where: { $0.value == transcodeId })?.key {
            backgroundSession.getAllTasks { tasks in
                tasks.first(where: { $0.taskIdentifier == taskId })?.cancel()
            }
            activeTaskMap.removeValue(forKey: taskId)
        }

        Task {
            await store.removeEntry(transcodeId: transcodeId)
            await reloadEntries()
        }
    }

    func localVideoURL(for transcodeId: Int64) -> URL? {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId }),
              entry.state == .completed else { return nil }
        // Synchronous — can't call actor method here, compute path directly
        let seq = String(format: "%07d.mp4", entry.sequence)
        let url = DownloadStore.shared.downloadsDir.appendingPathComponent(seq)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func state(for transcodeId: Int64) -> MMDownloadState {
        entries.first(where: { $0.transcodeID == transcodeId })?.state ?? .unknown
    }

    var activeDownloadCount: Int {
        entries.filter { $0.state == .fetchingMetadata || $0.state == .downloading }.count
    }

    var totalStorageUsed: Int64 {
        entries.filter { $0.state == .completed }.map { $0.fileSizeBytes }.reduce(0, +)
    }

    var offlineTitleIds: Set<Int64> {
        Set(entries.filter { $0.state == .completed }.map { $0.titleID })
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
            } catch {
                // Will retry next flush
            }
        }
    }

    // MARK: - Background Session

    func handleBackgroundSessionEvent(completionHandler: @escaping () -> Void) {
        backgroundCompletionHandler = completionHandler
    }

    // MARK: - URLSessionDownloadDelegate

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didWriteData bytesWritten: Int64,
                                totalBytesWritten: Int64,
                                totalBytesExpectedToWrite: Int64) {
        let taskId = downloadTask.taskIdentifier
        Task { @MainActor in
            guard let tcId = activeTaskMap[taskId] else { return }
            await store.updateEntry(transcodeId: tcId) { e in
                e.bytesDownloaded = totalBytesWritten
                if totalBytesExpectedToWrite > 0 { e.fileSizeBytes = totalBytesExpectedToWrite }
            }
            await reloadEntries()
        }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didFinishDownloadingTo location: URL) {
        let taskId = downloadTask.taskIdentifier

        MainActor.assumeIsolated {
            guard let tcId = activeTaskMap[taskId] else { return }

            Task {
                guard let entry = await store.entry(for: tcId) else { return }

                // Move temp file to staging path (.downloading)
                let stagingPath = await store.videoPath(for: entry, downloading: true)
                try? FileManager.default.removeItem(at: stagingPath)
                do {
                    try FileManager.default.moveItem(at: location, to: stagingPath)
                } catch {
                    await markFailed(transcodeId: tcId, error: "Failed to save: \(error.localizedDescription)")
                    return
                }

                // Rename staging → final
                do {
                    try await store.finalizeVideo(for: entry)
                } catch {
                    await markFailed(transcodeId: tcId, error: "Failed to finalize: \(error.localizedDescription)")
                    return
                }

                await store.updateEntry(transcodeId: tcId) { e in
                    e.state = .completed
                    e.bytesDownloaded = e.fileSizeBytes
                    e.completedAt = MMTimestamp.with { $0.secondsSinceEpoch = Int64(Date().timeIntervalSince1970) }
                }
                activeTaskMap.removeValue(forKey: taskId)
                await reloadEntries()

                // Start next queued download
                startNextQueued()
            }
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask,
                                didCompleteWithError error: Error?) {
        guard let error else { return }
        let taskId = task.taskIdentifier
        let nsError = error as NSError

        Task { @MainActor in
            guard let tcId = activeTaskMap[taskId] else { return }

            await store.updateEntry(transcodeId: tcId) { e in
                if let resumeData = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                    e.resumeData = resumeData
                }
                if nsError.code == NSURLErrorCancelled {
                    if e.state != .paused { e.state = .paused }
                } else {
                    e.state = .failed
                    e.errorMessage = error.localizedDescription
                }
            }
            activeTaskMap.removeValue(forKey: taskId)
            await reloadEntries()
        }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            backgroundCompletionHandler?()
            backgroundCompletionHandler = nil
        }
    }

    // MARK: - Private: Download Flow

    private func performDownload(transcodeId: Int64, sequence: Int32) async {
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
        } catch {
            await markFailed(transcodeId: transcodeId, error: "Manifest fetch failed: \(error.localizedDescription)")
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

        // Build download URL and start
        guard let url = downloadURL(for: transcodeId) else {
            await markFailed(transcodeId: transcodeId, error: "Cannot build download URL")
            return
        }

        await store.updateEntry(transcodeId: transcodeId) { e in
            e.state = .downloading
        }
        await reloadEntries()

        let task = backgroundSession.downloadTask(with: url)
        activeTaskMap[task.taskIdentifier] = transcodeId
        task.resume()
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

        // Poster via ImageProvider (will be cached by image system)
        // Save a local copy for offline display
        if let apiClient = self.apiClient,
           let posterData = try? await apiClient.getRaw("/posters/w185/\(entry.titleID)") {
            let path = await store.posterPath(for: entry)
            try? posterData.write(to: path)
            await store.updateEntry(transcodeId: tcId) { $0.hasPoster_p = true }
        }
    }

    // MARK: - Private: Helpers

    private func downloadURL(for transcodeId: Int64) -> URL? {
        guard let baseURL = cachedBaseURL else { return nil }
        return URL(string: baseURL.absoluteString + "/stream/\(transcodeId)?quality=mobile")
    }

    private func markFailed(transcodeId: Int64, error: String) async {
        await store.updateEntry(transcodeId: transcodeId) { e in
            e.state = .failed
            e.errorMessage = error
        }
        await reloadEntries()
    }

    private func reloadEntries() async {
        entries = await store.entries
    }

    private func startNextQueued() {
        guard activeDownloadCount < Self.maxConcurrentDownloads else { return }
        if let next = entries.first(where: { $0.state == .queued }) {
            Task {
                await performDownload(transcodeId: next.transcodeID, sequence: next.sequence)
            }
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

    // MARK: - Private: Task Reconciliation

    private func reconcileTasks() {
        backgroundSession.getAllTasks { [weak self] tasks in
            Task { @MainActor [weak self] in
                guard let self else { return }

                for entry in self.entries {
                    if entry.state == .downloading || entry.state == .fetchingMetadata {
                        let hasActiveTask = tasks.contains { task in
                            if let url = task.originalRequest?.url?.absoluteString,
                               url.contains("/stream/\(entry.transcodeID)") {
                                self.activeTaskMap[task.taskIdentifier] = entry.transcodeID
                                return true
                            }
                            return false
                        }
                        if !hasActiveTask {
                            await self.store.updateEntry(transcodeId: entry.transcodeID) { e in
                                e.state = .failed
                                e.errorMessage = "Download interrupted"
                            }
                        }
                    }
                }
                await self.reloadEntries()
            }
        }
    }

    /// Returns the downloads directory for a transcode — subtitles/chapters/thumbnails
    /// are stored as {seq}.subs.vtt etc. The caller can resolve specific files using the sequence.
    func localDir(for transcodeId: TranscodeID) -> URL? {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId.protoValue }),
              entry.state == .completed else { return nil }
        return store.downloadsDir
    }

    /// Returns the sequence prefix for a downloaded transcode (e.g., "0000001").
    func sequencePrefix(for transcodeId: TranscodeID) -> String? {
        guard let entry = entries.first(where: { $0.transcodeID == transcodeId.protoValue }),
              entry.state == .completed else { return nil }
        return String(format: "%07d", entry.sequence)
    }

    // MARK: - Offline Image Cache

    func loadCachedImage(for titleId: TitleID, name: String) -> Data? {
        // Check posters directory for the download's poster
        let entry = entries.first { $0.titleID == titleId.protoValue && $0.hasPoster_p }
        guard let entry else { return nil }
        let path = store.downloadsDir.appendingPathComponent("posters/\(String(format: "%07d", entry.sequence)).jpg")
        return try? Data(contentsOf: path)
    }
}
