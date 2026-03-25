# Offline Playback Architecture

Design document for downloading media and playing it back from the local filesystem on iOS.

## Storage Layout

Downloaded media lives in `Library/Application Support/Downloads/{titleId}/` with `isExcludedFromBackup = true` on each file.

```
Library/Application Support/Downloads/
  {titleId}/
    video.mp4          — pre-transcoded ForBrowser MP4 (H.264 + AAC)
    metadata.json      — title name, transcode ID, duration, chapters, subtitle paths
    poster.jpg         — cached poster for offline display
    subtitles.vtt      — WebVTT subtitles (if available)
```

### Why Application Support?

| Location | Backed Up | Purged by iOS | Visible in Files | Use |
|----------|-----------|---------------|------------------|-----|
| `Documents/` | Yes | No | Yes | User-visible files only |
| `Library/Application Support/` | Yes (opt-out per file) | No | No | App-managed persistent data |
| `Library/Caches/` | No | **Yes** | No | Re-downloadable content |
| `tmp/` | No | **Aggressively** | No | Truly temporary |

`Library/Application Support/` persists across app launches (unlike Caches, which iOS purges under storage pressure), doesn't show in the Files app (unlike Documents), and counts toward "Documents & Data" in Settings so users can see storage usage. Setting `isExcludedFromBackup = true` prevents multi-GB video files from bloating iCloud backups.

### Storage Space Check

Before starting a download, check available space:

```swift
let url = URL(fileURLWithPath: NSHomeDirectory())
let values = try url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
let availableBytes = values.volumeAvailableCapacityForImportantUsage ?? 0
```

## Download Engine

Use `URLSession` with a **background configuration** (`URLSessionConfiguration.background(withIdentifier:)`). This allows downloads to continue when the app is suspended or even terminated — the system wakes the app when downloads complete.

The existing HTTP endpoint `/stream/{transcodeId}` supports `Accept-Ranges` and `Content-Length` headers, enabling:

- **Progress tracking** via `URLSessionDownloadDelegate`
- **Resume** via `cancelByProducingResumeData` / `downloadTask(withResumeData:)`
- **Background completion** via `application(_:handleEventsForBackgroundURLSession:completionHandler:)` in AppDelegate

### Download State Machine

```
idle → queued → downloading(progress) → complete
                     ↓
                   paused → downloading(progress)
                     ↓
                  cancelled
                     ↓
                   error → queued (retry)
```

Track state with `@Published` properties for SwiftUI observation. Persist download state across app launches so interrupted downloads can resume.

## Video Player

**AVPlayer via AVPlayerViewController** — the same player component for both streaming and local playback. The only difference is the URL:

```swift
// Streaming
let url = URL(string: "https://server/stream/\(transcodeId)")!

// Local (downloaded)
let url = downloadDir.appendingPathComponent("video.mp4")
```

ForBrowser transcodes are MP4 with H.264 video + AAC audio, which AVPlayer handles natively. No VLC, FFmpeg, or third-party codec dependencies needed.

### Background Audio

Three requirements (already partially configured):

1. **Info.plist**: "Audio, AirPlay and Picture in Picture" background mode (already enabled)
2. **Audio session**: `.playback` category with `.moviePlayback` mode (already configured in `MediaManagerApp.init()`)
3. **On background**: Detach the player from its view layer so only audio continues

### Picture-in-Picture

`AVPlayerViewController` supports PiP natively with `allowsPictureInPicturePlayback = true`.

**PiP vs. background audio gotcha**: Both lock screen and PiP trigger `sceneDidEnterBackground`. If PiP is active, do NOT detach the player from the view (PiP needs the video layer). If PiP is NOT active (user locked screen or switched apps without PiP), detach for audio-only background. Detect PiP state via `AVPictureInPictureControllerDelegate`.

## Subtitles

AVPlayer has no native SRT support. Two approaches:

### Option A: Local HLS Manifest (Recommended)

Download the subtitle file as WebVTT alongside the video. Generate a local `.m3u8` manifest that references both:

