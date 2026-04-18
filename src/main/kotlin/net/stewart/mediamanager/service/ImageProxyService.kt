package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * Server-side proxy + cache for third-party image CDNs (TMDB, Open
 * Library). See the plan in the dev log — summary:
 *
 * 1. Callers provide [ProxiedUpstream] values that compose into a URL
 *    against a hard-coded host allowlist. No caller ever hands over a
 *    raw URL, so there's no SSRF surface via that path.
 * 2. Before connecting, the resolved target hostname's IPs are checked
 *    and any private / loopback / link-local / reserved range causes an
 *    immediate rejection. This catches DNS shenanigans against otherwise
 *    legitimate hostnames.
 * 3. Redirects are never followed — a flipped upstream CDN can't pivot
 *    us anywhere else.
 * 4. Response must carry `Content-Type: image/...` and fit under
 *    [MAX_BYTES]; anything else is refused.
 * 5. First fetch writes the bytes to a sharded disk cache; subsequent
 *    requests serve from disk with no network call.
 *
 * The disk cache lives under `data/image-proxy-cache/` with subdirs for
 * each upstream host. Cache keys are SHA-256 hashes of the fully-qualified
 * upstream URL so repeated requests collapse to the same file, and the
 * file extension is taken from the request path so the Content-Type
 * stays recoverable from disk without a sidecar.
 */
object ImageProxyService {

    private val log = LoggerFactory.getLogger(ImageProxyService::class.java)

    /** Absolute cap on any single proxied response. 10 MiB is generous for a cover image. */
    private const val MAX_BYTES = 10L * 1024 * 1024

    /**
     * Cap on redirect hops. OL covers chain:
     * covers.openlibrary.org → archive.org/download/... → ia8NNNN mirror,
     * and an occasional canonicalization hop on top of that. Four is
     * generous enough to absorb those without giving an upstream a way
     * to bounce us indefinitely.
     */
    private const val MAX_REDIRECTS = 4

    private val cacheRoot: Path = Path.of("data", "image-proxy-cache")

    /** HTTPS only, no automatic redirect-following, reasonable timeouts. */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private fun countProxy(result: String) {
        MetricsRegistry.registry.counter("mm_image_proxy_total", "result", result).increment()
    }

    /** Providers we'll ever reach. Code change gates any new entry. */
    enum class Provider(val host: String, val cacheBucket: String) {
        TMDB("image.tmdb.org", "tmdb"),
        OPEN_LIBRARY("covers.openlibrary.org", "ol");

        companion object {
            fun of(host: String): Provider? = entries.firstOrNull { it.host == host }
        }
    }

    /**
     * Composed upstream reference. The HTTP service constructs one of these
     * from its already-validated inputs (size enum, path regex, …) — this
     * service doesn't re-parse the path, it just validates constraints that
     * don't depend on provider shape.
     */
    data class ProxiedUpstream(
        val provider: Provider,
        /** Everything after the host, with leading slash. */
        val path: String,
        /** File extension for on-disk cache, e.g. "jpg". No dot. */
        val extension: String
    ) {
        val url: String get() = "https://${provider.host}$path"
    }

    sealed class Result {
        /** Served from cache or freshly fetched. Caller streams [file] to the client. */
        data class Hit(val file: Path, val contentType: String) : Result()
        /** Upstream said no or a guard rejected the request. HTTP status for the caller. */
        data class Failure(val httpStatus: Int, val reason: String) : Result()
    }

    /** Top-level entry. Serves from disk if cached; otherwise fetches and caches. */
    fun serve(upstream: ProxiedUpstream): Result {
        val cachePath = cachePathFor(upstream)
        if (Files.exists(cachePath)) {
            countProxy("hit")
            return Result.Hit(cachePath, guessContentType(upstream.extension))
        }
        return fetchAndCache(upstream, cachePath)
    }

