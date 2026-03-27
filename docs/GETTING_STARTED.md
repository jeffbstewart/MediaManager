<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Getting Started

This guide walks you through setting up your own Media Manager instance.

## Prerequisites

### TMDB API Key (required)

Media Manager uses [TMDB](https://www.themoviedb.org/) for poster images, cast data, descriptions, and popularity sorting. You need a free API key:

1. [Create a TMDB account](https://www.themoviedb.org/signup)
2. Go to [API settings](https://www.themoviedb.org/settings/api)
3. Copy **"API Key (v3 auth)"**

### Media Files (optional)

If you have ripped media files on a NAS or local drive, Media Manager can discover and stream them. Any directory structure works &mdash; the scanner auto-classifies folders by structure:

- **Flat directories** (files directly inside) &rarr; treated as movies
- **Nested directories** (files inside subdirectories) &rarr; treated as TV shows
- `SxxExx` patterns in filenames override to TV regardless of depth

The media directory must be accessible as a mounted filesystem path from wherever the server runs, and the app needs **read/write access** &mdash; it writes browser-optimized transcoded copies into a `ForBrowser/` subdirectory alongside your source files.

---

## Docker Deployment (recommended)

The easiest way to run Media Manager. No JDK, no build tools &mdash; just Docker.

### 1. Get the docker-compose file

Copy the [`docker-compose.yml`](https://github.com/jeffbstewart/MediaManager/blob/main/docker-compose.yml) from the repository. You can paste it directly into Portainer's stack editor, or save it to a local file and edit it there.

### 2. Configure

Edit the environment variables and volume paths:

**Required environment variables:**

| Variable | Purpose |
|----------|---------|
| `H2_PASSWORD` | Database password. Pick any strong password. |
| `H2_FILE_PASSWORD` | Encryption-at-rest password. Pick a different strong password. The database file is AES-encrypted with this key. |
| `TMDB_API_KEY` | Your TMDB API key from above. |

**Required volume mounts:**

| Mount | Purpose |
|-------|---------|
| Cache volume &rarr; `/cache` | Persistent storage for the H2 database, poster cache, and backups. Must survive container recreation. |
| Media volume &rarr; `/media` | Your ripped media files. **Must be read/write** &mdash; the app writes browser-optimized and media-appliance-optimized transcoded copies into a `ForBrowser/` subdirectory alongside your source files. |

**Other settings to review:**

- **`user`** &mdash; Set to your UID:GID so the container can read/write volumes. Find yours with `id -u` and `id -g` via SSH on your NAS, or use `1000:1000` on a typical Linux system.
- **`MM_BEHIND_PROXY`** &mdash; Set to `true` if behind a reverse proxy (nginx, traefik) so the app trusts `X-Forwarded-*` headers.

### 3. Launch

```bash
docker compose up -d
```

Or click **Deploy the stack** in Portainer.

### 4. Create your admin account

Open **http://your-host:8080**. On first launch, you're redirected to the setup page. Create your admin account (username, display name, password). This account has full access to all features.

### Auto-Updates with Watchtower (optional)

The `docker-compose.yml` includes a commented-out [Watchtower](https://containrrr.dev/watchtower/) service. Uncomment it to automatically pull new images and restart the container when updates are published.

### Networking Note

The default `network_mode: host` is required for Roku SSDP device discovery (UDP multicast). If you don't use a Roku, you can switch to bridge networking and uncomment the `ports` section instead.

---

## What's Next?

Once the server is running and you've created your admin account:

1. **Scan your first disc** &mdash; Open the sidebar &rarr; **Add Item**, enter a UPC barcode (or use the camera scanner). The system looks up the product, creates a catalog entry, and enriches it with TMDB data within seconds. You can also search TMDB directly or link unmatched NAS files from the same page.

2. **Browse your catalog** &mdash; Open **Catalog** from the sidebar. Titles appear with poster art as they're enriched.

3. **Connect your media** &mdash; If you have transcoded files, set the NAS root path in **Settings** and click **Scan NAS** under **Transcodes &rarr; Status**. Files are auto-matched to catalog titles.

4. **Watch something** &mdash; Find a title with a linked transcode and hit the play button. MP4 files play immediately; MKV files play after the background transcoder processes them.

   > **Tip:** Transcoding on a NAS CPU can be slow (hours per file for HEVC sources). If you have a large backlog, consider setting up a [Transcode Buddy](TRANSCODE_BUDDY.md) on a machine with an NVIDIA GPU for 5-20x faster transcoding.

5. **Invite users** &mdash; Open **Users** from the sidebar to create accounts for household members. Set content rating ceilings for younger viewers.

6. **Set up Roku** &mdash; See the [Roku Setup Guide](ROKU_GUIDE.md) to stream on your TV.

7. **Generate subtitles** &mdash; See [Generating Subtitles](GENERATING_SUBTITLES.md) to set up Whisper AI subtitle generation.

---

## iOS App Development

The iOS app communicates with the server via gRPC. Building the app requires protobuf tooling.

### Prerequisites

```bash
brew install protobuf swift-protobuf protoc-gen-grpc-swift
```

This installs:
- `protoc` — Protocol Buffer compiler
- `protoc-gen-swift` — Swift message code generator
- `protoc-gen-grpc-swift-2` — Swift gRPC service stub generator (v2)

### Configuration

Two config files are required before building:

**1. Developer.xcconfig** (code signing):
```bash
cp ios/MediaManager/Developer.xcconfig.example ios/MediaManager/Developer.xcconfig
# Edit and set your Apple Developer Team ID
```

**2. Branding.xcconfig** (legal and identity):
```bash
cp ios/MediaManager/Branding.xcconfig.example ios/MediaManager/Branding.xcconfig
# Edit and set your privacy policy and terms of use URLs
```

You need hosted privacy policy and terms of use URLs. Generate them for free at [termsfeed.com](https://www.termsfeed.com/) and host on GitHub Pages, your own site, or any public URL. Both are required — the build will fail without them.

### Building

```bash
./lifecycle/ios-build.sh                # Build for device (generic iOS)
./lifecycle/ios-build.sh --simulator    # Build for iOS Simulator
./lifecycle/ios-build.sh --release      # Release configuration
```

The build script handles proto code generation automatically (two-pass build: first pass generates `.pb.swift` and `.grpc.swift` files, second pass compiles them).

### Xcode SPM Dependencies

The Xcode project includes these Swift Package Manager dependencies (resolved automatically):
- `apple/swift-protobuf` — Protobuf runtime
- `grpc/grpc-swift-2` — gRPC core
- `grpc/grpc-swift-nio-transport` — HTTP/2 transport
- `grpc/grpc-swift-protobuf` — gRPC protobuf integration

### Network Setup for iOS

The iOS app connects to two server endpoints:
- **gRPC** (port 9090 default) — all data operations via HTTP/2
- **HTTP** (port 8080 default) — images, video streaming, file downloads

In production, HAProxy on pfSense terminates TLS (LetsEncrypt wildcard cert) and forwards to these ports. See the Network Architecture section in CLAUDE.md.

---

## Mac Development

For building the server and developing the iOS app on macOS, see [Mac Development Setup](MAC_SETUP.md).

## Local Development (Windows)

For contributors who want to build and run from source.

### 1. Prerequisites

- **JDK 21+** &mdash; [Amazon Corretto 25](https://aws.amazon.com/corretto/) recommended
- **FFmpeg** &mdash; Required for video transcoding. Download from [ffmpeg.org](https://ffmpeg.org/download.html) or install via your package manager. Note the install path (e.g., `C:\ffmpeg\bin\ffmpeg.exe` on Windows, `/usr/bin/ffmpeg` on Linux/Mac).
- **Faster-Whisper-XXL** (optional) &mdash; For AI subtitle generation. Requires an NVIDIA GPU with CUDA support. Download from the [Faster-Whisper-XXL releases page](https://github.com/Purfview/whisper-standalone-win/releases). On first run it downloads the language model (~1.6 GB for `large-v3-turbo`). See [Generating Subtitles](GENERATING_SUBTITLES.md) for full setup.

### 2. Configure secrets

```bash
cp secrets/example.env secrets/.env
```

Edit `secrets/.env`:

```properties
# Required — pick any password for the embedded database
H2_PASSWORD=your-database-password

# Required — AES encryption key for the database file
H2_FILE_PASSWORD=your-file-encryption-password

# Required — enables poster art, cast, descriptions, popularity sorting
TMDB_API_KEY=your-tmdb-v3-api-key
```

### 3. Build and run

```bash
./gradlew build              # First build: ~1 minute
./gradlew --no-daemon run    # Starts at http://localhost:8080
```

### 4. Configure FFmpeg and Whisper paths

After creating your admin account, open **Settings** from the sidebar:

- **FFmpeg path** &mdash; Set to your FFmpeg executable (e.g., `C:\ffmpeg\bin\ffmpeg.exe`). This enables the background transcoder that converts MKV/AVI files to browser-compatible MP4.
- **Whisper path** &mdash; Set to your Faster-Whisper-XXL executable if you want AI-generated subtitles. Leave empty to skip subtitle generation.

### 5. Create your admin account

Open **http://localhost:8080** and complete the setup wizard.

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="USER_GUIDE.md">User Guide</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a>
</p>
