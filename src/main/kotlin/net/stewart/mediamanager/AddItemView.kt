package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.AfterNavigationObserver
import java.time.format.DateTimeFormatter

@Route(value = "add", layout = MainLayout::class)
@PageTitle("Add Item")
class AddItemView : KComposite(), AfterNavigationObserver {

    private val log = LoggerFactory.getLogger(AddItemView::class.java)
    private val tmdbService = TmdbService()

    // Zone 1 components
    private lateinit var scanTab: Tab
    private lateinit var searchTab: Tab
    private lateinit var nasTab: Tab
    private lateinit var tabs: Tabs
    private lateinit var scanContent: VerticalLayout
    private lateinit var searchContent: VerticalLayout
    private lateinit var nasContent: VerticalLayout

    // Scan tab
    private lateinit var upcField: TextField
    private lateinit var quotaLabel: Span

    // Search tab
    private lateinit var searchResultsGrid: Grid<TmdbSearchResult>
    private lateinit var searchEntryForm: VerticalLayout
    private lateinit var selectedTitleLabel: Span
    private lateinit var searchFormatCombo: ComboBox<MediaFormat>
    private lateinit var searchSeasonsField: TextField
    private lateinit var searchSeasonsRow: HorizontalLayout
    private var selectedSearchResult: TmdbSearchResult? = null

    // NAS tab
    private lateinit var nasGrid: Grid<DiscoveredFile>
    private var nasSuggestions: Map<Long?, List<ScoredTitle>> = emptyMap()

    // Zone 2
    private lateinit var itemsGrid: Grid<AddItemRow>
    private lateinit var filterToggle: Button
    private var showAllRecent = false
    private var cachedItems: List<AddItemRow> = emptyList()



