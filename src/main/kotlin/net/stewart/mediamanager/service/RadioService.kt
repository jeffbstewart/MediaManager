package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackArtist
import org.slf4j.LoggerFactory

/**
 * Seed-based queue generator that powers **Start Radio** (M7). See
 * docs/MUSIC.md §M7.
 *
 * Pipeline for each batch:
 *
 *  1. Start from the seed artist(s).
 *  2. Fetch Last.fm similar-artists via [SimilarArtistService] (reads
 *     cached JSON when fresh, lazy-hydrates otherwise).
 *  3. Intersect with **owned** artists — those with at least one
 *     playable track (`track.file_path != null`). Unowned suggestions
 *     are dropped; we can only play what's ripped.
 *  4. For each owned similar-artist, pick up to [TRACKS_PER_ARTIST]
 *     candidate tracks, preferring earlier release-year as a "canonical
 *     cut" proxy (Last.fm per-track popularity would be nicer but needs
 *     a second API endpoint; parked until the feature proves itself).
 *  5. Round-robin interleave across artists so the queue doesn't
 *     clump, then tail-pad with deeper cuts from the seed artist.
 *  6. Subtract anything in [history] and anything already queued
 *     inside the batch.
 *
 * Fallback cascade kicks in when similar ∩ owned yields fewer than
 * [MIN_SIMILAR_OWNED] hits: same-genre owned → same-era owned → any
 * owned that isn't in the history. The queue always produces
 * something, even for small libraries.
 */
object RadioService {

    private val log = LoggerFactory.getLogger(RadioService::class.java)

    /** Per-artist track cap inside a single batch. */
    private const val TRACKS_PER_ARTIST = 3
    /** Triggers the genre/era/random fallback below this count of owned similar artists. */
    private const val MIN_SIMILAR_OWNED = 3
    /** Cap on how many similar-artists we'll poll from the cache per batch. */
    private const val MAX_SIMILAR_CONSIDERED = 20
    /** Skip-within-30 s weighting: this many skips in a session pushes an artist to the back. */
    private const val EARLY_SKIP_THRESHOLD = 2
    /** Release-year tolerance for the era-based fallback tier. */
    private const val ERA_WINDOW_YEARS = 5
    /** Default batch size — matches the front-end's 5-remaining refill threshold. */
    const val DEFAULT_BATCH_SIZE = 15

    data class TrackRef(
        val trackId: Long,
        val trackName: String,
        val albumTitleId: Long,
        val albumName: String,
        val artistName: String?,
        val artistMbid: String?,
        val discNumber: Int,
        val trackNumber: Int
    )

    /**
     * Serializable seed state — stored in-memory by [RadioSeedStore]
     * keyed by `radio_seed_id` and replayed on each `/radio/next` call.
     */
    data class RadioSeed(
        val seedType: String,         // "album" | "track"
        val seedId: Long,
        val seedName: String,         // album title or track name; for the UI chip
        val seedArtistName: String?,  // primary album/track artist; nice-to-have for the chip
        val seedArtistMbids: List<String>
    )

    data class RadioBatch(val seed: RadioSeed, val tracks: List<TrackRef>)

    /** Client report — one entry per recently-played track. */
    data class HistoryEntry(val trackId: Long, val skippedEarly: Boolean)

    fun startFromAlbum(titleId: Long): RadioBatch? {
        val album = Title.findById(titleId) ?: return null
        if (album.media_type != MediaType.ALBUM.name) return null
        val seedArtists = albumSeedArtists(album)
        val seed = RadioSeed(
            seedType = "album",
            seedId = titleId,
            seedName = album.name,
            seedArtistName = seedArtists.firstOrNull()?.name,
            seedArtistMbids = seedArtists.mapNotNull { it.musicbrainz_artist_id }
        )
        return nextBatch(seed, history = emptyList())
    }

