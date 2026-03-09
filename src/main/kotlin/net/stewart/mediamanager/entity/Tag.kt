package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
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

/** Creates a styled pill-shaped badge for a tag. */
fun createTagBadge(tag: Tag): Div = Div().apply {
    style.set("background-color", tag.bg_color)
    style.set("color", tag.textColor())
    style.set("border-radius", "9999px")
    style.set("font-weight", "500")
    style.set("white-space", "nowrap")
    style.set("display", "inline-flex")
    style.set("align-items", "center")
    style.set("padding", "2px 10px")
    style.set("font-size", "var(--lumo-font-size-xs)")
    add(Span(tag.name))
}
