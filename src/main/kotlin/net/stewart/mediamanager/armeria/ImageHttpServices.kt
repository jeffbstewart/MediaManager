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
import net.stewart.mediamanager.entity.Author
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.service.AuthorHeadshotCacheService
import net.stewart.mediamanager.service.BackdropCacheService
import net.stewart.mediamanager.service.CollectionPosterCacheService
import net.stewart.mediamanager.service.HeadshotCacheService
import net.stewart.mediamanager.service.ImageProxyService
import net.stewart.mediamanager.service.LocalImageService
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.OwnershipPhotoService
import net.stewart.mediamanager.service.PosterCacheService
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

class PosterHttpService {

    @Blocking
    @Get("/posters/{size}/{titleId}")
    fun poster(ctx: ServiceRequestContext, @Param("size") size: String, @Param("titleId") titleId: Long): HttpResponse {
        val posterSize = PosterSize.entries.firstOrNull { it.pathSegment == size }
            ?: return badRequest("poster")

        val title = Title.findById(titleId)
        if (title == null || title.poster_path == null) return notFound("poster")

        val user = ArmeriaAuthDecorator.getUser(ctx)
        if (user != null && !user.canSeeRating(title.content_rating)) return forbidden("poster")

        val cached = PosterCacheService.cacheAndServe(title, posterSize)
        if (cached != null && Files.exists(cached)) {
            return serveFile(cached, "image/jpeg", "poster")
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
                "poster"
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
                "poster"
            )
        }
        if (!isValidTmdbPath(path)) return notFound("poster")
        val extension = path.substringAfterLast('.', "jpg")
        return serveProxied(
            ImageProxyService.Provider.TMDB,
            "/t/p/${posterSize.pathSegment}$path",
            extension,
            "poster"
        )
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
