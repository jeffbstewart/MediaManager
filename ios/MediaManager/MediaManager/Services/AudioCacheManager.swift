import Foundation
import Observation

private let logger = MMLogger(category: "AudioCacheManager")

/// Per-album metadata persisted into the on-disk download index.
/// Codable rather than protobuf because this state is iOS-local.
struct DownloadedAlbum: Codable, Identifiable, Sendable, Hashable {
    var id: Int64 { titleId }

    let titleId: Int64
    let name: String
    let artistName: String
    let downloadedAt: Date
    var lastAccessedAt: Date
    /// Aggregate size of every track file on disk for this album.
    /// Computed at download time and updated on resume; the
    /// Downloads view sums these for the "Audio storage" line.
    var sizeBytes: Int64
    /// Track ids that landed successfully. Drives the "downloaded?"
    /// dot in tracklist rows.
    let trackIds: [Int64]
}

/// In-flight progress for a single album download. Aggregate over
/// all tracks — drives a single album-level progress ring rather
/// than N per-track spinners.
struct AlbumDownloadProgress: Sendable, Hashable {
    let titleId: Int64
    let albumName: String
    var tracksCompleted: Int
    var tracksTotal: Int
    /// 0…1 across the whole album. Approximated as
    /// `tracksCompleted / tracksTotal` since per-byte progress for
    /// HTTP audio fetches isn't worth the complexity.
    var fraction: Double {
        tracksTotal > 0 ? Double(tracksCompleted) / Double(tracksTotal) : 0
    }
}

/// Owns offline album storage. Mirrors BookCacheManager's shape:
/// in-memory list of completed downloads + dict of in-flight
/// progress, both projected from / to a JSON index on disk.
///
/// **Disk layout:**
/// ```
/// <Application Support>/Downloads/Audio/
///     index.json                       // [DownloadedAlbum]
///     <titleId>.detail.pb              // serialized MMTitleDetail
///     <titleId>/<trackId>              // raw audio bytes
/// ```
///
/// The directory is excluded from iCloud backup; albums re-download
/// cleanly on a new device.
///
/// AudioPlayerManager checks `localTrackURL(trackId:)` before
/// reaching for the streaming endpoint, so playback transparently
/// uses local files when the album has been downloaded.
@Observable
@MainActor
final class AudioCacheManager {

    /// Albums whose tracks have all landed. Persisted to index.json.
    private(set) var downloads: [DownloadedAlbum] = []
    /// titleId → live progress. Drives the Download button's
    /// in-flight state in AlbumDetailView and the Active Albums
    /// section of DownloadsView.
    private(set) var activeDownloads: [Int64: AlbumDownloadProgress] = [:]
    /// titleId → human-readable error from the last failed attempt.
    /// Tap-to-retry clears the row.
    private(set) var failedDownloads: [Int64: String] = [:]

    /// Reverse index: trackId → titleId. Lets the player resolve a
    /// trackId to its parent album folder in O(1) without scanning
    /// every download. Rebuilt from the persisted index on launch.
    private var trackToAlbum: [Int64: Int64] = [:]

    private var apiClient: APIClient?
    /// Cancel tokens for in-flight album downloads. Each Task
    /// downloads its album's tracks sequentially; cancelling tears
    /// down the in-flight track fetch and removes the partial files.
    private var downloadTasks: [Int64: Task<Void, Never>] = [:]

    private let audioDir: URL
    private let indexPath: URL

    init() {
        let appSupport = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let downloadsRoot = appSupport.appendingPathComponent("Downloads", isDirectory: true)
        audioDir = downloadsRoot.appendingPathComponent("Audio", isDirectory: true)
        indexPath = audioDir.appendingPathComponent("index.json")

        do {
            try FileManager.default.createDirectory(at: audioDir, withIntermediateDirectories: true)
        } catch {
            logger.error("init: createDirectory failed at \(self.audioDir.path): \(error.localizedDescription)")
        }
        // iCloud-exclude — audio re-downloads cleanly.
        var dir = audioDir
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? dir.setResourceValues(rv)

        downloads = Self.loadIndex(at: indexPath)
        rebuildTrackIndex()
        logger.info("AudioCacheManager initialised; \(self.downloads.count) downloaded albums at \(self.audioDir.path)")
    }

