<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Roku Channel Guide

Stream your media collection on the living room TV with a custom Roku channel.

---

## Overview

Media Manager includes a sideloaded Roku channel that connects to your server, fetches your media catalog, and provides a full browsing and playback experience on the TV. The channel supports multiple profiles — each paired to a different user account (and optionally different servers).

**What you get:**
- Multi-profile support — each profile pairs independently via QR code
- Home screen with browsable poster carousels
- Full-text search with categorized results (movies, TV, collections, tags, actors)
- Landing pages for collections, tags/genres, and actors
- Wishlist integration — add wishes from actor pages via the Options button
- Wish-fulfilled badges on Recently Added posters
- Episode picker for multi-season TV shows
- Full video playback with resume support
- Subtitle (closed caption) support with on/off toggle
- Playback progress synced across all devices (Roku, browser)
- QR code pairing (no typing server URLs on the remote)

---

## Prerequisites

- A **Roku streaming device** (any model) on the same network as your Media Manager server
- A [**Roku Developer account**](https://developer.roku.com/enrollment/standard) (free)
- Your Roku in **Developer Mode** (one-time setup)

### Enable Developer Mode

Using your **Roku remote**, press this sequence:

```
Home, Home, Home, Up, Up, Right, Left, Right, Left, Right
```

On the Developer Settings screen:
1. Select **"Enable installer and restart"**
2. Accept the license agreement
3. Set a **developer password** (write this down)
4. The device reboots

Verify by opening `http://<roku-ip>` in a browser and logging in as `rokudev`.

### Enable ECP Network Control (optional)

The External Control Protocol (ECP) allows developer tools and scripts to send remote key presses to the Roku over the network. This is only needed if you want to use the `roku-remote.sh` helper script.

1. Go to **Settings &rarr; System &rarr; Advanced system settings &rarr; Control by mobile apps &rarr; Network access**
2. Change from **"Limited"** to **"Enabled"**

Without this, `roku-remote.sh` keypress commands return HTTP 403. Query endpoints like `active-app` and `device-info` work regardless.

---

## Installing the Channel

### 1. Download the channel

Download **roku-channel.zip** from the [latest GitHub Release](https://github.com/jeffbstewart/MediaManager/releases/latest).

### 2. Upload to your Roku

1. Open `http://<roku-ip>` in a browser (use your Roku's IP address)
2. Log in with username `rokudev` and the developer password you set above
3. Click **Upload**, select `roku-channel.zip`, and click **Install with zip**

The channel installs and auto-launches on your Roku.

### Updating the channel

Repeat the same steps with the `roku-channel.zip` from the newer release. Uploading a new zip replaces the previous installation. Profiles and playback progress are stored on the Roku and server respectively, so nothing is lost.

---

## Profiles and Pairing

### Profile Picker

On launch, the channel shows a **"Who's Watching?"** profile picker screen. Each profile represents a user account paired to a Media Manager server. Profiles can be on different servers.

- **Select a profile** to enter the home screen
- **"Add Profile"** (+) to pair a new user account
- **Press the Options (\*) button** on a focused profile to remove it

If no profiles exist (first launch), the channel skips straight to the pairing screen.

### QR Code Pairing

When adding a new profile:

1. The channel attempts **SSDP auto-discovery** of your Media Manager server on the local network
2. A **QR code** and pairing code appear on screen
3. **Scan the QR code with your phone's camera** (or any QR reader)
4. Your phone opens a confirmation page: *"Link this Roku to your account?"*
5. Tap **Confirm** — the Roku receives a device token tied to your user account
6. The profile is saved and you're taken to the home screen

The pairing code below the QR code can also be entered manually at `http://<server>/pair?code=XXXXXX` if QR scanning isn't convenient. If SSDP discovery fails, a manual server address entry option is available.

### What the token does

The device token identifies which user is watching on this profile. This enables:
- Personal playback progress tracking (resume where you left off, on any device)
- Per-user content filtering
- Subtitle preferences

Tokens are permanent and survive Roku reboots. They're revoked if you change your password or manually revoke from **Active Sessions** in the web app.

---

## Using the Channel

### Home Screen

The home screen displays horizontal rows of poster art, organized into carousels:

| Carousel | Contents |
|----------|----------|
| **Resume Playing** | Titles with saved playback progress for your account. Only appears if you have in-progress items. |
| **Recently Added** | Most recently added playable titles, newest first. |
| **Movies** | All playable movies, sorted by popularity. |
| **TV Series** | All playable TV series (with episode-linked transcodes), sorted by popularity. |

Each carousel only shows titles that have playable transcodes (MP4/M4V, or MKV/AVI with a completed ForBrowser transcode).

**Navigation:**
- **Left/Right** — Browse within a row
- **Up/Down** — Switch between rows (or move to the profile widget / search box)
- **Select (OK)** — Play a movie or open the episode picker for a TV series

**Profile widget** (upper right): Shows your profile avatar and username. Select it to open a dropdown with options to **switch profile** (return to the profile picker) or **remove this profile**.

### Search

A search box appears at the top of the home screen. Select it and type a query using the on-screen keyboard (or voice search). Results appear on a **Search Results** screen organized by category.

**Category chips** across the top let you filter results:
- **All** — Every result, ranked by relevance
- **Movies (N)** — Playable movies matching your query
- **TV Shows (N)** — TV series matching your query
- **Collections (N)** — TMDB collections (e.g., "Star Wars Collection")
- **Tags (N)** — Tags and genres matching your query
- **Actors (N)** — Cast members matching your query

Use **Left/Right** to switch chips and **Down** to browse the filtered poster grid. Selecting a result navigates to the appropriate screen:

| Result Type | Action |
|-------------|--------|
| Movie | Plays immediately |
| TV Series | Opens the episode picker |
| Collection | Opens a **Collection** landing page showing all titles in the collection |
| Tag / Genre | Opens a **Tag** landing page showing all titles with that tag |
| Actor | Opens an **Actor** landing page showing titles they appear in |

### Actor Landing Page

Shows the actor's headshot and a grid of titles from your collection that feature this actor. Select a title to play or browse episodes. Press the **Options (\*)** button on a focused title to add it to your media wish list (if not already wished).

### Collection Landing Page

Shows the collection poster and a grid of titles you own from that collection. Select any owned title to play or browse episodes.

### Tag / Genre Landing Page

Shows a grid of playable titles matching the selected tag or genre. Select any title to play or browse episodes.

### Wish-Fulfilled Badge

Titles on the **Recently Added** carousel that fulfill a wish from your wish list display a gold star badge on the poster thumbnail, so you can quickly spot newly available titles you asked for.

### Episode Picker (TV Series)

When you select a TV series, the episode picker shows:
- **Left column** — Season list (Season 1, Season 2, etc.)
- **Right column** — Episodes for the focused season

Navigate between seasons and episodes with the arrow keys. Select an episode to start playback.

### Playback

During video playback:

| Button | Action |
|--------|--------|
| **Play/Pause** | Toggle playback |
| **Rewind / Fast Forward** | Seek backward / forward |
| **Options (\*)** | Toggle subtitles (CC) on/off |
| **Back** | Stop and return to previous screen (position is saved) |

**Resume:** If you've previously watched part of a title, a dialog asks whether to **Resume** from your last position or **Start Over**.

**Progress sync:** Playback position is reported to the server every 60 seconds and when you exit. Next time you open the title — on any device (Roku or browser) — you'll be offered to resume.

**Subtitles:** If an SRT subtitle file exists alongside the media file on the NAS, subtitles are available. They're enabled by default; press **Options (\*)** during playback to toggle. The current CC state (On/Off) is shown on the pause overlay.

### Audio Setup

The Roku's **Settings &rarr; Audio &rarr; Digital Output Format** must be set to **Stereo**. When set to "Auto", the Roku may negotiate audio formats that cause video to play without sound.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Channel shows "Discovering server..." indefinitely | Server may not be reachable. Check that the Roku and server are on the same network. Try entering the server address manually. |
| QR code won't scan | Use the text code shown below the QR code instead. Navigate to `http://<server>/pair?code=XXXXXX` on your phone. |
| Video won't play | Check that the file has a ForBrowser MP4 transcode. MKV/AVI files need transcoding first (see Transcodes > Status in the web app). |
| Video plays but no audio | Set Roku audio output to Stereo (Settings &rarr; Audio &rarr; Digital Output Format). |
| Home screen shows old content | The feed caches for 5 minutes. Wait or restart the channel. |
| "Authentication failed" | Your device token may have been revoked (password change or manual revocation). Remove the profile and re-pair. |
| Subtitles don't appear | Verify the SRT file exists on the NAS (named `{filename}.en.srt`). Check that subtitles display in the web app first. Press Options (\*) to toggle CC on. |
| TV series appears but has no episodes | The title needs episode-linked transcodes. Check Transcodes > Linked in the web app. |
| Search returns no results | Verify the server is reachable and the query matches title names, actor names, collection names, or tag names. Results only include playable titles. |

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="USER_GUIDE.md">User Guide</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a>
</p>
