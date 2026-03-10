package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.UserTitleFlagService
import net.stewart.mediamanager.service.WishListService

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
        val wishedTmdbKeys = WishListService.getActiveMediaWishes()
            .mapNotNull { it.tmdbKey() }.toMutableSet()

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
                grid.add(buildUnownedCard(part, wishedTmdbKeys))
            }
        }

        add(grid)
    }

    private fun buildUnownedCard(part: TmdbCollectionPart, wishedTmdbKeys: MutableSet<TmdbId>): Div {
        val tmdbKey = TmdbId(part.tmdb_movie_id, MediaType.MOVIE)
        return Div().apply {
            style.set("text-align", "center")
            style.set("opacity", "0.4")
            style.set("position", "relative")

            val posterContainer = Div().apply {
                style.set("width", "100%")
                style.set("aspect-ratio", "2/3")
                style.set("border-radius", "8px")
                style.set("overflow", "hidden")
                style.set("background", "rgba(255,255,255,0.05)")
                style.set("display", "flex")
                style.set("align-items", "center")
                style.set("justify-content", "center")

                if (part.poster_path != null) {
                    add(Image("https://image.tmdb.org/t/p/w185${part.poster_path}",
                        part.title).apply {
                        width = "100%"
                        style.set("height", "100%")
                        style.set("object-fit", "cover")
                    })
                } else {
                    add(Span("Not Owned").apply {
                        style.set("color", "rgba(255,255,255,0.5)")
                        style.set("font-size", "var(--lumo-font-size-xs)")
                    })
                }
            }
            add(posterContainer)

            // Wish list heart button
            val isWished = tmdbKey in wishedTmdbKeys
            val heartBtn = Button(
                if (isWished) VaadinIcon.HEART.create() else VaadinIcon.HEART_O.create()
            ).apply {
                addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY)
                style.set("position", "absolute")
                style.set("top", "4px")
                style.set("right", "4px")
                style.set("background", "rgba(0,0,0,0.6)")
                style.set("border-radius", "50%")
                style.set("min-width", "32px")
                style.set("width", "32px")
                style.set("height", "32px")
                style.set("padding", "0")
                style.set("color", if (isWished) "var(--lumo-error-color)" else "rgba(255,255,255,0.8)")
                element.setAttribute("title", if (isWished) "Remove from wish list" else "Add to wish list")

                addClickListener {
                    val currentlyWished = tmdbKey in wishedTmdbKeys
                    if (currentlyWished) {
                        val wishes = WishListService.getActiveMediaWishes()
                        val wish = wishes.firstOrNull { it.tmdbKey() == tmdbKey }
                        if (wish != null) {
                            WishListService.removeWish(wish.id!!)
                            wishedTmdbKeys.remove(tmdbKey)
                            icon = VaadinIcon.HEART_O.create()
                            style.set("color", "rgba(255,255,255,0.8)")
                            element.setAttribute("title", "Add to wish list")
                            Notification.show("Removed from wish list", 2000, Notification.Position.BOTTOM_START)
                        }
                    } else {
                        val releaseYear = part.release_date?.take(4)?.toIntOrNull()
                        val doAdd = {
                            val result = WishListService.addMediaWish(
                                tmdbKey, part.title, part.poster_path, releaseYear, null
                            )
                            if (result != null) {
                                wishedTmdbKeys.add(tmdbKey)
                                icon = VaadinIcon.HEART.create()
                                style.set("color", "var(--lumo-error-color)")
                                element.setAttribute("title", "Remove from wish list")
                                Notification.show("Added to wish list: ${part.title}", 3000, Notification.Position.BOTTOM_START)
                                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                            }
                        }

                        if (!WishListService.userHasAnyMediaWish()) {
                            val dialog = Dialog().apply {
                                headerTitle = "Heads up"
                                @Suppress("DEPRECATION")
                                isModal = true

                                add(Span("Your media wish list entries are shared with admins to help inform media purchase decisions. Continue?").apply {
                                    style.set("padding", "var(--lumo-space-m)")
                                })

                                val footer = HorizontalLayout().apply {
                                    justifyContentMode = FlexComponent.JustifyContentMode.END
                                    width = "100%"
                                    isSpacing = true
                                }
                                val cancelBtn = Button("Cancel") { close() }
                                val confirmBtn = Button("Got it, add to wish list").apply {
                                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                                    addClickListener {
                                        close()
                                        doAdd()
                                    }
                                }
                                footer.add(cancelBtn, confirmBtn)
                                add(footer)
                            }
                            dialog.open()
                        } else {
                            doAdd()
                        }
                    }
                }
            }
            add(heartBtn)

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
