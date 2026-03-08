package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
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
import com.vaadin.flow.router.Route
import com.vaadin.flow.router.PageTitle
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.ContinueWatchingItem
import net.stewart.mediamanager.service.MissingSeasonService
import net.stewart.mediamanager.service.MissingSeasonSummary
import net.stewart.mediamanager.service.PlaybackProgressService
import net.stewart.mediamanager.service.WishListService

@Route(value = "", layout = MainLayout::class)
@PageTitle("Media Manager")
class MainView : KComposite() {

    private lateinit var continueWatchingContainer: VerticalLayout
    private lateinit var recentlyWatchedContainer: VerticalLayout
    private lateinit var recentlyAddedContainer: VerticalLayout
    private lateinit var missingSeasonsContainer: VerticalLayout
    private lateinit var adminSection: VerticalLayout

    private val root = ui {
        verticalLayout {
            // Continue Watching section (dynamically populated)
            continueWatchingContainer = verticalLayout {
                isPadding = false
                isSpacing = false
            }

            // Recently Added section (dynamically populated)
            recentlyAddedContainer = verticalLayout {
                isPadding = false
                isSpacing = false
            }

            // Recently Watched section (dynamically populated)
            recentlyWatchedContainer = verticalLayout {
                isPadding = false
                isSpacing = false
            }

            // Missing Seasons section (dynamically populated)
            missingSeasonsContainer = verticalLayout {
                isPadding = false
                isSpacing = false
            }

            h2("Media Manager")
            add(Paragraph(
                "Your personal media library \u2014 browse, search, and stream movies and TV shows " +
                "from your physical disc collection (DVD, Blu-ray, UHD), all from your browser."
            ))

            // --- Browsing & Watching ---
            h3("Browsing & Watching")
            add(Paragraph(
                "Open the Catalog from the sidebar to see every title in the collection. " +
                "Each title shows its poster, release year, content rating, and format. " +
                "Click any title to open its detail page, where you can read the description, " +
                "see the cast, and \u2014 if the file has been transcoded for browser playback \u2014 " +
                "hit the green play button to start watching right in your browser."
            ))
            add(Paragraph(
                "Your playback position is saved automatically. If you stop partway through, " +
                "the title will appear in the Continue Watching row at the top of this page " +
                "with a progress bar showing where you left off. Click the poster or the play " +
                "button to resume. To clear a resume marker, click the \u00D7 on the poster card here, " +
                "or choose \"Start Over\" when prompted in the player."
            ))
            add(Paragraph(
                "TV series show an episode grid on the detail page. Each episode has its own " +
                "play button and resume indicator, so you can pick up any episode where you left off."
            ))

            // --- Searching ---
            h3("Searching")
            add(Paragraph(
                "Use the search box in the top navigation bar to quickly find any title or actor. " +
                "Start typing and matching results appear in a dropdown \u2014 titles show their poster, " +
                "actors show their headshot. Select a result to jump straight to its detail page."
            ))
            add(Paragraph("The search supports several advanced features:"))
            add(UnorderedList(
                ListItem("Type multiple words and all of them must appear (e.g. dark knight returns only titles containing both words)"),
                ListItem("Wrap words in quotes for an exact phrase match (e.g. \"dark knight\")"),
                ListItem("Prefix a word with a minus sign to exclude it (e.g. batman -lego)"),
                ListItem("Filter by tag or genre with the tag: prefix (e.g. tag:action or tag:favorites)")
            ))
            add(Paragraph(
                "The Catalog page has its own search bar with the same syntax, plus additional " +
                "filters: a multi-select tag filter (pick one or more tags to narrow results), " +
                "and a status filter to show titles needing attention."
            ))

            // --- Personalizing ---
            h3("Personalizing Your Library")
            add(UnorderedList(
                ListItem("Star a title on its detail page to mark it as a favorite \u2014 starred titles show a gold star in the catalog for easy scanning"),
                ListItem("Hide a title you're not interested in with \"Hide for me\" on its detail page \u2014 it disappears from your catalog and search results (only for you, not other users)"),
                ListItem("Your account may have a content rating ceiling set by an admin (e.g. PG-13), which automatically filters out titles above that rating")
            ))

            // --- Wish Lists ---
            h3("Wish Lists")
            add(Paragraph(
                "Open My Wish List from the sidebar to manage two kinds of requests:"
            ))
            add(UnorderedList(
                ListItem("Media wishes \u2014 movies or shows you'd like purchased as physical media. Search TMDB, add titles to your list, and admins can see what's most requested."),
                ListItem("Transcode wishes \u2014 titles you own that aren't yet playable in the browser. Request a priority transcode from the title detail page (the up-arrow icon next to the play button) and it moves ahead in the transcoding queue.")
            ))

            // --- Admin Section (conditionally visible) ---
            adminSection = verticalLayout {
                isPadding = false
                isSpacing = true
                isVisible = false

                add(Hr())
                h3("Administration (Manage)")
                add(Paragraph(
                    "As an admin, the Manage section in the sidebar gives you tools to " +
                    "build and maintain the catalog:"
                ))
                add(UnorderedList(
                    ListItem("Add Title \u2014 search TMDB and add a title directly to the catalog without scanning a barcode (useful for discs with no case)"),
                    ListItem("Scan New Purchase \u2014 scan or type a UPC barcode to add a new disc; the system looks up the product and creates a catalog entry automatically"),
                    ListItem("Amazon Order Import \u2014 upload your Amazon purchase history to bulk-fill purchase prices and dates"),
                    ListItem("Expand Multi-Packs \u2014 split box sets, double features, and trilogies into their individual titles"),
                    ListItem("Valuation \u2014 track what you paid for each disc for insurance inventory; link Amazon orders with one click"),
                    ListItem("User Wishes \u2014 see aggregated purchase requests from all users, sorted by vote count"),
                    ListItem("Tags \u2014 create and manage colored tags that can be applied to titles for organization"),
                    ListItem("Transcodes \u2014 monitor the background transcoder, match NAS files to catalog titles, review the backlog of un-transcoded titles, and manage linked transcodes"),
                    ListItem("Users \u2014 create accounts, set access levels, reset passwords, and configure content rating ceilings"),
                    ListItem("Settings \u2014 configure the NAS path, FFmpeg path, and Roku API key")
                ))
            }
        }
    }

