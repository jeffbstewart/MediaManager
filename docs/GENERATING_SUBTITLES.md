<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Generating Subtitles with Whisper

Media Manager can generate SRT subtitles from video audio using [OpenAI Whisper](https://github.com/openai/whisper). This uses the [whisper-standalone-win](https://github.com/Purfview/whisper-standalone-win) project, which packages [faster-whisper](https://github.com/SYSTRAN/faster-whisper) as a standalone Windows executable &mdash; no Python installation required.

Subtitle generation runs on the [Transcode Buddy](TRANSCODE_BUDDY.md), not the server. The buddy claims subtitle leases from the server after handling higher-priority transcode and thumbnail work.

---

## Prerequisites

- **NVIDIA GPU** with CUDA support (RTX 2000 series or newer recommended). The RTX 4090 with 24 GB VRAM can run any Whisper model. Minimum 6 GB VRAM for `large-v3-turbo`.
- **NVIDIA drivers** with CUDA support installed (verify with `nvidia-smi`).
- **FFmpeg** already installed (Whisper uses it internally for audio extraction).
- **Transcode Buddy** configured and running (see [Transcode Buddy](TRANSCODE_BUDDY.md)).

---

## Step 1: Download Faster-Whisper-XXL

1. Go to [whisper-standalone-win releases](https://github.com/Purfview/whisper-standalone-win/releases)
2. Download the latest `Faster-Whisper-XXL` `.7z` archive (not the plain "Whisper" variant)
3. Extract to `C:\whisper\` using 7-Zip (preserving directory structure):

```bash
"/c/ProgramData/chocolatey/tools/7z.exe" x "C:\Users\you\Downloads\Faster-Whisper-XXL.7z" -o"C:\whisper"
```

> **Note:** If `python310.dll` is missing or you get a "Failed to load Python DLL"
> error, your antivirus likely quarantined it during extraction. Add `C:\whisper`
> to your antivirus exclusion list and re-extract.

After extraction you should have:

```
C:\whisper\
  Faster-Whisper-XXL/
    faster-whisper-xxl.exe
    _xxl_data/                   (bundled Python + CUDA/cuDNN libraries)
```

4. Verify the installation:

```bash
C:\whisper\Faster-Whisper-XXL\faster-whisper-xxl.exe --help
```

---

## Step 2: Download the Whisper Model

The recommended model is **`large-v3-turbo`** &mdash; 1.6 GB download, 6 GB VRAM, ~100x realtime on a modern GPU, near-best accuracy.

**Option A: Automatic download (easiest)**

The first time you run Whisper with `--model large-v3-turbo`, it auto-downloads the model to `%USERPROFILE%\.cache\huggingface\`. This is a one-time ~1.6 GB download. No further setup needed.

**Option B: Manual download (offline machines)**

1. Download the CTranslate2 model from [HuggingFace](https://huggingface.co/Systran/faster-whisper-large-v3-turbo/tree/main)
   &mdash; you need all files in the repository (~1.6 GB total)
2. Place them in a local directory, e.g., `C:\whisper\models\large-v3-turbo\`
3. Set `whisper_model_dir=C:\whisper\models` in your buddy config

**Do NOT check the model into Subversion.** Model files are large binaries that don't diff well and would bloat the repository. They are downloaded once per machine and cached locally.

---

## Step 3: Test Whisper

Run a quick test on a short video file to verify GPU acceleration works:

```bash
C:\whisper\Faster-Whisper-XXL\faster-whisper-xxl.exe "\\NAS\YourShareName\media\DVD\SomeShortFile.mkv" \
    --model large-v3-turbo \
    --language en \
    --output_format srt \
    --output_dir C:\temp \
    --device cuda \
    --compute_type float16
```

You should see:
- `Device: cuda` in the output (not `cpu`)
- Progress lines as it transcribes
- An `.srt` file in `C:\temp\` containing timestamped subtitle cues

If you see `Device: cpu`, check that your NVIDIA drivers are current and CUDA is detected (`nvidia-smi`).

---

## Step 4: Configure the Transcode Buddy

Add the following to your `buddy.properties`:

```properties
# Whisper subtitle generation (optional — omit to skip subtitle leases)
whisper_path=C:\\whisper\\Faster-Whisper-XXL\\faster-whisper-xxl.exe
whisper_model=large-v3-turbo
# whisper_model_dir=C:\\whisper\\models    # uncomment for manual model location
# whisper_language=en                       # default: en
```

When `whisper_path` is set, the buddy claims `SUBTITLES` leases from the server and generates SRT files using Whisper. When unset, the buddy ignores subtitle work entirely.

---

## Model Comparison

| Model | Download | VRAM | Speed (RTX 4090) | Accuracy | Recommendation |
|-------|----------|------|------------------|----------|----------------|
| `large-v3` | 3.1 GB | ~10 GB | ~30x realtime | Best | Use if accuracy is critical |
| **`large-v3-turbo`** | **1.6 GB** | **~6 GB** | **~100x realtime** | **Near-best** | **Recommended** |
| `medium` | 1.5 GB | ~5 GB | ~150x realtime | Good | Faster, slightly lower quality |
| `small` | 0.5 GB | ~2 GB | ~250x realtime | Okay | Low VRAM or speed-critical |

---

## Estimated Processing Time

With the RTX 4090 and `large-v3-turbo` (~100x realtime):

| Content | Count | Avg Duration | Wall Time |
|---------|-------|-------------|-----------|
| Blu-ray movies | 381 | ~2 hours | ~7.5 hours |
| DVD movies | 22 | ~2 hours | ~0.5 hours |
| UHD movies | 12 | ~2 hours | ~0.25 hours |
| TV episodes | 1,990 | ~44 min | ~14.5 hours |
| **Total** | **2,405** | | **~23 hours** |

The buddy can process subtitles concurrently with video transcoding since Whisper uses the GPU compute cores while NVENC uses the dedicated encoder hardware.

---

## Output Files

Whisper generates an SRT file that the buddy places in two locations:

```
ForBrowser/BLURAY/Avatar.en.srt      # primary (served to players)
BLURAY/Avatar.en.srt                 # backup (alongside source MKV)
```

If Whisper fails (exit code != 0, empty output, or too few cues), a sentinel file is written instead:

```
ForBrowser/BLURAY/ConcertFilm.en.srt.failed
```

The sentinel tells the server not to re-queue this file. Delete it manually to retry after upgrading Whisper or switching models.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Device: cpu` instead of `cuda` | Update NVIDIA drivers. Run `nvidia-smi` to verify CUDA is available |
| Out of memory (OOM) | Switch to a smaller model (`medium` or `small`) or reduce `--compute_type` to `int8` |
| Garbled output on a specific file | The audio may be non-speech (music, effects). Delete the `.srt` and let the sentinel mechanism mark it as failed on the next attempt, or manually create a `.srt.failed` sentinel |
| Slow processing | Verify GPU is being used (`nvidia-smi` should show the process). Check that no other GPU-heavy tasks are competing |
| Model download hangs | Use Option B (manual download) and set `whisper_model_dir` in the buddy config |

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="TRANSCODE_BUDDY.md">Transcode Buddy</a> &bull;
  <a href="GETTING_STARTED.md">Getting Started</a>
</p>
