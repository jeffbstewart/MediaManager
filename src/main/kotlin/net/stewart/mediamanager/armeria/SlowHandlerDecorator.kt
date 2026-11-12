package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import org.slf4j.LoggerFactory

/**
 * Decorator that detects handlers blocking the Netty event loop.
 *
 * Measures the synchronous time spent in `delegate.serve()`. For @Blocking handlers,
 * serve() returns immediately (dispatches to the blocking executor), so the synchronous
 * time is <1ms. For handlers that accidentally block the event loop (missing @Blocking,
 * CompletableFuture.join(), synchronous I/O), serve() takes a long time — and that's
 * exactly what we want to catch.
 *
 * This eliminates false positives from long-running streaming responses (video, HLS)
 * which are legitimately slow but don't block the event loop.
 */
class SlowHandlerDecorator(
    private val thresholdMs: Long = 50,
    /**
     * Time source for the synchronous-serve probe. Defaults to the real
     * monotonic clock; tests inject a synthetic counter so they can
     * model "this delegate took 100 ms" without actually sleeping.
     */
    private val nanoTime: () -> Long = System::nanoTime,
) : DecoratingHttpServiceFunction {

    private val log = LoggerFactory.getLogger(SlowHandlerDecorator::class.java)

    override fun serve(delegate: HttpService, ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
        val startNanos = nanoTime()
        val response = delegate.serve(ctx, req)
        val serveMs = (nanoTime() - startNanos) / 1_000_000

        if (serveMs > thresholdMs) {
            val thread = Thread.currentThread().name
            val path = ctx.path()
            val method = req.method().name
            log.warn("EVENT LOOP BLOCKED: {} {} held event loop for {}ms on thread {}",
                method, path, serveMs, thread)
        }

        return response
    }
}
