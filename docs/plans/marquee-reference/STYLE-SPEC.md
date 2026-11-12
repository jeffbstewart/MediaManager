# Media Manager — "watch.plex.tv" look & feel: style and DOM specification

This spec describes how Media Manager can render its home view (and its
carousels) with the visual language of watch.plex.tv while **keeping Media
Manager's persistent left navigation**. Every value below was measured from
captures of watch.plex.tv at 1440 / 768 / 390 px (no Plex code or assets were
used). The working reference implementation is `src/styles.css` + `src/main.ts`
in this folder; class names below match it.

---------------------------------------------------------------------------

## 1. Design tokens

Put these in one `:root` block (in Angular: `styles.scss` or a theme partial)
and use them everywhere. Don't use Angular Material's default palette.

| Token | Value | Use |
|---|---|---|
| `--c-page` | `#1e1e1e` | page background |
| `--c-text` | `#ffffff` | headings, card titles |
| `--c-text-2` | `#ffffffcc` | body copy, descriptions |
| `--c-text-3` | `#ffffff99` | meta lines ("2025 • Movie", "23m left") |
| `--c-accent` | `#e5a00d` | progress fill, active tab, tag chevron, "% read" |
| `--c-surface` | `#ffffff1a` | chips, search pill, artist cards, ghost buttons |
| `--c-scrim` | `#0000004d` | translucent panels (left nav, FAQ rows) |
| `--c-scrim-strong` | `#000000b2` | chips sitting on artwork |
| `--c-footer` | `#00000099` | footer band |
| `--c-live` | `#dc3004` | LIVE badge, nav counters |
| secondary palette | `#1b90dc #f74366 #823165 #45934b #f37b38` | category tiles, avatar fills |
| greys | `#282a2d #3f4245 #575b61 #868c96` | image placeholders |
| `--g-top` | `linear-gradient(90deg,#6d0f4f,#3a1f86 50%,#04289a)` | glow behind header + welcome, fades to `--c-page` by ~660 px |

**Type**

| Role | Font | Size (1440 / 768 / 390) | Weight |
|---|---|---|---|
| Page title (h1) | display | 32 / 29 / 28 px | 700 |
| Row title (h2) | display | 21.008 / 19 / 18 px, line 27 px | 700 |
| Hero title (replaces Plex logo art) | display | 56 / 40 / 34 px | 700 |
| Body / description | Inter | 16 px, line 22 px | 400 |
| Card title | Inter | 14 px, line 18 px | 400 |
| Meta line | Inter | 12 px, line 16 px | 600 |
| Buttons, chips, nav | Inter | 14 px | 600 |

* Body: `Inter, "Inter Fallback", -apple-system, blinkmacsystemfont, "Segoe UI", roboto, …`
* Display: Plex uses `circularBold` (commercial). The closest free stand-in is **Figtree 800**,
  chosen by an objective bake-off: 42 free families scored against 23 heading crops for
  width, shape overlap and outline distance (see `font-bakeoff-report.md`). Runners-up:
  Outfit 800, Gantari 900, Albert Sans 800. Load only the 800 face, so "700" renders as 800.

**Shape and depth**

| Token | Value | Use |
|---|---|---|
| `--r-pill` | `9999px` | buttons, chips, nav items, search |
| `--r-card` | `8px` | posters, wide cards, artist cards, FAQ rows |
| `--r-hero` | `16px` | hero banners, welcome card (mobile), sheets |
| `--r-badge` | `4px` | rating box, tags |
| corner badge | `0 0 0 8px` | LIVE / NEW badge at the top-right of art |
| `--sh-card` | `rgba(0,0,0,.2) 0 4px 4px` | all artwork |
| `--sh-hero` | `rgba(0,0,0,.25) 6px 6px 15px` | hero banners |

**Spacing.** The scale is 4 · 8 · 12 · 16 · 24 · 32 · 64.

