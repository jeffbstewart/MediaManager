package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.net.Inet6Address
import java.net.InetAddress

/**
 * An IPv4 or IPv6 network in CIDR notation (`192.0.2.0/24`,
 * `2001:db8::/32`). A bare address is treated as a single-host network
 * (`/32` or `/128`). Parsing never performs DNS lookups.
 */
class IpNetwork private constructor(
    private val network: ByteArray,
    val prefixLength: Int,
) {
    /** True when [address] falls inside this network. IPv4-mapped IPv6 addresses match IPv4 networks. */
    fun contains(address: InetAddress): Boolean {
        val bytes = normalizedBytes(address)
        if (bytes.size != network.size) return false
        return prefixMatches(bytes)
    }

    private fun prefixMatches(bytes: ByteArray): Boolean {
        var remaining = prefixLength
        var i = 0
        while (remaining > 0) {
            val bits = minOf(remaining, 8)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            remaining -= bits
            i++
        }
        return true
    }

    override fun toString(): String =
        "${InetAddress.getByAddress(network).hostAddress}/$prefixLength"

    companion object {
        /**
         * Parse `address` or `address/prefix`. Throws [IllegalArgumentException]
         * on anything that isn't a literal IP network.
         */
        fun parse(spec: String): IpNetwork {
            val trimmed = spec.trim()
            val slash = trimmed.indexOf('/')
            val addrPart = if (slash >= 0) trimmed.substring(0, slash) else trimmed
            val address = parseIpLiteral(addrPart)
                ?: throw IllegalArgumentException("Not an IP address or CIDR: '$spec'")
            val bytes = normalizedBytes(address)
            val maxPrefix = bytes.size * 8
            val prefix = if (slash >= 0) {
                trimmed.substring(slash + 1).toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid CIDR prefix in '$spec'")
            } else {
                maxPrefix
            }
            require(prefix in 0..maxPrefix) { "CIDR prefix out of range in '$spec'" }
            return IpNetwork(bytes, prefix)
        }
    }
}

private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:.]+$")

/**
 * Parse an IP literal without touching DNS. Accepts the forms that
 * appear in `X-Forwarded-For` entries: bare IPv4/IPv6, `[v6]`,
 * `[v6]:port`, and `v4:port`. Returns null for anything else
 * (hostnames, `unknown`, obfuscated identifiers, zone IDs).
 */
internal fun parseIpLiteral(raw: String): InetAddress? {
    var s = raw.trim().removeSurrounding("\"")
    if (s.isEmpty()) return null
    if (s.startsWith("[")) {
        val close = s.indexOf(']')
        if (close < 0) return null
        s = s.substring(1, close)
    } else if (s.count { it == ':' } == 1) {
        // IPv4 with a port suffix
        s = s.substringBefore(':')
    }
    val literal = when {
        IPV4_LITERAL.matches(s) -> {
            if (s.split('.').any { it.toInt() > 255 }) return null
            s
        }
        s.contains(':') && IPV6_LITERAL.matches(s) -> s
        else -> return null
    }
    return try {
        // Safe: the input is a validated literal, so no name resolution occurs.
        InetAddress.getByName(literal)
    } catch (_: Exception) {
        null
    }
}

/** Address bytes with IPv4-mapped IPv6 (`::ffff:a.b.c.d`) collapsed to 4 bytes. */
private fun normalizedBytes(address: InetAddress): ByteArray {
    val bytes = address.address
    if (address is Inet6Address && isIpv4Mapped(bytes)) return bytes.copyOfRange(12, 16)
    return bytes
}

private fun isIpv4Mapped(b: ByteArray): Boolean =
    b.size == 16 && (0 until 10).all { b[it] == 0.toByte() } &&
        b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()

/** Originating client of a request, as established by [TrustedProxies.resolve]. */
data class ClientOrigin(
    /** Normalized client IP string. */
    val clientIp: String,
    /** True when the request arrived through a configured trusted proxy (TLS-terminated upstream). */
    val viaTrustedProxy: Boolean,
)

/**
 * Single source of truth for "who is the client" and "did this request
 * come through the TLS-terminating reverse proxy". Used by the gRPC
 * interceptors and the HTTP services alike.
 *
 * `X-Forwarded-*` headers are honored ONLY when the immediate TCP peer
 * is listed in [ENV_VAR] (comma- or whitespace-separated IPs/CIDRs).
 * The client IP is the right-most `X-Forwarded-For` entry that is not
 * itself a trusted proxy — entries to its left were supplied by the
 * client and cannot be trusted.
 *
 * With no trusted proxies configured, forwarded headers are ignored
 * entirely: only loopback peers are accepted for endpoints that require
 * a secure path, and every other peer is identified by its socket address.
 */
object TrustedProxies {
    private val log = LoggerFactory.getLogger(TrustedProxies::class.java)

