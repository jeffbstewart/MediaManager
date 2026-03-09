package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

@Route(value = "transcodes/unmatched", layout = MainLayout::class)
@PageTitle("Unmatched Files")
class TranscodeUnmatchedView : KComposite() {

    private lateinit var unmatchedSection: VerticalLayout
    private lateinit var unmatchedGrid: Grid<DiscoveredFile>
    private var unmatchedSuggestions: MutableMap<Long?, List<ScoredTitle>> = mutableMapOf()
    private var cachedUnmatchedFiles: MutableList<DiscoveredFile> = mutableListOf()

    private val root = ui {
        verticalLayout {

            h2("Unmatched Files")

            unmatchedSection = verticalLayout {
                isPadding = false
                isSpacing = false

                unmatchedGrid = grid {
                    width = "100%"
                    height = "600px"
                    pageSize = 100

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
                        val parsed = file.parsed_title ?: ""
                        Span(parsed).apply {
                            if (parsed.isNotEmpty()) {
                                element.setAttribute("title", parsed)
                            }
                            style.set("overflow", "hidden")
                            style.set("text-overflow", "ellipsis")
                            style.set("white-space", "nowrap")
                            style.set("display", "block")
                        }
                    }).setHeader("Parsed Title").setWidth("200px").setFlexGrow(0)
                    addColumn({ it.parsed_year?.toString() ?: "" }).setHeader("Year").setWidth("80px").setFlexGrow(0)
                    addColumn(ComponentRenderer { file ->
                        if (file.media_type == MediaType.PERSONAL.name) {
                            Span("\u2014")  // No suggestions for personal videos
                        } else {
                            val suggestions = unmatchedSuggestions[file.id]
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
                                        addClickListener { acceptSuggestion(file, top.title) }
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
                                addClickListener { ignoreFile(file) }
                            })
                        }
                    }).setHeader("Actions").setWidth("180px").setFlexGrow(0)
                }
            }

            // Spacer
            span { style.set("min-height", "6em"); style.set("display", "block") }
        }
    }

    init {
        refreshUnmatchedGrid()
    }

    private fun refreshUnmatchedGrid() {
        val unmatched = DiscoveredFile.findAll()
            .filter { it.match_status == DiscoveredFileStatus.UNMATCHED.name }
            .sortedBy { it.parsed_title?.lowercase() }

        cachedUnmatchedFiles = unmatched.toMutableList()

        val allTitles = Title.findAll()
        unmatchedSuggestions = unmatched.associate { file ->
            if (file.media_type == MediaType.PERSONAL.name) {
                file.id to emptyList()
            } else {
                val query = file.parsed_title ?: file.file_name
                file.id to FuzzyMatchService.findSuggestions(query, allTitles)
            }
        }.toMutableMap()

        unmatchedGrid.setItems(cachedUnmatchedFiles.toList())
        updateUnmatchedHeader()
    }

    private fun removeUnmatchedFiles(idsToRemove: Set<Long>) {
        cachedUnmatchedFiles.removeAll { it.id in idsToRemove }
        idsToRemove.forEach { unmatchedSuggestions.remove(it) }
        unmatchedGrid.setItems(cachedUnmatchedFiles.toList())
        updateUnmatchedHeader()
        // Update the sidebar badge
        (ui.orElse(null)?.children
            ?.filter { it is MainLayout }
            ?.findFirst()?.orElse(null) as? MainLayout)
            ?.refreshUnmatchedBadge()
    }

    private fun updateUnmatchedHeader() {
        val count = cachedUnmatchedFiles.size
        if (count == 0) {
            unmatchedSection.isVisible = false
        } else {
            unmatchedSection.isVisible = true
            val header = unmatchedSection.children
                .filter { it is H3 }
                .findFirst().orElse(null)
            if (header == null) {
                unmatchedSection.addComponentAsFirst(H3("Unmatched Files ($count)"))
            } else {
                (header as H3).text = "Unmatched Files ($count)"
            }
        }
    }

    private fun acceptSuggestion(file: DiscoveredFile, title: Title) {
        val count = linkDiscoveredFileToTitle(file, title)

        val linkedIds = if (file.media_type == MediaType.TV.name && !file.parsed_title.isNullOrBlank()) {
            val showName = file.parsed_title!!.lowercase()
            cachedUnmatchedFiles
                .filter { it.media_type == MediaType.TV.name && it.parsed_title?.lowercase() == showName }
                .mapNotNull { it.id }.toSet()
        } else {
            setOfNotNull(file.id)
        }
        removeUnmatchedFiles(linkedIds)

        val msg = if (count == 1) "Linked to ${title.name}" else "Linked $count episodes to ${title.name}"
        Notification.show(msg, 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun ignoreFile(file: DiscoveredFile) {
        val fresh = DiscoveredFile.findById(file.id!!) ?: return
        fresh.match_status = DiscoveredFileStatus.IGNORED.name
        fresh.save()

        removeUnmatchedFiles(setOfNotNull(file.id))
    }

    private fun openCreatePersonalVideoDialog(file: DiscoveredFile) {
        CreatePersonalVideoDialog(file) {
            removeUnmatchedFiles(setOfNotNull(file.id))
        }.open()
    }

    private fun openLinkDialog(file: DiscoveredFile) {
        LinkTranscodeDialog(file) {
            val linkedIds = if (file.media_type == MediaType.TV.name && !file.parsed_title.isNullOrBlank()) {
                val showName = file.parsed_title!!.lowercase()
                cachedUnmatchedFiles
                    .filter { it.media_type == MediaType.TV.name && it.parsed_title?.lowercase() == showName }
                    .mapNotNull { it.id }.toSet()
            } else {
                setOfNotNull(file.id)
            }
            removeUnmatchedFiles(linkedIds)
        }.open()
    }
}

/**
 * Links a discovered file (and all sibling TV episodes with the same show name) to a title.
 * Returns the number of files linked.
 */
internal fun linkDiscoveredFileToTitle(file: DiscoveredFile, title: Title): Int {
    val fresh = DiscoveredFile.findById(file.id!!) ?: return 0

    val filesToLink = if (fresh.media_type == MediaType.TV.name && !fresh.parsed_title.isNullOrBlank()) {
        val showName = fresh.parsed_title!!.lowercase()
        DiscoveredFile.findAll().filter {
            it.match_status == DiscoveredFileStatus.UNMATCHED.name &&
                it.media_type == MediaType.TV.name &&
                it.parsed_title?.lowercase() == showName
        }
    } else {
        listOf(fresh)
    }

    val now = LocalDateTime.now()
    for (df in filesToLink) {
        var episodeId: Long? = null
        if (df.parsed_season != null && df.parsed_episode != null) {
            val existing = Episode.findAll().firstOrNull {
                it.title_id == title.id && it.season_number == df.parsed_season &&
                    it.episode_number == df.parsed_episode
            }
            episodeId = if (existing != null) {
                existing.id
            } else {
                val ep = Episode(
                    title_id = title.id!!,
                    season_number = df.parsed_season!!,
                    episode_number = df.parsed_episode!!,
                    name = df.parsed_episode_title
                )
                ep.save()
                ep.id
            }
        }

        Transcode(
            title_id = title.id!!,
            episode_id = episodeId,
            file_path = df.file_path,
            file_size_bytes = df.file_size_bytes,
            status = TranscodeStatus.COMPLETE.name,
            media_format = df.media_format,
            match_method = MatchMethod.MANUAL.name,
            created_at = now,
            updated_at = now
        ).save()

        df.match_status = DiscoveredFileStatus.LINKED.name
        df.matched_title_id = title.id
        df.matched_episode_id = episodeId
        df.match_method = MatchMethod.MANUAL.name
        df.save()
    }

    return filesToLink.size
}

internal class LinkTranscodeDialog(
    private val file: DiscoveredFile,
    private val onLinked: () -> Unit
) : Dialog() {

    private val log = LoggerFactory.getLogger(LinkTranscodeDialog::class.java)
    private val titleGrid: Grid<Title>
    private val tmdbResultsGrid: Grid<TmdbSearchResult>
    private val tmdbService = TmdbService()

    init {
        headerTitle = "Link: ${file.parsed_title ?: file.file_name}"
        width = "700px"

        // --- Catalog search section ---
        val searchField = TextField("Search catalog").apply {
            placeholder = "Type to search..."
            isClearButtonVisible = true
            width = "100%"
            valueChangeMode = ValueChangeMode.LAZY
            valueChangeTimeout = 300
            addValueChangeListener { refreshTitles(value) }
        }

        titleGrid = Grid(Title::class.java, false)
        titleGrid.width = "100%"
        titleGrid.height = "300px"

        titleGrid.addColumn({ it.name }).setHeader("Title").setFlexGrow(1)
        titleGrid.addColumn({ it.media_type }).setHeader("Type").setWidth("80px").setFlexGrow(0)
        titleGrid.addColumn({ it.release_year?.toString() ?: "" }).setHeader("Year").setWidth("80px").setFlexGrow(0)
        titleGrid.addColumn(ComponentRenderer { title ->
            Button("Select").apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                addClickListener { linkToTitle(title) }
            }
        }).setHeader("").setWidth("100px").setFlexGrow(0)

        // --- TMDB search section ---
        val tmdbHeader = Span("Or search TMDB to add a new title").apply {
            style.set("font-weight", "bold")
            style.set("margin-top", "1em")
        }

        val tmdbSearchField = TextField().apply {
            placeholder = "Search TMDB..."
            width = "100%"
            value = file.parsed_title ?: ""
        }

        val tmdbMediaType = ComboBox<String>().apply {
            setItems("Movie", "TV")
            value = if (file.media_type == MediaType.TV.name) "TV" else "Movie"
            width = "120px"
        }

        val tmdbSearchBtn = Button("Search").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        }

        val tmdbSearchRow = HorizontalLayout(tmdbSearchField, tmdbMediaType, tmdbSearchBtn).apply {
            width = "100%"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
            setFlexGrow(1.0, tmdbSearchField)
        }

        tmdbResultsGrid = Grid(TmdbSearchResult::class.java, false)
        tmdbResultsGrid.width = "100%"
        tmdbResultsGrid.height = "250px"
        tmdbResultsGrid.isVisible = false

        tmdbResultsGrid.addColumn(ComponentRenderer { r ->
            val url = r.posterPath?.let { "https://image.tmdb.org/t/p/w92$it" }
            if (url != null) {
                Image(url, r.title ?: "").apply {
                    height = "60px"; width = "40px"
                    style.set("object-fit", "cover")
                    style.set("border-radius", "2px")
                }
            } else {
                Span("\u2014")
            }
        }).setHeader("").setWidth("60px").setFlexGrow(0)
        tmdbResultsGrid.addColumn({ it.title ?: "" }).setHeader("Title").setFlexGrow(1)
        tmdbResultsGrid.addColumn({ it.releaseYear?.toString() ?: "" }).setHeader("Year").setWidth("70px").setFlexGrow(0)
        tmdbResultsGrid.addColumn({ truncate(it.overview, 80) }).setHeader("Overview").setFlexGrow(1)
        tmdbResultsGrid.addColumn(ComponentRenderer { result ->
            Button("Add & Link").apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_SUCCESS)
                addClickListener { addFromTmdbAndLink(result) }
            }
        }).setHeader("").setWidth("110px").setFlexGrow(0)

        tmdbSearchBtn.addClickListener {
            val query = tmdbSearchField.value.trim()
            if (query.isBlank()) return@addClickListener
            val results = if (tmdbMediaType.value == "TV") {
                tmdbService.searchTvMultiple(query, maxResults = 10)
            } else {
                tmdbService.searchMovieMultiple(query, maxResults = 10)
            }
            tmdbResultsGrid.setItems(results)
            tmdbResultsGrid.isVisible = true
        }

        // --- Layout ---
        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            add(searchField, titleGrid, tmdbHeader, tmdbSearchRow, tmdbResultsGrid)
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

        if (!file.parsed_title.isNullOrBlank()) {
            searchField.value = file.parsed_title
        }
        refreshTitles(file.parsed_title ?: "")
    }

    private fun refreshTitles(search: String) {
        val query = search.trim()
        val allTitles = Title.findAll().filter { !it.hidden }
        if (query.isEmpty()) {
            titleGrid.setItems(allTitles.sortedBy { it.name.lowercase() })
            return
        }
        val lower = query.lowercase()

        val substringMatches = allTitles.filter {
            it.name.lowercase().contains(lower) ||
                (it.sort_name?.lowercase()?.contains(lower) == true)
        }

        if (substringMatches.isNotEmpty()) {
            val scored = substringMatches
                .map { title ->
                    val nameScore = FuzzyMatchService.similarity(query, title.name)
                    val sortScore = if (title.sort_name != null) FuzzyMatchService.similarity(query, title.sort_name!!) else 0.0
                    title to maxOf(nameScore, sortScore)
                }
                .sortedByDescending { it.second }
                .map { it.first }
            titleGrid.setItems(scored)
        } else {
            val fuzzy = FuzzyMatchService.findSuggestions(query, allTitles, maxResults = 10, threshold = 0.50)
            titleGrid.setItems(fuzzy.map { it.title })
        }
    }

    private fun linkToTitle(title: Title) {
        val count = linkDiscoveredFileToTitle(file, title)
        if (count == 0) {
            Notification.show("File record no longer exists", 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
            close()
            return
        }

        close()
        onLinked()
        val msg = if (count == 1) "Linked to ${title.name}" else "Linked $count episodes to ${title.name}"
        Notification.show(msg, 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun addFromTmdbAndLink(result: TmdbSearchResult) {
        val tmdbKey = result.tmdbKey() ?: run {
            log.warn("ADD_LINK: tmdbId is null for result '{}', aborting", result.title)
            return
        }
        val now = LocalDateTime.now()

        // Dedup: check if a Title with this tmdb_id AND media_type already exists
        // (TMDB uses separate ID spaces for movies and TV — same numeric ID can be different content)
        var title = Title.findAll().firstOrNull { it.tmdbKey() == tmdbKey }
        val created: Boolean
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
            created = true
            log.info("ADD_LINK: Created new title id={} name=\"{}\" type={} tmdb_id={} year={}",
                title.id, title.name, title.media_type, tmdbKey, result.releaseYear)
            WishListService.fulfillMediaWishes(tmdbKey)
        } else {
            created = false
            log.info("ADD_LINK: Reusing existing title id={} name=\"{}\" type={} tmdb_id={} status={}",
                title.id, title.name, title.media_type, title.tmdb_id, title.enrichment_status)
        }

        val count = linkDiscoveredFileToTitle(file, title)
        log.info("ADD_LINK: Linked {} file(s) to title id={} (created={})", count, title.id, created)
        if (count == 0) {
            Notification.show("File record no longer exists", 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
            close()
            return
        }

        close()
        onLinked()
        val msg = if (count == 1) "Added & linked to ${title.name}" else "Added & linked $count episodes to ${title.name}"
        Notification.show(msg, 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun truncate(text: String?, maxLen: Int): String {
        if (text.isNullOrBlank()) return ""
        return if (text.length <= maxLen) text else text.take(maxLen) + "..."
    }
}
