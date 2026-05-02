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
        case .massMarketPaperback: return "Mass Market Paperback"
        case .tradePaperback: return "Trade Paperback"
        case .hardback: return "Hardback"
        case .ebookEpub: return "EPUB"
        case .ebookPdf: return "PDF"
        case .audiobookCd: return "Audiobook CD"
        case .audiobookDigital: return "Audiobook"
        case .cd: return "CD"
        case .vinylLp: return "Vinyl"
        case .audioFlac: return "FLAC"
        case .audioMp3: return "MP3"
        case .audioAac: return "AAC"
        case .audioOgg: return "OGG"
        case .audioWav: return "WAV"
        case .other: return nil
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
        // Books and albums don't yet have iOS app-side enum cases —
        // they'll arrive with the Books / Audio feature modules.
        case .book, .album: return nil
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
        // New result types from the Books / Audio / Live modules. The
        // iOS SearchResultType doesn't yet model them; surface as nil
        // until the corresponding feature work lands.
        case .book, .album, .artist, .author, .track, .personal, .channel, .camera: return nil
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
        case .book: return "book"
        case .album: return "album"
        case .artist: return "artist"
        case .author: return "author"
        case .track: return "track"
        case .personal: return "home video"
        case .channel: return "channel"
        case .camera: return "camera"
        case .unknown, .UNRECOGNIZED: return "unknown"
        }
    }
}

extension MMAcquisitionStatus {
    var appStatus: AcquisitionStatus? {
        switch self {
        case .unknown, .UNRECOGNIZED: return nil
        case .notAvailable: return .notAvailable
        case .rejected: return .rejected
        case .ordered: return .ordered
        case .owned: return .owned
        case .needsAssistance: return .needsAssistance
        }
    }
}

