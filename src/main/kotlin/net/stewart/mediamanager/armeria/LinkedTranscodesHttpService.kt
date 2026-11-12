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
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.TranscoderAgent

/**
 * REST endpoint for the linked transcodes admin page.
 */
@Blocking
class LinkedTranscodesHttpService {

    private val gson = Gson()

    @Get("/api/v2/admin/linked-transcodes")
    fun list(
        ctx: ServiceRequestContext,
        @Param("search") @Default("") search: String,
        @Param("format") @Default("") format: String,
        @Param("type") @Default("") mediaType: String,
        @Param("sort") @Default("name") sort: String,
        @Param("page") @Default("0") page: Int,
        @Param("size") @Default("50") size: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val transcodes = Transcode.findAll().filter { it.file_path != null }
        val titles = Title.findAll().associateBy { it.id }
        val episodes = Episode.findAll().associateBy { it.id }
        val nasRoot = TranscoderAgent.getNasRoot()

        var rows = transcodes.mapNotNull { tc ->
            val title = titles[tc.title_id] ?: return@mapNotNull null
            val episode = tc.episode_id?.let { episodes[it] }
            val displayFile = if (episode != null) {
                "S${episode.season_number.toString().padStart(2, '0')}E${episode.episode_number.toString().padStart(2, '0')}" +
                    (episode.name?.let { " \u2014 $it" } ?: "")
            } else {
                tc.file_path?.substringAfterLast('\\')?.substringAfterLast('/')
            }

            val playable = if (TranscoderAgent.needsTranscoding(tc.file_path!!)) {
                nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, tc.file_path!!)
            } else true

            mapOf(
                "transcode_id" to tc.id,
                "title_id" to title.id,
                "title_name" to title.name,
                "media_type" to title.media_type,
                "format" to tc.media_format,
                "file_name" to displayFile,
                "file_path" to tc.file_path,
                "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                "playable" to playable,
                "retranscode_requested" to tc.retranscode_requested
            )
        }

        // Filter
        val q = search.trim().lowercase()
        if (q.isNotEmpty()) rows = rows.filter { (it["title_name"] as String).lowercase().contains(q) }
        if (format.isNotEmpty()) rows = rows.filter { it["format"] == format }
        if (mediaType.isNotEmpty()) rows = rows.filter { it["media_type"] == mediaType }

        // Sort
        rows = when (sort) {
            "format" -> rows.sortedBy { (it["format"] as? String) ?: "" }
            "type" -> rows.sortedBy { it["media_type"] as String }
            else -> rows.sortedBy { (it["title_name"] as String).lowercase() }
        }

        val total = rows.size
        val paged = rows.drop(page * size).take(size)

        return jsonResponse(gson.toJson(mapOf("rows" to paged, "total" to total)))
    }

    @Post("/api/v2/admin/linked-transcodes/{transcodeId}/retranscode")
    fun requestRetranscode(ctx: ServiceRequestContext, @Param("transcodeId") transcodeId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val tc = Transcode.findById(transcodeId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        tc.retranscode_requested = true
        tc.save()
        return jsonResponse("""{"ok":true}""")
    }

    @Delete("/api/v2/admin/linked-transcodes/{transcodeId}")
    fun unlink(ctx: ServiceRequestContext, @Param("transcodeId") transcodeId: Long): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        if (!user.isAdmin()) return HttpResponse.of(HttpStatus.FORBIDDEN)

        val tc = Transcode.findById(transcodeId) ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        val episodeId = tc.episode_id
        val filePath = tc.file_path
        tc.delete()

        if (episodeId != null && Transcode.findAll().none { it.episode_id == episodeId }) {
            Episode.findById(episodeId)?.delete()
        }

        if (filePath != null) {
            val df = DiscoveredFile.findAll().firstOrNull { it.file_path == filePath }
            if (df != null && df.match_status == DiscoveredFileStatus.LINKED.name) {
                df.match_status = DiscoveredFileStatus.UNMATCHED.name
                df.matched_title_id = null
                df.matched_episode_id = null
                df.match_method = null
                df.save()
            }
        }

        return jsonResponse("""{"ok":true}""")
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