    init {
        buildContinueWatching()
        buildRecentlyAdded()
        buildRecentlyWatched()
        buildMissingSeasons()
        val user = AuthService.getCurrentUser()
        adminSection.isVisible = user?.isAdmin() == true
    }

    private fun buildContinueWatching() {
        continueWatchingContainer.removeAll()
        val items = PlaybackProgressService.getContinueWatching(5)
        if (items.isEmpty()) return

        continueWatchingContainer.add(H3("Continue Watching").apply {
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        val scrollRow = HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            width = "100%"
            style.set("overflow-x", "auto")
            style.set("padding-bottom", "var(--lumo-space-s)")
        }

        for (item in items) {
            scrollRow.add(buildContinueWatchingCard(item))
        }

        continueWatchingContainer.add(scrollRow)
    }

    private fun buildRecentlyAdded() {
        recentlyAddedContainer.removeAll()
        val items = PlaybackProgressService.getRecentlyAdded(10)
        if (items.isEmpty()) return

        recentlyAddedContainer.add(H3("Recently Added").apply {
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        val scrollRow = HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            width = "100%"
            style.set("overflow-x", "auto")
            style.set("padding-bottom", "var(--lumo-space-s)")
        }

        for ((title, _) in items) {
            scrollRow.add(buildPosterCard(title))
        }

        recentlyAddedContainer.add(scrollRow)
    }

    private fun buildRecentlyWatched() {
        recentlyWatchedContainer.removeAll()
        val titles = PlaybackProgressService.getRecentlyWatched(10)
        if (titles.isEmpty()) return

        recentlyWatchedContainer.add(H3("Recently Watched").apply {
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        val scrollRow = HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            width = "100%"
            style.set("overflow-x", "auto")
            style.set("padding-bottom", "var(--lumo-space-s)")
        }

        for (title in titles) {
            scrollRow.add(buildPosterCard(title))
        }

        recentlyWatchedContainer.add(scrollRow)
    }

    private fun buildPosterCard(title: Title): VerticalLayout {
        return VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "120px"
            style.set("min-width", "120px")
            style.set("cursor", "pointer")

            element.addEventListener("click") {
                ui.ifPresent { it.navigate("title/${title.id}") }
            }

            val posterUrl = title.posterUrl(PosterSize.THUMBNAIL)
            if (posterUrl != null) {
                add(Image(posterUrl, title.name).apply {
                    width = "120px"
                    height = "180px"
                    style.set("border-radius", "6px")
                    style.set("object-fit", "cover")
                })
            } else {
                add(Div().apply {
                    style.set("width", "120px")
                    style.set("height", "180px")
                    style.set("border-radius", "6px")
                    style.set("background", "rgba(255,255,255,0.05)")
                })
            }

            add(Span(title.name).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("max-width", "120px")
                style.set("display", "block")
            })

            if (title.release_year != null) {
                add(Span(title.release_year.toString()).apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                })
            }
        }
    }

