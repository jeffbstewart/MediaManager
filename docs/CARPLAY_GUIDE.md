<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# CarPlay Guide

Drive with your music collection. The Household Disc Keeper iOS app includes a CarPlay audio interface so you can browse and play your library through your car's display.

---

## Overview

When your iPhone is connected to a CarPlay-equipped vehicle (wired or wireless), Household Disc Keeper appears on the CarPlay home screen alongside Apple Music, Spotify, and other audio apps. Tapping the icon brings up a three-tab browse surface designed for driver-friendly use: large tap targets, minimal scrolling, and one-tap shuffle.

**What you get:**

- **Albums tab** — recently-added albums, each with cover art. Tap an album to see its tracks; tap any track to start there.
- **Playlists tab** — your custom playlists. Tap one to see its tracks; tap any to start.
- **Smart Playlists tab** — server-curated lists ("Most Played", "Recently Added Tracks", etc.) plus a pinned **Shuffle Library** row at the top for instant random playback.
- **Now Playing surface** — the system audio Now Playing screen renders the current track's album art, title, artist, and a progress scrubber. Transport (play, pause, skip ±10 s, seek) is driven by CarPlay's hardware buttons and on-screen controls.
- **Lock-screen / AirPods integration** — single-press an AirPod or tap the lock-screen play/pause button to toggle playback. Skip forward/backward maps to ±10 seconds. The same audio session powers both the in-car display and the phone's Now Playing surfaces.

---

## Prerequisites

- An **iPhone** running iOS 17 or later with Household Disc Keeper installed (via TestFlight or the App Store).
- A **CarPlay-capable vehicle** or aftermarket head unit. Wired and wireless both work.
- You're **signed in** to Household Disc Keeper on the iPhone. CarPlay uses the same account and connects to the same server.
- You've **downloaded or have network access to** your music library. Albums and tracks stream from your server over your phone's connection.

> CarPlay is automatic. There's no separate sign-in or pairing — once the app is installed and you're signed in on the phone, the CarPlay surface activates the moment the iPhone connects to the car. The phone-side service container (`AppServices`) is populated at process launch rather than on first view appear, so the CarPlay scene delegate finds a fully populated browse hierarchy on the first connection — no "Loading…" spinner stuck on the in-car screen while the phone catches up.

---

## Connecting

### Wired CarPlay
Plug the iPhone into the car's USB port with a data-capable Lightning or USB-C cable (depending on iPhone model). CarPlay launches automatically after a few seconds.

### Wireless CarPlay
1. On the car's screen, open the CarPlay setup menu (varies by vehicle).
2. On the iPhone, go to **Settings → General → CarPlay → Available Cars** and pick the car.
3. Future trips connect automatically when the phone is near the car and Bluetooth is enabled.

Once CarPlay is up, look for the Household Disc Keeper icon on the CarPlay home screen and tap to enter the app's browse surface.

---

## Using the App

### Albums tab

The first tab. Shows your **recently-added albums** in chronological order — newest first. Each row has a square cover thumbnail (loaded from your server) and the album name + year.

Tap an album to drill into its tracklist. Each track row shows its name and the per-track artist credit (when different from the album artist). Tap a track to start playback from there — the remainder of the album queues up automatically.

### Playlists tab

Your custom playlists, listed by name. Tap one to see its tracks; tap any track to start playback. Each track in the list shows a thumbnail of its parent album, so you can see at a glance which album a track came from.

### Smart Playlists tab

The third tab combines a one-tap shuffle with server-curated lists.

**Shuffle Library** (pinned at the top). Tap to start playing a random selection of up to 200 tracks from your library. No need to pick an album or playlist — driver's fast path from "I want music" to playback. Identical to the Shuffle Library button on the phone's Music tab.

**Smart playlists** (below the Shuffle Library row). These are server-defined virtual playlists like "Most Played" or "Recently Played Tracks". Tap one to see its tracks; tap a track to start playback.

### Now Playing

Once a track starts, CarPlay shows the standard system Now Playing surface: album art on the left, track title / artist / album stacked on the right, transport controls below. Tap the album art (or use the Now Playing tab in CarPlay's bottom row) to return to it from anywhere in the app.

### AirPods / lock-screen controls

The same Now Playing state is published to iOS's system surfaces:

- **AirPods single press** — toggles play/pause.
- **Apple Watch / lock screen** — play, pause, scrub, and the elapsed-time / duration display all stay in sync.
- **Control Center → music widget** — same.

These work whether you're in the car or not — they're driven by the same audio engine that powers in-car playback.

---

## Offline & connectivity

CarPlay streams audio from your Household Disc Keeper server over the phone's network connection (cellular or Wi-Fi). For best results, ensure either:

- Your phone has **cellular coverage** strong enough to stream music, OR
- You're connected to a **Wi-Fi hotspot** that can reach your home server, OR
- You've **downloaded the album** for offline playback first (see [Offline Playback](OFFLINE_PLAYBACK.md))

When the phone briefly loses or reconnects to the network — for example, getting in the car after a long idle — the app automatically detects stale connections to the server and reconnects transparently. Earlier versions sometimes showed "Couldn't load" errors after a long idle; the current version self-heals on the first retry.

---

## Known limitations

- **Search isn't wired in CarPlay yet.** The phone supports voice and text search, but CarPlay's search surface is currently deferred. Use the Albums / Playlists / Smart Playlists tabs to navigate. Voice-driven "play X" via Siri is on the roadmap.
- **Video playback isn't a CarPlay feature.** Apple's CarPlay framework only supports audio apps. Video stays on the phone.
- **Album art on the Now Playing screen is loaded lazily** — first-listen on a brand-new track may show a blank hero for a moment while the server image fetch completes. Cached art (typical after browsing an album in the phone or CarPlay) appears instantly.

---

## Troubleshooting

**"Couldn't load X" on a browse tab.** The app should auto-recover on the next tap. If repeated taps fail, force-quit the Household Disc Keeper app from the iPhone's app switcher and relaunch — this rebuilds the connection to the server. Persistent failures are usually a server-side issue (server down, network unreachable) rather than a CarPlay problem.

**Audio plays but no album art shows.** Make sure the iPhone is signed in to Household Disc Keeper and has network access to the server. Album art is fetched from the server, not bundled with the track.

**CarPlay icon doesn't appear.** Confirm Household Disc Keeper is installed on the phone (TestFlight or App Store) and that you've signed in at least once. Then disconnect and reconnect from the car.

**AirPods controls don't work for video.** Video uses a separate playback surface; the recent build wires AirPods toggle-play/pause to both audio and video. Older builds only wired audio.
