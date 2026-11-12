# Live chain

Chain file for `docs/plans/chain-agent-brief.md`. This chain builds the
Live TV and Discover half of Phase 3 of `docs/plans/web-theming.md` on the
shared media components from `web-app/src/app/shared/media/`.

| Setting | Value |
|---|---|
| Chain name | Live |
| Branch prefix | `agent/live-` |
| Progress file | `docs/plans/live-progress.md` |
| Plan sections | Phase 3, "Live TV" and "Discover" |
| Spec sections | `STYLE-SPEC.md` 3.3 wide card, 3.4 hero banner, 3.5 chips, 3.6 person card; `styles.css` `.live-top`, `.live-player`, `.live-info`, `.guide*`, `.promo`; `main.ts` `liveTvPage()` and `trendingPage()` |

A sibling chain (`docs/plans/chain-grids.md`, prefix `agent/grids-`) runs at
the same time on the grids and search. Its branches and PRs are not yours.

## Rows

One PR per row, each branch from the previous row's branch (`main` for row
01). Layout only: the HLS player component (`live-tv-player`) and every RPC
stay as they are. The data each row needs already exists: channels from
`LiveService/ListTvChannels`, missing seasons and recently added books from
`CatalogService/HomeFeed` (`missing_seasons`, `spotlight`,
`recently_added_books`), popular titles from `ListTitles` sorted by
popularity, and the current artist recommendations. If a row seems to
need a server change, record it in Notes for later and do the closest
layout-only thing.

| Row | Branch | Step | Specs that must change with it |
|---|---|---|---|
| 01 | `agent/live-01-live-top` | Live TV: the page opens with the spec `live-top` layout, a 16:9 player box up to 604px wide (the existing player component inside it, or the channel tile before a channel is chosen) and an info column: channel name as `h1`, affiliation as `h2`, a meta line, Watch Live and Favorite pills (Favorite only if the RPC exists, else omit). Below 1024px the two stack. Same routes, same player. | functional 20; axe 12 |
| 02 | `agent/live-02-guide` | Live TV: the channel list becomes the spec guide: channel-group chips (`guide-tabs`, `aria-pressed`), then one `guide-row` per channel with the `guide-ch` tile and a `guide-prog` bar carrying the channel name and "Live now"; the LIVE badge from 3.3; choosing a row selects the channel as the grid did. | functional 20; axe 12 |
| 03 | `agent/live-03-discover-frame` | Discover: the prototype `trendingPage` frame. A full-width `hero-banner` from the home feed `spotlight` (or the first `missing_seasons` show with a backdrop) with the kicker naming the missing seasons; the `.promo` box with the "Find Something New To Watch" copy and an Open My Wish List pill; then a "New Seasons Out" row of `wide-card`s from `missing_seasons`. The existing artist recommendations stay below, unchanged for now. | functional 18; axe 11 |
| 04 | `agent/live-04-discover-rows` | Discover rows: "More From Authors You Read" as `poster-card`s from `recently_added_books`; the artist recommendations as `person-card`s in a `media-row` with their wish control as the card's ghost pill; "Popular In Your Library" as `poster-card`s from `ListTitles` sorted by popularity (18). Empty rows are omitted. | functional 18; axe 11 |

## Progress file template (`docs/plans/live-progress.md`)

```markdown
# Live chain progress

Working file for the cloud chain. Deleted in the mop-up.

| Row | Branch | Status | PR | Notes |
|---|---|---|---|---|
| 01 | agent/live-01-live-top | todo | | |
| 02 | agent/live-02-guide | todo | | |
| 03 | agent/live-03-discover-frame | todo | | |
| 04 | agent/live-04-discover-rows | todo | | |

Status values: todo, done (set by the run when it opens the PR; the merge makes it true), blocked. The operator sets a row back to `todo` to have it redone.

## Notes for later
- (adjacent work noticed but deliberately not done)
```
