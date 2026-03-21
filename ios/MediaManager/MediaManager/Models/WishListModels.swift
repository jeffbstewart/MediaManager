import Foundation

struct ApiWish: Codable, Identifiable {
    var id: String { "\(tmdbId ?? 0)-\(mediaType ?? "")-\(seasonNumber ?? 0)-\(status ?? "")" }
    let tmdbId: Int?
    let mediaType: String?
    let title: String
    let posterUrl: String?
    let releaseYear: Int?
    let seasonNumber: Int?
    let voteCount: Int
    let voters: [String]
    let voted: Bool
    let wishId: Int?
    let acquisitionStatus: String?
    let status: String?
    let titleId: Int?

    var isFulfilled: Bool { status == "fulfilled" }

    enum CodingKeys: String, CodingKey {
        case title, voted, voters, status
        case tmdbId = "tmdb_id"
        case mediaType = "media_type"
        case posterUrl = "poster_url"
        case releaseYear = "release_year"
        case seasonNumber = "season_number"
        case voteCount = "vote_count"
        case wishId = "wish_id"
        case acquisitionStatus = "acquisition_status"
        case titleId = "title_id"
    }
}

struct ApiWishListResponse: Codable {
    let wishes: [ApiWish]
}

struct ApiTranscodeWish: Codable, Identifiable {
    let id: Int
    let titleId: Int
    let titleName: String
    let posterUrl: String?
    let mediaType: String?
    let requestedAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case titleId = "title_id"
        case titleName = "title_name"
        case posterUrl = "poster_url"
        case mediaType = "media_type"
        case requestedAt = "requested_at"
    }
}

struct ApiTranscodeWishListResponse: Codable {
    let transcodeWishes: [ApiTranscodeWish]

    enum CodingKeys: String, CodingKey {
        case transcodeWishes = "transcode_wishes"
    }
}

struct TmdbSearchItem: Codable, Identifiable {
    var id: String { "\(tmdbId ?? 0)-\(mediaType ?? "")" }
    let tmdbId: Int?
    let title: String?
    let mediaType: String?
    let releaseYear: Int?
    let posterUrl: String?
    let posterPath: String?
    let popularity: Double?
    let overview: String?

    enum CodingKeys: String, CodingKey {
        case title, popularity, overview
        case tmdbId = "tmdb_id"
        case mediaType = "media_type"
        case releaseYear = "release_year"
        case posterUrl = "poster_url"
        case posterPath = "poster_path"
    }
}

struct TmdbSearchResponse: Codable {
    let results: [TmdbSearchItem]
}
