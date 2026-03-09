package net.stewart.mediamanager

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.PlaybackProgress

/**
 * Shared helper for building poster grid cards across Movies, TV Shows, Browse, etc.
 * Avoids duplicating the poster card layout in every content view.
 */
object PosterGridHelper {

    /** Creates a responsive CSS grid container for poster cards. */
    fun createPosterGrid(): Div = Div().apply {
        style.set("display", "grid")
        style.set("grid-template-columns", "repeat(auto-fill, minmax(140px, 1fr))")
        style.set("gap", "var(--lumo-space-m)")
        style.set("width", "100%")
    }

    /** Builds a single poster card with optional playable indicator and progress bar. */
    fun buildPosterCard(
        title: Title,
        playableTitleIds: Set<Long>,
        progressByTitle: Map<Long, PlaybackProgress>
    ): Div {
        return Div().apply {
            style.set("cursor", "pointer")
            style.set("text-align", "center")

            element.addEventListener("click") {
                ui.ifPresent { it.navigate("title/${title.id}") }
            }

            val posterContainer = Div().apply {
                style.set("position", "relative")
                style.set("width", "100%")
                style.set("aspect-ratio", "2/3")
                style.set("border-radius", "8px")
                style.set("overflow", "hidden")
                style.set("background", "rgba(255,255,255,0.05)")

                val posterUrl = title.posterUrl(PosterSize.THUMBNAIL)
                if (posterUrl != null) {
                    add(Image(posterUrl, title.name).apply {
                        width = "100%"
                        height = "100%"
                        style.set("object-fit", "cover")
                        style.set("display", "block")
                    })
                }

                if (title.id != null && title.id in playableTitleIds) {
                    add(Div().apply {
                        style.set("position", "absolute")
                        style.set("bottom", "6px")
                        style.set("right", "6px")
                        style.set("width", "24px")
                        style.set("height", "24px")
                        style.set("background", "rgba(0,0,0,0.6)")
                        style.set("border-radius", "50%")
                        style.set("display", "flex")
                        style.set("align-items", "center")
                        style.set("justify-content", "center")
                        add(Div().apply {
                            style.set("width", "0")
                            style.set("height", "0")
                            style.set("border-style", "solid")
                            style.set("border-width", "5px 0 5px 9px")
                            style.set("border-color", "transparent transparent transparent white")
                            style.set("margin-left", "2px")
                        })
                    })
                }

                val progress = title.id?.let { progressByTitle[it] }
                val dur = progress?.duration_seconds
                if (progress != null && dur != null && dur > 0.0) {
                    val pct = ((progress.position_seconds / dur) * 100)
                        .toInt().coerceIn(0, 100)
                    add(Div().apply {
                        style.set("position", "absolute")
                        style.set("bottom", "0")
                        style.set("left", "0")
                        style.set("width", "100%")
                        style.set("height", "3px")
                        style.set("background", "rgba(0,0,0,0.5)")
                        add(Div().apply {
                            style.set("width", "${pct}%")
                            style.set("height", "100%")
                            style.set("background", "var(--lumo-primary-color)")
                        })
                    })
                }
            }
            add(posterContainer)

            add(Span(title.name).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
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

    /** Computes the set of title IDs that have playable transcodes. */
    fun computePlayableTitleIds(): Set<Long> {
        val allTranscodes = net.stewart.mediamanager.entity.Transcode.findAll().filter { it.file_path != null }
        val nasRoot = net.stewart.mediamanager.service.TranscoderAgent.getNasRoot()
        return allTranscodes.filter { tc ->
            val fp = tc.file_path!!
            if (net.stewart.mediamanager.service.TranscoderAgent.needsTranscoding(fp)) {
                nasRoot != null && net.stewart.mediamanager.service.TranscoderAgent.isTranscoded(nasRoot, fp)
            } else {
                true
            }
        }.map { it.title_id }.toSet()
    }
}
