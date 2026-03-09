package net.stewart.mediamanager

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.createTagBadge
import net.stewart.mediamanager.service.TagService

/**
 * Viewer-accessible tag browser. Shows all tags as clickable badges
 * that navigate to the tag detail view. Separate from TagManagementView
 * (admin-only, route "tags") which handles tag CRUD.
 */
@Route(value = "content/tags", layout = MainLayout::class)
@PageTitle("Tags")
class TagsBrowseView : VerticalLayout() {

    init {
        isPadding = true
        isSpacing = true
        width = "100%"

        val tags = TagService.getAllTags()

        if (tags.isEmpty()) {
            add(Span("No tags yet.").apply {
                style.set("color", "rgba(255,255,255,0.5)")
            })
        } else {
            val titleCountByTag = tags.associate { tag ->
                tag.id!! to TagService.getTitleIdsForTags(setOf(tag.id!!)).size
            }

            add(Span("${tags.size} tags").apply {
                style.set("color", "rgba(255,255,255,0.5)")
                style.set("font-size", "var(--lumo-font-size-s)")
            })

            val tagGrid = Div().apply {
                style.set("display", "flex")
                style.set("flex-wrap", "wrap")
                style.set("gap", "var(--lumo-space-s)")
                style.set("width", "100%")
            }

            for (tag in tags) {
                val count = titleCountByTag[tag.id] ?: 0
                val badge = createClickableTagBadge(tag, count)
                tagGrid.add(badge)
            }

            add(tagGrid)
        }
    }

    private fun createClickableTagBadge(tag: Tag, titleCount: Int): Div {
        return Div().apply {
            style.set("cursor", "pointer")
            style.set("display", "inline-flex")
            style.set("align-items", "center")
            style.set("gap", "var(--lumo-space-xs)")

            val badge = createTagBadge(tag).apply {
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("padding", "6px 14px")
            }
            add(badge)

            add(Span("($titleCount)").apply {
                style.set("color", "rgba(255,255,255,0.4)")
                style.set("font-size", "var(--lumo-font-size-xs)")
            })

            element.addEventListener("click") {
                ui.ifPresent { it.navigate("tag/${tag.id}") }
            }
        }
    }
}
