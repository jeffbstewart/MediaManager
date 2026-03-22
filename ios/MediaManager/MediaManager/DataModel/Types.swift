import Foundation

// MARK: - Type-Safe ID Wrappers
// Each wraps an Int with single-value coding for JSON compatibility (decodes from bare `42`, not `{"rawValue":42}`).
// No ExpressibleByIntegerLiteral — you must explicitly construct e.g. TitleID(rawValue: 42).

struct TitleID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct TranscodeID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct EpisodeID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct TmdbPersonID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct TmdbCollectionID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct TagID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct GenreID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct WishID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct UserID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct SessionID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct CameraID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct ChannelID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct LeaseID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct UnmatchedFileID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct BuddyKeyID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

struct TmdbID: Hashable, Codable, Sendable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }
    init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

// MARK: - Enums replacing string constants

enum MediaType: String, Codable, Sendable, CaseIterable {
    case movie = "MOVIE"
    case tv = "TV"
    case personal = "PERSONAL"
}

enum SearchResultType: String, Codable, Sendable {
    case movie, series, actor, collection, tag, genre
}

enum AcquisitionStatus: String, Codable, Sendable {
    case none = "NONE"
    case ordered = "ORDERED"
    case shipped = "SHIPPED"
    case arrived = "ARRIVED"
    case ripped = "RIPPED"
    case returned = "RETURNED"
}

enum EnrichmentStatus: String, Codable, Sendable {
    case enriched = "ENRICHED"
    case pending = "PENDING"
    case failed = "FAILED"
    case notFound = "NOT_FOUND"
}

enum WishStatus: String, Codable, Sendable {
    case fulfilled
    case pending
}

enum LeaseStatus: String, Codable, Sendable {
    case completed = "COMPLETED"
    case failed = "FAILED"
    case active = "ACTIVE"
}

enum SessionType: String, Codable, Sendable {
    case access, refresh
}

// MARK: - Transcode model object

struct Transcode: Identifiable, Hashable {
    let id: TranscodeID
}
