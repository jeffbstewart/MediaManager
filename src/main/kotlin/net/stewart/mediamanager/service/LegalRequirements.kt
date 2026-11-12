package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of required legal document versions and user agreement status.
 * Avoids per-request DB queries for legal compliance checks.
 *
 * Required versions are loaded from app_config on startup and refreshed when
 * an admin updates a legal setting. User agreement versions are cached per-user
 * and evicted on version bump (forcing re-check from DB).
 */
object LegalRequirements {
    private val log = LoggerFactory.getLogger(LegalRequirements::class.java)

    // The app_config keys that hold the required versions and document
    // URLs. Persisted: each is a row key in every installed database, so a
    // rename needs a Flyway migration that renames the row; otherwise the
    // setting silently reads as "not configured".
    const val PRIVACY_POLICY_VERSION_KEY = "privacy_policy_version"
    const val PRIVACY_POLICY_URL_KEY = "privacy_policy_url"
    const val IOS_TERMS_OF_USE_VERSION_KEY = "ios_terms_of_use_version"
    const val IOS_TERMS_OF_USE_URL_KEY = "ios_terms_of_use_url"
    const val WEB_TERMS_OF_USE_VERSION_KEY = "web_terms_of_use_version"
    const val WEB_TERMS_OF_USE_URL_KEY = "web_terms_of_use_url"
    const val ANDROID_TV_TERMS_OF_USE_VERSION_KEY = "android_tv_terms_of_use_version"
    const val ANDROID_TV_TERMS_OF_USE_URL_KEY = "android_tv_terms_of_use_url"

    /** Every key above, in settings-page order; a save of one of these refreshes this cache. */
    val CONFIG_KEYS: Set<String> = setOf(
        PRIVACY_POLICY_URL_KEY, PRIVACY_POLICY_VERSION_KEY,
        IOS_TERMS_OF_USE_URL_KEY, IOS_TERMS_OF_USE_VERSION_KEY,
        WEB_TERMS_OF_USE_URL_KEY, WEB_TERMS_OF_USE_VERSION_KEY,
        ANDROID_TV_TERMS_OF_USE_URL_KEY, ANDROID_TV_TERMS_OF_USE_VERSION_KEY,
    )

    // Platform ids as clients send them in the gRPC x-client-platform header
    // (web-app client-platform.ts, android-tv GrpcClient.kt) and as the
    // ClientPlatform proto enum maps to them. Each platform has its own terms
    // of use; the privacy policy is shared. Wire contract: an installed
    // client keeps sending the id it was built with, so never rename one;
    // a new platform gets a new id.
    const val PLATFORM_IOS = "ios"
    const val PLATFORM_WEB = "web"
    const val PLATFORM_ANDROID_TV = "android_tv"

    // Required versions from app_config
    @Volatile var privacyPolicyVersion: Int = 0; private set
    @Volatile var iosTermsOfUseVersion: Int = 0; private set
    @Volatile var webTermsOfUseVersion: Int = 0; private set
    @Volatile var androidTvTermsOfUseVersion: Int = 0; private set
    @Volatile var privacyPolicyUrl: String? = null; private set
    @Volatile var iosTermsOfUseUrl: String? = null; private set
    @Volatile var webTermsOfUseUrl: String? = null; private set
    @Volatile var androidTvTermsOfUseUrl: String? = null; private set

    // Per-user cache of agreed versions
    data class AgreedVersions(val privacyPolicy: Int?, val termsOfUse: Int?)
    private val userCache = ConcurrentHashMap<Long, AgreedVersions>()

