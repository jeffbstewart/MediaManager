package net.stewart.mediamanager.tv.grpc

import com.google.protobuf.ByteString
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import net.stewart.mediamanager.grpc.AuthServiceGrpcKt
import net.stewart.mediamanager.grpc.CatalogServiceGrpcKt
import net.stewart.mediamanager.grpc.ImageServiceGrpcKt
import net.stewart.mediamanager.grpc.InfoServiceGrpcKt
import net.stewart.mediamanager.grpc.discoverRequest
import net.stewart.mediamanager.grpc.refreshRequest
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

    fun authService(): AuthServiceGrpcKt.AuthServiceCoroutineStub =
        AuthServiceGrpcKt.AuthServiceCoroutineStub(getChannel())

    fun infoService(): InfoServiceGrpcKt.InfoServiceCoroutineStub =
        InfoServiceGrpcKt.InfoServiceCoroutineStub(getChannel())

    fun catalogService(): CatalogServiceGrpcKt.CatalogServiceCoroutineStub =
        CatalogServiceGrpcKt.CatalogServiceCoroutineStub(getChannel())

    fun imageService(): ImageServiceGrpcKt.ImageServiceCoroutineStub =
        ImageServiceGrpcKt.ImageServiceCoroutineStub(getChannel())

    fun resetChannel() {
        channel?.shutdownNow()
        channel = null
    }

    /**
     * Execute an authenticated gRPC call with automatic token refresh.
     * If the call fails with UNAUTHENTICATED, refreshes the access token
     * using the stored refresh token and retries once.
     */
    suspend fun <T> withAuth(call: suspend () -> T): T {
        return try {
            call()
        } catch (e: StatusException) {
            if (e.status.code == Status.Code.UNAUTHENTICATED && tryRefreshToken()) {
                call()
            } else throw e
        } catch (e: StatusRuntimeException) {
            if (e.status.code == Status.Code.UNAUTHENTICATED && tryRefreshToken()) {
                call()
            } else throw e
        }
    }

    private suspend fun tryRefreshToken(): Boolean {
        val refresh = authManager.refreshToken ?: return false
        val username = authManager.activeUsername ?: return false
        return try {
            val response = authService().refresh(refreshRequest {
                this.refreshToken = ByteString.copyFrom(refresh)
            })
            authManager.addAccount(
                username,
                response.accessToken.toByteArray(),
                response.refreshToken.toByteArray()
            )
            true
        } catch (e: Exception) {
            false
        }
    }

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
                    val jwt = String(token, Charsets.UTF_8)
                    headers.put(AUTHORIZATION_KEY, "Bearer $jwt")
                }
                super.start(responseListener, headers)
            }
        }
    }
}
