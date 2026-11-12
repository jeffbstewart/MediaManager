package net.stewart.mediamanager.service

import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Periodically submits a no-op task to the Armeria event loop and measures
 * how long it takes to execute. This directly measures event loop queue latency.
 *
 * If the event loop is healthy, queue time is <1ms. If it's overloaded or
 * a handler is blocking the loop, queue time spikes — that's the smoking gun
 * for the stall we've been debugging.
 *
 * The latency is exported as a Prometheus timer (`mm_eventloop_queue_seconds`).
 */
class EventLoopPacemaker(private val server: com.linecorp.armeria.server.Server) {

    private val log = LoggerFactory.getLogger(EventLoopPacemaker::class.java)
    private val queueTimer: Timer = MetricsRegistry.registry.timer("mm_eventloop_queue_seconds")
    @Volatile private var running = true
    private var thread: Thread? = null

    fun start() {
        thread = Thread({
            Thread.sleep(30_000) // Wait for server to warm up
            log.info("Event loop pacemaker started — measuring queue latency every 10s")

            while (running) {
                try {
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break

                // Submit a task to the first event loop and time it
                val eventLoop = server.config().workerGroup().next()
                val submitTime = System.nanoTime()

                eventLoop.execute {
                    val queueNanos = System.nanoTime() - submitTime
                    queueTimer.record(queueNanos, TimeUnit.NANOSECONDS)

                    val queueMs = queueNanos / 1_000_000
                    if (queueMs > 1000) {
                        log.error("PACEMAKER: Event loop queue latency {}ms — loop may be blocked", queueMs)
                    } else if (queueMs > 100) {
                        log.warn("PACEMAKER: Event loop queue latency {}ms — elevated", queueMs)
                    }
                }
            }
        }, "eventloop-pacemaker").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }
}
