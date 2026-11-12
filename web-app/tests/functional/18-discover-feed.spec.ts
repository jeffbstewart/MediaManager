import type { Page } from '@playwright/test';
import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { fromBinary } from '@bufbuild/protobuf';
import {
  DismissArtistRecommendationRequestSchema,
} from '../../src/app/proto-gen/artist_pb';
import { AddAlbumWishRequestSchema } from '../../src/app/proto-gen/wishlist_pb';
import { ListTitlesRequestSchema } from '../../src/app/proto-gen/catalog_pb';
import { MediaType } from '../../src/app/proto-gen/common_pb';
import { expectView } from '../helpers/expect-view';

// Discover feed (M8) tests. The page reads
// ArtistService.ListArtistRecommendations, renders one person card per
// recommended artist in a row, and exposes three actions per card / page:
//   - Wishlist (WishListService.AddAlbumWish)
//   - Dismiss  (ArtistService.DismissArtistRecommendation)
//   - Refresh  (ArtistService.RefreshArtistRecommendations, then re-load)
//
// The page is gated on has_music_radio. mockBackend's default viewer
// features fixture has it on, so no override needed unless we want
// to test the gated-off explainer state (out of scope here).

const AS = '/mediamanager.ArtistService';

test.describe('discover feed', () => {

  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    // mock-backend's AddAlbumWish handler default returns id=99.
    // Warm up FeatureService before mounting the discover component.
    // discover.ngOnInit reads features.hasMusicRadio() synchronously
    // and bails to the "needs Last.fm" empty state if the flag is
    // false. On a direct page.goto('/discover') the shell's async
    // getFeatures() race-loses to the discover mount, so we land on
    // /  first (features populate via shell + home), then in-app
    // navigate to /discover with FeatureService already hydrated.
    await page.goto('/');
    await page.waitForSelector('aside.side-nav a[href="/discover"]');
    await page.locator('aside.side-nav a[href="/discover"]').click();
    await page.waitForSelector('app-discover .artists-section app-person-card');
  });

  test('renders one person card per recommended artist in a row', async ({ page }) => {
    const row = page.locator('app-discover .artists-section app-media-row ul[aria-label="Artists You Might Like"]');
    const cards = row.locator('li app-person-card.rec-card');
    await expect(cards).toHaveCount(2);

    // No library artist id in the fixture, so the name is plain text.
    await expect(cards.first().locator('span.n')).toHaveText('Bill Evans');
    await expect(cards.first().locator('a')).toHaveCount(0);
    await expect(cards.first().locator('.m').first()).toContainText(
      'Start with Sunday at the Village Vanguard',
    );

    await expect(cards.nth(1).locator('.n')).toHaveText('John Coltrane');
    await expect(cards.nth(1).locator('.m').first()).toContainText(
      'Start with A Love Supreme',
    );
  });

  test('voter lines render with names from the fixture', async ({ page }) => {
    // Single voter → "because you have NAME"
    await expect(page.locator('app-discover .rec-card').first().locator('.m').last())
      .toContainText('because you have Miles Davis');
    // Three voters → comma-and formatting via voterLine()
    await expect(page.locator('app-discover .rec-card').nth(1).locator('.m').last())
      .toContainText('because you have Miles Davis, Bill Evans, and Thelonious Monk');
  });

  test('round cover image shows when representative_release_group_id is set', async ({ page }) => {
    // SPA constructs the cover URL same-origin from the rgid:
    // /proxy/caa/release-group/{rgid}/front-250.
    const cover = page.locator('app-discover .rec-card').first().locator('.person-img img');
    await expect(cover).toBeVisible();
    await expect(cover).toHaveAttribute('src', /\/proxy\/caa\/release-group\/rg-1\/front-250$/);
  });

  test('Wishlist pill fires AddAlbumWish and flips the button', async ({ page }) => {
    const card = page.locator('app-discover .rec-card').first();
    const wishBtn = card.locator('button.wish-btn');
    await expect(wishBtn).toContainText('Wishlist');

    const posted = page.waitForRequest(req =>
      req.url().endsWith('/mediamanager.WishListService/AddAlbumWish'),
    );
    await wishBtn.click();
    const req = await posted;
    const decoded = fromBinary(
      AddAlbumWishRequestSchema,
      unframeGrpcWebRequest(req.postDataBuffer()),
    );
    expect(decoded.releaseGroupId).toBe('rg-1');
    expect(decoded.title).toBe('Sunday at the Village Vanguard');
    expect(decoded.primaryArtist).toBe('Bill Evans');

    // Label flips to "Wishlisted" + the button becomes disabled so
    // double-click can't double-add.
    await expect(wishBtn).toContainText('Wishlisted');
    await expect(wishBtn).toBeDisabled();
  });

  test('Dismiss click fires DismissArtistRecommendation and removes the card', async ({ page }) => {
    const cards = page.locator('app-discover .rec-card');
    await expect(cards).toHaveCount(2);

    const posted = page.waitForRequest(req =>
      req.url().endsWith(`${AS}/DismissArtistRecommendation`),
    );
    // Dismiss the FIRST card (Bill Evans, mbid abc-1).
    await cards.first().getByRole('button', { name: 'Dismiss Bill Evans' }).click();
    const req = await posted;
    expect(fromBinary(
      DismissArtistRecommendationRequestSchema,
      unframeGrpcWebRequest(req.postDataBuffer()),
    ).suggestedArtistMbid).toBe('abc-1');

    // Local state filter drops the dismissed row; only Coltrane left.
    await expect(cards).toHaveCount(1);
    await expect(cards.first().locator('.n')).toHaveText('John Coltrane');
  });

  test('Refresh now fires RefreshArtistRecommendations and shows the in-flight state', async ({ page }) => {
    const refreshBtn = page.locator('app-discover button.refresh-btn');
    await expect(refreshBtn).toContainText('Refresh now');
    await expect(refreshBtn).not.toBeDisabled();

    const posted = page.waitForRequest(req =>
      req.url().endsWith(`${AS}/RefreshArtistRecommendations`),
    );
    await refreshBtn.click();
    await posted;

    // After the POST resolves but before the 4 s setTimeout fires,
    // the button stays disabled and shows the in-flight label. We
    // don't wait for the second-load — the test ends well before
    // the timer fires and the page tearsdown the timer with it.
    await expect(refreshBtn).toContainText('Refreshing');
    await expect(refreshBtn).toBeDisabled();
  });
});