    /** Load required versions from app_config. Called on startup. */
    fun refresh() {
        val configs = AppConfig.findAll().associateBy { it.config_key }
        privacyPolicyVersion = configs[PRIVACY_POLICY_VERSION_KEY]?.config_val?.toIntOrNull() ?: 0
        iosTermsOfUseVersion = configs[IOS_TERMS_OF_USE_VERSION_KEY]?.config_val?.toIntOrNull() ?: 0
        webTermsOfUseVersion = configs[WEB_TERMS_OF_USE_VERSION_KEY]?.config_val?.toIntOrNull() ?: 0
        androidTvTermsOfUseVersion = configs[ANDROID_TV_TERMS_OF_USE_VERSION_KEY]?.config_val?.toIntOrNull() ?: 0
        privacyPolicyUrl = configs[PRIVACY_POLICY_URL_KEY]?.config_val?.takeIf { it.isNotBlank() }
        iosTermsOfUseUrl = configs[IOS_TERMS_OF_USE_URL_KEY]?.config_val?.takeIf { it.isNotBlank() }
        webTermsOfUseUrl = configs[WEB_TERMS_OF_USE_URL_KEY]?.config_val?.takeIf { it.isNotBlank() }
        androidTvTermsOfUseUrl = configs[ANDROID_TV_TERMS_OF_USE_URL_KEY]?.config_val?.takeIf { it.isNotBlank() }
        // Clear user cache — forces re-check against new versions
        userCache.clear()
        log.info("Legal requirements refreshed: pp={} ios_tou={} web_tou={} atv_tou={}",
            privacyPolicyVersion, iosTermsOfUseVersion, webTermsOfUseVersion, androidTvTermsOfUseVersion)
    }

    /**
     * Check if a user's agreed versions meet current requirements.
     *
     * @param requiredTou Override the required terms-of-use version. Callers that know the
     *   client platform should pass the platform-specific version (e.g. [webTermsOfUseVersion]
     *   for REST/web, [iosTermsOfUseVersion] for iOS, [androidTvTermsOfUseVersion] for Android TV).
     *   When omitted, defaults to the max of all platform versions — safe for contexts where
     *   the platform is unknown.
     */
    fun isCompliant(
        userId: Long,
        isAdmin: Boolean,
        requiredTou: Int = maxOf(iosTermsOfUseVersion, maxOf(webTermsOfUseVersion, androidTvTermsOfUseVersion))
    ): Boolean {
        // Admins are always compliant (they need access to configure URLs)
        if (isAdmin) return true
        // If no versions are configured, everyone is compliant
        if (privacyPolicyVersion == 0) return true

        val agreed = userCache.getOrPut(userId) {
            val user = AppUser.findById(userId) ?: return@getOrPut AgreedVersions(null, null)
            AgreedVersions(user.privacy_policy_version, user.terms_of_use_version)
        }

        if (agreed.privacyPolicy == null || agreed.privacyPolicy < privacyPolicyVersion) return false
        if (requiredTou > 0 && (agreed.termsOfUse == null || agreed.termsOfUse < requiredTou)) return false
        return true
    }

    /**
     * Compliance check for paired-device (device token) callers such as
     * Roku. There is no device-specific terms-of-use document and no way to
     * accept terms on the device, so only the platform-independent privacy
     * policy is enforced; the user accepts it from any other client.
     * Admins are exempt, as in [isCompliant].
     */
    fun isCompliantForDevice(user: AppUser): Boolean =
        isCompliant(user.id!!, user.isAdmin(), requiredTou = 0)

    /**
     * Returns the required TOU version for the given platform string.
     * Platform ids are [PLATFORM_IOS], [PLATFORM_WEB], and [PLATFORM_ANDROID_TV]; an unknown one falls back to the max.
     */
    fun touVersionForPlatform(platform: String?): Int = when (platform) {
        PLATFORM_IOS -> iosTermsOfUseVersion
        PLATFORM_WEB -> webTermsOfUseVersion
        PLATFORM_ANDROID_TV -> androidTvTermsOfUseVersion
        else -> maxOf(iosTermsOfUseVersion, maxOf(webTermsOfUseVersion, androidTvTermsOfUseVersion))
    }

    /** The terms-of-use URL for a platform id, or null when the platform is unknown or has no URL. */
    fun touUrlForPlatform(platform: String?): String? = when (platform) {
        PLATFORM_IOS -> iosTermsOfUseUrl
        PLATFORM_WEB -> webTermsOfUseUrl
        PLATFORM_ANDROID_TV -> androidTvTermsOfUseUrl
        else -> null
    }

    /** Update the cache after a user agrees to new versions. */
    fun recordAgreement(userId: Long, ppVersion: Int?, touVersion: Int?) {
        userCache[userId] = AgreedVersions(ppVersion, touVersion)
    }

    /** Evict a user from the cache (e.g., on logout). */
    fun evict(userId: Long) {
        userCache.remove(userId)
    }
}
