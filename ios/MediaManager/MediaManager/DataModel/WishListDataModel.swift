import Foundation

@MainActor protocol WishListDataModel {
    func wishList() async throws -> ApiWishListResponse
    func transcodeWishList() async throws -> ApiTranscodeWishListResponse
    func addWish(tmdbId: TmdbID, mediaType: MediaType, title: String, year: Int?,
                 posterPath: String?, seasonNumber: Int?) async throws
    func deleteWish(id: WishID) async throws
    func voteOnWish(id: WishID, vote: Bool) async throws
    func dismissWish(id: WishID) async throws
    func deleteTranscodeWish(titleId: TitleID) async throws
    func searchTmdb(query: String) async throws -> TmdbSearchResponse
}
