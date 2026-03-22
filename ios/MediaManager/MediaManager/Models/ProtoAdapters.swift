import Foundation

// MARK: - Proto Enum Display Extensions

extension MMQuality {
    var displayString: String? {
        switch self {
        case .sd: return "SD"
        case .fhd: return "FHD"
        case .uhd: return "UHD"
        case .unknown, .UNRECOGNIZED: return nil
        }
    }
}

extension MMContentRating {
    var displayString: String? {
        switch self {
        case .g: return "G"
        case .pg: return "PG"
        case .pg13: return "PG-13"
        case .r: return "R"
        case .nc17: return "NC-17"
        case .tvY: return "TV-Y"
        case .tvY7: return "TV-Y7"
        case .tvG: return "TV-G"
        case .tvPg: return "TV-PG"
        case .tv14: return "TV-14"
        case .tvMa: return "TV-MA"
        case .nr: return "NR"
        case .unknown, .UNRECOGNIZED: return nil
        }
    }
}

extension MMMediaFormat {
    var displayString: String? {
        switch self {
        case .dvd: return "DVD"
        case .bluray: return "Blu-ray"
        case .uhdBluray: return "UHD Blu-ray"
        case .hdDvd: return "HD DVD"
        case .unknown, .UNRECOGNIZED: return nil
        }
    }
}

extension MMMediaType {
    var appMediaType: MediaType? {
        switch self {
        case .movie: return .movie
        case .tv: return .tv
        case .personal: return .personal
        case .unknown, .UNRECOGNIZED: return nil
        }
    }
}

extension MMSearchResultType {
    var appType: SearchResultType? {
        switch self {
        case .movie: return .movie
        case .series: return .series
        case .actor: return .actor
        case .collection: return .collection
        case .tag: return .tag
        case .genre: return .genre
        case .unknown, .UNRECOGNIZED: return nil
        }
    }

    var displayString: String {
        switch self {
        case .movie: return "movie"
        case .series: return "series"
        case .actor: return "actor"
        case .collection: return "collection"
        case .tag: return "tag"
        case .genre: return "genre"
        case .unknown, .UNRECOGNIZED: return "unknown"
        }
    }
}

extension MMAcquisitionStatus {
    var displayString: String? {
        switch self {
        case .notAvailable: return "not_available"
        case .rejected: return "rejected"
        case .ordered: return "ordered"
        case .owned: return "owned"
        case .needsAssistance: return "needs_assistance"
        case .unknown, .UNRECOGNIZED: return nil
        }
    }
}

extension MMWishStatus {
    var displayString: String? {
        switch self {
        case .active: return "active"
        case .fulfilled: return "fulfilled"
        case .unknown, .UNRECOGNIZED: return nil
        }
    }
}

extension MMAuthMethod {
    var displayString: String {
        switch self {
        case .jwt: return "jwt"
        case .unknown, .UNRECOGNIZED: return "unknown"
        }
    }
}

extension MMCapability {
    var displayString: String {
        switch self {
        case .catalog: return "catalog"
        case .streaming: return "streaming"
        case .wishlist: return "wishlist"
        case .playbackProgress: return "playback_progress"
        case .downloads: return "downloads"
        case .unknown, .UNRECOGNIZED: return "unknown"
        }
    }
}

// MARK: - Helper: MMTimestamp -> Date

extension MMTimestamp {
    var date: Date {
        Date(timeIntervalSince1970: TimeInterval(secondsSinceEpoch))
    }

    var isoString: String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }
}

// MARK: - Helper: MMCalendarDate -> String

extension MMCalendarDate {
    /// Formats as "YYYY-MM-DD" for compatibility with old API string dates.
    var dateString: String? {
        guard year > 0, month != .unknown else { return nil }
        return String(format: "%04d-%02d-%02d", year, month.rawValue, day)
    }
}

// MARK: - CatalogModels Adapters

struct ApiTitle: Identifiable, Hashable, Sendable {
    let proto: MMTitle

