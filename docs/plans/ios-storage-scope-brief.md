# iOS per-server storage: agent brief

You are an agent fixing **one** issue from #68: content on the iOS
device that is not kept per server and account. Your prompt names the
issue: one of #70 to #75. This file, the issue, and the repository are your
whole context; you have no memory of earlier runs.

You run **on the operator's Mac** in your own git worktree. Cloud
sandboxes cannot build iOS, so this work is not a cloud chain row and
`docs/plans/chain-agent-brief.md` does not apply. Other agents are
working on the sibling issues at the same time in other worktrees.

## Read first, in this order

1. `CLAUDE.md` at the repo root.
2. The issue you were given (`gh issue view <n>`), then #68.
3. `docs/IOS_ARCHITECTURE.md`, the section "Per-server, per-account storage".
4. `ios/MediaManagerCore/Sources/MediaManagerCore/Services/StorageScope.swift`
   and its tests, `ios/MediaManagerCore/Tests/MediaManagerCoreTests/StorageScopeTests.swift`.
   This is the shared code (#69) every issue builds on. **Do not change
   it.** If your issue truly cannot be done without a change to it, stop
   and say so in your report; do not work around it.
5. The files your issue names.

## The rules your store must follow

- Register with `StorageScope.shared` (`register(_:)`) before reading
  `StorageScope.shared.current`, and conform to `StorageScopeParticipant`.
- **`storageScopeWillChange()`**: stop writing, cancel and *await* work
  that writes (downloads, flushes), close open files and databases, and
  drop in-memory state that came from the current scope. The folder may
  be deleted the moment this returns.
- **`storageScopeDidChange(to:)`**: with nil, serve nothing and write
  nothing. Otherwise load from `StorageScope.shared.directory(for: <your area>)`,
  then claim old content with `claimLegacy(at:into:as:)`, or with
  `discardLegacy(at:)` where the issue says to discard. Claiming is safe to
  repeat; it does nothing once the old content is gone.
- **On launch**, the scope may already be bound, restored from disk.
  Load from it, and claim old content, just as `storageScopeDidChange`
  would.
- **Never** keep a path computed from a scope after that scope changes,
  and never write outside your area's folder.
- Keep the store's public API as it is where you can, so the views do
  not change. Where a view must change, keep the change small.
- **Do not** change `AuthManager`'s storage events, the folder layout,
  or another issue's store. The image stream issue (#70) is the
  exception: it changes `GrpcClient`, `ImageStreamClient`,
  `ImageProvider`, `CachedImage` and `AuthManager`'s sign-out and
  server-change paths, and does not use `StorageScope`.

## Branch, commits, PR

- Branch from `main` (after #69 has merged): `agent/ios-scope-<issue number>-<short-name>`,
  for example `agent/ios-scope-72-video-downloads`.
- Commit messages follow `CLAUDE.md`: imperative summary line of about
  50 characters, body wrapped at 72, `-` bullets, and end with the
  `Co-Authored-By` trailer the session's instructions give.
- **Do not bump** `CURRENT_PROJECT_VERSION` or `MARKETING_VERSION`; the
  operator does that at the release cut. Do not commit the two `BB00003x`
  lines Xcode reorders in `project.pbxproj` on every build.
- Add one plain-language line for your change at the end of the
  "Merged so far" list under **In progress: content kept per server and
  account** in `ios/MediaManager/RELEASE_NOTES.md`, ending with
  `[#<issue>]`. Say what a user would notice, if anything. Other agents
  add lines in the same place; a merge conflict there is expected and
  the operator resolves it. Do not edit the rest of that entry.
- Open the PR against `main` with `gh pr create`. The body says
  `Fixes #<issue>.` and covers what changed, how it was tested, and
  what was **not** tested. End it with the attribution line the
  session's instructions give.
- Never force-push, never push to `main`, never merge, never tag.

## Verify before opening the PR

PR CI does not build iOS, so these runs are the only check.

1. Use your own simulator so you do not collide with other agents:
   `xcrun simctl clone "iPhone 17 Pro Max" "Agent-<issue>"`. If that fails,
   pick any available iPhone simulator not already booted, and boot it
   with `xcrun simctl boot`. Delete your clone when you are done.
2. Core unit tests:
   `cd ios/MediaManagerCore && xcodebuild test -scheme MediaManagerCore-Package -destination 'platform=iOS Simulator,id=<udid>'`.
   Write new tests with Swift Testing (`import Testing`), next to the
   existing ones in `MediaManagerCoreTests/`. Give a store a test seam
   (inject its base folder, or a `StorageScope` built over temp folders as
   `StorageScopeTests` does) rather than touching the real app folders.
3. App build: `cd ios/MediaManager && xcodebuild build -project MediaManager.xcodeproj -scheme MediaManager -destination 'platform=iOS Simulator,id=<udid>'`.
   No new warnings in files you touched.
4. UI tests: `xcodebuild test -project MediaManager.xcodeproj -scheme MediaManagerUITests -destination 'platform=iOS Simulator,id=<udid>'`.
   (`lifecycle/ios-test.sh` picks whichever simulator is booted, which
   may be someone else's, so call `xcodebuild` directly.)
5. Write logs under `data/` in your worktree, and read results from the
   logs. Never discard `xcodebuild` output to `/dev/null`.

The iOS build needs the generated protos (`lifecycle/ios-gen-protos.sh`)
if `MediaManager/Generated/` is missing in your worktree.

## Never

- Navigate into the server administration screens in a simulator.
- Install or upgrade software (brew, pipx, npm -g, Xcode components).
- Read files with the `.env` extension under `secrets/`.
- Write ad-hoc Python; use `jq` for JSON in the shell.
- Delete with a path variable that is not guarded (`"${DIR:?}"`), and
  check the target before deleting.

## Report

End with: the issue, the PR URL, the result of each verification step,
what you did not test, and anything the operator must decide.
