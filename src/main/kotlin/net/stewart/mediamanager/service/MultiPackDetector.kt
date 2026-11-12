package net.stewart.mediamanager.service

/**
 * Result of multi-pack detection on a product name.
 *
 * @property isMultiPack true if the product name indicates multiple titles
 * @property estimatedTitleCount best guess at how many titles are included (minimum 2 if multi-pack)
 * @property reason human-readable explanation of why it was flagged
 */
data class MultiPackResult(
    val isMultiPack: Boolean,
    val estimatedTitleCount: Int = 1,
    val reason: String? = null
)

/**
 * Detects multi-pack / multi-title products from raw UPCitemdb product names.
 *
 * Conservative: false negatives are OK (user can manually flag later);
 * false positives are OK (user dismisses as SINGLE in the expansion UI).
 *
 * Detection patterns (priority order):
 *   1. Named features: "Double Feature", "Triple Feature"
 *   2. Numeric packs: "3-Film Collection", "4-Movie Pack", "2-Disc Set"
 *   3. Slash-separated titles: "Movie A / Movie B" (guarded against short fragments)
 *   4. Keywords: "Trilogy" (3), "Box Set" (2 minimum)
 *
 * Deterministic and stateless — same pattern as TitleCleanerService.
 */
object MultiPackDetector {

    // "Double Feature", "Triple Feature", "Quadruple Feature"
    private val NAMED_FEATURE = Regex(
        """\b(Double|Triple|Quadruple)\s+Feature\b""",
        RegexOption.IGNORE_CASE
    )

    // "3-Film Collection", "4-Movie Pack", "2 Disc Set", "3-Movie Set", etc.
    private val NUMERIC_PACK = Regex(
        """\b(\d+)[- ](?:Film|Movie|Disc|DVD|Blu-?ray)\s+(?:Collection|Pack|Set|Combo)\b""",
        RegexOption.IGNORE_CASE
    )

    // Slash-separated titles: "Movie A / Movie B" or "Movie A / Movie B / Movie C"
    // Guard: each segment must be at least 4 chars (excludes "WS/FS", "P&S/WS")
    private val SLASH_SEPARATOR = Regex("""\s+/\s+""")

    // "Trilogy" implies exactly 3
    private val TRILOGY = Regex("""\bTrilogy\b""", RegexOption.IGNORE_CASE)

    // "Box Set" implies at least 2
    private val BOX_SET = Regex("""\bBox\s*Set\b""", RegexOption.IGNORE_CASE)

    fun detect(productName: String?): MultiPackResult {
        if (productName.isNullOrBlank()) {
            return MultiPackResult(isMultiPack = false)
        }

        // 1. Named features
        val namedMatch = NAMED_FEATURE.find(productName)
        if (namedMatch != null) {
            val count = when (namedMatch.groupValues[1].lowercase()) {
                "double" -> 2
                "triple" -> 3
                "quadruple" -> 4
                else -> 2
            }
            return MultiPackResult(true, count, "Named feature: ${namedMatch.value}")
        }

        // 2. Numeric packs
        val numericMatch = NUMERIC_PACK.find(productName)
        if (numericMatch != null) {
            val count = numericMatch.groupValues[1].toIntOrNull() ?: 2
            if (count >= 2) {
                return MultiPackResult(true, count, "Numeric pack: ${numericMatch.value}")
            }
        }

        // 3. Slash-separated titles
        val segments = productName.split(SLASH_SEPARATOR)
        if (segments.size >= 2) {
            // Guard: each segment must be at least 4 chars to avoid format markers like "WS/FS"
            val validSegments = segments.filter { it.trim().length >= 4 }
            if (validSegments.size >= 2) {
                return MultiPackResult(true, validSegments.size, "Slash-separated titles")
            }
        }

        // 4. Keywords
        if (TRILOGY.containsMatchIn(productName)) {
            return MultiPackResult(true, 3, "Keyword: Trilogy")
        }

        if (BOX_SET.containsMatchIn(productName)) {
            return MultiPackResult(true, 2, "Keyword: Box Set")
        }

        return MultiPackResult(isMultiPack = false)
    }
}
