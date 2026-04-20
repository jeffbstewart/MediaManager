"""
Madmom time-signature detection sidecar.

Exposes a gRPC MadmomAnalysis server on 0.0.0.0:9091 and a Prometheus
metrics server on 0.0.0.0:8090. Logs go to Binnacle via OTLP HTTP.

Request flow:
  MM sends AnalyzeRequest(file_path) ->
  we feed the file through madmom's RNNDownBeatProcessor ->
  DBNDownBeatTrackingProcessor with beats_per_bar=[3, 4] picks the
  most likely bar length ->
  we count beats, compute a confidence from the downbeat-class
  posterior, and return AnalyzeResponse(bpm, time_signature, ...).

Single-threaded inference: madmom's processors aren't thread-safe and
NumPy BLAS already parallelizes internally. We use a single gRPC
executor thread so one call at a time runs; MM's TimeSignatureAgent
only submits one at a time anyway.
"""

from __future__ import annotations

import logging
import os
import sys
import time
from concurrent import futures
from typing import Optional

import grpc
import numpy as np
from prometheus_client import Counter, Histogram, start_http_server

# Generated stubs live in the same directory as this file after the
# Dockerfile's grpc_tools.protoc run.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import madmom_pb2
import madmom_pb2_grpc
import common_pb2  # for Empty


# ------------------------------------------------------------------
# Logging → Binnacle (OTLP HTTP)
# ------------------------------------------------------------------
def setup_logging() -> None:
    """Wire stdlib logging through OpenTelemetry → Binnacle.

    BatchLogRecordProcessor flushes on a background thread so the
    inference path never blocks on network I/O. BINNACLE_API_KEY is
    required; missing it makes us log locally only (good for tests).
    """
    # Always have stderr output — survives a misconfigured OTLP exporter.
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )

    endpoint = os.environ.get("BINNACLE_OTLP_ENDPOINT", "http://binnacle:4318/v1/logs")
    api_key = os.environ.get("BINNACLE_API_KEY")
    if not api_key:
        logging.warning("BINNACLE_API_KEY not set — OTLP shipping DISABLED for this run")
        return

    try:
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
        from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
        from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
    except ImportError as e:  # pragma: no cover
        logging.warning("OpenTelemetry SDK not available (%s) — OTLP shipping DISABLED", e)
        return

    resource = Resource.create({
        "service.name": "mediamanager-madmom",
        "service.version": os.environ.get("SIDECAR_VERSION", "dev"),
    })
    provider = LoggerProvider(resource=resource)
    provider.add_log_record_processor(BatchLogRecordProcessor(
        OTLPLogExporter(
            endpoint=endpoint,
            headers={"Authorization": f"Bearer {api_key}"},
        )
    ))
    handler = LoggingHandler(level=logging.INFO, logger_provider=provider)
    logging.getLogger().addHandler(handler)
    logging.info("OTLP logging wired to %s", endpoint)


# ------------------------------------------------------------------
# Metrics
# ------------------------------------------------------------------
ANALYSIS_TOTAL = Counter(
    "madmom_analysis_total",
    "Madmom analyses completed, partitioned by outcome.",
    ["outcome"],  # success / failure / missing_file
)
ANALYSIS_SECONDS = Histogram(
    "madmom_analysis_seconds",
    "Time spent analyzing one track.",
    buckets=(1, 2, 5, 10, 20, 30, 60, 120),
)


# ------------------------------------------------------------------
# Madmom wrappers
# ------------------------------------------------------------------
class MadmomEngine:
    """Lazy-loads madmom's processors on first use so startup is fast
    and health probes come up immediately. First real request pays the
    ~2 s model-load cost; subsequent requests are steady-state."""

    def __init__(self) -> None:
        self._in = None
        self._out = None
        self._ready = False
        self._version = "unknown"

    def _ensure_loaded(self) -> None:
        if self._ready:
            return
        # Imported late so a missing madmom install surfaces at first
        # request rather than at container start.
        import madmom  # type: ignore
        from madmom.features.downbeats import (  # type: ignore
            RNNDownBeatProcessor,
            DBNDownBeatTrackingProcessor,
        )
        logging.info("Loading madmom models (version=%s)", madmom.__version__)
        self._version = madmom.__version__
        self._in = RNNDownBeatProcessor()
        # beats_per_bar=[3, 4] tells the DBN to decide between 3/4
        # (waltz) and 4/4 (common time). Adding 6 or 12 here is
        # possible but those time signatures are rare in our catalog
        # and the extra classes dilute accuracy.
        self._out = DBNDownBeatTrackingProcessor(beats_per_bar=[3, 4], fps=100)
        self._ready = True

    @property
    def ready(self) -> bool:
        return self._ready

    @property
    def version(self) -> str:
        return self._version

    def analyze(self, file_path: str):
        self._ensure_loaded()
        # RNN emits per-frame probabilities for (beat, downbeat) — a
        # 2D array, 100 fps. The DBN turns that into a list of
        # (time_in_seconds, beat_position_in_bar) tuples.
        activations = self._in(file_path)
        beats = self._out(activations)
        return activations, beats


