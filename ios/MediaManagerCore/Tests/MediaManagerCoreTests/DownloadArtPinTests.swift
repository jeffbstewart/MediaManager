import Foundation
import Testing
import MediaManagerProtos
@testable import MediaManagerCore

/// #91: downloaded titles' images stay pinned in the image cache across
/// scope changes and relaunches. Pins live in memory only, and
/// `ImageDiskCache` drops them on every scope change, so each download
/// store pins its downloads again whenever it loads a scope.
@MainActor
private struct Fixture {
    let base: URL
    let scope: StorageScope
    let images: ImageDiskCache
    let books: BookCacheManager
    let audio: AudioCacheManager
    let videos: DownloadManager

    var legacy: URL { base.appendingPathComponent("Legacy/Downloads", isDirectory: true) }

    init(base: URL? = nil) {
        self.base = base ?? FileManager.default.temporaryDirectory
            .appendingPathComponent("DownloadArtPinTests-\(UUID().uuidString)")
        scope = StorageScope(durableBase: self.base.appendingPathComponent("support"),
                             cacheBase: self.base.appendingPathComponent("caches"))
        images = ImageDiskCache(scope: scope, legacyDirectory: nil,
                                urlCache: URLCache(memoryCapacity: 1 << 20, diskCapacity: 0),
                                defaultsSuite: "DownloadArtPinTests-\(UUID().uuidString)")
        let legacy = self.base.appendingPathComponent("Legacy/Downloads", isDirectory: true)
        books = BookCacheManager(scope: scope, legacyFolder: legacy.appendingPathComponent("Books"),
                                 imageCache: images)
        audio = AudioCacheManager(scope: scope, legacyFolder: legacy.appendingPathComponent("Audio"),
                                  imageCache: images)
        videos = DownloadManager(scope: scope, legacyFolder: legacy, imageCache: images)
    }

    /// New stores, cache and scope over the same folders, as a relaunch would.
    func reopen() -> Fixture { Fixture(base: base) }

    func send(_ events: StorageScopeEvent...) async {
        for event in events { scope.send(event) }
        await scope.settled()
        await videos.loaded()
    }

    /// A downloaded book (title 1), album (title 2) and video (title 3),
    /// written the way the pre-scope build did.
    func writeLegacyDownloads() async throws {
        let fm = FileManager.default
        let books = legacy.appendingPathComponent("Books", isDirectory: true)
        try fm.createDirectory(at: books, withIntermediateDirectories: true)
        let book = DownloadedBook(
            mediaItemId: 11, titleId: 1, titleName: "A Book", authorName: "An Author",
            sizeBytes: 3, downloadedAt: Date(), lastAccessedAt: Date(), completedFraction: 0)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode([book]).write(to: books.appendingPathComponent("index.json"))
        try Data("epub".utf8).write(to: books.appendingPathComponent("11.epub"))

        let audio = legacy.appendingPathComponent("Audio", isDirectory: true)
        let album = audio.appendingPathComponent("2", isDirectory: true)
        try fm.createDirectory(at: album, withIntermediateDirectories: true)
        try Data("mp3".utf8).write(to: album.appendingPathComponent("21.mp3"))
        let entry = DownloadedAlbum(
            titleId: 2, name: "An Album", artistName: "An Artist", artistIds: [5],
            downloadedAt: Date(), lastAccessedAt: Date(), sizeBytes: 3, trackIds: [21])
        try JSONEncoder().encode([entry]).write(to: audio.appendingPathComponent("index.json"))

        let store = DownloadStore(folder: legacy)
        var video = MMDownloadEntry()
        video.transcodeID = 31
        video.titleID = 3
        video.titleName = "A Film"
        video.state = .completed
        let added = await store.addEntry(video)
        try Data("video".utf8).write(to: await store.videoPath(for: added))
    }

    /// The images each kind of download pins.
    static let pinnedRefs: [MMImageRef] = [
        .posterThumbnail(titleId: 1), .posterFull(titleId: 1),   // book
        .posterThumbnail(titleId: 2), .posterFull(titleId: 2),   // album
        .posterThumbnail(titleId: 3), .posterFull(titleId: 3),   // video
        .backdrop(titleId: 3),
    ]

    func pinned() async -> [Bool] {
        var result: [Bool] = []
        for ref in Self.pinnedRefs { result.append(await images.isPinned(ref)) }
        return result
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: base)
    }
}

/// Polls `condition` until it holds or `timeout` passes.
@MainActor
private func eventually(_ timeout: Duration = .seconds(5),
                        _ condition: () async -> Bool) async -> Bool {
    let clock = ContinuousClock()
    let deadline = clock.now + timeout
    while clock.now < deadline {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return await condition()
}

@MainActor
@Suite("Downloaded titles' images stay pinned")
struct DownloadArtPinTests {

    private let allPinned = Array(repeating: true, count: Fixture.pinnedRefs.count)
    private let nonePinned = Array(repeating: false, count: Fixture.pinnedRefs.count)

    @Test func downloadsAdoptedAtSignInArePinned() async throws {
        let first = Fixture(); defer { first.cleanUp() }
        try await first.writeLegacyDownloads()
        let f = first.reopen()
        await f.send(.recordLegacyOwner(serverFingerprint: "server-a"),
                     .serverConnected(serverFingerprint: "server-a"),
                     .accountSignedIn(accountId: 7))

        #expect(f.books.downloads.map(\.titleId) == [1])
        #expect(f.audio.downloads.map(\.titleId) == [2])
        #expect(f.videos.entries.map(\.titleID) == [3])
        #expect(await f.pinned() == allPinned)
    }

    @Test func signingOutAndBackInPinsThemAgain() async throws {
        let first = Fixture(); defer { first.cleanUp() }
        try await first.writeLegacyDownloads()
        let f = first.reopen()
        await f.send(.recordLegacyOwner(serverFingerprint: "server-a"),
                     .serverConnected(serverFingerprint: "server-a"),
                     .accountSignedIn(accountId: 7))

        await f.send(.suspend(.signedOut))
        #expect(await f.pinned() == nonePinned)

        await f.send(.accountSignedIn(accountId: 7))
        #expect(await f.pinned() == allPinned)
    }

    @Test func aRelaunchWithTheScopeBoundPinsThemAgain() async throws {
        let first = Fixture(); defer { first.cleanUp() }
        try await first.writeLegacyDownloads()
        let f = first.reopen()
        await f.send(.recordLegacyOwner(serverFingerprint: "server-a"),
                     .serverConnected(serverFingerprint: "server-a"),
                     .accountSignedIn(accountId: 7))

        // A new process: stores, image cache and scope start empty and
        // find the scope already bound on disk.
        let relaunched = f.reopen()
        await relaunched.videos.loaded()
        let all = allPinned
        let pinnedAgain = await eventually { await relaunched.pinned() == all }
        #expect(pinnedAgain)
    }
}
