import Foundation
import UIKit
import os.log

private let logger = MMLogger(category: "ImageProvider")

/// Provides images by reference, abstracting gRPC streaming and disk/memory caching.
///
/// Views request images via `ImageRef` — no URL construction needed.
/// The provider checks memory cache → disk cache → gRPC stream, in order.
/// Disk-cached images are returned immediately while a conditional revalidation
/// happens in the background (stale-while-revalidate).
@MainActor @Observable
final class ImageProvider {
    private let streamClient: ImageStreamClient
    private let cache = ImageDiskCache.shared

    init(grpcClient: GrpcClient) {
        self.streamClient = ImageStreamClient(grpcClient: grpcClient)
    }

    /// Load an image by reference. Returns nil if not found or on error.
    func image(for ref: MMImageRef) async -> UIImage? {
        // 1. Check cache (memory + disk)
        if let (cached, etag) = await cache.get(for: ref) {
            // Have a cached copy — do a conditional revalidation in the background
            // (don't block the UI waiting for it)
            Task {
                await revalidate(ref: ref, etag: etag)
            }
            return cached
        }

        // 2. Cache miss — fetch from server
        let etag = await cache.etag(for: ref)
        let response = await streamClient.fetch(ref: ref, etag: etag)

        guard let response else { return nil }

        switch response.result {
        case .data(let imageData):
            let data = imageData.content
            await cache.store(ref: ref, data: data, contentType: imageData.contentType, etag: imageData.etag)
            return UIImage(data: data)
        case .notModified:
            // Cache still valid — shouldn't happen on a miss, but handle gracefully
            return await cache.get(for: ref)?.0
        case .notFound, .permissionDenied:
            return nil
        case nil:
            return nil
        }
    }

    /// Notify that the user has navigated away — cancel pending image fetches.
    func cancelPending() {
        Task {
            await streamClient.cancelStale()
        }
    }

    /// Shut down the stream.
    func close() {
        Task {
            await streamClient.close()
        }
    }

    // MARK: - Background revalidation

    private func revalidate(ref: MMImageRef, etag: String) async {
        let response = await streamClient.fetch(ref: ref, etag: etag)
        guard let response else { return }

        switch response.result {
        case .data(let imageData):
            // Image changed — update cache
            await cache.store(ref: ref, data: imageData.content, contentType: imageData.contentType, etag: imageData.etag)
        case .notModified:
            break // Cache is still valid, nothing to do
        case .notFound:
            await cache.remove(for: ref)
        case .permissionDenied, nil:
            break
        }
    }
}

// MARK: - ImageRef convenience constructors

extension MMImageRef {
    static func posterThumbnail(titleId: Int64) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .posterThumbnail
        ref.titleID = titleId
        return ref
    }

    static func posterFull(titleId: Int64) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .posterFull
        ref.titleID = titleId
        return ref
    }

    static func backdrop(titleId: Int64) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .backdrop
        ref.titleID = titleId
        return ref
    }

    static func headshot(tmdbPersonId: Int32) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .headshot
        ref.tmdbPersonID = tmdbPersonId
        return ref
    }

    static func collectionPoster(tmdbCollectionId: Int32) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .collectionPoster
        ref.tmdbCollectionID = tmdbCollectionId
        return ref
    }

    static func localImage(uuid: String) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .localImage
        ref.uuid = uuid
        return ref
    }

    static func ownershipPhoto(uuid: String) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .ownershipPhoto
        ref.uuid = uuid
        return ref
    }

    static func cameraSnapshot(cameraId: Int64) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .cameraSnapshot
        ref.cameraID = cameraId
        return ref
    }

    static func tmdbPoster(tmdbId: Int32, mediaType: MMMediaType) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .tmdbPoster
        var media = MMTmdbMediaId()
        media.tmdbID = tmdbId
        media.mediaType = mediaType
        ref.tmdbMedia = media
        return ref
    }
}
