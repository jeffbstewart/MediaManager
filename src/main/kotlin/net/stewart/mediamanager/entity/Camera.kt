package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import net.stewart.mediamanager.service.UriCredentialRedactor
import java.time.LocalDateTime

@Table("camera")
data class Camera(
    override var id: Long? = null,
    var name: String = "",
    var display_order: Int = 0,
    var rtsp_url: String = "",
    var snapshot_url: String = "",
    var go2rtc_name: String = "",
    var enabled: Boolean = true,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<Camera, Long>(Camera::class.java)

    override fun toString() = "Camera(id=$id, name=$name, url=${UriCredentialRedactor.redact(rtsp_url)})"
}