    var id: TitleID { TitleID(proto: Int64(proto.id)) }
    var name: String { proto.name }
    var mediaType: MediaType { proto.mediaType.appMediaType ?? .movie }
    var year: Int? { proto.hasYear ? Int(proto.year) : nil }
    var description: String? { proto.hasDescription_p ? proto.description_p : nil }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var backdropUrl: String? { proto.hasBackdropURL ? proto.backdropURL : nil }
    var contentRating: String? { proto.contentRating.displayString }
    var popularity: Double? { proto.hasPopularity ? proto.popularity : nil }
    var quality: String? { proto.quality.displayString }
    var playable: Bool { proto.playable }
    var transcodeId: TranscodeID? { proto.hasTranscodeID ? TranscodeID(proto: proto.transcodeID) : nil }
    var tmdbId: TmdbID? { proto.hasTmdbID ? TmdbID(proto: Int64(proto.tmdbID)) : nil }
    var tmdbCollectionId: TmdbCollectionID? { proto.hasTmdbCollectionID ? TmdbCollectionID(proto: Int64(proto.tmdbCollectionID)) : nil }
    var tmdbCollectionName: String? { proto.hasTmdbCollectionName ? proto.tmdbCollectionName : nil }
    var familyMembers: [String]? { proto.familyMembers.isEmpty ? nil : proto.familyMembers }
    var forMobileAvailable: Bool? { proto.lowStorageTranscodeAvailable }

    static func == (lhs: ApiTitle, rhs: ApiTitle) -> Bool {
        lhs.proto.id == rhs.proto.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(proto.id)
    }
}

struct ApiCarousel: Sendable {
    let proto: MMCarousel

    var name: String { proto.name }
    var items: [ApiTitle] { proto.items.map { ApiTitle(proto: $0) } }
}

struct ApiHomeFeed: Sendable {
    let proto: MMHomeFeedResponse

    var carousels: [ApiCarousel] { proto.carousels.map { ApiCarousel(proto: $0) } }
    var missingSeasons: [ApiMissingSeason]? {
        proto.missingSeasons.isEmpty ? nil : proto.missingSeasons.map { ApiMissingSeason(proto: $0) }
    }
}

struct ApiMissingSeason: Identifiable, Sendable {
    let proto: MMMissingSeason

    var id: TitleID { titleId }
    var titleId: TitleID { TitleID(proto: proto.titleID) }
    var titleName: String { proto.titleName }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var tmdbId: TmdbID? { proto.hasTmdbID ? TmdbID(proto: Int64(proto.tmdbID)) : nil }
    var mediaType: MediaType? { proto.mediaType.appMediaType }
    var seasons: [ApiMissingSeasonEntry] { proto.seasons.map { ApiMissingSeasonEntry(proto: $0) } }
}

struct ApiMissingSeasonEntry: Identifiable, Sendable {
    let proto: MMMissingSeasonEntry

    var id: Int { seasonNumber }
    var seasonNumber: Int { Int(proto.seasonNumber) }
    var name: String? { proto.hasName ? proto.name : nil }
    var episodeCount: Int? { proto.hasEpisodeCount ? Int(proto.episodeCount) : nil }
}

struct ApiTitlePage: Sendable {
    let proto: MMTitlePageResponse

    var titles: [ApiTitle] { proto.titles.map { ApiTitle(proto: $0) } }
    var total: Int { proto.hasPagination ? Int(proto.pagination.total) : 0 }
    var page: Int { proto.hasPagination ? Int(proto.pagination.page) : 0 }
    var limit: Int { proto.hasPagination ? Int(proto.pagination.limit) : 0 }
    var totalPages: Int { proto.hasPagination ? Int(proto.pagination.totalPages) : 0 }
}

struct ApiCastMember: Identifiable, Sendable {
    let proto: MMCastMember

    var id: TmdbPersonID { tmdbPersonId }
    var tmdbPersonId: TmdbPersonID { TmdbPersonID(proto: Int64(proto.tmdbPersonID)) }
    var name: String { proto.name }
    var characterName: String? { proto.hasCharacterName ? proto.characterName : nil }
    var headshotUrl: String? { proto.hasHeadshotURL ? proto.headshotURL : nil }
    var order: Int { Int(proto.order) }
}

struct ApiGenre: Identifiable, Sendable {
    let proto: MMGenre

    var id: GenreID { GenreID(proto: proto.id) }
    var name: String { proto.name }
}

struct ApiTag: Identifiable, Sendable {
    let proto: MMTag

    var id: TagID { TagID(proto: proto.id) }
    var name: String { proto.name }
    var color: String { proto.color.hex }
}

struct ApiTranscode: Identifiable, Sendable {
    let proto: MMTranscode

