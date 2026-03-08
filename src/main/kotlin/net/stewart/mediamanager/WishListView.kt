package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.*
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.service.*

@Route(value = "wishlist", layout = MainLayout::class)
@PageTitle("My Wish List")
class WishListView : VerticalLayout() {

    private val currentUser = AuthService.getCurrentUser()
    private val tmdbService = TmdbService()

    private var mediaWishGrid: Div
    private var transcodeWishList: VerticalLayout
    private var mediaEmptyState: Span
    private var transcodeEmptyState: Span

    // Track transcode progress state for live updates
    private var lastTranscoderEvent: TranscoderProgressEvent? = null

    private val transcoderListener: (TranscoderProgressEvent) -> Unit = { event ->
        ui.ifPresent { ui ->
            ui.access {
                lastTranscoderEvent = event
                refreshTranscodeWishes()
            }
        }
    }

    init {
        isPadding = true
        isSpacing = true
        width = "100%"
        style.set("max-width", "1200px")
        style.set("margin", "0 auto")

        add(H2("My Wish List"))

        // --- TMDB Search Widget ---
        add(H3("Search TMDB").apply {
            style.set("margin-bottom", "var(--lumo-space-xs)")
        })

        val searchButton = Button("Search", VaadinIcon.SEARCH.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        }
        val searchField = TextField().apply {
            placeholder = "Search for a movie or TV show..."
            width = "100%"
            element.addEventListener("keypress") {
                searchButton.click()
            }.filter = "event.key == 'Enter'"
        }
        val searchBar = HorizontalLayout(searchField, searchButton).apply {
            width = "100%"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
            expand(searchField)
        }
        add(searchBar)

        val searchResultsRow = HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            width = "100%"
            style.set("overflow-x", "auto")
            style.set("padding-bottom", "var(--lumo-space-s)")
            isVisible = false
        }
        add(searchResultsRow)

