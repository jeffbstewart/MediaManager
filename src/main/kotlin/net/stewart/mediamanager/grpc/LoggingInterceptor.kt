package net.stewart.mediamanager.grpc

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory

/**
 * gRPC [ServerInterceptor] that logs every RPC for operational observability.
 *
 * Logs on completion:
 * - **INFO:** successful RPCs — method, user (if authenticated), response time
 * - **WARN:** client errors (NOT_FOUND, INVALID_ARGUMENT, etc.)
 * - **ERROR:** internal/unexpected failures with full context
 *
 * Sensitive data rules:
 * - Never logs auth tokens, passwords, or usernames from failed auth attempts
 *   (user may have typed password in the username field)
 * - Authenticated username (from gRPC context, post-validation) is safe to log
 *
 * Exception safety:
 * - Catches any unhandled exception from service methods
 * - Logs the full stack trace at ERROR level
 * - Returns Status.INTERNAL to the client (never exposing exception details)
 * - StatusException/StatusRuntimeException pass through (these are intentional)
 */
class LoggingInterceptor : ServerInterceptor {

    private val log = LoggerFactory.getLogger("grpc.access")

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        val startNanos = System.nanoTime()

        // Wrap the ServerCall to capture the status on close
        val wrappedCall = object : SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun close(status: Status, trailers: Metadata) {
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                val username = try { USER_CONTEXT_KEY.get()?.username } catch (_: Exception) { null } ?: "-"

                when {
                    status.isOk -> {
                        log.info("OK {} {}ms user={}", method, elapsedMs, username)
                    }
                    isClientError(status) -> {
                        // Client errors: log at WARN with the description
                        // Redact description for auth methods (may contain username-as-password)
                        val desc = if (method in AUTH_METHODS) "[redacted]"
                            else (status.description ?: status.code.name)
                        log.warn("{} {} {}ms user={} {}", status.code, method, elapsedMs, username, desc)
                    }
                    else -> {
                        // Server errors: log at ERROR
                        val desc = status.description ?: status.code.name
                        val cause = status.cause
                        if (cause != null) {
                            log.error("{} {} {}ms user={} {}", status.code, method, elapsedMs, username, desc, cause)
                        } else {
                            log.error("{} {} {}ms user={} {}", status.code, method, elapsedMs, username, desc)
                        }
                    }
                }
                super.close(status, trailers)
            }
        }

        // Wrap the listener to catch exceptions thrown by service methods.
        // grpc-kotlin translates StatusException to status errors, but unexpected
        // exceptions (NPE, DB errors) would propagate as UNKNOWN — we want to
        // log those and return INTERNAL instead.
        val listener = try {
            next.startCall(wrappedCall, headers)
        } catch (e: Exception) {
            logAndClose(wrappedCall, method, startNanos, e)
            return noop()
        }

        return object : SimpleForwardingServerCallListener<ReqT>(listener) {
            override fun onHalfClose() {
                try {
                    super.onHalfClose()
                } catch (e: StatusException) {
                    wrappedCall.close(e.status, e.trailers ?: Metadata())
                } catch (e: StatusRuntimeException) {
                    wrappedCall.close(e.status, e.trailers ?: Metadata())
                } catch (e: Exception) {
                    logAndClose(wrappedCall, method, startNanos, e)
                }
            }

            override fun onMessage(message: ReqT) {
                try {
                    super.onMessage(message)
                } catch (e: StatusException) {
                    wrappedCall.close(e.status, e.trailers ?: Metadata())
                } catch (e: StatusRuntimeException) {
                    wrappedCall.close(e.status, e.trailers ?: Metadata())
                } catch (e: Exception) {
                    logAndClose(wrappedCall, method, startNanos, e)
                }
            }
        }
    }

    private fun <ReqT, RespT> logAndClose(
        call: ServerCall<ReqT, RespT>,
        method: String,
        startNanos: Long,
        e: Exception
    ) {
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        log.error("INTERNAL {}  {}ms  unhandled exception", method, elapsedMs, e)
        try {
            call.close(Status.INTERNAL.withDescription("Internal server error"), Metadata())
        } catch (_: Exception) {
            // Call may already be closed
        }
    }

    private fun <ReqT> noop(): ServerCall.Listener<ReqT> = object : ServerCall.Listener<ReqT>() {}

    companion object {
        /** Auth-related methods where error descriptions may contain sensitive data. */
        private val AUTH_METHODS = setOf(
            "mediamanager.AuthService/Login",
            "mediamanager.AuthService/Refresh",
            "mediamanager.AuthService/Revoke",
            "mediamanager.AuthService/ChangePassword"
        )

        /** Client-error status codes — user's fault, not a server bug. */
        private fun isClientError(status: Status): Boolean = status.code in setOf(
            Status.Code.INVALID_ARGUMENT,
            Status.Code.NOT_FOUND,
            Status.Code.ALREADY_EXISTS,
            Status.Code.PERMISSION_DENIED,
            Status.Code.UNAUTHENTICATED,
            Status.Code.FAILED_PRECONDITION,
            Status.Code.RESOURCE_EXHAUSTED,
            Status.Code.UNIMPLEMENTED
        )
    }
}
