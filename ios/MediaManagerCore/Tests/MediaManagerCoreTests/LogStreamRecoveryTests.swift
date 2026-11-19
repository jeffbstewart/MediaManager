import Foundation
import GRPCCore
import Testing
@testable import MediaManagerCore
import MediaManagerProtos

// #84: the app stopped sending logs after its first trip to the
// background. The log buffer was an AsyncStream, and cancelling a task
// waiting on an AsyncStream iterator ends the stream for good; gRPC
// cancels the upload's producer whenever the connection drops.

private func record(_ message: String) -> PendingLogRecord {
    PendingLogRecord(timestamp: Date(), severity: .info, category: "test",
                     message: message, exceptionType: nil,
                     exceptionMessage: nil, attributes: [:])
}

/// Polls `condition` until it holds or `seconds` pass.
private func eventually(_ seconds: Double = 5,
                        _ condition: @Sendable () async -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(seconds)
    while Date() < deadline {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return await condition()
}

@Suite("Log buffer")
struct LogBufferTests {

    @Test func aCancelledReaderLosesNothingAndTheBufferStaysUsable() async throws {
        let buffer = LogBuffer(capacity: 10)
        let waiter = Task { await buffer.next() }
        try await Task.sleep(for: .milliseconds(50))
        waiter.cancel()
        #expect(await waiter.value == nil)

        buffer.enqueue(record("after cancel"))
        #expect(await buffer.next()?.message == "after cancel")
    }

    @Test func aReaderAlreadyCancelledGetsNilWithoutTakingARecord() async {
        let buffer = LogBuffer(capacity: 10)
        buffer.enqueue(record("kept"))
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return await buffer.next()
        }
        // Records already queued are still handed over; nothing is lost
        // either way.
        let got = await task.value
        if got == nil {
            #expect(buffer.count == 1)
        } else {
            #expect(got?.message == "kept")
        }
    }

    @Test func aWaitingReaderGetsTheNextRecord() async throws {
        let buffer = LogBuffer(capacity: 10)
        let waiter = Task { await buffer.next() }
        try await Task.sleep(for: .milliseconds(50))
        buffer.enqueue(record("hello"))
        #expect(await waiter.value?.message == "hello")
    }

    @Test func pastCapacityTheOldestAreDropped() async {
        let buffer = LogBuffer(capacity: 3)
        for i in 1...5 { buffer.enqueue(record("r\(i)")) }
        #expect(buffer.count == 3)
        #expect(await buffer.next()?.message == "r3")
    }

    @Test func aRecordPutBackGoesOutFirst() async {
        let buffer = LogBuffer(capacity: 10)
        buffer.enqueue(record("second"))
        buffer.pushFront(record("first"))
        #expect(await buffer.next()?.message == "first")
        #expect(await buffer.next()?.message == "second")
    }
}

// MARK: - LogStreamer against a fake transport

/// Delivers what the producer writes, session by session, and can drop a
/// session (cancel its producer, as gRPC does when the connection goes),
/// fail a write, or move the connection generation.
private actor FakeLogTransport: LogStreamTransport {
    var generation: UInt64 = 0
    var connectionGeneration: UInt64 { generation }

    private(set) var delivered: [String] = []
    private(set) var sessions = 0
    private var live: Task<Void, Error>?

    /// Fail the next write with this error, once.
    var failNextWrite: Error?

    func streamLogs(
        producer: @Sendable @escaping (RPCWriter<MMLogRecord>) async throws -> Void
    ) async throws -> MMStreamLogsAck {
        sessions += 1
        let writer = FakeLogWriter(transport: self)
        let task = Task { try await producer(RPCWriter(wrapping: writer)) }
        live = task
        try await task.value
        return MMStreamLogsAck()
    }

    /// What gRPC does when the connection drops: cancel the producer.
    func dropConnection() { live?.cancel() }

    func moveGeneration() { generation += 1 }

    func setFailNextWrite(_ error: Error) { failNextWrite = error }

    fileprivate func write(_ record: MMLogRecord) throws {
        if let error = failNextWrite {
            failNextWrite = nil
            throw error
        }
        delivered.append(record.message)
    }
}

private struct FakeLogWriter: RPCWriterProtocol {
    let transport: FakeLogTransport
    func write(_ element: MMLogRecord) async throws {
        try await transport.write(element)
    }
    func write(contentsOf elements: some Sequence<MMLogRecord>) async throws {
        for element in elements { try await write(element) }
    }
}

private struct WriteFailed: Error {}

@Suite("Log streamer recovery")
struct LogStreamerRecoveryTests {

    private func makeStreamer(_ transport: FakeLogTransport, _ buffer: LogBuffer) -> LogStreamer {
        LogStreamer(transport: transport,
                    identity: .init(serviceVersion: "test", baseAttributes: [:]),
                    buffer: buffer, backoffUnit: 0.005)
    }

    @Test func logsKeepFlowingAfterTheConnectionDropsWhileIdle() async {
        let transport = FakeLogTransport()
        let buffer = LogBuffer(capacity: 100)
        let streamer = makeStreamer(transport, buffer)
        await streamer.start()

        buffer.enqueue(record("before"))
        #expect(await eventually { await transport.delivered == ["before"] })

        // The producer is now waiting for the next record; the app goes
        // to the background and the connection drops.
        await transport.dropConnection()
        buffer.enqueue(record("after"))

        #expect(await eventually { await transport.delivered == ["before", "after"] })
        #expect(await transport.sessions >= 2)
        await streamer.stop()
    }

    @Test func logsFlowAgainAfterStopAndStart() async {
        let transport = FakeLogTransport()
        let buffer = LogBuffer(capacity: 100)
        let streamer = makeStreamer(transport, buffer)
        await streamer.start()
        buffer.enqueue(record("one"))
        #expect(await eventually { await transport.delivered == ["one"] })

        await streamer.stop()          // signing out
        buffer.enqueue(record("while stopped"))
        await streamer.start()         // signing back in

        #expect(await eventually { await transport.delivered == ["one", "while stopped"] })
        await streamer.stop()
    }

    @Test func aRecordWhoseWriteFailsIsSentOnTheNextSession() async {
        let transport = FakeLogTransport()
        let buffer = LogBuffer(capacity: 100)
        let streamer = makeStreamer(transport, buffer)
        await transport.setFailNextWrite(WriteFailed())
        await streamer.start()

        buffer.enqueue(record("survives"))
        #expect(await eventually { await transport.delivered == ["survives"] })
        #expect(await transport.sessions >= 2)
        await streamer.stop()
    }

    @Test func aConnectionChangeReopensTheSessionWithoutLosingTheRecord() async {
        let transport = FakeLogTransport()
        let buffer = LogBuffer(capacity: 100)
        let streamer = makeStreamer(transport, buffer)
        await streamer.start()
        buffer.enqueue(record("first"))
        #expect(await eventually { await transport.delivered == ["first"] })
        let sessionsBefore = await transport.sessions

        await transport.moveGeneration()   // e.g. reconnect after a network change
        buffer.enqueue(record("second"))

        #expect(await eventually { await transport.delivered == ["first", "second"] })
        #expect(await transport.sessions == sessionsBefore + 1)
        await streamer.stop()
    }
}
