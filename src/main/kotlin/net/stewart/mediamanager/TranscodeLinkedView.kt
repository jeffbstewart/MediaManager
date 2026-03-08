package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*

@Route(value = "transcodes/linked", layout = MainLayout::class)
@PageTitle("Linked Transcodes")
class TranscodeLinkedView : KComposite() {

    private lateinit var linkedGrid: Grid<LinkedTranscodeRow>
    private lateinit var searchField: TextField
    private lateinit var formatFilter: ComboBox<String>
    private lateinit var typeFilter: ComboBox<String>
    private val root = ui {
        verticalLayout {

            h2("Linked Transcodes")

            horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
                isSpacing = true

                searchField = textField {
                    placeholder = "Search titles..."
                    isClearButtonVisible = true
                    width = "20em"
                    valueChangeMode = ValueChangeMode.LAZY
                    valueChangeTimeout = 300
                    addValueChangeListener { refreshLinkedGrid() }
                }

                formatFilter = comboBox {
                    placeholder = "All formats"
                    isClearButtonVisible = true
                    setItems("BLURAY", "DVD", "UHD_BLURAY")
                    addValueChangeListener { refreshLinkedGrid() }
                }

                typeFilter = comboBox {
                    placeholder = "All types"
                    isClearButtonVisible = true
                    setItems("MOVIE", "TV")
                    addValueChangeListener { refreshLinkedGrid() }
                }
            }

            linkedGrid = grid {
                width = "100%"
                height = "600px"
                pageSize = 100

                addColumn(ComponentRenderer { row ->
                    val url = row.posterUrl
                    if (url != null) {
                        Image(url, row.titleName).apply {
                            height = "60px"
                            width = "40px"
                            style.set("object-fit", "cover")
                        }
                    } else {
                        Span("\u2014")
                    }
                }).setHeader("Poster").setWidth("80px").setFlexGrow(0)

                addColumn(ComponentRenderer { row ->
                    Span(row.titleName).apply {
                        element.setAttribute("title", row.titleName)
                        style.set("overflow", "hidden")
                        style.set("text-overflow", "ellipsis")
                        style.set("white-space", "nowrap")
                        style.set("display", "block")
                    }
                }).setHeader("Title").setFlexGrow(1)
                addColumn({ it.mediaType }).setHeader("Type").setWidth("80px").setFlexGrow(0)
                addColumn({ it.transcodeFormat ?: "\u2014" }).setHeader("Format").setWidth("110px").setFlexGrow(0)
                addColumn(ComponentRenderer { row ->
                    val display = row.fileName ?: "\u2014"
                    Span(display).apply {
                        if (row.filePath != null) {
                            element.setAttribute("title", row.filePath)
                        }
                        style.set("overflow", "hidden")
                        style.set("text-overflow", "ellipsis")
                        style.set("white-space", "nowrap")
                        style.set("display", "block")
                    }
                }).setHeader("File").setWidth("250px").setFlexGrow(0)
                addColumn(ComponentRenderer { row ->
                    HorizontalLayout().apply {
                        isSpacing = false
                        isPadding = false
                        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                        if (row.filePath != null) {
                            add(buildPlayButtons(row))
                        }
                        add(Button(VaadinIcon.UNLINK.create()).apply {
                            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY,
                                ButtonVariant.LUMO_ERROR)
                            element.setAttribute("title", "Unlink from title")
                            addClickListener { unlinkTranscode(row) }
                        })
                    }
                }).setHeader("Play").setAutoWidth(true).setFlexGrow(0)
            }

