package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistMembership
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.CreditRole
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.RecordingCredit
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Background daemon that populates [ArtistMembership] and [RecordingCredit]
 * from MusicBrainz. Runs on a 1-hour cycle, strictly rate-limited to ≤1
 * req/sec across both passes (MB's public terms). See docs/MUSIC.md M6.
 *
 * Two passes per cycle:
 *
 *  1. **Artist memberships.** For any Artist with `musicbrainz_artist_id`
 *     set and no [ArtistMembership] rows linking it to / from, fetch
 *     `/ws/2/artist/{mbid}?inc=artist-rels` and insert the membership
 *     rows. Members referenced by the relations get upserted if we don't
 *     already have them.
 *  2. **Recording credits.** For any ALBUM Title with
 *     `musicbrainz_release_id` set and no [RecordingCredit] rows against
 *     any of its tracks, fetch
 *     `/ws/2/release/{mbid}?inc=recordings+artist-credits+artist-rels+recording-rels`
 *     and insert one RecordingCredit per typed performer / composer /
 *     producer / engineer / mixer relation. Performers carry their
 *     instrument; the others don't.
 *
 * Coverage is patchy — MB documents personnel thoroughly for mainstream
 * rock / jazz and unevenly elsewhere. The UI gracefully hides the
 * Personnel / Band Members sections when nothing was found.
 */
class PersonnelEnrichmentAgent(
    private val clock: Clock = SystemClock,
    private val musicBrainz: MusicBrainzService = MusicBrainzHttpService()
) {
    private val log = LoggerFactory.getLogger(PersonnelEnrichmentAgent::class.java)
    internal val running = AtomicBoolean(false)
    private var thread: Thread? = null

    companion object {
        // See ArtistEnrichmentAgent for the active/idle rationale. MB's
        // 1 req/sec rate limit is honored inside each batch via MB_GAP.
        private val ACTIVE_CYCLE = 15.seconds
        private val IDLE_CYCLE = 5.minutes
        private val STARTUP_DELAY = 75.seconds
        private val MB_GAP = 1100.milliseconds
        private const val ARTIST_BATCH = 10
        private const val ALBUM_BATCH = 5
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            log.info("PersonnelEnrichmentAgent started (active {}s / idle {}m)",
                ACTIVE_CYCLE.inWholeSeconds, IDLE_CYCLE.inWholeMinutes)
            try { clock.sleep(STARTUP_DELAY) } catch (_: InterruptedException) { return@Thread }
            while (running.get()) {
                val processed = try {
                    enrichOnce()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.error("PersonnelEnrichmentAgent error: {}", e.message, e)
                    0
                }
                val wait = if (processed > 0) ACTIVE_CYCLE else IDLE_CYCLE
                try { clock.sleep(wait) } catch (_: InterruptedException) { break }
            }
            log.info("PersonnelEnrichmentAgent stopped")
        }, "personnel-enrichment").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /** Returns the total number of artists + albums processed this cycle. */
    internal fun enrichOnce(): Int {
        val a = enrichArtistMemberships()
        val b = enrichAlbumPersonnel()
        return a + b
    }

    // -------------------------------------------------------------------
    // Pass 1: artist memberships
    // -------------------------------------------------------------------

    private fun enrichArtistMemberships(): Int {
        val allArtists = Artist.findAll()
        val allMemberships = ArtistMembership.findAll()
        val touchedIds = allMemberships
            .flatMap { listOf(it.group_artist_id, it.member_artist_id) }
            .toSet()

        val candidates = allArtists
            .asSequence()
            .filter { !it.musicbrainz_artist_id.isNullOrBlank() }
            // Skip artists we've already fetched memberships for (rows
            // touching them on either side).
            .filter { it.id !in touchedIds }
            .take(ARTIST_BATCH)
            .toList()

        if (candidates.isEmpty()) {
            log.debug("No artists need membership enrichment")
            return 0
        }
        log.info("Enriching memberships for {} artist(s)", candidates.size)

        val byMbid = allArtists
            .asSequence()
            .filter { !it.musicbrainz_artist_id.isNullOrBlank() }
            .associateBy { it.musicbrainz_artist_id!! }
            .toMutableMap()

        for ((i, artist) in candidates.withIndex()) {
            if (!running.get()) break
            if (i > 0) {
                try { clock.sleep(MB_GAP) } catch (_: InterruptedException) { break }
            }
            try {
                val mbid = artist.musicbrainz_artist_id!!
                val memberships = musicBrainz.listArtistMemberships(mbid)
                if (memberships.isEmpty()) {
                    // Write a sentinel "no members found" by creating nothing —
                    // but we need to avoid re-fetching this artist next cycle.
                    // Insert a self-referencing sentinel? No — that'd show up
                    // in the UI. Instead we tolerate some churn: artists with
                    // no MB data get re-fetched every cycle, but that's OK at
                    // 1 rps for a 10-artist batch.
                    log.debug("No MB memberships for artist id={} '{}'", artist.id, artist.name)
                    continue
                }
                upsertMemberships(artist, memberships, byMbid)
            } catch (e: Exception) {
                log.warn("Membership enrichment failed for artist id={} mbid={}: {}",
                    artist.id, artist.musicbrainz_artist_id, e.message)
            }
        }
        return candidates.size
    }

    private fun upsertMemberships(
        selfArtist: Artist,
        memberships: List<MusicBrainzMembership>,
        byMbid: MutableMap<String, Artist>
    ) {
        val now = LocalDateTime.now()
        val existing = ArtistMembership.findAll()
        for (m in memberships) {
            val group = byMbid[m.groupMbid] ?: upsertSkeletonArtist(
                m.groupMbid,
                m.groupName,
                m.groupName,
                ArtistType.GROUP,
                now
            ).also { byMbid[m.groupMbid] = it }
            val member = byMbid[m.memberMbid] ?: upsertSkeletonArtist(
                m.memberMbid,
                m.memberName,
                m.memberSortName,
                ArtistType.PERSON,
                now
            ).also { byMbid[m.memberMbid] = it }

            val begin = parseMbDate(m.beginDate)
            val alreadyThere = existing.any {
                it.group_artist_id == group.id &&
                    it.member_artist_id == member.id &&
                    it.begin_date == begin
            }
            if (alreadyThere) continue

            ArtistMembership(
                group_artist_id = group.id!!,
                member_artist_id = member.id!!,
                begin_date = begin,
                end_date = parseMbDate(m.endDate),
                primary_instruments = m.instruments.joinToString(", ").ifBlank { null },
                notes = null,
                created_at = now,
                updated_at = now
            ).save()
        }
        log.info("Enriched {} membership(s) touching artist id={} '{}'",
            memberships.size, selfArtist.id, selfArtist.name)
    }

    // -------------------------------------------------------------------
    // Pass 2: per-track recording credits on owned albums
    // -------------------------------------------------------------------

    private fun enrichAlbumPersonnel(): Int {
        val allTitles = Title.findAll()
        val allTracks = Track.findAll()
        val allCredits = RecordingCredit.findAll()
        val creditedTrackIds = allCredits.map { it.track_id }.toSet()

        // An album is "personnel-enriched" if at least one of its tracks
        // has any recording_credit row. This is a coarse heuristic — some
        // MB releases only document a few track credits. Good enough: we
        // enrich once per album and stop hammering MB.
        val titleCandidates = allTitles
            .asSequence()
            .filter { it.media_type == MediaType.ALBUM.name }
            .filter { !it.musicbrainz_release_id.isNullOrBlank() }
            .filter { title ->
                val trackIds = allTracks.filter { it.title_id == title.id }.mapNotNull { it.id }
                trackIds.isNotEmpty() && trackIds.none { it in creditedTrackIds }
            }
            .take(ALBUM_BATCH)
            .toList()

        if (titleCandidates.isEmpty()) {
            log.debug("No albums need personnel enrichment")
            return 0
        }
        log.info("Enriching personnel for {} album(s)", titleCandidates.size)

        val byMbid = Artist.findAll()
            .asSequence()
            .filter { !it.musicbrainz_artist_id.isNullOrBlank() }
            .associateBy { it.musicbrainz_artist_id!! }
            .toMutableMap()

        for ((i, title) in titleCandidates.withIndex()) {
            if (!running.get()) break
            if (i > 0) {
                try { clock.sleep(MB_GAP) } catch (_: InterruptedException) { break }
            }
            try {
                enrichAlbumPersonnel(title, allTracks.filter { it.title_id == title.id }, byMbid)
            } catch (e: Exception) {
                log.warn("Personnel enrichment failed for album id={} '{}': {}",
                    title.id, title.name, e.message)
            }
        }
        return titleCandidates.size
    }

    private fun enrichAlbumPersonnel(
        title: Title,
        tracks: List<Track>,
        byMbid: MutableMap<String, Artist>
    ) {
        val releaseMbid = title.musicbrainz_release_id ?: return
        val credits = musicBrainz.listReleaseRecordingCredits(releaseMbid)
        if (credits.isEmpty()) {
            log.debug("No MB recording credits for album id={} release={}", title.id, releaseMbid)
            return
        }

        val trackByRecordingId = tracks
            .asSequence()
            .filter { it.musicbrainz_recording_id != null }
            .associateBy { it.musicbrainz_recording_id!! }

        val now = LocalDateTime.now()
        var inserted = 0
        // Per-track credit_order counter so the UI can render in a stable order.
        val orderByTrack = mutableMapOf<Long, Int>()
        for (credit in credits) {
            val track = trackByRecordingId[credit.musicBrainzRecordingId] ?: continue
            val artist = byMbid[credit.creditArtist.musicBrainzArtistId]
                ?: upsertSkeletonArtist(
                    credit.creditArtist.musicBrainzArtistId,
                    credit.creditArtist.name,
                    credit.creditArtist.sortName,
                    mapArtistType(credit.creditArtist.type),
                    now
                ).also { byMbid[credit.creditArtist.musicBrainzArtistId] = it }

            val order = orderByTrack.getOrDefault(track.id!!, 0)
            orderByTrack[track.id!!] = order + 1

            val role = runCatching { CreditRole.valueOf(credit.role) }.getOrNull()
                ?: CreditRole.OTHER
            RecordingCredit(
                track_id = track.id!!,
                artist_id = artist.id!!,
                role = role.name,
                instrument = credit.instrument,
                credit_order = order,
                created_at = now
            ).save()
            inserted++
        }
        log.info("Inserted {} recording credit(s) on album id={} '{}'",
            inserted, title.id, title.name)
    }

    // -------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------

    private fun upsertSkeletonArtist(
        mbid: String,
        name: String,
        sortName: String,
        type: ArtistType,
        now: LocalDateTime
    ): Artist {
        val existing = Artist.findAll().firstOrNull { it.musicbrainz_artist_id == mbid }
        if (existing != null) return existing
        val new = Artist(
            name = name,
            sort_name = sortName.ifBlank { name },
            artist_type = type.name,
            musicbrainz_artist_id = mbid,
            created_at = now,
            updated_at = now
        )
        new.save()
        return new
    }

    private fun mapArtistType(mb: String?): ArtistType = when (mb?.lowercase()) {
        "person" -> ArtistType.PERSON
        "group" -> ArtistType.GROUP
        "orchestra" -> ArtistType.ORCHESTRA
        "choir" -> ArtistType.CHOIR
        "other" -> ArtistType.OTHER
        null -> ArtistType.GROUP
        else -> ArtistType.OTHER
    }

    /** Parse MB date: "1971" / "1971-04" / "1971-04-19" -> LocalDate. */
    private fun parseMbDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return try {
            when {
                raw.matches(Regex("^\\d{4}$")) -> LocalDate.of(raw.toInt(), 1, 1)
                raw.matches(Regex("^\\d{4}-\\d{2}$")) -> LocalDate.parse("$raw-01")
                raw.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) -> LocalDate.parse(raw)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
