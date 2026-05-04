import SwiftUI

/// Loads images from server endpoints that require JWT authentication.
/// Standard AsyncImage can't add Authorization headers, so this handles
/// authenticated image fetching with in-memory caching.
@MainActor
final class AuthenticatedImageLoader {
    static let shared = AuthenticatedImageLoader()

    private let cache = NSCache<NSString, UIImage>()
    private var inFlight: [String: Task<UIImage?, Never>] = [:]

    private init() {
        cache.countLimit = 500
    }

    func image(for path: String, using apiClient: APIClient) async -> UIImage? {
        if let cached = cache.object(forKey: path as NSString) {
            return cached
        }

        // Coalesce duplicate requests
        if let existing = inFlight[path] {
            return await existing.value
        }

        let task = Task<UIImage?, Never> {
            do {
                let data: Data = try await apiClient.getRaw(path)
                guard let image = UIImage(data: data) else { return nil }
                cache.object(forKey: path as NSString)
                cache.setObject(image, forKey: path as NSString)
                return image
            } catch {
                return nil
            }
        }
        inFlight[path] = task
        let result = await task.value
        inFlight.removeValue(forKey: path)
        return result
    }
}

/// SwiftUI view that displays an authenticated image with a placeholder.
struct AuthenticatedImage: View {
    let path: String?
    let apiClient: APIClient
    var cornerRadius: CGFloat = 8
    var contentMode: ContentMode = .fill

    @State private var image: UIImage?
    @State private var loading = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else {
                Rectangle()
                    .fill(.quaternary)
                    .overlay {
                        if loading {
                            ProgressView()
                        } else {
                            Image(systemName: "film")
                                .foregroundStyle(.secondary)
                        }
                    }
            }
        }
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .task(id: path) {
            guard let path, !path.isEmpty else { return }
            loading = true
            image = await AuthenticatedImageLoader.shared.image(for: path, using: apiClient)
            loading = false
        }
    }
}

/// SwiftUI view that loads images via gRPC ImageService with disk caching.
/// Uses typed ImageRef instead of URL path strings.
struct CachedImage: View {
    let ref: MMImageRef?
    @Environment(ImageProvider.self) private var imageProvider
    var cornerRadius: CGFloat = 8
    var contentMode: ContentMode = .fill
    /// When true, the placeholder branch renders `Color.clear` instead
    /// of the gray-filled `film` rectangle. Use for overlay layers
    /// (e.g. an author headshot stacked on top of a fallback book
    /// cover) so a missing top-layer image lets the lower layer show.
    var transparentPlaceholder: Bool = false

    @State private var image: UIImage?
    @State private var loading = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else if transparentPlaceholder {
                Color.clear
            } else {
                Rectangle()
                    .fill(.quaternary)
                    .overlay {
                        if loading {
                            ProgressView()
                        } else {
                            Image(systemName: "film")
                                .foregroundStyle(.secondary)
                        }
                    }
            }
        }
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .task(id: refKey) {
            guard let ref else { return }
            loading = true
            image = await imageProvider.image(for: ref)
            loading = false
        }
    }

    private var refKey: String {
        guard let ref else { return "" }
        // Must enumerate every discriminating field on MMImageRef. Missing
        // any field causes SwiftUI's `.task(id:)` to skip re-fetches when
        // navigating between two different refs that share the omitted
        // fields' default values — manifested as author cards all
        // showing the first author's headshot.
        let tmdb = ref.hasTmdbMedia
            ? "\(ref.tmdbMedia.tmdbID)/\(ref.tmdbMedia.mediaType.rawValue)"
            : ""
        return [
            "\(ref.type.rawValue)",
            "\(ref.titleID)",
            "\(ref.tmdbPersonID)",
            "\(ref.tmdbCollectionID)",
            ref.uuid,
            "\(ref.cameraID)",
            "\(ref.artistID)",
            "\(ref.authorID)",
            ref.musicbrainzReleaseGroupID,
            ref.openlibraryWorkID,
            tmdb,
        ].joined(separator: "-")
    }
}
