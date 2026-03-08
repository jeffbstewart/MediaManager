package net.stewart.mediamanager

import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.PosterCacheService
import java.nio.file.Files

/**
 * Serves cached poster images. URL pattern: /posters/{sizeSegment}/{titleId}
 *
 * On first request for a title, fetches the poster from TMDB CDN and caches it to disk.
 * Subsequent requests serve directly from the local cache. If the fetch fails, redirects
 * to the TMDB CDN URL as a fallback.
 */
@WebServlet(urlPatterns = ["/posters/*"])
class PosterServlet : HttpServlet() {

    private fun isValidTmdbPath(path: String): Boolean =
        path.startsWith("/") && !path.startsWith("//")
            && path.matches(Regex("^/[a-zA-Z0-9/_.-]+$"))

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        // Parse /posters/{size}/{titleId}
        val parts = req.pathInfo?.removePrefix("/")?.split("/") ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST)
            MetricsRegistry.countHttpResponse("poster", 400)
            return
        }
        if (parts.size != 2) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST)
            MetricsRegistry.countHttpResponse("poster", 400)
            return
        }

        val size = PosterSize.entries.firstOrNull { it.pathSegment == parts[0] } ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST)
            MetricsRegistry.countHttpResponse("poster", 400)
            return
        }
        val titleId = parts[1].toLongOrNull() ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST)
            MetricsRegistry.countHttpResponse("poster", 400)
            return
        }

        val title = Title.findById(titleId)
        if (title == null || title.poster_path == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("poster", 404)
            return
        }

        // Rating enforcement: check user ceiling against title rating
        val user = req.getAttribute(AuthFilter.USER_ATTRIBUTE) as? AppUser
        if (user != null && !user.canSeeRating(title.content_rating)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Content restricted by parental controls")
            MetricsRegistry.countHttpResponse("poster", 403)
            return
        }

        val cached = PosterCacheService.cacheAndServe(title, size)
        if (cached != null && Files.exists(cached)) {
            resp.contentType = "image/jpeg"
            resp.setHeader("Cache-Control", "max-age=31536000, immutable")
            resp.setContentLengthLong(Files.size(cached))
            Files.copy(cached, resp.outputStream)
            MetricsRegistry.countHttpResponse("poster", 200)
        } else {
            // Fallback: redirect to TMDB CDN (validate path to prevent open redirect)
            val path = title.poster_path!!
            if (!isValidTmdbPath(path)) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                MetricsRegistry.countHttpResponse("poster", 404)
                return
            }
            resp.sendRedirect("https://image.tmdb.org/t/p/${size.pathSegment}$path")
            MetricsRegistry.countHttpResponse("poster", 302)
        }
    }
}
