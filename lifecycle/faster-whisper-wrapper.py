#!/usr/bin/env python3
"""
Wrapper script that provides a faster-whisper-xxl compatible CLI interface
using the faster-whisper Python library. Used by the transcode buddy on
macOS where the Windows-only faster-whisper-xxl.exe is not available.

Usage (matches faster-whisper-xxl interface):
  python3 faster-whisper-wrapper.py input.mkv \
    --model large-v3-turbo --language en \
    --output_format srt --output_dir /path/to/output \
    --device cpu --compute_type int8
"""

import argparse
import os
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="faster-whisper wrapper")
    parser.add_argument("input", help="Input audio/video file")
    parser.add_argument("--model", default="large-v3-turbo")
    parser.add_argument("--language", default="en")
    parser.add_argument("--output_format", default="srt")
    parser.add_argument("--output_dir", required=True)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--compute_type", default="int8")
    parser.add_argument("--model_dir", default=None)
    args = parser.parse_args()

    try:
        from faster_whisper import WhisperModel
    except ImportError:
        print("ERROR: faster-whisper not installed. Run: pip3 install faster-whisper", file=sys.stderr)
        sys.exit(1)

    print(f"Loading model '{args.model}' (device={args.device}, compute_type={args.compute_type})...")
    model = WhisperModel(
        args.model,
        device=args.device,
        compute_type=args.compute_type,
        download_root=args.model_dir,
    )

    print(f"Transcribing: {args.input}")
    segments, info = model.transcribe(args.input, language=args.language)

    print(f"Detected language: {info.language} (probability {info.language_probability:.2f})")

    # Generate SRT output
    input_stem = Path(args.input).stem
    output_path = os.path.join(args.output_dir, f"{input_stem}.{args.language}.srt")

    with open(output_path, "w", encoding="utf-8") as f:
        for i, segment in enumerate(segments, 1):
            start = format_timestamp_srt(segment.start)
            end = format_timestamp_srt(segment.end)
            text = segment.text.strip()
            f.write(f"{i}\n{start} --> {end}\n{text}\n\n")
            if i % 50 == 0:
                print(f"  {i} segments written...")

    print(f"Subtitles written to: {output_path}")


def format_timestamp_srt(seconds):
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    secs = int(seconds % 60)
    millis = int((seconds % 1) * 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{millis:03d}"


if __name__ == "__main__":
    main()
