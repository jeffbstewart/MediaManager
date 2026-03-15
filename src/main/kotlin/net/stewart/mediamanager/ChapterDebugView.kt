package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.*

/** Debug view for inspecting chapter extraction results (admin-only route). */
@Route(value = "debug/chapters", layout = MainLayout::class)
@PageTitle("Chapter Debug")
class ChapterDebugView : VerticalLayout() {

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true

        val allChapters = Chapter.findAll()
        val allSkipSegments = SkipSegment.findAll()
        val transcodes = Transcode.findAll().associateBy { it.id }
        val titles = Title.findAll().associateBy { it.id }
        val episodes = Episode.findAll().associateBy { it.id }

        // Summary stats
        val transcodeIdsWithChapters = allChapters.map { it.transcode_id }.distinct()
        val transcodeIdsWithSkips = allSkipSegments.map { it.transcode_id }.distinct()

        // Count completed CHAPTERS leases (includes files with no chapters)
        val completedChapterLeases = TranscodeLease.findAll()
            .filter { it.lease_type == LeaseType.CHAPTERS.name && it.status == LeaseStatus.COMPLETED.name }
        val probed = completedChapterLeases.map { it.transcode_id }.distinct().size
        val probedWithChapters = transcodeIdsWithChapters.size
        val probedWithoutChapters = probed - probedWithChapters

        add(H3("Chapter Extraction Debug"))
        add(Span("Transcodes probed: $probed | With chapters: $probedWithChapters | Without chapters: $probedWithoutChapters | Total chapter rows: ${allChapters.size} | Skip segments: ${allSkipSegments.size}"))

        // Chapter distribution: how many chapters per transcode?
        val chapterCounts = allChapters.groupBy { it.transcode_id }.mapValues { it.value.size }
        val distribution = chapterCounts.values.groupBy { it }.mapValues { it.value.size }.toSortedMap()
        if (distribution.isNotEmpty()) {
            add(Span("Chapter count distribution: ${distribution.entries.joinToString(", ") { "${it.key} chapters: ${it.value} files" }}"))
        }

        // Build flat rows for the grid
        data class ChapterRow(
            val transcodeId: Long,
            val titleName: String,
            val fileName: String,
            val chapterNumber: Int,
            val startSeconds: Double,
            val endSeconds: Double,
            val durationSeconds: Double,
            val chapterTitle: String?
        )

        val rows = allChapters.sortedWith(compareBy({ it.transcode_id }, { it.chapter_number })).map { ch ->
            val tc = transcodes[ch.transcode_id]
            val title = tc?.let { titles[it.title_id] }
            val episode = tc?.episode_id?.let { episodes[it] }
            val titleName = buildString {
                append(title?.name ?: "Unknown")
                if (episode != null) {
                    append(" S%02dE%02d".format(episode.season_number, episode.episode_number))
                }
            }
            val fileName = tc?.file_path?.substringAfterLast('/') ?: "?"
            ChapterRow(
                transcodeId = ch.transcode_id,
                titleName = titleName,
                fileName = fileName,
                chapterNumber = ch.chapter_number,
                startSeconds = ch.start_seconds,
                endSeconds = ch.end_seconds,
                durationSeconds = ch.end_seconds - ch.start_seconds,
                chapterTitle = ch.title
            )
        }

        val grid = Grid(ChapterRow::class.java, false)
        grid.addColumn({ it.transcodeId.toString() }).setHeader("TC ID").setWidth("70px").setFlexGrow(0)
        grid.addColumn({ it.titleName }).setHeader("Title").setFlexGrow(2)
        grid.addColumn({ it.fileName }).setHeader("File").setFlexGrow(2)
        grid.addColumn({ it.chapterNumber.toString() }).setHeader("#").setWidth("40px").setFlexGrow(0)
        grid.addColumn({ formatTime(it.startSeconds) }).setHeader("Start").setWidth("80px").setFlexGrow(0)
        grid.addColumn({ formatTime(it.endSeconds) }).setHeader("End").setWidth("80px").setFlexGrow(0)
        grid.addColumn({ formatTime(it.durationSeconds) }).setHeader("Dur").setWidth("80px").setFlexGrow(0)
        grid.addColumn({ it.chapterTitle ?: "" }).setHeader("Title").setFlexGrow(1)
        grid.setItems(rows)
        grid.isAllRowsVisible = rows.size <= 200
        if (rows.size > 200) grid.height = "600px"
        grid.setSizeFull()
        add(grid)

        // Skip segments section
        if (allSkipSegments.isNotEmpty()) {
            data class SkipRow(
                val transcodeId: Long,
                val titleName: String,
                val segmentType: String,
                val startSeconds: Double,
                val endSeconds: Double,
                val durationSeconds: Double,
                val detectionMethod: String?
            )

            val skipRows = allSkipSegments.map { seg ->
                val tc = transcodes[seg.transcode_id]
                val title = tc?.let { titles[it.title_id] }
                val episode = tc?.episode_id?.let { episodes[it] }
                val titleName = buildString {
                    append(title?.name ?: "Unknown")
                    if (episode != null) {
                        append(" S%02dE%02d".format(episode.season_number, episode.episode_number))
                    }
                }
                SkipRow(seg.transcode_id, titleName, seg.segment_type,
                    seg.start_seconds, seg.end_seconds, seg.end_seconds - seg.start_seconds, seg.detection_method)
            }

            add(H3("Skip Segments"))
            val skipGrid = Grid(SkipRow::class.java, false)
            skipGrid.addColumn({ it.transcodeId.toString() }).setHeader("TC ID").setWidth("70px").setFlexGrow(0)
            skipGrid.addColumn({ it.titleName }).setHeader("Title").setFlexGrow(2)
            skipGrid.addColumn({ it.segmentType }).setHeader("Type").setWidth("100px").setFlexGrow(0)
            skipGrid.addColumn({ formatTime(it.startSeconds) }).setHeader("Start").setWidth("80px").setFlexGrow(0)
            skipGrid.addColumn({ formatTime(it.endSeconds) }).setHeader("End").setWidth("80px").setFlexGrow(0)
            skipGrid.addColumn({ formatTime(it.durationSeconds) }).setHeader("Dur").setWidth("80px").setFlexGrow(0)
            skipGrid.addColumn({ it.detectionMethod ?: "" }).setHeader("Method").setWidth("100px").setFlexGrow(0)
            skipGrid.setItems(skipRows)
            skipGrid.isAllRowsVisible = true
            skipGrid.setWidthFull()
            add(skipGrid)
        }
    }

    private fun formatTime(seconds: Double): String {
        val totalSecs = seconds.toInt()
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
