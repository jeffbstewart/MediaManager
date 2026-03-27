# iOS Feature Parity Gap Analysis

Last updated: 2026-03-27

## Status: Phases 1-5 Complete

All 10 missing features have been implemented. The iOS app now has full admin parity
with the web UI. See execution plan below for details.

This document tracks features available in the web UI that are missing from the iOS app,
along with a phased execution plan to close those gaps.

## Current iOS Coverage

The iOS app already covers the core user experience:

- **Browsing:** Home feed, Movies, TV Shows, Collections, Tags, Family, Genres
- **Playback:** Streaming, offline downloads, subtitles, chapters, skip segments, resume
- **Search:** Full-text across titles, actors, collections, tags, genres
- **Wish List:** Add/cancel/vote/dismiss wishes, transcode request tracking
- **Cameras:** Live stream viewing, snapshot previews
- **Live TV:** Channel list with quality filtering, HLS playback
- **Profile:** Password change, sessions, TV quality, legal docs
- **Admin — Scanning:** Barcode scan (camera + BT), TMDB search/assign, purchase info, ownership photos
- **Admin — Transcodes:** Real-time status monitoring, buddy health, NAS scan trigger
- **Admin — Cameras:** CRUD with drag-reorder
- **Admin — Users:** Create, role toggle, rating ceiling, unlock, password reset, delete
- **Admin — Tags:** CRUD with color picker
- **Admin — Data Quality:** Review, re-enrich, delete
- **Admin — Unmatched Files:** Review, accept, ignore, link
- **Admin — Settings:** Core server config (NAS path, Roku URL, personal video, Keepa, buddy keys)
- **Admin — Purchase Wishes:** Status lifecycle (Ordered/Needs Assistance/Not Available/Rejected/Owned)

## Missing Features

### 1. First-User Setup

**Web:** `/setup` — When no users exist, all routes redirect to a setup page where the first
admin account is created (username, password, display name).

**iOS gap:** The app shows `ServerSetupView` (enter server URL) and `LoginView` (sign in), but
has no flow for creating the initial admin account on a fresh server. A user must use the web UI
to create the first account before the iOS app is usable.

**gRPC status:** No setup RPC exists. Need a new `Setup` RPC (or extend AuthService) that creates
the first user when `app_user` is empty. Must refuse to operate if any user already exists.

### 2. Add Title via TMDB Search (Standalone)

**Web:** `/add` Search tab — Admin can search TMDB by name, select a result, specify season count
for TV shows, and add it directly to the catalog without a barcode.

**iOS gap:** TMDB search exists in the scan detail context (assign a TMDB match to a scanned barcode),
but there's no standalone "add a title from TMDB" flow. An admin who wants to add a title they don't
physically have a barcode for must use the web UI.

**gRPC status:** `SearchTmdb` RPC exists. Need an `AddTitle` or `AddFromTmdb` RPC that creates a
catalog entry from a TMDB ID + media type + optional season count.

### 3. Amazon Order Import

**Web:** `/import` — Upload an Amazon order history CSV. Parses order lines, matches to catalog
titles by name/UPC, records purchase dates and prices. Bulk operation.

**iOS gap:** No import flow at all.

**gRPC status:** No import RPC exists. Need `ImportAmazonOrders` RPC accepting CSV content (or
parsed order lines) and returning match results.

### 4. Expand Multi-Packs

