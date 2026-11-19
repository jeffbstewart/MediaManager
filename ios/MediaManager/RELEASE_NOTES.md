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
- Downloads: covers and artwork for downloaded books, music and
  videos stay on the device after the app restarts or you sign back
  in, so they show offline. Before, they could be cleared from the
  image cache like any other image. [#91]

### Content kept per server and account [#68]

The app now keeps everything it stores for the server and account it
came from. Fixes posters from one server showing on another server's
titles after you switch servers, and images that never loaded.

- After you switch to a different server, its own posters and artwork
  show, and images already on screen reload. [#70, #71]
- Switching to a different server removes the previous server's
  downloads, cached images and saved progress from the device. Signing
  in as a different account does the same for the previous account.
  [#69]
- Signing out and back in to the same account keeps your downloads,
  cached images and progress. While signed out, downloads are hidden
  and downloads in progress pause; signing back in brings them back
  and resumes the downloads, and a downloaded song playing when you
  sign out stops. [#72, #73]
- Reading and listening progress not yet sent to the server is kept
  for the server and account that recorded it, and is never sent to
  another one, even if you switch while it is being sent. [#74]
- After you switch to a different server, the terms screen and the
  Downloads tab follow the new server straight away. [#75]
- After the update, existing downloads and progress are kept if they
  belong to the server the app is set up for. The image cache starts
  empty once, so images load again the first time you see them.

### What's New draft

> - Switching servers now works cleanly. Each server's posters,
>   downloads and progress are kept separately: another server's
>   artwork no longer shows on your titles, and switching to a
>   different server or account clears the previous one's content from
>   this device. Signing out and back in keeps everything.
> - Music starts playing without waiting for its cover art, and the
>   playback bar appears on every page as soon as a track starts.
> - The book reader's toolbar fits on larger iPhones. Text size is now
>   under a single Aa button whose menu stays open while you adjust it.
