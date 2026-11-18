import Foundation
import Testing
@testable import MediaManagerCore

/// The progress stores over a `StorageScope` on fresh temp folders.
/// `reopen()` builds new instances over the same folders, as a
/// relaunch would.
private struct Stores {
    let base: URL
    let scope: StorageScope
    let local: LocalProgressStore
    let reading: ReadingProgressQueue
    let listening: ListeningProgressQueue

    var legacy: URL { base.appendingPathComponent("Legacy") }

    init(base: URL? = nil) {
        self.base = base ?? FileManager.default.temporaryDirectory
            .appendingPathComponent("ProgressStoresScopeTests-\(UUID().uuidString)")
        scope = StorageScope(durableBase: self.base.appendingPathComponent("support"),
                             cacheBase: self.base.appendingPathComponent("caches"))
        let legacy = self.base.appendingPathComponent("Legacy")
        local = LocalProgressStore(scope: scope, legacyDirectory: legacy)
        reading = ReadingProgressQueue(scope: scope, legacyDirectory: legacy, localStore: local)
        listening = ListeningProgressQueue(scope: scope, legacyDirectory: legacy)
    }

    func reopen() -> Stores { Stores(base: base) }

    func signIn(server: String, account: Int64) async {
        scope.send(.serverConnected(serverFingerprint: server))
        scope.send(.accountSignedIn(accountId: account))
        await scope.settled()
    }

    func signOut() async {
        scope.send(.suspend(.signedOut))
        await scope.settled()
    }

    func writeLegacy(_ name: String, _ data: Data) throws {
        try FileManager.default.createDirectory(at: legacy, withIntermediateDirectories: true)
        try data.write(to: legacy.appendingPathComponent(name))
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: base)
    }
}

private struct SendFailed: Error {}

/// What a flush sent, in order.
private final class SentLog: @unchecked Sendable {
    private let lock = NSLock()
    private var _ids: [Int64] = []
    var ids: [Int64] { lock.withLock { _ids } }
    func append(_ id: Int64) { lock.withLock { _ids.append(id) } }
}

/// Holds a send until opened. Ignores cancellation, as a send already
/// on the wire would.
private actor Gate {
    private var open = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if open { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        open = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }
}