extension MMWishLifecycleStage {
    var appStage: WishLifecycleStage? {
        switch self {
        case .unknown, .UNRECOGNIZED: return nil
        case .wishedFor: return .wishedFor
        case .notFeasible: return .notFeasible
        case .wontOrder: return .wontOrder
        case .needsAssistance: return .needsAssistance
        case .ordered: return .ordered
        case .inHousePendingNas: return .inHousePendingNas
        case .onNasPendingDesktop: return .onNasPendingDesktop
        case .readyToWatch: return .readyToWatch
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

    init(proto: MMTitle) { self.proto = proto }

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
    var tmdbId: TmdbID? { proto.hasTmdbID ? TmdbID(proto: proto.tmdbID) : nil }
    var tmdbCollectionId: TmdbCollectionID? { proto.hasTmdbCollectionID ? TmdbCollectionID(proto: proto.tmdbCollectionID) : nil }
    var tmdbCollectionName: String? { proto.hasTmdbCollectionName ? proto.tmdbCollectionName : nil }
    var familyMembers: [String]? { proto.familyMembers.isEmpty ? nil : proto.familyMembers }
    var forMobileAvailable: Bool? { proto.lowStorageTranscodeAvailable }

    // Resume Playing fields (populated only in "Resume Playing" carousel)
    var resumePositionSeconds: Double? { proto.hasResumePosition ? proto.resumePosition.seconds : nil }
    var resumeDurationSeconds: Double? { proto.hasResumeDuration ? proto.resumeDuration.seconds : nil }
    var resumeSeasonNumber: Int? { proto.hasResumeSeasonNumber ? Int(proto.resumeSeasonNumber) : nil }
    var resumeEpisodeNumber: Int? { proto.hasResumeEpisodeNumber ? Int(proto.resumeEpisodeNumber) : nil }
    var resumeEpisodeName: String? { proto.hasResumeEpisodeName ? proto.resumeEpisodeName : nil }

    var resumeProgress: Double? {
        guard let pos = resumePositionSeconds, let dur = resumeDurationSeconds, dur > 0 else { return nil }
        return pos / dur
    }

    /// Convenience init for constructing ApiTitle from fields (used by views like DownloadsView).
    init(id: TitleID, name: String, mediaType: MediaType, year: Int? = nil,
         description: String? = nil, posterUrl: String? = nil, backdropUrl: String? = nil,
         contentRating: String? = nil, popularity: Double? = nil, quality: String? = nil,
         playable: Bool = false, transcodeId: TranscodeID? = nil, tmdbId: TmdbID? = nil,
         tmdbCollectionId: TmdbCollectionID? = nil, tmdbCollectionName: String? = nil,
         familyMembers: [String]? = nil, forMobileAvailable: Bool? = nil) {
        var t = MMTitle()
        t.id = id.protoValue
        t.name = name
        t.mediaType = mediaType == .movie ? .movie : mediaType == .tv ? .tv : .personal
        if let y = year { t.year = Int32(y) }
        if let d = description { t.description_p = d }
        if let p = posterUrl { t.posterURL = p }
        if let b = backdropUrl { t.backdropURL = b }
        if let p = popularity { t.popularity = p }
        t.playable = playable
        if let tid = transcodeId { t.transcodeID = tid.protoValue }
        if let tmid = tmdbId { t.tmdbID = tmid.protoValue }
        if let cid = tmdbCollectionId { t.tmdbCollectionID = cid.protoValue }
        if let cn = tmdbCollectionName { t.tmdbCollectionName = cn }
        if let fm = familyMembers { t.familyMembers = fm }
        t.lowStorageTranscodeAvailable = forMobileAvailable ?? false
        self.proto = t
    }

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
    var tmdbId: TmdbID? { proto.hasTmdbID ? TmdbID(proto: proto.tmdbID) : nil }
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
    var tmdbPersonId: TmdbPersonID { TmdbPersonID(proto: proto.tmdbPersonID) }
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
    var tmdbId: TmdbID? { t.hasTmdbID ? TmdbID(proto: t.tmdbID) : nil }
    var tmdbCollectionId: TmdbCollectionID? { t.hasTmdbCollectionID ? TmdbCollectionID(proto: t.tmdbCollectionID) : nil }
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
    var tmdbCollectionId: TmdbCollectionID? { proto.hasTmdbCollectionID ? TmdbCollectionID(proto: proto.tmdbCollectionID) : nil }
    var tmdbPersonId: TmdbPersonID? { proto.hasTmdbPersonID ? TmdbPersonID(proto: proto.tmdbPersonID) : nil }
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
    var tmdbCollectionId: TmdbCollectionID { TmdbCollectionID(proto: proto.tmdbCollectionID) }
    var name: String { proto.name }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var titleCount: Int { Int(proto.titleCount) }
}

struct ApiCollectionListResponse: Sendable {
    let proto: MMCollectionListResponse

    var collections: [ApiCollectionListItem] { proto.collections.map { ApiCollectionListItem(proto: $0) } }
}

struct ApiTagListItem: Identifiable, Sendable {
    let id: TagID
    let name: String
    let color: String
    let titleCount: Int

    init(proto: MMTagListItem) {
        id = TagID(proto: proto.id)
        name = proto.name
        color = proto.color.hex
        titleCount = Int(proto.titleCount)
    }

    init(adminProto tag: MMAdminTagListItem) {
        id = TagID(proto: tag.id)
        name = tag.name
        color = tag.color.hex
        titleCount = Int(tag.titleCount)
    }
}

struct ApiTagListResponse: Sendable {
    let tags: [ApiTagListItem]

    init(proto: MMTagListResponse) {
        tags = proto.tags.map { ApiTagListItem(proto: $0) }
    }

    init(tags: [ApiTagListItem]) {
        self.tags = tags
    }
}

// ApiTagListResponse defined above with both proto and manual inits

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
    var tmdbId: TmdbID { TmdbID(proto: proto.tmdbID) }
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
    var tmdbMovieId: TmdbID { TmdbID(proto: proto.tmdbMovieID) }
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
        wishId.map { "\($0.rawValue)" } ?? "\(tmdbId?.rawValue ?? 0)-\(mediaType?.rawValue ?? "")-\(seasonNumber ?? 0)"
    }
    var tmdbId: TmdbID? { proto.tmdbID != 0 ? TmdbID(proto: proto.tmdbID) : nil }
    var mediaType: MediaType? { proto.mediaType.appMediaType }
    var title: String { proto.title }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var releaseYear: Int? { proto.hasReleaseYear ? Int(proto.releaseYear) : nil }
    var seasonNumber: Int? { proto.hasSeasonNumber ? Int(proto.seasonNumber) : nil }
    var voteCount: Int { Int(proto.voteCount) }
    var voters: [String] { proto.voters }
    var voted: Bool { proto.userVoted }
    var wishId: WishID? { proto.id != 0 ? WishID(proto: proto.id) : nil }
    var acquisitionStatus: AcquisitionStatus? { proto.acquisitionStatus.appStatus }
    var lifecycleStage: WishLifecycleStage? { proto.lifecycleStage.appStage }
    var status: String? { proto.status.displayString }
    var titleId: TitleID? { proto.hasTitleID ? TitleID(proto: proto.titleID) : nil }

