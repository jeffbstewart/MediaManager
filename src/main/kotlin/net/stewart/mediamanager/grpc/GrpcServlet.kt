package net.stewart.mediamanager.grpc

import io.grpc.ServerInterceptors
import io.grpc.servlet.jakarta.ServletServerBuilder
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory

/**
 * Registers all gRPC services on /grpc, sharing port 8080 with Vaadin and HTTP servlets.
 *
 * Uses [io.grpc.servlet.jakarta.ServletAdapter] under the hood via [ServletServerBuilder],
 * which handles gRPC-Web transport over HTTP/1.1 — no HTTP/2 or TLS required.
 *
 * Security limits:
 * - Max inbound message: 64KB (matches REST API body limit, protects against decompression bombs)
 * - Max inbound metadata: 8KB
 */
@WebServlet(urlPatterns = ["/grpc/*"], asyncSupported = true)
class MediaManagerGrpcServlet : HttpServlet() {

    private val log = LoggerFactory.getLogger(MediaManagerGrpcServlet::class.java)
    private val delegate by lazy { buildDelegate() }

    private fun buildDelegate(): io.grpc.servlet.jakarta.ServletAdapter {
        // Interceptor order: logging is outermost (sees final status including auth failures),
        // auth is inner (runs after logging starts, before service method).
        val loggingInterceptor = LoggingInterceptor()
        val authInterceptor = AuthInterceptor()

        val builder = ServletServerBuilder()
            .maxInboundMessageSize(64 * 1024)   // 64KB — protects unauthenticated endpoints
            .maxInboundMetadataSize(8 * 1024)    // 8KB

        // Register all 9 services with both interceptors
        val services = listOf(
            AuthGrpcService(),
            InfoGrpcService(),
            CatalogGrpcService(),
            PlaybackGrpcService(),
            DownloadGrpcService(),
            WishListGrpcService(),
            ProfileGrpcService(),
            LiveGrpcService(),
            AdminGrpcService()
        )

        for (service in services) {
            builder.addService(
                ServerInterceptors.intercept(service, loggingInterceptor, authInterceptor)
            )
        }

        val adapter = builder.buildServletAdapter()
        log.info("gRPC servlet initialized with {} services at /grpc/*", services.size)
        return adapter
    }

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        delegate.doGet(req, resp)
    }

    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        delegate.doPost(req, resp)
    }

    override fun destroy() {
        delegate.destroy()
        super.destroy()
    }
}
