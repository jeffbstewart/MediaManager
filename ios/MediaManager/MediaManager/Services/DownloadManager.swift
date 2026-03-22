import Foundation
import Network
import Observation

@Observable
@MainActor
final class DownloadManager: NSObject, URLSessionDownloadDelegate {
    private(set) var items: [DownloadItem] = []
    var isOfflineMode: Bool {
        didSet { UserDefaults.standard.set(isOfflineMode, forKey: "offlineMode") }
    }
    private(set) var hasNetworkConnectivity = true

    /// True when forced offline or actually disconnected.
    var isEffectivelyOffline: Bool {
        isOfflineMode || !hasNetworkConnectivity
    }

    /// Whether any completed downloads exist (used for UI visibility decisions).
    var hasCompletedDownloads: Bool {
        items.contains { $0.state == .completed }
    }

    private var backgroundSession: URLSession!
    private var apiClient: APIClient?
    private var cachedBaseURL: URL?
    private var activeTaskMap: [Int: Int] = [:]  // URLSessionTask.taskIdentifier → transcodeId
    private var backgroundCompletionHandler: (() -> Void)?
    private var networkMonitor: NWPathMonitor?
    private var titleCacheEntries: [Int: TitleCacheEntry] = [:]  // titleId → entry
    private var pendingProgress: [PendingProgressUpdate] = []

    private static let sessionIdentifier = "net.stewart.mediamanager.downloads"
    private static let persistenceFile: URL = {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("downloads.json")
    }()
    private static let titleCacheFile: URL = {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("downloads_title_cache.json")
    }()
    private static let progressQueueFile: URL = {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("downloads_progress_queue.json")
    }()

    /// How long before a title cache is considered stale (24 hours).
    private static let cacheStaleInterval: TimeInterval = 24 * 60 * 60

    override init() {
        self.isOfflineMode = UserDefaults.standard.bool(forKey: "offlineMode")
        super.init()
        let config = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.httpCookieStorage = .shared
        backgroundSession = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        loadPersistedItems()
        loadTitleCacheEntries()
        loadPendingProgress()
        reconcileTasks()
        startNetworkMonitor()
    }

    func configure(apiClient: APIClient) {
        self.apiClient = apiClient
        Task {
            self.cachedBaseURL = await apiClient.getBaseURL()
            // Flush any queued progress updates now that we have an API client
            if !isEffectivelyOffline {
                await flushPendingProgress()
            }
        }
    }

    // MARK: - Public API

    func startDownload(transcodeId: Int, titleId: Int, titleName: String,
                       posterUrl: String?, quality: String?, year: Int?) {
        guard item(for: transcodeId) == nil else { return }

        let download = DownloadItem(
            transcodeId: transcodeId,
            titleId: titleId,
            titleName: titleName,
            posterUrl: posterUrl,
            quality: quality,
            year: year,
            state: .fetchingMetadata,
            fileSizeBytes: nil,
            bytesDownloaded: 0,
            hasSubtitles: false,
            hasThumbnails: false,
            hasChapters: false,
            resumeData: nil,
            errorMessage: nil,
            addedAt: Date()
        )

        items.append(download)
        persist()

        Task {
            await performDownload(transcodeId: transcodeId)
        }
    }

    func pauseDownload(transcodeId: Int) {
        guard let index = items.firstIndex(where: { $0.transcodeId == transcodeId }),
              items[index].state == .downloading else { return }

        if let taskId = activeTaskMap.first(where: { $0.value == transcodeId })?.key {
            backgroundSession.getAllTasks { [weak self] tasks in
                if let task = tasks.first(where: { $0.taskIdentifier == taskId }) as? URLSessionDownloadTask {
                    task.cancel(byProducingResumeData: { data in
                        Task { @MainActor [weak self] in
                            guard let self,
                                  let idx = self.items.firstIndex(where: { $0.transcodeId == transcodeId }) else { return }
                            self.items[idx].resumeData = data
                            self.items[idx].state = .paused
                            self.activeTaskMap.removeValue(forKey: taskId)
                            self.persist()
                        }
                    })
                }
            }
        }
    }

