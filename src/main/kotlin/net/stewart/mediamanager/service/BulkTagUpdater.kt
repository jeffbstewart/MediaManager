package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory

/**
 * Seeds genre tags at startup based on TMDB genre data already in the DB.
 *
 * Idempotent: skips existing tags and title_tag rows. Version-tracked via SchemaUpdater framework.
 */
class BulkTagUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(BulkTagUpdater::class.java)

    override val name = "bulk_tag_seed"
    override val version = 2

    // --- Tag definitions ---

    private data class GenreTagDef(val tagName: String, val color: String, val tmdbGenres: List<String>)

    private val genreTagDefs = listOf(
        GenreTagDef("Sci-Fi", "#2196F3", listOf("Science Fiction")),
        GenreTagDef("Action", "#F44336", listOf("Action")),
        GenreTagDef("Comedy", "#FFEB3B", listOf("Comedy")),
        GenreTagDef("Fantasy", "#9C27B0", listOf("Fantasy")),
        GenreTagDef("Family", "#4CAF50", listOf("Family")),
        GenreTagDef("Period / Historical", "#795548", listOf("History", "War")),
        GenreTagDef("Documentary", "#607D8B", listOf("Documentary")),
    )

    override fun run() {
        val allTitles = Title.findAll()
        val genres = Genre.findAll().associateBy { it.id!! }
        val titleGenres = TitleGenre.findAll()
        val existingTags = Tag.findAll().associateBy { it.name }.toMutableMap()
        val existingTitleTags = TitleTag.findAll().map { it.title_id to it.tag_id }.toSet()

        // Build TMDB genre lookup: title_id -> set of genre names (lowercase)
        val tmdbGenresByTitle = titleGenres.groupBy { it.title_id }
            .mapValues { (_, tgs) -> tgs.mapNotNull { genres[it.genre_id]?.name?.lowercase() }.toSet() }

        var tagsCreated = 0
        var assignmentsCreated = 0

        fun ensureTag(tagName: String, color: String): Tag {
            existingTags[tagName]?.let { return it }
            val tag = Tag(name = tagName, bg_color = color)
            tag.save()
            existingTags[tagName] = tag
            tagsCreated++
            return tag
        }

        fun assignTitles(tag: Tag, titleIds: Collection<Long>) {
            val tagId = tag.id!!
            for (titleId in titleIds) {
                if ((titleId to tagId) in existingTitleTags) continue
                TitleTag(title_id = titleId, tag_id = tagId).save()
                assignmentsCreated++
            }
        }

        // Genre tags: match via TMDB genre data
        for (def in genreTagDefs) {
            val tag = ensureTag(def.tagName, def.color)
            val tmdbNames = def.tmdbGenres.map { it.lowercase() }.toSet()
            val matchingIds = allTitles
                .filter { title -> tmdbGenresByTitle[title.id]?.any { it in tmdbNames } == true }
                .mapNotNull { it.id }
            assignTitles(tag, matchingIds)
            log.info("Tag '{}': {} titles", def.tagName, matchingIds.size)
        }

        SearchIndexService.onTagChanged()
        log.info("Bulk tag seeding complete: {} tags created, {} assignments created", tagsCreated, assignmentsCreated)
    }
}
