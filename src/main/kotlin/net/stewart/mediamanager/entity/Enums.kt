package net.stewart.mediamanager.entity

enum class MediaFormat {
    DVD, BLURAY, UHD_BLURAY, HD_DVD,
    // Book formats — see docs/BOOKS.md.
    MASS_MARKET_PAPERBACK, TRADE_PAPERBACK, HARDBACK,
    EBOOK_EPUB, EBOOK_PDF,
    AUDIOBOOK_CD, AUDIOBOOK_DIGITAL,
    // Music formats — see docs/MUSIC.md.
    CD, VINYL_LP,
    AUDIO_FLAC, AUDIO_MP3, AUDIO_AAC, AUDIO_OGG, AUDIO_WAV,
    UNKNOWN, OTHER;

    companion object {
        /** Formats that represent a physical or digital book edition. */
        val BOOK_FORMATS: Set<MediaFormat> = setOf(
            MASS_MARKET_PAPERBACK, TRADE_PAPERBACK, HARDBACK,
            EBOOK_EPUB, EBOOK_PDF,
            AUDIOBOOK_CD, AUDIOBOOK_DIGITAL
        )

        /** Physical music formats (count toward insurance replacement value). */
        val PHYSICAL_MUSIC_FORMATS: Set<MediaFormat> = setOf(CD, VINYL_LP)

        /** Digital-audio rip formats (excluded from insurance the same way ebooks are). */
        val DIGITAL_AUDIO_FORMATS: Set<MediaFormat> = setOf(
            AUDIO_FLAC, AUDIO_MP3, AUDIO_AAC, AUDIO_OGG, AUDIO_WAV
        )
    }
}

enum class MediaType {
    MOVIE, TV, PERSONAL, BOOK, ALBUM
}

/** MusicBrainz 'type' field on an artist. Drives UI (band vs person) and which relationships apply. */
enum class ArtistType {
    PERSON, GROUP, ORCHESTRA, CHOIR, OTHER
}

/** Role carried on a recording_credit row — who-did-what on a specific track. */
enum class CreditRole {
    PERFORMER, COMPOSER, PRODUCER, ENGINEER, MIXER, OTHER
}

/** Whether a book_series poster was auto-set from volume 1 or chosen manually. */
enum class PosterSource { AUTO, MANUAL }

enum class TagSourceType {
    MANUAL,      // User-created tag, manually populated
    GENRE,       // TMDB genre (video) or ID3 genre (audio); auto-populated on enrichment / ingestion
    COLLECTION,  // Backed by a TMDB collection ID; auto-populated on enrichment (legacy, no longer created)
    EVENT_TYPE,  // Pre-seeded event categories for personal/home videos
    // Music auto-tags (V089 +). Tag.source_key carries the canonical
    // lowercase value for dedup — e.g. "post-punk" for a STYLE tag.
    STYLE,       // ID3 TXXX:Style / Vorbis STYLE
    BPM_BUCKET,  // "80-100" etc., derived from the raw BPM tag
    DECADE,      // "1980s", derived from release year
    TIME_SIG     // "3/4" / "4/4" / "6/8", from a TXXX:TIME_SIGNATURE or manual override
}

enum class ItemCondition {
    MINT, EXCELLENT, GOOD, FAIR, POOR
}

enum class TranscodeStatus {
    NOT_STARTED, PENDING, IN_PROGRESS, COMPLETE, NOT_FEASIBLE, DEFECTIVE
}

enum class LookupStatus {
    NOT_LOOKED_UP, FOUND, NOT_FOUND
}

/**
 * Lifecycle of title enrichment by TmdbEnrichmentAgent.
 *
 *   PENDING → (agent processes) → ENRICHED | SKIPPED | FAILED
 *   FAILED  → (retry after backoff) → ENRICHED | SKIPPED | FAILED | ABANDONED
 *   REASSIGNMENT_REQUESTED → (agent fetches by TMDB ID) → ENRICHED | SKIPPED | FAILED
 */
enum class EnrichmentStatus {
    PENDING,                   // New title, awaiting initial enrichment
    ENRICHED,                  // Successfully enriched (TMDB match found)
    SKIPPED,                   // No TMDB key configured, or no TMDB match; title was cleaned only
    FAILED,                    // TMDB API error; will be retried with exponential backoff
    REASSIGNMENT_REQUESTED,    // User manually set a different tmdb_id; agent will re-fetch
    ABANDONED                  // Retries exhausted (11+ consecutive failures); needs manual intervention
}

