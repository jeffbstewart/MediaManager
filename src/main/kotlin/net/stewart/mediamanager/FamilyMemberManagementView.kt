package net.stewart.mediamanager

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.datepicker.DatePicker
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
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.service.FamilyMemberService
import java.time.LocalDate

@Route("family", layout = MainLayout::class)
@PageTitle("Family Members — Media Manager")
class FamilyMemberManagementView : VerticalLayout() {

    private val grid: Grid<FamilyMemberRow>

    init {
        isPadding = true
        isSpacing = true

        val header = HorizontalLayout().apply {
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            width = "100%"
            val titleSpan = Span("Family Members").apply {
                style.set("font-size", "var(--lumo-font-size-xl)")
                style.set("font-weight", "600")
            }
            add(titleSpan)
            val addBtn = Button("New Member", VaadinIcon.PLUS.create()) {
                openCreateEditDialog(null)
            }.apply {
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }
            add(addBtn)
            expand(titleSpan)
        }
        add(header)

        grid = Grid(FamilyMemberRow::class.java, false)
        grid.width = "100%"

        grid.addColumn(ComponentRenderer { row ->
            HorizontalLayout().apply {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isSpacing = true
                isPadding = false

                // Initial circle
                val initial = row.member.name.firstOrNull()?.uppercase() ?: "?"
                add(Span(initial).apply {
                    style.set("display", "inline-flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    style.set("width", "32px")
                    style.set("height", "32px")
                    style.set("border-radius", "50%")
                    style.set("background", "var(--lumo-primary-color-10pct)")
                    style.set("color", "var(--lumo-primary-text-color)")
                    style.set("font-weight", "bold")
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("flex-shrink", "0")
                })

                add(Span(row.member.name).apply {
                    style.set("font-weight", "500")
                })
            }
        }).setHeader("Name").setFlexGrow(2)

        grid.addColumn(ComponentRenderer { row ->
            val bd = row.member.birth_date
            if (bd != null) {
                val age = row.member.ageAt(LocalDate.now())
                Span("$bd (age $age)").apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-secondary-text-color)")
                }
            } else {
                Span("—").apply {
                    style.set("color", "var(--lumo-secondary-text-color)")
                }
            }
        }).setHeader("Birth Date").setWidth("180px").setFlexGrow(0)

        grid.addColumn({ it.titleCount.toString() }).setHeader("Videos").setWidth("80px").setFlexGrow(0)

        grid.addColumn(ComponentRenderer { row ->
            if (!row.member.notes.isNullOrBlank()) {
                Span(row.member.notes).apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-secondary-text-color)")
                    style.set("overflow", "hidden")
                    style.set("text-overflow", "ellipsis")
                    style.set("white-space", "nowrap")
                    style.set("max-width", "200px")
                }
            } else {
                Span()
            }
        }).setHeader("Notes").setFlexGrow(1)

        grid.addColumn(ComponentRenderer { row ->
            HorizontalLayout().apply {
                isSpacing = true
                isPadding = false
                add(Button(VaadinIcon.EDIT.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
                    addClickListener { openCreateEditDialog(row.member) }
                })
                add(Button(VaadinIcon.TRASH.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
                    style.set("color", "var(--lumo-error-text-color)")
                    addClickListener { openDeleteDialog(row.member, row.titleCount) }
                })
            }
        }).setHeader("Actions").setWidth("120px").setFlexGrow(0)

        add(grid)
        refreshGrid()
    }

    private fun refreshGrid() {
        val members = FamilyMemberService.getAllMembers()
        val counts = FamilyMemberService.getMemberTitleCounts()
        grid.setItems(members.map { FamilyMemberRow(it, counts[it.id] ?: 0) })
    }

    private fun openCreateEditDialog(existing: FamilyMember?) {
        val dialog = Dialog().apply {
            headerTitle = if (existing != null) "Edit Family Member" else "New Family Member"
            width = "450px"
        }

        val nameField = TextField("Name").apply {
            width = "100%"
            value = existing?.name ?: ""
            maxLength = 200
        }

        val birthDateField = DatePicker("Birth Date").apply {
            width = "100%"
            value = existing?.birth_date
            placeholder = "Optional"
        }

        val notesField = TextArea("Notes").apply {
            width = "100%"
            value = existing?.notes ?: ""
            maxLength = 500
            height = "100px"
            placeholder = "Optional notes"
        }

        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(nameField, birthDateField, notesField)
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
                if (!FamilyMemberService.isNameUnique(name, existing?.id)) {
                    Notification.show("A family member with that name already exists", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }
                if (existing != null) {
                    FamilyMemberService.updateMember(existing.id!!, name, birthDateField.value, notesField.value)
                } else {
                    FamilyMemberService.createMember(name, birthDateField.value, notesField.value)
                }
                dialog.close()
                refreshGrid()
                Notification.show(
                    if (existing != null) "Family member updated" else "Family member added",
                    2000, Notification.Position.BOTTOM_START
                ).addThemeVariants(NotificationVariant.LUMO_SUCCESS)
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

    private fun openDeleteDialog(member: FamilyMember, titleCount: Int) {
        val dialog = Dialog().apply {
            headerTitle = "Delete Family Member"
            width = "400px"
        }

        val message = if (titleCount > 0) {
            "Delete \"${member.name}\"? They appear in $titleCount video${if (titleCount != 1) "s" else ""}. They will be removed from all videos."
        } else {
            "Delete \"${member.name}\"?"
        }
        dialog.add(Span(message).apply {
            style.set("padding", "var(--lumo-space-m)")
        })

        val cancelBtn = Button("Cancel") { dialog.close() }
        val deleteBtn = Button("Delete").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR)
            addClickListener {
                FamilyMemberService.deleteMember(member.id!!)
                dialog.close()
                refreshGrid()
                Notification.show("Family member deleted", 2000, Notification.Position.BOTTOM_START)
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

private data class FamilyMemberRow(val member: FamilyMember, val titleCount: Int)
