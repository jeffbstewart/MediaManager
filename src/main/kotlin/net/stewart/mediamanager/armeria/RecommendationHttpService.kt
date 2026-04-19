package net.stewart.mediamanager.armeria

import com.fasterxml.jackson.databind.ObjectMapper
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
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.RecommendedArtist
import net.stewart.mediamanager.service.RecommendationAgent
import java.time.LocalDateTime

/**
 * HTTP surface for M8 library recommendations.
 *
 *   GET  /api/v2/recommendations/artists       — top-N active (non-dismissed) suggestions for the current user
 *   POST /api/v2/recommendations/dismiss       — mark a suggestion dismissed so it survives recompute passes
 *   POST /api/v2/recommendations/refresh       — manual recompute for the current user (bypass the daily cadence)
 *
 * Results on the GET path include a cover-art URL synthesised from the
 * existing `/proxy/caa/release-group/{rgid}/front-250` proxy (added in
 * the discography fix earlier this cycle), so the UI card can render
 * without a second MB hop.
 */
@Blocking
class RecommendationHttpService {

    private val gson = Gson()
    private val mapper = ObjectMapper()

    @Get("/api/v2/recommendations/artists")
    fun list(
        ctx: ServiceRequestContext,
        @Param("limit") @Default("30") limit: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val userId = user.id ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val rows = RecommendedArtist.findAll()
            .filter { it.user_id == userId && it.dismissed_at == null }
            .sortedByDescending { it.score }
            .take(limit.coerceIn(1, 100))

        val artistIdsByMbid = Artist.findAll().associate {
            (it.musicbrainz_artist_id ?: "") to it.id
        }.filterKeys { it.isNotEmpty() }

        val results = rows.map { r ->
            val voters = decodeVoters(r.voters_json)
            mapOf(
                "suggested_artist_mbid" to r.suggested_artist_mbid,
                "suggested_artist_name" to r.suggested_artist_name,
                // We usually don't have the suggested artist in our local
                // Artist table (they're unowned); this is null in that case
                // and the UI falls back to the MBID for the key.
                "artist_id" to artistIdsByMbid[r.suggested_artist_mbid],
                "score" to r.score,
                "voters" to voters,
                "representative_release_group_id" to r.representative_release_group_id,
                "representative_release_title" to r.representative_release_title,
                "cover_url" to r.representative_release_group_id?.let {
                    "/proxy/caa/release-group/$it/front-250"
                }
            )
        }

        return jsonResponse(gson.toJson(mapOf("artists" to results)))
    }

    @Post("/api/v2/recommendations/dismiss")
    fun dismiss(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val userId = user.id ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val bodyStr = ctx.request().aggregate().join().contentUtf8()
        @Suppress("UNCHECKED_CAST")
        val body = gson.fromJson(bodyStr, Map::class.java) as? Map<String, Any?>
            ?: return badRequest("invalid body")
        val mbid = (body["suggested_artist_mbid"] as? String)?.takeIf { it.isNotBlank() }
            ?: return badRequest("suggested_artist_mbid required")

        val row = RecommendedArtist.findAll()
            .firstOrNull { it.user_id == userId && it.suggested_artist_mbid == mbid }
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        row.dismissed_at = LocalDateTime.now()
        row.save()
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    /**
     * Manual refresh. Fire-and-forget — kicks [RecommendationAgent.refreshForUser]
     * on a background thread so the HTTP call returns quickly. The UI
     * can poll / reload the list endpoint a few seconds later.
     */
    @Post("/api/v2/recommendations/refresh")
    fun refresh(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx) ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val userId = user.id ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        Thread({
            try { RecommendationAgent.refreshForUserIfAvailable(userId) }
            catch (_: Exception) { /* logged inside the agent */ }
        }, "recommendation-manual-refresh-$userId").apply {
            isDaemon = true
            start()
        }
        return jsonResponse(gson.toJson(mapOf("ok" to true, "note" to "refresh queued")))
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeVoters(json: String?): List<Map<String, Any?>> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            mapper.readValue(json, List::class.java) as List<Map<String, Any?>>
        } catch (_: Exception) { emptyList() }
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(message: String): HttpResponse =
        HttpResponse.builder()
            .status(HttpStatus.BAD_REQUEST)
            .content(MediaType.JSON_UTF_8, gson.toJson(mapOf("error" to message)))
            .build()
}
