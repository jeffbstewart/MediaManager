package net.stewart.mediamanager.grpc

import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.JwtService

class InfoGrpcService : InfoServiceGrpcKt.InfoServiceCoroutineImplBase() {

    override suspend fun discover(request: DiscoverRequest): DiscoverResponse = discoverResponse {
        apiVersions.add(1)
        authMethods.add(AuthMethod.AUTH_METHOD_JWT)
        serverFingerprint = JwtService.getSigningKeyFingerprint()
        setupRequired = !AuthService.hasUsers()

        val configs = AppConfig.findAll().associateBy { it.config_key }
        configs["roku_base_url"]?.config_val?.takeIf { it.isNotBlank() }?.let { secureUrl = it }

        // Legal documents — privacy policy is shared, terms are platform-specific
        val ppUrl = configs["privacy_policy_url"]?.config_val?.takeIf { it.isNotBlank() }
        val ppVersion = configs["privacy_policy_version"]?.config_val?.toIntOrNull()
        val touKey = when (request.platform) {
            ClientPlatform.CLIENT_PLATFORM_IOS -> "ios_terms_of_use"
            else -> "web_terms_of_use"  // UNKNOWN defaults to web
        }
        val touUrl = configs["${touKey}_url"]?.config_val?.takeIf { it.isNotBlank() }
        val touVersion = configs["${touKey}_version"]?.config_val?.toIntOrNull()

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
