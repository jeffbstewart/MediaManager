package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.RecommendedArtist
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.Track
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Pre-compute per-user **library recommendations** — artists similar
 * to what you already own, that you don't own yet, ranked by an
 * aggregated Last.fm match score. See docs/MUSIC.md §M8.
 *
 * Each recompute walks the user's owned artists, pulls their cached
 * similar-artist data from [SimilarArtistService], aggregates votes
 * (weighted by album-count so a voter the user listens to heavily
 * carries more influence), filters out artists they already own and
 * previously-dismissed suggestions, then resolves a representative
 * release-group via MusicBrainz so the UI card can show a cover.
 *
 * Runs inside [RecommendationAgent]'s daily pass or from the manual
 * refresh endpoint. The top N suggestions are upserted into
 * [RecommendedArtist]; suggestions that fell off and weren't
 * dismissed are removed.
 */
object RecommendationService {

    private val log = LoggerFactory.getLogger(RecommendationService::class.java)
    private val mapper = ObjectMapper()

    /** Upper bound on how many active suggestions we keep per user. */
    const val TOP_N = 30

    /** Cap on representative-release lookups per recompute pass (per user). Each is one MB request. */
    private const val MAX_REPRESENTATIVE_LOOKUPS = 30

    data class Voter(val mbid: String, val name: String, val albumCount: Int)

    data class Suggestion(
        val mbid: String,
        val name: String,
        val score: Double,
        val voters: List<Voter>
    )

    /** Full recompute for a single user. Returns the count of active suggestions after the pass. */
    fun recompute(
        userId: Long,
        musicBrainz: MusicBrainzService = MusicBrainzHttpService(),
        similarArtists: SimilarArtistService = SimilarArtistService,
    ): Int {
        val ownership = buildOwnership()
        val voterArtists = ownership.voterArtists
        if (voterArtists.isEmpty()) {
            log.info("RecommendationService: user {} has no owned artists; skipping", userId)
            return pruneOrphanedRecommendations(userId, activeMbids = emptySet())
        }

        val suggestions = aggregateSuggestions(voterArtists, ownership.ownedArtistMbids, similarArtists)
            .sortedByDescending { it.score }
            .take(TOP_N)

        val existing = RecommendedArtist.findAll().filter { it.user_id == userId }
        val existingByMbid = existing.associateBy { it.suggested_artist_mbid }
        val dismissedMbids = existing.filter { it.dismissed_at != null }.map { it.suggested_artist_mbid }.toSet()

        val active = suggestions.filter { it.mbid !in dismissedMbids }

        // Resolve representative release-group for artists that don't yet
        // have one cached on their row. Cap the MB request volume so a
        // single recompute doesn't stall behind rate-limit.
        var lookups = 0
        val representatives = HashMap<String, Pair<String, String>?>() // mbid -> (rgid, title)
        for (s in active) {
            val row = existingByMbid[s.mbid]
            if (row?.representative_release_group_id != null) continue
            if (lookups >= MAX_REPRESENTATIVE_LOOKUPS) break
            lookups++
            representatives[s.mbid] = pickRepresentativeReleaseGroup(s.mbid, musicBrainz)
        }

        val now = LocalDateTime.now()
        for (s in active) {
            val row = existingByMbid[s.mbid]
            val rep = representatives[s.mbid]
                ?: row?.representative_release_group_id?.let { it to (row.representative_release_title ?: "") }
            if (row == null) {
                RecommendedArtist(
                    user_id = userId,
                    suggested_artist_mbid = s.mbid,
                    suggested_artist_name = s.name,
                    score = s.score,
                    voters_json = encodeVoters(s.voters),
                    representative_release_group_id = rep?.first,
                    representative_release_title = rep?.second?.ifBlank { null },
                    created_at = now
                ).save()
            } else {
                row.suggested_artist_name = s.name
                row.score = s.score
                row.voters_json = encodeVoters(s.voters)
                if (row.representative_release_group_id == null && rep != null) {
                    row.representative_release_group_id = rep.first
                    row.representative_release_title = rep.second.ifBlank { null }
                }
                row.save()
            }
        }

        val activeMbids = active.mapTo(HashSet()) { it.mbid }
        pruneOrphanedRecommendations(userId, activeMbids)
        return activeMbids.size + dismissedMbids.size
    }

