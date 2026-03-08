package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.asc
import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.data.provider.SortDirection
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.Broadcaster
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TagService
import net.stewart.mediamanager.service.TitleUpdateEvent
import net.stewart.mediamanager.service.TmdbSearchResult
import net.stewart.mediamanager.service.TmdbService
import net.stewart.mediamanager.service.UserTitleFlagService
import java.time.LocalDateTime

private const val NEEDS_ATTENTION = "Needs attention"

@Route(value = "data-quality", layout = MainLayout::class)
@PageTitle("Data Quality")
class DataQualityView : KComposite() {

    private lateinit var searchField: TextField
    private lateinit var statusFilter: ComboBox<String>
    private lateinit var tagFilter: MultiSelectComboBox<Tag>
    private lateinit var showHiddenCheckbox: Checkbox
    private lateinit var statusLabel: Span
    private lateinit var titleGrid: Grid<Title>

    private var seasonMap: Map<Long, String> = emptyMap()
    private var totalCount: Long = 0

    private val broadcastListener: (TitleUpdateEvent) -> Unit = { event ->
        SearchIndexService.onTitleChanged(event.titleId)
        ui.ifPresent { ui -> ui.access { refreshGrid() } }
    }

    private val root = ui {
        verticalLayout {
            h2("Data Quality")
            span("Titles needing TMDB enrichment or metadata corrections.").apply {
                style.set("color", "var(--lumo-secondary-text-color)")
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("margin-top", "calc(-1 * var(--lumo-space-m))")
                style.set("display", "block")
            }

            horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                isSpacing = true

                searchField = textField {
                    placeholder = "Search titles..."
                    isClearButtonVisible = true
                    width = "20em"
                    valueChangeMode = ValueChangeMode.LAZY
                    valueChangeTimeout = 300
                    addValueChangeListener { refreshGrid() }
                }

                statusFilter = comboBox {
                    placeholder = "All statuses"
                    isClearButtonVisible = true
                    width = "14em"
                    setItems(
                        NEEDS_ATTENTION,
                        *EnrichmentStatus.entries.map { it.name }.toTypedArray()
                    )
                    addValueChangeListener { refreshGrid() }
                }

                showHiddenCheckbox = checkBox("Show hidden") {
                    addValueChangeListener { refreshGrid() }
                }

                tagFilter = MultiSelectComboBox<Tag>().apply {
                    placeholder = "Filter by tag"
                    width = "16em"
                    isClearButtonVisible = true
                    setItemLabelGenerator { it.name }
                    setItems(TagService.getAllTags())
                    addValueChangeListener { refreshGrid() }
                }
                add(tagFilter)
            }

            statusLabel = span()

            titleGrid = grid {
                width = "100%"
                isAllRowsVisible = true

                addColumn(ComponentRenderer { title ->
                    val url = title.posterUrl(PosterSize.THUMBNAIL)
                    if (url != null) {
                        Image(url, title.name).apply {
                            height = "60px"
                            width = "40px"
                            style.set("object-fit", "cover")
                        }
                    } else {
                        Span("—")
                    }
                }).setHeader("Poster").setWidth("80px").setFlexGrow(0).setSortable(false)

                addColumn(ComponentRenderer { title ->
                    val container = com.vaadin.flow.component.html.Div().apply {
                        style.set("cursor", "pointer")
                        style.set("overflow", "hidden")

                        add(Span(title.name).apply {
                            style.set("color", "var(--lumo-primary-text-color)")
                            style.set("display", "block")
                            style.set("overflow", "hidden")
                            style.set("text-overflow", "ellipsis")
                            style.set("white-space", "nowrap")
                        })

                        element.addEventListener("click") {
                            ui.ifPresent { it.navigate("title/${title.id}") }
                        }
                    }
                    container
                }).apply {
                    setHeader("Title")
                    setFlexGrow(1)
                    setSortable(true)
                    setKey("name")
                }

                addColumn({ it.release_year?.toString() ?: "" }).setHeader("Year")
                    .setWidth("80px").setFlexGrow(0).setSortable(true).setKey("year")

                addColumn({ title ->
                    seasonMap[title.id] ?: ""
                }).setHeader("Seasons").setWidth("100px").setFlexGrow(0).setSortable(false)

                addColumn(ComponentRenderer { title ->
                    val rating = title.content_rating
                    if (rating != null) {
                        Span(rating).apply {
                            element.themeList.add("badge")
                            style.set("font-size", "var(--lumo-font-size-xs)")
                        }
                    } else {
                        Span("—")
                    }
                }).setHeader("Rating").setWidth("100px").setFlexGrow(0).setSortable(false)

                addColumn({ it.media_type ?: "" }).setHeader("Type")
                    .setWidth("80px").setFlexGrow(0).setSortable(true).setKey("media_type")

                addColumn(ComponentRenderer { title ->
                    val status = title.enrichment_status ?: ""
                    Span(status).apply {
                        element.themeList.add("badge")
                        style.set("font-size", "var(--lumo-font-size-xxs)")
                        when (status) {
                            EnrichmentStatus.ENRICHED.name -> {
                                element.themeList.add("success")
                            }
                            EnrichmentStatus.FAILED.name -> {
                                element.themeList.add("error")
                            }
                            EnrichmentStatus.PENDING.name, EnrichmentStatus.REASSIGNMENT_REQUESTED.name -> {
                                element.themeList.add("contrast")
                            }
                            EnrichmentStatus.SKIPPED.name -> {
                                // default badge styling
                            }
                        }
                    }
                }).setHeader("Status").setWidth("140px").setFlexGrow(0).setSortable(false)

                addColumn(ComponentRenderer { title ->
                    Button(VaadinIcon.EDIT.create()).apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
                        addClickListener { openEditDialog(title) }
                    }
                }).setHeader("").setWidth("50px").setFlexGrow(0).setSortable(false).setResizable(false)
            }

