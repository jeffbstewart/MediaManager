package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.service.ImageProxyService
import net.stewart.mediamanager.service.MetricsRegistry
import java.nio.file.Files

/**
 * Authenticated server-side proxy for third-party image CDNs (TMDB, Open
 * Library). Clients never hand over a URL — they pass typed identifiers
 * that map to a small hardcoded allowlist of upstream shapes. See the
 * design notes in the repo commit log for Phase 1 of image proxying.
 *
 * Both routes sit behind [ArmeriaAuthDecorator] — no anonymous access.
 */
@Blocking
class ImageProxyHttpService {

    companion object {
        /** TMDB poster / backdrop / profile size path segments. Closed enum. */
        private val TMDB_SIZES = setOf(
            "w45", "w92", "w154", "w185", "w300", "w342", "w500", "w780",
            "w1280", "h632", "original"
        )

        /** TMDB image file shape — alphanumeric hash, 20–64 chars, lower-case extension. */
        private val TMDB_FILE = Regex("^[a-zA-Z0-9]{20,64}\\.(jpg|jpeg|png|webp)$")

        /** OL cover kinds we allow. */
        private val OL_KINDS = setOf("isbn", "olid", "author", "cover")
        private val OL_SIZES = setOf("S", "M", "L")

        private val ISBN_RE = Regex("^\\d{10,13}$")
        private val OL_EDITION_RE = Regex("^OL\\d+M$")        // edition
        private val OL_WORK_RE = Regex("^OL\\d+W$")           // work (some clients pass these)
        private val OL_AUTHOR_RE = Regex("^OL\\d+A$")         // author
        private val OL_COVER_ID_RE = Regex("^\\d{1,12}$")     // OL numeric cover-image ID

        /** MusicBrainz MBID — UUID v4 shape. */
        private val MBID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        /** Cover Art Archive size segments. Closed enum per CAA docs. */
        private val CAA_SIZES = setOf("front-250", "front-500", "front-1200", "front")
    }

    // ------------------------------------------------------------------
    // TMDB   /proxy/tmdb/{size}/{file}
    // ------------------------------------------------------------------

    @Get("/proxy/tmdb/{size}/{file}")
    fun tmdb(@Param("size") size: String, @Param("file") file: String): HttpResponse {
        if (size !in TMDB_SIZES) return refuse("tmdb", HttpStatus.BAD_REQUEST, "bad size")
        if (!TMDB_FILE.matches(file)) return refuse("tmdb", HttpStatus.BAD_REQUEST, "bad file")

        val extension = file.substringAfterLast('.')
        val upstream = ImageProxyService.ProxiedUpstream(
            provider = ImageProxyService.Provider.TMDB,
            path = "/t/p/$size/$file",
            extension = extension
        )
        return serve(upstream, placeholderSeed = file)
    }

    // ------------------------------------------------------------------
    // Open Library covers
    // ------------------------------------------------------------------

    @Get("/proxy/ol/{kind}/{key}/{size}")
    fun openLibrary(
        @Param("kind") kind: String,
        @Param("key") key: String,
        @Param("size") size: String
    ): HttpResponse {
        if (kind !in OL_KINDS) return refuse("ol", HttpStatus.BAD_REQUEST, "bad kind")
        if (size !in OL_SIZES) return refuse("ol", HttpStatus.BAD_REQUEST, "bad size")
        val keyOk = when (kind) {
            "isbn" -> ISBN_RE.matches(key)
            "olid" -> OL_EDITION_RE.matches(key) || OL_WORK_RE.matches(key)
            "author" -> OL_AUTHOR_RE.matches(key)
            "cover" -> OL_COVER_ID_RE.matches(key)
            else -> false
        }
        if (!keyOk) return refuse("ol", HttpStatus.BAD_REQUEST, "bad key")

        // OL cover URLs:
        //   /b/isbn/{isbn}-{size}.jpg       book covers by ISBN
        //   /b/olid/{olid}-{size}.jpg       book covers by OL edition/work
        //   /b/id/{cover_id}-{size}.jpg     book covers by OL numeric cover ID
        //   /a/olid/{author-olid}-{size}.jpg author photos
        //
        // ?default=false tells OL to return 404 instead of a 1x1 "no cover"
        // placeholder when no real cover exists. The client falls back to
        // its own placeholder in that case.
        val path = when (kind) {
            "isbn" -> "/b/isbn/$key-$size.jpg?default=false"
            "olid" -> "/b/olid/$key-$size.jpg?default=false"
            "cover" -> "/b/id/$key-$size.jpg?default=false"
            "author" -> "/a/olid/$key-$size.jpg?default=false"
            else -> return refuse("ol", HttpStatus.BAD_REQUEST, "bad kind")
        }

        val upstream = ImageProxyService.ProxiedUpstream(
            provider = ImageProxyService.Provider.OPEN_LIBRARY,
            path = path,
            extension = "jpg"
        )
        return serve(upstream, placeholderSeed = key)
    }

