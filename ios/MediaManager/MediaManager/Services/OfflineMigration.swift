import Foundation

private let migrationLogger = MMLogger(category: "OfflineMigration")

/// One-time on-disk migrations for the offline subsystem. Bumped
/// when the on-disk format changes in a way that the new code can't
/// read old data — current managers create empty caches if the
/// expected files aren't present, so wiping is the cleanest path.
///
/// Called from `MediaManagerApp.init()` before any cache manager
/// constructs itself, so managers always see a coherent on-disk
/// state.
enum OfflineMigration {

    /// UserDefaults key. Absent or < `currentSchemaVersion` triggers
    /// the migration; after running we stash the new version so it
    /// doesn't recur on subsequent launches.
    private static let schemaVersionKey = "mm_offline_schema_version"

    /// Bump this when the on-disk format changes incompatibly.
    /// v1 — original (audio with detail.pb; video/book without).
    /// v2 — video carries per-season + per-episode metadata;
    ///      books carry detail.pb. Old downloads can't render the
    ///      new offline browse surfaces, so we wipe and re-fetch.
    private static let currentSchemaVersion = 2

    static func runIfNeeded() {
        let defaults = UserDefaults.standard
        let stored = defaults.integer(forKey: schemaVersionKey)
        guard stored < currentSchemaVersion else {
            migrationLogger.info("runIfNeeded: schema v\(stored) up to date")
            return
        }

        migrationLogger.info("runIfNeeded: migrating v\(stored) → v\(currentSchemaVersion)")
        wipeVideoAndBookDownloads()
        defaults.set(currentSchemaVersion, forKey: schemaVersionKey)
    }

    /// Delete every file under `<Application Support>/Downloads/`
    /// EXCEPT the `Audio` subtree — audio's on-disk format already
    /// matches v2, no need to make those users re-download.
    private static func wipeVideoAndBookDownloads() {
        let fm = FileManager.default
        guard let appSupport = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            migrationLogger.warning("wipe: no Application Support directory — skipping")
            return
        }
        let downloadsRoot = appSupport.appendingPathComponent("Downloads", isDirectory: true)

        guard fm.fileExists(atPath: downloadsRoot.path) else {
            migrationLogger.info("wipe: Downloads/ doesn't exist yet — nothing to do")
            return
        }

        let contents = (try? fm.contentsOfDirectory(at: downloadsRoot, includingPropertiesForKeys: nil)) ?? []
        var wiped = 0
        for url in contents {
            if url.lastPathComponent == "Audio" { continue }
            do {
                try fm.removeItem(at: url)
                wiped += 1
            } catch {
                migrationLogger.warning("wipe: failed to remove \(url.lastPathComponent): \(error.localizedDescription)")
            }
        }
        migrationLogger.info("wipe: removed \(wiped) entries from Downloads/ (Audio preserved)")
    }
}
