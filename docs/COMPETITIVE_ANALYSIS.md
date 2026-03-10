<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Competitive Analysis

*Updated March 2026*

---

## Executive Summary

MediaManager occupies a unique position in the home media management space: it is the only product that integrates **physical disc cataloging** (barcode scanning, purchase tracking, valuation, insurance reporting) with **digital playback** (NAS-based transcoding, in-browser and Roku streaming) in a single self-hosted application. The competitive landscape splits into two categories that MediaManager bridges: **media servers** (Plex, Jellyfin, Emby) that stream digital files but ignore physical ownership, and **collection catalogs** (CLZ Movies, DVD Profiler) that track what you own but don't play anything. No competitor does both.

This is both the product's greatest strength and its strategic challenge: it competes on two fronts simultaneously, and is currently behind dedicated solutions on each front individually. However, recent development has significantly closed the gap on the cataloging side with mobile barcode scanning, insurance reporting, and TMDB collection tracking now shipped.

**Notable competitive shifts since the last analysis:**
- **Plex** raised prices dramatically (lifetime pass from $120 to $250) and now paywalls remote streaming on TV apps, creating user backlash and driving interest in self-hosted alternatives.
- **Jellyfin** released 10.11 with major trickplay performance improvements (100x faster), Dolby Vision tone-mapping, and continued Roku app enhancements.
- **Overseerr and Jellyseerr merged into "Seerr"**, a unified request management platform supporting Plex, Jellyfin, and Emby.
- **DVD Profiler** appears fully defunct (website unresponsive as of January 2026), shrinking the catalog competitor field.
- **MediaManager** shipped mobile barcode scanning ([#42](https://github.com/jeffbstewart/MediaManager/issues/42) Done), full insurance reporting suite ([#44](https://github.com/jeffbstewart/MediaManager/issues/44)--[#46](https://github.com/jeffbstewart/MediaManager/issues/46) Done), TMDB collection tracking ([#49](https://github.com/jeffbstewart/MediaManager/issues/49)--[#50](https://github.com/jeffbstewart/MediaManager/issues/50) Done), catalog sub-navigation ([#48](https://github.com/jeffbstewart/MediaManager/issues/48) Done), personal/home video support ([#28](https://github.com/jeffbstewart/MediaManager/issues/28) Done), and structured season lifecycle tracking ([#25](https://github.com/jeffbstewart/MediaManager/issues/25) Done).

---

## Competitive Landscape

### Category 1: Media Servers

| Feature | **Plex** | **Jellyfin** | **Emby** | **MediaManager** |
|---------|----------|-------------|----------|-----------------|
| **License** | Freemium (Plex Pass: $7/mo, $70/yr, or $250 lifetime) | Free, open-source | Freemium (Emby Premiere: $5/mo, $54/yr, or $119 lifetime) | Free, self-hosted |
| **Client apps** | 20+ platforms (smart TVs, mobile, web, Roku, Fire TV, Apple TV, gaming consoles) | 10+ platforms (web, mobile, Roku, Android TV, Apple TV -- community-maintained) | 15+ platforms (similar to Plex) | Web browser + custom Roku channel -- [#1](https://github.com/jeffbstewart/MediaManager/issues/1) iOS app, [#5](https://github.com/jeffbstewart/MediaManager/issues/5) mobile offline |
| **Transcoding** | Excellent; hardware accel behind paywall | Excellent; hardware accel free; Dolby Vision tone-mapping | Excellent; hardware accel behind paywall | FFmpeg-based; CPU and NVENC GPU; background pre-transcoding |
| **Library scanning** | Automatic file detection + rich metadata | Automatic file detection + metadata | Automatic file detection + metadata | Automatic NAS scanning + TMDB enrichment + TMDB collection tracking |
| **User management** | Multi-user with managed/shared accounts | Multi-user with parental controls | Multi-user with parental controls | Multi-user with access levels and content rating ceilings -- [#30](https://github.com/jeffbstewart/MediaManager/issues/30) Roku multi-user |
| **Remote access** | Cloud relay (now requires Plex Pass or Remote Watch Pass for TV apps) | Manual (reverse proxy) | Built-in option | Manual (reverse proxy) |
| **Live TV/DVR** | Yes (premium) | Yes (via plugins) | Yes (premium) | No -- [#27](https://github.com/jeffbstewart/MediaManager/issues/27) security cameras, [#41](https://github.com/jeffbstewart/MediaManager/issues/41) TV tuner |
| **Mobile apps** | Yes (iOS, Android -- no longer requires Plex Pass) | Yes (community) | Yes | No -- [#1](https://github.com/jeffbstewart/MediaManager/issues/1) iOS app |
| **Smart TV apps** | Yes (all major platforms) | Yes (expanding; Roku app with Dolby Vision/HDR10+ support) | Yes | Roku only (custom sideloaded) |
| **Seek thumbnails** | Yes (premium) | Yes (trickplay, free -- 100x faster in 10.11) | Yes (premium) | No -- [#39](https://github.com/jeffbstewart/MediaManager/issues/39) BIF trick play |
| **Subtitle support** | Excellent (multiple formats, download) | Excellent (OpenSubtitles plugin) | Excellent | SRT generation from transcodes; auto-enabled -- [#40](https://github.com/jeffbstewart/MediaManager/issues/40) subtitle toggle |
| **Watch history sync** | Cross-device | Cross-device | Cross-device | Cross-device (browser <-> Roku via server) |
| **Continue Watching** | Yes | Yes | Yes | Web only; not on Roku -- [#29](https://github.com/jeffbstewart/MediaManager/issues/29) personalized home rows |
| **Home/personal videos** | No | No | No | **Yes** (personal/home video support -- [#28](https://github.com/jeffbstewart/MediaManager/issues/28) Done) |

### Category 2: Collection Catalogs

| Feature | **CLZ Movies** | **DVD Profiler** | **My Movies** | **MediaManager** |
|---------|---------------|-----------------|--------------|-----------------|
| **License** | $2/mo or $20/yr (mobile); $4/mo or $40/yr (web) | Defunct (website down since Jan 2026) | Free/premium | Free, self-hosted |
| **Barcode scanning** | Yes (camera, 98% hit rate) | N/A (discontinued) | Yes | **Yes** (phone camera via PWA + UPC lookup -- [#42](https://github.com/jeffbstewart/MediaManager/issues/42) Done) |
| **Metadata source** | IMDb | N/A | Multiple | TMDB (posters, cast, genres, descriptions, popularity, collections) |
| **Physical format tracking** | DVD, Blu-ray, 4K UHD, HD-DVD, LaserDisc, VHS, UMD | N/A | DVD, Blu-ray | DVD, Blu-ray, UHD (auto-detected via FFprobe resolution) |
| **Purchase price tracking** | Yes (purchase price, store, date) | N/A | No | **Yes** (valuation, Amazon import, insurance reporting) |
| **Multi-pack detection** | No | N/A | No | **Yes** (double features, trilogies, box sets auto-detected) |
| **Wish list** | Basic | N/A | No | **Yes** (TMDB search, admin review, season lifecycle, vote aggregation) -- [#43](https://github.com/jeffbstewart/MediaManager/issues/43) shareable wish lists |
| **Playback integration** | None | N/A | None | **Yes** (in-browser + Roku streaming) |
| **Collection value/insurance** | eBay price lookup | N/A | No | **Yes** (purchase tracking, executive summary, gap analysis, proof of ownership -- [#44](https://github.com/jeffbstewart/MediaManager/issues/44)--[#46](https://github.com/jeffbstewart/MediaManager/issues/46) Done; [#51](https://github.com/jeffbstewart/MediaManager/issues/51) automated replacement value) |
| **Collection tracking** | Manual | N/A | No | **Yes** (TMDB collection auto-linking, owned vs. unowned visibility -- [#49](https://github.com/jeffbstewart/MediaManager/issues/49)--[#50](https://github.com/jeffbstewart/MediaManager/issues/50) Done) |
| **Platform** | iOS, Android, Web, Windows | N/A | Windows | Web (any device with a browser) |

### Category 3: Request Management (Adjacent)

| Tool | Purpose | Relationship to MediaManager |
|------|---------|------------------------------|
| **Seerr** (merged from Overseerr + Jellyseerr, Feb 2026) | Users request movies/TV; integrates with Sonarr/Radarr to auto-download; supports Plex, Jellyfin, and Emby | MediaManager's wish list serves the same "users request, admin fulfills" workflow, but for *physical media purchases* rather than automated downloads. MediaManager's season lifecycle tracking ([#25](https://github.com/jeffbstewart/MediaManager/issues/25) Done) adds structured fulfillment that Seerr lacks for physical media. |
| **Ombi** | Similar request management for Plex/Emby/Jellyfin | Same comparison as above; likely to lose users to the unified Seerr project |

---

## MediaManager Strengths

### 1. Unique Market Position: Physical-to-Digital Bridge
No other product connects physical disc ownership (barcode scan -> catalog) to digital playback (NAS scan -> transcode -> stream). Plex users who also own physical media must use a separate tool (CLZ, spreadsheet) to track what they own. MediaManager unifies this.

### 2. Purchase, Valuation & Insurance Reporting
No media server tracks what you paid. MediaManager's Amazon order import, per-title purchase prices, and comprehensive insurance reporting suite are unique. The insurance system now includes proof of ownership documentation ([#44](https://github.com/jeffbstewart/MediaManager/issues/44) Done), executive summaries ([#45](https://github.com/jeffbstewart/MediaManager/issues/45) Done), and gap analysis ([#46](https://github.com/jeffbstewart/MediaManager/issues/46) Done). Automated replacement value estimation via price APIs is planned ([#51](https://github.com/jeffbstewart/MediaManager/issues/51)). For collectors with hundreds or thousands of discs, this has real financial value.

### 3. Integrated Wish List with Season Lifecycle
The wish list system -- where household members request titles, votes aggregate, and the admin tracks acquisition status (Ordered -> Owned -> Ready to watch) -- is a novel feature. Structured season lifecycle tracking ([#25](https://github.com/jeffbstewart/MediaManager/issues/25) Done) enables fine-grained management of multi-season TV series wishes. Seerr serves a similar role for automated download workflows, but MediaManager's version is designed for legitimate physical media purchasing. See [#43](https://github.com/jeffbstewart/MediaManager/issues/43) for shareable wish lists (gift giving).

### 4. Multi-Pack Intelligence
Automatic detection and expansion of double features, trilogies, and box sets during barcode scanning. No competitor does this.

### 5. Zero External Dependencies
No cloud account required. No telemetry. No subscription. No third-party service that can change terms, shut down, or degrade. The entire stack (app server, database, media files) lives on hardware you own. This advantage has grown more relevant as Plex now paywalls remote TV streaming and has raised prices significantly.

### 6. Format-Aware Transcoding Pipeline
Automatic FFprobe-based format detection (resolution -> DVD/Blu-ray/UHD), codec-aware transcoding (copy H.264, re-encode HEVC/MPEG-2), interlace detection, anamorphic SAR correction, and Roku-compatible output -- all handled automatically with no user configuration.

### 7. Tight Roku Integration
Custom-built Roku channel with QR-code device pairing, server-side progress sync, and subtitle support. While the feature set is still maturing vs. Jellyfin's Roku app, the tight integration with the catalog and wish list systems is a differentiator. See issues [#29](https://github.com/jeffbstewart/MediaManager/issues/29)--[#40](https://github.com/jeffbstewart/MediaManager/issues/40) for the Roku enhancement roadmap.

### 8. Mobile Barcode Scanning
Phone camera barcode scanning via PWA ([#42](https://github.com/jeffbstewart/MediaManager/issues/42) Done) now matches CLZ Movies' core cataloging workflow. Users can point their phone camera at a disc barcode to catalog it, closing what was previously a major gap with dedicated collection apps.

### 9. TMDB Collection Tracking
Automatic discovery of TMDB collections (franchises, series) with visibility into owned vs. unowned titles ([#49](https://github.com/jeffbstewart/MediaManager/issues/49)--[#50](https://github.com/jeffbstewart/MediaManager/issues/50) Done). No media server or catalog app surfaces "you own 3 of 5 films in the Marvel franchise" insights. Combined with the catalog sub-navigation ([#48](https://github.com/jeffbstewart/MediaManager/issues/48) Done), this creates a structured browsing experience organized by Movies, Collections, TV Shows, Family, Tags, and Live.

### 10. Personal/Home Video Support
Integration of personal and home videos alongside the commercial media library ([#28](https://github.com/jeffbstewart/MediaManager/issues/28) Done). No media server competitor treats home videos as first-class catalog entries alongside purchased media.

---

## MediaManager Weaknesses

### 1. Client App Coverage (Critical Gap)
Only web browser and a sideloaded Roku channel. No iOS app, no Android app, no Apple TV, no Fire TV, no Android TV, no smart TV apps. Plex and Jellyfin support 10-20+ platforms. This is the single largest competitive disadvantage -- most households expect to watch on their phone or tablet. See [#1](https://github.com/jeffbstewart/MediaManager/issues/1) (iOS app) and [#5](https://github.com/jeffbstewart/MediaManager/issues/5) (mobile offline playback).

### 2. No Remote Access Out of the Box
Plex's cloud relay provides zero-config remote streaming (though now paywalled for TV apps). MediaManager requires manual reverse proxy setup (nginx, Caddy, etc.) for access outside the LAN. This is a barrier for less technical users and for watching media away from home.

### 3. No Search on Roku
The Roku channel has no search or filtering -- all content is displayed in two flat rows. Jellyfin's Roku app now supports search, genre browsing, Dolby Vision HDR, and more sophisticated navigation. For libraries with hundreds of titles, this makes content discovery painful. See [#31](https://github.com/jeffbstewart/MediaManager/issues/31) (search with voice) and [#32](https://github.com/jeffbstewart/MediaManager/issues/32) (genre/tag filtering).

### 4. Single-Household Scale
The architecture assumes a single household with one admin managing a physical collection. There's no concept of shared servers, friend access, or federated libraries -- features that Plex and Jellyfin users rely on.

### 5. No Live TV / DVR
Plex, Jellyfin, and Emby all support live TV tuners and DVR recording. MediaManager has no support for live content. See [#27](https://github.com/jeffbstewart/MediaManager/issues/27) (security cameras) and [#41](https://github.com/jeffbstewart/MediaManager/issues/41) (TV tuner).

### 6. Manual Transcoding Pipeline
Plex and Jellyfin transcode on-the-fly at playback time, adapting quality to the client's bandwidth and capabilities. MediaManager pre-transcodes everything to a single MP4 format in the background. This means:
- New content isn't playable until the transcode queue reaches it
- Storage is doubled (source + ForBrowser copy)
- No adaptive bitrate streaming

### 7. Limited Ecosystem Integration
No Trakt.tv sync, no Sonarr/Radarr integration, no OpenSubtitles plugin, no DLNA server. The self-hosted media community expects these integrations.

---

## Key Differentiating Features to Improve Positioning

### High Impact, Moderate Effort

| Feature | Why It Matters | Competitive Effect | Tracked In |
|---------|---------------|-------------------|------------|
| **Roku home screen personalization** (Continue Watching, Recently Added) | Basic expectation from anyone who has used Netflix/Plex/Jellyfin | Removes the most obvious "this feels amateur" signal on the Roku | [#29](https://github.com/jeffbstewart/MediaManager/issues/29) |
| **Roku search with voice** | Libraries over ~50 titles are unusable without search; Jellyfin Roku now has search | Matches Jellyfin Roku's search capability | [#31](https://github.com/jeffbstewart/MediaManager/issues/31) |
| **Auto-play next episode** | Table-stakes for TV binge watching | Removes a constant friction point vs. every competitor | [#33](https://github.com/jeffbstewart/MediaManager/issues/33) |
| **Automated replacement value estimation** | Completes the insurance reporting suite with live market pricing | No competitor offers automated collection valuation | [#51](https://github.com/jeffbstewart/MediaManager/issues/51) |

### High Impact, High Effort

| Feature | Why It Matters | Competitive Effect | Tracked In |
|---------|---------------|-------------------|------------|
| **Mobile streaming app** (iOS/Android, even a PWA) | Most media consumption happens on phones/tablets; web browser playback on mobile is subpar | Addresses the #1 competitive weakness | [#1](https://github.com/jeffbstewart/MediaManager/issues/1) |
| **On-the-fly transcoding** (or adaptive bitrate) | Eliminates the "wait for transcode queue" delay and reduces storage | Matches Plex/Jellyfin's core streaming experience | -- |
| **Additional TV platform apps** (Fire TV, Apple TV, Android TV) | Expands device coverage beyond Roku | Addresses client app coverage gap | -- |

### Medium Impact, Low Effort (Quick Wins)

| Feature | Why It Matters | Competitive Effect | Tracked In |
|---------|---------------|-------------------|------------|
| **Roku title detail enhancements** (backdrop, rating badge, tags) | Data already in the feed; purely UI work | Makes the Roku experience feel polished | [#34](https://github.com/jeffbstewart/MediaManager/issues/34) |
| **Roku subtitle toggle** | User control over an existing feature | Matches Plex/Jellyfin UX expectations | [#40](https://github.com/jeffbstewart/MediaManager/issues/40) |
| **Roku multi-user picker** | Per-user watch history and content ratings | Matches Plex/Jellyfin household model | [#30](https://github.com/jeffbstewart/MediaManager/issues/30) |
| **Shareable wish lists** | Gift-giving workflow for birthdays/holidays | No competitor offers this | [#43](https://github.com/jeffbstewart/MediaManager/issues/43) |

### Strategic Differentiators (Unique to MediaManager)

These features lean into what competitors *don't* do, rather than chasing parity:

| Feature | Why It Matters | Tracked In |
|---------|---------------|------------|
| **Automated replacement value** (complete the insurance suite) | No media server generates automated market-value estimates for a physical collection. The insurance reporting foundation is built ([#44](https://github.com/jeffbstewart/MediaManager/issues/44)--[#46](https://github.com/jeffbstewart/MediaManager/issues/46) Done); automated pricing is the capstone. | [#51](https://github.com/jeffbstewart/MediaManager/issues/51) |
| **Physical-to-digital gap analysis** | "You own 400 Blu-rays but only 250 are ripped" -- surface the backlog with priority recommendations. Already partially implemented via the Transcode Backlog page. | -- |
| **Family wish list with seasonal events** | The wish list + admin fulfillment flow maps perfectly to birthdays and holidays. Season lifecycle tracking ([#25](https://github.com/jeffbstewart/MediaManager/issues/25) Done) adds structured multi-season management. Lean into this -- "What does Dad want for his birthday? Check the wish list." No media server does this. | [#43](https://github.com/jeffbstewart/MediaManager/issues/43) |
| **Disc lending tracker** | "Who has my copy of Inception?" -- physical media lending is a real problem for collectors. No competitor addresses it. | -- |
| **Live content** (cameras + TV tuner) | Consolidates home AV into one app -- security cameras and live TV alongside the media library. No single competitor covers all three. | [#27](https://github.com/jeffbstewart/MediaManager/issues/27), [#41](https://github.com/jeffbstewart/MediaManager/issues/41) |

---

## Positioning Recommendation

MediaManager should **not** try to be a Plex/Jellyfin replacement. It cannot win on client app breadth, ecosystem integrations, or transcoding sophistication -- those projects have years of development and large communities.

Instead, MediaManager should position as: **"The complete physical media management system -- from purchase to playback."**

The target user is a physical media collector (DVD/Blu-ray/4K UHD) who:
- Wants to catalog what they own (barcodes, metadata, purchase history)
- Wants to play their collection without swapping discs (NAS + transcoding)
- Wants household members to browse and request new titles (wish lists)
- Values ownership and self-hosting over streaming subscriptions
- Wants insurance documentation and collection value tracking

The competitive moat is the **integration between physical ownership tracking and digital playback** -- a workflow that requires duct-taping 2-3 products together with any other solution (CLZ + Plex + Seerr, for example). Plex's recent price increases and remote streaming paywalls strengthen this positioning: users paying $250 for a Plex lifetime pass still can't track what discs they own, what they paid, or generate an insurance report.

### Priority Roadmap Suggestion

1. ~~**Mobile barcode scanning** ([#42](https://github.com/jeffbstewart/MediaManager/issues/42))~~ -- Done
2. ~~**Collection reporting** ([#44](https://github.com/jeffbstewart/MediaManager/issues/44)--[#46](https://github.com/jeffbstewart/MediaManager/issues/46))~~ -- Done (core suite shipped; [#51](https://github.com/jeffbstewart/MediaManager/issues/51) automated pricing is next)
3. **Roku personalization + search + auto-play** ([#29](https://github.com/jeffbstewart/MediaManager/issues/29), [#31](https://github.com/jeffbstewart/MediaManager/issues/31), [#33](https://github.com/jeffbstewart/MediaManager/issues/33)) -- Makes the "to playback" side competitive with Jellyfin's improving Roku app
4. **Automated replacement value** ([#51](https://github.com/jeffbstewart/MediaManager/issues/51)) -- Completes the insurance reporting moat
5. **Mobile playback app** ([#1](https://github.com/jeffbstewart/MediaManager/issues/1)) -- Expands reach without competing head-to-head on server architecture

---

## Sources

- [Best Home Media Server 2026: Jellyfin vs Plex vs Emby](https://selfhosthero.com/jellyfin-vs-plex-vs-emby-home-media-server-comparison/)
- [Plex vs Jellyfin vs Emby on a NAS](https://nascompares.com/guide/plex-vs-jellyfin-vs-emby-on-a-nas-which-is-best-for-your-synology-qnap-truenas-or-other-nas/)
- [Jellyfin Roku App Updates](https://www.xda-developers.com/jellyfins-roku-app-just-got-good-enough-to-ditch-plex/)
- [Jellyfin Roku v3.1.6 Release](https://www.howtogeek.com/jellyfin-the-open-source-media-server-just-got-better-on-roku-tvs/)
- [Jellyfin 10.11.0 Release Notes](https://jellyfin.org/posts/jellyfin-release-10.11.0/)
- [State of the Fin 2026-01-06](https://jellyfin.org/posts/state-of-the-fin-2026-01-06/)
- [CLZ Movies](https://clz.com/movies)
- [CLZ Movies Pricing](https://app.clz.com/movies/pricing)
- [Seerr (unified Overseerr + Jellyseerr)](https://seerr.dev/)
- [Seerr Release Announcement](https://docs.seerr.dev/blog/seerr-release)
- [Ombi](https://github.com/Ombi-app/Ombi)
- [Plex Important 2025 Updates (pricing + remote streaming changes)](https://www.plex.tv/blog/important-2025-plex-updates/)
- [Plex Plans & Pricing](https://www.plex.tv/plans/)
- [Plex Remote Streaming Paywall (How-To Geek)](https://www.howtogeek.com/plex-is-now-enforcing-remote-play-restrictions-on-tvs/)
- [Plex Lifetime Pass Price Hike (PCWorld)](https://www.pcworld.com/article/2642674/plexs-lifetime-subscription-plan-is-getting-a-massive-price-hike.html)
- [Emby Premiere Pricing](https://emby.media/premiere.html)
- [DVD Profiler (Wikipedia)](https://en.wikipedia.org/wiki/DVD_Profiler)
- [Kodi vs Plex vs Jellyfin vs Emby Showdown](https://diymediaserver.com/post/kodi-vs-plex-vs-jellyfin-vs-emby-the-ultimate-media-playback-software-showdown/)
- [Top 7 Plex Alternatives 2026](https://www.rapidseedbox.com/blog/plex-alternatives)

---

## Maintaining This Document

To update this competitive analysis in a future Claude Code session, use the following prompt:

> Read `docs/COMPETITIVE_ANALYSIS.md` and `CLAUDE.md`. Search the web for the current state of Plex, Jellyfin, Emby, CLZ Movies, Overseerr/Jellyseerr, and any new entrants in the home media server and physical media cataloging spaces. Fetch the GitHub issues list with `gh issue list --limit 100 --state all`. Then update `docs/COMPETITIVE_ANALYSIS.md`:
>
> 1. Update the date at the top.
> 2. Refresh the comparison tables -- check for new features, pricing changes, discontinued products, or new competitors.
> 3. Move any MediaManager features that have been implemented from "Weaknesses" or "Key Differentiating Features" into "Strengths."
> 4. Update all issue annotations -- replace references to closed issues with "Done" or remove them, and add references to any new relevant issues.
> 5. Re-evaluate the Priority Roadmap based on what has shipped and what the current gap analysis suggests.
> 6. Add any new sources used to the Sources section.
> 7. Keep the same structure, tone, and formatting conventions.

---

<p align="center">
  <a href="index.md">Documentation Home</a> &bull;
  <a href="FEATURES.md">Feature Tracker</a> &bull;
  <a href="https://github.com/jeffbstewart/MediaManager/issues">GitHub Issues</a>
</p>
