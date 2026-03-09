package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*
import com.vaadin.flow.component.html.Hr

@Route(value = "transcodes/status", layout = MainLayout::class)
@PageTitle("Transcoder Status")
class TranscodeStatusView : KComposite() {

    private lateinit var statusLabel: Span
    private lateinit var scanButton: Button

    // Transcoder status panel components
    private lateinit var transcoderSummary: Span
    private lateinit var transcoderCurrentFile: Span
    private lateinit var transcoderProgressBar: ProgressBar
    private lateinit var transcoderProgressLabel: Span
    private lateinit var transcoderEta: Span
    private lateinit var transcoderRecentList: VerticalLayout

    // Overall progress panel components
    private lateinit var overallProgressLabel: Span
    private lateinit var overallPendingLabel: Span
    private lateinit var overallThroughputLabel: Span
    private lateinit var overallEtaLabel: Span
    private lateinit var overallWorkersLabel: Span

    // Buddy status panel components
    private lateinit var buddyActiveList: VerticalLayout
    private lateinit var buddyRecentList: VerticalLayout

    /** Cache the pending counts to avoid expensive NAS file checks on every event. */
    private var cachedPending: PendingWork? = null
    private var lastPendingRefresh: Long = 0
    private val PENDING_CACHE_MS = 60_000L // refresh at most once per minute

    private val nasScanListener: (NasScanProgress) -> Unit = { event ->
        ui.ifPresent { ui ->
            ui.access {
                statusLabel.text = event.message ?: event.phase
                if (event.phase == "COMPLETE" || event.phase == "FAILED") {
                    scanButton.isEnabled = true
                    updateStatusLabel()
                    cachedPending = null // force refresh after scan
                    refreshOverallProgress()
                }
            }
        }
    }

    private val transcoderListener: (TranscoderProgressEvent) -> Unit = { event ->
        ui.ifPresent { ui ->
            ui.access {
                updateTranscoderPanel(event)
                refreshOverallProgress()
            }
        }
    }

    private val buddyListener: (BuddyProgressEvent) -> Unit = { event ->
        ui.ifPresent { ui ->
            ui.access {
                refreshBuddyPanel()
                refreshOverallProgress()
            }
        }
    }

