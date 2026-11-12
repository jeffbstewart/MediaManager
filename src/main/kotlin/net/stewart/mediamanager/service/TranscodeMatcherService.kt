package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.MatchMethod
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title

data class MatchResult(
    val titleId: Long? = null,
    val episodeId: Long? = null,
    val method: MatchMethod? = null
)

object TranscodeMatcherService {

    fun matchMovie(parsed: ParsedFile, titles: List<Title>): MatchResult {
        return matchByName(parsed.title, parsed.year, MediaType.MOVIE.name, titles)
    }

    fun matchTvShow(showName: String, titles: List<Title>): MatchResult {
        return matchByName(showName, null, MediaType.TV.name, titles)
    }

    private fun matchByName(name: String, year: Int?, preferredMediaType: String, titles: List<Title>): MatchResult {
        // 1. Exact match (case-insensitive)
        val exactMatches = titles.filter { it.name.equals(name, ignoreCase = true) }
        val exactResult = disambiguate(exactMatches, year, preferredMediaType)
        if (exactResult != null) {
            return MatchResult(titleId = exactResult.id, method = MatchMethod.AUTO_EXACT)
        }

        // 2. Normalized match
        val normalizedName = normalize(name)
        val normalizedMatches = titles.filter { title ->
            normalize(title.name) == normalizedName ||
                (title.sort_name != null && normalize(title.sort_name!!) == normalizedName)
        }
        val normalizedResult = disambiguate(normalizedMatches, year, preferredMediaType)
        if (normalizedResult != null) {
            return MatchResult(titleId = normalizedResult.id, method = MatchMethod.AUTO_NORMALIZED)
        }

        return MatchResult()
    }

    /**
     * Picks the best title from a list of name-matched candidates.
     *
     * Disambiguation order:
     * 1. If only one match, return it.
     * 2. Filter to preferred media type (e.g., TV files prefer TV titles).
     * 3. Disambiguate by year if available.
     * 4. Fall back to first match.
     */
    private fun disambiguate(matches: List<Title>, year: Int?, preferredMediaType: String): Title? {
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches.first()

        // Prefer titles matching the expected media type
        val sameType = matches.filter { it.media_type == preferredMediaType }
        val candidates = sameType.ifEmpty { matches }

        if (candidates.size == 1) return candidates.first()

        if (year != null) {
            val yearMatch = candidates.firstOrNull { it.release_year == year }
            if (yearMatch != null) return yearMatch
        }

        // Multiple matches, no further disambiguation — return first (stable order)
        return candidates.first()
    }

    internal fun normalize(name: String): String {
        return name.lowercase()
            .replace(Regex("""^(the|a|an)\s+"""), "")     // strip leading articles
            .replace(Regex("""[^a-z0-9\s]"""), "")        // strip punctuation
            .replace(Regex("""\s+"""), " ")                // collapse spaces
            .trim()
    }
}
