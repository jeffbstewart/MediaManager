import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto, unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { create, fromBinary } from '@bufbuild/protobuf';
import {
  AcquisitionStatus,
  MediaType,
  WishLifecycleStage,
} from '../../src/app/proto-gen/common_pb';
import {
  AddBookWishRequestSchema,
  AddWishRequestSchema,
  AddWishResponseSchema,
  RemoveAlbumWishRequestSchema,
  RemoveBookWishRequestSchema,
  TmdbSearchResponseSchema,
  TranscodeWishStatus,
  WishListResponseSchema,
  WishStatus,
  type WishListResponse,
} from '../../src/app/proto-gen/wishlist_pb';

// Wish-list view tests.
//
// /wishlist (WishListComponent) is one long page with five sections
// (Search TMDB, Media Wishes, Transcode Wishes, Book Wishes, Album
// Wishes). Each section reads from the same WishListService.ListWishes
// payload and writes through WishListService mutation RPCs.
//
// The default fixture is intentionally small — one of each row type —
// to keep other specs (e.g. collections) fast. Tests here that need
// richer shapes (TV row with a season, non-dismissible cancel-button
// row, transcode pending status, album wishes) override
// ListWishes inline.
//
// No image fields cross the wire any more — the catalog adapter
// constructs same-origin URLs from IDs:
//   /tmdb-poster/{type}/{tmdb_id}/w185      media wishes
//   /posters/w185/{title_id}                 transcode wishes
//   /proxy/ol/olid/{ol_work_id}/M            book wishes
//   /proxy/caa/release-group/{rgid}/large    album wishes

const WS = '/mediamanager.WishListService';
const LIST_WISHES = `**${WS}/ListWishes`;

/** Build a typed WishListResponse for inline route overrides. */
function wishlistPayload(over: Partial<WishListResponse> = {}): WishListResponse {
  return create(WishListResponseSchema, {
    wishes: [],
    transcodeWishes: [],
    bookWishes: [],
    albumWishes: [],
    hasAnyMediaWish: true,
    ...over,
  });
}

