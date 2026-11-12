package net.stewart.mediamanager.service

/**
 * In-memory test double for [HttpFetcher]. URLs in [responses] return
 * the mapped body; everything else returns `null` (which the production
 * fetcher uses to signal 404 / non-success). [requestedUrls] records the
 * exact URLs the agent under test asked for, in order, so assertions
 * can verify the fetch sequence rather than just the entity side effects.
 */
class FakeHttpFetcher(
    var responses: Map<String, String> = emptyMap(),
) : HttpFetcher {
    val requestedUrls: MutableList<String> = mutableListOf()

    override fun fetch(url: String): String? {
        requestedUrls += url
        return responses[url]
    }
}