    func configure(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    // MARK: - Public API

    /// True when this album is fully downloaded — drives the
    /// AlbumDetailView Download button's "Remove" affordance.
    func isDownloaded(titleId: Int64) -> Bool {
        downloads.contains { $0.titleId == titleId }
    }

    /// True while a download is in flight for this album.
    func isDownloading(titleId: Int64) -> Bool {
        activeDownloads[titleId] != nil
    }

    /// Resolve a track id to its on-disk file URL, or nil if the
    /// track isn't part of a downloaded album. AudioPlayerManager
    /// calls this before the streaming endpoint.
    ///
    /// Glob-matches `<trackId>` *or* `<trackId>.<ext>` so files
    /// written by both the legacy extensionless layout and the
    /// new extension-aware path resolve. AVPlayer needs the
    /// extension on local files to pick the right decoder; the
    /// glob lets old downloads keep working without forcing a
    /// blanket re-download.
    func localTrackURL(trackId: Int64) -> URL? {
        guard let titleId = trackToAlbum[trackId] else { return nil }
        let dir = albumDir(titleId: titleId)
        let bare = dir.appendingPathComponent("\(trackId)")
        if FileManager.default.fileExists(atPath: bare.path) { return bare }
        let prefix = "\(trackId)."
        guard let files = try? FileManager.default
            .contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
        else { return nil }
        return files.first { $0.lastPathComponent.hasPrefix(prefix) }
    }

    /// True when at least one track in this album has a local file.
    /// Drives the per-row downloaded indicator in tracklists.
    func isTrackDownloaded(trackId: Int64) -> Bool {
        localTrackURL(trackId: trackId) != nil
    }

    /// Cached album detail for offline browsing. AlbumDetailView
    /// falls back to this when the dataModel call throws .offline.
    func cachedAlbumDetail(titleId: Int64) -> ApiTitleDetail? {
        let path = albumDir(titleId: titleId).appendingPathComponent("detail.pb")
        guard FileManager.default.fileExists(atPath: path.path) else {
            logger.warning("cachedAlbumDetail: missing file for titleId=\(titleId) at \(path.path)")
            return nil
        }
        guard let data = try? Data(contentsOf: path) else {
            logger.warning("cachedAlbumDetail: read failed for titleId=\(titleId)")
            return nil
        }
        guard let proto = try? MMTitleDetail(serializedData: data) else {
            logger.warning("cachedAlbumDetail: deserialize failed for titleId=\(titleId), bytes=\(data.count)")
            return nil
        }
        if !proto.hasAlbum {
            logger.warning("cachedAlbumDetail: proto loaded but hasAlbum=false for titleId=\(titleId), bytes=\(data.count)")
        }
        return ApiTitleDetail(proto: proto)
    }

    /// Kick off a download for every playable track on the album.
    /// Idempotent: if the album is already downloaded or downloading,
    /// returns without re-queueing.
    func downloadAlbum(detail: ApiTitleDetail, album: ApiAlbum) {
        let titleId = detail.id.protoValue
        if isDownloaded(titleId: titleId) || isDownloading(titleId: titleId) { return }
        let playable = album.tracks.filter { $0.playable }
        guard !playable.isEmpty else {
            failedDownloads[titleId] = "No playable tracks on this album."
            return
        }
        failedDownloads.removeValue(forKey: titleId)
        activeDownloads[titleId] = AlbumDownloadProgress(
            titleId: titleId,
            albumName: detail.name,
            tracksCompleted: 0,
            tracksTotal: playable.count)

        downloadTasks[titleId] = Task { [weak self] in
            await self?.performAlbumDownload(detail: detail, album: album, tracks: playable)
        }
    }

    /// Cancel an in-flight download. Partial files are removed in
    /// the task's catch handler.
    func cancelDownload(titleId: Int64) {
        downloadTasks[titleId]?.cancel()
        downloadTasks.removeValue(forKey: titleId)
        activeDownloads.removeValue(forKey: titleId)
        // Tear down the partial album dir — half-downloaded tracks
        // would otherwise sit there confusing the next run.
        try? FileManager.default.removeItem(at: albumDir(titleId: titleId))
    }

    /// Remove a downloaded album. Wipes the album dir and updates
    /// the index. Image pins are released so the next cache pass
    /// can evict the cover.
    func deleteAlbum(titleId: Int64) {
        downloadTasks[titleId]?.cancel()
        downloadTasks.removeValue(forKey: titleId)
        activeDownloads.removeValue(forKey: titleId)
        try? FileManager.default.removeItem(at: albumDir(titleId: titleId))
        downloads.removeAll { $0.titleId == titleId }
        rebuildTrackIndex()
        persistIndex()
        Task { await ImageDiskCache.shared.unpin(ref: .posterThumbnail(titleId: titleId)) }
        Task { await ImageDiskCache.shared.unpin(ref: .posterFull(titleId: titleId)) }
    }

    /// Aggregate disk usage across all downloaded albums. The
    /// Downloads view's storage row sums this with the video / book
    /// counterparts.
    var totalStorageBytes: Int64 {
        downloads.map(\.sizeBytes).reduce(0, +)
    }

    // MARK: - Download flow

    private func performAlbumDownload(detail: ApiTitleDetail, album: ApiAlbum, tracks: [ApiTrack]) async {
        guard let apiClient else {
            failedDownloads[detail.id.protoValue] = "Server connection isn't ready yet."
            activeDownloads.removeValue(forKey: detail.id.protoValue)
            return
        }
        let titleId = detail.id.protoValue
        let dir = albumDir(titleId: titleId)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            failedDownloads[titleId] = "Disk error: \(error.localizedDescription)"
            activeDownloads.removeValue(forKey: titleId)
            return
        }

        // Persist the album detail so AlbumDetailView works offline.
        // Capture the input shape so a load-time mismatch ("hasAlbum
        // false on the deserialized proto") points at the right
        // half of the round-trip.
        do {
            let data = try detail.proto.serializedData()
            let path = dir.appendingPathComponent("detail.pb")
            try data.write(to: path, options: .atomic)
            logger.info("downloadAlbum: wrote detail.pb titleId=\(titleId) bytes=\(data.count) hasAlbum=\(detail.proto.hasAlbum)")
        } catch {
            logger.error("downloadAlbum: failed to write detail.pb titleId=\(titleId): \(error.localizedDescription)")
        }

        var downloadedTrackIds: [Int64] = []
        var totalBytes: Int64 = 0
        for track in tracks {
            if Task.isCancelled { break }
            do {
                let (data, contentType) = try await apiClient
                    .getRawWithContentType("audio/\(track.id)")
                let ext = Self.extension(forContentType: contentType)
                let filename = "\(track.id).\(ext)"
                let url = dir.appendingPathComponent(filename)
                try data.write(to: url, options: .atomic)
                downloadedTrackIds.append(track.id)
                totalBytes += Int64(data.count)
                if var progress = activeDownloads[titleId] {
                    progress.tracksCompleted += 1
                    activeDownloads[titleId] = progress
                }
            } catch {
                logger.warning("downloadAlbum: track \(track.id) failed: \(error.localizedDescription)")
                // Don't bail the whole album on a single-track
                // failure — the user wants the rest.
                continue
            }
        }

        if Task.isCancelled {
            try? FileManager.default.removeItem(at: dir)
            activeDownloads.removeValue(forKey: titleId)
            return
        }
        guard !downloadedTrackIds.isEmpty else {
            failedDownloads[titleId] = "No tracks could be downloaded."
            activeDownloads.removeValue(forKey: titleId)
            try? FileManager.default.removeItem(at: dir)
            return
        }

        let entry = DownloadedAlbum(
            titleId: titleId,
            name: detail.name,
            artistName: album.albumArtists.first?.name ?? "",
            downloadedAt: Date(),
            lastAccessedAt: Date(),
            sizeBytes: totalBytes,
            trackIds: downloadedTrackIds)
        downloads.removeAll { $0.titleId == titleId }
        downloads.append(entry)
        rebuildTrackIndex()
        persistIndex()
        activeDownloads.removeValue(forKey: titleId)
        downloadTasks.removeValue(forKey: titleId)
        // Pin cover images so the album survives cache eviction
        // for offline browsing.
        Task { await ImageDiskCache.shared.pin(ref: .posterThumbnail(titleId: titleId)) }
        Task { await ImageDiskCache.shared.pin(ref: .posterFull(titleId: titleId)) }
    }

