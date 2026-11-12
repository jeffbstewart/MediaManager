package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("transcode_lease")
data class TranscodeLease(
    override var id: Long? = null,
    var transcode_id: Long = 0,
    var buddy_name: String = "",
    var relative_path: String = "",
    var file_size_bytes: Long? = null,
    var claimed_at: LocalDateTime? = null,
    var expires_at: LocalDateTime? = null,
    var last_progress_at: LocalDateTime? = null,
    var progress_percent: Int = 0,
    var status: String = LeaseStatus.CLAIMED.name,
    var encoder: String? = null,
    var error_message: String? = null,
    var completed_at: LocalDateTime? = null,
    var lease_type: String = LeaseType.TRANSCODE.name
) : KEntity<Long> {
    companion object : Dao<TranscodeLease, Long>(TranscodeLease::class.java)
}
