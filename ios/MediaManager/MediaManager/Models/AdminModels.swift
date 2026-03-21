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
