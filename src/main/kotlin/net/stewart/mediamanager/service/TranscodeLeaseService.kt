package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.time.LocalDateTime

/**
 * Core lease logic for the transcode buddy system.
 *
 * Both the local [TranscoderAgent] (buddy_name="local") and remote buddy workers
 * claim work through this service, ensuring a single codepath for work assignment.
 * This prevents races and enables poison pill detection: if a file has failed N times
 * (across any combination of workers), it is automatically skipped.
 */
object TranscodeLeaseService {

    private val log = LoggerFactory.getLogger(TranscodeLeaseService::class.java)
    private val claimLock = Any()

    /** Default lease duration if not configured. Kept short since buddies send
     *  heartbeats every ~15s which renew the lease. Short leases mean stranded
     *  leases from crashed buddies expire quickly instead of blocking for hours. */
    private const val DEFAULT_LEASE_MINUTES = 10L

    /** Number of failed/expired leases before a transcode is considered a poison pill. */
    private const val DEFAULT_MAX_FAILURES = 3

    /** Max simultaneous active bundles per buddy (counts unique transcode_ids, not individual leases). */
    private const val MAX_BUNDLES_PER_BUDDY = 3

    /**
     * Work item representing a single piece of work (transcode, thumbnail, subtitle, or chapter)
     * for unified priority sorting.
     */
    private data class WorkItem(
        val transcode: Transcode,
        val leaseType: LeaseType,
        val titleId: Long,
        /** 0 = TRANSCODE, 1 = THUMBNAILS, 2 = SUBTITLES, 3 = CHAPTERS — order within a title */
        val typeOrder: Int
    )

