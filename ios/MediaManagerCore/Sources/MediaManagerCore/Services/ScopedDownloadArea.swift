import Foundation

private let logger = MMLogger(category: "ScopedDownloadArea")

/// The folder a download store keeps in one `StorageScope` area, and
/// the downloads writing into it. Shared by `BookCacheManager`,
/// `AudioCacheManager` and `DownloadManager`, which stay the scope
/// participants: they call `bind()` on launch and after a change, and
/// `unbind()` plus `cancelAndAwaitWriters()` before one.
@MainActor
final class ScopedDownloadArea {
    let scope: StorageScope
    let area: StorageArea
    /// Where the store kept its files before scopes. Each item in it is
    /// claimed into the area on bind, then the emptied folder goes.
    let legacyFolder: URL?
    /// Items in `legacyFolder` that belong to another store and are
    /// left where they are (the video store's old folder held the
    /// book and audio folders too).
    let leaving: Set<String>

    /// The bound scope's folder for `area`; nil when nothing is bound,
    /// and from the moment a change is announced.
    private(set) var folder: URL?

    /// Work writing into `folder`, so a change can cancel and await it.
    private var writers: [UUID: Task<Void, Never>] = [:]

    init(scope: StorageScope, area: StorageArea, legacyFolder: URL?,
         leaving: Set<String> = []) {
        self.scope = scope
        self.area = area
        self.legacyFolder = legacyFolder
        self.leaving = leaving
    }

    /// Read the bound scope's folder and claim content from before
    /// scopes into it. Returns the folder, or nil when nothing is bound.
    @discardableResult
    func bind() -> URL? {
        folder = nil
        guard scope.current != nil, var url = scope.directory(for: area) else { return nil }
        // Downloads re-download on a new device; keep them out of iCloud.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        claimLegacy()
        folder = url
        return url
    }

    /// Stop serving the folder; new work is refused from here on.
    func unbind() {
        folder = nil
    }

    /// Start work that writes into the folder. It is tracked until it
    /// finishes, so `cancelAndAwaitWriters` can stop it.
    @discardableResult
    func run(_ body: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        let id = UUID()
        let task = Task { @MainActor [weak self] in
            await body()
            self?.writers[id] = nil
        }
        writers[id] = task
        return task
    }

    /// Cancel every download writing into the folder and wait for each
    /// to finish cleaning up, so the folder can be deleted after.
    func cancelAndAwaitWriters() async {
        while let entry = writers.first {
            for other in writers.values { other.cancel() }
            await entry.value.value
            writers[entry.key] = nil
        }
    }

    private func claimLegacy() {
        guard let legacyFolder else { return }
        let fm = FileManager.default
        guard fm.fileExists(atPath: legacyFolder.path) else { return }
        let items = ((try? fm.contentsOfDirectory(at: legacyFolder, includingPropertiesForKeys: nil)) ?? [])
            .filter { !leaving.contains($0.lastPathComponent) }
        var outcomes: [LegacyClaimOutcome] = []
        for item in items {
            outcomes.append(scope.claimLegacy(at: item, into: area, as: item.lastPathComponent))
        }
        let left = (try? fm.contentsOfDirectory(atPath: legacyFolder.path)) ?? []
        if left.isEmpty { scope.discardLegacy(at: legacyFolder) }
        if !items.isEmpty {
            let adopted = outcomes.filter { $0 == .adopted }.count
            logger.info("claimLegacy(\(area.rawValue)): \(adopted) of \(items.count) item(s) adopted, the rest discarded")
        }
    }
}
