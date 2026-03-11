package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.vaadin.flow.component.ClientCallable
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.component.UI
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.PriceLookup
import net.stewart.mediamanager.service.AmazonImportService
import net.stewart.mediamanager.service.KeepaHttpService
import net.stewart.mediamanager.service.KeepaProductResult
import net.stewart.mediamanager.service.MediaItemDeleteService
import net.stewart.mediamanager.service.OwnershipPhotoService
import net.stewart.mediamanager.service.AmazonSuggestion
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PriceSelectionService
import net.stewart.mediamanager.service.TitleCleanerService
import java.math.BigDecimal
import java.util.Base64
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Route(value = "valuation", layout = MainLayout::class)
@PageTitle("Valuation")
class PurchaseView : KComposite() {

    private lateinit var searchField: TextField
    private lateinit var priceFilter: ComboBox<String>
    private lateinit var countLabel: Span
    private lateinit var summaryContent: Div
    private lateinit var itemGrid: Grid<MediaItem>

    // Cached data — loaded once, invalidated on save
    private var allItems: List<MediaItem> = emptyList()
    private var titleMap: Map<Long, String> = emptyMap()
    private var seasonMap: Map<Long, String> = emptyMap()
    private var suggestions: Map<Long, AmazonSuggestion> = emptyMap()

