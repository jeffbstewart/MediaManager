import Foundation
import CryptoKit

private let logger = MMLogger(category: "StorageScope")

/// Whether an area's files may be rebuilt from the server at will
/// (`cache`, kept under Library/Caches, which iOS may purge) or hold
/// something the user chose to keep or has not sent yet (`durable`,
/// kept under Application Support).
public enum StorageClass: Sendable {
    case cache
    case durable
}

/// One store's folder inside a scope. Each store owns exactly one
/// area and keeps everything it writes inside it.
public enum StorageArea: String, CaseIterable, Sendable {
    /// `ImageDiskCache`.
    case images
    /// `DownloadStore` / `DownloadManager`: video downloads.
    case downloads
    /// `BookCacheManager`.
    case books
    /// `AudioCacheManager`.
    case audio
    /// `LocalProgressStore` and the reading and listening progress queues.
    case progress

    public var storageClass: StorageClass {
        switch self {
        case .images: return .cache
        case .downloads, .books, .audio, .progress: return .durable
        }
    }
}

/// The server and account that content on this device belongs to.
/// The server is named by the fingerprint its Discover answer carries
/// (its token-signing key), which survives a change of address; the
/// account by its user id on that server.
public struct StorageScopeID: Codable, Hashable, Sendable, CustomStringConvertible {
    public let serverFingerprint: String
    public let accountId: Int64

    public init(serverFingerprint: String, accountId: Int64) {
        self.serverFingerprint = serverFingerprint
        self.accountId = accountId
    }

    public var description: String {
        "server \(StorageScope.serverFolderName(serverFingerprint)) account \(accountId)"
    }
}

/// A store whose files live in a `StorageArea`. Register with
/// `StorageScope.register(_:)`, then read `StorageScope.current`;
/// from then on every change of scope arrives here, in order.
public protocol StorageScopeParticipant: AnyObject, Sendable {
    /// The scope is about to change, and the current folder may be
    /// deleted right after this returns. Stop writing, cancel work
    /// that writes, close open files, and drop anything held in
    /// memory that came from the current scope.
    func storageScopeWillChange() async

    /// The scope changed. `scope` is nil when nothing is bound (signed
    /// out, or changing server): serve nothing and write nothing.
    /// Otherwise load from `StorageScope.directory(for:)`. This is
    /// also the place to call `claimLegacy` for content stored before
    /// scopes existed.
    func storageScopeDidChange(to scope: StorageScopeID?) async
}

/// What `StorageScope.claimLegacy` did with content stored before
/// scopes existed.
public enum LegacyClaimOutcome: Equatable, Sendable {
    /// Nothing was at the old location.
    case nothingThere
    /// Moved into the current scope's folder.
    case adopted
    /// Deleted: it belongs to another server or account, or the
    /// scope already had content at the destination.
    case discarded
    /// No scope is bound, so who owns it is not known yet. The old
    /// content is left alone; claim it again after the next change.
    case notBound
}

/// Things that change which server and account the device's content
/// belongs to. `AuthManager` sends them as they happen.
public enum StorageScopeEvent: Sendable, Equatable {
    /// Recorded once, on the first launch with scoped storage: the
    /// server the device was set up for, whose content predates
    /// scopes. Later sends are ignored.
    case recordLegacyOwner(serverFingerprint: String?)
    /// The app is talking to the server with this fingerprint. A
    /// different server from the one content is kept for deletes all
    /// of that content.
    case serverConnected(serverFingerprint: String)
    /// The server said who is signed in. Binds the scope.
    case accountSignedIn(accountId: Int64)
    /// Launching offline with stored tokens: bind the last account
    /// without asking the server, if nothing has signed out since.
    case resumeLastAccount
    /// Signed out, or about to change server. Files are kept.
    case suspend(SuspendReason)
    /// The server was disconnected and forgotten. Deletes everything.
    case forgetEverything
    /// The account was deleted. Deletes its content.
    case forgetCurrentAccount
}

