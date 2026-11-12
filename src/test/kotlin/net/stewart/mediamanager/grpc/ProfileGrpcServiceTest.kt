package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.DeviceToken
import net.stewart.mediamanager.entity.RefreshToken
import net.stewart.mediamanager.entity.SessionToken
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [ProfileGrpcService] — getProfile, TV quality
 * preference, the active-sessions listing across browser/app/device
 * tokens, and the passkey-management RPCs.
 */
class ProfileGrpcServiceTest : GrpcTestBase() {

    @Test
    fun `getProfile returns the authenticated user's data`() = runBlocking {
        val user = createViewerUser(username = "viewer")
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val resp = stub.getProfile(Empty.getDefaultInstance())
            assertEquals("viewer", resp.username)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `getProfile requires authentication`() = runBlocking {
        val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.getProfile(Empty.getDefaultInstance())
        }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
    }

    // ---------------------- updateTvQuality ----------------------

    @Test
    fun `updateTvQuality maps each Quality enum to the right int`() = runBlocking {
        val user = createViewerUser(username = "qpref")
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)

            stub.updateTvQuality(updateTvQualityRequest { minQuality = Quality.QUALITY_SD })
            assertEquals(1, AppUser.findById(user.id!!)!!.live_tv_min_quality)

            stub.updateTvQuality(updateTvQualityRequest { minQuality = Quality.QUALITY_FHD })
            assertEquals(2, AppUser.findById(user.id!!)!!.live_tv_min_quality)

            stub.updateTvQuality(updateTvQualityRequest { minQuality = Quality.QUALITY_UHD })
            assertEquals(3, AppUser.findById(user.id!!)!!.live_tv_min_quality)

            // Unknown -> falls back to SD (1).
            stub.updateTvQuality(updateTvQualityRequest {
                minQuality = Quality.QUALITY_UNKNOWN
            })
            assertEquals(1, AppUser.findById(user.id!!)!!.live_tv_min_quality)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- listSessions ----------------------

