package net.stewart.mediamanager

import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.service.HeadshotCacheService
import net.stewart.mediamanager.service.MetricsRegistry
import java.nio.file.Files

/**
 * Serves cached headshot images. URL pattern: /headshots/{castMemberId}
 *
 * On first request for a cast member, fetches the headshot from TMDB CDN and caches
 * it to disk. Subsequent requests serve directly from the local cache. If the fetch
 * fails, redirects to the TMDB CDN URL as a fallback.
 */
@WebServlet(urlPatterns = ["/headshots/*"])
class HeadshotServlet : HttpServlet() {

    private fun isValidTmdbPath(path: String): Boolean =
        path.startsWith("/") && !path.startsWith("//")
            && path.matches(Regex("^/[a-zA-Z0-9/_.-]+$"))

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val castMemberId = req.pathInfo?.removePrefix("/")?.toLongOrNull() ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST)
            MetricsRegistry.countHttpResponse("headshot", 400)
            return
        }

        val castMember = CastMember.findById(castMemberId)
        if (castMember == null || castMember.profile_path == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("headshot", 404)
            return
        }

        val cached = HeadshotCacheService.cacheAndServe(castMember)
        if (cached != null && Files.exists(cached)) {
            resp.contentType = "image/jpeg"
            resp.setHeader("Cache-Control", "max-age=31536000, immutable")
            resp.setContentLengthLong(Files.size(cached))
            Files.copy(cached, resp.outputStream)
            MetricsRegistry.countHttpResponse("headshot", 200)
        } else {
            // Fallback: redirect to TMDB CDN (validate path to prevent open redirect)
            val path = castMember.profile_path!!
            if (!isValidTmdbPath(path)) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                MetricsRegistry.countHttpResponse("headshot", 404)
                return
            }
            resp.sendRedirect("https://image.tmdb.org/t/p/w185$path")
            MetricsRegistry.countHttpResponse("headshot", 302)
        }
    }
}
