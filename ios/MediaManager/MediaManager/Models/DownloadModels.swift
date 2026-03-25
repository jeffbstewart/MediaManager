import Foundation

// Download state and metadata are now defined in proto/download_meta.proto
// (MMDownloadDatabase, MMDownloadEntry, MMDownloadState, MMDownloadQuality).
// This file retains only the view-layer helpers and adapters.

extension MMDownloadEntry: Identifiable {
    public var id: Int64 { transcodeID }

    var progress: Double {
        guard fileSizeBytes > 0 else { return 0 }
        return Double(bytesDownloaded) / Double(fileSizeBytes)
    }

    var qualityLabel: String {
        switch quality {
        case .sd: "SD"
        case .fhd: "FHD"
        case .uhd: "UHD"
        default: ""
        }
    }

    var isActive: Bool {
        state == .fetchingMetadata || state == .downloading
    }
}

// MARK: - Download Item Adapter (for DownloadsView compatibility)

/// Lightweight wrapper used by DownloadsView where it previously expected DownloadItem.
struct DownloadItem: Identifiable {
    let entry: MMDownloadEntry

    var id: Int64 { entry.transcodeID }
    var transcodeId: TranscodeID { TranscodeID(rawValue: Int(entry.transcodeID)) }
    var titleId: TitleID { TitleID(rawValue: Int(entry.titleID)) }
    var titleName: String { entry.titleName }
    var posterUrl: String? { nil } // posters now via ImageProvider
    var quality: String? { entry.qualityLabel.isEmpty ? nil : entry.qualityLabel }
    var year: Int? { entry.year > 0 ? Int(entry.year) : nil }
    var state: MMDownloadState { entry.state }
    var fileSizeBytes: Int64? { entry.fileSizeBytes > 0 ? entry.fileSizeBytes : nil }
    var bytesDownloaded: Int64 { entry.bytesDownloaded }
    var progress: Double { entry.progress }
    var hasSubtitles: Bool { entry.hasSubtitles_p }
    var errorMessage: String? { entry.errorMessage.isEmpty ? nil : entry.errorMessage }
    var addedAt: Date { Date(timeIntervalSince1970: TimeInterval(entry.addedAt.secondsSinceEpoch)) }
    var mediaType: MediaType { entry.mediaType == .tv ? .tv : .movie }
    var seasonNumber: Int? { entry.seasonNumber > 0 ? Int(entry.seasonNumber) : nil }
    var episodeNumber: Int? { entry.episodeNumber > 0 ? Int(entry.episodeNumber) : nil }
    var episodeTitle: String? { entry.episodeTitle.isEmpty ? nil : entry.episodeTitle }
}
