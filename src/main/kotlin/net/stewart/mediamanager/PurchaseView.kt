package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
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
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.service.AmazonImportService
import net.stewart.mediamanager.service.AmazonSuggestion
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.TitleCleanerService
import java.math.BigDecimal
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
    private lateinit var inventoryLabel: Span
    private lateinit var itemGrid: Grid<MediaItem>

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
                    addValueChangeListener { refreshGrid() }
                }

                priceFilter = comboBox {
                    placeholder = "All items"
                    isClearButtonVisible = true
                    width = "14em"
                    setItems("With prices", "Without prices")
                    addValueChangeListener { refreshGrid() }
                }

                countLabel = span()
            }

            inventoryLabel = span {
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                style.set("border-radius", "var(--lumo-border-radius-m)")
                style.set("font-size", "var(--lumo-font-size-s)")
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
        refreshGrid()
    }

    private fun refreshGrid() {
        val allItems = MediaItem.findAll()
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
        val pricedItems = allItems.filter { it.purchase_price != null }
        val totalValue = pricedItems.fold(BigDecimal.ZERO) { acc, item ->
            acc + item.purchase_price!!.setScale(2, RoundingMode.HALF_UP)
        }
        inventoryLabel.text = "Inventory: \$${totalValue.setScale(2, RoundingMode.HALF_UP)}" +
                " \u00b7 ${pricedItems.size} of ${allItems.size} items priced"

        // Apply filters
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
    private lateinit var placeField: TextField
    private lateinit var dateField: DatePicker
    private lateinit var priceField: NumberField

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

        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(upcLabel, productLabel, titlesLabel, placeField, dateField, priceField)
        }

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
            add(bulkBtn)
            val spacer = Span()
            expand(spacer)
            add(spacer, cancelBtn, saveBtn)
        }
        footer.element.setAttribute("slot", "footer")
        add(footer)
    }

    private fun applyAmazonOrder(order: AmazonOrder) {
        placeField.value = "Amazon"
        dateField.value = order.order_date?.toLocalDate()
        priceField.value = order.unit_price?.toDouble()
        selectedAmazonOrderId = order.id
        Notification.show("Applied Amazon order — click Save to confirm",
            2000, Notification.Position.BOTTOM_START)
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
