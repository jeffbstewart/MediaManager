package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.github.vokorm.findAll
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.TitleFamilyMember
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.SimilarTitlesService
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.mediamanager.service.UserTitleFlagService
import java.time.LocalDateTime

/**
 * REST endpoint for the title detail page.
 *
 * Returns all data needed to render a movie, TV show, or personal video
 * detail page in a single request. Behind [ArmeriaAuthDecorator].
 */
@Blocking
class TitleDetailHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/titles/{titleId}")
    fun titleDetail(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val userId = user.id!!

        val title = Title.findById(titleId)
            ?: return jsonError(HttpStatus.NOT_FOUND, "Title not found")

        // Rating enforcement
        if (!user.canSeeRating(title.content_rating)) {
            return jsonError(HttpStatus.FORBIDDEN, "Content restricted")
        }

        val isPersonal = title.media_type == MMMediaType.PERSONAL.name
        val isTv = title.media_type == MMMediaType.TV.name

        // User flags
        val userFlags = UserTitleFlag.findAll().filter { it.user_id == userId && it.title_id == titleId }
        val isStarred = userFlags.any { it.flag == UserFlagType.STARRED.name }
        val isHidden = userFlags.any { it.flag == UserFlagType.HIDDEN.name }

        // Genres
        val genres = if (!isPersonal) {
            val genreIds = TitleGenre.findAll().filter { it.title_id == titleId }.map { it.genre_id }.toSet()
            Genre.findAll().filter { it.id in genreIds }.map { it.name }
        } else emptyList()

        // Tags
        val tagIds = TitleTag.findAll().filter { it.title_id == titleId }.map { it.tag_id }.toSet()
        val tags = Tag.findAll().filter { it.id in tagIds }.map { tag ->
            mapOf("id" to tag.id, "name" to tag.name, "bg_color" to tag.bg_color, "text_color" to tag.textColor())
        }

        // Formats from linked media items
        val mediaItemIds = MediaItemTitle.findAll().filter { it.title_id == titleId }.map { it.media_item_id }.toSet()
        val formats = MediaItem.findAll()
            .filter { it.id in mediaItemIds }
            .map { it.media_format }
            .distinct()
            .filter { it != MediaFormat.UNKNOWN.name && it != MediaFormat.OTHER.name }

        // Transcodes with playability
        val nasRoot = TranscoderAgent.getNasRoot()
        val transcodes = Transcode.findAll().filter { it.title_id == titleId && it.file_path != null }
        val progressMap = PlaybackProgressService.getProgressByTitleForUser(userId)
        val transcodeProgress = transcodes.associate { tc ->
            val progress = PlaybackProgressService.getProgressForUser(userId, tc.id!!)
            tc.id!! to progress
        }

        val transcodeList = transcodes.map { tc ->
            val fp = tc.file_path!!
            val needsTranscoding = TranscoderAgent.needsTranscoding(fp)
            val isTranscoded = if (needsTranscoding) {
                nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
            } else true
            val playable = isTranscoded
            val progress = transcodeProgress[tc.id]
            val episode = tc.episode_id?.let { Episode.findById(it) }

            val result = mutableMapOf<String, Any?>(
                "transcode_id" to tc.id,
                "file_name" to fp.substringAfterLast('/').substringAfterLast('\\'),
                "playable" to playable,
                "media_format" to (MediaItem.findAll()
                    .firstOrNull { it.id in mediaItemIds }?.media_format),
            )
            if (progress != null) {
                result["position_seconds"] = progress.position_seconds
                result["duration_seconds"] = progress.duration_seconds
            }
            if (episode != null) {
                result["season_number"] = episode.season_number
                result["episode_number"] = episode.episode_number
                result["episode_name"] = episode.name
            }
            result
        }

        // Cast (non-personal)
        val cast = if (!isPersonal) {
            CastMember.findAll()
                .filter { it.title_id == titleId }
                .sortedBy { it.cast_order }
                .map { cm ->
                    mapOf(
                        "id" to cm.id,
                        "name" to cm.name,
                        "character_name" to cm.character_name,
                        "headshot_url" to if (cm.headshot_cache_id != null) "/headshots/${cm.id}" else null,
                        "tmdb_person_id" to cm.tmdb_person_id
                    )
                }
        } else emptyList()

        // Episodes (TV)
        val episodes = if (isTv) {
            Episode.findAll()
                .filter { it.title_id == titleId }
                .sortedWith(compareBy({ it.season_number }, { it.episode_number }))
                .map { ep ->
                    val epTranscode = transcodes.firstOrNull { it.episode_id == ep.id }
                    val epProgress = epTranscode?.let { transcodeProgress[it.id] }
                    val epPlayable = epTranscode?.let { tc ->
                        val fp = tc.file_path!!
                        if (TranscoderAgent.needsTranscoding(fp)) {
                            nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
                        } else true
                    } ?: false

                    mapOf(
                        "id" to ep.id,
                        "season_number" to ep.season_number,
                        "episode_number" to ep.episode_number,
                        "name" to ep.name,
                        "transcode_id" to epTranscode?.id,
                        "playable" to epPlayable,
                        "position_seconds" to epProgress?.position_seconds,
                        "duration_seconds" to epProgress?.duration_seconds
                    )
                }
        } else emptyList()

        // Seasons (TV)
        val seasons = if (isTv) {
            TitleSeason.findAll()
                .filter { it.title_id == titleId && it.season_number > 0 }
                .sortedBy { it.season_number }
                .map { s ->
                    mapOf(
                        "season_number" to s.season_number,
                        "acquisition_status" to s.acquisition_status
                    )
                }
        } else emptyList()

        // Family members (personal)
        val familyMembers = if (isPersonal) {
            val fmtIds = TitleFamilyMember.findAll()
                .filter { it.title_id == titleId }
                .map { it.family_member_id }
                .toSet()
            FamilyMember.findAll()
                .filter { it.id in fmtIds }
                .map { fm -> mapOf("id" to fm.id, "name" to fm.name) }
        } else emptyList()

        // Similar titles (non-personal)
        val similarTitles = if (!isPersonal) {
            SimilarTitlesService.getSimilarTitlesForUser(title, userId, 12)
                .map { t ->
                    mapOf(
                        "title_id" to t.id,
                        "title_name" to t.name,
                        "poster_url" to t.posterUrl(PosterSize.THUMBNAIL),
                        "release_year" to t.release_year
                    )
                }
        } else emptyList()

        // Collection
        val collection = if (title.tmdb_collection_id != null) {
            val col = TmdbCollection.findAll().firstOrNull { it.tmdb_collection_id == title.tmdb_collection_id }
            if (col != null) {
                mapOf("id" to col.id, "name" to col.name)
            } else null
        } else null

        val response = mapOf(
            "title_id" to title.id,
            "title_name" to title.name,
            "media_type" to title.media_type,
            "release_year" to title.release_year,
            "description" to title.description,
            "content_rating" to title.content_rating,
            "poster_url" to title.posterUrl(PosterSize.FULL),
            "backdrop_url" to title.backdropUrl(),
            "event_date" to title.event_date?.toString(),
            "is_starred" to isStarred,
            "is_hidden" to isHidden,
            "genres" to genres,
            "tags" to tags,
            "formats" to formats,
            "transcodes" to transcodeList,
            "cast" to cast,
            "episodes" to episodes,
            "seasons" to seasons,
            "family_members" to familyMembers,
            "similar_titles" to similarTitles,
            "collection" to collection
        )

        return HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(response))
            .build()
    }

    /** Toggle star flag on a title for the current user. */
    @Post("/api/v2/catalog/titles/{titleId}/star")
    fun toggleStar(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        Title.findById(titleId) ?: return jsonError(HttpStatus.NOT_FOUND, "Title not found")

        val wasStarred = UserTitleFlagService.hasFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        if (wasStarred) UserTitleFlagService.clearFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        else UserTitleFlagService.setFlagForUser(user.id!!, titleId, UserFlagType.STARRED)
        val starred = !wasStarred
        return jsonOk(mapOf("is_starred" to starred))
    }

    /** Toggle hidden flag on a title for the current user. */
    @Post("/api/v2/catalog/titles/{titleId}/hide")
    fun toggleHide(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        Title.findById(titleId) ?: return jsonError(HttpStatus.NOT_FOUND, "Title not found")

        val wasHidden = UserTitleFlagService.hasFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        if (wasHidden) UserTitleFlagService.clearFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        else UserTitleFlagService.setFlagForUser(user.id!!, titleId, UserFlagType.HIDDEN)
        val hidden = !wasHidden
        return jsonOk(mapOf("is_hidden" to hidden))
    }

    /** Set tags for a title (admin only). Replaces all existing tags. */
    @Post("/api/v2/catalog/titles/{titleId}/tags")
    fun setTags(ctx: ServiceRequestContext, @Param("titleId") titleId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        Title.findById(titleId) ?: return jsonError(HttpStatus.NOT_FOUND, "Title not found")

        val body = gson.fromJson(ctx.request().aggregate().join().contentUtf8(), Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val tagIds = (body["tag_ids"] as? List<Number>)?.map { it.toLong() } ?: emptyList()

        // Remove existing tags
        TitleTag.findAll().filter { it.title_id == titleId }.forEach { it.delete() }
        // Add new tags
        for (tagId in tagIds) {
            TitleTag(title_id = titleId, tag_id = tagId).save()
        }
        SearchIndexService.onTitleChanged(titleId)

        return jsonOk(mapOf("ok" to true))
    }

    private fun jsonOk(data: Any): HttpResponse =
        HttpResponse.builder()
            .status(HttpStatus.OK)
            .content(MediaType.JSON_UTF_8, gson.toJson(data))
            .build()

    private fun jsonError(status: HttpStatus, message: String): HttpResponse =
        HttpResponse.builder()
            .status(status)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to message)))
            .build()
}
