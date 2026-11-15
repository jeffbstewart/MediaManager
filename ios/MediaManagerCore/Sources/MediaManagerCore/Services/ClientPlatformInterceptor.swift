import GRPCCore

/// Names this client's platform on every gRPC call, so the server's legal
/// gate checks the iOS terms-of-use version. Without the header a Bearer
/// caller is held to the highest terms version of any platform, which the
/// iOS terms screen cannot satisfy when another platform's is higher.
///
/// Installed once on the `GRPCClient` in `GrpcClient.configure`, so it
/// covers every RPC made through it: unary and streaming, authenticated
/// and not. Per-call metadata (the Bearer token) is left as it is.
public struct ClientPlatformInterceptor: ClientInterceptor {
    /// Metadata key the server reads the platform from. Wire contract:
    /// mirrors `CLIENT_PLATFORM_KEY` in the server's
    /// `grpc/AuthInterceptor.kt`, so do not rename it.
    public static let headerName = "x-client-platform"

    /// This client's platform id. Wire contract: mirrors
    /// `LegalRequirements.PLATFORM_IOS` on the server, which keys the
    /// iOS terms-of-use version, so do not rename it.
    public static let platformIOS = "ios"

    public init() {}

    public func intercept<Input: Sendable, Output: Sendable>(
        request: StreamingClientRequest<Input>,
        context: ClientContext,
        next: (
            _ request: StreamingClientRequest<Input>,
            _ context: ClientContext
        ) async throws -> StreamingClientResponse<Output>
    ) async throws -> StreamingClientResponse<Output> {
        var request = request
        request.metadata.replaceOrAddString(Self.platformIOS, forKey: Self.headerName)
        return try await next(request, context)
    }
}
