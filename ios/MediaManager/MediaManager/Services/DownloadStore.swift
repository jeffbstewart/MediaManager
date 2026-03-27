import Foundation
import SwiftProtobuf
import os.log

private let logger = Logger(subsystem: "net.stewart.mediamanager", category: "DownloadStore")

/// Atomic protobuf-encoded persistence for download metadata.
///
/// Files live in Library/Application Support/Downloads/.
/// Metadata is a single `DownloadDatabase` protobuf serialized to `downloads.meta.db`.
/// Write protocol: backup → rename → write. Startup: try .db → .backup → wipe.
actor DownloadStore {
    static let shared = DownloadStore()

    let downloadsDir: URL
    private let dbPath: URL
    private let backupPath: URL
    private let postersDir: URL

    private var db: MMDownloadDatabase

    private init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        downloadsDir = appSupport.appendingPathComponent("Downloads", isDirectory: true)
        dbPath = downloadsDir.appendingPathComponent("downloads.meta.db")
        backupPath = downloadsDir.appendingPathComponent("downloads.meta.db.backup")
        postersDir = downloadsDir.appendingPathComponent("posters", isDirectory: true)

        let fm = FileManager.default
        try? fm.createDirectory(at: downloadsDir, withIntermediateDirectories: true)
        try? fm.createDirectory(at: postersDir, withIntermediateDirectories: true)

        // Exclude from iCloud backup
        var dir = downloadsDir
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? dir.setResourceValues(rv)

        // Load database
        db = Self.loadDatabase(dbPath: dbPath, backupPath: backupPath, downloadsDir: downloadsDir)
        let count = db.entries.count
        let nextSeq = db.nextSequence
        logger.info("Loaded download store: \(count) entries, next_seq=\(nextSeq)")
    }

    // MARK: - Read

    var entries: [MMDownloadEntry] { db.entries }

    func entry(for transcodeId: Int64) -> MMDownloadEntry? {
        db.entries.first { $0.transcodeID == transcodeId }
    }

    func entry(bySequence seq: Int32) -> MMDownloadEntry? {
        db.entries.first { $0.sequence == seq }
    }

    // MARK: - Write

    /// Allocate a new sequence number and add an entry.
    func addEntry(_ entry: MMDownloadEntry) -> MMDownloadEntry {
        var e = entry
        e.sequence = db.nextSequence
        db.nextSequence += 1
        db.entries.append(e)
        save()
        return e
    }

    /// Update an existing entry by transcode ID.
    func updateEntry(transcodeId: Int64, _ mutate: @Sendable (inout MMDownloadEntry) -> Void) {
        guard let idx = db.entries.firstIndex(where: { $0.transcodeID == transcodeId }) else { return }
        mutate(&db.entries[idx])
        save()
    }

    /// Remove an entry and its files.
    func removeEntry(transcodeId: Int64) {
        guard let idx = db.entries.firstIndex(where: { $0.transcodeID == transcodeId }) else { return }
        let entry = db.entries[idx]
        deleteFiles(for: entry)
        db.entries.remove(at: idx)
        save()
    }

    /// Remove all entries and files.
    func removeAll() {
        for entry in db.entries {
            deleteFiles(for: entry)
        }
        db.entries.removeAll()
        db.nextSequence = 1
        save()
    }

    // MARK: - File Paths

    func videoPath(for entry: MMDownloadEntry, downloading: Bool = false) -> URL {
        let name = String(format: "%07d.mp4%@", entry.sequence, downloading ? ".downloading" : "")
        return downloadsDir.appendingPathComponent(name)
    }

    func subtitlesPath(for entry: MMDownloadEntry) -> URL {
        downloadsDir.appendingPathComponent(String(format: "%07d.subs.vtt", entry.sequence))
    }

    func chaptersPath(for entry: MMDownloadEntry) -> URL {
        downloadsDir.appendingPathComponent(String(format: "%07d.chapters.json", entry.sequence))
    }

    func thumbnailsPath(for entry: MMDownloadEntry) -> URL {
        downloadsDir.appendingPathComponent(String(format: "%07d.thumbs.vtt", entry.sequence))
    }

    func posterPath(for entry: MMDownloadEntry) -> URL {
        postersDir.appendingPathComponent(String(format: "%07d.jpg", entry.sequence))
    }

    func detailPath(for entry: MMDownloadEntry) -> URL {
        downloadsDir.appendingPathComponent(String(format: "%07d.detail.pb", entry.sequence))
    }

    /// Rename .downloading → .mp4 on completion.
    func finalizeVideo(for entry: MMDownloadEntry) throws {
        let downloading = videoPath(for: entry, downloading: true)
        let final_ = videoPath(for: entry, downloading: false)
        try FileManager.default.moveItem(at: downloading, to: final_)
    }

    // MARK: - Orphan Cleanup

    /// Delete files not referenced by any entry, and fix entries pointing to missing files.
    func cleanOrphans() {
        let fm = FileManager.default
        let knownFiles = buildKnownFileSet()

        // Delete orphan files
        if let contents = try? fm.contentsOfDirectory(at: downloadsDir, includingPropertiesForKeys: nil) {
            for fileURL in contents {
                let name = fileURL.lastPathComponent
                if name == "downloads.meta.db" || name == "downloads.meta.db.backup" || name == "posters" {
                    continue
                }
                if !knownFiles.contains(name) {
                    logger.info("Removing orphan file: \(name)")
                    try? fm.removeItem(at: fileURL)
                }
            }
        }

        // Fix entries with missing files
        var changed = false
        for i in db.entries.indices {
            let entry = db.entries[i]
            if entry.state == .completed {
                let path = videoPath(for: entry)
                if !fm.fileExists(atPath: path.path) {
                    logger.warning("Completed download missing file: seq=\(entry.sequence)")
                    db.entries[i].state = .failed
                    db.entries[i].errorMessage = "File missing from disk"
                    changed = true
                }
            } else if entry.state == .downloading || entry.state == .paused || entry.state == .fetchingMetadata || entry.state == .queued {
                let path = videoPath(for: entry, downloading: true)
                if fm.fileExists(atPath: path.path) {
                    // Reconcile bytesDownloaded with actual file size on disk
                    let attrs = try? fm.attributesOfItem(atPath: path.path)
                    let actualSize = (attrs?[.size] as? Int64) ?? 0
                    if actualSize != entry.bytesDownloaded {
                        logger.info("Reconciling download \(entry.sequence): stored=\(entry.bytesDownloaded) actual=\(actualSize)")
                        db.entries[i].bytesDownloaded = actualSize
                        changed = true
                    }
                } else {
                    // No .downloading file — reset progress
                    if entry.bytesDownloaded > 0 {
                        db.entries[i].bytesDownloaded = 0
                        changed = true
                    }
                }
                if !entry.resumeData.isEmpty && !fm.fileExists(atPath: path.path) {
                    db.entries[i].resumeData = Data()
                    changed = true
                }
            }
        }
        if changed { save() }
    }

    // MARK: - Storage Info

    var totalStorageBytes: Int64 {
        let fm = FileManager.default
        var total: Int64 = 0
        if let contents = try? fm.contentsOfDirectory(at: downloadsDir, includingPropertiesForKeys: [.fileSizeKey]) {
            for url in contents {
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
                total += Int64(size)
            }
        }
        return total
    }

    // MARK: - Private

    private func save() {
        // Step 1: if both exist, delete backup
        if FileManager.default.fileExists(atPath: dbPath.path) && FileManager.default.fileExists(atPath: backupPath.path) {
            try? FileManager.default.removeItem(at: backupPath)
        }

        // Step 2: if .db exists, rename to .backup
        if FileManager.default.fileExists(atPath: dbPath.path) {
            try? FileManager.default.moveItem(at: dbPath, to: backupPath)
        }

        // Step 3: write new .db
        do {
            let data = try db.serializedData()
            try data.write(to: dbPath)
        } catch {
            logger.error("Failed to write download database: \(error.localizedDescription)")
        }
    }

    private func deleteFiles(for entry: MMDownloadEntry) {
        let fm = FileManager.default
        let paths = [
            videoPath(for: entry),
            videoPath(for: entry, downloading: true),
            subtitlesPath(for: entry),
            chaptersPath(for: entry),
            thumbnailsPath(for: entry),
            posterPath(for: entry),
            detailPath(for: entry),
        ]
        for path in paths {
            try? fm.removeItem(at: path)
        }
    }

    private func buildKnownFileSet() -> Set<String> {
        var known = Set<String>()
        for entry in db.entries {
            let seq = String(format: "%07d", entry.sequence)
            known.insert("\(seq).mp4")
            known.insert("\(seq).mp4.downloading")
            known.insert("\(seq).subs.vtt")
            known.insert("\(seq).chapters.json")
            known.insert("\(seq).thumbs.vtt")
            known.insert("\(seq).detail.pb")
        }
        return known
    }

    // MARK: - Database Loading

    private static func loadDatabase(dbPath: URL, backupPath: URL, downloadsDir: URL) -> MMDownloadDatabase {
        // Try primary
        if let data = try? Data(contentsOf: dbPath),
           let db = try? MMDownloadDatabase(serializedBytes: data) {
            return db
        }

        // Try backup
        if let data = try? Data(contentsOf: backupPath),
           let db = try? MMDownloadDatabase(serializedBytes: data) {
            logger.warning("Loaded download database from backup")
            return db
        }

        // Both failed — wipe directory
        if FileManager.default.fileExists(atPath: downloadsDir.path) {
            logger.error("Download database corrupt — wiping downloads directory")
            let contents = (try? FileManager.default.contentsOfDirectory(at: downloadsDir, includingPropertiesForKeys: nil)) ?? []
            for url in contents {
                try? FileManager.default.removeItem(at: url)
            }
            // Recreate posters dir
            try? FileManager.default.createDirectory(
                at: downloadsDir.appendingPathComponent("posters", isDirectory: true),
                withIntermediateDirectories: true
            )
        }

        var db = MMDownloadDatabase()
        db.nextSequence = 1
        return db
    }
}
