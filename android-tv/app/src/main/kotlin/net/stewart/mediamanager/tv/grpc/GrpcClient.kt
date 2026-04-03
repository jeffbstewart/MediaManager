package net.stewart.mediamanager.tv.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import net.stewart.mediamanager.grpc.AuthServiceGrpcKt
import net.stewart.mediamanager.grpc.CatalogServiceGrpcKt
import net.stewart.mediamanager.grpc.InfoServiceGrpcKt
import net.stewart.mediamanager.grpc.discoverRequest
import net.stewart.mediamanager.tv.auth.AuthManager

class GrpcClient(private val authManager: AuthManager) {
    private var channel: ManagedChannel? = null

    private fun getChannel(): ManagedChannel {
        val existing = channel
        if (existing != null && !existing.isShutdown) return existing

        val host = authManager.grpcHost
            ?: throw IllegalStateException("No server configured")
        val port = authManager.grpcPort

        val newChannel = buildChannel(host, port, authManager.useTls, AuthInterceptor(authManager))
        channel = newChannel
        return newChannel
    }

    /** Unauthenticated -- for Login and Discover */
    fun authService(): AuthServiceGrpcKt.AuthServiceCoroutineStub =
        AuthServiceGrpcKt.AuthServiceCoroutineStub(getChannel())

    /** Unauthenticated -- for Discover */
    fun infoService(): InfoServiceGrpcKt.InfoServiceCoroutineStub =
        InfoServiceGrpcKt.InfoServiceCoroutineStub(getChannel())

    /** Authenticated */
    fun catalogService(): CatalogServiceGrpcKt.CatalogServiceCoroutineStub =
        CatalogServiceGrpcKt.CatalogServiceCoroutineStub(getChannel())

    /** Shut down and recreate on next call (e.g. after server address change) */
    fun resetChannel() {
        channel?.shutdownNow()
        channel = null
    }

    /**
     * Test connectivity to a server without modifying stored auth state.
     * Creates a temporary channel, calls Discover, then shuts it down.
     */
    suspend fun testDiscover(host: String, port: Int, useTls: Boolean) {
        val testChannel = buildChannel(host, port, useTls)
        try {
            InfoServiceGrpcKt.InfoServiceCoroutineStub(testChannel)
                .discover(discoverRequest { })
        } finally {
            testChannel.shutdownNow()
        }
    }

    companion object {
        private fun buildChannel(
            host: String,
            port: Int,
            useTls: Boolean,
            interceptor: ClientInterceptor? = null
        ): ManagedChannel {
            val builder = OkHttpChannelBuilder.forAddress(host, port)
            if (useTls) {
                builder.useTransportSecurity()
            } else {
                builder.usePlaintext()
            }
            interceptor?.let { builder.intercept(it) }
            return builder.build()
        }
    }
}

private val AUTHORIZATION_KEY: Metadata.Key<String> =
    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

private class AuthInterceptor(private val authManager: AuthManager) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel
    ): ClientCall<ReqT, RespT> {
        return object : SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions)
        ) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                authManager.accessToken?.let { token ->
                    // Server expects "Bearer <jwt>" in the ASCII "authorization" header.
                    // The token bytes are a UTF-8 encoded JWT string.
                    val jwt = String(token, Charsets.UTF_8)
                    headers.put(AUTHORIZATION_KEY, "Bearer $jwt")
                }
                super.start(responseListener, headers)
            }
        }
    }
}
