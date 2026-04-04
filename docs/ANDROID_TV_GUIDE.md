<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Android TV / Google TV Guide

Stream your media collection on Android TV and Google TV devices with a native Compose-based app.

---

## Overview

Media Manager includes a native Android TV app built with Jetpack Compose for TV. It connects to your server via gRPC (metadata, images) and HTTP (video streaming), providing a full browsing and playback experience optimized for D-pad/remote control navigation.

**What you get:**
- Multi-account support — "Who's watching?" picker for households with multiple users
- Home screen with browsable poster carousels (Resume Playing, Recently Added, etc.)
- Movies, TV Shows, and Family Videos grids with sorting (popularity, name, year, recent)
- Title detail with backdrop hero, cast, genres, tags, play button
- Episode picker for TV shows with resume progress bars
- Full video playback with ExoPlayer — subtitle support, skip intro/credits, auto-next-episode
- Playback progress synced across all devices (TV, browser, Roku, iOS)
- Wish list browsing, voting, and TMDB search to add wishes
- Live camera streaming with go2rtc warm-up
- Live TV channel list with HDHomeRun integration
- Profile and session management
- Search with categorized results (movies, TV, actors, collections, tags, genres)
- TLS support for secure connections through HAProxy

---

## Prerequisites

- A **Google TV** or **Android TV** device (or the Android TV emulator)
- **Android SDK** with API 36+ platform and build tools
- **JDK 21+** (Corretto 25 recommended, matching the server)
- **Gradle 9.1+** (the project bundles 9.3.1 via the wrapper)
- Your Media Manager server running and accessible from the TV's network

---

## Building

### Debug APK

```bash
./lifecycle/android-tv-build.sh
```

Or manually:

