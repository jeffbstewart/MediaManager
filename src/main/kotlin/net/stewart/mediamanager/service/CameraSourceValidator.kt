package net.stewart.mediamanager.service

/**
 * Validation for admin-supplied camera fields that end up in the go2rtc
 * config file. go2rtc interprets each stream source by its scheme, and
 * some schemes (e.g. `exec:`) launch processes, so anything other than a
 * well-formed `rtsp://` / `rtsps://` URL must never reach the config.
 *
 * Every write path (gRPC AdminService, `/api/v2/admin/cameras` REST) goes
 * through [CameraAdminService], which calls these; [Go2rtcAgent] re-checks
 * at config-write time so rows stored before this validation existed are
 * skipped rather than emitted.
 *
 * Parsing is deliberately tolerant of the userinfo segment: real camera
 * passwords contain characters such as `#`, `^`, and `&` that
 * `java.net.URI` rejects, so the host is located after the last `@`
 * instead of relying on URI parsing.
 */
object CameraSourceValidator {

    private val RTSP_SCHEMES = listOf("rtsp://", "rtsps://")
    private val HTTP_SCHEMES = listOf("http://", "https://")

    /** `host[:port]` where host is a DNS name / IPv4 literal or a bracketed IPv6 literal. */
    private val HOST_PORT = Regex("""^(\[[0-9A-Fa-f:.]+]|[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(:\d{1,5})?$""")

    /** go2rtc stream names: used as YAML keys and in go2rtc API query strings. */
    private val STREAM_NAME = Regex("""^[A-Za-z0-9_-]{1,64}$""")

    /** Returns the trimmed URL, or throws [IllegalArgumentException] describing the problem. */
    fun requireValidRtspUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotEmpty()) { "RTSP URL is required" }
        require(RTSP_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) }) {
            "RTSP URL must start with rtsp:// or rtsps://"
        }
        requirePrintableAscii(trimmed, "RTSP URL")
        require(hasValidHost(trimmed)) { "RTSP URL has an invalid host" }
        return trimmed
    }

    /**
     * Snapshot URL is optional. When present it must be an http(s) URL with
     * the same character restrictions as the RTSP URL.
     */
    fun requireValidSnapshotUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        require(HTTP_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) }) {
            "Snapshot URL must start with http:// or https://"
        }
        requirePrintableAscii(trimmed, "Snapshot URL")
        require(hasValidHost(trimmed)) { "Snapshot URL has an invalid host" }
        return trimmed
    }

    /** Returns the trimmed stream name, or throws [IllegalArgumentException]. */
    fun requireValidStreamName(name: String): String {
        val trimmed = name.trim()
        require(STREAM_NAME.matches(trimmed)) {
            "Stream name must be 1-64 characters of letters, digits, '_' or '-'"
        }
        return trimmed
    }

    fun isValidRtspUrl(url: String): Boolean = runCatching { requireValidRtspUrl(url) }.isSuccess

    fun isValidStreamName(name: String): Boolean = STREAM_NAME.matches(name)

    /**
     * Reject whitespace, control characters (including CR/LF and Unicode
     * line separators), and anything outside printable ASCII. Hostnames
     * must be given in their ASCII (punycode) form.
     */
    private fun requirePrintableAscii(value: String, label: String) {
        require(value.all { it in '!'..'~' }) {
            "$label must not contain whitespace, control, or non-ASCII characters"
        }
    }

    /**
     * True when `scheme://[userinfo@]host[:port][/path][?query][#frag]` has a
     * well-formed `host[:port]`. The userinfo may itself contain `@`, `/`,
     * or `#`, so each possible userinfo boundary is tried (no userinfo, then
     * each `@`); the URL is accepted if any split yields a valid host.
     */
    private fun hasValidHost(url: String): Boolean {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return false
        val starts = listOf(0) + afterScheme.indices.filter { afterScheme[it] == '@' }.map { it + 1 }
        return starts.any { start ->
            val rest = afterScheme.substring(start)
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val host = if (end < 0) rest else rest.substring(0, end)
            HOST_PORT.matches(host)
        }
    }
}
