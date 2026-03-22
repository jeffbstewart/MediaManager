import Foundation

enum DownloadState: String, Codable {
    case fetchingMetadata
    case downloading
    case paused
    case completed
    case failed
}

struct DownloadItem: Codable, Identifiable {
    var id: Int { transcodeId }
    let transcodeId: Int
    let titleId: Int
    let titleName: String
    let posterUrl: String?
    let quality: String?
    let year: Int?
    var state: DownloadState
    var fileSizeBytes: Int64?
    var bytesDownloaded: Int64
    var hasSubtitles: Bool
    var hasThumbnails: Bool
    var hasChapters: Bool
    var resumeData: Data?
    var errorMessage: String?
    let addedAt: Date

    enum CodingKeys: String, CodingKey {
        case transcodeId = "transcode_id"
        case titleId = "title_id"
        case titleName = "title_name"
        case posterUrl = "poster_url"
        case quality, year, state
        case fileSizeBytes = "file_size_bytes"
        case bytesDownloaded = "bytes_downloaded"
        case hasSubtitles = "has_subtitles"
        case hasThumbnails = "has_thumbnails"
        case hasChapters = "has_chapters"
        case resumeData = "resume_data"
        case errorMessage = "error_message"
        case addedAt = "added_at"
    }

    var progress: Double {
        guard let total = fileSizeBytes, total > 0 else { return 0 }
        return Double(bytesDownloaded) / Double(total)
    }

    var localDir: URL {
        DownloadItem.transcodesRoot.appendingPathComponent("\(transcodeId)")
    }

    var videoFileURL: URL {
        localDir.appendingPathComponent("video.mp4")
    }

    static var downloadsRoot: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Downloads")
    }

    static var transcodesRoot: URL {
        downloadsRoot.appendingPathComponent("transcodes")
    }

    static var titlesRoot: URL {
        downloadsRoot.appendingPathComponent("titles")
    }

    static func titleCacheDir(for titleId: Int) -> URL {
        titlesRoot.appendingPathComponent("\(titleId)")
    }
}

// MARK: - Title Cache

/// Tracks when a title's offline cache was last refreshed.
struct TitleCacheEntry: Codable {
    let titleId: Int
    var lastUpdated: Date

    enum CodingKeys: String, CodingKey {
        case titleId = "title_id"
        case lastUpdated = "last_updated"
    }
}

// MARK: - Pending Progress Updates

/// Queued progress report for syncing to server when connectivity returns.
struct PendingProgressUpdate: Codable {
    let transcodeId: Int
    let position: Double
    let duration: Double?
    let timestamp: Date

    enum CodingKeys: String, CodingKey {
        case transcodeId = "transcode_id"
        case position, duration, timestamp
    }
}

// MARK: - API Response Structs

struct ApiDownloadAvailable: Codable, Identifiable {
    var id: Int { transcodeId }
    let transcodeId: Int
    let titleId: Int
    let titleName: String
    let posterUrl: String?
    let quality: String?
    let year: Int?
    let fileSizeBytes: Int64
    let mediaType: String

    enum CodingKeys: String, CodingKey {
        case quality, year
        case transcodeId = "transcode_id"
        case titleId = "title_id"
        case titleName = "title_name"
        case posterUrl = "poster_url"
        case fileSizeBytes = "file_size_bytes"
        case mediaType = "media_type"
    }
}

struct ApiDownloadManifest: Codable {
    let transcodeId: Int
    let titleId: Int
    let titleName: String
    let fileSizeBytes: Int64
    let hasSubtitles: Bool
    let hasThumbnails: Bool
    let hasChapters: Bool

    enum CodingKeys: String, CodingKey {
        case transcodeId = "transcode_id"
        case titleId = "title_id"
        case titleName = "title_name"
        case fileSizeBytes = "file_size_bytes"
        case hasSubtitles = "has_subtitles"
        case hasThumbnails = "has_thumbnails"
        case hasChapters = "has_chapters"
    }
}
