package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.Title

data class ScoredTitle(val title: Title, val score: Double)

object FuzzyMatchService {

    fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }

    fun similarity(a: String, b: String): Double {
        val na = TranscodeMatcherService.normalize(a)
        val nb = TranscodeMatcherService.normalize(b)
        if (na.isEmpty() && nb.isEmpty()) return 1.0
        val maxLen = maxOf(na.length, nb.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(na, nb).toDouble() / maxLen
    }

    fun findSuggestions(
        query: String,
        titles: List<Title>,
        maxResults: Int = 3,
        threshold: Double = 0.60
    ): List<ScoredTitle> {
        return titles
            .filter { !it.hidden }
            .map { title ->
                val nameScore = similarity(query, title.name)
                val sortScore = if (title.sort_name != null) similarity(query, title.sort_name!!) else 0.0
                ScoredTitle(title, maxOf(nameScore, sortScore))
            }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(maxResults)
    }
}
