import Foundation
import UIKit
import CryptoKit
import os.log

private let logger = MMLogger(category: "ImageDiskCache")

/// Disk-backed LRU image cache with in-memory front layer.
/// Stores up to `maxCount` images on disk in Library/Caches/images/.
/// Each entry tracks an etag for conditional gRPC revalidation.
actor ImageDiskCache {
    static let shared = ImageDiskCache()

    private let maxCount = 500
    private let cacheDir: URL
    private let memoryCache = NSCache<NSString, UIImage>()

    /// In-memory manifest: cache_key → (etag, contentType, fileURL)
    private var manifest: [String: CacheEntry] = [:]
    /// LRU bookkeeping. Each touch bumps `accessClock` and stamps the
    /// key with the new value, so `touchAccess` is O(1) instead of
    /// the O(n) `Array.removeAll(where:)` scan we used before.
    /// Eviction sorts by this map only when the cache is over the
    /// `maxCount` cap — runs at most once per save burst.
    private var lastAccess: [String: UInt64] = [:]
    private var accessClock: UInt64 = 0
    private var pinnedKeys: Set<String> = [] // never evicted

    struct CacheEntry {
        let etag: String
        let contentType: String
        let fileURL: URL
    }

    private var loaded = false

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

    private init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        cacheDir = caches.appendingPathComponent("images", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        memoryCache.countLimit = 200
    }

    private func ensureLoaded() {
        guard !loaded else { return }
        loaded = true
        loadManifest()
    }

    // MARK: - Public API

    /// Returns cached image + etag if available. Nil if cache miss.
    func get(for ref: MMImageRef) -> (UIImage, String)? {
        ensureLoaded()
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
    func etag(for ref: MMImageRef) -> String? {
        ensureLoaded()
        return manifest[cacheKey(for: ref)]?.etag
    }

    /// Store image data on disk and in memory.
    func store(ref: MMImageRef, data: Data, contentType: String, etag: String) {
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

    /// Remove a specific entry.
    func remove(for ref: MMImageRef) {
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
    func pin(ref: MMImageRef) {
        pinnedKeys.insert(cacheKey(for: ref))
    }

    /// Unpin an image ref, allowing normal LRU eviction.
    func unpin(ref: MMImageRef) {
        pinnedKeys.remove(cacheKey(for: ref))
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

    // MARK: - Manifest Persistence

    private var manifestURL: URL { cacheDir.appendingPathComponent("manifest.json") }

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

    private func writeManifestNow() {
        struct ManifestEntry: Codable {
            let key: String
            let etag: String
            let contentType: String
            let fileName: String
        }
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
            try? data.write(to: manifestURL)
        }
    }

    private func loadManifest() {
        struct ManifestEntry: Codable {
            let key: String
            let etag: String
            let contentType: String
            let fileName: String
        }
        guard let data = try? Data(contentsOf: manifestURL),
              let entries = try? JSONDecoder().decode([ManifestEntry].self, from: data) else {
            return
        }
        for entry in entries {
            let fileURL = cacheDir.appendingPathComponent(entry.fileName)
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
