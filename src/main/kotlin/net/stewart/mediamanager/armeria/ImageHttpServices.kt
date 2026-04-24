package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.service.ArtistHeadshotCacheService
import net.stewart.mediamanager.service.AuthorHeadshotCacheService
import net.stewart.mediamanager.service.BackdropCacheService
import net.stewart.mediamanager.service.CollectionPosterCacheService
import net.stewart.mediamanager.service.HeadshotCacheService
import net.stewart.mediamanager.service.ImageProxyService
import net.stewart.mediamanager.service.LocalImageService
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.OwnershipPhotoService
import net.stewart.mediamanager.service.PosterCacheService
import net.stewart.mediamanager.service.PublicArtTokenService
import java.nio.file.Files
import java.nio.file.Path

private val TMDB_PATH_REGEX = Regex("^/[a-zA-Z0-9/_.-]+$")

private fun isValidTmdbPath(path: String): Boolean =
    path.startsWith("/") && !path.startsWith("//") && path.matches(TMDB_PATH_REGEX)

private fun serveFile(path: Path, contentType: String, metric: String, cacheControl: String = "max-age=31536000, immutable"): HttpResponse {
    val headers = ResponseHeaders.builder(HttpStatus.OK)
        .contentType(MediaType.parse(contentType))
        .add("Cache-Control", cacheControl)
        .contentLength(Files.size(path))
        .build()
    MetricsRegistry.countHttpResponse(metric, 200)
    return HttpResponse.of(headers, HttpData.wrap(Files.readAllBytes(path)))
}

/**
 * Fetches a third-party image through [ImageProxyService] and serves the
 * bytes from our origin. Replaces the previous 302-redirect fallback so
 * client IPs no longer leak to TMDB / Open Library when the local UUID-
 * keyed cache hasn't populated yet (cache misses here are quickly filled
 * by the proxy's own disk cache under data/image-proxy-cache/).
 */
private fun serveProxied(
    provider: ImageProxyService.Provider,
    path: String,
    extension: String,
    metric: String
): HttpResponse {
    val upstream = ImageProxyService.ProxiedUpstream(provider, path, extension)
    return when (val r = ImageProxyService.serve(upstream)) {
        is ImageProxyService.Result.Hit -> serveFile(r.file, r.contentType, metric)
        is ImageProxyService.Result.Failure -> {
            MetricsRegistry.countHttpResponse(metric, r.httpStatus)
            HttpResponse.of(HttpStatus.valueOf(r.httpStatus))
        }
    }
}

private fun notFound(metric: String): HttpResponse {
    MetricsRegistry.countHttpResponse(metric, 404)
    return HttpResponse.of(HttpStatus.NOT_FOUND)
}

private fun badRequest(metric: String): HttpResponse {
    MetricsRegistry.countHttpResponse(metric, 400)
    return HttpResponse.of(HttpStatus.BAD_REQUEST)
}

private fun forbidden(metric: String): HttpResponse {
    MetricsRegistry.countHttpResponse(metric, 403)
    return HttpResponse.of(HttpStatus.FORBIDDEN)
}

/**
 * Resolve the poster bytes for a title at a requested size, going through
 * the local cache first and the upstream proxy second. Returns NOT_FOUND
 * when the title has no poster on file. Caller is responsible for the
 * rating ceiling check before invoking this.
 */
private fun servePosterFor(title: Title, posterSize: PosterSize, metric: String): HttpResponse {
    if (title.poster_path == null) return notFound(metric)

    val cached = PosterCacheService.cacheAndServe(title, posterSize)
    if (cached != null && Files.exists(cached)) {
        return serveFile(cached, "image/jpeg", metric)
    }

    val path = title.poster_path!!
    // Books store "isbn/<isbn>" — serve via the OL proxy.
    if (path.startsWith("isbn/")) {
        val isbn = path.removePrefix("isbn/")
        val olSize = when (posterSize) {
            PosterSize.THUMBNAIL -> "M"
            PosterSize.FULL -> "L"
        }
        return serveProxied(
            ImageProxyService.Provider.OPEN_LIBRARY,
            // ?default=false → 404 instead of 1x1 placeholder GIF.
            "/b/isbn/$isbn-$olSize.jpg?default=false",
            "jpg",
            metric
        )
    }
    // Albums store "caa/<release-mbid>" — serve via the Cover Art Archive proxy.
    if (path.startsWith("caa/")) {
        val mbid = path.removePrefix("caa/")
        val caaSize = when (posterSize) {
            PosterSize.THUMBNAIL -> "front-250"
            PosterSize.FULL -> "front-500"
        }
        return serveProxied(
            ImageProxyService.Provider.COVER_ART_ARCHIVE,
            "/release/$mbid/$caaSize.jpg",
            "jpg",
            metric
        )
    }
    if (!isValidTmdbPath(path)) return notFound(metric)
    val extension = path.substringAfterLast('.', "jpg")
    return serveProxied(
        ImageProxyService.Provider.TMDB,
        "/t/p/${posterSize.pathSegment}$path",
        extension,
        metric
    )
}

