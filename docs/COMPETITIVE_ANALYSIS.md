<p align="center">
  <img src="images/logo.png" alt="Media Manager" width="96">
</p>

# Competitive Analysis

*Updated April 18, 2026 (music-launch pass)*

---

## Executive Summary

MediaManager occupies a unique position in the home media management space: it is the only product that integrates **physical media cataloging across formats** -- DVDs, Blu-rays, UHDs, **books, and CDs / vinyl** (barcode scanning, purchase tracking, valuation, insurance reporting) -- with **digital playback** (NAS-based transcoding for video, in-browser EPUB/PDF reader for ebooks, on-the-fly audio streaming with a persistent "Up Next" queue, Roku / Android TV streaming) in a single self-hosted application. The competitive landscape now splits into four categories that MediaManager bridges: **media servers** (Plex, Jellyfin, Emby) that stream digital video but ignore physical ownership; **ebook servers** (Calibre-Web, Kavita, Booklore, Audiobookshelf) that catalog and stream digital books but not video or physical media; **music servers** (Navidrome, Gonic) that stream a ripped audio library but have no physical-disc awareness, no video, no books, and no unified wishlist; and **collection catalogs** (CLZ Movies, DVD Profiler) that track what you own on various formats but don't play anything and require separate apps per media type. No competitor unifies all four.

This is both the product's greatest strength and its strategic challenge: it competes on four fronts simultaneously, and is currently behind dedicated solutions on each front individually. However, rapid development through early 2026 has significantly closed the gap -- the Roku channel is feature-complete with search, personalized home rows, auto-play next episode, seek thumbnails, actor/tag/collection landing pages, and multi-user support. The cataloging side gained automated replacement value estimation, mobile barcode scanning, and comprehensive insurance reporting. Two native client apps shipped: a **native iOS app** (custom video player with subtitles, skip segments, thumbnail scrubbing, wish list, cameras, live TV, admin views, offline downloads) and a **native Android TV / Google TV app** built with Jetpack Compose for TV (multi-user picker, home carousels, ExoPlayer playback with subtitles and skip intro/credits and auto-next, wish list + TMDB search, cameras, live TV, search, TLS). The catalog then extended beyond video into **books** -- physical books catalogue by ISBN barcode scan (Open Library metadata), .epub / .pdf files from a NAS directory ingest automatically, and the web app ships an in-browser paginated EPUB reader plus native PDF viewing, with linked author and series browse pages and wishlist-for-books end-to-end. Most recently it has extended into **music**: the album/track/artist schema landed, the CD-barcode and NAS-audio ingestion paths both ship, a persistent bottom-bar audio player with an "Up Next" drop-up queue panel plays ripped tracks via on-the-fly FFmpeg transcode (no pre-transcode queue for audio), **Start Radio** seeds a continuously-refilling owned-library queue from any album or track (Last.fm similar-artist data, graceful-degrade when the key is removed), and a nightly **RecommendationAgent** surfaces unowned artists on a Discover page ("because you own Duran Duran, Depeche Mode, and The Cure"). Artist pages render biographies pulled from MusicBrainz + Wikipedia, with discography cover art sourced from the Cover Art Archive release-group endpoint, and embedded cover art gets extracted straight out of the audio files during the NAS scan.