            // Spacer to keep grid rows clear of Vaadin dev-mode overlay
            span { style.set("min-height", "6em"); style.set("display", "block") }
        }
    }

    init {
        statusFilter.value = NEEDS_ATTENTION
        val nameCol = titleGrid.getColumnByKey("name")
        titleGrid.sort(listOf(GridSortOrder(nameCol, SortDirection.ASCENDING)))
        refreshGrid()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        Broadcaster.registerTitleListener(broadcastListener)
    }

    override fun onDetach(detachEvent: DetachEvent) {
        Broadcaster.unregisterTitleListener(broadcastListener)
        super.onDetach(detachEvent)
    }

    private fun refreshGrid() {
        val search = searchField.value?.trim() ?: ""
        val statusValue = statusFilter.value
        val showHidden = showHiddenCheckbox.value
        val selectedTags = tagFilter.value ?: emptySet()
        val allTitles = Title.findAll(Title::sort_name.asc)
        totalCount = allTitles.size.toLong()

        var filtered = allTitles

        // Filter by rating ceiling for non-admin users
        val currentUser = AuthService.getCurrentUser()
        if (currentUser != null && currentUser.rating_ceiling != null) {
            filtered = filtered.filter { currentUser.canSeeRating(it.content_rating) }
        }

        // Filter personally-hidden titles (per-user, always applied)
        val personallyHiddenIds = UserTitleFlagService.getHiddenTitleIds()
        filtered = filtered.filter { it.id !in personallyHiddenIds }

        // Filter hidden titles unless "Show hidden" is checked
        val hiddenCount = filtered.count { it.hidden }
        if (!showHidden) {
            filtered = filtered.filter { !it.hidden }
        }

        // Apply status filter
        if (statusValue != null) {
            filtered = if (statusValue == NEEDS_ATTENTION) {
                filtered.filter { it.enrichment_status != EnrichmentStatus.ENRICHED.name }
            } else {
                filtered.filter { it.enrichment_status == statusValue }
            }
        }

        // Apply tag filter (OR logic)
        if (selectedTags.isNotEmpty()) {
            val tagIds = selectedTags.mapNotNull { it.id }.toSet()
            val matchingTitleIds = TagService.getTitleIdsForTags(tagIds)
            filtered = filtered.filter { it.id in matchingTitleIds }
        }

        // Apply text search via inverted index
        if (search.isNotEmpty()) {
            val matchingIds = SearchIndexService.search(search)
            if (matchingIds != null) {
                filtered = filtered.filter { it.id in matchingIds }
            }
        }

        seasonMap = loadSeasonMap()
        titleGrid.setItems(filtered)
        val hasFilter = search.isNotEmpty() || statusValue != null || showHidden || selectedTags.isNotEmpty()
        val hiddenSuffix = if (hiddenCount > 0 && !showHidden) " ($hiddenCount hidden)" else ""
        statusLabel.text = if (hasFilter) {
            "Showing ${filtered.size} of $totalCount titles$hiddenSuffix"
        } else {
            "Showing ${filtered.size} titles$hiddenSuffix"
        }
    }

    private fun loadSeasonMap(): Map<Long, String> {
        return JdbiOrm.jdbi().withHandle<Map<Long, String>, Exception> { handle ->
            handle.createQuery(
                """SELECT mit.title_id, GROUP_CONCAT(DISTINCT mit.seasons)
                   FROM media_item_title mit
                   WHERE mit.seasons IS NOT NULL
                   GROUP BY mit.title_id"""
            ).map { rs, _ ->
                rs.getLong("title_id") to (rs.getString(2) ?: "")
            }.list().toMap()
        }
    }

    private fun openEditDialog(title: Title) {
        TitleEditDialog(title) { refreshGrid() }.open()
    }
}

