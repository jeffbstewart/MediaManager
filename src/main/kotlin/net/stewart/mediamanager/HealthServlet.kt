package net.stewart.mediamanager

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.annotation.WebServlet
import net.stewart.mediamanager.service.MetricsRegistry

/**
 * Health check endpoint for Docker/container orchestration.
 * Returns 200 with JSON body when the app and database are healthy.
 * Returns 503 if the database connection is broken.
 *
 * Available on both the main port (8080, for HAProxy health checks) and
 * the internal-only port (8081, mounted manually in [startInternalServer]).
 */
@WebServlet(urlPatterns = ["/health"], asyncSupported = true)
class HealthServlet : HttpServlet() {

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "application/json"

        val dbOk = try {
            JdbiOrm.getDataSource().connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SELECT 1")
                }
            }
            true
        } catch (_: Exception) {
            false
        }

        if (dbOk) {
            resp.status = HttpServletResponse.SC_OK
            resp.writer.write("""{"status":"UP","db":"UP"}""")
            MetricsRegistry.countHttpResponse("health", 200)
        } else {
            resp.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            resp.writer.write("""{"status":"DOWN","db":"DOWN"}""")
            MetricsRegistry.countHttpResponse("health", 503)
        }
    }
}
