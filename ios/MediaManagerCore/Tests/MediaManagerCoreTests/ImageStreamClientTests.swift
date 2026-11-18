import Foundation
import GRPCCore
import Testing
@testable import MediaManagerCore
import MediaManagerProtos

/// Stands in for `GrpcClient`. Each `streamImages` call is one opened
/// stream; a fetch written to it is answered at once, unless its title
/// id is `FakeImageTransport.silentTitleId`, which is never answered.
private actor FakeImageTransport: ImageStreamTransport {
    static let silentTitleId: Int64 = 999

    private var generation: UInt64 = 1
    /// Per opened stream, the title ids of the fetches written to it.
    private(set) var streams: [[Int64]] = []

    var connectionGeneration: UInt64 { generation }

    func advanceGeneration() { generation += 1 }

    func streamImages(
        requestProducer: @Sendable @escaping (RPCWriter<MMImageRequest>) async throws -> Void,
        onMessage: @Sendable @escaping (MMImageResponse) async -> Void
    ) async throws {
        streams.append([])
        let index = streams.count - 1
        let writer = FakeWriter(transport: self, streamIndex: index, onMessage: onMessage)
        // Runs until the client cancels the stream.
        try await requestProducer(RPCWriter(wrapping: writer))
    }

    fileprivate func received(_ request: MMImageRequest, onStream index: Int) -> MMImageResponse? {
        guard case .fetch(let fetch) = request.request else { return nil }
        streams[index].append(fetch.ref.titleID)
        guard fetch.ref.titleID != Self.silentTitleId else { return nil }
        var response = MMImageResponse()
        response.requestID = fetch.requestID
        response.ref = fetch.ref
        response.notFound = MMImageNotFound()
        return response
    }
}

private struct FakeWriter: RPCWriterProtocol {
    let transport: FakeImageTransport
    let streamIndex: Int
    let onMessage: @Sendable (MMImageResponse) async -> Void

    func write(_ element: MMImageRequest) async throws {
        if let response = await transport.received(element, onStream: streamIndex) {
            await onMessage(response)
        }
    }

    func write(contentsOf elements: some Sequence<MMImageRequest>) async throws {
        for element in elements { try await write(element) }
    }
}

@Suite("Image stream reopens on a connection change")
struct ImageStreamClientTests {

    @Test func requestsShareOneStreamWhileTheConnectionIsUnchanged() async {
        let transport = FakeImageTransport()
        let client = ImageStreamClient(transport: transport)

        #expect(await client.fetch(ref: .posterThumbnail(titleId: 1), etag: nil) != nil)
        #expect(await client.fetch(ref: .posterThumbnail(titleId: 2), etag: nil) != nil)

        #expect(await transport.streams == [[1, 2]])
        await client.close()
    }

    @Test func aGenerationChangeMakesTheNextRequestReopenTheStream() async {
        let transport = FakeImageTransport()
        let client = ImageStreamClient(transport: transport)

        #expect(await client.fetch(ref: .posterThumbnail(titleId: 1), etag: nil) != nil)
        await transport.advanceGeneration()
        #expect(await client.fetch(ref: .posterThumbnail(titleId: 2), etag: nil) != nil)
        #expect(await client.fetch(ref: .posterThumbnail(titleId: 3), etag: nil) != nil)

        // The request after the change went out on a new stream, and
        // nothing more was sent on the old one.
        #expect(await transport.streams == [[1], [2, 3]])
        await client.close()
    }

    @Test func closeMakesTheNextRequestOpenANewStream() async {
        let transport = FakeImageTransport()
        let client = ImageStreamClient(transport: transport)

        #expect(await client.fetch(ref: .posterThumbnail(titleId: 1), etag: nil) != nil)
        await client.close()
        #expect(await client.fetch(ref: .posterThumbnail(titleId: 2), etag: nil) != nil)

        #expect(await transport.streams == [[1], [2]])
        await client.close()
    }

    @Test func aRequestWaitingOnAClosedStreamReturnsNil() async throws {
        let transport = FakeImageTransport()
        let client = ImageStreamClient(transport: transport)

        let waiting = Task {
            await client.fetch(ref: .posterThumbnail(titleId: FakeImageTransport.silentTitleId), etag: nil)
        }
        // Wait until the request is on the wire.
        for _ in 0..<500 {
            if await transport.streams.first?.isEmpty == false { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(await transport.streams == [[FakeImageTransport.silentTitleId]])

        await client.close()
        #expect(await waiting.value == nil)
    }
}

@Suite("GrpcClient connection generation")
struct GrpcClientConnectionGenerationTests {

    @Test func settingATokenWhereThereWasNoneCounts() async {
        let client = GrpcClient()
        let start = await client.connectionGeneration
        await client.setAccessToken(Data("a".utf8))
        #expect(await client.connectionGeneration == start + 1)
    }

    @Test func aRefreshDoesNotCount() async {
        let client = GrpcClient()
        await client.setAccessToken(Data("a".utf8))
        let before = await client.connectionGeneration
        await client.setAccessToken(Data("b".utf8))
        #expect(await client.connectionGeneration == before)
    }

    @Test func clearingTheTokenCounts() async {
        let client = GrpcClient()
        await client.setAccessToken(Data("a".utf8))
        let before = await client.connectionGeneration
        await client.setAccessToken(nil)
        #expect(await client.connectionGeneration == before + 1)
    }

    @Test func clearingWhenAlreadyClearDoesNotCount() async {
        let client = GrpcClient()
        let before = await client.connectionGeneration
        await client.setAccessToken(nil)
        #expect(await client.connectionGeneration == before)
    }

    @Test func closeCounts() async {
        let client = GrpcClient()
        let before = await client.connectionGeneration
        await client.close()
        #expect(await client.connectionGeneration == before + 1)
    }

    @Test func theHandlerHearsEachChange() async {
        let client = GrpcClient()
        let heard = Heard()
        let start = await client.setConnectionGenerationHandler { heard.append($0) }
        await client.setAccessToken(Data("a".utf8))
        await client.close()
        #expect(heard.values == [start + 1, start + 2])
    }
}

private final class Heard: @unchecked Sendable {
    private let lock = NSLock()
    private var _values: [UInt64] = []
    var values: [UInt64] { lock.withLock { _values } }
    func append(_ v: UInt64) { lock.withLock { _values.append(v) } }
}
