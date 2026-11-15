import XCTest
import GRPCCore
import MediaManagerCore

/// Checks that `ClientPlatformInterceptor` names the iOS platform on a
/// request, without a server: the interceptor is handed a request and a
/// stand-in for the rest of the chain that records the metadata it is
/// given. The server side of the contract (a Bearer call carrying this
/// header is gated on the iOS terms version) is covered by
/// `AuthGrpcServiceTest` in the Kotlin suite.
///
/// The literals below are the wire contract, typed out on purpose: a
/// rename of either constant must fail here.
final class ClientPlatformInterceptorTests: XCTestCase {

    private static let wireHeaderName = "x-client-platform"
    private static let wirePlatformId = "ios"

    func testAddsPlatformHeaderAndKeepsAuthorization() async throws {
        let sent = try await metadataSent(with: ["authorization": "Bearer token"])

        XCTAssertEqual(Array(sent[stringValues: Self.wireHeaderName]), [Self.wirePlatformId])
        XCTAssertEqual(Array(sent[stringValues: "authorization"]), ["Bearer token"])
    }

    func testAddsPlatformHeaderToUnauthenticatedCall() async throws {
        let sent = try await metadataSent(with: [:])

        XCTAssertEqual(Array(sent[stringValues: Self.wireHeaderName]), [Self.wirePlatformId])
    }

    func testReplacesAPlatformHeaderTheCallAlreadyCarries() async throws {
        let sent = try await metadataSent(with: [Self.wireHeaderName: "web"])

        XCTAssertEqual(Array(sent[stringValues: Self.wireHeaderName]), [Self.wirePlatformId])
    }

    /// Runs the interceptor over a request carrying `metadata` and returns
    /// the metadata it passed on to the next link in the chain.
    private func metadataSent(with metadata: Metadata) async throws -> Metadata {
        let request = StreamingClientRequest<String>(metadata: metadata) { _ in }
        let context = ClientContext(
            descriptor: MethodDescriptor(fullyQualifiedService: "test.Service", method: "Call"),
            remotePeer: "in-memory:remote",
            localPeer: "in-memory:local"
        )
        let recorder = MetadataRecorder()

        let _: StreamingClientResponse<String> = try await ClientPlatformInterceptor().intercept(
            request: request,
            context: context
        ) { forwarded, _ in
            await recorder.record(forwarded.metadata)
            return StreamingClientResponse(
                of: String.self,
                error: RPCError(code: .unimplemented, message: "no server in this test")
            )
        }

        let recorded = await recorder.metadata
        return try XCTUnwrap(recorded, "the interceptor did not call the next link in the chain")
    }
}

private actor MetadataRecorder {
    private(set) var metadata: Metadata?

    func record(_ metadata: Metadata) {
        self.metadata = metadata
    }
}
