import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto, unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { clone, fromBinary } from '@bufbuild/protobuf';
import { CollectionDetailSchema, MediaType } from '../../src/app/proto-gen/common_pb';
import { AddWishRequestSchema } from '../../src/app/proto-gen/wishlist_pb';
import { expectView } from '../helpers/expect-view';
import { collectionDetail2344 } from '../fixtures-typed/collections.fixture';

// Collections list + collection detail tests.
//
// Fixture chain (gRPC):
//   ListCollections        → fixtures-typed/collections.fixture.ts
//                            (3 collections: Dark Knight=263, Matrix=2344,
//                            Star Wars=10)
//   GetCollectionDetail/:id → fixtures-typed/collections.fixture.ts
//                            (The Matrix Collection — 2 of 4 owned, 2
//                            unowned with TMDB ids; one wished, one not)
//
// Route IDs are the tmdb_collection_id (the gRPC GetCollectionDetail
// RPC takes that). The unowned-with-tmdb_movie_id parts are what
// surface the wish heart; the owned parts are anchor links to /title/:id.

test.describe('collections list', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/collections');
    await page.waitForSelector('app-collections .grid');
  });

  test('renders one tile per collection with name + count badge', async ({ page }) => {
    const cards = page.locator('app-collections .poster-card');
    await expect(cards).toHaveCount(3);

    // Card 1: Dark Knight Trilogy, 3/3 (complete).
    await expect(cards.first().locator('.poster-title')).toContainText('The Dark Knight Trilogy');
    await expect(cards.first().locator('.count-badge')).toContainText('3 / 3');

    // Card 2: Matrix Collection, 2/4 (the partial one our tests pivot on).
    await expect(cards.nth(1).locator('.poster-title')).toContainText('The Matrix Collection');
    await expect(cards.nth(1).locator('.count-badge')).toContainText('2 / 4');

    // Card 3: Star Wars, 5/11, no poster (placeholder).
    await expect(cards.nth(2).locator('.poster-title')).toContainText('Star Wars');
    await expect(cards.nth(2).locator('.count-badge')).toContainText('5 / 11');
    await expect(cards.nth(2).locator('.poster-placeholder')).toBeVisible();
    await expect(cards.nth(2).locator('img.poster-img')).toHaveCount(0);
  });

  test('total label reflects collection count', async ({ page }) => {
    await expect(page.locator('app-collections .status-label')).toContainText('3 collections');
  });

  test('clicking a tile navigates to /content/collection/:tmdb_collection_id', async ({ page }) => {
    await page.locator('app-collections .poster-card').nth(1).click();
    await expectView(page, /\/content\/collection\/2344$/, 'app-collection-detail');
  });
});

