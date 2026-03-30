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
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.TagService
import net.stewart.mediamanager.service.TranscoderAgent

/**
 * REST endpoints for browsing tags in the Angular web app.
 */
@Blocking
class TagHttpService {

    private val gson = Gson()

    /** Returns all tags with title counts. */
    @Get("/api/v2/catalog/tags")
    fun listTags(ctx: ServiceRequestContext): HttpResponse {
        ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val tags = TagService.getAllTags()
        val titleCounts = TagService.getTagTitleCounts()

        val items = tags.map { tag ->
            mapOf(
                "id" to tag.id,
                "name" to tag.name,
                "bg_color" to tag.bg_color,
                "text_color" to tag.textColor(),
                "title_count" to (titleCounts[tag.id] ?: 0)
            )
        }

        return jsonResponse(gson.toJson(mapOf("tags" to items, "total" to items.size)))
    }

    /** Returns titles for a specific tag as poster cards. */
    @Get("/api/v2/catalog/tags/{tagId}")
    fun tagDetail(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val tag = net.stewart.mediamanager.entity.Tag.findById(tagId)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val hiddenIds = UserTitleFlag.findAll()
            .filter { it.user_id == user.id && it.flag == UserFlagType.HIDDEN.name }
            .map { it.title_id }
            .toSet()

        val taggedTitleIds = TagService.getTitleIdsForTags(setOf(tagId))
        val allTitles = Title.findAll().associateBy { it.id }

        val nasRoot = TranscoderAgent.getNasRoot()
        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val transcodesByTitle = allTranscodes.groupBy { it.title_id }
        val progressByTitle = PlaybackProgressService.getProgressByTitle()

        val titles = taggedTitleIds.mapNotNull { allTitles[it] }
            .filter { !it.hidden && it.id !in hiddenIds }
            .filter { user.canSeeRating(it.content_rating) }
            .sortedBy { it.sort_name?.lowercase() ?: it.name.lowercase() }
            .map { title ->
                val transcodes = transcodesByTitle[title.id] ?: emptyList()
                val playable = transcodes.any { tc ->
                    val fp = tc.file_path ?: return@any false
                    if (TranscoderAgent.needsTranscoding(fp)) {
                        nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
                    } else true
                }
                val progress = progressByTitle[title.id]
                val dur = progress?.duration_seconds
                val progressFraction: Double? = if (progress != null && dur != null && dur > 0) {
                    progress.position_seconds / dur
                } else null

                mapOf(
                    "title_id" to title.id,
                    "title_name" to title.name,
                    "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                    "release_year" to title.release_year,
                    "content_rating" to title.content_rating,
                    "playable" to playable,
                    "progress_fraction" to progressFraction
                )
            }

        val result = mapOf(
            "tag" to mapOf(
                "id" to tag.id,
                "name" to tag.name,
                "bg_color" to tag.bg_color,
                "text_color" to tag.textColor()
            ),
            "titles" to titles,
            "total" to titles.size
        )

        return jsonResponse(gson.toJson(result))
    }

    /** Add a title to a tag (admin only). */
    @Post("/api/v2/catalog/tags/{tagId}/titles/{titleId}")
    fun addTitleToTag(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long,
        @Param("titleId") titleId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        Tag.findById(tagId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        Title.findById(titleId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val exists = TitleTag.findAll().any { it.tag_id == tagId && it.title_id == titleId }
        if (!exists) {
            TitleTag(title_id = titleId, tag_id = tagId).save()
            SearchIndexService.onTitleChanged(titleId)
        }
        return jsonResponse("""{"ok":true}""")
    }

    /** Remove a title from a tag (admin only). */
    @Delete("/api/v2/catalog/tags/{tagId}/titles/{titleId}")
    fun removeTitleFromTag(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long,
        @Param("titleId") titleId: Long
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        TitleTag.findAll()
            .filter { it.tag_id == tagId && it.title_id == titleId }
            .forEach { it.delete() }
        SearchIndexService.onTitleChanged(titleId)
        return jsonResponse("""{"ok":true}""")
    }

    /** Search titles for adding to a tag (admin only). */
    @Get("/api/v2/catalog/tags/{tagId}/search-titles")
    fun searchTitlesForTag(
        ctx: ServiceRequestContext,
        @Param("tagId") tagId: Long,
        @Param("q") q: String
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val taggedIds = TagService.getTitleIdsForTags(setOf(tagId))
        val query = q.trim().lowercase()
        if (query.length < 2) return jsonResponse("""{"results":[]}""")

        val results = Title.findAll()
            .filter { !it.hidden && it.id !in taggedIds }
            .filter { user.canSeeRating(it.content_rating) }
            .filter { it.name.lowercase().contains(query) }
            .sortedBy { it.name.lowercase() }
            .take(20)
            .map { mapOf("title_id" to it.id, "title_name" to it.name, "release_year" to it.release_year) }

        return jsonResponse(gson.toJson(mapOf("results" to results)))
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
