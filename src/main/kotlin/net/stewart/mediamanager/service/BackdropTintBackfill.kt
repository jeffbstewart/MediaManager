package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import net.stewart.mediamanager.entity.Title
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Computes [BackdropTint]s for backdrops cached before `title.backdrop_tint`
 * existed (web-theming decision 6). New backdrops get their tint from
 * [BackdropCacheService] as they are cached; this job only has to catch
 * up, once, and then finds nothing to do on every later start.
 *
 * Built to survive being killed at any instant:
 *
 * - The work set is the query `backdrop_path IS NOT NULL AND backdrop_tint
 *   IS NULL`. The column is the checkpoint, so there is no progress file to
 *   fall out of step with the rows.
 * - Per title: read the cached file, compute the tint, write it into the
 *   image's sidecar (temp file plus atomic move), then claim the row with
 *   `UPDATE ... WHERE backdrop_tint IS NULL`. Sidecar first, row last: a
 *   crash in between leaves the row unclaimed and the sidecar write is
 *   simply repeated.
 * - A backdrop that is missing on disk or does not decode gets
 *   [BackdropTint.FALLBACK] so the job converges instead of retrying
 *   forever; [BackdropCacheService] recomputes it if the backdrop is
 *   cached again.
 * - One thread, small batches, a pause between batches so the request
 *   path is not starved (H2 rule: sequential queries only).
 *
 * Not a `SchemaUpdater`: that framework records "applied" once and cannot
 * resume part way, which is the property this job needs.
 */
class BackdropTintBackfill(
    /** Where a title's cached backdrop lives, or null when it is not on disk. */
    private val resolveBackdrop: (Title) -> Path? = { title ->
        title.backdrop_cache_id?.let { BackdropCacheService.resolve(it) }
    },
    private val clock: Clock = SystemClock,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val pauseBetweenBatches: Duration = DEFAULT_PAUSE,
) {
    private val log = LoggerFactory.getLogger(BackdropTintBackfill::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Runs [run] on a daemon thread until it finishes or [stop] is called. */
    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            try {
                val done = run(alreadyRunning = true)
                if (done > 0) log.info("Backdrop tint backfill finished: {} titles", done)
            } catch (_: InterruptedException) {
                log.info("Backdrop tint backfill interrupted")
            } catch (e: Exception) {
                log.error("Backdrop tint backfill failed: {}", e.message, e)
            } finally {
                running.set(false)
            }
        }, "backdrop-tint-backfill").apply {
            isDaemon = true
            start()
        }
    }

    /** Asks the job to stop after the title it is on. */
    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /**
     * Processes pending titles until none remain or [stop] is called.
     * Returns how many titles were claimed. [onTitleDone] fires after each
     * claim, which lets a test stop the job mid-batch.
     */
    fun run(onTitleDone: (Long) -> Unit = {}, alreadyRunning: Boolean = false): Int {
        if (!alreadyRunning) running.set(true)
        var done = 0
        try {
            while (running.get()) {
                val ids = pendingIds(batchSize)
                if (ids.isEmpty()) break
                for (id in ids) {
                    if (!running.get()) return done
                    processOne(id)
                    done++
                    onTitleDone(id)
                }
                log.info("Backdrop tint backfill: {} titles so far, {} pending", done, pendingCount())
                if (running.get() && ids.size == batchSize) clock.sleep(pauseBetweenBatches)
            }
        } finally {
            if (!alreadyRunning) running.set(false)
        }
        return done
    }

    internal fun pendingIds(limit: Int): List<Long> =
        JdbiOrm.jdbi().withHandle<List<Long>, Exception> { handle ->
            handle.createQuery(
                "SELECT id FROM title WHERE backdrop_path IS NOT NULL AND backdrop_tint IS NULL ORDER BY id LIMIT :n")
                .bind("n", limit)
                .mapTo(Long::class.java)
                .list()
        }

    internal fun pendingCount(): Long =
        JdbiOrm.jdbi().withHandle<Long, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM title WHERE backdrop_path IS NOT NULL AND backdrop_tint IS NULL")
                .mapTo(Long::class.java)
                .one()
        }

    private fun processOne(id: Long) {
        val title = Title.findById(id) ?: return
        if (title.backdrop_tint != null) return
        val file = resolveBackdrop(title)
        val tint = when {
            file == null -> {
                log.warn("Backdrop tint backfill: title {} has no cached backdrop on disk; using {}", id, BackdropTint.FALLBACK)
                BackdropTint.FALLBACK
            }
            else -> {
                val computed = BackdropTint.fromFile(file)
                if (computed == null) {
                    log.warn("Backdrop tint backfill: backdrop for title {} did not decode ({}); using {}", id, file, BackdropTint.FALLBACK)
                    BackdropTint.FALLBACK
                } else {
                    writeSidecarTint(file, title, computed)
                    computed
                }
            }
        }
        claim(id, tint)
    }

    /** Records [tint] in the backdrop's sidecar, creating the sidecar if the file never had one. */
    private fun writeSidecarTint(file: Path, title: Title, tint: String) {
        val existing = MetadataWriter.readSidecar(file)
        val meta = existing?.copy(tint = tint) ?: ImageMetadata.internet(
            provider = InternetImageStore.Provider.TMDB_BACKDROP,
            cacheKey = file.fileName.toString().substringBeforeLast('.'),
            upstreamUrl = null,
            subjectType = "title",
            subjectId = title.id,
            contentType = "image/jpeg",
            fetchedAt = null,
            tint = tint,
        )
        MetadataWriter.writeSidecar(file, meta)
    }

    /** Claims the row; a no-op if something else set the tint first. */
    private fun claim(id: Long, tint: String): Int =
        JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("UPDATE title SET backdrop_tint = :tint WHERE id = :id AND backdrop_tint IS NULL")
                .bind("tint", tint)
                .bind("id", id)
                .execute()
        }

    companion object {
        const val DEFAULT_BATCH_SIZE = 50
        val DEFAULT_PAUSE: Duration = 2.seconds
    }
}
