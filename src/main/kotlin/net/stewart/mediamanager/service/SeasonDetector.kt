package net.stewart.mediamanager.service

data class SeasonResult(
    val hasSeason: Boolean,
    val seasons: String? = null,
    val reason: String? = null
)

/**
 * Detects TV season information from raw UPCitemdb product names.
 *
 * Stateless and deterministic — same pattern as MultiPackDetector.
 *
 * Detection patterns (priority order):
 *   1. Complete Series/Collection → "all"
 *   2. Season range: "Seasons 1-3", "Seasons 1 - 5" → "1-3", "1-5"
 *   3. Single season: "Season 1", "Season 02" → "1", "2"
 *   4. Short form: "S1", "S01" → "1"
 *   5. British convention: "Series 1" → "1"
 *   6. Ordinals: "First Season" through "Fifth Season" → "1"–"5"
 */
object SeasonDetector {

    // "Complete Series", "Complete Collection", "Complete Series Box Set"
    private val COMPLETE = Regex(
        """\bComplete\s+(?:Series|Collection)\b""",
        RegexOption.IGNORE_CASE
    )

    // "Seasons 1-3", "Seasons 1 - 5", "Seasons 1 thru 4"
    private val SEASON_RANGE = Regex(
        """\bSeasons?\s+(\d+)\s*[-–—]\s*(\d+)\b""",
        RegexOption.IGNORE_CASE
    )

    // "Season 1", "Season 02", "Season One"
    private val SINGLE_SEASON = Regex(
        """\bSeason\s+(\d+)\b""",
        RegexOption.IGNORE_CASE
    )

    // "S1", "S01", "S02" — must be preceded by space or start of string, followed by space/punctuation/end
    private val SHORT_FORM = Regex(
        """(?:^|[\s(])[Ss](\d{1,2})(?=[\s)\].,;:!?]|$)"""
    )

    // "Series 1", "Series 02" — British convention (must NOT match "Complete Series")
    private val BRITISH_SERIES = Regex(
        """\bSeries\s+(\d+)\b""",
        RegexOption.IGNORE_CASE
    )

    // "First Season" through "Fifth Season"
    private val ORDINAL_SEASON = Regex(
        """\b(First|Second|Third|Fourth|Fifth)\s+Season\b""",
        RegexOption.IGNORE_CASE
    )

    private val ORDINAL_MAP = mapOf(
        "first" to 1,
        "second" to 2,
        "third" to 3,
        "fourth" to 4,
        "fifth" to 5
    )

    fun detect(productName: String?): SeasonResult {
        if (productName.isNullOrBlank()) {
            return SeasonResult(hasSeason = false)
        }

        // 1. Complete Series/Collection
        if (COMPLETE.containsMatchIn(productName)) {
            return SeasonResult(true, "all", "Complete series/collection")
        }

        // 2. Season range
        val rangeMatch = SEASON_RANGE.find(productName)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toInt()
            val end = rangeMatch.groupValues[2].toInt()
            return SeasonResult(true, "$start-$end", "Season range: ${rangeMatch.value.trim()}")
        }

        // 3. Single season
        val singleMatch = SINGLE_SEASON.find(productName)
        if (singleMatch != null) {
            val num = singleMatch.groupValues[1].toInt()
            return SeasonResult(true, "$num", "Single season: ${singleMatch.value.trim()}")
        }

        // 4. Short form
        val shortMatch = SHORT_FORM.find(productName)
        if (shortMatch != null) {
            val num = shortMatch.groupValues[1].toInt()
            return SeasonResult(true, "$num", "Short form: S${shortMatch.groupValues[1]}")
        }

        // 5. British convention
        val britishMatch = BRITISH_SERIES.find(productName)
        if (britishMatch != null) {
            val num = britishMatch.groupValues[1].toInt()
            return SeasonResult(true, "$num", "British convention: ${britishMatch.value.trim()}")
        }

        // 6. Ordinals
        val ordinalMatch = ORDINAL_SEASON.find(productName)
        if (ordinalMatch != null) {
            val word = ordinalMatch.groupValues[1].lowercase()
            val num = ORDINAL_MAP[word] ?: return SeasonResult(hasSeason = false)
            return SeasonResult(true, "$num", "Ordinal: ${ordinalMatch.value.trim()}")
        }

        return SeasonResult(hasSeason = false)
    }
}
