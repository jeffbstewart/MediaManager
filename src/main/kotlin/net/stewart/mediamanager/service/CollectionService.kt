package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.TmdbCollectionPart
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Manages TMDB collection storage and collection-aware sort name generation.
 *
 * Titles belonging to a TMDB collection get sort names like "Back to the Future Collection 001"
 * so they sort together in release order. The collection name has leading articles stripped
 * (same logic as TitleCleanerService) so "The Lord of the Rings Collection" sorts under "L".
 */
object CollectionService {
    private val log = LoggerFactory.getLogger(CollectionService::class.java)

    private val LEADING_ARTICLE = Regex("""^(The|A|An)\s+""", RegexOption.IGNORE_CASE)

    /**
     * Stores a fetched TMDB collection and its parts.
     * Upserts: if the collection already exists, updates metadata and replaces parts.
     */
    fun storeCollection(result: TmdbCollectionResult): TmdbCollection? {
        if (!result.found || result.collectionId == null) return null

        var collection = TmdbCollection.findAll()
            .firstOrNull { it.tmdb_collection_id == result.collectionId }

        if (collection != null) {
            collection.name = result.name ?: collection.name
            collection.poster_path = result.posterPath
            collection.backdrop_path = result.backdropPath
            collection.fetched_at = LocalDateTime.now()
            collection.save()

            // Replace existing parts
            TmdbCollectionPart.findAll()
                .filter { it.collection_id == collection.id }
                .forEach { it.delete() }
        } else {
            collection = TmdbCollection(
                tmdb_collection_id = result.collectionId,
                name = result.name ?: "Unknown Collection",
                poster_path = result.posterPath,
                backdrop_path = result.backdropPath,
                fetched_at = LocalDateTime.now()
            )
            collection.save()
        }

        for (part in result.parts) {
            TmdbCollectionPart(
                collection_id = collection.id!!,
                tmdb_movie_id = part.tmdbMovieId,
                title = part.title,
                position = part.position,
                release_date = part.releaseDate
            ).save()
        }

        log.info("Stored collection \"{}\" (tmdb={}) with {} parts",
            collection.name, collection.tmdb_collection_id, result.parts.size)
        return collection
    }

    /**
     * Generates a collection-aware sort name for a title.
     * Returns null if the title is not in any stored collection.
     *
     * Format: "<article-stripped collection name> <NNN>" (3-digit zero-padded position)
     */
    fun collectionSortName(title: Title): String? {
        val collectionTmdbId = title.tmdb_collection_id ?: return null
        val titleTmdbId = title.tmdb_id ?: return null

        val collection = TmdbCollection.findAll()
            .firstOrNull { it.tmdb_collection_id == collectionTmdbId } ?: return null

        val part = TmdbCollectionPart.findAll()
            .firstOrNull { it.collection_id == collection.id && it.tmdb_movie_id == titleTmdbId }
            ?: return null

        val baseName = LEADING_ARTICLE.replace(collection.name, "").trim()
        return "$baseName ${part.position.toString().padStart(3, '0')}"
    }

    /**
     * Updates sort names for all titles belonging to a given TMDB collection.
     */
    fun updateSortNamesForCollection(tmdbCollectionId: Int) {
        val titles = Title.findAll().filter { it.tmdb_collection_id == tmdbCollectionId }
        var updated = 0
        for (title in titles) {
            val sortName = collectionSortName(title)
            if (sortName != null && title.sort_name != sortName) {
                title.sort_name = sortName
                title.save()
                updated++
            }
        }
        if (updated > 0) {
            log.info("Updated sort names for {} titles in collection tmdb={}", updated, tmdbCollectionId)
        }
    }

    fun findByTmdbId(tmdbCollectionId: Int): TmdbCollection? =
        TmdbCollection.findAll().firstOrNull { it.tmdb_collection_id == tmdbCollectionId }
}
