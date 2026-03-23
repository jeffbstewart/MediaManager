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
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.service.CameraAdminService
import net.stewart.mediamanager.service.Go2rtcAgent
import net.stewart.mediamanager.service.UriCredentialRedactor
import java.io.File

@Route(value = "cameras/settings", layout = MainLayout::class)
@PageTitle("Camera Settings")
class CameraSettingsView : KComposite() {

    private lateinit var cameraGrid: Grid<Camera>

    private val root = ui {
        verticalLayout {
            h2("Camera Settings")

            // go2rtc status (always shown) + config fields (non-Docker only)
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-l)")
                style.set("max-width", "600px")

                val statusSpan = Span().apply {
                    val agent = Go2rtcAgent.instance
                    val processAlive = agent?.currentProcess?.isAlive == true
                    val healthy = agent?.isHealthy() == true
                    text = when {
                        agent == null -> "go2rtc: No agent instance"
                        healthy -> "go2rtc: Running (port ${agent.apiPort})"
                        processAlive -> "go2rtc: Process alive but API not responding (port ${agent.apiPort})"
                        else -> "go2rtc: Not running"
                    }
                    style.set("color", if (healthy) "var(--lumo-success-color)" else "var(--lumo-error-color)")
                    style.set("font-weight", "bold")
                }
                add(statusSpan)

                val inDocker = File("/.dockerenv").exists()
                if (!inDocker) {
                    add(Span("go2rtc Configuration").apply {
                        style.set("font-weight", "bold")
                        style.set("font-size", "var(--lumo-font-size-l)")
                        style.set("margin-top", "var(--lumo-space-m)")
                    })

                    val configs = AppConfig.findAll()

                    val go2rtcPathField = textField("go2rtc Binary Path") {
                        width = "100%"
                        value = configs.firstOrNull { it.config_key == "go2rtc_path" }?.config_val ?: ""
                        placeholder = "/usr/local/bin/go2rtc"
                    }

                    val apiPortField = textField("go2rtc API Port") {
                        width = "100%"
                        value = configs.firstOrNull { it.config_key == "go2rtc_api_port" }?.config_val ?: "1984"
                        helperText = "Port for go2rtc HTTP API (default: 1984). Never expose externally."
                    }

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
        cameraGrid.setItems(CameraAdminService.listAll())
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

        val copyBtn = Button(VaadinIcon.COPY.create()) { showDuplicateCameraDialog(camera) }
        copyBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        copyBtn.element.setAttribute("title", "Duplicate")

        val deleteBtn = Button(VaadinIcon.TRASH.create()) { confirmDelete(camera) }
        deleteBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ERROR)
        deleteBtn.element.setAttribute("title", "Delete")

        layout.add(editBtn, testBtn, copyBtn, upBtn, downBtn, deleteBtn)
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
            if (streamNameField.value.isBlank() || streamNameField.value == CameraAdminService.generateStreamName(event.oldValue)) {
                streamNameField.value = CameraAdminService.generateStreamName(event.value)
            }
        }