```bash
cd android-tv && ./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Signed Release APK

First-time setup:

1. Generate a signing keystore (one-time):
   ```bash
   keytool -genkeypair -v -keystore secrets/android-tv-keystore.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias mediamanager-tv
   ```

2. Copy the signing config template and fill in your passwords:
   ```bash
   cp secrets/example.android-tv-signing.properties secrets/android-tv-signing.properties
   ```

3. Build:
   ```bash
   ./lifecycle/android-tv-release.sh
   # APK at: android-tv/app/build/outputs/apk/release/app-release.apk
   ```

Both the keystore (`.jks`) and signing properties are in `secrets/` (gitignored).

---

## Installing

### Emulator

```bash
./lifecycle/android-tv-deploy.sh
```

This builds, installs, and launches on the connected emulator or device.

### Physical Device (Google TV / Android TV)

Enable Developer Mode on your TV:
1. **Settings > System > About > Android TV OS Build** (tap 7 times)
2. **Settings > System > Developer options > USB debugging: ON**
3. For wireless: **ADB over network** and note the IP:port

Connect via ADB:
```bash
adb connect <TV-IP>:5555
./lifecycle/android-tv-deploy.sh
```

Or install directly:
```bash
adb install -r android-tv/app/build/outputs/apk/release/app-release.apk
```

---

## First Launch

1. **Server Setup** — enter your server hostname and port. TLS is enabled by default (port 8443). Toggle TLS off for direct LAN connections (gRPC 9090, HTTP 8080).

2. **Login** — enter your Media Manager username and password. The app stores tokens locally so you don't need to log in again.

3. **Home** — carousels appear with poster images loaded via the gRPC bidirectional streaming image service.

### Multiple Accounts

The app supports multiple user accounts — standard for TV apps in shared households:

- After the first login, additional users can log in via the **profile button** (top-right) > **account picker** > **Add Account**
- On launch, if multiple accounts exist, the **"Who's watching?"** picker appears
- Each account has independent tokens and playback progress
- Single-account households skip the picker automatically

---

## Navigation

The home screen top bar provides navigation:

```
Media Manager [Movies] [TV Shows] [Family] [Wishes] [Cameras] [Live TV] [Search]  [Profile] [● user]
```

| Button | Destination |
|--------|-------------|
| Movies | Playable movies grid with sort controls |
| TV Shows | Playable TV shows grid |
| Family | Family/personal videos grid |
| Wishes | Wish list with vote/add, TMDB search |
| Cameras | Live camera streams via go2rtc |
| Live TV | HDHomeRun channels with warm-up |
| Search | Full-text search across all content types |
| Profile | User info, rating ceiling, active sessions |
| User avatar | Account picker / switch user |

Every non-home screen has a **Back** button. On physical remotes, the Back button also navigates backward.

---

## Video Playback

The player uses AndroidX Media3 (ExoPlayer) for video playback:

- **Video format:** MP4 via HTTP with Range request support
- **Authentication:** Bearer JWT token on all HTTP requests
- **Subtitles:** WebVTT loaded automatically from the server
- **Resume:** Playback resumes from the last saved position (synced across all devices)
- **Progress reporting:** Position saved every 10 seconds and on exit
- **Skip segments:** "Skip Intro" / "Skip Credits" / "Skip Recap" button overlays appear automatically when playback enters a tagged segment
- **Next episode:** For TV shows, a "Next Episode" button appears in the last 30 seconds; auto-advances when playback ends

---

## Architecture

### Communication

| Layer | Protocol | Purpose |
|-------|----------|---------|
| **Metadata** | gRPC (protobuf) | Auth, catalog, search, playback progress, chapters, wish list, cameras, live TV, profile |
| **Images** | gRPC bidi streaming | All poster, backdrop, headshot, and collection images via `ImageService.StreamImages` |
| **Video** | HTTP (MP4) | Video streaming with Range support, Bearer auth |
| **Subtitles** | HTTP (WebVTT) | Subtitle files for the player |
| **Live streams** | HTTP (HLS) | Camera and live TV HLS streams |

### Image Loading

Images are **not** loaded over HTTP. The app uses a bidirectional gRPC streaming service (`ImageService.StreamImages`) with:

- **Request/response correlation** via monotonic request IDs
- **Cancel-stale watermark** to avoid wasted bandwidth when scrolling
- **LRU disk cache** (500 entries) + **memory cache** (200 bitmaps)
- **ETag-based conditional fetch** — stale-while-revalidate pattern
- **Typed ImageRef** objects (`posterRef`, `backdropRef`, `headshotRef`) instead of URL construction

### Token Management

- Tokens stored per-account in SharedPreferences
- Automatic token refresh: when a gRPC call returns `UNAUTHENTICATED`, the `withAuth` wrapper calls `AuthService.Refresh` and retries once
- TLS via `useTransportSecurity()` on the OkHttp gRPC channel

### Project Structure

```
android-tv/
├── app/
│   ├── build.gradle.kts              # AGP 8.9, protobuf codegen, signing
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/net/stewart/mediamanager/tv/
│       │   ├── MainActivity.kt        # Entry point, ImageProvider setup
│       │   ├── MediaManagerApp.kt     # Navigation graph (Compose Navigation)
│       │   ├── auth/                  # AuthManager, login, setup, account picker
│       │   ├── grpc/                  # GrpcClient, auth interceptor, token refresh
│       │   ├── image/                 # ImageStreamClient, ImageDiskCache, ImageProvider, CachedImage
│       │   ├── home/                  # Home feed with carousels
│       │   ├── catalog/               # Title grid, detail, seasons, episodes, actor, browse screens
│       │   ├── search/                # Search with saved query
│       │   ├── player/                # ExoPlayer video player
│       │   ├── wishlist/              # Wish list + TMDB search
│       │   ├── live/                  # Cameras + live TV
│       │   ├── profile/               # Profile + sessions
│       │   └── ui/                    # Theme, PosterCard, TvOutlinedTextField, CachedImage
│       ├── proto/                     # Symlinked from repo root proto/ (shared with server + iOS)
│       └── res/                       # Strings, themes, banner drawable
├── build.gradle.kts                   # Root build file
├── settings.gradle.kts
├── gradle.properties
└── gradle/
    ├── libs.versions.toml             # Version catalog
    └── wrapper/                       # Gradle 9.3.1
```

Proto files live in the repo root `proto/` directory and are shared by the server, iOS app, and Android TV app. The Android build references them via `srcDir("../../proto")` in the protobuf plugin config.

---

## Debugging

Stream filtered logcat:

```bash
./lifecycle/android-tv-debug.sh
# Logs to data/android-tv-debug.log
```

Or manually:

```bash
adb logcat net.stewart.mediamanager.tv:V GrpcClient:V ExoPlayer:W AndroidRuntime:E *:S
```

---

## Emulator Setup

Create a TV AVD in Android Studio:
1. **Device Manager > Create Virtual Device**
2. Category: **TV** > select **Television (1080p)**
3. System Image: **API 36, Google TV, x86_64**
4. Name: `Television_1080p`

The emulator maps keyboard keys to the remote:

| Key | Remote Button |
|-----|---------------|
| Arrow keys | D-pad |
| Enter | Select / OK |
| Escape | Back |
| Home | Home |

**Note:** SSDP auto-discovery does not work in the emulator (isolated NAT network). Use manual server entry.

---

## Known Limitations

- **Thumbnail scrubbing** — not implemented; ExoPlayer's default seek bar doesn't support VTT sprite sheets without a custom renderer
- **Offline downloads** — not implemented in the TV app (TV is always on the home network)
- **Voice search** — search input uses the on-screen keyboard; Android TV system-level voice search integration is not wired

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="ROKU_GUIDE.md">Roku Guide</a> &bull;
  <a href="ANDROID_TV_PLAN.md">Android TV Plan</a>
</p>