            // Spacer
            span { style.set("min-height", "6em"); style.set("display", "block") }
        }
    }

    init {
        refreshLinkedGrid()
    }

    private fun buildPlayButtons(row: LinkedTranscodeRow): HorizontalLayout {
        return HorizontalLayout().apply {
            isSpacing = false
            isPadding = false
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER

            val nasRoot = TranscoderAgent.getNasRoot()
            val canPlay = if (TranscoderAgent.needsTranscoding(row.filePath!!)) {
                nasRoot != null && TranscoderAgent.isTranscoded(nasRoot, row.filePath)
            } else {
                true // MP4/M4V — always playable
            }

            if (canPlay) {
                add(Button(VaadinIcon.PLAY.create()).apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                    addClickListener { openVideoPlayer(row.transcodeId, row.titleName, row.fileName) }
                })

                // Re-transcode REFRESH button
                if (row.retranscodeRequested) {
                    add(VaadinIcon.REFRESH.create().apply {
                        style.set("width", "var(--lumo-icon-size-s)")
                        style.set("height", "var(--lumo-icon-size-s)")
                        style.set("color", "var(--lumo-primary-color)")
                        element.setAttribute("title", "Re-transcode requested — waiting for MKV replacement")
                    })
                } else if (TranscoderAgent.needsTranscoding(row.filePath)) {
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
                                        val tc = Transcode.findById(row.transcodeId)
                                        if (tc != null) {
                                            tc.retranscode_requested = true
                                            tc.save()
                                            Notification.show("Re-transcode requested", 3000, Notification.Position.BOTTOM_START)
                                                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                                            refreshLinkedGrid()
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
                // Not playable — show transcode request button
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

        }
    }

    private fun refreshLinkedGrid() {
        val transcodes = Transcode.findAll()
        val titles = Title.findAll().associateBy { it.id }
        val episodes = Episode.findAll().associateBy { it.id }

        var rows = transcodes
            .filter { it.file_path != null }
            .mapNotNull { tc ->
                val title = titles[tc.title_id] ?: return@mapNotNull null
                val episode = tc.episode_id?.let { episodes[it] }
                val displayFile = if (episode != null) {
                    "S${episode.season_number.toString().padStart(2, '0')}E${episode.episode_number.toString().padStart(2, '0')}" +
                        (episode.name?.let { " - $it" } ?: "")
                } else {
                    tc.file_path?.substringAfterLast('\\')?.substringAfterLast('/')
                }

                LinkedTranscodeRow(
                    transcodeId = tc.id!!,
                    titleId = title.id!!,
                    titleName = title.name,
                    mediaType = title.media_type,
                    transcodeFormat = tc.media_format,
                    fileName = displayFile,
                    filePath = tc.file_path,
                    posterUrl = title.posterUrl(PosterSize.THUMBNAIL),
                    retranscodeRequested = tc.retranscode_requested
                )
            }
            .sortedBy { it.titleName.lowercase() }

        // Apply filters
        val search = searchField.value?.trim()?.lowercase() ?: ""
        if (search.isNotEmpty()) {
            rows = rows.filter { it.titleName.lowercase().contains(search) }
        }
        val format = formatFilter.value
        if (format != null) {
            rows = rows.filter { it.transcodeFormat == format }
        }
        val type = typeFilter.value
        if (type != null) {
            rows = rows.filter { it.mediaType == type }
        }

        linkedGrid.setItems(rows)
    }

    private fun unlinkTranscode(row: LinkedTranscodeRow) {
        val transcode = Transcode.findById(row.transcodeId) ?: return

        val episodeId = transcode.episode_id
        transcode.delete()
        if (episodeId != null) {
            val otherRefs = Transcode.findAll().any { it.episode_id == episodeId }
            if (!otherRefs) {
                Episode.findById(episodeId)?.delete()
            }
        }

        // Reset the DiscoveredFile back to UNMATCHED
        val df = DiscoveredFile.findAll().firstOrNull { it.file_path == row.filePath }
        if (df != null && df.match_status == DiscoveredFileStatus.LINKED.name) {
            df.match_status = DiscoveredFileStatus.UNMATCHED.name
            df.matched_title_id = null
            df.matched_episode_id = null
            df.match_method = null
            df.save()
        }

        refreshLinkedGrid()
        Notification.show("Unlinked from ${row.titleName}", 2000, Notification.Position.BOTTOM_START)
    }

    private fun openVideoPlayer(transcodeId: Long, titleName: String, fileName: String?) {
        val subsOn = AuthService.getCurrentUser()?.subtitles_enabled ?: true
        VideoPlayerDialog(transcodeId, titleName, fileName, subsOn).open()
    }

}

internal data class LinkedTranscodeRow(
    val transcodeId: Long,
    val titleId: Long,
    val titleName: String,
    val mediaType: String,
    val transcodeFormat: String?,
    val fileName: String?,
    val filePath: String?,
    val posterUrl: String?,
    val retranscodeRequested: Boolean = false
)
