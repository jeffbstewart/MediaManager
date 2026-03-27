package net.stewart.mediamanager.grpc

import com.google.protobuf.ByteString
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests the AuthInterceptor's three RPC categories:
 * 1. Unauthenticated — Login, Refresh, Revoke, Discover
 * 2. Authenticated gate-exempt — ChangePassword, Profile, GetInfo
 * 3. Authenticated gated — everything else
 *
 * Also tests admin enforcement on AdminService RPCs.
 */
class AuthInterceptorTest : GrpcTestBase() {

    // ========================================================================
    // Category 1: Unauthenticated RPCs should work without a token
    // ========================================================================

    @Test
    fun `Discover works without authentication`() = runBlocking {
        val stub = InfoServiceGrpcKt.InfoServiceCoroutineStub(channel)
        val response = stub.discover(DiscoverRequest.getDefaultInstance())
        assertTrue(response.apiVersionsList.isNotEmpty())
        assertEquals(AuthMethod.AUTH_METHOD_JWT, response.authMethodsList.first())
        assertTrue(response.serverFingerprint.isNotBlank())
    }

    @Test
    fun `Login works without authentication`() = runBlocking {
        createAdminUser(username = "testlogin", password = "Test1234!@#\$")
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val response = stub.login(loginRequest {
            username = "testlogin"
            password = "Test1234!@#\$"
        })
        assertFalse(response.accessToken.isEmpty)
        assertFalse(response.refreshToken.isEmpty)
        assertEquals(TokenType.TOKEN_TYPE_BEARER, response.tokenType)
    }

