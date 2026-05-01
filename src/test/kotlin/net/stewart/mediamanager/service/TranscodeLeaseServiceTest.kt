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
        // Other buddies untouched.
        assertEquals(LeaseStatus.CLAIMED.name,
            byBuddy["buddy-b"]!!.single().status)
    }
}
