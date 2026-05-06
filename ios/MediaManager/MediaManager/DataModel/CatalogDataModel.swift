import Foundation

@MainActor protocol CatalogDataModel {
    func homeFeed() async throws -> ApiHomeFeed
    func titles(type: MediaType, page: Int, sort: String?) async throws -> ApiTitlePage
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
}

/// Sort modes accepted by `ListArtists`. Values mirror the proto's
/// stringly-typed sort field; the enum exists so views don't have
/// to traffic in raw strings.
enum ArtistSort: String, CaseIterable, Identifiable, Sendable {
    case albums  // owned-album count desc — server default
    case name    // sort_name asc
    case recent  // created_at desc
    var id: String { rawValue }
    var label: String {
        switch self {
        case .albums: return "By Albums"
        case .name: return "By Name"
        case .recent: return "Recently Added"
        }
    }
}
