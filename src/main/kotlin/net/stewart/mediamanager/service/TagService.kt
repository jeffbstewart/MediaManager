package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TagSourceType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.entity.TrackTag
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

object TagService {
    private val log = LoggerFactory.getLogger(TagService::class.java)

    /** Named color palette for tag creation UI. */
    val COLOR_PALETTE: List<Pair<String, String>> = listOf(
        "Red" to "#EF4444",
        "Orange" to "#F97316",
        "Amber" to "#F59E0B",
        "Yellow" to "#EAB308",
        "Lime" to "#84CC16",
        "Green" to "#22C55E",
        "Emerald" to "#10B981",
        "Teal" to "#14B8A6",
        "Cyan" to "#06B6D4",
        "Sky" to "#0EA5E9",
        "Blue" to "#3B82F6",
        "Indigo" to "#6366F1",
        "Violet" to "#8B5CF6",
        "Purple" to "#A855F7",
        "Pink" to "#EC4899",
        "Stone" to "#78716C"
    )

    fun createTag(name: String, bgColor: String, createdBy: Long? = null): Tag {
        val tag = Tag(
            name = name.trim(),
            bg_color = bgColor,
            created_by = createdBy,
            created_at = LocalDateTime.now()
        )
        tag.save()
        log.info("Tag created: id={} name=\"{}\" color={}", tag.id, tag.name, tag.bg_color)
        return tag
    }

    fun updateTag(tagId: Long, name: String, bgColor: String): Tag? {
        val tag = Tag.findById(tagId) ?: return null
        tag.name = name.trim()
        tag.bg_color = bgColor
        tag.save()
        log.info("Tag updated: id={} name=\"{}\" color={}", tag.id, tag.name, tag.bg_color)
        SearchIndexService.onTagChanged()
        return tag
    }

    fun deleteTag(tagId: Long) {
        // Remove all title_tag and track_tag associations first. The DB
        // would cascade these via the FK, but doing it here keeps the
        // search index notification consistent with the explicit delete
        // path used elsewhere.
        TitleTag.findAll().filter { it.tag_id == tagId }.forEach { it.delete() }
        TrackTag.findAll().filter { it.tag_id == tagId }.forEach { it.delete() }
        Tag.deleteById(tagId)
        log.info("Tag deleted: id={}", tagId)
        SearchIndexService.onTagChanged()
    }

    fun getAllTags(): List<Tag> = Tag.findAll().sortedBy { it.name.lowercase() }

    fun addTagToTitle(titleId: Long, tagId: Long) {
        // Idempotent — skip if already exists
        val exists = TitleTag.findAll().any { it.title_id == titleId && it.tag_id == tagId }
        if (exists) return
        TitleTag(title_id = titleId, tag_id = tagId, created_at = LocalDateTime.now()).save()
        SearchIndexService.onTagChanged()
    }

    fun removeTagFromTitle(titleId: Long, tagId: Long) {
        TitleTag.findAll()
            .filter { it.title_id == titleId && it.tag_id == tagId }
            .forEach { it.delete() }
        SearchIndexService.onTagChanged()
    }

    fun getTagsForTitle(titleId: Long): List<Tag> {
        val tagIds = TitleTag.findAll().filter { it.title_id == titleId }.map { it.tag_id }.toSet()
        if (tagIds.isEmpty()) return emptyList()
        return Tag.findAll().filter { it.id in tagIds }.sortedBy { it.name.lowercase() }
    }

    /** Returns title IDs that have ANY of the given tags (OR logic). */
    fun getTitleIdsForTags(tagIds: Set<Long>): Set<Long> {
        if (tagIds.isEmpty()) return emptySet()
        return TitleTag.findAll()
            .filter { it.tag_id in tagIds }
            .map { it.title_id }
            .toSet()
    }

    /** Returns tag_id -> count of associated titles. */
    fun getTagTitleCounts(): Map<Long, Int> {
        return TitleTag.findAll()
            .groupBy { it.tag_id }
            .mapValues { it.value.size }
    }

