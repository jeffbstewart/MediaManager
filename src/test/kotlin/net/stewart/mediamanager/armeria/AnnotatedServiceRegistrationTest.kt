package net.stewart.mediamanager.armeria

import com.linecorp.armeria.server.Server
import net.stewart.mediamanager.grpc.ArmeriaServer
import org.junit.Test

/**
 * Validates that all Armeria annotated services can be registered without errors.
 *
 * Calls the same [ArmeriaServer.registerHttpServices] method that production uses,
 * so any new service with a bad @Param annotation, missing name, or decorator
 * wiring issue is caught here rather than at deploy time.
 *
 * Server.builder().build() resolves all annotated service metadata eagerly —
 * @Param name validation, route conflicts, and decorator compatibility all
 * happen during build().
 */
class AnnotatedServiceRegistrationTest {

    @Test
    fun allAnnotatedServicesRegisterSuccessfully() {
        val sb = Server.builder().http(0)
        ArmeriaServer.registerHttpServices(sb)
        val server = sb.build()
        server.close()
    }
}
