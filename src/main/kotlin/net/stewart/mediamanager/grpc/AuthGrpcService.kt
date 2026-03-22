package net.stewart.mediamanager.grpc

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.LoginResult
import net.stewart.mediamanager.service.PasswordService
import net.stewart.mediamanager.service.RefreshResult
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

class AuthGrpcService : AuthServiceGrpcKt.AuthServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(AuthGrpcService::class.java)

    override suspend fun login(request: LoginRequest): TokenResponse {
        if (request.username.isBlank() || request.password.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Username and password required"))
        }

        // IP extraction: gRPC-Web over HTTP/1.1, IP not directly available in gRPC context.
        // Use "unknown" for now; the interceptor could extract it from transport attributes.
        val ip = "unknown"

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
        when (val result = JwtService.refresh(rawToken)) {
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
            JwtService.revoke(request.refreshToken.toStringUtf8())
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

        val pair = JwtService.createTokenPair(fresh, "")
        log.info("AUDIT: gRPC password changed by user '{}' — all sessions invalidated", fresh.username)

        return tokenResponse {
            accessToken = ByteString.copyFromUtf8(pair.accessToken)
            refreshToken = ByteString.copyFromUtf8(pair.refreshToken)
            expiresIn = pair.expiresIn
            tokenType = TokenType.TOKEN_TYPE_BEARER
        }
    }
}
