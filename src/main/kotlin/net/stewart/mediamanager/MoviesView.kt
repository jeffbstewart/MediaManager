package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*

@Route(value = "content/movies", layout = MainLayout::class)
@PageTitle("Movies")
class MoviesView : KComposite() {

    private var playableOnly: Boolean = true
    private var selectedRatings: Set<String> = emptySet()
    private var sortMode: MovieSortMode = MovieSortMode.NAME

    private lateinit var posterGrid: Div
    private lateinit var statusLabel: Span
    private lateinit var chipRow: HorizontalLayout

    private var playableTitleIds: Set<Long> = emptySet()
    private var progressByTitle: Map<Long, PlaybackProgress> = emptyMap()

    private val broadcastListener: (TitleUpdateEvent) -> Unit = { _ ->
        ui.ifPresent { ui -> ui.access { refreshGrid() } }
    }

    private val root = ui {
        verticalLayout {
            isPadding = true
            isSpacing = true

            chipRow = horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isSpacing = false
                style.set("gap", "var(--lumo-space-xs)")
                style.set("flex-wrap", "wrap")
                style.set("row-gap", "var(--lumo-space-xs)")
            }

            statusLabel = span().apply {
                style.set("color", "rgba(255,255,255,0.5)")
                style.set("font-size", "var(--lumo-font-size-s)")
            }

            posterGrid = PosterGridHelper.createPosterGrid()
            add(posterGrid)
        }
    }

    init {
        buildChips()
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

    private fun buildChips() {
        chipRow.removeAll()

        // Playable toggle
        chipRow.add(createChip("Playable", playableOnly) {
            playableOnly = !playableOnly
            buildChips()
            refreshGrid()
        })

        // Rating chips
        chipRow.add(createSeparator())
        val allRatings = listOf("G", "PG", "PG-13", "R")
        val existingRatings = Title.findAll()
            .filter { it.media_type == MediaType.MOVIE.name }
            .mapNotNull { it.content_rating }.distinct()
        val ratingsToShow = allRatings.filter { it in existingRatings }

        if (ratingsToShow.isNotEmpty()) {
            chipRow.add(createChip("All Ratings", selectedRatings.isEmpty()) {
                selectedRatings = emptySet()
                buildChips()
                refreshGrid()
            })
            for (rating in ratingsToShow) {
                val selected = rating in selectedRatings
                chipRow.add(createChip(rating, selected) {
                    selectedRatings = if (selected) selectedRatings - rating else selectedRatings + rating
                    buildChips()
                    refreshGrid()
                })
            }
        }

        // Sort chips
        chipRow.add(createSeparator())
        for (mode in MovieSortMode.entries) {
            chipRow.add(createChip(mode.label, mode == sortMode) {
                sortMode = mode
                buildChips()
                refreshGrid()
            })
        }
    }

    private fun refreshGrid() {
        val currentUser = AuthService.getCurrentUser()
        var titles = Title.findAll()
            .filter { it.media_type == MediaType.MOVIE.name }
            .filter { !it.hidden }

        val personallyHiddenIds = UserTitleFlagService.getHiddenTitleIds()
        titles = titles.filter { it.id !in personallyHiddenIds }

        if (currentUser?.rating_ceiling != null) {
            titles = titles.filter { currentUser.canSeeRating(it.content_rating) }
        }
        if (selectedRatings.isNotEmpty()) {
            titles = titles.filter { it.content_rating in selectedRatings }
        }

        playableTitleIds = PosterGridHelper.computePlayableTitleIds()
        if (playableOnly) {
            titles = titles.filter { it.id in playableTitleIds }
        }

        progressByTitle = PlaybackProgressService.getProgressByTitle()

        titles = when (sortMode) {
            MovieSortMode.NAME -> titles.sortedBy { (it.sort_name ?: it.name).lowercase() }
            MovieSortMode.YEAR -> titles.sortedByDescending { it.release_year ?: 0 }
            MovieSortMode.RECENTLY_ADDED -> titles.sortedByDescending { it.created_at }
            MovieSortMode.POPULARITY -> titles.sortedByDescending { it.popularity ?: 0.0 }
        }

        posterGrid.removeAll()
        for (title in titles) {
            posterGrid.add(PosterGridHelper.buildPosterCard(title, playableTitleIds, progressByTitle))
        }
        statusLabel.text = "${titles.size} movies"
    }

    private fun createSeparator(): Span = Span("\u00b7").apply {
        style.set("color", "rgba(255,255,255,0.3)")
        style.set("margin", "0 var(--lumo-space-xs)")
    }

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
}

private enum class MovieSortMode(val label: String) {
    NAME("Name"),
    YEAR("Year"),
    RECENTLY_ADDED("Recent"),
    POPULARITY("Popular")
}
