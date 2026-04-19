# Image Cache Migration

Plan of record for migrating the nine scattered per-cache image directories
under `data/` into two unified stores with sidecar metadata that makes every
cached file self-describing and reconstructable without the database.

## Goals

1. **Durability of first-party images.** Ownership photos and local uploads
   are physically expensive (or impossible) to recreate. They must survive
   every step of the migration.
2. **Bounded directory fan-out.** Every leaf directory caps at ~256 files
   regardless of the identifier shape (UUID, sequential int, MBID, hash).
3. **Self-describing files.** If the database is lost, the sidecar next to
   each image is sufficient to rebuild `image → subject` mappings.
4. **Cheap internet-image loss.** Short-term, losing a cached TMDB poster or
   Wikipedia headshot is acceptable — those re-fetch on next request.
5. **No behavior change visible to clients.** Same URLs, same authentication,
   same bytes served.

## Inventory (as of plan start)

| Cache | Root | Sharding today | First-party? |
|---|---|---|---|
| PosterCacheService | `poster-cache/` | UUID `ab/cd/` | No |
| BackdropCacheService | `backdrop-cache/` | UUID `ab/cd/` | No |
| HeadshotCacheService (cast) | `headshot-cache/` | UUID `ab/cd/` | No |
| ArtistHeadshotCacheService | `artist-headshot-cache/` | int modulo `100×100` | No |
| AuthorHeadshotCacheService | `author-headshot-cache/` | int modulo `100×100` | No |
| LocalImageService | `local-images/` | UUID `ab/cd/` | **Yes** |
| OwnershipPhotoService | `ownership-photos/` | slug letters `a/b/` — collides heavily | **Yes** |
| ImageProxyService | `image-proxy-cache/{provider}/ab/cd/` | SHA-256 prefix | No |
| CollectionPosterCacheService | `collection-poster-cache/` | **FLAT — unbounded** | No |

Only ownership photos and local images are first-party. Everything else
fetches from TMDB / Open Library / Cover Art Archive / Wikimedia and can be
refetched when missing.

## Target shape

### Store 1 — First-party images (irreplaceable)

```
data/first-party-images/{category}/{ab}/{cd}/{uuid}.{ext}
data/first-party-images/{category}/{ab}/{cd}/{uuid}.meta.json
```

Categories: `ownership-photos`, `local-images`.

Shard is the first 4 hex characters of `sha256(identifier)` regardless of
whether the identifier is a UUID, a photo id, or an upload id. Uniform 256×256
fan-out. The image's basename matches the sidecar's basename so a directory
listing shows them paired.

Sidecar (ownership-photos):

```json
{
  "version": 1,
  "store": "first-party",
  "category": "ownership-photos",
  "photo_id": "<uuid-of-this-photo>",
  "storage_key": "786936215595",
  "media_item_id": 1234,
  "slug_hint": "the-matrix",
  "sequence": 1,
  "captured_at": "2025-01-15T14:22:00Z",
  "content_type": "image/jpeg",
  "original_filename": "IMG_2031.jpg"
}
```

Sidecar (local-images):

```json
{
  "version": 1,
  "store": "first-party",
  "category": "local-images",
  "uuid": "abcd-...",
  "subject_type": "title" | "personal_video" | "user_avatar",
  "subject_id": 1234,
  "uploaded_by_user_id": 2,
  "uploaded_at": "2025-01-15T14:22:00Z",
  "content_type": "image/jpeg"
}
```

### Store 2 — Internet-cached images (reproducible)

```
data/internet-images/{provider}/{ab}/{cd}/{cache_key}.{ext}
data/internet-images/{provider}/{ab}/{cd}/{cache_key}.meta.json
```

Providers: `tmdb-poster`, `tmdb-backdrop`, `tmdb-headshot`,
`tmdb-collection`, `openlibrary-cover`, `caa-release-group`,
`wikimedia-artist`, `wikimedia-author`.

`{ab}/{cd}` is the first 4 hex characters of `sha256(cache_key)` so fan-out
stays 256×256 regardless of whether the key is a UUID, int, or MBID.

Sidecar:

```json
{
  "version": 1,
  "store": "internet",
  "provider": "tmdb-poster",
  "cache_key": "<uuid-of-this-photo>",
  "upstream_url": "https://image.tmdb.org/t/p/w500/abc.jpg",
  "subject_type": "title",
  "subject_id": 1234,
  "fetched_at": "2025-01-15T14:22:00Z",
  "content_type": "image/jpeg",
  "etag": "opaque-cache-token"
}
```

`upstream_url` and `subject_id` are nullable — files whose provenance
can't be reconstructed at backfill time get a sidecar with nulls there,
still useful because `provider` and `cache_key` survive.

## Phases

### Phase 0 — Shared foundation

Single commit, no behavior change.

- `ImageMetadata` data class (sealed, one variant per store) + Gson schema.
- `MetadataWriter.writeSidecar(imagePath, metadata)`:
  - Atomic (`.tmp → rename`).
  - Called after the image bytes are committed. Order matters: if we crash
    between the image write and the sidecar write, the orphaned image still
    serves and the backfill updater fills the gap on next run.
- Pure library add. Safe to merge alone.

### Phase 1 — Decorate existing caches in place

No file moves. Every image on disk gets a sidecar. After this phase, losing
the database still leaves each image self-describing.

**Phase 1a — First-party sidecars**

- `OwnershipPhotoService.store(...)` + `storeForUpc(...)` write sidecars on new writes.
- `LocalImageService.save(...)` writes sidecars on new writes.
- `SchemaUpdater` (`BackfillFirstPartyImageSidecars`, version 1) scans both
  directories, joins each file against the DB to reconstruct metadata,
  writes missing sidecars. Version-tracked so it runs once per bump.