    var isReadyToWatch: Bool { lifecycleStage == .readyToWatch }
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
    // poster_url was retired from TranscodeWishItem (proto field 6 is reserved).
    // Clients now derive cover art from title_id via ImageService /
    // /posters/{size}/{title_id}.
    var posterUrl: String? { nil }
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
    var tmdbId: TmdbID? { proto.tmdbID != 0 ? TmdbID(proto: proto.tmdbID) : nil }
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

// MARK: - Profile / Sessions

struct ProfileResponse: Sendable {
    let proto: MMProfileResponse

    var username: String? { proto.username.isEmpty ? nil : proto.username }
    var displayName: String? { proto.hasDisplayName ? proto.displayName : nil }
    var isAdmin: Bool? { proto.isAdmin }
    var ratingCeiling: Int? { proto.hasRatingCeiling ? Int(proto.ratingCeiling.rawValue) : nil }
    var ratingCeilingLabel: String? { proto.hasRatingCeilingLabel ? proto.ratingCeilingLabel : nil }
    var liveTvMinQuality: Int? { Int(proto.liveTvMinQuality.rawValue) }
    var subtitlesEnabled: Bool? { proto.subtitlesEnabled }
    var mustChangePassword: Bool? { proto.mustChangePassword }
    var roleDisplay: String { (proto.isAdmin) ? "Admin" : "Viewer" }

    var privacyPolicyVersion: Int? { proto.hasPrivacyPolicyVersion ? Int(proto.privacyPolicyVersion) : nil }
    var privacyPolicyAcceptedAt: Date? {
        proto.hasPrivacyPolicyAcceptedAt ? Date(timeIntervalSince1970: TimeInterval(proto.privacyPolicyAcceptedAt.secondsSinceEpoch)) : nil
    }
    var termsOfUseVersion: Int? { proto.hasTermsOfUseVersion ? Int(proto.termsOfUseVersion) : nil }
    var termsOfUseAcceptedAt: Date? {
        proto.hasTermsOfUseAcceptedAt ? Date(timeIntervalSince1970: TimeInterval(proto.termsOfUseAcceptedAt.secondsSinceEpoch)) : nil
    }
}

struct ApiSession: Identifiable, Sendable {
    let proto: MMSessionInfo

    var id: String { "\(proto.id)-\(proto.type.rawValue)" }
    var sessionId: SessionID { SessionID(proto: proto.id) }
    var type: String {
        switch proto.type {
        case .browser: "browser"
        case .app: "app"
        case .device: "device"
        default: "unknown"
        }
    }
    var deviceName: String? { proto.hasDeviceName ? proto.deviceName : nil }
    var createdAt: String? { proto.hasCreatedAt ? proto.createdAt.isoString : nil }
    var lastUsedAt: String? { proto.hasLastUsedAt ? proto.lastUsedAt.isoString : nil }
    var expiresAt: String? { proto.hasExpiresAt ? proto.expiresAt.isoString : nil }
    var isCurrent: Bool { proto.isCurrent }
}

struct ApiSessionListResponse: Sendable {
    let proto: MMSessionListResponse

    var sessions: [ApiSession] { proto.sessions.map { ApiSession(proto: $0) } }
}

// MARK: - Admin Models

struct TranscodeStatusResponse: Sendable {
    let proto: MMTranscodeStatusResponse

    var pending: PendingWork { PendingWork(proto: proto) }
    var activeLeases: [TranscodeLease] { proto.activeLeases.map { TranscodeLease(proto: $0) } }
}

struct PendingWork: Sendable {
    let transcodes: Int
    let mobileTranscodes: Int?
    let thumbnails: Int
    let subtitles: Int
    let chapters: Int
    let total: Int

    init(proto: MMTranscodeStatusResponse) {
        transcodes = Int(proto.pendingTranscode)
        mobileTranscodes = Int(proto.pendingLowStorage)
        thumbnails = Int(proto.pendingThumbnails)
        subtitles = Int(proto.pendingSubtitles)
        chapters = Int(proto.pendingChapters)
        total = transcodes + (mobileTranscodes ?? 0) + thumbnails + subtitles + chapters
    }
}

struct TranscodeLease: Identifiable, Sendable {
    let proto: MMActiveLease

