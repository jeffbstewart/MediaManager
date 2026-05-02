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
import net.stewart.mediamanager.armeria.AccessLogDecorator
import net.stewart.mediamanager.armeria.ArmeriaAuthDecorator
import net.stewart.mediamanager.armeria.BackdropHttpService
import net.stewart.mediamanager.armeria.CspDecorator
import net.stewart.mediamanager.armeria.CollectionPosterHttpService
import net.stewart.mediamanager.armeria.ArtistHeadshotHttpService
import net.stewart.mediamanager.armeria.ArtistHttpService
import net.stewart.mediamanager.armeria.AudioStreamHttpService
import net.stewart.mediamanager.armeria.AdvancedSearchHttpService
import net.stewart.mediamanager.armeria.PlaylistHttpService
import net.stewart.mediamanager.armeria.RadioHttpService
import net.stewart.mediamanager.armeria.RecommendationHttpService
import net.stewart.mediamanager.armeria.AuthorHeadshotHttpService
import net.stewart.mediamanager.armeria.HeadshotHttpService
import net.stewart.mediamanager.armeria.HealthHttpService
import net.stewart.mediamanager.armeria.LocalImageHttpService
import net.stewart.mediamanager.armeria.MetricsHttpService
import net.stewart.mediamanager.armeria.OwnershipPhotoHttpService
import net.stewart.mediamanager.armeria.PairingHttpService
import net.stewart.mediamanager.armeria.PlaybackProgressHttpService
import net.stewart.mediamanager.armeria.PosterHttpService
import net.stewart.mediamanager.armeria.AuthRestService
import net.stewart.mediamanager.armeria.ActorHttpService
import net.stewart.mediamanager.armeria.AuthorHttpService
import net.stewart.mediamanager.armeria.BookSeriesHttpService
import net.stewart.mediamanager.armeria.CameraListHttpService
import net.stewart.mediamanager.armeria.CameraSettingsHttpService
import net.stewart.mediamanager.armeria.DataQualityHttpService
import net.stewart.mediamanager.armeria.ProblemReportHttpService
import net.stewart.mediamanager.armeria.LegalRestService
import net.stewart.mediamanager.armeria.CollectionHttpService
import net.stewart.mediamanager.armeria.LiveTvListHttpService
import net.stewart.mediamanager.armeria.ProfileHttpService
import net.stewart.mediamanager.armeria.PurchaseWishesHttpService
import net.stewart.mediamanager.armeria.SearchHttpService
import net.stewart.mediamanager.armeria.SettingsHttpService
import net.stewart.mediamanager.armeria.SlowHandlerDecorator
import net.stewart.mediamanager.armeria.UserManagementHttpService
import net.stewart.mediamanager.armeria.ValuationHttpService
import net.stewart.mediamanager.armeria.TagManagementHttpService
import net.stewart.mediamanager.armeria.TranscodeStatusHttpService
import net.stewart.mediamanager.armeria.AddItemHttpService
import net.stewart.mediamanager.armeria.AmazonImportHttpService
import net.stewart.mediamanager.armeria.BacklogHttpService
import net.stewart.mediamanager.armeria.DocumentOwnershipHttpService
import net.stewart.mediamanager.armeria.ExpandHttpService
import net.stewart.mediamanager.armeria.FamilyMemberHttpService
import net.stewart.mediamanager.armeria.LiveTvSettingsHttpService
import net.stewart.mediamanager.armeria.InventoryReportHttpService
import net.stewart.mediamanager.armeria.MediaItemEditHttpService
import net.stewart.mediamanager.armeria.LinkedTranscodesHttpService
import net.stewart.mediamanager.armeria.EbookHttpService
import net.stewart.mediamanager.armeria.ImageProxyHttpService
import net.stewart.mediamanager.armeria.ReadingProgressHttpService
import net.stewart.mediamanager.armeria.TrackDiagnosticHttpService
import net.stewart.mediamanager.armeria.UnmatchedAudioHttpService
import net.stewart.mediamanager.armeria.UnmatchedBookHttpService
import net.stewart.mediamanager.armeria.UnmatchedHttpService
import net.stewart.mediamanager.armeria.WishListHttpService
import net.stewart.mediamanager.armeria.FamilyVideosHttpService
import net.stewart.mediamanager.armeria.TagHttpService
import net.stewart.mediamanager.armeria.HomeFeedHttpService
import net.stewart.mediamanager.armeria.TitleDetailHttpService
import net.stewart.mediamanager.armeria.TitleListHttpService
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
 * - **Internal port** (default 8081): /health, /metrics. LAN-only, not
 *   port-forwarded through the router.
 */
