<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Getting Started

This guide walks you through setting up your own Media Manager instance, from prerequisites to your first barcode scan.

## Prerequisites

### Accounts

| Service | Required? | Purpose | Sign Up |
|---------|-----------|---------|---------|
| **TMDB** | **Yes** | Poster images, cast, descriptions, release years | [Create account](https://www.themoviedb.org/signup) &rarr; [API settings](https://www.themoviedb.org/settings/api) &rarr; copy "API Key (v3 auth)" |
| **UPCitemdb** | No account needed | Barcode &rarr; product name lookup | Free tier, IP-throttled (6 req/min, 100/day) |

A TMDB API key is required. Without it, titles won't have poster art, cast data, descriptions, or popularity sorting &mdash; the catalog is essentially unusable.

### Software

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 21 or later | [Amazon Corretto 25](https://aws.amazon.com/corretto/) recommended |
| **Gradle** | 9.1+ | Included via wrapper (`./gradlew`) &mdash; no separate install |
| **FFmpeg** | Any recent | Required only for transcoding; optional for catalog-only use |
| **Faster-Whisper-XXL** | Any recent | Subtitle generation from audio (optional; requires NVIDIA GPU) |
| **Docker** | Any recent | For containerized deployment; optional for local development |

### NAS (optional)

If you have transcoded media files on a NAS, Media Manager can discover and stream them. The expected directory layout:

```
<nas_root>/
  BLURAY/                    # Flat directory of movie rips (.mkv, .mp4)
  DVD/                       # Flat directory of movie rips
  UHD/                       # Flat directory of UHD rips
  TV Series From Media/      # Recursive: Show Name/Season XX/episodes
  ForBrowser/                # Auto-generated browser-compatible MP4 mirror
```

The NAS must be accessible as a mounted filesystem path from wherever the server runs (local path, SMB mount, or Docker volume).

---

## Option A: Run Locally

### 1. Configure secrets

```bash
cp secrets/example.env secrets/.env
```

Edit `secrets/.env`:

```properties
# Required — pick any password for the embedded database
H2_PASSWORD=your-database-password

# Required — enables poster art, cast, descriptions, popularity sorting
TMDB_API_KEY=your-tmdb-v3-api-key
```

### 2. Build

```bash
./gradlew build
```

The first build takes 4-6 minutes (downloads dependencies, compiles Kotlin, bundles the Vaadin frontend). Subsequent builds are faster.

### 3. Run

```bash
./gradlew --no-daemon run
```

The server starts at **http://localhost:8080**.

### 4. Create your admin account

On first launch, you're redirected to the setup page. Create your admin account (username, display name, password). This account has full access to all features.

### 5. Configure NAS path (optional)

If you have media files on a NAS:

1. Open the sidebar &rarr; **Settings**
2. Set **NAS Root Path** to your NAS mount point (e.g., `Z:\YourShareName\media` on Windows or `/media` in Docker)
3. Go to **Transcodes &rarr; Status** and click **Scan NAS** to discover files

---

## Option B: Docker Deployment

Docker is the recommended way to run Media Manager on a NAS or home server.

### 1. Create a `docker-compose.yml`

```yaml
services:
  mediamanager:
    image: ghcr.io/jeffbstewart/mediamanager:latest
    container_name: mediamanager
    # Run as non-root — UID:GID must own the volumes.
    # See Admin Guide > Docker Deployment Notes for how to find your UID.
    user: "1046:100"
    # Host networking required for Roku SSDP device discovery (UDP multicast).
    network_mode: host
    # ports:                            # (not used with host networking)
    #   - "8080:8080"                   # Main app
    #   - "16002:8081"                  # Internal health/metrics (LAN only)
    environment:
      - H2_PASSWORD=your-strong-database-password
      - TMDB_API_KEY=your-tmdb-api-key
      - MM_NAS_ROOT=/media
      - MM_BEHIND_PROXY=false          # Set true if behind nginx/traefik
    volumes:
      - ./cache:/cache                  # Database + poster cache (persistent)
      - /path/to/media:/media           # NAS media files (read/write)
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/health"]
      interval: 30s
      timeout: 5s
      retries: 3
```

### 2. Launch

```bash
docker compose up -d
```

### 3. First-time setup

Open **http://your-host:8080** and create your admin account.

### Environment Variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `H2_PASSWORD` | **Yes** | Database password |
| `TMDB_API_KEY` | **Yes** | TMDB enrichment (poster art, cast, descriptions) |
| `MM_NAS_ROOT` | For media streaming | Path to media inside the container |
| `MM_BEHIND_PROXY` | If proxied | Enables `X-Forwarded-*` header trust |
| `MM_FFMPEG_PATH` | Rarely | Override FFmpeg location (default: `/usr/bin/ffmpeg`) |

---

## What's Next?

Once the server is running and you've created your admin account:

1. **Scan your first disc** &mdash; Open the sidebar &rarr; **Scan New Purchase**, enter a UPC barcode. The system looks up the product, creates a catalog entry, and enriches it with TMDB data within seconds.

2. **Browse your catalog** &mdash; Open **Catalog** from the sidebar. Titles appear with poster art as they're enriched.

3. **Connect your NAS** &mdash; If you have transcoded files, set the NAS root path in Settings and run a NAS scan. Files are auto-matched to catalog titles.

4. **Watch something** &mdash; Find a title with a linked transcode and hit the play button. MP4 files play immediately; MKV files play once the background transcoder has processed them.

   > **Note:** Transcoding on a NAS CPU can be *extremely* slow (hours per file for HEVC sources). If you have a large backlog, consider setting up a [Transcode Buddy](TRANSCODE_BUDDY.md) on a machine with an NVIDIA GPU for 5-20x faster transcoding. To disable local (server-side) transcoding entirely, set the FFmpeg path in Settings to an empty value &mdash; the server will leave all transcoding work for the buddy.

5. **Invite users** &mdash; Open **Users** from the sidebar to create accounts for household members. Set content rating ceilings for younger viewers.

6. **Set up Roku** &mdash; See the [Roku Setup Guide](ROKU_GUIDE.md) to stream on your TV.

7. **Generate subtitles** &mdash; See [Generating Subtitles](GENERATING_SUBTITLES.md) to set up Whisper AI subtitle generation on the Transcode Buddy.

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="USER_GUIDE.md">User Guide</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a>
</p>
