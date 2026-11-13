# TestFlight Test Notes — iOS 1.3 (14)

---

NEW LOOK

The whole app has been restyled to match the web app: a dark charcoal page, an amber accent, pill buttons, bold display headings, and poster-first layouts. Nothing has moved and no feature was added or removed, so everything is where you left it. The app is now dark only.

WHAT CHANGED

- Home: a featured carousel at the top (each slide tinted from its own artwork), browse chips, Continue Watching as wide cards with progress, then poster rows, Continue Reading, and New Seasons.
- Sidebar: wordmark at the top, pill-shaped rows with the current section outlined, red counters, and the connection status at the bottom.
- Title pages: the backdrop runs under the navigation bar and fades into the page; Play, favorite, and download actions are pills; tags are chips; cast photos are round.
- Movies, TV, Books, and Music grids fill the width of the screen on iPhone and iPad.
- Live TV and Cameras are wide cards with a red Live badge.
- Sign-in, server setup, terms, Profile, Downloads, Search, Wish List, Collections, Tags, Family, and the admin screens all share the new colours and controls.

WHAT TO TEST

- Walk through the screens you use most and look for anything hard to read: low-contrast text, clipped or overlapping titles, buttons that run off the edge.
- Try both orientations on iPad, and the largest and smallest text sizes (Settings > Accessibility > Display & Text Size > Larger Text).
- Home: swipe the featured carousel; check that Continue Watching progress matches where you stopped.
- Title pages: scroll up and down and watch the navigation bar change from clear to frosted; check long titles and titles with no backdrop.
- Grids: check that posters fill the row with no ragged gap on the right, including after rotating.
- Offline Mode: turn it on from the sidebar and confirm the remaining sections still look right and downloads still play.
- Playback, the book reader, music, and CarPlay were not redesigned. They should behave exactly as before; please report anything that does not.

KNOWN AND INTENDED

- The video player and the book reader keep their previous appearance.
- iPhone still navigates from the sidebar list; there is no bottom tab bar.
- Episode lists are still text rows without thumbnails.
