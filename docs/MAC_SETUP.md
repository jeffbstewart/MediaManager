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

Xcode manages signing certificates and provisioning profiles automatically via the macOS Keychain. No signing files should ever be committed to the repository — `.gitignore` excludes `.p12`, `.mobileprovision`, and `.cer` files, and the presubmit hook blocks them as a safety net.

To configure signing:

1. Open the project in Xcode
2. Select the target &rarr; **Signing & Capabilities**
3. Enable **Automatically manage signing**
4. Select your Apple Developer team

The Team ID in the `.xcodeproj` is a public identifier and safe to commit.

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
