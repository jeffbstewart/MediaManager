# Grids chain

Chain file for `docs/plans/chain-agent-brief.md`. This chain builds the
grid and search half of Phase 3 of `docs/plans/web-theming.md`: the
Movies, TV, Family, Books, and Music grids, the filter pills, the search
page, the wish list, and the collections and tags pages, all on the shared
media components from `web-app/src/app/shared/media/`.

| Setting | Value |
|---|---|
| Chain name | Grids |
| Branch prefix | `agent/grids-` |
| Progress file | `docs/plans/grids-progress.md` |
| Plan sections | Phase 3, "Movies / TV / Books / Music grids" and "Search page"; decision 5 for the components |
| Spec sections | `STYLE-SPEC.md` 3.2 poster card, 3.5 chips, 3.6 person card, 3.7 category tile; `styles.css` `.grid`, `.chip`, `.search-box`, `.cat-tile` |

A sibling chain (`docs/plans/chain-live.md`, prefix `agent/live-`) runs at
the same time on Live TV and Discover. Its branches and PRs are not yours.

## Rows

One PR per row, each branch from the previous row's branch (`main` for row
01). Layout only: no RPC, proto, or Kotlin changes anywhere in this chain.
If a row seems to need a server change, record it in Notes for later and
do the closest layout-only thing.

| Row | Branch | Step | Specs that must change with it |
|---|---|---|---|
| 01 | `agent/grids-01-grid` | `shared/title-grid` renders the spec `.grid`: `repeat(auto-fill, minmax(var(--grid-min), 1fr))`, 32px gap, `--grid-min` 192px at desktop, 160px at 1023px and below, 104px at 599px and below (add the token to `styles/_layout.scss`), `poster-card` at 100% width with a 2:3 art box. Movies, TV, and Family use it unchanged otherwise; infinite scroll keeps working. | functional 21, 26, 19, 57; axe 09 |
| 02 | `agent/grids-02-filter-pills` | The title grid's filter and sort controls become spec chips (3.5): 40px pills in a scrolling `chip-row`, `aria-pressed` on the active one, the same labels and the same query parameters. No filter logic changes. | functional 21, 26, 57; axe 09 |
| 03 | `agent/grids-03-people-grids` | Books and Music landing pages: the author and artist exploration grids use `person-card` (3.6) in the `.grid`, their search box becomes the spec `.search-box`, their sort controls become chips. Drill-in links unchanged. | functional 15, 22; axe 09 |
| 04 | `agent/grids-04-search` | Search page: centered `.search-box` at the top, results as `poster-card` in the `.grid` with the type label as card meta (the row 09 note: search cards move onto `poster-card`), the empty and error states as spec `.empty` text, the advanced-search button kept. The header search box is not touched. | functional 55; axe 11 |
| 05 | `agent/grids-05-wishlist` | Wish list: cards onto `poster-card` with the lifecycle state as meta and the cancel and dismiss controls as card actions (the row 09 note). Same RPCs, same behaviour. | functional 24; axe 11 |
| 06 | `agent/grids-06-collections-tags` | Collections and tags: the collections page and tag list as `category-tile` (3.7) or `poster-card` in the `.grid`; collection detail and tag detail on `poster-card`, the media-type pill as card meta and the admin remove control as a card action (row 09 note); the remaining literal card colours in these views move to tokens (row 11 note). | functional 17, 25; axe 09, 10 |

## Progress file template (`docs/plans/grids-progress.md`)

```markdown
# Grids chain progress

Working file for the cloud chain. Deleted in the mop-up.

| Row | Branch | Status | PR | Notes |
|---|---|---|---|---|
| 01 | agent/grids-01-grid | todo | | |
| 02 | agent/grids-02-filter-pills | todo | | |
| 03 | agent/grids-03-people-grids | todo | | |
| 04 | agent/grids-04-search | todo | | |
| 05 | agent/grids-05-wishlist | todo | | |
| 06 | agent/grids-06-collections-tags | todo | | |

Status values: todo, done (set by the run when it opens the PR; the merge makes it true), blocked. The operator sets a row back to `todo` to have it redone.

## Notes for later
- (adjacent work noticed but deliberately not done)
```
