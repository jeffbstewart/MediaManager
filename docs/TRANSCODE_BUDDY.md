<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Transcode Buddy

The Transcode Buddy is a standalone worker that offloads GPU-intensive transcoding from the server to a separate machine (typically a desktop with a dedicated GPU).

---

## Why Use a Buddy?

The Media Manager server can transcode files itself using CPU-based FFmpeg, but this is slow (especially for HEVC or MPEG-2 sources). A buddy worker running on a machine with an NVIDIA GPU can transcode 5-20x faster using NVENC hardware encoding.

The buddy also handles:
- **Thumbnail sprite generation** &mdash; FFmpeg extracts preview frames and tiles them into sprite sheets
- **Subtitle generation** &mdash; Whisper AI transcribes audio into SRT subtitle files

All three tasks run through the same lease-based work queue, so the server coordinates what needs doing and the buddy does the heavy lifting.

---

## How It Works

```
Server (mediaManager)              Buddy (transcode-buddy)
────────────────────               ───────────────────────
  Lease Queue                        Poll for work
  ┌──────────┐        claim          ┌──────────────┐
  │ Transcode ├──────────────────────► FFmpeg NVENC  │
  │ Thumbnails│        progress      │ FFmpeg thumbs │
  │ Subtitles │◄──────────────────────┤ Whisper AI   │
  └──────────┘        complete       └──────────────┘
                                          │
                                     Reads/writes NAS
                                     via SMB mount
```

1. The server maintains a queue of pending work (video transcodes, thumbnails, subtitles)
2. The buddy polls `POST /buddy/claim` to grab the next job
3. The buddy reads the source file from the NAS, transcodes it, and writes the output back to the NAS
4. The buddy reports progress and completion back to the server via REST API
5. The server updates its database and notifies connected UI clients

---

## Setup

### Prerequisites

- **Machine with NVIDIA GPU** (RTX 2000 series or newer recommended for NVENC)
- **FFmpeg** with NVENC support installed
- **Network access** to both the Media Manager server (HTTP) and the NAS (SMB/network share)
- **JDK 21+** installed

### 1. Build the buddy

From the project root:

```bash
./gradlew :transcode-buddy:installDist
```

The built distribution is at `transcode-buddy/build/install/transcode-buddy/`.

### 2. Configure

Create a `buddy.properties` file (in the directory you'll run the buddy from):

```properties
# Server connection
server_url=http://your-server:8080
api_key=your-buddy-api-key          # Generated in Settings → New Key (shown once)

# Identity
buddy_name=desktop-gpu              # Shows in the server UI

# NAS access
nas_root=\\\\NAS\\YourShareName\\media       # Windows UNC path
# nas_root=/mnt/media                       # Linux mount point

# Encoder preference (tries in order, falls back)
encoders=nvenc,qsv,cpu

# Workers (usually 1 — transcoding is GPU-bound)
workers=1

# Whisper subtitle generation (optional)
# whisper_path=C:\\whisper\\Faster-Whisper-XXL\\faster-whisper-xxl.exe
# whisper_model=large-v3-turbo
```

The **API key** is found in the Media Manager web UI under **Settings &rarr; Buddy API Key**.

### 3. Run

```bash
# Windows
transcode-buddy\build\install\transcode-buddy\bin\transcode-buddy.bat

# Linux/macOS
transcode-buddy/build/install/transcode-buddy/bin/transcode-buddy
```

Or use the lifecycle script:

```bash
./lifecycle/run-buddy.sh
```

### 4. Verify

The buddy logs its configuration on startup:

```
[main] INFO TranscodeBuddy - Config loaded:
[main] INFO TranscodeBuddy -   Server:    http://your-server:8080
[main] INFO TranscodeBuddy -   Buddy:     desktop-gpu
[main] INFO TranscodeBuddy -   NAS Root:  \\NAS\YourShareName\media
[main] INFO TranscodeBuddy -   Workers:   1
[main] INFO TranscodeBuddy -   Encoders:  [nvenc, qsv, cpu]
[main] INFO TranscodeBuddy - Selected encoder: nvenc (h264_nvenc)
```

In the Media Manager web UI, go to **Transcodes &rarr; Status**. The buddy panel shows the connected worker and its current activity.

---

## Encoder Selection

The buddy auto-detects available encoders by running FFmpeg probe commands:

| Encoder | FFmpeg codec | Speed | Quality | Notes |
|---------|-------------|-------|---------|-------|
| **nvenc** | `h264_nvenc` | Fastest | Very good | NVIDIA GPU required |
| **qsv** | `h264_qsv` | Fast | Good | Intel GPU (iGPU or Arc) |
| **cpu** | `libx264` | Slow | Best | Always available |

Configure preference order in `buddy.properties`:

```properties
encoders=nvenc,qsv,cpu    # Try NVENC first, fall back to QSV, then CPU
```

---

## Whisper Subtitles

The buddy can generate SRT subtitles from video audio using Faster-Whisper. See the [Generating Subtitles](GENERATING_SUBTITLES.md) guide for installation and configuration instructions.

When `whisper_path` is configured, the buddy claims subtitle leases from the server after handling higher-priority transcode and thumbnail work.

---

## Lifecycle Scripts

| Script | Purpose |
|--------|---------|
| `lifecycle/run-buddy.sh` | Build (if needed) and start the buddy |
| `lifecycle/stop-buddy.sh` | Stop the running buddy process |
| `lifecycle/deploy-all.sh` | Full deploy: stop buddy, build Docker image, restart buddy, deploy Roku |

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Cannot connect to server" | Check `server_url` and `api_key` in buddy.properties. Ensure the server is running. |
| "No encoder available" | FFmpeg doesn't have NVENC/QSV support. Install the full FFmpeg build or fall back to `encoders=cpu`. |
| Transcode fails with "Permission denied" | The buddy process needs read/write access to the NAS share. Check mount permissions. |
| Buddy runs but no work appears | Check the server's Transcodes &rarr; Status page. The queue may be empty, or all files may already be transcoded. |
| GPU out of memory | Reduce concurrent workers to 1. NVENC uses minimal VRAM but large files can spike. |

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a> &bull;
  <a href="GETTING_STARTED.md">Getting Started</a>
</p>
