import Foundation

struct TranscodeStatusResponse: Codable {
    let pending: PendingWork
    let activeLeases: [TranscodeLease]

    enum CodingKeys: String, CodingKey {
        case pending
        case activeLeases = "active_leases"
    }
}

struct PendingWork: Codable {
    let transcodes: Int
    let mobileTranscodes: Int?
    let thumbnails: Int
    let subtitles: Int
    let chapters: Int
    let total: Int

    enum CodingKeys: String, CodingKey {
        case transcodes
        case mobileTranscodes = "mobile_transcodes"
        case thumbnails, subtitles, chapters, total
    }
}

struct TranscodeLease: Codable, Identifiable {
    var id: Int { leaseId }
    let leaseId: Int
    let buddyName: String?
    let relativePath: String?
    let leaseType: String?
    let status: String?
    let progressPercent: Int?
    let encoder: String?
    let claimedAt: String?

    enum CodingKeys: String, CodingKey {
        case leaseId = "lease_id"
        case buddyName = "buddy_name"
        case relativePath = "relative_path"
        case leaseType = "lease_type"
        case status
        case progressPercent = "progress_percent"
        case encoder
        case claimedAt = "claimed_at"
    }
}

struct BuddyStatusResponse: Codable {
    let buddies: [BuddyInfo]
    let recentLeases: [RecentLease]

    enum CodingKeys: String, CodingKey {
        case buddies
        case recentLeases = "recent_leases"
    }
}

struct BuddyInfo: Codable, Identifiable {
    var id: String { name ?? "unknown" }
    let name: String?
    let activeLeases: Int
    let currentWork: [BuddyWork]

    enum CodingKeys: String, CodingKey {
        case name
        case activeLeases = "active_leases"
        case currentWork = "current_work"
    }
}

struct BuddyWork: Codable, Identifiable {
    var id: Int { leaseId }
    let leaseId: Int
    let relativePath: String?
    let leaseType: String?
    let progressPercent: Int?
    let encoder: String?

    enum CodingKeys: String, CodingKey {
        case leaseId = "lease_id"
        case relativePath = "relative_path"
        case leaseType = "lease_type"
        case progressPercent = "progress_percent"
        case encoder
    }
}

struct RecentLease: Codable, Identifiable {
    var id: Int { leaseId }
    let leaseId: Int
    let buddyName: String?
    let relativePath: String?
    let leaseType: String?
    let status: String?
    let encoder: String?
    let completedAt: String?
    let errorMessage: String?

    enum CodingKeys: String, CodingKey {
        case leaseId = "lease_id"
        case buddyName = "buddy_name"
        case relativePath = "relative_path"
        case leaseType = "lease_type"
        case status, encoder
        case completedAt = "completed_at"
        case errorMessage = "error_message"
    }
}

// MARK: - User Management

struct AdminUser: Codable, Identifiable {
    let id: Int
    let username: String
    let displayName: String?
    let accessLevel: Int
    let isAdmin: Bool
    let locked: Bool
    let mustChangePassword: Bool
    let ratingCeiling: Int?
    let ratingCeilingLabel: String?
    let createdAt: String?

    enum CodingKeys: String, CodingKey {
        case id, username, locked
        case displayName = "display_name"
        case accessLevel = "access_level"
        case isAdmin = "is_admin"
        case mustChangePassword = "must_change_password"
        case ratingCeiling = "rating_ceiling"
        case ratingCeilingLabel = "rating_ceiling_label"
        case createdAt = "created_at"
    }
}

struct AdminUserListResponse: Codable {
    let users: [AdminUser]
}

// MARK: - Purchase Wishes

struct AdminPurchaseWish: Codable, Identifiable {
    var id: String { "\(tmdbId)-\(mediaType)-\(seasonNumber ?? -1)" }
    let tmdbId: Int
    let mediaType: String
    let title: String
    let posterUrl: String?
    let releaseYear: Int?
    let seasonNumber: Int?
    let voteCount: Int
    let voters: [String]
    let acquisitionStatus: String?

