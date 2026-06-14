import Foundation
import MediaManagerProtos

@MainActor public protocol CatalogDataModel {
    func homeFeed() async throws -> ApiHomeFeed
    func titles(type: MediaType, page: Int, sort: String?, query: String?) async throws -> ApiTitlePage
    func titleDetail(id: TitleID) async throws -> ApiTitleDetail
    func seasons(titleId: TitleID) async throws -> [ApiSeason]
    func episodes(titleId: TitleID, season: Int) async throws -> [ApiEpisode]
    func search(query: String) async throws -> ApiSearchResponse
    func actorDetail(id: TmdbPersonID) async throws -> ApiActorDetail
    func collections() async throws -> ApiCollectionListResponse
    func collectionDetail(id: TmdbCollectionID) async throws -> ApiCollectionDetail
    func tags() async throws -> ApiTagListResponse
    func tagDetail(id: TagID) async throws -> ApiTagDetail
    func genreDetail(id: GenreID) async throws -> ApiGenreDetail
    func setFavorite(titleId: TitleID, favorite: Bool) async throws
    func setHidden(titleId: TitleID, hidden: Bool) async throws
    func requestRetranscode(titleId: TitleID) async throws
    func requestMobileTranscode(titleId: TitleID) async throws
    func dismissContinueWatching(titleId: TitleID) async throws
    func dismissMissingSeason(titleId: TitleID, tmdbId: TmdbID, mediaType: MediaType, seasonNumber: Int) async throws

    // Books
    func authors(page: Int, sort: AuthorSort, query: String?, hiddenOnly: Bool) async throws -> ApiAuthorListResponse
    func authorDetail(id: AuthorID) async throws -> ApiAuthorDetail
    func bookSeriesDetail(id: BookSeriesID) async throws -> ApiBookSeriesDetail

    // Audio
    func artists(page: Int, sort: ArtistSort, query: String?) async throws -> ApiArtistListResponse
    func artistDetail(id: ArtistID) async throws -> ApiArtistDetail
    /// Random shuffle of the user's library; returns up to `limit`
    /// playable tracks for queueing into AudioPlayerManager.
    func libraryShuffle(limit: Int) async throws -> [ApiTrack]
    /// Server-defined virtual playlists for the Music landing page.
    func smartPlaylists() async throws -> [ApiSmartPlaylistSummary]
    func smartPlaylist(key: String) async throws -> ApiSmartPlaylistDetail
    /// Per-user dismissal of a single title from a home-feed
    /// carousel. Used by the iOS Music landing page's
    /// per-card dismiss-X on Recently Added Albums.
    func dismissHomeCarouselItem(titleId: TitleID, carousel: HomeCarousel) async throws

    // User playlists
    func playlists(scope: PlaylistScope) async throws -> [ApiPlaylistSummary]
    func playlist(id: Int64) async throws -> ApiPlaylistDetail
    func createPlaylist(name: String, description: String?) async throws -> ApiPlaylistSummary
    func renamePlaylist(id: Int64, name: String, description: String?) async throws
    func deletePlaylist(id: Int64) async throws
    func addTracksToPlaylist(id: Int64, trackIds: [Int64]) async throws
    func removeTrackFromPlaylist(id: Int64, playlistTrackId: Int64) async throws
    func reorderPlaylist(id: Int64, playlistTrackIdsInOrder: [Int64]) async throws
    func setPlaylistHero(id: Int64, trackId: Int64?) async throws
    func setPlaylistPrivacy(id: Int64, isPrivate: Bool) async throws
}

/// Visibility filter for `playlists(scope:)`. Mirrors the proto
/// `PlaylistScope` enum.
public enum PlaylistScope: Sendable {
    case mine            // playlists the caller owns
    case all             // all playlists visible to the caller (own + public)
}

/// Which home-feed carousel a `dismissHomeCarouselItem` call
/// targets. Mirrors the `HomeCarousel` proto enum.
public enum HomeCarousel: Sendable {
    case recentlyAddedAlbums
    case recentlyAddedBooks
    case recentlyAddedMovies
}

/// Sort modes accepted by `ListArtists`. Values mirror the proto's
/// stringly-typed sort field; the enum exists so views don't have
/// to traffic in raw strings.
public enum ArtistSort: String, CaseIterable, Identifiable, Sendable {
    case albums  // owned-album count desc — server default
    case name    // sort_name asc
    case recent  // created_at desc
    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .albums: return "By Albums"
        case .name: return "By Name"
        case .recent: return "Recently Added"
        }
    }
}
