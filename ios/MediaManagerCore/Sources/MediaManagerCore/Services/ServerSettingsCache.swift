import Foundation

private let logger = MMLogger(category: "ServerSettingsCache")

/// The server's answers kept in UserDefaults so the app is right from
/// the first frame and works offline: its legal documents (for the
/// terms screen), the signed-in user's capabilities, and which
/// sections it offers (`ServerFeatures`). They hold no server id of
/// their own, so this remembers which server they came from and
/// clears them when the app connects to a different one (#75).
///
/// Device preferences (offline mode, reader settings, biometric
/// sign-in) are not here; they belong to the device, not the server.
///
/// `AuthManager` calls `serverConnected` where it sends
/// `StorageScopeEvent.serverConnected`, with the same fingerprint,
/// and before it writes the new server's answers. That ordering is
/// why this is not a `StorageScopeParticipant`: scope callbacks run
/// later, on their own task, and would clear the legal documents
/// `connectToServer` has just written, or the capabilities
/// `refreshServerInfo` writes right after binding the account.
public struct ServerSettingsCache {
    /// `MMLegalDocumentInfo`, serialized. Persisted; do not rename.
    public static let legalDocsKey = "cachedLegalDocs"
    /// `[String]`. Persisted; do not rename.
    public static let capabilitiesKey = "cachedCapabilities"
    /// The server the cached answers came from, by
    /// `StorageScope.serverFolderName`, so the fingerprint itself is
    /// not stored here.
    static let ownerKey = "mm.serverSettingsOwner"

    /// Every key cleared when the server changes.
    static var serverKeys: [String] {
        [legalDocsKey, capabilitiesKey, ServerFeatures.defaultsKey]
    }

    /// Where the fingerprint passed to `serverConnected` came from.
    public enum Source: Sendable {
        /// The fingerprint pinned in the Keychain, at launch. The
        /// cached answers came from that server, so on the first
        /// launch with this code, when no owner is recorded yet, they
        /// are kept.
        case pinned
        /// A Discover answer. With no owner recorded, where the cached
        /// answers came from is unknown, so they are cleared.
        case discovered
    }

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The app is talking to the server with this fingerprint. A
    /// different server from the one the cached answers came from
    /// clears them. Returns true when it cleared them, so the caller
    /// can drop copies it holds in memory.
    @discardableResult
    public func serverConnected(_ fingerprint: String, source: Source) -> Bool {
        let server = StorageScope.serverFolderName(fingerprint)
        let owner = defaults.string(forKey: Self.ownerKey)
        defer { defaults.set(server, forKey: Self.ownerKey) }
        if owner == server { return false }
        if owner == nil, source == .pinned { return false }
        for key in Self.serverKeys { defaults.removeObject(forKey: key) }
        logger.info("serverConnected: \(owner == nil ? "no server recorded" : "a different server"); cleared the cached server settings")
        return true
    }
}