    private fun buildMissingSeasons() {
        missingSeasonsContainer.removeAll()
        val user = AuthService.getCurrentUser() ?: return
        val summaries = MissingSeasonService.getMissingSeasonsForUser(user.id!!)
        if (summaries.isEmpty()) return

        missingSeasonsContainer.add(H3("New Seasons Available").apply {
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        val scrollRow = HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            width = "100%"
            style.set("overflow-x", "auto")
            style.set("padding-bottom", "var(--lumo-space-s)")
        }

        for (summary in summaries) {
            scrollRow.add(buildMissingSeasonCard(summary))
        }

        missingSeasonsContainer.add(scrollRow)
    }

    private fun buildMissingSeasonCard(summary: MissingSeasonSummary): VerticalLayout {
        return VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "160px"
            style.set("min-width", "160px")
            style.set("cursor", "pointer")
            style.set("position", "relative")

            // Poster
            val posterContainer = Div().apply {
                style.set("position", "relative")
                style.set("width", "160px")
                style.set("height", "240px")
                style.set("border-radius", "8px")
                style.set("overflow", "hidden")
                style.set("background", "rgba(255,255,255,0.05)")

                if (summary.posterPath != null) {
                    add(Image("/posters/w185/${summary.titleId}", summary.titleName).apply {
                        width = "100%"
                        height = "100%"
                        style.set("object-fit", "cover")
                    })
                }

                // Dismiss button (X) at top-right
                add(Button(VaadinIcon.CLOSE_SMALL.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON)
                    style.set("position", "absolute")
                    style.set("top", "4px")
                    style.set("right", "4px")
                    style.set("color", "rgba(255,255,255,0.7)")
                    style.set("background", "rgba(0,0,0,0.5)")
                    style.set("border-radius", "50%")
                    style.set("min-width", "24px")
                    style.set("width", "24px")
                    style.set("height", "24px")
                    element.setAttribute("title", "Dismiss all missing seasons for this title")
                    addClickListener { event ->
                        event.source.isEnabled = false
                        val user = AuthService.getCurrentUser()
                        if (user != null) {
                            MissingSeasonService.dismissAllForTitle(user.id!!, summary.titleId)
                        }
                        buildMissingSeasons()
                    }
                })
            }
            add(posterContainer)

            // Title name
            add(Span(summary.titleName).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("max-width", "160px")
                style.set("display", "block")
            })

            // Missing season labels
            val seasonLabels = summary.missingSeasons.map { "S${it.season_number}" }
            add(Span(seasonLabels.joinToString(", ")).apply {
                style.set("color", "rgba(255,255,255,0.6)")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("max-width", "160px")
                style.set("display", "block")
            })