    var id: TranscodeID { TranscodeID(proto: proto.id) }
    var mediaFormat: String? { proto.mediaFormat.displayString }
    var quality: String { proto.quality.displayString ?? "SD" }
    var episodeId: EpisodeID? { proto.hasEpisodeID ? EpisodeID(proto: proto.episodeID) : nil }
    var seasonNumber: Int? { proto.hasSeasonNumber ? Int(proto.seasonNumber) : nil }
    var episodeNumber: Int? { proto.hasEpisodeNumber ? Int(proto.episodeNumber) : nil }
    var episodeName: String? { proto.hasEpisodeName ? proto.episodeName : nil }
    var playable: Bool { proto.playable }
    var hasSubtitles: Bool { proto.hasSubtitles_p }
    var forMobileAvailable: Bool? { proto.lowStorageTranscodeAvailable }
    var forMobileRequested: Bool? { proto.lowStorageTranscodeRequested }
}

struct ApiPlaybackProgress: Sendable {
    let proto: MMPlaybackProgress

    var transcodeId: TranscodeID { TranscodeID(proto: proto.transcodeID) }
    var positionSeconds: Double { proto.hasPosition ? proto.position.seconds : 0 }
    var durationSeconds: Double? { proto.hasDuration ? proto.duration.seconds : nil }
    var updatedAt: String? { proto.hasUpdatedAt ? proto.updatedAt.isoString : nil }
}

struct ApiTitleDetail: Sendable {
    let proto: MMTitleDetail

    // Flattened from proto.title
    private var t: MMTitle { proto.title }

    var id: TitleID { TitleID(proto: Int64(t.id)) }
    var name: String { t.name }
    var mediaType: MediaType { t.mediaType.appMediaType ?? .movie }
    var year: Int? { t.hasYear ? Int(t.year) : nil }
    var description: String? { t.hasDescription_p ? t.description_p : nil }
    var posterUrl: String? { t.hasPosterURL ? t.posterURL : nil }
    var backdropUrl: String? { t.hasBackdropURL ? t.backdropURL : nil }
    var contentRating: String? { t.contentRating.displayString }
    var popularity: Double? { t.hasPopularity ? t.popularity : nil }
    var quality: String? { t.quality.displayString }
    var playable: Bool { t.playable }
    var transcodeId: TranscodeID? { t.hasTranscodeID ? TranscodeID(proto: t.transcodeID) : nil }
    var tmdbId: TmdbID? { t.hasTmdbID ? TmdbID(proto: Int64(t.tmdbID)) : nil }
    var tmdbCollectionId: TmdbCollectionID? { t.hasTmdbCollectionID ? TmdbCollectionID(proto: Int64(t.tmdbCollectionID)) : nil }
    var tmdbCollectionName: String? { t.hasTmdbCollectionName ? t.tmdbCollectionName : nil }
    var familyMembers: [String]? { t.familyMembers.isEmpty ? nil : t.familyMembers }
    var forMobileAvailable: Bool? { t.lowStorageTranscodeAvailable }

    // Detail-only fields
    var cast: [ApiCastMember] { proto.cast.map { ApiCastMember(proto: $0) } }
    var genres: [ApiGenre] { proto.genres.map { ApiGenre(proto: $0) } }
    var tags: [ApiTag] { proto.tags.map { ApiTag(proto: $0) } }
    var transcodes: [ApiTranscode] { proto.transcodes.map { ApiTranscode(proto: $0) } }
    var playbackProgress: ApiPlaybackProgress? {
        proto.hasPlaybackProgress ? ApiPlaybackProgress(proto: proto.playbackProgress) : nil
    }
    var isFavorite: Bool? { proto.isFavorite }
    var isHidden: Bool? { proto.isHidden }
    var wished: Bool? { proto.wished }
}

struct ApiSearchResult: Identifiable, Sendable {
    let proto: MMSearchResult

