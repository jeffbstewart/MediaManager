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
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TagService
import net.stewart.mediamanager.service.TranscoderAgent

/**
 * Unified search endpoint for the Angular web app. Searches across titles, actors,
 * collections, tags, live TV channels, and cameras. Designed to be adaptable to
 * gRPC for iOS at a later date.
 */
@Blocking
class SearchHttpService {

    private val gson = Gson()

    /**
     * Search across all entity types. Returns categorized results sorted by relevance.
     * Suitable for search-as-you-type (fast, in-memory index for titles).
     */
    @Get("/api/v2/search")
    fun search(
        ctx: ServiceRequestContext,
        @Param("q") query: String,
        @Param("limit") @Default("30") limit: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val q = query.trim().lowercase()
        if (q.length < 2) return jsonResponse(gson.toJson(mapOf("results" to emptyList<Any>(), "query" to query)))

        val results = mutableListOf<Map<String, Any?>>()
        val queryTokens = q.split("\\s+".toRegex())

        // --- 1. Titles (movies, TV, personal) via SearchIndexService ---
        val titleIds = SearchIndexService.search(q)
        if (titleIds != null) {
            val allTitles = Title.findAll().associateBy { it.id }
            val nasRoot = TranscoderAgent.getNasRoot()
            val allTranscodes = Transcode.findAll().filter { it.file_path != null }
            val playableTitleIds = allTranscodes
                .filter { tc ->
                    val fp = tc.file_path!!
                    if (TranscoderAgent.needsTranscoding(fp)) {
                        nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
                    } else true
                }
                .map { it.title_id }
                .toSet()

            for (titleId in titleIds) {
                val title = allTitles[titleId] ?: continue
                if (title.hidden) continue
                if (!user.canSeeRating(title.content_rating)) continue
                val type = when (title.media_type) {
                    MMMediaType.TV.name -> "tv"
                    MMMediaType.PERSONAL.name -> "personal"
                    else -> "movie"
                }
                results.add(mapOf(
                    "type" to type,
                    "title_id" to title.id,
                    "name" to title.name,
                    "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                    "year" to title.release_year,
                    "playable" to (title.id in playableTitleIds),
                    "score" to (title.popularity ?: 0.0)
                ))
            }
        }

        // --- 2. Actors (cast members by name) ---
        val seenActors = mutableSetOf<Int>()
        val castMembers = CastMember.findAll()
        for (cm in castMembers) {
            if (cm.tmdb_person_id in seenActors) continue
            val nameLC = cm.name.lowercase()
            if (queryTokens.all { nameLC.contains(it) }) {
                seenActors.add(cm.tmdb_person_id)
                results.add(mapOf(
                    "type" to "actor",
                    "person_id" to cm.tmdb_person_id,
                    "name" to cm.name,
                    // Gate on profile_path — see note in TitleDetailHttpService.
                    "headshot_url" to if (cm.profile_path != null) "/headshots/${cm.id}" else null,
                    "score" to (cm.popularity ?: 0.0)
                ))
            }
        }

        // --- 3. Collections ---
        val collections = TmdbCollection.findAll()
        for (col in collections) {
            val nameLC = col.name.lowercase()
            if (queryTokens.all { nameLC.contains(it) }) {
                results.add(mapOf(
                    "type" to "collection",
                    "collection_id" to col.id,
                    "name" to col.name,
                    "score" to 8000.0
                ))
            }
        }

        // --- 4. Tags ---
        val tags = TagService.getAllTags()
        val tagCounts = TagService.getTagTitleCounts()
        for (tag in tags) {
            val nameLC = tag.name.lowercase()
            if (queryTokens.all { nameLC.contains(it) }) {
                results.add(mapOf(
                    "type" to "tag",
                    "tag_id" to tag.id,
                    "name" to tag.name,
                    "bg_color" to tag.bg_color,
                    "text_color" to tag.textColor(),
                    "title_count" to (tagCounts[tag.id] ?: 0),
                    "score" to 6000.0
                ))
            }
        }

        // --- 5. Live TV channels (by call sign, guide name, or affiliation) ---
        val enabledTunerIds = LiveTvTuner.findAll().filter { it.enabled }.mapNotNull { it.id }.toSet()
        val channels = LiveTvChannel.findAll().filter { it.enabled && it.tuner_id in enabledTunerIds }
        for (ch in channels) {
            val searchable = "${ch.guide_name} ${ch.network_affiliation ?: ""} ${ch.guide_number}".lowercase()
            if (queryTokens.all { searchable.contains(it) }) {
                results.add(mapOf(
                    "type" to "channel",
                    "channel_id" to ch.id,
                    "name" to "${ch.guide_number} ${ch.guide_name}",
                    "affiliation" to ch.network_affiliation,
                    "score" to 4000.0
                ))
            }
        }

        // --- 6. Cameras ---
        val cameras = Camera.findAll().filter { it.enabled }
        for (cam in cameras) {
            val nameLC = cam.name.lowercase()
            if (queryTokens.all { nameLC.contains(it) }) {
                results.add(mapOf(
                    "type" to "camera",
                    "camera_id" to cam.id,
                    "name" to cam.name,
                    "score" to 3000.0
                ))
            }
        }

        // Sort by score descending, limit
        val sorted = results.sortedByDescending { (it["score"] as? Number)?.toDouble() ?: 0.0 }.take(limit)

        return jsonResponse(gson.toJson(mapOf("results" to sorted, "query" to query)))
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
