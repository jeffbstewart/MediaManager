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
 * - `style-src 'self' 'unsafe-inline'` — Angular emits component-scoped
 *   styles inline at runtime. Tightening to nonces is a separate
 *   `ngCspNonce` refactor; `'unsafe-inline'` on styles is a widely-
 *   accepted pragmatic tradeoff for Angular apps.
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
            builder.build()
        }
    }

    companion object {
        /** Flip to `true` to enforce. Leave at `false` during the bake-in period. */
        private const val ENFORCING = true

        /** Reporting API endpoint binding — consumed by `report-to csp-endpoint` below. */
        private const val REPORTING_ENDPOINTS = "csp-endpoint=\"/csp-report\""

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
            "style-src 'self' 'unsafe-inline'",
            "report-uri /csp-report",
            "report-to csp-endpoint"
        ).joinToString("; ")
    }
}
