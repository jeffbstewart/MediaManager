import Foundation
import Testing
import MediaManagerProtos
@testable import MediaManagerCore

/// `DownloadManager` over a `StorageScope` on fresh temp folders, with
/// its pre-scope folder at `Legacy/Downloads`.
@MainActor
private struct Videos {
    let base: URL
    let scope: StorageScope
    let downloads: DownloadManager

    var legacy: URL { Self.legacy(base) }

    static func legacy(_ base: URL) -> URL {
        base.appendingPathComponent("Legacy/Downloads", isDirectory: true)
    }

    init(base: URL? = nil) {
        self.base = base ?? FileManager.default.temporaryDirectory
            .appendingPathComponent("VideoDownloadScopeTests-\(UUID().uuidString)")
        scope = StorageScope(durableBase: self.base.appendingPathComponent("support"),
                             cacheBase: self.base.appendingPathComponent("caches"))
        downloads = DownloadManager(scope: scope, legacyFolder: Self.legacy(self.base))
    }

    /// A new manager and scope over the same folders, as a relaunch would.
    func reopen() -> Videos { Videos(base: base) }

    /// Apply the events, then wait for the store opened by the bind
    /// to load.
    func signIn(server: String, account: Int64) async {
        scope.send(.serverConnected(serverFingerprint: server))
        scope.send(.accountSignedIn(accountId: account))
        await settled()
    }

    func signOut() async {
        scope.send(.suspend(.signedOut))
        await settled()
    }

    func settled() async {
        await scope.settled()
        await downloads.loaded()
    }

    /// The bound scope's downloads folder, without creating one.
    func scopedFolder(server: String, account: Int64) -> URL {
        StorageScope.directory(
            for: .downloads,
            in: StorageScopeID(serverFingerprint: server, accountId: account),
            root: base.appendingPathComponent("support/Scoped", isDirectory: true))
    }

    func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    /// Writes a completed video download the way the pre-scope build
    /// did: database and backup, the video, its poster and TV
    /// metadata, beside the book and audio folders.
    func writeLegacyVideo(transcodeId: Int64, titleId: Int64) async throws {
        let fm = FileManager.default
        try fm.createDirectory(at: legacy, withIntermediateDirectories: true)
        let store = DownloadStore(folder: legacy)
        var entry = MMDownloadEntry()
        entry.transcodeID = transcodeId
        entry.titleID = titleId
        entry.titleName = "A Film"
        entry.state = .completed
        let added = await store.addEntry(entry)
        // A second write leaves the backup beside the database.
        await store.updateEntry(transcodeId: transcodeId) { $0.fileSizeBytes = 5 }
        try Data("video".utf8).write(to: await store.videoPath(for: added))
        try Data("jpg".utf8).write(to: await store.posterPath(for: added))
        let tv = legacy.appendingPathComponent("tv/\(titleId)", isDirectory: true)
        try fm.createDirectory(at: tv, withIntermediateDirectories: true)
        try Data("season".utf8).write(to: tv.appendingPathComponent("season-1.pb"))
        for other in ["Books", "Audio"] {
            let folder = legacy.appendingPathComponent(other, isDirectory: true)
            try fm.createDirectory(at: folder, withIntermediateDirectories: true)
            try Data("index".utf8).write(to: folder.appendingPathComponent("index.json"))
        }
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: base)
    }
}

@MainActor
@Suite("Video downloads per storage scope")
struct VideoDownloadScopeTests {

    private let film = "0000001"

    // MARK: - Unbound

    @Test func withNoScopeTheListIsEmptyAndNothingStarts() async throws {
        let v = Videos(); defer { v.cleanUp() }
        try await v.writeLegacyVideo(transcodeId: 42, titleId: 4)
        let v2 = v.reopen()
        await v2.settled()

        #expect(v2.downloads.entries.isEmpty)
        #expect(v2.downloads.localVideoURL(for: 42) == nil)
        v2.downloads.startDownload(transcodeId: 43, titleId: 5, titleName: "B",
                                   quality: .unknown, year: 2000, mediaType: .movie,
                                   contentRating: .unknown)
        await v2.settled()
        #expect(v2.downloads.entries.isEmpty)
        #expect(v2.downloads.activeDownloadCount == 0)
        // Unbound, the old content's owner is not known yet: left alone.
        #expect(v.exists(v.legacy.appendingPathComponent("downloads.meta.db")))
        #expect(v.exists(v.legacy.appendingPathComponent("\(film).mp4")))
    }

    // MARK: - Content from before scopes

