package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table

@Table("app_config")
data class AppConfig(
    override var id: Long? = null,
    var config_key: String = "",
    var config_val: String? = null,
    var description: String? = null
) : KEntity<Long> {
    companion object : Dao<AppConfig, Long>(AppConfig::class.java)
}