// Discover frame (docs/plans/chain-live.md row 03): hero, promo and the
// New Seasons Out row, all from CatalogService.HomeFeed. The populated
// fixture's spotlight is Breaking Bad, which is also its only
// missing-seasons show (season 4).
test.describe('discover frame', () => {
  async function openDiscover(page: Page, homeFeed: 'populated' | 'empty'): Promise<void> {
    await mockBackend(page, { homeFeed });
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('aside.side-nav a[href="/discover"]');
    await page.locator('aside.side-nav a[href="/discover"]').click();
    await page.waitForSelector('app-discover .promo');
  }

  test('hero shows the spotlight show and names its missing seasons', async ({ page }) => {
    await openDiscover(page, 'populated');
    const hero = page.locator('app-discover .hero-section app-hero-banner');
    await expect(hero.locator('h2.hero-title')).toHaveText('Breaking Bad');
    await expect(hero.locator('.hero-kicker')).toHaveText('Your shelf is missing Season 4');
    // Not playable, so the one action is Details to the title page.
    const actions = hero.locator('.hero-actions a');
    await expect(actions).toHaveCount(1);
    await expect(actions.first()).toContainText('Details');
    await expect(actions.first()).toHaveAttribute('href', '/title/401');
  });

  test('promo box carries the page heading and opens the wish list', async ({ page }) => {
    await openDiscover(page, 'populated');
    await expect(page.locator('app-discover h1')).toHaveText('Find Something New To Watch');
    const pill = page.locator('app-discover .promo a.btn');
    await expect(pill).toHaveText('Open My Wish List');
    await expect(pill).toHaveAttribute('href', '/wishlist');
    await pill.click();
    await expectView(page, /\/wishlist$/, 'app-wishlist');
  });

  test('New Seasons Out row has one wide card per missing-seasons show', async ({ page }) => {
    await openDiscover(page, 'populated');
    const section = page.locator('app-discover .new-seasons');
    await expect(section.locator('h2')).toHaveText('New Seasons Out');
    const cards = section.locator('app-wide-card');
    await expect(cards).toHaveCount(1);
    await expect(cards.first().locator('.caption .t')).toHaveText('Breaking Bad');
    await expect(cards.first().locator('.caption .m.accent')).toHaveText('Season 4 available');
    await expect(cards.first().locator('a.card-link')).toHaveAttribute('href', '/title/401');
  });

  test('an empty home feed leaves out the hero and the row but keeps the promo', async ({ page }) => {
    await openDiscover(page, 'empty');
    await expect(page.locator('app-discover .promo h1')).toBeVisible();
    await expect(page.locator('app-discover .hero-section')).toHaveCount(0);
    await expect(page.locator('app-discover .new-seasons')).toHaveCount(0);
    await expect(page.locator('app-discover .author-books')).toHaveCount(0);
    // The artist recommendations still render below.
    await expect(page.locator('app-discover .rec-card')).toHaveCount(2);
  });
});