    @Test func oldDownloadsAreAdoptedOnTheFirstBindOnTheirServer() async throws {
        let v = Videos(); defer { v.cleanUp() }
        try await v.writeLegacyVideo(transcodeId: 42, titleId: 4)
        let v2 = v.reopen()
        v2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await v2.signIn(server: "server-a", account: 7)

        let folder = v.scopedFolder(server: "server-a", account: 7)
        #expect(v2.downloads.entries.map(\.transcodeID) == [42])
        #expect(v2.downloads.localVideoURL(for: 42)?.standardizedFileURL
                == folder.appendingPathComponent("\(film).mp4").standardizedFileURL)
        for name in ["downloads.meta.db", "downloads.meta.db.backup",
                     "posters/\(film).jpg", "tv/4/season-1.pb"] {
            #expect(v.exists(folder.appendingPathComponent(name)), "\(name) moved")
            #expect(!v.exists(v.legacy.appendingPathComponent(name)), "\(name) gone from the old folder")
        }
        // The book and audio folders belong to their own stores.
        #expect(v.exists(v.legacy.appendingPathComponent("Books/index.json")))
        #expect(v.exists(v.legacy.appendingPathComponent("Audio/index.json")))
        #expect(!v.exists(folder.appendingPathComponent("Books")))
        #expect(!v.exists(folder.appendingPathComponent("Audio")))
    }

    @Test func oldDownloadsAreDeletedWhenAnotherServerBindsFirst() async throws {
        let v = Videos(); defer { v.cleanUp() }
        try await v.writeLegacyVideo(transcodeId: 42, titleId: 4)
        let v2 = v.reopen()
        v2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await v2.signIn(server: "server-b", account: 7)

        #expect(v2.downloads.entries.isEmpty)
        #expect(v2.downloads.localVideoURL(for: 42) == nil)
        let folder = v.scopedFolder(server: "server-b", account: 7)
        #expect(!v.exists(folder.appendingPathComponent("\(film).mp4")))
        for name in ["downloads.meta.db", "downloads.meta.db.backup", "\(film).mp4", "posters", "tv"] {
            #expect(!v.exists(v.legacy.appendingPathComponent(name)), "\(name) deleted")
        }
        #expect(v.exists(v.legacy.appendingPathComponent("Books/index.json")))
        #expect(v.exists(v.legacy.appendingPathComponent("Audio/index.json")))
    }

    @Test func oldDownloadsAreAdoptedWhenTheScopeIsAlreadyBoundAtLaunch() async throws {
        let v = Videos(); defer { v.cleanUp() }
        try await v.writeLegacyVideo(transcodeId: 42, titleId: 4)
        // A build whose other stores were scoped, but not video
        // downloads, bound the scope on an earlier launch.
        let earlier = StorageScope(durableBase: v.base.appendingPathComponent("support"),
                                   cacheBase: v.base.appendingPathComponent("caches"))
        earlier.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        earlier.send(.serverConnected(serverFingerprint: "server-a"))
        earlier.send(.accountSignedIn(accountId: 7))
        await earlier.settled()

        let v2 = v.reopen()
        await v2.settled()
        #expect(v2.downloads.entries.map(\.transcodeID) == [42])
        #expect(v2.downloads.localVideoURL(for: 42) != nil)
    }

    // MARK: - Scope changes

    @Test func signingOutEmptiesTheListAndSigningBackInRestoresIt() async throws {
        let v = Videos(); defer { v.cleanUp() }
        try await v.writeLegacyVideo(transcodeId: 42, titleId: 4)
        let v2 = v.reopen()
        v2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await v2.signIn(server: "server-a", account: 7)
        #expect(v2.downloads.entries.count == 1)

        await v2.signOut()
        #expect(v2.downloads.entries.isEmpty)
        #expect(v2.downloads.localVideoURL(for: 42) == nil)
        #expect(v2.downloads.localDir(for: TranscodeID(rawValue: 42)) == nil)

        await v2.signIn(server: "server-a", account: 7)
        #expect(v2.downloads.entries.map(\.transcodeID) == [42])
        #expect(v2.downloads.localVideoURL(for: 42) != nil)
    }

    @Test func anotherServerSeesNoneOfThisServersDownloads() async throws {
        let v = Videos(); defer { v.cleanUp() }
        try await v.writeLegacyVideo(transcodeId: 42, titleId: 4)
        let v2 = v.reopen()
        v2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await v2.signIn(server: "server-a", account: 7)
        let folderA = v.scopedFolder(server: "server-a", account: 7)
        #expect(v.exists(folderA.appendingPathComponent("\(film).mp4")))

        await v2.signIn(server: "server-b", account: 7)
        #expect(v2.downloads.entries.isEmpty)
        #expect(v2.downloads.localVideoURL(for: 42) == nil)
        #expect(!v.exists(folderA), "the previous server's downloads are deleted")
    }

    @Test func aDifferentAccountDeletesThePreviousAccountsDownloads() async throws {
        let v = Videos(); defer { v.cleanUp() }
        try await v.writeLegacyVideo(transcodeId: 42, titleId: 4)
        let v2 = v.reopen()
        v2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await v2.signIn(server: "server-a", account: 7)
        await v2.signOut()

        await v2.signIn(server: "server-a", account: 8)
        #expect(v2.downloads.entries.isEmpty)
        #expect(!v.exists(v.scopedFolder(server: "server-a", account: 7)))
    }

    @Test func aWriteQueuedBeforeAChangeLandsInTheScopeItWasMadeIn() async throws {
        let v = Videos(); defer { v.cleanUp() }
        try await v.writeLegacyVideo(transcodeId: 42, titleId: 4)
        let v2 = v.reopen()
        v2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await v2.signIn(server: "server-a", account: 7)

        // Queued, then the scope changes before it can run: the change
        // waits for it, so it lands in this scope's store.
        v2.downloads.queueProgressUpdate(transcodeId: 42, position: 120, duration: 600)
        await v2.signOut()
        #expect(v2.downloads.entries.isEmpty)
        // Unbound: a position is dropped, not written anywhere.
        v2.downloads.queueProgressUpdate(transcodeId: 42, position: 300, duration: 600)

        await v2.signIn(server: "server-a", account: 7)
        let entry = try #require(v2.downloads.entries.first)
        #expect(entry.playbackPosition.seconds == 120)
        #expect(!entry.positionSynced)
    }
}
