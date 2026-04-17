package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.logging.RequestLogProperty
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import net.stewart.mediamanager.service.RequestLogBuffer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Duration

/**
 * Global Armeria decorator that logs every HTTP request on completion.
 *
 * Two destinations:
 * - **SLF4J** (`http.access` logger, INFO level) so the record flows
 *   through BufferingLogger → BinnacleExporter and becomes queryable
 *   in Binnacle. Filterable by `code.namespace = http.access`.
 * - **[RequestLogBuffer]** so `/admin/requests` is populated (it was
 *   previously wired but never fed data).
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
            val startTime = requestLog.requestStartTimeMillis()
            val elapsed = Duration.ofNanos(requestLog.responseDurationNanos())
            val elapsedMs = elapsed.toMillis()
            val responseSize = requestLog.responseLength()

            val clientIp = ctx.clientAddress().hostAddress
            val userAgent = req.headers().get("user-agent") ?: "-"

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

            RequestLogBuffer.add(RequestLogBuffer.RequestLogEntry(
                timestamp = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(startTime), ZoneId.systemDefault()),
                clientIp = clientIp,
                username = "-",
                method = method,
                uri = uri,
                protocol = ctx.sessionProtocol().uriText(),
                status = status,
                responseSize = responseSize,
                userAgent = userAgent,
                elapsedMs = elapsedMs
            ))
        }

        return delegate.serve(ctx, req)
    }
}
