package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.*
import java.time.LocalDateTime

/**
 * Shared logic for adding a title to the catalog from a TMDB result.
 * Used by both the Vaadin AddItemView and the gRPC AdminService.
 */
object AddTitleService {

    data class AddTitleResult(
        val titleId: Long,
        val titleName: String,
        val alreadyExisted: Boolean
    )

    /**
     * Create or find a title by TMDB ID + media type, create a MediaItem for the physical disc,
     * and link them. Triggers enrichment for new titles and fulfills matching wishes.
     */
    fun addFromTmdb(
        tmdbId: Int,
        mediaType: MediaType,
        mediaFormat: MediaFormat,
        seasonsInput: String? = null
    ): AddTitleResult {
        val now = LocalDateTime.now()
        val tmdbKey = TmdbId(tmdbId, mediaType)

        // Look up title info from TMDB for initial metadata
        val tmdbService = TmdbService()
        val searchResult = try {
            tmdbService.getDetails(tmdbKey)
        } catch (_: Exception) {
            null
        }

        // Dedup: find existing title with same TMDB key
        var title = Title.findAll().firstOrNull { it.tmdbKey() == tmdbKey }
        val alreadyExisted = title != null

        if (title == null) {
            title = Title(
                name = searchResult?.title ?: "Unknown",
                media_type = tmdbKey.typeString,
                tmdb_id = tmdbKey.id,
                release_year = searchResult?.releaseYear,
                description = searchResult?.overview,
                poster_path = searchResult?.posterPath,
                enrichment_status = EnrichmentStatus.REASSIGNMENT_REQUESTED.name,
                created_at = now,
                updated_at = now
            )
            title.save()
            SearchIndexService.onTitleChanged(title.id!!)
        }

        // Create the physical media item
        val mediaItem = MediaItem(
            media_format = mediaFormat.name,
            entry_source = EntrySource.MANUAL.name,
            product_name = searchResult?.title ?: title.name,
            title_count = 1,
            expansion_status = ExpansionStatus.SINGLE.name,
            created_at = now,
            updated_at = now
        )
        mediaItem.save()

        // Parse seasons for TV shows
        val seasonsValue = seasonsInput?.let { parseSeasonsInput(it) }

        val join = MediaItemTitle(
            media_item_id = mediaItem.id!!,
            title_id = title.id!!,
            disc_number = 1,
            seasons = seasonsValue
        )
        join.save()

        WishListService.syncPhysicalOwnership(title.id!!)
        WishListService.fulfillMediaWishes(tmdbKey)

        return AddTitleResult(
            titleId = title.id!!,
            titleName = title.name,
            alreadyExisted = alreadyExisted
        )
    }

    /**
     * Parse seasons input like "1", "1,2,3", "1-3", "S1, S2" into normalized "S1, S2" format.
     */
    fun parseSeasonsInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val rangeMatch = Regex("""^(\d+)\s*-\s*(\d+)$""").matchEntire(trimmed)
        return if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toInt()
            val end = rangeMatch.groupValues[2].toInt()
            (start..end).joinToString(", ") { "S$it" }
        } else {
            val parts = trimmed.split(",").map { it.trim().removePrefix("S").removePrefix("s") }
                .filter { it.isNotEmpty() }
            if (parts.all { it.toIntOrNull() != null }) {
                parts.joinToString(", ") { "S$it" }
            } else {
                trimmed.toIntOrNull()?.let { "S$it" }
            }
        }
    }
}
