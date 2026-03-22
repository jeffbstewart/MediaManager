package net.stewart.mediamanager.grpc

import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.JwtService

class InfoGrpcService : InfoServiceGrpcKt.InfoServiceCoroutineImplBase() {

    override suspend fun discover(request: Empty): DiscoverResponse = discoverResponse {
        apiVersions.add(1)
        authMethods.add(AuthMethod.AUTH_METHOD_JWT)
        serverFingerprint = JwtService.getSigningKeyFingerprint()
        AppConfig.findAll()
            .firstOrNull { it.config_key == "roku_base_url" }
            ?.config_val?.takeIf { it.isNotBlank() }
            ?.let { secureUrl = it }
    }

    override suspend fun getInfo(request: Empty): InfoResponse {
        val user = currentUser()
        val titleCount = Title.count().toInt()
        return infoResponse {
            serverVersion = "1.0.0"
            capabilities.addAll(listOf(
                Capability.CAPABILITY_CATALOG,
                Capability.CAPABILITY_STREAMING,
                Capability.CAPABILITY_WISHLIST,
                Capability.CAPABILITY_PLAYBACK_PROGRESS,
                Capability.CAPABILITY_DOWNLOADS
            ))
            this.titleCount = titleCount
            this.user = serverUserInfo {
                id = user.id!!
                username = user.username
                user.display_name.takeIf { it.isNotBlank() }?.let { displayName = it }
                isAdmin = user.isAdmin()
            }
        }
    }
}
