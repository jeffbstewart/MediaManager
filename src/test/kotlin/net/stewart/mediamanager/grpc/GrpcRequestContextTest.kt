package net.stewart.mediamanager.grpc

import io.grpc.Attributes
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.Status
import net.stewart.mediamanager.service.TrustedProxies
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrpcRequestContextTest {

    /** Minimal ServerCall exposing only a remote address — enough for [GrpcRequestContext.resolve]. */
    private class FakeCall(private val remote: SocketAddress?) : ServerCall<Any, Any>() {
        override fun getAttributes(): Attributes =
            Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remote).build()
        override fun getAuthority(): String = "media.example"
        override fun request(numMessages: Int) {}
        override fun sendHeaders(headers: Metadata) {}
        override fun sendMessage(message: Any) {}
        override fun close(status: Status, trailers: Metadata) {}
        override fun isCancelled(): Boolean = false
        override fun getMethodDescriptor(): MethodDescriptor<Any, Any> =
            throw UnsupportedOperationException()
    }

    private val proxies = TrustedProxies.parseList("192.0.2.10")

    private fun headers(proto: String? = null, vararg xff: String): Metadata = Metadata().apply {
        if (proto != null) put(Metadata.Key.of("x-forwarded-proto", Metadata.ASCII_STRING_MARSHALLER), proto)
        for (v in xff) put(Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER), v)
    }

    private fun peer(ip: String) = FakeCall(InetSocketAddress(ip, 40000))

    @Test
    fun `spoofed forwarded headers from an untrusted LAN peer are rejected`() {
        val ctx = GrpcRequestContext.resolve(
            headers("https", "203.0.113.9"), peer("198.51.100.20"), proxies)
        assertNull(ctx)
    }

    @Test
    fun `trusted proxy resolves the right-most untrusted client`() {
        val ctx = GrpcRequestContext.resolve(
            headers("https", "203.0.113.66, 203.0.113.9"), peer("192.0.2.10"), proxies)
        assertNotNull(ctx)
        assertEquals("203.0.113.9", ctx.clientIp)
        assertFalse(ctx.isLocal)
        assertEquals("media.example", ctx.authority)
    }

    @Test
    fun `trusted proxy combines repeated XFF metadata entries`() {
        val ctx = GrpcRequestContext.resolve(
            headers("https", "203.0.113.66", "203.0.113.9"), peer("192.0.2.10"), proxies)
        assertEquals("203.0.113.9", ctx?.clientIp)
    }

    @Test
    fun `trusted proxy forwarding plain http is rejected`() {
        assertNull(GrpcRequestContext.resolve(
            headers("http", "203.0.113.9"), peer("192.0.2.10"), proxies))
    }

    @Test
    fun `loopback peer is local and forwarded headers are ignored`() {
        val ctx = GrpcRequestContext.resolve(
            headers("https", "203.0.113.9"), peer("127.0.0.1"), proxies)
        assertNotNull(ctx)
        assertTrue(ctx.isLocal)
        assertEquals("127.0.0.1", ctx.clientIp)
    }

    @Test
    fun `IPv6 proxy and client`() {
        val v6Proxies = TrustedProxies.parseList("2001:db8::10")
        val ctx = GrpcRequestContext.resolve(
            headers("https", "2001:db8:1::5"), peer("2001:db8::10"), v6Proxies)
        assertNotNull(ctx)
        assertEquals(java.net.InetAddress.getByName("2001:db8:1::5").hostAddress, ctx.clientIp)
    }

    @Test
    fun `loopback transport is treated as local`() {
        assertTrue(GrpcRequestContext.isLocalTransport(InetSocketAddress("127.0.0.1", 1234)))
        assertTrue(GrpcRequestContext.isLocalTransport(InetSocketAddress("::1", 1234)))
    }

    @Test
    fun `non loopback transport is not treated as local`() {
        assertFalse(GrpcRequestContext.isLocalTransport(InetSocketAddress("192.0.2.5", 1234)))
    }
}
