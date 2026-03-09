package net.stewart.mediamanager

import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.service.LocalImageService
import net.stewart.mediamanager.service.MetricsRegistry
import java.nio.file.Files

/**
 * Serves locally-stored images (hero frames, uploads).
 * URL pattern: /local-images/{uuid}
 */
@WebServlet(urlPatterns = ["/local-images/*"])
class LocalImageServlet : HttpServlet() {

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val uuid = req.pathInfo?.removePrefix("/")?.trim()
        if (uuid.isNullOrBlank() || uuid.contains("/")) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST)
            MetricsRegistry.countHttpResponse("local_image", 400)
            return
        }

        val contentType = LocalImageService.getContentType(uuid)
        val file = LocalImageService.getFile(uuid)
        if (contentType == null || file == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("local_image", 404)
            return
        }

        resp.contentType = contentType
        resp.setHeader("Cache-Control", "max-age=31536000, immutable")
        resp.setContentLengthLong(file.length())
        Files.copy(file.toPath(), resp.outputStream)
        MetricsRegistry.countHttpResponse("local_image", 200)
    }
}
