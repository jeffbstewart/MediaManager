import Foundation
import Testing
import UIKit
@testable import MediaManagerCore
import MediaManagerProtos

/// An image cache over a `StorageScope` on fresh temp folders, with its
/// own URL cache and defaults, and an old unscoped cache folder.
private struct Fixture {
    let base: URL
    let scope: StorageScope
    let cache: ImageDiskCache
    let urlCache: URLCache
    let defaultsSuite: String
    var legacy: URL { base.appendingPathComponent("caches/images") }
    var scopedCaches: URL { base.appendingPathComponent("caches/Scoped") }

    init(legacyContent: Bool = false) throws {
        base = FileManager.default.temporaryDirectory
            .appendingPathComponent("ImageDiskCacheTests-\(UUID().uuidString)")
        let legacy = base.appendingPathComponent("caches/images")
        if legacyContent {
            try FileManager.default.createDirectory(at: legacy, withIntermediateDirectories: true)
            try Data("old".utf8).write(to: legacy.appendingPathComponent("manifest.json"))
        }
        scope = StorageScope(durableBase: base.appendingPathComponent("support"),
                             cacheBase: base.appendingPathComponent("caches"))
        urlCache = URLCache(memoryCapacity: 1 << 20, diskCapacity: 0)
        defaultsSuite = "ImageDiskCacheTests-\(UUID().uuidString)"
        cache = ImageDiskCache(scope: scope, legacyDirectory: legacy,
                               urlCache: urlCache, defaultsSuite: defaultsSuite)
    }

    func signIn(server: String, account: Int64) async {
        scope.send(.serverConnected(serverFingerprint: server))
        scope.send(.accountSignedIn(accountId: account))
        await scope.settled()
    }

    func send(_ event: StorageScopeEvent) async {
        scope.send(event)
        await scope.settled()
    }

    /// The files the cache holds for the bound scope, manifest aside.
    func imageFiles() throws -> [URL] {
        let folder = try #require(scope.directory(for: .images))
        return try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
            .filter { $0.lastPathComponent != "manifest.json" }
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: base)
        UserDefaults.standard.removePersistentDomain(forName: defaultsSuite)
    }
}

/// A 1x1 PNG of `color`, so two stored images can be told apart.
private func png(_ color: UIColor) -> Data {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    return UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1), format: format)
        .pngData { context in
            color.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        }
}

private let poster = MMImageRef.posterThumbnail(titleId: 42)

@Suite("Image disk cache per server and account")
struct ImageDiskCacheTests {

    @Test func theSameRefUnderTwoScopesStaysSeparate() async throws {
        let f = try Fixture(); defer { f.cleanUp() }

        await f.signIn(server: "server-a", account: 1)
        await f.cache.store(ref: poster, data: png(.red), contentType: "image/png", etag: "a")
        #expect(await f.cache.etag(for: poster) == "a")

        // Another server's title 42 is a different title.
        await f.signIn(server: "server-b", account: 1)
        #expect(await f.cache.get(for: poster) == nil)
        await f.cache.store(ref: poster, data: png(.blue), contentType: "image/png", etag: "b")
        #expect(await f.cache.etag(for: poster) == "b")

        // Another account on the same server has its own cache too.
        await f.signIn(server: "server-b", account: 2)
        #expect(await f.cache.get(for: poster) == nil)
        await f.cache.store(ref: poster, data: png(.green), contentType: "image/png", etag: "c")

        // Signing out and back in to the same account keeps its cache.
        await f.send(.suspend(.signedOut))
        await f.signIn(server: "server-b", account: 2)
        #expect(await f.cache.etag(for: poster) == "c")
        #expect(await f.cache.get(for: poster) != nil)
    }

    @Test func aScopeChangeEmptiesTheMemoryLayer() async throws {
        let f = try Fixture(); defer { f.cleanUp() }
        await f.signIn(server: "server-a", account: 1)
        await f.cache.store(ref: poster, data: png(.red), contentType: "image/png", etag: "a")

        // With the file gone from disk, only the memory layer can serve it.
        for file in try f.imageFiles() { try FileManager.default.removeItem(at: file) }
        #expect(await f.cache.get(for: poster) != nil)

        // Out and back in to the same scope: the manifest is saved and
        // reloaded, but memory is empty, so the missing file is a miss.
        await f.send(.suspend(.serverChanging))
        await f.send(.resumeLastAccount)
        #expect(f.scope.current != nil)
        #expect(await f.cache.get(for: poster) == nil)
    }

