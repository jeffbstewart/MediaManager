import Foundation
import Observation
import MediaManagerProtos

private let logger = MMLogger(category: "AudioCacheManager")

/// Per-album metadata persisted into the on-disk download index.
/// Codable rather than protobuf because this state is iOS-local.
public struct DownloadedAlbum: Codable, Identifiable, Sendable, Hashable {
    public var id: Int64 { titleId }

    public let titleId: Int64
    public let name: String
    public let artistName: String
    /// Album-artist IDs captured at download time. Drives the
    /// "any album by this artist is downloaded" indicator on the
    /// Artists grid (`AudioCacheManager.offlineArtistIds`). Entries
    /// persisted before this field existed deserialize as `[]` — the
    /// indicator stays hidden for those until the album is re-downloaded,
    /// which is acceptable degradation.
    public let artistIds: [Int64]
    public let downloadedAt: Date
    public var lastAccessedAt: Date
    /// Aggregate size of every track file on disk for this album.
    /// Computed at download time and updated on resume; the
    /// Downloads view sums these for the "Audio storage" line.
    public var sizeBytes: Int64
    /// Track ids that landed successfully. Drives the "downloaded?"
    /// dot in tracklist rows.
    public let trackIds: [Int64]

    public init(
        titleId: Int64, name: String, artistName: String,
        artistIds: [Int64] = [],
        downloadedAt: Date, lastAccessedAt: Date,
        sizeBytes: Int64, trackIds: [Int64]
    ) {
        self.titleId = titleId
        self.name = name
        self.artistName = artistName
        self.artistIds = artistIds
        self.downloadedAt = downloadedAt
        self.lastAccessedAt = lastAccessedAt
        self.sizeBytes = sizeBytes
        self.trackIds = trackIds
    }

    /// Decode with `artistIds` optional so the index keeps loading
    /// after a TestFlight build with no such field on disk.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.titleId       = try c.decode(Int64.self, forKey: .titleId)
        self.name          = try c.decode(String.self, forKey: .name)
        self.artistName    = try c.decode(String.self, forKey: .artistName)
        self.artistIds     = try c.decodeIfPresent([Int64].self, forKey: .artistIds) ?? []
        self.downloadedAt  = try c.decode(Date.self, forKey: .downloadedAt)
        self.lastAccessedAt = try c.decode(Date.self, forKey: .lastAccessedAt)
        self.sizeBytes     = try c.decode(Int64.self, forKey: .sizeBytes)
        self.trackIds      = try c.decode([Int64].self, forKey: .trackIds)
    }
}

/// In-flight progress for a single album download. Aggregate over
/// all tracks — drives a single album-level progress ring rather
/// than N per-track spinners.
public struct AlbumDownloadProgress: Sendable, Hashable {
    public let titleId: Int64
    public let albumName: String
    public var tracksCompleted: Int
    public var tracksTotal: Int
    /// 0…1 across the whole album. Approximated as
    /// `tracksCompleted / tracksTotal` since per-byte progress for
    /// HTTP audio fetches isn't worth the complexity.
    public var fraction: Double {
        tracksTotal > 0 ? Double(tracksCompleted) / Double(tracksTotal) : 0
    }
}

/// Per-playlist on-disk metadata. Same shape as DownloadedAlbum,
/// keyed by playlistId. Tracks can overlap with album-downloaded
/// content; on-disk we hard-link album files into the playlist
/// directory rather than re-downloading bytes — see downloadPlaylist
/// for the dedup strategy.
public struct DownloadedPlaylist: Codable, Identifiable, Sendable, Hashable {
    public var id: Int64 { playlistId }

    public let playlistId: Int64
    public let name: String
    public let downloadedAt: Date
    public var lastAccessedAt: Date
    public var sizeBytes: Int64
    public let trackIds: [Int64]
}

