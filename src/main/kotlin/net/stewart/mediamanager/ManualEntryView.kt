package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TmdbSearchResult
import net.stewart.mediamanager.service.TmdbService
import net.stewart.mediamanager.service.WishListService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Route(value = "manual-entry", layout = MainLayout::class)
@PageTitle("Add Title Manually")
class ManualEntryView : KComposite() {

    private val tmdbService = TmdbService()
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

    private lateinit var searchResultsGrid: Grid<TmdbSearchResult>
    private lateinit var entryForm: VerticalLayout
    private lateinit var selectedTitleLabel: Span
    private lateinit var formatCombo: ComboBox<MediaFormat>
    private lateinit var seasonsField: TextField
    private lateinit var seasonsRow: HorizontalLayout
    private lateinit var recentGrid: Grid<RecentEntry>

    private var selectedResult: TmdbSearchResult? = null
    private val recentEntries = mutableListOf<RecentEntry>()

    private data class RecentEntry(
        val title: String,
        val format: String,
        val seasons: String?,
        val addedAt: LocalDateTime
    )

    private val root = ui {
        verticalLayout {
            h2("Add Title Manually")

            // --- Search Section ---
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

            searchField.addKeyDownListener(com.vaadin.flow.component.Key.ENTER, { _ ->
                searchButton.click()
            })

            // --- Results Grid ---
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
                        addClickListener { selectResult(r) }
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
                // Hide entry form when doing a new search
                entryForm.isVisible = false
            }

            // --- Entry Form (hidden until a result is selected) ---
            entryForm = verticalLayout {
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

                formatCombo = comboBox("Format") {
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

                seasonsRow = horizontalLayout {
                    isVisible = false
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                    seasonsField = textField("Seasons") {
                        placeholder = "e.g. 2 or 1-3"
                        width = "10em"
                    }
                }

                button("Add to Collection") {
                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                    addClickListener { addToCollection() }
                }
            }

            // --- Recent Entries Grid ---
            add(Span("Recent Entries").apply {
                style.set("font-weight", "bold")
                style.set("margin-top", "var(--lumo-space-l)")
            })
            recentGrid = grid {
                width = "100%"
                isAllRowsVisible = true
                isVisible = false

                addColumn({ it.title }).setHeader("Title").setFlexGrow(1)
                addColumn({ it.format }).setHeader("Format").setWidth("120px").setFlexGrow(0)
                addColumn({ it.seasons ?: "" }).setHeader("Seasons").setWidth("100px").setFlexGrow(0)
                addColumn({ it.addedAt.format(timeFormat) }).setHeader("Added").setWidth("100px").setFlexGrow(0)
            }
        }
    }

    private fun selectResult(result: TmdbSearchResult) {
        selectedResult = result
        val yearStr = result.releaseYear?.let { " ($it)" } ?: ""
        selectedTitleLabel.text = "${result.title}$yearStr"
        seasonsRow.isVisible = result.mediaType == MediaType.TV.name
        seasonsField.value = ""
        entryForm.isVisible = true
    }

    private fun addToCollection() {
        val result = selectedResult ?: return
        val tmdbId = result.tmdbId ?: return
        val format = formatCombo.value ?: run {
            Notification.show("Please select a format", 2000, Notification.Position.BOTTOM_START)
            return
        }
        val now = LocalDateTime.now()

        // Parse seasons text (e.g. "2" or "1-3" → "S2" or "S1-S3")
        val seasonsText = seasonsField.value?.trim()?.takeIf { it.isNotBlank() }
        val seasonsValue = seasonsText?.let { parseSeasonsInput(it) }

        // Dedup Title: check if a Title with this tmdb_id + media_type already exists
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

        // Create MediaItem with MANUAL entry source
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

        // Create MediaItemTitle join
        MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!,
            disc_number = 1,
            seasons = seasonsValue
        ).save()

        // Fulfill wishes for new titles
        if (isNewTitle) {
            WishListService.fulfillMediaWishes(tmdbKey)
        }

        // Success feedback
        val yearStr = result.releaseYear?.let { " ($it)" } ?: ""
        Notification.show("Added: ${result.title}$yearStr as ${format.name}", 3000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)

        // Add to recent entries
        recentEntries.add(0, RecentEntry(
            title = "${result.title}$yearStr",
            format = format.name,
            seasons = seasonsValue,
            addedAt = now
        ))
        recentGrid.setItems(recentEntries)
        recentGrid.isVisible = true

        // Reset form
        entryForm.isVisible = false
        selectedResult = null
    }

    /** Parses season input like "2" → "S2", "1-3" → "S1-S3", "1,3" → "S1, S3". */
    private fun parseSeasonsInput(input: String): String {
        // Non-numeric keywords like "all" pass through as-is
        if (input.equals("all", ignoreCase = true)) return input.lowercase()
        // Range: "1-3"
        val rangeMatch = Regex("""^(\d+)\s*-\s*(\d+)$""").matchEntire(input)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toInt()
            val end = rangeMatch.groupValues[2].toInt()
            return (start..end).joinToString(", ") { "S$it" }
        }
        // Comma-separated: "1,3,5" or "1, 3, 5"
        val parts = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.all { it.matches(Regex("""\d+""")) }) {
            return parts.joinToString(", ") { "S$it" }
        }
        // Fallback: single number or already formatted
        val single = input.toIntOrNull()
        if (single != null) return "S$single"
        return input
    }
}
