# Madmom time-signature detection sidecar for mediaManager.
#
# Packaging: standard python:3.10-slim (glibc) rather than Alpine.
# madmom's scientific-stack deps (numpy / scipy / pyfftw) don't have
# wheels for musl; building from source on Alpine is a non-trivial
# pain. Debian slim is ~30 MB bigger and Just Works.
#
# Exposes two ports inside the container:
#   9091  — gRPC MadmomAnalysis. Bound to 127.0.0.1 on the host by
#           docker-compose; reachable by mediamanager (which is on
#           host networking for Roku SSDP) via localhost.
#   8090  — Prometheus /metrics endpoint for host-local scraping.
#
# Logs: OTLP HTTP → http://binnacle:4318/v1/logs, authed with the
# BINNACLE_API_KEY env var. The sidecar joins the external
# binnacle_ingest Docker network (set up in Binnacle's compose) so
# it can reach the collector without being LAN-exposed.

# Python 3.9 rather than 3.10+ — madmom 0.16.1 (last release 2018)
# uses a setup.py pattern that trips modern pip build isolation, and
# its Cython shims don't import cleanly under the 3.10+ import machinery
# without patches. 3.9 is the sweet spot where `pip install madmom`
# still Just Works with the pinned scientific stack.
FROM python:3.9-slim

# System deps madmom needs:
#   - ffmpeg: audio decode backend for non-WAV inputs
#   - libsndfile1: wrapped by soundfile for FLAC / OGG reads
#   - build-essential + python3-dev: cython compile for madmom install
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        libsndfile1 \
        build-essential \
        python3-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Build context is the project root (set via docker-compose's
# `build.context: .`). Paths below are repo-root-relative so we can
# pull in the canonical proto without duplicating it.
COPY madmom-sidecar/requirements.txt .

# madmom's setup.py imports numpy at install time (pre-PEP 518 pattern)
# so the build backend needs numpy + Cython already in place BEFORE
# `pip install madmom` runs. --no-build-isolation keeps pip from
# spinning up an ephemeral env where those aren't visible.
RUN pip install --no-cache-dir \
        numpy==1.24.4 \
        cython==0.29.36 \
    && pip install --no-cache-dir --no-build-isolation -r requirements.txt

# Generate Python gRPC stubs from the shared proto. common.proto is
# pulled in alongside because madmom.proto imports it, and time.proto
# is the transitive dep of common.proto — without generating it here,
# the common_pb2 import fails at runtime with ModuleNotFoundError.
COPY proto/ proto/
RUN python -m grpc_tools.protoc \
        -Iproto --python_out=. --grpc_python_out=. \
        proto/madmom.proto proto/common.proto proto/time.proto \
    && ls -l *_pb2*.py

COPY madmom-sidecar/server.py .

EXPOSE 9091 8090
CMD ["python", "server.py"]
