package net.stewart.mediamanager.service

import java.net.URI

/**
 * Stateless singleton for redacting credentials from RTSP and HTTP(S) URLs.
 * Prevents accidental credential leakage in logs, UI, and error messages.
 *
 * Handles URLs with special characters in passwords (e.g. #, ^, &) that break
 * java.net.URI parsing by falling back to regex-based redaction.
 */
object UriCredentialRedactor {

    private val urlPattern = Regex("""(rtsp|https?|RTSP|HTTPS?)://[^\s"'<>]+""")

    /** Matches scheme://user:pass@ where user:pass may contain special characters. */
    private val credentialPattern = Regex("""^(rtsp|https?)://([^@]+)@""", RegexOption.IGNORE_CASE)

    /** Redact credentials from a single URL. Returns the URL unchanged if no credentials are present. */
    fun redact(url: String): String {
        if (url.isBlank()) return url
        // Try URI parsing first (handles well-formed URLs correctly)
        try {
            val uri = URI(url)
            if (uri.userInfo != null) {
                val port = if (uri.port >= 0) ":${uri.port}" else ""
                val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
                val fragment = if (uri.rawFragment != null) "#${uri.rawFragment}" else ""
                return "${uri.scheme}://***:***@${uri.host}$port${uri.rawPath ?: ""}$query$fragment"
            }
        } catch (_: Exception) {
            // URI parsing failed — fall through to regex
        }
        // Regex fallback for URLs with special chars in credentials (e.g. #, ^, &)
        return credentialPattern.replace(url) { match -> "${match.groupValues[1]}://***:***@" }
    }

    /** Find and redact all credential-bearing URLs in a block of text. */
    fun redactAll(text: String): String {
        return urlPattern.replace(text) { match -> redact(match.value) }
    }

    /**
     * Restore credentials from [sourceUrl] into [editedUrl].
     * If [editedUrl] contains `***:***` as the userinfo, replaces it with the real
     * credentials from [sourceUrl]. Only restores if the host (and port, if present)
     * match between source and edited URLs — prevents credential exfiltration by
     * redirecting to a different host.
     * Returns [editedUrl] unchanged if no placeholder found or hosts don't match.
     */
    fun restoreCredentials(editedUrl: String, sourceUrl: String): String {
        if (!editedUrl.contains("***:***@")) return editedUrl
        // Extract the real userinfo from the source URL via regex (URI parsing may fail on special chars)
        val sourceMatch = credentialPattern.find(sourceUrl)
        val userInfo = sourceMatch?.groupValues?.get(2) ?: return editedUrl
        // Extract hosts from both URLs to verify they match
        val sourceHost = extractHost(sourceUrl)
        val editedHost = extractHost(editedUrl)
        if (sourceHost == null || editedHost == null) return editedUrl
        if (!sourceHost.equals(editedHost, ignoreCase = true)) return editedUrl
        return editedUrl.replace("***:***@", "$userInfo@")
    }

    /** Extract host (and port if present) from a URL, tolerant of special chars in credentials. */
    private fun extractHost(url: String): String? {
        // Strip scheme and credentials to get host:port/path...
        val afterAt = url.substringAfter("@", "")
        if (afterAt.isBlank()) return null
        // Host is everything before the first / or end of string
        return afterAt.substringBefore("/").substringBefore("?").lowercase()
    }
}
