package net.stewart.mediamanager

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import net.stewart.mediamanager.entity.DiscoveredFile
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.service.FamilyMemberService
import net.stewart.mediamanager.service.PersonalVideoService
import net.stewart.mediamanager.service.TagService

/**
 * Dialog for creating a personal video title from an unmatched discovered file.
 * Pre-fills the name from the filename, allows setting event date, description,
 * family members, and tags.
 */
internal class CreatePersonalVideoDialog(
    private val file: DiscoveredFile,
    private val onCreated: () -> Unit
) : Dialog() {

    init {
        headerTitle = "Create Personal Video"
        width = "600px"

        val nameField = TextField("Title").apply {
            width = "100%"
            value = file.parsed_title ?: file.file_name.substringBeforeLast(".")
            maxLength = 500
        }

        val eventDatePicker = DatePicker("Event Date").apply {
            width = "100%"
            placeholder = "When was this filmed?"
        }

        val descriptionField = TextArea("Description").apply {
            width = "100%"
            maxLength = 2000
            height = "100px"
            placeholder = "Optional notes about this video"
        }

        // Family members multi-select with inline "Add new" support
        val allMembers = FamilyMemberService.getAllMembers()
        val familyMemberSelect = MultiSelectComboBox<FamilyMember>("Family Members").apply {
            width = "100%"
            setItems(allMembers)
            setItemLabelGenerator { it.name }
            placeholder = "Select people in this video"
        }

        val addMemberRow = HorizontalLayout().apply {
            width = "100%"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
            isSpacing = true
            val newMemberField = TextField().apply {
                placeholder = "New family member name"
                width = "100%"
            }
            val addBtn = Button("Add").apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL)
                addClickListener {
                    val name = newMemberField.value?.trim() ?: ""
                    if (name.isBlank()) return@addClickListener
                    if (!FamilyMemberService.isNameUnique(name)) {
                        Notification.show("'$name' already exists", 2000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_CONTRAST)
                        return@addClickListener
                    }
                    // Capture selection BEFORE setItems clears it
                    val previousIds = familyMemberSelect.selectedItems.mapNotNull { it.id }.toSet()
                    val member = FamilyMemberService.createMember(name)
                    val updatedMembers = FamilyMemberService.getAllMembers()
                    familyMemberSelect.setItems(updatedMembers)
                    // Re-select previously selected + the new one
                    val newSelection = updatedMembers.filter { it.id in previousIds || it.id == member.id }.toSet()
                    familyMemberSelect.select(newSelection)
                    newMemberField.clear()
                    Notification.show("Added $name", 1500, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                }
            }
            setFlexGrow(1.0, newMemberField)
            add(newMemberField, addBtn)
        }

        // Tags multi-select
        val allTags = TagService.getAllTags()
        val tagSelect = MultiSelectComboBox<Tag>("Tags").apply {
            width = "100%"
            setItems(allTags)
            setItemLabelGenerator { it.name }
            placeholder = "Select tags"
        }

        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(Span(file.file_name).apply {
                style.set("color", "var(--lumo-secondary-text-color)")
                style.set("font-size", "var(--lumo-font-size-s)")
            })
            add(nameField, eventDatePicker, descriptionField)
            add(familyMemberSelect, addMemberRow)
            add(tagSelect)
        }
        add(content)

        // Footer
        val cancelBtn = Button("Cancel") { close() }
        val createBtn = Button("Create").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener {
                val name = nameField.value?.trim() ?: ""
                if (name.isBlank()) {
                    Notification.show("Title is required", 2000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }

                val title = PersonalVideoService.createAndLink(
                    discoveredFileId = file.id!!,
                    name = name,
                    eventDate = eventDatePicker.value,
                    description = descriptionField.value?.trim()?.ifBlank { null },
                    familyMemberIds = familyMemberSelect.selectedItems.mapNotNull { it.id },
                    tagIds = tagSelect.selectedItems.mapNotNull { it.id }
                )

                close()
                onCreated()
                Notification.show("Created: ${title.name}", 2000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }
        }

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            isSpacing = true
            add(cancelBtn, createBtn)
        }
        footer.element.setAttribute("slot", "footer")
        add(footer)
    }
}