**Web:** `/expand` — When a purchase contains a multi-title pack (e.g., "Indiana Jones 4-Movie
Collection"), this view splits it into individual catalog entries. Shows pending expansions,
lets admin assign individual titles to pack members.

**iOS gap:** No expand flow.

**gRPC status:** No expand RPCs exist. Need `ListPendingExpansions` and `ExpandPack` RPCs.

### 5. Media Item Edit

**Web:** `/item/:mediaItemId` — Edit physical media item details: format (DVD/Blu-ray/UHD/HD DVD),
condition, notes, link/unlink from catalog titles, reassign TMDB match.

**iOS gap:** No media item editing. Title detail shows metadata but doesn't allow editing physical
media attributes or relinking.

**gRPC status:** Partial — `UpdateTitleMetadata` exists but is limited (name, description, year).
Need broader admin RPC for physical media item editing (format, condition, notes, TMDB reassignment).

### 6. Valuation & Keepa Price Lookup

**Web:** `/valuation` — Browse titles with purchase prices, trigger Keepa lookups for current
Amazon market value, view price history. Used for insurance valuation.

**iOS gap:** No valuation view. Purchase prices can be set via scan detail, but there's no
browse/bulk view or Keepa lookup trigger.

**gRPC status:** Keepa settings are configurable via `GetSettings`/`UpdateSetting`. No RPC for
triggering price lookups or viewing valuation data. Need `ListValuations` and `LookupPrice` RPCs.

### 7. Insurance Inventory Report

**Web:** `/report` — Generate a CSV or formatted report of the entire collection with purchase
prices, replacement values, formats, and conditions. For insurance documentation.

**iOS gap:** No report generation or export.

**gRPC status:** No report RPC. Need `GenerateInventoryReport` RPC that returns structured data
(the iOS app can format it as a shareable document or CSV).

### 8. Document Ownership (Bulk View)

**Web:** `/document-ownership` — Browse all titles that lack ownership documentation (photos of
physical media). Prioritized list for bulk photo capture sessions.

**iOS gap:** Ownership photos can be uploaded per-title in scan detail, but there's no bulk view
showing which titles still need documentation.

**gRPC status:** No dedicated RPC. Need `ListUndocumentedTitles` RPC returning titles without
ownership photos (paginated, filterable).

### 9. Family Member Management

**Web:** `/family` — Create and edit family members (name, birthday, notes, content rating level).
Family members appear on title cards and are used for content filtering.

**iOS gap:** Family titles are browseable but family members themselves cannot be created or edited.

**gRPC status:** No family member management RPCs. Need CRUD RPCs: `ListFamilyMembers`,
`CreateFamilyMember`, `UpdateFamilyMember`, `DeleteFamilyMember`.

### 10. Live TV Settings

**Web:** `/live-tv/settings` — Configure HDHomeRun tuners (IP address, tuner count), manage
channels (enable/disable, display order, reception quality rating, content rating, network
affiliation), set streaming concurrency limits (global max, per-tuner, per-user).

**iOS gap:** Users can watch Live TV and filter by quality, but admins cannot configure tuners,
channels, or streaming limits from the iOS app.

**gRPC status:** `ListTvChannels` exists for the viewer. No admin RPCs for tuner/channel config.
Need: `ListTuners`, `CreateTuner`, `UpdateTuner`, `DeleteTuner`, `ListAdminChannels`,
`UpdateChannel`, `UpdateStreamingLimits`.

---

## Execution Plan

### Phase 1: Foundation — First-User Setup

**Goal:** Make the iOS app usable from a completely fresh server with zero users.

**Why first:** This is the only feature that _blocks_ all other iOS usage. Every other missing
feature has a web UI workaround; this one means you literally can't start without a browser.

| Task | Type | Effort |
|------|------|--------|
| Define `SetupService` proto with `CreateFirstUser` RPC | Proto | Small |
| Server: implement SetupGrpcService (reject if users exist) | Server | Small |
| Server: register in GrpcServer, add to AuthInterceptor unauthenticated list | Server | Small |
| iOS: detect "no users" state from Discover or failed login | iOS | Small |
| iOS: `SetupView` — username, password, display name, confirm | iOS | Medium |
| iOS: navigate to login after successful setup | iOS | Small |
| Tests: setup RPC works once, rejects second call | Test | Small |

### Phase 2: Catalog Completeness — Add Title & Media Item Edit

**Goal:** Admins can build and maintain the full catalog from iOS without needing the web UI
for title management.

| Task | Type | Effort |
|------|------|--------|
| Proto: `AddFromTmdb` RPC (tmdb_id, media_type, season_count) | Proto | Small |
| Server: implement in CatalogService or AdminService | Server | Medium |
| iOS: "Add Title" view with TMDB search + result selection + season picker | iOS | Medium |
| iOS: wire into admin navigation | iOS | Small |
| Proto: `GetMediaItem`, `UpdateMediaItem` RPCs (format, condition, notes, TMDB reassign) | Proto | Small |
| Server: implement media item CRUD | Server | Medium |
| iOS: Media item edit sheet accessible from title detail | iOS | Medium |
| Tests: add title, edit media item | Test | Small |

### Phase 3: Purchase Tracking — Amazon Import & Expand

**Goal:** Full purchase lifecycle management from iOS.

| Task | Type | Effort |
|------|------|--------|
| Proto: `ImportAmazonOrders` RPC (accepts CSV text, returns match results) | Proto | Small |
| Server: extract CSV parsing from web view into shared service | Server | Medium |
| Server: implement import RPC | Server | Small |
| iOS: Import view — file picker for CSV, progress, match review | iOS | Large |
| Proto: `ListPendingExpansions`, `ExpandPack` RPCs | Proto | Small |
| Server: extract expand logic into shared service, implement RPCs | Server | Medium |
| iOS: Expand view — pending packs, assign individual titles | iOS | Medium |
| Tests: import parsing, expand pack splitting | Test | Medium |

### Phase 4: Valuation & Reporting

**Goal:** Insurance documentation workflow fully available on iOS.

| Task | Type | Effort |
|------|------|--------|
| Proto: `ListValuations` RPC (paginated, with prices and Keepa data) | Proto | Small |
| Proto: `LookupPrice` RPC (trigger Keepa for a title) | Proto | Small |
| Proto: `GenerateInventoryReport` RPC (structured report data) | Proto | Small |
| Proto: `ListUndocumentedTitles` RPC (titles missing ownership photos) | Proto | Small |
| Server: extract valuation logic into shared service, implement RPCs | Server | Medium |
| Server: extract report generation into shared service, implement RPC | Server | Medium |
| Server: implement undocumented titles query | Server | Small |
| iOS: Valuation browse view with Keepa trigger | iOS | Medium |
| iOS: Report view — generate and share (CSV or formatted text) | iOS | Medium |
| iOS: Document Ownership view — undocumented title list with quick-photo flow | iOS | Medium |
| Tests: valuation, report generation, undocumented query | Test | Small |

### Phase 5: Family & Live TV Administration

**Goal:** Complete admin parity — every admin function available on iOS.

| Task | Type | Effort |
|------|------|--------|
| Proto: Family member CRUD RPCs (List, Create, Update, Delete) | Proto | Small |
| Server: extract family member logic, implement RPCs | Server | Medium |
| iOS: Family member management view (list, add/edit sheet, delete) | iOS | Medium |
| Proto: Live TV admin RPCs (tuner CRUD, channel config, streaming limits) | Proto | Medium |
| Server: extract Live TV settings logic, implement RPCs | Server | Medium |
| iOS: Live TV Settings view — tuner list, channel management, limits | iOS | Large |
| Tests: family CRUD, live TV config | Test | Medium |

### Phase 6: Polish & Edge Cases

**Goal:** Feature parity verified end-to-end.

| Task | Type | Effort |
|------|------|--------|
| End-to-end test: fresh server → setup → configure → scan → play on iOS only | Test | Medium |
| Audit all web admin views for minor features not yet captured | Research | Small |
| Chapter debug view (low priority, admin-only diagnostic) | iOS | Small |
| Documentation updates (CLAUDE.md, GETTING_STARTED.md, ADMIN_GUIDE.md) | Docs | Medium |

---

## Priority Rationale

- **Phase 1** unblocks the "manage from beginning" requirement — without it, a browser is mandatory.
- **Phase 2** covers the most frequent admin task (adding and editing titles).
- **Phase 3** handles bulk data entry that currently requires the web UI.
- **Phase 4** addresses the insurance/valuation workflow (less frequent but high-value).
- **Phase 5** covers less-used admin features (family members are niche; Live TV settings are
  configure-once).
- **Phase 6** catches anything missed and verifies the full end-to-end flow.

## iOS-Only Features (No Web Equivalent)

For completeness, these iOS features have no web counterpart:

- **Downloads & Offline Playback** — Download titles for offline viewing with subtitle/chapter support
- **Device Pairing Initiation** — iOS scans QR code to pair; web confirms
- **Barcode Camera Scanning** — VisionKit DataScannerViewController (web uses manual UPC entry)
- **Bluetooth Scanner Support** — Auto-detect BT barcode scanners
