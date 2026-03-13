<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Roku Setup Guide

Stream your media collection on the living room TV with a custom Roku channel.

---

## Overview

Media Manager includes a sideloaded Roku channel that connects to your server, fetches your media catalog, and provides a full browsing and playback experience on the TV.

**What you get:**
- Movie and TV series grids with poster art
- Episode picker for multi-season shows
- Full video playback with Roku's native player
- Playback progress synced with the browser
- Subtitle support
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

### Enable ECP Network Control

The External Control Protocol (ECP) allows developer tools and scripts to send remote key presses to the Roku over the network. By default, this is restricted.

1. Go to **Settings &rarr; System &rarr; Advanced system settings &rarr; Control by mobile apps &rarr; Network access**
2. Change from **"Limited"** to **"Enabled"**

This accepts ECP commands from any device on the same private network. Without this, the `roku-remote.sh` script's keypress commands will return HTTP 403. (Query endpoints like `active-app` and `device-info` work regardless of this setting.)

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

Repeat the same steps with the `roku-channel.zip` from the newer release. Uploading a new zip replaces the previous installation. Your pairing token and playback progress are stored on the server, so nothing is lost.

---

## Pairing Your Roku

On first launch, the channel automatically discovers the Media Manager server on your local network using SSDP (no configuration needed).

### QR Code Pairing

1. The Roku displays a **QR code** on screen
2. **Scan it with your phone's camera** (or any QR reader)
3. Your phone opens a confirmation page in the browser: *"Link this Roku to your account?"*
4. Tap **Confirm** &mdash; the Roku receives a device token tied to your user account
5. The channel loads your media catalog

The pairing code below the QR code can also be entered manually at `http://<server>/pair?code=XXXXXX` if QR scanning isn't convenient.

### What the token does

The device token identifies which user is watching on this Roku. This enables:
- Per-user content rating filters (age-appropriate content)
- Personal playback progress tracking
- Subtitle preferences

The token is permanent and survives Roku reboots. It's revoked if you change your password or manually revoke it from **Active Sessions**.

---

## Using the Channel

### Navigation

- **Home screen** &mdash; Horizontal rows of movie and TV series posters
- **Left/Right** &mdash; Browse within a row
- **Up/Down** &mdash; Switch between rows
- **Select (OK)** &mdash; Open the detail screen for the focused title

### Detail Screen

Shows the poster, backdrop, description, rating, year, and genres. For TV series, an episode list appears below.

- **Play** &mdash; Start playback (or resume from last position)
- **Episodes** &mdash; Select a specific episode

### Playback

- **Play/Pause** &mdash; Toggle playback
- **Rewind/Fast Forward** &mdash; Seek backward/forward
- **Back** &mdash; Return to the detail screen (position is saved)

Progress syncs to the server every 60 seconds. Next time you open the title (on any device), you'll be offered to resume.

### Audio Setup

The Roku's **Settings &rarr; Audio &rarr; Digital Output Format** must be set to **Stereo**. When set to "Auto", the Roku may negotiate audio formats that cause video to play without sound.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Channel shows "Discovering server..." indefinitely | Server may not be reachable. Check that the Roku and server are on the same network. Try entering the server IP manually in Settings. |
| QR code won't scan | Use the text code shown below the QR code instead. Navigate to `http://<server>/pair?code=XXXXXX` on your phone. |
| Video won't play | Check that the file has a ForBrowser MP4 transcode. MKV files need transcoding first. |
| Video plays but no audio | Set Roku audio output to Stereo (Settings &rarr; Audio &rarr; Digital Output Format). |
| Channel shows old content | The feed caches for 5 minutes. Wait or restart the channel. |
| "Authentication failed" | Your device token may have been revoked (password change or manual revocation). The channel should re-enter the pairing flow. |

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="USER_GUIDE.md">User Guide</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a>
</p>
