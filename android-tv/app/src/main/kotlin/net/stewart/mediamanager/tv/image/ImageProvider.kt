package net.stewart.mediamanager.tv.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.ImageRef
import net.stewart.mediamanager.grpc.ImageType
import net.stewart.mediamanager.grpc.imageRef

val LocalImageProvider = staticCompositionLocalOf<ImageProvider> {
    error("No ImageProvider provided")
}

/**
 * High-level image loading API backed by bidi gRPC streaming + LRU cache.
 * Uses stale-while-revalidate: returns cached image instantly, refreshes in background.
 */
class ImageProvider(
    private val streamClient: ImageStreamClient,
    private val cache: ImageDiskCache
) {
    private val backgroundScope = CoroutineScope(Dispatchers.IO)

    /** Synchronous memory-only lookup for instant composable display. */
    fun getCachedBitmap(ref: ImageRef): Bitmap? = cache.getFromMemory(ref)

    /**
     * Load an image. Checks cache first (returns cached + revalidates in background),
     * then fetches from gRPC if cache miss.
     */
    suspend fun image(ref: ImageRef): Bitmap? {
        // Cache hit — return immediately, revalidate in background
        cache.get(ref)?.let { (bitmap, etag) ->
            backgroundScope.launch { revalidate(ref, etag) }
            return bitmap
        }

        // Cache miss — fetch synchronously
        val etag = cache.etag(ref)
        val response = streamClient.fetch(ref, etag) ?: return null

        return when {
            response.hasData() -> {
                val data = response.data
                cache.store(ref, data.content.toByteArray(), data.contentType, data.etag)
                BitmapFactory.decodeByteArray(data.content.toByteArray(), 0, data.content.size())
            }
            response.hasNotModified() -> cache.get(ref)?.first
            else -> null
        }
    }

    /** Cancel all in-flight image requests (call when navigating away). */
    suspend fun cancelPending() {
        streamClient.cancelStale()
    }

    private suspend fun revalidate(ref: ImageRef, etag: String) {
        val response = streamClient.fetch(ref, etag) ?: return
        when {
            response.hasData() -> {
                val data = response.data
                cache.store(ref, data.content.toByteArray(), data.contentType, data.etag)
            }
            response.hasNotFound() -> cache.remove(ref)
        }
    }

    fun shutdown() {
        streamClient.shutdown()
    }
}

// ── ImageRef factory functions ───────────────────────────────────────

fun posterRef(titleId: Long) = imageRef {
    type = ImageType.IMAGE_TYPE_POSTER_FULL
    this.titleId = titleId
}

fun posterThumbnailRef(titleId: Long) = imageRef {
    type = ImageType.IMAGE_TYPE_POSTER_THUMBNAIL
    this.titleId = titleId
}

fun backdropRef(titleId: Long) = imageRef {
    type = ImageType.IMAGE_TYPE_BACKDROP
    this.titleId = titleId
}

fun headshotRef(tmdbPersonId: Int) = imageRef {
    type = ImageType.IMAGE_TYPE_HEADSHOT
    this.tmdbPersonId = tmdbPersonId
}

fun collectionPosterRef(tmdbCollectionId: Int) = imageRef {
    type = ImageType.IMAGE_TYPE_COLLECTION_POSTER
    this.tmdbCollectionId = tmdbCollectionId
}

fun localImageRef(uuid: String) = imageRef {
    type = ImageType.IMAGE_TYPE_LOCAL_IMAGE
    this.uuid = uuid
}
