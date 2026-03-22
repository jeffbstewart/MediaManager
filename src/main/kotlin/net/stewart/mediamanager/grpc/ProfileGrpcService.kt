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
import java.time.LocalDateTime

class ProfileGrpcService : ProfileServiceGrpcKt.ProfileServiceCoroutineImplBase() {

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
}
