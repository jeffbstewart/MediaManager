package net.stewart.mediamanager.service

/**
 * In-memory test double for [OpenLibraryService]. ISBN, author, and
 * search responses are scripted via maps; unscripted inputs return the
 * real-service equivalent of "miss" (NotFound, or an empty list).
 */
class FakeOpenLibraryService(
    var byIsbn: Map<String, OpenLibraryResult> = emptyMap(),
    var byAuthor: Map<String, List<AuthorWorkRef>> = emptyMap(),
    var bySearch: Map<String, List<OpenLibrarySearchHit>> = emptyMap(),
) : OpenLibraryService {

    override fun lookupByIsbn(isbn: String): OpenLibraryResult =
        byIsbn[isbn] ?: OpenLibraryResult.NotFound

    override fun listAuthorWorks(openLibraryAuthorId: String, limit: Int): List<AuthorWorkRef> =
        byAuthor[openLibraryAuthorId].orEmpty()

    override fun searchWorks(query: String, limit: Int): List<OpenLibrarySearchHit> =
        bySearch[query].orEmpty().take(limit)
}
