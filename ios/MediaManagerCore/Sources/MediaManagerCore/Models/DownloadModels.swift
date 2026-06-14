import Foundation
import MediaManagerProtos

// Download state and metadata are now defined in proto/download_meta.proto
// (MMDownloadDatabase, MMDownloadEntry, MMDownloadState, MMDownloadQuality).
// This file retains only the view-layer helpers and adapters.

public extension MMQuality {
    public var downloadQuality: MMDownloadQuality {
        switch self {
        case .sd: .sd
        case .fhd: .fhd
        case .uhd: .uhd
        default: .unknown
        }
    }
}

extension MMDownloadEntry: Identifiable {
    public var id: Int64 { transcodeID }

    public var progress: Double {
        guard fileSizeBytes > 0 else { return 0 }
        return Double(bytesDownloaded) / Double(fileSizeBytes)
    }

    public var qualityLabel: String {
        switch quality {
        case .sd: "SD"
        case .fhd: "FHD"
        case .uhd: "UHD"
        default: ""
        }
    }

    public var isActive: Bool {
        state == .fetchingMetadata || state == .downloading
    }
}

// MARK: - Download Item Adapter (for DownloadsView compatibility)

/// Lightweight wrapper used by DownloadsView where it previously expected DownloadItem.
public struct DownloadItem: Identifiable {
    public let entry: MMDownloadEntry

    public init(entry: MMDownloadEntry) { self.entry = entry }

    public var id: Int64 { entry.transcodeID }
    public var transcodeId: TranscodeID { TranscodeID(rawValue: Int(entry.transcodeID)) }
    public var titleId: TitleID { TitleID(rawValue: Int(entry.titleID)) }
    public var titleName: String { entry.titleName }
    public var quality: String? { entry.qualityLabel.isEmpty ? nil : entry.qualityLabel }
    public var year: Int? { entry.year > 0 ? Int(entry.year) : nil }
    public var state: MMDownloadState { entry.state }
    public var fileSizeBytes: Int64? { entry.fileSizeBytes > 0 ? entry.fileSizeBytes : nil }
    public var bytesDownloaded: Int64 { entry.bytesDownloaded }
    public var progress: Double { entry.progress }
    public var hasSubtitles: Bool { entry.hasSubtitles_p }
    public var errorMessage: String? { entry.errorMessage.isEmpty ? nil : entry.errorMessage }
    public var addedAt: Date { Date(timeIntervalSince1970: TimeInterval(entry.addedAt.secondsSinceEpoch)) }
    public var mediaType: MediaType { entry.mediaType == .tv ? .tv : .movie }
    public var seasonNumber: Int? { entry.seasonNumber > 0 ? Int(entry.seasonNumber) : nil }
    public var episodeNumber: Int? { entry.episodeNumber > 0 ? Int(entry.episodeNumber) : nil }
    public var episodeTitle: String? { entry.episodeTitle.isEmpty ? nil : entry.episodeTitle }
}
