# CLAUDE.md

## Project Overview

mediaManager is a Kotlin web application for managing physical media collections (DVD, Blu-ray, UHD, HD DVD). It catalogs titles via UPC barcode scanning, enriches them with TMDB metadata, discovers transcoded media files on a NAS, links them to catalog titles, and provides in-browser video playback via pre-transcoded ForBrowser cache. Built with Vaadin-on-Kotlin for server-side rendered UI (no JavaScript/npm toolchain) with an H2 embedded database.

The history of the app's evolution is recorded in `claude.log` in the project root.

## Working Directory

At the start of every session, `cd` to the project root (`/c/Programming/github/MediaManager`) as your **first Bash command** so all subsequent commands can use relative paths without repeating the `cd` prefix. Do this before any other shell operations on the mediaManager project.

## Build and Run Commands

```bash
./gradlew build          # Build the project (production mode)
./gradlew --no-daemon run            # Run the server (localhost:8080, production mode)
./gradlew test           # Run tests
./gradlew clean build    # Clean and rebuild
./gradlew --no-daemon run -Pvaadin.devMode   # Run with Vaadin dev mode (Vite dev server)
```

**Important:** Always use `--no-daemon` when running the server to avoid Gradle daemon locking problems. Kill existing Java processes first (`taskkill //F //IM java.exe`) if needed.

## Docker Deployment

```bash
./lifecycle/deploy-all.sh         # Stop buddy, build Docker + push + Watchtower, restart buddy
./lifecycle/docker-build.sh       # Build image, tag, push to registry, trigger Watchtower redeploy
```

Use `deploy-all.sh` for changes that affect both the server and the transcode buddy (shared code in transcode-common, buddy code, or server code). It stops the buddy, builds and pushes the Docker image (which triggers Watchtower), then restarts the buddy in the background. Use `docker-build.sh` alone for server-only changes.

### Transcode Buddy Logs

The buddy's stdout/stderr is redirected to `data/buddy.log` (gitignored) to prevent ffmpeg control characters from triggering Windows console beeps. All ffmpeg/whisper process output is also sanitized via `sanitizeFfmpegOutput()` in transcode-common before logging.

```bash
./lifecycle/run-buddy.sh             # Start buddy (output to data/buddy.log)
./lifecycle/stop-buddy.sh            # Stop buddy
./lifecycle/buddy-log.sh             # Last 50 lines of buddy log
./lifecycle/buddy-log.sh -f          # Live follow
./lifecycle/buddy-log.sh 100         # Last 100 lines
```

The `docker-build.sh` script builds the Docker image, tags the previous `latest` as `rollback`,
pushes both the timestamped and `latest` tags to an in-house private docker registry,
and triggers a Watchtower redeploy via HTTP API.

### Watchtower Auto-Deploy