| | 1440 | 768 | 390 |
|---|---|---|---|
| main padding (top r b l) | 24 64 64 64 | 16 16 64 16 | 16 16 64 16 |
| gap between sections | 32 | 24 | 24 |
| row title → row | 12 | 12 | 12 |
| gap between cards | 16 | 12 | 8 |
| poster (2:3) | 203 × 304 | 175 × 262 | 114 × 171 |
| wide card (16:9) | 247 × 139 | 220 × 124 | 175 × 99 |
| artist / person card | 350 × 160 | 350 × 160 | 300 × 132 |
| hero banner | row width − 66, 394 tall | stacked | stacked |

---------------------------------------------------------------------------

## 2. Page shell (keeps Media Manager's left nav)

```
body
├─ .page-glow                      absolute, 660px tall, --g-top fading to --c-page
├─ header.app-header               sticky, 64px, transparent; frosted after scroll
│  └─ .header-inner                max-width 1536px, centred, flex, gap 8
│     ├─ button.nav-toggle         (<1024 only) opens the nav sheet
│     ├─ a.brand
│     ├─ a.search-pill             40px pill, --c-surface, icon + "Search"
│     ├─ .header-spacer
│     ├─ a.header-link × n         40px pill links, icon + label
│     └─ button.account-btn        white pill: avatar + username → user sheet
├─ aside.side-nav > nav            ≥1024: fixed, top 64, width 240, --c-scrim + blur
│  └─ a.nav-item × n               40px pill, 20px icon, 14/600
│                                  active: 1px #ffffffb3 outline, icon in --c-accent
├─ main.app-main                   margin-left 240 (≥1024); padding per table above
│  └─ section × n                  flex column, gap 32 / 24
├─ footer.app-footer               --c-footer band
├─ nav.tabbar                      <1024: fixed bottom, 64px, #000, 4 tabs,
│                                  active tab label/icon in --c-accent
├─ dialog.sheet#sheet-user         right-hand sheet: inset 12, radius 16, #000
└─ dialog.sheet.left#sheet-nav     same sheet from the left: the full nav on phone/tablet
```

The Plex active top-nav item (outlined pill) is used as the active **left-nav**
item, so both feel like one system.

---------------------------------------------------------------------------

## 3. Building blocks

### 3.1 Row (carousel)
```html
<section>
  <div class="section-head">
    <span class="tag">›Books</span>                  <!-- optional black tag, amber chevron -->
    <a href="…"><h2>Resume Reading</h2><svg chevron/></a>
  </div>
  <p class="section-sub">optional one-line description</p>
  <div class="row-wrap">
    <ul class="row"> <li>CARD</li> … </ul>         <!-- horizontal scroll, snap, no scrollbar -->
    <button class="row-arrow prev"/> <button class="row-arrow next"/>  <!-- hover only -->
  </div>
</section>
```
* The row bleeds past the right edge (`margin-right:-pad; padding-right:pad`)
  so the next card peeks out. That peek is the cue that the row scrolls.
* The left edge lines up with the section title.

### 3.2 Poster card (movies, shows, books, square = albums)
```html
<a class="poster [square]">
  <div class="art"> <img> [<div class="progress"><i style="width:34%"></i></div>] [badge] </div>
  <div class="caption"><span class="t">Title</span><span class="m [accent]">Meta</span></div>
</a>
```
* Art: radius 8, `--sh-card`, 1px inner hairline `#ffffff14`, image scales to 1.04 on hover.
* Caption is optional (Plex hides it on most poster rows; Media Manager keeps it
  for author, artist and year).
* **Progress**: 4px pill, 8px from the art's edges, track `#ffffff4d`, fill `--c-accent`.
* "% read" / "Releases today"-style highlights use `.m.accent` (amber).
* Missing artwork: a gradient placeholder with a large faint initial and the title.

### 3.3 Wide card (Continue Watching, New Seasons, Live TV)
Same structure as the poster card at 16:9, plus a bottom scrim and the title
over the art (bold display font, 18px). Caption: `Show • S2 • E5` / `23m left`.
* Corner badge: top-right, radius `0 0 0 8px`, 12/700 caps (`LIVE` red, `NEW S3` amber).
* "All Channels" tile: plum → indigo → blue → teal gradient, icon + 20.55px label.

