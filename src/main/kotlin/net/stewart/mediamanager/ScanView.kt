package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.count
import com.github.vokorm.desc
import com.github.vokorm.findAll
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route
import com.vaadin.flow.router.PageTitle
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.Broadcaster
import net.stewart.mediamanager.service.QuotaTracker
import net.stewart.mediamanager.service.ScanUpdateEvent
import net.stewart.mediamanager.service.TitleUpdateEvent
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Route(value = "scan", layout = MainLayout::class)
@PageTitle("Barcode Scanner")
class ScanView : KComposite() {

    private lateinit var upcField: TextField
    private lateinit var scanGrid: Grid<BarcodeScan>
    private lateinit var quotaLabel: Span

    private val broadcastListener: (ScanUpdateEvent) -> Unit = {
        ui.ifPresent { ui -> ui.access { refreshGrid(); refreshQuota() } }
    }

    private val titleListener: (TitleUpdateEvent) -> Unit = {
        ui.ifPresent { ui -> ui.access { refreshGrid() } }
    }

    private val root = ui {
        verticalLayout {
            h2("Barcode Scanner")
            span("Start adding a new media item by scanning its UPC barcode with a USB scanner, or type the code in by hand. The system will look up the product automatically and add it to your catalog.") {
                style.set("color", "rgba(255,255,255,0.6)")
                style.set("margin-bottom", "var(--lumo-space-m)")
            }

            quotaLabel = span()

            upcField = textField("UPC") {
                placeholder = "Scan or type UPC barcode"
                allowedCharPattern = "[0-9]"
                isAutofocus = true
                width = "20em"
                addKeyDownListener(Key.ENTER, { handleScan() })
            }

            h4("Recent Scans")
            scanGrid = grid<BarcodeScan> {
                width = "100%"
                isAllRowsVisible = true

                addColumn({ it.upc }).setHeader("UPC").setSortable(false).setWidth("130px").setFlexGrow(0)
                addColumn({ it.lookup_status }).setHeader("Status").setSortable(false).setWidth("100px").setFlexGrow(0)
                addComponentColumn { scan -> buildEnrichmentCell(scan) }
                    .setHeader("Enrichment").setSortable(false).setFlexGrow(0).setWidth("250px")
                addColumn({ it.notes ?: "" }).setHeader("Notes").setSortable(false).setFlexGrow(1)
                addColumn({ it.scanned_at?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: "" })
                    .setHeader("Scanned At").setSortable(false).setWidth("160px").setFlexGrow(0)
            }
        }
    }

    init {
        refreshGrid()
        refreshQuota()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        Broadcaster.register(broadcastListener)
        Broadcaster.registerTitleListener(titleListener)
        upcField.focus()
    }

    override fun onDetach(detachEvent: DetachEvent) {
        Broadcaster.unregister(broadcastListener)
        Broadcaster.unregisterTitleListener(titleListener)
        super.onDetach(detachEvent)
    }

    private fun buildEnrichmentCell(scan: BarcodeScan): HorizontalLayout {
        val layout = HorizontalLayout()
        layout.isPadding = false
        layout.isSpacing = true
        layout.defaultVerticalComponentAlignment =
            com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER

        if (scan.lookup_status != LookupStatus.FOUND.name || scan.media_item_id == null) {
            return layout
        }

        // Find the title(s) linked to this scan's media item
        val joins = MediaItemTitle.findAll().filter { it.media_item_id == scan.media_item_id }
        if (joins.isEmpty()) return layout

        for (join in joins) {
            val title = Title.findById(join.title_id) ?: continue
            val status = try {
                EnrichmentStatus.valueOf(title.enrichment_status)
            } catch (_: Exception) { continue }

            when (status) {
                EnrichmentStatus.ENRICHED -> {
                    val label = Span("✓ ${title.name}")
                    label.style.set("color", "var(--lumo-success-text-color)")
                    layout.add(label)
                }
                EnrichmentStatus.PENDING -> {
                    val label = Span("⏳ Enriching…")
                    label.style.set("color", "var(--lumo-tertiary-text-color)")
                    layout.add(label)
                }
                EnrichmentStatus.SKIPPED, EnrichmentStatus.FAILED, EnrichmentStatus.ABANDONED -> {
                    val statusText = when (status) {
                        EnrichmentStatus.SKIPPED -> "No TMDB match"
                        EnrichmentStatus.FAILED -> "TMDB lookup failed"
                        EnrichmentStatus.ABANDONED -> "TMDB match abandoned"
                        else -> ""
                    }
                    val label = Span("⚠ $statusText")
                    label.style.set("color", "var(--lumo-error-text-color)")
                    layout.add(label)

                    val fixBtn = Button("Fix").apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                        addClickListener {
                            TitleEditDialog(title) { refreshGrid() }.open()
                        }
                    }
                    layout.add(fixBtn)
                }
                EnrichmentStatus.REASSIGNMENT_REQUESTED -> {
                    val label = Span("⏳ Re-enriching…")
                    label.style.set("color", "var(--lumo-tertiary-text-color)")
                    layout.add(label)
                }
            }
        }

        return layout
    }

    private fun handleScan() {
        val upc = upcField.value.trim()

        if (upc.isBlank()) {
            upcField.clear()
            upcField.focus()
            return
        }

        if (upc.length < 8 || upc.length > 14) {
            showError("Invalid UPC length: must be 8\u201314 digits (got ${upc.length})")
            upcField.clear()
            upcField.focus()
            return
        }

        try {
            val exists = BarcodeScan.count { BarcodeScan::upc eq upc } > 0
            if (exists) {
                upcField.clear()
                upcField.focus()
                return
            }

            BarcodeScan(
                upc = upc,
                scanned_at = LocalDateTime.now(),
                lookup_status = LookupStatus.NOT_LOOKED_UP.name
            ).save()

            showSuccess("Scanned: $upc")
            refreshGrid()
            refreshQuota()
        } catch (e: Exception) {
            showError("Database error: ${e.message}")
        }

        upcField.clear()
        upcField.focus()
    }

    private fun refreshGrid() {
        val recentScans = BarcodeScan.findAll(BarcodeScan::scanned_at.desc, range = 0..24)
        scanGrid.setItems(recentScans)
    }

    private fun refreshQuota() {
        val status = QuotaTracker.getStatus()
        quotaLabel.text = "UPC Lookups today: ${status.used} / ${status.limit} (${status.remaining} remaining)"
    }

    private fun showSuccess(message: String) {
        val n = Notification.show(message, 1500, Notification.Position.BOTTOM_START)
        n.addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun showError(message: String) {
        val n = Notification.show(message, 4000, Notification.Position.BOTTOM_START)
        n.addThemeVariants(NotificationVariant.LUMO_ERROR)
    }
}
