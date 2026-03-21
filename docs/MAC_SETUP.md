<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Mac Development Setup

This guide covers setting up a macOS machine for Media Manager development. The Mac serves as a build-and-deploy workstation — you can compile the server, push Docker images, and develop the iOS app, but you run the server on your NAS.

## Prerequisites

### Xcode (required for iOS development)

Install from the Mac App Store or:

```bash
xcode-select --install   # Command line tools only (no iOS simulator)
```

For iOS development you need the full Xcode from the App Store, which includes Swift, the iOS SDK, and the simulator runtime.

After installing Xcode, install an iOS simulator runtime:

- Open **Xcode &rarr; Settings &rarr; Platforms** and download **iOS 18.x**, or:

```bash
xcodebuild -downloadPlatform iOS
```

This download is ~7 GB. You can skip it if you only test on a physical device.

### Homebrew

Most dependencies install via [Homebrew](https://brew.sh/):

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### JDK (required for server compilation)

The server requires JDK 21+. Install Amazon Corretto 25 (the project standard):

```bash
brew install --cask corretto
```

Verify:

```bash
java -version
# Expected: openjdk version "25.x.x" (Amazon Corretto)
```

You don't need to install Kotlin separately — Gradle downloads the Kotlin compiler automatically.

### Docker (required for image builds and deployment)

```bash
brew install --cask docker
```

After install, launch **Docker.app** from Applications to complete the one-time setup (creates the Linux VM). Verify:

```bash
docker --version
```

> **Note:** Docker Desktop requires a [paid license](https://www.docker.com/pricing/) for companies with >250 employees or >$10M revenue. [Colima](https://github.com/ablemachines/colima) is a free alternative: `brew install colima && colima start`.

**Insecure registries:** If deploying to a local Docker registry over HTTP (not HTTPS), add it to Docker Desktop's engine config. Open **Docker Desktop &rarr; Settings &rarr; Docker Engine** and add:

```json
"insecure-registries": ["172.16.4.12:15000"]
```

Replace the IP and port with your registry's address. Click **Apply &amp; Restart**.

**Cross-compilation:** The Mac builds ARM64 images by default, but the Synology NAS is x86_64. The `lifecycle/docker-build.sh` script uses `--platform linux/amd64` to cross-compile. This is slower (~3 min vs ~1 min) due to QEMU emulation.

### Git

macOS ships with Apple Git via Xcode command line tools. Verify:

```bash
git --version
```

---

## Building the Server

You don't need to run the server locally — it runs on your NAS. But you do need to compile it to verify changes:

```bash
cd /path/to/MediaManager
./gradlew build          # Compile + run tests (~1 min first time)
```

Gradle auto-downloads all dependencies (including the Kotlin compiler) on first run.

## Deploying Docker Images

Push a new server image to your registry:

```bash
./lifecycle/docker-build.sh    # Build, tag, push, trigger Watchtower
```

For changes that affect both the server and the transcode buddy:

```bash
./lifecycle/deploy-all.sh      # Full deploy (buddy + Docker + Roku)
```

## iOS Development

The iOS app is a SwiftUI universal app (iPhone + iPad) in the `ios/` directory. Open the Xcode project:

```bash
open ios/MediaManager/MediaManager.xcodeproj
```

Or build from the command line:

```bash
xcodebuild -project ios/MediaManager/MediaManager.xcodeproj -scheme MediaManager -sdk iphonesimulator build
```

The app communicates with the server via the `/api/v1/` REST API. Point it at your NAS during development — there's no need to run the server locally.

### Code Signing

Xcode manages signing certificates and provisioning profiles automatically via the macOS Keychain. No signing files should ever be committed to the repository &mdash; `.gitignore` excludes `.p12`, `.mobileprovision`, and `.cer` files, and the presubmit hook blocks them as a safety net.

**Developer Team ID setup:**

Your Apple Developer Team ID is kept in a gitignored `Developer.xcconfig` file so it never enters source control:

```bash
cd ios/MediaManager
cp Developer.xcconfig.example Developer.xcconfig
```

Edit `Developer.xcconfig` and replace `YOUR_TEAM_ID_HERE` with your Team ID (found in [Apple Developer &rarr; Membership](https://developer.apple.com/account#MembershipDetailsCard)). Xcode picks this up automatically via the project's build configuration &mdash; no need to set the team in the Xcode UI.

### Installing on Your Device

1. Connect your iPhone or iPad via USB
2. Open the project in Xcode: `open ios/MediaManager/MediaManager.xcodeproj`
3. Select your device from the device dropdown (top of the Xcode window)
4. Hit **Run** (&#8984;R)
5. First time only: on the device, go to **Settings &rarr; General &rarr; VPN &amp; Device Management** and trust your developer certificate

The app installs and launches on your device. Subsequent builds are incremental and fast.

### Ad Hoc Distribution to Family Members

Ad Hoc distribution lets you install the app on up to 100 registered devices per year without App Store review.

**One-time setup:**

1. Each family member sends you their device UDID (on the device: **Settings &rarr; General &rarr; About**, tap and hold the serial number row to copy)
2. Log in to [Apple Developer &rarr; Devices](https://developer.apple.com/account/resources/devices/list) and register each UDID
3. Xcode automatically includes registered devices in your provisioning profile

**Building the IPA:**

1. In Xcode, select **Any iOS Device** as the build target (not a specific device or simulator)
2. **Product &rarr; Archive**
3. When the archive completes, the Organizer window opens
4. Select the archive and click **Distribute App**
5. Choose **Ad Hoc**
6. Follow the prompts &mdash; Xcode signs the IPA for your registered devices
7. Click **Export** to save the `.ipa` file

**Installing on family devices:**

- **AirDrop:** Share the `.ipa` file via AirDrop to the device
- **Apple Configurator:** Connect the device via USB, drag the `.ipa` into Apple Configurator
- **Web link:** Host the `.ipa` on a local web server with a manifest `.plist` &mdash; the device installs it via Safari

Each family member needs to trust the developer certificate on first install (**Settings &rarr; General &rarr; VPN &amp; Device Management**).

**Updating:** Build a new archive and distribute the same way. The app updates in place &mdash; no data loss.

### SSDP Network Discovery (Multicast Entitlement)

The app uses SSDP multicast to auto-discover the mediaManager server on the local network. On iOS, UDP multicast requires the `com.apple.developer.networking.multicast` entitlement from Apple.

**Current status:** Entitlement request submitted to Apple (2026-03-21). Typical turnaround is 1-2 weeks.

**Without the entitlement:** SSDP discovery silently fails and the app falls back to manual URL entry. All other functionality works normally.

**To request the entitlement** (if setting up a new developer account):
1. Go to [developer.apple.com/contact/request/networking-multicast](https://developer.apple.com/contact/request/networking-multicast)
2. Describe the use case: "SSDP (Simple Service Discovery Protocol) to discover a self-hosted media server on the local network. The app sends an M-SEARCH multicast to 239.255.255.250:1900 and listens for a unicast response containing the server's URL."
3. Once granted, add the entitlement to the app's `.entitlements` file:
```xml
<key>com.apple.developer.networking.multicast</key>
<true/>
```
4. Rebuild and deploy &mdash; SSDP will start working automatically.

## Transcode Buddy on Mac

The Mac can serve as a transcode buddy worker, using Apple Silicon's VideoToolbox hardware encoder for video transcoding and faster-whisper for AI subtitle generation.

### Prerequisites

- **FFmpeg** with VideoToolbox support: `brew install ffmpeg`
- **Java 21+**: `brew install --cask corretto` (or already installed for server builds)
- **NAS mounted** at a local path (e.g., `/Volumes/PlexServedMedia/media`)
- **faster-whisper** (optional, for subtitles): `pip3 install faster-whisper`

### Setup

1. **Get a buddy API key** from the server: **Settings &rarr; Buddy Keys &rarr; New Key**

2. **Configure** &mdash; copy and edit the buddy properties:

```bash
cp transcode-buddy/example.buddy.properties transcode-buddy/buddy.properties
```

Key settings for Mac:

```properties
server_url=https://your-server.example.com
api_key=your-buddy-key
buddy_name=macbook-pro
nas_root=/Volumes/YourNASShare/media
ffmpeg_path=/opt/homebrew/bin/ffmpeg
ffprobe_path=/opt/homebrew/bin/ffprobe
encoder_preference=videotoolbox,cpu
whisper_path=/path/to/MediaManager/lifecycle/faster-whisper-mac.sh
whisper_device=cpu
whisper_compute_type=int8
```

3. **Build and run:**

```bash
./lifecycle/run-buddy.sh
```

4. **Monitor:** `./lifecycle/buddy-log.sh -f`

5. **Stop:** `./lifecycle/stop-buddy.sh`

### VideoToolbox Encoder

The `videotoolbox` encoder uses Apple Silicon's hardware media engine for H.264 encoding. It's comparable in speed to NVIDIA NVENC and doesn't load the CPU. The encoder is auto-detected on startup &mdash; if VideoToolbox is unavailable, it falls back to CPU (libx264).

### Whisper Subtitles on Mac

The Windows transcode buddy uses `faster-whisper-xxl.exe` (standalone binary with bundled CUDA). On Mac, we use the Python `faster-whisper` library via a wrapper script (`lifecycle/faster-whisper-mac.sh`).

Key differences from Windows:
- **Device:** `cpu` instead of `cuda` (Apple Silicon doesn't support CUDA)
- **Compute type:** `int8` instead of `float16` (optimized for CPU inference)
- **Performance:** Roughly 4x faster than real-time on Apple Silicon CPU with `large-v3-turbo`. A 30-minute episode takes ~7-8 minutes.

The first run downloads the Whisper model (~1.5 GB for `large-v3-turbo`).

### Network Performance

**Use wired ethernet** for transcode buddy work. SMB file reads over WiFi are significantly slower &mdash; thumbnail generation for a 2-hour Blu-ray movie takes 30+ minutes over WiFi vs ~6 minutes over gigabit ethernet. Transcode jobs read the full source file, so network throughput directly impacts performance.

To verify ethernet is active and at gigabit speed:

```bash
ifconfig en7 | grep media
# Expected: media: autoselect (1000baseT <full-duplex>)
```

---

## Cross-Platform Notes

### Line Endings

The repository includes a `.gitattributes` file that normalizes line endings: LF in the repo, native on checkout. Shell scripts are forced to LF on all platforms (Git Bash on Windows requires LF). No manual configuration needed.

### Lifecycle Scripts

Most lifecycle scripts are POSIX-compatible and work on both macOS and Windows (Git Bash). The following scripts have Windows-specific commands and will need cross-platform updates before use on Mac:

| Script | Issue |
|--------|-------|
| `roku-deploy-dev.sh` | `sed -i` syntax (BSD vs GNU) |
| `roku-deploy-prod.sh` | `sed -i` syntax (BSD vs GNU) |
| `roku-debug.sh` | `ncat` vs `nc` |
| `roku-screenshot.sh` | DirectShow vs AVFoundation |
| `run-buddy.sh` | `javaw.exe`, classpath separator |
| `stop-buddy.sh` | `wmic` / `taskkill` |
| `stop-devserver.sh` | `wmic` / `taskkill` |

These are Roku and local process management scripts — they don't affect server compilation or Docker deployment.

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="GETTING_STARTED.md">Getting Started</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a>
</p>