        searchButton.addClickListener {
            val query = searchField.value?.trim() ?: ""
            if (query.isBlank()) return@addClickListener

            val movieResults = tmdbService.searchMovieMultiple(query, 5)
            val tvResults = tmdbService.searchTvMultiple(query, 5)
            val allResults = (movieResults + tvResults).sortedByDescending { it.popularity ?: 0.0 }.take(10)

            searchResultsRow.removeAll()
            if (allResults.isEmpty()) {
                searchResultsRow.add(Span("No results found.").apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                })
            } else {
                val wishedTmdbKeys =
                    WishListService.getActiveMediaWishes()
                        .mapNotNull { it.tmdbKey() }
                        .toSet()

                for (result in allResults) {
                    searchResultsRow.add(buildSearchResultCard(result, wishedTmdbKeys))
                }
            }
            searchResultsRow.isVisible = true
        }

        // --- Media Wish List ---
        add(H3("Media Wishes").apply {
            style.set("margin-top", "var(--lumo-space-l)")
            style.set("margin-bottom", "var(--lumo-space-xs)")
        })

        mediaEmptyState = Span("No media wishes yet — search above or browse actor filmographies.").apply {
            style.set("color", "rgba(255,255,255,0.5)")
        }
        add(mediaEmptyState)

        mediaWishGrid = Div().apply {
            style.set("display", "grid")
            style.set("grid-template-columns", "repeat(auto-fill, minmax(150px, 1fr))")
            style.set("gap", "var(--lumo-space-m)")
            style.set("width", "100%")
        }
        add(mediaWishGrid)

        // --- Transcode Wish List ---
        add(H3("Transcode Wishes").apply {
            style.set("margin-top", "var(--lumo-space-l)")
            style.set("margin-bottom", "var(--lumo-space-xs)")
        })

        transcodeEmptyState = Span("No transcode wishes yet — look for the star button on titles waiting to be transcoded.").apply {
            style.set("color", "rgba(255,255,255,0.5)")
        }
        add(transcodeEmptyState)

        transcodeWishList = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            width = "100%"
        }
        add(transcodeWishList)

        refreshMediaWishes()
        refreshTranscodeWishes()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        Broadcaster.registerTranscoderListener(transcoderListener)
    }

    override fun onDetach(detachEvent: DetachEvent) {
        Broadcaster.unregisterTranscoderListener(transcoderListener)
        super.onDetach(detachEvent)
    }

    private fun buildSearchResultCard(result: TmdbSearchResult, wishedTmdbKeys: Set<TmdbId>): VerticalLayout {
        val tmdbKey = result.tmdbKey() ?: return VerticalLayout()
        val alreadyWished = tmdbKey in wishedTmdbKeys

        return VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "150px"
            style.set("min-width", "150px")
            style.set("align-items", "center")
            style.set("position", "relative")

            // Poster
            if (result.posterPath != null) {
                add(Image("https://image.tmdb.org/t/p/w185${result.posterPath}",
                    result.title ?: "").apply {
                    width = "130px"
                    height = "195px"
                    style.set("border-radius", "8px")
                    style.set("object-fit", "cover")
                })
            } else {
                add(Div().apply {
                    style.set("width", "130px")
                    style.set("height", "195px")
                    style.set("border-radius", "8px")
                    style.set("background", "rgba(255,255,255,0.1)")
                    style.set("display", "flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    add(Span((result.title ?: "?").take(1)).apply {
                        style.set("color", "rgba(255,255,255,0.4)")
                        style.set("font-size", "var(--lumo-font-size-xl)")
                    })
                })
            }

            // Title
            add(Span(result.title ?: "Unknown").apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("text-align", "center")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("max-width", "150px")
            })

            // Year + type
            val metaParts = mutableListOf<String>()
            if (result.releaseYear != null) metaParts.add(result.releaseYear.toString())
            metaParts.add(if (result.mediaType == "TV") "TV" else "Film")
            add(Span(metaParts.joinToString(" \u00b7 ")).apply {
                style.set("color", "rgba(255,255,255,0.5)")
                style.set("font-size", "var(--lumo-font-size-xxs)")
            })

            // Add/already wished
            if (alreadyWished) {
                add(Span("\u2665 Wished").apply {
                    style.set("color", "var(--lumo-primary-color)")
                    style.set("font-size", "var(--lumo-font-size-xs)")
                    style.set("margin-top", "var(--lumo-space-xs)")
                })
            } else {
                val wishedLabel = Span("\u2665 Wished").apply {
                    style.set("color", "var(--lumo-primary-color)")
                    style.set("font-size", "var(--lumo-font-size-xs)")
                    isVisible = false
                }
                val addBtn = Button("Add to Wish List", VaadinIcon.HEART.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                    style.set("margin-top", "var(--lumo-space-xs)")
                    addClickListener {
                        addMediaWishWithInterstitial(
                            tmdbKey, result.title ?: "Unknown",
                            result.posterPath, result.releaseYear, result.popularity
                        )
                        isVisible = false
                        wishedLabel.isVisible = true
                    }
                }
                add(addBtn, wishedLabel)
            }
        }
    }

    private fun addMediaWishWithInterstitial(
        tmdbId: TmdbId, title: String,
        posterPath: String?, releaseYear: Int?, popularity: Double?
    ) {
        if (currentUser == null) return

        // First-wish interstitial check
        if (!WishListService.userHasAnyMediaWish()) {
            val dialog = com.vaadin.flow.component.dialog.Dialog().apply {
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
                        doAddMediaWish(tmdbId, title, posterPath, releaseYear, popularity)
                    }
                }
                footer.add(cancelBtn, confirmBtn)
                footer.element.setAttribute("slot", "footer")
                add(footer)
            }
            dialog.open()
        } else {
            doAddMediaWish(tmdbId, title, posterPath, releaseYear, popularity)
        }
    }

    private fun doAddMediaWish(
        tmdbId: TmdbId, title: String,
        posterPath: String?, releaseYear: Int?, popularity: Double?
    ) {
        val wish = WishListService.addMediaWish(tmdbId, title, posterPath, releaseYear, popularity)
        if (wish != null) {
            Notification.show("Added to wish list: $title", 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
            refreshMediaWishes()
        } else {
            Notification.show("Already on your wish list", 2000, Notification.Position.BOTTOM_START)
        }
    }

    private fun refreshMediaWishes() {
        val wishes = WishListService.getVisibleMediaWishes()

        mediaWishGrid.removeAll()
        mediaEmptyState.isVisible = wishes.isEmpty()
        mediaWishGrid.isVisible = wishes.isNotEmpty()

        for (wish in wishes) {
            mediaWishGrid.add(buildMediaWishCard(wish))
        }
    }

    private fun buildMediaWishCard(wish: WishListItem): VerticalLayout {
        val isFulfilled = wish.status == WishStatus.FULFILLED.name
        val acqStatus = WishListService.getAcquisitionStatus(wish)

        return VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            style.set("align-items", "center")
            style.set("position", "relative")

            // Poster container with cancel overlay
            val titleId = lookupTitleId(wish)
            val posterContainer = Div().apply {
                style.set("position", "relative")
                style.set("width", "130px")
                style.set("height", "195px")
                style.set("flex-shrink", "0")
                if (isFulfilled && titleId != null) style.set("cursor", "pointer")

                if (wish.tmdb_poster_path != null) {
                    add(Image("https://image.tmdb.org/t/p/w185${wish.tmdb_poster_path}",
                        wish.tmdb_title ?: "").apply {
                        width = "130px"
                        height = "195px"
                        style.set("border-radius", "8px")
                        style.set("object-fit", "cover")
                        if (isFulfilled) style.set("opacity", "0.6")
                    })
                } else {
                    add(Div().apply {
                        style.set("width", "130px")
                        style.set("height", "195px")
                        style.set("border-radius", "8px")
                        style.set("background", "rgba(255,255,255,0.1)")
                        style.set("display", "flex")
                        style.set("align-items", "center")
                        style.set("justify-content", "center")
                        add(Span((wish.tmdb_title ?: "?").take(1)).apply {
                            style.set("color", "rgba(255,255,255,0.4)")
                            style.set("font-size", "var(--lumo-font-size-xl)")
                        })
                    })
                }

                // Fulfilled: clicking poster navigates to title detail and dismisses
                if (isFulfilled && titleId != null) {
                    element.addEventListener("click") {
                        WishListService.dismissWish(wish.id!!)
                        ui.ifPresent { it.navigate("title/$titleId") }
                    }
                }

                // Cancel button overlaid on poster (active wishes only)
                if (!isFulfilled) {
                    add(Button(VaadinIcon.CLOSE_SMALL.create()).apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON)
                        style.set("position", "absolute")
                        style.set("top", "4px")
                        style.set("right", "4px")
                        style.set("min-width", "24px")
                        style.set("width", "24px")
                        style.set("height", "24px")
                        style.set("padding", "0")
                        style.set("border-radius", "50%")
                        style.set("background", "rgba(0,0,0,0.7)")
                        style.set("color", "rgba(255,255,255,0.8)")
                        style.set("cursor", "pointer")
                        element.setAttribute("title", "Cancel wish")
                        addClickListener {
                            WishListService.cancelWish(wish.id!!)
                            refreshMediaWishes()
                            Notification.show("Wish cancelled", 2000, Notification.Position.BOTTOM_START)
                        }
                    })
                }
            }
            add(posterContainer)

            // Title (as link for fulfilled wishes so clicking navigates to title detail)
            if (isFulfilled && titleId != null) {
                add(Anchor("title/$titleId", wish.tmdb_title ?: "Unknown").apply {
                    style.set("color", "#FFFFFF")
                    style.set("font-size", "var(--lumo-font-size-xs)")
                    style.set("text-align", "center")
                    style.set("margin-top", "var(--lumo-space-xs)")
                    style.set("text-decoration", "none")
                    // Clicking through to title detail dismisses the fulfilled wish
                    element.addEventListener("click") {
                        WishListService.dismissWish(wish.id!!)
                    }
                })
            } else {
                add(Span(wish.tmdb_title ?: "Unknown").apply {
                    style.set("color", "#FFFFFF")
                    style.set("font-size", "var(--lumo-font-size-xs)")
                    style.set("text-align", "center")
                    style.set("margin-top", "var(--lumo-space-xs)")
                    style.set("overflow", "hidden")
                    style.set("text-overflow", "ellipsis")
                    style.set("white-space", "nowrap")
                    style.set("max-width", "150px")
                })
            }

            // Year + type + season
            val metaParts = mutableListOf<String>()
            if (wish.tmdb_release_year != null) metaParts.add(wish.tmdb_release_year.toString())
            if (wish.tmdb_media_type != null) metaParts.add(if (wish.tmdb_media_type == "TV") "TV" else "Film")
            if (metaParts.isNotEmpty()) {
                add(Span(metaParts.joinToString(" \u00b7 ")).apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                })
            }
            if (wish.season_number != null) {
                add(Span("Season ${wish.season_number}").apply {
                    style.set("color", "var(--lumo-primary-text-color)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                    style.set("font-weight", "500")
                })
            }

            // Status badge or action button
            if (isFulfilled) {
                // Check if there's actually a playable transcode
                val nasRoot = TranscoderAgent.getNasRoot()
                val isPlayable = if (titleId != null) {
                    val transcodes = Transcode.findAll().filter {
                        it.title_id == titleId && it.file_path != null
                    }
                    transcodes.isNotEmpty() && transcodes.any { tc ->
                        if (TranscoderAgent.needsTranscoding(tc.file_path!!)) {
                            nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, tc.file_path!!)
                        } else true
                    }
                } else false

                if (isPlayable) {
                    add(Span("Ready to watch!").apply {
                        style.set("color", "var(--lumo-success-color)")
                        style.set("font-size", "var(--lumo-font-size-xs)")
                        style.set("font-weight", "600")
                        style.set("margin-top", "var(--lumo-space-xs)")
                    })
                } else {
                    add(Span("Added to collection").apply {
                        style.set("color", "var(--lumo-primary-color)")
                        style.set("font-size", "var(--lumo-font-size-xs)")
                        style.set("font-weight", "600")
                        style.set("margin-top", "var(--lumo-space-xs)")
                    })
                }
                add(Button("Dismiss", VaadinIcon.CHECK.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                    addClickListener {
                        WishListService.dismissWish(wish.id!!)
                        refreshMediaWishes()
                    }
                })
            } else {
                // Acquisition status badge
                when (acqStatus) {
                    AcquisitionStatus.ORDERED.name -> {
                        add(Span("Ordered!").apply {
                            style.set("color", "var(--lumo-primary-color)")
                            style.set("font-size", "var(--lumo-font-size-xs)")
                            style.set("font-weight", "600")
                            style.set("margin-top", "var(--lumo-space-xs)")
                        })
                    }
                    AcquisitionStatus.REJECTED.name -> {
                        add(Span("Won't be purchased").apply {
                            style.set("color", "var(--lumo-error-text-color)")
                            style.set("font-size", "var(--lumo-font-size-xs)")
                            style.set("margin-top", "var(--lumo-space-xs)")
                        })
                    }
                    AcquisitionStatus.NOT_AVAILABLE.name -> {
                        add(Span("Not yet available").apply {
                            style.set("color", "rgba(255,255,255,0.5)")
                            style.set("font-size", "var(--lumo-font-size-xs)")
                            style.set("font-style", "italic")
                            style.set("margin-top", "var(--lumo-space-xs)")
                        })
                    }
                }

            }
        }
    }

    /** Look up the internal title ID for a wish's TMDB reference. */
    private fun lookupTitleId(wish: WishListItem): Long? {
        val tmdbKey = wish.tmdbKey() ?: return null
        return Title.findAll().firstOrNull {
            it.tmdb_id == tmdbKey.id && it.media_type == tmdbKey.typeString
        }?.id
    }

    private fun refreshTranscodeWishes() {
        val wishes = WishListService.getActiveTranscodeWishes()

        transcodeWishList.removeAll()
        transcodeEmptyState.isVisible = wishes.isEmpty()
        transcodeWishList.isVisible = wishes.isNotEmpty()

        // Load title data for each wish
        val titles = Title.findAll().associateBy { it.id }
        val transcodes = Transcode.findAll()
        val nasRoot = TranscoderAgent.getNasRoot()

        for (wish in wishes) {
            val title = titles[wish.title_id] ?: continue
            transcodeWishList.add(buildTranscodeWishRow(wish, title, transcodes, nasRoot))
        }
    }

    private fun buildTranscodeWishRow(
        wish: WishListItem, title: Title,
        transcodes: List<Transcode>, nasRoot: String?
    ): HorizontalLayout {
        return HorizontalLayout().apply {
            width = "100%"
            isPadding = true
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            style.set("background", "rgba(255,255,255,0.05)")
            style.set("border-radius", "8px")
            style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")

            // Poster
            val posterUrl = title.posterUrl(PosterSize.THUMBNAIL)
            if (posterUrl != null) {
                add(Image(posterUrl, title.name).apply {
                    width = "40px"
                    height = "60px"
                    style.set("border-radius", "4px")
                    style.set("object-fit", "cover")
                    style.set("flex-shrink", "0")
                })
            }

            // Title name as link
            add(Anchor("title/${title.id}", title.name).apply {
                style.set("color", "#FFFFFF")
                style.set("font-weight", "500")
                style.set("flex-grow", "1")
                style.set("text-decoration", "none")
            })

            // Live transcode status
            val titleTranscodes = transcodes.filter {
                it.title_id == title.id && it.file_path != null && TranscoderAgent.needsTranscoding(it.file_path!!)
            }
            val event = lastTranscoderEvent
            val isTranscoding = event?.status == TranscoderStatus.TRANSCODING && event.currentFile != null

            // Check if any of this title's files are currently being transcoded
            var showingProgress = false
            if (isTranscoding && nasRoot != null) {
                for (tc in titleTranscodes) {
                    val relativePath = try {
                        java.io.File(nasRoot).toPath().relativize(java.io.File(tc.file_path!!).toPath()).toString()
                    } catch (_: Exception) { null }
                    if (relativePath != null && event.currentFile == relativePath) {
                        // This title is currently being transcoded
                        add(VerticalLayout().apply {
                            isPadding = false
                            isSpacing = false
                            width = "200px"
                            style.set("flex-shrink", "0")
                            add(ProgressBar().apply {
                                min = 0.0; max = 100.0
                                value = event.currentPercent.toDouble()
                                width = "100%"
                            })
                            add(Span("Transcoding... ${event.currentPercent}%").apply {
                                style.set("font-size", "var(--lumo-font-size-xs)")
                                style.set("color", "var(--lumo-primary-color)")
                            })
                        })
                        showingProgress = true
                        break
                    }
                }
            }

            if (!showingProgress) {
                // Check if any have completed transcoding
                val allTranscoded = nasRoot != null && titleTranscodes.isNotEmpty() &&
                    titleTranscodes.all { TranscoderAgent.isTranscoded(nasRoot, it.file_path!!) }
                if (allTranscoded && titleTranscodes.isNotEmpty()) {
                    add(Span("Ready to watch").apply {
                        style.set("color", "var(--lumo-success-color)")
                        style.set("font-size", "var(--lumo-font-size-s)")
                        style.set("flex-shrink", "0")
                    })
                } else {
                    add(Span("Queued").apply {
                        style.set("color", "rgba(255,255,255,0.5)")
                        style.set("font-size", "var(--lumo-font-size-s)")
                        style.set("flex-shrink", "0")
                    })
                }
            }

            // Remove button
            add(Button(VaadinIcon.CLOSE_SMALL.create()).apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR)
                style.set("flex-shrink", "0")
                addClickListener {
                    WishListService.removeWish(wish.id!!)
                    refreshTranscodeWishes()
                    Notification.show("Removed from wish list", 2000, Notification.Position.BOTTOM_START)
                }
            })
        }
    }
}
