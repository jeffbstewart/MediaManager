package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
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
import net.stewart.mediamanager.service.TitleCleanerService
import net.stewart.mediamanager.service.TmdbSearchResult
import net.stewart.mediamanager.service.TmdbService
import net.stewart.mediamanager.service.WishListService
import java.time.LocalDateTime

@Route(value = "expand", layout = MainLayout::class)
@PageTitle("Expand Multi-Packs")
class ExpandView : KComposite() {

    private lateinit var countLabel: Span
    private lateinit var itemGrid: Grid<MediaItem>

    private val root = ui {
        verticalLayout {
            h2("Expand Multi-Packs")
            span("Some UPC codes represent purchased items containing more than one movie \u2014 double features, trilogies, box sets, etc. This page lets you specify which individual movies are in each multi-pack, since the catalog tracks watchable titles rather than purchasable products. (TV shows don\u2019t need expansion \u2014 a single disc set already maps to one show.)") {
                style.set("color", "rgba(255,255,255,0.6)")
                style.set("margin-bottom", "var(--lumo-space-m)")
            }
            countLabel = span()

            itemGrid = grid {
                width = "100%"
                isAllRowsVisible = true

                addColumn({ it.upc ?: "" }).setHeader("UPC").setWidth("140px").setFlexGrow(0)
                    .setSortable(false)
                addColumn({ lookupRawTitle(it.id!!) }).setHeader("Product Name").setFlexGrow(1)
                    .setSortable(false)
                addColumn({ it.media_format }).setHeader("Format").setWidth("120px").setFlexGrow(0)
                    .setSortable(false)
                addColumn({ "~${it.title_count}" }).setHeader("Est. Titles").setWidth("100px")
                    .setFlexGrow(0).setSortable(false)

                addItemClickListener { event ->
                    ExpandDialog(event.item) { refreshGrid() }.open()
                }
            }

            span { style.set("min-height", "6em"); style.set("display", "block") }
        }
    }

    init {
        refreshGrid()
    }

    private fun refreshGrid() {
        val items = MediaItem.findAll()
            .filter { it.expansion_status == ExpansionStatus.NEEDS_EXPANSION.name }
        itemGrid.setItems(items)
        countLabel.text = "${items.size} multi-pack(s) awaiting expansion"
        (ui.orElse(null)?.children
            ?.filter { it is MainLayout }
            ?.findFirst()?.orElse(null) as? MainLayout)
            ?.refreshExpandBadge()
    }

    private fun lookupRawTitle(mediaItemId: Long): String {
        val joins = MediaItemTitle.findAll().filter { it.media_item_id == mediaItemId }
        if (joins.isEmpty()) return "—"
        val title = Title.findById(joins.first().title_id) ?: return "—"
        return title.raw_upc_title ?: title.name
    }
}