    // Broadcaster listeners
    private val scanListener: (ScanUpdateEvent) -> Unit = { event ->
        ui.ifPresent { ui -> ui.access {
            log.info("ScanUpdateEvent received: upc={}, status={}", event.upc, event.newStatus)
            refreshItemsGrid()
            refreshQuota()
            if (event.newStatus == LookupStatus.FOUND.name) {
                Notification.show("UPC ${event.upc} looked up: ${event.notes ?: "found"}",
                    3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }
            ui.push()
        } }
    }
    private val titleListener: (TitleUpdateEvent) -> Unit = { event ->
        ui.ifPresent { ui -> ui.access {
            log.info("TitleUpdateEvent received: title={}, status={}", event.name, event.enrichmentStatus)
            refreshItemsGrid()
            if (event.enrichmentStatus == EnrichmentStatus.ENRICHED.name) {
                Notification.show("${event.name} enriched",
                    3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }
            ui.push()
        } }
    }

    private val root = ui {
        verticalLayout {
            h2("Add Item")

            // === Zone 1: Identify ===
            scanTab = Tab(VaadinIcon.BARCODE.create(), Span("Scan Barcode"))
            searchTab = Tab(VaadinIcon.SEARCH.create(), Span("Search TMDB"))
            nasTab = Tab(VaadinIcon.HARDDRIVE.create(), Span("From NAS"))
            tabs = Tabs(scanTab, searchTab, nasTab).apply {
                width = "100%"
            }
            add(tabs)

            // Scan tab content
            scanContent = verticalLayout {
                isPadding = false
                isSpacing = true

                quotaLabel = span()

                horizontalLayout {
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                    isSpacing = true
                    isPadding = false

                    upcField = textField("UPC") {
                        placeholder = "Scan or type UPC barcode"
                        allowedCharPattern = "[0-9]"
                        isAutofocus = true
                        width = "20em"
                        addKeyDownListener(Key.ENTER, { handleScan() })
                    }

                    add(Button("Scan with Camera", VaadinIcon.CAMERA.create()).apply {
                        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                        addClickListener {
                            BarcodeScannerDialog {
                                refreshItemsGrid()
                                refreshQuota()
                            }.open()
                        }
                    })
                }
            }

            // Search tab content
            searchContent = verticalLayout {
                isPadding = false
                isSpacing = true
                isVisible = false

                val searchField = textField {
                    placeholder = "Search TMDB..."
                    width = "100%"
                }
                val mediaTypeCombo = comboBox<String> {
                    setItems("Movie", "TV")
                    value = "Movie"
                    width = "8em"
                }
                val searchButton = button("Search")

                horizontalLayout {
                    width = "100%"
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                    isSpacing = true
                    add(searchField, mediaTypeCombo, searchButton)
                    expand(searchField)
                }

                searchField.addKeyDownListener(Key.ENTER, { _ ->
                    searchButton.click()
                })

                searchResultsGrid = grid {
                    width = "100%"
                    isAllRowsVisible = true
                    isVisible = false

                    addColumn(ComponentRenderer { r ->
                        val url = r.posterPath?.let { "https://image.tmdb.org/t/p/w92$it" }
                        if (url != null) {
                            Image(url, r.title ?: "").apply {
                                height = "60px"; width = "40px"
                                style.set("object-fit", "cover")
                                style.set("border-radius", "2px")
                            }
                        } else {
                            Span("—")
                        }
                    }).setHeader("").setWidth("60px").setFlexGrow(0)

                    addColumn({ it.title ?: "" }).setHeader("Title").setFlexGrow(1)
                    addColumn({ it.releaseYear?.toString() ?: "" }).setHeader("Year").setWidth("70px").setFlexGrow(0)
                    addColumn({ it.mediaType ?: "" }).setHeader("Type").setWidth("80px").setFlexGrow(0)
                    addColumn({ r -> r.overview?.let { o -> if (o.length > 80) o.take(80) + "..." else o } ?: "" })
                        .setHeader("Overview").setFlexGrow(1)

                    addColumn(ComponentRenderer { r ->
                        Button("Add").apply {
                            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                            addClickListener { selectSearchResult(r) }
                        }
                    }).setHeader("").setWidth("80px").setFlexGrow(0)
                }

                searchButton.addClickListener {
                    val query = searchField.value?.trim() ?: ""
                    if (query.isBlank()) return@addClickListener
                    val results = if (mediaTypeCombo.value == "TV") {
                        tmdbService.searchTvMultiple(query)
                    } else {
                        tmdbService.searchMovieMultiple(query)
                    }
                    if (results.isEmpty()) {
                        Notification.show("No results found", 2000, Notification.Position.BOTTOM_START)
                    }
                    searchResultsGrid.setItems(results)
                    searchResultsGrid.isVisible = results.isNotEmpty()
                    searchEntryForm.isVisible = false
                }

                searchEntryForm = verticalLayout {
                    isVisible = false
                    isPadding = false
                    style.set("margin-top", "var(--lumo-space-m)")
                    style.set("padding", "var(--lumo-space-m)")
                    style.set("border", "1px solid rgba(255,255,255,0.1)")
                    style.set("border-radius", "var(--lumo-border-radius-m)")

                    selectedTitleLabel = span {
                        style.set("font-size", "var(--lumo-font-size-l)")
                        style.set("font-weight", "600")
                    }

                    searchFormatCombo = comboBox("Format") {
                        setItems(*MediaFormat.entries
                            .filter { it != MediaFormat.UNKNOWN && it != MediaFormat.OTHER }
                            .toTypedArray())
                        setItemLabelGenerator { fmt ->
                            when (fmt) {
                                MediaFormat.DVD -> "DVD"
                                MediaFormat.BLURAY -> "Blu-ray"
                                MediaFormat.UHD_BLURAY -> "UHD Blu-ray"
                                MediaFormat.HD_DVD -> "HD DVD"
                                else -> fmt.name
                            }
                        }
                        value = MediaFormat.BLURAY
                        width = "12em"
                    }

                    searchSeasonsRow = horizontalLayout {
                        isVisible = false
                        defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                        searchSeasonsField = textField("Seasons") {
                            placeholder = "e.g. 2 or 1-3"
                            width = "10em"
                        }
                    }

                    button("Add to Collection") {
                        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                        addClickListener { addFromSearch() }
                    }
                }
            }

            // NAS tab content
            nasContent = verticalLayout {
                isPadding = false
                isSpacing = true
                isVisible = false

                nasGrid = grid {
                    width = "100%"
                    height = "400px"
                    pageSize = 50

                    addColumn(ComponentRenderer { file ->
                        Span(file.file_name).apply {
                            element.setAttribute("title", file.file_path)
                            style.set("overflow", "hidden")
                            style.set("text-overflow", "ellipsis")
                            style.set("white-space", "nowrap")
                            style.set("display", "block")
                        }
                    }).setHeader("File Name").setFlexGrow(1)
                    addColumn({ it.directory }).setHeader("Directory").setWidth("120px").setFlexGrow(0)
                    addColumn(ComponentRenderer { file ->
                        Span(file.parsed_title ?: "").apply {
                            style.set("overflow", "hidden")
                            style.set("text-overflow", "ellipsis")
                            style.set("white-space", "nowrap")
                            style.set("display", "block")
                        }
                    }).setHeader("Parsed Title").setWidth("200px").setFlexGrow(0)
                    addColumn(ComponentRenderer { file ->
                        if (file.media_type == MediaType.PERSONAL.name) {
                            Span("\u2014")
                        } else {
                            val suggestions = nasSuggestions[file.id]
                            if (suggestions.isNullOrEmpty()) {
                                Span("\u2014")
                            } else {
                                val top = suggestions.first()
                                HorizontalLayout().apply {
                                    isSpacing = true
                                    isPadding = false
                                    defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                                    add(Span("${top.title.name} (${(top.score * 100).toInt()}%)").apply {
                                        style.set("color", "var(--lumo-secondary-text-color)")
                                        style.set("font-size", "var(--lumo-font-size-s)")
                                    })
                                    add(Button("Accept").apply {
                                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_SUCCESS)
                                        addClickListener { acceptNasSuggestion(file, top.title) }
                                    })
                                }
                            }
                        }
                    }).setHeader("Suggestion").setWidth("280px").setFlexGrow(0)
                    addColumn(ComponentRenderer { file ->
                        HorizontalLayout().apply {
                            isSpacing = true
                            isPadding = false
                            if (file.media_type == MediaType.PERSONAL.name) {
                                add(Button("Create").apply {
                                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_SUCCESS)
                                    addClickListener { openCreatePersonalVideoDialog(file) }
                                })
                            } else {
                                add(Button("Link").apply {
                                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                                    addClickListener { openLinkDialog(file) }
                                })
                            }
                            add(Button("Ignore").apply {
                                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                                addClickListener { ignoreNasFile(file) }
                            })
                        }
                    }).setHeader("Actions").setWidth("180px").setFlexGrow(0)
                }
            }

            tabs.addSelectedChangeListener { event ->
                scanContent.isVisible = event.selectedTab == scanTab
                searchContent.isVisible = event.selectedTab == searchTab
                nasContent.isVisible = event.selectedTab == nasTab
                if (event.selectedTab == scanTab) upcField.focus()
                if (event.selectedTab == nasTab) refreshNasGrid()
            }

            // === Zone 2: Items Needing Attention ===
            horizontalLayout {
                width = "100%"
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                style.set("margin-top", "var(--lumo-space-l)")

                add(H3("Items Needing Attention").apply {
                    style.set("flex-grow", "1")
                    style.set("margin", "0")
                })

                filterToggle = Button("Show All Recent").apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                    addClickListener {
                        showAllRecent = !showAllRecent
                        text = if (showAllRecent) "Needs Attention Only" else "Show All Recent"
                        refreshItemsGrid()
                    }
                }
                add(filterToggle)
            }

            itemsGrid = grid {
                width = "100%"
                isAllRowsVisible = true

                addColumn(ComponentRenderer { row ->
                    val posterUrl = row.posterUrl
                    if (posterUrl != null) {
                        Image(posterUrl, row.displayName).apply {
                            width = "34px"; height = "50px"
                            style.set("object-fit", "cover")
                            style.set("border-radius", "2px")
                        }
                    } else {
                        Span("")
                    }
                }).setHeader("").setWidth("50px").setFlexGrow(0)

                addColumn({ it.displayName }).setHeader("Title / Product").setFlexGrow(1)

                addColumn({ it.formatLabel }).setHeader("Format").setWidth("100px").setFlexGrow(0)

                addColumn(ComponentRenderer { row ->
                    val missing = mutableListOf<String>()
                    when (row.enrichmentStatus) {
                        "ENRICHED" -> {} // ok
                        "PENDING", "REASSIGNMENT_REQUESTED" -> missing.add("enriching\u2026")
                        "NOT_LOOKED_UP" -> missing.add("UPC lookup\u2026")
                        "FAILED" -> missing.add("enrichment failed")
                        "SKIPPED" -> missing.add("no TMDB match")
                        "ABANDONED" -> missing.add("enrichment abandoned")
                        else -> missing.add("enrichment: ${row.enrichmentStatus}")
                    }
                    if (!row.hasPurchaseInfo) missing.add("purchase info")
                    if (row.photoCount == 0) missing.add("photos")

                    if (missing.isEmpty()) {
                        Span("\u2713 Complete").apply {
                            style.set("color", "var(--lumo-success-text-color)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        }
                    } else {
                        Span("Needs: ${missing.joinToString(", ")}").apply {
                            style.set("color", "var(--lumo-tertiary-text-color)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        }
                    }
                }).setHeader("Needs").setFlexGrow(1)

                addColumn({ it.sourceLabel }).setHeader("Source").setWidth("80px").setFlexGrow(0)

                addColumn({ it.createdAt?.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")) ?: "" })
                    .setHeader("Added").setWidth("100px").setFlexGrow(0)

                addItemClickListener { event ->
                    val item = event.item
                    if (item.mediaItemId != null) {
                        ui.ifPresent { it.navigate("item/${item.mediaItemId}") }
                    }
                }
            }
        }
    }

    init {
        refreshItemsGrid()
        refreshQuota()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        Broadcaster.register(scanListener)
        Broadcaster.registerTitleListener(titleListener)
        refreshItemsGrid()
        refreshQuota()
        upcField.focus()
    }

    override fun onDetach(detachEvent: DetachEvent) {
        Broadcaster.unregister(scanListener)
        Broadcaster.unregisterTitleListener(titleListener)
        super.onDetach(detachEvent)
    }

    override fun afterNavigation(event: AfterNavigationEvent) {
        refreshItemsGrid()
        refreshQuota()
    }

    // ==================== Zone 1: Scan Tab ====================

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
            val existingScan = BarcodeScan.findAll().firstOrNull { it.upc == upc }
            if (existingScan != null) {
                val titleName = findTitleForScan(existingScan)
                val detail = if (titleName != null) " ($titleName)" else ""
                Notification.show("Already scanned: $upc$detail", 3000, Notification.Position.BOTTOM_START)
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
            refreshItemsGrid()
            refreshQuota()
        } catch (e: Exception) {
            showError("Database error: ${e.message}")
        }

        upcField.clear()
        upcField.focus()
    }

    private fun refreshQuota() {
        val status = QuotaTracker.getStatus()
        quotaLabel.text = "UPC Lookups today: ${status.used} / ${status.limit} (${status.remaining} remaining)"
    }

    private fun findTitleForScan(scan: BarcodeScan): String? {
        val mediaItemId = scan.media_item_id ?: return null
        val join = MediaItemTitle.findAll().firstOrNull { it.media_item_id == mediaItemId } ?: return null
        return Title.findById(join.title_id)?.name
    }

    // ==================== Zone 1: Search Tab ====================

    private fun selectSearchResult(result: TmdbSearchResult) {
        selectedSearchResult = result
        val yearStr = result.releaseYear?.let { " ($it)" } ?: ""
        selectedTitleLabel.text = "${result.title}$yearStr"
        searchSeasonsRow.isVisible = result.mediaType == MediaType.TV.name
        searchSeasonsField.value = ""
        searchEntryForm.isVisible = true
    }

    private fun addFromSearch() {
        val result = selectedSearchResult ?: return
        val tmdbId = result.tmdbId ?: return
        val format = searchFormatCombo.value ?: run {
            Notification.show("Please select a format", 2000, Notification.Position.BOTTOM_START)
            return
        }
        val now = LocalDateTime.now()

        val seasonsText = searchSeasonsField.value?.trim()?.takeIf { it.isNotBlank() }
        val seasonsValue = seasonsText?.let { parseSeasonsInput(it) }

        // Dedup Title
        val tmdbKey = result.tmdbKey() ?: TmdbId(tmdbId, MediaType.MOVIE)
        var title = Title.findAll().firstOrNull { it.tmdbKey() == tmdbKey }
        val isNewTitle = title == null
        if (title == null) {
            title = Title(
                name = result.title ?: "Unknown",
                media_type = tmdbKey.typeString,
                tmdb_id = tmdbKey.id,
                release_year = result.releaseYear,
                description = result.overview,
                poster_path = result.posterPath,
                enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name,
                created_at = now,
                updated_at = now
            )
            title.save()
            SearchIndexService.onTitleChanged(title.id!!)
        }

        val mediaItem = MediaItem(
            media_format = format.name,
            entry_source = EntrySource.MANUAL.name,
            product_name = result.title,
            title_count = 1,
            expansion_status = ExpansionStatus.SINGLE.name,
            created_at = now,
            updated_at = now
        )
        mediaItem.save()

        MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!,
            disc_number = 1,
            seasons = seasonsValue
        ).save()

        if (isNewTitle) {
            WishListService.fulfillMediaWishes(tmdbKey)
        }

        val yearStr = result.releaseYear?.let { " ($it)" } ?: ""
        showSuccess("Added: ${result.title}$yearStr as ${format.name}")

        searchEntryForm.isVisible = false
        selectedSearchResult = null
        refreshItemsGrid()
    }

    // ==================== Zone 1: NAS Tab ====================

    private fun refreshNasGrid() {
        val unmatched = DiscoveredFile.findAll()
            .filter { it.match_status == DiscoveredFileStatus.UNMATCHED.name }
            .sortedBy { it.parsed_title?.lowercase() }

        val allTitles = Title.findAll()
        nasSuggestions = unmatched.associate { file ->
            if (file.media_type == MediaType.PERSONAL.name) {
                file.id to emptyList()
            } else {
                val query = file.parsed_title ?: file.file_name
                file.id to FuzzyMatchService.findSuggestions(query, allTitles)
            }
        }

        nasGrid.setItems(unmatched)
    }

    private fun acceptNasSuggestion(file: DiscoveredFile, title: Title) {
        val count = linkDiscoveredFileToTitle(file, title)
        refreshNasGrid()
        refreshItemsGrid()
        updateUnmatchedBadge()
        val msg = if (count == 1) "Linked to ${title.name}" else "Linked $count episodes to ${title.name}"
        showSuccess(msg)
    }

    private fun openLinkDialog(file: DiscoveredFile) {
        ui.ifPresent { it.navigate("transcodes/unmatched") }
    }

    private fun openCreatePersonalVideoDialog(file: DiscoveredFile) {
        ui.ifPresent { it.navigate("transcodes/unmatched") }
    }

    private fun ignoreNasFile(file: DiscoveredFile) {
        val fresh = DiscoveredFile.findById(file.id!!) ?: return
        fresh.match_status = DiscoveredFileStatus.IGNORED.name
        fresh.save()
        refreshNasGrid()
        updateUnmatchedBadge()
    }

    private fun updateUnmatchedBadge() {
        (ui.orElse(null)?.children
            ?.filter { it is MainLayout }
            ?.findFirst()?.orElse(null) as? MainLayout)
            ?.refreshUnmatchedBadge()
    }

    // ==================== Zone 2: Items Grid ====================

    /** Unified row model for Zone 2 grid — represents either a MediaItem or a pending BarcodeScan. */
    data class AddItemRow(
        val mediaItemId: Long?,
        val barcodeScanId: Long?,
        val displayName: String,
        val formatLabel: String,
        val enrichmentStatus: String,
        val hasPurchaseInfo: Boolean,
        val photoCount: Int,
        val sourceLabel: String,
        val posterUrl: String?,
        val createdAt: LocalDateTime?,
        val titleId: Long?,
        val upc: String?
    ) {
        val needsAttention: Boolean
            get() = enrichmentStatus !in setOf("ENRICHED") ||
                    !hasPurchaseInfo ||
                    photoCount == 0
    }

    private fun refreshItemsGrid() {
        val cutoff = LocalDateTime.now().minusDays(30)

        // Build title map and photo counts
        val allLinks = MediaItemTitle.findAll()
        val allTitles = Title.findAll().associateBy { it.id }
        val titlesByItem = allLinks.groupBy { it.media_item_id }
        val photoCounts = OwnershipPhotoService.countByMediaItem()

        val rows = mutableListOf<AddItemRow>()

        // Recent MediaItems
        val recentItems = MediaItem.findAll()
            .filter { it.created_at != null && it.created_at!! >= cutoff }
            .sortedByDescending { it.created_at }

        for (item in recentItems) {
            val links = titlesByItem[item.id] ?: emptyList()
            val titles = links.mapNotNull { allTitles[it.title_id] }
            val primaryTitle = titles.firstOrNull()
            val displayName = primaryTitle?.name ?: item.product_name ?: item.upc ?: "Unknown"
            val enrichmentStatus = primaryTitle?.enrichment_status ?: "PENDING"
            val posterUrl = primaryTitle?.posterUrl(PosterSize.THUMBNAIL)
            val hasPurchase = item.purchase_price != null || item.purchase_place != null || item.purchase_date != null

            rows.add(AddItemRow(
                mediaItemId = item.id,
                barcodeScanId = null,
                displayName = displayName,
                formatLabel = item.media_format.replace("_", " "),
                enrichmentStatus = enrichmentStatus,
                hasPurchaseInfo = hasPurchase,
                photoCount = photoCounts[item.id] ?: 0,
                sourceLabel = if (item.entry_source == EntrySource.MANUAL.name) "TMDB" else "UPC",
                posterUrl = posterUrl,
                createdAt = item.created_at,
                titleId = primaryTitle?.id,
                upc = item.upc
            ))
        }

        // Pending BarcodeScan records (NOT_LOOKED_UP — no MediaItem yet)
        val pendingScans = BarcodeScan.findAll()
            .filter { it.lookup_status == LookupStatus.NOT_LOOKED_UP.name }
            .sortedByDescending { it.scanned_at }

        for (scan in pendingScans) {
            // Skip if already represented by a MediaItem row
            if (rows.any { it.upc == scan.upc }) continue

            rows.add(AddItemRow(
                mediaItemId = null,
                barcodeScanId = scan.id,
                displayName = "UPC: ${scan.upc}",
                formatLabel = "",
                enrichmentStatus = "NOT_LOOKED_UP",
                hasPurchaseInfo = false,
                photoCount = 0,
                sourceLabel = "UPC",
                posterUrl = null,
                createdAt = scan.scanned_at,
                titleId = null,
                upc = scan.upc
            ))
        }

        cachedItems = rows
        applyFilter()
    }

    private fun applyFilter() {
        val items = if (showAllRecent) cachedItems else cachedItems.filter { it.needsAttention }
        itemsGrid.setItems(items)
    }

    // ==================== Helpers ====================

    private fun parseSeasonsInput(input: String): String {
        if (input.equals("all", ignoreCase = true)) return input.lowercase()
        val rangeMatch = Regex("""^(\d+)\s*-\s*(\d+)$""").matchEntire(input)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toInt()
            val end = rangeMatch.groupValues[2].toInt()
            return (start..end).joinToString(", ") { "S$it" }
        }
        val parts = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.all { it.matches(Regex("""\d+""")) }) {
            return parts.joinToString(", ") { "S$it" }
        }
        val single = input.toIntOrNull()
        if (single != null) return "S$single"
        return input
    }

    private fun showSuccess(message: String) {
        Notification.show(message, 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun showError(message: String) {
        Notification.show(message, 4000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_ERROR)
    }
}
