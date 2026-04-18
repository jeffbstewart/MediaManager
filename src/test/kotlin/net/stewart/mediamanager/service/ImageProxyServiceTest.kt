package net.stewart.mediamanager.service

import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for [ImageProxyService]'s SSRF guards and content-type
 * helper. The network-fetch path is intentionally out of scope — it'd
 * need a local HTTP server, and the value per line of test code is low.
 */
class ImageProxyServiceTest {

    private fun v4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        Inet4Address.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun v6(hex: String): InetAddress = InetAddress.getByName(hex)

    @Test
    fun `private IPv4 ranges are rejected`() {
        assertNotNull(ImageProxyService.disallowedAddressReason(v4(127, 0, 0, 1)), "loopback")
        assertNotNull(ImageProxyService.disallowedAddressReason(v4(10, 0, 0, 1)), "10/8")
        assertNotNull(ImageProxyService.disallowedAddressReason(v4(172, 16, 4, 12)), "172.16/12 (our NAS)")
        assertNotNull(ImageProxyService.disallowedAddressReason(v4(192, 168, 1, 1)), "192.168/16")
        assertNotNull(ImageProxyService.disallowedAddressReason(v4(169, 254, 1, 1)), "link-local")
        assertNotNull(ImageProxyService.disallowedAddressReason(v4(0, 0, 0, 0)), "wildcard")
    }

    @Test
    fun `public IPv4 addresses pass`() {
        // image.tmdb.org / covers.openlibrary.org resolve somewhere here — no specific IP,
        // just check that a handful of obvious public addresses survive the guard.
        assertNull(ImageProxyService.disallowedAddressReason(v4(1, 1, 1, 1)))
        assertNull(ImageProxyService.disallowedAddressReason(v4(8, 8, 8, 8)))
        assertNull(ImageProxyService.disallowedAddressReason(v4(142, 250, 72, 142)))
    }

    @Test
    fun `IPv6 loopback and link-local rejected`() {
        assertNotNull(ImageProxyService.disallowedAddressReason(v6("::1")))
        assertNotNull(ImageProxyService.disallowedAddressReason(v6("fe80::1")))
    }

    @Test
    fun `IPv6 unique-local (ULA) rejected`() {
        assertNotNull(ImageProxyService.disallowedAddressReason(v6("fc00::1")))
        assertNotNull(ImageProxyService.disallowedAddressReason(v6("fd12:3456::1")))
    }

    @Test
    fun `public IPv6 pass`() {
        assertNull(ImageProxyService.disallowedAddressReason(v6("2001:4860:4860::8888")))  // Google DNS
        assertNull(ImageProxyService.disallowedAddressReason(v6("2606:4700:4700::1111")))  // Cloudflare DNS
    }

    @Test
    fun `resolveAndScreenHost rejects unresolvable names`() {
        val reason = ImageProxyService.resolveAndScreenHost(
            "this.name.does.not.exist.invalid-tld-example"
        )
        assertNotNull(reason)
        assertTrue(reason.contains("dns resolution failed", ignoreCase = true))
    }

    @Test
    fun `guessContentType maps extensions`() {
        assertEquals("image/jpeg", ImageProxyService.guessContentType("jpg"))
        assertEquals("image/jpeg", ImageProxyService.guessContentType("JPEG"))
        assertEquals("image/png", ImageProxyService.guessContentType("png"))
        assertEquals("image/webp", ImageProxyService.guessContentType("webp"))
        assertEquals("application/octet-stream", ImageProxyService.guessContentType("exe"))
    }

    @Test
    fun `Provider_of allowlist covers only known hosts`() {
        assertEquals(ImageProxyService.Provider.TMDB,
            ImageProxyService.Provider.of("image.tmdb.org"))
        assertEquals(ImageProxyService.Provider.OPEN_LIBRARY,
            ImageProxyService.Provider.of("covers.openlibrary.org"))
        assertNull(ImageProxyService.Provider.of("evil.example.com"))
        assertNull(ImageProxyService.Provider.of(""))
    }
}
