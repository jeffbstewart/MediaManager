package net.stewart.mediamanager.grpc

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.service.LoginResult
import net.stewart.mediamanager.service.PasswordService
import net.stewart.mediamanager.service.RefreshResult
import net.stewart.mediamanager.service.WebAuthnService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

class AuthGrpcService : AuthServiceGrpcKt.AuthServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(AuthGrpcService::class.java)

    companion object {
        private const val PASSKEY_MAX_PER_MINUTE = 10
        private const val WINDOW_MS = 60_000L
    }

    private val passkeyRateLimit = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

    override suspend fun login(request: LoginRequest): TokenResponse {
        if (request.username.isBlank() || request.password.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Username and password required"))
        }

        val ip = currentClientIp()

        when (val result = AuthService.login(request.username, request.password, ip)) {
            is LoginResult.Success -> {
                val deviceName = if (request.hasDeviceName()) request.deviceName else ""
                val pair = JwtService.createTokenPair(result.user, deviceName)
                return tokenResponse {
                    accessToken = ByteString.copyFromUtf8(pair.accessToken)
                    refreshToken = ByteString.copyFromUtf8(pair.refreshToken)
                    expiresIn = pair.expiresIn
                    tokenType = TokenType.TOKEN_TYPE_BEARER
                    passwordChangeRequired = result.user.must_change_password
                }
            }
            is LoginResult.Failed -> {
                // Generic error to prevent username enumeration
                throw StatusException(Status.UNAUTHENTICATED.withDescription("Invalid credentials"))
            }
            is LoginResult.RateLimited -> {
                throw StatusException(
                    Status.RESOURCE_EXHAUSTED.withDescription(
                        "Rate limited. Retry after ${result.retryAfterSeconds} seconds"
                    )
                )
            }
        }
    }

    override suspend fun refresh(request: RefreshRequest): TokenResponse {
        if (request.refreshToken.isEmpty) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Refresh token required"))
        }

        val rawToken = request.refreshToken.toStringUtf8()
        // Wrap in try-catch to prevent token leakage in exception stack traces
        val result = try {
            JwtService.refresh(rawToken)
        } catch (e: StatusException) {
            throw e
        } catch (e: Exception) {
            throw StatusException(Status.INTERNAL.withDescription("Token refresh failed"))
        }
        when (result) {
            is RefreshResult.Success -> {
                return tokenResponse {
                    accessToken = ByteString.copyFromUtf8(result.tokenPair.accessToken)
                    refreshToken = ByteString.copyFromUtf8(result.tokenPair.refreshToken)
                    expiresIn = result.tokenPair.expiresIn
                    tokenType = TokenType.TOKEN_TYPE_BEARER
                }
            }
            is RefreshResult.InvalidToken -> {
                throw StatusException(Status.UNAUTHENTICATED.withDescription("Invalid token"))
            }
            is RefreshResult.FamilyRevoked -> {
                throw StatusException(Status.UNAUTHENTICATED.withDescription("Token revoked"))
            }
        }
    }

    override suspend fun revoke(request: RevokeRequest): RevokeResponse {
        if (!request.refreshToken.isEmpty) {
            try {
                JwtService.revoke(request.refreshToken.toStringUtf8())
            } catch (_: Exception) {
                // Swallow — never reveal token validity
            }
        }
        // Always return success to prevent token existence probing
        return revokeResponse { revoked = true }
    }

    override suspend fun changePassword(request: ChangePasswordRequest): TokenResponse {
        val user = currentUser()

        if (request.currentPassword.isBlank() || request.newPassword.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Both passwords required"))
        }

        if (!PasswordService.verify(request.currentPassword, user.password_hash)) {
            throw StatusException(Status.UNAUTHENTICATED.withDescription("Invalid current password"))
        }

        val violations = PasswordService.validate(request.newPassword, user.username, user.password_hash)
        if (violations.isNotEmpty()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(violations.first()))
        }

        val fresh = AppUser.findById(user.id!!)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("User not found"))
        fresh.password_hash = PasswordService.hash(request.newPassword)
        fresh.must_change_password = false
        fresh.updated_at = LocalDateTime.now()
        fresh.save()

        AuthService.invalidateUserSessions(fresh.id!!)
        WebAuthnService.deleteAllCredentials(fresh.id!!)

        val pair = JwtService.createTokenPair(fresh, "")
        log.info("AUDIT: gRPC password changed by user '{}' — all sessions and passkeys invalidated", fresh.username)

        return tokenResponse {
            accessToken = ByteString.copyFromUtf8(pair.accessToken)
            refreshToken = ByteString.copyFromUtf8(pair.refreshToken)
            expiresIn = pair.expiresIn
            tokenType = TokenType.TOKEN_TYPE_BEARER
        }
    }

    override suspend fun createFirstUser(request: CreateFirstUserRequest): TokenResponse {
        if (request.username.isBlank() || request.password.isBlank() || request.displayName.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Username, password, and display name required"))
        }

        val violations = PasswordService.validate(request.password, request.username)
        if (violations.isNotEmpty()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(violations.first()))
        }

        val now = LocalDateTime.now()
        val user = try {
            JdbiOrm.jdbi().inTransaction<AppUser, Exception> { handle ->
                val count = handle.createQuery("SELECT COUNT(*) FROM app_user")
                    .mapTo(Int::class.java).one()
                if (count > 0) throw IllegalStateException("Setup already complete")
                val u = AppUser(
                    username = request.username,
                    display_name = request.displayName,
                    password_hash = PasswordService.hash(request.password),
                    access_level = 2, // Admin
                    created_at = now,
                    updated_at = now
                )
                u.save()
                AuthService.invalidateHasUsersCache()
                u
            }
        } catch (_: IllegalStateException) {
            throw StatusException(Status.ALREADY_EXISTS.withDescription("Setup already complete — users exist"))
        }

        log.info("AUDIT: First admin '{}' created via gRPC setup", request.username)

        val deviceName = if (request.hasDeviceName()) request.deviceName else ""
        val pair = JwtService.createTokenPair(user, deviceName)
        return tokenResponse {
            accessToken = ByteString.copyFromUtf8(pair.accessToken)
            refreshToken = ByteString.copyFromUtf8(pair.refreshToken)
            expiresIn = pair.expiresIn
            tokenType = TokenType.TOKEN_TYPE_BEARER
        }
    }

    // --- Legal agreement ---

    override suspend fun getLegalStatus(request: GetLegalStatusRequest): LegalStatusResponse {
        val user = currentUser()
        val platform = platformString(request.platform)
        val requiredTou = LegalRequirements.touVersionForPlatform(platform)
        val compliant = LegalRequirements.isCompliant(user.id!!, user.isAdmin(), requiredTou)

        return legalStatusResponse {
            this.compliant = compliant
            requiredPrivacyPolicyVersion = LegalRequirements.privacyPolicyVersion
            requiredTermsOfUseVersion = requiredTou
            user.privacy_policy_version?.let { agreedPrivacyPolicyVersion = it }
            user.terms_of_use_version?.let { agreedTermsOfUseVersion = it }
            LegalRequirements.privacyPolicyUrl?.let { privacyPolicyUrl = it }
            touUrlForPlatform(platform)?.let { termsOfUseUrl = it }
        }
    }

    override suspend fun agreeToTerms(request: AgreeToTermsRequest): AgreeToTermsResponse {
        val user = currentUser()
        val platform = platformString(request.platform)
        val requiredPp = LegalRequirements.privacyPolicyVersion
        val requiredTou = LegalRequirements.touVersionForPlatform(platform)

        if (requiredPp > 0 && request.privacyPolicyVersion != requiredPp) {
            throw StatusException(
                Status.INVALID_ARGUMENT.withDescription("Privacy policy version must be $requiredPp")
            )
        }
        if (requiredTou > 0 && request.termsOfUseVersion != requiredTou) {
            throw StatusException(
                Status.INVALID_ARGUMENT.withDescription("Terms of use version must be $requiredTou")
            )
        }

        val fresh = AppUser.findById(user.id!!)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("User not found"))

        val now = LocalDateTime.now()
        if (requiredPp > 0 && request.privacyPolicyVersion > 0 &&
            (fresh.privacy_policy_version == null || request.privacyPolicyVersion > fresh.privacy_policy_version!!)) {
            fresh.privacy_policy_version = request.privacyPolicyVersion
            fresh.privacy_policy_accepted_at = now
        }
        if (requiredTou > 0 && request.termsOfUseVersion > 0 &&
            (fresh.terms_of_use_version == null || request.termsOfUseVersion > fresh.terms_of_use_version!!)) {
            fresh.terms_of_use_version = request.termsOfUseVersion
            fresh.terms_of_use_accepted_at = now
        }
        fresh.save()

        LegalRequirements.recordAgreement(user.id!!, fresh.privacy_policy_version, fresh.terms_of_use_version)
        log.info("AUDIT: User '{}' agreed to legal terms via gRPC (pp={}, tou={})",
            fresh.username, request.privacyPolicyVersion, request.termsOfUseVersion)

        return agreeToTermsResponse { ok = true }
    }

    // --- Passkey authentication ---

    override suspend fun getPasskeyAuthenticationOptions(
        request: GetPasskeyAuthenticationOptionsRequest
    ): PasskeyOptionsResponse {
        enforcePasskeyRateLimit()
        if (!WebAuthnService.isAvailable()) {
            throw StatusException(Status.UNAVAILABLE.withDescription("Passkeys not configured"))
        }
        val options = WebAuthnService.generateAuthenticationOptions()
        return passkeyOptionsResponse {
            signedChallenge = options.signedChallenge
            optionsJson = options.options.toString()
        }
    }

    override suspend fun authenticateWithPasskey(request: AuthenticateWithPasskeyRequest): TokenResponse {
        enforcePasskeyRateLimit()
        if (request.signedChallenge.isBlank() || request.credentialId.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Challenge and credential ID required"))
        }

        val user = try {
            WebAuthnService.verifyAuthentication(
                signedChallenge = request.signedChallenge,
                credentialId = request.credentialId,
                clientDataJSON = request.clientDataJson,
                authenticatorData = request.authenticatorData,
                signature = request.signature,
                userHandle = if (request.hasUserHandle()) request.userHandle else null
            )
        } catch (e: IllegalArgumentException) {
            log.warn("gRPC passkey authentication failed: {}", e.message)
            throw StatusException(Status.UNAUTHENTICATED.withDescription("Passkey authentication failed"))
        } catch (e: IllegalStateException) {
            throw StatusException(Status.UNAVAILABLE.withDescription("Passkeys not available"))
        }

        if (user.locked) {
            throw StatusException(Status.UNAUTHENTICATED.withDescription("Account locked"))
        }

        val deviceName = if (request.hasDeviceName()) request.deviceName else "passkey"
        val pair = JwtService.createTokenPair(user, deviceName)
        return tokenResponse {
            accessToken = ByteString.copyFromUtf8(pair.accessToken)
            refreshToken = ByteString.copyFromUtf8(pair.refreshToken)
            expiresIn = pair.expiresIn
            tokenType = TokenType.TOKEN_TYPE_BEARER
            passwordChangeRequired = user.must_change_password
        }
    }

    // --- Helpers ---

    private fun platformString(platform: ClientPlatform): String = when (platform) {
        ClientPlatform.CLIENT_PLATFORM_IOS -> "ios"
        ClientPlatform.CLIENT_PLATFORM_WEB -> "web"
        ClientPlatform.CLIENT_PLATFORM_ANDROID_TV -> "android_tv"
        else -> "unknown"
    }

    private fun touUrlForPlatform(platform: String): String? = when (platform) {
        "ios" -> LegalRequirements.iosTermsOfUseUrl
        "web" -> LegalRequirements.webTermsOfUseUrl
        "android_tv" -> LegalRequirements.androidTvTermsOfUseUrl
        else -> null
    }

    /** Per-IP rolling-window rate limit for unauthenticated passkey endpoints. */
    private fun enforcePasskeyRateLimit() {
        val ip = currentClientIp()
        val now = System.currentTimeMillis()
        val timestamps = passkeyRateLimit.computeIfAbsent(ip) { ConcurrentLinkedDeque() }
        while (timestamps.peekFirst()?.let { it < now - WINDOW_MS } == true) {
            timestamps.pollFirst()
        }
        if (timestamps.size >= PASSKEY_MAX_PER_MINUTE) {
            log.warn("Passkey gRPC rate limit exceeded for IP {}", ip)
            throw StatusException(Status.RESOURCE_EXHAUSTED.withDescription("Too many requests. Retry after 60 seconds"))
        }
        timestamps.addLast(now)
    }
}
