import Foundation

struct ApiTitle: Codable, Identifiable, Hashable {
    let id: TitleID
    let name: String
    let mediaType: MediaType
    let year: Int?
    let description: String?
    let posterUrl: String?
    let backdropUrl: String?
    let contentRating: String?
    let popularity: Double?
    let quality: String?
    let playable: Bool
    let transcodeId: TranscodeID?
    let tmdbId: TmdbID?
    let tmdbCollectionId: TmdbCollectionID?
    let tmdbCollectionName: String?
    let familyMembers: [String]?
    let forMobileAvailable: Bool?

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
        case familyMembers = "family_members"
        case forMobileAvailable = "for_mobile_available"
    }
}

struct ApiCarousel: Codable {
    let name: String
    let items: [ApiTitle]
}

struct ApiHomeFeed: Codable {
    let carousels: [ApiCarousel]
    let missingSeasons: [ApiMissingSeason]?

    enum CodingKeys: String, CodingKey {
        case carousels
        case missingSeasons = "missing_seasons"
    }
}

struct ApiMissingSeason: Codable, Identifiable {
    var id: TitleID { titleId }
    let titleId: TitleID
    let titleName: String
    let posterUrl: String?
    let tmdbId: TmdbID?
    let mediaType: MediaType?
    let seasons: [ApiMissingSeasonEntry]

    enum CodingKeys: String, CodingKey {
        case seasons
        case titleId = "title_id"
        case titleName = "title_name"
        case posterUrl = "poster_url"
        case tmdbId = "tmdb_id"
        case mediaType = "media_type"
    }
}

struct ApiMissingSeasonEntry: Codable, Identifiable {
    var id: Int { seasonNumber }
    let seasonNumber: Int
    let name: String?
    let episodeCount: Int?

    enum CodingKeys: String, CodingKey {
        case name
        case seasonNumber = "season_number"
        case episodeCount = "episode_count"
    }
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
    var id: TmdbPersonID { tmdbPersonId }
    let tmdbPersonId: TmdbPersonID
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
    let id: GenreID
    let name: String
}

struct ApiTag: Codable, Identifiable {
    let id: TagID
    let name: String
    let color: String
}

struct ApiTranscode: Codable, Identifiable {
    let id: TranscodeID
    let mediaFormat: String?
    let quality: String
    let episodeId: EpisodeID?
    let seasonNumber: Int?
    let episodeNumber: Int?
    let episodeName: String?
    let playable: Bool
    let hasSubtitles: Bool
    let forMobileAvailable: Bool?
    let forMobileRequested: Bool?

    enum CodingKeys: String, CodingKey {
        case id, quality, playable
        case mediaFormat = "media_format"
        case episodeId = "episode_id"
        case seasonNumber = "season_number"
        case episodeNumber = "episode_number"
        case episodeName = "episode_name"
        case hasSubtitles = "has_subtitles"
        case forMobileAvailable = "for_mobile_available"
        case forMobileRequested = "for_mobile_requested"
    }
}

struct ApiPlaybackProgress: Codable {
    let transcodeId: TranscodeID
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
    let id: TitleID
    let name: String
    let mediaType: MediaType
    let year: Int?
    let description: String?
    let posterUrl: String?
    let backdropUrl: String?
    let contentRating: String?
    let popularity: Double?
    let quality: String?
    let playable: Bool
    let transcodeId: TranscodeID?
    let tmdbId: TmdbID?
    let tmdbCollectionId: TmdbCollectionID?
    let tmdbCollectionName: String?
    let cast: [ApiCastMember]
    let genres: [ApiGenre]
    let tags: [ApiTag]
    let transcodes: [ApiTranscode]
    let playbackProgress: ApiPlaybackProgress?
    let familyMembers: [String]?
    let isFavorite: Bool?
    let isHidden: Bool?
    let forMobileAvailable: Bool?
    let wished: Bool?

    enum CodingKeys: String, CodingKey {
        case id, name, year, description, popularity, quality, playable, cast, genres, tags, transcodes, wished
        case mediaType = "media_type"
        case posterUrl = "poster_url"
        case backdropUrl = "backdrop_url"
        case contentRating = "content_rating"
        case transcodeId = "transcode_id"
        case tmdbId = "tmdb_id"
        case tmdbCollectionId = "tmdb_collection_id"
        case tmdbCollectionName = "tmdb_collection_name"
        case playbackProgress = "playback_progress"
        case familyMembers = "family_members"
        case isFavorite = "is_favorite"
        case isHidden = "is_hidden"
        case forMobileAvailable = "for_mobile_available"
    }
}

