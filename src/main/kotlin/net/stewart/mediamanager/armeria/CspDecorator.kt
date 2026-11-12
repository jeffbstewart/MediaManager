package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext

/**
 * Attaches a Content-Security-Policy header to every Armeria HTTP
 * response so the client can't regress to external image / script /
 * font / XHR loads.
 *
 * ### The policy lives in one file
 *
 * The directives, with the reason for each, are in the classpath
 * resource [POLICY_RESOURCE] (`src/main/resources/csp-policy.txt`), one
 * per line. This class joins them into the header value at startup.
 * The Playwright suite reads the same file and serves every page it
 * tests with the same header, so a directive change lands on the NAS
 * and in the suite together, and a load the policy blocks fails a test
 * before it reaches production.
 *
 * ### Reporting
 *
 * The policy names `/csp-report` through both `report-uri` and
 * `report-to`, and this class adds the matching `Reporting-Endpoints`
 * header so Chrome's Reporting API finds the same path.
 * [CspReportHttpService] logs each report at ERROR under
 * `csp.violation`, which Binnacle picks up.
 *
 * [ENFORCING] has been `true` since the policy's bake-in period ended;
 * the Report-Only switch stays for the next directive that needs one.
 */
class CspDecorator : DecoratingHttpServiceFunction {

    override fun serve(delegate: HttpService, ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
        val response = delegate.serve(ctx, req)
        return response.mapHeaders { headers ->
            val builder = headers.toBuilder()
            if (ENFORCING) {
                builder.add("Content-Security-Policy", CSP_VALUE)
            } else {
                builder.add("Content-Security-Policy-Report-Only", CSP_VALUE)
            }
            builder.add("Reporting-Endpoints", REPORTING_ENDPOINTS)

            // Companion security headers — same scope as CSP. Each is
            // idempotent and no upstream service in this app emits
            // them, so plain add() is fine.
            builder.add("Strict-Transport-Security", HSTS)
            builder.add("X-Content-Type-Options", "nosniff")
            builder.add("Referrer-Policy", "strict-origin-when-cross-origin")
            builder.add("Permissions-Policy", PERMISSIONS_POLICY)
            builder.add("Cross-Origin-Opener-Policy", "same-origin")

            // Fingerprint suppression. Armeria's default `Server`
            // header is `Armeria/<version>` which lets attackers
            // shortcut CVE matching against our specific framework
            // version. Replace with a generic value so the header is
            // still present (some upstream proxies log its absence
            // as suspicious) but reveals no version. X-Powered-By
            // and the legacy ASP.NET banners aren't emitted by this
            // stack today, but stripping them defensively costs
            // nothing and keeps the response clean if a future
            // dependency starts adding them.
            builder.set("server", "MediaManager")
            builder.remove("x-powered-by")
            builder.remove("x-aspnet-version")
            builder.remove("x-aspnetmvc-version")
            builder.remove("x-generator")
            builder.build()
        }
    }

    companion object {
        /** Flip to `true` to enforce. Leave at `false` during the bake-in period. */
        private const val ENFORCING = true


        /**
         * 2-year HSTS, includes subdomains, preload-eligible. We're
         * exclusively HTTPS at the HAProxy edge — committing
         * indefinitely costs nothing.
         */
        private const val HSTS = "max-age=63072000; includeSubDomains; preload"

        /**
         * Disable browser APIs we don't use; explicitly allow `camera`
         * because /admin/document-ownership and /admin/cameras need
         * getUserMedia for in-browser barcode scanning + capture.
         * `interest-cohort=()` and `browsing-topics=()` opt out of
         * FLoC / Topics behavioural-cohort APIs that would otherwise
         * default-on.
         */
        private val PERMISSIONS_POLICY: String = listOf(
            "camera=(self)",
            "microphone=()",
            "geolocation=()",
            "gyroscope=()",
            "magnetometer=()",
            "accelerometer=()",
            "interest-cohort=()",
            "browsing-topics=()",
            "payment=()",
            "usb=()",
            "midi=()"
        ).joinToString(", ")

        /** The policy file: one directive per line, `#` comments, read by this class and by the Playwright suite. */
        internal const val POLICY_RESOURCE = "/csp-policy.txt"

        /**
         * Policy body, built once from [POLICY_RESOURCE] so no response
         * reallocates it. A missing file is a packaging error, so it fails
         * at class load rather than serving pages with no policy.
         */
        private val DIRECTIVES: List<String> = loadPolicy()
        private val CSP_VALUE: String = DIRECTIVES.joinToString("; ")

        /**
         * `Reporting-Endpoints` binds the group the policy's `report-to`
         * names to the URL its `report-uri` names, so Chrome's Reporting
         * API posts to the same path as every other browser. Derived from
         * the policy so the path is written once.
         */
        private val REPORTING_ENDPOINTS: String = run {
            val group = directiveValue("report-to")
            val uri = directiveValue("report-uri")
            "$group=\"$uri\""
        }

        private fun directiveValue(name: String): String =
            DIRECTIVES.firstOrNull { it.startsWith("$name ") }?.removePrefix("$name ")?.trim()
                ?: error("CSP policy resource $POLICY_RESOURCE has no '$name' directive")

        private fun loadPolicy(): List<String> {
            val stream = CspDecorator::class.java.getResourceAsStream(POLICY_RESOURCE)
                ?: error("CSP policy resource $POLICY_RESOURCE is missing from the classpath")
            val directives = stream.bufferedReader().use { it.readLines() }
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            check(directives.isNotEmpty()) { "CSP policy resource $POLICY_RESOURCE has no directives" }
            return directives
        }
    }
}
