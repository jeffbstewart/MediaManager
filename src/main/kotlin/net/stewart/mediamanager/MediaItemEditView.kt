package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
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
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.AmazonImportService
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.MissingSeasonService
import net.stewart.mediamanager.service.ScanDetailService
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TitleCleanerService
import net.stewart.mediamanager.service.TmdbSearchResult
import net.stewart.mediamanager.service.TmdbService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Route(value = "item/:mediaItemId", layout = MainLayout::class)
@PageTitle("Edit Item")
class MediaItemEditView : VerticalLayout(), BeforeEnterObserver {

    override fun beforeEnter(event: BeforeEnterEvent) {
        val paramId = event.routeParameters.get("mediaItemId").orElse(null)?.toLongOrNull()
        val mediaItem = paramId?.let { MediaItem.findById(it) }
        if (mediaItem == null) {
            event.forwardTo("")
            return
        }
        buildContent(mediaItem)
    }

    private fun buildContent(mediaItem: MediaItem) {
        removeAll()
        isPadding = true
        isSpacing = true

        // Find associated titles
        val joins = MediaItemTitle.findAll().filter { it.media_item_id == mediaItem.id }
        val titles = joins.mapNotNull { Title.findById(it.title_id) }
        val primaryTitle = titles.firstOrNull()
        val displayName = primaryTitle?.name ?: mediaItem.product_name ?: "Item #${mediaItem.id}"

        // Header with poster and title info
        val headerRow = HorizontalLayout().apply {
            width = "100%"
            isPadding = false
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        }

        if (primaryTitle?.tmdb_id != null) {
            headerRow.add(Image("/posters/w185/${primaryTitle.id}", primaryTitle.name).apply {
                width = "80px"; height = "120px"
                style.set("object-fit", "cover")
                style.set("border-radius", "4px")
                style.set("flex-shrink", "0")
            })
        }

        val infoCol = VerticalLayout().apply {
            isPadding = false
            isSpacing = false

            add(H2(displayName).apply {
                style.set("margin", "0")
            })

            val meta = mutableListOf<String>()
            val format = try { MediaFormat.valueOf(mediaItem.media_format) } catch (_: Exception) { null }
            if (format != null) {
                meta.add(when (format) {
                    MediaFormat.BLURAY -> "Blu-ray"
                    MediaFormat.UHD_BLURAY -> "UHD Blu-ray"
                    MediaFormat.HD_DVD -> "HD DVD"
                    else -> format.name
                })
            }
            mediaItem.upc?.let { meta.add("UPC: $it") }
            if (meta.isNotEmpty()) {
                add(Span(meta.joinToString(" \u00b7 ")).apply {
                    style.set("color", "var(--lumo-secondary-text-color)")
                })
            }

            if (titles.size > 1) {
                add(Span("${titles.size} titles in this item").apply {
                    style.set("color", "var(--lumo-secondary-text-color)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                })
            }
        }
        headerRow.add(infoCol)
        add(headerRow)

        // === Title Settings ===
        if (primaryTitle != null) {
            val mediaTypeCombo = ComboBox<String>("Media Type").apply {
                setItems(MediaType.entries.map { it.name })
                value = primaryTitle.media_type
                width = "12em"
                addValueChangeListener {
                    val fresh = Title.findById(primaryTitle.id!!) ?: return@addValueChangeListener
                    fresh.media_type = it.value
                    fresh.save()
                    SearchIndexService.onTitleChanged(fresh.id!!)
                    showSaved()
                    // Rebuild to show/hide seasons field
                    buildContent(mediaItem)
                }
            }
            add(mediaTypeCombo)
        }

        // === Seasons (for TV titles) ===
        for (join in joins) {
            val title = titles.firstOrNull { it.id == join.title_id } ?: continue
            if (title.media_type != MediaType.TV.name) continue

            val label = if (titles.size > 1) "Seasons (${title.name})" else "Seasons"
            val seasonsField = TextField(label).apply {
                width = "100%"
                value = join.seasons ?: ""
                placeholder = "e.g. 2 or 1, 2, 3"
                valueChangeMode = ValueChangeMode.LAZY
                valueChangeTimeout = 1000
                addValueChangeListener {
                    val fresh = MediaItemTitle.findById(join.id!!) ?: return@addValueChangeListener
                    val seasonsValue = it.value?.trim()?.ifEmpty { null }
                    if (seasonsValue != null && MissingSeasonService.parseSeasonText(seasonsValue) == null) {
                        Notification.show("Invalid seasons format. Use numbers like: 2 or 1, 2 or 1-3",
                            4000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR)
                        return@addValueChangeListener
                    }
                    fresh.seasons = seasonsValue
                    fresh.save()
                    MissingSeasonService.syncStructuredSeasons(fresh.id!!, fresh.title_id, seasonsValue)
                    showSaved()
                }
            }
            add(seasonsField)
        }

        // === TMDB Enrichment ===
        if (primaryTitle != null) {
            val needsFix = primaryTitle.enrichment_status in setOf(
                EnrichmentStatus.FAILED.name,
                EnrichmentStatus.SKIPPED.name,
                EnrichmentStatus.ABANDONED.name
            )
            val isPending = primaryTitle.enrichment_status in setOf(
                EnrichmentStatus.PENDING.name,
                EnrichmentStatus.REASSIGNMENT_REQUESTED.name
            )

            if (needsFix || isPending) {
                add(Span("TMDB Match").apply {
                    style.set("font-weight", "600")
                    style.set("font-size", "var(--lumo-font-size-l)")
                    style.set("margin-top", "var(--lumo-space-m)")
                })

                val statusText = when (primaryTitle.enrichment_status) {
                    EnrichmentStatus.FAILED.name -> "Enrichment failed"
                    EnrichmentStatus.SKIPPED.name -> "No TMDB match found"
                    EnrichmentStatus.ABANDONED.name -> "Enrichment abandoned"
                    EnrichmentStatus.PENDING.name -> "Awaiting enrichment..."
                    EnrichmentStatus.REASSIGNMENT_REQUESTED.name -> "Re-enrichment queued..."
                    else -> primaryTitle.enrichment_status
                }
                val statusColor = if (needsFix) "var(--lumo-error-text-color)" else "var(--lumo-tertiary-text-color)"
                add(Span(statusText).apply {
                    style.set("color", statusColor)
                    style.set("font-size", "var(--lumo-font-size-s)")
                })
            }

            if (needsFix) {
                val tmdbSearchField = TextField().apply {
                    placeholder = "Search TMDB..."
                    value = primaryTitle.name
                    width = "100%"
                }
                val tmdbMediaType = ComboBox<String>().apply {
                    setItems("Movie", "TV")
                    value = if (primaryTitle.media_type == MediaType.TV.name) "TV" else "Movie"
                    width = "8em"
                }
                val tmdbSearchBtn = Button("Search")

                val searchRow = HorizontalLayout().apply {
                    width = "100%"
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                    isSpacing = true
                    add(tmdbSearchField, tmdbMediaType, tmdbSearchBtn)
                    expand(tmdbSearchField)
                }

                val tmdbResultsGrid = Grid<TmdbSearchResult>().apply {
                    width = "100%"
                    isAllRowsVisible = true
                    isVisible = false

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

                    addColumn(ComponentRenderer { r ->
                        Button("Select").apply {
                            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                            addClickListener {
                                applyTmdbSelection(primaryTitle, r)
                                buildContent(mediaItem)
                            }
                        }
                    }).setHeader("").setWidth("90px").setFlexGrow(0)
                }

                tmdbSearchBtn.addClickListener {
                    val query = tmdbSearchField.value?.trim() ?: ""
                    if (query.isBlank()) return@addClickListener
                    val tmdbService = TmdbService()
                    val results = if (tmdbMediaType.value == "TV") {
                        tmdbService.searchTvMultiple(query)
                    } else {
                        tmdbService.searchMovieMultiple(query)
                    }
                    tmdbResultsGrid.setItems(results)
                    tmdbResultsGrid.isVisible = true
                }

                add(searchRow, tmdbResultsGrid)
            }
        }

        // === Purchase Info ===
        add(Span("Purchase Info").apply {
            style.set("font-weight", "600")
            style.set("font-size", "var(--lumo-font-size-l)")
            style.set("margin-top", "var(--lumo-space-m)")
        })

        val purchasePlace = TextField("Purchase Place").apply {
            width = "100%"
            value = mediaItem.purchase_place ?: ""
            valueChangeMode = ValueChangeMode.LAZY
            valueChangeTimeout = 1000
            addValueChangeListener {
                mediaItem.purchase_place = it.value?.takeIf { v -> v.isNotBlank() }
                mediaItem.updated_at = LocalDateTime.now()
                mediaItem.save()
                showSaved()
            }
        }

        val purchaseDate = DatePicker("Purchase Date").apply {
            width = "100%"
            value = mediaItem.purchase_date
            addValueChangeListener {
                mediaItem.purchase_date = it.value
                mediaItem.updated_at = LocalDateTime.now()
                mediaItem.save()
                showSaved()
            }
        }

        val purchasePrice = NumberField("Purchase Price").apply {
            width = "100%"
            value = mediaItem.purchase_price?.toDouble()
            prefixComponent = Span("$")
            addValueChangeListener {
                mediaItem.purchase_price = it.value?.let { v -> BigDecimal.valueOf(v) }
                mediaItem.updated_at = LocalDateTime.now()
                mediaItem.save()
                showSaved()
            }
        }

        add(HorizontalLayout(purchasePlace).apply {
            width = "100%"; isPadding = false; isSpacing = true
        })
        add(HorizontalLayout(purchaseDate, purchasePrice).apply {
            width = "100%"; isPadding = false; isSpacing = true
        })

        // === Amazon Order Lookup ===
        val userId = AuthService.getCurrentUser()?.id
        if (userId != null) {
            add(Span("Amazon Purchase History").apply {
                style.set("font-weight", "600")
                style.set("font-size", "var(--lumo-font-size-l)")
                style.set("margin-top", "var(--lumo-space-l)")
            })

            if (mediaItem.amazon_order_id != null) {
                add(Span("Linked to Amazon order ${mediaItem.amazon_order_id}").apply {
                    style.set("color", "var(--lumo-success-text-color)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                })
            }

            val amazonSearchField = TextField("Search Amazon Orders").apply {
                width = "100%"
                isClearButtonVisible = true
                valueChangeMode = ValueChangeMode.LAZY
                valueChangeTimeout = 300
                val cleanedName = TitleCleanerService.clean(
                    mediaItem.product_name ?: displayName
                ).displayName
                value = cleanedName
            }

            val amazonGrid = Grid<AmazonOrder>().apply {
                width = "100%"
                height = "200px"

                addColumn({ TitleCleanerService.clean(it.product_name).displayName })
                    .setHeader("Product").setFlexGrow(1).setSortable(false)

                addColumn({ order ->
                    order.order_date?.toLocalDate()
                        ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) ?: ""
                }).setHeader("Date").setWidth("90px").setFlexGrow(0).setSortable(false)

                addColumn({ order ->
                    order.unit_price?.let { "$${it.setScale(2, RoundingMode.HALF_UP)}" } ?: ""
                }).setHeader("Price").setWidth("80px").setFlexGrow(0).setSortable(false)

                addColumn(ComponentRenderer { order ->
                    Button("Use").apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                        addClickListener {
                            AmazonImportService.linkToMediaItem(order.id!!, mediaItem.id!!)
                            // Rebuild to reflect the linked data
                            val fresh = MediaItem.findById(mediaItem.id!!)
                            if (fresh != null) buildContent(fresh)
                            Notification.show("Linked to Amazon order",
                                2000, Notification.Position.BOTTOM_START)
                                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                        }
                    }
                }).setHeader("").setWidth("70px").setFlexGrow(0).setSortable(false)
            }

            fun refreshAmazonGrid() {
                val query = amazonSearchField.value?.trim() ?: ""
                val results = AmazonImportService.searchOrders(
                    userId, query, unlinkedOnly = true, limit = 50
                )
                amazonGrid.setItems(results)
            }

            amazonSearchField.addValueChangeListener { refreshAmazonGrid() }
            add(amazonSearchField, amazonGrid)
            refreshAmazonGrid()
        }

        // === Ownership Photos ===
        add(Span("Ownership Photos").apply {
            style.set("font-weight", "600")
            style.set("font-size", "var(--lumo-font-size-l)")
            style.set("margin-top", "var(--lumo-space-l)")
        })

        val photoPanel = OwnershipPhotoPanel().apply {
            mediaItemId = mediaItem.id
            upc = mediaItem.upc
            refresh()
        }
        add(photoPanel)

        // === Navigation ===
        val navRow = HorizontalLayout().apply {
            isPadding = false
            isSpacing = true
            style.set("margin-top", "var(--lumo-space-l)")

            add(Button("Back to Add Item", VaadinIcon.ARROW_LEFT.create()).apply {
                addThemeVariants(ButtonVariant.LUMO_TERTIARY)
                addClickListener { ui.ifPresent { it.navigate("add") } }
            })

            if (primaryTitle != null) {
                add(Button("View Title", VaadinIcon.BOOK.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_TERTIARY)
                    addClickListener { ui.ifPresent { it.navigate("title/${primaryTitle.id}") } }
                })
            }
        }
        add(navRow)
    }

    private fun applyTmdbSelection(title: Title, result: TmdbSearchResult) {
        val tmdbId = result.tmdbId ?: return
        val mediaType = result.mediaType ?: return
        when (val outcome = ScanDetailService.assignTmdb(title.id!!, tmdbId, mediaType)) {
            is ScanDetailService.AssignResult.Merged -> {
                Notification.show("Merged into existing title: ${outcome.mergedTitleName}",
                    3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }
            is ScanDetailService.AssignResult.Assigned -> {
                Notification.show("TMDB match set — re-enrichment queued",
                    2000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            }
            is ScanDetailService.AssignResult.NotFound -> {
                Notification.show(outcome.message, 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR)
            }
        }
    }

    private fun showSaved() {
        Notification.show("Saved", 1500, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }
}