public struct PlaylistDownloadProgress: Sendable, Hashable {
    public let playlistId: Int64
    public let playlistName: String
    public var tracksCompleted: Int
    public var tracksTotal: Int
    public var fraction: Double {
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
public final class AudioCacheManager {

    /// Albums whose tracks have all landed. Persisted to index.json.
    public private(set) var downloads: [DownloadedAlbum] = []
    /// titleId → live progress. Drives the Download button's
    /// in-flight state in AlbumDetailView and the Active Albums
    /// section of DownloadsView.
    public private(set) var activeDownloads: [Int64: AlbumDownloadProgress] = [:]
    /// titleId → human-readable error from the last failed attempt.
    /// Tap-to-retry clears the row.
    public private(set) var failedDownloads: [Int64: String] = [:]

    /// Playlists whose tracks have all landed. Persisted to
    /// playlists.json (separate file from album index for clean
    /// migration / partial corruption containment).
    public private(set) var playlistDownloads: [DownloadedPlaylist] = []
    /// playlistId → live progress.
    public private(set) var activePlaylistDownloads: [Int64: PlaylistDownloadProgress] = [:]
    /// playlistId → last failure message.
    public private(set) var failedPlaylistDownloads: [Int64: String] = [:]

    /// Reverse index: trackId → titleId. Lets the player resolve a
    /// trackId to its parent album folder in O(1) without scanning
    /// every download. Rebuilt from the persisted index on launch.
    private var trackToAlbum: [Int64: Int64] = [:]
    /// Reverse index: trackId → set of playlistIds containing it.
    /// One track can be in multiple playlists, hence the array.
    /// Used by localTrackURL for the playlist-directory fallback
    /// when a track isn't in an album download.
    private var trackToPlaylists: [Int64: [Int64]] = [:]

    private var apiClient: APIClient?
    /// Cancel tokens for in-flight album downloads. Each Task
    /// downloads its album's tracks sequentially; cancelling tears
    /// down the in-flight track fetch and removes the partial files.
    private var downloadTasks: [Int64: Task<Void, Never>] = [:]
    private var playlistDownloadTasks: [Int64: Task<Void, Never>] = [:]

    private let audioDir: URL
    private let indexPath: URL
    private let playlistIndexPath: URL

    public init() {
        let appSupport = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let downloadsRoot = appSupport.appendingPathComponent("Downloads", isDirectory: true)
        audioDir = downloadsRoot.appendingPathComponent("Audio", isDirectory: true)
        indexPath = audioDir.appendingPathComponent("index.json")
        playlistIndexPath = audioDir.appendingPathComponent("playlists.json")

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
        playlistDownloads = Self.loadPlaylistIndex(at: playlistIndexPath)
        rebuildTrackIndex()
        logger.info("AudioCacheManager initialised; \(self.downloads.count) albums + \(self.playlistDownloads.count) playlists at \(self.audioDir.path)")
    }

    public func configure(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    // MARK: - Public API

    /// True when this album is fully downloaded — drives the
    /// AlbumDetailView Download button's "Remove" affordance.
    public func isDownloaded(titleId: Int64) -> Bool {
        downloads.contains { $0.titleId == titleId }
    }

    /// Set of album-artist IDs that have at least one album cached
    /// locally. Drives the Artists grid's "any album by this artist
    /// is offline" badge. Self-heals as albums are (re-)downloaded
    /// — entries persisted before `artistIds` was tracked carry an
    /// empty array and won't contribute until the album re-downloads.
    public var offlineArtistIds: Set<Int64> {
        Set(downloads.flatMap { $0.artistIds })
    }

    /// Aggregate progress across an explicit set of album title IDs.
    /// Used by container views (ArtistDetail "Download all albums")
    /// to drive a status row. Pure-compute on the existing state.
    public struct BulkAlbumStatus: Equatable {
        public let total: Int
        public let completed: Int
        public let inFlight: Int
        public init(total: Int, completed: Int, inFlight: Int) {
            self.total = total
            self.completed = completed
            self.inFlight = inFlight
        }
        public var pending: Int { max(0, total - completed - inFlight) }
        public var fraction: Double {
            total > 0 ? Double(completed) / Double(total) : 0
        }
        public var hasWork: Bool { pending > 0 }
    }

    public func bulkStatus(forAlbumTitleIds ids: [Int64]) -> BulkAlbumStatus {
        guard !ids.isEmpty else {
            return BulkAlbumStatus(total: 0, completed: 0, inFlight: 0)
        }
        let completedSet = Set(downloads.map { $0.titleId })
        let inFlightSet = Set(activeDownloads.keys)
        var c = 0, f = 0
        for id in ids {
            if completedSet.contains(id) { c += 1 }
            else if inFlightSet.contains(id) { f += 1 }
        }
        return BulkAlbumStatus(total: ids.count, completed: c, inFlight: f)
    }

    /// True while a download is in flight for this album.
    public func isDownloading(titleId: Int64) -> Bool {
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
    public func localTrackURL(trackId: Int64) -> URL? {
        // Album storage first — album downloads are the canonical
        // home for a track's bytes. Playlist downloads usually
        // hard-link into this same file (same inode), so checking
        // the album directory wins for the common case.
        if let titleId = trackToAlbum[trackId],
           let url = findTrackFile(in: albumDir(titleId: titleId), trackId: trackId) {
            return url
        }
        // Fall back to any playlist directory carrying the track —
        // tracks downloaded as part of a playlist whose parent album
        // wasn't (or has since been deleted) live there.
        for playlistId in trackToPlaylists[trackId] ?? [] {
            if let url = findTrackFile(in: playlistDir(playlistId: playlistId), trackId: trackId) {
                return url
            }
        }
        return nil
    }

    /// Glob-match `<trackId>` *or* `<trackId>.<ext>` inside `dir`.
    /// Used by both album- and playlist-storage lookups so the
    /// extension-aware (post-fix) and extensionless (legacy)
    /// layouts both resolve.
    private func findTrackFile(in dir: URL, trackId: Int64) -> URL? {
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
    public func isTrackDownloaded(trackId: Int64) -> Bool {
        localTrackURL(trackId: trackId) != nil
    }

    /// Cached album detail for offline browsing. AlbumDetailView
    /// falls back to this when the dataModel call throws .offline.
    public func cachedAlbumDetail(titleId: Int64) -> ApiTitleDetail? {
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
    public func downloadAlbum(detail: ApiTitleDetail, album: ApiAlbum) {
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
    public func cancelDownload(titleId: Int64) {
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
    public func deleteAlbum(titleId: Int64) {
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

    /// Aggregate disk usage across all downloaded albums + playlists.
    /// Sums the size fields verbatim — hard-linked tracks shared
    /// between an album and a playlist will be counted in both
    /// rows, slightly overestimating actual on-disk usage. That's
    /// fine for the Downloads view's "you've used X" line; an
    /// exact figure would need to dedup by inode which is more
    /// work than the user-facing display warrants.
    public var totalStorageBytes: Int64 {
        downloads.map(\.sizeBytes).reduce(0, +)
            + playlistDownloads.map(\.sizeBytes).reduce(0, +)
    }

    // MARK: - Playlist downloads

    public func isPlaylistDownloaded(playlistId: Int64) -> Bool {
        playlistDownloads.contains { $0.playlistId == playlistId }
    }

    public func isPlaylistDownloading(playlistId: Int64) -> Bool {
        activePlaylistDownloads[playlistId] != nil
    }

    /// Cached playlist detail for offline browsing. PlaylistDetailView
    /// falls back to this when the dataModel call throws .offline.
    public func cachedPlaylistDetail(playlistId: Int64) -> ApiPlaylistDetail? {
        let path = playlistDir(playlistId: playlistId).appendingPathComponent("detail.pb")
        guard FileManager.default.fileExists(atPath: path.path),
              let data = try? Data(contentsOf: path),
              let proto = try? MMPlaylistDetail(serializedData: data) else {
            return nil
        }
        return ApiPlaylistDetail(proto: proto)
    }

    /// Kick off a download for every playable track in a playlist.
    /// Tracks already on disk via an album download are hard-linked
    /// into the playlist directory rather than re-downloaded —
    /// duplicates the inode reference, not the bytes. When either
    /// the album or the playlist is later deleted, the file stays
    /// alive until the last reference is gone.
    public func downloadPlaylist(detail: ApiPlaylistDetail) {
        let playlistId = detail.summary.id
        if isPlaylistDownloaded(playlistId: playlistId) || isPlaylistDownloading(playlistId: playlistId) { return }
        let playable = detail.tracks.filter { $0.track.playable }
        guard !playable.isEmpty else {
            failedPlaylistDownloads[playlistId] = "No playable tracks on this playlist."
            return
        }
        failedPlaylistDownloads.removeValue(forKey: playlistId)
        activePlaylistDownloads[playlistId] = PlaylistDownloadProgress(
            playlistId: playlistId,
            playlistName: detail.summary.name,
            tracksCompleted: 0,
            tracksTotal: playable.count)

        playlistDownloadTasks[playlistId] = Task { [weak self] in
            await self?.performPlaylistDownload(detail: detail, entries: playable)
        }
    }

    public func cancelPlaylistDownload(playlistId: Int64) {
        playlistDownloadTasks[playlistId]?.cancel()
        playlistDownloadTasks.removeValue(forKey: playlistId)
        activePlaylistDownloads.removeValue(forKey: playlistId)
        try? FileManager.default.removeItem(at: playlistDir(playlistId: playlistId))
    }

    public func deletePlaylist(playlistId: Int64) {
        playlistDownloadTasks[playlistId]?.cancel()
        playlistDownloadTasks.removeValue(forKey: playlistId)
        activePlaylistDownloads.removeValue(forKey: playlistId)
        // Removing the directory drops every hard link inside it.
        // Files that were hard-linked from an album (same inode) stay
        // alive on disk via the album's reference; standalone-
        // downloaded tracks (no album link) are freed here.
        try? FileManager.default.removeItem(at: playlistDir(playlistId: playlistId))
        playlistDownloads.removeAll { $0.playlistId == playlistId }
        rebuildTrackIndex()
        persistPlaylistIndex()
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
            artistIds: album.albumArtists.map { $0.id.protoValue },
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

    /// Download every playable track in a playlist. For each track:
    ///
    /// - If the track already has a local file on disk (from an
    ///   album download), hard-link it into the playlist directory.
    ///   Both paths refer to the same inode; deleting one path
    ///   keeps the file alive while any path still references it.
    ///   Saves disk space and bandwidth.
    /// - Otherwise download fresh into the playlist directory.
    ///
    /// The serialized MMPlaylistDetail is written to detail.pb so
    /// PlaylistDetailView can browse it offline.
    private func performPlaylistDownload(detail: ApiPlaylistDetail, entries: [ApiPlaylistTrackEntry]) async {
        guard let apiClient else {
            failedPlaylistDownloads[detail.summary.id] = "Server connection isn't ready yet."
            activePlaylistDownloads.removeValue(forKey: detail.summary.id)
            return
        }
        let playlistId = detail.summary.id
        let dir = playlistDir(playlistId: playlistId)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            failedPlaylistDownloads[playlistId] = "Disk error: \(error.localizedDescription)"
            activePlaylistDownloads.removeValue(forKey: playlistId)
            return
        }

        // Persist playlist detail proto for offline browsing.
        do {
            let data = try detail.proto.serializedData()
            let path = dir.appendingPathComponent("detail.pb")
            try data.write(to: path, options: .atomic)
        } catch {
            logger.error("downloadPlaylist: failed to write detail.pb playlistId=\(playlistId): \(error.localizedDescription)")
        }

        var downloadedTrackIds: [Int64] = []
        var totalBytes: Int64 = 0
        for entry in entries {
            if Task.isCancelled { break }
            let trackId = entry.track.id
            // Dedup path: if the track is already on disk via an
            // album download, hard-link instead of re-fetching.
            if let existing = localTrackURL(trackId: trackId) {
                let ext = existing.pathExtension.isEmpty ? "mp3" : existing.pathExtension
                let dest = dir.appendingPathComponent("\(trackId).\(ext)")
                do {
                    if !FileManager.default.fileExists(atPath: dest.path) {
                        try FileManager.default.linkItem(at: existing, to: dest)
                    }
                    downloadedTrackIds.append(trackId)
                    if let attrs = try? FileManager.default.attributesOfItem(atPath: dest.path),
                       let size = attrs[.size] as? Int64 {
                        totalBytes += size
                    }
                } catch {
                    logger.warning("downloadPlaylist: hardlink failed for track \(trackId): \(error.localizedDescription) — falling through to fresh download")
                    // Fall through to the HTTP download path below
                    // by NOT continuing here. Wrap in a do/catch
                    // ladder if we ever need to differentiate.
                }
                if let progress = activePlaylistDownloads[playlistId] {
                    var p = progress
                    p.tracksCompleted += 1
                    activePlaylistDownloads[playlistId] = p
                }
                continue
            }
            // Fresh download path — same shape as performAlbumDownload.
            do {
                let (data, contentType) = try await apiClient
                    .getRawWithContentType("audio/\(trackId)")
                let ext = Self.extension(forContentType: contentType)
                let url = dir.appendingPathComponent("\(trackId).\(ext)")
                try data.write(to: url, options: .atomic)
                downloadedTrackIds.append(trackId)
                totalBytes += Int64(data.count)
                if let progress = activePlaylistDownloads[playlistId] {
                    var p = progress
                    p.tracksCompleted += 1
                    activePlaylistDownloads[playlistId] = p
                }
            } catch {
                logger.warning("downloadPlaylist: track \(trackId) failed: \(error.localizedDescription)")
                continue
            }
        }

        if Task.isCancelled {
            try? FileManager.default.removeItem(at: dir)
            activePlaylistDownloads.removeValue(forKey: playlistId)
            return
        }
        guard !downloadedTrackIds.isEmpty else {
            failedPlaylistDownloads[playlistId] = "No tracks could be downloaded."
            activePlaylistDownloads.removeValue(forKey: playlistId)
            try? FileManager.default.removeItem(at: dir)
            return
        }

        let entry = DownloadedPlaylist(
            playlistId: playlistId,
            name: detail.summary.name,
            downloadedAt: Date(),
            lastAccessedAt: Date(),
            sizeBytes: totalBytes,
            trackIds: downloadedTrackIds)
        playlistDownloads.removeAll { $0.playlistId == playlistId }
        playlistDownloads.append(entry)
        rebuildTrackIndex()
        persistPlaylistIndex()
        activePlaylistDownloads.removeValue(forKey: playlistId)
        playlistDownloadTasks.removeValue(forKey: playlistId)
    }

    // MARK: - Persistence

    private func albumDir(titleId: Int64) -> URL {
        audioDir.appendingPathComponent("\(titleId)", isDirectory: true)
    }

    /// Playlist directory naming uses a `playlist-` prefix so we
    /// can't collide with album directories (album titleId and
    /// playlistId could share an integer value across the two
    /// namespaces).
    private func playlistDir(playlistId: Int64) -> URL {
        audioDir.appendingPathComponent("playlist-\(playlistId)", isDirectory: true)
    }

    private func rebuildTrackIndex() {
        var albumIdx: [Int64: Int64] = [:]
        for album in downloads {
            for tid in album.trackIds { albumIdx[tid] = album.titleId }
        }
        trackToAlbum = albumIdx

        var playlistIdx: [Int64: [Int64]] = [:]
        for playlist in playlistDownloads {
            for tid in playlist.trackIds {
                playlistIdx[tid, default: []].append(playlist.playlistId)
            }
        }
        trackToPlaylists = playlistIdx
    }

    private func persistIndex() {
        if let data = try? JSONEncoder().encode(downloads) {
            try? data.write(to: indexPath, options: .atomic)
        }
    }

    private func persistPlaylistIndex() {
        if let data = try? JSONEncoder().encode(playlistDownloads) {
            try? data.write(to: playlistIndexPath, options: .atomic)
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

    private static func loadPlaylistIndex(at path: URL) -> [DownloadedPlaylist] {
        guard let data = try? Data(contentsOf: path),
              let entries = try? JSONDecoder().decode([DownloadedPlaylist].self, from: data) else {
            return []
        }
        return entries
    }
}
