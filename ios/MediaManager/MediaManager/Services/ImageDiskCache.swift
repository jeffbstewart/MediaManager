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
    private var accessOrder: [String] = [] // most recent at end
    private var pinnedKeys: Set<String> = [] // never evicted

    struct CacheEntry {
        let etag: String
        let contentType: String
        let fileURL: URL
    }

    private var loaded = false

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
            accessOrder.removeAll { $0 == key }
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
        saveManifest()
    }

    /// Remove a specific entry.
    func remove(for ref: MMImageRef) {
        let key = cacheKey(for: ref)
        if let entry = manifest.removeValue(forKey: key) {
            try? FileManager.default.removeItem(at: entry.fileURL)
        }
        accessOrder.removeAll { $0 == key }
        memoryCache.removeObject(forKey: key as NSString)
        saveManifest()
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

    private func touchAccess(_ key: String) {
        accessOrder.removeAll { $0 == key }
        accessOrder.append(key)
    }

    private func evictIfNeeded() {
        while manifest.count > maxCount {
            // Find oldest non-pinned entry
            guard let idx = accessOrder.firstIndex(where: { !pinnedKeys.contains($0) }) else { break }
            let oldest = accessOrder.remove(at: idx)
            if let entry = manifest.removeValue(forKey: oldest) {
                try? FileManager.default.removeItem(at: entry.fileURL)
                memoryCache.removeObject(forKey: oldest as NSString)
            }
        }
    }

    // MARK: - Manifest Persistence

    private var manifestURL: URL { cacheDir.appendingPathComponent("manifest.json") }

    private func saveManifest() {
        struct ManifestEntry: Codable {
            let key: String
            let etag: String
            let contentType: String
            let fileName: String
        }
        let entries = accessOrder.compactMap { key -> ManifestEntry? in
            guard let entry = manifest[key] else { return nil }
            return ManifestEntry(key: key, etag: entry.etag, contentType: entry.contentType, fileName: entry.fileURL.lastPathComponent)
        }
        if let data = try? JSONEncoder().encode(entries) {
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
                accessOrder.append(entry.key)
            }
        }
        logger.info("Loaded \(self.manifest.count) cached images from disk")
    }
}