    private val root = ui {
        verticalLayout {
            h2("Valuation")
            span("Track what you paid for each media item \u2014 useful for insurance inventory and collection valuation. Use Amazon Order Import to speed things up: imported orders can be linked to items here with one click, automatically filling in price, date, and retailer.") {
                style.set("color", "rgba(255,255,255,0.6)")
                style.set("margin-bottom", "var(--lumo-space-m)")
            }

            horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                isSpacing = true

                searchField = textField {
                    placeholder = "Search titles, UPC, place..."
                    isClearButtonVisible = true
                    width = "30em"
                    valueChangeMode = ValueChangeMode.LAZY
                    valueChangeTimeout = 300
                    addValueChangeListener { applyFilters() }
                }

                priceFilter = comboBox {
                    placeholder = "All items"
                    isClearButtonVisible = true
                    width = "14em"
                    setItems("With prices", "Without prices", "Needs replacement value")
                    addValueChangeListener { applyFilters() }
                }

                countLabel = span()
            }

            summaryContent = div {
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                style.set("border-radius", "var(--lumo-border-radius-m)")
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("display", "grid")
                style.set("grid-template-columns", "repeat(auto-fit, minmax(200px, 1fr))")
                style.set("gap", "var(--lumo-space-xs) var(--lumo-space-l)")
            }

            itemGrid = grid {
                width = "100%"
                isAllRowsVisible = true

                addColumn({ it.upc ?: "" }).setHeader("UPC").setWidth("140px").setFlexGrow(0)
                    .setSortable(false)

                addColumn(ComponentRenderer { item ->
                    val productName = item.product_name ?: titleMap[item.id] ?: ""
                    val childTitles = titleMap[item.id] ?: ""
                    val isMultiPack = item.title_count > 1
                    val season = seasonMap[item.id]

                    VerticalLayout().apply {
                        isPadding = false
                        isSpacing = false
                        style.set("padding", "var(--lumo-space-xs) 0")

                        val nameWithSeason = if (season != null) "$productName (S$season)" else productName
                        add(Span(nameWithSeason).apply {
                            style.set("font-weight", "bold")
                        })

                        // Show child titles when they differ from the product name (multi-packs)
                        if (isMultiPack && childTitles.isNotEmpty() && childTitles != productName) {
                            childTitles.split(", ").forEach { title ->
                                add(Span(title).apply {
                                    style.set("font-size", "var(--lumo-font-size-s)")
                                    style.set("color", "var(--lumo-secondary-text-color)")
                                })
                            }
                        }
                    }
                }).setHeader("Item").setFlexGrow(1).setSortable(false)

                addColumn(ComponentRenderer { item ->
                    val suggestion = suggestions[item.id]
                    if (item.purchase_place != null) {
                        Span(item.purchase_place ?: "")
                    } else if (suggestion != null) {
                        val full = suggestion.cleanedName
                        val truncated = if (full.length > 25) full.take(25) + "\u2026" else full
                        Span(truncated).apply {
                            element.setAttribute("title", full)
                            style.set("font-style", "italic")
                            style.set("color", "var(--lumo-primary-text-color)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        }
                    } else {
                        Span("")
                    }
                }).setHeader("Place").setWidth("160px").setFlexGrow(0).setSortable(false)

                addColumn(ComponentRenderer { item ->
                    val suggestion = suggestions[item.id]
                    if (item.purchase_date != null) {
                        Span(item.purchase_date!!.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    } else if (suggestion?.amazonOrder?.order_date != null) {
                        Span(suggestion.amazonOrder.order_date!!.toLocalDate()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).apply {
                            style.set("font-style", "italic")
                            style.set("color", "var(--lumo-primary-text-color)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        }
                    } else {
                        Span("")
                    }
                }).setHeader("Date").setWidth("110px").setFlexGrow(0).setSortable(false)

                addColumn(ComponentRenderer { item ->
                    val suggestion = suggestions[item.id]
                    VerticalLayout().apply {
                        isPadding = false
                        isSpacing = false
                        style.set("padding", "var(--lumo-space-xs) 0")

                        if (item.purchase_price != null) {
                            add(Span("$${item.purchase_price!!.setScale(2, RoundingMode.HALF_UP)}"))
                            if (item.title_count > 1) {
                                add(Span("(bundle)").apply {
                                    style.set("font-size", "var(--lumo-font-size-xs)")
                                    style.set("color", "var(--lumo-secondary-text-color)")
                                })
                            }
                        } else if (suggestion != null) {
                            val price = suggestion.amazonOrder.unit_price
                            if (price != null) {
                                add(Span("$${price.setScale(2, RoundingMode.HALF_UP)}").apply {
                                    style.set("font-style", "italic")
                                    style.set("color", "var(--lumo-primary-text-color)")
                                    style.set("font-size", "var(--lumo-font-size-s)")
                                })
                            }
                            add(Button("Link").apply {
                                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                                addClickListener {
                                    AmazonImportService.linkToMediaItem(suggestion.amazonOrder.id!!, item.id!!)
                                    refreshGrid()
                                    Notification.show("Linked to Amazon order",
                                        2000, Notification.Position.BOTTOM_START)
                                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                                }
                            })
                        }
                    }
                }).setHeader("Price").setWidth("140px").setFlexGrow(0).setSortable(false)

                addColumn(ComponentRenderer { item ->
                    Button("Edit").apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                        addClickListener { openEditDialog(item) }
                    }
                }).setHeader("").setWidth("60px").setFlexGrow(0).setSortable(false)
            }

            span { style.set("min-height", "6em"); style.set("display", "block") }
        }
    }

    init {
        loadData()
        applyFilters()
    }

    /** Load all data from DB and compute suggestions. Called once on init and after saves. */
    private fun loadData() {
        allItems = MediaItem.findAll()
        titleMap = loadTitleMap()
        seasonMap = loadSeasonMap()

        // Compute Amazon order suggestions for unpriced items
        val userId = AuthService.getCurrentUser()?.id
        val unpricedItems = allItems.filter { it.purchase_price == null }
        suggestions = if (userId != null && unpricedItems.isNotEmpty()) {
            AmazonImportService.findSuggestionsForMediaItems(userId, unpricedItems, titleMap)
        } else {
            emptyMap()
        }

        // Inventory summary (always computed from ALL items, not filtered)
        updateSummary()
    }

    private fun updateSummary() {
        summaryContent.removeAll()

        val totalItems = allItems.size
        val pricedItems = allItems.filter { it.purchase_price != null }
        val totalPurchase = pricedItems.fold(BigDecimal.ZERO) { acc, it ->
            acc + it.purchase_price!!.setScale(2, RoundingMode.HALF_UP)
        }
        val itemsWithReplacement = allItems.count { it.replacement_value != null }
        val replacementTotal = allItems.mapNotNull { it.replacement_value }.fold(BigDecimal.ZERO, BigDecimal::add)

        // Format breakdown
        val formatCounts = allItems.groupBy { it.media_format }
        val formatParts = listOf("DVD", "BLURAY", "UHD_BLURAY", "HD_DVD").mapNotNull { fmt ->
            val count = formatCounts[fmt]?.size ?: 0
            if (count > 0) {
                val label = when (fmt) {
                    "BLURAY" -> "Blu-ray"
                    "UHD_BLURAY" -> "UHD"
                    "HD_DVD" -> "HD DVD"
                    else -> fmt
                }
                "$count $label"
            } else null
        }

        fun addStat(label: String, value: String) {
            summaryContent.add(Div().apply {
                add(Span(value).apply {
                    style.set("font-weight", "600")
                    style.set("font-size", "var(--lumo-font-size-m)")
                    style.set("display", "block")
                })
                add(Span(label).apply {
                    style.set("color", "var(--lumo-secondary-text-color)")
                })
            })
        }

        addStat("Total Items", "$totalItems items (${formatParts.joinToString(", ")})")

        val purchasePct = if (totalItems > 0) (pricedItems.size * 100) / totalItems else 0
        addStat("Purchase Data", "\$${totalPurchase.setScale(2)} \u00b7 ${pricedItems.size} of $totalItems ($purchasePct%)")

        if (itemsWithReplacement > 0) {
            val replPct = if (totalItems > 0) (itemsWithReplacement * 100) / totalItems else 0
            addStat("Replacement Value", "\$${replacementTotal.setScale(2)} \u00b7 $itemsWithReplacement of $totalItems ($replPct%)")

            // Pricing source breakdown
            val itemIdsWithLookup = PriceLookup.findAll().map { it.media_item_id }.toSet()
            val autoPriced = allItems.count { it.replacement_value != null && it.id in itemIdsWithLookup }
            val manualPriced = itemsWithReplacement - autoPriced
            val unpriced = totalItems - itemsWithReplacement
            val parts = mutableListOf<String>()
            if (autoPriced > 0) parts.add("$autoPriced auto")
            if (manualPriced > 0) parts.add("$manualPriced manual")
            if (unpriced > 0) parts.add("$unpriced unpriced")
            addStat("Pricing Breakdown", parts.joinToString(" \u00b7 "))
        } else {
            addStat("Replacement Value", "No items priced yet")
        }

        // Evidence coverage
        val photoCounts = OwnershipPhotoService.countByMediaItem()
        val itemIds = allItems.mapNotNull { it.id }.toSet()
        val itemsWithPhotos = photoCounts.keys.intersect(itemIds).size
        val totalPhotos = photoCounts.values.sum()
        if (totalPhotos > 0) {
            val evidencePct = if (totalItems > 0) (itemsWithPhotos * 100) / totalItems else 0
            addStat("Evidence Photos", "$totalPhotos photos \u00b7 $itemsWithPhotos of $totalItems items ($evidencePct%)")
        }
    }

    /** Apply search/filter to cached data. Fast — no DB queries or fuzzy matching. */
    private fun applyFilters() {
        val searchTerm = searchField.value?.trim()?.lowercase() ?: ""
        val priceSelection = priceFilter.value

        var filtered = allItems.asSequence()

        if (searchTerm.isNotEmpty()) {
            filtered = filtered.filter { item ->
                val titles = titleMap[item.id]?.lowercase() ?: ""
                val product = item.product_name?.lowercase() ?: ""
                val upc = item.upc?.lowercase() ?: ""
                val place = item.purchase_place?.lowercase() ?: ""
                titles.contains(searchTerm) || product.contains(searchTerm) ||
                        upc.contains(searchTerm) || place.contains(searchTerm)
            }
        }

        when (priceSelection) {
            "With prices" -> filtered = filtered.filter { it.purchase_price != null }
            "Without prices" -> filtered = filtered.filter { it.purchase_price == null }
            "Needs replacement value" -> filtered = filtered.filter { it.replacement_value == null }
        }

        val sorted = filtered.sortedBy { titleMap[it.id]?.lowercase() ?: "" }.toList()
        itemGrid.setItems(sorted)

        val hasFilter = searchTerm.isNotEmpty() || priceSelection != null
        countLabel.text = if (hasFilter) {
            "Showing ${sorted.size} of ${allItems.size} media items"
        } else {
            "${allItems.size} media items"
        }
    }

    /** Reload data from DB and re-apply filters. Called after saves/edits. */
    private fun refreshGrid() {
        loadData()
        applyFilters()
    }

    private fun loadTitleMap(): Map<Long, String> {
        return JdbiOrm.jdbi().withHandle<Map<Long, String>, Exception> { handle ->
            handle.createQuery(
                """SELECT mit.media_item_id, GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ', ')
                   FROM media_item_title mit
                   JOIN title t ON t.id = mit.title_id
                   GROUP BY mit.media_item_id"""
            ).map { rs, _ ->
                rs.getLong("media_item_id") to (rs.getString(2) ?: "")
            }.list().toMap()
        }
    }

    private fun loadSeasonMap(): Map<Long, String> {
        return JdbiOrm.jdbi().withHandle<Map<Long, String>, Exception> { handle ->
            handle.createQuery(
                """SELECT media_item_id, GROUP_CONCAT(seasons ORDER BY seasons SEPARATOR ', ')
                   FROM media_item_title
                   WHERE seasons IS NOT NULL
                   GROUP BY media_item_id"""
            ).map { rs, _ ->
                rs.getLong("media_item_id") to (rs.getString(2) ?: "")
            }.list().toMap()
        }
    }

    private fun openEditDialog(item: MediaItem) {
        PurchaseEditDialog(item, titleMap) { refreshGrid() }.open()
    }
}

private class PurchaseEditDialog(
    private val mediaItem: MediaItem,
    private val titleMap: Map<Long, String>,
    private val onSave: () -> Unit
) : Dialog() {

    private var selectedAmazonOrderId: Long? = null
    private var placeField: TextField
    private var dateField: DatePicker
    private var priceField: NumberField
    private var replacementField: NumberField
    private var photoStrip: HorizontalLayout
    private var photoLabel: Span

    init {
        headerTitle = "Edit Purchase"
        width = "600px"

        val productName = mediaItem.product_name ?: titleMap[mediaItem.id] ?: "—"
        val linkedTitles = titleMap[mediaItem.id] ?: "—"

        val upcLabel = Span("UPC: ${mediaItem.upc ?: "—"}").apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
        }
        val productLabel = Span("Product: $productName").apply {
            style.set("font-weight", "bold")
        }
        val titlesLabel = Span("Titles: $linkedTitles").apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
        }

        placeField = TextField("Purchase Place").apply {
            value = mediaItem.purchase_place ?: "Amazon"
            width = "100%"
        }
        priceField = NumberField("Purchase Price").apply {
            prefixComponent = Span("$")
            value = mediaItem.purchase_price?.toDouble()
            width = "100%"
        }
        replacementField = NumberField("Replacement Value").apply {
            prefixComponent = Span("$")
            value = mediaItem.replacement_value?.toDouble()
            width = "100%"
            val updatedAt = mediaItem.replacement_value_updated_at
            if (updatedAt != null) {
                helperText = "Last updated: ${updatedAt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))}"
            }
        }
        dateField = DatePicker("Purchase Date").apply {
            value = mediaItem.purchase_date
            width = "100%"
            element.executeJs("""
                this.addEventListener('keydown', function(e) {
                    if (e.key === 'Tab' && !e.shiftKey) {
                        e.preventDefault();
                        e.stopPropagation();
                        $0.focus();
                    }
                });
            """, priceField.element)
        }

        // ASIN section
        val currentAsin = resolveAsin(mediaItem)
        val asinLayout = HorizontalLayout().apply {
            width = "100%"
            isPadding = false
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
        }
        val asinDisplay = Span().apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
        }
        val asinField = TextField().apply {
            width = "100%"
            placeholder = "Paste Amazon URL or ASIN"
            isVisible = false
            style.set("font-size", "var(--lumo-font-size-s)")
        }

        fun updateAsinDisplay() {
            val resolved = resolveAsin(MediaItem.findById(mediaItem.id!!) ?: mediaItem)
            if (resolved != null) {
                asinDisplay.text = "ASIN: ${resolved.first} (${resolved.second})"
                asinDisplay.element.executeJs(
                    "this.innerHTML='ASIN: <a href=\"https://www.amazon.com/dp/' + $0 + '\" target=\"_blank\" style=\"color:var(--lumo-primary-text-color)\">' + $0 + '</a> (' + $1 + ')'",
                    resolved.first, resolved.second
                )
            } else {
                asinDisplay.text = "ASIN: none"
            }
        }
        updateAsinDisplay()

        val setAsinBtn = Button("Set").apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
            addClickListener {
                asinField.isVisible = !asinField.isVisible
                if (asinField.isVisible) asinField.focus()
            }
        }
        val clearAsinBtn = Button("Clear").apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
            isVisible = mediaItem.override_asin != null
            addClickListener {
                val fresh = MediaItem.findById(mediaItem.id!!) ?: return@addClickListener
                fresh.override_asin = null
                fresh.save()
                mediaItem.override_asin = null
                isVisible = false
                asinField.isVisible = false
                updateAsinDisplay()
                Notification.show("ASIN override cleared", 2000, Notification.Position.BOTTOM_START)
            }
        }
        asinField.addValueChangeListener { event ->
            val input = event.value?.trim() ?: ""
            if (input.isEmpty()) return@addValueChangeListener
            val extracted = extractAsin(input)
            if (extracted != null) {
                val fresh = MediaItem.findById(mediaItem.id!!) ?: return@addValueChangeListener
                fresh.override_asin = extracted
                fresh.save()
                mediaItem.override_asin = extracted
                asinField.value = ""
                asinField.isVisible = false
                clearAsinBtn.isVisible = true
                updateAsinDisplay()
                Notification.show("ASIN set to $extracted", 2000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            } else {
                Notification.show("Could not extract ASIN from input", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR)
            }
        }
        asinLayout.add(asinDisplay, setAsinBtn, clearAsinBtn)

        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(upcLabel, productLabel, titlesLabel, placeField, dateField, priceField, replacementField, asinLayout, asinField)
        }

        // Find on Keepa section
        val keepaApiKey = AppConfig.findAll().firstOrNull { it.config_key == "keepa_api_key" }?.config_val?.trim()
        val keepaSeparator = Span().apply {
            width = "100%"
            style.set("border-top", "1px solid var(--lumo-contrast-20pct)")
            style.set("margin-top", "var(--lumo-space-xs)")
        }
        content.add(keepaSeparator)

        val keepaCandidateGrid = Grid<KeepaProductResult>().apply {
            width = "100%"
            height = "200px"
            isVisible = false

            addColumn(ComponentRenderer { r ->
                Span(r.title ?: "").apply {
                    element.setAttribute("title", r.title ?: "")
                    style.set("overflow", "hidden")
                    style.set("text-overflow", "ellipsis")
                    style.set("white-space", "nowrap")
                    style.set("display", "block")
                }
            }).setHeader("Amazon Title").setFlexGrow(1).setSortable(false)

            addColumn({ r ->
                val price = PriceSelectionService.selectPrice(r)
                if (price != null) "\$$price" else "—"
            }).setHeader("New Price").setWidth("90px").setFlexGrow(0).setSortable(false)

            addColumn({ it.asin ?: "" }).setHeader("ASIN").setWidth("110px").setFlexGrow(0).setSortable(false)

            addColumn(ComponentRenderer { result ->
                Button("Use").apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                    addClickListener {
                        val asin = result.asin ?: return@addClickListener
                        val fresh = MediaItem.findById(mediaItem.id!!) ?: return@addClickListener
                        fresh.override_asin = asin
                        // Optionally apply the best price as replacement_value
                        val price = PriceSelectionService.selectPrice(result)
                        if (price != null && fresh.replacement_value == null) {
                            fresh.replacement_value = price
                            fresh.replacement_value_updated_at = LocalDateTime.now()
                            replacementField.value = price.toDouble()
                        }
                        fresh.save()
                        mediaItem.override_asin = asin
                        clearAsinBtn.isVisible = true
                        asinField.isVisible = false
                        updateAsinDisplay()
                        Notification.show("ASIN set to $asin", 2000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                    }
                }
            }).setHeader("").setWidth("65px").setFlexGrow(0).setSortable(false)
        }

        val keepaStatusLabel = Span().apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
            isVisible = false
        }

        val findOnKeepaBtn = Button("Find on Keepa", VaadinIcon.SEARCH.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
            if (keepaApiKey.isNullOrBlank()) {
                isEnabled = false
                element.setAttribute("title", "Keepa API key not configured — set it in Transcodes > Settings")
            } else {
                addClickListener {
                    isEnabled = false
                    keepaStatusLabel.text = "Searching Keepa..."
                    keepaStatusLabel.isVisible = true
                    keepaCandidateGrid.isVisible = false

                    val formatTerm = formatToSearchTerm(mediaItem.media_format)
                    val ui = UI.getCurrent()
                    val log = org.slf4j.LoggerFactory.getLogger("KeepaSearch")
                    Thread {
                        try {
                            log.info("Keepa search: '{}' format='{}'", productName, formatTerm)
                            val candidates =
                                KeepaHttpService(keepaApiKey).searchCandidates(productName, formatTerm.ifEmpty { null })
                            log.info("Keepa search returned {} candidates", candidates.size)
                            ui.access {
                                isEnabled = true
                                if (candidates.isEmpty()) {
                                    keepaStatusLabel.text = "No results found on Keepa."
                                    keepaStatusLabel.isVisible = true
                                    keepaCandidateGrid.isVisible = false
                                } else {
                                    keepaStatusLabel.isVisible = false
                                    keepaCandidateGrid.setItems(candidates)
                                    keepaCandidateGrid.isVisible = true
                                }
                                ui.push()
                            }
                        } catch (e: Exception) {
                            log.error("Keepa search failed", e)
                            ui.access {
                                isEnabled = true
                                keepaStatusLabel.text = "Search failed: ${e.message}"
                                keepaStatusLabel.isVisible = true
                                keepaCandidateGrid.isVisible = false
                                ui.push()
                            }
                        }
                    }.also { it.isDaemon = true }.start()
                }
            }
        }

        content.add(findOnKeepaBtn, keepaStatusLabel, keepaCandidateGrid)

        // Ownership photos section
        val photoSeparator = Span().apply {
            width = "100%"
            style.set("border-top", "1px solid var(--lumo-contrast-20pct)")
            style.set("margin-top", "var(--lumo-space-xs)")
        }
        content.add(photoSeparator)

        photoLabel = Span().apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
            style.set("font-weight", "500")
        }
        content.add(photoLabel)

        photoStrip = HorizontalLayout().apply {
            isPadding = false
            isSpacing = true
            style.set("flex-wrap", "wrap")
            style.set("gap", "var(--lumo-space-xs)")
        }
        content.add(photoStrip)

        // Add Photo button with native camera capture
        val addPhotoBtn = Button("Add Photo", VaadinIcon.CAMERA.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
        }
        val captureContainer = Div().apply {
            add(addPhotoBtn)
        }

        // Wire the button to a native file input with capture=environment
        element.executeJs(
            "var dlgEl=\$0;" +
            "var container=\$1;" +
            "var input=document.createElement('input');" +
            "input.type='file';" +
            "input.accept='image/*';" +
            "input.capture='environment';" +
            "input.style.display='none';" +
            "container.appendChild(input);" +
            "container.firstElementChild.addEventListener('click',function(e){" +
            "e.preventDefault();e.stopPropagation();input.click();});" +
            "input.addEventListener('change',function(){" +
            "if(!input.files||!input.files[0])return;" +
            "var file=input.files[0];" +
            "var reader=new FileReader();" +
            "reader.onload=function(){" +
            "var base64=reader.result.split(',')[1];" +
            "var mimeType=file.type||'image/jpeg';" +
            "dlgEl.\$server.onPhotoCapture(base64,mimeType);" +
            "};" +
            "reader.readAsDataURL(file);" +
            "input.value='';});",
            this.element, captureContainer.element
        )
        content.add(captureContainer)

        refreshPhotoStrip()

        // Amazon order search section
        val userId = AuthService.getCurrentUser()?.id
        if (userId != null) {
            val separator = Span().apply {
                width = "100%"
                style.set("border-top", "1px solid var(--lumo-contrast-20pct)")
                style.set("margin-top", "var(--lumo-space-s)")
            }
            content.add(separator)

            val amazonSearchField = TextField("Search Amazon Orders").apply {
                width = "100%"
                isClearButtonVisible = true
                valueChangeMode = ValueChangeMode.LAZY
                valueChangeTimeout = 300
                // Pre-populate with cleaned product name
                val cleanedName = TitleCleanerService.clean(productName).displayName
                value = cleanedName
            }

            val amazonGrid = Grid<AmazonOrder>().apply {
                width = "100%"
                height = "200px"

                addColumn({ TitleCleanerService.clean(it.product_name).displayName })
                    .setHeader("Product").setFlexGrow(1).setSortable(false)

                addColumn({ order ->
                    order.order_date?.toLocalDate()?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) ?: ""
                }).setHeader("Date").setWidth("90px").setFlexGrow(0).setSortable(false)

                addColumn({ order ->
                    order.unit_price?.let { "$${it.setScale(2, RoundingMode.HALF_UP)}" } ?: ""
                }).setHeader("Price").setWidth("80px").setFlexGrow(0).setSortable(false)

                addColumn(ComponentRenderer { order ->
                    Button("Use").apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                        addClickListener { applyAmazonOrder(order) }
                    }
                }).setHeader("").setWidth("70px").setFlexGrow(0).setSortable(false)
            }

            fun refreshAmazonGrid() {
                val query = amazonSearchField.value?.trim() ?: ""
                val results = AmazonImportService.searchOrders(userId, query, unlinkedOnly = true, limit = 50)
                amazonGrid.setItems(results)
            }

            amazonSearchField.addValueChangeListener { refreshAmazonGrid() }
            content.add(amazonSearchField, amazonGrid)

            // Initial search
            refreshAmazonGrid()
        }

        add(content)

        val bulkBtn = Button("Add Other Items to This Purchase").apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
            addClickListener {
                val place = placeField.value?.trim()
                val date = dateField.value
                if (place.isNullOrEmpty() || date == null) {
                    Notification.show("Set place and date before adding other items",
                        3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                } else {
                    // Save current item inline (without closing this dialog)
                    val fresh = MediaItem.findById(mediaItem.id!!)
                    if (fresh != null) {
                        fresh.purchase_place = place
                        fresh.purchase_date = date
                        fresh.purchase_price = priceField.value?.let {
                            BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP)
                        }
                        fresh.updated_at = LocalDateTime.now()
                        fresh.save()
                    }
                    BulkPurchaseDialog(place, date, titleMap) {
                        close()
                        onSave()
                    }.open()
                }
            }
        }

        val deleteBtn = Button("Delete Item", VaadinIcon.TRASH.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR)
            addClickListener { openDeleteConfirmation() }
        }

        val cancelBtn = Button("Cancel") { close() }
        val saveBtn = Button("Save").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener {
                save(placeField.value, dateField.value,
                    priceField.value?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) })
            }
        }

        val footer = HorizontalLayout().apply {
            width = "100%"
            isSpacing = true
            add(deleteBtn, bulkBtn)
            val spacer = Span()
            expand(spacer)
            add(spacer, cancelBtn, saveBtn)
        }
        footer.element.setAttribute("slot", "footer")
        add(footer)
    }

    private fun refreshPhotoStrip() {
        photoStrip.removeAll()
        val photos = OwnershipPhotoService.findAllForItem(mediaItem.id!!, mediaItem.upc)
        photoLabel.text = "Evidence Photos (${photos.size})"

        for (photo in photos) {
            val container = Div().apply {
                style.set("position", "relative")
                style.set("display", "inline-block")

                val img = Image("/ownership-photos/${photo.id}", "Evidence").apply {
                    height = "70px"
                    style.set("border-radius", "4px")
                    style.set("object-fit", "cover")
                    style.set("max-width", "105px")
                    style.set("cursor", "pointer")
                }
                img.element.addEventListener("click") {
                    img.element.executeJs(
                        "window.open('/ownership-photos/' + $0 + '?download=1', '_blank')",
                        photo.id!!
                    )
                }
                add(img)

                // Delete overlay
                val deleteBtn = Div().apply {
                    style.set("position", "absolute")
                    style.set("top", "2px")
                    style.set("right", "2px")
                    style.set("width", "20px")
                    style.set("height", "20px")
                    style.set("border-radius", "50%")
                    style.set("background", "rgba(0,0,0,0.6)")
                    style.set("color", "rgba(255,255,255,0.7)")
                    style.set("display", "flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    style.set("cursor", "pointer")
                    style.set("font-size", "14px")
                    style.set("line-height", "1")
                    element.setProperty("innerHTML", "&#10005;")
                    element.setAttribute("title", "Remove photo")
                }
                deleteBtn.addClickListener {
                    OwnershipPhotoService.delete(photo.id!!)
                    Notification.show("Photo removed", 2000, Notification.Position.BOTTOM_START)
                    refreshPhotoStrip()
                }
                add(deleteBtn)

                // Download overlay
                val dlBtn = Div().apply {
                    style.set("position", "absolute")
                    style.set("bottom", "2px")
                    style.set("right", "2px")
                    style.set("width", "20px")
                    style.set("height", "20px")
                    style.set("border-radius", "50%")
                    style.set("background", "rgba(0,0,0,0.6)")
                    style.set("color", "rgba(255,255,255,0.7)")
                    style.set("display", "flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    style.set("cursor", "pointer")
                    style.set("font-size", "12px")
                    element.setProperty("innerHTML", "&#8628;")
                    element.setAttribute("title", "Download photo")
                }
                dlBtn.addClickListener {
                    dlBtn.element.executeJs(
                        "window.open('/ownership-photos/' + $0 + '?download=1', '_blank')",
                        photo.id!!
                    )
                }
                add(dlBtn)
            }
            photoStrip.add(container)
        }

        if (photos.isEmpty()) {
            photoStrip.add(Span("No evidence photos").apply {
                style.set("color", "var(--lumo-secondary-text-color)")
                style.set("font-size", "var(--lumo-font-size-s)")
            })
        }
    }

    @ClientCallable
    fun onPhotoCapture(base64Data: String, mimeType: String) {
        val bytes = Base64.getDecoder().decode(base64Data)
        OwnershipPhotoService.store(bytes, mimeType, mediaItem.id!!)
        Notification.show("Photo saved", 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
        refreshPhotoStrip()
    }

    private fun openDeleteConfirmation() {
        val dialog = Dialog().apply {
            headerTitle = "Delete Item"
            width = "400px"
        }

        val productName = mediaItem.product_name ?: titleMap[mediaItem.id] ?: "this item"
        dialog.add(Span("Permanently delete \"$productName\"? This removes the media item, linked titles (if not shared), ownership photos, and unlinks any Amazon orders.").apply {
            style.set("padding", "var(--lumo-space-m)")
        })

        val dlgFooter = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            isSpacing = true
        }
        val dlgCancel = Button("Cancel") { dialog.close() }
        val dlgDelete = Button("Delete").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR)
            addClickListener {
                MediaItemDeleteService.delete(mediaItem.id!!)
                dialog.close()
                close()
                onSave()
                Notification.show("Item deleted", 2000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }
        }
        dlgFooter.add(dlgCancel, dlgDelete)
        dlgFooter.element.setAttribute("slot", "footer")
        dialog.add(dlgFooter)
        dialog.open()
    }

    private fun applyAmazonOrder(order: AmazonOrder) {
        placeField.value = "Amazon"
        dateField.value = order.order_date?.toLocalDate()
        priceField.value = order.unit_price?.toDouble()
        selectedAmazonOrderId = order.id
        Notification.show("Applied Amazon order — click Save to confirm",
            2000, Notification.Position.BOTTOM_START)
    }

    /**
     * Resolve the best known ASIN for a media item.
     * Returns (asin, source) or null.
     */
    private fun resolveAsin(item: MediaItem): Pair<String, String>? {
        // User override takes priority
        if (!item.override_asin.isNullOrBlank()) {
            return item.override_asin!! to "override"
        }
        // Linked Amazon order
        if (!item.amazon_order_id.isNullOrBlank()) {
            val asin = JdbiOrm.jdbi().withHandle<String?, Exception> { handle ->
                handle.createQuery(
                    "SELECT asin FROM amazon_order WHERE linked_media_item_id = :itemId AND asin != '' LIMIT 1"
                ).bind("itemId", item.id)
                    .mapTo(String::class.java)
                    .findFirst().orElse(null)
            }
            if (asin != null) return asin to "Amazon order"
        }
        // Most recent price lookup with a keepa_asin
        val keepaAsin = JdbiOrm.jdbi().withHandle<String?, Exception> { handle ->
            handle.createQuery(
                "SELECT keepa_asin FROM price_lookup WHERE media_item_id = :itemId AND keepa_asin IS NOT NULL ORDER BY looked_up_at DESC LIMIT 1"
            ).bind("itemId", item.id)
                .mapTo(String::class.java)
                .findFirst().orElse(null)
        }
        if (keepaAsin != null) return keepaAsin to "auto-discovered"

        return null
    }

    /**
     * Extract an ASIN from user input. Accepts:
     * - Raw ASIN: "B00ABC1234"
     * - Amazon URL: "https://www.amazon.com/dp/B00ABC1234/..."
     * - Amazon URL: "https://www.amazon.com/gp/product/B00ABC1234/..."
     */
    private fun extractAsin(input: String): String? {
        val trimmed = input.trim()
        // Raw ASIN (10 alphanumeric, starts with B or 0-9)
        if (trimmed.matches(Regex("^[A-Z0-9]{10}$"))) return trimmed
        // URL with /dp/ or /product/
        val urlMatch = Regex("(?:dp|product)/([A-Z0-9]{10})").find(trimmed.uppercase())
        return urlMatch?.groupValues?.get(1)
    }

    private fun save(place: String?, date: LocalDate?, price: BigDecimal?) {
        try {
            val fresh = MediaItem.findById(mediaItem.id!!) ?: run {
                Notification.show("Item no longer exists", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR)
                close()
                return
            }

            val placeValue = place?.trim()?.ifEmpty { null }
            fresh.purchase_place = placeValue
            fresh.purchase_date = date
            fresh.purchase_price = price

            val newReplacementValue = replacementField.value?.let {
                BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP)
            }
            if (newReplacementValue != fresh.replacement_value) {
                fresh.replacement_value = newReplacementValue
                fresh.replacement_value_updated_at = if (newReplacementValue != null) LocalDateTime.now() else null
            }

            fresh.updated_at = LocalDateTime.now()

            // Link Amazon order if one was selected via "Use"
            val amazonId = selectedAmazonOrderId
            if (amazonId != null) {
                val order = AmazonOrder.findById(amazonId)
                if (order != null) {
                    order.linked_media_item_id = mediaItem.id
                    order.linked_at = LocalDateTime.now()
                    order.save()
                    fresh.amazon_order_id = order.order_id
                }
            }

            fresh.save()

            close()
            onSave()
            Notification.show("Purchase updated", 2000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
        } catch (e: Exception) {
            Notification.show("Save failed: ${e.message}", 4000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }

}

private fun formatToSearchTerm(format: String): String = when (format) {
    MediaFormat.DVD.name -> "DVD"
    MediaFormat.BLURAY.name -> "Blu-ray"
    MediaFormat.UHD_BLURAY.name -> "4K UHD Blu-ray"
    MediaFormat.HD_DVD.name -> "HD DVD"
    else -> ""
}

private class BulkPurchaseDialog(
    private val place: String,
    private val date: LocalDate,
    private val titleMap: Map<Long, String>,
    private val onSave: () -> Unit
) : Dialog() {

    private val capturedPrices = mutableMapOf<Long, Double>()
    private val resultsGrid: Grid<MediaItem>
    private val searchField: TextField

    init {
        headerTitle = "Add Items to $place (${date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))})"
        width = "700px"
        isResizable = true

        searchField = TextField().apply {
            placeholder = "Search unpriced items..."
            isClearButtonVisible = true
            width = "100%"
            valueChangeMode = ValueChangeMode.LAZY
            valueChangeTimeout = 300
            addValueChangeListener { refreshResults() }
        }

        resultsGrid = Grid<MediaItem>().apply {
            width = "100%"
            height = "350px"

            addColumn(ComponentRenderer { item ->
                val productName = item.product_name ?: titleMap[item.id] ?: "—"
                val childTitles = titleMap[item.id] ?: ""
                val isMultiPack = item.title_count > 1

                VerticalLayout().apply {
                    isPadding = false
                    isSpacing = false
                    style.set("padding", "var(--lumo-space-xs) 0")

                    add(Span(productName).apply {
                        style.set("font-weight", "bold")
                    })

                    if (isMultiPack && childTitles.isNotEmpty() && childTitles != productName) {
                        childTitles.split(", ").forEach { title ->
                            add(Span(title).apply {
                                style.set("font-size", "var(--lumo-font-size-s)")
                                style.set("color", "var(--lumo-secondary-text-color)")
                            })
                        }
                    }
                }
            }).setHeader("Item").setFlexGrow(1).setSortable(false)

            addColumn({ it.upc ?: "" }).setHeader("UPC").setWidth("140px").setFlexGrow(0)
                .setSortable(false)

            addColumn(ComponentRenderer { item ->
                NumberField().apply {
                    prefixComponent = Span("$")
                    width = "120px"
                    placeholder = "0.00"
                    capturedPrices[item.id!!]?.let { value = it }
                    addValueChangeListener { e ->
                        if (e.value != null && e.value > 0.0) {
                            capturedPrices[item.id!!] = e.value
                        } else {
                            capturedPrices.remove(item.id!!)
                        }
                    }
                }
            }).setHeader("Price").setWidth("160px").setFlexGrow(0).setSortable(false)
        }

        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(searchField, resultsGrid)
        }
        add(content)

        val cancelBtn = Button("Cancel") { close() }
        val saveAllBtn = Button("Save All").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener { saveAll() }
        }

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            isSpacing = true
            add(cancelBtn, saveAllBtn)
        }
        footer.element.setAttribute("slot", "footer")
        add(footer)

        refreshResults()
    }

    private fun refreshResults() {
        val searchTerm = searchField.value?.trim()?.lowercase() ?: ""
        val unpriced = MediaItem.findAll().filter { it.purchase_price == null }

        val filtered = if (searchTerm.isEmpty()) {
            unpriced
        } else {
            unpriced.filter { item ->
                val titles = titleMap[item.id]?.lowercase() ?: ""
                val upc = item.upc?.lowercase() ?: ""
                titles.contains(searchTerm) || upc.contains(searchTerm)
            }
        }

        val sorted = filtered.sortedBy { titleMap[it.id]?.lowercase() ?: "" }
        resultsGrid.setItems(sorted)
    }

    private fun saveAll() {
        try {
            var count = 0
            for ((itemId, priceValue) in capturedPrices) {
                if (priceValue <= 0.0) continue
                val item = MediaItem.findById(itemId) ?: continue
                item.purchase_place = place
                item.purchase_date = date
                item.purchase_price = BigDecimal(priceValue).setScale(2, RoundingMode.HALF_UP)
                item.updated_at = LocalDateTime.now()
                item.save()
                count++
            }

            if (count == 0) {
                Notification.show("No prices entered", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_CONTRAST)
                return
            }

            close()
            onSave()
            Notification.show("$count item${if (count != 1) "s" else ""} updated",
                2000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
        } catch (e: Exception) {
            Notification.show("Save failed: ${e.message}", 4000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }
}
