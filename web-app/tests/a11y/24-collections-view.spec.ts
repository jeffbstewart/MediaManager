import { test, expect } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Collections list + collection detail tests.
//
// Fixture chain:
//   /api/v2/catalog/collections        → catalog/collections.list.json
//                                        (3 collections: Dark Knight,
//                                        Matrix, Star Wars)
//   /api/v2/catalog/collections/:id    → catalog/collection-detail.json
//                                        (The Matrix Collection — 2 of
//                                        4 owned, 2 unowned with TMDB
//                                        ids; one wished, one not)
//
// The unowned-with-tmdb_movie_id parts are what surface the wish
// heart; the owned parts are anchor links to /title/:id.

test.describe('collections list', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/collections');
    await page.waitForSelector('app-collections .poster-grid');
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

  test('clicking a tile navigates to /content/collection/:id', async ({ page }) => {
    await page.locator('app-collections .poster-card').nth(1).click();
    await expect(page).toHaveURL(/\/content\/collection\/2$/);
  });
});

test.describe('collection detail', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/collection/1');
    await page.waitForSelector('app-collection-detail .poster-grid');
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
    const owned = page.locator('app-collection-detail a.poster-card');
    await expect(owned).toHaveCount(2);

    const first = owned.first();
    await expect(first.locator('.poster-title')).toContainText('The Matrix');
    await expect(first.locator('.poster-meta')).toContainText('1999');
    await expect(first).toHaveAttribute('href', /\/title\/500$/);
    await expect(first.locator('img.poster-img')).toBeVisible();
    // Playable badge present on the playable part.
    await expect(first.locator('.playable-badge')).toBeVisible();
  });

  test('progress overlay renders on a partly-watched owned part', async ({ page }) => {
    // The Matrix Reloaded has progress_fraction=0.42 and is NOT
    // playable — the progress bar should still show because
    // progress_fraction is truthy.
    const reloaded = page.locator('app-collection-detail a.poster-card', { hasText: 'The Matrix Reloaded' });
    await expect(reloaded.locator('.progress-fill')).toHaveAttribute('style', /width:\s*42%/);
    // Not playable on this part — no play badge.
    await expect(reloaded.locator('.playable-badge')).toHaveCount(0);
  });

  test('unowned parts are dimmed and surface the wish heart', async ({ page }) => {
    const unowned = page.locator('app-collection-detail .poster-card.unowned');
    await expect(unowned).toHaveCount(2);

    // Both have no poster → "Not Owned" placeholder text.
    await expect(unowned.first().locator('.not-owned-label')).toContainText('Not Owned');

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
      req.method() === 'POST' && req.url().endsWith('/api/v2/wishlist/add'),
    );

    // First unowned (Revolutions, tmdb_movie_id 605, not wished).
    await page.locator('app-collection-detail .poster-card.unowned button.wish-heart').first().click();
    const req = await posted;
    expect(req.postDataJSON()).toMatchObject({
      tmdb_id: 605,
      media_type: 'MOVIE',
      title: 'The Matrix Revolutions',
      release_year: 2003,
    });
  });

  test('clicking the title text on an owned part navigates to /title/:id', async ({ page }) => {
    // The .poster-title sits inside the anchor, so a click anywhere
    // inside the card routes. Verify by clicking the text label
    // specifically rather than the whole card.
    await page.locator('app-collection-detail a.poster-card .poster-title', { hasText: 'The Matrix' })
      .first().click();
    await expect(page).toHaveURL(/\/title\/500$/);
  });
});
