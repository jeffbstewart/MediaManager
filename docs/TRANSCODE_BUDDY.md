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

- **Windows or Linux machine** with an NVIDIA GPU (RTX 2000 series or newer recommended)
- **Java 25+** (Amazon Corretto 25 recommended)
- **FFmpeg** with NVENC support (full build, not minimal)
- **Network access** to both the Media Manager server (HTTP) and the NAS media files (mounted filesystem)
- *Optional:* **Faster-Whisper** for AI subtitle generation (see [Whisper Subtitles](#whisper-subtitles))

### 1. Build the buddy

From the project root:

```bash
./gradlew :transcode-buddy:build
```

The fat JAR is produced at `transcode-buddy/build/libs/transcode-buddy-all.jar`.

### 2. Configure

Copy `transcode-buddy/example.buddy.properties` to `buddy.properties` (in the directory where you'll run the buddy) and fill in your values:

```properties
server_url=http://your-server:8080
api_key=your-buddy-api-key-from-settings
buddy_name=my-worker
nas_root=\\NAS\PlexServedMedia\media
ffmpeg_path=C:\ProgramData\chocolatey\bin\ffmpeg.exe
ffprobe_path=C:\ProgramData\chocolatey\bin\ffprobe.exe
worker_count=1
encoder_preference=nvenc,qsv,cpu
poll_interval_seconds=30
progress_interval_seconds=15
```

| Property | Required | Purpose |
|----------|----------|---------|
| `server_url` | Yes | URL of the Media Manager server |
| `api_key` | Yes | API key from Media Manager UI: **Settings &rarr; Buddy Keys &rarr; New Key** |
| `buddy_name` | Yes | Name shown in the server UI for this worker |
| `nas_root` | Yes | Path to the NAS media root (must be the same files the server sees) |
| `ffmpeg_path` | No | Path to FFmpeg binary (default: system-dependent) |
| `ffprobe_path` | No | Path to FFprobe binary (default: system-dependent) |
| `worker_count` | No | Parallel workers (default 3). Usually 1 is enough &mdash; transcoding is GPU-bound |
| `encoder_preference` | No | Encoder priority list (default `nvenc,qsv,cpu`). See [Encoder Selection](#encoder-selection) |
| `poll_interval_seconds` | No | How often to check for work (default 30) |
| `progress_interval_seconds` | No | How often to report FFmpeg progress (default 15) |
| `whisper_path` | No | Path to faster-whisper CLI binary (enables subtitle generation) |
| `whisper_model` | No | Whisper model name (default `large-v3-turbo`). See [Whisper Models](#whisper-models) |
| `whisper_model_dir` | No | Custom directory for cached Whisper models |
| `whisper_language` | No | Subtitle language, ISO 639-1 code (default `en`) |

### 3. Run

```bash
java -jar transcode-buddy-all.jar buddy.properties
```

Or use the lifecycle script:

```bash
./lifecycle/run-buddy.sh
```

### 4. Verify

You should see:

```
[main] INFO TranscodeBuddy - Config loaded:
[main] INFO TranscodeBuddy -   Server:    http://your-server:8080
[main] INFO TranscodeBuddy -   Buddy:     my-worker
[main] INFO TranscodeBuddy -   NAS Root:  \\NAS\PlexServedMedia\media
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
encoder_preference=nvenc,qsv,cpu    # Try NVENC first, fall back to QSV, then CPU
```

---

## Whisper Models

The buddy uses [Faster-Whisper](https://github.com/SYSTRAN/faster-whisper) (a CTranslate2-based reimplementation of OpenAI's Whisper) for AI subtitle generation. Models are downloaded automatically on first use from Hugging Face and cached locally.

| Model | Size | Speed | Accuracy | Best For |
|-------|------|-------|----------|----------|
| `tiny` / `tiny.en` | ~75 MB | Fastest | Low | Testing, quick drafts |
| `base` / `base.en` | ~140 MB | Very fast | Fair | Quick subtitles, low-resource machines |
| `small` / `small.en` | ~460 MB | Fast | Good | Decent balance on modest GPUs |
| `medium` / `medium.en` | ~1.5 GB | Moderate | Very good | High accuracy without the large model cost |
| `large-v3` | ~2.9 GB | Slow | Best | Maximum accuracy, multilingual |
| **`large-v3-turbo`** | **~1.6 GB** | **Fast** | **Near-best** | **Recommended &mdash; best speed/accuracy tradeoff** |

Models ending in `.en` are English-only and slightly more accurate for English content. The multilingual models handle any language.

**Choosing a model:** Start with `large-v3-turbo` (the default). It's a distilled version of `large-v3` that runs significantly faster with minimal accuracy loss. Only switch to `large-v3` if you notice transcription quality issues with specific content. Use `small` or `medium` if your GPU has limited VRAM (< 4 GB).

For detailed comparisons, see the [OpenAI Whisper model documentation](https://github.com/openai/whisper#available-models-and-languages).

All Whisper components (OpenAI Whisper, faster-whisper, whisper-ctranslate2, model weights) are MIT licensed.

---

## Whisper Subtitles

The buddy generates SRT subtitles from video audio using Faster-Whisper. Install faster-whisper or whisper-ctranslate2 on the buddy machine and set `whisper_path` in `buddy.properties`. For detailed setup, see the [Generating Subtitles](GENERATING_SUBTITLES.md) guide.

When Whisper is available, the buddy claims subtitle leases from the server after handling higher-priority transcode and thumbnail work.

---

## Lifecycle Scripts

| Script | Purpose |
|--------|---------|
| `lifecycle/run-buddy.sh` | Start the transcode buddy |
| `lifecycle/stop-buddy.sh` | Stop the transcode buddy |
| `lifecycle/deploy-all.sh` | Full deploy: stop buddy, build Docker server image, restart buddy, sideload Roku |
| `lifecycle/docker-build.sh` | Build and push server Docker image to registry, trigger Watchtower |

---

## Why Not Docker?

We investigated running the buddy in a Docker container with NVIDIA GPU passthrough. While this works on **native Linux** hosts with the NVIDIA Container Toolkit, it does **not** work on **Windows (Docker Desktop + WSL2)** for video encoding:

- **WSL2 exposes CUDA compute** via `/dev/dxg`, so GPU-accelerated workloads like Whisper AI transcription work fine in Docker on Windows.
- **WSL2 does NOT expose `/dev/nvidia*` device nodes**, which are required for NVENC/NVDEC hardware video encoding. FFmpeg's `h264_nvenc` encoder reports "No capable devices found."
- This is a fundamental WSL2 platform limitation, not a configuration issue. NVIDIA's own documentation confirms that Video Codec SDK (NVENC/NVDEC) is not supported under WSL2.

Since transcoding is the buddy's primary and most resource-intensive workload, running it as a standalone process on a Windows or Linux machine with direct GPU access is the recommended approach.

**Note on NAS mounts in Docker on Windows:** If you do attempt Docker on Windows for CPU-only transcoding, be aware that Docker Desktop's WSL2 backend cannot see Windows mapped drives (e.g., `M:\`) or UNC paths. You must create Docker CIFS volumes to mount SMB shares:

```bash
docker volume create --driver local ^
  --opt type=cifs ^
  --opt "device=//NAS-HOSTNAME/ShareName/path" ^
  --opt "o=username=USER,password=PASS,vers=3.0" ^
  nas-media
```

Run this command in **cmd.exe** (not PowerShell or Git Bash) to avoid `$` and `%` being interpreted as variable expansions.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Cannot connect to server" | Check `server_url` and `api_key` in buddy.properties. Ensure the server is running. |
| "No encoder available" | FFmpeg doesn't have NVENC/QSV support. Install the full FFmpeg build or fall back to `encoder_preference=cpu`. |
| Transcode fails with "Permission denied" | The buddy process needs read/write access to the NAS share. Check mount permissions. |
| Buddy runs but no work appears | Check the server's Transcodes &rarr; Status page. The queue may be empty, or all files may already be transcoded. |
| GPU out of memory | Reduce concurrent workers to 1. NVENC uses minimal VRAM but large files can spike. |
| Many transcodes stuck as "failed" | Leases may have been poisoned by a misconfigured buddy. Use `POST /buddy/clear-failures?key={apiKey}` to reset all failed leases. |

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="ADMIN_GUIDE.md">Admin Guide</a> &bull;
  <a href="GETTING_STARTED.md">Getting Started</a>
</p>