    /**
     * Remove rows that aren't in [activeMbids] *and* aren't dismissed.
     * Dismissals persist even after an artist falls off the top-N.
     */
    private fun pruneOrphanedRecommendations(userId: Long, activeMbids: Set<String>): Int {
        val all = RecommendedArtist.findAll().filter { it.user_id == userId }
        val doomed = all.filter { it.dismissed_at == null && it.suggested_artist_mbid !in activeMbids }
        for (row in doomed) {
            try { row.delete() } catch (e: Exception) {
                log.warn("Failed to delete stale recommendation id={} for user {}: {}", row.id, userId, e.message)
            }
        }
        return all.size - doomed.size
    }

    // -------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------

    private data class Ownership(
        /** Artists the user owns with album-count for vote weighting. */
        val voterArtists: List<Voter>,
        /** MBID set used to filter out already-owned suggestions. */
        val ownedArtistMbids: Set<String>
    )

    private fun buildOwnership(): Ownership {
        val albums = Title.findAll().filter { it.media_type == MediaType.ALBUM.name }
        val playableAlbumIds = Track.findAll()
            .filter { !it.file_path.isNullOrBlank() }
            .mapTo(HashSet()) { it.title_id }
        val playableAlbums = albums.filter { it.id in playableAlbumIds }

        val primaryArtistIdByAlbum = TitleArtist.findAll()
            .filter { it.artist_order == 0 }
            .associate { it.title_id to it.artist_id }
        val artistsById = Artist.findAll().associateBy { it.id }

        val albumCountByArtistId = HashMap<Long, Int>()
        for (album in playableAlbums) {
            val artistId = primaryArtistIdByAlbum[album.id] ?: continue
            albumCountByArtistId.merge(artistId, 1) { a, b -> a + b }
        }

        val voters = albumCountByArtistId.entries.mapNotNull { (artistId, count) ->
            val artist = artistsById[artistId] ?: return@mapNotNull null
            val mbid = artist.musicbrainz_artist_id ?: return@mapNotNull null
            Voter(mbid = mbid, name = artist.name, albumCount = count)
        }

        val ownedMbids = voters.mapTo(HashSet()) { it.mbid }
        return Ownership(voterArtists = voters, ownedArtistMbids = ownedMbids)
    }

    private fun aggregateSuggestions(
        voters: List<Voter>,
        ownedMbids: Set<String>,
        similarArtists: SimilarArtistService
    ): List<Suggestion> {
        data class Agg(
            val name: String,
            var score: Double,
            val voterMbids: MutableList<Voter>
        )
        val byMbid = HashMap<String, Agg>()

        for (voter in voters) {
            val artist = Artist.findAll().firstOrNull { it.musicbrainz_artist_id == voter.mbid } ?: continue
            val similar = try {
                similarArtists.getSimilar(artist)
            } catch (e: Exception) {
                log.warn("Similar fetch failed for voter {}: {}", voter.mbid, e.message)
                emptyList()
            }
            for (s in similar) {
                val mbid = s.musicBrainzArtistId ?: continue
                if (mbid in ownedMbids) continue  // don't recommend what they already own
                if (mbid == voter.mbid) continue  // never recommend an artist to themselves
                val weighted = s.match * voter.albumCount
                val agg = byMbid.getOrPut(mbid) { Agg(name = s.name, score = 0.0, voterMbids = mutableListOf()) }
                agg.score += weighted
                agg.voterMbids += voter
            }
        }

        return byMbid.entries.map { (mbid, agg) ->
            // Keep only the top 3 voters per suggestion for the explanation
            // line; the full list isn't useful to the UI.
            val topVoters = agg.voterMbids.sortedByDescending { it.albumCount }.take(3)
            Suggestion(mbid = mbid, name = agg.name, score = agg.score, voters = topVoters)
        }
    }

    private fun pickRepresentativeReleaseGroup(
        artistMbid: String,
        musicBrainz: MusicBrainzService
    ): Pair<String, String>? {
        val groups = try {
            musicBrainz.listArtistReleaseGroups(artistMbid, limit = 25)
        } catch (e: Exception) {
            log.warn("Representative RG lookup failed for {}: {}", artistMbid, e.message)
            return null
        }
        // Prefer non-compilation albums; take earliest release year as the
        // canonical-album proxy — same heuristic as RadioService.
        val candidate = groups
            .filter { !it.isCompilation }
            .sortedWith(compareBy(nullsLast()) { it.firstReleaseYear })
            .firstOrNull() ?: groups.firstOrNull() ?: return null
        return candidate.musicBrainzReleaseGroupId to candidate.title
    }

    private fun encodeVoters(voters: List<Voter>): String =
        mapper.writeValueAsString(voters.map {
            mapOf("mbid" to it.mbid, "name" to it.name, "album_count" to it.albumCount)
        })
}
