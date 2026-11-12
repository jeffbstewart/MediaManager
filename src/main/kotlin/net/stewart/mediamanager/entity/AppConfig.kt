package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

@Table("app_config")
data class AppConfig(
    override var id: Long? = null,
    var config_key: String = "",
    var config_val: String? = null,
    var description: String? = null
) : KEntity<Long> {
    companion object : Dao<AppConfig, Long>(AppConfig::class.java) {
        /** Settings key for the server's public base URL ("Public Server URL" in Settings). */
        const val PUBLIC_BASE_URL = "public_base_url"

        /** Pre-rename key, honored at read time so existing databases work unmigrated. */
        const val LEGACY_PUBLIC_BASE_URL = "roku_base_url"

        /**
         * The public base URL with any trailing slash trimmed, or null
         * when unset. Reads [PUBLIC_BASE_URL] and falls back to
         * [LEGACY_PUBLIC_BASE_URL].
         */
        fun publicBaseUrl(): String? {
            val configs = findAll()
            val value = configs.firstOrNull { it.config_key == PUBLIC_BASE_URL }
                ?.config_val?.takeIf { it.isNotBlank() }
                ?: configs.firstOrNull { it.config_key == LEGACY_PUBLIC_BASE_URL }
                    ?.config_val?.takeIf { it.isNotBlank() }
            return value?.trimEnd('/')
        }
    }
}
