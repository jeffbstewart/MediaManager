# iOS Architecture Notes

*Reference: [Issue #1](https://github.com/jeffbstewart/MediaManager/issues/1)*

This document describes the current server/client contract for the iOS app.

The older `/api/v1` REST API plan has been retired. The iOS app uses:

- **gRPC** for application data, mutations, and admin operations
- **HTTP** for binary endpoints such as images, video streams, subtitle files, and downloads

## Current Transport

The server runs a single Armeria process that hosts both gRPC and HTTP on the same port. There is no longer a separate Jetty / Netty split.

| Port | Default | Purpose |
|------|---------|---------|
| Main | `9090` | gRPC (HTTP/2, native protobuf for iOS / Android TV / Roku; gRPC-Web for the browser SPA) + HTTP for images, video streams, subtitles, downloads, and the Angular web app's static assets. Also serves `/health` for HAProxy checks. |
| Internal | `8081` | `/health` + `/metrics` (Prometheus). LAN-only, not port-forwarded. |

The iOS app talks to the main port for everything — the gRPC stack handles HTTP/2 multiplexing, so unary RPCs, server-streaming RPCs, and the HTTP image / video traffic share the same TCP connection through HAProxy on the way in.

### gRPC usage

- Authentication and token refresh
- Catalog browsing, search, title detail, landing pages
- Playback / reading / listening progress + chapter & skip metadata
- Profile, sessions, biometric pairing
- Wish list operations
- Live TV and camera metadata
- Admin workflows
- Download manifests + chunked file transfer

### HTTP usage

- Posters, backdrops, headshots (also reachable via `ImageGrpcService` for streaming, but the still-image path is HTTP)
- Video streaming (HLS / progressive MP4)
- Subtitle file fetches
- Other authenticated file-style endpoints

## Source of Truth

For current implementation details, use these locations in priority order:

1. `CLAUDE.md`
2. `proto/`
3. `src/main/kotlin/net/stewart/mediamanager/grpc/`
4. `ios/MediaManager/MediaManager/Services/GrpcClient.swift`
5. `ios/MediaManager/MediaManager/Services/APIClient.swift`

## Active Proto Surface

The current gRPC contract is defined in `proto/`:

**iOS-facing:**

- `auth.proto`, `info.proto`, `catalog.proto`, `playback.proto`, `profile.proto`, `wishlist.proto`, `live.proto`, `downloads.proto`, `admin.proto`
- `images.proto` — bidi image streaming used by the iOS image cache
- `observability.proto` — client log forwarding into Binnacle
- `playlist.proto`, `artist.proto`, `radio.proto`, `recommendations.proto` — audio surfaces
- `common.proto`, `time.proto` — shared message types
- `download_meta.proto` — extended per-download metadata used by the offline browse surface

**Not iOS-facing** (server-internal):

- `buddy.proto` — Transcode Buddy ↔ server protocol
- `madmom.proto` — Madmom audio analysis sidecar

## Server Implementation

The Kotlin gRPC services live in `src/main/kotlin/net/stewart/mediamanager/grpc/`:

- `AuthGrpcService.kt`, `InfoGrpcService.kt`, `CatalogGrpcService.kt`, `PlaybackGrpcService.kt`
- `ProfileGrpcService.kt`, `WishListGrpcService.kt`, `LiveGrpcService.kt`
- `DownloadGrpcService.kt`, `AdminGrpcService.kt`
- `ImageGrpcService.kt`, `ObservabilityGrpcService.kt`
- `ArtistGrpcService.kt`, `RadioGrpcService.kt`, `RecommendationGrpcService.kt`, `PlaylistGrpcService.kt`
- `BuddyGrpcService.kt` (server-internal)

Shared concerns:

- `ArmeriaServer.kt` — registers all services and runs the HTTP/2 server
- `AuthInterceptor.kt` — JWT validation per-RPC
- `LoggingInterceptor.kt` — structured access logs to Binnacle
- `GrpcRequestContext.kt` — per-call user / device context
- `ProtoMappers.kt` — DB row ↔ proto message mappers

## iOS Client Structure

The SwiftUI app lives in `ios/MediaManager/MediaManager/`.

**Transport + auth:**

- `Services/GrpcClient.swift` — gRPC connectivity, single chokepoint for all RPCs (`requireClient()` enforces the offline gate)
- `Services/APIClient.swift` — authenticated HTTP for binary content
- `Services/AuthManager.swift` — server discovery, login, token refresh, biometric gate, sign-out
- `Services/KeychainService.swift` — credential storage

**Data model layer:**

- `DataModel/OnlineDataModel.swift` — primary, routes to the offline delegate when `isOnline == false`
- `DataModel/OfflineDataModel.swift` — reads exclusively from local caches
- `DataModel/PlaybackDataModel.swift`, `DataModel/DataModel.swift` — protocols

**Offline + progress:**

- `Services/DownloadManager.swift` — video transcode downloads, listening-progress queue
- `Services/BookCacheManager.swift` — ebook downloads, cached `MMTitleDetail` mirrors
- `Services/AudioCacheManager.swift` — album track downloads
- `Services/LocalProgressStore.swift` — per-device shadow of playback / reading positions, survives the online→offline transition
- `Services/ReadingProgressQueue.swift` + `Services/ProgressFlusher.swift` — reader → server flush queue (mirrors to `LocalProgressStore` so flushed entries don't disappear)

**Media playback:**

- `Services/AudioPlayerManager.swift` — single source of truth for the music queue + system Now Playing
- `Views/CustomPlayerView.swift` — video playback (subtitles, skip segments, thumbnail scrubber)
- `Views/BookReaderView.swift` — epub.js WKWebView wrapper

**CarPlay:**

- `Services/CarPlaySceneDelegate.swift`, `Services/CarPlayBrowseController.swift` — independent UIScene, reads through `AppServices.shared.dataModel` so the offline routing applies the same way

## Code Generation

The Xcode project's `Generate Protos` build phase runs `protoc` against `proto/*.proto` on every build, producing Swift message types and gRPC-Swift v2 stubs into `MediaManager/Generated/`.

Required local tooling on the build machine:

```bash
brew install protobuf swift-protobuf
```

The grpc-swift-2 plugin (`protoc-gen-grpc-swift-2`) is invoked via the `--grpc-swift-2_out` flag and is provided by the `grpc-swift-protobuf` Swift package that the Xcode project depends on — no separate brew install needed for that side. If `protoc` itself isn't on `PATH`, the build phase logs a warning and skips generation (existing generated files keep working until `proto/*.proto` changes).

## Downloads

Offline downloads are backed by the server's `ForMobile` transcode tier. The app uses gRPC to discover what is downloadable and HTTP to transfer the actual media files.

### Book downloads

A matching pair of gRPC methods on `DownloadService` handles ebooks — same
streaming chunk shape as video, different source lookup.

| RPC | Request | Response |
|---|---|---|
| `GetBookManifest` | `{media_item_id}` | `{media_item_id, title_id, title_name, file_size_bytes, media_format, suggested_filename}` |
| `DownloadBookFile` | `{media_item_id, offset, length}` | `stream DownloadChunk` (same as video) |

**Client flow**:

1. Call `GetBookManifest` with the `MediaItem.id` returned by
   `CatalogService.GetTitleDetail.readable_editions[*]`. Use the
   returned `file_size_bytes` to show progress and
   `suggested_filename` to store on disk.
2. Call `DownloadBookFile` with `offset = 0, length = 0` for a full
   download, or `offset = <already-received bytes>` to resume.
3. Reassemble `DownloadChunk.data` in order — server guarantees
   monotonically-increasing `offset` values. `is_last = true` on the
   terminal chunk; `total_size` echoes the manifest size on every
   chunk so clients can validate.
4. On disk the file is ready to hand to the reader — `BookReaderView`
   runs epub.js inside a `WKWebView` for `EBOOK_EPUB` and renders
   PDFs via `PDFKit` for `EBOOK_PDF`. The `media_format` string
   matches what the catalog serves, so a simple dispatch picks the
   right viewer.

**Error codes**:

- `NOT_FOUND` — unknown media_item_id or backing file missing on disk.
- `FAILED_PRECONDITION` — the media_item isn't a digital edition
  (no `file_path`, or `media_format` isn't in the book set).
- `PERMISSION_DENIED` — caller's rating ceiling blocks the linked title.

## Historical Note

If you encounter old references to `/api/v1` in conversation history, design notes, or commit messages, treat them as archival context only. They do not describe the current iOS/server integration.
