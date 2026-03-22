package net.stewart.mediamanager.grpc

import com.google.protobuf.ByteString
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.EnrichmentStatus as EnrichmentStatusEnum
import net.stewart.mediamanager.entity.MediaFormat as MediaFormatEnum
import net.stewart.mediamanager.entity.MediaType as MediaTypeEnum
import net.stewart.mediamanager.entity.Tag as TagEntity
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Genre as GenreEntity
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.Episode as EpisodeEntity
import net.stewart.mediamanager.entity.TitleSeason
import net.stewart.mediamanager.entity.PlaybackProgress as ProgressEntity
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.service.PasswordService
import java.time.LocalDateTime
import kotlin.test.*

/**
 * Integration tests for gRPC service methods with real database state.
 * Tests the full path: client stub → interceptors → service → database → proto response.
 */
class ServiceIntegrationTest : GrpcTestBase() {

    // ========================================================================
    // Auth service
    // ========================================================================

    @Test
    fun `Login and Refresh token flow`() = runBlocking {
        createAdminUser(username = "flowtest", password = "Test1234!@#\$")
        val authStub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)

        // Login
        val loginResp = authStub.login(loginRequest {
            username = "flowtest"
            password = "Test1234!@#\$"
        })
        assertFalse(loginResp.accessToken.isEmpty)
        assertFalse(loginResp.refreshToken.isEmpty)
        assertTrue(loginResp.expiresIn > 0)

        // Refresh
        val refreshResp = authStub.refresh(refreshRequest {
            refreshToken = loginResp.refreshToken
        })
        assertFalse(refreshResp.accessToken.isEmpty)
        assertFalse(refreshResp.refreshToken.isEmpty)
    }

    @Test
    fun `Refresh with invalid token returns UNAUTHENTICATED`() = runBlocking {
        val authStub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            authStub.refresh(refreshRequest {
                refreshToken = ByteString.copyFromUtf8("invalid-token")
            })
        }
        assertEquals(io.grpc.Status.UNAUTHENTICATED.code, ex.status.code)
    }

    // ========================================================================
    // Info service
    // ========================================================================

    @Test
    fun `GetInfo returns capabilities and title count`() = runBlocking {
        val user = createAdminUser()
        createTitle(name = "Movie A")
        createTitle(name = "Movie B")

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = InfoServiceGrpcKt.InfoServiceCoroutineStub(authedChannel)
            val response = stub.getInfo(Empty.getDefaultInstance())
            assertEquals(2, response.titleCount)
            assertTrue(response.capabilitiesList.contains(Capability.CAPABILITY_CATALOG))
            assertEquals(user.username, response.user.username)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ========================================================================
    // Profile service
    // ========================================================================

    @Test
    fun `GetProfile returns user info`() = runBlocking {
        val user = createViewerUser(username = "proftest")
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val profile = stub.getProfile(Empty.getDefaultInstance())
            assertEquals("proftest", profile.username)
            assertFalse(profile.isAdmin)
            assertFalse(profile.mustChangePassword)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `UpdateTvQuality changes user preference`() = runBlocking {
        val user = createViewerUser()
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            stub.updateTvQuality(updateTvQualityRequest {
                minQuality = Quality.QUALITY_UHD
            })
            // Verify via fresh profile fetch
            val profile = stub.getProfile(Empty.getDefaultInstance())
            assertEquals(Quality.QUALITY_UHD, profile.liveTvMinQuality)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ========================================================================
    // Playback service
    // ========================================================================

    @Test
    fun `ReportProgress and GetProgress round-trip`() = runBlocking {
        val user = createViewerUser()
        val title = createTitle()
        val tc = createTranscode(title.id!!)

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = PlaybackServiceGrpcKt.PlaybackServiceCoroutineStub(authedChannel)

            // Report progress
            stub.reportProgress(reportProgressRequest {
                transcodeId = tc.id!!
                position = 120.5.toPlaybackOffset()
                duration = 7200.0.toPlaybackOffset()
            })

            // Get progress
            val progress = stub.getProgress(transcodeIdRequest { transcodeId = tc.id!! })
            assertEquals(tc.id!!, progress.transcodeId)
            assertEquals(120.5, progress.position.seconds, 0.1)
            assertEquals(7200.0, progress.duration.seconds, 0.1)

            // Clear progress
            stub.clearProgress(transcodeIdRequest { transcodeId = tc.id!! })
            val cleared = stub.getProgress(transcodeIdRequest { transcodeId = tc.id!! })
            assertEquals(0.0, cleared.position.seconds, 0.001)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ========================================================================
    // Admin service — user management
    // ========================================================================

    @Test
    fun `Admin can create and list users`() = runBlocking {
        val admin = createAdminUser()
        val authedChannel = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authedChannel)

            // Create a user
            val createResp = stub.createUser(createUserRequest {
                username = "newuser"
                password = "NewUser1234!@#\$"
                displayName = "New User"
                forceChange = true
            })
            assertEquals("newuser", createResp.username)
            assertTrue(createResp.id > 0)

            // List users — should include both admin and newuser
            val listResp = stub.listUsers(Empty.getDefaultInstance())
            val usernames = listResp.usersList.map { it.username }
            assertTrue("admin" in usernames)
            assertTrue("newuser" in usernames)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `Admin cannot delete themselves`() = runBlocking {
        val admin = createAdminUser()
        val authedChannel = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.deleteUser(userIdRequest { userId = admin.id!! })
            }
            assertEquals(io.grpc.Status.FAILED_PRECONDITION.code, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ========================================================================
    // Admin service — settings
    // ========================================================================

    @Test
    fun `GetSettings and UpdateSetting round-trip`() = runBlocking {
        val admin = createAdminUser()
        val authedChannel = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authedChannel)

            // Update a setting
            stub.updateSetting(updateSettingRequest {
                key = SettingKey.SETTING_KEY_NAS_ROOT_PATH
                value = "/mnt/nas"
            })

            // Get settings and verify
            val settings = stub.getSettings(Empty.getDefaultInstance())
            val nasSetting = settings.settingsList.firstOrNull {
                it.key == SettingKey.SETTING_KEY_NAS_ROOT_PATH
            }
            assertNotNull(nasSetting)
            assertEquals("/mnt/nas", nasSetting.value)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `UpdateSetting rejects unknown key`() = runBlocking {
        val admin = createAdminUser()
        val authedChannel = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.updateSetting(updateSettingRequest {
                    key = SettingKey.SETTING_KEY_UNKNOWN
                    value = "anything"
                })
            }
            assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ========================================================================
    // Admin service — tags
    // ========================================================================

    @Test
    fun `CreateTag with duplicate returns DUPLICATE result`() = runBlocking {
        val admin = createAdminUser()
        val authedChannel = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authedChannel)

            // Create first tag
            val first = stub.createTag(createTagRequest {
                name = "Action"
                color = color { hex = "#FF0000" }
            })
            assertEquals(CreateTagResult.CREATE_TAG_RESULT_CREATED, first.result)
            assertTrue(first.id > 0)

            // Create duplicate
            val dupe = stub.createTag(createTagRequest {
                name = "Action"
                color = color { hex = "#00FF00" }
            })
            assertEquals(CreateTagResult.CREATE_TAG_RESULT_DUPLICATE, dupe.result)
            assertEquals(first.id, dupe.id)
        } finally {
            authedChannel.shutdownNow()
        }
    }
}
