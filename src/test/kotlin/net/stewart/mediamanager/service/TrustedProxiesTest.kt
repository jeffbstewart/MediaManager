package net.stewart.mediamanager.service

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [TrustedProxies] / [IpNetwork]. All addresses are from the
 * documentation ranges (RFC 5737 IPv4, RFC 3849 IPv6) plus loopback.
 */
class TrustedProxiesTest {

    private fun ip(s: String): InetAddress = parseIpLiteral(s)!!
    private val proxy = TrustedProxies.parseList("192.0.2.10, 2001:db8:ffff::/48")
    private val chain = TrustedProxies.parseList("192.0.2.0/28 198.51.100.7")

    // ---------------------- IpNetwork ----------------------

    @Test
    fun `IpNetwork matches IPv4 CIDR boundaries`() {
        val net = IpNetwork.parse("192.0.2.0/25")
        assertTrue(net.contains(ip("192.0.2.0")))
        assertTrue(net.contains(ip("192.0.2.127")))
        assertFalse(net.contains(ip("192.0.2.128")))
        assertFalse(net.contains(ip("198.51.100.1")))
    }

    @Test
    fun `IpNetwork bare address is a single host`() {
        val net = IpNetwork.parse("203.0.113.5")
        assertEquals(32, net.prefixLength)
        assertTrue(net.contains(ip("203.0.113.5")))
        assertFalse(net.contains(ip("203.0.113.6")))
    }

    @Test
    fun `IpNetwork matches IPv6 and keeps address families apart`() {
        val net = IpNetwork.parse("2001:db8::/32")
        assertTrue(net.contains(ip("2001:db8:1234::1")))
        assertFalse(net.contains(ip("2001:db9::1")))
        assertFalse(net.contains(ip("192.0.2.1")))
    }

