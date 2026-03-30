# Angular Migration: Remaining Feature Deficits

Phases 0-3a/3b are complete. This document tracks remaining feature gaps
between the Angular SPA and the Vaadin UI. Once all items are resolved,
Phase 4 (Remove Vaadin and Jetty) can proceed.

---

## Entire Views Missing from Angular

| # | Vaadin View | Route | Description | Priority |
|---|---|---|---|---|
| 1 | MediaItemEditView | `/item/:mediaItemId` | Per-media-item edit: TMDB re-match, seasons, purchase info, Amazon order linking, ownership photos. Critical for fixing bad UPC scans. | High |
| 2 | ChangePasswordView | `/change-password` | Forced password change after first login / admin reset. Profile has voluntary change but the forced-redirect flow doesn't exist. | High |
| 3 | PairConfirmView | `/pair` | Roku device pairing confirmation. Shows code, user badge, confirm/cancel. | High |
| 4 | FamilyMemberManagementView | `/family` | Admin page to CRUD family members (name, birth date, notes). No way to manage family members in Angular. | Medium |
| 5 | BrowseView | `/catalog` | Unified catalog with format + rating + tag + playable filters across all media types. Angular has Movies/TV separately but no combined "all content" view. | Low |
| 6 | ChapterDebugView | `/debug/chapters` | Chapter/skip segment debug stats. Diagnostic tool. | Low |

---

## Per-View Feature Deficits

### Title Detail (High Priority)

| Feature | Detail |
|---|---|
| Hide/unhide title | Toggle button to personally hide a title from the current user |
| Tag editing (admin) | Pencil button to add/remove tags on a title |
| UPC badges + unlink (admin) | Show linked UPCs with admin unlink button |
| Season acquisition status (admin) | Color-coded season chips (Owned/Ordered/Rejected/etc.) with click-to-edit dialog |
| Episode skip badge | SKIP indicator on episodes that have skip segment data |
| Re-transcode request button | Refresh icon per transcode to request re-transcode |
| Transcode wish button | Up-arrow icon to request/unrequest transcode priority |
| Edit personal video details (admin) | Edit title, event date, description for personal videos |
| Set hero image (admin) | Extract candidate frames from video, select as hero |
| Edit family members (admin) | Multi-select combobox to assign/create family members on a title |
| Rush downloads button (TV) | Toggle to prioritize pending mobile episode transcodes |
| Format icons/badges | Show DVD/Blu-ray/UHD/HD DVD badges per format |
| Resume timestamp on episodes | Show resume point like "Resume at 23:45" |

### Collection Detail (High Priority)

| Feature | Detail |
|---|---|
| Wish list heart button on unowned parts | Heart icon to add unowned collection parts to wish list |

### Tag Detail (Medium Priority)

| Feature | Detail |
|---|---|
| Add title to tag (admin) | Combobox + add button to associate titles with a tag |
| Remove title from tag (admin) | X button on poster to remove a title-tag association |

### Data Quality (Medium Priority)

| Feature | Detail |
|---|---|
| Tag filter | Multi-select tag filter in the filter bar |
| Seasons column | Grid shows comma-separated seasons |
| TMDB search in edit dialog | Inline TMDB search with poster/title/year/overview results and "Select" button |
| Flag as Multi-Pack button | Button to flag single items as multi-packs |
| Title merge logic | Handle TMDB ID change that collides with existing title (merge links, transcodes, episodes) |

### Add Item (Medium Priority)

| Feature | Detail |
|---|---|
| NAS tab (discovered files) | Third tab showing discovered NAS files with accept/link/create/ignore actions |
| Link scan dialog | Scan-linking dialog with TMDB search, format/seasons, multi-pack checkbox, ownership photos |

### Transcode Linked (Medium Priority)

| Feature | Detail |
|---|---|
| Play button | Inline play button opening video player |
| Re-transcode request button | Toggle to request/cancel re-transcode |
| Transcode wish toggle | Toggle to request/unrequest transcode priority |

### Transcode Backlog (Medium Priority)

