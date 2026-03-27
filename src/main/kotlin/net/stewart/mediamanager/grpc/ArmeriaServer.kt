package net.stewart.mediamanager.grpc

import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.grpc.GrpcService
import io.grpc.ServerInterceptors
import org.slf4j.LoggerFactory

/**
 * Armeria-based server hosting gRPC services on a single port with native HTTP/2.
 *
 * Replaces the previous standalone Netty gRPC server. Armeria handles HTTP/2 (h2c)
 * natively and will later also serve HTTP endpoints (REST API, static files) on the
 * same port — enabling a single-port architecture behind HAProxy.
 */
object ArmeriaServer {

    private val log = LoggerFactory.getLogger(ArmeriaServer::class.java)
    private var server: Server? = null

    fun start(port: Int) {
        val loggingInterceptor = LoggingInterceptor()
        val authInterceptor = AuthInterceptor()

        val services = listOf(
            AuthGrpcService(),
            InfoGrpcService(),
            CatalogGrpcService(),
            PlaybackGrpcService(),
            DownloadGrpcService(),
            WishListGrpcService(),
            ProfileGrpcService(),
            LiveGrpcService(),
            AdminGrpcService(),
            ImageGrpcService()
        )

        val grpcServiceBuilder = GrpcService.builder()
            .maxRequestMessageLength(16 * 1024 * 1024)  // 16MB (ownership photos)

        for (service in services) {
            grpcServiceBuilder.addService(
                ServerInterceptors.intercept(service, loggingInterceptor, authInterceptor)
            )
        }

        val grpcService = grpcServiceBuilder.build()

        server = Server.builder()
            .http(port)
            .service(grpcService)
            .build()

        server!!.start().join()
        log.info("Armeria gRPC server started on port {} with {} services (h2c)", port, services.size)
    }

    fun stop() {
        server?.stop()?.join()
        log.info("Armeria gRPC server stopped")
    }
}
