<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Competitive Analysis

*March 2026*

---

## Executive Summary

MediaManager occupies a unique position in the home media management space: it is the only product that integrates **physical disc cataloging** (barcode scanning, purchase tracking, valuation) with **digital playback** (NAS-based transcoding, in-browser and Roku streaming) in a single self-hosted application. The competitive landscape splits into two categories that MediaManager bridges: **media servers** (Plex, Jellyfin, Emby) that stream digital files but ignore physical ownership, and **collection catalogs** (CLZ Movies, DVD Profiler) that track what you own but don't play anything. No competitor does both.

This is both the product's greatest strength and its strategic challenge: it competes on two fronts simultaneously, and is currently behind dedicated solutions on each front individually.

---

## Competitive Landscape

### Category 1: Media Servers

| Feature | **Plex** | **Jellyfin** | **Emby** | **MediaManager** |
|---------|----------|-------------|----------|-----------------|
| **License** | Freemium (Plex Pass: $5/mo or $120 lifetime) | Free, open-source | Freemium (Emby Premiere) | Free, self-hosted |
| **Client apps** | 20+ platforms (smart TVs, mobile, web, Roku, Fire TV, Apple TV, gaming consoles) | 10+ platforms (web, mobile, Roku, Android TV, Apple TV — community-maintained) | 15+ platforms (similar to Plex) | Web browser + custom Roku channel — [#1](https://github.com/jeffbstewart/MediaManager/issues/1) iOS app, [#5](https://github.com/jeffbstewart/MediaManager/issues/5) mobile offline |
| **Transcoding** | Excellent; hardware accel behind paywall | Excellent; hardware accel free | Excellent; hardware accel behind paywall | FFmpeg-based; CPU and NVENC GPU; background pre-transcoding |
| **Library scanning** | Automatic file detection + rich metadata | Automatic file detection + metadata | Automatic file detection + metadata | Automatic NAS scanning + TMDB enrichment |
| **User management** | Multi-user with managed/shared accounts | Multi-user with parental controls | Multi-user with parental controls | Multi-user with access levels and content rating ceilings — [#30](https://github.com/jeffbstewart/MediaManager/issues/30) Roku multi-user |
| **Remote access** | Cloud relay (zero-config) | Manual (reverse proxy) | Built-in option | Manual (reverse proxy) |
| **Live TV/DVR** | Yes (premium) | Yes (via plugins) | Yes (premium) | No — [#27](https://github.com/jeffbstewart/MediaManager/issues/27) security cameras, [#41](https://github.com/jeffbstewart/MediaManager/issues/41) TV tuner |
| **Mobile apps** | Yes (iOS, Android) | Yes (community) | Yes | No — [#1](https://github.com/jeffbstewart/MediaManager/issues/1) iOS app |
| **Smart TV apps** | Yes (all major platforms) | Yes (expanding) | Yes | Roku only (custom sideloaded) |
| **Seek thumbnails** | Yes (premium) | Yes (trickplay, free) | Yes (premium) | No — [#10](https://github.com/jeffbstewart/MediaManager/issues/10) [#39](https://github.com/jeffbstewart/MediaManager/issues/39) BIF trick play |
| **Subtitle support** | Excellent (multiple formats, download) | Excellent (OpenSubtitles plugin) | Excellent | SRT generation from transcodes; auto-enabled — [#40](https://github.com/jeffbstewart/MediaManager/issues/40) subtitle toggle |
| **Watch history sync** | Cross-device | Cross-device | Cross-device | Cross-device (browser ↔ Roku via server) |
| **Continue Watching** | Yes | Yes | Yes | Web only; not on Roku — [#29](https://github.com/jeffbstewart/MediaManager/issues/29) personalized home rows |

### Category 2: Collection Catalogs

| Feature | **CLZ Movies** | **DVD Profiler** | **My Movies** | **MediaManager** |
|---------|---------------|-----------------|--------------|-----------------|
| **License** | $20-40/year subscription | Discontinued | Free/premium | Free, self-hosted |
| **Barcode scanning** | Yes (camera, 98% hit rate) | Yes | Yes | Yes (UPC lookup via UPCitemdb) — [#42](https://github.com/jeffbstewart/MediaManager/issues/42) phone camera scanning |
| **Metadata source** | IMDb | Multiple | Multiple | TMDB (posters, cast, genres, descriptions, popularity) |
| **Physical format tracking** | DVD, Blu-ray, 4K UHD, HD-DVD, LaserDisc, VHS | DVD, Blu-ray | DVD, Blu-ray | DVD, Blu-ray, UHD (auto-detected via FFprobe resolution) |
| **Purchase price tracking** | No | No | No | **Yes** (valuation, Amazon import, insurance reporting) |
| **Multi-pack detection** | No | No | No | **Yes** (double features, trilogies, box sets auto-detected) |
| **Wish list** | Basic | No | No | **Yes** (TMDB search, admin review, status tracking, vote aggregation) — [#43](https://github.com/jeffbstewart/MediaManager/issues/43) shareable wish lists |
| **Playback integration** | None | None | None | **Yes** (in-browser + Roku streaming) |
| **Collection value/insurance** | eBay price lookup | No | No | **Yes** (purchase price tracking, inventory report) — [#47](https://github.com/jeffbstewart/MediaManager/issues/47) replacement value |
| **Platform** | iOS, Android, Web, Windows | Windows only | Windows | Web (any device with a browser) |

### Category 3: Request Management (Adjacent)

| Tool | Purpose | Relationship to MediaManager |
|------|---------|------------------------------|
| **Overseerr / Jellyseerr** | Users request movies/TV; integrates with Sonarr/Radarr to auto-download | MediaManager's wish list serves the same "users request, admin fulfills" workflow, but for *physical media purchases* rather than automated downloads |
| **Ombi** | Similar request management for Plex/Emby/Jellyfin | Same comparison as above |

---

## MediaManager Strengths

### 1. Unique Market Position: Physical-to-Digital Bridge
No other product connects physical disc ownership (barcode scan → catalog) to digital playback (NAS scan → transcode → stream). Plex users who also own physical media must use a separate tool (CLZ, spreadsheet) to track what they own. MediaManager unifies this.

### 2. Purchase & Valuation Tracking
No media server tracks what you paid. MediaManager's Amazon order import, per-title purchase prices, and inventory/insurance reporting are unique. For collectors with hundreds or thousands of discs, this has real financial value. See [#45](https://github.com/jeffbstewart/MediaManager/issues/45) (executive summary), [#46](https://github.com/jeffbstewart/MediaManager/issues/46) (gap analysis), and [#47](https://github.com/jeffbstewart/MediaManager/issues/47) (replacement value) for planned enhancements.

### 3. Integrated Wish List with Admin Workflow
The wish list system — where household members request titles, votes aggregate, and the admin tracks acquisition status (Ordered → Owned → Ready to watch) — is a novel feature. Overseerr/Ombi serve a similar role for digital piracy workflows, but MediaManager's version is designed for legitimate physical media purchasing. See [#43](https://github.com/jeffbstewart/MediaManager/issues/43) for shareable wish lists (gift giving).

### 4. Multi-Pack Intelligence
Automatic detection and expansion of double features, trilogies, and box sets during barcode scanning. No competitor does this.

### 5. Zero External Dependencies
No cloud account required. No telemetry. No subscription. No third-party service that can change terms, shut down, or degrade. The entire stack (app server, database, media files) lives on hardware you own.

### 6. Format-Aware Transcoding Pipeline
Automatic FFprobe-based format detection (resolution → DVD/Blu-ray/UHD), codec-aware transcoding (copy H.264, re-encode HEVC/MPEG-2), interlace detection, anamorphic SAR correction, and Roku-compatible output — all handled automatically with no user configuration.

### 7. Tight Roku Integration
Custom-built Roku channel with QR-code device pairing, server-side progress sync, and subtitle support. While the feature set is still maturing vs. Jellyfin's Roku app, the tight integration with the catalog and wish list systems is a differentiator. See issues [#29](https://github.com/jeffbstewart/MediaManager/issues/29)–[#40](https://github.com/jeffbstewart/MediaManager/issues/40) for the Roku enhancement roadmap.

---

## MediaManager Weaknesses

### 1. Client App Coverage (Critical Gap)
Only web browser and a sideloaded Roku channel. No iOS app, no Android app, no Apple TV, no Fire TV, no Android TV, no smart TV apps. Plex and Jellyfin support 10-20+ platforms. This is the single largest competitive disadvantage — most households expect to watch on their phone or tablet. See [#1](https://github.com/jeffbstewart/MediaManager/issues/1) (iOS app) and [#5](https://github.com/jeffbstewart/MediaManager/issues/5) (mobile offline playback).

### 2. No Mobile App for Scanning
Barcode scanning requires typing a UPC number into a web form. CLZ Movies lets you point your phone camera at a barcode. A mobile scanning app (or even a PWA with camera access) would dramatically improve the cataloging workflow. See [#42](https://github.com/jeffbstewart/MediaManager/issues/42).

### 3. No Remote Access Out of the Box
Plex's cloud relay provides zero-config remote streaming. MediaManager requires manual reverse proxy setup (nginx, Caddy, etc.) for access outside the LAN. This is a barrier for less technical users and for watching media away from home.

### 4. No Search on Roku
The Roku channel has no search or filtering — all content is displayed in two flat rows. Jellyfin's Roku app now supports search, genre browsing, and more sophisticated navigation. For libraries with hundreds of titles, this makes content discovery painful. See [#31](https://github.com/jeffbstewart/MediaManager/issues/31) (search with voice) and [#32](https://github.com/jeffbstewart/MediaManager/issues/32) (genre/tag filtering).

### 5. Single-Household Scale
The architecture assumes a single household with one admin managing a physical collection. There's no concept of shared servers, friend access, or federated libraries — features that Plex and Jellyfin users rely on.

### 6. No Live TV / DVR
Plex, Jellyfin, and Emby all support live TV tuners and DVR recording. MediaManager has no support for live content. See [#27](https://github.com/jeffbstewart/MediaManager/issues/27) (security cameras), [#28](https://github.com/jeffbstewart/MediaManager/issues/28) (personal videos), and [#41](https://github.com/jeffbstewart/MediaManager/issues/41) (TV tuner).

### 7. Manual Transcoding Pipeline
Plex and Jellyfin transcode on-the-fly at playback time, adapting quality to the client's bandwidth and capabilities. MediaManager pre-transcodes everything to a single MP4 format in the background. This means:
- New content isn't playable until the transcode queue reaches it
- Storage is doubled (source + ForBrowser copy)
- No adaptive bitrate streaming

### 8. Limited Ecosystem Integration
No Trakt.tv sync, no Sonarr/Radarr integration, no OpenSubtitles plugin, no DLNA server. The self-hosted media community expects these integrations.

---

## Key Differentiating Features to Improve Positioning

### High Impact, Moderate Effort

| Feature | Why It Matters | Competitive Effect | Tracked In |
|---------|---------------|-------------------|------------|
| **Mobile barcode scanning** (PWA or native app with camera) | Transforms the cataloging workflow from "type UPC" to "point and scan" — matches CLZ's core experience | Closes the gap with CLZ while maintaining the playback advantage CLZ lacks | [#42](https://github.com/jeffbstewart/MediaManager/issues/42) |
| **Roku home screen personalization** (Continue Watching, Recently Added) | Basic expectation from anyone who has used Netflix/Plex/Jellyfin | Removes the most obvious "this feels amateur" signal on the Roku | [#29](https://github.com/jeffbstewart/MediaManager/issues/29) |
| **Roku search with voice** | Libraries over ~50 titles are unusable without search | Matches Jellyfin Roku's search capability | [#31](https://github.com/jeffbstewart/MediaManager/issues/31) |
| **Auto-play next episode** | Table-stakes for TV binge watching | Removes a constant friction point vs. every competitor | [#33](https://github.com/jeffbstewart/MediaManager/issues/33) |

### High Impact, High Effort

| Feature | Why It Matters | Competitive Effect | Tracked In |
|---------|---------------|-------------------|------------|
| **Mobile streaming app** (iOS/Android, even a PWA) | Most media consumption happens on phones/tablets; web browser playback on mobile is subpar | Addresses the #1 competitive weakness | [#1](https://github.com/jeffbstewart/MediaManager/issues/1) |
| **On-the-fly transcoding** (or adaptive bitrate) | Eliminates the "wait for transcode queue" delay and reduces storage | Matches Plex/Jellyfin's core streaming experience | — |
| **Additional TV platform apps** (Fire TV, Apple TV, Android TV) | Expands device coverage beyond Roku | Addresses client app coverage gap | — |

### Medium Impact, Low Effort (Quick Wins)

| Feature | Why It Matters | Competitive Effect | Tracked In |
|---------|---------------|-------------------|------------|
| **Roku title detail enhancements** (backdrop, rating badge, tags) | Data already in the feed; purely UI work | Makes the Roku experience feel polished | [#34](https://github.com/jeffbstewart/MediaManager/issues/34) |
| **Roku subtitle toggle** | User control over an existing feature | Matches Plex/Jellyfin UX expectations | [#40](https://github.com/jeffbstewart/MediaManager/issues/40) |
| **Roku multi-user picker** | Per-user watch history and content ratings | Matches Plex/Jellyfin household model | [#30](https://github.com/jeffbstewart/MediaManager/issues/30) |

### Strategic Differentiators (Unique to MediaManager)

These features lean into what competitors *don't* do, rather than chasing parity:

| Feature | Why It Matters | Tracked In |
|---------|---------------|------------|
| **Collection insurance report** (expand current valuation) | No media server generates a "here's what my collection is worth" document. Home insurance, estate planning, and collector communities would value this. | [#44](https://github.com/jeffbstewart/MediaManager/issues/44) (proof of ownership), [#45](https://github.com/jeffbstewart/MediaManager/issues/45) (executive summary), [#46](https://github.com/jeffbstewart/MediaManager/issues/46) (gap analysis), [#47](https://github.com/jeffbstewart/MediaManager/issues/47) (replacement value) |
| **Physical-to-digital gap analysis** | "You own 400 Blu-rays but only 250 are ripped" — surface the backlog with priority recommendations. Already partially implemented via the Transcode Backlog page. | — |
| **Family wish list with seasonal events** | The wish list + admin fulfillment flow maps perfectly to birthdays and holidays. Lean into this — "What does Dad want for his birthday? Check the wish list." No media server does this. | [#43](https://github.com/jeffbstewart/MediaManager/issues/43) |
| **Disc lending tracker** | "Who has my copy of Inception?" — physical media lending is a real problem for collectors. No competitor addresses it. | — |
| **Live content** (cameras + TV tuner) | Consolidates home AV into one app — security cameras and live TV alongside the media library. No single competitor covers all three. | [#27](https://github.com/jeffbstewart/MediaManager/issues/27), [#41](https://github.com/jeffbstewart/MediaManager/issues/41) |

---

## Positioning Recommendation

MediaManager should **not** try to be a Plex/Jellyfin replacement. It cannot win on client app breadth, ecosystem integrations, or transcoding sophistication — those projects have years of development and large communities.

Instead, MediaManager should position as: **"The complete physical media management system — from purchase to playback."**

The target user is a physical media collector (DVD/Blu-ray/4K UHD) who:
- Wants to catalog what they own (barcodes, metadata, purchase history)
- Wants to play their collection without swapping discs (NAS + transcoding)
- Wants household members to browse and request new titles (wish lists)
- Values ownership and self-hosting over streaming subscriptions

The competitive moat is the **integration between physical ownership tracking and digital playback** — a workflow that requires duct-taping 2-3 products together with any other solution (CLZ + Plex + Overseerr, for example).

### Priority Roadmap Suggestion

1. **Mobile barcode scanning** ([#42](https://github.com/jeffbstewart/MediaManager/issues/42)) — Dramatically improves the "from purchase" side of the value prop
2. **Roku personalization + search + auto-play** ([#29](https://github.com/jeffbstewart/MediaManager/issues/29), [#31](https://github.com/jeffbstewart/MediaManager/issues/31), [#33](https://github.com/jeffbstewart/MediaManager/issues/33)) — Makes the "to playback" side competitive
3. **Collection reporting** ([#44](https://github.com/jeffbstewart/MediaManager/issues/44)–[#47](https://github.com/jeffbstewart/MediaManager/issues/47)) — Deepens the unique moat
4. **Mobile playback app** ([#1](https://github.com/jeffbstewart/MediaManager/issues/1)) — Expands reach without competing head-to-head on server architecture

---

## Sources

- [Best Home Media Server 2026: Jellyfin vs Plex vs Emby](https://selfhosthero.com/jellyfin-vs-plex-vs-emby-home-media-server-comparison/)
- [Plex vs Jellyfin vs Emby on a NAS](https://nascompares.com/guide/plex-vs-jellyfin-vs-emby-on-a-nas-which-is-best-for-your-synology-qnap-truenas-or-other-nas/)
- [Jellyfin Roku App Updates](https://www.xda-developers.com/jellyfins-roku-app-just-got-good-enough-to-ditch-plex/)
- [Jellyfin Roku v3.1.6 Release](https://www.howtogeek.com/jellyfin-the-open-source-media-server-just-got-better-on-roku-tvs/)
- [CLZ Movies](https://clz.com/movies)
- [Overseerr / Jellyseerr](https://seerr.dev/)
- [Ombi](https://github.com/Ombi-app/Ombi)
- [Plex Universal Watchlist](https://support.plex.tv/articles/universal-watchlist/)
- [Jellyfin Watchlist Feature Request](https://features.jellyfin.org/posts/576/watchlist-like-netflix)
- [Kodi vs Plex vs Jellyfin vs Emby Showdown](https://diymediaserver.com/post/kodi-vs-plex-vs-jellyfin-vs-emby-the-ultimate-media-playback-software-showdown/)

---

## Maintaining This Document

To update this competitive analysis in a future Claude Code session, use the following prompt:

> Read `docs/COMPETITIVE_ANALYSIS.md` and `CLAUDE.md`. Search the web for the current state of Plex, Jellyfin, Emby, CLZ Movies, Overseerr/Jellyseerr, and any new entrants in the home media server and physical media cataloging spaces. Fetch the GitHub issues list with `gh issue list --limit 100 --state all`. Then update `docs/COMPETITIVE_ANALYSIS.md`:
>
> 1. Update the date at the top.
> 2. Refresh the comparison tables — check for new features, pricing changes, discontinued products, or new competitors.
> 3. Move any MediaManager features that have been implemented from "Weaknesses" or "Key Differentiating Features" into "Strengths."
> 4. Update all issue annotations — replace references to closed issues with "Done" or remove them, and add references to any new relevant issues.
> 5. Re-evaluate the Priority Roadmap based on what has shipped and what the current gap analysis suggests.
> 6. Add any new sources used to the Sources section.
> 7. Keep the same structure, tone, and formatting conventions.

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="FEATURES.md">Feature Tracker</a> &bull;
  <a href="https://github.com/jeffbstewart/MediaManager/issues">GitHub Issues</a>
</p>
