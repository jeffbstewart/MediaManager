package net.stewart.mediamanager.grpc

import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.slf4j.LoggerFactory

/**
 * Standalone gRPC server running on its own port with native HTTP/2.
 *
 * Completely separate from the Jetty/Vaadin servlet container — no servlet
 * async issues, no filter chain, proper HTTP/2 from the ground up.
 * HAProxy or a reverse proxy terminates TLS and forwards to this port.
 */
object GrpcServer {

    private val log = LoggerFactory.getLogger(GrpcServer::class.java)
    private var server: io.grpc.Server? = null

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

        val builder = NettyServerBuilder.forPort(port)
            .maxInboundMessageSize(16 * 1024 * 1024)  // 16MB (ownership photos)
            .maxInboundMetadataSize(8 * 1024)     // 8KB

        for (service in services) {
            builder.addService(
                ServerInterceptors.intercept(service, loggingInterceptor, authInterceptor)
            )
        }

        server = builder.build().start()
        log.info("gRPC server started on port {} with {} services (plaintext h2c)", port, services.size)
    }

    fun stop() {
        server?.shutdown()
        log.info("gRPC server stopped")
    }
}
