# TestFlight Test Notes — iOS 1.2 (12)

Paste the section below verbatim into App Store Connect's "What to Test" field for this build. Keep it under the 4000-character limit.

---

**Heads up: this build will ask for your server address on first launch. That's on purpose.**

The server moved to a new address, and every app that had the old one stored was stuck: it couldn't connect, couldn't sign out, and even deleting and reinstalling didn't help — the address lives in the iOS Keychain, which survives app deletion. This build fixes that, and the fix starts by throwing away the old address.

**What that means for you**

1. Launch the app. You'll land on the "Enter your server address" screen, exactly like a fresh install.
2. Enter the new address your admin gave you.
3. You should go **straight back into your library without typing a password.** Your sign-in survives an address change — only the address is forgotten. If it makes you sign in again, that's a bug worth reporting.

**What to test**

- **The first launch above.** This is the main event. Confirm you land at server setup and that entering the new address puts you back in without re-entering credentials.
- **Turn off Wi-Fi and cellular, then use the app.** You should get a "Server Unreachable" prompt with four choices: Retry, Go Offline (if you have downloads), Change Server, and Sign Out. Every one of them should work with no network at all — nothing should hang or spin forever.
- **Sign Out with the network off.** Previously this hung, because the app waited to tell the server about it. It should now be instant.
- **Profile with the network off.** Open Profile while unreachable. It used to render an empty screen — no Sign Out button anywhere, right when you'd want one. It should now show "Can't reach the server", a Retry button, and Change Server / Sign Out at the bottom.
- **Change Server vs Sign Out.** Change Server should forget only the address and keep you signed in. Sign Out should do the usual full sign-out.
- **Delete the app and reinstall from TestFlight.** It should come up completely blank — no remembered server, no remembered account. That's new; it used to remember both.
- **Everything else.** Browsing, search, playback, downloads, CarPlay, and offline mode should be unchanged. The plumbing underneath moved a fair bit, so shout if anything that worked in build 11 doesn't now.

**Also in this build**

- The app now follows the server automatically if its address changes again, as long as the old address still answers once. Every launch re-checks and adopts the new one, so this should be the last time an address change strands you.
- Images, video, and audio streaming now use the same address as everything else. They used to track a second address separately, which could silently rot while the rest of the app worked.
- Connection failures now say something useful. The old text was "GRPCCore.RPCError error 1".
- Removed a leftover Siri media-search declaration that wasn't wired to anything.

**Known limits**

- Cross-device resume is still server-only: offline-only progress doesn't reach your other devices until you're back online.
- If you point the app at a genuinely different server (not just a new address for the same one), you'll get a "Server Identity Changed" warning. That's the intended safety check, not a bug — choose Disconnect and set it up fresh.
