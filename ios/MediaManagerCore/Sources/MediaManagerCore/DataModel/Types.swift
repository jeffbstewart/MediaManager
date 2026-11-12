import Foundation

// MARK: - Type-Safe ID Wrappers
// Each wraps an Int with single-value coding for JSON compatibility (decodes from bare `42`, not `{"rawValue":42}`).
// No ExpressibleByIntegerLiteral — you must explicitly construct e.g. TitleID(rawValue: 42).

public struct TitleID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct TranscodeID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct EpisodeID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct TmdbPersonID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct TmdbCollectionID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct TagID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct GenreID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct WishID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct UserID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct SessionID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct CameraID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct ChannelID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct LeaseID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct UnmatchedFileID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct BuddyKeyID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct AuthorID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct BookSeriesID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

public struct TmdbID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

// MARK: - Proto Convenience Inits
// Explicitly-labeled initializers for converting proto Int64 IDs to type-safe wrappers.
// The `proto:` label prevents accidental implicit conversion.

extension TitleID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension TranscodeID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension EpisodeID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension TmdbPersonID {
    public init(proto value: Int32) { self.init(rawValue: Int(value)) }
    public var protoValue: Int32 { Int32(rawValue) }
}
extension TmdbCollectionID {
    public init(proto value: Int32) { self.init(rawValue: Int(value)) }
    public var protoValue: Int32 { Int32(rawValue) }
}
extension TagID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension GenreID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension WishID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension UserID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension SessionID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension CameraID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension ChannelID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension TmdbID {
    public init(proto value: Int32) { self.init(rawValue: Int(value)) }
    public var protoValue: Int32 { Int32(rawValue) }
}
extension LeaseID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension UnmatchedFileID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension BuddyKeyID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension AuthorID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}

public struct ArtistID: Hashable, Codable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public init(from decoder: Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(Int.self)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}
extension ArtistID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}
extension BookSeriesID {
    public init(proto value: Int64) { self.init(rawValue: Int(value)) }
    public var protoValue: Int64 { Int64(rawValue) }
}

// MARK: - Enums replacing string constants

public enum MediaType: String, Codable, Sendable, CaseIterable {
    case movie = "MOVIE"
    case tv = "TV"
    case personal = "PERSONAL"
}

public enum SearchResultType: String, Codable, Sendable {
    case movie, series, actor, collection, tag, genre
}

/// Author-grid sort, translated to `MMAuthorSort` at the gRPC boundary.
public enum AuthorSort: String, Codable, Sendable, CaseIterable, Identifiable {
    case name, books, recent

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .name: return "Name"
        case .books: return "Most books"
        case .recent: return "Recent"
        }
    }

    public var protoValue: MMAuthorSort {
        switch self {
        case .name: return .name
        case .books: return .books
        case .recent: return .recent
        }
    }
}

public enum AcquisitionStatus: String, Codable, Sendable {
    case unknown = "UNKNOWN"
    case notAvailable = "NOT_AVAILABLE"
    case rejected = "REJECTED"
    case ordered = "ORDERED"
    case owned = "OWNED"
    case needsAssistance = "NEEDS_ASSISTANCE"

    public var displayLabel: String {
        switch self {
        case .unknown: "Wished for"
        case .notAvailable: "Not feasible"
        case .rejected: "Won't order"
        case .ordered: "Ordered"
        case .owned: "Owned"
        case .needsAssistance: "Needs assistance"
        }
    }
}

public enum WishLifecycleStage: String, Codable, Sendable {
    case wishedFor = "WISHED_FOR"
    case notFeasible = "NOT_FEASIBLE"
    case wontOrder = "WONT_ORDER"
    case needsAssistance = "NEEDS_ASSISTANCE"
    case ordered = "ORDERED"
    case inHousePendingNas = "IN_HOUSE_PENDING_NAS"
    case onNasPendingDesktop = "ON_NAS_PENDING_DESKTOP"
    case readyToWatch = "READY_TO_WATCH"

    public var displayLabel: String {
        switch self {
        case .wishedFor: "Wished for"
        case .notFeasible: "Not feasible"
        case .wontOrder: "Won't order"
        case .needsAssistance: "Needs assistance"
        case .ordered: "Ordered"
        case .inHousePendingNas: "In house, pending NAS"
        case .onNasPendingDesktop: "On NAS, pending desktop"
        case .readyToWatch: "Ready to watch"
        }
    }
}

public enum EnrichmentStatus: String, Codable, Sendable {
    case enriched = "ENRICHED"
    case pending = "PENDING"
    case failed = "FAILED"
    case notFound = "NOT_FOUND"
}

public enum WishStatus: String, Codable, Sendable {
    case fulfilled
    case pending
}

public enum LeaseStatus: String, Codable, Sendable {
    case completed = "COMPLETED"
    case failed = "FAILED"
    case active = "ACTIVE"
}

public enum SessionType: String, Codable, Sendable {
    case access, refresh
}

// MARK: - Transcode model object

public struct Transcode: Identifiable, Hashable {
    public let id: TranscodeID
}
