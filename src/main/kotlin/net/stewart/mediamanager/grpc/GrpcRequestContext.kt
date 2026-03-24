package net.stewart.mediamanager.grpc

import io.grpc.Context
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import java.net.InetAddress
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
        val isLocal: Boolean
    )

    fun resolve(headers: Metadata, call: ServerCall<*, *>): TransportContext? {
        val remoteAddr = call.attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)
        if (isLocalTransport(remoteAddr)) {
            return TransportContext(clientIp = localClientIp(remoteAddr), isLocal = true)
        }

        val proto = headers.get(FORWARDED_PROTO_KEY)?.trim()
        if (!proto.equals("https", ignoreCase = true)) return null

        val forwardedFor = headers.get(FORWARDED_FOR_KEY)?.trim().orEmpty()
        val clientIp = parseForwardedFor(forwardedFor) ?: return null
        return TransportContext(clientIp = clientIp, isLocal = false)
    }

    internal fun parseForwardedFor(value: String): String? {
        val first = value.split(',').firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return null
        if (!first.contains('.') && !first.contains(':')) return null
        if (!first.matches(Regex("^[0-9A-Fa-f:.]+$"))) return null
        return try {
            InetAddress.getByName(first).hostAddress
        } catch (_: Exception) {
            null
        }
    }

    internal fun isLocalTransport(remoteAddr: SocketAddress?): Boolean {
        if (remoteAddr == null) return false
        if (remoteAddr.javaClass.simpleName == "InProcessSocketAddress") return true
        val inet = remoteAddr as? InetSocketAddress ?: return false
        return inet.address?.isLoopbackAddress == true
    }

    private fun localClientIp(remoteAddr: SocketAddress?): String {
        if (remoteAddr == null) return "127.0.0.1"
        val inet = remoteAddr as? InetSocketAddress
        return inet?.address?.hostAddress ?: "127.0.0.1"
    }
}