    fun startFromTrack(trackId: Long): RadioBatch? {
        val track = Track.findById(trackId) ?: return null
        val album = Title.findById(track.title_id) ?: return null
        val trackArtistIds = TrackArtist.findAll()
            .filter { it.track_id == trackId }
            .sortedBy { it.artist_order }
            .map { it.artist_id }
        val seedArtistRows = if (trackArtistIds.isNotEmpty()) {
            Artist.findAll().filter { it.id in trackArtistIds.toSet() }
        } else {
            albumSeedArtists(album)
        }
        val seed = RadioSeed(
            seedType = "track",
            seedId = trackId,
            seedName = track.name,
            seedArtistName = seedArtistRows.firstOrNull()?.name,
            seedArtistMbids = seedArtistRows.mapNotNull { it.musicbrainz_artist_id }
        )
        return nextBatch(seed, history = emptyList())
    }

    fun nextBatch(
        seed: RadioSeed,
        history: List<HistoryEntry>,
        batchSize: Int = DEFAULT_BATCH_SIZE
    ): RadioBatch {
        val historyTrackIds = history.mapTo(HashSet()) { it.trackId }
        val skippedArtistMbids = history.asSequence()
            .filter { it.skippedEarly }
            .mapNotNull { h -> Track.findById(h.trackId) }
            .flatMap { t -> primaryArtistMbidsOf(t) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= EARLY_SKIP_THRESHOLD }
            .keys

        val ownedByArtistMbid = buildOwnedIndex()
        val allOwnedMbids = ownedByArtistMbid.keys

        // Similar-artist weighted vote from every seed artist.
        val similarRank = gatherSimilar(seed.seedArtistMbids)

        val candidateMbids = similarRank.asSequence()
            .filter { it.mbid in allOwnedMbids }
            .filter { it.mbid !in seed.seedArtistMbids }
            .filter { it.mbid !in skippedArtistMbids }
            .take(MAX_SIMILAR_CONSIDERED)
            .map { it.mbid }
            .toList()

        val selected = mutableListOf<TrackRef>()
        val used = HashSet<Long>(historyTrackIds)

        // Phase 1: tracks from owned similar-artists, interleaved.
        val perArtistPicks: List<List<TrackRef>> = candidateMbids.map { mbid ->
            pickTracksForArtist(mbid, ownedByArtistMbid, used).take(TRACKS_PER_ARTIST).toList()
        }
        roundRobinInto(perArtistPicks, selected, used, batchSize)

        // Phase 2: if the owned overlap was thin, pull from genre / era / any-owned.
        // Skip weighting applies to every phase — an artist the user keeps
        // skipping shouldn't come back through the fallback route.
        if (candidateMbids.size < MIN_SIMILAR_OWNED && selected.size < batchSize) {
            val seedAlbum = Title.findById(seed.seedId).takeIf { seed.seedType == "album" }
                ?: seed.seedArtistMbids.firstOrNull()?.let { findFirstOwnedAlbumForArtist(it) }
            if (seedAlbum != null) {
                fillFromGenre(seedAlbum, ownedByArtistMbid, skippedArtistMbids, used, selected, batchSize)
                fillFromEra(seedAlbum.release_year, ownedByArtistMbid, skippedArtistMbids, used, selected, batchSize)
            }
            fillFromAny(ownedByArtistMbid, skippedArtistMbids, used, selected, batchSize)
        }

        // Phase 3: tail-pad with the seed artist's own catalogue (deeper cuts).
        if (selected.size < batchSize) {
            for (mbid in seed.seedArtistMbids) {
                val padding = pickTracksForArtist(mbid, ownedByArtistMbid, used).toList()
                for (t in padding) {
                    if (selected.size >= batchSize) break
                    selected += t
                    used += t.trackId
                }
            }
        }

        if (selected.isEmpty()) {
            log.info("Radio: no playable tracks for seed '{}' (seedMbids={})",
                seed.seedName, seed.seedArtistMbids)
        }
        return RadioBatch(seed = seed, tracks = selected)
    }

    // -------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------

    /** Ranked similar-artist entry with an aggregated match score from all seed artists. */
    private data class SimilarRank(val mbid: String, val score: Double)

