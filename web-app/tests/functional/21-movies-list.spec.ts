import { test, expect, Page, Request } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { fromBinary } from '@bufbuild/protobuf';
import { ListTitlesRequestSchema } from '../../src/app/proto-gen/catalog_pb';

const LIST_TITLES_URL = '/mediamanager.CatalogService/ListTitles';

function decodeListTitles(req: Request): { sort: string; ratings: string[]; playableOnly: boolean } {
  const decoded = fromBinary(
    ListTitlesRequestSchema,
    unframeGrpcWebRequest(req.postDataBuffer()),
  );
  return { sort: decoded.sort, ratings: decoded.ratings, playableOnly: decoded.playableOnly };
}

// Movies list view tests.
//
// /content/movies mounts <app-title-grid mediaType="MOVIE">. The
// title-grid filter wiring is shared with /content/books, /content/tv,
// /content/music and /content/family — the books spec
// (15-books-and-reader) already covers the generic chip / sort / URL-
// param plumbing. This spec adds the movies-specific bits:
//   - Popular sort option (replaces Author from books)
//   - Rating chips (G, PG, PG-13, R) which only appear for video
//     mediaTypes
//
// Actual movie playback isn't re-covered here. The /play/:transcodeId
// route is media-type agnostic; 13-player-playback exercises the full
// HTMLMediaElement pipeline (currentTime, subs, scrub preview) against
// a generic video transcode and 14-player-resume covers the resume
// prompt. Both apply to movies as much as TV episodes.
//
// Fixture: catalog/titles.movies.json — 4 movies (Matrix, Inception,
// Interstellar, Blade Runner 2049). available_ratings: G, PG, PG-13,
// R. Inception has progress_fraction=0.34. Blade Runner 2049 has
// no poster + isn't playable.

function captureTitlesRequests(page: Page) {
  const urls: string[] = [];
  page.on('request', req => {
    if (req.url().endsWith(LIST_TITLES_URL)) urls.push(req.url());
  });
  return () => urls;
}

