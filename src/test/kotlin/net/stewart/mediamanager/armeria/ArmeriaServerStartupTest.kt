package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.annotation.Get
import org.junit.Test

/**
 * Validates that Armeria's annotatedService() accepts our decorator wiring.
 * Reproduces the startup crash: "is neither an exception handler nor a converter"
 * caused by passing a Function to the varargs overload.
 */
class ArmeriaServerStartupTest {

    /** Minimal service for testing decorator wiring. */
    class StubService {
        @Get("/stub")
        fun stub(): HttpResponse = HttpResponse.of(HttpStatus.OK)
    }

    @Test
    fun annotatedServiceWithAuthDecoratorBuilderApi() {
        // Use the builder API: .annotatedService().decorator().build()
        // This avoids the varargs ambiguity that causes the "neither an exception
        // handler nor a converter" crash.
        val server = Server.builder()
            .http(0)
            .annotatedService().decorator(ArmeriaAuthDecorator()).build(StubService())
            .build()

        server.start().join()
        server.stop().join()
    }
}
