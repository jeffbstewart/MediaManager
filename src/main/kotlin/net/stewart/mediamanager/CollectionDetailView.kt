package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.UserTitleFlagService

@Route("content/collection/:collectionId", layout = MainLayout::class)
@PageTitle("Collection")
class CollectionDetailView : VerticalLayout(), BeforeEnterObserver {

    override fun beforeEnter(event: BeforeEnterEvent) {
        val collectionId = event.routeParameters.get("collectionId").orElse(null)?.toLongOrNull()
        if (collectionId == null) {
            event.forwardTo("content/collections")
            return
        }
        val collection = TmdbCollection.findById(collectionId)
        if (collection == null) {
            event.forwardTo("content/collections")
            return
        }
        buildContent(collection)
    }

    private fun buildContent(collection: TmdbCollection) {
        removeAll()
        isPadding = true
        isSpacing = true
        width = "100%"

        val currentUser = AuthService.getCurrentUser()
        val personallyHiddenIds = UserTitleFlagService.getHiddenTitleIds()

        // Load all parts of this collection (ordered by position)
        val parts = TmdbCollectionPart.findAll()
            .filter { it.collection_id == collection.id }
            .sortedBy { it.position }

        // Load owned titles keyed by TMDB movie ID
        val ownedTitlesByTmdbId = Title.findAll()
            .filter { it.tmdb_collection_id == collection.tmdb_collection_id }
            .filter { !it.hidden }
            .filter { it.id !in personallyHiddenIds }
            .filter { currentUser == null || currentUser.canSeeRating(it.content_rating) }
            .associateBy { it.tmdb_id }

        val playableTitleIds = PosterGridHelper.computePlayableTitleIds()
        val progressByTitle = PlaybackProgressService.getProgressByTitle()

        // Header
        add(H2(collection.name).apply {
            style.set("margin", "0")
            style.set("color", "#FFFFFF")
        })

        val ownedCount = parts.count { ownedTitlesByTmdbId.containsKey(it.tmdb_movie_id) }
        add(Span("${ownedCount} of ${parts.size} titles in your collection").apply {
            style.set("color", "rgba(255,255,255,0.5)")
            style.set("font-size", "var(--lumo-font-size-s)")
        })

        // Grid of all parts — owned titles are full cards, unowned are dimmed placeholders
        val grid = PosterGridHelper.createPosterGrid()

        for (part in parts) {
            val ownedTitle = ownedTitlesByTmdbId[part.tmdb_movie_id]
            if (ownedTitle != null) {
                grid.add(PosterGridHelper.buildPosterCard(ownedTitle, playableTitleIds, progressByTitle))
            } else {
                grid.add(buildUnownedCard(part))
            }
        }

        add(grid)
    }

    private fun buildUnownedCard(part: TmdbCollectionPart): Div {
        return Div().apply {
            style.set("text-align", "center")
            style.set("opacity", "0.4")

            val posterContainer = Div().apply {
                style.set("width", "100%")
                style.set("aspect-ratio", "2/3")
                style.set("border-radius", "8px")
                style.set("overflow", "hidden")
                style.set("background", "rgba(255,255,255,0.05)")
                style.set("display", "flex")
                style.set("align-items", "center")
                style.set("justify-content", "center")

                add(Span("Not Owned").apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-xs)")
                })
            }
            add(posterContainer)

            add(Span(part.title).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("display", "block")
            })

            val year = part.release_date?.take(4)
            if (year != null) {
                add(Span(year).apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                })
            }
        }
    }
}
