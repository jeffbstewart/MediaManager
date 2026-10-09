package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import net.stewart.mediamanager.service.UriCredentialRedactor
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
            val uri = logSafeUri(path, ctx.query())

            val status = requestLog.responseHeaders().status().code()
            val elapsedMs = Duration.ofNanos(requestLog.responseDurationNanos()).toMillis()
            val responseSize = requestLog.responseLength()
            val clientIp = ctx.clientAddress().hostAddress
            val username = ArmeriaAuthDecorator.getUser(ctx)?.username ?: "-"

            // Skip gRPC — those are logged with proper granularity by
            // LoggingInterceptor at grpc.access. Our services live under
            // /mediamanager.<Service>/<method>; reflection lives under
            // /grpc.reflection.*; armeria's own healthcheck under
            // /armeria.*. All three would otherwise be double-logged.
            if (path.startsWith("/grpc.") || path.startsWith("/armeria.")
                || path.startsWith("/mediamanager.")) {
                return@thenAccept
            }

            // Skip boring successful health/metrics polls — HAProxy and
            // Prometheus hit these on a short interval. Still log non-200
            // responses so outages remain visible.
            if (status == 200 && method == "GET" && (path == "/health" || path == "/metrics")) {
                return@thenAccept
            }

            log.info("{} {} {} {}ms {}B {} user={}",
                method, uri, status, elapsedMs, responseSize, clientIp, username)
        }

        return delegate.serve(ctx, req)
    }

    companion object {
        /**
         * Path prefixes whose final segment is a bearer credential (a
         * signed token in the URL path rather than the query string).
         */
        private val tokenPathPrefixes = listOf("/public/album-art/")

        /**
         * Builds the request target for the access log with credentials
         * removed: sensitive query parameter values (`?key=` device tokens,
         * `token`, `code`, ...) and path-embedded tokens become `REDACTED`.
         * Access logs are forwarded off-box, so they must never carry a
         * replayable credential.
         */
        internal fun logSafeUri(path: String, query: String?): String {
            var safePath = path
            for (prefix in tokenPathPrefixes) {
                if (path.startsWith(prefix) && path.length > prefix.length) {
                    safePath = prefix + UriCredentialRedactor.REDACTED
                    break
                }
            }
            return if (query != null) "$safePath?${UriCredentialRedactor.redactQuery(query)}" else safePath
        }
    }
}