        val content = VerticalLayout(nameField, rtspUrlField, snapshotUrlField, streamNameField, enabledCheck).apply {
            isPadding = false
        }
        dialog.add(content)

        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Save") {
                try {
                    val camera = CameraAdminService.create(
                        name = nameField.value,
                        rtspUrl = rtspUrlField.value,
                        snapshotUrl = snapshotUrlField.value,
                        streamName = streamNameField.value.ifBlank { CameraAdminService.generateStreamName(nameField.value) },
                        enabled = enabledCheck.value
                    )
                    refreshGrid()
                    dialog.close()
                    Notification.show("Camera '${camera.name}' added", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                } catch (e: IllegalArgumentException) {
                    Notification.show(e.message ?: "Validation error", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )

        dialog.open()
    }

    private fun showEditCameraDialog(camera: Camera) {
        showCameraUrlDialog(
            title = "Edit Camera: ${camera.name}",
            sourceCamera = camera,
            initialName = camera.name,
            initialStreamName = camera.go2rtc_name,
            initialEnabled = camera.enabled,
        ) { name, rtspUrl, snapshotUrl, streamName, enabled ->
            try {
                val updated = CameraAdminService.update(camera.id!!, name, rtspUrl, snapshotUrl, streamName, enabled)
                refreshGrid()
                Notification.show("Camera '${updated.name}' updated", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            } catch (e: IllegalArgumentException) {
                Notification.show(e.message ?: "Validation error", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR)
            }
        }
    }

    private fun showDuplicateCameraDialog(camera: Camera) {
        showCameraUrlDialog(
            title = "Duplicate Camera: ${camera.name}",
            sourceCamera = camera,
            initialName = "${camera.name} (copy)",
            initialStreamName = CameraAdminService.generateStreamName("${camera.name} (copy)"),
            initialEnabled = camera.enabled,
        ) { name, rtspUrl, snapshotUrl, streamName, enabled ->
            try {
                val newCamera = CameraAdminService.create(name, rtspUrl, snapshotUrl,
                    streamName.ifBlank { CameraAdminService.generateStreamName(name) }, enabled)
                refreshGrid()
                Notification.show("Camera '${newCamera.name}' created", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            } catch (e: IllegalArgumentException) {
                Notification.show(e.message ?: "Validation error", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR)
            }
        }
    }

    /**
     * Shared dialog for editing/duplicating a camera with redacted URLs.
     * Shows credentials as `***:***` — on save, restores them from [sourceCamera]
     * only if the host matches (prevents credential exfiltration).
     */
    private fun showCameraUrlDialog(
        title: String,
        sourceCamera: Camera,
        initialName: String,
        initialStreamName: String,
        initialEnabled: Boolean,
        onSave: (name: String, rtspUrl: String, snapshotUrl: String, streamName: String, enabled: Boolean) -> Unit
    ) {
        val dialog = Dialog()
        dialog.headerTitle = title
        dialog.width = "500px"

        val nameField = TextField("Name").apply {
            width = "100%"
            value = initialName
        }
        val streamNameField = TextField("Stream Name").apply {
            width = "100%"
            value = initialStreamName
            helperText = "go2rtc stream identifier"
        }

        // Show redacted URLs in plain TextFields — editable path/query, credentials hidden
        val rtspUrlField = TextField("RTSP URL").apply {
            width = "100%"
            value = UriCredentialRedactor.redact(sourceCamera.rtsp_url)
            helperText = "Credentials shown as ***:*** — preserved on save if host unchanged"
        }
        val snapshotUrlField = TextField("Snapshot URL").apply {
            width = "100%"
            value = UriCredentialRedactor.redact(sourceCamera.snapshot_url)
        }
        val enabledCheck = Checkbox("Enabled").apply { value = initialEnabled }

        nameField.addValueChangeListener { event ->
            if (streamNameField.value == CameraAdminService.generateStreamName(event.oldValue)) {
                streamNameField.value = CameraAdminService.generateStreamName(event.value)
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
                try {
                    onSave(
                        nameField.value.trim(),
                        rtspUrlField.value.trim(),
                        snapshotUrlField.value.trim(),
                        streamNameField.value.trim(),
                        enabledCheck.value
                    )
                    dialog.close()
                } catch (e: IllegalArgumentException) {
                    Notification.show(e.message ?: "Validation error", 5000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )

        dialog.open()
    }

    private fun testSnapshot(camera: Camera) {
        val dialog = Dialog()
        dialog.headerTitle = "Snapshot: ${camera.name}"
        dialog.width = "640px"

        val img = Image("/cam/${camera.id}/snapshot.jpg", "Snapshot of ${camera.name}")
        img.width = "100%"
        img.style.set("border-radius", "var(--lumo-border-radius-m)")

        val refreshBtn = Button("Refresh", VaadinIcon.REFRESH.create()) {
            // Force browser to refetch by adding cache-buster
            img.src = "/cam/${camera.id}/snapshot.jpg?t=${System.currentTimeMillis()}"
        }

        dialog.add(img, refreshBtn)
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.open()
    }

    private fun moveCamera(camera: Camera, direction: Int) {
        val cameras = CameraAdminService.listAll().toMutableList()
        val index = cameras.indexOfFirst { it.id == camera.id }
        if (index < 0) return
        val newIndex = index + direction
        if (newIndex < 0 || newIndex >= cameras.size) return
        cameras.add(newIndex, cameras.removeAt(index))
        CameraAdminService.reorder(cameras.mapNotNull { it.id })
        refreshGrid()
    }

    private fun confirmDelete(camera: Camera) {
        val dialog = Dialog()
        dialog.headerTitle = "Delete Camera"
        dialog.add(Span("Delete camera '${camera.name}'? This cannot be undone."))
        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Delete") {
                CameraAdminService.delete(camera.id!!)
                refreshGrid()
                dialog.close()
                Notification.show("Camera '${camera.name}' deleted", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR) }
        )
        dialog.open()
    }

    private fun CameraAdminService.generateStreamName(name: String): String {
        return name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    private fun saveConfig(key: String, value: String, description: String) {
        val trimmed = value.trim()
        val configs = AppConfig.findAll()
        val existing = configs.firstOrNull { it.config_key == key }
        if (trimmed.isBlank()) {
            // Remove the key so default fallback logic applies
            existing?.delete()
        } else if (existing != null) {
            existing.config_val = trimmed
            existing.save()
        } else {
            AppConfig(config_key = key, config_val = trimmed, description = description).save()
        }
    }
}