    var id: LeaseID { LeaseID(proto: proto.leaseID) }
    var leaseId: LeaseID { id }
    var buddyName: String? { proto.buddyName.isEmpty ? nil : proto.buddyName }
    var relativePath: String? { proto.relativePath.isEmpty ? nil : proto.relativePath }
    var leaseType: String? { proto.leaseType.displayString }
    var status: String? { proto.status.displayString }
    var progressPercent: Int? { Int(proto.progressPercent) }
    var encoder: String? { proto.hasEncoder ? proto.encoder : nil }
    var claimedAt: String? { proto.hasClaimedAt ? proto.claimedAt.isoString : nil }
}

struct BuddyStatusResponse: Sendable {
    let proto: MMBuddyStatusResponse

    var buddies: [BuddyInfo] { proto.buddies.map { BuddyInfo(proto: $0) } }
    var recentLeases: [RecentLease] { proto.recentLeases.map { RecentLease(proto: $0) } }
}

struct BuddyInfo: Identifiable, Sendable {
    let proto: MMBuddyInfo

    var id: String { proto.name }
    var name: String? { proto.name.isEmpty ? nil : proto.name }
    var activeLeases: Int { Int(proto.activeLeases) }
    var currentWork: [BuddyWork] { [] } // Not in proto; kept for view compat
}

struct BuddyWork: Identifiable, Sendable {
    var id: Int { 0 }
    var leaseId: LeaseID { LeaseID(rawValue: 0) }
    var relativePath: String? { nil }
    var leaseType: String? { nil }
    var progressPercent: Int? { nil }
    var encoder: String? { nil }
}

struct RecentLease: Identifiable, Sendable {
    let proto: MMRecentLease

    var id: LeaseID { LeaseID(proto: proto.leaseID) }
    var leaseId: LeaseID { id }
    var buddyName: String? { proto.buddyName.isEmpty ? nil : proto.buddyName }
    var relativePath: String? { proto.relativePath.isEmpty ? nil : proto.relativePath }
    var leaseType: String? { proto.leaseType.displayString }
    var status: String? { proto.status.displayString }
    var encoder: String? { nil }
    var completedAt: String? { proto.hasCompletedAt ? proto.completedAt.isoString : nil }
    var errorMessage: String? { nil }
}

struct AdminUser: Identifiable, Sendable {
    let proto: MMUserInfo

    var id: UserID { UserID(proto: proto.id) }
    var username: String { proto.username }
    var displayName: String? { proto.hasDisplayName ? proto.displayName : nil }
    var accessLevel: Int { Int(proto.accessLevel.rawValue) }
    var isAdmin: Bool { proto.accessLevel == .admin }
    var locked: Bool { proto.locked }
    var mustChangePassword: Bool { proto.mustChangePassword }
    var ratingCeiling: Int? { proto.hasRatingCeiling ? Int(proto.ratingCeiling.rawValue) : nil }
    var ratingCeilingLabel: String? { nil }
    var createdAt: String? { nil }
}

struct AdminUserListResponse: Sendable {
    let proto: MMUserListResponse

    var users: [AdminUser] { proto.users.map { AdminUser(proto: $0) } }
}

struct AdminPurchaseWish: Identifiable, Sendable {
    let proto: MMPurchaseWish

    var id: String { "\(proto.tmdbID)-\(proto.mediaType.rawValue)-\(seasonNumber ?? 0)" }
    var tmdbId: TmdbID { TmdbID(proto: proto.tmdbID) }
    var mediaType: MediaType { proto.mediaType.appMediaType ?? .movie }
    var title: String { proto.title }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var releaseYear: Int? { proto.hasReleaseYear ? Int(proto.releaseYear) : nil }
    var seasonNumber: Int? { proto.hasSeasonNumber ? Int(proto.seasonNumber) : nil }
    var voteCount: Int { Int(proto.voteCount) }
    var voters: [String] { proto.voters }
    var acquisitionStatus: AcquisitionStatus? { proto.acquisitionStatus.appStatus }
    var lifecycleStage: WishLifecycleStage? { proto.lifecycleStage.appStage }
    var titleId: TitleID? { proto.hasTitleID ? TitleID(proto: proto.titleID) : nil }
}

struct AdminPurchaseWishListResponse: Sendable {
    let proto: MMPurchaseWishListResponse

