import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// TV shows list view tests.
//
// /content/tv mounts <app-tv-shows>, which is a thin wrapper around
// <app-title-grid mediaType="TV" label="TV shows" />. Filter / sort
// plumbing is shared with /content/movies and /content/books — the
// movies spec (21-movies-list) covers the generic chip / sort / URL-
// param machinery against MOVIE titles. This spec exercises the
// TV-specific bits:
//   - the "TV shows" label on the status chip
//   - rating chips populated from TV ratings (TV-G, TV-PG, TV-14, TV-MA)
//   - that the title-grid hits the catalog endpoint with media_type=TV
//
// Accessibility coverage for /content/tv lives in
// axe/09-browse-grids.spec.ts (viewer scope, light/dark × desktop/mobile).
//
// Fixture: catalog/titles.tv.json — 3 TV titles (Breaking Bad with
// progress 0.62, The Expanse, Severance). available_ratings: TV-G,
// TV-PG, TV-14, TV-MA. All three are playable.

test.describe('TV shows list view', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/tv');
    await page.waitForSelector('app-title-grid .poster-card');
  });

  // -------- Display --------

  test('renders all 3 TV shows with title + release year', async ({ page }) => {
    const cards = page.locator('app-title-grid .poster-card');
    await expect(cards).toHaveCount(3);
    await expect(cards.nth(0).locator('.poster-title')).toContainText('Breaking Bad');
    await expect(cards.nth(0).locator('.poster-meta')).toContainText('2008');
    await expect(cards.nth(1).locator('.poster-title')).toContainText('The Expanse');
    await expect(cards.nth(1).locator('.poster-meta')).toContainText('2015');
    await expect(cards.nth(2).locator('.poster-title')).toContainText('Severance');
    await expect(cards.nth(2).locator('.poster-meta')).toContainText('2022');
  });

  test('total label reflects TV-show count + the configured "TV shows" label', async ({ page }) => {
    // <app-tv-shows> passes label="TV shows" to title-grid; the status
    // label is "<n> <label>" so the singular/plural decision the grid
    // makes for movies/books doesn't apply here — it's whatever string
    // got handed in.
    await expect(page.locator('app-title-grid .status-label')).toContainText('3 TV shows');
  });

  test('hero image renders from poster_url for every fixture row', async ({ page }) => {
    const cards = page.locator('app-title-grid .poster-card');
    await expect(cards.nth(0).locator('img.poster-img'))
      .toHaveAttribute('src', /\/posters\/w185\/200$/);
    await expect(cards.nth(1).locator('img.poster-img'))
      .toHaveAttribute('src', /\/posters\/w185\/201$/);
    await expect(cards.nth(2).locator('img.poster-img'))
      .toHaveAttribute('src', /\/posters\/w185\/202$/);
  });

  test('playable badge renders on every playable show', async ({ page }) => {
    const cards = page.locator('app-title-grid .poster-card');
    // All three TV fixture rows are playable.
    await expect(cards.nth(0).locator('.playable-badge')).toBeVisible();
    await expect(cards.nth(1).locator('.playable-badge')).toBeVisible();
    await expect(cards.nth(2).locator('.playable-badge')).toBeVisible();
  });

  test('progress overlay renders on a partly-watched show', async ({ page }) => {
    // Breaking Bad has progress_fraction=0.62.
    await expect(page.locator('app-title-grid .poster-card').nth(0).locator('.progress-fill'))
      .toHaveAttribute('style', /width:\s*62%/);
    // The other two have progress_fraction=null → no progress overlay.
    await expect(page.locator('app-title-grid .poster-card').nth(1).locator('.progress-fill'))
      .toHaveCount(0);
  });

  test('clicking a show navigates to /title/:id', async ({ page }) => {
    await page.locator('app-title-grid .poster-card').first().click();
    await expect(page).toHaveURL(/\/title\/200$/);
  });

  test('initial GET fires with media_type=TV', async ({ page }) => {
    // beforeEach already triggered the initial load, but Playwright's
    // request listener registered after goto won't see it. Trip a sort
    // change to fire a fresh request and assert on its query string.
    const reqPromise = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/titles')
        && req.url().includes('media_type=TV'),
      { timeout: 3_000 },
    );
    await page.locator('mat-chip', { hasText: 'Year' }).click();
    await reqPromise;
  });

  // -------- Sort chips --------

  test('sort chips include the same Movie set (Name / Year / Recent / Popular)', async ({ page }) => {
    // TV shares the default branch with movies — author/artist replacements
    // are book/album-only.
    for (const label of ['Name', 'Year', 'Recent', 'Popular']) {
      await expect(page.locator('mat-chip', { hasText: label })).toBeVisible();
    }
    // And explicitly NOT the book/album-only chips.
    await expect(page.locator('mat-chip', { hasText: /^\s*Author\s*$/ })).toHaveCount(0);
    await expect(page.locator('mat-chip', { hasText: /^\s*Artist\s*$/ })).toHaveCount(0);
  });

  test('clicking each sort chip fires a request with the right sort=<mode>', async ({ page }) => {
    for (const { label, param } of [
      { label: 'Year',    param: 'sort=year' },
      { label: 'Recent',  param: 'sort=recent' },
      { label: 'Popular', param: 'sort=popular' },
      { label: 'Name',    param: 'sort=name' },
    ]) {
      const reqPromise = page.waitForRequest(req =>
        req.url().includes('/api/v2/catalog/titles')
          && req.url().includes(param)
          && req.url().includes('media_type=TV'),
        { timeout: 3_000 },
      );
      await page.locator('mat-chip', { hasText: label }).click();
      await reqPromise;
    }
  });

  // -------- Filter chips: Playable + ratings --------

  test('Playable chip starts highlighted (default true) — toggling off adds playable_only=false', async ({ page }) => {
    const playable = page.locator('mat-chip', { hasText: 'Playable' }).first();
    await expect(playable).toHaveClass(/mat-mdc-chip-highlighted/);

    const reqPromise = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/titles')
        && req.url().includes('playable_only=false')
        && req.url().includes('media_type=TV'),
      { timeout: 3_000 },
    );
    await playable.click();
    await reqPromise;
    await expect(playable).not.toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('rating chips render from TV ratings + All Ratings is selected by default', async ({ page }) => {
    // available_ratings is ["TV-G", "TV-PG", "TV-14", "TV-MA"]. Use
    // anchored exact-match on each pill so "TV-14" doesn't match
    // "TV-MA" by substring.
    await expect(page.locator('mat-chip', { hasText: 'All Ratings' })).toBeVisible();
    await expect(page.getByText('TV-G',  { exact: true })).toBeVisible();
    await expect(page.getByText('TV-PG', { exact: true })).toBeVisible();
    await expect(page.getByText('TV-14', { exact: true })).toBeVisible();
    await expect(page.getByText('TV-MA', { exact: true })).toBeVisible();
    await expect(page.locator('mat-chip', { hasText: 'All Ratings' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('clicking a rating chip adds ratings=TV-MA to the URL', async ({ page }) => {
    const reqPromise = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/titles')
        && /ratings=TV-MA/.test(req.url())
        && req.url().includes('media_type=TV'),
      { timeout: 3_000 },
    );
    await page.locator('mat-chip', { hasText: 'TV-MA' }).click();
    await reqPromise;
    await expect(page.locator('mat-chip', { hasText: 'TV-MA' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
    await expect(page.locator('mat-chip', { hasText: 'All Ratings' }))
      .not.toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('All Ratings clears the rating filter', async ({ page }) => {
    let pending = page.waitForRequest(req =>
      req.url().includes('/api/v2/catalog/titles') && /ratings=TV-MA/.test(req.url()),
      { timeout: 3_000 },
    );
    await page.locator('mat-chip', { hasText: 'TV-MA' }).click();
    await pending;

    const cleared = page.waitForRequest(req => {
      if (!req.url().includes('/api/v2/catalog/titles')) return false;
      return !new URL(req.url()).searchParams.has('ratings');
    }, { timeout: 3_000 });
    await page.locator('mat-chip', { hasText: 'All Ratings' }).click();
    await cleared;
    await expect(page.locator('mat-chip', { hasText: 'All Ratings' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
  });
});