/// Polls `condition` until it holds or two seconds pass.
private func eventually(_ condition: () async -> Bool) async -> Bool {
    for _ in 0..<400 {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return await condition()
}

@Suite("Progress stores per storage scope")
struct ProgressStoresScopeTests {

    // MARK: - Unbound

    @Test func withNoScopeNothingIsReadWrittenOrSent() async {
        let s = Stores(); defer { s.cleanUp() }
        await s.local.recordPlayback(transcodeId: 1, position: 30, duration: 60)
        await s.reading.record(mediaItemId: 2, locator: "/page/3", fraction: 0.5, recordedAt: Date())
        await s.listening.enqueue(trackId: 3, position: 10, duration: nil)

        #expect(await s.local.playback(transcodeId: 1) == nil)
        #expect(await s.reading.pending().isEmpty)
        #expect(await s.listening.pending().isEmpty)

        let sent = SentLog()
        await s.reading.flush { sent.append($0.mediaItemId) }
        await s.listening.flush { sent.append($0.trackId) }
        #expect(sent.ids.isEmpty)

        // Nothing written while unbound turns up once a scope binds.
        await s.signIn(server: "server-a", account: 7)
        #expect(await s.local.playback(transcodeId: 1) == nil)
        #expect(await s.reading.pending().isEmpty)
        #expect(await s.listening.pending().isEmpty)
    }

    // MARK: - Kept per scope

    @Test func entriesWrittenUnderOneScopeAreNotVisibleUnderAnother() async {
        let s = Stores(); defer { s.cleanUp() }
        await s.signIn(server: "server-a", account: 7)
        await s.local.recordPlayback(transcodeId: 1, position: 30, duration: 60)
        await s.reading.record(mediaItemId: 2, locator: "/page/3", fraction: 0.5, recordedAt: Date())
        await s.listening.enqueue(trackId: 3, position: 10, duration: nil)
        #expect(await s.local.playback(transcodeId: 1)?.positionSeconds == 30)
        #expect(await s.local.reading(mediaItemId: 2)?.locator == "/page/3")

        await s.signOut()
        #expect(await s.local.playback(transcodeId: 1) == nil)
        #expect(await s.local.readingEntriesByRecency().isEmpty)
        #expect(await s.reading.entry(mediaItemId: 2) == nil)
        #expect(await s.listening.pending().isEmpty)

        await s.signIn(server: "server-a", account: 8)
        #expect(await s.local.playback(transcodeId: 1) == nil)
        #expect(await s.reading.pending().isEmpty)
        #expect(await s.listening.pending().isEmpty)

        await s.signOut()
        await s.signIn(server: "server-b", account: 7)
        #expect(await s.local.playback(transcodeId: 1) == nil)
        #expect(await s.reading.count == 0)
        #expect(await s.listening.pending().isEmpty)
    }

    @Test func signingBackInToTheSameAccountFindsEverything() async {
        let s = Stores(); defer { s.cleanUp() }
        await s.signIn(server: "server-a", account: 7)
        await s.local.recordPlayback(transcodeId: 1, position: 30, duration: 60)
        await s.reading.record(mediaItemId: 2, locator: "/page/3", fraction: 0.5, recordedAt: Date())
        // Signing out straight away: the debounced save must still land.
        await s.listening.enqueue(trackId: 3, position: 10, duration: nil)

        await s.signOut()
        await s.signIn(server: "server-a", account: 7)
        #expect(await s.local.playback(transcodeId: 1)?.positionSeconds == 30)
        #expect(await s.reading.entry(mediaItemId: 2)?.locator == "/page/3")
        #expect(await s.listening.pending().map(\.trackId) == [3])
    }

    @Test func aRelaunchLoadsTheBoundScope() async {
        let first = Stores(); defer { first.cleanUp() }
        await first.signIn(server: "server-a", account: 7)
        await first.local.recordReading(mediaItemId: 5, locator: "cfi", fraction: 0.2)
        await first.reading.record(mediaItemId: 5, locator: "cfi", fraction: 0.2, recordedAt: Date())
        await first.listening.enqueue(trackId: 9, position: 4, duration: 8)
        try? await Task.sleep(for: .milliseconds(600))  // debounced save

        let second = first.reopen()
        #expect(await second.local.reading(mediaItemId: 5)?.locator == "cfi")
        #expect(await second.reading.entry(mediaItemId: 5)?.locator == "cfi")
        #expect(await second.listening.pending().map(\.trackId) == [9])
    }

    @Test func clearingEmptiesTheBoundScope() async {
        let s = Stores(); defer { s.cleanUp() }
        await s.signIn(server: "server-a", account: 7)
        await s.local.recordPlayback(transcodeId: 1, position: 30, duration: 60)
        await s.reading.record(mediaItemId: 2, locator: "/page/3", fraction: 0.5, recordedAt: Date())
        await s.listening.enqueue(trackId: 3, position: 10, duration: nil)
        await s.local.clear()
        await s.reading.clear()
        await s.listening.clear()
        #expect(await s.local.playback(transcodeId: 1) == nil)
        #expect(await s.reading.pending().isEmpty)
        #expect(await s.listening.pending().isEmpty)

        let relaunched = s.reopen()
        #expect(await relaunched.local.playback(transcodeId: 1) == nil)
        #expect(await relaunched.reading.pending().isEmpty)
    }

    // MARK: - Content from before scopes

    @Test func theOldFilesAreClaimedOnTheFirstBind() async throws {
        let s = Stores(); defer { s.cleanUp() }
        let date = Date(timeIntervalSince1970: 1_700_000_000)
        let playback: [Int64: LocalProgressStore.PlaybackEntry] = [
            1: .init(transcodeId: 1, positionSeconds: 42, durationSeconds: nil, updatedAt: date),
        ]
        try s.writeLegacy(LocalProgressStore.playbackFileName, JSONEncoder().encode(playback))
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let queued = [ReadingProgressQueueEntry(mediaItemId: 2, locator: "/page/9",
                                                fraction: 0.9, recordedAt: date)]
        try s.writeLegacy(ReadingProgressQueue.fileName, encoder.encode(queued))
        let listening = [ListeningProgressQueue.Entry(trackId: 3, position: 5, duration: nil,
                                                      recordedAt: date)]
        try s.writeLegacy(ListeningProgressQueue.fileName, JSONEncoder().encode(listening))

        s.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await s.signIn(server: "server-a", account: 7)

        #expect(await s.local.playback(transcodeId: 1)?.positionSeconds == 42)
        #expect(await s.reading.entry(mediaItemId: 2)?.locator == "/page/9")
        #expect(await s.listening.pending().map(\.trackId) == [3])
        for name in [LocalProgressStore.playbackFileName, ReadingProgressQueue.fileName,
                     ListeningProgressQueue.fileName] {
            #expect(!FileManager.default.fileExists(
                atPath: s.legacy.appendingPathComponent(name).path))
        }
    }

    @Test func theOldFilesAreDiscardedOnAnotherServer() async throws {
        let s = Stores(); defer { s.cleanUp() }
        let queued = [ListeningProgressQueue.Entry(trackId: 3, position: 5, duration: nil,
                                                   recordedAt: Date())]
        try s.writeLegacy(ListeningProgressQueue.fileName, JSONEncoder().encode(queued))
        s.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await s.signIn(server: "server-b", account: 7)
        #expect(await s.listening.pending().isEmpty)
        #expect(!FileManager.default.fileExists(
            atPath: s.legacy.appendingPathComponent(ListeningProgressQueue.fileName).path))
    }

    // MARK: - Flushing

    @Test func aFlushSendsAndRemovesWhatItSent() async {
        let s = Stores(); defer { s.cleanUp() }
        await s.signIn(server: "server-a", account: 7)
        for id: Int64 in 1...3 {
            await s.reading.record(mediaItemId: id, locator: "l\(id)", fraction: 0, recordedAt: Date())
            await s.listening.enqueue(trackId: id, position: 1, duration: nil)
        }
        let sentReading = SentLog()
        let sentListening = SentLog()
        await s.reading.flush { entry in
            sentReading.append(entry.mediaItemId)
            if entry.mediaItemId == 2 { throw SendFailed() }
        }
        await s.listening.flush { sentListening.append($0.trackId) }
        #expect(Set(sentReading.ids) == [1, 2, 3])
        #expect(Set(sentListening.ids) == [1, 2, 3])
        #expect(await s.reading.pending().map(\.mediaItemId) == [2])
        #expect(await s.listening.pending().isEmpty)
        // LocalProgressStore keeps the position after the flush.
        #expect(await s.local.reading(mediaItemId: 1)?.locator == "l1")
    }

    @Test func aReadingFlushStartedUnderOneScopeSendsNothingAfterASwitch() async {
        let s = Stores(); defer { s.cleanUp() }
        await s.signIn(server: "server-a", account: 7)
        for id: Int64 in 1...3 {
            await s.reading.record(mediaItemId: id, locator: "a\(id)", fraction: 0, recordedAt: Date())
        }
        let sent = SentLog()
        let gate = Gate()
        let flush = Task {
            await s.reading.flush { entry in
                sent.append(entry.mediaItemId)
                await gate.wait()  // the first send is on the wire
            }
        }
        #expect(await eventually { sent.ids.count == 1 })
        let epoch = await s.reading.currentEpochForTesting

        s.scope.send(.suspend(.signedOut))
        s.scope.send(.serverConnected(serverFingerprint: "server-b"))
        s.scope.send(.accountSignedIn(accountId: 8))
        // The switch waits for the send in progress; let it finish now.
        #expect(await eventually { await s.reading.currentEpochForTesting != epoch })
        await gate.release()
        await flush.value
        await s.scope.settled()

        #expect(sent.ids.count == 1)
        #expect(s.scope.current?.serverFingerprint == "server-b")

        // Scope B sends only its own entries.
        await s.reading.record(mediaItemId: 50, locator: "b", fraction: 0, recordedAt: Date())
        let sentInB = SentLog()
        await s.reading.flush { sentInB.append($0.mediaItemId) }
        #expect(sentInB.ids == [50])
    }

    @Test func aListeningFlushIsCancelledByASwitch() async {
        let s = Stores(); defer { s.cleanUp() }
        await s.signIn(server: "server-a", account: 7)
        for id: Int64 in 1...3 {
            await s.listening.enqueue(trackId: id, position: 1, duration: nil)
        }
        let started = SentLog()
        let completed = SentLog()
        let flush = Task {
            await s.listening.flush { entry in
                started.append(entry.trackId)
                // A send that honours cancellation, as a gRPC call does.
                try await Task.sleep(for: .seconds(30))
                completed.append(entry.trackId)
            }
        }
        #expect(await eventually { started.ids.count == 1 })

        s.scope.send(.suspend(.signedOut))
        s.scope.send(.accountSignedIn(accountId: 8))
        await s.scope.settled()
        await flush.value

        #expect(started.ids.count == 1)
        #expect(completed.ids.isEmpty)
        #expect(s.scope.current?.accountId == 8)
        #expect(await s.listening.pending().isEmpty)
    }

    @Test func aFlushStopsWhenSignedOutPartWay() async {
        let s = Stores(); defer { s.cleanUp() }
        await s.signIn(server: "server-a", account: 7)
        for id: Int64 in 1...3 {
            await s.listening.enqueue(trackId: id, position: 1, duration: nil)
        }
        let sent = SentLog()
        let gate = Gate()
        let flush = Task {
            await s.listening.flush { entry in
                sent.append(entry.trackId)
                await gate.wait()
            }
        }
        #expect(await eventually { sent.ids.count == 1 })
        let epoch = await s.listening.currentEpochForTesting
        s.scope.send(.suspend(.signedOut))
        #expect(await eventually { await s.listening.currentEpochForTesting != epoch })
        await gate.release()
        await flush.value
        await s.scope.settled()
        #expect(sent.ids.count == 1)

        // Everything waits for the same account, including the entry
        // whose send straddled the change: sending it again is harmless,
        // the server keeps the most recent position.
        await s.signIn(server: "server-a", account: 7)
        #expect(await s.listening.pending().count == 3)
    }
}
