package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.TmdbCollectionPart
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.TranscoderAgent

/**
 * REST endpoints for browsing TMDB collections in the Angular web app.
 */
@Blocking
class CollectionHttpService {

    private val gson = Gson()

    /**
     * Returns all collections that have at least one owned title,
     * with poster URL, owned count, and total parts count.
     */
    @Get("/api/v2/catalog/collections")
    fun listCollections(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val personallyHiddenIds = UserTitleFlag.findAll()
            .filter { it.user_id == user.id && it.flag == UserFlagType.HIDDEN.name }
            .map { it.title_id }
            .toSet()

        val allTitles = Title.findAll()
            .filter { !it.hidden }
            .filter { it.id !in personallyHiddenIds }
            .filter { user.canSeeRating(it.content_rating) }

        val titlesByCollectionId = allTitles
            .filter { it.tmdb_collection_id != null }
            .groupBy { it.tmdb_collection_id!! }

        val collections = TmdbCollection.findAll()
            .filter { it.tmdb_collection_id in titlesByCollectionId }
            .sortedBy { it.name.lowercase() }

        val partsByCollection = TmdbCollectionPart.findAll().groupBy { it.collection_id }

        val items = collections.map { collection ->
            val ownedTitles = titlesByCollectionId[collection.tmdb_collection_id] ?: emptyList()
            val totalParts = partsByCollection[collection.id]?.size ?: ownedTitles.size
            val posterUrl = ownedTitles
                .sortedBy { it.release_year ?: 9999 }
                .firstNotNullOfOrNull { it.posterUrl(PosterSize.THUMBNAIL) }

            mapOf(
                "id" to collection.id,
                "name" to collection.name,
                "poster_url" to posterUrl,
                "owned_count" to ownedTitles.size,
                "total_parts" to totalParts
            )
        }

        return jsonResponse(gson.toJson(mapOf("collections" to items, "total" to items.size)))
    }

    /**
     * Returns a single collection's parts with ownership status,
     * playability, and progress for owned titles.
     */
    @Get("/api/v2/catalog/collections/{collectionId}")
    fun collectionDetail(
        ctx: ServiceRequestContext,
        @Param("collectionId") collectionId: Long
    ): HttpResponse {
        val collection = TmdbCollection.findById(collectionId)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val personallyHiddenIds = UserTitleFlag.findAll()
            .filter { it.user_id == user.id && it.flag == UserFlagType.HIDDEN.name }
            .map { it.title_id }
            .toSet()

        val parts = TmdbCollectionPart.findAll()
            .filter { it.collection_id == collection.id }
            .sortedBy { it.position }

        val ownedTitlesByTmdbId = Title.findAll()
            .filter { it.tmdb_collection_id == collection.tmdb_collection_id }
            .filter { !it.hidden }
            .filter { it.id !in personallyHiddenIds }
            .filter { user.canSeeRating(it.content_rating) }
            .associateBy { it.tmdb_id }

        // Wish status for unowned parts — collection parts are always movies
        val userWishes = WishListItem.findAll()
            .filter { it.user_id == user.id && it.status == WishStatus.ACTIVE.name
                && it.tmdb_media_type == net.stewart.mediamanager.entity.MediaType.MOVIE.name }
            .mapNotNull { it.tmdb_id }
            .toSet()

        val nasRoot = TranscoderAgent.getNasRoot()
        val allTranscodes = net.stewart.mediamanager.entity.Transcode.findAll()
        val progressByTitle = PlaybackProgressService.getProgressByTitleForUser(user.id!!)

        val partList = parts.map { part ->
            val ownedTitle = ownedTitlesByTmdbId[part.tmdb_movie_id]
            if (ownedTitle != null) {
                val transcodes = allTranscodes.filter { it.title_id == ownedTitle.id }
                val playable = transcodes.any { tc ->
                    val fp = tc.file_path ?: return@any false
                    if (TranscoderAgent.needsTranscoding(fp)) {
                        nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
                    } else true
                }
                val progress = progressByTitle[ownedTitle.id]
                val dur = progress?.duration_seconds
                val progressFraction: Double? = if (progress != null && dur != null && dur > 0) {
                    progress.position_seconds / dur
                } else null

                mapOf(
                    "title_id" to ownedTitle.id,
                    "title_name" to ownedTitle.name,
                    "poster_url" to ownedTitle.posterUrl(PosterSize.THUMBNAIL),
                    "release_year" to ownedTitle.release_year,
                    "owned" to true,
                    "playable" to playable,
                    "progress_fraction" to progressFraction
                )
            } else {
                mapOf(
                    "tmdb_movie_id" to part.tmdb_movie_id,
                    "title_name" to part.title,
                    "poster_url" to part.poster_path?.let { "https://image.tmdb.org/t/p/w185$it" },
                    "release_year" to part.release_date?.take(4)?.toIntOrNull(),
                    "owned" to false,
                    "playable" to false,
                    "progress_fraction" to null,
                    "wished" to (part.tmdb_movie_id in userWishes)
                )
            }
        }

        val result = mapOf(
            "id" to collection.id,
            "name" to collection.name,
            "owned_count" to partList.count { it["owned"] == true },
            "total_parts" to partList.size,
            "parts" to partList
        )

        return jsonResponse(gson.toJson(result))
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
