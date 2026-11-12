package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*

/**
 * Finds titles in our catalog that are similar to a given title.
 * Combines three signals: TMDB recommendations, shared genres, and shared cast.
 */
object SimilarTitlesService {

    /**
     * Returns up to [limit] similar titles from our catalog for the given title.
     * Scores each candidate by: TMDB recommendation match (+10), shared genres (+2 each),
     * shared top-5 cast members (+3 each). Excludes hidden titles for the given user.
     */
    fun getSimilarTitlesForUser(title: Title, userId: Long?, limit: Int = 12, user: AppUser? = null): List<Title> {
        val titleId = title.id ?: return emptyList()
        val tmdbKey = title.tmdbKey()

        val resolvedUser = user ?: userId?.let { AppUser.findById(it) }
        val hiddenIds = if (userId != null) {
            UserTitleFlag.findAll()
                .filter { it.user_id == userId && it.flag == UserFlagType.HIDDEN.name }
                .map { it.title_id }
                .toSet()
        } else emptySet()

        val allTitles = Title.findAll().filter {
            it.id != titleId && !it.hidden && it.id !in hiddenIds &&
                it.enrichment_status == EnrichmentStatus.ENRICHED.name
        }
        if (allTitles.isEmpty()) return emptyList()

        val titleById = allTitles.associateBy { it.id }
        val scores = mutableMapOf<Long, Int>()

        // Signal 1: TMDB recommendations (strongest signal)
        if (tmdbKey != null) {
            val tmdbService = TmdbService()
            val recs = tmdbService.fetchRecommendations(tmdbKey)
            for (rec in recs) {
                val match = allTitles.firstOrNull {
                    it.tmdb_id == rec.tmdbId && it.media_type == rec.mediaType
                }
                if (match != null) {
                    scores[match.id!!] = (scores[match.id] ?: 0) + 10
                }
            }
        }

        // Signal 2: Shared genres (+2 per shared genre)
        val myGenreIds = TitleGenre.findAll().filter { it.title_id == titleId }.map { it.genre_id }.toSet()
        if (myGenreIds.isNotEmpty()) {
            val allTitleGenres = TitleGenre.findAll()
            val genresByTitle = allTitleGenres.groupBy { it.title_id }
            for ((candidateId, tgs) in genresByTitle) {
                if (candidateId == titleId || candidateId !in titleById) continue
                val shared = tgs.count { it.genre_id in myGenreIds }
                if (shared > 0) {
                    scores[candidateId] = (scores[candidateId] ?: 0) + (shared * 2)
                }
            }
        }

        // Signal 3: Shared top-5 cast (+3 per shared cast member)
        val myCast = CastMember.findAll()
            .filter { it.title_id == titleId }
            .sortedBy { it.cast_order }
            .take(5)
            .map { it.tmdb_person_id }
            .toSet()
        if (myCast.isNotEmpty()) {
            val allCast = CastMember.findAll()
            val castByTitle = allCast.groupBy { it.title_id }
            for ((candidateId, members) in castByTitle) {
                if (candidateId == titleId || candidateId !in titleById) continue
                val shared = members.count { it.tmdb_person_id in myCast }
                if (shared > 0) {
                    scores[candidateId] = (scores[candidateId] ?: 0) + (shared * 3)
                }
            }
        }

        // Rating enforcement
        val ratingFilter: (Title) -> Boolean = if (resolvedUser != null) {
            { t -> resolvedUser.canSeeRating(t.content_rating) }
        } else {
            { true }
        }

        return scores.entries
            .filter { titleById[it.key] != null && ratingFilter(titleById[it.key]!!) }
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { titleById[it.key] }
    }
}
