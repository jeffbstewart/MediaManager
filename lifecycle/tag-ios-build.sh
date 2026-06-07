#!/bin/bash
# Tag the current HEAD as an iOS TestFlight / App Store build.
#
# Reads MARKETING_VERSION and CURRENT_PROJECT_VERSION out of the
# Xcode project and creates an annotated tag of the form
# `ios/<marketing>-<build>` (e.g. `ios/1.2-10`). The `ios/` prefix
# keeps these tags clear of the server release workflow, which
# fires on `v*` tags only.
#
# Use this right after submitting a build to TestFlight so the next
# release can derive its changelog with:
#     git log ios/1.2-10..HEAD -- ios/MediaManager/
#
# Usage:
#     lifecycle/tag-ios-build.sh           # tag + push
#     lifecycle/tag-ios-build.sh --dry-run # print the tag name only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PBXPROJ="$ROOT/ios/MediaManager/MediaManager.xcodeproj/project.pbxproj"

if [ ! -f "$PBXPROJ" ]; then
    echo "error: $PBXPROJ not found" >&2
    exit 1
fi

# Pull the first MARKETING_VERSION and CURRENT_PROJECT_VERSION the
# project file carries. The pbxproj lists them once per build
# configuration (Debug + Release) but the values are identical.
MARKETING="$(grep -m1 -E 'MARKETING_VERSION = ' "$PBXPROJ" | sed -E 's/.*MARKETING_VERSION = ([^;]+);.*/\1/' | tr -d '[:space:]')"
BUILD="$(grep -m1 -E 'CURRENT_PROJECT_VERSION = ' "$PBXPROJ" | sed -E 's/.*CURRENT_PROJECT_VERSION = ([^;]+);.*/\1/' | tr -d '[:space:]')"

if [ -z "$MARKETING" ] || [ -z "$BUILD" ]; then
    echo "error: failed to parse MARKETING_VERSION or CURRENT_PROJECT_VERSION from pbxproj" >&2
    exit 1
fi

TAG="ios/${MARKETING}-${BUILD}"

if [ "${1:-}" = "--dry-run" ]; then
    echo "$TAG"
    exit 0
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "error: tag $TAG already exists. Bump CURRENT_PROJECT_VERSION before re-tagging." >&2
    exit 1
fi

MSG="iOS TestFlight build ${MARKETING} (${BUILD})"
echo "Creating tag: $TAG"
git tag -a "$TAG" -m "$MSG"
echo "Pushing tag: $TAG"
git push origin "$TAG"
echo "Done. Next-build changelog cutoff: $TAG"
