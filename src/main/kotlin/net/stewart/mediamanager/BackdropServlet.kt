package net.stewart.mediamanager

import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.BackdropCacheService
import net.stewart.mediamanager.service.MetricsRegistry
import java.nio.file.Files

/**
 * Serves cached backdrop images. URL pattern: /backdrops/{titleId}
 *
 * On first request for a title, fetches the backdrop from TMDB CDN (w1280) and caches
 * it to disk. Subsequent requests serve directly from the local cache. If the fetch
 * fails, redirects to the TMDB CDN URL as a fallback.
 */
@WebServlet(urlPatterns = ["/backdrops/*"])
class BackdropServlet : HttpServlet() {

    private fun isValidTmdbPath(path: String): Boolean =
        path.startsWith("/") && !path.startsWith("//")
            && path.matches(Regex("^/[a-zA-Z0-9/_.-]+$"))

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val titleId = req.pathInfo?.removePrefix("/")?.toLongOrNull() ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST)
            MetricsRegistry.countHttpResponse("backdrop", 400)
            return
        }

        val title = Title.findById(titleId)
        if (title == null || title.backdrop_path == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("backdrop", 404)
            return
        }

        // Rating enforcement: check user ceiling against title rating
        val user = req.getAttribute(AuthFilter.USER_ATTRIBUTE) as? AppUser
        if (user != null && !user.canSeeRating(title.content_rating)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Content restricted by parental controls")
            MetricsRegistry.countHttpResponse("backdrop", 403)
            return
        }

        val cached = BackdropCacheService.cacheAndServe(title)
        if (cached != null && Files.exists(cached)) {
            resp.contentType = "image/jpeg"
            resp.setHeader("Cache-Control", "max-age=31536000, immutable")
            resp.setContentLengthLong(Files.size(cached))
            Files.copy(cached, resp.outputStream)
            MetricsRegistry.countHttpResponse("backdrop", 200)
        } else {
            // Fallback: redirect to TMDB CDN (validate path to prevent open redirect)
            val path = title.backdrop_path!!
            if (!isValidTmdbPath(path)) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                MetricsRegistry.countHttpResponse("backdrop", 404)
                return
            }
            resp.sendRedirect("https://image.tmdb.org/t/p/w1280$path")
            MetricsRegistry.countHttpResponse("backdrop", 302)
        }
    }
}
