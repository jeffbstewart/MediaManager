package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("discovered_file")
data class DiscoveredFile(
    override var id: Long? = null,
    var file_path: String = "",
    var file_name: String = "",
    var directory: String = "",
    var file_size_bytes: Long? = null,
    var media_format: String? = null,
    var media_type: String? = null,
    var parsed_title: String? = null,
    var parsed_year: Int? = null,
    var parsed_season: Int? = null,
    var parsed_episode: Int? = null,
    var parsed_episode_title: String? = null,
    var match_status: String = DiscoveredFileStatus.UNMATCHED.name,
    var matched_title_id: Long? = null,
    var matched_episode_id: Long? = null,
    var match_method: String? = null,
    var file_modified_at: LocalDateTime? = null,
    var discovered_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<DiscoveredFile, Long>(DiscoveredFile::class.java)
}