    /**
     * Claims all outstanding work for the highest-priority file as a bundle.
     *
     * Returns a [LeaseBundle] containing multiple leases for a single transcode_id,
     * allowing the buddy to copy the source file once and process all operations
     * against the local copy.
     *
     * Priority ordering:
     * 1. Files already cached by the buddy ([cachedTranscodeIds]) — avoids re-copying
     * 2. Wished titles first (priority transcodes jump to top)
     * 3. Then by TMDB popularity (most popular first)
     *
     * Per-buddy limit counts active bundles (unique transcode_ids), not individual leases.
     *
     * Synchronized to prevent two workers from claiming the same file.
     */
    fun claimWork(
        buddyName: String,
        skipTypes: Set<String> = emptySet(),
        cachedTranscodeIds: Set<Long> = emptySet()
    ): LeaseBundle? = synchronized(claimLock) {
        expireStaleLeases()

        // Per-buddy bundle limit: count active bundles (unique transcode_ids), not individual leases
        val activeBundleCount = TranscodeLease.findAll()
            .filter {
                it.buddy_name == buddyName &&
                    (it.status == LeaseStatus.CLAIMED.name || it.status == LeaseStatus.IN_PROGRESS.name)
            }
            .map { it.transcode_id }
            .distinct()
            .count()
        if (activeBundleCount >= MAX_BUNDLES_PER_BUDDY) {
            log.info("Buddy '{}' at bundle limit ({}/{}), rejecting claim", buddyName, activeBundleCount, MAX_BUNDLES_PER_BUDDY)
            return null
        }

        val nasRoot = TranscoderAgent.getNasRoot() ?: return null

        val activeLeasedIds = getActiveLeasedTranscodeIds()
        val activeThumbnailIds = getActiveLeasedTranscodeIds(LeaseType.THUMBNAILS)
        val activeSubtitleIds = getActiveLeasedTranscodeIds(LeaseType.SUBTITLES)
        val activeChapterIds = getActiveLeasedTranscodeIds(LeaseType.CHAPTERS)
        val activeMobileIds = getActiveLeasedTranscodeIds(LeaseType.MOBILE_TRANSCODE)

        val poisonPillIds = getPoisonPillTranscodeIds()
        val thumbnailPoisonPillIds = getPoisonPillTranscodeIds(leaseType = LeaseType.THUMBNAILS)
        val subtitlePoisonPillIds = getPoisonPillTranscodeIds(leaseType = LeaseType.SUBTITLES)
        val chapterPoisonPillIds = getPoisonPillTranscodeIds(leaseType = LeaseType.CHAPTERS)
        val mobilePoisonPillIds = getPoisonPillTranscodeIds(leaseType = LeaseType.MOBILE_TRANSCODE)

        // Pre-load chapter extraction state: transcode IDs that already have chapters or completed CHAPTERS leases
        val chapterExtractedIds = getChapterExtractedTranscodeIds()

        val titles = Title.findAll().associateBy { it.id }
        val hiddenTitleIds = titles.values.filter { it.hidden }.map { it.id }.toSet()
        val wishedTitleIds = WishListService.getTranscodeWishedTitleIds()

        val workItems = mutableListOf<WorkItem>()

        for (tc in Transcode.findAll()) {
            if (tc.file_path == null || tc.title_id in hiddenTitleIds) continue
            val filePath = tc.file_path!!

            // --- Check TRANSCODE eligibility ---
            if (LeaseType.TRANSCODE.name !in skipTypes &&
                TranscoderAgent.needsTranscoding(filePath) &&
                tc.id !in activeLeasedIds &&
                tc.id !in poisonPillIds &&
                !TranscoderAgent.isTranscoded(nasRoot, filePath) &&
                File(filePath).exists()
            ) {
                workItems.add(WorkItem(tc, LeaseType.TRANSCODE, tc.title_id, 0))
            }

            // --- Check THUMBNAILS eligibility ---
            // FFmpeg can generate thumbnails from any video format (MKV, MP4, AVI).
            // No need to wait for ForBrowser transcode — source file is sufficient.
            if (LeaseType.THUMBNAILS.name !in skipTypes &&
                tc.id !in activeThumbnailIds &&
                tc.id !in thumbnailPoisonPillIds
            ) {
                val hasSprites = TranscoderAgent.findAuxFile(nasRoot, filePath, ".thumbs.vtt") != null
                if (!hasSprites && File(filePath).exists()) {
                    workItems.add(WorkItem(tc, LeaseType.THUMBNAILS, tc.title_id, 1))
                }
            }

            // --- Check SUBTITLES eligibility ---
            // Whisper reads MKV/AVI source directly for best audio quality — no playable MP4 needed.
            if (LeaseType.SUBTITLES.name !in skipTypes &&
                tc.id !in activeSubtitleIds &&
                tc.id !in subtitlePoisonPillIds
            ) {
                val hasSubs = TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt") != null
                val hasSentinel = TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt.failed") != null
                if (!hasSubs && !hasSentinel && File(filePath).exists()) {
                    workItems.add(WorkItem(tc, LeaseType.SUBTITLES, tc.title_id, 2))
                }
            }

            // --- Check CHAPTERS eligibility ---
            if (LeaseType.CHAPTERS.name !in skipTypes &&
                tc.id !in activeChapterIds &&
                tc.id !in chapterPoisonPillIds &&
                tc.id !in chapterExtractedIds &&
                File(filePath).exists()
            ) {
                workItems.add(WorkItem(tc, LeaseType.CHAPTERS, tc.title_id, 3))
            }

            // --- Check MOBILE_TRANSCODE eligibility ---
            if (LeaseType.MOBILE_TRANSCODE.name !in skipTypes &&
                isForMobileEnabled() &&
                tc.id !in activeMobileIds &&
                tc.id !in mobilePoisonPillIds &&
                !tc.for_mobile_available &&
                !TranscoderAgent.isMobileTranscoded(nasRoot, filePath) &&
                File(filePath).exists()
            ) {
                workItems.add(WorkItem(tc, LeaseType.MOBILE_TRANSCODE, tc.title_id, 4))
            }
        }

        if (workItems.isEmpty()) return null

        // Unified sort: wished first, then popularity, then work type within title
        val sorted = workItems.sortedWith(
            compareByDescending<WorkItem> {
                if (it.titleId in wishedTitleIds) 1 else 0
            }.thenByDescending {
                titles[it.titleId]?.popularity ?: Double.MIN_VALUE
            }.thenBy {
                it.typeOrder
            }
        )

        // Pick the best transcode_id: prefer cached files, then highest priority
        val orderedTranscodeIds = sorted.map { it.transcode.id!! }.distinct()
        val winnerId = if (cachedTranscodeIds.isNotEmpty()) {
            orderedTranscodeIds.firstOrNull { it in cachedTranscodeIds } ?: orderedTranscodeIds.first()
        } else {
            orderedTranscodeIds.first()
        }

        // Create leases for ALL outstanding work on the winning file
        val winnerItems = workItems.filter { it.transcode.id == winnerId }
        return createBundle(winnerItems, nasRoot, buddyName)
    }

    private fun createBundle(items: List<WorkItem>, nasRoot: String, buddyName: String): LeaseBundle {
        val tc = items.first().transcode
        val sourceFile = File(tc.file_path!!)
        val relativePath = File(nasRoot).toPath().relativize(sourceFile.toPath()).toString()
            .replace('\\', '/')
        val fileSizeBytes = tc.file_size_bytes ?: sourceFile.length()

        val leases = items.map { item ->
            createLease(item.transcode, nasRoot, buddyName, item.leaseType, relativePath)
        }

        val types = leases.map { it.lease_type }
        log.info("Bundle of {} lease(s) claimed by '{}': {} — types={} (transcode_id={})",
            leases.size, buddyName, relativePath, types, tc.id)

        return LeaseBundle(
            transcodeId = tc.id!!,
            relativePath = relativePath,
            fileSizeBytes = fileSizeBytes,
            leases = leases
        )
    }

