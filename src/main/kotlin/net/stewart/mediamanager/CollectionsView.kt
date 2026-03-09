package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.TmdbCollectionPart
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.UserTitleFlagService

@Route(value = "content/collections", layout = MainLayout::class)
@PageTitle("Collections")
class CollectionsView : VerticalLayout() {

    init {
        isPadding = true
        isSpacing = true
        width = "100%"

        buildContent()
    }

    private fun buildContent() {
        removeAll()

        val currentUser = AuthService.getCurrentUser()
        val personallyHiddenIds = UserTitleFlagService.getHiddenTitleIds()

        // Load all titles indexed by TMDB collection ID
        val allTitles = Title.findAll()
            .filter { !it.hidden }
            .filter { it.id !in personallyHiddenIds }
            .filter { currentUser == null || currentUser.canSeeRating(it.content_rating) }

        val titlesByCollectionId = allTitles
            .filter { it.tmdb_collection_id != null }
            .groupBy { it.tmdb_collection_id!! }

        // Load TMDB collections and parts
        val collections = TmdbCollection.findAll()
            .filter { it.tmdb_collection_id in titlesByCollectionId }
            .sortedBy { it.name.lowercase() }

        val partsByCollection = TmdbCollectionPart.findAll().groupBy { it.collection_id }

        if (collections.isEmpty()) {
            add(Span("No collections found. Collections are automatically created when movies belonging to TMDB franchises are added to the catalog.").apply {
                style.set("color", "rgba(255,255,255,0.5)")
            })
            return
        }

        add(Span("${collections.size} collections").apply {
            style.set("color", "rgba(255,255,255,0.5)")
            style.set("font-size", "var(--lumo-font-size-s)")
        })

        val grid = Div().apply {
            style.set("display", "grid")
            style.set("grid-template-columns", "repeat(auto-fill, minmax(200px, 1fr))")
            style.set("gap", "var(--lumo-space-m)")
            style.set("width", "100%")
        }

        for (collection in collections) {
            val ownedTitles = titlesByCollectionId[collection.tmdb_collection_id] ?: continue
            val totalParts = partsByCollection[collection.id]?.size ?: ownedTitles.size
            val ownedCount = ownedTitles.size

            // Use the first owned title's poster as the collection poster
            val representativePoster = ownedTitles
                .sortedBy { it.release_year ?: 9999 }
                .firstNotNullOfOrNull { it.posterUrl(net.stewart.mediamanager.entity.PosterSize.THUMBNAIL) }

            grid.add(buildCollectionCard(collection, representativePoster, ownedCount, totalParts))
        }

        add(grid)
    }

    private fun buildCollectionCard(
        collection: TmdbCollection,
        posterUrl: String?,
        ownedCount: Int,
        totalParts: Int
    ): Div {
        return Div().apply {
            style.set("cursor", "pointer")
            style.set("text-align", "center")

            element.addEventListener("click") {
                ui.ifPresent { it.navigate("content/collection/${collection.id}") }
            }

            val posterContainer = Div().apply {
                style.set("position", "relative")
                style.set("width", "100%")
                style.set("aspect-ratio", "2/3")
                style.set("border-radius", "8px")
                style.set("overflow", "hidden")
                style.set("background", "rgba(255,255,255,0.05)")

                if (posterUrl != null) {
                    add(Image(posterUrl, collection.name).apply {
                        width = "100%"
                        height = "100%"
                        style.set("object-fit", "cover")
                        style.set("display", "block")
                    })
                }

                // Owned count badge
                add(Div().apply {
                    style.set("position", "absolute")
                    style.set("bottom", "6px")
                    style.set("right", "6px")
                    style.set("background", "rgba(0,0,0,0.7)")
                    style.set("color", "white")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                    style.set("padding", "2px 8px")
                    style.set("border-radius", "9999px")
                    style.set("font-weight", "600")
                    add(Span("$ownedCount / $totalParts"))
                })
            }
            add(posterContainer)

            add(Span(collection.name).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("display", "block")
            })
        }
    }
}
