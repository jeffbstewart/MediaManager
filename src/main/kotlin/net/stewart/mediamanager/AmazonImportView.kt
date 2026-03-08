package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.component.upload.receivers.MemoryBuffer
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.AmazonImportService
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.FuzzyMatchService
import net.stewart.mediamanager.service.TitleCleanerService
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

@Route(value = "import", layout = MainLayout::class)
@PageTitle("Amazon Order Import")
class AmazonImportView : KComposite() {

    private lateinit var uploadSection: VerticalLayout
    private lateinit var browseSection: VerticalLayout
    private lateinit var summaryBar: Span
    private lateinit var searchField: TextField
    private lateinit var mediaOnlyToggle: Checkbox
    private lateinit var unlinkedOnlyToggle: Checkbox
    private lateinit var hideCancelledToggle: Checkbox
    private lateinit var orderGrid: Grid<AmazonOrder>
    private lateinit var uploadNewButton: Button

    private var titleMap: Map<Long, String> = emptyMap()
    private var seasonMap: Map<Long, String> = emptyMap()

    private val root = ui {
        verticalLayout {

            h2("Amazon Order Import")
            span("Import your Amazon purchase history to speed up cataloging prices and dates. Go to Amazon > Account > Download Your Data, request \"Your Orders\", and upload the .zip or .csv file below. Once imported, you can search and link orders to your media items \u2014 matching purchases automatically fills in price, date, and retailer.") {
                style.set("color", "rgba(255,255,255,0.6)")
                style.set("margin-bottom", "var(--lumo-space-m)")
            }

            // --- State 1: Upload ---
            uploadSection = verticalLayout {
                isPadding = false
                isSpacing = true

                span("Upload your Amazon data export (.zip or .csv) to get started.")

                val buffer = MemoryBuffer()
                val upload = Upload(buffer).apply {
                    setAcceptedFileTypes(".zip", ".csv")
                    maxFileSize = 20 * 1024 * 1024 // 20 MB
                    uploadButton = Button("Upload File")
                }
                upload.addSucceededListener {
                    processUpload(buffer, it.fileName)
                }
                upload.addFailedListener { event ->
                    Notification.show("Upload failed: ${event.reason.message}",
                        4000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                }
                add(upload)
            }

            // --- State 2: Browse/Search/Link ---
            browseSection = verticalLayout {
                isPadding = false
                isSpacing = true
                isVisible = false

                summaryBar = span {
                    style.set("background", "var(--lumo-contrast-5pct)")
                    style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                    style.set("border-radius", "var(--lumo-border-radius-m)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("display", "block")
                    style.set("width", "100%")
                }

                horizontalLayout {
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                    isSpacing = true
                    width = "100%"

                    searchField = textField("Search orders") {
                        placeholder = "Type to filter by product name..."
                        isClearButtonVisible = true
                        width = "300px"
                        valueChangeMode = ValueChangeMode.LAZY
                        valueChangeTimeout = 300
                        addValueChangeListener { refreshGrid() }
                    }

                    mediaOnlyToggle = checkBox("Media items only") {
                        value = true
                        addValueChangeListener { refreshGrid() }
                    }

                    unlinkedOnlyToggle = checkBox("Unlinked only") {
                        value = true
                        addValueChangeListener { refreshGrid() }
                    }

                    hideCancelledToggle = checkBox("Hide cancelled") {
                        value = true
                        addValueChangeListener { refreshGrid() }
                    }

                    // Spacer pushes Upload New to the right
                    val spacer = Span()
                    add(spacer)
                    setFlexGrow(1.0, spacer)

                    uploadNewButton = button("Upload New") {
                        addThemeVariants(ButtonVariant.LUMO_TERTIARY)
                        addClickListener { showUploadState() }
                    }
                }

                orderGrid = grid {
                    width = "100%"
                    height = "600px"
                    setSelectionMode(Grid.SelectionMode.NONE)

                    addColumn(ComponentRenderer { order ->
                        Span(order.product_name).apply {
                            element.setAttribute("title", order.product_name)
                        }
                    }).setHeader("Product Name").setFlexGrow(1)

                    addColumn({
                        it.order_date?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) ?: ""
                    }).setHeader("Date").setWidth("110px").setFlexGrow(0)

                    addColumn({
                        if (it.unit_price != null) "$${it.unit_price!!.setScale(2, RoundingMode.HALF_UP)}" else ""
                    }).setHeader("Price").setWidth("90px").setFlexGrow(0)

                    addColumn({ it.product_condition ?: "" })
                        .setHeader("Condition").setWidth("100px").setFlexGrow(0)

                    addColumn(ComponentRenderer { order ->
                        if (order.linked_media_item_id != null) {
                            val name = titleMap[order.linked_media_item_id] ?: "Item #${order.linked_media_item_id}"
                            Span(name).apply {
                                style.set("font-size", "var(--lumo-font-size-s)")
                                style.set("color", "var(--lumo-success-text-color)")
                            }
                        } else {
                            Span("—").apply {
                                style.set("color", "var(--lumo-secondary-text-color)")
                            }
                        }
                    }).setHeader("Linked To").setWidth("250px").setFlexGrow(0)

                    addColumn(ComponentRenderer { order ->
                        HorizontalLayout().apply {
                            isSpacing = true
                            isPadding = false
                            if (order.linked_media_item_id != null) {
                                add(Button("Unlink").apply {
                                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
                                    addClickListener { unlinkOrder(order) }
                                })
                            } else {
                                add(Button("Link").apply {
                                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                                    addClickListener { openLinkDialog(order) }
                                })
                            }
                        }
                    }).setHeader("Actions").setWidth("120px").setFlexGrow(0)
                }
            }

            span { style.set("min-height", "6em"); style.set("display", "block") }
        }
    }

    override fun onAttach(attachEvent: com.vaadin.flow.component.AttachEvent) {
        super.onAttach(attachEvent)
        titleMap = loadTitleMap()
        seasonMap = loadSeasonMap()
        checkExistingOrders()
    }

    private fun checkExistingOrders() {
        val user = AuthService.getCurrentUser() ?: return
        val (total, _, _) = AmazonImportService.countOrders(user.id!!)
        if (total > 0) {
            showBrowseState()
        }
    }

    private fun processUpload(buffer: MemoryBuffer, fileName: String) {
        try {
            val user = AuthService.getCurrentUser()
            if (user == null) {
                Notification.show("Not logged in", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR)
                return
            }

            val rows = if (fileName.endsWith(".zip", ignoreCase = true)) {
                AmazonImportService.parseZip(buffer.inputStream)
            } else {
                AmazonImportService.parseCsv(buffer.inputStream)
            }

            if (rows.isEmpty()) {
                Notification.show("No rows found in file", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR)
                return
            }

            val result = AmazonImportService.importRows(user.id!!, rows)

            Notification.show(
                "Imported ${result.inserted} orders" +
                    if (result.skipped > 0) ", ${result.skipped} already existed" else "",
                4000, Notification.Position.BOTTOM_START
            ).addThemeVariants(NotificationVariant.LUMO_SUCCESS)

            titleMap = loadTitleMap()
            seasonMap = loadSeasonMap()
            showBrowseState()
        } catch (e: Exception) {
            Notification.show("Import error: ${e.message}", 4000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }

    private fun showBrowseState() {
        uploadSection.isVisible = false
        browseSection.isVisible = true
        refreshGrid()
    }

    private fun showUploadState() {
        browseSection.isVisible = false
        uploadSection.isVisible = true
    }

    private fun refreshGrid() {
        val user = AuthService.getCurrentUser() ?: return
        val userId = user.id!!

        val query = searchField.value ?: ""
        val mediaOnly = mediaOnlyToggle.value ?: true
        val unlinkedOnly = unlinkedOnlyToggle.value ?: true
        val hideCancelled = hideCancelledToggle.value ?: true

        val orders = AmazonImportService.searchOrders(
            userId, query, mediaOnly, unlinkedOnly, hideCancelled
        )
        orderGrid.setItems(orders)

        // Update summary
        val (total, _, linked) = AmazonImportService.countOrders(userId)
        val mediaCount = countMediaOrders(userId)
        summaryBar.text = "$total orders stored, $mediaCount media-related, $linked linked to catalog"
    }

    private fun countMediaOrders(userId: Long): Int {
        // Quick count: fetch all product names and filter
        return JdbiOrm.jdbi().withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT product_name FROM amazon_order WHERE user_id = :uid")
                .bind("uid", userId)
                .mapTo(String::class.java)
                .list()
        }.count { AmazonImportService.isLikelyMedia(it) }
    }

    private fun openLinkDialog(order: AmazonOrder) {
        LinkMediaItemDialog(order.product_name, titleMap, seasonMap) { selectedItem ->
            AmazonImportService.linkToMediaItem(order.id!!, selectedItem.id!!)
            titleMap = loadTitleMap()
            seasonMap = loadSeasonMap()
            refreshGrid()
            Notification.show("Linked to ${selectedItem.product_name ?: "media item"}",
                3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
        }.open()
    }

    private fun unlinkOrder(order: AmazonOrder) {
        AmazonImportService.unlinkFromMediaItem(order.id!!)
        refreshGrid()
        Notification.show("Unlinked", 2000, Notification.Position.BOTTOM_START)
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
}

/** Dialog for manually linking an Amazon order to a MediaItem. */
private class LinkMediaItemDialog(
    private val amazonProductName: String,
    private val titleMap: Map<Long, String>,
    private val seasonMap: Map<Long, String>,
    private val onLinked: (MediaItem) -> Unit
) : Dialog() {

    private val itemGrid: Grid<MediaItem>

    init {
        headerTitle = "Link: ${amazonProductName.take(80)}${if (amazonProductName.length > 80) "..." else ""}"
        width = "850px"

        val searchField = TextField("Search media items").apply {
            placeholder = "Type to search..."
            isClearButtonVisible = true
            width = "100%"
            valueChangeMode = ValueChangeMode.LAZY
            valueChangeTimeout = 300
            addValueChangeListener { refreshItems(value) }
        }

        itemGrid = Grid(MediaItem::class.java, false)
        itemGrid.width = "100%"
        itemGrid.height = "300px"

        itemGrid.addColumn(ComponentRenderer { item ->
            val productName = item.product_name ?: titleMap[item.id] ?: "—"
            val titles = titleMap[item.id] ?: ""
            VerticalLayout().apply {
                isPadding = false
                isSpacing = false
                style.set("padding", "var(--lumo-space-xs) 0")
                add(Span(productName).apply {
                    style.set("font-weight", "bold")
                    element.setAttribute("title", productName)
                })
                if (titles.isNotEmpty() && titles != productName) {
                    add(Span(titles).apply {
                        style.set("font-size", "var(--lumo-font-size-s)")
                        style.set("color", "var(--lumo-secondary-text-color)")
                        element.setAttribute("title", titles)
                    })
                }
            }
        }).setHeader("Item").setFlexGrow(1)

        itemGrid.addColumn({ it.upc ?: "" }).setHeader("UPC").setWidth("140px").setFlexGrow(0)
        itemGrid.addColumn({ it.media_format }).setHeader("Format").setWidth("90px").setFlexGrow(0)
        itemGrid.addColumn({ item ->
            seasonMap[item.id]?.let { "S$it" } ?: ""
        }).setHeader("Season").setWidth("80px").setFlexGrow(0)

        itemGrid.addColumn(ComponentRenderer { item ->
            Button("Select").apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                addClickListener {
                    close()
                    onLinked(item)
                }
            }
        }).setHeader("").setWidth("100px").setFlexGrow(0)

        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(searchField, itemGrid)
        }
        add(content)

        val cancelBtn = Button("Cancel") { close() }
        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            add(cancelBtn)
        }
        footer.element.setAttribute("slot", "footer")
        add(footer)

        // Pre-populate search with cleaned Amazon product name
        val cleaned = TitleCleanerService.clean(amazonProductName).displayName
        searchField.value = cleaned
        refreshItems(cleaned)
    }

    private fun refreshItems(search: String) {
        val query = search.trim().lowercase()
        val allItems = MediaItem.findAll()

        if (query.isEmpty()) {
            itemGrid.setItems(allItems.sortedBy { titleMap[it.id]?.lowercase() ?: "" })
            return
        }

        val filtered = allItems.filter { item ->
            val product = item.product_name?.lowercase() ?: ""
            val titles = titleMap[item.id]?.lowercase() ?: ""
            val upc = item.upc?.lowercase() ?: ""
            product.contains(query) || titles.contains(query) || upc.contains(query)
        }

        if (filtered.isNotEmpty()) {
            itemGrid.setItems(filtered.sortedBy { titleMap[it.id]?.lowercase() ?: "" })
        } else {
            // Fuzzy fallback
            val allTitles = Title.findAll().filter { !it.hidden }
            val suggestions = FuzzyMatchService.findSuggestions(query, allTitles, maxResults = 10, threshold = 0.50)
            val suggestedTitleIds = suggestions.map { it.title.id }.toSet()
            val mit = MediaItemTitle.findAll()
            val matchedItemIds = mit.filter { it.title_id in suggestedTitleIds }.map { it.media_item_id }.toSet()
            val fuzzyItems = allItems.filter { it.id in matchedItemIds }
            itemGrid.setItems(fuzzyItems)
        }
    }
}