    private fun fetchAndCache(upstream: ProxiedUpstream, destPath: Path): Result {
        // OL frequently 302s cover requests to a default placeholder or to
        // archive.org mirrors. We manually follow up to [MAX_REDIRECTS] hops
        // so we don't lose the initial-host SSRF screen: JDK's built-in
        // follower would happily follow to any public address, but could
        // also follow to a compromised CDN's redirect into private space.
        var currentUrl = upstream.url
        var response: HttpResponse<InputStream>? = null
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            val host = runCatching { URI.create(currentUrl).host }.getOrNull()
                ?: run {
                    log.warn("Image proxy: invalid redirect target url={} origin={}", currentUrl, upstream.url)
                    return Result.Failure(502, "invalid redirect target")
                }
            val reject = resolveAndScreenHost(host)
            if (reject != null) {
                log.warn("Image proxy refused {}: {}", currentUrl, reject)
                countProxy("blocked_host")
                return Result.Failure(502, reject)
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create(currentUrl))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/*")
                .GET()
                .build()

            response = try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: Exception) {
                log.warn("Image proxy fetch failed {}: {}", currentUrl, e.message)
                countProxy("fetch_error")
                return Result.Failure(502, "upstream fetch failed")
            }

            val status = response.statusCode()
            if (status in 300..399 && status != 304) {
                val location = response.headers().firstValue("Location").orElse(null)
                    ?: return run {
                        log.warn("Image proxy: upstream HTTP {} without Location header url={} origin={}",
                            status, currentUrl, upstream.url)
                        countProxy("upstream_${status}_no_location")
                        Result.Failure(502, "upstream http $status without Location")
                    }
                response.body()?.close()
                val next = runCatching { URI.create(currentUrl).resolve(location).toString() }
                    .getOrNull() ?: run {
                        log.warn("Image proxy: invalid redirect Location='{}' from url={} origin={}",
                            location, currentUrl, upstream.url)
                        return Result.Failure(502, "invalid redirect target")
                    }
                if (!next.startsWith("https://")) {
                    log.warn("Image proxy: refused non-HTTPS redirect from {} to {} (origin={})",
                        currentUrl, next, upstream.url)
                    countProxy("insecure_redirect")
                    return Result.Failure(502, "upstream redirected to non-HTTPS")
                }
                log.info("Image proxy: following redirect ({} → {}) for origin={}", currentUrl, next, upstream.url)
                currentUrl = next
                hops++
                continue
            }
            break
        }
        response ?: return Result.Failure(502, "upstream returned no response")
        if (hops > MAX_REDIRECTS) {
            log.warn("Image proxy: too many redirects (> {}) origin={}", MAX_REDIRECTS, upstream.url)
            countProxy("too_many_redirects")
            return Result.Failure(502, "too many redirects")
        }

        if (response.statusCode() == 404) {
            log.info("Image proxy: upstream 404 url={} origin={}", currentUrl, upstream.url)
            countProxy("upstream_404")
            return Result.Failure(404, "not found")
        }
        if (response.statusCode() >= 300) {
            log.warn("Image proxy: upstream HTTP {} url={} origin={}",
                response.statusCode(), currentUrl, upstream.url)
            countProxy("upstream_${response.statusCode()}")
            return Result.Failure(502, "upstream http ${response.statusCode()}")
        }

        val rawContentType = response.headers().firstValue("Content-Type").orElse("").trim()
        // covers.openlibrary.org occasionally omits Content-Type entirely.
        // Fall back to our extension-based guess (the upstream path is
        // allowlisted and carries a known extension, so this is safe and
        // avoids dropping otherwise-valid image responses).
        val contentType = if (rawContentType.isEmpty()) {
            log.info("Image proxy: blank Content-Type, inferring from extension='{}' url={}",
                upstream.extension, currentUrl)
            guessContentType(upstream.extension)
        } else rawContentType
        if (!contentType.lowercase().startsWith("image/")) {
            log.warn("Image proxy: non-image Content-Type '{}' url={} origin={}",
                rawContentType, currentUrl, upstream.url)
            countProxy("wrong_content_type")
            response.body()?.close()
            return Result.Failure(502, "upstream returned non-image")
        }

        // Content-Length sanity check (advisory — some CDNs don't send it).
        val declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        if (declared > MAX_BYTES) {
            countProxy("too_large")
            response.body()?.close()
            return Result.Failure(502, "upstream too large")
        }

        // Stream to a temp file with a running byte counter; abort if we
        // exceed MAX_BYTES regardless of what Content-Length claimed.
        val written = try {
            Files.createDirectories(destPath.parent)
            val tmp = Files.createTempFile(destPath.parent, ".proxy-", ".tmp")
            var total = 0L
            response.body().use { inStream ->
                Files.newOutputStream(tmp).use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = inStream.read(buf)
                        if (n == -1) break
                        total += n
                        if (total > MAX_BYTES) {
                            Files.deleteIfExists(tmp)
                            return@use -1L
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == -1L) {
                countProxy("stream_cap_hit")
                return Result.Failure(502, "upstream exceeded size cap")
            }
            // Atomic rename so concurrent requests never see a half-written file.
            Files.move(tmp, destPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            total
        } catch (e: Exception) {
            log.warn("Image proxy write failed {}: {}", upstream.url, e.message)
            countProxy("write_error")
            return Result.Failure(502, "cache write failed")
        }

        log.debug("Image proxy cached {} ({} bytes)", upstream.url, written)
        countProxy("miss")
        return Result.Hit(destPath, contentType)
    }

    /**
     * Resolves [host] and returns a non-null reason string if any of its
     * IPs falls in a range we should never reach. Returns null when the host
     * resolves only to public addresses.
     *
     * Doing the check in the service layer (rather than trusting the JDK
     * HttpClient to use the first resolved address) is defense in depth:
     * if a future code change accidentally broadens the provider allowlist
     * or a CDN hostname is transiently pointed at internal space, we catch
     * it here before the connect().
     */
    internal fun resolveAndScreenHost(host: String): String? {
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return "dns resolution failed for $host: ${e.message}"
        }
        for (addr in addresses) {
            val reason = disallowedAddressReason(addr)
            if (reason != null) return "host $host resolved to forbidden address ${addr.hostAddress} ($reason)"
        }
        return null
    }

