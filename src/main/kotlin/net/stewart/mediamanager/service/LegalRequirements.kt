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
        privacyPolicyVersion = configs["privacy_policy_version"]?.config_val?.toIntOrNull() ?: 0
        iosTermsOfUseVersion = configs["ios_terms_of_use_version"]?.config_val?.toIntOrNull() ?: 0
        webTermsOfUseVersion = configs["web_terms_of_use_version"]?.config_val?.toIntOrNull() ?: 0
        androidTvTermsOfUseVersion = configs["android_tv_terms_of_use_version"]?.config_val?.toIntOrNull() ?: 0
        privacyPolicyUrl = configs["privacy_policy_url"]?.config_val?.takeIf { it.isNotBlank() }
        iosTermsOfUseUrl = configs["ios_terms_of_use_url"]?.config_val?.takeIf { it.isNotBlank() }
        webTermsOfUseUrl = configs["web_terms_of_use_url"]?.config_val?.takeIf { it.isNotBlank() }
        androidTvTermsOfUseUrl = configs["android_tv_terms_of_use_url"]?.config_val?.takeIf { it.isNotBlank() }
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
     * Returns the required TOU version for the given platform string.
     * Platform strings: "ios", "web", "android_tv". Unknown platforms fall back to the max.
     */
    fun touVersionForPlatform(platform: String?): Int = when (platform) {
        "ios" -> iosTermsOfUseVersion
        "web" -> webTermsOfUseVersion
        "android_tv" -> androidTvTermsOfUseVersion
        else -> maxOf(iosTermsOfUseVersion, maxOf(webTermsOfUseVersion, androidTvTermsOfUseVersion))
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