    /** Iterator of Track-refs for [mbid], cheapest-first (canonical cuts). */
    private fun pickTracksForArtist(
        mbid: String,
        ownedByArtistMbid: Map<String, List<OwnedAlbum>>,
        exclude: Set<Long>
    ): Sequence<TrackRef> {
        val albums = ownedByArtistMbid[mbid] ?: return emptySequence()
        // Canonical-cut proxy: ascending release_year then disc/track order.
        return albums
            .sortedWith(compareBy(nullsLast()) { it.title.release_year })
            .asSequence()
            .flatMap { album ->
                album.tracks
                    .asSequence()
                    .filter { it.trackId !in exclude }
                    .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
            }
    }

    private fun gatherSimilar(seedMbids: List<String>): List<SimilarRank> {
        if (seedMbids.isEmpty()) return emptyList()
        val seedArtistRows = Artist.findAll()
            .filter { it.musicbrainz_artist_id in seedMbids.toSet() }
        val scores = HashMap<String, Double>()
        for (artist in seedArtistRows) {
            val similar = try {
                SimilarArtistService.getSimilar(artist)
            } catch (e: Exception) {
                log.warn("Similar-artist fetch failed for {}: {}", artist.musicbrainz_artist_id, e.message)
                emptyList()
            }
            for (s in similar) {
                val mbid = s.musicBrainzArtistId ?: continue
                scores.merge(mbid, s.match) { old, add -> old + add }
            }
        }
        return scores.entries
            .map { SimilarRank(it.key, it.value) }
            .sortedByDescending { it.score }
    }

    /** Owned-index bucket per artist MBID, with playable tracks pre-grouped by album. */
    private data class OwnedAlbum(val title: Title, val tracks: List<TrackRef>)

    private fun buildOwnedIndex(): Map<String, List<OwnedAlbum>> {
        val albums = Title.findAll().filter { it.media_type == MediaType.ALBUM.name }
        val albumsById = albums.associateBy { it.id }
        val allTracks = Track.findAll().filter { !it.file_path.isNullOrBlank() }
        val tracksByAlbum = allTracks.groupBy { it.title_id }

        // Resolve primary album-artist for each album from title_artist.
        val primaryArtistByAlbum = TitleArtist.findAll()
            .filter { it.artist_order == 0 }
            .associate { it.title_id to it.artist_id }
        val artistsById = Artist.findAll().associateBy { it.id }

        val out = HashMap<String, MutableList<OwnedAlbum>>()
        for (album in albums) {
            val artistId = primaryArtistByAlbum[album.id] ?: continue
            val artist = artistsById[artistId] ?: continue
            val mbid = artist.musicbrainz_artist_id ?: continue
            val tracks = tracksByAlbum[album.id].orEmpty().map { t ->
                TrackRef(
                    trackId = t.id ?: return@map null,
                    trackName = t.name,
                    albumTitleId = album.id!!,
                    albumName = album.name,
                    artistName = artist.name,
                    artistMbid = mbid,
                    discNumber = t.disc_number,
                    trackNumber = t.track_number
                )
            }.filterNotNull()
            if (tracks.isEmpty()) continue
            out.getOrPut(mbid) { mutableListOf() } += OwnedAlbum(album, tracks)
        }
        return out
    }

    private fun albumSeedArtists(album: Title): List<Artist> {
        val artistIds = TitleArtist.findAll()
            .filter { it.title_id == album.id }
            .sortedBy { it.artist_order }
            .map { it.artist_id }
        if (artistIds.isEmpty()) return emptyList()
        val artistsById = Artist.findAll().associateBy { it.id }
        return artistIds.mapNotNull { artistsById[it] }
    }

    private fun primaryArtistMbidsOf(track: Track): List<String> {
        // Track-level credits win when present; fall back to album-level.
        val trackArtistIds = TrackArtist.findAll()
            .filter { it.track_id == track.id }
            .map { it.artist_id }
        val ids = trackArtistIds.ifEmpty {
            TitleArtist.findAll()
                .filter { it.title_id == track.title_id }
                .map { it.artist_id }
        }
        if (ids.isEmpty()) return emptyList()
        val artistsById = Artist.findAll().associateBy { it.id }
        return ids.mapNotNull { artistsById[it]?.musicbrainz_artist_id }
    }

