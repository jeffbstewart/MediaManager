# Server-Side API for iOS App — Phased Implementation Plan

*Reference: [Issue #1](https://github.com/jeffbstewart/MediaManager/issues/1)*

## Context

Issue #1 defines a native iOS app that needs a decoupled `/api/v1/` REST API with JWT auth, catalog browsing, TV show support, video streaming, offline downloads via a mobile-optimized transcode format (ForMobile), and wish list endpoints. This plan covers the server-side work only — the iOS app is a separate project.

The work is organized into 7 implementation phases with 3 security audit gates. Each phase is independently deployable and testable. Security audits occur after authentication (the attack surface), after the first write operation (state mutation), and after adding sensitive content access (cameras).

---

## Phase 1: JWT Auth + Server Info ✅

**Status: Complete** (2026-03-18)

**Goal:** iOS app can discover the server, log in, and get tokens.

**New files:**
- `entity/RefreshToken.kt` — new entity (id, user_id, token_hash, device_name, expires_at, revoked)
- `service/JwtService.kt` — JWT generation (access 15min, refresh 30d), validation, refresh, revocation. HMAC-SHA256 signing key auto-generated, stored in `app_config`
- `api/ApiV1Servlet.kt` — `@WebServlet("/api/v1/*")`, Jackson, path routing, JWT Bearer auth from `Authorization` header
- `api/AuthHandler.kt` — `POST /auth/login`, `POST /auth/refresh`, `POST /auth/revoke`
- `api/InfoHandler.kt` — `GET /info` (version, API version, capabilities, title count)
- `db/migration/V065__refresh_token.sql`

**Modified files:**
- `gradle/libs.versions.toml` + `build.gradle.kts` — add `com.auth0:java-jwt:4.x`

**Verify:** curl login → get tokens → curl info with Bearer token → refresh → revoke

---

## Security Audit Gate 1: Authentication ✅

**Status: Passed** (2026-03-18) — 16/20 pass, 3 advisory, 1 low fail (fixed: body size limit + device_name truncation)

**When:** After Phase 1 is implemented and tested.

**Scope:** Review the JWT auth mechanism for:
- Token generation security (signing algorithm, key strength, key storage)
- Token lifetime appropriateness (access 15min, refresh 30 days)
- Refresh token rotation (is the old refresh token invalidated on use?)
- Brute-force resistance on login endpoint (rate limiting, timing attacks)
- Refresh token revocation completeness (logout, password change, admin delete)
- JWT claim validation (issuer, audience, expiration, user existence check)
- Signing key rotation strategy (what happens if the key is compromised?)
- HTTPS enforcement (does the API reject HTTP in production?)
- Error message information leakage (do auth failures reveal user existence?)
- Token storage guidance for iOS client (Keychain, not UserDefaults)
- CORS policy for the `/api/v1/` namespace
- Rate limiting on auth endpoints (login, refresh)

**Deliverable:** Security findings documented, critical issues fixed before proceeding.

---

## Phase 2: Catalog Browsing (Home, Titles, Search) ✅

**Status: Complete** (2026-03-18)

**Goal:** iOS app can browse the library.

**New files:**
- `api/CatalogHandler.kt` — `GET /catalog/home` (carousels), `GET /catalog/titles` (paginated, filterable by type/tag/genre/rating/playable/downloadable), `GET /catalog/titles/{id}` (full detail), `GET /catalog/search?q=`
- `api/ApiModels.kt` — shared DTOs (ApiTitle, ApiCarousel, ApiSearchResult)

**Reuses:** SearchIndexService, RokuSearchService query patterns, Title/CastMember/TitleGenre entities. Does NOT call RokuHomeService/RokuTitleService (those embed Roku-specific URLs).

**Depends on:** Phase 1

## Phase 3: TV Shows + Landing Pages ✅

**Status: Complete** (2026-03-18)

**Goal:** iOS app can browse TV seasons/episodes and drill into actors, collections, tags, genres.

**New files:**
- `api/TvHandler.kt` — `GET /catalog/titles/{id}/seasons`, `GET /catalog/titles/{id}/seasons/{num}/episodes` (includes per-episode stream availability + playback progress)
- `api/BrowseHandler.kt` — `GET /catalog/actors/{tmdbPersonId}`, `GET /catalog/collections/{tmdbCollectionId}`, `GET /catalog/tags/{id}`, `GET /catalog/genres/{id}`

**Depends on:** Phase 2

## Phase 4: Streaming + Playback Progress ✅

**Status: Complete** (2026-03-18)

**Goal:** iOS app can play video and track progress.

**Modified files:**
- `AuthFilter.kt` — add JWT Bearer as third auth method (after cookie, before device token). Sets USER_ATTRIBUTE on success. This is the only change to existing code — makes `/stream/*`, `/posters/*`, `/headshots/*`, `/backdrops/*` all work with JWT.
- `service/JwtService.kt` — add `validateFromHeader(authHeader)` helper for AuthFilter

**New files:**
- `api/PlaybackHandler.kt` — `GET/POST/DELETE /playback/progress/{transcodeId}` (thin wrapper around PlaybackProgressService)

**Depends on:** Phase 1

## Phase 5: Wish List API ✅

**Status: Complete** (2026-03-18)

**Goal:** iOS app users can browse, add, and vote on wishes.

**New files:**
- `api/WishListHandler.kt` — `GET /wishlist`, `POST /wishlist`, `DELETE /wishlist/{id}`, `POST /wishlist/{id}/vote`, `DELETE /wishlist/{id}/vote`

**Modified files:**
- `service/WishListService.kt` — add overloaded methods accepting explicit `userId` parameter (existing methods use VaadinSession which isn't available in servlet context)

**Depends on:** Phase 1

---

## Security Audit Gate 2: First Write Operations ✅

**Status: Passed** (2026-03-18) — 8/12 pass, 2 advisory, 3 fails (all fixed: Content-Length bypass, string length bounds, playback parental controls, position validation, JSON escaping, unvote audit logging)

**When:** After Phase 5 is implemented (wish list is the first API that mutates user-visible state).

**Scope:** Review write operations for:
- Authorization: Can a viewer-level user access admin-only mutations?
- Input validation: Are POST bodies size-limited and schema-validated?
- CSRF: Is the JWT Bearer token scheme immune to CSRF? (Yes, by design — Bearer tokens are not auto-attached by browsers like cookies, but verify no cookie fallback on write endpoints)
- Rate limiting on write endpoints (wish list add, vote)
- Idempotency: Can duplicate POSTs cause data corruption?
- User isolation: Can user A mutate user B's data?
- Playback progress: Can a user write progress for titles they can't see (parental controls bypass)?

**Deliverable:** Findings documented, issues fixed before proceeding.

---

## Phase 6: ForMobile Transcode Tier + Downloads ✅

**Status: Complete** (2026-03-18)

**Goal:** iOS app can download mobile-optimized videos for offline viewing.

**ForMobile transcode profile:**
- 1080p, H.264 High, 5.0 Mbps ABR (not CRF — predictable file sizes for shopping cart UX)
- AAC stereo 160k
- Path: `{nasRoot}/ForMobile/{relative}.mp4`
- Expected size: ~6 GB per 3-hour movie, ~4 GB per 2-hour movie (~160 movies per 1 TB iPad)
- Rationale: 720p at 1.5 Mbps looks soft on Retina iPad/iPhone displays. 1080p at 5 Mbps gives iTunes-quality downloads while still fitting 100+ movies per TB. ABR ensures predictable file sizes for the download cart UX.

**Config flag:** `for_mobile_enabled` in `app_config` (default: false). When disabled, the lease service never creates `MOBILE_TRANSCODE` work, so no ForMobile files are produced. The download API endpoints remain active but naturally return empty results (no `for_mobile_available` transcodes exist). The `GET /info` endpoint conditionally includes `"downloads"` in the capabilities list only when enabled. Enable after ForBrowser backlog is complete.

**New files:**
- `db/migration/V066__for_mobile.sql` — add `for_mobile_available BOOLEAN DEFAULT FALSE` to `transcode` table
- `service/ForMobileService.kt` — path computation (`getForMobilePath`), availability checks, startup reconciliation scan
- `api/DownloadHandler.kt` — `GET /downloads/available` (titles with ForMobile), `GET /downloads/manifest/{transcodeId}` (size, checksum), `POST /downloads/batch-manifest` (multiple items for cart checkout), `GET /downloads/{transcodeId}` (serve ForMobile MP4 with Range support)

**Modified files:**
- `entity/Transcode.kt` — add `for_mobile_available` field
- `entity/Enums.kt` — add `MOBILE_TRANSCODE` to LeaseType
- `service/TranscodeLeaseService.kt` — add MOBILE_TRANSCODE eligibility in `claimWork()`, gated by `for_mobile_enabled` config flag
- `service/TranscoderAgent.kt` — add ForMobile path helpers
- `transcode-common/.../TranscodeCommand.kt` — add `buildMobile()` with 1080p/5Mbps profile
- `transcode-common/.../EncoderProfile.kt` — add mobile encoder profiles
- `transcode-buddy/.../TranscodeWorker.kt` — handle MOBILE_TRANSCODE lease type
- `api/InfoHandler.kt` — conditionally include `"downloads"` in capabilities when `for_mobile_enabled` is true

**Depends on:** Phase 4 (AuthFilter JWT for serving downloads)

## Phase 7: Admin + Live Content ✅

**Status: Complete** (2026-03-18)

**Goal:** Admin monitoring from mobile, camera and live TV access.

**New files:**
- `api/AdminHandler.kt` — `GET /admin/transcode-status`, `GET /admin/buddy-status` (admin-only, access_level >= 2)
- `api/LiveHandler.kt` — `GET /live/cameras`, `GET /live/cameras/{id}/stream.m3u8`, `GET /live/tv/channels`, `GET /live/tv/{channelId}/stream.m3u8`

**Depends on:** Phase 1

---

## Security Audit Gate 3: Sensitive Content Access ✅

**Status: Passed** (2026-03-18) — 9/10 pass, 1 advisory (fixed: live TV content rating gate added to channel listing)

**When:** After Phase 7 is implemented (cameras and live TV expose real-time private feeds).

**Scope:** Review for:
- Camera stream authorization: Is the user's access level checked for camera access?
- Camera URL credential redaction: Are RTSP credentials stripped from API responses? (existing UriCredentialRedactor must be applied)
- Live TV content rating gate: Is the per-user quality/rating filter enforced?
- Stream URL leakage: Can API responses expose go2rtc or HDHomeRun internal URLs?
- Admin endpoint protection: Are all `/admin/*` endpoints restricted to access_level >= 2?
- Concurrency limits: Are live TV per-tuner and per-user stream limits enforced through the API?
- Download endpoint: Can a user download content they can't view (parental controls bypass)?
- JWT in streaming URLs: Are stream URLs safe to share? (They shouldn't contain the JWT — auth should be in headers only)

**Deliverable:** Findings documented, issues fixed before the API is exposed to the internet.

---

## Phase Dependency Graph

```
Phase 1: JWT Auth + Server Info
  │
  ├── [Security Audit Gate 1: Authentication]
  │
  ├── Phase 2: Catalog → Phase 3: TV + Landing Pages
  ├── Phase 4: Streaming + Playback Progress
  ├── Phase 5: Wish List
  │     │
  │     └── [Security Audit Gate 2: Write Operations]
  │
  ├── Phase 6: ForMobile + Downloads
  └── Phase 7: Admin + Live Content
        │
        └── [Security Audit Gate 3: Sensitive Content]
```

## Security: SSDP Discovery and MITM Protection

### Threat Model

SSDP discovery is inherently unauthenticated. A malicious device on the LAN can respond to M-SEARCH with a spoofed `LOCATION:`, redirect the app to a proxy, and capture credentials by forwarding the login request to the real server. HTTPS does not prevent this because the attacker controls which HTTPS endpoint the app connects to.

### Mitigations (implemented)

- **`secure_url` HTTPS validation:** The iOS client rejects any `secure_url` from `/discover` that does not start with `https://`. Prevents trivial downgrade to HTTP.
- **SSDP service type validation:** The client only accepts SSDP responses containing the expected `ST: urn:stewart:service:mediamanager:1` header. Filters out unrelated UPnP devices.
- **SSDP only on first connection:** Once the user has connected, the HTTPS URL is saved in Keychain and used directly on subsequent launches. SSDP is never re-run after first setup, limiting the attack window to a single moment.
- **No credentials over HTTP:** The SSDP→discover flow only retrieves the HTTPS URL. Login credentials are never sent until the app has switched to the HTTPS endpoint.

### Mitigations (planned): TOFU Server Fingerprint

Trust-on-first-use verification of the server's identity, independent of TLS certificates (which rotate every 90 days with Let's Encrypt).

**Server side:**
- Add `server_fingerprint` to the `/api/v1/discover` response: `SHA-256(jwt_signing_key)` — a hex digest of the server's JWT HMAC signing key. This value is stable (only changes during deliberate key rotation) and cannot be forged without possessing the signing key.

**iOS client side:**
1. On first successful login, save the `server_fingerprint` from `/discover` in Keychain alongside the server URL.
2. On every subsequent connection (app relaunch, reconnect, SSDP rediscovery), call `/discover` and compare the returned fingerprint to the stored one.
3. If the fingerprint matches: proceed normally.
4. If the fingerprint changes: **block the connection** and display a warning: *"This server's identity has changed since you last connected. This could mean the server's signing key was rotated, or it could indicate a security issue. If you did not rotate the server's signing key, do not proceed."*
5. Provide an "I rotated the key" button that clears the stored fingerprint and re-trusts.

**Why this works:** The attacker can spoof SSDP, spoof `/discover`, and present a valid TLS cert — but they cannot produce the correct `server_fingerprint` because they don't have the JWT signing key. The only unprotected moment is the very first connection (before any fingerprint is stored), which is inherent to TOFU and acceptable for a household app.

**Why not TLS certificate pinning:** Let's Encrypt certs rotate every 90 days. Pinning the cert would cause false alarms on every renewal. The JWT signing key is application-controlled and stable.

---

## Key Design Decisions

- **New `api/` subpackage** isolates iOS API code from Vaadin views and existing servlets
- **Jackson ObjectMapper** (not Gson) for JSON — matches RokuFeedServlet pattern
- **JWT via `com.auth0:java-jwt`** — lightweight, no transitive deps, HMAC-SHA256
- **No new services for catalog** — handlers assemble directly from entities, reusing SearchIndexService, TagService, etc.
- **AuthFilter extended, not replaced** — JWT added as third auth method, preserving cookie + device token
- **ForMobile uses 1080p/5Mbps ABR (not CRF)** — predictable file sizes for shopping cart UX; 1080p for Retina displays; ~160 movies per 1 TB iPad
- **ForMobile disabled by default** — `for_mobile_enabled` config flag, enable after ForBrowser backlog completes; download API returns empty results when disabled
- **Existing Roku/buddy APIs untouched** — complete decoupling

## Verification Strategy

Each phase verified with curl commands against the running server. Phase 4 additionally verified by confirming existing browser + Roku auth continues working after AuthFilter modification. Security audit gates block forward progress until findings are resolved.

---

## Pre-Implementation Security Audit: JWT Authentication Design

*Conducted 2026-03-17 against the proposed Phase 1 design.*

### Summary

The proposed design is fundamentally sound. The existing codebase already has mature security mechanisms (rate limiting with exponential backoff, account lockout at 20 failures, timing-equalized bcrypt comparison, proxy header handling). The JWT design inherits these protections. Four findings require resolution before implementation.

### Findings

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| 1 | HMAC-SHA256 algorithm choice | None | Correct for single-server |
| 2 | Signing key in DB | Medium | Acceptable with encryption-at-rest |
| 3 | Token lifetimes (15min/30d) | Low | Appropriate, matches session cookie TTL |
| 4 | **Refresh token rotation race condition** | **High** | Must fix |
| 5 | Login brute force | Low | Already handled by AuthService rate limiting |
| 6 | Login error message leakage | Medium | Must standardize |
| 7 | JWT claims incomplete | Medium | Must add iss/aud/sub |
| 8 | Access token theft blast radius | Medium | 15min TTL is primary mitigation |
| 9 | JWT replay | Low | Covered by HTTPS + short TTL |
| 10 | **Signing key compromise recovery** | **High** | Must document procedure |
| 11 | CORS | Low | Do not add; default deny is correct |
| 12 | **Server-side HTTPS enforcement** | **High** | Must implement |
| 13 | No cap on refresh tokens per user | Medium | Cap at 10 |
| 14 | **JWT in URLs** | **High** | Must strictly enforce Bearer header only |
| 15 | Password change revocation | Low | Add refresh tokens to existing invalidation |
| 16 | Account lockout DoS | Low | Already implemented; document tradeoff |

### Must-Fix Items (resolve during Phase 1 implementation)

**Finding 4 — Refresh Token Rotation Race Condition:**
Two concurrent refresh requests can cause one to fail (logout) or fork the token chain. Fix: implement a 60-second grace period — the old refresh token remains valid for 60s after rotation. Within the grace period, return the same new token pair (idempotent). After the grace period, treat old token use as potential theft and revoke the entire token family.

**Finding 10 — Key Compromise Recovery:**
Document a key rotation procedure: generate new key → update `app_config` → restart server → all access tokens invalidate immediately → users re-auth via refresh tokens. Implement dual-key validation during the rotation window (accept JWTs signed with either key for 15 minutes).

**Finding 12 — Server-Side HTTPS Enforcement:**
The API servlet must reject non-HTTPS requests in production. Check `req.isSecure` (which respects `ProxyHeaderFilter`'s `X-Forwarded-Proto` rewriting). Exception for localhost. Return 403 `{"error":"https_required"}`.

**Finding 14 — JWT Never in URLs:**
The API servlet must only accept JWTs from the `Authorization: Bearer` header. Never from query parameters, cookies, or form data. For streaming URLs where the iOS `AVPlayer` needs auth, use `AVURLAsset` with `HTTPAdditionalHeaders` (iOS supports this natively). The `AccessLogFilter` would log JWTs in cleartext if they appeared in URLs.

### Should-Fix Items (resolve during Phase 1 implementation)

**Finding 7 — JWT Claims:** Use `sub` for user_id (standard convention). Add `iss: "mediamanager"` and `aud: "mediamanager-api"` and validate both on every verification. Remove `username` from claims (unnecessary PII).

**Finding 13 — Refresh Token Cap:** Limit to 10 non-revoked refresh tokens per user, matching the existing session token cap. Evict oldest on overflow.

**Finding 6 — Auth Error Messages:** Always return `{"error":"invalid_credentials"}` for login failures. Never differentiate "user not found" from "wrong password". Return `{"error":"rate_limited","retry_after":N}` with HTTP 429 for throttled requests.
