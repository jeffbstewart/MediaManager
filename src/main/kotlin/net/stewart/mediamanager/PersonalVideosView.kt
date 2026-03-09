package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*
import net.stewart.mediamanager.service.*
import java.time.format.DateTimeFormatter

@Route(value = "content/family", layout = MainLayout::class)
@PageTitle("Family Videos")
class PersonalVideosView : KComposite() {

    private var sortMode: FamilySortMode = FamilySortMode.DATE_DESC
    private var selectedMemberIds: Set<Long> = emptySet()
    private var playableOnly: Boolean = false

    private lateinit var cardGrid: Div
    private lateinit var statusLabel: Span
    private lateinit var chipRow: HorizontalLayout

    private var playableTitleIds: Set<Long> = emptySet()
    private var progressByTitle: Map<Long, PlaybackProgress> = emptyMap()

    private val root = ui {
        verticalLayout {
            isPadding = true
            isSpacing = true

            chipRow = horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isSpacing = false
                style.set("gap", "var(--lumo-space-xs)")
                style.set("flex-wrap", "wrap")
                style.set("row-gap", "var(--lumo-space-xs)")
            }

            statusLabel = span().apply {
                style.set("color", "rgba(255,255,255,0.5)")
                style.set("font-size", "var(--lumo-font-size-s)")
            }

            cardGrid = Div().apply {
                style.set("display", "grid")
                style.set("grid-template-columns", "repeat(auto-fill, minmax(280px, 1fr))")
                style.set("gap", "var(--lumo-space-m)")
                style.set("width", "100%")
            }
            add(cardGrid)
        }
    }

    init {
        buildChips()
        refreshGrid()
    }

    private fun buildChips() {
        chipRow.removeAll()

        // Playable toggle
        chipRow.add(createChip("Playable", playableOnly) {
            playableOnly = !playableOnly
            buildChips()
            refreshGrid()
        })

        // Family member filter chips
        val members = FamilyMemberService.getAllMembers()
        if (members.isNotEmpty()) {
            chipRow.add(createSeparator())
            chipRow.add(createChip("All People", selectedMemberIds.isEmpty()) {
                selectedMemberIds = emptySet()
                buildChips()
                refreshGrid()
            })
            for (member in members) {
                val selected = member.id in selectedMemberIds
                chipRow.add(createChip(member.name, selected) {
                    selectedMemberIds = if (selected) selectedMemberIds - member.id!! else selectedMemberIds + member.id!!
                    buildChips()
                    refreshGrid()
                })
            }
        }

        // Sort chips
        chipRow.add(createSeparator())
        for (mode in FamilySortMode.entries) {
            chipRow.add(createChip(mode.label, mode == sortMode) {
                sortMode = mode
                buildChips()
                refreshGrid()
            })
        }
    }

    private fun refreshGrid() {
        var titles = PersonalVideoService.getAllPersonalVideos()

        // Filter by family member
        if (selectedMemberIds.isNotEmpty()) {
            val titleIdsForMembers = FamilyMemberService.getTitleIdsForMembers(selectedMemberIds)
            titles = titles.filter { it.id in titleIdsForMembers }
        }

        playableTitleIds = PosterGridHelper.computePlayableTitleIds()
        if (playableOnly) {
            titles = titles.filter { it.id in playableTitleIds }
        }

        progressByTitle = PlaybackProgressService.getProgressByTitle()

        // Load family members and tags for all visible titles
        val titleIds = titles.mapNotNull { it.id }.toSet()
        val membersByTitle = titleIds.associateWith { FamilyMemberService.getMembersForTitle(it) }
        val tagsByTitle = titleIds.associateWith { TagService.getTagsForTitle(it) }

        titles = when (sortMode) {
            FamilySortMode.DATE_DESC -> titles.sortedByDescending { it.event_date }
            FamilySortMode.DATE_ASC -> titles.sortedBy { it.event_date }
            FamilySortMode.NAME -> titles.sortedBy { (it.sort_name ?: it.name).lowercase() }
            FamilySortMode.RECENTLY_ADDED -> titles.sortedByDescending { it.created_at }
        }

        cardGrid.removeAll()
        for (title in titles) {
            cardGrid.add(buildVideoCard(title, membersByTitle[title.id] ?: emptyList(),
                tagsByTitle[title.id] ?: emptyList()))
        }
        statusLabel.text = "${titles.size} family video${if (titles.size != 1) "s" else ""}"
    }

    private fun buildVideoCard(title: Title, members: List<FamilyMember>, tags: List<Tag>): Div {
        val isPlayable = title.id in playableTitleIds
        val progress = progressByTitle[title.id]

        return Div().apply {
            style.set("background", "var(--lumo-contrast-5pct)")
            style.set("border-radius", "var(--lumo-border-radius-l)")
            style.set("padding", "var(--lumo-space-m)")
            style.set("cursor", "pointer")
            style.set("transition", "background-color 0.2s")

            // Title row with play indicator
            val titleRow = HorizontalLayout().apply {
                width = "100%"
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isSpacing = true
                isPadding = false

                if (isPlayable) {
                    add(VaadinIcon.PLAY_CIRCLE.create().apply {
                        setSize("16px")
                        style.set("color", "var(--lumo-success-color)")
                        style.set("flex-shrink", "0")
                    })
                }

                add(Span(title.name).apply {
                    style.set("font-weight", "600")
                    style.set("font-size", "var(--lumo-font-size-m)")
                    style.set("overflow", "hidden")
                    style.set("text-overflow", "ellipsis")
                    style.set("white-space", "nowrap")
                    style.set("flex-grow", "1")
                })
            }
            add(titleRow)

            // Event date
            if (title.event_date != null) {
                add(Span(title.event_date!!.format(DATE_FORMAT)).apply {
                    style.set("color", "var(--lumo-secondary-text-color)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("display", "block")
                    style.set("margin-top", "var(--lumo-space-xs)")
                })
            }

            // Description (truncated)
            if (!title.description.isNullOrBlank()) {
                val desc = if (title.description!!.length > 120) title.description!!.take(120) + "..." else title.description!!
                add(Span(desc).apply {
                    style.set("color", "rgba(255,255,255,0.6)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("display", "block")
                    style.set("margin-top", "var(--lumo-space-xs)")
                })
            }

            // Family members
            if (members.isNotEmpty()) {
                val memberRow = Div().apply {
                    style.set("display", "flex")
                    style.set("flex-wrap", "wrap")
                    style.set("gap", "4px")
                    style.set("margin-top", "var(--lumo-space-xs)")
                }
                for (member in members) {
                    memberRow.add(Span(member.name).apply {
                        style.set("background", "var(--lumo-primary-color-10pct)")
                        style.set("color", "var(--lumo-primary-text-color)")
                        style.set("border-radius", "9999px")
                        style.set("padding", "1px 8px")
                        style.set("font-size", "var(--lumo-font-size-xs)")
                        style.set("white-space", "nowrap")
                    })
                }
                add(memberRow)
            }

            // Tags
            if (tags.isNotEmpty()) {
                val tagRow = Div().apply {
                    style.set("display", "flex")
                    style.set("flex-wrap", "wrap")
                    style.set("gap", "4px")
                    style.set("margin-top", "var(--lumo-space-xs)")
                }
                for (tag in tags) {
                    tagRow.add(createTagBadge(tag))
                }
                add(tagRow)
            }

            // Progress bar
            val dur = progress?.duration_seconds
            if (progress != null && dur != null && dur > 0.0) {
                val pct = ((progress.position_seconds / dur) * 100).toInt().coerceIn(0, 100)
                if (pct in 1..94) {
                    add(Div().apply {
                        style.set("margin-top", "var(--lumo-space-xs)")
                        style.set("height", "3px")
                        style.set("background", "rgba(255,255,255,0.1)")
                        style.set("border-radius", "2px")
                        style.set("overflow", "hidden")
                        add(Div().apply {
                            style.set("height", "100%")
                            style.set("width", "${pct}%")
                            style.set("background", "var(--lumo-primary-color)")
                        })
                    })
                }
            }

            // Click navigates to title detail
            element.addEventListener("click") {
                ui.ifPresent { it.navigate("title/${title.id}") }
            }
        }
    }

    private fun createSeparator(): Span = Span("\u00b7").apply {
        style.set("color", "rgba(255,255,255,0.3)")
        style.set("margin", "0 var(--lumo-space-xs)")
    }

    private fun createChip(label: String, selected: Boolean, onClick: () -> Unit): Span {
        return Span(label).apply {
            style.set("padding", "4px 12px")
            style.set("border-radius", "9999px")
            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("cursor", "pointer")
            style.set("user-select", "none")
            style.set("white-space", "nowrap")
            if (selected) {
                style.set("background", "var(--lumo-primary-color)")
                style.set("color", "white")
            } else {
                style.set("background", "rgba(255,255,255,0.1)")
                style.set("color", "rgba(255,255,255,0.7)")
            }
            element.addEventListener("click") { onClick() }
        }
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy")
    }
}

private enum class FamilySortMode(val label: String) {
    DATE_DESC("Newest"),
    DATE_ASC("Oldest"),
    NAME("Name"),
    RECENTLY_ADDED("Recent")
}
