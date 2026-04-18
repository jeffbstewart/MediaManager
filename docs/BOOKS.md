<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Books in Media Manager — Design & Implementation Plan

*Created April 17, 2026*

---

## Overview

Media Manager today catalogs physical video media (DVD, Blu-ray, UHD, HD DVD) and lets the user watch it on several clients. This document extends the same model to books — physical books, ebooks, and (schema-only) audiobooks — so a household library sits in the same system as its film and television collection.

Books aren't a second product. They slot into the existing `title`, `media_item`, genre, tag, wishlist, and ownership-photo infrastructure. Most of the work is new UI and a handful of book-shaped entities; the plumbing is already there.

---

## Goals

1. **Insurance-grade inventory.** Scan a book's ISBN, capture metadata, attach proof-of-ownership photos, record a replacement value. Produce the same inventory report the existing home-media inventory produces today.
2. **Physical location tracking.** Answer "where is my copy of *Dune*?" — which bookcase, which shelf. Reuse the existing media-location concept where applicable.
3. **Read ebooks on screen.** EPUB and PDF in the browser, with reading progress saved per-user.
4. **Accommodate audiobooks in the schema** without building the playback surface yet. The user currently has few if any audiobooks; the data model should not prevent them later.

---

## Non-Goals (for now)

- **No Roku / Android TV book support.** Books are a personal-device experience. Both platforms are explicitly out of scope.
- **No audiobook player UI.** Schema accommodates audiobook editions, but no playback surface is built in this round.
- **No Calibre import.** The user has no pre-existing digital library to migrate from. Greenfield.
- **No comic book formats (CBZ, CBR)** in the initial reader. Called out as a possible future extension.
- **No parental controls / age-appropriate filtering for books.** Existing rating-ceiling filters still function; books default to `content_rating = NR`.

---

## Conceptual Model

### Work vs. edition

Books have a stronger **work/edition** distinction than movies. *Foundation* is a work; the 1951 Gnome hardback, a 1991 Bantam mass-market paperback, a specific EPUB, and an audiobook read by Scott Brick are four editions of that single work.

This is exactly what `title` (the abstract work) and `media_item` (a specific owned instance — a DVD today) already encode for movies. Books reuse this split:

- A `Title` with `media_type = BOOK` is the **work**: *Foundation* by Isaac Asimov, genres, tags, author(s), series + volume, cover, description.
- A `MediaItem` linked to that `Title` via `media_item_title` is an **edition**: ISBN, format (mass-market / hardback / EPUB / …), physical location, ownership photos, purchase price, replacement value, (for digital editions) file path.

Movies already use `media_item_title` as many-to-many because a single Blu-ray disc can contain multiple films (*The Matrix Trilogy*). The same table handles the book omnibus case natively — *The Foundation Trilogy* hardback is one `media_item` linking to three `title`s. This directly reuses the existing **Expand Multi-Movie Discs** concept.

### Editions with and without barcodes

- **Physical editions** (mass-market, trade paperback, hardback, audiobook CD) have an ISBN, which is encoded as EAN-13 — the same barcode hardware the UPC scanner already handles. No new scanner flow.
- **Digital editions** (EPUB, PDF, digital audiobook file) typically have no ISBN. `MediaItem.upc` is nullable today; book ingestion treats that as normal.

Entry paths mirror what's there today:

| Path | Movie analog | Book version |
|------|--------------|--------------|
| Barcode scan | UPC → UPCitemdb → create Title + MediaItem | ISBN → Open Library → create Title + MediaItem |
| File discovery | NAS scan detects MP4 → match/create Title + Transcode | NAS scan detects EPUB → embedded metadata match to existing work or create Title + MediaItem (digital) |
| Manual entry | TMDB search → create Title | Open Library search (by title/author) → create Title |

---

## Data Sources

### Primary: Open Library

