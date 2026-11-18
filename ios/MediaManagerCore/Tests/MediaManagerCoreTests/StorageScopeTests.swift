import Foundation
import Testing
@testable import MediaManagerCore

/// Records every callback, in order.
private final class RecordingParticipant: StorageScopeParticipant, @unchecked Sendable {
    private let lock = NSLock()
    private var _calls: [String] = []
    var calls: [String] { lock.withLock { _calls } }

    func storageScopeWillChange() async {
        lock.withLock { _calls.append("will") }
    }

    func storageScopeDidChange(to scope: StorageScopeID?) async {
        lock.withLock { _calls.append("did:\(scope.map { "\($0.accountId)" } ?? "nil")") }
    }
}

/// A scope over fresh temp folders. `reopen()` builds a second
/// instance over the same folders, as a relaunch would.
private struct Fixture {
    let base: URL
    var durable: URL { base.appendingPathComponent("support") }
    var caches: URL { base.appendingPathComponent("caches") }

    init() {
        base = FileManager.default.temporaryDirectory
            .appendingPathComponent("StorageScopeTests-\(UUID().uuidString)")
    }

    func open() -> StorageScope {
        StorageScope(durableBase: durable, cacheBase: caches)
    }

    func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    /// Writes a marker file into `area` of the bound scope.
    func touch(_ scope: StorageScope, _ area: StorageArea) throws -> URL {
        let folder = try #require(scope.directory(for: area))
        let file = folder.appendingPathComponent("marker")
        try Data("x".utf8).write(to: file)
        return file
    }

    /// Writes old, pre-scope content and returns its URL.
    func legacyFile(_ name: String) throws -> URL {
        let folder = durable.appendingPathComponent("Legacy")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent(name)
        try Data("old".utf8).write(to: file)
        return file
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: base)
    }
}

private extension StorageScope {
    func signIn(server: String, account: Int64) async {
        send(.serverConnected(serverFingerprint: server))
        send(.accountSignedIn(accountId: account))
        await settled()
    }
}

@Suite("Storage scope")
struct StorageScopeTests {

    @Test func nothingIsBoundAtFirst() async {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        #expect(scope.current == nil)
        #expect(scope.directory(for: .images) == nil)
    }

    @Test func aServerAloneBindsNothing() async {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        scope.send(.serverConnected(serverFingerprint: "server-a"))
        await scope.settled()
        #expect(scope.current == nil)
    }

    @Test func foldersFollowTheLayoutAndStorageClass() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        await scope.signIn(server: "server-a", account: 7)
        #expect(scope.current == StorageScopeID(serverFingerprint: "server-a", accountId: 7))

