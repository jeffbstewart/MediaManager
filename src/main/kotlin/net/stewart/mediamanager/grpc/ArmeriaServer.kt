package net.stewart.mediamanager.grpc

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.file.FileService
import com.linecorp.armeria.server.file.HttpFile
import com.linecorp.armeria.server.grpc.GrpcService
import io.grpc.ServerInterceptors
import java.nio.file.Path
import net.stewart.mediamanager.armeria.AppLogHttpService
import net.stewart.mediamanager.armeria.ArmeriaAuthDecorator
import net.stewart.mediamanager.armeria.BackdropHttpService
import net.stewart.mediamanager.armeria.CollectionPosterHttpService
import net.stewart.mediamanager.armeria.HeadshotHttpService
import net.stewart.mediamanager.armeria.HealthHttpService
import net.stewart.mediamanager.armeria.LocalImageHttpService
import net.stewart.mediamanager.armeria.MetricsHttpService
import net.stewart.mediamanager.armeria.OwnershipPhotoHttpService
import net.stewart.mediamanager.armeria.PairingHttpService
import net.stewart.mediamanager.armeria.PlaybackProgressHttpService
import net.stewart.mediamanager.armeria.PosterHttpService
import net.stewart.mediamanager.armeria.RequestLogHttpService
import net.stewart.mediamanager.armeria.AuthRestService
import net.stewart.mediamanager.armeria.RokuFeedHttpService
import net.stewart.mediamanager.armeria.VideoStreamHttpService
import net.stewart.mediamanager.armeria.CameraStreamHttpService
import net.stewart.mediamanager.armeria.LiveTvStreamHttpService
import org.slf4j.LoggerFactory

/**
 * Armeria-based server hosting gRPC services and HTTP endpoints.
 *
 * Listens on two ports:
 * - **Main port** (default 9090): gRPC (HTTP/2) + /health for HAProxy checks.
 *   Will later also serve REST API, static files, and ported servlets.
 * - **Internal port** (default 8081): /health, /metrics, /admin/logs, /admin/requests.
 *   LAN-only, not port-forwarded through the router.
 */
object ArmeriaServer {

    private val log = LoggerFactory.getLogger(ArmeriaServer::class.java)
    private var server: Server? = null

    fun start(port: Int, internalPort: Int = 0) {
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

        // Buddy service: own auth (in-stream API key for bidi, metadata for unary).
        // Not behind AuthInterceptor — uses BuddyAuthInterceptor for context setup only.
        val buddyAuthInterceptor = BuddyAuthInterceptor()
        grpcServiceBuilder.addService(
            ServerInterceptors.intercept(BuddyGrpcService(), loggingInterceptor, buddyAuthInterceptor)
        )

        val grpcService = grpcServiceBuilder.build()
        val authDecorator = ArmeriaAuthDecorator()

        // Use the builder API (.annotatedService().decorator().build()) to avoid
        // varargs ambiguity with annotatedService(Object, Object...) which interprets
        // Function/DecoratingHttpServiceFunction as exception handlers instead of decorators.
        val sb = Server.builder()
            .http(port)
            .service(grpcService)
            // Health check on the main port for HAProxy (no auth)
            .annotatedService(HealthHttpService())

        // Authenticated image/data endpoints
        sb.annotatedService().decorator(authDecorator).build(PosterHttpService())
        sb.annotatedService().decorator(authDecorator).build(HeadshotHttpService())
        sb.annotatedService().decorator(authDecorator).build(BackdropHttpService())
        sb.annotatedService().decorator(authDecorator).build(CollectionPosterHttpService())
        sb.annotatedService().decorator(authDecorator).build(LocalImageHttpService())
        sb.annotatedService().decorator(authDecorator).build(OwnershipPhotoHttpService())
        sb.annotatedService().decorator(authDecorator).build(PlaybackProgressHttpService())

        // Streaming endpoints (video, camera, live TV)
        sb.annotatedService().decorator(authDecorator).build(VideoStreamHttpService())
        sb.annotatedService().decorator(authDecorator).build(CameraStreamHttpService())
        sb.annotatedService().decorator(authDecorator).build(LiveTvStreamHttpService())

        // REST API auth (unauthenticated — own proxy validation + rate limiting)
        sb.annotatedService(AuthRestService())

        // Roku endpoints (own auth: device token + cookie fallback, no ArmeriaAuthDecorator)
        sb.annotatedService(RokuFeedHttpService())
        // Device pairing (unauthenticated, has its own rate limiting)
        sb.annotatedService(PairingHttpService())

        // Angular SPA at /app/ with client-side routing fallback.
        // In production, Angular build output is at spa/ (copied by Dockerfile).
        // In development, this directory won't exist — use ng serve instead.
        val spaDir = Path.of("spa")
        if (spaDir.toFile().exists()) {
            val fileService = FileService.of(spaDir)
            val indexHtml = HttpFile.of(spaDir.resolve("index.html"))
            val indexService = indexHtml.asService()

            // Serve static files if they exist, otherwise fall back to index.html
            // so Angular's client-side router can handle the route.
            sb.serviceUnder("/app/") { ctx, req ->
                val mappedPath = ctx.mappedPath().removePrefix("/")
                val file = spaDir.resolve(mappedPath)
                if (mappedPath.isNotEmpty() && file.toFile().isFile) {
                    fileService.serve(ctx, req)
                } else {
                    indexService.serve(ctx, req)
                }
            }
            log.info("Angular SPA enabled at /app/ from {}", spaDir.toAbsolutePath())
        } else {
            log.info("Angular SPA directory not found ({}), SPA routes disabled", spaDir.toAbsolutePath())
        }

        // Root redirect: / -> /app/
        sb.service("/") { _, _ -> HttpResponse.ofRedirect("/app/") }

        if (internalPort > 0) {
            sb.http(internalPort)

            // Restrict monitoring endpoints to the internal port only
            val internalOnly = internalOnlyDecorator(internalPort)
            sb.annotatedService().decorator(internalOnly).build(MetricsHttpService())
            sb.annotatedService().decorator(internalOnly).build(AppLogHttpService())
            sb.annotatedService().decorator(internalOnly).build(RequestLogHttpService())
        }

        server = sb.build()
        server!!.start().join()

        if (internalPort > 0) {
            log.info("Armeria server started on port {} (gRPC + HTTP) and {} (internal monitoring)",
                port, internalPort)
        } else {
            log.info("Armeria gRPC server started on port {} (h2c)", port)
        }
    }

    fun stop() {
        server?.stop()?.join()
        log.info("Armeria server stopped")
    }

    /**
     * Returns a decorator that rejects requests not arriving on the specified port.
     * Used to keep /metrics, /admin/logs, /admin/requests off the internet-facing port.
     */
    private fun internalOnlyDecorator(allowedPort: Int) = DecoratingHttpServiceFunction { delegate, ctx, req ->
        if (ctx.localAddress().port == allowedPort) {
            delegate.serve(ctx, req)
        } else {
            HttpResponse.of(HttpStatus.NOT_FOUND)
        }
    }
}