object ArmeriaServer {

    private val log = LoggerFactory.getLogger(ArmeriaServer::class.java)
    private var server: Server? = null

    fun start(port: Int, internalPort: Int = 0): Server {
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
            ImageGrpcService(),
            ObservabilityGrpcService(),
            ArtistGrpcService(),
            RadioGrpcService(),
            RecommendationGrpcService(),
            PlaylistGrpcService()
        )

        val grpcServiceBuilder = GrpcService.builder()
            .maxRequestMessageLength(16 * 1024 * 1024)  // 16MB (ownership photos)
            // Accept every standard gRPC serialization format Armeria
            // ships with — proto-binary for native gRPC clients (iOS,
            // Android TV, Roku), and the gRPC-Web variants for the
            // browser SPA via @connectrpc/connect-web. Same handler
            // code path for both; Armeria picks the codec from the
            // request's Content-Type.
            //
            // We deliberately do NOT enable enableUnframedRequests:
            // browsers go through proper gRPC-Web framing via Connect,
            // and leaving the unframed shim off means a POST without
            // the gRPC framing is rejected outright instead of being
            // routed into the auth path.
            .supportedSerializationFormats(
                com.linecorp.armeria.common.grpc.GrpcSerializationFormats.values()
            )

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

        // Use the builder API (.annotatedService().decorator().build()) to avoid
        // varargs ambiguity with annotatedService(Object, Object...) which interprets
        // Function/DecoratingHttpServiceFunction as exception handlers instead of decorators.
        val meterRegistry = net.stewart.mediamanager.service.MetricsRegistry.registry

        val sb = Server.builder()
            .http(port)
            .meterRegistry(meterRegistry)
        registerGlobalDecorators(sb)
        sb.service(grpcService)

