import type { Page, Route } from '@playwright/test';
import { loadFixture } from './load-fixture';
import { fulfillProto, unframeGrpcWebRequest } from './proto-fixture';
import { clone, create, fromBinary } from '@bufbuild/protobuf';
import {
  ActorDetailSchema,
  ArtistDetailSchema,
  AuthorDetailSchema,
  CollectionDetailSchema,
  EmptySchema,
  MediaType,
  ReadingProgressSchema,
  TitleDetailSchema,
  TitleIdRequestSchema,
} from '../../src/app/proto-gen/common_pb';
import {
  ArtistIdRequestSchema,
  ArtistListResponseSchema,
  ArtistRecommendationsResponseSchema,
  AuthorIdRequestSchema,
  ListArtistsRequestSchema,
} from '../../src/app/proto-gen/artist_pb';
import {
  AddTracksToPlaylistResponseSchema,
  LibraryShuffleResponseSchema,
  ListPlaylistsResponseSchema,
  ListSmartPlaylistsResponseSchema,
  PlaylistDetailSchema,
  PlaylistSummarySchema,
  ListPlaylistsRequestSchema,
  SmartPlaylistDetailSchema,
} from '../../src/app/proto-gen/playlist_pb';
import {
  AdvancedSearchPresetsResponseSchema,
  CollectionIdRequestSchema,
  CollectionListResponseSchema,
  FeaturesSchema,
  HomeFeedResponseSchema,
  ListTitlesRequestSchema,
  MintPublicArtTokenResponseSchema,
  SearchResponseSchema,
  SearchTracksResponseSchema,
  TagListResponseSchema,
  TitlePageResponseSchema,
} from '../../src/app/proto-gen/catalog_pb';
import { titleMovie100 } from '../fixtures-typed/title-100-movie.fixture';
import { titleTv200 } from '../fixtures-typed/title-200-tv.fixture';
import { titleBook300 } from '../fixtures-typed/title-300-book.fixture';
import { titleAlbum301 } from '../fixtures-typed/title-301-album.fixture';
import { tagsList } from '../fixtures-typed/tags-list.fixture';
import { actor6384 } from '../fixtures-typed/actor-6384.fixture';
import { booksPage, moviesPage, tvPage } from '../fixtures-typed/titles-list.fixture';
import { collectionDetail2344, collectionsList } from '../fixtures-typed/collections.fixture';
import { featuresAdmin, featuresViewer, homeFeedEmpty, homeFeedPopulated } from '../fixtures-typed/home-feed.fixture';
import { artistMilesDavis, artistsListFixture, authorFrankHerbert } from '../fixtures-typed/artist-author.fixture';
import { artistRecommendations } from '../fixtures-typed/recommendations.fixture';
import {
  advancedSearchPresetsFixture,
  searchResultsFixture,
  searchTracksEmptyFixture,
} from '../fixtures-typed/search.fixture';
import { camerasList, tvChannelsList } from '../fixtures-typed/live.fixture';
import { CameraListResponseSchema, TvChannelListResponseSchema } from '../../src/app/proto-gen/live_pb';
import { tagDetailFixture } from '../fixtures-typed/tag-detail.fixture';
import { TagDetailSchema } from '../../src/app/proto-gen/common_pb';
import { wishListFixture } from '../fixtures-typed/wishlist.fixture';
import {
  AddWishResponseSchema,
  TmdbSearchResponseSchema,
  WishListResponseSchema,
  WishlistSeriesGapsResponseSchema,
} from '../../src/app/proto-gen/wishlist_pb';
import {
  playlistDetailRoadTrip,
  playlistsList,
  smartPlaylistDetail,
  smartPlaylistsList,
} from '../fixtures-typed/playlists.fixture';

/**
 * Backend-mock options. Each key toggles one endpoint's default response.
 * Individual tests can override any endpoint with a per-test `page.route()`
 * registered AFTER `mockBackend()` — Playwright applies route handlers in
 * LIFO order, so later registrations win.
 */
