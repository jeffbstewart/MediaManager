import Foundation
import MediaManagerProtos

// MARK: - Proto Enum Display Extensions

public extension MMQuality {
    public var displayString: String? {
        switch self {
        case .sd: return "SD"
        case .fhd: return "FHD"
        case .uhd: return "UHD"
        case .unknown, .UNRECOGNIZED: return nil
        }
    }
}

public extension MMContentRating {
    public var displayString: String? {
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

public extension MMMediaFormat {
    public var displayString: String? {
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

public extension MMMediaType {
    public var appMediaType: MediaType? {
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

public extension MediaType {
    /// Conversion to the proto enum, used when constructing requests or
    /// `MMImageRef.tmdbPoster(tmdbId:mediaType:)` from app-side state.
    public var protoMediaType: MMMediaType {
        switch self {
        case .movie: return .movie
        case .tv: return .tv
        case .personal: return .personal
        }
    }
}

public extension MMSearchResultType {
    public var appType: SearchResultType? {
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
        case .book, .album, .artist, .author, .track, .personal, .channel, .camera, .playlist: return nil
        case .unknown, .UNRECOGNIZED: return nil
        }
    }

    public var displayString: String {
        switch self {
        case .movie: return "movie"
        case .series: return "series"
        case .actor: return "actor"
        case .collection: return "collection"
        case .tag: return "tag"
        case .genre: return "genre"
        case .book: return "book"
        case .album: return "album"
        case .playlist: return "playlist"
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

public extension MMAcquisitionStatus {
    public var appStatus: AcquisitionStatus? {
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

public extension MMWishLifecycleStage {
    public var appStage: WishLifecycleStage? {
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

public extension MMWishStatus {
    public var displayString: String? {
        switch self {
        case .active: return "active"
        case .fulfilled: return "fulfilled"
        case .unknown, .UNRECOGNIZED: return nil
        }
    }
}

public extension MMAuthMethod {
    public var displayString: String {
        switch self {
        case .jwt: return "jwt"
        case .unknown, .UNRECOGNIZED: return "unknown"
        }
    }
}

public extension MMCapability {
    public var displayString: String {
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

public extension MMTimestamp {
    public var date: Date {
        Date(timeIntervalSince1970: TimeInterval(secondsSinceEpoch))
    }

    public var isoString: String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }
}

// MARK: - Helper: MMCalendarDate -> String

public extension MMCalendarDate {
    /// Formats as "YYYY-MM-DD" for compatibility with old API string dates.
    public var dateString: String? {
        guard year > 0, month != .unknown else { return nil }
        return String(format: "%04d-%02d-%02d", year, month.rawValue, day)
    }
}

// MARK: - CatalogModels Adapters

public struct ApiTitle: Identifiable, Hashable, Sendable {
    public let proto: MMTitle
    public init(proto: MMTitle) { self.proto = proto }

    public var id: TitleID { TitleID(proto: Int64(proto.id)) }
    public var name: String { proto.name }
    public var mediaType: MediaType { proto.mediaType.appMediaType ?? .movie }
    /// True when the underlying proto media_type is BOOK. Distinct from
    /// `mediaType`, which collapses BOOK / ALBUM to .movie because the
    /// app-side `MediaType` enum doesn't model them yet. Used to dispatch
    /// navigation to `BookDetailView` instead of the movie-centric
    /// `TitleDetailView`.
    public var isBook: Bool { proto.mediaType == .book }
    /// Audio counterpart to isBook — routes ApiTitle navigation to
    /// AlbumDetailView (which has tracks, square cover, play buttons)
    /// instead of the movie-shaped TitleDetailView.
    public var isAlbum: Bool { proto.mediaType == .album }
    public var year: Int? { proto.hasYear ? Int(proto.year) : nil }
    public var description: String? { proto.hasDescription_p ? proto.description_p : nil }
    public var backdropUrl: String? { proto.hasBackdropURL ? proto.backdropURL : nil }
    public var contentRating: String? { proto.contentRating.displayString }
    public var popularity: Double? { proto.hasPopularity ? proto.popularity : nil }
    public var quality: String? { proto.quality.displayString }
    public var playable: Bool { proto.playable }
    public var transcodeId: TranscodeID? { proto.hasTranscodeID ? TranscodeID(proto: proto.transcodeID) : nil }
    public var tmdbId: TmdbID? { proto.hasTmdbID ? TmdbID(proto: proto.tmdbID) : nil }
    public var tmdbCollectionId: TmdbCollectionID? { proto.hasTmdbCollectionID ? TmdbCollectionID(proto: proto.tmdbCollectionID) : nil }
    public var tmdbCollectionName: String? { proto.hasTmdbCollectionName ? proto.tmdbCollectionName : nil }
    public var familyMembers: [String]? { proto.familyMembers.isEmpty ? nil : proto.familyMembers }
    public var forMobileAvailable: Bool? { proto.lowStorageTranscodeAvailable }

    // Resume Playing fields (populated only in "Resume Playing" carousel)
    public var resumePositionSeconds: Double? { proto.hasResumePosition ? proto.resumePosition.seconds : nil }
    public var resumeDurationSeconds: Double? { proto.hasResumeDuration ? proto.resumeDuration.seconds : nil }
    public var resumeSeasonNumber: Int? { proto.hasResumeSeasonNumber ? Int(proto.resumeSeasonNumber) : nil }
    public var resumeEpisodeNumber: Int? { proto.hasResumeEpisodeNumber ? Int(proto.resumeEpisodeNumber) : nil }
    public var resumeEpisodeName: String? { proto.hasResumeEpisodeName ? proto.resumeEpisodeName : nil }

    public var resumeProgress: Double? {
        guard let pos = resumePositionSeconds, let dur = resumeDurationSeconds, dur > 0 else { return nil }
        return pos / dur
    }

    /// Convenience init for constructing ApiTitle from fields (used by views like DownloadsView).
    /// Image artwork is intentionally NOT a parameter — consumers fetch via
    /// ImageService keyed by `id` (and `tmdbId` for unfulfilled wishes).
    public init(id: TitleID, name: String, mediaType: MediaType, year: Int? = nil,
         description: String? = nil, backdropUrl: String? = nil,
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

    public static func == (lhs: ApiTitle, rhs: ApiTitle) -> Bool {
        lhs.proto.id == rhs.proto.id
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(proto.id)
    }
}

public struct ApiCarousel: Sendable {
    public let proto: MMCarousel
    public init(proto: MMCarousel) { self.proto = proto }

    public var name: String { proto.name }
    public var items: [ApiTitle] { proto.items.map { ApiTitle(proto: $0) } }
}

public struct ApiHomeFeed: Sendable {
    public let proto: MMHomeFeedResponse
    public init(proto: MMHomeFeedResponse) { self.proto = proto }

    public var carousels: [ApiCarousel] { proto.carousels.map { ApiCarousel(proto: $0) } }
    public var missingSeasons: [ApiMissingSeason]? {
        proto.missingSeasons.isEmpty ? nil : proto.missingSeasons.map { ApiMissingSeason(proto: $0) }
    }
    /// Books with active reading progress, newest first. Drives the
    /// "Continue Reading" carousel on HomeView. Server populates
    /// from the reading_progress table joined to MediaItem + Title.
    public var resumeReading: [ApiResumeReading] {
        proto.resumeReading.map { ApiResumeReading(proto: $0) }
    }
    /// Newest-first audio albums. Powers the Music landing page's
    /// "Recently Added" carousel. Server filters out per-user
    /// dismissals so the row goes empty (and the section hides) when
    /// the user has waved off everything.
    public var recentlyAddedAlbums: [ApiTitle] {
        proto.recentlyAddedAlbums.map { ApiTitle(proto: $0) }
    }
}

/// One row in the home page's "Continue Reading" carousel. Card art
/// uses `MMImageRef.posterThumbnail(titleId:)`; tapping a card
/// navigates straight into `BookReaderView` since the user's intent
/// is unambiguous when they pick from a resume list.
public struct ApiResumeReading: Identifiable, Sendable {
    public let proto: MMResumeReading
    public init(proto: MMResumeReading) { self.proto = proto }

    public var id: Int64 { proto.mediaItemID }
    public var mediaItemId: Int64 { proto.mediaItemID }
    public var titleId: TitleID { TitleID(proto: proto.titleID) }
    public var titleName: String { proto.titleName }
    public var percent: Double { proto.percent }
}

public struct ApiMissingSeason: Identifiable, Sendable {
    public let proto: MMMissingSeason
    public init(proto: MMMissingSeason) { self.proto = proto }

    public var id: TitleID { titleId }
    public var titleId: TitleID { TitleID(proto: proto.titleID) }
    public var titleName: String { proto.titleName }
    public var tmdbId: TmdbID? { proto.hasTmdbID ? TmdbID(proto: proto.tmdbID) : nil }
    public var mediaType: MediaType? { proto.mediaType.appMediaType }
    public var seasons: [ApiMissingSeasonEntry] { proto.seasons.map { ApiMissingSeasonEntry(proto: $0) } }
}

public struct ApiMissingSeasonEntry: Identifiable, Sendable {
    public let proto: MMMissingSeasonEntry
    public init(proto: MMMissingSeasonEntry) { self.proto = proto }

    public var id: Int { seasonNumber }
    public var seasonNumber: Int { Int(proto.seasonNumber) }
    public var name: String? { proto.hasName ? proto.name : nil }
    public var episodeCount: Int? { proto.hasEpisodeCount ? Int(proto.episodeCount) : nil }
}

public struct ApiTitlePage: Sendable {
    public let proto: MMTitlePageResponse
    public init(proto: MMTitlePageResponse) { self.proto = proto }

    public var titles: [ApiTitle] { proto.titles.map { ApiTitle(proto: $0) } }
    public var total: Int { proto.hasPagination ? Int(proto.pagination.total) : 0 }
    public var page: Int { proto.hasPagination ? Int(proto.pagination.page) : 0 }
    public var limit: Int { proto.hasPagination ? Int(proto.pagination.limit) : 0 }
    public var totalPages: Int { proto.hasPagination ? Int(proto.pagination.totalPages) : 0 }
}

public struct ApiCastMember: Identifiable, Sendable {
    public let proto: MMCastMember
    public init(proto: MMCastMember) { self.proto = proto }

    public var id: TmdbPersonID { tmdbPersonId }
    public var tmdbPersonId: TmdbPersonID { TmdbPersonID(proto: proto.tmdbPersonID) }
    public var name: String { proto.name }
    public var characterName: String? { proto.hasCharacterName ? proto.characterName : nil }
    public var headshotUrl: String? { proto.hasHeadshotURL ? proto.headshotURL : nil }
    public var order: Int { Int(proto.order) }
}

public struct ApiGenre: Identifiable, Sendable {
    public let proto: MMGenre
    public init(proto: MMGenre) { self.proto = proto }

    public var id: GenreID { GenreID(proto: proto.id) }
    public var name: String { proto.name }
}

public struct ApiTag: Identifiable, Sendable {
    public let proto: MMTag
    public init(proto: MMTag) { self.proto = proto }

    public var id: TagID { TagID(proto: proto.id) }
    public var name: String { proto.name }
    public var color: String { proto.color.hex }
}

public struct ApiTranscode: Identifiable, Sendable {
    public let proto: MMTranscode
    public init(proto: MMTranscode) { self.proto = proto }

    public var id: TranscodeID { TranscodeID(proto: proto.id) }
    public var mediaFormat: String? { proto.mediaFormat.displayString }
    public var quality: String { proto.quality.displayString ?? "SD" }
    public var episodeId: EpisodeID? { proto.hasEpisodeID ? EpisodeID(proto: proto.episodeID) : nil }
    public var seasonNumber: Int? { proto.hasSeasonNumber ? Int(proto.seasonNumber) : nil }
    public var episodeNumber: Int? { proto.hasEpisodeNumber ? Int(proto.episodeNumber) : nil }
    public var episodeName: String? { proto.hasEpisodeName ? proto.episodeName : nil }
    public var playable: Bool { proto.playable }
    public var hasSubtitles: Bool { proto.hasSubtitles_p }
    public var forMobileAvailable: Bool? { proto.lowStorageTranscodeAvailable }
    public var forMobileRequested: Bool? { proto.lowStorageTranscodeRequested }
}

public struct ApiPlaybackProgress: Sendable {
    public let proto: MMPlaybackProgress
    public init(proto: MMPlaybackProgress) { self.proto = proto }

    public var transcodeId: TranscodeID { TranscodeID(proto: proto.transcodeID) }
    public var positionSeconds: Double { proto.hasPosition ? proto.position.seconds : 0 }
    public var durationSeconds: Double? { proto.hasDuration ? proto.duration.seconds : nil }
    public var updatedAt: String? { proto.hasUpdatedAt ? proto.updatedAt.isoString : nil }
}

public struct ApiTitleDetail: Sendable {
    public let proto: MMTitleDetail
    public init(proto: MMTitleDetail) { self.proto = proto }

    // Flattened from proto.title
    private var t: MMTitle { proto.title }

    public var id: TitleID { TitleID(proto: Int64(t.id)) }
    public var name: String { t.name }
    public var mediaType: MediaType { t.mediaType.appMediaType ?? .movie }
    public var isBook: Bool { t.mediaType == .book }
    public var year: Int? { t.hasYear ? Int(t.year) : nil }
    public var description: String? { t.hasDescription_p ? t.description_p : nil }
    public var backdropUrl: String? { t.hasBackdropURL ? t.backdropURL : nil }
    public var contentRating: String? { t.contentRating.displayString }
    public var popularity: Double? { t.hasPopularity ? t.popularity : nil }
    public var quality: String? { t.quality.displayString }
    public var playable: Bool { t.playable }
    public var transcodeId: TranscodeID? { t.hasTranscodeID ? TranscodeID(proto: t.transcodeID) : nil }
    public var tmdbId: TmdbID? { t.hasTmdbID ? TmdbID(proto: t.tmdbID) : nil }
    public var tmdbCollectionId: TmdbCollectionID? { t.hasTmdbCollectionID ? TmdbCollectionID(proto: t.tmdbCollectionID) : nil }
    public var tmdbCollectionName: String? { t.hasTmdbCollectionName ? t.tmdbCollectionName : nil }
    public var familyMembers: [String]? { t.familyMembers.isEmpty ? nil : t.familyMembers }
    public var forMobileAvailable: Bool? { t.lowStorageTranscodeAvailable }

    // Detail-only fields
    public var cast: [ApiCastMember] { proto.cast.map { ApiCastMember(proto: $0) } }
    public var genres: [ApiGenre] { proto.genres.map { ApiGenre(proto: $0) } }
    public var tags: [ApiTag] { proto.tags.map { ApiTag(proto: $0) } }
    public var transcodes: [ApiTranscode] { proto.transcodes.map { ApiTranscode(proto: $0) } }
    public var playbackProgress: ApiPlaybackProgress? {
        proto.hasPlaybackProgress ? ApiPlaybackProgress(proto: proto.playbackProgress) : nil
    }
    public var isFavorite: Bool? { proto.isFavorite }
    public var isHidden: Bool? { proto.isHidden }
    public var wished: Bool? { proto.wished }

    /// Populated only when `isBook` — author list, editions, series link,
    /// reading progress, page count, first publication year. Returns nil
    /// for non-book titles.
    public var book: ApiBookDetail? {
        proto.hasBook ? ApiBookDetail(proto: proto.book) : nil
    }

    /// Populated only when `isAlbum` — tracks, album artists, label,
    /// MusicBrainz IDs, personnel. Returns nil for non-album titles.
    public var album: ApiAlbum? {
        proto.hasAlbum ? ApiAlbum(proto: proto.album) : nil
    }

    /// Single primary author name from the embedded Title row. Books
    /// always have at least one credit; the server populates the
    /// primary author here so list cards don't need to walk
    /// `book.authors`.
    public var authorName: String? { t.hasAuthorName ? t.authorName : nil }

    /// Series link from the embedded Title row. Both fields are populated
    /// together; nil when the book is a standalone work.
    public var seriesName: String? { t.hasSeriesName ? t.seriesName : nil }
    public var seriesNumber: String? { t.hasSeriesNumber ? t.seriesNumber : nil }
}

public struct ApiSearchResult: Identifiable, Sendable {
    public let proto: MMSearchResult
    public init(proto: MMSearchResult) { self.proto = proto }

    public var id: String {
        "\(resultType)-\(name)-\(titleId?.rawValue ?? 0)-\(itemId ?? 0)"
    }
    public var resultType: String { proto.resultType.displayString }
    public var name: String { proto.name }
    public var titleId: TitleID? { proto.hasTitleID ? TitleID(proto: proto.titleID) : nil }
    public var year: Int? { proto.hasYear ? Int(proto.year) : nil }
    public var quality: String? { proto.quality.displayString }
    public var contentRating: String? { proto.contentRating.displayString }
    public var transcodeId: TranscodeID? { proto.hasTranscodeID ? TranscodeID(proto: proto.transcodeID) : nil }
    public var mediaType: MediaType? { proto.mediaType.appMediaType }
    public var tmdbCollectionId: TmdbCollectionID? { proto.hasTmdbCollectionID ? TmdbCollectionID(proto: proto.tmdbCollectionID) : nil }
    public var tmdbPersonId: TmdbPersonID? { proto.hasTmdbPersonID ? TmdbPersonID(proto: proto.tmdbPersonID) : nil }
    public var headshotUrl: String? { proto.hasHeadshotURL ? proto.headshotURL : nil }
    public var titleCount: Int? { proto.hasTitleCount ? Int(proto.titleCount) : nil }
    public var itemId: Int? { proto.hasItemID ? Int(proto.itemID) : nil }
    public var authorId: AuthorID? { proto.hasAuthorID ? AuthorID(rawValue: Int(proto.authorID)) : nil }
    /// Local Artist row id for ARTIST results. Always present on
    /// owned-artist hits; tap routes to ArtistDetailView.
    public var artistId: ArtistID? {
        proto.hasArtistID ? ArtistID(proto: proto.artistID) : nil
    }
    /// Track-level id for TRACK results. The parent album's title id
    /// is on `albumTitleId` — the iOS row navigates to AlbumDetailView
    /// keyed by that, since there's no per-track detail surface and
    /// "tap a track in search results" matches Apple Music's behaviour
    /// of opening the parent album.
    public var trackId: Int64? { proto.hasTrackID ? proto.trackID : nil }
    public var albumTitleId: TitleID? {
        proto.hasAlbumTitleID ? TitleID(proto: proto.albumTitleID) : nil
    }
    /// Context line for ALBUM and TRACK results — the album-credit
    /// artist (e.g. "Taylor Swift" under "Folklore"). Falls back to
    /// per-track artist when the album-level credit is empty.
    public var artistName: String? {
        proto.hasArtistName ? proto.artistName : nil
    }
    /// Context line for TRACK results — the parent album's name.
    /// (ALBUM rows already carry the name in `name`.)
    public var albumName: String? {
        proto.hasAlbumName ? proto.albumName : nil
    }
    /// Server playlist row id for PLAYLIST results. Routes to the
    /// playlist tracklist via dataModel.playlist(id:). Server only
    /// populates this when SearchRequest.includePlaylists is set.
    public var playlistId: Int64? {
        proto.hasPlaylistID ? proto.playlistID : nil
    }
}

public struct ApiSearchResponse: Sendable {
    public let proto: MMSearchResponse
    public init(proto: MMSearchResponse) { self.proto = proto }

    public var query: String { proto.query }
    public var results: [ApiSearchResult] { proto.results.map { ApiSearchResult(proto: $0) } }
    public var counts: [String: Int] { proto.counts.mapValues { Int($0) } }
}

// MARK: - Advanced (dance) search

/// Server-curated dance preset — Slow Waltz, Cha-Cha, etc. Tapping a
/// chip in the advanced-search sheet pre-fills the BPM range and
/// time signature. Shared source of truth with the web app, so iOS
/// and the SPA agree on what counts as "Salsa tempo".
public struct ApiAdvancedSearchPreset: Identifiable, Sendable, Hashable {
    public let proto: MMAdvancedSearchPreset
    public init(proto: MMAdvancedSearchPreset) { self.proto = proto }

    public var id: String { proto.key }
    public var key: String { proto.key }
    public var name: String { proto.name }
    /// Short blurb shown under the chip name as a subtitle.
    public var description: String { proto.description_p }
    public var bpmMin: Int? { proto.hasBpmMin ? Int(proto.bpmMin) : nil }
    public var bpmMax: Int? { proto.hasBpmMax ? Int(proto.bpmMax) : nil }
    public var timeSignature: String? {
        proto.hasTimeSignature ? proto.timeSignature : nil
    }
}

/// One row of advanced-search results — track-level hit, with bpm
/// and time-signature surfaced so the user can verify the match.
public struct ApiTrackSearchHit: Identifiable, Sendable {
    public let proto: MMTrackSearchHit
    public init(proto: MMTrackSearchHit) { self.proto = proto }

    public var id: Int64 { proto.trackID }
    public var trackId: Int64 { proto.trackID }
    /// Parent album's title id — drives artwork (square) and the
    /// "tap a row → land on its album" navigation.
    public var titleId: Int64 { proto.titleID }
    public var name: String { proto.name }
    public var albumName: String { proto.albumName }
    public var artistName: String? {
        proto.hasArtistName ? proto.artistName : nil
    }
    public var bpm: Int? { proto.hasBpm ? Int(proto.bpm) : nil }
    public var timeSignature: String? {
        proto.hasTimeSignature ? proto.timeSignature : nil
    }
    public var durationSeconds: Int? {
        proto.hasDurationSeconds ? Int(proto.durationSeconds) : nil
    }
    public var playable: Bool { proto.playable }
}

/// Composed filter record passed from the AdvancedSearchSheet to
/// the results surface. nil / empty fields mean "don't restrict".
public struct AdvancedTrackSearchFilters: Sendable, Hashable {
    public var query: String?
    public var bpmMin: Int?
    public var bpmMax: Int?
    public var timeSignature: String?

    public init(query: String? = nil, bpmMin: Int? = nil, bpmMax: Int? = nil, timeSignature: String? = nil) {
        self.query = query
        self.bpmMin = bpmMin
        self.bpmMax = bpmMax
        self.timeSignature = timeSignature
    }

    public var isEmpty: Bool {
        (query?.isEmpty ?? true)
            && bpmMin == nil
            && bpmMax == nil
            && (timeSignature?.isEmpty ?? true)
    }
}

// MARK: - List Endpoints

public struct ApiCollectionListItem: Identifiable, Sendable {
    public let proto: MMCollectionListItem
    public init(proto: MMCollectionListItem) { self.proto = proto }

    public var id: TmdbCollectionID { tmdbCollectionId }
    public var tmdbCollectionId: TmdbCollectionID { TmdbCollectionID(proto: proto.tmdbCollectionID) }
    public var name: String { proto.name }
    public var titleCount: Int { Int(proto.titleCount) }
}

public struct ApiCollectionListResponse: Sendable {
    public let proto: MMCollectionListResponse
    public init(proto: MMCollectionListResponse) { self.proto = proto }

    public var collections: [ApiCollectionListItem] { proto.collections.map { ApiCollectionListItem(proto: $0) } }
}

public struct ApiTagListItem: Identifiable, Sendable {
    public let id: TagID
    public let name: String
    public let color: String
    public let titleCount: Int

    public init(proto: MMTagListItem) {
        id = TagID(proto: proto.id)
        name = proto.name
        color = proto.color.hex
        titleCount = Int(proto.titleCount)
    }

    public init(adminProto tag: MMAdminTagListItem) {
        id = TagID(proto: tag.id)
        name = tag.name
        color = tag.color.hex
        titleCount = Int(tag.titleCount)
    }
}

public struct ApiTagListResponse: Sendable {
    public let tags: [ApiTagListItem]

    public init(proto: MMTagListResponse) {
        tags = proto.tags.map { ApiTagListItem(proto: $0) }
    }

    public init(tags: [ApiTagListItem]) {
        self.tags = tags
    }
}

// ApiTagListResponse defined above with both proto and manual inits

// MARK: - Browse/Landing Pages

public struct ApiActorDetail: Sendable {
    public let proto: MMActorDetail
    public init(proto: MMActorDetail) { self.proto = proto }

    public var name: String { proto.name }
    public var headshotUrl: String? { proto.hasHeadshotURL ? proto.headshotURL : nil }
    public var biography: String? { proto.hasBiography ? proto.biography : nil }
    public var birthday: String? { proto.hasBirthday ? proto.birthday.dateString : nil }
    public var deathday: String? { proto.hasDeathday ? proto.deathday.dateString : nil }
    public var placeOfBirth: String? { proto.hasPlaceOfBirth ? proto.placeOfBirth : nil }
    public var knownForDepartment: String? { proto.hasKnownForDepartment ? proto.knownForDepartment : nil }
    public var ownedTitles: [ApiOwnedCredit] { proto.ownedTitles.map { ApiOwnedCredit(proto: $0) } }
    public var otherWorks: [ApiCreditEntry] { proto.otherWorks.map { ApiCreditEntry(proto: $0) } }
}

public struct ApiOwnedCredit: Sendable {
    public let proto: MMOwnedCredit
    public init(proto: MMOwnedCredit) { self.proto = proto }

    public var title: ApiTitle { ApiTitle(proto: proto.title) }
    public var characterName: String? { proto.hasCharacterName ? proto.characterName : nil }
}

public struct ApiCreditEntry: Identifiable, Sendable {
    public let proto: MMCreditEntry
    public init(proto: MMCreditEntry) { self.proto = proto }

    public var id: String { "\(tmdbId.rawValue)-\(mediaType.rawValue)" }
    public var tmdbId: TmdbID { TmdbID(proto: proto.tmdbID) }
    public var title: String { proto.title }
    public var mediaType: MediaType { proto.mediaType.appMediaType ?? .movie }
    public var characterName: String? { proto.hasCharacterName ? proto.characterName : nil }
    public var releaseYear: Int? { proto.hasReleaseYear ? Int(proto.releaseYear) : nil }
    public var popularity: Double { proto.popularity }
    public var wished: Bool { proto.wished }
}

public struct ApiCollectionDetail: Sendable {
    public let proto: MMCollectionDetail
    public init(proto: MMCollectionDetail) { self.proto = proto }

    public var name: String { proto.name }
    public var items: [ApiCollectionItem] { proto.items.map { ApiCollectionItem(proto: $0) } }
}

public struct ApiCollectionItem: Identifiable, Sendable {
    public let proto: MMCollectionItem
    public init(proto: MMCollectionItem) { self.proto = proto }

    public var id: TmdbID { tmdbMovieId }
    public var tmdbMovieId: TmdbID { TmdbID(proto: proto.tmdbMovieID) }
    public var name: String { proto.name }
    public var year: Int? { proto.hasYear ? Int(proto.year) : nil }
    public var owned: Bool { proto.owned }
    public var playable: Bool { proto.playable }
    public var titleId: TitleID? { proto.hasTitleID ? TitleID(proto: proto.titleID) : nil }
    public var quality: String? { proto.quality.displayString }
    public var contentRating: String? { proto.contentRating.displayString }
    public var transcodeId: TranscodeID? { proto.hasTranscodeID ? TranscodeID(proto: proto.transcodeID) : nil }
}

public struct ApiTagDetail: Sendable {
    public let proto: MMTagDetail
    public init(proto: MMTagDetail) { self.proto = proto }

    public var name: String { proto.name }
    public var color: String { proto.color.hex }
    public var titles: [ApiTitle] { proto.titles.map { ApiTitle(proto: $0) } }
}

public struct ApiGenreDetail: Sendable {
    public let proto: MMGenreDetail
    public init(proto: MMGenreDetail) { self.proto = proto }

    public var name: String { proto.name }
    public var titles: [ApiTitle] { proto.titles.map { ApiTitle(proto: $0) } }
}

// MARK: - TV Shows

public struct ApiSeason: Hashable, Sendable {
    public let proto: MMSeason
    public init(proto: MMSeason) { self.proto = proto }

    public var seasonNumber: Int { Int(proto.seasonNumber) }
    public var name: String? { proto.hasName ? proto.name : nil }
    public var episodeCount: Int { Int(proto.episodeCount) }

    public static func == (lhs: ApiSeason, rhs: ApiSeason) -> Bool {
        lhs.seasonNumber == rhs.seasonNumber
            && lhs.name == rhs.name
            && lhs.episodeCount == rhs.episodeCount
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(seasonNumber)
        hasher.combine(name)
        hasher.combine(episodeCount)
    }
}

public struct ApiEpisode: Sendable {
    public let proto: MMEpisode
    public init(proto: MMEpisode) { self.proto = proto }

    public var episodeId: EpisodeID { EpisodeID(proto: proto.episodeID) }
    public var transcodeId: TranscodeID? { proto.hasTranscodeID ? TranscodeID(proto: proto.transcodeID) : nil }
    public var seasonNumber: Int { Int(proto.seasonNumber) }
    public var episodeNumber: Int { Int(proto.episodeNumber) }
    public var name: String? { proto.hasName ? proto.name : nil }
    public var quality: String? { proto.quality.displayString }
    public var playable: Bool { proto.playable }
    public var hasSubtitles: Bool { proto.hasSubtitles_p }
    public var resumePosition: Double { proto.hasResumePosition ? proto.resumePosition.seconds : 0 }
    public var watchedPercent: Int {
        let duration = proto.hasDuration ? proto.duration.seconds : 0
        guard duration > 0 else { return 0 }
        let position = proto.hasResumePosition ? proto.resumePosition.seconds : 0
        return Int(position / duration * 100)
    }
    public var forMobileAvailable: Bool? { proto.lowStorageTranscodeAvailable }
    public var forMobileRequested: Bool? { proto.lowStorageTranscodeRequested }
}

// MARK: - APIModels Adapters

public struct DiscoverResponse: Sendable {
    public let proto: MMDiscoverResponse
    public init(proto: MMDiscoverResponse) { self.proto = proto }

    public var apiVersions: [String] { proto.apiVersions.map { String($0) } }
    public var authMethods: [String] { proto.authMethods.map { $0.displayString } }
    public var secureUrl: String? { proto.hasSecureURL ? proto.secureURL : nil }
    public var serverFingerprint: String? { proto.serverFingerprint.isEmpty ? nil : proto.serverFingerprint }
}

public struct ServerInfo: Sendable {
    public let proto: MMInfoResponse
    public init(proto: MMInfoResponse) { self.proto = proto }

    public var serverVersion: String { proto.serverVersion }
    public var apiVersion: String { "1" }
    public var capabilities: [String] { proto.capabilities.map { $0.displayString } }
    public var titleCount: Int { Int(proto.titleCount) }
    public var user: ServerUserInfo? { proto.hasUser ? ServerUserInfo(proto: proto.user) : nil }
}

public struct ServerUserInfo: Sendable {
    public let proto: MMServerUserInfo
    public init(proto: MMServerUserInfo) { self.proto = proto }

    public var id: UserID { UserID(proto: proto.id) }
    public var username: String { proto.username }
    public var displayName: String { proto.hasDisplayName ? proto.displayName : proto.username }
    public var isAdmin: Bool { proto.isAdmin }
    public var ratingCeiling: Int? { nil }
    public var ratingCeilingLabel: String? { nil }
    public var fulfilledWishCount: Int? { nil }
    public var passwordChangeRequired: Bool? { nil }
}

public struct AuthResponse: Sendable {
    public let proto: MMTokenResponse
    public init(proto: MMTokenResponse) { self.proto = proto }

    public var accessToken: String { proto.accessToken.base64EncodedString() }
    public var refreshToken: String { proto.refreshToken.base64EncodedString() }
    public var expiresIn: Int { Int(proto.expiresIn) }
}

// MARK: - WishListModels Adapters

public struct ApiWish: Identifiable, Sendable {
    public let proto: MMWishItem
    public init(proto: MMWishItem) { self.proto = proto }

    public var id: String {
        wishId.map { "\($0.rawValue)" } ?? "\(tmdbId?.rawValue ?? 0)-\(mediaType?.rawValue ?? "")-\(seasonNumber ?? 0)"
    }
    public var tmdbId: TmdbID? { proto.tmdbID != 0 ? TmdbID(proto: proto.tmdbID) : nil }
    public var mediaType: MediaType? { proto.mediaType.appMediaType }
    public var title: String { proto.title }
    public var releaseYear: Int? { proto.hasReleaseYear ? Int(proto.releaseYear) : nil }
    public var seasonNumber: Int? { proto.hasSeasonNumber ? Int(proto.seasonNumber) : nil }
    public var voteCount: Int { Int(proto.voteCount) }
    public var voters: [String] { proto.voters }
    public var voted: Bool { proto.userVoted }
    public var wishId: WishID? { proto.id != 0 ? WishID(proto: proto.id) : nil }
    public var acquisitionStatus: AcquisitionStatus? { proto.acquisitionStatus.appStatus }
    public var lifecycleStage: WishLifecycleStage? { proto.lifecycleStage.appStage }
    public var status: String? { proto.status.displayString }
    public var titleId: TitleID? { proto.hasTitleID ? TitleID(proto: proto.titleID) : nil }

    public var isReadyToWatch: Bool { lifecycleStage == .readyToWatch }
}

public struct ApiWishListResponse: Sendable {
    public let proto: MMWishListResponse
    public init(proto: MMWishListResponse) { self.proto = proto }

    public var wishes: [ApiWish] { proto.wishes.map { ApiWish(proto: $0) } }
}

public struct ApiTranscodeWish: Identifiable, Sendable {
    public let proto: MMTranscodeWishItem
    public init(proto: MMTranscodeWishItem) { self.proto = proto }

    public var id: TranscodeID { TranscodeID(proto: proto.titleID) }
    public var titleId: TitleID { TitleID(proto: proto.titleID) }
    public var titleName: String { proto.titleName }
    // poster_url was retired from TranscodeWishItem (proto field 6 is reserved).
    // Clients now derive cover art from title_id via ImageService /
    // /posters/{size}/{title_id}.
    public var mediaType: MediaType? { nil }
    public var requestedAt: String? { nil }
}

public struct ApiTranscodeWishListResponse: Sendable {
    public let proto: MMTranscodeWishListResponse
    public init(proto: MMTranscodeWishListResponse) { self.proto = proto }

    public var transcodeWishes: [ApiTranscodeWish] { proto.wishes.map { ApiTranscodeWish(proto: $0) } }
}

public struct TmdbSearchItem: Identifiable, Sendable {
    public let proto: MMTmdbResult
    public init(proto: MMTmdbResult) { self.proto = proto }

    public var id: String { "\(tmdbId?.rawValue ?? 0)-\(mediaType?.rawValue ?? "")" }
    public var tmdbId: TmdbID? { proto.tmdbID != 0 ? TmdbID(proto: proto.tmdbID) : nil }
    public var title: String? { proto.title.isEmpty ? nil : proto.title }
    public var mediaType: MediaType? { proto.mediaType.appMediaType }
    public var releaseYear: Int? { proto.hasReleaseYear ? Int(proto.releaseYear) : nil }
    public var popularity: Double? { proto.hasPopularity ? proto.popularity : nil }
    public var overview: String? { nil }
}

public struct TmdbSearchResponse: Sendable {
    public let proto: MMTmdbSearchResponse
    public init(proto: MMTmdbSearchResponse) { self.proto = proto }

    public var results: [TmdbSearchItem] { proto.results.map { TmdbSearchItem(proto: $0) } }
}

// MARK: - LiveModels Adapters

public struct ApiCamera: Identifiable, Sendable {
    public let proto: MMCamera
    public init(proto: MMCamera) { self.proto = proto }

    public var id: CameraID { CameraID(proto: proto.id) }
    public var name: String { proto.name }
    public var hlsUrl: String { proto.streamURL }
    public var snapshotUrl: String { proto.hasSnapshotURL ? proto.snapshotURL : "" }
}

public struct ApiCameraListResponse: Sendable {
    public let proto: MMCameraListResponse
    public init(proto: MMCameraListResponse) { self.proto = proto }

    public var cameras: [ApiCamera] { proto.cameras.map { ApiCamera(proto: $0) } }
}

public struct ApiTvChannel: Identifiable, Sendable {
    public let proto: MMTvChannel
    public init(proto: MMTvChannel) { self.proto = proto }

    public var id: ChannelID { ChannelID(proto: proto.id) }
    public var guideNumber: String { proto.number }
    public var guideName: String { proto.name }
    public var networkAffiliation: String? { nil }
    public var receptionQuality: Int { 0 }
    public var hlsUrl: String { proto.streamURL }
}

public struct ApiTvChannelListResponse: Sendable {
    public let proto: MMTvChannelListResponse
    public init(proto: MMTvChannelListResponse) { self.proto = proto }

    public var channels: [ApiTvChannel] { proto.channels.map { ApiTvChannel(proto: $0) } }
}

// MARK: - Profile / Sessions

public struct ProfileResponse: Sendable {
    public let proto: MMProfileResponse
    public init(proto: MMProfileResponse) { self.proto = proto }

    public var username: String? { proto.username.isEmpty ? nil : proto.username }
    public var displayName: String? { proto.hasDisplayName ? proto.displayName : nil }
    public var isAdmin: Bool? { proto.isAdmin }
    public var ratingCeiling: Int? { proto.hasRatingCeiling ? Int(proto.ratingCeiling.rawValue) : nil }
    public var ratingCeilingLabel: String? { proto.hasRatingCeilingLabel ? proto.ratingCeilingLabel : nil }
    public var liveTvMinQuality: Int? { Int(proto.liveTvMinQuality.rawValue) }
    public var subtitlesEnabled: Bool? { proto.subtitlesEnabled }
    public var mustChangePassword: Bool? { proto.mustChangePassword }
    public var roleDisplay: String { (proto.isAdmin) ? "Admin" : "Viewer" }

    public var privacyPolicyVersion: Int? { proto.hasPrivacyPolicyVersion ? Int(proto.privacyPolicyVersion) : nil }
    public var privacyPolicyAcceptedAt: Date? {
        proto.hasPrivacyPolicyAcceptedAt ? Date(timeIntervalSince1970: TimeInterval(proto.privacyPolicyAcceptedAt.secondsSinceEpoch)) : nil
    }
    public var termsOfUseVersion: Int? { proto.hasTermsOfUseVersion ? Int(proto.termsOfUseVersion) : nil }
    public var termsOfUseAcceptedAt: Date? {
        proto.hasTermsOfUseAcceptedAt ? Date(timeIntervalSince1970: TimeInterval(proto.termsOfUseAcceptedAt.secondsSinceEpoch)) : nil
    }
}

public struct ApiSession: Identifiable, Sendable {
    public let proto: MMSessionInfo
    public init(proto: MMSessionInfo) { self.proto = proto }

    public var id: String { "\(proto.id)-\(proto.type.rawValue)" }
    public var sessionId: SessionID { SessionID(proto: proto.id) }
    public var type: String {
        switch proto.type {
        case .browser: "browser"
        case .app: "app"
        case .device: "device"
        default: "unknown"
        }
    }
    public var deviceName: String? { proto.hasDeviceName ? proto.deviceName : nil }
    public var createdAt: String? { proto.hasCreatedAt ? proto.createdAt.isoString : nil }
    public var lastUsedAt: String? { proto.hasLastUsedAt ? proto.lastUsedAt.isoString : nil }
    public var expiresAt: String? { proto.hasExpiresAt ? proto.expiresAt.isoString : nil }
    public var isCurrent: Bool { proto.isCurrent }
}

public struct ApiSessionListResponse: Sendable {
    public let proto: MMSessionListResponse
    public init(proto: MMSessionListResponse) { self.proto = proto }

    public var sessions: [ApiSession] { proto.sessions.map { ApiSession(proto: $0) } }
}

// MARK: - Admin Models

public struct TranscodeStatusResponse: Sendable {
    public let proto: MMTranscodeStatusResponse
    public init(proto: MMTranscodeStatusResponse) { self.proto = proto }

    public var pending: PendingWork { PendingWork(proto: proto) }
    public var activeLeases: [TranscodeLease] { proto.activeLeases.map { TranscodeLease(proto: $0) } }
}

public struct PendingWork: Sendable {
    public let transcodes: Int
    public let mobileTranscodes: Int?
    public let thumbnails: Int
    public let subtitles: Int
    public let chapters: Int
    public let total: Int

    public init(proto: MMTranscodeStatusResponse) {
        transcodes = Int(proto.pendingTranscode)
        mobileTranscodes = Int(proto.pendingLowStorage)
        thumbnails = Int(proto.pendingThumbnails)
        subtitles = Int(proto.pendingSubtitles)
        chapters = Int(proto.pendingChapters)
        total = transcodes + (mobileTranscodes ?? 0) + thumbnails + subtitles + chapters
    }
}

public struct TranscodeLease: Identifiable, Sendable {
    public let proto: MMActiveLease
    public init(proto: MMActiveLease) { self.proto = proto }

    public var id: LeaseID { LeaseID(proto: proto.leaseID) }
    public var leaseId: LeaseID { id }
    public var buddyName: String? { proto.buddyName.isEmpty ? nil : proto.buddyName }
    public var relativePath: String? { proto.relativePath.isEmpty ? nil : proto.relativePath }
    public var leaseType: String? { proto.leaseType.displayString }
    public var status: String? { proto.status.displayString }
    public var progressPercent: Int? { Int(proto.progressPercent) }
    public var encoder: String? { proto.hasEncoder ? proto.encoder : nil }
    public var claimedAt: String? { proto.hasClaimedAt ? proto.claimedAt.isoString : nil }
}

public struct BuddyStatusResponse: Sendable {
    public let proto: MMBuddyStatusResponse
    public init(proto: MMBuddyStatusResponse) { self.proto = proto }

    public var buddies: [BuddyInfo] { proto.buddies.map { BuddyInfo(proto: $0) } }
    public var recentLeases: [RecentLease] { proto.recentLeases.map { RecentLease(proto: $0) } }
}

public struct BuddyInfo: Identifiable, Sendable {
    public let proto: MMBuddyInfo
    public init(proto: MMBuddyInfo) { self.proto = proto }

    public var id: String { proto.name }
    public var name: String? { proto.name.isEmpty ? nil : proto.name }
    public var activeLeases: Int { Int(proto.activeLeases) }
    public var currentWork: [BuddyWork] { [] } // Not in proto; kept for view compat
}

public struct BuddyWork: Identifiable, Sendable {
    public var id: Int { 0 }
    public var leaseId: LeaseID { LeaseID(rawValue: 0) }
    public var relativePath: String? { nil }
    public var leaseType: String? { nil }
    public var progressPercent: Int? { nil }
    public var encoder: String? { nil }
}

public struct RecentLease: Identifiable, Sendable {
    public let proto: MMRecentLease
    public init(proto: MMRecentLease) { self.proto = proto }

    public var id: LeaseID { LeaseID(proto: proto.leaseID) }
    public var leaseId: LeaseID { id }
    public var buddyName: String? { proto.buddyName.isEmpty ? nil : proto.buddyName }
    public var relativePath: String? { proto.relativePath.isEmpty ? nil : proto.relativePath }
    public var leaseType: String? { proto.leaseType.displayString }
    public var status: String? { proto.status.displayString }
    public var encoder: String? { nil }
    public var completedAt: String? { proto.hasCompletedAt ? proto.completedAt.isoString : nil }
    public var errorMessage: String? { nil }
}

public struct AdminUser: Identifiable, Sendable {
    public let proto: MMUserInfo
    public init(proto: MMUserInfo) { self.proto = proto }

    public var id: UserID { UserID(proto: proto.id) }
    public var username: String { proto.username }
    public var displayName: String? { proto.hasDisplayName ? proto.displayName : nil }
    public var accessLevel: Int { Int(proto.accessLevel.rawValue) }
    public var isAdmin: Bool { proto.accessLevel == .admin }
    public var locked: Bool { proto.locked }
    public var mustChangePassword: Bool { proto.mustChangePassword }
    public var ratingCeiling: Int? { proto.hasRatingCeiling ? Int(proto.ratingCeiling.rawValue) : nil }
    public var ratingCeilingLabel: String? { nil }
    public var createdAt: String? { nil }
}

public struct AdminUserListResponse: Sendable {
    public let proto: MMUserListResponse
    public init(proto: MMUserListResponse) { self.proto = proto }

    public var users: [AdminUser] { proto.users.map { AdminUser(proto: $0) } }
}

public struct AdminPurchaseWish: Identifiable, Sendable {
    public let proto: MMPurchaseWish
    public init(proto: MMPurchaseWish) { self.proto = proto }

    public var id: String { "\(proto.tmdbID)-\(proto.mediaType.rawValue)-\(seasonNumber ?? 0)" }
    public var tmdbId: TmdbID { TmdbID(proto: proto.tmdbID) }
    public var mediaType: MediaType { proto.mediaType.appMediaType ?? .movie }
    public var title: String { proto.title }
    public var releaseYear: Int? { proto.hasReleaseYear ? Int(proto.releaseYear) : nil }
    public var seasonNumber: Int? { proto.hasSeasonNumber ? Int(proto.seasonNumber) : nil }
    public var voteCount: Int { Int(proto.voteCount) }
    public var voters: [String] { proto.voters }
    public var acquisitionStatus: AcquisitionStatus? { proto.acquisitionStatus.appStatus }
    public var lifecycleStage: WishLifecycleStage? { proto.lifecycleStage.appStage }
    public var titleId: TitleID? { proto.hasTitleID ? TitleID(proto: proto.titleID) : nil }
}

public struct AdminPurchaseWishListResponse: Sendable {
    public let proto: MMPurchaseWishListResponse
    public init(proto: MMPurchaseWishListResponse) { self.proto = proto }

    public var wishes: [AdminPurchaseWish] { proto.wishes.map { AdminPurchaseWish(proto: $0) } }
}

public struct AdminDataQualityTitle: Identifiable, Sendable {
    public let proto: MMDataQualityItem
    public init(proto: MMDataQualityItem) { self.proto = proto }

    public var id: TitleID { TitleID(proto: proto.titleID) }
    public var name: String { proto.name }
    public var mediaType: MediaType? { proto.mediaType.appMediaType }
    public var enrichmentStatus: String? { proto.enrichmentStatus.displayString }
    public var tmdbId: TmdbID? { nil }
    public var releaseYear: Int? { nil }
    public var contentRating: String? { nil }
    public var hidden: Bool { false }
    public var createdAt: String? { nil }
}

public struct AdminDataQualityResponse: Sendable {
    public let proto: MMDataQualityResponse
    public init(proto: MMDataQualityResponse) { self.proto = proto }

    public var titles: [AdminDataQualityTitle] { proto.items.map { AdminDataQualityTitle(proto: $0) } }
    public var total: Int { Int(proto.pagination.total) }
    public var page: Int { Int(proto.pagination.page) }
    public var limit: Int { Int(proto.pagination.limit) }
    public var totalPages: Int { Int(proto.pagination.totalPages) }
}

public struct AdminSettingsResponse: Sendable {
    public let proto: MMSettingsResponse
    public init(proto: MMSettingsResponse) { self.proto = proto }

    public var settings: [String: String?] {
        var dict: [String: String?] = [:]
        for setting in proto.settings {
            if let key = setting.key.configKey {
                dict[key] = setting.value.isEmpty ? nil : setting.value
            }
        }
        return dict
    }
    public var buddyKeys: [AdminBuddyKey] { [] } // Not in gRPC proto yet
}

public struct AdminBuddyKey: Identifiable, Sendable {
    public var id: BuddyKeyID
    public var name: String
    public var createdAt: String?
}

public struct AdminLinkedTranscode: Identifiable, Sendable {
    public let proto: MMLinkedTranscodeItem
    public init(proto: MMLinkedTranscodeItem) { self.proto = proto }

    public var id: TranscodeID { TranscodeID(proto: proto.transcodeID) }
    public var transcodeId: TranscodeID { id }
    public var titleId: TitleID { TitleID(proto: proto.titleID) }
    public var titleName: String { proto.titleName }
    public var mediaType: MediaType? { nil }
    public var filePath: String? { proto.hasFilePath ? proto.filePath : nil }
    public var mediaFormat: String? { proto.mediaFormat.displayString }
    public var seasonNumber: Int? { nil }
    public var episodeNumber: Int? { nil }
    public var episodeName: String? { nil }
    public var retranscodeRequested: Bool? { nil }
}

public struct AdminLinkedTranscodeResponse: Sendable {
    public let proto: MMLinkedTranscodeResponse
    public init(proto: MMLinkedTranscodeResponse) { self.proto = proto }

    public var transcodes: [AdminLinkedTranscode] { proto.transcodes.map { AdminLinkedTranscode(proto: $0) } }
    public var total: Int { Int(proto.pagination.total) }
    public var page: Int { Int(proto.pagination.page) }
    public var limit: Int { Int(proto.pagination.limit) }
    public var totalPages: Int { Int(proto.pagination.totalPages) }
}

public struct AdminUnmatchedFile: Identifiable, Sendable {
    public let proto: MMUnmatchedFile
    public init(proto: MMUnmatchedFile) { self.proto = proto }

    public var id: UnmatchedFileID { UnmatchedFileID(proto: proto.id) }
    public var fileName: String { String(proto.filePath.split(separator: "/").last ?? "") }
    public var directory: String? { nil }
    public var mediaType: MediaType? { nil }
    public var parsedTitle: String? { nil }
    public var parsedYear: Int? { nil }
    public var parsedSeason: Int? { nil }
    public var parsedEpisode: Int? { nil }
    public var suggestions: [AdminMatchSuggestion] {
        if proto.hasSuggestedTitle {
            [AdminMatchSuggestion(titleId: TitleID(proto: proto.suggestedTitleID),
                                  titleName: proto.suggestedTitle,
                                  score: proto.matchScore)]
        } else { [] }
    }
}

public struct AdminMatchSuggestion: Identifiable, Sendable {
    public var id: TitleID { titleId }
    public let titleId: TitleID
    public let titleName: String
    public let score: Double
}

public struct AdminUnmatchedResponse: Sendable {
    public let proto: MMUnmatchedResponse
    public init(proto: MMUnmatchedResponse) { self.proto = proto }

    public var unmatched: [AdminUnmatchedFile] { proto.unmatched.map { AdminUnmatchedFile(proto: $0) } }
    public var total: Int { Int(proto.total) }
}

// MARK: - Admin Enum Display Extensions

public extension MMLeaseStatus {
    public var displayString: String? {
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

public extension MMLeaseType {
    public var displayString: String? {
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

public extension MMEnrichmentStatus {
    public var displayString: String? {
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

public extension MMScanStatus {
    public var displayString: String {
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

    public var isTerminal: Bool {
        switch self {
        case .upcNotFound, .enriched, .enrichmentFailed, .noMatch: true
        default: false
        }
    }

    public var needsTmdbAction: Bool {
        switch self {
        case .noMatch, .enrichmentFailed: true
        default: false
        }
    }
}

public extension MMSubmitBarcodeResult {
    public var displayString: String {
        switch self {
        case .created: "Created"
        case .duplicate: "Duplicate"
        case .invalid: "Invalid"
        case .unknown, .UNRECOGNIZED: "Unknown"
        }
    }
}

public extension MMSettingKey {
    public var configKey: String? {
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

public extension AcquisitionStatus {
    public var protoValue: MMAcquisitionStatus {
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

public extension MMSettingKey {
    public static func fromConfigKey(_ key: String) -> MMSettingKey {
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

// MARK: - Author / Book Adapters

/// Card-row author for the Authors grid. Hero artwork is fetched via
/// `MMImageRef.authorHeadshot(authorId:)` when `hasHeadshot`, with a
/// fallback to `MMImageRef.posterThumbnail(titleId: fallbackBookTitleId!)`
/// for authors who have owned books but no Wikimedia/OpenLibrary thumbnail.
public struct ApiAuthorListItem: Identifiable, Sendable {
    public let proto: MMAuthorListItem
    public init(proto: MMAuthorListItem) { self.proto = proto }

    public var id: AuthorID { AuthorID(proto: proto.id) }
    public var name: String { proto.name }
    public var ownedBookCount: Int { Int(proto.ownedBookCount) }
    public var hasHeadshot: Bool { proto.hasHeadshot_p }
    public var hidden: Bool { proto.hidden }
    /// Title id of an owned book to use as a hero fallback when the
    /// author lacks a headshot. Returns nil for authors with neither
    /// owned books nor a headshot — those render as a placeholder.
    public var fallbackBookTitleId: TitleID? {
        proto.hasFallbackBookTitleID ? TitleID(proto: proto.fallbackBookTitleID) : nil
    }
}

public struct ApiAuthorListResponse: Sendable {
    public let proto: MMAuthorListResponse
    public init(proto: MMAuthorListResponse) { self.proto = proto }

    public var authors: [ApiAuthorListItem] { proto.authors.map { ApiAuthorListItem(proto: $0) } }
    public var totalPages: Int { Int(proto.pagination.totalPages) }
    public var currentPage: Int { Int(proto.pagination.page) }
}

// MARK: - Artist (audio)

/// Card cell for the artists grid. Mirrors `ApiAuthorListItem` for
/// books — same hero-image-with-fallback pattern: real headshot when
/// available, owned-album poster as fallback, synthesised colour
/// swatch when neither exists.
public struct ApiArtistListItem: Identifiable, Sendable {
    public let proto: MMArtistListItem
    public init(proto: MMArtistListItem) { self.proto = proto }

    public var id: ArtistID { ArtistID(proto: proto.id) }
    public var name: String { proto.name }
    public var sortName: String? { proto.hasSortName ? proto.sortName : nil }
    public var artistType: MMArtistType { proto.artistType }
    public var ownedAlbumCount: Int { Int(proto.ownedAlbumCount) }
    public var hasHeadshot: Bool { proto.hasHeadshot_p }
    /// Album titleId used as the artwork fallback when the artist
    /// has no headshot. Hero rendering uses this with
    /// `.posterThumbnail(titleId:)` — square 1:1 aspect since this
    /// is an album cover, not a movie poster.
    public var fallbackAlbumTitleId: TitleID? {
        proto.hasFallbackAlbumTitleID ? TitleID(proto: proto.fallbackAlbumTitleID) : nil
    }
}

public struct ApiArtistListResponse: Sendable {
    public let proto: MMArtistListResponse
    public init(proto: MMArtistListResponse) { self.proto = proto }

    public var artists: [ApiArtistListItem] { proto.artists.map { ApiArtistListItem(proto: $0) } }
    public var totalPages: Int { Int(proto.pagination.totalPages) }
    public var currentPage: Int { Int(proto.pagination.page) }
}

/// Artist header info on ArtistDetailView. Headshot via
/// `MMImageRef.artistHeadshot(artistId:)`; the bool prevents the UI
/// from flashing a broken-image placeholder while the image loads.
public struct ApiArtist: Sendable {
    public let proto: MMArtist
    public init(proto: MMArtist) { self.proto = proto }

    public var id: ArtistID { ArtistID(proto: proto.id) }
    public var name: String { proto.name }
    public var artistType: MMArtistType { proto.artistType }
    public var beginYear: Int? { proto.hasBeginYear ? Int(proto.beginYear) : nil }
    public var endYear: Int? { proto.hasEndYear ? Int(proto.endYear) : nil }
    public var hasHeadshot: Bool { proto.hasHeadshot_p }
    public var musicbrainzArtistId: String? {
        proto.hasMusicbrainzArtistID ? proto.musicbrainzArtistID : nil
    }
}

/// Artist detail = the artist itself, optional bio, owned albums on
/// the shelf, "Other Works" discography from MusicBrainz for
/// wishlisting, and (when GROUP) members / (when PERSON) memberOf.
public struct ApiArtistDetail: Sendable {
    public let proto: MMArtistDetail
    public init(proto: MMArtistDetail) { self.proto = proto }

    public var artist: ApiArtist { ApiArtist(proto: proto.artist) }
    public var biography: String? { proto.hasBiography ? proto.biography : nil }
    public var ownedAlbums: [ApiTitle] { proto.ownedAlbums.map { ApiTitle(proto: $0) } }
    public var otherWorks: [ApiDiscographyEntry] { proto.otherWorks.map { ApiDiscographyEntry(proto: $0) } }
    /// Members of a group artist (populated when artistType == GROUP).
    public var members: [ApiArtistMember] { proto.members.map { ApiArtistMember(proto: $0) } }
    /// Groups this person is a member of (populated when artistType == PERSON).
    public var memberOf: [ApiArtistMember] { proto.memberOf.map { ApiArtistMember(proto: $0) } }
}

/// Unowned discography entry. Cover via
/// `MMImageRef.coverArtArchiveReleaseGroup(releaseGroupId:)`.
public struct ApiDiscographyEntry: Identifiable, Sendable {
    public let proto: MMDiscographyEntry
    public init(proto: MMDiscographyEntry) { self.proto = proto }

    public var id: String { proto.musicbrainzReleaseGroupID }
    public var releaseGroupId: String { proto.musicbrainzReleaseGroupID }
    public var name: String { proto.name }
    public var year: Int? { proto.hasYear ? Int(proto.year) : nil }
    public var releaseGroupType: MMReleaseGroupType { proto.releaseGroupType }
    public var isCompilation: Bool { proto.isCompilation }
    public var secondaryTypes: [String] { proto.secondaryTypes }
    public var alreadyWished: Bool { proto.alreadyWished }
}

/// Album shape attached to ApiTitleDetail.album when the title is an
/// audio album. Holds the tracklist plus album-level metadata that
/// doesn't fit on the bare Title.
public struct ApiAlbum: Sendable {
    public let proto: MMAlbum
    public init(proto: MMAlbum) { self.proto = proto }

    public var tracks: [ApiTrack] { proto.tracks.map { ApiTrack(proto: $0) } }
    public var albumArtists: [ApiArtist] { proto.albumArtists.map { ApiArtist(proto: $0) } }
    public var trackCount: Int? { proto.hasTrackCount ? Int(proto.trackCount) : nil }
    public var totalDurationSeconds: Double? {
        proto.hasTotalDuration ? proto.totalDuration.seconds : nil
    }
    public var label: String? { proto.hasLabel ? proto.label : nil }
    public var releaseGroupId: String? {
        proto.hasMusicbrainzReleaseGroupID ? proto.musicbrainzReleaseGroupID : nil
    }
}

/// One track within an album. Wraps the proto for cheap pass-through;
/// the AudioPlayerManager builds its `QueuedTrack` from these +
/// album context (titleId / albumName / artistName).
public struct ApiTrack: Identifiable, Sendable {
    public let proto: MMTrack
    public init(proto: MMTrack) { self.proto = proto }

    public var id: Int64 { proto.id }
    public var titleId: Int64 { proto.titleID }
    public var trackNumber: Int32 { proto.trackNumber }
    public var discNumber: Int32 { proto.discNumber }
    public var name: String { proto.name }
    public var durationSeconds: Double? {
        proto.hasDuration ? proto.duration.seconds : nil
    }
    public var playable: Bool { proto.playable }
    /// Per-track artists (when different from the album credit). Used
    /// to render "feat. X" or compilation per-track artists in the
    /// tracklist row.
    public var trackArtistNames: [String] { proto.trackArtistNames }
    /// Beats-per-minute, extracted from the file's ID3/Vorbis tags
    /// at ingest. nil for tracks the tagger never wrote one onto.
    /// Useful for ballroom / DJ contexts ("dance bpm ∈ [78, 96]").
    public var bpm: Int? { proto.hasBpm ? Int(proto.bpm) : nil }
    /// Raw time-signature string from the file's tags (e.g. "4/4",
    /// "3/4", "6/8"). nil for files that don't carry it — common
    /// for older rips. Displayed verbatim alongside BPM.
    public var timeSignature: String? {
        proto.hasTimeSignature ? proto.timeSignature : nil
    }
    /// Parent album's name. Populated when the track is surfaced
    /// standalone (library shuffle, smart playlists, tag-detail);
    /// empty when the track ships inside an Album that already
    /// carries its title.
    public var albumName: String? {
        proto.hasTitleName ? proto.titleName : nil
    }
    /// Parent album's primary-artist credit. Populated alongside
    /// `albumName` for standalone surfaces. Used by the mini-player
    /// + Now Playing when `trackArtistNames` is empty (the common
    /// non-compilation case).
    public var albumArtistName: String? {
        proto.hasTitleArtistName ? proto.titleArtistName : nil
    }
}

/// One row in the Music landing page's smart-playlist carousel.
/// Server-defined and read-only; the `key` is the stable handle
/// (`recently-added`, `most-played`, etc.) used by `smartPlaylist`
/// to fetch the tracklist.
public struct ApiSmartPlaylistSummary: Identifiable, Sendable {
    public let proto: MMSmartPlaylistSummary
    public init(proto: MMSmartPlaylistSummary) { self.proto = proto }

    public var id: String { proto.key }
    public var key: String { proto.key }
    public var name: String { proto.name }
    public var description: String { proto.description_p }
    public var trackCount: Int { Int(proto.trackCount) }
    /// Album titleId the server picked as a representative cover.
    /// Square 1:1 like all album art elsewhere in the audio module.
    public var heroTitleId: TitleID? {
        proto.hasHeroTitleID ? TitleID(proto: proto.heroTitleID) : nil
    }
}

/// User-owned (or peer-owned) playlist row. Lightweight — used in
/// list grids. The detail RPC hands back the full tracklist on tap.
public struct ApiPlaylistSummary: Identifiable, Sendable {
    public let proto: MMPlaylistSummary
    public init(proto: MMPlaylistSummary) { self.proto = proto }

    public var id: Int64 { proto.id }
    public var name: String { proto.name }
    public var description: String? { proto.hasDescription_p ? proto.description_p : nil }
    public var ownerUsername: String { proto.ownerUsername }
    public var isOwner: Bool { proto.isOwner }
    public var isPrivate: Bool { proto.isPrivate }
    /// Album titleId server picked as the hero cover (square art).
    public var heroTitleId: TitleID? {
        proto.hasHeroTitleID ? TitleID(proto: proto.heroTitleID) : nil
    }
    /// User-pinned hero track, when set. Drives the per-row star
    /// icon on the detail page.
    public var heroTrackId: Int64? {
        proto.hasHeroTrackID ? proto.heroTrackID : nil
    }
}

/// Full playlist detail — summary + tracks. Lifted out of the
/// summary so the list grid doesn't pay tracklist serialisation
/// cost on every render.
public struct ApiPlaylistDetail: Sendable {
    public let proto: MMPlaylistDetail
    public init(proto: MMPlaylistDetail) { self.proto = proto }

    public var summary: ApiPlaylistSummary { ApiPlaylistSummary(proto: proto.summary) }
    public var tracks: [ApiPlaylistTrackEntry] { proto.tracks.map { ApiPlaylistTrackEntry(proto: $0) } }
    public var totalDurationSeconds: Int { Int(proto.totalDurationSeconds) }
}

/// Full tracklist for a smart playlist. Used by the detail page.
public struct ApiSmartPlaylistDetail: Sendable {
    public let proto: MMSmartPlaylistDetail
    public init(proto: MMSmartPlaylistDetail) { self.proto = proto }

    public var summary: ApiSmartPlaylistSummary { ApiSmartPlaylistSummary(proto: proto.summary) }
    public var tracks: [ApiPlaylistTrackEntry] { proto.tracks.map { ApiPlaylistTrackEntry(proto: $0) } }
    public var totalDurationSeconds: Int { Int(proto.totalDurationSeconds) }
}

/// One playlist row — a track with its position in the playlist.
/// The proto carries the parent album's titleId + name as
/// `title_id` / `title_name`; artist credit comes off the embedded
/// `Track.track_artist_names` (the playlist row itself doesn't
/// duplicate album-artist).
public struct ApiPlaylistTrackEntry: Identifiable, Sendable {
    public let proto: MMPlaylistTrackEntry
    public init(proto: MMPlaylistTrackEntry) { self.proto = proto }

    /// `playlist_track_id` is the stable handle for remove / reorder
    /// (track_id can repeat in the same playlist). Uses it as the
    /// SwiftUI list identity.
    public var id: Int64 { proto.playlistTrackID }
    public var position: Int { Int(proto.position) }
    public var track: ApiTrack { ApiTrack(proto: proto.track) }
    /// Parent album's titleId — for posterThumbnail lookup.
    public var albumTitleId: Int64 { proto.titleID }
    /// Album name (the proto's `title_name` for the parent album).
    public var albumName: String { proto.titleName }
    /// First per-track artist credit, when populated. Empty when
    /// the embedded Track has no per-track artists (use the album
    /// artist from elsewhere on the page in that case).
    public var primaryArtistName: String { track.trackArtistNames.first ?? "" }
}

/// Membership row — either members of a group, or groups a person is
/// in. Tap routes back into ArtistDetailView for the linked artist.
public struct ApiArtistMember: Identifiable, Sendable {
    public let proto: MMArtistMemberEntry
    public init(proto: MMArtistMemberEntry) { self.proto = proto }

    public var id: ArtistID { ArtistID(proto: proto.artistID) }
    public var name: String { proto.name }
    public var beginYear: Int? { proto.hasBeginYear ? Int(proto.beginYear) : nil }
    public var endYear: Int? { proto.hasEndYear ? Int(proto.endYear) : nil }
    public var artistType: MMArtistType { proto.artistType }
    public var instruments: String? { proto.hasInstruments ? proto.instruments : nil }
}

/// Author bio used on the author-detail header. Headshot via
/// `MMImageRef.authorHeadshot(authorId:)`; the bool keeps the UI from
/// flashing a broken-image placeholder while the stream warms up.
public struct ApiAuthor: Sendable {
    public let proto: MMAuthor
    public init(proto: MMAuthor) { self.proto = proto }

    public var id: AuthorID { AuthorID(proto: proto.id) }
    public var name: String { proto.name }
    public var biography: String? { proto.hasBiography ? proto.biography : nil }
    public var openLibraryId: String? { proto.hasOpenlibraryID ? proto.openlibraryID : nil }
    public var birthYear: Int? { proto.hasBirthYear ? Int(proto.birthYear) : nil }
    public var deathYear: Int? { proto.hasDeathYear ? Int(proto.deathYear) : nil }
    public var hasHeadshot: Bool { proto.hasHeadshot_p }
    public var hidden: Bool { proto.hidden }
}

/// Author detail = bio + the owned books we have on the shelf + the
/// "other works" bibliography pulled from OpenLibrary.
public struct ApiAuthorDetail: Sendable {
    public let proto: MMAuthorDetail
    public init(proto: MMAuthorDetail) { self.proto = proto }

    public var author: ApiAuthor { ApiAuthor(proto: proto.author) }
    public var ownedBooks: [ApiTitle] { proto.ownedBooks.map { ApiTitle(proto: $0) } }
    public var otherWorks: [ApiBibliographyEntry] { proto.otherWorks.map { ApiBibliographyEntry(proto: $0) } }
}

/// Unowned bibliography entry. Cover via
/// `MMImageRef.openlibraryCover(workId:)`.
public struct ApiBibliographyEntry: Identifiable, Sendable {
    public let proto: MMBibliographyEntry
    public init(proto: MMBibliographyEntry) { self.proto = proto }

    public var id: String { proto.openlibraryWorkID }
    public var openLibraryWorkId: String { proto.openlibraryWorkID }
    public var name: String { proto.name }
    public var year: Int? { proto.hasYear ? Int(proto.year) : nil }
    public var seriesRaw: String? { proto.hasSeriesRaw ? proto.seriesRaw : nil }
    public var alreadyWished: Bool { proto.alreadyWished }
}

/// Book-series detail. Hero cover preference: when the user has owned
/// volumes, use the first volume's `posterThumbnail`. The proto's
/// `coverIsbn` is not yet wired into ImageService so we fall back to
/// the first owned volume.
public struct ApiBookSeriesDetail: Sendable {
    public let proto: MMBookSeriesDetail
    public init(proto: MMBookSeriesDetail) { self.proto = proto }

    public var id: BookSeriesID { BookSeriesID(proto: proto.id) }
    public var name: String { proto.name }
    public var description: String? { proto.hasDescription_p ? proto.description_p : nil }
    public var coverIsbn: String? { proto.hasCoverIsbn ? proto.coverIsbn : nil }
    public var author: ApiBookSeriesAuthor? {
        proto.hasAuthor ? ApiBookSeriesAuthor(proto: proto.author) : nil
    }
    public var volumes: [ApiBookSeriesVolume] {
        proto.volumes.map { ApiBookSeriesVolume(proto: $0) }
    }
    public var missingVolumes: [ApiBookSeriesMissingVolume] {
        proto.missingVolumes.map { ApiBookSeriesMissingVolume(proto: $0) }
    }
    public var canFillGaps: Bool { proto.canFillGaps }
}

public struct ApiBookSeriesAuthor: Sendable {
    public let proto: MMBookSeriesAuthor
    public init(proto: MMBookSeriesAuthor) { self.proto = proto }

    public var id: AuthorID { AuthorID(proto: proto.id) }
    public var name: String { proto.name }
}

public struct ApiBookSeriesVolume: Identifiable, Sendable {
    public let proto: MMBookSeriesVolume
    public init(proto: MMBookSeriesVolume) { self.proto = proto }

    public var id: TitleID { titleId }
    public var titleId: TitleID { TitleID(proto: proto.titleID) }
    public var titleName: String { proto.titleName }
    public var seriesNumber: String? { proto.hasSeriesNumber ? proto.seriesNumber : nil }
    public var firstPublicationYear: Int? { proto.hasFirstPublicationYear ? Int(proto.firstPublicationYear) : nil }
    public var owned: Bool { proto.owned }
}

public struct ApiBookSeriesMissingVolume: Identifiable, Sendable {
    public let proto: MMBookSeriesMissingVolume
    public init(proto: MMBookSeriesMissingVolume) { self.proto = proto }

    public var id: String { proto.olWorkID }
    public var openLibraryWorkId: String { proto.olWorkID }
    public var title: String { proto.title }
    public var seriesNumber: String? { proto.hasSeriesNumber ? proto.seriesNumber : nil }
    public var year: Int? { proto.hasYear ? Int(proto.year) : nil }
    public var alreadyWished: Bool { proto.alreadyWished }
}

/// Book-specific fields nested under `MMTitleDetail.book` for BOOK titles.
/// The reader uses `editions` to know whether the book is digitally
/// available and `readingProgress` to resume from the last position.
public struct ApiBookDetail: Sendable {
    public let proto: MMBookDetail
    public init(proto: MMBookDetail) { self.proto = proto }

    public var authors: [ApiAuthor] { proto.authors.map { ApiAuthor(proto: $0) } }
    public var editions: [ApiBookEdition] { proto.editions.map { ApiBookEdition(proto: $0) } }
    public var readingProgress: ApiReadingProgress? {
        proto.hasReadingProgress ? ApiReadingProgress(proto: proto.readingProgress) : nil
    }
    public var bookSeries: ApiBookSeriesRef? {
        proto.hasBookSeries ? ApiBookSeriesRef(proto: proto.bookSeries) : nil
    }
    public var pageCount: Int? { proto.hasPageCount ? Int(proto.pageCount) : nil }
    public var firstPublicationYear: Int? {
        proto.hasFirstPublicationYear ? Int(proto.firstPublicationYear) : nil
    }
    public var openLibraryWorkId: String? {
        proto.hasOpenLibraryWorkID ? proto.openLibraryWorkID : nil
    }

    /// Convenience: at least one editions row is digital (downloadable),
    /// i.e. the book can be opened in the in-app reader.
    public var hasDigitalEdition: Bool { editions.contains { $0.downloadable } }
}

/// One physical or digital edition of a book.
public struct ApiBookEdition: Identifiable, Sendable {
    public let proto: MMBookEdition
    public init(proto: MMBookEdition) { self.proto = proto }

    public var id: Int64 { proto.mediaItemID }
    public var format: MMBookEditionFormat { proto.editionFormat }
    public var fileSizeBytes: Int64? {
        proto.hasFileSizeBytes ? proto.fileSizeBytes : nil
    }
    /// Physical shelf for non-digital editions ("Living room shelf 3").
    public var storageLocation: String? {
        proto.hasStorageLocation ? proto.storageLocation : nil
    }
    public var downloadable: Bool { proto.downloadable }
}

/// Reading position within a book. Locator is an EPUB CFI for EPUB
/// editions or "/page/N" for PDFs; `fraction` is the 0..1 progress.
public struct ApiReadingProgress: Sendable {
    public let proto: MMReadingProgress
    public init(proto: MMReadingProgress) { self.proto = proto }

    public var locator: String { proto.locator }
    public var fraction: Double { proto.fraction }
    /// Server's wall-clock at the moment the row was last written.
    public var updatedAt: Date? {
        proto.hasUpdatedAt
            ? Date(timeIntervalSince1970: TimeInterval(proto.updatedAt.secondsSinceEpoch))
            : nil
    }
    /// Client wall-clock that produced the row, when known. Pre-V098
    /// rows from old clients have nil here even though `updatedAt`
    /// is set; the reader's resume picker treats absence as
    /// "infinitely old" so a fresh client write supersedes.
    public var clientRecordedAt: Date? {
        proto.hasClientRecordedAt
            ? Date(timeIntervalSince1970: TimeInterval(proto.clientRecordedAt.secondsSinceEpoch))
            : nil
    }
}

/// Lightweight series link from `BookDetail.book_series`. Tap-through
/// builds a `BookSeriesRoute` with id + name.
public struct ApiBookSeriesRef: Sendable {
    public let proto: MMBookSeriesRef
    public init(proto: MMBookSeriesRef) { self.proto = proto }

    public var id: BookSeriesID { BookSeriesID(proto: proto.id) }
    public var name: String { proto.name }
    public var number: String? { proto.hasNumber ? proto.number : nil }
}

// MARK: - Radio

/// Identity of what's seeding the current radio session — a track or
/// an album. Surfaced in the mini-player / now-playing chrome so the
/// user can tell "this is a station" vs "this is the album I picked".
public struct ApiRadioSeed: Sendable {
    public let proto: MMRadioSeed
    public init(proto: MMRadioSeed) { self.proto = proto }

    public var seedId: Int64 { proto.seedID }
    /// True when seeded from a track (vs. an album). Drives label
    /// copy: "Station from <song>" vs "Station from <album>".
    public var isTrackSeed: Bool { proto.seedType == .track }
    public var name: String { proto.seedName }
    /// Empty for album-seeded stations (the album credit is the
    /// album-artist; the seed name carries the album name). For
    /// track-seeded stations, this is the track's primary artist.
    public var artistName: String { proto.seedArtistName }
}

/// Wraps StartRadioResponse so the AudioPlayerManager doesn't need to
/// know proto field shapes. The session id is just a String — no
/// adapter needed.
public struct ApiStartRadioResponse: Sendable {
    public let proto: MMStartRadioResponse
    public init(proto: MMStartRadioResponse) { self.proto = proto }

    public var sessionId: String { proto.radioSessionID }
    public var seed: ApiRadioSeed { ApiRadioSeed(proto: proto.seed) }
    public var initialBatch: [ApiTrack] { proto.initialBatch.map { ApiTrack(proto: $0) } }
}

// MARK: - Recommendations

/// "Artist you might like" suggestion. MBID is the authoritative id
/// since most recommendations point at artists the user *doesn't*
/// own — there is no local Artist row for them. When the server has
/// already seeded one (via wishlist or sibling-artist lookups),
/// `localArtistId` is non-nil and the card can navigate to
/// ArtistDetailView; otherwise tap is a no-op (Phase 7 scope).
public struct ApiRecommendedArtist: Identifiable, Sendable {
    public let proto: MMRecommendedArtist
    public init(proto: MMRecommendedArtist) { self.proto = proto }

    /// Stable id for ForEach. Each user has a unique MBID per
    /// recommendation row (server enforces dedup), so the MBID is
    /// safe as the SwiftUI identity.
    public var id: String { proto.suggestedArtistMbid }
    public var mbid: String { proto.suggestedArtistMbid }
    public var localArtistId: Int64? {
        proto.hasSuggestedArtistID ? proto.suggestedArtistID : nil
    }
    public var name: String { proto.suggestedArtistName }
    public var score: Double { proto.score }
    /// Names of the user's own artists that "voted" for this
    /// suggestion via the similar-artist graph. Drives the "Liked
    /// by X & Y" caption on the discover card.
    public var voterArtistNames: [String] { proto.voterArtistNames }
    /// MBID of the release group whose cover represents the artist.
    /// Use with `MMImageRef.coverArtArchiveReleaseGroup(releaseGroupId:)`.
    /// nil for artists with no usable cover (rare — server tries).
    public var representativeReleaseGroupMbid: String? {
        proto.hasRepresentativeReleaseGroupMbid
            ? proto.representativeReleaseGroupMbid
            : nil
    }
    public var representativeReleaseTitle: String? {
        proto.hasRepresentativeReleaseTitle
            ? proto.representativeReleaseTitle
            : nil
    }
}
