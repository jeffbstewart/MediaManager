package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("local_image")
data class LocalImage(
    override var id: String? = null,
    var source_type: String = "UPLOAD",
    var content_type: String = "image/jpeg",
    var created_at: LocalDateTime? = null
) : KEntity<String> {
    companion object : Dao<LocalImage, String>(LocalImage::class.java)
}

enum class LocalImageSourceType {
    UPLOAD, FRAME_EXTRACT
}
