import Foundation
import UIKit
import os.log
import MediaManagerProtos

private let logger = MMLogger(category: "ImageProvider")

/// Provides images by reference, abstracting gRPC streaming and disk/memory caching.
///
/// Views request images via `ImageRef` — no URL construction needed.
/// The provider checks memory cache → disk cache → gRPC stream, in order.
/// Disk-cached images are returned immediately while a conditional revalidation
/// happens in the background (stale-while-revalidate).
@MainActor @Observable
public final class ImageProvider {
    private let streamClient: ImageStreamClient
    private let cache = ImageDiskCache.shared

    /// The gRPC client's connection generation, mirrored here so views
    /// can key their image loads on it: an image already on screen
    /// reloads after a server or account change (`CachedImage`).
    public private(set) var connectionGeneration: UInt64 = 0

    /// `imageStream` is the one `AuthManager` closes on sign-out and
    /// server changes; it must talk through `grpcClient`.
    public init(grpcClient: GrpcClient, imageStream: ImageStreamClient) {
        self.streamClient = imageStream
        let (generations, continuation) = AsyncStream.makeStream(of: UInt64.self)
        Task { [weak self] in
            let current = await grpcClient.setConnectionGenerationHandler { generation in
                continuation.yield(generation)
            }
            self?.noteConnectionGeneration(current)
            for await generation in generations {
                self?.noteConnectionGeneration(generation)
            }
        }
    }

    /// The value only goes up, whatever order updates arrive in.
    private func noteConnectionGeneration(_ generation: UInt64) {
        if generation > connectionGeneration {
            connectionGeneration = generation
        }
    }

    /// The image if it's already on the phone (memory or disk), without
    /// asking the server and without revalidating. Answers at once,
    /// online or offline; `image(for:)` waits on the image stream for a
    /// miss, which offline never answers.
    public func cachedImage(for ref: MMImageRef) async -> UIImage? {
        if ScreenshotMode.isActive { return GeneratedArt.image(for: ref) }
        return await cache.get(for: ref)?.0
    }

    /// Load an image by reference. Returns nil if not found or on error.
    public func image(for ref: MMImageRef) async -> UIImage? {
        // Screenshot mode: generated artwork only. Nothing real is
        // fetched, read from the cache, or written to it.
        if ScreenshotMode.isActive {
            return GeneratedArt.image(for: ref)
        }

        // Read before anything else: if the server or account changes
        // while this request runs, what it fetched is not stored.
        let scopeEpoch = await cache.scopeEpoch

        // 1. Check cache (memory + disk)
        if let (cached, etag) = await cache.get(for: ref) {
            // Have a cached copy — do a conditional revalidation in the background
            // (don't block the UI waiting for it)
            Task {
                await revalidate(ref: ref, etag: etag, scopeEpoch: scopeEpoch)
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
            await cache.store(ref: ref, data: data, contentType: imageData.contentType,
                              etag: imageData.etag, scopeEpoch: scopeEpoch)
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
    public func cancelPending() {
        Task {
            await streamClient.cancelStale()
        }
    }

    /// Shut down the stream.
    public func close() {
        Task {
            await streamClient.close()
        }
    }

    // MARK: - Background revalidation

    private func revalidate(ref: MMImageRef, etag: String, scopeEpoch: UInt64) async {
        let response = await streamClient.fetch(ref: ref, etag: etag)
        guard let response else { return }

        switch response.result {
        case .data(let imageData):
            // Image changed — update cache
            await cache.store(ref: ref, data: imageData.content, contentType: imageData.contentType,
                              etag: imageData.etag, scopeEpoch: scopeEpoch)
        case .notModified:
            break // Cache is still valid, nothing to do
        case .notFound:
            await cache.remove(for: ref, scopeEpoch: scopeEpoch)
        case .permissionDenied, nil:
            break
        }
    }
}

// MARK: - ImageRef convenience constructors

public extension MMImageRef {
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

    static func authorHeadshot(authorId: Int64) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .authorHeadshot
        ref.authorID = authorId
        return ref
    }

    /// Open Library cover by abstract work id (e.g. "OL45804W"). Used for
    /// unowned bibliography entries and missing series volumes.
    static func openlibraryCover(workId: String) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .openlibraryCover
        ref.openlibraryWorkID = workId
        return ref
    }

    /// Owned-artist headshot, keyed by artist_id. Used by the artists
    /// grid card and ArtistDetailView's hero — falls back to the
    /// first owned album's cover when the artist record has no real
    /// headshot (parallel to authorHeadshot's fallback strategy).
    static func artistHeadshot(artistId: Int64) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .artistHeadshot
        ref.artistID = artistId
        return ref
    }

    /// Cover Art Archive release-group cover, keyed by MBID. Used by
    /// unowned discography entries on ArtistDetailView and album
    /// wish rows. Square aspect (1:1) — album covers, not posters.
    static func coverArtArchiveReleaseGroup(releaseGroupId: String) -> MMImageRef {
        var ref = MMImageRef()
        ref.type = .caaReleaseGroup
        ref.musicbrainzReleaseGroupID = releaseGroupId
        return ref
    }
}