struct ApiSearchResult: Codable, Identifiable {
    var id: String { "\(resultType)-\(name)-\(titleId?.rawValue ?? 0)-\(itemId ?? 0)" }
    let resultType: String
    let name: String
    let titleId: TitleID?
    let posterUrl: String?
    let year: Int?
    let quality: String?
    let contentRating: String?
    let transcodeId: TranscodeID?
    let mediaType: MediaType?
    let tmdbCollectionId: TmdbCollectionID?
    let tmdbPersonId: TmdbPersonID?
    let headshotUrl: String?
    let titleCount: Int?
    let itemId: Int?

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
        case itemId = "id"
    }
}

struct ApiSearchResponse: Codable {
    let query: String
    let results: [ApiSearchResult]
    let counts: [String: Int]
}

// --- List Endpoints ---

struct ApiCollectionListItem: Codable, Identifiable {
    var id: TmdbCollectionID { tmdbCollectionId }
    let tmdbCollectionId: TmdbCollectionID
    let name: String
    let posterUrl: String?
    let titleCount: Int

    enum CodingKeys: String, CodingKey {
        case name
        case tmdbCollectionId = "tmdb_collection_id"
        case posterUrl = "poster_url"
        case titleCount = "title_count"
    }
}

struct ApiCollectionListResponse: Codable {
    let collections: [ApiCollectionListItem]
}

struct ApiTagListItem: Codable, Identifiable {
    let id: TagID
    let name: String
    let color: String
    let titleCount: Int

    enum CodingKeys: String, CodingKey {
        case id, name, color
        case titleCount = "title_count"
    }
}

struct ApiTagListResponse: Codable {
    let tags: [ApiTagListItem]
}

// --- Browse/Landing Pages ---

struct ApiActorDetail: Codable {
    let name: String
    let headshotUrl: String?
    let biography: String?
    let birthday: String?
    let deathday: String?
    let placeOfBirth: String?
    let knownForDepartment: String?
    let ownedTitles: [ApiOwnedCredit]
    let otherWorks: [ApiCreditEntry]

    enum CodingKeys: String, CodingKey {
        case name, biography, birthday, deathday
        case headshotUrl = "headshot_url"
        case placeOfBirth = "place_of_birth"
        case knownForDepartment = "known_for_department"
        case ownedTitles = "owned_titles"
        case otherWorks = "other_works"
    }
}

struct ApiOwnedCredit: Codable {
    let title: ApiTitle
    let characterName: String?

    enum CodingKeys: String, CodingKey {
        case title
        case characterName = "character_name"
    }
}

struct ApiCreditEntry: Codable, Identifiable {
    var id: String { "\(tmdbId.rawValue)-\(mediaType.rawValue)" }
    let tmdbId: TmdbID
    let title: String
    let mediaType: MediaType
    let characterName: String?
    let releaseYear: Int?
    let posterUrl: String?
    let popularity: Double
    let wished: Bool

    enum CodingKeys: String, CodingKey {
        case title, popularity, wished
        case tmdbId = "tmdb_id"
        case mediaType = "media_type"
        case characterName = "character_name"
        case releaseYear = "release_year"
        case posterUrl = "poster_url"
    }
}

struct ApiCollectionDetail: Codable {
    let name: String
    let posterUrl: String?
    let items: [ApiCollectionItem]

    enum CodingKeys: String, CodingKey {
        case name, items
        case posterUrl = "poster_url"
    }
}

struct ApiCollectionItem: Codable, Identifiable {
    var id: TmdbID { tmdbMovieId }
    let tmdbMovieId: TmdbID
    let name: String
    let posterUrl: String?
    let year: Int?
    let owned: Bool
    let playable: Bool
    let titleId: TitleID?
    let quality: String?
    let contentRating: String?
    let transcodeId: TranscodeID?

    enum CodingKeys: String, CodingKey {
        case name, year, owned, playable, quality
        case tmdbMovieId = "tmdb_movie_id"
        case posterUrl = "poster_url"
        case titleId = "title_id"
        case contentRating = "content_rating"
        case transcodeId = "transcode_id"
    }
}

struct ApiTagDetail: Codable {
    let name: String
    let color: String
    let titles: [ApiTitle]
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
    let episodeId: EpisodeID
    let transcodeId: TranscodeID?
    let seasonNumber: Int
    let episodeNumber: Int
    let name: String?
    let quality: String?
    let playable: Bool
    let hasSubtitles: Bool
    let resumePosition: Double
    let watchedPercent: Int
    let forMobileAvailable: Bool?
    let forMobileRequested: Bool?

    enum CodingKeys: String, CodingKey {
        case name, quality, playable
        case episodeId = "episode_id"
        case transcodeId = "transcode_id"
        case seasonNumber = "season_number"
        case episodeNumber = "episode_number"
        case hasSubtitles = "has_subtitles"
        case resumePosition = "resume_position"
        case watchedPercent = "watched_percent"
        case forMobileAvailable = "for_mobile_available"
        case forMobileRequested = "for_mobile_requested"
    }
}
