package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.DeviceToken
import net.stewart.mediamanager.entity.RefreshToken
import net.stewart.mediamanager.entity.SessionToken
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.PairingService
import net.stewart.mediamanager.service.WebAuthnService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset

class ProfileGrpcService : ProfileServiceGrpcKt.ProfileServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(ProfileGrpcService::class.java)

    override suspend fun getProfile(request: Empty): ProfileResponse {
        return currentUser().toProfileResponse()
    }

    override suspend fun updateTvQuality(request: UpdateTvQualityRequest): Empty {
        val user = currentUser()
        val fresh = AppUser.findById(user.id!!)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("User not found"))
        fresh.live_tv_min_quality = when (request.minQuality) {
            Quality.QUALITY_SD -> 1
            Quality.QUALITY_FHD -> 2
            Quality.QUALITY_UHD -> 3
            else -> 1
        }
        fresh.updated_at = LocalDateTime.now()
        fresh.save()
        return Empty.getDefaultInstance()
    }

    override suspend fun listSessions(request: Empty): SessionListResponse {
        val user = currentUser()
        val userId = user.id!!

        val browserSessions = SessionToken.findAll()
            .filter { it.user_id == userId && it.expires_at.isAfter(LocalDateTime.now()) }
            .map { it.toProto(isCurrent = false) }

        val appSessions = RefreshToken.findAll()
            .filter { it.user_id == userId && !it.revoked && it.expires_at.isAfter(LocalDateTime.now()) }
            .map { it.toProto(isCurrent = false) }

        val deviceSessions = DeviceToken.findAll()
            .filter { it.user_id == userId }
            .map { it.toProto() }

        return sessionListResponse {
            sessions.addAll(browserSessions + appSessions + deviceSessions)
        }
    }

    override suspend fun deleteSession(request: DeleteSessionRequest): Empty {
        val user = currentUser()
        when (request.type) {
            SessionType.SESSION_TYPE_BROWSER -> {
                val session = SessionToken.findById(request.sessionId)
                if (session != null && session.user_id == user.id) {
                    session.delete()
                }
            }
            SessionType.SESSION_TYPE_APP -> {
                val token = RefreshToken.findById(request.sessionId)
                if (token != null && token.user_id == user.id) {
                    token.revoked = true
                    token.save()
                }
            }
            SessionType.SESSION_TYPE_DEVICE -> {
                val deviceToken = DeviceToken.findById(request.sessionId)
                if (deviceToken != null && deviceToken.user_id == user.id) {
                    deviceToken.delete()
                }
            }
            else -> throw StatusException(Status.INVALID_ARGUMENT.withDescription("Invalid session type"))
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun deleteOtherSessions(request: Empty): Empty {
        val user = currentUser()
        AuthService.invalidateUserSessions(user.id!!)
        return Empty.getDefaultInstance()
    }

    // --- Passkey management ---

    override suspend fun getPasskeyRegistrationOptions(request: Empty): PasskeyOptionsResponse {
        val user = currentUser()
        try {
            val options = WebAuthnService.generateRegistrationOptions(user)
            return passkeyOptionsResponse {
                signedChallenge = options.signedChallenge
                optionsJson = options.options.toString()
            }
        } catch (e: IllegalStateException) {
            throw StatusException(Status.UNAVAILABLE.withDescription("Passkeys not available"))
        }
    }

    override suspend fun registerPasskey(request: RegisterPasskeyRequest): PasskeyCredentialInfo {
        val user = currentUser()

        if (request.signedChallenge.isBlank() || request.credentialId.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Challenge and credential ID required"))
        }

        val displayName = if (request.hasDisplayName()) request.displayName else "Passkey"
        val transports = if (request.hasTransports()) request.transports else null

        val credential = try {
            WebAuthnService.verifyRegistration(
                signedChallenge = request.signedChallenge,
                clientDataJSON = request.clientDataJson,
                attestationObject = request.attestationObject,
                credentialId = request.credentialId,
                transports = transports,
                displayName = displayName,
                userId = user.id!!
            )
        } catch (e: IllegalArgumentException) {
            log.warn("gRPC passkey registration failed for user '{}': {}", user.username, e.message)
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("Passkey registration failed"))
        } catch (e: IllegalStateException) {
            throw StatusException(Status.UNAVAILABLE.withDescription("Passkeys not available"))
        }

        return credential.toProto()
    }

    override suspend fun listPasskeys(request: Empty): ListPasskeysResponse {
        val user = currentUser()
        val creds = WebAuthnService.listCredentials(user.id!!)
        return listPasskeysResponse {
            passkeys.addAll(creds.map { it.toProto() })
        }
    }

    override suspend fun deletePasskey(request: DeletePasskeyRequest): Empty {
        val user = currentUser()
        val deleted = WebAuthnService.deleteCredential(request.passkeyId, user.id!!)
        if (!deleted) {
            throw StatusException(Status.NOT_FOUND.withDescription("Passkey not found"))
        }
        return Empty.getDefaultInstance()
    }
}
