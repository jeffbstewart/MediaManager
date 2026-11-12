package net.stewart.mediamanager.grpc

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.LoginAttempt
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.service.PasswordService
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [AuthGrpcService] paths not yet covered by
 * [ServiceIntegrationTest] — login validation, rate limiting, revoke,
 * change-password, first-user setup, and the legal-agreement RPCs.
 *
 * BCrypt at cost 12 is the slow op here; tests share a single hashed
 * password fixture and minimize the number of full login flows.
 */
class AuthGrpcServiceTest : GrpcTestBase() {

    companion object {
        /** The password the test users log in with. */
        private const val PLAINTEXT = GrpcTestBase.DEFAULT_PASSWORD
        /** A second policy-compliant password, for the rotation test. */
        private const val ROTATED_PLAINTEXT = "NewPass1234!@#\$"
        /** Too short for the password policy. */
        private const val WEAK_PASSWORD = "abc"
        /** RFC 5737 documentation address, already on the presubmit allowlist. */
        private const val CLIENT_IP = "203.0.113.4"

        // Legal versions the tests pin in app_config. A user who agreed to
        // a REQUIRED version is compliant; a STALE one is one behind and is
        // refused. NO_TERMS marks a platform with no terms of use at all.
        private const val REQUIRED_PRIVACY_VERSION = 3
        private const val STALE_PRIVACY_VERSION = REQUIRED_PRIVACY_VERSION - 1
        private const val REQUIRED_WEB_TOU_VERSION = 2
        private const val STALE_WEB_TOU_VERSION = REQUIRED_WEB_TOU_VERSION - 1
        private const val REQUIRED_IOS_TOU_VERSION = 5
        private const val NO_TERMS = 0
        private const val PRIVACY_URL = "https://example.test/privacy"
        private const val TOU_URL = "https://example.test/tou"
    }

    private fun setConfig(key: String, value: String) {
        AppConfig(config_key = key, config_val = value).save()
    }

    private fun setConfig(key: String, value: Int) = setConfig(key, value.toString())

    // ---------------------- login validation ----------------------

