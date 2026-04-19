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

    /**
     * Manual ingest for the unmatched-audio admin path: derive a Title
     * + Tracks + (Various-Artists) primary credit purely from the file
     * tags the scanner already captured. The admin doesn't have to type
     * anything — name, year, track list, durations, recording MBIDs all
     * come from what's in the row.
     *
     * Used when MusicBrainz genuinely has nothing useful for the album.
     * No release-group MBID is stored, so the record reads as
     * "user-created" forever and won't be merged with future MB-sourced
     * pressings.
     */
    fun ingestManualFromRows(
        rows: List<net.stewart.mediamanager.entity.UnmatchedAudio>,
        clock: Clock = SystemClock
    ): Title {
        require(rows.isNotEmpty()) { "ingestManualFromRows needs at least one row" }
        val now = clock.now()

        val albumName = dominant(rows) { it.parsed_album }?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("rows have no parsed_album to ingest as a title")

        // Album-level artist: explicit album_artist tag wins; otherwise
        // a single track_artist across every file is treated as the
        // artist (single-artist album with no album_artist tag); else
        // it's a Various Artists compilation.
        val explicitAlbumArtist = dominant(rows) { it.parsed_album_artist }?.takeIf { it.isNotBlank() }
        val uniqueTrackArtists = rows.mapNotNull { it.parsed_track_artist?.takeIf { s -> s.isNotBlank() } }
            .distinct()
        val albumArtistName = explicitAlbumArtist
            ?: uniqueTrackArtists.singleOrNull()
            ?: "Various Artists"

        val albumArtist = upsertNamedArtist(albumArtistName, now)

        val title = Title(
            name = albumName,
            media_type = MediaType.ALBUM.name,
            raw_upc_title = albumName,
            sort_name = titleSortName(albumName),
            // Manually-created albums have no MB grounding; we mark them
            // ENRICHED so downstream views don't try to re-enrich them.
            enrichment_status = EnrichmentStatus.ENRICHED.name,
            track_count = rows.size,
            total_duration_seconds = rows.mapNotNull { it.parsed_duration_seconds }.sum().takeIf { it > 0 },
            label = dominant(rows) { it.parsed_label },
            created_at = now,
            updated_at = now
        )
        title.save()

        TitleArtist(
            title_id = title.id!!,
            artist_id = albumArtist.id!!,
            artist_order = 0
        ).save()

        // One Track per file, ordered by (disc, track). track_number gaps
        // are preserved — if the admin staged disc 1 tracks 5-12 only, the
        // resulting Title shows them at 5-12 not renumbered.
        val sorted = rows.sortedWith(
            compareBy({ it.parsed_disc_number ?: 1 }, { it.parsed_track_number ?: 0 })
        )
        for (row in sorted) {
            val track = Track(
                title_id = title.id!!,
                track_number = row.parsed_track_number ?: 0,
                disc_number = row.parsed_disc_number ?: 1,
                name = row.parsed_title?.takeIf { it.isNotBlank() } ?: row.file_name,
                duration_seconds = row.parsed_duration_seconds,
                musicbrainz_recording_id = row.parsed_mb_recording_id?.takeIf { it.isNotBlank() },
                created_at = now,
                updated_at = now
            )
            track.save()

            // Per-track credit when the track artist diverges from the
            // album credit — typical compilation shape ("Various Artists"
            // album, real artist per track).
            val trackArtistName = row.parsed_track_artist?.takeIf {
                it.isNotBlank() && !it.equals(albumArtistName, ignoreCase = true)
            }
            if (trackArtistName != null) {
                val trackArtist = upsertNamedArtist(trackArtistName, now)
                TrackArtist(
                    track_id = track.id!!,
                    artist_id = trackArtist.id!!,
                    artist_order = 0
                ).save()
            }
        }

        SearchIndexService.onTitleChanged(title.id!!)
        log.info("Manual album ingested: title='{}' artist='{}' tracks={}",
            albumName, albumArtistName, rows.size)
        return title
    }

    /** Reuse-or-create an Artist row keyed on case-insensitive name match. */
    private fun upsertNamedArtist(name: String, now: LocalDateTime): Artist {
        val existing = Artist.findAll().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing
        val artist = Artist(
            name = name,
            sort_name = titleSortName(name),
            artist_type = ArtistType.OTHER.name,
            created_at = now,
            updated_at = now
        )
        artist.save()
        return artist
    }

    private fun <T> dominant(
        rows: List<net.stewart.mediamanager.entity.UnmatchedAudio>,
        picker: (net.stewart.mediamanager.entity.UnmatchedAudio) -> T?
    ): T? = rows.mapNotNull(picker).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

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
