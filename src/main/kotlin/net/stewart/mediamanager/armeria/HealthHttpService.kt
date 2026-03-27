package net.stewart.mediamanager.armeria

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.annotation.Get
import net.stewart.mediamanager.service.MetricsRegistry

class HealthHttpService {

    @Get("/health")
    fun health(): HttpResponse {
        val dbOk = try {
            JdbiOrm.getDataSource().connection.use { conn ->
                conn.createStatement().use { it.execute("SELECT 1") }
            }
            true
        } catch (_: Exception) {
            false
        }

        val (status, body) = if (dbOk) {
            HttpStatus.OK to """{"status":"UP","db":"UP"}"""
        } else {
            HttpStatus.SERVICE_UNAVAILABLE to """{"status":"DOWN","db":"DOWN"}"""
        }
        MetricsRegistry.countHttpResponse("health", status.code())
        return HttpResponse.of(status, MediaType.JSON_UTF_8, body)
    }
}