    // ============================================================
    // Track-level tags (Tags phase B). Independent surface — a track
    // can carry tags that its parent album doesn't, and vice versa.
    // ============================================================

    fun addTagToTrack(trackId: Long, tagId: Long) {
        if (Track.findById(trackId) == null) return
        if (Tag.findById(tagId) == null) return
        val exists = TrackTag.findAll().any { it.track_id == trackId && it.tag_id == tagId }
        if (exists) return
        TrackTag(track_id = trackId, tag_id = tagId, created_at = LocalDateTime.now()).save()
        SearchIndexService.onTagChanged()
    }

    fun removeTagFromTrack(trackId: Long, tagId: Long) {
        TrackTag.findAll()
            .filter { it.track_id == trackId && it.tag_id == tagId }
            .forEach { it.delete() }
        SearchIndexService.onTagChanged()
    }

    fun getTagsForTrack(trackId: Long): List<Tag> {
        val tagIds = TrackTag.findAll().filter { it.track_id == trackId }.map { it.tag_id }.toSet()
        if (tagIds.isEmpty()) return emptyList()
        return Tag.findAll().filter { it.id in tagIds }.sortedBy { it.name.lowercase() }
    }

    /** OR semantics — tracks with any of the given tags. */
    fun getTrackIdsForTags(tagIds: Set<Long>): Set<Long> {
        if (tagIds.isEmpty()) return emptySet()
        return TrackTag.findAll()
            .filter { it.tag_id in tagIds }
            .map { it.track_id }
            .toSet()
    }

    /**
     * Tracks matching any of [tagIds] *plus* all tracks belonging to
     * albums tagged with any of [tagIds] (query-time inheritance).
     * Drives the "tagged tracks" section on tag detail pages so
     * browsing a `Rock` tag surfaces both explicit track tags and
     * every track on a Rock-tagged album, without write-amplifying
     * the inheritance into stored track_tag rows.
     */
    fun getTrackIdsForTagsWithInheritance(tagIds: Set<Long>): Set<Long> {
        if (tagIds.isEmpty()) return emptySet()
        val direct = getTrackIdsForTags(tagIds)
        val inheritedTitleIds = getTitleIdsForTags(tagIds)
        if (inheritedTitleIds.isEmpty()) return direct
        val inheritedTrackIds = Track.findAll()
            .filter { it.title_id in inheritedTitleIds }
            .mapNotNull { it.id }
            .toSet()
        return direct + inheritedTrackIds
    }

    /** tag_id -> count of associated tracks. */
    fun getTagTrackCounts(): Map<Long, Int> {
        return TrackTag.findAll()
            .groupBy { it.tag_id }
            .mapValues { it.value.size }
    }

    fun isNameUnique(name: String, excludeTagId: Long? = null): Boolean {
        val trimmed = name.trim().lowercase()
        return Tag.findAll().none {
            it.name.lowercase() == trimmed && it.id != excludeTagId
        }
    }

    /**
     * Auto-associate a title with genre and collection tags after enrichment.
     * Called from TmdbEnrichmentAgent after a title is enriched.
     */
    fun autoAssociateOnEnrichment(title: Title) {
        val allTags = Tag.findAll()

        // Genre tags: match title's TMDB genres against GENRE-sourced tags
        val genreTags = allTags.filter { it.source_type == TagSourceType.GENRE.name }
        if (genreTags.isNotEmpty()) {
            val titleGenreNames = TitleGenre.findAll()
                .filter { it.title_id == title.id }
                .mapNotNull { tg -> Genre.findById(tg.genre_id)?.name }
                .toSet()

            for (tag in genreTags) {
                if (tag.source_key != null && tag.source_key in titleGenreNames) {
                    addTagToTitle(title.id!!, tag.id!!)
                }
            }
        }

    }
}