    var wishes: [AdminPurchaseWish] { proto.wishes.map { AdminPurchaseWish(proto: $0) } }
}

struct AdminDataQualityTitle: Identifiable, Sendable {
    let proto: MMDataQualityItem

    var id: TitleID { TitleID(proto: proto.titleID) }
    var name: String { proto.name }
    var mediaType: MediaType? { proto.mediaType.appMediaType }
    var enrichmentStatus: String? { proto.enrichmentStatus.displayString }
    var tmdbId: TmdbID? { nil }
    var releaseYear: Int? { nil }
    var contentRating: String? { nil }
    var posterUrl: String? { proto.hasPosterURL ? proto.posterURL : nil }
    var hidden: Bool { false }
    var createdAt: String? { nil }
}

struct AdminDataQualityResponse: Sendable {
    let proto: MMDataQualityResponse

    var titles: [AdminDataQualityTitle] { proto.items.map { AdminDataQualityTitle(proto: $0) } }
    var total: Int { Int(proto.pagination.total) }
    var page: Int { Int(proto.pagination.page) }
    var limit: Int { Int(proto.pagination.limit) }
    var totalPages: Int { Int(proto.pagination.totalPages) }
}

struct AdminSettingsResponse: Sendable {
    let proto: MMSettingsResponse

    var settings: [String: String?] {
        var dict: [String: String?] = [:]
        for setting in proto.settings {
            if let key = setting.key.configKey {
                dict[key] = setting.value.isEmpty ? nil : setting.value
            }
        }
        return dict
    }
    var buddyKeys: [AdminBuddyKey] { [] } // Not in gRPC proto yet
}

struct AdminBuddyKey: Identifiable, Sendable {
    var id: BuddyKeyID
    var name: String
    var createdAt: String?
}

struct AdminLinkedTranscode: Identifiable, Sendable {
    let proto: MMLinkedTranscodeItem

    var id: TranscodeID { TranscodeID(proto: proto.transcodeID) }
    var transcodeId: TranscodeID { id }
    var titleId: TitleID { TitleID(proto: proto.titleID) }
    var titleName: String { proto.titleName }
    var mediaType: MediaType? { nil }
    var posterUrl: String? { nil }
    var filePath: String? { proto.hasFilePath ? proto.filePath : nil }
    var mediaFormat: String? { proto.mediaFormat.displayString }
    var seasonNumber: Int? { nil }
    var episodeNumber: Int? { nil }
    var episodeName: String? { nil }
    var retranscodeRequested: Bool? { nil }
}

struct AdminLinkedTranscodeResponse: Sendable {
    let proto: MMLinkedTranscodeResponse

    var transcodes: [AdminLinkedTranscode] { proto.transcodes.map { AdminLinkedTranscode(proto: $0) } }
    var total: Int { Int(proto.pagination.total) }
    var page: Int { Int(proto.pagination.page) }
    var limit: Int { Int(proto.pagination.limit) }
    var totalPages: Int { Int(proto.pagination.totalPages) }
}

struct AdminUnmatchedFile: Identifiable, Sendable {
    let proto: MMUnmatchedFile

    var id: UnmatchedFileID { UnmatchedFileID(proto: proto.id) }
    var fileName: String { String(proto.filePath.split(separator: "/").last ?? "") }
    var directory: String? { nil }
    var mediaType: MediaType? { nil }
    var parsedTitle: String? { nil }
    var parsedYear: Int? { nil }
    var parsedSeason: Int? { nil }
    var parsedEpisode: Int? { nil }
    var suggestions: [AdminMatchSuggestion] {
        if proto.hasSuggestedTitle {
            [AdminMatchSuggestion(titleId: TitleID(proto: proto.suggestedTitleID),
                                  titleName: proto.suggestedTitle,
                                  score: proto.matchScore)]
        } else { [] }
    }
}

struct AdminMatchSuggestion: Identifiable, Sendable {
    var id: TitleID { titleId }
    let titleId: TitleID
    let titleName: String
    let score: Double
}

struct AdminUnmatchedResponse: Sendable {
    let proto: MMUnmatchedResponse

