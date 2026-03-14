package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.service.Go2rtcAgent
import net.stewart.mediamanager.service.UriCredentialRedactor

@Route(value = "cameras/settings", layout = MainLayout::class)
@PageTitle("Camera Settings")
class CameraSettingsView : KComposite() {

    private lateinit var cameraGrid: Grid<Camera>

    private val root = ui {
        verticalLayout {
            h2("Camera Settings")

            // go2rtc settings card
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-l)")
                style.set("max-width", "600px")

                add(Span("go2rtc Configuration").apply {
                    style.set("font-weight", "bold")
                    style.set("font-size", "var(--lumo-font-size-l)")
                })

                val configs = AppConfig.findAll()

                val go2rtcPathField = textField("go2rtc Binary Path") {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "go2rtc_path" }?.config_val ?: ""
                    placeholder = "/usr/local/bin/go2rtc"
                    helperText = "Path to go2rtc binary. In Docker: /usr/local/bin/go2rtc"
                }

                val apiPortField = textField("go2rtc API Port") {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "go2rtc_api_port" }?.config_val ?: "1984"
                    helperText = "Port for go2rtc HTTP API (default: 1984). Never expose externally."
                }

                val statusSpan = Span().apply {
                    val healthy = Go2rtcAgent.instance?.isHealthy() == true
                    text = if (healthy) "go2rtc: Running" else "go2rtc: Not running"
                    style.set("color", if (healthy) "var(--lumo-success-color)" else "var(--lumo-error-color)")
                    style.set("font-weight", "bold")
                }
                add(statusSpan)

                button("Save go2rtc Settings") {
                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                    addClickListener {
                        saveConfig("go2rtc_path", go2rtcPathField.value, "Path to go2rtc binary")
                        saveConfig("go2rtc_api_port", apiPortField.value, "go2rtc API port")
                        Notification.show("go2rtc settings saved", 3000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                    }
                }
            }

            // Camera list
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-l)")
                style.set("margin-top", "var(--lumo-space-l)")

                val headerRow = HorizontalLayout().apply {
                    width = "100%"
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    add(Span("Cameras").apply {
                        style.set("font-weight", "bold")
                        style.set("font-size", "var(--lumo-font-size-l)")
                        style.set("flex-grow", "1")
                    })
                    val addBtn = Button("Add Camera", VaadinIcon.PLUS.create()) {
                        showAddCameraDialog()
                    }
                    addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                    add(addBtn)
                }
                add(headerRow)

                cameraGrid = Grid(Camera::class.java, false)
                cameraGrid.width = "100%"
                cameraGrid.addColumn { it.name }.setHeader("Name").setAutoWidth(true)
                cameraGrid.addColumn { UriCredentialRedactor.redact(it.rtsp_url) }.setHeader("RTSP URL").setAutoWidth(true)
                cameraGrid.addColumn { it.go2rtc_name }.setHeader("Stream Name").setAutoWidth(true)
                cameraGrid.addColumn { if (it.enabled) "Yes" else "No" }.setHeader("Enabled").setAutoWidth(true)
                cameraGrid.addColumn { it.display_order }.setHeader("Order").setAutoWidth(true)
                cameraGrid.addColumn(ComponentRenderer { camera -> createActionButtons(camera) }).setHeader("Actions").setAutoWidth(true)
                add(cameraGrid)

                refreshGrid()
            }
        }
    }

    private fun refreshGrid() {
        cameraGrid.setItems(Camera.findAll().sortedBy { it.display_order })
    }

    private fun createActionButtons(camera: Camera): HorizontalLayout {
        val layout = HorizontalLayout()
        layout.isSpacing = true

        val editBtn = Button(VaadinIcon.EDIT.create()) { showEditCameraDialog(camera) }
        editBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        editBtn.element.setAttribute("title", "Edit")

        val testBtn = Button(VaadinIcon.PICTURE.create()) { testSnapshot(camera) }
        testBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        testBtn.element.setAttribute("title", "Test snapshot")

        val upBtn = Button(VaadinIcon.ARROW_UP.create()) { moveCamera(camera, -1) }
        upBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        upBtn.element.setAttribute("title", "Move up")

        val downBtn = Button(VaadinIcon.ARROW_DOWN.create()) { moveCamera(camera, 1) }
        downBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        downBtn.element.setAttribute("title", "Move down")

        val deleteBtn = Button(VaadinIcon.TRASH.create()) { confirmDelete(camera) }
        deleteBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ERROR)
        deleteBtn.element.setAttribute("title", "Delete")

        layout.add(editBtn, testBtn, upBtn, downBtn, deleteBtn)
        return layout
    }

    private fun showAddCameraDialog() {
        val dialog = Dialog()
        dialog.headerTitle = "Add Camera"
        dialog.width = "500px"

        val nameField = TextField("Name").apply { width = "100%" }
        val rtspUrlField = TextField("RTSP URL").apply {
            width = "100%"
            placeholder = "rtsp://user:pass@192.168.1.100:554/stream"
        }
        val snapshotUrlField = TextField("Snapshot URL (optional)").apply {
            width = "100%"
            placeholder = "http://user:pass@192.168.1.100/snapshot.jpg"
        }
        val streamNameField = TextField("Stream Name").apply {
            width = "100%"
            helperText = "go2rtc stream identifier (auto-generated from name)"
        }
        val enabledCheck = Checkbox("Enabled").apply { value = true }

        // Auto-generate stream name from camera name
        nameField.addValueChangeListener { event ->
            if (streamNameField.value.isBlank() || streamNameField.value == generateStreamName(event.oldValue)) {
                streamNameField.value = generateStreamName(event.value)
            }
        }

        val content = VerticalLayout(nameField, rtspUrlField, snapshotUrlField, streamNameField, enabledCheck).apply {
            isPadding = false
        }
        dialog.add(content)

        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Save") {
                if (nameField.value.isBlank()) {
                    Notification.show("Name is required", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }
                if (rtspUrlField.value.isBlank()) {
                    Notification.show("RTSP URL is required", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }
                if (!validateUrlScheme(rtspUrlField.value)) {
                    Notification.show("RTSP URL must start with rtsp://", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }

                val maxOrder = Camera.findAll().maxOfOrNull { it.display_order } ?: -1
                val camera = Camera(
                    name = nameField.value.trim(),
                    rtsp_url = rtspUrlField.value.trim(),
                    snapshot_url = snapshotUrlField.value.trim(),
                    go2rtc_name = streamNameField.value.trim().ifBlank { generateStreamName(nameField.value) },
                    enabled = enabledCheck.value,
                    display_order = maxOrder + 1
                )
                camera.save()
                Go2rtcAgent.instance?.reconfigure()
                refreshGrid()
                dialog.close()
                Notification.show("Camera '${camera.name}' added", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )

        dialog.open()
    }

    private fun showEditCameraDialog(camera: Camera) {
        val dialog = Dialog()
        dialog.headerTitle = "Edit Camera: ${camera.name}"
        dialog.width = "500px"

        val nameField = TextField("Name").apply {
            width = "100%"
            value = camera.name
        }

        val currentUrlLabel = Span("Current URL: ${UriCredentialRedactor.redact(camera.rtsp_url)}").apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
        }
        val changeUrlField = PasswordField("New RTSP URL (leave blank to keep current)").apply {
            width = "100%"
            placeholder = "rtsp://user:pass@host:554/stream"
        }

        val currentSnapshotLabel = Span("Current Snapshot URL: ${UriCredentialRedactor.redact(camera.snapshot_url)}").apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
        }
        val changeSnapshotField = PasswordField("New Snapshot URL (leave blank to keep current)").apply {
            width = "100%"
        }

        val streamNameField = TextField("Stream Name").apply {
            width = "100%"
            value = camera.go2rtc_name
        }
        val enabledCheck = Checkbox("Enabled").apply { value = camera.enabled }

        val content = VerticalLayout(
            nameField, currentUrlLabel, changeUrlField,
            currentSnapshotLabel, changeSnapshotField,
            streamNameField, enabledCheck
        ).apply { isPadding = false }
        dialog.add(content)

        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Save") {
                if (nameField.value.isBlank()) {
                    Notification.show("Name is required", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }
                val newUrl = changeUrlField.value.trim()
                if (newUrl.isNotBlank() && !validateUrlScheme(newUrl)) {
                    Notification.show("RTSP URL must start with rtsp://", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }

                val fresh = Camera.findById(camera.id!!) ?: return@Button
                fresh.name = nameField.value.trim()
                if (newUrl.isNotBlank()) fresh.rtsp_url = newUrl
                val newSnapshot = changeSnapshotField.value.trim()
                if (newSnapshot.isNotBlank()) fresh.snapshot_url = newSnapshot
                fresh.go2rtc_name = streamNameField.value.trim()
                fresh.enabled = enabledCheck.value
                fresh.save()
                Go2rtcAgent.instance?.reconfigure()
                refreshGrid()
                dialog.close()
                Notification.show("Camera '${fresh.name}' updated", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )

        dialog.open()
    }

    private fun testSnapshot(camera: Camera) {
        val dialog = Dialog()
        dialog.headerTitle = "Snapshot: ${camera.name}"
        dialog.width = "640px"

        val img = Image("/cameras/${camera.id}/snapshot.jpg", "Snapshot of ${camera.name}")
        img.width = "100%"
        img.style.set("border-radius", "var(--lumo-border-radius-m)")

        val refreshBtn = Button("Refresh", VaadinIcon.REFRESH.create()) {
            // Force browser to refetch by adding cache-buster
            img.src = "/cameras/${camera.id}/snapshot.jpg?t=${System.currentTimeMillis()}"
        }

        dialog.add(img, refreshBtn)
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.open()
    }

    private fun moveCamera(camera: Camera, direction: Int) {
        val cameras = Camera.findAll().sortedBy { it.display_order }.toMutableList()
        val index = cameras.indexOfFirst { it.id == camera.id }
        if (index < 0) return
        val newIndex = index + direction
        if (newIndex < 0 || newIndex >= cameras.size) return

        // Swap display orders
        val other = cameras[newIndex]
        val tempOrder = camera.display_order
        camera.display_order = other.display_order
        other.display_order = tempOrder
        camera.save()
        other.save()
        refreshGrid()
    }

    private fun confirmDelete(camera: Camera) {
        val dialog = Dialog()
        dialog.headerTitle = "Delete Camera"
        dialog.add(Span("Delete camera '${camera.name}'? This cannot be undone."))
        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Delete") {
                camera.delete()
                Go2rtcAgent.instance?.reconfigure()
                refreshGrid()
                dialog.close()
                Notification.show("Camera '${camera.name}' deleted", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR) }
        )
        dialog.open()
    }

    private fun generateStreamName(name: String): String {
        return name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    private fun validateUrlScheme(url: String): Boolean {
        return url.startsWith("rtsp://", ignoreCase = true)
    }

    private fun saveConfig(key: String, value: String, description: String) {
        val configs = AppConfig.findAll()
        val existing = configs.firstOrNull { it.config_key == key }
        if (existing != null) {
            existing.config_val = value
            existing.save()
        } else {
            AppConfig(config_key = key, config_val = value, description = description).save()
        }
    }
}
