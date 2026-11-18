import Foundation
import Testing
@testable import MediaManagerCore

/// `BookCacheManager` and `AudioCacheManager` over a `StorageScope` on
/// fresh temp folders, with their pre-scope folders under `Legacy/`.
@MainActor
private struct Stores {
    let base: URL
    let scope: StorageScope
    let books: BookCacheManager
    let audio: AudioCacheManager

    var legacyBooks: URL { Self.legacyBooks(base) }
    var legacyAudio: URL { Self.legacyAudio(base) }

    static func legacyBooks(_ base: URL) -> URL {
        base.appendingPathComponent("Legacy/Downloads/Books", isDirectory: true)
    }

    static func legacyAudio(_ base: URL) -> URL {
        base.appendingPathComponent("Legacy/Downloads/Audio", isDirectory: true)
    }

    init(base: URL? = nil) {
        self.base = base ?? FileManager.default.temporaryDirectory
            .appendingPathComponent("BookAudioScopeTests-\(UUID().uuidString)")
        scope = StorageScope(durableBase: self.base.appendingPathComponent("support"),
                             cacheBase: self.base.appendingPathComponent("caches"))
        books = BookCacheManager(scope: scope, legacyFolder: Self.legacyBooks(self.base))
        audio = AudioCacheManager(scope: scope, legacyFolder: Self.legacyAudio(self.base))
    }

    /// New stores and scope over the same folders, as a relaunch would.
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

    func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    /// Writes a downloaded book the way the pre-scope build did.
    func writeLegacyBook(mediaItemId: Int64, titleId: Int64) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: legacyBooks, withIntermediateDirectories: true)
        let book = DownloadedBook(
            mediaItemId: mediaItemId, titleId: titleId, titleName: "A Book",
            authorName: "An Author", sizeBytes: 3, downloadedAt: Date(),
            lastAccessedAt: Date(), completedFraction: 0.25)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode([book]).write(to: legacyBooks.appendingPathComponent("index.json"))
        try Data("epub".utf8).write(to: legacyBooks.appendingPathComponent("\(mediaItemId).epub"))
    }

    /// Writes a downloaded album and playlist the way the pre-scope
    /// build did.
    func writeLegacyAudio(titleId: Int64, trackId: Int64, playlistId: Int64, playlistTrackId: Int64) throws {
        let fm = FileManager.default
        let album = legacyAudio.appendingPathComponent("\(titleId)", isDirectory: true)
        let playlist = legacyAudio.appendingPathComponent("playlist-\(playlistId)", isDirectory: true)
        try fm.createDirectory(at: album, withIntermediateDirectories: true)
        try fm.createDirectory(at: playlist, withIntermediateDirectories: true)
        try Data("mp3".utf8).write(to: album.appendingPathComponent("\(trackId).mp3"))
        try Data("m4a".utf8).write(to: playlist.appendingPathComponent("\(playlistTrackId).m4a"))
        let entry = DownloadedAlbum(
            titleId: titleId, name: "An Album", artistName: "An Artist", artistIds: [5],
            downloadedAt: Date(), lastAccessedAt: Date(), sizeBytes: 3, trackIds: [trackId])
        try JSONEncoder().encode([entry]).write(to: legacyAudio.appendingPathComponent("index.json"))
        let list = DownloadedPlaylist(
            playlistId: playlistId, name: "A Playlist", downloadedAt: Date(),
            lastAccessedAt: Date(), sizeBytes: 3, trackIds: [playlistTrackId])
        try JSONEncoder().encode([list]).write(to: legacyAudio.appendingPathComponent("playlists.json"))
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: base)
    }
}

/// Counts stop requests from the audio store.
@MainActor
private final class FakePlayer {
    var currentTrackId: Int64?
    var stops = 0

    func attach(to audio: AudioCacheManager) {
        audio.configurePlayback(
            currentTrackId: { [weak self] in self?.currentTrackId },
            stop: { [weak self] in
                self?.stops += 1
                self?.currentTrackId = nil
            })
    }
}

@MainActor
@Suite("Book and audio downloads per storage scope")
struct BookAudioScopeTests {

    // MARK: - Unbound

