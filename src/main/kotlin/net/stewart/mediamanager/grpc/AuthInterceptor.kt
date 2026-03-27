package net.stewart.mediamanager.grpc

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.JwtService
import org.slf4j.LoggerFactory

/** Context key for the authenticated user. Service methods read this via [currentUser]. */
val USER_CONTEXT_KEY: Context.Key<AppUser> = Context.key("authenticated-user")

/** Convenience accessor for the authenticated user in the current gRPC context. */
fun currentUser(): AppUser = USER_CONTEXT_KEY.get()
    ?: error("BUG: currentUser() called in an unauthenticated RPC")

private val AUTHORIZATION_KEY: Metadata.Key<String> =
    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

/**
 * gRPC [ServerInterceptor] that validates JWT Bearer tokens and enforces access policies.
 *
 * Three RPC categories:
 * 1. **Unauthenticated** — no token required (Login, Refresh, Revoke, Discover)
 * 2. **Authenticated, gate-exempt** — token required but allowed even with must_change_password
 *    (ChangePassword, all ProfileService RPCs, InfoService/GetInfo)
 * 3. **Authenticated, gated** — token required AND blocked if must_change_password
 *
 * Admin enforcement: all RPCs under AdminService require [AppUser.isAdmin].
 */
class AuthInterceptor : ServerInterceptor {

    private val log = LoggerFactory.getLogger(AuthInterceptor::class.java)

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        val transport = GrpcRequestContext.resolve(headers, call)
        if (transport == null) {
            call.close(
                Status.PERMISSION_DENIED.withDescription("gRPC requires HTTPS via trusted reverse proxy"),
                Metadata()
            )
            return noop()
        }

        // Category 1: Unauthenticated RPCs — no token needed
        if (method in UNAUTHENTICATED_METHODS) {
            val ctx = Context.current().withValue(CLIENT_IP_CONTEXT_KEY, transport.clientIp)
            return Contexts.interceptCall(ctx, call, headers, next)
        }

        // All other RPCs require a valid Bearer token
        val token = extractBearer(headers)
        if (token == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing authorization token"), Metadata())
            return noop()
        }

        val user = JwtService.validateAccessToken(token)
        if (user == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid or expired token"), Metadata())
            return noop()
        }

        // Admin enforcement: all AdminService RPCs require admin role
        if (method.startsWith(ADMIN_SERVICE_PREFIX) && !user.isAdmin()) {
            call.close(Status.PERMISSION_DENIED.withDescription("Admin access required"), Metadata())
            return noop()
        }

        // Legal compliance: block if user hasn't agreed to current terms
        // (admins are exempt — they need access to configure the URLs)
        if (!net.stewart.mediamanager.service.LegalRequirements.isCompliant(user.id!!, user.isAdmin()) && !isGateExempt(method)) {
            call.close(
                Status.PERMISSION_DENIED.withDescription("legal_agreement_required"),
                Metadata()
            )
            return noop()
        }

        // Category 3 (gated): block if must_change_password, unless gate-exempt
        if (user.must_change_password && !isGateExempt(method)) {
            call.close(
                Status.PERMISSION_DENIED.withDescription("password_change_required"),
                Metadata()
            )
            return noop()
        }

        // Attach user to context and proceed
        val ctx = Context.current()
            .withValue(USER_CONTEXT_KEY, user)
            .withValue(CLIENT_IP_CONTEXT_KEY, transport.clientIp)
        return Contexts.interceptCall(ctx, call, headers, next)
    }

    private fun extractBearer(headers: Metadata): String? {
        val value = headers.get(AUTHORIZATION_KEY) ?: return null
        return if (value.startsWith("Bearer ", ignoreCase = true)) {
            value.substring(7)
        } else {
            null
        }
    }

    private fun isGateExempt(method: String): Boolean {
        return method in GATE_EXEMPT_METHODS || method.startsWith(PROFILE_SERVICE_PREFIX)
    }

    private fun <ReqT> noop(): ServerCall.Listener<ReqT> = object : ServerCall.Listener<ReqT>() {}

    companion object {
        private const val ADMIN_SERVICE_PREFIX = "mediamanager.AdminService/"
        private const val PROFILE_SERVICE_PREFIX = "mediamanager.ProfileService/"

        /** Category 1: No token required at all. */
        private val UNAUTHENTICATED_METHODS = setOf(
            "mediamanager.AuthService/Login",
            "mediamanager.AuthService/Refresh",
            "mediamanager.AuthService/Revoke",
            "mediamanager.AuthService/CreateFirstUser",
            "mediamanager.InfoService/Discover"
        )

        /** Category 2: Token required, but allowed even with must_change_password. */
        private val GATE_EXEMPT_METHODS = setOf(
            "mediamanager.AuthService/ChangePassword",
            "mediamanager.InfoService/GetInfo"
            // All ProfileService RPCs are handled by prefix check in isGateExempt()
        )
    }
}
