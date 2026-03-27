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
    @Volatile var privacyPolicyUrl: String? = null; private set
    @Volatile var iosTermsOfUseUrl: String? = null; private set
    @Volatile var webTermsOfUseUrl: String? = null; private set

    // Per-user cache of agreed versions
    data class AgreedVersions(val privacyPolicy: Int?, val termsOfUse: Int?)
    private val userCache = ConcurrentHashMap<Long, AgreedVersions>()

    /** Load required versions from app_config. Called on startup. */
    fun refresh() {
        val configs = AppConfig.findAll().associateBy { it.config_key }
        privacyPolicyVersion = configs["privacy_policy_version"]?.config_val?.toIntOrNull() ?: 0
        iosTermsOfUseVersion = configs["ios_terms_of_use_version"]?.config_val?.toIntOrNull() ?: 0
        webTermsOfUseVersion = configs["web_terms_of_use_version"]?.config_val?.toIntOrNull() ?: 0
        privacyPolicyUrl = configs["privacy_policy_url"]?.config_val?.takeIf { it.isNotBlank() }
        iosTermsOfUseUrl = configs["ios_terms_of_use_url"]?.config_val?.takeIf { it.isNotBlank() }
        webTermsOfUseUrl = configs["web_terms_of_use_url"]?.config_val?.takeIf { it.isNotBlank() }
        // Clear user cache — forces re-check against new versions
        userCache.clear()
        log.info("Legal requirements refreshed: pp={} ios_tou={} web_tou={}",
            privacyPolicyVersion, iosTermsOfUseVersion, webTermsOfUseVersion)
    }

    /** Check if a user's agreed versions meet current requirements. */
    fun isCompliant(userId: Long, isAdmin: Boolean): Boolean {
        // Admins are always compliant (they need access to configure URLs)
        if (isAdmin) return true
        // If no versions are configured, everyone is compliant
        if (privacyPolicyVersion == 0) return true

        val agreed = userCache.getOrPut(userId) {
            val user = AppUser.findById(userId) ?: return@getOrPut AgreedVersions(null, null)
            AgreedVersions(user.privacy_policy_version, user.terms_of_use_version)
        }

        if (agreed.privacyPolicy == null || agreed.privacyPolicy < privacyPolicyVersion) return false
        // Terms of use — check the higher of ios/web (user may have agreed via either platform)
        val requiredTou = maxOf(iosTermsOfUseVersion, webTermsOfUseVersion)
        if (requiredTou > 0 && (agreed.termsOfUse == null || agreed.termsOfUse < requiredTou)) return false
        return true
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