test.describe('collection detail', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    // Any tmdb_collection_id falls through to the same Matrix fixture
    // — mock-backend's dispatch ignores the id for now.
    await page.goto('/content/collection/2344');
    await page.waitForSelector('app-collection-detail .grid');
  });

  test('title, count, and grid all render', async ({ page }) => {
    await expect(page.locator('app-collection-detail h2.collection-title'))
      .toContainText('The Matrix Collection');
    await expect(page.locator('app-collection-detail .status-label'))
      .toContainText('2 of 4 titles in your collection');
    await expect(page.locator('app-collection-detail .poster-card')).toHaveCount(4);
  });

  test('owned parts render as anchor links to /title/:id', async ({ page }) => {
    // First two parts are owned (anchors). Third + fourth are unowned
    // (divs). Use the .unowned class as the discriminator.
    const owned = page.locator('app-collection-detail .poster-card:not(.unowned)');
    await expect(owned).toHaveCount(2);

    const first = owned.first();
    await expect(first.locator('.poster-title')).toContainText('The Matrix');
    await expect(first.locator('.poster-meta')).toContainText('1999');
    await expect(first.locator('a.card-link')).toHaveAttribute('href', /\/title\/500$/);
    await expect(first.locator('img.poster-img')).toBeVisible();
    // Playable badge present on the playable part.
    await expect(first.locator('.playable-badge')).toBeVisible();
  });

  test('progress overlay renders on a partly-watched owned part', async ({ page }) => {
    // The Matrix Reloaded has progress_fraction=0.42 and is NOT
    // playable — the progress bar should still show because
    // progress_fraction is truthy.
    const reloaded = page.locator('app-collection-detail .poster-card:not(.unowned)', { hasText: 'The Matrix Reloaded' });
    await expect(reloaded.locator('.progress-fill')).toHaveAttribute('style', /width:\s*42%/);
    // Not playable on this part — no play badge.
    await expect(reloaded.locator('.playable-badge')).toHaveCount(0);
  });

  test('unowned parts are dimmed and surface the wish heart', async ({ page }) => {
    const unowned = page.locator('app-collection-detail .poster-card.unowned');
    await expect(unowned).toHaveCount(2);

    // Both say so in the caption, under the title, like Similar Titles.
    await expect(unowned.first().locator('.poster-subtitle')).toHaveText('Not owned');
    await expect(unowned.nth(1).locator('.poster-subtitle')).toHaveText('Not owned');
    await expect(page.locator('app-collection-detail .not-owned-label')).toHaveCount(0);

    // First unowned part (Revolutions) is NOT wished → outlined heart,
    // aria-label asks to add.
    const heart1 = unowned.first().locator('button.wish-heart');
    await expect(heart1).toBeVisible();
    await expect(heart1).not.toHaveClass(/wished/);
    await expect(heart1).toHaveAttribute('aria-label', 'Add to wish list');

    // Second unowned part (Resurrections) IS wished → filled heart,
    // aria-label asks to remove.
    const heart2 = unowned.nth(1).locator('button.wish-heart');
    await expect(heart2).toHaveClass(/wished/);
    await expect(heart2).toHaveAttribute('aria-label', 'Remove from wish list');
  });

  test('release year shows on every part', async ({ page }) => {
    const cards = page.locator('app-collection-detail .poster-card');
    await expect(cards).toHaveCount(4);
    for (const expected of ['1999', '2003', '2003', '2021']) {
      // Use a content match across the row of cards rather than
      // indexing — we just need to know each year appears once.
      await expect(page.locator(`app-collection-detail .poster-meta`, { hasText: expected }).first())
        .toBeVisible();
    }
  });

  test('clicking a heart on an unwished part fires POST /wishlist/add', async ({ page }) => {
    // Acknowledge the interstitial proactively so toggleWish() doesn't
    // pop the confirm() dialog. The mockBackend's catalog/wishlist.json
    // has has_any_media_wish=true which already short-circuits the
    // interstitial, but listen for the prompt anyway as defence.
    page.on('dialog', d => d.accept());

    const posted = page.waitForRequest(req =>
      req.url().endsWith('/mediamanager.WishListService/AddWish'),
    );

    // First unowned (Revolutions, tmdb_movie_id 605, not wished).
    await page.locator('app-collection-detail .poster-card.unowned button.wish-heart').first().click();
    const req = await posted;
    const decoded = fromBinary(
      AddWishRequestSchema,
      unframeGrpcWebRequest(req.postDataBuffer()),
    );
    expect(decoded.tmdbId).toBe(605);
    expect(decoded.mediaType).toBe(MediaType.MOVIE);
    expect(decoded.title).toBe('The Matrix Revolutions');
    expect(decoded.releaseYear).toBe(2003);
  });

  test('clicking the title text on an owned part navigates to /title/:id', async ({ page }) => {
    // The .poster-title sits inside the anchor, so a click anywhere
    // inside the card routes. Verify by clicking the text label
    // specifically rather than the whole card.
    await page.locator('app-collection-detail .poster-card:not(.unowned) .poster-title', { hasText: 'The Matrix' })
      .first().click();
    await expectView(page, /\/title\/500$/, 'app-title-detail');
  });
});

// The server names a not-owned part's poster by its TMDB URL
// (https://image.tmdb.org/t/p/w500/<file>). The SPA makes no third-party
// requests, and the CSP's img-src blocks that host, so the card must point
// at the local proxy for the same file instead.
test.describe('collection detail artwork', () => {
  const TMDB_FILE = 'revolutions-poster.jpg';

  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    const detail = clone(CollectionDetailSchema, collectionDetail2344);
    const revolutions = detail.items.find(i => i.tmdbMovieId === 605)!;
    revolutions.posterUrl = `https://image.tmdb.org/t/p/w500/${TMDB_FILE}`;
    await page.route('**/mediamanager.CatalogService/GetCollectionDetail',
      r => fulfillProto(r, CollectionDetailSchema, detail));
    await page.goto('/content/collection/2344');
    await page.waitForSelector('app-collection-detail .grid');
  });

  test('a not-owned part with a TMDB poster loads it through the local proxy', async ({ page }) => {
    const revolutions = page.locator('app-collection-detail .poster-card.unowned', { hasText: 'The Matrix Revolutions' });
    const img = revolutions.locator('img.poster-img');
    await expect(img).toHaveAttribute('src', `/proxy/tmdb/w500/${TMDB_FILE}`);
    await expect(img).toBeVisible();
  });

  test('a not-owned part without a poster keeps the placeholder', async ({ page }) => {
    const resurrections = page.locator('app-collection-detail .poster-card.unowned', { hasText: 'The Matrix Resurrections' });
    await expect(resurrections.locator('.poster-placeholder')).toBeVisible();
    await expect(resurrections.locator('img.poster-img')).toHaveCount(0);
  });
});