    // ------------------------------------------------------------------
    // Cover Art Archive — album covers keyed by MusicBrainz release MBID
    // ------------------------------------------------------------------

    @Get("/proxy/caa/release/{mbid}/{size}")
    fun coverArtArchive(
        @Param("mbid") mbid: String,
        @Param("size") size: String
    ): HttpResponse {
        if (!MBID_RE.matches(mbid)) return refuse("caa", HttpStatus.BAD_REQUEST, "bad mbid")
        if (size !in CAA_SIZES) return refuse("caa", HttpStatus.BAD_REQUEST, "bad size")

        // CAA URL shape: /release/{mbid}/{size}.jpg. The service redirects
        // (typically to archive.org/download/...); the image-proxy
        // redirect-follower with per-hop SSRF screening handles that chain.
        val upstream = ImageProxyService.ProxiedUpstream(
            provider = ImageProxyService.Provider.COVER_ART_ARCHIVE,
            path = "/release/$mbid/$size.jpg",
            extension = "jpg"
        )
        return serve(upstream, placeholderSeed = mbid)
    }

    /**
     * Cover-art lookup by MusicBrainz release-group MBID. Used for the
     * Other Works grid on the artist page: we have the release-group but
     * not a specific release MBID, and CAA serves the canonical
     * release-group cover directly — no extra MB round trip required.
     * Falls back with 404 when MB has no cover art for the group.
     */
    @Get("/proxy/caa/release-group/{rgid}/{size}")
    fun coverArtArchiveReleaseGroup(
        @Param("rgid") rgid: String,
        @Param("size") size: String
    ): HttpResponse {
        if (!MBID_RE.matches(rgid)) return refuse("caa", HttpStatus.BAD_REQUEST, "bad mbid")
        if (size !in CAA_SIZES) return refuse("caa", HttpStatus.BAD_REQUEST, "bad size")
        val upstream = ImageProxyService.ProxiedUpstream(
            provider = ImageProxyService.Provider.COVER_ART_ARCHIVE,
            path = "/release-group/$rgid/$size.jpg",
            extension = "jpg"
        )
        return serve(upstream, placeholderSeed = rgid)
    }

    // ------------------------------------------------------------------
    // Shared path
    // ------------------------------------------------------------------

    /**
     * @param placeholderSeed When set, upstream 404 / 503 returns a
     *   synthesised SVG placeholder via [servePlaceholder] instead of the
     *   raw status. Used so the artist page's "Other works" grid and
     *   similar surfaces don't pollute the network panel with 404s when
     *   CAA / OL has no cover for a given mbid / isbn. Other failure
     *   codes still bubble up — those are real problems worth seeing.
     */
    private fun serve(
        upstream: ImageProxyService.ProxiedUpstream,
        placeholderSeed: String? = null,
    ): HttpResponse {
        return when (val r = ImageProxyService.serve(upstream)) {
            is ImageProxyService.Result.Hit -> {
                val bytes = Files.readAllBytes(r.file)
                val headers = ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.parse(r.contentType))
                    // Long cache: the content of a TMDB path hash or OL ISBN
                    // cover is immutable for all practical purposes. Browsers
                    // and any intermediate caches can pin for hours.
                    .add("Cache-Control", "private, max-age=86400, immutable")
                    .contentLength(bytes.size.toLong())
                    .build()
                MetricsRegistry.countHttpResponse("image_proxy", 200)
                HttpResponse.of(headers, HttpData.wrap(bytes))
            }
            is ImageProxyService.Result.Failure -> {
                if (placeholderSeed != null && (r.httpStatus == 404 || r.httpStatus == 503)) {
                    servePlaceholder(placeholderSeed, "image_proxy")
                } else {
                    MetricsRegistry.countHttpResponse("image_proxy", r.httpStatus)
                    HttpResponse.of(HttpStatus.valueOf(r.httpStatus))
                }
            }
        }
    }

    private fun refuse(label: String, status: HttpStatus, reason: String): HttpResponse {
        MetricsRegistry.countHttpResponse("image_proxy", status.code())
        // Reason is for internal logs via the access log; the HTTP body
        // stays empty so we don't echo client-controlled input.
        return HttpResponse.of(status)
    }
}
