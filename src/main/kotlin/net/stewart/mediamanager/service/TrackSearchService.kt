package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.Track

/**
 * Filtered-track search for the advanced-search surface. Backs both
 * HTTP (`GET /api/v2/search/tracks`) and gRPC (`SearchTracks`) so the
 * filter semantics stay identical across clients.
 *
 * All filters AND together; null / missing filters don't restrict.
 * Rating ceiling is enforced — a viewer can never widen their
 * visibility via search.
 */
object TrackSearchService {

    /** Wire-agnostic inputs. */
    data class Filters(
        val query: String? = null,
        val bpmMin: Int? = null,
        val bpmMax: Int? = null,
        val timeSignature: String? = null,
        val limit: Int = 200
    )

    /** Single result row — rich enough that the client doesn't need a follow-up fetch. */
    data class Hit(
        val trackId: Long,
        val titleId: Long,
        val name: String,
        val albumName: String,
        val artistName: String?,
        val bpm: Int?,
        val timeSignature: String?,
        val durationSeconds: Int?,
        val posterUrl: String?,
        val playable: Boolean
    )

    fun search(user: AppUser, filters: Filters): List<Hit> {
        // Reject an outright-no-filters request — with no BPM / time-sig
        // / query, we'd return an arbitrary 200 rows which isn't useful.
        val q = filters.query?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
        if (q == null && filters.bpmMin == null && filters.bpmMax == null &&
            filters.timeSignature == null) {
            return emptyList()
        }

        val titlesById = Title.findAll()
            .filter { it.media_type == MediaType.ALBUM.name }
            .filter { !it.hidden && user.canSeeRating(it.content_rating) }
            .associateBy { it.id }

        // Primary artist per album, same pattern as the browse grid.
        val primaryArtistByTitle: Map<Long, Artist> = run {
            val links = TitleArtist.findAll().filter { it.artist_order == 0 }
            val artistsById = Artist.findAll()
                .filter { a -> links.any { it.artist_id == a.id } }
                .associateBy { it.id }
            links.mapNotNull { link ->
                artistsById[link.artist_id]?.let { link.title_id to it }
            }.toMap()
        }

        val tracks = Track.findAll().asSequence()
            .filter { it.title_id in titlesById.keys }
            .filter { filters.bpmMin == null || (it.bpm != null && it.bpm!! >= filters.bpmMin) }
            .filter { filters.bpmMax == null || (it.bpm != null && it.bpm!! <= filters.bpmMax) }
            .filter { filters.timeSignature == null ||
                (it.time_signature != null && it.time_signature == filters.timeSignature) }
            .filter { track ->
                if (q == null) return@filter true
                val albumName = titlesById[track.title_id]?.name?.lowercase().orEmpty()
                val artistName = primaryArtistByTitle[track.title_id]?.name?.lowercase().orEmpty()
                q in track.name.lowercase() || q in albumName || q in artistName
            }
            .sortedByDescending { it.bpm ?: 0 }   // Useful ordering when filtering by BPM range.
            .take(filters.limit.coerceIn(1, 500))
            .toList()

        return tracks.map { track ->
            val title = titlesById[track.title_id]
            Hit(
                trackId = track.id!!,
                titleId = track.title_id,
                name = track.name,
                albumName = title?.name ?: "",
                artistName = primaryArtistByTitle[track.title_id]?.name,
                bpm = track.bpm,
                timeSignature = track.time_signature,
                durationSeconds = track.duration_seconds,
                posterUrl = title?.posterUrl(PosterSize.THUMBNAIL),
                playable = !track.file_path.isNullOrBlank()
            )
        }
    }
}
