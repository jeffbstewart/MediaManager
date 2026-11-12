package net.stewart.mediamanager.demosetup

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded-parallelism helper for the fetcher pipelines. Each fetcher
 * has a list of independent units of work (one row in a TSV); we run
 * them through a fixed-size thread pool so the slow ones (ffmpeg
 * transcoding a 90-minute feature) overlap with the fast ones (a
 * one-track 78rpm download).
 *
 * Why not coroutines: the fetchers are pure I/O + native ffmpeg
 * shellouts. The blocking-thread cost is cheap and dragging in the
 * coroutines runtime for one feature would balloon the dependency
 * surface of a tool that runs once per demo refresh.
 */
internal object Concurrency {

    /**
     * Run [action] over each of [items] with at most [parallelism]
     * workers active at a time. Blocks until every item has finished
     * (success or failure). When any item throws, all in-flight items
     * still run to completion — half-cancelling a fetch / ffmpeg
     * pipeline mid-write leaves a corrupt file the next idempotent
     * pass treats as already-done. After all join, the first thrown
     * error is re-raised with subsequent ones attached as suppressed.
     */
    fun <T> parallel(items: List<T>, parallelism: Int, action: (T) -> Unit) {
        if (items.isEmpty()) return
        val n = parallelism.coerceIn(1, items.size)
        if (n == 1) {
            items.forEach(action)
            return
        }
        val threadCounter = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(n) { r ->
            Thread(r).apply {
                isDaemon = true
                name = "demo-fetch-${threadCounter.incrementAndGet()}"
            }
        }
        try {
            val futures = items.map { item -> pool.submit { action(item) } }
            val errors = mutableListOf<Throwable>()
            for (f in futures) {
                try {
                    f.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    errors += (e.cause ?: e)
                } catch (e: Exception) {
                    errors += e
                }
            }
            if (errors.isNotEmpty()) {
                val first = errors.first()
                errors.drop(1).forEach { first.addSuppressed(it) }
                throw first
            }
        } finally {
            pool.shutdown()
        }
    }
}

/**
 * Print a line prefixed with [tag]. Useful when parallel workers
 * interleave output — tells the operator which item each line
 * belongs to. Atomic per-line because PrintStream synchronizes
 * each println.
 */
internal fun logTagged(tag: String, msg: String) {
    if (tag.isEmpty()) println(msg) else println("[$tag] $msg")
}