**Phase 1b — Internet sidecars**

- Every internet-cache service writes sidecars on new writes:
  `PosterCacheService`, `BackdropCacheService`, `HeadshotCacheService`,
  `CollectionPosterCacheService`, `ArtistHeadshotCacheService`,
  `AuthorHeadshotCacheService`, `ImageProxyService`.
- `SchemaUpdater` (`BackfillInternetImageSidecars`, version 1) reconstructs
  sidecars from DB state:
  - `poster_cache_id → title_id` for poster/backdrop.
  - `artist.id → mbid` for artist headshots.
  - etc.
- Files whose source can't be reconstructed get a sidecar with
  `upstream_url: null` — still useful, carries `provider` + `cache_key`.

### Phase 2 — Migrate Store 2 (destructive, safe because reproducible)

- Introduce `InternetImageStore`:
  - `putImage(provider, cacheKey, bytes, contentType, upstreamUrl, subject)`
  - `getImage(provider, cacheKey): Path?`
- Refactor each internet-cache service to route through it. One service per
  commit, each independently revertible.
- A cleanup `SchemaUpdater` deletes the old directories after all services
  have migrated. Next request repopulates from upstream into the new layout
  with a sidecar attached.
- Migrate `ImageProxyService` first — its layout is already nearly the
  target shape, so it's the lowest-risk starting point.

### Phase 3 — Migrate Store 1 (non-destructive, dual-read)

**Critical constraint: never delete a first-party image in this phase.**

- Introduce `FirstPartyImageStore`:
  - `putImage(category, identifier, bytes, contentType, subject)` writes to
    the new path + sidecar. Becomes the primary location for all new writes.
  - `getImage(category, identifier): Path?`
    1. Try new path.
    2. On miss, try the old path.
    3. Do not copy on read. Partial migrations must not cause divergence.
- `OwnershipPhotoService` + `LocalImageService` route through the new store.
- `SchemaUpdater` (`CopyFirstPartyImagesToNewLayout`, version 1) walks every
  old file and **copies** (not moves) it to the new location with a sidecar.
  Logs per-file success/failure. Re-runnable via version bump.
- Old directories stay on disk indefinitely as a read-only fallback.

### Phase 4 — Verification window (deferred, weeks-to-months later)

- Audit: every file in the old ownership-photos path has a byte-identical
  copy at the new path with a valid sidecar.
- Only after that audit passes is it safe to consider deleting the old
  ownership-photos directory.
- **Not scheduled automatically.** The maintainer decides when to pull the
  trigger.

## Risk register

| Risk | Mitigation |
|---|---|
| Sidecar write fails after image write | Image still serves. Backfill updater fills the gap on next run. |
| Power loss mid-copy in Phase 3 | `copy-then-rename-from-.tmp`. Partial copies become orphan `.tmp` files, cleaned on next run. Old file untouched. |
| Backfill updater misidentifies an image | DB join is authoritative. If the DB has no row, sidecar writes `subject_id: null` — still useful for provenance. |
| Post-Phase-2 upstream refetch fails | User sees a broken image and we retry on next request. Acceptable per the "losing a cached internet image is fine" ground rule. |
| Old ownership-photos dir deleted prematurely | Phase 4 is gated behind a verifier and maintainer approval. Never scheduled automatically. |
| Name collision between stores | Namespaced by root dir (`first-party-images` vs `internet-images`) and by `{category}` / `{provider}` subdir. |

## Commit breakdown

Each commit builds + ships standalone. At no point is Store 1 data at risk.

1. **`image-metadata-foundation`** — `ImageMetadata` + `MetadataWriter`. No
   callers yet. Pure library add.
2. **`sidecar-writes-first-party`** — ownership + local write sidecars on
   new writes. No backfill. No moves.
3. **`backfill-first-party-sidecars`** — SchemaUpdater decorates every
   existing ownership + local file.
4. **`sidecar-writes-internet`** — all seven internet cache services write
   sidecars on new writes.
5. **`backfill-internet-sidecars`** — SchemaUpdater for existing internet
   files.
6. **`internet-image-store`** — new `InternetImageStore`. Migrate
   `ImageProxyService` first (closest layout). Destroy its old cache dir.
7. **`migrate-poster-cache`** through **`migrate-wikimedia-caches`** —
   one commit per service, each independently revertible. Old dir for each
   is deleted as that service migrates.
8. **`first-party-image-store`** — new store with dual-read. Route
   ownership + local through it. Copy updater added but not yet run.
9. **`run-first-party-copy-updater`** — separate commit, version-bumps the
   updater to trigger the copy on next deploy. Rolling back the code change
   and rolling back the trigger are independent.

## Recovery procedures

### DB loss with sidecars intact

1. Walk every sidecar under `data/first-party-images/` and
   `data/internet-images/`.
2. For first-party: rebuild `ownership_photo` + `media_item` /
   `local_image` rows from sidecar fields. Bytes are the image files
   themselves.
3. For internet: rebuild `poster_cache_id` / artist.headshot_path /
   equivalents from sidecar `subject_id` + `subject_type`. Upstream refetch
   remains an option for any missing sidecars.

### Sidecar loss with DB intact

The backfill updaters are idempotent and re-runnable by bumping their
version. Regenerates every sidecar from DB joins.

### Partial Phase 3 failure (some ownership photos copied, some not)

Rerun the copy updater by bumping its version. Reads continue to fall back
to the old path throughout, so the system stays correct during the gap.
