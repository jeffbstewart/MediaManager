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
| Inventory Export | Downloadable CSV/PDF valuation report for insurance documentation |
| Ownership Evidence | Mobile photo capture for proof-of-ownership, optional inline photos in PDF report |
| Automated Pricing | Keepa API integration for replacement value estimation via Amazon.com prices |
| Delete Media Item | Cascade delete for incorrectly scanned items with full FK cleanup |
| Override ASIN | Manual ASIN correction for price lookup, accepts Amazon URLs |
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
| Camera Barcode Scanning | Mobile camera scanning via html5-qrcode with continuous scan, audio/visual feedback, duplicate detection |
| Roku Search & Landing Pages | Full-text search on Roku with categorized results, collection/tag/genre/actor landing pages, wishlist from actor page, wish-fulfilled badges |
| Live Camera Streaming | RTSP camera relay via go2rtc, browser MJPEG grid, Roku HLS playback, credential redaction, admin CRUD with blind credential updates |
| Passkey Authentication | WebAuthn/passkey support for Face ID, Touch ID, and hardware security key re-login; standalone terms agreement page; admin passkey management |
| Legal Terms Separation | Decoupled terms acceptance from login flow into dedicated /terms page with server-side 451 enforcement |
| iOS CarPlay | Audio-only CarPlay surface with Albums / Playlists / Smart Playlists tabs, list-row thumbnails, Shuffle Library row, Now Playing hero, MPRemoteCommandCenter wiring for AirPods + lock screen + Control Center, auto-reconnect on stale HTTP/2 streams |
| iOS ebook reader polish | Persist font size across sessions (#67), preserve scroll position across font-size changes (#69), WebView respects bottom safe area to avoid mini-player overlap (#68), TOC dropdown wired to internal link clicks (#70), repaginate on resize |
| iOS offline mode promotion | Sidebar **Offline Mode** toggle moved to the top row (#73); every gRPC call gated at the client layer when offline so cached views stay quiet |
| iOS About page | Build info, server URL, app and server legal links consolidated under Profile &rarr; About (#75) |
| iOS local progress shadow | Resume positions work offline via a local progress store; reconciled to the server when reconnecting (#72) |
| iOS mini-player stability | `safeAreaInset` pinning per NavigationSplitView column, height stays stable through tab loads, no flex on AuthorsView, no flex on LiveStreamView connect |
| iOS poster-card chrome fix | "Not Playable" chrome no longer painted on albums and books (#76) |
| iOS LiveStream close responsiveness | Instant close + press feedback by removing an unbounded HLS-log iteration (#77) |
| iOS CarPlay boot-time population | `AppServices.populate` runs at process boot rather than per view-appear (#74), so the CarPlay scene delegate finds a populated browse hierarchy on first connect |
| Offline-unsupported surfaces hidden | Collections, Tags, Recently Added hidden in iOS offline mode; HomeView populates feed.carousels in offline mode so it isn't blank |
| iOS Downloads sub-pages | Per-category sub-pages and Delete-All with typed confirmation; Sign Out consolidated to Profile; Search promoted to top of sidebar |
| iOS forced password change | ForcedPasswordChangeView asks for the current password before accepting the new one |

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a> &bull;
  <a href="GETTING_STARTED.md">Getting Started</a>
</p>