    @Test func withNoScopeNothingIsDownloadedAndNothingStarts() async throws {
        let s = Stores(); defer { s.cleanUp() }
        try s.writeLegacyBook(mediaItemId: 11, titleId: 1)
        try s.writeLegacyAudio(titleId: 2, trackId: 21, playlistId: 3, playlistTrackId: 31)
        let s2 = s.reopen()

        #expect(s2.books.downloads.isEmpty)
        #expect(s2.books.localBookURL(11) == nil)
        #expect(s2.audio.downloads.isEmpty)
        #expect(s2.audio.playlistDownloads.isEmpty)
        #expect(s2.audio.localTrackURL(trackId: 21) == nil)
        #expect(throws: BookCacheError.self) {
            try s2.books.startDownload(mediaItemId: 12, titleId: 1, titleName: "B", authorName: "C")
        }
        #expect(s2.books.activeDownloads.isEmpty)
        // Unbound, the old content's owner is not known yet: left alone.
        #expect(s.exists(s.legacyBooks) && s.exists(s.legacyAudio))
    }

    // MARK: - Content from before scopes

    @Test func oldDownloadsAreAdoptedOnTheFirstBindOnTheirServer() async throws {
        let s = Stores(); defer { s.cleanUp() }
        try s.writeLegacyBook(mediaItemId: 11, titleId: 1)
        try s.writeLegacyAudio(titleId: 2, trackId: 21, playlistId: 3, playlistTrackId: 31)
        let s2 = s.reopen()
        s2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await s2.signIn(server: "server-a", account: 7)

        #expect(s2.books.downloads.map(\.mediaItemId) == [11])
        #expect(s2.books.downloads.first?.completedFraction == 0.25)
        let book = try #require(s2.books.localBookURL(11))
        #expect(book.path.contains("/Scoped/servers/"))
        #expect(s2.audio.downloads.map(\.titleId) == [2])
        #expect(s2.audio.offlineArtistIds == [5])
        #expect(s2.audio.playlistDownloads.map(\.playlistId) == [3])
        let track = try #require(s2.audio.localTrackURL(trackId: 21))
        #expect(track.path.contains("/Scoped/servers/"))
        #expect(s2.audio.localTrackURL(trackId: 31) != nil)

        // The old folders are gone; their parent is not ours to touch.
        #expect(!s.exists(s.legacyBooks) && !s.exists(s.legacyAudio))
        #expect(s.exists(s.legacyBooks.deletingLastPathComponent()))

        // And they stay after a relaunch.
        let s3 = s.reopen()
        #expect(s3.books.localBookURL(11) != nil)
        #expect(s3.audio.localTrackURL(trackId: 21) != nil)
    }

    @Test func oldDownloadsAreDeletedOnADifferentServer() async throws {
        let s = Stores(); defer { s.cleanUp() }
        try s.writeLegacyBook(mediaItemId: 11, titleId: 1)
        try s.writeLegacyAudio(titleId: 2, trackId: 21, playlistId: 3, playlistTrackId: 31)
        let s2 = s.reopen()
        s2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await s2.signIn(server: "server-b", account: 7)

        #expect(s2.books.downloads.isEmpty)
        #expect(s2.audio.downloads.isEmpty)
        #expect(s2.audio.playlistDownloads.isEmpty)
        #expect(!s.exists(s.legacyBooks) && !s.exists(s.legacyAudio))
    }

    @Test func oldDownloadsGoToTheFirstAccountOnly() async throws {
        let s = Stores(); defer { s.cleanUp() }
        s.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await s.signIn(server: "server-a", account: 7)
        await s.signOut()
        // Old content turning up after the first account bound (the
        // store claims on every bind) belongs to no one.
        try s.writeLegacyBook(mediaItemId: 11, titleId: 1)
        await s.signIn(server: "server-a", account: 8)
        #expect(s.books.downloads.isEmpty)
        #expect(!s.exists(s.legacyBooks))
    }

    // MARK: - Scope changes

    @Test func indicesAreEmptyAfterAChangeToAnEmptyScope() async throws {
        let s = Stores(); defer { s.cleanUp() }
        try s.writeLegacyBook(mediaItemId: 11, titleId: 1)
        try s.writeLegacyAudio(titleId: 2, trackId: 21, playlistId: 3, playlistTrackId: 31)
        let s2 = s.reopen()
        s2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await s2.signIn(server: "server-a", account: 7)
        #expect(!s2.books.downloads.isEmpty && !s2.audio.downloads.isEmpty)

        await s2.signOut()
        #expect(s2.books.downloads.isEmpty)
        #expect(s2.books.localBookURL(11) == nil)
        #expect(s2.audio.downloads.isEmpty)
        #expect(s2.audio.playlistDownloads.isEmpty)
        #expect(s2.audio.localTrackURL(trackId: 21) == nil)

        // Another account on the same server has nothing.
        await s2.signIn(server: "server-a", account: 8)
        #expect(s2.books.downloads.isEmpty)
        #expect(s2.audio.downloads.isEmpty)
        #expect(s2.audio.localTrackURL(trackId: 21) == nil)
    }

    @Test func signingBackInToTheSameAccountFindsTheDownloads() async throws {
        let s = Stores(); defer { s.cleanUp() }
        try s.writeLegacyBook(mediaItemId: 11, titleId: 1)
        try s.writeLegacyAudio(titleId: 2, trackId: 21, playlistId: 3, playlistTrackId: 31)
        let s2 = s.reopen()
        s2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await s2.signIn(server: "server-a", account: 7)
        await s2.signOut()
        await s2.signIn(server: "server-a", account: 7)
        #expect(s2.books.localBookURL(11) != nil)
        #expect(s2.audio.localTrackURL(trackId: 21) != nil)
        #expect(s2.audio.localTrackURL(trackId: 31) != nil)
    }

    @Test func aScopeBoundAtLaunchIsLoaded() async throws {
        let s = Stores(); defer { s.cleanUp() }
        try s.writeLegacyBook(mediaItemId: 11, titleId: 1)
        // Bind on one launch, then create the stores on the next with
        // the scope already restored from disk.
        let first = StorageScope(durableBase: s.base.appendingPathComponent("support"),
                                 cacheBase: s.base.appendingPathComponent("caches"))
        first.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        first.send(.serverConnected(serverFingerprint: "server-a"))
        first.send(.accountSignedIn(accountId: 7))
        await first.settled()

        let s2 = s.reopen()
        #expect(s2.scope.current?.accountId == 7)
        #expect(s2.books.downloads.map(\.mediaItemId) == [11])
        #expect(!s.exists(s.legacyBooks))
    }

    // MARK: - Playback

    @Test func aScopeChangeStopsADownloadedTrackThatIsPlaying() async throws {
        let s = Stores(); defer { s.cleanUp() }
        try s.writeLegacyAudio(titleId: 2, trackId: 21, playlistId: 3, playlistTrackId: 31)
        let s2 = s.reopen()
        let player = FakePlayer()
        player.attach(to: s2.audio)
        s2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await s2.signIn(server: "server-a", account: 7)

        player.currentTrackId = 21
        await s2.signOut()
        #expect(player.stops == 1)
    }

    @Test func aScopeChangeLeavesAStreamedTrackPlaying() async throws {
        let s = Stores(); defer { s.cleanUp() }
        let player = FakePlayer()
        player.attach(to: s.audio)
        await s.signIn(server: "server-a", account: 7)

        player.currentTrackId = 99  // not downloaded
        await s.signOut()
        #expect(player.stops == 0)
    }

    // MARK: - Account deletion

    @Test func removingAllDownloadsEmptiesTheScopesFolders() async throws {
        let s = Stores(); defer { s.cleanUp() }
        try s.writeLegacyBook(mediaItemId: 11, titleId: 1)
        try s.writeLegacyAudio(titleId: 2, trackId: 21, playlistId: 3, playlistTrackId: 31)
        let s2 = s.reopen()
        s2.scope.send(.recordLegacyOwner(serverFingerprint: "server-a"))
        await s2.signIn(server: "server-a", account: 7)
        let book = try #require(s2.books.localBookURL(11))
        let track = try #require(s2.audio.localTrackURL(trackId: 21))

        s2.books.removeAllDownloads()
        s2.audio.removeAllDownloads()
        #expect(s2.books.downloads.isEmpty)
        #expect(s2.audio.downloads.isEmpty && s2.audio.playlistDownloads.isEmpty)
        #expect(!s.exists(book) && !s.exists(track))

        // As completeAccountDeletion goes on to do.
        s2.scope.send(.forgetCurrentAccount)
        await s2.scope.settled()
        #expect(s2.books.downloads.isEmpty && s2.audio.downloads.isEmpty)
    }

    // MARK: - Work in progress

    @Test func writersAreCancelledAndAwaited() async {
        let s = Stores(); defer { s.cleanUp() }
        let area = ScopedDownloadArea(scope: s.scope, area: .books, legacyFolder: nil)
        var finished = false
        area.run {
            // Runs until cancelled, then cleans up.
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(5))
            }
            finished = true
        }
        await area.cancelAndAwaitWriters()
        #expect(finished)
    }
}