    private fun findFirstOwnedAlbumForArtist(mbid: String): Title? {
        val artist = Artist.findAll().firstOrNull { it.musicbrainz_artist_id == mbid } ?: return null
        val aid = artist.id ?: return null
        val titleIds = TitleArtist.findAll()
            .filter { it.artist_id == aid && it.artist_order == 0 }
            .map { it.title_id }
        return Title.findAll().firstOrNull { it.id in titleIds.toSet() }
    }

    private fun fillFromGenre(
        seedAlbum: Title,
        ownedByArtistMbid: Map<String, List<OwnedAlbum>>,
        skippedArtistMbids: Set<String>,
        used: HashSet<Long>,
        into: MutableList<TrackRef>,
        batchSize: Int
    ) {
        val seedGenreIds = TitleGenre.findAll()
            .filter { it.title_id == seedAlbum.id }
            .map { it.genre_id }
            .toSet()
        if (seedGenreIds.isEmpty()) return
        val matchingAlbumIds = TitleGenre.findAll()
            .filter { it.genre_id in seedGenreIds }
            .map { it.title_id }
            .toSet()
        val candidates = ownedByArtistMbid.entries.asSequence()
            .filter { it.key !in skippedArtistMbids }
            .flatMap { (_, albums) ->
                albums.asSequence()
                    .filter { it.title.id in matchingAlbumIds && it.title.id != seedAlbum.id }
                    .flatMap { it.tracks.asSequence() }
            }
            .filter { it.trackId !in used }
            .shuffled()
        for (t in candidates) {
            if (into.size >= batchSize) return
            into += t
            used += t.trackId
        }
    }

    private fun fillFromEra(
        seedYear: Int?,
        ownedByArtistMbid: Map<String, List<OwnedAlbum>>,
        skippedArtistMbids: Set<String>,
        used: HashSet<Long>,
        into: MutableList<TrackRef>,
        batchSize: Int
    ) {
        if (seedYear == null) return
        val range = (seedYear - ERA_WINDOW_YEARS)..(seedYear + ERA_WINDOW_YEARS)
        val candidates = ownedByArtistMbid.entries.asSequence()
            .filter { it.key !in skippedArtistMbids }
            .flatMap { (_, albums) ->
                albums.asSequence()
                    .filter { it.title.release_year in range }
                    .flatMap { it.tracks.asSequence() }
            }
            .filter { it.trackId !in used }
            .shuffled()
        for (t in candidates) {
            if (into.size >= batchSize) return
            into += t
            used += t.trackId
        }
    }

    private fun fillFromAny(
        ownedByArtistMbid: Map<String, List<OwnedAlbum>>,
        skippedArtistMbids: Set<String>,
        used: HashSet<Long>,
        into: MutableList<TrackRef>,
        batchSize: Int
    ) {
        if (into.size >= batchSize) return
        val pool = ownedByArtistMbid.entries.asSequence()
            .filter { it.key !in skippedArtistMbids }
            .flatMap { (_, albums) -> albums.asSequence().flatMap { it.tracks.asSequence() } }
            .filter { it.trackId !in used }
            .toMutableList()
        pool.shuffle()
        for (t in pool) {
            if (into.size >= batchSize) return
            into += t
            used += t.trackId
        }
    }

    private fun roundRobinInto(
        perArtistPicks: List<List<TrackRef>>,
        into: MutableList<TrackRef>,
        used: HashSet<Long>,
        batchSize: Int
    ) {
        if (perArtistPicks.isEmpty()) return
        val indices = IntArray(perArtistPicks.size)
        var exhausted = false
        while (!exhausted && into.size < batchSize) {
            exhausted = true
            for ((i, picks) in perArtistPicks.withIndex()) {
                val idx = indices[i]
                if (idx >= picks.size) continue
                val candidate = picks[idx]
                indices[i] = idx + 1
                if (candidate.trackId in used) continue
                into += candidate
                used += candidate.trackId
                exhausted = false
                if (into.size >= batchSize) return
            }
        }
    }
}
