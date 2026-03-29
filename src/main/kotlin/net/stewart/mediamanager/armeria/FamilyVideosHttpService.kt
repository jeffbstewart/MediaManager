package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.service.FamilyMemberService
import net.stewart.mediamanager.service.PersonalVideoService
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.PosterGridHelper
import net.stewart.mediamanager.service.TagService
import net.stewart.mediamanager.service.TranscoderAgent

/**
 * REST endpoint for the personal/family videos page in the Angular web app.
 * Returns videos as cards with event dates, family members, tags, and descriptions.
 */
@Blocking
class FamilyVideosHttpService {

    private val gson = Gson()

    @Get("/api/v2/catalog/family-videos")
    fun listFamilyVideos(
        ctx: ServiceRequestContext,
        @Param("sort") @Default("date_desc") sort: String,
        @Param("members") @Default("") members: String,
        @Param("playable_only") @Default("false") playableOnly: Boolean
    ): HttpResponse {
        ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        var titles = PersonalVideoService.getAllPersonalVideos()

        // Filter by family members
        val memberIds = members.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        if (memberIds.isNotEmpty()) {
            val titleIdsForMembers = FamilyMemberService.getTitleIdsForMembers(memberIds)
            titles = titles.filter { it.id in titleIdsForMembers }
        }

        // Compute playability
        val playableTitleIds = PosterGridHelper.computePlayableTitleIds()
        if (playableOnly) {
            titles = titles.filter { it.id in playableTitleIds }
        }

        // Sort
        titles = when (sort) {
            "date_asc" -> titles.sortedBy { it.event_date }
            "name" -> titles.sortedBy { (it.sort_name ?: it.name).lowercase() }
            "recent" -> titles.sortedByDescending { it.created_at }
            else -> titles.sortedByDescending { it.event_date } // date_desc default
        }

        // Load related data
        val titleIds = titles.mapNotNull { it.id }.toSet()
        val membersByTitle = titleIds.associateWith { FamilyMemberService.getMembersForTitle(it) }
        val tagsByTitle = titleIds.associateWith { TagService.getTagsForTitle(it) }
        val progressByTitle = PlaybackProgressService.getProgressByTitle()

        val videos = titles.map { title ->
            val progress = progressByTitle[title.id]
            val dur = progress?.duration_seconds
            val progressFraction: Double? = if (progress != null && dur != null && dur > 0) {
                progress.position_seconds / dur
            } else null

            val titleMembers = (membersByTitle[title.id] ?: emptyList()).map { m ->
                mapOf("id" to m.id, "name" to m.name)
            }
            val titleTags = (tagsByTitle[title.id] ?: emptyList()).map { t ->
                mapOf("id" to t.id, "name" to t.name, "bg_color" to t.bg_color, "text_color" to t.textColor())
            }

            mapOf(
                "title_id" to title.id,
                "title_name" to title.name,
                "poster_url" to title.posterUrl(PosterSize.FULL),
                "event_date" to title.event_date?.toString(),
                "description" to title.description,
                "playable" to (title.id in playableTitleIds),
                "progress_fraction" to progressFraction,
                "family_members" to titleMembers,
                "tags" to titleTags
            )
        }

        // All family members for filter chips
        val allMembers = FamilyMemberService.getAllMembers().map { m ->
            mapOf("id" to m.id, "name" to m.name)
        }

        return jsonResponse(gson.toJson(mapOf(
            "videos" to videos,
            "total" to videos.size,
            "family_members" to allMembers
        )))
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
