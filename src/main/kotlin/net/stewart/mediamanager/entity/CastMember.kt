package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("cast_member")
data class CastMember(
    override var id: Long? = null,
    var title_id: Long = 0,
    var tmdb_person_id: Int = 0,
    var name: String = "",
    var character_name: String? = null,
    var profile_path: String? = null,
    var cast_order: Int = 0,
    var headshot_cache_id: String? = null,
    var popularity: Double? = null,
    var popularity_refreshed_at: LocalDateTime? = null,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<CastMember, Long>(CastMember::class.java)
}