internal class TitleEditDialog(
    private val title: Title,
    private val onSave: () -> Unit
) : Dialog() {

    init {
        headerTitle = "Edit Title"
        width = "700px"

        val t = title  // avoid shadowing by Vaadin's HTML title attribute in apply blocks

        val nameLabel = Span("Title: ${t.name}").apply {
            style.set("font-weight", "bold")
        }
        val rawLabel = Span("Raw UPC title: ${t.raw_upc_title ?: "—"}").apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
        }
        val upcLabel = Span("UPC: ${lookupLinkedUpcs(t.id!!)}").apply {
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("color", "var(--lumo-secondary-text-color)")
        }
        val currentStatus = Span("Status: ${t.enrichment_status}").apply {
            style.set("font-size", "var(--lumo-font-size-s)")
        }

        val tmdbIdField = IntegerField("TMDB ID").apply {
            value = t.tmdb_id
            isStepButtonsVisible = false
            width = "100%"
        }

        val mediaTypeCombo = ComboBox<String>("Media Type").apply {
            setItems(MediaType.entries.map { it.name })
            value = t.media_type
            width = "100%"
        }

        // Seasons field — editable when single join, disabled when multiple
        val joins = MediaItemTitle.findAll().filter { it.title_id == t.id }
        val seasonsField = TextField("Seasons").apply {
            width = "100%"
            if (joins.size == 1) {
                value = joins[0].seasons ?: ""
            } else if (joins.size > 1) {
                value = joins.mapNotNull { it.seasons }.distinct().joinToString(", ")
                isEnabled = false
                helperText = "Multiple media items linked — edit seasons per-item"
            }
        }

        val hiddenCheckbox = Checkbox("Hidden").apply {
            value = t.hidden
        }

        // --- TMDB Search section ---
        val tmdbSearchLabel = Span("Search TMDB").apply {
            style.set("font-weight", "bold")
            style.set("margin-top", "var(--lumo-space-m)")
        }

        val tmdbSearchField = TextField().apply {
            placeholder = "Search query"
            value = t.name
            width = "100%"
        }
        val tmdbMediaTypeToggle = ComboBox<String>().apply {
            setItems("Movie", "TV")
            value = when (t.media_type) {
                MediaType.TV.name -> "TV"
                else -> "Movie"
            }
            width = "8em"
        }
        val tmdbSearchButton = Button("Search")

        val tmdbSearchRow = HorizontalLayout().apply {
            width = "100%"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
            isSpacing = true
            add(tmdbSearchField, tmdbMediaTypeToggle, tmdbSearchButton)
            expand(tmdbSearchField)
        }

        val tmdbResultsGrid = Grid<TmdbSearchResult>().apply {
            width = "100%"
            isAllRowsVisible = true
            isVisible = false

            addColumn(ComponentRenderer { r ->
                val url = r.posterPath?.let { "https://image.tmdb.org/t/p/w185$it" }
                if (url != null) {
                    Image(url, r.title ?: "").apply {
                        height = "60px"; width = "40px"
                        style.set("object-fit", "cover")
                    }
                } else {
                    Span("—")
                }
            }).setHeader("").setWidth("60px").setFlexGrow(0)

            addColumn({ it.title ?: "" }).setHeader("Title").setFlexGrow(1)
            addColumn({ it.releaseYear?.toString() ?: "" }).setHeader("Year").setWidth("70px").setFlexGrow(0)
            addColumn({ r -> r.overview?.let { o -> if (o.length > 80) o.take(80) + "..." else o } ?: "" })
                .setHeader("Overview").setFlexGrow(1)

            addColumn(ComponentRenderer { r ->
                Button("Select").apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                    addClickListener {
                        tmdbIdField.value = r.tmdbId
                        mediaTypeCombo.value = if (r.mediaType == "TV") MediaType.TV.name else MediaType.MOVIE.name
                    }
                }
            }).setHeader("").setWidth("90px").setFlexGrow(0)
        }

        tmdbSearchButton.addClickListener {
            val query = tmdbSearchField.value?.trim() ?: ""
            if (query.isBlank()) return@addClickListener
            val tmdbService = TmdbService()
            val results = if (tmdbMediaTypeToggle.value == "TV") {
                tmdbService.searchTvMultiple(query)
            } else {
                tmdbService.searchMovieMultiple(query)
            }
            tmdbResultsGrid.setItems(results)
            tmdbResultsGrid.isVisible = true
        }

        val content = com.vaadin.flow.component.orderedlayout.VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(nameLabel, rawLabel, upcLabel, currentStatus, tmdbIdField, mediaTypeCombo, seasonsField, hiddenCheckbox)
            add(tmdbSearchLabel, tmdbSearchRow, tmdbResultsGrid)
        }
        add(content)

        // Only show "Flag as Multi-Pack" if the linked media item is currently SINGLE
        val linkedMediaItem = findLinkedMediaItem(t.id!!)
        val multiPackBtn = if (linkedMediaItem != null &&
            linkedMediaItem.expansion_status == ExpansionStatus.SINGLE.name) {
            Button("Flag as Multi-Pack").apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL)
                addClickListener { flagAsMultiPack(linkedMediaItem) }
            }
        } else null

        val cancelBtn = Button("Cancel") { close() }
        val saveBtn = Button("Save").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener {
                save(tmdbIdField.value, mediaTypeCombo.value,
                    seasonsField.value, joins, hiddenCheckbox.value)
            }
        }

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            isSpacing = true
            if (multiPackBtn != null) {
                add(multiPackBtn)
                expand(multiPackBtn)
                multiPackBtn.style.set("margin-right", "auto")
            }
            add(cancelBtn, saveBtn)
        }
        footer.element.setAttribute("slot", "footer")
        add(footer)
    }

    private fun save(newTmdbId: Int?, newMediaType: String?,
                     newSeasons: String, joins: List<MediaItemTitle>, newHidden: Boolean) {
        try {
            val fresh = Title.findById(title.id!!)
                ?: run {
                    Notification.show("Title no longer exists", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    close()
                    return
                }

            var changed = false

            val tmdbIdChanged = newTmdbId != fresh.tmdb_id && newTmdbId != null
            if (tmdbIdChanged) {
                fresh.tmdb_id = newTmdbId
                fresh.enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name
                if (newMediaType != null) {
                    fresh.media_type = newMediaType
                }
                changed = true
            } else if (newMediaType != null && newMediaType != fresh.media_type) {
                fresh.media_type = newMediaType
                changed = true
            }

            if (newHidden != fresh.hidden) {
                fresh.hidden = newHidden
                changed = true
            }

            // Save seasons on the join row (only when single join is editable)
            if (joins.size == 1) {
                val seasonsValue = newSeasons.trim().ifEmpty { null }
                val join = MediaItemTitle.findById(joins[0].id!!)
                if (join != null && join.seasons != seasonsValue) {
                    join.seasons = seasonsValue
                    join.save()
                    changed = true
                }
            }

            if (!changed) {
                close()
                return
            }

            fresh.updated_at = LocalDateTime.now()
            fresh.save()
            close()
            onSave()
            Notification.show("Title updated", 2000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
        } catch (e: Exception) {
            Notification.show("Save failed: ${e.message}", 4000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }

    private fun lookupLinkedUpcs(titleId: Long): String {
        val upcs = MediaItemTitle.findAll()
            .filter { it.title_id == titleId }
            .mapNotNull { MediaItem.findById(it.media_item_id)?.upc }
        return if (upcs.isEmpty()) "—" else upcs.joinToString(", ")
    }

    private fun findLinkedMediaItem(titleId: Long): MediaItem? {
        val join = MediaItemTitle.findAll().firstOrNull { it.title_id == titleId } ?: return null
        return MediaItem.findById(join.media_item_id)
    }

    private fun flagAsMultiPack(mediaItem: MediaItem) {
        val fresh = MediaItem.findById(mediaItem.id!!) ?: run {
            Notification.show("Media item no longer exists", 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
            return
        }
        fresh.expansion_status = ExpansionStatus.NEEDS_EXPANSION.name
        fresh.updated_at = LocalDateTime.now()
        fresh.save()

        // Set the title's enrichment to SKIPPED so it doesn't get enriched as-is
        val freshTitle = Title.findById(title.id!!)
        if (freshTitle != null && freshTitle.enrichment_status != EnrichmentStatus.ENRICHED.name) {
            freshTitle.enrichment_status = EnrichmentStatus.SKIPPED.name
            freshTitle.updated_at = LocalDateTime.now()
            freshTitle.save()
        }

        close()
        onSave()
        Notification.show("Flagged as multi-pack — expand at /expand", 3000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }
}