    private fun createLease(
        tc: Transcode,
        nasRoot: String,
        buddyName: String,
        leaseType: LeaseType,
        relativePath: String? = null
    ): TranscodeLease {
        val sourceFile = File(tc.file_path!!)
        val relPath = relativePath ?: File(nasRoot).toPath().relativize(sourceFile.toPath()).toString()
            .replace('\\', '/')
        val leaseDuration = getLeaseDurationMinutes()

        val lease = TranscodeLease(
            transcode_id = tc.id!!,
            buddy_name = buddyName,
            relative_path = relPath,
            file_size_bytes = tc.file_size_bytes ?: sourceFile.length(),
            claimed_at = LocalDateTime.now(),
            expires_at = LocalDateTime.now().plusMinutes(leaseDuration),
            status = LeaseStatus.CLAIMED.name,
            lease_type = leaseType.name
        )
        lease.save()

        log.info("Lease {} ({}) claimed by '{}': {} (transcode_id={})",
            lease.id, leaseType, buddyName, relPath, tc.id)

        Broadcaster.broadcastBuddyProgress(BuddyProgressEvent(
            leaseId = lease.id!!,
            buddyName = buddyName,
            relativePath = relPath,
            status = LeaseStatus.CLAIMED.name,
            progressPercent = 0,
            encoder = null
        ))

        return lease
    }

    /**
     * Reports progress on an active lease. Updates status to IN_PROGRESS and
     * renews the expiry if forward progress is detected.
     */
    fun reportProgress(leaseId: Long, percent: Int, encoder: String?): TranscodeLease? {
        val lease = TranscodeLease.findById(leaseId) ?: return null
        if (lease.status != LeaseStatus.CLAIMED.name && lease.status != LeaseStatus.IN_PROGRESS.name) return null

        val forwardProgress = percent > lease.progress_percent
        lease.status = LeaseStatus.IN_PROGRESS.name
        lease.progress_percent = percent
        lease.last_progress_at = LocalDateTime.now()
        if (encoder != null) lease.encoder = encoder
        if (forwardProgress) {
            lease.expires_at = LocalDateTime.now().plusMinutes(getLeaseDurationMinutes())
        }
        lease.save()

        Broadcaster.broadcastBuddyProgress(BuddyProgressEvent(
            leaseId = leaseId,
            buddyName = lease.buddy_name,
            relativePath = lease.relative_path,
            status = LeaseStatus.IN_PROGRESS.name,
            progressPercent = percent,
            encoder = encoder
        ))

        return lease
    }

    /**
     * Reports successful completion of a transcode.
     */
    fun reportComplete(leaseId: Long, encoder: String?): TranscodeLease? {
        val lease = TranscodeLease.findById(leaseId) ?: return null
        if (lease.status != LeaseStatus.CLAIMED.name && lease.status != LeaseStatus.IN_PROGRESS.name) return null

        lease.status = LeaseStatus.COMPLETED.name
        lease.progress_percent = 100
        lease.completed_at = LocalDateTime.now()
        if (encoder != null) lease.encoder = encoder
        lease.save()

        log.info("Lease {} completed by '{}': {}", leaseId, lease.buddy_name, lease.relative_path)

        // Auto-fulfill transcode wishes
        val transcode = Transcode.findById(lease.transcode_id)
        if (transcode != null) {
            WishListService.fulfillTranscodeWishes(transcode.title_id)
        }

        Broadcaster.broadcastBuddyProgress(BuddyProgressEvent(
            leaseId = leaseId,
            buddyName = lease.buddy_name,
            relativePath = lease.relative_path,
            status = LeaseStatus.COMPLETED.name,
            progressPercent = 100,
            encoder = encoder
        ))

        return lease
    }

    /**
     * Reports a failed transcode attempt.
     */
    fun reportFailure(leaseId: Long, errorMessage: String?): TranscodeLease? {
        val lease = TranscodeLease.findById(leaseId) ?: return null
        if (lease.status != LeaseStatus.CLAIMED.name && lease.status != LeaseStatus.IN_PROGRESS.name) return null

        lease.status = LeaseStatus.FAILED.name
        lease.error_message = errorMessage?.take(2048)
        lease.completed_at = LocalDateTime.now()
        lease.save()

        log.warn("Lease {} failed by '{}': {} — {}", leaseId, lease.buddy_name, lease.relative_path, errorMessage)

        Broadcaster.broadcastBuddyProgress(BuddyProgressEvent(
            leaseId = leaseId,
            buddyName = lease.buddy_name,
            relativePath = lease.relative_path,
            status = LeaseStatus.FAILED.name,
            progressPercent = lease.progress_percent,
            encoder = lease.encoder
        ))

        return lease
    }

