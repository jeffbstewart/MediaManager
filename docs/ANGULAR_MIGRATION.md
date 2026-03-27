# Angular Migration Plan: Single-Port Armeria Server + Angular SPA

## Context

MediaManager currently runs three servers in one JVM:
- **Jetty :8080** — Vaadin web UI + 14 HTTP servlets (video, posters, roku, cameras, buddy, pairing, etc.)
- **Netty gRPC :9090** — 10 gRPC services, ~130 RPCs for iOS
- **Internal Jetty :8081** — health, metrics, logs

This requires two different HAProxy backends on two different hostnames, which is fragile and annoying. The goal is to collapse everything onto a single Armeria server on one port, replace Vaadin with an Angular 19 SPA, and simplify HAProxy to one backend — while keeping iOS, Roku, and Vaadin working throughout the transition.

---

## Phase 0: Armeria Replaces Standalone Netty gRPC

**Goal:** Swap the gRPC transport from bare Netty to Armeria on the same port (9090). Nothing changes externally.

### Changes
- `gradle/libs.versions.toml` — Add `armeria` and `armeria-grpc` dependencies. Remove `grpc-netty-shaded`.
- `build.gradle.kts` — Swap dependency references.
- **New file: `ArmeriaServer.kt`** — Replaces `GrpcServer.kt`. Registers all 10 gRPC services with the same `AuthInterceptor` + `LoggingInterceptor`. Configures `maxInboundMessageSize(16MB)`, `maxInboundMetadataSize(8KB)`, `flowControlWindow(4MB)` to match current settings.
- `Main.kt` — Replace `GrpcServer.start()/stop()` with `ArmeriaServer.start()/stop()`.
- **Delete `GrpcServer.kt`** after migration.

### Verify
- iOS app works through HAProxy (catalog, playback, downloads, admin streaming).
- Bidirectional `StreamImages` RPC works (image loading in iOS).
- Server-streaming RPCs work (`MonitorTranscodeStatus`, `WatchTranscodeProgress`, `MonitorScanProgress`).
- All existing gRPC tests pass (they use InProcessServer, unaffected).
- Vaadin on :8080 and internal server on :8081 unchanged.

### Key files
- `src/main/kotlin/net/stewart/mediamanager/grpc/GrpcServer.kt` (replace)
- `src/main/kotlin/net/stewart/mediamanager/Main.kt` (lines 156-158)
- `gradle/libs.versions.toml`
- `build.gradle.kts`

---

## Phase 1: Port HTTP Servlets to Armeria

**Goal:** Move all non-Vaadin HTTP endpoints from Jetty to the Armeria server. Armeria now serves gRPC + HTTP on port 9090 (internet-facing) and monitoring on port 8081 (LAN-only). Jetty :8080 serves only Vaadin.

### Auth decorator
Create `ArmeriaAuthDecorator` — an Armeria `DecoratingHttpServiceFunction` replicating `AuthFilter` logic:
- Cookie session auth (`mm_session` cookie → `session_token` table)
- JWT Bearer header (`Authorization: Bearer <token>`)
- JWT cookie (`mm_jwt` for HLS sub-requests)
- Device token (`?key=` parameter for Roku)

### Migration order (lowest risk first)

**Tier 0 — Internal-only endpoints (LAN, not internet-accessible):**
Armeria supports multiple `ServerPort` entries. Add a second listener on `:8081` (LAN-only, not port-forwarded through the router) for monitoring endpoints. This preserves the current security boundary.
1. `HealthServlet` → `/health` (on internal port; also exposed on :9090 for HAProxy health checks)
2. `MetricsServlet` → `/metrics` (internal port only)
3. `AppLogServlet` → `/admin/logs` (internal port only)
4. `RequestLogServlet` → `/admin/requests` (internal port only)

Retire the separate internal Jetty :8081 server once these are on Armeria's internal listener.

**Tier 1 — Stateless image/data endpoints:**
5. `PosterServlet` → `/posters/*`
6. `HeadshotServlet` → `/headshots/*`
7. `BackdropServlet` → `/backdrops/*`
8. `CollectionPosterServlet` → `/collection-posters/*`
9. `LocalImageServlet` → `/local-images/*`
10. `OwnershipPhotoServlet` → `/ownership-photos/*`
11. `PlaybackProgressServlet` → `/playback-progress/*`

**Tier 2 — Roku-critical:**
12. `RokuFeedServlet` → `/roku/*` (10+ JSON endpoints, device token auth)
13. `PairingServlet` → `/api/pair/*` (rate limiting, QR code generation)

