package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import org.slf4j.LoggerFactory

/**
 * Wipe every `track.time_signature_source='MADMOM_FAILED'` back to
 * 'TAG' so the TimeSignatureAgent re-attempts analysis on the next
 * cycle.
 *
 * Why this is a one-shot migration: the first deploys of the madmom
 * sidecar crashed on a numpy compatibility issue (madmom 0.16.1's
 * `np.float` reference removed in numpy 1.20). Every track the
 * TimeSignatureAgent dispatched in that window got marked
 * MADMOM_FAILED + a `bpm_analysis_failed_mtime` equal to the current
 * file mtime. Once the sidecar was fixed, the built-in auto-requeue
 * would only pick them up when the file's mtime advances past the
 * recorded failure — which for a static catalog never happens.
 *
 * This updater resets the failure state across the board so the
 * whole backlog drains under the working sidecar. We also clear
 * `bpm_analysis_failed_mtime` for these rows so a future real
 * failure gets a fresh mtime captured.
 *
 * Runs once via the SchemaUpdater framework; bump `version` to
 * force a re-run if we ever need to repeat the reset.
 */
class RetryMadmomFailuresUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(RetryMadmomFailuresUpdater::class.java)

    override val name = "retry_madmom_failures"
    override val version = 1

    override fun run() {
        val affected = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE track
                SET time_signature_source = 'TAG',
                    time_signature_fail_count = 0,
                    bpm_analysis_failed_mtime = NULL
                WHERE time_signature_source = 'MADMOM_FAILED'
                """.trimIndent()
            ).execute()
        }
        log.info(
            "RetryMadmomFailuresUpdater: reset {} track(s) from MADMOM_FAILED -> TAG " +
            "for re-analysis under the working sidecar.",
            affected
        )
    }
}
