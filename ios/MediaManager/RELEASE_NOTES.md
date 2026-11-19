# iOS Release Notes — changes since the App Store build

A running list of user-visible iOS changes made since the build that
App Review last reviewed. It is the source for the next submission's
App Store "What's New" text and TestFlight notes.

**Baseline: iOS 1.3 (21)**, submitted to App Review in November 2026
as the proposed first public version. It was built from `main` at
`77e3b68` (the merge of #65). For the full developer history since
then, run `git log 77e3b68..HEAD -- ios/MediaManager/`.

## How to keep this file

- Every pull request that changes something an iOS user can see adds
  a line under **Unreleased**, in plain language, with the issue or
  PR number in brackets at the end.
- When a build is submitted for review, rename **Unreleased** to that
  version and build (for example "1.3.1 (22)"), copy the entries into
  `CHANGELOG.md`, rewrite the **What's New draft** for the App Store
  (4000-character limit; measure it with `wc -c`), and move the
  baseline above to the new build.
- If App Review rejects a build and you resubmit with fixes, the
  baseline stays where it was; keep adding to the same section.

---

## Unreleased (build 22)

### Fixes

- Book reader: the toolbar at the top of the page now fits on large
  iPhones. Previously it ran off the right edge on an iPhone 16 Pro
  Max, cutting off the text-size buttons. The two text-size buttons
  are now one **Aa** button that opens a menu with smaller and larger
  options and the current size; the menu stays open while you tap, so
  you can change the size in several steps. [#66]
- Diagnostic logs: the app keeps sending its logs to the server after
  it has been in the background or after you sign out and back in.
  Before, it stopped after the first time, until the app was closed
  and reopened. Not visible in the app; it helps diagnose problems you
  report. [#84]
- Music: a track starts playing without waiting for its cover art.
  The art appears when it arrives. Before, a track could fail to
  start at all while the cover was still loading, even a downloaded
  one. [#85]
- Music: the playback bar appears on every page as soon as a track
  starts. Before, starting a track from an album, artist or playlist
  page often showed no bar, so there was no way to pause it in the
  app until you moved to another page. [#86]

### In progress: content kept per server and account [#68]

Not complete yet; finish this entry, and add it to the What's New
draft, when #70–#75 have merged. When done:

- After you switch to a different server, its own posters and
  artwork show. Previously the old server's images could show on
  unrelated titles, and other images never loaded.
- Switching to a different server removes the previous server's
  downloads, cached images and saved progress from the device.
  Signing in as a different account does the same for the previous
  account. Signing out and back in to the same account keeps them.
- After the update, existing downloads and progress are kept if they
  belong to the server the app is set up for.

Merged so far:

- Shared storage that tracks the current server and account, with no
  visible change on its own. [#69]
- Images now come from the server you are signed in to after you sign
  out, change server, or sign in as someone else; before, they kept
  coming from the previous server. Posters the previous server left in
  the image cache can still show until the cache change lands. [#70]
- Resume positions, and reading and listening progress not yet sent,
  are kept for the server and account that recorded them. Progress
  saved under one account or server is no longer sent to another,
  even if you switch while it is being sent. [#74]
- After you switch to a different server, the terms screen and the
  Downloads tab follow the new server from the start; before, they
  could reflect the previous server until the new one answered. [#75]
- Downloaded books and music are kept for the server and account that
  downloaded them. Signing out, changing server or signing in as
  someone else hides them and stops a downloaded song that is playing;
  signing back in to the same account brings them back. [#73]
- Cached posters and artwork are now kept per server and account, so
  another server's posters no longer show on your titles. The image
  cache starts empty once after the update, so images load again the
  first time you see them. [#71]

### What's New draft

> Fixed the book reader's toolbar running off the edge of the screen
> on larger iPhones. Text size is now under a single Aa button, and
> its menu stays open so you can step the size up or down.
