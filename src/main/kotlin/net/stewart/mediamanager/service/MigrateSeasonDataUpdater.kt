package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AcquisitionStatus
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaItemTitleSeason
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleSeason
import org.slf4j.LoggerFactory

/**
 * Migrates freetext media_item_title.seasons data to the structured
 * media_item_title_season join table and title_season acquisition_status.
 * Also creates pseudo-season rows (season_number=0) for movie titles.
 */
class MigrateSeasonDataUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(MigrateSeasonDataUpdater::class.java)

    override val name = "migrate_season_data"
    override val version = 1

    override fun run() {
        createMoviePseudoSeasons()
        migrateFreetextSeasons()
    }

    /**
     * Creates title_season rows with season_number=0 for movie titles
     * that have media_item_title joins (i.e., we own the movie).
     */
    private fun createMoviePseudoSeasons() {
        val existingMovieSeasons = TitleSeason.findAll()
            .filter { it.season_number == 0 }
            .map { it.title_id }
            .toSet()

        // Find movie titles that have physical media linked
        val movieTitleIds = MediaItemTitle.findAll().map { it.title_id }.toSet()
        val movieTitles = Title.findAll().filter {
            it.media_type == "MOVIE" && it.id in movieTitleIds && it.id !in existingMovieSeasons
        }

        var created = 0
        for (title in movieTitles) {
            val ts = TitleSeason(
                title_id = title.id!!,
                season_number = 0,
                acquisition_status = AcquisitionStatus.OWNED.name
            )
            ts.save()

            // Link all media_item_title joins for this movie to the pseudo-season
            val joins = MediaItemTitle.findAll().filter { it.title_id == title.id }
            for (join in joins) {
                MediaItemTitleSeason(
                    media_item_title_id = join.id!!,
                    title_season_id = ts.id!!
                ).save()
            }
            created++
        }

        if (created > 0) {
            log.info("Created {} movie pseudo-season rows", created)
        }
    }

    /**
     * Parses freetext media_item_title.seasons values and creates structured
     * media_item_title_season join rows. Sets matching title_season rows to OWNED.
     */
    private fun migrateFreetextSeasons() {
        val joins = MediaItemTitle.findAll().filter { !it.seasons.isNullOrBlank() }
        if (joins.isEmpty()) return

        var migrated = 0
        var needsAssistance = 0

        for (join in joins) {
            val title = Title.findById(join.title_id) ?: continue
            if (title.media_type != "TV") continue

            val seasonNumbers = parseSeasons(join.seasons!!, join.title_id)
            if (seasonNumbers == null) {
                // Unparseable — mark all seasons for this title as NEEDS_ASSISTANCE
                log.warn("Cannot parse seasons '{}' for title '{}' (id={})",
                    join.seasons, title.name, title.id)
                needsAssistance++
                continue
            }

            for (num in seasonNumbers) {
                // Find or create the title_season row
                var ts = TitleSeason.findAll().firstOrNull {
                    it.title_id == join.title_id && it.season_number == num
                }
                if (ts == null) {
                    ts = TitleSeason(
                        title_id = join.title_id,
                        season_number = num,
                        acquisition_status = AcquisitionStatus.OWNED.name
                    )
                    ts.save()
                } else if (ts.acquisition_status == AcquisitionStatus.UNKNOWN.name) {
                    ts.acquisition_status = AcquisitionStatus.OWNED.name
                    ts.save()
                }

                // Create join row if not already present
                val exists = MediaItemTitleSeason.findAll().any {
                    it.media_item_title_id == join.id && it.title_season_id == ts.id
                }
                if (!exists) {
                    MediaItemTitleSeason(
                        media_item_title_id = join.id!!,
                        title_season_id = ts.id!!
                    ).save()
                }
            }
            migrated++
        }

        log.info("Season freetext migration: {} migrated, {} need manual assistance", migrated, needsAssistance)
    }

    /**
     * Parses a freetext seasons value into a list of season numbers.
     * Returns null if the value cannot be parsed.
     */
    private fun parseSeasons(text: String, titleId: Long): List<Int>? {
        val trimmed = text.trim()

        // "all" → all known seasons for this title
        if (trimmed.equals("all", ignoreCase = true)) {
            val known = TitleSeason.findAll()
                .filter { it.title_id == titleId && it.season_number > 0 }
                .map { it.season_number }
            return if (known.isNotEmpty()) known else null
        }

        // Single number: "3"
        trimmed.toIntOrNull()?.let { return listOf(it) }

        // Range: "1-3"
        val rangeMatch = Regex("""^(\d+)\s*-\s*(\d+)$""").matchEntire(trimmed)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toInt()
            val end = rangeMatch.groupValues[2].toInt()
            if (start <= end && end - start < 50) return (start..end).toList()
        }

        // Comma-separated with optional S prefix: "S1, S2, S3" or "1,2,3"
        val parts = trimmed.split(",").map { it.trim().removePrefix("S").removePrefix("s") }
        if (parts.all { it.toIntOrNull() != null }) {
            return parts.map { it.toInt() }
        }

        return null
    }
}