test.describe('movies list view', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/content/movies');
    await page.waitForSelector('app-title-grid .poster-card');
  });

  // -------- Display --------

  test('renders all 4 movies with title + release year', async ({ page }) => {
    const cards = page.locator('app-title-grid .poster-card');
    await expect(cards).toHaveCount(4);
    await expect(cards.first().locator('.poster-title')).toContainText('The Matrix');
    await expect(cards.first().locator('.poster-meta')).toContainText('1999');
    await expect(cards.nth(1).locator('.poster-title')).toContainText('Inception');
    await expect(cards.nth(1).locator('.poster-meta')).toContainText('2010');
    await expect(cards.nth(2).locator('.poster-title')).toContainText('Interstellar');
    await expect(cards.nth(3).locator('.poster-title')).toContainText('Blade Runner 2049');
  });

  test('total label reflects movie count', async ({ page }) => {
    await expect(page.locator('app-title-grid .status-label')).toContainText('4 movies');
  });

  test('hero image renders when poster_url is set, placeholder otherwise', async ({ page }) => {
    const cards = page.locator('app-title-grid .poster-card');
    // Matrix has poster_url
    await expect(cards.first().locator('img.poster-img')).toBeVisible();
    await expect(cards.first().locator('img.poster-img'))
      .toHaveAttribute('src', /\/posters\/w185\/100$/);
    // Blade Runner 2049 has poster_url=null → placeholder div, no <img>
    await expect(cards.nth(3).locator('img.poster-img')).toHaveCount(0);
    await expect(cards.nth(3).locator('.poster-placeholder')).toBeVisible();
  });

  test('playable badge shows only on playable titles', async ({ page }) => {
    const cards = page.locator('app-title-grid .poster-card');
    // Matrix, Inception, Interstellar are playable.
    await expect(cards.first().locator('.playable-badge')).toBeVisible();
    await expect(cards.nth(1).locator('.playable-badge')).toBeVisible();
    await expect(cards.nth(2).locator('.playable-badge')).toBeVisible();
    // Blade Runner 2049 is not.
    await expect(cards.nth(3).locator('.playable-badge')).toHaveCount(0);
  });

  test('progress overlay renders on a partly-watched movie', async ({ page }) => {
    // Inception has progress_fraction=0.34.
    await expect(page.locator('app-title-grid .poster-card').nth(1).locator('.progress-fill'))
      .toHaveAttribute('style', /width:\s*34%/);
  });

  test('clicking a movie navigates to /title/:id', async ({ page }) => {
    await page.locator('app-title-grid .poster-card').first().click();
    await expect(page).toHaveURL(/\/title\/100$/);
  });

  // -------- Sort chips: movies-specific Popular option --------

  test('sort chips include the movies-specific Popular option', async ({ page }) => {
    // Books would show "Author" instead of "Popular". Movies show
    // Name, Year, Recent, Popular.
    for (const label of ['Name', 'Year', 'Recent', 'Popular']) {
      await expect(page.locator('mat-chip', { hasText: label })).toBeVisible();
    }
    // And explicitly NOT "Author".
    await expect(page.locator('mat-chip', { hasText: /^\s*Author\s*$/ })).toHaveCount(0);
  });

  test('clicking each sort chip fires a ListTitles request with the right sort', async ({ page }) => {
    // ListTitles is a gRPC POST; the sort field rides in the binary
    // request body, so we decode the proto to assert on it.
    for (const { label, sort } of [
      { label: 'Year',    sort: 'year' },
      { label: 'Recent',  sort: 'recent' },
      { label: 'Popular', sort: 'popular' },
      { label: 'Name',    sort: 'name' },
    ]) {
      const reqPromise = page.waitForRequest(req =>
        req.url().endsWith(LIST_TITLES_URL),
        { timeout: 3_000 },
      );
      await page.locator('mat-chip', { hasText: label }).click();
      const got = await reqPromise;
      expect(decodeListTitles(got).sort).toBe(sort);
    }
  });

  // -------- Filter chips: Playable + ratings --------

  test('Playable chip starts highlighted (default true) — toggling off sends playableOnly=false', async ({ page }) => {
    const playable = page.locator('mat-chip', { hasText: 'Playable' }).first();
    await expect(playable).toHaveClass(/mat-mdc-chip-highlighted/);

    const reqPromise = page.waitForRequest(req =>
      req.url().endsWith(LIST_TITLES_URL),
      { timeout: 3_000 },
    );
    await playable.click();
    const got = await reqPromise;
    expect(decodeListTitles(got).playableOnly).toBe(false);
    await expect(playable).not.toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('rating chips render from available_ratings + All Ratings is selected by default', async ({ page }) => {
    // available_ratings is ["G", "PG", "PG-13", "R"]; "All Ratings"
    // is the synthetic clear-all chip. Use anchored regex / getByText
    // exact-match so "G" doesn't match "PG" / "PG-13" by substring.
    await expect(page.locator('mat-chip', { hasText: 'All Ratings' })).toBeVisible();
    await expect(page.getByText('G', { exact: true })).toBeVisible();
    await expect(page.getByText('PG', { exact: true })).toBeVisible();
    await expect(page.getByText('PG-13', { exact: true })).toBeVisible();
    await expect(page.getByText('R', { exact: true })).toBeVisible();
    // No selectedRatings → All Ratings is highlighted.
    await expect(page.locator('mat-chip', { hasText: 'All Ratings' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('clicking a rating chip sends ratings=[rating] in the request', async ({ page }) => {
    const reqPromise = page.waitForRequest(req =>
      req.url().endsWith(LIST_TITLES_URL),
      { timeout: 3_000 },
    );
    await page.locator('mat-chip', { hasText: 'PG-13' }).click();
    const got = await reqPromise;
    expect(decodeListTitles(got).ratings).toEqual(['PG-13']);
    // PG-13 chip is now highlighted; All Ratings is not.
    await expect(page.locator('mat-chip', { hasText: 'PG-13' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
    await expect(page.locator('mat-chip', { hasText: 'All Ratings' }))
      .not.toHaveClass(/mat-mdc-chip-highlighted/);
  });

  test('All Ratings clears the rating filter', async ({ page }) => {
    // First select PG-13 so there's something to clear.
    let pending = page.waitForRequest(req =>
      req.url().endsWith(LIST_TITLES_URL),
      { timeout: 3_000 },
    );
    await page.locator('mat-chip', { hasText: 'PG-13' }).click();
    await pending;

    // Click All Ratings — request should ship an empty ratings list.
    const cleared = page.waitForRequest(req =>
      req.url().endsWith(LIST_TITLES_URL),
      { timeout: 3_000 },
    );
    await page.locator('mat-chip', { hasText: 'All Ratings' }).click();
    const got = await cleared;
    expect(decodeListTitles(got).ratings).toEqual([]);
    await expect(page.locator('mat-chip', { hasText: 'All Ratings' }))
      .toHaveClass(/mat-mdc-chip-highlighted/);
  });
});
