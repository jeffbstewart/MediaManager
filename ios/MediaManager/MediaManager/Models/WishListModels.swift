import Foundation

struct ApiWish: Codable, Identifiable {
    var id: String { "\(tmdbId ?? 0)-\(mediaType ?? "")-\(seasonNumber ?? 0)" }
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

    enum CodingKeys: String, CodingKey {
        case title, voted, voters
        case tmdbId = "tmdb_id"
        case mediaType = "media_type"
        case posterUrl = "poster_url"
        case releaseYear = "release_year"
        case seasonNumber = "season_number"
        case voteCount = "vote_count"
        case wishId = "wish_id"
        case acquisitionStatus = "acquisition_status"
    }
}

struct ApiWishListResponse: Codable {
    let wishes: [ApiWish]
}