    private val root = ui {
        verticalLayout {
            h2("Transcoder Status")

            horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                isSpacing = true

                scanButton = button("Scan NAS") {
                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                    addClickListener { startScan() }
                }

                button("Clear Failures") {
                    addThemeVariants(ButtonVariant.LUMO_TERTIARY)
                    addClickListener {
                        val cleared = TranscodeLeaseService.clearAllFailures()
                        Notification.show("Cleared $cleared failure record(s)", 2000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                    }
                }

                statusLabel = span()
            }

            // --- Overall Progress Panel ---
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-primary-color-10pct)")
                style.set("border-radius", "var(--lumo-border-radius-m)")
                style.set("margin-bottom", "var(--lumo-space-m)")

                add(Span("Overall Transcode Progress").apply {
                    style.set("font-weight", "bold")
                })

                overallProgressLabel = span("Loading...")
                overallPendingLabel = span().apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-secondary-text-color)")
                    isVisible = false
                }
                overallThroughputLabel = span().apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-secondary-text-color)")
                }
                overallEtaLabel = span().apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-secondary-text-color)")
                    isVisible = false
                }
                overallWorkersLabel = span().apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-secondary-text-color)")
                }
            }

            // --- Transcoder Status Panel ---
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-m)")
                style.set("margin-bottom", "var(--lumo-space-m)")

                add(Span("Background Transcoder").apply {
                    style.set("font-weight", "bold")
                })

                if (CommandLineFlags.disableLocalTranscoding) {
                    add(Span("Local transcoding is disabled (--disable_local_transcoding)").apply {
                        style.set("font-size", "var(--lumo-font-size-s)")
                        style.set("color", "var(--lumo-secondary-text-color)")
                        style.set("font-style", "italic")
                    })
                    add(Span("Transcoding is handled by remote buddy workers.").apply {
                        style.set("font-size", "var(--lumo-font-size-s)")
                        style.set("color", "var(--lumo-secondary-text-color)")
                    })
                }

                transcoderSummary = span(if (CommandLineFlags.disableLocalTranscoding) "" else "Loading...")
                transcoderCurrentFile = span().apply { isVisible = false }

                val localDisabled = CommandLineFlags.disableLocalTranscoding

                transcoderProgressBar = progressBar {
                    min = 0.0
                    max = 100.0
                    value = 0.0
                    width = "100%"
                    isVisible = false
                }

                transcoderProgressLabel = span().apply {
                    isVisible = false
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-secondary-text-color)")
                }

                transcoderEta = span().apply {
                    isVisible = false
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-secondary-text-color)")
                }

                transcoderRecentList = verticalLayout {
                    isPadding = false
                    isSpacing = false
                    isVisible = !localDisabled
                }
            }

            // --- Transcode Buddies Panel ---
            verticalLayout {
                isPadding = true
                isSpacing = true
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("border-radius", "var(--lumo-border-radius-m)")
                style.set("margin-bottom", "var(--lumo-space-m)")

                add(Span("Transcode Buddies").apply {
                    style.set("font-weight", "bold")
                })

                buddyActiveList = verticalLayout {
                    isPadding = false
                    isSpacing = false
                }

                add(Span("Recent:").apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-secondary-text-color)")
                    style.set("margin-top", "var(--lumo-space-s)")
                })

                buddyRecentList = verticalLayout {
                    isPadding = false
                    isSpacing = false
                }
            }
        }
    }

    init {
        updateStatusLabel()
        refreshBuddyPanel()
        refreshOverallProgress()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        Broadcaster.registerNasScanListener(nasScanListener)
        Broadcaster.registerTranscoderListener(transcoderListener)
        Broadcaster.registerBuddyListener(buddyListener)
    }

    override fun onDetach(detachEvent: DetachEvent) {
        Broadcaster.unregisterNasScanListener(nasScanListener)
        Broadcaster.unregisterTranscoderListener(transcoderListener)
        Broadcaster.unregisterBuddyListener(buddyListener)
        super.onDetach(detachEvent)
    }

    private fun updateTranscoderPanel(event: TranscoderProgressEvent) {
        transcoderSummary.text = "${event.totalCompleted} of ${event.totalNeeding} transcoded"

        val eta = event.estimatedSecondsLeft
        if (eta != null && eta > 0) {
            transcoderEta.text = "Estimated time remaining: ${formatDuration(eta)}"
            transcoderEta.isVisible = true
        } else {
            transcoderEta.isVisible = false
        }

        when (event.status) {
            TranscoderStatus.TRANSCODING -> {
                transcoderCurrentFile.text = "Now: ${event.currentFile} — ${event.currentPercent}%"
                transcoderCurrentFile.isVisible = true
                transcoderCurrentFile.style.remove("color")
                transcoderProgressBar.value = event.currentPercent.toDouble()
                transcoderProgressBar.isVisible = true
                transcoderProgressLabel.text = "${event.currentPercent}%"
                transcoderProgressLabel.isVisible = true
            }
            TranscoderStatus.IDLE -> {
                transcoderCurrentFile.isVisible = false
                transcoderProgressBar.isVisible = false
                transcoderProgressLabel.isVisible = false
            }
            TranscoderStatus.ERROR -> {
                transcoderCurrentFile.text = "Error: ${event.currentFile}"
                transcoderCurrentFile.isVisible = true
                transcoderCurrentFile.style.set("color", "var(--lumo-error-text-color)")
                transcoderProgressBar.isVisible = false
                transcoderProgressLabel.isVisible = false
            }
        }

        if (event.recentCompletions.isNotEmpty()) {
            transcoderRecentList.removeAll()
            transcoderRecentList.add(Span("Recent:").apply {
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("color", "var(--lumo-secondary-text-color)")
            })
            for (path in event.recentCompletions.takeLast(5)) {
                transcoderRecentList.add(Span("  \u2713 $path").apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "var(--lumo-success-text-color)")
                })
            }
        }
    }

    private fun refreshBuddyPanel() {
        buddyActiveList.removeAll()
        val activeLeases = TranscodeLeaseService.getActiveLeases()
            .filter { it.buddy_name != "local" }
        if (activeLeases.isEmpty()) {
            buddyActiveList.add(Span("No active buddy workers").apply {
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("color", "var(--lumo-secondary-text-color)")
            })
        } else {
            for (lease in activeLeases) {
                val fileName = lease.relative_path.substringAfterLast('/')
                val status = if (lease.status == LeaseStatus.IN_PROGRESS.name)
                    "${lease.progress_percent}%" else "claimed"
                val encoderInfo = if (lease.encoder != null) " [${lease.encoder}]" else ""
                val typeTag = when (lease.lease_type) {
                    LeaseType.THUMBNAILS.name -> " [thumbs]"
                    LeaseType.SUBTITLES.name -> " [subs]"
                    else -> ""
                }
                buddyActiveList.add(Span("${lease.buddy_name}: $fileName — $status$encoderInfo$typeTag").apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                })
                if (lease.status == LeaseStatus.IN_PROGRESS.name) {
                    buddyActiveList.add(ProgressBar().apply {
                        min = 0.0
                        max = 100.0
                        value = lease.progress_percent.toDouble()
                        width = "100%"
                        style.set("margin-bottom", "var(--lumo-space-xs)")
                    })
                }
            }
        }

        buddyRecentList.removeAll()
        val recentLeases = TranscodeLeaseService.getRecentLeases(5)
            .filter { it.buddy_name != "local" }
        if (recentLeases.isEmpty()) {
            buddyRecentList.add(Span("No recent buddy activity").apply {
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("color", "var(--lumo-secondary-text-color)")
            })
        } else {
            for (lease in recentLeases) {
                val fileName = lease.relative_path.substringAfterLast('/')
                val icon = if (lease.status == LeaseStatus.COMPLETED.name) "\u2713" else "\u2717"
                val color = if (lease.status == LeaseStatus.COMPLETED.name)
                    "var(--lumo-success-text-color)" else "var(--lumo-error-text-color)"
                val encoderInfo = if (lease.encoder != null) " [${lease.encoder}]" else ""
                val typeTag = when (lease.lease_type) {
                    LeaseType.THUMBNAILS.name -> " [thumbs]"
                    LeaseType.SUBTITLES.name -> " [subs]"
                    else -> ""
                }
                buddyRecentList.add(Span("  $icon ${lease.buddy_name}: $fileName$encoderInfo$typeTag").apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", color)
                })
            }
        }
    }

    private fun refreshOverallProgress() {
        try {
            val stats = TranscodeLeaseService.getThroughputStats()

            // Refresh pending counts at most once per minute (expensive NAS check)
            val now = System.currentTimeMillis()
            if (cachedPending == null || now - lastPendingRefresh > PENDING_CACHE_MS) {
                cachedPending = TranscodeLeaseService.countPendingWork()
                lastPendingRefresh = now
            }
            val pending = cachedPending ?: PendingWork(0, 0, 0)

            // Line 1: completed + total bytes
            overallProgressLabel.text = "${stats.totalCompleted} completed  \u00b7  ${stats.formatTotalBytes()} processed"

            // Line 2: pending breakdown (only if there's work remaining)
            if (pending.total > 0) {
                val parts = mutableListOf<String>()
                if (pending.transcodes > 0) parts.add("${pending.transcodes} transcodes")
                if (pending.thumbnails > 0) parts.add("${pending.thumbnails} thumbnails")
                if (pending.subtitles > 0) parts.add("${pending.subtitles} subtitles")
                overallPendingLabel.text = "Pending: ${parts.joinToString(", ")}"
                overallPendingLabel.isVisible = true
            } else {
                overallPendingLabel.isVisible = false
            }

            // Line 3: throughput rates
            val throughputParts = mutableListOf<String>()
            if (stats.transcodeRate > 0) throughputParts.add("%.1f transcodes/hr".format(stats.transcodeRate))
            if (stats.thumbnailRate > 0) throughputParts.add("%.0f thumbs/hr".format(stats.thumbnailRate))
            if (stats.subtitleRate > 0) throughputParts.add("%.0f subs/hr".format(stats.subtitleRate))
            if (stats.bytesPerHour > 0) throughputParts.add(stats.formatBytesPerHour())
            overallThroughputLabel.text = if (throughputParts.isNotEmpty())
                "Throughput: ${throughputParts.joinToString("  \u00b7  ")}" else ""
            overallThroughputLabel.isVisible = throughputParts.isNotEmpty()

            // Line 4: ETA (composite across all work types)
            val eta = stats.estimateSecondsLeft(pending)
            if (eta != null && eta > 0) {
                overallEtaLabel.text = "Estimated time remaining: ${formatDuration(eta)}"
                overallEtaLabel.isVisible = true
            } else {
                overallEtaLabel.isVisible = false
            }

            // Line 5: workers + completed task type counts
            val workerParts = mutableListOf<String>()
            if (stats.activeWorkers > 0) workerParts.add("${stats.activeWorkers} active worker(s)")
            if (stats.failedCount > 0) workerParts.add("${stats.failedCount} failed")
            val (thumbTotal, thumbToday) = TranscodeLeaseService.getThumbnailStats()
            if (thumbTotal > 0 || thumbToday > 0) {
                val todayPart = if (thumbToday > 0) " ($thumbToday today)" else ""
                workerParts.add("$thumbTotal thumbnails$todayPart")
            }
            val (subTotal, subToday) = TranscodeLeaseService.getSubtitleStats()
            if (subTotal > 0 || subToday > 0) {
                val todayPart = if (subToday > 0) " ($subToday today)" else ""
                workerParts.add("$subTotal subtitles$todayPart")
            }
            overallWorkersLabel.text = workerParts.joinToString("  \u00b7  ")
            overallWorkersLabel.isVisible = workerParts.isNotEmpty()
        } catch (e: Exception) {
            overallProgressLabel.text = "Error loading stats"
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "< 1m"
        }
    }

    private fun updateStatusLabel() {
        val linkedCount = Transcode.findAll().count { it.file_path != null }
        val unmatchedCount = DiscoveredFile.findAll().count {
            it.match_status == DiscoveredFileStatus.UNMATCHED.name
        }
        statusLabel.text = "$linkedCount linked, $unmatchedCount unmatched"
    }

    private fun startScan() {
        if (NasScannerService.isRunning()) {
            Notification.show("Scan already in progress", 3000, Notification.Position.BOTTOM_START)
            return
        }
        scanButton.isEnabled = false
        statusLabel.text = "Starting scan..."
        NasScannerService.scan()
    }
}
