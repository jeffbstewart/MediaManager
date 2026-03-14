package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.BuddyApiKey
import net.stewart.mediamanager.service.BuddyKeyService
import net.stewart.mediamanager.service.TranscoderAgent
import java.time.format.DateTimeFormatter

@Route(value = "settings", layout = MainLayout::class)
@PageTitle("Settings")
class SettingsView : KComposite() {

    private lateinit var buddyKeyGrid: Grid<BuddyApiKey>

    private val root = ui {
        verticalLayout {
            h2("Settings")

            val configs = AppConfig.findAll()

            // Card-style container
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-l)")
                style.set("max-width", "600px")

                val isDocker = java.io.File("/.dockerenv").exists()

                val nasPathField = textField("NAS Root Path") {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "nas_root_path" }?.config_val ?: ""
                    placeholder = """/path/to/media/root"""
                    if (isDocker) {
                        isReadOnly = true
                        helperText = "Locked to Docker volume mount"
                    }
                }

                val ffmpegPathField = textField("FFmpeg Path") {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "ffmpeg_path" }?.config_val ?: ""
                    placeholder = TranscoderAgent.getDefaultFfmpegPath()
                    if (isDocker) {
                        isReadOnly = true
                        helperText = "Locked to container path"
                    }
                }

                val rokuBaseUrlField = textField("Roku Base URL") {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "roku_base_url" }?.config_val ?: ""
                    placeholder = "https://myserver.example.com:8080"
                    helperText = "Base URL for Roku feed poster/stream URLs. Leave blank to auto-detect."
                }

                // --- Personal / Home Videos Section ---
                hr()
                add(Span("Personal / Home Videos").apply {
                    style.set("font-weight", "bold")
                    style.set("font-size", "var(--lumo-font-size-l)")
                })

                val personalEnabledCheck = Checkbox("Enable personal video support").apply {
                    value = configs.firstOrNull { it.config_key == "personal_video_enabled" }?.config_val == "true"
                }
                add(personalEnabledCheck)

                val personalDirField = textField("Personal Video Directory") {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "personal_video_nas_dir" }?.config_val ?: ""
                    placeholder = "e.g., Family Videos"
                    helperText = "Name of the directory inside the NAS root (not a full path). " +
                        "For example, if your NAS root is /media and your home videos are in /media/Family Videos, enter \"Family Videos\"."
                }

                // --- Transcode Buddy Section ---
                hr()
                add(Span("Transcode Buddy").apply {
                    style.set("font-weight", "bold")
                    style.set("font-size", "var(--lumo-font-size-l)")
                })

                add(HorizontalLayout().apply {
                    width = "100%"
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    isSpacing = true
                    add(Span("Buddy API Keys").apply {
                        style.set("font-weight", "500")
                    })
                    val spacer = Span()
                    add(spacer)
                    expand(spacer)
                    add(Button("New Key", VaadinIcon.PLUS.create()).apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                        addClickListener { openNewKeyDialog() }
                    })
                })

                buddyKeyGrid = Grid(BuddyApiKey::class.java, false)
                buddyKeyGrid.width = "100%"
                buddyKeyGrid.addColumn({ it.name }).setHeader("Name").setFlexGrow(1)
                buddyKeyGrid.addColumn({
                    it.created_at?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: ""
                }).setHeader("Created").setWidth("150px").setFlexGrow(0)
                buddyKeyGrid.addColumn(ComponentRenderer { key ->
                    Button(VaadinIcon.TRASH.create()).apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY,
                            ButtonVariant.LUMO_ERROR)
                        element.setAttribute("title", "Delete")
                        addClickListener { openDeleteKeyDialog(key) }
                    }
                }).setHeader("").setWidth("80px").setFlexGrow(0)
                add(buddyKeyGrid)
                refreshBuddyKeyGrid()

                val leaseDurationField = textField("Lease Duration (minutes)") {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "buddy_lease_duration_minutes" }?.config_val ?: "90"
                    helperText = "How long a buddy can hold a transcode job before it expires"
                }

                // --- Price Lookup Section ---
                hr()
                add(Span("Price Lookup").apply {
                    style.set("font-weight", "bold")
                    style.set("font-size", "var(--lumo-font-size-l)")
                })
                add(Span("Automated replacement value estimation via Keepa (Amazon.com US marketplace). " +
                    "Queries current and historical Amazon prices by UPC or ASIN to estimate replacement cost for insurance reports.").apply {
                    style.set("color", "var(--lumo-secondary-text-color)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("margin-bottom", "var(--lumo-space-s)")
                })

                val keepaEnabledCheck = Checkbox("Enable Keepa price lookups").apply {
                    value = configs.firstOrNull { it.config_key == "keepa_enabled" }?.config_val == "true"
                }
                add(keepaEnabledCheck)

                val keepaApiKeyField = textField("Keepa API Key") {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "keepa_api_key" }?.config_val ?: ""
                    placeholder = "Paste your Keepa API key"
                    helperText = "Get an API key from keepa.com (requires subscription)"
                }
                // Mask the API key like a password
                keepaApiKeyField.element.setAttribute("type", "password")

                val keepaTokensField = NumberField("Tokens per minute").apply {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "keepa_tokens_per_minute" }?.config_val?.toDoubleOrNull() ?: 20.0
                    min = 1.0
                    max = 1000.0
                    step = 1.0
                    helperText = "Base subscription: 20/min. Higher tiers: 250, 1000, 4000. " +
                        "Each item lookup costs 1 token."
                }
                add(keepaTokensField)

                // Save button
                button("Save") {
                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                    addClickListener {
                        if (!isDocker) {
                            saveConfig("nas_root_path", nasPathField.value.trim())
                            saveConfig("ffmpeg_path", ffmpegPathField.value.trim())
                        }
                        saveConfig("roku_base_url", rokuBaseUrlField.value.trim())
                        saveConfig("personal_video_enabled", if (personalEnabledCheck.value) "true" else "false")
                        saveConfig("personal_video_nas_dir", personalDirField.value.trim())
                        saveConfig("buddy_lease_duration_minutes", leaseDurationField.value.trim())
                        saveConfig("keepa_enabled", if (keepaEnabledCheck.value) "true" else "false")
                        saveConfig("keepa_api_key", keepaApiKeyField.value.trim())
                        saveConfig("keepa_tokens_per_minute", keepaTokensField.value?.toInt()?.toString() ?: "20")
                        Notification.show("Settings saved", 2000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                    }
                }
            }
        }
    }

    private fun refreshBuddyKeyGrid() {
        buddyKeyGrid.setItems(BuddyKeyService.getAllKeys())
    }

    private fun openNewKeyDialog() {
        val dialog = Dialog().apply {
            headerTitle = "New Buddy API Key"
            width = "500px"
        }

        val nameField = TextField("Key Name").apply {
            width = "100%"
            placeholder = "e.g., Office GPU, Living Room"
            maxLength = 100
        }

        dialog.add(VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(nameField)
            add(Span("A new API key will be generated. You will see it once — copy it to your buddy's configuration file before closing this dialog.").apply {
                style.set("color", "var(--lumo-secondary-text-color)")
                style.set("font-size", "var(--lumo-font-size-s)")
            })
        })

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            isSpacing = true
        }
        val cancelBtn = Button("Cancel") { dialog.close() }
        val createBtn = Button("Generate Key").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener {
                val name = nameField.value?.trim() ?: ""
                if (name.isBlank()) {
                    Notification.show("Name is required", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }
                val rawKey = BuddyKeyService.createKey(name)
                dialog.close()
                refreshBuddyKeyGrid()
                openShowKeyDialog(name, rawKey)
            }
        }
        footer.add(cancelBtn, createBtn)
        footer.element.setAttribute("slot", "footer")
        dialog.add(footer)
        dialog.open()
    }

    private fun openShowKeyDialog(name: String, rawKey: String) {
        val dialog = Dialog().apply {
            headerTitle = "Key Created: $name"
            width = "500px"
            isCloseOnOutsideClick = false
            isCloseOnEsc = false
        }

        val keyField = TextField("API Key").apply {
            width = "100%"
            value = rawKey
            isReadOnly = true
            style.set("font-family", "monospace")
        }

        dialog.add(VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(Span("Copy this key now. It will not be shown again.").apply {
                style.set("color", "var(--lumo-error-text-color)")
                style.set("font-weight", "bold")
            })
            add(HorizontalLayout().apply {
                width = "100%"
                isSpacing = true
                defaultVerticalComponentAlignment = FlexComponent.Alignment.END
                expand(keyField)
                add(keyField)
                add(Button("Copy", VaadinIcon.COPY.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL)
                    addClickListener {
                        keyField.element.executeJs("navigator.clipboard.writeText($0)", rawKey)
                        Notification.show("Copied to clipboard", 2000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                    }
                })
            })
            add(Span("Set this as the api_key value in your buddy's configuration file.").apply {
                style.set("color", "var(--lumo-secondary-text-color)")
                style.set("font-size", "var(--lumo-font-size-s)")
            })
        })

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
        }
        val doneBtn = Button("Done").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener { dialog.close() }
        }
        footer.add(doneBtn)
        footer.element.setAttribute("slot", "footer")
        dialog.add(footer)
        dialog.open()
    }

    private fun openDeleteKeyDialog(key: BuddyApiKey) {
        val dialog = Dialog().apply {
            headerTitle = "Delete Key"
            width = "400px"
        }

        dialog.add(Span("Delete \"${key.name}\"? Any buddy using this key will immediately lose access.").apply {
            style.set("padding", "var(--lumo-space-m)")
        })

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            isSpacing = true
        }
        val cancelBtn = Button("Cancel") { dialog.close() }
        val deleteBtn = Button("Delete").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR)
            addClickListener {
                BuddyKeyService.deleteKey(key.id!!)
                dialog.close()
                refreshBuddyKeyGrid()
                Notification.show("Key deleted", 2000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }
        }
        footer.add(cancelBtn, deleteBtn)
        footer.element.setAttribute("slot", "footer")
        dialog.add(footer)
        dialog.open()
    }

    private fun saveConfig(key: String, value: String) {
        val existing = AppConfig.findAll().firstOrNull { it.config_key == key }
        if (existing != null) {
            existing.config_val = value.ifBlank { null }
            existing.save()
        } else if (value.isNotBlank()) {
            AppConfig(config_key = key, config_val = value).save()
        }
    }
}
