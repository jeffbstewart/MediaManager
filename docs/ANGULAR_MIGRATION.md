# Angular Migration: Ready for Phase 4

Phases 0-3 are complete. The Angular SPA has feature parity with the
Vaadin UI for all production workflows. The remaining items below are
intentionally deferred — they are either low-impact cosmetic polish,
diagnostic tools, or features adequately covered by existing UI paths.

## Intentionally Deferred

These items were evaluated and consciously excluded:

- **BrowseView unified catalog** — Movies and TV pages cover this; tag browsing provides cross-type filtering
- **ChapterDebugView** — Diagnostic tool, not needed for production use
- **Live TV channel navigation** — Playback works via the player route; channel nav is convenience
- **Transcode Status real-time SSE** — Polling works; push updates are nice-to-have
- **Camera Settings go2rtc binary config** — Docker handles this; non-Docker users edit config directly
- **Profile session type badges/expiry** — Sessions display works; badge styling is cosmetic
- **Movies/TV tag filter chips** — Tags are accessible through the tag browse UI
- **Data Quality tag filter, seasons column, multi-pack flag, merge logic** — Edge cases; TMDB search in edit dialog covers the main workflow
- **Valuation ownership photos in edit dialog** — Photos viewable on title detail page
- **Valuation pricing agent status panel** — Agent runs automatically; status is monitoring

## Phase 4: Remove Vaadin and Jetty

1. Armeria serves Angular SPA at `/` instead of `/app/`
2. Delete Vaadin dependencies from `build.gradle.kts` and `libs.versions.toml`
3. Delete all Vaadin view files (~43 `*View.kt`, `*Dialog.kt`, `MainLayout.kt`, `AppShell.kt`)
4. Delete `SecurityServiceInitListener.kt`
5. Delete all `*Servlet.kt` and `*Filter.kt` files
6. Remove `VaadinBoot` block from `Main.kt`
7. Remove `--port` flag (was Jetty's port)
8. Simplify Dockerfile (no Vaadin frontend build step)
9. Update `CLAUDE.md`, `README.md`, `docs/` for new architecture
