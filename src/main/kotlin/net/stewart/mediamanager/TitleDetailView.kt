package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.*
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*

@Route(value = "title/:titleId", layout = MainLayout::class)
@PageTitle("Title Detail")
class TitleDetailView : VerticalLayout(), BeforeEnterObserver {

    private var currentTitle: Title? = null

    /** Track completed count so we only rebuild when a new transcode finishes. */
    private var lastCompletedCount = 0

    private val transcoderListener: (TranscoderProgressEvent) -> Unit = { event ->
        // Only rebuild the page when a transcode actually completes (not on every progress tick).
        // Progress ticks arrive every few seconds and a full rebuild kills open dialogs
        // and scrolls to top.
        if (event.totalCompleted != lastCompletedCount) {
            lastCompletedCount = event.totalCompleted
            ui.ifPresent { ui ->
                ui.access {
                    val title = currentTitle ?: return@access
                    buildContent(title)
                }
            }
        }
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        val titleId = event.routeParameters.get("titleId").orElse(null)?.toLongOrNull()
        if (titleId == null) {
            event.forwardTo("")
            return
        }

        val title = Title.findById(titleId)
        if (title == null) {
            event.forwardTo("")
            return
        }

        // Rating enforcement: redirect restricted users to home
        val currentUser = AuthService.getCurrentUser()
        if (currentUser != null && !currentUser.canSeeRating(title.content_rating)) {
            event.forwardTo("")
            return
        }

        currentTitle = title
        buildContent(title)
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        Broadcaster.registerTranscoderListener(transcoderListener)
    }

    override fun onDetach(detachEvent: DetachEvent) {
        Broadcaster.unregisterTranscoderListener(transcoderListener)
        super.onDetach(detachEvent)
    }

    private fun buildContent(title: Title) {
        removeAll()
        isPadding = false
        isSpacing = false
        width = "100%"

        // Inject responsive CSS for mobile (once)
        element.executeJs(
            "if(!document.getElementById('title-detail-responsive')){" +
            "var s=document.createElement('style');s.id='title-detail-responsive';" +
            "s.textContent='" +
            "@media(max-width:600px){" +
            ".hero-poster{width:150px!important}" +
            ".hero-backdrop{display:none!important}" +
            "}" +
            "';" +
            "document.head.appendChild(s)}"
        )

        // Hero section
        add(buildHeroSection(title))

        // Content area with padding
        val contentArea = VerticalLayout().apply {
            isPadding = true
            isSpacing = true
            width = "100%"
            style.set("max-width", "1200px")
            style.set("margin", "0 auto")
            style.set("padding-top", "var(--lumo-space-l)")
        }

        if (title.media_type == MediaType.TV.name) {
            buildTvSection(title, contentArea)
        } else {
            buildMovieTranscodes(title, contentArea)
        }

        buildCastRow(title, contentArea)
        buildSimilarTitlesRow(title, contentArea)

        add(contentArea)
    }

    // --- Hero Section ---

