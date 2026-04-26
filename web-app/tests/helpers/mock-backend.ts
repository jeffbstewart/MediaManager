import type { Page, Route } from '@playwright/test';
import { loadFixture } from './load-fixture';
import { fulfillProto, unframeGrpcWebRequest } from './proto-fixture';
import { fromBinary } from '@bufbuild/protobuf';
import { TitleDetailSchema, TitleIdRequestSchema } from '../../src/app/proto-gen/common_pb';
import { titleMovie100 } from '../fixtures-typed/title-100-movie.fixture';
import { titleTv200 } from '../fixtures-typed/title-200-tv.fixture';
import { titleAlbum301 } from '../fixtures-typed/title-301-album.fixture';

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
  const featuresFixture = opts.features === 'admin'
    ? 'catalog/features.admin.json'
    : 'catalog/features.viewer.json';
  const homeFixture = opts.homeFeed === 'empty'
    ? 'catalog/home.empty.json'
    : 'catalog/home.populated.json';

  await page.route('**/api/v2/catalog/features', (r: Route) =>
    r.fulfill({ json: loadFixture(featuresFixture) })
  );
  await page.route('**/api/v2/catalog/home', (r: Route) =>
    r.fulfill({ json: loadFixture(homeFixture) })
  );

  // --- Browse grids (Tier 3) ---
  // /api/v2/catalog/titles is parameterised by `media_type`; dispatch
  // to the right fixture so movies / tv / books / personal each get
  // a sensible populated response.
  await page.route('**/api/v2/catalog/titles*', (r: Route) => {
    const url = new URL(r.request().url());
    const mt = url.searchParams.get('media_type');
    const fixture = mt === 'TV' ? 'catalog/titles.tv.json'
                  : mt === 'BOOK' ? 'catalog/titles.books.json'
                  : 'catalog/titles.movies.json';
    return r.fulfill({ json: loadFixture(fixture) });
  });

  await page.route('**/api/v2/catalog/collections', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/collections.list.json') })
  );
  await page.route('**/api/v2/catalog/tags', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/tags.list.json') })
  );
  await page.route('**/api/v2/catalog/artists*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/artists.list.json') })
  );
  await page.route('**/api/v2/catalog/family-videos*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/family-videos.list.json') })
  );
  await page.route('**/api/v2/playlists', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/playlists.list.json') })
  );
  await page.route('**/api/v2/playlists/mine', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/playlists.list.json') })
  );

  // --- Detail pages (Tier 4) ---
  // gRPC-JSON unary: POST /<service>/<rpc> with a JSON body. Single
  // entry covers all unary catalog RPCs we route here; we dispatch on
  // the rpc name. Web → server contract is the proto, so we serve a
  // proto-typed fixture and round-trip it through TitleDetail.fromJSON
  // /toJSON in fulfillProto so any fixture drift fails the test setup
  // loudly instead of silently shipping the wrong shape.
  await page.route('**/mediamanager.CatalogService/*', async (r: Route) => {
    const url = new URL(r.request().url());
    const rpc = url.pathname.split('/').pop();
    if (rpc === 'GetTitleDetail') {
      // Body is gRPC-Web framed: 5-byte header + binary protobuf
      // TitleIdRequest. Strip the frame, decode, dispatch on id;
      // ids without a typed fixture fall through to the legacy REST
      // handler below.
      const payload = unframeGrpcWebRequest(r.request().postDataBuffer());
      const req = fromBinary(TitleIdRequestSchema, payload);
      if (req.titleId === 100n) {
        return fulfillProto(r, TitleDetailSchema, titleMovie100);
      }
      if (req.titleId === 200n) {
        return fulfillProto(r, TitleDetailSchema, titleTv200);
      }
      if (req.titleId === 301n) {
        return fulfillProto(r, TitleDetailSchema, titleAlbum301);
      }
    }
    return r.fallback();
  });

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

  await page.route('**/api/v2/catalog/actor/*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/actor.json') })
  );
  await page.route('**/api/v2/catalog/authors/*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/author.json') })
  );
  await page.route('**/api/v2/catalog/artists/*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/artist.json') })
  );
  await page.route('**/api/v2/catalog/series/*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/series.json') })
  );
  await page.route('**/api/v2/catalog/tags/*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/tag-detail.json') })
  );
  await page.route('**/api/v2/catalog/collections/*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/collection-detail.json') })
  );

  // --- Wishlist / Search / Discover / Profile (Tier 5) ---
  // Title-detail & collection-detail consult the wishlist to drive
  // their "add to wishlist" state; the wishlist page itself renders
  // the same response.
  await page.route('**/api/v2/wishlist', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/wishlist.json') })
  );

  // Search lives under /api/v2/search (NOT /api/v2/catalog/search,
  // despite the name). Use a regex so the route only matches the
  // bare endpoint with optional query-string, leaving sub-paths
  // (/search/presets, /search/tracks) to their own handlers below.
  await page.route(/\/api\/v2\/search(\?|$)/, (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/search.results.json') })
  );
  await page.route('**/api/v2/search/tracks*', (r: Route) =>
    r.fulfill({ json: { tracks: [] } })
  );
  // Advanced-search presets for the search dialog variant.
  await page.route('**/api/v2/search/presets*', (r: Route) =>
    r.fulfill({ json: { presets: [] } })
  );

  await page.route('**/api/v2/recommendations/artists*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/recommendations.json') })
  );

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
  // Playlist endpoints registered after `/api/v2/playlists` and
  // `/api/v2/playlists/mine` (Tier 3) so trailing-segment paths win
  // via Playwright's LIFO route matching.
  await page.route('**/api/v2/playlists/smart/*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/smart-playlist.json') })
  );
  await page.route('**/api/v2/playlists/*', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/playlist.json') })
  );

  await page.route('**/api/v2/catalog/cameras', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/cameras.json') })
  );
  await page.route('**/api/v2/catalog/live-tv/channels', (r: Route) =>
    r.fulfill({ json: loadFixture('catalog/tv-channels.json') })
  );
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
  // to discover EPUB-vs-PDF. Return EPUB content-type so the page
  // mounts the EPUB branch (we don't care if epub.js can't render
  // the empty body — the page chrome is what axe is auditing).
  await page.route('**/api/v2/reading-progress/*', (r: Route) =>
    r.fulfill({ json: { media_item_id: 0, cfi: null, percent: 0, updated_at: null } })
  );
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
