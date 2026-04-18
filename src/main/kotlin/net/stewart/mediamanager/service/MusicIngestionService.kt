package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.ExpansionStatus
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackArtist
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.Locale

/**
 * Creates / reuses Title, Artist, Track, and MediaItem rows for a single
 * MusicBrainz release lookup. Mirrors [BookIngestionService] for books.
 *
 * Dedup rules:
 *   - A Title is reused when `musicbrainz_release_group_id + media_type=ALBUM`
 *     already exists. Different pressings of the same album collapse to one
 *     Title with multiple MediaItem rows via `media_item_title`.
 *   - An Artist is reused when `musicbrainz_artist_id` matches.
 *   - Tracks are always created fresh under the Title when the Title is new;
 *     when reusing an existing Title we trust its existing tracks (a re-scan
 *     of a different pressing shouldn't rewrite the tracklist).
 */
object MusicIngestionService {

    private val log = LoggerFactory.getLogger(MusicIngestionService::class.java)

    data class IngestResult(
        val mediaItem: MediaItem,
        val title: Title,
        /** True if the Title already existed and a new pressing was linked to it. */
        val titleReused: Boolean
    )

    /**
     * Physical-CD scan path. [upc] is the EAN-13 the user scanned (usually
     * null for this user's bulk-import case where jewel cases are gone — in
     * which case MB's `barcode` field from [lookup] fills in when available).
     */
    fun ingest(
        upc: String?,
        mediaFormat: MediaFormat,
        lookup: MusicBrainzReleaseLookup,
        clock: Clock = SystemClock
    ): IngestResult {
        val now = clock.now()
        val effectiveUpc = upc ?: lookup.barcode

        val albumArtists = lookup.albumArtistCredits.map { upsertArtist(it, now) }
        val (title, reused) = upsertTitle(lookup, now)
        if (!reused) {
            albumArtists.forEachIndexed { index, artist ->
                TitleArtist(
                    title_id = title.id!!,
                    artist_id = artist.id!!,
                    artist_order = index
                ).save()
            }
            createTracks(title.id!!, albumArtists, lookup, now)
            SearchIndexService.onTitleChanged(title.id!!)
        }

        val mediaItem = MediaItem(
            upc = effectiveUpc,
            media_format = mediaFormat.name,
            title_count = 1,
            expansion_status = ExpansionStatus.SINGLE.name,
            upc_lookup_json = lookup.rawJson,
            product_name = lookup.title,
            created_at = now,
            updated_at = now
        )
        mediaItem.save()

        MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!
        ).save()

        if (effectiveUpc != null) {
            OwnershipPhotoService.resolveOrphans(effectiveUpc, mediaItem.id!!)
        }

        // Fulfill any active album wishes for this release-group (across users).
        WishListService.fulfillAlbumWishes(lookup.musicBrainzReleaseGroupId)

        log.info(
            "Album ingested: upc={} mediaFormat={} releaseGroup={} title='{}' titleReused={}",
            effectiveUpc, mediaFormat.name, lookup.musicBrainzReleaseGroupId, lookup.title, reused
        )
        return IngestResult(mediaItem, title, reused)
    }

    private fun upsertArtist(credit: MusicBrainzArtistCredit, now: LocalDateTime): Artist {
        val existing = Artist.findAll().firstOrNull {
            it.musicbrainz_artist_id == credit.musicBrainzArtistId
        }
        if (existing != null) return existing

        val new = Artist(
            name = credit.name,
            sort_name = credit.sortName.ifBlank { credit.name },
            artist_type = mapArtistType(credit.type),
            musicbrainz_artist_id = credit.musicBrainzArtistId,
            created_at = now,
            updated_at = now
        )
        new.save()
        return new
    }

    /**
     * Reuses an existing ALBUM title with the same
     * `musicbrainz_release_group_id`; otherwise creates one.
     */
    private fun upsertTitle(
        lookup: MusicBrainzReleaseLookup,
        now: LocalDateTime
    ): Pair<Title, Boolean> {
        val existing = Title.findAll().firstOrNull {
            it.media_type == MediaType.ALBUM.name &&
                it.musicbrainz_release_group_id == lookup.musicBrainzReleaseGroupId
        }
        if (existing != null) return existing to true

        val title = Title(
            name = lookup.title,
            media_type = MediaType.ALBUM.name,
            raw_upc_title = lookup.title,
            release_year = lookup.releaseYear,
            // Poster path routes through the cover-art proxy, keyed by the
            // specific-pressing MBID (not release-group; art is per-pressing).
            poster_path = "caa/${lookup.musicBrainzReleaseId}",
            sort_name = titleSortName(lookup.title),
            // Music isn't TMDB-enriched; the MB record is the ground truth.
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            musicbrainz_release_group_id = lookup.musicBrainzReleaseGroupId,
            musicbrainz_release_id = lookup.musicBrainzReleaseId,
            track_count = lookup.tracks.size.takeIf { it > 0 },
            total_duration_seconds = lookup.totalDurationSeconds,
            label = lookup.label,
            created_at = now,
            updated_at = now
        )
        title.save()
        return title to false
    }

    private fun createTracks(
        titleId: Long,
        albumArtists: List<Artist>,
        lookup: MusicBrainzReleaseLookup,
        now: LocalDateTime
    ) {
        for (mbTrack in lookup.tracks) {
            val track = Track(
                title_id = titleId,
                track_number = mbTrack.trackNumber,
                disc_number = mbTrack.discNumber,
                name = mbTrack.name,
                duration_seconds = mbTrack.durationSeconds,
                musicbrainz_recording_id = mbTrack.musicBrainzRecordingId,
                created_at = now,
                updated_at = now
            )
            track.save()

            // Per-track artist credits only populate when different from
            // the album-level credit (MusicBrainzService already stripped
            // redundant duplicates on the inbound side).
            if (mbTrack.trackArtistCredits.isNotEmpty()) {
                mbTrack.trackArtistCredits.forEachIndexed { index, credit ->
                    val artist = upsertArtist(credit, now)
                    TrackArtist(
                        track_id = track.id!!,
                        artist_id = artist.id!!,
                        artist_order = index
                    ).save()
                }
            }
        }
    }

    private fun mapArtistType(mb: String?): String = when (mb?.lowercase(Locale.ROOT)) {
        "person" -> ArtistType.PERSON.name
        "group" -> ArtistType.GROUP.name
        "orchestra" -> ArtistType.ORCHESTRA.name
        "choir" -> ArtistType.CHOIR.name
        "other" -> ArtistType.OTHER.name
        null -> ArtistType.GROUP.name      // Very old MB records don't set type
        else -> ArtistType.OTHER.name       // Forward-compat for MB adding new values
    }

    /** Strip leading "The", "A", "An" for alphabetical sort, like book titles. */
    private fun titleSortName(name: String): String {
        val trimmed = name.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("the ") -> trimmed.substring(4)
            lower.startsWith("an ") -> trimmed.substring(3)
            lower.startsWith("a ") -> trimmed.substring(2)
            else -> trimmed
        }
    }
}