    @Test
    fun `listSessions returns browser + app + device tokens for the caller only`() = runBlocking {
        val user = createViewerUser(username = "sessuser")
        val other = createViewerUser(username = "otheruser")

        // Caller owns: 1 valid browser session, 1 expired browser session,
        // 1 valid+non-revoked app refresh token, 1 revoked app token,
        // 1 expired app token, 1 device token. Other user has tokens that
        // must NOT appear.
        SessionToken(user_id = user.id!!, token_hash = "valid-browser",
            user_agent = "ua", expires_at = LocalDateTime.now().plusDays(1)).save()
        SessionToken(user_id = user.id!!, token_hash = "expired-browser",
            user_agent = "ua", expires_at = LocalDateTime.now().minusDays(1)).save()

        RefreshToken(user_id = user.id!!, token_hash = "valid-app",
            family_id = "f1", device_name = "App-1",
            expires_at = LocalDateTime.now().plusDays(1)).save()
        RefreshToken(user_id = user.id!!, token_hash = "revoked-app",
            family_id = "f2", device_name = "App-2",
            expires_at = LocalDateTime.now().plusDays(1), revoked = true).save()
        RefreshToken(user_id = user.id!!, token_hash = "expired-app",
            family_id = "f3", device_name = "App-3",
            expires_at = LocalDateTime.now().minusDays(1)).save()

        DeviceToken(user_id = user.id!!, token_hash = "device-1",
            device_name = "Roku-1").save()

        // Other user — must be filtered out.
        SessionToken(user_id = other.id!!, token_hash = "other-browser",
            user_agent = "ua", expires_at = LocalDateTime.now().plusDays(1)).save()

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val resp = stub.listSessions(Empty.getDefaultInstance())
            // 1 valid browser + (1 seeded app + 1 created by authenticatedChannel
            // via JwtService.createTokenPair) + 1 device = 4.
            assertEquals(4, resp.sessionsCount,
                "expired/revoked filtered, other user excluded")
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- deleteSession ----------------------

    @Test
    fun `deleteSession removes a browser session by id`() = runBlocking {
        val user = createViewerUser(username = "delbrowser")
        val targetId = SessionToken(user_id = user.id!!, token_hash = "h",
            user_agent = "ua", expires_at = LocalDateTime.now().plusDays(1))
            .apply { save() }.id!!

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            stub.deleteSession(deleteSessionRequest {
                sessionId = targetId
                type = SessionType.SESSION_TYPE_BROWSER
            })
            assertEquals(null, SessionToken.findById(targetId))
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `deleteSession marks an app refresh token revoked rather than hard-deleting`() = runBlocking {
        val user = createViewerUser(username = "delapp")
        val tokenId = RefreshToken(user_id = user.id!!, token_hash = "h",
            family_id = "f", device_name = "App",
            expires_at = LocalDateTime.now().plusDays(1))
            .apply { save() }.id!!

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            stub.deleteSession(deleteSessionRequest {
                sessionId = tokenId
                type = SessionType.SESSION_TYPE_APP
            })
            val refreshed = RefreshToken.findById(tokenId)!!
            assertTrue(refreshed.revoked, "app session deletion is a soft revoke")
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `deleteSession deletes a device token`() = runBlocking {
        val user = createViewerUser(username = "deldevice")
        val tokenId = DeviceToken(user_id = user.id!!, token_hash = "h",
            device_name = "Roku").apply { save() }.id!!

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            stub.deleteSession(deleteSessionRequest {
                sessionId = tokenId
                type = SessionType.SESSION_TYPE_DEVICE
            })
            assertEquals(null, DeviceToken.findById(tokenId))
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `deleteSession refuses other users' sessions silently`() = runBlocking {
        val user = createViewerUser(username = "owner")
        val intruder = createViewerUser(username = "intruder")
        val victimId = SessionToken(user_id = intruder.id!!, token_hash = "victim",
            user_agent = "ua", expires_at = LocalDateTime.now().plusDays(1))
            .apply { save() }.id!!

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            // No exception — service silently ignores other-user IDs.
            stub.deleteSession(deleteSessionRequest {
                sessionId = victimId
                type = SessionType.SESSION_TYPE_BROWSER
            })
            assertTrue(SessionToken.findById(victimId) != null,
                "intruder's session must NOT have been deleted")
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `deleteSession with unknown session type returns INVALID_ARGUMENT`() = runBlocking {
        val user = createViewerUser(username = "badtype")
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.deleteSession(deleteSessionRequest {
                    sessionId = 1
                    type = SessionType.SESSION_TYPE_UNKNOWN
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- deleteOtherSessions ----------------------

    @Test
    fun `deleteOtherSessions invalidates every session for the caller`() = runBlocking {
        val user = createViewerUser(username = "wipemyown")
        val other = createViewerUser(username = "preserved")
        SessionToken(user_id = user.id!!, token_hash = "a", user_agent = "ua",
            expires_at = LocalDateTime.now().plusDays(1)).save()
        SessionToken(user_id = user.id!!, token_hash = "b", user_agent = "ua",
            expires_at = LocalDateTime.now().plusDays(1)).save()
        SessionToken(user_id = other.id!!, token_hash = "preserved", user_agent = "ua",
            expires_at = LocalDateTime.now().plusDays(1)).save()

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            stub.deleteOtherSessions(Empty.getDefaultInstance())
            // Caller's sessions all gone; other user's untouched.
            assertEquals(0, SessionToken.findAll().count { it.user_id == user.id })
            assertEquals(1, SessionToken.findAll().count { it.user_id == other.id })
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- passkey RPCs ----------------------

    @Test
    fun `getPasskeyRegistrationOptions returns UNAVAILABLE when no RP is configured`() = runBlocking {
        val user = createViewerUser(username = "nopasskey")
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getPasskeyRegistrationOptions(Empty.getDefaultInstance())
            }
            assertEquals(Status.Code.UNAVAILABLE, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `registerPasskey rejects blank challenge with INVALID_ARGUMENT`() = runBlocking {
        val user = createViewerUser(username = "regnopass")
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.registerPasskey(registerPasskeyRequest {
                    signedChallenge = ""; credentialId = ""
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `listPasskeys returns an empty list when the user has none`() = runBlocking {
        val user = createViewerUser(username = "emptypasskeys")
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val resp = stub.listPasskeys(Empty.getDefaultInstance())
            assertEquals(0, resp.passkeysCount)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `deletePasskey returns NOT_FOUND for an unknown id`() = runBlocking {
        val user = createViewerUser(username = "delpasskey")
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.deletePasskey(deletePasskeyRequest { passkeyId = 99_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }
}