[Open Library](https://openlibrary.org/) is the metadata spine. Free, no API key, widely mirrored.

| Need | Endpoint | Notes |
|------|----------|-------|
| ISBN → edition + work | `GET /isbn/{isbn}.json` | Returns edition fields plus a `works[]` pointer. |
| Work details | `GET /works/{OL...W}.json` | Description, subjects (→ genres), authors, series. |
| Author details | `GET /authors/{OL...A}.json` | Bio, alternate names, optional `wikidata_id`. |
| Cover image | `https://covers.openlibrary.org/b/isbn/{isbn}-L.jpg` | Predictable URL; also `/b/olid/{edition OLID}-L.jpg`. |
| Author photo | `https://covers.openlibrary.org/a/olid/{OLID}-L.jpg` | Quality varies. |
| Search | `GET /search.json?q=…` | Free-text. |

**Work/edition split is native.** OL's model matches ours: one work record, many editions pointing at it.

### Author enrichment: Wikipedia

Open Library author bios are thin for mid-tier authors. When OL returns a `wikidata_id`, we can resolve it to the author's Wikipedia page and pull a better biography and a higher-quality headshot. This is optional enrichment, batched in the background, and falls back to whatever OL provides.

### Series data

OL exposes `series` on a work as free-text like `["Foundation #1"]`. The ingestion parser splits `"<Name> #<Number>"`, does a get-or-create on `book_series.name`, and stores the number on the title. When OL is silent (plenty of series it doesn't know), the user can author the series during scan.

---

## Schema

### Extensions to existing tables

**`title`** — new nullable columns used only when `media_type = BOOK`:

| Column | Type | Purpose |
|--------|------|---------|
| `open_library_work_id` | string | e.g. `OL46125W` |
| `book_series_id` | FK → `book_series` | Nullable; standalone works have none |
| `series_number` | decimal(5,2) | Nullable; supports `0.5` prequels, `1.5` interquels |
| `page_count` | int | Longest edition's page count (rough) |
| `first_publication_year` | int | Distinct from `release_year` which tracks an edition |

Existing `media_type` enum gains `BOOK`. All existing columns (`name`, `description`, `poster_path`, `genres`, `tags`, `popularity`, `content_rating`, `tmdb_id`) still apply: `tmdb_id` stays null for books, `content_rating` defaults to `NR`.

**`media_item`** — broadened semantics + two new columns:

- `upc` holds the ISBN for physical editions (ISBN-13 is EAN-13, which is a superset of UPC-A).
- `media_format` enum gains: `MASS_MARKET_PAPERBACK`, `TRADE_PAPERBACK`, `HARDBACK`, `EBOOK_EPUB`, `EBOOK_PDF`, `AUDIOBOOK_CD`, `AUDIOBOOK_DIGITAL`.
- Ownership photos, purchase price, replacement value — all reused as-is.
- **New** `storage_location` (nullable string) — free-text location like "Living room bookcase, shelf 3." Benefits movies too: the entity has `purchase_place` for where it was bought, but no column for where the disc currently lives. Reusable across all media types.
- **New** `file_path` (nullable string) — points into the NAS for digital editions (EPUB / PDF / digital audiobook). Analogous to `transcode.file_path` for video.

### New tables

**`book_series`**

| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `name` | string | "Foundation", "The Wheel of Time" |
| `description` | text | Optional |
| `poster_path` | string | Auto-set to book 1's cover on first create |
| `poster_source` | enum `AUTO`/`MANUAL` | `AUTO` on create; flips to `MANUAL` when the admin sets a custom poster, at which point auto-backfill stops overwriting |
| `author_id` | FK → `author` | Nullable; sometimes a series has no single author |
| `open_library_key` | string | Usually null; OL has inconsistent series IDs |
| `created_at`, `updated_at` | timestamps | |

**`author`**

| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `name` | string | Display form, e.g. "Isaac Asimov" |
| `sort_name` | string | "Asimov, Isaac" for alphabetical browse |
| `biography` | text | Open Library → Wikipedia fallback |
| `headshot_path` | string | Cached locally via existing image pipeline |
| `open_library_author_id` | string | e.g. `OL34184A` |
| `wikidata_id` | string | For Wikipedia enrichment |
| `birth_date`, `death_date` | date (nullable) | |
| `created_at`, `updated_at` | timestamps | |

**`title_author`** (many-to-many; anthologies have multiple authors)

| Column | Type | Notes |
|--------|------|-------|
| `title_id` | FK → `title` | |
| `author_id` | FK → `author` | |
| `author_order` | int | Primary author is 0 |
| PK (`title_id`, `author_id`) | | |

**`reading_progress`** (parallel to `playback_progress`)

| Column | Type | Notes |
|--------|------|-------|
| `user_id` | FK → `app_user` | |
| `media_item_id` | FK → `media_item` | The specific edition being read |
| `cfi` | string | EPUB Canonical Fragment Identifier — the position |
| `percent` | double | 0.0–1.0 for quick "progress" displays |
| `updated_at` | timestamp | |
| PK (`user_id`, `media_item_id`) | | |

For PDFs, `cfi` stores a page number instead (PDFs don't have CFI). The column stays a string and the reader serializes its own scheme.

### Author as first-class entity vs. reusing `cast_member`

Authors are **not** reused as cast members. Rationale:

- Different primary data source (Open Library vs. TMDB); the existing enrichment pipeline wouldn't work.
- No notion of "character played" or cast ordering relative to screen time.
- Authors are per-Title (usually 1:1, occasionally 1:N for anthologies); cast is always N:M with a deeper association.
- The UX asks different questions — "all books by this author" vs. "all movies this actor appeared in."

A parallel `AuthorScreen` mirrors `ActorScreen`'s shape but queries its own data.

---

## Feature Surface

### Reused, unchanged

- **Genre** — OL `subjects[]` map onto existing `genre` records. Genre browse lists a mix of movies and books, filterable by media type.
- **Tag** — user-authored tags (favorites, reference, "kids' shelf") apply across all media types already.
- **Wishlist** — wishing for *Dune* works identically to wishing for a movie. Insurance/replacement value flows the same way.
- **Ownership photos** — `MediaItem` already takes photos; attach them to physical book editions.
- **Physical location** — the new `media_item.storage_location` holds "Living room bookcase, shelf 3." (Movies benefit too; this fills a pre-existing gap.)
- **Home feed** — new carousels ("Recently added books", "Resume reading"), same shape as the existing movie carousels.

### New for books

- **AuthorScreen** — hero image, bio, list of "books I own by this author" and a "published works" list from OL (for wishlisting missing titles).
- **SeriesScreen** — grid of volumes in order, owned/not-owned state, "fill the gaps" affordance that bulk-adds the missing volumes to the wishlist.
- **Book detail screen** — similar to `TitleDetailScreen` but with book-shaped affordances: author link, series link with "read previous / next", list of editions (own a paperback + an EPUB shows two line items), a read button per digital edition.
- **Editions list on the detail page** — shows format, location if physical, page count, acquisition date, replacement value, and (for ebooks) a "Read" button.

### Web reader (MVP)

- Serve EPUB from the NAS via a new servlet that streams `media_item.file_path` with access control from the existing auth chain.
- Render in-browser with [epub.js](https://github.com/futurepress/epub.js) (EPUB) and [PDF.js](https://mozilla.github.io/pdf.js/) (PDF). Both are pure-frontend, server just ships the bytes.
- Reader UI: single-column scroll or paginated, font size control, table of contents, last-position resume. No annotations in MVP.
- Reading progress saved every 10 s via a new `PlaybackService.reportReadingProgress` gRPC (or reuse the existing progress service with a new request shape).

### Download support

Critical to keep in mind now because it constrains the serving layer. The existing `DownloadService.DownloadFile` gRPC that already serves video files works unchanged for EPUB/PDF — it streams `DownloadChunk` messages and any authenticated client can request them. The web app won't use it (it'll stream the book directly over HTTP for rendering), but when iOS arrives, it uses the same download RPC the video path already has. No additional server work for offline ebooks on iOS beyond what streaming for the web already requires.

---

## Insurance / Inventory Reporting

Existing `InventoryReportHttpService` produces a CSV of owned physical items with replacement values. Adding books means:

1. Reports include book-format editions alongside discs.
2. The "replacement value" column is populated by:
   - An explicit user entry at scan time.
   - Or a background lookup against a pricing source (Keepa already used for movies; Amazon ISBN lookup would work equivalently).
3. Proof-of-ownership photos already attach to `MediaItem`.

Digital editions are excluded from insurance reports — you can't insure an EPUB file you downloaded.

---

## Entry Paths

### Scan an ISBN

Reuses the existing scan pipeline:

1. User scans a barcode in the Scan view.
2. Barcode is EAN-13. If the prefix is 978 or 979, it's an ISBN — route to the Open Library lookup branch instead of UPCitemdb.
3. OL returns edition + work + author(s).
4. Create/update `author` records; create/update `book_series` if the work advertises one; create `title` with `media_type = BOOK`; create `media_item` with ISBN/format/price; link via `media_item_title`.
5. Prompt for physical location and ownership photos using the existing flows.

### Discover an ebook file on the NAS

The existing NAS scanner watches for video files. Extend it to also notice `.epub` and `.pdf` files in a configured books root. On detection:

1. Read the embedded metadata (EPUB has `META-INF/container.xml` → OPF with title/author/ISBN; PDF has an XMP dictionary).
2. If an ISBN is present: same as the scan path but with `media_item.file_path` set and no ownership photo.
3. If no ISBN: present for manual match — like the existing `UnmatchedHttpService` does for orphaned video transcodes. The user picks a `title` (or creates one via OL search) and the file is linked.

### Manual add

An "Add book" flow runs an Open Library title/author search in the same UI shell the existing TMDB search uses. User picks a work; a `title` is created. No `media_item` yet — they can add editions subsequently (e.g., wishlist the book before owning it, like movies today).

---

## Implementation Plan

Phases are ordered so each one ships a usable increment. Milestones prefixed **M**. Each phase assumes the previous is done.

**Status (2026-04-17):** M1 and M2 shipped. M3 (book wishlists) is the next planned milestone, carved out of what was originally M2's "other works" / "fill the gaps" scope so that the multi-surface wishlist integration gets the attention it deserves.

### **M1 — Schema + physical book scanning** *(smallest shippable inventory feature)* ✅

- Flyway migrations:
  - Add `BOOK` to `media_type` enum.
  - Add book-specific nullable columns to `title` (`open_library_work_id`, `book_series_id`, `series_number`, `page_count`, `first_publication_year`).
  - Add new book formats to `media_format` enum.
  - Add nullable `file_path` and `storage_location` to `media_item`.
  - Create `book_series`, `author`, `title_author` tables.
- Introduce proper dedup at scan time (see Resolved Questions Q2): look up by `open_library_work_id + media_type=BOOK` and by `tmdb_id + media_type` for movies/TV before creating a new `title`. Today each scan always creates a new title row — this fixes a pre-existing correctness bug for movies as a side benefit.
- `OpenLibraryService`: ISBN lookup, work fetch, author fetch, series parser. Follows existing `TmdbService` patterns — HTTP client, rate limiting, small DTOs.
- Scan flow: detect `978`/`979` prefix on EAN-13 → route to `OpenLibraryService`.
- Web UI: the existing Scan page handles books with no change beyond showing author + series where normally it shows cast + TMDB collection.
- Inventory report includes books.

**Deliverable:** Scan any physical book, get it into the catalog with author + series + cover + location + ownership photo. Inventory CSV covers books. No new browse screens yet.

### **M2 — Authors + series as browse destinations** ✅

- `AuthorScreen` web view. Route `/author/:authorId`. Queries a new `AuthorHttpService`. Surfaces: hero, bio, and an owned-books grid grouped by series.
- `SeriesScreen` web view. Route `/series/:seriesId`. Surfaces: linked author, description, and volumes you own in series order with `#N` badges.
- `AuthorEnrichmentAgent` — background daemon that enriches authors in two passes:
  1. **Open Library** — fills `biography`, `wikidata_id`, and `birth_date` / `death_date` for authors where they're missing.
  2. **Wikipedia** — for authors whose OL bio is short or whose `headshot_path` is still empty, resolves `wikidata_id` → Wikipedia title via Wikidata `Special:EntityData`, then fetches the Wikipedia REST page summary for a richer extract + thumbnail. Replaces bio only when the candidate is longer than the existing one; never clobbers a good OL bio.
- Home-feed carousel for books: "Recently added books", integrated into the existing feed. Gated on `features.has_books`.
- Catalog browse gets a Books landing page (`/content/books`) via the shared `TitleGridComponent` with `mediaType="BOOK"`. Shell nav surfaces the Books link when `has_books`.
- Admin `media-item-edit` Book Details block links author names to `/author/:id` and the series name to `/series/:id` so the admin flow flows naturally into the new browse views.

**Deliverable:** Books are browseable by author and by series. Every ingested book has a canonical hero page reachable from the admin edit view and the home carousel. Catalog feels complete for the physical-inventory use case.

### **M3 — Book wishlists, "other works", and "fill the gaps"**

Adds the first wish-list-dependent surfaces. The `WishListItem` schema today is keyed on `(tmdb_id, media_type)` — books need a parallel identity based on `open_library_work_id`.

- **Schema** — migration extending `wish_list_item` with `open_library_work_id`, book-wish title / author / cover columns, and a new `wish_type = BOOK`. (Alternative considered: a separate `book_wish` table; rejected because the Wishlist page rendering wants a single unified list, and `wish_list_item` already carries the user-scoping, status, and notes we need.)
- **Service** — `WishListService` gains book-wish add / remove / list. Lookup key is OL work ID so a book can be wished by an ISBN search OR an author's bibliography listing without duplicates.
- **Open Library bibliography** — `OpenLibraryService.listAuthorWorks(authorOlid)` hits `/authors/{olid}/works.json` (paginated). Filters out works already owned (matched by OL work ID) and returns the remainder sorted by popularity / publication year.
- **AuthorScreen `Other Works`** — new section below the owned grid, rendering OL works you don't own with a heart button. Wishlist state is returned from the author endpoint so the UI reflects prior wishes immediately.
- **SeriesScreen `Fill the Gaps`** — an action that, given a series's OL identity, loads the full volume list from OL, diffs against what the user owns, and offers a bulk "wishlist the missing N volumes" button. Uses the same add-book-wish path one-per-volume.
- **Wishlist page** — renders book wishes alongside movie wishes, grouped by type, with OL-covered thumbnails.
- **Admin inventory impact** — book wishes do not affect insurance reporting; they're future acquisitions.

**Deliverable:** From an AuthorScreen you can wishlist any of that author's books you don't own. From a SeriesScreen you can one-click the missing volumes. The main Wishlist shows both media and book wishes in one place.

### **M4 — Ebook ingestion**

- NAS scanner extension for `.epub` / `.pdf`. Configurable `books_root` in `app_config`.
- EPUB metadata reader (JVM library: `epublib` or similar). PDF metadata reader (`PDFBox`).
- ISBN-found path reuses M1 ingestion.
- No-ISBN path: adds files to a new `unmatched_book` staging table, shown on the Unmatched admin page next to unmatched transcodes.
- `media_item.file_path` populated for ebook editions.

**Deliverable:** Drop an EPUB into the books folder; it appears under its title's editions list. No reader yet.

### **M5 — Web reader + reading progress**

- New servlet `/ebook/{mediaItemId}` — authenticated, streams the file with range requests. Auth same as `/stream/`.
- New `/reader/{mediaItemId}` Angular route hosting epub.js or PDF.js based on `media_format`.
- Reader UI: TOC, font size, page/position display, close.
- `reading_progress` table + `PlaybackService.reportReadingProgress` gRPC. Client reports every 10 s (same cadence as video).
- "Resume reading" home-feed carousel, sorted by `reading_progress.updated_at`.
- `GetReadingProgress` so the reader resumes at the last CFI on open.

**Deliverable:** Click Read on an EPUB or PDF edition. Browser reader opens at the right page. Closing and re-opening resumes. Home page shows what you're in the middle of.

### **M6 — Download support polish** *(no new UX, just the RPC contract proven)*

- Confirm `DownloadService.DownloadFile` works for EPUB/PDF with the existing chunk protocol. Integration test sending a 5 MB EPUB through the RPC and reassembling it.
- Document the client contract in `docs/ANGULAR_MIGRATION.md` / `docs/IOS_PLAN.md` so the iOS phase can slot in with no new server work.

**Deliverable:** iOS can start on its reader whenever it gets picked up, with a settled contract.

### **M7 — iOS reader** *(out of scope for this doc's timeline; scoped separately)*

Placeholder. Will reuse the same gRPC stubs the web uses, plus `DownloadService` for offline. Native reader — `PDFKit` for PDFs, a third-party EPUB renderer (`FolioReaderKit` or similar) for EPUBs.

### **Later**

- **Audiobook playback** — once the user has audiobooks worth playing. Web first with HTML5 `<audio>` + chapter navigation, then iOS.
- **CBZ / CBR** comics — third reader mode in the web viewer.
- **Insurance pricing automation** — ISBN → Keepa / Amazon to backfill replacement values.
- **Highlights & annotations** — EPUB and PDF both support them; store as a new table keyed by `(user_id, media_item_id, cfi_range)`.
- **Reading stats** — pages per day, minutes per session.
- **Parental controls** — map book `subjects` onto a book-specific rating enum; gate by `rating_ceiling` like videos.

---

## Resolved Design Decisions

### Q1. Keepa / Amazon for book pricing — **deferred past M1**

`KeepaService.lookupByAsin()` accepts an ASIN directly, so a book (whose ASIN is its ISBN-10) could round-trip through it. But `searchByTitle()` / `searchCandidates()` hardcode `rootCategory=2625373011L` (DVD / Blu-ray), so any code path that uses the search variants would reject books.

**Decision:** for M1 and M2, book replacement values are **user-entered at scan time** — same field already on `media_item.replacement_value`. Automated Keepa backfill for books is deferred. When it happens, the work is: (a) parameterize `rootCategory` on the search endpoints, (b) wire a book-specific category ID, (c) or skip the search branch and go directly through `lookupByAsin()` with the ISBN-10 conversion. None of this blocks M1.

### Q2. Dedup across formats — **Open Library Work ID as the key, with a fallback**

- **Primary key:** `open_library_work_id + media_type=BOOK`. Scanning a paperback of *Foundation* and then a hardback resolves both to the same existing `title`; two `media_item` rows hang off it via `media_item_title`.
- **Fallback when OL has no work ID:** normalized hash of `lowercased(trimmed(title_name)) + "|" + lowercased(author.sort_name_primary)`. Imperfect — typos split records — but covers the long tail.
- **Bonus fix for movies.** The survey found that the current scan pipeline doesn't dedup at all — every UPC scan creates a fresh `title`, even when TMDB would map two discs to the same film. M1's dedup introduction applies the same `tmdb_id + media_type` key retroactively for movies (per memory, `tmdb_id` namespaces are separate between movie and TV, so the composite is required). This closes a latent bug.

### Q3. Multi-author sort — **both, ordered by `author_order`**

*The Talisman* (King + Straub) creates two `title_author` rows; King gets `author_order=0`, Straub gets `author_order=1`. Both AuthorScreens list the book. Book detail shows "Stephen King & Peter Straub" in `author_order` sequence. No "co-authored" badge — listing both names naturally is self-explanatory.

---

## Pre-M1 Verifications

- **`media_format` enum extension is safe.** Five non-exhaustive `when` expressions exist (four in Roku-feed services, one in `CatalogGrpcService.formatPriority`). Since Roku is explicitly out of book scope, no book-format `MediaItem` will reach the Roku feed queries anyway; M1 adds a book-format filter on those queries defensively. `CatalogGrpcService.formatPriority` and `ProtoMappers.toProtoQuality` fall through to `0` / `QUALITY_UNKNOWN` for unrecognized formats — fine for books, which have no video-quality tier. `InventoryReportGenerator` just stringifies the enum value (`media_format.replace("_", " ")`), so new values print cleanly in the CSV. **No pre-M1 refactor needed.** ✅

- **Open Library rate limits — comfortable.** OL publishes no hard cap; informal guidance is "be polite, avoid bursts > ~100 req/min." Scan volume is user-driven (one barcode at a time, a few per minute at most during a scan session). Well under the threshold. Background enrichment jobs (author bios, series poster backfill) will pace themselves at 1 req/sec to stay considerate. ✅

- **`book_series.poster_path` default — auto from volume 1, overridable.** On first-time series creation during scan, the series poster is set to the scanned book's cover (which will typically be volume 1, but any volume is acceptable as a starting point). A `poster_source` column (`AUTO` / `MANUAL`) on `book_series` records whether subsequent scans are allowed to overwrite the poster (`AUTO`) or must leave a user-chosen poster alone (`MANUAL`). Setting the series poster manually in the admin UI flips the column to `MANUAL`. ✅

- **Scan UI has room for book-specific fields.** The current Angular scan flow (`web-app/src/app/features/admin/add-item.ts`) returns a terse status string post-scan and has **no** rich post-resolution detail panel for any media type. For M1 we add a conditional "review" panel that shows only when the scan resolved to a book, surfacing author, series + volume, and storage location for confirmation or correction before save. The movie flow is unchanged — no panel, same behavior as today. ✅

All pre-M1 items resolved. Schema and ingestion plan is ready to execute.