    /**
     * Heartbeat to renew lease expiry without changing progress.
     */
    fun heartbeat(leaseId: Long): TranscodeLease? {
        val lease = TranscodeLease.findById(leaseId) ?: return null
        if (lease.status != LeaseStatus.CLAIMED.name && lease.status != LeaseStatus.IN_PROGRESS.name) return null

        lease.expires_at = LocalDateTime.now().plusMinutes(getLeaseDurationMinutes())
        lease.last_progress_at = LocalDateTime.now()
        lease.save()

        log.debug("Heartbeat for lease {} (buddy='{}', file={})", leaseId, lease.buddy_name, lease.relative_path)

        return lease
    }

    /**
     * Heartbeat for multiple lease IDs at once. Used by buddies to keep all
     * leases in a bundle alive while processing one at a time.
     */
    fun heartbeatMultiple(leaseIds: List<Long>): Int {
        var renewed = 0
        for (id in leaseIds) {
            if (heartbeat(id) != null) renewed++
        }
        return renewed
    }

    /**
     * Checks which of the given transcode IDs have outstanding work.
     * Used by buddies on startup to determine which cached files are still useful.
     * Lightweight query — no leases are created.
     */
    fun checkPending(transcodeIds: List<Long>): List<Long> {
        if (transcodeIds.isEmpty()) return emptyList()

        val nasRoot = TranscoderAgent.getNasRoot() ?: return emptyList()
        val hiddenTitleIds = Title.findAll().filter { it.hidden }.mapNotNull { it.id }.toSet()

        val activeLeasedIds = getActiveLeasedTranscodeIds()
        val activeThumbnailIds = getActiveLeasedTranscodeIds(LeaseType.THUMBNAILS)
        val activeSubtitleIds = getActiveLeasedTranscodeIds(LeaseType.SUBTITLES)
        val activeChapterIds = getActiveLeasedTranscodeIds(LeaseType.CHAPTERS)
        val activeMobileIds = getActiveLeasedTranscodeIds(LeaseType.MOBILE_TRANSCODE)

        val poisonPillIds = getPoisonPillTranscodeIds()
        val thumbnailPoisonPillIds = getPoisonPillTranscodeIds(leaseType = LeaseType.THUMBNAILS)
        val subtitlePoisonPillIds = getPoisonPillTranscodeIds(leaseType = LeaseType.SUBTITLES)
        val chapterPoisonPillIds = getPoisonPillTranscodeIds(leaseType = LeaseType.CHAPTERS)
        val mobilePoisonPillIds = getPoisonPillTranscodeIds(leaseType = LeaseType.MOBILE_TRANSCODE)
        val chapterExtractedIds = getChapterExtractedTranscodeIds()

        val requestedSet = transcodeIds.toSet()
        val pending = mutableSetOf<Long>()

        for (tc in Transcode.findAll()) {
            if (tc.id !in requestedSet) continue
            if (tc.file_path == null || tc.title_id in hiddenTitleIds) continue
            val filePath = tc.file_path!!
            if (!File(filePath).exists()) continue

            if (TranscoderAgent.needsTranscoding(filePath) &&
                tc.id !in activeLeasedIds && tc.id !in poisonPillIds &&
                !TranscoderAgent.isTranscoded(nasRoot, filePath)
            ) { pending.add(tc.id!!); continue }

            val hasSprites = TranscoderAgent.findAuxFile(nasRoot, filePath, ".thumbs.vtt") != null
            if (!hasSprites && tc.id !in activeThumbnailIds && tc.id !in thumbnailPoisonPillIds) {
                pending.add(tc.id!!); continue
            }

            val hasSubs = TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt") != null
            val hasSentinel = TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt.failed") != null
            if (!hasSubs && !hasSentinel && tc.id !in activeSubtitleIds && tc.id !in subtitlePoisonPillIds) {
                pending.add(tc.id!!); continue
            }

            if (tc.id !in activeChapterIds && tc.id !in chapterPoisonPillIds && tc.id !in chapterExtractedIds) {
                pending.add(tc.id!!); continue
            }

            if (TranscodeLeaseService.isForMobileEnabled() &&
                tc.id !in activeMobileIds && tc.id !in mobilePoisonPillIds &&
                !tc.for_mobile_available && !TranscoderAgent.isMobileTranscoded(nasRoot, filePath)
            ) { pending.add(tc.id!!); continue }
        }

        return pending.toList()
    }

    /**
     * Returns transcode IDs with active (CLAIMED or IN_PROGRESS) leases,
     * optionally filtered by lease type.
     */
    fun getActiveLeasedTranscodeIds(leaseType: LeaseType? = null): Set<Long> {
        return TranscodeLease.findAll()
            .filter {
                (it.status == LeaseStatus.CLAIMED.name || it.status == LeaseStatus.IN_PROGRESS.name) &&
                    (leaseType == null || it.lease_type == leaseType.name)
            }
            .map { it.transcode_id }
            .toSet()
    }

