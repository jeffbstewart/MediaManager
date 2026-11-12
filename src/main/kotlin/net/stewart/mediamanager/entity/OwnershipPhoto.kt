package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("ownership_photo")
data class OwnershipPhoto(
    override var id: String? = null,
    var media_item_id: Long? = null,
    var upc: String? = null,
    var content_type: String = "image/jpeg",
    var orientation: Int = 1,
    var disk_path: String? = null,
    var captured_at: LocalDateTime? = null
) : KEntity<String> {
    companion object : Dao<OwnershipPhoto, String>(OwnershipPhoto::class.java)
}
