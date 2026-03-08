package net.stewart.mediamanager.service

/**
 * Result of cleaning a raw UPCitemdb product title.
 *
 * @property displayName The human-readable title (e.g. "The Golden Compass")
 * @property sortName    Title for alphabetical sorting, with leading articles stripped
 *                       (e.g. "Golden Compass"). Equals displayName when no article is present.
 */
data class CleanedTitle(
    val displayName: String,
    val sortName: String
)

/**
 * Strips marketing/packaging text from UPCitemdb product titles to produce clean display names.
 *
 * UPCitemdb returns titles like:
 *   "Golden Compas The: 2-Disc Special Edition (WS/Dbl DB) (Blu-ray Platinum Series) [Blu-ray]"
 *
 * This service transforms that into:
 *   displayName = "The Golden Compass"
 *   sortName    = "Golden Compass"
 *
 * Processing steps (applied in order):
 *   1. Strip bracketed tags:       [Blu-ray], [DVD], [UHD], [Region 1], etc.
 *   2. Strip parenthesized format: (DVD), (Blu-ray Platinum Series), (WS/Dbl DB), (Widescreen), etc.
 *   3. Strip edition markers:      "2-Disc Special Edition", "Collector's Edition", "Platinum Series"
 *   4. Normalize whitespace
 *   5. Move trailing articles:     "Compass, The" → "The Compass"; "Compas The:" → "The Compas"
 *   6. Clean orphaned punctuation: trailing colons/dashes left by removals
 *
 * Content-distinguishing variants are intentionally preserved:
 *   "Director's Cut", "Unrated", "Extended Edition", "Theatrical"
 *
 * Deterministic and stateless — no I/O, no external dependencies. Fully unit-testable.
 */
object TitleCleanerService {

    // Matches anything inside square brackets: [Blu-ray], [DVD], [Region 2], etc.
    private val BRACKETED_TAGS = Regex("""\[([^\]]*)\]""")

    // Matches parenthesized groups that begin with a known format/packaging keyword.
    // Each alternative anchors on a recognizable prefix and then consumes to the closing paren.
    private val PARENTHESIZED_FORMAT = Regex(
        """\((?:""" +
        """DVD|Blu-ray[^)]*|BD[^)]*|UHD[^)]*|HD[- ]?DVD[^)]*|""" +
        """4K[^)]*|WS[^)]*|FS[^)]*|P&S[^)]*|""" +
        """Widescreen[^)]*|Wide Screen[^)]*|Full\s*Screen[^)]*|Fullscreen[^)]*|Pan\s*(?:&|and)\s*Scan[^)]*|""" +
        """Dbl\s*DB[^)]*|Double\s*Disc[^)]*|""" +
        """(?:\d+-?Disc[^)]*)|""" +
        """Region\s*\d[^)]*""" +
        """)\)""",
        RegexOption.IGNORE_CASE
    )

    // Matches standalone edition/series labels, optionally preceded by a disc count.
    // e.g. "2-Disc Special Edition", "Collector's Edition", "Platinum Series"
    private val EDITION_MARKERS = Regex(
        """(?:\b\d+-?Disc\s+)?(?:Special|Limited|Collector'?s|Platinum|Diamond|Anniversary|Deluxe|Ultimate|Premium)\s+(?:Edition|Series|Collection)\b""",
        RegexOption.IGNORE_CASE
    )

    // Trailing article with comma: "Compass, The" or "Compass, The:"
    private val TRAILING_ARTICLE_COMMA = Regex(
        """,\s+(The|A|An)\b\s*:?\s*""",
        RegexOption.IGNORE_CASE
    )

    // Trailing article before colon (no comma) — common UPCitemdb pattern: "Compas The:"
    private val TRAILING_ARTICLE_COLON = Regex(
        """\s+(The|A|An)\s*:\s*$""",
        RegexOption.IGNORE_CASE
    )

    // Leading article for sort-name generation
    private val LEADING_ARTICLE = Regex(
        """^(The|A|An)\s+""",
        RegexOption.IGNORE_CASE
    )

    // Colons, dashes, or em-dashes left dangling at end of string after removals
    private val ORPHANED_PUNCTUATION = Regex("""\s*[:–—-]\s*$""")

    private val MULTI_SPACE = Regex("""\s{2,}""")

    fun clean(rawTitle: String): CleanedTitle {
        if (rawTitle.isBlank()) {
            return CleanedTitle("", "")
        }

        var title = rawTitle

        // 1. Remove bracketed tags: [Blu-ray], [DVD], [UHD], [Region X], etc.
        title = BRACKETED_TAGS.replace(title, "")

        // 2. Remove parenthesized format/edition content
        title = PARENTHESIZED_FORMAT.replace(title, "")

        // 3. Remove standalone edition markers
        title = EDITION_MARKERS.replace(title, "")

        // 4. Clean up residual punctuation and normalize whitespace (before article detection)
        title = MULTI_SPACE.replace(title.trim(), " ").trim()

        // 5. Move trailing articles: "Golden Compass, The" → "The Golden Compass"
        var article: String? = null

        // Try comma pattern first: "Compass, The" or "Compass, The:"
        val commaMatch = TRAILING_ARTICLE_COMMA.find(title)
        if (commaMatch != null) {
            article = commaMatch.groupValues[1]
            title = TRAILING_ARTICLE_COMMA.replace(title, "")
        } else {
            // Try colon pattern: "Compas The:" (no comma)
            val colonMatch = TRAILING_ARTICLE_COLON.find(title)
            if (colonMatch != null) {
                article = colonMatch.groupValues[1]
                title = TRAILING_ARTICLE_COLON.replace(title, "")
            }
        }

        if (article != null) {
            title = "$article $title"
        }

        // 6. Clean up any remaining orphaned punctuation
        title = ORPHANED_PUNCTUATION.replace(title, "")
        title = MULTI_SPACE.replace(title.trim(), " ").trim()

        val displayName = title

        // Generate sort name: strip leading "The"/"A"/"An"
        val sortName = LEADING_ARTICLE.replace(displayName, "").trim()

        return CleanedTitle(
            displayName = displayName,
            sortName = if (sortName == displayName) displayName else sortName
        )
    }
}
