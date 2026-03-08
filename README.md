<p align="center">
  <img src="docs/images/logo.png" alt="Media Manager" width="128" height="128">
</p>

<h1 align="center">mediaManager</h1>

<p align="center">
  <strong>Your physical media collection, digitized and streamable.</strong><br>
  Catalog DVDs, Blu-rays, and UHDs by barcode. Enrich with TMDB metadata.<br>
  Stream from your NAS to any browser or Roku.
</p>

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Clients                                    │
│                                                                     │
│   ┌──────────┐    ┌──────────────┐    ┌──────────┐                  │
│   │ Browser  │    │ Roku Channel │    │ iOS App  │                  │
│   │ (Vaadin) │    │ (BrightScript│    │ (future) │                  │
│   │          │    │  sideloaded) │    │          │                  │
│   └────┬─────┘    └──────┬───────┘    └────┬─────┘                  │
│        │ WebSocket       │ HTTP/REST       │                        │
└────────┼─────────────────┼─────────────────┼────────────────────────┘
         │                 │                 │
┌────────┴─────────────────┴─────────────────┴────────────────────────┐
│                     mediaManager Server                             │
│                     (Embedded Jetty)                                │
│                                                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐                │
│  │  Vaadin UI  │  │  REST API   │  │   Servlets   │                │
│  │  (routes)   │  │ /roku/feed  │  │ /stream /api │                │
│  │             │  │ /buddy/*    │  │ /posters     │                │
│  └─────────────┘  │ /pair/*     │  │ /admin/*     │                │
│                   └─────────────┘  └──────────────┘                │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │              Background Agents (daemon threads)          │       │
│  │                                                          │       │
│  │  UPC Lookup ─── TMDB Enrichment ─── NAS Scanner          │       │
│  │  (barcode      (metadata,           (file discovery,     │       │
│  │   → product)    posters, cast)       auto-matching)      │       │
│  │                                                          │       │
│  │  Transcoder Agent ─── SSDP Responder                     │       │
│  │  (MKV→MP4 for        (Roku auto-                         │       │
│  │   browser playback)    discovery)                         │       │
│  └──────────────────────────────────────────────────────────┘       │
│                                                                     │
│  ┌──────────────┐  ┌────────────────────────────────────────┐       │
│  ┌──────────────┐  ┌────────────────────────────────────────┐       │
│  │  H2 Database │  │  Flyway Migrations + Schema Updaters  │       │
│  │  (file mode) │  │  (V001..V043 SQL + programmatic)      │       │
│  └──────────────┘  └────────────────────────────────────────┘       │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │  Internal Server (:8081 → LAN :16002)                    │       │
│  │  /health (Docker healthcheck)  /metrics (Prometheus)     │       │
│  │  Not internet-accessible                                 │       │
│  └──────────────────────────────────────────────────────────┘       │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
┌─────────────────────────────┼───────────────────────────────────────┐
│                    NAS (SMB share)                                   │
│                              │                                      │
│  ┌───────────────────────────┴──────────────────────────────┐       │
│  │                    Media Files                           │       │
│  │                                                          │       │
│  │  BLURAY/          ← movie rips (.mkv, .mp4)              │       │
│  │  DVD/             ← movie rips (.mkv, .mp4)              │       │
│  │  UHD/             ← movie rips (.mkv, .mp4)              │       │
│  │  TV Series From Media/  ← Show/Season/episodes           │       │
│  │  ForBrowser/      ← transcoded MP4s (browser/Roku-safe)  │       │
│  │    ├── sprites    ← thumbnail sprite sheets + VTT        │       │
│  │    └── subtitles  ← Whisper-generated .srt files         │       │
│  └──────────────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    Transcode Buddy (optional)                       │
│                    (standalone worker)                               │
│                                                                     │
│  Runs on a separate machine (e.g., desktop with GPU).               │
│  Claims work from the server via REST API (/buddy/*).               │
│                                                                     │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐                    │
│  │  Video     │  │  Thumbnail │  │  Subtitle  │                    │
│  │  Transcode │  │  Sprites   │  │  Generation│                    │
│  │  (FFmpeg   │  │  (FFmpeg   │  │  (Whisper) │                    │
│  │   NVENC)   │  │   → JPG)   │  │            │                    │
│  └────────────┘  └────────────┘  └────────────┘                    │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    External APIs                                    │
│                                                                     │
│  UPCitemdb ──── barcode → product name (free, no key, IP-throttled) │
│  TMDB ───────── title search, metadata, posters, cast (API key)     │
└─────────────────────────────────────────────────────────────────────┘
```

## Documentation

| Guide | Audience | Covers |
|-------|----------|--------|
| [Getting Started](docs/GETTING_STARTED.md) | Server admin | Installation, configuration, first launch |
| [User Guide](docs/USER_GUIDE.md) | Everyone | Browsing, searching, watching, personalizing |
| [Admin Guide](docs/ADMIN_GUIDE.md) | Administrators | Catalog management, transcoding, user management, env vars, CLI flags |
| [Roku Setup](docs/ROKU_GUIDE.md) | Roku users | Channel installation, pairing, playback |
| [Transcode Buddy](docs/TRANSCODE_BUDDY.md) | Server admin | Distributed transcoding with GPU acceleration |
| [Generating Subtitles](docs/GENERATING_SUBTITLES.md) | Server admin | Whisper AI subtitle generation setup |
| [Feature Tracker](docs/FEATURES.md) | Contributors | Planned, in-progress, and completed features |

## Tech Stack

- **Language:** Kotlin on JDK 21+ (Corretto 25)
- **UI:** Vaadin 25 via Vaadin-on-Kotlin (server-rendered, no JavaScript toolchain)
- **Server:** Embedded Jetty via vaadin-boot
- **Database:** H2 in file mode, Flyway migrations, HikariCP
- **Build:** Gradle 9.3.1 with Kotlin DSL
- **Deployment:** Docker on Synology NAS with Watchtower auto-deploy

## License

[MIT License](LICENSE) — Copyright (c) 2026 Jeffrey B. Stewart