class PosterHttpService {

    @Blocking
    @Get("/posters/{size}/{titleId}")
    fun poster(ctx: ServiceRequestContext, @Param("size") size: String, @Param("titleId") titleId: Long): HttpResponse {
        val posterSize = PosterSize.entries.firstOrNull { it.pathSegment == size }
            ?: return badRequest("poster")

        val title = Title.findById(titleId) ?: return notFound("poster")

        val user = ArmeriaAuthDecorator.getUser(ctx)
        if (user != null && !user.canSeeRating(title.content_rating)) return forbidden("poster")

        return servePosterFor(title, posterSize, "poster")
    }
}

/**
 * Unauthenticated artwork endpoint, gated by a short-lived signed token.
 * Used by the web-app's MediaSession integration so iOS / macOS lock-screen
 * now-playing UI can render album art (the OS-level fetch doesn't share
 * the browser's auth cookies, so the normal `/posters/...` route 401s).
 *
 * Token is minted by `/api/v2/public-art-token?title_id=...` and validated
 * here by [PublicArtTokenService]. Always serves the FULL (w500) size —
 * lock screens want a large, square image.
 *
 * Registered with `blockingNoAuth` in ArmeriaServer.
 */
class PublicAlbumArtHttpService {

    @Blocking
    @Get("/public/album-art/{token}")
    fun publicAlbumArt(@Param("token") token: String): HttpResponse {
        val titleId = PublicArtTokenService.validate(token) ?: return notFound("public_album_art")
        val title = Title.findById(titleId) ?: return notFound("public_album_art")
        return servePosterFor(title, PosterSize.FULL, "public_album_art")
    }
}

/**
 * Authenticated mint endpoint. Returns a 12-hour signed token usable only
 * against [PublicAlbumArtHttpService] for the given title id. The user
 * must be able to see this title's content rating; we don't want a
 * low-rating viewer minting tokens for adult-rated artwork.
 */
class PublicArtTokenHttpService {

    @Blocking
    @Get("/api/v2/public-art-token")
    fun mintToken(ctx: ServiceRequestContext, @Param("title_id") titleId: Long): HttpResponse {
        val title = Title.findById(titleId) ?: return notFound("public_art_token")
        val user = ArmeriaAuthDecorator.getUser(ctx)
        if (user != null && !user.canSeeRating(title.content_rating)) return forbidden("public_art_token")
        val token = PublicArtTokenService.mint(titleId)
        val body = "{\"token\":\"$token\",\"ttl_seconds\":${PublicArtTokenService.TOKEN_TTL_SECONDS}}"
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .build()
        MetricsRegistry.countHttpResponse("public_art_token", 200)
        return HttpResponse.of(headers, HttpData.ofUtf8(body))
    }
}

class HeadshotHttpService {

    @Blocking
    @Get("/headshots/{castMemberId}")
    fun headshot(@Param("castMemberId") castMemberId: Long): HttpResponse {
        val castMember = CastMember.findById(castMemberId)
        if (castMember == null || castMember.profile_path == null) return notFound("headshot")

        val cached = HeadshotCacheService.cacheAndServe(castMember)
        if (cached != null && Files.exists(cached)) {
            return serveFile(cached, "image/jpeg", "headshot")
        }

        val path = castMember.profile_path!!
        if (!isValidTmdbPath(path)) return notFound("headshot")
        val extension = path.substringAfterLast('.', "jpg")
        return serveProxied(
            ImageProxyService.Provider.TMDB,
            "/t/p/w185$path",
            extension,
            "headshot"
        )
    }
}