    var id: String {
        "\(resultType)-\(name)-\(titleId?.rawValue ?? 0)-\(itemId ?? 0)"
    }
    var resultType: String { proto.resultType.displayString }
    var name: String { proto.name }
    var titleId: TitleID? { proto.hasTitleID ? TitleID(proto: proto.titleID) : nil }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var year: Int? { proto.hasYear ? Int(proto.year) : nil }
    var quality: String? { proto.quality.displayString }
    var contentRating: String? { proto.contentRating.displayString }
    var transcodeId: TranscodeID? { proto.hasTranscodeID ? TranscodeID(proto: proto.transcodeID) : nil }
    var mediaType: MediaType? { proto.mediaType.appMediaType }
    var tmdbCollectionId: TmdbCollectionID? { proto.hasTmdbCollectionID ? TmdbCollectionID(proto: Int64(proto.tmdbCollectionID)) : nil }
    var tmdbPersonId: TmdbPersonID? { proto.hasTmdbPersonID ? TmdbPersonID(proto: Int64(proto.tmdbPersonID)) : nil }
    var headshotUrl: String? { proto.hasHeadshotURL ? proto.headshotURL : nil }
    var titleCount: Int? { proto.hasTitleCount ? Int(proto.titleCount) : nil }
    var itemId: Int? { proto.hasItemID ? Int(proto.itemID) : nil }
}

struct ApiSearchResponse: Sendable {
    let proto: MMSearchResponse

    var query: String { proto.query }
    var results: [ApiSearchResult] { proto.results.map { ApiSearchResult(proto: $0) } }
    var counts: [String: Int] { proto.counts.mapValues { Int($0) } }
}

// MARK: - List Endpoints

struct ApiCollectionListItem: Identifiable, Sendable {
    let proto: MMCollectionListItem

    var id: TmdbCollectionID { tmdbCollectionId }
    var tmdbCollectionId: TmdbCollectionID { TmdbCollectionID(proto: Int64(proto.tmdbCollectionID)) }
    var name: String { proto.name }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var titleCount: Int { Int(proto.titleCount) }
}

struct ApiCollectionListResponse: Sendable {
    let proto: MMCollectionListResponse

    var collections: [ApiCollectionListItem] { proto.collections.map { ApiCollectionListItem(proto: $0) } }
}

struct ApiTagListItem: Identifiable, Sendable {
    let proto: MMTagListItem

    var id: TagID { TagID(proto: proto.id) }
    var name: String { proto.name }
    var color: String { proto.color.hex }
    var titleCount: Int { Int(proto.titleCount) }
}

struct ApiTagListResponse: Sendable {
    let proto: MMTagListResponse

    var tags: [ApiTagListItem] { proto.tags.map { ApiTagListItem(proto: $0) } }
}

// MARK: - Browse/Landing Pages

struct ApiActorDetail: Sendable {
    let proto: MMActorDetail

    var name: String { proto.name }
    var headshotUrl: String? { proto.hasHeadshotURL ? proto.headshotURL : nil }
    var biography: String? { proto.hasBiography ? proto.biography : nil }
    var birthday: String? { proto.hasBirthday ? proto.birthday.dateString : nil }
    var deathday: String? { proto.hasDeathday ? proto.deathday.dateString : nil }
    var placeOfBirth: String? { proto.hasPlaceOfBirth ? proto.placeOfBirth : nil }
    var knownForDepartment: String? { proto.hasKnownForDepartment ? proto.knownForDepartment : nil }
    var ownedTitles: [ApiOwnedCredit] { proto.ownedTitles.map { ApiOwnedCredit(proto: $0) } }
    var otherWorks: [ApiCreditEntry] { proto.otherWorks.map { ApiCreditEntry(proto: $0) } }
}

struct ApiOwnedCredit: Sendable {
    let proto: MMOwnedCredit

    var title: ApiTitle { ApiTitle(proto: proto.title) }
    var characterName: String? { proto.hasCharacterName ? proto.characterName : nil }
}

struct ApiCreditEntry: Identifiable, Sendable {
    let proto: MMCreditEntry

    var id: String { "\(tmdbId.rawValue)-\(mediaType.rawValue)" }
    var tmdbId: TmdbID { TmdbID(proto: Int64(proto.tmdbID)) }
    var title: String { proto.title }
    var mediaType: MediaType { proto.mediaType.appMediaType ?? .movie }
    var characterName: String? { proto.hasCharacterName ? proto.characterName : nil }
    var releaseYear: Int? { proto.hasReleaseYear ? Int(proto.releaseYear) : nil }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var popularity: Double { proto.popularity }
    var wished: Bool { proto.wished }
}

struct ApiCollectionDetail: Sendable {
    let proto: MMCollectionDetail

    var name: String { proto.name }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var items: [ApiCollectionItem] { proto.items.map { ApiCollectionItem(proto: $0) } }
}

struct ApiCollectionItem: Identifiable, Sendable {
    let proto: MMCollectionItem

