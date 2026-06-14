# Siri simulator validation

Playbook for testing the SiriKit `INPlayMediaIntent` integration in the
iOS Simulator.

## Architecture

In-app handler — **no Intents Extension target**. The main app declares
itself as a Siri media app via top-level `Info.plist` keys, and
`AppDelegate.application(_:handlerFor:)` vends a `SiriIntentHandler`
for each incoming `INPlayMediaIntent`.

This mirrors how VLC, ShelfPlayer, and Amperfy ship Siri support. We
tried the extension-based architecture (Path 2) first — it works
end-to-end but adds a separate process, IPC, shared Keychain, App
Groups, and a separate App ID. None of that complexity is necessary
for media-play intents; the in-app handler is sufficient.

## What's wired

- `ios/MediaManager/MediaManager/Info.plist`:
  - `INIntentsSupported = [INPlayMediaIntent]`
  - `INSupportedMediaCategories = [INMediaCategoryMusic]`
  - `NSAppleMusicUsageDescription` — needed even for non-Apple-Music
    apps; cited as undocumented requirement on Apple Dev Forums.
  - `NSSiriUsageDescription` — user-facing prompt text.
  - `NSUserActivityTypes = [INPlayMediaIntent]` — lets the system route
    intents to the app via `userActivity`.
- `ios/MediaManager/MediaManager/Services/SiriIntentHandler.swift` —
  stub conforming to `INPlayMediaIntentHandling`. Resolves to one
  hardcoded `INMediaItem`, returns `.success` from `handle()`.
- `MediaManagerApp.swift` `AppDelegate.application(_:handlerFor:)` —
  vends `SiriIntentHandler()` for `INPlayMediaIntent`.

## Setup

```sh
# Boot a simulator and open it
xcrun simctl boot "iPhone 17 Pro"
open -a Simulator

# Build + install
./lifecycle/ios-build.sh --simulator
APP=$(find ~/Library/Developer/Xcode/DerivedData/MediaManager-* \
    -name MediaManager.app -path '*Debug-iphonesimulator*' | head -1)
xcrun simctl install booted "$APP"
xcrun simctl launch booted net.stewart.mediamanager
```

Grant Siri permission on first launch (Settings → Apps → MediaManager →
Siri & Search → Use with Ask Siri) OR accept the in-app prompt.

## Trigger Siri

**Voice path:** Device → Siri (`⌥⌘S`), hold and speak:
*"Play test on Household Disc Keeper"*

**Type-to-Siri (most reliable in sim):** Settings → Accessibility →
Siri → Type to Siri → ON. Then Device → Siri, type the phrase.

## Watch the handler fire

Tail the app's unified log filtered to our subsystem:

```sh
xcrun simctl spawn booted log stream \
    --predicate 'subsystem == "net.stewart.mediamanager"' \
    --level=debug
```

Expected sequence:

```
SiriIntentHandler init
resolveMediaItems: phrase=test
SiriIntentHandler init                                       (Siri's second handler instance for the handle phase — normal lifecycle)
handle: returning .success (Phase 1 stub — no playback yet)
```

And Siri voices "Now Playing Phase 1 stub track by media manager test"
— that's her reading back the `INMediaItem` we returned.

## Failure modes

- **"Household Disc Keeper hasn't added support for that with Siri"**
  — `INSupportedMediaCategories` or `INIntentsSupported` not in
  `Info.plist`, or category strings are wrong. Verify with:
  ```sh
  /usr/libexec/PlistBuddy -c "Print :INSupportedMediaCategories" \
    "$(xcrun simctl get_app_container booted net.stewart.mediamanager bundle)/MediaManager.app/Info.plist"
  ```
  Expected: array with `INMediaCategoryMusic`.

- **`SiriIntentHandler init` runs but `resolveMediaItems` doesn't** —
  `AppDelegate.application(_:handlerFor:)` not vending the handler.
  Check the `intent is INPlayMediaIntent` branch.

- **No log output at all** — Siri permission denied. Toggle off/on
  in Settings → Apps → MediaManager → Siri & Search.

## Next: real search + playback

Phase 2 (now collapsed since there's no extension/main-app handoff):
extend `SiriIntentHandler.handle` to call
`AppServices.shared.audioPlayer.play(...)` with a real track queue,
and `resolveMediaItems` to call `GrpcClient.searchMusicOnly` against
the catalog instead of returning the stub.
