package net.stewart.mediamanager.grpc

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.RecommendedArtist as RecommendedArtistEntity
import net.stewart.mediamanager.service.RecommendationAgent
import java.time.LocalDateTime

/**
 * M8 library recommendations over gRPC. Mirrors
 * [net.stewart.mediamanager.armeria.RecommendationHttpService] — keep them
 * in sync. Covers for suggested artists are addressable via ImageService
 * using IMAGE_TYPE_CAA_RELEASE_GROUP + representative_release_group_mbid.
 */
class RecommendationGrpcService : RecommendationServiceGrpcKt.RecommendationServiceCoroutineImplBase() {

    private val mapper = ObjectMapper()

    override suspend fun listRecommendedArtists(
        request: ListRecommendedArtistsRequest
    ): ListRecommendedArtistsResponse {
        val user = currentUser()
        val userId = user.id ?: throw StatusException(Status.UNAUTHENTICATED)
        val limit = if (request.limit <= 0) 30 else request.limit.coerceIn(1, 100)

        val rows = RecommendedArtistEntity.findAll()
            .filter { it.user_id == userId && it.dismissed_at == null }
            .sortedByDescending { it.score }
            .take(limit)

        val artistIdsByMbid = Artist.findAll()
            .mapNotNull { a -> a.musicbrainz_artist_id?.takeIf { it.isNotBlank() }?.let { it to a.id } }
            .toMap()

        val latestRefresh = rows.mapNotNull { it.created_at }.maxOrNull()

        return listRecommendedArtistsResponse {
            artists.addAll(rows.map { it.toProtoRecommendation(artistIdsByMbid) })
            latestRefresh?.let { lastRefreshed = it.toProtoTimestamp() }
        }
    }

    override suspend fun dismissRecommendation(request: DismissRecommendationRequest): Empty {
        val user = currentUser()
        val userId = user.id ?: throw StatusException(Status.UNAUTHENTICATED)
        val mbid = request.suggestedArtistMbid.takeIf { it.isNotBlank() }
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("suggested_artist_mbid required"))

        val row = RecommendedArtistEntity.findAll()
            .firstOrNull { it.user_id == userId && it.suggested_artist_mbid == mbid }
            ?: throw StatusException(Status.NOT_FOUND)

        row.dismissed_at = LocalDateTime.now()
        row.save()
        return Empty.getDefaultInstance()
    }

    override suspend fun refreshRecommendations(request: Empty): RefreshRecommendationsResponse {
        val user = currentUser()
        val userId = user.id ?: throw StatusException(Status.UNAUTHENTICATED)

        // Fire-and-forget — matches the HTTP service's behavior. Clients
        // re-list a few seconds later to see the new data.
        Thread({
            try { RecommendationAgent.refreshForUserIfAvailable(userId) }
            catch (_: Exception) { /* logged inside the agent */ }
        }, "recommendation-manual-refresh-grpc-$userId").apply {
            isDaemon = true
            start()
        }
        return refreshRecommendationsResponse { refreshStarted = true }
    }

    private fun RecommendedArtistEntity.toProtoRecommendation(
        artistIdsByMbid: Map<String, Long?>
    ): RecommendedArtist {
        val voterNames = decodeVoterNames(voters_json)
        return recommendedArtist {
            suggestedArtistMbid = suggested_artist_mbid
            artistIdsByMbid[suggested_artist_mbid]?.let { suggestedArtistId = it }
            suggestedArtistName = suggested_artist_name
            score = this@toProtoRecommendation.score
            if (voterNames.isNotEmpty()) voterArtistNames.addAll(voterNames)
            representative_release_group_id?.takeIf { it.isNotBlank() }
                ?.let { representativeReleaseGroupMbid = it }
            representative_release_title?.takeIf { it.isNotBlank() }
                ?.let { representativeReleaseTitle = it }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeVoterNames(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val raw = mapper.readValue(json, List::class.java) as? List<Map<String, Any?>> ?: return emptyList()
            raw.mapNotNull { it["name"] as? String }
        } catch (_: Exception) { emptyList() }
    }
}
