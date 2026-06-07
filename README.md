<p align="center">
  <img src="docs/images/logo.png" alt="Media Manager" width="128" height="128">
</p>

<h1 align="center">mediaManager</h1>

<p align="center">
  <strong>Your physical media collection, digitized and streamable.</strong><br>
  Catalog DVDs, Blu-rays, UHDs, ebooks, and audio CDs by barcode. Enrich with TMDB, Open Library, and MusicBrainz metadata.<br>
  Watch, read, and listen from any browser, Roku, Android TV, iPhone (incl. CarPlay), or iPad — online or offline.
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

## Platforms

| Surface | Notes |
|---------|-------|
| Web browser | Full feature surface — catalog, playback, admin |
| iOS (iPhone / iPad) | Native SwiftUI app: browse, download for offline, video + ebook + audio playback |
| CarPlay | Music browse + Shuffle Library + AirPods transport, works offline against downloaded albums |
| Android TV / Google TV | Native Compose-for-TV app with D-pad navigation |
| Roku | Sideloaded BrightScript channel driving the same catalog |

## Documentation

| Guide | Audience | Covers |
|-------|----------|--------|
| [Getting Started](docs/GETTING_STARTED.md) | Server admin | Installation, configuration, first launch |
| [User Guide](docs/USER_GUIDE.md) | Everyone | Browsing, searching, watching, personalizing |
| [Admin Guide](docs/ADMIN_GUIDE.md) | Administrators | Catalog management, transcoding, user management, env vars, CLI flags |
| [Android TV Guide](docs/ANDROID_TV_GUIDE.md) | Android TV / Google TV users | Sign-in, browsing, playback on the TV |
| [CarPlay Guide](docs/CARPLAY_GUIDE.md) | iPhone + car users | Browsing, Shuffle Library, AirPods controls |
| [Roku Setup](docs/ROKU_GUIDE.md) | Roku users | Channel installation, pairing, playback |
| [Offline Playback](docs/OFFLINE_PLAYBACK.md) | iOS users + contributors | How downloads + offline browsing + resume work |
| [Transcode Buddy](docs/TRANSCODE_BUDDY.md) | Server admin | Distributed transcoding with GPU acceleration |
| [Generating Subtitles](docs/GENERATING_SUBTITLES.md) | Server admin | Whisper AI subtitle generation setup |
| [iOS Architecture](docs/IOS_ARCHITECTURE.md) | Contributors | SwiftUI app internals — data model, gRPC, caches |
| [Mac Development Setup](docs/MAC_SETUP.md) | Contributors | macOS prerequisites, building, deploying, iOS development |
| [Feature Tracker](docs/FEATURES.md) | Contributors | Completed features history; open items tracked in [GitHub Issues](https://github.com/jeffbstewart/MediaManager/issues) |
| [Competitive Analysis](docs/COMPETITIVE_ANALYSIS.md) | Contributors | Market positioning vs Plex, Jellyfin, CLZ Movies, and others |

## Tech Stack

- **Server:** Kotlin on JDK 21+ (Corretto 25); Armeria hosts the gRPC and HTTP endpoints
- **Web UI:** Angular 21 (TypeScript), served by the same Armeria process
- **API surface:** gRPC over HTTP/2 (10 services, ~130 RPCs) consumed by the web app, iOS, Android TV, and Roku
- **Database:** H2 in file mode, Flyway migrations, HikariCP, jdbi-orm
- **Build:** Gradle 9.3.1 with Kotlin DSL (server); Angular CLI (web)
- **iOS:** SwiftUI on iOS 26, grpc-swift-2, AVFoundation, CarPlay framework
- **Android TV:** Jetpack Compose for TV, ExoPlayer, grpc-kotlin
- **Roku:** BrightScript / SceneGraph
- **Deployment:** Docker on Synology NAS with Watchtower auto-deploy

## License

[MIT License](LICENSE) — Copyright (c) 2026 Jeffrey B. Stewart

Third-party libraries whose source is vendored in this repository are
enumerated in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md), with
the corresponding license texts sitting beside each vendored file.