```m3u8
#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="subtitles.vtt"
#EXT-X-STREAM-INF:SUBTITLES="subs"
video.mp4
```

AVPlayer plays this natively with subtitle selection in the transport controls.

### Option B: SRT Parser + Overlay

Parse SRT files and overlay text using a timer synced to `player.currentTime()`. More work, but avoids generating manifest files.

## Chapter Markers

Download chapter data in `metadata.json` and apply via `AVPlayerItem.navigationMarkerGroups`:

```swift
let chapters: [AVTimedMetadataGroup] = chapterData.map { chapter in
    let titleItem = AVMutableMetadataItem()
    titleItem.identifier = .commonIdentifierTitle
    titleItem.value = chapter.title as NSString
    titleItem.extendedLanguageTag = "und"
    let timeRange = CMTimeRange(start: chapter.startTime, duration: chapter.duration)
    return AVTimedMetadataGroup(items: [titleItem], timeRange: timeRange)
}
playerItem.navigationMarkerGroups = [AVNavigationMarkersGroup(title: nil, timedNavigationMarkers: chapters)]
```

AVPlayerViewController displays these as a chapter list in its transport controls.

## Playback Position Tracking

### Local Storage

A `PlaybackProgress` record per downloaded item:

```
transcodeId: Int64
position: Double        — seconds from start
duration: Double        — total duration
updatedAt: Date
synced: Bool            — has this been sent to the server?
```

Save position:
- Every 10 seconds during playback (via `addPeriodicTimeObserver`)
- On pause
- On app backgrounding
- On playback end

### Sync to Server

When network connectivity returns, batch-sync unsynced records to the server via the existing `PlaybackService.ReportProgress` gRPC RPC.

```swift
let monitor = NWPathMonitor()
monitor.pathUpdateHandler = { path in
    if path.status == .satisfied {
        syncPendingPlaybackPositions()
    }
}
monitor.start(queue: .global())
```

The server uses **last-write-wins**: if another device reported a newer position, the server keeps the newer one. Synced records are marked `synced = true` or deleted.

## Download Management UI

Users need to see and manage their downloads:

- **Total storage used** (sum of all downloaded files)
- **Per-item storage** (file size)
- **Delete individual downloads** (swipe-to-delete)
- **Delete all downloads** (settings action)
- **Download progress** (progress bar, pause/resume/cancel)
- **Storage warning** when device is low on space

This is critical for App Store acceptance and user trust — Netflix, Disney+, Plex, and Jellyfin all provide this.

## Metadata for Offline Browse

When offline, the app needs enough metadata to display the download list without server access:

```json
{
  "titleId": 42,
  "transcodeId": 17,
  "titleName": "The Matrix",
  "mediaType": "MOVIE",
  "year": 1999,
  "duration": 8160.0,
  "contentRating": "R",
  "quality": "FHD",
  "chapters": [
    {"title": "Opening", "startSeconds": 0},
    {"title": "The Red Pill", "startSeconds": 1842}
  ],
  "subtitleFile": "subtitles.vtt",
  "downloadedAt": "2026-03-24T20:00:00Z",
  "fileSize": 4294967296
}
```

The poster image is cached alongside the video so it displays without network.

## What's NOT Changing

- **Streaming playback** continues to work via HTTP `/stream/{transcodeId}` — no changes needed
- **Server-side transcoding** is unchanged — ForBrowser MP4 files are already the right format
- **The web UI** is unaffected — downloads are iOS-only
- **gRPC PlaybackService** is unchanged — the same `ReportProgress` RPC handles both streaming and offline position reports

## Open Questions

- **TV episodes**: Download individual episodes or entire seasons? Probably individual episodes with a "Download Season" batch action.
- **Low-storage transcodes**: Should ForMobile (smaller) transcodes be preferred for downloads? Could save significant space.
- **Auto-cleanup**: Should completed-and-watched downloads be auto-deleted after some period? Or always manual?
- **Concurrent downloads**: How many simultaneous downloads? Plex limits to 1-3. More than 3 tends to saturate bandwidth and slow everything down.