    @Test func nothingIsServedOrWrittenWhileUnbound() async throws {
        let f = try Fixture(); defer { f.cleanUp() }
        await f.signIn(server: "server-a", account: 1)
        await f.cache.store(ref: poster, data: png(.red), contentType: "image/png", etag: "a")

        await f.send(.suspend(.signedOut))
        #expect(await f.cache.get(for: poster) == nil)
        #expect(await f.cache.etag(for: poster) == nil)
        let other = MMImageRef.posterThumbnail(titleId: 7)
        await f.cache.store(ref: other, data: png(.blue), contentType: "image/png", etag: "x")

        await f.signIn(server: "server-a", account: 1)
        #expect(await f.cache.get(for: other) == nil)
        #expect(await f.cache.etag(for: poster) == "a")
    }

    @Test func aFetchFromBeforeAScopeChangeIsNotStored() async throws {
        let f = try Fixture(); defer { f.cleanUp() }
        await f.signIn(server: "server-a", account: 1)
        let epoch = await f.cache.scopeEpoch

        await f.signIn(server: "server-b", account: 1)
        await f.cache.store(ref: poster, data: png(.red), contentType: "image/png",
                            etag: "a", scopeEpoch: epoch)
        #expect(await f.cache.get(for: poster) == nil)

        let current = await f.cache.scopeEpoch
        await f.cache.store(ref: poster, data: png(.blue), contentType: "image/png",
                            etag: "b", scopeEpoch: current)
        #expect(await f.cache.etag(for: poster) == "b")
    }

    @Test func theOldUnscopedCacheIsDeleted() async throws {
        let f = try Fixture(legacyContent: true); defer { f.cleanUp() }
        #expect(FileManager.default.fileExists(atPath: f.legacy.path))
        // On the first use, even before any scope is bound.
        #expect(await f.cache.get(for: poster) == nil)
        #expect(!FileManager.default.fileExists(atPath: f.legacy.path))
    }

    @Test func aScopeBoundBeforeTheFirstUseIsLoaded() async throws {
        let f = try Fixture(); defer { f.cleanUp() }
        await f.signIn(server: "server-a", account: 1)
        await f.cache.store(ref: poster, data: png(.red), contentType: "image/png", etag: "a")
        await f.send(.suspend(.serverChanging))   // saves the manifest

        // A relaunch: a new scope over the same folders, already bound.
        let relaunched = StorageScope(durableBase: f.base.appendingPathComponent("support"),
                                      cacheBase: f.base.appendingPathComponent("caches"))
        relaunched.send(.resumeLastAccount)
        await relaunched.settled()
        let cache = ImageDiskCache(scope: relaunched, legacyDirectory: nil,
                                   urlCache: f.urlCache, defaultsSuite: f.defaultsSuite)
        #expect(await cache.etag(for: poster) == "a")
        #expect(await cache.get(for: poster) != nil)
    }

    @Test func pinsDoNotCarryIntoAnotherScope() async throws {
        let f = try Fixture(); defer { f.cleanUp() }
        await f.signIn(server: "server-a", account: 1)
        await f.cache.pin(ref: poster)
        #expect(await f.cache.isPinned(poster))

        await f.signIn(server: "server-b", account: 1)
        #expect(await !f.cache.isPinned(poster))
    }

    @Test func clearDeletesTheCurrentScopesImages() async throws {
        let f = try Fixture(); defer { f.cleanUp() }
        await f.signIn(server: "server-a", account: 1)
        await f.cache.store(ref: poster, data: png(.red), contentType: "image/png", etag: "a")
        await f.cache.clear()
        #expect(await f.cache.get(for: poster) == nil)
        #expect(try f.imageFiles().isEmpty)
    }

    @Test func httpResponsesAreClearedWhenTheServerChanges() async throws {
        let f = try Fixture(); defer { f.cleanUp() }
        let url = URL(string: "https://example.invalid/poster")!
        func cacheAResponse() {
            let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!
            f.urlCache.storeCachedResponse(CachedURLResponse(response: response, data: Data("x".utf8)),
                                           for: URLRequest(url: url))
        }
        func cached() -> Bool { f.urlCache.cachedResponse(for: URLRequest(url: url)) != nil }

        await f.signIn(server: "server-a", account: 1)
        cacheAResponse()

        // Same server, another account: kept.
        await f.signIn(server: "server-a", account: 2)
        #expect(cached())

        await f.signIn(server: "server-b", account: 1)
        #expect(!cached())
    }
}
