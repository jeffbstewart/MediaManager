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
import com.vaadin.flow.component.dialog.Dialog
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
    private lateinit var filterCombo: ComboBox<ItemFilter>
    private var currentFilter = ItemFilter.NEEDS_ATTENTION
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

                filterCombo = ComboBox<ItemFilter>().apply {
                    setItems(*ItemFilter.entries.toTypedArray())
                    setItemLabelGenerator { it.label }
                    value = currentFilter
                    width = "14em"
                    addValueChangeListener {
                        currentFilter = it.value ?: ItemFilter.NEEDS_ATTENTION
                        applyFilter()
                    }
                }
                add(filterCombo)
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
                        "NOT_FOUND" -> missing.add("UPC not in database")
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

                addColumn(ComponentRenderer { row ->
                    if (row.barcodeScanId != null && row.mediaItemId == null) {
                        HorizontalLayout().apply {
                            isSpacing = true
                            isPadding = false
                            add(Button("Link", VaadinIcon.LINK.create()).apply {
                                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                                addClickListener { openLinkScanDialog(row) }
                            })
                            add(Button(VaadinIcon.TRASH.create()).apply {
                                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_ICON)
                                element.setAttribute("title", "Delete scan ${row.upc}")
                                addClickListener { confirmDeleteScan(row) }
                            })
                        }
                    } else {
                        Span("")
                    }
                }).setHeader("").setWidth("140px").setFlexGrow(0)

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

        try {
            when (val result = BarcodeScanService.submit(upc)) {
                is BarcodeScanService.SubmitResult.Created -> {
                    showSuccess("Scanned: ${result.upc}")
                    refreshItemsGrid()
                    refreshQuota()
                }
                is BarcodeScanService.SubmitResult.Duplicate -> {
                    Notification.show("Already scanned: ${result.upc} (${result.titleName})", 3000, Notification.Position.BOTTOM_START)
                }
                is BarcodeScanService.SubmitResult.Invalid -> {
                    showError(result.reason)
                }
            }
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
        val seasonsValue = seasonsText?.let {
            parseSeasonsInput(it) ?: run {
                showError("Invalid seasons format. Use numbers like: 2 or 1, 2 or 1-3")
                return
            }
        }

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

        val join = MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!,
            disc_number = 1,
            seasons = seasonsValue
        )
        join.save()

        WishListService.syncPhysicalOwnership(title.id!!)
        WishListService.fulfillMediaWishes(tmdbKey)

        val yearStr = result.releaseYear?.let { " ($it)" } ?: ""
        showSuccess("Added: ${result.title}$yearStr as ${format.name}")

        searchEntryForm.isVisible = false
        selectedSearchResult = null
        refreshItemsGrid()
    }

    // ==================== Delete Stuck Scan ====================

    private fun confirmDeleteScan(row: AddItemRow) {
        val scanId = row.barcodeScanId ?: return
        val scan = BarcodeScan.findById(scanId) ?: return
        val photos = OwnershipPhotoService.findByUpc(scan.upc)

        if (photos.isEmpty()) {
            // No photos — just delete immediately
            scan.delete()
            showSuccess("Deleted scan ${scan.upc}")
            refreshItemsGrid()
            return
        }

        val dlg = Dialog()
        dlg.headerTitle = "Delete scan?"
        dlg.add(Span("Delete UPC ${scan.upc} and its ${photos.size} ownership photo(s)?"))
        dlg.footer.add(
            Button("Cancel") { dlg.close() },
            Button("Delete").apply {
                addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR)
                addClickListener {
                    dlg.close()
                    photos.forEach { OwnershipPhotoService.delete(it.id!!) }
                    scan.delete()
                    showSuccess("Deleted scan ${scan.upc}")
                    refreshItemsGrid()
                }
            }
        )
        dlg.open()
    }

    // ==================== Link Stuck Scan Dialog ====================

    private fun openLinkScanDialog(row: AddItemRow) {
        val scanId = row.barcodeScanId ?: return
        val scan = BarcodeScan.findById(scanId) ?: return

        val dlg = Dialog()
        dlg.headerTitle = "Link UPC ${scan.upc} to Title"
        dlg.width = "600px"

        // Show ownership photos for this UPC
        val photos = OwnershipPhotoService.findByUpc(scan.upc)
        val photoRow = if (photos.isNotEmpty()) {
            HorizontalLayout().apply {
                isSpacing = true
                isPadding = false
                style.set("overflow-x", "auto")
                style.set("margin-bottom", "var(--lumo-space-s)")
                for (photo in photos) {
                    add(Image("/ownership-photos/${photo.id}", "Ownership photo").apply {
                        height = "120px"
                        style.set("border-radius", "4px")
                        style.set("cursor", "pointer")
                        style.set("flex-shrink", "0")
                        element.addEventListener("click") {
                            element.executeJs(
                                "window.open('/ownership-photos/' + $0 + '?download=1', '_blank')",
                                photo.id!!
                            )
                        }
                    })
                }
            }
        } else null

        val searchField = TextField("Search TMDB").apply {
            width = "100%"
            placeholder = "Title name..."
        }
        val mediaTypeCombo = ComboBox<String>("Type").apply {
            setItems("Movie", "TV")
            value = "Movie"
            width = "8em"
        }
        val searchBtn = Button("Search")
        val searchRow = HorizontalLayout(searchField, mediaTypeCombo, searchBtn).apply {
            width = "100%"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
            isSpacing = true
            expand(searchField)
        }

        val resultsGrid = Grid<TmdbSearchResult>().apply {
            width = "100%"
            height = "300px"

            addColumn(ComponentRenderer { r ->
                val url = r.posterPath?.let { "https://image.tmdb.org/t/p/w92$it" }
                if (url != null) {
                    Image(url, r.title ?: "").apply {
                        height = "60px"; width = "40px"
                        style.set("object-fit", "cover")
                    }
                } else Span("\u2014")
            }).setHeader("").setWidth("60px").setFlexGrow(0)

            addColumn({ it.title ?: "" }).setHeader("Title").setFlexGrow(1)
            addColumn({ it.releaseYear?.toString() ?: "" }).setHeader("Year").setWidth("70px").setFlexGrow(0)
            addColumn({ r ->
                r.overview?.let { if (it.length > 80) it.take(80) + "\u2026" else it } ?: ""
            }).setHeader("Overview").setFlexGrow(1)
        }
        resultsGrid.isVisible = false

        // Selection form (shown after picking a TMDB result)
        val selectedLabel = Span()
        val formatCombo = ComboBox<MediaFormat>("Format").apply {
            setItems(*MediaFormat.entries.toTypedArray())
            setItemLabelGenerator {
                when (it) {
                    MediaFormat.BLURAY -> "Blu-ray"
                    MediaFormat.UHD_BLURAY -> "UHD Blu-ray"
                    MediaFormat.HD_DVD -> "HD DVD"
                    else -> it.name
                }
            }
            value = MediaFormat.BLURAY
        }
        val seasonsField = TextField("Seasons").apply {
            placeholder = "e.g. 2 or 1, 2"
            width = "100%"
        }
        val seasonsRow = HorizontalLayout(seasonsField).apply {
            width = "100%"
            isVisible = false
        }
        val multiPackCheck = com.vaadin.flow.component.checkbox.Checkbox("Multi-pack (expand at /expand)")
        val addBtn = Button("Link").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        }
        val selectionForm = VerticalLayout(selectedLabel, formatCombo, seasonsRow, multiPackCheck, addBtn).apply {
            isPadding = false
            isSpacing = true
            isVisible = false
        }

        var selectedResult: TmdbSearchResult? = null

        resultsGrid.addItemClickListener { event ->
            selectedResult = event.item
            val r = event.item
            val yearStr = r.releaseYear?.let { " ($it)" } ?: ""
            selectedLabel.text = "${r.title}$yearStr"
            seasonsRow.isVisible = r.mediaType == MediaType.TV.name
            selectionForm.isVisible = true
        }

        searchBtn.addClickListener {
            val query = searchField.value?.trim() ?: ""
            if (query.isBlank()) return@addClickListener
            val results = if (mediaTypeCombo.value == "TV") {
                tmdbService.searchTvMultiple(query)
            } else {
                tmdbService.searchMovieMultiple(query)
            }
            resultsGrid.setItems(results)
            resultsGrid.isVisible = true
            selectionForm.isVisible = false
            selectedResult = null
        }

        addBtn.addClickListener {
            val result = selectedResult ?: return@addClickListener
            val tmdbId = result.tmdbId ?: return@addClickListener
            val format = formatCombo.value ?: return@addClickListener
            linkScanToTitle(scan, result, tmdbId, format, seasonsField.value?.trim()?.takeIf { it.isNotBlank() }, multiPackCheck.value)
            dlg.close()
        }

        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            if (photoRow != null) add(photoRow)
            add(searchRow, resultsGrid, selectionForm)
        }
        dlg.add(content)
        dlg.open()
    }

    private fun linkScanToTitle(
        scan: BarcodeScan,
        result: TmdbSearchResult,
        tmdbId: Int,
        format: MediaFormat,
        seasonsText: String?,
        isMultiPack: Boolean = false
    ) {
        val now = LocalDateTime.now()
        val seasonsValue = seasonsText?.let {
            parseSeasonsInput(it) ?: run {
                showError("Invalid seasons format. Use numbers like: 2 or 1, 2 or 1-3")
                return
            }
        }

        // Dedup Title — may already exist (e.g. linked to a transcode)
        val tmdbKey = result.tmdbKey() ?: TmdbId(tmdbId, MediaType.MOVIE)
        var title = Title.findAll().firstOrNull { it.tmdbKey() == tmdbKey }
        val isNewTitle = title == null
        if (title == null) {
            val enrichStatus = if (isMultiPack) EnrichmentStatus.SKIPPED.name
                else EnrichmentStatus.REASSIGNMENT_REQUESTED.name
            title = Title(
                name = result.title ?: "Unknown",
                media_type = tmdbKey.typeString,
                tmdb_id = tmdbKey.id,
                release_year = result.releaseYear,
                description = result.overview,
                poster_path = result.posterPath,
                enrichment_status = enrichStatus,
                created_at = now,
                updated_at = now
            )
            title.save()
        }

        // Create MediaItem with the scan's UPC
        val mediaItem = MediaItem(
            upc = scan.upc,
            media_format = format.name,
            entry_source = EntrySource.UPC_SCAN.name,
            product_name = result.title,
            title_count = 1,
            expansion_status = if (isMultiPack) ExpansionStatus.NEEDS_EXPANSION.name
                else ExpansionStatus.SINGLE.name,
            created_at = now,
            updated_at = now
        )
        mediaItem.save()

        // Link media item to title
        val join = MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!,
            disc_number = 1,
            seasons = seasonsValue
        )
        join.save()

        // Update barcode scan
        scan.lookup_status = LookupStatus.FOUND.name
        scan.media_item_id = mediaItem.id
        scan.notes = "Manually linked to ${result.title ?: "Unknown"}"
        scan.save()

        // Link orphaned ownership photos
        OwnershipPhotoService.resolveOrphans(scan.upc, mediaItem.id!!)

        // Update search index and refresh any matching legacy fulfilled wishes
        SearchIndexService.onTitleChanged(title.id!!)
        WishListService.syncPhysicalOwnership(title.id!!)
        WishListService.fulfillMediaWishes(tmdbKey)

        val yearStr = result.releaseYear?.let { " ($it)" } ?: ""
        showSuccess("Linked UPC ${scan.upc} to ${result.title}$yearStr")
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

        // Pending/failed BarcodeScan records — no MediaItem yet
        val stuckStatuses = setOf(LookupStatus.NOT_LOOKED_UP.name, LookupStatus.NOT_FOUND.name)
        val pendingScans = BarcodeScan.findAll()
            .filter { it.lookup_status in stuckStatuses }
            .sortedByDescending { it.scanned_at }

        for (scan in pendingScans) {
            // Skip if already represented by a MediaItem row
            if (rows.any { it.upc == scan.upc }) continue

            val photoCount = OwnershipPhotoService.findByUpc(scan.upc).size
            rows.add(AddItemRow(
                mediaItemId = null,
                barcodeScanId = scan.id,
                displayName = "UPC: ${scan.upc}",
                formatLabel = "",
                enrichmentStatus = scan.lookup_status,
                hasPurchaseInfo = false,
                photoCount = photoCount,
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
        val items = when (currentFilter) {
            ItemFilter.ALL -> cachedItems
            ItemFilter.NEEDS_ATTENTION -> cachedItems.filter { it.needsAttention }
            ItemFilter.UPC_NOT_FOUND -> cachedItems.filter {
                it.enrichmentStatus in setOf("NOT_LOOKED_UP", "NOT_FOUND")
            }
            ItemFilter.NEEDS_ENRICHMENT -> cachedItems.filter {
                it.enrichmentStatus in setOf("PENDING", "REASSIGNMENT_REQUESTED", "FAILED", "SKIPPED", "ABANDONED")
            }
            ItemFilter.NEEDS_PURCHASE -> cachedItems.filter { !it.hasPurchaseInfo }
            ItemFilter.NEEDS_PHOTOS -> cachedItems.filter { it.photoCount == 0 }
        }
        itemsGrid.setItems(items)
    }

    // ==================== Helpers ====================

    /**
     * Parses and normalizes season input. Returns the normalized string (e.g. "S1, S2"),
     * or null if the input can't be parsed into valid season numbers.
     */
    private fun parseSeasonsInput(input: String): String? {
        val trimmed = input.trim()
        // Normalize to "S1, S2" format, then validate via MissingSeasonService
        val rangeMatch = Regex("""^(\d+)\s*-\s*(\d+)$""").matchEntire(trimmed)
        val normalized = if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toInt()
            val end = rangeMatch.groupValues[2].toInt()
            (start..end).joinToString(", ") { "S$it" }
        } else {
            val parts = trimmed.split(",").map { it.trim().removePrefix("S").removePrefix("s") }
                .filter { it.isNotEmpty() }
            if (parts.all { it.toIntOrNull() != null }) {
                parts.joinToString(", ") { "S$it" }
            } else {
                trimmed.toIntOrNull()?.let { "S$it" } ?: trimmed
            }
        }
        // Validate: must be parseable by MissingSeasonService
        return if (MissingSeasonService.parseSeasonText(normalized) != null) normalized else null
    }

    private fun showSuccess(message: String) {
        Notification.show(message, 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun showError(message: String) {
        Notification.show(message, 4000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_ERROR)
    }

    private enum class ItemFilter(val label: String) {
        NEEDS_ATTENTION("Needs Attention"),
        UPC_NOT_FOUND("UPC Not Found"),
        NEEDS_ENRICHMENT("Needs Enrichment"),
        NEEDS_PURCHASE("Needs Purchase Info"),
        NEEDS_PHOTOS("Needs Photos"),
        ALL("All Recent")
    }
}