    func resumeDownload(transcodeId: Int) {
        guard let index = items.firstIndex(where: { $0.transcodeId == transcodeId }),
              items[index].state == .paused || items[index].state == .failed else { return }

        items[index].state = .downloading
        items[index].errorMessage = nil

        if let resumeData = items[index].resumeData {
            let task = backgroundSession.downloadTask(withResumeData: resumeData)
            activeTaskMap[task.taskIdentifier] = transcodeId
            task.resume()
            items[index].resumeData = nil
        } else {
            guard let url = downloadURL(for: transcodeId) else {
                items[index].state = .failed
                items[index].errorMessage = "Cannot build download URL"
                persist()
                return
            }
            let task = backgroundSession.downloadTask(with: url)
            activeTaskMap[task.taskIdentifier] = transcodeId
            task.resume()
        }
        persist()
    }

    func deleteDownload(transcodeId: Int) {
        guard let item = item(for: transcodeId) else { return }
        let titleId = item.titleId

        // Cancel any active task
        if let taskId = activeTaskMap.first(where: { $0.value == transcodeId })?.key {
            backgroundSession.getAllTasks { tasks in
                tasks.first(where: { $0.taskIdentifier == taskId })?.cancel()
            }
            activeTaskMap.removeValue(forKey: taskId)
        }

        // Remove transcode directory
        let dir = DownloadItem.transcodesRoot.appendingPathComponent("\(transcodeId)")
        try? FileManager.default.removeItem(at: dir)

        items.removeAll { $0.transcodeId == transcodeId }
        persist()

        // Clean up title cache if no more downloads for this title
        cleanupTitleCacheIfOrphaned(titleId: titleId)
    }

    func localFileURL(for transcodeId: Int) -> URL? {
        guard let item = item(for: transcodeId),
              item.state == .completed else { return nil }
        let url = item.videoFileURL
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func localDir(for transcodeId: Int) -> URL? {
        guard let item = item(for: transcodeId),
              item.state == .completed else { return nil }
        let url = item.localDir
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) && isDir.boolValue ? url : nil
    }

    func state(for transcodeId: Int) -> DownloadState? {
        item(for: transcodeId)?.state
    }

    func item(for transcodeId: Int) -> DownloadItem? {
        items.first { $0.transcodeId == transcodeId }
    }

    var activeDownloadCount: Int {
        items.filter { $0.state == .fetchingMetadata || $0.state == .downloading }.count
    }

    var totalStorageUsed: Int64 {
        items.filter { $0.state == .completed }.compactMap { $0.fileSizeBytes }.reduce(0, +)
    }

    // MARK: - Offline Mode

    /// Set of unique title IDs that have at least one completed download.
    var offlineTitleIds: Set<Int> {
        Set(items.filter { $0.state == .completed }.map { $0.titleId })
    }

    // MARK: - Title Cache

