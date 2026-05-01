package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Tiny GET-only HTTP seam for the enrichment agents and any other
 * service that needs to fetch a URL and treat 200/404/non-200 the same
 * way. The contract is intentionally minimal: hand back the body on a
 * 200, null on 404 or any non-success status. Tests substitute a
 * scripted [FakeHttpFetcher] (in test sources) so the agents can be
 * driven without a real network.
 */
interface HttpFetcher {
    fun fetch(url: String): String?
}

/**
 * Default JDK HttpClient-backed fetcher. Carries a fixed User-Agent
 * (Open Library / MusicBrainz / Wikipedia all expect a descriptive one)
 * and a 15-second per-request timeout. 429s log a rate-limit warning;
 * other non-success codes log the URL + status so failures are
 * diagnosable in production.
 */
class JdkHttpFetcher(
    private val userAgent: String =
        "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
) : HttpFetcher {
    private val log = LoggerFactory.getLogger(JdkHttpFetcher::class.java)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun fetch(url: String): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response: HttpResponse<String> = httpClient.send(
            request, HttpResponse.BodyHandlers.ofString()
        )
        return when (response.statusCode()) {
            200 -> response.body()
            404 -> null
            429, 503 -> {
                log.warn("Upstream rate limited ({}); skipping {}",
                    response.statusCode(), url)
                null
            }
            else -> {
                log.warn("Upstream returned HTTP {} for {}",
                    response.statusCode(), url)
                null
            }
        }
    }
}
