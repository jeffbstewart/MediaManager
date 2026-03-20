import Foundation

struct ApiTitle: Codable, Identifiable, Hashable {
    let id: Int
    let name: String
    let mediaType: String
    let year: Int?
    let description: String?
    let posterUrl: String?
    let backdropUrl: String?
    let contentRating: String?
    let popularity: Double?
    let quality: String?
    let playable: Bool
    let transcodeId: Int?
    let tmdbId: Int?
    let tmdbCollectionId: Int?
    let tmdbCollectionName: String?

    enum CodingKeys: String, CodingKey {
        case id, name, year, description, popularity, quality, playable
        case mediaType = "media_type"
        case posterUrl = "poster_url"
        case backdropUrl = "backdrop_url"
        case contentRating = "content_rating"
        case transcodeId = "transcode_id"
        case tmdbId = "tmdb_id"
        case tmdbCollectionId = "tmdb_collection_id"
        case tmdbCollectionName = "tmdb_collection_name"
    }
}

struct ApiCarousel: Codable {
    let name: String
    let items: [ApiTitle]
}

struct ApiHomeFeed: Codable {
    let carousels: [ApiCarousel]
}

struct ApiTitlePage: Codable {
    let titles: [ApiTitle]
    let total: Int
    let page: Int
    let limit: Int
    let totalPages: Int

    enum CodingKeys: String, CodingKey {
        case titles, total, page, limit
        case totalPages = "total_pages"
    }
}

struct ApiCastMember: Codable, Identifiable {
    var id: Int { tmdbPersonId }
    let tmdbPersonId: Int
    let name: String
    let characterName: String?
    let headshotUrl: String?
    let order: Int

    enum CodingKeys: String, CodingKey {
        case name, order
        case tmdbPersonId = "tmdb_person_id"
        case characterName = "character_name"
        case headshotUrl = "headshot_url"
    }
}

struct ApiGenre: Codable, Identifiable {
    let id: Int
    let name: String
}

struct ApiTag: Codable, Identifiable {
    let id: Int
    let name: String
    let color: String
}

struct ApiTranscode: Codable, Identifiable {
    let id: Int
    let mediaFormat: String?
    let quality: String
    let episodeId: Int?
    let seasonNumber: Int?
    let episodeNumber: Int?
    let episodeName: String?
    let playable: Bool
    let hasSubtitles: Bool

    enum CodingKeys: String, CodingKey {
        case id, quality, playable
        case mediaFormat = "media_format"
        case episodeId = "episode_id"
        case seasonNumber = "season_number"
        case episodeNumber = "episode_number"
        case episodeName = "episode_name"
        case hasSubtitles = "has_subtitles"
    }
}

struct ApiPlaybackProgress: Codable {
    let transcodeId: Int
    let positionSeconds: Double
    let durationSeconds: Double?
    let updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case transcodeId = "transcode_id"
        case positionSeconds = "position_seconds"
        case durationSeconds = "duration_seconds"
        case updatedAt = "updated_at"
    }
}

struct ApiTitleDetail: Codable {
    let id: Int
    let name: String
    let mediaType: String
    let year: Int?
    let description: String?
    let posterUrl: String?
    let backdropUrl: String?
    let contentRating: String?
    let popularity: Double?
    let quality: String?
    let playable: Bool
    let transcodeId: Int?
    let tmdbId: Int?
    let tmdbCollectionId: Int?
    let tmdbCollectionName: String?
    let cast: [ApiCastMember]
    let genres: [ApiGenre]
    let tags: [ApiTag]
    let transcodes: [ApiTranscode]
    let playbackProgress: ApiPlaybackProgress?

    enum CodingKeys: String, CodingKey {
        case id, name, year, description, popularity, quality, playable, cast, genres, tags, transcodes
        case mediaType = "media_type"
        case posterUrl = "poster_url"
        case backdropUrl = "backdrop_url"
        case contentRating = "content_rating"
        case transcodeId = "transcode_id"
        case tmdbId = "tmdb_id"
        case tmdbCollectionId = "tmdb_collection_id"
        case tmdbCollectionName = "tmdb_collection_name"
        case playbackProgress = "playback_progress"
    }
}

struct ApiSearchResult: Codable, Identifiable {
    var id: String { "\(resultType)-\(name)-\(titleId ?? 0)" }
    let resultType: String
    let name: String
    let titleId: Int?
    let posterUrl: String?
    let year: Int?
    let quality: String?
    let contentRating: String?
    let transcodeId: Int?
    let mediaType: String?
    let tmdbCollectionId: Int?
    let tmdbPersonId: Int?
    let headshotUrl: String?
    let titleCount: Int?

    enum CodingKeys: String, CodingKey {
        case name, year, quality
        case resultType = "result_type"
        case titleId = "title_id"
        case posterUrl = "poster_url"
        case contentRating = "content_rating"
        case transcodeId = "transcode_id"
        case mediaType = "media_type"
        case tmdbCollectionId = "tmdb_collection_id"
        case tmdbPersonId = "tmdb_person_id"
        case headshotUrl = "headshot_url"
        case titleCount = "title_count"
    }
}

struct ApiSearchResponse: Codable {
    let query: String
    let results: [ApiSearchResult]
    let counts: [String: Int]
}

// --- Phase 3: TV Shows ---

struct ApiSeason: Codable, Hashable {
    let seasonNumber: Int
    let name: String?
    let episodeCount: Int

    enum CodingKeys: String, CodingKey {
        case seasonNumber = "season_number"
        case name
        case episodeCount = "episode_count"
    }
}

struct ApiEpisode: Codable {
    let episodeId: Int
    let transcodeId: Int?
    let seasonNumber: Int
    let episodeNumber: Int
    let name: String?
    let quality: String?
    let playable: Bool
    let hasSubtitles: Bool
    let resumePosition: Double
    let watchedPercent: Int

    enum CodingKeys: String, CodingKey {
        case name, quality, playable
        case episodeId = "episode_id"
        case transcodeId = "transcode_id"
        case seasonNumber = "season_number"
        case episodeNumber = "episode_number"
        case hasSubtitles = "has_subtitles"
        case resumePosition = "resume_position"
        case watchedPercent = "watched_percent"
    }
}
