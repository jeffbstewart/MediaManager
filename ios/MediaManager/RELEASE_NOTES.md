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

### What's New draft

> Fixed the book reader's toolbar running off the edge of the screen
> on larger iPhones. Text size is now under a single Aa button, and
> its menu stays open so you can step the size up or down.
