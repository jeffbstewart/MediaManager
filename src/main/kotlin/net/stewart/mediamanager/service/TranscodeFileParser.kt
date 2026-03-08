package net.stewart.mediamanager.service

data class ParsedFile(
    val title: String,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val isEpisode: Boolean = false
)

object TranscodeFileParser {

    // TV pattern: clean format "Show - S01E01 - Episode Title"
    private val TV_CLEAN = Regex("""^(.+?) - [Ss](\d{2})[Ee](\d{2})(?: - (.+))?$""")

    // TV pattern: doubled format "Show - S01E01 - Show - s01e01 - Episode Title"
    private val TV_DOUBLED = Regex("""^(.+?) - [Ss](\d{2})[Ee](\d{2}) - .+? - [Ss]\d{2}[Ee]\d{2}(?: - (.+))?$""")

    // Year extraction: (YYYY) where 1950-2050
    private val YEAR_PATTERN = Regex("""\((\d{4})\)""")

    // MakeMKV suffix: _t00, _t01, _t02
    private val MAKEMKV_SUFFIX = Regex("""_t\d{2}$""")

    // Video file extensions
    private val VIDEO_EXTENSION = Regex("""\.(mkv|mp4|avi|m4v)$""", RegexOption.IGNORE_CASE)

    fun parseMovieFile(fileName: String): ParsedFile {
        var name = fileName

        // Strip extension
        name = VIDEO_EXTENSION.replace(name, "")

        // Strip MakeMKV suffix
        name = MAKEMKV_SUFFIX.replace(name, "")

        // Trim trailing whitespace
        name = name.trimEnd()

        // Extract year
        var year: Int? = null
        val yearMatch = YEAR_PATTERN.findAll(name).lastOrNull()
        if (yearMatch != null) {
            val y = yearMatch.groupValues[1].toInt()
            if (y in 1950..2050) {
                year = y
                name = name.removeRange(yearMatch.range).trim()
            }
        }

        // Normalize trailing articles: "Karate Kid The" → "The Karate Kid"
        name = normalizeTrailingArticle(name)

        return ParsedFile(title = name.trim(), year = year)
    }

    fun parseTvEpisodeFile(fileName: String): ParsedFile {
        var name = fileName

        // Strip extension
        name = VIDEO_EXTENSION.replace(name, "")

        // Try doubled pattern first (more specific)
        val doubledMatch = TV_DOUBLED.matchEntire(name)
        if (doubledMatch != null) {
            return buildTvResult(doubledMatch)
        }

        // Try clean pattern
        val cleanMatch = TV_CLEAN.matchEntire(name)
        if (cleanMatch != null) {
            return buildTvResult(cleanMatch)
        }

        // Fallback: treat as unparseable episode file, just strip extension
        return ParsedFile(title = name.trim(), isEpisode = true)
    }

    private fun buildTvResult(match: kotlin.text.MatchResult): ParsedFile {
        var showName = match.groupValues[1].trim()
        val season = match.groupValues[2].toInt()
        val episode = match.groupValues[3].toInt()
        val episodeTitle = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }

        // Strip year from show name
        var showYear: Int? = null
        val yearMatch = YEAR_PATTERN.find(showName)
        if (yearMatch != null) {
            val y = yearMatch.groupValues[1].toInt()
            if (y in 1950..2050) {
                showYear = y
                showName = showName.removeRange(yearMatch.range).trim()
            }
        }

        return ParsedFile(
            title = showName,
            year = showYear,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = episodeTitle,
            isEpisode = true
        )
    }

    private fun normalizeTrailingArticle(name: String): String {
        val articles = listOf("The", "A", "An")
        for (article in articles) {
            if (name.endsWith(" $article", ignoreCase = true)) {
                val base = name.dropLast(article.length + 1)
                return "$article $base"
            }
        }
        return name
    }
}
