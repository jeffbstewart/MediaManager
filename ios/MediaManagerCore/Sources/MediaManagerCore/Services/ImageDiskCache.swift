import Foundation
import UIKit
import CryptoKit
import os.log
import MediaManagerProtos

private let logger = MMLogger(category: "ImageDiskCache")

/// Disk-backed LRU image cache with in-memory front layer.
/// Stores up to `maxCount` images on disk in the `images` area of the
/// current storage scope (see `StorageScope`), so each server and
/// account has its own cache: image refs carry the server's own ids,
/// and the same ref on two servers is two different images.
/// Each entry tracks an etag for conditional gRPC revalidation.
///
/// While no scope is bound (signed out, changing server) nothing is
/// served from disk and nothing is written. A scope change drops the
/// memory layer, the manifest and the pins, and loads the new scope's
/// manifest. The cache from before scopes (`Library/Caches/images`)
/// may hold another server's posters and is always deleted.
public actor ImageDiskCache: StorageScopeParticipant {
    public static let shared = ImageDiskCache(
        scope: .shared,
        legacyDirectory: FileManager.default
            .urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("images", isDirectory: true),
        urlCache: .shared,
        defaultsSuite: nil)

    private let maxCount = 500
    private let scope: StorageScope
    /// The unscoped cache from before scopes existed; deleted on sight.
    private let legacyDirectory: URL?
    /// HTTP responses, cleared when the bound server changes.
    private let urlCache: URLCache
    /// The `UserDefaults` suite (nil: standard) that remembers the last
    /// server bound, across launches, for `urlCache`.
    private let defaultsSuite: String?
    private var defaults: UserDefaults {
        defaultsSuite.flatMap { UserDefaults(suiteName: $0) } ?? .standard
    }
    private let memoryCache = NSCache<NSString, UIImage>()

    private enum Binding {
        /// `StorageScope.current` has not been read yet (launch).
        case notYetRead
        /// No scope bound: serve nothing, write nothing.
        case unbound
        /// The current scope's `images` folder.
        case bound(URL)
    }
    private var binding: Binding = .notYetRead

    /// Goes up on every scope change. A caller that fetches from the
    /// server reads it first and passes it to `store`, so an image
    /// fetched for one scope is never written into the next.
    public private(set) var scopeEpoch: UInt64 = 0

    /// In-memory manifest: cache_key → (etag, contentType, fileURL)
    private var manifest: [String: CacheEntry] = [:]
    /// LRU bookkeeping. Each touch bumps `accessClock` and stamps the
    /// key with the new value, so `touchAccess` is O(1) instead of
    /// the O(n) `Array.removeAll(where:)` scan we used before.
    /// Eviction sorts by this map only when the cache is over the
    /// `maxCount` cap — runs at most once per save burst.
    private var lastAccess: [String: UInt64] = [:]
    private var accessClock: UInt64 = 0
    /// Never evicted. Belong to the scope bound when they were made;
    /// pins made while unbound carry into the next scope bound.
    private var pinnedKeys: Set<String> = []

    struct CacheEntry {
        let etag: String
        let contentType: String
        let fileURL: URL
    }

    // MARK: - Manifest debounce
    //
    // Every `store()` used to call `saveManifest()` synchronously,
    // which JSON-encoded all 500 entries and wrote them to disk on
    // the actor. A burst of cache misses (e.g. the For You carousel
    // populating 30 cards at once) serialized 30 disk writes
    // through the actor and blocked every other cache op behind
    // them — visibly tens of seconds in the simulator.
    //
    // We coalesce now: each mutation flips `manifestDirty` and
    // schedules a single flush after a 500 ms quiet period. If
    // another mutation lands while a flush is pending, the flag
    // stays dirty and the same scheduled task picks it up. If
    // mutations land while a flush is *running*, the flag is
    // re-set and a new flush is scheduled by the *next* mutation.
    private var manifestDirty: Bool = false
    private var manifestFlushScheduled: Bool = false

    static let lastServerKey = "imageDiskCache.lastServer"

    /// `scope` supplies the folder; tests pass one over temp folders.
    init(scope: StorageScope, legacyDirectory: URL?, urlCache: URLCache, defaultsSuite: String?) {
        self.scope = scope
        self.legacyDirectory = legacyDirectory
        self.urlCache = urlCache
        self.defaultsSuite = defaultsSuite
        memoryCache.countLimit = 200
        // Register before the first read of `scope.current`
        // (`bindIfNotYetRead`), so no change is missed.
        scope.register(self)
    }

    // MARK: - Scope

    public func storageScopeWillChange() {
        // Save what this scope has, while its folder still exists:
        // signing back in to the same account finds it again.
        if manifestDirty {
            manifestDirty = false
            writeManifestNow()
        }
        scopeEpoch &+= 1
        binding = .unbound
        dropScopeContent()
        pinnedKeys.removeAll()
    }

    public func storageScopeDidChange(to newScope: StorageScopeID?) {
        bind(to: newScope)
    }

    /// On launch the scope may already be bound, restored from disk.
    private func bindIfNotYetRead() {
        guard case .notYetRead = binding else { return }
        bind(to: scope.current)
    }

    private func bind(to newScope: StorageScopeID?) {
        scopeEpoch &+= 1
        dropScopeContent()
        if let legacyDirectory {
            scope.discardLegacy(at: legacyDirectory)
        }
        guard let newScope, let folder = scope.directory(for: .images) else {
            binding = .unbound
            return
        }
        binding = .bound(folder)
        noteServer(newScope.serverFingerprint)
        loadManifest(from: folder)
    }

    /// The folder to read and write, or nil when unbound.
    private var cacheDir: URL? {
        bindIfNotYetRead()
        if case .bound(let folder) = binding { return folder }
        return nil
    }

    private func dropScopeContent() {
        memoryCache.removeAllObjects()
        manifest.removeAll()
        lastAccess.removeAll()
        manifestDirty = false
    }

    /// Clear the HTTP response cache when the bound server differs from
    /// the last one, across launches too: it holds the old server's
    /// responses.
    private func noteServer(_ fingerprint: String) {
        let folder = StorageScope.serverFolderName(fingerprint)
        guard defaults.string(forKey: Self.lastServerKey) != folder else { return }
        urlCache.removeAllCachedResponses()
        defaults.set(folder, forKey: Self.lastServerKey)
        logger.info("Server changed; cleared cached HTTP responses")
    }

    // MARK: - Public API

    /// Returns cached image + etag if available. Nil if cache miss.
    public func get(for ref: MMImageRef) -> (UIImage, String)? {
        guard cacheDir != nil else { return nil }
        let key = cacheKey(for: ref)

        // Memory cache
        if let image = memoryCache.object(forKey: key as NSString),
           let entry = manifest[key] {
            touchAccess(key)
            return (image, entry.etag)
        }

        // Disk cache
        guard let entry = manifest[key] else { return nil }
        guard let data = try? Data(contentsOf: entry.fileURL),
              let image = UIImage(data: data) else {
            // Corrupt or missing file — remove from manifest
            manifest.removeValue(forKey: key)
            lastAccess.removeValue(forKey: key)
            return nil
        }

        memoryCache.setObject(image, forKey: key as NSString)
        touchAccess(key)
        return (image, entry.etag)
    }

    /// Returns just the etag for conditional requests, without loading the image.
    public func etag(for ref: MMImageRef) -> String? {
        guard cacheDir != nil else { return nil }
        return manifest[cacheKey(for: ref)]?.etag
    }

    /// Store image data on disk and in memory. Pass the `scopeEpoch`
    /// read before fetching: if the scope has changed since, nothing is
    /// stored. Nothing is stored while unbound.
    public func store(ref: MMImageRef, data: Data, contentType: String, etag: String,
                      scopeEpoch fetchedIn: UInt64? = nil) {
        guard let cacheDir else { return }
        if let fetchedIn, fetchedIn != scopeEpoch { return }
        let key = cacheKey(for: ref)
        let fileURL = cacheDir.appendingPathComponent(key)

        do {
            try data.write(to: fileURL)
        } catch {
            logger.error("Failed to write cache file: \(error.localizedDescription)")
            return
        }

        manifest[key] = CacheEntry(etag: etag, contentType: contentType, fileURL: fileURL)
        touchAccess(key)

        if let image = UIImage(data: data) {
            memoryCache.setObject(image, forKey: key as NSString)
        }

        evictIfNeeded()
        scheduleManifestSave()
    }

    /// Remove a specific entry. With `scopeEpoch`, only if the scope
    /// has not changed since it was read.
    public func remove(for ref: MMImageRef, scopeEpoch fetchedIn: UInt64? = nil) {
        guard cacheDir != nil else { return }
        if let fetchedIn, fetchedIn != scopeEpoch { return }
        let key = cacheKey(for: ref)
        if let entry = manifest.removeValue(forKey: key) {
            try? FileManager.default.removeItem(at: entry.fileURL)
        }
        lastAccess.removeValue(forKey: key)
        memoryCache.removeObject(forKey: key as NSString)
        scheduleManifestSave()
    }

    // MARK: - Pinning (prevents eviction for downloaded content)

    /// Pin an image ref so it won't be evicted from cache.
    public func pin(ref: MMImageRef) {
        pinnedKeys.insert(cacheKey(for: ref))
    }

    /// Unpin an image ref, allowing normal LRU eviction.
    public func unpin(ref: MMImageRef) {
        pinnedKeys.remove(cacheKey(for: ref))
    }

    func isPinned(_ ref: MMImageRef) -> Bool {
        pinnedKeys.contains(cacheKey(for: ref))
    }

    // MARK: - Cache Key

    private func cacheKey(for ref: MMImageRef) -> String {
        // Deterministic hash of type + every identifying field. Every new
        // ImageRef variant must hash its discriminating field here, or
        // entries in that variant will all collide on the same on-disk
        // slot — see Author headshots / TMDB posters / OpenLibrary covers
        // which previously shared a single key because their fields were
        // omitted.
        var hasher = SHA256()
        hasher.update(data: Data("\(ref.type.rawValue)".utf8))
        hasher.update(data: Data("\(ref.titleID)".utf8))
        hasher.update(data: Data("\(ref.tmdbPersonID)".utf8))
        hasher.update(data: Data("\(ref.tmdbCollectionID)".utf8))
        hasher.update(data: Data(ref.uuid.utf8))
        hasher.update(data: Data("\(ref.cameraID)".utf8))
        hasher.update(data: Data("\(ref.artistID)".utf8))
        hasher.update(data: Data("\(ref.authorID)".utf8))
        hasher.update(data: Data(ref.musicbrainzReleaseGroupID.utf8))
        hasher.update(data: Data(ref.openlibraryWorkID.utf8))
        if ref.hasTmdbMedia {
            hasher.update(data: Data("\(ref.tmdbMedia.tmdbID)/\(ref.tmdbMedia.mediaType.rawValue)".utf8))
        }
        let digest = hasher.finalize()
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - LRU

    /// O(1) — bump the monotonic clock and stamp the key. Eviction
    /// reads `lastAccess` later to pick the oldest.
    private func touchAccess(_ key: String) {
        accessClock &+= 1
        lastAccess[key] = accessClock
    }

    private func evictIfNeeded() {
        guard manifest.count > maxCount else { return }
        let overflow = manifest.count - maxCount
        // Sort once, evict in order. O(n log n) on the cache size,
        // and only triggers when we're actually over the cap.
        let candidates = lastAccess
            .filter { !pinnedKeys.contains($0.key) }
            .sorted { $0.value < $1.value }
        var evicted = 0
        for (key, _) in candidates where evicted < overflow {
            if let entry = manifest.removeValue(forKey: key) {
                try? FileManager.default.removeItem(at: entry.fileURL)
                memoryCache.removeObject(forKey: key as NSString)
            }
            lastAccess.removeValue(forKey: key)
            evicted += 1
        }
    }

    /// Delete every cached image of the current scope, pinned ones
    /// included, and the manifest. Used when the account is deleted.
    public func clear() {
        let folder = cacheDir
        dropScopeContent()
        pinnedKeys.removeAll()
        guard let folder else { return }
        let fm = FileManager.default
        for url in (try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? [] {
            try? fm.removeItem(at: url)
        }
    }

    // MARK: - Manifest Persistence

    private struct ManifestEntry: Codable {
        let key: String
        let etag: String
        let contentType: String
        let fileName: String
    }

    private static func manifestURL(in folder: URL) -> URL {
        folder.appendingPathComponent("manifest.json")
    }

    /// Mark the manifest dirty and ensure exactly one save task is
    /// pending. Multiple stores in the same quiet period collapse
    /// into a single disk write.
    private func scheduleManifestSave() {
        manifestDirty = true
        guard !manifestFlushScheduled else { return }
        manifestFlushScheduled = true
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            await self?.flushManifestIfDirty()
        }
    }

    private func flushManifestIfDirty() {
        manifestFlushScheduled = false
        guard manifestDirty else { return }
        manifestDirty = false
        writeManifestNow()
    }

    /// Writes the manifest into the bound scope's folder; nothing
    /// while unbound.
    private func writeManifestNow() {
        guard case .bound(let folder) = binding else { return }
        // Persist in access order (oldest first → newest last) so a
        // future load can reconstruct relative recency without
        // needing the raw clock value on disk.
        let ordered = lastAccess
            .sorted { $0.value < $1.value }
            .compactMap { (key, _) -> ManifestEntry? in
                guard let entry = manifest[key] else { return nil }
                return ManifestEntry(
                    key: key,
                    etag: entry.etag,
                    contentType: entry.contentType,
                    fileName: entry.fileURL.lastPathComponent)
            }
        if let data = try? JSONEncoder().encode(ordered) {
            try? data.write(to: Self.manifestURL(in: folder))
        }
    }

    private func loadManifest(from folder: URL) {
        guard let data = try? Data(contentsOf: Self.manifestURL(in: folder)),
              let entries = try? JSONDecoder().decode([ManifestEntry].self, from: data) else {
            return
        }
        for entry in entries {
            let fileURL = folder.appendingPathComponent(entry.fileName)
            if FileManager.default.fileExists(atPath: fileURL.path) {
                manifest[entry.key] = CacheEntry(etag: entry.etag, contentType: entry.contentType, fileURL: fileURL)
                // File ordering preserves relative recency; assign
                // monotonic stamps in iteration order so newest-on-disk
                // becomes newest-in-memory.
                accessClock &+= 1
                lastAccess[entry.key] = accessClock
            }
        }
        logger.info("Loaded \(self.manifest.count) cached images from disk")
    }
}