**Tier 3 — Streaming (highest complexity):**
14. `VideoStreamServlet` → `/stream/*` (HTTP Range support via Armeria's `HttpFile`)
15. `CameraStreamServlet` → `/cam/*` (HLS relay proxy)
16. `LiveTvStreamServlet` → `/live-tv-stream/*` (FFmpeg HLS proxy)

**Tier 4 — Server-internal:**
17. `BuddyApiServlet` → `/buddy/*` (buddy API key auth)

### HAProxy consolidation
Once all servlets are on Armeria, both HAProxy backends point to NAS:9090. Simplify to one backend. The old hostname continues to work — Armeria serves both gRPC and HTTP on the same port, distinguished by `content-type: application/grpc`.

### Verify
- Roku: feed loads, playback works, pairing works — all through port 9090
- iOS: gRPC RPCs + image URLs + video streaming all through one port
- Vaadin: still works on :8080 (Jetty untouched)
- Buddy workers: claim/complete/heartbeat still work
- HAProxy health checks pass on new config

### Key files
- `src/main/kotlin/net/stewart/mediamanager/AuthFilter.kt` (reference for decorator)
- All `*Servlet.kt` files (port logic, then remove `@WebServlet` registration from Jetty)
- `Main.kt` (remove `startInternalServer()`, remove servlet registrations from Jetty)

---

## Phase 2: Angular Project + REST API Foundation

**Goal:** Create the Angular 19 project, build the REST API layer on Armeria, and get login + home page working.

### Angular project setup
- Location: `web-app/` at project root (monorepo subfolder, like `roku-channel/`)
- `ng new media-manager --directory web-app --style scss --routing --ssr false`
- Angular Material with dark theme
- Standalone components (no NgModules — Angular 19 style)
- `.gitignore` additions: `web-app/node_modules/`, `web-app/.angular/`

### REST API design
RESTful URLs under `/api/v2/`. JSON request/response bodies mirror proto message field names. The REST endpoints call the **same service layer** as gRPC (e.g., both `CatalogGrpcService` and the REST catalog endpoint call `SearchIndexService`, `TitleDao`, etc.).

```
POST /api/v2/auth/login          → JWT token pair
POST /api/v2/auth/refresh        → new token pair
POST /api/v2/auth/logout         → revoke
GET  /api/v2/info/discover       → server capabilities
GET  /api/v2/catalog/home        → home feed carousels
GET  /api/v2/catalog/titles      → paginated title grid
GET  /api/v2/catalog/titles/{id} → title detail
GET  /api/v2/catalog/search?q=   → search results
... (remaining endpoints added as Angular views need them)
```

### Auth for the SPA
JWT Bearer tokens (same as iOS). Angular `HttpInterceptor` attaches the token. Access token in memory, refresh token in HttpOnly cookie set by the login endpoint. No localStorage for tokens (XSS protection).

### Build integration
- Gradle does NOT build Angular. Separate `npm run build` step.
- Development: `ng serve` on :4200 with `proxy.conf.json` forwarding `/api/v2/*`, `/stream/*`, `/posters/*` etc. to localhost:9090.
- Production: Docker multi-stage build adds a Node stage for `npm run build`, output served by Armeria `FileService`.
- Armeria serves the compiled SPA at `/app/` with SPA fallback (unmatched `/app/*` routes return `index.html`).

### Angular structure
```
web-app/src/app/
  core/           # AuthService, JWT interceptor, route guards, SSE service
  shared/         # Shared components, pipes, models
  features/
    auth/         # Login, setup, change-password
    catalog/      # Home, browse, title detail, search
    ...           # (remaining feature modules added in Phase 3)
```

### Verify
- `ng serve` → login page renders → authenticates against `/api/v2/auth/login` → stores JWT → routes to home
- Home page loads carousels from `/api/v2/catalog/home`
- Production build served by Armeria at `/app/`
- Docker build includes Angular stage
- Vaadin still at `/`, Angular at `/app/` — both work

### Key files
- New: `web-app/` directory (Angular project)
- New: `src/main/kotlin/net/stewart/mediamanager/rest/` package (REST API endpoints)
- `ArmeriaServer.kt` (add FileService for SPA, add REST routes)
- `Dockerfile` (add Angular build stage)

---

## Phase 3: Angular Feature Parity

**Goal:** Build all Angular views to match Vaadin functionality. REST API endpoints are added incrementally as each view needs them. This is the longest phase.

### Sub-phase 3a: Viewer features
- Catalog browsing (poster grid with filters/sort)
- Title detail (cast, genres, tags, transcodes, playback progress overlay)
- Search (integrated search bar)
- Collections, tags, genres, actors browse pages
- Video player (HTML5 `<video>` consuming `/stream/{transcodeId}`)
- Cameras and live TV
- Profile and session management
- Wish list with TMDB search

### Sub-phase 3b: Admin features
REST endpoints added for admin operations. Real-time updates via **Server-Sent Events (SSE)** — Armeria streams `ServerSentEvent` objects backed by the existing `Broadcaster` infrastructure:

```
GET /api/v2/admin/transcode-progress  → SSE (replaces WatchTranscodeProgress gRPC)
GET /api/v2/admin/scan-monitor        → SSE (replaces MonitorScanProgress gRPC)
```

Admin views:
- Barcode scanning + scan detail + TMDB assignment
- Transcode status/unmatched/linked/backlog
- Data quality
- Amazon import, expand, valuation, document ownership
- Tag management, user management
- Settings (app, camera, live TV)
- Inventory report

### Sub-phase 3c: Polish
- Responsive mobile layout
- Loading states, error handling, empty states
- File upload for Amazon CSV import and ownership photos

### Verify at each sub-phase
- Side-by-side comparison: every Vaadin action is possible in Angular
- Angular at `/app/`, Vaadin at `/` — both work
- iOS and Roku unchanged

---

## Phase 4: Remove Vaadin and Jetty

**Goal:** Once Angular has full parity and you're satisfied, tear down Vaadin.

### Changes
1. Armeria serves Angular SPA at `/` instead of `/app/`
2. Delete from `build.gradle.kts`: `vaadin-core`, `vaadin-dev`, `vaadin-boot`, `karibu-dsl`, Vaadin plugin
3. Delete from `libs.versions.toml`: `vaadin`, `vok`, `karibu-dsl`, `karibu-testing`, `jetty-http2-server`
4. Delete all Vaadin view files (~43 `*View.kt`, `*Dialog.kt`, `MainLayout.kt`, `AppShell.kt`)
5. Delete `SecurityServiceInitListener.kt`
6. Delete all `*Servlet.kt` and `*Filter.kt` files (already ported in Phase 1)
7. Remove `VaadinBoot` block from `Main.kt`
8. Remove `--port` flag (was Jetty's port). `--grpc_port` becomes the sole `--port`.
9. `--internal_port` remains (Armeria's LAN-only listener for metrics/logs)
10. Simplify Dockerfile (no Vaadin frontend build step)
11. Update `CLAUDE.md`, `README.md`, `docs/` for new architecture

### Verify
- Single port: iOS, Roku, Angular all work through one HAProxy backend
- Internal port: metrics/logs still accessible on LAN only
- Docker image is smaller
- Startup is faster

---

## Architecture: Before and After

### Before (3 servers, 2 HAProxy backends)
```
HAProxy :8443
├── grpc.domain → NAS:9090 (Netty gRPC, iOS)
└── mm.domain   → NAS:8080 (Jetty, Vaadin + servlets + Roku)

NAS Docker:
├── Jetty :8080    (Vaadin + servlets)
├── Netty :9090    (gRPC)
└── Jetty :8081    (health/metrics, LAN-only)
```

### After (1 server, 2 ports, 1 HAProxy backend)
```
HAProxy :8443
└── *.domain → NAS:9090 (Armeria, everything internet-facing)

NAS Docker:
└── Armeria
    ├── :9090 (internet-facing, via HAProxy)
    │   ├── gRPC (HTTP/2, iOS, full bidi streaming)
    │   ├── /api/v2/* (JSON REST, Angular SPA)
    │   ├── /stream/*, /posters/*, /roku/*, /cam/*, etc. (ported servlets)
    │   ├── /health (HAProxy health checks)
    │   └── /* (Angular SPA static files)
    └── :8081 (LAN-only, not port-forwarded)
        ├── /health, /metrics (Prometheus scraping)
        └── /admin/logs, /admin/requests (monitoring)
```

---

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Web SPA protocol | JSON REST, not gRPC-Web | Simpler, debuggable in browser dev tools, no proxy needed |
| Real-time updates | SSE (Server-Sent Events) | Browser-native `EventSource`, Armeria supports natively, maps 1:1 to existing streaming RPCs |
| SPA framework | Angular 19 + Material | User familiarity, batteries-included, first-party Material library |
| Project location | `web-app/` monorepo subfolder | Atomic commits across API + UI changes |
| Build integration | Docker multi-stage, NOT Gradle | Avoids gradle-node-plugin fragility |
| REST API shapes | Mirror proto message field names | One mental model, shared service layer, no parallel DTOs |
| SPA auth | JWT Bearer in memory + refresh via HttpOnly cookie | Same as iOS, XSS-safe |
| Servlet migration | Armeria annotated services | Native HTTP Range, streaming, decorator pattern for auth |
| Internal port | Armeria dual-port (9090 public + 8081 LAN) | Preserves security boundary for metrics/logs |

## Implementation Order Summary

```
Phase 0: Armeria replaces Netty gRPC          ← smallest change, proves Armeria works
Phase 1: Port servlets to Armeria             ← single port achieved, HAProxy simplified
Phase 2: Angular project + REST API + login   ← SPA foundation
Phase 3: Angular feature parity               ← longest phase, incremental
Phase 4: Remove Vaadin/Jetty                  ← conscious decision, clean teardown
```

## What NOT to Remove Until Phase 4

The following must stay working until the conscious decision to tear down Vaadin:
- All Vaadin view files and `MainLayout.kt`
- `VaadinBoot` startup in `Main.kt`
- Jetty on port 8080
- `SecurityServiceInitListener.kt`
- `AppShell.kt`
- Vaadin dependencies in `build.gradle.kts`
