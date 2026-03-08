package net.stewart.mediamanager

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.service.MetricsRegistry

/**
 * Prometheus metrics endpoint. Returns Micrometer metrics in Prometheus
 * exposition format.
 *
 * Served on the internal-only port (not the main app port) so it is
 * not exposed to the internet. Mounted manually in [startInternalServer].
 */
class MetricsServlet : HttpServlet() {

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "text/plain; version=0.0.4; charset=utf-8"
        resp.status = HttpServletResponse.SC_OK
        resp.writer.write(MetricsRegistry.registry.scrape())
        MetricsRegistry.countHttpResponse("metrics", 200)
    }
}
