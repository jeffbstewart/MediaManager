<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Feature Tracker

All open features, bugs, and security items have been migrated to
[GitHub Issues](https://github.com/jeffbstewart/MediaManager/issues).
See the completed features table below for historical record.

---

# Completed Features

Previously completed features, listed in implementation order. Details removed;
see git history for full specifications.

| Feature | Summary |
|---------|---------|
| Manage Submenu | Admin nav items consolidated into "Manage" dropdown |
| Authentication | User login with bcrypt-hashed passwords, session management |
| Authorization | Route-level and servlet-level auth checks, admin gating |
| First-User Setup | `/setup` wizard for initial admin account creation |
| Docker Packaging | Multi-stage Dockerfile, docker-compose, Synology deployment |
| Wish Lists | Per-user media and transcode wish lists with priority transcoding |
| Security Hardening | TLS, rate limiting, input validation, session policies |
| Roku App | BrightScript channel with feed, playback, QR pairing |
| Request Log | In-memory HTTP request log at `/admin/requests` |
| App Log | In-memory application log at `/admin/logs` |
| Roku Debug Logging | `[MM]`-prefixed print statements throughout channel code |
| Roku Deploy | `roku-deploy.sh` sideloading automation |
| Roku Debug Tailing | `roku-debug.sh` for streaming debug console output |
| Roku Transcoding | Roku-compatible MP4 output (SAR, frame rate, SEI, deinterlace) |
| HTTP Streaming | Full-range 206 Partial Content with proper headers |
| Transcode Buddy | Distributed GPU transcoding via REST API lease system |
| Prometheus Metrics | `/metrics` endpoint with Micrometer + JVM binders |
| Expand View Enter Key | Enter key triggers search in Expand view |
| Audit Endpoints | Verified no non-admin endpoints accept client-supplied user IDs |
| Manual Title Entry | Add titles via TMDB search without UPC barcode |
| Amazon Import | Bulk import purchase dates/prices from Amazon order CSV |
| Similar Titles | Genre, cast, and TMDB recommendation-based similarity scoring |
| Content Ratings | MPAA/TV ratings with per-user ceiling filtering |
| Video Thumbnails | Sprite sheet generation + WebVTT seek preview on progress bar |
| Auto-Play Next | "Up Next" prompt with countdown near end of TV episodes |
| Filter Credits | Hide incidental TV appearances from actor "Other Works" |
| User Tags | Admin-managed colored tags, star (priority transcode), personal hide |
| Playback Progress | Per-user resume, continue watching, viewed flags, Roku sync |
| Enhanced Search | In-memory inverted index with phrases, negation, tag operators |
| Inventory Export | Downloadable CSV valuation report for insurance documentation |
| Bulk Tag Seeding | Auto-create genre tags from TMDB data via SchemaUpdater |
| TMDB Wish Search | Search TMDB directly from wish list page |
| Missing Seasons | Detect unowned TV seasons, home page notifications, season wishes |
| Typed TMDB ID | `TmdbId` data class pairing integer ID with media type namespace |
| Security Audit | Address/accept all findings from 2026-03-04 security audit |
| Continue Watching | Home screen rows, progress bars, resume indicators, Roku sync |
| Roku QR Pairing | SSDP discovery + QR code phone-mediated device pairing |
| Device Token Lifecycle | Permanent tokens with revocation on password change |
| Active Sessions | User-facing session list with revocation, admin view |
| Dual-Location Sprites | Thumbnail sprites copied to both ForBrowser and source dirs |
| Roku Subtitles | Per-user subtitle preference, SRT tracks in Roku feed |
| README & Docs | Architecture diagram, prerequisites, documentation site |
| Remove Roku API Key | Dropped `roku_api_key` from DB, UI, and auth; device tokens only |
| Scrub Sensitive Data | Removed hardcoded IPs, leaked keys, and secrets from all tracked files |
| Remove Catalog Tags | Removed franchise and decade tag auto-population; genre tags only |
| Family Videos | Personal video management with family member tagging, hero image extraction, event dates |
| Browse View | Poster grid at `/catalog` with sort, filter chips, tag chips, playable toggle |
| Data Quality View | Admin enrichment triage at `/data-quality` with status badges and filters |
| Tag Browsing | Tag chips in browse view for OR-mode filtering by tag |
| Smart Tag Association | Auto-associate tags from TMDB genres and collections on enrichment |
| Collection Sort Names | Auto-generate sort keys from TMDB collection order (e.g., "Back to the Future Collection 001") |
| Probe-Based Format Detection | FFprobe resolution analysis replaces directory-name format inference; `.mm-ignore` marker files; auto-classify TV vs Movie by directory structure (r404) |
| Feature Tracker Migration | Migrated all open items from FEATURES.md to GitHub Issues with project-specific labels |

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a> &bull;
  <a href="GETTING_STARTED.md">Getting Started</a>
</p>
