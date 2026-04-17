package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Global Armeria decorator that logs every HTTP request on completion
 * to the `http.access` SLF4J logger (INFO). Records flow through
 * BufferingLogger → BinnacleExporter and are queryable in Binnacle by
 * `code.namespace = http.access`.
 *
 * Register globally on the Armeria ServerBuilder via `.decorator()`
 * alongside [SlowHandlerDecorator]. gRPC requests are covered by
 * [net.stewart.mediamanager.grpc.LoggingInterceptor] (`grpc.access`)
 * and intentionally not double-logged here — Armeria's gRPC service
 * runs as a single opaque HTTP endpoint, so the HTTP-level log would
 * just show `POST /grpc.reflection.v1.ServerReflection/...` with no
 * useful detail. The gRPC interceptor has method names, user context,
 * and status codes at the proper granularity.
 */
class AccessLogDecorator : DecoratingHttpServiceFunction {

    private val log = LoggerFactory.getLogger("http.access")

    override fun serve(delegate: HttpService, ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
        ctx.log().whenComplete().thenAccept { requestLog ->
            val method = ctx.method().name
            val path = ctx.path()
            val query = ctx.query()
            val uri = if (query != null) "$path?$query" else path

            val status = requestLog.responseHeaders().status().code()
            val elapsedMs = Duration.ofNanos(requestLog.responseDurationNanos()).toMillis()
            val responseSize = requestLog.responseLength()
            val clientIp = ctx.clientAddress().hostAddress

            // Skip gRPC — those are logged with proper granularity by
            // LoggingInterceptor at grpc.access.
            if (path.startsWith("/grpc.") || path.startsWith("/armeria.")) {
                return@thenAccept
            }

            // Skip boring successful health/metrics polls — HAProxy and
            // Prometheus hit these on a short interval. Still log non-200
            // responses so outages remain visible.
            if (status == 200 && method == "GET" && (path == "/health" || path == "/metrics")) {
                return@thenAccept
            }

            log.info("{} {} {} {}ms {}B {}",
                method, uri, status, elapsedMs, responseSize, clientIp)
        }

        return delegate.serve(ctx, req)
    }
}
