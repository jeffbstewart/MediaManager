package net.stewart.mediamanager.grpc

import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.LegalRequirements

class InfoGrpcService : InfoServiceGrpcKt.InfoServiceCoroutineImplBase() {

    override suspend fun discover(request: DiscoverRequest): DiscoverResponse = discoverResponse {
        apiVersions.add(1)
        authMethods.add(AuthMethod.AUTH_METHOD_JWT)
        serverFingerprint = JwtService.getSigningKeyFingerprint()
        setupRequired = !AuthService.hasUsers()

        AppConfig.publicBaseUrl()?.let { secureUrl = it }

        // Legal documents — privacy policy is shared, terms are platform-specific
        // (an unknown platform is treated as the web).
        val platform = request.platform.toPlatformId() ?: LegalRequirements.PLATFORM_WEB
        val ppUrl = LegalRequirements.privacyPolicyUrl
        val ppVersion = LegalRequirements.privacyPolicyVersion.takeIf { it > 0 }
        val touUrl = LegalRequirements.touUrlForPlatform(platform)
        val touVersion = LegalRequirements.touVersionForPlatform(platform).takeIf { it > 0 }

        if (ppUrl != null || touUrl != null) {
            legal = legalDocumentInfo {
                ppUrl?.let { privacyPolicyUrl = it }
                ppVersion?.let { privacyPolicyVersion = it }
                touUrl?.let { termsOfUseUrl = it }
                touVersion?.let { termsOfUseVersion = it }
            }
        }
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