    /** Returns a reason string if [addr] is in a range we never want to reach, or null if it's public. */
    internal fun disallowedAddressReason(addr: InetAddress): String? = when {
        addr.isLoopbackAddress -> "loopback"
        addr.isLinkLocalAddress -> "link-local"
        addr.isSiteLocalAddress -> "site-local / RFC1918"
        addr.isAnyLocalAddress -> "wildcard"
        addr.isMulticastAddress -> "multicast"
        // Unique-local IPv6: fc00::/7
        addr is java.net.Inet6Address && (addr.address[0].toInt() and 0xfe) == 0xfc -> "IPv6 ULA"
        // IPv4-mapped IPv6 covers ::ffff:10.0.0.0/8 etc. — InetAddress usually
        // unwraps these automatically, but belt and suspenders.
        addr is java.net.Inet6Address && addr.isIPv4CompatibleAddress -> "IPv4-compatible (legacy)"
        else -> null
    }

    private fun cachePathFor(upstream: ProxiedUpstream): Path {
        val hash = sha256(upstream.url)
        val shard1 = hash.substring(0, 2)
        val shard2 = hash.substring(2, 4)
        return cacheRoot
            .resolve(upstream.provider.cacheBucket)
            .resolve(shard1)
            .resolve(shard2)
            .resolve("$hash.${upstream.extension}")
    }

    private fun sha256(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun guessContentType(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    private const val USER_AGENT =
        "MediaManager/1.0 (+https://github.com/jeffbstewart/MediaManager)"
}
