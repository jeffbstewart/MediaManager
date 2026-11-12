package net.stewart.mediamanager.service

/**
 * Parsed search query supporting quoted phrases, negative terms, and tag operators.
 *
 * Example: `"dark knight" -sequel tag:action batman` parses to:
 * - phrases = ["dark knight"]
 * - requiredTerms = ["batman"]
 * - excludedTerms = ["sequel"]
 * - tagFilters = ["action"]
 */
data class SearchQuery(
    val phrases: List<String> = emptyList(),
    val requiredTerms: List<String> = emptyList(),
    val excludedTerms: List<String> = emptyList(),
    val tagFilters: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = phrases.isEmpty() && requiredTerms.isEmpty() &&
        excludedTerms.isEmpty() && tagFilters.isEmpty()

    /** True if there are only exclusions and no positive search criteria. */
    val isExclusionOnly: Boolean get() = excludedTerms.isNotEmpty() &&
        phrases.isEmpty() && requiredTerms.isEmpty() && tagFilters.isEmpty()
}

/**
 * Stateless parser that converts a raw search string into a structured [SearchQuery].
 * All output is lowercased. Supports:
 * - Quoted phrases: `"dark knight"` matches the exact phrase
 * - Negation: `-sequel` excludes titles containing "sequel"
 * - Tag filter: `tag:action` filters by tag name
 * - Required terms: any other token must appear in the title
 */
object SearchQueryParser {

    fun parse(input: String): SearchQuery {
        if (input.isBlank()) return SearchQuery()

        // Fold diacritics so queries like "Celine" match indexed "Céline".
        // Must mirror SearchIndexService.tokenize, which folds at index time.
        val lower = SearchIndexService.foldAccents(input).lowercase()
        val phrases = mutableListOf<String>()
        val requiredTerms = mutableListOf<String>()
        val excludedTerms = mutableListOf<String>()
        val tagFilters = mutableListOf<String>()

        // Extract quoted phrases first
        val remaining = StringBuilder()
        var i = 0
        while (i < lower.length) {
            if (lower[i] == '"') {
                val closeIdx = lower.indexOf('"', i + 1)
                if (closeIdx > i + 1) {
                    val phrase = lower.substring(i + 1, closeIdx).trim()
                    if (phrase.isNotEmpty()) {
                        phrases.add(phrase)
                    }
                    i = closeIdx + 1
                } else {
                    // Unclosed quote — treat rest as a phrase
                    val phrase = lower.substring(i + 1).trim()
                    if (phrase.isNotEmpty()) {
                        phrases.add(phrase)
                    }
                    i = lower.length
                }
            } else {
                remaining.append(lower[i])
                i++
            }
        }

        // Split remaining text on whitespace and classify tokens
        val tokens = remaining.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (token in tokens) {
            when {
                token.startsWith("tag:") && token.length > 4 -> {
                    tagFilters.add(token.removePrefix("tag:"))
                }
                token.startsWith("-") && token.length > 1 -> {
                    excludedTerms.add(token.removePrefix("-"))
                }
                else -> {
                    requiredTerms.add(token)
                }
            }
        }

        return SearchQuery(
            phrases = phrases,
            requiredTerms = requiredTerms,
            excludedTerms = excludedTerms,
            tagFilters = tagFilters
        )
    }
}
