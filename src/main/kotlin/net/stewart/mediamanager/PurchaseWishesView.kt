package net.stewart.mediamanager

import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.service.MediaWishAggregate
import net.stewart.mediamanager.service.WishListService

@Route(value = "purchase-wishes", layout = MainLayout::class)
@PageTitle("Purchase Wishes")
class PurchaseWishesView : VerticalLayout() {

    init {
        isPadding = true
        isSpacing = true
        width = "100%"
        style.set("max-width", "1200px")
        style.set("margin", "0 auto")

        add(H2("Purchase Wishes"))
        add(Span("Users can heart a movie or TV show from the catalog to indicate they\u2019d like it purchased. This page summarizes those requests, sorted by vote count, so you can see what\u2019s most wanted.").apply {
            style.set("color", "rgba(255,255,255,0.6)")
            style.set("margin-bottom", "var(--lumo-space-m)")
        })

        val aggregates = WishListService.getMediaWishVoteCounts()

        if (aggregates.isEmpty()) {
            add(Span("No active media wishes from any user.").apply {
                style.set("color", "rgba(255,255,255,0.5)")
            })
        } else {
            val grid = Grid<MediaWishAggregate>(MediaWishAggregate::class.java, false).apply {
                width = "100%"
                isAllRowsVisible = true

                addColumn(ComponentRenderer { agg ->
                    val url = agg.tmdbPosterPath?.let { "https://image.tmdb.org/t/p/w185$it" }
                    if (url != null) {
                        Image(url, agg.tmdbTitle).apply {
                            height = "75px"; width = "50px"
                            style.set("object-fit", "cover")
                            style.set("border-radius", "4px")
                        }
                    } else {
                        Span("-")
                    }
                }).setHeader("").setWidth("70px").setFlexGrow(0)

                addColumn { it.tmdbTitle }.setHeader("Title").setFlexGrow(1)
                addColumn { it.tmdbReleaseYear?.toString() ?: "" }.setHeader("Year").setWidth("80px").setFlexGrow(0)
                addColumn { if (it.tmdbMediaType == "TV") "TV" else "Movie" }.setHeader("Type").setWidth("80px").setFlexGrow(0)
                addColumn { it.voteCount.toString() }.setHeader("Votes").setWidth("80px").setFlexGrow(0)
                addColumn { it.voters.joinToString(", ") }.setHeader("Requested by").setFlexGrow(1)
            }
            grid.setItems(aggregates)
            add(grid)
        }
    }
}