    var id: TmdbID { tmdbMovieId }
    var tmdbMovieId: TmdbID { TmdbID(proto: Int64(proto.tmdbMovieID)) }
    var name: String { proto.name }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var year: Int? { proto.hasYear ? Int(proto.year) : nil }
    var owned: Bool { proto.owned }
    var playable: Bool { proto.playable }
    var titleId: TitleID? { proto.hasTitleID ? TitleID(proto: proto.titleID) : nil }
    var quality: String? { proto.quality.displayString }
    var contentRating: String? { proto.contentRating.displayString }
    var transcodeId: TranscodeID? { proto.hasTranscodeID ? TranscodeID(proto: proto.transcodeID) : nil }
}

struct ApiTagDetail: Sendable {
    let proto: MMTagDetail

    var name: String { proto.name }
    var color: String { proto.color.hex }
    var titles: [ApiTitle] { proto.titles.map { ApiTitle(proto: $0) } }
}

struct ApiGenreDetail: Sendable {
    let proto: MMGenreDetail

    var name: String { proto.name }
    var titles: [ApiTitle] { proto.titles.map { ApiTitle(proto: $0) } }
}

// MARK: - TV Shows

struct ApiSeason: Hashable, Sendable {
    let proto: MMSeason

    var seasonNumber: Int { Int(proto.seasonNumber) }
    var name: String? { proto.hasName ? proto.name : nil }
    var episodeCount: Int { Int(proto.episodeCount) }

    static func == (lhs: ApiSeason, rhs: ApiSeason) -> Bool {
        lhs.seasonNumber == rhs.seasonNumber
            && lhs.name == rhs.name
            && lhs.episodeCount == rhs.episodeCount
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(seasonNumber)
        hasher.combine(name)
        hasher.combine(episodeCount)
    }
}

struct ApiEpisode: Sendable {
    let proto: MMEpisode

    var episodeId: EpisodeID { EpisodeID(proto: proto.episodeID) }
    var transcodeId: TranscodeID? { proto.hasTranscodeID ? TranscodeID(proto: proto.transcodeID) : nil }
    var seasonNumber: Int { Int(proto.seasonNumber) }
    var episodeNumber: Int { Int(proto.episodeNumber) }
    var name: String? { proto.hasName ? proto.name : nil }
    var quality: String? { proto.quality.displayString }
    var playable: Bool { proto.playable }
    var hasSubtitles: Bool { proto.hasSubtitles_p }
    var resumePosition: Double { proto.hasResumePosition ? proto.resumePosition.seconds : 0 }
    var watchedPercent: Int {
        let duration = proto.hasDuration ? proto.duration.seconds : 0
        guard duration > 0 else { return 0 }
        let position = proto.hasResumePosition ? proto.resumePosition.seconds : 0
        return Int(position / duration * 100)
    }
    var forMobileAvailable: Bool? { proto.lowStorageTranscodeAvailable }
    var forMobileRequested: Bool? { proto.lowStorageTranscodeRequested }
}

// MARK: - APIModels Adapters

struct DiscoverResponse: Sendable {
    let proto: MMDiscoverResponse

    var apiVersions: [String] { proto.apiVersions.map { String($0) } }
    var authMethods: [String] { proto.authMethods.map { $0.displayString } }
    var secureUrl: String? { proto.hasSecureURL ? proto.secureURL : nil }
    var serverFingerprint: String? { proto.serverFingerprint.isEmpty ? nil : proto.serverFingerprint }
}

struct ServerInfo: Sendable {
    let proto: MMInfoResponse

    var serverVersion: String { proto.serverVersion }
    var apiVersion: String { "1" }
    var capabilities: [String] { proto.capabilities.map { $0.displayString } }
    var titleCount: Int { Int(proto.titleCount) }
    var user: ServerUserInfo? { proto.hasUser ? ServerUserInfo(proto: proto.user) : nil }
}

struct ServerUserInfo: Sendable {
    let proto: MMServerUserInfo

    var id: UserID { UserID(proto: proto.id) }
    var username: String { proto.username }
    var displayName: String { proto.hasDisplayName ? proto.displayName : proto.username }
    var isAdmin: Bool { proto.isAdmin }
    var ratingCeiling: Int? { nil }
    var ratingCeilingLabel: String? { nil }
    var fulfilledWishCount: Int? { nil }
    var passwordChangeRequired: Bool? { nil }
}

struct AuthResponse: Sendable {
    let proto: MMTokenResponse