    var unmatched: [AdminUnmatchedFile] { proto.unmatched.map { AdminUnmatchedFile(proto: $0) } }
    var total: Int { Int(proto.total) }
}

// MARK: - Admin Enum Display Extensions

extension MMLeaseStatus {
    var displayString: String? {
        switch self {
        case .claimed: "CLAIMED"
        case .inProgress: "IN_PROGRESS"
        case .completed: "COMPLETED"
        case .failed: "FAILED"
        case .expired: "EXPIRED"
        default: nil
        }
    }
}

extension MMLeaseType {
    var displayString: String? {
        switch self {
        case .transcode: "TRANSCODE"
        case .thumbnails: "THUMBNAILS"
        case .subtitles: "SUBTITLES"
        case .chapters: "CHAPTERS"
        case .lowStorageTranscode: "MOBILE_TRANSCODE"
        default: nil
        }
    }
}

extension MMEnrichmentStatus {
    var displayString: String? {
        switch self {
        case .pending: "PENDING"
        case .enriched: "ENRICHED"
        case .skipped: "SKIPPED"
        case .failed: "FAILED"
        case .reassignmentRequested: "REASSIGNMENT_REQUESTED"
        case .abandoned: "ABANDONED"
        default: nil
        }
    }
}

extension MMScanStatus {
    var displayString: String {
        switch self {
        case .submitted: "Submitted"
        case .upcFound: "Found"
        case .upcNotFound: "Not Found"
        case .enriching: "Enriching..."
        case .enriched: "Ready"
        case .enrichmentFailed: "Enrichment Failed"
        case .noMatch: "No Match"
        case .unknown, .UNRECOGNIZED: "Unknown"
        }
    }

    var isTerminal: Bool {
        switch self {
        case .upcNotFound, .enriched, .enrichmentFailed, .noMatch: true
        default: false
        }
    }

    var needsTmdbAction: Bool {
        switch self {
        case .noMatch, .enrichmentFailed: true
        default: false
        }
    }
}

extension MMSubmitBarcodeResult {
    var displayString: String {
        switch self {
        case .created: "Created"
        case .duplicate: "Duplicate"
        case .invalid: "Invalid"
        case .unknown, .UNRECOGNIZED: "Unknown"
        }
    }
}

extension MMSettingKey {
    var configKey: String? {
        switch self {
        case .nasRootPath: "nas_root_path"
        case .ffmpegPath: "ffmpeg_path"
        case .go2RtcPath: "go2rtc_path"
        case .rokuBaseURL: "roku_base_url"
        case .go2RtcApiPort: "go2rtc_api_port"
        case .personalVideoEnabled: "personal_video_enabled"
        case .forMobileEnabled: "for_mobile_enabled"
        case .keepaEnabled: "keepa_enabled"
        case .personalVideoNasDir: "personal_video_nas_dir"
        case .buddyLeaseDurationMinutes: "buddy_lease_duration_minutes"
        case .keepaApiKey: "keepa_api_key"
        case .keepaTokensPerMinute: "keepa_tokens_per_minute"
        case .liveTvMinRating: "live_tv_min_rating"
        case .liveTvMaxStreams: "live_tv_max_streams"
        case .liveTvIdleTimeoutSeconds: "live_tv_idle_timeout_seconds"
        default: nil
        }
    }
}

// MARK: - AcquisitionStatus proto mapping

extension AcquisitionStatus {
    var protoValue: MMAcquisitionStatus {
        switch self {
        case .unknown: .unknown
        case .notAvailable: .notAvailable
        case .rejected: .rejected
        case .ordered: .ordered
        case .owned: .owned
        case .needsAssistance: .needsAssistance
        }
    }
}

extension MMSettingKey {
    static func fromConfigKey(_ key: String) -> MMSettingKey {
        switch key {
        case "nas_root_path": .nasRootPath
        case "ffmpeg_path": .ffmpegPath
        case "go2rtc_path": .go2RtcPath
        case "roku_base_url": .rokuBaseURL
        case "go2rtc_api_port": .go2RtcApiPort
        case "personal_video_enabled": .personalVideoEnabled
        case "for_mobile_enabled": .forMobileEnabled
        case "keepa_enabled": .keepaEnabled
        case "personal_video_nas_dir": .personalVideoNasDir
        case "buddy_lease_duration_minutes": .buddyLeaseDurationMinutes
        case "keepa_api_key": .keepaApiKey
        case "keepa_tokens_per_minute": .keepaTokensPerMinute
        case "live_tv_min_rating": .liveTvMinRating
        case "live_tv_max_streams": .liveTvMaxStreams
        case "live_tv_idle_timeout_seconds": .liveTvIdleTimeoutSeconds
        default: .unknown
        }
    }
}

// ID wrapper proto initializers are in Types.swift
