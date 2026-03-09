package net.stewart.mediamanager

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TagSourceType
import net.stewart.mediamanager.entity.createTagBadge
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.TagService

@Route("tags", layout = MainLayout::class)
@PageTitle("Tags — Media Manager")
class TagManagementView : VerticalLayout() {

    private val grid: Grid<TagRow>

    init {
        isPadding = true
        isSpacing = true

        val header = HorizontalLayout().apply {
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            width = "100%"
            val titleSpan = Span("Tags").apply {
                style.set("font-size", "var(--lumo-font-size-xl)")
                style.set("font-weight", "600")
            }
            add(titleSpan)
            val addBtn = Button("New Tag", VaadinIcon.PLUS.create()) {
                openCreateEditDialog(null)
            }.apply {
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }
            add(addBtn)
            expand(titleSpan)
        }
        add(header)

        grid = Grid(TagRow::class.java, false)
        grid.width = "100%"

        grid.addColumn(ComponentRenderer { row ->
            val badge = createTagBadge(row.tag)
            badge.style.set("cursor", "pointer")
            badge.element.addEventListener("click") {
                ui.ifPresent { it.navigate("tag/${row.tag.id}") }
            }
            badge
        }).setHeader("Tag").setFlexGrow(1)

        grid.addColumn(ComponentRenderer { row ->
            val sourceType = try { TagSourceType.valueOf(row.tag.source_type) } catch (_: Exception) { TagSourceType.MANUAL }
            val label = when (sourceType) {
                TagSourceType.MANUAL -> "Manual"
                TagSourceType.GENRE -> "Genre"
                TagSourceType.COLLECTION -> "Collection"  // Legacy; no longer created
            }
            Span(label).apply {
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("color", "var(--lumo-secondary-text-color)")
            }
        }).setHeader("Source").setWidth("110px").setFlexGrow(0)

        grid.addColumn({ it.titleCount.toString() }).setHeader("Titles").setWidth("80px").setFlexGrow(0)

        grid.addColumn(ComponentRenderer { row ->
            HorizontalLayout().apply {
                isSpacing = true
                isPadding = false
                add(Button(VaadinIcon.EDIT.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
                    addClickListener { openCreateEditDialog(row.tag) }
                })
                add(Button(VaadinIcon.TRASH.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
                    style.set("color", "var(--lumo-error-text-color)")
                    addClickListener { openDeleteDialog(row.tag, row.titleCount) }
                })
            }
        }).setHeader("Actions").setWidth("120px").setFlexGrow(0)

        add(grid)

        refreshGrid()
    }

    private fun refreshGrid() {
        val tags = TagService.getAllTags()
        val counts = TagService.getTagTitleCounts()
        grid.setItems(tags.map { TagRow(it, counts[it.id] ?: 0) })
    }

    private fun openCreateEditDialog(existingTag: Tag?) {
        val dialog = Dialog().apply {
            headerTitle = if (existingTag != null) "Edit Tag" else "New Tag"
            width = "400px"
        }

        val nameField = TextField("Name").apply {
            width = "100%"
            value = existingTag?.name ?: ""
            maxLength = 100
        }

        val colorCombo = ComboBox<Pair<String, String>>("Color").apply {
            width = "100%"
            setItems(TagService.COLOR_PALETTE)
            setItemLabelGenerator { it.first }
            setRenderer(ComponentRenderer { pair ->
                HorizontalLayout().apply {
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    isSpacing = true
                    isPadding = false
                    style.set("padding", "var(--lumo-space-xs) 0")
                    add(Span().apply {
                        style.set("display", "inline-block")
                        style.set("width", "20px")
                        style.set("height", "20px")
                        style.set("border-radius", "50%")
                        style.set("background-color", pair.second)
                        style.set("flex-shrink", "0")
                    })
                    add(Span(pair.first))
                }
            })
            // Default to matching color or first in palette
            val currentColor = existingTag?.bg_color
            value = TagService.COLOR_PALETTE.firstOrNull { it.second.equals(currentColor, ignoreCase = true) }
                ?: TagService.COLOR_PALETTE.first()
        }

        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(nameField, colorCombo)
        }
        dialog.add(content)

        val cancelBtn = Button("Cancel") { dialog.close() }
        val saveBtn = Button("Save").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener {
                val name = nameField.value?.trim() ?: ""
                if (name.isBlank()) {
                    Notification.show("Name is required", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }
                if (!TagService.isNameUnique(name, existingTag?.id)) {
                    Notification.show("A tag with that name already exists", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }
                val color = colorCombo.value?.second ?: "#6B7280"
                if (existingTag != null) {
                    TagService.updateTag(existingTag.id!!, name, color)
                } else {
                    val userId = AuthService.getCurrentUser()?.id
                    TagService.createTag(name, color, userId)
                }
                dialog.close()
                refreshGrid()
                Notification.show(if (existingTag != null) "Tag updated" else "Tag created", 2000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }
        }

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            isSpacing = true
            add(cancelBtn, saveBtn)
        }
        footer.element.setAttribute("slot", "footer")
        dialog.add(footer)
        dialog.open()
    }

    private fun openDeleteDialog(tag: Tag, titleCount: Int) {
        val dialog = Dialog().apply {
            headerTitle = "Delete Tag"
            width = "400px"
        }

        val message = if (titleCount > 0) {
            "Delete \"${tag.name}\"? It is applied to $titleCount title${if (titleCount != 1) "s" else ""}. The tag will be removed from all titles."
        } else {
            "Delete \"${tag.name}\"?"
        }
        dialog.add(Span(message).apply {
            style.set("padding", "var(--lumo-space-m)")
        })

        val cancelBtn = Button("Cancel") { dialog.close() }
        val deleteBtn = Button("Delete").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR)
            addClickListener {
                TagService.deleteTag(tag.id!!)
                dialog.close()
                refreshGrid()
                Notification.show("Tag deleted", 2000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }
        }

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            isSpacing = true
            add(cancelBtn, deleteBtn)
        }
        footer.element.setAttribute("slot", "footer")
        dialog.add(footer)
        dialog.open()
    }
}

private data class TagRow(val tag: Tag, val titleCount: Int)
