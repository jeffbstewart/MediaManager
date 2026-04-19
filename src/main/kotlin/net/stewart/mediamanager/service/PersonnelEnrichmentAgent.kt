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

        val queue = allArtists
            .asSequence()
            .filter { !it.musicbrainz_artist_id.isNullOrBlank() }
            .filter { it.id !in touchedIds }
            .toList()

        val now = clock.now()
        val eligible = queue.filter {
            EnrichmentBackoff.isEligibleForRetry(
                it.membership_last_attempt_at, it.membership_no_progress_streak, now
            )
        }
        val cooling = queue.size - eligible.size
        val candidates = eligible.take(ARTIST_BATCH)

        if (candidates.isEmpty()) {
            if (cooling > 0) {
                log.info("No artists eligible for membership enrichment; {} in cooldown", cooling)
            } else {
                log.debug("No artists need membership enrichment")
            }
            return 0
        }
        log.info("Enriching memberships for {} artist(s); queue={} eligible={} cooling={}",
            candidates.size, queue.size, eligible.size, cooling)

        val byMbid = allArtists
            .asSequence()
            .filter { !it.musicbrainz_artist_id.isNullOrBlank() }
            .associateBy { it.musicbrainz_artist_id!! }
            .toMutableMap()

        var filledMemberships = 0
        var emptyFromMb = 0

        for ((i, artist) in candidates.withIndex()) {
            if (!running.get()) break
            if (i > 0) {
                try { clock.sleep(MB_GAP) } catch (_: InterruptedException) { break }
            }
            var madeProgress = false
            try {
                val mbid = artist.musicbrainz_artist_id!!
                val memberships = musicBrainz.listArtistMemberships(mbid)
                if (memberships.isEmpty()) {
                    emptyFromMb++
                    val nextRetry = EnrichmentBackoff.cooldownFor(artist.membership_no_progress_streak + 1)
                    log.info("Artist {} '{}' (mbid={}): MB has 0 memberships; next try in {}",
                        artist.id, artist.name, mbid, nextRetry)
                } else {
                    upsertMemberships(artist, memberships, byMbid)
                    filledMemberships++
                    madeProgress = true
                }
            } catch (e: Exception) {
                log.warn("Membership enrichment failed for artist id={} mbid={}: {}",
                    artist.id, artist.musicbrainz_artist_id, e.message)
            }
            // Reload in case upsertMemberships mutated the row (it doesn't
            // today, but writing through the reloaded copy is the safe habit).
            val reloaded = Artist.findById(artist.id!!) ?: continue
            reloaded.membership_last_attempt_at = clock.now()
            reloaded.membership_no_progress_streak = EnrichmentBackoff.nextStreak(
                reloaded.membership_no_progress_streak, madeProgress
            )
            reloaded.save()
        }

        val queueAfter = Artist.findAll().count { a ->
            !a.musicbrainz_artist_id.isNullOrBlank() &&
                ArtistMembership.findAll().none {
                    it.group_artist_id == a.id || it.member_artist_id == a.id
                }
        }
        log.info("Membership batch done: filled={} empty-from-mb={} cooling={} queue={} (delta={})",
            filledMemberships, emptyFromMb, cooling, queueAfter, queueAfter - queue.size)
        return candidates.size
    }

    private fun upsertMemberships(
        selfArtist: Artist,
        memberships: List<MusicBrainzMembership>,
        byMbid: MutableMap<String, Artist>
    ) {
        val now = LocalDateTime.now()
        // Seed with already-persisted keys, then grow as we insert inside
        // this call. MB often returns multiple membership rows for the
        // same (group, member, begin_date) tuple when an artist played
        // several instruments on the same gig; our unique constraint
        // collapses instruments into a comma-joined string so only the
        // first of such a run should hit the DB. Prior to this change the
        // `existing` snapshot was only refreshed once per call, so a
        // second MB row for the same tuple within the same call would
        // slip through and trip the unique index.
        val seenKeys: MutableSet<Triple<Long, Long, LocalDate?>> =
            ArtistMembership.findAll()
                .mapTo(mutableSetOf()) { Triple(it.group_artist_id, it.member_artist_id, it.begin_date) }

        var inserted = 0
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
            val key = Triple(group.id!!, member.id!!, begin)
            if (!seenKeys.add(key)) continue  // already in DB or just inserted

            try {
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
                inserted++
            } catch (e: Exception) {
                // Belt-and-suspenders for the case where a concurrent
                // writer beat us to this key. Our set should have caught
                // it; this path is the defensive log-and-continue so a
                // unique-index violation can't crash the batch.
                val msg = e.message.orEmpty()
                if (msg.contains("Unique", ignoreCase = true) || msg.contains("duplicate", ignoreCase = true)) {
                    log.warn("Duplicate membership skipped for group={} member={} begin={}",
                        group.id, member.id, begin)
                } else {
                    throw e
                }
            }
        }
        log.info("Enriched {} membership(s) touching artist id={} '{}' (inserted {})",
            memberships.size, selfArtist.id, selfArtist.name, inserted)
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
        val queue = allTitles
            .asSequence()
            .filter { it.media_type == MediaType.ALBUM.name }
            .filter { !it.musicbrainz_release_id.isNullOrBlank() }
            .filter { title ->
                val trackIds = allTracks.filter { it.title_id == title.id }.mapNotNull { it.id }
                trackIds.isNotEmpty() && trackIds.none { it in creditedTrackIds }
            }
            .toList()

        val now = clock.now()
        val eligible = queue.filter {
            EnrichmentBackoff.isEligibleForRetry(
                it.personnel_last_attempt_at, it.personnel_no_progress_streak, now
            )
        }
        val cooling = queue.size - eligible.size
        val titleCandidates = eligible.take(ALBUM_BATCH)

        if (titleCandidates.isEmpty()) {
            if (cooling > 0) {
                log.info("No albums eligible for personnel enrichment; {} in cooldown", cooling)
            } else {
                log.debug("No albums need personnel enrichment")
            }
            return 0
        }
        log.info("Enriching personnel for {} album(s); queue={} eligible={} cooling={}",
            titleCandidates.size, queue.size, eligible.size, cooling)

        val byMbid = Artist.findAll()
            .asSequence()
            .filter { !it.musicbrainz_artist_id.isNullOrBlank() }
            .associateBy { it.musicbrainz_artist_id!! }
            .toMutableMap()

        var filled = 0
        var emptyFromMb = 0

        for ((i, title) in titleCandidates.withIndex()) {
            if (!running.get()) break
            if (i > 0) {
                try { clock.sleep(MB_GAP) } catch (_: InterruptedException) { break }
            }
            var inserted = 0
            try {
                inserted = enrichAlbumPersonnel(title, allTracks.filter { it.title_id == title.id }, byMbid)
                if (inserted > 0) filled++ else emptyFromMb++
            } catch (e: Exception) {
                log.warn("Personnel enrichment failed for album id={} '{}': {}",
                    title.id, title.name, e.message)
            }
            // Reload so we don't overwrite any updates enrichAlbumPersonnel
            // might have made to the title. Today it doesn't mutate title,
            // but writing through a reload stays correct if that changes.
            val reloaded = Title.findById(title.id!!) ?: continue
            reloaded.personnel_last_attempt_at = clock.now()
            reloaded.personnel_no_progress_streak = EnrichmentBackoff.nextStreak(
                reloaded.personnel_no_progress_streak, madeProgress = inserted > 0
            )
            reloaded.save()
        }
        log.info("Album personnel batch done: filled={} empty-from-mb={} cooling={} queue={}",
            filled, emptyFromMb, cooling, queue.size)
        return titleCandidates.size
    }

    /** Returns the count of recording-credit rows inserted. */
    private fun enrichAlbumPersonnel(
        title: Title,
        tracks: List<Track>,
        byMbid: MutableMap<String, Artist>
    ): Int {
        val releaseMbid = title.musicbrainz_release_id ?: return 0
        val credits = musicBrainz.listReleaseRecordingCredits(releaseMbid)
        if (credits.isEmpty()) {
            log.debug("No MB recording credits for album id={} release={}", title.id, releaseMbid)
            return 0
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
        return inserted
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
