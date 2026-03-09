package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*

@Route(value = "catalog", layout = MainLayout::class)
@PageTitle("All Content")
class BrowseView : KComposite() {

    // Filter state
    private var formatFilter: String? = null       // null=all, "MOVIE", "TV"
    private var playableOnly: Boolean = true
    private var selectedRatings: Set<String> = emptySet()  // empty=all, or set of content_rating values
    private var selectedTagIds: Set<Long> = emptySet()
    private var sortMode: SortMode = SortMode.NAME

    // UI components
    private lateinit var posterGrid: Div
    private lateinit var statusLabel: Span
    private lateinit var formatChips: HorizontalLayout
    private lateinit var sortChips: HorizontalLayout
    private lateinit var tagChipRow: HorizontalLayout
    private lateinit var ratingChipRow: HorizontalLayout

    // Cached data
    private var playableTitleIds: Set<Long> = emptySet()
    private var progressByTitle: Map<Long, PlaybackProgress> = emptyMap()

    private val broadcastListener: (TitleUpdateEvent) -> Unit = { _ ->
        ui.ifPresent { ui -> ui.access { refreshGrid() } }
    }

    private val root = ui {
        verticalLayout {
            isPadding = true
            isSpacing = true

            // --- Sort row ---
            sortChips = horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isSpacing = false
                style.set("gap", "var(--lumo-space-xs)")
                style.set("flex-wrap", "wrap")

                add(Span("Sort:").apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("margin-right", "var(--lumo-space-xs)")
                })
            }

