package net.stewart.mediamanager.grpc

import com.github.vokorm.findAll
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.service.BackdropCacheService
import net.stewart.mediamanager.service.CollectionPosterCacheService
import net.stewart.mediamanager.service.HeadshotCacheService
import net.stewart.mediamanager.service.LocalImageService
import net.stewart.mediamanager.service.OwnershipPhotoService
import net.stewart.mediamanager.service.PosterCacheService
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * Bidirectional streaming image service.
 *
 * Serves all image types (posters, backdrops, headshots, ownership photos, etc.)
 * over a single persistent gRPC stream. Eliminates URL construction on the client.
 *
 * Auth: any authenticated user. OWNERSHIP_PHOTO requires admin role (checked per-request).
 */
class ImageGrpcService : ImageServiceGrpcKt.ImageServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(ImageGrpcService::class.java)

    override fun streamImages(requests: Flow<ImageRequest>): Flow<ImageResponse> = flow {
        val user = currentUser()
        var cancelWatermark = -1

        requests.collect { request ->
            when {
                request.hasCancelStale() -> {
                    cancelWatermark = request.cancelStale.beforeRequestId
                }
                request.hasFetch() -> {
                    val fetch = request.fetch
                    // Skip if cancelled
                    if (fetch.requestId <= cancelWatermark) return@collect

                    val response = handleFetch(fetch, user.isAdmin())
                    emit(response)
                }
            }
        }
    }

    private fun handleFetch(fetch: FetchImage, isAdmin: Boolean): ImageResponse {
        val ref = fetch.ref
        val requestId = fetch.requestId

        // Admin-only check for ownership photos
        if (ref.type == ImageType.IMAGE_TYPE_OWNERSHIP_PHOTO && !isAdmin) {
            return imageResponse {
                this.requestId = requestId
                this.ref = ref
                permissionDenied = imagePermissionDenied {}
            }
        }

        return try {
            val result = resolveImage(ref, fetch.ifNoneMatch)
            when (result) {
                is ImageResult.Found -> imageResponse {
                    this.requestId = requestId
                    this.ref = ref
                    data = imageData {
                        content = ByteString.copyFrom(result.bytes)
                        contentType = result.contentType
                        etag = result.etag
                    }
                }
                is ImageResult.NotModified -> imageResponse {
                    this.requestId = requestId
                    this.ref = ref
                    notModified = imageNotModified {}
                }
                is ImageResult.NotFound -> imageResponse {
                    this.requestId = requestId
                    this.ref = ref
                    notFound = imageNotFound {}
                }
            }
        } catch (e: Exception) {
            log.warn("Error resolving image: type={} err={}", ref.type, e.message)
            imageResponse {
                this.requestId = requestId
                this.ref = ref
                notFound = imageNotFound {}
            }
        }
    }

    // --- Image resolution ---

    private sealed class ImageResult {
        data class Found(val bytes: ByteArray, val contentType: String, val etag: String) : ImageResult()
        data object NotModified : ImageResult()
        data object NotFound : ImageResult()
    }

    private fun resolveImage(ref: ImageRef, ifNoneMatch: String?): ImageResult {
        return when (ref.type) {
            ImageType.IMAGE_TYPE_POSTER_THUMBNAIL -> resolvePoster(ref.titleId, PosterSize.THUMBNAIL, ifNoneMatch)
            ImageType.IMAGE_TYPE_POSTER_FULL -> resolvePoster(ref.titleId, PosterSize.FULL, ifNoneMatch)
            ImageType.IMAGE_TYPE_BACKDROP -> resolveBackdrop(ref.titleId, ifNoneMatch)
            ImageType.IMAGE_TYPE_HEADSHOT -> resolveHeadshot(ref.tmdbPersonId, ifNoneMatch)
            ImageType.IMAGE_TYPE_COLLECTION_POSTER -> resolveCollectionPoster(ref.tmdbCollectionId, ifNoneMatch)
            ImageType.IMAGE_TYPE_LOCAL_IMAGE -> resolveLocalImage(ref.uuid, ifNoneMatch)
            ImageType.IMAGE_TYPE_OWNERSHIP_PHOTO -> resolveOwnershipPhoto(ref.uuid, ifNoneMatch)
            ImageType.IMAGE_TYPE_CAMERA_SNAPSHOT -> resolveCameraSnapshot(ref.cameraId)
            else -> ImageResult.NotFound
        }
    }

    private fun resolvePoster(titleId: Long, size: PosterSize, ifNoneMatch: String?): ImageResult {
        val title = Title.findById(titleId) ?: return ImageResult.NotFound
        if (title.poster_path == null) return ImageResult.NotFound

        // Check content rating against user
        val user = currentUser()
        if (!user.canSeeRating(title.content_rating)) return ImageResult.NotFound

        val etag = title.poster_cache_id ?: title.poster_path!!
        if (ifNoneMatch != null && ifNoneMatch == etag) return ImageResult.NotModified

        // For personal videos with local images
        if (title.media_type == "PERSONAL" && title.poster_cache_id != null) {
            return resolveLocalImage(title.poster_cache_id!!, ifNoneMatch)
        }

        val path = PosterCacheService.cacheAndServe(title, size) ?: return ImageResult.NotFound
        if (!Files.exists(path)) return ImageResult.NotFound
        return ImageResult.Found(Files.readAllBytes(path), "image/jpeg", etag)
    }

    private fun resolveBackdrop(titleId: Long, ifNoneMatch: String?): ImageResult {
        val title = Title.findById(titleId) ?: return ImageResult.NotFound
        if (title.backdrop_path == null) return ImageResult.NotFound

        val etag = title.backdrop_cache_id ?: title.backdrop_path!!
        if (ifNoneMatch != null && ifNoneMatch == etag) return ImageResult.NotModified

        val path = BackdropCacheService.cacheAndServe(title) ?: return ImageResult.NotFound
        if (!Files.exists(path)) return ImageResult.NotFound
        return ImageResult.Found(Files.readAllBytes(path), "image/jpeg", etag)
    }

    private fun resolveHeadshot(tmdbPersonId: Int, ifNoneMatch: String?): ImageResult {
        val castMember = CastMember.findAll().firstOrNull { it.tmdb_person_id == tmdbPersonId }
            ?: return ImageResult.NotFound
        if (castMember.profile_path == null) return ImageResult.NotFound

        val etag = castMember.headshot_cache_id ?: castMember.profile_path!!
        if (ifNoneMatch != null && ifNoneMatch == etag) return ImageResult.NotModified

        val path = HeadshotCacheService.cacheAndServe(castMember) ?: return ImageResult.NotFound
        if (!Files.exists(path)) return ImageResult.NotFound
        return ImageResult.Found(Files.readAllBytes(path), "image/jpeg", etag)
    }

    private fun resolveCollectionPoster(tmdbCollectionId: Int, ifNoneMatch: String?): ImageResult {
        val collection = TmdbCollection.findAll().firstOrNull { it.tmdb_collection_id == tmdbCollectionId }
            ?: return ImageResult.NotFound
        if (collection.poster_path == null) return ImageResult.NotFound

        val etag = "collection-${tmdbCollectionId}-${collection.poster_path}"
        if (ifNoneMatch != null && ifNoneMatch == etag) return ImageResult.NotModified

        val path = CollectionPosterCacheService.cacheAndServe(collection) ?: return ImageResult.NotFound
        if (!Files.exists(path)) return ImageResult.NotFound
        return ImageResult.Found(Files.readAllBytes(path), "image/jpeg", etag)
    }

    private fun resolveLocalImage(uuid: String, ifNoneMatch: String?): ImageResult {
        if (uuid.isBlank()) return ImageResult.NotFound

        val etag = "local-$uuid"
        if (ifNoneMatch != null && ifNoneMatch == etag) return ImageResult.NotModified

        val file = LocalImageService.getFile(uuid) ?: return ImageResult.NotFound
        if (!file.exists()) return ImageResult.NotFound
        val contentType = LocalImageService.getContentType(uuid) ?: "image/jpeg"
        return ImageResult.Found(file.readBytes(), contentType, etag)
    }

    private fun resolveOwnershipPhoto(uuid: String, ifNoneMatch: String?): ImageResult {
        if (uuid.isBlank()) return ImageResult.NotFound

        val etag = "photo-$uuid"
        if (ifNoneMatch != null && ifNoneMatch == etag) return ImageResult.NotModified

        val file = OwnershipPhotoService.getFile(uuid) ?: return ImageResult.NotFound
        if (!file.exists()) return ImageResult.NotFound
        val contentType = OwnershipPhotoService.getContentType(uuid) ?: "image/jpeg"
        return ImageResult.Found(file.readBytes(), contentType, etag)
    }

    // TODO: Camera.snapshot_url field exists but isn't used — snapshots currently
    // always go through go2rtc's frame.jpeg endpoint. Revisit: when snapshot_url is
    // populated, hit the camera directly (faster, no go2rtc dependency).
    private fun resolveCameraSnapshot(cameraId: Long): ImageResult {
        val camera = net.stewart.mediamanager.entity.Camera.findById(cameraId)
            ?: return ImageResult.NotFound
        if (!camera.enabled) return ImageResult.NotFound

        // Proxy snapshot from go2rtc (matches CameraStreamServlet.proxySnapshot behavior)
        val agent = net.stewart.mediamanager.service.Go2rtcAgent.instance
            ?: return ImageResult.NotFound
        val apiPort = agent.apiPort
        val url = "http://127.0.0.1:$apiPort/api/frame.jpeg?src=${camera.go2rtc_name}"

        return try {
            val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return ImageResult.NotFound
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            // Camera snapshots are never cached (live content) — empty etag
            ImageResult.Found(bytes, "image/jpeg", "")
        } catch (e: Exception) {
            log.warn("Camera snapshot failed for {}: {}", camera.go2rtc_name, e.message)
            ImageResult.NotFound
        }
    }
}