| Feature | Detail |
|---|---|
| Transcode request button | Toggle to add/remove transcode wish per title |

### Transcode Unmatched (Medium Priority)

| Feature | Detail |
|---|---|
| Create personal video button | "Create" button for personal media files (not just "Link") |
| TMDB search in link dialog | TMDB search with "Add & Link" to create new title |

### Valuation (Medium Priority)

| Feature | Detail |
|---|---|
| Keepa "Find on Keepa" integration | Keepa search with candidate results and "Use" button |
| Amazon order linking | Inline Amazon order search with "Use" button to link |
| ASIN management | Set/Clear override ASIN with Amazon URL parsing |
| Ownership photo management | Photo thumbnails + "Add Photo" button in edit dialog |
| Pricing agent status panel | Shows batch time, remaining count, session total |
| "Needs replacement value" filter | Third filter option beyond unpriced toggle |

### Wish List (Medium Priority)

| Feature | Detail |
|---|---|
| First-wish interstitial dialog | "Wishes are shared with admins" confirmation on first-ever wish |
| Live transcode progress | Real-time progress bar on transcode wishes |

### Camera Settings (Medium Priority)

| Feature | Detail |
|---|---|
| go2rtc binary path/port settings | Fields for go2rtc configuration (non-Docker) |
| Test Snapshot button | Opens snapshot preview from camera |
| Duplicate camera button | Clones camera config with "(copy)" suffix |
| RTSP URL display (redacted) | Show redacted URL in grid column |

### Live TV Viewer (Low Priority — playback works via separate player route)

| Feature | Detail |
|---|---|
| Channel navigation (prev/next) | Arrow buttons and keyboard shortcuts for stepping channels |
| Channel picker combobox | Dropdown to jump to any channel |
| Status indicator | Tuning/Playing/Buffering/Stalled/Error display |
| Quality rating (admin) | Clickable star rating to rate channel reception |
| Fullscreen toggle | Button to enter/exit browser fullscreen |

### Profile (Low Priority)

| Feature | Detail |
|---|---|
| Attempt limit on password change | Track attempts (max 5) with remaining count display |
| Session type badges | Browser vs Roku Device with colored type badges |
| Session expiry display | Shows expires timestamp or "Never" for Roku devices |

### Movies / TV Shows (Low Priority)

| Feature | Detail |
|---|---|
| Tag filter chips | Multi-select tag filtering in title grid |

### Settings (Low Priority)

| Feature | Detail |
|---|---|
| Legal documents section | Privacy policy URL/version, iOS/web terms of use URLs/versions |

### Actor Page (Low Priority)

| Feature | Detail |
|---|---|
| Biography expand/collapse | "Show more/less" toggle for long bios (>500 chars) |

### Amazon Import (Low Priority)

| Feature | Detail |
|---|---|
| Product condition column | Show condition (New/Used) |
| "Hide cancelled" filter | Checkbox to show/hide cancelled orders |

### Live TV Settings (Low Priority)

| Feature | Detail |
|---|---|
| Tuner edit dialog | Edit tuner name and IP with re-validation |
| Channel network inline edit | Inline text field for network affiliation |

### Transcode Status (Low Priority)

| Feature | Detail |
|---|---|
| Real-time event updates | Live push updates via SSE (currently static/polling) |

---

## Phase 4: Remove Vaadin and Jetty

Once all deficits above are resolved:

1. Armeria serves Angular SPA at `/` instead of `/app/`
2. Delete Vaadin dependencies from `build.gradle.kts` and `libs.versions.toml`
3. Delete all Vaadin view files (~43 `*View.kt`, `*Dialog.kt`, `MainLayout.kt`, `AppShell.kt`)
4. Delete `SecurityServiceInitListener.kt`
5. Delete all `*Servlet.kt` and `*Filter.kt` files
6. Remove `VaadinBoot` block from `Main.kt`
7. Remove `--port` flag (was Jetty's port)
8. Simplify Dockerfile (no Vaadin frontend build step)
9. Update `CLAUDE.md`, `README.md`, `docs/` for new architecture