// Discover rows (docs/plans/chain-live.md row 04): books from the home
// feed's recently added books, and the most popular movies from
// CatalogService.ListTitles.
test.describe('discover rows', () => {
  async function openDiscover(page: Page): Promise<void> {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('aside.side-nav a[href="/discover"]');
    await page.locator('aside.side-nav a[href="/discover"]').click();
    await page.waitForSelector('app-discover .promo');
  }

  test('More From Authors You Read shows poster cards from recently added books', async ({ page }) => {
    await openDiscover(page);
    const section = page.locator('app-discover .author-books');
    await expect(section.locator('h2')).toHaveText('More From Authors You Read');
    await expect(section.locator('.section-head .tag')).toContainText('Books');
    const cards = section.locator('ul[aria-label="More From Authors You Read"] li app-poster-card');
    await expect(cards).toHaveCount(1);
    await expect(cards.first().locator('.poster-title')).toHaveText('Dune');
    await expect(cards.first().locator('.poster-credit')).toHaveText('Frank Herbert');
    await expect(cards.first().locator('.poster-meta')).toHaveText('1965');
    await expect(cards.first().locator('a.card-link')).toHaveAttribute('href', '/title/201');
  });

  test('Popular In Your Library lists movies sorted by popularity', async ({ page }) => {
    // Other pages may list titles too; pick the popularity request.
    const decodeList = (buf: Buffer | null) =>
      fromBinary(ListTitlesRequestSchema, unframeGrpcWebRequest(buf));
    const listed = page.waitForRequest(req =>
      req.url().endsWith('/mediamanager.CatalogService/ListTitles') &&
      decodeList(req.postDataBuffer()).sort === 'popular',
    );
    await openDiscover(page);
    const decoded = decodeList((await listed).postDataBuffer());
    expect(decoded.type).toBe(MediaType.MOVIE);
    expect(decoded.sort).toBe('popular');
    expect(decoded.limit).toBe(18);

    const section = page.locator('app-discover .popular');
    await expect(section.locator('a.see-all')).toHaveAttribute('href', '/content/movies');
    await expect(section.locator('h2')).toHaveText('Popular In Your Library');
    const cards = section.locator('ul[aria-label="Popular In Your Library"] li app-poster-card');
    await expect(cards).toHaveCount(4);
    await expect(cards.first().locator('.poster-title')).toHaveText('The Matrix');
    await expect(cards.first().locator('.poster-meta')).toHaveText('1999');
    await expect(cards.first().locator('a.card-link')).toHaveAttribute('href', '/title/100');
  });

  test('rows come after the frame in page order', async ({ page }) => {
    await openDiscover(page);
    await page.waitForSelector('app-discover .popular');
    const order = await page.locator('app-discover .discover-page > section').evaluateAll(
      els => els.map(e => e.className.split(' ').find(c => c !== 'carousel-section') ?? ''),
    );
    expect(order).toEqual([
      'hero-section', 'promo', 'new-seasons', 'author-books', 'artists-section', 'popular',
    ]);
  });
});