    // MARK: - Persistence

    private func albumDir(titleId: Int64) -> URL {
        audioDir.appendingPathComponent("\(titleId)", isDirectory: true)
    }

    private func rebuildTrackIndex() {
        var idx: [Int64: Int64] = [:]
        for album in downloads {
            for tid in album.trackIds { idx[tid] = album.titleId }
        }
        trackToAlbum = idx
    }

    private func persistIndex() {
        if let data = try? JSONEncoder().encode(downloads) {
            try? data.write(to: indexPath, options: .atomic)
        }
    }

    /// Map an audio Content-Type to a file extension AVURLAsset
    /// will recognize. Mirrors the server's contentTypeFor table in
    /// AudioStreamHttpService.kt — keep them in sync.
    private static func `extension`(forContentType contentType: String?) -> String {
        // Strip parameters like ";charset=binary" the server might add.
        let bare = (contentType ?? "")
            .split(separator: ";").first.map(String.init)?
            .trimmingCharacters(in: .whitespaces).lowercased()
            ?? ""
        switch bare {
        case "audio/mpeg":           return "mp3"
        case "audio/mp4":            return "m4a"
        case "audio/flac":           return "flac"
        case "audio/ogg":            return "ogg"
        case "audio/wav",
             "audio/x-wav":          return "wav"
        case "audio/aac":            return "aac"
        // Fallback when the server returns octet-stream or empty.
        // AVPlayer can sniff most magic-byte-tagged formats from a
        // generic ".audio" extension, but mp3 is the most common
        // audio source format MM ingests, so treat that as the
        // safest blind guess.
        default:                     return "mp3"
        }
    }

    private static func loadIndex(at path: URL) -> [DownloadedAlbum] {
        guard let data = try? Data(contentsOf: path),
              let entries = try? JSONDecoder().decode([DownloadedAlbum].self, from: data) else {
            return []
        }
        return entries
    }
}