    /**
     * Returns transcode IDs with >= [maxFailures] failed or expired leases.
     * These files have crashed FFmpeg repeatedly and are skipped to prevent
     * blocking the queue. Filtered by lease type so TRANSCODE and THUMBNAILS
     * poison pills are tracked independently.
     */
    fun getPoisonPillTranscodeIds(
        maxFailures: Int = DEFAULT_MAX_FAILURES,
        leaseType: LeaseType = LeaseType.TRANSCODE
    ): Set<Long> {
        val failedStatuses = setOf(LeaseStatus.FAILED.name, LeaseStatus.EXPIRED.name)
        return TranscodeLease.findAll()
            .filter { it.status in failedStatuses && it.lease_type == leaseType.name }
            .groupBy { it.transcode_id }
            .filter { it.value.size >= maxFailures }
            .keys
    }

    /**
     * Returns all active leases (CLAIMED or IN_PROGRESS).
     */
    fun getActiveLeases(): List<TranscodeLease> {
        return TranscodeLease.findAll()
            .filter { it.status == LeaseStatus.CLAIMED.name || it.status == LeaseStatus.IN_PROGRESS.name }
            .sortedByDescending { it.claimed_at }
    }

    /**
     * Returns recent completed/failed leases for display.
     */
    fun getRecentLeases(limit: Int = 10): List<TranscodeLease> {
        return TranscodeLease.findAll()
            .filter { it.status == LeaseStatus.COMPLETED.name || it.status == LeaseStatus.FAILED.name }
            .sortedByDescending { it.completed_at }
            .take(limit)
    }

    /**
     * Marks expired leases and cleans up orphaned .tmp files.
     */
    fun expireStaleLeases() {
        val now = LocalDateTime.now()
        val nasRoot = TranscoderAgent.getNasRoot()

        val stale = TranscodeLease.findAll().filter {
            (it.status == LeaseStatus.CLAIMED.name || it.status == LeaseStatus.IN_PROGRESS.name) &&
                it.expires_at != null && it.expires_at!!.isBefore(now)
        }

        for (lease in stale) {
            log.warn("Expiring stale lease {} (buddy='{}', file={})", lease.id, lease.buddy_name, lease.relative_path)
            lease.status = LeaseStatus.EXPIRED.name
            lease.completed_at = now
            lease.save()

            // Clean up orphaned .tmp file
            if (nasRoot != null) {
                try {
                    val sourceFile = File(nasRoot, lease.relative_path)
                    val forBrowserPath = TranscoderAgent.getForBrowserPath(nasRoot, sourceFile.absolutePath)
                    val tmpFile = File(forBrowserPath.parentFile, forBrowserPath.nameWithoutExtension + ".tmp")
                    if (tmpFile.exists()) {
                        log.info("Cleaning up orphaned tmp file: {}", tmpFile.absolutePath)
                        tmpFile.delete()
                    }
                } catch (e: Exception) {
                    log.warn("Error cleaning up tmp file for expired lease {}: {}", lease.id, e.message)
                }
            }

            Broadcaster.broadcastBuddyProgress(BuddyProgressEvent(
                leaseId = lease.id!!,
                buddyName = lease.buddy_name,
                relativePath = lease.relative_path,
                status = LeaseStatus.EXPIRED.name,
                progressPercent = lease.progress_percent,
                encoder = lease.encoder
            ))
        }
    }

    /**
     * Clears ALL failed and expired lease records across all transcodes.
     * Used to wipe stale failures from previous sessions (e.g., after wiping ForBrowser).
     */
    fun clearAllFailures(): Int {
        val failedStatuses = setOf(LeaseStatus.FAILED.name, LeaseStatus.EXPIRED.name)
        val toDelete = TranscodeLease.findAll()
            .filter { it.status in failedStatuses }
        toDelete.forEach { it.delete() }
        log.info("Cleared {} failed/expired leases (all transcodes)", toDelete.size)
        return toDelete.size
    }

    /**
     * Clears failed/expired lease records for a specific transcode, allowing retry.
     * Used as an admin action to reset poison pill files.
     */
    fun clearFailures(transcodeId: Long): Int {
        val failedStatuses = setOf(LeaseStatus.FAILED.name, LeaseStatus.EXPIRED.name)
        val toDelete = TranscodeLease.findAll()
            .filter { it.transcode_id == transcodeId && it.status in failedStatuses }
        toDelete.forEach { it.delete() }
        log.info("Cleared {} failed/expired leases for transcode_id={}", toDelete.size, transcodeId)
        return toDelete.size
    }