    @Test
    fun `login rejects blank username and blank password with INVALID_ARGUMENT`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)

        val ex1 = assertFailsWith<StatusException> {
            stub.login(loginRequest { username = ""; password = "p" })
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, ex1.status.code)

        val ex2 = assertFailsWith<StatusException> {
            stub.login(loginRequest { username = "u"; password = "" })
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, ex2.status.code)
    }

    @Test
    fun `login with wrong credentials returns UNAUTHENTICATED with generic message`() = runBlocking {
        createAdminUser(username = "alice", password = PLAINTEXT)
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)

        val ex = assertFailsWith<StatusException> {
            stub.login(loginRequest { username = "alice"; password = "nope" })
        }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
        // Description must NOT distinguish unknown-user vs wrong-password.
        assertEquals(AuthService.INVALID_CREDENTIALS_MESSAGE, ex.status.description)
    }

    @Test
    fun `login surfaces RESOURCE_EXHAUSTED when AuthService rate-limits`() = runBlocking {
        createAdminUser(username = "ratelimited", password = PLAINTEXT)
        // Seed enough recent failures to trip the throttle, with the most
        // recent one still inside the base cooldown so retryAfter > 0.
        val lastFailure = LocalDateTime.now().minusSeconds(AuthService.RATE_LIMIT_BASE_COOLDOWN_SECONDS / 2)
        repeat(AuthService.THROTTLE_THRESHOLD) {
            LoginAttempt(username = "ratelimited", ip_address = CLIENT_IP,
                attempted_at = lastFailure,
                success = false).save()
        }

        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.login(loginRequest { username = "ratelimited"; password = PLAINTEXT })
        }
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, ex.status.code)
        assertNotNull(ex.status.description)
        assertTrue(ex.status.description!!.contains(AuthGrpcService.RETRY_AFTER))
    }

    // ---------------------- refresh validation ----------------------

    @Test
    fun `refresh with empty token returns INVALID_ARGUMENT`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.refresh(refreshRequest { refreshToken = ByteString.EMPTY })
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
    }

    // ---------------------- revoke ----------------------

    @Test
    fun `revoke always returns success even with an unknown token`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val resp = stub.revoke(revokeRequest {
            refreshToken = ByteString.copyFromUtf8("never-issued")
        })
        assertTrue(resp.revoked, "revoke must not reveal token validity")
    }

    @Test
    fun `revoke with empty token still returns success`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val resp = stub.revoke(revokeRequest { refreshToken = ByteString.EMPTY })
        assertTrue(resp.revoked)
    }

    // ---------------------- changePassword ----------------------

    @Test
    fun `changePassword requires authentication`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.changePassword(changePasswordRequest {
                currentPassword = "x"; newPassword = "y"
            })
        }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
    }

    @Test
    fun `changePassword end to end - validates, rotates, and issues a new token`() = runBlocking {
        val user = createAdminUser(username = "carol", password = PLAINTEXT)
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(authedChannel)

            // Blank password rejected.
            val blank = assertFailsWith<StatusException> {
                stub.changePassword(changePasswordRequest {
                    currentPassword = ""; newPassword = ""
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, blank.status.code)

            // Wrong current password rejected.
            val wrong = assertFailsWith<StatusException> {
                stub.changePassword(changePasswordRequest {
                    currentPassword = "WRONG"; newPassword = ROTATED_PLAINTEXT
                })
            }
            assertEquals(Status.Code.UNAUTHENTICATED, wrong.status.code)

            // New password violating policy rejected (too short).
            val tooShort = assertFailsWith<StatusException> {
                stub.changePassword(changePasswordRequest {
                    currentPassword = PLAINTEXT; newPassword = WEAK_PASSWORD
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, tooShort.status.code)

            // Successful rotation — new tokens, must_change_password cleared,
            // password verifies under the new value.
            val resp = stub.changePassword(changePasswordRequest {
                currentPassword = PLAINTEXT; newPassword = ROTATED_PLAINTEXT
            })
            assertFalse(resp.accessToken.isEmpty)
            assertFalse(resp.refreshToken.isEmpty)
            val refreshed = AppUser.findById(user.id!!)!!
            assertFalse(refreshed.must_change_password)
            assertTrue(PasswordService.verify(ROTATED_PLAINTEXT, refreshed.password_hash))
            assertFalse(PasswordService.verify(PLAINTEXT, refreshed.password_hash),
                "old password must no longer verify")
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ---------------------- createFirstUser ----------------------

    @Test
    fun `createFirstUser rejects blank fields with INVALID_ARGUMENT`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.createFirstUser(createFirstUserRequest {
                username = ""; password = "x"; displayName = "x"
            })
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
    }

    @Test
    fun `createFirstUser rejects passwords that violate the policy`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.createFirstUser(createFirstUserRequest {
                username = "newadmin"; password = WEAK_PASSWORD; displayName = "New Admin"
            })
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
    }

    @Test
    fun `createFirstUser succeeds on empty DB then refuses second call with ALREADY_EXISTS`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        // First call on a fresh DB succeeds.
        val resp = stub.createFirstUser(createFirstUserRequest {
            username = "rootadmin"; password = PLAINTEXT
            displayName = "Root Admin"
        })
        assertFalse(resp.accessToken.isEmpty)

        // The first user is always an admin.
        val created = AppUser.findAll().single { it.username == "rootadmin" }
        assertTrue(created.isAdmin(), "the first user is an admin")

        // Second call must be refused — setup is now complete.
        val ex = assertFailsWith<StatusException> {
            stub.createFirstUser(createFirstUserRequest {
                username = "second"; password = PLAINTEXT
                displayName = "Second"
            })
        }
        assertEquals(Status.Code.ALREADY_EXISTS, ex.status.code)
    }

    // ---------------------- legal gate: which platform's terms apply ----------------------

    private fun configurePlatformVersions() {
        // Privacy is required everywhere; terms only on iOS. A viewer who
        // agreed to the privacy policy owes nothing more on the web.
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, REQUIRED_PRIVACY_VERSION)
        setConfig(LegalRequirements.IOS_TERMS_OF_USE_VERSION_KEY, REQUIRED_IOS_TOU_VERSION)
        setConfig(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, NO_TERMS)
        LegalRequirements.refresh()
    }

    private fun privacyOnlyViewer(name: String): AppUser = createViewerUser(username = name).apply {
        privacy_policy_version = REQUIRED_PRIVACY_VERSION
        privacy_policy_accepted_at = LocalDateTime.now()
        save()
    }

    @Test
    fun `a session-cookie caller without a platform header is gated as the web app`() = runBlocking<Unit> {
        // The SPA is the only cookie-authenticated gRPC client. Before this,
        // a missing header meant the strictest platform's terms version,
        // which the web terms page could never satisfy: every non-admin web
        // user got legal_agreement_required on every call (2026-11-10).
        configurePlatformVersions()
        val viewer = privacyOnlyViewer("web-cookie-viewer")
        val channel = cookieChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(channel)
            assertNotNull(stub.getFeatures(Empty.getDefaultInstance()))
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `a bearer caller without a platform header is held to the strictest terms version`() = runBlocking {
        configurePlatformVersions()
        val viewer = privacyOnlyViewer("app-bearer-viewer")
        val channel = authenticatedChannel(viewer)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(channel)
            val ex = assertFailsWith<StatusException> { stub.getFeatures(Empty.getDefaultInstance()) }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
            assertEquals(AuthInterceptor.LEGAL_AGREEMENT_REQUIRED, ex.status.description)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `a bearer caller that names the web platform is gated by the web terms version`() = runBlocking<Unit> {
        configurePlatformVersions()
        val viewer = privacyOnlyViewer("web-bearer-viewer")
        val channel = authenticatedChannel(viewer, platform = LegalRequirements.PLATFORM_WEB)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(channel)
            assertNotNull(stub.getFeatures(Empty.getDefaultInstance()))
        } finally {
            channel.shutdownNow()
        }
    }

    // ---------------------- getLegalStatus + agreeToTerms ----------------------

    private fun configureLegalVersions() {
        // Pin both versions so getLegalStatus has something to check.
        setConfig(LegalRequirements.PRIVACY_POLICY_VERSION_KEY, REQUIRED_PRIVACY_VERSION)
        setConfig(LegalRequirements.WEB_TERMS_OF_USE_VERSION_KEY, REQUIRED_WEB_TOU_VERSION)
        setConfig(LegalRequirements.PRIVACY_POLICY_URL_KEY, PRIVACY_URL)
        setConfig(LegalRequirements.WEB_TERMS_OF_USE_URL_KEY, TOU_URL)
        LegalRequirements.refresh()
    }

    @Test
    fun `getLegalStatus reports non-compliant for a fresh non-admin viewer`() = runBlocking {
        configureLegalVersions()
        val viewer = createViewerUser(username = "fresh-viewer")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(authedChannel)
            val resp = stub.getLegalStatus(getLegalStatusRequest {
                platform = ClientPlatform.CLIENT_PLATFORM_WEB
            })
            assertFalse(resp.compliant)
            assertEquals(REQUIRED_PRIVACY_VERSION, resp.requiredPrivacyPolicyVersion)
            assertEquals(REQUIRED_WEB_TOU_VERSION, resp.requiredTermsOfUseVersion)
            assertEquals(PRIVACY_URL, resp.privacyPolicyUrl)
            assertEquals(TOU_URL, resp.termsOfUseUrl)
            // No prior agreement, so optional agreed_* are unset.
            assertFalse(resp.hasAgreedPrivacyPolicyVersion())
            assertFalse(resp.hasAgreedTermsOfUseVersion())
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `getLegalStatus reports compliant for admins regardless of agreement`() = runBlocking {
        configureLegalVersions()
        val admin = createAdminUser(username = "fresh-admin")
        val authedChannel = authenticatedChannel(admin)
        try {
            val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(authedChannel)
            val resp = stub.getLegalStatus(getLegalStatusRequest {
                platform = ClientPlatform.CLIENT_PLATFORM_WEB
            })
            assertTrue(resp.compliant, "admins are always compliant")
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `agreeToTerms rejects a stale version, then accepts the current one and flips compliance`() = runBlocking {
        configureLegalVersions()
        val viewer = createViewerUser(username = "agreeing-viewer")
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(authedChannel)

            // Stale PP version is rejected.
            val staleEx = assertFailsWith<StatusException> {
                stub.agreeToTerms(agreeToTermsRequest {
                    platform = ClientPlatform.CLIENT_PLATFORM_WEB
                    privacyPolicyVersion = STALE_PRIVACY_VERSION
                    termsOfUseVersion = REQUIRED_WEB_TOU_VERSION
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, staleEx.status.code)

            // Stale TOU version is rejected.
            val staleTouEx = assertFailsWith<StatusException> {
                stub.agreeToTerms(agreeToTermsRequest {
                    platform = ClientPlatform.CLIENT_PLATFORM_WEB
                    privacyPolicyVersion = REQUIRED_PRIVACY_VERSION
                    termsOfUseVersion = STALE_WEB_TOU_VERSION
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, staleTouEx.status.code)

            // Current versions accepted; user row + cache updated.
            val ok = stub.agreeToTerms(agreeToTermsRequest {
                platform = ClientPlatform.CLIENT_PLATFORM_WEB
                privacyPolicyVersion = REQUIRED_PRIVACY_VERSION
                termsOfUseVersion = REQUIRED_WEB_TOU_VERSION
            })
            assertTrue(ok.ok)

            val refreshed = AppUser.findById(viewer.id!!)!!
            assertEquals(REQUIRED_PRIVACY_VERSION, refreshed.privacy_policy_version)
            assertEquals(REQUIRED_WEB_TOU_VERSION, refreshed.terms_of_use_version)
            assertNotNull(refreshed.privacy_policy_accepted_at)
            assertNotNull(refreshed.terms_of_use_accepted_at)

            // After agreement, getLegalStatus reports compliant for the same user.
            val status = stub.getLegalStatus(getLegalStatusRequest {
                platform = ClientPlatform.CLIENT_PLATFORM_WEB
            })
            assertTrue(status.compliant)
            assertEquals(REQUIRED_PRIVACY_VERSION, status.agreedPrivacyPolicyVersion)
            assertEquals(REQUIRED_WEB_TOU_VERSION, status.agreedTermsOfUseVersion)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `agreeToTerms requires authentication`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.agreeToTerms(agreeToTermsRequest {
                platform = ClientPlatform.CLIENT_PLATFORM_WEB
                privacyPolicyVersion = REQUIRED_PRIVACY_VERSION
                termsOfUseVersion = REQUIRED_WEB_TOU_VERSION
            })
        }
        assertEquals(Status.Code.UNAUTHENTICATED, ex.status.code)
    }

    // ---------------------- passkey rate limit ----------------------

    @Test
    fun `getPasskeyAuthenticationOptions returns UNAVAILABLE when not configured`() = runBlocking {
        // Default H2 fixture has no webauthn_rp_id row — passkey isn't
        // configured for this test class, so the RPC must short-circuit
        // with UNAVAILABLE rather than try to generate a challenge.
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.getPasskeyAuthenticationOptions(
                getPasskeyAuthenticationOptionsRequest { }
            )
        }
        assertEquals(Status.Code.UNAVAILABLE, ex.status.code)
    }

    @Test
    fun `authenticateWithPasskey rejects empty challenge with INVALID_ARGUMENT`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.authenticateWithPasskey(authenticateWithPasskeyRequest {
                signedChallenge = ""; credentialId = ""
            })
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
    }
}
