# Localized metadata

Status: **not scheduled.** A record of what localizing the catalog's
metadata would take, written 2026-11-23 while setting up iOS string
catalogs (#99, #105, #108). Tracked in GitHub Issues.

Translating the apps' own text (buttons, headings, messages) is the
smaller half. Most of what people read is metadata the server fetched:
titles, synopses, season and episode names, collection names, posters.
An app translated into Spanish would still show all of that in English.
This note covers the metadata half: where translations come from, and
what changes in the database, the server's caches and APIs, and the
clients' caches.

## What can be translated, and what can't

| Content | Source | Translatable? |
|---|---|---|
| Movie and TV titles, synopses, taglines | TMDB | Yes. Every TMDB call takes `language` (for example `es-MX`); `/movie/{id}/translations` and `/tv/{id}/translations` return every language in one call. A missing translation comes back empty, not in English. |
| Season and episode names and overviews | TMDB | Yes, through the same `language` parameter on the season and episode calls. (Confirm whether per-season and per-episode `/translations` endpoints exist before relying on one-call fetching.) |
| Collection names | TMDB | Yes (`language` on `/collection/{id}`). |
| Genre names | TMDB | Yes (`/genre/movie/list?language=…`). |
| Posters and backdrops | TMDB | Yes. Images are tagged with a language (`null` means no text on the image); `include_image_language=es,null` picks a localized poster. |
| People's names, character names | TMDB credits | No; credits aren't translated. Unchanged. |
| Books | Open Library | Mostly no; editions differ per language, and are different products. Unchanged. |
| Albums, tracks, artists | MusicBrainz | Mostly no; titles stay in their original language, with aliases at most. Unchanged. |
| Product names from UPC lookups | UPCitemdb | No (English). Unchanged; only used until TMDB matching. |
| Household content: tags, playlists, collections the household made, problem reports, family videos | Typed by people | No. Shown as entered, in every language. |

Content ratings are a **region** question, not a language one: the
catalog stores US certifications (`title.content_rating`), and the
per-user rating ceiling compares against them. Supporting another
region's ratings is a separate feature.

## Choosing a language

- The client says what it wants, per request, as a BCP 47 tag (`es-MX`).
  The server resolves each field through a fallback chain: `es-MX`, then
  `es`, then the stored default (today's English). A field is never
  blank because a translation is missing.
- Clients send their language in an `x-client-language` gRPC metadata
  header, defined once with an owner comment like the other owned
  strings (CLAUDE.md, "Owned strings"), and as a `lang` query parameter
  on image URLs, since browsers and AVPlayer can't add headers to image
  loads.
- Optionally, a user override (`app_user.metadata_language`, nullable,
  NULL meaning "the device's language").
- An `app_config` setting, `metadata_languages`, lists the languages the
  household wants fetched. Enrichment fetches only those, which bounds
  TMDB calls and storage; any other request falls back to the default.

## Database

Today's columns stay and keep meaning "the default language", so every
existing query keeps working and translations are purely additive. New
tables follow the ownership rule (CLAUDE.md, "Account deletion and data
ownership"): translations are household data, so they reference the
catalog row with `ON DELETE CASCADE` and carry no user id.

| New table | Columns | Replaces, per language |
|---|---|---|
| `title_translation` | `title_id` → `title(id)`, `language`, `name`, `description`, `tagline`, `fetched_at`; key (`title_id`, `language`) | `title.name`, `title.description` (TMDB taglines aren't stored today) |
| `title_season_translation` | `title_id`, `season_number`, `language`, `name`; key all but `name` | `title_season.name` |
| `episode_translation` | `episode_id` → `episode(id)`, `language`, `name`, `overview` | `episode.name` (overviews aren't stored today) |
| `tmdb_collection_translation` | `tmdb_collection_id`, `language`, `name` | `tmdb_collection.name`, and the copy in `title.tmdb_collection_name` |
| `genre_translation` | `genre_id` → `genre(id)`, `language`, `name` | `genre.name` |
| `title_image` | `title_id`, `kind` (POSTER or BACKDROP), `language` (NULL for no text), `tmdb_path`, `cache_id`, `tint` | `title.poster_path` / `poster_cache_id`, `backdrop_path` / `backdrop_cache_id` / `backdrop_tint` |

Notes:
- `language` is a short BCP 47 tag; a migration test should reject
  malformed tags.
- `sort_name` is derived from the English name (leading articles moved).
  Sorting another language needs a sort key per translation, or sorting
  at query time with a `java.text.Collator` for the request's language.
- Genre-based household **tags** were seeded from genre names (Bulk Tag
  Seeding). They're household content and stay as named;
  `genre_translation` only affects where genre names are shown.
- A versioned `SchemaUpdater` backfills translations for existing
  titles when `metadata_languages` is first set, and again when a
  language is added.

## Server code and caches

- **`TmdbService`:** a `language` argument on `getDetails`,
  `fetchCollection` and the season calls; a new
  `fetchTranslations(tmdbId)` (one call, every language); images through
  `/images` with `include_image_language`.
- **`TmdbEnrichmentAgent`** and the season, cast and collection updaters
  store translations for `metadata_languages`, with the existing backoff
  (`EnrichmentBackoff`) and a refresh age like `popularity_refreshed_at`.
- **One resolver:** for example `LocalizedCatalog.resolve(title,
  language)`, through which every gRPC and REST mapper reads translatable
  fields, so no mapper reads `title.name` directly. A lint or test should
  enforce that.
- **Request language:** read once in `AuthInterceptor` (gRPC) and
  `ArmeriaAuthDecorator` (HTTP), and passed in the request context like
  the caller's identity.
- **Images:** `/posters/{size}/{titleId}` and the backdrop route take
  `lang`. `PosterCacheService.cacheAndServe(title, size, language)`
  picks the `title_image` row (language match, else no-text, else
  default) and caches under that row's `cache_id` in
  `InternetImageStore`. The language is in the URL, so browser and
  iOS HTTP caches stay correct without a `Vary` header.
- **`SearchIndexService`:** index every stored language's names, so a
  search matches in either language, and return each title once. Results
  are shown in the request's language.
- **Short-lived caches:** the ~60-second response caches and the home
  feed must include the language in their keys.
- **Roku feed:** `/roku/feed.json` takes `lang` (the channel knows the
  device's locale) and serves resolved text.

## Client caches

- **iOS:**
  - Send `x-client-language` from `Locale.preferredLanguages`.
  - `ImageDiskCache` keys (`MMImageRef`) include the language.
  - Downloads store the title detail (`detail.pb`) in one language, so
    record the language with each download. After the phone's language
    changes, a download shows its stored language until it's refreshed
    online.
  - `StorageScope` is unaffected; it's per server and account, not per
    language.
- **Web (Angular):** send `navigator.language`; image URLs carry `lang`.
- **Android TV:** the same, from the system locale.

## Not covered

- Translating household content: it's shown as typed.
- Regional content ratings (above).
- Localized audio and subtitle tracks: those come from the media files,
  not metadata.