**Notable competitive shifts since the last analysis:**
- **Plex** remote playback paywall now fully enforced across all TV platforms (Samsung, LG, Vizio, PlayStation, Xbox, Fire TV) as of March 31, 2026. PMS 1.43.1.10611 is the current stable. Lifetime Plex Pass remains at its doubled $249.99 price point. Smart TV app UI refresh planned for all platforms by end of 2026. Remote Watch Pass introductory pricing ($1.99/mo, $19.99/yr) still ends June 1, 2026, rising to $2.99/mo and $29.99/yr. The paywall notably does **not** apply to music or photos -- a tacit acknowledgement from Plex that music isn't where the monetization pressure is.
- **Jellyfin** released Server 10.11.8 (April 5, 2026) shortly after 10.11.7 -- a bugfix release cleaning up regressions from the critical security patch, with the explicit goal of getting users onto an updated build before the four CVEs in 10.11.7 were publicly disclosed. 10.11.8 improves subtitle saving, media language filtering, and folder handling.
- **Emby** shipped Beta 4.10.0.10 on April 11, 2026. Stable remains 4.9.3.0 (Jan 8, 2026). Beta adds SQLite 3.51.3, a "played" filter and display mode options on home-screen sections, a new Spotlight home-screen section, and fixes realtime-monitor failures for directories containing periods. Native Linux app beta continues. Premiere pricing unchanged: $4.99/mo, $54/yr, $119 lifetime.
- **CLZ Movies** pushed mobile down-sync optimizations on April 14, 2026 (follow-on to the v10.3 faster-sync release from April 1). Pricing and feature set otherwise unchanged since the last analysis.
- **Seerr** no material change since v3.1.0 (Feb 28, 2026). Legacy Overseerr/Jellyseerr removal deadline of end-March 2026 has passed; those projects are now officially deprecated.
- **Mydia** still 0.9.0. Cross-platform P2P player announced in February remains the highlight. Pre-1.0 breaking-change warning still applies.
- **Ebook servers** -- Calibre-Web remains the most mature self-hosted option; Kavita continues to specialize in manga/comics/light novels; Booklore has gained attention as a modern Calibre alternative with a built-in OPDS server; Audiobookshelf covers audiobooks + podcasts. None cross into physical-disc cataloging or video playback.
- **Music servers** (new category, relevant to MediaManager's music launch) -- **Navidrome 0.60** (Feb 2026) shipped a WebAssembly plugin system, a pure-Go metadata extractor, and an **Instant Mix** playlist tool for music discovery. It remains the dominant self-hosted Subsonic-compatible server (lightweight Go binary, 30-50 MB idle RAM, large third-party mobile-app ecosystem via Subsonic API). **Gonic** continues as the lightest-footprint alternative favoured by Raspberry Pi users. Neither project catalogues physical discs, handles video, handles books, or ties audio playback into a unified wishlist / insurance-reporting / collection-value model -- the shape of the gap is identical to the one Calibre-Web leaves on the book side.
- **DVD Profiler** website recovered after August 2025 server migration, but the software itself has not been updated since version 4.0.0 (~2017). Android app unpublished from Google Play in 2021. Effectively dormant.
- **MediaManager** launched full **music support** on top of the existing books and video catalogue: CD-barcode ingestion (EAN-13 -> MusicBrainz release-group, fallback UPCitemdb), NAS audio-file scanner for `.flac` / `.mp3` / `.m4a` / `.ogg` / `.wav` / `.opus` (FFprobe reads embedded MBID / ISRC / disc-number tags authoritatively), **embedded cover-art extraction during the music scan** (art pulled straight out of the audio files when Cover Art Archive misses), first-class artist pages with biographies (MusicBrainz + Wikipedia), discography cover art from the Cover Art Archive **release-group** endpoint (not per-release, so one cover per work instead of dozens per pressing), album detail pages with per-disc track lists, a persistent bottom-bar web audio player with volume control, an **"Up Next" drop-up queue panel** for queue management (play-next, remove, reorder), **M7 Start Radio** (seed from album or track; pipeline is similar-artists -> intersect owned -> 3 canonical tracks per similar-artist -> round-robin interleave -> tail-pad with deeper seed-artist cuts; skip-weighting within 30s down-weights that artist for the session; graceful-degrade on cached data when the Last.fm key is absent), **M8 Library Recommendations** (nightly `RecommendationAgent` scores unowned artists weighted by how many owned albums vote for each suggestion; Discover page + "Artists you might like" home-row carousel; per-user dismiss-persistence). Also shipped **accent-insensitive search** (`cafe` matches `caf&eacute;` and vice versa) and expanded the search result types so **tracks, artists, authors, books, and albums** are first-class result types alongside movies and TV shows, with artist-biography matches surfacing directly. Also launched earlier in this cycle: native **Android TV / Google TV app** (Jetpack Compose for TV) covering multi-user, home carousels, ExoPlayer playback with subtitles and skip intro/credits and auto-next, wish list, cameras, live TV, search, and TLS; full books support (ISBN barcode scanning via Open Library, .epub / .pdf NAS scanner, in-browser paginated EPUB reader (epub.js, full-viewport route) with resume, native PDF viewing, author and series browse pages with "Other Works" / "Missing Volumes" from OL, wishlist-for-books with one-click heart actions, and an **Unmatched Books** admin queue with three resolution paths); external skip-detection import ([#54](https://github.com/jeffbstewart/MediaManager/issues/54) Done); custom web progress bar ([#57](https://github.com/jeffbstewart/MediaManager/issues/57) Done); chapter seeking in the video player ([#55](https://github.com/jeffbstewart/MediaManager/issues/55) Done); H2_FILE_PASSWORD rotation ([#24](https://github.com/jeffbstewart/MediaManager/issues/24) Done); and image-proxy hardening (SSRF re-screening on every redirect hop up to 4, Content-Type inference fallback for OL covers that omit the header, `?default=false` on OL URLs, Wikipedia author-headshot cache so Wikimedia URLs never reach the client and CSP can stay `img-src 'self'`). Post-NAS-scan trigger now kicks both the ebook scanner and the music scanner automatically.

---

## Competitive Landscape

### Category 1: Media Servers

| Feature | **Plex** | **Jellyfin** | **Emby** | **MediaManager** |
|---------|----------|-------------|----------|-----------------|
| **License** | Freemium (Plex Pass: $6.99/mo, $69.99/yr, or $249.99 lifetime; Remote Watch Pass: $1.99/mo intro rising to $2.99/mo after Jun 2026, or $19.99/yr rising to $29.99/yr) | Free, open-source | Freemium (Emby Premiere: $4.99/mo, $54/yr, or $119 lifetime) | Free, self-hosted |
| **Client apps** | 20+ platforms (smart TVs, mobile, web, Roku, Fire TV, Apple TV, gaming consoles) | 10+ platforms (web, mobile, Roku, Android TV, Apple TV -- community-maintained) | 15+ platforms (similar to Plex; new native Linux app beta) | Web browser + custom Roku channel + **native iOS app** ([#1](https://github.com/jeffbstewart/MediaManager/issues/1)) + **native Android TV / Google TV app** (Jetpack Compose for TV) -- [#5](https://github.com/jeffbstewart/MediaManager/issues/5) mobile offline |
| **Transcoding** | Excellent; hardware accel behind paywall | Excellent; hardware accel free; Dolby Vision tone-mapping | Excellent; hardware accel behind paywall | FFmpeg-based; CPU and NVENC GPU; background pre-transcoding |
| **Library scanning** | Automatic file detection + rich metadata | Automatic file detection + metadata | Automatic file detection + metadata | Automatic NAS scanning + TMDB enrichment + TMDB collection tracking |
| **User management** | Multi-user with managed/shared accounts | Multi-user with parental controls | Multi-user with parental controls | Multi-user with access levels and content rating ceilings; Roku multi-user picker ([#30](https://github.com/jeffbstewart/MediaManager/issues/30) Done) |
| **Remote access** | Cloud relay (requires Plex Pass or Remote Watch Pass for TV apps) | Manual (reverse proxy) | Built-in option | Manual (reverse proxy) |
| **Live TV/DVR** | Yes (premium) | Yes (via plugins) | Yes (premium) | Live TV via HDHomeRun ([#27](https://github.com/jeffbstewart/MediaManager/issues/27) cameras Done, [#41](https://github.com/jeffbstewart/MediaManager/issues/41) tuner Done); no DVR yet |
| **Mobile apps** | Yes (iOS, Android -- no longer requires Plex Pass or $4.99 fee) | Yes (community) | Yes | **Yes** (native iOS app with custom player, subtitles, skip segments, thumbnail scrubbing, offline downloads -- [#1](https://github.com/jeffbstewart/MediaManager/issues/1), [#5](https://github.com/jeffbstewart/MediaManager/issues/5) Done); no Android yet |
| **Smart TV apps** | Yes (all major platforms) | Yes (expanding; Roku app v3.1.7 with Dolby Vision/HDR10+/HLG; Xbox with gamepad + 4K/HDR) | Yes (Samsung Smart TV 2.1.0 with auto-skip intros) | Roku (custom sideloaded) + **Android TV / Google TV** (native Compose for TV app). No Fire TV, Apple TV, or Samsung/LG/Vizio yet. |
| **Seek thumbnails** | Yes (premium) | Yes (trickplay, free -- 100x faster in 10.11) | Yes (premium) | **Yes** (BIF trick play on Roku + thumbnail scrubbing on iOS -- [#39](https://github.com/jeffbstewart/MediaManager/issues/39) Done) |
| **Subtitle support** | Excellent (multiple formats, download) | Excellent (OpenSubtitles plugin) | Excellent | SRT generation from transcodes; VTT rendering on iOS; auto-enabled -- [#40](https://github.com/jeffbstewart/MediaManager/issues/40) Roku subtitle toggle |
| **Watch history sync** | Cross-device | Cross-device | Cross-device | Cross-device (browser <-> Roku <-> iOS via server) |
| **Continue Watching** | Yes | Yes | Yes | **Yes** (personalized home rows -- [#29](https://github.com/jeffbstewart/MediaManager/issues/29) Done) |
| **Search** | Yes (full-text, voice) | Yes (search, genre browsing) | Yes | **Yes** (accent-insensitive text + voice search; results include movies, TV, tracks, albums, artists, books, authors, actors as first-class types; artist-biography matches surface directly -- [#31](https://github.com/jeffbstewart/MediaManager/issues/31), [#32](https://github.com/jeffbstewart/MediaManager/issues/32) Done) |
| **Auto-play next episode** | Yes | Yes | Yes | **Yes** ([#33](https://github.com/jeffbstewart/MediaManager/issues/33) Done) |
| **Home/personal videos** | No | No | No | **Yes** (personal/home video support -- [#28](https://github.com/jeffbstewart/MediaManager/issues/28) Done) |
| **Ebooks (EPUB/PDF) in same catalog** | No (audiobooks only, via music library hack) | No | No | **Yes** (ISBN scan + .epub/.pdf scanner, Open Library metadata, in-browser paginated reader, author/series browse, wishlist) |
| **Music (CDs + ripped audio) in same catalog** | Music library (no physical-disc tracking, no barcode scan, no per-album purchase price); paywall exempts music | Music library (no physical-disc tracking) | Music library (no physical-disc tracking) | **Yes** (CD-barcode ingest via MusicBrainz + FFprobe NAS audio scanner with embedded-art extraction, persistent bottom-bar player with "Up Next" queue panel and volume control, on-the-fly FFmpeg transcode, **Start Radio** and nightly library-based recommendations) |

### Category 2: Collection Catalogs

| Feature | **CLZ Movies** | **DVD Profiler** | **My Movies** | **MediaManager** |
|---------|---------------|-----------------|--------------|-----------------|
| **License** | $1.99/mo or $19.99/yr (mobile); $3.95/mo or $39.95/yr (web) | Dormant (no updates since 4.0.0, ~2017) | Free/premium | Free, self-hosted |
| **Barcode scanning** | Yes (camera, 98% hit rate; new one-by-one mode) | N/A (discontinued) | Yes | **Yes** (phone camera via PWA + UPC lookup -- [#42](https://github.com/jeffbstewart/MediaManager/issues/42) Done) |
| **Metadata source** | IMDb | N/A | Multiple | TMDB (posters, cast, genres, descriptions, popularity, collections) |
| **Physical format tracking** | DVD, Blu-ray, 4K UHD, HD-DVD, LaserDisc, VHS, UMD | N/A | DVD, Blu-ray | DVD, Blu-ray, UHD (auto-detected via FFprobe resolution); **physical and digital books** (mass-market paperback, trade paperback, hardcover, EPUB, PDF) |
| **Books in the same catalog** | No (separate CLZ Books app, ~$20/yr) | N/A | No | **Yes** (unified catalogue across video and books; no additional app or subscription) |
| **Purchase price tracking** | Yes (purchase price, store, date; new currency selection) | N/A | No | **Yes** (valuation, Amazon import, automated Keepa pricing, insurance reporting) |
| **Multi-pack detection** | No | N/A | No | **Yes** (double features, trilogies, box sets auto-detected) |
| **Wish list** | Basic | N/A | No | **Yes** (TMDB search, admin review, season lifecycle, vote aggregation) -- [#43](https://github.com/jeffbstewart/MediaManager/issues/43) shareable wish lists |
| **Playback integration** | None | N/A | None | **Yes** (in-browser + Roku streaming) |
| **Collection value/insurance** | eBay price lookup; automatic eBay search links (new) | N/A | No | **Yes** (automated Keepa replacement pricing, executive summary, gap analysis, proof of ownership -- [#44](https://github.com/jeffbstewart/MediaManager/issues/44)--[#46](https://github.com/jeffbstewart/MediaManager/issues/46), [#51](https://github.com/jeffbstewart/MediaManager/issues/51) Done) |
| **Collection tracking** | Manual | N/A | No | **Yes** (TMDB collection auto-linking, owned vs. unowned visibility -- [#49](https://github.com/jeffbstewart/MediaManager/issues/49)--[#50](https://github.com/jeffbstewart/MediaManager/issues/50) Done) |
| **Custom fields** | Yes (launched Feb 2026) | N/A | No | No (structured schema; tags provide flexible categorization) |
| **TV episode editing** | Yes (added Sep 2025; XML import from Movie Collector) | N/A | No | No (TV series managed via TMDB metadata) |
| **Platform** | iOS, Android, Web, Windows | N/A | Windows | Web (any device with a browser) |

### Category 3: Request Management (Adjacent)

| Tool | Purpose | Relationship to MediaManager |
|------|---------|------------------------------|
| **Seerr** (v3.1.0, merged from Overseerr + Jellyseerr; legacy projects deprecated, feature freeze lifted) | Users request movies/TV; integrates with Sonarr/Radarr to auto-download; supports Plex, Jellyfin, and Emby; TheTVDB metadata provider | MediaManager's wish list serves the same "users request, admin fulfills" workflow, but for *physical media purchases* rather than automated downloads. MediaManager's season lifecycle tracking ([#25](https://github.com/jeffbstewart/MediaManager/issues/25) Done) adds structured fulfillment that Seerr lacks for physical media. |
| **Ombi** | Similar request management for Plex/Emby/Jellyfin | Likely to lose users to the unified Seerr project |

### Category 4: Ebook Servers (Adjacent, Relevant to Books Launch)

| Tool | Purpose | Relationship to MediaManager |
|------|---------|------------------------------|
| **Calibre-Web** | The most mature self-hosted ebook server; web interface over a Calibre library with user management, in-browser reading, OPDS feeds, and format conversion. | Dedicated ebook workflow, strong format conversion. MediaManager does not convert formats or expose OPDS, but unifies books with the rest of the physical catalogue and adds wishlist/fulfillment + author & series browse grounded in Open Library rather than Calibre's local metadata. |
| **Kavita** | Self-hosted digital library with first-class manga, comic, and light-novel support (right-to-left, double-page spreads). | Specialized for comic formats MediaManager does not target. Non-overlapping. |
| **Booklore** | Modern Calibre-alternative with built-in OPDS server and a cleaner onboarding. | Same space as Calibre-Web; same lack of overlap with physical-disc cataloguing or video. |
| **Audiobookshelf** | Self-hosted audiobooks and podcasts with phone apps. | MediaManager does not catalogue audiobooks yet; the ingestion pipeline could be extended, but this is not on the near-term roadmap. |

### Category 5: Music Servers (Adjacent, Relevant to Music Launch)

| Tool | Purpose | Relationship to MediaManager |
|------|---------|------------------------------|
| **Navidrome** (0.60, Feb 2026) | Lightweight Go-based self-hosted music server. Subsonic-API compatible, so a large third-party mobile-app ecosystem (Symfonium, play:Sub, DSub, Ultrasonic) comes along for free. 0.60 added a WebAssembly plugin system, a pure-Go metadata extractor, and an **Instant Mix** playlist tool. 30-50 MB idle RAM. | The closest direct comparison on the audio side. Strong library browsing and mobile client coverage; no physical-disc cataloguing, no video, no books, no wishlist across media types, no insurance reporting, no MusicBrainz-first barcode ingest (relies on tag data). MediaManager's competitive angle is the unified catalogue + the CD-barcode workflow + Start Radio bound to the owned library. |
| **Gonic** | Minimalist Subsonic-API server. On-the-fly transcoding, multi-user, Last.fm / ListenBrainz scrobbling, podcast downloads. Best-in-class footprint for Raspberry Pi / low-power NAS. | Narrower scope than Navidrome. Same non-overlap with MediaManager's physical-cataloguing / video / books / wishlist strengths. |
| **Mopidy** / **Funkwhale** / **Ampache** | Older self-hosted music-server options, various stacks (Python, Django/Python, PHP). | Niche. Same non-overlap pattern. |

### Category 6: New Entrants

| Tool | Purpose | Relationship to MediaManager |
|------|---------|------------------------------|
| **Mydia** (0.9.0, Elixir/Phoenix LiveView; TrueNAS app available) | Self-hosted media management with TMDB/TVDB metadata, automated downloads (qBittorrent/SABnzbd/NZBGet via Prowlarr/Jackett), multi-user with SSO, cross-platform P2P player with encrypted mesh remote access and offline downloads. Positions itself as an "Arr-stack replacement." | Targets the Plex/Jellyfin space with a modern stack. No physical media cataloging. Still pre-1.0 with expected breaking changes. TrueNAS app availability lowers the barrier to entry. Positive XDA Developers coverage noting minimal overhead and Raspberry Pi compatibility. |

---

## MediaManager Strengths

### 1. Unique Market Position: Physical-to-Digital Bridge Across Every Format
No other product connects physical ownership (barcode scan -> catalog) to digital playback / reading / listening (NAS scan -> transcode -> stream) across **video, books, and music simultaneously**. Plex users who also own physical media must use a separate tool (CLZ, spreadsheet) to track what they own. Navidrome users who also want book or disc tracking must stack another three or four products. MediaManager unifies this in one application.

### 2. Purchase, Valuation & Insurance Reporting
No media server tracks what you paid. MediaManager's Amazon order import, per-title purchase prices, and comprehensive insurance reporting suite are unique. The insurance system includes automated Keepa-based replacement value estimation ([#51](https://github.com/jeffbstewart/MediaManager/issues/51) Done), proof of ownership documentation ([#44](https://github.com/jeffbstewart/MediaManager/issues/44) Done), executive summaries ([#45](https://github.com/jeffbstewart/MediaManager/issues/45) Done), and gap analysis ([#46](https://github.com/jeffbstewart/MediaManager/issues/46) Done). CLZ added automatic eBay search links in late 2025, but MediaManager's integrated Keepa pricing with automated market-value estimates is more comprehensive. For collectors with hundreds or thousands of discs, this has real financial value.

### 3. Integrated Wish List with Season Lifecycle
The wish list system -- where household members request titles, votes aggregate, and the admin tracks acquisition status (Ordered -> Owned -> Ready to watch) -- is a novel feature. Structured season lifecycle tracking ([#25](https://github.com/jeffbstewart/MediaManager/issues/25) Done) enables fine-grained management of multi-season TV series wishes. Seerr serves a similar role for automated download workflows, but MediaManager's version is designed for legitimate physical media purchasing. See [#43](https://github.com/jeffbstewart/MediaManager/issues/43) for shareable wish lists (gift giving).

### 4. Multi-Pack Intelligence
Automatic detection and expansion of double features, trilogies, and box sets during barcode scanning. No competitor does this.

### 5. Zero External Dependencies
No cloud account required. No telemetry. No subscription. No third-party service that can change terms, shut down, or degrade. The entire stack (app server, database, media files) lives on hardware you own. This advantage has grown even more relevant as Plex now requires a Plex Pass or Remote Watch Pass for remote TV streaming on all platforms (fully enforced March 2026) and has nearly doubled prices across the board.

### 6. Format-Aware Transcoding Pipeline
Automatic FFprobe-based format detection (resolution -> DVD/Blu-ray/UHD), codec-aware transcoding (copy H.264, re-encode HEVC/MPEG-2), interlace detection, anamorphic SAR correction, and Roku-compatible output -- all handled automatically with no user configuration.

### 7. Full-Featured Roku Channel
Custom-built Roku channel with QR-code device pairing, multi-user picker ([#30](https://github.com/jeffbstewart/MediaManager/issues/30) Done), personalized home rows with Continue Watching and Recently Added ([#29](https://github.com/jeffbstewart/MediaManager/issues/29) Done), text and voice search ([#31](https://github.com/jeffbstewart/MediaManager/issues/31) Done), genre/tag/collection/actor browsing ([#32](https://github.com/jeffbstewart/MediaManager/issues/32), [#36](https://github.com/jeffbstewart/MediaManager/issues/36) Done), similar titles ([#35](https://github.com/jeffbstewart/MediaManager/issues/35) Done), auto-play next episode ([#33](https://github.com/jeffbstewart/MediaManager/issues/33) Done), BIF seek thumbnails ([#39](https://github.com/jeffbstewart/MediaManager/issues/39) Done), backdrop images and clickable tag pills ([#34](https://github.com/jeffbstewart/MediaManager/issues/34) Done), server-side progress sync, and subtitle support. While Jellyfin's Roku app v3.1.7 has broader codec support (Dolby Vision/HDR10+), MediaManager's tight integration with the catalog and wish list systems remains a differentiator.

### 8. Mobile Barcode Scanning
Phone camera barcode scanning via PWA ([#42](https://github.com/jeffbstewart/MediaManager/issues/42) Done) now matches CLZ Movies' core cataloging workflow. Users can point their phone camera at a disc barcode to catalog it, closing what was previously a major gap with dedicated collection apps.

### 9. TMDB Collection Tracking
Automatic discovery of TMDB collections (franchises, series) with visibility into owned vs. unowned titles ([#49](https://github.com/jeffbstewart/MediaManager/issues/49)--[#50](https://github.com/jeffbstewart/MediaManager/issues/50) Done). No media server or catalog app surfaces "you own 3 of 5 films in the Marvel franchise" insights. Combined with the catalog sub-navigation ([#48](https://github.com/jeffbstewart/MediaManager/issues/48) Done), this creates a structured browsing experience organized by Movies, Collections, TV Shows, Family, Tags, and Live.

### 10. Personal/Home Video Support
Integration of personal and home videos alongside the commercial media library ([#28](https://github.com/jeffbstewart/MediaManager/issues/28) Done). No media server competitor treats home videos as first-class catalog entries alongside purchased media.

### 11. Native iOS App with Offline Playback
A full-featured native iOS app ([#1](https://github.com/jeffbstewart/MediaManager/issues/1)) with SwiftUI, featuring SSDP server auto-discovery, gRPC-based app data access, title detail with cast, a custom video player with subtitles (VTT parser), skip segments, and thumbnail scrubbing, wish list management with TMDB search, security cameras, live TV, admin transcode status views, and offline downloads with low-fidelity mobile transcodes ([#5](https://github.com/jeffbstewart/MediaManager/issues/5) Done). This addresses what was previously the #1 competitive weakness and puts MediaManager ahead of CLZ Movies (which has no playback) while narrowing the gap with Plex/Jellyfin on mobile.

### 11a. Native Android TV / Google TV App
A Jetpack Compose for TV app (see [Android TV Guide](ANDROID_TV_GUIDE.md)) with multi-user picker, home-screen poster carousels, movies/TV/family grids with sorting, title detail with cast/genres/tags/backdrop, episode picker with resume progress, ExoPlayer playback with subtitles and skip intro/credits and auto-next-episode, cross-device progress sync, wish list browsing + voting + TMDB search, live camera streaming with go2rtc warm-up, live TV via HDHomeRun, categorized search, and TLS support through HAProxy. This gives MediaManager native coverage of two of the three big living-room platforms (Android TV/Google TV + Roku); only Apple TV and Fire TV remain.

### 12. Unified Books + Video Catalogue
Books are first-class alongside movies and TV. Physical books catalogue by ISBN-13 barcode scan (978/979 prefixes route to Open Library instead of UPCitemdb); EPUB and PDF files on the NAS ingest automatically (EPUBs with embedded ISBNs auto-catalogue; PDFs sitting next to a catalogued EPUB auto-link as sibling editions). The web app ships an in-browser paginated EPUB reader (epub.js) with font-size controls and resume, plus native PDF viewing. Author and series pages mirror the actor/collection pages in the video catalogue, with "Other Works" and "Missing Volumes" pulled from Open Library and one-click heart icons to wishlist. The **Unmatched Books** admin queue handles ingestion edge cases with three resolution paths (by corrected ISBN, by OL title/author search, by linking to an existing catalogue title). CLZ covers books in a separate $20/yr app; Calibre-Web and Kavita only do ebooks. No competitor unifies physical books, ebooks, and physical video in one catalogue.

### 13. Unified Music (CDs + Ripped Audio) Catalogue with Start Radio and Library Recommendations
Music is a third first-class category alongside movies / TV and books. **CD barcodes** scan straight through MusicBrainz (release-group dedup keeps US and UK pressings on the same title) with UPCitemdb as fallback. The NAS audio scanner walks `.flac` / `.mp3` / `.m4a` / `.ogg` / `.wav` / `.opus` and uses FFprobe to read embedded MBID tags authoritatively -- dBpoweramp / EAC rips with `MUSICBRAINZ_ALBUMID` / `MUSICBRAINZ_RELEASETRACKID` identify themselves even when the jewel case (and barcode) are long gone. The scanner also **extracts embedded cover art directly from the audio files** when the Cover Art Archive misses, so home-ripped obscurities still get artwork. Artist pages render biographies from MusicBrainz + Wikipedia; discography art comes from the CAA **release-group** endpoint (one cover per work, not one per pressing). A **persistent bottom-bar audio player** survives page navigations with volume control and an **"Up Next" drop-up queue panel** for play-next / remove / reorder. Playback uses **on-the-fly FFmpeg transcode** with an LRU byte cache keyed by `(track_id, target_codec)` -- no pre-transcode queue, no "wait for the encoder." **Start Radio** seeds a continuously-refilling owned-library queue from any album or track (Last.fm similar-artists -> intersect owned -> round-robin across artists with skip-weighting to down-weight artists whose tracks you skip inside 30 s); works on cached similarity data even when the Last.fm key is absent. A nightly **`RecommendationAgent`** reads the same similar-artist cache pointed at the *unowned* universe, surfaces suggestions on a Discover page and "Artists you might like" home carousel with voter explanations ("because you own Duran Duran, Depeche Mode, and The Cure"), and persists per-user dismissals. Navidrome has Instant Mix but no purchase/ownership model; Plex has music but no barcode / radio / recommendations over a physical collection; no competitor unifies physical disc scanning, NAS rip ingestion, owned-library radio, and unowned-library discovery in one product.

### 14. Accent-Insensitive Universal Search
Search normalizes Unicode diacritics both ways, so `cafe` matches `caf&eacute;` and vice versa. Result types span **movies, TV shows, tracks, artists, albums, books, authors, and cast members** as first-class rows, and artist-biography matches surface directly (search for a genre + a decade and artists whose bios mention both come back). No competitor offers this breadth in a single search box across video, books, and music simultaneously.

### 15. API Rate Limiting and Security Hardening
The pairing API is rate-limited per-IP with a global cap on active pair codes ([#53](https://github.com/jeffbstewart/MediaManager/issues/53) Done). Buddy API hardening completed with device-token pairing ([#7](https://github.com/jeffbstewart/MediaManager/issues/7) Done), lease limits, probe validation, and audit logging ([#13](https://github.com/jeffbstewart/MediaManager/issues/13) Done). H2 database password rotation support shipped ([#24](https://github.com/jeffbstewart/MediaManager/issues/24) Done). Image proxy rejects non-HTTPS redirects, re-runs SSRF host screening on every hop (up to 4), and caps response size. Remaining: [#12](https://github.com/jeffbstewart/MediaManager/issues/12) Docker secrets, [#59](https://github.com/jeffbstewart/MediaManager/issues/59) JWT bind cookie for cookie-auth hardening.

---

## MediaManager Weaknesses

### 1. Client App Coverage (Narrowing Gap)
Web browser, sideloaded Roku channel, native iOS app with offline downloads, and native Android TV / Google TV app. No Android phone/tablet app, no Apple TV, no Fire TV, no Samsung/LG/Vizio TV app. Plex and Jellyfin support 10-20+ platforms. The combination of iOS (phones + iPads) and Android TV (living-room big screen) covers the two highest-leverage surfaces outside of Roku, but an Android phone app and additional TV platforms remain open gaps.

### 2. No Remote Access Out of the Box
Plex's cloud relay provides zero-config remote streaming (though now paywalled for TV apps). MediaManager requires manual reverse proxy setup (nginx, Caddy, etc.) for access outside the LAN. This is a barrier for less technical users and for watching media away from home.

### 3. Single-Household Scale
The architecture assumes a single household with one admin managing a physical collection. There's no concept of shared servers, friend access, or federated libraries -- features that Plex and Jellyfin users rely on.

### 4. No DVR
Live TV streaming is supported via HDHomeRun tuners ([#41](https://github.com/jeffbstewart/MediaManager/issues/41) Done) and security cameras via go2rtc ([#27](https://github.com/jeffbstewart/MediaManager/issues/27) Done). However, DVR recording is not yet implemented -- competitors offer scheduled recording, time-shifting, and commercial skip.

### 5. Manual Transcoding Pipeline (Video Only)
Plex and Jellyfin transcode video on-the-fly at playback time, adapting quality to the client's bandwidth and capabilities. MediaManager pre-transcodes video to a single MP4 format in the background. This means for video:
- New content isn't playable until the transcode queue reaches it
- Storage is doubled (source + ForBrowser copy)
- No adaptive bitrate streaming

Note: this applies only to video. Audio uses on-the-fly FFmpeg transcode with an LRU byte cache (20-40x realtime encoding is fast enough to serve the first byte immediately), so the "wait for the queue" problem does not exist for music.

### 6. Limited Ecosystem Integration
No Trakt.tv sync, no Sonarr/Radarr integration, no OpenSubtitles plugin, no DLNA server, no Subsonic-API surface on top of the music catalogue (which would bring third-party mobile apps for free and match Navidrome's biggest leverage point). The self-hosted media community expects these integrations.

### 7. Music Limited to the Web Client
The persistent bottom-bar player, queue panel, Start Radio, and Discover page are web-only. Roku, iOS, and Android TV don't yet surface music. Navidrome's Subsonic-API compatibility means its users get mobile apps for free via the third-party client ecosystem; MediaManager will either need to build its own music surfaces on each client or add a Subsonic-API shim.

---

## Key Differentiating Features to Improve Positioning

### High Impact, High Effort

| Feature | Why It Matters | Competitive Effect | Tracked In |
|---------|---------------|-------------------|------------|
| **Android phone/tablet app** | iOS and Android TV apps shipped; Android phones/tablets remain unserved. Most households have a mix of iOS and Android devices | Completes mobile coverage | -- |
| **On-the-fly video transcoding** (or adaptive bitrate) | Eliminates the "wait for transcode queue" delay and reduces storage (audio already does this) | Matches Plex/Jellyfin's core streaming experience | -- |
| **Additional TV platform apps** (Fire TV, Apple TV) | Roku and Android TV / Google TV both shipped; Fire TV and Apple TV remain | Addresses remaining client-app coverage gap | -- |
| **Music on Roku / iOS / Android TV** | Music is web-only today. Navidrome gets mobile for free via Subsonic; MediaManager needs either native music surfaces on each client or a Subsonic-API shim that lets third-party clients consume the library directly | Closes the biggest remaining gap vs. dedicated music servers | -- |

### Medium Impact, Low Effort (Quick Wins)

| Feature | Why It Matters | Competitive Effect | Tracked In |
|---------|---------------|-------------------|------------|
| **Roku subtitle toggle** | User control over an existing feature | Matches Plex/Jellyfin UX expectations | [#40](https://github.com/jeffbstewart/MediaManager/issues/40) |
| **Shareable wish lists** | Gift-giving workflow for birthdays/holidays, now spanning books, music, and video | No competitor offers this | [#43](https://github.com/jeffbstewart/MediaManager/issues/43) |
| **Roku quick actions** (hide, star) | Power-user title management from the couch | Quality-of-life improvement | [#38](https://github.com/jeffbstewart/MediaManager/issues/38) |
| **Roku wish list view** | Browse and manage wishes from the TV | Completes the wish list experience on Roku | [#37](https://github.com/jeffbstewart/MediaManager/issues/37) |
| **Books & music on Roku / iOS / Android TV** | Book and music catalogs currently web-only; TV and phone surfaces would close the coverage gap | Extends the unique unified-catalogue strength to every surface | -- |
| **M6 Personnel enrichment for music** | Album-level credit already ships; per-track performer credits and band-lineup-over-time against MusicBrainz's `recording-rels` / `artist-rels` are specified but not implemented | Adds "who played drums on this?" depth that no other self-hosted music server offers | -- |

### Strategic Differentiators (Unique to MediaManager)

These features lean into what competitors *don't* do, rather than chasing parity:

| Feature | Why It Matters | Tracked In |
|---------|---------------|------------|
| **Physical-to-digital gap analysis** | "You own 400 Blu-rays but only 250 are ripped" -- surface the backlog with priority recommendations. Already partially implemented via the Transcode Backlog page. The same shape applies to "You own 200 CDs but only 120 have rips on disk" now that music is in the catalogue. | -- |
| **Family wish list with seasonal events** | The wish list + admin fulfillment flow maps perfectly to birthdays and holidays. Season lifecycle tracking ([#25](https://github.com/jeffbstewart/MediaManager/issues/25) Done) adds structured multi-season management. Lean into this -- "What does Dad want for his birthday? Check the wish list." No media server does this, and the wish list now spans movies, TV, books, *and* albums. | [#43](https://github.com/jeffbstewart/MediaManager/issues/43) |
| **Disc lending tracker** | "Who has my copy of Inception?" -- physical media lending is a real problem for collectors. No competitor addresses it. | -- |
| **Live content** (cameras + TV tuner) | Consolidates home AV into one app -- security cameras and live TV alongside the media library. No single competitor covers all three. | [#27](https://github.com/jeffbstewart/MediaManager/issues/27) Done, [#41](https://github.com/jeffbstewart/MediaManager/issues/41) Done |
| **Library-bound Start Radio** | Unlike streaming services that generate radio from a universal catalogue, MediaManager's radio is bound to what you own. No dead ends. No cloud dependency. Works even when Last.fm API access lapses, via cached similarity data. | -- |
| **Cross-media library recommendations** | `RecommendationAgent` runs nightly; Discover surfaces unowned artists scored by the overlap of owned-artist voters. Same machinery can extend to "authors you might like" and "collections worth completing." | -- |

---

## Positioning Recommendation

MediaManager should **not** try to be a Plex/Jellyfin replacement. It cannot win on client app breadth, ecosystem integrations, or transcoding sophistication -- those projects have years of development and large communities.

Instead, MediaManager should position as: **"The complete physical media management system -- from purchase to playback, across every format you own."**

The target user is a physical media collector (DVD/Blu-ray/4K UHD, **books, and CDs/vinyl**) who:
- Wants to catalog what they own (barcodes, metadata, purchase history) across formats in one app
- Wants to play, read, or listen to their collection without swapping discs or hunting for files (NAS + transcoding for video; in-browser EPUB/PDF reader for books; on-the-fly FFmpeg audio with a persistent bottom-bar player for music)
- Wants household members to browse and request new titles across video, books, and albums (one unified wish list)
- Values ownership and self-hosting over streaming subscriptions
- Wants insurance documentation and collection value tracking
- Wants radio and discovery bound to their own library (no cloud dead-ends, no algorithmic rabbit holes pushing content they can't own)

The competitive moat is the **integration between physical ownership tracking, digital playback, books, and music** -- a workflow that otherwise requires duct-taping five or more products together (CLZ Movies + CLZ Books + Plex + Calibre-Web + Navidrome + Seerr, for example). Plex's 2025-2026 price increases and remote streaming paywalls strengthen the video side of this positioning; the books launch extends the same "unified physical catalogue" value proposition into a category where CLZ charges a separate subscription and the self-hosted options (Calibre-Web, Kavita) only handle digital files; the music launch does the same for CDs, where no self-hosted music server catalogues physical ownership at all and Navidrome's Instant Mix is the closest analog to Start Radio but has no awareness of what you physically own on disc.

### Priority Roadmap

**Completed:**
1. ~~Mobile barcode scanning~~ ([#42](https://github.com/jeffbstewart/MediaManager/issues/42) Done)
2. ~~Collection reporting suite~~ ([#44](https://github.com/jeffbstewart/MediaManager/issues/44)--[#46](https://github.com/jeffbstewart/MediaManager/issues/46) Done)
3. ~~Roku personalization + search + auto-play~~ ([#29](https://github.com/jeffbstewart/MediaManager/issues/29), [#31](https://github.com/jeffbstewart/MediaManager/issues/31), [#33](https://github.com/jeffbstewart/MediaManager/issues/33) Done)
4. ~~Automated replacement value estimation~~ ([#51](https://github.com/jeffbstewart/MediaManager/issues/51) Done)
5. ~~Roku detail enhancements + similar titles + actor pages + seek thumbnails~~ ([#34](https://github.com/jeffbstewart/MediaManager/issues/34)--[#36](https://github.com/jeffbstewart/MediaManager/issues/36), [#39](https://github.com/jeffbstewart/MediaManager/issues/39) Done)
6. ~~Native iOS app~~ ([#1](https://github.com/jeffbstewart/MediaManager/issues/1)) -- Full catalog browsing, custom video player with subtitles/skip segments/thumbnail scrubbing, wish list, cameras, live TV, admin views
7. ~~Security hardening~~ -- Buddy API device-token pairing ([#7](https://github.com/jeffbstewart/MediaManager/issues/7) Done), lease limits + probe validation + audit logging ([#13](https://github.com/jeffbstewart/MediaManager/issues/13) Done), pairing API rate limiting ([#53](https://github.com/jeffbstewart/MediaManager/issues/53) Done)
8. ~~Mobile offline playback~~ ([#5](https://github.com/jeffbstewart/MediaManager/issues/5) Done) -- Low-fidelity mobile transcodes with offline downloads on iOS
9. ~~Thumbnail/subtitle relocation~~ ([#56](https://github.com/jeffbstewart/MediaManager/issues/56) Done) -- Thumbnails and subtitles stored alongside source files
10. ~~Transcode buddy reliability~~ -- Staging heartbeat, bundle-level heartbeat, lease invalidation detection with mid-flight abort, retry with reconnection for completion reports
11. ~~UTC timestamp handling~~ -- Server sends UTC ISO-8601 timestamps; clients format in local timezone
12. ~~Chapter seeking in video player~~ ([#55](https://github.com/jeffbstewart/MediaManager/issues/55) Done)
13. ~~Custom progress bar for web video player~~ ([#57](https://github.com/jeffbstewart/MediaManager/issues/57) Done)
14. ~~H2_FILE_PASSWORD rotation support~~ ([#24](https://github.com/jeffbstewart/MediaManager/issues/24) Done)
15. ~~Books support~~ -- Physical books (ISBN barcode scan + Open Library), .epub/.pdf NAS scanner, in-browser paginated EPUB reader and native PDF viewing, author and series browse, wishlist-for-books with "Missing Volumes" fill-gap, Unmatched Books admin queue, Wikipedia author headshot cache, image-proxy hardening for OL covers
16. ~~Native Android TV / Google TV app~~ -- Jetpack Compose for TV with multi-user, home carousels, ExoPlayer playback (subtitles, skip segments, auto-next), wish list, cameras, live TV, search, TLS
17. ~~External skip-detection import~~ ([#54](https://github.com/jeffbstewart/MediaManager/issues/54) Done) -- `.skip.json` sidecar files from external skip-detection agents are imported during the NAS scan
18. ~~Music support M1-M5~~ -- Album/track/artist schema, CD barcode via MusicBrainz release-group dedup (+ UPCitemdb fallback), FFprobe-based NAS audio scanner reading MBIDs and embedded artwork, artist + album browse destinations with MusicBrainz + Wikipedia biography enrichment, discography art via CAA release-group endpoint, album wishlists, persistent bottom-bar audio player with volume control and "Up Next" drop-up queue panel, on-the-fly FFmpeg transcode with LRU byte cache (no pre-transcode queue for audio)
19. ~~Music M7 Start Radio~~ -- Last.fm similar-artist + owned-library intersection, round-robin interleave across artists, tail-pad from seed-artist deeper cuts, skip-weighting within 30s, graceful-degrade on cached similarity data when the API key is removed, UI button on album detail, radio-chip in bottom-bar player
20. ~~Music M8 Library Recommendations~~ -- Nightly `RecommendationAgent` pass scores unowned artists weighted by owned-album voters, Discover page + "Artists you might like" home carousel with voter explanations, per-user dismissal persistence
21. ~~Accent-insensitive universal search~~ -- Diacritic normalization both directions; search result types expanded to include tracks, artists, authors, books, and albums as first-class rows alongside movies/TV/actors; artist biography matches surface directly

**Next priorities:**
1. **Music client coverage** -- Roku / iOS / Android TV surfaces for the music catalogue, or a Subsonic-API shim so third-party clients (Symfonium, play:Sub, DSub) can consume MediaManager the way they consume Navidrome. This is now the biggest remaining competitive gap, since music on the web is complete but dedicated music servers win on mobile by leveraging the Subsonic client ecosystem.
2. **Music M6 personnel enrichment** -- Background pull of MusicBrainz `artist-rels` / `recording-rels` into `artist_membership` and `recording_credit`. Unlocks "who played drums on *L.A. Woman*" and band-lineup-over-time UI. Designed; not implemented.
3. **Roku polish** ([#40](https://github.com/jeffbstewart/MediaManager/issues/40) subtitle toggle, [#38](https://github.com/jeffbstewart/MediaManager/issues/38) quick actions, [#37](https://github.com/jeffbstewart/MediaManager/issues/37) wish list view) -- Completes the Roku experience for parity with Jellyfin's Roku app on core UX
4. **Shareable wish lists** ([#43](https://github.com/jeffbstewart/MediaManager/issues/43)) -- Leans into the unique "physical media household" positioning, now spanning video, books, and music
5. **Security hardening** ([#12](https://github.com/jeffbstewart/MediaManager/issues/12) Docker secrets, [#59](https://github.com/jeffbstewart/MediaManager/issues/59) JWT bind cookie) -- Necessary for confidence in internet-exposed deployment
6. **Transcode buddy Windows service installer** ([#58](https://github.com/jeffbstewart/MediaManager/issues/58)) -- Removes the last rough edge of the buddy deployment story on Windows hosts
7. **Transcode buddy console-beep fix** ([#52](https://github.com/jeffbstewart/MediaManager/issues/52)) -- Long-running annoyance on Windows hosts; javaw.exe does not stop child ffmpeg/whisper BEL characters from reaching the console
8. **Amazon affiliate shop button on wish list** ([#26](https://github.com/jeffbstewart/MediaManager/issues/26)) -- Small, high-leverage wish-list polish still open

---

## Sources

- [Best Home Media Server 2026: Jellyfin vs Plex vs Emby](https://selfhosthero.com/jellyfin-vs-plex-vs-emby-home-media-server-comparison/)
- [Plex vs Jellyfin vs Emby on a NAS](https://nascompares.com/guide/plex-vs-jellyfin-vs-emby-on-a-nas-which-is-best-for-your-synology-qnap-truenas-or-other-nas/)
- [Plex Plans & Pricing](https://www.plex.tv/plans/)
- [Plex Important 2025 Updates (pricing + remote streaming changes)](https://www.plex.tv/blog/important-2025-plex-updates/)
- [Plex Lifetime Pass Price Hike (PCWorld)](https://www.pcworld.com/article/2642674/plexs-lifetime-subscription-plan-is-getting-a-massive-price-hike.html)
- [Plex Raises Price for Plex Pass (MacRumors)](https://www.macrumors.com/2025/03/19/plex-price-increase/)
- [Plex Paywalls Remote Streaming on TVs (TechNewsVision)](https://www.technewsvision.com/plex-remote-streaming-tv-paywall-2026/)
- [Plex Paywall Enforced (9to5Mac)](https://9to5mac.com/2025/11/27/plex-paywall-for-remote-streaming-now-being-enforced/)
- [Plex Ends Free Remote Streaming (Android Central)](https://www.androidcentral.com/streaming-tv/plex-just-killed-a-beloved-feature-for-remote-users)
- [Plex Remote Streaming Paywall (How-To Geek)](https://www.howtogeek.com/plex-is-now-enforcing-remote-play-restrictions-on-tvs/)
- [Plex vs Jellyfin 2026 (HomeDock)](https://www.homedock.cloud/blog/plex-vs-jellyfin-2026/)
- [Jellyfin 10.11.0 Release Notes](https://jellyfin.org/posts/jellyfin-release-10.11.0/)
- [State of the Fin 2026-01-06](https://jellyfin.org/posts/state-of-the-fin-2026-01-06/)
- [Jellyfin Roku App Releases (GitHub)](https://github.com/jellyfin/jellyfin-roku/releases)
- [Jellyfin Roku App Updates (XDA)](https://www.xda-developers.com/jellyfins-roku-app-just-got-good-enough-to-ditch-plex/)
- [Jellyfin Roku v3.1.6 Release (How-To Geek)](https://www.howtogeek.com/jellyfin-the-open-source-media-server-just-got-better-on-roku-tvs/)
- [Emby Premiere Pricing](https://emby.media/premiere.html)
- [Emby Premiere Lifetime $99 Sale (Neowin)](https://www.neowin.net/news/emby-premiere-lifetime-discounted-to-99-likely-the-last-time-price-will-be-so-low/)
- [Emby Releases (GitHub)](https://github.com/MediaBrowser/Emby.Releases/releases)
- [CLZ Movies](https://clz.com/movies)
- [CLZ Movies Pricing](https://app.clz.com/movies/pricing)
- [CLZ Movies What's New](https://clz.com/movies/mobile/whatsnew)
- [Seerr (unified Overseerr + Jellyseerr)](https://seerr.dev/)
- [Seerr Release Announcement](https://docs.seerr.dev/blog/seerr-release)
- [Seerr Migration Guide](https://docs.seerr.dev/migration-guide)
- [Overseerr and Jellyseerr Merge (ElfHosted)](https://store.elfhosted.com/blog/2026/02/17/overseerr-and-jellyseerr-merge-into-seerr/)
- [Ombi](https://github.com/Ombi-app/Ombi)
- [Mydia (Official Site)](https://www.mydia.dev/)
- [Mydia (GitHub)](https://github.com/getmydia/mydia)
- [Mydia (XDA-Developers)](https://www.xda-developers.com/new-self-hosted-media-platform-exactly-what-home-lab-was-missing/)
- [DVD Profiler (Wikipedia)](https://en.wikipedia.org/wiki/DVD_Profiler)
- [Kodi vs Plex vs Jellyfin vs Emby Showdown](https://diymediaserver.com/post/kodi-vs-plex-vs-jellyfin-vs-emby-the-ultimate-media-playback-software-showdown/)
- [Top 7 Plex Alternatives 2026](https://www.rapidseedbox.com/blog/plex-alternatives)
- [Plex Pro Week '25: API Unlocked](https://www.plex.tv/blog/plex-pro-week-25-api-unlocked/)
- [Plex Pricing 2026 (CheckThat.ai)](https://checkthat.ai/brands/plex/pricing)
- [Jellyfin Server Releases (GitHub)](https://github.com/jellyfin/jellyfin/releases)
- [Jellyfin Desktop Releases (GitHub)](https://github.com/jellyfin/jellyfin-desktop/releases)
- [Emby Server 4.9.3.0 / 4.10.0.5 Beta (VideoHelp)](https://www.videohelp.com/software/Emby)
- [CLZ Movies Mobile What's New](https://clz.com/movies/mobile/whatsnew)
- [CLZ Movies Web What's New](https://clz.com/movies/web/whatsnew)
- [Mydia 0.9.0 Documentation](https://docs.mydia.dev/0.9.0/)
- [Jellyfin vs Plex 2026 (JellyWatch)](https://jellywatch.app/blog/jellyfin-vs-plex-self-hosted-media-server-2026)
- [Jellyfin 10.11.7 Critical Security Update (JellyWatch)](https://jellywatch.app/blog/jellyfin-10-11-7-critical-security-update-april-2026)
- [Plex Remote Playback Requirements (Support)](https://support.plex.tv/articles/requirements-for-remote-playback-of-personal-media/)
- [Plex Custom Metadata Providers (How-To Geek)](https://www.howtogeek.com/plex-is-overhauling-custom-metadata-providers/)
- [Emby Premiere Pricing 2026 (JellyWatch)](https://jellywatch.app/blog/emby-premiere-pricing-cost-2026)
- [Seerr v3.1.0 Security Release](https://docs.seerr.dev/blog/seerr-3-1-0-security-release)
- [Mydia TrueNAS App](https://apps.truenas.com/catalog/mydia/)
- [DVD Profiler Server Migration (Invelos)](https://www.invelos.com/)
- [Jellyfin 10.11.8 Release (TechSpot)](https://www.techspot.com/downloads/7165-jellyfin.html)
- [Jellyfin Release Notes (Releasebot)](https://releasebot.io/updates/jellyfin)
- [Emby Server 4.10.0.10 Beta (VideoHelp)](https://www.videohelp.com/software/Emby)
- [CLZ Movies What's New 2026](https://clz.com/movies/whatsnew)
- [Plex Media Server 1.43.1.10611 (VideoHelp)](https://www.videohelp.com/software/Plex)
- [Plex Remote Playback Requirements (Support)](https://support.plex.tv/articles/requirements-for-remote-playback-of-personal-media/)
- [Self-Hosted Ebook Servers 2026 (AlternativeTo)](https://alternativeto.net/software/calibre/?platform=self-hosted)
- [Calibre Alternatives 2026 (Technical Wall)](https://technicalwall.com/alternatives/best-calibre-alternatives/)
- [Best Self-Hosted Ebook Servers 2026 (selfhosting.sh)](https://selfhosting.sh/best/ebooks-reading/)
- [Open Library Covers API](https://openlibrary.org/dev/docs/api/covers)
- [Navidrome 0.60 Release Notes (LinuxToday)](https://www.linuxtoday.com/blog/navidrome-0-60-music-server-streamer-unleashes-webassembly-plugin-system/)
- [Navidrome 0.60 WebAssembly Plugin System (AlternativeTo)](https://alternativeto.net/news/2026/2/navidrome-0-60-adds-webassembly-based-plugin-system-new-instant-mix-feature-and-more/)
- [Navidrome GitHub Releases](https://github.com/navidrome/navidrome/releases)
- [Best Self-Hosted Music Streaming Servers 2026 (selfhosting.sh)](https://selfhosting.sh/best/music-streaming/)
- [MusicBrainz Web Service Documentation](https://musicbrainz.org/doc/MusicBrainz_API)
- [Cover Art Archive API](https://musicbrainz.org/doc/Cover_Art_Archive/API)
- [Last.fm Artist.getSimilar API](https://www.last.fm/api/show/artist.getSimilar)
- [Plex Music and Photos Remote Streaming Exemption (Support)](https://support.plex.tv/articles/requirements-for-remote-playback-of-personal-media/)
- [Plex Pass $249.99 Lifetime Price Confirmed (How-To Geek)](https://www.howtogeek.com/plex-pass-2025-price-hike/)
- [Seerr Migration Guide 2026 (JellyWatch)](https://jellywatch.app/blog/jellyseerr-overseerr-to-seerr-migration-guide-2026)
- [Emby Server 4.10.0.10 Beta Notes (Free-codecs)](https://www.free-codecs.com/emby_download.htm)
- [XDA Self-Hosted Music Server Coverage](https://www.xda-developers.com/stopped-using-spotify-built-own-self-hosted-music-server/)

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
