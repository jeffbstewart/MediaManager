# TestFlight Test Notes — iOS 1.2 (10)

Paste the section below verbatim into App Store Connect's "What to Test" field for this build. ~3400 chars, comfortably under the 4000 limit.

---

This build is mostly about making offline mode actually feel offline, killing a bunch of mini-player jank, and fixing the Live TV close button. If you have a long list, skip down to "What to Test" — that's the short version.

**Offline mode**

- The Offline Mode toggle moved from buried in Profile to the **top of the sidebar**. Reach it without firing any RPCs.
- When you flip offline mode on, the app now blocks **every** gRPC at the network layer — not just the ones routed through the offline data model. Background tasks (token refresh, log streamer, image stream, progress flushers) all respect the toggle.
- Auto-clear on logout so a previous session's offline flag can't strand you at the login screen.

**Offline resume — movies, TV episodes, books**

- Resume positions now persist on the device, not just on the server. Open a movie, watch a few minutes, go offline, reopen — you should land at the same spot. Same for TV episodes and books.
- The local shadow is updated on every progress write, even while online. So online → offline transitions keep the position you had a second ago.
- One known limit: cross-device resume is still server-only. If you watch on the phone, your other devices won't see your offline-only progress until the next time you're online (it then flushes).

**Mini-player**

- The bar now extends to the bottom of the screen so the system surface doesn't show through under it on devices with a home indicator.
- Stops flexing to half-screen during navigation / tab loads. There was a layout race triggered by the body switching between a tiny ProgressView and a full-screen ScrollView during initial load.
- Search bar in Authors view + Browse Artists row in Music are now reachable above the mini-player instead of hiding behind it.

**Live TV + Cameras**

- Close button on Live TV / Camera streams now responds instantly. The HLS access-log iteration on the player's status observer was burning seconds on the main thread per close — that's gone. Same fix unsticks the mini-player while a camera feed is open.
- Press feedback animation + medium haptic so the tap registers visually + tactilely before the cover slides away.
- Live TV pauses your music queue when you tune a channel (mirrors what movies already did). Cameras don't pause music — most cameras are silent and we assumed you're glancing, not watching.
- Camera view now shows the mini-player overlay so you can pause / skip music without leaving the camera feed.

**Music tags**

- Tags that contain music CDs no longer show "Not Playable" on the album posters. The flag is video-only — albums are always playable via the audio player.

**Ebook reader**

- Reader font size now persists across sessions.
- Scroll position is preserved when you change the font size.
- Internal links + TOC entries actually navigate now (were no-ops before).
- TOC sheet + mini-player tint to match the reader's theme (light / sepia / dark).
- Reader respects the bottom safe area + the mini-player when music is playing.

**CarPlay**

- Launches independently — you no longer need to open the iOS app on the phone first. The browse hierarchy is ready by the time the head unit's scene connects.
- Auto-recovers from stale HTTP/2 connections after the phone has been suspended (AirPods / lock-screen mini-player likewise).
- Shuffle Library row added.

**About page**

- New About entry in the sidebar (next to Profile). Build info + app legal + server legal moved here from their previous homes. The build-number chip is no longer in the sidebar bottom bar — it didn't deserve that prominence.

**What to test (top priority)**

1. Toggle Offline Mode from the sidebar. Confirm: no errors during navigation while offline; no surfaces try and fail RPCs.
2. Resume a movie / book offline that you watched online a few minutes earlier.
3. Tune a Live TV channel with music playing — music should pause. Close button responds in <1 second.
4. Open a camera with music playing — music keeps playing, mini-player is visible at the bottom of the camera view.
5. CarPlay first connection without unlocking the phone — the browse hierarchy should appear within a second.
6. Anything still misbehaving with the mini-player resizing during navigation, please report.