    var accessToken: String { proto.accessToken.base64EncodedString() }
    var refreshToken: String { proto.refreshToken.base64EncodedString() }
    var expiresIn: Int { Int(proto.expiresIn) }
}

// MARK: - WishListModels Adapters

struct ApiWish: Identifiable, Sendable {
    let proto: MMWishItem

    var id: String {
        "\(tmdbId?.rawValue ?? 0)-\(mediaType?.rawValue ?? "")-\(seasonNumber ?? 0)-\(status ?? "")"
    }
    var tmdbId: TmdbID? { proto.tmdbID != 0 ? TmdbID(proto: Int64(proto.tmdbID)) : nil }
    var mediaType: MediaType? { proto.mediaType.appMediaType }
    var title: String { proto.title }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var releaseYear: Int? { proto.hasReleaseYear ? Int(proto.releaseYear) : nil }
    var seasonNumber: Int? { proto.hasSeasonNumber ? Int(proto.seasonNumber) : nil }
    var voteCount: Int { Int(proto.voteCount) }
    var voters: [String] { proto.voters }
    var voted: Bool { proto.userVoted }
    var wishId: WishID? { proto.id != 0 ? WishID(proto: proto.id) : nil }
    var acquisitionStatus: String? { proto.acquisitionStatus.displayString }
    var status: String? { proto.status.displayString }
    var titleId: TitleID? { nil }

    var isFulfilled: Bool { proto.status == .fulfilled }
}

struct ApiWishListResponse: Sendable {
    let proto: MMWishListResponse

    var wishes: [ApiWish] { proto.wishes.map { ApiWish(proto: $0) } }
}

struct ApiTranscodeWish: Identifiable, Sendable {
    let proto: MMTranscodeWishItem

    var id: TranscodeID { TranscodeID(proto: proto.titleID) }
    var titleId: TitleID { TitleID(proto: proto.titleID) }
    var titleName: String { proto.titleName }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var mediaType: MediaType? { nil }
    var requestedAt: String? { nil }
}

struct ApiTranscodeWishListResponse: Sendable {
    let proto: MMTranscodeWishListResponse

    var transcodeWishes: [ApiTranscodeWish] { proto.wishes.map { ApiTranscodeWish(proto: $0) } }
}

struct TmdbSearchItem: Identifiable, Sendable {
    let proto: MMTmdbResult

    var id: String { "\(tmdbId?.rawValue ?? 0)-\(mediaType?.rawValue ?? "")" }
    var tmdbId: TmdbID? { proto.tmdbID != 0 ? TmdbID(proto: Int64(proto.tmdbID)) : nil }
    var title: String? { proto.title.isEmpty ? nil : proto.title }
    var mediaType: MediaType? { proto.mediaType.appMediaType }
    var releaseYear: Int? { proto.hasReleaseYear ? Int(proto.releaseYear) : nil }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var posterPath: String? { nil }
    var popularity: Double? { proto.hasPopularity ? proto.popularity : nil }
    var overview: String? { nil }
}

struct TmdbSearchResponse: Sendable {
    let proto: MMTmdbSearchResponse

    var results: [TmdbSearchItem] { proto.results.map { TmdbSearchItem(proto: $0) } }
}

// MARK: - LiveModels Adapters

struct ApiCamera: Identifiable, Sendable {
    let proto: MMCamera

    var id: CameraID { CameraID(proto: proto.id) }
    var name: String { proto.name }
    var hlsUrl: String { proto.streamURL }
    var snapshotUrl: String { proto.hasSnapshotURL ? proto.snapshotURL : "" }
}

struct ApiCameraListResponse: Sendable {
    let proto: MMCameraListResponse

    var cameras: [ApiCamera] { proto.cameras.map { ApiCamera(proto: $0) } }
}

struct ApiTvChannel: Identifiable, Sendable {
    let proto: MMTvChannel

    var id: ChannelID { ChannelID(proto: proto.id) }
    var guideNumber: String { proto.number }
    var guideName: String { proto.name }
    var networkAffiliation: String? { nil }
    var receptionQuality: Int { 0 }
    var hlsUrl: String { proto.streamURL }
}

struct ApiTvChannelListResponse: Sendable {
    let proto: MMTvChannelListResponse

    var channels: [ApiTvChannel] { proto.channels.map { ApiTvChannel(proto: $0) } }
}

// ID wrapper proto initializers are in Types.swift