    const val ENV_VAR = "MM_TRUSTED_PROXIES"
    private const val LEGACY_ENV_VAR = "MM_BEHIND_PROXY"

    @Volatile
    private var networks: List<IpNetwork> = emptyList()

    /** Currently configured trusted proxy networks. */
    fun configured(): List<IpNetwork> = networks

    /**
     * Replace the trusted proxy list from a comma/whitespace-separated
     * spec. Blank or null clears it. Throws [IllegalArgumentException]
     * on an unparseable entry so a typo fails loudly at startup instead
     * of silently trusting nothing (or the wrong thing).
     */
    fun configure(spec: String?) {
        networks = parseList(spec)
    }

    internal fun parseList(spec: String?): List<IpNetwork> =
        spec.orEmpty()
            .split(',', ' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { IpNetwork.parse(it) }

    /** Read [ENV_VAR] (system property first, then environment) and log the result. */
    fun configureFromEnvironment() {
        val spec = System.getProperty(ENV_VAR) ?: System.getenv(ENV_VAR)
        try {
            configure(spec)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid $ENV_VAR: ${e.message}", e)
        }
        if (networks.isEmpty()) {
            val legacy = System.getProperty(LEGACY_ENV_VAR) ?: System.getenv(LEGACY_ENV_VAR)
            if (legacy != null) {
                log.warn("{} is no longer read. Set {} to the reverse proxy address(es) " +
                    "or X-Forwarded-* headers will be ignored and proxied logins will be refused.",
                    LEGACY_ENV_VAR, ENV_VAR)
            }
            log.info("No trusted reverse proxies configured; X-Forwarded-* headers will be ignored")
        } else {
            log.info("Trusted reverse proxies: {}", networks.joinToString(", "))
        }
    }

    fun isTrusted(address: InetAddress?, trusted: List<IpNetwork> = networks): Boolean =
        address != null && trusted.any { it.contains(address) }

    /**
     * Resolve the client of a request that must arrive over a secure path.
     *
     * - Peer is a trusted proxy: requires `X-Forwarded-Proto: https` and a
     *   usable `X-Forwarded-For`; returns the right-most untrusted entry.
     * - Peer is loopback (and not configured as a proxy): a local caller;
     *   forwarded headers are ignored.
     * - Anything else: null — the caller must reject the request.
     *
     * [forwardedFor] holds every `X-Forwarded-For` header value in order
     * of appearance.
     */
    fun resolve(
        peer: InetAddress?,
        forwardedProto: String?,
        forwardedFor: List<String>,
        trusted: List<IpNetwork> = networks,
    ): ClientOrigin? {
        if (peer == null) return null
        if (isTrusted(peer, trusted)) {
            if (!forwardedProto?.trim().equals("https", ignoreCase = true)) return null
            val client = clientFromForwardedFor(forwardedFor, trusted) ?: return null
            return ClientOrigin(client.hostAddress, viaTrustedProxy = true)
        }
        if (peer.isLoopbackAddress) {
            return ClientOrigin(peer.hostAddress, viaTrustedProxy = false)
        }
        return null
    }

    /**
     * Best-effort client IP for logging and rate limiting on endpoints
     * that don't require the secure path. Honors `X-Forwarded-For` only
     * from a trusted peer; otherwise returns the peer's own address.
     */
    fun bestEffortClientIp(
        peer: InetAddress?,
        forwardedFor: List<String>,
        trusted: List<IpNetwork> = networks,
    ): String {
        if (peer == null) return "unknown"
        if (isTrusted(peer, trusted)) {
            clientFromForwardedFor(forwardedFor, trusted)?.let { return it.hostAddress }
        }
        return peer.hostAddress
    }

    /**
     * Value of a forwarded header (`X-Forwarded-Proto`, `X-Forwarded-Host`)
     * when [peer] is a trusted proxy, else null.
     */
    fun forwardedHeaderIfTrusted(peer: InetAddress?, value: String?, trusted: List<IpNetwork> = networks): String? =
        if (isTrusted(peer, trusted)) value?.substringBefore(',')?.trim()?.ifEmpty { null } else null

    /**
     * Walk the combined `X-Forwarded-For` chain right-to-left and return the
     * first entry that is not a trusted proxy. When every entry is trusted
     * (a request originating on the proxy network), return the left-most.
     * Returns null when the header is absent or the selected entry isn't a
     * valid IP literal.
     */
    internal fun clientFromForwardedFor(forwardedFor: List<String>, trusted: List<IpNetwork>): InetAddress? {
        val entries = forwardedFor
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (entries.isEmpty()) return null
        var leftmost: InetAddress? = null
        for (entry in entries.asReversed()) {
            val address = parseIpLiteral(entry) ?: return null
            if (!isTrusted(address, trusted)) return address
            leftmost = address
        }
        return leftmost
    }
}
