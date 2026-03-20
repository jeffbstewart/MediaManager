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

    @State private var image: UIImage?
    @State private var loading = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
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
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .task(id: path) {
            guard let path, !path.isEmpty else { return }
            loading = true
            image = await AuthenticatedImageLoader.shared.image(for: path, using: apiClient)
            loading = false
        }
    }
}
