package net.stewart.mediamanager.grpc

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.ServerInterceptors
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.PasswordService
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

// Use fully-qualified entity references to avoid collisions with proto-generated
// classes in the net.stewart.mediamanager.grpc package.
private typealias TitleEntity = net.stewart.mediamanager.entity.Title
private typealias TranscodeEntity = net.stewart.mediamanager.entity.Transcode
private typealias EpisodeEntity = net.stewart.mediamanager.entity.Episode
private typealias TagEntity = net.stewart.mediamanager.entity.Tag
private typealias GenreEntity = net.stewart.mediamanager.entity.Genre
private typealias CastMemberEntity = net.stewart.mediamanager.entity.CastMember

/**
 * Base class for gRPC service integration tests.
 *
 * Stands up an in-memory H2 database with Flyway migrations and an in-process
 * gRPC server with [AuthInterceptor] and [LoggingInterceptor]. Subclasses get
 * a [channel] for creating typed stubs and helper methods for auth setup.
 */
open class GrpcTestBase {

    companion object {
        private lateinit var dataSource: HikariDataSource
        private lateinit var server: io.grpc.Server
        lateinit var channel: ManagedChannel
            private set

        private const val SERVER_NAME = "grpc-test-server"

        @BeforeClass @JvmStatic
        fun setupAll() {
            // Database
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:grpctest;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate()

            // In-process gRPC server with auth + logging interceptors
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
                AdminGrpcService()
            )

            val builder = InProcessServerBuilder.forName(SERVER_NAME).directExecutor()
            for (service in services) {
                builder.addService(
                    ServerInterceptors.intercept(service, loggingInterceptor, authInterceptor)
                )
            }
            server = builder.build().start()

            channel = InProcessChannelBuilder.forName(SERVER_NAME)
                .directExecutor()
                .build()
        }

        @AfterClass @JvmStatic
        fun teardownAll() {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    @Before
    fun cleanAllTables() {
        // Delete in FK-safe order (reverse dependency)
        net.stewart.mediamanager.entity.PlaybackProgress.deleteAll()
        net.stewart.mediamanager.entity.UserTitleFlag.deleteAll()
        net.stewart.mediamanager.entity.SkipSegment.deleteAll()
        net.stewart.mediamanager.entity.Chapter.deleteAll()
        net.stewart.mediamanager.entity.TranscodeLease.deleteAll()
        net.stewart.mediamanager.entity.DiscoveredFile.deleteAll()
        net.stewart.mediamanager.entity.TitleFamilyMember.deleteAll()
        net.stewart.mediamanager.entity.FamilyMember.deleteAll()
        net.stewart.mediamanager.entity.TitleTag.deleteAll()
        net.stewart.mediamanager.entity.TitleGenre.deleteAll()
        CastMemberEntity.deleteAll()
        net.stewart.mediamanager.entity.MediaItemTitleSeason.deleteAll()
        net.stewart.mediamanager.entity.TitleSeason.deleteAll()
        net.stewart.mediamanager.entity.MediaItemTitle.deleteAll()
        net.stewart.mediamanager.entity.MediaItem.deleteAll()
        EpisodeEntity.deleteAll()
        TranscodeEntity.deleteAll()
        net.stewart.mediamanager.entity.WishListItem.deleteAll()
        net.stewart.mediamanager.entity.DismissedNotification.deleteAll()
        net.stewart.mediamanager.entity.DeviceToken.deleteAll()
        net.stewart.mediamanager.entity.RefreshToken.deleteAll()
        net.stewart.mediamanager.entity.SessionToken.deleteAll()
        TitleEntity.deleteAll()
        TagEntity.deleteAll()
        GenreEntity.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    // ========================================================================
    // Test data helpers
    // ========================================================================

    protected fun createAdminUser(
        username: String = "admin",
        password: String = "Test1234!@#\$"
    ): AppUser {
        val now = LocalDateTime.now()
        val user = AppUser(
            username = username,
            display_name = username,
            password_hash = PasswordService.hash(password),
            access_level = 2,
            created_at = now,
            updated_at = now
        )
        user.save()
        return user
    }

    protected fun createViewerUser(
        username: String = "viewer",
        password: String = "Test1234!@#\$"
    ): AppUser {
        val now = LocalDateTime.now()
        val user = AppUser(
            username = username,
            display_name = username,
            password_hash = PasswordService.hash(password),
            access_level = 1,
            created_at = now,
            updated_at = now
        )
        user.save()
        return user
    }

    protected fun authenticatedChannel(user: AppUser): ManagedChannel {
        val tokenPair = JwtService.createTokenPair(user, "test")
        val metadata = Metadata().apply {
            put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer ${tokenPair.accessToken}"
            )
        }
        return InProcessChannelBuilder.forName(SERVER_NAME)
            .directExecutor()
            .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
            .build()
    }

    protected fun createTitle(
        name: String = "Test Movie",
        mediaType: String = net.stewart.mediamanager.entity.MediaType.MOVIE.name,
        tmdbId: Int? = null,
        enrichmentStatus: String = net.stewart.mediamanager.entity.EnrichmentStatus.ENRICHED.name,
        contentRating: String? = "PG-13",
        popularity: Double? = 50.0,
        posterPath: String? = "/poster.jpg",
        releaseYear: Int? = 2024
    ): TitleEntity {
        val now = LocalDateTime.now()
        val title = TitleEntity(
            name = name,
            media_type = mediaType,
            tmdb_id = tmdbId,
            enrichment_status = enrichmentStatus,
            content_rating = contentRating,
            popularity = popularity,
            poster_path = posterPath,
            release_year = releaseYear,
            sort_name = name.lowercase(),
            created_at = now,
            updated_at = now
        )
        title.save()
        return title
    }

    protected fun createTranscode(
        titleId: Long,
        filePath: String? = null,
        mediaFormat: String = net.stewart.mediamanager.entity.MediaFormat.BLURAY.name,
        episodeId: Long? = null
    ): TranscodeEntity {
        val tc = TranscodeEntity(
            title_id = titleId,
            file_path = filePath,
            media_format = mediaFormat,
            episode_id = episodeId,
            created_at = LocalDateTime.now(),
            updated_at = LocalDateTime.now()
        )
        tc.save()
        return tc
    }
}