    /**
     * Returns summary statistics for the buddy status endpoint.
     */
    fun getStatusSummary(): BuddyStatusSummary {
        val allLeases = TranscodeLease.findAll()
        val today = LocalDateTime.now().toLocalDate()
        return BuddyStatusSummary(
            activeLeases = allLeases.count {
                it.status == LeaseStatus.CLAIMED.name || it.status == LeaseStatus.IN_PROGRESS.name
            },
            completedToday = allLeases.count {
                it.status == LeaseStatus.COMPLETED.name &&
                    it.completed_at?.toLocalDate() == today
            },
            poisonPills = getPoisonPillTranscodeIds().size
        )
    }

    /**
     * Returns counts of completed thumbnail leases (total and today).
     */
    fun getThumbnailStats(): Pair<Int, Int> {
        val allLeases = TranscodeLease.findAll()
            .filter { it.lease_type == LeaseType.THUMBNAILS.name }
        val today = LocalDateTime.now().toLocalDate()
        val total = allLeases.count { it.status == LeaseStatus.COMPLETED.name }
        val todayCount = allLeases.count {
            it.status == LeaseStatus.COMPLETED.name &&
                it.completed_at?.toLocalDate() == today
        }
        return total to todayCount
    }

    /**
     * Returns throughput statistics computed from completed leases.
     * Uses a rolling 24-hour window for rate calculations.
     */
    fun getThroughputStats(): ThroughputStats {
        val allLeases = TranscodeLease.findAll()
        val now = LocalDateTime.now()
        val windowStart = now.minusHours(24)

        val completedAll = allLeases.filter { it.status == LeaseStatus.COMPLETED.name }

        fun rateForType(type: LeaseType): Double {
            val typed = completedAll.filter {
                it.lease_type == type.name && it.completed_at != null
            }
            // Prefer recent 24h window; fall back to all historical data
            val recent = typed.filter { it.completed_at!!.isAfter(windowStart) }
            val candidates = if (recent.size >= 2) recent else typed
            if (candidates.size < 2) return 0.0
            val earliest = candidates.minOf { it.claimed_at ?: it.completed_at!! }
            val latest = candidates.maxOf { it.completed_at!! }
            val spanHours = Duration.between(earliest, latest).toMinutes() / 60.0
            return if (spanHours > 0.01) candidates.size / spanHours else 0.0
        }

        val transcodeRate = rateForType(LeaseType.TRANSCODE)
        val thumbnailRate = rateForType(LeaseType.THUMBNAILS)
        val subtitleRate = rateForType(LeaseType.SUBTITLES)
        val chapterRate = rateForType(LeaseType.CHAPTERS)

        val totalCompleted = completedAll.size
        val totalBytes = completedAll.sumOf { it.file_size_bytes ?: 0L }
        val activeWorkers = allLeases.count {
            it.status == LeaseStatus.CLAIMED.name || it.status == LeaseStatus.IN_PROGRESS.name
        }
        val failedCount = allLeases.count {
            it.status == LeaseStatus.FAILED.name || it.status == LeaseStatus.EXPIRED.name
        }

        // Byte-level throughput for transcodes only (most meaningful for data volume)
        val recentTranscodes = completedAll.filter {
            it.lease_type == LeaseType.TRANSCODE.name &&
                it.completed_at != null && it.completed_at!!.isAfter(windowStart)
        }
        val bytesPerHour = if (recentTranscodes.size >= 2) {
            val earliest = recentTranscodes.minOf { it.claimed_at ?: it.completed_at!! }
            val latest = recentTranscodes.maxOf { it.completed_at!! }
            val spanHours = Duration.between(earliest, latest).toMinutes() / 60.0
            if (spanHours > 0.01) recentTranscodes.sumOf { it.file_size_bytes ?: 0L } / spanHours else 0.0
        } else 0.0

        return ThroughputStats(
            totalCompleted = totalCompleted,
            totalBytes = totalBytes,
            transcodeRate = transcodeRate,
            thumbnailRate = thumbnailRate,
            subtitleRate = subtitleRate,
            chapterRate = chapterRate,
            bytesPerHour = bytesPerHour,
            activeWorkers = activeWorkers,
            failedCount = failedCount
        )
    }

