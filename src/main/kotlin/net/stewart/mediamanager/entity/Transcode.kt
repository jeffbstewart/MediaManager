package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import java.time.LocalDateTime

data class Transcode(
    override var id: Long? = null,
    var title_id: Long = 0,
    var media_item_id: Long? = null,
    var episode_id: Long? = null,
    var file_path: String? = null,
    var file_size_bytes: Long? = null,
    var status: String = TranscodeStatus.NOT_STARTED.name,
    var media_format: String? = null,
    var match_method: String? = null,
    var notes: String? = null,
    var retranscode_requested: Boolean = false,
    var for_mobile_available: Boolean = false,
    var file_modified_at: LocalDateTime? = null,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<Transcode, Long>(Transcode::class.java)
}
