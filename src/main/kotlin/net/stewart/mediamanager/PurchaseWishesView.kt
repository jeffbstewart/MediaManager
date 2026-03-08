package net.stewart.mediamanager

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
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
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.service.MediaWishAggregate
import net.stewart.mediamanager.service.WishListService

@Route(value = "purchase-wishes", layout = MainLayout::class)
@PageTitle("Purchase Wishes")
class PurchaseWishesView : VerticalLayout() {

    private lateinit var grid: Grid<MediaWishAggregate>

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

        grid = Grid(MediaWishAggregate::class.java, false).apply {
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

            addColumn(ComponentRenderer { agg ->
                if (agg.seasonNumber != null) {
                    com.vaadin.flow.component.html.Div().apply {
                        add(Span(agg.tmdbTitle).apply {
                            style.set("display", "block")
                            style.set("font-weight", "500")
                        })
                        add(Span("Season ${agg.seasonNumber}").apply {
                            style.set("display", "block")
                            style.set("color", "rgba(255,255,255,0.5)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        })
                    }
                } else {
                    Span(agg.tmdbTitle).apply {
                        style.set("font-weight", "500")
                    }
                }
            }).setHeader("Title").setFlexGrow(3)
            addColumn { it.tmdbReleaseYear?.toString() ?: "" }.setHeader("Year").setWidth("80px").setFlexGrow(0)
            addColumn { if (it.tmdbMediaType == "TV") "TV" else "Movie" }.setHeader("Type").setWidth("80px").setFlexGrow(0)
            addColumn { it.voteCount.toString() }.setHeader("Votes").setWidth("80px").setFlexGrow(0)
            addColumn { it.voters.joinToString(", ") }.setHeader("Requested by").setFlexGrow(1)

            addColumn(ComponentRenderer { agg -> buildStatusCell(agg) })
                .setHeader("Status").setWidth("200px").setFlexGrow(0)
        }
        add(grid)

        refreshGrid()
    }

    private fun refreshGrid() {
        val aggregates = WishListService.getMediaWishVoteCounts()
        if (aggregates.isEmpty()) {
            grid.isVisible = false
            add(Span("No active media wishes from any user.").apply {
                style.set("color", "rgba(255,255,255,0.5)")
            })
        } else {
            grid.setItems(aggregates)
        }
    }

    private fun buildStatusCell(agg: MediaWishAggregate): Div {
        val status = try {
            agg.acquisitionStatus?.let { AcquisitionStatus.valueOf(it) }
        } catch (_: Exception) { null }

        val (bgColor, textColor, label) = when (status) {
            AcquisitionStatus.OWNED -> Triple("rgba(0,200,83,0.2)", "var(--lumo-success-color)", "Owned")
            AcquisitionStatus.ORDERED -> Triple("rgba(30,136,229,0.2)", "var(--lumo-primary-color)", "Ordered")
            AcquisitionStatus.REJECTED -> Triple("rgba(244,67,54,0.15)", "var(--lumo-error-color)", "Rejected")
            AcquisitionStatus.NOT_AVAILABLE -> Triple("rgba(255,255,255,0.08)", "rgba(255,255,255,0.5)", "Not Available")
            AcquisitionStatus.NEEDS_ASSISTANCE -> Triple("rgba(255,165,0,0.2)", "#FFA500", "Needs Help")
            AcquisitionStatus.UNKNOWN -> Triple("rgba(255,255,255,0.08)", "rgba(255,255,255,0.4)", "Unreviewed")
            null -> Triple("rgba(255,255,255,0.08)", "rgba(255,255,255,0.4)", "Unreviewed")
        }

        return Div().apply {
            style.set("display", "inline-flex")
            style.set("align-items", "center")
            style.set("gap", "var(--lumo-space-xs)")
            style.set("background", bgColor)
            style.set("border-radius", "9999px")
            style.set("padding", "4px 12px")
            style.set("cursor", "pointer")

            add(Span(label).apply {
                style.set("color", textColor)
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("font-weight", "500")
            })

            element.setAttribute("title", "Click to change status")
            element.addEventListener("click") {
                openStatusDialog(agg)
            }
        }
    }

    private fun openStatusDialog(agg: MediaWishAggregate) {
        val dialog = Dialog().apply {
            headerTitle = agg.displayTitle
            width = "350px"
        }

        val statusOptions = listOf(
            AcquisitionStatus.ORDERED,
            AcquisitionStatus.NOT_AVAILABLE,
            AcquisitionStatus.REJECTED,
            AcquisitionStatus.OWNED
        )

        val current = try {
            agg.acquisitionStatus?.let { AcquisitionStatus.valueOf(it) }
        } catch (_: Exception) { null }

        val combo = ComboBox<AcquisitionStatus>("Status").apply {
            setItems(statusOptions)
            setItemLabelGenerator {
                when (it) {
                    AcquisitionStatus.ORDERED -> "Ordered"
                    AcquisitionStatus.NOT_AVAILABLE -> "Not Available"
                    AcquisitionStatus.REJECTED -> "Rejected"
                    AcquisitionStatus.OWNED -> "Owned"
                    else -> it.name
                }
            }
            value = current
            width = "100%"
        }
        dialog.add(combo)

        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            isSpacing = true
        }
        footer.add(Button("Cancel") { dialog.close() })
        footer.add(Button("Save").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener {
                val newStatus = combo.value ?: return@addClickListener
                WishListService.setAcquisitionStatus(agg, newStatus)
                dialog.close()
                Notification.show("${agg.displayTitle} → ${newStatus.name}", 3000,
                    Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                refreshGrid()
            }
        })
        footer.element.setAttribute("slot", "footer")
        dialog.add(footer)
        dialog.open()
    }
}