test.describe('wishlist — default fixture', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
  });

  test('renders the four section headers (Album hidden when empty)', async ({ page }) => {
    const headers = page.locator('app-wishlist h3');
    await expect(headers).toContainText(['Search TMDB', 'Media Wishes', 'Transcode Wishes', 'Book Wishes']);
    // album_wishes is [] in the default fixture → no "Album Wishes" header.
    await expect(page.locator('app-wishlist h3', { hasText: /^Album Wishes$/ })).toHaveCount(0);
  });

  test('media wish card renders poster, title, year, type "Film"', async ({ page }) => {
    const card = page.locator('app-wishlist .media-card').first();
    // Poster hits the same-origin /tmdb-poster servlet keyed on
    // (media_type, tmdb_id). Server resolves via TmdbPosterPathResolver.
    await expect(card.locator('img.media-poster'))
      .toHaveAttribute('src', /\/tmdb-poster\/MOVIE\/245891\/w185$/);
    await expect(card.locator('.media-title')).toContainText('John Wick');
    await expect(card.locator('.media-meta')).toContainText('2014');
    await expect(card.locator('.media-meta')).toContainText('Film');
    await expect(card.locator('.season-label')).toHaveCount(0);
    // Lifecycle pill is client-rendered from the proto enum (i18n).
    await expect(card.locator('.lifecycle-badge')).toContainText('Wished for');
  });

  test('transcode wish renders ready status + remove button', async ({ page }) => {
    const row = page.locator('app-wishlist .transcode-list .transcode-row').first();
    await expect(row.locator('.transcode-title')).toContainText('The Matrix');
    await expect(row.locator('.transcode-status')).toContainText('Ready to watch');
    await expect(row.locator('.transcode-status')).toHaveClass(/ready/);
    await expect(row.locator('button[aria-label="Remove from wish list"]')).toBeVisible();
  });

  test('book wish renders cover, title, author', async ({ page }) => {
    const row = page.locator('app-wishlist .transcode-list').nth(1).locator('.transcode-row');
    // Cover is now /proxy/ol/olid/{ol_work_id}/M, server-fetched.
    await expect(row.locator('img.transcode-poster'))
      .toHaveAttribute('src', /\/proxy\/ol\/olid\/OL12345W\/M$/);
    await expect(row.locator('.transcode-title')).toContainText('Dune Messiah');
    await expect(row.locator('.book-wish-author')).toContainText('by Frank Herbert');
  });

  test('book wish remove fires RemoveBookWish with ol_work_id', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/RemoveBookWish`),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .transcode-list').nth(1)
      .locator('button[aria-label="Remove from wish list"]').click();
    const got = await req;
    expect(fromBinary(
      RemoveBookWishRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    ).olWorkId).toBe('OL12345W');
  });

  test('transcode wish remove fires RemoveTranscodeWish keyed on title_id', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/RemoveTranscodeWish`),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .transcode-list').first()
      .locator('button[aria-label="Remove from wish list"]').click();
    await req;
  });

  test('dismissible media wish without title_id fires DismissWish and stays on page', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/DismissWish`),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .media-card').first()
      .locator('button.dismiss-btn').click();
    await req;
    await expect(page).toHaveURL(/\/wishlist$/);
  });
});

// -------- TMDB search → add flow --------

test.describe('wishlist — TMDB search', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    // Two results: one not yet wished, one already wished — exercise both
    // visible affordances (Add button vs ♥ Wished label).
    await page.route(`**${WS}/SearchTmdb`, r =>
      fulfillProto(r, TmdbSearchResponseSchema, create(TmdbSearchResponseSchema, {
        results: [
          {
            tmdbId: 603, title: 'The Matrix', mediaType: MediaType.MOVIE,
            releaseYear: 1999, popularity: 80.5, owned: false, wished: false,
          },
          {
            tmdbId: 1399, title: 'Game of Thrones', mediaType: MediaType.TV,
            releaseYear: 2011, popularity: 90.0, owned: false, wished: true,
          },
        ],
      })));
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
  });

  test('Search button fires SearchTmdb', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/SearchTmdb`),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist input.search-input').fill('matrix');
    await page.locator('app-wishlist button.search-btn').click();
    await req;
  });

  test('Enter key in the search input also fires search', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/SearchTmdb`), { timeout: 3_000 },
    );
    const input = page.locator('app-wishlist input.search-input');
    await input.fill('matrix');
    await input.press('Enter');
    await req;
  });

  test('search results render placeholder thumb + title + meta + Add or Wished label', async ({ page }) => {
    await page.locator('app-wishlist input.search-input').fill('matrix');
    await page.locator('app-wishlist button.search-btn').click();
    const cards = page.locator('app-wishlist .search-card');
    await expect(cards).toHaveCount(2);

    // No URLs cross the wire any more — search dropdowns render a
    // first-letter placeholder until the user adds the wish (after
    // which /tmdb-poster/{type}/{tmdb_id} resolves via the wish row).
    await expect(cards.first().locator('.search-poster-placeholder')).toContainText('T');
    await expect(cards.first().locator('.search-title')).toContainText('The Matrix');
    await expect(cards.first().locator('.search-meta')).toContainText('1999');
    await expect(cards.first().locator('.search-meta')).toContainText('Film');
    await expect(cards.first().locator('button.add-wish-btn')).toBeVisible();

    await expect(cards.nth(1).locator('.search-poster-placeholder')).toContainText('G');
    await expect(cards.nth(1).locator('.search-meta')).toContainText('TV');
    await expect(cards.nth(1).locator('button.add-wish-btn')).toHaveCount(0);
    await expect(cards.nth(1).locator('.wished-label')).toBeVisible();
  });

  test('Add fires AddWish with the right payload (no interstitial when has_any_media_wish=true)', async ({ page }) => {
    await page.locator('app-wishlist input.search-input').fill('matrix');
    await page.locator('app-wishlist button.search-btn').click();
    await page.waitForSelector('app-wishlist .search-card');

    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/AddWish`),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .search-card').first()
      .locator('button.add-wish-btn').click();
    const got = await req;
    const decoded = fromBinary(
      AddWishRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    );
    expect(decoded.tmdbId).toBe(603);
    expect(decoded.title).toBe('The Matrix');
    expect(decoded.mediaType).toBe(MediaType.MOVIE);
    expect(decoded.releaseYear).toBe(1999);
    expect(decoded.popularity).toBe(80.5);

    await expect(page.locator('app-wishlist .search-card').first()
      .locator('.wished-label')).toBeVisible();
  });
});

