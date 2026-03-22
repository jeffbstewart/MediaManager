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
}