public enum SuspendReason: Sendable, Equatable {
    /// The tokens are gone; the next account signed in may be
    /// someone else, so the scope is not resumed without the server.
    case signedOut
    /// The tokens stay (same account, possibly a new address).
    case serverChanging
}

/// Which server and account the content on this device belongs to,
/// and where each store keeps its files.
///
/// Layout, under both Application Support (durable areas) and
/// Library/Caches (cache areas):
///
///     Scoped/servers/<hash of server fingerprint>/accounts/<user id>/<area>/
///
/// Rules:
/// - Content is kept for one server. Connecting to a different server
///   deletes all of the previous server's content.
/// - Content is kept for one account per server: signing in as a
///   different account deletes the previous account's. That rule is
///   `accountsToKeep`, the one place to change for multi-account.
/// - Signing out, or changing server, suspends the scope (stores
///   serve nothing) but keeps the files, so signing back in to the
///   same account, or following the same server to a new address,
///   finds everything still there.
///
/// Events are applied one at a time in the order sent, each one
/// waiting for every participant's callbacks, so a store is never
/// told about a change before it has finished with the last one.
public final class StorageScope: @unchecked Sendable {

    public static let shared: StorageScope = {
        let fm = FileManager.default
        return StorageScope(
            durableBase: fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0],
            cacheBase: fm.urls(for: .cachesDirectory, in: .userDomainMask)[0])
    }()

    /// Persisted, so an offline launch knows its scope.
    struct State: Codable, Equatable {
        /// The server whose content is kept, if any.
        var server: String?
        /// The account on `server` whose content is kept, if any.
        var account: Int64?
        /// Whether that account is bound right now.
        var active = false
        /// Whether `resumeLastAccount` may bind `account` without the
        /// server confirming it. False once someone has signed out.
        var resumable = false
        var legacyRecorded = false
        /// The server whose content predates scopes.
        var legacyOwner: String?
        /// The first scope bound on `legacyOwner`; the only one old
        /// content is moved into.
        var legacyAdopter: StorageScopeID?

        var current: StorageScopeID? {
            guard active, let server, let account else { return nil }
            return StorageScopeID(serverFingerprint: server, accountId: account)
        }
    }

    private struct WeakParticipant {
        weak var value: (any StorageScopeParticipant)?
    }

    private let durableRoot: URL
    private let cacheRoot: URL
    private let stateURL: URL
    private let fm = FileManager.default

    private let lock = NSLock()
    /// Guarded by `lock`.
    private var state: State
    /// Guarded by `lock`.
    private var participants: [WeakParticipant] = []
    /// Guarded by `lock`. The last queued event's task.
    private var tail: Task<Void, Never>?

    /// `durableBase` and `cacheBase` are the folders `Scoped/` goes
    /// under: Application Support and Library/Caches in the app, temp
    /// folders in tests.
    public init(durableBase: URL, cacheBase: URL) {
        durableRoot = durableBase.appendingPathComponent("Scoped", isDirectory: true)
        cacheRoot = cacheBase.appendingPathComponent("Scoped", isDirectory: true)
        stateURL = durableRoot.appendingPathComponent("scope.json")
        if let data = try? Data(contentsOf: stateURL),
           let saved = try? JSONDecoder().decode(State.self, from: data) {
            state = saved
        } else {
            state = State()
        }
    }

    // MARK: - Reading

    /// The bound scope, or nil when stores should serve nothing.
    public var current: StorageScopeID? {
        lock.withLock { state.current }
    }

    /// The folder for `area` in the bound scope, created if missing.
    /// Nil when no scope is bound.
    public func directory(for area: StorageArea) -> URL? {
        guard let scope = current else { return nil }
        let url = Self.directory(for: area, in: scope,
                                 root: root(for: area.storageClass))
        do {
            try fm.createDirectory(at: url, withIntermediateDirectories: true)
        } catch {
            logger.error("directory(for: \(area.rawValue)): \(error.localizedDescription)")
            return nil
        }
        return url
    }

    /// Folder name for a server: a hash, so the fingerprint itself is
    /// not written into paths and its characters never matter.
    public static func serverFolderName(_ fingerprint: String) -> String {
        let digest = SHA256.hash(data: Data(fingerprint.utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    static func directory(for area: StorageArea, in scope: StorageScopeID, root: URL) -> URL {
        accountFolder(scope.accountId, on: scope.serverFingerprint, root: root)
            .appendingPathComponent(area.rawValue, isDirectory: true)
    }

    private static func serversFolder(root: URL) -> URL {
        root.appendingPathComponent("servers", isDirectory: true)
    }

    private static func serverFolder(_ fingerprint: String, root: URL) -> URL {
        serversFolder(root: root)
            .appendingPathComponent(serverFolderName(fingerprint), isDirectory: true)
    }

    private static func accountFolder(_ account: Int64, on server: String, root: URL) -> URL {
        serverFolder(server, root: root)
            .appendingPathComponent("accounts", isDirectory: true)
            .appendingPathComponent(String(account), isDirectory: true)
    }

    private func root(for storageClass: StorageClass) -> URL {
        storageClass == .cache ? cacheRoot : durableRoot
    }

    // MARK: - Participants

    /// Start telling `participant` about scope changes. Held weakly.
    /// Register first, then read `current`, so no change is missed.
    public func register(_ participant: any StorageScopeParticipant) {
        lock.withLock {
            participants.removeAll { $0.value == nil || $0.value === participant }
            participants.append(WeakParticipant(value: participant))
        }
    }

    // MARK: - Events

    /// Queue an event. Events are applied in the order sent.
    public func send(_ event: StorageScopeEvent) {
        lock.withLock {
            let previous = tail
            tail = Task { [self] in
                await previous?.value
                await apply(event)
            }
        }
    }

    /// Returns once every event sent so far has been applied.
    public func settled() async {
        let last = lock.withLock { tail }
        await last?.value
    }

    private func apply(_ event: StorageScopeEvent) async {
        let before = lock.withLock { state }
        var after = before
        var deletions: [URL] = []

        switch event {
        case .recordLegacyOwner(let fingerprint):
            guard !before.legacyRecorded else { return }
            after.legacyRecorded = true
            after.legacyOwner = fingerprint

        case .serverConnected(let fingerprint):
            guard before.server != fingerprint else { return }
            // A different server: nothing of the old one is kept.
            deletions = allServerFolders()
            after.server = fingerprint
            after.account = nil
            after.active = false
            after.resumable = false
            if after.legacyAdopter == nil, after.legacyOwner != fingerprint {
                // Old content belongs to a server we have left.
                after.legacyOwner = nil
            }

        case .accountSignedIn(let accountId):
            guard let server = before.server else {
                logger.warning("accountSignedIn: no server connected; not binding")
                return
            }
            guard !(before.active && before.account == accountId) else { return }
            let keep = Self.accountsToKeep(signingIn: accountId, previous: before.account)
            deletions = accountFolders(on: server, except: keep)
            after.account = accountId
            after.active = true
            after.resumable = true
            if after.legacyAdopter == nil {
                if after.legacyOwner == server {
                    after.legacyAdopter = StorageScopeID(serverFingerprint: server, accountId: accountId)
                } else {
                    after.legacyOwner = nil
                }
            }

        case .resumeLastAccount:
            guard !before.active, before.resumable,
                  before.server != nil, before.account != nil else { return }
            after.active = true

        case .suspend(let reason):
            after.active = false
            if reason == .signedOut { after.resumable = false }

        case .forgetEverything:
            deletions = allServerFolders()
            after = State(legacyRecorded: true)

        case .forgetCurrentAccount:
            if let server = before.server, let account = before.account {
                deletions = [durableRoot, cacheRoot].map {
                    Self.accountFolder(account, on: server, root: $0)
                }
            }
            after.account = nil
            after.active = false
            after.resumable = false
            if after.legacyAdopter?.accountId == before.account {
                after.legacyOwner = nil
            }
        }

        let scopeChanges = before.current != after.current || !deletions.isEmpty
        let live = lock.withLock { participants.compactMap(\.value) }

        if scopeChanges, before.current != nil {
            for participant in live { await participant.storageScopeWillChange() }
        }
        for url in deletions where fm.fileExists(atPath: url.path) {
            do {
                try fm.removeItem(at: url)
            } catch {
                logger.error("apply(\(String(describing: event))): could not delete a folder: \(error.localizedDescription)")
            }
        }
        lock.withLock { state = after }
        persist(after)
        if scopeChanges {
            logger.info("scope \(before.current?.description ?? "none") -> \(after.current?.description ?? "none") after \(String(describing: event)); deleted \(deletions.count) folder(s)")
            for participant in live { await participant.storageScopeDidChange(to: after.current) }
        }
    }

    /// The accounts whose content survives `accountId` signing in on
    /// the same server. One account per device today: only the one
    /// signing in. Supporting several accounts means returning more
    /// here (and suspending, rather than deleting, the others).
    static func accountsToKeep(signingIn accountId: Int64, previous: Int64?) -> Set<Int64> {
        [accountId]
    }

    private func allServerFolders() -> [URL] {
        [durableRoot, cacheRoot].flatMap { root -> [URL] in
            (try? fm.contentsOfDirectory(at: Self.serversFolder(root: root),
                                         includingPropertiesForKeys: nil)) ?? []
        }
    }

    private func accountFolders(on server: String, except keep: Set<Int64>) -> [URL] {
        [durableRoot, cacheRoot].flatMap { root -> [URL] in
            let accounts = Self.serverFolder(server, root: root)
                .appendingPathComponent("accounts", isDirectory: true)
            let children = (try? fm.contentsOfDirectory(at: accounts,
                                                       includingPropertiesForKeys: nil)) ?? []
            return children.filter { url in
                guard let id = Int64(url.lastPathComponent) else { return true }
                return !keep.contains(id)
            }
        }
    }

    private func persist(_ state: State) {
        do {
            try fm.createDirectory(at: durableRoot, withIntermediateDirectories: true)
            try JSONEncoder().encode(state).write(to: stateURL, options: .atomic)
        } catch {
            logger.error("persist: \(error.localizedDescription)")
        }
    }

    // MARK: - Content from before scopes

    /// Hand content stored before scopes existed to the bound scope.
    /// It moves to `relativePath` inside `area`'s folder if this is
    /// the first scope bound on the server the device was set up for,
    /// and is deleted otherwise. Safe to call on every scope change:
    /// once the old content is gone this does nothing.
    ///
    /// Call from `storageScopeDidChange(to:)` or after it, never while
    /// unbound expecting a deletion: with no scope bound the owner is
    /// not known yet, so the content is left where it is.
    @discardableResult
    public func claimLegacy(at legacyURL: URL, into area: StorageArea,
                            as relativePath: String) -> LegacyClaimOutcome {
        guard fm.fileExists(atPath: legacyURL.path) else { return .nothingThere }
        let (scope, adopter) = lock.withLock { (state.current, state.legacyAdopter) }
        guard let scope else { return .notBound }
        if scope == adopter, let folder = directory(for: area) {
            let destination = folder.appendingPathComponent(relativePath)
            if !fm.fileExists(atPath: destination.path) {
                do {
                    try fm.createDirectory(at: destination.deletingLastPathComponent(),
                                           withIntermediateDirectories: true)
                    try fm.moveItem(at: legacyURL, to: destination)
                    logger.info("claimLegacy: adopted \(legacyURL.lastPathComponent) into \(area.rawValue)")
                    return .adopted
                } catch {
                    logger.error("claimLegacy: could not move \(legacyURL.lastPathComponent): \(error.localizedDescription); deleting it")
                }
            }
        }
        discardLegacy(at: legacyURL)
        return .discarded
    }

    /// Delete content stored before scopes existed that is never worth
    /// keeping, such as the old image cache.
    public func discardLegacy(at legacyURL: URL) {
        guard fm.fileExists(atPath: legacyURL.path) else { return }
        do {
            try fm.removeItem(at: legacyURL)
            logger.info("discardLegacy: deleted \(legacyURL.lastPathComponent)")
        } catch {
            logger.error("discardLegacy: \(error.localizedDescription)")
        }
    }
}
