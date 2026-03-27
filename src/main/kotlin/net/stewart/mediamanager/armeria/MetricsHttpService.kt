package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.annotation.Get
import net.stewart.mediamanager.service.MetricsRegistry

class MetricsHttpService {

    @Get("/metrics")
    fun metrics(): HttpResponse {
        val body = MetricsRegistry.registry.scrape()
        MetricsRegistry.countHttpResponse("metrics", 200)
        return HttpResponse.of(
            HttpStatus.OK,
            MediaType.parse("text/plain; version=0.0.4; charset=utf-8"),
            body
        )
    }
}
