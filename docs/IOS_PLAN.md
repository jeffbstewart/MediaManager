# iOS Architecture Notes

*Reference: [Issue #1](https://github.com/jeffbstewart/MediaManager/issues/1)*

This document describes the current server/client contract for the iOS app.

The older `/api/v1` REST API plan has been retired. The iOS app now uses:

- **gRPC** for application data, mutations, and admin operations
- **HTTP** for binary endpoints such as images, video streams, subtitle files, and downloads

## Current Transport Split

### gRPC

The standalone Netty gRPC server listens on its own port (default `9090`). The iOS app uses it for:

- Authentication and token refresh
- Catalog browsing, search, title detail, and landing pages
- Playback progress and chapter/skip metadata
- Profile and session management
- Wish list operations
- Live TV and camera metadata
- Admin workflows
- Download manifests and metadata

### HTTP

The main Jetty server listens on port `8080`. The iOS app uses it for:

- Posters, backdrops, headshots, and other images
- Video streaming
- Subtitle file fetches
- Binary download transfers
- Other authenticated file-style endpoints

## Source of Truth

For current implementation details, use these locations in priority order:

1. `CLAUDE.md`
2. `proto/`
3. `src/main/kotlin/net/stewart/mediamanager/grpc/`
4. `ios/MediaManager/MediaManager/Services/GrpcClient.swift`
5. `ios/MediaManager/MediaManager/Services/APIClient.swift`

## Active Proto Surface

The current gRPC contract is defined in:

- `proto/auth.proto`
- `proto/info.proto`
- `proto/catalog.proto`
- `proto/playback.proto`
- `proto/profile.proto`
- `proto/wishlist.proto`
- `proto/live.proto`
- `proto/downloads.proto`
- `proto/admin.proto`
- `proto/common.proto`
- `proto/time.proto`

## Server Implementation

The Kotlin gRPC services live in:

- `src/main/kotlin/net/stewart/mediamanager/grpc/AuthGrpcService.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/InfoGrpcService.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/CatalogGrpcService.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/PlaybackGrpcService.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/ProfileGrpcService.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/WishListGrpcService.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/LiveGrpcService.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/DownloadGrpcService.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/AdminGrpcService.kt`

Shared concerns are handled by:

- `src/main/kotlin/net/stewart/mediamanager/grpc/AuthInterceptor.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/LoggingInterceptor.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/ProtoMappers.kt`
- `src/main/kotlin/net/stewart/mediamanager/grpc/GrpcServer.kt`

## iOS Client Structure

The SwiftUI app lives in `ios/MediaManager/MediaManager/`.

Key pieces:

- `Services/GrpcClient.swift` handles gRPC connectivity and authenticated RPCs
- `Services/APIClient.swift` handles authenticated HTTP requests for binary content
- `Services/AuthManager.swift` coordinates server setup, login, token refresh, and app startup
- `Services/DownloadManager.swift` manages offline downloads and cached playback assets
- `DataModel/OnlineDataModel.swift` maps app behavior to the gRPC client

## Code Generation

The Xcode project generates Swift protobuf messages and gRPC stubs from `proto/*.proto` during the build.

Required tooling:

```bash
brew install protobuf swift-protobuf protoc-gen-grpc-swift
```

The generated Swift files are written into the app's generated sources directory by the Xcode build script.

## Downloads

Offline downloads are backed by the server's `ForMobile` transcode tier. The app uses gRPC to discover what is downloadable and HTTP to transfer the actual media files.

## Historical Note

If you encounter old references to `/api/v1` in conversation history, design notes, or commit messages, treat them as archival context only. They do not describe the current iOS/server integration.