    /**
     * Counts pending work across all lease types in a single pass.
     * Expensive — checks NAS file existence. Callers should cache the result.
     */
    fun countPendingWork(): PendingWork {
        val nasRoot = TranscoderAgent.getNasRoot() ?: return PendingWork(0, 0, 0, 0)
        val hiddenTitleIds = Title.findAll()
            .filter { it.hidden }.mapNotNull { it.id }.toSet()

        val transcodePoisonIds = getPoisonPillTranscodeIds()
        val thumbPoisonIds = getPoisonPillTranscodeIds(leaseType = LeaseType.THUMBNAILS)
        val subtitlePoisonIds = getPoisonPillTranscodeIds(leaseType = LeaseType.SUBTITLES)
        val chapterPoisonIds = getPoisonPillTranscodeIds(leaseType = LeaseType.CHAPTERS)
        val activeTranscodeIds = getActiveLeasedTranscodeIds()
        val activeThumbIds = getActiveLeasedTranscodeIds(LeaseType.THUMBNAILS)
        val activeSubIds = getActiveLeasedTranscodeIds(LeaseType.SUBTITLES)
        val activeChapterIds = getActiveLeasedTranscodeIds(LeaseType.CHAPTERS)
        val chapterExtractedIds = getChapterExtractedTranscodeIds()

        var pendingTranscodes = 0
        var pendingThumbnails = 0
        var pendingSubtitles = 0
        var pendingChapters = 0

        for (tc in Transcode.findAll()) {
            if (tc.file_path == null || tc.title_id in hiddenTitleIds) continue
            val filePath = tc.file_path!!
            val sourceFile = File(filePath)
            if (!sourceFile.exists()) continue

            // ForBrowser transcoding
            if (TranscoderAgent.needsTranscoding(filePath) &&
                tc.id !in activeTranscodeIds && tc.id !in transcodePoisonIds &&
                !TranscoderAgent.isTranscoded(nasRoot, filePath)
            ) {
                pendingTranscodes++
            }

            // Thumbnails — source file is sufficient (FFmpeg handles MKV)
            val hasSprites = TranscoderAgent.findAuxFile(nasRoot, filePath, ".thumbs.vtt") != null
            if (!hasSprites && tc.id !in activeThumbIds && tc.id !in thumbPoisonIds) {
                pendingThumbnails++
            }

            // Subtitles — source file is sufficient (Whisper reads MKV directly)
            val hasSubs = TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt") != null
            val hasSentinel = TranscoderAgent.findAuxFile(nasRoot, filePath, ".en.srt.failed") != null
            if (!hasSubs && !hasSentinel && tc.id !in activeSubIds && tc.id !in subtitlePoisonIds) {
                pendingSubtitles++
            }

            // Chapters
            if (tc.id !in activeChapterIds && tc.id !in chapterPoisonIds &&
                tc.id !in chapterExtractedIds
            ) {
                pendingChapters++
            }
        }

        return PendingWork(pendingTranscodes, pendingThumbnails, pendingSubtitles, pendingChapters)
    }

    /**
     * Counts transcodes that still need ForBrowser transcoding.
     * Convenience wrapper around [countPendingWork].
     */
    fun countPendingTranscodes(): Int = countPendingWork().transcodes

    /**
     * Releases all active leases for a given buddy. Used when a buddy restarts
     * and wants to signal that it's no longer working on previously claimed files.
     */
    fun releaseLeases(buddyName: String): Int {
        val active = TranscodeLease.findAll().filter {
            it.buddy_name == buddyName &&
                (it.status == LeaseStatus.CLAIMED.name || it.status == LeaseStatus.IN_PROGRESS.name)
        }
        val nasRoot = TranscoderAgent.getNasRoot()
        for (lease in active) {
            log.info("Releasing lease {} for buddy '{}': {}", lease.id, buddyName, lease.relative_path)
            lease.status = LeaseStatus.EXPIRED.name
            lease.completed_at = LocalDateTime.now()
            lease.error_message = "Released on buddy restart"
            lease.save()

            // Clean up orphaned .tmp file
            if (nasRoot != null) {
                try {
                    val sourceFile = File(nasRoot, lease.relative_path)
                    val forBrowserPath = TranscoderAgent.getForBrowserPath(nasRoot, sourceFile.absolutePath)
                    val tmpFile = File(forBrowserPath.parentFile, forBrowserPath.nameWithoutExtension + ".tmp")
                    if (tmpFile.exists()) {
                        log.info("Cleaning up orphaned tmp file: {}", tmpFile.absolutePath)
                        tmpFile.delete()
                    }
                } catch (e: Exception) {
                    log.warn("Error cleaning up tmp file for released lease {}: {}", lease.id, e.message)
                }
            }
        }
        if (active.isNotEmpty()) {
            log.info("Released {} active lease(s) for buddy '{}'", active.size, buddyName)
        }
        return active.size
    }

    /**
     * Returns counts of completed subtitle leases (total and today).
     */
    fun getSubtitleStats(): Pair<Int, Int> {
        val allLeases = TranscodeLease.findAll()
            .filter { it.lease_type == LeaseType.SUBTITLES.name }
        val today = LocalDateTime.now().toLocalDate()
        val total = allLeases.count { it.status == LeaseStatus.COMPLETED.name }
        val todayCount = allLeases.count {
            it.status == LeaseStatus.COMPLETED.name &&
                it.completed_at?.toLocalDate() == today
        }
        return total to todayCount
    }