        let server = StorageScope.serverFolderName("server-a")
        let images = try #require(scope.directory(for: .images))
        let downloads = try #require(scope.directory(for: .downloads))
        #expect(images.path.hasSuffix("caches/Scoped/servers/\(server)/accounts/7/images"))
        #expect(downloads.path.hasSuffix("support/Scoped/servers/\(server)/accounts/7/downloads"))
        #expect(f.exists(images) && f.exists(downloads))
    }

    @Test func serverFolderNamesHideTheFingerprint() {
        let name = StorageScope.serverFolderName("AB:CD/../ef")
        #expect(name.count == 32)
        #expect(name.allSatisfy { $0.isHexDigit })
        #expect(name != StorageScope.serverFolderName("AB:CD/../eg"))
    }

    @Test func aDifferentServerDeletesTheOldServersContent() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        await scope.signIn(server: "server-a", account: 7)
        let durable = try f.touch(scope, .downloads)
        let cached = try f.touch(scope, .images)

        await scope.signIn(server: "server-b", account: 7)
        #expect(!f.exists(durable))
        #expect(!f.exists(cached))
        #expect(scope.current == StorageScopeID(serverFingerprint: "server-b", accountId: 7))
    }

    @Test func connectingToADifferentServerDeletesBeforeAnyoneSignsIn() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        await scope.signIn(server: "server-a", account: 7)
        let file = try f.touch(scope, .progress)
        scope.send(.suspend(.serverChanging))
        scope.send(.serverConnected(serverFingerprint: "server-b"))
        await scope.settled()
        #expect(!f.exists(file))
        #expect(scope.current == nil)
    }

    @Test func aDifferentAccountDeletesTheOldAccountsContent() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        await scope.signIn(server: "server-a", account: 7)
        let old = try f.touch(scope, .books)
        scope.send(.suspend(.signedOut))
        await scope.signIn(server: "server-a", account: 8)
        #expect(!f.exists(old))
        #expect(scope.current?.accountId == 8)
    }

    @Test func signingOutAndBackInKeepsContent() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        await scope.signIn(server: "server-a", account: 7)
        let file = try f.touch(scope, .audio)

        scope.send(.suspend(.signedOut))
        await scope.settled()
        #expect(scope.current == nil)
        #expect(scope.directory(for: .audio) == nil)
        #expect(f.exists(file))

        await scope.signIn(server: "server-a", account: 7)
        #expect(f.exists(file))
    }

    @Test func followingTheSameServerToANewAddressKeepsContent() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        await scope.signIn(server: "server-a", account: 7)
        let file = try f.touch(scope, .downloads)
        scope.send(.suspend(.serverChanging))
        scope.send(.serverConnected(serverFingerprint: "server-a"))
        scope.send(.resumeLastAccount)
        await scope.settled()
        #expect(f.exists(file))
        #expect(scope.current?.accountId == 7)
    }

    @Test func resumingIsRefusedAfterSigningOut() async {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        await scope.signIn(server: "server-a", account: 7)
        scope.send(.suspend(.signedOut))
        scope.send(.resumeLastAccount)
        await scope.settled()
        #expect(scope.current == nil)
    }

    @Test func theScopeSurvivesARelaunch() async {
        let f = Fixture(); defer { f.cleanUp() }
        let first = f.open()
        await first.signIn(server: "server-a", account: 7)
        let second = f.open()
        #expect(second.current == StorageScopeID(serverFingerprint: "server-a", accountId: 7))
    }

    @Test func forgettingEverythingDeletesEverything() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        await scope.signIn(server: "server-a", account: 7)
        let durable = try f.touch(scope, .downloads)
        let cached = try f.touch(scope, .images)
        scope.send(.forgetEverything)
        await scope.settled()
        #expect(!f.exists(durable) && !f.exists(cached))
        #expect(scope.current == nil)
        scope.send(.resumeLastAccount)
        await scope.settled()
        #expect(scope.current == nil)
    }

    @Test func forgettingTheAccountDeletesOnlyItsContent() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        await scope.signIn(server: "server-a", account: 7)
        let file = try f.touch(scope, .progress)
        scope.send(.forgetCurrentAccount)
        await scope.settled()
        #expect(!f.exists(file))
        #expect(scope.current == nil)
    }

    @Test func participantsHearWillThenDidInOrder() async {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        let participant = RecordingParticipant()
        scope.register(participant)

        await scope.signIn(server: "server-a", account: 7)
        scope.send(.suspend(.signedOut))
        await scope.signIn(server: "server-a", account: 8)
        await scope.signIn(server: "server-a", account: 8)  // no change, no calls

        #expect(participant.calls == ["did:7", "will", "did:nil", "did:8"])
    }

    // MARK: - Content from before scopes

    @Test func oldContentIsAdoptedByTheFirstAccountOnItsServer() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        let old = try f.legacyFile("downloads.meta.db")
        scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await scope.signIn(server: "server-a", account: 7)

        #expect(scope.claimLegacy(at: old, into: .downloads, as: "downloads.meta.db") == .adopted)
        let moved = try #require(scope.directory(for: .downloads))
            .appendingPathComponent("downloads.meta.db")
        #expect(f.exists(moved))
        #expect(!f.exists(old))
        #expect(scope.claimLegacy(at: old, into: .downloads, as: "downloads.meta.db") == .nothingThere)
    }

    @Test func oldContentIsDiscardedOnADifferentServer() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        let old = try f.legacyFile("index.json")
        scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await scope.signIn(server: "server-b", account: 7)
        #expect(scope.claimLegacy(at: old, into: .books, as: "index.json") == .discarded)
        #expect(!f.exists(old))
    }

    @Test func oldContentWithNoKnownOwnerIsDiscarded() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        let old = try f.legacyFile("index.json")
        scope.send(.recordLegacyOwner(serverFingerprint: nil))
        await scope.signIn(server: "server-a", account: 7)
        #expect(scope.claimLegacy(at: old, into: .audio, as: "index.json") == .discarded)
    }

    @Test func oldContentIsLeftAloneUntilAScopeIsBound() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        let old = try f.legacyFile("queue.json")
        scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await scope.settled()
        #expect(scope.claimLegacy(at: old, into: .progress, as: "queue.json") == .notBound)
        #expect(f.exists(old))
    }

    @Test func onlyTheFirstAccountAdoptsOldContent() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        let old = try f.legacyFile("queue.json")
        scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await scope.signIn(server: "server-a", account: 7)
        scope.send(.suspend(.signedOut))
        await scope.signIn(server: "server-a", account: 8)
        #expect(scope.claimLegacy(at: old, into: .progress, as: "queue.json") == .discarded)
    }

    @Test func theLegacyOwnerIsRecordedOnce() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        let old = try f.legacyFile("queue.json")
        scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        scope.send(.recordLegacyOwner(serverFingerprint: "server-b"))
        await scope.signIn(server: "server-a", account: 7)
        #expect(scope.claimLegacy(at: old, into: .progress, as: "queue.json") == .adopted)
    }

    @Test func existingContentWinsOverOldContent() async throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await scope.signIn(server: "server-a", account: 7)
        let current = try f.touch(scope, .progress)
        let old = try f.legacyFile("marker")
        #expect(scope.claimLegacy(at: old, into: .progress, as: "marker") == .discarded)
        #expect(try String(contentsOf: current, encoding: .utf8) == "x")
        #expect(!f.exists(old))
    }

    @Test func discardingOldContentDeletesIt() throws {
        let f = Fixture(); defer { f.cleanUp() }
        let scope = f.open()
        let old = try f.legacyFile("images")
        scope.discardLegacy(at: old)
        #expect(!f.exists(old))
    }
}