    @Test
    fun `Login with wrong password returns UNAUTHENTICATED`() = runBlocking {
        createAdminUser(username = "badpw", password = "Test1234!@#\$")
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.login(loginRequest {
                username = "badpw"
                password = "wrongpassword"
            })
        }
        assertEquals(io.grpc.Status.UNAUTHENTICATED.code, ex.status.code)
    }

    @Test
    fun `Revoke works without authentication and always succeeds`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val response = stub.revoke(revokeRequest {
            refreshToken = ByteString.copyFromUtf8("nonexistent-token")
        })
        assertTrue(response.revoked)
    }

    // ========================================================================
    // CreateFirstUser — setup flow
    // ========================================================================

    @Test
    fun `CreateFirstUser succeeds on empty database`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val response = stub.createFirstUser(createFirstUserRequest {
            username = "firstadmin"
            password = "Test1234!@#\$"
            displayName = "First Admin"
            deviceName = "Test Device"
        })
        assertFalse(response.accessToken.isEmpty)
        assertFalse(response.refreshToken.isEmpty)
        assertEquals(TokenType.TOKEN_TYPE_BEARER, response.tokenType)
    }

    @Test
    fun `CreateFirstUser fails when users already exist`() = runBlocking {
        createAdminUser()
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.createFirstUser(createFirstUserRequest {
                username = "secondadmin"
                password = "Test1234!@#\$"
                displayName = "Second Admin"
            })
        }
        assertEquals(io.grpc.Status.ALREADY_EXISTS.code, ex.status.code)
    }

    @Test
    fun `CreateFirstUser validates password`() = runBlocking {
        val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.createFirstUser(createFirstUserRequest {
                username = "admin"
                password = "short"
                displayName = "Admin"
            })
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
    }

    @Test
    fun `Discover returns setup_required when no users exist`() = runBlocking {
        val stub = InfoServiceGrpcKt.InfoServiceCoroutineStub(channel)
        val response = stub.discover(DiscoverRequest.getDefaultInstance())
        assertTrue(response.setupRequired)
    }

    @Test
    fun `Discover returns setup_required false when users exist`() = runBlocking {
        createAdminUser()
        val stub = InfoServiceGrpcKt.InfoServiceCoroutineStub(channel)
        val response = stub.discover(DiscoverRequest.getDefaultInstance())
        assertFalse(response.setupRequired)
    }

    // ========================================================================
    // Authenticated RPCs should reject unauthenticated calls
    // ========================================================================

    @Test
    fun `GetInfo without token returns UNAUTHENTICATED`() = runBlocking {
        val stub = InfoServiceGrpcKt.InfoServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.getInfo(Empty.getDefaultInstance())
        }
        assertEquals(io.grpc.Status.UNAUTHENTICATED.code, ex.status.code)
    }

    @Test
    fun `HomeFeed without token returns UNAUTHENTICATED`() = runBlocking {
        val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.homeFeed(Empty.getDefaultInstance())
        }
        assertEquals(io.grpc.Status.UNAUTHENTICATED.code, ex.status.code)
    }

    @Test
    fun `ListUsers without token returns UNAUTHENTICATED`() = runBlocking {
        val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(channel)
        val ex = assertFailsWith<StatusException> {
            stub.listUsers(Empty.getDefaultInstance())
        }
        assertEquals(io.grpc.Status.UNAUTHENTICATED.code, ex.status.code)
    }

    // ========================================================================
    // Authenticated calls should succeed with a valid token
    // ========================================================================

    @Test
    fun `GetInfo succeeds with valid token`() = runBlocking {
        val user = createAdminUser()
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = InfoServiceGrpcKt.InfoServiceCoroutineStub(authedChannel)
            val response = stub.getInfo(Empty.getDefaultInstance())
            assertEquals(user.username, response.user.username)
            assertTrue(response.user.isAdmin)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `GetProfile succeeds with valid token`() = runBlocking {
        val user = createViewerUser()
        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val response = stub.getProfile(Empty.getDefaultInstance())
            assertEquals(user.username, response.username)
            assertFalse(response.isAdmin)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ========================================================================
    // Admin enforcement: viewer cannot call AdminService RPCs
    // ========================================================================

    @Test
    fun `Viewer cannot call ListUsers`() = runBlocking {
        val viewer = createViewerUser()
        val authedChannel = authenticatedChannel(viewer)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.listUsers(Empty.getDefaultInstance())
            }
            assertEquals(io.grpc.Status.PERMISSION_DENIED.code, ex.status.code)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `Admin can call ListUsers`() = runBlocking {
        val admin = createAdminUser()
        val authedChannel = authenticatedChannel(admin)
        try {
            val stub = AdminServiceGrpcKt.AdminServiceCoroutineStub(authedChannel)
            val response = stub.listUsers(Empty.getDefaultInstance())
            assertTrue(response.usersList.isNotEmpty())
        } finally {
            authedChannel.shutdownNow()
        }
    }

    // ========================================================================
    // Password-change gate
    // ========================================================================

    @Test
    fun `User with must_change_password can call ChangePassword`() = runBlocking {
        val user = createAdminUser(username = "mustchange", password = "Test1234!@#\$")
        user.must_change_password = true
        user.save()

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(authedChannel)
            val response = stub.changePassword(changePasswordRequest {
                currentPassword = "Test1234!@#\$"
                newPassword = "NewPass5678!@#\$"
            })
            assertFalse(response.accessToken.isEmpty)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `User with must_change_password can call GetProfile`() = runBlocking {
        val user = createViewerUser(username = "gatexempt")
        user.must_change_password = true
        user.save()

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(authedChannel)
            val response = stub.getProfile(Empty.getDefaultInstance())
            assertEquals("gatexempt", response.username)
            assertTrue(response.mustChangePassword)
        } finally {
            authedChannel.shutdownNow()
        }
    }

    @Test
    fun `User with must_change_password is blocked from HomeFeed`() = runBlocking {
        val user = createViewerUser(username = "gated")
        user.must_change_password = true
        user.save()

        val authedChannel = authenticatedChannel(user)
        try {
            val stub = CatalogServiceGrpcKt.CatalogServiceCoroutineStub(authedChannel)
            val ex = assertFailsWith<StatusException> {
                stub.homeFeed(Empty.getDefaultInstance())
            }
            assertEquals(io.grpc.Status.PERMISSION_DENIED.code, ex.status.code)
            assertTrue(ex.status.description?.contains("password_change_required") == true)
        } finally {
            authedChannel.shutdownNow()
        }
    }
}
