package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.LeaseStatus
import net.stewart.mediamanager.entity.LeaseType
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.TranscodeLease
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [TranscodeLeaseService] — the lease lifecycle (claim →
 * progress → complete / fail), heartbeats, queries, expire-stale, and
 * the failure-clear admin paths. The full `claimWork` orchestration
 * (with cache preference, bundle limits, and poison-pill skipping) is
 * out of scope here — its priority sort is covered separately by
 * `TranscodeLeaseSortTest` against pure-function fixtures.
 */
class TranscodeLeaseServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:tlstest;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    @Before
    fun reset() {
        TranscodeLease.deleteAll()
        Transcode.deleteAll()
        Title.deleteAll()
        AppConfig.deleteAll()
    }

    private fun seedTitle(name: String = "Movie"): Title =
        Title(name = name, media_type = MediaType.MOVIE.name,
            sort_name = name.lowercase()).apply { save() }

    private fun seedTranscode(titleId: Long, filePath: String? = "/nas/movies/m.mkv"): Transcode =
        Transcode(title_id = titleId, file_path = filePath).apply { save() }

    private fun seedLease(
        transcodeId: Long,
        buddyName: String = "local",
        status: LeaseStatus = LeaseStatus.CLAIMED,
        leaseType: LeaseType = LeaseType.TRANSCODE,
        expiresAt: LocalDateTime? = LocalDateTime.now().plusMinutes(10),
        completedAt: LocalDateTime? = null,
        progressPercent: Int = 0,
    ): TranscodeLease = TranscodeLease(
        transcode_id = transcodeId,
        buddy_name = buddyName,
        relative_path = "movies/m.mkv",
        claimed_at = LocalDateTime.now(),
        expires_at = expiresAt,
        status = status.name,
        lease_type = leaseType.name,
        completed_at = completedAt,
        progress_percent = progressPercent,
    ).apply { save() }

    // ---------------------- reportProgress ----------------------

    @Test
    fun `reportProgress flips CLAIMED to IN_PROGRESS and renews expiry on forward progress`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val lease = seedLease(tc.id!!, expiresAt = LocalDateTime.now().plusSeconds(30))
        val originalExpiry = lease.expires_at!!

        val updated = TranscodeLeaseService.reportProgress(lease.id!!, percent = 25, encoder = "x264")
        assertNotNull(updated)
        assertEquals(LeaseStatus.IN_PROGRESS.name, updated.status)
        assertEquals(25, updated.progress_percent)
        assertEquals("x264", updated.encoder)
        assertTrue(updated.expires_at!!.isAfter(originalExpiry),
            "forward progress renews the expiry past the original")
        assertNotNull(updated.last_progress_at)
    }

    @Test
    fun `reportProgress without forward movement keeps the prior expiry`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        // H2 TIMESTAMP truncates to microseconds, so reload after save to
        // get the canonical-precision expiry rather than the in-memory
        // nanosecond value we constructed with.
        val seeded = seedLease(tc.id!!, progressPercent = 50,
            expiresAt = LocalDateTime.now().plusMinutes(1))
        val originalExpiry = TranscodeLease.findById(seeded.id!!)!!.expires_at!!

        val updated = TranscodeLeaseService.reportProgress(seeded.id!!, percent = 50, encoder = null)
        assertNotNull(updated)
        // Same percent → no forward progress → expiry unchanged.
        assertEquals(originalExpiry, updated.expires_at)
    }

    @Test
    fun `reportProgress returns null for unknown lease id`() {
        assertNull(TranscodeLeaseService.reportProgress(999_999, 10, null))
    }

    @Test
    fun `reportProgress refuses to mutate a COMPLETED lease`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val lease = seedLease(tc.id!!, status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now())
        assertNull(TranscodeLeaseService.reportProgress(lease.id!!, 50, null),
            "completed leases are terminal — progress reports get ignored")
    }

    // ---------------------- reportComplete ----------------------

    @Test
    fun `reportComplete flips status, sets percent=100, and stamps completed_at`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val lease = seedLease(tc.id!!)

        val completed = TranscodeLeaseService.reportComplete(
            lease.id!!, encoder = "x265", outputSizeBytes = 1_234_567)
        assertNotNull(completed)
        assertEquals(LeaseStatus.COMPLETED.name, completed.status)
        assertEquals(100, completed.progress_percent)
        assertEquals("x265", completed.encoder)
        assertEquals(1_234_567, completed.file_size_bytes)
        assertNotNull(completed.completed_at)
    }

    @Test
    fun `reportComplete refuses to mutate a FAILED lease`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val lease = seedLease(tc.id!!, status = LeaseStatus.FAILED,
            completedAt = LocalDateTime.now())
        assertNull(TranscodeLeaseService.reportComplete(lease.id!!, "x264"),
            "FAILED is terminal; can't be promoted back to COMPLETED")
    }

    @Test
    fun `reportComplete ignores zero or negative outputSizeBytes`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val lease = seedLease(tc.id!!)

        TranscodeLeaseService.reportComplete(lease.id!!, "x264", outputSizeBytes = 0)
        // file_size_bytes left at the seeded null since 0 isn't a valid file size.
        assertNull(TranscodeLease.findById(lease.id!!)!!.file_size_bytes)
    }

    // ---------------------- reportFailure ----------------------

    @Test
    fun `reportFailure stamps the error message and flips status`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val lease = seedLease(tc.id!!)

        val failed = TranscodeLeaseService.reportFailure(lease.id!!,
            errorMessage = "FFmpeg exit code 137 (OOM)")
        assertNotNull(failed)
        assertEquals(LeaseStatus.FAILED.name, failed.status)
        assertEquals("FFmpeg exit code 137 (OOM)", failed.error_message)
        assertNotNull(failed.completed_at)
    }

    @Test
    fun `reportFailure truncates an oversize error message to 2048 chars`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val lease = seedLease(tc.id!!)

        val huge = "x".repeat(5000)
        val failed = TranscodeLeaseService.reportFailure(lease.id!!, huge)
        assertNotNull(failed)
        assertEquals(2048, failed.error_message!!.length,
            "error_message column has a 2048-char ceiling")
    }

    // ---------------------- heartbeat ----------------------

    @Test
    fun `heartbeat renews expiry without changing progress`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val lease = seedLease(tc.id!!, progressPercent = 33,
            expiresAt = LocalDateTime.now().plusSeconds(10))
        val originalExpiry = lease.expires_at!!

        val refreshed = TranscodeLeaseService.heartbeat(lease.id!!, durationMinutes = 30)
        assertNotNull(refreshed)
        assertEquals(33, refreshed.progress_percent, "heartbeat doesn't touch progress")
        assertTrue(refreshed.expires_at!!.isAfter(originalExpiry))
        assertNotNull(refreshed.last_progress_at)
    }

    @Test
    fun `heartbeat returns null on unknown id and on terminal-state leases`() {
        assertNull(TranscodeLeaseService.heartbeat(999_999))

        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val terminal = seedLease(tc.id!!, status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now())
        assertNull(TranscodeLeaseService.heartbeat(terminal.id!!))
    }

    @Test
    fun `heartbeatMultiple counts only the lease ids that flipped`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val active1 = seedLease(tc.id!!)
        val active2 = seedLease(tc.id!!, leaseType = LeaseType.THUMBNAILS)
        val terminal = seedLease(tc.id!!, status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now(), leaseType = LeaseType.SUBTITLES)

        val renewed = TranscodeLeaseService.heartbeatMultiple(
            listOf(active1.id!!, active2.id!!, terminal.id!!, 999_999),
            durationMinutes = 5)
        assertEquals(2, renewed,
            "two active leases get heartbeat — terminal + missing IDs are skipped")
    }

    // ---------------------- queries ----------------------

    @Test
    fun `getActiveLeasedTranscodeIds returns only CLAIMED + IN_PROGRESS, optionally per type`() {
        val title = seedTitle()
        val a = seedTranscode(title.id!!, "/nas/a.mkv")
        val b = seedTranscode(title.id!!, "/nas/b.mkv")
        val c = seedTranscode(title.id!!, "/nas/c.mkv")
        seedLease(a.id!!, status = LeaseStatus.CLAIMED)
        seedLease(b.id!!, status = LeaseStatus.IN_PROGRESS,
            leaseType = LeaseType.THUMBNAILS)
        seedLease(c.id!!, status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now())

        val all = TranscodeLeaseService.getActiveLeasedTranscodeIds()
        assertEquals(setOf(a.id!!, b.id!!), all)

        val thumbnailsOnly = TranscodeLeaseService.getActiveLeasedTranscodeIds(LeaseType.THUMBNAILS)
        assertEquals(setOf(b.id!!), thumbnailsOnly)
    }

    @Test
    fun `getPoisonPillTranscodeIds flags transcodes with at least the configured fail count`() {
        val title = seedTitle()
        val poisoned = seedTranscode(title.id!!, "/nas/poison.mkv")
        val healthy = seedTranscode(title.id!!, "/nas/healthy.mkv")
        // 3 failed attempts on the poisoned transcode (reaches DEFAULT_MAX_FAILURES).
        repeat(3) {
            seedLease(poisoned.id!!, status = LeaseStatus.FAILED,
                completedAt = LocalDateTime.now())
        }
        // Only one failed attempt on the other — still recoverable.
        seedLease(healthy.id!!, status = LeaseStatus.FAILED,
            completedAt = LocalDateTime.now())

        val poison = TranscodeLeaseService.getPoisonPillTranscodeIds()
        assertEquals(setOf(poisoned.id!!), poison)
    }

    @Test
    fun `getPoisonPillTranscodeIds counts EXPIRED alongside FAILED`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        seedLease(tc.id!!, status = LeaseStatus.FAILED, completedAt = LocalDateTime.now())
        seedLease(tc.id!!, status = LeaseStatus.EXPIRED, completedAt = LocalDateTime.now())
        // 2 fails — below the default threshold of 3.
        assertTrue(TranscodeLeaseService.getPoisonPillTranscodeIds().isEmpty())
        // Bump threshold so 2 is enough.
        assertEquals(setOf(tc.id!!),
            TranscodeLeaseService.getPoisonPillTranscodeIds(maxFailures = 2))
    }

    @Test
    fun `getActiveLeases returns CLAIMED + IN_PROGRESS sorted newest-claimed first`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val older = seedLease(tc.id!!).apply {
            claimed_at = LocalDateTime.now().minusMinutes(10); save()
        }
        val newer = seedLease(tc.id!!).apply {
            claimed_at = LocalDateTime.now(); save()
        }
        seedLease(tc.id!!, status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now())  // filtered out

        val active = TranscodeLeaseService.getActiveLeases()
        assertEquals(listOf(newer.id!!, older.id!!), active.map { it.id!! })
    }

    @Test
    fun `getRecentLeases caps the list and orders by completed_at desc`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val now = LocalDateTime.now()
        repeat(5) { i ->
            seedLease(tc.id!!, status = LeaseStatus.COMPLETED,
                completedAt = now.minusMinutes(i.toLong()))
        }
        // Active leases stay out.
        seedLease(tc.id!!)

        val recent = TranscodeLeaseService.getRecentLeases(limit = 3)
        assertEquals(3, recent.size)
        // Sorted descending → first is the newest (offset 0).
        assertTrue(recent[0].completed_at!!.isAfter(recent[1].completed_at))
        assertTrue(recent[1].completed_at!!.isAfter(recent[2].completed_at))
    }

    // ---------------------- expireStaleLeases ----------------------

    @Test
    fun `expireStaleLeases flips active leases past their expires_at to EXPIRED`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val stale = seedLease(tc.id!!, expiresAt = LocalDateTime.now().minusMinutes(1))
        val fresh = seedLease(tc.id!!, expiresAt = LocalDateTime.now().plusMinutes(10))
        val terminal = seedLease(tc.id!!, status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now(),
            expiresAt = LocalDateTime.now().minusMinutes(1))

        TranscodeLeaseService.expireStaleLeases()

        assertEquals(LeaseStatus.EXPIRED.name,
            TranscodeLease.findById(stale.id!!)!!.status,
            "active+expired → EXPIRED")
        assertEquals(LeaseStatus.CLAIMED.name,
            TranscodeLease.findById(fresh.id!!)!!.status,
            "active+not-yet-expired stays CLAIMED")
        assertEquals(LeaseStatus.COMPLETED.name,
            TranscodeLease.findById(terminal.id!!)!!.status,
            "terminal-state leases are immune even with stale expires_at")
    }

    // ---------------------- clearAllFailures / clearFailures ----------------------

    @Test
    fun `clearAllFailures wipes every FAILED and EXPIRED lease across all transcodes`() {
        val title = seedTitle()
        val a = seedTranscode(title.id!!, "/nas/a.mkv")
        val b = seedTranscode(title.id!!, "/nas/b.mkv")
        seedLease(a.id!!, status = LeaseStatus.FAILED, completedAt = LocalDateTime.now())
        seedLease(a.id!!, status = LeaseStatus.EXPIRED, completedAt = LocalDateTime.now())
        seedLease(b.id!!, status = LeaseStatus.COMPLETED, completedAt = LocalDateTime.now())
        seedLease(b.id!!) // active

        val cleared = TranscodeLeaseService.clearAllFailures()
        assertEquals(2, cleared, "FAILED + EXPIRED removed; COMPLETED + active stay")
        assertEquals(2, TranscodeLease.findAll().size)
    }

    @Test
    fun `clearFailures scoped to a single transcode_id leaves siblings alone`() {
        val title = seedTitle()
        val target = seedTranscode(title.id!!, "/nas/target.mkv")
        val sibling = seedTranscode(title.id!!, "/nas/sibling.mkv")
        seedLease(target.id!!, status = LeaseStatus.FAILED, completedAt = LocalDateTime.now())
        seedLease(target.id!!, status = LeaseStatus.EXPIRED, completedAt = LocalDateTime.now())
        seedLease(sibling.id!!, status = LeaseStatus.FAILED, completedAt = LocalDateTime.now())

        val cleared = TranscodeLeaseService.clearFailures(target.id!!)
        assertEquals(2, cleared)
        assertEquals(1, TranscodeLease.findAll().size,
            "sibling's failed lease survives the scoped clear")
    }

    // ---------------------- isForMobileEnabled ----------------------

    @Test
    fun `isForMobileEnabled defaults to true when AppConfig key is missing`() {
        // No row → service returns the default.
        assertTrue(TranscodeLeaseService.isForMobileEnabled())
    }

    @Test
    fun `isForMobileEnabled honours the AppConfig override`() {
        AppConfig(config_key = "for_mobile_enabled", config_val = "false").save()
        assertEquals(false, TranscodeLeaseService.isForMobileEnabled())

        AppConfig.findAll().single { it.config_key == "for_mobile_enabled" }.apply {
            config_val = "true"; save()
        }
        assertEquals(true, TranscodeLeaseService.isForMobileEnabled())
    }

    // ---------------------- releaseLeases ----------------------

    @Test
    fun `releaseLeases marks every active lease for a buddy as EXPIRED and counts them`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        seedLease(tc.id!!, buddyName = "buddy-a")
        seedLease(tc.id!!, buddyName = "buddy-a", leaseType = LeaseType.THUMBNAILS)
        seedLease(tc.id!!, buddyName = "buddy-b")  // different buddy
        seedLease(tc.id!!, buddyName = "buddy-a",
            status = LeaseStatus.COMPLETED, completedAt = LocalDateTime.now())  // terminal

        val released = TranscodeLeaseService.releaseLeases("buddy-a")
        assertEquals(2, released)
        // Buddy-a's two active leases are now EXPIRED.
        val byBuddy = TranscodeLease.findAll().groupBy { it.buddy_name }
        val buddyA = byBuddy["buddy-a"]!!
        assertEquals(2, buddyA.count { it.status == LeaseStatus.EXPIRED.name })
        // Buddy-a's leases now carry the released-on-restart marker so
        // operators can distinguish from organic expiry.
        assertTrue(buddyA.filter { it.status == LeaseStatus.EXPIRED.name }
            .all { it.error_message == "Released on buddy restart" })
        // Other buddies untouched.
        assertEquals(LeaseStatus.CLAIMED.name,
            byBuddy["buddy-b"]!!.single().status)
    }

    @Test
    fun `releaseLeases is a no-op when buddy has no active leases`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        // Only terminal leases for the buddy → nothing to release.
        seedLease(tc.id!!, buddyName = "ghost",
            status = LeaseStatus.COMPLETED, completedAt = LocalDateTime.now())
        assertEquals(0, TranscodeLeaseService.releaseLeases("ghost"))
        assertEquals(LeaseStatus.COMPLETED.name,
            TranscodeLease.findAll().single().status,
            "completed leases stay completed")
    }

    // ---------------------- getStatusSummary ----------------------

    @Test
    fun `getStatusSummary counts active, completedToday, and poison pills`() {
        val title = seedTitle()
        val a = seedTranscode(title.id!!, "/nas/a.mkv")
        val b = seedTranscode(title.id!!, "/nas/b.mkv")
        // Two active leases.
        seedLease(a.id!!)
        seedLease(b.id!!, leaseType = LeaseType.THUMBNAILS)
        // One completed today.
        seedLease(a.id!!, status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now())
        // One completed yesterday — outside today's count.
        seedLease(a.id!!, status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now().minusDays(1).withHour(12))
        // 3 failed → counts as a poison pill on transcode b.
        repeat(3) {
            seedLease(b.id!!, status = LeaseStatus.FAILED,
                completedAt = LocalDateTime.now())
        }

        val summary = TranscodeLeaseService.getStatusSummary()
        assertEquals(2, summary.activeLeases)
        assertEquals(1, summary.completedToday)
        assertEquals(1, summary.poisonPills)
    }

    // ---------------------- getThumbnailStats / getSubtitleStats / getChapterStats ----------------------

    @Test
    fun `getThumbnailStats counts only THUMBNAILS COMPLETED, splitting today vs total`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        // 2 completed today + 1 completed yesterday + 1 active + 1 of a different type.
        repeat(2) {
            seedLease(tc.id!!, leaseType = LeaseType.THUMBNAILS,
                status = LeaseStatus.COMPLETED,
                completedAt = LocalDateTime.now().minusMinutes(it.toLong()))
        }
        seedLease(tc.id!!, leaseType = LeaseType.THUMBNAILS,
            status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now().minusDays(1).withHour(12))
        seedLease(tc.id!!, leaseType = LeaseType.THUMBNAILS)  // active
        seedLease(tc.id!!, leaseType = LeaseType.TRANSCODE,
            status = LeaseStatus.COMPLETED, completedAt = LocalDateTime.now())

        val (total, today) = TranscodeLeaseService.getThumbnailStats()
        assertEquals(3, total, "all completed THUMBNAILS leases (across days)")
        assertEquals(2, today, "only today's completions")
    }

    @Test
    fun `getSubtitleStats and getChapterStats follow the same shape as thumbnails`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        seedLease(tc.id!!, leaseType = LeaseType.SUBTITLES,
            status = LeaseStatus.COMPLETED, completedAt = LocalDateTime.now())
        seedLease(tc.id!!, leaseType = LeaseType.CHAPTERS,
            status = LeaseStatus.COMPLETED,
            completedAt = LocalDateTime.now().minusDays(2))

        val subs = TranscodeLeaseService.getSubtitleStats()
        assertEquals(1 to 1, subs)

        val chapters = TranscodeLeaseService.getChapterStats()
        assertEquals(1 to 0,
            chapters,
            "old completion shows up in total but not in today's count")
    }

    // ---------------------- getChapterExtractedTranscodeIds ----------------------

    @Test
    fun `getChapterExtractedTranscodeIds unions Chapter rows and completed CHAPTERS leases`() {
        val title = seedTitle()
        val a = seedTranscode(title.id!!, "/nas/a.mkv")
        val b = seedTranscode(title.id!!, "/nas/b.mkv")
        // Transcode a has chapters in the DB but no completed lease — covered.
        net.stewart.mediamanager.entity.Chapter(
            transcode_id = a.id!!, chapter_number = 1,
            start_seconds = 0.0, end_seconds = 60.0,
            title = "Opening").create()
        // Transcode b has a completed CHAPTERS lease but no Chapter rows — covered.
        seedLease(b.id!!, leaseType = LeaseType.CHAPTERS,
            status = LeaseStatus.COMPLETED, completedAt = LocalDateTime.now())

        val extracted = TranscodeLeaseService.getChapterExtractedTranscodeIds()
        assertEquals(setOf(a.id!!, b.id!!), extracted)
    }

    // ---------------------- getThroughputStats ----------------------

    @Test
    fun `getThroughputStats reports zeros on an empty table`() {
        val stats = TranscodeLeaseService.getThroughputStats()
        assertEquals(0, stats.totalCompleted)
        assertEquals(0L, stats.totalBytes)
        assertEquals(0.0, stats.transcodeRate)
        assertEquals(0, stats.activeWorkers)
        assertEquals(0, stats.failedCount)
    }

    @Test
    fun `getThroughputStats sums totals across completed leases and counts failures`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        val now = LocalDateTime.now()
        // Two completed transcodes within the rolling window. claimed_at
        // must precede completed_at so the rate calc's span is positive
        // — seedLease's default claimed_at is `now`, which is wrong if
        // we then backdate completed_at.
        seedLease(tc.id!!, status = LeaseStatus.COMPLETED,
            completedAt = now.minusMinutes(30)).apply {
            claimed_at = now.minusMinutes(60)
            file_size_bytes = 1_000_000_000; save()
        }
        seedLease(tc.id!!, status = LeaseStatus.COMPLETED,
            completedAt = now.minusMinutes(10)).apply {
            claimed_at = now.minusMinutes(40)
            file_size_bytes = 2_000_000_000; save()
        }
        // One active.
        seedLease(tc.id!!)
        // Two failures.
        seedLease(tc.id!!, status = LeaseStatus.FAILED, completedAt = now)
        seedLease(tc.id!!, status = LeaseStatus.EXPIRED, completedAt = now)

        val stats = TranscodeLeaseService.getThroughputStats()
        assertEquals(2, stats.totalCompleted)
        assertEquals(3_000_000_000L, stats.totalBytes)
        assertEquals(1, stats.activeWorkers)
        assertEquals(2, stats.failedCount, "FAILED + EXPIRED both count as failure")
        assertTrue(stats.transcodeRate > 0.0, "two completions across ~20m → non-zero rate")
        assertTrue(stats.bytesPerHour > 0.0)
    }

    @Test
    fun `getThroughputStats returns zero rates with fewer than two completions in a category`() {
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        // Single completion in each non-default category — needs >= 2 to compute a rate.
        seedLease(tc.id!!, leaseType = LeaseType.THUMBNAILS,
            status = LeaseStatus.COMPLETED, completedAt = LocalDateTime.now())

        val stats = TranscodeLeaseService.getThroughputStats()
        assertEquals(0.0, stats.thumbnailRate)
        assertEquals(0.0, stats.transcodeRate)
        assertEquals(0.0, stats.subtitleRate)
        assertEquals(0.0, stats.chapterRate)
        assertEquals(0.0, stats.mobileRate)
    }

    // ---------------------- ThroughputStats.estimateSecondsLeft ----------------------

    @Test
    fun `ThroughputStats estimateSecondsLeft returns null when nothing is pending`() {
        val stats = ThroughputStats(
            totalCompleted = 5,
            totalBytes = 0,
            transcodeRate = 1.0,
            thumbnailRate = 0.0,
            subtitleRate = 0.0,
            bytesPerHour = 0.0,
            activeWorkers = 0,
            failedCount = 0,
        )
        assertNull(stats.estimateSecondsLeft(PendingWork(0, 0, 0)))
    }

    @Test
    fun `ThroughputStats estimateSecondsLeft scales pending work by the per-type rate`() {
        // 60 transcodes/hour = 1 per minute; 60 pending → ~1 hour = 3600 s.
        val stats = ThroughputStats(
            totalCompleted = 100,
            totalBytes = 0,
            transcodeRate = 60.0,
            thumbnailRate = 0.0,
            subtitleRate = 0.0,
            bytesPerHour = 0.0,
            activeWorkers = 0,
            failedCount = 0,
        )
        val seconds = stats.estimateSecondsLeft(PendingWork(transcodes = 60,
            thumbnails = 0, subtitles = 0))!!
        assertTrue(seconds in 3500..3700,
            "60 transcodes / 60 per hour ≈ 3600s; got $seconds")
    }

    // ---------------------- hasSubtitleFile / hasSubtitleSentinel ----------------------

    @Test
    fun `hasSubtitleFile sees the en-srt sibling and ignores unrelated files`() {
        val tmp = java.nio.file.Files.createTempDirectory("sub-test-").toFile()
        tmp.deleteOnExit()
        val mp4 = java.io.File(tmp, "Movie.mp4").apply { writeBytes(ByteArray(0)) }
        // No sibling yet.
        assertFalse(TranscodeLeaseService.hasSubtitleFile(mp4))
        // Drop the SRT next to it.
        java.io.File(tmp, "Movie.en.srt").writeText("1\n00:00:00,000 --> 00:00:01,000\nhi\n")
        assertTrue(TranscodeLeaseService.hasSubtitleFile(mp4))
    }

    @Test
    fun `hasSubtitleSentinel sees the en-srt-failed sibling`() {
        val tmp = java.nio.file.Files.createTempDirectory("sub-sentinel-").toFile()
        tmp.deleteOnExit()
        val mp4 = java.io.File(tmp, "Movie.mp4").apply { writeBytes(ByteArray(0)) }
        assertFalse(TranscodeLeaseService.hasSubtitleSentinel(mp4))
        java.io.File(tmp, "Movie.en.srt.failed").writeText("")
        assertTrue(TranscodeLeaseService.hasSubtitleSentinel(mp4))
    }

    // ---------------------- countPendingWork ----------------------

    @Test
    fun `countPendingWork returns zero when no transcodes need work`() {
        // No nas_root_path → countPendingWork returns zeros (early return).
        val pending = TranscodeLeaseService.countPendingWork()
        assertEquals(0, pending.transcodes)
        assertEquals(0, pending.thumbnails)
        assertEquals(0, pending.subtitles)
        assertEquals(0, pending.chapters)
        assertEquals(0, pending.mobileTranscodes)
        assertEquals(0, pending.total)
        assertEquals(0, TranscodeLeaseService.countPendingTranscodes())
    }

    // ---------------------- claimWork early returns ----------------------

    @Test
    fun `claimWork returns null when nas_root_path is not configured`() {
        // No AppConfig row → TranscoderAgent.getNasRoot returns null →
        // claimWork bails before scanning candidates.
        val title = seedTitle()
        val tc = seedTranscode(title.id!!, "/nas/movies/foo.mkv")
        // Even with a transcode in flight, no nas_root → null.
        assertNull(TranscodeLeaseService.claimWork("local"))
        // Sanity: we didn't create any leases trying.
        assertEquals(0, TranscodeLease.findAll().size)
    }

    @Test
    fun `claimWork returns null when buddy is at the bundle limit`() {
        AppConfig(config_key = "nas_root_path", config_val = "/nas").save()
        val title = seedTitle()
        val tc = seedTranscode(title.id!!)
        // Three active bundles for `busy-buddy` — equals MAX_BUNDLES_PER_BUDDY.
        repeat(3) { i ->
            seedLease(tc.id!!, buddyName = "busy-buddy",
                leaseType = LeaseType.TRANSCODE).apply {
                // Each bundle needs a unique transcode_id so the buddy-bundle
                // count counts unique IDs, not duplicate leases on one tc.
                this.transcode_id = seedTranscode(title.id!!, "/nas/movies/x$i.mkv").id!!
                save()
            }
        }
        assertNull(TranscodeLeaseService.claimWork("busy-buddy"))
    }

    @Test
    fun `claimWork returns null when nothing in the catalog is eligible`() {
        AppConfig(config_key = "nas_root_path", config_val = "/nas").save()
        // No Transcode rows → workItems stays empty → returns null.
        assertNull(TranscodeLeaseService.claimWork("local"))
    }

    @Test
    fun `claimWork skips transcodes whose source file does not exist`() {
        AppConfig(config_key = "nas_root_path", config_val = "/nas").save()
        val title = seedTitle()
        // Transcode points at /nas/movies/missing.mkv — File(...).exists()
        // is false → not eligible for any work item type.
        seedTranscode(title.id!!, "/nas/movies/missing.mkv")
        assertNull(TranscodeLeaseService.claimWork("local"))
    }

    @Test
    fun `claimWork skips transcodes whose title is hidden`() {
        AppConfig(config_key = "nas_root_path", config_val = "/nas").save()
        val title = seedTitle().apply { hidden = true; save() }
        // Even if a real file existed, hidden-title transcodes are
        // filtered before any per-type check.
        seedTranscode(title.id!!, "/nas/movies/foo.mkv")
        assertNull(TranscodeLeaseService.claimWork("local"))
    }
}