        // All annotated HTTP services run on the blocking executor by default.
        // This prevents any handler from accidentally blocking the Netty event loop
        // with DB queries, file I/O, or CompletableFuture.join() calls.
        // gRPC services are unaffected — they use their own coroutine dispatchers
        // and are registered via .service(grpcService) above.
        registerHttpServices(sb)

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
            sb.annotatedService().decorator(internalOnly).useBlockingTaskExecutor(true).build(MetricsHttpService())
        }

        server = sb.build()
        server!!.start().join()

        if (internalPort > 0) {
            log.info("Armeria server started on port {} (gRPC + HTTP) and {} (internal monitoring)",
                port, internalPort)
        } else {
            log.info("Armeria gRPC server started on port {} (h2c)", port)
        }
        return server!!
    }

    fun stop() {
        server?.stop()?.join()
        log.info("Armeria server stopped")
    }

    /**
     * Attaches the cross-cutting [DecoratingHttpServiceFunction]s every
     * HTTP service should be wrapped in: access logging, event-loop
     * blocking detection, and Content-Security-Policy + companion
     * security headers. Extracted so [DecoratorWiringTest] can call the
     * exact same wiring production uses and prove via real HTTP that
     * each decorator is on the chain.
     */
    fun registerGlobalDecorators(sb: com.linecorp.armeria.server.ServerBuilder) {
        sb.decorator(AccessLogDecorator())
        sb.decorator(SlowHandlerDecorator())
        sb.decorator(CspDecorator())
    }

    /**
     * Registers all HTTP annotated services on the given [ServerBuilder].
     * Extracted so tests can call the same registration path that production uses,
     * catching @Param name resolution and decorator wiring issues at test time.
     */
    fun registerHttpServices(sb: com.linecorp.armeria.server.ServerBuilder) {
        val authDecorator = ArmeriaAuthDecorator()

        // Helper: register an annotated service that always runs on the blocking executor.
        // Every HTTP handler does DB queries or file I/O — none should run on the event loop.
        fun blocking(service: Any) =
            sb.annotatedService().decorator(authDecorator).useBlockingTaskExecutor(true).build(service)
        fun blockingNoAuth(service: Any) =
            sb.annotatedService().useBlockingTaskExecutor(true).build(service)

        // Health check (no auth, lightweight — but blocking is harmless and consistent)
        blockingNoAuth(HealthHttpService())

        // CSP violation sink — unauthenticated by design (violations can fire
        // on the login page). Paired with CspDecorator.
        blockingNoAuth(net.stewart.mediamanager.armeria.CspReportHttpService())

        // Image endpoints
        blocking(PosterHttpService())
        blocking(net.stewart.mediamanager.armeria.TmdbPosterByIdHttpService())
        blocking(HeadshotHttpService())
        blocking(AuthorHeadshotHttpService())
        blocking(ArtistHeadshotHttpService())
        blocking(BackdropHttpService())
        blocking(CollectionPosterHttpService())
        blocking(LocalImageHttpService())
        blocking(OwnershipPhotoHttpService())
        blocking(PlaybackProgressHttpService())
        blocking(net.stewart.mediamanager.armeria.PublicArtTokenHttpService())
        // Token-gated unauthenticated artwork — needed so iOS / macOS
        // lock-screen now-playing UI can fetch album art (the OS fetch
        // doesn't share the browser's auth cookies).
        blockingNoAuth(net.stewart.mediamanager.armeria.PublicAlbumArtHttpService())

        // Streaming endpoints (video, camera, live TV)
        blocking(VideoStreamHttpService())
        blocking(CameraStreamHttpService())
        blocking(LiveTvStreamHttpService())

        // REST API — catalog endpoints
        blocking(HomeFeedHttpService())
        blocking(TitleListHttpService())
        blocking(TitleDetailHttpService())
        blocking(CollectionHttpService())
        blocking(TagHttpService())
        blocking(FamilyVideosHttpService())
        blocking(CameraListHttpService())
        blocking(LiveTvListHttpService())
        blocking(WishListHttpService())
        blocking(ProfileHttpService())
        blocking(ActorHttpService())
        blocking(AuthorHttpService())
        blocking(ArtistHttpService())
        blocking(AudioStreamHttpService())
        blocking(RadioHttpService())
        blocking(PlaylistHttpService())
        blocking(AdvancedSearchHttpService())
        blocking(RecommendationHttpService())
        blocking(BookSeriesHttpService())
        blocking(SearchHttpService())
        blocking(TranscodeStatusHttpService())
        blocking(UnmatchedHttpService())
        blocking(UnmatchedBookHttpService())
        blocking(UnmatchedAudioHttpService())
        blocking(TrackDiagnosticHttpService())
        blocking(EbookHttpService())
        blocking(ReadingProgressHttpService())
        blocking(ImageProxyHttpService())
        blocking(LinkedTranscodesHttpService())
        blocking(BacklogHttpService())
        blocking(TagManagementHttpService())
        blocking(SettingsHttpService())
        blocking(UserManagementHttpService())
        blocking(InventoryReportHttpService())
        blocking(PurchaseWishesHttpService())
        blocking(ValuationHttpService())
        blocking(AmazonImportHttpService())
        blocking(DocumentOwnershipHttpService())
        blocking(ExpandHttpService())
        blocking(LiveTvSettingsHttpService())
        blocking(CameraSettingsHttpService())
        blocking(DataQualityHttpService())
        blocking(ProblemReportHttpService())
        blocking(AddItemHttpService())
        blocking(MediaItemEditHttpService())
        blocking(FamilyMemberHttpService())
        blocking(LegalRestService())

        // REST API auth (unauthenticated — own proxy validation + rate limiting)
        blockingNoAuth(AuthRestService())

        // Roku endpoints (own auth: device token + cookie fallback, no ArmeriaAuthDecorator)
        blockingNoAuth(RokuFeedHttpService())
        // Device pairing (unauthenticated, has its own rate limiting)
        sb.annotatedService(PairingHttpService())
    }

    /**
     * Returns a decorator that rejects requests not arriving on the specified port.
     * Used to keep /metrics off the internet-facing port.
     */
    private fun internalOnlyDecorator(allowedPort: Int) = DecoratingHttpServiceFunction { delegate, ctx, req ->
        if (ctx.localAddress().port == allowedPort) {
            delegate.serve(ctx, req)
        } else {
            HttpResponse.of(HttpStatus.NOT_FOUND)
        }
    }
}
