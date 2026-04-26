package net.stewart.mediamanager.grpc

import com.google.protobuf.ByteString
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.service.JwtService
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

    // ========================================================================
    // Cookie auth path (browser SPA — no JWT in JS by design)
    // ========================================================================

    @Test
    fun `mm_auth cookie authenticates a same-origin request`() = runBlocking {
        val user = createViewerUser(username = "cookieuser")
        // Same-origin: Origin matches the InProcess channel's authority,
        // which is "localhost" (gRPC default for in-process transport).
        val ch = cookieChannel(user, origin = "https://localhost")
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(ch)
            val response = stub.getProfile(Empty.getDefaultInstance())
            assertEquals(user.username, response.username)
        } finally {
            ch.shutdownNow()
        }
    }

    @Test
    fun `mm_auth cookie authenticates when no Origin is sent (native client)`() = runBlocking {
        val user = createViewerUser(username = "nativecookie")
        // Native clients (iOS / Roku / curl) don't send Origin. Cookie
        // auth still works — the CSRF gate is bypassed because there's
        // nothing to compare against and SameSite stops cross-origin
        // browsers from getting here in the first place.
        val ch = cookieChannel(user, origin = null)
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(ch)
            val response = stub.getProfile(Empty.getDefaultInstance())
            assertEquals(user.username, response.username)
        } finally {
            ch.shutdownNow()
        }
    }

    @Test
    fun `mm_auth cookie is rejected when Origin points at a different host (CSRF gate)`() = runBlocking {
        val user = createViewerUser(username = "csrfvictim")
        // Cross-origin: evil.com fires fetch() with credentials. Even if
        // a future config change lets the cookie reach the server, the
        // Origin mismatch shuts the auth path down. UNAUTHENTICATED, not
        // a partial success.
        val ch = cookieChannel(user, origin = "https://evil.example.com")
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(ch)
            val ex = assertFailsWith<StatusException> {
                stub.getProfile(Empty.getDefaultInstance())
            }
            assertEquals(io.grpc.Status.UNAUTHENTICATED.code, ex.status.code)
        } finally {
            ch.shutdownNow()
        }
    }

    @Test
    fun `Bearer auth ignores Origin (Bearer is CSRF-safe by construction)`() = runBlocking {
        val user = createViewerUser(username = "beareruser")
        // Bearer auth + a hostile Origin: the Origin only gates the cookie
        // path. JS-controlled clients that hold a JWT are by definition
        // not browser SPAs (the SPA can't see the JWT), so Origin is
        // irrelevant.
        val ch = authenticatedChannel(user, origin = "https://evil.example.com")
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(ch)
            val response = stub.getProfile(Empty.getDefaultInstance())
            assertEquals(user.username, response.username)
        } finally {
            ch.shutdownNow()
        }
    }

    @Test
    fun `mm_jwt cookie is no longer accepted on gRPC (legacy path removed)`() = runBlocking {
        // The SPA used to JS-set mm_jwt and the interceptor validated it
        // as a JWT. After the migration to the HttpOnly session cookie,
        // mm_jwt is no longer an authenticator on gRPC — the only way
        // for a JS-readable JWT to authenticate the user (XSS-vulnerable)
        // has been closed. iOS HLS still uses mm_jwt for HTTP servlets
        // (different path, different decorator).
        val user = createViewerUser(username = "jwtdenied")
        val tokenPair = JwtService.createTokenPair(user, "test")
        val ch = cookieChannel(user, origin = "https://localhost",
            cookieToken = "ignored")  // session token; we override the cookie below
        // Re-attach with a literal mm_jwt= cookie instead.
        val metadata = io.grpc.Metadata().apply {
            put(
                io.grpc.Metadata.Key.of("cookie", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
                "mm_jwt=${tokenPair.accessToken}"
            )
            put(
                io.grpc.Metadata.Key.of("origin", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
                "https://localhost"
            )
        }
        ch.shutdownNow()
        val ch2 = io.grpc.inprocess.InProcessChannelBuilder.forName("grpc-test-server")
            .directExecutor()
            .intercept(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(metadata))
            .build()
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(ch2)
            val ex = assertFailsWith<StatusException> {
                stub.getProfile(Empty.getDefaultInstance())
            }
            assertEquals(io.grpc.Status.UNAUTHENTICATED.code, ex.status.code)
        } finally {
            ch2.shutdownNow()
        }
    }

    @Test
    fun `Invalid mm_session cookie returns UNAUTHENTICATED`() = runBlocking {
        val user = createViewerUser(username = "fakecookie")
        // Pass a syntactically-valid but never-issued cookie value.
        val ch = cookieChannel(user, origin = "https://grpc-test-server",
            cookieToken = "00000000-0000-0000-0000-000000000000")
        try {
            val stub = ProfileServiceGrpcKt.ProfileServiceCoroutineStub(ch)
            val ex = assertFailsWith<StatusException> {
                stub.getProfile(Empty.getDefaultInstance())
            }
            assertEquals(io.grpc.Status.UNAUTHENTICATED.code, ex.status.code)
        } finally {
            ch.shutdownNow()
        }
    }

    // ========================================================================
    // Origin-parsing pure-function tests (no DB / no channel needed)
    // ========================================================================

    @Test
    fun `originPermitted accepts absent Origin`() {
        val interceptor = AuthInterceptor()
        assertTrue(interceptor.originPermitted(origin = null, authority = "host:8443"))
    }

    @Test
    fun `originPermitted accepts matching host+port`() {
        val interceptor = AuthInterceptor()
        assertTrue(interceptor.originPermitted(
            origin = "https://mediamanagergrpc.example.com:8443",
            authority = "mediamanagergrpc.example.com:8443"))
    }

    @Test
    fun `originPermitted rejects different host`() {
        val interceptor = AuthInterceptor()
        assertFalse(interceptor.originPermitted(
            origin = "https://evil.example.com",
            authority = "mediamanagergrpc.example.com:8443"))
    }

    @Test
    fun `originPermitted accepts same host on different ports (proxy rewrite tolerance)`() {
        // HAProxy / pfSense reverse proxies often rewrite the HTTP/2
        // :authority pseudo-header, dropping the public-facing port.
        // The CSRF gate compares hostnames only — same host means same
        // origin for our purposes; a different host is what mattered.
        val interceptor = AuthInterceptor()
        assertTrue(interceptor.originPermitted(
            origin = "https://host.example.com:9000",
            authority = "host.example.com:8443"))
        assertTrue(interceptor.originPermitted(
            origin = "https://host.example.com:8443",
            authority = "host.example.com"))
    }

    @Test
    fun `originPermitted is case-insensitive on host`() {
        val interceptor = AuthInterceptor()
        assertTrue(interceptor.originPermitted(
            origin = "https://Host.Example.COM:8443",
            authority = "host.example.com:8443"))
    }

    @Test
    fun `originPermitted rejects when authority is unknown`() {
        val interceptor = AuthInterceptor()
        // Fail closed — without an authority we can't prove same-origin.
        assertFalse(interceptor.originPermitted(
            origin = "https://anything.example.com",
            authority = null))
    }

    @Test
    fun `originPermitted rejects garbage Origin values`() {
        val interceptor = AuthInterceptor()
        assertFalse(interceptor.originPermitted(
            origin = "not a url",
            authority = "host:8443"))
        assertFalse(interceptor.originPermitted(
            origin = "",
            authority = "host:8443"))
    }
}