    enum CodingKeys: String, CodingKey {
        case title, voters
        case tmdbId = "tmdb_id"
        case mediaType = "media_type"
        case posterUrl = "poster_url"
        case releaseYear = "release_year"
        case seasonNumber = "season_number"
        case voteCount = "vote_count"
        case acquisitionStatus = "acquisition_status"
    }
}

struct AdminPurchaseWishListResponse: Codable {
    let wishes: [AdminPurchaseWish]
}

// MARK: - Data Quality

struct AdminDataQualityTitle: Codable, Identifiable {
    let id: Int
    let name: String
    let mediaType: String?
    let enrichmentStatus: String?
    let tmdbId: Int?
    let releaseYear: Int?
    let contentRating: String?
    let posterUrl: String?
    let hidden: Bool
    let createdAt: String?

    enum CodingKeys: String, CodingKey {
        case id, name, hidden
        case mediaType = "media_type"
        case enrichmentStatus = "enrichment_status"
        case tmdbId = "tmdb_id"
        case releaseYear = "release_year"
        case contentRating = "content_rating"
        case posterUrl = "poster_url"
        case createdAt = "created_at"
    }
}

struct AdminDataQualityResponse: Codable {
    let titles: [AdminDataQualityTitle]
    let total: Int
    let page: Int
    let limit: Int
    let totalPages: Int

    enum CodingKeys: String, CodingKey {
        case titles, total, page, limit
        case totalPages = "total_pages"
    }
}

// MARK: - Settings

struct AdminSettingsResponse: Codable {
    let settings: [String: String?]
    let buddyKeys: [AdminBuddyKey]

    enum CodingKeys: String, CodingKey {
        case settings
        case buddyKeys = "buddy_keys"
    }
}

struct AdminBuddyKey: Codable, Identifiable {
    let id: Int
    let name: String
    let createdAt: String?

    enum CodingKeys: String, CodingKey {
        case id, name
        case createdAt = "created_at"
    }
}

// MARK: - Linked Transcodes

struct AdminLinkedTranscode: Codable, Identifiable {
    var id: Int { transcodeId }
    let transcodeId: Int
    let titleId: Int
    let titleName: String
    let mediaType: String?
    let posterUrl: String?
    let filePath: String?
    let mediaFormat: String?
    let seasonNumber: Int?
    let episodeNumber: Int?
    let episodeName: String?
    let retranscodeRequested: Bool?

    enum CodingKeys: String, CodingKey {
        case transcodeId = "transcode_id"
        case titleId = "title_id"
        case titleName = "title_name"
        case mediaType = "media_type"
        case posterUrl = "poster_url"
        case filePath = "file_path"
        case mediaFormat = "media_format"
        case seasonNumber = "season_number"
        case episodeNumber = "episode_number"
        case episodeName = "episode_name"
        case retranscodeRequested = "retranscode_requested"
    }
}

struct AdminLinkedTranscodeResponse: Codable {
    let transcodes: [AdminLinkedTranscode]
    let total: Int
    let page: Int
    let limit: Int
    let totalPages: Int

    enum CodingKeys: String, CodingKey {
        case transcodes, total, page, limit
        case totalPages = "total_pages"
    }
}

// MARK: - Unmatched Files

struct AdminUnmatchedFile: Codable, Identifiable {
    let id: Int
    let fileName: String
    let directory: String?
    let mediaType: String?
    let parsedTitle: String?
    let parsedYear: Int?
    let parsedSeason: Int?
    let parsedEpisode: Int?
    let suggestions: [AdminMatchSuggestion]

    enum CodingKeys: String, CodingKey {
        case id, directory, suggestions
        case fileName = "file_name"
        case mediaType = "media_type"
        case parsedTitle = "parsed_title"
        case parsedYear = "parsed_year"
        case parsedSeason = "parsed_season"
        case parsedEpisode = "parsed_episode"
    }
}

struct AdminMatchSuggestion: Codable, Identifiable {
    var id: Int { titleId }
    let titleId: Int
    let titleName: String
    let score: Double

    enum CodingKeys: String, CodingKey {
        case score
        case titleId = "title_id"
        case titleName = "title_name"
    }
}

struct AdminUnmatchedResponse: Codable {
    let unmatched: [AdminUnmatchedFile]
    let total: Int
}