    /// Returns the title cache directory for a given title, or nil if no cache exists.
    func titleCacheDir(for titleId: Int) -> URL? {
        let dir = DownloadItem.titleCacheDir(for: titleId)
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: dir.path, isDirectory: &isDir),
              isDir.boolValue else { return nil }
        return dir
    }

    /// Load cached title detail from disk.
    func loadCachedTitleDetail(for titleId: Int) -> ApiTitleDetail? {
        guard let dir = titleCacheDir(for: titleId) else { return nil }
        let file = dir.appendingPathComponent("detail.json")
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(ApiTitleDetail.self, from: data)
    }

    /// Load cached seasons from disk.
    func loadCachedSeasons(for titleId: Int) -> [ApiSeason]? {
        guard let dir = titleCacheDir(for: titleId) else { return nil }
        let file = dir.appendingPathComponent("seasons.json")
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode([ApiSeason].self, from: data)
    }

    /// Load cached episodes for a season from disk.
    func loadCachedEpisodes(for titleId: Int, season: Int) -> [ApiEpisode]? {
        guard let dir = titleCacheDir(for: titleId) else { return nil }
        let file = dir.appendingPathComponent("episodes_\(season).json")
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode([ApiEpisode].self, from: data)
    }

    /// Load a cached image (poster, backdrop, headshot) from the title cache.
    func loadCachedImage(for titleId: Int, name: String) -> Data? {
        guard let dir = titleCacheDir(for: titleId) else { return nil }
        return try? Data(contentsOf: dir.appendingPathComponent(name))
    }

    // MARK: - Progress Queue

    /// Queue a progress update for later sync (used when offline).
    func queueProgressUpdate(transcodeId: Int, position: Double, duration: Double?) {
        // Replace any existing entry for this transcode (only latest matters)
        pendingProgress.removeAll { $0.transcodeId == transcodeId }
        pendingProgress.append(PendingProgressUpdate(
            transcodeId: transcodeId,
            position: position,
            duration: duration,
            timestamp: Date()
        ))
        persistPendingProgress()
    }

    /// Flush all pending progress updates to the server.
    func flushPendingProgress() async {
        guard let apiClient, !pendingProgress.isEmpty else { return }

        var remaining: [PendingProgressUpdate] = []
        for update in pendingProgress {
            var body: [String: Any] = ["position": update.position]
            if let dur = update.duration {
                body["duration"] = dur
            }
            do {
                try await apiClient.post("playback/progress/\(update.transcodeId)", body: body)
            } catch {
                remaining.append(update)
            }
        }
        pendingProgress = remaining
        persistPendingProgress()
    }

    // MARK: - Background Session Handling

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
            guard let tcId = activeTaskMap[taskId],
                  let index = items.firstIndex(where: { $0.transcodeId == tcId }) else { return }
            items[index].bytesDownloaded = totalBytesWritten
            if totalBytesExpectedToWrite > 0 {
                items[index].fileSizeBytes = totalBytesExpectedToWrite
            }
        }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didFinishDownloadingTo location: URL) {
        let taskId = downloadTask.taskIdentifier
        let tempURL = location
        let fm = FileManager.default

        MainActor.assumeIsolated {
            guard let tcId = activeTaskMap[taskId] else { return }

            let dir = DownloadItem.transcodesRoot.appendingPathComponent("\(tcId)")
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let dest = dir.appendingPathComponent("video.mp4")
            try? fm.removeItem(at: dest)
            do {
                try fm.moveItem(at: tempURL, to: dest)
            } catch {
                markFailed(transcodeId: tcId, error: "Failed to save video: \(error.localizedDescription)")
                return
            }

            guard let index = items.firstIndex(where: { $0.transcodeId == tcId }) else { return }
            items[index].state = .completed
            items[index].bytesDownloaded = items[index].fileSizeBytes ?? 0
            activeTaskMap.removeValue(forKey: taskId)
            persist()

            // Cache title data now that a download completed
            let titleId = items[index].titleId
            Task {
                await cacheTitleData(titleId: titleId)
            }
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask,
                                didCompleteWithError error: Error?) {
        guard let error else { return }
        let taskId = task.taskIdentifier
        let nsError = error as NSError

        Task { @MainActor in
            guard let tcId = activeTaskMap[taskId],
                  let index = items.firstIndex(where: { $0.transcodeId == tcId }) else { return }

            if let resumeData = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                items[index].resumeData = resumeData
            }

            if nsError.code == NSURLErrorCancelled {
                if items[index].state != .paused {
                    items[index].state = .paused
                }
            } else {
                items[index].state = .failed
                items[index].errorMessage = error.localizedDescription
            }

            activeTaskMap.removeValue(forKey: taskId)
            persist()
        }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            backgroundCompletionHandler?()
            backgroundCompletionHandler = nil
        }
    }

    // MARK: - Private: Download Flow

    private func performDownload(transcodeId tcId: Int) async {
        guard let apiClient else {
            markFailed(transcodeId: tcId, error: "Not configured")
            return
        }

        guard let download = item(for: tcId) else { return }
        let dir = download.localDir

        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        // Fetch manifest
        do {
            let manifest: ApiDownloadManifest = try await apiClient.get("downloads/manifest/\(tcId)")
            guard let index = items.firstIndex(where: { $0.transcodeId == tcId }) else { return }
            items[index].fileSizeBytes = manifest.fileSizeBytes
        } catch {
            markFailed(transcodeId: tcId, error: "Failed to fetch manifest: \(error.localizedDescription)")
            return
        }

        // Check disk space
        if let index = items.firstIndex(where: { $0.transcodeId == tcId }),
           let fileSize = items[index].fileSizeBytes {
            let requiredSpace = fileSize + 500_000_000
            if let freeSpace = try? FileManager.default.attributesOfFileSystem(
                forPath: NSHomeDirectory())[.systemFreeSize] as? Int64,
               freeSpace < requiredSpace {
                markFailed(transcodeId: tcId, error: "Not enough disk space. Need \(ByteCountFormatter.string(fromByteCount: requiredSpace, countStyle: .file)), have \(ByteCountFormatter.string(fromByteCount: freeSpace, countStyle: .file))")
                return
            }
        }

        // Download supporting files
        await downloadSupportingFiles(transcodeId: tcId, dir: dir)

        // Cache title data (refresh if stale)
        if let download = item(for: tcId) {
            await cacheTitleDataIfStale(titleId: download.titleId)
        }

        // Start MP4 download
        guard let url = downloadURL(for: tcId) else {
            markFailed(transcodeId: tcId, error: "Cannot build download URL")
            return
        }

        guard let index = items.firstIndex(where: { $0.transcodeId == tcId }) else { return }
        items[index].state = .downloading
        persist()

        let task = backgroundSession.downloadTask(with: url)
        activeTaskMap[task.taskIdentifier] = tcId
        task.resume()
    }

    private func downloadSupportingFiles(transcodeId: Int, dir: URL) async {
        guard let apiClient else { return }

        // Subtitles
        if let data = try? await apiClient.getRaw("stream/\(transcodeId)/subs.vtt"),
           let content = String(data: data, encoding: .utf8), content.contains("-->") {
            try? data.write(to: dir.appendingPathComponent("subs.vtt"))
            if let index = items.firstIndex(where: { $0.transcodeId == transcodeId }) {
                items[index].hasSubtitles = true
            }
        }

        // Thumbnails VTT
        if let data = try? await apiClient.getRaw("stream/\(transcodeId)/thumbs.vtt"),
           let content = String(data: data, encoding: .utf8), content.contains("-->") {
            try? data.write(to: dir.appendingPathComponent("thumbs.vtt"))

            let sheetIndices = parseSpriteSheetIndices(from: content)
            for sheetIndex in sheetIndices {
                if let imgData = try? await apiClient.getRaw("stream/\(transcodeId)/thumbs_\(sheetIndex).jpg") {
                    try? imgData.write(to: dir.appendingPathComponent("thumbs_\(sheetIndex).jpg"))
                }
            }

            if let index = items.firstIndex(where: { $0.transcodeId == transcodeId }) {
                items[index].hasThumbnails = true
            }
        }

        // Chapters/skip segments
        if let data = try? await apiClient.getRaw("api/v1/stream/\(transcodeId)/chapters") {
            if (try? JSONDecoder().decode(ChaptersResponse.self, from: data)) != nil {
                try? data.write(to: dir.appendingPathComponent("chapters.json"))
                if let index = items.firstIndex(where: { $0.transcodeId == transcodeId }) {
                    items[index].hasChapters = true
                }
            }
        }

        persist()
    }

    // MARK: - Private: Title Cache

    /// Cache title detail, images, seasons, and episodes for offline use.
    private func cacheTitleData(titleId: Int) async {
        guard let apiClient else { return }
        let dir = DownloadItem.titleCacheDir(for: titleId)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        // Title detail
        do {
            let detail: ApiTitleDetail = try await apiClient.get("catalog/titles/\(titleId)")
            let data = try JSONEncoder().encode(detail)
            try data.write(to: dir.appendingPathComponent("detail.json"), options: .atomic)

            // Poster image
            if let posterUrl = detail.posterUrl {
                if let imgData = try? await apiClient.getRaw(posterUrl) {
                    try? imgData.write(to: dir.appendingPathComponent("poster.jpg"))
                }
            }

            // Backdrop image
            if let backdropUrl = detail.backdropUrl {
                if let imgData = try? await apiClient.getRaw(backdropUrl) {
                    try? imgData.write(to: dir.appendingPathComponent("backdrop.jpg"))
                }
            }

            // Cast headshots
            let headshotsDir = dir.appendingPathComponent("headshots")
            try? FileManager.default.createDirectory(at: headshotsDir, withIntermediateDirectories: true)
            for member in detail.cast.prefix(20) {
                if let headshotUrl = member.headshotUrl {
                    if let imgData = try? await apiClient.getRaw(headshotUrl) {
                        try? imgData.write(to: headshotsDir.appendingPathComponent("\(member.tmdbPersonId).jpg"))
                    }
                }
            }
        } catch {
            // Title detail fetch failed — don't block on this
        }

        // Seasons
        if let seasons: [ApiSeason] = try? await apiClient.get("catalog/titles/\(titleId)/seasons"),
           let data = try? JSONEncoder().encode(seasons) {
            try? data.write(to: dir.appendingPathComponent("seasons.json"), options: .atomic)

            // Episodes for each season
            for season in seasons {
                if let episodes: [ApiEpisode] = try? await apiClient.get(
                    "catalog/titles/\(titleId)/seasons/\(season.seasonNumber)/episodes"),
                   let epData = try? JSONEncoder().encode(episodes) {
                    try? epData.write(to: dir.appendingPathComponent("episodes_\(season.seasonNumber).json"), options: .atomic)
                }
            }
        }

        // Update cache timestamp
        titleCacheEntries[titleId] = TitleCacheEntry(titleId: titleId, lastUpdated: Date())
        persistTitleCacheEntries()
    }

    /// Refresh title cache only if stale (older than 24 hours).
    private func cacheTitleDataIfStale(titleId: Int) async {
        if let entry = titleCacheEntries[titleId],
           Date().timeIntervalSince(entry.lastUpdated) < Self.cacheStaleInterval {
            return // Still fresh
        }
        await cacheTitleData(titleId: titleId)
    }

    /// Remove title cache directory if no downloads remain for that title.
    private func cleanupTitleCacheIfOrphaned(titleId: Int) {
        let hasRemainingDownloads = items.contains { $0.titleId == titleId }
        if !hasRemainingDownloads {
            let dir = DownloadItem.titleCacheDir(for: titleId)
            try? FileManager.default.removeItem(at: dir)
            titleCacheEntries.removeValue(forKey: titleId)
            persistTitleCacheEntries()
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
                // Connectivity restored — flush queued progress
                if wasOffline && !self.isEffectivelyOffline {
                    await self.flushPendingProgress()
                }
            }
        }
        monitor.start(queue: .main)
        networkMonitor = monitor
    }

    // MARK: - Private: Helpers

    private func parseSpriteSheetIndices(from vttContent: String) -> Set<Int> {
        var indices = Set<Int>()
        let pattern = try? NSRegularExpression(pattern: #"thumbs_(\d+)\.jpg"#)
        let range = NSRange(vttContent.startIndex..., in: vttContent)
        pattern?.enumerateMatches(in: vttContent, range: range) { match, _, _ in
            if let match, let numRange = Range(match.range(at: 1), in: vttContent) {
                if let num = Int(vttContent[numRange]) {
                    indices.insert(num)
                }
            }
        }
        return indices
    }

    private func downloadURL(for transcodeId: Int) -> URL? {
        guard let baseURL = cachedBaseURL else { return nil }
        return URL(string: baseURL.absoluteString + "/api/v1/downloads/\(transcodeId)")
    }

    private func markFailed(transcodeId: Int, error: String) {
        guard let index = items.firstIndex(where: { $0.transcodeId == transcodeId }) else { return }
        items[index].state = .failed
        items[index].errorMessage = error
        persist()
    }

    // MARK: - Persistence

    private func persist() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(items) else { return }
        try? data.write(to: Self.persistenceFile, options: .atomic)
    }

    private func loadPersistedItems() {
        guard let data = try? Data(contentsOf: Self.persistenceFile) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        items = (try? decoder.decode([DownloadItem].self, from: data)) ?? []
    }

    private func persistTitleCacheEntries() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let entries = Array(titleCacheEntries.values)
        guard let data = try? encoder.encode(entries) else { return }
        try? data.write(to: Self.titleCacheFile, options: .atomic)
    }

    private func loadTitleCacheEntries() {
        guard let data = try? Data(contentsOf: Self.titleCacheFile) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if let entries = try? decoder.decode([TitleCacheEntry].self, from: data) {
            titleCacheEntries = Dictionary(uniqueKeysWithValues: entries.map { ($0.titleId, $0) })
        }
    }

    private func persistPendingProgress() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(pendingProgress) else { return }
        try? data.write(to: Self.progressQueueFile, options: .atomic)
    }

    private func loadPendingProgress() {
        guard let data = try? Data(contentsOf: Self.progressQueueFile) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        pendingProgress = (try? decoder.decode([PendingProgressUpdate].self, from: data)) ?? []
    }

    private func reconcileTasks() {
        backgroundSession.getAllTasks { [weak self] tasks in
            Task { @MainActor [weak self] in
                guard let self else { return }

                for i in self.items.indices {
                    if self.items[i].state == .downloading || self.items[i].state == .fetchingMetadata {
                        let hasActiveTask = tasks.contains { task in
                            if let url = task.originalRequest?.url?.absoluteString,
                               url.contains("/downloads/\(self.items[i].transcodeId)") {
                                self.activeTaskMap[task.taskIdentifier] = self.items[i].transcodeId
                                return true
                            }
                            return false
                        }
                        if !hasActiveTask {
                            self.items[i].state = .failed
                            self.items[i].errorMessage = "Download interrupted"
                        }
                    }
                }
                self.persist()
            }
        }
    }
}
