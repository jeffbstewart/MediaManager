import Foundation
import MediaManagerProtos

/// Which optional sections this server offers the signed-in user:
/// the `Features` message from `CatalogService`. A server with no
/// cameras, no tuner or no personal videos reports those as off, and
/// the app leaves the matching sections out of its navigation, as the
/// web app does.
///
/// The last answer is kept across launches so the sidebar is right
/// from the first frame. Before any answer has ever arrived, the
/// sections every server is expected to have (books, music) show and
/// the ones that need extra hardware or content (cameras, live TV,
/// personal videos) do not.
public struct ServerFeatures: Codable, Equatable, Sendable {
    public var hasPersonalVideos: Bool
    public var hasBooks: Bool
    public var hasMusic: Bool
    public var hasCameras: Bool
    public var hasLiveTv: Bool

    public init(proto: MMFeatures) {
        hasPersonalVideos = proto.hasPersonalVideos_p
        hasBooks = proto.hasBooks_p
        hasMusic = proto.hasMusic_p
        hasCameras = proto.hasCameras_p
        hasLiveTv = proto.hasLiveTv_p
    }

    /// Used until a server has answered.
    public static let unknown = ServerFeatures(
        hasPersonalVideos: false, hasBooks: true, hasMusic: true, hasCameras: false, hasLiveTv: false)

    init(hasPersonalVideos: Bool, hasBooks: Bool, hasMusic: Bool, hasCameras: Bool, hasLiveTv: Bool) {
        self.hasPersonalVideos = hasPersonalVideos
        self.hasBooks = hasBooks
        self.hasMusic = hasMusic
        self.hasCameras = hasCameras
        self.hasLiveTv = hasLiveTv
    }

    // MARK: Last-known answer

    /// Persisted; do not rename. Cleared with the other server answers
    /// by `ServerSettingsCache` when the server changes.
    static let defaultsKey = "mm.serverFeatures"

    static func loadCached() -> ServerFeatures? {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey) else { return nil }
        return try? JSONDecoder().decode(ServerFeatures.self, from: data)
    }

    func saveToCache() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: Self.defaultsKey)
        }
    }

    /// Forget the answer: the next server may differ.
    public static func clearCache() {
        UserDefaults.standard.removeObject(forKey: defaultsKey)
    }
}