            // "Add to Wish List" button for the first missing season
            val firstMissing = summary.missingSeasons.first()
            val wishBtn = Button("Wish S${firstMissing.season_number}",
                VaadinIcon.HEART.create()
            ).apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                style.set("color", "var(--lumo-primary-text-color)")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("padding", "0")
                style.set("min-width", "unset")
                addClickListener { event ->
                    event.source.isEnabled = false
                    val tmdbKey = TmdbId.of(summary.tmdbId, summary.tmdbMediaType)
                    if (tmdbKey != null) {
                        WishListService.addSeasonWish(
                            tmdbKey, summary.titleName,
                            summary.posterPath, null, null,
                            firstMissing.season_number
                        )
                        // Dismiss this season so the notification goes away
                        val user = AuthService.getCurrentUser()
                        if (user != null) {
                            MissingSeasonService.dismiss(user.id!!, summary.titleId, firstMissing.season_number)
                        }
                        Notification.show(
                            "${summary.titleName} Season ${firstMissing.season_number} added to wish list",
                            3000, Notification.Position.BOTTOM_START
                        ).addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                        buildMissingSeasons()
                    }
                }
            }
            add(wishBtn)

            // Click card → navigate to title detail (but not when clicking buttons)
            addAttachListener {
                // Use JS to stop button clicks from propagating to the card's click handler
                element.executeJs(
                    "this.querySelectorAll('vaadin-button').forEach(function(b){" +
                    "b.addEventListener('click',function(e){e.stopPropagation()})});"
                )
            }
            element.addEventListener("click") {
                ui.ifPresent { it.navigate("title/${summary.titleId}") }
            }
        }
    }

    private fun buildContinueWatchingCard(item: ContinueWatchingItem): VerticalLayout {
        return VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "160px"
            style.set("min-width", "160px")
            style.set("cursor", "pointer")
            style.set("position", "relative")

            // Poster with progress bar overlay
            val posterContainer = Div().apply {
                style.set("position", "relative")
                style.set("width", "160px")
                style.set("height", "240px")
                style.set("border-radius", "8px")
                style.set("overflow", "hidden")
                style.set("background", "rgba(255,255,255,0.05)")

                if (item.posterUrl != null) {
                    add(Image(item.posterUrl, item.titleName).apply {
                        width = "100%"
                        height = "100%"
                        style.set("object-fit", "cover")
                    })
                }

                // Progress bar at bottom of poster
                add(Div().apply {
                    style.set("position", "absolute")
                    style.set("bottom", "0")
                    style.set("left", "0")
                    style.set("width", "100%")
                    style.set("height", "4px")
                    style.set("background", "rgba(0,0,0,0.5)")

                    add(Div().apply {
                        val pct = (item.progressFraction * 100).toInt().coerceIn(0, 100)
                        style.set("width", "${pct}%")
                        style.set("height", "100%")
                        style.set("background", "var(--lumo-primary-color)")
                    })
                })

                // Dismiss button (X) at top-right
                add(Button(VaadinIcon.CLOSE_SMALL.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON)
                    style.set("position", "absolute")
                    style.set("top", "4px")
                    style.set("right", "4px")
                    style.set("color", "rgba(255,255,255,0.7)")
                    style.set("background", "rgba(0,0,0,0.5)")
                    style.set("border-radius", "50%")
                    style.set("min-width", "24px")
                    style.set("width", "24px")
                    style.set("height", "24px")
                    element.setAttribute("title", "Remove from Continue Watching")
                    addClickListener { event ->
                        event.source.isEnabled = false
                        PlaybackProgressService.clearProgress(item.transcodeId)
                        buildContinueWatching()
                    }
                })

                // Play overlay button (center)
                add(Button(VaadinIcon.PLAY.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON)
                    style.set("position", "absolute")
                    style.set("top", "50%")
                    style.set("left", "50%")
                    style.set("transform", "translate(-50%, -50%)")
                    style.set("color", "#fff")
                    style.set("background", "rgba(0,0,0,0.6)")
                    style.set("border-radius", "50%")
                    style.set("width", "48px")
                    style.set("height", "48px")
                    style.set("font-size", "24px")
                    element.setAttribute("title", "Play")
                    addClickListener {
                        val subsOn = AuthService.getCurrentUser()?.subtitles_enabled ?: true
                        VideoPlayerDialog(item.transcodeId, item.titleName, null, subsOn).open()
                    }
                })
            }
            add(posterContainer)

            // Title name
            add(Span(item.titleName).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("max-width", "160px")
                style.set("display", "block")
            })

            // Episode label (TV only)
            if (item.isEpisode) {
                val label = item.episodeLabel ?: ""
                add(Span(label).apply {
                    style.set("color", "rgba(255,255,255,0.6)")
                    style.set("font-size", "var(--lumo-font-size-xs)")
                    style.set("overflow", "hidden")
                    style.set("text-overflow", "ellipsis")
                    style.set("white-space", "nowrap")
                    style.set("max-width", "160px")
                    style.set("display", "block")
                    element.setAttribute("title", label)
                })
            }

            // Time remaining
            add(Span(item.timeRemaining).apply {
                style.set("color", "rgba(255,255,255,0.5)")
                style.set("font-size", "var(--lumo-font-size-xs)")
            })

            // Click card → navigate to title detail
            element.addEventListener("click") {
                ui.ifPresent { it.navigate("title/${item.titleId}") }
            }
        }
    }
}
