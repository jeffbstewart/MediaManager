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
 * ### Current rollout state
 *
 * We ship initially in **Report-Only** mode — if a still-direct external
 * load slipped past the Phase-3 migration, the browser reports it to
 * `/csp-report` (logged by [CspReportHttpService] under `csp.violation`
 * and shipped to Binnacle) without actually blocking the request. Flip
 * [ENFORCING] to `true` after one browsing cycle with an empty report
 * stream.
 *
 * ### Directive rationale
 *
 * - `default-src 'self'` — baseline deny for anything not below.
 * - `img-src 'self' data: blob:` — our origin only; `data:` for inline
 *   SVG icons Angular Material emits; `blob:` for dynamically-created
 *   images (book cover URLs constructed in the reader etc.).
 * - `script-src 'self'` — no inline / remote JS. epub.js + JSZip are
 *   vendored locally.
 * - `style-src 'self' 'unsafe-inline' blob:` — Angular emits component-scoped
 *   styles inline at runtime. Tightening to nonces is a separate
 *   `ngCspNonce` refactor; `'unsafe-inline'` on styles is a widely-
 *   accepted pragmatic tradeoff for Angular apps.
 *   `blob:` is required for the EPUB reader: epub.js renders book pages
 *   inside srcdoc iframes and attaches book-stylesheet contents as Blob
 *   URLs. Without `blob:` the reader falls back to unstyled text.
 * - `font-src 'self'` — Roboto + Material Icons vendored locally.
 * - `connect-src 'self'` — XHR / fetch / WebSocket to our origin only.
 * - `frame-src 'self'` — the PDF reader iframes `/ebook/{id}`.
 * - `object-src 'none'` — no Flash / plugins, no `<object>` escape hatch.
 * - `base-uri 'self'` — `<base>` tag can't be injected to a third party.
 * - `form-action 'self'` — form submissions can't go off-origin.
 * - `frame-ancestors 'none'` — we can't be iframed anywhere (replaces
 *   the older `X-Frame-Options: DENY`).
 * - `report-uri /csp-report` (legacy) + `report-to csp-endpoint`
 *   (modern) — plus the matching [Reporting-Endpoints] response
 *   header so Chrome's Reporting API finds its target.
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

        /** Reporting API endpoint binding — consumed by `report-to csp-endpoint` below. */
        private const val REPORTING_ENDPOINTS = "csp-endpoint=\"/csp-report\""

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

        /**
         * Policy body. Built once as a `String` so we don't reallocate on
         * every response. Keep directives alphabetical after `default-src`
         * so diffs are readable.
         */
        private val CSP_VALUE: String = listOf(
            "default-src 'self'",
            "base-uri 'self'",
            "connect-src 'self'",
            "font-src 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "frame-src 'self'",
            "img-src 'self' data: blob:",
            "object-src 'none'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline' blob:",
            "report-uri /csp-report",
            "report-to csp-endpoint"
        ).joinToString("; ")
    }
}