    @Test
    fun `IPv4-mapped IPv6 peers match IPv4 networks`() {
        val net = IpNetwork.parse("192.0.2.0/24")
        val mapped = InetAddress.getByAddress(byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 192.toByte(), 0, 2, 9))
        assertTrue(net.contains(mapped))
    }

    @Test
    fun `IpNetwork rejects hostnames and bad prefixes`() {
        assertFailsWith<IllegalArgumentException> { IpNetwork.parse("proxy.example") }
        assertFailsWith<IllegalArgumentException> { IpNetwork.parse("192.0.2.0/33") }
        assertFailsWith<IllegalArgumentException> { IpNetwork.parse("192.0.2.0/x") }
        assertFailsWith<IllegalArgumentException> { IpNetwork.parse("192.0.2.999") }
        assertFailsWith<IllegalArgumentException> { TrustedProxies.parseList("192.0.2.1, nope") }
    }

    @Test
    fun `parseIpLiteral handles ports brackets and junk`() {
        assertEquals("192.0.2.1", parseIpLiteral("192.0.2.1:4433")?.hostAddress)
        assertEquals(ip("2001:db8::1"), parseIpLiteral("[2001:db8::1]:443"))
        assertEquals(ip("2001:db8::1"), parseIpLiteral("2001:db8::1"))
        assertNull(parseIpLiteral("unknown"))
        assertNull(parseIpLiteral("_hidden"))
        assertNull(parseIpLiteral("example.com"))
        assertNull(parseIpLiteral(""))
    }

    // ---------------------- resolve ----------------------

    @Test
    fun `untrusted peer with spoofed forwarded headers is rejected`() {
        val origin = TrustedProxies.resolve(
            ip("198.51.100.20"), "https", listOf("203.0.113.1"), proxy)
        assertNull(origin, "forwarded headers from a non-proxy peer must not be honored")
    }

    @Test
    fun `untrusted peer is rejected when no proxies are configured`() {
        assertNull(TrustedProxies.resolve(ip("192.0.2.10"), "https", listOf("203.0.113.1"), emptyList()))
    }

    @Test
    fun `loopback peer is local and ignores forwarded headers`() {
        val origin = TrustedProxies.resolve(ip("127.0.0.1"), "https", listOf("203.0.113.1"), proxy)
        assertNotNull(origin)
        assertEquals("127.0.0.1", origin.clientIp)
        assertFalse(origin.viaTrustedProxy)

        val v6 = TrustedProxies.resolve(ip("::1"), null, emptyList(), emptyList())
        assertNotNull(v6)
        assertFalse(v6.viaTrustedProxy)
    }

    @Test
    fun `trusted proxy single hop yields the appended client address`() {
        val origin = TrustedProxies.resolve(ip("192.0.2.10"), "https", listOf("203.0.113.50"), proxy)
        assertNotNull(origin)
        assertEquals("203.0.113.50", origin.clientIp)
        assertTrue(origin.viaTrustedProxy)
    }

    @Test
    fun `client-supplied left-hand XFF entries are ignored`() {
        // Client sent "X-Forwarded-For: 203.0.113.66"; the proxy appended the real peer.
        val origin = TrustedProxies.resolve(
            ip("192.0.2.10"), "https", listOf("203.0.113.66, 203.0.113.50"), proxy)
        assertEquals("203.0.113.50", origin?.clientIp)
    }

    @Test
    fun `multi-hop chain skips every trusted proxy from the right`() {
        // client -> 198.51.100.7 (edge proxy) -> 192.0.2.3 (inner proxy) -> server
        val origin = TrustedProxies.resolve(
            ip("192.0.2.3"), "https", listOf("203.0.113.66, 203.0.113.50, 198.51.100.7"), chain)
        assertEquals("203.0.113.50", origin?.clientIp)
    }

    @Test
    fun `multiple XFF header lines are combined in order`() {
        val origin = TrustedProxies.resolve(
            ip("192.0.2.3"), "https", listOf("203.0.113.66", "203.0.113.50", "198.51.100.7"), chain)
        assertEquals("203.0.113.50", origin?.clientIp)
    }

    @Test
    fun `all-trusted chain falls back to the left-most entry`() {
        val origin = TrustedProxies.resolve(ip("192.0.2.3"), "https", listOf("192.0.2.4"), chain)
        assertEquals("192.0.2.4", origin?.clientIp)
    }

    @Test
    fun `IPv6 client and IPv6 proxy`() {
        val origin = TrustedProxies.resolve(
            ip("2001:db8:ffff::2"), "https", listOf("2001:db8:1::99"), proxy)
        assertNotNull(origin)
        assertEquals(ip("2001:db8:1::99").hostAddress, origin.clientIp)
    }

    @Test
    fun `trusted proxy without https or without XFF is rejected`() {
        assertNull(TrustedProxies.resolve(ip("192.0.2.10"), "http", listOf("203.0.113.50"), proxy))
        assertNull(TrustedProxies.resolve(ip("192.0.2.10"), null, listOf("203.0.113.50"), proxy))
        assertNull(TrustedProxies.resolve(ip("192.0.2.10"), "https", emptyList(), proxy))
    }

    @Test
    fun `malformed right-most untrusted entry is rejected`() {
        assertNull(TrustedProxies.resolve(ip("192.0.2.10"), "https", listOf("203.0.113.5, garbage"), proxy))
    }

    // ---------------------- best effort / forwarded header ----------------------

    @Test
    fun `bestEffortClientIp honors XFF only from trusted peers`() {
        assertEquals("203.0.113.50",
            TrustedProxies.bestEffortClientIp(ip("192.0.2.10"), listOf("203.0.113.50"), proxy))
        assertEquals("198.51.100.20",
            TrustedProxies.bestEffortClientIp(ip("198.51.100.20"), listOf("203.0.113.50"), proxy))
        // Trusted peer, no XFF: the peer itself.
        assertEquals("192.0.2.10",
            TrustedProxies.bestEffortClientIp(ip("192.0.2.10"), emptyList(), proxy))
    }

    @Test
    fun `forwardedHeaderIfTrusted gates on the peer`() {
        assertEquals("https", TrustedProxies.forwardedHeaderIfTrusted(ip("192.0.2.10"), "https", proxy))
        assertNull(TrustedProxies.forwardedHeaderIfTrusted(ip("198.51.100.20"), "https", proxy))
    }

    @Test
    fun `configure replaces and clears the global list`() {
        try {
            TrustedProxies.configure("192.0.2.10")
            assertTrue(TrustedProxies.isTrusted(ip("192.0.2.10")))
            TrustedProxies.configure("")
            assertFalse(TrustedProxies.isTrusted(ip("192.0.2.10")))
        } finally {
            TrustedProxies.configure(null)
        }
    }
}
