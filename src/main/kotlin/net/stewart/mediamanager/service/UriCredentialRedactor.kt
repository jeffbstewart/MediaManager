package net.stewart.mediamanager.service

import java.net.URI

/**
 * Stateless singleton for redacting credentials from RTSP and HTTP(S) URLs.
 * Prevents accidental credential leakage in logs, UI, and error messages.
 */
object UriCredentialRedactor {

    private val urlPattern = Regex("""(rtsp|https?|RTSP|HTTPS?)://[^\s"'<>]+""")

    /** Redact credentials from a single URL. Returns the URL unchanged if no credentials are present. */
    fun redact(url: String): String {
        if (url.isBlank()) return url
        return try {
            val uri = URI(url)
            if (uri.userInfo != null) {
                val port = if (uri.port >= 0) ":${uri.port}" else ""
                val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
                val fragment = if (uri.rawFragment != null) "#${uri.rawFragment}" else ""
                "${uri.scheme}://***:***@${uri.host}$port${uri.rawPath ?: ""}$query$fragment"
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    /** Find and redact all credential-bearing URLs in a block of text. */
    fun redactAll(text: String): String {
        return urlPattern.replace(text) { match -> redact(match.value) }
    }
}