private class ExpandDialog(
    private val mediaItem: MediaItem,
    private val onComplete: () -> Unit
) : Dialog() {

    private val tmdbService = TmdbService()
    /** Title IDs linked when the dialog opened — these are the "originals" to retire. */
    private val originalTitleIds: Set<Long> = MediaItemTitle.findAll()
        .filter { it.media_item_id == mediaItem.id }
        .mapNotNull { Title.findById(it.title_id)?.id }
        .toSet()
    private var linkedGrid: Grid<LinkedTitle>
    private var linkedCountLabel: Span
    private var searchResultsGrid: Grid<TmdbSearchResult>

    /** View model for linked titles grid */
    private data class LinkedTitle(
        val joinId: Long,
        val titleId: Long,
        val name: String,
        val year: Int?,
        val posterPath: String?,
        val tmdbKey: TmdbId?,
        val discNumber: Int
    )

    init {
        headerTitle = lookupRawTitle()
        width = "800px"
        isResizable = true
        @Suppress("DEPRECATION")
        isModal = true

        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true

            // Header info
            add(HorizontalLayout().apply {
                isSpacing = true
                add(Span("UPC: ${mediaItem.upc ?: "—"}"))
                val fmtFile = when (mediaItem.media_format) {
                    "DVD" -> "dvd.svg"
                    "BLURAY" -> "bluray.svg"
                    "UHD_BLURAY" -> "uhd-bluray.svg"
                    "HD_DVD" -> "hd-dvd.svg"
                    else -> null
                }
                if (fmtFile != null) {
                    add(Image("icons/$fmtFile", mediaItem.media_format).apply {
                        height = "20px"
                        style.set("width", "auto")
                    })
                } else {
                    add(Span(mediaItem.media_format).apply {
                        element.themeList.add("badge")
                    })
                }
            })

            // Linked titles section
            linkedCountLabel = Span()
            add(Span("Linked Titles").apply {
                style.set("font-weight", "bold")
            })
            add(linkedCountLabel)

            linkedGrid = Grid<LinkedTitle>().apply {
                width = "100%"
                isAllRowsVisible = true

                addColumn(ComponentRenderer { lt ->
                    val url = lt.posterPath?.let { "/posters/${PosterSize.THUMBNAIL.pathSegment}/${lt.titleId}" }
                    if (url != null) {
                        Image(url, lt.name).apply {
                            height = "45px"; width = "30px"
                            style.set("object-fit", "cover")
                        }
                    } else {
                        Span("—")
                    }
                }).setHeader("").setWidth("50px").setFlexGrow(0)

                addColumn({ it.name }).setHeader("Title").setFlexGrow(1)
                addColumn({ it.year?.toString() ?: "" }).setHeader("Year").setWidth("70px").setFlexGrow(0)
                addColumn({ "#${it.discNumber}" }).setHeader("Disc").setWidth("60px").setFlexGrow(0)

                addColumn(ComponentRenderer { lt ->
                    Button("Remove").apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR,
                            ButtonVariant.LUMO_TERTIARY)
                        addClickListener { removeTitle(lt) }
                    }
                }).setHeader("").setWidth("100px").setFlexGrow(0)
            }
            add(linkedGrid)

            // TMDB search section
            add(Span("Search TMDB").apply {
                style.set("font-weight", "bold")
                style.set("margin-top", "1em")
            })

            val searchField = TextField().apply {
                placeholder = "Search for a title..."
                width = "100%"
            }
            val mediaTypeToggle = ComboBox<String>().apply {
                setItems("Movie", "TV")
                value = "Movie"
                width = "8em"
            }
            val searchButton = Button("Search")

            add(HorizontalLayout().apply {
                width = "100%"
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                isSpacing = true
                add(searchField)
                add(mediaTypeToggle)
                add(searchButton)
                expand(searchField)
            })

            searchResultsGrid = Grid<TmdbSearchResult>().apply {
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
                    Button("Add").apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                        addClickListener { addTitleFromTmdb(r) }
                    }
                }).setHeader("").setWidth("80px").setFlexGrow(0)
            }
            add(searchResultsGrid)

            searchField.addKeyDownListener(com.vaadin.flow.component.Key.ENTER, { _ ->
                searchButton.click()
            })

            searchButton.addClickListener {
                val query = searchField.value?.trim() ?: ""
                if (query.isBlank()) return@addClickListener
                val results = if (mediaTypeToggle.value == "TV") {
                    tmdbService.searchTvMultiple(query)
                } else {
                    tmdbService.searchMovieMultiple(query)
                }
                searchResultsGrid.setItems(results)
                searchResultsGrid.isVisible = true
            }
        }
        add(content)

        // Footer buttons
        val hideOriginalCheckbox = Checkbox("Hide original title").apply {
            value = true
        }
        val cancelBtn = Button("Cancel") { close() }
        val notMultiBtn = Button("Not a Multi-Pack").apply {
            addClickListener { markNotMultiPack() }
        }
        val expandBtn = Button("Mark Expanded").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener { markExpanded(hideOriginalCheckbox.value) }
        }

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            width = "100%"
            isSpacing = true
            add(hideOriginalCheckbox)
            expand(hideOriginalCheckbox)
            hideOriginalCheckbox.style.set("margin-right", "auto")
            add(cancelBtn, notMultiBtn, expandBtn)
        }
        footer.element.setAttribute("slot", "footer")
        add(footer)

        refreshLinkedTitles()
    }

    private fun lookupRawTitle(): String {
        val joins = MediaItemTitle.findAll().filter { it.media_item_id == mediaItem.id }
        if (joins.isEmpty()) return "Multi-Pack"
        val title = Title.findById(joins.first().title_id) ?: return "Multi-Pack"
        return title.raw_upc_title ?: title.name
    }

    private fun refreshLinkedTitles() {
        val joins = MediaItemTitle.findAll()
            .filter { it.media_item_id == mediaItem.id }
            .sortedBy { it.disc_number }

        val linked = joins.mapNotNull { join ->
            val title = Title.findById(join.title_id) ?: return@mapNotNull null
            LinkedTitle(
                joinId = join.id!!,
                titleId = title.id!!,
                name = title.name,
                year = title.release_year,
                posterPath = title.poster_path,
                tmdbKey = title.tmdbKey(),
                discNumber = join.disc_number
            )
        }

        linkedGrid.setItems(linked)
        linkedCountLabel.text = "Linked ${linked.size} of ~${mediaItem.title_count} titles"
    }

    private fun addTitleFromTmdb(result: TmdbSearchResult) {
        val tmdbKey = result.tmdbKey() ?: return
        val now = LocalDateTime.now()

        // Dedup: check if a Title with this tmdb_id + media_type already exists
        var title = Title.findAll().firstOrNull { it.tmdbKey() == tmdbKey }
        if (title == null) {
            title = Title(
                name = result.title ?: "Unknown",
                media_type = tmdbKey.typeString,
                tmdb_id = tmdbKey.id,
                release_year = result.releaseYear,
                description = result.overview,
                poster_path = result.posterPath,
                enrichment_status = EnrichmentStatus.ENRICHED.name,
                created_at = now,
                updated_at = now
            )
            title.save()
            SearchIndexService.onTitleChanged(title.id!!)
            WishListService.fulfillMediaWishes(tmdbKey)
        }

        // Check if already linked to this media item
        val alreadyLinked = MediaItemTitle.findAll()
            .any { it.media_item_id == mediaItem.id && it.title_id == title.id }
        if (alreadyLinked) {
            Notification.show("Title already linked", 2000, Notification.Position.BOTTOM_START)
            return
        }

        // Determine next disc number
        val maxDisc = MediaItemTitle.findAll()
            .filter { it.media_item_id == mediaItem.id }
            .maxOfOrNull { it.disc_number } ?: 0

        MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!,
            disc_number = maxDisc + 1
        ).save()

        refreshLinkedTitles()
        Notification.show("Added: ${result.title}", 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun removeTitle(lt: LinkedTitle) {
        // Remove join
        val join = MediaItemTitle.findById(lt.joinId) ?: return
        join.delete()

        // If title has no remaining links and no tmdb_id, delete it too
        if (lt.tmdbKey == null) {
            val hasOtherLinks = MediaItemTitle.findAll().any { it.title_id == lt.titleId }
            if (!hasOtherLinks) {
                Title.findById(lt.titleId)?.delete()
            }
        }

        refreshLinkedTitles()
    }

    private fun markExpanded(hideOriginal: Boolean) {
        val fresh = MediaItem.findById(mediaItem.id!!) ?: run {
            close(); return
        }

        val joins = MediaItemTitle.findAll().filter { it.media_item_id == mediaItem.id }

        // Capture the original placeholder's sort key before retiring it.
        // Used as the base for sequenced sort keys on expansion titles.
        val originalTitle = originalTitleIds.firstNotNullOfOrNull { id -> Title.findById(id) }
        val baseSortKey = originalTitle?.sort_name
            ?: originalTitle?.let { TitleCleanerService.clean(it.name).sortName }
            ?: ""

        // Retire original titles (those present when the dialog opened, tracked
        // in originalTitleIds). Expansion titles added via TMDB search are kept.
        for (join in joins) {
            if (join.title_id !in originalTitleIds) continue
            val title = Title.findById(join.title_id) ?: continue

            // Unlink the original from this media item
            join.delete()

            val hasOtherLinks = MediaItemTitle.findAll().any { it.title_id == title.id }
            if (hideOriginal) {
                title.hidden = true
                title.updated_at = LocalDateTime.now()
                title.save()
                SearchIndexService.onTitleChanged(title.id!!)
            } else if (!hasOtherLinks) {
                // User chose not to hide, and it's orphaned — delete it
                val deletedId = title.id!!
                EnrichmentAttempt.findAll()
                    .filter { it.title_id == title.id }
                    .forEach { it.delete() }
                title.delete()
                SearchIndexService.onTitleDeleted(deletedId)
            }
        }

        // Assign sequenced sort keys to expansion titles (in disc_number order).
        // Uses the original placeholder's sort key as a base so they group together.
        val remainingJoins = MediaItemTitle.findAll()
            .filter { it.media_item_id == mediaItem.id }
            .sortedBy { it.disc_number }
        val now = LocalDateTime.now()
        for ((index, join) in remainingJoins.withIndex()) {
            val title = Title.findById(join.title_id) ?: continue
            if (title.sort_name.isNullOrBlank()) {
                title.sort_name = "%s %02d".format(baseSortKey, index + 1)
                title.updated_at = now
                title.save()
            }
        }

        fresh.expansion_status = ExpansionStatus.EXPANDED.name
        fresh.title_count = remainingJoins.size
        fresh.updated_at = now
        fresh.save()

        close()
        onComplete()
        Notification.show("Multi-pack expanded", 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun markNotMultiPack() {
        val fresh = MediaItem.findById(mediaItem.id!!) ?: run {
            close(); return
        }

        fresh.expansion_status = ExpansionStatus.SINGLE.name
        fresh.title_count = 1
        fresh.updated_at = LocalDateTime.now()
        fresh.save()

        // Set placeholder title back to PENDING so it gets enriched normally
        val joins = MediaItemTitle.findAll().filter { it.media_item_id == mediaItem.id }
        for (join in joins) {
            val title = Title.findById(join.title_id) ?: continue
            if (title.tmdb_id == null && title.enrichment_status == EnrichmentStatus.SKIPPED.name) {
                title.enrichment_status = EnrichmentStatus.PENDING.name
                title.updated_at = LocalDateTime.now()
                title.save()
            }
        }

        close()
        onComplete()
        Notification.show("Marked as single title", 2000, Notification.Position.BOTTOM_START)
    }
}
