package net.stewart.mediamanager

import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.OwnershipPhotoService
import java.nio.file.Files

@WebServlet(urlPatterns = ["/ownership-photos/*"])
class OwnershipPhotoServlet : HttpServlet() {

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val uuid = req.pathInfo?.removePrefix("/")?.trim()
        if (uuid.isNullOrBlank() || uuid.contains("/")) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST)
            MetricsRegistry.countHttpResponse("ownership_photo", 400)
            return
        }

        val contentType = OwnershipPhotoService.getContentType(uuid)
        val file = OwnershipPhotoService.getFile(uuid)
        if (contentType == null || file == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            MetricsRegistry.countHttpResponse("ownership_photo", 404)
            return
        }

        resp.contentType = contentType
        resp.setContentLengthLong(file.length())

        val download = req.getParameter("download") == "1"
        if (download) {
            resp.setHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        } else {
            resp.setHeader("Cache-Control", "max-age=31536000, immutable")
        }

        Files.copy(file.toPath(), resp.outputStream)
        MetricsRegistry.countHttpResponse("ownership_photo", 200)
    }
}
