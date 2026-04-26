import { test, expect } from '@playwright/test';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// Discover feed (M8) tests. The page reads
// /api/v2/recommendations/artists, renders one card per recommended
// artist, and exposes three actions per card / page:
//   - Wishlist (POST /api/v2/wishlist/albums)
//   - Dismiss  (POST /api/v2/recommendations/dismiss)
//   - Refresh  (POST /api/v2/recommendations/refresh, then re-load)
//
// The page is gated on has_music_radio. mockBackend's default viewer
// features fixture has it on, so no override needed unless we want
// to test the gated-off explainer state (out of scope here).

test.describe('discover feed', () => {

  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    // Stub the two write endpoints the page posts to. Both return
    // 204 so the catalog service's awaited promises resolve.
    await page.route('**/api/v2/recommendations/dismiss', route =>
      route.fulfill({ status: 204 }),
    );
    await page.route('**/api/v2/recommendations/refresh', route =>
      route.fulfill({ status: 204 }),
    );
    await page.route('**/api/v2/wishlist/albums', route =>
      route.fulfill({ status: 204 }),
    );
    // Warm up FeatureService before mounting the discover component.
    // discover.ngOnInit reads features.hasMusicRadio() synchronously
    // and bails to the "needs Last.fm" empty state if the flag is
    // false. On a direct page.goto('/discover') the shell's async
    // getFeatures() race-loses to the discover mount, so we land on
    // /  first (features populate via shell + home), then in-app
    // navigate to /discover with FeatureService already hydrated.
    await page.goto('/');
    await page.waitForSelector('mat-sidenav a[href="/discover"]');
    await page.locator('mat-sidenav a[href="/discover"]').click();
    await page.waitForSelector('app-discover .card-grid');
  });

  test('renders one card per recommended artist with name + album hint', async ({ page }) => {
    const cards = page.locator('app-discover .rec-card');
    await expect(cards).toHaveCount(2);

    await expect(cards.first().locator('.artist-name')).toContainText('Bill Evans');
    await expect(cards.first().locator('.album-hint')).toContainText(
      'Start with Sunday at the Village Vanguard',
    );

    await expect(cards.nth(1).locator('.artist-name')).toContainText('John Coltrane');
    await expect(cards.nth(1).locator('.album-hint')).toContainText(
      'Start with A Love Supreme',
    );
  });

  test('voter lines render with names from the fixture', async ({ page }) => {
    // Single voter → "because you have NAME"
    await expect(page.locator('app-discover .rec-card').first().locator('.voter-line'))
      .toContainText('because you have Miles Davis');
    // Three voters → comma-and formatting via voterLine()
    await expect(page.locator('app-discover .rec-card').nth(1).locator('.voter-line'))
      .toContainText('because you have Miles Davis, Bill Evans, and Thelonious Monk');
  });

  test('cover image shows when cover_url is set', async ({ page }) => {
    const cover = page.locator('app-discover .rec-card').first().locator('img.cover');
    await expect(cover).toBeVisible();
    await expect(cover).toHaveAttribute('src', /\/posters\/w185\/400$/);
  });

  test('Wishlist click POSTs the right body and flips the button', async ({ page }) => {
    const card = page.locator('app-discover .rec-card').first();
    const wishBtn = card.locator('button.wish-btn');
    await expect(wishBtn).toContainText('Wishlist');

    const posted = page.waitForRequest(req =>
      req.method() === 'POST' && req.url().endsWith('/api/v2/wishlist/albums'),
    );
    await wishBtn.click();
    const req = await posted;
    expect(req.postDataJSON()).toEqual({
      release_group_id: 'rg-1',
      title: 'Sunday at the Village Vanguard',
      primary_artist: 'Bill Evans',
    });

    // Label flips to "Wishlisted" + the button becomes disabled so
    // double-click can't double-add.
    await expect(wishBtn).toContainText('Wishlisted');
    await expect(wishBtn).toBeDisabled();
  });

  test('Dismiss click POSTs the mbid and removes the card', async ({ page }) => {
    const cards = page.locator('app-discover .rec-card');
    await expect(cards).toHaveCount(2);

    const posted = page.waitForRequest(req =>
      req.method() === 'POST' && req.url().endsWith('/api/v2/recommendations/dismiss'),
    );
    // Dismiss the FIRST card (Bill Evans, mbid abc-1).
    await cards.first().locator('button.dismiss-btn').click();
    const req = await posted;
    expect(req.postDataJSON()).toEqual({ suggested_artist_mbid: 'abc-1' });

    // Local state filter drops the dismissed row; only Coltrane left.
    await expect(cards).toHaveCount(1);
    await expect(cards.first().locator('.artist-name')).toContainText('John Coltrane');
  });

  test('Refresh now POSTs /refresh and shows the in-flight state', async ({ page }) => {
    const refreshBtn = page.locator('app-discover button.refresh-btn');
    await expect(refreshBtn).toContainText('Refresh now');
    await expect(refreshBtn).not.toBeDisabled();

    const posted = page.waitForRequest(req =>
      req.method() === 'POST' && req.url().endsWith('/api/v2/recommendations/refresh'),
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
