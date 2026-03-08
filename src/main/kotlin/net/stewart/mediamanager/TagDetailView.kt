package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.createTagBadge
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.TagService

@Route("tag/:tagId", layout = MainLayout::class)
@PageTitle("Tag Detail")
class TagDetailView : VerticalLayout(), BeforeEnterObserver {

    private var currentTag: Tag? = null

    override fun beforeEnter(event: BeforeEnterEvent) {
        val tagId = event.routeParameters.get("tagId").orElse(null)?.toLongOrNull()
        if (tagId == null) {
            event.forwardTo("")
            return
        }
        val tag = Tag.findById(tagId)
        if (tag == null) {
            event.forwardTo("")
            return
        }
        currentTag = tag
        buildContent(tag)
    }

    private fun buildContent(tag: Tag) {
        removeAll()
        isPadding = true
        isSpacing = true
        width = "100%"
        style.set("max-width", "1000px")
        style.set("margin", "0 auto")

        val currentUser = AuthService.getCurrentUser()
        val isAdmin = currentUser?.isAdmin() == true

        // Header: badge + title count
        val taggedTitleIds = TagService.getTitleIdsForTags(setOf(tag.id!!))
        val allTitles = Title.findAll().associateBy { it.id }
        val taggedTitles = taggedTitleIds.mapNotNull { allTitles[it] }
            .filter { currentUser == null || currentUser.canSeeRating(it.content_rating) }
            .sortedBy { it.sort_name?.lowercase() ?: it.name.lowercase() }

        val headerRow = HorizontalLayout().apply {
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isSpacing = true
            isPadding = false
            val badge = createTagBadge(tag).apply {
                style.set("font-size", "var(--lumo-font-size-l)")
                style.set("padding", "4px 16px")
            }
            add(badge)
            add(Span("${taggedTitles.size} title${if (taggedTitles.size != 1) "s" else ""}").apply {
                style.set("color", "rgba(255,255,255,0.6)")
                style.set("font-size", "var(--lumo-font-size-s)")
            })
        }
        add(headerRow)

        // Admin: add title to tag
        if (isAdmin) {
            val addRow = HorizontalLayout().apply {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                isSpacing = true
                width = "100%"

                val titleCombo = ComboBox<Title>().apply {
                    placeholder = "Add title to tag..."
                    width = "300px"
                    isClearButtonVisible = true
                    setItemLabelGenerator { "${it.name}${it.release_year?.let { y -> " ($y)" } ?: ""}" }

                    val available = Title.findAll()
                        .filter { it.id !in taggedTitleIds }
                        .filter { !it.hidden }
                        .filter { currentUser == null || currentUser.canSeeRating(it.content_rating) }
                        .sortedBy { it.sort_name?.lowercase() ?: it.name.lowercase() }

                    setItems(
                        ComboBox.ItemFilter<Title> { item, filterString ->
                            if (filterString.length < 2) false
                            else item.name.lowercase().contains(filterString.lowercase())
                        },
                        available
                    )
                }

                val addBtn = Button("Add", VaadinIcon.PLUS.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL)
                    addClickListener {
                        val title = titleCombo.value ?: return@addClickListener
                        TagService.addTagToTitle(title.id!!, tag.id!!)
                        titleCombo.value = null
                        buildContent(tag)
                        Notification.show("Added \"${title.name}\" to tag", 2000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                    }
                }
                add(titleCombo, addBtn)
            }
            add(addRow)
        }

        // Titles grid
        val grid = Grid<Title>(Title::class.java, false).apply {
            width = "100%"
            isAllRowsVisible = true

            addColumn(ComponentRenderer { title ->
                val posterUrl = title.posterUrl(PosterSize.THUMBNAIL)
                if (posterUrl != null) {
                    Image(posterUrl, title.name).apply {
                        height = "60px"
                        width = "40px"
                        style.set("object-fit", "cover")
                    }
                } else {
                    Span("—")
                }
            }).setHeader("Poster").setWidth("80px").setFlexGrow(0)

            addColumn(ComponentRenderer { title ->
                Span(title.name).apply {
                    style.set("cursor", "pointer")
                    style.set("color", "var(--lumo-primary-text-color)")
                    element.addEventListener("click") {
                        ui.ifPresent { it.navigate("title/${title.id}") }
                    }
                }
            }).setHeader("Title").setFlexGrow(1)

            addColumn({ it.release_year?.toString() ?: "" }).setHeader("Year").setWidth("80px").setFlexGrow(0)

            if (isAdmin) {
                addColumn(ComponentRenderer { title ->
                    Button(VaadinIcon.CLOSE_SMALL.create()).apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
                        style.set("color", "var(--lumo-error-text-color)")
                        element.setAttribute("title", "Remove from tag")
                        addClickListener {
                            TagService.removeTagFromTitle(title.id!!, tag.id!!)
                            buildContent(tag)
                            Notification.show("Removed from tag", 2000, Notification.Position.BOTTOM_START)
                        }
                    }
                }).setHeader("").setWidth("60px").setFlexGrow(0)
            }

            setItems(taggedTitles)
        }
        add(grid)
    }
}
