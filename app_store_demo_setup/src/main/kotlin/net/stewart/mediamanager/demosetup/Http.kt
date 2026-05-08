package net.stewart.mediamanager.demosetup

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Thin HTTP helper around [java.net.http.HttpClient] that:
 *  - retries on transient failures (5xx, IOException) with backoff,
 *  - streams large downloads to disk via a `.partial` sibling so an
 *    interrupted run doesn't leave a half-file the next pass treats
 *    as already-complete,
 *  - throws on non-2xx instead of silently returning the body.
 *
 * Used by every fetcher (movies, books, albums) — the demo seed
 * pipeline is one of those tools that's worth making robust against
 * a flaky archive.org day, since cold-start is ~30 minutes.
 */
internal object Http {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private const val MAX_ATTEMPTS = 4
    private val BACKOFF = listOf(2, 5, 15, 30)  // seconds — 4 attempts max

    /** GET [url] and return the body as a UTF-8 string. Retries transient failures. */
    fun getString(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = buildRequest(url, headers).GET().build()
        return withRetry(url) {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            checkSuccess(resp, url)
            resp.body()
        }
    }

    /**
     * Stream GET [url] to [dest], creating parent dirs as needed. Writes
     * to `<dest>.partial` first, then renames atomically so an
     * interrupted run never leaves [dest] looking like a successful
     * download.
     */
    fun download(url: String, dest: Path, headers: Map<String, String> = emptyMap()) {
        Files.createDirectories(dest.parent)
        val partial = dest.resolveSibling("${dest.fileName}.partial")
        Files.deleteIfExists(partial)
        val req = buildRequest(url, headers).GET().build()
        withRetry(url) {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(partial))
            checkSuccess(resp, url)
        }
        Files.move(partial, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun buildRequest(url: String, headers: Map<String, String>): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(10))  // big videos are slow
        for ((k, v) in headers) b.header(k, v)
        return b
    }

    private fun checkSuccess(resp: HttpResponse<*>, url: String) {
        if (resp.statusCode() !in 200..299) {
            throw IOException("HTTP ${resp.statusCode()} from $url")
        }
    }

    private inline fun <T> withRetry(url: String, block: () -> T): T {
        var lastErr: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                return block()
            } catch (e: IOException) {
                lastErr = e
                if (attempt == MAX_ATTEMPTS - 1) break
                val sleep = BACKOFF[attempt]
                System.err.println("WARN  $url attempt ${attempt + 1}/$MAX_ATTEMPTS failed: ${e.message}; retrying in ${sleep}s")
                Thread.sleep(sleep * 1_000L)
            }
        }
        throw IOException("giving up on $url after $MAX_ATTEMPTS attempts", lastErr)
    }
}