class AuthorHeadshotHttpService {

    @Blocking
    @Get("/author-headshots/{authorId}")
    fun authorHeadshot(@Param("authorId") authorId: Long): HttpResponse {
        val author = Author.findById(authorId) ?: return notFound("author-headshot")
        val cached = AuthorHeadshotCacheService.cacheAndServe(author)
        return if (cached != null && Files.exists(cached)) {
            serveFile(cached, "image/jpeg", "author-headshot")
        } else {
            notFound("author-headshot")
        }
    }
}

class ArtistHeadshotHttpService {

    @Blocking
    @Get("/artist-headshots/{artistId}")
    fun artistHeadshot(@Param("artistId") artistId: Long): HttpResponse {
        val artist = Artist.findById(artistId) ?: return notFound("artist-headshot")
        val cached = ArtistHeadshotCacheService.cacheAndServe(artist)
        return if (cached != null && Files.exists(cached)) {
            serveFile(cached, "image/jpeg", "artist-headshot")
        } else {
            notFound("artist-headshot")
        }
    }
}

class BackdropHttpService {

    @Blocking
    @Get("/backdrops/{titleId}")
    fun backdrop(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val title = Title.findById(titleId)
        if (title == null || title.backdrop_path == null) return notFound("backdrop")

        val user = ArmeriaAuthDecorator.getUser(ctx)
        if (user != null && !user.canSeeRating(title.content_rating)) return forbidden("backdrop")

        val cached = BackdropCacheService.cacheAndServe(title)
        if (cached != null && Files.exists(cached)) {
            return serveFile(cached, "image/jpeg", "backdrop")
        }

        val path = title.backdrop_path!!
        if (!isValidTmdbPath(path)) return notFound("backdrop")
        val extension = path.substringAfterLast('.', "jpg")
        return serveProxied(
            ImageProxyService.Provider.TMDB,
            "/t/p/w1280$path",
            extension,
            "backdrop"
        )
    }
}

class CollectionPosterHttpService {

    @Blocking
    @Get("/collection-posters/{collectionId}")
    fun collectionPoster(@Param("collectionId") collectionId: Int): HttpResponse {
        val collection = TmdbCollection.findAll().firstOrNull { it.tmdb_collection_id == collectionId }
        if (collection == null || collection.poster_path == null) return notFound("collection-poster")

        val cached = CollectionPosterCacheService.cacheAndServe(collection)
        if (cached != null && Files.exists(cached)) {
            return serveFile(cached, "image/jpeg", "collection-poster")
        }

        val path = collection.poster_path!!
        if (!isValidTmdbPath(path)) return notFound("collection-poster")
        val extension = path.substringAfterLast('.', "jpg")
        return serveProxied(
            ImageProxyService.Provider.TMDB,
            "/t/p/w500$path",
            extension,
            "collection-poster"
        )
    }
}

class LocalImageHttpService {

    @Blocking
    @Get("/local-images/{uuid}")
    fun localImage(@Param("uuid") uuid: String): HttpResponse {
        if (uuid.isBlank() || uuid.contains("/")) return badRequest("local_image")

        val contentType = LocalImageService.getContentType(uuid)
        val file = LocalImageService.getFile(uuid)
        if (contentType == null || file == null) return notFound("local_image")

        return serveFile(file.toPath(), contentType, "local_image")
    }
}

class OwnershipPhotoHttpService {

    @Blocking
    @Get("/ownership-photos/{uuid}")
    fun ownershipPhoto(ctx: ServiceRequestContext, @Param("uuid") uuid: String): HttpResponse {
        if (uuid.isBlank() || uuid.contains("/")) return badRequest("ownership_photo")

        val contentType = OwnershipPhotoService.getContentType(uuid)
        val file = OwnershipPhotoService.getFile(uuid)
        if (contentType == null || file == null) return notFound("ownership_photo")

        val download = ctx.queryParams().get("download") == "1"
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.parse(contentType))
            .contentLength(file.length())
        if (download) {
            headers.add("Content-Disposition", "attachment; filename=\"${file.name}\"")
        } else {
            headers.add("Cache-Control", "max-age=31536000, immutable")
        }
        MetricsRegistry.countHttpResponse("ownership_photo", 200)
        return HttpResponse.of(headers.build(), HttpData.wrap(Files.readAllBytes(file.toPath())))
    }
}
