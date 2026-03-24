package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*

@Route(value = "transcodes/backlog", layout = MainLayout::class)
@PageTitle("Transcode Backlog")
class TranscodeBacklogView : KComposite() {

    private lateinit var backlogGrid: Grid<BacklogRow>
    private lateinit var searchField: TextField

    private val root = ui {
        verticalLayout {
            h2("Rip Suggestions")
            add(Span("Owned titles with no rip on the NAS — sorted by request count, then popularity.").apply {
                style.set("color", "rgba(255,255,255,0.5)")
                style.set("margin-bottom", "var(--lumo-space-m)")
            })

            searchField = textField {
                placeholder = "Search titles..."
                isClearButtonVisible = true
                width = "20em"
                valueChangeMode = ValueChangeMode.LAZY
                valueChangeTimeout = 300
                addValueChangeListener { refreshBacklogGrid() }
            }

            backlogGrid = grid {
                width = "100%"
                height = "600px"
                pageSize = 100

                addColumn(ComponentRenderer { row ->
                    if (row.posterUrl != null) {
                        Image(row.posterUrl, row.titleName).apply {
                            height = "60px"
                            width = "40px"
                            style.set("object-fit", "cover")
                        }
                    } else {
                        Span("\u2014")
                    }
                }).setHeader("Poster").setWidth("80px").setFlexGrow(0)

                addColumn({ it.titleName }).setHeader("Title").setFlexGrow(1)
                addColumn({ it.mediaType }).setHeader("Type").setWidth("80px").setFlexGrow(0)
                addColumn({ it.releaseYear?.toString() ?: "\u2014" }).setHeader("Year").setWidth("80px").setFlexGrow(0)
                addColumn({ it.requestCount }).setHeader("Requests").setWidth("100px").setFlexGrow(0)
                addColumn(ComponentRenderer { row ->
                    HorizontalLayout().apply {
                        isPadding = false
                        isSpacing = false
                        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER

                        val isWished = WishListService.hasActiveTranscodeWish(row.titleId)
                        add(Button(VaadinIcon.ARROW_CIRCLE_UP.create()).apply {
                            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                            style.set("color", if (isWished) "var(--lumo-primary-color)" else "rgba(255,255,255,0.5)")
                            element.setAttribute("title",
                                if (isWished) "Transcoding requested" else "Request transcoding")
                            addClickListener {
                                if (WishListService.hasActiveTranscodeWish(row.titleId)) {
                                    WishListService.removeTranscodeWish(row.titleId)
                                    icon = VaadinIcon.ARROW_CIRCLE_UP.create()
                                    style.set("color", "rgba(255,255,255,0.5)")
                                    element.setAttribute("title", "Request transcoding")
                                    Notification.show("Transcoding request removed", 2000, Notification.Position.BOTTOM_START)
                                } else {
                                    WishListService.addTranscodeWish(row.titleId)
                                    icon = VaadinIcon.ARROW_CIRCLE_UP.create()
                                    style.set("color", "var(--lumo-primary-color)")
                                    element.setAttribute("title", "Transcoding requested")
                                    Notification.show("Transcoding requested", 3000, Notification.Position.BOTTOM_START)
                                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                                }
                            }
                        })
                    }
                }).setHeader("Request").setWidth("100px").setFlexGrow(0)
            }

            // Spacer
            span { style.set("min-height", "6em"); style.set("display", "block") }
        }
    }

    init {
        refreshBacklogGrid()
    }

    private fun refreshBacklogGrid() {
        val titles = Title.findAll()
        val transcodes = Transcode.findAll()
        val wishCounts = WishListService.getRipPriorityCounts()

        // Title IDs that have at least one transcode with a file_path
        val titlesWithTranscodes = transcodes
            .filter { it.file_path != null }
            .map { it.title_id }
            .toSet()

        // Title IDs that have physical media (a media_item_title link)
        val titlesWithMedia = MediaItemTitle.findAll()
            .map { it.title_id }
            .toSet()

        // Enriched, non-hidden titles with physical media but ZERO linked transcodes
        var rows = titles
            .filter {
                it.enrichment_status == EnrichmentStatus.ENRICHED.name &&
                    !it.hidden &&
                    it.id in titlesWithMedia &&
                    it.id !in titlesWithTranscodes
            }
            .map { title ->
                BacklogRow(
                    titleId = title.id!!,
                    titleName = title.name,
                    mediaType = title.media_type,
                    releaseYear = title.release_year,
                    posterUrl = title.posterUrl(PosterSize.THUMBNAIL),
                    requestCount = wishCounts[title.id] ?: 0,
                    popularity = title.popularity ?: 0.0
                )
            }

        // Apply search filter
        val search = searchField.value?.trim()?.lowercase() ?: ""
        if (search.isNotEmpty()) {
            rows = rows.filter { it.titleName.lowercase().contains(search) }
        }

        // Sort: request count DESC, then popularity DESC
        rows = rows.sortedWith(
            compareByDescending<BacklogRow> { it.requestCount }
                .thenByDescending { it.popularity }
        )

        backlogGrid.setItems(rows)
    }
}

private data class BacklogRow(
    val titleId: Long,
    val titleName: String,
    val mediaType: String,
    val releaseYear: Int?,
    val posterUrl: String?,
    val requestCount: Int,
    val popularity: Double
)
