package net.stewart.mediamanager.grpc

import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.Context
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import net.stewart.mediamanager.service.IpNetwork
import net.stewart.mediamanager.service.TrustedProxies
import java.net.InetSocketAddress
import java.net.SocketAddress

/** Authenticated client IP captured from trusted proxy headers or local transport. */
val CLIENT_IP_CONTEXT_KEY: Context.Key<String> = Context.key("client-ip")

/** Convenience accessor for the originating client IP in the current gRPC context. */
fun currentClientIp(): String = CLIENT_IP_CONTEXT_KEY.get()
    ?: error("BUG: currentClientIp() called before request context was established")

internal object GrpcRequestContext {
    private val FORWARDED_PROTO_KEY: Metadata.Key<String> =
        Metadata.Key.of("x-forwarded-proto", Metadata.ASCII_STRING_MARSHALLER)
    private val FORWARDED_FOR_KEY: Metadata.Key<String> =
        Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER)

    data class TransportContext(
        val clientIp: String,
        val isLocal: Boolean,
        /** HTTP/2 `:authority` (== HTTP/1.1 `Host`) for this RPC. Null when the transport doesn't expose it. */
        val authority: String?
    )

    /**
     * Establish the caller's IP and whether the RPC arrived over a secure
     * path. Returns null when it did not: a non-loopback peer that is not a
     * configured trusted proxy, or a trusted proxy that didn't forward an
     * HTTPS request. Forwarded headers are evaluated by [TrustedProxies].
     */
    fun resolve(
        headers: Metadata,
        call: ServerCall<*, *>,
        trusted: List<IpNetwork> = TrustedProxies.configured(),
    ): TransportContext? {
        val authority = resolveAuthority(call)
        val remoteAddr = call.attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)
        if (isInProcess(remoteAddr)) {
            return TransportContext(clientIp = "127.0.0.1", isLocal = true, authority = authority)
        }
        val peer = (remoteAddr as? InetSocketAddress)?.address
        val origin = TrustedProxies.resolve(
            peer = peer,
            forwardedProto = headers.get(FORWARDED_PROTO_KEY),
            forwardedFor = headers.getAll(FORWARDED_FOR_KEY)?.toList().orEmpty(),
            trusted = trusted,
        ) ?: return null
        return TransportContext(
            clientIp = origin.clientIp,
            isLocal = !origin.viaTrustedProxy,
            authority = authority,
        )
    }

    /**
     * Authority of the inbound request. Used by AuthInterceptor's CSRF
     * gate to compare against the browser-supplied Origin header.
     *
     * Armeria's gRPC bridge does not populate grpc-java's
     * `ServerCall#getAuthority()` on the Netty transport — it returns
     * null for every real-world request. Armeria's own
     * `ServiceRequestContext` is the source of truth: it sees the
     * original `:authority` / `Host` header from the inbound HTTP/2
     * stream. Falling back to `call.authority` keeps the InProcess
     * test transport working (Armeria's context isn't installed there).
     */
    private fun resolveAuthority(call: ServerCall<*, *>): String? {
        val ctx = RequestContext.currentOrNull() as? ServiceRequestContext
        val armeriaAuthority = ctx?.request()?.authority()
        if (!armeriaAuthority.isNullOrBlank()) return armeriaAuthority
        return call.authority
    }

    private fun isInProcess(remoteAddr: SocketAddress?): Boolean =
        remoteAddr?.javaClass?.simpleName == "InProcessSocketAddress"

    internal fun isLocalTransport(remoteAddr: SocketAddress?): Boolean {
        if (remoteAddr == null) return false
        if (isInProcess(remoteAddr)) return true
        val inet = remoteAddr as? InetSocketAddress ?: return false
        return inet.address?.isLoopbackAddress == true
    }
}
