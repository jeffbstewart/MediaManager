import Foundation

@MainActor protocol WishListDataModel {
    func wishList() async throws -> ApiWishListResponse
    func transcodeWishList() async throws -> ApiTranscodeWishListResponse
    func addWish(tmdbId: TmdbID, mediaType: MediaType, title: String, year: Int?,
                 seasonNumber: Int?) async throws
    /// Adds a book wish keyed on Open Library work id. Used by
    /// AuthorDetailView's "Other Works" tap-to-wish; bypasses TMDB
    /// (which doesn't catalogue books) and goes straight to the
    /// server's OL-keyed book-wish RPC.
    func addBookWish(olWorkId: String, title: String, author: String?) async throws
    /// Removes a book wish by OL work id (the most-likely identifier
    /// the calling view actually has, since the bibliography only
    /// surfaces the work id, not a row id).
    func removeBookWish(olWorkId: String) async throws
    func deleteWish(id: WishID) async throws
    func voteOnWish(id: WishID, vote: Bool) async throws
    func dismissWish(id: WishID) async throws
    func deleteTranscodeWish(titleId: TitleID) async throws
    func searchTmdb(query: String) async throws -> TmdbSearchResponse
    func searchTmdb(query: String, type: MMMediaType) async throws -> MMTmdbSearchResponse
}