// -------- First-wish interstitial --------

test.describe('wishlist — first-wish interstitial', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route(LIST_WISHES, r =>
      fulfillProto(r, WishListResponseSchema, wishlistPayload({ hasAnyMediaWish: false })));
    await page.route(`**${WS}/SearchTmdb`, r =>
      fulfillProto(r, TmdbSearchResponseSchema, create(TmdbSearchResponseSchema, {
        results: [{
          tmdbId: 1, title: 'Citizen Kane', mediaType: MediaType.MOVIE,
          releaseYear: 1941, popularity: 50, owned: false, wished: false,
        }],
      })));
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
    await page.locator('app-wishlist input.search-input').fill('kane');
    await page.locator('app-wishlist button.search-btn').click();
    await page.waitForSelector('app-wishlist .search-card');
  });

  test('first Add shows the interstitial, Cancel suppresses the wish', async ({ page }) => {
    let addFired = false;
    page.on('request', r => {
      if (r.url().endsWith(`${WS}/AddWish`)) addFired = true;
    });
    await page.locator('app-wishlist button.add-wish-btn').click();
    await expect(page.locator('app-wishlist .modal-overlay')).toBeVisible();
    await page.locator('app-wishlist .modal-content button.cancel-btn').click();
    await expect(page.locator('app-wishlist .modal-overlay')).toHaveCount(0);
    expect(addFired).toBe(false);
  });

  test('first Add shows the interstitial, Confirm fires the wish', async ({ page }) => {
    await page.locator('app-wishlist button.add-wish-btn').click();
    await expect(page.locator('app-wishlist .modal-overlay')).toBeVisible();

    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/AddWish`),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .modal-content button.confirm-btn').click();
    await req;
    await expect(page.locator('app-wishlist .modal-overlay')).toHaveCount(0);
  });
});

// -------- TV-with-season + non-dismissible media wish + pending transcode --------

test.describe('wishlist — varied row shapes', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route(LIST_WISHES, r =>
      fulfillProto(r, WishListResponseSchema, wishlistPayload({
        wishes: [
          // Non-dismissible: ORDERED stage, no title_id, dismissible=false.
          {
            id: 7n, tmdbId: 1399, title: 'Game of Thrones',
            mediaType: MediaType.TV,
            releaseYear: 2011, seasonNumber: 4,
            status: WishStatus.ACTIVE,
            voteCount: 1, userVoted: true,
            acquisitionStatus: AcquisitionStatus.ORDERED,
            lifecycleStage: WishLifecycleStage.ORDERED,
            dismissible: false,
          },
          // Dismissible WITH title_id → click navigates to /title/100.
          {
            id: 8n, tmdbId: 603, title: 'The Matrix',
            mediaType: MediaType.MOVIE,
            releaseYear: 1999,
            status: WishStatus.FULFILLED,
            voteCount: 1, userVoted: true,
            acquisitionStatus: AcquisitionStatus.OWNED,
            lifecycleStage: WishLifecycleStage.READY_TO_WATCH,
            titleId: 100n,
            dismissible: true,
          },
        ],
        transcodeWishes: [{
          id: 5n, titleId: 200n, titleName: 'Inception',
          status: TranscodeWishStatus.PENDING,
        }],
        albumWishes: [
          {
            id: 11n, releaseGroupId: 'rg-single', title: 'Kind of Blue',
            primaryArtist: 'Miles Davis', year: 1959, isCompilation: false,
          },
          {
            id: 12n, releaseGroupId: 'rg-comp', title: "Now That's What I Call Music!",
            year: 2024, isCompilation: true,
          },
        ],
      })));
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
  });

  test('TV media wish shows "TV" type + "Season N" label', async ({ page }) => {
    const card = page.locator('app-wishlist .media-card').first();
    await expect(card.locator('.media-title')).toContainText('Game of Thrones');
    await expect(card.locator('.media-meta')).toContainText('TV');
    await expect(card.locator('.season-label')).toContainText('Season 4');
    await expect(card.locator('.lifecycle-badge')).toContainText('Ordered');
  });

  test('non-dismissible media wish shows the corner × cancel button', async ({ page }) => {
    const card = page.locator('app-wishlist .media-card').first();
    await expect(card.locator('button.cancel-btn')).toBeVisible();
    await expect(card.locator('button.dismiss-btn')).toHaveCount(0);

    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/CancelWish`),
      { timeout: 3_000 },
    );
    await card.locator('button.cancel-btn').click();
    await req;
  });

  test('dismissible media wish with title_id navigates to the title detail', async ({ page }) => {
    await page.locator('app-wishlist .media-card').nth(1)
      .locator('button.dismiss-btn').click();
    await expect(page).toHaveURL(/\/title\/100$/);
  });

  test('transcode wish with status=pending renders the pending label', async ({ page }) => {
    const row = page.locator('app-wishlist .transcode-list').first().locator('.transcode-row');
    await expect(row.locator('.transcode-status')).toContainText('On NAS, pending desktop');
    await expect(row.locator('.transcode-status')).not.toHaveClass(/ready/);
  });

  test('album wish — single artist subtitle is "Artist · Year"', async ({ page }) => {
    const sections = page.locator('app-wishlist .transcode-list');
    const albumRows = sections.last().locator('.transcode-row');
    const single = albumRows.first();
    await expect(single.locator('.transcode-title')).toContainText('Kind of Blue');
    await expect(single.locator('.book-wish-author')).toContainText('Miles Davis');
    await expect(single.locator('.book-wish-author')).toContainText('1959');
  });

  test('album wish — compilation subtitle is "Compilation · Year"', async ({ page }) => {
    const albumRows = page.locator('app-wishlist .transcode-list').last().locator('.transcode-row');
    const comp = albumRows.nth(1);
    await expect(comp.locator('.transcode-title')).toContainText('Now That');
    await expect(comp.locator('.book-wish-author')).toContainText('Compilation');
    await expect(comp.locator('.book-wish-author')).toContainText('2024');
  });

  test('album wish remove fires RemoveAlbumWish with release_group_id', async ({ page }) => {
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${WS}/RemoveAlbumWish`),
      { timeout: 3_000 },
    );
    await page.locator('app-wishlist .transcode-list').last()
      .locator('.transcode-row').first()
      .locator('button[aria-label="Remove from wish list"]').click();
    const got = await req;
    expect(fromBinary(
      RemoveAlbumWishRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    ).releaseGroupId).toBe('rg-single');
  });
});

// -------- Empty states --------

test.describe('wishlist — empty states', () => {
  test('all-empty wishlist shows the media empty hint + transcode empty hint', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.route(LIST_WISHES, r =>
      fulfillProto(r, WishListResponseSchema, wishlistPayload({ hasAnyMediaWish: false })));
    await page.goto('/wishlist');
    await page.waitForSelector('app-wishlist .content-page');
    await expect(page.locator('app-wishlist').getByText(/No media wishes yet/)).toBeVisible();
    await expect(page.locator('app-wishlist').getByText(/No transcode wishes yet/)).toBeVisible();
    await expect(page.locator('app-wishlist h3', { hasText: /^Book Wishes$/ })).toHaveCount(0);
    await expect(page.locator('app-wishlist h3', { hasText: /^Album Wishes$/ })).toHaveCount(0);
  });
});
