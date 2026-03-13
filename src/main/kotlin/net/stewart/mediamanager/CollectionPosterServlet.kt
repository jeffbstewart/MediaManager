package net.stewart.mediamanager

import com.github.vokorm.findAll
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.service.CollectionPosterCacheService
import net.stewart.mediamanager.service.MetricsRegistry
import java.nio.file.Files

/**
 * Serves cached collection poster images. URL pattern: /collection-posters/{tmdbCollectionId}
 *
 * On first request, fetches the poster from TMDB CDN and caches it to disk.
 * Subsequent requests serve directly from the local cache. Falls back to
 * redirect to TMDB CDN if the fetch fails.
 */
@WebServlet(urlPatterns = ["/collection-posters/*"])
class CollectionPosterServlet : HttpServlet() {

    private fun isValidTmdbPath(path: String): Boolean =
        path.startsWith("/") && !path.startsWith("//")
            && path.matches(Regex("^/[a-zA-Z0-9/_.-]+$"))

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val collectionId = req.pathInfo?.removePrefix("/")?.toIntOrNull() ?: run {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST)
            MetricsRegistry.countHttpResponse("collection-poster", 400)
            return
        }

        val collection = TmdbCollection.findAll().firstOrNull { it.tmdb_collection_id == collectionId }
        if (collection == null || collection.poster_path == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("collection-poster", 404)
            return
        }

        val cached = CollectionPosterCacheService.cacheAndServe(collection)
        if (cached != null && Files.exists(cached)) {
            resp.contentType = "image/jpeg"
            resp.setHeader("Cache-Control", "max-age=31536000, immutable")
            resp.setContentLengthLong(Files.size(cached))
            Files.copy(cached, resp.outputStream)
            MetricsRegistry.countHttpResponse("collection-poster", 200)
        } else {
            val path = collection.poster_path!!
            if (!isValidTmdbPath(path)) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                MetricsRegistry.countHttpResponse("collection-poster", 404)
                return
            }
            resp.sendRedirect("https://image.tmdb.org/t/p/w500$path")
            MetricsRegistry.countHttpResponse("collection-poster", 302)
        }
    }
}