export interface MockBackendOptions {
  /** What `/api/v2/auth/discover` returns — controls login vs setup branching. */
  discover?: 'normal' | 'setup-required';
  /** Legal-compliance status returned by `/api/v2/legal/status`. */
  legalStatus?: 'compliant' | 'pending';
  /** Feature-flags fixture served from `/api/v2/catalog/features`. */
  features?: 'viewer' | 'admin';
  /** Home-feed fixture served from `/api/v2/catalog/home`. */
  homeFeed?: 'populated' | 'empty';
}

/**
 * Install the default set of `page.route()` handlers so every Tier 1 page
 * can render without a real backend. Helpers that come online in later
 * tiers (catalog endpoints, admin endpoints, image stubs) plug in here
 * with no call-site changes in the tests.
 *
 * Must be called BEFORE `page.goto(...)` — Playwright's route handlers
 * only intercept requests issued after they're registered.
 */
export async function mockBackend(page: Page, opts: MockBackendOptions = {}): Promise<void> {
  const discoverFixture = opts.discover === 'setup-required'
    ? 'auth/discover.setup-required.json'
    : 'auth/discover.normal.json';
  const legalFixture = opts.legalStatus === 'pending'
    ? 'legal/status.pending.json'
    : 'legal/status.compliant.json';

  // --- Auth ---
  await page.route('**/api/v2/auth/discover', (r: Route) =>
    r.fulfill({ json: loadFixture(discoverFixture) })
  );

  await page.route('**/api/v2/auth/login', (r: Route) =>
    r.fulfill({ json: loadFixture('auth/login-response.json') })
  );

  // Silent refresh: 401 until the test explicitly grants a session via
  // loginAs(). Login page uses this during ngOnInit to skip the form if
  // a cookie is already live.
  await page.route('**/api/v2/auth/refresh', (r: Route) =>
    r.fulfill({ status: 401, json: { error: 'no refresh cookie' } })
  );

  await page.route('**/api/v2/auth/logout', (r: Route) =>
    r.fulfill({ status: 204 })
  );

  // Setup endpoint: shape matches LoginResponse.
  await page.route('**/api/v2/auth/setup', (r: Route) =>
    r.fulfill({ json: loadFixture('auth/login-response.json') })
  );

  // --- Legal ---
  await page.route('**/api/v2/legal/status', (r: Route) =>
    r.fulfill({ json: loadFixture(legalFixture) })
  );

  await page.route('**/api/v2/legal/agree', (r: Route) =>
    r.fulfill({ status: 204 })
  );

  // --- Profile (change-password) ---
  await page.route('**/api/v2/profile/change-password', (r: Route) =>
    r.fulfill({ json: { ok: true } })
  );

  // --- Catalog ---
  // /api/v2/catalog/features and /api/v2/catalog/home are no longer
  // hit — getFeatures() and getHomeFeed() go through the gRPC
  // GetFeatures and HomeFeed RPCs (dispatched in the gRPC route block
  // below). Per-test gRPC fixtures pick up `opts.features` /
  // `opts.homeFeed` via the closure variables here.
  //
  // The home fixture's nested `features` is paired with the standalone
  // featuresFixture so the home page's onload-publish to FeatureService
  // matches what the shell's getFeatures() already set. Otherwise admin
  // flags would silently flip back to viewer when the home page
  // resolved.
  const featuresFixture = opts.features === 'admin' ? featuresAdmin : featuresViewer;
  const homeFixture = (() => {
    const base = opts.homeFeed === 'empty' ? homeFeedEmpty : homeFeedPopulated;
    if (opts.features !== 'admin') return base;
    const variant = clone(HomeFeedResponseSchema, base);
    variant.features = clone(FeaturesSchema, featuresAdmin);
    return variant;
  })();

  // --- Browse grids (Tier 3) ---
  // /api/v2/catalog/titles + /api/v2/catalog/collections are no longer
  // hit — getTitles() and getCollections() go through gRPC ListTitles
  // and ListCollections respectively, dispatched in the gRPC route
  // block below.
  // /api/v2/catalog/tags (list) is no longer hit — the SPA's getTags()
  // calls ListTags via gRPC, dispatched in the gRPC route block below.
  // /api/v2/catalog/artists (list) ditto — listArtists() goes through
  // ArtistService.ListArtists.
  await page.route('**/api/v2/catalog/family-videos*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/family-videos.list.json') })
  );
  // /api/v2/playlists, /mine, /:id, /smart/:key are no longer hit —
  // listPlaylists / getPlaylist / getSmartPlaylist call PlaylistService
  // RPCs (dispatched in the gRPC route block above).

  // --- gRPC dispatch (Tier 4) ---
  // One handler map per service. Each entry is `RpcName: handler(r)`
  // returning a Playwright fulfill — fixture lookups and proto round-
  // tripping (toBinary/fromBinary) happen inside the handler. No-op
  // mutations share a `noopEmpty` returner; reads share a generic
  // fixture-returner. fulfillProto itself round-trips through
  // toJson/fromJson + toBinary so any fixture-shape drift fails the
  // test setup loudly.
  //
  // To dispatch on the request body (e.g. GetTitleDetail by id),
  // decode the proto inside the handler. The maps stay flat — no
  // chained ifs, just one entry per RPC.
  type RpcHandler = (r: Route) => unknown;
  const noopEmpty: RpcHandler = r => fulfillProto(r, EmptySchema, create(EmptySchema));
  function readBody<S extends Parameters<typeof fromBinary>[0]>(r: Route, schema: S): ReturnType<typeof fromBinary<S>> {
    return fromBinary(schema, unframeGrpcWebRequest(r.request().postDataBuffer()));
  }

  // ---- mediamanager.CatalogService ----
  const titleDetailById = new Map<bigint, typeof titleMovie100>([
    [100n, titleMovie100],
    [200n, titleTv200],
    [300n, titleBook300],
    [301n, titleAlbum301],
  ]);
  const catalogHandlers: Record<string, RpcHandler> = {
    GetTitleDetail: r => {
      const req = readBody(r, TitleIdRequestSchema);
      const fixture = titleDetailById.get(req.titleId);
      if (fixture) return fulfillProto(r, TitleDetailSchema, fixture);
      // Unknown id → fall through to the legacy REST handler below.
      return r.fallback();
    },
    HomeFeed:        r => fulfillProto(r, HomeFeedResponseSchema, homeFixture),
    GetFeatures:     r => fulfillProto(r, FeaturesSchema, featuresFixture),
    GetActorDetail:  r => fulfillProto(r, ActorDetailSchema, actor6384),
    ListTags:        r => fulfillProto(r, TagListResponseSchema, tagsList),
    ListTagsForTrack:
      // Default: track has no tags. Per-test overrides set richer values.
      r => fulfillProto(r, TagListResponseSchema, create(TagListResponseSchema)),
    ListCollections: r => fulfillProto(r, CollectionListResponseSchema, collectionsList),
    GetCollectionDetail:
      // One detail fixture covers every tmdb_collection_id today; per-
      // test overrides dispatch on req.tmdbCollectionId when needed.
      r => fulfillProto(r, CollectionDetailSchema, collectionDetail2344),
    ListTitles: r => {
      const req = readBody(r, ListTitlesRequestSchema);
      const fixture = req.type === MediaType.TV   ? tvPage
                    : req.type === MediaType.BOOK ? booksPage
                    : moviesPage;
      return fulfillProto(r, TitlePageResponseSchema, fixture);
    },
    // No-op mutations (return Empty). The SPA's caller awaits success;
    // tests that want to capture the request register their own
    // override before mockBackend's catch-all.
    SetTitleTags:           noopEmpty,
    SetTrackTags:           noopEmpty,
    SetTrackMusicTags:      noopEmpty,
    AddTagToTitle:          noopEmpty,
    RemoveTagFromTitle:     noopEmpty,
    AddTagToTrack:          noopEmpty,
    RemoveTagFromTrack:     noopEmpty,
    SetFavorite:            noopEmpty,
    SetHidden:              noopEmpty,
    DismissMissingSeason:   noopEmpty,
    DismissContinueWatching: noopEmpty,
    GetTagDetail:           r => fulfillProto(r, TagDetailSchema, tagDetailFixture),
    MintPublicArtToken:
      // Audio player's MediaSession integration calls this to mint a
      // signed token for OS lock-screen art. Tests don't assert on
      // it; return a stable fake so the audio-player promise resolves.
      r => fulfillProto(r, MintPublicArtTokenResponseSchema, create(MintPublicArtTokenResponseSchema, {
        token: 'test-token',
        ttl: { nanos: 12n * 60n * 60n * 1_000_000_000n },
      })),
    Search:                 r => fulfillProto(r, SearchResponseSchema, searchResultsFixture),
    ListAdvancedSearchPresets:
      r => fulfillProto(r, AdvancedSearchPresetsResponseSchema, advancedSearchPresetsFixture),
    SearchTracks:
      r => fulfillProto(r, SearchTracksResponseSchema, searchTracksEmptyFixture),
  };

  // ---- mediamanager.PlaylistService ----
  const playlistHandlers: Record<string, RpcHandler> = {
    ListPlaylists:        r => fulfillProto(r, ListPlaylistsResponseSchema, playlistsList),
    ListSmartPlaylists:   r => fulfillProto(r, ListSmartPlaylistsResponseSchema, smartPlaylistsList),
    GetPlaylist:          r => fulfillProto(r, PlaylistDetailSchema, playlistDetailRoadTrip),
    GetSmartPlaylist:     r => fulfillProto(r, SmartPlaylistDetailSchema, smartPlaylistDetail),
    LibraryShuffle:       r => fulfillProto(r, LibraryShuffleResponseSchema, create(LibraryShuffleResponseSchema)),
    CreatePlaylist:
      r => fulfillProto(r, PlaylistSummarySchema, create(PlaylistSummarySchema, {
        id: 99n, name: 'Created',
      })),
    DuplicatePlaylist:
      r => fulfillProto(r, PlaylistSummarySchema, create(PlaylistSummarySchema, {
        id: 42n, name: 'Road Trip (copy)',
      })),
    AddTracksToPlaylist:
      r => fulfillProto(r, AddTracksToPlaylistResponseSchema, create(AddTracksToPlaylistResponseSchema, {
        added: 1, playlistTrackIds: [42n],
      })),
    SetPlaylistPrivacy:      noopEmpty,
    ReportPlaylistProgress:  noopEmpty,
    ClearPlaylistProgress:   noopEmpty,
    RecordTrackCompletion:   noopEmpty,
    RenamePlaylist:          noopEmpty,
    DeletePlaylist:          noopEmpty,
    RemoveTrackFromPlaylist: noopEmpty,
    ReorderPlaylist:         noopEmpty,
    SetPlaylistHero:         noopEmpty,
  };

  // ---- mediamanager.ArtistService ----
  const artistHandlers: Record<string, RpcHandler> = {
    ListArtists:     r => fulfillProto(r, ArtistListResponseSchema, artistsListFixture),
    GetArtistDetail: r => fulfillProto(r, ArtistDetailSchema, artistMilesDavis),
    GetAuthorDetail: r => fulfillProto(r, AuthorDetailSchema, authorFrankHerbert),
    ListArtistRecommendations:
      r => fulfillProto(r, ArtistRecommendationsResponseSchema, artistRecommendations),
    DismissArtistRecommendation: noopEmpty,
    RefreshArtistRecommendations: noopEmpty,
  };

  // Each gRPC service mounts at its own URL prefix. The dispatcher
  // peels the rpc name off the path tail and delegates to the
  // service's handler map; unknown rpcs fall through to r.fallback()
  // so a per-test page.route() registered earlier (LIFO) can still win.
  function mountService(prefix: string, handlers: Record<string, RpcHandler>) {
    return page.route(`**/${prefix}/*`, async (r: Route) => {
      const rpc = new URL(r.request().url()).pathname.split('/').pop() ?? '';
      const handler = handlers[rpc];
      if (handler) return handler(r);
      return r.fallback();
    });
  }
  // ---- mediamanager.PlaybackService ----
  // Reading-progress is the only RPC migrated so far; the rest of the
  // playback surface (video / audio progress, chapters, skip segments)
  // still rides REST. Defaults: GetReadingProgress returns zeroed
  // progress (no resume point), Report/Clear return Empty.
  const playbackHandlers: Record<string, RpcHandler> = {
    GetReadingProgress:    r => fulfillProto(r, ReadingProgressSchema, create(ReadingProgressSchema)),
    ReportReadingProgress: noopEmpty,
    ClearReadingProgress:  noopEmpty,
    // Video progress: only ClearProgress is migrated yet — the player
    // itself still reads /playback-progress/:id via HTTP. Once the
    // player migrates, GetProgress / ReportProgress join here.
    ClearProgress:         noopEmpty,
  };

  // ---- mediamanager.WishListService ----
  const wishListHandlers: Record<string, RpcHandler> = {
    ListWishes:         r => fulfillProto(r, WishListResponseSchema, wishListFixture),
    SearchTmdb:         r => fulfillProto(r, TmdbSearchResponseSchema, create(TmdbSearchResponseSchema)),
    AddWish:            r => fulfillProto(r, AddWishResponseSchema, create(AddWishResponseSchema, { id: 99n })),
    AddBookWish:        r => fulfillProto(r, AddWishResponseSchema, create(AddWishResponseSchema, { id: 99n })),
    AddAlbumWish:       r => fulfillProto(r, AddWishResponseSchema, create(AddWishResponseSchema, { id: 99n })),
    CancelWish:         noopEmpty,
    DismissWish:        noopEmpty,
    VoteOnWish:         noopEmpty,
    RemoveBookWish:     noopEmpty,
    RemoveAlbumWish:    noopEmpty,
    AddTranscodeWish:   r => fulfillProto(r, AddWishResponseSchema, create(AddWishResponseSchema, { id: 99n })),
    RemoveTranscodeWish: noopEmpty,
    WishlistSeriesGaps:
      r => fulfillProto(r, WishlistSeriesGapsResponseSchema, create(WishlistSeriesGapsResponseSchema)),
  };

  // ---- mediamanager.LiveService ----
  // Camera + TV-channel browse.
  const liveHandlers: Record<string, RpcHandler> = {
    ListCameras:    r => fulfillProto(r, CameraListResponseSchema, camerasList),
    ListTvChannels: r => fulfillProto(r, TvChannelListResponseSchema, tvChannelsList),
  };

  await mountService('mediamanager.CatalogService', catalogHandlers);
  await mountService('mediamanager.PlaylistService', playlistHandlers);
  await mountService('mediamanager.ArtistService', artistHandlers);
  await mountService('mediamanager.PlaybackService', playbackHandlers);
  await mountService('mediamanager.LiveService', liveHandlers);
  await mountService('mediamanager.WishListService', wishListHandlers);

  // Legacy REST: /api/v2/catalog/titles/:id — dispatch by id to the
  // right media-type fixture. 100 movie, 200 tv, 300 book, 301 album.
  // Anything else falls back to the movie fixture. Kept in place
  // during the proto migration; a title id only exits this path
  // once a typed fixture lands above.
  await page.route('**/api/v2/catalog/titles/*', (r: Route) => {
    const url = new URL(r.request().url());
    const id = url.pathname.split('/').pop();
    const fixture = id === '200' ? 'catalog/title.tv.json'
                  : id === '300' ? 'catalog/title.book.json'
                  : id === '301' ? 'catalog/title.album.json'
                  : 'catalog/title.movie.json';
    return r.fulfill({ json: loadFixture(fixture) });
  });

  // /api/v2/catalog/actor/:id, /authors/:id, /artists/:id are no
  // longer hit — getActorDetail / getAuthorDetail / getArtistDetail
  // go through GetActorDetail / GetAuthorDetail / GetArtistDetail
  // gRPC RPCs (dispatched above).
  await page.route('**/api/v2/catalog/series/*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/series.json') })
  );
  // /api/v2/catalog/tags/:id is no longer hit — getTagDetail() goes
  // through CatalogService.GetTagDetail (dispatched in the gRPC
  // route block above). The legacy `**/tags/*` glob also caught
  // /tags/:id/titles/:titleId admin add/remove paths; those still
  // ride REST and are handled by per-test page.route() overrides.
  // /api/v2/catalog/collections/:id is no longer hit — getCollectionDetail()
  // calls GetCollectionDetail via gRPC (dispatched above).

  // --- Wishlist / Search / Discover / Profile (Tier 5) ---
  // /api/v2/wishlist + /wishlist/* mutation paths are no longer hit —
  // the SPA's wishlist methods go through WishListService gRPC RPCs
  // (dispatched in the gRPC route block above).

  // /api/v2/search, /search/presets, /search/tracks are no longer hit —
  // search() / listAdvancedSearchPresets() / searchTracks() go through
  // CatalogService.{Search,ListAdvancedSearchPresets,SearchTracks}
  // (dispatched in the gRPC route block above).

  // /api/v2/recommendations/* are no longer hit — discover-feed reads
  // ArtistService.{ListArtistRecommendations,DismissArtistRecommendation,
  // RefreshArtistRecommendations} (dispatched in the gRPC route block
  // above).

  await page.route('**/api/v2/profile', (r: Route) =>
    r.fulfill({ json: loadFixture('profile/profile.json') })
  );
  await page.route('**/api/v2/profile/sessions', (r: Route) =>
    r.fulfill({ json: loadFixture('profile/sessions.json') })
  );
  await page.route('**/api/v2/profile/hidden-titles', (r: Route) =>
    r.fulfill({ json: loadFixture('profile/hidden-titles.json') })
  );

  // --- Media + Live (Tier 6) ---
  // /api/v2/playlists/* trailing-segment routes were retired with the
  // PlaylistService gRPC migration (see the gRPC dispatch block above).
  // /api/v2/catalog/cameras is no longer hit — getCameras() goes
  // through LiveService.ListCameras (dispatched in the gRPC route
  // block above).
  // /api/v2/catalog/live-tv/channels is no longer hit — getTvChannels()
  // goes through LiveService.ListTvChannels (dispatched in the gRPC
  // route block above).
  // Live-TV-player launches an HLS stream against
  //   /api/v2/live-tv/stream/:channelId/playlist.m3u8
  // The page renders before the stream is needed; if HLS.js fires
  // a request, return a minimal valid manifest so it doesn't error
  // loudly. The page's a11y is what we care about, not the stream.
  await page.route('**/api/v2/live-tv/stream/**', (r: Route) =>
    r.fulfill({
      status: 200,
      headers: { 'Content-Type': 'application/vnd.apple.mpegurl' },
      body: '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:0\n#EXT-X-ENDLIST\n',
    })
  );

  // --- Standalone surfaces (Tier 7) ---
  // /play/:id reads chapter JSON, thumbnail VTT, subs VTT, progress,
  // and next-episode. Return minimal/empty shapes so the player
  // settles into its idle state and renders the controls.
  await page.route('**/stream/*/chapters.json', (r: Route) =>
    r.fulfill({ json: { chapters: [] } })
  );
  await page.route('**/stream/*/thumbs.vtt', (r: Route) =>
    r.fulfill({ status: 200, headers: { 'Content-Type': 'text/vtt' }, body: 'WEBVTT\n\n' })
  );
  await page.route('**/stream/*/subs.vtt', (r: Route) =>
    r.fulfill({ status: 404 })
  );
  await page.route('**/stream/*/next-episode', (r: Route) =>
    r.fulfill({ status: 404 })
  );
  await page.route('**/playback-progress/*', (r: Route) =>
    r.fulfill({ json: { position: 0, duration: 0 } })
  );
  // Video binary itself — return empty 200 so <video> doesn't 404.
  await page.route('**/stream/*', (r: Route) =>
    r.fulfill({ status: 200, headers: { 'Content-Type': 'video/mp4' }, body: '' })
  );

  // /reader/:id needs reading progress + a HEAD probe on /ebook/:id
  // to discover EPUB-vs-PDF. Reading-progress now flows through
  // PlaybackService.GetReadingProgress (dispatched in the gRPC route
  // block above). Return EPUB content-type so the page mounts the
  // EPUB branch (we don't care if epub.js can't render the empty
  // body — the page chrome is what axe is auditing).
  await page.route('**/ebook/*', (r: Route) =>
    r.fulfill({
      status: 200,
      headers: { 'Content-Type': 'application/epub+zip' },
      body: '',
    })
  );

  // /pair?code=X queries pair info.
  await page.route('**/api/v2/pair/info*', (r: Route) =>
    r.fulfill({ json: { status: 'pending', display_name: 'iOS App' } })
  );

  // --- Admin (Tier A: list + simple CRUD pages) ---
  // Each handler matches the canonical admin endpoint and returns a
  // populated fixture so the table-shaped pages render rows. Detail
  // sub-paths (e.g. .../sessions, .../audit) ride a generic 200/[]
  // catch-all where applicable.
  await page.route('**/api/v2/admin/users', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/users.json') })
  );
  await page.route('**/api/v2/admin/users/*/sessions', (r: Route) =>
    r.fulfill({ json: { sessions: [] } })
  );

  await page.route('**/api/v2/admin/tags', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/tags.json') })
  );

  await page.route('**/api/v2/admin/family-members', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/family-members.json') })
  );

  await page.route('**/api/v2/admin/purchase-wishes', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/purchase-wishes.json') })
  );

  await page.route('**/api/v2/admin/valuations*', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/valuations.json') })
  );

  await page.route('**/api/v2/admin/reports*', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/reports.json') })
  );

  await page.route('**/api/v2/admin/report/info', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/report-info.json') })
  );
  await page.route('**/api/v2/admin/report/status', (r: Route) =>
    r.fulfill({ json: { status: 'idle', phase: '', current: 0, total: 0, error: null } })
  );

  // --- Admin (Tier B: transcode pipeline + unmatched media) ---
  await page.route('**/api/v2/admin/transcode-status', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/transcode-status.json') })
  );
  await page.route('**/api/v2/admin/unmatched', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/transcode-unmatched.json') })
  );
  // /api/v2/admin/linked-transcodes accepts query params; match the
  // base path with optional querystring.
  await page.route('**/api/v2/admin/linked-transcodes*', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/transcode-linked.json') })
  );
  await page.route('**/api/v2/admin/transcode-backlog*', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/transcode-backlog.json') })
  );
  await page.route('**/api/v2/admin/unmatched-books', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/unmatched-books.json') })
  );
  await page.route('**/api/v2/admin/unmatched-audio/groups', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/unmatched-audio.json') })
  );

  // --- Admin (Tier C: heavy-form pages) ---
  await page.route('**/api/v2/admin/add-item/quota', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/add-item-quota.json') })
  );
  await page.route('**/api/v2/admin/add-item/recent*', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/add-item-recent.json') })
  );
  await page.route('**/api/v2/admin/add-item/search-tmdb*', (r: Route) =>
    r.fulfill({ json: { results: [] } })
  );

  await page.route('**/api/v2/admin/expand', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/expand.json') })
  );

  // /api/v2/admin/media-item/:id (the edit page) — return a populated
  // detail so the form fields are bound to real values.
  await page.route('**/api/v2/admin/media-item/*', (r: Route) => {
    const url = new URL(r.request().url());
    // Subpaths like .../seasons / .../purchase / .../media-type need
    // a 200 with empty/no-op shape — they're not what we audit.
    if (/\/media-item\/\d+\/(seasons|purchase|media-type|link-amazon)/.test(url.pathname)) {
      return r.fulfill({ json: { ok: true } });
    }
    return r.fulfill({ json: loadFixture('admin/media-item-detail.json') });
  });

  await page.route('**/api/v2/admin/amazon-orders*', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/amazon-orders.json') })
  );

  await page.route('**/api/v2/admin/settings', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/settings.json') })
  );

  // --- Admin (Tier D: config surfaces with embedded media) ---
  // /api/v2/admin/cameras returns the camera list AND the go2rtc
  // status badge; the test fixture reports "running".
  await page.route('**/api/v2/admin/cameras', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/cameras.json') })
  );
  // /api/v2/admin/live-tv (NOT /live-tv/settings) returns the
  // composite tuners + channels + settings payload.
  await page.route('**/api/v2/admin/live-tv', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/live-tv.json') })
  );
  await page.route('**/api/v2/admin/data-quality*', (r: Route) =>
    r.fulfill({ json: loadFixture('admin/data-quality.json') })
  );
  // Document-ownership lookup; empty by default so the page renders
  // its idle/scan state instead of a populated capture.
  await page.route('**/api/v2/admin/ownership/lookup*', (r: Route) =>
    r.fulfill({ json: { item: null, photos: [] } })
  );
  await page.route('**/api/v2/admin/ownership/search*', (r: Route) =>
    r.fulfill({ json: { items: [] } })
  );
}
