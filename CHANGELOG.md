# Changelog

History of user-visible changes per platform. Server changes are released continuously via Docker / Watchtower and aren't versioned individually — only the client apps are entered here.

For developer-facing change history, use `git log`.

## Release-cut convention

Each iOS TestFlight / App Store build is git-tagged immediately after submission so the next build's diff can be derived without manual hash bookkeeping.

- **Tag format:** `ios/<marketing>-<build>` — e.g. `ios/1.2-10` for `MARKETING_VERSION=1.2`, `CURRENT_PROJECT_VERSION=10`.
- **Tag prefix:** `ios/` so these don't fire the server release workflow (which triggers on `v*` tags).
- **How to tag:** run `lifecycle/tag-ios-build.sh` right after submitting to TestFlight. It reads the marketing + build versions from the pbxproj and pushes the tag.
- **Deriving the next changelog:** `git log ios/<previous-tag>..HEAD -- ios/MediaManager/`.

Server / Android TV / Roku get their own tag schemes (or none — the server is rolling).

---

## iOS 1.2 (build 10) — 2026-06-07

**TestFlight release.** First build that's fully usable offline for movies, TV episodes, and books on the same device; close-button responsiveness on Live TV / Camera streams is fixed; mini-player no longer flexes mid-navigation. CarPlay can cold-launch without unlocking the phone.

### Offline mode

- Offline Mode toggle moved from Profile to the top of the sidebar — reachable without any RPC (fixes #73)
- Every gRPC is gated at the client layer when offline, not just calls routed through the offline data model
- Tab views (Movies, TV, Books, Music) hold their spinner stably during loads instead of flashing an error/empty state
- Auto-clear of the offline flag on logout so a stale session preference can't strand a fresh login
- Hidden offline: Search, Collections, Tags, Family, Cameras, Live TV, Wish List, all admin tabs

### Offline resume (fixes #72)

- Per-device shadow store for playback + reading positions; survives an online→offline transition on the same device
- Movies / TV episodes resume at the right offset offline
- Books resume at the right locator offline
- Server stays canonical for cross-device sync; local shadow fills the gap when the server isn't reachable

### Mini-player

- Bar extends through the bottom safe area — no system-surface gap under it on devices with a home indicator (fixes #75)
- Stops flexing to ~half the screen during navigation transitions / page loads
- Browse Artists in Music + the search bar in Authors are now reachable above the mini-player instead of hidden behind it (fixes #78)
- Custom press-feedback button style replaces `.plain` so the close button on Live TV / Camera streams shows a dim + scale on tap
- Camera view shows the mini-player overlay so audio is controllable without leaving the camera feed
- Tags / collections that contain music CDs no longer show "Not Playable" on the album posters (fixes #76)

### Live TV / Cameras

- Close button on Live TV / Camera streams responds instantly (fixes #77). Was 2–7 seconds because the AVPlayer status observer iterated the entire HLS access log on every status change on the main thread; that loop is gone and the close-handler uses an explicit `onClose` closure rather than `@Environment(\.dismiss)` (which was flaky on item-based `fullScreenCover`).
- Press animation + medium haptic on the close button
- Live TV pauses the music queue when you tune a channel (same hand-off pattern movies already had)
- AVPlayer teardown deferred so the dismiss animation isn't blocked
- LiveStreamView body pinned to fill the available area — kills the mini-player half-screen flex that used to fire during the Connecting → playing branch transition

### Ebook reader

- Font size persists across reader sessions (fixes #67)
- Scroll position preserved when changing font size (fixes #69)
- Internal links + TOC entries actually navigate now (fixes #70)
- TOC dropdown shows entries on first open (was empty until you re-opened)
- TOC sheet + mini-player tint to match the reader's light / sepia / dark theme
- WebView respects the bottom safe area (fixes #68)
- Reader repaginates on resize + accounts for the mini-player when audio is playing

### CarPlay (#74 — verification pending)

- AppServices populated at process boot so the CarPlay scene's `whenReady` resolves immediately on first connect — no more "open the iOS app on the phone first"
- Auto-recovers from stale HTTP/2 connections (AirPods / lock-screen mini-player benefits from the same fix)
- Shuffle Library row added
- Dropped the dead-end Search delegate

### About page

- New About entry in the sidebar (next to Profile)
- Houses build info + App Legal + Server Legal — moved out of Profile and out of the sidebar's bottom toolbar
- Profile is now cleaner; the build-number chip is no longer the most prominent thing at the bottom of the sidebar

### Other

- gRPC client detects dead HTTP/2 streams + reconnects transparently — fewer "stuck app, need to log out and back in" reports
- Forced-password-change flow asks for the current password (was unsafe — a borrowed phone could rotate the password)
- Downloads view split into per-category sub-pages with a "Delete All" typed-confirmation
- Sign Out consolidated to Profile (was duplicated in the sidebar bottom bar)
- Search promoted to the top of the sidebar (most-frequent jump-anywhere entry)
- HomeView no longer paints empty when offline — feed carousels are populated from the local catalog caches
- Empty state for Music's "For You" rendered only when there are recommendations
