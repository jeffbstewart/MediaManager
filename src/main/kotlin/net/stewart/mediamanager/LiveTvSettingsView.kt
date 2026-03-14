package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
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
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.entity.LiveTvChannel
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.service.HdHomeRunService
import net.stewart.mediamanager.service.LiveTvStreamManager

@Route(value = "live-tv/settings", layout = MainLayout::class)
@PageTitle("Live TV Settings")
class LiveTvSettingsView : KComposite() {

    private lateinit var tunerGrid: Grid<LiveTvTuner>
    private lateinit var channelGrid: Grid<LiveTvChannel>
    private lateinit var statusSpan: Span

    private val root = ui {
        verticalLayout {
            h2("Live TV Settings")

            // Status card
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-l)")
                style.set("max-width", "800px")

                statusSpan = Span()
                add(statusSpan)
                updateStatus()
            }

            // Tuner card
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-l)")
                style.set("margin-top", "var(--lumo-space-l)")

                val headerRow = HorizontalLayout().apply {
                    width = "100%"
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    add(Span("Tuners").apply {
                        style.set("font-weight", "bold")
                        style.set("font-size", "var(--lumo-font-size-l)")
                        style.set("flex-grow", "1")
                    })
                    val addBtn = Button("Add Tuner", VaadinIcon.PLUS.create()) {
                        showAddTunerDialog()
                    }
                    addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                    add(addBtn)
                }
                add(headerRow)

                tunerGrid = Grid(LiveTvTuner::class.java, false)
                tunerGrid.width = "100%"
                tunerGrid.addColumn { it.name }.setHeader("Name").setAutoWidth(true)
                tunerGrid.addColumn { it.ip_address }.setHeader("IP Address").setAutoWidth(true)
                tunerGrid.addColumn { it.model_number }.setHeader("Model").setAutoWidth(true)
                tunerGrid.addColumn { it.tuner_count }.setHeader("Tuners").setAutoWidth(true)
                tunerGrid.addColumn { if (it.enabled) "Yes" else "No" }.setHeader("Enabled").setAutoWidth(true)
                tunerGrid.addColumn(ComponentRenderer { tuner -> createTunerActionButtons(tuner) }).setHeader("Actions").setAutoWidth(true)
                add(tunerGrid)

                refreshTunerGrid()
            }

            // Settings card
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-l)")
                style.set("margin-top", "var(--lumo-space-l)")
                style.set("max-width", "600px")

                add(Span("Settings").apply {
                    style.set("font-weight", "bold")
                    style.set("font-size", "var(--lumo-font-size-l)")
                })

                val configs = AppConfig.findAll()

                // Content rating minimum
                val ratingChoices = ContentRating.ceilingChoices()
                val currentRating = configs.firstOrNull { it.config_key == "live_tv_min_rating" }
                    ?.config_val?.toIntOrNull() ?: 4
                val ratingCombo = ComboBox<Pair<Int, String>>("Minimum Content Rating for Live TV").apply {
                    width = "100%"
                    setItems(ratingChoices)
                    setItemLabelGenerator { it.second }
                    value = ratingChoices.firstOrNull { it.first == currentRating }
                    helperText = "Users must have a rating ceiling at or above this level to access Live TV"
                }

                // Max concurrent streams
                val maxStreamsField = IntegerField("Max Concurrent Streams").apply {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "live_tv_max_streams" }
                        ?.config_val?.toIntOrNull() ?: 2
                    min = 1
                    max = 10
                    helperText = "Global limit on simultaneous live TV FFmpeg processes"
                }

                // Idle timeout
                val idleTimeoutField = IntegerField("Idle Timeout (seconds)").apply {
                    width = "100%"
                    value = configs.firstOrNull { it.config_key == "live_tv_idle_timeout_seconds" }
                        ?.config_val?.toIntOrNull() ?: 15
                    min = 5
                    max = 300
                    helperText = "Streams idle longer than this are stopped (default 15s)"
                }

                add(ratingCombo, maxStreamsField, idleTimeoutField)

                button("Save Settings") {
                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                    addClickListener {
                        val ratingVal = ratingCombo.value?.first?.toString() ?: "4"
                        saveConfig("live_tv_min_rating", ratingVal, "Minimum content rating level for Live TV access")
                        saveConfig("live_tv_max_streams", maxStreamsField.value?.toString() ?: "2", "Max concurrent live TV streams")
                        saveConfig("live_tv_idle_timeout_seconds", idleTimeoutField.value?.toString() ?: "15", "Idle timeout for live TV streams in seconds")
                        Notification.show("Live TV settings saved", 3000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                    }
                }
            }

            // Channel grid
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-l)")
                style.set("margin-top", "var(--lumo-space-l)")

                add(Span("Channels").apply {
                    style.set("font-weight", "bold")
                    style.set("font-size", "var(--lumo-font-size-l)")
                })

                channelGrid = Grid(LiveTvChannel::class.java, false)
                channelGrid.width = "100%"
                channelGrid.addColumn { it.guide_number }.setHeader("Guide #").setAutoWidth(true).setSortable(true)
                channelGrid.addColumn { it.guide_name }.setHeader("Name").setAutoWidth(true).setSortable(true)
                channelGrid.addColumn { tunerName(it.tuner_id) }.setHeader("Tuner").setAutoWidth(true)
                channelGrid.addColumn(ComponentRenderer { ch -> createQualityCombo(ch) }).setHeader("Quality").setAutoWidth(true)
                channelGrid.addColumn { it.tags }.setHeader("Tags").setAutoWidth(true)
                channelGrid.addColumn(ComponentRenderer { ch -> createEnabledToggle(ch) }).setHeader("Enabled").setAutoWidth(true)
                add(channelGrid)

                refreshChannelGrid()
            }
        }
    }

    private fun updateStatus() {
        val tunerCount = LiveTvTuner.findAll().count { it.enabled }
        val activeStreams = LiveTvStreamManager.activeStreamCount()
        statusSpan.text = "$tunerCount tuner(s) configured, $activeStreams active stream(s)"
        statusSpan.style.set("font-weight", "bold")
    }

    private fun refreshTunerGrid() {
        tunerGrid.setItems(LiveTvTuner.findAll().sortedBy { it.name })
        updateStatus()
    }

    private fun refreshChannelGrid() {
        channelGrid.setItems(LiveTvChannel.findAll().sortedWith(
            compareBy<LiveTvChannel> { it.tuner_id }
                .thenBy { it.guide_number.toDoubleOrNull() ?: Double.MAX_VALUE }
        ))
    }

    private fun tunerName(tunerId: Long): String {
        return LiveTvTuner.findById(tunerId)?.name ?: "Unknown"
    }

    private fun createQualityCombo(channel: LiveTvChannel): ComboBox<Int> {
        return ComboBox<Int>().apply {
            setItems(1, 2, 3, 4, 5)
            setItemLabelGenerator { stars ->
                when (stars) {
                    1 -> "1 - Poor"
                    2 -> "2 - Fair"
                    3 -> "3 - Unrated"
                    4 -> "4 - Good"
                    5 -> "5 - Excellent"
                    else -> stars.toString()
                }
            }
            value = channel.reception_quality
            width = "160px"
            addValueChangeListener { event ->
                if (event.isFromClient && event.value != null) {
                    val fresh = LiveTvChannel.findById(channel.id!!) ?: return@addValueChangeListener
                    fresh.reception_quality = event.value
                    fresh.save()
                }
            }
        }
    }

    private fun createEnabledToggle(channel: LiveTvChannel): Checkbox {
        return Checkbox().apply {
            value = channel.enabled
            addValueChangeListener { event ->
                if (event.isFromClient) {
                    val fresh = LiveTvChannel.findById(channel.id!!) ?: return@addValueChangeListener
                    fresh.enabled = event.value
                    fresh.save()
                }
            }
        }
    }

    private fun createTunerActionButtons(tuner: LiveTvTuner): HorizontalLayout {
        val layout = HorizontalLayout()
        layout.isSpacing = true

        val editBtn = Button(VaadinIcon.EDIT.create()) { showEditTunerDialog(tuner) }
        editBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        editBtn.element.setAttribute("title", "Edit")

        val refreshBtn = Button(VaadinIcon.REFRESH.create()) { refreshChannels(tuner) }
        refreshBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        refreshBtn.element.setAttribute("title", "Refresh channels")

        val enableBtn = Button(if (tuner.enabled) VaadinIcon.EYE.create() else VaadinIcon.EYE_SLASH.create()) {
            val fresh = LiveTvTuner.findById(tuner.id!!) ?: return@Button
            fresh.enabled = !fresh.enabled
            fresh.save()
            refreshTunerGrid()
            refreshChannelGrid()
        }
        enableBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        enableBtn.element.setAttribute("title", if (tuner.enabled) "Disable" else "Enable")

        val deleteBtn = Button(VaadinIcon.TRASH.create()) { confirmDeleteTuner(tuner) }
        deleteBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ERROR)
        deleteBtn.element.setAttribute("title", "Delete")

        layout.add(editBtn, refreshBtn, enableBtn, deleteBtn)
        return layout
    }

    private fun showAddTunerDialog() {
        val dialog = Dialog()
        dialog.headerTitle = "Add Tuner"
        dialog.width = "500px"

        val ipField = TextField("HDHomeRun IP Address").apply {
            width = "100%"
            placeholder = "192.168.1.100"
        }

        val statusLabel = Span().apply {
            style.set("font-size", "var(--lumo-font-size-s)")
        }

        val nameField = TextField("Name").apply { width = "100%"; isEnabled = false }
        val modelField = TextField("Model").apply { width = "100%"; isEnabled = false }
        val tunerCountField = IntegerField("Tuner Count").apply { width = "100%"; isEnabled = false }
        val deviceIdField = TextField("Device ID").apply { width = "100%"; isEnabled = false }
        val firmwareField = TextField("Firmware").apply { width = "100%"; isEnabled = false }

        var discoveryResult: HdHomeRunService.TunerDiscoveryResult? = null

        val validateBtn = Button("Validate", VaadinIcon.CHECK.create()) {
            val ip = ipField.value.trim()
            if (ip.isBlank()) {
                statusLabel.text = "Enter an IP address"
                statusLabel.style.set("color", "var(--lumo-error-color)")
                return@Button
            }

            statusLabel.text = "Connecting..."
            statusLabel.style.set("color", "var(--lumo-body-text-color)")

            val result = HdHomeRunService.discoverDevice(ip)
            if (result != null) {
                discoveryResult = result
                nameField.value = result.friendlyName
                nameField.isEnabled = true
                modelField.value = result.modelNumber
                tunerCountField.value = result.tunerCount
                deviceIdField.value = result.deviceId
                firmwareField.value = result.firmwareVersion
                statusLabel.text = "Device found: ${result.friendlyName}"
                statusLabel.style.set("color", "var(--lumo-success-color)")
            } else {
                discoveryResult = null
                statusLabel.text = "No HDHomeRun found at $ip"
                statusLabel.style.set("color", "var(--lumo-error-color)")
            }
        }
        validateBtn.addThemeVariants(ButtonVariant.LUMO_SMALL)

        val ipRow = HorizontalLayout(ipField, validateBtn).apply {
            width = "100%"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.END
        }

        val content = VerticalLayout(ipRow, statusLabel, nameField, modelField, tunerCountField, deviceIdField, firmwareField).apply {
            isPadding = false
        }
        dialog.add(content)

        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Save") {
                val result = discoveryResult
                if (result == null) {
                    Notification.show("Validate the device first", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }

                val tuner = LiveTvTuner(
                    name = nameField.value.trim().ifBlank { result.friendlyName },
                    device_id = result.deviceId,
                    ip_address = result.ipAddress,
                    model_number = result.modelNumber,
                    tuner_count = result.tunerCount,
                    firmware_version = result.firmwareVersion
                )
                tuner.save()

                // Auto-sync channels
                val syncResult = HdHomeRunService.syncChannels(tuner.id!!, tuner.ip_address)

                refreshTunerGrid()
                refreshChannelGrid()
                dialog.close()

                val msg = if (syncResult != null) {
                    "Tuner '${tuner.name}' added — ${syncResult.added} channels imported"
                } else {
                    "Tuner '${tuner.name}' added (channel sync failed)"
                }
                Notification.show(msg, 5000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )

        dialog.open()
    }

    private fun showEditTunerDialog(tuner: LiveTvTuner) {
        val dialog = Dialog()
        dialog.headerTitle = "Edit Tuner: ${tuner.name}"
        dialog.width = "500px"

        val nameField = TextField("Name").apply { width = "100%"; value = tuner.name }
        val ipField = TextField("IP Address").apply { width = "100%"; value = tuner.ip_address }

        val content = VerticalLayout(nameField, ipField).apply { isPadding = false }
        dialog.add(content)

        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Save") {
                if (nameField.value.isBlank()) {
                    Notification.show("Name is required", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }

                val fresh = LiveTvTuner.findById(tuner.id!!) ?: return@Button
                fresh.name = nameField.value.trim()

                // If IP changed, re-validate
                val newIp = ipField.value.trim()
                if (newIp != fresh.ip_address && newIp.isNotBlank()) {
                    val result = HdHomeRunService.discoverDevice(newIp)
                    if (result != null) {
                        fresh.ip_address = newIp
                        fresh.device_id = result.deviceId
                        fresh.model_number = result.modelNumber
                        fresh.tuner_count = result.tunerCount
                        fresh.firmware_version = result.firmwareVersion
                    } else {
                        Notification.show("Cannot reach HDHomeRun at $newIp — IP not changed", 5000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    }
                }
                fresh.save()
                refreshTunerGrid()
                dialog.close()
                Notification.show("Tuner '${fresh.name}' updated", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )

        dialog.open()
    }

    private fun refreshChannels(tuner: LiveTvTuner) {
        val result = HdHomeRunService.syncChannels(tuner.id!!, tuner.ip_address)
        refreshChannelGrid()
        updateStatus()
        if (result != null) {
            Notification.show(
                "Channel sync: ${result.added} added, ${result.updated} updated, ${result.deleted} deleted",
                5000, Notification.Position.BOTTOM_START
            ).addThemeVariants(NotificationVariant.LUMO_SUCCESS)
        } else {
            Notification.show("Failed to sync channels from ${tuner.ip_address}", 5000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }

    private fun confirmDeleteTuner(tuner: LiveTvTuner) {
        val channelCount = LiveTvChannel.findAll().count { it.tuner_id == tuner.id }
        val dialog = Dialog()
        dialog.headerTitle = "Delete Tuner"
        dialog.add(Span("Delete tuner '${tuner.name}' and its $channelCount channel(s)? This cannot be undone."))
        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Delete") {
                tuner.delete()
                refreshTunerGrid()
                refreshChannelGrid()
                dialog.close()
                Notification.show("Tuner '${tuner.name}' deleted", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR) }
        )
        dialog.open()
    }

    private fun saveConfig(key: String, value: String, description: String) {
        val trimmed = value.trim()
        val configs = AppConfig.findAll()
        val existing = configs.firstOrNull { it.config_key == key }
        if (trimmed.isBlank()) {
            existing?.delete()
        } else if (existing != null) {
            existing.config_val = trimmed
            existing.save()
        } else {
            AppConfig(config_key = key, config_val = trimmed, description = description).save()
        }
    }
}
