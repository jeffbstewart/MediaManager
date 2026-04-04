<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Android TV App — Project Plan

*Created April 3, 2026*

---

## Overview

A native app for Google TV (Android TV OS), built with Kotlin and Jetpack Compose for TV. The app exposes all non-admin playback and browsing features, modeled after the iOS app's view structure but adapted for TV navigation (D-pad, remote control, no touchscreen). Communicates with the server via the existing gRPC API (same `.proto` definitions used by the iOS app) and HTTP for binary content (images, video streams).

**Target platform:** Google TV (which runs on Android TV OS). All Android TV apps are fully compatible with Google TV — same SDK, same APIs. The Compose for TV libraries handle both.

**Scope:** Non-admin functionality only — browsing, playback, wish list, cameras, live TV, profile/sessions. Admin views are excluded (use the web UI or iOS app for admin tasks).

---

## Architecture

### Transport Layer (Shared with iOS)

| Transport | Port | Purpose |
|-----------|------|---------|
| gRPC | 9090 | All metadata: auth, catalog, search, playback progress, chapters, wish list, cameras, live TV, profile |
| HTTP | 8080 | Binary content: poster/backdrop/headshot images, video streaming (HLS/MP4), subtitle files |

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI Framework | Jetpack Compose for TV (`androidx.tv:tv-foundation`, `androidx.tv:tv-material`) |
| Navigation | Compose Navigation |
| gRPC Client | `grpc-kotlin` + `grpc-okhttp` (generated from `proto/` definitions) |
| HTTP Client | OkHttp (images, video stream URLs) |
| Image Loading | Coil (Compose-native, supports auth headers) |
| Server Discovery | SSDP multicast via [Lighthouse](https://github.com/ivanempire/lighthouse) (Kotlin coroutines) |
| Video Player | AndroidX Media3 (ExoPlayer) with HLS support |
| Async | Kotlin Coroutines + Flow (natural fit for gRPC streaming RPCs) |
| DI | Manual injection or Hilt |
| Min SDK | API 21 (Android 5.0 — covers all Android TV devices) |
| Target SDK | API 35 (Android 15) |

### Code Sharing

The `.proto` files in `proto/` generate Kotlin data classes and gRPC stubs at build time via the `protobuf-gradle-plugin`. This is the same API surface the iOS app uses — no new server work needed.

---

## View Structure

Modeled after the iOS app, adapted for TV D-pad navigation:

### Authentication
| View | gRPC RPCs | Notes |
|------|-----------|-------|
| **Server Setup** | `InfoService.Discover` | SSDP auto-discovery (same as iOS — server's `SsdpResponder` already broadcasts on `239.255.255.250:1900`). Falls back to manual IP:port entry. Requires `android.permission.INTERNET` and local network access for multicast. |
| **Login** | `AuthService.Login` | Username + password via on-screen keyboard. Stores tokens in EncryptedSharedPreferences. |

### Main Navigation (Tab/Sidebar)
| View | gRPC RPCs | Notes |
|------|-----------|-------|
| **Home** | `CatalogService.HomeFeed` | Carousels: Continue Watching, Recently Added, Popular. Hero banner. Focus-driven browsing. |
| **Movies** | `CatalogService.ListTitles` (type=MOVIE) | Grid with poster cards, D-pad navigation. Sort by popularity/name/year/recent. |
| **TV Shows** | `CatalogService.ListTitles` (type=TV) | Same grid, filtered to TV. |
| **Collections** | `CatalogService.ListCollections` | Collection cards with owned/total count. |
| **Tags** | `CatalogService.ListTags` | Tag chips or list, navigate to tag detail. |
| **Wish List** | `WishListService.ListWishes`, `SearchTmdb` | Browse wishes, vote, add new via TMDB search. |
| **Cameras** | `LiveService.ListCameras` | Camera grid with live previews. |
| **Live TV** | `LiveService.ListTvChannels` | Channel list with number, name, quality indicator. |
| **Search** | `CatalogService.Search` | Full-text search with categorized results (movies, TV, actors, collections, tags). Voice search via Android TV voice input. |

### Detail Views
| View | gRPC RPCs | Notes |
|------|-----------|-------|
| **Title Detail** | `CatalogService.GetTitleDetail` | Backdrop, poster, description, cast, genres, tags, play button. Favorite/hidden toggle. |
| **Seasons** | `CatalogService.ListSeasons` | Season picker for TV shows. |
| **Episodes** | `CatalogService.ListEpisodes` | Episode list with thumbnails, resume indicators. |
| **Actor** | `CatalogService.GetActorDetail` | Bio, filmography (owned + other works with wish status). |
| **Collection Detail** | `CatalogService.GetCollectionDetail` | Collection members, owned vs. unowned. |
| **Tag Detail** | `CatalogService.GetTagDetail` | Titles with this tag. |
| **Genre Detail** | `CatalogService.GetGenreDetail` | Titles in this genre. |

### Playback
| View | gRPC RPCs | HTTP | Notes |
|------|-----------|------|-------|
| **Video Player** | `PlaybackService.GetProgress`, `ReportProgress`, `GetChapters` | MP4 stream URL | Media3/ExoPlayer with HLS support, chapter seeking, skip segment overlay (intro/credits/recap), subtitle rendering (SRT via HTTP), resume from last position. |
| **Camera Player** | `LiveService.WarmUpStream` | HLS stream URL | Live camera stream with warm-up. |
| **Live TV Player** | `LiveService.WarmUpStream` | HLS stream URL | Live TV with channel info overlay. |

### Profile
| View | gRPC RPCs | Notes |
|------|-----------|-------|
| **Profile** | `ProfileService.GetProfile`, `UpdateTvQuality` | View user info, adjust live TV quality. |
| **Sessions** | `ProfileService.ListSessions`, `DeleteSession` | View/revoke active sessions. |

---

## Project Structure

```
android-tv/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/net/stewart/mediamanager/tv/
│       │   ├── MainActivity.kt
│       │   ├── MediaManagerApp.kt              # Compose entry point + navigation
│       │   ├── auth/
│       │   │   ├── AuthManager.kt              # Token storage, refresh, login state
│       │   │   ├── ServerSetupScreen.kt
│       │   │   └── LoginScreen.kt
│       │   ├── grpc/
│       │   │   └── GrpcClient.kt               # Channel management, auth interceptor
│       │   ├── home/
│       │   │   └── HomeScreen.kt               # Carousels, hero banner
│       │   ├── catalog/
│       │   │   ├── TitleGridScreen.kt           # Movies / TV Shows grid
│       │   │   ├── TitleDetailScreen.kt
│       │   │   ├── SeasonsScreen.kt
│       │   │   ├── EpisodesScreen.kt
│       │   │   ├── ActorScreen.kt
│       │   │   ├── CollectionsScreen.kt
│       │   │   ├── CollectionDetailScreen.kt
│       │   │   ├── TagsScreen.kt
│       │   │   ├── TagDetailScreen.kt
│       │   │   └── GenreDetailScreen.kt
│       │   ├── search/
│       │   │   └── SearchScreen.kt
│       │   ├── player/
│       │   │   ├── VideoPlayerScreen.kt         # Media3 + chapters + skip segments
│       │   │   ├── CameraPlayerScreen.kt
│       │   │   └── LiveTvPlayerScreen.kt
│       │   ├── wishlist/
│       │   │   └── WishListScreen.kt
│       │   ├── live/
│       │   │   ├── CamerasScreen.kt
│       │   │   └── LiveTvScreen.kt
│       │   ├── profile/
│       │   │   ├── ProfileScreen.kt
│       │   │   └── SessionsScreen.kt
│       │   └── ui/
│       │       ├── theme/Theme.kt
│       │       ├── components/PosterCard.kt
│       │       ├── components/Carousel.kt
│       │       └── components/FocusableCard.kt
│       └── res/
│           ├── values/strings.xml
│           └── drawable/                        # App icon, banner
├── build.gradle.kts                             # Root build file
├── settings.gradle.kts
└── gradle/
    └── libs.versions.toml                       # Version catalog
```

---

## Toolchain Setup

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Android Studio | Ladybug (2025.1+) or later | IDE with Compose preview, TV emulator |
| JDK | 21+ (Corretto 25 recommended) | Matches server JDK |
| Android SDK | API 35 | Target SDK |
| Android SDK Build Tools | 35.0.0 | APK building |
| Android TV System Image | API 35, Google TV, x86_64 | Emulator |

### First-Time Setup

```bash
# 1. Install Android Studio (includes SDK Manager)
#    Download from https://developer.android.com/studio

# 2. Install SDK components via Android Studio SDK Manager:
#    - Android 15 (API 35) SDK Platform
#    - Android TV (API 35) system image (x86_64, Google TV)
#    - Android SDK Build-Tools 35.0.0

# 3. Create a TV emulator AVD:
#    Android Studio > Device Manager > Create Virtual Device
#    Category: TV > select "Television (1080p)"
#    System Image: API 35, Google TV, x86_64
#    Name: "MediaManager TV"

# 4. Verify protoc is available (gradle plugin handles this,
#    but protoc must be compatible with the host OS)
```

### Environment Variables

Add to your shell profile:
```bash
export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"   # Windows
export PATH="$PATH:$ANDROID_HOME/platform-tools"         # adb
```

---

## Emulator & Device Testing

### Android TV Emulator

The Android Studio emulator provides a Google TV device with D-pad navigation. Create an AVD with the TV profile:

- **Hardware:** Television (1080p) or Television (4K)
- **System Image:** API 35, Google TV, x86_64
- **RAM:** 2048 MB recommended
- **Performance:** Use x86_64 image with hardware acceleration (HAXM/Hyper-V)

The emulator supports D-pad input via keyboard arrow keys, Enter for Select, Escape for Back.

### Physical Device (Google TV)

Enable Developer Mode on your Google TV:
1. Settings > System > About > Android TV OS Build (tap 7 times)
2. Settings > System > Developer options > USB debugging: ON
3. Settings > System > Developer options > ADB over network: note the IP:port

Connect via ADB:
```bash
adb connect <TV-IP>:5555
adb devices                    # Verify connection
adb install app.apk            # Install APK
adb logcat -s MediaManager     # Stream logs
```

---

## Lifecycle Scripts

Create these in `lifecycle/`, mirroring the Roku/iOS patterns:

### `android-tv-build.sh`
Build the debug APK:
```bash
cd android-tv && ./gradlew assembleDebug
```

### `android-tv-deploy.sh`
Build and install to connected device/emulator:
```bash
cd android-tv && ./gradlew installDebug
# Or for a specific device:
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
```

### `android-tv-debug.sh`
Stream logcat output filtered to MediaManager:
```bash
adb logcat -s MediaManager:V GrpcClient:V ExoPlayer:W *:S | tee data/android-tv-debug.log
```

### `android-tv-release.sh`
Build a signed release APK for sideloading:
```bash
cd android-tv && ./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/app-release.apk
```

---

## Implementation Phases

### Phase 1: Skeleton + Auth + Home (Week 1-2)
- Project scaffolding: Gradle, dependencies, proto codegen
- gRPC client with OkHttp transport, auth interceptor (Bearer token)
- SSDP server discovery (multicast on `239.255.255.250:1900`, same protocol as iOS) with manual fallback
- Server setup screen (auto-discovered servers listed, or enter IP:port manually)
- Login screen with on-screen keyboard
- Token persistence in EncryptedSharedPreferences
- Home screen with carousels from `HomeFeed` RPC
- Poster image loading via Coil with auth headers
- Basic TV theme (dark, focus states)

**Milestone:** App launches on emulator, logs in, shows home feed with poster images.

### Phase 2: Catalog Browsing (Week 2-3)
- Movies/TV grid with pagination and sorting
- Title detail screen (backdrop, metadata, cast, genres, tags)
- Seasons + Episodes views for TV shows
- Actor, Collection, Tag, Genre detail screens
- Search with voice input support

**Milestone:** Full catalog browsing with all landing pages.

### Phase 3: Video Playback (Week 3-4)
- Media3/ExoPlayer integration for MP4 and HLS
- Playback progress sync (report + resume)
- Chapter list with seek-to-chapter
- Skip segment detection and overlay (Skip Intro / Skip Credits)
- Subtitle loading and rendering (SRT from HTTP)
- Thumbnail scrubbing (if BIF or VTT sprite sheets available)

**Milestone:** Full video playback with chapters, skip segments, subtitles, and progress sync.

### Phase 4: Wish List + Live Content (Week 4-5)
- Wish list view: browse, vote, dismiss
- Add wish via TMDB search
- Camera list + live stream player
- Live TV channel list + player with warm-up
- Profile + sessions management

**Milestone:** All non-admin features complete.

### Phase 5: Polish + Sideload Deployment (Week 5-6)
- Focus management audit (every screen navigable by D-pad)
- Loading states, error handling, empty states
- App icon and banner for Android TV launcher
- Signed release APK
- Lifecycle scripts (build, deploy, debug)
- Test on physical Google TV device
- Documentation in `docs/ANDROID_TV_GUIDE.md`

**Milestone:** Sideloadable release APK tested on physical hardware.

---

## Key Technical Decisions

### Compose for TV vs. Leanback
**Decision: Compose for TV.** Leanback is the legacy framework (XML-based, Java-oriented). Compose for TV is Google's current recommendation, uses Kotlin-native declarative UI, and aligns with our Kotlin-everywhere approach. The `tv-foundation` and `tv-material` libraries provide TV-optimized focus handling, content cards, and immersive layouts.

### gRPC Transport
**Decision: `grpc-okhttp`.** The standard gRPC Android transport. Lighter than `grpc-netty` and designed for mobile/embedded. Generated Kotlin stubs with suspend functions and Flow for streaming RPCs.

### Video Player
**Decision: AndroidX Media3 (ExoPlayer).** The standard Android video player. Native HLS support, subtitle rendering, and extensible for custom UI overlays (chapters, skip segments). Compose for TV has built-in media playback integration.

### SSDP Discovery + Standard Login
The server's existing `SsdpResponder` broadcasts on `239.255.255.250:1900`. The app discovers the server automatically (same as iOS's `SsdpDiscovery.swift`), then authenticates with username/password login and JWT tokens. No QR code pairing needed (unlike Roku, which uses pairing because BrightScript can't do HTTPS). No new server-side infrastructure needed — SSDP and auth are already running.

### No Offline Downloads (Initial)
The iOS app supports offline downloads via the `DownloadService` gRPC RPCs. This can be added later but is lower priority for a TV that's always on the home network.

---

## Dependencies on Server

**None.** The existing gRPC API (9 non-admin services, ~50 RPCs) and HTTP endpoints fully cover all planned features. No new server code, proto changes, or migrations are needed.

The server already handles:
- Authentication (JWT access + refresh tokens)
- Content rating filtering per user
- Playback progress sync across devices
- Chapter and skip segment metadata
- Live TV stream management
- Camera stream relay via go2rtc

---

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| Compose for TV is relatively new | Google's tv-samples repo has reference implementations; JetStream sample app demonstrates all major patterns |
| D-pad focus management complexity | Start with simple linear navigation; use `tv-foundation` focus system; test early and often on emulator |
| ExoPlayer + custom skip segment UI | iOS already proves the concept; ExoPlayer has a well-documented overlay API |
| Google restricting Android TV sideloading | No announcements yet for TV; phone restrictions (April 2026) don't apply to Android TV OS |
| gRPC on Android TV performance | `grpc-okhttp` is production-proven on Android; same binary as used by thousands of Android gRPC apps |

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="ANDROID_TV_GUIDE.md">Android TV Guide</a> &bull;
  <a href="IOS_PLAN.md">iOS Architecture Notes</a> &bull;
  <a href="ROKU_GUIDE.md">Roku Guide</a>
</p>