A [Watchtower](https://containrrr.dev/watchtower/) container runs alongside mediamanager on the NAS. It polls for new `:latest` images every 60 seconds and automatically restarts the container when a new image is detected. The HTTP API is also enabled so `docker-build.sh` can trigger an immediate update after pushing (no 60-second wait).

- **Docker socket** (Synology DSM): `/volume1/docker/docker.sock` (mounted to `/var/run/docker.sock` inside Watchtower)
- **HTTP API port**: 16001 on the NAS
- **API token**: stored in `secrets/deploy.agent_visible_env` as `WATCHTOWER_TOKEN`, must match `WATCHTOWER_HTTP_API_TOKEN` in docker-compose on the NAS
- **Trigger endpoint**: `POST http://[IP redacted]:16001/v1/update` with `Authorization: Bearer <token>`
- **Metrics endpoint**: `GET http://[IP redacted]:16001/v1/metrics` (Prometheus format)

The docker-compose Watchtower service on the NAS:
```yaml
watchtower:
  image: containrrr/watchtower
  volumes:
    - /volume1/docker/docker.sock:/var/run/docker.sock
  ports:
    - "16001:8080"
  environment:
    - WATCHTOWER_HTTP_API_UPDATE=true
    - WATCHTOWER_HTTP_API_METRICS=true
    - WATCHTOWER_HTTP_API_TOKEN=<same value as WATCHTOWER_TOKEN in deploy.agent_visible_env>
  command: --interval 60 --cleanup mediamanager
```

### Internal Health/Metrics Server

A separate lightweight Jetty server runs on port 8081 (inside the container) serving only `/health` and `/metrics`. These endpoints are **not** on the main app port (8080), so they are not internet-accessible.

- **Container port**: 8081 (configurable via `--internal_port`)
- **NAS LAN port**: 16002 (mapped in docker-compose as `16002:8081`)
- **Docker healthcheck**: `curl -f http://localhost:8081/health` (inside container)
- **Prometheus target**: `172.16.4.12:16002` (update `prometheus.yml` to scrape this instead of `:16000/metrics`)
- **Endpoints served**: `/health`, `/metrics`, `/admin/logs`, `/admin/requests`

## Architecture

- **UI Framework:** Vaadin 25 via Vaadin-on-Kotlin (VoK) — server-side rendered, no JavaScript
- **UI DSL:** Karibu-DSL for type-safe Kotlin Vaadin component building
- **Server:** Embedded Jetty via vaadin-boot (started from `main()`, no app server deployment)
- **Database:** H2 in file mode (`./data/mediamanager.mv.db`)
- **Connection Pool:** HikariCP
- **Migrations:** Flyway — SQL files in `src/main/resources/db/migration/`, naming convention `V{NNN}__{description}.sql`
- **ORM:** vok-framework-vokdb (jdbi-orm under the hood)
- **Logging:** SLF4J with custom BufferingServiceProvider (stderr + in-memory ring buffers at `/admin/logs`)
- **Build:** Gradle 9.3.1 with Kotlin DSL, version catalog in `gradle/libs.versions.toml`
- **JDK:** Corretto 25 (Java 21+ required by Vaadin 25.x)
- **Package:** net.stewart.mediamanager

### Key Files

**Core:**
- `Main.kt` — Entry point: parses CLI flags, initializes DB, starts agents, launches VaadinBoot
- `Bootstrap.kt` — Database initialization (HikariCP + Flyway), `secrets/.env` loader
- `AppShell.kt` — Vaadin app shell configuration (@Push for server push)

**Views (routes):**
- `MainView.kt` — Root UI (route `/`)
- `AddItemView.kt` — Unified add-item flow: barcode scan, TMDB search, NAS linking, purchase details, photos (route `/add`)
- `ScanView.kt` — Redirects to `/add`
- `ManualEntryView.kt` — Redirects to `/add`
- `CatalogView.kt` — Title catalog with search, filters, enrichment status (route `/catalog`)
- `TranscodeStatusView.kt` — Transcoder status panel, NAS scan (route `/transcodes/status`)
- `TranscodeUnmatchedView.kt` — Unmatched NAS files, linking, suggestions (route `/transcodes/unmatched`)
- `TranscodeLinkedView.kt` — Linked transcodes grid, playback, re-transcode (route `/transcodes/linked`)
- `TranscodeBacklogView.kt` — Catalog titles with no transcodes (route `/transcodes/backlog`)
- `TranscodeRedirectView.kt` — Redirects `/transcodes` to `/transcodes/status`
- `TitleDetailView.kt` — Title detail with transcode/episode grids (route `/title/{titleId}`)
- `PurchaseView.kt` — Media item valuation tracking (route `/valuation`)
- `DocumentOwnershipView.kt` — Mobile photo capture for proof of ownership (route `/document-ownership`)
- `ExpandView.kt` — Multi-pack title expansion (route `/expand`)
- `MainLayout.kt` — Shared AppLayout with nav bar
- `VideoPlayerDialog.kt` — In-browser video player dialog (HTML5 `<video>`)
- `LoginView.kt` — Login page (route `/login`, no MainLayout)
- `SetupView.kt` — First-user setup wizard (route `/setup`, no MainLayout)
- `UserManagementView.kt` — Admin user management (route `/users`)

**Entities (`entity/`):**
- `Title.kt`, `MediaItem.kt`, `MediaItemTitle.kt` — Core catalog domain
- `BarcodeScan.kt` — UPC scan queue
- `Episode.kt` — TV episodes (created from NAS filenames during scan)
- `Transcode.kt` — Links a Title (and optionally Episode) to a file on disk
- `DiscoveredFile.kt` — Staging table for NAS files not yet matched to a Title
- `Genre.kt`, `TitleGenre.kt` — Genre tagging
- `EnrichmentAttempt.kt` — TMDB retry tracking
- `AppConfig.kt` — Key/value app settings (NAS path, FFmpeg path, quota tracking)
- `Enums.kt` — MediaFormat, MediaType, TranscodeStatus, DiscoveredFileStatus, MatchMethod, etc.
- `AppUser.kt` — User accounts with access levels (1=viewer, 2=admin)
- `SessionToken.kt` — Persistent login tokens (30-day cookie sessions)
- `BuddyApiKey.kt` — Bcrypt-hashed API keys for transcode buddy workers (multiple keys, show-once)
- `OwnershipPhoto.kt` — Proof-of-ownership photos linked to media items (stored on disk, metadata in DB)
- `PriceLookup.kt` — Price observations from Keepa API (new/used/Amazon prices, ASIN, raw JSON)

**Services (`service/`):**
- `UpcLookupAgent.kt` — Background daemon: polls for unprocessed scans, calls UPCitemdb API
- `TmdbEnrichmentAgent.kt` — Background daemon: enriches titles via TMDB (search, retry with backoff)
- `NasScannerService.kt` — NAS file discovery, parsing, auto-matching, cleanup orchestration
- `TranscodeFileParser.kt` — Stateless filename parser for movie/TV files (MakeMKV suffixes, year extraction, SxxExx)
- `TranscodeMatcherService.kt` — Stateless title matching (exact + normalized with article/punctuation stripping)
- `TitleCleanerService.kt` — Strips UPC marketing text, normalizes trailing articles, generates sort names
- `MultiPackDetector.kt` — Detects multi-title products (Double Feature, Trilogy, slash-separated)
- `SeasonDetector.kt` — Extracts season info from product names
- `TranscoderAgent.kt` — Background daemon: pre-transcodes MKV/AVI to MP4 under ForBrowser/ mirror, prioritized by TMDB popularity
- `Broadcaster.kt` — Thread-safe event bus for server-push UI updates (scan, title, NAS progress, transcoder events)
- `QuotaTracker.kt` — Daily UPC API quota tracking (100/day)
- `PosterCacheService.kt` — Local disk cache for TMDB poster images
- `TmdbService.kt` — TMDB API client (search, multi-result, detail fetch)
- `UpcLookupService.kt` — UPCitemdb API client + mock implementation
- `SchemaUpdater.kt` — Framework for programmatic data updates (interface + runner); runs after Flyway migrations
- `PopulatePopularityUpdater.kt` — Backfills TMDB popularity scores for existing enriched titles
- `PopularityRefreshAgent.kt` — Background daemon: gradually refreshes TMDB popularity for titles and cast members (~1%/day, full cycle ~100 days)
- `CollectionRefreshAgent.kt` — Background daemon: gradually re-fetches TMDB collection data (parts, poster paths, new entries) (~1%/day, full cycle ~100 days)
- `ManagedDirectoryService.kt` — Ensures managed NAS directories (ForBrowser/) exist with `.mm-ignore` markers
- `FormatProbeService.kt` — Background FFprobe-based media format detection (resolution → DVD/Blu-ray/UHD)
- `Clock.kt` — Clock interface for testable time
- `PasswordService.kt` — BCrypt password hashing and verification
- `AuthService.kt` — Central auth coordinator (login, session management, cookie validation, token cleanup)
- `BuddyKeyService.kt` — Buddy API key management (create with bcrypt hash, validate, delete)
- `RokuFeedService.kt` — Builds Roku-compatible JSON feed from enriched titles with playable transcodes
- `OwnershipPhotoService.kt` — Store/retrieve/delete proof-of-ownership photos (disk files at `data/ownership-photos/`)
- `KeepaService.kt` — Keepa API client (batch ASIN lookup, UPC lookup, title search) + MockKeepaService + PriceSelectionService
- `PriceLookupAgent.kt` — Background daemon: prices media items via Keepa (Amazon.com US), configurable token rate
- `MediaItemDeleteService.kt` — Cascade delete for MediaItem and orphaned Titles

**Security:**
- `SecurityServiceInitListener.kt` — VaadinServiceInitListener enforcing route-level authentication and authorization
- `AuthFilter.kt` — Servlet filter protecting `/posters/*`, `/headshots/*`, `/stream/*` endpoints

**Servlets:**
- `PosterServlet.kt` — `/posters/{size}/{titleId}` — serves cached TMDB poster images
- `VideoStreamServlet.kt` — `/stream/{id}` — video streaming with HTTP Range support; serves MP4/M4V directly, MKV/AVI from ForBrowser mirror
- `RokuFeedServlet.kt` — `/roku/feed.json?key={apiKey}` — Roku channel JSON feed (device token auth, 5-minute cache)
- `BuddyApiServlet.kt` — `/buddy/*` — REST API for transcode buddy workers (bcrypt API key auth)
- `OwnershipPhotoServlet.kt` — `/ownership-photos/{uuid}` — serves proof-of-ownership photos (supports `?download=1` for attachment)
- `HealthServlet.kt` — `/health` — health check (internal port only)
- `MetricsServlet.kt` — `/metrics` — Prometheus metrics (internal port only)
- `AppLogServlet.kt` — `/admin/logs` — in-memory application log viewer (internal port only)
- `RequestLogServlet.kt` — `/admin/requests` — HTTP request log viewer (internal port only)

**Shared Components:**
- `OwnershipPhotoPanel.kt` — Reusable panel for camera capture + photo strip with delete. Used by AddItemView and DocumentOwnershipView.

**Other:**
- `secrets/example.env` — Template for required environment variables (TMDB API key)
- `src/main/resources/webapp/ROOT` — Marker file required by vaadin-boot
- `src/main/resources/webapp/html5-qrcode.min.js` — html5-qrcode v2.3.8 (Apache 2.0 license), client-side barcode detection for mobile camera scanning. Bundled locally to avoid CDN dependency. Source: https://github.com/mebjas/html5-qrcode
- `src/main/resources/db/migration/` — Flyway SQL migration files (V001–V018)
- `build.gradle.kts` — Build config
- `gradle/libs.versions.toml` — Dependency version catalog

### Database

H2 stores its file at `./data/mediamanager.mv.db` (in the `data/` directory, which is gitignored). Flyway manages schema migrations via SQL files in `src/main/resources/db/migration/`, applied in lexicographic order on startup. Flyway tracks applied migrations in its `flyway_schema_history` table and checksums each file to detect tampering.

**Encryption at rest:** When `H2_FILE_PASSWORD` is set, the database uses AES encryption (`CIPHER=AES`). On first startup with the env var, Bootstrap automatically exports the unencrypted DB to SQL, backs up the original file (`.mv.db.pre-encryption`), creates a new encrypted DB, and reimports. The JDBC URL becomes `jdbc:h2:file:./data/mediamanager;CIPHER=AES` and the HikariCP password is the compound `"filePassword userPassword"` format. Once encrypted, `H2_FILE_PASSWORD` is required on every startup.

### External APIs

- **UPCitemdb** — Free trial tier, no API key required. Per-IP throttling: 6 requests/minute, 100/day. Used for barcode-to-product lookup.
- **TMDB (The Movie Database)** — Requires API key via `secrets/.env` file (`TMDB_API_KEY`). Used for canonical title names, poster images, release years, and descriptions. A TMDB API key is required for a functional catalog — without it, there are no poster images, cast data, descriptions, or popularity sorting.
  - **IMPORTANT: TMDB ID namespaces are separate for movies and TV shows.** A movie and a TV show can share the same integer ID (e.g., movie 253 = "Live and Let Die", TV 253 = "Star Trek"). Any lookup, dedup, or comparison involving `tmdb_id` **must also consider `media_type`**. Failing to do so causes cross-type collisions — titles silently reused, wishes fulfilled for the wrong type, etc. Always pair `tmdb_id` with `media_type` in all queries and set operations.
- **Keepa** — Amazon price tracking API. Requires paid subscription (minimum 19 EUR/month for basic API access, 49 EUR/month for practical throughput). API key stored in `app_config` (`keepa_api_key`). Used for automated replacement value estimation. **Amazon.com (US, domain=1) only** — other Amazon marketplaces not currently supported. Token-based rate limiting configured via `keepa_tokens_per_minute` in Settings.

### NAS Integration

Transcoded media files live on a NAS or local storage. The root path is configured via `app_config` key `nas_root_path` (set in Settings, or via `MM_NAS_ROOT` environment variable). In Docker, this maps to the media volume mount.

**Directory auto-classification:** The NAS scanner classifies each top-level subdirectory by structure, not by name:
- **Flat** (media files directly in the folder) → Movies
- **Nested** (media files inside subdirectories) → TV shows
- `SxxExx` patterns in filenames override to TV regardless of depth

**Directory exclusion:** Drop a `.mm-ignore` marker file in any directory to exclude it from scanning. MediaManager auto-creates `.mm-ignore` in managed directories (`ForBrowser/`) on startup.

**Media format detection:** Format (DVD, Blu-ray, UHD) is determined by FFprobe resolution analysis during a background probe phase, not by directory name. Files show as `UNKNOWN` until probed.

A mass-deletion guard (`--max_transcode_deletes`, default 25) prevents cleanup from false positives during NAS outages.

### Video Playback

Two playback modes, both accessible from play buttons in TranscodeView and TitleDetailView:

**In-Browser (all clients):** HTML5 `<video>` element in a `VideoPlayerDialog`. The `VideoStreamServlet` (`/stream/{id}`) handles streaming:
- **MP4/M4V** — streamed directly from source with full HTTP Range support (seeking)
- **MKV/AVI** — served from pre-transcoded ForBrowser mirror (`{nas_root}/ForBrowser/`); play button only appears when the transcoded file exists. Returns 404 if not yet transcoded.
- **Background Transcoder** (`TranscoderAgent`) — daemon that batch-transcodes MKV/AVI files to browser-compatible MP4, one at a time, prioritized by TMDB popularity (most popular first)
  - Codec-aware: probes source video codec before transcoding
  - H.264 sources: `ffmpeg -c:v copy` (fast, copies video as-is)
  - HEVC/MPEG-2/other sources: `ffmpeg -c:v libx264 -preset medium -crf 18` (re-encodes to H.264 for browser compatibility — slower but necessary)
  - Audio always transcoded: `-c:a aac -b:a 192k`
  - On startup, validates existing ForBrowser files and deletes any with non-browser-safe codecs for re-transcoding
  - Writes to `.tmp`, renamed to `.mp4` on completion (atomic swap)
  - Path mirroring: `{nas_root}/BLURAY/Movie.mkv` → `{nas_root}/ForBrowser/BLURAY/Movie.mp4`
  - Status panel in TranscodeView shows live progress, completion counts, recent transcodes
  - **Roku-compatible output requirements** (verified working on Roku):
    - Video: H.264 High or Constrained Baseline profile, yuv420p, progressive scan, SAR 1:1
    - Audio: AAC-LC stereo, 44100 or 48000 Hz, 128–192 kbps
    - Container: MP4 with `+faststart` (moov atom at start for streaming)
    - Stream selection: `-map 0:v:0 -map 0:a:0 -dn -map_chapters -1` (single video + single audio, no data/chapter streams)
    - Re-encode flags: `-c:v libx264 -preset medium -crf 18 -profile:v high -level 4.1 -pix_fmt yuv420p -vf setsar=1 -r <source_fps>`
    - NVENC flags: `-c:v h264_nvenc -preset p7 -rc vbr -cq 19 -b:v 0 -profile:v high -level:v 4.1 -pix_fmt yuv420p -vf setsar=1 -r <source_fps> -rc-lookahead 32 -bsf:v "filter_units=remove_types=6"`
    - Copy flags (H.264 sources): `-c:v copy` (video passed through as-is)
    - Audio flags: `-c:a aac -b:a 192k -ar 48000 -ac 2`
    - **SEI NAL stripping** (`-bsf:v "filter_units=remove_types=6"`): Required for NVENC/QSV output. These GPU encoders emit extra SEI NAL units (type 6) that Roku's strict H.264 decoder rejects. Harmless for libx264 (just removes optional info). Applied whenever re-encoding (not copy).
    - **Interlace handling**: DVD sources may be interlaced. Detected via FFprobe ("top first"/"bottom first"). Deinterlaced with `-vf yadif` (placed before any scale filters).
    - **Anamorphic SAR**: DVD sources may have non-square pixels (e.g., 720x480 SAR 32:27). Scaled to square pixels with `-vf scale=iw*sar:ih,setsar=1:1`.
  - **Verified working FFmpeg commands** (tested on Roku with Star Trek TOS S02E10, MPEG-2 DVD source 720x480 SAR 32:27):
    - CPU (libx264): `ffmpeg -i input.mkv -map 0:v:0 -map 0:a:0 -dn -pix_fmt yuv420p -c:v libx264 -preset medium -crf 18 -level:v 4.1 -vf scale=iw*sar:ih,setsar=1:1 -r 29.970 -bsf:v "filter_units=remove_types=6" -c:a aac -ac 2 -ar 48000 -b:a 192k -map_chapters -1 -movflags +faststart -threads 0 -f mp4 -y output.mp4`
    - GPU (h264_nvenc): `ffmpeg -i input.mkv -map 0:v:0 -map 0:a:0 -dn -pix_fmt yuv420p -c:v h264_nvenc -preset p7 -rc vbr -cq 19 -b:v 0 -profile:v high -rc-lookahead 32 -level:v 4.1 -vf scale=iw*sar:ih,setsar=1:1 -r 29.970 -bsf:v "filter_units=remove_types=6" -c:a aac -ac 2 -ar 48000 -b:a 192k -map_chapters -1 -movflags +faststart -threads 0 -f mp4 -y output.mp4`
- FFmpeg path configurable via `app_config` key `ffmpeg_path` (default `C:\ffmpeg\bin\ffmpeg.exe`, set in Transcodes > Settings)

### Roku Integration

A JSON feed endpoint at `/roku/feed.json?key={apiKey}` serves the media catalog for consumption by a Roku channel. This is the server-side component; a custom BrightScript/SceneGraph channel (Phase 2) fetches this feed and renders the UI on the Roku device.

**Feed endpoint:** `RokuFeedServlet` at `/roku/feed.json` — validates the `key` query parameter against `roku_api_key` in `app_config`, returns 401 if invalid. Responds with `Cache-Control: public, max-age=300` (5-minute cache).

**Feed content:** `RokuFeedService` builds a JSON feed containing:
- `movies[]` — enriched, non-hidden titles with `media_type=MOVIE` and at least one playable transcode
- `series[]` — enriched, non-hidden TV titles grouped by season/episode, each episode with a playable transcode
- Each entry includes: poster URL, stream URL, description, release year, genres, top 5 cast credits
- Poster/stream URLs embed the API key for auth: `/posters/w500/{id}?key=xxx`, `/stream/{id}?key=xxx`
- Playability: MP4/M4V are always playable; MKV/AVI only if a ForBrowser transcoded copy exists on disk
- Quality tier: UHD_BLURAY → "UHD", DVD → "SD", all others → "FHD"

**Authentication:** The API key (auto-generated UUID, stored as `roku_api_key` in `app_config`) authenticates all Roku requests. `AuthFilter` accepts `?key=` as a fallback when no cookie is present, so Roku devices can access `/stream/*` and `/posters/*` endpoints. The key can be regenerated from Transcodes > Settings.

**Architecture note:** Roku Direct Publisher was sunset in January 2024. The feed format follows the original Roku DP JSON spec (https://developer.roku.com/docs/specs/direct-publisher-feed-specs/json-dp-spec.md) because it's a well-structured media catalog format, but the feed is consumed by a custom sideloaded BrightScript channel, not by the defunct Direct Publisher service. For personal use, sideloading via Developer Mode has no expiration and requires no Roku certification review.

### Schema Updater Framework

Programmatic data updates that need Kotlin code (API calls, computation). Flyway handles DDL; SchemaUpdaters handle data population. Tracked in `schema_updater` table (name PK, version, applied_at). Bumping an updater's version re-triggers it on next startup. Runner called from Bootstrap.init() after Flyway migrations. Current updaters:
- `populate_popularity` (v1) — backfills TMDB popularity scores for existing enriched titles

### CLI Flags

| Flag | Default | Purpose |
|------|---------|---------|
| `--developer_mode` | off | Enables H2 web console |
| `--port N` | 8080 | HTTP listen port |
| `--h2_console_port N` | 8082 | H2 web console port (developer mode) |
| `--listen_on_all_interfaces` | off | Bind to 0.0.0.0 instead of 127.0.0.1 |
| `--max_transcode_deletes N` | 25 | Mass-deletion guard threshold for NAS cleanup |
| `--disable_local_transcoding` | off | Skip starting the local TranscoderAgent |
| `--internal_port N` | 8081 | Port for internal-only health/metrics server |

### Decisions Made

- **Vaadin-on-Kotlin over Ktor+Angular/JS** — eliminates npm/node/webpack entirely. UI is rendered server-side; browser receives DOM diffs via WebSocket.
- **H2 over SQLite** — native Java, no JNI/native wrappers, fully supported by VoK/Flyway/HikariCP ecosystem. File-mode gives same single-file-DB experience.
- **Flyway for migrations** — standard Java migration tool, auto-creates tracking table, checksums applied migrations.
- **Gradle version catalog** (`libs.versions.toml`) — centralizes dependency versions.
- **Gradle 9.3.1 over 8.12** — Java 25 support requires Gradle 9.1+; 8.x cannot parse version "25.0.2".
- **discovered_file staging table** — `transcode.title_id` is NOT NULL, so unmatched NAS files park in `discovered_file` until linked (auto or manual), then a `Transcode` record is created.
- **Stateless parser/matcher** — `TranscodeFileParser` and `TranscodeMatcherService` are pure-logic objects with no DB or I/O dependencies, fully unit-testable.
- **In-browser playback** — HTML5 `<video>` allows any network client to watch. MKV files are pre-transcoded to ForBrowser mirror; MP4 files stream directly.
- **Background transcoder over on-the-fly remux** — Pre-transcoding in the background avoids interactive wait times. Files are transcoded in TMDB popularity order (most popular first). Play button only appears when the ForBrowser MP4 exists.
- **FFmpeg remux (copy video, transcode audio)** — `-c:v copy` avoids slow video re-encoding; `-c:a aac` converts AC3/DTS to browser-compatible audio. `-movflags +faststart` places the moov atom at the start for immediate seeking.
- **Path-mirrored ForBrowser cache** — `{nas_root}/ForBrowser/` mirrors the source directory structure.
- **Schema updater framework** — Flyway handles DDL only; SchemaUpdater interface + runner handles programmatic data updates (API calls, computation) with version tracking for re-runnability.
- **Roku JSON feed over Direct Publisher** — Direct Publisher was sunset January 2024. Server provides a JSON feed at `/roku/feed.json` consumed by a custom sideloaded BrightScript channel. Feed format follows the original Roku DP JSON spec for structure, but is served to our own channel code. API key auth (UUID in `app_config`) for stateless device authentication.
- **Sideloaded Roku channel over beta channel** — Beta channels expire after 120 days. Sideloading via Developer Mode has no expiration and requires no certification review. Only one sideloaded channel per device, but that's fine for personal use.

## Coding Rules

- **Always use `Clock.sleep()` instead of `Thread.sleep` directly.** The `Clock` interface (in `service/Clock.kt`) abstracts time operations for testability. All production code should inject `clock: Clock = SystemClock` and call `clock.sleep(duration)`. Direct `Thread.sleep` calls make code untestable.

## Philosophy

**"Debugging sucks, Testing Rocks."** — Write integration and unit tests before unleashing code on real data. If something can be tested with mocked dependencies and an in-memory database, it should be.

## Security

- Claude must **never read files with the `.env` extension** in `secrets/` (contains real API keys and passwords)
- Claude **may read `.agent_visible_env` files** in `secrets/` (test credentials, non-secret config)
- Values from `secrets/.env` must **never be committed to source control or logged**
- Use `secrets/example.env` as the template for documenting required environment variables

### User Authentication

Two access levels enforce role-based access:

| Level | Role | Can Access |
|-------|------|------------|
| 1 | Viewer | Home, Catalog, Title Detail, Actor, Search, Playback |
| 2 | Admin | Everything Viewer can + Scan, Purchases, Expand, Transcodes, Users |

**Routes:** `SecurityServiceInitListener` enforces Vaadin route access. Public routes (`login`, `setup`) are always accessible. Admin routes (`scan`, `purchases`, `expand`, `transcodes`, `users`) require Level 2. All other routes require any authenticated user.

**Servlets:** `AuthFilter` protects `/posters/*`, `/headshots/*`, `/stream/*` via cookie token validation against `session_token` table. Falls back to API key auth (`?key=` parameter validated against `roku_api_key` in `app_config`) for Roku device access. Returns 401 for unauthenticated requests. Allows all through when no users exist (pre-setup).

**First-user setup:** When no users exist in `app_user`, all navigations redirect to `/setup` where the first account (always admin) is created.

**Sessions:** 30-day persistent login via `mm_session` cookie mapped to `session_token` DB rows. Cookie set via client-side JS. Expired tokens cleaned on startup.

**Testing:** `secrets/test-credentials.agent_visible_env` (gitignored) contains test account credentials for automated UI and API testing. Claude may read this file. Copy from `secrets/example.test-credentials.env` and create the test accounts manually after first setup.

## Version Control

This repository uses **git** hosted on GitHub. See `.gitignore` for excluded files and directories.

### Commit Message Style

Commit messages follow this format:

```
Short summary line (imperative mood, ~50 chars)

Body paragraph(s) describing what changed and why. Wrap at ~72 chars.
Use bullet points with `-` for lists of changes. Include context like
"replaces X with Y" or "adds X for Y purpose".

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
```

- **First line**: imperative verb ("Add", "Fix", "Replace", "Redesign"), concise summary
- **Body**: explain the what/why, list notable changes with `-` bullets
- **Co-author trailer**: always include when Claude authored or co-authored the change
- No need to check `git log` for style — just follow this format

### Presubmit Check

A git pre-commit hook scans staged changes for sensitive data (hardcoded IPs, UUIDs, API keys, etc.). If the commit is rejected, fix the violations or add known-safe values to `lifecycle/presubmit-allowlist.txt`.

### Documentation Check Before Commit

Before each commit, consider whether the changes require documentation updates. Check:

- **CLAUDE.md** — Does a new flag, entity, service, servlet, or architectural change need to be reflected here?
- **README.md** — Does the user-facing overview need updating?
- **docs/** — Do the Admin Guide, Getting Started, Transcode Buddy, Roku Guide, User Guide, or architecture diagram (index.md) need updating?
- **docs/FEATURES.md** — Should a feature request be marked done, or a new one added?
- **docker-compose.yml** — Do port mappings, environment variables, or healthchecks need updating?
- **claude.log** — Has this session's work been logged?

If documentation updates are needed, include them in the same commit or a follow-up commit before moving on.

## Roku Debugging

Three helper scripts in `lifecycle/` support Roku channel development. All read `ROKU_IP` and `ROKU_DEV_PASSWORD` from `secrets/roku-deploy.env` (gitignored; copy from `secrets/example.roku-deploy.env`).

### Deploy Channel

```bash
./lifecycle/roku-deploy.sh          # Zip roku-channel/, sideload to Roku via HTTP digest auth
```

After deploy, the channel auto-launches on the Roku. Verify installation:
```bash
./lifecycle/roku-remote.sh apps     # List installed apps (look for "dev" entry)
```

### Debug Console

The Roku debug console (port 8085) accumulates all BrightScript `print` output since the channel launched. The `roku-debug.sh` script connects via ncat and streams output to both the terminal and a local file via `tee` (overwrites each run, no `-a`). The connection is fragile — ncat frequently drops, so don't rely on it staying connected. The local log file (`data/roku-debug.log`) is **always stale** — it only contains output captured during the last script run. Never read the local file and assume it reflects current Roku state.

```bash
./lifecycle/roku-debug.sh                                  # Stream to terminal + data/roku-debug.log
./lifecycle/roku-debug.sh prod                             # Connect to prod Roku
./lifecycle/roku-debug.sh dev data/custom-output.log       # Custom log file name
```

Always write debug logs to the `data/` directory (gitignored) to avoid cluttering the project root.

All mediaManager channel logs use the `[MM]` prefix. Filter with:
```bash
grep '\[MM\]' data/roku-debug.log
```

### Build Timestamp Verification

Each deploy stamps a `build_timestamp` into the manifest (format `YYYYMMDD-HHMMSS`, e.g., `20260313-075327`). The deploy scripts (`roku-deploy-dev.sh`, `roku-deploy-prod.sh`) inject this into the manifest before zipping and remove it after, so it only appears in the deployed package. The channel logs it at startup:
```
[MM] main: starting Media Manager v2.0.0 (build 20260313-075327)
```
**Always check this line** after fetching debug logs to confirm you are reading output from the expected build, not a stale session.

### Roku Debug Workflow

Follow this exact sequence when debugging Roku channel behavior. Do NOT skip steps or read stale logs.

1. **Delete the local debug log** — `rm -f data/roku-debug.log` — ensures no stale data contaminates results
2. **Deploy the channel** — `./lifecycle/roku-deploy-dev.sh` (or `-prod.sh`) — stamps build timestamp, zips, sideloads; channel auto-launches on the Roku
3. **Perform the user action** — navigate menus, play content, trigger the behavior under test. Use `./lifecycle/roku-remote.sh` for ECP keypresses if needed, or ask the user to perform the action manually
4. **Fetch debug logs** — `./lifecycle/roku-debug.sh` — connects to port 8085, captures the console buffer to `data/roku-debug.log`, connection will drop on its own
5. **Inspect logs** — `grep '\[MM\]' data/roku-debug.log` — verify the build timestamp matches the deploy (step 2), then analyze the relevant log lines

**Critical:** Steps must be sequential. The log file captures output printed since the channel launched. If you fetch logs before the user action, you won't see the relevant output. If you skip step 1, you may confuse old output with new.

**Important:** This Windows environment does not have `nc` (netcat). Use `ncat` (from Nmap, installed at `/c/Program Files (x86)/Nmap/ncat`) instead. The roku-debug.sh script uses `ncat` for this reason.

**Roku audio requirement:** The Roku's **Settings > Audio > Digital Output Format** must be set to **Stereo** (not "Auto"). When set to "Auto", the Roku attempts to negotiate audio formats with the TV, which causes the Video node to play video without audio while system/UI navigation sounds still work. This is a device-level setting, not a channel code issue.

**Debug ports:**
| Port | Protocol | Purpose |
|------|----------|---------|
| 8080 | HTTP | Roku installer web UI |
| 8085 | Telnet | BrightScript debug console (`print` output) |
| 8087 | Telnet | SceneGraph debug console (node inspector) |
| 8089 | Telnet | SceneGraph profiler |
| 8060 | HTTP | External Control Protocol (ECP) |

### Remote Control via ECP

**Prerequisite:** The Roku must have ECP keypress enabled: **Settings > System > Advanced system settings > Control by mobile apps > Network access** set to **"Enabled"** (default is "Limited", which returns HTTP 403 for keypress commands). Query endpoints like `active-app` and `device-info` work regardless.

```bash
./lifecycle/roku-remote.sh                # Press Select (OK)
./lifecycle/roku-remote.sh Home           # Press Home
./lifecycle/roku-remote.sh Back           # Press Back
./lifecycle/roku-remote.sh Up             # Navigation keys: Up, Down, Left, Right
./lifecycle/roku-remote.sh launch         # Launch the sideloaded dev channel
./lifecycle/roku-remote.sh apps           # List installed apps (XML)
./lifecycle/roku-remote.sh active         # Show active app
./lifecycle/roku-remote.sh info           # Device info
./lifecycle/roku-remote.sh player         # Media player state (playing/paused/stopped)
./lifecycle/roku-remote.sh type "text"    # Type text into a Roku keyboard dialog
```

**Key names:** `Home`, `Rev`, `Fwd`, `Play`, `Select`, `Left`, `Right`, `Down`, `Up`, `Back`, `InstantReplay`, `Info`, `Backspace`, `Search`, `Enter`, `VolumeDown`, `VolumeUp`, `VolumeMute`

## Screenshots

Save all Playwright screenshots to `data/screenshots/` (e.g., `data/screenshots/navbar-test.png`). The `data/` directory is gitignored so screenshots never enter version control.

## MCP Tools

The Playwright MCP server is available for browser automation and visual verification. Use it to:
- Navigate to the running app (`http://localhost:8080/`)
- Take snapshots (accessibility tree) and screenshots (visual)
- Click elements, fill forms, and verify navigation
- Run arbitrary Playwright code for complex interactions

**Login persistence:** The browser session persists across Playwright launches. The default state when opening the browser is **already logged in** as the admin test user. You do not need to navigate to `/login` or enter credentials at the start of each session — just navigate directly to the page you want to test.

Console logs are stored in `.playwright-mcp/` (gitignored).

## Conversation Transcript

All Claude Code conversations for this project must be logged to `claude.log` in the project root. Log **every substantive exchange** — not just at session end, but as the conversation progresses. Each entry must include a date and timestamp. Format:

```
=== YYYY-MM-DD HH:MM — <brief topic> ===
- What was discussed
- What was decided
- What was changed (files created/modified/deleted)
===
```

This log serves as a persistent project history across sessions. Always append; never overwrite.