### 3.4 Hero banner (featured carousel + single spotlight)
```html
<article class="hero" style="--tint:#1c4449">
  <div class="hero-img"><img backdrop></div>      <!-- right 62%, cover -->
  <div class="hero-body">
    <h3 class="hero-title">Project Hail Mary</h3>   <!-- Plex shows logo art here -->
    <div class="meta"><span class="rating">PG-13</span><span>2026</span><span>Movie, Cast…</span></div>
    <p class="hero-kicker">Just added to your library</p>   <!-- 12/700 white -->
    <p class="hero-desc">3-line clamp, 16/22, max 600px</p>
    <div class="hero-actions"><a class="btn">▶ Play</a><a class="btn ghost">+ Wish List</a></div>
  </div>
</article>
```
* `--tint` is each backdrop's saturated average colour at about 20% lightness.
  It fills the card, and a gradient fades it over the left edge of the image.
  That is what gives every Plex banner its own colour (Blade maroon, Vice olive).
  Work it out once on the server, when the backdrop is cached.
* Radius 16, `--sh-hero`, 394px tall at desktop; text column inset 80px.
* Below 1024px it stacks: 16:9 image on top, fading into the tint, then the text.
  The button goes full width on phones.
* Carousel: about 66px of the next banner peeks out, with pill dots underneath.

### 3.5 Chips
40px pill, `--c-surface`, 14/600, 16px gap, optional 16px icon. They scroll in a row.

### 3.6 Person / artist card
350×160, `--c-surface`, radius 8, 16 padding: 128px round photo, then the name
(16/400), meta lines (12/600 muted) and a small ghost pill button.

### 3.7 Category tile
The poster width × 116, radius 8, a saturated two-stop gradient with soft
radial highlights, and a centred 16px label.

### 3.8 Welcome block
Desktop: h1 + 16px intro (600px) on the glow, with a tilted poster mosaic
(rotate −30°, skew 10°, masked to fade left and down) in the top-right 600×212.
Below 1024px it becomes a radius-16 card: the mosaic on top, then the text.

### 3.9 FAQ accordion
Centred column of max 700px. Each row is radius 8 on `--c-scrim`, 52px tall,
with a 16/700 display question and a +/− icon. When open, a hairline divider
appears above a 16px answer. The button carries `aria-expanded` / `aria-controls`.

### 3.10 Sheets (user menu, mobile nav)
Native `<dialog>` + `showModal()`: inset 12px, radius 16, `#000` with a
`#ffffff1a` border and a `rgba(0,0,0,.6)` backdrop. It slides in from its edge.

---------------------------------------------------------------------------

## 4. Home view composition used here

1. Welcome block (glow + mosaic)
2. Featured hero carousel: recently added titles that have a backdrop
3. Browse Your Library chips
4. Continue Watching: wide cards + progress (+ All Channels tile)
5. Recently Added: posters
6. Spotlight hero: a show with a new season you don't own
7. Resume Reading: book posters + progress + "% read"
8. Recently Added Books: posters + author
9. Continue Listening: square album art + progress + track and artist
10. Artists in Your Library: person cards
11. Recently Added Albums: square art + artist
12. New Seasons Available: wide cards + amber "NEW S3" badge
13. On Live TV: channel tiles + LIVE badge
14. FAQ

Left out on purpose (no Media Manager equivalent): rent/price tags, streaming-service
logos, "Sponsored" units, review/discussion cards with reactions.

## 5. Mapping to the mediamanager API

| Row | Source |
|---|---|
| hero, recently added | `GET /api/v2/catalog/home` → `recently_added`; details from `/api/v2/catalog/titles/{id}` (description, content_rating, cast); `/backdrops/{id}` |
| continue watching | `continue_watching` (`progress_fraction`, `time_remaining`, `episode_label`) |
| resume reading | `resume_reading` (`percent`) |
| continue listening | `resume_listening` (`track_name`, `artist_name`) |
| books / albums | `recently_added_books` (`author_name`), `recently_added_albums` (`artist_name`, `track_count`) |
| artists | album details → `artists[]`, `/artist-headshots/{id}` |
| new seasons | `missing_seasons` |
| live TV | `/api/v2/catalog/live-tv/channels` |

**Note:** the test viewer account has no watch, read or listen history, so rows
4, 7 and 9 use *sample* progress on real library titles (`SAMPLE_*` in `main.ts`).
