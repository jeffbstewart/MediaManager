import Foundation
import Testing
@testable import MediaManagerCore

/// A UserDefaults suite of its own, holding every cached server answer
/// and one device preference.
private struct Fixture {
    let suiteName = "ServerSettingsCacheTests-\(UUID().uuidString)"
    let defaults: UserDefaults

    init() {
        defaults = UserDefaults(suiteName: suiteName)!
    }

    var cache: ServerSettingsCache { ServerSettingsCache(defaults: defaults) }

    func fill() {
        defaults.set(Data("docs".utf8), forKey: ServerSettingsCache.legalDocsKey)
        defaults.set(["downloads"], forKey: ServerSettingsCache.capabilitiesKey)
        defaults.set(Data("features".utf8), forKey: ServerFeatures.defaultsKey)
        defaults.set(true, forKey: "offlineMode")
    }

    var cachedKeys: [String] {
        ServerSettingsCache.serverKeys.filter { defaults.object(forKey: $0) != nil }
    }

    func cleanUp() {
        defaults.removePersistentDomain(forName: suiteName)
    }
}

@Suite("Server settings cache")
struct ServerSettingsCacheTests {

    @Test func clearsEveryServerAnswer() {
        #expect(Set(ServerSettingsCache.serverKeys)
                == ["cachedLegalDocs", "cachedCapabilities", "mm.serverFeatures"])
    }

    @Test func aDifferentServerClearsTheThreeKeys() {
        let f = Fixture(); defer { f.cleanUp() }
        f.cache.serverConnected("server-a", source: .discovered)
        f.fill()

        #expect(f.cache.serverConnected("server-b", source: .discovered))
        #expect(f.cachedKeys.isEmpty)
        // A device preference stays.
        #expect(f.defaults.bool(forKey: "offlineMode"))
    }

    @Test func theSameServerAgainKeepsThem() {
        let f = Fixture(); defer { f.cleanUp() }
        f.cache.serverConnected("server-a", source: .discovered)
        f.fill()

        #expect(!f.cache.serverConnected("server-a", source: .discovered))
        #expect(!f.cache.serverConnected("server-a", source: .pinned))
        #expect(f.cachedKeys.count == 3)
    }

    @Test func aDifferentPinnedServerAlsoClears() {
        let f = Fixture(); defer { f.cleanUp() }
        f.cache.serverConnected("server-a", source: .discovered)
        f.fill()

        #expect(f.cache.serverConnected("server-b", source: .pinned))
        #expect(f.cachedKeys.isEmpty)
    }

    @Test func answersWrittenAfterTheChangeAreKept() {
        let f = Fixture(); defer { f.cleanUp() }
        f.cache.serverConnected("server-a", source: .discovered)
        f.fill()
        f.cache.serverConnected("server-b", source: .discovered)
        // What connectToServer does next: the new server's legal docs.
        f.defaults.set(Data("new docs".utf8), forKey: ServerSettingsCache.legalDocsKey)

        #expect(!f.cache.serverConnected("server-b", source: .pinned))
        #expect(f.defaults.data(forKey: ServerSettingsCache.legalDocsKey) == Data("new docs".utf8))
    }

    @Test func theFirstLaunchAdoptsThePinnedServersAnswers() {
        let f = Fixture(); defer { f.cleanUp() }
        f.fill()

        #expect(!f.cache.serverConnected("server-a", source: .pinned))
        #expect(f.cachedKeys.count == 3)
        // And from then on they belong to that server.
        #expect(f.cache.serverConnected("server-b", source: .discovered))
        #expect(f.cachedKeys.isEmpty)
    }

    @Test func answersOfUnknownOriginAreClearedOnDiscovery() {
        let f = Fixture(); defer { f.cleanUp() }
        f.fill()

        #expect(f.cache.serverConnected("server-a", source: .discovered))
        #expect(f.cachedKeys.isEmpty)
    }

    @Test func theFingerprintItselfIsNotStored() {
        let f = Fixture(); defer { f.cleanUp() }
        f.cache.serverConnected("secret-fingerprint", source: .discovered)
        let owner = f.defaults.string(forKey: ServerSettingsCache.ownerKey)
        #expect(owner == StorageScope.serverFolderName("secret-fingerprint"))
    }
}