            // --- Format + Playable filter row ---
            formatChips = horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isSpacing = false
                style.set("gap", "var(--lumo-space-xs)")
                style.set("flex-wrap", "wrap")
            }

            // --- Rating filter row ---
            ratingChipRow = horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isSpacing = false
                style.set("gap", "var(--lumo-space-xs)")
                style.set("flex-wrap", "wrap")

                add(Span("Rating:").apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("margin-right", "var(--lumo-space-xs)")
                })
            }

            // --- Tag chip row ---
            tagChipRow = horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isSpacing = false
                style.set("gap", "var(--lumo-space-xs)")
                style.set("flex-wrap", "wrap")
                style.set("overflow-x", "auto")
                style.set("max-width", "100%")
            }

            // --- Status ---
            statusLabel = span().apply {
                style.set("color", "rgba(255,255,255,0.5)")
                style.set("font-size", "var(--lumo-font-size-s)")
            }

            // --- Poster grid ---
            posterGrid = Div().apply {
                style.set("display", "grid")
                style.set("grid-template-columns", "repeat(auto-fill, minmax(140px, 1fr))")
                style.set("gap", "var(--lumo-space-m)")
                style.set("width", "100%")
            }
            add(posterGrid)
        }
    }

    init {
        buildSortChips()
        buildFormatChips()
        buildRatingChips()
        buildTagChips()
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

    // --- Chip builders ---

    private fun buildSortChips() {
        // Keep the label, remove everything after it
        while (sortChips.componentCount > 1) sortChips.remove(sortChips.getComponentAt(1))
        for (mode in SortMode.entries) {
            sortChips.add(createChip(mode.label, mode == sortMode) {
                sortMode = mode
                buildSortChips()
                refreshGrid()
            })
        }
    }

    private fun buildFormatChips() {
        formatChips.removeAll()
        data class FilterDef(val label: String, val value: String?)
        val formats = listOf(
            FilterDef("All", null),
            FilterDef("Movies", MediaType.MOVIE.name),
            FilterDef("TV", MediaType.TV.name)
        )
        for (f in formats) {
            formatChips.add(createChip(f.label, formatFilter == f.value) {
                formatFilter = f.value
                buildFormatChips()
                refreshGrid()
            })
        }

        // Playable toggle
        formatChips.add(Span("\u00b7").apply {
            style.set("color", "rgba(255,255,255,0.3)")
            style.set("margin", "0 var(--lumo-space-xs)")
        })
        formatChips.add(createChip("Playable", playableOnly) {
            playableOnly = !playableOnly
            buildFormatChips()
            refreshGrid()
        })
    }

    private fun buildRatingChips() {
        // Keep the label, remove everything after it
        while (ratingChipRow.componentCount > 1) ratingChipRow.remove(ratingChipRow.getComponentAt(1))

        // Collect distinct ratings that actually exist in the catalog, ordered by severity
        val allRatings = listOf("G", "PG", "PG-13", "R", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "TV-14", "TV-MA")
        val existingRatings = Title.findAll().mapNotNull { it.content_rating }.distinct()
        val ratingsToShow = allRatings.filter { it in existingRatings }

        ratingChipRow.add(createChip("All", selectedRatings.isEmpty()) {
            selectedRatings = emptySet()
            buildRatingChips()
            refreshGrid()
        })
        for (rating in ratingsToShow) {
            val selected = rating in selectedRatings
            ratingChipRow.add(createChip(rating, selected) {
                selectedRatings = if (selected) selectedRatings - rating else selectedRatings + rating
                buildRatingChips()
                refreshGrid()
            })
        }

        // Hide the row if there are no ratings in the data
        ratingChipRow.isVisible = ratingsToShow.isNotEmpty()
    }

    private fun buildTagChips() {
        tagChipRow.removeAll()
        val tags = TagService.getAllTags()
        if (tags.isEmpty()) {
            tagChipRow.isVisible = false
            return
        }
        tagChipRow.isVisible = true

        for (tag in tags) {
            val selected = tag.id in selectedTagIds
            val chip = createTagChip(tag, selected) {
                selectedTagIds = if (selected) {
                    selectedTagIds - tag.id!!
                } else {
                    selectedTagIds + tag.id!!
                }
                buildTagChips()
                refreshGrid()
            }
            tagChipRow.add(chip)
        }

        if (selectedTagIds.isNotEmpty()) {
            tagChipRow.add(createChip("Clear", false) {
                selectedTagIds = emptySet()
                buildTagChips()
                refreshGrid()
            })
        }
    }

    // --- Grid refresh ---

    private fun refreshGrid() {
        val currentUser = AuthService.getCurrentUser()
        var titles = Title.findAll()

        // Filter hidden titles
        titles = titles.filter { !it.hidden }

        // Filter personally-hidden titles
        val personallyHiddenIds = UserTitleFlagService.getHiddenTitleIds()
        titles = titles.filter { it.id !in personallyHiddenIds }

        // Rating ceiling
        if (currentUser?.rating_ceiling != null) {
            titles = titles.filter { currentUser.canSeeRating(it.content_rating) }
        }

        // Format filter
        if (formatFilter != null) {
            titles = titles.filter { it.media_type == formatFilter }
        }

        // Rating filter (OR — title matches any selected rating)
        if (selectedRatings.isNotEmpty()) {
            titles = titles.filter { it.content_rating in selectedRatings }
        }

        // Tag filter (OR union — title has any selected tag)
        if (selectedTagIds.isNotEmpty()) {
            val matchingTitleIds = TagService.getTitleIdsForTags(selectedTagIds)
            titles = titles.filter { it.id in matchingTitleIds }
        }

        // Playable filter — a title is playable if it has at least one playable transcode.
        // MP4/M4V are always playable (streamed directly). MKV/AVI need a ForBrowser copy.
        val allTranscodes = Transcode.findAll().filter { it.file_path != null }
        val nasRoot = TranscoderAgent.getNasRoot()
        playableTitleIds = allTranscodes.filter { tc ->
            val fp = tc.file_path!!
            if (TranscoderAgent.needsTranscoding(fp)) {
                nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, fp)
            } else {
                true // MP4/M4V are always playable
            }
        }.map { it.title_id }.toSet()

        if (playableOnly) {
            titles = titles.filter { it.id in playableTitleIds }
        }

        // Load progress for overlay bars
        progressByTitle = PlaybackProgressService.getProgressByTitle()

        // Apply search from navbar (SearchIndexService integration)
        // The navbar search ComboBox navigates directly, but we can support
        // a query parameter in the future. For now, show all.

        // Sort
        titles = when (sortMode) {
            SortMode.NAME -> titles.sortedBy { (it.sort_name ?: it.name).lowercase() }
            SortMode.YEAR -> titles.sortedByDescending { it.release_year ?: 0 }
            SortMode.RECENTLY_ADDED -> titles.sortedByDescending { it.created_at }
            SortMode.POPULARITY -> titles.sortedByDescending { it.popularity ?: 0.0 }
        }

        // Render
        posterGrid.removeAll()
        for (title in titles) {
            posterGrid.add(buildPosterCard(title))
        }

        statusLabel.text = "${titles.size} titles"
    }

    private fun buildPosterCard(title: Title): Div {
        return Div().apply {
            style.set("cursor", "pointer")
            style.set("text-align", "center")

            element.addEventListener("click") {
                ui.ifPresent { it.navigate("title/${title.id}") }
            }

            // Poster container with progress overlay
            val posterContainer = Div().apply {
                style.set("position", "relative")
                style.set("width", "100%")
                style.set("aspect-ratio", "2/3")
                style.set("border-radius", "8px")
                style.set("overflow", "hidden")
                style.set("background", "rgba(255,255,255,0.05)")

                val posterUrl = title.posterUrl(PosterSize.THUMBNAIL)
                if (posterUrl != null) {
                    add(Image(posterUrl, title.name).apply {
                        width = "100%"
                        height = "100%"
                        style.set("object-fit", "cover")
                        style.set("display", "block")
                    })
                }

                // Playable indicator (small play icon in bottom-right)
                if (title.id in playableTitleIds) {
                    add(Div().apply {
                        style.set("position", "absolute")
                        style.set("bottom", "6px")
                        style.set("right", "6px")
                        style.set("width", "24px")
                        style.set("height", "24px")
                        style.set("background", "rgba(0,0,0,0.6)")
                        style.set("border-radius", "50%")
                        style.set("display", "flex")
                        style.set("align-items", "center")
                        style.set("justify-content", "center")
                        // CSS triangle for play icon
                        add(Div().apply {
                            style.set("width", "0")
                            style.set("height", "0")
                            style.set("border-style", "solid")
                            style.set("border-width", "5px 0 5px 9px")
                            style.set("border-color", "transparent transparent transparent white")
                            style.set("margin-left", "2px")
                        })
                    })
                }

                // Progress bar at bottom
                val progress = progressByTitle[title.id]
                if (progress != null && progress.duration_seconds != null && progress.duration_seconds!! > 0) {
                    val pct = ((progress.position_seconds / progress.duration_seconds!!) * 100)
                        .toInt().coerceIn(0, 100)
                    add(Div().apply {
                        style.set("position", "absolute")
                        style.set("bottom", "0")
                        style.set("left", "0")
                        style.set("width", "100%")
                        style.set("height", "3px")
                        style.set("background", "rgba(0,0,0,0.5)")

                        add(Div().apply {
                            style.set("width", "${pct}%")
                            style.set("height", "100%")
                            style.set("background", "var(--lumo-primary-color)")
                        })
                    })
                }
            }
            add(posterContainer)

            // Title name below poster
            add(Span(title.name).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("display", "block")
            })

            // Year
            if (title.release_year != null) {
                add(Span(title.release_year.toString()).apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                })
            }
        }
    }

    // --- Chip helpers ---

    private fun createChip(label: String, selected: Boolean, onClick: () -> Unit): Span {
        return Span(label).apply {
            style.set("padding", "4px 12px")
            style.set("border-radius", "9999px")
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("cursor", "pointer")
            style.set("user-select", "none")
            style.set("white-space", "nowrap")
            if (selected) {
                style.set("background", "var(--lumo-primary-color)")
                style.set("color", "white")
            } else {
                style.set("background", "rgba(255,255,255,0.1)")
                style.set("color", "rgba(255,255,255,0.7)")
            }
            element.addEventListener("click") { onClick() }
        }
    }

    private fun createTagChip(tag: Tag, selected: Boolean, onClick: () -> Unit): Span {
        return Span(tag.name).apply {
            style.set("padding", "4px 12px")
            style.set("border-radius", "9999px")
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("cursor", "pointer")
            style.set("user-select", "none")
            style.set("white-space", "nowrap")
            if (selected) {
                style.set("background", tag.bg_color)
                style.set("color", tag.textColor())
            } else {
                style.set("background", "rgba(255,255,255,0.1)")
                style.set("color", "rgba(255,255,255,0.7)")
            }
            element.addEventListener("click") { onClick() }
        }
    }
}

private enum class SortMode(val label: String) {
    NAME("Name"),
    YEAR("Year"),
    RECENTLY_ADDED("Recent"),
    POPULARITY("Popular")
}
