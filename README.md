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

## Quick Start

1. Copy the [`docker-compose.yml`](docker-compose.yml) to your server (or paste it into Portainer)
2. Set `H2_PASSWORD`, `H2_FILE_PASSWORD`, and `TMDB_API_KEY` ([free signup](https://www.themoviedb.org/settings/api))
3. Update the volume paths to point at your cache directory and media files
4. `docker compose up -d`
5. Open **http://your-host:8080** and create your admin account

See [Getting Started](docs/GETTING_STARTED.md) for the full walkthrough.

See the [architecture diagram](docs/index.md#architecture) for a visual overview of the deployment topology.

## Documentation

| Guide | Audience | Covers |
|-------|----------|--------|
| [Getting Started](docs/GETTING_STARTED.md) | Server admin | Installation, configuration, first launch |
| [User Guide](docs/USER_GUIDE.md) | Everyone | Browsing, searching, watching, personalizing |
| [Admin Guide](docs/ADMIN_GUIDE.md) | Administrators | Catalog management, transcoding, user management, env vars, CLI flags |
| [Roku Setup](docs/ROKU_GUIDE.md) | Roku users | Channel installation, pairing, playback |
| [Transcode Buddy](docs/TRANSCODE_BUDDY.md) | Server admin | Distributed transcoding with GPU acceleration |
| [Generating Subtitles](docs/GENERATING_SUBTITLES.md) | Server admin | Whisper AI subtitle generation setup |
| [Feature Tracker](docs/FEATURES.md) | Contributors | Completed features history; open items tracked in [GitHub Issues](https://github.com/jeffbstewart/MediaManager/issues) |
| [Competitive Analysis](docs/COMPETITIVE_ANALYSIS.md) | Contributors | Market positioning vs Plex, Jellyfin, CLZ Movies, and others |

## Tech Stack

- **Language:** Kotlin on JDK 21+ (Corretto 25)
- **UI:** Vaadin 25 via Vaadin-on-Kotlin (server-rendered, no JavaScript toolchain)
- **Server:** Embedded Jetty via vaadin-boot
- **Database:** H2 in file mode, Flyway migrations, HikariCP
- **Build:** Gradle 9.3.1 with Kotlin DSL
- **Deployment:** Docker on Synology NAS with Watchtower auto-deploy

## License

[MIT License](LICENSE) — Copyright (c) 2026 Jeffrey B. Stewart