    /**
     * Returns transcode IDs that have already had chapters extracted
     * (either chapters exist in DB, or a CHAPTERS lease completed).
     */
    fun getChapterExtractedTranscodeIds(): Set<Long> {
        val withChapters = Chapter.findAll().map { it.transcode_id }.toSet()
        val completedLeases = TranscodeLease.findAll()
            .filter { it.lease_type == LeaseType.CHAPTERS.name && it.status == LeaseStatus.COMPLETED.name }
            .map { it.transcode_id }
            .toSet()
        return withChapters + completedLeases
    }

    /**
     * Returns counts of completed chapter leases (total and today).
     */
    fun getChapterStats(): Pair<Int, Int> {
        val allLeases = TranscodeLease.findAll()
            .filter { it.lease_type == LeaseType.CHAPTERS.name }
        val today = LocalDateTime.now().toLocalDate()
        val total = allLeases.count { it.status == LeaseStatus.COMPLETED.name }
        val todayCount = allLeases.count {
            it.status == LeaseStatus.COMPLETED.name &&
                it.completed_at?.toLocalDate() == today
        }
        return total to todayCount
    }

    /**
     * Checks whether an SRT subtitle file exists alongside the given MP4 file.
     * E.g., for `Movie.mp4`, checks for `Movie.en.srt`.
     */
    fun hasSubtitleFile(mp4File: File): Boolean {
        val srtFile = File(mp4File.parentFile, mp4File.nameWithoutExtension + ".en.srt")
        return srtFile.exists()
    }

    /**
     * Checks whether a subtitle failure sentinel exists for the given MP4 file.
     * E.g., for `Movie.mp4`, checks for `Movie.en.srt.failed`.
     */
    fun hasSubtitleSentinel(mp4File: File): Boolean {
        val sentinel = File(mp4File.parentFile, mp4File.nameWithoutExtension + ".en.srt.failed")
        return sentinel.exists()
    }

    fun isForMobileEnabled(): Boolean {
        return AppConfig.findAll()
            .firstOrNull { it.config_key == "for_mobile_enabled" }
            ?.config_val?.equals("true", ignoreCase = true) ?: true
    }

    private fun getLeaseDurationMinutes(): Long {
        return AppConfig.findAll()
            .firstOrNull { it.config_key == "buddy_lease_duration_minutes" }
            ?.config_val?.toLongOrNull()
            ?: DEFAULT_LEASE_MINUTES
    }
}

/**
 * A bundle of leases for a single source file. The buddy copies the file once
 * to local temp, then processes all leases against the local copy.
 */
data class LeaseBundle(
    val transcodeId: Long,
    val relativePath: String,
    val fileSizeBytes: Long,
    val leases: List<TranscodeLease>
)

data class BuddyStatusSummary(
    val activeLeases: Int,
    val completedToday: Int,
    val poisonPills: Int
)

data class PendingWork(
    val transcodes: Int,
    val thumbnails: Int,
    val subtitles: Int,
    val chapters: Int = 0
) {
    val total get() = transcodes + thumbnails + subtitles + chapters
}

data class ThroughputStats(
    val totalCompleted: Int,
    val totalBytes: Long,
    val transcodeRate: Double,
    val thumbnailRate: Double,
    val subtitleRate: Double,
    val chapterRate: Double = 0.0,
    val bytesPerHour: Double,
    val activeWorkers: Int,
    val failedCount: Int
) {
    /** Combined files/hour across all task types. */
    val filesPerHour: Double get() = transcodeRate + thumbnailRate + subtitleRate + chapterRate

    /** Estimated seconds to complete given pending work, accounting for per-type rates. */
    fun estimateSecondsLeft(pending: PendingWork): Long? {
        var totalSeconds = 0.0
        var hasRate = false
        if (pending.transcodes > 0 && transcodeRate > 0) {
            totalSeconds += pending.transcodes / transcodeRate * 3600
            hasRate = true
        }
        if (pending.thumbnails > 0 && thumbnailRate > 0) {
            totalSeconds += pending.thumbnails / thumbnailRate * 3600
            hasRate = true
        }
        if (pending.subtitles > 0 && subtitleRate > 0) {
            totalSeconds += pending.subtitles / subtitleRate * 3600
            hasRate = true
        }
        if (pending.chapters > 0 && chapterRate > 0) {
            totalSeconds += pending.chapters / chapterRate * 3600
            hasRate = true
        }
        return if (hasRate && totalSeconds > 0) totalSeconds.toLong() else null
    }
    fun formatBytesPerHour(): String = formatBytes(bytesPerHour.toLong()) + "/hr"
    fun formatTotalBytes(): String = formatBytes(totalBytes)

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_099_511_627_776L -> "%.1f TB".format(bytes / 1_099_511_627_776.0)
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
