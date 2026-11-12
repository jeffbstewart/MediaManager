#!/usr/bin/env bash
# Run the transcode buddy against the App Store demo server.
# Thin wrapper around run-buddy.sh — defers to it for build,
# JAVA_HOME detection, OS handling, and the mutex check that
# prevents the production buddy from running concurrently.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/run-buddy.sh" buddy2.properties
