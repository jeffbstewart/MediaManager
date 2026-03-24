package net.stewart.mediamanager.grpc

import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrpcRequestContextTest {

    @Test
    fun `parseForwardedFor accepts first valid IP from proxy chain`() {
        val parsed = GrpcRequestContext.parseForwardedFor("203.0.113.10, 10.0.0.5")
        assertEquals("203.0.113.10", parsed)
    }

    @Test
    fun `parseForwardedFor rejects blank and invalid values`() {
        assertNull(GrpcRequestContext.parseForwardedFor(""))
        assertNull(GrpcRequestContext.parseForwardedFor("not-an-ip"))
    }

    @Test
    fun `loopback transport is treated as local`() {
        assertTrue(GrpcRequestContext.isLocalTransport(InetSocketAddress("127.0.0.1", 1234)))
        assertTrue(GrpcRequestContext.isLocalTransport(InetSocketAddress("::1", 1234)))
    }

    @Test
    fun `non loopback transport is not treated as local`() {
        assertFalse(GrpcRequestContext.isLocalTransport(InetSocketAddress("10.0.0.5", 1234)))
    }
}
