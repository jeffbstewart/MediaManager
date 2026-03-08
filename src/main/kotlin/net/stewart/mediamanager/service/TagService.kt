package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TagSourceType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleTag
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
        // Remove all title_tag associations first
        TitleTag.findAll().filter { it.tag_id == tagId }.forEach { it.delete() }
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

        // Collection tags: match title's TMDB collection against COLLECTION-sourced tags
        val collectionId = title.tmdb_collection_id
        if (collectionId != null) {
            val collectionTags = allTags.filter {
                it.source_type == TagSourceType.COLLECTION.name &&
                it.source_key == collectionId.toString()
            }
            for (tag in collectionTags) {
                addTagToTitle(title.id!!, tag.id!!)
            }
        }
    }

    /**
     * Creates a tag backed by a TMDB collection and auto-associates all matching titles.
     * Returns the created tag.
     */
    fun createCollectionTag(collectionId: Int, collectionName: String, bgColor: String, createdBy: Long? = null): Tag {
        val tag = Tag(
            name = collectionName,
            bg_color = bgColor,
            source_type = TagSourceType.COLLECTION.name,
            source_key = collectionId.toString(),
            created_by = createdBy,
            created_at = LocalDateTime.now()
        )
        tag.save()
        log.info("Collection tag created: id={} name=\"{}\" tmdb_collection={}", tag.id, tag.name, collectionId)

        // Auto-associate all titles with this collection
        val matchingTitles = Title.findAll().filter { it.tmdb_collection_id == collectionId }
        for (title in matchingTitles) {
            addTagToTitle(title.id!!, tag.id!!)
        }
        if (matchingTitles.isNotEmpty()) {
            log.info("Auto-associated {} titles with collection tag \"{}\"", matchingTitles.size, collectionName)
        }

        return tag
    }

    /**
     * Returns TMDB collections present in the catalog that don't have a corresponding tag.
     * Each entry is (collectionId, collectionName, titleCount).
     */
    fun getSuggestedCollections(): List<Triple<Int, String, Int>> {
        val existingCollectionKeys = Tag.findAll()
            .filter { it.source_type == TagSourceType.COLLECTION.name }
            .mapNotNull { it.source_key }
            .toSet()

        return Title.findAll()
            .filter { it.tmdb_collection_id != null && it.tmdb_collection_name != null }
            .groupBy { it.tmdb_collection_id!! }
            .filter { it.key.toString() !in existingCollectionKeys }
            .filter { it.value.size >= 2 }  // Only suggest when we have 2+ titles
            .map { (colId, titles) ->
                Triple(colId, titles.first().tmdb_collection_name!!, titles.size)
            }
            .sortedByDescending { it.third }
    }
}
