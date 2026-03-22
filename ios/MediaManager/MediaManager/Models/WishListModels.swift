import Foundation

struct ApiWish: Codable, Identifiable {
    var id: String { "\(tmdbId?.rawValue ?? 0)-\(mediaType?.rawValue ?? "")-\(seasonNumber ?? 0)-\(status ?? "")" }
    let tmdbId: TmdbID?
    let mediaType: MediaType?
    let title: String
    let posterUrl: String?
    let releaseYear: Int?
    let seasonNumber: Int?
    let voteCount: Int
    let voters: [String]
    let voted: Bool
    let wishId: WishID?
    let acquisitionStatus: String?
    let status: String?
    let titleId: TitleID?

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
    let id: TranscodeID
    let titleId: TitleID
    let titleName: String
    let posterUrl: String?
    let mediaType: MediaType?
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
    var id: String { "\(tmdbId?.rawValue ?? 0)-\(mediaType?.rawValue ?? "")" }
    let tmdbId: TmdbID?
    let title: String?
    let mediaType: MediaType?
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
