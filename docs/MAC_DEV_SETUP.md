# Mac Development Setup

This guide covers setting up an Apple Silicon Mac for full-stack MediaManager development: server changes, Docker image builds, and iOS app development.

## Prerequisites

### 1. Xcode Command Line Tools

Includes `git`, compilers, and headers needed by Homebrew packages.

```bash
xcode-select --install
```

### 2. Homebrew

Package manager for everything else. Install from [brew.sh](https://brew.sh):

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

After install, follow the instructions to add Homebrew to your PATH (it prints the commands).

### 3. JDK 21+

Required by Gradle to build the server. Amazon Corretto 25 recommended:

```bash
brew install --cask corretto
```

Verify:

```bash
java -version
# Expected: openjdk version "25.x.x" ... Amazon.com Inc.
```

### 4. Docker Desktop for Mac

Required to build and push Linux container images.

Download from [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/) or:

```bash
brew install --cask docker
```

Launch Docker Desktop and complete the setup wizard.

**Configure the insecure registry** (the LAN registry doesn't use TLS):

1. Docker Desktop -> Settings -> Docker Engine
2. Add your registry to the JSON config:

```json
{
  "insecure-registries": ["172.16.4.12:15000"]
}
```

3. Apply & Restart

**Enable cross-platform builds** for Apple Silicon. The NAS runs Linux/amd64, so container images must target that platform:

```bash
docker buildx create --name multiarch --use
docker buildx inspect --bootstrap
```

### 5. GitHub CLI

For creating PRs, viewing issues, and managing releases:

```bash
brew install gh
```

Authenticate:

```bash
gh auth login
# Choose: GitHub.com -> HTTPS -> Login with a web browser
```

Verify:

```bash
gh auth status
# Expected: Logged in to github.com account jeffbstewart
```

### 6. Claude Code

The AI coding assistant used for this project:

```bash
npm install -g @anthropic-ai/claude-code
```

Or if you don't have Node.js:

```bash
brew install node
npm install -g @anthropic-ai/claude-code
```

Launch in the project directory:

```bash
cd ~/path/to/MediaManager
claude
```

Claude will read `CLAUDE.md` for project context on startup.

### 7. FFmpeg (optional)

Only needed if you want to run the transcoder locally on the Mac:

```bash
brew install ffmpeg
```

### 8. Xcode

Required for iOS app development. Install from the Mac App Store or:

```bash
xcode-select --install  # CLI tools only (already done above)
# Full Xcode: download from App Store
```

---

## Clone and Configure

### 1. Clone the repository

```bash
git clone https://github.com/jeffbstewart/MediaManager.git
cd MediaManager
```

### 2. Configure secrets

```bash
cp secrets/example.env secrets/.env
```

Edit `secrets/.env` with your credentials:

```properties
H2_PASSWORD=your-database-password
H2_FILE_PASSWORD=your-file-encryption-password
TMDB_API_KEY=your-tmdb-v3-api-key
```

### 3. Configure Docker deployment secrets

```bash
cp secrets/example.deploy.env secrets/deploy.agent_visible_env
```

Edit `secrets/deploy.agent_visible_env`:

```properties
REGISTRY=172.16.4.12:15000
NAS_IP=172.16.4.12
WATCHTOWER_PORT=8083
WATCHTOWER_TOKEN=your-watchtower-token
```

### 4. Build and verify

```bash
./gradlew build              # Should complete successfully
./gradlew --no-daemon run    # Starts at http://localhost:8080
```

---

## Docker Workflow on Apple Silicon

The `docker-build.sh` script builds for the host architecture by default. On Apple Silicon, you need to cross-compile for `linux/amd64` (the NAS target).

Set this environment variable before running the build script:

```bash
export DOCKER_DEFAULT_PLATFORM=linux/amd64
```

Or add it to your shell profile (`~/.zshrc`):

```bash
echo 'export DOCKER_DEFAULT_PLATFORM=linux/amd64' >> ~/.zshrc
source ~/.zshrc
```

Then the standard deploy workflow works:

```bash
# Server-only changes:
./lifecycle/docker-build.sh

# Changes affecting transcode-common or transcode-buddy:
./lifecycle/deploy-all.sh
```

Cross-compilation is slower than native builds (~2-3 minutes vs ~1 minute) because Gradle runs under QEMU emulation inside the Docker build. This is a one-time cost per deploy, not per code change — local `./gradlew build` runs natively and is fast.

### Verify the image architecture

```bash
docker inspect --format='{{.Architecture}}' 172.16.4.12:15000/mediamanager:latest
# Expected: amd64
```

---

## Development Cycle

### Local server development (fast iteration)

```bash
./gradlew build                      # Compile + test
./gradlew --no-daemon run            # Run locally at :8080
```

### Deploy to NAS

```bash
./lifecycle/docker-build.sh          # Build + push + Watchtower redeploy
```

### iOS app development

The iOS app is a separate Xcode project that consumes the `/api/v1/` REST API. During development, point the iOS app at:

- **Local server**: `http://localhost:8080` (Mac running the server via Gradle)
- **NAS server**: `https://your-domain:8080` (production, requires HTTPS)

The API uses JWT Bearer authentication. See `docs/IOS_PLAN.md` for the full endpoint reference.

---

## Key Differences from Windows Development

| Area | Windows | Mac |
|------|---------|-----|
| Shell | Git Bash (MSYS2) | zsh (native) |
| Docker | Docker Desktop (native amd64) | Docker Desktop (cross-compile to amd64) |
| Path separator | `\` (but Git Bash uses `/`) | `/` |
| JDK install | Manual or Chocolatey | `brew install --cask corretto` |
| Transcode buddy | Runs locally via `run-buddy.sh` | Same, but GPU transcoding requires NVIDIA (not available on Mac) |

The server code, Gradle build, and lifecycle scripts all work identically on both platforms. The only Mac-specific concern is the `DOCKER_DEFAULT_PLATFORM=linux/amd64` setting for Docker builds.

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="GETTING_STARTED.md">Getting Started</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a>
</p>