/**
 * Lifecycle of multi-pack expansion for media items containing multiple titles.
 *
 *   SINGLE → default for single-title products
 *   NEEDS_EXPANSION → detected as multi-pack during UPC lookup; awaiting user action
 *   EXPANDED → user has identified and linked individual titles
 */
enum class ExpansionStatus {
    SINGLE,
    NEEDS_EXPANSION,
    EXPANDED
}

enum class DiscoveredFileStatus {
    UNMATCHED, MATCHED, LINKED, IGNORED
}

enum class MatchMethod {
    AUTO_EXACT, AUTO_NORMALIZED, MANUAL
}

enum class WishType { MEDIA, TRANSCODE, BOOK, ALBUM }

enum class WishStatus { ACTIVE, CANCELLED, FULFILLED, DISMISSED }

enum class AcquisitionStatus { UNKNOWN, NOT_AVAILABLE, REJECTED, ORDERED, OWNED, NEEDS_ASSISTANCE }

enum class EntrySource { UPC_SCAN, MANUAL }

enum class UserFlagType { STARRED, HIDDEN, VIEWED }

enum class LeaseStatus { CLAIMED, IN_PROGRESS, COMPLETED, FAILED, EXPIRED }

enum class LeaseType { TRANSCODE, THUMBNAILS, SUBTITLES, CHAPTERS, MOBILE_TRANSCODE }

enum class ReportStatus { OPEN, RESOLVED, DISMISSED }

/**
 * Content ratings from TMDB (MPAA for movies, TV Parental Guidelines for TV shows).
 *
 * Each entry carries an ordinal_level for cross-system comparison:
 *   0=TV-Y, 1=TV-Y7, 2=G/TV-G, 3=PG/TV-PG, 4=PG-13/TV-14, 5=R/TV-MA, 6=NC-17
 *
 * Rules for ceiling enforcement:
 *   - Admin users always see everything (bypass)
 *   - null ceiling = unrestricted (sees everything)
 *   - Unrated titles (content_rating IS NULL) are hidden from ceiling-limited accounts
 */
enum class ContentRating(val displayLabel: String, val ordinalLevel: Int) {
    TV_Y("TV-Y", 0),
    TV_Y7("TV-Y7", 1),
    G("G", 2),
    TV_G("TV-G", 2),
    PG("PG", 3),
    TV_PG("TV-PG", 3),
    PG_13("PG-13", 4),
    TV_14("TV-14", 4),
    R("R", 5),
    TV_MA("TV-MA", 5),
    NC_17("NC-17", 6);

    companion object {
        /** Parses a TMDB certification string (e.g. "PG-13", "TV-MA") into a ContentRating. */
        fun fromTmdbCertification(cert: String?): ContentRating? {
            if (cert.isNullOrBlank()) return null
            val normalized = cert.trim().uppercase().replace(" ", "_").replace("-", "_")
            return entries.firstOrNull { it.name == normalized }
                ?: entries.firstOrNull { it.displayLabel.equals(cert.trim(), ignoreCase = true) }
        }

        /**
         * Returns ordered ceiling choices for UI dropdowns.
         * Each choice is a pair of (ordinal_level, display label) with duplicates merged.
         */
        fun ceilingChoices(): List<Pair<Int, String>> = listOf(
            0 to "TV-Y",
            1 to "TV-Y7",
            2 to "G / TV-G",
            3 to "PG / TV-PG",
            4 to "PG-13 / TV-14",
            5 to "R / TV-MA",
            6 to "NC-17"
        )

        /** Returns a display label for a given ordinal level. */
        fun ceilingLabel(ordinalLevel: Int): String =
            ceilingChoices().firstOrNull { it.first == ordinalLevel }?.second ?: "Unknown"
    }
}

/**
 * Type-safe wrapper for a user's content rating ceiling (max ordinal level they can see).
 *
 * Stored as an integer in the DB (`app_user.rating_ceiling`) but wrapped here to avoid
 * confusing bare ints with ordinal levels. Provides [allows] to check whether a content
 * rating passes the ceiling, and [label] for display.
 */
@JvmInline
value class RatingCeiling(val ordinalLevel: Int) {

    /** Returns true if the given [ContentRating] is at or below this ceiling. */
    fun allows(rating: ContentRating): Boolean = rating.ordinalLevel <= ordinalLevel

    /** Display label for this ceiling level (e.g. "PG-13 / TV-14"). */
    val label: String get() = ContentRating.ceilingLabel(ordinalLevel)

    companion object {
        /** Wraps a nullable DB integer into a [RatingCeiling], or null if unrestricted. */
        fun fromDb(ordinalLevel: Int?): RatingCeiling? = ordinalLevel?.let { RatingCeiling(it) }
    }
}