    private fun buildHeroSection(title: Title): Div {
        val transcodes = Transcode.findAll().filter { it.title_id == title.id }
        val formats = transcodes.mapNotNull { it.media_format }.distinct()
        val genres = loadGenres(title.id!!)

        val heroContent = HorizontalLayout().apply {
            width = "100%"
            isPadding = true
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.START
            style.set("padding", "var(--lumo-space-xl)")
            style.set("position", "relative")
            style.set("z-index", "1")

            // Poster
            val posterUrl = title.posterUrl(PosterSize.FULL)
            if (posterUrl != null) {
                add(Image(posterUrl, title.name).apply {
                    addClassName("hero-poster")
                    width = "300px"
                    style.set("border-radius", "8px")
                    style.set("box-shadow", "0 8px 24px rgba(0,0,0,0.4)")
                    style.set("flex-shrink", "0")
                })
            }

            // Info column
            val info = VerticalLayout().apply {
                isPadding = false
                isSpacing = false
                style.set("max-width", "700px")

                // Title row with star toggle
                val titleRow = HorizontalLayout().apply {
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    isSpacing = true
                    isPadding = false
                    style.set("margin-bottom", "var(--lumo-space-s)")

                    add(H1(title.name).apply {
                        style.set("margin", "0")
                        style.set("font-size", "2em")
                        style.set("line-height", "1.2")
                    })

                    // Star toggle
                    val isStarred = UserTitleFlagService.hasFlag(title.id!!, UserFlagType.STARRED)
                    add(Button((if (isStarred) VaadinIcon.STAR else VaadinIcon.STAR_O).create()).apply {
                        addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
                        style.set("color", if (isStarred) "#F59E0B" else "rgba(255,255,255,0.3)")
                        style.set("font-size", "1.5em")
                        style.set("min-width", "40px")
                        element.setAttribute("title", if (isStarred) "Unstar" else "Star")
                        addClickListener {
                            val nowStarred = UserTitleFlagService.toggleFlag(title.id!!, UserFlagType.STARRED)
                            icon = (if (nowStarred) VaadinIcon.STAR else VaadinIcon.STAR_O).create()
                            style.set("color", if (nowStarred) "#F59E0B" else "rgba(255,255,255,0.3)")
                            element.setAttribute("title", if (nowStarred) "Unstar" else "Star")
                        }
                    })
                }
                add(titleRow)

                // Metadata row: year, rating, type, genres
                val metaRow = HorizontalLayout().apply {
                    isSpacing = true
                    isPadding = false
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    style.set("gap", "var(--lumo-space-m)")
                    style.set("flex-wrap", "wrap")
                    style.set("margin-bottom", "var(--lumo-space-s)")

                    if (title.release_year != null) {
                        add(Span(title.release_year.toString()).apply {
                            style.set("color", "rgba(255,255,255,0.8)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        })
                    }
                    if (title.content_rating != null) {
                        add(Span(title.content_rating).apply {
                            style.set("color", "rgba(255,255,255,0.9)")
                            style.set("font-size", "var(--lumo-font-size-xs)")
                            style.set("border", "1px solid rgba(255,255,255,0.4)")
                            style.set("border-radius", "var(--lumo-border-radius-s)")
                            style.set("padding", "2px 6px")
                        })
                    }
                    add(Span(title.media_type).apply {
                        style.set("color", "rgba(255,255,255,0.6)")
                        style.set("font-size", "var(--lumo-font-size-s)")
                    })
                    if (genres.isNotEmpty()) {
                        add(Span(genres.joinToString(", ")).apply {
                            style.set("color", "rgba(255,255,255,0.6)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        })
                    }
                }
                add(metaRow)

                // Format badges
                if (formats.isNotEmpty()) {
                    val badgeRow = HorizontalLayout().apply {
                        isSpacing = true
                        isPadding = false
                        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                        style.set("margin-bottom", "var(--lumo-space-m)")
                        for (fmt in formats) {
                            val file = when (fmt) {
                                "DVD" -> "dvd.svg"
                                "BLURAY" -> "bluray.svg"
                                "UHD_BLURAY" -> "uhd-bluray.svg"
                                "HD_DVD" -> "hd-dvd.svg"
                                else -> null
                            }
                            if (file != null) {
                                add(Image("icons/$file", fmt).apply {
                                    height = "24px"
                                    style.set("width", "auto")
                                })
                            } else {
                                add(Span(fmt))
                            }
                        }
                    }
                    add(badgeRow)
                }

                // Tag badges
                val currentUser = AuthService.getCurrentUser()
                val isAdmin = currentUser?.isAdmin() == true
                val tags = TagService.getTagsForTitle(title.id!!)
                if (tags.isNotEmpty() || isAdmin) {
                    val tagRow = HorizontalLayout().apply {
                        isSpacing = true
                        isPadding = false
                        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                        style.set("flex-wrap", "wrap")
                        style.set("margin-bottom", "var(--lumo-space-s)")

                        for (tag in tags) {
                            val badge = createTagBadge(tag).apply {
                                style.set("cursor", "pointer")
                                element.addEventListener("click") {
                                    ui.ifPresent { it.navigate("tag/${tag.id}") }
                                }
                            }
                            add(badge)
                        }

                        if (isAdmin) {
                            add(Button(VaadinIcon.PENCIL.create()).apply {
                                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
                                style.set("color", "rgba(255,255,255,0.5)")
                                element.setAttribute("title", "Edit tags")
                                addClickListener { openEditTagsDialog(title) }
                            })
                        }
                    }
                    add(tagRow)
                }

                // Personal hide toggle
                val isPersonallyHidden = UserTitleFlagService.hasFlag(title.id!!, UserFlagType.HIDDEN)
                add(Button(if (isPersonallyHidden) "Unhide for me" else "Hide for me").apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-xs)")
                    style.set("margin-bottom", "var(--lumo-space-s)")
                    addClickListener {
                        val nowHidden = UserTitleFlagService.toggleFlag(title.id!!, UserFlagType.HIDDEN)
                        Notification.show(
                            if (nowHidden) "Hidden from your catalog and search" else "Unhidden — visible again",
                            3000, Notification.Position.BOTTOM_START
                        ).addThemeVariants(if (nowHidden) NotificationVariant.LUMO_CONTRAST else NotificationVariant.LUMO_SUCCESS)
                        buildContent(title)
                    }
                })

                // Description
                if (!title.description.isNullOrBlank()) {
                    add(Paragraph(title.description).apply {
                        style.set("color", "rgba(255,255,255,0.8)")
                        style.set("max-width", "700px")
                        style.set("line-height", "1.6")
                        style.set("margin", "0")
                    })
                }
            }
            add(info)
            expand(info)
        }

        // Wrap in a container with the backdrop image
        return Div().apply {
            style.set("position", "relative")
            style.set("overflow", "hidden")
            style.set("width", "100%")
            style.set("background", "linear-gradient(180deg, rgba(0,0,0,0.3) 0%, transparent 100%)")

            // Backdrop image (only if title has one)
            val backdropUrl = title.backdropUrl()
            if (backdropUrl != null) {
                add(Div().apply {
                    addClassName("hero-backdrop")
                    style.set("position", "absolute")
                    style.set("top", "0")
                    style.set("right", "0")
                    style.set("width", "60%")
                    style.set("height", "100%")
                    style.set("background-image", "url('$backdropUrl')")
                    style.set("background-size", "cover")
                    style.set("background-position", "center")
                    style.set("opacity", "0.4")
                    style.set("mask-image", "linear-gradient(to right, transparent 0%, rgba(0,0,0,1) 30%)")
                    style.set("-webkit-mask-image", "linear-gradient(to right, transparent 0%, rgba(0,0,0,1) 30%)")
                })
            }

            add(heroContent)
        }
    }

    // --- Watch from these locations ---

    private fun buildMovieTranscodes(title: Title, container: VerticalLayout) {
        val transcodes = Transcode.findAll()
            .filter { it.title_id == title.id && it.file_path != null }

        if (transcodes.isEmpty()) return

        // Load playback progress for these transcodes
        val progressMap = PlaybackProgressService.getProgressForTranscodes(
            transcodes.mapNotNull { it.id }.toSet()
        )

        container.add(H3("Watch from these locations").apply {
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        for (tc in transcodes) {
            container.add(buildTranscodeRow(tc, title.name, progressMap[tc.id]))
        }
    }

    private fun buildTranscodeRow(tc: Transcode, titleName: String, progress: PlaybackProgress? = null): HorizontalLayout {
        return HorizontalLayout().apply {
            width = "100%"
            isPadding = true
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            style.set("background", "rgba(255,255,255,0.05)")
            style.set("border-radius", "8px")
            style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")

            // Format icon
            val fmtFile = when (tc.media_format) {
                "DVD" -> "dvd.svg"
                "BLURAY" -> "bluray.svg"
                "UHD_BLURAY" -> "uhd-bluray.svg"
                "HD_DVD" -> "hd-dvd.svg"
                else -> null
            }
            if (fmtFile != null) {
                add(Image("icons/$fmtFile", tc.media_format ?: "").apply {
                    height = "20px"
                    style.set("width", "auto")
                    style.set("flex-shrink", "0")
                })
            }

            // Filename
            val fileName = tc.file_path?.substringAfterLast('\\')?.substringAfterLast('/') ?: "—"
            add(Span(fileName).apply {
                style.set("flex-grow", "1")
                style.set("color", "rgba(255,255,255,0.8)")
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
            })

            // Resume indicator
            if (progress != null && progress.position_seconds > 10) {
                val mins = (progress.position_seconds / 60).toInt()
                val secs = (progress.position_seconds % 60).toInt()
                add(Span("Resume from %d:%02d".format(mins, secs)).apply {
                    style.set("color", "var(--lumo-primary-text-color)")
                    style.set("font-size", "var(--lumo-font-size-xs)")
                    style.set("white-space", "nowrap")
                    style.set("flex-shrink", "0")
                })
            }

            // Action buttons
            add(buildPlayButtons(tc.id!!, tc.file_path!!, titleName, fileName, tc.title_id, tc.retranscode_requested))
        }
    }

    private fun buildPlayButtons(
        transcodeId: Long, filePath: String, titleName: String, fileName: String?,
        titleId: Long, retranscodeRequested: Boolean = false
    ): HorizontalLayout {
        return HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            style.set("flex-shrink", "0")

            val nasRoot = TranscoderAgent.getNasRoot()
            val canPlay = if (TranscoderAgent.needsTranscoding(filePath)) {
                nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, filePath)
            } else {
                true
            }

            if (canPlay) {
                // Watch in Browser button
                add(Button(VaadinIcon.PLAY.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL)
                    element.setAttribute("title", "Watch in Browser")
                    addClickListener { openVideoPlayer(transcodeId, titleName, fileName) }
                })

                // Re-transcode REFRESH button (for quality issues)
                if (retranscodeRequested) {
                    add(VaadinIcon.REFRESH.create().apply {
                        style.set("width", "var(--lumo-icon-size-s)")
                        style.set("height", "var(--lumo-icon-size-s)")
                        style.set("color", "var(--lumo-primary-color)")
                        element.setAttribute("title", "Re-transcode requested — waiting for MKV replacement")
                    })
                } else if (TranscoderAgent.needsTranscoding(filePath)) {
                    add(Button(VaadinIcon.REFRESH.create()).apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                        style.set("color", "rgba(255,255,255,0.5)")
                        element.setAttribute("title", "Request re-transcode")
                        addClickListener {
                            val dialog = Dialog().apply {
                                headerTitle = "Request re-transcode?"
                                add(Span("The MKV source will need to be re-ripped. The ForBrowser copy will be regenerated when the new MKV is detected.").apply {
                                    style.set("padding", "var(--lumo-space-m)")
                                })
                                val footer = HorizontalLayout().apply {
                                    justifyContentMode = FlexComponent.JustifyContentMode.END
                                    width = "100%"
                                    isSpacing = true
                                }
                                val cancelBtn = Button("Cancel") { close() }
                                val confirmBtn = Button("Request").apply {
                                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                                    addClickListener {
                                        close()
                                        val tc = Transcode.findById(transcodeId)
                                        if (tc != null) {
                                            tc.retranscode_requested = true
                                            tc.save()
                                            Notification.show("Re-transcode requested", 3000, Notification.Position.BOTTOM_START)
                                                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                                            currentTitle?.let { buildContent(it) }
                                        }
                                    }
                                }
                                footer.add(cancelBtn, confirmBtn)
                                footer.element.setAttribute("slot", "footer")
                                add(footer)
                            }
                            dialog.open()
                        }
                    })
                }
            } else {
                // Not playable
                add(Button(VaadinIcon.PLAY.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL)
                    isEnabled = false
                    element.setAttribute("title", "Transcoding not yet complete")
                })

                // Transcode request button (ARROW_CIRCLE_UP, toggleable)
                val isWished = WishListService.hasActiveTranscodeWish(titleId)
                add(Button(VaadinIcon.ARROW_CIRCLE_UP.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                    style.set("color", if (isWished) "var(--lumo-primary-color)" else "rgba(255,255,255,0.5)")
                    element.setAttribute("title",
                        if (isWished) "Transcoding requested" else "Request transcoding")
                    addClickListener {
                        if (WishListService.hasActiveTranscodeWish(titleId)) {
                            WishListService.removeTranscodeWish(titleId)
                            icon = VaadinIcon.ARROW_CIRCLE_UP.create()
                            style.set("color", "rgba(255,255,255,0.5)")
                            element.setAttribute("title", "Request transcoding")
                            Notification.show("Transcoding request removed", 2000, Notification.Position.BOTTOM_START)
                        } else {
                            WishListService.addTranscodeWish(titleId)
                            icon = VaadinIcon.ARROW_CIRCLE_UP.create()
                            style.set("color", "var(--lumo-primary-color)")
                            element.setAttribute("title", "Transcoding requested")
                            Notification.show("Transcoding requested", 3000, Notification.Position.BOTTOM_START)
                                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                        }
                    }
                })
            }

        }
    }

    // --- TV Section ---

    private fun buildTvSection(title: Title, container: VerticalLayout) {
        val allEpisodes = Episode.findAll().filter { it.title_id == title.id }
        val transcodes = Transcode.findAll().filter { it.title_id == title.id }
        val transcodedEpisodeIds = transcodes.mapNotNull { it.episode_id }.toSet()

        // Load playback progress for these transcodes
        val progressMap = PlaybackProgressService.getProgressForTranscodes(
            transcodes.mapNotNull { it.id }.toSet()
        )

        // Season lifecycle overview
        buildSeasonOverview(title, container)

        if (allEpisodes.isEmpty()) {
            container.add(Span("No episodes on disk yet.").apply {
                style.set("color", "rgba(255,255,255,0.5)")
            })
            return
        }

        val seasons = allEpisodes.map { it.season_number }.distinct().sorted()

        container.add(H3("Watch from these locations").apply {
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        val episodeGrid = Grid<EpisodeRow>(EpisodeRow::class.java, false).apply {
            width = "100%"
            isAllRowsVisible = true

            addColumn({ it.episodeNumber.toString() }).setHeader("#").setWidth("60px").setFlexGrow(0)
            addColumn({ it.episodeTitle ?: "" }).setHeader("Episode Title").setFlexGrow(1)
            addColumn(ComponentRenderer { row ->
                if (row.filePath != null && row.transcodeId != null) {
                    val epLabel = "S${row.episodeNumber.toString().padStart(2, '0')}" +
                        (row.episodeTitle?.let { " - $it" } ?: "")
                    val rowLayout = HorizontalLayout().apply {
                        isSpacing = true
                        isPadding = false
                        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    }
                    // Resume indicator
                    val progress = progressMap[row.transcodeId]
                    if (progress != null && progress.position_seconds > 10) {
                        val mins = (progress.position_seconds / 60).toInt()
                        val secs = (progress.position_seconds % 60).toInt()
                        rowLayout.add(Span("Resume from %d:%02d".format(mins, secs)).apply {
                            style.set("color", "var(--lumo-primary-text-color)")
                            style.set("font-size", "var(--lumo-font-size-xs)")
                            style.set("white-space", "nowrap")
                        })
                    }
                    rowLayout.add(buildPlayButtons(row.transcodeId, row.filePath, title.name, epLabel, title.id!!, row.retranscodeRequested))
                    rowLayout
                } else {
                    Span()
                }
            }).setHeader("Play").setAutoWidth(true).setFlexGrow(0)
        }

        val seasonCombo = ComboBox<Int>("Season").apply {
            setItems(seasons)
            setItemLabelGenerator { if (it == 0) "Special Features" else "Season $it" }
            // Default to Season 1 if it exists, otherwise the first available season
            value = if (1 in seasons) 1 else seasons.first()
            addValueChangeListener { event ->
                val selectedSeason = event.value ?: return@addValueChangeListener
                refreshEpisodeGrid(episodeGrid, allEpisodes, transcodes,
                    transcodedEpisodeIds, selectedSeason)
            }
        }
        container.add(seasonCombo)
        container.add(episodeGrid)

        // Initial load — must match the combo's default value
        refreshEpisodeGrid(episodeGrid, allEpisodes, transcodes,
            transcodedEpisodeIds, seasonCombo.value)
    }

    // --- Season Overview ---

    private fun buildSeasonOverview(title: Title, container: VerticalLayout) {
        val titleSeasons = TitleSeason.findAll()
            .filter { it.title_id == title.id && it.season_number > 0 }
            .sortedBy { it.season_number }

        if (titleSeasons.isEmpty()) return

        val isAdmin = AuthService.getCurrentUser()?.isAdmin() == true

        container.add(H3("Seasons").apply {
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        // Check for media items with unparseable freetext seasons
        val mitJoins = MediaItemTitle.findAll().filter { it.title_id == title.id }
        val mitIds = mitJoins.mapNotNull { it.id }.toSet()
        val structuredJoins = MediaItemTitleSeason.findAll().filter { it.media_item_title_id in mitIds }
        val unparseableJoins = mitJoins.filter { join ->
            !join.seasons.isNullOrBlank() &&
                structuredJoins.none { it.media_item_title_id == join.id }
        }
        if (unparseableJoins.isNotEmpty() && isAdmin) {
            container.add(HorizontalLayout().apply {
                isPadding = true
                isSpacing = true
                width = "100%"
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                style.set("background", "rgba(255,165,0,0.15)")
                style.set("border", "1px solid rgba(255,165,0,0.4)")
                style.set("border-radius", "8px")
                style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                style.set("margin-bottom", "var(--lumo-space-s)")

                add(VaadinIcon.WARNING.create().apply {
                    style.set("color", "#FFA500")
                    style.set("flex-shrink", "0")
                })
                val seasonTexts = unparseableJoins.map { "\"${it.seasons}\"" }.joinToString(", ")
                add(Span("Unparseable season data: $seasonTexts — needs manual review").apply {
                    style.set("color", "rgba(255,255,255,0.9)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                })
            })
        }

        val chipRow = HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            style.set("flex-wrap", "wrap")
            style.set("gap", "var(--lumo-space-s)")
            style.set("margin-bottom", "var(--lumo-space-m)")
        }

        for (ts in titleSeasons) {
            chipRow.add(buildSeasonChip(ts, isAdmin, title))
        }

        container.add(chipRow)
    }

    private fun buildSeasonChip(ts: TitleSeason, isAdmin: Boolean, title: Title): Div {
        val status = try {
            AcquisitionStatus.valueOf(ts.acquisition_status)
        } catch (_: Exception) {
            AcquisitionStatus.UNKNOWN
        }

        val (bgColor, textColor, label) = when (status) {
            AcquisitionStatus.OWNED -> Triple("rgba(0,200,83,0.2)", "var(--lumo-success-color)", "Owned")
            AcquisitionStatus.ORDERED -> Triple("rgba(30,136,229,0.2)", "var(--lumo-primary-color)", "Ordered")
            AcquisitionStatus.REJECTED -> Triple("rgba(244,67,54,0.15)", "var(--lumo-error-color)", "Rejected")
            AcquisitionStatus.NOT_AVAILABLE -> Triple("rgba(255,255,255,0.08)", "rgba(255,255,255,0.5)", "N/A")
            AcquisitionStatus.NEEDS_ASSISTANCE -> Triple("rgba(255,165,0,0.2)", "#FFA500", "Needs Help")
            AcquisitionStatus.UNKNOWN -> Triple("rgba(255,255,255,0.08)", "rgba(255,255,255,0.4)", "Unknown")
        }

        return Div().apply {
            style.set("display", "inline-flex")
            style.set("align-items", "center")
            style.set("gap", "var(--lumo-space-xs)")
            style.set("background", bgColor)
            style.set("border-radius", "9999px")
            style.set("padding", "4px 12px")
            if (isAdmin) style.set("cursor", "pointer")

            add(Span("S${ts.season_number}").apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("font-weight", "600")
            })
            add(Span(label).apply {
                style.set("color", textColor)
                style.set("font-size", "var(--lumo-font-size-xs)")
            })

            if (isAdmin) {
                element.setAttribute("title", "Click to change status")
                element.addEventListener("click") {
                    openSeasonStatusDialog(ts, title)
                }
            }
        }
    }

    private fun openSeasonStatusDialog(ts: TitleSeason, title: Title) {
        val dialog = Dialog().apply {
            headerTitle = "Season ${ts.season_number} — Set Status"
            width = "350px"
        }

        val statusOptions = listOf(
            AcquisitionStatus.UNKNOWN, AcquisitionStatus.NOT_AVAILABLE,
            AcquisitionStatus.ORDERED, AcquisitionStatus.OWNED,
            AcquisitionStatus.REJECTED
        )

        val combo = ComboBox<AcquisitionStatus>("Acquisition Status").apply {
            setItems(statusOptions)
            setItemLabelGenerator { it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.replace('_', ' ') }
            value = try {
                AcquisitionStatus.valueOf(ts.acquisition_status)
            } catch (_: Exception) {
                AcquisitionStatus.UNKNOWN
            }
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
                ts.acquisition_status = newStatus.name
                ts.save()

                // If marked as OWNED, fulfill matching media wishes
                if (newStatus == AcquisitionStatus.OWNED) {
                    val tmdbKey = TmdbId.of(title.tmdb_id, title.media_type)
                    if (tmdbKey != null) {
                        WishListService.fulfillMediaWishes(tmdbKey)
                    }
                }

                dialog.close()
                Notification.show("Season ${ts.season_number} → ${newStatus.name}", 3000,
                    Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                buildContent(title)
            }
        })
        footer.element.setAttribute("slot", "footer")
        dialog.add(footer)
        dialog.open()
    }

    // --- Cast Row ---

    private fun buildCastRow(title: Title, container: VerticalLayout) {
        val castMembers = CastMember.findAll()
            .filter { it.title_id == title.id }
            .sortedBy { it.cast_order }

        if (castMembers.isEmpty()) return

        container.add(H3("Cast").apply {
            style.set("margin-top", "var(--lumo-space-l)")
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        val scrollRow = HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            width = "100%"
            style.set("overflow-x", "auto")
            style.set("padding-bottom", "var(--lumo-space-s)")
        }

        for (member in castMembers) {
            scrollRow.add(buildCastCard(member))
        }

        container.add(scrollRow)
    }

    private fun buildCastCard(member: CastMember): VerticalLayout {
        return VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "120px"
            style.set("min-width", "120px")
            style.set("align-items", "center")
            style.set("cursor", "pointer")

            element.addEventListener("click") {
                ui.ifPresent { it.navigate("actor/${member.tmdb_person_id}") }
            }

            // Headshot or placeholder
            if (member.profile_path != null) {
                add(Image("/headshots/${member.id}", member.name).apply {
                    width = "80px"
                    height = "80px"
                    style.set("border-radius", "50%")
                    style.set("object-fit", "cover")
                })
            } else {
                // Placeholder circle with initial
                val initial = member.name.firstOrNull()?.uppercase() ?: "?"
                add(Span(initial).apply {
                    style.set("display", "flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    style.set("width", "80px")
                    style.set("height", "80px")
                    style.set("border-radius", "50%")
                    style.set("background", "rgba(255,255,255,0.15)")
                    style.set("color", "rgba(255,255,255,0.6)")
                    style.set("font-size", "var(--lumo-font-size-xl)")
                    style.set("font-weight", "bold")
                    style.set("flex-shrink", "0")
                })
            }

            // Actor name
            add(Span(member.name).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("text-align", "center")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("max-width", "120px")
            })

            // Character name
            if (member.character_name != null) {
                add(Span(member.character_name).apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                    style.set("text-align", "center")
                    style.set("overflow", "hidden")
                    style.set("text-overflow", "ellipsis")
                    style.set("white-space", "nowrap")
                    style.set("max-width", "120px")
                })
            }
        }
    }

    // --- Similar Titles ---

    private fun buildSimilarTitlesRow(title: Title, container: VerticalLayout) {
        val similar = SimilarTitlesService.getSimilarTitles(title)
        if (similar.isEmpty()) return

        container.add(H3("Similar Titles").apply {
            style.set("margin-top", "var(--lumo-space-l)")
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        val scrollRow = HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            width = "100%"
            style.set("overflow-x", "auto")
            style.set("padding-bottom", "var(--lumo-space-s)")
        }

        for (t in similar) {
            scrollRow.add(buildSimilarTitleCard(t))
        }

        container.add(scrollRow)
    }

    private fun buildSimilarTitleCard(title: Title): VerticalLayout {
        return VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "120px"
            style.set("min-width", "120px")
            style.set("cursor", "pointer")

            element.addEventListener("click") {
                ui.ifPresent { it.navigate("title/${title.id}") }
            }

            // Poster
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

            // Title name
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

            // Year
            if (title.release_year != null) {
                add(Span(title.release_year.toString()).apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                })
            }
        }
    }

    // --- Helpers ---

    private fun loadGenres(titleId: Long): List<String> {
        val genreIds = TitleGenre.findAll().filter { it.title_id == titleId }.map { it.genre_id }
        if (genreIds.isEmpty()) return emptyList()
        return Genre.findAll().filter { it.id in genreIds }.map { it.name }
    }

    private fun refreshEpisodeGrid(
        grid: Grid<EpisodeRow>,
        allEpisodes: List<Episode>,
        transcodes: List<Transcode>,
        transcodedEpisodeIds: Set<Long>,
        season: Int
    ) {
        val seasonEpisodes = allEpisodes
            .filter { it.season_number == season }
            .sortedBy { it.episode_number }

        val rows = seasonEpisodes.map { ep ->
            val isTranscoded = ep.id in transcodedEpisodeIds
            val tc = if (isTranscoded) transcodes.firstOrNull { it.episode_id == ep.id } else null

            EpisodeRow(
                episodeNumber = ep.episode_number,
                episodeTitle = ep.name,
                transcoded = isTranscoded,
                filePath = tc?.file_path,
                transcodeId = tc?.id,
                retranscodeRequested = tc?.retranscode_requested ?: false
            )
        }
        grid.setItems(rows)
    }

    private fun openVideoPlayer(transcodeId: Long, titleName: String, fileName: String?) {
        val subsOn = AuthService.getCurrentUser()?.subtitles_enabled ?: true
        VideoPlayerDialog(transcodeId, titleName, fileName, subsOn).open()
    }

    private fun openEditTagsDialog(title: Title) {
        val dialog = Dialog().apply {
            headerTitle = "Edit Tags"
            width = "500px"
        }

        val contentLayout = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
        }

        fun rebuildDialogContent() {
            contentLayout.removeAll()

            val currentTags = TagService.getTagsForTitle(title.id!!)

            // Current tags with remove buttons
            if (currentTags.isNotEmpty()) {
                val currentRow = HorizontalLayout().apply {
                    isSpacing = true
                    isPadding = false
                    style.set("flex-wrap", "wrap")
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER

                    for (tag in currentTags) {
                        val chip = HorizontalLayout().apply {
                            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                            isSpacing = false
                            isPadding = false
                            style.set("background-color", tag.bg_color)
                            style.set("color", tag.textColor())
                            style.set("border-radius", "9999px")
                            style.set("padding", "2px 6px 2px 10px")
                            style.set("font-size", "var(--lumo-font-size-xs)")
                            style.set("font-weight", "500")

                            add(Span(tag.name))
                            add(Button(VaadinIcon.CLOSE_SMALL.create()).apply {
                                addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON)
                                style.set("color", tag.textColor())
                                style.set("min-width", "20px")
                                style.set("width", "20px")
                                style.set("height", "20px")
                                addClickListener {
                                    TagService.removeTagFromTitle(title.id!!, tag.id!!)
                                    rebuildDialogContent()
                                }
                            })
                        }
                        add(chip)
                    }
                }
                contentLayout.add(currentRow)
            }

            // Add tag ComboBox
            val allTags = TagService.getAllTags()
            val currentTagIds = currentTags.map { it.id }.toSet()
            val available = allTags.filter { it.id !in currentTagIds }

            if (available.isNotEmpty()) {
                val addRow = HorizontalLayout().apply {
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                    isSpacing = true
                    width = "100%"

                    val combo = ComboBox<Tag>().apply {
                        placeholder = "Add tag..."
                        width = "100%"
                        setItems(available)
                        setItemLabelGenerator { it.name }
                        setRenderer(ComponentRenderer { tag ->
                            HorizontalLayout().apply {
                                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                                isSpacing = true
                                isPadding = false
                                add(createTagBadge(tag))
                            }
                        })
                    }
                    add(combo)
                    expand(combo)

                    val addBtn = Button("Add").apply {
                        addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL)
                        addClickListener {
                            val tag = combo.value ?: return@addClickListener
                            TagService.addTagToTitle(title.id!!, tag.id!!)
                            rebuildDialogContent()
                        }
                    }
                    add(addBtn)
                }
                contentLayout.add(addRow)
            }
        }

        rebuildDialogContent()
        dialog.add(contentLayout)

        val closeBtn = Button("Done") {
            dialog.close()
            buildContent(title)
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        }
        val footer = HorizontalLayout().apply {
            justifyContentMode = FlexComponent.JustifyContentMode.END
            width = "100%"
            add(closeBtn)
        }
        footer.element.setAttribute("slot", "footer")
        dialog.add(footer)
        dialog.open()
    }
}

private data class EpisodeRow(
    val episodeNumber: Int,
    val episodeTitle: String?,
    val transcoded: Boolean,
    val filePath: String?,
    val transcodeId: Long? = null,
    val retranscodeRequested: Boolean = false
)
