<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="128" height="128">
</p>

<h1 align="center">Media Manager</h1>

<p align="center">
  <strong>Your physical media collection, digitized and streamable.</strong><br>
  Catalog DVDs, Blu-rays, and UHDs by barcode. Enrich with TMDB metadata.<br>
  Stream from your NAS to any browser or Roku.
</p>

<p align="center">
  <a href="GETTING_STARTED.md">Getting Started</a> &bull;
  <a href="USER_GUIDE.md">User Guide</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a> &bull;
  <a href="ROKU_GUIDE.md">Roku Setup</a> &bull;
  <a href="TRANSCODE_BUDDY.md">Transcode Buddy</a>
</p>

---

## What is Media Manager?

Media Manager is a self-hosted web application for people who own physical media (DVD, Blu-ray, UHD, HD DVD). It solves the problem of knowing what you own, finding it, and watching it without getting up to load a disc.

**Catalog** your collection by scanning UPC barcodes. Media Manager looks up the product, identifies the titles inside (even multi-packs), and enriches each title with poster art, descriptions, cast, genres, and content ratings from TMDB.

**Discover** transcoded files on your NAS and automatically match them to catalog titles. The system understands MakeMKV naming conventions and handles movies, TV series with season/episode structure, and multi-disc sets.

**Watch** from any browser or your living room Roku. The built-in video player streams MP4 files directly. MKV and AVI files are automatically transcoded to browser-compatible MP4 in the background, prioritized by popularity. Playback position syncs across devices.

**Organize** with tags, wish lists, favorites, and per-user content rating filters. Each household member gets their own account with personalized views.

## Documentation

| Guide | Audience | Covers |
|-------|----------|--------|
| [Getting Started](GETTING_STARTED.md) | Server admin | Installation, configuration, first launch |
| [User Guide](USER_GUIDE.md) | Everyone | Browsing, searching, watching, personalizing |
| [Admin Guide](ADMIN_GUIDE.md) | Administrators | Catalog management, transcoding, user management |
| [Roku Setup](ROKU_GUIDE.md) | Roku users | Channel installation, pairing, playback |
| [Transcode Buddy](TRANSCODE_BUDDY.md) | Server admin | Distributed transcoding with GPU acceleration |
| [Generating Subtitles](GENERATING_SUBTITLES.md) | Server admin | Whisper AI subtitle generation setup |
| [Feature Tracker](FEATURES.md) | Contributors | Planned, in-progress, and completed features |

## Quick Start

```bash
# Clone and configure
cp secrets/example.env secrets/.env
# Edit secrets/.env — set H2_PASSWORD and TMDB_API_KEY

# Build and run
./gradlew build
./gradlew --no-daemon run

# Open http://localhost:8080 — create your admin account
```

See [Getting Started](GETTING_STARTED.md) for the full walkthrough.

## Architecture

```
                              ┌─── Synology NAS ──────────────────────────┐
                              │                                           │
Clients                       │  Reverse Proxy         Docker             │
─────────                     │  ──────────────        ──────             │
                              │                                           │
Browser ──────┐               │  DSM Reverse    HTTP   mediaManager       │
              ├── HTTPS ──►   │  Proxy (nginx) ──────► (Jetty :8080)      │
Roku Channel ─┘               │  TLS terminate         │   │              │
                              │  Let's Encrypt         │   │  Volume      │
                              │                        │   ├──► /media    │
                              │  Watchtower            │   │   (NAS files)│
                              │  (auto-deploy)         │   │              │
                              │                        │   └──► /cache    │
                              │  Prometheus            │      (H2 + data) │
                              │  ──────────            │                  │
                              │  :9090 ──► scrape ──►  │  Internal server │
                              │                        │  (:8081 → :16002)│
                              │                        │  /health /metrics│
                              └────────────────────────│──────────────────┘
                                                       │
                                                       │ REST API
                                                       ▼
                                               Transcode Buddy
                                               (GPU worker)
```

The Synology NAS hosts everything: the DSM built-in reverse proxy terminates TLS with a Let's Encrypt certificate and forwards traffic to the mediaManager Docker container on port 8080. The server runs as a single Java process with an embedded Jetty web server. A separate internal Jetty server on port 8081 (mapped to LAN port 16002) serves `/health` and `/metrics` — these are not internet-accessible. Background agents handle barcode lookups, TMDB enrichment, NAS file scanning, and video transcoding. Watchtower monitors for new Docker images and auto-deploys updates. An optional Transcode Buddy worker offloads GPU-intensive transcoding to a separate machine.
