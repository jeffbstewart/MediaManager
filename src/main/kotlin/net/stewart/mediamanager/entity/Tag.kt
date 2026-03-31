package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import java.time.LocalDateTime

data class Tag(
    override var id: Long? = null,
    var name: String = "",
    var bg_color: String = "#6B7280",
    var source_type: String = TagSourceType.MANUAL.name,
    var source_key: String? = null,
    var created_by: Long? = null,
    var created_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<Tag, Long>(Tag::class.java)

    /**
     * Returns a text color (white or dark) that contrasts well with [bg_color].
     * Uses the W3C luminance formula for perceived brightness.
     */
    fun textColor(): String {
        val hex = bg_color.removePrefix("#")
        if (hex.length != 6) return "#FFFFFF"
        val r = hex.substring(0, 2).toIntOrNull(16) ?: return "#FFFFFF"
        val g = hex.substring(2, 4).toIntOrNull(16) ?: return "#FFFFFF"
        val b = hex.substring(4, 6).toIntOrNull(16) ?: return "#FFFFFF"
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.5) "#1E1E1E" else "#FFFFFF"
    }
}
