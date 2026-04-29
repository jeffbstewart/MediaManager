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
        private const val PLAINTEXT = "Test1234!@#\$"
    }

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
        assertEquals("Invalid credentials", ex.status.description)
    }

    @Test
    fun `login surfaces RESOURCE_EXHAUSTED when AuthService rate-limits`() = runBlocking {
        createAdminUser(username = "ratelimited", password = PLAINTEXT)
        // Seed enough recent failures to trip the rate limit, with the most
        // recent attempt within the cooldown window so retryAfter > 0.
        val ip = "203.0.113.4" // RFC 5737 doc IP, already in allowlist
        repeat(5) {
            LoginAttempt(username = "ratelimited", ip_address = ip,
                attempted_at = LocalDateTime.now().minusSeconds(5),
                success = false).save()
        }

        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.login(loginRequest { username = "ratelimited"; password = PLAINTEXT })
        }
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, ex.status.code)
        assertNotNull(ex.status.description)
        assertTrue(ex.status.description!!.contains("Retry after"))
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
                    currentPassword = "WRONG"; newPassword = "NewPass1234!@#\$"
                })
            }
            assertEquals(Status.Code.UNAUTHENTICATED, wrong.status.code)

            // New password violating policy rejected (too short).
            val tooShort = assertFailsWith<StatusException> {
                stub.changePassword(changePasswordRequest {
                    currentPassword = PLAINTEXT; newPassword = "abc"
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, tooShort.status.code)

            // Successful rotation — new tokens, must_change_password cleared,
            // password verifies under the new value.
            val newPlaintext = "NewPass1234!@#\$"
            val resp = stub.changePassword(changePasswordRequest {
                currentPassword = PLAINTEXT; newPassword = newPlaintext
            })
            assertFalse(resp.accessToken.isEmpty)
            assertFalse(resp.refreshToken.isEmpty)
            val refreshed = AppUser.findById(user.id!!)!!
            assertFalse(refreshed.must_change_password)
            assertTrue(PasswordService.verify(newPlaintext, refreshed.password_hash))
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
                username = "newadmin"; password = "abc"; displayName = "New Admin"
            })
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
    }

    @Test
    fun `createFirstUser succeeds on empty DB then refuses second call with ALREADY_EXISTS`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        // First call on a fresh DB succeeds.
        val resp = stub.createFirstUser(createFirstUserRequest {
            username = "rootadmin"; password = "Test1234!@#\$"
            displayName = "Root Admin"
        })
        assertFalse(resp.accessToken.isEmpty)

        // The created user is an admin (access_level=2).
        val created = AppUser.findAll().single { it.username == "rootadmin" }
        assertEquals(2, created.access_level)

        // Second call must be refused — setup is now complete.
        val ex = assertFailsWith<StatusException> {
            stub.createFirstUser(createFirstUserRequest {
                username = "second"; password = "Test1234!@#\$"
                displayName = "Second"
            })
        }
        assertEquals(Status.Code.ALREADY_EXISTS, ex.status.code)
    }

    // ---------------------- getLegalStatus + agreeToTerms ----------------------

    private fun configureLegalVersions() {
        // Pin both versions so getLegalStatus has something to check.
        AppConfig(config_key = "privacy_policy_version", config_val = "3").save()
        AppConfig(config_key = "web_terms_of_use_version", config_val = "2").save()
        AppConfig(config_key = "privacy_policy_url",
            config_val = "https://example.test/privacy").save()
        AppConfig(config_key = "web_terms_of_use_url",
            config_val = "https://example.test/tou").save()
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
            assertEquals(3, resp.requiredPrivacyPolicyVersion)
            assertEquals(2, resp.requiredTermsOfUseVersion)
            assertEquals("https://example.test/privacy", resp.privacyPolicyUrl)
            assertEquals("https://example.test/tou", resp.termsOfUseUrl)
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
                    privacyPolicyVersion = 2 // old, current is 3
                    termsOfUseVersion = 2
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, staleEx.status.code)

            // Stale TOU version is rejected.
            val staleTouEx = assertFailsWith<StatusException> {
                stub.agreeToTerms(agreeToTermsRequest {
                    platform = ClientPlatform.CLIENT_PLATFORM_WEB
                    privacyPolicyVersion = 3
                    termsOfUseVersion = 1 // old, current is 2
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, staleTouEx.status.code)

            // Current versions accepted; user row + cache updated.
            val ok = stub.agreeToTerms(agreeToTermsRequest {
                platform = ClientPlatform.CLIENT_PLATFORM_WEB
                privacyPolicyVersion = 3
                termsOfUseVersion = 2
            })
            assertTrue(ok.ok)

            val refreshed = AppUser.findById(viewer.id!!)!!
            assertEquals(3, refreshed.privacy_policy_version)
            assertEquals(2, refreshed.terms_of_use_version)
            assertNotNull(refreshed.privacy_policy_accepted_at)
            assertNotNull(refreshed.terms_of_use_accepted_at)

            // After agreement, getLegalStatus reports compliant for the same user.
            val status = stub.getLegalStatus(getLegalStatusRequest {
                platform = ClientPlatform.CLIENT_PLATFORM_WEB
            })
            assertTrue(status.compliant)
            assertEquals(3, status.agreedPrivacyPolicyVersion)
            assertEquals(2, status.agreedTermsOfUseVersion)
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
                privacyPolicyVersion = 1; termsOfUseVersion = 1
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