# ------------------------------------------------------------------
# Service implementation
# ------------------------------------------------------------------
class MadmomAnalysisServicer(madmom_pb2_grpc.MadmomAnalysisServicer):
    def __init__(self, engine: MadmomEngine) -> None:
        self.engine = engine

    def Analyze(self, request, context):
        path = request.file_path
        started = time.monotonic()

        if not path or not os.path.isfile(path):
            ANALYSIS_TOTAL.labels(outcome="missing_file").inc()
            logging.warning("Analyze: file not found: %s", path)
            # Return an empty response rather than an error status;
            # MM's agent treats "no fields set" as "couldn't detect."
            return madmom_pb2.AnalyzeResponse()

        try:
            _activations, beats = self.engine.analyze(path)
        except Exception as e:
            ANALYSIS_TOTAL.labels(outcome="failure").inc()
            logging.error("Analyze failed for %s: %s", path, e, exc_info=True)
            return madmom_pb2.AnalyzeResponse()

        # beats is an array of (time_seconds, beat_position_in_bar).
        # beat_position_in_bar is 1-indexed; the maximum value equals
        # the detected bar length (3 or 4).
        if beats is None or len(beats) == 0:
            ANALYSIS_TOTAL.labels(outcome="failure").inc()
            logging.warning("Analyze: no beats detected in %s", path)
            return madmom_pb2.AnalyzeResponse()

        beat_count = len(beats)
        positions = np.array([b[1] for b in beats])
        # DBN output class == max beat position seen. The DBN commits
        # to a single bar length per track, so max == the chosen class.
        bar_length = int(round(positions.max()))
        if bar_length == 3:
            time_signature = "3/4"
        elif bar_length == 4:
            time_signature = "4/4"
        else:
            # Shouldn't happen with beats_per_bar=[3, 4], but guard.
            time_signature = None

        # BPM = (beats - 1) / elapsed_seconds. Integer round to match
        # our DB column; matches the Essentia rounding convention.
        first_time = float(beats[0][0])
        last_time = float(beats[-1][0])
        elapsed = max(last_time - first_time, 1e-6)
        bpm = int(round((beat_count - 1) * 60.0 / elapsed))
        if bpm < 1 or bpm > 400:
            bpm = None  # implausible — drop so MM stays with tag value

        # Confidence: fraction of detected beats whose class matches
        # the chosen bar length. A clean track scores ~1.0; a track
        # where the DBN oscillated between 3 and 4 scores lower.
        if bar_length in (3, 4):
            matches = int((positions == bar_length).sum())
            confidence = float(matches) / beat_count
        else:
            confidence = 0.0

        elapsed_wall = time.monotonic() - started
        ANALYSIS_SECONDS.observe(elapsed_wall)
        ANALYSIS_TOTAL.labels(outcome="success").inc()
        logging.info(
            "Analyze OK path=%s bpm=%s time_sig=%s confidence=%.2f beats=%d wall=%.2fs",
            path, bpm, time_signature, confidence, beat_count, elapsed_wall,
        )

        resp = madmom_pb2.AnalyzeResponse()
        if bpm is not None:
            resp.bpm = bpm
        if time_signature is not None:
            resp.time_signature = time_signature
        resp.downbeat_confidence = confidence
        resp.beat_count = beat_count
        return resp

    def Health(self, request, context):
        return madmom_pb2.HealthStatus(
            status="READY" if self.engine.ready else "LOADING_MODELS",
            madmom_version=self.engine.version,
        )


# ------------------------------------------------------------------
# Entry point
# ------------------------------------------------------------------
def main() -> None:
    setup_logging()

    # Prometheus on a separate port. The compose file binds this to
    # 127.0.0.1 only so LAN scrapers can't hit it.
    metrics_port = int(os.environ.get("MADMOM_METRICS_PORT", "8090"))
    start_http_server(metrics_port)
    logging.info("Prometheus /metrics serving on :%d", metrics_port)

    engine = MadmomEngine()
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=1),  # madmom is not thread-safe
        options=[
            # madmom inference runs 10-25 s per track; bump the server's
            # max receive size just for safety — our payloads are tiny
            # but the default (4 MB) would be annoying to hit accidentally.
            ("grpc.max_receive_message_length", 16 * 1024 * 1024),
        ],
    )
    madmom_pb2_grpc.add_MadmomAnalysisServicer_to_server(
        MadmomAnalysisServicer(engine), server
    )
    grpc_port = int(os.environ.get("MADMOM_GRPC_PORT", "9091"))
    server.add_insecure_port(f"0.0.0.0:{grpc_port}")
    server.start()
    logging.info("MadmomAnalysis gRPC serving on :%d", grpc_port)
    server.wait_for_termination()


if __name__ == "__main__":
    main()
